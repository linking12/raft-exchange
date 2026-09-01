//! 整簿撮合实现。对应 Java: exchange.core2.core.orderbook.OrderBookNaiveImpl
use std::collections::BTreeMap;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::l2_market_data::L2MarketData;
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::matcher_event_type::MatcherEventType;
use crate::core::common::matcher_trade_event::MatcherTradeEvent;
use crate::core::common::order::Order;
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;
use crate::core::orderbook::i_order_book::IOrderBook;
use crate::core::orderbook::orders_bucket_naive::OrdersBucketNaive;

/// 整簿（naive 实现）。对应 Java `OrderBookNaiveImpl`。
///
/// - `ask_buckets`：升序（`BTreeMap` 自然序），最优价 = 最小 key。
/// - `bid_buckets`：存储用升序 key，但按买方最优价（最高价）遍历时用 `.iter().rev()` / `.range(..).rev()`。
/// - `id_index`：order_id -> (side, price, uid)，用于 O(log n) 定位挂单所在的桶（cancel/reduce/move），
///   `uid` 用于复刻 Java `idMap.get(orderId).uid != cmd.uid` 的所有权校验（Task 7 补全，此前 Task 6 遗留）。
pub struct OrderBookNaiveImpl {
    ask_buckets: BTreeMap<i64, OrdersBucketNaive>,
    bid_buckets: BTreeMap<i64, OrdersBucketNaive>,
    id_index: BTreeMap<i64, (OrderAction, i64, i64)>,
}

impl OrderBookNaiveImpl {
    pub fn new() -> Self {
        Self {
            ask_buckets: BTreeMap::new(),
            bid_buckets: BTreeMap::new(),
            id_index: BTreeMap::new(),
        }
    }

    fn buckets_by_action_mut(&mut self, action: OrderAction) -> &mut BTreeMap<i64, OrdersBucketNaive> {
        match action {
            OrderAction::Ask => &mut self.ask_buckets,
            OrderAction::Bid => &mut self.bid_buckets,
        }
    }

    /// GTC 下单：先尝试即时撮合，再把剩余量挂入本方桶。
    /// 对应 Java `newOrderPlaceGtc`。
    fn new_order_place_gtc(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("GTC order requires action");
        let price = cmd.price;
        let size = cmd.size;

        let filled = self.try_match_instantly(action, price, size, cmd);
        if filled == size {
            // 完全成交，无需挂单（对应 Java: filledSize == size -> return）
            return;
        }

        let order_id = cmd.order_id;
        if self.id_index.contains_key(&order_id) {
            // 重复 order id：能撮合但不能挂单。对应 Java `OrderBookNaiveImpl.newOrderPlaceGtc`：
            // `if (idMap.containsKey(newOrderId)) { attachRejectEvent(cmd, cmd.size - filledSize); return; }`
            // 走到这里已确定 filled < size（filled == size 的完全成交分支在上面已经 return 过）。
            Self::attach_reject_event(cmd, size - filled);
            return;
        }

        let order = Order {
            order_id,
            price,
            size,
            filled,
            reserve_bid_price: cmd.reserve_bid_price,
            action,
            uid: cmd.uid,
            timestamp: cmd.timestamp,
        };

        self.buckets_by_action_mut(action)
            .entry(price)
            .or_insert_with(|| OrdersBucketNaive::new(price))
            .put(order);
        self.id_index.insert(order_id, (action, price, cmd.uid));
    }

    /// 即时撮合 taker（GTC / IOC / FOK 共用主循环，价格受限）。对应 Java `tryMatchInstantly`。
    ///
    /// - Bid taker 吃 `ask_buckets` 中价 <= `taker_price`，从最低价开始。
    /// - Ask taker 吃 `bid_buckets` 中价 >= `taker_price`，从最高价开始。
    ///
    /// 返回 taker 已成交量；撮合产生的 `MatcherTradeEvent` 链（按撮合发生顺序）写入 `cmd.matcher_event`。
    fn try_match_instantly(
        &mut self,
        taker_action: OrderAction,
        taker_price: i64,
        taker_size: i64,
        cmd: &mut OrderCommand,
    ) -> i64 {
        match taker_action {
            // Bid taker 撮 asks：升序遍历（BTreeMap 天然升序）。
            OrderAction::Bid => Self::match_against(
                &mut self.ask_buckets,
                &mut self.id_index,
                Some(taker_price),
                taker_size,
                taker_action,
                true,
                cmd,
            ),
            // Ask taker 撮 bids：降序遍历（先取 >= taker_price 的升序 range，再 .rev()）。
            OrderAction::Ask => Self::match_against(
                &mut self.bid_buckets,
                &mut self.id_index,
                Some(taker_price),
                taker_size,
                taker_action,
                false,
                cmd,
            ),
        }
    }

    /// 无价格上限的全量撮合（对应 Java `FOK_BUDGET` 路径：预算已在调用方校验足够，
    /// 直接吃对手侧全部 buckets 直到 `taker_size` 撮合完毕，不做价格过滤）。
    fn try_match_full(
        &mut self,
        taker_action: OrderAction,
        taker_size: i64,
        cmd: &mut OrderCommand,
    ) -> i64 {
        match taker_action {
            OrderAction::Bid => Self::match_against(
                &mut self.ask_buckets,
                &mut self.id_index,
                None,
                taker_size,
                taker_action,
                true,
                cmd,
            ),
            OrderAction::Ask => Self::match_against(
                &mut self.bid_buckets,
                &mut self.id_index,
                None,
                taker_size,
                taker_action,
                false,
                cmd,
            ),
        }
    }

