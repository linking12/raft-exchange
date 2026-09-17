//! Chronicle Wire RAW 二进制读取器：读 Java 快照(`MemorySerializationProcessor` 产出)。
//! 编码规范(见 memory `snapshot-chronicle-format`,已用 fixture 逐字节验证):
//! - int  = 小端 4 字节；long = 小端 8 字节
//! - utf8 = stop-bit 长度(LEB128,7 位/字节,高位续位)+ UTF8 字节
//! - 对象 = 字段按 `writeMarshallable` 顺序,无包裹
//! - map  = int(size) + 逐项(key + value)
//! - 文档帧 = 4 字节小端头(低 30 位为 payload 长度,高 2 位为控制标志)+ payload
//!
//! 只读、不 panic 于正常输入；越界/非法编码返回 `Err`。字节序、stop-bit 与 Java `net.openhft.chronicle.bytes` 对齐。

use std::fmt;

/// 读取错误。
#[derive(Debug, PartialEq, Eq)]
pub enum ChronicleError {
    /// 剩余字节不足以读取请求的类型。
    UnexpectedEof { needed: usize, remaining: usize },
    /// stop-bit 编码超过 64 位仍未终止。
    StopBitOverflow,
    /// UTF8 字节非法。
    InvalidUtf8,
    /// 文档头控制位表示非数据(meta / 未完成)文档。
    NotDataDocument { header: u32 },
}

impl fmt::Display for ChronicleError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ChronicleError::UnexpectedEof { needed, remaining } => {
                write!(f, "chronicle EOF: 需 {needed} 字节, 剩 {remaining}")
            }
            ChronicleError::StopBitOverflow => write!(f, "chronicle stop-bit 溢出 64 位"),
            ChronicleError::InvalidUtf8 => write!(f, "chronicle utf8 非法"),
            ChronicleError::NotDataDocument { header } => {
                write!(f, "chronicle 非数据文档 header={header:#010x}")
            }
        }
    }
}

impl std::error::Error for ChronicleError {}

type Result<T> = std::result::Result<T, ChronicleError>;

