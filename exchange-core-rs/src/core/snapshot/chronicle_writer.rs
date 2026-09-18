use std::collections::BTreeMap;

#[derive(Default)]
pub struct ChronicleWriter {
    buf: Vec<u8>,
}

impl ChronicleWriter {
    pub fn new() -> Self {
        ChronicleWriter { buf: Vec::new() }
    }

    pub fn into_bytes(self) -> Vec<u8> {
        self.buf
    }

    pub fn as_bytes(&self) -> &[u8] {
        &self.buf
    }

    pub fn write_i32(&mut self, v: i32) {
        self.buf.extend_from_slice(&v.to_le_bytes());
    }

    pub fn write_i64(&mut self, v: i64) {
        self.buf.extend_from_slice(&v.to_le_bytes());
    }

    pub fn write_u8(&mut self, v: u8) {
        self.buf.push(v);
    }

    pub fn write_stop_bit(&mut self, mut v: u64) {
        loop {
            let mut byte = (v & 0x7f) as u8;
            v >>= 7;
            if v != 0 {
                byte |= 0x80;
                self.buf.push(byte);
            } else {
                self.buf.push(byte);
                break;
            }
        }
    }

    pub fn write_utf8(&mut self, s: &str) {
        self.write_stop_bit(s.len() as u64);
        self.buf.extend_from_slice(s.as_bytes());
    }

    pub fn write_document<F>(&mut self, f: F)
    where
        F: FnOnce(&mut ChronicleWriter),
    {
        let mut inner = ChronicleWriter::new();
        f(&mut inner);
        let payload = inner.into_bytes();
        debug_assert!(payload.len() as u64 <= 0x3FFF_FFFF, "chronicle document payload too long");
        self.write_i32(payload.len() as i32);
        self.buf.extend_from_slice(&payload);
    }

    pub fn write_long_keyed_map<V, F>(&mut self, map: &BTreeMap<i64, V>, mut write_value: F)
    where
        F: FnMut(&mut ChronicleWriter, &V),
    {
        self.write_i32(map.len() as i32);
        for (k, v) in map {
            self.write_i64(*k);
            write_value(self, v);
        }
    }

    pub fn write_int_keyed_map<V, F>(&mut self, map: &BTreeMap<i32, V>, mut write_value: F)
    where
        F: FnMut(&mut ChronicleWriter, &V),
    {
        self.write_i32(map.len() as i32);
        for (k, v) in map {
            self.write_i32(*k);
            write_value(self, v);
        }
    }

    pub fn write_long_long_treemap(&mut self, map: &BTreeMap<i64, i64>) {
        self.write_stop_bit(map.len() as u64);
        for (k, v) in map {
            self.write_i64(*k);
            self.write_i64(*v);
        }
    }

    pub fn write_int_long_map(&mut self, map: &BTreeMap<i32, i64>) {
        self.write_i32(map.len() as i32);
        for (k, v) in map {
            self.write_i32(*k);
            self.write_i64(*v);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::snapshot::chronicle_reader::ChronicleReader;

    fn hex(b: &[u8]) -> String {
        b.iter().map(|x| format!("{x:02x}")).collect()
    }

    #[test]
    fn write_i32_matches_java() {
        let mut w = ChronicleWriter::new();
        w.write_i32(0x0102_0304);
        assert_eq!(hex(w.as_bytes()), "04030201");
    }

    #[test]
    fn write_i64_matches_java() {
        let mut w = ChronicleWriter::new();
        w.write_i64(0x0102_0304_0506_0708);
        assert_eq!(hex(w.as_bytes()), "0807060504030201");
    }

    #[test]
    fn write_utf8_short_matches_java() {
        let mut w = ChronicleWriter::new();
        w.write_utf8("BTC");
        assert_eq!(hex(w.as_bytes()), "03425443");
    }

    #[test]
    fn write_stop_bit_multibyte_matches_java_len200() {
        let mut w = ChronicleWriter::new();
        w.write_stop_bit(200);
        assert_eq!(hex(w.as_bytes()), "c801");
    }

    #[test]
    fn write_currency_object_matches_java() {
        let mut w = ChronicleWriter::new();
        w.write_i32(7);
        w.write_utf8("BTC");
        w.write_i32(8);
        w.write_i32(9000);
        assert_eq!(hex(w.as_bytes()), "07000000034254430800000028230000");
    }

    #[test]
    fn write_document_frame_matches_java() {
        let mut w = ChronicleWriter::new();
        w.write_document(|inner| {
            inner.write_i64(1);
            inner.write_i64(2);
            inner.write_i64(3);
            inner.write_i64(4);
        });
        assert_eq!(
            hex(w.as_bytes()),
            "200000000100000000000000020000000000000003000000000000000400000000000000"
        );
    }

    #[test]
    fn roundtrip_primitives_and_map() {
        let mut w = ChronicleWriter::new();
        w.write_i32(-42);
        w.write_i64(1 << 40);
        w.write_utf8("hello-世界");
        w.write_int_long_map(&BTreeMap::from([(3, 300), (1, 100), (2, 200)]));
        let bytes = w.into_bytes();

        let mut r = ChronicleReader::new(&bytes);
        assert_eq!(r.read_i32().unwrap(), -42);
        assert_eq!(r.read_i64().unwrap(), 1 << 40);
        assert_eq!(r.read_utf8().unwrap(), "hello-世界");
        assert_eq!(r.read_int_long_map().unwrap(), vec![(1, 100), (2, 200), (3, 300)]);
        assert!(r.is_empty());
    }

    #[test]
    fn roundtrip_document_and_nested_map() {
        let mut w = ChronicleWriter::new();
        w.write_document(|inner| {
            inner.write_long_keyed_map(&BTreeMap::from([(5i64, (1i64, 2i64))]), |vw, v| {
                vw.write_i64(v.0);
                vw.write_i64(v.1);
            });
        });
        let bytes = w.into_bytes();

        let mut outer = ChronicleReader::new(&bytes);
        let mut inner = outer.read_document().unwrap();
        let m = inner.read_long_keyed_map(|rr| Ok((rr.read_i64()?, rr.read_i64()?))).unwrap();
        assert_eq!(m, vec![(5, (1, 2))]);
        assert!(inner.is_empty());
        assert!(outer.is_empty());
    }
}
