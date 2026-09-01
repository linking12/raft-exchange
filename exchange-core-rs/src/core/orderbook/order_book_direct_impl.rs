//! 高性能订单簿（slab/arena 实现）。对应 Java: exchange.core2.core.orderbook.OrderBookDirectImpl
//! （字段：`:44-103` 构造/字段，`:960-1100` DirectOrder/Bucket 节点）。
//!
//! Java 用侵入式双向链表（`DirectOrder.next/prev` 直接持对象引用）+ ART
//! （`LongAdaptiveRadixTreeMap`）价位索引。Rust 版用 slab（`Vec<Option<T>> + free-list`）+
//! `BTreeMap` 落地同一结构：
//! - `orders`/`buckets`：slab，`next/prev/parent/tail` 全部退化为 slab 索引（`usize`）而非引用；
//!   `order_free`/`bucket_free` 是 LIFO 空闲槽栈，回收后优先复用（确定性：先释放的先被复用）。
//! - `ask_price_buckets`/`bid_price_buckets`：`BTreeMap<i64, BucketIdx>` 对应 Java 的 ART，按价格
//!   有序（ask 升序=最优价最小 key，bid 用降序遍历=最优价最大 key），`range`/`.rev()`
//!   对应 Java `getLowerValue`/`getHigherValue`/`forEach`/`forEachDesc`。
//! - `order_id_index`：`BTreeMap<i64, OrderIdx>`（点查为主，用 BTreeMap 维持"禁 HashMap"不变式，
//!   迭代序不影响任何输出）。
//! - `symbol_spec`：仅 `moveOrder` 的 `CURRENCY_EXCHANGE_PAIR` 现货风控读取（`:565`）+ 访问器；
//!   `objectsPool`/`eventsHelper`/`loggingCfg`（纯 Java 对象池/日志）本移植不落地。
//!
//! 本任务（P2 Task 1）只落地数据结构 + slab 原语 + 可编译的 `IOrderBook` 骨架：所有撮合/
//! cancel/reduce/move/L2/hash 逻辑均为占位（Task 2-6 补全），保证 crate 编译、既有 211 个
//! `cargo test --lib` 用例不受影响。

use std::collections::BTreeMap;

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::l2_market_data::L2MarketData;
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;
use crate::core::orderbook::i_order_book::IOrderBook;

/// 挂单节点（slab 元素）。对应 Java `OrderBookDirectImpl.DirectOrder`(`:960-1093`)。
///
/// `parent`/`next`/`prev` 在 Java 是对象引用，这里退化为 slab 索引：
/// - `parent`：挂单所在 `Bucket` 的索引（对应 Java `Bucket parent`）。
/// - `next`：链上更靠近撮合前端（best）方向的下一个 order（对应 Java 注释
///   "next order (towards the matching direction, price grows for asks)"）。
/// - `prev`：链上更靠近队尾（更差价/同价更晚 FIFO）方向的下一个 order。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DirectOrder {
    pub order_id: i64,
    pub price: i64,
    pub size: i64,
    pub filled: i64,
    pub filled_notional: i64,
    /// 现货 BID 挂单的风控预留价（对应 Java 注释
    /// "reserved price for fast moves of GTC bid orders in exchange mode"）。
    pub reserve_bid_price: i64,
    pub action: OrderAction,
    pub order_type: OrderType,
    pub command: OrderCommandType,
    pub uid: i64,
    pub timestamp: i64,
    pub user_cookie: i32,
    /// 所在 Bucket 的 slab 索引（对应 Java `Bucket parent`）。
    pub parent: Option<usize>,
    /// 撮合前端方向的下一个 order 的 slab 索引（对应 Java `DirectOrder next`）。
    pub next: Option<usize>,
    /// 队尾方向的下一个 order 的 slab 索引（对应 Java `DirectOrder prev`）。
    pub prev: Option<usize>,
}

/// 价位桶：同价位挂单的聚合视图。对应 Java `OrderBookDirectImpl.Bucket`(`:1096-1100`)。
///
/// 价格本身不存在 `Bucket` 里（隐含 = `tail` 那个 order 的 `price`，也是 ART key）；
/// 桶之间没有自己的 next/prev 指针——桶间衔接全靠全局单链的
/// `tail.prev`/`(桶内最老 order).next` 完成（见模块头注释）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Bucket {
    /// 桶内所有挂单剩余量（`size - filled`）之和。
    pub volume: i64,
    pub num_orders: i32,
    /// 桶内最新挂入（= FIFO 队尾/撮合序最差）的 order 的 slab 索引。
    pub tail: usize,
}

