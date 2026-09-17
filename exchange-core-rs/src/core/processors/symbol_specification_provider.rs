//! 对应 Java `SymbolSpecificationProvider`：交易对/币种元数据的注册表，
//! 并维护"现货对 (base,quote) -> symbolId"的派生索引，供 loan 子系统按币种
//! 反查现货对做估值/折价/强平定位用。

use std::collections::{BTreeMap, BTreeSet};

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::symbol_type::SymbolType;

/// symbolId -> symbol 元数据、currency -> 币种元数据，以及现货对派生索引。
/// 对应 Java `symbolSpecs: IntObjectHashMap<CoreSymbolSpecification>`
/// （`currencies` 是 Rust 新增字段，Java 版本币种元数据不在本类维护）。
///
/// `spot_pair_index` 对应 Java `spotPairIndex: LongIntHashMap`（key 为
/// `base<<32|quote` 编码）：纯派生态，不参与快照序列化，仅在 `chronicle_read`
/// 之后由 `rebuild_spot_pair_index` 从 `symbols` 重建；只索引
/// `SymbolType::CurrencyExchangePair`（现货对），期货/期权按交割日合法
/// 共享 base/quote，故豁免不入索引，以保证现货 (base,quote) 唯一不变式。
#[derive(Debug, Clone, Default)]
pub struct SymbolSpecificationProvider {
    pub symbols: BTreeMap<i32, CoreSymbolSpecification>,
    pub currencies: BTreeMap<i32, CoreCurrencySpecification>,
    pub spot_pair_index: BTreeSet<(i32, i32)>,
}

impl SymbolSpecificationProvider {

    pub fn new() -> Self {
        Self::default()
    }

    /// 对应 Java `addSymbol`：注册一个新 symbol。symbolId 已存在、或该 symbol
    /// 是现货对且 (base,quote) 已被其他现货 symbol 占用时拒绝
    /// （`SymbolMgmtSymbolAlreadyExists`），维持"现货对 (base,quote) 唯一"的不变式；
    /// 期货/期权类型不受该唯一性约束。成功时同步更新 `spot_pair_index`。
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

    /// 注册/覆盖一个币种的元数据。Java `SymbolSpecificationProvider` 无对应方法
    /// （币种元数据在 Rust 侧收拢进本类维护）。
    pub fn add_currency(&mut self, spec: CoreCurrencySpecification) {
        self.currencies.insert(spec.currency, spec);
    }

    /// 对应 Java 构造器 `SymbolSpecificationProvider(BytesIn)` 里的
    /// `rebuildSpotPairIndex`：从 `symbols` 全量重建现货对派生索引，
    /// 用于快照反序列化后恢复索引（索引本身不入快照）。
    pub fn rebuild_spot_pair_index(&mut self) {
        self.spot_pair_index.clear();
        for spec in self.symbols.values() {
            if spec.symbol_type == SymbolType::CurrencyExchangePair {
                self.spot_pair_index.insert((spec.base_currency, spec.quote_currency));
            }
        }
    }

    /// 对应 Java `getSymbolSpecification`。
    pub fn get_symbol(&self, symbol_id: i32) -> Option<&CoreSymbolSpecification> {
        self.symbols.get(&symbol_id)
    }

    /// Java `SymbolSpecificationProvider` 无对应方法（币种查询是 Rust 侧新增职责）。
    pub fn get_currency(&self, currency: i32) -> Option<&CoreCurrencySpecification> {
        self.currencies.get(&currency)
    }

    /// 对应 Java `findSpotSymbol`：按 (base,quote) 反查现货对 symbol。
    /// Java 版本经 `spotPairIndex` 做 O(1) 定位；这里改用集合扫描（O(n)，n 为
    /// symbol 总数），语义等价（因唯一性不变式，最多命中一个），只是没有走
    /// `spot_pair_index` 这个 O(1) 索引本身来完成查找。
    pub fn find_spot_symbol(&self, base_currency: i32, quote_currency: i32) -> Option<&CoreSymbolSpecification> {
        self.symbols.values().find(|s| {
            s.symbol_type == SymbolType::CurrencyExchangePair
                && s.base_currency == base_currency
                && s.quote_currency == quote_currency
        })
    }
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::{to_btree_i32, ChronicleMarshallable};

// 对应 Java `writeMarshallable`（只写 `symbolSpecs`）+ 读构造器（读 `symbolSpecs` 后
// `rebuildSpotPairIndex`）。Rust 版本额外多写一段 `currencies`（Java
// `SymbolSpecificationProvider.writeMarshallable` 没有这部分，币种元数据在 Java 侧不属于
// 本类职责），是 Rust 收拢币种元数据到本 provider 后随之扩展的快照格式。
impl ChronicleMarshallable for SymbolSpecificationProvider {
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_int_keyed_map(&self.symbols, |vw, v| v.chronicle_write(vw));
        w.write_int_keyed_map(&self.currencies, |vw, v| v.chronicle_write(vw));
    }
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let symbols = to_btree_i32(r.read_int_keyed_map(CoreSymbolSpecification::chronicle_read)?);
        let currencies = to_btree_i32(r.read_int_keyed_map(CoreCurrencySpecification::chronicle_read)?);
        let mut ssp = SymbolSpecificationProvider { symbols, currencies, spot_pair_index: BTreeSet::new() };
        ssp.rebuild_spot_pair_index();
        Ok(ssp)
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
        let result = provider.add_symbol(spot_spec(1, 3, 4));
        assert_eq!(result, CommandResultCode::SymbolMgmtSymbolAlreadyExists);
        assert_eq!(provider.get_symbol(1).unwrap().base_currency, 1);
    }

    #[test]
    fn add_symbol_rejects_duplicate_spot_pair() {
        let mut provider = SymbolSpecificationProvider::new();
        assert_eq!(provider.add_symbol(spot_spec(1, 1, 2)), CommandResultCode::Success);
        let result = provider.add_symbol(spot_spec(2, 1, 2));
        assert_eq!(result, CommandResultCode::SymbolMgmtSymbolAlreadyExists);
        assert!(provider.get_symbol(2).is_none());
    }

    #[test]
    fn add_symbol_allows_futures_to_share_base_quote() {
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
