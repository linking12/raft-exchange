//! 单价位 FIFO 桶 + 整簿撮合。对应 Java: orderbook/OrdersBucketNaive.java、orderbook/OrderBookNaiveImpl.java
use std::collections::BTreeMap;
use crate::api::order::Order;
use crate::api::command::OrderCommand;
use crate::api::enums::{CommandResultCode, MatcherEventType, OrderAction, OrderType};
use crate::api::event::MatcherTradeEvent;
use crate::api::l2::L2MarketData;
use crate::orderbook::book::IOrderBook;

pub struct OrdersBucketNaive {
    price: i64,
    total_volume: i64,
    next_seq: i64,
    entries: BTreeMap<i64, Order>,      // seq -> order（FIFO）
    id_to_seq: BTreeMap<i64, i64>,      // order_id -> seq
}

impl OrdersBucketNaive {
    pub fn new(price: i64) -> Self {
        Self {
            price,
            total_volume: 0,
            next_seq: 0,
            entries: BTreeMap::new(),
            id_to_seq: BTreeMap::new()
        }
    }

    pub fn price(&self) -> i64 {
        self.price
    }

    pub fn total_volume(&self) -> i64 {
        self.total_volume
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn put(&mut self, order: Order) {
        self.total_volume += order.remaining();
        let seq = self.next_seq;
        self.next_seq += 1;
        self.id_to_seq.insert(order.order_id, seq);
        self.entries.insert(seq, order);
    }

    pub fn remove(&mut self, order_id: i64) -> Option<Order> {
        let seq = self.id_to_seq.remove(&order_id)?;
        let o = self.entries.remove(&seq)?;
        self.total_volume -= o.remaining();
        Some(o)
    }

    /// 从桶头 FIFO 撮合 `to_collect`，返回剩余未撮合量。
    pub fn match_forward(&mut self, mut to_collect: i64,
                         on_trade: &mut impl FnMut(i64, i64, bool)) -> i64 {
        let seqs: Vec<i64> = self.entries.keys().copied().collect();
        for seq in seqs {
            if to_collect == 0 {
                break;
            }
            let (maker_id, trade, completed) = {
                let o = self.entries.get_mut(&seq).unwrap();
                let avail = o.remaining();
                let trade = to_collect.min(avail);
                o.filled += trade;
                (o.order_id, trade, o.remaining() == 0)
            };
            to_collect -= trade;
            self.total_volume -= trade;
            on_trade(maker_id, trade, completed);
            if completed {
                self.entries.remove(&seq);
                self.id_to_seq.remove(&maker_id);
            }
        }
        to_collect
    }
}

/// 整簿（naive 实现）。对应 Java `OrderBookNaiveImpl`。
///
/// - `ask_buckets`：升序（`BTreeMap` 自然序），最优价 = 最小 key。
/// - `bid_buckets`：存储用升序 key，但按买方最优价（最高价）遍历时用 `.iter().rev()` / `.range(..).rev()`。
/// - `id_index`：order_id -> (side, price)，用于 O(log n) 定位挂单所在的桶（cancel/reduce/move 在 Task 6 补全）。
pub struct OrderBookNaive {
    ask_buckets: BTreeMap<i64, OrdersBucketNaive>,
    bid_buckets: BTreeMap<i64, OrdersBucketNaive>,
    id_index: BTreeMap<i64, (OrderAction, i64)>,
}

impl OrderBookNaive {
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
            // 重复 order id：能撮合但不能挂单（对应 Java 的 duplicate-id 分支）
            // TODO(Task 7): emit Java reject event for duplicate order id
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
        self.id_index.insert(order_id, (action, price));
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
        id_index: &mut BTreeMap<i64, (OrderAction, i64)>,
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

