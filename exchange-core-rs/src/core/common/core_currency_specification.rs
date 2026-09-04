//! 对应 Java `CoreCurrencySpecification`（现货子集 + P5 新增 `collateralWeightBps`，per-currency）。

/// 对应 Java `CoreCurrencySpecification`。Ruling P5-A：`#[derive(Default)]` 零值兜底（`0` = 不可作为 Cross 抵押）。
#[derive(Debug, Clone, PartialEq, Eq, Default, serde::Serialize, serde::Deserialize)]
pub struct CoreCurrencySpecification {
    pub currency: i32,
    pub currency_scale_k: i64,
    /// Cross 借贷抵押折算权重（bps）：`0` = 不可用作 Cross 抵押。对应 Java `collateralWeightBps`。
    pub collateral_weight_bps: i32,
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
