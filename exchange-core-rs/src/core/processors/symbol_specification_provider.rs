//! 对应 Java `SymbolSpecificationProvider`（现货子集：add_symbol 的重复 symbolId/(base,quote) 拒绝）。
use std::collections::{BTreeMap, BTreeSet};

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::symbol_type::SymbolType;

/// 对应 Java `SymbolSpecificationProvider`；`spot_pair_index` 对应派生索引 `spotPairIndex`（不进 stateHash/序列化）。
#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize)]
pub struct SymbolSpecificationProvider {
    pub symbols: BTreeMap<i32, CoreSymbolSpecification>,
    pub currencies: BTreeMap<i32, CoreCurrencySpecification>,
    pub spot_pair_index: BTreeSet<(i32, i32)>,
}

impl SymbolSpecificationProvider {
    pub fn new() -> Self {
        Self::default()
    }

    /// 对应 Java `SymbolSpecificationProvider.addSymbol`：拒重复 symbol_id；现货额外拒重复 (base,quote)，期货/期权豁免。
    pub fn add_symbol(&mut self, spec: CoreSymbolSpecification) -> CommandResultCode {
        if self.symbols.contains_key(&spec.symbol_id) {
            return CommandResultCode::SymbolMgmtSymbolAlreadyExists;
        }
        let is_spot = spec.symbol_type == SymbolType::CurrencyExchangePair;
        let pair = (spec.base_currency, spec.quote_currency);
        if is_spot && self.spot_pair_index.contains(&pair) {
            return CommandResultCode::SymbolMgmtSymbolAlreadyExists;
        }
        if is_spot {
            self.spot_pair_index.insert(pair);
        }
        self.symbols.insert(spec.symbol_id, spec);
        CommandResultCode::Success
    }

    pub fn add_currency(&mut self, spec: CoreCurrencySpecification) {
        self.currencies.insert(spec.currency, spec);
    }

    pub fn get_symbol(&self, symbol_id: i32) -> Option<&CoreSymbolSpecification> {
        self.symbols.get(&symbol_id)
    }

    pub fn get_currency(&self, currency: i32) -> Option<&CoreCurrencySpecification> {
        self.currencies.get(&currency)
    }

    /// 对应 Java `findSpotSymbol(int baseCurrency, int quoteCurrency)`（`:77-81`）：反查 base/quote 现货对 spec，线性扫 BTreeMap。
    pub fn find_spot_symbol(&self, base_currency: i32, quote_currency: i32) -> Option<&CoreSymbolSpecification> {
        self.symbols.values().find(|s| {
            s.symbol_type == SymbolType::CurrencyExchangePair
                && s.base_currency == base_currency
                && s.quote_currency == quote_currency
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn spot_spec(symbol_id: i32, base: i32, quote: i32) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: base,
            quote_currency: quote,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    #[test]
    fn add_symbol_succeeds_first_time() {
        let mut provider = SymbolSpecificationProvider::new();
        assert_eq!(provider.add_symbol(spot_spec(1, 1, 2)), CommandResultCode::Success);
        assert!(provider.get_symbol(1).is_some());
    }

    #[test]
    fn add_symbol_rejects_duplicate_symbol_id() {
        let mut provider = SymbolSpecificationProvider::new();
        assert_eq!(provider.add_symbol(spot_spec(1, 1, 2)), CommandResultCode::Success);
        // 同 symbolId，即便 (base,quote) 不同也拒绝。
        let result = provider.add_symbol(spot_spec(1, 3, 4));
        assert_eq!(result, CommandResultCode::SymbolMgmtSymbolAlreadyExists);
        // 原 spec 未被覆盖。
        assert_eq!(provider.get_symbol(1).unwrap().base_currency, 1);
    }

    #[test]
    fn add_symbol_rejects_duplicate_spot_pair() {
        let mut provider = SymbolSpecificationProvider::new();
        assert_eq!(provider.add_symbol(spot_spec(1, 1, 2)), CommandResultCode::Success);
        // 不同 symbolId，但 (base,quote) 重复 —— 现货对唯一性不变式。
        let result = provider.add_symbol(spot_spec(2, 1, 2));
        assert_eq!(result, CommandResultCode::SymbolMgmtSymbolAlreadyExists);
        assert!(provider.get_symbol(2).is_none());
    }

    #[test]
    fn add_symbol_allows_futures_to_share_base_quote() {
        // 期货/期权豁免现货对唯一性——交割合约按交割日合法共享 base/quote。
        let mut provider = SymbolSpecificationProvider::new();
        let mut fut1 = spot_spec(1, 1, 2);
        fut1.symbol_type = SymbolType::FuturesContractDelivery;
        let mut fut2 = spot_spec(2, 1, 2);
        fut2.symbol_type = SymbolType::FuturesContractDelivery;
        assert_eq!(provider.add_symbol(fut1), CommandResultCode::Success);
        assert_eq!(provider.add_symbol(fut2), CommandResultCode::Success);
    }

    #[test]
    fn add_currency_and_get_currency_roundtrip() {
        let mut provider = SymbolSpecificationProvider::new();
        provider.add_currency(CoreCurrencySpecification { currency: 1, currency_scale_k: 100, ..Default::default() });
        assert_eq!(provider.get_currency(1).unwrap().currency_scale_k, 100);
        assert!(provider.get_currency(2).is_none());
    }
}
