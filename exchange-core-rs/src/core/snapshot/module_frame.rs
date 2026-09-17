//! 快照模块文件(`{exchangeId}_snapshot_{id}_{RE|ME}{shard}.ecs`)的外框解码:
//! `.ecs` 文件 = [可选 LZ4 Frame] → [4字节大端流分块长度(InputStreamToWire)] → [4字节小端 Chronicle 文档头] → payload。
//! `DiskSerializationProcessor` 恒 LZ4;生产 `MemorySerializationProcessor` 默认不压(compression=false)。据 LZ4 魔数 autodetect。
//! 输出 payload(模块 `writeMarshallable` 字节),交 [`super::chronicle_reader::ChronicleReader`] 解析。

use std::io::Read;

/// LZ4 Frame 魔数(小端 0x184D2204)。
const LZ4_FRAME_MAGIC: [u8; 4] = [0x04, 0x22, 0x4d, 0x18];

#[derive(Debug)]
pub enum ModuleFrameError {
    Lz4(String),
    Truncated,
    /// Chronicle 文档头控制位表示非数据文档。
    NotDataDocument(u32),
}

impl std::fmt::Display for ModuleFrameError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ModuleFrameError::Lz4(e) => write!(f, "LZ4 解压失败: {e}"),
            ModuleFrameError::Truncated => write!(f, "快照模块字节被截断"),
            ModuleFrameError::NotDataDocument(h) => write!(f, "非数据文档 header={h:#010x}"),
        }
    }
}
impl std::error::Error for ModuleFrameError {}

/// 若以 LZ4 Frame 魔数开头则解压,否则原样返回。
fn maybe_lz4_decompress(bytes: &[u8]) -> Result<Vec<u8>, ModuleFrameError> {
    if bytes.len() >= 4 && bytes[0..4] == LZ4_FRAME_MAGIC {
        let mut decoder = lz4_flex::frame::FrameDecoder::new(bytes);
        let mut out = Vec::new();
        decoder.read_to_end(&mut out).map_err(|e| ModuleFrameError::Lz4(e.to_string()))?;
        Ok(out)
    } else {
        Ok(bytes.to_vec())
    }
}

/// 解出模块 payload(`writeMarshallable` 字节):autodetect LZ4 → 剥大端分块长度 → 剥小端文档头。
pub fn decode_module_payload(file_bytes: &[u8]) -> Result<Vec<u8>, ModuleFrameError> {
    let raw = maybe_lz4_decompress(file_bytes)?;
    if raw.len() < 8 {
        return Err(ModuleFrameError::Truncated);
    }
    // [0..4) 大端:InputStreamToWire 流分块长度(= 后续文档头+payload 的字节数)。
    let chunk_len = u32::from_be_bytes([raw[0], raw[1], raw[2], raw[3]]) as usize;
    if raw.len() < 4 + chunk_len {
        return Err(ModuleFrameError::Truncated);
    }
    let chunk = &raw[4..4 + chunk_len];
    // [0..4) 小端:Chronicle writeBytes 文档头(低30位=payload 长度,高2位=控制位,数据文档为 0)。
    if chunk.len() < 4 {
        return Err(ModuleFrameError::Truncated);
    }
    let header = u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
    if header & 0xC000_0000 != 0 {
        return Err(ModuleFrameError::NotDataDocument(header));
    }
    let payload_len = (header & 0x3FFF_FFFF) as usize;
    if chunk.len() < 4 + payload_len {
        return Err(ModuleFrameError::Truncated);
    }
    Ok(chunk[4..4 + payload_len].to_vec())
}

/// 把模块 payload 封成 Java(`MemorySerializationProcessor` 未压缩路径)可读的模块字节:
/// `[4字节大端分块长度][4字节小端文档头][payload]`(不压缩;Java autodetect 无 LZ4 魔数即当未压缩)。
/// 与 [`decode_module_payload`] 互逆。
pub fn encode_module_payload(payload: &[u8]) -> Vec<u8> {
    let doc_len = payload.len() as u32; // 数据文档,控制位 0
    let chunk_len = 4 + payload.len(); // LE 文档头(4)+ payload
    let mut out = Vec::with_capacity(4 + chunk_len);
    out.extend_from_slice(&(chunk_len as u32).to_be_bytes()); // 大端分块长度
    out.extend_from_slice(&doc_len.to_le_bytes()); // 小端文档头
    out.extend_from_slice(payload);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::snapshot::chronicle_reader::ChronicleReader;

    #[test]
    fn encode_decode_module_roundtrip() {
        let payload = b"\x01\x02\x03\x04hello";
        let framed = encode_module_payload(payload);
        assert_eq!(decode_module_payload(&framed).unwrap(), payload);
    }

    // 真实 Java 快照(单分片,2 币种带 name + symbol 100 + user 42 + 余额),由
    // exchange-core/src/test/.../snapshot/SnapshotProduce.java 产出。
    const RE0: &[u8] = include_bytes!("../../../tests/snapshot_fixtures/re0.ecs");
    const ME0: &[u8] = include_bytes!("../../../tests/snapshot_fixtures/me0.ecs");

    #[test]
    fn decode_real_re0_frame_and_verify_head() {
        let payload = decode_module_payload(RE0).expect("解出 RE payload");
        let mut r = ChronicleReader::new(&payload);
        // RiskEngine.writeMarshallable 头:shardId(int) + shardMask(long)
        assert_eq!(r.read_i32().unwrap(), 0, "shardId 单分片=0");
        assert_eq!(r.read_i64().unwrap(), 0, "shardMask 单分片=0");
        // symbolSpecificationProvider.writeMarshallable → marshallIntHashMap(symbolSpecs): size + key + spec
        assert_eq!(r.read_i32().unwrap(), 1, "symbolSpecs size=1");
        assert_eq!(r.read_i32().unwrap(), 100, "map key=symbol 100");
        assert_eq!(r.read_i32().unwrap(), 100, "CoreSymbolSpecification.symbolId=100");
        assert_eq!(r.read_u8().unwrap(), 0, "type byte=CURRENCY_EXCHANGE_PAIR(0)");
    }

    #[test]
    fn decode_real_me0_frame_nonempty() {
        let payload = decode_module_payload(ME0).expect("解出 ME payload");
        assert!(!payload.is_empty(), "ME payload 非空");
    }
}
