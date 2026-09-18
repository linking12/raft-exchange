#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest, PlaceOrderRequest};

    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;

    const BNB_ID: i32 = 11;
    const USDT_ID: i32 = 12;

    fn symbol_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: 10001,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 0,
            taker_fee: 0,
            maintenance_margin: BTreeMap::from([(10_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1000,
            max_leverage: BTreeMap::from([(10_000, 75), (100_000, 40)]),
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
    fn test_submit_fail_when_no_mark_price() {
        let spec = symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);

        assert_eq!(
            place_fut(&mut api, 101, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 0),
            CommandResultCode::RiskMarkpriceNotAvailable
        );
        assert_eq!(api.set_mark_price(spec.symbol_id, 650), CommandResultCode::Success);
        assert_eq!(
            place_fut(&mut api, 101, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 0),
            CommandResultCode::Success
        );
    }

    #[test]
    fn test_submit_pass_when_no_mark_price() {
        let spot = CoreSymbolSpecification {
            symbol_id: 10003,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BNB_ID,
            quote_currency: USDT_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            fee_scale_k: 0,
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_symbol(spot.clone()), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);

        assert_eq!(
            api.place_order(PlaceOrderRequest {
                order_id: 101,
                uid: UID_1,
                symbol: spot.symbol_id,
                price: 1000,
                size: 1,
                reserve_bid_price: 1000,
                action: OrderAction::Bid,
                order_type: OrderType::Gtc,
            }),
            CommandResultCode::Success
        );
    }

    #[test]
    fn test_tiered_leverage() {
        let spec = symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 500_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 500_000, 20);
        assert_eq!(api.set_mark_price(spec.symbol_id, 1000), CommandResultCode::Success);

        assert_eq!(
            place_fut(&mut api, 10000, UID_1, spec.symbol_id, 1000, 100, OrderAction::Bid, OrderType::Gtc, 75),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 10001, UID_2, spec.symbol_id, 1000, 100, OrderAction::Ask, OrderType::Gtc, 10),
            CommandResultCode::Success
        );

        assert_eq!(
            place_fut(&mut api, 10002, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 75),
            CommandResultCode::RiskInvalidLeverage
        );

        assert_eq!(api.leverage_adjustment(UID_1, spec.symbol_id, 40), CommandResultCode::Success);
        assert_eq!(
            place_fut(&mut api, 10003, UID_1, spec.symbol_id, 1000, 1, OrderAction::Bid, OrderType::Gtc, 40),
            CommandResultCode::Success
        );
        assert_eq!(
            place_fut(&mut api, 10004, UID_2, spec.symbol_id, 1000, 1, OrderAction::Ask, OrderType::Gtc, 10),
            CommandResultCode::Success
        );

        let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
        assert_eq!(pos.open_init_margin_sum, 1358);
        assert_eq!(pos.pending_buy_size, 0);
        let r = api.single_user(UID_1, 0);
        let p = r.positions.iter().find(|p| p.symbol == spec.symbol_id).unwrap();
        assert_eq!(p.margin_ratio_scale_k, 375);
    }

    fn pos_view(api: &ExchangeApi, uid: i64, symbol: i32) -> exchange_core_rs::core::reports::PositionView {
        api.single_user(uid, 0)
            .positions
            .into_iter()
            .find(|p| p.symbol == symbol)
            .expect("position report record should exist")
    }

    #[test]
    fn test_mark_price_report_derived_margin() {
        let price = 680i64;
        let size = 10i64;
        let spec = symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 10_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 4_000_000, 20);

        assert_eq!(api.set_mark_price(spec.symbol_id, price), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 101, UID_1, spec.symbol_id, price, size, OrderAction::Bid, OrderType::Gtc, 0), CommandResultCode::Success);

        {
            let p = pos_view(&api, UID_1, spec.symbol_id);
            assert_eq!(p.pending_buy_size, size);
            assert_eq!(p.pending_buy_avg_price, price);
            assert_eq!(p.unrealized_pnl, 0);
            assert_eq!(p.liquidation_price, 0);
            assert_eq!(p.margin_ratio_scale_k, 0);
        }

        assert_eq!(
            api.place_futures_order(PlaceFuturesOrderRequest {
                order_id: 102, uid: UID_2, symbol: spec.symbol_id, price, size: 2,
                action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 0,
                margin_mode: MarginMode::Cross, reduce_only: false,
            }),
            CommandResultCode::Success
        );

        {
            let p = pos_view(&api, UID_1, spec.symbol_id);
            assert_eq!(p.pending_buy_avg_price, 680);
            assert_eq!(p.open_volume, 2);
            assert_eq!(p.pending_buy_size, 8);
            assert_eq!(p.unrealized_pnl, 0);
            assert_eq!(p.liquidation_price, 3);
            assert_eq!(p.margin_ratio_scale_k, 4);
            assert_eq!(p.open_price_sum, 1360);
            assert_eq!(p.open_init_margin_sum, 1360);
        }
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn test_init_margin_and_maintenance_margin() {
        let spec = symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 6_800, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 50_000, 20);

        assert_eq!(api.set_mark_price(spec.symbol_id, 650), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 10001, UID_1, spec.symbol_id, 680, 10, OrderAction::Bid, OrderType::Gtc, 10), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.pending_buy_size, 10);
            assert_eq!(pos.pending_buy_avg_price, 680);
        }

        assert_eq!(place_fut(&mut api, 10002, UID_2, spec.symbol_id, 680, 1, OrderAction::Ask, OrderType::Gtc, 0), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.open_init_margin_sum, 65);
            assert_eq!(pos.pending_buy_size, 9);
            assert_eq!(pos.pending_buy_avg_price, 680);
        }

        assert_eq!(api.set_mark_price(spec.symbol_id, 670), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 10003, UID_2, spec.symbol_id, 680, 9, OrderAction::Ask, OrderType::Gtc, 0), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.open_init_margin_sum, 668);
            assert_eq!(pos.pending_buy_size, 0);
            assert_eq!(pos.pending_buy_avg_price, 0);
        }

        assert_eq!(place_fut(&mut api, 10004, UID_1, spec.symbol_id, 680, 1, OrderAction::Ask, OrderType::Gtc, 10), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 10005, UID_2, spec.symbol_id, 680, 1, OrderAction::Bid, OrderType::Gtc, 0), CommandResultCode::Success);
        {
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.open_init_margin_sum, 602);
            assert_eq!(pos.pending_buy_size, 0);
            assert_eq!(pos.pending_buy_avg_price, 0);
        }

        assert_eq!(api.set_mark_price(spec.symbol_id, 617), CommandResultCode::Success);
        assert_eq!(api.user_position(UID_1, spec.symbol_id).unwrap().open_volume, 9);

        assert_eq!(place_fut(&mut api, 10006, UID_2, spec.symbol_id, 616, 10, OrderAction::Bid, OrderType::Gtc, 0), CommandResultCode::Success);
        api.enable_liquidation();
        assert_eq!(api.set_mark_price(spec.symbol_id, 616), CommandResultCode::Success);
        assert!(api.user_position(UID_1, spec.symbol_id).is_none(), "mark=616 should trigger a full isolated-position close");
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn test_tiered_maintenance_margin() {
        let spec = symbol_spec();
        let mut api = ExchangeApi::new();
        api.add_currency(BNB_ID, 1);
        api.add_currency(USDT_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, USDT_ID, 500_000, 10);
        create_user_with_money(&mut api, UID_2, USDT_ID, 500_000, 20);

        assert_eq!(api.set_mark_price(spec.symbol_id, 650), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 10001, UID_1, spec.symbol_id, 680, 1000, OrderAction::Bid, OrderType::Gtc, 10), CommandResultCode::Success);
        assert_eq!(place_fut(&mut api, 10002, UID_2, spec.symbol_id, 680, 1000, OrderAction::Ask, OrderType::Gtc, 10), CommandResultCode::Success);

        {
            let pos = api.user_position(UID_1, spec.symbol_id).unwrap();
            assert_eq!(pos.open_init_margin_sum, 65_000);
            assert_eq!(pos.pending_buy_size, 0);
            assert_eq!(pos.pending_buy_avg_price, 0);
        }

        assert_eq!(api.set_mark_price(spec.symbol_id, 622), CommandResultCode::Success);
        {
            let p = pos_view(&api, UID_1, spec.symbol_id);
            assert_eq!(p.open_volume, 1000);
            assert_eq!(p.liquidation_price, 620);
            assert_eq!(p.margin_ratio_scale_k, 817);
        }

        assert_eq!(place_fut(&mut api, 10003, UID_2, spec.symbol_id, 620, 1000, OrderAction::Bid, OrderType::Gtc, 10), CommandResultCode::Success);
        api.enable_liquidation();
        assert_eq!(api.set_mark_price(spec.symbol_id, 620), CommandResultCode::Success);
        assert!(api.user_position(UID_1, spec.symbol_id).is_none(), "mark=620 (the liquidation price) should trigger a full close");
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn test_cross_margin_liquidation() {
        const SYMBOL_ID: i32 = 2;
        const QUOTE_ID: i32 = 840;
        let spec = CoreSymbolSpecification {
            symbol_id: SYMBOL_ID,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: 1,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            fee_scale_k: 0,
            maintenance_margin: BTreeMap::from([(1_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1_000,
            max_leverage: BTreeMap::from([(2_000, 5), (100_000, 10)]),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        };

        let mut api = ExchangeApi::new();
        api.add_currency(1, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(spec.clone()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL_ID, 10_000), CommandResultCode::Success);
        create_user_with_money(&mut api, UID_1, QUOTE_ID, 1_000, 10);
        create_user_with_money(&mut api, UID_2, QUOTE_ID, 4_000_000, 20);

        assert_eq!(
            api.place_futures_order(PlaceFuturesOrderRequest {
                order_id: 10000, uid: UID_1, symbol: SYMBOL_ID, price: 10_000, size: 1,
                action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
                margin_mode: MarginMode::Cross, reduce_only: false,
            }),
            CommandResultCode::Success
        );
        assert_eq!(
            api.place_futures_order(PlaceFuturesOrderRequest {
                order_id: 10001, uid: UID_2, symbol: SYMBOL_ID, price: 10_000, size: 1,
                action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
                margin_mode: MarginMode::Cross, reduce_only: false,
            }),
            CommandResultCode::Success
        );

        assert_eq!(pos_view(&api, UID_1, SYMBOL_ID).liquidation_price, 9_055);

        assert_eq!(
            api.place_futures_order(PlaceFuturesOrderRequest {
                order_id: 10002, uid: UID_2, symbol: SYMBOL_ID, price: 9_054, size: 1,
                action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
                margin_mode: MarginMode::Cross, reduce_only: false,
            }),
            CommandResultCode::Success
        );
        api.enable_liquidation();
        assert_eq!(api.set_mark_price(SYMBOL_ID, 9_054), CommandResultCode::Success);
        assert!(api.user_position(UID_1, SYMBOL_ID).is_none(), "mark=9054 should trigger a full cross-margin close");
        assert!(api.total_balance().is_global_zero());
    }
}
