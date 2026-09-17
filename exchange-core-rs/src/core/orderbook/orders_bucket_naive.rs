//! 对应 Java `exchange.core2.core.orderbook.OrdersBucketNaive`：朴素订单簿实现中
//! 单个价位（price level）上的订单桶，按先进先出（FIFO）顺序保存该价位上的所有挂单。
//!
//! Java 版本用 `LinkedHashMap<Long, Order>`（按插入顺序遍历）维护同价位订单队列；
//! Rust 这里用两个 `BTreeMap` 组合实现同样的 FIFO 语义：
//! - `entries: BTreeMap<seq, Order>`：以单调递增的 `next_seq` 为 key，按 key 升序遍历
//!   即为插入顺序（等价于 Java LinkedHashMap 的迭代顺序）。
//! - `id_to_seq: BTreeMap<order_id, seq>`：order_id -> seq 的反向索引，
//!   用于按 order_id 做 O(log n) 的查找/删除/修改（Java 的 LinkedHashMap 天然支持按 key 查找，
//!   这里用双索引模拟同样的能力）。

use std::collections::BTreeMap;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::order::Order;
use crate::core::common::order_type::OrderType;
use crate::core::utils::core_arithmetic_utils::{add_exact, mul_exact};

/// 一次撮合成交时，某个 maker（挂单方）订单被吃掉的信息快照，供调用方据此生成成交事件。
/// 大致对应 Java `OrdersBucketNaive.match()` 中构造 `MatcherTradeEvent` 时用到的 maker
/// 订单字段（见 `OrderBookEventsHelper.sendTradeEvent(order, ...)` 的入参），
/// 但这里不构造事件对象本身，而是把成交所需字段打包传给 `on_trade` 回调，
/// 由调用方（订单簿实现）决定如何生成/串联具体事件。
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

/// 单个价位的订单桶。
/// 对应 Java `OrdersBucketNaive` 类字段：`price`、`entries`、`totalVolume`。
/// 注意：Java 版本还持有一个 `symbolSpec` 字段（每个桶都引用一份 symbol 规格，
/// 主要用于序列化/事件构造）；Rust 这里不在运行时结构体里保留 symbol spec，
/// 只在下面 chronicle 序列化时临时读写它以保持与 Java 快照二进制格式兼容
/// （详见 `chronicle_write`/`chronicle_read`）。
pub struct OrdersBucketNaive {
    price: i64,
    total_volume: i64,
    /// 单调递增的插入序号生成器，下一个 put() 的订单将使用该序号作为 `entries` 的 key，
    /// 从而让 BTreeMap 按 key 升序遍历时等价于 Java LinkedHashMap 的插入顺序（FIFO）。
    next_seq: i64,
    entries: BTreeMap<i64, Order>,
    id_to_seq: BTreeMap<i64, i64>,
}

impl OrdersBucketNaive {
    /// 对应 Java `OrdersBucketNaive(CoreSymbolSpecification symbolSpec, long price)` 构造函数
    /// （symbol spec 参数在 Rust 侧被省略，见上方结构体注释）。
    pub fn new(price: i64) -> Self {
        Self {
            price,
            total_volume: 0,
            next_seq: 0,
            entries: BTreeMap::new(),
            id_to_seq: BTreeMap::new()
        }
    }

    /// 将新订单放入桶尾（按插入顺序），并累加剩余量到 total_volume。
    /// 对应 Java `OrdersBucketNaive.put(Order order)`：
    /// `totalVolume += order.size - order.filled`，这里等价地用 `order.remaining()`。
    pub fn put(&mut self, order: Order) {
        self.total_volume += order.remaining();
        let seq = self.next_seq;
        self.next_seq += 1;
        self.id_to_seq.insert(order.order_id, seq);
        self.entries.insert(seq, order);
    }

    /// 按 order_id 从桶中移除订单并扣减 total_volume。
    /// 对应 Java `OrdersBucketNaive.remove(long orderId, long uid)`；
    /// 差异：Java 版本额外校验 `order.uid == uid` 不匹配则返回 null（防止误删他人订单），
    /// 这里不做 uid 校验，调用方需自行保证 order_id 的归属校验（如果需要）。
    pub fn remove(&mut self, order_id: i64) -> Option<Order> {
        let seq = self.id_to_seq.remove(&order_id)?;
        let o = self.entries.remove(&seq)?;
        self.total_volume -= o.remaining();
        Some(o)
    }

    /// 减少指定订单的剩余量（用于 REDUCE_ORDER 命令），返回减少后的订单副本。
    /// 对应 Java 侧的用法：调用方先执行 `order.size -= reduceBy`，
    /// 再调用 `OrdersBucketNaive.reduceSize(reduceBy)`（仅扣减 totalVolume，见 Java 源码
    /// `OrderBookNaiveImpl.reduceOrder` 中的两步操作）；这里把两步合并成一个桶方法，
    /// 直接修改订单的 size 字段并同步扣减 total_volume。
    pub fn reduce(&mut self, order_id: i64, reduce_by: i64) -> Option<Order> {
        let seq = *self.id_to_seq.get(&order_id)?;
        let o = self.entries.get_mut(&seq)?;
        o.size -= reduce_by;
        self.total_volume -= reduce_by;
        Some(o.clone())
    }

