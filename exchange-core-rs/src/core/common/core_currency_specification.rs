//! 对应 Java: exchange.core2.core.common.CoreCurrencySpecification
//! （现货子集：`id` → `currency`，`getCurrencyScaleK()` → `currency_scale_k`；P5 Task 1
//! 新增 `collateralWeightBps`（Cross 借贷抵押折算权重，per-currency，非 per-symbol））。

/// 对应 Java `CoreCurrencySpecification`。
///
/// Ruling P5-A：`#[derive(Default)]` 让新增的 `collateral_weight_bps` 在所有既有 P1-P4
/// 构造点上零值兜底（`0` = 该币种不可作为 Cross 抵押，`LOAN_COLLATERAL_NOT_ALLOWED`，
/// 即"未配置借贷"时的安全默认）——所有既有构造点已改用 `..Default::default()` 展开语法。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct CoreCurrencySpecification {
    pub currency: i32,
    pub currency_scale_k: i64,
    /// Cross 借贷抵押折算权重（bps）：`0` = 该币种不可用作 Cross 抵押
    /// （`LoanService::collateral_weight_for_base` 门守 `LOAN_COLLATERAL_NOT_ALLOWED`）；
    /// 对应 Java `CoreCurrencySpecification.collateralWeightBps`。只此一处 mutation point：
    /// `ADD_LOAN` 的 `SymbolLoanConfig` apply 路径（本 Task 未落地，仅字段本身）。
    pub collateral_weight_bps: i64,
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
