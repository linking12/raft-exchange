//! 对应 Java: exchange.core2.core.common.CrossLoanRecord —— Cross 单笔债务凭证，挂在
//! `UserProfile::cross_loans`。无抵押字段——Cross 抵押是账户级的，多笔 debt 共享
//! `UserProfile::cross_loan_collateral` 池。
use crate::core::common::loan_record::LoanRecord;

/// 对应 Java `CrossLoanRecord`。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct CrossLoanRecord {
    // ── 身份
    /// 所属用户（上下文注入，不参与序列化，只进 state_hash）。
    pub uid: i64,
    /// 客户端提供，per-user 唯一，创建时锁死（与 Isolated `loan_id` 是独立命名空间）。
    pub loan_id: i64,

    // ── 开仓条款：存续期不变
    /// 匹配的现货 pair，scanner 据此取 spec。
    pub symbol_id: i32,
    /// = 该 pair 的 quoteCurrency。
    pub loan_currency: i32,
    /// 借入时锁定的年化利率（bps）。
    pub rate_bps: i32,
    /// 开仓时间戳（ms），期限校验用。
    pub opened_at_ts: i64,

    // ── 债务：随借还、计息、强平变动。抵押不在此处——见 UserProfile::cross_loan_collateral
    /// 剩余未偿本金（loanCurrency）。
    pub outstanding_principal: i64,
    /// 已计提未付利息（loanCurrency），结算时进 interestRevenue。
    pub accumulated_interest: i64,
    /// 上次计息时间戳（ms），初始 = opened_at_ts；Cross 恒 FLOATING，此游标不参与计息。
    pub last_accrue_ts: i64,
    /// FLOATING 计息游标：上次 accrue 的 liveAcc 快照（bps·ms）。
    pub acc_snapshot: i64,

    // ── 累计量：FundEvent 只发快照，本次量由下游相邻两条相减得出
    /// 累计已付利息（loanCurrency）。
    pub cum_interest_paid: i64,
}

impl CrossLoanRecord {
    /// 对应 Java `CrossLoanRecord(uid, loanId, symbolId, loanCurrency, rateBps, openedAtTs)`
    /// 构造器：直接调用 `initialize`。
    pub fn new(uid: i64, loan_id: i64, symbol_id: i32, loan_currency: i32, rate_bps: i32, opened_at_ts: i64) -> Self {
        let mut r = CrossLoanRecord::default();
        r.initialize(uid, loan_id, symbol_id, loan_currency, rate_bps, opened_at_ts);
        r
    }

    /// 对应 Java `initialize(...)`：复用一条记录前必须先重置 identity + 可变状态。
    pub fn initialize(&mut self, uid: i64, loan_id: i64, symbol_id: i32, loan_currency: i32, rate_bps: i32, opened_at_ts: i64) {
        self.uid = uid;
        self.loan_id = loan_id;
        self.symbol_id = symbol_id;
        self.loan_currency = loan_currency;
        self.rate_bps = rate_bps;
        self.opened_at_ts = opened_at_ts;
        self.outstanding_principal = 0;
        self.accumulated_interest = 0;
        self.last_accrue_ts = opened_at_ts;
        self.acc_snapshot = 0;
        self.cum_interest_paid = 0;
    }

    /// 对应 Java `isEmpty()`：无抵押字段，仅本金+利息全 0 才算空。
    pub fn is_empty(&self) -> bool {
        self.outstanding_principal == 0 && self.accumulated_interest == 0
    }

    /// 对应 Java `stateHash()`（`:174-178`）：`Objects.hash` 覆盖的 10 个字段，风格对齐
    /// `IsolatedLoanRecord::state_hash`（`h=h*31+field` 滚动折叠）。
    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        h = h.wrapping_mul(31).wrapping_add(self.uid);
        h = h.wrapping_mul(31).wrapping_add(self.loan_id);
        h = h.wrapping_mul(31).wrapping_add(self.symbol_id as i64);
        h = h.wrapping_mul(31).wrapping_add(self.loan_currency as i64);
        h = h.wrapping_mul(31).wrapping_add(self.rate_bps as i64);
        h = h.wrapping_mul(31).wrapping_add(self.opened_at_ts);
        h = h.wrapping_mul(31).wrapping_add(self.outstanding_principal);
        h = h.wrapping_mul(31).wrapping_add(self.accumulated_interest);
        h = h.wrapping_mul(31).wrapping_add(self.last_accrue_ts);
        h = h.wrapping_mul(31).wrapping_add(self.acc_snapshot);
        h = h.wrapping_mul(31).wrapping_add(self.cum_interest_paid);
        ((h >> 32) as i32) ^ (h as i32)
    }
}

impl LoanRecord for CrossLoanRecord {
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

    /// Cross 恒 Floating。
    fn is_fixed_rate(&self) -> bool {
        false
    }

    fn cum_interest_paid(&self) -> i64 {
        self.cum_interest_paid
    }

    fn set_cum_interest_paid(&mut self, value: i64) {
        self.cum_interest_paid = value;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_initializes_zeroed_debt() {
        let r = CrossLoanRecord::new(1, 2, 100, 20, 500, 1_000);
        assert_eq!(r.uid, 1);
        assert_eq!(r.loan_id, 2);
        assert_eq!(r.symbol_id, 100);
        assert_eq!(r.loan_currency, 20);
        assert_eq!(r.rate_bps, 500);
        assert_eq!(r.opened_at_ts, 1_000);
        assert_eq!(r.outstanding_principal, 0);
        assert_eq!(r.accumulated_interest, 0);
        assert_eq!(r.last_accrue_ts, 1_000);
        assert_eq!(r.acc_snapshot, 0);
        assert_eq!(r.cum_interest_paid, 0);
    }

    #[test]
    fn is_empty_true_only_when_principal_and_interest_zero() {
        let mut r = CrossLoanRecord::new(1, 2, 100, 20, 500, 1_000);
        assert!(r.is_empty());
        r.outstanding_principal = 1;
        assert!(!r.is_empty());
        r.outstanding_principal = 0;
        r.accumulated_interest = 1;
        assert!(!r.is_empty());
    }

    #[test]
    fn is_fixed_rate_always_false() {
        let r = CrossLoanRecord::new(1, 2, 100, 20, 500, 1_000);
        assert!(!r.is_fixed_rate());
    }

    #[test]
    fn state_hash_deterministic_and_sensitive_to_field_change() {
        let base = CrossLoanRecord::new(1, 2, 100, 20, 500, 1_000);
        let same = CrossLoanRecord::new(1, 2, 100, 20, 500, 1_000);
        assert_eq!(base.state_hash(), same.state_hash());

        let mut diff = base.clone();
        diff.outstanding_principal = 10;
        assert_ne!(base.state_hash(), diff.state_hash());
    }

    #[test]
    fn loan_record_trait_getters_setters_round_trip() {
        let mut r = CrossLoanRecord::new(1, 2, 100, 20, 500, 1_000);
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
}
