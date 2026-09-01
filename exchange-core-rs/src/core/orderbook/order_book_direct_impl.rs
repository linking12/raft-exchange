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
//! P2 Task 1 落地了数据结构 + slab 原语 + 可编译的 `IOrderBook` 骨架（撮合/cancel/reduce/move/
//! hash 仍占位）。**P2 Task 2** 在此基础上补齐：`insert_order`（对应 Java `insertOrder`
//! `:638-715`，挂单入链+入桶的核心指针手术）、`new_order` 的 GTC **无撮合**挂单路径（对应
//! Java `newOrderPlaceGtc:148-189`，撮合与 dup-id 拒绝留 Task 3）、`fill_l2`（对应 Java
//! `fillAsks/fillBids:916-935`，纯桶图迭代不走链，与 `OrderBookNaiveImpl::fill_l2` 逐位一致）、
//! `validate_internal_state`（对应 Java `validateInternalState:738-849` 的核心子集，供测试/
//! 后续任务当对拍 oracle 用）。cancel/reduce/move/IOC/FOK*/state_hash 仍占位，留 Task 3-6。

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
use crate::core::common::order_type::OrderType;
use crate::core::common::symbol_type::SymbolType;
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

    /// 建空簿并注入 `symbolSpec`（P2 Task 5：`moveOrder` 的现货 BID 风控守卫需要它，见
    /// `move_order`）。对应 Java 构造函数的 `symbolSpec` 入参；`ObjectsPool`/`OrderBookEventsHelper`/
    /// `LoggingConfiguration` 仍不落地。
    pub fn with_symbol_spec(symbol_spec: CoreSymbolSpecification) -> Self {
        Self { symbol_spec: Some(symbol_spec), ..Self::new() }
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

    // ---- 挂单入链/入桶 ----

    /// 挂单入链+入桶。对应 Java `insertOrder(order, freeBucket)`(`:638-715`)。
    ///
    /// `free_bucket`：仅 moveOrder（Task 4）会传入——`removeOrder` 摘链时若整桶被清空，会把该
    /// Bucket 槽位"摘出 BTreeMap 但不释放"传回来复用；本任务的 GTC 挂单路径恒传 `None`。
    ///
    /// - **情形 A（价位已有桶）**（`:646-669`）：新 order 成为桶的新 `tail`（FIFO 最新）：
    ///   `old_tail.prev` 指向新 order、`prev_order.next`（若存在）指向新 order、新 order 自己的
    ///   `next=old_tail, prev=prev_order, parent=bucket`；`bucket.volume += remaining`、
    ///   `num_orders += 1`。若调用方传了 `free_bucket`（本情形用不上），直接释放回 slab
    ///   （对应 Java `objectsPool.put`）。
    /// - **情形 B（价位无桶）**（`:670-714`）：新建/复用一个 Bucket（`tail/volume/num_orders`
    ///   全部对应新 order），登记进 `ask_price_buckets`/`bid_price_buckets`。然后查"最近的更优价
    ///   邻桶"：ask 更优=更低价→`range(..price).next_back()`（Java `getLowerValue`）；
    ///   bid 更优=更高价→`range(price+1..).next()`（Java `getHigherValue`）。
    ///   - 找到邻桶：插在该邻桶 `tail` 的边界前（`:683-694`），global best 不动。
    ///   - 没找到：新 order 成为该侧新的 global best（`:696-713`）。
    ///
    /// **borrow 处理**：先把需要的标量（`is_ask/price/remaining/old_tail/prev_order` 等）读出到
    /// 局部变量，再逐个 `order_mut`/`bucket_mut` 写回——不同时持有两个 slab 元素的 `&mut`。
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
            // 情形 A：价位已有桶——新 order 成为新 tail。
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
            // 情形 B：价位无桶——新建/复用桶，再接边界。
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
                self.bid_price_buckets.range((price + 1)..).next().map(|(_, &b)| b)
            };

            if let Some(neighbor_idx) = neighbor_bucket {
                // 邻近更优桶存在：插到它的 tail 边界前，best 不动。
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
                // 没有更优邻桶：新 order 成为该侧新的 global best。
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

    /// 摘链摘桶：cancel/reduce(全删)/move 共用的核心手术。对应 Java `removeOrder`(`:599-635`)。
    ///
    /// 1. `bucket.volume -= (size-filled)`、`bucket.num_orders -= 1`（在读 `bucket.tail`/桶内
    ///    其它字段前——Java 同样先扣量再判断 tail）。
    /// 2. 若 `order` 正是它所在桶的 `tail`：
    ///    - 若 `order.next` 为空 **或** `order.next` 属于别的桶（本桶只剩这一个 order）→
    ///      从价位索引（按 `order.action` 选 ask/bid 侧）摘除该价位、**返回该 Bucket 的 slab
    ///      索引交给调用方处理**（cancel/reduce 直接 `free_bucket`；move 传给
    ///      `insert_order` 复用或按情况释放，见 `move_order`）——本函数自己不释放。
    ///    - 否则（桶内还有别的 order）：`bucket.tail = order.next`（`next` 必非空，上面已
    ///      判过）。
    /// 3. 通用双向链摘除：`order.next` 存在则其 `.prev` 接到 `order.prev`；`order.prev` 存在
    ///    则其 `.next` 接到 `order.next`（两者互不依赖，Java 也是分别独立判断）。
    /// 4. best 修复：`order_idx==best_ask` → `best_ask=order.prev`；否则
    ///    `order_idx==best_bid` → `best_bid=order.prev`（`else if`——两个 best 不可能同时
    ///    指向同一个 order，因为 ask/bid 是两条独立的链）。
    ///
    /// 不释放 `order` 自身的 slab 槽——调用方在读完仍需要的字段后再 `free_order`
    /// （cancel/reduce(全删) 立即释放；move 视是否需要重挂决定）。
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
                // next 为空，或 next 已属于别的桶 -> 本桶只剩这一个 order -> 从价位索引摘除。
                let is_ask = action == OrderAction::Ask;
                if is_ask {
                    self.ask_price_buckets.remove(&price);
                } else {
                    self.bid_price_buckets.remove(&price);
                }
                bucket_removed = Some(parent);
            } else {
                // 桶内还有别的 order（next 必非空）-> tail 前移到 next。
                self.bucket_mut(parent).tail = next.expect("next_shares_bucket implies next.is_some()");
            }
        }

        // 通用双向链摘除。
        if let Some(n) = next {
            self.order_mut(n).prev = prev;
        }
        if let Some(p) = prev {
            self.order_mut(p).next = next;
        }

        // best 修复：ask/bid 是两条独立的链，同一个 order 不可能同时是两侧的 best。
        if Some(order_idx) == self.best_ask {
            self.best_ask = prev;
        } else if Some(order_idx) == self.best_bid {
            self.best_bid = prev;
        }

        bucket_removed
    }

    /// GTC 下单：先 `try_match_instantly`，全成则不挂、重复 order_id 则拒绝剩余、否则挂簿。
    /// 对应 Java `newOrderPlaceGtc`(`:148-189`)（P2 Task 3）。
    ///
    /// - `filled_size == size`：完全成交，不挂单，直接返回（已发的 TRADE 事件链留在 `cmd.matcher_event`）。
    /// - `order_id_index` 已存在该 `order_id`（重复下单）：能撮合但不能挂，对未成交剩余量
    ///   `size - filled_size` 发 REJECT 事件（前插到已有的 TRADE 事件链前，见 `attach_reject_event`）。
    /// - 否则：建 `DirectOrder`（`filled`/`filled_notional` = 撮合产生的累计值）、登记
    ///   `order_id_index`、`insert_order` 挂簿。
    fn new_order_place_gtc(&mut self, cmd: &mut OrderCommand) {
        let size = cmd.size;
        let action = cmd.action.expect("GTC order requires action");
        let price = cmd.price;
        let reserve_bid_price = cmd.reserve_bid_price;

        let (filled_size, filled_notional) =
            self.try_match_instantly(action, size, reserve_bid_price, Some(price), cmd);

        if filled_size == size {
            // 完全成交，无需挂单（对应 Java: filledSize == size -> return）。
            return;
        }

        let order_id = cmd.order_id;
        if self.order_id_index.contains_key(&order_id) {
            // 重复 order id：能撮合但不能挂——对未成交剩余发 REJECT（已发的成交事件不回滚）。
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
            user_cookie: 0, // OrderCommand（本移植子集）未含 userCookie 字段
            parent: None,
            next: None,
            prev: None,
        };
        let idx = self.alloc_order(order);
        self.order_id_index.insert(order_id, idx);
        self.insert_order(idx, None);
    }

    /// 撮合核心。对应 Java `tryMatchInstantly(:253-372)`（GTC/IOC/FOK_BUDGET/move 共用；GTC/IOC
    /// 调用它时 taker 起始 `filled`/`filled_notional` 恒为 0）。
    ///
    /// `limit_price`：`Some(p)` 按价格过滤对手侧（GTC/IOC 主路径，BID 要求 `maker.price<=p`、
    /// ASK 要求 `maker.price>=p`）；`None` 表示不限价——**仅 FOK_BUDGET 使用**。按 Ruling P2-1，
    /// Rust 侧 BID/ASK FOK_BUDGET 一律不设价格上限，镜像 Naive 的 `try_match_full`，不复刻
    /// Java Direct "BID FOK_BUDGET 复用 cmd.price 当每单价上限"的巧合（见 §8/模块头 Ruling 说明）。
    ///
    /// 无撮合（对手侧无挂单 / 价格越限 / `taker_size==0`）→ 不发事件、不改任何状态，返回
    /// `(0, 0)`（对应 Java `EMPTY_LONGS`；GTC/IOC 起始 filled 恒 0，故与"返回 taker 起始值"退化为同一形式）。
    ///
    /// 循环（§2.1(a)-(j)）：`maker` 从 `best_ask`(BID taker)/`best_bid`(ASK taker) 起沿 `.prev` 走
    /// （桶内老到新、跨桶更优到更差）。每轮一个 maker：
    /// - `trade_size=min(remaining, maker.size-maker.filled)`，成交价=maker.price；
    /// - 更新 taker 累计 + maker.filled/filled_notional + `maker.parent.volume -= trade_size`；
    /// - `maker_completed = maker.filled==maker.size`：完成则 `parent.num_orders -= 1`（发事件前）；
    /// - `remaining -= trade_size`（发事件前，使 `active_order_completed` 反映扣减后的状态）；
    /// - 发 TRADE 事件、追加到本次调用的事件链；
    /// - 若 maker 未完成 → break（它留簿，taker 已耗尽）；
    /// - 若完成：从 `order_id_index` 摘除，**推迟 slab 槽的实际回收到循环结束+best 修复之后**
    ///   （`freed_orders`，对应 §2.1(h) 的"防止同一次撮合调用内新分配复用刚释放的槽"约束——
    ///   本函数内不会新分配 order，纯防御性保留该顺序以匹配 Java 的对象池释放时机）；
    ///   若 maker 是其桶的 `tail` → 从价位索引摘桶+释放 Bucket，`price_bucket_tail` 推进到
    ///   `maker.prev` 所在桶的 tail（若存在）；
    /// - 前进 `maker = maker.prev`，价格/剩余量条件不满足则退出循环。
    ///
    /// 循环后：`maker` 非空则 `maker.next = None`（它成为新链头）；`best_ask`/`best_bid = maker`
    /// （可能为 `None`）。返回 `(taker_filled, taker_filled_notional)`。
    pub fn try_match_instantly(
        &mut self,
        taker_action: OrderAction,
        taker_size: i64,
        taker_reserve_bid_price: i64,
        limit_price: Option<i64>,
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

        // `priceBucketTail`(Java `:270`)：起始 maker 所在桶的 tail，撮合到该 order 即整桶清空。
        let mut price_bucket_tail: usize = {
            let parent = self.order(maker_idx0).parent.expect("maker must have parent bucket");
            self.bucket(parent).tail
        };

        let mut taker_filled: i64 = 0;
        let mut taker_filled_notional: i64 = 0;
        let mut events: Vec<MatcherTradeEvent> = Vec::new();
        // 完成的 maker 槽位：先摘 order_id_index，槽本身推迟到循环结束+best 修复之后才真正 free
        // （§2.1(h)：本函数内不重新分配 order，纯粹保持与 Java 对象池释放时机一致的顺序）。
        let mut freed_orders: Vec<usize> = Vec::new();

        loop {
            let midx = maker.expect("loop body only runs while maker is Some");

            let (m_size, m_filled_before, m_price, m_parent, m_prev, m_uid, m_order_id, m_reserve_bid_price) = {
                let o = self.order(midx);
                (
                    o.size,
                    o.filled,
                    o.price,
                    o.parent.expect("maker must have parent bucket"),
                    o.prev,
                    o.uid,
                    o.order_id,
                    o.reserve_bid_price,
                )
            };

            let trade_size = remaining.min(m_size - m_filled_before);
            let trade_price = m_price;

            taker_filled += trade_size;
            taker_filled_notional += trade_size * trade_price;

            {
                let o = self.order_mut(midx);
                o.filled += trade_size;
                o.filled_notional += trade_size * trade_price;
            }
            self.bucket_mut(m_parent).volume -= trade_size;

            let maker_completed = m_filled_before + trade_size == m_size;
            if maker_completed {
                self.bucket_mut(m_parent).num_orders -= 1;
            }

            remaining -= trade_size;
            let active_order_completed = remaining == 0;

            // bidderHoldPrice：BID 那一方的 reserve_bid_price（taker 是 BID 用 taker 自己的，
            // 否则 maker 是 BID、用 maker 的）——同 Naive §6。
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
                next: None,
            });

            if !maker_completed {
                // maker 未成交完 -> taker 已无剩余量可分配，退出撮合循环（maker 留簿）。
                break;
            }

            // maker 完成：摘 order_id_index，槽位推迟到循环后才 free。
            self.order_id_index.remove(&m_order_id);
            freed_orders.push(midx);

            if midx == price_bucket_tail {
                // 撮到了当前价位桶的 tail -> 整桶已清空，从价位索引摘桶+释放 Bucket。
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

        // 循环后：断开新链头的前向指针，更新 best。
        if let Some(midx) = maker {
            self.order_mut(midx).next = None;
        }
        if is_bid {
            self.best_ask = maker;
        } else {
            self.best_bid = maker;
        }

        // 现在才真正回收完成的 order 槽（best 指针已修复，槽内数据此前一直有效可读）。
        for idx in freed_orders {
            self.free_order(idx);
        }

        // 按撮合发生顺序拼成单链表，整体覆盖 cmd.matcher_event（对应 Java 首事件直接赋值
        // triggerCmd.matcherEvent，不与调用前已有的链拼接——与 Naive 的 match_against 一致）。
        let mut chain: Option<Box<MatcherTradeEvent>> = None;
        for mut ev in events.into_iter().rev() {
            ev.next = chain.take();
            chain = Some(Box::new(ev));
        }
        cmd.matcher_event = chain;

        (taker_filled, taker_filled_notional)
    }

    /// IOC：即时撮合（价格受限），未成交剩余（`size - filled`）直接 REJECT，从不挂簿。
    /// 对应 Java `newOrderMatchIoc`(`:191-202`)。
    fn new_order_match_ioc(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("IOC order requires action");
        let price = cmd.price;
        let size = cmd.size;
        let reserve_bid_price = cmd.reserve_bid_price;

        let (filled, _) = self.try_match_instantly(action, size, reserve_bid_price, Some(price), cmd);
        let rejected_size = size - filled;
        if rejected_size != 0 {
            Self::attach_reject_event(cmd, rejected_size);
        }
    }

    /// 无价格限制地探测撮合满 `size` 所需的总预算。对应 Java `checkBudgetToFill`(`:222-250`)。
    ///
    /// 从对侧链头起**按桶粒度**走（不逐 order）：`available = bucket.volume`（该桶剩余总量），
    /// `size<=available` 时直接返回 `budget + size*price`；否则 `budget += available*price`、
    /// `size -= available`，用 `bucket.tail.prev` 跳到下一个（更差价）桶继续。链走尽仍未凑够
    /// `size` → 流动性不足，返回 `i64::MAX` 哨兵（对应 Java `Long.MAX_VALUE`）。
    ///
    /// 累加用 `i128` 防止 `available*price`/`budget` 溢出 `i64`（对应 Java `Math.multiplyExact`
    /// 在溢出时抛异常；这里选择饱和到 `i64::MAX`——效果上等价于"预算不可能满足"，与
    /// `is_budget_limit_satisfied` 对哨兵值的显式排除语义一致，见下）。
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

            // 跳到下一个（更差价）桶：桶内其余 order 已被 `bucket.volume` 一次性计入，
            // 无需逐 order 遍历（对应 Java `makerOrder = bucket.tail.prev`）。
            maker = self.order(bucket.tail).prev;
        }

        i64::MAX // 流动性不足以吃满 size（对应 Java `Long.MAX_VALUE` 哨兵）
    }

    /// 对应 Java `isBudgetLimitSatisfied`(`:217-220`)：BID 要求成本 `calculated<=limit`，
    /// ASK 要求收入 `calculated>=limit`；`calculated==i64::MAX`（`check_budget_to_fill` 的
    /// "流动性不足"哨兵）恒不满足——即使 ASK 分支的 `calculated>=limit` 数值上会成立。
    fn is_budget_limit_satisfied(action: OrderAction, calculated: i64, limit: i64) -> bool {
        calculated != i64::MAX
            && (calculated == limit || ((action == OrderAction::Bid) != (calculated > limit)))
    }

    /// FOK_BUDGET：`check_budget_to_fill` 探测吃满 `size` 所需总预算，`is_budget_limit_satisfied`
    /// 判定满足则整单撮合（`limit_price=None`，见 Ruling P2-1：镜像 Naive、不设每单价上限），
    /// 不满足则整单 REJECT（不改簿）。对应 Java `newOrderMatchFokBudget`(`:204-215`)。
    fn new_order_match_fok_budget(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("FOK_BUDGET order requires action");
        let size = cmd.size;
        let limit = cmd.price;
        let reserve_bid_price = cmd.reserve_bid_price;

        let budget = self.check_budget_to_fill(action, size);

        if Self::is_budget_limit_satisfied(action, budget, limit) {
            // 预算已证足够吃满 size：调用不限价的 try_match_instantly（"满足即全成"不变式），
            // 之后不再 reject。
            self.try_match_instantly(action, size, reserve_bid_price, None, cmd);
        } else {
            Self::attach_reject_event(cmd, size);
        }
    }

    /// IOC_BUDGET：仅支持 BID（用预算上限买）；ASK 语义模糊，整单 REJECT（同 Naive）。
    /// 对应 Java `newOrderMatchIocBudget`(`:133-145`)。
    fn new_order_match_ioc_budget(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("IOC_BUDGET order requires action");
        if action != OrderAction::Bid {
            Self::attach_reject_event(cmd, cmd.size);
            return;
        }

        let size = cmd.size;
        let budget = cmd.price; // product-scale 总预算
        let reserve_bid_price = cmd.reserve_bid_price;

        let (filled, _) = self.match_against_budget_ioc(size, reserve_bid_price, budget, cmd);
        let rejected_size = size - filled;
        if rejected_size != 0 {
            Self::attach_reject_event(cmd, rejected_size);
        }
    }

    /// IOC_BUDGET 专用撮合。**不对应 Java `tryMatchInstantlyWithBudget`(`:381-488`) 的结构**——
    /// 那个 Java 版本用"每单价<=limitPrice(=cmd.price 复用) + 预算连续递减"的单一 do-while，
    /// 但已定案、不可回退的 Naive Rust 移植（`OrderBookNaiveImpl::match_against_budget` 配合
    /// `OrdersBucketNaive::match_forward`）用了完全不同的算法：**无价格上限，且预算上限按
    /// "每个价位一个独立批次"分配、批次之间不延续、也不重试**。既然规格明确"Direct 必须与
    /// Naive 外部结果逐位一致"，本函数镜像 Naive 的算法而非 Java 的（在实现过程中通过对拍
    /// 测试发现两者在"预算批次是否跨桶延续"上存在真实分歧，而非仅是 Ruling P2-1 提到的
    /// FOK_BUDGET 那种"巧合但等价"——这里不镜像 Naive 会导致真实的成交量/事件分歧，已用
    /// `ioc_budget_partial_fill_capped_by_budget_matches_naive` 覆盖）：
    /// - **无价格上限**（对应 Naive `match_against_budget` 没有 `taker_price_limit` 参数）。
    /// - `batch_remaining==0`（尚未开始，或上一批已耗尽）时，才用"当前 maker 的价 + 当前剩余
    ///   预算/剩余量"重新计算 `size_cap = remaining.min(affordable)`；`size_cap<=0` → 整体停止
    ///   撮合（对应 Naive `if size_cap<=0 {break}` 跳出整个外层循环）。
    /// - 批次内（同价位，可能跨多个 FIFO 挂单）逐单成交，直到批次上限耗尽或该价位挂单耗尽。
    ///
    /// **`batch_remaining` 归零的三种方式，必须逐一都能触发"下一价位重新计算"**（这是曾经的
    /// 一个真实 bug 的教训——第 3 种最初被漏掉，见下）：
    /// 1. **预算耗尽**：`size_cap` 由 `affordable` 决定，trade 后 `remaining_budget_new =
    ///    remaining_budget - size_cap*price < price`（地板除余数恒小于除数）。ask 价格链严格
    ///    递增、`remaining_budget` 只减不增，故无论下一个 maker 价格相同还是更差(更高)，都有
    ///    `remaining_budget_new < 该价`，`affordable=0`，重新计算必然立即 `size_cap<=0` 而
    ///    整体停止——与 Naive"该价位批次已用完、永不重试"的效果逐位等价。这种情形 trade 数学
    ///    本身就会让 `batch_remaining` 精确归零，无需额外干预。
    /// 2. **taker 整体剩余耗尽**：`size_cap` 由 `remaining` 决定，trade 后 `remaining==0`，
    ///    本轮循环顶部的 `if remaining==0 {break}` 已在下一次迭代前拦下，不会走到重新计算。
    /// 3. **本价位流动性耗尽（桶内货比批次上限少）**：`size_cap` 由 `affordable`/`remaining`
    ///    的 min 决定，但**该价位桶的总量比 size_cap 还小**——吃穿整个桶（`midx ==
    ///    price_bucket_tail`）跨到下一个（更差）价位时，`batch_remaining` 可能仍 **>0**（预算
    ///    或量都还没花完，只是这一档没货了）！trade 数学不会自动把它归零，必须在桶被吃穿、
    ///    `maker` 跨桶时**显式**把 `batch_remaining` 置 0，强迫下一轮用新价位重新算一次
    ///    `size_cap`（否则会把按旧价位算出的 leftover 原封不动地套到更差价位上继续吃、跳过了
    ///    对新价位的 affordability 检查——这正是 Naive 严禁的"预算批次跨桶延续"，对应 Naive
    ///    `for p in prices` 对每个价位独立调用 `size_cap = size_left.min(affordable)`，从不
    ///    带着上一价位的余量进入下一价位）。见下方 `if midx == price_bucket_tail` 分支。
    ///
    /// 因此 `active_order_completed` 语义是"**当前批次**是否耗尽"而非"taker 整体是否成交完"——
    /// 批次上限 < taker 整体剩余量时两者不同，这是本函数与 `try_match_instantly` 的关键差异。
    ///
    /// 仅 BID 会调用本函数（IOC_BUDGET 对 ASK 已在调用方 `new_order_match_ioc_budget` 整单
    /// reject），故硬编码吃 `ask_price_buckets`/`best_ask`、`bid_gt_ask=true`、
    /// `bidder_hold_price=taker_reserve_bid_price`（同 Naive 对 taker=BID 的字段选择）。
    /// 允许部分成交、从不挂单；未成交剩余由调用方走 REJECT。
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

        // 批次状态：`batch_remaining`=当前批次还能买多少（0 表示尚未开始或上一批已耗尽，
        // 下一轮循环顶部要用"当前 maker"重新计算）；`price_bucket_tail`=当前批次所在桶的
        // tail（判断"是否吃穿整个桶"用，每次重新计算批次时刷新）。
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

            let (m_size, m_filled_before, m_price, m_parent, m_prev, m_uid, m_order_id) = {
                let o = self.order(midx);
                (o.size, o.filled, o.price, o.parent.expect("maker must have parent bucket"), o.prev, o.uid, o.order_id)
            };

            let trade_price = m_price;
            // batch_remaining>0（上面已保证）且挂单必有剩余量（挂单不变式）：trade_size 恒>0。
            let trade_size = batch_remaining.min(m_size - m_filled_before);

            taker_filled += trade_size;
            taker_filled_notional += trade_size * trade_price;
            {
                let o = self.order_mut(midx);
                o.filled += trade_size;
                o.filled_notional += trade_size * trade_price;
            }
            self.bucket_mut(m_parent).volume -= trade_size;

            let maker_completed = m_filled_before + trade_size == m_size;
            if maker_completed {
                self.bucket_mut(m_parent).num_orders -= 1;
            }

            remaining -= trade_size;
            remaining_budget -= trade_size * trade_price;
            batch_remaining -= trade_size;
            // 本批次是否耗尽（≠ taker 整体是否成交完——见函数文档）。
            let active_order_completed = batch_remaining == 0;

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
                next: None,
            });

            if maker_completed {
                self.order_id_index.remove(&m_order_id);
                freed_orders.push(midx);

                if midx == price_bucket_tail {
                    // 吃穿了整个桶（本桶流动性 < 批次上限）——跨到下一个（更差）价位。
                    // `batch_remaining` 可能仍 >0（本批次的钱/量并未真正花完，只是这一价位没
                    // 货了），必须强制清零，强迫下一轮循环顶部重新为"新价位"计算一次全新的
                    // `size_cap`（否则会把这笔按旧价位算出的 leftover 预算原封不动地套到更差
                    // 价位上继续吃，等于没有对新价位做 affordability 检查——这正是 Naive 严禁
                    // 的"预算批次跨桶延续"）。对应 Naive `for p in prices` 对每个价位都独立
                    // 调用 `size_cap = size_left.min(affordable)`，从不把上一个价位的余量带
                    // 到下一个价位。
                    self.ask_price_buckets.remove(&m_price);
                    self.free_bucket(m_parent);
                    batch_remaining = 0;
                }
                maker = m_prev;
            }
            // !maker_completed：maker 原地不动（留簿、部分成交）；此时 batch_remaining 必为 0
            // （trade_size=batch_remaining.min(avail)，avail>batch_remaining 时 trade_size=
            // batch_remaining，减完后归零）；`maker` 保留在这个仍合法、未被摘除的挂单上，
            // 循环顶部的 `remaining==0` 检查/下一轮的"批次耗尽重算"会按上面的论证正确处理
            // （重算必然 size_cap<=0 而 break，`maker` 原样成为新 best，绝不会被误"跳过"丢失）。
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

    /// 不挂单、不改簿的 REJECT 事件，前插到 `cmd.matcher_event` 链头。对应 Java
    /// `OrderBookEventsHelper.attachRejectEvent`（同 Naive 的 `attach_reject_event`：已有的成交
    /// 事件链——如部分撮合后 dup-id 拒绝剩余——被接到 REJECT 之后）。
    fn attach_reject_event(cmd: &mut OrderCommand, rejected_size: i64) {
        let event = MatcherTradeEvent {
            event_type: MatcherEventType::Reject,
            active_order_completed: true,
            maker_order_id: 0,
            maker_order_completed: false,
            price: cmd.price,
            size: rejected_size,
            bid_gt_ask: false,
            bidder_hold_price: cmd.reserve_bid_price,
            matched_order_uid: 0,
            next: cmd.matcher_event.take(),
        };
        cmd.matcher_event = Some(Box::new(event));
    }

    /// 内部状态校验（测试/对拍用）。对应 Java `validateInternalState`(`:738-849`) 的核心子集
    /// （§7 不变式 1/2/3/5/7/8/9 的等价形式；完整版留 Task 6）：
    /// - 每侧 `best.next == None`（若 best 存在）；
    /// - 从 best 沿 `.prev` 走访问该侧每个 order 恰一次，且相邻两者 `X.prev==Y ⟺ Y.next==X`；
    /// - 链上每个 order 的 `action` 与所属侧一致；
    /// - 按 `parent` 聚合：在其 `tail` 处 `bucket.volume == Σ(size-filled)` 且
    ///   `num_orders == count`，`bucket.tail.price` 与 price-map 的 key 一致；
    /// - 每价 ↔ 恰一个 Bucket（BTreeMap 图与链可达桶 1:1，用长度比对判定无孤儿/无缺失）；
    /// - `order_id_index` 的 key 集合 == 两条链上 order_id 的并集（无孤儿）。
    ///
    /// 违反任一条直接 panic（清晰消息），供测试当断言用，不在生产路径调用。
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

        let mut cur = best;
        let mut closer: Option<usize> = None; // 上一轮访问的 order（更靠近 best）
        while let Some(idx) = cur {
            assert!(visited.insert(idx), "{side_name} chain revisits slab idx {idx} (cycle?)");
            let o = self.order(idx);

            if let Some(c) = closer {
                assert_eq!(
                    o.next,
                    Some(c),
                    "{side_name} chain broken: idx {idx}.next must equal {c} (X.prev==Y ⟺ Y.next==X)"
                );
            }
            assert_eq!(
                o.action,
                if is_ask { OrderAction::Ask } else { OrderAction::Bid },
                "{side_name} chain order_id={} has wrong action",
                o.order_id
            );

            let parent = o.parent.unwrap_or_else(|| panic!("{side_name} order_id={} has no parent bucket", o.order_id));
            *bucket_volume.entry(parent).or_insert(0) += o.size - o.filled;
            *bucket_count.entry(parent).or_insert(0) += 1;

            closer = Some(idx);
            cur = o.prev;
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
        assert_eq!(
            buckets_map.len(),
            bucket_volume.len(),
            "{side_name} price-bucket map and chain-reachable buckets must be 1:1"
        );
    }
}

