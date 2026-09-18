use std::collections::BTreeMap;

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::{to_btree_i64, ChronicleMarshallable};

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct BinaryCommandsProcessor {
    pub incoming: BTreeMap<i64, TransferRecord>,
}

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
