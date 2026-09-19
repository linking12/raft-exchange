use std::collections::BTreeMap;

use crate::core::common::symbol_loan_specification::SymbolLoanSpecification;
use crate::core::common::symbol_type::SymbolType;
use crate::core::utils::core_arithmetic_utils::{add_exact, ceil_mul_div, trunc_mul_div};

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct CoreSymbolSpecification {
    pub symbol_id: i32,
    pub symbol_type: SymbolType,
    pub base_currency: i32,
    pub quote_currency: i32,
    pub base_scale_k: i64,
    pub quote_scale_k: i64,
    pub taker_fee: i64,
    pub maker_fee: i64,
    pub fee_scale_k: i64,
    pub liquidation_fee: i64,
    pub init_margin: i64,
    pub init_margin_scale_k: i64,
    pub maintenance_margin: BTreeMap<i64, i64>,
    pub maintenance_margin_scale_k: i64,
    pub max_leverage: BTreeMap<i64, i64>,
    pub loan_config: SymbolLoanSpecification,
}

impl CoreSymbolSpecification {

    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        h = h.wrapping_mul(31).wrapping_add(self.symbol_id as i64);
        h = h.wrapping_mul(31).wrapping_add(self.symbol_type.code() as i64);
        h = h.wrapping_mul(31).wrapping_add(self.base_currency as i64);
        h = h.wrapping_mul(31).wrapping_add(self.quote_currency as i64);
        h = h.wrapping_mul(31).wrapping_add(self.base_scale_k);
        h = h.wrapping_mul(31).wrapping_add(self.quote_scale_k);
        h = h.wrapping_mul(31).wrapping_add(self.taker_fee);
        h = h.wrapping_mul(31).wrapping_add(self.maker_fee);
        h = h.wrapping_mul(31).wrapping_add(self.liquidation_fee);
        h = h.wrapping_mul(31).wrapping_add(self.fee_scale_k);
        for (&k, &v) in &self.maintenance_margin {
            h = h.wrapping_mul(31).wrapping_add(k);
            h = h.wrapping_mul(31).wrapping_add(v);
        }
        h = h.wrapping_mul(31).wrapping_add(self.maintenance_margin_scale_k);
        for (&k, &v) in &self.max_leverage {
            h = h.wrapping_mul(31).wrapping_add(k);
            h = h.wrapping_mul(31).wrapping_add(v);
        }
        h = h.wrapping_mul(31).wrapping_add(self.loan_config.state_hash() as i64);
        ((h >> 32) as i32) ^ (h as i32)
    }

    pub fn is_fixed_fee(&self) -> bool {
        self.fee_scale_k == 0
    }

    pub fn calculate_init_margin(&self, notional: i64, leverage: i64) -> i64 {
        if self.init_margin_scale_k == 0 || self.init_margin == 0 {
            return notional / leverage;
        }

        let denom = self
            .init_margin_scale_k
            .checked_mul(leverage)
            .unwrap_or_else(|| panic!("overflow: init_margin_scale_k * leverage"));
        ceil_mul_div(notional, self.init_margin, denom)
    }

    pub fn calculate_maintenance_margin(&self, notional: i64) -> i64 {
        if self.maintenance_margin_scale_k == 0 || self.maintenance_margin.is_empty() {
            return notional;
        }
        let mut iter = self.maintenance_margin.iter();
        let (&first_floor, &first_rate) = iter.next().expect("non-empty checked above");
        if notional <= first_floor {
            return trunc_mul_div(notional, first_rate, self.maintenance_margin_scale_k);
        }
        let mut mm: i64 = 0;
        let mut prev_floor: i64 = 0;
        let mut prev_rate: i64 = first_rate;
        for (&floor, &rate) in self.maintenance_margin.iter() {
            let seg = notional.min(floor) - prev_floor;
            mm = add_exact(mm, trunc_mul_div(seg, prev_rate, self.maintenance_margin_scale_k));
            if notional <= floor {
                return mm;
            }
            prev_floor = floor;
            prev_rate = rate;
        }
        add_exact(mm, trunc_mul_div(notional - prev_floor, prev_rate, self.maintenance_margin_scale_k))
    }

    pub fn is_valid_leverage(&self, notional: i64, leverage: i32) -> bool {
        if leverage < 0 {
            return false;
        }
        if self.max_leverage.is_empty() {
            return true;
        }
        match Self::floor_value(&self.max_leverage, notional) {
            Some(max_leverage_value) => (leverage as i64) <= max_leverage_value,
            None => true,
        }
    }

    fn floor_value(map: &BTreeMap<i64, i64>, key: i64) -> Option<i64> {
        match map.range(..key).next_back() {
            Some((_, &v)) => Some(v),
            None => map.iter().next().map(|(_, &v)| v),
        }
    }
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