impl Default for OrderBookDirectImpl {
    fn default() -> Self {
        Self::new()
    }
}

impl IOrderBook for OrderBookDirectImpl {
    /// 分派 `newOrder`（对应 Java `:106-126`）。Task 2/3 落地了 GTC（挂单+撮合）；本任务
    /// （Task 4）补齐 IOC/FOK_BUDGET/IOC_BUDGET。裸 FOK 在 Java Direct 侧本身也未落地
    /// （源码标注 `// TODO FOK support`），故仍走 `_` 分支报 `MatchingUnsupportedCommand`
    /// （同步写回 `cmd.result_code`），保证不 panic。
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

    /// 撤单。对应 Java `cancelOrder`(`:491-512`)：未知 id / `uid` 不符 → `MatchingUnknownOrderId`；
    /// 否则从 `order_id_index` 摘除、`remove_order` 摘链摘桶（§4.4），发一枚
    /// `completed=true` 的 REDUCE 事件覆盖整个剩余量，`cmd.action` 回填。
    ///
    /// **`free_bucket` 处理**：`remove_order` 返回的桶已经从价位索引里摘出、不再被任何 order
    /// 引用——Java 用对象池 `objectsPool.put` 回收；这里直接 `free_bucket` 释放回 slab
    /// 空闲栈（对应，纯内部资源管理，不影响外部可观测行为）。
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

