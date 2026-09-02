//! 对应 Java: exchange.core2.core.common.SymbolPositionRecord（Task 1 子集：字段 + 构造/初始化
//! + `is_empty`/`reset`/`state_hash`；保证金/PnL/开平原语见 §1 `:494-669`，本移植落 Task 2）。
//!
//! Java 字段里 `adlEligibility`/`pendingADLSize`/`liquidationFlow` 是 **leader-local**、纯内存、
//! 不持久化、不进 `stateHash` 的字段（强平扫描专用状态）。P4 移植时整体不落这三个字段（无消费者）。
//! **P6 Task 1**：引入 `pending_adl_size`/`adl_eligibility`（ADL Task 6 消费的纯标量非复制计数），
//! 二者**排除出 `state_hash`**（Ruling P6-E：非复制 scratch 状态，由 R1/R2 确定性重放维持一致，
//! 不参与 raft 状态校验）。`liquidation_flow` 是状态机对象（`Option<LiquidationFlow>`），随
//! FORCE→IF→ADL 状态机一起在 P6 Task 7 引入（其类型与状态机同处定义，避免过早搬入空壳）——同样
//! 非复制、不进 `state_hash`。
use std::collections::BTreeMap;

use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::order_action::OrderAction;
use crate::core::common::position_direction::PositionDirection;
use crate::core::utils::core_arithmetic_utils::{calculate_taker_fee, ceil_divide, ceil_mul_div, trunc_mul_div};

/// 对应 Java `Math.addExact(long, long)`：`i128` 中间精度相加后收窄回 `i64`，溢出 panic。
/// `CoreArithmeticUtils` 里的等价函数是私有的（Task 1 的零依赖 ruling），这里本地重复一份
/// 而非放开可见性——保持 arithmetic 层与 model 层的依赖边界不动。
fn add_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 + b as i128).unwrap_or_else(|_| panic!("overflow: {a} + {b}"))
}

/// 对应 Java `Math.subtractExact(long, long)`。
fn sub_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 - b as i128).unwrap_or_else(|_| panic!("overflow: {a} - {b}"))
}

/// 对应 Java `Math.multiplyExact(long, long)`。
fn mul_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 * b as i128).unwrap_or_else(|_| panic!("overflow: {a} * {b}"))
}

/// 对应 Java `SymbolPositionRecord`：期货 / 保证金交易的单 symbol、单方向持仓记录。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SymbolPositionRecord {
    pub uid: i64,
    pub symbol: i32,
    pub currency: i32,

    /// 持仓方向；`EMPTY` 表示当前无持仓（`open_volume==0` 时恒为 `Empty`）。
    pub direction: PositionDirection,
    /// 持仓量（baseScaleK，无符号，符号由 `direction` 带）。
    pub open_volume: i64,
    /// 当前开仓量锁定的初始保证金（sizePriceScale = baseScaleK×quoteScaleK）。
    pub open_init_margin_sum: i64,
    /// 持仓总成本（sizePriceScale）；均价 = `open_price_sum / open_volume`。
    pub open_price_sum: i64,
    /// 已实现盈亏累加器，仅在仓位清空时入账（累加进 accounts）。
    pub profit: i64,

    /// 挂单量（baseScaleK）：R1 发单前加，R2 成交/拒/减确认时减。
    pub pending_sell_size: i64,
    pub pending_buy_size: i64,
    /// 挂单侧加权均价，最坏敞口/费用估算用。
    pub pending_sell_avg_price: i64,
    pub pending_buy_avg_price: i64,

    /// 用户自选杠杆，`updateLeverage` 归一：0 -> 1。
    pub leverage: i32,
    /// 默认逐仓（`ISOLATED`）。
    pub margin_mode: MarginMode,
    /// 补充保证金（sizePriceScale），`MARGIN_ADJUSTMENT` 手动加，清空仓位时整额退。
    pub extra_margin: i64,

    // ================================================================
    // 非复制 leader-local scratch（Ruling P6-E）：不进 `state_hash`、不序列化。
    // ADL 一条命令内 R1 预留 / R2-finalize 对称释放的纯标量计数（参考 §3）。
    // ================================================================
    /// 对应 Java `SymbolPositionRecord.pendingADLSize`：ADL R1 `collect_input` 里对本仓预留的
    /// 待减仓量，`finalize_for_command` 对称释放。防同一命令的多 shard 候选选择重复减同一手量。
    pub pending_adl_size: i64,
    /// 对应 Java `SymbolPositionRecord.adlEligibility`：ADL 资格因子（ISOLATED 默认 100，CROSS
    /// 默认 0、过账户级安全门后写 clamp 因子）。`riskScore` 的乘子之一。
    pub adl_eligibility: i64,
}

impl SymbolPositionRecord {
    /// 对应 Java 池化复用前的手工构造场景：直接给定 `(uid, symbol, currency, margin_mode,
    /// leverage)`，其余字段取结构体默认零值（`direction=Empty`）。`leverage` 经
    /// [`Self::update_leverage`] 归一（`0` -> `1`）。**P6 Task 6 补齐**：`adl_eligibility` 按
    /// `margin_mode` 归一（`Isolated -> 100`，`Cross -> 0`），对应 Java `SymbolPositionRecord`
    /// 字段原始初始值 `adlEligibility = 100`（`Default` derive 给出的是 `0`，与 Java raw 字段
    /// 初始值不同，同 `leverage` 的 Ruling P4-B 一个道理——这个便捷构造函数是本移植新增的，不是
    /// Java 逐字对应的构造器，必须显式补上这条默认值规则，否则新建的 ISOLATED 仓位
    /// `adl_eligibility` 会停留在 Rust 零值 `0`，导致 `risk_score` 恒为 0、ADL 永远选不中它）。
    pub fn new(uid: i64, symbol: i32, currency: i32, margin_mode: MarginMode, leverage: i32) -> Self {
        let mut r = SymbolPositionRecord { uid, symbol, currency, margin_mode, ..Default::default() };
        r.update_leverage(leverage);
        r.adl_eligibility = if margin_mode == MarginMode::Isolated { 100 } else { 0 };
        r.pending_adl_size = 0;
        r
    }

    /// 对应 Java `initialize(long uid, int symbol, int currency, OrderAction orderAction,
    /// int leverage, MarginMode marginMode)`：池化复用入口，按开仓方向建立新记录（`direction`
    /// 由 `orderAction` 推导：`BID -> LONG`，否则 `SHORT`），持仓/成本/盈亏清零。
    pub fn initialize(
        &mut self,
        uid: i64,
        symbol: i32,
        currency: i32,
        order_action: OrderAction,
        leverage: i32,
        margin_mode: MarginMode,
    ) {
        self.uid = uid;
        self.symbol = symbol;
        self.currency = currency;

        self.direction = PositionDirection::of_action(order_action);
        self.open_volume = 0;
        self.open_init_margin_sum = 0;
        self.open_price_sum = 0;
        self.profit = 0;

        self.pending_sell_size = 0;
        self.pending_buy_size = 0;

        self.update_leverage(leverage);
        self.margin_mode = margin_mode;
        self.extra_margin = 0;
        // 对应 Java `initialize` (`:110-111`)：ADL 资格因子按 margin_mode 归一，pendingADLSize
        // 清零——池化复用入口，池复用前的旧值必须清干净（P6 Task 6 补齐，见 `Self::new` 文档）。
        self.adl_eligibility = if margin_mode == MarginMode::Isolated { 100 } else { 0 };
        self.pending_adl_size = 0;
    }

