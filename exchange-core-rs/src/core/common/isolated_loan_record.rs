use crate::core::common::loan_record::LoanRecord;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LoanRateMode {
    Locked,
    Floating,
}

impl LoanRateMode {
    pub fn code(self) -> i8 {
        match self {
            LoanRateMode::Locked => 0,
            LoanRateMode::Floating => 1,
        }
    }

    pub fn of_code(code: i8) -> Self {
        match code {
            0 => LoanRateMode::Locked,
            1 => LoanRateMode::Floating,
            other => panic!("unknown LoanRateMode code: {other}"),
        }
    }
}

impl Default for LoanRateMode {
    fn default() -> Self {
        LoanRateMode::Locked
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct IsolatedLoanRecord {
    pub uid: i64,
    pub loan_id: i64,
    pub symbol_id: i32,
    pub collateral_currency: i32,
    pub loan_currency: i32,
    pub rate_mode: LoanRateMode,
    pub rate_bps: i32,
    pub opened_at_ts: i64,
    pub collateral_amount: i64,
    pub outstanding_principal: i64,
    pub accumulated_interest: i64,
    pub last_accrue_ts: i64,
    pub acc_snapshot: i64,
    pub cum_interest_paid: i64,
}

impl IsolatedLoanRecord {
    #[allow(clippy::too_many_arguments)]
    pub fn new(
        uid: i64,
        loan_id: i64,
        symbol_id: i32,
        collateral_currency: i32,
        loan_currency: i32,
        rate_bps: i32,
        opened_at_ts: i64,
    ) -> Self {
        let mut r = IsolatedLoanRecord::default();
        r.initialize(uid, loan_id, symbol_id, collateral_currency, loan_currency, rate_bps, opened_at_ts);
        r
    }

    #[allow(clippy::too_many_arguments)]
    pub fn initialize(
        &mut self,
        uid: i64,
        loan_id: i64,
        symbol_id: i32,
        collateral_currency: i32,
        loan_currency: i32,
        rate_bps: i32,
        opened_at_ts: i64,
    ) {
        self.uid = uid;
        self.loan_id = loan_id;
        self.symbol_id = symbol_id;
        self.collateral_currency = collateral_currency;
        self.loan_currency = loan_currency;
        self.rate_mode = LoanRateMode::Locked;
        self.rate_bps = rate_bps;
        self.opened_at_ts = opened_at_ts;
        self.collateral_amount = 0;
        self.outstanding_principal = 0;
        self.accumulated_interest = 0;
        self.last_accrue_ts = opened_at_ts;
        self.acc_snapshot = 0;
        self.cum_interest_paid = 0;
    }

    pub fn is_empty(&self) -> bool {
        self.collateral_amount == 0 && self.outstanding_principal == 0 && self.accumulated_interest == 0
    }

    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        h = h.wrapping_mul(31).wrapping_add(self.uid);
        h = h.wrapping_mul(31).wrapping_add(self.loan_id);
        h = h.wrapping_mul(31).wrapping_add(self.symbol_id as i64);
        h = h.wrapping_mul(31).wrapping_add(self.collateral_currency as i64);
        h = h.wrapping_mul(31).wrapping_add(self.loan_currency as i64);
        h = h.wrapping_mul(31).wrapping_add(self.rate_mode.code() as i64);
        h = h.wrapping_mul(31).wrapping_add(self.rate_bps as i64);
        h = h.wrapping_mul(31).wrapping_add(self.opened_at_ts);
        h = h.wrapping_mul(31).wrapping_add(self.collateral_amount);
        h = h.wrapping_mul(31).wrapping_add(self.outstanding_principal);
        h = h.wrapping_mul(31).wrapping_add(self.accumulated_interest);
        h = h.wrapping_mul(31).wrapping_add(self.last_accrue_ts);
        h = h.wrapping_mul(31).wrapping_add(self.acc_snapshot);
        h = h.wrapping_mul(31).wrapping_add(self.cum_interest_paid);
        ((h >> 32) as i32) ^ (h as i32)
    }
}

impl LoanRecord for IsolatedLoanRecord {
    fn loan_currency(&self) -> i32 {
        self.loan_currency
    }

    fn rate_bps(&self) -> i32 {
        self.rate_bps
    }

    fn outstanding_principal(&self) -> i64 {
        self.outstanding_principal
    }

    fn set_outstanding_principal(&mut self, value: i64) {
        self.outstanding_principal = value;
    }

    fn accumulated_interest(&self) -> i64 {
        self.accumulated_interest
    }

    fn set_accumulated_interest(&mut self, value: i64) {
        self.accumulated_interest = value;
    }

    fn last_accrue_ts(&self) -> i64 {
        self.last_accrue_ts
    }

    fn set_last_accrue_ts(&mut self, value: i64) {
        self.last_accrue_ts = value;
    }

    fn acc_snapshot(&self) -> i64 {
        self.acc_snapshot
    }

    fn set_acc_snapshot(&mut self, value: i64) {
        self.acc_snapshot = value;
    }

    fn is_fixed_rate(&self) -> bool {
        matches!(self.rate_mode, LoanRateMode::Locked)
    }

    fn cum_interest_paid(&self) -> i64 {
        self.cum_interest_paid
    }

