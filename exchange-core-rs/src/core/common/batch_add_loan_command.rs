//! 对应 Java: exchange.core2.core.common.api.binary.BatchAddLoanCommand —— `ADD_LOAN` 运行时
//! 配置命令的 DTO + 全部校验器（参考文档 §2.12）。三段（`global`/`symbol`/`rate_curve`）各自
//! 独立可选、独立校验：一段非法只 `log.warn`（本移植无日志基建，调用方按返回值/文档约定自行
//! 感知，见 `RiskEngine::apply_add_loan` 文档）并跳过，不影响另外两段。
//!
//! Java 原版还携带 Chronicle `BytesIn`/`BytesOut` 序列化（跨节点 binary command 帧）——本仓库
//! 无该序列化基建（`grep BytesIn/BytesOut` 全仓库唯一命中在 journaling 模块，与本 DTO 无关），
//! 故本移植只保留纯数据 + 纯校验，不做 marshalling；`RiskEngine::apply_add_loan` 直接接收
//! `&BatchAddLoanCommand` 值本身（见该方法文档，routing 偏差的完整说明）。

/// bps 满量程（100%）。对应 Java `BatchAddLoanCommand.BPS_FULL`。
pub const BPS_FULL: i32 = 10_000;

/// 对应 Java `BatchAddLoanCommand`：三段独立可选。至少一段非 `None`（Java 构造器校验此项；
/// 本移植把该约束留给调用方——纯数据类型没有"非法状态不可构造"的构造器可拒绝，三段全
/// `None` 时 `RiskEngine::apply_add_loan` 是纯 no-op，无害）。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct BatchAddLoanCommand {
    pub global: Option<GlobalLoanConfig>,
    pub symbol: Option<SymbolLoanConfig>,
    pub rate_curve: Option<RateCurveConfig>,
}

// ========================================================================
// GlobalLoanConfig —— 参考文档 §2.12 第一段
// ========================================================================

/// 对应 Java `BatchAddLoanCommand.GlobalLoanConfig`：5 个 partial-update 字段（`<=0` = 不改，
/// 两个字段是绝对赋值——`numeraireCurrency`/`crossLiquidationLtvBps`/`crossMarginCallLtvBps`
/// 同样遵循 `<=0` 不改约定，见 Java `numeraireCurrency <= 0` 分支）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GlobalLoanConfig {
    /// Cross 估值基准币；`<=0` = 不改。
    pub numeraire_currency: i32,
    /// Cross 账户级强平线；`<=0` = 不改。
    pub cross_liquidation_ltv_bps: i32,
    /// Cross 账户级预警线；`<=0` = 不改。
    pub cross_margin_call_ltv_bps: i32,
    /// 借贷池利用率上限；`<=0` = 不改。
    pub loan_pool_utilization_cap_bps: i32,
    /// 强平专项费率；`<=0` = 不改。
    pub loan_liquidation_fee_bps: i32,
    /// Symbol 派生缓冲：`liquidationLtv = initialLtv + 本值`；`<=0` = 不改。
    pub ltv_liquidation_buffer_bps: i32,
    /// Symbol 派生缓冲：`marginCallLtv = liquidationLtv − 本值`；`<=0` = 不改。
    pub ltv_margin_call_buffer_bps: i32,
}

impl GlobalLoanConfig {
    /// 对应 Java `thresholdsValidGivenCurrent(int, int)`（`:199-209`）：apply-all-or-nothing
    /// 校验——针对"应用本次更新之后"会生效的有效值（未提供的沿用调用方传入的当前值），而非
    /// 本次更新携带的原始字段值本身。`loanPoolUtilizationCapBps`/`loanLiquidationFeeBps`/两个
    /// 缓冲字段这 4 项则直接校验原始字段（它们没有"当前值"参数可比对——调用方若不改就传
    /// `<=0`，此时该子式恒真，不卡校验）。
    pub fn thresholds_valid_given_current(
        &self,
        current_cross_liquidation_ltv_bps: i32,
        current_cross_margin_call_ltv_bps: i32,
    ) -> bool {
        let eff_liquidation = if self.cross_liquidation_ltv_bps > 0 {
            self.cross_liquidation_ltv_bps
        } else {
            current_cross_liquidation_ltv_bps
        };
        let eff_margin_call = if self.cross_margin_call_ltv_bps > 0 {
            self.cross_margin_call_ltv_bps
        } else {
            current_cross_margin_call_ltv_bps
        };
        eff_margin_call > 0
            && eff_margin_call < eff_liquidation
            && eff_liquidation < BPS_FULL
            && (self.loan_pool_utilization_cap_bps <= 0 || self.loan_pool_utilization_cap_bps <= BPS_FULL)
            && (self.loan_liquidation_fee_bps <= 0 || self.loan_liquidation_fee_bps < BPS_FULL)
            && (self.ltv_liquidation_buffer_bps <= 0 || self.ltv_liquidation_buffer_bps < BPS_FULL)
            && (self.ltv_margin_call_buffer_bps <= 0 || self.ltv_margin_call_buffer_bps < BPS_FULL)
    }
}

