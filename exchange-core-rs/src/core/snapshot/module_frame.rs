//! `.ecs` 快照文件的最外层分帧格式：可选 LZ4 压缩 + 一层分片长度前缀 + 内层 Chronicle 文档头，
//! 包住 [`super::chronicle_reader`]/[`super::marshalling`] 能解码的 RiskEngine(RE)/MatchingEngine(ME)
//! 模块载荷。
//!
//! 这一层**不是**对某一个具体 Java 类的移植——Java 端 `.ecs` 是由
//! `exchange.core2.core.processors.journaling.DiskSerializationProcessor`
//! （`storeData`/`loadData`、内部类 `WireToOutputStream2`）通过
//! `net.jpountz.lz4.LZ4FrameOutputStream` + `Bytes`/`WireType.RAW` + `java.io.DataOutputStream`
//! 这几层标准库/第三方库拼出来的文件流，本身没有一个单独描述"文件格式"的类；这里的格式是
//! 通过阅读该类的写出逻辑、并对照 `tests/snapshot_fixtures/re0.ecs`/`me0.ecs`
//! 两个真实 Java 写出的 fixture 逐字节核实后重建的：
//!
//! 1. 可选的 LZ4 Frame 压缩（`LZ4FrameOutputStream`/`LZ4FrameInputStream`），
//!    用标准 LZ4 Frame 魔数 `04 22 4D 18` 探测是否存在；[`decode_module_payload`] 里若命中魔数
//!    就用 `lz4_flex` 的 frame 解码器整体解压，命中不了就当作已经是明文字节处理。
//! 2. 一个 **大端** 4 字节分片长度前缀：对应 `WireToOutputStream2.flush()` 里的
//!    `dos.writeInt(length)`，`dos` 是 `java.io.DataOutputStream`——Java `DataOutputStream.writeInt`
//!    恒为大端，这与内层 Chronicle Wire 自己的字段（一律小端）刻意不同，读的时候不要混淆。
//! 3. 分片内部：一个小端 4 字节 Chronicle 文档头（高 2 位保留/元数据标记，低 30 位为载荷长度，
//!    布局与 [`super::chronicle_reader::ChronicleReader::read_document_header`] 完全一致，
//!    对应 Java 端 `wire.writeBytes(obj)` 写出的 `WireType.RAW` 文档），随后紧跟的就是
//!    RE/ME 模块的实际快照载荷字节。
use std::io::Read;

/// 标准 LZ4 Frame 格式魔数（与 Java 端 `net.jpountz.lz4.LZ4FrameOutputStream` 写出的帧头一致），
/// 用于探测 `.ecs` 文件是否整体做了 LZ4 压缩。
const LZ4_FRAME_MAGIC: [u8; 4] = [0x04, 0x22, 0x4d, 0x18];

#[derive(Debug)]
pub enum ModuleFrameError {
    /// LZ4 帧解压失败（数据损坏，或魔数命中但内容并非合法 LZ4 帧）。
    Lz4(String),
    /// 字节数不足以解出声明的分片/文档长度，说明快照文件被截断或损坏。
    Truncated,
    /// 内层文档头的高位标记非零，不是一个普通数据文档
    /// （对应 [`super::chronicle_reader::ChronicleError::NotDataDocument`]）。
    NotDataDocument(u32),
}

impl std::fmt::Display for ModuleFrameError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            ModuleFrameError::Lz4(e) => write!(f, "LZ4 decompress failed: {e}"),
            ModuleFrameError::Truncated => write!(f, "snapshot module bytes truncated"),
            ModuleFrameError::NotDataDocument(h) => write!(f, "not a data document header={h:#010x}"),
        }
    }
}
impl std::error::Error for ModuleFrameError {}

/// 若字节以 LZ4 Frame 魔数开头则整体解压，否则原样返回——对应 Java 端
/// `LZ4FrameInputStream` 在读入前无条件按 LZ4 帧解码；这里显式探测魔数是为了同时兼容
/// 测试里构造的未压缩明文分片。
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

