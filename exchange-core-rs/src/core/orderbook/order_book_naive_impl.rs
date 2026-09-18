//! 对应 Java `exchange.core2.core.orderbook.OrderBookNaiveImpl`：朴素（TreeMap-per-price-level）
//! 订单簿实现，主要作为参照/对拍实现，用于跟性能优化版 `OrderBookDirectImpl`
//! （见 `order_book_direct_impl.rs`）做一致性验证，而不是生产撮合路径的首选实现。
//!
//! Java 版用 `NavigableMap<Long, OrdersBucketNaive>`：`askBuckets` 用普通 `TreeMap`（升序，
//! 最优卖价在最前），`bidBuckets` 用 `TreeMap<>(Collections.reverseOrder())`（降序，最优买价
//! 在最前）。Rust 这里两个方向都统一用**升序** `BTreeMap`（`ask_buckets`/`bid_buckets`），
//! 需要"最优买价优先"的降序语义时（`fill_l2`、`state_hash`、`chronicle_write`、
//! `chronicle_orders` 等）显式调用 `.rev()` 反转迭代方向，而不是像 Java 那样在构造时
//! 用反向比较器固化排序方向。撮合时的价位遍历方向（`ascending` 参数）也是通过
//! `BTreeMap::range`/`.rev()` 显式表达，对应 Java `subtreeForMatching` 依赖桶自身排序方向
//! 隐式决定遍历顺序的写法。
//!
//! Java 侧订单索引 `idMap: LongObjectHashMap<Order>` 直接持有 `Order` 对象引用；Rust 侧
//! `id_index: BTreeMap<i64, (OrderAction, i64, i64)>` 只保存「桶方向、价位、uid」三元组
//! （订单本体仍归其所在的 `OrdersBucketNaive` 所有），按 `(action, price)` 回到对应桶里
//! 查找/修改订单，避免在 Rust 所有权模型下让 idMap 和桶同时持有/借用同一个 `Order`。
use std::collections::BTreeMap;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::l2_market_data::L2MarketData;
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::matcher_event_type::MatcherEventType;
use crate::core::common::matcher_trade_event::MatcherTradeEvent;
use crate::core::common::order::Order;
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;
use crate::core::orderbook::orders_bucket_naive::MakerFill;
use crate::core::common::symbol_type::SymbolType;
use crate::core::orderbook::i_order_book::IOrderBook;
use crate::core::orderbook::orders_bucket_naive::OrdersBucketNaive;
use crate::core::utils::core_arithmetic_utils::{add_exact, mul_exact, sub_exact};

/// 朴素订单簿：按价位分桶（`OrdersBucketNaive`），价位用 `BTreeMap` 排序（见模块顶部注释）。
/// 对应 Java `OrderBookNaiveImpl` 的四个实例字段：`askBuckets`、`bidBuckets`、
/// `symbolSpec`、`idMap`（此处拆成 `id_index` 轻量索引）；Java 另有 `eventsHelper`（事件对象池）
/// 和 `logDebug` 字段，Rust 没有对应的池化事件构造/调试日志开关，事件直接以 `Box` 构造。
pub struct OrderBookNaiveImpl {
    ask_buckets: BTreeMap<i64, OrdersBucketNaive>,
    bid_buckets: BTreeMap<i64, OrdersBucketNaive>,
    /// order_id -> (action 所在方向, 价位, 下单 uid)，用于按 order_id O(log n) 定位订单所在桶。
    /// 对应 Java `idMap: LongObjectHashMap<Order>`（见结构体注释关于持有 Order 引用 vs 索引三元组的差异）。
    id_index: BTreeMap<i64, (OrderAction, i64, i64)>,
    symbol_spec: Option<CoreSymbolSpecification>,
}

impl OrderBookNaiveImpl {
    /// 构造一个不带 symbol spec 的空订单簿。Java 没有直接对应的无 spec 构造函数——
    /// Java 三个构造函数都要求非空 `CoreSymbolSpecification`；这里允许 `symbol_spec: None`
    /// 主要是为了测试场景（跳过 `move_order` 里针对 CURRENCY_EXCHANGE_PAIR BID 的预留价风控，
    /// 见 `move_order` 与测试 `move_bid_guard_skipped_when_symbol_spec_absent`）。
    pub fn new() -> Self {
        Self {
            ask_buckets: BTreeMap::new(),
            bid_buckets: BTreeMap::new(),
            id_index: BTreeMap::new(),
            symbol_spec: None,
        }
    }

    /// 构造带 symbol spec 的订单簿，大致对应 Java
    /// `OrderBookNaiveImpl(CoreSymbolSpecification symbolSpec, LoggingConfiguration loggingCfg)`
    /// 构造函数（非池化事件 helper 的那个变体）；Rust 侧没有 `ObjectsPool`/`LoggingConfiguration`
    /// 参数，只保留驱动业务逻辑分支所需的 symbol spec。
    pub fn with_symbol_spec(symbol_spec: CoreSymbolSpecification) -> Self {
        Self { symbol_spec: Some(symbol_spec), ..Self::new() }
    }

    /// 对应 Java `newOrderPlaceGtc`：先尝试与对手方即时撮合，未完全成交的剩余部分才挂单入桶；
    /// 若 order_id 已存在（重复下单）则已撮合部分保留成交效果，剩余部分走 reject（不入桶），
    /// 与 Java 的重复 id 处理逻辑一致（见 Java 源码 140-150 行注释 "duplicate order id"）。
    fn new_order_place_gtc(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("GTC order requires action");
        let price = cmd.price;
        let size = cmd.size;

        let (filled, filled_notional) = self.try_match_instantly(action, price, size, cmd.reserve_bid_price, 0, 0, cmd);
        if filled == size {
            return;
        }

        let order_id = cmd.order_id;
        if self.id_index.contains_key(&order_id) {
            Self::attach_reject_event(cmd, size - filled);
            return;
        }

        let order = Order {
            order_id,
            price,
            size,
            filled,
            filled_notional,
            reserve_bid_price: cmd.reserve_bid_price,
            action,
            order_type: cmd.order_type.expect("GTC order requires order_type"),
            uid: cmd.uid,
            timestamp: cmd.timestamp,
            user_cookie: cmd.user_cookie,
            command: cmd.command,
        };

        self.buckets_by_action_mut(action)
            .entry(price)
            .or_insert_with(|| OrdersBucketNaive::new(price))
            .put(order);
        self.id_index.insert(order_id, (action, price, cmd.uid));
    }

    /// 以限价 `taker_price` 为界即时撮合对手盘，对应 Java `tryMatchInstantly(activeOrder,
    /// subtreeForMatching(action, price), triggerCmd)`：Java 通过
    /// `(action == ASK ? bidBuckets : askBuckets).headMap(price, true)` 截出价位不劣于
    /// taker 限价的对手桶子集再线性遍历；这里改用 `BTreeMap::range` 直接按价位区间取出候选价位，
    /// 语义等价（BID 只吃 `<= taker_price` 的 ask 桶，ASK 只吃 `>= taker_price` 的 bid 桶）。
    /// 调用点对应 Java `newOrderPlaceGtc`/`newOrderMatchIoc`/`moveOrder` 里对 `tryMatchInstantly`
    /// 的三处限价撮合用法。
    fn try_match_instantly(
        &mut self,
        taker_action: OrderAction,
        taker_price: i64,
        taker_size: i64,
        taker_reserve_bid_price: i64,
        taker_prior_filled: i64,
        taker_prior_filled_notional: i64,
        cmd: &mut OrderCommand,
    ) -> (i64, i64) {
        match taker_action {
            OrderAction::Bid => Self::match_against(
                &mut self.ask_buckets,
                &mut self.id_index,
                Some(taker_price),
                taker_size,
                taker_action,
                taker_reserve_bid_price,
                true,
                taker_prior_filled,
                taker_prior_filled_notional,
                cmd,
            ),
            OrderAction::Ask => Self::match_against(
                &mut self.bid_buckets,
                &mut self.id_index,
                Some(taker_price),
                taker_size,
                taker_action,
                taker_reserve_bid_price,
                false,
                taker_prior_filled,
                taker_prior_filled_notional,
                cmd,
            ),
        }
    }

    /// 不设价位上限、吃穿整本对手盘的撮合入口，对应 Java `tryMatchInstantly(activeOrder,
    /// subtreeForMatching = 完整的 askBuckets/bidBuckets, triggerCmd)` 这一用法——
    /// Java `newOrderMatchFokBudget` 里传入的 `subtreeForMatching` 就是未经 `headMap` 裁剪的
    /// 完整 `askBuckets`/`bidBuckets`，本质上仍是同一个 `tryMatchInstantly`，只是不加价位限制；
    /// 这里拆成独立函数 `try_match_full`，是因为 `match_against` 需要一个显式的
    /// `Option<taker_price_limit>` 参数区分"有上限"与"无上限"两种遍历范围。
    /// 仅在 FOK_BUDGET 校验通过后调用（见 `new_order_match_fok_budget`）。
    fn try_match_full(
        &mut self,
        taker_action: OrderAction,
        taker_size: i64,
        taker_reserve_bid_price: i64,
        taker_prior_filled: i64,
        taker_prior_filled_notional: i64,
        cmd: &mut OrderCommand,
    ) -> (i64, i64) {
        match taker_action {
            OrderAction::Bid => Self::match_against(
                &mut self.ask_buckets,
                &mut self.id_index,
                None,
                taker_size,
                taker_action,
                taker_reserve_bid_price,
                true,
                taker_prior_filled,
                taker_prior_filled_notional,
                cmd,
            ),
            OrderAction::Ask => Self::match_against(
                &mut self.bid_buckets,
                &mut self.id_index,
                None,
                taker_size,
                taker_action,
                taker_reserve_bid_price,
                false,
                taker_prior_filled,
                taker_prior_filled_notional,
                cmd,
            ),
        }
    }

