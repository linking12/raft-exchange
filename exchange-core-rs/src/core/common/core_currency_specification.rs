#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct CoreCurrencySpecification {
    pub currency: i32,
    pub name: String,
    pub currency_scale_k: i64,
    pub collateral_weight_bps: i32,
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

fn scale_k_to_digit(scale_k: i64) -> i32 {
    if scale_k <= 1 {
        0
    } else {
        (scale_k as u64).ilog10() as i32
    }
}

fn digit_to_scale_k(digit: i32) -> i64 {
    10i64.pow(digit.max(0) as u32)
}

impl ChronicleMarshallable for CoreCurrencySpecification {
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_i32(self.currency);
        w.write_utf8(&self.name);
        w.write_i32(scale_k_to_digit(self.currency_scale_k));
        w.write_i32(self.collateral_weight_bps);
    }

    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let currency = r.read_i32()?;
        let name = r.read_utf8()?;
        let digit = r.read_i32()?;
        let collateral_weight_bps = r.read_i32()?;
        Ok(CoreCurrencySpecification {
            currency,
            name,
            currency_scale_k: digit_to_scale_k(digit),
            collateral_weight_bps,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn currency_spec_holds_scale() {
        let cur = CoreCurrencySpecification { currency: 1, currency_scale_k: 100, ..Default::default() };
        assert_eq!(cur.currency, 1);
        assert_eq!(cur.currency_scale_k, 100);
    }
}