/// 从一个原始 `.ecs` 文件字节串（可能整体 LZ4 压缩）里解出内层 RE/ME 模块的快照载荷字节，
/// 之后即可交给 [`super::chronicle_reader::ChronicleReader`]/[`super::marshalling`] 按字段解析。
/// 对应 Java 端读取路径 `DiskSerializationProcessor.loadData`（`LZ4FrameInputStream` 解压 +
/// `InputStreamToWire`/`Wire.readBytes` 解出 `WireType.RAW` 文档）。
pub fn decode_module_payload(file_bytes: &[u8]) -> Result<Vec<u8>, ModuleFrameError> {
    let raw = maybe_lz4_decompress(file_bytes)?;
    if raw.len() < 8 {
        return Err(ModuleFrameError::Truncated);
    }
    // 大端分片长度前缀：对应 Java `DataOutputStream.writeInt`（恒大端），
    // 与下面内层文档头的小端字段刻意不同字节序。
    let chunk_len = u32::from_be_bytes([raw[0], raw[1], raw[2], raw[3]]) as usize;
    if raw.len() < 4 + chunk_len {
        return Err(ModuleFrameError::Truncated);
    }
    let chunk = &raw[4..4 + chunk_len];
    if chunk.len() < 4 {
        return Err(ModuleFrameError::Truncated);
    }
    // 内层 Chronicle 文档头：小端，高 2 位保留/元数据标记，低 30 位载荷长度；
    // 与 chronicle_reader::read_document_header 的位运算逻辑一致（此处内联重复，未复用该方法）。
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

/// [`decode_module_payload`] 的逆操作：把已编码好的 RE/ME 模块载荷字节包上一层分片长度前缀
/// （大端）+ 内层 Chronicle 文档头（小端），拼成可写入 `.ecs` 文件的字节串。
/// 注意本函数不做 LZ4 压缩——对应 Java 写入路径里 `Wire.writeBytes` 产出未压缩的 Wire 字节，
/// 压缩是外面再套一层 `LZ4FrameOutputStream` 完成的，与这里的分帧格式是正交的两层。
pub fn encode_module_payload(payload: &[u8]) -> Vec<u8> {
    let doc_len = payload.len() as u32;
    let chunk_len = 4 + payload.len();
    let mut out = Vec::with_capacity(4 + chunk_len);
    out.extend_from_slice(&(chunk_len as u32).to_be_bytes());
    out.extend_from_slice(&doc_len.to_le_bytes());
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

    // 真实的 Java 端写出的快照 fixture：单分片场景下的 RiskEngine(RE) / MatchingEngine(ME) 模块，
    // 用来对拍本模块的分帧解码是否与 Java `DiskSerializationProcessor` 写出的字节完全一致。
    const RE0: &[u8] = include_bytes!("../../../tests/snapshot_fixtures/re0.ecs");
    const ME0: &[u8] = include_bytes!("../../../tests/snapshot_fixtures/me0.ecs");

    #[test]
    fn decode_real_re0_frame_and_verify_head() {
        let payload = decode_module_payload(RE0).expect("failed to decode RE payload");
        let mut r = ChronicleReader::new(&payload);
        assert_eq!(r.read_i32().unwrap(), 0, "shardId is 0 for a single shard");
        assert_eq!(r.read_i64().unwrap(), 0, "shardMask is 0 for a single shard");
        assert_eq!(r.read_i32().unwrap(), 1, "symbolSpecs size=1");
        assert_eq!(r.read_i32().unwrap(), 100, "map key=symbol 100");
        assert_eq!(r.read_i32().unwrap(), 100, "CoreSymbolSpecification.symbolId=100");
        assert_eq!(r.read_u8().unwrap(), 0, "type byte=CURRENCY_EXCHANGE_PAIR(0)");
    }

    #[test]
    fn decode_real_me0_frame_nonempty() {
        let payload = decode_module_payload(ME0).expect("failed to decode ME payload");
        assert!(!payload.is_empty(), "ME payload is non-empty");
    }
}