        // order slab 槽此时仍存活（remove_order 只摘链摘桶，不动 order 自身字段）——读完
        // 事件需要的字段后再释放槽位。
        let (action, size, filled, price, reserve_bid_price) = {
            let o = self.order(order_idx);
            (o.action, o.size, o.filled, o.price, o.reserve_bid_price)
        };

        cmd.action = Some(action);
        cmd.matcher_event = Some(Box::new(MatcherTradeEvent {
            event_type: MatcherEventType::Reduce,
            active_order_completed: true,
            maker_order_id: 0,
            maker_order_completed: false,
            price,
            size: size - filled,
            bid_gt_ask: false,
            bidder_hold_price: reserve_bid_price,
            matched_order_uid: 0,
            next: None,
        }));

        self.free_order(order_idx);

        CommandResultCode::Success
    }

    /// 部分/全部减量。对应 Java `reduceOrder`(`:515-553`)：`cmd.size<=0` →
    /// `MatchingReduceFailedWrongSize`（先于查单，同 Naive）；未知 id / uid 不符 →
    /// `MatchingUnknownOrderId`；`reduce_by = min(remaining, requested)`，
    /// `can_remove = (reduce_by == remaining)`：
    /// - `can_remove`：等价整单 cancel（`remove_order` 摘链摘桶 + 摘 index + 释放槽）。
    /// - 否则：仅改 `order.size -= reduce_by` 与 `bucket.volume -= reduce_by`（不动
    ///   `num_orders`/链——挂单原地留在同一 FIFO 位置，价格不变）。
    ///
    /// 发一枚 REDUCE 事件（`size=reduce_by`，`active_order_completed=can_remove`），
    /// `cmd.action` 回填。
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

        let (action, price, reserve_bid_price) = {
            let o = self.order(order_idx);
            (o.action, o.price, o.reserve_bid_price)
        };

        cmd.matcher_event = Some(Box::new(MatcherTradeEvent {
            event_type: MatcherEventType::Reduce,
            active_order_completed: can_remove,
            maker_order_id: 0,
            maker_order_completed: false,
            price,
            size: reduce_by,
            bid_gt_ask: false,
            bidder_hold_price: reserve_bid_price,
            matched_order_uid: 0,
            next: None,
        }));
        cmd.action = Some(action);

        if can_remove {
            self.free_order(order_idx);
        }

        CommandResultCode::Success
    }

    /// 改价（可能引发即时撮合）。对应 Java `moveOrder`(`:556-596`)：未知 id / uid 不符 →
    /// `MatchingUnknownOrderId`；现货 BID 风控（Ruling P2-3，`:565`）：`symbol_spec` 存在且
    /// `symbol_type==CurrencyExchangePair && action==BID && cmd.price>order.reserve_bid_price`
    /// → `MatchingMoveFailedPriceOverRiskLimit`（不改任何状态）——`symbol_spec` 为 `None` 时
    /// 该守卫天然不生效（惰性——同 Naive 无此风控）。
    ///
    /// 否则：`remove_order` 摘链摘桶（保留摘出的 `free_bucket` 供复用/清理）、原地改价、
    /// **以该 order 为 taker** 在新价重新撮合。**已有 `filled`/`filled_notional` 的延续**：
    /// `try_match_instantly` 的 `taker_size` 参数语义本就是"本次调用要吃的剩余量"（GTC/IOC
    /// 调用方之所以能直接传 `cmd.size` 是因为全新挂单 `filled` 恒为 0，`remaining==size`
    /// 只是这个通用规则的特例），返回值同样是"本次调用撮合掉的量"（增量，不是累计总量，
    /// 函数内部从 0 开始累加只是因为它对每次调用而言本来就是从 0 开始的独立累加器）。
    /// 因此这里按 Naive `move_order` 同款写法在**调用方（本函数）侧**把已有 `filled` 累加
    /// 到返回的增量上（`total_filled = existing_filled + matched_now`），无需改
    /// `try_match_instantly` 的签名——它不依赖"起点"参数，GTC/IOC 调用方继续原样传
    /// `cmd.size`/隐式 0 起点，不受影响。
    ///
    /// `total_filled==existing_size` → 完全成交，摘 index + 释放槽（不重挂）；否则
    /// `order.filled/filled_notional` 更新为新的累计值，`insert_order(order_idx, free_bucket)`
    /// 重挂新价（复用摘桶）。
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

        // 现货 BID 风控守卫（Ruling P2-3）：`symbol_spec` 缺省时天然不生效。
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
            self.try_match_instantly(action, remaining, reserve_bid_price, Some(new_price), cmd);

        let total_filled = existing_filled + matched_now;

        if total_filled == existing_size {
            // 完全成交 -> 摘 index + 释放槽，不重挂。
            self.order_id_index.remove(&order_id);
            self.free_order(order_idx);
            // `free_bucket`（若有）此时已从价位索引摘出且不再被任何 order 引用——不释放会
            // 永久滞留在 slab 里成为孤儿槽（Java 对象池同一场景下也不会把它放回池，但那只是
            // "不复用"，不是真正的内存泄漏；我们的 slab 没有 GC，放着不管才是真泄漏）。
            // 释放它不影响任何外部可观测行为（价位索引/order_id_index/事件/结果码均已在上面
            // 处理完毕），故补上这一步。
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

    /// L2 快照。对应 Java `fillAsks`/`fillBids`(`:916-935`)：**纯桶图迭代、不走链**——ask 按
    /// `ask_price_buckets` 升序（最优价最先）、bid 按 `bid_price_buckets` 降序（`.rev()`，最优价
    /// 最先），每档取 `bucket.tail.price`/`bucket.volume`，按 `size` 截断。`size==0`→零档、
    /// 负数→不限档、正数→截断到该档数——与 `OrderBookNaiveImpl::fill_l2` 语义逐位一致
    /// （L2MarketData 本移植未含 `ask_orders`/`bid_orders` 字段，故不填 `num_orders`）。
    fn fill_l2(&self, size: i32) -> L2MarketData {
        let take: usize = match size {
            0 => 0,
            s if s < 0 => usize::MAX,
            s => s as usize,
        };

        let mut ask_prices = Vec::new();
        let mut ask_volumes = Vec::new();
        for &bucket_idx in self.ask_price_buckets.values() {
            if ask_prices.len() == take {
                break;
            }
            let b = self.bucket(bucket_idx);
            ask_prices.push(self.order(b.tail).price);
            ask_volumes.push(b.volume);
        }

        let mut bid_prices = Vec::new();
        let mut bid_volumes = Vec::new();
        for &bucket_idx in self.bid_price_buckets.values().rev() {
            if bid_prices.len() == take {
                break;
            }
            let b = self.bucket(bucket_idx);
            bid_prices.push(self.order(b.tail).price);
            bid_volumes.push(b.volume);
        }

        L2MarketData { ask_prices, ask_volumes, bid_prices, bid_volumes }
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
    fn skeleton_new_order_reports_unsupported_for_unimplemented_types() {
        // GTC/IOC/FOK_BUDGET/IOC_BUDGET 从 Task 2/3/4 起都有真实实现（见下方 gtc_*/ioc_*/
        // fok_budget_*/ioc_budget_* 测试）；裸 FOK 在 Java Direct 侧本身未落地
        // （`// TODO FOK support`），此处覆盖它仍占位、保证骨架不 panic。
        let mut book = OrderBookDirectImpl::new();
        let mut cmd = OrderCommand {
            order_id: 1,
            symbol: 1,
            price: 100,
            size: 10,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Fok),
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

    // ---- Task 2: insertOrder + GTC 挂单 + fill_l2 + validate_internal_state ----

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
        // 乱序挂：110 -> 100(best) -> 120，fill_l2 必须按价升序返回。
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

        // best_ask 必须是价格最低（100）的那个 order。
        let best = book.best_ask.expect("best_ask must be set");
        assert_eq!(book.order(best).price, 100);
        assert!(book.order(best).next.is_none());
    }

    #[test]
    fn gtc_bids_out_of_order_fill_l2_descending_with_correct_volumes() {
        // 乱序挂：90 -> 100(best) -> 80，fill_l2 必须按价降序返回（最高价最优先）。
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
        // 同价三单依次挂：桶内 tail 必须是最后一个挂入的（FIFO 最新），
        // num_orders/volume 必须聚合正确。
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Ask, 100, 5);
        place_gtc(&mut book, 2, OrderAction::Ask, 100, 7);
        place_gtc(&mut book, 3, OrderAction::Ask, 100, 3);
        book.validate_internal_state();

        let bucket_idx = *book.ask_price_buckets.get(&100).expect("bucket at 100 must exist");
        let bucket = book.bucket(bucket_idx);
        assert_eq!(bucket.volume, 15);
        assert_eq!(bucket.num_orders, 3);
        // tail = 最新挂入 = order_id 3。
        assert_eq!(book.order(bucket.tail).order_id, 3);

        // 撮合序（沿 .prev 从 best 起）= 最老先撮合：best = 1(最老) -> 2 -> 3(最新/tail)。
        // 注意：`insertOrder` 情形 A（价位已有桶）从不改写 `bestAsk`/`bestBid`——同价挂多单时
        // best 恒是该价位第一次建桶时的那个 order（最老），新单只接到桶的“远端”（tail）。
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
        book.bucket_mut(bucket_idx).volume = 999; // 人为破坏不变式
        book.validate_internal_state();
    }

    #[test]
    #[should_panic(expected = "order_id_index must exactly equal")]
    fn validate_internal_state_catches_orphan_in_order_id_index() {
        let mut book = OrderBookDirectImpl::new();
        place_gtc(&mut book, 1, OrderAction::Ask, 100, 10);
        book.order_id_index.insert(999, 0); // 人为造孤儿索引项
        book.validate_internal_state();
    }

    // ---- Task 3: tryMatchInstantly 撮合主循环 + GTC 撮合（对拍 Naive） ----

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
            "Direct 事件链须与 Naive 逐位一致"
        );

        let ev = d_taker.matcher_event.as_ref().expect("应有成交事件");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.maker_order_id, 1);
        assert_eq!(ev.matched_order_uid, 1);
        assert_eq!(ev.price, 100);
        assert_eq!(ev.size, 6);
        assert!(!ev.maker_order_completed, "maker 只部分成交(10 中吃 6)，应留簿");
        assert!(ev.active_order_completed, "taker 6 全部成交");
        assert!(ev.next.is_none(), "只应有一笔成交");

        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        direct.validate_internal_state();
    }

    #[test]
    fn multi_bucket_sweep_matches_naive_event_chain_and_completion_timing() {
        // taker 吃穿两个价位桶（100 全部 5 + 101 部分 3/7），第三个价位 102 留在簿上未被触碰。
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();

        for (id, price, size) in [(1i64, 100i64, 5i64), (2, 101, 7), (3, 102, 4)] {
            direct.new_order(&mut gtc_cmd(id, OrderAction::Ask, price, size));
            naive.new_order(&mut gtc_cmd(id, OrderAction::Ask, price, size));
        }

        let mut d_taker = gtc_cmd(10, OrderAction::Bid, 101, 8); // 5(全) + 3(部分,101 桶剩 4)
        direct.new_order(&mut d_taker);
        let mut n_taker = gtc_cmd(10, OrderAction::Bid, 101, 8);
        naive.new_order(&mut n_taker);

        assert_eq!(
            d_taker.matcher_event, n_taker.matcher_event,
            "多桶扫单事件链须与 Naive 逐位一致"
        );

        // 事件链应为 2 笔：先 100(全成,5)，后 101(部分,3)。
        let ev1 = d_taker.matcher_event.as_ref().expect("应有第一笔成交");
        assert_eq!(ev1.maker_order_id, 1);
        assert_eq!(ev1.price, 100);
        assert_eq!(ev1.size, 5);
        assert!(ev1.maker_order_completed);
        assert!(!ev1.active_order_completed, "taker 剩余量还没吃完(8中吃5)");

        let ev2 = ev1.next.as_ref().expect("应有第二笔成交");
        assert_eq!(ev2.maker_order_id, 2);
        assert_eq!(ev2.price, 101);
        assert_eq!(ev2.size, 3);
        assert!(!ev2.maker_order_completed, "101 桶只吃 3/7，maker 留簿");
        assert!(ev2.active_order_completed, "taker 已全部成交(5+3=8)");
        assert!(ev2.next.is_none());

        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        // 100 桶清空，101 桶剩 4，102 桶原样 4。
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

        let ev = taker.matcher_event.as_ref().expect("应有成交事件");
        assert_eq!(ev.size, 5);
        assert!(ev.maker_order_completed);
        assert!(!ev.active_order_completed, "taker 剩余 7 未成交，需挂簿");

        // taker 剩余 7 应挂在 bid 侧、ask 侧已清空。
        let l2 = direct.fill_l2(10);
        assert!(l2.ask_prices.is_empty());
        assert_eq!(l2.bid_prices, vec![100]);
        assert_eq!(l2.bid_volumes, vec![7]);

        // 挂单确实登记进 order_id_index，可被后续操作（如撤单）定位到。
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

        let ev = taker.matcher_event.as_ref().expect("应有成交事件");
        assert_eq!(ev.size, 10);
        assert!(ev.maker_order_completed);
        assert!(ev.active_order_completed);

        // taker 全部成交，不应挂簿，也不应出现在 order_id_index 里。
        assert!(!direct.order_id_index.contains_key(&2));
        let l2 = direct.fill_l2(10);
        assert!(l2.ask_prices.is_empty());
        assert!(l2.bid_prices.is_empty());
        direct.validate_internal_state();
    }

    #[test]
    fn dup_id_rejects_remaining_after_partial_match_matching_naive() {
        // order_id=1 已被一笔远离成交价的挂单占用(bid@50)；taker 复用同一 order_id=1 去吃 ask@100，
        // 部分成交后因 order_id 已存在而拒绝剩余——被吃的 maker(ask, order_id=2)与占位的 order_id=1
        // 是两个不同订单，match 过程不会碰到 order_id=1 的挂单，故 dup 检测在 match 后仍能命中。
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();

        direct.new_order(&mut gtc_cmd(1, OrderAction::Bid, 50, 5)); // 占位，order_id=1，不参与撮合
        naive.new_order(&mut gtc_cmd(1, OrderAction::Bid, 50, 5));
        direct.new_order(&mut gtc_cmd(2, OrderAction::Ask, 100, 10)); // 真正的 maker
        naive.new_order(&mut gtc_cmd(2, OrderAction::Ask, 100, 10));

        let mut d_taker = gtc_cmd(1, OrderAction::Bid, 100, 15); // 复用 order_id=1（dup）
        direct.new_order(&mut d_taker);
        let mut n_taker = gtc_cmd(1, OrderAction::Bid, 100, 15);
        naive.new_order(&mut n_taker);

        assert_eq!(
            d_taker.matcher_event, n_taker.matcher_event,
            "dup-id 拒绝剩余的事件链须与 Naive 逐位一致"
        );

        // 事件链头应是 REJECT（剩余 5 = 15 - 10），其后接已发生的 TRADE(10)。
        let head = d_taker.matcher_event.as_ref().expect("应有事件链");
        assert_eq!(head.event_type, MatcherEventType::Reject);
        assert_eq!(head.size, 5);
        assert!(head.active_order_completed);
        let trade = head.next.as_ref().expect("reject 之后应有成交事件");
        assert_eq!(trade.event_type, MatcherEventType::Trade);
        assert_eq!(trade.size, 10);
        assert_eq!(trade.maker_order_id, 2);
        assert!(trade.next.is_none());

        // taker 未被挂簿（重复 id 不能挂）；order_id=1 仍只对应最早那笔占位挂单(bid@50)。
        let l2 = direct.fill_l2(10);
        assert_eq!(l2.ask_prices, Vec::<i64>::new(), "ask@100 已被吃满清空");
        assert_eq!(l2.bid_prices, vec![50]);
        assert_eq!(l2.bid_volumes, vec![5]);
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        direct.validate_internal_state();
    }

    // ---- Task 4: IOC / FOK_BUDGET / IOC_BUDGET（对拍 Naive） ----

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

        assert_eq!(d.matcher_event, n.matcher_event, "IOC 部分成交+拒绝剩余的事件链须与 Naive 逐位一致");
        assert_eq!(d.result_code, Some(CommandResultCode::Success));

        // 成交 5，剩 5 丢弃、不挂簿。
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
        let ev = d.matcher_event.as_ref().expect("应有成交事件");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert!(ev.next.is_none(), "全部成交不应带 REJECT");

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
        let ev = d.matcher_event.as_ref().expect("应有 REJECT 事件");
        assert_eq!(ev.event_type, MatcherEventType::Reject);
        assert_eq!(ev.size, 10);
        assert!(!direct.order_id_index.contains_key(&1));
        direct.validate_internal_state();
    }

    #[test]
    fn fok_budget_rejects_when_budget_insufficient_matching_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10)); // 需要 10*100=1000 才能吃满
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));

        let mut d = taker_cmd(2, OrderAction::Bid, OrderType::FokBudget, 500, 10);
        direct.new_order(&mut d);
        let mut n = taker_cmd(2, OrderAction::Bid, OrderType::FokBudget, 500, 10);
        naive.new_order(&mut n);

        assert_eq!(d.matcher_event, n.matcher_event);
        let ev = d.matcher_event.as_ref().expect("应有 REJECT 事件");
        assert_eq!(ev.event_type, MatcherEventType::Reject);
        assert_eq!(ev.size, 10);
        // 簿未改变
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
        // 需要 5*100 + 5*200 = 1500 才能吃满 size=10。
        let budget = 1500;
        let mut d = taker_cmd(3, OrderAction::Bid, OrderType::FokBudget, budget, 10);
        direct.new_order(&mut d);
        let mut n = taker_cmd(3, OrderAction::Bid, OrderType::FokBudget, budget, 10);
        naive.new_order(&mut n);

        assert_eq!(d.matcher_event, n.matcher_event, "FOK_BUDGET 跨桶成交事件链须与 Naive 逐位一致");
        let ev1 = d.matcher_event.as_ref().expect("应有第一笔成交");
        assert_eq!(ev1.price, 100);
        assert_eq!(ev1.size, 5);
        let ev2 = ev1.next.as_ref().expect("应有第二笔成交");
        assert_eq!(ev2.price, 200);
        assert_eq!(ev2.size, 5);
        assert!(ev2.next.is_none());

        assert!(direct.fill_l2(10).ask_prices.is_empty());
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        direct.validate_internal_state();
    }

    #[test]
    fn fok_budget_insufficient_liquidity_rejects_matching_naive() {
        // 总量不足(3<10)，checkBudgetToFill 应返回哨兵(流动性不足)而非某个有限预算值。
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 3));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 3));

        let mut d = taker_cmd(2, OrderAction::Bid, OrderType::FokBudget, i64::MAX, 10);
        direct.new_order(&mut d);
        let mut n = taker_cmd(2, OrderAction::Bid, OrderType::FokBudget, i64::MAX, 10);
        naive.new_order(&mut n);

        assert_eq!(d.matcher_event, n.matcher_event);
        let ev = d.matcher_event.as_ref().expect("应有 REJECT 事件");
        assert_eq!(ev.event_type, MatcherEventType::Reject);
        assert_eq!(ev.size, 10);
        assert_eq!(direct.fill_l2(10).ask_volumes, vec![3]); // 簿未改变
        direct.validate_internal_state();
    }

    /// Ruling P2-1 专测：BID FOK_BUDGET 小总预算 vs 高价 ask。Direct 必须镜像 Naive（不复刻
    /// Java Direct "BID FOK_BUDGET 复用 cmd.price 当每单价上限"的巧合，见 `try_match_instantly`
    /// 文档 + 规格 §8）。两个子案例都要求 Direct 与 Naive 产生完全一致的结果（同 fill 或同 reject）：
    /// - 案例 A：预算(500) 远小于唯一 ask 单价(1000)——连 1 个单位都买不起，两者应一致整单 REJECT。
    /// - 案例 B：预算(5200)恰好覆盖"便宜档(100*2=200)+贵档(5000*1=5000)"，贵档单价(5000)远高于
    ///   案例 A 的预算量级，验证跨桶后半段仍被正确撮合（FOK_BUDGET 撮合走 `limit_price=None`
    ///   不设每单价上限），两者应一致整单成交。
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

            assert_eq!(d.matcher_event, n.matcher_event, "案例A：Direct 须与 Naive 一致地整单拒绝");
            let ev = d.matcher_event.as_ref().expect("应有 REJECT 事件");
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

            let budget = 100 * 2 + 5000; // 5200，恰好足够
            let mut d = taker_cmd(3, OrderAction::Bid, OrderType::FokBudget, budget, 3);
            direct.new_order(&mut d);
            let mut n = taker_cmd(3, OrderAction::Bid, OrderType::FokBudget, budget, 3);
            naive.new_order(&mut n);

            assert_eq!(d.matcher_event, n.matcher_event, "案例B：Direct 须与 Naive 逐位一致地整单成交");
            let ev1 = d.matcher_event.as_ref().expect("应有第一笔成交");
            assert_eq!(ev1.event_type, MatcherEventType::Trade);
            assert_eq!(ev1.price, 100);
            assert_eq!(ev1.size, 2);
            let ev2 = ev1.next.as_ref().expect("应有第二笔成交");
            assert_eq!(ev2.price, 5000);
            assert_eq!(ev2.size, 1);
            assert!(ev2.next.is_none());

            assert!(direct.fill_l2(10).ask_prices.is_empty(), "两档全部吃满");
            assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
            direct.validate_internal_state();
        }
    }

    #[test]
    fn check_budget_to_fill_saturates_on_overflow_instead_of_panicking() {
        let mut direct = OrderBookDirectImpl::new();
        // price * size 远超 i64::MAX，验证 i128 累加 + 饱和转换不 panic。
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, i64::MAX / 2, 4));
        let budget = direct.check_budget_to_fill(OrderAction::Bid, 4);
        assert_eq!(budget, i64::MAX);
        // 哨兵值必须被 is_budget_limit_satisfied 显式排除（不因数值恰好 >= limit 而"意外满足"）。
        assert!(!OrderBookDirectImpl::is_budget_limit_satisfied(OrderAction::Bid, budget, i64::MAX));
    }

    #[test]
    fn ioc_budget_partial_fill_capped_by_budget_matches_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10)); // 10 @ 100 = notional 1000 max
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));

        // 预算 250 只够买 2 个单位（2*100=200 <= 250 < 300）。
        let mut d = taker_cmd(2, OrderAction::Bid, OrderType::IocBudget, 250, 10);
        direct.new_order(&mut d);
        let mut n = taker_cmd(2, OrderAction::Bid, OrderType::IocBudget, 250, 10);
        naive.new_order(&mut n);

        assert_eq!(d.matcher_event, n.matcher_event, "IOC_BUDGET 预算限量事件链须与 Naive 逐位一致");
        let head = d.matcher_event.as_ref().expect("应有事件链");
        assert_eq!(head.event_type, MatcherEventType::Reject);
        assert_eq!(head.size, 8);
        let trade = head.next.as_ref().expect("reject 之后应有成交事件");
        assert_eq!(trade.event_type, MatcherEventType::Trade);
        assert_eq!(trade.size, 2);
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
        let ev = d.matcher_event.as_ref().expect("应有 REJECT 事件");
        assert_eq!(ev.event_type, MatcherEventType::Reject);
        assert_eq!(ev.size, 10);
        assert_eq!(direct.fill_l2(10).ask_volumes, vec![10]); // 簿未改变
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
        let ev = d.matcher_event.as_ref().expect("应有 REJECT 事件");
        assert_eq!(ev.event_type, MatcherEventType::Reject);
        assert_eq!(ev.size, 5);
        // 簿未改变(仍是 bid@100 x10)
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
        let ev = d.matcher_event.as_ref().expect("应有成交事件");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert!(ev.next.is_none(), "全部成交、无 REJECT");
        assert!(direct.fill_l2(10).ask_prices.is_empty());
        direct.validate_internal_state();
    }

    /// 覆盖 `match_against_budget_ioc` 文档中论证的"批次耗尽后同价位剩余挂单不重试"场景：
    /// 同一价位桶内有两个 FIFO 挂单，预算恰好只够吃完第一个，第二个应原样留簿（不被触碰），
    /// 且必须成为新的 best_ask（不能因内部实现细节丢失引用）。Direct 与 Naive 须逐位一致。
    #[test]
    fn ioc_budget_leaves_untouched_sibling_order_in_same_bucket_as_new_best_matches_naive() {
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();
        // 同价 100 两单：order1(size2) 先挂，order2(size5) 后挂（FIFO：先吃 order1）。
        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 2));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 2));
        direct.new_order(&mut gtc_cmd(2, OrderAction::Ask, 100, 5));
        naive.new_order(&mut gtc_cmd(2, OrderAction::Ask, 100, 5));

        // 预算 200 恰好只够吃 order1(2*100=200)，之后 remaining_budget=0 < 100，批次关闭。
        let mut d = taker_cmd(3, OrderAction::Bid, OrderType::IocBudget, 200, 10);
        direct.new_order(&mut d);
        let mut n = taker_cmd(3, OrderAction::Bid, OrderType::IocBudget, 200, 10);
        naive.new_order(&mut n);

        assert_eq!(d.matcher_event, n.matcher_event, "同桶内批次关闭后事件链须与 Naive 逐位一致");
        let head = d.matcher_event.as_ref().expect("应有事件链");
        assert_eq!(head.event_type, MatcherEventType::Reject);
        assert_eq!(head.size, 8); // 10 - 2
        let trade = head.next.as_ref().expect("reject 之后应有成交事件");
        assert_eq!(trade.event_type, MatcherEventType::Trade);
        assert_eq!(trade.maker_order_id, 1);
        assert_eq!(trade.size, 2);
        assert!(trade.maker_order_completed);
        assert!(trade.next.is_none(), "order2 不应被触碰");

        // order2 必须原样留在簿上（未被吃、未丢失引用），且成为新的 best_ask。
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        assert_eq!(direct.fill_l2(10).ask_prices, vec![100]);
        assert_eq!(direct.fill_l2(10).ask_volumes, vec![5]);
        let best = direct.order_id_index.get(&2).copied().expect("order2 应仍在索引中");
        assert_eq!(direct.order(best).order_id, 2);
        assert_eq!(direct.order(best).filled, 0, "order2 未被触碰");
        direct.validate_internal_state();
    }

    /// 回归测试（code review 发现的严重 bug）：`match_against_budget_ioc` 曾经在"吃穿整个桶、
    /// 跨到下一个（更差）价位"（`midx == price_bucket_tail`）时忘记把 `batch_remaining` 清零，
    /// 导致把按*旧*价位算出的预算余量（`batch_remaining` 仍 >0，因为这一档是被"流动性耗尽"
    /// 而非"预算/量耗尽"截断的）原封不动地套到*新*价位上继续吃，完全跳过了对新价位的
    /// affordability 检查——这与 Naive 对每个价位都独立重新计算 `size_cap`（`match_against_budget`,
    /// `order_book_naive_impl.rs:261-271`）不一致，会产生比预算实际能负担的更多成交（复现：
    /// asks `100 -> {size 3}` 后 `200 -> {size 100}`，taker BID IOC_BUDGET `size=10`
    /// `budget(price)=1000`；旧 bug 版本会在跨桶后继续用价位 100 算出的 `size_cap=10` 里剩下
    /// 的 `7` 去吃 200 那一档而不重新核对 700 的预算，得到 filled=10、notional=1700>1000）。
    ///
    /// 正确结果（Naive 与修复后的 Direct 都应如此）：价位 100 的 `size_cap=min(10,1000/100=10)
    /// =10`，但该桶只有 3 → 吃 3（`maker_completed=true`），花掉 300，剩预算 700；跨桶后为
    /// 价位 200 **重新计算** `size_cap=min(7,700/200=3)=3` → 只吃 3（`maker_completed=false`，
    /// 97 留簿），花掉 600，剩预算 100；100/200 都不够再买 1 单位 → 停止。总成交 6
    /// （3@100+3@200），剩 4 走 REJECT。
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
            "跨价位桶的 IOC_BUDGET 事件链（含每笔的 active_order_completed）须与 Naive 逐位一致"
        );

        let head = d.matcher_event.as_ref().expect("应有事件链");
        assert_eq!(head.event_type, MatcherEventType::Reject);
        assert_eq!(head.size, 4, "10 - 6 = 4 未成交");

        let trade1 = head.next.as_ref().expect("应有第一笔成交(@100)");
        assert_eq!(trade1.event_type, MatcherEventType::Trade);
        assert_eq!(trade1.price, 100);
        assert_eq!(trade1.size, 3);
        assert!(trade1.maker_order_completed, "价位100仅有的3个单位被吃满");
        assert!(!trade1.active_order_completed, "批次上限(10)未耗尽——只是这一档没货了");

        let trade2 = trade1.next.as_ref().expect("应有第二笔成交(@200)");
        assert_eq!(trade2.event_type, MatcherEventType::Trade);
        assert_eq!(trade2.price, 200);
        assert_eq!(trade2.size, 3, "跨桶后必须用700(剩余预算)/200=3重新计算，而非沿用旧批次的7");
        assert!(!trade2.maker_order_completed, "100单位的挂单只吃了3");
        assert!(trade2.active_order_completed, "价位200的新批次上限(3)恰好耗尽");
        assert!(trade2.next.is_none());

        // 簿状态：100 桶被吃穿清空，200 桶剩 97（100-3）。
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        assert_eq!(direct.fill_l2(10).ask_prices, vec![200]);
        assert_eq!(direct.fill_l2(10).ask_volumes, vec![97]);
        direct.validate_internal_state();
    }

    // ---- P2 Task 5: cancel / reduce / move + removeOrder（对拍 Naive） ----

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
        book.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10)); // uid=1 (gtc_cmd 用 order_id 当 uid)

        let mut cmd = cancel_cmd(1, 999);
        assert_eq!(book.cancel_order(&mut cmd), CommandResultCode::MatchingUnknownOrderId);
        // 订单未被撤销，仍在簿上
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
        assert_eq!(d.matcher_event, n.matcher_event, "cancel REDUCE 事件须与 Naive 逐位一致");
        assert_eq!(d.action, n.action);

        let ev = d.matcher_event.as_ref().expect("应有 REDUCE 事件");
        assert_eq!(ev.event_type, MatcherEventType::Reduce);
        assert_eq!(ev.size, 10);
        assert!(ev.active_order_completed);
        assert!(ev.next.is_none());

        assert!(direct.fill_l2(10).ask_prices.is_empty());
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        assert!(!direct.order_id_index.contains_key(&1));
        direct.validate_internal_state();

        // 二次撤销 -> 未知订单（对拍 Naive 同样行为）。
        let mut again_d = cancel_cmd(1, 1);
        let mut again_n = cancel_cmd(1, 1);
        assert_eq!(direct.cancel_order(&mut again_d), CommandResultCode::MatchingUnknownOrderId);
        assert_eq!(naive.cancel_order(&mut again_n), CommandResultCode::MatchingUnknownOrderId);
    }

    #[test]
    fn cancel_one_of_two_orders_keeps_bucket_and_sibling_chain_matches_naive() {
        // order_id=1 是桶内最老(best)，order_id=2 是 tail(最新)——撤最老的那个，练到
        // removeOrder 的"非 tail"分支（`bucket.tail != order`）。
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

        // 桶未空（order 2 还在），价位仍在，volume/num_orders 正确聚合。
        let l2 = direct.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![100]);
        assert_eq!(l2.ask_volumes, vec![5]);
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));

        // order 2 现在应是该侧唯一 order，也是新 best。
        let best = direct.best_ask.expect("best_ask must be set");
        assert_eq!(direct.order(best).order_id, 2);
        assert!(direct.order(best).next.is_none());
        assert!(direct.order(best).prev.is_none());
        direct.validate_internal_state();
    }

    #[test]
    fn cancel_tail_order_when_it_is_only_order_removes_bucket() {
        // order_id=1 既是桶内唯一 order 也是 tail——练到 removeOrder"整桶清空"分支。
        let mut direct = OrderBookDirectImpl::new();
        direct.new_order(&mut gtc_cmd(1, OrderAction::Bid, 90, 4));
        direct.new_order(&mut gtc_cmd(2, OrderAction::Bid, 80, 2)); // 另一个价位，防止两侧全空的退化情形

        let mut d = cancel_cmd(1, 1);
        assert_eq!(direct.cancel_order(&mut d), CommandResultCode::Success);

        assert!(!direct.bid_price_buckets.contains_key(&90), "空桶必须从价位索引摘除");
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
        assert_eq!(d.matcher_event, n.matcher_event, "部分 reduce 的 REDUCE 事件须与 Naive 逐位一致");
        let ev = d.matcher_event.as_ref().expect("应有 REDUCE 事件");
        assert_eq!(ev.event_type, MatcherEventType::Reduce);
        assert_eq!(ev.size, 4);
        assert!(!ev.active_order_completed);

        assert_eq!(direct.fill_l2(10).ask_volumes, vec![6]);
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        // 订单仍在 index 里，FIFO 位置/价格不变，num_orders 不受影响。
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

        // 请求减 100，但剩余只有 10 -> clamp 到 10，整单移除。
        let mut d = reduce_cmd(1, 1, 100);
        let mut n = reduce_cmd(1, 1, 100);
        assert_eq!(direct.reduce_order(&mut d), CommandResultCode::Success);
        assert_eq!(naive.reduce_order(&mut n), CommandResultCode::Success);

        assert_eq!(d.matcher_event, n.matcher_event);
        let ev = d.matcher_event.as_ref().expect("应有 REDUCE 事件");
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
        assert!(d.matcher_event.is_none(), "未交叉，无成交事件");
        assert_eq!(d.action, n.action);

        let l2 = direct.fill_l2(10);
        assert!(l2.ask_prices.iter().all(|&p| p != 100), "旧价已清空");
        assert_eq!(l2.ask_prices, vec![105]);
        assert_eq!(l2.ask_volumes, vec![10]);
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        direct.validate_internal_state();
    }

    #[test]
    fn move_reprices_into_existing_bucket_matches_naive() {
        // 移到一个已有挂单的价位（insert_order 情形 A），检查 tail/volume/num_orders 聚合正确。
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

        // order 2（ask@100)移到 80，应与 order 1(bid@90) 立即成交。
        let mut d = move_cmd(2, 2, 80);
        let mut n = move_cmd(2, 2, 80);
        assert_eq!(direct.move_order(&mut d), CommandResultCode::Success);
        assert_eq!(naive.move_order(&mut n), CommandResultCode::Success);
        assert_eq!(d.matcher_event, n.matcher_event, "移价交叉后的 TRADE 事件须与 Naive 逐位一致");

        let ev = d.matcher_event.as_ref().expect("移价交叉后应立即成交");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.maker_order_id, 1);
        assert_eq!(ev.price, 90, "成交价=maker(bid)价，非移价目标价 80");
        assert_eq!(ev.size, 5);
        assert!(!ev.maker_order_completed, "bid(maker) 只吃 5/10，未完成");
        assert!(ev.active_order_completed, "ask(taker) 5 全部成交");

        // ask 全部成交不再挂簿；bid 剩 5。
        assert!(direct.fill_l2(10).ask_prices.is_empty());
        assert_eq!(direct.fill_l2(10).bid_volumes, vec![5]);
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        assert!(!direct.order_id_index.contains_key(&2));
        direct.validate_internal_state();

        // 完全成交的订单不该再能被撤销。
        let mut cancel_d = cancel_cmd(2, 2);
        let mut cancel_n = cancel_cmd(2, 2);
        assert_eq!(direct.cancel_order(&mut cancel_d), CommandResultCode::MatchingUnknownOrderId);
        assert_eq!(naive.cancel_order(&mut cancel_n), CommandResultCode::MatchingUnknownOrderId);
    }

    #[test]
    fn move_carries_over_existing_filled_when_fully_matching_matches_naive() {
        // 关键场景（规格 §"move's carried-over filled"）：order 1 在移价前已被部分成交
        // （filled=4，留簿剩 6），移价后与另一侧挂单恰好撮合剩余 6——验证 Direct 与 Naive
        // 都能正确地把"移价前已有的 filled"与"移价撮合产生的新增量"相加。
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();

        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10)); // 待移价的订单
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        direct.new_order(&mut gtc_cmd(2, OrderAction::Bid, 100, 4)); // 先吃掉 order1 的 4/10
        naive.new_order(&mut gtc_cmd(2, OrderAction::Bid, 100, 4));
        direct.new_order(&mut gtc_cmd(3, OrderAction::Bid, 85, 6)); // 移价目标价的对手方，恰好吃满剩余 6
        naive.new_order(&mut gtc_cmd(3, OrderAction::Bid, 85, 6));

        // 移价前内部状态核对：order1 filled=4，剩 6，仍挂在 100。
        let idx1 = direct.order_id_index[&1];
        assert_eq!(direct.order(idx1).filled, 4);
        assert_eq!(direct.order(idx1).size, 10);

        let mut d = move_cmd(1, 1, 85);
        let mut n = move_cmd(1, 1, 85);
        assert_eq!(direct.move_order(&mut d), CommandResultCode::Success);
        assert_eq!(naive.move_order(&mut n), CommandResultCode::Success);
        assert_eq!(
            d.matcher_event, n.matcher_event,
            "携带既有 filled 的移价撮合事件须与 Naive 逐位一致"
        );

        let ev = d.matcher_event.as_ref().expect("移价后应立即撮合剩余 6");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.maker_order_id, 3);
        assert_eq!(ev.size, 6, "剩余量(10-4)全部撮合，而非按订单总 size 重新撮合");
        assert!(ev.active_order_completed, "order1 总计 4+6=10=size，完全成交");

        // order1 完全成交（4 之前 + 6 现在 = 10），不再挂簿。
        assert!(!direct.order_id_index.contains_key(&1));
        assert!(direct.fill_l2(10).ask_prices.is_empty());
        assert!(direct.fill_l2(10).bid_prices.is_empty(), "order3 也被吃满");
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        direct.validate_internal_state();
    }

    #[test]
    fn move_carries_over_existing_filled_when_partially_matching_rests_with_combined_filled() {
        // 同上场景变体：移价后只撮合到一部分，订单带着"旧 filled + 新增量"重新挂簿。
        let mut direct = OrderBookDirectImpl::new();
        let mut naive = OrderBookNaiveImpl::new();

        direct.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        naive.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 10));
        direct.new_order(&mut gtc_cmd(2, OrderAction::Bid, 100, 3)); // order1: filled=3，剩 7
        naive.new_order(&mut gtc_cmd(2, OrderAction::Bid, 100, 3));
        direct.new_order(&mut gtc_cmd(3, OrderAction::Bid, 85, 2)); // 移价后只够吃 2
        naive.new_order(&mut gtc_cmd(3, OrderAction::Bid, 85, 2));

        let mut d = move_cmd(1, 1, 85);
        let mut n = move_cmd(1, 1, 85);
        assert_eq!(direct.move_order(&mut d), CommandResultCode::Success);
        assert_eq!(naive.move_order(&mut n), CommandResultCode::Success);
        assert_eq!(d.matcher_event, n.matcher_event);

        let ev = d.matcher_event.as_ref().expect("应有一笔成交");
        assert_eq!(ev.size, 2);
        assert!(!ev.active_order_completed, "order1 仍剩 5 (10-3-2) 未成交");

        // order1 应以 filled=5(3+2)、剩 5 重新挂在新价 85。
        let idx1 = direct.order_id_index[&1];
        assert_eq!(direct.order(idx1).filled, 5);
        assert_eq!(direct.order(idx1).price, 85);
        assert_eq!(direct.order(idx1).size, 10);

        let l2 = direct.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![85]);
        assert_eq!(l2.ask_volumes, vec![5]); // size(10) - filled(5)
        assert!(l2.bid_prices.is_empty(), "order3 也被吃满");
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
        // Ruling P2-3：现货 BID 移价不得超过挂单时锁定的 reserve_bid_price。
        let mut book = OrderBookDirectImpl::with_symbol_spec(exchange_pair_spec());
        book.new_order(&mut gtc_bid_with_reserve(1, 90, 5, 95)); // reserve=95

        let mut cmd = move_cmd(1, 1, 96); // 96 > 95 -> 越限
        let rc = book.move_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::MatchingMoveFailedPriceOverRiskLimit);
        assert!(cmd.action.is_none(), "失败分支不应回填 cmd.action（对应 Java 提前 return）");
        assert!(cmd.matcher_event.is_none(), "失败分支不应产生任何事件");

        // 状态完全未变：仍挂在原价 90。
        let l2 = book.fill_l2(10);
        assert_eq!(l2.bid_prices, vec![90]);
        assert_eq!(l2.bid_volumes, vec![5]);
        book.validate_internal_state();
    }

    #[test]
    fn move_bid_within_reserve_price_succeeds_on_exchange_pair_spec() {
        let mut book = OrderBookDirectImpl::with_symbol_spec(exchange_pair_spec());
        book.new_order(&mut gtc_bid_with_reserve(1, 90, 5, 95)); // reserve=95

        let mut cmd = move_cmd(1, 1, 95); // 恰好等于 reserve -> 放行（Java: `cmd.price > reserveBidPrice` 严格大于才拒）
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
        // 风控只针对 BID（Java `:565`：`orderToMove.action == OrderAction.BID` 是必要条件）。
        let mut book = OrderBookDirectImpl::with_symbol_spec(exchange_pair_spec());
        book.new_order(&mut gtc_cmd(1, OrderAction::Ask, 100, 5)); // ASK，reserve_bid_price 恒 0

        let mut cmd = move_cmd(1, 1, 200); // 远超 0，但 ASK 不受该守卫约束
        assert_eq!(book.move_order(&mut cmd), CommandResultCode::Success);
        assert_eq!(book.fill_l2(10).ask_prices, vec![200]);
    }

    #[test]
    fn move_bid_over_reserve_price_allowed_when_symbol_spec_absent() {
        // 未注入 symbol_spec 时守卫天然不生效（同 Naive——Naive 从不做这个风控）。
        let mut direct = OrderBookDirectImpl::new(); // symbol_spec == None
        let mut naive = OrderBookNaiveImpl::new();
        direct.new_order(&mut gtc_bid_with_reserve(1, 90, 5, 95));
        naive.new_order(&mut gtc_bid_with_reserve(1, 90, 5, 95));

        let mut d = move_cmd(1, 1, 999); // 远超 reserve=95，但无 spec -> 放行
        let mut n = move_cmd(1, 1, 999);
        assert_eq!(direct.move_order(&mut d), CommandResultCode::Success);
        assert_eq!(naive.move_order(&mut n), CommandResultCode::Success);
        assert_eq!(direct.fill_l2(10), naive.fill_l2(10));
        direct.validate_internal_state();
    }

    #[test]
    fn cancel_reduce_move_sequence_keeps_internal_state_valid() {
        // 组合场景：多次 cancel/reduce/move 交替操作，每一步后都跑 validate_internal_state。
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
}
