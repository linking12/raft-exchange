use std::collections::BTreeMap;

use crate::core::common::loan_record::LoanRecord;
use crate::core::processors::loan::loan_service::{BPS_SCALE, YEAR_MS};
use crate::core::utils::core_arithmetic_utils::{add_exact, mul_exact, sub_exact, trunc_mul_div};

pub const DEFAULT_BASE_BPS: i32 = 200;

pub const DEFAULT_KINK_UTIL_BPS: i32 = 8000;

pub const DEFAULT_SLOPE1_BPS: i32 = 400;

pub const DEFAULT_SLOPE2_BPS: i32 = 6000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FloatingRateModel {
    pub base_bps: i32,
    pub kink_util_bps: i32,
    pub slope1_bps: i32,
    pub slope2_bps: i32,
    pub current_rate_bps: BTreeMap<i32, i64>,
    pub acc_rate_bps_ms: BTreeMap<i32, i64>,
    pub last_reprice_ts: i64,
}

impl FloatingRateModel {

    pub fn utilization_bps(borrowed: i64, available: i64) -> i64 {
        let total = add_exact(borrowed, available);
        if total <= 0 { 0 } else { trunc_mul_div(borrowed, BPS_SCALE, total) }
    }

    pub fn curve_rate_bps(&self, util_bps: i64) -> i64 {
        let util = if util_bps < 0 { 0 } else { util_bps.min(BPS_SCALE) };
        let kink = self.kink_util_bps as i64;
        if util <= kink {
            let seg = if kink <= 0 { 0 } else { (self.slope1_bps as i64 * util) / kink };
            self.base_bps as i64 + seg
        } else {
            let denom = BPS_SCALE - kink;
            let seg = if denom <= 0 { 0 } else { (self.slope2_bps as i64 * (util - kink)) / denom };
            self.base_bps as i64 + self.slope1_bps as i64 + seg
        }
    }

    pub fn advance_accumulator(&mut self, currency: i32, tick_ts: i64) {
        if self.last_reprice_ts > 0 && tick_ts > self.last_reprice_ts {
            let elapsed = tick_ts - self.last_reprice_ts;

            let advance = mul_exact(self.current_rate_bps_or_base(currency) as i64, elapsed);
            let cur = *self.acc_rate_bps_ms.get(&currency).unwrap_or(&0);
            self.acc_rate_bps_ms.insert(currency, add_exact(cur, advance));
        }
    }

    pub fn reprice_currency(&mut self, currency: i32, util_bps: i64) {
        let rate = self.curve_rate_bps(util_bps);
        self.current_rate_bps.insert(currency, rate);
    }

    pub fn init_open_snapshot<L: LoanRecord>(&self, loan: &mut L, now: i64) {
        let live = self.live_acc_rate_bps_ms(loan.loan_currency(), now);
        loan.set_acc_snapshot(live);
    }

