//! 对应 Java `CoreCurrencySpecification`（现货子集 + `collateralWeightBps`，per-currency）。

/// 对应 Java `CoreCurrencySpecification`：`#[derive(Default)]` 零值兜底（`0` = 不可作为 Cross 抵押）。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct CoreCurrencySpecification {
    pub currency: i32,
    /// 对应 Java `name`：纯展示标签,引擎不参与计算,但进 Java `stateHash` 且随 `ADD_CURRENCY` 复制。
    /// 为跨语言快照互操作(读回/写出 Java 格式)与 stateHash 一致而携带;默认空串。
    pub name: String,
    pub currency_scale_k: i64,
    /// Cross 借贷抵押折算权重（bps）：`0` = 不可用作 Cross 抵押。对应 Java `collateralWeightBps`。
    pub collateral_weight_bps: i32,
}


// ---- Chronicle 快照读写(见 crate::core::snapshot;字段序照 Java writeMarshallable)----
use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

/// `currency_scale_k`(=10^digit)↔ Java `digit` 可逆变换。
fn scale_k_to_digit(scale_k: i64) -> i32 {
    if scale_k <= 1 {
        0
    } else {
        (scale_k as u64).ilog10() as i32
    }
}
fn digit_to_scale_k(digit: i32) -> i64 {
    (0..digit.max(0)).fold(1i64, |acc, _| acc * 10)
}

impl ChronicleMarshallable for CoreCurrencySpecification {
    /// Java `CoreCurrencySpecification.writeMarshallable`：`id(int) name(utf8) digit(int) collateralWeightBps(int)`。
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