    /// 对应 Java `updateLeverage(int leverage)`：`0` 归一为 `1`（用户未选 = 默认 1 倍）。
    pub fn update_leverage(&mut self, leverage: i32) {
        self.leverage = if leverage == 0 { 1 } else { leverage };
    }

    /// 对应 Java `isSameLeverage(int leverage)`：按同一 `0 -> 1` 归一规则比较。
    pub fn is_same_leverage(&self, leverage: i32) -> bool {
        self.leverage == if leverage == 0 { 1 } else { leverage }
    }

    /// 对应 Java `isEmpty()`：无挂单、无持仓——拆记录（从 map 移除）的触发条件。
    pub fn is_empty(&self) -> bool {
        self.open_volume == 0 && self.pending_sell_size == 0 && self.pending_buy_size == 0
    }

    /// 对应 Java `reset()`：池复用清零（Rust 无对象池，保留方法以便调用方显式清空复用一条记录）。
    /// 与 `Default::default()` 的区别：`Default` 会把 `uid`/`symbol`/`currency` 也清零，
    /// 而 `reset()` 与 Java 一致——保留 identity 字段不动，只清业务状态。
    pub fn reset(&mut self) {
        self.pending_buy_size = 0;
        self.pending_sell_size = 0;
        self.pending_buy_avg_price = 0;
        self.pending_sell_avg_price = 0;

        self.open_volume = 0;
        self.open_init_margin_sum = 0;
        self.open_price_sum = 0;
        self.direction = PositionDirection::Empty;

        self.update_leverage(0);
        self.margin_mode = MarginMode::Isolated;
        self.extra_margin = 0;
        // 对应 Java `reset()` (`:706-709`)：无条件回落到 ISOLATED 默认值——`reset()` 之后紧跟
        // `initialize()` 才会按真实 margin_mode 重设，二者组合与 Java 池化复用序列一致
        // （P6 Task 6 补齐，见 `Self::new` 文档）。
        self.adl_eligibility = 100;
        self.pending_adl_size = 0;
    }