    /// 撮合主循环的实体：对某一侧（对手侧）buckets 按价格优先级逐桶吃单。
    /// 用静态方法（而非 &mut self 方法）以便同时拿到 `buckets` 和 `id_index` 两个不相交字段的可变借用。
    ///
    /// `taker_price_limit`：`Some(p)` 表示按价格过滤（GTC/IOC/FOK 主路径）；`None` 表示不限价（FOK_BUDGET，
    /// 预算已由调用方预先校验足够覆盖 `taker_size`）。
    fn match_against(
        buckets: &mut BTreeMap<i64, OrdersBucketNaive>,
        id_index: &mut BTreeMap<i64, (OrderAction, i64, i64)>,
        taker_price_limit: Option<i64>,
        taker_size: i64,
        taker_action: OrderAction,
        ascending: bool,
        cmd: &mut OrderCommand,
    ) -> i64 {
        // 对手侧价格优先级序列：bid taker 用 (..=taker_price) 升序；ask taker 用 [taker_price..) 再反转成降序；
        // 无价格上限时取全部 buckets（同样按对应方向排序）。
        let prices: Vec<i64> = match (ascending, taker_price_limit) {
            (true, Some(limit)) => buckets.range(..=limit).map(|(p, _)| *p).collect(),
            (true, None) => buckets.keys().copied().collect(),
            (false, Some(limit)) => buckets.range(limit..).rev().map(|(p, _)| *p).collect(),
            (false, None) => buckets.keys().rev().copied().collect(),
        };

        let mut filled: i64 = 0;
        let mut events: Vec<MatcherTradeEvent> = Vec::new();
        let mut emptied: Vec<i64> = Vec::new();

        // taker 侧的 reserve_bid_price：taker 是 BID 时才有意义（对照 Java
        // `OrdersBucketNaive.match`：`activeOrder.getReserveBidPrice()`），先取出来避免在
        // 下面的闭包里与 `cmd`（结尾要写 `cmd.matcher_event`）产生借用冲突。
        let taker_reserve_bid_price = cmd.reserve_bid_price;

        for p in prices {
            if filled == taker_size {
                break;
            }
            let size_left = taker_size - filled;
            let bucket = buckets.get_mut(&p).expect("bucket must exist for collected price");

            // remaining_in_call 从 size_left 递减；归零即代表 taker 整体成交完毕
            // （size_left 就是 taker 当前总剩余量，与 Java `volumeToCollect == 0` 语义一致）。
            let mut remaining_in_call = size_left;
            bucket.match_forward(size_left, &mut |maker_id, trade, maker_completed, maker_uid, maker_reserve_bid_price| {
                remaining_in_call -= trade;
                let active_order_completed = remaining_in_call == 0;
                // bidderHoldPrice = 成交双方中 BID 那一方的 reserve_bid_price（对照 Java
                // `OrdersBucketNaive.match`: `order.action == ASK ? activeOrder.getReserveBidPrice() : order.reserveBidPrice`）。
                // 对手侧 buckets 里的挂单方向恒为 taker_action 的反面，故 maker 是 ASK <=> taker 是 BID。
                let bidder_hold_price = if taker_action == OrderAction::Bid {
                    taker_reserve_bid_price
                } else {
                    maker_reserve_bid_price
                };
                events.push(MatcherTradeEvent {
                    event_type: MatcherEventType::Trade,
                    active_order_completed,
                    maker_order_id: maker_id,
                    maker_order_completed: maker_completed,
                    price: p, // 成交价 = maker 挂单价（对照 Java: event.price = matchingOrder.getPrice()）
                    size: trade,
                    bid_gt_ask: taker_action == OrderAction::Bid,
                    bidder_hold_price,
                    matched_order_uid: maker_uid,
                    next: None,
                });
                if maker_completed {
                    id_index.remove(&maker_id);
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

        // 按撮合发生顺序拼接单链表（对应 Java 逐桶把 eventsChainHead/Tail 接到 triggerCmd.matcherEvent 上）。
        let mut chain: Option<Box<MatcherTradeEvent>> = None;
        for mut ev in events.into_iter().rev() {
            ev.next = chain.take();
            chain = Some(Box::new(ev));
        }
        cmd.matcher_event = chain;

        filled
    }

    /// 预算受限撮合（对应 Java `tryMatchInstantlyWithBudget`，仅 IOC_BUDGET 使用）：
    /// 按价格优先级逐桶吃单，但每桶可购量额外被 `remaining_budget / bucket_price` 封顶，
    /// 预算耗尽即停（未吃满的剩余量由调用方走 reject）。
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
        let mut events: Vec<MatcherTradeEvent> = Vec::new();
        let mut emptied: Vec<i64> = Vec::new();

        // 同 match_against：先取出 taker 的 reserve_bid_price，避免与结尾的 `cmd.matcher_event` 借用冲突。
        let taker_reserve_bid_price = cmd.reserve_bid_price;

        for p in prices {
            if filled == taker_size {
                break;
            }
            let size_left = taker_size - filled;
            let affordable = if p == 0 { i64::MAX } else { remaining_budget / p };
            let size_cap = size_left.min(affordable);
            if size_cap <= 0 {
                // 预算已不足以再吃一个最小成交单位
                break;
            }
            let bucket = buckets.get_mut(&p).expect("bucket must exist for collected price");

            let mut remaining_in_call = size_cap;
            bucket.match_forward(size_cap, &mut |maker_id, trade, maker_completed, maker_uid, maker_reserve_bid_price| {
                remaining_in_call -= trade;
                let active_order_completed = remaining_in_call == 0;
                // 同 match_against 的 bidderHoldPrice 语义（见该处注释）。
                let bidder_hold_price = if taker_action == OrderAction::Bid {
                    taker_reserve_bid_price
                } else {
                    maker_reserve_bid_price
                };
                events.push(MatcherTradeEvent {
                    event_type: MatcherEventType::Trade,
                    active_order_completed,
                    maker_order_id: maker_id,
                    maker_order_completed: maker_completed,
                    price: p,
                    size: trade,
                    bid_gt_ask: taker_action == OrderAction::Bid,
                    bidder_hold_price,
                    matched_order_uid: maker_uid,
                    next: None,
                });
                remaining_budget -= trade * p;
                if maker_completed {
                    id_index.remove(&maker_id);
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

    /// 不挂单、不改簿的 REJECT 事件，插入到 `cmd.matcher_event` 链头（对应 Java `attachRejectEvent`：
    /// 已有的成交事件链——如部分成交的 IOC——被接到 REJECT 之后）。
    fn attach_reject_event(cmd: &mut OrderCommand, rejected_size: i64) {
        let event = MatcherTradeEvent {
            event_type: MatcherEventType::Reject,
            active_order_completed: true,
            maker_order_id: 0,
            maker_order_completed: false,
            price: cmd.price,
            size: rejected_size,
            bid_gt_ask: false,
            // 对应 Java `attachRejectEvent`: `event.bidderHoldPrice = cmd.reserveBidPrice;`
            // （ASK 命令的 reserve_bid_price 恒为 0，语义上不会被读取）。
            bidder_hold_price: cmd.reserve_bid_price,
            // 对应 Java 注释 "matchedOrderUid; // 0 for rejection"：REJECT 无 maker，恒为 0。
            matched_order_uid: 0,
            next: cmd.matcher_event.take(),
        };
        cmd.matcher_event = Some(Box::new(event));
    }

    /// 侧无副作用地统计对手侧在价格范围内的可撮合总量（FOK 全量可成探测用）。
    /// 对应 Java `subtreeForMatching` 的价格过滤范围，但只求和、不修改任何状态。
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

    /// 无价格限制地探测撮合满 `size` 所需的总预算（对应 Java `checkBudgetToFill`）。
    /// `iter` 须已按价格优先级排好序；纯函数、不修改任何状态。返回 `None` 表示流动性不足以吃满 `size`。
    fn check_budget_to_fill(iter: impl Iterator<Item = (i64, i64)>, mut size: i64) -> Option<i64> {
        let mut budget: i64 = 0;
        for (price, available_size) in iter {
            if size > available_size {
                size -= available_size;
                budget += available_size * price;
            } else {
                return Some(budget + size * price);
            }
        }
        None
    }

    /// 对应 Java `isBudgetLimitSatisfied`：BID 要求成本 <= limit（预算上限），ASK 要求收入 >= limit（最低收入）。
    fn is_budget_limit_satisfied(action: OrderAction, calculated: i64, limit: i64) -> bool {
        calculated == limit || ((action == OrderAction::Bid) != (calculated > limit))
    }

    /// IOC：与 GTC 共用即时撮合主循环，但成交后剩余量直接丢弃（不挂单）；
    /// 未完全成交时对未成交量发 REJECT 事件。对应 Java `newOrderMatchIoc`。
    fn new_order_match_ioc(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("IOC order requires action");
        let price = cmd.price;
        let size = cmd.size;

        let filled = self.try_match_instantly(action, price, size, cmd);
        let rejected_size = size - filled;
        if rejected_size != 0 {
            Self::attach_reject_event(cmd, rejected_size);
        }
    }

    /// IOC_BUDGET：仅支持 BID（用预算上限买），按价逐档吃单直到预算或量耗尽，剩余丢弃。
    /// 对应 Java `newOrderMatchIocBudget`（`cmd.price` 即预算上限）。
    fn new_order_match_ioc_budget(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("IOC_BUDGET order requires action");
        if action != OrderAction::Bid {
            // ASK IOC_BUDGET 语义模糊，不支持：整单拒绝
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

    /// FOK：先无副作用地探测对手侧在价格限制内的可撮合总量，够则整单成交，不够则整单拒绝（不改簿）。
    /// Java 参照中此分支标注 "TODO FOK support"（未落地），此处按 IOC 价格过滤 + 全量判定语义补齐。
    fn new_order_match_fok(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("FOK order requires action");
        let price = cmd.price;
        let size = cmd.size;

        let available = self.available_volume_for_match(action, price);
        if available >= size {
            self.try_match_instantly(action, price, size, cmd);
        } else {
            Self::attach_reject_event(cmd, size);
        }
    }

    /// FOK_BUDGET：无价格限制地探测吃满 `size` 所需总预算，满足 `isBudgetLimitSatisfied` 才整单成交，
    /// 否则整单拒绝（不改簿）。对应 Java `newOrderMatchFokBudget` + `checkBudgetToFill`。
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
                self.try_match_full(action, size, cmd);
            }
            _ => Self::attach_reject_event(cmd, size),
        }
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
                // 未设置 order_type：不支持的命令类型，整单拒绝但不 panic（对应 Java
                // `MatchingEngineRouter` 遇到未知/不支持类型时的 resultCode 报告语义）。
                cmd.result_code = Some(CommandResultCode::MatchingUnsupportedCommand);
                return CommandResultCode::MatchingUnsupportedCommand;
            }
        }
        cmd.result_code = Some(CommandResultCode::Success);
        CommandResultCode::Success
    }

    /// 撤单：按 `id_index` 定位桶并移除，桶空则删桶，最后从 `id_index` 摘除。
    /// 发一枚 REDUCE 事件代表释放的剩余量（`active_order_completed=true`）。
    /// 对应 Java `OrderBookNaiveImpl.cancelOrder` + `OrderBookEventsHelper.sendReduceEvent`。
    /// 未知 order_id → `MatchingUnknownOrderId`（对应 Java `idMap.get == null`）。
    /// 同样地，`order.uid != cmd.uid`（撤销他人订单）也复用 `MatchingUnknownOrderId`——
    /// 对应 Java `if (order == null || order.uid != cmd.uid) return MATCHING_UNKNOWN_ORDER_ID;`。
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
            maker_order_id: 0,
            maker_order_completed: false,
            price: order.price,
            size: remaining,
            bid_gt_ask: false,
            // 对应 Java `sendReduceEvent`: `event.bidderHoldPrice = order.getReserveBidPrice();`
            // （ASK 挂单的 reserve_bid_price 恒为 0）。
            bidder_hold_price: order.reserve_bid_price,
            // 对应 Java `sendReduceEvent` 未赋值 matchedOrderUid（恒为默认 0）。
            matched_order_uid: 0,
            next: None,
        }));
        cmd.action = Some(order.action);

        CommandResultCode::Success
    }

    /// 部分撤销：把订单剩余量减少 `cmd.size`（超过剩余量则整单撤销，等价 cancel）。
    /// 发一枚 REDUCE 事件，`size` = 实际减少量，`active_order_completed` = 是否整单移除。
    /// 对应 Java `OrderBookNaiveImpl.reduceOrder`。
    /// 未知 order_id → `MatchingUnknownOrderId`；请求量 <= 0 → `MatchingReduceFailedWrongSize`；
    /// `order.uid != cmd.uid`（减他人订单）同样归为 `MatchingUnknownOrderId`（对应 Java 语义，见 cancel_order 注释）。
    /// 注意顺序：Java 先判 `requestedReduceSize <= 0`，再查订单是否存在/属主——此处保持一致。
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
            maker_order_id: 0,
            maker_order_completed: false,
            price: order.price,
            size: reduce_by,
            bid_gt_ask: false,
            // 同 cancel_order：对应 Java `sendReduceEvent` 的 bidderHoldPrice/matchedOrderUid 语义。
            bidder_hold_price: order.reserve_bid_price,
            matched_order_uid: 0,
            next: None,
        }));
        cmd.action = Some(order.action);

        CommandResultCode::Success
    }

    /// 移价：撤旧价（桶空则删桶），按新价重新走即时撮合主路径（可能立即成交，也可能挂在新价）。
    /// 对应 Java `OrderBookNaiveImpl.moveOrder`（`subtreeForMatching` + `tryMatchInstantly`）。
    /// 未知 order_id → `MatchingUnknownOrderId`；`order.uid != cmd.uid`（移他人订单）同样归为
    /// `MatchingUnknownOrderId`（对应 Java 语义，见 cancel_order 注释）。
    ///
    /// 注（deferred）：Java 对 `CURRENCY_EXCHANGE_PAIR` 类型 symbol 的 BID 移价会额外校验
    /// `newPrice <= order.reserveBidPrice`（否则 `MATCHING_MOVE_FAILED_PRICE_OVER_RISK_LIMIT`）。
    /// 本移植阶段尚未引入 `SymbolType`/`CoreSymbolSpecification`（见 processors/mod.rs 的 TODO），
    /// 无法区分现货/期货 symbol，因此该守卫推迟到该基础设施落地后再补——现在对所有 BID 移价一律放行。
    /// 见 Task 6 报告 concerns。
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

        // 重新走撮合主路径：taker_size = 订单剩余量（对应 Java `tryMatchInstantly` 以
        // `activeOrder.getFilled()` 为起点累加，此处等价地只喂剩余量、再把返回值叠加到既有 filled 上）。
        let remaining = order.size - order.filled;
        let matched_now = self.try_match_instantly(action, new_price, remaining, cmd);
        let total_filled = order.filled + matched_now;

        if total_filled == order.size {
            // 完全成交（100% marketable）：无需挂单
            self.id_index.remove(&order_id);
            return CommandResultCode::Success;
        }

        order.filled = total_filled;
        self.buckets_by_action_mut(action)
            .entry(new_price)
            .or_insert_with(|| OrdersBucketNaive::new(new_price))
            .put(order);
        self.id_index.insert(order_id, (action, new_price, uid));

        CommandResultCode::Success
    }

    /// L2 快照：ask 升序、bid 降序，各取前 `size` 档。
    /// `size == 0` → 两侧均取 0 档（对应 Java `fillAsks`/`fillBids`: `if (size == 0) { askSize = 0; return; }`）；
    /// `size < 0` 或极大值 → 取全部档位（Java 的 `++i == size` 在 size 为负数时永不成立，等价于遍历到底）。
    fn fill_l2(&self, size: i32) -> L2MarketData {
        let take: usize = match size {
            0 => 0,
            s if s < 0 => usize::MAX,
            s => s as usize,
        };

        let mut ask_prices = Vec::new();
        let mut ask_volumes = Vec::new();
        for (price, bucket) in self.ask_buckets.iter() {
            if ask_prices.len() == take {
                break;
            }
            ask_prices.push(*price);
            ask_volumes.push(bucket.total_volume());
        }

        let mut bid_prices = Vec::new();
        let mut bid_volumes = Vec::new();
        for (price, bucket) in self.bid_buckets.iter().rev() {
            if bid_prices.len() == take {
                break;
            }
            bid_prices.push(*price);
            bid_volumes.push(bucket.total_volume());
        }

        L2MarketData { ask_prices, ask_volumes, bid_prices, bid_volumes }
    }

    /// 确定性状态 hash：按撮合优先级顺序折叠簿内每张挂单的关键字段。
    /// 对应 Java `IOrderBook.stateHash`（`Objects.hash(stateHashStream(askOrdersStream), stateHashStream(bidOrdersStream), symbolSpec.stateHash())`）
    /// 的整体形状——ask 侧按价格升序、bid 侧按价格降序遍历（各自再按 FIFO 挂单序），
    /// 用 `h = h*31 + orderHash` 滚动折叠（对应 Java `HashingUtils.stateHashStream`）。
    ///
    /// 与 Java 的差异（有意，任务书允许）：
    /// - 不折叠 `symbolSpec.stateHash()`——P1 阶段尚无 `CoreSymbolSpecification`；
    /// - `orderHash` 取我们目前持有的字段（order_id/action/price/size/filled/reserve_bid_price/uid），
    ///   Java 版还含 orderType/command/filledNotional/userCookie——这些字段本移植阶段
    ///   要么不存在、要么恒为默认值，纳入不会增加确定性/敏感性，故略去。
    /// 因此不保证与 Java 侧数值相等，只保证「同操作序列 → 同 hash，不同状态 → 不同 hash」。
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
        // ask 侧：BTreeMap 天然升序 == 最优价（最低价）优先。
        for bucket in self.ask_buckets.values() {
            for order in bucket.iter_orders() {
                h = h.wrapping_mul(31).wrapping_add(order_hash(order));
            }
        }
        // bid 侧：按买方最优价（最高价）优先，即降序遍历。
        for bucket in self.bid_buckets.values().rev() {
            for order in bucket.iter_orders() {
                h = h.wrapping_mul(31).wrapping_add(order_hash(order));
            }
        }
        // 折叠 i64 -> i32（对应 Java `Long.hashCode`: high ^ low 32 位）。
        ((h >> 32) as i32) ^ (h as i32)
    }
}

