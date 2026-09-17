pub trait LoanRecord {
    fn loan_currency(&self) -> i32;

    fn rate_bps(&self) -> i32;

    fn outstanding_principal(&self) -> i64;
    fn set_outstanding_principal(&mut self, value: i64);

    fn accumulated_interest(&self) -> i64;
    fn set_accumulated_interest(&mut self, value: i64);

    fn last_accrue_ts(&self) -> i64;
    fn set_last_accrue_ts(&mut self, value: i64);

    fn acc_snapshot(&self) -> i64;
    fn set_acc_snapshot(&mut self, value: i64);

    fn is_fixed_rate(&self) -> bool;

    fn cum_interest_paid(&self) -> i64;
    fn set_cum_interest_paid(&mut self, value: i64);
}
