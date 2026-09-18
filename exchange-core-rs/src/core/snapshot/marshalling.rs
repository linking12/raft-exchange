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

    // ---- route B:真实 server MemorySerializationProcessor 产的不压缩富状态 .dat 单分片加载 ----
    // fixture 由 raft-exchange-server 的 SnapshotDatProduce 产出(真实 persist 落盘链路):
    // 4 币种(带 name)+ 2 现货 symbol + 1 永续期货 symbol + 4 用户 + 余额/锁定 + 挂单 + 期货持仓。

    #[test]
    fn load_real_rich_re_dat() {
        use crate::core::common::position_direction::PositionDirection;
        let re = include_bytes!("../../../tests/snapshot_fixtures/rich_re0.dat");
        let payload = crate::core::snapshot::module_frame::decode_module_payload(re).unwrap();
        let mut core = ExchangeCore::default();
        read_risk_engine_payload(&payload, &mut core).unwrap();

        // 4 币种带 name。
        assert_eq!(core.ssp.currencies.len(), 4);
        assert_eq!(core.ssp.currencies[&1].name, "USD");
        assert_eq!(core.ssp.currencies[&2].name, "USDT");
        assert_eq!(core.ssp.currencies[&3].name, "BTC");
        assert_eq!(core.ssp.currencies[&4].name, "ETH");
        // BTC(3) 初值 8000,后被 LOAN_SYMBOL 的 collateralWeight=10000 覆盖(loan 抵押权重落到 base 币);ETH(4) 未被 loan 触及。
        assert_eq!(core.ssp.currencies[&3].collateral_weight_bps, 10000);
        assert_eq!(core.ssp.currencies[&4].collateral_weight_bps, 6000);

        // 2 现货 + 1 永续期货 symbol。
        assert_eq!(core.ssp.symbols.len(), 3);
        assert!(core.ssp.symbols.contains_key(&100));
        assert!(core.ssp.symbols.contains_key(&101));
        assert!(core.ssp.symbols.contains_key(&200));

        // 6 用户(10-13 现货/期货 + 20/22 loan)。
        assert_eq!(core.ups.users.len(), 6);
        for uid in [10, 11, 12, 13, 20, 22] {
            assert!(core.ups.users.contains_key(&uid), "missing user {uid}");
        }

        // 现货记账:u11 卖 10 BTC@50000 → USDT 1_000_000 + 500_000 = 1_500_000;u10 买 10 BTC → BTC=10。
        assert_eq!(core.ups.users[&11].accounts.get(&2), Some(&1_500_000));
        assert_eq!(core.ups.users[&10].accounts.get(&3), Some(&10));

        // 期货持仓:u10 LONG 3 / u13 SHORT 3 @ sym200。
        let p10 = &core.ups.users[&10].positions[&200];
        assert_eq!(p10.direction, PositionDirection::Long);
        assert_eq!(p10.open_volume, 3);
        let p13 = &core.ups.users[&13].positions[&200];
        assert_eq!(p13.direction, PositionDirection::Short);
        assert_eq!(p13.open_volume, 3);

        // ===== loan 子系统 =====
        // 全局配置:numeraire=USDT(2),cross LTV 阈值。
        let gc = &core.risk.loan_service.global_config;
        assert_eq!(gc.numeraire_currency, 2);
        assert_eq!(gc.cross_liquidation_ltv_bps, 8500);
        assert_eq!(gc.cross_margin_call_ltv_bps, 7500);
        // 借贷池:USDT 注资 1_000_000,借出 2(isolated 1 + cross 1)→ available 999_998 / borrowed 2。
        assert_eq!(core.risk.loan_service.loan_pool_available.get(&2), Some(&999_998));
        assert_eq!(core.risk.loan_service.loan_pool_borrowed.get(&2), Some(&2));
        // isolated 借款:u20 loanId=9001,抵押 1000 BTC(cur=3),本金 1 USDT,symbol=100。
        let il = &core.ups.users[&20].isolated_loans[&9001];
        assert_eq!(il.symbol_id, 100);
        assert_eq!(il.collateral_currency, 3);
        assert_eq!(il.collateral_amount, 1000);
        assert_eq!(il.outstanding_principal, 1);
        // cross 借款:u22 抵押 300 BTC + loanId=9002 本金 1 USDT,symbol=100。
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

        // 3 订单簿。
        assert_eq!(core.matching.books.len(), 3);
        assert!(core.matching.books.contains_key(&100));
        assert!(core.matching.books.contains_key(&101));
        assert!(core.matching.books.contains_key(&200));

        // sym100:u11 残留卖单 5@60000(10@50000 已全成);无买单。
        let (asks100, bids100) = core.matching.books[&100].chronicle_orders();
        assert_eq!(asks100.len(), 1);
        assert_eq!(asks100[0].price, 60000);
        assert_eq!(asks100[0].size - asks100[0].filled, 5);
        assert!(bids100.is_empty());

        // sym101:u12 挂卖 20@3000(无对手,全留簿)。
        let (asks101, bids101) = core.matching.books[&101].chronicle_orders();
        assert_eq!(asks101.len(), 1);
        assert_eq!(asks101[0].price, 3000);
        assert_eq!(asks101[0].size - asks101[0].filled, 20);
        assert!(bids101.is_empty());

        // sym200 期货:3@1000 全成,簿空。
        let (asks200, bids200) = core.matching.books[&200].chronicle_orders();
        assert!(asks200.is_empty());
        assert!(bids200.is_empty());
    }

    #[test]
    fn rich_dat_re_me_write_read_roundtrip() {
        // RE:真实 .dat → read → write 应与原 payload 逐字节相同。
        let re = include_bytes!("../../../tests/snapshot_fixtures/rich_re0.dat");
        let re_payload = crate::core::snapshot::module_frame::decode_module_payload(re).unwrap();
        let mut core = ExchangeCore::default();
        read_risk_engine_payload(&re_payload, &mut core).unwrap();
        let re_rewritten = write_risk_engine_payload(&core);
        assert_eq!(re_rewritten, re_payload, "rich RE .dat payload write→read→write bytes diverge from Java");

        // ME:同理。
        let me = include_bytes!("../../../tests/snapshot_fixtures/rich_me0.dat");
        let me_payload = crate::core::snapshot::module_frame::decode_module_payload(me).unwrap();
        core.matching = MatchingEngineRouter::chronicle_read(&mut ChronicleReader::new(&me_payload)).unwrap();
        let mut mw = ChronicleWriter::new();
        core.matching.chronicle_write(&mut mw);
        assert_eq!(mw.into_bytes(), me_payload, "rich ME .dat payload write→read→write bytes diverge from Java");
    }

    // ---- 纯 Rust:程序化造现货+期货+loan 富状态 → persist(File 后端落 .dat) → 另一实例 recover → 对称 ----
    // 不依赖任何 Java fixture,走真实磁盘 .dat 落盘/加载(FileSerializationProcessor),验证 Rust 自身
    // 写→读→写字节对称 + 恢复后状态与原实例一致。
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
        core.risk.cfg_margin_trading_enabled = true; // 期货需要

        // ===== 4 币种(带 name + collateral weight) =====
        for (id, name, cw) in [(1, "USD", 0), (2, "USDT", 0), (3, "BTC", 8000), (4, "ETH", 6000)] {
            core.ssp.add_currency(CoreCurrencySpecification {
                currency: id,
                name: name.to_string(),
                currency_scale_k: 1,
                collateral_weight_bps: cw,
                ..Default::default()
            });
        }

        // ===== 2 现货 symbol + 1 永续期货 symbol(建 SSP + router book) =====
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
        // 期货 + loan 抵押估值都需 mark price 就绪。
        run_ok(&mut core, OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: 200, price: 1000, ..Default::default() });
        run_ok(&mut core, OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: 100, price: 50000, ..Default::default() });

        // ===== 6 用户 + 余额 =====
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

        // ===== 现货成交 + 残留挂单 =====
        let spot = |core: &mut ExchangeCore, oid: i64, uid: i64, sym: i32, px: i64, sz: i64, act: OrderAction| {
            run_ok(core, OrderCommand {
                command: OrderCommandType::PlaceOrder, order_id: oid, uid, symbol: sym, price: px, size: sz,
                reserve_bid_price: px, action: Some(act), order_type: Some(OrderType::Gtc), ..Default::default()
            });
        };
        spot(&mut core, 1001, 11, 100, 50000, 10, OrderAction::Ask);
        spot(&mut core, 1002, 10, 100, 50000, 10, OrderAction::Bid);
        spot(&mut core, 1003, 11, 100, 60000, 5, OrderAction::Ask); // 残留卖单
        spot(&mut core, 1004, 12, 101, 3000, 20, OrderAction::Ask); // 残留卖单

        // ===== 期货持仓:u10 LONG / u13 SHORT 各 3@1000 ISOLATED lev1 =====
        let fut = |core: &mut ExchangeCore, oid: i64, uid: i64, px: i64, sz: i64, act: OrderAction| {
            run_ok(core, OrderCommand {
                command: OrderCommandType::PlaceOrder, order_id: oid, uid, symbol: 200, price: px, size: sz,
                action: Some(act), order_type: Some(OrderType::Gtc), leverage: 1, margin_mode: MarginMode::Isolated, ..Default::default()
            });
        };
        fut(&mut core, 2001, 13, 1000, 3, OrderAction::Ask);
        fut(&mut core, 2002, 10, 1000, 3, OrderAction::Bid);

        // ===== loan:全局配置 + per-symbol + 池注资 + isolated 借款 + cross 借款(留 outstanding) =====
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
                collateral_weight_bps: 10000, // 覆盖 BTC 初值 8000
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

        // ===== persist(落 .dat)→ 另一实例 recover → 写→读→写字节对称 =====
        assert!(core.persist(1, 0), "persist to disk .dat must succeed");
        // 文件名与 Java SnapshotHelper.genSnapshotFileName 一致。
        assert!(dir.join("snapshot_1_RE_0.dat").exists());
        assert!(dir.join("snapshot_1_ME_0.dat").exists());
        let mut recovered = ExchangeCore::new(); recovered.with_serialization_processor(Box::new(FileSerializationProcessor::new(&dir)));
        recovered.recover(1, 0);
        // 对称:recovered 重新 persist 到快照 2,比对两次落盘的 .dat 文件逐字节相等。
        assert!(recovered.persist(2, 0));
        let read = |id: i64, code: &str| std::fs::read(dir.join(format!("snapshot_{id}_{code}_0.dat"))).unwrap();
        assert_eq!(read(2, "RE"), read(1, "RE"), "pure-Rust RE .dat build→persist→recover→persist bytes must be identical");
        assert_eq!(read(2, "ME"), read(1, "ME"), "pure-Rust ME .dat build→persist→recover→persist bytes must be identical");

        // ===== 恢复后状态与原实例一致(抽查三系统关键字段) =====
        assert_eq!(recovered.ssp.currencies.len(), 4);
        assert_eq!(recovered.ssp.currencies[&3].name, "BTC");
        assert_eq!(recovered.ssp.currencies[&3].collateral_weight_bps, 10000); // loan 覆盖
        assert_eq!(recovered.ssp.symbols.len(), 3);
        assert_eq!(recovered.ups.users.len(), 6);
        // 现货
        assert_eq!(recovered.ups.users[&11].accounts.get(&2), Some(&1_500_000));
        assert_eq!(recovered.ups.users[&10].accounts.get(&3), Some(&10));
        assert_eq!(recovered.matching.books.len(), 3);
        assert_eq!(recovered.matching.books[&100].chronicle_orders().0.len(), 1);
        // 期货
        assert_eq!(recovered.ups.users[&10].positions[&200].direction, PositionDirection::Long);
        assert_eq!(recovered.ups.users[&10].positions[&200].open_volume, 3);
        assert_eq!(recovered.ups.users[&13].positions[&200].direction, PositionDirection::Short);
        // loan
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
