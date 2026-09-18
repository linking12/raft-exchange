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

    const BASE: i32 = 1;
    const USD: i32 = 840;

    const SYMBOL_BTC: i32 = 1001;
    const SYMBOL_ETH: i32 = 1002;
    const SYMBOL_BNB: i32 = 1003;

    const MAKER_UID: i64 = 1;
    const TAKER_UID: i64 = 2;
    const USER_MULTI: i64 = 3;

    const MAX_VALUE: i64 = 4_000_000;

    fn mm_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(1_000, 5), (100_000, 10)])
    }
    fn leverage_table() -> BTreeMap<i64, i64> {
        BTreeMap::from([(2_000, 5), (100_000, 10)])
    }

    fn futures_spec(symbol_id: i32) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE,
            quote_currency: USD,
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

    fn new_api() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(USD, 1);
        api
    }

    fn add_futures(api: &mut ExchangeApi, symbol_id: i32, mark: i64) {
        assert_eq!(api.add_futures_symbol(futures_spec(symbol_id)), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(symbol_id, mark), CommandResultCode::Success);
    }

    fn add_user_money(api: &mut ExchangeApi, uid: i64, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, USD, amount, txid), CommandResultCode::Success);
    }

    fn bid(api: &mut ExchangeApi, order_id: i64, uid: i64, size: i64, price: i64, symbol: i32, mode: MarginMode) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id, uid, symbol, price, size,
            action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1, margin_mode: mode, reduce_only: false,
        })
    }

    fn ask(api: &mut ExchangeApi, order_id: i64, uid: i64, size: i64, price: i64, symbol: i32, mode: MarginMode) -> CommandResultCode {
        api.place_futures_order(PlaceFuturesOrderRequest {
            order_id, uid, symbol, price, size,
            action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1, margin_mode: mode, reduce_only: false,
        })
    }

    fn locked_margin(api: &ExchangeApi, uid: i64) -> i64 {
        match api.ups().users.get(&uid) {
            Some(p) => p.positions.values().map(|pos| pos.open_init_margin_sum).sum(),
            None => 0,
        }
    }

    fn balance(api: &ExchangeApi, uid: i64) -> i64 {
        api.user_account(uid, USD)
    }

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
            assert_eq!(total, 0, "futures global conservation broken: currency={cur} total={total}");
        }
    }

    #[test]
    fn basic_locked_margin_calculation() {
        let mut api = new_api();
        add_futures(&mut api, SYMBOL_BTC, 100_000);
        add_user_money(&mut api, TAKER_UID, 10_000, 1);
        add_user_money(&mut api, MAKER_UID, MAX_VALUE, 2);

        assert_eq!(ask(&mut api, 1001, MAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 1002, TAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);

        let pos = api.user_position(TAKER_UID, SYMBOL_BTC).expect("Taker should have a position");
        assert_eq!(pos.open_volume, 1);
        assert_eq!(pos.direction, PositionDirection::Long);
        assert!(pos.open_init_margin_sum > 0, "locked margin should be positive");
        assert_eq!(pos.open_init_margin_sum, 1000);
        assert_conserved(&api);
    }

    #[test]
    fn multi_position_locked_margin() {
        let mut api = new_api();
        add_futures(&mut api, SYMBOL_BTC, 100_000);
        add_futures(&mut api, SYMBOL_ETH, 3_000);
        add_futures(&mut api, SYMBOL_BNB, 300);
        add_user_money(&mut api, USER_MULTI, 100_000, 1);
        add_user_money(&mut api, MAKER_UID, MAX_VALUE, 2);

        assert_eq!(ask(&mut api, 1001, MAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2001, USER_MULTI, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 1002, MAKER_UID, 10, 3_000, SYMBOL_ETH, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2002, USER_MULTI, 10, 3_000, SYMBOL_ETH, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 1003, MAKER_UID, 100, 300, SYMBOL_BNB, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2003, USER_MULTI, 100, 300, SYMBOL_BNB, MarginMode::Isolated), CommandResultCode::Success);

        assert!(api.user_position(USER_MULTI, SYMBOL_BTC).is_some());
        assert!(api.user_position(USER_MULTI, SYMBOL_ETH).is_some());
        assert!(api.user_position(USER_MULTI, SYMBOL_BNB).is_some());
        let initial_locked = locked_margin(&api, USER_MULTI);
        assert_eq!(initial_locked, 1600, "total locked margin across 3 positions should be 1600");

        assert_eq!(ask(&mut api, 1004, MAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2004, USER_MULTI, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);

        let new_locked = locked_margin(&api, USER_MULTI);
        assert!(new_locked > initial_locked, "locked margin should increase after adding to the position");
        assert_eq!(api.user_position(USER_MULTI, SYMBOL_BTC).unwrap().open_volume, 2);
        assert_conserved(&api);
    }

    #[test]
    fn new_position_creation() {
        let mut api = new_api();
        add_futures(&mut api, SYMBOL_BTC, 100_000);
        add_user_money(&mut api, TAKER_UID, 10_000, 1);
        add_user_money(&mut api, MAKER_UID, MAX_VALUE, 2);

        assert!(api.user_position(TAKER_UID, SYMBOL_BTC).is_none(), "should have no position initially");

        assert_eq!(ask(&mut api, 1001, MAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2001, TAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert!(api.user_position(TAKER_UID, SYMBOL_BTC).unwrap().open_init_margin_sum > 0);
        let locked_after_first = locked_margin(&api, TAKER_UID);

        assert_eq!(ask(&mut api, 1002, MAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2002, TAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        let locked_after_second = locked_margin(&api, TAKER_UID);
        assert!(locked_after_second > locked_after_first, "locked margin should keep increasing after adding to the position");
        assert_conserved(&api);
    }

    #[test]
    fn position_fully_close() {
        let mut api = new_api();
        add_futures(&mut api, SYMBOL_BTC, 100_000);
        add_futures(&mut api, SYMBOL_ETH, 3_000);
        add_user_money(&mut api, USER_MULTI, 100_000, 1);
        add_user_money(&mut api, MAKER_UID, MAX_VALUE, 2);

        assert_eq!(ask(&mut api, 1001, MAKER_UID, 2, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2001, USER_MULTI, 2, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 1002, MAKER_UID, 10, 3_000, SYMBOL_ETH, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2002, USER_MULTI, 10, 3_000, SYMBOL_ETH, MarginMode::Isolated), CommandResultCode::Success);
        let locked_with_two = locked_margin(&api, USER_MULTI);

        assert_eq!(bid(&mut api, 1003, MAKER_UID, 2, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 2003, USER_MULTI, 2, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);

        assert!(api.user_position(USER_MULTI, SYMBOL_BTC).is_none(), "BTC position should be fully closed");
        assert_eq!(api.user_position(USER_MULTI, SYMBOL_ETH).unwrap().open_volume, 10, "ETH position should still exist");

        let locked_after = locked_margin(&api, USER_MULTI);
        assert!(locked_after < locked_with_two, "locked margin should decrease after closing one position");
        assert!(locked_after > 0, "ETH position should still occupy locked margin");
        assert_conserved(&api);
    }

    #[test]
    fn position_close_with_extra_margin() {
        let extra_margin = 1000i64;
        let mut api = new_api();
        add_futures(&mut api, SYMBOL_BTC, 100_000);
        add_user_money(&mut api, TAKER_UID, 10_000, 1);
        add_user_money(&mut api, MAKER_UID, MAX_VALUE, 2);

        assert_eq!(ask(&mut api, 1001, MAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2001, TAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);

        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest {
                uid: TAKER_UID, symbol: SYMBOL_BTC, action: OrderAction::Bid,
                amount: extra_margin, margin_mode: MarginMode::Isolated, order_id: 9001,
            }),
            CommandResultCode::Success
        );
        assert!(api.user_position(TAKER_UID, SYMBOL_BTC).unwrap().extra_margin > 0, "extra margin should be added");
        assert_eq!(api.user_position(TAKER_UID, SYMBOL_BTC).unwrap().extra_margin, extra_margin);

        let balance_before_close = balance(&api, TAKER_UID);

        assert_eq!(bid(&mut api, 1002, MAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 2002, TAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert!(api.user_position(TAKER_UID, SYMBOL_BTC).is_none(), "position should be fully closed");

        let balance_after_close = balance(&api, TAKER_UID);
        let expected_close_fee = 20i64;
        assert_eq!(balance_after_close - balance_before_close, extra_margin - expected_close_fee);
        assert_conserved(&api);
    }

    #[test]
    fn multiple_matcher_events() {
        let mut api = new_api();
        add_futures(&mut api, SYMBOL_BTC, 100_000);
        add_futures(&mut api, SYMBOL_ETH, 3_000);
        add_user_money(&mut api, TAKER_UID, 100_000, 1);
        add_user_money(&mut api, MAKER_UID, MAX_VALUE, 2);

        assert_eq!(ask(&mut api, 1001, MAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 1002, MAKER_UID, 1, 100_001, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 1003, MAKER_UID, 1, 100_002, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);

        assert_eq!(ask(&mut api, 1004, MAKER_UID, 10, 3_000, SYMBOL_ETH, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2001, TAKER_UID, 10, 3_000, SYMBOL_ETH, MarginMode::Isolated), CommandResultCode::Success);
        let locked_before = locked_margin(&api, TAKER_UID);

        assert_eq!(bid(&mut api, 2002, TAKER_UID, 3, 100_003, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);

        assert_eq!(api.user_position(TAKER_UID, SYMBOL_BTC).unwrap().open_volume, 3);
        let locked_after = locked_margin(&api, TAKER_UID);
        assert!(locked_after > locked_before, "locked margin should increase after opening a position");
        assert_conserved(&api);
    }

    #[test]
    fn pending_orders_locked_margin() {
        let mut api = new_api();
        add_futures(&mut api, SYMBOL_BTC, 100_000);
        add_user_money(&mut api, TAKER_UID, 10_000, 1);
        add_user_money(&mut api, MAKER_UID, MAX_VALUE, 2);

        assert_eq!(ask(&mut api, 1001, MAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2001, TAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);

        {
            let pos = api.user_position(TAKER_UID, SYMBOL_BTC).expect("should have a position");
            assert_eq!(pos.open_volume, 1);
            assert_eq!(pos.pending_buy_size, 0);
            assert_eq!(pos.pending_sell_size, 0);
        }

        assert_eq!(bid(&mut api, 2002, TAKER_UID, 1, 90_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        {
            let pos = api.user_position(TAKER_UID, SYMBOL_BTC).expect("should have a position");
            assert_eq!(pos.pending_buy_size, 1);
            assert_eq!(pos.pending_buy_avg_price, 90_000);
        }

        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 2002, uid: TAKER_UID, symbol: SYMBOL_BTC }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_position(TAKER_UID, SYMBOL_BTC).unwrap().pending_buy_size, 0);
        assert_conserved(&api);
    }

    #[test]
    fn maker_locked_margin_calculation() {
        let mut api = new_api();
        add_futures(&mut api, SYMBOL_BTC, 100_000);
        add_futures(&mut api, SYMBOL_ETH, 3_000);
        add_user_money(&mut api, MAKER_UID, MAX_VALUE, 1);
        add_user_money(&mut api, TAKER_UID, MAX_VALUE, 2);

        assert_eq!(ask(&mut api, 1001, TAKER_UID, 2, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2001, MAKER_UID, 2, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 1002, TAKER_UID, 20, 3_000, SYMBOL_ETH, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2002, MAKER_UID, 20, 3_000, SYMBOL_ETH, MarginMode::Isolated), CommandResultCode::Success);
        let maker_locked_before = locked_margin(&api, MAKER_UID);

        assert_eq!(ask(&mut api, 2003, MAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 1003, TAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);

        let maker_locked_after = locked_margin(&api, MAKER_UID);
        assert!(maker_locked_after < maker_locked_before, "Maker's locked margin should decrease after reducing the position");
        assert_eq!(api.user_position(MAKER_UID, SYMBOL_ETH).unwrap().open_volume, 20, "Maker's ETH position should be unaffected");
        assert_conserved(&api);
    }

    #[test]
    fn cross_margin_locked_calculation() {
        let mut api = new_api();
        add_futures(&mut api, SYMBOL_BTC, 100_000);
        add_futures(&mut api, SYMBOL_ETH, 3_000);
        add_user_money(&mut api, USER_MULTI, 100_000, 1);
        add_user_money(&mut api, MAKER_UID, MAX_VALUE, 2);

        assert_eq!(ask(&mut api, 1001, MAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2001, USER_MULTI, 1, 100_000, SYMBOL_BTC, MarginMode::Cross), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 1002, MAKER_UID, 10, 3_000, SYMBOL_ETH, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2002, USER_MULTI, 10, 3_000, SYMBOL_ETH, MarginMode::Cross), CommandResultCode::Success);

        let locked = locked_margin(&api, USER_MULTI);
        assert!(locked > 0, "CROSS-margin user should have locked margin");

        assert_eq!(bid(&mut api, 1003, MAKER_UID, 1, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 2003, USER_MULTI, 1, 100_000, SYMBOL_BTC, MarginMode::Cross), CommandResultCode::Success);

        let locked_after = locked_margin(&api, USER_MULTI);
        assert!(locked_after < locked, "locked margin should decrease after closing the position");
        assert_conserved(&api);
    }

    #[test]
    fn performance_improvement_functional() {
        let mut api = new_api();
        add_futures(&mut api, SYMBOL_BTC, 100_000);
        add_futures(&mut api, SYMBOL_ETH, 3_000);
        add_futures(&mut api, SYMBOL_BNB, 300);
        add_user_money(&mut api, USER_MULTI, 10_000_000, 1);
        add_user_money(&mut api, MAKER_UID, MAX_VALUE, 2);

        assert_eq!(ask(&mut api, 1001, MAKER_UID, 10, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2001, USER_MULTI, 10, 100_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 1002, MAKER_UID, 100, 3_000, SYMBOL_ETH, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2002, USER_MULTI, 100, 3_000, SYMBOL_ETH, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(ask(&mut api, 1003, MAKER_UID, 1000, 300, SYMBOL_BNB, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(bid(&mut api, 2003, USER_MULTI, 1000, 300, SYMBOL_BNB, MarginMode::Isolated), CommandResultCode::Success);

        for i in 0..10i64 {
            let order_id = 1010 + i;
            assert_eq!(ask(&mut api, order_id, MAKER_UID, 1, 100_000 + i, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        }

        assert_eq!(bid(&mut api, 2010, USER_MULTI, 10, 200_000, SYMBOL_BTC, MarginMode::Isolated), CommandResultCode::Success);
        assert_eq!(api.user_position(USER_MULTI, SYMBOL_BTC).unwrap().open_volume, 20, "after a large order sweeps 10 price levels, BTC LONG volume should be 20");
        assert_conserved(&api);
    }
}
