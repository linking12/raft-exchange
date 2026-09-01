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
}
