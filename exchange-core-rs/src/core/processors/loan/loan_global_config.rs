//! 对应 Java `LoanGlobalConfig`：现货借贷全局运行时配置（per-shard 单例），仅 `ADD_LOAN` 命令 partial-update 改写。

pub const DEFAULT_CROSS_LIQUIDATION_LTV_BPS: i32 = 8500; // 85%
pub const DEFAULT_CROSS_MARGIN_CALL_LTV_BPS: i32 = 8000; // 80%
pub const DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS: i32 = 9000; // 90%
pub const DEFAULT_LOAN_LIQUIDATION_FEE_BPS: i32 = 200; // 2%
pub const DEFAULT_LTV_LIQUIDATION_BUFFER_BPS: i32 = 2000; // 20%，initial→liquidation 缓冲
pub const DEFAULT_LTV_MARGIN_CALL_BUFFER_BPS: i32 = 1000; // 10%，liquidation→marginCall 缓冲
/// numeraire 未配置的 sentinel 值。
pub const NUMERAIRE_UNSET: i32 = 0;

/// 对应 Java `LoanGlobalConfig`。
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct LoanGlobalConfig {
    /// Cross 估值基准币；未配（`NUMERAIRE_UNSET`）时 Cross BORROW/WITHDRAW fail-close、scanner 跳过。
    pub numeraire_currency: i32,
    /// Cross 账户级强平线。
    pub cross_liquidation_ltv_bps: i32,
    /// Cross 账户级预警线。
    pub cross_margin_call_ltv_bps: i32,
    /// 借贷池利用率上限。
    pub loan_pool_utilization_cap_bps: i32,
    /// 强平专项费率。
    pub loan_liquidation_fee_bps: i32,
    /// Symbol 派生：`liquidationLtv = initialLtv + 本值`。
    pub ltv_liquidation_buffer_bps: i32,
    /// Symbol 派生：`marginCallLtv = liquidationLtv − 本值`。
    pub ltv_margin_call_buffer_bps: i32,
}

impl LoanGlobalConfig {
    /// 对应 Java `reset()`（`:58-66`），也是默认构造值。
    pub fn reset(&mut self) {
        *self = LoanGlobalConfig::default();
    }

    /// 对应 Java `isNumeraireConfigured()`（`:68-71`）：未配则 Cross 借贷 fail-close、scanner 跳过 Cross。
    pub fn is_numeraire_configured(&self) -> bool {
        self.numeraire_currency != NUMERAIRE_UNSET
    }

    /// 对应 Java `stateHash()`（`:84-88`）：风格对齐仓内其余 model 类型的滚动折叠。
    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        h = h.wrapping_mul(31).wrapping_add(self.numeraire_currency as i64);
        h = h.wrapping_mul(31).wrapping_add(self.cross_liquidation_ltv_bps as i64);
        h = h.wrapping_mul(31).wrapping_add(self.cross_margin_call_ltv_bps as i64);
        h = h.wrapping_mul(31).wrapping_add(self.loan_pool_utilization_cap_bps as i64);
        h = h.wrapping_mul(31).wrapping_add(self.loan_liquidation_fee_bps as i64);
        h = h.wrapping_mul(31).wrapping_add(self.ltv_liquidation_buffer_bps as i64);
        h = h.wrapping_mul(31).wrapping_add(self.ltv_margin_call_buffer_bps as i64);
        ((h >> 32) as i32) ^ (h as i32)
    }
}

impl Default for LoanGlobalConfig {
    /// 对应 Java `LoanGlobalConfig()` 构造器：7 个默认值逐字对应 `LoanGlobalConfig.java:28-33`。
    fn default() -> Self {
        LoanGlobalConfig {
            numeraire_currency: NUMERAIRE_UNSET,
            cross_liquidation_ltv_bps: DEFAULT_CROSS_LIQUIDATION_LTV_BPS,
            cross_margin_call_ltv_bps: DEFAULT_CROSS_MARGIN_CALL_LTV_BPS,
            loan_pool_utilization_cap_bps: DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS,
            loan_liquidation_fee_bps: DEFAULT_LOAN_LIQUIDATION_FEE_BPS,
            ltv_liquidation_buffer_bps: DEFAULT_LTV_LIQUIDATION_BUFFER_BPS,
            ltv_margin_call_buffer_bps: DEFAULT_LTV_MARGIN_CALL_BUFFER_BPS,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_matches_java_seven_defaults() {
        let cfg = LoanGlobalConfig::default();
        assert_eq!(cfg.numeraire_currency, 0);
        assert_eq!(cfg.cross_liquidation_ltv_bps, 8500);
        assert_eq!(cfg.cross_margin_call_ltv_bps, 8000);
        assert_eq!(cfg.loan_pool_utilization_cap_bps, 9000);
        assert_eq!(cfg.loan_liquidation_fee_bps, 200);
        assert_eq!(cfg.ltv_liquidation_buffer_bps, 2000);
        assert_eq!(cfg.ltv_margin_call_buffer_bps, 1000);
    }

    #[test]
    fn is_numeraire_configured_tracks_unset_sentinel() {
        let mut cfg = LoanGlobalConfig::default();
        assert!(!cfg.is_numeraire_configured());
        cfg.numeraire_currency = 5;
        assert!(cfg.is_numeraire_configured());
        cfg.numeraire_currency = NUMERAIRE_UNSET;
        assert!(!cfg.is_numeraire_configured());
    }

    #[test]
    fn reset_restores_defaults_after_mutation() {
        let mut cfg = LoanGlobalConfig::default();
        cfg.numeraire_currency = 7;
        cfg.cross_liquidation_ltv_bps = 1;
        cfg.reset();
        assert_eq!(cfg, LoanGlobalConfig::default());
    }

    #[test]
    fn state_hash_deterministic_and_sensitive_to_field_change() {
        let a = LoanGlobalConfig::default();
        let b = LoanGlobalConfig::default();
        assert_eq!(a.state_hash(), b.state_hash());

        let mut c = a;
        c.numeraire_currency = 1;
        assert_ne!(a.state_hash(), c.state_hash());
    }
}
