//! 对应 Java: exchange.core2.core.processors.loan.LoanService —— per-shard 单例，
//! 纯状态 + 纯函数工具类，不持有 `RiskEngine` 引用（`loan.md` line 105）。
//!
//! **Task 1 范围**：仅字段 + 构造 + `state_hash` + 桶存取。accrue/debt-payment/LTV/
//! LIF-takeover/scale 换算/orderId 编码等纯函数（Java `:60-497`）留 Task 2/4，参考文档
//! `docs/superpowers/specs/2026-09-02-p5-loan-reference.md` §1.6/§3/§4/§6。
use std::collections::BTreeMap;

use crate::core::common::loan_record::LoanRecord;
use crate::core::processors::loan::loan_global_config::LoanGlobalConfig;
use crate::core::processors::loan::rate::fixed_rate_model::FixedRateModel;
use crate::core::processors::loan::rate::floating_rate_model::FloatingRateModel;

/// 对应 Java `LoanService.YEAR_MS`（`:41`）：1 年（ms），跨节点唯一确定性形式，不依赖日历/闰年。
pub const YEAR_MS: i64 = 365 * 24 * 3600 * 1_000;
/// 对应 Java `LoanService.BPS_SCALE`（`:42`）：bps 精度基准（10000 = 100%）。
pub const BPS_SCALE: i64 = 10_000;

/// 对应 Java `LoanService`（字段子集，`:51-59`）。
///
/// 4 个资金桶（进 raft snapshot，参与全局守恒对账，参考文档 §6）：
/// - `loan_pool_available`：各币种可借余额（currency scale）。
/// - `loan_pool_borrowed`：各币种已借出本金（currency scale）——**tracker，不参与守恒**（其
///   对应资金已在借款人 `accounts` 里，见参考文档 §6.2 "excludes loanPoolBorrowed"）。
/// - `interest_revenue`：利息收入（currency scale）。
/// - `loan_insurance_fund`：LIF 保险基金，允许为负（LIF 接管坏账的被动结果，非运营透支）。
///
/// Java 侧对应类型是 `IntLongHashMap`；本移植用 `BTreeMap<i32,i64>` 保持确定性迭代序
/// （仓库铁律：状态/输出禁 HashMap）。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct LoanService {
    pub loan_pool_available: BTreeMap<i32, i64>,
    pub loan_pool_borrowed: BTreeMap<i32, i64>,
    pub interest_revenue: BTreeMap<i32, i64>,
    pub loan_insurance_fund: BTreeMap<i32, i64>,
    /// 全局运行时配置（Cross 阈值 / pool 利用率上限 / numeraire）。
    pub global_config: LoanGlobalConfig,
    /// 活期利率：Isolated FLOATING + 全部 Cross。
    pub floating_rate: FloatingRateModel,
    /// 定期利率：Isolated LOCKED，开仓时锚定 `floating_rate` 当前利率
    /// （移植偏差见 `rate::fixed_rate_model` 模块文档：不持有 `floating_rate` 的引用字段）。
    pub fixed_rate: FixedRateModel,
}

impl LoanService {
    /// 对应 Java `LoanService()` 构造器（`:61-69`）：全部桶空、`globalConfig`/两个利率模型各自
    /// 默认值。
    pub fn new() -> Self {
        LoanService::default()
    }

    // ================================================================
    // 桶存取 —— 对应 Java `IntLongHashMap.get(currency)` / `.addToValue(currency, delta)`
    // 缺省语义（缺省 0，`delta` 可为负）
    // ================================================================

    pub fn get_loan_pool_available(&self, currency: i32) -> i64 {
        *self.loan_pool_available.get(&currency).unwrap_or(&0)
    }

    pub fn add_to_loan_pool_available(&mut self, currency: i32, delta: i64) {
        *self.loan_pool_available.entry(currency).or_insert(0) += delta;
    }

    pub fn get_loan_pool_borrowed(&self, currency: i32) -> i64 {
        *self.loan_pool_borrowed.get(&currency).unwrap_or(&0)
    }

    pub fn add_to_loan_pool_borrowed(&mut self, currency: i32, delta: i64) {
        *self.loan_pool_borrowed.entry(currency).or_insert(0) += delta;
    }

    pub fn get_interest_revenue(&self, currency: i32) -> i64 {
        *self.interest_revenue.get(&currency).unwrap_or(&0)
    }

    pub fn add_to_interest_revenue(&mut self, currency: i32, delta: i64) {
        *self.interest_revenue.entry(currency).or_insert(0) += delta;
    }

    pub fn get_loan_insurance_fund(&self, currency: i32) -> i64 {
        *self.loan_insurance_fund.get(&currency).unwrap_or(&0)
    }