// ========================================================================
// SymbolLoanConfig —— 参考文档 §2.12 第二段
// ========================================================================

/// `UNSET` 哨兵值：override 字段未指定，按 `resolve` 从全局缓冲派生/默认。对应 Java
/// `SymbolLoanConfig.UNSET`。
pub const UNSET: i32 = -1;
/// `loan_max_amount`（`i64`）的 `UNSET` 哨兵值——Java 字段是 `long`，`UNSET` 字面量 `-1` 隐式转
/// 宽；本移植字段类型不同，需要各自一份同值常量。
pub const UNSET_AMOUNT: i64 = -1;

/// 对应 Java `BatchAddLoanCommand.SymbolLoanConfig`：6 个 per-symbol override 字段（外加
/// `collateral_weight_bps`，虽然落地时写到 base currency，而不是 symbol）。`UNSET`
/// （`-1`/`UNSET_AMOUNT`）= 未指定该项，`resolve` 时从 `loanInitialLtvBps`/全局缓冲派生。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SymbolLoanConfig {
    pub symbol_id: i32,
    /// 开仓 LTV 上限；`0` = 关闭该 pair 的借贷（kill-switch）。
    pub loan_initial_ltv_bps: i32,
    /// `UNSET` = 派生：`initial + ltvLiquidationBufferBps`。
    pub loan_liquidation_ltv_bps: i32,
    /// `UNSET` = 派生：`liquidation − ltvMarginCallBufferBps`。
    pub loan_margin_call_ltv_bps: i32,
    /// `UNSET_AMOUNT` = 派生为 `0`（无上限）。
    pub loan_max_amount: i64,
    /// `UNSET` = 派生为 `0`（无期限）。
    pub loan_max_term_days: i32,
    /// `UNSET` = 派生为 `loanInitialLtvBps`（抵押折价率默认等于该 LTV 本身）。落地时写到 base
    /// currency，见 `RiskEngine::apply_add_loan` 文档。
    pub collateral_weight_bps: i32,
}

impl SymbolLoanConfig {
    /// 对应 Java `resolve(int liqBufferBps, int mcBufferBps)`（`:283-290`）：把三个可能是
    /// `UNSET` 的字段（`liquidation`/`marginCall`/`collateralWeight`）连同两个金额/期限字段
    /// 一起填实成 [`Resolved`]。**注意派生顺序**：`marginCall` 的默认值依赖 `liq`（已派生后的
    /// 值），不是 `loan_liquidation_ltv_bps` 的原始字段值——即便原始 `liquidation` 字段本身是
    /// 显式指定的，`marginCall` 未指定时依然从"最终生效的 liquidation"倒推。
    pub fn resolve(&self, liq_buffer_bps: i32, mc_buffer_bps: i32) -> Resolved {
        let liq = if self.loan_liquidation_ltv_bps == UNSET {
            self.loan_initial_ltv_bps + liq_buffer_bps
        } else {
            self.loan_liquidation_ltv_bps
        };
        let mc =
            if self.loan_margin_call_ltv_bps == UNSET { liq - mc_buffer_bps } else { self.loan_margin_call_ltv_bps };
        let weight =
            if self.collateral_weight_bps == UNSET { self.loan_initial_ltv_bps } else { self.collateral_weight_bps };
        let max_amount = if self.loan_max_amount == UNSET_AMOUNT { 0 } else { self.loan_max_amount };
        let max_term_days = if self.loan_max_term_days == UNSET { 0 } else { self.loan_max_term_days };
        Resolved {
            symbol_id: self.symbol_id,
            initial_ltv_bps: self.loan_initial_ltv_bps,
            liquidation_ltv_bps: liq,
            margin_call_ltv_bps: mc,
            max_amount,
            max_term_days,
            collateral_weight_bps: weight,
        }
    }
}

