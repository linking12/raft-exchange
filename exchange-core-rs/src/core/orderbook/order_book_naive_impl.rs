use std::collections::BTreeMap;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::l2_market_data::L2MarketData;
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::matcher_event_type::MatcherEventType;
use crate::core::common::matcher_trade_event::MatcherTradeEvent;
use crate::core::common::order::Order;
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;
use crate::core::orderbook::orders_bucket_naive::MakerFill;
use crate::core::common::symbol_type::SymbolType;
use crate::core::orderbook::i_order_book::IOrderBook;
use crate::core::orderbook::orders_bucket_naive::OrdersBucketNaive;
use crate::core::utils::core_arithmetic_utils::{add_exact, mul_exact, sub_exact};

pub struct OrderBookNaiveImpl {
    ask_buckets: BTreeMap<i64, OrdersBucketNaive>,
    bid_buckets: BTreeMap<i64, OrdersBucketNaive>,
    id_index: BTreeMap<i64, (OrderAction, i64, i64)>,
    symbol_spec: Option<CoreSymbolSpecification>,
}

impl OrderBookNaiveImpl {

    pub fn new() -> Self {
        Self {
            ask_buckets: BTreeMap::new(),
            bid_buckets: BTreeMap::new(),
            id_index: BTreeMap::new(),
            symbol_spec: None,
        }
    }

    pub fn with_symbol_spec(symbol_spec: CoreSymbolSpecification) -> Self {
        Self { symbol_spec: Some(symbol_spec), ..Self::new() }
    }

    fn new_order_place_gtc(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("GTC order requires action");
        let price = cmd.price;
        let size = cmd.size;

        let (filled, filled_notional) = self.try_match_instantly(action, price, size, cmd.reserve_bid_price, 0, 0, cmd);
        if filled == size {
            return;
        }

        let order_id = cmd.order_id;
        if self.id_index.contains_key(&order_id) {
            Self::attach_reject_event(cmd, size - filled);
            return;
        }

        let order = Order {
            order_id,
            price,
            size,
            filled,
            filled_notional,
            reserve_bid_price: cmd.reserve_bid_price,
            action,
            order_type: cmd.order_type.expect("GTC order requires order_type"),
            uid: cmd.uid,
            timestamp: cmd.timestamp,
            user_cookie: cmd.user_cookie,
            command: cmd.command,
        };

        self.buckets_by_action_mut(action)
            .entry(price)
            .or_insert_with(|| OrdersBucketNaive::new(price))
            .put(order);
        self.id_index.insert(order_id, (action, price, cmd.uid));
    }

    fn try_match_instantly(
        &mut self,
        taker_action: OrderAction,
        taker_price: i64,
        taker_size: i64,
        taker_reserve_bid_price: i64,
        taker_prior_filled: i64,
        taker_prior_filled_notional: i64,
        cmd: &mut OrderCommand,
    ) -> (i64, i64) {
        match taker_action {
            OrderAction::Bid => Self::match_against(
                &mut self.ask_buckets,
                &mut self.id_index,
                Some(taker_price),
                taker_size,
                taker_action,
                taker_reserve_bid_price,
                true,
                taker_prior_filled,
                taker_prior_filled_notional,
                cmd,
            ),
            OrderAction::Ask => Self::match_against(
                &mut self.bid_buckets,
                &mut self.id_index,
                Some(taker_price),
                taker_size,
                taker_action,
                taker_reserve_bid_price,
                false,
                taker_prior_filled,
                taker_prior_filled_notional,
                cmd,
            ),
        }
    }

