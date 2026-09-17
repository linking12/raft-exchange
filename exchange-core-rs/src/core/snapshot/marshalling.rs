//! 快照可序列化类型的统一接口，以及 Chronicle 风格 map 解码结果到 Rust `BTreeMap` 的转换工具。
//!
//! Java 侧同一个类往往把"怎么写"和"怎么读"拆成两处：实现 `WriteBytesMarshallable.writeMarshallable(BytesOut)`
//! 处理写，另有一个接受 `BytesIn` 的构造器处理读（例如 `CoreSymbolSpecification(BytesIn bytes)`、
//! `SymbolLoanSpecification(BytesIn bytes)`）。[`ChronicleMarshallable`] 把这两半合并成一个 trait，
//! 让每个快照结构体（`CoreCurrencySpecification`、`CoreSymbolSpecification`、`UserProfile`、
//! `LoanService`、`LiquidationService` 等，均在 `crate::core::common`/`crate::core::processors` 下）
//! 只需实现一处、对应关系一目了然，便于和 Java 源码逐字段对拍。
//!
//! [`ChronicleReader`] 的 `read_int_keyed_map`/`read_long_keyed_map`/`read_int_long_map`/
//! `read_long_long_treemap` 都返回保留原始写出顺序的 `Vec<(K, V)>`（因为 Java 端对应的
//! `IntObjectHashMap`/`LongObjectHashMap`/`IntLongHashMap` 不是有序容器，读出顺序本就不代表语义）。
//! `to_btree_i32`/`to_btree_i64` 把这些 pairs 折叠进 `BTreeMap`，这样 Rust 侧才有一个确定的按 key
//! 排序的规范形式，便于状态比较、`state_hash` 计算以及测试里的 write→read→write 字节级回归。
use std::collections::BTreeMap;

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;

/// 对应 Java 的 "`WriteBytesMarshallable.writeMarshallable(BytesOut)` + 接受 `BytesIn` 的构造器"
/// 这对读写方法的组合，统一成一个 trait。实现者需保证 `chronicle_write`/`chronicle_read`
/// 与 Java 端对应类型的字段顺序、类型宽度逐一对齐——字段顺序变化即是快照格式变化。
pub trait ChronicleMarshallable: Sized {
    fn chronicle_write(&self, w: &mut ChronicleWriter);
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError>;
}

/// 把 [`ChronicleReader::read_int_keyed_map`]/`read_int_long_map` 读出的 `(i32, V)` pairs
/// 折叠为按 key 排序的 `BTreeMap`（对应 Java `IntObjectHashMap`/`IntLongHashMap` 的内容集合，
/// 但赋予其一个确定的排序，Rust 侧不复刻 Java 的哈希表内部顺序）。
pub fn to_btree_i32<V>(pairs: Vec<(i32, V)>) -> BTreeMap<i32, V> {
    pairs.into_iter().collect()
}
/// 同 [`to_btree_i32`]，键类型为 `i64`（对应 Java `LongObjectHashMap`/`LongIntHashMap` 等）。
pub fn to_btree_i64<V>(pairs: Vec<(i64, V)>) -> BTreeMap<i64, V> {
    pairs.into_iter().collect()
}