    /// `try_match_instantly`/`try_match_full` 的共享核心：按价位由优到劣遍历对手桶，逐桶调用
    /// `OrdersBucketNaive::match_forward` 撮合，并把每笔成交组装成 `MatcherTradeEvent` 串成链表
    /// 挂到 `cmd.matcher_event` 上。整体对应 Java `tryMatchInstantly` 的主循环体（第 250-313
    /// 行）：Java 依赖传入的 `matchingBuckets: SortedMap` 本身已经是按"最优价在前"排好序的
    /// 子视图（BID 撮合传入按升序排的 askBuckets 头部，ASK 撮合传入按降序排的 bidBuckets 头部），
    /// 直接 `for (bucket : matchingBuckets.values())` 顺序遍历；这里因为 `ask_buckets`/
    /// `bid_buckets` 统一按升序存储（见模块顶部注释），改用 `ascending` 参数显式选择
    /// `BTreeMap::range`/`.rev()` 的遍历方向，效果等价。
    ///
    /// Java 用 `OrdersBucketNaive.match(...)` 返回的 `MatcherResult`（含事件链头尾指针）做链表拼接；
    /// 这里改为闭包回调 `on_trade` 形式（见 `orders_bucket_naive.rs` 里 `match_forward`
    /// 的说明），本函数负责把每次回调收到的 `MakerFill` 组装成 `MatcherTradeEvent` 并倒序
    /// reverse 一次得到正确的正向链表顺序（成交发生顺序）。
    #[allow(clippy::too_many_arguments)]
    fn match_against(
        buckets: &mut BTreeMap<i64, OrdersBucketNaive>,
        id_index: &mut BTreeMap<i64, (OrderAction, i64, i64)>,
        taker_price_limit: Option<i64>,
        taker_size: i64,
        taker_action: OrderAction,
        taker_reserve_bid_price: i64,
        ascending: bool,
        taker_prior_filled: i64,
        taker_prior_filled_notional: i64,
        cmd: &mut OrderCommand,
    ) -> (i64, i64) {
        // 先收集一遍价位快照再逐个处理（而不是直接对 BTreeMap 做迭代中修改），
        // 原因与 orders_bucket_naive.rs 里 match_forward 的做法一致：避免同时遍历和
        // 修改（撮合后可能整桶清空）BTreeMap 导致的借用冲突。
        let prices: Vec<i64> = match (ascending, taker_price_limit) {
            (true, Some(limit)) => buckets.range(..=limit).map(|(p, _)| *p).collect(),
            (true, None) => buckets.keys().copied().collect(),
            (false, Some(limit)) => buckets.range(limit..).rev().map(|(p, _)| *p).collect(),
            (false, None) => buckets.keys().rev().copied().collect(),
        };

        let mut filled: i64 = 0;
        let mut taker_filled: i64 = 0;
        let mut taker_filled_notional: i64 = 0;
        let mut events: Vec<MatcherTradeEvent> = Vec::new();
        let mut emptied: Vec<i64> = Vec::new();

        for p in prices {
            if filled == taker_size {
                break;
            }
            let size_left = taker_size - filled;
            let bucket = buckets.get_mut(&p).expect("bucket must exist for collected price");

            let mut remaining_in_call = size_left;
            bucket.match_forward(size_left, &mut |f: MakerFill| {
                remaining_in_call -= f.trade;
                let active_order_completed = remaining_in_call == 0;
                taker_filled += f.trade;
                taker_filled_notional = add_exact(taker_filled_notional, mul_exact(f.trade, p));
                // 与 Java OrderBookEventsHelper.sendTradeEvent 调用处保持一致的 bidderHoldPrice
                // 选择规则：若 taker 是 BID 方，用 taker 自己的预留价（因为 BID 方的资金已按
                // 预留价冻结，事件要能据此正确释放冻结）；若 taker 是 ASK 方，maker 才是 BID
                // 方，此时用 maker（f）的预留价。
                let bidder_hold_price = if taker_action == OrderAction::Bid {
                    taker_reserve_bid_price
                } else {
                    f.reserve_bid_price
                };
                // 字段对应 Java OrderBookEventsHelper.sendTradeEvent 里对 MatcherTradeEvent 各字段
                // 的赋值：matched_order_* 系列取自 maker（f，即 matchingOrder），price 取成交价 p
                // （即 maker 挂单价，因为撮合永远以 maker 的挂单价成交），filled/filled_notional
                // 是 taker 累计已成交量/名义金额。
                events.push(MatcherTradeEvent {
                    event_type: MatcherEventType::Trade,
                    active_order_completed,
                    maker_order_id: f.order_id,
                    maker_order_completed: f.completed,
                    price: p,
                    size: f.trade,
                    bid_gt_ask: taker_action == OrderAction::Bid,
                    bidder_hold_price,
                    matched_order_uid: f.uid,
                    matched_order_command_type: f.command,
                    filled: add_exact(taker_prior_filled, taker_filled),
                    filled_notional: add_exact(taker_prior_filled_notional, taker_filled_notional),
                    matched_order_size: f.size,
                    matched_order_price: f.price,
                    matched_order_type: f.order_type,
                    matched_order_timestamp: f.timestamp,
                    matched_user_cookie: f.user_cookie,
                    matched_order_filled: f.filled,
                    matched_order_filled_notional: f.filled_notional,
                    next: None,
                });
                if f.completed {
                    // maker 完全成交，从 id_index 摘除；对应 Java
                    // `bucketMatchings.ordersToRemove.forEach(idMap::remove)`。
                    id_index.remove(&f.order_id);
                }
            });

            filled += size_left - remaining_in_call;

            if bucket.is_empty() {
                emptied.push(p);
            }
        }

        // 撮合后清空的价位桶整体移除；对应 Java `emptyBuckets.forEach(matchingBuckets::remove)`。
        for p in emptied {
            buckets.remove(&p);
        }

        // 按成交发生的先后顺序（events 是顺序 push 的）把事件正向串成链表挂到 cmd 上；
        // 对应 Java 在遍历各桶时用 eventsTail 尾插法拼接 bucketMatchings.eventsChainHead/Tail
        // 得到的同一条链（此处闭包收集为 Vec 后一次性反向重建，效果等价）。
        let mut chain: Option<Box<MatcherTradeEvent>> = None;
        for mut ev in events.into_iter().rev() {
            ev.next = chain.take();
            chain = Some(Box::new(ev));
        }
        cmd.matcher_event = chain;

        (filled, taker_filled_notional)
    }

    /// IOC_BUDGET 专用撮合，对应 Java `tryMatchInstantlyWithBudget`：与 `match_against` 结构
    /// 相同，区别是遍历对手桶（固定按升序，即从最优价开始吃，`buckets` 参数恒为
    /// `ask_buckets`——IOC_BUDGET 只支持 BID，见 `new_order_match_ioc_budget`）时，
    /// 每档可购量额外受 `remaining_budget / bucket_price`（向下取整）约束，预算耗尽即停；
    /// 未成交剩余量由调用方走 reject 事件（见 Java 注释："budget 不足以再吃一个最小成交单位"）。
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
        let mut taker_filled: i64 = 0;
        let mut taker_filled_notional: i64 = 0;
        let mut events: Vec<MatcherTradeEvent> = Vec::new();
        let mut emptied: Vec<i64> = Vec::new();

        let taker_reserve_bid_price = cmd.reserve_bid_price;

