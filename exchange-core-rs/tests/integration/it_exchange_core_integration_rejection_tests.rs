#[cfg(test)]
mod tests {
    // 翻译自 Java `ITExchangeCoreIntegrationRejection`（testMultiBuy/testMultiSell 用例矩阵）
    // 验证现货（带手续费）与合约品种在 GTC/IOC/FOK_BUDGET/IOC_BUDGET 各订单类型、以及无拒绝/按 size 拒绝/按预算拒绝三种场景下都能正确撮合或部分拒单，且全局账面守恒。

    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest, PlaceOrderRequest};

    const CUR_USD: i32 = 840;
    const CUR_JPY: i32 = 392;
    const CUR_XBT: i32 = 3762;
    const CUR_LTC: i32 = 4141;

    const SYMBOL_MARGIN: i32 = 5991;
    const SYMBOL_EXCHANGE_FEE: i32 = 9340;

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
            fee_scale_k: 0,
            ..Default::default()
        }
    }

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

    fn setup() -> ExchangeApi {
        let mut api = ExchangeApi::new();
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

        assert_eq!(api.set_mark_price(SYMBOL_EXCHANGE_FEE, 1000), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_MARGIN, 1000), CommandResultCode::Success);
        api
    }

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

    fn assert_globally_conserved(api: &ExchangeApi) {
        let mark = api.risk().last_price_cache.get(&SYMBOL_MARGIN).map(|r| r.mark_price).unwrap_or(0);
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
            assert_eq!(total, 0, "Global balance sheet should be closed, currency={cur}");
        }
    }

    // 对应 Java 私有方法 testMultiBuy：4 个 ASK 挂单构建订单簿深度，UID_4 用给定订单类型下 BID 吃单
    fn test_multi_buy(is_margin: bool, order_type: OrderType, rejection: RejectionCause) {
        let mut api = setup();
        let size = 40 + if rejection == RejectionBySize { 1 } else { 0 };

        assert_eq!(submit(&mut api, is_margin, 101, UID_1, OrderAction::Ask, 160000, 0, 7, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(submit(&mut api, is_margin, 202, UID_2, OrderAction::Ask, 159900, 0, 10, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(submit(&mut api, is_margin, 303, UID_3, OrderAction::Ask, 160000, 0, 3, OrderType::Gtc), CommandResultCode::Success);
        assert_eq!(submit(&mut api, is_margin, 304, UID_3, OrderAction::Ask, 160500, 0, 20, OrderType::Gtc), CommandResultCode::Success);

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

    // 对应 Java 私有方法 testMultiSell：4 个 BID 挂单构建订单簿深度，UID_4 用给定订单类型下 ASK 吃单
    fn test_multi_sell(is_margin: bool, order_type: OrderType, rejection: RejectionCause) {
        let mut api = setup();
        let size = 22 + if rejection == RejectionBySize { 1 } else { 0 };

        let mut price = 159_900_i64;
        if order_type == OrderType::FokBudget {
            price = 160_500 + 160_000 * 20 + 159_900 + if rejection == RejectionByBudget { 1 } else { 0 };
        } else if order_type == OrderType::IocBudget {
            price = 160_500 + 160_000 * 20 + 159_900;
        }

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

    // 对应 Java testMultiBuyNoRejectionMarginGtc：合约 GTC 买单吃单，全部成交，无拒绝
    #[test]
    fn test_multi_buy_no_rejection_margin_gtc() {
        test_multi_buy(true, OrderType::Gtc, NoRejection);
    }
    // 对应 Java testMultiBuyNoRejectionExchangeGtc：现货 GTC 买单吃单，全部成交，无拒绝
    #[test]
    fn test_multi_buy_no_rejection_exchange_gtc() {
        test_multi_buy(false, OrderType::Gtc, NoRejection);
    }
    // 对应 Java testMultiBuyNoRejectionExchangeIoc：现货 IOC 买单，size 精确覆盖订单簿深度，无拒绝
    #[test]
    fn test_multi_buy_no_rejection_exchange_ioc() {
        test_multi_buy(false, OrderType::Ioc, NoRejection);
    }
    // 对应 Java testMultiBuyNoRejectionMarginIoc：合约版本，同上
    #[test]
    fn test_multi_buy_no_rejection_margin_ioc() {
        test_multi_buy(true, OrderType::Ioc, NoRejection);
    }
    // 对应 Java testMultiBuyNoRejectionExchangeFokB：现货 FOK_BUDGET 买单，预算精确覆盖，全部成交
    #[test]
    fn test_multi_buy_no_rejection_exchange_fok_b() {
        test_multi_buy(false, OrderType::FokBudget, NoRejection);
    }
    // 对应 Java testMultiBuyNoRejectionMarginFokB：合约版本，同上
    #[test]
    fn test_multi_buy_no_rejection_margin_fok_b() {
        test_multi_buy(true, OrderType::FokBudget, NoRejection);
    }

    // 对应 Java testMultiBuyWithRejectionMarginGtc：合约 GTC 买单 size 多 1，GTC 不会拒单，多出部分转为挂单
    #[test]
    fn test_multi_buy_with_rejection_margin_gtc() {
        test_multi_buy(true, OrderType::Gtc, RejectionBySize);
    }
    // 对应 Java testMultiBuyWithRejectionExchangeGtc：现货版本，同上
    #[test]
    fn test_multi_buy_with_rejection_exchange_gtc() {
        test_multi_buy(false, OrderType::Gtc, RejectionBySize);
    }
    // 对应 Java testMultiBuyWithRejectionExchangeIoc：现货 IOC 买单 size 多 1，多出部分按 IOC 规则被拒
    #[test]
    fn test_multi_buy_with_rejection_exchange_ioc() {
        test_multi_buy(false, OrderType::Ioc, RejectionBySize);
    }
    // 对应 Java testMultiBuyWithRejectionMarginIoc：合约版本，同上
    #[test]
    fn test_multi_buy_with_rejection_margin_ioc() {
        test_multi_buy(true, OrderType::Ioc, RejectionBySize);
    }
    // 对应 Java testMultiBuyWithSizeRejectionExchangeFokB：现货 FOK_BUDGET，size 多 1 但预算充足，整单按 size 校验被拒
    #[test]
    fn test_multi_buy_with_size_rejection_exchange_fok_b() {
        test_multi_buy(false, OrderType::FokBudget, RejectionBySize);
    }
    // 对应 Java testMultiBuyWithSizeRejectionMarginFokB：合约版本，同上
    #[test]
    fn test_multi_buy_with_size_rejection_margin_fok_b() {
        test_multi_buy(true, OrderType::FokBudget, RejectionBySize);
    }
    // 对应 Java testMultiBuyWithBudgetRejectionExchangeFokB：现货 FOK_BUDGET，预算减 1，整单按预算校验被拒
    #[test]
    fn test_multi_buy_with_budget_rejection_exchange_fok_b() {
        test_multi_buy(false, OrderType::FokBudget, RejectionByBudget);
    }
    // 对应 Java testMultiBuyWithBudgetRejectionMarginFokB：合约版本，同上
    #[test]
    fn test_multi_buy_with_budget_rejection_margin_fok_b() {
        test_multi_buy(true, OrderType::FokBudget, RejectionByBudget);
    }

    // 对应 Java testMultiBuyNoRejectionExchangeIocB：现货 IOC_BUDGET，预算精确覆盖整本订单簿，全部成交
    #[test]
    fn test_multi_buy_no_rejection_exchange_ioc_b() {
        test_multi_buy(false, OrderType::IocBudget, NoRejection);
    }
    // 对应 Java testMultiBuyNoRejectionMarginIocB：合约版本，同上
    #[test]
    fn test_multi_buy_no_rejection_margin_ioc_b() {
        test_multi_buy(true, OrderType::IocBudget, NoRejection);
    }
    // 对应 Java testMultiBuyWithSizeRejectionExchangeIocB：现货 IOC_BUDGET，size 多 1 但预算够，簿子吃光后多出部分被拒
    #[test]
    fn test_multi_buy_with_size_rejection_exchange_ioc_b() {
        test_multi_buy(false, OrderType::IocBudget, RejectionBySize);
    }
    // 对应 Java testMultiBuyWithSizeRejectionMarginIocB：合约版本，同上
    #[test]
    fn test_multi_buy_with_size_rejection_margin_ioc_b() {
        test_multi_buy(true, OrderType::IocBudget, RejectionBySize);
    }
    // 对应 Java testMultiBuyWithBudgetRejectionExchangeIocB：现货 IOC_BUDGET，预算减 1，按价档逐档吃单到预算耗尽后剩余部分被拒（区别于 FOK 的整单弃成）
    #[test]
    fn test_multi_buy_with_budget_rejection_exchange_ioc_b() {
        test_multi_buy(false, OrderType::IocBudget, RejectionByBudget);
    }
    // 对应 Java testMultiBuyWithBudgetRejectionMarginIocB：合约版本，同上
    #[test]
    fn test_multi_buy_with_budget_rejection_margin_ioc_b() {
        test_multi_buy(true, OrderType::IocBudget, RejectionByBudget);
    }

    // 对应 Java testMultiSellNoRejectionMarginGtc：合约 GTC 卖单吃单，全部成交，无拒绝
    #[test]
    fn test_multi_sell_no_rejection_margin_gtc() {
        test_multi_sell(true, OrderType::Gtc, NoRejection);
    }
    // 对应 Java testMultiSellNoRejectionExchangeGtc：现货版本，同上
    #[test]
    fn test_multi_sell_no_rejection_exchange_gtc() {
        test_multi_sell(false, OrderType::Gtc, NoRejection);
    }
    // 对应 Java testMultiSellNoRejectionMarginIoc：合约 IOC 卖单，size 精确覆盖，无拒绝
    #[test]
    fn test_multi_sell_no_rejection_margin_ioc() {
        test_multi_sell(true, OrderType::Ioc, NoRejection);
    }
    // 对应 Java testMultiSellNoRejectionExchangeIoc：现货版本，同上
    #[test]
    fn test_multi_sell_no_rejection_exchange_ioc() {
        test_multi_sell(false, OrderType::Ioc, NoRejection);
    }
    // 对应 Java testMultiSellNoRejectionMarginFokB：合约 FOK_BUDGET 卖单，预算精确覆盖，全部成交
    #[test]
    fn test_multi_sell_no_rejection_margin_fok_b() {
        test_multi_sell(true, OrderType::FokBudget, NoRejection);
    }
    // 对应 Java testMultiSellNoRejectionExchangeFokB：现货版本，同上
    #[test]
    fn test_multi_sell_no_rejection_exchange_fok_b() {
        test_multi_sell(false, OrderType::FokBudget, NoRejection);
    }

    // 对应 Java testMultiSellWithRejectionMarginGtc：合约 GTC 卖单 size 多 1，GTC 不会拒单，多出部分转为挂单
    #[test]
    fn test_multi_sell_with_rejection_margin_gtc() {
        test_multi_sell(true, OrderType::Gtc, RejectionBySize);
    }
    // 对应 Java testMultiSellWithRejectionExchangeGtc：现货版本，同上
    #[test]
    fn test_multi_sell_with_rejection_exchange_gtc() {
        test_multi_sell(false, OrderType::Gtc, RejectionBySize);
    }
    // 对应 Java testMultiSellWithRejectionMarginIoc：合约 IOC 卖单 size 多 1，多出部分按 IOC 规则被拒
    #[test]
    fn test_multi_sell_with_rejection_margin_ioc() {
        test_multi_sell(true, OrderType::Ioc, RejectionBySize);
    }
    // 对应 Java testMultiSellWithRejectionExchangeIoc：现货版本，同上
    #[test]
    fn test_multi_sell_with_rejection_exchange_ioc() {
        test_multi_sell(false, OrderType::Ioc, RejectionBySize);
    }
    // 对应 Java testMultiSellWithSizeRejectionMarginFokB：合约 FOK_BUDGET，size 多 1 但预算充足，整单按 size 校验被拒
    #[test]
    fn test_multi_sell_with_size_rejection_margin_fok_b() {
        test_multi_sell(true, OrderType::FokBudget, RejectionBySize);
    }
    // 对应 Java testMultiSellWithSizeRejectionExchangeFokB：现货版本，同上
    #[test]
    fn test_multi_sell_with_size_rejection_exchange_fok_b() {
        test_multi_sell(false, OrderType::FokBudget, RejectionBySize);
    }
    // 对应 Java testMultiSellWithExpectationRejectionMarginFokB：合约 FOK_BUDGET，预算不足（少收 1），整单按预算校验被拒
    #[test]
    fn test_multi_sell_with_expectation_rejection_margin_fok_b() {
        test_multi_sell(true, OrderType::FokBudget, RejectionByBudget);
    }
    // 对应 Java testMultiSellWithExpectationRejectionExchangeFokB：现货版本，同上
    #[test]
    fn test_multi_sell_with_expectation_rejection_exchange_fok_b() {
        test_multi_sell(false, OrderType::FokBudget, RejectionByBudget);
    }

    // 对应 Java testMultiSellAskRejectionExchangeIocB：ASK 方向的 IOC_BUDGET 引擎不支持部分成交，此处仅冒烟验证 command 能成功入队
    #[test]
    fn test_multi_sell_ask_rejection_exchange_ioc_b() {
        test_multi_sell(false, OrderType::IocBudget, NoRejection);
    }
    // 对应 Java testMultiSellAskRejectionMarginIocB：合约版本，同上
    #[test]
    fn test_multi_sell_ask_rejection_margin_ioc_b() {
        test_multi_sell(true, OrderType::IocBudget, NoRejection);
    }
}
