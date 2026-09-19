#[cfg(test)]

mod tests {
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{CancelOrderRequest, ExchangeApi, PlaceOrderRequest};
    use exchange_core_rs::core::utils::core_arithmetic_utils::{
        calculate_amount_bid_taker_fee, calculate_maker_fee, calculate_taker_fee,
        size_price_to_currency_scale, symbol_to_currency_scale,
    };

    const XBT: i32 = 3762;
    const LTC: i32 = 4141;
    const SYM: i32 = 9340;
    const UID_1: i64 = 1_440_001;
    const UID_2: i64 = 1_440_002;

    const BASE_SCALE_K: i64 = 1_000;
    const QUOTE_SCALE_K: i64 = 10;
    const CURRENCY_SCALE_K: i64 = 100_000_000;
    const MAKER_FEE: i64 = 700;
    const TAKER_FEE: i64 = 1_900;
    const FEE_SCALE_K: i64 = 0;

    const PRICE: i64 = 11_500 * QUOTE_SCALE_K;

    fn spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYM,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: XBT,
            quote_currency: LTC,
            base_scale_k: BASE_SCALE_K,
            quote_scale_k: QUOTE_SCALE_K,
            taker_fee: TAKER_FEE,
            maker_fee: MAKER_FEE,
            fee_scale_k: FEE_SCALE_K,
            ..Default::default()
        }
    }

    fn new_api() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(XBT, CURRENCY_SCALE_K);
        api.add_currency(LTC, CURRENCY_SCALE_K);
        assert_eq!(api.add_symbol(spec()), CommandResultCode::Success);
        api
    }

    fn user_money(api: &mut ExchangeApi, uid: i64, cur: i32, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, cur, amount, txid), CommandResultCode::Success);
    }

    fn bid(order_id: i64, uid: i64, price: i64, reserve: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYM, price, size, reserve_bid_price: reserve, action: OrderAction::Bid, order_type: ot }
    }
    fn ask(order_id: i64, uid: i64, price: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYM, price, size, reserve_bid_price: 0, action: OrderAction::Ask, order_type: ot }
    }

    fn fee_pool(filled: i64, price: i64) -> i64 {
        if filled == 0 {
            return 0;
        }
        let maker = size_price_to_currency_scale(
            calculate_maker_fee(filled, price, MAKER_FEE, FEE_SCALE_K),
            BASE_SCALE_K,
            QUOTE_SCALE_K,
            CURRENCY_SCALE_K,
        );
        let taker = size_price_to_currency_scale(
            calculate_taker_fee(filled, price, TAKER_FEE, FEE_SCALE_K),
            BASE_SCALE_K,
            QUOTE_SCALE_K,
            CURRENCY_SCALE_K,
        );
        maker + taker
    }

    fn base_amt(size: i64) -> i64 {
        symbol_to_currency_scale(size, BASE_SCALE_K, CURRENCY_SCALE_K)
    }

    fn available(api: &ExchangeApi, uid: i64, cur: i32) -> i64 {
        api.user_account(uid, cur) - api.user_locked(uid, cur)
    }

    fn conserved(api: &ExchangeApi, cur: i32) -> i64 {
        api.ups().users.values().map(|p| p.account(cur)).sum::<i64>() + api.adjustments(cur) + api.fees(cur)
    }

    fn assert_outcome(
        api: &ExchangeApi,
        filled: i64,
        price: i64,
        buyer: i64,
        buyer_xbt_dep: i64,
        seller: i64,
        seller_xbt_dep: i64,
    ) {
        assert_eq!(api.fees(LTC), fee_pool(filled, price), "LTC fee pool");
        assert_eq!(api.fees(XBT), 0, "XBT is not charged fees");
        assert_eq!(api.user_account(buyer, XBT), buyer_xbt_dep + base_amt(filled), "buyer XBT");
        assert_eq!(api.user_account(seller, XBT), seller_xbt_dep - base_amt(filled), "seller XBT");
        assert_eq!(conserved(api, XBT), 0, "XBT conservation");
        assert_eq!(conserved(api, LTC), 0, "LTC conservation");
    }

    #[test]
    fn should_require_taker_fees_gtc_cancel() {
        let mut api = new_api();
        let price = 11_400i64;
        let size = 30i64;
        let need = size_price_to_currency_scale(
            calculate_amount_bid_taker_fee(size, price, TAKER_FEE, FEE_SCALE_K),
            BASE_SCALE_K,
            QUOTE_SCALE_K,
            CURRENCY_SCALE_K,
        );

        user_money(&mut api, UID_2, LTC, need - 1, 1);
        assert_eq!(
            api.place_order(bid(203, UID_2, price, price, size, OrderType::Gtc)),
            CommandResultCode::RiskNsf,
            "short by 1 litoshi should be NSF"
        );
        assert_eq!(api.balance_adjustment(UID_2, LTC, 1, 2), CommandResultCode::Success);
        assert_eq!(
            api.place_order(bid(203, UID_2, price, price, size, OrderType::Gtc)),
            CommandResultCode::Success
        );
        assert_eq!(api.fees(LTC), 0, "placing order charges no fee");
        assert_eq!(available(&api, UID_2, LTC), 0, "the full amount is reserved");

        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 203, uid: UID_2, symbol: SYM }), CommandResultCode::Success);
        assert_eq!(available(&api, UID_2, LTC), need, "available balance returns to need after cancel");
        assert_eq!(api.fees(LTC), 0);
        assert_eq!(conserved(&api, LTC), 0);

        let btc_amount = 100_000_000i64;
        assert_eq!(api.balance_adjustment(UID_2, XBT, btc_amount, 3), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(204, UID_2, price, 100, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 204, uid: UID_2, symbol: SYM }), CommandResultCode::Success);
        assert_eq!(available(&api, UID_2, XBT), btc_amount, "XBT is fully returned after cancel");
        assert_eq!(api.fees(LTC), 0);
        assert_eq!(api.fees(XBT), 0);
        assert_eq!(conserved(&api, LTC), 0);
        assert_eq!(conserved(&api, XBT), 0);
    }

    fn ltc_deposit() -> i64 {
        2_000_000_000i64 * CURRENCY_SCALE_K
    }
    fn xbt_deposit() -> i64 {
        2_000i64 * CURRENCY_SCALE_K
    }

    #[test]
    fn bid_gtc_maker_ask_ioc_taker_partial() {
        let mut api = new_api();
        let maker_size = 1731 * BASE_SCALE_K;
        user_money(&mut api, UID_1, LTC, ltc_deposit(), 1);
        assert_eq!(
            api.place_order(bid(101, UID_1, PRICE, 11_553 * QUOTE_SCALE_K, maker_size, OrderType::Gtc)),
            CommandResultCode::Success
        );
        assert_eq!(api.fees(LTC), 0);

        let xbt_dep = xbt_deposit();
        user_money(&mut api, UID_2, XBT, xbt_dep, 2);
        assert_eq!(
            api.place_order(ask(102, UID_2, 11_493 * QUOTE_SCALE_K, 2000 * BASE_SCALE_K, OrderType::Ioc)),
            CommandResultCode::Success
        );

        assert_outcome(&api, maker_size, PRICE, UID_1, 0, UID_2, xbt_dep);
    }

    #[test]
    fn bid_gtc_maker_partial_ask_ioc_taker() {
        let mut api = new_api();
        let maker_size = 1731 * BASE_SCALE_K;
        let taker_size = 1000 * BASE_SCALE_K;
        user_money(&mut api, UID_1, LTC, ltc_deposit(), 1);
        assert_eq!(
            api.place_order(bid(101, UID_1, PRICE, 11_553 * QUOTE_SCALE_K, maker_size, OrderType::Gtc)),
            CommandResultCode::Success
        );

        let xbt_dep = xbt_deposit();
        user_money(&mut api, UID_2, XBT, xbt_dep, 2);
        assert_eq!(
            api.place_order(ask(102, UID_2, 11_493 * QUOTE_SCALE_K, taker_size, OrderType::Ioc)),
            CommandResultCode::Success
        );

        assert_outcome(&api, taker_size, PRICE, UID_1, 0, UID_2, xbt_dep);
        let l2 = api.request_l2(SYM, 10);
        assert_eq!(l2.bid_prices, vec![PRICE]);
        assert_eq!(l2.bid_volumes, vec![maker_size - taker_size]);
    }

    #[test]
    fn ask_gtc_maker_bid_ioc_taker_partial() {
        let mut api = new_api();
        let maker_size = 2000 * BASE_SCALE_K;
        let xbt_dep = xbt_deposit();
        user_money(&mut api, UID_1, XBT, xbt_dep, 1);
        assert_eq!(api.place_order(ask(101, UID_1, PRICE, maker_size, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.fees(LTC), 0);

        user_money(&mut api, UID_2, LTC, ltc_deposit(), 2);
        assert_eq!(
            api.place_order(bid(102, UID_2, 11_521 * QUOTE_SCALE_K, 11_659 * QUOTE_SCALE_K, 2197 * BASE_SCALE_K, OrderType::Ioc)),
            CommandResultCode::Success
        );

        assert_outcome(&api, maker_size, PRICE, UID_2, 0, UID_1, xbt_dep);
    }

    #[test]
    fn ask_gtc_maker_partial_bid_gtc_taker() {
        let mut api = new_api();
        let maker_size = 2000 * BASE_SCALE_K;
        let taker_size = 1997 * BASE_SCALE_K;
        let xbt_dep = xbt_deposit();
        user_money(&mut api, UID_1, XBT, xbt_dep, 1);
        assert_eq!(api.place_order(ask(101, UID_1, PRICE, maker_size, OrderType::Gtc)), CommandResultCode::Success);

        user_money(&mut api, UID_2, LTC, ltc_deposit(), 2);
        assert_eq!(
            api.place_order(bid(102, UID_2, 11_521 * QUOTE_SCALE_K, 11_659 * QUOTE_SCALE_K, taker_size, OrderType::Gtc)),
            CommandResultCode::Success
        );

        assert_outcome(&api, taker_size, PRICE, UID_2, 0, UID_1, xbt_dep);
    }

    #[test]
    fn ask_gtc_maker_partial_bid_ioc_taker() {
        let mut api = new_api();
        let maker_size = 2000 * BASE_SCALE_K;
        let taker_size = 1997 * BASE_SCALE_K;
        let xbt_dep = xbt_deposit();
        user_money(&mut api, UID_1, XBT, xbt_dep, 1);
        assert_eq!(api.place_order(ask(101, UID_1, PRICE, maker_size, OrderType::Gtc)), CommandResultCode::Success);

        user_money(&mut api, UID_2, LTC, ltc_deposit(), 2);
        assert_eq!(
            api.place_order(bid(102, UID_2, 11_521 * QUOTE_SCALE_K, 11_659 * QUOTE_SCALE_K, taker_size, OrderType::Ioc)),
            CommandResultCode::Success
        );

        assert_outcome(&api, taker_size, PRICE, UID_2, 0, UID_1, xbt_dep);
    }

    #[test]
    fn should_not_process_fees_ask_gtc_maker_partial_bid_fok_taker() {
        let mut api = new_api();
        let maker_size = 2000 * BASE_SCALE_K;
        let xbt_dep = xbt_deposit();
        user_money(&mut api, UID_1, XBT, xbt_dep, 1);
        assert_eq!(api.place_order(ask(101, UID_1, PRICE, maker_size, OrderType::Gtc)), CommandResultCode::Success);

        let ltc_dep = ltc_deposit();
        user_money(&mut api, UID_2, LTC, ltc_dep, 2);
        assert_eq!(
            api.place_order(bid(102, UID_2, PRICE, PRICE, 1000 * BASE_SCALE_K, OrderType::FokBudget)),
            CommandResultCode::Success
        );

        assert_outcome(&api, 0, PRICE, UID_2, 0, UID_1, xbt_dep);
        assert_eq!(api.user_account(UID_2, LTC), ltc_dep, "taker LTC is untouched");
    }

    #[test]
    fn should_process_fees_ask_gtc_maker_partial_bid_fok_taker() {
        let mut api = new_api();
        let maker_size = 2000 * BASE_SCALE_K;
        let taker_size = 1000 * BASE_SCALE_K;
        let xbt_dep = xbt_deposit();
        user_money(&mut api, UID_1, XBT, xbt_dep, 1);
        assert_eq!(api.place_order(ask(101, UID_1, PRICE, maker_size, OrderType::Gtc)), CommandResultCode::Success);

        user_money(&mut api, UID_2, LTC, ltc_deposit(), 2);
        let budget = taker_size * PRICE;
        assert_eq!(
            api.place_order(bid(102, UID_2, budget, budget, taker_size, OrderType::FokBudget)),
            CommandResultCode::Success
        );

        assert_outcome(&api, taker_size, PRICE, UID_2, 0, UID_1, xbt_dep);
    }
}