    /// 对应 Java `stateHash()`：`Objects.hash(symbol, currency, direction.getMultiplier(),
    /// openVolume, openInitMarginSum, openPriceSum, profit, pendingSellSize, pendingBuySize,
    /// pendingSellAvgPrice, pendingBuyAvgPrice, leverage, enumStateHash(marginMode),
    /// extraMargin)`——**不含 `uid`**（逐字对齐 Java，非遗漏）。风格对齐
    /// `UserProfile::state_hash`（`h=h*31+field` 滚动折叠）；不保证与 Java 数值相等，只保证
    /// 「同状态 -> 同 hash，不同状态 -> 不同 hash」。
    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        h = h.wrapping_mul(31).wrapping_add(self.symbol as i64);
        h = h.wrapping_mul(31).wrapping_add(self.currency as i64);
        h = h.wrapping_mul(31).wrapping_add(self.direction.multiplier() as i64);
        h = h.wrapping_mul(31).wrapping_add(self.open_volume);
        h = h.wrapping_mul(31).wrapping_add(self.open_init_margin_sum);
        h = h.wrapping_mul(31).wrapping_add(self.open_price_sum);
        h = h.wrapping_mul(31).wrapping_add(self.profit);
        h = h.wrapping_mul(31).wrapping_add(self.pending_sell_size);
        h = h.wrapping_mul(31).wrapping_add(self.pending_buy_size);
        h = h.wrapping_mul(31).wrapping_add(self.pending_sell_avg_price);
        h = h.wrapping_mul(31).wrapping_add(self.pending_buy_avg_price);
        h = h.wrapping_mul(31).wrapping_add(self.leverage as i64);
        h = h.wrapping_mul(31).wrapping_add(self.margin_mode.code() as i64);
        h = h.wrapping_mul(31).wrapping_add(self.extra_margin);
        ((h >> 32) as i32) ^ (h as i32)
    }

    /// 对应 Java `calculateBankruptcyPrice(CoreSymbolSpecification, ToLongFunction)`
    /// （`SymbolPositionRecord.java:295-312`）：破产价（= 权益恰好归零的清算限价）。
    /// `margin_base_fn` 对应 Java 的 `crossMarginBaseFn`——ISOLATED 分支不调它（直接
    /// `open_init_margin_sum + extra_margin`），CROSS 分支用它取账户级 marginBase（P4 已移植的
    /// `cross_margin_base_allocation` 之类，由清算引擎在 P6 Task 7 传入；单仓/测试可传 `|_| 0`）。
    /// 单位：三者(marginBase/openPriceSum/结果)同 sizePriceScale/quoteScaleK 对齐，见 Java 注释。
    /// 逐字对齐 Java：运算顺序、`*_exact`/`ceil_*` 取舍照抄，不重推。
    pub fn calculate_bankruptcy_price(
        &self,
        spec: &CoreSymbolSpecification,
        margin_base_fn: impl Fn(&SymbolPositionRecord) -> i64,
    ) -> i64 {
        let margin_base = match self.margin_mode {
            MarginMode::Isolated => add_exact(self.open_init_margin_sum, self.extra_margin),
            MarginMode::Cross => margin_base_fn(self),
        };
        let sign = self.direction.multiplier() as i64;
        let total_fee = add_exact(spec.taker_fee, spec.liquidation_fee);
        if spec.is_fixed_fee() {
            let max_loss = sub_exact(margin_base, mul_exact(total_fee, self.open_volume));
            let numer = sub_exact(self.open_price_sum, mul_exact(sign, max_loss));
            ceil_divide(numer, self.open_volume)
        } else {
            let numer = sub_exact(self.open_price_sum, mul_exact(sign, margin_base));
            let denom = mul_exact(self.open_volume, sub_exact(spec.fee_scale_k, mul_exact(sign, total_fee)));
            ceil_mul_div(numer, spec.fee_scale_k, denom)
        }
    }

    // ================================================================
    // pending 挂单占用 / 释放 —— 对应 Java `:160-226`
    // ================================================================

    /// 对应 Java `pendingHold(OrderAction, long size, long price)`（`:160-168`）：R1 发单前调，
    /// 累加挂单量并把该侧的挂单加权均价重算（ceil，保守估计）。
    pub fn pending_hold(&mut self, order_action: OrderAction, size: i64, price: i64) {
        match order_action {
            OrderAction::Ask => {
                self.pending_sell_avg_price =
                    Self::calculate_avg_price(self.pending_sell_avg_price, self.pending_sell_size, price, size);
                self.pending_sell_size = add_exact(self.pending_sell_size, size);
            }
            OrderAction::Bid => {
                self.pending_buy_avg_price =
                    Self::calculate_avg_price(self.pending_buy_avg_price, self.pending_buy_size, price, size);
                self.pending_buy_size = add_exact(self.pending_buy_size, size);
            }
        }
    }

    /// 对应 Java `pendingHoldBudget(OrderAction, long size, long budgetNotional)`（`:179-199`）：
    /// BUDGET 单专用——`cmd.price` 本身就是总预算 notional，无需再乘 size；直接把 budget 累进
    /// 该侧 pending notional 再 ceil 除回新 size 得新 avg（下游 `size × avg ≈ notional` 契约）。
    pub fn pending_hold_budget(&mut self, order_action: OrderAction, size: i64, budget_notional: i64) {
        match order_action {
            OrderAction::Ask => {
                let new_size = add_exact(self.pending_sell_size, size);
                if new_size <= 0 {
                    return;
                }
                let pending_notional = add_exact(
                    mul_exact(self.pending_sell_avg_price, self.pending_sell_size),
                    budget_notional,
                );
                self.pending_sell_avg_price = ceil_divide(pending_notional, new_size);
                self.pending_sell_size = new_size;
            }
            OrderAction::Bid => {
                let new_size = add_exact(self.pending_buy_size, size);
                if new_size <= 0 {
                    return;
                }
                let pending_notional = add_exact(
                    mul_exact(self.pending_buy_avg_price, self.pending_buy_size),
                    budget_notional,
                );
                self.pending_buy_avg_price = ceil_divide(pending_notional, new_size);
                self.pending_buy_size = new_size;
            }
        }
    }

    /// 对应 Java `calculateAvgPrice`（private helper，`:201-208`）：`currentSize` 与
    /// `newSize` 合计的加权均价，ceil 取整（保守）。合计量 ≤0 时返回 0（对应侧已清空）。
    fn calculate_avg_price(current_avg: i64, current_size: i64, new_price: i64, new_size: i64) -> i64 {
        let total_size = add_exact(current_size, new_size);
        if total_size <= 0 {
            return 0;
        }
        let total_notional = add_exact(mul_exact(current_avg, current_size), mul_exact(new_price, new_size));
        ceil_divide(total_notional, total_size)
    }

    /// 对应 Java `pendingRelease(OrderAction, long size)`（`:210-226`）：R2 成交/拒/减确认时调，
    /// 减该侧挂单量，返回实际释放量（`min(pending, size)`）；侧归零时重置该侧 avg 价。
    pub fn pending_release(&mut self, order_action: OrderAction, size: i64) -> i64 {
        match order_action {
            OrderAction::Ask => {
                let released = self.pending_sell_size.min(size);
                self.pending_sell_size -= released;
                if self.pending_sell_size == 0 {
                    self.pending_sell_avg_price = 0;
                }
                released
            }
            OrderAction::Bid => {
                let released = self.pending_buy_size.min(size);
                self.pending_buy_size -= released;
                if self.pending_buy_size == 0 {
                    self.pending_buy_avg_price = 0;
                }
                released
            }
        }
    }

    // ================================================================
    // 盈亏 / 保证金 —— 对应 Java `:233-245`, `:478-615`
    // ================================================================

    /// 对应 Java `estimatePnl(LastPriceCacheRecord)`（`:233-235`）：`profit`（已实现）+
    /// 未实现盈亏（以 `mark_price` 估价）。
    pub fn estimate_pnl(&self, mark_price: i64) -> i64 {
        add_exact(self.profit, self.estimate_unrealized_profit(mark_price))
    }

    /// 对应 Java `estimateUnrealizedProfit(LastPriceCacheRecord)`（`:240-245`）：
    /// `sign × (openVolume × mark − openPriceSum)`。LONG 取正号、SHORT 取反号、EMPTY 恒 0
    /// （`openVolume==0` 时 `direction` 必为 `Empty`，乘数 0 天然兜底）。
    pub fn estimate_unrealized_profit(&self, mark_price: i64) -> i64 {
        let notional = mul_exact(self.open_volume, mark_price);
        let delta = sub_exact(notional, self.open_price_sum);
        mul_exact(self.direction.multiplier() as i64, delta)
    }

    /// 对应 Java `calculateMaintenanceMargin(CoreSymbolSpecification, LastPriceCacheRecord)`
    /// （`:478-484`）：【强平风险评估用】只看 `openVolume × mark`，不看 pending 挂单。空仓
    /// 返回 0（避免 `spec.calculateMaintenanceMargin(0)` 在未配置分档表时按 100% 兜底成非零）。
    pub fn calculate_maintenance_margin(&self, spec: &CoreSymbolSpecification, mark_price: i64) -> i64 {
        if self.open_volume == 0 {
            return 0;
        }
        let notional = mul_exact(self.open_volume, mark_price);
        spec.calculate_maintenance_margin(notional)
    }

    /// 对应 Java `calculateRequiredMarginForFutures(CoreSymbolSpecification)`（`:494-496`）：
    /// 单参重载，杠杆取本仓 `self.leverage`。
    pub fn calculate_required_margin_for_futures(&self, spec: &CoreSymbolSpecification) -> i64 {
        self.calculate_required_margin_for_futures_with_leverage(spec, self.leverage)
    }

    /// 对应 Java `calculateRequiredMarginForFutures(CoreSymbolSpecification, int leverage)`
    /// （`:523-539`）：持仓 + 挂单需要锁定的总保证金。
    ///
    /// 数学：把仓位有向化，比较 BID 全成 vs ASK 全成两种极端下的敞口最大值——
    /// `worstCaseNotional = max(|open+bid|, |open−ask|)`，减去 `|open|` 得
    /// `newExposureNotional`（挂单只对"能扩大最坏敞口"的部分收保证金，纯减仓的反向挂单在
    /// `openVolume` 范围内成交只会释放保证金，不占额外保证金）。返回
    /// `openInitMarginSum + calculateInitMargin(newExposure, leverage) + max(bidFee, askFee)`。
    pub fn calculate_required_margin_for_futures_with_leverage(
        &self,
        spec: &CoreSymbolSpecification,
        leverage: i32,
    ) -> i64 {
        let open_notional = if self.open_volume == 0 {
            0
        } else {
            mul_exact(self.direction.multiplier() as i64, self.open_price_sum)
        };
        let bid_notional = mul_exact(self.pending_buy_size, self.pending_buy_avg_price);
        let ask_notional = mul_exact(self.pending_sell_size, self.pending_sell_avg_price);

        let worst_case_notional = add_exact(open_notional, bid_notional)
            .abs()
            .max(sub_exact(open_notional, ask_notional).abs());
        let new_exposure_notional = 0i64.max(sub_exact(worst_case_notional, open_notional.abs()));

        let bid_fee = calculate_taker_fee(self.pending_buy_size, self.pending_buy_avg_price, spec.taker_fee, spec.fee_scale_k);
        let ask_fee =
            calculate_taker_fee(self.pending_sell_size, self.pending_sell_avg_price, spec.taker_fee, spec.fee_scale_k);
        add_exact(
            add_exact(self.open_init_margin_sum, spec.calculate_init_margin(new_exposure_notional, leverage as i64)),
            bid_fee.max(ask_fee),
        )
    }

    /// 对应 Java `calculateRequiredMarginForOrder(CoreSymbolSpecification, OrderAction, long)`
    /// （`:548-569`）：把新单 `order_notional` 落在 `action` 侧后，仓位需要的总保证金——
    /// "有此单 vs 无此单"最坏敞口差。敞口口径与 [`Self::calculate_required_margin_for_futures_with_leverage`]
    /// 一致。新单不扩大最坏敞口（纯反向或抵消现有 pending）时返回 **-1 哨兵**，caller 回退到
    /// `calculate_required_margin_for_futures`。
    pub fn calculate_required_margin_for_order(
        &self,
        spec: &CoreSymbolSpecification,
        action: OrderAction,
        order_notional: i64,
    ) -> i64 {
        let open_notional = if self.open_volume == 0 {
            0
        } else {
            mul_exact(self.direction.multiplier() as i64, self.open_price_sum)
        };
        let abs_open_notional = open_notional.abs();
        let bid_notional = mul_exact(self.pending_buy_size, self.pending_buy_avg_price);
        let ask_notional = mul_exact(self.pending_sell_size, self.pending_sell_avg_price);
        let new_bid_notional = if action == OrderAction::Bid {
            add_exact(bid_notional, order_notional)
        } else {
            bid_notional
        };
        let new_ask_notional = if action == OrderAction::Ask {
            add_exact(ask_notional, order_notional)
        } else {
            ask_notional
        };

        let current_exposure_notional = 0i64.max(sub_exact(
            add_exact(open_notional, bid_notional)
                .abs()
                .max(sub_exact(open_notional, ask_notional).abs()),
            abs_open_notional,
        ));
        let new_exposure_notional = 0i64.max(sub_exact(
            add_exact(open_notional, new_bid_notional)
                .abs()
                .max(sub_exact(open_notional, new_ask_notional).abs()),
            abs_open_notional,
        ));

        let leverage = self.leverage as i64;
        let new_total_margin =
            add_exact(self.open_init_margin_sum, spec.calculate_init_margin(new_exposure_notional, leverage));
        let current_total_margin =
            add_exact(self.open_init_margin_sum, spec.calculate_init_margin(current_exposure_notional, leverage));
        if new_total_margin <= current_total_margin {
            -1
        } else {
            new_total_margin
        }
    }

    /// 对应 Java `estimateNotionalForOrder`（`:574-579`）：假设 pending 部分以及新下单的 size
    /// 都能开出来，估算仓位名义价值（保守估计，仅 `isValidLeverage` 检查用）。
    pub fn estimate_notional_for_order(&self, action: OrderAction, size: i64, price: i64) -> i64 {
        let new_pending_buy_size =
            if action == OrderAction::Bid { add_exact(self.pending_buy_size, size) } else { self.pending_buy_size };
        let new_pending_sell_size =
            if action == OrderAction::Ask { add_exact(self.pending_sell_size, size) } else { self.pending_sell_size };
        let estimated_size = add_exact(self.open_volume, new_pending_buy_size.max(new_pending_sell_size));
        mul_exact(estimated_size, price)
    }

    /// 对应 Java `calculatePendingFeeForOrder`（`:581-593`）：加此单后较差挂单侧的 taker 费估算
    /// （仅 NSF 预检用，不实收）。
    pub fn calculate_pending_fee_for_order(
        &self,
        spec: &CoreSymbolSpecification,
        action: OrderAction,
        size: i64,
        price: i64,
    ) -> i64 {
        let new_pending_buy_size =
            if action == OrderAction::Bid { add_exact(self.pending_buy_size, size) } else { self.pending_buy_size };
        let new_pending_sell_size =
            if action == OrderAction::Ask { add_exact(self.pending_sell_size, size) } else { self.pending_sell_size };
        let new_pending_buy_avg_price = if action == OrderAction::Bid {
            Self::calculate_avg_price(self.pending_buy_avg_price, self.pending_buy_size, price, size)
        } else {
            self.pending_buy_avg_price
        };
        let new_pending_sell_avg_price = if action == OrderAction::Ask {
            Self::calculate_avg_price(self.pending_sell_avg_price, self.pending_sell_size, price, size)
        } else {
            self.pending_sell_avg_price
        };

        let fee_pending_buy =
            calculate_taker_fee(new_pending_buy_size, new_pending_buy_avg_price, spec.taker_fee, spec.fee_scale_k);
        let fee_pending_sell =
            calculate_taker_fee(new_pending_sell_size, new_pending_sell_avg_price, spec.taker_fee, spec.fee_scale_k);
        fee_pending_buy.max(fee_pending_sell)
    }

    /// 对应 Java `calculatePendingFeeForOrderBudget`（`:599-615`）：BUDGET 单专用，与
    /// [`Self::calculate_pending_fee_for_order`] 同构，区别是用 `budget_notional`（product-scale
    /// 总预算）直接累加 notional，而不是 `price × size`。
    pub fn calculate_pending_fee_for_order_budget(
        &self,
        spec: &CoreSymbolSpecification,
        action: OrderAction,
        size: i64,
        budget_notional: i64,
    ) -> i64 {
        let new_pending_buy_size =
            if action == OrderAction::Bid { add_exact(self.pending_buy_size, size) } else { self.pending_buy_size };
        let new_pending_sell_size =
            if action == OrderAction::Ask { add_exact(self.pending_sell_size, size) } else { self.pending_sell_size };
        let new_pending_buy_avg_price = if action == OrderAction::Bid && new_pending_buy_size > 0 {
            ceil_divide(
                add_exact(mul_exact(self.pending_buy_avg_price, self.pending_buy_size), budget_notional),
                new_pending_buy_size,
            )
        } else {
            self.pending_buy_avg_price
        };
        let new_pending_sell_avg_price = if action == OrderAction::Ask && new_pending_sell_size > 0 {
            ceil_divide(
                add_exact(mul_exact(self.pending_sell_avg_price, self.pending_sell_size), budget_notional),
                new_pending_sell_size,
            )
        } else {
            self.pending_sell_avg_price
        };

        let fee_pending_buy =
            calculate_taker_fee(new_pending_buy_size, new_pending_buy_avg_price, spec.taker_fee, spec.fee_scale_k);
        let fee_pending_sell =
            calculate_taker_fee(new_pending_sell_size, new_pending_sell_avg_price, spec.taker_fee, spec.fee_scale_k);
        fee_pending_buy.max(fee_pending_sell)
    }

    // ================================================================
    // 成交开 / 平仓 —— 对应 Java `:625-669`
    // ================================================================

    /// 对应 Java `closeCurrentPositionFutures(OrderAction, long tradeSize, long tradePrice)`
    /// （`:625-654`）：唯一平/翻仓原语，用一笔反向成交去平当前持仓，返回平完后【还需新开】的
    /// 手数（reverse 单超出部分，由 [`Self::open_position_margin`] 接手）。三分支：
    ///
    /// - 无仓 (`open_volume==0`) 或同向成交：无可平，原样返回 `trade_size`（整笔用于开仓）。
    /// - 部分平（`open_volume > trade_size`）：**不结算盈亏**——按比例 `truncMulDiv(openInitMarginSum,
    ///   tradeSize, openVolume)` 释放保证金、减 `openVolume`，`openPriceSum` 按【成交价】而非开仓
    ///   均价扣减（把被平部分的盈亏"递延"进剩余仓位的成本基，剩余仓位最终全平时一并释放，全程
    ///   总盈亏守恒），返回 `0`。
    /// - 全平/翻仓（`trade_size >= open_volume`）：结算整仓已实现盈亏
    ///   `sign × (openVolume × tradePrice − openPriceSum)` 累加进 `profit`，清零
    ///   `openInitMarginSum`/`openPriceSum`/`openVolume`，返回 `tradeSize − openVolume`
    ///   （翻仓超出部分反手开新仓）。
    pub fn close_current_position_futures(&mut self, action: OrderAction, trade_size: i64, trade_price: i64) -> i64 {
        if self.open_volume == 0 || self.direction == PositionDirection::of_action(action) {
            return trade_size; // 无反向仓可平，整笔用于开仓
        }

        if self.open_volume > trade_size {
            // 部分平仓：此处不结算盈亏，而是把被平部分的盈亏递延进剩余仓位的成本基。
            let margin_release = trunc_mul_div(self.open_init_margin_sum, trade_size, self.open_volume);
            self.open_init_margin_sum = sub_exact(self.open_init_margin_sum, margin_release);
            self.open_volume -= trade_size; // open_volume > trade_size 已保证不变负、不溢出
            self.open_price_sum = sub_exact(self.open_price_sum, mul_exact(trade_size, trade_price));
            return 0;
        }

        // 全平（tradeSize ≥ openVolume）：结算整仓已实现盈亏 = 有向(平仓名义 − 成本基)，清零仓位。
        let close_notional = mul_exact(self.open_volume, trade_price);
        let pnl_raw = sub_exact(close_notional, self.open_price_sum);
        let pnl_signed = mul_exact(pnl_raw, self.direction.multiplier() as i64);
        self.profit = add_exact(self.profit, pnl_signed);
        self.open_init_margin_sum = 0;
        self.open_price_sum = 0;
        let size_to_open = sub_exact(trade_size, self.open_volume); // 超出部分反手开新仓
        self.open_volume = 0;

        size_to_open
    }

    /// 对应 Java `openPositionMargin(OrderAction, long sizeToOpen, long tradePrice,
    /// CoreSymbolSpecification, LastPriceCacheRecord)`（`:660-669`）：成交开新敞口，按
    /// `size_to_open` 累加持仓。初始保证金按【标记价 `mark_price`】名义计（更保守、与强平口径
    /// 一致），成本基 `open_price_sum` 按【成交价 `trade_price`】记（用于后续平仓算盈亏）。
    pub fn open_position_margin(
        &mut self,
        action: OrderAction,
        size_to_open: i64,
        trade_price: i64,
        spec: &CoreSymbolSpecification,
        mark_price: i64,
    ) {
        let open_notional = mul_exact(mark_price, size_to_open);
        let init_margin_delta = spec.calculate_init_margin(open_notional, self.leverage as i64);
        let price_notional = mul_exact(trade_price, size_to_open);
        self.open_volume = add_exact(self.open_volume, size_to_open);
        self.open_init_margin_sum = add_exact(self.open_init_margin_sum, init_margin_delta);
        self.open_price_sum = add_exact(self.open_price_sum, price_notional);
        self.direction = PositionDirection::of_action(action);
    }
}