    pub fn accrue<L: LoanRecord>(&self, loan: &mut L, now: i64) -> i64 {
        let live = self.live_acc_rate_bps_ms(loan.loan_currency(), now);
        let delta = Self::pending_from_live(loan, live);
        if delta > 0 {
            loan.set_accumulated_interest(add_exact(loan.accumulated_interest(), delta));
        }

        let delta_acc = live - loan.acc_snapshot();
        let truncated_but_chargeable = delta == 0 && loan.outstanding_principal() > 0 && delta_acc > 0;
        if !truncated_but_chargeable {
            loan.set_acc_snapshot(live);
        }
        delta
    }

    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        h = h.wrapping_mul(31).wrapping_add(self.base_bps as i64);
        h = h.wrapping_mul(31).wrapping_add(self.kink_util_bps as i64);
        h = h.wrapping_mul(31).wrapping_add(self.slope1_bps as i64);
        h = h.wrapping_mul(31).wrapping_add(self.slope2_bps as i64);
        for (&cur, &rate) in &self.current_rate_bps {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(rate);
        }
        for (&cur, &acc) in &self.acc_rate_bps_ms {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(acc);
        }
        h = h.wrapping_mul(31).wrapping_add(self.last_reprice_ts);
        ((h >> 32) as i32) ^ (h as i32)
    }

    pub fn current_rate_bps_or_base(&self, currency: i32) -> i32 {
        match self.current_rate_bps.get(&currency) {
            Some(&v) => v as i32,
            None => self.base_bps,
        }
    }

    pub fn open_rate_bps(&self, loan_currency: i32) -> i32 {
        self.current_rate_bps_or_base(loan_currency)
    }

    pub fn live_acc_rate_bps_ms(&self, currency: i32, now: i64) -> i64 {
        let acc = *self.acc_rate_bps_ms.get(&currency).unwrap_or(&0);
        let elapsed = now - self.last_reprice_ts;
        if self.last_reprice_ts <= 0 || elapsed <= 0 {
            return acc;
        }
        add_exact(acc, mul_exact(self.current_rate_bps_or_base(currency) as i64, elapsed))
    }

    pub fn pending_interest<L: LoanRecord>(&self, loan: &L, now: i64) -> i64 {
        let live = self.live_acc_rate_bps_ms(loan.loan_currency(), now);
        Self::pending_from_live(loan, live)
    }

    pub fn display_interest<L: LoanRecord>(&self, loan: &L, now: i64) -> i64 {
        let live = self.live_acc_rate_bps_ms(loan.loan_currency(), now);
        add_exact(loan.accumulated_interest(), Self::pending_from_live(loan, live))
    }

    pub fn set_last_reprice_ts(&mut self, ts: i64) {
        self.last_reprice_ts = ts;
    }

    fn pending_from_live<L: LoanRecord>(loan: &L, live_acc: i64) -> i64 {
        let delta_acc = sub_exact(live_acc, loan.acc_snapshot());
        if delta_acc <= 0 || loan.outstanding_principal() <= 0 {
            0
        } else {
            trunc_mul_div(delta_acc, loan.outstanding_principal(), YEAR_MS * BPS_SCALE)
        }
    }
}

