//! 对应 Java `exchange.core2.core.processors.journaling.ISerializationProcessor`：快照存储后端抽象。
//!
//! Java 端该接口把「模块自身的 Chronicle marshalling」与「外层帧格式 + 落盘/传输」拆开——
//! `RiskEngine`/`MatchingEngineRouter` 各自实现 `WriteBytesMarshallable` 只产**模块 payload**，
//! 而 `storeData(snapshotId, seq, timestampNs, type, instanceId, obj)` / `loadData(...)` 由具体 processor
//! 实现（`DiskSerializationProcessor` 加 LZ4 `.ecs`、`MemorySerializationProcessor` 不压缩 `.dat`、
//! `DummySerializationProcessor` 空实现）负责外层分帧（`WireToOutputStream` = 大端分片长度 +
//! Chronicle 文档头 ± LZ4）与实际存储。
//!
//! Rust 侧同样把这两层分开：模块层（`write_risk_engine_payload` / `MatchingEngineRouter::chronicle_write`）
//! 只吐/吃 **raw payload**；[`SerializationProcessor`] 的实现负责 framing（[`encode_module_payload`] /
//! [`decode_module_payload`]，见 [`super::module_frame`]）与存储。`ExchangeCore` 持有一个
//! `Box<dyn SerializationProcessor>`（对应 Java `ExchangeCore.serializationProcessor`，由 config 注入），
//! `persist`/`recover` 经它按 per-(module, instanceId) 粒度 store/load，与 Java `PERSIST_STATE_RISK`/
//! `PERSIST_STATE_MATCHING` 两条命令 + `recoverStateBySnapshot` 的模块边界逐一对齐。
//!
//! **刻意不移植**的 Java 接口成员：`writeToJournal`/`enableJournaling`/`replayJournal*`/
//! `findAllSnapshotPoints`（journaling 生命周期——Rust 的 log 就是 raft，在 Java 侧，移过来是死代码）。

use std::cell::RefCell;
use std::collections::BTreeMap;
use std::path::PathBuf;
use std::rc::Rc;

use crate::core::snapshot::module_frame::{decode_module_payload, encode_module_payload};

/// 对应 Java `ISerializationProcessor.SerializedModuleType`：快照分两个模块，各自独立编码/落盘。
/// `code()` 返回 Java 侧同款短码（`"RE"`/`"ME"`），`file_name()` 与
/// `raft-exchange-server` 的 `SnapshotHelper.genSnapshotFileName` 完全一致。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum SerializedModuleType {
    RiskEngine,
    MatchingEngineRouter,
}

impl SerializedModuleType {
    pub fn code(self) -> &'static str {
        match self {
            SerializedModuleType::RiskEngine => "RE",
            SerializedModuleType::MatchingEngineRouter => "ME",
        }
    }

    /// 对应 `SnapshotHelper.genSnapshotFileName`：`snapshot_{snapshotId}_{RE|ME}_{instanceId}.dat`。
    pub fn file_name(self, snapshot_id: i64, instance_id: i32) -> String {
        format!("snapshot_{}_{}_{}.dat", snapshot_id, self.code(), instance_id)
    }
}

/// 对应 Java `ISerializationProcessor` 的两个存储原语（去掉 journaling 部分）。
/// `payload` 是模块层已 marshalling 出的 **raw Chronicle payload**；framing（分帧 ± 压缩）
/// 是实现者的职责，与 Java `storeData` 内部 `WireToOutputStream` 负责 framing 一致。
pub trait SerializationProcessor {
    /// 对应 `boolean storeData(long snapshotId, long seq, long timestampNs, SerializedModuleType type,
    /// int instanceId, WriteBytesMarshallable obj)`。Rust 传入已 marshalling 的 payload，实现负责 framing。
    fn store_data(
        &mut self,
        snapshot_id: i64,
        seq: i64,
        timestamp_ns: i64,
        module: SerializedModuleType,
        instance_id: i32,
        payload: &[u8],
    ) -> bool;

    /// 对应 `<T> T loadData(long snapshotId, SerializedModuleType type, int instanceId,
    /// Function<BytesIn,T> initFunc)`。返回 un-framed 的 raw payload（`None`=该模块快照不存在）。
    fn load_data(&self, snapshot_id: i64, module: SerializedModuleType, instance_id: i32) -> Option<Vec<u8>>;
}

/// 对应 Java `MemorySerializationProcessor`（不压缩）+ 单元测试用的内存后端：把 framing 后的字节
/// 存进一张 `(snapshotId, moduleCode, instanceId) -> framed bytes` 的表。内部 `Rc<RefCell<..>>`
/// 使其可 `clone` 共享同一张表——测试里让「持久化的 core」与「恢复的 fresh core」共享后端，
/// 模拟 failover（一个实例 persist、另一个实例 recover）。
#[derive(Clone, Default)]
pub struct InMemorySerializationProcessor {
    store: Rc<RefCell<BTreeMap<(i64, &'static str, i32), Vec<u8>>>>,
}

impl InMemorySerializationProcessor {
    pub fn new() -> Self {
        Self::default()
    }
}

impl SerializationProcessor for InMemorySerializationProcessor {
    fn store_data(
        &mut self,
        snapshot_id: i64,
        _seq: i64,
        _timestamp_ns: i64,
        module: SerializedModuleType,
        instance_id: i32,
        payload: &[u8],
    ) -> bool {
        let framed = encode_module_payload(payload);
        self.store.borrow_mut().insert((snapshot_id, module.code(), instance_id), framed);
        true
    }

