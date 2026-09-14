//! 对应 Java `common/TimeWindowDedupSet`：外部触发命令（BALANCE_ADJUSTMENT / MARGIN_ADJUSTMENT / 借贷 /
//! POOL_* / INTERNAL_TRANSFER 等）的幂等门。FIFO 保留最近 `window_ms`（默认 3 天）内的 (id, time)；超窗淘汰、
//! `hard_cap` 硬上限兜底防无界增长。淘汰只由确定性命令时间 `now_ms` 驱动（随 raft 复制，非本地钟），插入时 clamp
//! 成 `max(now_ms, 队尾时间)` 保容器内时间单调不减。`state_hash`/serde 按 FIFO 逻辑序遍历 (id, time)，物理布局不进 hash。
use std::collections::{HashSet, VecDeque};

/// 默认保留窗口：3 天。
pub const DEFAULT_WINDOW_MS: i64 = 3 * 24 * 3600 * 1000;
/// 默认硬上限：单用户最多保留的 id 条数（安全阀）。
pub const DEFAULT_HARD_CAP: usize = 1 << 16;

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(from = "DedupData", into = "DedupData")]
pub struct TimeWindowDedupSet {
    window_ms: i64,
    hard_cap: usize,
    /// (id, time) FIFO，队头最老。
    entries: VecDeque<(i64, i64)>,
    /// 成员索引（entries 的派生，不进 hash/序列化，反序列化时重建）。
    ids: HashSet<i64>,
}

/// 序列化投影：只存 window_ms/hard_cap/entries（FIFO 顺序），`ids` 从 entries 重建。
#[derive(serde::Serialize, serde::Deserialize)]
struct DedupData {
    window_ms: i64,
    hard_cap: usize,
    entries: Vec<(i64, i64)>,
}

impl From<DedupData> for TimeWindowDedupSet {
    fn from(d: DedupData) -> Self {
        let ids = d.entries.iter().map(|&(id, _)| id).collect();
        TimeWindowDedupSet { window_ms: d.window_ms, hard_cap: d.hard_cap, entries: d.entries.into(), ids }
    }
}

impl From<TimeWindowDedupSet> for DedupData {
    fn from(s: TimeWindowDedupSet) -> Self {
        DedupData { window_ms: s.window_ms, hard_cap: s.hard_cap, entries: s.entries.into() }
    }
}

impl TimeWindowDedupSet {
    pub fn new() -> Self {
        Self { window_ms: DEFAULT_WINDOW_MS, hard_cap: DEFAULT_HARD_CAP, entries: VecDeque::new(), ids: HashSet::new() }
    }

    /// 对应 Java `tryClaim(id, nowMs)`：以命令时间 `now_ms` 清超窗老条目后，首次见到 `id` → 记录返回 `true`；
    /// 窗口内已见过 → `false`（命令应拒）。`now_ms` 须为确定性命令时间（随 raft 复制）。
    pub fn try_claim(&mut self, id: i64, now_ms: i64) -> bool {
        // clamp 到队尾时间，保容器内时间单调不减。
        let eff = match self.entries.back() {
            Some(&(_, t)) => now_ms.max(t),
            None => now_ms,
        };
        let cutoff = eff - self.window_ms;
        // 1) 清超窗老条目（队头恒最老）。
        while let Some(&(oid, t)) = self.entries.front() {
            if t < cutoff {
                self.entries.pop_front();
                self.ids.remove(&oid);
            } else {
                break;
            }
        }
        // 2) 窗口内已见过 → 拒。
        if self.ids.contains(&id) {
            return false;
        }
        // 3) 硬上限兜底：触顶按最老淘汰。
        while self.entries.len() >= self.hard_cap {
            match self.entries.pop_front() {
                Some((oid, _)) => {
                    self.ids.remove(&oid);
                }
                None => break,
            }
        }
        self.entries.push_back((id, eff));
        self.ids.insert(id);
        true
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    /// 按 FIFO 逻辑序把 (window_ms, hard_cap, len, 各 (id,time)) 折入滚动 hash（对应 Java `stateHash`，物理布局不影响）。
    pub fn fold_hash(&self, mut h: i64) -> i64 {
        h = h.wrapping_mul(31).wrapping_add(self.window_ms);
        h = h.wrapping_mul(31).wrapping_add(self.hard_cap as i64);
        h = h.wrapping_mul(31).wrapping_add(self.entries.len() as i64);
        for &(id, t) in &self.entries {
            h = h.wrapping_mul(31).wrapping_add(id);
            h = h.wrapping_mul(31).wrapping_add(t);
        }
        h
    }
}

impl Default for TimeWindowDedupSet {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn first_claim_true_repeat_false_within_window() {
        let mut s = TimeWindowDedupSet::new();
        assert!(s.try_claim(1, 1000));
        assert!(!s.try_claim(1, 1000));
        assert!(s.try_claim(2, 1000));
        assert_eq!(s.len(), 2);
    }

    #[test]
    fn evicts_beyond_window_then_reclaimable() {
        let mut s = TimeWindowDedupSet::new();
        assert!(s.try_claim(1, 0));
        // 超过 window（3 天）后同 id 视为新事件。
        let far = DEFAULT_WINDOW_MS + 1;
        assert!(s.try_claim(1, far));
        assert_eq!(s.len(), 1, "老条目已淘汰，只剩新的");
    }

    #[test]
    fn time_clamped_monotonic() {
        let mut s = TimeWindowDedupSet::new();
        assert!(s.try_claim(1, 1000));
        // now_ms 回退不应破坏单调；不淘汰、正常记录。
        assert!(s.try_claim(2, 500));
        assert_eq!(s.len(), 2);
    }

    #[test]
    fn serde_roundtrip_rebuilds_id_index() {
        let mut s = TimeWindowDedupSet::new();
        s.try_claim(7, 100);
        s.try_claim(9, 200);
        let bytes = bincode::serialize(&s).unwrap();
        let mut back: TimeWindowDedupSet = bincode::deserialize(&bytes).unwrap();
        assert_eq!(s, back);
        assert!(!back.try_claim(7, 200), "反序列化后 id 索引应已重建");
        assert!(back.try_claim(8, 200));
    }
}