        for p in prices {
            if filled == taker_size {
                break;
            }
            let size_left = taker_size - filled;
            // 本档可购量上限 = remaining_budget / p（向下取整），p == 0 时不设约束以防除零；
            // 对应 Java tryMatchInstantlyWithBudget 中 affordableAtBucket 的计算。
            let affordable = if p == 0 { i64::MAX } else { remaining_budget / p };
            let size_cap = size_left.min(affordable);
            if size_cap <= 0 {
                // 预算已不足以再吃一个最小成交单位，提前结束（对应 Java 同处 break 逻辑）。
                break;
            }
            let bucket = buckets.get_mut(&p).expect("bucket must exist for collected price");

            let mut remaining_in_call = size_cap;
            bucket.match_forward(size_cap, &mut |f: MakerFill| {
                remaining_in_call -= f.trade;
                taker_filled += f.trade;
                let active_order_completed = taker_filled == taker_size;
                taker_filled_notional = add_exact(taker_filled_notional, mul_exact(f.trade, p));
                let bidder_hold_price = if taker_action == OrderAction::Bid {
                    taker_reserve_bid_price
                } else {
                    f.reserve_bid_price
                };
                events.push(MatcherTradeEvent {
                    event_type: MatcherEventType::Trade,
                    active_order_completed,
                    maker_order_id: f.order_id,
                    maker_order_completed: f.completed,
                    price: p,
                    size: f.trade,
                    bid_gt_ask: taker_action == OrderAction::Bid,
                    bidder_hold_price,
                    matched_order_uid: f.uid,
                    matched_order_command_type: f.command,
                    filled: taker_filled,
                    filled_notional: taker_filled_notional,
                    matched_order_size: f.size,
                    matched_order_price: f.price,
                    matched_order_type: f.order_type,
                    matched_order_timestamp: f.timestamp,
                    matched_user_cookie: f.user_cookie,
                    matched_order_filled: f.filled,
                    matched_order_filled_notional: f.filled_notional,
                    next: None,
                });
                remaining_budget = sub_exact(remaining_budget, mul_exact(f.trade, p));
                if f.completed {
                    id_index.remove(&f.order_id);
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

    /// 对应 Java `newOrderMatchIoc`：即时撮合，未成交剩余部分不入簿，直接走 reject（不像
    /// GTC 那样挂单等待）。
    fn new_order_match_ioc(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("IOC order requires action");
        let price = cmd.price;
        let size = cmd.size;

        let (filled, _) = self.try_match_instantly(action, price, size, cmd.reserve_bid_price, 0, 0, cmd);
        let rejected_size = size - filled;
        if rejected_size != 0 {
            Self::attach_reject_event(cmd, rejected_size);
        }
    }

    /// 对应 Java `newOrderMatchIocBudget`：`cmd.price` 语义变为"总预算"而非限价，仅支持 BID
    /// （用预算买入；ASK 方向语义模糊——"最低收入约束下部分成交"没有良定义，Java 同样只允许
    /// BID，非 BID 直接整单 reject，见 Java 源码 114-118 行注释）。
    fn new_order_match_ioc_budget(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("IOC_BUDGET order requires action");
        if action != OrderAction::Bid {
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

    /// 全部成交或全部拒绝（Fill-Or-Kill，按限价而非预算）：先用 `available_volume_for_match`
    /// 统计限价内可用对手量，够则整单撮合，不够则整单 reject（不产生任何部分成交）。
    /// 注意：Java `OrderBookNaiveImpl.newOrder` 的 switch 语句里**没有** `FOK`（普通限价 FOK）
    /// 分支，只有 `FOK_BUDGET`——普通 FOK 落到 default 分支，走 `MATCHING_UNSUPPORTED_COMMAND`
    /// 路径（源码里留了一行 `// TODO FOK support`）。这里的 `new_order_match_fok` 是 Rust 侧
    /// 补上的真实实现，并非对某个已有 Java 方法的翻译，调用方式上与 `new_order_match_fok_budget`
    /// 平行（价格维度 vs 预算维度的 FOK），但在 Java naive 参照实现里没有对应方法。
    fn new_order_match_fok(&mut self, cmd: &mut OrderCommand) {
        let action = cmd.action.expect("FOK order requires action");
        let price = cmd.price;
        let size = cmd.size;

        let available = self.available_volume_for_match(action, price);
        if available >= size {
            self.try_match_instantly(action, price, size, cmd.reserve_bid_price, 0, 0, cmd);
        } else {
            Self::attach_reject_event(cmd, size);
        }
    }

    /// 对应 Java `newOrderMatchFokBudget`：先用 `check_budget_to_fill` 扫描对手盘算出吃满
    /// `size` 所需的总名义金额，再用 `is_budget_limit_satisfied` 判断是否满足 `cmd.price`
    /// 给出的预算/收入限制，满足才调用 `try_match_full` 吃穿撮合，否则整单 reject。
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
                self.try_match_full(action, size, cmd.reserve_bid_price, 0, 0, cmd);
            }
            _ => Self::attach_reject_event(cmd, size),
        }
    }

    /// 对应 Java `getBucketsByAction`（可变借用版本）：某订单要放进哪一侧的桶集合，
    /// ASK 单放 ask_buckets，BID 单放 bid_buckets。
    fn buckets_by_action_mut(&mut self, action: OrderAction) -> &mut BTreeMap<i64, OrdersBucketNaive> {
        match action {
            OrderAction::Ask => &mut self.ask_buckets,
            OrderAction::Bid => &mut self.bid_buckets,
        }
    }

    /// 对应 Java `getBucketsByAction`（只读借用版本，Java 侧同一个方法身兼两用，
    /// Rust 因借用检查器需要分成可变/不可变两个版本）。
    fn buckets_by_action(&self, action: OrderAction) -> &BTreeMap<i64, OrdersBucketNaive> {
        match action {
            OrderAction::Ask => &self.ask_buckets,
            OrderAction::Bid => &self.bid_buckets,
        }
    }

    /// 构造一个 REJECT 事件并挂到 `cmd.matcher_event` 链表头部（保留之前已产生的成交事件，
    /// reject 事件排在这些成交事件之后，即最新发生的事件在链表头）。对应 Java
    /// `OrderBookEventsHelper.attachRejectEvent`：`event.nextEvent = cmd.matcherEvent;
    /// cmd.matcherEvent = event`，同样是头插法；这里没有 Java 那边的对象池
    /// （`eventsHelper.newMatcherEvent()` 池化复用逻辑），直接 `Box::new` 分配。
    fn attach_reject_event(cmd: &mut OrderCommand, rejected_size: i64) {
        let event = MatcherTradeEvent {
            event_type: MatcherEventType::Reject,
            active_order_completed: true,
            price: cmd.price,
            size: rejected_size,
            bidder_hold_price: cmd.reserve_bid_price,
            next: cmd.matcher_event.take(),
            ..Default::default()
        };
        cmd.matcher_event = Some(Box::new(event));
    }

    /// 统计限价 `taker_price` 内对手盘的可撮合总量，供 `new_order_match_fok`（普通限价 FOK）
    /// 判断"是否够吃满整单"用。Java naive 参照实现没有普通 FOK、因而也没有此方法的直接对应；
    /// 结构上类似 Java `checkBudgetToFill` 的对手盘扫描思路，但这里统计的是数量（volume）
    /// 而不是名义金额（budget/notional）。
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

    /// 对应 Java `checkBudgetToFill`：沿对手盘价位由优到劣累加，直到累计量达到 `size`，
    /// 返回吃满 `size` 所需的总名义金额；若对手盘总量不足以吃满 `size` 则返回 `None`
    /// （对应 Java `Optional.empty()`）。调用方按调用方向传入不同的价位迭代器
    /// （ASK 方向传 bid_buckets 降序，BID 方向传 ask_buckets 升序，见
    /// `new_order_match_fok_budget` 两个分支）。
    fn check_budget_to_fill(iter: impl Iterator<Item = (i64, i64)>, mut size: i64) -> Option<i64> {
        let mut budget: i64 = 0;
        for (price, available_size) in iter {
            if size > available_size {
                size -= available_size;
                budget = add_exact(budget, mul_exact(available_size, price));
            } else {
                return Some(add_exact(budget, mul_exact(size, price)));
            }
        }
        None
    }

    /// 对应 Java `isBudgetLimitSatisfied`：`calculated == limit`（预算刚好用完/收入刚好达标）
    /// 总是满足；否则 BID 方向要求 `calculated <= limit`（实际花费不超预算），ASK 方向要求
    /// `calculated >= limit`（实际收入不低于预期），用 `!=`（对应 Java 的 `^` 异或）把这两种
    /// 方向相反的比较统一成一个布尔表达式。
    fn is_budget_limit_satisfied(action: OrderAction, calculated: i64, limit: i64) -> bool {
        calculated == limit || ((action == OrderAction::Bid) != (calculated > limit))
    }
}

impl Default for OrderBookNaiveImpl {
    fn default() -> Self {
        Self::new()
    }
}

impl IOrderBook for OrderBookNaiveImpl {
    /// 对应 Java `newOrder`：按 `orderType` 分派到具体处理函数。Java 的 switch 只覆盖
    /// GTC/IOC/FOK_BUDGET/IOC_BUDGET 四种类型，其余（包括普通 FOK）落到 default 分支，
    /// 记一条 warn 日志并调用 `eventsHelper.attachRejectEvent` 生成拒绝事件；这里的
    /// `Some(OrderType::Fok)` 分支是 Rust 侧新增的真实撮合实现（见 `new_order_match_fok`
    /// 注释），`None` 分支（订单类型缺失）则只设置 `MatchingUnsupportedCommand` 结果码、
    /// 不额外生成 reject 事件——与 Java default 分支相比少了 `attachRejectEvent` 这一步，
    /// 是两者在"不支持的命令"处理上的一个实际差异，而非翻译遗漏。
    fn new_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode {
        match cmd.order_type {
            Some(OrderType::Gtc) => self.new_order_place_gtc(cmd),
            Some(OrderType::Ioc) => self.new_order_match_ioc(cmd),
            Some(OrderType::IocBudget) => self.new_order_match_ioc_budget(cmd),
            Some(OrderType::Fok) => self.new_order_match_fok(cmd),
            Some(OrderType::FokBudget) => self.new_order_match_fok_budget(cmd),
            None => {
                cmd.result_code = Some(CommandResultCode::MatchingUnsupportedCommand);
                return CommandResultCode::MatchingUnsupportedCommand;
            }
        }
        cmd.result_code = Some(CommandResultCode::Success);
        CommandResultCode::Success
    }

    /// 对应 Java `cancelOrder`：按 order_id 查 id_index 定位订单，校验 uid 归属后
    /// 从桶里整单移除（桶清空则连价位一并移除），生成一条 `Reduce` 事件（size = 剩余未成交量，
    /// `active_order_completed = true` 表示订单彻底终止）。取不到桶或桶里取不到订单被视为
    /// 内部不变式被破坏（`id_index` 与桶数据不同步），对应 Java 里
    /// `if (ordersBucket == null) throw new IllegalStateException(...)` 的"不可能状态"防御。
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
            price: order.price,
            size: remaining,
            filled: order.filled,
            filled_notional: order.filled_notional,
            bidder_hold_price: order.reserve_bid_price,
            ..Default::default()
        }));
        cmd.action = Some(order.action);