    fn try_match_full(
        &mut self,
        taker_action: OrderAction,
        taker_size: i64,
        taker_reserve_bid_price: i64,
        taker_prior_filled: i64,
        taker_prior_filled_notional: i64,
        cmd: &mut OrderCommand,
    ) -> (i64, i64) {
        match taker_action {
            OrderAction::Bid => Self::match_against(
                &mut self.ask_buckets,
                &mut self.id_index,
                None,
                taker_size,
                taker_action,
                taker_reserve_bid_price,
                true,
                taker_prior_filled,
                taker_prior_filled_notional,
                cmd,
            ),
            OrderAction::Ask => Self::match_against(
                &mut self.bid_buckets,
                &mut self.id_index,
                None,
                taker_size,
                taker_action,
                taker_reserve_bid_price,
                false,
                taker_prior_filled,
                taker_prior_filled_notional,
                cmd,
            ),
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn match_against(
        buckets: &mut BTreeMap<i64, OrdersBucketNaive>,
        id_index: &mut BTreeMap<i64, (OrderAction, i64, i64)>,
        taker_price_limit: Option<i64>,
        taker_size: i64,
        taker_action: OrderAction,
        taker_reserve_bid_price: i64,
        ascending: bool,
        taker_prior_filled: i64,
        taker_prior_filled_notional: i64,
        cmd: &mut OrderCommand,
    ) -> (i64, i64) {

        let prices: Vec<i64> = match (ascending, taker_price_limit) {
            (true, Some(limit)) => buckets.range(..=limit).map(|(p, _)| *p).collect(),
            (true, None) => buckets.keys().copied().collect(),
            (false, Some(limit)) => buckets.range(limit..).rev().map(|(p, _)| *p).collect(),
            (false, None) => buckets.keys().rev().copied().collect(),
        };

        let mut filled: i64 = 0;
        let mut taker_filled: i64 = 0;
        let mut taker_filled_notional: i64 = 0;
        let mut events: Vec<MatcherTradeEvent> = Vec::new();
        let mut emptied: Vec<i64> = Vec::new();

        for p in prices {
            if filled == taker_size {
                break;
            }
            let size_left = taker_size - filled;
            let bucket = buckets.get_mut(&p).expect("bucket must exist for collected price");

            let mut remaining_in_call = size_left;
            bucket.match_forward(size_left, &mut |f: MakerFill| {
                remaining_in_call -= f.trade;
                let active_order_completed = remaining_in_call == 0;
                taker_filled += f.trade;
                taker_filled_notional = add_exact(taker_filled_notional, mul_exact(f.trade, p));

                let bidder_hold_price = if taker_action == OrderAction::Bid {
                    taker_reserve_bid_price
                } else {
                    f.reserve_bid_price
                };

                events.push(MatcherTradeEvent {
                    event_type: MatcherEventType::Trade,
                    active_order_completed,
                    maker_order_id: f.order_id,
                    maker_order_completed: f.completed,
                    price: p,
                    size: f.trade,
                    bid_gt_ask: taker_action == OrderAction::Bid,
                    bidder_hold_price,
                    matched_order_uid: f.uid,
                    matched_order_command_type: f.command,
                    filled: add_exact(taker_prior_filled, taker_filled),
                    filled_notional: add_exact(taker_prior_filled_notional, taker_filled_notional),
                    matched_order_size: f.size,
                    matched_order_price: f.price,
                    matched_order_type: f.order_type,
                    matched_order_timestamp: f.timestamp,
                    matched_user_cookie: f.user_cookie,
                    matched_order_filled: f.filled,
                    matched_order_filled_notional: f.filled_notional,
                    next: None,
                });
                if f.completed {

                    id_index.remove(&f.order_id);
                }
            });

            filled += size_left - remaining_in_call;

            if bucket.is_empty() {
                emptied.push(p);
            }
        }

        for p in emptied {
            buckets.remove(&p);
        }

        let mut chain: Option<Box<MatcherTradeEvent>> = None;
        for mut ev in events.into_iter().rev() {
            ev.next = chain.take();
            chain = Some(Box::new(ev));
        }
        cmd.matcher_event = chain;

        (filled, taker_filled_notional)
    }

    fn match_against_budget(
        buckets: &mut BTreeMap<i64, OrdersBucketNaive>,
        id_index: &mut BTreeMap<i64, (OrderAction, i64, i64)>,
        taker_size: i64,
        mut remaining_budget: i64,
        taker_action: OrderAction,
        cmd: &mut OrderCommand,
    ) -> i64 {
        let prices: Vec<i64> = buckets.keys().copied().collect();

        let mut filled: i64 = 0;
        let mut taker_filled: i64 = 0;
        let mut taker_filled_notional: i64 = 0;
        let mut events: Vec<MatcherTradeEvent> = Vec::new();
        let mut emptied: Vec<i64> = Vec::new();

        let taker_reserve_bid_price = cmd.reserve_bid_price;

        for p in prices {
            if filled == taker_size {
                break;
            }
            let size_left = taker_size - filled;

            let affordable = if p == 0 { i64::MAX } else { remaining_budget / p };
            let size_cap = size_left.min(affordable);
            if size_cap <= 0 {

                break;
            }
            let bucket = buckets.get_mut(&p).expect("bucket must exist for collected price");

            let mut remaining_in_call = size_cap;
            bucket.match_forward(size_cap, &mut |f: MakerFill| {
                remaining_in_call -= f.trade;
                taker_filled += f.trade;
                let active_order_completed = taker_filled == taker_size;
                taker_filled_notional = add_exact(taker_filled_notional, mul_exact(f.trade, p));
                let bidder_hold_price = if taker_action == OrderAction::Bid {
                    taker_reserve_bid_price
                } else {
                    f.reserve_bid_price
                };
                events.push(MatcherTradeEvent {
                    event_type: MatcherEventType::Trade,
                    active_order_completed,
                    maker_order_id: f.order_id,
                    maker_order_completed: f.completed,
                    price: p,
                    size: f.trade,
                    bid_gt_ask: taker_action == OrderAction::Bid,
                    bidder_hold_price,
                    matched_order_uid: f.uid,
                    matched_order_command_type: f.command,
                    filled: taker_filled,
                    filled_notional: taker_filled_notional,
                    matched_order_size: f.size,
                    matched_order_price: f.price,
                    matched_order_type: f.order_type,
                    matched_order_timestamp: f.timestamp,
                    matched_user_cookie: f.user_cookie,
                    matched_order_filled: f.filled,
                    matched_order_filled_notional: f.filled_notional,
                    next: None,
                });
                remaining_budget = sub_exact(remaining_budget, mul_exact(f.trade, p));
                if f.completed {
                    id_index.remove(&f.order_id);
                }
            });

            filled += size_cap - remaining_in_call;

            if bucket.is_empty() {
                emptied.push(p);
            }
        }

        for p in emptied {
            buckets.remove(&p);
        }

        let mut chain: Option<Box<MatcherTradeEvent>> = None;
        for mut ev in events.into_iter().rev() {
            ev.next = chain.take();
            chain = Some(Box::new(ev));
        }
        cmd.matcher_event = chain;

        filled
    }

    fn new_order_match_ioc(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("IOC order requires action");
        let price = cmd.price;
        let size = cmd.size;

        let (filled, _) = self.try_match_instantly(action, price, size, cmd.reserve_bid_price, 0, 0, cmd);
        let rejected_size = size - filled;
        if rejected_size != 0 {
            Self::attach_reject_event(cmd, rejected_size);
        }
    }

    fn new_order_match_ioc_budget(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("IOC_BUDGET order requires action");
        if action != OrderAction::Bid {
            Self::attach_reject_event(cmd, cmd.size);
            return;
        }
        let budget = cmd.price;
        let size = cmd.size;
        let filled = Self::match_against_budget(
            &mut self.ask_buckets,
            &mut self.id_index,
            size,
            budget,
            action,
            cmd,
        );
        let rejected_size = size - filled;
        if rejected_size != 0 {
            Self::attach_reject_event(cmd, rejected_size);
        }
    }

    fn new_order_match_fok(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("FOK order requires action");
        let price = cmd.price;
        let size = cmd.size;

        let available = self.available_volume_for_match(action, price);
        if available >= size {
            self.try_match_instantly(action, price, size, cmd.reserve_bid_price, 0, 0, cmd);
        } else {
            Self::attach_reject_event(cmd, size);
        }
    }

    fn new_order_match_fok_budget(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("FOK_BUDGET order requires action");
        let size = cmd.size;
        let limit = cmd.price;

        let budget = match action {
            OrderAction::Ask => Self::check_budget_to_fill(
                self.bid_buckets.iter().rev().map(|(p, b)| (*p, b.total_volume())),
                size,
            ),
            OrderAction::Bid => Self::check_budget_to_fill(
                self.ask_buckets.iter().map(|(p, b)| (*p, b.total_volume())),
                size,
            ),
        };

        match budget {
            Some(calculated) if Self::is_budget_limit_satisfied(action, calculated, limit) => {
                self.try_match_full(action, size, cmd.reserve_bid_price, 0, 0, cmd);
            }
            _ => Self::attach_reject_event(cmd, size),
        }
    }

    fn buckets_by_action_mut(&mut self, action: OrderAction) -> &mut BTreeMap<i64, OrdersBucketNaive> {
        match action {
            OrderAction::Ask => &mut self.ask_buckets,
            OrderAction::Bid => &mut self.bid_buckets,
        }
    }

    fn buckets_by_action(&self, action: OrderAction) -> &BTreeMap<i64, OrdersBucketNaive> {
        match action {
            OrderAction::Ask => &self.ask_buckets,
            OrderAction::Bid => &self.bid_buckets,
        }
    }

    fn attach_reject_event(cmd: &mut OrderCommand, rejected_size: i64) {
        let event = MatcherTradeEvent {
            event_type: MatcherEventType::Reject,
            active_order_completed: true,
            price: cmd.price,
            size: rejected_size,
            bidder_hold_price: cmd.reserve_bid_price,
            next: cmd.matcher_event.take(),
            ..Default::default()
        };
        cmd.matcher_event = Some(Box::new(event));
    }

    fn available_volume_for_match(&self, taker_action: OrderAction, taker_price: i64) -> i64 {
        match taker_action {
            OrderAction::Bid => self
                .ask_buckets
                .range(..=taker_price)
                .map(|(_, b)| b.total_volume())
                .sum(),
            OrderAction::Ask => self
                .bid_buckets
                .range(taker_price..)
                .map(|(_, b)| b.total_volume())
                .sum(),
        }
    }

    fn check_budget_to_fill(iter: impl Iterator<Item = (i64, i64)>, mut size: i64) -> Option<i64> {
        let mut budget: i64 = 0;
        for (price, available_size) in iter {
            if size > available_size {
                size -= available_size;
                budget = add_exact(budget, mul_exact(available_size, price));
            } else {
                return Some(add_exact(budget, mul_exact(size, price)));
            }
        }
        None
    }

    fn is_budget_limit_satisfied(action: OrderAction, calculated: i64, limit: i64) -> bool {
        calculated == limit || ((action == OrderAction::Bid) != (calculated > limit))
    }
}

impl Default for OrderBookNaiveImpl {
    fn default() -> Self {
        Self::new()
    }
}

impl IOrderBook for OrderBookNaiveImpl {

    fn new_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode {
        match cmd.order_type {
            Some(OrderType::Gtc) => self.new_order_place_gtc(cmd),
            Some(OrderType::Ioc) => self.new_order_match_ioc(cmd),
            Some(OrderType::IocBudget) => self.new_order_match_ioc_budget(cmd),
            Some(OrderType::Fok) => self.new_order_match_fok(cmd),
            Some(OrderType::FokBudget) => self.new_order_match_fok_budget(cmd),
            None => {
                cmd.result_code = Some(CommandResultCode::MatchingUnsupportedCommand);
                return CommandResultCode::MatchingUnsupportedCommand;
            }
        }
        cmd.result_code = Some(CommandResultCode::Success);
        CommandResultCode::Success
    }

    fn cancel_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode {
        let order_id = cmd.order_id;
        let (action, price, uid) = match self.id_index.get(&order_id) {
            Some(&v) => v,
            None => return CommandResultCode::MatchingUnknownOrderId,
        };
        if uid != cmd.uid {
            return CommandResultCode::MatchingUnknownOrderId;
        }

        let buckets = self.buckets_by_action_mut(action);
        let order = buckets
            .get_mut(&price)
            .and_then(|b| b.remove(order_id))
            .expect("id_index/bucket invariant violated");
        let bucket_empty = buckets.get(&price).map(|b| b.is_empty()).unwrap_or(true);
        if bucket_empty {
            buckets.remove(&price);
        }
        self.id_index.remove(&order_id);

        let remaining = order.remaining();
        cmd.matcher_event = Some(Box::new(MatcherTradeEvent {
            event_type: MatcherEventType::Reduce,
            active_order_completed: true,
            price: order.price,
            size: remaining,
            filled: order.filled,
            filled_notional: order.filled_notional,
            bidder_hold_price: order.reserve_bid_price,
            ..Default::default()
        }));
        cmd.action = Some(order.action);

        CommandResultCode::Success
    }

    fn reduce_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode {
        let order_id = cmd.order_id;
        let requested = cmd.size;
        if requested <= 0 {
            return CommandResultCode::MatchingReduceFailedWrongSize;
        }

        let (action, price, uid) = match self.id_index.get(&order_id) {
            Some(&v) => v,
            None => return CommandResultCode::MatchingUnknownOrderId,
        };
        if uid != cmd.uid {
            return CommandResultCode::MatchingUnknownOrderId;
        }

        let buckets = self.buckets_by_action_mut(action);
        let remaining = buckets
            .get(&price)
            .and_then(|b| b.get(order_id))
            .map(|o| o.remaining())
            .expect("id_index/bucket invariant violated");

        let reduce_by = requested.min(remaining);
        let can_remove = reduce_by == remaining;

        let order = if can_remove {
            buckets.get_mut(&price).and_then(|b| b.remove(order_id))
        } else {
            buckets.get_mut(&price).and_then(|b| b.reduce(order_id, reduce_by))
        }
        .expect("id_index/bucket invariant violated");

        if can_remove {
            let bucket_empty = buckets.get(&price).map(|b| b.is_empty()).unwrap_or(true);
            if bucket_empty {
                buckets.remove(&price);
            }
            self.id_index.remove(&order_id);
        }

        cmd.matcher_event = Some(Box::new(MatcherTradeEvent {
            event_type: MatcherEventType::Reduce,
            active_order_completed: can_remove,
            price: order.price,
            size: reduce_by,
            filled: order.filled,
            filled_notional: order.filled_notional,
            bidder_hold_price: order.reserve_bid_price,
            ..Default::default()
        }));
        cmd.action = Some(order.action);

        CommandResultCode::Success
    }

    fn move_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode {
        let order_id = cmd.order_id;
        let new_price = cmd.price;

        let (action, old_price, uid) = match self.id_index.get(&order_id) {
            Some(&v) => v,
            None => return CommandResultCode::MatchingUnknownOrderId,
        };
        if uid != cmd.uid {
            return CommandResultCode::MatchingUnknownOrderId;
        }

        cmd.action = Some(action);

        if let Some(spec) = &self.symbol_spec {
            if spec.symbol_type == SymbolType::CurrencyExchangePair && action == OrderAction::Bid {
                let reserve = self
                    .buckets_by_action(action)
                    .get(&old_price)
                    .and_then(|b| b.get(order_id))
                    .map(|o| o.reserve_bid_price);
                if let Some(reserve_bid_price) = reserve {
                    if new_price > reserve_bid_price {
                        return CommandResultCode::MatchingMoveFailedPriceOverRiskLimit;
                    }
                }
            }
        }

        let buckets = self.buckets_by_action_mut(action);
        let mut order = buckets
            .get_mut(&old_price)
            .and_then(|b| b.remove(order_id))
            .expect("id_index/bucket invariant violated");
        let bucket_empty = buckets.get(&old_price).map(|b| b.is_empty()).unwrap_or(true);
        if bucket_empty {
            buckets.remove(&old_price);
        }

        cmd.action = Some(order.action);
        order.price = new_price;

        let remaining = order.size - order.filled;
        let (matched_now, matched_notional_now) =
            self.try_match_instantly(action, new_price, remaining, order.reserve_bid_price, order.filled, order.filled_notional, cmd);
        let total_filled = order.filled + matched_now;

        if total_filled == order.size {
            self.id_index.remove(&order_id);
            return CommandResultCode::Success;
        }

        order.filled = total_filled;
        order.filled_notional += matched_notional_now;
        self.buckets_by_action_mut(action)
            .entry(new_price)
            .or_insert_with(|| OrdersBucketNaive::new(new_price))
            .put(order);
        self.id_index.insert(order_id, (action, new_price, uid));

        CommandResultCode::Success
    }

    fn fill_l2(&self, size: i32) -> L2MarketData {
        let take: usize = match size {
            0 => 0,
            s if s < 0 => usize::MAX,
            s => s as usize,
        };

        let mut ask_prices = Vec::new();
        let mut ask_volumes = Vec::new();
        let mut ask_orders = Vec::new();
        for (price, bucket) in self.ask_buckets.iter() {
            if ask_prices.len() == take {
                break;
            }
            ask_prices.push(*price);
            ask_volumes.push(bucket.total_volume());
            ask_orders.push(bucket.num_orders() as i64);
        }

        let mut bid_prices = Vec::new();
        let mut bid_volumes = Vec::new();
        let mut bid_orders = Vec::new();
        for (price, bucket) in self.bid_buckets.iter().rev() {
            if bid_prices.len() == take {
                break;
            }
            bid_prices.push(*price);
            bid_volumes.push(bucket.total_volume());
            bid_orders.push(bucket.num_orders() as i64);
        }

        L2MarketData { ask_prices, ask_volumes, ask_orders, bid_prices, bid_volumes, bid_orders }
    }

    fn state_hash(&self) -> i32 {
        fn order_hash(o: &Order) -> i64 {
            let mut h: i64 = 17;
            h = h.wrapping_mul(31).wrapping_add(o.order_id);
            h = h.wrapping_mul(31).wrapping_add(o.action.code() as i64);
            h = h.wrapping_mul(31).wrapping_add(o.price);
            h = h.wrapping_mul(31).wrapping_add(o.size);
            h = h.wrapping_mul(31).wrapping_add(o.filled);
            h = h.wrapping_mul(31).wrapping_add(o.reserve_bid_price);
            h = h.wrapping_mul(31).wrapping_add(o.uid);
            h
        }

        let mut h: i64 = 0;
        for bucket in self.ask_buckets.values() {
            for order in bucket.iter_orders() {
                h = h.wrapping_mul(31).wrapping_add(order_hash(order));
            }
        }
        for bucket in self.bid_buckets.values().rev() {
            for order in bucket.iter_orders() {
                h = h.wrapping_mul(31).wrapping_add(order_hash(order));
            }
        }
        ((h >> 32) as i32) ^ (h as i32)
    }

    fn find_user_orders(&self, uid: i64) -> Vec<Order> {
        let mut out: Vec<Order> = self
            .ask_buckets
            .values()
            .chain(self.bid_buckets.values())
            .flat_map(|b| b.iter_orders())
            .filter(|o| o.uid == uid)
            .cloned()
            .collect();
        out.sort_by_key(|o| o.order_id);
        out
    }
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::{to_btree_i64, ChronicleMarshallable};

impl OrderBookNaiveImpl {

    pub fn chronicle_orders(&self) -> (Vec<Order>, Vec<Order>) {
        let asks = self.ask_buckets.values().flat_map(|b| b.iter_orders().cloned()).collect();
        let bids = self.bid_buckets.values().rev().flat_map(|b| b.iter_orders().cloned()).collect();
        (asks, bids)
    }

    pub fn chronicle_symbol_spec(&self) -> Option<CoreSymbolSpecification> {
        self.symbol_spec.clone()
    }

    pub fn chronicle_read_body(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let symbol_spec = CoreSymbolSpecification::chronicle_read(r)?;
        let ask_buckets = to_btree_i64(r.read_long_keyed_map(OrdersBucketNaive::chronicle_read)?);
        let bid_buckets = to_btree_i64(r.read_long_keyed_map(OrdersBucketNaive::chronicle_read)?);
        let mut book = OrderBookNaiveImpl { ask_buckets, bid_buckets, id_index: BTreeMap::new(), symbol_spec: Some(symbol_spec) };
        book.rebuild_id_index();
        Ok(book)
    }

    fn rebuild_id_index(&mut self) {
        self.id_index.clear();
        for (&price, bucket) in &self.ask_buckets {
            for order in bucket.iter_orders() {
                self.id_index.insert(order.order_id, (OrderAction::Ask, price, order.uid));
            }
        }
        for (&price, bucket) in &self.bid_buckets {
            for order in bucket.iter_orders() {
                self.id_index.insert(order.order_id, (OrderAction::Bid, price, order.uid));
            }
        }
    }
}

impl ChronicleMarshallable for OrderBookNaiveImpl {

    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_u8(0);
        let spec = self.symbol_spec.as_ref().expect("naive order book missing symbol_spec");
        spec.chronicle_write(w);
        w.write_i32(self.ask_buckets.len() as i32);
        for (&price, bucket) in &self.ask_buckets {
            w.write_i64(price);
            bucket.chronicle_write(w, spec);
        }
        w.write_i32(self.bid_buckets.len() as i32);
        for (&price, bucket) in self.bid_buckets.iter().rev() {
            w.write_i64(price);
            bucket.chronicle_write(w, spec);
        }
    }

    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let impl_type = r.read_u8()?;
        assert_eq!(impl_type, 0, "not a Naive order book (code {impl_type}); expected OrderBookImplType.NAIVE=0");
        Self::chronicle_read_body(r)
    }
}

#[cfg(test)]
mod ob_tests {
    use super::*;
    use crate::core::common::cmd::order_command_type::OrderCommandType;

