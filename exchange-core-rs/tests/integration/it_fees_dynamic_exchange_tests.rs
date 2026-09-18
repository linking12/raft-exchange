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

    const BASE_SCALE_K: i64 = 1;
    const QUOTE_SCALE_K: i64 = 1;
    const CURRENCY_SCALE_K: i64 = 1;
    const MAKER_FEE: i64 = 1;
    const TAKER_FEE: i64 = 2;
    const FEE_SCALE_K: i64 = 100;

    const PRICE: i64 = 10_000;
    const RESERVE: i64 = 10_005;
    const MAKER_LTC_DEPOSIT: i64 = 10_000_000;
    const TAKER_LTC_DEPOSIT: i64 = 10_000_000;
    const ASK_MAKER_XBT_DEPOSIT: i64 = 2_000;

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

    fn fee_pool(filled: i64) -> i64 {
        if filled == 0 {
            return 0;
        }
        let maker = size_price_to_currency_scale(
            calculate_maker_fee(filled, PRICE, MAKER_FEE, FEE_SCALE_K),
            BASE_SCALE_K,
            QUOTE_SCALE_K,
            CURRENCY_SCALE_K,
        );
        let taker = size_price_to_currency_scale(
            calculate_taker_fee(filled, PRICE, TAKER_FEE, FEE_SCALE_K),
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

    #[allow(clippy::too_many_arguments)]
    fn run_spot(
        maker_action: OrderAction,
        maker_size: i64,
        taker_type: OrderType,
        taker_size: i64,
        taker_price: i64,
        taker_reserve: i64,
        expected_filled: i64,
    ) {
        let mut api = new_api();

        let (buyer, seller, buyer_xbt_dep, seller_xbt_dep);
        match maker_action {
            OrderAction::Bid => {
                user_money(&mut api, UID_1, LTC, MAKER_LTC_DEPOSIT, 1);
                user_money(&mut api, UID_2, XBT, taker_size, 2);
                buyer = UID_1;
                seller = UID_2;
                buyer_xbt_dep = 0;
                seller_xbt_dep = taker_size;
            }
            OrderAction::Ask => {
                user_money(&mut api, UID_1, XBT, ASK_MAKER_XBT_DEPOSIT, 1);
                user_money(&mut api, UID_2, LTC, TAKER_LTC_DEPOSIT, 2);
                buyer = UID_2;
                seller = UID_1;
                buyer_xbt_dep = 0;
                seller_xbt_dep = ASK_MAKER_XBT_DEPOSIT;
            }
        }

        let maker_res = if maker_action == OrderAction::Bid { RESERVE } else { 0 };
        let maker_req = PlaceOrderRequest {
            order_id: 101,
            uid: UID_1,
            symbol: SYM,
            price: PRICE,
            size: maker_size,
            reserve_bid_price: maker_res,
            action: maker_action,
            order_type: OrderType::Gtc,
        };
        assert_eq!(api.place_order(maker_req), CommandResultCode::Success);
        assert_eq!(api.fees(LTC), 0, "placing the maker order should not add to the fee pool");

        let taker_action = match maker_action {
            OrderAction::Bid => OrderAction::Ask,
            OrderAction::Ask => OrderAction::Bid,
        };
        let taker_req = match taker_action {
            OrderAction::Bid => bid(102, UID_2, taker_price, taker_reserve, taker_size, taker_type),
            OrderAction::Ask => ask(102, UID_2, taker_price, taker_size, taker_type),
        };
        assert_eq!(api.place_order(taker_req), CommandResultCode::Success);

        assert_eq!(api.fees(LTC), fee_pool(expected_filled), "LTC fee pool");
        assert_eq!(api.fees(XBT), 0);
        assert_eq!(api.user_account(buyer, XBT), buyer_xbt_dep + base_amt(expected_filled), "buyer XBT");
        assert_eq!(api.user_account(seller, XBT), seller_xbt_dep - base_amt(expected_filled), "seller XBT");
        assert_eq!(conserved(&api, XBT), 0, "XBT conservation");
        assert_eq!(conserved(&api, LTC), 0, "LTC conservation");
    }

    #[test]
    fn should_require_taker_fees_gtc_cancel1() {
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
            CommandResultCode::RiskNsf
        );
        assert_eq!(api.balance_adjustment(UID_2, LTC, 1, 2), CommandResultCode::Success);
        assert_eq!(
            api.place_order(bid(203, UID_2, price, price, size, OrderType::Gtc)),
            CommandResultCode::Success
        );
        assert_eq!(api.fees(LTC), 0);
        assert_eq!(available(&api, UID_2, LTC), 0, "fully reserved");

        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 203, uid: UID_2, symbol: SYM }), CommandResultCode::Success);
        assert_eq!(available(&api, UID_2, LTC), need, "available balance returns to `need` after cancel");
        assert_eq!(api.fees(LTC), 0);
        assert_eq!(conserved(&api, LTC), 0);

        let btc_amount = 100_000_000i64;
        assert_eq!(api.balance_adjustment(UID_2, XBT, btc_amount, 3), CommandResultCode::Success);
        assert_eq!(api.place_order(ask(204, UID_2, price, 100, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 204, uid: UID_2, symbol: SYM }), CommandResultCode::Success);
        assert_eq!(available(&api, UID_2, XBT), btc_amount, "full XBT returned after cancel");
        assert_eq!(api.fees(LTC), 0);
        assert_eq!(api.fees(XBT), 0);
        assert_eq!(conserved(&api, LTC), 0);
        assert_eq!(conserved(&api, XBT), 0);
    }

    #[test]
    fn bid_gtc_maker_ask_ioc_taker_partial() {
        run_spot(OrderAction::Bid, 500, OrderType::Ioc, 2000, PRICE, 0, 500);
    }

    #[test]
    fn bid_gtc_maker_partial_ask_ioc_taker() {
        run_spot(OrderAction::Bid, 500, OrderType::Ioc, 100, PRICE, 0, 100);
    }

    #[test]
    fn bid_gtc_maker_ask_ioc_taker_fully_match() {
        run_spot(OrderAction::Bid, 500, OrderType::Ioc, 500, PRICE, 0, 500);
    }

    #[test]
    fn ask_gtc_maker_bid_ioc_taker_partial() {
        run_spot(OrderAction::Ask, 100, OrderType::Ioc, 500, PRICE, RESERVE, 100);
    }

    #[test]
    fn ask_gtc_maker_partial_bid_ioc_taker() {
        run_spot(OrderAction::Ask, 500, OrderType::Ioc, 100, PRICE, RESERVE, 100);
    }

    #[test]
    fn ask_gtc_maker_partial_bid_gtc_taker() {
        run_spot(OrderAction::Ask, 500, OrderType::Gtc, 100, PRICE, RESERVE, 100);
    }

    #[test]
    fn ask_gtc_maker_bid_gtc_taker_partial() {
        run_spot(OrderAction::Ask, 100, OrderType::Gtc, 500, PRICE, RESERVE, 100);
    }

    #[test]
    fn ask_gtc_maker_partial_bid_fok_budget_taker() {
        run_spot(OrderAction::Ask, 500, OrderType::FokBudget, 1, PRICE, PRICE, 1);
    }

    #[test]
    fn should_not_process_fees_ask_gtc_maker_partial_bid_fok_taker() {
        run_spot(OrderAction::Ask, 500, OrderType::FokBudget, 10, PRICE, PRICE, 0);
    }

    #[test]
    fn fee_oracle_matches_java_independent_formula() {
        for filled in [1i64, 30, 100] {
            let java_maker = PRICE * filled * QUOTE_SCALE_K * MAKER_FEE / FEE_SCALE_K;
            let java_taker = PRICE * filled * QUOTE_SCALE_K * TAKER_FEE / FEE_SCALE_K;
            assert_eq!(fee_pool(filled), java_maker + java_taker, "fee_pool@{filled} = Java (maker+taker) price*size*step*sideFee/scale");
        }
    }
}
