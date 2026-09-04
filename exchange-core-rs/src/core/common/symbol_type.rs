/// 对应 Java `SymbolType`。现货移植只用 `CurrencyExchangePair`，其余变体仅保留码值。
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
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
    /// 隐含默认值：Rust `Default` 契约新增，Java 无显式默认值；既有构造点均显式指定 `symbol_type`。
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
