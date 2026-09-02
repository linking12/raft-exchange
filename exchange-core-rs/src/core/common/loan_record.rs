//! 对应 Java: exchange.core2.core.common.LoanRecord —— Isolated / Cross 贷款凭证共享的债务视图，
//! 让 accrue / repay / 强平结算逻辑只写一份（Task 2+ 消费；本 Task 只落接口）。金额均为
//! `loanCurrency` 的 currencyScale。

/// 对应 Java `LoanRecord` 接口。`IsolatedLoanRecord`/`CrossLoanRecord` 均实现之；
/// `LoanService`/`FloatingRateModel`/`FixedRateModel`（Task 2+）都只通过该接口操作 loan。
pub trait LoanRecord {
    fn loan_currency(&self) -> i32;

    /// 借入时锁定的年化利率（bps）。
    fn rate_bps(&self) -> i32;

    fn outstanding_principal(&self) -> i64;
    fn set_outstanding_principal(&mut self, value: i64);

    /// 已计提未付利息，抵债时利息优先。
    fn accumulated_interest(&self) -> i64;
    fn set_accumulated_interest(&mut self, value: i64);

    /// LOCKED 计息起点游标。
    fn last_accrue_ts(&self) -> i64;
    fn set_last_accrue_ts(&mut self, value: i64);

    /// FLOATING 计息游标：上次 accrue 的 liveAcc 快照（bps·ms）。
    fn acc_snapshot(&self) -> i64;
    fn set_acc_snapshot(&mut self, value: i64);

    /// `true` = Fixed 走线性计息 / `false` = Floating 走累加器；Cross 恒 `false`。
    fn is_fixed_rate(&self) -> bool;

    /// 累计已付利息（单调递增）；FundEvent 只发快照，本次量由相邻两条相减得出。
    fn cum_interest_paid(&self) -> i64;
    fn set_cum_interest_paid(&mut self, value: i64);
}
