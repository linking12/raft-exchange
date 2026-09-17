//! 对应 Java `exchange.core2.core.common.TimeWindowDedupSet`。时间窗去重容器:外部触发命令
//! (BALANCE_ADJUSTMENT / MARGIN_ADJUSTMENT / 借贷全家桶 / POOL_* / INTERNAL_TRANSFER 等)的幂等门。
//! 保留最近 `window_ms`(默认 3 天)内的 id,超窗淘汰后同 id 视为新事件——外部 at-least-once 重投落在窗口内即幂等。
//!
//! **确定性(raft 收敛红线)**:淘汰只由命令时间 `now_ms` 驱动,而 `now_ms` 必须随 raft log 复制(leader 盖章、各节点同值),
//! 严禁用本地钟。插入时把时间 clamp 成 `max(now_ms, 队尾时间)`,使容器内时间单调不减(队头恒最老),leader 切换处命令时间不严格单调也自洽。
//!
//! 物理布局(`VecDeque` 容量/head)不进 hash、不序列化;`fold_hash`(对应 Java `stateHash`)与 `chronicle_write`
//! 均按 FIFO 逻辑序遍历 (id, time),使快照恢复后布局不同但逻辑等价的状态在各节点产生相同 hash。
use std::collections::{HashSet, VecDeque};

/// 默认保留窗口:3 天(对应 Java `DEFAULT_WINDOW_MS`)。
pub const DEFAULT_WINDOW_MS: i64 = 3 * 24 * 3600 * 1000;
/// 默认硬上限:单用户最多保留的 id 条数(安全阀,对应 Java `DEFAULT_HARD_CAP`)。
pub const DEFAULT_HARD_CAP: usize = 1 << 16;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TimeWindowDedupSet {
    window_ms: i64,
    hard_cap: usize,
    /// (id, time) FIFO,队头恒最老(对应 Java `ids`/`times` 环形数组的逻辑序)。
    entries: VecDeque<(i64, i64)>,
    /// 成员索引,`entries` 的派生(对应 Java `idSet`);不进 hash/快照,`from_snapshot_parts` 时从 entries 重建。
    ids: HashSet<i64>,
}

impl TimeWindowDedupSet {
    pub fn new() -> Self {
        Self { window_ms: DEFAULT_WINDOW_MS, hard_cap: DEFAULT_HARD_CAP, entries: VecDeque::new(), ids: HashSet::new() }
    }

    /// 从快照分量重建(`ids` 由 entries 派生);对应 Java `TimeWindowDedupSet(BytesIn)` 读构造器,见本类 `chronicle_read`。
    pub fn from_snapshot_parts(window_ms: i64, hard_cap: usize, entries: Vec<(i64, i64)>) -> Self {
        let ids = entries.iter().map(|&(id, _)| id).collect();
        Self { window_ms, hard_cap, entries: entries.into(), ids }
    }

    /// 对应 Java `tryClaim(id, nowMs)`:首次见到(且未超窗)记录并返 true;窗口内已见过返 false(命令应拒)。
    /// `now_ms` 须为确定性命令时间。步骤:clamp 时间 → 清超窗队头 → 查重 → hard_cap 兜底淘汰 → 入队。
    pub fn try_claim(&mut self, id: i64, now_ms: i64) -> bool {
        // clamp 成 max(now_ms, 队尾时间),保容器内时间单调不减。
        let eff = match self.entries.back() {
            Some(&(_, t)) => now_ms.max(t),
            None => now_ms,
        };
        let cutoff = eff - self.window_ms;
        // 清超窗老条目(队头恒最老)。
        while let Some(&(oid, t)) = self.entries.front() {
            if t < cutoff {
                self.entries.pop_front();
                self.ids.remove(&oid);
            } else {
                break;
            }
        }
        // 窗口内已见过 → 拒。
        if self.ids.contains(&id) {
            return false;
        }
        // 硬上限兜底:触顶按最老淘汰(应打 metric 告警)。
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

    /// 按 FIFO 逻辑序把 (window_ms, hard_cap, len, 各 (id,time)) 折入滚动 hash;对应 Java `stateHash`(物理布局不影响)。
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

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

impl ChronicleMarshallable for TimeWindowDedupSet {
    /// 对应 Java `writeMarshallable`:windowMs(long) + hardCap(int) + size(int) + size×(id long, time long),FIFO 顺序。
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_i64(self.window_ms);
        w.write_i32(self.hard_cap as i32);
        w.write_i32(self.entries.len() as i32);
        for &(id, time) in &self.entries {
            w.write_i64(id);
            w.write_i64(time);
        }
    }
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let window_ms = r.read_i64()?;
        let hard_cap = r.read_i32()? as usize;
        let size = r.read_i32()?;
        let mut entries = Vec::with_capacity(size.max(0) as usize);
        for _ in 0..size {
            let id = r.read_i64()?;
            let time = r.read_i64()?;
            entries.push((id, time));
        }
        Ok(TimeWindowDedupSet::from_snapshot_parts(window_ms, hard_cap, entries))
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
        let far = DEFAULT_WINDOW_MS + 1;
        assert!(s.try_claim(1, far));
        assert_eq!(s.len(), 1, "老条目已淘汰，只剩新的");
    }

    #[test]
    fn time_clamped_monotonic() {
        let mut s = TimeWindowDedupSet::new();
        assert!(s.try_claim(1, 1000));
        assert!(s.try_claim(2, 500));
        assert_eq!(s.len(), 2);
    }

    #[test]
    fn chronicle_roundtrip_rebuilds_id_index() {
        let mut s = TimeWindowDedupSet::new();
        s.try_claim(7, 100);
        s.try_claim(9, 200);
        let mut w = ChronicleWriter::new();
        s.chronicle_write(&mut w);
        let bytes = w.into_bytes();
        let mut back = TimeWindowDedupSet::chronicle_read(&mut ChronicleReader::new(&bytes)).unwrap();
        assert_eq!(s, back);
        assert!(!back.try_claim(7, 200), "读回后 id 索引应已从 entries 重建");
        assert!(back.try_claim(8, 200));
    }
}
