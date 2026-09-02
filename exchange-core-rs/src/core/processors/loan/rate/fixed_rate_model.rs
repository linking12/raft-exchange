//! 对应 Java: exchange.core2.core.processors.loan.rate.FixedRateModel。
//!
//! 定期利率模型（Fixed/Lock），仅用于 Isolated LOCKED：开仓时锁定 `FloatingRateModel` 当前利率 +
//! 点差，此后利率不再变化，按固定利率线性计息。
//!
//! **移植偏差（有意，Task 1 已定）**：Java 版持有 `final FloatingRateModel floating` 字段（同一
//! `LoanService` 实例内的共享引用）。Rust 侧 `LoanService` 同时拥有 `floating_rate`/`fixed_rate`
//! 两个字段，若在 `FixedRateModel` 内再放一份 `floating` 引用会形成同结构体内的自引用，需要
//! `Rc`/生命周期才能表达——与仓库"禁 Rc/RefCell"铁律冲突。因此本移植不搬这个引用字段，
//! `open_rate_bps` 改为显式接收 `&FloatingRateModel` 参数（`LoanService` 调用时传
//! `&self.floating_rate`），语义等价，只是把"隐式持有"换成"显式传参"。`accrue`/`display_interest`
//! 不需要 floating（只读 loan 自身锁定的 `rate_bps`），故不带该参数。
use crate::core::common::loan_record::LoanRecord;
use crate::core::processors::loan::loan_service::{BPS_SCALE, YEAR_MS};
use crate::core::processors::loan::rate::floating_rate_model::FloatingRateModel;
use crate::core::utils::core_arithmetic_utils::trunc_mul_div;

/// 对应 Java `Math.addExact(long, long)`：局部私有重复一份（arithmetic 层零依赖 ruling）。
fn add_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 + b as i128).unwrap_or_else(|_| panic!("overflow: {a} + {b}"))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FixedRateModel {
    /// 相对 floating 曲线的加/减价（bps），默认 `0` = 与 floating 同价。
    pub locked_rate_adjust_bps: i32,
}

impl FixedRateModel {
    /// 对应 Java `reset()`（`:89-91`）。
    pub fn reset(&mut self) {
        self.locked_rate_adjust_bps = 0;
    }

