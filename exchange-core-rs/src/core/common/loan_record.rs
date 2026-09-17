/// 对应 Java `exchange.core2.core.common.LoanRecord` 接口。Isolated/Cross 贷款凭证共享的
/// 债务视图,让 accrue/repay/强平结算逻辑只写一份。金额均为 `loan_currency` 的 currency scale。
pub trait LoanRecord {
    fn loan_currency(&self) -> i32;

    /// 对应 Java `getRateBps()`:借入时锁定的年化利率(bps)。
    fn rate_bps(&self) -> i32;

    fn outstanding_principal(&self) -> i64;
    fn set_outstanding_principal(&mut self, value: i64);

    /// 对应 Java `getAccumulatedInterest()`:已计提未付利息,抵债时利息优先扣减。
    fn accumulated_interest(&self) -> i64;
    fn set_accumulated_interest(&mut self, value: i64);

    /// 对应 Java `getLastAccrueTs()`:Fixed(LOCKED)计息模式的起点时间游标。
    fn last_accrue_ts(&self) -> i64;
    fn set_last_accrue_ts(&mut self, value: i64);

    /// 对应 Java `getAccSnapshot()`:Floating 计息模式的游标——上次 accrue 时的
    /// 全局累加器(liveAcc)快照,单位 bps·ms。
    fn acc_snapshot(&self) -> i64;
    fn set_acc_snapshot(&mut self, value: i64);

    /// 对应 Java `isFixedRate()`:true=Fixed,走线性计息;false=Floating,走累加器;
    /// Cross 贷款恒为 false。
    fn is_fixed_rate(&self) -> bool;

    /// 对应 Java `getCumInterestPaid()`:累计已付利息,单调递增;FundEvent 只发快照,
    /// 本次实际发生量由相邻两次快照相减得出。
    fn cum_interest_paid(&self) -> i64;
    fn set_cum_interest_paid(&mut self, value: i64);
}