    pub fn add_to_loan_insurance_fund(&mut self, currency: i32, delta: i64) {
        *self.loan_insurance_fund.entry(currency).or_insert(0) += delta;
    }

    // ================================================================
    // 利率模型二分派 —— 对应 Java `LoanService.accrueTo`/`calculateDisplayInterest`
    // （`:123-131`）：按 `loan.isFixedRate()` 分派到 `fixed_rate`/`floating_rate`，
    // 2 处调用点，if/else 即可，不需要多态。
    // ================================================================

    /// 写路径：把截至 `now` 的利息补计进 `loan.accumulated_interest` 并推进游标
    /// （`acc_snapshot` 或 `last_accrue_ts`，按利率模型而定）；返回本次新增利息（≥ 0）。
    pub fn accrue_to<L: LoanRecord>(&self, loan: &mut L, now: i64) -> i64 {
        if loan.is_fixed_rate() {
            self.fixed_rate.accrue(loan, now)
        } else {
            self.floating_rate.accrue(loan, now)
        }
    }

    /// 读路径：返回 `accumulated_interest` 加上截至 `now` 的 pending 利息，不推进游标、
    /// 不改 loan（展示、强平判定等只读场景用）。
    pub fn calculate_display_interest<L: LoanRecord>(&self, loan: &L, now: i64) -> i64 {
        if loan.is_fixed_rate() {
            self.fixed_rate.display_interest(loan, now)
        } else {
            self.floating_rate.display_interest(loan, now)
        }
    }

    /// 确定性状态 hash：折叠排序后的 4 个资金桶 + `global_config`/`floating_rate`/`fixed_rate`
    /// 各自的 `state_hash()`。风格对齐 `UserProfile::state_hash`（`h=h*31+field` 滚动折叠）；
    /// 不保证与 Java `Objects.hash(...)`-style 数值相等，只保证「同状态 -> 同 hash，不同状态 ->
    /// 不同 hash」。
    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        for (&cur, &amt) in &self.loan_pool_available {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(amt);
        }
        for (&cur, &amt) in &self.loan_pool_borrowed {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(amt);
        }
        for (&cur, &amt) in &self.interest_revenue {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(amt);
        }
        for (&cur, &amt) in &self.loan_insurance_fund {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(amt);
        }
        h = h.wrapping_mul(31).wrapping_add(self.global_config.state_hash() as i64);
        h = h.wrapping_mul(31).wrapping_add(self.floating_rate.state_hash() as i64);
        h = h.wrapping_mul(31).wrapping_add(self.fixed_rate.state_hash() as i64);
        ((h >> 32) as i32) ^ (h as i32)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_has_empty_buckets_and_default_config() {
        let s = LoanService::new();
        assert!(s.loan_pool_available.is_empty());
        assert!(s.loan_pool_borrowed.is_empty());
        assert!(s.interest_revenue.is_empty());
        assert!(s.loan_insurance_fund.is_empty());
        assert_eq!(s.global_config, LoanGlobalConfig::default());
        assert_eq!(s.floating_rate, FloatingRateModel::default());
        assert_eq!(s.fixed_rate, FixedRateModel::default());
    }

    #[test]
    fn get_defaults_to_zero_for_unseen_currency() {
        let s = LoanService::new();
        assert_eq!(s.get_loan_pool_available(1), 0);
        assert_eq!(s.get_loan_pool_borrowed(1), 0);
        assert_eq!(s.get_interest_revenue(1), 0);
        assert_eq!(s.get_loan_insurance_fund(1), 0);
    }

    #[test]
    fn add_to_loan_pool_available_accumulates_and_allows_negative_delta() {
        let mut s = LoanService::new();
        s.add_to_loan_pool_available(1, 1_000);
        s.add_to_loan_pool_available(1, -300);
        assert_eq!(s.get_loan_pool_available(1), 700);
        assert_eq!(s.get_loan_pool_available(2), 0); // 未涉及币种仍缺省 0
    }

    #[test]
    fn add_to_loan_pool_borrowed_accumulates() {
        let mut s = LoanService::new();
        s.add_to_loan_pool_borrowed(1, 500);
        s.add_to_loan_pool_borrowed(1, 250);
        assert_eq!(s.get_loan_pool_borrowed(1), 750);
    }

    #[test]
    fn add_to_interest_revenue_accumulates() {
        let mut s = LoanService::new();
        s.add_to_interest_revenue(1, 10);
        s.add_to_interest_revenue(1, 5);
        assert_eq!(s.get_interest_revenue(1), 15);
    }

