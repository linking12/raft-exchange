use std::collections::BTreeMap;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::order::Order;
use crate::core::common::order_type::OrderType;
use crate::core::utils::core_arithmetic_utils::{add_exact, mul_exact};

#[derive(Debug, Clone, Copy)]
pub struct MakerFill {
    pub order_id: i64,
    pub trade: i64,
    pub completed: bool,
    pub uid: i64,
    pub reserve_bid_price: i64,
    pub command: OrderCommandType,
    pub size: i64,
    pub price: i64,
    pub order_type: OrderType,
    pub timestamp: i64,
    pub user_cookie: i32,
    pub filled: i64,
    pub filled_notional: i64,
}

pub struct OrdersBucketNaive {
    price: i64,
    total_volume: i64,
    next_seq: i64,
    entries: BTreeMap<i64, Order>,
    id_to_seq: BTreeMap<i64, i64>,
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

    pub fn reduce(&mut self, order_id: i64, reduce_by: i64) -> Option<Order> {
        let seq = *self.id_to_seq.get(&order_id)?;
        let o = self.entries.get_mut(&seq)?;
        o.size -= reduce_by;
        self.total_volume -= reduce_by;
        Some(o.clone())
    }

    pub fn match_forward(&mut self, mut to_collect: i64,
                         on_trade: &mut impl FnMut(MakerFill)) -> i64 {
        let seqs: Vec<i64> = self.entries.keys().copied().collect();
        for seq in seqs {
            if to_collect == 0 {
                break;
            }
            let fill = {
                let o = self.entries.get_mut(&seq).unwrap();
                let avail = o.remaining();
                let trade = to_collect.min(avail);
                o.filled += trade;
                o.filled_notional = add_exact(o.filled_notional, mul_exact(trade, o.price));
                MakerFill {
                    order_id: o.order_id,
                    trade,
                    completed: o.remaining() == 0,
                    uid: o.uid,
                    reserve_bid_price: o.reserve_bid_price,
                    command: o.command,
                    size: o.size,
                    price: o.price,
                    order_type: o.order_type,
                    timestamp: o.timestamp,
                    user_cookie: o.user_cookie,
                    filled: o.filled,
                    filled_notional: o.filled_notional,
                }
            };
            to_collect -= fill.trade;
            self.total_volume -= fill.trade;
            let completed = fill.completed;
            let maker_id = fill.order_id;
            on_trade(fill);
            if completed {
                self.entries.remove(&seq);
                self.id_to_seq.remove(&maker_id);
            }
        }
        to_collect
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

    pub fn num_orders(&self) -> usize {
        self.entries.len()
    }

    pub fn get(&self, order_id: i64) -> Option<&Order> {
        let seq = *self.id_to_seq.get(&order_id)?;
        self.entries.get(&seq)
    }

    pub fn iter_orders(&self) -> impl Iterator<Item = &Order> {
        self.entries.values()
    }
}

use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

impl OrdersBucketNaive {

    pub fn chronicle_write(&self, w: &mut ChronicleWriter, symbol_spec: &CoreSymbolSpecification) {
        symbol_spec.chronicle_write(w);
        w.write_i64(self.price);
        w.write_i32(self.entries.len() as i32);
        for order in self.entries.values() {
            w.write_i64(order.order_id);
            order.chronicle_write(w);
        }
        w.write_i64(self.total_volume);
    }

    pub fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let _spec = CoreSymbolSpecification::chronicle_read(r)?;
        let price = r.read_i64()?;
        let count = r.read_i32()?;
        let mut bucket = OrdersBucketNaive::new(price);
        for _ in 0..count {
            let _order_id = r.read_i64()?;
            let order = Order::chronicle_read(r)?;
            bucket.put(order);
        }
        bucket.total_volume = r.read_i64()?;
        Ok(bucket)
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
            filled_notional: 0,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
            uid: id,
            timestamp: id,
            user_cookie: 0,
            command: OrderCommandType::PlaceOrder,
        }
    }

    #[test]
    fn bucket_fifo_and_total_volume() {
        let mut b = OrdersBucketNaive::new(100);
        b.put(mk(1, 10));
        b.put(mk(2, 5));
        assert_eq!(b.total_volume(), 15);
        let mut collected: Vec<(i64, i64)> = vec![];
        let remaining = b.match_forward(12, &mut |f: MakerFill| {
            collected.push((f.order_id, f.trade));
        });
        assert_eq!(remaining, 0);
        assert_eq!(collected, vec![(1, 10), (2, 2)]);
        assert_eq!(b.total_volume(), 3);
    }

    const JAVA_UID_1: i64 = 412;
    const JAVA_UID_2: i64 = 413;

    fn mk_u(order_id: i64, uid: i64, size: i64) -> Order {
        Order {
            order_id,
            price: 1000,
            size,
            filled: 0,
            filled_notional: 0,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
            uid,
            timestamp: 0,
            user_cookie: 0,
            command: OrderCommandType::PlaceOrder,
        }
    }

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

    #[test]
    fn java_should_add_order() {
        let mut bucket = setup_bucket();
        bucket.put(mk_u(5, JAVA_UID_2, 240));
        assert_eq!(bucket.num_orders(), 4);
        assert_eq!(bucket.total_volume(), 541);
    }

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

        let removed = bucket.remove(4);
        assert!(removed.is_none());
        assert_eq!(bucket.num_orders(), 1);
        assert_eq!(bucket.total_volume(), 1);

        let removed = bucket.remove(3);
        assert!(removed.is_some());
        assert_eq!(bucket.num_orders(), 0);
        assert_eq!(bucket.total_volume(), 0);
    }

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

        for (id, size) in ids.into_iter().take(80) {
            bucket.remove(id);
            expected_num_orders -= 1;
            expected_volume -= size;
            assert_eq!(bucket.num_orders(), expected_num_orders);
            assert_eq!(bucket.total_volume(), expected_volume);
        }

        let mut events_count = 0usize;
        let remaining = bucket.match_forward(expected_volume, &mut |_f: MakerFill| {
            events_count += 1;
        });
        assert_eq!(events_count, expected_num_orders);
        assert_eq!(remaining, 0);
        assert_eq!(bucket.num_orders(), 0);
        assert_eq!(bucket.total_volume(), 0);
    }

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
            let remaining = bucket.match_forward(to_match, &mut |f: MakerFill| {
                collected_volume += f.trade;
            });
            assert_eq!(collected_volume, to_match);
            assert_eq!(remaining, 0);
            expected_volume -= collected_volume;
            assert_eq!(bucket.total_volume(), expected_volume);
            expected_num_orders = bucket.num_orders();
        }

        let mut events_count = 0usize;
        let remaining = bucket.match_forward(expected_volume, &mut |_f: MakerFill| {
            events_count += 1;
        });
        assert_eq!(events_count, expected_num_orders);
        assert_eq!(remaining, 0);
        assert_eq!(bucket.num_orders(), 0);
        assert_eq!(bucket.total_volume(), 0);
    }
}