/// 高性能订单簿。对应 Java `OrderBookDirectImpl`。
///
/// slab/arena 布局：`orders`/`buckets` 是带 free-list 的 `Vec<Option<T>>`，回收槽位 LIFO 复用
/// （见 `alloc_order`/`free_order`/`alloc_bucket`/`free_bucket`）。
///
/// `#[allow(dead_code)]`：`ask_price_buckets`/`bid_price_buckets`/`order_id_index`/`best_ask`/
/// `best_bid` 本任务（Task 1）只建结构、不接线——`insertOrder`/`removeOrder`/撮合逻辑落在
/// Task 2-6，此前这些字段除了在 `new()` 里初始化外无生产代码读写（测试里通过 `#[cfg(test)]`
/// 内部访问会读到，但非测试构建下会触发 `dead_code`）。
#[allow(dead_code)]
pub struct OrderBookDirectImpl {
    /// 订单 slab；`None` 表示该槽已释放待复用。
    orders: Vec<Option<DirectOrder>>,
    /// `orders` 的空闲槽 LIFO 栈。
    order_free: Vec<usize>,
    /// 价位桶 slab；`None` 表示该槽已释放待复用。
    buckets: Vec<Option<Bucket>>,
    /// `buckets` 的空闲槽 LIFO 栈。
    bucket_free: Vec<usize>,
    /// ask 侧价位索引：价格 -> Bucket slab 索引，升序（对应 Java ART `askPriceBuckets`，
    /// ask "更优" = 更低价）。
    ask_price_buckets: BTreeMap<i64, usize>,
    /// bid 侧价位索引：价格 -> Bucket slab 索引，存储用升序 key，按买方最优价（最高价）
    /// 遍历用 `.rev()`（对应 Java ART `bidPriceBuckets`，bid "更优" = 更高价）。
    bid_price_buckets: BTreeMap<i64, usize>,
    /// orderId -> Order slab 索引（对应 Java ART `orderIdIndex`；用 BTreeMap 维持
    /// "禁 HashMap" 不变式，点查为主，迭代序不影响任何输出）。
    order_id_index: BTreeMap<i64, usize>,
    /// ask 侧链头 = 全局最优 ask（可空）。对应 Java `bestAskOrder`。
    best_ask: Option<usize>,
    /// bid 侧链头 = 全局最优 bid（可空）。对应 Java `bestBidOrder`。
    best_bid: Option<usize>,
    /// 仅 `moveOrder` 的 `CURRENCY_EXCHANGE_PAIR` 现货风控 + 访问器读取
    /// （对应 Java 构造入参 `symbolSpec`；`ObjectsPool`/`OrderBookEventsHelper`/
    /// `LoggingConfiguration` 纯 Java 对象池/日志，本移植不落地）。
    symbol_spec: Option<CoreSymbolSpecification>,
}

impl OrderBookDirectImpl {
    /// 建空簿。对应 Java 构造函数 `OrderBookDirectImpl(CoreSymbolSpecification, ObjectsPool,
    /// OrderBookEventsHelper, LoggingConfiguration)`（`:60-73`）——本移植只保留有功能依赖的
    /// `symbolSpec`，此处先留空（Task 2+ 由调用方注入）。
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

    /// 访问器：symbol spec（对应 Java `getSymbolSpec`）。
    pub fn symbol_spec(&self) -> Option<&CoreSymbolSpecification> {
        self.symbol_spec.as_ref()
    }

    // ---- slab 原语：order ----

    /// 分配一个 order 槽：优先复用最近释放的槽（LIFO），否则追加新槽。返回 slab 索引。
    pub fn alloc_order(&mut self, order: DirectOrder) -> usize {
        if let Some(idx) = self.order_free.pop() {
            self.orders[idx] = Some(order);
            idx
        } else {
            self.orders.push(Some(order));
            self.orders.len() - 1
        }
    }

    /// 释放一个 order 槽：置空并推入空闲栈供后续复用。
    pub fn free_order(&mut self, idx: usize) {
        self.orders[idx] = None;
        self.order_free.push(idx);
    }

    /// 按 slab 索引取 order 只读引用。索引须来自本簿当前存活的分配（否则 panic）。
    pub fn order(&self, idx: usize) -> &DirectOrder {
        self.orders[idx].as_ref().expect("dangling order slab index")
    }

    /// 按 slab 索引取 order 可变引用。索引须来自本簿当前存活的分配（否则 panic）。
    pub fn order_mut(&mut self, idx: usize) -> &mut DirectOrder {
        self.orders[idx].as_mut().expect("dangling order slab index")
    }