/// 派生后的最终配置（所有 `UNSET` 已填实）。对应 Java `SymbolLoanConfig.Resolved`。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Resolved {
    pub symbol_id: i32,
    pub initial_ltv_bps: i32,
    pub liquidation_ltv_bps: i32,
    pub margin_call_ltv_bps: i32,
    pub max_amount: i64,
    pub max_term_days: i32,
    pub collateral_weight_bps: i32,
}

impl Resolved {
    /// 对应 Java `Resolved.valid()`（`:269-274`）：`0 <= initial < 10000`；`initial>0` 时还要
    /// `thresholds_valid`；`maxAmount`/`maxTermDays` 非负；**`collateralWeightBps ∈ [0,10000]`
    /// ——这一条是 Task 5 遗留的前置义务的收口点**：`LoanService::cross_ltv_bps` 里对
    /// `collateral_weight_bps` 做 `trunc_mul_div(valueInNum, weight, BPS_SCALE)` 且不做
    /// overflow-checked（Task 5 报告记录：weight ∈ [0,10000] 时数学上不可达 panic，前提是
    /// "当前没有任何命令能把它设到该区间外"）——`ADD_LOAN` 正是那个命令，所以这里必须严格挡住
    /// 越界值，否则会让 Task 5 标记为"当前不可达"的 panic 变得可达。
    pub fn valid(&self) -> bool {
        self.initial_ltv_bps >= 0
            && self.initial_ltv_bps < BPS_FULL
            && (self.initial_ltv_bps == 0
                || thresholds_valid(self.initial_ltv_bps, self.margin_call_ltv_bps, self.liquidation_ltv_bps))
            && self.max_amount >= 0
            && self.max_term_days >= 0
            && self.collateral_weight_bps >= 0
            && self.collateral_weight_bps <= BPS_FULL
    }
}

/// 对应 Java `SymbolLoanConfig.thresholdsValid(int, int, int)`（`:278-281`）：
/// `initial < marginCall < liquidation < 100%`；`marginCall==0` 表示关预警（合法）。
fn thresholds_valid(initial: i32, margin_call: i32, liquidation: i32) -> bool {
    liquidation > initial
        && liquidation < BPS_FULL
        && (margin_call == 0 || (margin_call > initial && margin_call < liquidation))
}

// ========================================================================
// RateCurveConfig —— 参考文档 §2.12 第三段
// ========================================================================

/// 对应 Java `BatchAddLoanCommand.RateCurveConfig`：5 个字段，存在即整体替换（无 partial-update
/// ——`0` 是合法曲线值，不能借用 `<=0` 当"不改"哨兵）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RateCurveConfig {
    /// 零利用率基础利率。
    pub base_bps: i32,
    /// 利用率拐点；须 `0 < kink < 100%`。
    pub kink_util_bps: i32,
    /// 拐点前斜率。
    pub slope1_bps: i32,
    /// 拐点后斜率。
    pub slope2_bps: i32,
    /// Fixed 相对曲线的加/减价（可负，apply 时下限 0——见 `FixedRateModel::open_rate_bps`）。
    pub locked_rate_adjust_bps: i32,
}