#[cfg(test)]
mod ob_tests {
    use super::*;
    
    

    fn place(book: &mut OrderBookNaiveImpl, id: i64, act: OrderAction, price: i64, size: i64) -> OrderCommand {
        let mut cmd = OrderCommand { order_id: id, symbol: 1, price, size,
            action: Some(act), order_type: Some(OrderType::Gtc), uid: id, ..Default::default() };
        book.new_order(&mut cmd);
        cmd
    }

    #[test]
    fn ioc_discards_remainder() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 5);
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 100, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Ioc), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        // 成交 5，剩 5 丢弃、不挂簿
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
        // 不足量 → 整单拒绝：无成交、卖单仍在
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
        // 全部成交，无 reject 事件
        let ev = cmd.matcher_event.as_ref().expect("应有成交事件");
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
        let ev = cmd.matcher_event.as_ref().expect("应有成交事件");
        assert_eq!(ev.event_type, crate::core::common::matcher_event_type::MatcherEventType::Trade);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![4]);
    }

    #[test]
    fn ioc_budget_caps_by_notional_and_discards_rest() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10); // 10 @ 100 = notional 1000 max
        // 预算 250 只够买 2 个单位（2*100=200 <= 250 < 300）
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 250, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::IocBudget), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        // 未吃满部分（8）走 reject，插在链头；已成交部分（2）紧随其后（对应 Java attachRejectEvent 语义）。
        let head = cmd.matcher_event.as_ref().expect("应有事件链");
        assert_eq!(head.event_type, crate::core::common::matcher_event_type::MatcherEventType::Reject);
        assert_eq!(head.size, 8);
        let trade = head.next.as_ref().expect("reject 之后应有成交事件");
        assert_eq!(trade.event_type, crate::core::common::matcher_event_type::MatcherEventType::Trade);
        assert_eq!(trade.size, 2);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![8]);
    }

    #[test]
    fn fok_budget_rejects_when_budget_insufficient() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10); // 需要 10*100=1000 才能吃满 10
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 500, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::FokBudget), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        let ev = cmd.matcher_event.as_ref().unwrap();
        assert_eq!(ev.event_type, crate::core::common::matcher_event_type::MatcherEventType::Reject);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![10]); // 簿未改变
    }

    #[test]
    fn fok_budget_matches_when_budget_sufficient() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 1000, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::FokBudget), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        let ev = cmd.matcher_event.as_ref().expect("应有成交事件");
        assert_eq!(ev.event_type, crate::core::common::matcher_event_type::MatcherEventType::Trade);
        assert_eq!(ev.size, 10);
        assert_eq!(book.fill_l2(10).ask_prices.len(), 0);
    }

    #[test]
    fn two_orders_cross_into_one_trade() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10); // 挂卖
        let taker = place(&mut book, 2, OrderAction::Bid, 100, 6); // 吃 6
        let ev = taker.matcher_event.as_ref().expect("应有成交事件");
        assert_eq!(ev.event_type, crate::core::common::matcher_event_type::MatcherEventType::Trade);
        assert_eq!(ev.maker_order_id, 1);
        assert_eq!(ev.price, 100);
        assert_eq!(ev.size, 6);
        assert!(ev.next.is_none()); // 只撮一笔
        // 卖单剩 4 仍在簿上
        let l2 = book.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![100]);
        assert_eq!(l2.ask_volumes, vec![4]);
    }

    // ---- Task 3: MatcherTradeEvent.bidder_hold_price / matched_order_uid ----
    //
    // 对照 Java `OrdersBucketNaive.match`：
    //   `bidderHoldPrice = order.action == ASK ? activeOrder.getReserveBidPrice() : order.reserveBidPrice`
    // 其中 `order` 是 maker（挂单方），`activeOrder` 是 taker。`matchedOrderUid` 恒为 maker 的 uid。

    /// maker 是 BID 一方：taker 卖(ASK)吃进挂着的买单。
    /// bidder_hold_price 应取 **maker** 的 reserve_bid_price（taker 的 reserve_bid_price 无关，故意设为
    /// 一个不同的哨兵值，若实现读错了这个测试会失败）。
    #[test]
    fn trade_event_bidder_hold_price_when_maker_is_bid() {
        let mut book = OrderBookNaiveImpl::new();
        // 挂买单：uid=501，reserve_bid_price=12345（> price，模拟风控预留的保守价）
        let mut maker_cmd = OrderCommand {
            order_id: 1, symbol: 1, price: 100, size: 10, reserve_bid_price: 12345,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 501,
            ..Default::default()
        };
        book.new_order(&mut maker_cmd);

        // taker 卖单吃进：uid=777，reserve_bid_price 设一个哨兵值（ASK 单本不应被读取）。
        let mut taker_cmd = OrderCommand {
            order_id: 2, symbol: 1, price: 100, size: 4, reserve_bid_price: 999_999,
            action: Some(OrderAction::Ask), order_type: Some(OrderType::Gtc), uid: 777,
            ..Default::default()
        };
        book.new_order(&mut taker_cmd);

        let ev = taker_cmd.matcher_event.as_ref().expect("应有成交事件");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.matched_order_uid, 501); // maker 的 uid
        assert_eq!(ev.bidder_hold_price, 12345); // maker（BID 方）自己的 reserve_bid_price
    }

    /// taker 是 BID 一方：taker 买(BID)吃进挂着的卖单。
    /// bidder_hold_price 应取 **taker** 的 reserve_bid_price（maker 的 reserve_bid_price 无关，故意设为
    /// 一个不同的哨兵值）。
    #[test]
    fn trade_event_bidder_hold_price_when_taker_is_bid() {
        let mut book = OrderBookNaiveImpl::new();
        // 挂卖单：uid=502。ASK 单的 reserve_bid_price 语义上不冻结价格，设哨兵值证明未被读取。
        let mut maker_cmd = OrderCommand {
            order_id: 1, symbol: 1, price: 200, size: 10, reserve_bid_price: 999_999,
            action: Some(OrderAction::Ask), order_type: Some(OrderType::Gtc), uid: 502,
            ..Default::default()
        };
        book.new_order(&mut maker_cmd);

        // taker 买单吃进：uid=888，reserve_bid_price=20000（风控预留的保守价）。
        let mut taker_cmd = OrderCommand {
            order_id: 2, symbol: 1, price: 200, size: 4, reserve_bid_price: 20000,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 888,
            ..Default::default()
        };
        book.new_order(&mut taker_cmd);

        let ev = taker_cmd.matcher_event.as_ref().expect("应有成交事件");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.matched_order_uid, 502); // maker 的 uid
        assert_eq!(ev.bidder_hold_price, 20000); // taker（BID 方）自己的 reserve_bid_price
    }

    #[test]
    fn new_order_reports_result_code() {
        let mut book = OrderBookNaiveImpl::new();
        // 正常路径（挂单成交/挂簿）：result_code 置 Success，返回值同步。
        let mut cmd = OrderCommand { order_id: 1, symbol: 1, price: 100, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 1, ..Default::default() };
        let rc = book.new_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        assert_eq!(book.fill_l2(10).bid_volumes, vec![10]); // 确实挂上了簿

        // 不支持的 order_type（None）：不 panic，result_code 报 MatchingUnsupportedCommand，且不改簿。
        let mut unsupported = OrderCommand { order_id: 2, symbol: 1, price: 100, size: 5,
            action: Some(OrderAction::Bid), order_type: None, uid: 2, ..Default::default() };
        let rc2 = book.new_order(&mut unsupported);
        assert_eq!(rc2, CommandResultCode::MatchingUnsupportedCommand);
        assert_eq!(unsupported.result_code, Some(CommandResultCode::MatchingUnsupportedCommand));
        // 簿未受影响：仍只有 order_id=1 那笔挂单。
        assert_eq!(book.fill_l2(10).bid_volumes, vec![10]);
    }

    // ---- Task 6: cancel / reduce / move + fill_l2(0) ----

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
        assert_eq!(l2.ask_prices, vec![100, 101, 102]); // 卖侧升序
    }

    #[test]
    fn cancel_removes_resting_order() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, uid: 1, ..Default::default() };
        let rc = book.cancel_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);

        let ev = cmd.matcher_event.as_ref().expect("应有 REDUCE 事件");
        assert_eq!(ev.event_type, MatcherEventType::Reduce);
        assert_eq!(ev.size, 10);
        assert!(ev.active_order_completed);
        assert!(ev.next.is_none());

        // 订单和空桶都已从簿上移除
        let l2 = book.fill_l2(10);
        assert!(l2.ask_prices.is_empty());

        // 二次撤销 → 未知订单
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

        // 桶未空（order 2 还在），价位仍在
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

        let ev = cmd.matcher_event.as_ref().expect("应有 REDUCE 事件");
        assert_eq!(ev.event_type, MatcherEventType::Reduce);
        assert_eq!(ev.size, 4);
        assert!(!ev.active_order_completed);

        assert_eq!(book.fill_l2(10).ask_volumes, vec![6]);
    }

    #[test]
    fn reduce_beyond_remaining_removes_order_like_cancel() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);

        // 请求减 100，但剩余只有 10 → clamp 到 10，整单移除
        let mut cmd = OrderCommand { order_id: 1, symbol: 1, size: 100, uid: 1, ..Default::default() };
        let rc = book.reduce_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);

        let ev = cmd.matcher_event.as_ref().expect("应有 REDUCE 事件");
        assert_eq!(ev.size, 10);
        assert!(ev.active_order_completed);

        assert!(book.fill_l2(10).ask_prices.is_empty());
    }

    // ---- Task 7: uid 所有权校验（补 Task 6 遗留的 concerns）----

    #[test]
    fn cancel_other_users_order_returns_unknown() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10); // uid=1（place() 用 order_id 当 uid）

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, uid: 999, ..Default::default() };
        assert_eq!(book.cancel_order(&mut cmd), CommandResultCode::MatchingUnknownOrderId);
        // 订单未被撤销，仍在簿上
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
        // 未被移价
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

        // 移到 105，不与任何对手方交叉，应原样搬到新价位挂单
        let mut cmd = OrderCommand { order_id: 1, symbol: 1, price: 105, uid: 1, ..Default::default() };
        let rc = book.move_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);
        assert!(cmd.matcher_event.is_none()); // 未成交，无事件

        let l2 = book.fill_l2(10);
        assert!(l2.ask_prices.iter().all(|&p| p != 100)); // 旧价已清空
        assert_eq!(l2.ask_prices, vec![105]);
        assert_eq!(l2.ask_volumes, vec![10]);
    }

    #[test]
    fn move_crosses_and_trades_immediately() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Bid, 90, 10); // 挂买 @90
        place(&mut book, 2, OrderAction::Ask, 100, 5); // 挂卖 @100，稍后移到 80 应与买单立即成交

        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 80, uid: 2, ..Default::default() };
        let rc = book.move_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);

        let ev = cmd.matcher_event.as_ref().expect("移价交叉后应立即成交");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.maker_order_id, 1);
        assert_eq!(ev.size, 5);

        // 卖单 5 全部成交，不再挂簿；买单剩 5
        assert!(book.fill_l2(10).ask_prices.is_empty());
        assert_eq!(book.fill_l2(10).bid_volumes, vec![5]);
    }

    #[test]
    fn move_fully_filled_order_is_removed_from_id_index() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Bid, 90, 5);
        place(&mut book, 2, OrderAction::Ask, 100, 5);

        // 移到 80，恰好与买单 5 全部成交
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 80, uid: 2, ..Default::default() };
        assert_eq!(book.move_order(&mut cmd), CommandResultCode::Success);

        // 完全成交的订单不该再能被撤销
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

    // ---- Task 7: dup-id reject + state_hash ----

    /// 对应 Java `OrderBookNaiveImpl.newOrderPlaceGtc` 的 duplicate-id 分支
    /// （`OrderBookBaseTest.shouldIgnoredDuplicateOrder` 覆盖同语义，但那边用的是"零撮合"场景；
    /// 这里额外覆盖"先撮合、再因重复 id 拒绝剩余"的分支，确保 dup-id 检查在 `try_match_instantly`
    /// 之后触发，且不影响已发生的撮合）。
    ///
    /// 关键构造：order_id=1 的挂单价格设在 taker 撮合价范围之外（不会被这次撮合吃掉），
    /// 因此撮合结束后 id_index 里 order_id=1 依然存在 —— 触发 dup-id 拒绝剩余量。
    #[test]
    fn duplicate_order_id_matches_then_rejects_remainder_and_does_not_place() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10); // 占用 order_id=1，价格 100（本次撮合吃不到）
        place(&mut book, 2, OrderAction::Ask, 90, 6);   // 会被撮合掉的另一张挂单

        // 复用 order_id=1（重复 id）下买单：价格 95 只能吃到 90 这一档（6），吃不到 100 那档
        let mut cmd = OrderCommand { order_id: 1, symbol: 1, price: 95, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 99, ..Default::default() };
        book.new_order(&mut cmd);

        let head = cmd.matcher_event.as_ref().expect("应有事件链：先撮合，再 reject 剩余");
        assert_eq!(head.event_type, MatcherEventType::Reject);
        assert_eq!(head.size, 4); // 10 - 6 撮合 = 4 被拒绝
        let trade = head.next.as_ref().expect("reject 之后应有先前的成交事件");
        assert_eq!(trade.event_type, MatcherEventType::Trade);
        assert_eq!(trade.maker_order_id, 2);
        assert_eq!(trade.size, 6);

        // 原 order_id=1 的挂单（100@10）完好未变；被吃掉的 order_id=2 已消失；新买单未挂簿
        let l2 = book.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![100]);
        assert_eq!(l2.ask_volumes, vec![10]);
        assert!(l2.bid_prices.is_empty());
    }

    /// 未匹配到任何对手单时，重复 id 同样被 reject 整个剩余量（未成交部分即整单）。
    #[test]
    fn duplicate_order_id_full_reject_when_no_match() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10); // 挂卖 10 @100，占用 order_id=1

        // 再次用 order_id=1 挂买单，价格不交叉（不会撮合到 order_id=1 自己，因为买价<卖价）
        let mut cmd = OrderCommand { order_id: 1, symbol: 1, price: 50, size: 7,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 99, ..Default::default() };
        book.new_order(&mut cmd);

        let ev = cmd.matcher_event.as_ref().expect("应有 reject 事件");
        assert_eq!(ev.event_type, MatcherEventType::Reject);
        assert_eq!(ev.size, 7);
        assert!(ev.next.is_none());

        // 原挂单（卖 10 @100）未受影响
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

        // 不同价格
        let mut diff_price = OrderBookNaiveImpl::new();
        place(&mut diff_price, 1, OrderAction::Ask, 101, 10);
        assert_ne!(h1, diff_price.state_hash());

        // 不同 size
        let mut diff_size = OrderBookNaiveImpl::new();
        place(&mut diff_size, 1, OrderAction::Ask, 100, 11);
        assert_ne!(h1, diff_size.state_hash());

        // 多一张挂单
        let mut diff_extra = OrderBookNaiveImpl::new();
        place(&mut diff_extra, 1, OrderAction::Ask, 100, 10);
        place(&mut diff_extra, 2, OrderAction::Bid, 90, 3);
        assert_ne!(h1, diff_extra.state_hash());

        // 部分撮合后剩余量变化也应改变 hash
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


