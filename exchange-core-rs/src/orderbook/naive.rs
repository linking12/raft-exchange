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
            // 重复 order id：能撮合但不能挂单（对应 Java 的 duplicate-id 分支，reject 事件留待后续任务补）
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

    /// 即时撮合 taker（GTC / IOC 共用主循环）。对应 Java `tryMatchInstantly`。
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
                taker_price,
                taker_size,
                taker_action,
                true,
                cmd,
            ),
            // Ask taker 撮 bids：降序遍历（先取 >= taker_price 的升序 range，再 .rev()）。
            OrderAction::Ask => Self::match_against(
                &mut self.bid_buckets,
                &mut self.id_index,
                taker_price,
                taker_size,
                taker_action,
                false,
                cmd,
            ),
        }
    }

    /// 撮合主循环的实体：对某一侧（对手侧）buckets 按价格优先级逐桶吃单。
    /// 用静态方法（而非 &mut self 方法）以便同时拿到 `buckets` 和 `id_index` 两个不相交字段的可变借用。
    fn match_against(
        buckets: &mut BTreeMap<i64, OrdersBucketNaive>,
        id_index: &mut BTreeMap<i64, (OrderAction, i64)>,
        taker_price: i64,
        taker_size: i64,
        taker_action: OrderAction,
        ascending: bool,
        cmd: &mut OrderCommand,
    ) -> i64 {
        // 对手侧价格优先级序列：bid taker 用 (..=taker_price) 升序；ask taker 用 [taker_price..) 再反转成降序。
        let prices: Vec<i64> = if ascending {
            buckets.range(..=taker_price).map(|(p, _)| *p).collect()
        } else {
            buckets.range(taker_price..).rev().map(|(p, _)| *p).collect()
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
            Some(_) => {
                // TODO(Task 5): IOC/FOK/IOC_BUDGET/FOK_BUDGET —— 撮合主循环共用 try_match_instantly，
                // 但未成交剩余量需要 reject 事件（不挂单），本任务只落地 GTC。
            }
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
