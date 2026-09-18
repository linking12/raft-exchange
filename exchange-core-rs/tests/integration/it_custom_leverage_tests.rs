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
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest, PlaceOrderRequest};

    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;

    const BNB_ID: i32 = 11;
    const USDT_ID: i32 = 12;

    fn init_symbol_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: 10001,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 100,
            maker_fee: 1,
            taker_fee: 2,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1000,
            max_leverage: BTreeMap::from([(2000, 10), (5000, 20), (10_000, 50)]),
            ..Default::default()
        }
    }

    fn init_symbol_exchange_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: 30001,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: 21,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 100,
            maker_fee: 1,
            taker_fee: 2,
            ..Default::default()
        }
    }

    fn place_fut(
        api: &mut ExchangeApi,
        order_id: i64,
        uid: i64,
        symbol: i32,
        price: i64,
        size: i64,
        action: OrderAction,
        ot: OrderType,
        leverage: i32,
    ) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol,
            price,
            size,
            action,
            order_type: ot,
            leverage,
            margin_mode: MarginMode::Isolated,
            reduce_only: false,
        })
    }

    fn create_user_with_money(api: &mut ExchangeApi, uid: i64, currency: i32, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, currency, amount, txid), CommandResultCode::Success);
    }

    #[test]
    fn test_init_leverage() {
        let spec = CoreSymbolSpecification {
            symbol_id: 10001,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 1_000_000,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            max_leverage: BTreeMap::from([(2000, 5), (5000, 20)]),
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 10_000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 50_000, 20);

        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1000, 10, OrderAction::Bid, OrderType::Gtc, 0),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().leverage, 1);
    }

    #[test]
    fn test_leverage_mismatch() {
        let spec = CoreSymbolSpecification {
            symbol_id: 10001,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 1_000_000,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            max_leverage: BTreeMap::from([(2000, 5), (5000, 20)]),
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 10_000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 50_000, 20);

        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1000, 10, OrderAction::Bid, OrderType::Gtc, 10),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 10002, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 20),
            CommandResultCode::RiskLeverageMismatch
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().pending_buy_size, 10);
    }

    #[test]
    fn test_adjust_leverage() {
        let deposit: i64 = 1_200;
        let spec = init_symbol_spec();

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, deposit, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 12_000, 20);

        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1000, 10, OrderAction::Bid, OrderType::Gtc, 10),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().pending_buy_size, 10);

        assert_eq!(api.leverage_adjustment(UID_1, spec.symbol_id, 11), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().pending_buy_size, 10);

        assert_eq!(api.leverage_adjustment(UID_1, spec.symbol_id, 9), CommandResultCode::RiskNsf);

        assert_eq!(
            place_fut(&mut api, 10002, UID_2, spec.symbol_id, 1000, 10, OrderAction::Ask, OrderType::Gtc, 0),
            CommandResultCode::Success
        );

        let fee = 1000 * 10 * 1 / 100;
        assert_eq!(api.user_account(UID_1, USDT_ID), deposit - fee);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().pending_sell_size, 0);
        assert_eq!(api.user_position(UID_2, spec.symbol_id).unwrap().pending_buy_size, 0);
    }

    #[test]
    fn test_open_position_then_adjust_leverage() {
        let spec = init_symbol_spec();

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 3_500, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 100_000, 20);

        assert_eq!(
            place_fut(&mut api, 30001, UID_1, spec.symbol_id, 1000, 50, OrderAction::Bid, OrderType::Gtc, 20),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 30002, UID_2, spec.symbol_id, 1000, 50, OrderAction::Ask, OrderType::Gtc, 0),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 50);

        assert_eq!(api.leverage_adjustment(UID_1, spec.symbol_id, 51), CommandResultCode::RiskInvalidLeverage);
        assert_eq!(api.leverage_adjustment(UID_1, spec.symbol_id, 50), CommandResultCode::Success);
        assert_eq!(api.leverage_adjustment(UID_1, spec.symbol_id, 15), CommandResultCode::Success);
    }

    #[test]
    fn test_custom_leverage_open_position() {
        let spec = CoreSymbolSpecification {
            symbol_id: 10001,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 1_000_000,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            max_leverage: BTreeMap::from([(2000, 5), (5000, 10)]),
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 10_000, 20);

        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1000, 100, OrderAction::Bid, OrderType::Gtc, 10),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 10002, UID_2, spec.symbol_id, 1000, 10, OrderAction::Ask, OrderType::Ioc, 0),
            CommandResultCode::Success
        );

        assert_eq!(api.user_account(UID_1, USDT_ID) + api.user_account(UID_2, USDT_ID), 20_000);
        assert_eq!(api.fees(USDT_ID), 0);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 10);
        assert_eq!(api.user_position(UID_2, spec.symbol_id).unwrap().open_volume, 10);

        assert_eq!(
            place_fut(&mut api, 10003, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 10),
            CommandResultCode::RiskNsf
        );
    }

    #[test]
    fn test_reject_invalid_leverage() {
        let spec = CoreSymbolSpecification {
            symbol_id: 10002,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 1_000_000,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            max_leverage: BTreeMap::from([(2000, 5), (100_000, 10)]),
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 100_000, 10);

        assert_eq!(
            place_fut(&mut api, 20001, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 100),
            CommandResultCode::RiskInvalidLeverage
        );
    }

    #[test]
    fn test_custom_leverage_withdraw() {
        let spec = CoreSymbolSpecification {
            symbol_id: 10001,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 100,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            max_leverage: BTreeMap::from([(2000, 5), (10_000, 10), (50_000, 50)]),
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 10_000, 20);

        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1000, 100, OrderAction::Bid, OrderType::Gtc, 10),
            CommandResultCode::Success
        );
        {
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.open_volume, 0);
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.pending_buy_size, 100);
            assert_eq!(pos.pending_sell_size, 0);
        }

        assert_eq!(
            api.balance_adjustment(UID_1, USDT_ID, -1, 30),
            CommandResultCode::RiskNsf
        );
        assert_eq!(api.user_account(UID_1, USDT_ID), 10_000);
    }

    #[test]
    fn test_two_leverage_orders() {
        let spec = init_symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 10_000, 20);

        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 10),
            CommandResultCode::Success
        );
        {
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.open_volume, 0);
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.pending_buy_size, 1);
            assert_eq!(pos.pending_sell_size, 0);
        }

        assert_eq!(
            place_fut(&mut api, 10002, UID_1, spec.symbol_id, 1100, 5, OrderAction::Bid, OrderType::Gtc, 20),
            CommandResultCode::RiskLeverageMismatch
        );
        {
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.open_volume, 0);
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.pending_buy_size, 1);
            assert_eq!(pos.pending_sell_size, 0);
        }
    }

    #[test]
    fn test_two_leverage_orders2() {
        let spec = init_symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 10_000, 20);

        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 1),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().pending_buy_size, 1);

        assert_eq!(
            place_fut(&mut api, 10002, UID_1, spec.symbol_id, 1100, 5, OrderAction::Bid, OrderType::Gtc, 50),
            CommandResultCode::RiskLeverageMismatch
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().pending_buy_size, 1);

        assert_eq!(
            place_fut(&mut api, 10003, UID_1, spec.symbol_id, 1200, 1, OrderAction::Bid, OrderType::Gtc, 1),
            CommandResultCode::Success
        );
    }

    #[test]
    fn test_two_leverage_orders_with_same_order_id() {
        let spec = init_symbol_spec();
        let charge: i64 = 10_000;
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, charge, 10);

        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 10),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().pending_buy_size, 1);

        assert_eq!(
            place_fut(&mut api, 10001, UID_1, spec.symbol_id, 1100, 5, OrderAction::Bid, OrderType::Gtc, 20),
            CommandResultCode::RiskLeverageMismatch
        );
        {
            assert_eq!(api.user_account(UID_1, USDT_ID), charge);
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.open_volume, 0);
            assert_eq!(pos.direction, PositionDirection::Long);
            assert_eq!(pos.pending_buy_size, 1);
            assert_eq!(pos.pending_sell_size, 0);
        }
    }

    #[test]
    fn test_place_exchange_while_has_leverage() {
        let fut = init_symbol_spec();
        let spot = init_symbol_exchange_spec();

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        api.add_currency(21, 1);
        assert_eq!(api.add_futures_symbol(fut.clone()), CommandResultCode::Success);
        assert_eq!(api.add_symbol(spot.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(fut.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 2_000, 10);

        assert_eq!(
            place_fut(&mut api, 30000, UID_1, fut.symbol_id, 1000, 50, OrderAction::Bid, OrderType::Gtc, 50),
            CommandResultCode::Success
        );
        assert!(api.user_position(UID_1, fut.symbol_id).is_some());

        assert_eq!(
            api.place_order(PlaceOrderRequest {
                order_id: 30001,
                uid: UID_1,
                symbol: spot.symbol_id,
                price: 1000,
                size: 1,
                reserve_bid_price: 1000,
                action: OrderAction::Bid,
                order_type: OrderType::Gtc,
            }),
            CommandResultCode::RiskNsf
        );
        assert_eq!(api.user_account(UID_1, USDT_ID), 2_000);
        assert!(api.user_position(UID_1, fut.symbol_id).is_some());
    }

    const MAX_VALUE: i64 = 4_000_000;

    #[test]
    fn test_liquidation_triggered_by_high_leverage() {
        let spec = CoreSymbolSpecification {
            symbol_id: 10003,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            fee_scale_k: 1_000_000,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(1000, 5), (100_000, 10)]),
            max_leverage: BTreeMap::from([(2000, 5), (5000, 10), (10_000, 50)]),
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 1_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 100_000, 20);

        assert_eq!(
            place_fut(&mut api, 30001, UID_1, spec.symbol_id, 1000, 50, OrderAction::Bid, OrderType::Gtc, 50),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 30002, UID_2, spec.symbol_id, 1000, 50, OrderAction::Ask, OrderType::Gtc, 0),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 50);

        assert_eq!(
            place_fut(&mut api, 30003, UID_2, spec.symbol_id, 980, 50, OrderAction::Bid, OrderType::Gtc, 0),
            CommandResultCode::Success
        );

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(spec.symbol_id, 980), CommandResultCode::Success);
        assert!(api.user_position(UID_1, spec.symbol_id).is_none(), "50x long position should be fully closed");
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn test_liquidation_of_maintenance_margin() {
        let spec = init_symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 2_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 100_000, 20);

        assert_eq!(
            place_fut(&mut api, 30001, UID_1, spec.symbol_id, 1000, 50, OrderAction::Bid, OrderType::Gtc, 50),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 30002, UID_2, spec.symbol_id, 1000, 50, OrderAction::Ask, OrderType::Gtc, 0),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, USDT_ID), 2_000 - 500);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().direction, PositionDirection::Long);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 50);
        assert_eq!(api.user_account(UID_2, USDT_ID), 100_000 - 1_000);
        assert_eq!(api.user_position(UID_2, spec.symbol_id).unwrap().direction, PositionDirection::Short);
        assert_eq!(api.user_position(UID_2, spec.symbol_id).unwrap().open_volume, 50);

        assert_eq!(
            place_fut(&mut api, 30003, UID_2, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 0),
            CommandResultCode::Success
        );

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(spec.symbol_id, 980), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 49, "maintenance-margin liquidation closes only 1 contract");
        assert_eq!(api.user_position(UID_2, spec.symbol_id).unwrap().open_volume, 49, "counterparty SHORT reduced by 1 accordingly");
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn test_liquidation_send_warn() {
        let spec = init_symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 2_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 100_000, 20);

        assert_eq!(
            place_fut(&mut api, 30001, UID_1, spec.symbol_id, 1000, 50, OrderAction::Bid, OrderType::Gtc, 50),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 30002, UID_2, spec.symbol_id, 1000, 50, OrderAction::Ask, OrderType::Gtc, 0),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 50);

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(spec.symbol_id, 981), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 50, "price 981 does not reach the liquidation threshold, position unchanged");
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn test_liquidation_leverage() {
        let spec = init_symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 50_020, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, MAX_VALUE, 20);

        assert_eq!(
            place_fut(&mut api, 30000, UID_1, spec.symbol_id, 1000, 50, OrderAction::Bid, OrderType::Gtc, 50),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 30001, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 1),
            CommandResultCode::RiskLeverageMismatch
        );
        assert_eq!(
            place_fut(&mut api, 30002, UID_2, spec.symbol_id, 1000, 50, OrderAction::Ask, OrderType::Gtc, 0),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, USDT_ID), 50_020 - 500);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().direction, PositionDirection::Long);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 50);
        assert_eq!(api.user_account(UID_2, USDT_ID), MAX_VALUE - 1_000);
        assert_eq!(api.user_position(UID_2, spec.symbol_id).unwrap().open_volume, 50);

        assert_eq!(
            place_fut(&mut api, 30003, UID_2, spec.symbol_id, 1000, 50, OrderAction::Bid, OrderType::Gtc, 0),
            CommandResultCode::Success
        );

        api.enable_liquidation();
        assert_eq!(api.set_mark_price(spec.symbol_id, 25), CommandResultCode::Success);
        assert!(api.user_position(UID_1, spec.symbol_id).is_none(), "using the latest 50x leverage, price 25 should trigger a full liquidation");
        assert!(api.total_balance().is_global_zero());
    }
}