// =======================================================================================
// 翻译自 Java OrderBookBaseTest（exchange-core/.../orderbook/OrderBookBaseTest.java，40 个 @Test）。
//
// 范围（Ruling E）：翻译我们当前引擎能支撑的子集——L2 快照、GTC/IOC/FOK(_BUDGET)/IOC_BUDGET 撮合
// （含跨多桶/多单事件链）、cancel/reduce/move、未知 id 错误、他人订单所有权错误。跳过的用例见
// 本模块末尾注释（对应 task-7-report.md 的 skip 表）。
//
// 适配（不算"跳过"，是按 Ruling E 允许的接口精简做的忠实翻译）：
// - Java `checkEventRejection(event, size, price, bidderHoldPrice)` / `checkEventReduce(..., bidderHoldPrice)`
//   的 bidderHoldPrice 参数——`MatcherTradeEvent` 自 Task 3 起已带 `bidder_hold_price`/`matched_order_uid`
//   字段（见 event.rs），但这批（Task 7 翻译自 Java `OrderBookBaseTest`）测试当初翻译时该字段尚不存在，
//   故 check_reject/check_reduce helper 仍不比对；专门覆盖该字段语义的测试见 ob_tests 模块的
//   `trade_event_bidder_hold_price_when_maker_is_bid` / `..._when_taker_is_bid`。
// - Java `orderBook.getOrderById(id)` / `orderBook.validateInternalState()`——我们的 IOrderBook trait
//   未收录这两个查询/校验方法（P1 精简接口），涉及它们的具体断言行被跳过，测试其余部分照常翻译。
// =======================================================================================
#[cfg(test)]
mod ob_base_tests {
    use super::*;

