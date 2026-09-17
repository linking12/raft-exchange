//! 对应 Java `BinaryCommandsProcessor` 的**快照分片**(`incomingData`:多段大二进制命令的重组缓冲)。
//!
//! Rust **不处理** `BINARY_DATA_COMMAND`(整套二进制命令流未移植),所以本类型**永不被 Rust 填充** ——
//! 它只为**快照字节兼容**存在:Java 快照在 RE/ME 两个模块里都写一段 `marshallLongHashMap(incomingData)`,
//! 若 Java 在「某多段命令收到一半」时打快照,该 map 非空。本类型忠实**读入→原样写回**这段字节(方案 B),
//! 使 Rust 加载 Java 中途快照不 panic、不丢数据;Rust 自己产的快照里它恒空(`writeInt(0)`)。
//!
//! 注:`incoming` 不进 Rust 的 `state_hash`(Rust 恒空,且不处理二进制命令,与该子系统整体未移植一致)。
use std::collections::BTreeMap;

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::{to_btree_i64, ChronicleMarshallable};

/// 对应 Java `BinaryCommandsProcessor.incomingData`(`transferId → TransferRecord`)。仅快照透传,Rust 恒空。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct BinaryCommandsProcessor {
    pub incoming: BTreeMap<i64, TransferRecord>,
}

/// 对应 Java `BinaryCommandsProcessor.TransferRecord`:某个 transferId 已收到的分片(字长游标 + 数据字)。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TransferRecord {
    pub words_transferred: i32,
    pub data: Vec<i64>,
}

impl BinaryCommandsProcessor {
    pub fn new() -> Self {
        Self::default()
    }
}

impl ChronicleMarshallable for TransferRecord {
    /// Java `TransferRecord.writeMarshallable`:`writeInt(wordsTransfered)` + `marshallLongArray(dataArray)`
    /// (`marshallLongArray` = `writeInt(len)` + 逐个 `writeLong`)。
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_i32(self.words_transferred);
        w.write_i32(self.data.len() as i32);
        for &word in &self.data {
            w.write_i64(word);
        }
    }
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let words_transferred = r.read_i32()?;
        let len = r.read_i32()?;
        let mut data = Vec::with_capacity(len.max(0) as usize);
        for _ in 0..len {
            data.push(r.read_i64()?);
        }
        Ok(TransferRecord { words_transferred, data })
    }
}

impl ChronicleMarshallable for BinaryCommandsProcessor {
    /// Java `BinaryCommandsProcessor.writeMarshallable`:`marshallLongHashMap(incomingData)`
    /// (`writeInt(size)` + 逐项 `long key` + `TransferRecord`)。空态 = `writeInt(0)`。
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_long_keyed_map(&self.incoming, |vw, v| v.chronicle_write(vw));
    }
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let incoming = to_btree_i64(r.read_long_keyed_map(TransferRecord::chronicle_read)?);
        Ok(BinaryCommandsProcessor { incoming })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_roundtrips_as_single_zero_int() {
        let bcp = BinaryCommandsProcessor::new();
        let mut w = ChronicleWriter::new();
        bcp.chronicle_write(&mut w);
        let bytes = w.into_bytes();
        assert_eq!(bytes, vec![0, 0, 0, 0], "空态 = writeInt(0)");
        let back = BinaryCommandsProcessor::chronicle_read(&mut ChronicleReader::new(&bytes)).unwrap();
        assert_eq!(back, bcp);
    }

    #[test]
    fn nonempty_passthrough_roundtrips_bytes() {
        // 模拟 Java 中途快照:两个 transferId,各带分片数据。Rust 忠实透传。
        let mut incoming = BTreeMap::new();
        incoming.insert(7i64, TransferRecord { words_transferred: 3, data: vec![10, 20, 30] });
        incoming.insert(9i64, TransferRecord { words_transferred: 1, data: vec![] });
        let bcp = BinaryCommandsProcessor { incoming };
        let mut w = ChronicleWriter::new();
        bcp.chronicle_write(&mut w);
        let bytes = w.into_bytes();
        let back = BinaryCommandsProcessor::chronicle_read(&mut ChronicleReader::new(&bytes)).unwrap();
        assert_eq!(back, bcp, "读入→写回应逐字段一致");
        let mut w2 = ChronicleWriter::new();
        back.chronicle_write(&mut w2);
        assert_eq!(w2.into_bytes(), bytes, "write→read→write 字节稳定");
    }
}
