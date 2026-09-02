//! 对应 Java: exchange.core2.core.processors.loan.rate.FixedRateModel —— **仅字段骨架**
//! （Task 1 范围；`openRateBps`/`accrue`/`displayInterest` 数学留 Task 2）。
//!
//! 定期利率模型（Fixed/Lock），仅用于 Isolated LOCKED：开仓时锁定 `FloatingRateModel` 当前利率 +
//! 点差，此后利率不再变化，按固定利率线性计息。
//!
//! **移植偏差（有意）**：Java 版持有 `final FloatingRateModel floating` 字段（同一 `LoanService`
//! 实例内的共享引用）。Rust 侧 `LoanService` 同时拥有 `floating_rate`/`fixed_rate` 两个字段，
//! 若在 `FixedRateModel` 内再放一份 `floating` 引用会形成同结构体内的自引用，需要 `Rc`/生命周期
//! 才能表达——与仓库"禁 Rc/RefCell"铁律冲突。因此本移植不搬这个引用字段，Task 2 的
//! `open_rate_bps`/`accrue`/`display_interest` 改为显式接收 `&FloatingRateModel` 参数
//! （`LoanService` 调用时传 `&self.floating_rate`），语义等价，只是把"隐式持有"换成"显式传参"。
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
}