    const UID_1: i64 = 412;
    const UID_2: i64 = 413;
    const INITIAL_PRICE: i64 = 81600;
    const MAX_PRICE: i64 = 400000;

    // ---------------- 测试专用最小 harness（对应 Java OrderCommandFactory / L2MarketDataHelper）----------------

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

    /// 把 `cmd.matcher_event` 单链表展开成 `Vec`，方便按下标断言（对应 Java `cmd.extractEvents()`）。
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

    /// 对应 Java `checkEventRejection`，略去 bidderHoldPrice 比对（见模块头注释）。
    fn check_reject(ev: &MatcherTradeEvent, size: i64, price: i64) {
        assert_eq!(ev.event_type, MatcherEventType::Reject);
        assert_eq!(ev.size, size);
        assert_eq!(ev.price, price);
        assert!(ev.active_order_completed);
    }

    /// 对应 Java `checkEventReduce`，略去 bidderHoldPrice 比对（Java 该套用例里这个参数其实也一直传 null）。
    fn check_reduce(ev: &MatcherTradeEvent, reduce_size: i64, price: i64, completed: bool) {
        assert_eq!(ev.event_type, MatcherEventType::Reduce);
        assert_eq!(ev.size, reduce_size);
        assert_eq!(ev.price, price);
        assert_eq!(ev.active_order_completed, completed);
        assert!(ev.next.is_none());
    }

