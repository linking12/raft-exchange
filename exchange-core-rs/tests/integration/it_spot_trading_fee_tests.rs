#[cfg(test)]
mod tests {
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceOrderRequest};

    const BASE: i32 = 3762;
    const QUOTE: i32 = 4141;
    const SYMBOL: i32 = 20001;

    const BASE_SCALE_K: i64 = 1000;
    const QUOTE_SCALE_K: i64 = 10;
    const TAKER_FEE: i64 = 20;
    const MAKER_FEE: i64 = 10;
    const CURRENCY_SCALE_K: i64 = 100_000_000;
    const FEE_SCALE_FACTOR: i64 = 10_000;

    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;
    const UID_3: i64 = 1_440_003;
    const UID_4: i64 = 1_440_004;
    const UID_5: i64 = 1_440_005;

    const BIG_MONEY: i64 = 100_000_000i64 * 100_000_000i64;

    fn test_symbol() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: BASE_SCALE_K,
            quote_scale_k: QUOTE_SCALE_K,
            taker_fee: TAKER_FEE,
            maker_fee: MAKER_FEE,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    fn new_api() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, CURRENCY_SCALE_K);
        api.add_currency(QUOTE, CURRENCY_SCALE_K);
        assert_eq!(api.add_symbol(test_symbol()), CommandResultCode::Success);
        api
    }

    fn create_user_with_money(api: &mut ExchangeApi, uid: i64, currency: i32, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, currency, amount, txid), CommandResultCode::Success);
    }

    fn bid(order_id: i64, uid: i64, price: i64, reserve: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYMBOL, price, size, reserve_bid_price: reserve, action: OrderAction::Bid, order_type: ot }
    }
    fn ask(order_id: i64, uid: i64, price: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYMBOL, price, size, reserve_bid_price: 0, action: OrderAction::Ask, order_type: ot }
    }

    fn fee_pool_for(matched_volume: i64) -> i64 {
        matched_volume * (TAKER_FEE + MAKER_FEE) * FEE_SCALE_FACTOR
    }

    fn conserved(api: &ExchangeApi, cur: i32) -> i64 {
        api.ups().users.values().map(|p| p.account(cur)).sum::<i64>() + api.adjustments(cur) + api.fees(cur)
    }

    #[test]
    fn gtc_maker_taker_fee_calculation() {
        let mut api = new_api();
        let size = 100;
        let price = 15_000;
        create_user_with_money(&mut api, UID_1, QUOTE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, BASE, BIG_MONEY, 2);

        assert_eq!(api.place_order(bid(1001, UID_1, price, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(1002, UID_2, price, size, OrderType::Gtc)), CommandResultCode::Success);

        assert_eq!(api.fees(QUOTE), fee_pool_for(size), "GTC maker+taker pooled fee = 100×300000");
        assert_eq!(api.fees(BASE), 0);
        let notional = size * price * FEE_SCALE_FACTOR;
        let maker_fee = size * MAKER_FEE * FEE_SCALE_FACTOR;
        let taker_fee = size * TAKER_FEE * FEE_SCALE_FACTOR;
        assert_eq!(BIG_MONEY - api.user_account(UID_1, QUOTE), notional + maker_fee, "maker(BID) spend = notional + maker fee");
        assert_eq!(api.user_account(UID_2, QUOTE), notional - taker_fee, "taker(ASK) receipt = notional - taker fee");
        assert_eq!(conserved(&api, BASE), 0);
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    #[test]
    fn ioc_taker_fee_calculation() {
        let mut api = new_api();
        let size = 50;
        let price = 12_000;
        create_user_with_money(&mut api, UID_1, QUOTE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, BASE, BIG_MONEY, 2);

        assert_eq!(api.place_order(bid(2001, UID_1, price, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(2002, UID_2, price, size, OrderType::Ioc)), CommandResultCode::Success);

        assert_eq!(api.fees(QUOTE), fee_pool_for(size));
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    #[test]
    fn fok_budget_taker_fee_calculation() {
        let mut api = new_api();
        let size = 75;
        let price = 14_000;
        let budget = size * price;
        create_user_with_money(&mut api, UID_1, BASE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, QUOTE, BIG_MONEY, 2);

        assert_eq!(api.place_order(ask(3001, UID_1, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(3002, UID_2, budget, budget, size, OrderType::FokBudget)), CommandResultCode::Success);

        assert_eq!(api.fees(QUOTE), fee_pool_for(size));
        assert_eq!(conserved(&api, BASE), 0);
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    #[test]
    fn ioc_budget_full_fill_taker_fee_calculation() {
        let mut api = new_api();
        let size = 60;
        let price = 13_500;
        let budget = size * price;
        create_user_with_money(&mut api, UID_1, BASE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, QUOTE, BIG_MONEY, 2);

        assert_eq!(api.place_order(ask(3101, UID_1, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(3102, UID_2, budget, budget, size, OrderType::IocBudget)), CommandResultCode::Success);

        let l2 = api.request_l2(SYMBOL, 10);
        assert!(l2.ask_prices.is_empty() && l2.bid_prices.is_empty(), "order book empty after full fill");
        assert_eq!(api.fees(QUOTE), fee_pool_for(size));
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    #[test]
    fn ioc_budget_partial_fill_taker_fee_calculation() {
        let mut api = new_api();
        let requested_size = 100;
        let filled_size = 60;
        let price = 14_000;
        let budget = filled_size * price;
        create_user_with_money(&mut api, UID_1, BASE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, QUOTE, BIG_MONEY, 2);

        assert_eq!(api.place_order(ask(3201, UID_1, price, requested_size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(3202, UID_2, budget, budget, requested_size, OrderType::IocBudget)), CommandResultCode::Success);

        assert_eq!(api.fees(QUOTE), fee_pool_for(filled_size), "fee should only be based on the filled amount of 60");
        assert_ne!(api.fees(QUOTE), fee_pool_for(requested_size), "must not be charged on the requested amount of 100");
        assert!(api.fees(QUOTE) > 0);
        let l2 = api.request_l2(SYMBOL, 10);
        assert_eq!(l2.ask_prices, vec![price]);
        assert_eq!(l2.ask_volumes, vec![requested_size - filled_size]);
        assert_eq!(conserved(&api, BASE), 0);
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    #[test]
    fn ioc_budget_full_reject_global_balance_reconciliation() {
        let mut api = new_api();
        let requested_size = 50;
        let price = 14_000;
        let budget = 1;
        create_user_with_money(&mut api, UID_1, BASE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, QUOTE, BIG_MONEY, 2);

        assert_eq!(api.place_order(ask(3301, UID_1, price, requested_size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(3302, UID_2, budget, budget, requested_size, OrderType::IocBudget)), CommandResultCode::Success);

        assert_eq!(api.fees(QUOTE), 0, "full reject should have 0 fee");
        assert_eq!(conserved(&api, BASE), 0);
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    #[test]
    fn maker_one_to_many_fee_calculation() {
        let mut api = new_api();
        let total_size = 300;
        let (s1, s2, s3) = (100, 120, 80);
        let price = 13_000;
        create_user_with_money(&mut api, UID_1, QUOTE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, BASE, BIG_MONEY, 2);
        create_user_with_money(&mut api, UID_3, BASE, BIG_MONEY, 3);
        create_user_with_money(&mut api, UID_4, BASE, BIG_MONEY, 4);

        assert_eq!(api.place_order(bid(4001, UID_1, price, price, total_size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(4002, UID_2, price, s1, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(4003, UID_3, price, s2, OrderType::Ioc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(4004, UID_4, price, s3, OrderType::Gtc)), CommandResultCode::Success);

        assert_eq!(api.fees(QUOTE), fee_pool_for(s1 + s2 + s3));
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    #[test]
    fn taker_one_to_many_fee_calculation() {
        let mut api = new_api();
        let (s1, s2, s3) = (80, 90, 70);
        let total = s1 + s2 + s3;
        let (p1, p2, p3) = (11_000, 11_100, 11_200);
        create_user_with_money(&mut api, UID_1, BASE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, BASE, BIG_MONEY, 2);
        create_user_with_money(&mut api, UID_3, BASE, BIG_MONEY, 3);
        create_user_with_money(&mut api, UID_4, QUOTE, BIG_MONEY, 4);

        assert_eq!(api.place_order(ask(5001, UID_1, p1, s1, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(5002, UID_2, p2, s2, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(5003, UID_3, p3, s3, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(5004, UID_4, p3, p3, total, OrderType::Gtc)), CommandResultCode::Success);

        assert_eq!(api.fees(QUOTE), fee_pool_for(total));
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    #[test]
    fn mixed_order_types_fee_calculation() {
        let mut api = new_api();
        let size = 60;
        let price = 16_000;
        create_user_with_money(&mut api, UID_1, QUOTE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, QUOTE, BIG_MONEY, 2);
        create_user_with_money(&mut api, UID_3, BASE, BIG_MONEY, 3);
        create_user_with_money(&mut api, UID_4, BASE, BIG_MONEY, 4);
        let _ = UID_5;

        assert_eq!(api.place_order(bid(6001, UID_1, price, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(6002, UID_2, price, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(6003, UID_3, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(6004, UID_4, price, size, OrderType::Ioc)), CommandResultCode::Success);

        assert_eq!(api.fees(QUOTE), fee_pool_for(2 * size));
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    #[test]
    fn fee_calculation_parameters() {
        let mut api = new_api();
        let size = 123;
        let price = 17_789;
        create_user_with_money(&mut api, UID_1, QUOTE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, BASE, BIG_MONEY, 2);

        assert_eq!(api.place_order(bid(7001, UID_1, price, price, size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(7002, UID_2, price, size, OrderType::Gtc)), CommandResultCode::Success);

        assert_eq!(api.fees(QUOTE), fee_pool_for(size));
        assert_eq!(api.fees(BASE), 0);
        assert_eq!(conserved(&api, BASE), 0);
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    #[test]
    fn maker_one_to_many_partial_fill_fee_consistency() {
        let mut api = new_api();
        let maker_total = 500;
        let (s1, s2, s3) = (80, 120, 150);
        let price = 14_500;
        create_user_with_money(&mut api, UID_1, QUOTE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, BASE, BIG_MONEY, 2);
        create_user_with_money(&mut api, UID_3, BASE, BIG_MONEY, 3);
        create_user_with_money(&mut api, UID_4, BASE, BIG_MONEY, 4);

        assert_eq!(api.place_order(bid(8001, UID_1, price, price, maker_total, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(8002, UID_2, price, s1, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(8003, UID_3, price, s2, OrderType::Ioc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(8004, UID_4, price, s3, OrderType::Gtc)), CommandResultCode::Success);

        let filled = s1 + s2 + s3;
        assert!(api.fees(QUOTE) > 0);
        assert_eq!(api.fees(QUOTE), fee_pool_for(filled));
        let l2 = api.request_l2(SYMBOL, 10);
        assert_eq!(l2.bid_prices, vec![price]);
        assert_eq!(l2.bid_volumes, vec![maker_total - filled]);
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    #[test]
    fn taker_one_to_many_partial_fill_fee_consistency() {
        let mut api = new_api();
        let (s1, s2, s3) = (60, 80, 90);
        let taker_total = 300;
        let (p1, p2, p3) = (12_000, 12_100, 12_200);
        create_user_with_money(&mut api, UID_1, BASE, BIG_MONEY, 1);
        create_user_with_money(&mut api, UID_2, BASE, BIG_MONEY, 2);
        create_user_with_money(&mut api, UID_3, BASE, BIG_MONEY, 3);
        create_user_with_money(&mut api, UID_4, QUOTE, BIG_MONEY, 4);

        assert_eq!(api.place_order(ask(9001, UID_1, p1, s1, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(9002, UID_2, p2, s2, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(9003, UID_3, p3, s3, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(9004, UID_4, p3, p3, taker_total, OrderType::Gtc)), CommandResultCode::Success);

        let filled = s1 + s2 + s3;
        assert!(api.fees(QUOTE) > 0);
        assert_eq!(api.fees(QUOTE), fee_pool_for(filled));
        let l2 = api.request_l2(SYMBOL, 10);
        assert_eq!(l2.bid_prices, vec![p3]);
        assert_eq!(l2.bid_volumes, vec![taker_total - filled]);
        assert_eq!(conserved(&api, QUOTE), 0);
    }

    #[test]
    fn spot_full_lifecycle_with_deposit_withdraw() {
        for taker_type in [OrderType::Gtc, OrderType::Ioc, OrderType::FokBudget, OrderType::IocBudget] {
            run_spot_full_lifecycle(taker_type);
        }
    }

    fn run_spot_full_lifecycle(taker_type: OrderType) {
        let maker_uid = 7101;
        let taker_uid = 7102;
        let size = 5;
        let price = 12_000;

        let mut api = new_api();
        assert_eq!(api.add_user(maker_uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(maker_uid, BASE, BIG_MONEY, 1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(maker_uid, QUOTE, BIG_MONEY, 2), CommandResultCode::Success);
        assert_eq!(api.add_user(taker_uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(taker_uid, QUOTE, BIG_MONEY, 3), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(taker_uid, BASE, BIG_MONEY, 4), CommandResultCode::Success);

        assert_eq!(conserved(&api, BASE), 0, "[{taker_type:?}] base balanced after deposit");
        assert_eq!(conserved(&api, QUOTE), 0, "[{taker_type:?}] quote balanced after deposit");

        assert_eq!(api.place_order(ask(7201, maker_uid, price, size, OrderType::Gtc)), CommandResultCode::Success);
        let is_budget = matches!(taker_type, OrderType::FokBudget | OrderType::IocBudget);
        let taker_price_field = if is_budget { size * price } else { price };
        assert_eq!(
            api.place_order(bid(7202, taker_uid, taker_price_field, taker_price_field, size, taker_type)),
            CommandResultCode::Success
        );
        assert_eq!(conserved(&api, BASE), 0, "[{taker_type:?}] base balanced after matching");
        assert_eq!(conserved(&api, QUOTE), 0, "[{taker_type:?}] quote balanced after matching");

        for (uid, txbase, txquote) in [(maker_uid, 10, 11), (taker_uid, 12, 13)] {
            let b = api.user_account(uid, BASE);
            let q = api.user_account(uid, QUOTE);
            if b != 0 {
                assert_eq!(api.balance_adjustment(uid, BASE, -b, txbase), CommandResultCode::Success);
            }
            if q != 0 {
                assert_eq!(api.balance_adjustment(uid, QUOTE, -q, txquote), CommandResultCode::Success);
            }
        }

        for uid in [maker_uid, taker_uid] {
            assert_eq!(api.user_account(uid, BASE), 0, "[{taker_type:?}] base zeroed after withdrawal");
            assert_eq!(api.user_account(uid, QUOTE), 0, "[{taker_type:?}] quote zeroed after withdrawal");
        }

        assert_eq!(api.adjustments(QUOTE) + api.fees(QUOTE), 0, "[{taker_type:?}] quote adjustments+fees==0");
        assert_eq!(api.adjustments(BASE) + api.fees(BASE), 0, "[{taker_type:?}] base adjustments+fees==0");
        assert_eq!(conserved(&api, BASE), 0);
        assert_eq!(conserved(&api, QUOTE), 0);

        assert_eq!(api.fees(QUOTE), fee_pool_for(size), "[{taker_type:?}] quote fee pool");
    }
}
