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
//! **已 backfill 的 @Test（harness 现支持 suspend/resume + 清算触发）**：
//!   - `testInactiveUser`：`suspend_user`/`resume_user`——挂起用户后 MARGIN_ADJUSTMENT 拒 AUTH_INVALID_USER，
//!     恢复后成功。
//!   - `testIsolatedMarginLiquidationWarning` / `...Warning2` / `testCrossMarginLiquidationWarning` /
//!     `...Warning2`：`enable_liquidation()` + `set_mark_price_at(sym, adverse, ts)` 触发定向扫描，价格波动
//!     到「预警区间」（MM ≤ equity < 1.2×MM）→ **不强平**、仓位/账户不变；MARGIN_ALERT 在 Rust 外置 no-op 不断言，
//!     改断强平前的 report 派生字段（unrealized_pnl，逐仓另含 liquidation_price/margin_ratio_scale_k）via
//!     `single_user()` + 全局守恒 `total_balance().is_global_zero()`。
//!   - `testIsolatedMarginLiquidation2`：逐仓双 symbol 加过 extraMargin 后价格暴跌 → 两腿均被清算（仓位全平）；
//!     强平 fund event 走内部排空命令不捕获，断言 UID_1 两仓移除 + 守恒（BP-fill/ADL/IF 逐笔手续费黄金值依赖
//!     异步 timing，按 `it_liquidation_tests` 降级不逐值断言）。
//!
//! **未翻译的 @Test（及原因）——共 1 个跳过**：
//!   - `testCrossMarginWithdraw`：依赖期货挂单在提现 NSF 校验中锁定的保证金（golden "locked margin is 570"）；
//!     Rust 期货挂单不占 accounts/locked（纯虚拟仓位字段，见 `it_future_basic_tests` cancel 用例），提现 NSF
//!     阈值不复现。needs harness extension（report-derived margin lock）。

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

    // ============================================================================================
    // suspend + 强平预警/清算 backfill。强平 fund event 走内部排空命令不捕获，断言最终状态 + 强平前 report
    // 派生字段 via single_user() + 全局守恒 total_balance().is_global_zero()。MARGIN_ALERT 外置 no-op 不断言。
    // ============================================================================================

    /// 从 single_user 报表按 symbol 取 PositionView（含 unrealized_pnl / liquidation_price / margin_ratio_scale_k）。
    fn pos_view(api: &ExchangeApi, uid: i64, symbol: i32) -> exchange_core_rs::core::reports::PositionView {
        api.single_user(uid, 0)
            .positions
            .into_iter()
            .find(|p| p.symbol == symbol)
            .expect("仓位报表记录应存在")
    }

    // 5. testInactiveUser —— 挂起用户不允许追加保证金（AUTH_INVALID_USER），恢复后成功。
    #[test]
    fn inactive_user_cannot_adjust_margin() {
        let deposit = 10_000i64;

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(QUOTE_ID, 1);
        api.add_currency(ETH_CUR, 1);
        assert_eq!(api.add_futures_symbol(btc_symbol()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(eth_symbol()), CommandResultCode::Success);

        // 用户 0 余额（可挂起：无非空账户）。
        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.suspend_user(UID_1), CommandResultCode::Success);
        assert!(api.ups().get(UID_1).is_none(), "挂起后从注册表移除");

        // 挂起态：CROSS 追加保证金拒 AUTH_INVALID_USER。
        let adjust = MarginAdjustmentRequest {
            uid: UID_1,
            symbol: QUOTE_ID,
            action: OrderAction::Bid,
            amount: deposit,
            margin_mode: MarginMode::Cross,
            order_id: 1_345,
        };
        assert_eq!(api.margin_adjustment(adjust), CommandResultCode::AuthInvalidUser);

        // 恢复后 CROSS 追加成功（直接进 accounts）。
        assert_eq!(api.resume_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.margin_adjustment(adjust), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit);
        assert!(api.total_balance().is_global_zero());
    }

    // 6. testIsolatedMarginLiquidationWarning —— 逐仓多头价格波动进预警区间（不强平）；追加保证金后 LP/mr 下移。
    #[test]
    fn isolated_margin_liquidation_warning() {
        let deposit = 10_000i64;
        let fee = 10i64; // 固定 maker fee = size(1)*10

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(btc_symbol()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        // UID_1 maker BID（LONG）1@10000 ISOLATED，UID_2 taker ASK。
        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, 10_000, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, BTC_SYM, 10_000, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().open_volume, 1);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee);
        assert!(api.total_balance().is_global_zero());

        // 落价 9950：进入预警区间（MM ≤ equity < 1.2×MM）→ 不强平。
        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, 9_950), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee, "预警不改账户");
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().open_volume, 1, "预警不平仓");
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, 0);
        {
            // 强平前 report 派生（逐字对齐 Java MARGIN_ALERT golden）。
            let p = pos_view(&api, UID_1, BTC_SYM);
            assert_eq!(p.unrealized_pnl, -50);
            assert_eq!(p.liquidation_price, 9_949);
            assert_eq!(p.margin_ratio_scale_k, 980);
        }
        assert!(api.total_balance().is_global_zero());

        // ISOLATED 追加 10 → extra_margin=10、account 再减 10；LP 降到 9939、mr 816。
        let extra = 10i64;
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1, symbol: BTC_SYM, action: OrderAction::Bid, amount: extra,
                margin_mode: MarginMode::Isolated, order_id: 10_001,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, extra);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee - extra);
        {
            let p = pos_view(&api, UID_1, BTC_SYM);
            assert_eq!(p.liquidation_price, 9_939);
            assert_eq!(p.margin_ratio_scale_k, 816);
        }

        // 再次扫描（同价）→ 补了保证金后仍不强平，状态不变。
        assert_eq!(api.set_mark_price(BTC_SYM, 9_950), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee - extra);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().open_volume, 1);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, extra);
        assert!(api.total_balance().is_global_zero());
    }

    // 7. testIsolatedMarginLiquidationWarning2 —— 同上，但只补 7（仍不足 1.2×MM）→ 二次预警，仓位/守恒不变。
    #[test]
    fn isolated_margin_liquidation_warning_insufficient_topup() {
        let deposit = 10_000i64;
        let fee = 10i64;

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(btc_symbol()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);

        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, 10_000, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, BTC_SYM, 10_000, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, 9_950), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().open_volume, 1);
        {
            let p = pos_view(&api, UID_1, BTC_SYM);
            assert_eq!(p.unrealized_pnl, -50);
            assert_eq!(p.liquidation_price, 9_949);
            assert_eq!(p.margin_ratio_scale_k, 980);
        }

        // 只补 7（不足 1.2×MM）→ 仍预警（no-op），仓位不变。
        let extra = 7i64;
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1, symbol: BTC_SYM, action: OrderAction::Bid, amount: extra,
                margin_mode: MarginMode::Isolated, order_id: 10_001,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, extra);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee - extra);

        assert_eq!(api.set_mark_price(BTC_SYM, 9_950), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().open_volume, 1, "补 7 仍不足，不强平");
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, extra);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), deposit - fee - extra);
        assert!(api.total_balance().is_global_zero());
    }

    // 8. testCrossMarginLiquidationWarning —— 全仓双 symbol（BTC LONG + ETH SHORT）价格波动进预警区间（不强平）；
    //    CROSS 追加保证金直接进 accounts。
    #[test]
    fn cross_margin_liquidation_warning() {
        let deposit = 10_000i64;

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(QUOTE_ID, 1);
        api.add_currency(ETH_CUR, 1);
        assert_eq!(api.add_futures_symbol(btc_symbol()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(eth_symbol()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, 10_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);
        seed_user(&mut api, UID_3, MAX_VALUE, 3);

        // UID_1 maker：BTC BID(LONG) + ETH ASK(SHORT)，均 CROSS；UID_2 taker 对吃。
        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, 10_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1007, UID_1, ETH_SYM, 15_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, BTC_SYM, 10_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, ETH_SYM, 15_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        // account = 10000 - 10(BTC maker 固定) - 150(ETH maker 15000*1/100) = 9840。
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().open_volume, 1);
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().open_volume, 1);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_840);
        assert!(api.total_balance().is_global_zero());

        // UID_3 承接单（预警不消耗，仅 resting）。
        assert_eq!(place(&mut api, 1009, UID_3, BTC_SYM, 10_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1010, UID_3, ETH_SYM, 15_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        // 落价：BTC→9000（LONG 亏 1000）、ETH→23660（SHORT 亏 8660）→ equity 进预警区间，不强平。
        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, 9_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 23_660), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, BTC_SYM).map(|p| p.open_volume), Some(1), "预警不强平");
        assert_eq!(api.user_position(UID_1, ETH_SYM).map(|p| p.open_volume), Some(1));
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_840);
        assert_eq!(pos_view(&api, UID_1, ETH_SYM).unrealized_pnl, -8_660, "ETH SHORT@15000 mark23660");
        // Java 黄金值：ETH SHORT 腿的强平价/保证金率（跨币种 cross，ITExtraMarginIntegration:755-756 MARGIN_ALERT）。
        assert_eq!(pos_view(&api, UID_1, ETH_SYM).liquidation_price, 23_677, "cross LP");
        assert_eq!(pos_view(&api, UID_1, ETH_SYM).margin_ratio_scale_k, 655, "cross margin ratio");
        assert!(api.total_balance().is_global_zero());

        // CROSS 追加 20 → 直接进 accounts（9840→9860）；再扫描仍不强平。
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1, symbol: QUOTE_ID, action: OrderAction::Bid, amount: 20,
                margin_mode: MarginMode::Cross, order_id: 20_001,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_860);
        assert_eq!(api.set_mark_price(ETH_SYM, 23_660), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, BTC_SYM).map(|p| p.open_volume), Some(1));
        assert_eq!(api.user_position(UID_1, ETH_SYM).map(|p| p.open_volume), Some(1));
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_860);
        assert!(api.total_balance().is_global_zero());
    }

    // 9. testCrossMarginLiquidationWarning2 —— 同上，但 CROSS 只补 12（仍不足）→ 二次预警，仓位/守恒不变。
    #[test]
    fn cross_margin_liquidation_warning_insufficient_topup() {
        let deposit = 10_000i64;

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(QUOTE_ID, 1);
        api.add_currency(ETH_CUR, 1);
        assert_eq!(api.add_futures_symbol(btc_symbol()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(eth_symbol()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, 10_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);
        seed_user(&mut api, UID_3, MAX_VALUE, 3);

        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, 10_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1007, UID_1, ETH_SYM, 15_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, BTC_SYM, 10_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, ETH_SYM, 15_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_840);

        assert_eq!(place(&mut api, 1009, UID_3, BTC_SYM, 10_000, 1, OrderAction::Bid, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1010, UID_3, ETH_SYM, 15_000, 1, OrderAction::Ask, MarginMode::Cross), CommandResultCode::Success);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, 9_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 23_660), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, BTC_SYM).map(|p| p.open_volume), Some(1));
        assert_eq!(api.user_position(UID_1, ETH_SYM).map(|p| p.open_volume), Some(1));
        assert_eq!(pos_view(&api, UID_1, ETH_SYM).unrealized_pnl, -8_660);
        // Java 黄金值：首次预警的 ETH SHORT 强平价/保证金率（ITExtraMarginIntegration:889-890）。
        assert_eq!(pos_view(&api, UID_1, ETH_SYM).liquidation_price, 23_677, "cross LP 首警");
        assert_eq!(pos_view(&api, UID_1, ETH_SYM).margin_ratio_scale_k, 655, "cross margin ratio 首警");
        assert!(api.total_balance().is_global_zero());

        // CROSS 只补 12（仍不足）→ 9840→9852，二次预警，仓位不变。
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1, symbol: QUOTE_ID, action: OrderAction::Bid, amount: 12,
                margin_mode: MarginMode::Cross, order_id: 20_001,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_852);
        assert_eq!(api.set_mark_price(ETH_SYM, 23_660), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, BTC_SYM).map(|p| p.open_volume), Some(1));
        assert_eq!(api.user_position(UID_1, ETH_SYM).map(|p| p.open_volume), Some(1));
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_852);
        // Java 黄金值：补 12 后二次预警的 ETH SHORT 强平价/保证金率（ITExtraMarginIntegration:918-919）。
        assert_eq!(pos_view(&api, UID_1, ETH_SYM).liquidation_price, 23_689, "cross LP 二警");
        assert_eq!(pos_view(&api, UID_1, ETH_SYM).margin_ratio_scale_k, 614, "cross margin ratio 二警");
        assert!(api.total_balance().is_global_zero());
    }

    // 10. testIsolatedMarginLiquidation2 —— 逐仓双 symbol 加过 extraMargin 后价格暴跌 → 两腿均被清算（全平）。
    #[test]
    fn isolated_margin_liquidation_both_legs() {
        let deposit = 10_000i64;

        let mut api = ExchangeApi::new();
        api.add_currency(XBT, 1);
        api.add_currency(QUOTE_ID, 1);
        api.add_currency(ETH_CUR, 1);
        assert_eq!(api.add_futures_symbol(btc_symbol()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(eth_symbol()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(BTC_SYM, 10_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 10_000), CommandResultCode::Success);
        seed_user(&mut api, UID_1, deposit, 1);
        seed_user(&mut api, UID_2, MAX_VALUE, 2);
        seed_user(&mut api, UID_3, MAX_VALUE, 3);

        // UID_1 maker：BTC BID(LONG) + ETH ASK(SHORT)，均 ISOLATED；UID_2 taker 对吃。
        assert_eq!(place(&mut api, 1005, UID_1, BTC_SYM, 10_000, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1007, UID_1, ETH_SYM, 15_000, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1006, UID_2, BTC_SYM, 10_000, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1008, UID_2, ETH_SYM, 15_000, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);

        // 逐仓追加：BTC 900、ETH 2900（价格独立，先于落价加）。account = 9840 - 3800 = 6040。
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1, symbol: BTC_SYM, action: OrderAction::Bid, amount: 900,
                margin_mode: MarginMode::Isolated, order_id: 20_001,
            }),
            CommandResultCode::Success
        );
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1, symbol: ETH_SYM, action: OrderAction::Bid, amount: 2_900,
                margin_mode: MarginMode::Isolated, order_id: 20_002,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, BTC_SYM).unwrap().extra_margin, 900);
        assert_eq!(api.user_position(UID_1, ETH_SYM).unwrap().extra_margin, 2_900);
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 6_040);
        assert!(api.total_balance().is_global_zero());

        // UID_3 承接单（BTC BID@9000 接 LONG 强平卖单、ETH ASK@18000 接 SHORT 强平买单）。
        assert_eq!(place(&mut api, 1011, UID_3, BTC_SYM, 9_000, 1, OrderAction::Bid, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(place(&mut api, 1012, UID_3, ETH_SYM, 18_000, 1, OrderAction::Ask, MarginMode::Isolated), CommandResultCode::Success);

        // 落价：BTC→9000（LONG 亏 1000 > 100+900）、ETH→18000（SHORT 亏 3000 > 150+2900）→ 两腿均被清算。
        api.enable_liquidation();
        assert_eq!(api.set_mark_price(BTC_SYM, 9_000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(ETH_SYM, 18_000), CommandResultCode::Success);
        assert!(api.user_position(UID_1, BTC_SYM).is_none(), "BTC 逐仓腿应被全平");
        assert!(api.user_position(UID_1, ETH_SYM).is_none(), "ETH 逐仓腿应被全平");
        assert!(api.total_balance().is_global_zero());
    }
}
