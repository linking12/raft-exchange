//! 对应 Java: exchange.core2.core.processors.loan.rate.FloatingRateModel。
//!
//! 浮动利率引擎（Flexible/活期）：kinked 曲线 + reprice 生效利率 + 累加器计息，适用于 Isolated
//! FLOATING 及全部 Cross 借贷，也是 `FixedRateModel` 开仓锁率的来源。per-shard 复制状态。
//!
//! 计息用"累加器差值法"：`acc_rate_bps_ms` 按币种累积"利率 × 时间"，只在 reprice 时用旧生效
//! 利率结算 `[last_reprice_ts, tick_ts)` 这段区间（`advance_accumulator`）；两次 reprice 之间的
//! 即时值由 `live_acc_rate_bps_ms` 用当前生效利率外推得到。每笔 loan 记一个 `acc_snapshot`
//! （开仓或上次结息时的累加器读数），欠息 = `(liveAcc − accSnapshot)` 换算成本金对应的利息——
//! 这样计息与 loan 笔数无关，一次 reprice 即对所有持仓同时生效。**加法，非乘法/复利**——
//! 刻意选择以保证 64-bit 溢出安全，且"利息只作用于本金"（loan.md §13.5 rationale）。
use std::collections::BTreeMap;

use crate::core::common::loan_record::LoanRecord;
use crate::core::processors::loan::loan_service::{BPS_SCALE, YEAR_MS};
use crate::core::utils::core_arithmetic_utils::trunc_mul_div;

/// 对应 Java `Math.addExact(long, long)`：局部私有重复一份（arithmetic 层零依赖 ruling，见
/// `core_arithmetic_utils.rs` 头注），累加器随运行时长单调增长，用 exact 让溢出立刻暴露而非
/// 静默翻负。
fn add_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 + b as i128).unwrap_or_else(|_| panic!("overflow: {a} + {b}"))
}

/// 对应 Java `Math.subtractExact(long, long)`。
fn sub_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 - b as i128).unwrap_or_else(|_| panic!("overflow: {a} - {b}"))
}

/// 对应 Java `Math.multiplyExact(long, long)`。
fn mul_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 * b as i128).unwrap_or_else(|_| panic!("overflow: {a} * {b}"))
}

pub const DEFAULT_BASE_BPS: i32 = 200; // 零利用率 2%
pub const DEFAULT_KINK_UTIL_BPS: i32 = 8000; // 拐点 80%
pub const DEFAULT_SLOPE1_BPS: i32 = 400; // 0→kink 增幅
pub const DEFAULT_SLOPE2_BPS: i32 = 6000; // kink→100% 陡增幅

/// 对应 Java `FloatingRateModel`（字段子集，`:47-53`）。
///
/// Java 侧 `currentRateBps`/`accRateBpsMs` 是 `IntLongHashMap`（currency -> value）；本移植
/// 用 `BTreeMap<i32,i64>` 保持确定性迭代序（仓库铁律：禁 HashMap）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FloatingRateModel {
    /// 零利用率基础利率。
    pub base_bps: i32,
    /// 利用率拐点。
    pub kink_util_bps: i32,
    /// 拐点前斜率。
    pub slope1_bps: i32,
    /// 拐点后斜率。
    pub slope2_bps: i32,
    /// 每币种当前生效利率（reprice 写入）。
    pub current_rate_bps: BTreeMap<i32, i64>,
    /// 每币种计息累加器：截至 `last_reprice_ts` 累积的"利率 × 时间"（bps·ms）。
    pub acc_rate_bps_ms: BTreeMap<i32, i64>,
    /// 上次 reprice 时刻（ms）；`<= 0` 表示尚未 reprice 过（冷启动）。
    pub last_reprice_ts: i64,
}

impl FloatingRateModel {
    /// 对应 Java `stateHash()`（`:192-196`）：`currentRateBps`/`accRateBpsMs` 的
    /// `IntLongHashMap.hashCode()` 无法逐字复现，这里改为对排序后 (key,value) 对逐个折叠——
    /// `BTreeMap` 天然有序满足确定性要求，只保证「同状态 -> 同 hash，不同状态 -> 不同 hash」。
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

    /// 利用率（bps）= borrowed / (borrowed + available)；空池返 0。对应 Java
    /// `utilizationBps` 静态方法（`:76-79`）。
    pub fn utilization_bps(borrowed: i64, available: i64) -> i64 {
        let total = add_exact(borrowed, available);
        if total <= 0 { 0 } else { trunc_mul_div(borrowed, BPS_SCALE, total) }
    }

