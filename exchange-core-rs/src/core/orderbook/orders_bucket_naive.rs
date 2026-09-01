//! 单价位 FIFO 桶。对应 Java: exchange.core2.core.orderbook.OrdersBucketNaive
use std::collections::BTreeMap;
use crate::core::common::order::Order;

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

    /// 桶内挂单数量。对应 Java `OrdersBucketNaive.getNumOrders`。
    pub fn num_orders(&self) -> usize {
        self.entries.len()
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

    /// 按 order_id 只读定位订单，不移除（cancel/reduce 判定剩余量用）。
    pub fn get(&self, order_id: i64) -> Option<&Order> {
        let seq = *self.id_to_seq.get(&order_id)?;
        self.entries.get(&seq)
    }

    /// 原地减少某挂单的 size（不移除），同步扣减桶的 total_volume。
    /// 对应 Java `order.size -= reduceBy; ordersBucket.reduceSize(reduceBy);`。
    /// 返回减量后的订单快照（用于构造 REDUCE 事件）；未找到返回 `None`。
    pub fn reduce(&mut self, order_id: i64, reduce_by: i64) -> Option<Order> {
        let seq = *self.id_to_seq.get(&order_id)?;
        let o = self.entries.get_mut(&seq)?;
        o.size -= reduce_by;
        self.total_volume -= reduce_by;
        Some(o.clone())
    }

    /// 按 FIFO（挂单顺序）只读遍历桶内订单。对应 Java `OrdersBucketNaive.forEachOrder` /
    /// `getAllOrders`（用于 `state_hash` 的确定性折叠，只读、不改变桶状态）。
    pub fn iter_orders(&self) -> impl Iterator<Item = &Order> {
        self.entries.values()
    }

    /// 从桶头 FIFO 撮合 `to_collect`，返回剩余未撮合量。
    /// 回调额外携带 maker 的 `uid`/`reserve_bid_price`（Task 3 新增，供调用方按 Java
    /// `OrdersBucketNaive.match` 的语义算出 `MatcherTradeEvent.matchedOrderUid`/`bidderHoldPrice`）。
    pub fn match_forward(&mut self, mut to_collect: i64,
                         on_trade: &mut impl FnMut(i64, i64, bool, i64, i64)) -> i64 {
        let seqs: Vec<i64> = self.entries.keys().copied().collect();
        for seq in seqs {
            if to_collect == 0 {
                break;
            }
            let (maker_id, trade, completed, maker_uid, maker_reserve_bid_price) = {
                let o = self.entries.get_mut(&seq).unwrap();
                let avail = o.remaining();
                let trade = to_collect.min(avail);
                o.filled += trade;
                (o.order_id, trade, o.remaining() == 0, o.uid, o.reserve_bid_price)
            };
            to_collect -= trade;
            self.total_volume -= trade;
            on_trade(maker_id, trade, completed, maker_uid, maker_reserve_bid_price);
            if completed {
                self.entries.remove(&seq);
                self.id_to_seq.remove(&maker_id);
            }
        }
        to_collect
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::order_action::OrderAction;

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
        let remaining = b.match_forward(12, &mut |maker_id, sz, _completed, _maker_uid, _maker_reserve_bid_price| {
            collected.push((maker_id, sz));
        });
        assert_eq!(remaining, 0); // 请求量全部撮合
        assert_eq!(collected, vec![(1, 10), (2, 2)]);
        assert_eq!(b.total_volume(), 3);
    }

    // ===================================================================
    // 翻译自 Java OrdersBucketNaiveTest（exchange-core/src/test/java/.../orderbook/OrdersBucketNaiveTest.java）
    // 适配：Java `remove(orderId, uid)` -> 我们的 `remove(order_id)`（uid 归属校验在 OrderBookNaiveImpl::id_index
    // 层做，桶层本身不持有 uid 校验职责，见 Task 6/7）；Java `Collections.shuffle(..., new Random(1))` 打乱移除
    // 顺序 -> 这里简化为固定顺序（顺序不影响这些测试断言的计数/总量结果，因为都是按 id 精确移除+动态重算期望值，
    // 不依赖具体哪个 id 被移除），不额外引入 RNG 依赖。
    // ===================================================================

    const JAVA_UID_1: i64 = 412;
    const JAVA_UID_2: i64 = 413;

    fn mk_u(order_id: i64, uid: i64, size: i64) -> Order {
        Order {
            order_id,
            price: 1000,
            size,
            filled: 0,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            uid,
            timestamp: 0,
        }
    }

    /// 对应 Java `@BeforeEach beforeGlobal`。
    fn setup_bucket() -> OrdersBucketNaive {
        let mut bucket = OrdersBucketNaive::new(1000);

        bucket.put(mk_u(1, JAVA_UID_1, 100));
        assert_eq!(bucket.num_orders(), 1);
        assert_eq!(bucket.total_volume(), 100);

        bucket.put(mk_u(2, JAVA_UID_2, 40));
        assert_eq!(bucket.num_orders(), 2);
        assert_eq!(bucket.total_volume(), 140);

        bucket.put(mk_u(3, JAVA_UID_1, 1));
        assert_eq!(bucket.num_orders(), 3);
        assert_eq!(bucket.total_volume(), 141);

        bucket.remove(2);
        assert_eq!(bucket.num_orders(), 2);
        assert_eq!(bucket.total_volume(), 101);

        bucket.put(mk_u(4, JAVA_UID_1, 200));
        assert_eq!(bucket.num_orders(), 3);
        assert_eq!(bucket.total_volume(), 301);

        bucket
    }

    /// Java `shouldAddOrder`
    #[test]
    fn java_should_add_order() {
        let mut bucket = setup_bucket();
        bucket.put(mk_u(5, JAVA_UID_2, 240));
        assert_eq!(bucket.num_orders(), 4);
        assert_eq!(bucket.total_volume(), 541);
    }

    /// Java `shouldRemoveOrders`
    #[test]
    fn java_should_remove_orders() {
        let mut bucket = setup_bucket();

        let removed = bucket.remove(1);
        assert!(removed.is_some());
        assert_eq!(bucket.num_orders(), 2);
        assert_eq!(bucket.total_volume(), 201);

        let removed = bucket.remove(4);
        assert!(removed.is_some());
        assert_eq!(bucket.num_orders(), 1);
        assert_eq!(bucket.total_volume(), 1);

        // can not remove existing order (already removed earlier)
        let removed = bucket.remove(4);
        assert!(removed.is_none());
        assert_eq!(bucket.num_orders(), 1);
        assert_eq!(bucket.total_volume(), 1);

        let removed = bucket.remove(3);
        assert!(removed.is_some());
        assert_eq!(bucket.num_orders(), 0);
        assert_eq!(bucket.total_volume(), 0);
    }

    /// Java `shouldAddManyOrders`
    #[test]
    fn java_should_add_many_orders() {
        let mut bucket = setup_bucket();
        let num_to_add: i64 = 100_000;
        let mut expected_volume = bucket.total_volume();
        let expected_num_orders = bucket.num_orders() + num_to_add as usize;
        for i in 0..num_to_add {
            bucket.put(mk_u(i + 5, JAVA_UID_2, i));
            expected_volume += i;
        }
        assert_eq!(bucket.num_orders(), expected_num_orders);
        assert_eq!(bucket.total_volume(), expected_volume);
    }

    /// Java `shouldAddAndRemoveManyOrders`
    #[test]
    fn java_should_add_and_remove_many_orders() {
        let mut bucket = setup_bucket();
        let num_to_add: i64 = 100;
        let mut expected_volume = bucket.total_volume();
        let mut expected_num_orders = bucket.num_orders() + num_to_add as usize;

        let mut ids: Vec<(i64, i64)> = Vec::with_capacity(num_to_add as usize);
        for i in 0..num_to_add {
            let id = i + 5;
            bucket.put(mk_u(id, JAVA_UID_2, i));
            ids.push((id, i));
            expected_volume += i;
        }
        assert_eq!(bucket.num_orders(), expected_num_orders);
        assert_eq!(bucket.total_volume(), expected_volume);

        for (id, size) in ids.into_iter().rev() {
            bucket.remove(id);
            expected_num_orders -= 1;
            expected_volume -= size;
            assert_eq!(bucket.num_orders(), expected_num_orders);
            assert_eq!(bucket.total_volume(), expected_volume);
        }
    }

    /// Java `shouldMatchAllOrders`
    #[test]
    fn java_should_match_all_orders() {
        let mut bucket = setup_bucket();
        let num_to_add: i64 = 100;
        let mut expected_volume = bucket.total_volume();
        let mut expected_num_orders = bucket.num_orders() + num_to_add as usize;

        let mut order_id: i64 = 5;
        let mut ids: Vec<(i64, i64)> = Vec::with_capacity(num_to_add as usize);
        for i in 0..num_to_add {
            bucket.put(mk_u(order_id, JAVA_UID_2, i));
            ids.push((order_id, i));
            order_id += 1;
            expected_volume += i;
        }
        assert_eq!(bucket.num_orders(), expected_num_orders);
        assert_eq!(bucket.total_volume(), expected_volume);

        // Java 打乱后取前 80 个移除；这里简化为固定取前 80 个插入的（不影响后续动态重算的断言）。
        for (id, size) in ids.into_iter().take(80) {
            bucket.remove(id);
            expected_num_orders -= 1;
            expected_volume -= size;
            assert_eq!(bucket.num_orders(), expected_num_orders);
            assert_eq!(bucket.total_volume(), expected_volume);
        }

        let mut events_count = 0usize;
        let remaining = bucket.match_forward(expected_volume, &mut |_maker_id, _trade, _completed, _maker_uid, _maker_reserve_bid_price| {
            events_count += 1;
        });
        assert_eq!(events_count, expected_num_orders);
        assert_eq!(remaining, 0);
        assert_eq!(bucket.num_orders(), 0);
        assert_eq!(bucket.total_volume(), 0);
    }

    /// Java `shouldMatchAllOrders2`
    #[test]
    fn java_should_match_all_orders_2() {
        let mut bucket = setup_bucket();
        let num_to_add: i64 = 1000;
        let mut expected_volume = bucket.total_volume();
        let mut expected_num_orders = bucket.num_orders();

        let mut order_id: i64 = 5;

        for _round in 0..100 {
            let mut ids: Vec<(i64, i64)> = Vec::with_capacity(num_to_add as usize);
            for i in 0..num_to_add {
                bucket.put(mk_u(order_id, JAVA_UID_2, i));
                ids.push((order_id, i));
                order_id += 1;
                expected_num_orders += 1;
                expected_volume += i;
            }

            assert_eq!(bucket.num_orders(), expected_num_orders);
            assert_eq!(bucket.total_volume(), expected_volume);

            for (id, size) in ids.into_iter().take(900) {
                bucket.remove(id);
                expected_num_orders -= 1;
                expected_volume -= size;
                assert_eq!(bucket.num_orders(), expected_num_orders);
                assert_eq!(bucket.total_volume(), expected_volume);
            }

            let to_match = expected_volume / 2;
            let mut collected_volume: i64 = 0;
            let remaining = bucket.match_forward(to_match, &mut |_maker_id, trade, _completed, _maker_uid, _maker_reserve_bid_price| {
                collected_volume += trade;
            });
            assert_eq!(collected_volume, to_match);
            assert_eq!(remaining, 0);
            expected_volume -= collected_volume;
            assert_eq!(bucket.total_volume(), expected_volume);
            expected_num_orders = bucket.num_orders();
        }

        let mut events_count = 0usize;
        let remaining = bucket.match_forward(expected_volume, &mut |_maker_id, _trade, _completed, _maker_uid, _maker_reserve_bid_price| {
            events_count += 1;
        });
        assert_eq!(events_count, expected_num_orders);
        assert_eq!(remaining, 0);
        assert_eq!(bucket.num_orders(), 0);
        assert_eq!(bucket.total_volume(), 0);
    }
}
