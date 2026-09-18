use std::collections::BTreeMap;
use std::collections::BTreeSet;

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::l2_market_data::L2MarketData;
use crate::core::common::matcher_event_type::MatcherEventType;
use crate::core::common::matcher_trade_event::MatcherTradeEvent;
use crate::core::common::order_action::OrderAction;
use crate::core::common::order::Order;
use crate::core::common::order_type::OrderType;
use crate::core::common::symbol_type::SymbolType;
use crate::core::orderbook::i_order_book::IOrderBook;
use crate::core::utils::core_arithmetic_utils::{add_exact, mul_exact, sub_exact};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DirectOrder {
    pub order_id: i64,
    pub price: i64,
    pub size: i64,
    pub filled: i64,
    pub filled_notional: i64,
    pub reserve_bid_price: i64,
    pub action: OrderAction,
    pub order_type: OrderType,
    pub command: OrderCommandType,
    pub uid: i64,
    pub timestamp: i64,
    pub user_cookie: i32,
    pub parent: Option<usize>,
    pub next: Option<usize>,
    pub prev: Option<usize>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Bucket {
    pub volume: i64,
    pub num_orders: i32,
    pub tail: usize,
}

#[allow(dead_code)]
pub struct OrderBookDirectImpl {
    orders: Vec<Option<DirectOrder>>,
    order_free: Vec<usize>,
    buckets: Vec<Option<Bucket>>,
    bucket_free: Vec<usize>,
    ask_price_buckets: BTreeMap<i64, usize>,
    bid_price_buckets: BTreeMap<i64, usize>,
    order_id_index: BTreeMap<i64, usize>,
    best_ask: Option<usize>,
    best_bid: Option<usize>,
    symbol_spec: Option<CoreSymbolSpecification>,
}

impl OrderBookDirectImpl {

    fn chain_snapshot(&self, start: Option<usize>) -> Vec<Order> {
        let mut out = Vec::new();
        let mut cur = start;
        while let Some(idx) = cur {
            let o = self.order(idx);
            out.push(Order {
                order_id: o.order_id,
                price: o.price,
                size: o.size,
                filled: o.filled,
                filled_notional: o.filled_notional,
                reserve_bid_price: o.reserve_bid_price,
                action: o.action,
                order_type: o.order_type,
                uid: o.uid,
                timestamp: o.timestamp,
                user_cookie: o.user_cookie,
                command: o.command,
            });
            cur = o.prev;
        }
        out
    }

    fn rebuild_insert(&mut self, o: Order) {
        let order = DirectOrder {
            order_id: o.order_id,
            price: o.price,
            size: o.size,
            filled: o.filled,
            filled_notional: o.filled_notional,
            reserve_bid_price: o.reserve_bid_price,
            action: o.action,
            order_type: o.order_type,
            command: o.command,
            uid: o.uid,
            timestamp: o.timestamp,
            user_cookie: o.user_cookie,
            parent: None,
            next: None,
            prev: None,
        };
        let idx = self.alloc_order(order);
        self.order_id_index.insert(o.order_id, idx);
        self.insert_order(idx, None);
    }

    pub fn chronicle_symbol_spec(&self) -> Option<CoreSymbolSpecification> {
        self.symbol_spec.clone()
    }

    pub fn chronicle_orders(&self) -> (Vec<Order>, Vec<Order>) {
        (self.chain_snapshot(self.best_ask), self.chain_snapshot(self.best_bid))
    }

    pub fn restore_chronicle(symbol_spec: CoreSymbolSpecification, asks: Vec<Order>, bids: Vec<Order>) -> Self {
        let mut b = Self::new();
        b.symbol_spec = Some(symbol_spec);
        for o in asks {
            b.rebuild_insert(o);
        }
        for o in bids {
            b.rebuild_insert(o);
        }
        b
    }
}

impl OrderBookDirectImpl {

    pub fn new() -> Self {
        Self {
            orders: Vec::new(),
            order_free: Vec::new(),
            buckets: Vec::new(),
            bucket_free: Vec::new(),
            ask_price_buckets: BTreeMap::new(),
            bid_price_buckets: BTreeMap::new(),
            order_id_index: BTreeMap::new(),
            best_ask: None,
            best_bid: None,
            symbol_spec: None,
        }
    }

    pub fn with_symbol_spec(symbol_spec: CoreSymbolSpecification) -> Self {
        Self { symbol_spec: Some(symbol_spec), ..Self::new() }
    }

    pub fn insert_order(&mut self, order_idx: usize, free_bucket: Option<usize>) {
        let (is_ask, price, remaining) = {
            let o = self.order(order_idx);
            (o.action == OrderAction::Ask, o.price, o.size - o.filled)
        };

        let existing_bucket = if is_ask {
            self.ask_price_buckets.get(&price).copied()
        } else {
            self.bid_price_buckets.get(&price).copied()
        };

        if let Some(bucket_idx) = existing_bucket {
            if let Some(fb) = free_bucket {
                self.free_bucket(fb);
            }

            let old_tail = self.bucket(bucket_idx).tail;
            let prev_order = self.order(old_tail).prev;

            {
                let b = self.bucket_mut(bucket_idx);
                b.volume += remaining;
                b.num_orders += 1;
                b.tail = order_idx;
            }
            self.order_mut(old_tail).prev = Some(order_idx);
            if let Some(p) = prev_order {
                self.order_mut(p).next = Some(order_idx);
            }
            {
                let o = self.order_mut(order_idx);
                o.next = Some(old_tail);
                o.prev = prev_order;
                o.parent = Some(bucket_idx);
            }
        } else {
            let new_bucket = Bucket { volume: remaining, num_orders: 1, tail: order_idx };
            let bucket_idx = if let Some(fb) = free_bucket {
                *self.bucket_mut(fb) = new_bucket;
                fb
            } else {
                self.alloc_bucket(new_bucket)
            };
            self.order_mut(order_idx).parent = Some(bucket_idx);

            if is_ask {
                self.ask_price_buckets.insert(price, bucket_idx);
            } else {
                self.bid_price_buckets.insert(price, bucket_idx);
            }

            let neighbor_bucket = if is_ask {
                self.ask_price_buckets.range(..price).next_back().map(|(_, &b)| b)
            } else {
                price
                    .checked_add(1)
                    .and_then(|p1| self.bid_price_buckets.range(p1..).next())
                    .map(|(_, &b)| b)
            };

            if let Some(neighbor_idx) = neighbor_bucket {
                let lower_tail = self.bucket(neighbor_idx).tail;
                let prev_order = self.order(lower_tail).prev;

                self.order_mut(lower_tail).prev = Some(order_idx);
                if let Some(p) = prev_order {
                    self.order_mut(p).next = Some(order_idx);
                }
                let o = self.order_mut(order_idx);
                o.next = Some(lower_tail);
                o.prev = prev_order;
            } else {
                let old_best = if is_ask { self.best_ask } else { self.best_bid };
                if let Some(ob) = old_best {
                    self.order_mut(ob).next = Some(order_idx);
                }
                if is_ask {
                    self.best_ask = Some(order_idx);
                } else {
                    self.best_bid = Some(order_idx);
                }
                let o = self.order_mut(order_idx);
                o.next = None;
                o.prev = old_best;
            }
        }
    }

    fn remove_order(&mut self, order_idx: usize) -> Option<usize> {
        let (size, filled, action, price, next, prev, parent) = {
            let o = self.order(order_idx);
            (o.size, o.filled, o.action, o.price, o.next, o.prev, o.parent.expect("order must have parent bucket"))
        };

        {
            let b = self.bucket_mut(parent);
            b.volume -= size - filled;
            b.num_orders -= 1;
        }

        let mut bucket_removed: Option<usize> = None;

        if self.bucket(parent).tail == order_idx {
            let next_shares_bucket = next.and_then(|n| self.order(n).parent) == Some(parent);
            if !next_shares_bucket {
                let is_ask = action == OrderAction::Ask;
                if is_ask {
                    self.ask_price_buckets.remove(&price);
                } else {
                    self.bid_price_buckets.remove(&price);
                }
                bucket_removed = Some(parent);
            } else {
                self.bucket_mut(parent).tail = next.expect("next_shares_bucket implies next.is_some()");
            }
        }

        if let Some(n) = next {
            self.order_mut(n).prev = prev;
        }
        if let Some(p) = prev {
            self.order_mut(p).next = next;
        }

        if Some(order_idx) == self.best_ask {
            self.best_ask = prev;
        } else if Some(order_idx) == self.best_bid {
            self.best_bid = prev;
        }

        bucket_removed
    }

    fn new_order_place_gtc(&mut self, cmd: &mut OrderCommand) {
        let size = cmd.size;
        let action = cmd.action.expect("GTC order requires action");
        let price = cmd.price;
        let reserve_bid_price = cmd.reserve_bid_price;

        let (filled_size, filled_notional) =
            self.try_match_instantly(action, size, reserve_bid_price, Some(price), 0, 0, cmd);

        if filled_size == size {
            return;
        }

        let order_id = cmd.order_id;
        if self.order_id_index.contains_key(&order_id) {
            Self::attach_reject_event(cmd, size - filled_size);
            return;
        }

        let order = DirectOrder {
            order_id,
            price,
            size,
            filled: filled_size,
            filled_notional,
            reserve_bid_price,
            action,
            order_type: cmd.order_type.expect("GTC order requires order_type"),
            command: cmd.command,
            uid: cmd.uid,
            timestamp: cmd.timestamp,
            user_cookie: cmd.user_cookie,
            parent: None,
            next: None,
            prev: None,
        };
        let idx = self.alloc_order(order);
        self.order_id_index.insert(order_id, idx);
        self.insert_order(idx, None);
    }

    pub fn try_match_instantly(
        &mut self,
        taker_action: OrderAction,
        taker_size: i64,
        taker_reserve_bid_price: i64,
        limit_price: Option<i64>,
        taker_prior_filled: i64,
        taker_prior_filled_notional: i64,
        cmd: &mut OrderCommand,
    ) -> (i64, i64) {
        let is_bid = taker_action == OrderAction::Bid;

        let mut maker = if is_bid { self.best_ask } else { self.best_bid };
        let maker_idx0 = match maker {
            Some(idx) => idx,
            None => return (0, 0),
        };
        if let Some(limit) = limit_price {
            let first_price = self.order(maker_idx0).price;
            let first_out_of_limit = if is_bid { first_price > limit } else { first_price < limit };
            if first_out_of_limit {
                return (0, 0);
            }
        }

        let mut remaining = taker_size;
        if remaining == 0 {
            return (0, 0);
        }

        let mut price_bucket_tail: usize = {
            let parent = self.order(maker_idx0).parent.expect("maker must have parent bucket");
            self.bucket(parent).tail
        };

        let mut taker_filled: i64 = 0;
        let mut taker_filled_notional: i64 = 0;

        let mut events: Vec<MatcherTradeEvent> = Vec::new();

        let mut freed_orders: Vec<usize> = Vec::new();

        loop {
            let midx = maker.expect("loop body only runs while maker is Some");

            let (m_size, m_filled_before, m_filled_notional_before, m_price, m_parent, m_prev, m_uid, m_order_id, m_reserve_bid_price, m_command, m_order_type, m_timestamp, m_user_cookie) = {
                let o = self.order(midx);
                (
                    o.size,
                    o.filled,
                    o.filled_notional,
                    o.price,
                    o.parent.expect("maker must have parent bucket"),
                    o.prev,
                    o.uid,
                    o.order_id,
                    o.reserve_bid_price,
                    o.command,
                    o.order_type,
                    o.timestamp,
                    o.user_cookie,
                )
            };

            let trade_size = remaining.min(m_size - m_filled_before);
            let trade_price = m_price;

            taker_filled += trade_size;
            taker_filled_notional = add_exact(taker_filled_notional, mul_exact(trade_size, trade_price));

            {
                let o = self.order_mut(midx);
                o.filled += trade_size;
                o.filled_notional = add_exact(o.filled_notional, mul_exact(trade_size, trade_price));
            }
            self.bucket_mut(m_parent).volume -= trade_size;

            let maker_completed = m_filled_before + trade_size == m_size;
            if maker_completed {
                self.bucket_mut(m_parent).num_orders -= 1;
            }

            remaining -= trade_size;
            let active_order_completed = remaining == 0;

            let bidder_hold_price = if is_bid { taker_reserve_bid_price } else { m_reserve_bid_price };

            events.push(MatcherTradeEvent {
                event_type: MatcherEventType::Trade,
                active_order_completed,
                maker_order_id: m_order_id,
                maker_order_completed: maker_completed,
                price: trade_price,
                size: trade_size,
                bid_gt_ask: is_bid,
                bidder_hold_price,
                matched_order_uid: m_uid,
                matched_order_command_type: m_command,
                filled: add_exact(taker_prior_filled, taker_filled),
                filled_notional: add_exact(taker_prior_filled_notional, taker_filled_notional),
                matched_order_size: m_size,
                matched_order_price: m_price,
                matched_order_type: m_order_type,
                matched_order_timestamp: m_timestamp,
                matched_user_cookie: m_user_cookie,
                matched_order_filled: m_filled_before + trade_size,
                matched_order_filled_notional: add_exact(m_filled_notional_before, mul_exact(trade_size, trade_price)),
                next: None,
            });

            if !maker_completed {
                break;
            }

            self.order_id_index.remove(&m_order_id);
            freed_orders.push(midx);

            if midx == price_bucket_tail {
                if is_bid {
                    self.ask_price_buckets.remove(&m_price);
                } else {
                    self.bid_price_buckets.remove(&m_price);
                }
                self.free_bucket(m_parent);

                if let Some(p) = m_prev {
                    let pp = self.order(p).parent.expect("prev order must have parent bucket");
                    price_bucket_tail = self.bucket(pp).tail;
                }
            }

            maker = m_prev;

            match maker {
                None => break,
                Some(next_idx) => {
                    if remaining == 0 {
                        break;
                    }
                    if let Some(limit) = limit_price {
                        let np = self.order(next_idx).price;
                        let within_limit = if is_bid { np <= limit } else { np >= limit };
                        if !within_limit {
                            break;
                        }
                    }
                }
            }
        }

        if let Some(midx) = maker {
            self.order_mut(midx).next = None;
        }
        if is_bid {
            self.best_ask = maker;
        } else {
            self.best_bid = maker;
        }

        for idx in freed_orders {
            self.free_order(idx);
        }

        let mut chain: Option<Box<MatcherTradeEvent>> = None;
        for mut ev in events.into_iter().rev() {
            ev.next = chain.take();
            chain = Some(Box::new(ev));
        }
        cmd.matcher_event = chain;

        (taker_filled, taker_filled_notional)
    }

    fn new_order_match_ioc(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("IOC order requires action");
        let price = cmd.price;
        let size = cmd.size;
        let reserve_bid_price = cmd.reserve_bid_price;

        let (filled, _) = self.try_match_instantly(action, size, reserve_bid_price, Some(price), 0, 0, cmd);
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

        let size = cmd.size;
        let budget = cmd.price;
        let reserve_bid_price = cmd.reserve_bid_price;

        let (filled, _) = self.match_against_budget_ioc(size, reserve_bid_price, budget, cmd);
        let rejected_size = size - filled;
        if rejected_size != 0 {
            Self::attach_reject_event(cmd, rejected_size);
        }
    }

    fn new_order_match_fok(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("FOK order requires action");
        let price = cmd.price;
        let size = cmd.size;
        let reserve_bid_price = cmd.reserve_bid_price;

        if self.available_volume_for_match(action, price, size) >= size {
            self.try_match_instantly(action, size, reserve_bid_price, Some(price), 0, 0, cmd);
        } else {
            Self::attach_reject_event(cmd, size);
        }
    }

    fn new_order_match_fok_budget(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("FOK_BUDGET order requires action");
        let size = cmd.size;
        let limit = cmd.price;
        let reserve_bid_price = cmd.reserve_bid_price;

        let budget = self.check_budget_to_fill(action, size);

        if Self::is_budget_limit_satisfied(action, budget, limit) {
            self.try_match_instantly(action, size, reserve_bid_price, None, 0, 0, cmd);
        } else {
            Self::attach_reject_event(cmd, size);
        }
    }

    fn match_against_budget_ioc(
        &mut self,
        taker_size: i64,
        taker_reserve_bid_price: i64,
        mut remaining_budget: i64,
        cmd: &mut OrderCommand,
    ) -> (i64, i64) {
        let mut maker = self.best_ask;
        if maker.is_none() {
            return (0, 0);
        }
        let mut remaining = taker_size;
        if remaining == 0 {
            return (0, 0);
        }

        let mut taker_filled: i64 = 0;
        let mut taker_filled_notional: i64 = 0;
        let mut events: Vec<MatcherTradeEvent> = Vec::new();
        let mut freed_orders: Vec<usize> = Vec::new();

        let mut batch_remaining: i64 = 0;
        let mut price_bucket_tail: usize = 0;

        loop {
            if remaining == 0 {
                break;
            }
            let midx = match maker {
                Some(idx) => idx,
                None => break,
            };

            if batch_remaining == 0 {
                let m_price = self.order(midx).price;
                let affordable = if m_price == 0 { i64::MAX } else { remaining_budget / m_price };
                let size_cap = remaining.min(affordable);
                if size_cap <= 0 {
                    break;
                }
                batch_remaining = size_cap;
                let parent = self.order(midx).parent.expect("maker must have parent bucket");
                price_bucket_tail = self.bucket(parent).tail;
            }

            let (m_size, m_filled_before, m_filled_notional_before, m_price, m_parent, m_prev, m_uid, m_order_id, m_command, m_order_type, m_timestamp, m_user_cookie) = {
                let o = self.order(midx);
                (o.size, o.filled, o.filled_notional, o.price, o.parent.expect("maker must have parent bucket"), o.prev, o.uid, o.order_id, o.command, o.order_type, o.timestamp, o.user_cookie)
            };

            let trade_price = m_price;
            let trade_size = batch_remaining.min(m_size - m_filled_before);

            taker_filled += trade_size;
            taker_filled_notional = add_exact(taker_filled_notional, mul_exact(trade_size, trade_price));
            {
                let o = self.order_mut(midx);
                o.filled += trade_size;
                o.filled_notional = add_exact(o.filled_notional, mul_exact(trade_size, trade_price));
            }
            self.bucket_mut(m_parent).volume -= trade_size;

            let maker_completed = m_filled_before + trade_size == m_size;
            if maker_completed {
                self.bucket_mut(m_parent).num_orders -= 1;
            }

            remaining -= trade_size;
            remaining_budget = sub_exact(remaining_budget, mul_exact(trade_size, trade_price));
            batch_remaining -= trade_size;
            let active_order_completed = remaining == 0;

            events.push(MatcherTradeEvent {
                event_type: MatcherEventType::Trade,
                active_order_completed,
                maker_order_id: m_order_id,
                maker_order_completed: maker_completed,
                price: trade_price,
                size: trade_size,
                bid_gt_ask: true,
                bidder_hold_price: taker_reserve_bid_price,
                matched_order_uid: m_uid,
                matched_order_command_type: m_command,
                filled: taker_filled,
                filled_notional: taker_filled_notional,
                matched_order_size: m_size,
                matched_order_price: m_price,
                matched_order_type: m_order_type,
                matched_order_timestamp: m_timestamp,
                matched_user_cookie: m_user_cookie,
                matched_order_filled: m_filled_before + trade_size,
                matched_order_filled_notional: add_exact(m_filled_notional_before, mul_exact(trade_size, trade_price)),
                next: None,
            });

            if maker_completed {
                self.order_id_index.remove(&m_order_id);
                freed_orders.push(midx);

                if midx == price_bucket_tail {
                    self.ask_price_buckets.remove(&m_price);
                    self.free_bucket(m_parent);
                    batch_remaining = 0;
                }
                maker = m_prev;
            }
        }

        if let Some(midx) = maker {
            self.order_mut(midx).next = None;
        }
        self.best_ask = maker;

        for idx in freed_orders {
            self.free_order(idx);
        }

        let mut chain: Option<Box<MatcherTradeEvent>> = None;
        for mut ev in events.into_iter().rev() {
            ev.next = chain.take();
            chain = Some(Box::new(ev));
        }
        cmd.matcher_event = chain;

        (taker_filled, taker_filled_notional)
    }

    pub fn symbol_spec(&self) -> Option<&CoreSymbolSpecification> {
        self.symbol_spec.as_ref()
    }

    pub fn alloc_order(&mut self, order: DirectOrder) -> usize {
        if let Some(idx) = self.order_free.pop() {
            self.orders[idx] = Some(order);
            idx
        } else {
            self.orders.push(Some(order));
            self.orders.len() - 1
        }
    }

    pub fn free_order(&mut self, idx: usize) {
        self.orders[idx] = None;
        self.order_free.push(idx);
    }

    pub fn order(&self, idx: usize) -> &DirectOrder {
        self.orders[idx].as_ref().expect("dangling order slab index")
    }

    pub fn order_mut(&mut self, idx: usize) -> &mut DirectOrder {
        self.orders[idx].as_mut().expect("dangling order slab index")
    }

    pub fn alloc_bucket(&mut self, bucket: Bucket) -> usize {
        if let Some(idx) = self.bucket_free.pop() {
            self.buckets[idx] = Some(bucket);
            idx
        } else {
            self.buckets.push(Some(bucket));
            self.buckets.len() - 1
        }
    }

    pub fn free_bucket(&mut self, idx: usize) {
        self.buckets[idx] = None;
        self.bucket_free.push(idx);
    }

    pub fn bucket(&self, idx: usize) -> &Bucket {
        self.buckets[idx].as_ref().expect("dangling bucket slab index")
    }

    pub fn bucket_mut(&mut self, idx: usize) -> &mut Bucket {
        self.buckets[idx].as_mut().expect("dangling bucket slab index")
    }

    pub fn validate_internal_state(&self) {
        self.validate_side(true);
        self.validate_side(false);

        let mut chain_ids: BTreeSet<i64> = BTreeSet::new();
        for is_ask in [true, false] {
            let mut cur = if is_ask { self.best_ask } else { self.best_bid };
            while let Some(idx) = cur {
                let o = self.order(idx);
                assert!(chain_ids.insert(o.order_id), "duplicate order_id {} across chains", o.order_id);
                cur = o.prev;
            }
        }
        let index_ids: BTreeSet<i64> = self.order_id_index.keys().copied().collect();
        assert_eq!(
            chain_ids, index_ids,
            "order_id_index must exactly equal the union of both chains (no orphans)"
        );
    }

    fn check_budget_to_fill(&self, action: OrderAction, mut size: i64) -> i64 {
        let mut maker = if action == OrderAction::Bid { self.best_ask } else { self.best_bid };
        let mut budget: i128 = 0;

        while let Some(idx) = maker {
            let o = self.order(idx);
            let price = o.price;
            let parent = o.parent.expect("maker must have parent bucket");
            let bucket = self.bucket(parent);
            let available = bucket.volume;

            if size > available {
                size -= available;
                budget += (available as i128) * (price as i128);
            } else {
                let total = budget + (size as i128) * (price as i128);
                return if total > i64::MAX as i128 { i64::MAX } else { total as i64 };
            }

            maker = self.order(bucket.tail).prev;
        }

        i64::MAX
    }

    fn is_budget_limit_satisfied(action: OrderAction, calculated: i64, limit: i64) -> bool {
        calculated != i64::MAX
            && (calculated == limit || ((action == OrderAction::Bid) != (calculated > limit)))
    }

    fn available_volume_for_match(&self, taker_action: OrderAction, taker_price: i64, taker_size: i64) -> i64 {
        let is_bid = taker_action == OrderAction::Bid;
        let mut maker = if is_bid { self.best_ask } else { self.best_bid };
        let mut available: i128 = 0;

        while let Some(idx) = maker {
            let o = self.order(idx);
            let price = o.price;
            let within_limit = if is_bid { price <= taker_price } else { price >= taker_price };
            if !within_limit {
                break;
            }
            let parent = o.parent.expect("maker must have parent bucket");
            let bucket = self.bucket(parent);
            available += bucket.volume as i128;
            if available >= taker_size as i128 {
                return taker_size;
            }
            maker = self.order(bucket.tail).prev;
        }

        available.min(taker_size as i128) as i64
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

    fn validate_side(&self, is_ask: bool) {
        let side_name = if is_ask { "ask" } else { "bid" };
        let best = if is_ask { self.best_ask } else { self.best_bid };
        let buckets_map = if is_ask { &self.ask_price_buckets } else { &self.bid_price_buckets };

        if let Some(best_idx) = best {
            assert!(
                self.order(best_idx).next.is_none(),
                "{side_name} best.next must be None, order_id={}",
                self.order(best_idx).order_id
            );
        }

        let mut visited: BTreeSet<usize> = BTreeSet::new();
        let mut bucket_volume: BTreeMap<usize, i64> = BTreeMap::new();
        let mut bucket_count: BTreeMap<usize, i32> = BTreeMap::new();
        let mut discovered_buckets: BTreeSet<usize> = BTreeSet::new();

        let mut cur = best;
        let mut closer: Option<(usize, usize, i64)> = None;
        while let Some(idx) = cur {
            assert!(visited.insert(idx), "{side_name} chain revisits slab idx {idx} (cycle?)");
            let o = self.order(idx);

            if let Some((c_idx, _, _)) = closer {
                assert_eq!(
                    o.next,
                    Some(c_idx),
                    "{side_name} chain broken: idx {idx}.next must equal {c_idx} (X.prev==Y ⟺ Y.next==X)"
                );
            }

            assert_eq!(
                o.action,
                if is_ask { OrderAction::Ask } else { OrderAction::Bid },
                "{side_name} chain order_id={} has wrong action",
                o.order_id
            );

            let parent = o.parent.unwrap_or_else(|| panic!("{side_name} order_id={} has no parent bucket", o.order_id));
            discovered_buckets.insert(parent);
            *bucket_volume.entry(parent).or_insert(0) += o.size - o.filled;
            *bucket_count.entry(parent).or_insert(0) += 1;

            let tail_idx = self.bucket(parent).tail;
            assert_eq!(
                self.order(tail_idx).price,
                o.price,
                "{side_name} order_id={} price {} disagrees with its bucket's tail price",
                o.order_id,
                o.price
            );

            if let Some((c_idx, c_parent, c_price)) = closer {
                if c_parent != parent {
                    assert_ne!(
                        c_price,
                        o.price,
                        "{side_name} adjacent orders in different buckets must not share price {}",
                        o.price
                    );
                    if is_ask {
                        assert!(
                            o.price > c_price,
                            "{side_name} price must strictly increase away from best across bucket boundary ({c_price} -> {})",
                            o.price
                        );
                    } else {
                        assert!(
                            o.price < c_price,
                            "{side_name} price must strictly decrease away from best across bucket boundary ({c_price} -> {})",
                            o.price
                        );
                    }
                    assert_eq!(
                        self.bucket(c_parent).tail,
                        c_idx,
                        "{side_name} bucket-boundary order idx {c_idx} must be its bucket's tail"
                    );
                } else {
                    assert_eq!(
                        c_price, o.price,
                        "{side_name} orders sharing a bucket must share the same price"
                    );
                }
            }

            closer = Some((idx, parent, o.price));
            cur = o.prev;
        }

        if let Some((last_idx, last_parent, _)) = closer {
            assert_eq!(
                self.bucket(last_parent).tail,
                last_idx,
                "{side_name} farthest-from-best order idx {last_idx} must be its bucket's tail"
            );
        }

        for (&price, &bucket_idx) in buckets_map.iter() {
            let b = self.bucket(bucket_idx);
            assert_eq!(
                self.order(b.tail).price,
                price,
                "{side_name} bucket at price {price} tail.price mismatch"
            );
            let vol = bucket_volume.get(&bucket_idx).copied().unwrap_or(0);
            let cnt = bucket_count.get(&bucket_idx).copied().unwrap_or(0);
            assert_eq!(b.volume, vol, "{side_name} bucket at price {price} volume mismatch");
            assert_eq!(b.num_orders, cnt, "{side_name} bucket at price {price} num_orders mismatch");
        }

        let map_bucket_set: BTreeSet<usize> = buckets_map.values().copied().collect();
        assert_eq!(
            map_bucket_set, discovered_buckets,
            "{side_name} price-bucket map and chain-reachable buckets must be exactly 1:1 (orphan bucket either direction)"
        );
    }
}

impl Default for OrderBookDirectImpl {
    fn default() -> Self {
        Self::new()
    }
}

impl IOrderBook for OrderBookDirectImpl {

    fn new_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode {
        let rc = match cmd.order_type {
            Some(OrderType::Gtc) => {
                self.new_order_place_gtc(cmd);
                CommandResultCode::Success
            }
            Some(OrderType::Ioc) => {
                self.new_order_match_ioc(cmd);
                CommandResultCode::Success
            }
            Some(OrderType::Fok) => {
                self.new_order_match_fok(cmd);
                CommandResultCode::Success
            }
            Some(OrderType::FokBudget) => {
                self.new_order_match_fok_budget(cmd);
                CommandResultCode::Success
            }
            Some(OrderType::IocBudget) => {
                self.new_order_match_ioc_budget(cmd);
                CommandResultCode::Success
            }
            _ => CommandResultCode::MatchingUnsupportedCommand,
        };
        cmd.result_code = Some(rc);
        rc
    }

    fn cancel_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode {
        let order_id = cmd.order_id;
        let order_idx = match self.order_id_index.get(&order_id) {
            Some(&idx) => idx,
            None => return CommandResultCode::MatchingUnknownOrderId,
        };
        if self.order(order_idx).uid != cmd.uid {
            return CommandResultCode::MatchingUnknownOrderId;
        }

        self.order_id_index.remove(&order_id);
        let free_bucket = self.remove_order(order_idx);
        if let Some(b) = free_bucket {
            self.free_bucket(b);
        }

        let (action, size, filled, filled_notional, price, reserve_bid_price) = {
            let o = self.order(order_idx);
            (o.action, o.size, o.filled, o.filled_notional, o.price, o.reserve_bid_price)
        };

        cmd.action = Some(action);
        cmd.matcher_event = Some(Box::new(MatcherTradeEvent {
            event_type: MatcherEventType::Reduce,
            active_order_completed: true,
            price,
            size: size - filled,
            filled,
            filled_notional,
            bidder_hold_price: reserve_bid_price,
            ..Default::default()
        }));

        self.free_order(order_idx);

        CommandResultCode::Success
    }

    fn reduce_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode {
        let order_id = cmd.order_id;
        let requested = cmd.size;
        if requested <= 0 {
            return CommandResultCode::MatchingReduceFailedWrongSize;
        }

        let order_idx = match self.order_id_index.get(&order_id) {
            Some(&idx) => idx,
            None => return CommandResultCode::MatchingUnknownOrderId,
        };
        if self.order(order_idx).uid != cmd.uid {
            return CommandResultCode::MatchingUnknownOrderId;
        }

        let (size, filled) = {
            let o = self.order(order_idx);
            (o.size, o.filled)
        };
        let remaining = size - filled;
        let reduce_by = requested.min(remaining);
        let can_remove = reduce_by == remaining;

        if can_remove {
            self.order_id_index.remove(&order_id);
            let free_bucket = self.remove_order(order_idx);
            if let Some(b) = free_bucket {
                self.free_bucket(b);
            }
        } else {
            let parent = self.order(order_idx).parent.expect("order must have parent bucket");
            self.order_mut(order_idx).size -= reduce_by;
            self.bucket_mut(parent).volume -= reduce_by;
        }

        let (action, price, reserve_bid_price, filled, filled_notional) = {
            let o = self.order(order_idx);
            (o.action, o.price, o.reserve_bid_price, o.filled, o.filled_notional)
        };

        cmd.matcher_event = Some(Box::new(MatcherTradeEvent {
            event_type: MatcherEventType::Reduce,
            active_order_completed: can_remove,
            price,
            size: reduce_by,
            filled,
            filled_notional,
            bidder_hold_price: reserve_bid_price,
            ..Default::default()
        }));
        cmd.action = Some(action);

        if can_remove {
            self.free_order(order_idx);
        }

        CommandResultCode::Success
    }

    fn move_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode {
        let order_id = cmd.order_id;
        let order_idx = match self.order_id_index.get(&order_id) {
            Some(&idx) => idx,
            None => return CommandResultCode::MatchingUnknownOrderId,
        };
        if self.order(order_idx).uid != cmd.uid {
            return CommandResultCode::MatchingUnknownOrderId;
        }

        let (action, reserve_bid_price) = {
            let o = self.order(order_idx);
            (o.action, o.reserve_bid_price)
        };

        if let Some(spec) = &self.symbol_spec {
            if spec.symbol_type == SymbolType::CurrencyExchangePair
                && action == OrderAction::Bid
                && cmd.price > reserve_bid_price
            {
                return CommandResultCode::MatchingMoveFailedPriceOverRiskLimit;
            }
        }

        let free_bucket = self.remove_order(order_idx);

        let new_price = cmd.price;
        self.order_mut(order_idx).price = new_price;
        cmd.action = Some(action);

        let (existing_size, existing_filled, existing_filled_notional) = {
            let o = self.order(order_idx);
            (o.size, o.filled, o.filled_notional)
        };
        let remaining = existing_size - existing_filled;

        let (matched_now, matched_notional_now) =
            self.try_match_instantly(action, remaining, reserve_bid_price, Some(new_price), existing_filled, existing_filled_notional, cmd);

        let total_filled = existing_filled + matched_now;

        if total_filled == existing_size {
            self.order_id_index.remove(&order_id);
            self.free_order(order_idx);
            if let Some(b) = free_bucket {
                self.free_bucket(b);
            }
            return CommandResultCode::Success;
        }

        {
            let o = self.order_mut(order_idx);
            o.filled = total_filled;
            o.filled_notional = existing_filled_notional + matched_notional_now;
        }
        self.insert_order(order_idx, free_bucket);

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
        for &bucket_idx in self.ask_price_buckets.values() {
            if ask_prices.len() == take {
                break;
            }
            let b = self.bucket(bucket_idx);
            ask_prices.push(self.order(b.tail).price);
            ask_volumes.push(b.volume);
            ask_orders.push(b.num_orders as i64);
        }

        let mut bid_prices = Vec::new();
        let mut bid_volumes = Vec::new();
        let mut bid_orders = Vec::new();
        for &bucket_idx in self.bid_price_buckets.values().rev() {
            if bid_prices.len() == take {
                break;
            }
            let b = self.bucket(bucket_idx);
            bid_prices.push(self.order(b.tail).price);
            bid_volumes.push(b.volume);
            bid_orders.push(b.num_orders as i64);
        }

        L2MarketData { ask_prices, ask_volumes, ask_orders, bid_prices, bid_volumes, bid_orders }
    }

    fn state_hash(&self) -> i32 {
        fn order_hash(o: &DirectOrder) -> i64 {
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
        let mut cur = self.best_ask;
        while let Some(idx) = cur {
            let o = self.order(idx);
            h = h.wrapping_mul(31).wrapping_add(order_hash(o));
            cur = o.prev;
        }
        let mut cur = self.best_bid;
        while let Some(idx) = cur {
            let o = self.order(idx);
            h = h.wrapping_mul(31).wrapping_add(order_hash(o));
            cur = o.prev;
        }
        ((h >> 32) as i32) ^ (h as i32)
    }

    fn find_user_orders(&self, uid: i64) -> Vec<Order> {
        self.order_id_index
            .values()
            .map(|&idx| self.order(idx))
            .filter(|o| o.uid == uid)
            .map(|o| Order {
                order_id: o.order_id,
                price: o.price,
                size: o.size,
                filled: o.filled,
                filled_notional: o.filled_notional,
                reserve_bid_price: o.reserve_bid_price,
                action: o.action,
                order_type: o.order_type,
                uid: o.uid,
                timestamp: o.timestamp,
                user_cookie: o.user_cookie,
                command: o.command,
            })
            .collect()
    }
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

impl ChronicleMarshallable for OrderBookDirectImpl {
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_u8(2);
        self.symbol_spec.as_ref().expect("order book missing symbol_spec").chronicle_write(w);
        let (asks, bids) = self.chronicle_orders();
        w.write_i32((asks.len() + bids.len()) as i32);
        for o in &asks {
            o.chronicle_write(w);
        }
        for o in &bids {
            o.chronicle_write(w);
        }
    }
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let impl_type = r.read_u8()?;
        assert_eq!(impl_type, 2, "not a Direct order book (code {impl_type}); Java production order book impl must match Rust (Direct)");
        Self::chronicle_read_body(r)
    }
}

impl OrderBookDirectImpl {

    pub fn chronicle_read_body(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let symbol_spec = CoreSymbolSpecification::chronicle_read(r)?;
        let count = r.read_i32()?;
        let mut asks = Vec::new();
        let mut bids = Vec::new();
        for _ in 0..count {
            let o = Order::chronicle_read(r)?;
            if o.action == OrderAction::Ask {
                asks.push(o);
            } else {
                bids.push(o);
            }
        }
        Ok(OrderBookDirectImpl::restore_chronicle(symbol_spec, asks, bids))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_order(order_id: i64, price: i64, size: i64) -> DirectOrder {
        DirectOrder {
            order_id,
            price,
            size,
            filled: 0,
            filled_notional: 0,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
            command: OrderCommandType::PlaceOrder,
            uid: 1,
            timestamp: 0,
            user_cookie: 0,
            parent: None,
            next: None,
            prev: None,
        }
    }

    fn sample_bucket(tail: usize) -> Bucket {
        Bucket { volume: 0, num_orders: 0, tail }
    }

    #[test]
    fn new_builds_empty_book() {
        let book = OrderBookDirectImpl::new();
        assert!(book.orders.is_empty());
        assert!(book.order_free.is_empty());
        assert!(book.buckets.is_empty());
        assert!(book.bucket_free.is_empty());
        assert!(book.ask_price_buckets.is_empty());
        assert!(book.bid_price_buckets.is_empty());
        assert!(book.order_id_index.is_empty());
        assert!(book.best_ask.is_none());
        assert!(book.best_bid.is_none());
        assert!(book.symbol_spec().is_none());
    }

    #[test]
    fn default_matches_new() {
        let book = OrderBookDirectImpl::default();
        assert!(book.orders.is_empty());
        assert!(book.buckets.is_empty());
    }

    #[test]
    fn order_slab_alloc_free_round_trip_reuses_freed_slot() {
        let mut book = OrderBookDirectImpl::new();

        let idx0 = book.alloc_order(sample_order(1, 100, 10));
        let idx1 = book.alloc_order(sample_order(2, 200, 20));
        assert_eq!(idx0, 0);
        assert_eq!(idx1, 1);
        assert_eq!(book.order(idx0).order_id, 1);
        assert_eq!(book.order(idx1).order_id, 2);

        book.free_order(idx0);
        assert_eq!(book.orders.len(), 2);

        let idx2 = book.alloc_order(sample_order(3, 300, 30));
        assert_eq!(idx2, idx0);
        assert_eq!(book.orders.len(), 2);
        assert_eq!(book.order(idx2).order_id, 3);
        assert_eq!(book.order(idx1).order_id, 2);
    }

    #[test]
    fn order_mut_allows_in_place_mutation() {
        let mut book = OrderBookDirectImpl::new();
        let idx = book.alloc_order(sample_order(1, 100, 10));
        book.order_mut(idx).filled = 4;
        assert_eq!(book.order(idx).filled, 4);
    }

    #[test]
    fn bucket_slab_alloc_free_round_trip_reuses_freed_slot() {
        let mut book = OrderBookDirectImpl::new();

        let b0 = book.alloc_bucket(sample_bucket(0));
        let b1 = book.alloc_bucket(sample_bucket(1));
        assert_eq!(b0, 0);
        assert_eq!(b1, 1);

        book.free_bucket(b0);
        assert_eq!(book.buckets.len(), 2);

        let b2 = book.alloc_bucket(sample_bucket(2));
        assert_eq!(b2, b0);
        assert_eq!(book.buckets.len(), 2);
        assert_eq!(book.bucket(b2).tail, 2);
        assert_eq!(book.bucket(b1).tail, 1);
    }

    #[test]
    fn bucket_mut_allows_in_place_mutation() {
        let mut book = OrderBookDirectImpl::new();
        let idx = book.alloc_bucket(sample_bucket(0));
        book.bucket_mut(idx).volume = 99;
        book.bucket_mut(idx).num_orders = 3;
        assert_eq!(book.bucket(idx).volume, 99);
        assert_eq!(book.bucket(idx).num_orders, 3);
    }

    #[test]
    fn skeleton_new_order_reports_unsupported_for_unset_order_type() {
        let mut book = OrderBookDirectImpl::new();
        let mut cmd = OrderCommand {
            order_id: 1,
            symbol: 1,
            price: 100,
            size: 10,
            action: Some(OrderAction::Bid),
            order_type: None,
            uid: 1,
            ..Default::default()
        };
        let rc = book.new_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::MatchingUnsupportedCommand);
        assert_eq!(cmd.result_code, Some(CommandResultCode::MatchingUnsupportedCommand));
    }

    #[test]
    fn skeleton_cancel_reduce_move_report_unknown_order() {
        let mut book = OrderBookDirectImpl::new();
        let mut cancel = OrderCommand { order_id: 1, symbol: 1, uid: 1, ..Default::default() };
        assert_eq!(book.cancel_order(&mut cancel), CommandResultCode::MatchingUnknownOrderId);

        let mut reduce = OrderCommand { order_id: 1, symbol: 1, size: 1, uid: 1, ..Default::default() };
        assert_eq!(book.reduce_order(&mut reduce), CommandResultCode::MatchingUnknownOrderId);

        let mut mv = OrderCommand { order_id: 1, symbol: 1, price: 100, uid: 1, ..Default::default() };
        assert_eq!(book.move_order(&mut mv), CommandResultCode::MatchingUnknownOrderId);
    }

    #[test]
    fn skeleton_fill_l2_returns_empty_snapshot() {
        let book = OrderBookDirectImpl::new();
        let l2 = book.fill_l2(10);
        assert!(l2.ask_prices.is_empty());
        assert!(l2.ask_volumes.is_empty());
        assert!(l2.bid_prices.is_empty());
        assert!(l2.bid_volumes.is_empty());
    }

    #[test]
    fn skeleton_state_hash_returns_zero() {
        let book = OrderBookDirectImpl::new();
        assert_eq!(book.state_hash(), 0);
    }

    fn place_gtc(
        book: &mut OrderBookDirectImpl,
        order_id: i64,
        action: OrderAction,
        price: i64,
        size: i64,
    ) {
        let mut cmd = OrderCommand {
            order_id,
            symbol: 1,
            price,
            size,
            action: Some(action),
            order_type: Some(OrderType::Gtc),
            uid: 1,
            ..Default::default()
        };
        let rc = book.new_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
    }

    #[test]
    fn gtc_place_single_ask_becomes_best_and_validates() {
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Ask, 100, 10);

        assert_eq!(book.order_id_index.get(&1).copied(), book.best_ask);
        let best = book.best_ask.expect("best_ask must be set");
        assert!(book.order(best).next.is_none());
        assert_eq!(book.order(best).prev, None);
        book.validate_internal_state();
    }

    #[test]
    fn gtc_three_asks_out_of_order_fill_l2_ascending_with_correct_volumes() {
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Ask, 110, 5);
        place_gtc(&mut book, 2, OrderAction::Ask, 100, 7);
        place_gtc(&mut book, 3, OrderAction::Ask, 120, 3);
        book.validate_internal_state();

        let l2 = book.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![100, 110, 120]);
        assert_eq!(l2.ask_volumes, vec![7, 5, 3]);
        assert!(l2.bid_prices.is_empty());
        assert!(l2.bid_volumes.is_empty());

        let best = book.best_ask.expect("best_ask must be set");
        assert_eq!(book.order(best).price, 100);
        assert!(book.order(best).next.is_none());
    }

    #[test]
    fn gtc_bids_out_of_order_fill_l2_descending_with_correct_volumes() {
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Bid, 90, 4);
        place_gtc(&mut book, 2, OrderAction::Bid, 100, 6);
        place_gtc(&mut book, 3, OrderAction::Bid, 80, 2);
        book.validate_internal_state();

        let l2 = book.fill_l2(10);
        assert_eq!(l2.bid_prices, vec![100, 90, 80]);
        assert_eq!(l2.bid_volumes, vec![6, 4, 2]);
        assert!(l2.ask_prices.is_empty());

        let best = book.best_bid.expect("best_bid must be set");
        assert_eq!(book.order(best).price, 100);
        assert!(book.order(best).next.is_none());
    }

    #[test]
    fn gtc_same_price_multiple_orders_fifo_tail_and_bucket_aggregation() {
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Ask, 100, 5);
        place_gtc(&mut book, 2, OrderAction::Ask, 100, 7);
        place_gtc(&mut book, 3, OrderAction::Ask, 100, 3);
        book.validate_internal_state();

        let bucket_idx = *book.ask_price_buckets.get(&100).expect("bucket at 100 must exist");
        let bucket = book.bucket(bucket_idx);
        assert_eq!(bucket.volume, 15);
        assert_eq!(bucket.num_orders, 3);
        assert_eq!(book.order(bucket.tail).order_id, 3);

        let best = book.best_ask.expect("best_ask must be set");
        assert_eq!(book.order(best).order_id, 1);
        let second = book.order(best).prev.expect("second order must exist");
        assert_eq!(book.order(second).order_id, 2);
        let third = book.order(second).prev.expect("third order must exist");
        assert_eq!(book.order(third).order_id, 3);
        assert!(book.order(third).prev.is_none());

        let l2 = book.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![100]);
        assert_eq!(l2.ask_volumes, vec![15]);
    }

    #[test]
    fn gtc_mixed_ask_and_bid_chains_are_independent_and_both_validate() {
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Ask, 105, 5);
        place_gtc(&mut book, 2, OrderAction::Bid, 95, 5);
        place_gtc(&mut book, 3, OrderAction::Ask, 100, 5);
        place_gtc(&mut book, 4, OrderAction::Bid, 99, 5);
        book.validate_internal_state();

        let l2 = book.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![100, 105]);
        assert_eq!(l2.bid_prices, vec![99, 95]);
    }

    #[test]
    fn fill_l2_truncates_to_requested_size() {
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Ask, 100, 1);
        place_gtc(&mut book, 2, OrderAction::Ask, 101, 1);
        place_gtc(&mut book, 3, OrderAction::Ask, 102, 1);

        let l2 = book.fill_l2(2);
        assert_eq!(l2.ask_prices, vec![100, 101]);
    }

    #[test]
    fn fill_l2_zero_size_returns_empty_matches_naive_semantics() {
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Ask, 100, 10);
        place_gtc(&mut book, 2, OrderAction::Bid, 90, 5);

        let l2 = book.fill_l2(0);
        assert!(l2.ask_prices.is_empty());
        assert!(l2.ask_volumes.is_empty());
        assert!(l2.bid_prices.is_empty());
        assert!(l2.bid_volumes.is_empty());
    }

    #[test]
    fn fill_l2_negative_size_means_unlimited_matches_naive_semantics() {
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Ask, 100, 1);
        place_gtc(&mut book, 2, OrderAction::Ask, 101, 1);

        let l2 = book.fill_l2(-1);
        assert_eq!(l2.ask_prices, vec![100, 101]);
    }

    #[test]
    #[should_panic(expected = "volume mismatch")]
    fn validate_internal_state_catches_corrupted_bucket_volume() {
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Ask, 100, 10);
        let bucket_idx = *book.ask_price_buckets.get(&100).unwrap();
        book.bucket_mut(bucket_idx).volume = 999;
        book.validate_internal_state();
    }

    #[test]
    #[should_panic(expected = "order_id_index must exactly equal")]
    fn validate_internal_state_catches_orphan_in_order_id_index() {
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Ask, 100, 10);
        book.order_id_index.insert(999, 0);
        book.validate_internal_state();
    }

    use crate::core::orderbook::order_book_naive_impl::OrderBookNaiveImpl;

    fn gtc_cmd(order_id: i64, action: OrderAction, price: i64, size: i64) -> OrderCommand {
        OrderCommand {
            order_id,
            symbol: 1,
            price,
            size,
            action: Some(action),
            order_type: Some(OrderType::Gtc),
            uid: order_id,
            ..Default::default()
        }
    }

    #[test]
    fn two_orders_cross_produce_single_trade_matching_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();

        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));

        let mut d_taker = gtc_cmd(2, OrderAction::Bid, 100, 6);
        direct.new_order(&mut d_taker);
        let mut n_taker = gtc_cmd(2, OrderAction::Bid, 100, 6);
        naive.new_order(&mut n_taker);

        assert_eq!(d_taker.result_code, n_taker.result_code);
        assert_eq!(
            d_taker.matcher_event, n_taker.matcher_event,
            "Direct's event chain must exactly match Naive's, field by field"
        );

        let ev = d_taker.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.maker_order_id, 1);
        assert_eq!(ev.matched_order_uid, 1);
        assert_eq!(ev.price, 100);
        assert_eq!(ev.size, 6);
        assert!(!ev.maker_order_completed, "maker only partially filled (6 of 10), should remain resting");
        assert!(ev.active_order_completed, "taker fully filled (6)");
        assert!(ev.next.is_none(), "there should be exactly one trade");

        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        direct.validate_internal_state();
    }

    #[test]
    fn multi_bucket_sweep_matches_naive_event_chain_and_completion_timing() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();

        for (id, price, size) in [(1i64, 100i64, 5i64), (2, 101, 7), (3, 102, 4)] {
            direct.new_order(&mut gtc_cmd(id, OrderAction::Ask, price, size));
            naive.new_order(&mut gtc_cmd(id, OrderAction::Ask, price, size));
        }

        let mut d_taker = gtc_cmd(10, OrderAction::Bid, 101, 8);
        direct.new_order(&mut d_taker);
        let mut n_taker = gtc_cmd(10, OrderAction::Bid, 101, 8);
        naive.new_order(&mut n_taker);

        assert_eq!(
            d_taker.matcher_event, n_taker.matcher_event,
            "multi-bucket sweep event chain must exactly match Naive, field by field"
        );

        let ev1 = d_taker.matcher_event.as_ref().expect("expected the first trade");
        assert_eq!(ev1.maker_order_id, 1);
        assert_eq!(ev1.price, 100);
        assert_eq!(ev1.size, 5);
        assert!(ev1.maker_order_completed);
        assert!(!ev1.active_order_completed, "taker's remaining size not yet fully filled (5 of 8)");

        let ev2 = ev1.next.as_ref().expect("expected the second trade");
        assert_eq!(ev2.maker_order_id, 2);
        assert_eq!(ev2.price, 101);
        assert_eq!(ev2.size, 3);
        assert!(!ev2.maker_order_completed, "bucket at 101 only filled 3/7, maker remains resting");
        assert!(ev2.active_order_completed, "taker fully filled (5+3=8)");
        assert!(ev2.next.is_none());

        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        let l2 = direct.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![101, 102]);
        assert_eq!(l2.ask_volumes, vec![4, 4]);
        direct.validate_internal_state();
    }

    #[test]
    fn gtc_partial_fill_rests_remainder_and_l2_reflects() {
        let mut direct = OrderBookDirectImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 5));

        let mut taker = gtc_cmd(2, OrderAction::Bid, 100, 12);
        direct.new_order(&mut taker);
        assert_eq!(taker.result_code, Some(CommandResultCode::Success));

        let ev = taker.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.size, 5);
        assert!(ev.maker_order_completed);
        assert!(!ev.active_order_completed, "taker's remaining 7 unfilled, must rest on book");

        let l2 = direct.fill_l2(10);
        assert!(l2.ask_prices.is_empty());
        assert_eq!(l2.bid_prices, vec![100]);
        assert_eq!(l2.bid_volumes, vec![7]);

        assert!(direct.order_id_index.contains_key(&2));
        direct.validate_internal_state();
    }

    #[test]
    fn gtc_full_fill_does_not_rest() {
        let mut direct = OrderBookDirectImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));

        let mut taker = gtc_cmd(2, OrderAction::Bid, 100, 10);
        direct.new_order(&mut taker);
        assert_eq!(taker.result_code, Some(CommandResultCode::Success));

        let ev = taker.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.size, 10);
        assert!(ev.maker_order_completed);
        assert!(ev.active_order_completed);

        assert!(!direct.order_id_index.contains_key(&2));
        let l2 = direct.fill_l2(10);
        assert!(l2.ask_prices.is_empty());
        assert!(l2.bid_prices.is_empty());
        direct.validate_internal_state();
    }

    #[test]
    fn trade_event_matched_order_command_type_is_makers_command_not_takers_matching_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();

        let maker = |order_id: i64| OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id,
            symbol: 1,
            price: 100,
            size: 10,
            action: Some(OrderAction::Ask),
            order_type: Some(OrderType::Gtc),
            uid: 501,
            ..Default::default()
        };
        direct.new_order(&mut maker(1));
        naive.new_order(&mut maker(1));

        let taker = |order_id: i64| OrderCommand {
            command: OrderCommandType::ForceLiquidation,
            order_id,
            symbol: 1,
            price: 100,
            size: 4,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Ioc),
            uid: 888,
            ..Default::default()
        };
        let mut d_taker = taker(2);
        direct.new_order(&mut d_taker);
        let mut n_taker = taker(2);
        naive.new_order(&mut n_taker);

        assert_eq!(
            d_taker.matcher_event, n_taker.matcher_event,
            "matched_order_command_type (and all other fields) must match Direct/Naive field by field (P6-G)"
        );
        let ev = d_taker.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(
            ev.matched_order_command_type,
            OrderCommandType::PlaceOrder,
            "matched_order_command_type must take the maker's original command type, not the taker's ForceLiquidation"
        );
    }

    #[test]
    fn trade_event_matched_order_command_type_follows_maker_when_maker_is_force_liquidation_matching_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();

        let maker = |order_id: i64| OrderCommand {
            command: OrderCommandType::ForceLiquidation,
            order_id,
            symbol: 1,
            price: 100,
            size: 10,
            action: Some(OrderAction::Ask),
            order_type: Some(OrderType::Gtc),
            uid: 501,
            ..Default::default()
        };
        direct.new_order(&mut maker(1));
        naive.new_order(&mut maker(1));

        let taker = |order_id: i64| OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id,
            symbol: 1,
            price: 100,
            size: 4,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: 888,
            ..Default::default()
        };
        let mut d_taker = taker(2);
        direct.new_order(&mut d_taker);
        let mut n_taker = taker(2);
        naive.new_order(&mut n_taker);

        assert_eq!(d_taker.matcher_event, n_taker.matcher_event);
        let ev = d_taker.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.matched_order_command_type, OrderCommandType::ForceLiquidation);
    }

    #[test]
    fn dup_id_rejects_remaining_after_partial_match_matching_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();

        direct.new_order(&mut gtc_cmd(1, OrderAction::Bid, 50, 5));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Bid, 50, 5));
        direct.new_order(&mut gtc_cmd(2, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(2, OrderAction::Ask, 100, 10));

        let mut d_taker = gtc_cmd(1, OrderAction::Bid, 100, 15);
        direct.new_order(&mut d_taker);
        let mut n_taker = gtc_cmd(1, OrderAction::Bid, 100, 15);
        naive.new_order(&mut n_taker);

        assert_eq!(
            d_taker.matcher_event, n_taker.matcher_event,
            "dup-id reject-remainder event chain must exactly match Naive, field by field"
        );

        let head = d_taker.matcher_event.as_ref().expect("expected an event chain");
        assert_eq!(head.event_type, MatcherEventType::Reject);
        assert_eq!(head.size, 5);
        assert!(head.active_order_completed);
        let trade = head.next.as_ref().expect("expected a trade event after the reject");
        assert_eq!(trade.event_type, MatcherEventType::Trade);
        assert_eq!(trade.size, 10);
        assert_eq!(trade.maker_order_id, 2);
        assert!(trade.next.is_none());

        let l2 = direct.fill_l2(10);
        assert_eq!(l2.ask_prices, Vec::<i64>::new(), "ask@100 fully consumed and cleared");
        assert_eq!(l2.bid_prices, vec![50]);
        assert_eq!(l2.bid_volumes, vec![5]);
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        direct.validate_internal_state();
    }

    fn taker_cmd(order_id: i64, action: OrderAction, order_type: OrderType, price: i64, size: i64) -> OrderCommand {
        OrderCommand {
            order_id,
            symbol: 1,
            price,
            size,
            action: Some(action),
            order_type: Some(order_type),
            uid: order_id,
            ..Default::default()
        }
    }

    #[test]
    fn ioc_discards_unfilled_remainder_never_rests_matching_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 5));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 5));

        let mut d = taker_cmd(2, OrderAction::Bid, OrderType::Ioc, 100, 10);
        direct.new_order(&mut d);
        let mut n = taker_cmd(2, OrderAction::Bid, OrderType::Ioc, 100, 10);
        naive.new_order(&mut n);

        assert_eq!(d.matcher_event, n.matcher_event, "IOC partial-fill + reject-remainder event chain must exactly match Naive, field by field");
        assert_eq!(d.result_code, Some(CommandResultCode::Success));

        let l2 = direct.fill_l2(10);
        assert!(l2.ask_prices.is_empty());
        assert!(l2.bid_prices.is_empty());
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        assert!(!direct.order_id_index.contains_key(&2));
        direct.validate_internal_state();
    }

    #[test]
    fn ioc_full_fill_leaves_no_reject_matching_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));

        let mut d = taker_cmd(2, OrderAction::Bid, OrderType::Ioc, 100, 6);
        direct.new_order(&mut d);
        let mut n = taker_cmd(2, OrderAction::Bid, OrderType::Ioc, 100, 6);
        naive.new_order(&mut n);

        assert_eq!(d.matcher_event, n.matcher_event);
        let ev = d.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert!(ev.next.is_none(), "full fill should carry no REJECT");

        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        direct.validate_internal_state();
    }

    #[test]
    fn ioc_no_liquidity_rejects_whole_size_matching_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();

        let mut d = taker_cmd(1, OrderAction::Bid, OrderType::Ioc, 100, 10);
        direct.new_order(&mut d);
        let mut n = taker_cmd(1, OrderAction::Bid, OrderType::Ioc, 100, 10);
        naive.new_order(&mut n);

        assert_eq!(d.matcher_event, n.matcher_event);
        let ev = d.matcher_event.as_ref().expect("expected a REJECT event");
        assert_eq!(ev.event_type, MatcherEventType::Reject);
        assert_eq!(ev.size, 10);
        assert!(!direct.order_id_index.contains_key(&1));
        direct.validate_internal_state();
    }

    #[test]
    fn fok_budget_rejects_when_budget_insufficient_matching_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));

        let mut d = taker_cmd(2, OrderAction::Bid, OrderType::FokBudget, 500, 10);
        direct.new_order(&mut d);
        let mut n = taker_cmd(2, OrderAction::Bid, OrderType::FokBudget, 500, 10);
        naive.new_order(&mut n);

        assert_eq!(d.matcher_event, n.matcher_event);
        let ev = d.matcher_event.as_ref().expect("expected a REJECT event");
        assert_eq!(ev.event_type, MatcherEventType::Reject);
        assert_eq!(ev.size, 10);
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        assert_eq!(direct.fill_l2(10).ask_volumes, vec![10]);
        direct.validate_internal_state();
    }

    #[test]
    fn fok_budget_matches_when_budget_sufficient_crosses_buckets_matching_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        for (id, price, size) in [(1i64, 100i64, 5i64), (2, 200, 5)] {
            direct.new_order(&mut gtc_cmd(id, OrderAction::Ask, price, size));
            naive.new_order(&mut gtc_cmd(id, OrderAction::Ask, price, size));
        }
        let budget = 1500;
        let mut d = taker_cmd(3, OrderAction::Bid, OrderType::FokBudget, budget, 10);
        direct.new_order(&mut d);
        let mut n = taker_cmd(3, OrderAction::Bid, OrderType::FokBudget, budget, 10);
        naive.new_order(&mut n);

        assert_eq!(d.matcher_event, n.matcher_event, "FOK_BUDGET cross-bucket fill event chain must exactly match Naive, field by field");
        let ev1 = d.matcher_event.as_ref().expect("expected the first trade");
        assert_eq!(ev1.price, 100);
        assert_eq!(ev1.size, 5);
        let ev2 = ev1.next.as_ref().expect("expected the second trade");
        assert_eq!(ev2.price, 200);
        assert_eq!(ev2.size, 5);
        assert!(ev2.next.is_none());

        assert!(direct.fill_l2(10).ask_prices.is_empty());
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        direct.validate_internal_state();
    }

    #[test]
    fn fok_budget_insufficient_liquidity_rejects_matching_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 3));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 3));

        let mut d = taker_cmd(2, OrderAction::Bid, OrderType::FokBudget, i64::MAX, 10);
        direct.new_order(&mut d);
        let mut n = taker_cmd(2, OrderAction::Bid, OrderType::FokBudget, i64::MAX, 10);
        naive.new_order(&mut n);

        assert_eq!(d.matcher_event, n.matcher_event);
        let ev = d.matcher_event.as_ref().expect("expected a REJECT event");
        assert_eq!(ev.event_type, MatcherEventType::Reject);
        assert_eq!(ev.size, 10);
        assert_eq!(direct.fill_l2(10).ask_volumes, vec![3]);
        direct.validate_internal_state();
    }

    #[test]
    fn ruling_p2_1_fok_budget_small_budget_vs_high_priced_ask_matches_naive() {
        {
            let mut direct = OrderBookDirectImpl::new();
            let mut naive = OrderBookNaiveImpl::new();
            direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 1000, 5));
            naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 1000, 5));

            let mut d = taker_cmd(2, OrderAction::Bid, OrderType::FokBudget, 500, 1);
            direct.new_order(&mut d);
            let mut n = taker_cmd(2, OrderAction::Bid, OrderType::FokBudget, 500, 1);
            naive.new_order(&mut n);

            assert_eq!(d.matcher_event, n.matcher_event, "Case A: Direct must reject the whole order consistently with Naive");
            let ev = d.matcher_event.as_ref().expect("expected a REJECT event");
            assert_eq!(ev.event_type, MatcherEventType::Reject);
            assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
            direct.validate_internal_state();
        }
        {
            let mut direct = OrderBookDirectImpl::new();
            let mut naive = OrderBookNaiveImpl::new();
            direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 2));
            naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 2));
            direct.new_order(&mut gtc_cmd(2, OrderAction::Ask, 5000, 1));
            naive.new_order(&mut gtc_cmd(2, OrderAction::Ask, 5000, 1));

            let budget = 100 * 2 + 5000;
            let mut d = taker_cmd(3, OrderAction::Bid, OrderType::FokBudget, budget, 3);
            direct.new_order(&mut d);
            let mut n = taker_cmd(3, OrderAction::Bid, OrderType::FokBudget, budget, 3);
            naive.new_order(&mut n);

            assert_eq!(d.matcher_event, n.matcher_event, "Case B: Direct must fully fill the whole order consistently with Naive, field by field");
            let ev1 = d.matcher_event.as_ref().expect("expected the first trade");
            assert_eq!(ev1.event_type, MatcherEventType::Trade);
            assert_eq!(ev1.price, 100);
            assert_eq!(ev1.size, 2);
            let ev2 = ev1.next.as_ref().expect("expected the second trade");
            assert_eq!(ev2.price, 5000);
            assert_eq!(ev2.size, 1);
            assert!(ev2.next.is_none());

            assert!(direct.fill_l2(10).ask_prices.is_empty(), "both price levels fully consumed");
            assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
            direct.validate_internal_state();
        }
    }

    #[test]
    fn check_budget_to_fill_saturates_on_overflow_instead_of_panicking() {
        let mut direct = OrderBookDirectImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, i64::MAX / 2, 4));
        let budget = direct.check_budget_to_fill(OrderAction::Bid, 4);
        assert_eq!(budget, i64::MAX);
        assert!(!OrderBookDirectImpl::is_budget_limit_satisfied(OrderAction::Bid, budget, i64::MAX));
    }

    #[test]
    fn ioc_budget_partial_fill_capped_by_budget_matches_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));

        let mut d = taker_cmd(2, OrderAction::Bid, OrderType::IocBudget, 250, 10);
        direct.new_order(&mut d);
        let mut n = taker_cmd(2, OrderAction::Bid, OrderType::IocBudget, 250, 10);
        naive.new_order(&mut n);

        assert_eq!(d.matcher_event, n.matcher_event, "IOC_BUDGET budget-capped event chain must exactly match Naive, field by field");
        let head = d.matcher_event.as_ref().expect("expected an event chain");
        assert_eq!(head.event_type, MatcherEventType::Reject);
        assert_eq!(head.size, 8);
        let trade = head.next.as_ref().expect("expected a trade event after the reject");
        assert_eq!(trade.event_type, MatcherEventType::Trade);
        assert_eq!(trade.size, 2);
        assert!(!trade.active_order_completed, "active_order_completed 用全局 remaining（8 未成交待 REJECT），对齐 Java DirectImpl");
        assert!(trade.next.is_none());

        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        assert_eq!(direct.fill_l2(10).ask_volumes, vec![8]);
        direct.validate_internal_state();
    }

    #[test]
    fn ioc_budget_rejects_when_budget_too_small_for_one_unit_matches_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));

        let mut d = taker_cmd(2, OrderAction::Bid, OrderType::IocBudget, 99, 10);
        direct.new_order(&mut d);
        let mut n = taker_cmd(2, OrderAction::Bid, OrderType::IocBudget, 99, 10);
        naive.new_order(&mut n);

        assert_eq!(d.matcher_event, n.matcher_event);
        let ev = d.matcher_event.as_ref().expect("expected a REJECT event");
        assert_eq!(ev.event_type, MatcherEventType::Reject);
        assert_eq!(ev.size, 10);
        assert_eq!(direct.fill_l2(10).ask_volumes, vec![10]);
        direct.validate_internal_state();
    }

    #[test]
    fn ioc_budget_rejects_ask_action_matches_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Bid, 100, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Bid, 100, 10));

        let mut d = taker_cmd(2, OrderAction::Ask, OrderType::IocBudget, 100, 5);
        direct.new_order(&mut d);
        let mut n = taker_cmd(2, OrderAction::Ask, OrderType::IocBudget, 100, 5);
        naive.new_order(&mut n);

        assert_eq!(d.matcher_event, n.matcher_event);
        let ev = d.matcher_event.as_ref().expect("expected a REJECT event");
        assert_eq!(ev.event_type, MatcherEventType::Reject);
        assert_eq!(ev.size, 5);
        assert_eq!(direct.fill_l2(10).bid_volumes, vec![10]);
        direct.validate_internal_state();
    }

    #[test]
    fn ioc_budget_full_fill_with_sufficient_budget_matches_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));

        let mut d = taker_cmd(2, OrderAction::Bid, OrderType::IocBudget, 1000, 10);
        direct.new_order(&mut d);
        let mut n = taker_cmd(2, OrderAction::Bid, OrderType::IocBudget, 1000, 10);
        naive.new_order(&mut n);

        assert_eq!(d.matcher_event, n.matcher_event);
        let ev = d.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert!(ev.next.is_none(), "full fill, no REJECT");
        assert!(direct.fill_l2(10).ask_prices.is_empty());
        direct.validate_internal_state();
    }

    #[test]
    fn ioc_budget_leaves_untouched_sibling_order_in_same_bucket_as_new_best_matches_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 2));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 2));
        direct.new_order(&mut gtc_cmd(2, OrderAction::Ask, 100, 5));
        naive.new_order(&mut gtc_cmd(2, OrderAction::Ask, 100, 5));

        let mut d = taker_cmd(3, OrderAction::Bid, OrderType::IocBudget, 200, 10);
        direct.new_order(&mut d);
        let mut n = taker_cmd(3, OrderAction::Bid, OrderType::IocBudget, 200, 10);
        naive.new_order(&mut n);

        assert_eq!(d.matcher_event, n.matcher_event, "event chain after same-bucket batch closes must exactly match Naive, field by field");
        let head = d.matcher_event.as_ref().expect("expected an event chain");
        assert_eq!(head.event_type, MatcherEventType::Reject);
        assert_eq!(head.size, 8);
        let trade = head.next.as_ref().expect("expected a trade event after the reject");
        assert_eq!(trade.event_type, MatcherEventType::Trade);
        assert_eq!(trade.maker_order_id, 1);
        assert_eq!(trade.size, 2);
        assert!(trade.maker_order_completed);
        assert!(!trade.active_order_completed, "active_order_completed 用全局 remaining（8 未成交待 REJECT），对齐 Java DirectImpl");
        assert!(trade.next.is_none(), "order2 should not be touched");

        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        assert_eq!(direct.fill_l2(10).ask_prices, vec![100]);
        assert_eq!(direct.fill_l2(10).ask_volumes, vec![5]);
        let best = direct.order_id_index.get(&2).copied().expect("order2 should still be in the index");
        assert_eq!(direct.order(best).order_id, 2);
        assert_eq!(direct.order(best).filled, 0, "order2 untouched");
        direct.validate_internal_state();
    }

    #[test]
    fn ioc_budget_recomputes_budget_cap_fresh_across_bucket_boundary_matches_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 3));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 3));
        direct.new_order(&mut gtc_cmd(2, OrderAction::Ask, 200, 100));
        naive.new_order(&mut gtc_cmd(2, OrderAction::Ask, 200, 100));

        let mut d = taker_cmd(3, OrderAction::Bid, OrderType::IocBudget, 1000, 10);
        direct.new_order(&mut d);
        let mut n = taker_cmd(3, OrderAction::Bid, OrderType::IocBudget, 1000, 10);
        naive.new_order(&mut n);

        assert_eq!(
            d.matcher_event, n.matcher_event,
            "cross-price-bucket IOC_BUDGET event chain (including each trade's active_order_completed) must exactly match Naive, field by field"
        );

        let head = d.matcher_event.as_ref().expect("expected an event chain");
        assert_eq!(head.event_type, MatcherEventType::Reject);
        assert_eq!(head.size, 4, "10 - 6 = 4 unfilled");

        let trade1 = head.next.as_ref().expect("expected the first trade(@100)");
        assert_eq!(trade1.event_type, MatcherEventType::Trade);
        assert_eq!(trade1.price, 100);
        assert_eq!(trade1.size, 3);
        assert!(trade1.maker_order_completed, "the only 3 units at price 100 are fully filled");
        assert!(!trade1.active_order_completed, "全局 remaining=7 未清");

        let trade2 = trade1.next.as_ref().expect("expected the second trade(@200)");
        assert_eq!(trade2.event_type, MatcherEventType::Trade);
        assert_eq!(trade2.price, 200);
        assert_eq!(trade2.size, 3, "after crossing buckets must recompute as 700(remaining budget)/200=3, not carry over the old batch's 7");
        assert!(!trade2.maker_order_completed, "the 100-unit resting order was only filled 3");
        assert!(!trade2.active_order_completed, "active_order_completed 用全局 remaining（4 未成交待 REJECT），对齐 Java DirectImpl");
        assert!(trade2.next.is_none());

        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        assert_eq!(direct.fill_l2(10).ask_prices, vec![200]);
        assert_eq!(direct.fill_l2(10).ask_volumes, vec![97]);
        direct.validate_internal_state();
    }

    fn cancel_cmd(order_id: i64, uid: i64) -> OrderCommand {
        OrderCommand { order_id, symbol: 1, uid, ..Default::default() }
    }

    fn reduce_cmd(order_id: i64, uid: i64, size: i64) -> OrderCommand {
        OrderCommand { order_id, symbol: 1, size, uid, ..Default::default() }
    }

    fn move_cmd(order_id: i64, uid: i64, new_price: i64) -> OrderCommand {
        OrderCommand { order_id, symbol: 1, price: new_price, uid, ..Default::default() }
    }

    #[test]
    fn cancel_unknown_order_id_returns_error() {
        let mut book = OrderBookDirectImpl::new();
        let mut cmd = cancel_cmd(999, 1);
        assert_eq!(book.cancel_order(&mut cmd), CommandResultCode::MatchingUnknownOrderId);
    }

    #[test]
    fn cancel_wrong_uid_returns_unknown_order_id_and_order_untouched() {
        let mut book = OrderBookDirectImpl::new();
        book.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));

        let mut cmd = cancel_cmd(1, 999);
        assert_eq!(book.cancel_order(&mut cmd), CommandResultCode::MatchingUnknownOrderId);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![10]);
        book.validate_internal_state();
    }

    #[test]
    fn cancel_releases_resting_order_matches_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));

        let mut d = cancel_cmd(1, 1);
        let d_rc = direct.cancel_order(&mut d);
        let mut n = cancel_cmd(1, 1);
        let n_rc = naive.cancel_order(&mut n);

        assert_eq!(d_rc, n_rc);
        assert_eq!(d_rc, CommandResultCode::Success);
        assert_eq!(d.matcher_event, n.matcher_event, "cancel's REDUCE event must exactly match Naive, field by field");
        assert_eq!(d.action, n.action);

        let ev = d.matcher_event.as_ref().expect("expected a REDUCE event");
        assert_eq!(ev.event_type, MatcherEventType::Reduce);
        assert_eq!(ev.size, 10);
        assert!(ev.active_order_completed);
        assert!(ev.next.is_none());

        assert!(direct.fill_l2(10).ask_prices.is_empty());
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        assert!(!direct.order_id_index.contains_key(&1));
        direct.validate_internal_state();

        let mut again_d = cancel_cmd(1, 1);
        let mut again_n = cancel_cmd(1, 1);
        assert_eq!(direct.cancel_order(&mut again_d), CommandResultCode::MatchingUnknownOrderId);
        assert_eq!(naive.cancel_order(&mut again_n), CommandResultCode::MatchingUnknownOrderId);
    }

    #[test]
    fn cancel_one_of_two_orders_keeps_bucket_and_sibling_chain_matches_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        direct.new_order(&mut gtc_cmd(2, OrderAction::Ask, 100, 5));
        naive.new_order(&mut gtc_cmd(2, OrderAction::Ask, 100, 5));

        let mut d = cancel_cmd(1, 1);
        assert_eq!(direct.cancel_order(&mut d), CommandResultCode::Success);
        let mut n = cancel_cmd(1, 1);
        assert_eq!(naive.cancel_order(&mut n), CommandResultCode::Success);

        let l2 = direct.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![100]);
        assert_eq!(l2.ask_volumes, vec![5]);
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));

        let best = direct.best_ask.expect("best_ask must be set");
        assert_eq!(direct.order(best).order_id, 2);
        assert!(direct.order(best).next.is_none());
        assert!(direct.order(best).prev.is_none());
        direct.validate_internal_state();
    }

    #[test]
    fn cancel_tail_order_when_it_is_only_order_removes_bucket() {
        let mut direct = OrderBookDirectImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Bid, 90, 4));
        direct.new_order(&mut gtc_cmd(2, OrderAction::Bid, 80, 2));

        let mut d = cancel_cmd(1, 1);
        assert_eq!(direct.cancel_order(&mut d), CommandResultCode::Success);

        assert!(!direct.bid_price_buckets.contains_key(&90), "an empty bucket must be removed from the price index");
        assert_eq!(direct.fill_l2(10).bid_prices, vec![80]);
        let best = direct.best_bid.expect("best_bid must be set");
        assert_eq!(direct.order(best).order_id, 2);
        direct.validate_internal_state();
    }

    #[test]
    fn reduce_wrong_size_rejected() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));

        let mut d = reduce_cmd(1, 1, 0);
        let mut n = reduce_cmd(1, 1, 0);
        assert_eq!(direct.reduce_order(&mut d), CommandResultCode::MatchingReduceFailedWrongSize);
        assert_eq!(naive.reduce_order(&mut n), CommandResultCode::MatchingReduceFailedWrongSize);

        let mut d2 = reduce_cmd(1, 1, -5);
        assert_eq!(direct.reduce_order(&mut d2), CommandResultCode::MatchingReduceFailedWrongSize);
    }

    #[test]
    fn reduce_unknown_order_id_returns_error() {
        let mut book = OrderBookDirectImpl::new();
        let mut cmd = reduce_cmd(999, 1, 1);
        assert_eq!(book.reduce_order(&mut cmd), CommandResultCode::MatchingUnknownOrderId);
    }

    #[test]
    fn reduce_wrong_uid_returns_unknown_order_id_and_order_untouched() {
        let mut book = OrderBookDirectImpl::new();
        book.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));

        let mut cmd = reduce_cmd(1, 999, 3);
        assert_eq!(book.reduce_order(&mut cmd), CommandResultCode::MatchingUnknownOrderId);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![10]);
    }

    #[test]
    fn reduce_partial_leaves_order_resting_matches_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));

        let mut d = reduce_cmd(1, 1, 4);
        let d_rc = direct.reduce_order(&mut d);
        let mut n = reduce_cmd(1, 1, 4);
        let n_rc = naive.reduce_order(&mut n);

        assert_eq!(d_rc, n_rc);
        assert_eq!(d.matcher_event, n.matcher_event, "partial reduce's REDUCE event must exactly match Naive, field by field");
        let ev = d.matcher_event.as_ref().expect("expected a REDUCE event");
        assert_eq!(ev.event_type, MatcherEventType::Reduce);
        assert_eq!(ev.size, 4);
        assert!(!ev.active_order_completed);

        assert_eq!(direct.fill_l2(10).ask_volumes, vec![6]);
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        assert!(direct.order_id_index.contains_key(&1));
        let idx = direct.order_id_index[&1];
        assert_eq!(direct.order(idx).size, 6);
        assert_eq!(direct.order(idx).filled, 0);
        direct.validate_internal_state();
    }

    #[test]
    fn reduce_beyond_remaining_removes_order_like_cancel_matches_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));

        let mut d = reduce_cmd(1, 1, 100);
        let mut n = reduce_cmd(1, 1, 100);
        assert_eq!(direct.reduce_order(&mut d), CommandResultCode::Success);
        assert_eq!(naive.reduce_order(&mut n), CommandResultCode::Success);

        assert_eq!(d.matcher_event, n.matcher_event);
        let ev = d.matcher_event.as_ref().expect("expected a REDUCE event");
        assert_eq!(ev.size, 10);
        assert!(ev.active_order_completed);

        assert!(direct.fill_l2(10).ask_prices.is_empty());
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        assert!(!direct.order_id_index.contains_key(&1));
        direct.validate_internal_state();
    }

    #[test]
    fn move_unknown_order_id_returns_error() {
        let mut book = OrderBookDirectImpl::new();
        let mut cmd = move_cmd(999, 1, 100);
        assert_eq!(book.move_order(&mut cmd), CommandResultCode::MatchingUnknownOrderId);
    }

    #[test]
    fn move_wrong_uid_returns_unknown_order_id_and_order_untouched() {
        let mut book = OrderBookDirectImpl::new();
        book.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));

        let mut cmd = move_cmd(1, 999, 105);
        assert_eq!(book.move_order(&mut cmd), CommandResultCode::MatchingUnknownOrderId);
        assert_eq!(book.fill_l2(10).ask_prices, vec![100]);
    }

    #[test]
    fn move_reprices_resting_order_without_crossing_matches_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));

        let mut d = move_cmd(1, 1, 105);
        let mut n = move_cmd(1, 1, 105);
        assert_eq!(direct.move_order(&mut d), CommandResultCode::Success);
        assert_eq!(naive.move_order(&mut n), CommandResultCode::Success);

        assert_eq!(d.matcher_event, n.matcher_event);
        assert!(d.matcher_event.is_none(), "no crossing, no trade event");
        assert_eq!(d.action, n.action);

        let l2 = direct.fill_l2(10);
        assert!(l2.ask_prices.iter().all(|&p| p != 100), "old price level cleared");
        assert_eq!(l2.ask_prices, vec![105]);
        assert_eq!(l2.ask_volumes, vec![10]);
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        direct.validate_internal_state();
    }

    #[test]
    fn move_reprices_into_existing_bucket_matches_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        direct.new_order(&mut gtc_cmd(2, OrderAction::Ask, 110, 5));
        naive.new_order(&mut gtc_cmd(2, OrderAction::Ask, 110, 5));

        let mut d = move_cmd(1, 1, 110);
        let mut n = move_cmd(1, 1, 110);
        assert_eq!(direct.move_order(&mut d), CommandResultCode::Success);
        assert_eq!(naive.move_order(&mut n), CommandResultCode::Success);
        assert_eq!(d.matcher_event, n.matcher_event);

        let l2 = direct.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![110]);
        assert_eq!(l2.ask_volumes, vec![15]);
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        direct.validate_internal_state();
    }

    #[test]
    fn move_crosses_and_trades_immediately_matches_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Bid, 90, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Bid, 90, 10));
        direct.new_order(&mut gtc_cmd(2, OrderAction::Ask, 100, 5));
        naive.new_order(&mut gtc_cmd(2, OrderAction::Ask, 100, 5));

        let mut d = move_cmd(2, 2, 80);
        let mut n = move_cmd(2, 2, 80);
        assert_eq!(direct.move_order(&mut d), CommandResultCode::Success);
        assert_eq!(naive.move_order(&mut n), CommandResultCode::Success);
        assert_eq!(d.matcher_event, n.matcher_event, "TRADE event after move-crossing must exactly match Naive, field by field");

        let ev = d.matcher_event.as_ref().expect("should trade immediately after move crosses");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.maker_order_id, 1);
        assert_eq!(ev.price, 90, "trade price = maker(bid) price, not the move's target price 80");
        assert_eq!(ev.size, 5);
        assert!(!ev.maker_order_completed, "bid(maker) only filled 5/10, not complete");
        assert!(ev.active_order_completed, "ask(taker) fully filled (5)");

        assert!(direct.fill_l2(10).ask_prices.is_empty());
        assert_eq!(direct.fill_l2(10).bid_volumes, vec![5]);
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        assert!(!direct.order_id_index.contains_key(&2));
        direct.validate_internal_state();

        let mut cancel_d = cancel_cmd(2, 2);
        let mut cancel_n = cancel_cmd(2, 2);
        assert_eq!(direct.cancel_order(&mut cancel_d), CommandResultCode::MatchingUnknownOrderId);
        assert_eq!(naive.cancel_order(&mut cancel_n), CommandResultCode::MatchingUnknownOrderId);
    }

    #[test]
    fn move_carries_over_existing_filled_when_fully_matching_matches_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();

        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        direct.new_order(&mut gtc_cmd(2, OrderAction::Bid, 100, 4));
        naive.new_order(&mut gtc_cmd(2, OrderAction::Bid, 100, 4));
        direct.new_order(&mut gtc_cmd(3, OrderAction::Bid, 85, 6));
        naive.new_order(&mut gtc_cmd(3, OrderAction::Bid, 85, 6));

        let idx1 = direct.order_id_index[&1];
        assert_eq!(direct.order(idx1).filled, 4);
        assert_eq!(direct.order(idx1).size, 10);

        let mut d = move_cmd(1, 1, 85);
        let mut n = move_cmd(1, 1, 85);
        assert_eq!(direct.move_order(&mut d), CommandResultCode::Success);
        assert_eq!(naive.move_order(&mut n), CommandResultCode::Success);
        assert_eq!(
            d.matcher_event, n.matcher_event,
            "move-match event carrying pre-existing filled must exactly match Naive, field by field"
        );

        let ev = d.matcher_event.as_ref().expect("should immediately match the remaining 6 after move");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.maker_order_id, 3);
        assert_eq!(ev.size, 6, "the remaining amount (10-4) is fully matched, not re-matched against the order's total size");
        assert!(ev.active_order_completed, "order1 total 4+6=10=size, fully filled");

        assert!(!direct.order_id_index.contains_key(&1));
        assert!(direct.fill_l2(10).ask_prices.is_empty());
        assert!(direct.fill_l2(10).bid_prices.is_empty(), "order3 also fully consumed");
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        direct.validate_internal_state();
    }

    #[test]
    fn cancel_and_reduce_report_prior_partial_fill_in_reduce_event() {
        fn check(book: &mut dyn IOrderBook, reduce: bool) {
            book.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
            book.new_order(&mut gtc_cmd(2, OrderAction::Bid, 100, 4));
            let mut c = if reduce { reduce_cmd(1, 1, 6) } else { cancel_cmd(1, 1) };
            let rc = if reduce { book.reduce_order(&mut c) } else { book.cancel_order(&mut c) };
            assert_eq!(rc, CommandResultCode::Success);
            let ev = c.matcher_event.as_ref().expect("expected a REDUCE event");
            assert_eq!(ev.event_type, MatcherEventType::Reduce);
            assert_eq!(ev.size, 6, "REDUCE size = remaining amount");
            assert_eq!(ev.filled, 4, "cumulative filled amount must be reported with the REDUCE event");
            assert_eq!(ev.filled_notional, 400, "cumulative filled notional = 4*100");
        }
        check(&mut OrderBookDirectImpl::new(), false);
        check(&mut OrderBookNaiveImpl::new(), false);
        check(&mut OrderBookDirectImpl::new(), true);
        check(&mut OrderBookNaiveImpl::new(), true);
    }

    #[test]
    fn move_carries_over_existing_filled_when_partially_matching_rests_with_combined_filled() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();

        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        direct.new_order(&mut gtc_cmd(2, OrderAction::Bid, 100, 3));
        naive.new_order(&mut gtc_cmd(2, OrderAction::Bid, 100, 3));
        direct.new_order(&mut gtc_cmd(3, OrderAction::Bid, 85, 2));
        naive.new_order(&mut gtc_cmd(3, OrderAction::Bid, 85, 2));

        let mut d = move_cmd(1, 1, 85);
        let mut n = move_cmd(1, 1, 85);
        assert_eq!(direct.move_order(&mut d), CommandResultCode::Success);
        assert_eq!(naive.move_order(&mut n), CommandResultCode::Success);
        assert_eq!(d.matcher_event, n.matcher_event);

        let ev = d.matcher_event.as_ref().expect("expected a trade");
        assert_eq!(ev.size, 2);
        assert!(!ev.active_order_completed, "order1 still has 5 (10-3-2) unfilled");

        let idx1 = direct.order_id_index[&1];
        assert_eq!(direct.order(idx1).filled, 5);
        assert_eq!(direct.order(idx1).price, 85);
        assert_eq!(direct.order(idx1).size, 10);

        let l2 = direct.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![85]);
        assert_eq!(l2.ask_volumes, vec![5]);
        assert!(l2.bid_prices.is_empty(), "order3 also fully consumed");
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        direct.validate_internal_state();
    }

    fn exchange_pair_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: 1,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    fn gtc_bid_with_reserve(order_id: i64, price: i64, size: i64, reserve_bid_price: i64) -> OrderCommand {
        OrderCommand {
            order_id,
            symbol: 1,
            price,
            size,
            reserve_bid_price,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: order_id,
            ..Default::default()
        }
    }

    #[test]
    fn move_bid_over_reserve_price_rejected_on_exchange_pair_spec() {
        let mut book = OrderBookDirectImpl::with_symbol_spec(exchange_pair_spec());
        book.new_order(&mut gtc_bid_with_reserve(1, 90, 5, 95));

        let mut cmd = move_cmd(1, 1, 96);
        let rc = book.move_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::MatchingMoveFailedPriceOverRiskLimit);
        assert!(cmd.action.is_none(), "the failure branch must not populate cmd.action (matches Java's early return)");
        assert!(cmd.matcher_event.is_none(), "the failure branch must not produce any event");

        let l2 = book.fill_l2(10);
        assert_eq!(l2.bid_prices, vec![90]);
        assert_eq!(l2.bid_volumes, vec![5]);
        book.validate_internal_state();
    }

    #[test]
    fn move_bid_within_reserve_price_succeeds_on_exchange_pair_spec() {
        let mut book = OrderBookDirectImpl::with_symbol_spec(exchange_pair_spec());
        book.new_order(&mut gtc_bid_with_reserve(1, 90, 5, 95));

        let mut cmd = move_cmd(1, 1, 95);
        let rc = book.move_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(cmd.action, Some(OrderAction::Bid));

        let l2 = book.fill_l2(10);
        assert_eq!(l2.bid_prices, vec![95]);
        assert_eq!(l2.bid_volumes, vec![5]);
        book.validate_internal_state();
    }

    #[test]
    fn move_ask_ignores_reserve_price_guard_even_on_exchange_pair_spec() {
        let mut book = OrderBookDirectImpl::with_symbol_spec(exchange_pair_spec());
        book.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 5));

        let mut cmd = move_cmd(1, 1, 200);
        assert_eq!(book.move_order(&mut cmd), CommandResultCode::Success);
        assert_eq!(book.fill_l2(10).ask_prices, vec![200]);
    }

    #[test]
    fn move_bid_over_reserve_price_allowed_when_symbol_spec_absent() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_bid_with_reserve(1, 90, 5, 95));
        naive.new_order(&mut gtc_bid_with_reserve(1, 90, 5, 95));

        let mut d = move_cmd(1, 1, 999);
        let mut n = move_cmd(1, 1, 999);
        assert_eq!(direct.move_order(&mut d), CommandResultCode::Success);
        assert_eq!(naive.move_order(&mut n), CommandResultCode::Success);
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        direct.validate_internal_state();
    }

    #[test]
    fn cancel_reduce_move_sequence_keeps_internal_state_valid() {
        let mut book = OrderBookDirectImpl::new();
        book.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        book.new_order(&mut gtc_cmd(2, OrderAction::Ask, 100, 5));
        book.new_order(&mut gtc_cmd(3, OrderAction::Ask, 105, 8));
        book.new_order(&mut gtc_cmd(4, OrderAction::Bid, 90, 6));
        book.validate_internal_state();

        assert_eq!(book.reduce_order(&mut reduce_cmd(1, 1, 3)), CommandResultCode::Success);
        book.validate_internal_state();

        assert_eq!(book.move_order(&mut move_cmd(3, 3, 100)), CommandResultCode::Success);
        book.validate_internal_state();

        assert_eq!(book.cancel_order(&mut cancel_cmd(2, 2)), CommandResultCode::Success);
        book.validate_internal_state();

        assert_eq!(book.move_order(&mut move_cmd(4, 4, 100)), CommandResultCode::Success);
        book.validate_internal_state();
    }

    fn seed_mixed_book<B: IOrderBook>(book: &mut B) {
        book.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        book.new_order(&mut gtc_cmd(2, OrderAction::Ask, 100, 5));
        book.new_order(&mut gtc_cmd(3, OrderAction::Ask, 105, 8));
        book.new_order(&mut gtc_cmd(4, OrderAction::Bid, 90, 6));
        book.new_order(&mut gtc_cmd(5, OrderAction::Bid, 90, 3));
        book.new_order(&mut gtc_cmd(6, OrderAction::Bid, 85, 2));
        book.new_order(&mut taker_cmd(7, OrderAction::Bid, OrderType::Ioc, 100, 4));
    }

    #[test]
    fn state_hash_deterministic_for_same_operation_sequence() {
        let mut a = OrderBookDirectImpl::new();
        let mut b = OrderBookDirectImpl::new();
        seed_mixed_book(&mut a);
        seed_mixed_book(&mut b);
        a.validate_internal_state();
        b.validate_internal_state();
        assert_eq!(a.state_hash(), b.state_hash());
    }

    #[test]
    fn state_hash_changes_with_different_book_state() {
        let mut base = OrderBookDirectImpl::new();
        seed_mixed_book(&mut base);
        let h_base = base.state_hash();

        let mut diff_price = OrderBookDirectImpl::new();
        diff_price.new_order(&mut gtc_cmd(1, OrderAction::Ask, 101, 10));
        assert_ne!(h_base, diff_price.state_hash());

        let mut diff_size = OrderBookDirectImpl::new();
        diff_size.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 11));
        assert_ne!(diff_price.state_hash(), diff_size.state_hash());

        let empty = OrderBookDirectImpl::new();
        assert_eq!(empty.state_hash(), 0, "empty book: both chains empty, rolling fold is always 0");
        assert_ne!(h_base, empty.state_hash());
    }

    #[test]
    fn state_hash_matches_naive_on_identical_logical_book() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        seed_mixed_book(&mut direct);
        seed_mixed_book(&mut naive);
        direct.validate_internal_state();

        assert_eq!(direct.fill_l2(10), naive.fill_l2(10), "first confirm the two books' observable snapshots match");
        assert_eq!(
            direct.state_hash(),
            naive.state_hash(),
            "Direct and Naive must produce the same state_hash for the same logical order book (Ruling P2-2)"
        );
    }

    #[test]
    fn state_hash_matches_naive_after_only_asks_seeded() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        direct.new_order(&mut gtc_cmd(2, OrderAction::Ask, 100, 5));
        direct.new_order(&mut gtc_cmd(3, OrderAction::Ask, 105, 8));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(2, OrderAction::Ask, 100, 5));
        naive.new_order(&mut gtc_cmd(3, OrderAction::Ask, 105, 8));
        assert_eq!(direct.state_hash(), naive.state_hash());
    }

    fn slab_idx_of(book: &OrderBookDirectImpl, order_id: i64) -> usize {
        *book.order_id_index.get(&order_id).unwrap()
    }

    #[test]
    #[should_panic(expected = "disagrees with its bucket's tail price")]
    fn validate_internal_state_catches_non_tail_order_price_disagreeing_with_bucket() {
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Ask, 100, 10);
        place_gtc(&mut book, 2, OrderAction::Ask, 100, 5);
        let idx1 = slab_idx_of(&book, 1);
        book.order_mut(idx1).price = 999;
        book.validate_internal_state();
    }

    #[test]
    #[should_panic(expected = "price must strictly increase away from best across bucket boundary")]
    fn validate_internal_state_catches_price_monotonic_violation_across_bucket_boundary() {
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Ask, 100, 10);
        place_gtc(&mut book, 2, OrderAction::Ask, 200, 5);
        let idx1 = slab_idx_of(&book, 1);
        book.order_mut(idx1).price = 300;
        book.validate_internal_state();
    }

    #[test]
    #[should_panic(expected = "adjacent orders in different buckets must not share price")]
    fn validate_internal_state_catches_same_price_adjacent_different_buckets() {
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Ask, 100, 10);
        place_gtc(&mut book, 2, OrderAction::Ask, 200, 5);
        let idx2 = slab_idx_of(&book, 2);
        book.order_mut(idx2).price = 100;
        book.validate_internal_state();
    }

    #[test]
    #[should_panic(expected = "must be its bucket's tail")]
    fn validate_internal_state_catches_bucket_tail_pointing_at_wrong_order() {
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Ask, 100, 10);
        place_gtc(&mut book, 2, OrderAction::Ask, 100, 5);
        let idx1 = slab_idx_of(&book, 1);
        let idx2 = slab_idx_of(&book, 2);
        let bucket_idx = *book.ask_price_buckets.get(&100).unwrap();
        assert_eq!(book.bucket(bucket_idx).tail, idx2, "test setup precondition: order 2 is the actual tail");
        book.bucket_mut(bucket_idx).tail = idx1;
        book.validate_internal_state();
    }

    #[test]
    #[should_panic(expected = "price-bucket map and chain-reachable buckets must be exactly 1:1")]
    fn validate_internal_state_catches_bucket_orphaned_in_art_map() {
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Ask, 100, 10);

        let orphan_order_idx = book.alloc_order(sample_order(999, 12345, 0));
        let orphan_bucket_idx = book.alloc_bucket(sample_bucket(orphan_order_idx));
        book.ask_price_buckets.insert(12345, orphan_bucket_idx);
        book.validate_internal_state();
    }

    #[test]
    #[should_panic(expected = "has wrong action")]
    fn validate_internal_state_catches_wrong_action_in_chain() {
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Ask, 100, 10);
        let idx1 = slab_idx_of(&book, 1);
        book.order_mut(idx1).action = OrderAction::Bid;
        book.validate_internal_state();
    }

    #[test]
    fn snapshot_roundtrip_preserves_state_and_is_deterministic() {
        let mut a = OrderBookDirectImpl::with_symbol_spec(exchange_pair_spec());
        seed_mixed_book(&mut a);
        a.validate_internal_state();

        let mut w = ChronicleWriter::new();
        a.chronicle_write(&mut w);
        let bytes = w.into_bytes();
        let restored = OrderBookDirectImpl::chronicle_read(&mut ChronicleReader::new(&bytes)).expect("chronicle_read");
        restored.validate_internal_state();

        assert_eq!(a.state_hash(), restored.state_hash(), "round-trip state_hash must match");
        assert_eq!(a.fill_l2(-1), restored.fill_l2(-1), "round-trip fill_l2 (price/volume) must match");
        let mut w2 = ChronicleWriter::new();
        restored.chronicle_write(&mut w2);
        assert_eq!(w2.into_bytes(), bytes, "re-serializing the same logical state must produce byte-identical output");
    }

    #[test]
    fn snapshot_is_canonical_across_operation_history() {
        let mut a = OrderBookDirectImpl::with_symbol_spec(exchange_pair_spec());
        seed_mixed_book(&mut a);

        let mut b = OrderBookDirectImpl::with_symbol_spec(exchange_pair_spec());
        for id in [900i64, 901, 902, 903] {
            b.new_order(&mut gtc_cmd(id, OrderAction::Ask, 2_000 + id, 3));
        }
        for id in [900i64, 901, 902, 903] {
            let mut c = OrderCommand { order_id: id, symbol: 1, uid: id, ..Default::default() };
            assert_eq!(b.cancel_order(&mut c), CommandResultCode::Success, "cancel should succeed");
        }
        seed_mixed_book(&mut b);

        assert_eq!(a.state_hash(), b.state_hash(), "both operation histories should reach the same logical state");
        let (mut wa, mut wb) = (ChronicleWriter::new(), ChronicleWriter::new());
        a.chronicle_write(&mut wa);
        b.chronicle_write(&mut wb);
        assert_eq!(
            wa.into_bytes(), wb.into_bytes(),
            "two books with identical logic but different physical slab layouts must serialize to identical bytes (canonicalization, guards against false raft forks)"
        );
    }
}
