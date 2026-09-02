/// 对应 Java `exchange.core2.core.common.SymbolType`。现货移植只用 `CurrencyExchangePair`；
/// 其余变体（期货/期权）仅保留码值以便未来分支/序列化对齐，本期无业务逻辑。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SymbolType {
    CurrencyExchangePair,
    FuturesContractPerpetual,
    FuturesContractDelivery,
    Option,
}

impl SymbolType {
    pub fn code(self) -> i8 {
        match self {
            SymbolType::CurrencyExchangePair => 0,
            SymbolType::FuturesContractPerpetual => 1,
            SymbolType::FuturesContractDelivery => 2,
            SymbolType::Option => 3,
        }
    }

    /// 对应 Java `SymbolType.isFuturesContract(SymbolType type)`：永续 / 交割合约二者之一。
    pub fn is_futures_contract(self) -> bool {
        matches!(self, SymbolType::FuturesContractPerpetual | SymbolType::FuturesContractDelivery)
    }
}

impl Default for SymbolType {
    /// 现货移植的隐含默认值：`CoreSymbolSpecification` 的期货字段全 0/空即代表"非期货"，
    /// 与之配套，`symbol_type` 派生 `Default` 时取 `CurrencyExchangePair`（对应 Java 无显式
    /// 默认值，此处为 Rust `#[derive(Default)]` 契约新增，未改变任何显式构造路径的行为——
    /// 所有既有 spot 构造点都显式指定 `symbol_type`）。
    fn default() -> Self {
        SymbolType::CurrencyExchangePair
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn symbol_type_codes_match_java() {
        assert_eq!(SymbolType::CurrencyExchangePair.code(), 0);
        assert_eq!(SymbolType::FuturesContractPerpetual.code(), 1);
        assert_eq!(SymbolType::FuturesContractDelivery.code(), 2);
        assert_eq!(SymbolType::Option.code(), 3);
    }

    #[test]
    fn is_futures_contract_matches_java() {
        assert!(SymbolType::FuturesContractPerpetual.is_futures_contract());
        assert!(SymbolType::FuturesContractDelivery.is_futures_contract());
        assert!(!SymbolType::CurrencyExchangePair.is_futures_contract());
        assert!(!SymbolType::Option.is_futures_contract());
    }

    #[test]
    fn default_is_currency_exchange_pair() {
        assert_eq!(SymbolType::default(), SymbolType::CurrencyExchangePair);
    }
}
