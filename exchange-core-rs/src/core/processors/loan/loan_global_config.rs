//! 现货借贷全局配置（对应 Java `exchange.core2.core.processors.loan.LoanGlobalConfig`）。
//!
//! per-shard 单例运行时配置：Cross 强平线/预警线、借贷池利用率上限、强平费率、numeraire 基准币。
//! 进 raft snapshot，各 shard 独立维护一份；仅 `ADD_LOAN` 命令按字段逐个 partial-update 改写。

/// Cross 账户级强平线，85%，对应 Java `DEFAULT_CROSS_LIQUIDATION_LTV_BPS`。
pub const DEFAULT_CROSS_LIQUIDATION_LTV_BPS: i32 = 8500;
/// Cross 账户级预警线，80%，对应 Java `DEFAULT_CROSS_MARGIN_CALL_LTV_BPS`。
pub const DEFAULT_CROSS_MARGIN_CALL_LTV_BPS: i32 = 8000;
/// 借贷池利用率上限，90%，对应 Java `DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS`。
pub const DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS: i32 = 9000;
/// 强平专项费率，2%，对应 Java `DEFAULT_LOAN_LIQUIDATION_FEE_BPS`。
pub const DEFAULT_LOAN_LIQUIDATION_FEE_BPS: i32 = 200;
/// Symbol 派生：`liquidation_ltv = initial_ltv + 本值`（20%），对应 Java `DEFAULT_LTV_LIQUIDATION_BUFFER_BPS`。
pub const DEFAULT_LTV_LIQUIDATION_BUFFER_BPS: i32 = 2000;
/// Symbol 派生：`margin_call_ltv = liquidation_ltv − 本值`（10%），对应 Java `DEFAULT_LTV_MARGIN_CALL_BUFFER_BPS`。
pub const DEFAULT_LTV_MARGIN_CALL_BUFFER_BPS: i32 = 1000;
/// numeraire 未配置的 sentinel 值，对应 Java `NUMERAIRE_UNSET`。
pub const NUMERAIRE_UNSET: i32 = 0;

/// 对应 Java `LoanGlobalConfig`。字段语义对应 Java 同名字段：
/// - `numeraire_currency`：Cross 估值基准币；未配（`NUMERAIRE_UNSET`）时 Cross BORROW/WITHDRAW
///   fail-close、scanner 跳过。
/// - `cross_liquidation_ltv_bps` / `cross_margin_call_ltv_bps`：Cross 账户级强平线/预警线。
/// - `loan_pool_utilization_cap_bps`：借贷池利用率上限。
/// - `loan_liquidation_fee_bps`：强平专项费率。
/// - `ltv_liquidation_buffer_bps` / `ltv_margin_call_buffer_bps`：Symbol 派生 LTV 缓冲值。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LoanGlobalConfig {
    pub numeraire_currency: i32,
    pub cross_liquidation_ltv_bps: i32,
    pub cross_margin_call_ltv_bps: i32,
    pub loan_pool_utilization_cap_bps: i32,
    pub loan_liquidation_fee_bps: i32,
    pub ltv_liquidation_buffer_bps: i32,
    pub ltv_margin_call_buffer_bps: i32,
}

impl LoanGlobalConfig {
    /// 对应 Java `reset()`：恢复到默认配置。
    pub fn reset(&mut self) {
        *self = LoanGlobalConfig::default();
    }

    /// 对应 Java `isNumeraireConfigured()`：numeraire 是否已配置；未配则 Cross 借贷 fail-close、
    /// scanner 跳过 Cross。
    pub fn is_numeraire_configured(&self) -> bool {
        self.numeraire_currency != NUMERAIRE_UNSET
    }

    /// 状态哈希；无直接对应 Java 方法体（Java 用 `Objects.hash(...)` 组合全部字段），
    /// 这里等价地对全部 7 个字段按声明顺序做自定义 31 进制滚动哈希。
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

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

impl ChronicleMarshallable for LoanGlobalConfig {
    /// 对应 Java `writeMarshallable(BytesOut)`：按声明顺序依次写 numeraire_currency、
    /// cross_liquidation_ltv_bps、cross_margin_call_ltv_bps、loan_pool_utilization_cap_bps、
    /// loan_liquidation_fee_bps、ltv_liquidation_buffer_bps、ltv_margin_call_buffer_bps（均 i32），
    /// 与 Java 写出顺序一致。
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_i32(self.numeraire_currency);
        w.write_i32(self.cross_liquidation_ltv_bps);
        w.write_i32(self.cross_margin_call_ltv_bps);
        w.write_i32(self.loan_pool_utilization_cap_bps);
        w.write_i32(self.loan_liquidation_fee_bps);
        w.write_i32(self.ltv_liquidation_buffer_bps);
        w.write_i32(self.ltv_margin_call_buffer_bps);
    }
    /// 对应 Java 反序列化构造器 `LoanGlobalConfig(BytesIn)`：按同一顺序读回。
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        Ok(LoanGlobalConfig {
            numeraire_currency: r.read_i32()?,
            cross_liquidation_ltv_bps: r.read_i32()?,
            cross_margin_call_ltv_bps: r.read_i32()?,
            loan_pool_utilization_cap_bps: r.read_i32()?,
            loan_liquidation_fee_bps: r.read_i32()?,
            ltv_liquidation_buffer_bps: r.read_i32()?,
            ltv_margin_call_buffer_bps: r.read_i32()?,
        })
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