        CommandResultCode::Success
    }

    /// 对应 Java `reduceOrder`：把某挂单的剩余量减少 `cmd.size`（不能减到负数，实际减少量
    /// 取 `min(requested, remaining)`）；若减少量等于全部剩余量则等价于整单撤销（从桶和
    /// id_index 中彻底移除），否则只原地缩小订单的 size 并同步扣减桶的 total_volume。
    /// 请求的 `size <= 0` 直接返回 `MatchingReduceFailedWrongSize`（对应 Java 同名结果码分支）。
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
            price: order.price,
            size: reduce_by,
            filled: order.filled,
            filled_notional: order.filled_notional,
            bidder_hold_price: order.reserve_bid_price,
            ..Default::default()
        }));
        cmd.action = Some(order.action);

        CommandResultCode::Success
    }

    /// 对应 Java `moveOrder`：把挂单改价到 `new_price`，先取出旧价位的订单，再以新价为限价
    /// 重新尝试即时撮合（改价后订单若变得可成交，会像新下单一样立即吃对手盘），未完全成交的
    /// 剩余部分挂回新价位对应的桶。若改价后完全成交则从 id_index 移除。
    ///
    /// 对于 `CURRENCY_EXCHANGE_PAIR` 品种的 BID 单有一条额外风控：新价不能超过下单时锁定的
    /// `reserve_bid_price`（预留价），否则拒绝改价（`MatchingMoveFailedPriceOverRiskLimit`）
    /// 且订单原封不动留在旧价位。对应 Java 源码 495 行
    /// `if (symbolSpec.type == CURRENCY_EXCHANGE_PAIR && order.action == BID && cmd.price >
    /// order.reserveBidPrice) return MATCHING_MOVE_FAILED_PRICE_OVER_RISK_LIMIT;`。
    ///
    /// 注意 Rust 这里对 `cmd.action` 的回填时机：在做风控判断**之前**就先 `cmd.action =
    /// Some(action)`，这与 Java 源码的实际顺序一致（Java 491 行先 `cmd.action =
    /// order.getAction()`，494 行才做预留价风控判断），也就是说即使这条 guard 触发拒绝，
    /// `cmd.action` 也已经被写回（测试 `move_bid_over_reserve_price_rejected_on_exchange_pair_spec`
    /// 专门验证了这一点，并注明这与 `OrderBookDirectImpl` 的行为不同）。
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

        cmd.action = Some(action);

        if let Some(spec) = &self.symbol_spec {
            if spec.symbol_type == SymbolType::CurrencyExchangePair && action == OrderAction::Bid {
                let reserve = self
                    .buckets_by_action(action)
                    .get(&old_price)
                    .and_then(|b| b.get(order_id))
                    .map(|o| o.reserve_bid_price);
                if let Some(reserve_bid_price) = reserve {
                    if new_price > reserve_bid_price {
                        return CommandResultCode::MatchingMoveFailedPriceOverRiskLimit;
                    }
                }
            }
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

        let remaining = order.size - order.filled;
        let (matched_now, matched_notional_now) =
            self.try_match_instantly(action, new_price, remaining, order.reserve_bid_price, order.filled, order.filled_notional, cmd);
        let total_filled = order.filled + matched_now;

        if total_filled == order.size {
            self.id_index.remove(&order_id);
            return CommandResultCode::Success;
        }

        order.filled = total_filled;
        order.filled_notional += matched_notional_now;
        self.buckets_by_action_mut(action)
            .entry(new_price)
            .or_insert_with(|| OrdersBucketNaive::new(new_price))
            .put(order);
        self.id_index.insert(order_id, (action, new_price, uid));

        CommandResultCode::Success
    }

    /// 生成 L2 深度快照（每侧最多 `size` 档），把 Java 里 `fillAsks`/`fillBids` 两个分别
    /// 填充 `L2MarketData` 的方法合并成一个直接返回 `L2MarketData` 的函数。`size < 0` 时
    /// 取 `usize::MAX`（不限档数），对应 Java `IOrderBook` 静态处理入口里
    /// `size >= 0 ? size : Integer.MAX_VALUE` 的归一化（这段归一化在 Java 源码里位于
    /// `IOrderBook.processCommand` 处理 `ORDER_BOOK_REQUEST` 命令时，而不在
    /// `OrderBookNaiveImpl.fillAsks`/`fillBids` 内部），这里把该逻辑内联进了 `fill_l2` 本身。
    /// ask 侧按价位升序取（最优卖价在前，对应 Java `askBuckets` 天然升序），
    /// bid 侧对内部升序存储的 `bid_buckets` 用 `.rev()` 取（最优买价在前，对应 Java
    /// `bidBuckets` 用反向比较器构造出的降序遍历顺序）。
    fn fill_l2(&self, size: i32) -> L2MarketData {
        let take: usize = match size {
            0 => 0,
            s if s < 0 => usize::MAX,
            s => s as usize,
        };

        let mut ask_prices = Vec::new();
        let mut ask_volumes = Vec::new();
        let mut ask_orders = Vec::new();
        for (price, bucket) in self.ask_buckets.iter() {
            if ask_prices.len() == take {
                break;
            }
            ask_prices.push(*price);
            ask_volumes.push(bucket.total_volume());
            ask_orders.push(bucket.num_orders() as i64);
        }

        let mut bid_prices = Vec::new();
        let mut bid_volumes = Vec::new();
        let mut bid_orders = Vec::new();
        for (price, bucket) in self.bid_buckets.iter().rev() {
            if bid_prices.len() == take {
                break;
            }
            bid_prices.push(*price);
            bid_volumes.push(bucket.total_volume());
            bid_orders.push(bucket.num_orders() as i64);
        }

        L2MarketData { ask_prices, ask_volumes, ask_orders, bid_prices, bid_volumes, bid_orders }
    }

    /// 计算订单簿的状态哈希，用途上对应 Java `IOrderBook` 接口的 `default int stateHash()`
    /// 方法（`IOrderBook.java` 121-135 行：`Objects.hash(stateHashStream(askOrdersStream),
    /// stateHashStream(bidOrdersStream), symbolSpec.stateHash())`，逐单哈希用
    /// `Order.stateHash()`/`hashCode()`，字段覆盖 orderId、action、orderType、command、
    /// price、size、reserveBidPrice、filled、filledNotional、uid、userCookie 共 11 个字段）。
    ///
    /// **注意这不是按位兼容的移植**，只是结构上类似的哈希实现，具体差异：
    /// - 每单哈希（`order_hash`）只纳入 order_id/action/price/size/filled/reserve_bid_price/
    ///   uid 共 7 个字段，缺少 order_type、command、filled_notional、user_cookie；
    /// - Java 用 `Objects.hash` 把 ask 子哈希、bid 子哈希、symbolSpec 哈希三者组合成最终结果
    ///   （ask/bid 各自先用 `h = h*31 + item.stateHash()` 折叠成一个 int）；这里则是用同一个
    ///   `i64` 累加器 `h`，先遍历全部 ask 订单、再遍历全部 bid 订单连续累加
    ///   （`h = h.wrapping_mul(31).wrapping_add(order_hash(order))`），完全不并入 symbol_spec
    ///   的哈希，最后用 `(h >> 32) ^ h` 把 i64 折叠成 i32。
    /// - 因此两侧的 state_hash 数值不能跨语言直接比较，仅要求 Rust 自身在相同操作序列下
    ///   保持确定性（见测试 `state_hash_deterministic_for_same_operation_sequence`）。
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
        for bucket in self.ask_buckets.values() {
            for order in bucket.iter_orders() {
                h = h.wrapping_mul(31).wrapping_add(order_hash(order));
            }
        }
        for bucket in self.bid_buckets.values().rev() {
            for order in bucket.iter_orders() {
                h = h.wrapping_mul(31).wrapping_add(order_hash(order));
            }
        }
        ((h >> 32) as i32) ^ (h as i32)
    }

    /// 对应 Java `findUserOrders`：扫描全部 ask/bid 桶，收集 `uid` 匹配的订单。
    /// Rust 版在返回前额外按 `order_id` 排序（`out.sort_by_key`），使结果顺序确定；
    /// Java 版没有这一步显式排序，返回顺序取决于 `askBuckets`/`bidBuckets`（TreeMap，
    /// 按价位排序）及桶内 `LinkedHashMap`（按插入顺序）的天然遍历顺序，不保证按 order_id 排列。
    fn find_user_orders(&self, uid: i64) -> Vec<Order> {
        let mut out: Vec<Order> = self
            .ask_buckets
            .values()
            .chain(self.bid_buckets.values())
            .flat_map(|b| b.iter_orders())
            .filter(|o| o.uid == uid)
            .cloned()
            .collect();
        out.sort_by_key(|o| o.order_id);
        out
    }
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::{to_btree_i64, ChronicleMarshallable};

impl OrderBookNaiveImpl {
    /// 按"最优价在前"的顺序导出全部挂单（asks 升序、bids 降序），主要供测试/对拍代码直接
    /// 检视订单簿内容用；Java 没有直接对应的单一方法，等价于分别对
    /// `askOrdersStream(true)`/`bidOrdersStream(true)`（`IOrderBook` 接口方法）取值再收集成
    /// 列表。
    pub fn chronicle_orders(&self) -> (Vec<Order>, Vec<Order>) {
        let asks = self.ask_buckets.values().flat_map(|b| b.iter_orders().cloned()).collect();
        let bids = self.bid_buckets.values().rev().flat_map(|b| b.iter_orders().cloned()).collect();
        (asks, bids)
    }
    /// 对应 Java `getSymbolSpec()`。
    pub fn chronicle_symbol_spec(&self) -> Option<CoreSymbolSpecification> {
        self.symbol_spec.clone()
    }
    /// 反序列化订单簿主体（symbol spec + 双向桶集合），对应 Java
    /// `OrderBookNaiveImpl(BytesIn bytes, LoggingConfiguration loggingCfg)` 构造函数
    /// （源码 70-83 行）：先读 `CoreSymbolSpecification`，再用
    /// `SerializationUtils.readLongMap` 分别读回 askBuckets/bidBuckets，最后重建
    /// order_id 索引。`to_btree_i64` 把线序读出的 (price, bucket) 序列还原成按 price 排序的
    /// `BTreeMap`（Java 侧直接用 TreeMap/反向 TreeMap 的 `SerializationUtils.readLongMap`
    /// 重建，效果等价）。"body" 之所以拆成独立函数，是因为它不消费/校验最前面的
    /// impl-type 字节——该字节的读取与校验放在下面 `ChronicleMarshallable::chronicle_read`
    /// 里（对应 Java 侧由 `IOrderBook.create` 统一读取分派字节，再调用具体实现类的构造函数）。
    pub fn chronicle_read_body(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let symbol_spec = CoreSymbolSpecification::chronicle_read(r)?;
        let ask_buckets = to_btree_i64(r.read_long_keyed_map(OrdersBucketNaive::chronicle_read)?);
        let bid_buckets = to_btree_i64(r.read_long_keyed_map(OrdersBucketNaive::chronicle_read)?);
        let mut book = OrderBookNaiveImpl { ask_buckets, bid_buckets, id_index: BTreeMap::new(), symbol_spec: Some(symbol_spec) };
        book.rebuild_id_index();
        Ok(book)
    }
    /// 从反序列化得到的桶集合重建 `id_index`。对应 Java 构造函数里的
    /// `askBuckets.values().forEach(bucket -> bucket.forEachOrder(order ->
    /// idMap.put(order.orderId, order)))`（bidBuckets 同理）；区别在于 Java 的 `idMap` 直接
    /// 存 `Order` 对象引用，这里的 `id_index` 只存 `(action, price, uid)` 三元组
    /// （见模块顶部关于 id_index 设计的说明）。
    fn rebuild_id_index(&mut self) {
        self.id_index.clear();
        for (&price, bucket) in &self.ask_buckets {
            for order in bucket.iter_orders() {
                self.id_index.insert(order.order_id, (OrderAction::Ask, price, order.uid));
            }
        }
        for (&price, bucket) in &self.bid_buckets {
            for order in bucket.iter_orders() {
                self.id_index.insert(order.order_id, (OrderAction::Bid, price, order.uid));
            }
        }
    }
}

