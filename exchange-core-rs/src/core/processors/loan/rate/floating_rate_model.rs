//! 对应 Java: exchange.core2.core.processors.loan.rate.FloatingRateModel —— **仅字段骨架**
//! （Task 1 范围；curve/accrue/reprice 数学留 Task 2，见参考文档 §4.1/§4.2 "entangled" 提示）。
//!
//! 浮动利率引擎（Flexible/活期）：kinked 曲线 + reprice 生效利率 + 累加器计息，适用于 Isolated
//! FLOATING 及全部 Cross 借贷，也是 `FixedRateModel` 开仓锁率的来源。per-shard 复制状态。
use std::collections::BTreeMap;

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
}
