//! 单价位 FIFO 桶。对应 Java: orderbook/OrdersBucketNaive.java
use std::collections::BTreeMap;
use crate::api::order::Order;

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