impl ChronicleMarshallable for OrderBookNaiveImpl {
    /// 对应 Java `writeMarshallable(BytesOut bytes)`（源码 656-662 行）：
    /// 1. 先写一个字节的实现类型标签 —— Java 写 `getImplementationType().getCode()`，
    ///    即 `OrderBookImplType.NAIVE.code`；`IOrderBook.java` 里该枚举定义为
    ///    `NAIVE(0)`、`DIRECT(2)`（注意不是 0/1 连续编号，DIRECT 是 2），这里写死 `0`
    ///    与 Java 的 NAIVE 编码保持一致，供反序列化时区分是 Naive 还是 Direct 实现产出的快照。
    /// 2. 写 symbol spec（`spec.chronicle_write`，对应 `symbolSpec.writeMarshallable`）。
    /// 3. 写 ask_buckets：数量 + 逐个 (price, bucket) 对，按升序写出——与 Java `askBuckets`
    ///    本身的升序存储顺序一致，直接对应 `SerializationUtils.marshallLongMap(askBuckets, bytes)`。
    /// 4. 写 bid_buckets：这里显式用 `.iter().rev()` 把内部按升序存储的 `bid_buckets`
    ///    反转成降序（最优买价在前）再写出——因为 Java 的 `bidBuckets` 本身就用反向比较器
    ///    存成降序，`marshallLongMap` 对它的遍历顺序天然就是降序；Rust 这里必须主动 `.rev()`
    ///    才能产出与 Java 字节兼容的相同写出顺序（否则两侧对同一逻辑状态会写出不同顺序的
    ///    价位序列，虽然反序列化后重建的 BTreeMap 语义仍然正确，但不满足字节级兼容，
    ///    参见下方测试 `chronicle_naive_roundtrip_bytes` 对 write→read→write 幂等性的校验）。
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_u8(0);
        let spec = self.symbol_spec.as_ref().expect("naive order book missing symbol_spec");
        spec.chronicle_write(w);
        w.write_i32(self.ask_buckets.len() as i32);
        for (&price, bucket) in &self.ask_buckets {
            w.write_i64(price);
            bucket.chronicle_write(w, spec);
        }
        w.write_i32(self.bid_buckets.len() as i32);
        for (&price, bucket) in self.bid_buckets.iter().rev() {
            w.write_i64(price);
            bucket.chronicle_write(w, spec);
        }
    }
    /// 读取并校验实现类型标签、再委托 `chronicle_read_body` 读取主体。对应 Java
    /// `IOrderBook.create(BytesIn bytes, ...)`（源码 229-238 行）里
    /// `switch (OrderBookImplType.of(bytes.readByte()))` 对 `NAIVE` 分支的处理
    /// （`return new OrderBookNaiveImpl(bytes, loggingCfg)`）：Java 是在一个共享的顶层工厂
    /// 方法里读标签并分派到 NAIVE 或 DIRECT 两个具体实现类的构造函数；这里则是把"读标签 +
    /// 校验它确实是 NAIVE（值必须为 0，对应 `OrderBookImplType.NAIVE.code`）"折进了
    /// `OrderBookNaiveImpl` 自己的 `chronicle_read` 里，即调用方已经知道/约定要反序列化的是
    /// 一个 Naive 订单簿快照，读到非 0 标签视为格式错误直接 panic（Java 对应 `of(byte code)`
    /// 遇到未知编码时抛 `IllegalArgumentException`，这里对已知但不匹配的 DIRECT=2 编码，
    /// 或任何其他非法字节，同样以 panic 中止，而不是尝试兼容读取）。
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let impl_type = r.read_u8()?;
        assert_eq!(impl_type, 0, "not a Naive order book (code {impl_type}); expected OrderBookImplType.NAIVE=0");
        Self::chronicle_read_body(r)
    }
}

/// Rust 侧自建的补充测试（区别于下面 `ob_base_tests` 对应 Java 共享基类的移植测试），
/// 覆盖 Chronicle 序列化往返、各订单类型（GTC/IOC/IOC_BUDGET/FOK/FOK_BUDGET）的成交/拒绝
/// 分支、cancel/reduce/move 的各类结果码路径，以及本文件相对 Java 的新增/差异行为
/// （如 `state_hash` 确定性、FOK 的补充实现）。
#[cfg(test)]
mod ob_tests {
    use super::*;
    use crate::core::common::cmd::order_command_type::OrderCommandType;

    fn place(book: &mut OrderBookNaiveImpl, id: i64, act: OrderAction, price: i64, size: i64) -> OrderCommand {
        let mut cmd = OrderCommand { order_id: id, symbol: 1, price, size,
            action: Some(act), order_type: Some(OrderType::Gtc), uid: id, ..Default::default() };
        book.new_order(&mut cmd);
        cmd
    }

    #[test]
    fn chronicle_naive_roundtrip_bytes() {
        use crate::core::snapshot::chronicle_reader::ChronicleReader;
        use crate::core::snapshot::chronicle_writer::ChronicleWriter;
        let spec = CoreSymbolSpecification { symbol_id: 1, ..Default::default() };
        let mut book = OrderBookNaiveImpl::with_symbol_spec(spec);
        place(&mut book, 1, OrderAction::Ask, 110, 5);
        place(&mut book, 2, OrderAction::Ask, 110, 3);
        place(&mut book, 3, OrderAction::Ask, 120, 7);
        place(&mut book, 4, OrderAction::Bid, 100, 4);
        place(&mut book, 5, OrderAction::Bid, 90, 6);
        let mut w1 = ChronicleWriter::new();
        book.chronicle_write(&mut w1);
        let bytes1 = w1.into_bytes();
        let mut r = ChronicleReader::new(&bytes1);
        let back = OrderBookNaiveImpl::chronicle_read(&mut r).unwrap();
        assert!(r.is_empty(), "read did not consume all bytes");
        assert_eq!(back.fill_l2(100).ask_volumes, book.fill_l2(100).ask_volumes);
        assert_eq!(back.fill_l2(100).bid_volumes, book.fill_l2(100).bid_volumes);
        let mut w2 = ChronicleWriter::new();
        back.chronicle_write(&mut w2);
        assert_eq!(w2.into_bytes(), bytes1, "Naive write->read->write byte mismatch");
    }

