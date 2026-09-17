//! Chronicle Wire "RAW" 二进制格式的读取端。
//!
//! Java 版 exchange-core 的快照序列化（各 `writeMarshallable(BytesOut)` / 以 `BytesIn` 为参数的构造器，
//! 例如 `SerializationUtils.java`、`CoreSymbolSpecification.java`）全部建立在
//! `net.openhft.chronicle-bytes` 的 `BytesIn`/`BytesOut` 原语之上（wire 类型为 `WireType.RAW`，
//! 即不带字段名/类型自描述，纯按约定顺序读写）。本模块是这些原语的纯 Rust 重实现——没有单一 Java 文件
//! 与之一一对应，而是照抄 `Bytes`/`BytesIn` 接口里 `readInt`/`readLong`/`readStopBit`/`readUtf8` 等
//! 方法的二进制行为。字节序、变长编码规则必须与 Java 端逐字节一致，否则混合 Java/Rust 的 raft 集群
//! 互相读取对方写出的快照（`.ecs` 文件，外层分帧见 [`super::module_frame`]）就会失败或错读。
use std::fmt;

/// 与 Java 侧解码失败时抛出的 `IllegalStateException`/`BufferUnderflowException` 等对应，
/// 这里显式建模为 `Result` 而非 panic，便于快照加载失败时上层优雅降级。
#[derive(Debug, PartialEq, Eq)]
pub enum ChronicleError {
    /// 缓冲区剩余字节不足以满足本次读取。
    UnexpectedEof { needed: usize, remaining: usize },
    /// stop-bit 变长整数编码超过 64 位（正常数据不应出现，多半意味着读取位置已经错位）。
    StopBitOverflow,
    /// UTF-8 字符串字段包含非法字节序列。
    InvalidUtf8,
    /// 文档头的保留位（见 [`ChronicleReader::read_document_header`]）非零，说明这不是一个普通数据文档。
    NotDataDocument { header: u32 },
}

impl fmt::Display for ChronicleError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ChronicleError::UnexpectedEof { needed, remaining } => {
                write!(f, "chronicle EOF: need {needed} bytes, {remaining} remaining")
            }
            ChronicleError::StopBitOverflow => write!(f, "chronicle stop-bit overflows 64 bits"),
            ChronicleError::InvalidUtf8 => write!(f, "chronicle invalid utf8"),
            ChronicleError::NotDataDocument { header } => {
                write!(f, "chronicle not a data document header={header:#010x}")
            }
        }
    }
}

impl std::error::Error for ChronicleError {}

type Result<T> = std::result::Result<T, ChronicleError>;