    fn place(book: &mut OrderBookNaiveImpl, id: i64, act: OrderAction, price: i64, size: i64) -> OrderCommand {
        let mut cmd = OrderCommand { order_id: id, symbol: 1, price, size,
            action: Some(act), order_type: Some(OrderType::Gtc), uid: id, ..Default::default() };
        book.new_order(&mut cmd);
        cmd
    }

    #[test]
    fn chronicle_naive_roundtrip_bytes() {
        use crate::core::snapshot::chronicle_reader::ChronicleReader;
        use crate::core::snapshot::chronicle_writer::ChronicleWriter;
        let spec = CoreSymbolSpecification { symbol_id: 1, ..Default::default() };
        let mut book = OrderBookNaiveImpl::with_symbol_spec(spec);
        place(&mut book, 1, OrderAction::Ask, 110, 5);
        place(&mut book, 2, OrderAction::Ask, 110, 3);
        place(&mut book, 3, OrderAction::Ask, 120, 7);
        place(&mut book, 4, OrderAction::Bid, 100, 4);
        place(&mut book, 5, OrderAction::Bid, 90, 6);
        let mut w1 = ChronicleWriter::new();
        book.chronicle_write(&mut w1);
        let bytes1 = w1.into_bytes();
        let mut r = ChronicleReader::new(&bytes1);
        let back = OrderBookNaiveImpl::chronicle_read(&mut r).unwrap();
        assert!(r.is_empty(), "read did not consume all bytes");
        assert_eq!(back.fill_l2(100).ask_volumes, book.fill_l2(100).ask_volumes);
        assert_eq!(back.fill_l2(100).bid_volumes, book.fill_l2(100).bid_volumes);
        let mut w2 = ChronicleWriter::new();
        back.chronicle_write(&mut w2);
        assert_eq!(w2.into_bytes(), bytes1, "Naive write->read->write byte mismatch");
    }