/// 游标式读取器，包裹一段字节。
pub struct ChronicleReader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> ChronicleReader<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        ChronicleReader { buf, pos: 0 }
    }

    #[inline]
    pub fn remaining(&self) -> usize {
        self.buf.len() - self.pos
    }

    #[inline]
    pub fn is_empty(&self) -> bool {
        self.pos >= self.buf.len()
    }

    #[inline]
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        if self.remaining() < n {
            return Err(ChronicleError::UnexpectedEof { needed: n, remaining: self.remaining() });
        }
        let s = &self.buf[self.pos..self.pos + n];
        self.pos += n;
        Ok(s)
    }

    /// 小端 i8/u8。
    pub fn read_u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }

    /// 小端 i32（Chronicle `writeInt`）。
    pub fn read_i32(&mut self) -> Result<i32> {
        let b = self.take(4)?;
        Ok(i32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }

    /// 小端 i64（Chronicle `writeLong`）。
    pub fn read_i64(&mut self) -> Result<i64> {
        let b = self.take(8)?;
        Ok(i64::from_le_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]]))
    }

    /// stop-bit 无符号（LEB128，7 位/字节,高位续位）。用于长度前缀等非负值。
    pub fn read_stop_bit(&mut self) -> Result<u64> {
        let mut result: u64 = 0;
        let mut shift: u32 = 0;
        loop {
            let byte = self.read_u8()?;
            result |= ((byte & 0x7f) as u64) << shift;
            if byte & 0x80 == 0 {
                return Ok(result);
            }
            shift += 7;
            if shift >= 64 {
                return Err(ChronicleError::StopBitOverflow);
            }
        }
    }

    /// utf8 字符串（Chronicle `writeUtf8`）：stop-bit 长度 + UTF8 字节。
    /// Chronicle 用 -1(stop-bit) 表示 null；这里 null 归一为空串（快照里字符串字段非空）。
    pub fn read_utf8(&mut self) -> Result<String> {
        let len = self.read_stop_bit()?;
        if len == u64::MAX {
            return Ok(String::new()); // null 归一
        }
        let bytes = self.take(len as usize)?;
        std::str::from_utf8(bytes).map(|s| s.to_owned()).map_err(|_| ChronicleError::InvalidUtf8)
    }

    /// 读文档帧头：4 字节小端,低 30 位=payload 长度,高 2 位=控制标志(数据文档须为 0)。
    /// 返回 payload 长度；调用方随后按该长度读 payload。
    pub fn read_document_header(&mut self) -> Result<u32> {
        let header = self.read_i32()? as u32;
        // 高 2 位:bit31=not-complete, bit30=meta-data。数据文档二者皆 0。
        if header & 0xC000_0000 != 0 {
            return Err(ChronicleError::NotDataDocument { header });
        }
        Ok(header & 0x3FFF_FFFF)
    }

    /// 进入一个 `writeBytes` 文档:读 4 字节长度头,返回该 payload 的独立读取器。
    pub fn read_document(&mut self) -> Result<ChronicleReader<'a>> {
        let len = self.read_document_header()? as usize;
        let payload = self.take(len)?;
        Ok(ChronicleReader::new(payload))
    }

    /// `SerializationUtils.marshallLongHashMap`：int(size) + 逐项(long key + value)。
    /// `read_value` 读单个 value(通常是嵌套对象的 `writeMarshallable`)。返回 (key, value) 序列(读序=Java 写序,无关紧要)。
    pub fn read_long_keyed_map<V, F>(&mut self, mut read_value: F) -> Result<Vec<(i64, V)>>
    where
        F: FnMut(&mut ChronicleReader<'a>) -> Result<V>,
    {
        let size = self.read_i32()?;
        let mut out = Vec::with_capacity(size.max(0) as usize);
        for _ in 0..size {
            let k = self.read_i64()?;
            let v = read_value(self)?;
            out.push((k, v));
        }
        Ok(out)
    }

    /// `SerializationUtils.marshallIntHashMap`：int(size) + 逐项(int key + value)。
    pub fn read_int_keyed_map<V, F>(&mut self, mut read_value: F) -> Result<Vec<(i32, V)>>
    where
        F: FnMut(&mut ChronicleReader<'a>) -> Result<V>,
    {
        let size = self.read_i32()?;
        let mut out = Vec::with_capacity(size.max(0) as usize);
        for _ in 0..size {
            let k = self.read_i32()?;
            let v = read_value(self)?;
            out.push((k, v));
        }
        Ok(out)
    }

    /// `CoreSymbolSpecification.writeTreeMapToBytes`：**stop-bit** size + 逐项(long key + long value)。
    /// 注意 size 是 stop-bit(非 int),用于 maintenanceMargin / maxLeverage。
    pub fn read_long_long_treemap(&mut self) -> Result<Vec<(i64, i64)>> {
        let size = self.read_stop_bit()?;
        let mut out = Vec::with_capacity(size as usize);
        for _ in 0..size {
            let k = self.read_i64()?;
            let v = self.read_i64()?;
            out.push((k, v));
        }
        Ok(out)
    }

    /// `SerializationUtils.marshallIntLongHashMap`：int(size) + 逐项(int key + long value)。
    pub fn read_int_long_map(&mut self) -> Result<Vec<(i32, i64)>> {
        let size = self.read_i32()?;
        let mut out = Vec::with_capacity(size.max(0) as usize);
        for _ in 0..size {
            let k = self.read_i32()?;
            let v = self.read_i64()?;
            out.push((k, v));
        }
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// hex 串 → Vec<u8>。
    fn hx(s: &str) -> Vec<u8> {
        (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
    }

    #[test]
    fn read_i32_le_matches_java_writeint() {
        // Java writeInt(0x01020304) → 04030201
        let b = hx("04030201");
        assert_eq!(ChronicleReader::new(&b).read_i32().unwrap(), 0x0102_0304);
    }

    #[test]
    fn read_i64_le_matches_java_writelong() {
        // Java writeLong(0x0102030405060708) → 0807060504030201
        let b = hx("0807060504030201");
        assert_eq!(ChronicleReader::new(&b).read_i64().unwrap(), 0x0102_0304_0506_0708);
    }

    #[test]
    fn read_utf8_short_matches_java() {
        // Java writeUtf8("BTC") → 03 425443
        let b = hx("03425443");
        assert_eq!(ChronicleReader::new(&b).read_utf8().unwrap(), "BTC");
    }

    #[test]
    fn read_stop_bit_multibyte_matches_java_len200() {
        // Java writeUtf8(200×'x') 长度前缀 → c8 01
        let b = hx("c801");
        assert_eq!(ChronicleReader::new(&b).read_stop_bit().unwrap(), 200);
    }

    #[test]
    fn read_currency_object_field_order() {
        // Java CoreCurrencySpecification(id=7,name="BTC",digit=8,cwBps=9000).writeMarshallable
        //   → 07000000 03425443 08000000 28230000
        let b = hx("07000000034254430800000028230000");
        let mut r = ChronicleReader::new(&b);
        assert_eq!(r.read_i32().unwrap(), 7);
        assert_eq!(r.read_utf8().unwrap(), "BTC");
        assert_eq!(r.read_i32().unwrap(), 8);
        assert_eq!(r.read_i32().unwrap(), 9000);
        assert!(r.is_empty());
    }

    #[test]
    fn read_document_frame_strips_4byte_le_length() {
        // Java wire.writeBytes(LastPriceCacheRecord(1,2,3,4)) → 20000000 + 32B inner
        let b = hx("200000000100000000000000020000000000000003000000000000000400000000000000");
        let mut outer = ChronicleReader::new(&b);
        let mut inner = outer.read_document().unwrap();
        assert_eq!(inner.read_i64().unwrap(), 1);
        assert_eq!(inner.read_i64().unwrap(), 2);
        assert_eq!(inner.read_i64().unwrap(), 3);
        assert_eq!(inner.read_i64().unwrap(), 4);
        assert!(inner.is_empty());
        assert!(outer.is_empty());
    }

    #[test]
    fn read_int_long_map_size_prefixed() {
        // size=2, {1:100, 2:200}: 02000000 01000000 6400...00 02000000 c800...00
        let b = hx("0200000001000000640000000000000002000000c800000000000000");
        let m = ChronicleReader::new(&b).read_int_long_map().unwrap();
        assert_eq!(m, vec![(1, 100), (2, 200)]);
    }

    #[test]
    fn read_long_keyed_map_with_nested_value() {
        // size=1, key=5, value=LastPriceCacheRecord(1,2,3,4)(32B four LE longs)
        let b = hx("010000000500000000000000\
0100000000000000020000000000000003000000000000000400000000000000");
        let m = ChronicleReader::new(&b)
            .read_long_keyed_map(|r| {
                let a = r.read_i64()?;
                let bb = r.read_i64()?;
                let c = r.read_i64()?;
                let d = r.read_i64()?;
                Ok((a, bb, c, d))
            })
            .unwrap();
        assert_eq!(m, vec![(5, (1, 2, 3, 4))]);
    }

    #[test]
    fn eof_is_error_not_panic() {
        let b = hx("0102");
        assert_eq!(
            ChronicleReader::new(&b).read_i32(),
            Err(ChronicleError::UnexpectedEof { needed: 4, remaining: 2 })
        );
    }
}
