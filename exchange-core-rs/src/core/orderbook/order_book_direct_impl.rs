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
    /// **正确性论证（为何不需要"跳过同价位剩余挂单"的显式 skip 逻辑）**：ask 价格链严格递增、
    /// `remaining_budget` 只减不增。若某次 `batch_remaining` 归零是因为**预算耗尽**（`size_cap`
    /// 由 `affordable` 而非 `remaining` 决定），则 `remaining_budget_new = remaining_budget -
    /// size_cap*price < price`（地板除的余数恒小于除数）；无论下一次遇到的 maker 价格与刚才
    /// 相同还是更差（更高），都满足 `remaining_budget_new < 该价`，故 `affordable=0`、
    /// `size_cap<=0`，重新计算必然立即整体停止——与 Naive"该价位批次已用完、永不重试"的效果
    /// **逐位等价**（都不会再产生任何成交），因此可以放心地"每次 `batch_remaining==0` 就直接
    /// 用当前 maker 重新算一批"，不需要额外记录/跳过"已访问过的价位"。若 `batch_remaining`
    /// 归零是因为 `remaining`（taker 整体剩余）耗尽，则本轮循环顶部的 `remaining==0` 检查已在
    /// 下一次迭代前拦下，同样不会误重试。
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
                    self.ask_price_buckets.remove(&m_price);
                    self.free_bucket(m_parent);
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
}
