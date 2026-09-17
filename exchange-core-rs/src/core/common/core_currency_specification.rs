//! 对应 Java `exchange.core2.core.common.CoreCurrencySpecification`。币种静态配置。

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct CoreCurrencySpecification {
    /// 对应 Java `id`。
    pub currency: i32,
    pub name: String,
    /// 对应 Java `getCurrencyScaleK()`(即 `TenPowers.pow10(digit)`)。线上/内存态直接存
    /// 缩放后的 10^digit,持久化时再经 `scale_k_to_digit` 换算回 Java 的 `digit` 精度位数。
    pub currency_scale_k: i64,
    /// 对应 Java `collateralWeightBps`:Cross 抵押折价率(bps);0 = 该币不能作 Cross 抵押。
    /// 唯一 mutation 入口对应 Java `updateCollateralWeight`,仅供 ADD_LOAN 装配调用。
    pub collateral_weight_bps: i32,
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

/// 把内存态的 `currency_scale_k`(10 的幂)还原成 Java `digit` 字段(精度位数),
/// 与 `chronicle_write`/`chronicle_read` 配对,保证快照格式与 Java 端一致。
fn scale_k_to_digit(scale_k: i64) -> i32 {
    if scale_k <= 1 {
        0
    } else {
        (scale_k as u64).ilog10() as i32
    }
}
/// `scale_k_to_digit` 的逆运算,对应 Java `TenPowers.pow10(digit)`。
fn digit_to_scale_k(digit: i32) -> i64 {
    10i64.pow(digit.max(0) as u32)
}

/// 对应 Java `writeMarshallable`/构造函数 `CoreCurrencySpecification(BytesIn)`:
/// 字段顺序 id→name→digit→collateralWeightBps 必须与 Java 保持一致。
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
