use std::collections::BTreeMap;

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;

pub trait ChronicleMarshallable: Sized {
    fn chronicle_write(&self, w: &mut ChronicleWriter);
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError>;
}

pub fn to_btree_i32<V>(pairs: Vec<(i32, V)>) -> BTreeMap<i32, V> {
    pairs.into_iter().collect()
}

pub fn to_btree_i64<V>(pairs: Vec<(i64, V)>) -> BTreeMap<i64, V> {
    pairs.into_iter().collect()
}

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

    fn run_ok(core: &mut ExchangeCore, mut cmd: crate::core::common::cmd::order_command::OrderCommand) {
        use crate::core::common::cmd::command_result_code::CommandResultCode;
        core.process_command(&mut cmd);
        assert_eq!(cmd.result_code, Some(CommandResultCode::Success), "command failed: {:?}", cmd.command);
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
    fn load_real_rich_re_dat() {
        use crate::core::common::position_direction::PositionDirection;
        let re = include_bytes!("../../../tests/snapshot_fixtures/rich_re0.dat");
        let payload = crate::core::snapshot::module_frame::decode_module_payload(re).unwrap();
        let mut core = ExchangeCore::default();
        read_risk_engine_payload(&payload, &mut core).unwrap();

        assert_eq!(core.ssp.currencies.len(), 4);
        assert_eq!(core.ssp.currencies[&1].name, "USD");
        assert_eq!(core.ssp.currencies[&2].name, "USDT");
        assert_eq!(core.ssp.currencies[&3].name, "BTC");
        assert_eq!(core.ssp.currencies[&4].name, "ETH");

        assert_eq!(core.ssp.currencies[&3].collateral_weight_bps, 10000);
        assert_eq!(core.ssp.currencies[&4].collateral_weight_bps, 6000);

        assert_eq!(core.ssp.symbols.len(), 3);
        assert!(core.ssp.symbols.contains_key(&100));
        assert!(core.ssp.symbols.contains_key(&101));
        assert!(core.ssp.symbols.contains_key(&200));

        assert_eq!(core.ups.users.len(), 6);
        for uid in [10, 11, 12, 13, 20, 22] {
            assert!(core.ups.users.contains_key(&uid), "missing user {uid}");
        }

        assert_eq!(core.ups.users[&11].accounts.get(&2), Some(&1_500_000));
        assert_eq!(core.ups.users[&10].accounts.get(&3), Some(&10));

        let p10 = &core.ups.users[&10].positions[&200];
        assert_eq!(p10.direction, PositionDirection::Long);
        assert_eq!(p10.open_volume, 3);
        let p13 = &core.ups.users[&13].positions[&200];
        assert_eq!(p13.direction, PositionDirection::Short);
        assert_eq!(p13.open_volume, 3);

        let gc = &core.risk.loan_service.global_config;
        assert_eq!(gc.numeraire_currency, 2);
        assert_eq!(gc.cross_liquidation_ltv_bps, 8500);
        assert_eq!(gc.cross_margin_call_ltv_bps, 7500);

        assert_eq!(core.risk.loan_service.loan_pool_available.get(&2), Some(&999_998));
        assert_eq!(core.risk.loan_service.loan_pool_borrowed.get(&2), Some(&2));

        let il = &core.ups.users[&20].isolated_loans[&9001];
        assert_eq!(il.symbol_id, 100);
        assert_eq!(il.collateral_currency, 3);
        assert_eq!(il.collateral_amount, 1000);
        assert_eq!(il.outstanding_principal, 1);

        assert_eq!(core.ups.users[&22].cross_loan_collateral.get(&3), Some(&300));
        let cl = &core.ups.users[&22].cross_loans[&9002];
        assert_eq!(cl.symbol_id, 100);
        assert_eq!(cl.outstanding_principal, 1);
    }

    #[test]
    fn load_real_rich_me_dat() {
        let me = include_bytes!("../../../tests/snapshot_fixtures/rich_me0.dat");
        let payload = crate::core::snapshot::module_frame::decode_module_payload(me).unwrap();
        let mut core = ExchangeCore::default();
        core.matching = MatchingEngineRouter::chronicle_read(&mut ChronicleReader::new(&payload)).unwrap();

        assert_eq!(core.matching.books.len(), 3);
        assert!(core.matching.books.contains_key(&100));
        assert!(core.matching.books.contains_key(&101));
        assert!(core.matching.books.contains_key(&200));

        let (asks100, bids100) = core.matching.books[&100].chronicle_orders();
        assert_eq!(asks100.len(), 1);
        assert_eq!(asks100[0].price, 60000);
        assert_eq!(asks100[0].size - asks100[0].filled, 5);
        assert!(bids100.is_empty());

        let (asks101, bids101) = core.matching.books[&101].chronicle_orders();
        assert_eq!(asks101.len(), 1);
        assert_eq!(asks101[0].price, 3000);
        assert_eq!(asks101[0].size - asks101[0].filled, 20);
        assert!(bids101.is_empty());

        let (asks200, bids200) = core.matching.books[&200].chronicle_orders();
        assert!(asks200.is_empty());
        assert!(bids200.is_empty());
    }

    #[test]
    fn rich_dat_re_me_write_read_roundtrip() {

        let re = include_bytes!("../../../tests/snapshot_fixtures/rich_re0.dat");
        let re_payload = crate::core::snapshot::module_frame::decode_module_payload(re).unwrap();
        let mut core = ExchangeCore::default();
        read_risk_engine_payload(&re_payload, &mut core).unwrap();
        let re_rewritten = write_risk_engine_payload(&core);
        assert_eq!(re_rewritten, re_payload, "rich RE .dat payload write→read→write bytes diverge from Java");

        let me = include_bytes!("../../../tests/snapshot_fixtures/rich_me0.dat");
        let me_payload = crate::core::snapshot::module_frame::decode_module_payload(me).unwrap();
        core.matching = MatchingEngineRouter::chronicle_read(&mut ChronicleReader::new(&me_payload)).unwrap();
        let mut mw = ChronicleWriter::new();
        core.matching.chronicle_write(&mut mw);
        assert_eq!(mw.into_bytes(), me_payload, "rich ME .dat payload write→read→write bytes diverge from Java");
    }

    #[test]
    fn pure_rust_build_persist_recover_symmetry_spot_futures_loan() {
        use crate::core::common::batch_add_loan_command::{BatchAddLoanCommand, GlobalLoanConfig, SymbolLoanConfig};
        use crate::core::common::cmd::command_result_code::CommandResultCode;
        use crate::core::common::cmd::order_command::OrderCommand;
        use crate::core::common::cmd::order_command_type::OrderCommandType;
        use crate::core::common::margin_mode::MarginMode;
        use crate::core::common::order_action::OrderAction;
        use crate::core::common::order_type::OrderType;
        use crate::core::common::position_direction::PositionDirection;
        use crate::core::common::symbol_type::SymbolType;
        use crate::core::snapshot::serialization_processor::FileSerializationProcessor;

        let dir = std::env::temp_dir().join(format!(
            "ecrs_snap_sym_{}_{}",
            std::process::id(),
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()
        ));
        let mut core = ExchangeCore::new(); core.with_serialization_processor(Box::new(FileSerializationProcessor::new(&dir)));
        core.risk.cfg_margin_trading_enabled = true;

        for (id, name, cw) in [(1, "USD", 0), (2, "USDT", 0), (3, "BTC", 8000), (4, "ETH", 6000)] {
            core.ssp.add_currency(CoreCurrencySpecification {
                currency: id,
                name: name.to_string(),
                currency_scale_k: 1,
                collateral_weight_bps: cw,
                ..Default::default()
            });
        }

        let add_sym = |core: &mut ExchangeCore, spec: CoreSymbolSpecification| {
            assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
            core.matching.add_symbol(&spec);
        };
        add_sym(&mut core, CoreSymbolSpecification {
            symbol_id: 100, symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: 3, quote_currency: 2, base_scale_k: 1, quote_scale_k: 1, ..Default::default()
        });
        add_sym(&mut core, CoreSymbolSpecification {
            symbol_id: 101, symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: 4, quote_currency: 2, base_scale_k: 1, quote_scale_k: 1, ..Default::default()
        });
        add_sym(&mut core, CoreSymbolSpecification {
            symbol_id: 200, symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: 3, quote_currency: 1, base_scale_k: 1, quote_scale_k: 1,
            init_margin: 1, init_margin_scale_k: 100,
            maintenance_margin: [(1000i64, 5i64), (100000, 10)].into_iter().collect(),
            maintenance_margin_scale_k: 1000,
            max_leverage: [(2000i64, 5i64), (100000, 10)].into_iter().collect(),
            ..Default::default()
        });

        run_ok(&mut core, OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: 200, price: 1000, ..Default::default() });
        run_ok(&mut core, OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: 100, price: 50000, ..Default::default() });

        for uid in [10i64, 11, 12, 13, 20, 22] {
            run_ok(&mut core, OrderCommand { command: OrderCommandType::AddUser, uid, ..Default::default() });
        }
        let bal = |core: &mut ExchangeCore, uid: i64, cur: i32, amt: i64, txid: i64| {
            run_ok(core, OrderCommand { command: OrderCommandType::BalanceAdjustment, uid, symbol: cur, price: amt, order_id: txid, ..Default::default() });
        };
        bal(&mut core, 10, 2, 1_000_000, 101);
        bal(&mut core, 10, 1, 100_000, 102);
        bal(&mut core, 11, 2, 1_000_000, 111);
        bal(&mut core, 11, 3, 100, 112);
        bal(&mut core, 12, 4, 100, 121);
        bal(&mut core, 13, 1, 100_000, 131);
        bal(&mut core, 20, 3, 1000, 201);
        bal(&mut core, 22, 3, 300, 221);

        let spot = |core: &mut ExchangeCore, oid: i64, uid: i64, sym: i32, px: i64, sz: i64, act: OrderAction| {
            run_ok(core, OrderCommand {
                command: OrderCommandType::PlaceOrder, order_id: oid, uid, symbol: sym, price: px, size: sz,
                reserve_bid_price: px, action: Some(act), order_type: Some(OrderType::Gtc), ..Default::default()
            });
        };
        spot(&mut core, 1001, 11, 100, 50000, 10, OrderAction::Ask);
        spot(&mut core, 1002, 10, 100, 50000, 10, OrderAction::Bid);
        spot(&mut core, 1003, 11, 100, 60000, 5, OrderAction::Ask);
        spot(&mut core, 1004, 12, 101, 3000, 20, OrderAction::Ask);

        let fut = |core: &mut ExchangeCore, oid: i64, uid: i64, px: i64, sz: i64, act: OrderAction| {
            run_ok(core, OrderCommand {
                command: OrderCommandType::PlaceOrder, order_id: oid, uid, symbol: 200, price: px, size: sz,
                action: Some(act), order_type: Some(OrderType::Gtc), leverage: 1, margin_mode: MarginMode::Isolated, ..Default::default()
            });
        };
        fut(&mut core, 2001, 13, 1000, 3, OrderAction::Ask);
        fut(&mut core, 2002, 10, 1000, 3, OrderAction::Bid);

        core.risk.apply_add_loan(&BatchAddLoanCommand {
            global: Some(GlobalLoanConfig {
                numeraire_currency: 2, cross_liquidation_ltv_bps: 8500, cross_margin_call_ltv_bps: 7500,
                loan_pool_utilization_cap_bps: 0, loan_liquidation_fee_bps: 0,
                ltv_liquidation_buffer_bps: 0, ltv_margin_call_buffer_bps: 0,
            }),
            symbol: None, rate_curve: None,
        }, &mut core.ssp);
        core.risk.apply_add_loan(&BatchAddLoanCommand {
            global: None,
            symbol: Some(SymbolLoanConfig {
                symbol_id: 100, loan_initial_ltv_bps: 6000, loan_liquidation_ltv_bps: 8000,
                loan_margin_call_ltv_bps: 7500, loan_max_amount: 0, loan_max_term_days: 365,
                collateral_weight_bps: 10000,
            }),
            rate_curve: None,
        }, &mut core.ssp);
        run_ok(&mut core, OrderCommand { command: OrderCommandType::PoolDeposit, symbol: 2, size: 1_000_000, order_id: 901, ..Default::default() });
        run_ok(&mut core, OrderCommand {
            command: OrderCommandType::LoanCreate, uid: 20, symbol: 100, reserve_bid_price: 9001,
            size: 1000, price: 1, user_cookie: 0, order_id: 902, timestamp: 0, ..Default::default()
        });
        run_ok(&mut core, OrderCommand { command: OrderCommandType::LoanCrossAddCollateral, uid: 22, symbol: 3, size: 300, order_id: 903, ..Default::default() });
        run_ok(&mut core, OrderCommand {
            command: OrderCommandType::LoanCrossBorrow, uid: 22, symbol: 100, price: 1, reserve_bid_price: 9002, order_id: 904, ..Default::default()
        });

        assert!(core.persist(1, 0), "persist to disk .dat must succeed");

        assert!(dir.join("snapshot_1_RE_0.dat").exists());
        assert!(dir.join("snapshot_1_ME_0.dat").exists());
        let mut recovered = ExchangeCore::new(); recovered.with_serialization_processor(Box::new(FileSerializationProcessor::new(&dir)));
        recovered.recover(1, 0);

        assert!(recovered.persist(2, 0));
        let read = |id: i64, code: &str| std::fs::read(dir.join(format!("snapshot_{id}_{code}_0.dat"))).unwrap();
        assert_eq!(read(2, "RE"), read(1, "RE"), "pure-Rust RE .dat build→persist→recover→persist bytes must be identical");
        assert_eq!(read(2, "ME"), read(1, "ME"), "pure-Rust ME .dat build→persist→recover→persist bytes must be identical");

        assert_eq!(recovered.ssp.currencies.len(), 4);
        assert_eq!(recovered.ssp.currencies[&3].name, "BTC");
        assert_eq!(recovered.ssp.currencies[&3].collateral_weight_bps, 10000);
        assert_eq!(recovered.ssp.symbols.len(), 3);
        assert_eq!(recovered.ups.users.len(), 6);

        assert_eq!(recovered.ups.users[&11].accounts.get(&2), Some(&1_500_000));
        assert_eq!(recovered.ups.users[&10].accounts.get(&3), Some(&10));
        assert_eq!(recovered.matching.books.len(), 3);
        assert_eq!(recovered.matching.books[&100].chronicle_orders().0.len(), 1);

        assert_eq!(recovered.ups.users[&10].positions[&200].direction, PositionDirection::Long);
        assert_eq!(recovered.ups.users[&10].positions[&200].open_volume, 3);
        assert_eq!(recovered.ups.users[&13].positions[&200].direction, PositionDirection::Short);

        assert_eq!(recovered.risk.loan_service.global_config.numeraire_currency, 2);
        assert_eq!(recovered.risk.loan_service.loan_pool_borrowed.get(&2), Some(&2));
        assert_eq!(recovered.ups.users[&20].isolated_loans[&9001].collateral_amount, 1000);
        assert_eq!(recovered.ups.users[&22].cross_loan_collateral.get(&3), Some(&300));
        assert_eq!(recovered.ups.users[&22].cross_loans[&9002].outstanding_principal, 1);

        std::fs::remove_dir_all(&dir).ok();
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
