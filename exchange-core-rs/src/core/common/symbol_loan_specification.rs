#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct SymbolLoanSpecification {
    pub initial_ltv_bps: i32,
    pub liquidation_ltv_bps: i32,
    pub margin_call_ltv_bps: i32,
    pub max_amount: i64,
    pub max_term_days: i32,
}

impl SymbolLoanSpecification {

    pub fn is_enabled(&self) -> bool {
        self.initial_ltv_bps > 0
    }

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

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

impl ChronicleMarshallable for SymbolLoanSpecification {
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_i32(self.initial_ltv_bps);
        w.write_i32(self.liquidation_ltv_bps);
        w.write_i32(self.margin_call_ltv_bps);
        w.write_i64(self.max_amount);
        w.write_i32(self.max_term_days);
    }
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        Ok(SymbolLoanSpecification {
            initial_ltv_bps: r.read_i32()?,
            liquidation_ltv_bps: r.read_i32()?,
            margin_call_ltv_bps: r.read_i32()?,
            max_amount: r.read_i64()?,
            max_term_days: r.read_i32()?,
        })
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