    /// 对应 Java `L2MarketDataHelper`：只维护 prices/volumes 两侧数组（不含 order 计数——我们的
    /// `L2MarketData` 本身就没有该字段，Ruling E 只锁定 MatcherTradeEvent 不让扩，L2MarketData 维持
    /// Task 5/6 已定的精简形状，故这里也不引入计数跟踪）。
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

        fn to_l2(&self) -> L2MarketData {
            L2MarketData {
                ask_prices: self.ask_prices.clone(),
                ask_volumes: self.ask_volumes.clone(),
                bid_prices: self.bid_prices.clone(),
                bid_volumes: self.bid_volumes.clone(),
            }
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

        /// 对应 Java `L2MarketDataHelper.aggregateBuyBudget`：沿 ask 侧升序累加至吃满 `size`。
        fn aggregate_buy_budget(&self, mut size: i64) -> i64 {
            let mut budget = 0i64;
            for i in 0..self.ask_prices.len() {
                let v = self.ask_volumes[i];
                let p = self.ask_prices[i];
                if v < size {
                    budget += v * p;
                    size -= v;
                } else {
                    return budget + size * p;
                }
            }
            panic!("Can not collect size {size}");
        }

        /// 对应 Java `L2MarketDataHelper.aggregateSellExpectation`：沿 bid 侧（降序存储）累加至吃满 `size`。
        fn aggregate_sell_expectation(&self, mut size: i64) -> i64 {
            let mut expectation = 0i64;
            for i in 0..self.bid_prices.len() {
                let v = self.bid_volumes[i];
                let p = self.bid_prices[i];
                if v < size {
                    expectation += v * p;
                    size -= v;
                } else {
                    return expectation + size * p;
                }
            }
            panic!("Can not collect size {size}");
        }
    }

    /// 对应 Java `OrderBookBaseTest.before()`：搭好共享初始簿状态 + 校验初始 L2 快照。
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