    fn set_cum_interest_paid(&mut self, value: i64) {
        self.cum_interest_paid = value;
    }
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

impl ChronicleMarshallable for IsolatedLoanRecord {
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_i64(self.loan_id);
        w.write_i32(self.symbol_id);
        w.write_i32(self.collateral_currency);
        w.write_i32(self.loan_currency);
        w.write_u8(self.rate_mode.code() as u8);
        w.write_i32(self.rate_bps);
        w.write_i64(self.opened_at_ts);
        w.write_i64(self.collateral_amount);
        w.write_i64(self.outstanding_principal);
        w.write_i64(self.accumulated_interest);
        w.write_i64(self.last_accrue_ts);
        w.write_i64(self.acc_snapshot);
        w.write_i64(self.cum_interest_paid);
    }
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        Ok(IsolatedLoanRecord {
            uid: 0,
            loan_id: r.read_i64()?,
            symbol_id: r.read_i32()?,
            collateral_currency: r.read_i32()?,
            loan_currency: r.read_i32()?,
            rate_mode: LoanRateMode::of_code(r.read_u8()? as i8),
            rate_bps: r.read_i32()?,
            opened_at_ts: r.read_i64()?,
            collateral_amount: r.read_i64()?,
            outstanding_principal: r.read_i64()?,
            accumulated_interest: r.read_i64()?,
            last_accrue_ts: r.read_i64()?,
            acc_snapshot: r.read_i64()?,
            cum_interest_paid: r.read_i64()?,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_initializes_zeroed_debt_and_locked_mode() {
        let r = IsolatedLoanRecord::new(1, 2, 100, 10, 20, 500, 1_000);
        assert_eq!(r.uid, 1);
        assert_eq!(r.loan_id, 2);
        assert_eq!(r.symbol_id, 100);
        assert_eq!(r.collateral_currency, 10);
        assert_eq!(r.loan_currency, 20);
        assert_eq!(r.rate_mode, LoanRateMode::Locked);
        assert_eq!(r.rate_bps, 500);
        assert_eq!(r.opened_at_ts, 1_000);
        assert_eq!(r.collateral_amount, 0);
        assert_eq!(r.outstanding_principal, 0);
        assert_eq!(r.accumulated_interest, 0);
        assert_eq!(r.last_accrue_ts, 1_000);
        assert_eq!(r.acc_snapshot, 0);
        assert_eq!(r.cum_interest_paid, 0);
    }

    #[test]
    fn is_empty_true_only_when_all_three_amounts_zero() {
        let mut r = IsolatedLoanRecord::new(1, 2, 100, 10, 20, 500, 1_000);
        assert!(r.is_empty());

        r.collateral_amount = 1;
        assert!(!r.is_empty());
        r.collateral_amount = 0;

        r.outstanding_principal = 1;
        assert!(!r.is_empty());
        r.outstanding_principal = 0;

        r.accumulated_interest = 1;
        assert!(!r.is_empty());
        r.accumulated_interest = 0;

        assert!(r.is_empty());
    }

    #[test]
    fn is_fixed_rate_tracks_rate_mode() {
        let mut r = IsolatedLoanRecord::new(1, 2, 100, 10, 20, 500, 1_000);
        assert!(r.is_fixed_rate());
        r.rate_mode = LoanRateMode::Floating;
        assert!(!r.is_fixed_rate());
    }

    #[test]
    fn state_hash_deterministic_and_sensitive_to_each_field() {
        let base = IsolatedLoanRecord::new(1, 2, 100, 10, 20, 500, 1_000);
        let same = IsolatedLoanRecord::new(1, 2, 100, 10, 20, 500, 1_000);
        assert_eq!(base.state_hash(), same.state_hash());

        let mut diff_collateral = base.clone();
        diff_collateral.collateral_amount = 5;
        assert_ne!(base.state_hash(), diff_collateral.state_hash());

        let mut diff_mode = base.clone();
        diff_mode.rate_mode = LoanRateMode::Floating;
        assert_ne!(base.state_hash(), diff_mode.state_hash());
    }

    #[test]
    fn loan_record_trait_getters_setters_round_trip() {
        let mut r = IsolatedLoanRecord::new(1, 2, 100, 10, 20, 500, 1_000);
        assert_eq!(LoanRecord::loan_currency(&r), 20);
        assert_eq!(LoanRecord::rate_bps(&r), 500);

        r.set_outstanding_principal(1_000);
        assert_eq!(r.outstanding_principal(), 1_000);

        r.set_accumulated_interest(50);
        assert_eq!(r.accumulated_interest(), 50);

        r.set_last_accrue_ts(2_000);
        assert_eq!(r.last_accrue_ts(), 2_000);

        r.set_acc_snapshot(77);
        assert_eq!(r.acc_snapshot(), 77);

        r.set_cum_interest_paid(9);
        assert_eq!(r.cum_interest_paid(), 9);
    }

    #[test]
    fn loan_rate_mode_codes_and_default_match_java() {
        assert_eq!(LoanRateMode::Locked.code(), 0);
        assert_eq!(LoanRateMode::Floating.code(), 1);
        assert_eq!(LoanRateMode::of_code(0), LoanRateMode::Locked);
        assert_eq!(LoanRateMode::of_code(1), LoanRateMode::Floating);
        assert_eq!(LoanRateMode::default(), LoanRateMode::Locked);
    }
}