    /// kinked 曲线：util（clamp 到 `[0, BPS_SCALE]`）过曲线得 rateBps，纯整数。对应 Java
    /// `curveRateBps`（`:82-93`，静态 + 实例两个重载合并为一个方法，直接读 `self` 字段）。
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

    /// 某币种当前利率；未 reprice 过时回退 `base_bps`（= `curve_rate_bps(0)`，冷启动兜底）。
    /// 对应 Java `currentRateBpsOrBase`（`:96-98`）——**含窄化**：`current_rate_bps` 存的是
    /// `i64`，这里按 Java `(int)` 强转截断为 `i32`（Java 侧同样是隐式窄化，非 exact-checked；
    /// bps 量级远小于 `i32::MAX`，实际不会截断丢位，按位复刻而非改成 checked 转换）。
    pub fn current_rate_bps_or_base(&self, currency: i32) -> i32 {
        match self.current_rate_bps.get(&currency) {
            Some(&v) => v as i32,
            None => self.base_bps,
        }
    }

    /// reprice 前半步：用旧生效利率把 `[last_reprice_ts, tick_ts)` 这段区间计入
    /// `acc_rate_bps_ms` 累加器。**必须先于 [`Self::reprice_currency`] 调用**，否则会用新利率
    /// 错误结算旧区间——调用方（Task 8 的 TwoStep reprice 处理器）在同一次 reprice 处理完所有
    /// 币种后才整体推进 `last_reprice_ts`，故这里读到的仍是推进前的值。对应 Java
    /// `advanceAccumulator`（`:105-112`）。
    pub fn advance_accumulator(&mut self, currency: i32, tick_ts: i64) {
        if self.last_reprice_ts > 0 && tick_ts > self.last_reprice_ts {
            let elapsed = tick_ts - self.last_reprice_ts;
            let advance = mul_exact(self.current_rate_bps_or_base(currency) as i64, elapsed);
            let cur = *self.acc_rate_bps_ms.get(&currency).unwrap_or(&0);
            self.acc_rate_bps_ms.insert(currency, add_exact(cur, advance));
        }
    }

    /// reprice 后半步：util 过曲线写入 `current_rate_bps`，成为新生效利率。对应 Java
    /// `repriceCurrency`（`:115-117`）。
    pub fn reprice_currency(&mut self, currency: i32, util_bps: i64) {
        let rate = self.curve_rate_bps(util_bps);
        self.current_rate_bps.insert(currency, rate);
    }

    /// 开仓利率 = 当前生效利率（未 reprice 过则回退 base）。对应 Java `openRateBps`
    /// （`:119-121`）。
    pub fn open_rate_bps(&self, loan_currency: i32) -> i32 {
        self.current_rate_bps_or_base(loan_currency)
    }

    /// 累加器实时值 = `acc_rate_bps_ms + currentRate × (now − last_reprice_ts)`，用当前生效
    /// 利率把上次 reprice 之后的区间外推到 `now`；冷启动（`last_reprice_ts<=0`）不外推。对应
    /// Java `liveAccRateBpsMs`（`:123-131`）。
    pub fn live_acc_rate_bps_ms(&self, currency: i32, now: i64) -> i64 {
        let acc = *self.acc_rate_bps_ms.get(&currency).unwrap_or(&0);
        let elapsed = now - self.last_reprice_ts;
        if self.last_reprice_ts <= 0 || elapsed <= 0 {
            return acc;
        }
        add_exact(acc, mul_exact(self.current_rate_bps_or_base(currency) as i64, elapsed))
    }

    /// 开仓：`acc_snapshot` 定在当前 liveAcc，此后只计从此刻起新增的利息。对应 Java
    /// `initOpenSnapshot`（`:134-136`）。
    pub fn init_open_snapshot<L: LoanRecord>(&self, loan: &mut L, now: i64) {
        let live = self.live_acc_rate_bps_ms(loan.loan_currency(), now);
        loan.set_acc_snapshot(live);
    }

    /// pending = `(liveAcc − accSnapshot)` 对应的利率·时间增量，换算成本金对应的利息；
    /// `deltaAcc<=0` 或无本金则免息。对应 Java 私有静态 `pending`（`:161-169`）。
    fn pending_from_live<L: LoanRecord>(loan: &L, live_acc: i64) -> i64 {
        let delta_acc = sub_exact(live_acc, loan.acc_snapshot());
        if delta_acc <= 0 || loan.outstanding_principal() <= 0 {
            0
        } else {
            trunc_mul_div(delta_acc, loan.outstanding_principal(), YEAR_MS * BPS_SCALE)
        }
    }