        for p in prices {
            if filled == taker_size {
                break;
            }
            let size_left = taker_size - filled;
            let bucket = buckets.get_mut(&p).expect("bucket must exist for collected price");

            // remaining_in_call 从 size_left 递减；归零即代表 taker 整体成交完毕
            // （size_left 就是 taker 当前总剩余量，与 Java `volumeToCollect == 0` 语义一致）。
            let mut remaining_in_call = size_left;
            bucket.match_forward(size_left, &mut |maker_id, trade, maker_completed| {
                remaining_in_call -= trade;
                let active_order_completed = remaining_in_call == 0;
                events.push(MatcherTradeEvent {
                    event_type: MatcherEventType::Trade,
                    active_order_completed,
                    maker_order_id: maker_id,
                    maker_order_completed: maker_completed,
                    price: p, // 成交价 = maker 挂单价（对照 Java: event.price = matchingOrder.getPrice()）
                    size: trade,
                    bid_gt_ask: taker_action == OrderAction::Bid,
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
        id_index: &mut BTreeMap<i64, (OrderAction, i64)>,
        taker_size: i64,
        mut remaining_budget: i64,
        taker_action: OrderAction,
        cmd: &mut OrderCommand,
    ) -> i64 {
        let prices: Vec<i64> = buckets.keys().copied().collect();

        let mut filled: i64 = 0;
        let mut events: Vec<MatcherTradeEvent> = Vec::new();
        let mut emptied: Vec<i64> = Vec::new();

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
            bucket.match_forward(size_cap, &mut |maker_id, trade, maker_completed| {
                remaining_in_call -= trade;
                let active_order_completed = remaining_in_call == 0;
                events.push(MatcherTradeEvent {
                    event_type: MatcherEventType::Trade,
                    active_order_completed,
                    maker_order_id: maker_id,
                    maker_order_completed: maker_completed,
                    price: p,
                    size: trade,
                    bid_gt_ask: taker_action == OrderAction::Bid,
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

impl Default for OrderBookNaive {
    fn default() -> Self {
        Self::new()
    }
}

impl IOrderBook for OrderBookNaive {
    fn new_order(&mut self, cmd: &mut OrderCommand) {
        match cmd.order_type {
            Some(OrderType::Gtc) => self.new_order_place_gtc(cmd),
            Some(OrderType::Ioc) => self.new_order_match_ioc(cmd),
            Some(OrderType::IocBudget) => self.new_order_match_ioc_budget(cmd),
            Some(OrderType::Fok) => self.new_order_match_fok(cmd),
            Some(OrderType::FokBudget) => self.new_order_match_fok_budget(cmd),
            None => {
                // 未设置 order_type：本任务不处理（无来源，测试契约不覆盖）。
            }
        }
    }

    fn cancel_order(&mut self, _cmd: &mut OrderCommand) -> CommandResultCode {
        // TODO(Task 6): 按 id_index 定位桶并移除、发 Reduce 事件。占位实现先保证可编译。
        CommandResultCode::MatchingUnknownOrderId
    }

    fn reduce_order(&mut self, _cmd: &mut OrderCommand) -> CommandResultCode {
        // TODO(Task 6): 部分撤销。占位实现先保证可编译。
        CommandResultCode::MatchingUnknownOrderId
    }

    fn move_order(&mut self, _cmd: &mut OrderCommand) -> CommandResultCode {
        // TODO(Task 6): 移价 + 重新撮合。占位实现先保证可编译。
        CommandResultCode::MatchingUnknownOrderId
    }

    /// L2 快照：ask 升序、bid 降序，各取前 `size` 档；`size <= 0` 视为取全部档位（Ruling C）。
    /// 对应 Java `fillAsks` / `fillBids`。
    fn fill_l2(&self, size: i32) -> L2MarketData {
        let take: usize = if size <= 0 { usize::MAX } else { size as usize };

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

    /// 占位实现：确定性折叠 hash（Task 7 会最终确定真正的 state_hash 算法）。
    fn state_hash(&self) -> i32 {
        0
    }
}

#[cfg(test)]
mod ob_tests {
    use super::*;
    use crate::api::enums::{OrderAction, OrderType};
    use crate::api::command::OrderCommand;

    fn place(book: &mut OrderBookNaive, id: i64, act: OrderAction, price: i64, size: i64) -> OrderCommand {
        let mut cmd = OrderCommand { order_id: id, symbol: 1, price, size,
            action: Some(act), order_type: Some(OrderType::Gtc), uid: id, ..Default::default() };
        book.new_order(&mut cmd);
        cmd
    }

    #[test]
    fn ioc_discards_remainder() {
        let mut book = OrderBookNaive::new();
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
        let mut book = OrderBookNaive::new();
        place(&mut book, 1, OrderAction::Ask, 100, 5);
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 100, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Fok), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        // 不足量 → 整单拒绝：无成交、卖单仍在
        let ev = cmd.matcher_event.as_ref().unwrap();
        assert_eq!(ev.event_type, crate::api::enums::MatcherEventType::Reject);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![5]);
    }

    #[test]
    fn ioc_full_fill_matches_and_leaves_no_remainder() {
        let mut book = OrderBookNaive::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 100, size: 6,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Ioc), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        // 全部成交，无 reject 事件
        let ev = cmd.matcher_event.as_ref().expect("应有成交事件");
        assert_eq!(ev.event_type, crate::api::enums::MatcherEventType::Trade);
        assert!(ev.next.is_none());
        assert_eq!(book.fill_l2(10).ask_volumes, vec![4]);
    }

    #[test]
    fn fok_full_fill_matches_completely() {
        let mut book = OrderBookNaive::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 100, size: 6,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Fok), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        let ev = cmd.matcher_event.as_ref().expect("应有成交事件");
        assert_eq!(ev.event_type, crate::api::enums::MatcherEventType::Trade);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![4]);
    }

    #[test]
    fn ioc_budget_caps_by_notional_and_discards_rest() {
        let mut book = OrderBookNaive::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10); // 10 @ 100 = notional 1000 max
        // 预算 250 只够买 2 个单位（2*100=200 <= 250 < 300）
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 250, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::IocBudget), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        // 未吃满部分（8）走 reject，插在链头；已成交部分（2）紧随其后（对应 Java attachRejectEvent 语义）。
        let head = cmd.matcher_event.as_ref().expect("应有事件链");
        assert_eq!(head.event_type, crate::api::enums::MatcherEventType::Reject);
        assert_eq!(head.size, 8);
        let trade = head.next.as_ref().expect("reject 之后应有成交事件");
        assert_eq!(trade.event_type, crate::api::enums::MatcherEventType::Trade);
        assert_eq!(trade.size, 2);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![8]);
    }

    #[test]
    fn fok_budget_rejects_when_budget_insufficient() {
        let mut book = OrderBookNaive::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10); // 需要 10*100=1000 才能吃满 10
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 500, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::FokBudget), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        let ev = cmd.matcher_event.as_ref().unwrap();
        assert_eq!(ev.event_type, crate::api::enums::MatcherEventType::Reject);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![10]); // 簿未改变
    }