    /// 对应 Java `stateHash()`（`:98-100`，`Objects.hash(lockedRateAdjustBps)`）。
    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        h = h.wrapping_mul(31).wrapping_add(self.locked_rate_adjust_bps as i64);
        ((h >> 32) as i32) ^ (h as i32)
    }

    /// 开仓利率 = floating 当前利率（未 reprice 过则回退 base）+ `locked_rate_adjust_bps`，
    /// 下限 0；结果固化进 `loan.rate_bps`，此后不再随 floating 变化。对应 Java `openRateBps`
    /// （`:50-53`）。
    pub fn open_rate_bps(&self, floating: &FloatingRateModel, loan_currency: i32) -> i32 {
        let adjusted =
            floating.current_rate_bps_or_base(loan_currency) as i64 + self.locked_rate_adjust_bps as i64;
        adjusted.max(0) as i32
    }

    /// 写路径：按 `loan.rate_bps` 补计利息到 `now`，推进 `last_accrue_ts`；返回本次新增利息
    /// （≥ 0）。对应 Java `accrue`（`:56-68`）。
    ///
    /// **truncated-but-chargeable（F1）**：只在"已计息（`delta>0`）"或"本就不可能计息（无本金/
    /// 免息）"时推进游标；有本金有利率却因截断得 0 时保留游标，让被截断的 `elapsed` 继续累积到
    /// 跨过精度阈值再计——否则高频 accrue（如反复 REPAY）会把每段亚阈值利息永久吞掉。
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

    /// 读路径：`accumulated_interest` + 到 `now` 的 pending 利息，不改 loan。对应 Java
    /// `displayInterest`（`:71-74`）。
    pub fn display_interest<L: LoanRecord>(&self, loan: &L, now: i64) -> i64 {
        let pending =
            Self::accrue_delta(loan.outstanding_principal(), loan.rate_bps(), loan.last_accrue_ts(), now);
        add_exact(loan.accumulated_interest(), pending)
    }

    /// 对应 Java 私有静态 `accrueDelta`（`:76-87`）：**分两步 `truncMulDiv`**——先
    /// `elapsed×principal/YEAR_MS` 再 `×rateBps/BPS_SCALE`，避免中间值溢出。两次调用的截断点
    /// 与合并成一次 `i128` 连乘再除的结果不同，必须严格按 Java 的两步顺序调用，不可合并。
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
    /// 对应 Java `FixedRateModel(FloatingRateModel floating)` 构造器：`lockedRateAdjustBps = 0`。
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

    // ---- (a) fixed simple-interest one year = principal × rate, correctly scaled ----

    #[test]
    fn accrue_one_year_simple_interest_equals_principal_times_rate_over_bps_scale() {
        let mut loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 500 /* 5% */, 0);
        loan.set_outstanding_principal(1_000_000);
        let model = FixedRateModel::default();

        let delta = model.accrue(&mut loan, YEAR_MS);

        assert_eq!(delta, 50_000); // 1_000_000 * 500 / 10_000
        assert_eq!(loan.accumulated_interest(), 50_000);
        assert_eq!(loan.last_accrue_ts(), YEAR_MS); // cursor advanced since it actually charged
    }

    #[test]
    fn accrue_is_additive_across_two_equal_half_year_slices() {
        let mut loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 500, 0);
        loan.set_outstanding_principal(1_000_000);
        let model = FixedRateModel::default();

        model.accrue(&mut loan, YEAR_MS / 2);
        model.accrue(&mut loan, YEAR_MS);

        assert_eq!(loan.accumulated_interest(), 50_000); // same total as one full-year call
    }

    #[test]
    fn display_interest_does_not_mutate_loan() {
        let mut loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 500, 0);
        loan.set_outstanding_principal(1_000_000);
        let model = FixedRateModel::default();

        let pending = model.display_interest(&loan, YEAR_MS);

        assert_eq!(pending, 50_000);
        assert_eq!(loan.accumulated_interest(), 0); // read path: unchanged
        assert_eq!(loan.last_accrue_ts(), 0); // read path: cursor untouched
    }

    // ---- open_rate_bps: derived from FloatingRateModel's current curve value + spread ----

    #[test]
    fn open_rate_bps_is_floating_current_rate_plus_spread_floored_at_zero() {
        let mut floating = FloatingRateModel::default();
        floating.current_rate_bps.insert(20, 700);

        let model = FixedRateModel { locked_rate_adjust_bps: 50 };
        assert_eq!(model.open_rate_bps(&floating, 20), 750);

        let negative_spread = FixedRateModel { locked_rate_adjust_bps: -900 };
        assert_eq!(negative_spread.open_rate_bps(&floating, 20), 0); // floored at 0, not negative

        // falls back to floating's base_bps when that currency was never repriced
        let default_model = FixedRateModel::default();
        assert_eq!(default_model.open_rate_bps(&floating, 999), floating.base_bps);
    }

    // ---- (d) truncated-but-chargeable: F1 cursor freeze ----

    #[test]
    fn accrue_truncated_but_chargeable_freezes_cursor_until_threshold_crossed() {
        // principal == YEAR_MS makes interest_base == elapsed exactly (no first-step truncation),
        // so only the second truncMulDiv step (× rate_bps / BPS_SCALE) truncates — isolates F1
        // to exactly the case the brief describes.
        let mut loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 5_000 /* 50% */, 0);
        loan.set_outstanding_principal(YEAR_MS);
        let model = FixedRateModel::default();

        // elapsed=1: interest_base=1, delta=trunc(1*5000/10000)=0 -> truncated to 0.
        let d1 = model.accrue(&mut loan, 1);
        assert_eq!(d1, 0);
        assert_eq!(loan.accumulated_interest(), 0);
        assert_eq!(loan.last_accrue_ts(), 0, "F1: cursor must NOT advance while principal>0, rate>0, but truncated to 0");

        // elapsed since the STILL-frozen cursor is now 2: interest_base=2, delta=trunc(2*5000/10000)=1.
        let d2 = model.accrue(&mut loan, 2);
        assert_eq!(d2, 1, "sub-threshold interest from the first call must be recovered, not lost");
        assert_eq!(loan.accumulated_interest(), 1);
        assert_eq!(loan.last_accrue_ts(), 2, "cursor advances once interest is actually charged");
    }

    #[test]
    fn accrue_advances_cursor_even_at_zero_delta_when_principal_or_rate_is_nonpositive() {
        // No principal: delta is intrinsically always 0, so the cursor must still advance
        // (this is NOT the F1 case — F1 only freezes when principal>0 AND rate>0).
        let mut loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 500, 0);
        loan.set_outstanding_principal(0);
        let model = FixedRateModel::default();

        let delta = model.accrue(&mut loan, 1_000);
        assert_eq!(delta, 0);
        assert_eq!(loan.last_accrue_ts(), 1_000, "cursor advances: no principal means interest can never accrue anyway");
    }
}