        assert_eq!(book.fill_l2(25), expected.to_l2());
        (book, expected)
    }

    /// 对应 Java `@AfterEach clearOrderBook`：用两笔吃光全部流动性的 IOC 单把簿清空，验证不留残余状态。
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

    // ------------------------ TESTS WITHOUT MATCHING -----------------------

    #[test]
    fn should_initialize_without_errors() {
        let (mut book, expected) = setup_book();
        assert_eq!(book.fill_l2(25), expected.to_l2());
        clear_order_book(&mut book);
    }

    /// Java `shouldAddGtcOrders`
    #[test]
    fn should_add_gtc_orders() {
        let (mut book, mut expected) = setup_book();

        place_order(&mut book, OrderType::Gtc, 93, UID_1, 81598, 0, 1, OrderAction::Ask);
        expected.insert_ask(0, 81598, 1);

        place_order(&mut book, OrderType::Gtc, 94, UID_1, 81594, MAX_PRICE, 9_000_000_000, OrderAction::Bid);
        expected.insert_bid(0, 81594, 9_000_000_000);

        assert_eq!(book.fill_l2(25), expected.to_l2());

        place_order(&mut book, OrderType::Gtc, 95, UID_1, 130000, 0, 13_000_000_000, OrderAction::Ask);
        expected.insert_ask(3, 130000, 13_000_000_000);

        place_order(&mut book, OrderType::Gtc, 96, UID_1, 1000, MAX_PRICE, 4, OrderAction::Bid);
        expected.insert_bid(6, 1000, 4);

        assert_eq!(book.fill_l2(25), expected.to_l2());
        clear_order_book(&mut book);
    }

    /// Java `shouldIgnoredDuplicateOrder`：非交叉重复 id，整单 reject（不撮合、不挂簿）。
    #[test]
    fn should_ignored_duplicate_order() {
        let (mut book, expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 1, UID_1, 81600, 0, 100, OrderAction::Ask);

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reject(events[0], 100, 81600);

        // 簿完全未变
        assert_eq!(book.fill_l2(25), expected.to_l2());
        clear_order_book(&mut book);
    }

    /// Java `shouldRemoveBidOrder`
    #[test]
    fn should_remove_bid_order() {
        let (mut book, mut expected) = setup_book();

        let (rc, cmd) = cancel_cmd(&mut book, 5, UID_1);
        assert_eq!(rc, CommandResultCode::Success);

        expected.set_bid_volume(1, 1);
        assert_eq!(book.fill_l2(25), expected.to_l2());
        assert_eq!(cmd.action, Some(OrderAction::Bid));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reduce(events[0], 20, 81590, true);

        clear_order_book(&mut book);
    }

    /// Java `shouldRemoveAskOrder`
    #[test]
    fn should_remove_ask_order() {
        let (mut book, mut expected) = setup_book();

        let (rc, cmd) = cancel_cmd(&mut book, 2, UID_1);
        assert_eq!(rc, CommandResultCode::Success);

        expected.set_ask_volume(0, 25);
        assert_eq!(book.fill_l2(25), expected.to_l2());
        assert_eq!(cmd.action, Some(OrderAction::Ask));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reduce(events[0], 50, 81599, true);

        clear_order_book(&mut book);
    }

    /// Java `shouldReduceBidOrder`
    #[test]
    fn should_reduce_bid_order() {
        let (mut book, mut expected) = setup_book();

        let (rc, cmd) = reduce_cmd(&mut book, 5, UID_1, 3);
        assert_eq!(rc, CommandResultCode::Success);

        expected.decrement_bid_volume(1, 3);
        assert_eq!(book.fill_l2(25), expected.to_l2());
        assert_eq!(cmd.action, Some(OrderAction::Bid));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reduce(events[0], 3, 81590, false);

        clear_order_book(&mut book);
    }

    /// Java `shouldReduceAskOrder`（减量超过剩余量 -> 等价整单撤销）
    #[test]
    fn should_reduce_ask_order() {
        let (mut book, mut expected) = setup_book();

        let (rc, cmd) = reduce_cmd(&mut book, 1, UID_1, 300);
        assert_eq!(rc, CommandResultCode::Success);

        expected.remove_ask(1);
        assert_eq!(book.fill_l2(25), expected.to_l2());
        assert_eq!(cmd.action, Some(OrderAction::Ask));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reduce(events[0], 100, 81600, true);

        clear_order_book(&mut book);
    }

    /// Java `shouldRemoveOrderAndEmptyBucket`
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
        assert_eq!(book.fill_l2(25), expected.to_l2());

        let events3 = events_list(&cmd3);
        assert_eq!(events3.len(), 1);
        check_reduce(events3[0], 25, 81599, true);

        clear_order_book(&mut book);
    }

    /// Java `shouldReturnErrorWhenDeletingUnknownOrder`
    #[test]
    fn should_return_error_when_deleting_unknown_order() {
        let (mut book, expected) = setup_book();
        let (rc, cmd) = cancel_cmd(&mut book, 5291, UID_1);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        assert_eq!(book.fill_l2(25), expected.to_l2());
        assert_eq!(events_list(&cmd).len(), 0);
        clear_order_book(&mut book);
    }

    /// Java `shouldReturnErrorWhenDeletingOtherUserOrder`
    #[test]
    fn should_return_error_when_deleting_other_user_order() {
        let (mut book, expected) = setup_book();
        let (rc, cmd) = cancel_cmd(&mut book, 3, UID_2);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        assert!(cmd.matcher_event.is_none());
        assert_eq!(book.fill_l2(25), expected.to_l2());
        clear_order_book(&mut book);
    }

    /// Java `shouldReturnErrorWhenUpdatingOtherUserOrder`
    #[test]
    fn should_return_error_when_updating_other_user_order() {
        let (mut book, expected) = setup_book();

        let (rc, cmd) = move_cmd(&mut book, 2, UID_2, 100);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        assert!(cmd.matcher_event.is_none());

        let (rc2, cmd2) = move_cmd(&mut book, 8, UID_2, 100);
        assert_eq!(rc2, CommandResultCode::MatchingUnknownOrderId);
        assert!(cmd2.matcher_event.is_none());

        assert_eq!(book.fill_l2(25), expected.to_l2());
        clear_order_book(&mut book);
    }

    /// Java `shouldReturnErrorWhenUpdatingUnknownOrder`
    #[test]
    fn should_return_error_when_updating_unknown_order() {
        let (mut book, expected) = setup_book();
        let (rc, cmd) = move_cmd(&mut book, 2433, UID_1, 300);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        assert_eq!(book.fill_l2(10), expected.to_l2());
        assert_eq!(events_list(&cmd).len(), 0);
        clear_order_book(&mut book);
    }

    /// Java `shouldReturnErrorWhenReducingUnknownOrder`
    #[test]
    fn should_return_error_when_reducing_unknown_order() {
        let (mut book, expected) = setup_book();
        let (rc, cmd) = reduce_cmd(&mut book, 3, UID_2, 1);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        assert!(cmd.matcher_event.is_none());
        assert_eq!(book.fill_l2(25), expected.to_l2());
        clear_order_book(&mut book);
    }

    /// Java `shouldReturnErrorWhenReducingByZeroOrNegativeSize`
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

        assert_eq!(book.fill_l2(25), expected.to_l2());
        clear_order_book(&mut book);
    }

    /// Java `shouldReturnErrorWhenReducingOtherUserOrder`
    #[test]
    fn should_return_error_when_reducing_other_user_order() {
        let (mut book, expected) = setup_book();
        let (rc, cmd) = reduce_cmd(&mut book, 8, UID_2, 3);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        assert!(cmd.matcher_event.is_none());
        assert_eq!(book.fill_l2(25), expected.to_l2());
        clear_order_book(&mut book);
    }

    /// Java `shouldMoveOrderExistingBucket`
    #[test]
    fn should_move_order_existing_bucket() {
        let (mut book, mut expected) = setup_book();
        let (rc, cmd) = move_cmd(&mut book, 7, UID_1, 81590);
        assert_eq!(rc, CommandResultCode::Success);

        expected.set_bid_volume(1, 41).remove_bid(2);
        assert_eq!(book.fill_l2(10), expected.to_l2());
        assert_eq!(events_list(&cmd).len(), 0);

        clear_order_book(&mut book);
    }

    /// Java `shouldMoveOrderNewBucket`
    #[test]
    fn should_move_order_new_bucket() {
        let (mut book, mut expected) = setup_book();
        let (rc, cmd) = move_cmd(&mut book, 7, UID_1, 81594);
        assert_eq!(rc, CommandResultCode::Success);

        expected.remove_bid(2).insert_bid(0, 81594, 20);
        assert_eq!(book.fill_l2(10), expected.to_l2());
        assert_eq!(events_list(&cmd).len(), 0);

        clear_order_book(&mut book);
    }

    // ------------------------ MATCHING TESTS -----------------------

    /// Java `shouldMatchIocOrderPartialBBO`
    #[test]
    fn should_match_ioc_order_partial_bbo() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Ioc, 123, UID_2, 1, 0, 10, OrderAction::Ask);

        expected.set_bid_volume(0, 30);
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_trade(events[0], 4, 81593, 10);

        clear_order_book(&mut book);
    }

    /// Java `shouldMatchIocOrderFullBBO`
    #[test]
    fn should_match_ioc_order_full_bbo() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Ioc, 123, UID_2, 1, 0, 40, OrderAction::Ask);

        expected.remove_bid(0);
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_trade(events[0], 4, 81593, 40);

        clear_order_book(&mut book);
    }

    /// Java `shouldMatchIocOrderWithTwoLimitOrdersPartial`（略过 `getOrderById` 断言，见模块头注释）
    #[test]
    fn should_match_ioc_order_with_two_limit_orders_partial() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Ioc, 123, UID_2, 1, 0, 41, OrderAction::Ask);

        expected.remove_bid(0).set_bid_volume(0, 20);
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 2);
        check_trade(events[0], 4, 81593, 40);
        check_trade(events[1], 5, 81590, 1);

        clear_order_book(&mut book);
    }

    /// Java `shouldMatchIocOrderFullLiquidity` —— 跨 2 个价位桶（81599/81600）、3 笔成交事件，
    /// 覆盖任务书要求的"跨桶事件链 + active_order_completed 时序"（第三笔成交后 taker 才完全成交）。
    /// 略过 `getOrderById` 断言（见模块头注释）。
    #[test]
    fn should_match_ioc_order_full_liquidity_crosses_multiple_buckets() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Ioc, 123, UID_2, MAX_PRICE, MAX_PRICE, 175, OrderAction::Bid);

        expected.remove_ask(0).remove_ask(0);
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 3);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 100);
        // 只有最后一笔（跨到第二个价位桶 81600 之后）taker 才完全成交
        assert!(!events[0].active_order_completed);
        assert!(!events[1].active_order_completed);
        assert!(events[2].active_order_completed);

        clear_order_book(&mut book);
    }

    /// Java `shouldMatchIocOrderWithRejection`
    #[test]
    fn should_match_ioc_order_with_rejection() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Ioc, 123, UID_2, MAX_PRICE, MAX_PRICE + 1, 270, OrderAction::Bid);

        expected.remove_all_asks();
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 7);
        check_reject(events[0], 25, MAX_PRICE);

        clear_order_book(&mut book);
    }

    // ---------------------- FOK BUDGET ORDERS ---------------------------

    /// Java `shouldRejectFokBidOrderOutOfBudget`
    #[test]
    fn should_reject_fok_bid_order_out_of_budget() {
        let (mut book, expected) = setup_book();
        let size = 180i64;
        let buy_budget = expected.aggregate_buy_budget(size) - 1;
        assert_eq!(buy_budget, 81599 * 75 + 81600 * 100 + 200954 * 5 - 1);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reject(events[0], size, buy_budget);

        clear_order_book(&mut book);
    }

    /// Java `shouldMatchFokBidOrderExactBudget` —— 跨 3 个价位桶（81599/81600/200954）。
    #[test]
    fn should_match_fok_bid_order_exact_budget_crosses_multiple_buckets() {
        let (mut book, mut expected) = setup_book();
        let size = 180i64;
        let buy_budget = expected.aggregate_buy_budget(size);
        assert_eq!(buy_budget, 81599 * 75 + 81600 * 100 + 200954 * 5);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        expected.remove_ask(0).remove_ask(0).set_ask_volume(0, 5);
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 4);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 100);
        check_trade(events[3], 10, 200954, 5);

        clear_order_book(&mut book);
    }

    /// Java `shouldMatchFokBidOrderExtraBudget`
    #[test]
    fn should_match_fok_bid_order_extra_budget() {
        let (mut book, mut expected) = setup_book();
        let size = 176i64;
        let buy_budget = expected.aggregate_buy_budget(size) + 1;
        assert_eq!(buy_budget, 81599 * 75 + 81600 * 100 + 200954 + 1);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        expected.remove_ask(0).remove_ask(0).set_ask_volume(0, 9);
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 4);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 100);
        check_trade(events[3], 10, 200954, 1);

        clear_order_book(&mut book);
    }

    /// Java `shouldRejectFokAskOrderBelowExpectation`
    #[test]
    fn should_reject_fok_ask_order_below_expectation() {
        let (mut book, expected) = setup_book();
        let size = 60i64;
        let sell_expectation = expected.aggregate_sell_expectation(size) + 1;
        assert_eq!(sell_expectation, 81593 * 40 + 81590 * 20 + 1);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, sell_expectation, sell_expectation, size, OrderAction::Ask);

        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reject(events[0], size, sell_expectation);

        clear_order_book(&mut book);
    }

    /// Java `shouldMatchFokAskOrderExactExpectation`
    #[test]
    fn should_match_fok_ask_order_exact_expectation() {
        let (mut book, mut expected) = setup_book();
        let size = 60i64;
        let sell_expectation = expected.aggregate_sell_expectation(size);
        assert_eq!(sell_expectation, 81593 * 40 + 81590 * 20);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, sell_expectation, sell_expectation, size, OrderAction::Ask);

        expected.remove_bid(0).set_bid_volume(0, 1);
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 2);
        check_trade(events[0], 4, 81593, 40);
        check_trade(events[1], 5, 81590, 20);

        clear_order_book(&mut book);
    }

    /// Java `shouldMatchFokAskOrderExtraBudget`
    #[test]
    fn should_match_fok_ask_order_extra_budget() {
        let (mut book, mut expected) = setup_book();
        let size = 61i64;
        let sell_expectation = expected.aggregate_sell_expectation(size) - 1;
        assert_eq!(sell_expectation, 81593 * 40 + 81590 * 21 - 1);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, sell_expectation, sell_expectation, size, OrderAction::Ask);

        expected.remove_bid(0).remove_bid(0);
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 3);
        check_trade(events[0], 4, 81593, 40);
        check_trade(events[1], 5, 81590, 20);
        check_trade(events[2], 6, 81590, 1);

        clear_order_book(&mut book);
    }

    // ---------------------- IOC_BUDGET ORDERS ---------------------------

    /// Java `shouldFullyMatchIocBudgetWithSufficientBudget`
    #[test]
    fn should_fully_match_ioc_budget_with_sufficient_budget() {
        let (mut book, mut expected) = setup_book();
        let size = 180i64;
        let buy_budget = expected.aggregate_buy_budget(size);

        let cmd = place_order(&mut book, OrderType::IocBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        expected.remove_ask(0).remove_ask(0).set_ask_volume(0, 5);
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 4);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 100);
        check_trade(events[3], 10, 200954, 5);

        clear_order_book(&mut book);
    }

    /// Java `shouldPartiallyMatchIocBudgetWhenBudgetRunsOut`
    #[test]
    fn should_partially_match_ioc_budget_when_budget_runs_out() {
        let (mut book, mut expected) = setup_book();
        let size = 180i64;
        let buy_budget = 81599 * 75;

        let cmd = place_order(&mut book, OrderType::IocBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        expected.remove_ask(0);
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 3);
        check_reject(events[0], 105, buy_budget);
        check_trade(events[1], 2, 81599, 50);
        check_trade(events[2], 3, 81599, 25);

        clear_order_book(&mut book);
    }

    /// Java `shouldRejectIocBudgetWhenBudgetTooSmallForOneUnit`
    #[test]
    fn should_reject_ioc_budget_when_budget_too_small_for_one_unit() {
        let (mut book, expected) = setup_book();
        let size = 100i64;
        let buy_budget = 81598i64;

        let cmd = place_order(&mut book, OrderType::IocBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reject(events[0], size, buy_budget);

        clear_order_book(&mut book);
    }

    /// Java `shouldRejectAskIocBudget`
    #[test]
    fn should_reject_ask_ioc_budget() {
        let (mut book, expected) = setup_book();
        let size = 50i64;
        let sell_expectation = 81593 * 40;

        let cmd = place_order(&mut book, OrderType::IocBudget, 123, UID_2, sell_expectation, sell_expectation, size, OrderAction::Ask);

        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reject(events[0], size, sell_expectation);

        clear_order_book(&mut book);
    }

    // MARKETABLE GTC ORDERS

    /// Java `shouldFullyMatchMarketableGtcOrder`
    #[test]
    fn should_fully_match_marketable_gtc_order() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 123, UID_2, 81599, MAX_PRICE, 1, OrderAction::Bid);

        expected.set_ask_volume(0, 74);
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_trade(events[0], 2, 81599, 1);

        clear_order_book(&mut book);
    }

    /// Java `shouldPartiallyMatchMarketableGtcOrderAndPlace`
    #[test]
    fn should_partially_match_marketable_gtc_order_and_place() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 123, UID_2, 81599, MAX_PRICE, 77, OrderAction::Bid);

        expected.remove_ask(0).insert_bid(0, 81599, 2);
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 2);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);

        clear_order_book(&mut book);
    }

    /// Java `shouldFullyMatchMarketableGtcOrder2Prices`
    #[test]
    fn should_fully_match_marketable_gtc_order_2_prices() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 123, UID_2, 81600, MAX_PRICE, 77, OrderAction::Bid);

        expected.remove_ask(0).set_ask_volume(0, 98);
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 3);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 2);

        clear_order_book(&mut book);
    }

    /// Java `shouldFullyMatchMarketableGtcOrderWithAllLiquidity` —— 跨全部 4 个价位桶
    /// （81599/81600/200954/201000），6 笔成交事件，任务书要求的"多桶多单事件链"主力覆盖用例之一。
    #[test]
    fn should_fully_match_marketable_gtc_order_with_all_liquidity_crosses_four_buckets() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 123, UID_2, 220000, MAX_PRICE, 1000, OrderAction::Bid);

        expected.remove_all_asks().insert_bid(0, 220000, 755);
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd);
        assert_eq!(events.len(), 6);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 100);
        check_trade(events[3], 10, 200954, 10);
        check_trade(events[4], 8, 201000, 28);
        check_trade(events[5], 9, 201000, 32);
        // taker size=1000 远超总流动性 245，全程未完全成交（剩余 755 转为挂单，见下方 L2 断言的
        // `insert_bid(0, 220000, 755)`）——因此全部 6 笔事件的 active_order_completed 都应为 false。
        for ev in &events {
            assert!(!ev.active_order_completed);
        }

        clear_order_book(&mut book);
    }

    // Move GTC order to marketable price

    /// Java `shouldMoveOrderFullyMatchAsMarketable`
    #[test]
    fn should_move_order_fully_match_as_marketable() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 83, UID_2, 81200, MAX_PRICE, 20, OrderAction::Bid);
        assert_eq!(events_list(&cmd).len(), 0);

        expected.set_bid_volume(2, 40);
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let (rc, cmd2) = move_cmd(&mut book, 83, UID_2, 81602);
        assert_eq!(rc, CommandResultCode::Success);

        expected.set_bid_volume(2, 20).set_ask_volume(0, 55);
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd2);
        assert_eq!(events.len(), 1);
        check_trade(events[0], 2, 81599, 20);

        clear_order_book(&mut book);
    }

    /// Java `shouldMoveOrderFullyMatchAsMarketable2Prices`
    #[test]
    fn should_move_order_fully_match_as_marketable_2_prices() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 83, UID_2, 81594, MAX_PRICE, 100, OrderAction::Bid);
        assert_eq!(events_list(&cmd).len(), 0);

        let (rc, cmd2) = move_cmd(&mut book, 83, UID_2, 81600);
        assert_eq!(rc, CommandResultCode::Success);

        expected.remove_ask(0).set_ask_volume(0, 75);
        assert_eq!(book.fill_l2(10), expected.to_l2());

        let events = events_list(&cmd2);
        assert_eq!(events.len(), 3);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 25);

        clear_order_book(&mut book);
    }

    /// Java `shouldMoveOrderMatchesAllLiquidity` —— move 触发的撮合同样跨全部 4 个价位桶。
    #[test]
    fn should_move_order_matches_all_liquidity_crosses_four_buckets() {
        let (mut book, mut expected) = setup_book();
        let _cmd = place_order(&mut book, OrderType::Gtc, 83, UID_2, 81594, MAX_PRICE, 246, OrderAction::Bid);

        let (rc, cmd2) = move_cmd(&mut book, 83, UID_2, 201000);
        assert_eq!(rc, CommandResultCode::Success);

        expected.remove_all_asks().insert_bid(0, 201000, 1);
        assert_eq!(book.fill_l2(10), expected.to_l2());

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

    // `multipleCommandsKeepInternalStateTest` 跳过：需要 Java 侧 `TestOrdersGenerator`
    // （带种子的随机命令生成器）+ `IOrderBook.validateInternalState()`——两者都是测试基础设施而非
    // 核心撮合逻辑，本任务未复刻该 harness（见 task-7-report.md skip 表）。
}
