#[cfg(test)]

mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::position_direction::PositionDirection;
    use exchange_core_rs::core::common::symbol_position_record::SymbolPositionRecord;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{
        ClosePositionRequest, ExchangeApi, LiquidationScanRequest, MarginAdjustmentRequest,
        PlaceFuturesOrderRequest,
    };
    use exchange_core_rs::core::utils::core_arithmetic_utils::currency_to_size_price_scale;

    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;
    const UID_3: i64 = 1_440_003;
    const UID_4: i64 = 1_440_004;
    const UID_5: i64 = 1_440_005;

    const BNB_ID: i32 = 11;
    const USDT_ID: i32 = 12;

    const SYMBOL_ID: i32 = 10001;
    const BASE_SCALE_K: i64 = 1_000;
    const QUOTE_SCALE_K: i64 = 100_000;

    fn bnb_usdt_spec() -> CoreSymbolSpecification {
        let tier_notional = 10_000 * 100_000_000;
        CoreSymbolSpecification {
            symbol_id: SYMBOL_ID,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            base_scale_k: BASE_SCALE_K,
            quote_currency: USDT_ID,
            quote_scale_k: QUOTE_SCALE_K,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(tier_notional, 5)]),
            maintenance_margin_scale_k: 1000,
            max_leverage: BTreeMap::from([(tier_notional, 75)]),
            ..Default::default()
        }
    }

    fn place_fut(
        api: &mut ExchangeApi,
        order_id: i64,
        uid: i64,
        price: i64,
        size: i64,
        action: OrderAction,
        leverage: i32,
    ) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol: SYMBOL_ID,
            price,
            size,
            action,
            order_type: OrderType::Gtc,
            leverage,
            margin_mode: MarginMode::Isolated,
            reduce_only: false,
        })
    }

    fn setup() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 100_000_000);
        api.add_currency(USDT_ID, 1_000_000);
        assert_eq!(api.add_futures_symbol(bnb_usdt_spec()), CommandResultCode::Success);

        let mark_price = 750 * QUOTE_SCALE_K;
        assert_eq!(api.set_mark_price(SYMBOL_ID, mark_price), CommandResultCode::Success);

        let deposit = 10_000 * 1_000_000;
        for (i, uid) in [UID_1, UID_2, UID_3, UID_4, UID_5].into_iter().enumerate() {
            assert_eq!(api.add_user(uid), CommandResultCode::Success);
            assert_eq!(
                api.balance_adjustment(uid, USDT_ID, deposit, 100 + i as i64),
                CommandResultCode::Success
            );
        }
        api
    }

    #[allow(clippy::too_many_arguments)]
    fn place_on(
        api: &mut ExchangeApi,
        order_id: i64,
        uid: i64,
        symbol: i32,
        price: i64,
        size: i64,
        action: OrderAction,
        order_type: OrderType,
        margin_mode: MarginMode,
        leverage: i32,
    ) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol,
            price,
            size,
            action,
            order_type,
            leverage,
            margin_mode,
            reduce_only: false,
        })
    }

    fn leg_dir(
        api: &ExchangeApi,
        uid: i64,
        symbol: i32,
        dir: PositionDirection,
    ) -> Option<&SymbolPositionRecord> {
        api.ups()
            .get(uid)?
            .positions
            .values()
            .find(|p| p.symbol == symbol && p.direction == dir)
    }

    fn sym_position_count(api: &ExchangeApi, uid: i64, symbol: i32) -> usize {
        api.ups()
            .get(uid)
            .map(|p| p.positions.values().filter(|r| r.symbol == symbol).count())
            .unwrap_or(0)
    }

    fn init_hedge_orders(api: &mut ExchangeApi) {
        assert_eq!(place_on(api, 10001, UID_1, SYMBOL_ID, 75_000_000, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(api, 10002, UID_2, SYMBOL_ID, 75_000_000, 100, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(leg_dir(api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);

        assert_eq!(place_on(api, 10003, UID_1, SYMBOL_ID, 80_000_000, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(api, 10004, UID_3, SYMBOL_ID, 80_000_000, 50, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
    }

    #[test]
    fn test_default_single_direction() {
        let mut api = setup();

        let size = 2 * BASE_SCALE_K;
        let price = 750 * QUOTE_SCALE_K;

        assert_eq!(place_fut(&mut api, 10001, UID_1, price, size, OrderAction::Bid, 10), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 10002, UID_2, price, size, OrderAction::Ask, 10), CommandResultCode::Success);

        {
            let pos = api.user_position(UID_1, SYMBOL_ID).expect("UID_1 should have one LONG position");
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.open_volume, size);
        }

        let size2 = 1 * BASE_SCALE_K;
        let price2 = 780 * QUOTE_SCALE_K;
        assert_eq!(place_fut(&mut api, 10003, UID_1, price2, size2, OrderAction::Ask, 10), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, SYMBOL_ID).expect("should still be the same position record");
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.open_volume, size);
            assert_eq!(pos.pending_sell_size, size2);
        }

        assert_eq!(place_fut(&mut api, 10004, UID_3, price2, size2, OrderAction::Bid, 10), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, SYMBOL_ID).expect("should still hold LONG after the opposing fill");
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.open_volume, size - size2);
            assert_eq!(pos.pending_sell_size, 0);
        }
    }

    #[test]
    fn test_change_position_mode() {
        let mut api = setup();

        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);

        let size = 2 * BASE_SCALE_K;
        let price = 750 * QUOTE_SCALE_K;
        assert_eq!(place_on(&mut api, 10001, UID_1, SYMBOL_ID, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        let size2 = 10 * BASE_SCALE_K;
        let price2 = 800 * QUOTE_SCALE_K;
        assert_eq!(place_on(&mut api, 10002, UID_1, SYMBOL_ID, price2, size2, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 10), CommandResultCode::RiskMarginModeMismatch);
        assert_eq!(place_on(&mut api, 10003, UID_1, SYMBOL_ID, price2, size2, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().pending_buy_size, size);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().pending_sell_size, size2);

        assert_eq!(place_on(&mut api, 10004, UID_3, SYMBOL_ID, price, size / 2, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10005, UID_3, SYMBOL_ID, price2, size2, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);

        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().pending_buy_size, size / 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().pending_sell_size, 0);
    }

    #[test]
    fn test_cannot_change_mode_with_position() {
        let mut api = setup();

        let size = 2 * BASE_SCALE_K;
        let price = 750 * QUOTE_SCALE_K;
        assert_eq!(place_on(&mut api, 10001, UID_1, SYMBOL_ID, price, size, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10002, UID_2, SYMBOL_ID, 75_000_000, 100, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 10), CommandResultCode::Success);

        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::RiskMarginPositionExists);
        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 1);
    }

    fn margin_hedge_symbol_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL_ID,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            base_scale_k: 1,
            quote_currency: USDT_ID,
            quote_scale_k: 1,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(10_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1000,
            max_leverage: BTreeMap::from([(10_000, 75), (100_000, 40)]),
            ..Default::default()
        }
    }

    #[test]
    fn test_margin_hedge_mode() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(margin_hedge_symbol_spec()), CommandResultCode::Success);

        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, USDT_ID, 10_000, 1), CommandResultCode::Success);
        assert_eq!(api.add_user(UID_2), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_2, USDT_ID, 10_000, 2), CommandResultCode::Success);

        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_ID, 650), CommandResultCode::Success);

        assert_eq!(place_on(&mut api, 101, UID_1, SYMBOL_ID, 652, 1, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 102, UID_2, SYMBOL_ID, 652, 1, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 103, UID_1, SYMBOL_ID, 655, 1, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 104, UID_2, SYMBOL_ID, 655, 1, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);

        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 1);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 1);
        assert_eq!(sym_position_count(&api, UID_2, SYMBOL_ID), 0);
    }

    #[test]
    fn test_dual_position_sign() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);

        assert_eq!(place_on(&mut api, 10001, UID_1, SYMBOL_ID, 75_000_000, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10002, UID_1, SYMBOL_ID, 75_000_000, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 11), CommandResultCode::RiskLeverageMismatch);
        assert_eq!(place_on(&mut api, 10003, UID_2, SYMBOL_ID, 75_000_000, 100, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);

        assert_eq!(place_on(&mut api, 10004, UID_1, SYMBOL_ID, 75_000_000, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 11), CommandResultCode::RiskMarginModeMismatch);
        assert_eq!(place_on(&mut api, 10005, UID_1, SYMBOL_ID, 75_000_000, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 11), CommandResultCode::RiskLeverageMismatch);
        assert_eq!(place_on(&mut api, 10006, UID_1, SYMBOL_ID, 75_000_000, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10007, UID_2, SYMBOL_ID, 75_000_000, 50, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);

        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50);
    }

    #[test]
    fn test_close_position() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);
        init_hedge_orders(&mut api);

        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50);

        assert_eq!(
            api.close_position(ClosePositionRequest {
                order_id: 10005,
                uid: UID_1,
                symbol: SYMBOL_ID,
                action: OrderAction::Ask,
                price: 81_000_000,
                size: 30,
                order_type: OrderType::Gtc,
            }),
            CommandResultCode::Success
        );
        assert_eq!(place_on(&mut api, 10006, UID_4, SYMBOL_ID, 81_000_000, 30, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 70);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50);

        assert_eq!(
            api.close_position(ClosePositionRequest {
                order_id: 10007,
                uid: UID_1,
                symbol: SYMBOL_ID,
                action: OrderAction::Bid,
                price: 74_000_000,
                size: 30,
                order_type: OrderType::Gtc,
            }),
            CommandResultCode::Success
        );
        assert_eq!(place_on(&mut api, 10008, UID_5, SYMBOL_ID, 74_000_000, 30, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 70);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 20);
    }

    #[test]
    fn test_hedge_mode_match() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);

        assert_eq!(place_on(&mut api, 10001, UID_1, SYMBOL_ID, 75_000_000, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10003, UID_1, SYMBOL_ID, 80_000_000, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 0);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 0);

        assert_eq!(api.adjust_position_mode(UID_5, true), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10003, UID_5, SYMBOL_ID, 75_000_000, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10004, UID_5, SYMBOL_ID, 80_000_000, 50, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 50);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50);
        assert_eq!(sym_position_count(&api, UID_5, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_5, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 50);
        assert_eq!(leg_dir(&api, UID_5, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50);
    }

    #[test]
    fn test_hedge_open_second_direction_does_not_pollute_free_margin_calc() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);

        assert_eq!(place_on(&mut api, 30001, UID_1, SYMBOL_ID, 75_000_000, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 30002, UID_2, SYMBOL_ID, 75_000_000, 100, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        assert_eq!(place_on(&mut api, 30003, UID_1, SYMBOL_ID, 75_000_000, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 30004, UID_3, SYMBOL_ID, 75_000_000, 50, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50);

        assert!(api.total_balance().is_global_zero(), "Global conservation must hold after opening the second HEDGE direction");
    }

    #[test]
    fn test_hedge_mode_self_match_conservation() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);

        assert_eq!(place_on(&mut api, 20001, UID_1, SYMBOL_ID, 75_000_000, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 20002, UID_1, SYMBOL_ID, 75_000_000, 100, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 100);

        assert!(api.total_balance().is_global_zero(), "Global conservation must hold after a hedge self-match");
    }

    #[test]
    fn test_mixed_funding_rate() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);
        init_hedge_orders(&mut api);

        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50);

        assert_eq!(
            api.submit(OrderCommand {
                command: OrderCommandType::SettleFundingfees,
                symbol: SYMBOL_ID,
                action: Some(OrderAction::Bid),
                price: 1,
                size: 100,
                order_id: 500,
                ..Default::default()
            }),
            CommandResultCode::Success
        );

        assert!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().profit < 0, "LONG profit should decrease");
        assert!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().profit > 0, "SHORT profit should increase");
    }

    fn bnb_usdt_delivery_spec() -> CoreSymbolSpecification {
        let tier_notional = 10_000 * 100_000_000;
        CoreSymbolSpecification {
            symbol_id: 30001,
            symbol_type: SymbolType::FuturesContractDelivery,
            base_currency: BNB_ID,
            base_scale_k: BASE_SCALE_K,
            quote_currency: USDT_ID,
            quote_scale_k: QUOTE_SCALE_K,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(tier_notional, 5)]),
            maintenance_margin_scale_k: 1000,
            max_leverage: BTreeMap::from([(tier_notional, 75)]),
            ..Default::default()
        }
    }

    #[test]
    fn test_settle_pnl() {
        const DELIVERY_ID: i32 = 30001;
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 100_000_000);
        api.add_currency(USDT_ID, 1_000_000);
        assert_eq!(api.add_futures_symbol(bnb_usdt_delivery_spec()), CommandResultCode::Success);

        let mark_price = 750 * QUOTE_SCALE_K;
        assert_eq!(api.set_mark_price(DELIVERY_ID, mark_price), CommandResultCode::Success);

        let deposit = 10_000 * 1_000_000;
        for (i, uid) in [UID_1, UID_2, UID_3].into_iter().enumerate() {
            assert_eq!(api.add_user(uid), CommandResultCode::Success);
            assert_eq!(api.balance_adjustment(uid, USDT_ID, deposit, 100 + i as i64), CommandResultCode::Success);
        }

        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);

        assert_eq!(place_on(&mut api, 10001, UID_1, DELIVERY_ID, 75_000_000, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10002, UID_2, DELIVERY_ID, 75_000_000, 100, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(leg_dir(&api, UID_1, DELIVERY_ID, PositionDirection::Long).unwrap().open_volume, 100);

        assert_eq!(place_on(&mut api, 10003, UID_1, DELIVERY_ID, 80_000_000, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10004, UID_3, DELIVERY_ID, 80_000_000, 50, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        assert_eq!(api.set_mark_price(DELIVERY_ID, 800 * QUOTE_SCALE_K), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT_ID), deposit);
        assert_eq!(sym_position_count(&api, UID_1, DELIVERY_ID), 2);

        assert_eq!(
            api.submit(OrderCommand {
                command: OrderCommandType::SettlePnl,
                symbol: DELIVERY_ID,
                price: mark_price,
                order_id: 600,
                timestamp: 1,
                ..Default::default()
            }),
            CommandResultCode::Success
        );

        assert_eq!(sym_position_count(&api, UID_1, DELIVERY_ID), 0);
        assert_eq!(api.user_account(UID_1, USDT_ID), 10_002_500_000);
    }

    #[test]
    fn test_total_balance() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);
        init_hedge_orders(&mut api);
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn test_add_extra_margin_to_dual_position() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);
        init_hedge_orders(&mut api);

        let base_deposit = 10_000 * 1_000_000;
        assert_eq!(api.user_account(UID_1, USDT_ID), base_deposit);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().extra_margin, 0);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().extra_margin, 0);

        let deposit_long = 100 * 1_000_000;
        let scaled_long = currency_to_size_price_scale(deposit_long, BASE_SCALE_K, QUOTE_SCALE_K, 1_000_000);
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1,
                symbol: SYMBOL_ID,
                action: OrderAction::Bid,
                amount: deposit_long,
                margin_mode: MarginMode::Isolated,
                order_id: 700,
            }),
            CommandResultCode::Success
        );

        let deposit_short = 200 * 1_000_000;
        let scaled_short = currency_to_size_price_scale(deposit_short, BASE_SCALE_K, QUOTE_SCALE_K, 1_000_000);
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1,
                symbol: SYMBOL_ID,
                action: OrderAction::Ask,
                amount: deposit_short,
                margin_mode: MarginMode::Isolated,
                order_id: 701,
            }),
            CommandResultCode::Success
        );

        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().extra_margin, scaled_long);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().extra_margin, scaled_short);
        assert_eq!(api.user_account(UID_1, USDT_ID), base_deposit - deposit_long - deposit_short);

        let deposit_cross = 500 * 1_000_000;
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: UID_1,
                symbol: USDT_ID,
                action: OrderAction::Bid,
                amount: deposit_cross,
                margin_mode: MarginMode::Cross,
                order_id: 702,
            }),
            CommandResultCode::Success
        );

        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().extra_margin, scaled_long);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().extra_margin, scaled_short);
        assert_eq!(api.user_account(UID_1, USDT_ID), base_deposit - deposit_long - deposit_short + deposit_cross);
    }

    #[test]
    fn test_adjust_leverage_dual() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);
        init_hedge_orders(&mut api);

        assert_eq!(api.leverage_adjustment(UID_1, SYMBOL_ID, 20), CommandResultCode::Success);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().leverage, 20);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().leverage, 20);
    }

    #[test]
    fn test_hedge_one_leg_liquidated_other_preserved() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);

        let entry = 750 * QUOTE_SCALE_K;
        assert_eq!(place_on(&mut api, 1, UID_1, SYMBOL_ID, entry, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 50), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 2, UID_2, SYMBOL_ID, entry, 100, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 1), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 3, UID_1, SYMBOL_ID, entry, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 50), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 4, UID_3, SYMBOL_ID, entry, 50, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 1), CommandResultCode::Success);

        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50);
        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2, "both legs should coexist after opening");

        assert_eq!(
            api.submit(OrderCommand { command: OrderCommandType::IfDeposit, symbol: SYMBOL_ID, price: 5_000 * 1_000_000, order_id: 900, ..Default::default() }),
            CommandResultCode::Success
        );

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(SYMBOL_ID, 700 * QUOTE_SCALE_K), CommandResultCode::Success);

        assert!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).is_none(), "LONG leg should be liquidated and closed");
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50, "SHORT profitable leg should be preserved");
        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 1, "only the SHORT leg should remain");
        assert_eq!(leg_dir(&api, UID_2, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 100, "UID_2 counterparty SHORT should survive");
        assert_eq!(leg_dir(&api, UID_3, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 50, "UID_3 counterparty LONG should survive");
        assert!(api.total_balance().is_global_zero(), "global conservation");
    }

    #[test]
    fn test_liquidation_loop2() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);
        assert_eq!(api.adjust_position_mode(UID_2, true), CommandResultCode::Success);

        assert_eq!(place_on(&mut api, 10001, UID_1, SYMBOL_ID, 75_000_000, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10002, UID_2, SYMBOL_ID, 75_000_000, 100, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        assert_eq!(place_on(&mut api, 10003, UID_1, SYMBOL_ID, 80_000_000, 50, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10004, UID_2, SYMBOL_ID, 80_000_000, 50, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 50);
        assert_eq!(leg_dir(&api, UID_2, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 50);
        assert_eq!(leg_dir(&api, UID_2, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 100);

        assert_eq!(api.set_mark_price(SYMBOL_ID, 95_000_000), CommandResultCode::Success);

        assert_eq!(place_on(&mut api, 20001, UID_4, SYMBOL_ID, 80_000_000, 180, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(sym_position_count(&api, UID_4, SYMBOL_ID), 1);
        assert_eq!(leg_dir(&api, UID_4, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 0);

        api.enable_liquidation();
        assert_eq!(api.submit_liquidation_scan(LiquidationScanRequest { scan_slice: 0, slice_count: 1, timestamp: 1 }), CommandResultCode::Success);

        assert_eq!(sym_position_count(&api, UID_4, SYMBOL_ID), 1);
        assert_eq!(leg_dir(&api, UID_4, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 150);

        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 1);
        assert!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).is_some());

        assert_eq!(sym_position_count(&api, UID_2, SYMBOL_ID), 1);
        assert!(leg_dir(&api, UID_2, SYMBOL_ID, PositionDirection::Long).is_some());
    }

    #[test]
    fn test_liquidation_loop3() {
        let mut api = setup();
        assert_eq!(api.adjust_position_mode(UID_1, true), CommandResultCode::Success);

        assert_eq!(place_on(&mut api, 10001, UID_1, SYMBOL_ID, 75_000_000, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10002, UID_2, SYMBOL_ID, 75_000_000, 100, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10003, UID_1, SYMBOL_ID, 75_000_000, 100, OrderAction::Ask, OrderType::Gtc, MarginMode::Cross, 0), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 10004, UID_3, SYMBOL_ID, 75_000_000, 100, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 0), CommandResultCode::Success);

        assert_eq!(sym_position_count(&api, UID_1, SYMBOL_ID), 2);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 100);

        assert_eq!(place_on(&mut api, 20001, UID_4, SYMBOL_ID, 70_000_000, 200, OrderAction::Bid, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);
        assert_eq!(place_on(&mut api, 20002, UID_5, SYMBOL_ID, 80_000_000, 200, OrderAction::Ask, OrderType::Gtc, MarginMode::Isolated, 10), CommandResultCode::Success);

        api.enable_liquidation();

        assert_eq!(api.set_mark_price(SYMBOL_ID, 95_000_000), CommandResultCode::Success);
        assert_eq!(api.submit_liquidation_scan(LiquidationScanRequest { scan_slice: 0, slice_count: 1, timestamp: 1 }), CommandResultCode::Success);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 100);

        assert_eq!(api.set_mark_price(SYMBOL_ID, 55_000_000), CommandResultCode::Success);
        assert_eq!(api.submit_liquidation_scan(LiquidationScanRequest { scan_slice: 0, slice_count: 1, timestamp: 2 }), CommandResultCode::Success);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Long).unwrap().open_volume, 100);
        assert_eq!(leg_dir(&api, UID_1, SYMBOL_ID, PositionDirection::Short).unwrap().open_volume, 100);
    }
}