impl Default for FloatingRateModel {
    fn default() -> Self {
        FloatingRateModel {
            base_bps: DEFAULT_BASE_BPS,
            kink_util_bps: DEFAULT_KINK_UTIL_BPS,
            slope1_bps: DEFAULT_SLOPE1_BPS,
            slope2_bps: DEFAULT_SLOPE2_BPS,
            current_rate_bps: BTreeMap::new(),
            acc_rate_bps_ms: BTreeMap::new(),
            last_reprice_ts: 0,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_matches_java_curve_defaults_and_cold_start() {
        let m = FloatingRateModel::default();
        assert_eq!(m.base_bps, 200);
        assert_eq!(m.kink_util_bps, 8000);
        assert_eq!(m.slope1_bps, 400);
        assert_eq!(m.slope2_bps, 6000);
        assert!(m.current_rate_bps.is_empty());
        assert!(m.acc_rate_bps_ms.is_empty());
        assert_eq!(m.last_reprice_ts, 0);
    }

    #[test]
    fn state_hash_deterministic_and_sensitive_to_bucket_change() {
        let a = FloatingRateModel::default();
        let b = FloatingRateModel::default();
        assert_eq!(a.state_hash(), b.state_hash());

        let mut c = a.clone();
        c.current_rate_bps.insert(1, 250);
        assert_ne!(a.state_hash(), c.state_hash());
    }

    use crate::core::common::isolated_loan_record::{IsolatedLoanRecord, LoanRateMode};

    fn floating_loan(currency: i32, principal: i64, opened_at: i64) -> IsolatedLoanRecord {
        let mut r = IsolatedLoanRecord::new(1, 1, 100, 10, currency, 0, opened_at);
        r.rate_mode = LoanRateMode::Floating;
        r.set_outstanding_principal(principal);
        r
    }

    #[test]
    fn curve_rate_bps_below_kink_is_linear_from_base() {
        let m = FloatingRateModel::default();
        assert_eq!(m.curve_rate_bps(4000), 400);
        assert_eq!(m.curve_rate_bps(0), 200);
    }

    #[test]
    fn curve_rate_bps_at_and_above_kink_switches_to_slope2() {
        let m = FloatingRateModel::default();
        assert_eq!(m.curve_rate_bps(8000), 600);
        assert_eq!(m.curve_rate_bps(9000), 3600);
        assert_eq!(m.curve_rate_bps(10000), 600 + 6000);
    }

    #[test]
    fn curve_rate_bps_clamps_out_of_range_util() {
        let m = FloatingRateModel::default();
        assert_eq!(m.curve_rate_bps(-500), m.curve_rate_bps(0));
        assert_eq!(m.curve_rate_bps(20_000), m.curve_rate_bps(10_000));
    }

    #[test]
    fn utilization_bps_empty_pool_is_zero_and_scales_correctly() {
        assert_eq!(FloatingRateModel::utilization_bps(0, 0), 0);
        assert_eq!(FloatingRateModel::utilization_bps(5_000, 5_000), 5_000);
        assert_eq!(FloatingRateModel::utilization_bps(8_000, 2_000), 8_000);
    }

    #[test]
    fn additive_accumulator_two_loans_opened_at_different_times_use_own_snapshot() {
        let cur = 7;
        let mut model = FloatingRateModel::default();
        model.last_reprice_ts = 1_000;
        model.current_rate_bps.insert(cur, 500);

        let principal = 315_360_000_000;
        let mut loan_a = floating_loan(cur, principal, 1_000);
        model.init_open_snapshot(&mut loan_a, 1_000);
        assert_eq!(loan_a.acc_snapshot(), 0);

        let mut loan_b = floating_loan(cur, principal, 2_000);
        model.init_open_snapshot(&mut loan_b, 2_000);
        assert_eq!(loan_b.acc_snapshot(), 500_000);

        assert_eq!(model.pending_interest(&loan_a, 3_000), 1_000);
        assert_eq!(model.pending_interest(&loan_b, 3_000), 500);
    }

    #[test]
    fn accrue_advances_snapshot_and_accumulates_interest_across_calls() {
        let cur = 4;
        let mut model = FloatingRateModel::default();
        model.last_reprice_ts = 1_000;
        model.current_rate_bps.insert(cur, 500);
        let mut loan = floating_loan(cur, 315_360_000_000, 1_000);
        model.init_open_snapshot(&mut loan, 1_000);

        let d1 = model.accrue(&mut loan, 2_000);
        assert_eq!(d1, 500);
        assert_eq!(loan.accumulated_interest(), 500);
        assert_eq!(loan.acc_snapshot(), 500_000);

        let d2 = model.accrue(&mut loan, 3_000);
        assert_eq!(d2, 500);
        assert_eq!(loan.accumulated_interest(), 1_000);
    }

    #[test]
    fn accrue_truncated_but_chargeable_freezes_snapshot_until_threshold_crossed() {
        let cur = 9;
        let mut model = FloatingRateModel::default();
        model.current_rate_bps.insert(cur, 1);
        model.last_reprice_ts = 1;
        let principal = 1;
        let mut loan = floating_loan(cur, principal, 1);
        loan.set_acc_snapshot(0);

        let d1 = model.accrue(&mut loan, 100);
        assert_eq!(d1, 0);
        assert_eq!(loan.acc_snapshot(), 0, "F1: snapshot must NOT advance when truncated to 0 but principal>0 and time elapsed");

        let threshold = YEAR_MS * BPS_SCALE;
        let now = 1 + threshold;
        let d2 = model.accrue(&mut loan, now);
        assert!(d2 > 0, "sub-threshold interest must eventually be charged, not lost forever");
        assert_eq!(loan.acc_snapshot(), threshold, "snapshot advances once interest is actually charged");
    }

    #[test]
    fn advance_accumulator_before_reprice_settles_old_interval_at_old_rate() {
        let cur = 3;
        let mut base = FloatingRateModel::default();
        base.last_reprice_ts = 1_000;
        base.current_rate_bps.insert(cur, 300);

        let mut correct = base.clone();
        correct.advance_accumulator(cur, 2_000);
        correct.reprice_currency(cur, 9_000);
        let correct_acc = *correct.acc_rate_bps_ms.get(&cur).unwrap();
        assert_eq!(correct_acc, 300 * 1_000, "old interval must settle at the OLD rate");

        let mut reversed = base.clone();
        reversed.reprice_currency(cur, 9_000);
        reversed.advance_accumulator(cur, 2_000);
        let reversed_acc = *reversed.acc_rate_bps_ms.get(&cur).unwrap();
        let new_rate = reversed.current_rate_bps_or_base(cur);
        assert_ne!(new_rate as i64, 300, "sanity: reprice must actually change the rate for this test to be meaningful");
        assert_eq!(reversed_acc, new_rate as i64 * 1_000, "bug reproduced: old interval mis-costed at the NEW rate");

        assert_ne!(correct_acc, reversed_acc, "ordering must change the outcome");
    }

    #[test]
    fn open_rate_bps_and_current_rate_bps_or_base_fallback_to_base_when_never_repriced() {
        let m = FloatingRateModel::default();
        assert_eq!(m.open_rate_bps(42), m.base_bps);
        assert_eq!(m.current_rate_bps_or_base(42), 200);
    }
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

impl ChronicleMarshallable for FloatingRateModel {

    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_i32(self.base_bps);
        w.write_i32(self.kink_util_bps);
        w.write_i32(self.slope1_bps);
        w.write_i32(self.slope2_bps);
        w.write_int_long_map(&self.current_rate_bps);
        w.write_int_long_map(&self.acc_rate_bps_ms);
        w.write_i64(self.last_reprice_ts);
    }

    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        Ok(FloatingRateModel {
            base_bps: r.read_i32()?,
            kink_util_bps: r.read_i32()?,
            slope1_bps: r.read_i32()?,
            slope2_bps: r.read_i32()?,
            current_rate_bps: crate::core::snapshot::marshalling::to_btree_i32(r.read_int_long_map()?),
            acc_rate_bps_ms: crate::core::snapshot::marshalling::to_btree_i32(r.read_int_long_map()?),
            last_reprice_ts: r.read_i64()?,
        })
    }
}

#[cfg(test)]
mod java_parity {
    use super::*;

