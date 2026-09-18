use std::fmt;

#[derive(Debug, PartialEq, Eq)]
pub enum ChronicleError {
    UnexpectedEof { needed: usize, remaining: usize },
    StopBitOverflow,
    InvalidUtf8,
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

    pub fn read_u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }

    pub fn read_i32(&mut self) -> Result<i32> {
        let b = self.take(4)?;
        Ok(i32::from_le_bytes([b[0], b[1], b[2], b[3]]))
    }

    pub fn read_i64(&mut self) -> Result<i64> {
        let b = self.take(8)?;
        Ok(i64::from_le_bytes([b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7]]))
    }

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

    pub fn read_utf8(&mut self) -> Result<String> {
        let len = self.read_stop_bit()?;
        if len == u64::MAX {
            return Ok(String::new());
        }
        let bytes = self.take(len as usize)?;
        std::str::from_utf8(bytes).map(|s| s.to_owned()).map_err(|_| ChronicleError::InvalidUtf8)
    }

    pub fn read_document_header(&mut self) -> Result<u32> {
        let header = self.read_i32()? as u32;
        if header & 0xC000_0000 != 0 {
            return Err(ChronicleError::NotDataDocument { header });
        }
        Ok(header & 0x3FFF_FFFF)
    }

    pub fn read_document(&mut self) -> Result<ChronicleReader<'a>> {
        let len = self.read_document_header()? as usize;
        let payload = self.take(len)?;
        Ok(ChronicleReader::new(payload))
    }

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
