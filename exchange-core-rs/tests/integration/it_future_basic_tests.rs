//! 翻译自 Java `exchange.core2.tests.integration.ITFutureBasic`（期货集成测试）。
//! 直连引擎（无 raft/Disruptor），逐条对拍 Java 黄金值：结果码 + 仓位态（direction/open_volume）
//! + accounts 结算（fee/PnL）+ 全局守恒。期货 symbol 规格精确复刻 Java `ExchangeTestContainer.initFutureSymbol`：
//!   base/quoteScaleK=1、makerFee=10、takerFee=20、fee 固定费（fee_scale_k=0）、
//!   maintenanceMargin={1000:5,100000:10}@scaleK=1000、maxLeverage={2000:5,100000:10}、initMargin=1@scaleK=100。
//!
//! 不可复刻而**降级/跳过**的断言（harness 缺相应基础设施，见每个 @Test 旁注）：
//!   - Java 用 mockito 跨命令累计 `fundEventReport`/`futuresExecutionReport` 计数 —— harness 只暴露
//!     `last_fund_events()`（最近一条命令），且 `BALANCE_ADJUSTMENT` 在 Rust 引擎**不产 fund event**
//!     （见 `RiskEngine::balance_adjustment`），故 testBalanceIncrease/Decrease/CancelSuccess 只保留
//!     账户余额/仓位态断言，事件计数与字段断言无法复刻。
//!   - testForceClosePosition 依赖强平引擎 + report 层 unrealizedProfit/marginRatioScaleK 字段，且
//!     `set_mark_price` 会触发 targeted 强平扫描 —— 见文件末 SKIP 说明。
//!   - testAdjustment / testMultiBuy / testMultiSell 见 `it_future_base_tests.rs`（属基类参数化用例）。

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::position_direction::PositionDirection;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{
        CancelOrderRequest, ExchangeApi, PlaceFuturesOrderRequest,
    };

    // Java `TestConstants`：BASE_CURRENCY_ID=1、quoteId=840(USD)、symbolId=2、SYMBOL_MARGIN=5991、MAX_VALUE=4_000_000。
    const BASE_CURRENCY_ID: i32 = 1;
    const QUOTE_ID: i32 = 840;
    const SYMBOL_ID: i32 = 2;
    const SYMBOL_MARGIN: i32 = 5991;
    const MAX_VALUE: i64 = 4_000_000;

    const UID_1: i64 = 1;
    const UID_2: i64 = 2;
    const UID_3: i64 = 3;

    const MARK: i64 = 10_000;

    fn mm_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(1_000, 5), (100_000, 10)])
    }
    fn leverage_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(2_000, 5), (100_000, 10)])
    }

    /// 精确复刻 Java `initFutureSymbol(symbolId, quoteId)`。
    fn futures_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL_ID,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_CURRENCY_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            fee_scale_k: 0, // 固定费（Java 未设 feeScaleK -> 默认 0）
            maintenance_margin: mm_table(),
            maintenance_margin_scale_k: 1_000,
            max_leverage: leverage_table(),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    /// 建 currencies + 期货 symbol + 设 mark 价 10000（对应 initFutureSymbol + initMarkPrice）。
    fn setup() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_CURRENCY_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(futures_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_ID, MARK), CommandResultCode::Success);
        api
    }

    /// 对应 `createUserWithSpecificMoney(uid, amount, quoteId)`。
    fn add_user_money(api: &mut ExchangeApi, uid: i64, amount: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        // txid 只要用户内唯一即可（try_claim_tx 按用户去重）；每用户单笔充值用 1。
        assert_eq!(api.balance_adjustment(uid, QUOTE_ID, amount, 1), CommandResultCode::Success);
    }

    #[allow(clippy::too_many_arguments)]
    fn place(
        api: &mut ExchangeApi,
        order_id: i64,
        uid: i64,
        price: i64,
        size: i64,
        action: OrderAction,
        order_type: OrderType,
        leverage: i32,
        reduce_only: bool,
    ) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol: SYMBOL_ID,
            price,
            size,
            action,
            order_type,
            leverage,
            margin_mode: MarginMode::Isolated,
            reduce_only,
        })
    }

    /// 全局守恒（完整公式，含仓位 estimate_pnl + extra_margin）：见 `futures_e2e_tests.rs` 文件头说明。
    fn assert_conserved(api: &ExchangeApi) {
        for &cur in api.ssp().currencies.keys() {
            let mut total: i64 = api.ups().users.values().map(|p| p.account(cur)).sum();
            total += api.adjustments(cur);
            total += api.fees(cur);
            for p in api.ups().users.values() {
                for pos in p.positions.values() {
                    if pos.currency != cur {
                        continue;
                    }
                    let mark = api
                        .risk()
                        .mark_price(pos.symbol)
                        .unwrap_or_else(|| panic!("open position on symbol {} missing mark price", pos.symbol));
                    total += pos.estimate_pnl(mark);
                    total += pos.extra_margin;
                }
            }
            assert_eq!(total, 0, "期货全局守恒被打破：currency={cur} total={total}");
        }
    }

    // ==========================================================================================
    // fund tests —— 降级为账户余额断言（BALANCE_ADJUSTMENT 在 Rust 引擎不产 fund event）。
    // ==========================================================================================

    // testBalanceIncrease：充值后余额增加（Java 断言 DEPOSIT fundEvent 字段，harness 无此事件，仅验余额）。
    #[test]
    fn balance_increase_credits_account() {
        let delta = 100;
        let mut api = ExchangeApi::new();
        api.add_currency(SYMBOL_MARGIN, 1);
        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, SYMBOL_MARGIN, delta, 1), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, SYMBOL_MARGIN), delta);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
    }

    // testBalanceDecrease：充值 100 后提现 40，余额 = 60。
    #[test]
    fn balance_decrease_debits_account() {
        let deposit = 100;
        let withdraw = -40;
        let mut api = ExchangeApi::new();
        api.add_currency(SYMBOL_MARGIN, 1);
        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, SYMBOL_MARGIN, deposit, 1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, SYMBOL_MARGIN, withdraw, 2), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, SYMBOL_MARGIN), deposit + withdraw);
    }

    // testCancelSuccess：挂 BID 后撤单，余额完整退回（期货保证金为虚拟仓位字段，不占 accounts/locked）。
    // Java 断言的 NEW/CANCEL futuresExecutionReport 与 lock/unlock fundEvent 无法复刻（harness 无累计事件）。
    #[test]
    fn cancel_returns_balance_intact() {
        let deposit = 1_000;
        let order_id = 1005;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);

        assert_eq!(place(&mut api, order_id, UID_1, MARK, 1, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        // 期货挂单不占 accounts/locked（纯虚拟仓位字段）。
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0);

        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id, uid: UID_1, symbol: SYMBOL_ID }),
            CommandResultCode::Success
        );
        // 撤单后 pending 释放 -> 仓位记录拆除、余额不变。
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
        assert_conserved(&api);
    }

    // ==========================================================================================
    // 开仓：完全成交 / 部分成交 —— accounts = deposit − fee。
    // ==========================================================================================

    // testOpenPosition4Bid：user1 挂 BID(maker)、user2 吃 ASK(taker)，size1；user1 −10(maker)、user2 −20(taker)。
    #[test]
    fn open_position_maker_bid_taker_ask() {
        let deposit = 1_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, MAX_VALUE);

        assert_eq!(place(&mut api, 1005, UID_1, MARK, 1, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, MARK, 1, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10, "user1 maker fee = 1*10");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - 20, "user2 taker fee = 1*20");
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().direction, PositionDirection::Long);
        assert_eq!(api.user_position(UID_2, SYMBOL_ID).unwrap().direction, PositionDirection::Short);
        assert_conserved(&api);
    }

    // testOpenPosition4Ask：user1 挂 ASK(maker)、user2 吃 BID(taker)，size1；费同上。
    #[test]
    fn open_position_maker_ask_taker_bid() {
        let deposit = 1_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, MAX_VALUE);

        assert_eq!(place(&mut api, 1005, UID_1, MARK, 1, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, MARK, 1, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - 20);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().direction, PositionDirection::Short);
        assert_eq!(api.user_position(UID_2, SYMBOL_ID).unwrap().direction, PositionDirection::Long);
        assert_conserved(&api);
    }

    // testOpenMultiplePosition4Bid：user1 挂 BID(maker) size10、user2 吃 ASK(taker) size2 -> 只成交 2；
    // maker/taker fee 只按已成交 2 计（8 残量挂单未计费）。
    #[test]
    fn open_multiple_partial_maker_bid() {
        let size = 10;
        let tx_size = 2;
        let deposit = 1_000 * size;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, MAX_VALUE);

        assert_eq!(place(&mut api, 1005, UID_1, MARK, size, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, MARK, tx_size, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - tx_size * 10);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - tx_size * 20);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, tx_size);
        assert_conserved(&api);
    }

    // testOpenMultiplePosition4Ask：对称（user1 挂 ASK maker size10、user2 吃 BID taker size2）。
    #[test]
    fn open_multiple_partial_maker_ask() {
        let size = 10;
        let tx_size = 2;
        let deposit = 1_000 * size;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, MAX_VALUE);

        assert_eq!(place(&mut api, 1005, UID_1, MARK, size, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, MARK, tx_size, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - tx_size * 10);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - tx_size * 20);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, tx_size);
        assert_conserved(&api);
    }

    // ==========================================================================================
    // 平仓：完全平 / 部分平。
    // ==========================================================================================

    // testClosePosition：开 LONG@10000 后 @10500 全平；user1 全程 maker、user2 全程 taker。
    // user1 = 1000 −10(开 maker) −10(平 maker) +500(已实现盈利)；user2 = MAX −20 −20 −500。
    #[test]
    fn close_full_position_settles_pnl() {
        let deposit = 1_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, MAX_VALUE);

        // 开仓：user1 BID maker、user2 ASK taker @10000。
        assert_eq!(place(&mut api, 1005, UID_1, 10_000, 1, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, 10_000, 1, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);

        // 平仓：user1 ASK maker、user2 BID taker @10500。
        assert_eq!(place(&mut api, 1007, UID_1, 10_500, 1, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, 10_500, 1, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10 - 10 + 500);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - 20 - 20 - 500);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none(), "全平后仓位拆除");
        assert!(api.user_position(UID_2, SYMBOL_ID).is_none());
        assert_conserved(&api);
    }

    // testPartialClosePosition：开 LONG10@10000，平仓单 ASK size2@10500 只成交 1（taker BID 只 1）。
    // 部分平不实现盈亏；user1 = 10000 −100(开 maker 10*10) −10(平 maker 1*10)；user2 = MAX −200 −20。
    #[test]
    fn partial_close_position_defers_pnl() {
        let deposit = 10_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, MAX_VALUE);

        assert_eq!(place(&mut api, 1005, UID_1, 10_000, 10, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, 10_000, 10, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);

        // user1 ASK size2@10500(maker resting)，user2 BID size1@10500(taker) -> 只平 1。
        assert_eq!(place(&mut api, 1007, UID_1, 10_500, 2, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, 10_500, 1, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10 * 10 - 10 * 1);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - 20 * 10 - 20 * 1);
        // user1 剩 LONG 9（10−1 平），部分平不实现盈亏。
        let pos = api.user_position(UID_1, SYMBOL_ID).expect("部分平后仍持仓");
        assert_eq!(pos.open_volume, 9);
        assert_eq!(pos.direction, PositionDirection::Long);
        assert_eq!(pos.profit, 0, "部分平递延盈亏，不入 profit");
        assert_conserved(&api);
    }

    // ==========================================================================================
    // reduce-only 用例。
    // ==========================================================================================

    // testReduceOnlyWithoutPosition：无仓位时 reduce-only 单被裁到 0 -> SUCCESS no-op，不建仓、不动 accounts。
    #[test]
    fn reduce_only_without_position_is_noop() {
        let deposit = 10_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);

        assert_eq!(place(&mut api, 2001, UID_1, MARK, 1, OrderAction::Ask, OrderType::Gtc, 1, true), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
    }

    // testReduceOnlyPartialCloseLongPosition：LONG10 -> reduce-only ASK 6 平剩 4 -> 再 reduce-only ASK 6 平光（截断到 4）。
    #[test]
    fn reduce_only_partial_then_full_close_long() {
        let deposit = 100_000;
        let position_size = 10;
        let close_size = 6;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, deposit);
        add_user_money(&mut api, UID_3, deposit);

        // user1 开 LONG 10。
        assert_eq!(place(&mut api, 1001, UID_1, 10_000, position_size, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1002, UID_2, 10_000, position_size, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        // UID_3 挂 BID 20@10100 等待平仓单。
        assert_eq!(place(&mut api, 3001, UID_3, 10_100, 2 * position_size, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);

        // reduce-only ASK 6@10100(IOC) -> 平 6，剩 LONG 4。
        assert_eq!(place(&mut api, 2002, UID_1, 10_100, close_size, OrderAction::Ask, OrderType::Ioc, 1, true), CommandResultCode::Success);
        let pos = api.user_position(UID_1, SYMBOL_ID).expect("部分平后仍持仓");
        assert_eq!(pos.open_volume, (position_size - close_size));
        assert_eq!(pos.direction, PositionDirection::Long);

        // 再 reduce-only ASK 6@10100 -> 截断到 4，平光。
        assert_eq!(place(&mut api, 2003, UID_1, 10_100, close_size, OrderAction::Ask, OrderType::Ioc, 1, true), CommandResultCode::Success);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
    }

    // testReduceOnlyFullCloseShortPosition：SHORT10 -> reduce-only BID 6 平剩 4 -> 再 BID 6 平光。
    #[test]
    fn reduce_only_partial_then_full_close_short() {
        let deposit = 100_000;
        let position_size = 10;
        let close_size = 6;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, deposit);
        add_user_money(&mut api, UID_3, deposit);

        // user1 开 SHORT 10。
        assert_eq!(place(&mut api, 1001, UID_1, 10_000, position_size, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1002, UID_2, 10_000, position_size, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        // UID_3 挂 ASK 20@9900 等待平仓单。
        assert_eq!(place(&mut api, 2001, UID_3, 9_900, 2 * position_size, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);

        assert_eq!(place(&mut api, 2002, UID_1, 9_900, close_size, OrderAction::Bid, OrderType::Ioc, 1, true), CommandResultCode::Success);
        let pos = api.user_position(UID_1, SYMBOL_ID).expect("部分平后仍持仓");
        assert_eq!(pos.open_volume, (position_size - close_size));
        assert_eq!(pos.direction, PositionDirection::Short);

        assert_eq!(place(&mut api, 2003, UID_1, 9_900, close_size, OrderAction::Bid, OrderType::Ioc, 1, true), CommandResultCode::Success);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
    }

    // testReduceOnlyExceedPositionSize：reduce-only size 超持仓被截断到持仓量，不超卖；对手方只成交持仓量。
    #[test]
    fn reduce_only_exceeding_size_is_truncated() {
        let deposit = 100_000;
        let position_size = 5;
        let large_close = 10;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, deposit);
        add_user_money(&mut api, UID_3, deposit);

        assert_eq!(place(&mut api, 1001, UID_1, 10_000, position_size, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1002, UID_2, 10_000, position_size, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        // UID_3 挂 BID 10@10100（超持仓）。
        assert_eq!(place(&mut api, 2001, UID_3, 10_100, large_close, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);

        // reduce-only ASK 10@10100 -> 截断到 5，全平。
        assert_eq!(place(&mut api, 2002, UID_1, 10_100, large_close, OrderAction::Ask, OrderType::Ioc, 1, true), CommandResultCode::Success);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none(), "全平不超卖");
        // UID_3 只吃到 position_size(5)，不是 large_close(10)。
        assert_eq!(api.user_position(UID_3, SYMBOL_ID).unwrap().open_volume, position_size);
    }

    // testReduceOnlyWrongDirection：LONG 仓下 reduce-only BID（错方向）被 R1 同向 guard 裁到 0 -> SUCCESS no-op。
    #[test]
    fn reduce_only_wrong_direction_is_noop() {
        let deposit = 100_000;
        let position_size = 5;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, deposit);

        assert_eq!(place(&mut api, 1001, UID_1, 10_000, position_size, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1002, UID_2, 10_000, position_size, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, SYMBOL_ID).unwrap();
            assert_eq!(pos.open_volume, position_size);
            assert_eq!(pos.pending_buy_size, 0);
            assert_eq!(pos.direction, PositionDirection::Long);
        }

        // 错方向 reduce-only BID 3@10100 GTC：同向裁到 0 -> no-op（未入 orderbook）。
        assert_eq!(place(&mut api, 2001, UID_1, 10_100, 3, OrderAction::Bid, OrderType::Gtc, 0, true), CommandResultCode::Success);
        let pos = api.user_position(UID_1, SYMBOL_ID).unwrap();
        assert_eq!(pos.open_volume, position_size);
        assert_eq!(pos.pending_buy_size, 0);
        assert_eq!(pos.direction, PositionDirection::Long);
    }

    // testReduceOnlySameDirectionDoesNotExtendPosition：LONG5 下同向 reduce-only BID 被裁到 0，即便有对手 ASK 也不扩仓。
    #[test]
    fn reduce_only_same_direction_does_not_extend() {
        let deposit = 100_000;
        let position_size = 5;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, 1_000_000);

        assert_eq!(place(&mut api, 10001, UID_1, 10_000, position_size, OrderAction::Bid, OrderType::Gtc, 5, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 10002, UID_2, 10_000, position_size, OrderAction::Ask, OrderType::Gtc, 5, false), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, position_size);

        // 同向 reduce-only BID 3：R1 裁到 0 -> SUCCESS no-op，不进 orderbook。
        assert_eq!(place(&mut api, 10003, UID_1, 10_000, 3, OrderAction::Bid, OrderType::Gtc, 5, true), CommandResultCode::Success);
        // 即使 user2 挂 ASK 3 等着，user1 的 reduce-only 未入簿，不应成交扩仓。
        assert_eq!(place(&mut api, 10004, UID_2, 10_000, 3, OrderAction::Ask, OrderType::Ioc, 5, false), CommandResultCode::Success);

        assert_eq!(
            api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume,
            position_size,
            "ONEWAY 同向 reduce-only 不应扩仓"
        );
    }

    // ==========================================================================================
    // deferred position insertion 用例：失败下单不得在 positions map 留空 record，全局守恒不破。
    // ==========================================================================================

    // T1 testLeverageRejectDoesNotLeavePosition：leverage 超限拒绝不污染 positions，后续合法单仍成功。
    #[test]
    fn leverage_reject_does_not_leave_position() {
        let deposit = 100_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, deposit);

        // leverage=100 超 maxLeverage 上限 -> RISK_INVALID_LEVERAGE。
        assert_eq!(place(&mut api, 5001, UID_1, 10_000, 1, OrderAction::Bid, OrderType::Gtc, 100, false), CommandResultCode::RiskInvalidLeverage);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());

        // 后续合法下单仍成功（验回收的 record 干净）。
        assert_eq!(place(&mut api, 5002, UID_1, 10_000, 1, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 5003, UID_2, 10_000, 1, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, 1);
    }

    // T2 testNSFRejectDoesNotLeavePosition：NSF 拒绝且无已有仓位时不污染 positions。
    #[test]
    fn nsf_reject_does_not_leave_position() {
        let deposit = 100; // 故意做小
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, 1_000_000);

        // notional=10*10000=100000，required=ceil(100000/(100*5))=200 > 100 -> RISK_NSF；leverage=5 在允许范围。
        assert_eq!(place(&mut api, 6001, UID_1, 10_000, 10, OrderAction::Bid, OrderType::Gtc, 5, false), CommandResultCode::RiskNsf);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
    }

    // T3 testNSFRejectKeepsExistingPosition：已有仓位时 NSF 新单绝不误删老仓位。
    #[test]
    fn nsf_reject_keeps_existing_position() {
        let deposit = 50_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, 1_000_000);

        // 先开 LONG 1（leverage=5）。
        assert_eq!(place(&mut api, 7001, UID_1, 10_000, 1, OrderAction::Bid, OrderType::Gtc, 5, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 7002, UID_2, 10_000, 1, OrderAction::Ask, OrderType::Gtc, 5, false), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, 1);

        // 大幅 NSF 加仓：size=3000 -> notional=30M -> required=60000 > 剩余资金 -> RISK_NSF。
        assert_eq!(place(&mut api, 7003, UID_1, 10_000, 3000, OrderAction::Bid, OrderType::Gtc, 5, false), CommandResultCode::RiskNsf);
        // 老 LONG 1 完整保留。
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, 1);
    }

    // T4 testReduceOnlyZeroFollowedBySuccessfulOpen：reduce-only-zero SUCCESS 后紧接开仓成功。
    #[test]
    fn reduce_only_zero_followed_by_successful_open() {
        let deposit = 100_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);
        add_user_money(&mut api, UID_2, deposit);

        // reduce-only 无仓 -> SUCCESS（不入 map）。
        assert_eq!(place(&mut api, 8001, UID_1, 10_000, 1, OrderAction::Ask, OrderType::Gtc, 1, true), CommandResultCode::Success);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());

        // 随后合法 BID 开仓成功。
        assert_eq!(place(&mut api, 8002, UID_1, 10_000, 1, OrderAction::Bid, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(place(&mut api, 8003, UID_2, 10_000, 1, OrderAction::Ask, OrderType::Gtc, 1, false), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, 1);
    }

    // T5 testGlobalBalanceConservedAfterFailedPlaceOrders：连续混合 fail 下单后 accounts/exchangeLocked 不变、守恒成立。
    #[test]
    fn global_balance_conserved_after_failed_place_orders() {
        let deposit = 10_000;
        let mut api = setup();
        add_user_money(&mut api, UID_1, deposit);

        let mut order_id = 9000;
        for _ in 0..5 {
            order_id += 1;
            // reduce-only 无仓 -> SUCCESS no-op。
            assert_eq!(place(&mut api, order_id, UID_1, 10_000, 1, OrderAction::Ask, OrderType::Gtc, 1, true), CommandResultCode::Success);
            order_id += 1;
            // leverage 超限。
            assert_eq!(place(&mut api, order_id, UID_1, 10_000, 1, OrderAction::Bid, OrderType::Gtc, 100, false), CommandResultCode::RiskInvalidLeverage);
            order_id += 1;
            // NSF。
            assert_eq!(place(&mut api, order_id, UID_1, 10_000, 1000, OrderAction::Bid, OrderType::Gtc, 5, false), CommandResultCode::RiskNsf);
        }

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0);
        assert_conserved(&api);
    }

    // ==========================================================================================
    // testForceClosePosition —— 强平（事件驱动）：enable_liquidation() + set_mark_price_at 使 mark 暴跌，
    // 触发 targeted 强平扫描 → FORCE 命令由 process_command 排空重喂成交 → 仓位被平掉。
    //
    // 与 Java 差异（刻意）：Java testForceClosePosition 在 mark=500 断言仓位「仍存在」且 report 字段
    // unrealizedProfit=-95000 / marginRatioScaleK=-1000（其容器 updateCurrentPriceTo 不在该配置下真正
    // 触发强平）。Rust 引擎 markprice_adjustment 在 leader 门开启时会立即 targeted 扫描并强平水下仓，
    // 故此处断言「仓位被强平移除」这一 Rust 实际终态（对应 task 说明：drained 命令的资金事件不捕获 → 断言 STATE）。
    // 用自带简单 spec（MM 5%、恒等缩放、liquidation_fee 2%）+ 破产价对手盘，确保 FORCE 全额吸收、终态确定。
    #[test]
    fn force_close_position_liquidates_underwater_long() {
        const FC_BASE: i32 = 1;
        const FC_QUOTE: i32 = 2;
        const FC_FUT: i32 = 400;
        const BORROWER: i64 = 10;
        const M1: i64 = 20; // 开仓对手（maker SHORT）
        const M2: i64 = 30; // 强平吸单方（破产价 BID）

        let fc_spec = || {
            let mut mm = BTreeMap::new();
            mm.insert(i64::MAX, 500i64); // MM 5%（scaleK 10000）
            CoreSymbolSpecification {
                symbol_id: FC_FUT,
                symbol_type: SymbolType::FuturesContractPerpetual,
                base_currency: FC_BASE,
                quote_currency: FC_QUOTE,
                base_scale_k: 1,
                quote_scale_k: 1,
                taker_fee: 0,
                maker_fee: 0,
                fee_scale_k: 10_000,
                maintenance_margin: mm,
                maintenance_margin_scale_k: 10_000,
                liquidation_fee: 200, // 2%
                ..Default::default()
            }
        };

        let mut api = ExchangeApi::new();
        api.add_currency(FC_BASE, 1);
        api.add_currency(FC_QUOTE, 1);
        assert_eq!(api.add_futures_symbol(fc_spec()), CommandResultCode::Success);
        api.enable_liquidation(); // leader 门：markprice 更新触发 targeted 扫描

        for uid in [BORROWER, M1, M2] {
            assert_eq!(api.add_user(uid), CommandResultCode::Success);
            assert_eq!(api.balance_adjustment(uid, FC_QUOTE, 10_000_000, 1), CommandResultCode::Success);
        }

        let order = |order_id: i64, uid: i64, price: i64, size: i64, action: OrderAction| PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol: FC_FUT,
            price,
            size,
            action,
            order_type: OrderType::Gtc,
            leverage: 10,
            margin_mode: MarginMode::Isolated,
            reduce_only: false,
        };

        assert_eq!(api.set_mark_price_at(FC_FUT, 100, 1_000), CommandResultCode::Success);
        // 借款人 LONG 10@100 leverage 10（margin 100）：M1 挂 ASK@100 开 SHORT，借款人 BID@100 吃单。
        assert_eq!(api.place_futures_order(order(1, M1, 100, 10, OrderAction::Ask)), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(order(2, BORROWER, 100, 10, OrderAction::Bid)), CommandResultCode::Success);
        {
            let pos = api.user_position(BORROWER, FC_FUT).expect("借款人 LONG 已开");
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.open_volume, 10);
        }

        // 破产价 = ceil_mul_div(900,10000,10*9800)=92：M2 挂 BID@92 size10 恰好吸收 FORCE ASK@92。
        assert_eq!(api.place_futures_order(order(3, M2, 92, 10, OrderAction::Bid)), CommandResultCode::Success);

        // mark 跌到 94：借款人 LONG（avg100/lev10/margin100）equity=100-60=40 < MM(47) → 触发强平。
        assert_eq!(api.set_mark_price_at(FC_FUT, 94, 2_000), CommandResultCode::Success);

        // FORCE 已由 markprice 钩子生成并被 drain_liquidation_commands 排空重喂、成交平仓。
        assert!(
            api.risk().liquidation_engine.pending_commands.is_empty(),
            "强平队列必须被排空（FORCE 已处理）"
        );
        assert!(
            api.user_position(BORROWER, FC_FUT).is_none(),
            "借款人水下 LONG 被 FORCE 全平，仓位移除"
        );
        let if_available: i64 = api.insurance_fund().futures.values().map(|e| e.available).sum();
        assert!(if_available > 0, "清算费必须计入保险基金 available");
    }

    // ==========================================================================================
    // testAdjustment —— SYMBOL_MARGIN（base USD/quote JPY）簿：4 个 ASK maker + 一笔 BID IOC size20 部分扫单，
    // 断言全局守恒（isGlobalBalancesAllZero）。Java 归属 ITFutureBasic，但用 ITFutureBase 的 SYMBOL_MARGIN spec，
    // 故内联该 spec；只断言守恒（Java 亦只断言守恒），事件计数不可复刻（无累计 handler）。
    #[test]
    fn adjustment_partial_sweep_conserves_globally() {
        const USD: i32 = 840; // base
        const JPY: i32 = 392; // quote（计费币）
        const A_MARK: i64 = 10_000;
        let (u1, u2, u3, u4) = (1_440_001i64, 1_440_002i64, 1_440_003i64, 1_440_004i64);

        let a_spec = || CoreSymbolSpecification {
            symbol_id: SYMBOL_MARGIN,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: USD,
            quote_currency: JPY,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 2,
            taker_fee: 3,
            fee_scale_k: 0,
            maintenance_margin: BTreeMap::from([(1_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 0,
            max_leverage: BTreeMap::from([(2_000, 5), (100_000, 10)]),
            init_margin: 1,
            init_margin_scale_k: 21,
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(USD, 1);
        api.add_currency(JPY, 1);
        assert_eq!(api.add_futures_symbol(a_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_MARGIN, A_MARK), CommandResultCode::Success);
        for uid in [u1, u2, u3, u4] {
            assert_eq!(api.add_user(uid), CommandResultCode::Success);
            assert_eq!(api.balance_adjustment(uid, JPY, 10_000_000, 1), CommandResultCode::Success);
        }

        let ord = |order_id: i64, uid: i64, price: i64, size: i64, action: OrderAction, ot: OrderType| PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol: SYMBOL_MARGIN,
            price,
            size,
            action,
            order_type: ot,
            leverage: 0, // builderPlace 未设 → 引擎归一为 1
            margin_mode: MarginMode::Isolated,
            reduce_only: false,
        };

        // makers（ASK）：u1 7@160000、u2 10@159900、u3 3@160000、u3 20@160500。
        assert_eq!(api.place_futures_order(ord(101, u1, 160_000, 7, OrderAction::Ask, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(ord(202, u2, 159_900, 10, OrderAction::Ask, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(ord(303, u3, 160_000, 3, OrderAction::Ask, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(ord(304, u3, 160_500, 20, OrderAction::Ask, OrderType::Gtc)), CommandResultCode::Success);

        // taker BID IOC @160500 size20：只吃 159900(10)+160000(10)=20，160500(20) maker 残留，无守恒破坏。
        assert_eq!(api.place_futures_order(ord(405, u4, 160_500, 20, OrderAction::Bid, OrderType::Ioc)), CommandResultCode::Success);

        assert!(api.total_balance().is_global_zero(), "部分扫单后全局守恒");
    }
}
