//! 对应 Java: exchange.core2.core.processors.loan.LoanService —— per-shard 单例，
//! 纯状态 + 纯函数工具类，不持有 `RiskEngine` 引用（`loan.md` line 105）。
//!
//! **Task 1 范围**：仅字段 + 构造 + `state_hash` + 桶存取。accrue/debt-payment/LTV/
//! LIF-takeover/scale 换算/orderId 编码等纯函数（Java `:60-497`）留 Task 2/4，参考文档
//! `docs/superpowers/specs/2026-09-02-p5-loan-reference.md` §1.6/§3/§4/§6。
use std::collections::BTreeMap;

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::isolated_loan_record::LoanRateMode;
use crate::core::common::loan_record::LoanRecord;
use crate::core::common::user_profile::UserProfile;
use crate::core::processors::loan::loan_global_config::LoanGlobalConfig;
use crate::core::processors::loan::rate::fixed_rate_model::FixedRateModel;
use crate::core::processors::loan::rate::floating_rate_model::FloatingRateModel;
use crate::core::utils::core_arithmetic_utils as arithmetic;

/// 对应 Java `LoanService.YEAR_MS`（`:41`）：1 年（ms），跨节点唯一确定性形式，不依赖日历/闰年。
pub const YEAR_MS: i64 = 365 * 24 * 3600 * 1_000;
/// 对应 Java `LoanService.BPS_SCALE`（`:42`）：bps 精度基准（10000 = 100%）。
pub const BPS_SCALE: i64 = 10_000;

/// 对应 Java `Math.multiplyExact(long, long)`：局部私有重复一份（arithmetic 层零依赖 ruling，
/// 风格对齐 `rate::fixed_rate_model`/`rate::floating_rate_model`/`risk_engine.rs` 各自的同名 helper）。
fn mul_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 * b as i128).unwrap_or_else(|_| panic!("overflow: {a} * {b}"))
}

