//! 翻译自 Java `exchange.core2.tests.integration.ITExtraMarginIntegration`（11 个 @Test）。
//! 验证 CROSS/ISOLATED 追加保证金（extraMargin）语义：CROSS 直接进 accounts、ISOLATED 转入
//! `position.extra_margin`，且逐仓位互相独立、平仓整额退回。
//!
//! Java 版通过 `SimpleEventsProcessor4Test` 捕获逐条 `FundEventReport`（MARGIN_ADJUST/MARGIN_REFUND/
//! MARGIN_ALERT 等）并断言 report 派生字段（unrealizedProfit/liquidationPrice/marginRatioScaleK）；Rust
//! `ExchangeApi` 不暴露这些 report 聚合事件，改为断言等价的**状态**：`api.user_account(uid,cur)` +
//! `api.user_position(uid,symbol).extra_margin/direction` + 全局守恒。
//!
//! 期货 symbol 逐字复刻 Java `ExchangeTestContainer.initFutureSymbol/initFutureSymbols`。
//! `margin_adjustment(Isolated)` 的 `symbol` 是 symbol id、`(Cross)` 的 `symbol` 是 currency id
//! （见 `MarginAdjustmentRequest` 文档）。ONEWAY 下 `action` 被忽略（随意传 Bid）。
//!
//! **未翻译的 @Test（及原因）**——harness 缺相应命令 / report 字段，按简报规则跳过：
//!   - `testInactiveUser`：依赖 SUSPEND/RESUME 用户（`ApiSuspendUser`/`ApiResumeUser`），`ExchangeApi`
//!     未暴露挂起/恢复用户命令。needs harness extension。
//!   - `testIsolatedMarginLiquidationWarning` / `...Warning2` / `testCrossMarginLiquidationWarning` /
//!     `...Warning2` / `testIsolatedMarginLiquidation2`：依赖 `updateCurrentPriceTo` + `triggerLiquidation`
//!     驱动强平 + MARGIN_ALERT/MARGIN_REFUND report 派生字段；`set_mark_price` 在 Rust 会触发 targeted
//!     强平扫描（无法用 stop-engine 关掉），且 report 聚合不建模。needs harness extension（liquidation-trigger
//!     + report-derived fields）。
//!   - `testCrossMarginWithdraw`：依赖期货挂单在提现 NSF 校验中锁定的保证金（golden "locked margin is 570"）；
//!     Rust 期货挂单不占 accounts/locked（纯虚拟仓位字段，见 `it_future_basic_tests` cancel 用例），提现 NSF
//!     阈值不复现。needs harness extension（report-derived margin lock）。
//!   共 7 个跳过，其余 4 个已翻译。

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
    use crate::core::exchange_api::{
        CancelOrderRequest, ExchangeApi, MarginAdjustmentRequest, PlaceFuturesOrderRequest,
    };

    // Java `ITExtraMarginIntegration` 字段 + `TestConstants`。
    const BASE_CURRENCY_ID: i32 = 1;
    const QUOTE_ID: i32 = 840; // CURRENECY_USD
    const XBT: i32 = 3762; // CURRENECY_XBT
    const ETH_CUR: i32 = 3928; // CURRENECY_ETH
    const SYMBOL_ID: i32 = 2;
    const BTC_SYM: i32 = 10000;
    const ETH_SYM: i32 = 10001;

    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;
    const UID_3: i64 = 1_440_003;
    const MAX_VALUE: i64 = 4_000_000;

    fn mm_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(1_000, 5), (100_000, 10)])
    }
    fn lev_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(2_000, 5), (100_000, 10)])
    }

    /// 逐字复刻 Java `initFutureSymbol(symbolId, quoteId)`（固定费 maker=10/taker=20）。
    fn init_future_symbol(symbol_id: i32) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id,
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
            max_leverage: lev_table(),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    /// 逐字复刻 `initFutureSymbols().get(0)`：BTC_USDT(10000)，固定费 maker=10/taker=20。
    fn btc_symbol() -> CoreSymbolSpecification {
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
            max_leverage: lev_table(),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    /// 逐字复刻 `initFutureSymbols().get(1)`：ETH_USDT(10001)，比例费 maker=1/taker=2 feeScaleK=100。
    fn eth_symbol() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: ETH_SYM,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: ETH_CUR,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 1,
            taker_fee: 2,
            fee_scale_k: 100,
            maintenance_margin: mm_table(),
            maintenance_margin_scale_k: 1_000,
            max_leverage: lev_table(),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    /// 对应 `createUserWithSpecificMoney(uid, money, quoteId)`。
    fn seed_user(api: &mut ExchangeApi, uid: i64, money: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, QUOTE_ID, money, txid), CommandResultCode::Success);
    }

    /// 期货下单（默认 ISOLATED / leverage 0->1 / GTC，对应 `createBid/createAskWithOrderId`）。
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

    /// 全局守恒（完整公式，含仓位 estimate_pnl + extra_margin），逐币种。
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

    // ============================================================================================
    // 1. testExtraMarin4Cross —— CROSS 追加保证金直接进 accounts（等价充值）。
    // ============================================================================================

    #[test]
    fn extra_margin_cross_credits_account() {
        let deposit1 = 2_000i64;
        let deposit2 = 1_000i64;

        let mut api = ExchangeApi::new();
        api.add_currency(BASE_CURRENCY_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(init_future_symbol(SYMBOL_ID)), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_ID, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit1, 1);

        // CROSS：symbol 传 currency id（quoteId），直接加到 balance 上。
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1,
                symbol: QUOTE_ID,
                action: OrderAction::Bid,
                amount: deposit2,
                margin_mode: MarginMode::Cross,
                order_id: 10_001,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit1 + deposit2);
        assert_conserved(&api);
    }

    // ============================================================================================
    // 2. testExtraMarin4Isolated —— ISOLATED 追加保证金：无仓位拒绝 / 模式不匹配拒绝 / 匹配成功。
    // ============================================================================================

    #[test]
    fn extra_margin_isolated_requires_matching_position() {
        let deposit1 = 2_000i64;
        let deposit2 = 1_000i64;
        let size = 1i64;

        let mut api = ExchangeApi::new();
        api.add_currency(BASE_CURRENCY_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(init_future_symbol(SYMBOL_ID)), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_ID, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit1, 1);

        // ISOLATED margin cmd（symbol=symbol id）；仓位不存在 -> 拒绝。
        let adjust = MarginAdjustmentRequest {
            uid: UID_1,
            symbol: SYMBOL_ID,
            action: OrderAction::Bid,
            amount: deposit2,
            margin_mode: MarginMode::Isolated,
            order_id: 10_001,
        };
        assert_eq!(api.margin_adjustment(adjust), CommandResultCode::RiskMarginPositionNotExists);

        // 挂一个 CROSS BID（resting，无对手）-> 生成 CROSS 仓位记录。
        assert_eq!(place(&mut api, 10_101, UID_1, SYMBOL_ID, 10_000, size, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).map(|p| p.margin_mode), Some(MarginMode::Cross));
        assert_conserved(&api);

        // 仓位是 CROSS，ISOLATED 追加 -> 模式不匹配拒绝。
        assert_eq!(api.margin_adjustment(adjust), CommandResultCode::RiskMarginModeMismatch);

        // 撤单 -> 仓位记录拆除。
        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 10_101, uid: UID_1, symbol: SYMBOL_ID }),
            CommandResultCode::Success
        );
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none());
        assert_conserved(&api);

        // 挂 ISOLATED BID -> ISOLATED 仓位记录。
        assert_eq!(place(&mut api, 10_102, UID_1, SYMBOL_ID, 10_000, size, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).map(|p| p.margin_mode), Some(MarginMode::Isolated));

        // 仓位存在 + 模式匹配 -> 追加成功，extra_margin == deposit2。
        assert_eq!(api.margin_adjustment(adjust), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().extra_margin, deposit2);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit1 - deposit2, "追加后 account 减 deposit2");
        assert_conserved(&api);
    }

    // ============================================================================================
    // 3. testIsolatedClosePosition —— 平仓后 extra_margin 整额退回 accounts。
    // ============================================================================================

    #[test]
    fn isolated_close_position_refunds_extra_margin() {
        let deposit = 1_000i64;
        let deposit2 = 500i64;
        let fee = 10i64; // 固定 maker fee = size(1) * 10
        let price1 = 10_000i64;
        let price2 = 10_500i64;

        let mut api = ExchangeApi::new();
        api.add_currency(BASE_CURRENCY_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(init_future_symbol(SYMBOL_ID)), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_ID, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        // 开仓：UID_1 maker BID（LONG）、UID_2 taker ASK（SHORT），ISOLATED。
        assert_eq!(place(&mut api, 1005, UID_1, SYMBOL_ID, price1, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, SYMBOL_ID, price1, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().extra_margin, 0);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee);
        assert_conserved(&api);

        // ISOLATED 追加 deposit2。
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1,
                symbol: SYMBOL_ID,
                action: OrderAction::Bid,
                amount: deposit2,
                margin_mode: MarginMode::Isolated,
                order_id: 10_001,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, SYMBOL_ID).unwrap().extra_margin, deposit2);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee - deposit2);
        assert_conserved(&api);

        // 平仓 @10500：UID_1 maker ASK、UID_2 taker BID。
        assert_eq!(place(&mut api, 1007, UID_1, SYMBOL_ID, price2, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, SYMBOL_ID, price2, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);

        assert!(api.user_position(UID_1, SYMBOL_ID).is_none(), "平仓后仓位拆除，extra_margin 退回");
        // deposit + deposit2 + price2 - price1 - 2*fee - deposit2（extra_margin 整额退回后剩 fee 与已实现盈利）。
        let expected = deposit + deposit2 + price2 - price1 - 2 * fee - deposit2;
        assert_eq!(api.user_account(UID_1, QUOTE_ID), expected);
        assert_eq!(expected, 1_480);
        assert_conserved(&api);
    }

    // ============================================================================================
    // 4. tesMultipleExtraMargin —— 每个仓位维护自己的 extra_margin，互相独立。
    // ============================================================================================

    #[test]
    fn multiple_extra_margin_are_independent_per_position() {
        let deposit = 10_000i64;
        let price1 = 10_000i64;
        let price2 = 15_000i64;

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(QUOTE_ID, 1);
        api.add_currency(ETH_CUR, 1);
        assert_eq!(api.add_futures_symbol(btc_symbol()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(eth_symbol()), CommandResultCode::Success);
        // 两个 symbol mark 都设 10000（先于开仓，故不触发对已开仓的强平扫描）。
        assert_eq!(api.set_mark_price(BTC_SYM, 10_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);
        seed_user(&mut api, UID_3, MAX_VALUE, 3);

        // UID_1 maker：BTC BID(LONG) + ETH ASK(SHORT)；UID_2 taker 对吃。
        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, price1, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1007, UID_1, ETH_SYM, price2, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, BTC_SYM, price1, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, ETH_SYM, price2, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);

        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().direction, PositionDirection::Long);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, 0);
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().direction, PositionDirection::Short);
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().extra_margin, 0);
        assert_conserved(&api);

        // 逐仓位追加，独立累加。
        let add = |api: &mut ExchangeApi, symbol: i32, amount: i64, order_id: i64| {
            assert_eq!(
                api.margin_adjustment(MarginAdjustmentRequest {
                    uid: UID_1,
                    symbol,
                    action: OrderAction::Bid,
                    amount,
                    margin_mode: MarginMode::Isolated,
                    order_id,
                }),
                CommandResultCode::Success
            );
        };

        add(&mut api, BTC_SYM, 200, 20_001);
        add(&mut api, ETH_SYM, 300, 20_002);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, 200);
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().extra_margin, 300);
        assert_conserved(&api);

        add(&mut api, BTC_SYM, 300, 20_003);
        add(&mut api, ETH_SYM, 200, 20_004);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, 500);
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().extra_margin, 500);
        assert_conserved(&api);
    }
}