    #[test]
    fn ioc_discards_remainder() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 5);
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 100, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Ioc), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        assert_eq!(book.fill_l2(10).bid_prices.len(), 0);
        assert_eq!(book.fill_l2(10).ask_prices.len(), 0);
    }

    #[test]
    fn fok_all_or_nothing_rejects() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 5);
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 100, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Fok), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        let ev = cmd.matcher_event.as_ref().unwrap();
        assert_eq!(ev.event_type, crate::core::common::matcher_event_type::MatcherEventType::Reject);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![5]);
    }

    #[test]
    fn ioc_full_fill_matches_and_leaves_no_remainder() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 100, size: 6,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Ioc), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        let ev = cmd.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, crate::core::common::matcher_event_type::MatcherEventType::Trade);
        assert!(ev.next.is_none());
        assert_eq!(book.fill_l2(10).ask_volumes, vec![4]);
    }

    #[test]
    fn fok_full_fill_matches_completely() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 100, size: 6,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Fok), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        let ev = cmd.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, crate::core::common::matcher_event_type::MatcherEventType::Trade);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![4]);
    }

    #[test]
    fn ioc_budget_caps_by_notional_and_discards_rest() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 250, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::IocBudget), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        let head = cmd.matcher_event.as_ref().expect("expected an event chain");
        assert_eq!(head.event_type, crate::core::common::matcher_event_type::MatcherEventType::Reject);
        assert_eq!(head.size, 8);
        let trade = head.next.as_ref().expect("expected a trade event after the reject");
        assert_eq!(trade.event_type, crate::core::common::matcher_event_type::MatcherEventType::Trade);
        assert_eq!(trade.size, 2);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![8]);
    }

    #[test]
    fn fok_budget_rejects_when_budget_insufficient() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 500, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::FokBudget), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        let ev = cmd.matcher_event.as_ref().unwrap();
        assert_eq!(ev.event_type, crate::core::common::matcher_event_type::MatcherEventType::Reject);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![10]);
    }

    #[test]
    fn fok_budget_matches_when_budget_sufficient() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 1000, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::FokBudget), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        let ev = cmd.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, crate::core::common::matcher_event_type::MatcherEventType::Trade);
        assert_eq!(ev.size, 10);
        assert_eq!(book.fill_l2(10).ask_prices.len(), 0);
    }

    #[test]
    fn two_orders_cross_into_one_trade() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        let taker = place(&mut book, 2, OrderAction::Bid, 100, 6);
        let ev = taker.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, crate::core::common::matcher_event_type::MatcherEventType::Trade);
        assert_eq!(ev.maker_order_id, 1);
        assert_eq!(ev.price, 100);
        assert_eq!(ev.size, 6);
        assert!(ev.next.is_none());
        let l2 = book.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![100]);
        assert_eq!(l2.ask_volumes, vec![4]);
    }

    #[test]
    fn trade_event_matched_order_command_type_is_makers_command_not_takers() {
        let mut book = OrderBookNaiveImpl::new();
        let mut maker_cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1, symbol: 1, price: 100, size: 10,
            action: Some(OrderAction::Ask), order_type: Some(OrderType::Gtc), uid: 501,
            ..Default::default()
        };
        book.new_order(&mut maker_cmd);

        let mut taker_cmd = OrderCommand {
            command: OrderCommandType::ForceLiquidation,
            order_id: 2, symbol: 1, price: 100, size: 4,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Ioc), uid: 888,
            ..Default::default()
        };
        book.new_order(&mut taker_cmd);

        let ev = taker_cmd.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(
            ev.matched_order_command_type,
            OrderCommandType::PlaceOrder,
            "matched_order_command_type must take the maker's original command type, not the taker's ForceLiquidation"
        );
    }

    #[test]
    fn trade_event_matched_order_command_type_follows_maker_even_when_maker_is_force_liquidation() {
        let mut book = OrderBookNaiveImpl::new();
        let mut maker_cmd = OrderCommand {
            command: OrderCommandType::ForceLiquidation,
            order_id: 1, symbol: 1, price: 100, size: 10,
            action: Some(OrderAction::Ask), order_type: Some(OrderType::Gtc), uid: 501,
            ..Default::default()
        };
        book.new_order(&mut maker_cmd);

        let mut taker_cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 2, symbol: 1, price: 100, size: 4,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 888,
            ..Default::default()
        };
        book.new_order(&mut taker_cmd);

        let ev = taker_cmd.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.matched_order_command_type, OrderCommandType::ForceLiquidation);
    }

    #[test]
    fn trade_event_bidder_hold_price_when_maker_is_bid() {
        let mut book = OrderBookNaiveImpl::new();
        let mut maker_cmd = OrderCommand {
            order_id: 1, symbol: 1, price: 100, size: 10, reserve_bid_price: 12345,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 501,
            ..Default::default()
        };
        book.new_order(&mut maker_cmd);

        let mut taker_cmd = OrderCommand {
            order_id: 2, symbol: 1, price: 100, size: 4, reserve_bid_price: 999_999,
            action: Some(OrderAction::Ask), order_type: Some(OrderType::Gtc), uid: 777,
            ..Default::default()
        };
        book.new_order(&mut taker_cmd);

        let ev = taker_cmd.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.matched_order_uid, 501);
        assert_eq!(ev.bidder_hold_price, 12345);
    }

    #[test]
    fn trade_event_bidder_hold_price_when_taker_is_bid() {
        let mut book = OrderBookNaiveImpl::new();
        let mut maker_cmd = OrderCommand {
            order_id: 1, symbol: 1, price: 200, size: 10, reserve_bid_price: 999_999,
            action: Some(OrderAction::Ask), order_type: Some(OrderType::Gtc), uid: 502,
            ..Default::default()
        };
        book.new_order(&mut maker_cmd);

        let mut taker_cmd = OrderCommand {
            order_id: 2, symbol: 1, price: 200, size: 4, reserve_bid_price: 20000,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 888,
            ..Default::default()
        };
        book.new_order(&mut taker_cmd);

        let ev = taker_cmd.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.matched_order_uid, 502);
        assert_eq!(ev.bidder_hold_price, 20000);
    }

    #[test]
    fn new_order_reports_result_code() {
        let mut book = OrderBookNaiveImpl::new();
        let mut cmd = OrderCommand { order_id: 1, symbol: 1, price: 100, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 1, ..Default::default() };
        let rc = book.new_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        assert_eq!(book.fill_l2(10).bid_volumes, vec![10]);

        let mut unsupported = OrderCommand { order_id: 2, symbol: 1, price: 100, size: 5,
            action: Some(OrderAction::Bid), order_type: None, uid: 2, ..Default::default() };
        let rc2 = book.new_order(&mut unsupported);
        assert_eq!(rc2, CommandResultCode::MatchingUnsupportedCommand);
        assert_eq!(unsupported.result_code, Some(CommandResultCode::MatchingUnsupportedCommand));
        assert_eq!(book.fill_l2(10).bid_volumes, vec![10]);
    }

    #[test]
    fn cancel_unknown_returns_error() {
        let mut book = OrderBookNaiveImpl::new();
        let mut cmd = OrderCommand { order_id: 999, symbol: 1, uid: 1, ..Default::default() };
        assert_eq!(book.cancel_order(&mut cmd), CommandResultCode::MatchingUnknownOrderId);
    }

    #[test]
    fn l2_prices_sorted() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 102, 1);
        place(&mut book, 2, OrderAction::Ask, 100, 1);
        place(&mut book, 3, OrderAction::Ask, 101, 1);
        let l2 = book.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![100, 101, 102]);
    }

    #[test]
    fn cancel_removes_resting_order() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, uid: 1, ..Default::default() };
        let rc = book.cancel_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);

        let ev = cmd.matcher_event.as_ref().expect("expected a REDUCE event");
        assert_eq!(ev.event_type, MatcherEventType::Reduce);
        assert_eq!(ev.size, 10);
        assert!(ev.active_order_completed);
        assert!(ev.next.is_none());

        let l2 = book.fill_l2(10);
        assert!(l2.ask_prices.is_empty());

        let mut again = OrderCommand { order_id: 1, symbol: 1, uid: 1, ..Default::default() };
        assert_eq!(book.cancel_order(&mut again), CommandResultCode::MatchingUnknownOrderId);
    }

    #[test]
    fn cancel_one_of_two_orders_keeps_bucket() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        place(&mut book, 2, OrderAction::Ask, 100, 5);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, uid: 1, ..Default::default() };
        assert_eq!(book.cancel_order(&mut cmd), CommandResultCode::Success);

        let l2 = book.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![100]);
        assert_eq!(l2.ask_volumes, vec![5]);
    }

    #[test]
    fn reduce_unknown_returns_error() {
        let mut book = OrderBookNaiveImpl::new();
        let mut cmd = OrderCommand { order_id: 999, symbol: 1, size: 1, uid: 1, ..Default::default() };
        assert_eq!(book.reduce_order(&mut cmd), CommandResultCode::MatchingUnknownOrderId);
    }

    #[test]
    fn reduce_wrong_size_rejected() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        let mut cmd = OrderCommand { order_id: 1, symbol: 1, size: 0, uid: 1, ..Default::default() };
        assert_eq!(book.reduce_order(&mut cmd), CommandResultCode::MatchingReduceFailedWrongSize);
    }

    #[test]
    fn reduce_partial_keeps_order_resting() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, size: 4, uid: 1, ..Default::default() };
        let rc = book.reduce_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);

        let ev = cmd.matcher_event.as_ref().expect("expected a REDUCE event");
        assert_eq!(ev.event_type, MatcherEventType::Reduce);
        assert_eq!(ev.size, 4);
        assert!(!ev.active_order_completed);

        assert_eq!(book.fill_l2(10).ask_volumes, vec![6]);
    }

    #[test]
    fn reduce_beyond_remaining_removes_order_like_cancel() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, size: 100, uid: 1, ..Default::default() };
        let rc = book.reduce_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);

        let ev = cmd.matcher_event.as_ref().expect("expected a REDUCE event");
        assert_eq!(ev.size, 10);
        assert!(ev.active_order_completed);

        assert!(book.fill_l2(10).ask_prices.is_empty());
    }

    #[test]
    fn cancel_other_users_order_returns_unknown() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, uid: 999, ..Default::default() };
        assert_eq!(book.cancel_order(&mut cmd), CommandResultCode::MatchingUnknownOrderId);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![10]);
    }

    #[test]
    fn reduce_other_users_order_returns_unknown() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, size: 3, uid: 999, ..Default::default() };
        assert_eq!(book.reduce_order(&mut cmd), CommandResultCode::MatchingUnknownOrderId);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![10]);
    }

    #[test]
    fn move_other_users_order_returns_unknown() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, price: 105, uid: 999, ..Default::default() };
        assert_eq!(book.move_order(&mut cmd), CommandResultCode::MatchingUnknownOrderId);
        assert_eq!(book.fill_l2(10).ask_prices, vec![100]);
    }

    #[test]
    fn move_unknown_returns_error() {
        let mut book = OrderBookNaiveImpl::new();
        let mut cmd = OrderCommand { order_id: 999, symbol: 1, price: 100, uid: 1, ..Default::default() };
        assert_eq!(book.move_order(&mut cmd), CommandResultCode::MatchingUnknownOrderId);
    }

    #[test]
    fn move_reprices_resting_order() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, price: 105, uid: 1, ..Default::default() };
        let rc = book.move_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);
        assert!(cmd.matcher_event.is_none());

        let l2 = book.fill_l2(10);
        assert!(l2.ask_prices.iter().all(|&p| p != 100));
        assert_eq!(l2.ask_prices, vec![105]);
        assert_eq!(l2.ask_volumes, vec![10]);
    }

    #[test]
    fn move_crosses_and_trades_immediately() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Bid, 90, 10);
        place(&mut book, 2, OrderAction::Ask, 100, 5);

        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 80, uid: 2, ..Default::default() };
        let rc = book.move_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);

        let ev = cmd.matcher_event.as_ref().expect("expected an immediate trade after the move crosses");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.maker_order_id, 1);
        assert_eq!(ev.size, 5);

        assert!(book.fill_l2(10).ask_prices.is_empty());
        assert_eq!(book.fill_l2(10).bid_volumes, vec![5]);
    }

    fn exchange_pair_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: 1,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        }
    }

    #[test]
    fn move_bid_over_reserve_price_rejected_on_exchange_pair_spec() {
        let mut book = OrderBookNaiveImpl::with_symbol_spec(exchange_pair_spec());
        let mut place = OrderCommand {
            order_id: 1, symbol: 1, price: 90, size: 5, reserve_bid_price: 95,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 1, ..Default::default()
        };
        book.new_order(&mut place);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, price: 96, uid: 1, ..Default::default() };
        let rc = book.move_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::MatchingMoveFailedPriceOverRiskLimit);
        assert_eq!(cmd.action, Some(OrderAction::Bid), "Java Naive backfills cmd.action before the guard check (unlike Direct)");
        assert!(cmd.matcher_event.is_none(), "the failure branch produces no event");
        let l2 = book.fill_l2(10);
        assert_eq!(l2.bid_prices, vec![90], "after rejection the order stays at the original price 90; FIFO/state unchanged");
        assert_eq!(l2.bid_volumes, vec![5]);

        let mut ok = OrderCommand { order_id: 1, symbol: 1, price: 95, uid: 1, ..Default::default() };
        assert_eq!(book.move_order(&mut ok), CommandResultCode::Success, "== reserve boundary is allowed");
        assert_eq!(book.fill_l2(10).bid_prices, vec![95]);
    }

    #[test]
    fn move_bid_guard_skipped_when_symbol_spec_absent() {
        let mut book = OrderBookNaiveImpl::new();
        let mut place = OrderCommand {
            order_id: 1, symbol: 1, price: 90, size: 5, reserve_bid_price: 95,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 1, ..Default::default()
        };
        book.new_order(&mut place);
        let mut cmd = OrderCommand { order_id: 1, symbol: 1, price: 200, uid: 1, ..Default::default() };
        assert_eq!(book.move_order(&mut cmd), CommandResultCode::Success, "no BID risk check when spec is absent");
    }

    #[test]
    fn move_fully_filled_order_is_removed_from_id_index() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Bid, 90, 5);
        place(&mut book, 2, OrderAction::Ask, 100, 5);

        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 80, uid: 2, ..Default::default() };
        assert_eq!(book.move_order(&mut cmd), CommandResultCode::Success);

        let mut cancel = OrderCommand { order_id: 2, symbol: 1, uid: 2, ..Default::default() };
        assert_eq!(book.cancel_order(&mut cancel), CommandResultCode::MatchingUnknownOrderId);
    }

    #[test]
    fn fill_l2_zero_size_returns_empty() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        place(&mut book, 2, OrderAction::Bid, 90, 5);

        let l2 = book.fill_l2(0);
        assert!(l2.ask_prices.is_empty());
        assert!(l2.ask_volumes.is_empty());
        assert!(l2.bid_prices.is_empty());
        assert!(l2.bid_volumes.is_empty());
    }

    #[test]
    fn duplicate_order_id_matches_then_rejects_remainder_and_does_not_place() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        place(&mut book, 2, OrderAction::Ask, 90, 6);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, price: 95, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 99, ..Default::default() };
        book.new_order(&mut cmd);

        let head = cmd.matcher_event.as_ref().expect("expected an event chain: match first, then reject the remainder");
        assert_eq!(head.event_type, MatcherEventType::Reject);
        assert_eq!(head.size, 4);
        let trade = head.next.as_ref().expect("expected the earlier trade event after the reject");
        assert_eq!(trade.event_type, MatcherEventType::Trade);
        assert_eq!(trade.maker_order_id, 2);
        assert_eq!(trade.size, 6);

        let l2 = book.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![100]);
        assert_eq!(l2.ask_volumes, vec![10]);
        assert!(l2.bid_prices.is_empty());
    }

    #[test]
    fn duplicate_order_id_full_reject_when_no_match() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, price: 50, size: 7,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 99, ..Default::default() };
        book.new_order(&mut cmd);

        let ev = cmd.matcher_event.as_ref().expect("expected a reject event");
        assert_eq!(ev.event_type, MatcherEventType::Reject);
        assert_eq!(ev.size, 7);
        assert!(ev.next.is_none());

        assert_eq!(book.fill_l2(10).ask_volumes, vec![10]);
    }

    #[test]
    fn state_hash_deterministic_for_same_operation_sequence() {
        let build = || {
            let mut book = OrderBookNaiveImpl::new();
            place(&mut book, 1, OrderAction::Ask, 100, 10);
            place(&mut book, 2, OrderAction::Ask, 101, 5);
            place(&mut book, 3, OrderAction::Bid, 90, 7);
            book
        };
        let a = build();
        let b = build();
        assert_eq!(a.state_hash(), b.state_hash());
    }

    #[test]
    fn state_hash_changes_with_different_book_state() {
        let mut base = OrderBookNaiveImpl::new();
        place(&mut base, 1, OrderAction::Ask, 100, 10);
        let h1 = base.state_hash();

        let mut diff_price = OrderBookNaiveImpl::new();
        place(&mut diff_price, 1, OrderAction::Ask, 101, 10);
        assert_ne!(h1, diff_price.state_hash());

        let mut diff_size = OrderBookNaiveImpl::new();
        place(&mut diff_size, 1, OrderAction::Ask, 100, 11);
        assert_ne!(h1, diff_size.state_hash());

        let mut diff_extra = OrderBookNaiveImpl::new();
        place(&mut diff_extra, 1, OrderAction::Ask, 100, 10);
        place(&mut diff_extra, 2, OrderAction::Bid, 90, 3);
        assert_ne!(h1, diff_extra.state_hash());

        let mut partially_filled = OrderBookNaiveImpl::new();
        place(&mut partially_filled, 1, OrderAction::Ask, 100, 10);
        let mut taker = OrderCommand { order_id: 2, symbol: 1, price: 100, size: 3,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Ioc), uid: 2, ..Default::default() };
        partially_filled.new_order(&mut taker);
        assert_ne!(h1, partially_filled.state_hash());
    }

    #[test]
    fn fill_l2_large_size_returns_all_levels() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 102, 1);
        place(&mut book, 2, OrderAction::Ask, 100, 1);
        place(&mut book, 3, OrderAction::Ask, 101, 1);

        let l2 = book.fill_l2(i32::MAX);
        assert_eq!(l2.ask_prices, vec![100, 101, 102]);
    }
}