    #[test]
    fn ioc_discards_remainder() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 5);
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 100, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Ioc), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
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
        let ev = cmd.matcher_event.as_ref().expect("expected a trade event");
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
        let ev = cmd.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, crate::core::common::matcher_event_type::MatcherEventType::Trade);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![4]);
    }

    #[test]
    fn ioc_budget_caps_by_notional_and_discards_rest() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 250, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::IocBudget), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        let head = cmd.matcher_event.as_ref().expect("expected an event chain");
        assert_eq!(head.event_type, crate::core::common::matcher_event_type::MatcherEventType::Reject);
        assert_eq!(head.size, 8);
        let trade = head.next.as_ref().expect("expected a trade event after the reject");
        assert_eq!(trade.event_type, crate::core::common::matcher_event_type::MatcherEventType::Trade);
        assert_eq!(trade.size, 2);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![8]);
    }

    #[test]
    fn fok_budget_rejects_when_budget_insufficient() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 500, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::FokBudget), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        let ev = cmd.matcher_event.as_ref().unwrap();
        assert_eq!(ev.event_type, crate::core::common::matcher_event_type::MatcherEventType::Reject);
        assert_eq!(book.fill_l2(10).ask_volumes, vec![10]);
    }

    #[test]
    fn fok_budget_matches_when_budget_sufficient() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 1000, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::FokBudget), uid: 2, ..Default::default() };
        book.new_order(&mut cmd);
        let ev = cmd.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, crate::core::common::matcher_event_type::MatcherEventType::Trade);
        assert_eq!(ev.size, 10);
        assert_eq!(book.fill_l2(10).ask_prices.len(), 0);
    }

    #[test]
    fn two_orders_cross_into_one_trade() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        let taker = place(&mut book, 2, OrderAction::Bid, 100, 6);
        let ev = taker.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, crate::core::common::matcher_event_type::MatcherEventType::Trade);
        assert_eq!(ev.maker_order_id, 1);
        assert_eq!(ev.price, 100);
        assert_eq!(ev.size, 6);
        assert!(ev.next.is_none());
        let l2 = book.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![100]);
        assert_eq!(l2.ask_volumes, vec![4]);
    }

    #[test]
    fn trade_event_matched_order_command_type_is_makers_command_not_takers() {
        let mut book = OrderBookNaiveImpl::new();
        let mut maker_cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1, symbol: 1, price: 100, size: 10,
            action: Some(OrderAction::Ask), order_type: Some(OrderType::Gtc), uid: 501,
            ..Default::default()
        };
        book.new_order(&mut maker_cmd);

        let mut taker_cmd = OrderCommand {
            command: OrderCommandType::ForceLiquidation,
            order_id: 2, symbol: 1, price: 100, size: 4,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Ioc), uid: 888,
            ..Default::default()
        };
        book.new_order(&mut taker_cmd);

        let ev = taker_cmd.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(
            ev.matched_order_command_type,
            OrderCommandType::PlaceOrder,
            "matched_order_command_type must take the maker's original command type, not the taker's ForceLiquidation"
        );
    }

    #[test]
    fn trade_event_matched_order_command_type_follows_maker_even_when_maker_is_force_liquidation() {
        let mut book = OrderBookNaiveImpl::new();
        let mut maker_cmd = OrderCommand {
            command: OrderCommandType::ForceLiquidation,
            order_id: 1, symbol: 1, price: 100, size: 10,
            action: Some(OrderAction::Ask), order_type: Some(OrderType::Gtc), uid: 501,
            ..Default::default()
        };
        book.new_order(&mut maker_cmd);

        let mut taker_cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 2, symbol: 1, price: 100, size: 4,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 888,
            ..Default::default()
        };
        book.new_order(&mut taker_cmd);

        let ev = taker_cmd.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.matched_order_command_type, OrderCommandType::ForceLiquidation);
    }

    #[test]
    fn trade_event_bidder_hold_price_when_maker_is_bid() {
        let mut book = OrderBookNaiveImpl::new();
        let mut maker_cmd = OrderCommand {
            order_id: 1, symbol: 1, price: 100, size: 10, reserve_bid_price: 12345,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 501,
            ..Default::default()
        };
        book.new_order(&mut maker_cmd);

        let mut taker_cmd = OrderCommand {
            order_id: 2, symbol: 1, price: 100, size: 4, reserve_bid_price: 999_999,
            action: Some(OrderAction::Ask), order_type: Some(OrderType::Gtc), uid: 777,
            ..Default::default()
        };
        book.new_order(&mut taker_cmd);

        let ev = taker_cmd.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.matched_order_uid, 501);
        assert_eq!(ev.bidder_hold_price, 12345);
    }

    #[test]
    fn trade_event_bidder_hold_price_when_taker_is_bid() {
        let mut book = OrderBookNaiveImpl::new();
        let mut maker_cmd = OrderCommand {
            order_id: 1, symbol: 1, price: 200, size: 10, reserve_bid_price: 999_999,
            action: Some(OrderAction::Ask), order_type: Some(OrderType::Gtc), uid: 502,
            ..Default::default()
        };
        book.new_order(&mut maker_cmd);

        let mut taker_cmd = OrderCommand {
            order_id: 2, symbol: 1, price: 200, size: 4, reserve_bid_price: 20000,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 888,
            ..Default::default()
        };
        book.new_order(&mut taker_cmd);

        let ev = taker_cmd.matcher_event.as_ref().expect("expected a trade event");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.matched_order_uid, 502);
        assert_eq!(ev.bidder_hold_price, 20000);
    }

    #[test]
    fn new_order_reports_result_code() {
        let mut book = OrderBookNaiveImpl::new();
        let mut cmd = OrderCommand { order_id: 1, symbol: 1, price: 100, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 1, ..Default::default() };
        let rc = book.new_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        assert_eq!(book.fill_l2(10).bid_volumes, vec![10]);

        let mut unsupported = OrderCommand { order_id: 2, symbol: 1, price: 100, size: 5,
            action: Some(OrderAction::Bid), order_type: None, uid: 2, ..Default::default() };
        let rc2 = book.new_order(&mut unsupported);
        assert_eq!(rc2, CommandResultCode::MatchingUnsupportedCommand);
        assert_eq!(unsupported.result_code, Some(CommandResultCode::MatchingUnsupportedCommand));
        assert_eq!(book.fill_l2(10).bid_volumes, vec![10]);
    }

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
        assert_eq!(l2.ask_prices, vec![100, 101, 102]);
    }

    #[test]
    fn cancel_removes_resting_order() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, uid: 1, ..Default::default() };
        let rc = book.cancel_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);

        let ev = cmd.matcher_event.as_ref().expect("expected a REDUCE event");
        assert_eq!(ev.event_type, MatcherEventType::Reduce);
        assert_eq!(ev.size, 10);
        assert!(ev.active_order_completed);
        assert!(ev.next.is_none());

        let l2 = book.fill_l2(10);
        assert!(l2.ask_prices.is_empty());

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

        let ev = cmd.matcher_event.as_ref().expect("expected a REDUCE event");
        assert_eq!(ev.event_type, MatcherEventType::Reduce);
        assert_eq!(ev.size, 4);
        assert!(!ev.active_order_completed);

        assert_eq!(book.fill_l2(10).ask_volumes, vec![6]);
    }

    #[test]
    fn reduce_beyond_remaining_removes_order_like_cancel() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, size: 100, uid: 1, ..Default::default() };
        let rc = book.reduce_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);

        let ev = cmd.matcher_event.as_ref().expect("expected a REDUCE event");
        assert_eq!(ev.size, 10);
        assert!(ev.active_order_completed);

        assert!(book.fill_l2(10).ask_prices.is_empty());
    }

    #[test]
    fn cancel_other_users_order_returns_unknown() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, uid: 999, ..Default::default() };
        assert_eq!(book.cancel_order(&mut cmd), CommandResultCode::MatchingUnknownOrderId);
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

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, price: 105, uid: 1, ..Default::default() };
        let rc = book.move_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);
        assert!(cmd.matcher_event.is_none());

        let l2 = book.fill_l2(10);
        assert!(l2.ask_prices.iter().all(|&p| p != 100));
        assert_eq!(l2.ask_prices, vec![105]);
        assert_eq!(l2.ask_volumes, vec![10]);
    }

    #[test]
    fn move_crosses_and_trades_immediately() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Bid, 90, 10);
        place(&mut book, 2, OrderAction::Ask, 100, 5);

        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 80, uid: 2, ..Default::default() };
        let rc = book.move_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::Success);

        let ev = cmd.matcher_event.as_ref().expect("expected an immediate trade after the move crosses");
        assert_eq!(ev.event_type, MatcherEventType::Trade);
        assert_eq!(ev.maker_order_id, 1);
        assert_eq!(ev.size, 5);

        assert!(book.fill_l2(10).ask_prices.is_empty());
        assert_eq!(book.fill_l2(10).bid_volumes, vec![5]);
    }

    fn exchange_pair_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: 1,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        }
    }

    #[test]
    fn move_bid_over_reserve_price_rejected_on_exchange_pair_spec() {
        let mut book = OrderBookNaiveImpl::with_symbol_spec(exchange_pair_spec());
        let mut place = OrderCommand {
            order_id: 1, symbol: 1, price: 90, size: 5, reserve_bid_price: 95,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 1, ..Default::default()
        };
        book.new_order(&mut place);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, price: 96, uid: 1, ..Default::default() };
        let rc = book.move_order(&mut cmd);
        assert_eq!(rc, CommandResultCode::MatchingMoveFailedPriceOverRiskLimit);
        assert_eq!(cmd.action, Some(OrderAction::Bid), "Java Naive backfills cmd.action before the guard check (unlike Direct)");
        assert!(cmd.matcher_event.is_none(), "the failure branch produces no event");
        let l2 = book.fill_l2(10);
        assert_eq!(l2.bid_prices, vec![90], "after rejection the order stays at the original price 90; FIFO/state unchanged");
        assert_eq!(l2.bid_volumes, vec![5]);

        let mut ok = OrderCommand { order_id: 1, symbol: 1, price: 95, uid: 1, ..Default::default() };
        assert_eq!(book.move_order(&mut ok), CommandResultCode::Success, "== reserve boundary is allowed");
        assert_eq!(book.fill_l2(10).bid_prices, vec![95]);
    }

    #[test]
    fn move_bid_guard_skipped_when_symbol_spec_absent() {
        let mut book = OrderBookNaiveImpl::new();
        let mut place = OrderCommand {
            order_id: 1, symbol: 1, price: 90, size: 5, reserve_bid_price: 95,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 1, ..Default::default()
        };
        book.new_order(&mut place);
        let mut cmd = OrderCommand { order_id: 1, symbol: 1, price: 200, uid: 1, ..Default::default() };
        assert_eq!(book.move_order(&mut cmd), CommandResultCode::Success, "no BID risk check when spec is absent");
    }

    #[test]
    fn move_fully_filled_order_is_removed_from_id_index() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Bid, 90, 5);
        place(&mut book, 2, OrderAction::Ask, 100, 5);

        let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 80, uid: 2, ..Default::default() };
        assert_eq!(book.move_order(&mut cmd), CommandResultCode::Success);

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

    #[test]
    fn duplicate_order_id_matches_then_rejects_remainder_and_does_not_place() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);
        place(&mut book, 2, OrderAction::Ask, 90, 6);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, price: 95, size: 10,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 99, ..Default::default() };
        book.new_order(&mut cmd);

        let head = cmd.matcher_event.as_ref().expect("expected an event chain: match first, then reject the remainder");
        assert_eq!(head.event_type, MatcherEventType::Reject);
        assert_eq!(head.size, 4);
        let trade = head.next.as_ref().expect("expected the earlier trade event after the reject");
        assert_eq!(trade.event_type, MatcherEventType::Trade);
        assert_eq!(trade.maker_order_id, 2);
        assert_eq!(trade.size, 6);

        let l2 = book.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![100]);
        assert_eq!(l2.ask_volumes, vec![10]);
        assert!(l2.bid_prices.is_empty());
    }

    #[test]
    fn duplicate_order_id_full_reject_when_no_match() {
        let mut book = OrderBookNaiveImpl::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10);

        let mut cmd = OrderCommand { order_id: 1, symbol: 1, price: 50, size: 7,
            action: Some(OrderAction::Bid), order_type: Some(OrderType::Gtc), uid: 99, ..Default::default() };
        book.new_order(&mut cmd);

        let ev = cmd.matcher_event.as_ref().expect("expected a reject event");
        assert_eq!(ev.event_type, MatcherEventType::Reject);
        assert_eq!(ev.size, 7);
        assert!(ev.next.is_none());

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

        let mut diff_price = OrderBookNaiveImpl::new();
        place(&mut diff_price, 1, OrderAction::Ask, 101, 10);
        assert_ne!(h1, diff_price.state_hash());

        let mut diff_size = OrderBookNaiveImpl::new();
        place(&mut diff_size, 1, OrderAction::Ask, 100, 11);
        assert_ne!(h1, diff_size.state_hash());

        let mut diff_extra = OrderBookNaiveImpl::new();
        place(&mut diff_extra, 1, OrderAction::Ask, 100, 10);
        place(&mut diff_extra, 2, OrderAction::Bid, 90, 3);
        assert_ne!(h1, diff_extra.state_hash());

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

