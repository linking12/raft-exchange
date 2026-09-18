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

    pub fn of_code(code: i8) -> Self {
        match code {
            0 => SymbolType::CurrencyExchangePair,
            1 => SymbolType::FuturesContractPerpetual,
            2 => SymbolType::FuturesContractDelivery,
            3 => SymbolType::Option,
            c => panic!("unknown SymbolType code {c}"),
        }
    }

    pub fn is_futures_contract(self) -> bool {
        matches!(self, SymbolType::FuturesContractPerpetual | SymbolType::FuturesContractDelivery)
    }
}

impl Default for SymbolType {
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