#[cfg(test)]
mod ob_base_tests {
    use super::*;

    const UID_1: i64 = 412;
    const UID_2: i64 = 413;
    const INITIAL_PRICE: i64 = 81600;
    const MAX_PRICE: i64 = 400000;

    fn place_order(
        book: &mut OrderBookNaiveImpl,
        order_type: OrderType,
        order_id: i64,
        uid: i64,
        price: i64,
        reserve_bid_price: i64,
        size: i64,
        action: OrderAction,
    ) -> OrderCommand {
        let mut cmd = OrderCommand {
            order_id,
            symbol: 1,
            price,
            size,
            reserve_bid_price,
            action: Some(action),
            order_type: Some(order_type),
            uid,
            ..Default::default()
        };
        book.new_order(&mut cmd);
        cmd
    }

    fn cancel_cmd(book: &mut OrderBookNaiveImpl, order_id: i64, uid: i64) -> (CommandResultCode, OrderCommand) {
        let mut cmd = OrderCommand { order_id, uid, ..Default::default() };
        let rc = book.cancel_order(&mut cmd);
        (rc, cmd)
    }

    fn reduce_cmd(book: &mut OrderBookNaiveImpl, order_id: i64, uid: i64, size: i64) -> (CommandResultCode, OrderCommand) {
        let mut cmd = OrderCommand { order_id, uid, size, ..Default::default() };
        let rc = book.reduce_order(&mut cmd);
        (rc, cmd)
    }

