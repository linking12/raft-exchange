use std::cell::RefCell;
use std::collections::BTreeMap;
use std::path::PathBuf;
use std::rc::Rc;

use crate::core::snapshot::module_frame::{decode_module_payload, encode_module_payload};

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

    pub fn file_name(self, snapshot_id: i64, instance_id: i32) -> String {
        format!("snapshot_{}_{}_{}.dat", snapshot_id, self.code(), instance_id)
    }
}

pub trait SerializationProcessor {

    fn store_data(
        &mut self,
        snapshot_id: i64,
        seq: i64,
        timestamp_ns: i64,
        module: SerializedModuleType,
        instance_id: i32,
        payload: &[u8],
    ) -> bool;

    fn load_data(&self, snapshot_id: i64, module: SerializedModuleType, instance_id: i32) -> Option<Vec<u8>>;
}

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

        assert_eq!(proc.load_data(1, SerializedModuleType::MatchingEngineRouter, 0), None);
        assert_eq!(proc.load_data(2, SerializedModuleType::RiskEngine, 0), None);
    }

    #[test]
    fn in_memory_clone_shares_store() {
        let mut a = InMemorySerializationProcessor::new();
        let b = a.clone();
        a.store_data(7, 0, 0, SerializedModuleType::MatchingEngineRouter, 0, b"payload".as_ref());

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

        assert!(dir.join("snapshot_99_ME_0.dat").exists());
        assert_eq!(proc.load_data(99, SerializedModuleType::MatchingEngineRouter, 0), Some(payload));
        assert_eq!(proc.load_data(99, SerializedModuleType::RiskEngine, 0), None);
        std::fs::remove_dir_all(&dir).ok();
    }
}