impl RateCurveConfig {
    /// 对应 Java `RateCurveConfig.valid()`（`:323-326`）：`base ∈ [0,10000)`、
    /// `0 < kink < 10000`、`slope1/slope2 >= 0`；`locked_rate_adjust_bps` 无约束（可负）。
    pub fn valid(&self) -> bool {
        self.base_bps >= 0
            && self.base_bps < BPS_FULL
            && self.kink_util_bps > 0
            && self.kink_util_bps < BPS_FULL
            && self.slope1_bps >= 0
            && self.slope2_bps >= 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ================================================================
    // GlobalLoanConfig::thresholds_valid_given_current
    // ================================================================

    fn global(
        numeraire: i32,
        cross_liq: i32,
        cross_mc: i32,
        util_cap: i32,
        liq_fee: i32,
        liq_buf: i32,
        mc_buf: i32,
    ) -> GlobalLoanConfig {
        GlobalLoanConfig {
            numeraire_currency: numeraire,
            cross_liquidation_ltv_bps: cross_liq,
            cross_margin_call_ltv_bps: cross_mc,
            loan_pool_utilization_cap_bps: util_cap,
            loan_liquidation_fee_bps: liq_fee,
            ltv_liquidation_buffer_bps: liq_buf,
            ltv_margin_call_buffer_bps: mc_buf,
        }
    }

    #[test]
    fn global_thresholds_valid_when_all_fields_within_range() {
        let g = global(5, 8500, 8000, 9000, 200, 2000, 1000);
        assert!(g.thresholds_valid_given_current(8500, 8000));
    }

    #[test]
    fn global_thresholds_no_change_fields_fall_back_to_current() {
        // cross_liq/cross_mc both <=0 ("no change") -> effective values come from "current".
        let g = global(0, 0, 0, 0, 0, 0, 0);
        assert!(g.thresholds_valid_given_current(8500, 8000));
        // But if current itself is inconsistent (marginCall >= liquidation), still invalid.
        assert!(!g.thresholds_valid_given_current(8000, 8500));
    }

    #[test]
    fn global_thresholds_rejects_margin_call_at_or_above_liquidation() {
        let g = global(0, 8000, 8000, 0, 0, 0, 0); // eff_margin_call == eff_liquidation
        assert!(!g.thresholds_valid_given_current(1, 1));
    }

    #[test]
    fn global_thresholds_rejects_liquidation_at_bps_full() {
        let g = global(0, BPS_FULL, 8000, 0, 0, 0, 0);
        assert!(!g.thresholds_valid_given_current(1, 1));
    }

    #[test]
    fn global_thresholds_rejects_out_of_range_side_fields() {
        assert!(!global(0, 8500, 8000, BPS_FULL + 1, 0, 0, 0).thresholds_valid_given_current(1, 1));
        assert!(!global(0, 8500, 8000, 0, BPS_FULL, 0, 0).thresholds_valid_given_current(1, 1));
        assert!(!global(0, 8500, 8000, 0, 0, BPS_FULL, 0).thresholds_valid_given_current(1, 1));
        assert!(!global(0, 8500, 8000, 0, 0, 0, BPS_FULL).thresholds_valid_given_current(1, 1));
    }

    // ================================================================
    // SymbolLoanConfig::resolve / Resolved::valid
    // ================================================================

    fn symbol(
        symbol_id: i32,
        initial: i32,
        liquidation: i32,
        margin_call: i32,
        max_amount: i64,
        max_term_days: i32,
        collateral_weight: i32,
    ) -> SymbolLoanConfig {
        SymbolLoanConfig {
            symbol_id,
            loan_initial_ltv_bps: initial,
            loan_liquidation_ltv_bps: liquidation,
            loan_margin_call_ltv_bps: margin_call,
            loan_max_amount: max_amount,
            loan_max_term_days: max_term_days,
            collateral_weight_bps: collateral_weight,
        }
    }

    #[test]
    fn resolve_derives_liquidation_margin_call_and_weight_from_unset_fields() {
        let s = symbol(1, 5_000, UNSET, UNSET, UNSET_AMOUNT, UNSET, UNSET);
        let r = s.resolve(2_000, 1_000); // liq_buffer=2000, mc_buffer=1000
        assert_eq!(r.liquidation_ltv_bps, 7_000); // 5000 + 2000
        assert_eq!(r.margin_call_ltv_bps, 6_000); // 7000 - 1000
        assert_eq!(r.collateral_weight_bps, 5_000); // defaults to initialLtv
        assert_eq!(r.max_amount, 0);
        assert_eq!(r.max_term_days, 0);
        assert!(r.valid());
    }

    #[test]
    fn resolve_preserves_explicit_overrides_over_derivation() {
        let s = symbol(1, 5_000, 8_000, 7_000, 1_000_000, 30, 4_000);
        let r = s.resolve(2_000, 1_000);
        assert_eq!(r.liquidation_ltv_bps, 8_000); // explicit, buffer ignored
        assert_eq!(r.margin_call_ltv_bps, 7_000); // explicit
        assert_eq!(r.collateral_weight_bps, 4_000); // explicit
        assert_eq!(r.max_amount, 1_000_000);
        assert_eq!(r.max_term_days, 30);
        assert!(r.valid());
    }

    #[test]
    fn resolve_margin_call_default_depends_on_resolved_liquidation_not_raw_field() {
        // liquidation explicit, marginCall UNSET -> derived from the *resolved* liquidation.
        let s = symbol(1, 5_000, 8_500, UNSET, UNSET_AMOUNT, UNSET, UNSET);
        let r = s.resolve(2_000, 1_000);
        assert_eq!(r.liquidation_ltv_bps, 8_500);
        assert_eq!(r.margin_call_ltv_bps, 7_500); // 8500 - 1000, not derived-from-initial path
    }

    #[test]
    fn resolved_valid_accepts_margin_call_zero_as_disabled_warning() {
        let r = symbol(1, 5_000, 8_000, 0, 0, 0, 5_000).resolve(0, 0);
        assert!(r.valid()); // marginCall==0 is legal (warning disabled)
    }

    #[test]
    fn resolved_valid_rejects_margin_call_at_or_below_initial() {
        let r = symbol(1, 5_000, 8_000, 5_000, 0, 0, 5_000).resolve(0, 0);
        assert!(!r.valid());
    }

    #[test]
    fn resolved_valid_rejects_liquidation_not_above_initial_or_at_bps_full() {
        assert!(!symbol(1, 5_000, 5_000, 0, 0, 0, 5_000).resolve(0, 0).valid());
        assert!(!symbol(1, 5_000, BPS_FULL, 0, 0, 0, 5_000).resolve(0, 0).valid());
    }

    #[test]
    fn resolved_valid_zero_initial_is_the_kill_switch_and_always_valid() {
        // initial==0 short-circuits thresholds_valid entirely (kill-switch symbol).
        let r = symbol(1, 0, UNSET, UNSET, UNSET_AMOUNT, UNSET, UNSET).resolve(2_000, 1_000);
        assert_eq!(r.initial_ltv_bps, 0);
        assert!(r.valid());
    }

    #[test]
    fn resolved_valid_rejects_negative_max_amount_or_term() {
        // -1 is the UNSET sentinel (resolves to 0, always valid) — use -2 to get a genuinely
        // negative value past resolve() and into Resolved::valid()'s own guard.
        assert!(!symbol(1, 5_000, 8_000, 0, -2, 0, 5_000).resolve(0, 0).valid());
        assert!(!symbol(1, 5_000, 8_000, 0, 0, -2, 5_000).resolve(0, 0).valid());
    }

    /// **前置义务收口测试**：collateralWeightBps 越界（>10000 或 <0）必须被拒——这是 Task 5
    /// `trunc_mul_div` overflow-panic "当前不可达" 论断成立的唯一前提，`ADD_LOAN` 是唯一能写这
    /// 个字段的命令，必须在这里守住。
    #[test]
    fn resolved_valid_rejects_collateral_weight_out_of_bps_range() {
        assert!(!symbol(1, 5_000, 8_000, 0, 0, 0, BPS_FULL + 1).resolve(0, 0).valid());
        assert!(!symbol(1, 5_000, 8_000, 0, 0, 0, -2).resolve(0, 0).valid());
        // Exactly at the boundary is still legal.
        assert!(symbol(1, 5_000, 8_000, 0, 0, 0, BPS_FULL).resolve(0, 0).valid());
        assert!(symbol(1, 5_000, 8_000, 0, 0, 0, 0).resolve(0, 0).valid());
    }

    // ================================================================
    // RateCurveConfig::valid
    // ================================================================

    #[test]
    fn rate_curve_valid_accepts_in_range_curve() {
        let rc = RateCurveConfig { base_bps: 200, kink_util_bps: 8000, slope1_bps: 400, slope2_bps: 6000,
            locked_rate_adjust_bps: -50 };
        assert!(rc.valid());
    }

    #[test]
    fn rate_curve_valid_rejects_base_out_of_range() {
        let mut rc = RateCurveConfig { base_bps: -1, kink_util_bps: 8000, slope1_bps: 0, slope2_bps: 0,
            locked_rate_adjust_bps: 0 };
        assert!(!rc.valid());
        rc.base_bps = BPS_FULL;
        assert!(!rc.valid());
    }

    #[test]
    fn rate_curve_valid_rejects_kink_at_boundaries() {
        let mut rc = RateCurveConfig { base_bps: 0, kink_util_bps: 0, slope1_bps: 0, slope2_bps: 0,
            locked_rate_adjust_bps: 0 };
        assert!(!rc.valid()); // kink==0 rejected (must be strictly > 0)
        rc.kink_util_bps = BPS_FULL;
        assert!(!rc.valid()); // kink==BPS_FULL rejected (must be strictly < BPS_FULL)
    }

    #[test]
    fn rate_curve_valid_rejects_negative_slopes() {
        let base = RateCurveConfig { base_bps: 0, kink_util_bps: 8000, slope1_bps: 0, slope2_bps: 0,
            locked_rate_adjust_bps: 0 };
        assert!(base.valid());
        assert!(!RateCurveConfig { slope1_bps: -1, ..base }.valid());
        assert!(!RateCurveConfig { slope2_bps: -1, ..base }.valid());
    }
}