/// 对应 Java `Math.addExact(long, long)`。
fn add_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 + b as i128).unwrap_or_else(|_| panic!("overflow: {a} + {b}"))
}

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

    // ================================================================
    // Task 4：Isolated 生命周期命令的公共金钱原语 —— 对应 Java `LoanCommandDispatcher.java`
    // 私有静态 `verifyPoolCapacity`（`:1045-1062`）/`disburseLoan`（`:1065-1069`）+
    // `LoanService.java` `applyDebtPayment`（`:138-152`）/`collateralValueInQuoteCurrency`
    // （`:412-420`）。放在 `LoanService` 而非 dispatcher：这四个都是纯粹的"钱怎么走"逻辑，
    // 不涉及 loan 存在性/所有权/LTV 门禁等命令级校验（那些留在
    // `loan_command_dispatcher::LoanCommandDispatcher` 里）。
    // ================================================================

    /// 对应 handleLoanCreate 处 `openRateBps` 的二选一分派（`:186-188`）：按 `rate_mode` 选
    /// floating 当前利率 / fixed 派生利率（fixed 需要显式传 `&self.floating_rate`，见
    /// `rate::fixed_rate_model` 模块文档的移植偏差说明）。
    pub fn open_rate_bps(&self, rate_mode: LoanRateMode, loan_currency: i32) -> i32 {
        match rate_mode {
            LoanRateMode::Floating => self.floating_rate.open_rate_bps(loan_currency),
            LoanRateMode::Locked => self.fixed_rate.open_rate_bps(&self.floating_rate, loan_currency),
        }
    }

    /// 对应 Java 私有静态 `verifyPoolCapacity`（`:1045-1062`）：池容量 + 利用率校验，
    /// `LOAN_CREATE`（本 Task）与 `LOAN_CROSS_BORROW`（Task 5）共用。`available < principal` →
    /// `LoanPoolInsufficient`；否则若 `totalPool>0` 且新利用率（`newBorrowed/totalPool`，两边
    /// 同放大 `BPS_SCALE` 比较避免除法精度损失）超过 `loanPoolUtilizationCapBps` →
    /// `LoanPoolUtilizationExceeded`。`totalPool<=0`（池子从未注资）时跳过利用率检查——与
    /// Java `if (totalPool > 0)` 分支逐字一致。
    pub fn verify_pool_capacity(&self, loan_currency: i32, principal: i64) -> CommandResultCode {
        let available = self.get_loan_pool_available(loan_currency);
        let borrowed = self.get_loan_pool_borrowed(loan_currency);
        if available < principal {
            return CommandResultCode::LoanPoolInsufficient;
        }
        let new_borrowed = add_exact(borrowed, principal);
        let total_pool = add_exact(available, borrowed);
        if total_pool > 0 {
            let new_utilization_scaled = mul_exact(new_borrowed, BPS_SCALE);
            let utilization_cap_scaled =
                mul_exact(total_pool, self.global_config.loan_pool_utilization_cap_bps as i64);
            if new_utilization_scaled > utilization_cap_scaled {
                return CommandResultCode::LoanPoolUtilizationExceeded;
            }
        }
        CommandResultCode::Success
    }

    /// 对应 Java 私有静态 `disburseLoan`（`:1065-1069`）：借款划账——`loanPoolAvailable` →
    /// 用户 `accounts`，`loanPoolBorrowed` 记账 `+principal`（tracker，不参与守恒，见模块文档）。
    pub fn disburse_loan(&mut self, up: &mut UserProfile, loan_currency: i32, principal: i64) {
        up.add_to_account(loan_currency, principal);
        self.add_to_loan_pool_available(loan_currency, -principal);
        self.add_to_loan_pool_borrowed(loan_currency, principal);
    }

    /// 对应 Java `applyDebtPayment`（`:138-152`）：用 `fund` 按利息优先、本金其次抵债，封顶为
    /// 当前未偿本息之和；本金部分回补 `loanPoolAvailable` / 冲减 `loanPoolBorrowed`，利息部分
    /// 计入 `interestRevenue`。返回本次抵扣的利息部分（≥ 0，供调用方发 `LOAN_REPAY`/
    /// `LOAN_LIQUIDATED` 事件——本 Task 未落地事件发送，见 `loan_command_dispatcher` 模块文档
    /// 的事件缺口说明）。`account` 是调用方传入的 `UserProfile::accounts`（对应 Java
    /// `IntLongHashMap account` 参数，非整个 `UserProfile`）——REPAY 与强平结算共用此一处金钱
    /// 逻辑，Cross（Task 5+）复用同一实现。
    pub fn apply_debt_payment<L: LoanRecord>(
        &mut self,
        loan: &mut L,
        account: &mut BTreeMap<i32, i64>,
        fund: i64,
    ) -> i64 {
        let currency = loan.loan_currency();
        let interest_part = fund.min(loan.accumulated_interest());
        let fund_after_interest = fund - interest_part;
        let principal_part = fund_after_interest.min(loan.outstanding_principal());
        let paid = add_exact(interest_part, principal_part);
        *account.entry(currency).or_insert(0) -= paid;
        loan.set_accumulated_interest(loan.accumulated_interest() - interest_part);
        loan.set_outstanding_principal(loan.outstanding_principal() - principal_part);
        loan.set_cum_interest_paid(add_exact(loan.cum_interest_paid(), interest_part)); // 单调累计
        self.add_to_interest_revenue(currency, interest_part);
        self.add_to_loan_pool_available(currency, principal_part);
        self.add_to_loan_pool_borrowed(currency, -principal_part);
        interest_part
    }

    /// 对应 Java 静态 `collateralValueInQuoteCurrency`（`:412-420`）：base amount（base
    /// currencyScale）经 `mark_price` 折算成 quote 等值量（quote currencyScale）——Isolated LTV
    /// 开仓/减抵押判定与 scanner 估值共用。`base_currency_spec`/`quote_currency_spec` 任一缺失
    /// （对应 Java 传 `null`）→ `-1`（价格未就绪，交由上层按各自的 `LOAN_MARKPRICE_NOT_READY`
    /// 语义 skip）。
    pub fn collateral_value_in_quote_currency(
        amount: i64,
        spec: &CoreSymbolSpecification,
        mark_price: i64,
        base_currency_spec: Option<&CoreCurrencySpecification>,
        quote_currency_spec: Option<&CoreCurrencySpecification>,
    ) -> i64 {
        let (base_spec, quote_spec) = match (base_currency_spec, quote_currency_spec) {
            (Some(b), Some(q)) => (b, q),
            _ => return -1,
        };
        let base_amount = arithmetic::convert_scale(amount, base_spec.currency_scale_k, spec.base_scale_k);
        let notional = mul_exact(base_amount, mark_price);
        arithmetic::size_price_to_currency_scale(
            notional,
            spec.base_scale_k,
            spec.quote_scale_k,
            quote_spec.currency_scale_k,
        )
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

    // ====================================================================
    // Task 4：open_rate_bps / verify_pool_capacity / disburse_loan /
    // apply_debt_payment / collateral_value_in_quote_currency
    // ====================================================================

    #[test]
    fn open_rate_bps_dispatches_floating_directly() {
        let mut s = LoanService::new();
        s.floating_rate.current_rate_bps.insert(20, 777);
        assert_eq!(s.open_rate_bps(LoanRateMode::Floating, 20), 777);
    }

    #[test]
    fn open_rate_bps_locked_derives_from_floating_plus_spread() {
        let mut s = LoanService::new();
        s.floating_rate.current_rate_bps.insert(20, 700);
        s.fixed_rate.locked_rate_adjust_bps = 50;
        assert_eq!(s.open_rate_bps(LoanRateMode::Locked, 20), 750);
    }

    #[test]
    fn verify_pool_capacity_rejects_when_available_below_principal() {
        let mut s = LoanService::new();
        s.add_to_loan_pool_available(1, 100);
        assert_eq!(s.verify_pool_capacity(1, 101), CommandResultCode::LoanPoolInsufficient);
        // 100% utilization would exceed the default 90% cap (see the utilization test below),
        // so pick a principal within both the available AND the utilization limit.
        assert_eq!(s.verify_pool_capacity(1, 90), CommandResultCode::Success);
    }

    #[test]
    fn verify_pool_capacity_rejects_when_utilization_exceeds_cap() {
        let mut s = LoanService::new();
        // default cap = 9000 bps (90%). available=1000, borrowed=8000 -> total=9000.
        // borrowing 200 more -> newBorrowed=8200, util=8200/9000=91.1% > 90% -> exceeded.
        s.add_to_loan_pool_available(1, 1_000);
        s.add_to_loan_pool_borrowed(1, 8_000);
        assert_eq!(s.verify_pool_capacity(1, 200), CommandResultCode::LoanPoolUtilizationExceeded);
        // borrowing exactly to the cap boundary succeeds: newBorrowed=8900, util=8900/9000=98.9%...
        // pick a principal that lands exactly at the cap: need newBorrowed/9000 <= 0.9 -> newBorrowed<=8100.
        assert_eq!(s.verify_pool_capacity(1, 100), CommandResultCode::Success); // newBorrowed=8100 = 90% exactly
    }

    #[test]
    fn verify_pool_capacity_skips_utilization_check_when_pool_untouched() {
        let s = LoanService::new(); // available=borrowed=0 for currency 1 -> total_pool<=0
        // available(0) < principal(1) triggers LoanPoolInsufficient before utilization is ever checked.
        assert_eq!(s.verify_pool_capacity(1, 1), CommandResultCode::LoanPoolInsufficient);
        // principal=0 never fails the available check and never touches the (skipped) utilization branch.
        assert_eq!(s.verify_pool_capacity(1, 0), CommandResultCode::Success);
    }

    #[test]
    fn disburse_loan_moves_principal_from_pool_available_to_account_and_marks_borrowed() {
        let mut s = LoanService::new();
        s.add_to_loan_pool_available(1, 1_000);
        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);

        s.disburse_loan(&mut up, 1, 300);

        assert_eq!(up.account(1), 300);
        assert_eq!(s.get_loan_pool_available(1), 700);
        assert_eq!(s.get_loan_pool_borrowed(1), 300);
    }

    #[test]
    fn apply_debt_payment_pays_interest_before_principal() {
        let mut s = LoanService::new();
        let mut loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 500, 0);
        loan.set_outstanding_principal(1_000);
        loan.set_accumulated_interest(100);
        let mut accounts: BTreeMap<i32, i64> = BTreeMap::new();
        accounts.insert(20, 10_000);

        // fund=150 covers all interest (100) plus 50 of principal.
        let interest_paid = s.apply_debt_payment(&mut loan, &mut accounts, 150);

        assert_eq!(interest_paid, 100);
        assert_eq!(loan.accumulated_interest(), 0);
        assert_eq!(loan.outstanding_principal(), 950);
        assert_eq!(loan.cum_interest_paid(), 100);
        assert_eq!(*accounts.get(&20).unwrap(), 10_000 - 150);
        assert_eq!(s.get_interest_revenue(20), 100);
        assert_eq!(s.get_loan_pool_available(20), 50);
        assert_eq!(s.get_loan_pool_borrowed(20), -50); // no prior borrowed tracked in this isolated test
    }

    #[test]
    fn apply_debt_payment_full_payoff_zeroes_both_debt_fields() {
        let mut s = LoanService::new();
        let mut loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 500, 0);
        loan.set_outstanding_principal(1_000);
        loan.set_accumulated_interest(100);
        let mut accounts: BTreeMap<i32, i64> = BTreeMap::new();

        s.apply_debt_payment(&mut loan, &mut accounts, 1_100);

        assert_eq!(loan.accumulated_interest(), 0);
        assert_eq!(loan.outstanding_principal(), 0);
        assert!(*accounts.get(&20).unwrap() < 0); // test never funded the account; only checking the delta applied
    }

    #[test]
    fn collateral_value_in_quote_currency_scales_through_symbol_and_currency() {
        let spec = CoreSymbolSpecification {
            symbol_id: 1,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        };
        let base_spec = CoreCurrencySpecification { currency: 1, currency_scale_k: 1, ..Default::default() };
        let quote_spec = CoreCurrencySpecification { currency: 2, currency_scale_k: 1, ..Default::default() };

        // amount=10 (base) * markPrice=5 -> notional=50, scale-identity -> 50 (quote).
        let value = LoanService::collateral_value_in_quote_currency(10, &spec, 5, Some(&base_spec), Some(&quote_spec));
        assert_eq!(value, 50);
    }

    #[test]
    fn collateral_value_in_quote_currency_returns_negative_one_when_a_currency_spec_is_missing() {
        let spec = CoreSymbolSpecification {
            symbol_id: 1,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        };
        let base_spec = CoreCurrencySpecification { currency: 1, currency_scale_k: 1, ..Default::default() };

        assert_eq!(LoanService::collateral_value_in_quote_currency(10, &spec, 5, Some(&base_spec), None), -1);
        assert_eq!(LoanService::collateral_value_in_quote_currency(10, &spec, 5, None, Some(&base_spec)), -1);
        assert_eq!(LoanService::collateral_value_in_quote_currency(10, &spec, 5, None, None), -1);
    }
}
