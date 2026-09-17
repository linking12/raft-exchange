//! 对应 Java `BinaryCommandsProcessor`。
//!
//! Java 版本是有状态的"跨帧二进制命令重组器"：批量数据（如快照恢复时的
//! symbols/accounts 等）会被 `ExchangeApi` 拆成多个定长 `OrderCommand` 帧
//! （每帧携带 orderId/price/reserveBidPrice/size/uid 共 5 个 long 字），按
//! `userCookie` 作为 transferId 累积进 `incomingData`，直到 `symbol == -1`
//! 标记最后一帧到达后，才整体 LZ4 解压+反序列化，派发给
//! `completeMessagesHandler`（BINARY_DATA_COMMAND）或
//! `reportQueriesHandler`（BINARY_DATA_QUERY）。
//!
//! Rust 版本做了大幅简化/阉割：只保留了 `incoming` 这个"transferId ->
//! 已收字数据"缓冲区的数据结构（对应 Java `incomingData` +
//! `TransferRecord`），以及它在 Chronicle 快照里的序列化/反序列化往返
//! （逐字节兼容 Java `writeMarshallable`/构造器读取），但**没有**移植
//! `acceptBinaryFrame`（按帧累积、`symbol==-1` 收尾判定）、
//! `deserializeBinaryCommand`/`deserializeQuery`（按 classCode 反射派发
//! 反序列化）、`reset()`、`stateHash()`、`serializeObject()` 等运行时逻辑。
//! 据 risk_engine.rs 的审计结论，Rust 全代码库目前没有生产路径会向
//! `incoming` 写入数据（批量命令走同步处理，不经过多帧二进制重组），因此
//! 本结构体在运行时实质上恒为空，存在的唯一目的是让快照字节布局与 Java
//! 保持逐字段对齐，便于跨语言快照互操作/对拍。

use std::collections::BTreeMap;

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::{to_btree_i64, ChronicleMarshallable};

/// transferId -> 已收字数据缓冲区。对应 Java `incomingData: LongObjectHashMap<TransferRecord>`。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct BinaryCommandsProcessor {
    pub incoming: BTreeMap<i64, TransferRecord>,
}

/// 单个 transferId 已累积到的分片数据。对应 Java 内部类 `TransferRecord`：
/// `words_transferred` 是已写入的字数（Java 侧还维护 `dataArray` 的容量并按需扩容，
/// Rust 用 `Vec` 语义等价地省去了显式扩容逻辑），`data` 是已收到的 long 字序列。
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

// 对应 Java `TransferRecord.writeMarshallable`/构造器读取：先写 wordsTransfered，
// 再写数组。Java 写的是完整 `dataArray`（含未使用的扩容富余槽位），Rust 的
// `data` 是紧凑 `Vec`，因此写 `data.len()` 而非某个预分配容量——由于本结构体
// 恒为空（见文件头注释），两侧的字节布局差异不会在实际快照中体现。
impl ChronicleMarshallable for TransferRecord {
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

// 对应 Java `writeMarshallable`（`SerializationUtils.marshallLongHashMap(incomingData, bytes)`）
// 与读构造器。
impl ChronicleMarshallable for BinaryCommandsProcessor {
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
        assert_eq!(bytes, vec![0, 0, 0, 0], "empty state should encode as writeInt(0)");
        let back = BinaryCommandsProcessor::chronicle_read(&mut ChronicleReader::new(&bytes)).unwrap();
        assert_eq!(back, bcp);
    }

    #[test]
    fn nonempty_passthrough_roundtrips_bytes() {
        let mut incoming = BTreeMap::new();
        incoming.insert(7i64, TransferRecord { words_transferred: 3, data: vec![10, 20, 30] });
        incoming.insert(9i64, TransferRecord { words_transferred: 1, data: vec![] });
        let bcp = BinaryCommandsProcessor { incoming };
        let mut w = ChronicleWriter::new();
        bcp.chronicle_write(&mut w);
        let bytes = w.into_bytes();
        let back = BinaryCommandsProcessor::chronicle_read(&mut ChronicleReader::new(&bytes)).unwrap();
        assert_eq!(back, bcp, "read-back value should match field-for-field");
        let mut w2 = ChronicleWriter::new();
        back.chronicle_write(&mut w2);
        assert_eq!(w2.into_bytes(), bytes, "write->read->write bytes should be stable");
    }
}