    // ---- slab 原语：bucket ----

    /// 分配一个 bucket 槽：优先复用最近释放的槽（LIFO），否则追加新槽。返回 slab 索引。
    pub fn alloc_bucket(&mut self, bucket: Bucket) -> usize {
        if let Some(idx) = self.bucket_free.pop() {
            self.buckets[idx] = Some(bucket);
            idx
        } else {
            self.buckets.push(Some(bucket));
            self.buckets.len() - 1
        }
    }

    /// 释放一个 bucket 槽：置空并推入空闲栈供后续复用。
    pub fn free_bucket(&mut self, idx: usize) {
        self.buckets[idx] = None;
        self.bucket_free.push(idx);
    }

    /// 按 slab 索引取 bucket 只读引用。索引须来自本簿当前存活的分配（否则 panic）。
    pub fn bucket(&self, idx: usize) -> &Bucket {
        self.buckets[idx].as_ref().expect("dangling bucket slab index")
    }

    /// 按 slab 索引取 bucket 可变引用。索引须来自本簿当前存活的分配（否则 panic）。
    pub fn bucket_mut(&mut self, idx: usize) -> &mut Bucket {
        self.buckets[idx].as_mut().expect("dangling bucket slab index")
    }
}

impl Default for OrderBookDirectImpl {
    fn default() -> Self {
        Self::new()
    }
}

impl IOrderBook for OrderBookDirectImpl {
    /// 占位（Task 2-6 补全撮合/挂单分派，镜像 Java `newOrder`/`newOrderPlaceGtc`/...）。
    /// 当前不改动任何簿状态，恒报 `MatchingUnsupportedCommand`（同步写回 `cmd.result_code`），
    /// 保证编译期骨架不 panic。
    fn new_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode {
        cmd.result_code = Some(CommandResultCode::MatchingUnsupportedCommand);
        CommandResultCode::MatchingUnsupportedCommand
    }

    /// 占位（Task 4 补全，镜像 Java `cancelOrder`）：簿目前恒空，统一按"未知订单"处理。
    fn cancel_order(&mut self, _cmd: &mut OrderCommand) -> CommandResultCode {
        CommandResultCode::MatchingUnknownOrderId
    }

    /// 占位（Task 4 补全，镜像 Java `reduceOrder`）：簿目前恒空，统一按"未知订单"处理。
    fn reduce_order(&mut self, _cmd: &mut OrderCommand) -> CommandResultCode {
        CommandResultCode::MatchingUnknownOrderId
    }

    /// 占位（Task 4 补全，镜像 Java `moveOrder`）：簿目前恒空，统一按"未知订单"处理。
    fn move_order(&mut self, _cmd: &mut OrderCommand) -> CommandResultCode {
        CommandResultCode::MatchingUnknownOrderId
    }

    /// 占位（Task 5 补全，镜像 Java `fillAsks`/`fillBids`）：恒返回空快照。
    fn fill_l2(&self, _size: i32) -> L2MarketData {
        L2MarketData::default()
    }

    /// 占位（Task 6 补全，镜像 Java `IOrderBook.stateHash`）：恒返回 0。
    fn state_hash(&self) -> i32 {
        0
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

        // 释放 idx0，slab 不收缩，但空闲栈记录了这个槽。
        book.free_order(idx0);
        assert_eq!(book.orders.len(), 2);

        // 再分配一个新 order：LIFO 复用刚释放的 idx0，而不是追加新槽。
        let idx2 = book.alloc_order(sample_order(3, 300, 30));
        assert_eq!(idx2, idx0);
        assert_eq!(book.orders.len(), 2); // 未增长：复用了旧槽
        assert_eq!(book.order(idx2).order_id, 3);
        // 未受影响的 idx1 依然是原来的 order。
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
        assert_eq!(b2, b0); // LIFO 复用释放的槽
        assert_eq!(book.buckets.len(), 2);
        assert_eq!(book.bucket(b2).tail, 2);
        assert_eq!(book.bucket(b1).tail, 1); // 未受影响
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

    // ---- IOrderBook 骨架占位：编译 + 不 panic，不做行为断言（Task 2-6 补全后再断言真实语义）----

    #[test]
    fn skeleton_new_order_reports_unsupported_and_does_not_panic() {
        let mut book = OrderBookDirectImpl::new();
        let mut cmd = OrderCommand {
            order_id: 1,
            symbol: 1,
            price: 100,
            size: 10,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
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
}