    /// 读路径小工具：截至 `now` 的 pending 利息（不含 `accumulated_interest`），不改 loan。
    /// 对应 Java 私有 `pending(loan, liveAccRateBpsMs(...))` 的组合调用，此处独立暴露供上层
    /// 复用/测试（brief 接口列表 `pending_interest(loan,now)`）。
    pub fn pending_interest<L: LoanRecord>(&self, loan: &L, now: i64) -> i64 {
        let live = self.live_acc_rate_bps_ms(loan.loan_currency(), now);
        Self::pending_from_live(loan, live)
    }

    /// 写路径：按累加器差值补计利息到 `now`，推进 `acc_snapshot`；返回本次新增利息（≥ 0）。
    /// 对应 Java `accrue`（`:139-153`）。
    ///
    /// **truncated-but-chargeable（F1）**：有本金且累加器确有推进（`deltaAcc>0`）却因截断得 0
    /// 时保留 `acc_snapshot`，让亚阈值增量继续累积到跨过阈值再计；否则高频 accrue 会把每段
    /// 利息永久吞掉。已计息/无本金/累加器未动 → 照常推进游标。
    pub fn accrue<L: LoanRecord>(&self, loan: &mut L, now: i64) -> i64 {
        let live = self.live_acc_rate_bps_ms(loan.loan_currency(), now);
        let delta = Self::pending_from_live(loan, live);
        if delta > 0 {
            loan.set_accumulated_interest(add_exact(loan.accumulated_interest(), delta));
        }
        // Java 用普通减法（`:147`），非 subtractExact：同一对 (live, accSnapshot) 已在
        // `pending_from_live` 内部经 `sub_exact` 验证不溢出，这里重复计算数学上必不溢出，
        // 按 Java 字面写法保留普通减法而非改成 `sub_exact`（行为等价，纯粹风格对齐）。
        let delta_acc = live - loan.acc_snapshot();
        let truncated_but_chargeable = delta == 0 && loan.outstanding_principal() > 0 && delta_acc > 0;
        if !truncated_but_chargeable {
            loan.set_acc_snapshot(live);
        }
        delta
    }

    /// 读路径：`accumulated_interest` + 到 `now` 的 pending，不改 loan。对应 Java
    /// `displayInterest`（`:156-159`）。
    pub fn display_interest<L: LoanRecord>(&self, loan: &L, now: i64) -> i64 {
        let live = self.live_acc_rate_bps_ms(loan.loan_currency(), now);
        add_exact(loan.accumulated_interest(), Self::pending_from_live(loan, live))
    }

    /// 对应 Java lombok `setLastRepriceTs`：`last_reprice_ts` 是 `pub` 字段，直接赋值也可，
    /// 提供方法版本供 Task 8 处理器按 Java 调用习惯使用（"处理完所有币种后才整体推进"）。
    pub fn set_last_reprice_ts(&mut self, ts: i64) {
        self.last_reprice_ts = ts;
    }
}

impl Default for FloatingRateModel {
    /// 对应 Java `FloatingRateModel()` 构造器（`:55-63`）。
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

    // ---- (b) kinked curve above/below kink ----

    #[test]
    fn curve_rate_bps_below_kink_is_linear_from_base() {
        let m = FloatingRateModel::default(); // base=200 kink=8000 slope1=400 slope2=6000
        // util = half of kink -> base + slope1 * util / kink = 200 + 400*4000/8000 = 400
        assert_eq!(m.curve_rate_bps(4000), 400);
        // util = 0 -> exactly base
        assert_eq!(m.curve_rate_bps(0), 200);
    }

    #[test]
    fn curve_rate_bps_at_and_above_kink_switches_to_slope2() {
        let m = FloatingRateModel::default();
        // exactly at kink -> base + slope1 (slope2 segment contributes 0)
        assert_eq!(m.curve_rate_bps(8000), 600);
        // above kink: base+slope1 + slope2*(util-kink)/(BPS-kink) = 600 + 6000*1000/2000 = 3600
        assert_eq!(m.curve_rate_bps(9000), 3600);
        // full utilization
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
        assert_eq!(FloatingRateModel::utilization_bps(5_000, 5_000), 5_000); // 50%
        assert_eq!(FloatingRateModel::utilization_bps(8_000, 2_000), 8_000); // 80%
    }