    #[test]
    fn add_to_loan_insurance_fund_allows_negative_balance() {
        let mut s = LoanService::new();
        s.add_to_loan_insurance_fund(1, -100); // LIF 接管坏账场景：允许为负
        assert_eq!(s.get_loan_insurance_fund(1), -100);
        s.add_to_loan_insurance_fund(1, 40);
        assert_eq!(s.get_loan_insurance_fund(1), -60);
    }

    #[test]
    fn state_hash_deterministic_for_same_state() {
        let mut a = LoanService::new();
        a.add_to_loan_pool_available(1, 100);
        let mut b = LoanService::new();
        b.add_to_loan_pool_available(1, 100);
        assert_eq!(a.state_hash(), b.state_hash());
    }

    #[test]
    fn state_hash_changes_with_each_bucket_and_substructure() {
        let base = LoanService::new();
        let h0 = base.state_hash();

        let mut diff_available = LoanService::new();
        diff_available.add_to_loan_pool_available(1, 1);
        assert_ne!(h0, diff_available.state_hash());

        let mut diff_borrowed = LoanService::new();
        diff_borrowed.add_to_loan_pool_borrowed(1, 1);
        assert_ne!(h0, diff_borrowed.state_hash());

        let mut diff_interest = LoanService::new();
        diff_interest.add_to_interest_revenue(1, 1);
        assert_ne!(h0, diff_interest.state_hash());

        let mut diff_if = LoanService::new();
        diff_if.add_to_loan_insurance_fund(1, 1);
        assert_ne!(h0, diff_if.state_hash());

        let mut diff_config = LoanService::new();
        diff_config.global_config.numeraire_currency = 1;
        assert_ne!(h0, diff_config.state_hash());

        let mut diff_floating = LoanService::new();
        diff_floating.floating_rate.base_bps = 999;
        assert_ne!(h0, diff_floating.state_hash());

        let mut diff_fixed = LoanService::new();
        diff_fixed.fixed_rate.locked_rate_adjust_bps = 5;
        assert_ne!(h0, diff_fixed.state_hash());
    }

    use crate::core::common::isolated_loan_record::{IsolatedLoanRecord, LoanRateMode};

    #[test]
    fn accrue_to_dispatches_fixed_loans_to_fixed_rate_model() {
        let s = LoanService::new();
        let mut fixed_loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 5_000 /* 50% */, 0);
        fixed_loan.set_outstanding_principal(1_000_000);
        assert!(fixed_loan.is_fixed_rate()); // default rate_mode = Locked

        let delta = s.accrue_to(&mut fixed_loan, YEAR_MS);

        assert_eq!(delta, 500_000); // simple interest, matches FixedRateModel::accrue directly
        assert_eq!(fixed_loan.accumulated_interest(), 500_000);
    }

    #[test]
    fn accrue_to_dispatches_floating_loans_to_floating_rate_model() {
        let mut s = LoanService::new();
        s.floating_rate.last_reprice_ts = 1_000;
        s.floating_rate.current_rate_bps.insert(20, 500); // 5%

        let mut floating_loan = IsolatedLoanRecord::new(2, 2, 100, 10, 20, 0, 1_000);
        floating_loan.rate_mode = LoanRateMode::Floating;
        floating_loan.set_outstanding_principal(315_360_000_000);
        assert!(!floating_loan.is_fixed_rate());

        let delta = s.accrue_to(&mut floating_loan, 3_000); // 2000ms since last_reprice_ts at 5%

        assert_eq!(delta, 1_000);
        assert_eq!(floating_loan.accumulated_interest(), 1_000);
        assert_eq!(floating_loan.acc_snapshot(), 1_000_000); // cursor advanced to live acc
    }

    #[test]
    fn calculate_display_interest_dispatches_by_is_fixed_rate_and_does_not_mutate() {
        let s = LoanService::new();

        let mut fixed_loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 5_000, 0);
        fixed_loan.set_outstanding_principal(1_000_000);
        assert_eq!(s.calculate_display_interest(&fixed_loan, YEAR_MS), 500_000);
        assert_eq!(fixed_loan.accumulated_interest(), 0); // unchanged: read path

        let mut floating_loan = IsolatedLoanRecord::new(2, 2, 100, 10, 20, 0, 1_000);
        floating_loan.rate_mode = LoanRateMode::Floating;
        floating_loan.set_outstanding_principal(315_360_000_000);
        let mut s2 = LoanService::new();
        s2.floating_rate.last_reprice_ts = 1_000;
        s2.floating_rate.current_rate_bps.insert(20, 500);
        assert_eq!(s2.calculate_display_interest(&floating_loan, 3_000), 1_000);
        assert_eq!(floating_loan.accumulated_interest(), 0); // unchanged: read path
    }
}
