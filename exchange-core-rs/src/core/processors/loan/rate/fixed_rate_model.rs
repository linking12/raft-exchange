use crate::core::common::loan_record::LoanRecord;
use crate::core::processors::loan::loan_service::{BPS_SCALE, YEAR_MS};
use crate::core::processors::loan::rate::floating_rate_model::FloatingRateModel;
use crate::core::utils::core_arithmetic_utils::{add_exact, trunc_mul_div};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FixedRateModel {
    pub locked_rate_adjust_bps: i32,
}

impl FixedRateModel {

    pub fn reset(&mut self) {
        self.locked_rate_adjust_bps = 0;
    }

    pub fn open_rate_bps(&self, floating: &FloatingRateModel, loan_currency: i32) -> i32 {
        let adjusted =
            floating.current_rate_bps_or_base(loan_currency) as i64 + self.locked_rate_adjust_bps as i64;
        adjusted.max(0) as i32
    }

    pub fn accrue<L: LoanRecord>(&self, loan: &mut L, now: i64) -> i64 {
        let delta =
            Self::accrue_delta(loan.outstanding_principal(), loan.rate_bps(), loan.last_accrue_ts(), now);
        if delta > 0 {
            loan.set_accumulated_interest(add_exact(loan.accumulated_interest(), delta));
        }

        if now > loan.last_accrue_ts()
            && (delta > 0 || loan.outstanding_principal() <= 0 || loan.rate_bps() <= 0)
        {
            loan.set_last_accrue_ts(now);
        }
        delta
    }

    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        h = h.wrapping_mul(31).wrapping_add(self.locked_rate_adjust_bps as i64);
        ((h >> 32) as i32) ^ (h as i32)
    }

    pub fn display_interest<L: LoanRecord>(&self, loan: &L, now: i64) -> i64 {
        let pending =
            Self::accrue_delta(loan.outstanding_principal(), loan.rate_bps(), loan.last_accrue_ts(), now);
        add_exact(loan.accumulated_interest(), pending)
    }

    fn accrue_delta(outstanding_principal: i64, rate_bps: i32, last_accrue_ts: i64, now: i64) -> i64 {
        if outstanding_principal <= 0 || rate_bps <= 0 {
            return 0;
        }
        let elapsed = now - last_accrue_ts;
        if elapsed <= 0 {
            return 0;
        }
        let interest_base = trunc_mul_div(elapsed, outstanding_principal, YEAR_MS);
        trunc_mul_div(interest_base, rate_bps as i64, BPS_SCALE)
    }
}

impl Default for FixedRateModel {
    fn default() -> Self {
        FixedRateModel { locked_rate_adjust_bps: 0 }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_is_zero_spread() {
        assert_eq!(FixedRateModel::default().locked_rate_adjust_bps, 0);
    }

    #[test]
    fn reset_restores_zero_after_mutation() {
        let mut m = FixedRateModel { locked_rate_adjust_bps: 50 };
        m.reset();
        assert_eq!(m, FixedRateModel::default());
    }

    #[test]
    fn state_hash_deterministic_and_sensitive_to_field_change() {
        let a = FixedRateModel::default();
        let b = FixedRateModel::default();
        assert_eq!(a.state_hash(), b.state_hash());

        let c = FixedRateModel { locked_rate_adjust_bps: 25 };
        assert_ne!(a.state_hash(), c.state_hash());
    }

    use crate::core::common::isolated_loan_record::IsolatedLoanRecord;

    #[test]
    fn accrue_one_year_simple_interest_equals_principal_times_rate_over_bps_scale() {
        let mut loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 500 , 0);
        loan.set_outstanding_principal(1_000_000);
        let model = FixedRateModel::default();

        let delta = model.accrue(&mut loan, YEAR_MS);

        assert_eq!(delta, 50_000);
        assert_eq!(loan.accumulated_interest(), 50_000);
        assert_eq!(loan.last_accrue_ts(), YEAR_MS);
    }

