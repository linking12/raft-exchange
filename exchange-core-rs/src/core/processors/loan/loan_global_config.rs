pub const DEFAULT_CROSS_LIQUIDATION_LTV_BPS: i32 = 8500;

pub const DEFAULT_CROSS_MARGIN_CALL_LTV_BPS: i32 = 8000;

pub const DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS: i32 = 9000;

pub const DEFAULT_LOAN_LIQUIDATION_FEE_BPS: i32 = 200;

pub const DEFAULT_LTV_LIQUIDATION_BUFFER_BPS: i32 = 2000;

pub const DEFAULT_LTV_MARGIN_CALL_BUFFER_BPS: i32 = 1000;

pub const NUMERAIRE_UNSET: i32 = 0;

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

    pub fn reset(&mut self) {
        *self = LoanGlobalConfig::default();
    }

    pub fn is_numeraire_configured(&self) -> bool {
        self.numeraire_currency != NUMERAIRE_UNSET
    }

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

    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_i32(self.numeraire_currency);
        w.write_i32(self.cross_liquidation_ltv_bps);
        w.write_i32(self.cross_margin_call_ltv_bps);
        w.write_i32(self.loan_pool_utilization_cap_bps);
        w.write_i32(self.loan_liquidation_fee_bps);
        w.write_i32(self.ltv_liquidation_buffer_bps);
        w.write_i32(self.ltv_margin_call_buffer_bps);
    }

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