/// 对应 Java `positions` map 的 key，形如 `BTreeMap<i32, SymbolPositionRecord>`（HEDGE 模式用
/// ±symbol 区分多空）；本移植归属 `UserProfile`（见 `user_profile.rs`）。
pub type PositionsMapKey = i32;
pub type PositionsMap = BTreeMap<PositionsMapKey, SymbolPositionRecord>;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_sets_identity_and_normalizes_leverage() {
        let r = SymbolPositionRecord::new(7, 100, 2, MarginMode::Cross, 0);
        assert_eq!(r.uid, 7);
        assert_eq!(r.symbol, 100);
        assert_eq!(r.currency, 2);
        assert_eq!(r.margin_mode, MarginMode::Cross);
        assert_eq!(r.leverage, 1); // 0 -> 1 归一
        assert_eq!(r.direction, PositionDirection::Empty);
        assert!(r.is_empty());
    }

    #[test]
    fn new_keeps_explicit_leverage() {
        let r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 10);
        assert_eq!(r.leverage, 10);
    }

    #[test]
    fn initialize_sets_direction_from_action_and_clears_state() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 5);
        r.open_volume = 100;
        r.profit = 50;

        r.initialize(2, 3, 4, OrderAction::Bid, 0, MarginMode::Cross);
        assert_eq!(r.uid, 2);
        assert_eq!(r.symbol, 3);
        assert_eq!(r.currency, 4);
        assert_eq!(r.direction, PositionDirection::Long);
        assert_eq!(r.open_volume, 0);
        assert_eq!(r.profit, 0);
        assert_eq!(r.leverage, 1);
        assert_eq!(r.margin_mode, MarginMode::Cross);

        r.initialize(2, 3, 4, OrderAction::Ask, 3, MarginMode::Isolated);
        assert_eq!(r.direction, PositionDirection::Short);
        assert_eq!(r.leverage, 3);
    }

    #[test]
    fn is_same_leverage_normalizes_zero_to_one() {
        let r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        assert!(r.is_same_leverage(0));
        assert!(r.is_same_leverage(1));
        assert!(!r.is_same_leverage(2));
    }

    #[test]
    fn is_empty_true_only_when_no_open_and_no_pending() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        assert!(r.is_empty());

        r.open_volume = 10;
        assert!(!r.is_empty());
        r.open_volume = 0;

        r.pending_sell_size = 5;
        assert!(!r.is_empty());
        r.pending_sell_size = 0;

        r.pending_buy_size = 5;
        assert!(!r.is_empty());
    }

    #[test]
    fn reset_clears_business_state_but_keeps_identity() {
        let mut r = SymbolPositionRecord::new(9, 100, 2, MarginMode::Cross, 5);
        r.open_volume = 10;
        r.open_init_margin_sum = 20;
        r.open_price_sum = 30;
        r.profit = 40;
        r.pending_sell_size = 1;
        r.pending_buy_size = 2;
        r.pending_sell_avg_price = 3;
        r.pending_buy_avg_price = 4;
        r.extra_margin = 5;
        r.direction = PositionDirection::Long;

        r.reset();

        assert_eq!(r.uid, 9); // identity 保留
        assert_eq!(r.symbol, 100);
        assert_eq!(r.currency, 2);

        assert_eq!(r.open_volume, 0);
        assert_eq!(r.open_init_margin_sum, 0);
        assert_eq!(r.open_price_sum, 0);
        // Java `reset()`（`:693-711`）逐字不清 `profit`——已实现盈亏累加器在池复用清零时保留
        // （非遗漏；本移植逐字对齐，不做"看起来更对"的修正）。
        assert_eq!(r.profit, 40);
        assert_eq!(r.pending_sell_size, 0);
        assert_eq!(r.pending_buy_size, 0);
        assert_eq!(r.pending_sell_avg_price, 0);
        assert_eq!(r.pending_buy_avg_price, 0);
        assert_eq!(r.extra_margin, 0);
        assert_eq!(r.direction, PositionDirection::Empty);
        assert_eq!(r.leverage, 1);
        assert_eq!(r.margin_mode, MarginMode::Isolated);
    }

    #[test]
    fn state_hash_deterministic_and_excludes_uid() {
        let a = SymbolPositionRecord::new(1, 100, 2, MarginMode::Isolated, 5);
        let b = SymbolPositionRecord::new(999, 100, 2, MarginMode::Isolated, 5); // uid 不同
        assert_eq!(a.state_hash(), b.state_hash(), "stateHash 逐字对齐 Java：不含 uid");
    }

    #[test]
    fn state_hash_changes_with_business_fields() {
        let base = SymbolPositionRecord::new(1, 100, 2, MarginMode::Isolated, 5);
        let h0 = base.state_hash();

        let mut diff_symbol = base.clone();
        diff_symbol.symbol = 101;
        assert_ne!(h0, diff_symbol.state_hash());

        let mut diff_open_volume = base.clone();
        diff_open_volume.open_volume = 1;
        assert_ne!(h0, diff_open_volume.state_hash());

        let mut diff_margin_mode = base.clone();
        diff_margin_mode.margin_mode = MarginMode::Cross;
        assert_ne!(h0, diff_margin_mode.state_hash());

        let mut diff_extra_margin = base.clone();
        diff_extra_margin.extra_margin = 1;
        assert_ne!(h0, diff_extra_margin.state_hash());
    }

    // ------------------------------------------------------------------
    // P6 Task 1：非复制字段排除 state_hash（Ruling P6-E）+ calculate_bankruptcy_price。
    // ------------------------------------------------------------------

    #[test]
    fn state_hash_excludes_non_replicated_adl_fields() {
        let base = SymbolPositionRecord::new(1, 100, 2, MarginMode::Isolated, 5);
        let h0 = base.state_hash();

        let mut diff_pending_adl = base.clone();
        diff_pending_adl.pending_adl_size = 999;
        assert_eq!(h0, diff_pending_adl.state_hash(), "pending_adl_size 非复制，不进 state_hash");

        let mut diff_adl_elig = base.clone();
        diff_adl_elig.adl_eligibility = 100;
        assert_eq!(h0, diff_adl_elig.state_hash(), "adl_eligibility 非复制，不进 state_hash");
    }

    fn long_position(open_volume: i64, open_init_margin_sum: i64, open_price_sum: i64, extra_margin: i64) -> SymbolPositionRecord {
        let mut p = SymbolPositionRecord::new(1, 100, 2, MarginMode::Isolated, 1);
        p.direction = PositionDirection::Long; // sign = +1
        p.open_volume = open_volume;
        p.open_init_margin_sum = open_init_margin_sum;
        p.open_price_sum = open_price_sum;
        p.extra_margin = extra_margin;
        p
    }

    #[test]
    fn calculate_bankruptcy_price_isolated_fixed_fee() {
        // ISOLATED Long：margin_base = 100+20 = 120；total_fee = taker(2)+liq(3) = 5；fixed(fee_scale_k=0)。
        // max_loss = 120 - 5*10 = 70；numer = 1000 - 1*70 = 930；ceil_divide(930,10) = 93。
        let pos = long_position(10, 100, 1000, 20);
        let spec = CoreSymbolSpecification { taker_fee: 2, liquidation_fee: 3, fee_scale_k: 0, ..Default::default() };
        assert_eq!(pos.calculate_bankruptcy_price(&spec, |_| 0), 93);
    }

    #[test]
    fn calculate_bankruptcy_price_isolated_proportional_fee() {
        // ISOLATED Long：margin_base = 100+0 = 100；total_fee = 100+100 = 200；fee_scale_k = 1_000_000。
        // numer = 1000 - 1*100 = 900；denom = 10*(1_000_000 - 1*200) = 9_998_000；
        // ceil_mul_div(900, 1_000_000, 9_998_000) = ceil(900_000_000/9_998_000) = ceil(90.018) = 91。
        let pos = long_position(10, 100, 1000, 0);
        let spec = CoreSymbolSpecification { taker_fee: 100, liquidation_fee: 100, fee_scale_k: 1_000_000, ..Default::default() };
        assert_eq!(pos.calculate_bankruptcy_price(&spec, |_| 0), 91);
    }

    #[test]
    fn default_is_all_zero_empty() {
        let r = SymbolPositionRecord::default();
        assert_eq!(r.uid, 0);
        assert_eq!(r.leverage, 0); // Default derive 不走 update_leverage 归一，逐字是原始零值
        assert_eq!(r.margin_mode, MarginMode::Isolated);
        assert_eq!(r.direction, PositionDirection::Empty);
        assert!(r.is_empty());
    }

    // ================================================================
    // Task 2：pending hold / release —— Java `:160-226`
    // ================================================================

    #[test]
    fn pending_hold_ask_accumulates_size_and_weighted_avg_ceil() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.pending_hold(OrderAction::Ask, 10, 100);
        assert_eq!(r.pending_sell_size, 10);
        assert_eq!(r.pending_sell_avg_price, 100);

        // total_size=15, total_notional=10*100+5*130=1650, ceil(1650/15)=110
        r.pending_hold(OrderAction::Ask, 5, 130);
        assert_eq!(r.pending_sell_size, 15);
        assert_eq!(r.pending_sell_avg_price, 110);
        // 未触碰 buy 侧
        assert_eq!(r.pending_buy_size, 0);
        assert_eq!(r.pending_buy_avg_price, 0);
    }

    #[test]
    fn pending_hold_bid_ceils_non_exact_average() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.pending_hold(OrderAction::Bid, 3, 10); // notional=30
        r.pending_hold(OrderAction::Bid, 2, 11); // notional+=22=52, size=5, 52/5=10.4 -> ceil 11
        assert_eq!(r.pending_buy_size, 5);
        assert_eq!(r.pending_buy_avg_price, 11);
    }

    #[test]
    fn pending_hold_budget_tracks_notional_directly_and_ceils() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.pending_hold_budget(OrderAction::Ask, 10, 1005); // ceil(1005/10)=101
        assert_eq!(r.pending_sell_size, 10);
        assert_eq!(r.pending_sell_avg_price, 101);

        // pendingNotional = 101*10 + 500 = 1510, newSize=15, ceil(1510/15)=101 (100.67->101)
        r.pending_hold_budget(OrderAction::Ask, 5, 500);
        assert_eq!(r.pending_sell_size, 15);
        assert_eq!(r.pending_sell_avg_price, 101);
    }

    #[test]
    fn pending_hold_budget_new_size_non_positive_is_noop() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.pending_hold_budget(OrderAction::Bid, -5, -500); // newSize=-5<=0 -> 直接返回
        assert_eq!(r.pending_buy_size, 0);
        assert_eq!(r.pending_buy_avg_price, 0);
    }

    #[test]
    fn pending_release_partial_keeps_avg_full_resets_avg() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.pending_sell_size = 10;
        r.pending_sell_avg_price = 100;

        // 部分释放：avg 不变
        let released = r.pending_release(OrderAction::Ask, 4);
        assert_eq!(released, 4);
        assert_eq!(r.pending_sell_size, 6);
        assert_eq!(r.pending_sell_avg_price, 100);

        // 侧归零：重置 avg
        let released2 = r.pending_release(OrderAction::Ask, 6);
        assert_eq!(released2, 6);
        assert_eq!(r.pending_sell_size, 0);
        assert_eq!(r.pending_sell_avg_price, 0);
    }

    #[test]
    fn pending_release_over_release_clamps_to_available_and_resets_avg() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.pending_buy_size = 5;
        r.pending_buy_avg_price = 200;

        let released = r.pending_release(OrderAction::Bid, 8); // min(5,8)=5
        assert_eq!(released, 5);
        assert_eq!(r.pending_buy_size, 0);
        assert_eq!(r.pending_buy_avg_price, 0);
    }

    // ================================================================
    // Task 2：estimate_unrealized_profit / estimate_pnl —— Java `:233-245`
    // ================================================================

    #[test]
    fn estimate_unrealized_profit_long_gains_when_mark_above_cost() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 10;
        r.open_price_sum = 1000; // avg cost 100
        assert_eq!(r.estimate_unrealized_profit(120), 200); // (1200-1000)*+1
    }

    #[test]
    fn estimate_unrealized_profit_short_gains_when_mark_below_cost() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Short;
        r.open_volume = 10;
        r.open_price_sum = 1000; // avg cost 100
        assert_eq!(r.estimate_unrealized_profit(80), 200); // (800-1000)*-1
    }

    #[test]
    fn estimate_pnl_adds_realized_profit_to_unrealized() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 10;
        r.open_price_sum = 1000;
        r.profit = 50;
        assert_eq!(r.estimate_pnl(120), 250); // 50 + 200
    }

    // ================================================================
    // Task 2：calculate_maintenance_margin —— Java `:478-484`
    // ================================================================

    #[test]
    fn calculate_maintenance_margin_zero_when_flat() {
        let r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        let spec = CoreSymbolSpecification::default();
        assert_eq!(r.calculate_maintenance_margin(&spec, 100), 0);
    }

    #[test]
    fn calculate_maintenance_margin_ignores_pending_uses_open_volume_at_mark() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.open_volume = 10;
        r.pending_buy_size = 999; // 挂单必须被忽略
        // 未配置分档表：spec.calculate_maintenance_margin 按 100% 兜底返回 notional
        let spec = CoreSymbolSpecification::default();
        assert_eq!(r.calculate_maintenance_margin(&spec, 100), 1000);
    }

    // ================================================================
    // Task 2：calculate_required_margin_for_futures —— Java `:523-539`
    // ================================================================

    fn fee_spec(taker_fee: i64) -> CoreSymbolSpecification {
        // fee_scale_k=0 -> 固定费模式：fee = size * taker_fee（避免测试里再算比例费率）
        CoreSymbolSpecification { taker_fee, fee_scale_k: 0, ..Default::default() }
    }

    #[test]
    fn required_margin_for_futures_flat_no_pending_is_zero() {
        let r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        let spec = fee_spec(2);
        assert_eq!(r.calculate_required_margin_for_futures(&spec), 0);
    }

    #[test]
    fn required_margin_for_futures_bid_pending_expands_long_exposure() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 10;
        r.open_price_sum = 1000; // openNotional=+1000
        r.open_init_margin_sum = 100;
        r.pending_buy_size = 5;
        r.pending_buy_avg_price = 100; // bidNotional=500

        let spec = fee_spec(2); // bidFee=5*2=10, askFee=0
        // worstCase=max(|1000+500|=1500, |1000-0|=1000)=1500; newExposure=500
        // initMargin(500, lev=1, 未配置)=500/1=500
        // total = 100 + 500 + max(10,0) = 610
        assert_eq!(r.calculate_required_margin_for_futures(&spec), 610);
    }

    #[test]
    fn required_margin_for_futures_pure_reducing_ask_pending_adds_no_exposure_margin() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 10;
        r.open_price_sum = 1000;
        r.open_init_margin_sum = 100;
        r.pending_sell_size = 5; // 全在 openVolume 范围内，纯减仓
        r.pending_sell_avg_price = 100; // askNotional=500

        let spec = fee_spec(2); // askFee=5*2=10
        // worstCase=max(|1000|=1000, |1000-500|=500)=1000; newExposure=max(0,1000-1000)=0
        // total = 100 + 0 + max(0,10) = 110
        assert_eq!(r.calculate_required_margin_for_futures(&spec), 110);
    }

    // ================================================================
    // Task 2：calculate_required_margin_for_order —— Java `:548-569`，-1 哨兵
    // ================================================================

    #[test]
    fn required_margin_for_order_pure_reduce_returns_sentinel_minus_one() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 10;
        r.open_price_sum = 1000;
        r.open_init_margin_sum = 100;

        let spec = CoreSymbolSpecification::default();
        // action=Ask, orderNotional=300：newAsk=300 -> worstCase=max(1000,700)=1000, newExposure=0
        // currentExposure 也是 0 -> newTotal(100) <= currentTotal(100) -> -1
        assert_eq!(r.calculate_required_margin_for_order(&spec, OrderAction::Ask, 300), -1);
    }

    #[test]
    fn required_margin_for_order_expanding_bid_returns_positive_total() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 10;
        r.open_price_sum = 1000;
        r.open_init_margin_sum = 100;

        let spec = CoreSymbolSpecification::default();
        // action=Bid, orderNotional=500: newBid=500 -> worstCase=max(1500,1000)=1500, newExposure=500
        // newTotal = 100 + 500/1 = 600 > currentTotal(100) -> 600
        assert_eq!(r.calculate_required_margin_for_order(&spec, OrderAction::Bid, 500), 600);
    }

    // ================================================================
    // Task 2：estimate_notional_for_order / calculate_pending_fee_for_order[_budget]
    // —— Java `:574-615`
    // ================================================================

    #[test]
    fn estimate_notional_for_order_uses_max_pending_side_plus_open() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.open_volume = 10;
        r.pending_buy_size = 5;
        r.pending_sell_size = 2;
        // action=Bid size=3: newPendingBuy=8, newPendingSell=2, estimatedSize=10+8=18, *50=900
        assert_eq!(r.estimate_notional_for_order(OrderAction::Bid, 3, 50), 900);
    }

    #[test]
    fn calculate_pending_fee_for_order_picks_worse_side() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.pending_sell_size = 3;
        r.pending_sell_avg_price = 200;

        let spec = fee_spec(10);
        // newPendingBuy: size=5, avg=ceil((0+500)/5)=100 -> fee=5*10=50
        // pendingSell 不变: size=3,avg=200 -> fee=3*10=30
        // max=50
        assert_eq!(r.calculate_pending_fee_for_order(&spec, OrderAction::Bid, 5, 100), 50);
    }

    #[test]
    fn calculate_pending_fee_for_order_budget_uses_notional_directly() {
        let r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        let spec = fee_spec(10);
        // action=Ask size=4 budget=1000: newPendingSellAvg=ceil(1000/4)=250 -> fee=4*10=40
        // pendingBuy 仍 0 -> fee=0
        assert_eq!(r.calculate_pending_fee_for_order_budget(&spec, OrderAction::Ask, 4, 1000), 40);
    }

    // ================================================================
    // Task 2：open_position_margin —— Java `:660-669`
    // ================================================================

    #[test]
    fn open_position_margin_margin_off_mark_cost_off_trade() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 2); // leverage=2
        let spec = CoreSymbolSpecification::default(); // 未配置 -> initMargin = notional/leverage

        r.open_position_margin(OrderAction::Bid, 10, 100, &spec, 120);
        assert_eq!(r.open_volume, 10);
        assert_eq!(r.open_init_margin_sum, 600); // (120*10)/2
        assert_eq!(r.open_price_sum, 1000); // 100*10（按成交价，非标记价）
        assert_eq!(r.direction, PositionDirection::Long);
    }

    // ================================================================
    // Task 2：close_current_position_futures —— Java `:625-654`（三分支）
    // ================================================================

    #[test]
    fn close_current_position_no_open_position_returns_full_trade_size_untouched() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        let size_to_open = r.close_current_position_futures(OrderAction::Ask, 7, 100);
        assert_eq!(size_to_open, 7);
        assert_eq!(r.open_volume, 0);
        assert_eq!(r.profit, 0);
    }

    #[test]
    fn close_current_position_same_direction_is_noop_returns_full_trade_size() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 5;
        r.open_price_sum = 500;
        r.open_init_margin_sum = 50;

        // action=Bid 与 direction=Long 同向 -> 无可平，整笔用于开仓
        let size_to_open = r.close_current_position_futures(OrderAction::Bid, 3, 999);
        assert_eq!(size_to_open, 3);
        // 仓位状态不变
        assert_eq!(r.open_volume, 5);
        assert_eq!(r.open_price_sum, 500);
        assert_eq!(r.open_init_margin_sum, 50);
        assert_eq!(r.profit, 0);
    }

    #[test]
    fn close_current_position_partial_close_defers_pnl_into_cost_basis() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 10;
        r.open_price_sum = 1000;
        r.open_init_margin_sum = 300;

        // action=Ask（反向）trade_size=4 < open_volume=10 -> 部分平
        let size_to_open = r.close_current_position_futures(OrderAction::Ask, 4, 120);
        assert_eq!(size_to_open, 0);
        assert_eq!(r.open_volume, 6);
        // marginRelease = truncMulDiv(300,4,10) = 120 -> 剩 180
        assert_eq!(r.open_init_margin_sum, 180);
        // openPriceSum -= tradeSize*tradePrice = 1000 - 480 = 520（按成交价扣，非均价）
        assert_eq!(r.open_price_sum, 520);
        // 不实现盈亏
        assert_eq!(r.profit, 0);
        assert_eq!(r.direction, PositionDirection::Long);
    }

    #[test]
    fn close_current_position_full_close_exact_realizes_pnl_and_zeroes_position() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Long;
        r.open_volume = 6;
        r.open_price_sum = 520;
        r.open_init_margin_sum = 180;
        r.profit = 0;

        // trade_size == open_volume（边界）-> 全平分支
        let size_to_open = r.close_current_position_futures(OrderAction::Ask, 6, 150);
        assert_eq!(size_to_open, 0);
        assert_eq!(r.open_volume, 0);
        assert_eq!(r.open_init_margin_sum, 0);
        assert_eq!(r.open_price_sum, 0);
        // pnl = (6*150 - 520) * +1 = 380
        assert_eq!(r.profit, 380);
    }

    #[test]
    fn close_current_position_flip_realizes_pnl_on_old_volume_and_returns_remainder() {
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Short;
        r.open_volume = 5;
        r.open_price_sum = 500; // avg 100
        r.open_init_margin_sum = 50;
        r.profit = 0;

        // action=Bid（反向于 Short）trade_size=8 > open_volume=5 -> 翻仓
        let size_to_open = r.close_current_position_futures(OrderAction::Bid, 8, 80);
        assert_eq!(size_to_open, 3); // 8-5 反手开新仓余量
        assert_eq!(r.open_volume, 0);
        assert_eq!(r.open_init_margin_sum, 0);
        assert_eq!(r.open_price_sum, 0);
        // pnl = (5*80 - 500) * -1(Short) = (400-500)*-1 = 100
        assert_eq!(r.profit, 100);
    }

    #[test]
    fn close_then_open_round_trip_flip_matches_manual_open() {
        // R2 恒 close-then-open：验证 close 返回的 sizeToOpen 喂给 open_position_margin 后
        // 状态与"先全平再单独开仓"一致（无独立 flip 方法，靠两原语组合）。
        let mut r = SymbolPositionRecord::new(1, 1, 1, MarginMode::Isolated, 1);
        r.direction = PositionDirection::Short;
        r.open_volume = 5;
        r.open_price_sum = 500;
        r.open_init_margin_sum = 50;

        let spec = CoreSymbolSpecification::default();
        let size_to_open = r.close_current_position_futures(OrderAction::Bid, 8, 80);
        assert_eq!(size_to_open, 3);
        r.open_position_margin(OrderAction::Bid, size_to_open, 80, &spec, 80);

        assert_eq!(r.open_volume, 3);
        assert_eq!(r.direction, PositionDirection::Long);
        assert_eq!(r.open_price_sum, 240); // 80*3
        assert_eq!(r.open_init_margin_sum, 240); // mark=80: (80*3)/leverage(1)
        assert_eq!(r.profit, 100); // 翻仓平腿已实现盈亏
    }
}