    fn model_with(base: i32, kink: i32, s1: i32, s2: i32) -> FloatingRateModel {
        let mut m = FloatingRateModel::default();
        m.base_bps = base;
        m.kink_util_bps = kink;
        m.slope1_bps = s1;
        m.slope2_bps = s2;
        m
    }

    #[test]
    fn curve_at_zero_util_is_base() {
        assert_eq!(FloatingRateModel::default().curve_rate_bps(0), 200);
    }

    #[test]
    fn curve_below_kink_linear_on_slope1() {
        assert_eq!(FloatingRateModel::default().curve_rate_bps(4000), 400);
    }

    #[test]
    fn curve_at_kink_is_base_plus_slope1() {
        assert_eq!(FloatingRateModel::default().curve_rate_bps(8000), 600);
    }

    #[test]
    fn curve_above_kink_steep_on_slope2() {
        assert_eq!(FloatingRateModel::default().curve_rate_bps(9000), 3600);
    }

    #[test]
    fn curve_at_full_util_is_base_plus_both_slopes() {
        assert_eq!(FloatingRateModel::default().curve_rate_bps(10000), 6600);
    }

    #[test]
    fn curve_clamps_out_of_range_util() {
        let m = FloatingRateModel::default();
        assert_eq!(m.curve_rate_bps(-5), 200, "negative util is clamped to 0");
        assert_eq!(m.curve_rate_bps(20000), 6600, "util above 100% is clamped to BPS_SCALE");
    }

    #[test]
    fn curve_kink_zero_whole_range_is_slope2() {
        assert_eq!(model_with(100, 0, 400, 600).curve_rate_bps(5000), 800);
    }

    #[test]
    fn curve_kink_at_bps_scale_no_slope2_segment() {
        let m = model_with(200, 10000, 400, 6000);
        assert_eq!(m.curve_rate_bps(10000), 600, "kink=10000 at full utilization -> base+slope1");
        assert_eq!(m.curve_rate_bps(20000), 600, "still base+slope1 after out-of-range clamp");
    }

