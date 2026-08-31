//! 对应 Java: exchange.core2.core.common.{CoreCurrencySpecification, CoreSymbolSpecification}
//! （现货子集：symbolId/type/base+quote currency/base+quoteScaleK/taker+makerFee/feeScaleK；
//! margin/loan 相关字段本期不移植）。
use crate::api::enums::SymbolType;

/// 对应 Java `CoreCurrencySpecification`（现货子集：`id` → `currency`，`getCurrencyScaleK()` → `currency_scale_k`）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CoreCurrencySpecification {
    pub currency: i32,
    pub currency_scale_k: i64,
}

/// 对应 Java `CoreSymbolSpecification`（现货子集）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CoreSymbolSpecification {
    pub symbol_id: i32,
    pub symbol_type: SymbolType,
    pub base_currency: i32,
    pub quote_currency: i32,
    pub base_scale_k: i64,
    pub quote_scale_k: i64,
    pub taker_fee: i64,
    pub maker_fee: i64,
    pub fee_scale_k: i64,
}

impl CoreSymbolSpecification {
    /// 对应 Java `CoreSymbolSpecification.isFixedFee()`：`feeScaleK == 0` 表示固定费用（非比例费率）。
    pub fn is_fixed_fee(&self) -> bool {
        self.fee_scale_k == 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn spot_spec(fee_scale_k: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: 1,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 10,
            maker_fee: 5,
            fee_scale_k,
        }
    }

    #[test]
    fn is_fixed_fee_true_when_fee_scale_k_zero() {
        assert!(spot_spec(0).is_fixed_fee());
    }

    #[test]
    fn is_fixed_fee_false_when_fee_scale_k_positive() {
        assert!(!spot_spec(10_000).is_fixed_fee());
    }

    #[test]
    fn currency_spec_holds_scale() {
        let cur = CoreCurrencySpecification { currency: 1, currency_scale_k: 100 };
        assert_eq!(cur.currency, 1);
        assert_eq!(cur.currency_scale_k, 100);
    }
}