    #[test]
    fn fok_budget_matches_when_budget_sufficient() {
        let mut book = OrderBookNaive::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 1000, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::FokBudget), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        let ev = cmd.matcher_event.as_ref().expect("应有成交事件");
        assert_eq!(ev.event_type, crate::api::enums::MatcherEventType::Trade);
        assert_eq!(ev.size, 10);
        assert_eq!(book.fill_l2(10).ask_prices.len(), 0);
    }

    #[test]
    fn two_orders_cross_into_one_trade() {
        let mut book = OrderBookNaive::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10); // 挂卖
        let taker = place(&mut book, 2, OrderAction::Bid, 100, 6); // 吃 6
        let ev = taker.matcher_event.as_ref().expect("应有成交事件");
        assert_eq!(ev.event_type, crate::api::enums::MatcherEventType::Trade);
        assert_eq!(ev.maker_order_id, 1);
        assert_eq!(ev.price, 100);
        assert_eq!(ev.size, 6);
        assert!(ev.next.is_none()); // 只撮一笔
        // 卖单剩 4 仍在簿上
        let l2 = book.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![100]);
        assert_eq!(l2.ask_volumes, vec![4]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::enums::OrderAction;

    fn mk(id: i64, size: i64) -> Order {
        Order {
            order_id: id,
            price: 100,
            size,
            filled: 0,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            uid: id,
            timestamp: id
        }
    }

    #[test]
    fn bucket_fifo_and_total_volume() {
        let mut b = OrdersBucketNaive::new(100);
        b.put(mk(1, 10));
        b.put(mk(2, 5));
        assert_eq!(b.total_volume(), 15);
        // 先进先出：撮合 12 → 全吃 order1(10) + order2 部分(2)
        let mut collected: Vec<(i64, i64)> = vec![]; // (maker_id, trade_size)
        let remaining = b.match_forward(12, &mut |maker_id, sz, _completed| {
            collected.push((maker_id, sz));
        });
        assert_eq!(remaining, 0); // 请求量全部撮合
        assert_eq!(collected, vec![(1, 10), (2, 2)]);
        assert_eq!(b.total_volume(), 3);
    }
}
