use std::io::Read;

const LZ4_FRAME_MAGIC: [u8; 4] = [0x04, 0x22, 0x4d, 0x18];

#[derive(Debug)]
pub enum ModuleFrameError {
    Lz4(String),
    Truncated,
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

pub fn decode_module_payload(file_bytes: &[u8]) -> Result<Vec<u8>, ModuleFrameError> {
    let raw = maybe_lz4_decompress(file_bytes)?;
    if raw.len() < 8 {
        return Err(ModuleFrameError::Truncated);
    }

    let chunk_len = u32::from_be_bytes([raw[0], raw[1], raw[2], raw[3]]) as usize;
    if raw.len() < 4 + chunk_len {
        return Err(ModuleFrameError::Truncated);
    }
    let chunk = &raw[4..4 + chunk_len];
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