    #[test]
    fn utilization_basic() {
        assert_eq!(FloatingRateModel::utilization_bps(0, 0), 0, "empty pool");
        assert_eq!(FloatingRateModel::utilization_bps(30, 70), 3000, "30/(30+70)=30%");
        assert_eq!(FloatingRateModel::utilization_bps(100, 0), 10000, "fully borrowed = 100%");
    }

    #[test]
    fn utilization_overflow_scale_path_uses_trunc_mul_div_128() {
        let half = i64::MAX / 2;
        assert_eq!(FloatingRateModel::utilization_bps(half, half), 5000, "50% after overflow fallback path");
    }

    #[test]
    fn floating_open_rate_falls_back_to_base_when_unpriced_then_uses_current() {
        let mut m = FloatingRateModel::default();
        assert_eq!(m.open_rate_bps(2), 200, "before any reprice -> falls back to curve base=200");
        m.current_rate_bps.insert(2, 555);
        assert_eq!(m.open_rate_bps(2), 555, "after reprice -> uses the effective rate");
    }

    #[test]
    fn live_acc_cold_start_returns_acc_unchanged() {
        let mut m = FloatingRateModel::default();
        m.acc_rate_bps_ms.insert(2, 987_654);
        m.current_rate_bps.insert(2, 555);
        m.last_reprice_ts = 0;
        assert_eq!(m.live_acc_rate_bps_ms(2, 1_000_000), 987_654, "cold start: even with now>0, returns the raw acc value");
    }

    #[test]
    fn live_acc_non_positive_elapsed_returns_acc_unchanged() {
        let mut m = FloatingRateModel::default();
        m.acc_rate_bps_ms.insert(2, 987_654);
        m.current_rate_bps.insert(2, 555);
        m.last_reprice_ts = 1_000;
        assert_eq!(m.live_acc_rate_bps_ms(2, 1_000), 987_654, "elapsed=0 returns the raw acc value");
        assert_eq!(m.live_acc_rate_bps_ms(2, 500), 987_654, "elapsed<0 returns the raw acc value");
        assert_eq!(m.live_acc_rate_bps_ms(2, 2_000), 987_654 + 555 * 1_000, "elapsed>0 accumulates normally");
    }

    #[test]
    fn advance_accumulator_cold_start_is_noop() {
        let mut m = FloatingRateModel::default();
        m.acc_rate_bps_ms.insert(2, 100);
        m.current_rate_bps.insert(2, 555);
        m.last_reprice_ts = 0;
        m.advance_accumulator(2, 5_000);
        assert_eq!(*m.acc_rate_bps_ms.get(&2).unwrap(), 100, "cold start: advance is a no-op");
    }

    #[test]
    fn advance_accumulator_tick_not_after_last_reprice_is_noop() {
        let mut m = FloatingRateModel::default();
        m.acc_rate_bps_ms.insert(2, 100);
        m.current_rate_bps.insert(2, 555);
        m.last_reprice_ts = 1_000;
        m.advance_accumulator(2, 1_000);
        assert_eq!(*m.acc_rate_bps_ms.get(&2).unwrap(), 100, "tick_ts=last_reprice_ts is a no-op");
        m.advance_accumulator(2, 500);
        assert_eq!(*m.acc_rate_bps_ms.get(&2).unwrap(), 100, "tick_ts<last_reprice_ts is a no-op");
    }

    #[test]
    fn advance_accumulator_positive_accumulates_rate_times_elapsed() {
        let mut m = FloatingRateModel::default();
        m.acc_rate_bps_ms.insert(2, 100);
        m.current_rate_bps.insert(2, 555);
        m.last_reprice_ts = 1_000;
        m.advance_accumulator(2, 3_000);
        assert_eq!(*m.acc_rate_bps_ms.get(&2).unwrap(), 100 + 555 * 2_000, "accumulates 555 x (3000-1000)");
    }
}