    /// 从桶内最老（先插入）的订单开始，依次撮合直到吃满 `to_collect` 或桶被榨干，
    /// 完全成交的订单从桶中移除，部分成交的订单保留剩余量。返回未能撮合掉的剩余量
    /// （即 taker 一侧仍未满足的量）。
    /// 对应 Java `OrdersBucketNaive.match(long volumeToCollect, IOrder activeOrder,
    /// OrderBookEventsHelper helper)`。
    ///
    /// 与 Java 版本的主要差异：
    /// - Java 在遍历时直接调用 `helper.sendTradeEvent(...)` 构造并串联
    ///   `MatcherTradeEvent` 链表返回给调用方；这里改为对每笔成交调用一次 `on_trade`
    ///   回调，把事件构造完全交给调用方。
    /// - Java 计算 `bidderHoldPrice` 时会区分：若 maker 是 ASK 方则使用 taker
    ///   （`activeOrder`）的 `reserveBidPrice`，否则使用 maker 自己的 `reserveBidPrice`。
    ///   这里 `match_forward` 不接收 taker 信息，`MakerFill.reserve_bid_price` 只是
    ///   maker 订单自身的 reserve_bid_price，taker 侧的价格替换（如果需要）由调用方
    ///   在处理 `on_trade` 回调时自行处理。
    /// - 先遍历一遍收集 `seqs` 快照再逐个处理，是为了避免在遍历 BTreeMap 的同时
    ///   对其做插入/删除导致的借用冲突；完全成交的订单在回调结束后再从
    ///   `entries`/`id_to_seq` 中移除。
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

    /// 对应 Java `OrdersBucketNaive.getPrice()`（`@Getter` 生成）。
    pub fn price(&self) -> i64 {
        self.price
    }

    /// 对应 Java `OrdersBucketNaive.getTotalVolume()`（`@Getter` 生成）。
    pub fn total_volume(&self) -> i64 {
        self.total_volume
    }

    /// 桶内是否已无订单（Java 版本无直接对应方法，调用方一般用 `getNumOrders() == 0` 判断）。
    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// 对应 Java `OrdersBucketNaive.getNumOrders()`。
    pub fn num_orders(&self) -> usize {
        self.entries.len()
    }

    /// 按 order_id 查找订单，不修改桶状态。对应 Java `OrdersBucketNaive.findOrder(long orderId)`。
    pub fn get(&self, order_id: i64) -> Option<&Order> {
        let seq = *self.id_to_seq.get(&order_id)?;
        self.entries.get(&seq)
    }

    /// 按插入顺序（FIFO，最老的在前）遍历桶内所有订单。
    /// 大致对应 Java `OrdersBucketNaive.getAllOrders()` / `forEachOrder(Consumer)`
    /// 所保证的“保留执行队列顺序”的语义，这里以零拷贝迭代器的形式提供。
    pub fn iter_orders(&self) -> impl Iterator<Item = &Order> {
        self.entries.values()
    }
}

use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

// Chronicle 快照读写：对应 Java `OrdersBucketNaive.writeMarshallable(BytesOut)` 和
// `OrdersBucketNaive(BytesIn bytes)` 构造函数，用于保持与 Java 端二进制快照格式兼容
// （字段顺序必须与 Java 完全一致：symbolSpec -> price -> entries(count + 各订单) -> totalVolume）。
impl OrdersBucketNaive {
    /// 写出一个桶：symbol spec（Java 每个桶都会写一份，尽管桶结构体本身不保留它，
    /// 这里同样按格式要求接收外部传入的 `symbol_spec` 写出，以兼容 Java 快照格式）、
    /// 价格、订单数量、按插入顺序的各订单（先写 order_id 再写订单本体，
    /// 对应 Java `SerializationUtils.marshallLongMap` 对 `LinkedHashMap<Long, Order>` 的序列化方式）、
    /// 最后写 total_volume。
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
    /// 按 `chronicle_write` 写出的顺序读回一个桶。
    /// symbol spec 只读出丢弃（`_spec`），因为运行时结构体不持有它；
    /// 逐个订单调用 `put()` 重建插入顺序与 total_volume，
    /// 最后再用快照中记录的 total_volume 覆盖（防止累加误差，也与 Java 侧
    /// 直接反序列化 totalVolume 字段而非重新计算的做法保持一致）。
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

// 单元测试：`bucket_fifo_and_total_volume` 为本地新增的基础用例；
// 以 `java_should_*` 命名的测试用例移植自 Java `OrdersBucketNaiveTest`
// （add/remove/match 场景及数量级用例），用于交叉验证 Rust 实现与 Java 行为一致。
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