/// 对应 Java 共享测试基类 `exchange.core2.core.orderbook.OrderBookBaseTest`（同一套用例
/// 被 `OrderBookNaiveImplTest` 和 `OrderBookDirectImplTest` 复用，分别针对两种实现跑一遍，
/// 用以保证两个实现在相同操作序列下行为一致）。这里把该基类中与 Naive 实现相关的用例
/// 逐条移植过来（`setup_book` 构造的初始订单簿快照、各价位/挂单数量与 Java 版一致），
/// 覆盖新增/撤销/改价/规约（reduce）在各种正常与异常（未知订单、非本人订单、非法 size 等）
/// 路径下的结果码、事件链、L2 快照变化。
#[cfg(test)]
mod ob_base_tests {
    use super::*;

    const UID_1: i64 = 412;
    const UID_2: i64 = 413;
    const INITIAL_PRICE: i64 = 81600;
    const MAX_PRICE: i64 = 400000;

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

    fn check_reject(ev: &MatcherTradeEvent, size: i64, price: i64) {
        assert_eq!(ev.event_type, MatcherEventType::Reject);
        assert_eq!(ev.size, size);
        assert_eq!(ev.price, price);
        assert!(ev.active_order_completed);
    }

    fn check_reduce(ev: &MatcherTradeEvent, reduce_size: i64, price: i64, completed: bool) {
        assert_eq!(ev.event_type, MatcherEventType::Reduce);
        assert_eq!(ev.size, reduce_size);
        assert_eq!(ev.price, price);
        assert_eq!(ev.active_order_completed, completed);
        assert!(ev.next.is_none());
    }

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

        fn assert_matches(&self, actual: &L2MarketData) {
            assert_eq!(actual.ask_prices, self.ask_prices);
            assert_eq!(actual.ask_volumes, self.ask_volumes);
            assert_eq!(actual.bid_prices, self.bid_prices);
            assert_eq!(actual.bid_volumes, self.bid_volumes);
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

        fn aggregate_buy_budget(&self, mut size: i64) -> i64 {
            let mut budget = 0i64;
            for i in 0..self.ask_prices.len() {
                let v = self.ask_volumes[i];
                let p = self.ask_prices[i];
                if v < size {
                    budget = add_exact(budget, mul_exact(v, p));
                    size -= v;
                } else {
                    return add_exact(budget, mul_exact(size, p));
                }
            }
            panic!("Can not collect size {size}");
        }

        fn aggregate_sell_expectation(&self, mut size: i64) -> i64 {
            let mut expectation = 0i64;
            for i in 0..self.bid_prices.len() {
                let v = self.bid_volumes[i];
                let p = self.bid_prices[i];
                if v < size {
                    expectation = add_exact(expectation, mul_exact(v, p));
                    size -= v;
                } else {
                    return add_exact(expectation, mul_exact(size, p));
                }
            }
            panic!("Can not collect size {size}");
        }
    }

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

