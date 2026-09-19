pub const BPS_FULL: i32 = 10_000;

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct BatchAddLoanCommand {
    pub global: Option<GlobalLoanConfig>,
    pub symbol: Option<SymbolLoanConfig>,
    pub rate_curve: Option<RateCurveConfig>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GlobalLoanConfig {
    pub numeraire_currency: i32,
    pub cross_liquidation_ltv_bps: i32,
    pub cross_margin_call_ltv_bps: i32,
    pub loan_pool_utilization_cap_bps: i32,
    pub loan_liquidation_fee_bps: i32,
    pub ltv_liquidation_buffer_bps: i32,
    pub ltv_margin_call_buffer_bps: i32,
}

impl GlobalLoanConfig {

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

pub const UNSET: i32 = -1;

pub const UNSET_AMOUNT: i64 = -1;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SymbolLoanConfig {
    pub symbol_id: i32,
    pub loan_initial_ltv_bps: i32,
    pub loan_liquidation_ltv_bps: i32,
    pub loan_margin_call_ltv_bps: i32,
    pub loan_max_amount: i64,
    pub loan_max_term_days: i32,
    pub collateral_weight_bps: i32,
}

impl SymbolLoanConfig {

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

fn thresholds_valid(initial: i32, margin_call: i32, liquidation: i32) -> bool {
    liquidation > initial
        && liquidation < BPS_FULL
        && (margin_call == 0 || (margin_call > initial && margin_call < liquidation))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RateCurveConfig {
    pub base_bps: i32,
    pub kink_util_bps: i32,
    pub slope1_bps: i32,
    pub slope2_bps: i32,
    pub locked_rate_adjust_bps: i32,
}

impl RateCurveConfig {

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
        let g = global(0, 0, 0, 0, 0, 0, 0);
        assert!(g.thresholds_valid_given_current(8500, 8000));
        assert!(!g.thresholds_valid_given_current(8000, 8500));
    }

    #[test]
    fn global_thresholds_rejects_margin_call_at_or_above_liquidation() {
        let g = global(0, 8000, 8000, 0, 0, 0, 0);
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
        let r = s.resolve(2_000, 1_000);
        assert_eq!(r.liquidation_ltv_bps, 7_000);
        assert_eq!(r.margin_call_ltv_bps, 6_000);
        assert_eq!(r.collateral_weight_bps, 5_000);
        assert_eq!(r.max_amount, 0);
        assert_eq!(r.max_term_days, 0);
        assert!(r.valid());
    }

    #[test]
    fn resolve_preserves_explicit_overrides_over_derivation() {
        let s = symbol(1, 5_000, 8_000, 7_000, 1_000_000, 30, 4_000);
        let r = s.resolve(2_000, 1_000);
        assert_eq!(r.liquidation_ltv_bps, 8_000);
        assert_eq!(r.margin_call_ltv_bps, 7_000);
        assert_eq!(r.collateral_weight_bps, 4_000);
        assert_eq!(r.max_amount, 1_000_000);
        assert_eq!(r.max_term_days, 30);
        assert!(r.valid());
    }

    #[test]
    fn resolve_margin_call_default_depends_on_resolved_liquidation_not_raw_field() {
        let s = symbol(1, 5_000, 8_500, UNSET, UNSET_AMOUNT, UNSET, UNSET);
        let r = s.resolve(2_000, 1_000);
        assert_eq!(r.liquidation_ltv_bps, 8_500);
        assert_eq!(r.margin_call_ltv_bps, 7_500);
    }

    #[test]
    fn resolved_valid_accepts_margin_call_zero_as_disabled_warning() {
        let r = symbol(1, 5_000, 8_000, 0, 0, 0, 5_000).resolve(0, 0);
        assert!(r.valid());
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
        let r = symbol(1, 0, UNSET, UNSET, UNSET_AMOUNT, UNSET, UNSET).resolve(2_000, 1_000);
        assert_eq!(r.initial_ltv_bps, 0);
        assert!(r.valid());
    }

    #[test]
    fn resolved_valid_rejects_negative_max_amount_or_term() {
        assert!(!symbol(1, 5_000, 8_000, 0, -2, 0, 5_000).resolve(0, 0).valid());
        assert!(!symbol(1, 5_000, 8_000, 0, 0, -2, 5_000).resolve(0, 0).valid());
    }

    #[test]
    fn resolved_valid_rejects_collateral_weight_out_of_bps_range() {
        assert!(!symbol(1, 5_000, 8_000, 0, 0, 0, BPS_FULL + 1).resolve(0, 0).valid());
        assert!(!symbol(1, 5_000, 8_000, 0, 0, 0, -2).resolve(0, 0).valid());
        assert!(symbol(1, 5_000, 8_000, 0, 0, 0, BPS_FULL).resolve(0, 0).valid());
        assert!(symbol(1, 5_000, 8_000, 0, 0, 0, 0).resolve(0, 0).valid());
    }

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
        assert!(!rc.valid());
        rc.kink_util_bps = BPS_FULL;
        assert!(!rc.valid());
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