// 以下测试用真实的 Java 端写出的快照 fixture（`tests/snapshot_fixtures/re0.ecs`/`me0.ecs`，
// RE=RiskEngine 模块、ME=MatchingEngine 模块）驱动 write→read→write 字节级回归，
// 确保 Rust 解码/编码出的字节与 Java 原始快照逐字节相同——这是混合 Java/Rust 集群能互读快照的前提。
#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::symbol_loan_specification::SymbolLoanSpecification;
    use crate::core::common::symbol_position_record::SymbolPositionRecord;
    use crate::core::common::user_profile::UserProfile;
    use crate::core::exchange_core::ExchangeCore;
    use crate::core::processors::liquidation::liquidation_service::{IfNotional, IfPositionRecord, LiquidationService};
    use crate::core::processors::loan::loan_global_config::LoanGlobalConfig;
    use crate::core::processors::loan::loan_service::LoanService;
    use crate::core::processors::loan::rate::fixed_rate_model::FixedRateModel;
    use crate::core::processors::loan::rate::floating_rate_model::FloatingRateModel;
    use crate::core::processors::matching_engine_router::MatchingEngineRouter;
    use crate::core::processors::risk_engine::{read_risk_engine_payload, write_risk_engine_payload};

    fn hx(s: &str) -> Vec<u8> {
        (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
    }

    #[test]
    fn load_real_re0_into_exchange_core() {
        let re0 = include_bytes!("../../../tests/snapshot_fixtures/re0.ecs");
        let payload = crate::core::snapshot::module_frame::decode_module_payload(re0).unwrap();
        let mut core = ExchangeCore::default();
        read_risk_engine_payload(&payload, &mut core).unwrap();
        assert_eq!(core.ssp.currencies.len(), 2);
        assert_eq!(core.ssp.currencies[&1].name, "BTC");
        assert_eq!(core.ssp.currencies[&2].name, "USDT");
        assert_eq!(core.ssp.currencies[&1].collateral_weight_bps, 8000);
        assert_eq!(core.ssp.symbols.len(), 1);
        assert!(core.ssp.symbols.contains_key(&100));
        assert_eq!(core.ups.users.len(), 1);
        assert_eq!(core.ups.users[&42].accounts.get(&2), Some(&1_000_000));
    }

    #[test]
    fn load_real_me0_into_exchange_core() {
        let me0 = include_bytes!("../../../tests/snapshot_fixtures/me0.ecs");
        let payload = crate::core::snapshot::module_frame::decode_module_payload(me0).unwrap();
        let mut core = ExchangeCore::default();
        core.matching = MatchingEngineRouter::chronicle_read(&mut ChronicleReader::new(&payload)).unwrap();
        assert_eq!(core.matching.books.len(), 1);
        assert!(core.matching.books.contains_key(&100));
    }

    #[test]
    fn me_payload_write_read_roundtrip_from_real_me0() {
        let me0 = include_bytes!("../../../tests/snapshot_fixtures/me0.ecs");
        let payload = crate::core::snapshot::module_frame::decode_module_payload(me0).unwrap();
        let mut core = ExchangeCore::default();
        core.matching = MatchingEngineRouter::chronicle_read(&mut ChronicleReader::new(&payload)).unwrap();
        let mut mw = ChronicleWriter::new();
        core.matching.chronicle_write(&mut mw);
        let rewritten = mw.into_bytes();
        assert_eq!(rewritten, payload, "ME payload write→read→write bytes do not match Java's original bytes");
    }

    #[test]
    fn re_payload_write_read_roundtrip_from_real_re0() {
        let re0 = include_bytes!("../../../tests/snapshot_fixtures/re0.ecs");
        let payload = crate::core::snapshot::module_frame::decode_module_payload(re0).unwrap();
        let mut core = ExchangeCore::default();
        read_risk_engine_payload(&payload, &mut core).unwrap();
        let rewritten = write_risk_engine_payload(&core);
        assert_eq!(rewritten, payload, "RE payload write→read→write bytes do not match Java's original bytes");
    }

    #[test]
    fn currency_read_matches_java_fixture() {
        let bytes = hx("07000000034254430800000028230000");
        let spec = CoreCurrencySpecification::chronicle_read(&mut ChronicleReader::new(&bytes)).unwrap();
        assert_eq!(spec.currency, 7);
        assert_eq!(spec.name, "BTC");
        assert_eq!(spec.currency_scale_k, 100_000_000);
        assert_eq!(spec.collateral_weight_bps, 9000);
    }

    #[test]
    fn currency_write_matches_java_fixture() {
        let spec = CoreCurrencySpecification {
            currency: 7,
            name: "BTC".to_string(),
            currency_scale_k: 100_000_000,
            collateral_weight_bps: 9000,
        };
        let mut w = ChronicleWriter::new();
        spec.chronicle_write(&mut w);
        let hex: String = w.as_bytes().iter().map(|b| format!("{b:02x}")).collect();
        assert_eq!(hex, "07000000034254430800000028230000");
    }

    fn rt<T: ChronicleMarshallable>(v: &T) {
        let mut w1 = ChronicleWriter::new();
        v.chronicle_write(&mut w1);
        let bytes1 = w1.into_bytes();
        let mut r = ChronicleReader::new(&bytes1);
        let back = T::chronicle_read(&mut r).expect("read");
        assert!(r.is_empty(), "read did not consume all bytes");
        let mut w2 = ChronicleWriter::new();
        back.chronicle_write(&mut w2);
        assert_eq!(w2.into_bytes(), bytes1, "write→read→write bytes do not match");
    }

    #[test]
    fn roundtrip_symbol_spec_with_maps_and_loan() {
        use crate::core::common::symbol_type::SymbolType;
        let spec = CoreSymbolSpecification {
            symbol_id: 100,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 10,
            quote_scale_k: 100,
            taker_fee: 20,
            maker_fee: 10,
            fee_scale_k: 1_000_000,
            liquidation_fee: 5,
            init_margin: 1,
            init_margin_scale_k: 100,
            maintenance_margin: BTreeMap::from([(1000i64, 5i64), (100000, 10)]),
            maintenance_margin_scale_k: 1000,
            max_leverage: BTreeMap::from([(2000i64, 5i64), (100000, 10)]),
            loan_config: SymbolLoanSpecification {
                initial_ltv_bps: 6000,
                liquidation_ltv_bps: 8500,
                margin_call_ltv_bps: 7500,
                max_amount: 0,
                max_term_days: 365,
            },
        };
        rt(&spec);
    }

    #[test]
    fn roundtrip_liquidation_service() {
        use crate::core::common::position_direction::PositionDirection;
        let ls = LiquidationService {
            notionals: BTreeMap::from([(1i32, IfNotional { available: 500, reserved: 100 })]),
            positions: BTreeMap::from([(1i64, IfPositionRecord {
                symbol: 10,
                direction: PositionDirection::Short,
                open_volume: 7,
                open_price_sum: 7000,
            })]),
        };
        rt(&ls);
    }

    #[test]
    fn roundtrip_loan_service() {
        let ls = LoanService {
            loan_pool_available: BTreeMap::from([(2i32, 1_000_000i64)]),
            loan_pool_borrowed: BTreeMap::from([(2, 50000)]),
            interest_revenue: BTreeMap::new(),
            loan_insurance_fund: BTreeMap::from([(2, 999)]),
            global_config: LoanGlobalConfig {
                numeraire_currency: 2,
                cross_liquidation_ltv_bps: 8500,
                cross_margin_call_ltv_bps: 7500,
                loan_pool_utilization_cap_bps: 9000,
                loan_liquidation_fee_bps: 100,
                ltv_liquidation_buffer_bps: 50,
                ltv_margin_call_buffer_bps: 30,
            },
            floating_rate: FloatingRateModel {
                base_bps: 100,
                kink_util_bps: 8000,
                slope1_bps: 200,
                slope2_bps: 3000,
                current_rate_bps: BTreeMap::from([(2i32, 150i64)]),
                acc_rate_bps_ms: BTreeMap::from([(2, 12345)]),
                last_reprice_ts: 999,
            },
            fixed_rate: FixedRateModel { locked_rate_adjust_bps: 25 },
        };
        rt(&ls);
    }

    #[test]
    fn roundtrip_user_profile_with_positions_and_loans() {
        use crate::core::common::margin_mode::MarginMode;
        use crate::core::common::position_direction::PositionDirection;
        use crate::core::common::user_status::UserStatus;
        let mut up = UserProfile::new(42, UserStatus::Active);
        up.accounts.insert(2, 1_000_000);
        up.exchange_locked.insert(2, 500);
        let mut spr = SymbolPositionRecord::default();
        spr.uid = 42;
        spr.symbol = 100;
        spr.currency = 2;
        spr.direction = PositionDirection::Long;
        spr.open_volume = 10;
        spr.open_price_sum = 10000;
        spr.leverage = 1;
        spr.margin_mode = MarginMode::Isolated;
        up.positions.insert(100, spr);
        up.processed_tx_ids.try_claim(7, 1000);
        rt(&up);
    }

    #[test]
    fn currency_roundtrip_various_scales() {
        for (scale_k, name) in [(1i64, ""), (100, "X"), (1_000_000, "USDT"), (100_000_000, "BTC")] {
            let spec = CoreCurrencySpecification {
                currency: 42,
                name: name.to_string(),
                currency_scale_k: scale_k,
                collateral_weight_bps: 8000,
            };
            let mut w = ChronicleWriter::new();
            spec.chronicle_write(&mut w);
            let bytes = w.into_bytes();
            let back = CoreCurrencySpecification::chronicle_read(&mut ChronicleReader::new(&bytes)).unwrap();
            assert_eq!(back, spec, "round-trip scale_k={scale_k}");
        }
    }
}
