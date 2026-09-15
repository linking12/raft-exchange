//! 翻译自 Java `exchange.core2.tests.integration.ITFutureCross`（19 个 @Test）——期货 **CROSS 保证金**
//! 集成测试。直连引擎（无 raft/Disruptor），逐条对拍 Java 黄金值：结果码 + 仓位态
//! （direction/open_volume/margin_mode/pending_*_avg_price）+ accounts 结算（fee/PnL）+ 全局守恒。
//!
//! 期货 symbol 规格逐字复刻 Java `ExchangeTestContainer`：
//!   - `initFutureSymbol(symbolId=2, quoteId=840)`：base=1 quote=840, makerFee=10 takerFee=20,
//!     feeScaleK=0（固定费）, maintenanceMargin={1000:5,100000:10}@scaleK=1000, maxLeverage={2000:5,100000:10},
//!     initMargin=1@scaleK=100。
//!   - `initFutureSymbols()`：BTC(10000, base=XBT quote=USD, 固定费 maker10/taker20) +
//!     ETH(10001, base=ETH quote=USD, **比例费** maker1/taker2 @feeScaleK=100)。LTC(10002) 未被任何断言引用，略去。
//! 货币 digit(0) → scale_k=1（恒等缩放）。
//!
//! Java `createBid/AskWithOrderId` 不显式设 leverage（Lombok int 默认，走 base initMargin 率）；本文件统一传
//! `leverage=1`——与该 base 率等价（notional*initMargin/(initMarginScaleK*1)=notional*1%），验金对齐 Java 黄金
//! 值（如 `testCrossMarginWithdraw` 的 570 = 仓位保证金 250 + taker 费预留 320）。
//!
//! **未翻译的 @Test（及原因）**——harness 缺相应基础设施，按简报 SKIP 规则跳过（共 7 个）：
//!   - 强平类 4 个（`testCrossMarginLiquidation`/`2`/`3`/`testCrossMarginLiquidationWarning`）：依赖
//!     `LiquidationEngine::stop` + `triggerLiquidation`（主动驱动 FORCE→IF→ADL）+ `updateCurrentPriceTo` 推进 R2 +
//!     report 层 unrealizedProfit/liquidationPrice/marginRatioScaleK 字段 + LIQUIDATION_ALERT/ADL_ORIGIN_CLOSE
//!     累计 fund event 断言。`ExchangeApi` 无强平引擎控制入口，且 `set_mark_price` 只做 targeted 扫描，无法复刻。
//!   - 现货交叉类 2 个（`testPlaceExchange`/`testPlaceExchange2`）：断言 `available(profile,cur) =
//!     accounts − exchangeLocked`（report 派生字段），验证「下现货单时 NSF 校验要合计该币种所有 CROSS 期货持仓保证金」；
//!     harness 未暴露 exchangeLocked 报表派生量，跳过。
//!   - `testGlobalBalance`：依赖 `initFeeSymbols/initFeeUsers`（SYMBOL_MARGIN=5991 的 USD/JPY 期货基类配置）+
//!     `totalBalanceReport().isGlobalBalancesAllZero()` 报表 + IOC `reservePrice`，非本 harness 期货路径的干净子集，跳过。
//!
//! 其余 12 个已翻译（事件断言类 `testOpenPosition4Bid/Ask`、`testOpenMultiplePosition4Bid/Ask`、
//! `testClosePosition`、`testPartialClosePosition` 只保留 accounts/仓位态断言——harness 无逐笔
//! FuturesExecutionReport / 累计 FundEvent，事件字段断言无法复刻，同 `it_future_basic_tests.rs` 的降级策略）。

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::margin_mode::MarginMode;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::position_direction::PositionDirection;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::exchange_api::{CancelOrderRequest, ExchangeApi, PlaceFuturesOrderRequest};

    // ==========================================================================================
    // 常量：逐字对齐 Java TestConstants / initFutureSymbol(s)。
    // ==========================================================================================

    const BASE_CURRENCY_ID: i32 = 1; // 单 symbol 测试的 base
    const QUOTE_ID: i32 = 840; // CURRENECY_USD（所有测试的 quote/抵押币）
    const XBT: i32 = 3762; // CURRENECY_XBT
    const ETH: i32 = 3928; // CURRENECY_ETH
    const SYMBOL_ID: i32 = 2; // initFutureSymbol
    const BTC_SYM: i32 = 10000; // initFutureSymbols()[0]
    const ETH_SYM: i32 = 10001; // initFutureSymbols()[1]

    const UID_1: i64 = 1;
    const UID_2: i64 = 2;
    const UID_3: i64 = 3;
    const MAX_VALUE: i64 = 4_000_000;
    const MARK: i64 = 10_000;

    fn mm_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(1_000, 5), (100_000, 10)])
    }
    fn leverage_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(2_000, 5), (100_000, 10)])
    }

    /// 精确复刻 Java `initFutureSymbol(symbolId=2, quoteId=840)`。
    fn single_futures_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL_ID,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_CURRENCY_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            fee_scale_k: 0,
            maintenance_margin: mm_table(),
            maintenance_margin_scale_k: 1_000,
            max_leverage: leverage_table(),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    /// 复刻 `initFutureSymbols()[0]`——BTC 固定费。
    fn btc_futures_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: BTC_SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: XBT,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            fee_scale_k: 0,
            maintenance_margin: mm_table(),
            maintenance_margin_scale_k: 1_000,
            max_leverage: leverage_table(),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    /// 复刻 `initFutureSymbols()[1]`——ETH 比例费（makerFee=1 takerFee=2 feeScaleK=100 → 1%/2%）。
    fn eth_futures_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: ETH_SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: ETH,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 1,
            taker_fee: 2,
            fee_scale_k: 100,
            maintenance_margin: mm_table(),
            maintenance_margin_scale_k: 1_000,
            max_leverage: leverage_table(),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    // ==========================================================================================
    // api 构造 / 播种 helper。
    // ==========================================================================================

    /// 单 symbol（symbolId=2）：currencies(1,840) + 期货 symbol + mark=10000。
    fn setup_single() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_CURRENCY_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(single_futures_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_ID, MARK), CommandResultCode::Success);
        api
    }

    /// 双 symbol（BTC 10000 + ETH 10001）：currencies(XBT,USD,ETH) + 两期货 symbol + 两 mark=10000。
    fn setup_two() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(QUOTE_ID, 1);
        api.add_currency(ETH, 1);
        assert_eq!(api.add_futures_symbol(btc_futures_spec()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(eth_futures_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, MARK), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, MARK), CommandResultCode::Success);
        api
    }

    /// 对应 `createUserWithSpecificMoney(uid, amount, quoteId)`：ADD_USER + BALANCE_ADJUSTMENT。
    fn seed_user(api: &mut ExchangeApi, uid: i64, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, QUOTE_ID, amount, txid), CommandResultCode::Success);
    }

    /// 期货下单封装：leverage=1（等价 Java createBid/Ask 的 base initMargin 率），ONEWAY，非 reduce-only。
    #[allow(clippy::too_many_arguments)]
    fn place(
        api: &mut ExchangeApi,
        order_id: i64,
        uid: i64,
        symbol: i32,
        price: i64,
        size: i64,
        action: OrderAction,
        margin_mode: MarginMode,
    ) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol,
            price,
            size,
            action,
            order_type: OrderType::Gtc,
            leverage: 1,
            margin_mode,
            reduce_only: false,
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
    // 1. testCancelSuccess —— isolated / cross 不能混下；撤单后可改模式。
    // ==========================================================================================

    #[test]
    fn cancel_success_margin_mode_mismatch_guard() {
        let deposit = 2_000;
        let order1 = 1101; // ISOLATED BID
        let order2 = 1102; // CROSS BID
        let mut api = setup_single();
        seed_user(&mut api, UID_1, deposit, 1);

        // 下 ISOLATED BID 成功 → 仓位 pending_buy_size=1、margin_mode=Isolated。
        assert_eq!(place(&mut api, order1, UID_1, SYMBOL_ID, MARK, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, SYMBOL_ID).expect("ISOLATED 挂单后应有仓位记录");
            assert_eq!(pos.pending_buy_size, 1);
            assert_eq!(pos.margin_mode, MarginMode::Isolated);
        }
        // 已有 isolated 持仓 → cross 下单被拒。
        assert_eq!(place(&mut api, order2, UID_1, SYMBOL_ID, MARK, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::RiskMarginModeMismatch);

        // 撤 isolated 单 → 仓位记录拆除。
        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: order1, uid: UID_1, symbol: SYMBOL_ID }), CommandResultCode::Success);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());

        // 撤单后再下 cross 成功。
        assert_eq!(place(&mut api, order2, UID_1, SYMBOL_ID, MARK, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, SYMBOL_ID).expect("CROSS 挂单后应有仓位记录");
            assert_eq!(pos.pending_buy_size, 1);
            assert_eq!(pos.margin_mode, MarginMode::Cross);
        }
        // 已有 cross 持仓 → isolated 下单被拒。
        assert_eq!(place(&mut api, order1, UID_1, SYMBOL_ID, MARK, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::RiskMarginModeMismatch);

        // 撤 cross 单 → 记录拆除，再下 isolated 成功。
        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: order2, uid: UID_1, symbol: SYMBOL_ID }), CommandResultCode::Success);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
        assert_eq!(place(&mut api, order1, UID_1, SYMBOL_ID, MARK, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, SYMBOL_ID).unwrap();
            assert_eq!(pos.pending_buy_size, 1);
            assert_eq!(pos.margin_mode, MarginMode::Isolated);
        }
        assert_conserved(&api);
    }

    // ==========================================================================================
    // 2. testDefaultMargin —— 显式 ISOLATED 下单，仓位 margin_mode 为 Isolated。
    // ==========================================================================================

    #[test]
    fn default_margin_is_isolated() {
        let deposit = 20_000;
        let mut api = setup_single();
        seed_user(&mut api, UID_1, deposit, 1);

        assert_eq!(place(&mut api, 1201, UID_1, SYMBOL_ID, MARK, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().margin_mode, MarginMode::Isolated);
    }

    // ==========================================================================================
    // 3. tesCloseMarginThenChangeMode —— 平仓后可改保证金模式（CROSS 开平 → 再开 ISOLATED）。
    //    仅断言仓位数量 / margin_mode（不验余额）；user1 抵押放宽以规避与本用例无关的 NSF 摩擦。
    // ==========================================================================================

    #[test]
    fn close_margin_then_change_mode() {
        let mut api = setup_single();
        seed_user(&mut api, UID_1, 100_000, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        // 开 CROSS 仓：user1 BID maker、user2 ASK taker @10000。
        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, 10_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, 10_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().margin_mode, MarginMode::Cross);

        // 平 CROSS 仓：user1 ASK maker、user2 BID taker @10500 → 全平。
        assert_eq!(place(&mut api, 1007, UID_1, SYMBOL_ID, 10_500, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, SYMBOL_ID, 10_500, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none(), "全平后仓位拆除，可改模式");

        // 平仓后开 ISOLATED 仓：user1 BID maker、user2 ASK taker @11000。
        assert_eq!(place(&mut api, 1009, UID_1, SYMBOL_ID, 11_000, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1010, UID_2, SYMBOL_ID, 11_000, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().margin_mode, MarginMode::Isolated);
    }

    // ==========================================================================================
    // 4. testPendingAvgPrice —— pending_buy_avg_price / pending_sell_avg_price 计算准确。
    // ==========================================================================================

    #[test]
    fn pending_avg_price() {
        let price1 = 10_000;
        let price2 = 15_000;
        let mut api = setup_single();
        seed_user(&mut api, UID_1, 10_000, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        // user1 两张 CROSS BID（size1@10000 + size1@15000）resting → 均价 = (10000+15000)/2。
        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, price1, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1007, UID_1, SYMBOL_ID, price2, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().pending_buy_avg_price, (price1 + price2) / 2);

        // user2 两张 ASK 逐一吃满 user1 的挂单（@15000 吃 15000 档、@10000 吃 10000 档）→ 完全成交。
        assert_eq!(place(&mut api, 1008, UID_2, SYMBOL_ID, price2, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, price1, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        // 完全成交后均价归零。
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().pending_buy_avg_price, 0);
        assert_eq!(api.user_position(UID_2, SYMBOL_ID).unwrap().pending_sell_avg_price, 0);
        assert_conserved(&api);
    }

    // ==========================================================================================
    // 5. testCrossMarginWithdraw —— 提现要合计该币种所有 CROSS 期货持仓保证金（空仓，仅挂单）。
    //    两 symbol 各一张 resting 单，锁定保证金 = 250(仓位) + 320(taker 费预留) = 570。
    // ==========================================================================================

    #[test]
    fn cross_margin_withdraw() {
        let deposit = 10_000;
        let price1 = 10_000;
        let price2 = 15_000;
        let mut api = setup_two();
        seed_user(&mut api, UID_1, deposit, 1);

        // BTC BID size1@10000 + ETH ASK size1@15000（均 CROSS resting，不动 accounts）。
        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, price1, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1007, UID_1, ETH_SYM, price2, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit, "期货挂单不扣 accounts");

        // 提现全额 → NSF（570 保证金锁定）。
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -deposit, 100), CommandResultCode::RiskNsf);

        // 补 569 后提现仍 NSF（可用 569 < 570）。
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 569, 101), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -deposit, 102), CommandResultCode::RiskNsf);

        // 再补 1（可用 570 == 570）→ 提现成功。
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 1, 103), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -deposit, 104), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 570, "提现后仅余 570 保证金对应额");
        assert_conserved(&api);
    }

    // ==========================================================================================
    // 6. testCrossMarginWithdraw2 —— 提现合计所有 CROSS 持仓（有仓 + 浮盈）；平掉一腿后可提现。
    // ==========================================================================================

    #[test]
    fn cross_margin_withdraw2() {
        let deposit = 10_000;
        let price1 = 10_000;
        let price2 = 15_000;
        let mut api = setup_two();
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);
        seed_user(&mut api, UID_3, MAX_VALUE, 3);

        // user1 开仓：BTC BID maker + ETH ASK maker；user2 各吃对手。
        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, price1, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1007, UID_1, ETH_SYM, price2, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, BTC_SYM, price1, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, ETH_SYM, price2, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        // fee1(BTC maker,固定)=10, fee2(ETH maker,比例)=15000*1*1/100=150 → account = 10000-10-150 = 9840。
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().open_volume, 1);
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().open_volume, 1);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10 - 150);

        // mark：BTC→15000（LONG 浮盈 +5000）、ETH→5000（SHORT 浮盈 +10000）；双方均盈利，不触发强平。
        assert_eq!(api.set_mark_price(BTC_SYM, 15_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 5_000), CommandResultCode::Success);

        // 浮盈虽高，但现金 9840 < 提现额 10000 → NSF。
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -deposit, 500), CommandResultCode::RiskNsf);

        // 平 BTC：user1 ASK maker、user3 BID taker @15000 → 实现盈利 +5000，fee3(BTC maker)=10。
        assert_eq!(place(&mut api, 1009, UID_1, BTC_SYM, 15_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1010, UID_3, BTC_SYM, 15_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert!(api.user_position(UID_1, BTC_SYM).is_none(), "BTC 平掉，剩 ETH 一腿");
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().open_volume, 1);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10 - 150 - 10 + 5_000);

        // 现金 14830 ≥ 10000 → 提现成功。
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -deposit, 501), CommandResultCode::Success);
        assert_conserved(&api);
    }

    // ==========================================================================================
    // 7. testOpenPosition4Bid —— 完全成交，taker 为 ASK（user1 BID maker、user2 ASK taker）。
    //    只保留 accounts + direction（事件字段断言 harness 无法复刻）。
    // ==========================================================================================

    #[test]
    fn open_position_taker_ask() {
        let deposit = 1_000;
        let mut api = setup_single();
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, MARK, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, MARK, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10, "user1 maker fee = 1*10");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - 20, "user2 taker fee = 1*20");
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().direction, PositionDirection::Long);
        assert_eq!(api.user_position(UID_2, SYMBOL_ID).unwrap().direction, PositionDirection::Short);
        assert_conserved(&api);
    }

    // ==========================================================================================
    // 8. testOpenPosition4Ask —— 完全成交，taker 为 BID（user1 ASK maker、user2 BID taker）。
    // ==========================================================================================

    #[test]
    fn open_position_taker_bid() {
        let deposit = 1_000;
        let mut api = setup_single();
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, MARK, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, MARK, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - 20);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().direction, PositionDirection::Short);
        assert_eq!(api.user_position(UID_2, SYMBOL_ID).unwrap().direction, PositionDirection::Long);
        assert_conserved(&api);
    }

    // ==========================================================================================
    // 9. testOpenMultiplePosition4Bid —— maker BID size10、taker ASK size2 → 只成交 2，仅按已成交计费。
    // ==========================================================================================

    #[test]
    fn open_multiple_partial_taker_ask() {
        let size = 10;
        let tx_size = 2;
        let deposit = 1_000 * size;
        let mut api = setup_single();
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, MARK, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, MARK, tx_size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - tx_size * 10);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - tx_size * 20);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, tx_size);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().direction, PositionDirection::Long);
        assert_conserved(&api);
    }

    // ==========================================================================================
    // 10. testOpenMultiplePosition4Ask —— 对称（maker ASK size10、taker BID size2）。
    // ==========================================================================================

    #[test]
    fn open_multiple_partial_taker_bid() {
        let size = 10;
        let tx_size = 2;
        let deposit = 1_000 * size;
        let mut api = setup_single();
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, MARK, size, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, MARK, tx_size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - tx_size * 10);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - tx_size * 20);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().open_volume, tx_size);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().direction, PositionDirection::Short);
        assert_conserved(&api);
    }

    // ==========================================================================================
    // 11. testClosePosition —— 开 LONG@10000 后 @10500 全平；CROSS 全平即结算 PnL。
    //     user1 = 1000 −10(开 maker) −10(平 maker) +500；user2 = MAX −20 −20 −500。
    // ==========================================================================================

    #[test]
    fn close_full_position_settles_pnl() {
        let deposit = 1_000;
        let mut api = setup_single();
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        // 开仓：user1 BID maker、user2 ASK taker @10000。
        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, 10_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, 10_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        // 平仓：user1 ASK maker、user2 BID taker @10500。
        assert_eq!(place(&mut api, 1007, UID_1, SYMBOL_ID, 10_500, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, SYMBOL_ID, 10_500, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10 - 10 + 500);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - 20 - 20 - 500);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none(), "全平后仓位拆除");
        assert!(api.user_position(UID_2, SYMBOL_ID).is_none());
        assert_conserved(&api);
    }

    // ==========================================================================================
    // 12. testPartialClosePosition —— 开 LONG10@10000，平仓单 ASK size2@10500 只成交 1（taker BID 1）。
    //     部分平不实现盈亏；user1 = 10000 −100 −10；user2 = MAX −200 −20。
    // ==========================================================================================

    #[test]
    fn partial_close_position_defers_pnl() {
        let deposit = 10_000;
        let mut api = setup_single();
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, 10_000, 10, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, 10_000, 10, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        // user1 ASK size2@10500(maker resting)、user2 BID size1@10500(taker) → 只平 1。
        assert_eq!(place(&mut api, 1007, UID_1, SYMBOL_ID, 10_500, 2, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, SYMBOL_ID, 10_500, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - 10 * 10 - 10 * 1);
        assert_eq!(api.user_account(UID_2, QUOTE_ID), MAX_VALUE - 20 * 10 - 20 * 1);
        // user1 剩 LONG 9，部分平不实现盈亏。
        let pos = api.user_position(UID_1, SYMBOL_ID).expect("部分平后仍持仓");
        assert_eq!(pos.open_volume, 9);
        assert_eq!(pos.direction, PositionDirection::Long);
        assert_eq!(pos.profit, 0, "部分平递延盈亏，不入 profit");
        // user2 剩 SHORT 9。
        assert_eq!(api.user_position(UID_2, SYMBOL_ID).unwrap().open_volume, 9);
        assert_eq!(api.user_position(UID_2, SYMBOL_ID).unwrap().direction, PositionDirection::Short);
        assert_conserved(&api);
    }
}
