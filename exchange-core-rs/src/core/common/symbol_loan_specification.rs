//! 对应 Java: exchange.core2.core.common.SymbolLoanSpecification —— per-symbol 现货借贷配置，
//! 挂在 `CoreSymbolSpecification::loan_config` 上；全 0 = 该 pair 未启用。仅 `ADD_LOAN`（参考
//! §2.12，P5 Task 6 已落地 `RiskEngine::apply_add_loan`）经 `update` 改写。利率不在此（是
//! per-loanCurrency 池级概念，归 `LoanService`）。

/// 对应 Java `SymbolLoanSpecification`。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, serde::Serialize, serde::Deserialize)]
pub struct SymbolLoanSpecification {
    /// 开仓 LTV 上限；`0` = 借贷未启用。
    pub initial_ltv_bps: i32,
    /// Isolated 单笔强平触发线（Cross 用 `LoanGlobalConfig` 全局阈值）。
    pub liquidation_ltv_bps: i32,
    /// Isolated 预警线；`0` = 关闭。
    pub margin_call_ltv_bps: i32,
    /// 单笔本金上限；`0` = 无上限。
    pub max_amount: i64,
    /// 最大贷款期限（天）；`0` = 无期限（仅 Isolated LOCKED 生效）。
    pub max_term_days: i32,
}

impl SymbolLoanSpecification {
    /// 对应 Java `isEnabled()`（`:54-56`）：`initialLtvBps > 0` 才算启用。
    pub fn is_enabled(&self) -> bool {
        self.initial_ltv_bps > 0
    }

    /// 对应 Java `update(...)`（`:59-66`）：唯一 mutation point；调用方已完成字段层校验。
    pub fn update(
        &mut self,
        initial_ltv_bps: i32,
        liquidation_ltv_bps: i32,
        margin_call_ltv_bps: i32,
        max_amount: i64,
        max_term_days: i32,
    ) {
        self.initial_ltv_bps = initial_ltv_bps;
        self.liquidation_ltv_bps = liquidation_ltv_bps;
        self.margin_call_ltv_bps = margin_call_ltv_bps;
        self.max_amount = max_amount;
        self.max_term_days = max_term_days;
    }

    /// 对应 Java `stateHash()`（`:78-80`）：风格对齐仓内其余 model 类型的滚动折叠。
    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        h = h.wrapping_mul(31).wrapping_add(self.initial_ltv_bps as i64);
        h = h.wrapping_mul(31).wrapping_add(self.liquidation_ltv_bps as i64);
        h = h.wrapping_mul(31).wrapping_add(self.margin_call_ltv_bps as i64);
        h = h.wrapping_mul(31).wrapping_add(self.max_amount);
        h = h.wrapping_mul(31).wrapping_add(self.max_term_days as i64);
        ((h >> 32) as i32) ^ (h as i32)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_is_disabled_and_zeroed() {
        let spec = SymbolLoanSpecification::default();
        assert!(!spec.is_enabled());
        assert_eq!(spec.initial_ltv_bps, 0);
        assert_eq!(spec.liquidation_ltv_bps, 0);
        assert_eq!(spec.margin_call_ltv_bps, 0);
        assert_eq!(spec.max_amount, 0);
        assert_eq!(spec.max_term_days, 0);
    }

    #[test]
    fn is_enabled_tracks_initial_ltv_bps_positivity() {
        let mut spec = SymbolLoanSpecification::default();
        assert!(!spec.is_enabled());
        spec.initial_ltv_bps = 1;
        assert!(spec.is_enabled());
        spec.initial_ltv_bps = 0;
        assert!(!spec.is_enabled());
    }

    #[test]
    fn update_writes_all_five_fields() {
        let mut spec = SymbolLoanSpecification::default();
        spec.update(7000, 8500, 8000, 1_000_000, 30);
        assert_eq!(spec.initial_ltv_bps, 7000);
        assert_eq!(spec.liquidation_ltv_bps, 8500);
        assert_eq!(spec.margin_call_ltv_bps, 8000);
        assert_eq!(spec.max_amount, 1_000_000);
        assert_eq!(spec.max_term_days, 30);
        assert!(spec.is_enabled());
    }

    #[test]
    fn state_hash_deterministic_and_sensitive_to_field_change() {
        let mut a = SymbolLoanSpecification::default();
        a.update(7000, 8500, 8000, 1_000_000, 30);
        let mut b = SymbolLoanSpecification::default();
        b.update(7000, 8500, 8000, 1_000_000, 30);
        assert_eq!(a.state_hash(), b.state_hash());

        let mut c = a;
        c.max_amount = 2_000_000;
        assert_ne!(a.state_hash(), c.state_hash());
    }
}
