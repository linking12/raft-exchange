//! 对应 Java `BatchAddLoanCommand`（`ADD_LOAN` 配置命令 DTO+校验，参考文档 §2.12）：三段独立可选独立校验，一段非法跳过不影响另外两段；不移植 Chronicle 序列化。

/// bps 满量程（100%）。对应 Java `BatchAddLoanCommand.BPS_FULL`。
pub const BPS_FULL: i32 = 10_000;

/// 对应 Java `BatchAddLoanCommand`：三段独立可选；三段全 `None` 时下游是纯 no-op。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct BatchAddLoanCommand {
    pub global: Option<GlobalLoanConfig>,
    pub symbol: Option<SymbolLoanConfig>,
    pub rate_curve: Option<RateCurveConfig>,
}

// ========================================================================
// GlobalLoanConfig —— 参考文档 §2.12 第一段
// ========================================================================

/// 对应 Java `BatchAddLoanCommand.GlobalLoanConfig`：partial-update 字段，`<=0` = 不改。
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
    /// 对应 Java `thresholdsValidGivenCurrent(int, int)`（`:199-209`）：校验应用更新后生效的有效值（未提供的沿用当前值）。
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
/// `loan_max_amount`（`i64`）专用的 `UNSET` 哨兵值（类型不同于 `UNSET`）。
pub const UNSET_AMOUNT: i64 = -1;

/// 对应 Java `BatchAddLoanCommand.SymbolLoanConfig`：per-symbol override 字段；`UNSET` = 未指定，`resolve` 时派生。
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
    /// 对应 Java `resolve(int liqBufferBps, int mcBufferBps)`（`:283-290`）：把 `UNSET` 字段填实成 [`Resolved`]；`marginCall` 默认值依赖已派生的 `liq`，不是原始字段。
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
    /// 对应 Java `Resolved.valid()`（`:269-274`）：LTV/金额/期限范围校验；`collateralWeightBps ∈ [0,10000]` 是 `LoanService::cross_ltv_bps` 免 overflow-panic 的前置义务收口点。
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

/// 对应 Java `BatchAddLoanCommand.RateCurveConfig`：存在即整体替换（无 partial-update，`0` 是合法曲线值）。
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
    /// 对应 Java `RateCurveConfig.valid()`（`:323-326`）：`base ∈ [0,10000)`、`0 < kink < 10000`、`slope1/slope2 >= 0`。
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

    /// 前置义务收口测试：collateralWeightBps 越界（>10000 或 <0）必须被拒。
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
