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
        CancelOrderRequest, ExchangeApi, MarginAdjustmentRequest, PlaceFuturesOrderRequest, PlaceOrderRequest,
    };

    const BNB: i32 = 11;
    const USDT: i32 = 12;
    const BTC: i32 = 8;
    const XBT: i32 = 3762;
    const LTC: i32 = 4141;

    const BNB_SCALE: i64 = 100_000_000;
    const USDT_SCALE: i64 = 1_000_000;
    const BTC_SCALE: i64 = 100_000_000;
    const C8_SCALE: i64 = 100_000_000;

    const SYM_FUT: i32 = 10001;
    const SYM_SPOT: i32 = 20001;
    const SYM_BTC_FUT: i32 = 10002;
    const SYM_FEE: i32 = 9340;

    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;

    fn bnb_usdt_fut() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYM_FUT,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BNB,
            quote_currency: USDT,
            base_scale_k: 1_000,
            quote_scale_k: 100_000,
            taker_fee: 0,
            maker_fee: 0,
            maintenance_margin: BTreeMap::from([(10_000 * 100_000_000i64, 5i64)]),
            maintenance_margin_scale_k: 1_000,
            max_leverage: BTreeMap::from([(10_000 * 100_000_000i64, 75i64)]),
            ..Default::default()
        }
    }

    fn bnb_usdt_spot() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYM_SPOT,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BNB,
            quote_currency: USDT,
            base_scale_k: 1_000,
            quote_scale_k: 100_000,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    fn btc_usdt_fut() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYM_BTC_FUT,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BTC,
            quote_currency: USDT,
            base_scale_k: 10_000,
            quote_scale_k: 100_000,
            maker_fee: 1,
            taker_fee: 2,
            maintenance_margin: BTreeMap::from([(10_000 * 1_000_000_000i64, 5i64)]),
            maintenance_margin_scale_k: 1_000,
            max_leverage: BTreeMap::from([(10_000 * 1_000_000_000i64, 10i64)]),
            ..Default::default()
        }
    }

    fn xbt_ltc_fee() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYM_FEE,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: XBT,
            quote_currency: LTC,
            base_scale_k: 1_000_000,
            quote_scale_k: 10_000,
            taker_fee: 1_900,
            maker_fee: 700,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    fn create_user_with_money(api: &mut ExchangeApi, uid: i64, currency: i32, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, currency, amount, txid), CommandResultCode::Success);
    }

    fn spot_bid(order_id: i64, uid: i64, symbol: i32, price: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol, price, size, reserve_bid_price: price, action: OrderAction::Bid, order_type: ot }
    }
    fn spot_ask(order_id: i64, uid: i64, symbol: i32, price: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol, price, size, reserve_bid_price: 0, action: OrderAction::Ask, order_type: ot }
    }

    fn fut(order_id: i64, uid: i64, symbol: i32, price: i64, size: i64, action: OrderAction, ot: OrderType, leverage: i32, mm: MarginMode) -> PlaceFuturesOrderRequest {
        PlaceFuturesOrderRequest { order_id, uid, symbol, price, size, action, order_type: ot, leverage, margin_mode: mm, reduce_only: false }
    }

    fn available(api: &ExchangeApi, uid: i64, cur: i32) -> i64 {
        api.user_account(uid, cur) - api.user_locked(uid, cur)
    }

    fn conserved(api: &ExchangeApi, cur: i32) -> i64 {
        api.ups().users.values().map(|p| p.account(cur)).sum::<i64>() + api.adjustments(cur) + api.fees(cur)
    }

    #[test]
    fn margin_trade_price_scale() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_futures_symbol(bnb_usdt_fut()), CommandResultCode::Success);

        let charge = (750.55 * USDT_SCALE as f64) as i64;
        create_user_with_money(&mut api, UID_1, USDT, charge, 1);
        create_user_with_money(&mut api, UID_2, USDT, charge, 2);
        assert_eq!(api.user_account(UID_1, USDT), charge);

        let mark_price = 750 * 100_000;
        assert_eq!(api.set_mark_price(SYM_FUT, mark_price), CommandResultCode::Success);

        let size = (0.1 * 1_000.0) as i64;
        let price = (753.4 * 100_000.0) as i64;

        assert_eq!(api.place_futures_order(fut(10001, UID_1, SYM_FUT, price, size, OrderAction::Bid, OrderType::Gtc, 10, MarginMode::Isolated)), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(fut(10002, UID_2, SYM_FUT, price, size, OrderAction::Ask, OrderType::Gtc, 10, MarginMode::Isolated)), CommandResultCode::Success);

        let unrealized = (-0.34 * 100_000_000.0) as i64;
        let initial_margin = (0.1 * 750.0 / 10.0 * 100_000_000.0) as i64;
        let open_price_sum = (0.1 * 753.4 * 100_000_000.0) as i64;

        let pos = api.user_position(UID_1, SYM_FUT).expect("position must exist after opening a long");
        assert_eq!(pos.direction, PositionDirection::Long);
        assert_eq!(pos.open_volume, size);
        assert_eq!(pos.open_init_margin_sum, initial_margin);
        assert_eq!(pos.open_price_sum, open_price_sum);
        assert_eq!(pos.estimate_unrealized_profit(mark_price), unrealized);

        assert_eq!(api.user_account(UID_1, USDT), charge);
    }

    #[test]
    fn spot_trade_price_scale() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        let bnb_deposit = 2 * BNB_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, bnb_deposit, 2);

        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_account(UID_1, BNB), 0);
        assert_eq!(api.user_account(UID_2, BNB), bnb_deposit);
        assert_eq!(api.user_account(UID_2, USDT), 0);

        let size = (0.05 * 1_000.0) as i64;
        let price = (745.123 * 100_000.0) as i64;

        assert_eq!(api.place_order(spot_bid(10001, UID_1, SYM_SPOT, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_ask(10002, UID_2, SYM_SPOT, price, size, OrderType::Gtc)), CommandResultCode::Success);

        let trade_amount_currency = 37_256_150i64;
        let bnb_balance = 5_000_000i64;

        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit - trade_amount_currency);
        assert_eq!(api.user_account(UID_1, BNB), bnb_balance);
        assert_eq!(api.user_account(UID_2, BNB), bnb_deposit - bnb_balance);
        assert_eq!(api.user_account(UID_2, USDT), trade_amount_currency);
    }

    #[test]
    fn withdraw_when_margin_exist() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_futures_symbol(bnb_usdt_fut()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM_FUT, 750 * 100_000), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, 2 * BNB_SCALE, 2);

        let size = 1_000;
        let price = 750 * 100_000;
        assert_eq!(api.place_futures_order(fut(10001, UID_1, SYM_FUT, price, size, OrderAction::Bid, OrderType::Gtc, 1, MarginMode::Isolated)), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);

        assert_eq!(api.balance_adjustment(UID_1, USDT, -usdt_deposit, 10), CommandResultCode::RiskNsf);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);

        let extra = 750 * USDT_SCALE - 1;
        assert_eq!(api.balance_adjustment(UID_1, USDT, extra, 11), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, USDT, -usdt_deposit, 12), CommandResultCode::RiskNsf);

        assert_eq!(api.balance_adjustment(UID_1, USDT, 1, 13), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, USDT, -usdt_deposit, 100), CommandResultCode::Success);
    }

    #[test]
    fn place_exchange_when_margin_exist() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(bnb_usdt_fut()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM_FUT, 500 * 100_000), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, 2 * BNB_SCALE, 2);

        let size = 1_000;
        let price = 500 * 100_000;
        assert_eq!(api.place_futures_order(fut(10001, UID_1, SYM_FUT, price, size, OrderAction::Bid, OrderType::Gtc, 1, MarginMode::Isolated)), CommandResultCode::Success);
        assert_eq!(available(&api, UID_1, USDT), usdt_deposit);

        assert_eq!(api.place_order(spot_bid(10002, UID_1, SYM_SPOT, price + 1, size, OrderType::Gtc)), CommandResultCode::RiskNsf);
        assert_eq!(api.place_order(spot_bid(10003, UID_1, SYM_SPOT, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(available(&api, UID_1, USDT), usdt_deposit / 2);
    }

    #[test]
    fn extra_margin_4_cross() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_futures_symbol(bnb_usdt_fut()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM_FUT, 500 * 100_000), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, 2 * BNB_SCALE, 2);

        let price = 500 * 100_000;
        assert_eq!(api.place_futures_order(fut(10001, UID_1, SYM_FUT, price, 2_000, OrderAction::Bid, OrderType::Gtc, 1, MarginMode::Cross)), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);

        let mk_order = |id: i64| fut(id, UID_1, SYM_FUT, price, 1_000, OrderAction::Bid, OrderType::Gtc, 1, MarginMode::Cross);
        assert_eq!(api.place_futures_order(mk_order(10002)), CommandResultCode::RiskNsf);

        let extra = usdt_deposit / 2 - 1;
        assert_eq!(api.margin_adjustment(MarginAdjustmentRequest { uid: UID_1, symbol: USDT, action: OrderAction::Bid, amount: extra, margin_mode: MarginMode::Cross, order_id: 20 }), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit + extra);
        assert_eq!(api.place_futures_order(mk_order(10003)), CommandResultCode::RiskNsf);

        assert_eq!(api.margin_adjustment(MarginAdjustmentRequest { uid: UID_1, symbol: USDT, action: OrderAction::Bid, amount: 1, margin_mode: MarginMode::Cross, order_id: 21 }), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(mk_order(10004)), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit + extra + 1);
    }

    #[test]
    fn extra_margin_4_isolated() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_futures_symbol(bnb_usdt_fut()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM_FUT, 500 * 100_000), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, 2 * BNB_SCALE, 2);

        let price = 500 * 100_000;
        assert_eq!(api.place_futures_order(fut(10001, UID_1, SYM_FUT, price, 2_000, OrderAction::Bid, OrderType::Gtc, 1, MarginMode::Isolated)), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);

        let mk_order = |id: i64| fut(id, UID_1, SYM_FUT, price, 1_000, OrderAction::Bid, OrderType::Gtc, 1, MarginMode::Isolated);
        assert_eq!(api.place_futures_order(mk_order(10002)), CommandResultCode::RiskNsf);

        let extra = usdt_deposit / 2 - 1;
        assert_eq!(api.margin_adjustment(MarginAdjustmentRequest { uid: UID_1, symbol: USDT, action: OrderAction::Bid, amount: extra, margin_mode: MarginMode::Cross, order_id: 20 }), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit + extra);
        assert_eq!(api.place_futures_order(mk_order(10003)), CommandResultCode::RiskNsf);

        assert_eq!(api.margin_adjustment(MarginAdjustmentRequest { uid: UID_1, symbol: USDT, action: OrderAction::Bid, amount: 1, margin_mode: MarginMode::Cross, order_id: 21 }), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(mk_order(10004)), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit + extra + 1);
    }

    #[test]
    fn adjust_leverage() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_futures_symbol(bnb_usdt_fut()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM_FUT, 500 * 100_000), CommandResultCode::Success);

        let usdt_deposit = 100 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);

        let price = 500 * 100_000;
        assert_eq!(api.place_futures_order(fut(10001, UID_1, SYM_FUT, price, 2_000, OrderAction::Bid, OrderType::Gtc, 10, MarginMode::Isolated)), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);

        assert_eq!(api.leverage_adjustment(UID_1, SYM_FUT, 11), CommandResultCode::Success);
        assert_eq!(api.leverage_adjustment(UID_1, SYM_FUT, 9), CommandResultCode::RiskNsf);
    }

    #[test]
    fn withdraw_blocked_by_spot_lock() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);

        let size = 1_000;
        let price = 500 * 100_000;
        assert_eq!(api.place_order(spot_bid(10001, UID_1, SYM_SPOT, price, size, OrderType::Gtc)), CommandResultCode::Success);

        let expected_lock = 500 * USDT_SCALE;
        let free_after_lock = usdt_deposit - expected_lock;
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_locked(UID_1, USDT), expected_lock);
        assert_eq!(available(&api, UID_1, USDT), free_after_lock);

        assert_eq!(api.balance_adjustment(UID_1, USDT, -(free_after_lock + 1), 10), CommandResultCode::RiskNsf);
        assert_eq!(api.balance_adjustment(UID_1, USDT, -free_after_lock, 11), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, USDT), expected_lock);
        assert_eq!(api.user_locked(UID_1, USDT), expected_lock);
        assert_eq!(available(&api, UID_1, USDT), 0);
    }

    #[test]
    fn place_margin_order_blocked_by_spot_lock() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);
        assert_eq!(api.add_futures_symbol(bnb_usdt_fut()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM_FUT, 500 * 100_000), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);

        assert_eq!(api.place_order(spot_bid(10001, UID_1, SYM_SPOT, 500 * 100_000, 1_000, OrderType::Gtc)), CommandResultCode::Success);

        let fut_price = 501 * 100_000;
        assert_eq!(api.place_futures_order(fut(20001, UID_1, SYM_FUT, fut_price, 1_000, OrderAction::Bid, OrderType::Gtc, 1, MarginMode::Cross)), CommandResultCode::RiskNsf);

        assert_eq!(api.balance_adjustment(UID_1, USDT, 2 * USDT_SCALE, 2), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(fut(20002, UID_1, SYM_FUT, fut_price, 1_000, OrderAction::Bid, OrderType::Gtc, 1, MarginMode::Cross)), CommandResultCode::Success);
    }

    #[test]
    fn isolated_margin_adjust_blocked_by_spot_lock() {
        let mut api = ExchangeApi::new();
        api.add_currency(BTC, BTC_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        api.add_currency(BNB, BNB_SCALE);
        assert_eq!(api.add_futures_symbol(btc_usdt_fut()), CommandResultCode::Success);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM_BTC_FUT, 10_000 * 100_000), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, USDT, 100_000_000i64 * USDT_SCALE, 2);

        let open_size = 10_000;
        let open_price = 10_000 * 100_000;
        assert_eq!(api.place_futures_order(fut(30001, UID_2, SYM_BTC_FUT, open_price, open_size, OrderAction::Ask, OrderType::Gtc, 1, MarginMode::Cross)), CommandResultCode::Success);

        assert_eq!(api.place_futures_order(fut(30002, UID_1, SYM_BTC_FUT, open_price, open_size / 10, OrderAction::Bid, OrderType::Gtc, 10, MarginMode::Isolated)), CommandResultCode::Success);

        assert_eq!(api.place_order(spot_bid(30003, UID_1, SYM_SPOT, 500 * 100_000, 1_000, OrderType::Gtc)), CommandResultCode::Success);
        let spot_lock = 500 * USDT_SCALE;
        assert_eq!(api.user_locked(UID_1, USDT), spot_lock);

        let too_much = usdt_deposit - spot_lock + 1;
        assert_eq!(
            api.margin_adjustment(MarginAdjustmentRequest { uid: UID_1, symbol: SYM_BTC_FUT, action: OrderAction::Bid, amount: too_much, margin_mode: MarginMode::Isolated, order_id: 40 }),
            CommandResultCode::RiskNsf
        );
    }

    #[test]
    fn spot_lock_partial_release_on_partial_fill() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, 2 * BNB_SCALE, 2);

        let price = 500 * 100_000;
        assert_eq!(api.place_order(spot_bid(10001, UID_1, SYM_SPOT, price, 1_000, OrderType::Gtc)), CommandResultCode::Success);
        let full_lock = 500 * USDT_SCALE;
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_locked(UID_1, USDT), full_lock);

        assert_eq!(api.place_order(spot_ask(10002, UID_2, SYM_SPOT, price, 400, OrderType::Ioc)), CommandResultCode::Success);

        let matched_quote = 200 * USDT_SCALE;
        let matched_base = (0.4 * BNB_SCALE as f64) as i64;
        let remaining_lock = full_lock - matched_quote;
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit - matched_quote);
        assert_eq!(api.user_locked(UID_1, USDT), remaining_lock);
        assert_eq!(api.user_account(UID_1, BNB), matched_base);
        assert_eq!(available(&api, UID_1, USDT), usdt_deposit - matched_quote - remaining_lock);

        assert_eq!(conserved(&api, USDT), 0);
        assert_eq!(conserved(&api, BNB), 0);
    }

    #[test]
    fn spot_lock_full_release_on_cancel() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);

        let price = 500 * 100_000;
        assert_eq!(api.place_order(spot_bid(10001, UID_1, SYM_SPOT, price, 1_000, OrderType::Gtc)), CommandResultCode::Success);
        let lock = 500 * USDT_SCALE;
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_locked(UID_1, USDT), lock);

        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 10001, uid: UID_1, symbol: SYM_SPOT }), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_locked(UID_1, USDT), 0);
        assert_eq!(available(&api, UID_1, USDT), usdt_deposit);
        assert_eq!(conserved(&api, USDT), 0);
    }

    #[test]
    fn spot_lock_accumulates_across_multiple_orders() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 2_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);

        assert_eq!(api.place_order(spot_bid(10001, UID_1, SYM_SPOT, 500 * 100_000, 1_000, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_bid(10002, UID_1, SYM_SPOT, 400 * 100_000, 500, OrderType::Gtc)), CommandResultCode::Success);

        let total_lock = (500 + 200) * USDT_SCALE;
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_locked(UID_1, USDT), total_lock);
        assert_eq!(available(&api, UID_1, USDT), usdt_deposit - total_lock);

        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 10002, uid: UID_1, symbol: SYM_SPOT }), CommandResultCode::Success);
        let remaining = 500 * USDT_SCALE;
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_locked(UID_1, USDT), remaining);
        assert_eq!(available(&api, UID_1, USDT), usdt_deposit - remaining);
        assert_eq!(conserved(&api, USDT), 0);
    }

    #[test]
    fn fok_budget_full_fill_releases_exchange_locked() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, 5 * BNB_SCALE, 2);

        let size = 1_000;
        let unit_price = 200 * 100_000;
        assert_eq!(api.place_order(spot_ask(20001, UID_2, SYM_SPOT, unit_price, size, OrderType::Gtc)), CommandResultCode::Success);

        let budget = size * unit_price;
        assert_eq!(api.place_order(spot_bid(20002, UID_1, SYM_SPOT, budget, size, OrderType::FokBudget)), CommandResultCode::Success);

        let actual_paid = 200 * USDT_SCALE;
        let actual_base = BNB_SCALE;
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit - actual_paid);
        assert_eq!(api.user_locked(UID_1, USDT), 0);
        assert_eq!(api.user_account(UID_1, BNB), actual_base);
        let l2 = api.request_l2(SYM_SPOT, 10);
        assert!(l2.bid_prices.is_empty(), "no resting order should remain for the taker");
        assert_eq!(conserved(&api, USDT), 0);
        assert_eq!(conserved(&api, BNB), 0);
    }

    #[test]
    fn fok_budget_full_reject_releases_exchange_locked() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, 5 * BNB_SCALE, 2);

        assert_eq!(api.place_order(spot_ask(21001, UID_2, SYM_SPOT, 250 * 100_000, 1_000, OrderType::Gtc)), CommandResultCode::Success);

        let budget = 1_000 * (200 * 100_000);
        assert_eq!(api.place_order(spot_bid(21002, UID_1, SYM_SPOT, budget, 1_000, OrderType::FokBudget)), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_locked(UID_1, USDT), 0);
        assert_eq!(api.user_account(UID_1, BNB), 0);
        assert_eq!(conserved(&api, USDT), 0);
    }

    #[test]
    fn ioc_budget_partial_fill_releases_exchange_locked() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, BNB, 5 * BNB_SCALE, 2);

        let unit_price = 200 * 100_000;
        assert_eq!(api.place_order(spot_ask(22001, UID_2, SYM_SPOT, unit_price, 400, OrderType::Gtc)), CommandResultCode::Success);

        let budget = 1_000 * unit_price;
        assert_eq!(api.place_order(spot_bid(22002, UID_1, SYM_SPOT, budget, 1_000, OrderType::IocBudget)), CommandResultCode::Success);

        let actual_paid = 80 * USDT_SCALE;
        let actual_base = (0.4 * BNB_SCALE as f64) as i64;
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit - actual_paid);
        assert_eq!(api.user_locked(UID_1, USDT), 0, "exchangeLocked fully released after partial fill");
        assert_eq!(api.user_account(UID_1, BNB), actual_base);
        assert_eq!(conserved(&api, USDT), 0);
        assert_eq!(conserved(&api, BNB), 0);
    }

    #[test]
    fn ioc_budget_full_reject_releases_exchange_locked() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);

        let budget = 1_000 * (200 * 100_000);
        assert_eq!(api.place_order(spot_bid(23002, UID_1, SYM_SPOT, budget, 1_000, OrderType::IocBudget)), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_locked(UID_1, USDT), 0);
        assert_eq!(api.user_account(UID_1, BNB), 0);
        assert_eq!(conserved(&api, USDT), 0);
    }

    #[test]
    fn maker_bid_exchange_locked_precision_drift_on_partial_fills() {
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, C8_SCALE);
        api.add_currency(LTC, C8_SCALE);
        assert_eq!(api.add_symbol(xbt_ltc_fee()), CommandResultCode::Success);

        create_user_with_money(&mut api, UID_1, LTC, 100_000, 1);
        create_user_with_money(&mut api, UID_2, XBT, 100_000_000i64 * 100_000_000, 2);

        let price = 1_933;
        assert_eq!(api.place_order(spot_bid(40001, UID_1, SYM_FEE, price, 4, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, LTC), 153, "single-shot lock = floor(15332/100) = 153");

        for i in 0..4 {
            assert_eq!(api.place_order(spot_ask(40100 + i, UID_2, SYM_FEE, price, 1, OrderType::Ioc)), CommandResultCode::Success);
        }

        let l2 = api.request_l2(SYM_FEE, 10);
        assert!(l2.bid_prices.is_empty(), "BID fully filled, no remainder");
        assert_eq!(api.user_locked(UID_1, LTC), 1, "precision drift: 4×floor(3833/100)=152, 1 remains");
        assert_eq!(conserved(&api, LTC), 0);
        assert_eq!(conserved(&api, XBT), 0);
    }

    #[test]
    fn maker_bid_exchange_locked_dust_stays_after_cancel_of_remainder() {
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, C8_SCALE);
        api.add_currency(LTC, C8_SCALE);
        assert_eq!(api.add_symbol(xbt_ltc_fee()), CommandResultCode::Success);

        create_user_with_money(&mut api, UID_1, LTC, 100_000, 1);
        create_user_with_money(&mut api, UID_2, XBT, 100_000_000i64 * 100_000_000, 2);

        let price = 1_933;
        let bid_order_id = 50001;
        assert_eq!(api.place_order(spot_bid(bid_order_id, UID_1, SYM_FEE, price, 4, OrderType::Gtc)), CommandResultCode::Success);

        for i in 0..2 {
            assert_eq!(api.place_order(spot_ask(50100 + i, UID_2, SYM_FEE, price, 1, OrderType::Ioc)), CommandResultCode::Success);
        }

        assert_eq!(api.user_locked(UID_1, LTC), 77, "after partial fill lock = 153 - 38×2 = 77");
        let accounts_before_cancel = 100_000 - 26 * 2;
        assert_eq!(api.user_account(UID_1, LTC), accounts_before_cancel);

        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: bid_order_id, uid: UID_1, symbol: SYM_FEE }), CommandResultCode::Success);

        assert_eq!(api.user_locked(UID_1, LTC), 1);
        assert_eq!(api.user_account(UID_1, LTC), accounts_before_cancel);
        assert_eq!(available(&api, UID_1, LTC), accounts_before_cancel - 1);
        let l2 = api.request_l2(SYM_FEE, 10);
        assert!(l2.bid_prices.is_empty(), "BID cleared by cancel");
        assert_eq!(conserved(&api, LTC), 0);
        assert_eq!(conserved(&api, XBT), 0);
    }

    #[test]
    fn maker_bid_cancel_without_any_fill_fully_releases_exchange_locked() {
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, C8_SCALE);
        api.add_currency(LTC, C8_SCALE);
        assert_eq!(api.add_symbol(xbt_ltc_fee()), CommandResultCode::Success);

        create_user_with_money(&mut api, UID_1, LTC, 100_000, 1);

        let price = 1_933;
        let bid_order_id = 60001;
        assert_eq!(api.place_order(spot_bid(bid_order_id, UID_1, SYM_FEE, price, 4, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, LTC), 153);

        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: bid_order_id, uid: UID_1, symbol: SYM_FEE }), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, LTC), 0, "cancel with no fills zeroes out exactly");
        assert_eq!(api.user_account(UID_1, LTC), 100_000);
        assert_eq!(available(&api, UID_1, LTC), 100_000);
        assert_eq!(conserved(&api, LTC), 0);
    }

    #[test]
    fn futures_fill_does_not_affect_spot_lock() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_futures_symbol(bnb_usdt_fut()), CommandResultCode::Success);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYM_FUT, 500 * 100_000), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);
        create_user_with_money(&mut api, UID_2, USDT, 100_000_000i64 * USDT_SCALE, 2);

        let spot_price = 100 * 100_000;
        assert_eq!(api.place_order(spot_bid(40001, UID_1, SYM_SPOT, spot_price, 1_000, OrderType::Gtc)), CommandResultCode::Success);
        let spot_lock = 100 * USDT_SCALE;
        assert_eq!(api.user_locked(UID_1, USDT), spot_lock);

        let fut_price = 500 * 100_000;
        assert_eq!(api.place_futures_order(fut(40002, UID_2, SYM_FUT, fut_price, 1_000, OrderAction::Ask, OrderType::Gtc, 1, MarginMode::Cross)), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(fut(40003, UID_1, SYM_FUT, fut_price, 1_000, OrderAction::Bid, OrderType::Ioc, 1, MarginMode::Cross)), CommandResultCode::Success);

        assert!(api.user_position(UID_1, SYM_FUT).is_some(), "futures position should be opened");
        assert_eq!(api.user_locked(UID_1, USDT), spot_lock, "spot lock unaffected by futures flow");
        let l2 = api.request_l2(SYM_SPOT, 10);
        assert_eq!(l2.bid_prices, vec![spot_price], "spot order should still be resting");
        assert_eq!(conserved(&api, USDT), 0);
        assert_eq!(conserved(&api, BNB), 0);
    }

    #[test]
    fn suspend_sweeps_dust_to_fees() {
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, C8_SCALE);
        api.add_currency(LTC, C8_SCALE);
        assert_eq!(api.add_symbol(xbt_ltc_fee()), CommandResultCode::Success);

        create_user_with_money(&mut api, UID_1, LTC, 100_000, 1);
        create_user_with_money(&mut api, UID_2, XBT, 100_000_000i64 * 100_000_000, 2);

        let price = 1_933;
        assert_eq!(api.place_order(spot_bid(70001, UID_1, SYM_FEE, price, 4, OrderType::Gtc)), CommandResultCode::Success);
        for i in 0..4 {
            assert_eq!(api.place_order(spot_ask(70100 + i, UID_2, SYM_FEE, price, 1, OrderType::Ioc)), CommandResultCode::Success);
        }
        assert_eq!(api.user_locked(UID_1, LTC), 1);
        assert_eq!(api.user_account(UID_1, LTC), 99_896);
        assert_eq!(api.user_account(UID_1, XBT), 400);

        assert_eq!(api.balance_adjustment(UID_1, LTC, -99_895, 10), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, XBT, -400, 11), CommandResultCode::Success);
        assert_eq!(api.user_account(UID_1, LTC), 1, "LTC dust of 1 remains");
        assert_eq!(api.user_locked(UID_1, LTC), 1, "dust still sits in lock");
        assert_eq!(api.user_account(UID_1, XBT), 0, "XBT fully withdrawn");

        let fees_before = api.fees(LTC);
        assert_eq!(api.suspend_user(UID_1), CommandResultCode::Success);
        assert!(api.ups().get(UID_1).is_none(), "user should be removed after successful SUSPEND");
        assert_eq!(api.fees(LTC), fees_before + 1, "dust of 1 swept into the fees bucket");
        assert!(api.total_balance().is_global_zero(), "global conservation still holds after SUSPEND (including dust sweep)");
    }

    #[test]
    fn suspend_no_sweep_with_real_accounts() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let usdt_deposit = 1_000 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, usdt_deposit, 1);

        assert_eq!(api.place_order(spot_bid(80001, UID_1, SYM_SPOT, 500 * 100_000, 1_000, OrderType::Gtc)), CommandResultCode::Success);
        let spot_lock = 500 * USDT_SCALE;

        assert_eq!(api.suspend_user(UID_1), CommandResultCode::UserMgmtUserNotSuspendableNonEmptyAccounts);
        assert_eq!(api.user_account(UID_1, USDT), usdt_deposit);
        assert_eq!(api.user_locked(UID_1, USDT), spot_lock);
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn suspend_clean_account_no_sweep() {
        let mut api = ExchangeApi::new();
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);

        let fees_before = api.fees(USDT);
        assert_eq!(api.suspend_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.fees(USDT), fees_before, "no dust swept into the fees bucket");
        assert!(api.ups().get(UID_1).is_none(), "user should be removed after SUSPEND on a clean account");
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn cross_liquidation_price_accounts_for_spot_lock() {
        let mut api = ExchangeApi::new();
        api.add_currency(BNB, BNB_SCALE);
        api.add_currency(USDT, USDT_SCALE);
        assert_eq!(api.add_futures_symbol(bnb_usdt_fut()), CommandResultCode::Success);
        assert_eq!(api.add_symbol(bnb_usdt_spot()), CommandResultCode::Success);

        let mark_price = 500 * 100_000;
        assert_eq!(api.set_mark_price(SYM_FUT, mark_price), CommandResultCode::Success);

        let uid1_deposit = 300 * USDT_SCALE;
        create_user_with_money(&mut api, UID_1, USDT, uid1_deposit, 1);
        create_user_with_money(&mut api, UID_2, USDT, 10_000 * USDT_SCALE * 100, 2);

        let fut_size = 1_000;
        let fut_price = mark_price;
        assert_eq!(api.place_futures_order(fut(50001, UID_2, SYM_FUT, fut_price, fut_size, OrderAction::Ask, OrderType::Gtc, 1, MarginMode::Cross)), CommandResultCode::Success);
        assert_eq!(api.place_futures_order(fut(50002, UID_1, SYM_FUT, fut_price, fut_size, OrderAction::Bid, OrderType::Ioc, 10, MarginMode::Cross)), CommandResultCode::Success);

        let liq_no_spot = {
            let report = api.single_user(UID_1, 0);
            assert_eq!(report.exchange_locked.get(&USDT).copied().unwrap_or(0), 0, "no spot order lock");
            let pos = report.positions.iter().find(|p| p.symbol == SYM_FUT).expect("CROSS position should be open");
            assert!(pos.liquidation_price > 0, "liquidation price must be positive");
            pos.liquidation_price
        };

        let spot_lock = 100 * USDT_SCALE;
        assert_eq!(api.place_order(spot_bid(50003, UID_1, SYM_SPOT, 50 * 100_000, 2_000, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, USDT), spot_lock, "spot lock has frozen 100 USDT");

        let liq_with_spot = {
            let report = api.single_user(UID_1, 0);
            assert_eq!(report.exchange_locked.get(&USDT).copied().unwrap_or(0), spot_lock);
            report.positions.iter().find(|p| p.symbol == SYM_FUT).expect("position should still exist").liquidation_price
        };
        assert!(
            liq_with_spot > liq_no_spot,
            "spot order lock should raise the liquidation price: with_spot={liq_with_spot} no_spot={liq_no_spot} (if equal, liquidation_price did not deduct exchangeLocked)"
        );

        assert!(api.total_balance().is_global_zero());
    }
}