/// 对一段字节切片做顺序、只前进的游标读取，镜像 Java `Bytes`/`BytesIn` 的读游标语义
/// （`readPosition` 单调递增，不支持随机寻址，与 `WireIn` 顺序解码字段的用法一致）。
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

    /// 取走接下来的 `n` 个字节并前移游标；越界时返回 `UnexpectedEof` 而不是 panic，
    /// 因为快照文件可能损坏/截断，调用方（快照加载路径）需要能优雅报错。
    #[inline]
    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        if self.remaining() < n {
            return Err(ChronicleError::UnexpectedEof { needed: n, remaining: self.remaining() });
        }
        let s = &self.buf[self.pos..self.pos + n];
        self.pos += n;
        Ok(s)
    }

    /// 对应 Java `Bytes.readByte()`（无符号视角），单字节，无需考虑字节序。
    pub fn read_u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }

    /// 对应 Java `Bytes.readInt()`。Chronicle Bytes 的定长整数按小端存储，
    /// 因此这里用 `from_le_bytes`；与 Java 不一致会导致跨语言读出错误数值而非直接报错，
    /// 所以这个字节序选择必须与写端 [`super::chronicle_writer::ChronicleWriter::write_i32`] 严格对称。
    pub fn read_i32(&mut self) -> Result<i32> {
        let b = self.take(4)?;
        Ok(i32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }

    /// 对应 Java `Bytes.readLong()`，同样是小端定长 8 字节。
    pub fn read_i64(&mut self) -> Result<i64> {
        let b = self.take(8)?;
        Ok(i64::from_le_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]]))
    }

    /// 对应 Java `Bytes.readStopBit()`：stop-bit 变长编码（LEB128 风格），每字节取低 7 位参与数值、
    /// 最高位(0x80)为"后面还有字节"的续接标记，小端序拼接（先读到的字节是低位）。
    /// Chronicle Wire 用它压缩存储字符串长度、集合大小等通常较小的非负整数。
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

    /// 对应 Java `Bytes.readUtf8()`：先读一个 stop-bit 编码的长度，再读定长字节按 UTF-8 解码。
    /// `u64::MAX`（即 stop-bit 全 1，对应 Java 侧写入 -1 表示 null `CharSequence` 的哨兵值）在这里
    /// 被当作空字符串处理，兼容 Java 可能写出的 null 字符串字段；本 Rust 实现自身写出的字符串
    /// （见 [`super::chronicle_writer::ChronicleWriter::write_utf8`]）不会产生这个哨兵值。
    pub fn read_utf8(&mut self) -> Result<String> {
        let len = self.read_stop_bit()?;
        if len == u64::MAX {
            return Ok(String::new());
        }
        let bytes = self.take(len as usize)?;
        std::str::from_utf8(bytes).map(|s| s.to_owned()).map_err(|_| ChronicleError::InvalidUtf8)
    }

    /// 读取 Chronicle Wire 的"文档头"：一个小端 4 字节整数，高 2 位(`0xC000_0000`)是元数据/未就绪标记位，
    /// 低 30 位(`0x3FFF_FFFF`)是紧随其后的文档载荷长度。普通数据文档这两个高位都是 0；
    /// 非 0 说明这不是本模块能处理的数据文档（例如元数据文档），因而报 `NotDataDocument`。
    /// [`super::module_frame`] 里 `.ecs` 外层分帧解出单个 chunk 后，chunk 内部同样是这种头部布局
    /// （那里出于历史原因内联重复了同一段位运算，而不是复用这个方法）。
    pub fn read_document_header(&mut self) -> Result<u32> {
        let header = self.read_i32()? as u32;
        if header & 0xC000_0000 != 0 {
            return Err(ChronicleError::NotDataDocument { header });
        }
        Ok(header & 0x3FFF_FFFF)
    }

    /// 读取一个完整的 Chronicle 数据文档：文档头 + 定长载荷，返回一个作用域局限在载荷内的子 reader，
    /// 使调用方无法越界读到文档边界之外的数据。
    pub fn read_document(&mut self) -> Result<ChronicleReader<'a>> {
        let len = self.read_document_header()? as usize;
        let payload = self.take(len)?;
        Ok(ChronicleReader::new(payload))
    }

    /// 对应 Java `SerializationUtils.readLongHashMap`/`marshallLongHashMap`（`LongObjectHashMap<T>`）：
    /// 4 字节小端 size 前缀（**不是** stop-bit），随后逐条 `i64` key + 由调用方提供的 value 读取器。
    /// 典型用途：`UserProfileService.users`（按 uid 索引的用户档案表）。
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

    /// 对应 Java `SerializationUtils.readIntHashMap`/`marshallIntHashMap`（`IntObjectHashMap<T>`）：
    /// 4 字节小端 size 前缀 + 逐条 `i32` key + value 读取器。
    /// 典型用途：symbol/currency 规格表、订单簿按 symbolId 索引、`LastPriceCacheRecord` 表等。
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

    /// 对应 Java `CoreSymbolSpecification` 内的私有辅助方法 `readTreeMapFromBytes`
    /// （与之对写的 `writeTreeMapToBytes`），注意这两个方法**不在** `SerializationUtils.java` 里，
    /// 是 `CoreSymbolSpecification` 自己为 `TreeMap<Long,Long>`（保证金/杠杆分档表）单独实现的格式：
    /// size 用 **stop-bit** 编码（区别于上面两个 `_keyed_map` 用的定长 4 字节 size），
    /// 随后逐条 `i64` key + `i64` value（无嵌套值读取器，值恒为定长 long）。
    /// 用于 `CoreSymbolSpecification.maintenanceMargin` / `maxLeverage`。
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

    /// 对应 Java `SerializationUtils.readIntLongHashMap`/`marshallIntLongHashMap`（`IntLongHashMap`）：
    /// 4 字节小端 size 前缀 + 逐条 `i32` key + `i64` value（value 是原生 long，无需嵌套读取器）。
    /// 典型用途：借贷资金池（`LoanService.loanPoolAvailable/Borrowed`）、
    /// `RiskEngine` 的 `fees`/`adjustments`/`suspends` 等按币种/symbol 累计的定长数值表。
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

    fn hx(s: &str) -> Vec<u8> {
        (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
    }

    #[test]
    fn read_i32_le_matches_java_writeint() {
        let b = hx("04030201");
        assert_eq!(ChronicleReader::new(&b).read_i32().unwrap(), 0x0102_0304);
    }

    #[test]
    fn read_i64_le_matches_java_writelong() {
        let b = hx("0807060504030201");
        assert_eq!(ChronicleReader::new(&b).read_i64().unwrap(), 0x0102_0304_0506_0708);
    }

    #[test]
    fn read_utf8_short_matches_java() {
        let b = hx("03425443");
        assert_eq!(ChronicleReader::new(&b).read_utf8().unwrap(), "BTC");
    }

    #[test]
    fn read_stop_bit_multibyte_matches_java_len200() {
        let b = hx("c801");
        assert_eq!(ChronicleReader::new(&b).read_stop_bit().unwrap(), 200);
    }

    #[test]
    fn read_currency_object_field_order() {
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
        let b = hx("0200000001000000640000000000000002000000c800000000000000");
        let m = ChronicleReader::new(&b).read_int_long_map().unwrap();
        assert_eq!(m, vec![(1, 100), (2, 200)]);
    }

    #[test]
    fn read_long_keyed_map_with_nested_value() {
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