        expected.assert_matches(&book.fill_l2(25));
        (book, expected)
    }

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

    #[test]
    fn should_initialize_without_errors() {
        let (mut book, expected) = setup_book();
        expected.assert_matches(&book.fill_l2(25));
        clear_order_book(&mut book);
    }

    #[test]
    fn fill_l2_reports_per_level_order_counts() {
        let (book, _) = setup_book();
        let l2 = book.fill_l2(25);
        assert_eq!(l2.ask_orders, vec![2, 1, 1, 2]);
        assert_eq!(l2.bid_orders, vec![1, 2, 1, 2, 1]);
    }

    #[test]
    fn should_add_gtc_orders() {
        let (mut book, mut expected) = setup_book();

        place_order(&mut book, OrderType::Gtc, 93, UID_1, 81598, 0, 1, OrderAction::Ask);
        expected.insert_ask(0, 81598, 1);

        place_order(&mut book, OrderType::Gtc, 94, UID_1, 81594, MAX_PRICE, 9_000_000_000, OrderAction::Bid);
        expected.insert_bid(0, 81594, 9_000_000_000);

        expected.assert_matches(&book.fill_l2(25));

        place_order(&mut book, OrderType::Gtc, 95, UID_1, 130000, 0, 13_000_000_000, OrderAction::Ask);
        expected.insert_ask(3, 130000, 13_000_000_000);

        place_order(&mut book, OrderType::Gtc, 96, UID_1, 1000, MAX_PRICE, 4, OrderAction::Bid);
        expected.insert_bid(6, 1000, 4);

        expected.assert_matches(&book.fill_l2(25));
        clear_order_book(&mut book);
    }

    #[test]
    fn should_ignored_duplicate_order() {
        let (mut book, expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 1, UID_1, 81600, 0, 100, OrderAction::Ask);

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reject(events[0], 100, 81600);

        expected.assert_matches(&book.fill_l2(25));
        clear_order_book(&mut book);
    }

    #[test]
    fn should_remove_bid_order() {
        let (mut book, mut expected) = setup_book();

        let (rc, cmd) = cancel_cmd(&mut book, 5, UID_1);
        assert_eq!(rc, CommandResultCode::Success);

        expected.set_bid_volume(1, 1);
        expected.assert_matches(&book.fill_l2(25));
        assert_eq!(cmd.action, Some(OrderAction::Bid));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reduce(events[0], 20, 81590, true);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_remove_ask_order() {
        let (mut book, mut expected) = setup_book();

        let (rc, cmd) = cancel_cmd(&mut book, 2, UID_1);
        assert_eq!(rc, CommandResultCode::Success);

        expected.set_ask_volume(0, 25);
        expected.assert_matches(&book.fill_l2(25));
        assert_eq!(cmd.action, Some(OrderAction::Ask));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reduce(events[0], 50, 81599, true);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_reduce_bid_order() {
        let (mut book, mut expected) = setup_book();

        let (rc, cmd) = reduce_cmd(&mut book, 5, UID_1, 3);
        assert_eq!(rc, CommandResultCode::Success);

        expected.decrement_bid_volume(1, 3);
        expected.assert_matches(&book.fill_l2(25));
        assert_eq!(cmd.action, Some(OrderAction::Bid));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reduce(events[0], 3, 81590, false);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_reduce_ask_order() {
        let (mut book, mut expected) = setup_book();

        let (rc, cmd) = reduce_cmd(&mut book, 1, UID_1, 300);
        assert_eq!(rc, CommandResultCode::Success);

        expected.remove_ask(1);
        expected.assert_matches(&book.fill_l2(25));
        assert_eq!(cmd.action, Some(OrderAction::Ask));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reduce(events[0], 100, 81600, true);

        clear_order_book(&mut book);
    }

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
        expected.assert_matches(&book.fill_l2(25));

        let events3 = events_list(&cmd3);
        assert_eq!(events3.len(), 1);
        check_reduce(events3[0], 25, 81599, true);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_return_error_when_deleting_unknown_order() {
        let (mut book, expected) = setup_book();
        let (rc, cmd) = cancel_cmd(&mut book, 5291, UID_1);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        expected.assert_matches(&book.fill_l2(25));
        assert_eq!(events_list(&cmd).len(), 0);
        clear_order_book(&mut book);
    }

    #[test]
    fn should_return_error_when_deleting_other_user_order() {
        let (mut book, expected) = setup_book();
        let (rc, cmd) = cancel_cmd(&mut book, 3, UID_2);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        assert!(cmd.matcher_event.is_none());
        expected.assert_matches(&book.fill_l2(25));
        clear_order_book(&mut book);
    }

    #[test]
    fn should_return_error_when_updating_other_user_order() {
        let (mut book, expected) = setup_book();

        let (rc, cmd) = move_cmd(&mut book, 2, UID_2, 100);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        assert!(cmd.matcher_event.is_none());

        let (rc2, cmd2) = move_cmd(&mut book, 8, UID_2, 100);
        assert_eq!(rc2, CommandResultCode::MatchingUnknownOrderId);
        assert!(cmd2.matcher_event.is_none());

        expected.assert_matches(&book.fill_l2(25));
        clear_order_book(&mut book);
    }

    #[test]
    fn should_return_error_when_updating_unknown_order() {
        let (mut book, expected) = setup_book();
        let (rc, cmd) = move_cmd(&mut book, 2433, UID_1, 300);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        expected.assert_matches(&book.fill_l2(10));
        assert_eq!(events_list(&cmd).len(), 0);
        clear_order_book(&mut book);
    }

    #[test]
    fn should_return_error_when_reducing_unknown_order() {
        let (mut book, expected) = setup_book();
        let (rc, cmd) = reduce_cmd(&mut book, 3, UID_2, 1);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        assert!(cmd.matcher_event.is_none());
        expected.assert_matches(&book.fill_l2(25));
        clear_order_book(&mut book);
    }

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

        expected.assert_matches(&book.fill_l2(25));
        clear_order_book(&mut book);
    }

    #[test]
    fn should_return_error_when_reducing_other_user_order() {
        let (mut book, expected) = setup_book();
        let (rc, cmd) = reduce_cmd(&mut book, 8, UID_2, 3);
        assert_eq!(rc, CommandResultCode::MatchingUnknownOrderId);
        assert!(cmd.matcher_event.is_none());
        expected.assert_matches(&book.fill_l2(25));
        clear_order_book(&mut book);
    }

    #[test]
    fn should_move_order_existing_bucket() {
        let (mut book, mut expected) = setup_book();
        let (rc, cmd) = move_cmd(&mut book, 7, UID_1, 81590);
        assert_eq!(rc, CommandResultCode::Success);

        expected.set_bid_volume(1, 41).remove_bid(2);
        expected.assert_matches(&book.fill_l2(10));
        assert_eq!(events_list(&cmd).len(), 0);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_move_order_new_bucket() {
        let (mut book, mut expected) = setup_book();
        let (rc, cmd) = move_cmd(&mut book, 7, UID_1, 81594);
        assert_eq!(rc, CommandResultCode::Success);

        expected.remove_bid(2).insert_bid(0, 81594, 20);
        expected.assert_matches(&book.fill_l2(10));
        assert_eq!(events_list(&cmd).len(), 0);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_ioc_order_partial_bbo() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Ioc, 123, UID_2, 1, 0, 10, OrderAction::Ask);

        expected.set_bid_volume(0, 30);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_trade(events[0], 4, 81593, 10);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_ioc_order_full_bbo() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Ioc, 123, UID_2, 1, 0, 40, OrderAction::Ask);

        expected.remove_bid(0);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_trade(events[0], 4, 81593, 40);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_ioc_order_with_two_limit_orders_partial() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Ioc, 123, UID_2, 1, 0, 41, OrderAction::Ask);

        expected.remove_bid(0).set_bid_volume(0, 20);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 2);
        check_trade(events[0], 4, 81593, 40);
        check_trade(events[1], 5, 81590, 1);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_ioc_order_full_liquidity_crosses_multiple_buckets() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Ioc, 123, UID_2, MAX_PRICE, MAX_PRICE, 175, OrderAction::Bid);

        expected.remove_ask(0).remove_ask(0);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 3);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 100);
        assert!(!events[0].active_order_completed);
        assert!(!events[1].active_order_completed);
        assert!(events[2].active_order_completed);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_ioc_order_with_rejection() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Ioc, 123, UID_2, MAX_PRICE, MAX_PRICE + 1, 270, OrderAction::Bid);

        expected.remove_all_asks();
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 7);
        check_reject(events[0], 25, MAX_PRICE);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_reject_fok_bid_order_out_of_budget() {
        let (mut book, expected) = setup_book();
        let size = 180i64;
        let buy_budget = expected.aggregate_buy_budget(size) - 1;
        assert_eq!(buy_budget, 81599 * 75 + 81600 * 100 + 200954 * 5 - 1);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reject(events[0], size, buy_budget);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_fok_bid_order_exact_budget_crosses_multiple_buckets() {
        let (mut book, mut expected) = setup_book();
        let size = 180i64;
        let buy_budget = expected.aggregate_buy_budget(size);
        assert_eq!(buy_budget, 81599 * 75 + 81600 * 100 + 200954 * 5);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        expected.remove_ask(0).remove_ask(0).set_ask_volume(0, 5);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 4);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 100);
        check_trade(events[3], 10, 200954, 5);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_fok_bid_order_extra_budget() {
        let (mut book, mut expected) = setup_book();
        let size = 176i64;
        let buy_budget = expected.aggregate_buy_budget(size) + 1;
        assert_eq!(buy_budget, 81599 * 75 + 81600 * 100 + 200954 + 1);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        expected.remove_ask(0).remove_ask(0).set_ask_volume(0, 9);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 4);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 100);
        check_trade(events[3], 10, 200954, 1);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_reject_fok_ask_order_below_expectation() {
        let (mut book, expected) = setup_book();
        let size = 60i64;
        let sell_expectation = expected.aggregate_sell_expectation(size) + 1;
        assert_eq!(sell_expectation, 81593 * 40 + 81590 * 20 + 1);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, sell_expectation, sell_expectation, size, OrderAction::Ask);

        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reject(events[0], size, sell_expectation);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_fok_ask_order_exact_expectation() {
        let (mut book, mut expected) = setup_book();
        let size = 60i64;
        let sell_expectation = expected.aggregate_sell_expectation(size);
        assert_eq!(sell_expectation, 81593 * 40 + 81590 * 20);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, sell_expectation, sell_expectation, size, OrderAction::Ask);

        expected.remove_bid(0).set_bid_volume(0, 1);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 2);
        check_trade(events[0], 4, 81593, 40);
        check_trade(events[1], 5, 81590, 20);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_match_fok_ask_order_extra_budget() {
        let (mut book, mut expected) = setup_book();
        let size = 61i64;
        let sell_expectation = expected.aggregate_sell_expectation(size) - 1;
        assert_eq!(sell_expectation, 81593 * 40 + 81590 * 21 - 1);

        let cmd = place_order(&mut book, OrderType::FokBudget, 123, UID_2, sell_expectation, sell_expectation, size, OrderAction::Ask);

        expected.remove_bid(0).remove_bid(0);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 3);
        check_trade(events[0], 4, 81593, 40);
        check_trade(events[1], 5, 81590, 20);
        check_trade(events[2], 6, 81590, 1);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_fully_match_ioc_budget_with_sufficient_budget() {
        let (mut book, mut expected) = setup_book();
        let size = 180i64;
        let buy_budget = expected.aggregate_buy_budget(size);

        let cmd = place_order(&mut book, OrderType::IocBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        expected.remove_ask(0).remove_ask(0).set_ask_volume(0, 5);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 4);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 100);
        check_trade(events[3], 10, 200954, 5);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_partially_match_ioc_budget_when_budget_runs_out() {
        let (mut book, mut expected) = setup_book();
        let size = 180i64;
        let buy_budget = 81599 * 75;

        let cmd = place_order(&mut book, OrderType::IocBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        expected.remove_ask(0);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 3);
        check_reject(events[0], 105, buy_budget);
        check_trade(events[1], 2, 81599, 50);
        check_trade(events[2], 3, 81599, 25);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_reject_ioc_budget_when_budget_too_small_for_one_unit() {
        let (mut book, expected) = setup_book();
        let size = 100i64;
        let buy_budget = 81598i64;

        let cmd = place_order(&mut book, OrderType::IocBudget, 123, UID_2, buy_budget, buy_budget, size, OrderAction::Bid);

        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reject(events[0], size, buy_budget);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_reject_ask_ioc_budget() {
        let (mut book, expected) = setup_book();
        let size = 50i64;
        let sell_expectation = 81593 * 40;

        let cmd = place_order(&mut book, OrderType::IocBudget, 123, UID_2, sell_expectation, sell_expectation, size, OrderAction::Ask);

        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_reject(events[0], size, sell_expectation);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_fully_match_marketable_gtc_order() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 123, UID_2, 81599, MAX_PRICE, 1, OrderAction::Bid);

        expected.set_ask_volume(0, 74);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 1);
        check_trade(events[0], 2, 81599, 1);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_partially_match_marketable_gtc_order_and_place() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 123, UID_2, 81599, MAX_PRICE, 77, OrderAction::Bid);

        expected.remove_ask(0).insert_bid(0, 81599, 2);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 2);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_fully_match_marketable_gtc_order_2_prices() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 123, UID_2, 81600, MAX_PRICE, 77, OrderAction::Bid);

        expected.remove_ask(0).set_ask_volume(0, 98);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 3);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 2);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_fully_match_marketable_gtc_order_with_all_liquidity_crosses_four_buckets() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 123, UID_2, 220000, MAX_PRICE, 1000, OrderAction::Bid);

        expected.remove_all_asks().insert_bid(0, 220000, 755);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd);
        assert_eq!(events.len(), 6);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 100);
        check_trade(events[3], 10, 200954, 10);
        check_trade(events[4], 8, 201000, 28);
        check_trade(events[5], 9, 201000, 32);
        for ev in &events {
            assert!(!ev.active_order_completed);
        }

        clear_order_book(&mut book);
    }

    #[test]
    fn should_move_order_fully_match_as_marketable() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 83, UID_2, 81200, MAX_PRICE, 20, OrderAction::Bid);
        assert_eq!(events_list(&cmd).len(), 0);

        expected.set_bid_volume(2, 40);
        expected.assert_matches(&book.fill_l2(10));

        let (rc, cmd2) = move_cmd(&mut book, 83, UID_2, 81602);
        assert_eq!(rc, CommandResultCode::Success);

        expected.set_bid_volume(2, 20).set_ask_volume(0, 55);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd2);
        assert_eq!(events.len(), 1);
        check_trade(events[0], 2, 81599, 20);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_move_order_fully_match_as_marketable_2_prices() {
        let (mut book, mut expected) = setup_book();
        let cmd = place_order(&mut book, OrderType::Gtc, 83, UID_2, 81594, MAX_PRICE, 100, OrderAction::Bid);
        assert_eq!(events_list(&cmd).len(), 0);

        let (rc, cmd2) = move_cmd(&mut book, 83, UID_2, 81600);
        assert_eq!(rc, CommandResultCode::Success);

        expected.remove_ask(0).set_ask_volume(0, 75);
        expected.assert_matches(&book.fill_l2(10));

        let events = events_list(&cmd2);
        assert_eq!(events.len(), 3);
        check_trade(events[0], 2, 81599, 50);
        check_trade(events[1], 3, 81599, 25);
        check_trade(events[2], 1, 81600, 25);

        clear_order_book(&mut book);
    }

    #[test]
    fn should_move_order_matches_all_liquidity_crosses_four_buckets() {
        let (mut book, mut expected) = setup_book();
        let _cmd = place_order(&mut book, OrderType::Gtc, 83, UID_2, 81594, MAX_PRICE, 246, OrderAction::Bid);

        let (rc, cmd2) = move_cmd(&mut book, 83, UID_2, 201000);
        assert_eq!(rc, CommandResultCode::Success);

        expected.remove_all_asks().insert_bid(0, 201000, 1);
        expected.assert_matches(&book.fill_l2(10));

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

}