impl ChronicleMarshallable for CoreSymbolSpecification {
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_i32(self.symbol_id);
        w.write_u8(self.symbol_type.code() as u8);
        w.write_i32(self.base_currency);
        w.write_i32(self.quote_currency);
        w.write_i64(self.base_scale_k);
        w.write_i64(self.quote_scale_k);
        w.write_i64(self.taker_fee);
        w.write_i64(self.maker_fee);
        w.write_i64(self.liquidation_fee);
        w.write_i64(self.fee_scale_k);
        w.write_i64(self.init_margin);
        w.write_i64(self.init_margin_scale_k);
        w.write_long_long_treemap(&self.maintenance_margin);
        w.write_i64(self.maintenance_margin_scale_k);
        w.write_long_long_treemap(&self.max_leverage);
        self.loan_config.chronicle_write(w);
    }
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let symbol_id = r.read_i32()?;
        let symbol_type = SymbolType::of_code(r.read_u8()? as i8);
        let base_currency = r.read_i32()?;
        let quote_currency = r.read_i32()?;
        let base_scale_k = r.read_i64()?;
        let quote_scale_k = r.read_i64()?;
        let taker_fee = r.read_i64()?;
        let maker_fee = r.read_i64()?;
        let liquidation_fee = r.read_i64()?;
        let fee_scale_k = r.read_i64()?;
        let init_margin = r.read_i64()?;
        let init_margin_scale_k = r.read_i64()?;
        let maintenance_margin = crate::core::snapshot::marshalling::to_btree_i64(r.read_long_long_treemap()?);
        let maintenance_margin_scale_k = r.read_i64()?;
        let max_leverage = crate::core::snapshot::marshalling::to_btree_i64(r.read_long_long_treemap()?);
        let loan_config = SymbolLoanSpecification::chronicle_read(r)?;
        Ok(CoreSymbolSpecification {
            symbol_id, symbol_type, base_currency, quote_currency, base_scale_k, quote_scale_k,
            taker_fee, maker_fee, fee_scale_k, liquidation_fee, init_margin, init_margin_scale_k,
            maintenance_margin, maintenance_margin_scale_k, max_leverage, loan_config,
            ..Default::default()
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn state_hash_deterministic_and_sensitive_to_fee_and_loan_config() {
        let base = spot_spec(1_000_000);
        assert_eq!(base.state_hash(), base.clone().state_hash());
        let mut fee_changed = base.clone();
        fee_changed.taker_fee += 1;
        assert_ne!(base.state_hash(), fee_changed.state_hash());
        let mut loan_changed = base.clone();
        loan_changed.loan_config.initial_ltv_bps += 1;
        assert_ne!(base.state_hash(), loan_changed.state_hash());
    }

    fn spot_spec(fee_scale_k: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: 1,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 10,
            maker_fee: 5,
            fee_scale_k,
            ..Default::default()
        }
    }

    fn futures_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: 1,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        }
    }

    #[test]
    fn is_fixed_fee_true_when_fee_scale_k_zero() {
        assert!(spot_spec(0).is_fixed_fee());
    }

    #[test]
    fn is_fixed_fee_false_when_fee_scale_k_positive() {
        assert!(!spot_spec(10_000).is_fixed_fee());
    }

    #[test]
    fn default_futures_fields_are_zero_and_empty() {
        let s = spot_spec(0);
        assert_eq!(s.init_margin, 0);
        assert_eq!(s.init_margin_scale_k, 0);
        assert!(s.maintenance_margin.is_empty());
        assert_eq!(s.maintenance_margin_scale_k, 0);
        assert!(s.max_leverage.is_empty());
    }

    #[test]
    fn calculate_init_margin_default_notional_over_leverage_when_unconfigured() {
        let spec = futures_spec();
        assert_eq!(spec.calculate_init_margin(10_000, 10), 1_000);
    }

    #[test]
    fn calculate_init_margin_proportional_tier_ceils() {
        let mut spec = futures_spec();
        spec.init_margin = 500;
        spec.init_margin_scale_k = 10_000;
        assert_eq!(spec.calculate_init_margin(10_000, 3), 167);
    }

    #[test]
    fn calculate_init_margin_zero_init_margin_falls_back_to_default() {
        let mut spec = futures_spec();
        spec.init_margin = 0;
        spec.init_margin_scale_k = 10_000;
        assert_eq!(spec.calculate_init_margin(10_000, 5), 2_000);
    }

    #[test]
    fn calculate_maintenance_margin_returns_notional_when_unconfigured() {
        let spec = futures_spec();
        assert_eq!(spec.calculate_maintenance_margin(12_345), 12_345);
    }

    #[test]
    fn calculate_maintenance_margin_below_first_floor_uses_first_rate_flat() {
        let mut spec = futures_spec();
        spec.maintenance_margin_scale_k = 10_000;
        spec.maintenance_margin.insert(50_000, 50);
        spec.maintenance_margin.insert(200_000, 100);
        assert_eq!(spec.calculate_maintenance_margin(10_000), 50);
    }

    #[test]
    fn calculate_maintenance_margin_tiers_across_segments() {
        let mut spec = futures_spec();
        spec.maintenance_margin_scale_k = 10_000;
        spec.maintenance_margin.insert(50_000, 50);
        spec.maintenance_margin.insert(200_000, 100);
        assert_eq!(spec.calculate_maintenance_margin(100_000), 500);
    }

    #[test]
    fn calculate_maintenance_margin_beyond_last_tier_uses_last_rate() {
        let mut spec = futures_spec();
        spec.maintenance_margin_scale_k = 10_000;
        spec.maintenance_margin.insert(50_000, 50);
        spec.maintenance_margin.insert(200_000, 100);
        assert_eq!(spec.calculate_maintenance_margin(300_000), 2000);
    }

    #[test]
    fn is_valid_leverage_negative_leverage_always_invalid() {
        let spec = futures_spec();
        assert!(!spec.is_valid_leverage(1_000, -1));
    }

    #[test]
    fn is_valid_leverage_unconfigured_table_allows_any_nonnegative_leverage() {
        let spec = futures_spec();
        assert!(spec.is_valid_leverage(1_000_000, 125));
    }

    #[test]
    fn is_valid_leverage_floor_lookup_strictly_less_than_key() {
        let mut spec = futures_spec();
        spec.max_leverage.insert(0, 125);
        spec.max_leverage.insert(50_000, 50);
        spec.max_leverage.insert(200_000, 20);

        assert!(spec.is_valid_leverage(10_000, 125));
        assert!(!spec.is_valid_leverage(10_000, 126));

        assert!(spec.is_valid_leverage(50_000, 125));

        assert!(spec.is_valid_leverage(50_001, 50));
        assert!(!spec.is_valid_leverage(50_001, 51));
    }

    #[test]
    fn is_valid_leverage_key_below_min_floor_falls_back_to_min_floor_value() {
        let mut spec = futures_spec();
        spec.max_leverage.insert(10_000, 100);
        spec.max_leverage.insert(50_000, 50);
        assert!(spec.is_valid_leverage(0, 100));
        assert!(!spec.is_valid_leverage(0, 101));
    }
}
