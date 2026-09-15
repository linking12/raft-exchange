//! 翻译自 Java `exchange.core2.tests.integration.ITExchangeCoreIntegrationRejection`。
//!
//! 原 Java 测试是参数化的：两个 `testMultiBuy` / `testMultiSell` 私有方法 × (margin 期货 symbol
//! `SYMBOLSPECFEE_USD_JPY` 或 exchange 现货 symbol `SYMBOLSPECFEE_XBT_LTC`) × (GTC/IOC/FOK_BUDGET/
//! IOC_BUDGET) × (NO_REJECTION / REJECTION_BY_SIZE / REJECTION_BY_BUDGET)，共 36 个 `@Test`。
//!
//! **当前生效的行为断言**（Java 里 trade/reject 事件断言整段被注释掉了，见原文件 `:342-398`、`:434-490`）：
//!   1. 4 个 maker + 1 个 taker 命令全部返回 `CommandResultCode::SUCCESS`（Java `submitCommandSync(.., SUCCESS)`）。
//!   2. 全局账面闭合（Java `isGlobalBalancesAllZero()`）：逐币种 Σaccounts + fees + adjustments +
//!      Σ(仓位 estimate_pnl(mark) + extra_margin) == 0。
//!
//! 字段/常量对拍 Java `TestConstants` 与 `ExchangeTestContainer.initFeeSymbols/initFeeUsers`：
//!   - currency digit=0 → `pow10(0)` = currency_scale_k=1（Java `CoreCurrencySpecification.getCurrencyScaleK`）。
//!   - initFeeUser 逐用户充值 USD 1_000_000 / JPY 10_000_000 / XBT 100_000_000 / LTC 10_000_000_000。
//!   - 期货默认 leverage：Java `builderPlace` 不设 leverage（=未配置），引擎按 leverage=1 兑现，此处显式传 1。

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::margin_mode::MarginMode;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest, PlaceOrderRequest};

    // ------------------------------------------------------------------
    // 常量（对拍 Java TestConstants）
    // ------------------------------------------------------------------
    const CUR_USD: i32 = 840;
    const CUR_JPY: i32 = 392;
    const CUR_XBT: i32 = 3762;
    const CUR_LTC: i32 = 4141;

    const SYMBOL_MARGIN: i32 = 5991; // SYMBOLSPECFEE_USD_JPY（期货）
    const SYMBOL_EXCHANGE_FEE: i32 = 9340; // SYMBOLSPECFEE_XBT_LTC（现货）

    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;
    const UID_3: i64 = 1_440_003;
    const UID_4: i64 = 1_440_004;

    const ALL_CURRENCIES: [i32; 4] = [CUR_USD, CUR_JPY, CUR_XBT, CUR_LTC];

    #[derive(Clone, Copy, PartialEq, Eq)]
    enum RejectionCause {
        NoRejection,
        RejectionBySize,
        RejectionByBudget,
    }
    use RejectionCause::*;

    // ------------------------------------------------------------------
    // symbol specs（对拍 Java TestConstants.SYMBOLSPECFEE_*）
    // ------------------------------------------------------------------

    /// SYMBOLSPECFEE_XBT_LTC：现货，base=XBT/quote=LTC，baseScaleK=1M/quoteScaleK=10K，固定费 taker1900/maker700。
    fn exchange_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL_EXCHANGE_FEE,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: CUR_XBT,
            quote_currency: CUR_LTC,
            base_scale_k: 1_000_000,
            quote_scale_k: 10_000,
            taker_fee: 1900,
            maker_fee: 700,
            fee_scale_k: 0, // 固定费
            ..Default::default()
        }
    }

    /// SYMBOLSPECFEE_USD_JPY：期货，base=USD/quote=JPY，baseScaleK=100K/quoteScaleK=10，
    /// initMargin=1/initMarginScaleK=21，maxLeverage 分档，taker3/maker2（maintenanceMarginScaleK 未配 → 0）。
    fn margin_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL_MARGIN,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: CUR_USD,
            quote_currency: CUR_JPY,
            base_scale_k: 100_000,
            quote_scale_k: 10,
            taker_fee: 3,
            maker_fee: 2,
            fee_scale_k: 0,
            init_margin: 1,
            init_margin_scale_k: 21,
            maintenance_margin: BTreeMap::from([(1000_i64, 5_i64), (100_000, 10)]),
            maintenance_margin_scale_k: 0,
            max_leverage: BTreeMap::from([(2000_i64, 5_i64), (100_000, 10)]),
            ..Default::default()
        }
    }

    // ------------------------------------------------------------------
    // setup：initFeeSymbols + initFeeUsers + initMarkPrice
    // ------------------------------------------------------------------
    fn setup() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        // currency digit=0 → scale_k=1
        for cur in ALL_CURRENCIES {
            api.add_currency(cur, 1);
        }
        assert_eq!(api.add_symbol(exchange_spec()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(margin_spec()), CommandResultCode::Success);

        for uid in [UID_1, UID_2, UID_3, UID_4] {
            assert_eq!(api.add_user(uid), CommandResultCode::Success);
            assert_eq!(api.balance_adjustment(uid, CUR_USD, 10_000_00, 1), CommandResultCode::Success);
            assert_eq!(api.balance_adjustment(uid, CUR_JPY, 10_000_000, 2), CommandResultCode::Success);
            assert_eq!(api.balance_adjustment(uid, CUR_XBT, 1_0000_0000, 3), CommandResultCode::Success);
            assert_eq!(api.balance_adjustment(uid, CUR_LTC, 1000_0000_0000, 4), CommandResultCode::Success);
        }

        // Java initMarkPrice(testSymbol, 1000)：两个 symbol 都喂上 mark 价（期货下单前必须有 mark 价）。
        assert_eq!(api.set_mark_price(SYMBOL_EXCHANGE_FEE, 1000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_MARGIN, 1000), CommandResultCode::Success);
        api
    }

    /// 统一下单：margin → 期货单（leverage=1, Isolated）；exchange → 现货单（BID 带 reserve）。
    #[allow(clippy::too_many_arguments)]
    fn submit(
        api: &mut ExchangeApi,
        is_margin: bool,
        order_id: i64,
        uid: i64,
        action: OrderAction,
        price: i64,
        reserve: i64,
        size: i64,
        ot: OrderType,
    ) -> CommandResultCode {
        if is_margin {
            api.place_futures_order(PlaceFuturesOrderRequest {
                order_id,
                uid,
                symbol: SYMBOL_MARGIN,
                price,
                size,
                action,
                order_type: ot,
                leverage: 1,
                margin_mode: MarginMode::Isolated,
                reduce_only: false,
            })
        } else {
            api.place_order(PlaceOrderRequest {
                order_id,
                uid,
                symbol: SYMBOL_EXCHANGE_FEE,
                price,
                size,
                reserve_bid_price: if action == OrderAction::Bid { reserve } else { 0 },
                action,
                order_type: ot,
            })
        }
    }

    /// 全局守恒（对拍 Java `isGlobalBalancesAllZero`）：逐币种 Σaccounts + fees + adjustments +
    /// Σ(仓位 estimate_pnl(mark) + extra_margin) == 0。现货无仓位，期货多空撮合量相等 → PnL 项净零。
    fn assert_globally_conserved(api: &ExchangeApi) {
        let mark = *api.risk().last_price_cache.get(&SYMBOL_MARGIN).unwrap_or(&0);
        for cur in ALL_CURRENCIES {
            let mut total: i64 = api.ups().users.values().map(|u| u.account(cur)).sum();
            total += api.fees(cur);
            total += api.adjustments(cur);
            for u in api.ups().users.values() {
                for p in u.positions.values() {
                    if p.currency == cur {
                        total += p.estimate_pnl(mark) + p.extra_margin;
                    }
                }
            }
            assert_eq!(total, 0, "全局账面应闭合，currency={cur}");
        }
    }

    // ==================================================================
    // testMultiBuy（对拍 Java `:311-400`）
    // ==================================================================
    fn test_multi_buy(is_margin: bool, order_type: OrderType, rejection: RejectionCause) {
        let mut api = setup();
        let size = 40 + if rejection == RejectionBySize { 1 } else { 0 };

        // 4 个 maker ASK（Java :322-325）。
        assert_eq!(submit(&mut api, is_margin, 101, UID_1, OrderAction::Ask, 160000, 0, 7, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(submit(&mut api, is_margin, 202, UID_2, OrderAction::Ask, 159900, 0, 10, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(submit(&mut api, is_margin, 303, UID_3, OrderAction::Ask, 160000, 0, 3, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(submit(&mut api, is_margin, 304, UID_3, OrderAction::Ask, 160500, 0, 20, OrderType::Gtc), CommandResultCode::Success);

        // taker BID：GTC/IOC 用限价 160500；FOK_BUDGET/IOC_BUDGET 用总预算（Java :328-332）。
        let mut price = 160500_i64;
        if order_type == OrderType::FokBudget || order_type == OrderType::IocBudget {
            price = 160000 * 7 + 159900 * 10 + 160000 * 3 + 160500 * 20
                + if rejection == RejectionByBudget { -1 } else { 0 };
        }
        assert_eq!(
            submit(&mut api, is_margin, 405, UID_4, OrderAction::Bid, price, price, size, order_type),
            CommandResultCode::Success
        );

        assert_globally_conserved(&api);
    }

    // ==================================================================
    // testMultiSell（对拍 Java `:402-492`）
    // ==================================================================
    fn test_multi_sell(is_margin: bool, order_type: OrderType, rejection: RejectionCause) {
        let mut api = setup();
        let size = 22 + if rejection == RejectionBySize { 1 } else { 0 };

        // taker ASK 价格：GTC/IOC 用 159900；FOK_BUDGET/IOC_BUDGET 用 expectation（Java :413-419）。
        let mut price = 159_900_i64;
        if order_type == OrderType::FokBudget {
            price = 160_500 + 160_000 * 20 + 159_900 + if rejection == RejectionByBudget { 1 } else { 0 };
        } else if order_type == OrderType::IocBudget {
            // ASK IOC_BUDGET 无论 expectation 都整单 reject；给个有效值让 RiskEngine 预占通过。
            price = 160_500 + 160_000 * 20 + 159_900;
        }

        // 4 个 maker BID（现货 reserve=166000；Java :421-424）。
        assert_eq!(submit(&mut api, is_margin, 101, UID_1, OrderAction::Bid, 160_000, 166_000, 12, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(submit(&mut api, is_margin, 202, UID_2, OrderAction::Bid, 159_900, 166_000, 1, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(submit(&mut api, is_margin, 303, UID_3, OrderAction::Bid, 160_000, 166_000, 8, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(submit(&mut api, is_margin, 304, UID_3, OrderAction::Bid, 160_500, 166_000, 1, OrderType::Gtc), CommandResultCode::Success);

        assert_eq!(
            submit(&mut api, is_margin, 405, UID_4, OrderAction::Ask, price, 0, size, order_type),
            CommandResultCode::Success
        );

        assert_globally_conserved(&api);
    }

    // ==================================================================
    // -------------------------- buy no rejection --------------------------
    // ==================================================================
    #[test]
    fn test_multi_buy_no_rejection_margin_gtc() {
        test_multi_buy(true, OrderType::Gtc, NoRejection);
    }
    #[test]
    fn test_multi_buy_no_rejection_exchange_gtc() {
        test_multi_buy(false, OrderType::Gtc, NoRejection);
    }
    #[test]
    fn test_multi_buy_no_rejection_exchange_ioc() {
        test_multi_buy(false, OrderType::Ioc, NoRejection);
    }
    #[test]
    fn test_multi_buy_no_rejection_margin_ioc() {
        test_multi_buy(true, OrderType::Ioc, NoRejection);
    }
    #[test]
    fn test_multi_buy_no_rejection_exchange_fok_b() {
        test_multi_buy(false, OrderType::FokBudget, NoRejection);
    }
    #[test]
    fn test_multi_buy_no_rejection_margin_fok_b() {
        test_multi_buy(true, OrderType::FokBudget, NoRejection);
    }

    // -------------------------- buy with rejection --------------------------
    #[test]
    fn test_multi_buy_with_rejection_margin_gtc() {
        test_multi_buy(true, OrderType::Gtc, RejectionBySize);
    }
    #[test]
    fn test_multi_buy_with_rejection_exchange_gtc() {
        test_multi_buy(false, OrderType::Gtc, RejectionBySize);
    }
    #[test]
    fn test_multi_buy_with_rejection_exchange_ioc() {
        test_multi_buy(false, OrderType::Ioc, RejectionBySize);
    }
    #[test]
    fn test_multi_buy_with_rejection_margin_ioc() {
        test_multi_buy(true, OrderType::Ioc, RejectionBySize);
    }
    #[test]
    fn test_multi_buy_with_size_rejection_exchange_fok_b() {
        test_multi_buy(false, OrderType::FokBudget, RejectionBySize);
    }
    #[test]
    fn test_multi_buy_with_size_rejection_margin_fok_b() {
        test_multi_buy(true, OrderType::FokBudget, RejectionBySize);
    }
    #[test]
    fn test_multi_buy_with_budget_rejection_exchange_fok_b() {
        test_multi_buy(false, OrderType::FokBudget, RejectionByBudget);
    }
    #[test]
    fn test_multi_buy_with_budget_rejection_margin_fok_b() {
        test_multi_buy(true, OrderType::FokBudget, RejectionByBudget);
    }

    // -------------------------- IOC_BUDGET buy --------------------------
    #[test]
    fn test_multi_buy_no_rejection_exchange_ioc_b() {
        test_multi_buy(false, OrderType::IocBudget, NoRejection);
    }
    #[test]
    fn test_multi_buy_no_rejection_margin_ioc_b() {
        test_multi_buy(true, OrderType::IocBudget, NoRejection);
    }
    #[test]
    fn test_multi_buy_with_size_rejection_exchange_ioc_b() {
        test_multi_buy(false, OrderType::IocBudget, RejectionBySize);
    }
    #[test]
    fn test_multi_buy_with_size_rejection_margin_ioc_b() {
        test_multi_buy(true, OrderType::IocBudget, RejectionBySize);
    }
    #[test]
    fn test_multi_buy_with_budget_rejection_exchange_ioc_b() {
        test_multi_buy(false, OrderType::IocBudget, RejectionByBudget);
    }
    #[test]
    fn test_multi_buy_with_budget_rejection_margin_ioc_b() {
        test_multi_buy(true, OrderType::IocBudget, RejectionByBudget);
    }

    // -------------------------- sell no rejection --------------------------
    #[test]
    fn test_multi_sell_no_rejection_margin_gtc() {
        test_multi_sell(true, OrderType::Gtc, NoRejection);
    }
    #[test]
    fn test_multi_sell_no_rejection_exchange_gtc() {
        test_multi_sell(false, OrderType::Gtc, NoRejection);
    }
    #[test]
    fn test_multi_sell_no_rejection_margin_ioc() {
        test_multi_sell(true, OrderType::Ioc, NoRejection);
    }
    #[test]
    fn test_multi_sell_no_rejection_exchange_ioc() {
        test_multi_sell(false, OrderType::Ioc, NoRejection);
    }
    #[test]
    fn test_multi_sell_no_rejection_margin_fok_b() {
        test_multi_sell(true, OrderType::FokBudget, NoRejection);
    }
    #[test]
    fn test_multi_sell_no_rejection_exchange_fok_b() {
        test_multi_sell(false, OrderType::FokBudget, NoRejection);
    }

    // -------------------------- sell with rejection --------------------------
    #[test]
    fn test_multi_sell_with_rejection_margin_gtc() {
        test_multi_sell(true, OrderType::Gtc, RejectionBySize);
    }
    #[test]
    fn test_multi_sell_with_rejection_exchange_gtc() {
        test_multi_sell(false, OrderType::Gtc, RejectionBySize);
    }
    #[test]
    fn test_multi_sell_with_rejection_margin_ioc() {
        test_multi_sell(true, OrderType::Ioc, RejectionBySize);
    }
    #[test]
    fn test_multi_sell_with_rejection_exchange_ioc() {
        test_multi_sell(false, OrderType::Ioc, RejectionBySize);
    }
    #[test]
    fn test_multi_sell_with_size_rejection_margin_fok_b() {
        test_multi_sell(true, OrderType::FokBudget, RejectionBySize);
    }
    #[test]
    fn test_multi_sell_with_size_rejection_exchange_fok_b() {
        test_multi_sell(false, OrderType::FokBudget, RejectionBySize);
    }
    #[test]
    fn test_multi_sell_with_expectation_rejection_margin_fok_b() {
        test_multi_sell(true, OrderType::FokBudget, RejectionByBudget);
    }
    #[test]
    fn test_multi_sell_with_expectation_rejection_exchange_fok_b() {
        test_multi_sell(false, OrderType::FokBudget, RejectionByBudget);
    }

    // -------------------------- sell IOC_BUDGET（always matcher-reject）--------------------------
    #[test]
    fn test_multi_sell_ask_rejection_exchange_ioc_b() {
        test_multi_sell(false, OrderType::IocBudget, NoRejection);
    }
    #[test]
    fn test_multi_sell_ask_rejection_margin_ioc_b() {
        test_multi_sell(true, OrderType::IocBudget, NoRejection);
    }
}