    // ---- (c) additive accumulator: two loans opened at different times share one
    // accumulator but each gets correct pending via its own acc_snapshot ----

    #[test]
    fn additive_accumulator_two_loans_opened_at_different_times_use_own_snapshot() {
        let cur = 7;
        let mut model = FloatingRateModel::default();
        model.last_reprice_ts = 1_000;
        model.current_rate_bps.insert(cur, 500); // 5%, effective since last_reprice_ts

        let principal = 315_360_000_000; // chosen so trunc_mul_div below divides exactly
        let mut loan_a = floating_loan(cur, principal, 1_000);
        model.init_open_snapshot(&mut loan_a, 1_000); // opened right at the reprice tick
        assert_eq!(loan_a.acc_snapshot(), 0);

        let mut loan_b = floating_loan(cur, principal, 2_000);
        model.init_open_snapshot(&mut loan_b, 2_000); // opened 1000ms later, mid-interval
        assert_eq!(loan_b.acc_snapshot(), 500_000); // 500bps * 1000ms already accrued in the live acc

        // at now=3000: loan_a has accrued over its full 2000ms lifetime, loan_b only 1000ms,
        // even though both read the SAME shared accumulator.
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

        let d1 = model.accrue(&mut loan, 2_000); // 1000ms elapsed at 5% -> pending 500
        assert_eq!(d1, 500);
        assert_eq!(loan.accumulated_interest(), 500);
        assert_eq!(loan.acc_snapshot(), 500_000); // cursor advanced to live acc at t=2000

        let d2 = model.accrue(&mut loan, 3_000); // next 1000ms slice
        assert_eq!(d2, 500);
        assert_eq!(loan.accumulated_interest(), 1_000); // additive, not compounding
    }

    // ---- (d) truncated-but-chargeable: F1 cursor freeze ----

    #[test]
    fn accrue_truncated_but_chargeable_freezes_snapshot_until_threshold_crossed() {
        let cur = 9;
        let mut model = FloatingRateModel::default();
        model.current_rate_bps.insert(cur, 1); // 1 bps — deliberately tiny
        model.last_reprice_ts = 1; // "warm": elapsed since this ts extrapolates live acc at 1bps
        let principal = 1; // tiny principal maximizes truncation
        let mut loan = floating_loan(cur, principal, 1);
        loan.set_acc_snapshot(0);

        // First accrue: live = 1bps * (100-1)ms = 99 bps*ms; pending = trunc(99*1/(YEAR_MS*BPS_SCALE)) = 0.
        let d1 = model.accrue(&mut loan, 100);
        assert_eq!(d1, 0);
        assert_eq!(loan.acc_snapshot(), 0, "F1: snapshot must NOT advance when truncated to 0 but principal>0 and time elapsed");

        // Keep calling with ever-larger `now`; snapshot stays frozen at 0 until the accumulated
        // deltaAcc finally crosses the truncation threshold and a nonzero charge is possible.
        let threshold = YEAR_MS * BPS_SCALE; // deltaAcc needed for principal=1 to yield pending=1
        let now = 1 + threshold; // elapsed since last_reprice_ts=1 -> live = 1*threshold
        let d2 = model.accrue(&mut loan, now);
        assert!(d2 > 0, "sub-threshold interest must eventually be charged, not lost forever");
        assert_eq!(loan.acc_snapshot(), threshold, "snapshot advances once interest is actually charged");
    }

    // ---- (e) advance_accumulator MUST run before reprice_currency ----

    #[test]
    fn advance_accumulator_before_reprice_settles_old_interval_at_old_rate() {
        let cur = 3;
        let mut base = FloatingRateModel::default();
        base.last_reprice_ts = 1_000;
        base.current_rate_bps.insert(cur, 300); // old rate: 3%

        // Correct order (per contract): advance_accumulator THEN reprice_currency.
        let mut correct = base.clone();
        correct.advance_accumulator(cur, 2_000);
        correct.reprice_currency(cur, 9_000); // util above kink -> new rate very different from 300
        let correct_acc = *correct.acc_rate_bps_ms.get(&cur).unwrap();
        assert_eq!(correct_acc, 300 * 1_000, "old interval must settle at the OLD rate");

        // Reversed (buggy) order: reprice_currency THEN advance_accumulator.
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