    fn move_cmd(book: &mut OrderBookNaiveImpl, order_id: i64, uid: i64, new_price: i64) -> (CommandResultCode, OrderCommand) {
        let mut cmd = OrderCommand { order_id, uid, price: new_price, ..Default::default() };
        let rc = book.move_order(&mut cmd);
        (rc, cmd)
    }

    fn events_list(cmd: &OrderCommand) -> Vec<&MatcherTradeEvent> {
        let mut v = Vec::new();
        let mut cur = cmd.matcher_event.as_deref();
        while let Some(ev) = cur {
            v.push(ev);
            cur = ev.next.as_deref();
        }
        v
    }

    fn check_trade(ev: &MatcherTradeEvent, maker_id: i64, price: i64, size: i64) {
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.maker_order_id, maker_id);
        assert_eq!(ev.price, price);
        assert_eq!(ev.size, size);
    }

    fn check_reject(ev: &MatcherTradeEvent, size: i64, price: i64) {
        assert_eq!(ev.event_type, MatcherEventType::Reject);
        assert_eq!(ev.size, size);
        assert_eq!(ev.price, price);
        assert!(ev.active_order_completed);
    }

    fn check_reduce(ev: &MatcherTradeEvent, reduce_size: i64, price: i64, completed: bool) {
        assert_eq!(ev.event_type, MatcherEventType::Reduce);
        assert_eq!(ev.size, reduce_size);
        assert_eq!(ev.price, price);
        assert_eq!(ev.active_order_completed, completed);
        assert!(ev.next.is_none());
    }

    #[derive(Debug, Clone, PartialEq)]
    struct ExpectedL2 {
        ask_prices: Vec<i64>,
        ask_volumes: Vec<i64>,
        bid_prices: Vec<i64>,
        bid_volumes: Vec<i64>,
    }

    impl ExpectedL2 {
        fn new(ask_prices: Vec<i64>, ask_volumes: Vec<i64>, bid_prices: Vec<i64>, bid_volumes: Vec<i64>) -> Self {
            Self { ask_prices, ask_volumes, bid_prices, bid_volumes }
        }

        fn assert_matches(&self, actual: &L2MarketData) {
            assert_eq!(actual.ask_prices, self.ask_prices);
            assert_eq!(actual.ask_volumes, self.ask_volumes);
            assert_eq!(actual.bid_prices, self.bid_prices);
            assert_eq!(actual.bid_volumes, self.bid_volumes);
        }

        fn insert_ask(&mut self, idx: usize, price: i64, vol: i64) -> &mut Self {
            self.ask_prices.insert(idx, price);
            self.ask_volumes.insert(idx, vol);
            self
        }
        fn insert_bid(&mut self, idx: usize, price: i64, vol: i64) -> &mut Self {
            self.bid_prices.insert(idx, price);
            self.bid_volumes.insert(idx, vol);
            self
        }
        fn set_ask_volume(&mut self, idx: usize, vol: i64) -> &mut Self {
            self.ask_volumes[idx] = vol;
            self
        }
        fn set_bid_volume(&mut self, idx: usize, vol: i64) -> &mut Self {
            self.bid_volumes[idx] = vol;
            self
        }
        fn decrement_bid_volume(&mut self, idx: usize, diff: i64) -> &mut Self {
            self.bid_volumes[idx] -= diff;
            self
        }
        fn remove_ask(&mut self, idx: usize) -> &mut Self {
            self.ask_prices.remove(idx);
            self.ask_volumes.remove(idx);
            self
        }
        fn remove_bid(&mut self, idx: usize) -> &mut Self {
            self.bid_prices.remove(idx);
            self.bid_volumes.remove(idx);
            self
        }
        fn remove_all_asks(&mut self) -> &mut Self {
            self.ask_prices.clear();
            self.ask_volumes.clear();
            self
        }

        fn aggregate_buy_budget(&self, mut size: i64) -> i64 {
            let mut budget = 0i64;
            for i in 0..self.ask_prices.len() {
                let v = self.ask_volumes[i];
                let p = self.ask_prices[i];
                if v < size {
                    budget = add_exact(budget, mul_exact(v, p));
                    size -= v;
                } else {
                    return add_exact(budget, mul_exact(size, p));
                }
            }
            panic!("Can not collect size {size}");
        }

        fn aggregate_sell_expectation(&self, mut size: i64) -> i64 {
            let mut expectation = 0i64;
            for i in 0..self.bid_prices.len() {
                let v = self.bid_volumes[i];
                let p = self.bid_prices[i];
                if v < size {
                    expectation = add_exact(expectation, mul_exact(v, p));
                    size -= v;
                } else {
                    return add_exact(expectation, mul_exact(size, p));
                }
            }
            panic!("Can not collect size {size}");
        }
    }

    fn setup_book() -> (OrderBookNaiveImpl, ExpectedL2) {
        let mut book = OrderBookNaiveImpl::new();

        place_order(&mut book, OrderType::Gtc, 0, UID_2, INITIAL_PRICE, 0, 13, OrderAction::Ask);
        let (rc, _) = cancel_cmd(&mut book, 0, UID_2);
        assert_eq!(rc, CommandResultCode::Success);

        place_order(&mut book, OrderType::Gtc, 1, UID_1, 81600, 0, 100, OrderAction::Ask);
        place_order(&mut book, OrderType::Gtc, 2, UID_1, 81599, 0, 50, OrderAction::Ask);
        place_order(&mut book, OrderType::Gtc, 3, UID_1, 81599, 0, 25, OrderAction::Ask);
        place_order(&mut book, OrderType::Gtc, 8, UID_1, 201000, 0, 28, OrderAction::Ask);
        place_order(&mut book, OrderType::Gtc, 9, UID_1, 201000, 0, 32, OrderAction::Ask);
        place_order(&mut book, OrderType::Gtc, 10, UID_1, 200954, 0, 10, OrderAction::Ask);

        place_order(&mut book, OrderType::Gtc, 4, UID_1, 81593, 82000, 40, OrderAction::Bid);
        place_order(&mut book, OrderType::Gtc, 5, UID_1, 81590, 82000, 20, OrderAction::Bid);
        place_order(&mut book, OrderType::Gtc, 6, UID_1, 81590, 82000, 1, OrderAction::Bid);
        place_order(&mut book, OrderType::Gtc, 7, UID_1, 81200, 82000, 20, OrderAction::Bid);
        place_order(&mut book, OrderType::Gtc, 11, UID_1, 10000, 12000, 12, OrderAction::Bid);
        place_order(&mut book, OrderType::Gtc, 12, UID_1, 10000, 12000, 1, OrderAction::Bid);
        place_order(&mut book, OrderType::Gtc, 13, UID_1, 9136, 12000, 2, OrderAction::Bid);

        let expected = ExpectedL2::new(
            vec![81599, 81600, 200954, 201000],
            vec![75, 100, 10, 60],
            vec![81593, 81590, 81200, 10000, 9136],
            vec![40, 21, 20, 13, 2],
        );

        expected.assert_matches(&book.fill_l2(25));
        (book, expected)
    }

    fn clear_order_book(book: &mut OrderBookNaiveImpl) {
        let snap = book.fill_l2(i32::MAX);
        let ask_sum: i64 = snap.ask_volumes.iter().sum();
        if ask_sum > 0 {
            place_order(book, OrderType::Ioc, 100_000_000_000, -1, MAX_PRICE, MAX_PRICE, ask_sum, OrderAction::Bid);
        }

        let snap = book.fill_l2(i32::MAX);
        let bid_sum: i64 = snap.bid_volumes.iter().sum();
        if bid_sum > 0 {
            place_order(book, OrderType::Ioc, 100_000_000_001, -2, 1, 0, bid_sum, OrderAction::Ask);
        }

        let snap = book.fill_l2(i32::MAX);
        assert!(snap.ask_prices.is_empty());
        assert!(snap.bid_prices.is_empty());
    }

    #[test]
    fn should_initialize_without_errors() {
        let (mut book, expected) = setup_book();
        expected.assert_matches(&book.fill_l2(25));
        clear_order_book(&mut book);
    }

    #[test]
    fn fill_l2_reports_per_level_order_counts() {
        let (book, _) = setup_book();
        let l2 = book.fill_l2(25);
        assert_eq!(l2.ask_orders, vec![2, 1, 1, 2]);
        assert_eq!(l2.bid_orders, vec![1, 2, 1, 2, 1]);
    }

    #[test]
    fn should_add_gtc_orders() {
        let (mut book, mut expected) = setup_book();

        place_order(&mut book, OrderType::Gtc, 93, UID_1, 81598, 0, 1, OrderAction::Ask);
        expected.insert_ask(0, 81598, 1);

        place_order(&mut book, OrderType::Gtc, 94, UID_1, 81594, MAX_PRICE, 9_000_000_000, OrderAction::Bid);
        expected.insert_bid(0, 81594, 9_000_000_000);

        expected.assert_matches(&book.fill_l2(25));

        place_order(&mut book, OrderType::Gtc, 95, UID_1, 130000, 0, 13_000_000_000, OrderAction::Ask);
        expected.insert_ask(3, 130000, 13_000_000_000);

        place_order(&mut book, OrderType::Gtc, 96, UID_1, 1000, MAX_PRICE, 4, OrderAction::Bid);
        expected.insert_bid(6, 1000, 4);

        expected.assert_matches(&book.fill_l2(25));
        clear_order_book(&mut book);
    }

    #[test]
    fn should_ignored_duplicate_order() {
        let (mut book, expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 1, UID_1, 81600, 0, 100, OrderAction::Ask);

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reject(events[0], 100, 81600);

        expected.assert_matches(&book.fill_l2(25));
        clear_order_book(&mut book);
    }

    #[test]
    fn should_remove_bid_order() {
        let (mut book, mut expected) = setup_book();

        let (rc, cmd) = cancel_cmd(&mut book, 5, UID_1);
        assert_eq!(rc, CommandResultCode::Success);

        expected.set_bid_volume(1, 1);
        expected.assert_matches(&book.fill_l2(25));
        assert_eq!(cmd.action, Some(OrderAction::Bid));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reduce(events[0], 20, 81590, true);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_remove_ask_order() {
        let (mut book, mut expected) = setup_book();

        let (rc, cmd) = cancel_cmd(&mut book, 2, UID_1);
        assert_eq!(rc, CommandResultCode::Success);

        expected.set_ask_volume(0, 25);
        expected.assert_matches(&book.fill_l2(25));
        assert_eq!(cmd.action, Some(OrderAction::Ask));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reduce(events[0], 50, 81599, true);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_reduce_bid_order() {
        let (mut book, mut expected) = setup_book();

        let (rc, cmd) = reduce_cmd(&mut book, 5, UID_1, 3);
        assert_eq!(rc, CommandResultCode::Success);

        expected.decrement_bid_volume(1, 3);
        expected.assert_matches(&book.fill_l2(25));
        assert_eq!(cmd.action, Some(OrderAction::Bid));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reduce(events[0], 3, 81590, false);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_reduce_ask_order() {
        let (mut book, mut expected) = setup_book();

        let (rc, cmd) = reduce_cmd(&mut book, 1, UID_1, 300);
        assert_eq!(rc, CommandResultCode::Success);

        expected.remove_ask(1);
        expected.assert_matches(&book.fill_l2(25));
        assert_eq!(cmd.action, Some(OrderAction::Ask));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reduce(events[0], 100, 81600, true);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_remove_order_and_empty_bucket() {
        let (mut book, mut expected) = setup_book();

        let (rc2, cmd2) = cancel_cmd(&mut book, 2, UID_1);
        assert_eq!(rc2, CommandResultCode::Success);
        assert_eq!(cmd2.action, Some(OrderAction::Ask));
        let events2 = events_list(&cmd2);
        assert_eq!(events2.len(), 1);
        check_reduce(events2[0], 50, 81599, true);

        let (rc3, cmd3) = cancel_cmd(&mut book, 3, UID_1);
        assert_eq!(rc3, CommandResultCode::Success);
        assert_eq!(cmd3.action, Some(OrderAction::Ask));

        expected.remove_ask(0);
        expected.assert_matches(&book.fill_l2(25));

        let events3 = events_list(&cmd3);
        assert_eq!(events3.len(), 1);
        check_reduce(events3[0], 25, 81599, true);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_return_error_when_deleting_unknown_order() {
        let (mut book, expected) = setup_book();
        let (rc, cmd) = cancel_cmd(&mut book, 5291, UID_1);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        expected.assert_matches(&book.fill_l2(25));
        assert_eq!(events_list(&cmd).len(), 0);
        clear_order_book(&mut book);
    }

    #[test]
    fn should_return_error_when_deleting_other_user_order() {
        let (mut book, expected) = setup_book();
        let (rc, cmd) = cancel_cmd(&mut book, 3, UID_2);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        assert!(cmd.matcher_event.is_none());
        expected.assert_matches(&book.fill_l2(25));
        clear_order_book(&mut book);
    }

    #[test]
    fn should_return_error_when_updating_other_user_order() {
        let (mut book, expected) = setup_book();

        let (rc, cmd) = move_cmd(&mut book, 2, UID_2, 100);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        assert!(cmd.matcher_event.is_none());

        let (rc2, cmd2) = move_cmd(&mut book, 8, UID_2, 100);
        assert_eq!(rc2, CommandResultCode::MatchingUnknownOrderId);
        assert!(cmd2.matcher_event.is_none());

        expected.assert_matches(&book.fill_l2(25));
        clear_order_book(&mut book);
    }

    #[test]
    fn should_return_error_when_updating_unknown_order() {
        let (mut book, expected) = setup_book();
        let (rc, cmd) = move_cmd(&mut book, 2433, UID_1, 300);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        expected.assert_matches(&book.fill_l2(10));
        assert_eq!(events_list(&cmd).len(), 0);
        clear_order_book(&mut book);
    }

    #[test]
    fn should_return_error_when_reducing_unknown_order() {
        let (mut book, expected) = setup_book();
        let (rc, cmd) = reduce_cmd(&mut book, 3, UID_2, 1);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        assert!(cmd.matcher_event.is_none());
        expected.assert_matches(&book.fill_l2(25));
        clear_order_book(&mut book);
    }

    #[test]
    fn should_return_error_when_reducing_by_zero_or_negative_size() {
        let (mut book, expected) = setup_book();

        let (rc, cmd) = reduce_cmd(&mut book, 4, UID_1, 0);
        assert_eq!(rc, CommandResultCode::MatchingReduceFailedWrongSize);
        assert!(cmd.matcher_event.is_none());

        let (rc2, cmd2) = reduce_cmd(&mut book, 8, UID_1, -1);
        assert_eq!(rc2, CommandResultCode::MatchingReduceFailedWrongSize);
        assert!(cmd2.matcher_event.is_none());

        let (rc3, cmd3) = reduce_cmd(&mut book, 8, UID_1, i64::MIN);
        assert_eq!(rc3, CommandResultCode::MatchingReduceFailedWrongSize);
        assert!(cmd3.matcher_event.is_none());

        expected.assert_matches(&book.fill_l2(25));
        clear_order_book(&mut book);
    }

    #[test]
    fn should_return_error_when_reducing_other_user_order() {
        let (mut book, expected) = setup_book();
        let (rc, cmd) = reduce_cmd(&mut book, 8, UID_2, 3);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        assert!(cmd.matcher_event.is_none());
        expected.assert_matches(&book.fill_l2(25));
        clear_order_book(&mut book);
    }

    #[test]
    fn should_move_order_existing_bucket() {
        let (mut book, mut expected) = setup_book();
        let (rc, cmd) = move_cmd(&mut book, 7, UID_1, 81590);
        assert_eq!(rc, CommandResultCode::Success);

        expected.set_bid_volume(1, 41).remove_bid(2);
        expected.assert_matches(&book.fill_l2(10));
        assert_eq!(events_list(&cmd).len(), 0);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_move_order_new_bucket() {
        let (mut book, mut expected) = setup_book();
        let (rc, cmd) = move_cmd(&mut book, 7, UID_1, 81594);
        assert_eq!(rc, CommandResultCode::Success);

        expected.remove_bid(2).insert_bid(0, 81594, 20);
        expected.assert_matches(&book.fill_l2(10));
        assert_eq!(events_list(&cmd).len(), 0);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_ioc_order_partial_bbo() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Ioc, 123, UID_2, 1, 0, 10, OrderAction::Ask);

        expected.set_bid_volume(0, 30);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_trade(events[0], 4, 81593, 10);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_ioc_order_full_bbo() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Ioc, 123, UID_2, 1, 0, 40, OrderAction::Ask);

        expected.remove_bid(0);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_trade(events[0], 4, 81593, 40);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_ioc_order_with_two_limit_orders_partial() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Ioc, 123, UID_2, 1, 0, 41, OrderAction::Ask);

        expected.remove_bid(0).set_bid_volume(0, 20);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 2);
        check_trade(events[0], 4, 81593, 40);
        check_trade(events[1], 5, 81590, 1);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_ioc_order_full_liquidity_crosses_multiple_buckets() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Ioc, 123, UID_2, MAX_PRICE, MAX_PRICE, 175, OrderAction::Bid);

        expected.remove_ask(0).remove_ask(0);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 3);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 100);
        assert!(!events[0].active_order_completed);
        assert!(!events[1].active_order_completed);
        assert!(events[2].active_order_completed);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_ioc_order_with_rejection() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Ioc, 123, UID_2, MAX_PRICE, MAX_PRICE + 1, 270, OrderAction::Bid);

        expected.remove_all_asks();
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 7);
        check_reject(events[0], 25, MAX_PRICE);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_reject_fok_bid_order_out_of_budget() {
        let (mut book, expected) = setup_book();
        let size = 180i64;
        let buy_budget = expected.aggregate_buy_budget(size) - 1;
        assert_eq!(buy_budget, 81599 * 75 + 81600 * 100 + 200954 * 5 - 1);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reject(events[0], size, buy_budget);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_fok_bid_order_exact_budget_crosses_multiple_buckets() {
        let (mut book, mut expected) = setup_book();
        let size = 180i64;
        let buy_budget = expected.aggregate_buy_budget(size);
        assert_eq!(buy_budget, 81599 * 75 + 81600 * 100 + 200954 * 5);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        expected.remove_ask(0).remove_ask(0).set_ask_volume(0, 5);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 4);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 100);
        check_trade(events[3], 10, 200954, 5);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_fok_bid_order_extra_budget() {
        let (mut book, mut expected) = setup_book();
        let size = 176i64;
        let buy_budget = expected.aggregate_buy_budget(size) + 1;
        assert_eq!(buy_budget, 81599 * 75 + 81600 * 100 + 200954 + 1);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        expected.remove_ask(0).remove_ask(0).set_ask_volume(0, 9);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 4);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 100);
        check_trade(events[3], 10, 200954, 1);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_reject_fok_ask_order_below_expectation() {
        let (mut book, expected) = setup_book();
        let size = 60i64;
        let sell_expectation = expected.aggregate_sell_expectation(size) + 1;
        assert_eq!(sell_expectation, 81593 * 40 + 81590 * 20 + 1);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, sell_expectation, sell_expectation, size, OrderAction::Ask);

        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reject(events[0], size, sell_expectation);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_fok_ask_order_exact_expectation() {
        let (mut book, mut expected) = setup_book();
        let size = 60i64;
        let sell_expectation = expected.aggregate_sell_expectation(size);
        assert_eq!(sell_expectation, 81593 * 40 + 81590 * 20);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, sell_expectation, sell_expectation, size, OrderAction::Ask);

        expected.remove_bid(0).set_bid_volume(0, 1);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 2);
        check_trade(events[0], 4, 81593, 40);
        check_trade(events[1], 5, 81590, 20);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_fok_ask_order_extra_budget() {
        let (mut book, mut expected) = setup_book();
        let size = 61i64;
        let sell_expectation = expected.aggregate_sell_expectation(size) - 1;
        assert_eq!(sell_expectation, 81593 * 40 + 81590 * 21 - 1);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, sell_expectation, sell_expectation, size, OrderAction::Ask);

        expected.remove_bid(0).remove_bid(0);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 3);
        check_trade(events[0], 4, 81593, 40);
        check_trade(events[1], 5, 81590, 20);
        check_trade(events[2], 6, 81590, 1);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_fully_match_ioc_budget_with_sufficient_budget() {
        let (mut book, mut expected) = setup_book();
        let size = 180i64;
        let buy_budget = expected.aggregate_buy_budget(size);

        let cmd = place_order(&mut book, OrderType::IocBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        expected.remove_ask(0).remove_ask(0).set_ask_volume(0, 5);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 4);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 100);
        check_trade(events[3], 10, 200954, 5);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_partially_match_ioc_budget_when_budget_runs_out() {
        let (mut book, mut expected) = setup_book();
        let size = 180i64;
        let buy_budget = 81599 * 75;

        let cmd = place_order(&mut book, OrderType::IocBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        expected.remove_ask(0);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 3);
        check_reject(events[0], 105, buy_budget);
        check_trade(events[1], 2, 81599, 50);
        check_trade(events[2], 3, 81599, 25);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_reject_ioc_budget_when_budget_too_small_for_one_unit() {
        let (mut book, expected) = setup_book();
        let size = 100i64;
        let buy_budget = 81598i64;

        let cmd = place_order(&mut book, OrderType::IocBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reject(events[0], size, buy_budget);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_reject_ask_ioc_budget() {
        let (mut book, expected) = setup_book();
        let size = 50i64;
        let sell_expectation = 81593 * 40;

        let cmd = place_order(&mut book, OrderType::IocBudget, 123, UID_2, sell_expectation, sell_expectation, size, OrderAction::Ask);

        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reject(events[0], size, sell_expectation);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_fully_match_marketable_gtc_order() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 123, UID_2, 81599, MAX_PRICE, 1, OrderAction::Bid);

        expected.set_ask_volume(0, 74);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_trade(events[0], 2, 81599, 1);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_partially_match_marketable_gtc_order_and_place() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 123, UID_2, 81599, MAX_PRICE, 77, OrderAction::Bid);

        expected.remove_ask(0).insert_bid(0, 81599, 2);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 2);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_fully_match_marketable_gtc_order_2_prices() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 123, UID_2, 81600, MAX_PRICE, 77, OrderAction::Bid);

        expected.remove_ask(0).set_ask_volume(0, 98);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 3);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 2);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_fully_match_marketable_gtc_order_with_all_liquidity_crosses_four_buckets() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 123, UID_2, 220000, MAX_PRICE, 1000, OrderAction::Bid);

        expected.remove_all_asks().insert_bid(0, 220000, 755);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 6);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 100);
        check_trade(events[3], 10, 200954, 10);
        check_trade(events[4], 8, 201000, 28);
        check_trade(events[5], 9, 201000, 32);
        for ev in &events {
            assert!(!ev.active_order_completed);
        }

        clear_order_book(&mut book);
    }

    #[test]
    fn should_move_order_fully_match_as_marketable() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 83, UID_2, 81200, MAX_PRICE, 20, OrderAction::Bid);
        assert_eq!(events_list(&cmd).len(), 0);

        expected.set_bid_volume(2, 40);
        expected.assert_matches(&book.fill_l2(10));

        let (rc, cmd2) = move_cmd(&mut book, 83, UID_2, 81602);
        assert_eq!(rc, CommandResultCode::Success);

        expected.set_bid_volume(2, 20).set_ask_volume(0, 55);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd2);
        assert_eq!(events.len(), 1);
        check_trade(events[0], 2, 81599, 20);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_move_order_fully_match_as_marketable_2_prices() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 83, UID_2, 81594, MAX_PRICE, 100, OrderAction::Bid);
        assert_eq!(events_list(&cmd).len(), 0);

        let (rc, cmd2) = move_cmd(&mut book, 83, UID_2, 81600);
        assert_eq!(rc, CommandResultCode::Success);

        expected.remove_ask(0).set_ask_volume(0, 75);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd2);
        assert_eq!(events.len(), 3);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 25);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_move_order_matches_all_liquidity_crosses_four_buckets() {
        let (mut book, mut expected) = setup_book();
        let _cmd = place_order(&mut book, OrderType::Gtc, 83, UID_2, 81594, MAX_PRICE, 246, OrderAction::Bid);

        let (rc, cmd2) = move_cmd(&mut book, 83, UID_2, 201000);
        assert_eq!(rc, CommandResultCode::Success);

        expected.remove_all_asks().insert_bid(0, 201000, 1);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd2);
        assert_eq!(events.len(), 6);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 100);
        check_trade(events[3], 10, 200954, 10);
        check_trade(events[4], 8, 201000, 28);
        check_trade(events[5], 9, 201000, 32);

        clear_order_book(&mut book);
    }

}