    #[test]
    fn accrue_is_additive_across_two_equal_half_year_slices() {
        let mut loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 500, 0);
        loan.set_outstanding_principal(1_000_000);
        let model = FixedRateModel::default();

        model.accrue(&mut loan, YEAR_MS / 2);
        model.accrue(&mut loan, YEAR_MS);

        assert_eq!(loan.accumulated_interest(), 50_000);
    }

    #[test]
    fn display_interest_does_not_mutate_loan() {
        let mut loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 500, 0);
        loan.set_outstanding_principal(1_000_000);
        let model = FixedRateModel::default();

        let pending = model.display_interest(&loan, YEAR_MS);

        assert_eq!(pending, 50_000);
        assert_eq!(loan.accumulated_interest(), 0);
        assert_eq!(loan.last_accrue_ts(), 0);
    }

    #[test]
    fn open_rate_bps_is_floating_current_rate_plus_spread_floored_at_zero() {
        let mut floating = FloatingRateModel::default();
        floating.current_rate_bps.insert(20, 700);

        let model = FixedRateModel { locked_rate_adjust_bps: 50 };
        assert_eq!(model.open_rate_bps(&floating, 20), 750);

        let negative_spread = FixedRateModel { locked_rate_adjust_bps: -900 };
        assert_eq!(negative_spread.open_rate_bps(&floating, 20), 0);

        let default_model = FixedRateModel::default();
        assert_eq!(default_model.open_rate_bps(&floating, 999), floating.base_bps);
    }

    #[test]
    fn accrue_truncated_but_chargeable_freezes_cursor_until_threshold_crossed() {
        let mut loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 5_000 , 0);
        loan.set_outstanding_principal(YEAR_MS);
        let model = FixedRateModel::default();

        let d1 = model.accrue(&mut loan, 1);
        assert_eq!(d1, 0);
        assert_eq!(loan.accumulated_interest(), 0);
        assert_eq!(loan.last_accrue_ts(), 0, "F1: cursor must NOT advance while principal>0, rate>0, but truncated to 0");

        let d2 = model.accrue(&mut loan, 2);
        assert_eq!(d2, 1, "sub-threshold interest from the first call must be recovered, not lost");
        assert_eq!(loan.accumulated_interest(), 1);
        assert_eq!(loan.last_accrue_ts(), 2, "cursor advances once interest is actually charged");
    }

    #[test]
    fn accrue_advances_cursor_even_at_zero_delta_when_principal_or_rate_is_nonpositive() {
        let mut loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 500, 0);
        loan.set_outstanding_principal(0);
        let model = FixedRateModel::default();

        let delta = model.accrue(&mut loan, 1_000);
        assert_eq!(delta, 0);
        assert_eq!(loan.last_accrue_ts(), 1_000, "cursor advances: no principal means interest can never accrue anyway");
    }
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

impl ChronicleMarshallable for FixedRateModel {

    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_i32(self.locked_rate_adjust_bps);
    }

    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        Ok(FixedRateModel { locked_rate_adjust_bps: r.read_i32()? })
    }
}

#[cfg(test)]
mod java_parity {
    use super::*;

    #[test]
    fn fixed_open_rate_applies_adjust_with_floor() {
        let mut floating = FloatingRateModel::default();
        floating.current_rate_bps.insert(2, 500);

        let zero = FixedRateModel { locked_rate_adjust_bps: 0 };
        assert_eq!(zero.open_rate_bps(&floating, 2), 500, "adjust=0 -> same as Floating");

        let plus = FixedRateModel { locked_rate_adjust_bps: 50 };
        assert_eq!(plus.open_rate_bps(&floating, 2), 550, "Fixed = Floating + adjust");

        let minus = FixedRateModel { locked_rate_adjust_bps: -600 };
        assert_eq!(minus.open_rate_bps(&floating, 2), 0, "negative adjust that crosses zero is floored at 0");
    }
}