    fn load_data(&self, snapshot_id: i64, module: SerializedModuleType, instance_id: i32) -> Option<Vec<u8>> {
        self.store
            .borrow()
            .get(&(snapshot_id, module.code(), instance_id))
            .map(|framed| decode_module_payload(framed).expect("in-memory snapshot frame decode failed"))
    }
}

/// 对应 Java `MemorySerializationProcessor`（落盘、不压缩 `.dat`）/ `DiskSerializationProcessor` 的
/// 磁盘后端。文件名 = [`SerializedModuleType::file_name`]（与 `SnapshotHelper.genSnapshotFileName` 同款），
/// 落在 `root` 目录下。写出的正是不压缩 `.dat` 帧（`decode_module_payload` 读侧 autodetect LZ4，
/// 故 Java 写的 LZ4 `.ecs` 若放进来也能读）。
pub struct FileSerializationProcessor {
    root: PathBuf,
}

impl FileSerializationProcessor {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    fn path(&self, snapshot_id: i64, module: SerializedModuleType, instance_id: i32) -> PathBuf {
        self.root.join(module.file_name(snapshot_id, instance_id))
    }
}

impl SerializationProcessor for FileSerializationProcessor {
    fn store_data(
        &mut self,
        snapshot_id: i64,
        _seq: i64,
        _timestamp_ns: i64,
        module: SerializedModuleType,
        instance_id: i32,
        payload: &[u8],
    ) -> bool {
        if std::fs::create_dir_all(&self.root).is_err() {
            return false;
        }
        let framed = encode_module_payload(payload);
        std::fs::write(self.path(snapshot_id, module, instance_id), framed).is_ok()
    }

    fn load_data(&self, snapshot_id: i64, module: SerializedModuleType, instance_id: i32) -> Option<Vec<u8>> {
        let framed = std::fs::read(self.path(snapshot_id, module, instance_id)).ok()?;
        Some(decode_module_payload(&framed).expect("on-disk snapshot frame decode failed"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn module_type_code_and_file_name() {
        assert_eq!(SerializedModuleType::RiskEngine.code(), "RE");
        assert_eq!(SerializedModuleType::MatchingEngineRouter.code(), "ME");
        assert_eq!(SerializedModuleType::RiskEngine.file_name(88888, 0), "snapshot_88888_RE_0.dat");
        assert_eq!(SerializedModuleType::MatchingEngineRouter.file_name(88888, 1), "snapshot_88888_ME_1.dat");
    }

    #[test]
    fn in_memory_store_load_roundtrip() {
        let mut proc = InMemorySerializationProcessor::new();
        let payload = b"hello-risk-engine-payload".to_vec();
        assert!(proc.store_data(1, 0, 0, SerializedModuleType::RiskEngine, 0, &payload));
        assert_eq!(proc.load_data(1, SerializedModuleType::RiskEngine, 0), Some(payload));
        // 不存在的键返回 None。
        assert_eq!(proc.load_data(1, SerializedModuleType::MatchingEngineRouter, 0), None);
        assert_eq!(proc.load_data(2, SerializedModuleType::RiskEngine, 0), None);
    }

    #[test]
    fn in_memory_clone_shares_store() {
        let mut a = InMemorySerializationProcessor::new();
        let b = a.clone();
        a.store_data(7, 0, 0, SerializedModuleType::MatchingEngineRouter, 0, b"payload".as_ref());
        // clone 共享同一张表：a 写、b 读得到。
        assert_eq!(b.load_data(7, SerializedModuleType::MatchingEngineRouter, 0), Some(b"payload".to_vec()));
    }

    #[test]
    fn file_store_load_roundtrip() {
        let dir = std::env::temp_dir().join(format!(
            "ecrs_ser_proc_test_{}_{}",
            std::process::id(),
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()
        ));
        let mut proc = FileSerializationProcessor::new(&dir);
        let payload = b"matching-engine-payload".to_vec();
        assert!(proc.store_data(99, 0, 0, SerializedModuleType::MatchingEngineRouter, 0, &payload));
        // 文件名与 Java SnapshotHelper.genSnapshotFileName 一致。
        assert!(dir.join("snapshot_99_ME_0.dat").exists());
        assert_eq!(proc.load_data(99, SerializedModuleType::MatchingEngineRouter, 0), Some(payload));
        assert_eq!(proc.load_data(99, SerializedModuleType::RiskEngine, 0), None);
        std::fs::remove_dir_all(&dir).ok();
    }
}
