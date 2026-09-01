//! 对应 Java: exchange.core2.core.common.CoreCurrencySpecification
//! （现货子集：`id` → `currency`，`getCurrencyScaleK()` → `currency_scale_k`）。

/// 对应 Java `CoreCurrencySpecification`。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CoreCurrencySpecification {
    pub currency: i32,
    pub currency_scale_k: i64,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn currency_spec_holds_scale() {
        let cur = CoreCurrencySpecification { currency: 1, currency_scale_k: 100 };
        assert_eq!(cur.currency, 1);
        assert_eq!(cur.currency_scale_k, 100);
    }
}
