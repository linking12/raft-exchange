#[cfg(test)]
mod tests {
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{
        CancelOrderRequest, ExchangeApi, MoveOrderRequest, PlaceOrderRequest, ReduceOrderRequest,
    };

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;
    const SELLER: i64 = 1;
    const BUYER: i64 = 3;

    fn spot_spec(taker_fee: i64, maker_fee: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee,
            maker_fee,
            ..Default::default()
        }
    }

    fn setup() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(api.add_symbol(spot_spec(2, 1)), CommandResultCode::Success);
        assert_eq!(api.add_user(SELLER), CommandResultCode::Success);
        assert_eq!(api.add_user(BUYER), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(SELLER, BASE, 1_000_000, 1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(BUYER, QUOTE, 10_000_000_000, 2), CommandResultCode::Success);
        api
    }

    fn bid(order_id: i64, uid: i64, price: i64, reserve: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYMBOL, price, size, reserve_bid_price: reserve, action: OrderAction::Bid, order_type: ot }
    }
    fn ask(order_id: i64, uid: i64, price: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYMBOL, price, size, reserve_bid_price: 0, action: OrderAction::Ask, order_type: ot }
    }
    fn acct(api: &ExchangeApi, uid: i64, cur: i32) -> i64 {
        api.ups().get(uid).unwrap().account(cur)
    }
    fn locked(api: &ExchangeApi, uid: i64, cur: i32) -> i64 {
        api.ups().get(uid).unwrap().locked(cur)
    }
    fn total_quote(api: &ExchangeApi) -> i64 {
        let user_sum: i64 = api.ups().users.values().map(|p| p.account(QUOTE)).sum();
        user_sum + api.risk().fees.get(&QUOTE).copied().unwrap_or(0)
    }

    #[test]
    fn match_updates_balances_directionally_and_conserves() {
        let mut api = setup();
        let (buyer_quote0, seller_base0) = (acct(&api, BUYER, QUOTE), acct(&api, SELLER, BASE));
        let q_before = total_quote(&api);

        assert_eq!(api.place_order(ask(5001, SELLER, 20_000, 1, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(5002, BUYER, 20_000, 20_000, 1, OrderType::Gtc)), CommandResultCode::Success);

        assert!(acct(&api, BUYER, BASE) > 0, "buyer should receive BTC");
        assert!(acct(&api, BUYER, QUOTE) < buyer_quote0, "buyer's USDT should decrease");
        assert!(acct(&api, SELLER, BASE) < seller_base0, "seller's BTC should decrease");
        assert!(acct(&api, SELLER, QUOTE) > 0, "seller should receive USDT");
        assert_eq!(total_quote(&api), q_before, "QUOTE should be globally conserved (including fees pool)");
    }

    #[test]
    fn cancel_restores_locked_funds() {
        let mut api = setup();
        let locked_before = locked(&api, BUYER, QUOTE);
        assert_eq!(api.place_order(bid(6001, BUYER, 10_000, 10_000, 1, OrderType::Gtc)), CommandResultCode::Success);
        assert!(locked(&api, BUYER, QUOTE) > locked_before, "quote should be locked after placing the order");
        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 6001, uid: BUYER, symbol: SYMBOL }), CommandResultCode::Success);
        assert_eq!(locked(&api, BUYER, QUOTE), locked_before, "the lock should be fully released after cancel");
    }

    #[test]
    fn partial_fill_then_cancel_releases_remaining() {
        let mut api = setup();
        assert_eq!(api.place_order(ask(7001, SELLER, 20_000, 1, OrderType::Gtc)), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(7002, BUYER, 20_000, 20_000, 3, OrderType::Gtc)), CommandResultCode::Success);
        let locked_after_partial = locked(&api, BUYER, QUOTE);
        assert!(locked_after_partial > 0, "the remaining 2 lots should still be locked");
        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 7002, uid: BUYER, symbol: SYMBOL }), CommandResultCode::Success);
        assert!(locked(&api, BUYER, QUOTE) < locked_after_partial, "canceling the remainder should release the lock");
    }

    #[test]
    fn ioc_no_counterparty_no_change() {
        let mut api = setup();
        let (bal, lk) = (acct(&api, BUYER, QUOTE), locked(&api, BUYER, QUOTE));
        assert_eq!(api.place_order(bid(8001, BUYER, 2_000, 2_000, 1, OrderType::Ioc)), CommandResultCode::Success);
        assert_eq!(acct(&api, BUYER, QUOTE), bal, "an unfilled IOC should not change the balance");
        assert_eq!(locked(&api, BUYER, QUOTE), lk, "an unfilled IOC should not leave any lock");
    }

    #[test]
    fn oversized_order_rejected_risk_nsf() {
        let mut api = setup();
        assert_eq!(
            api.place_order(bid(9001, BUYER, 20_000, 20_000, 1_000_000_000, OrderType::Gtc)),
            CommandResultCode::RiskNsf,
            "exceeding the balance should return RISK_NSF"
        );
    }

    #[test]
    fn withdraw_decreases_balance() {
        let mut api = setup();
        let before = acct(&api, BUYER, QUOTE);
        assert_eq!(api.balance_adjustment(BUYER, QUOTE, -1_000, 100), CommandResultCode::Success);
        assert_eq!(acct(&api, BUYER, QUOTE), before - 1_000, "withdrawal should decrease the balance exactly");
    }

    #[test]
    fn move_within_reserve_keeps_lock_over_reserve_rejected() {
        let mut api = setup();
        assert_eq!(api.place_order(bid(1001, BUYER, 10_000, 14_000, 1, OrderType::Gtc)), CommandResultCode::Success);
        let lock_at_place = locked(&api, BUYER, QUOTE);
        assert!(lock_at_place > 0);

        assert_eq!(api.move_order(MoveOrderRequest { order_id: 1001, uid: BUYER, symbol: SYMBOL, new_price: 12_000 }), CommandResultCode::Success);
        assert_eq!(locked(&api, BUYER, QUOTE), lock_at_place, "moving price within reserve should not change the lock");

        assert_eq!(
            api.move_order(MoveOrderRequest { order_id: 1001, uid: BUYER, symbol: SYMBOL, new_price: 18_000 }),
            CommandResultCode::MatchingMoveFailedPriceOverRiskLimit,
            "moving price beyond reserveBidPrice should be rejected by risk control"
        );
    }

    #[test]
    fn reduce_releases_lock() {
        let mut api = setup();
        assert_eq!(api.place_order(bid(1101, BUYER, 10_000, 10_000, 2, OrderType::Gtc)), CommandResultCode::Success);
        let lock_full = locked(&api, BUYER, QUOTE);
        assert_eq!(api.reduce_order(ReduceOrderRequest { order_id: 1101, uid: BUYER, symbol: SYMBOL, reduce_size: 1 }), CommandResultCode::Success);
        assert!(locked(&api, BUYER, QUOTE) < lock_full, "reduce should release the corresponding lock");
    }

    #[test]
    fn fok_no_full_liquidity_no_fill() {
        let mut api = setup();
        assert_eq!(api.place_order(ask(1201, SELLER, 20_000, 1, OrderType::Gtc)), CommandResultCode::Success);
        let (base_before, quote_before) = (acct(&api, BUYER, BASE), acct(&api, BUYER, QUOTE));
        api.place_order(bid(1202, BUYER, 20_000, 20_000, 3, OrderType::Fok));
        assert_eq!(acct(&api, BUYER, BASE), base_before, "FOK should not fill at all when it cannot fill fully");
        assert_eq!(acct(&api, BUYER, QUOTE), quote_before, "an unfilled FOK should not change the balance");
        assert_eq!(locked(&api, BUYER, QUOTE), 0, "an unfilled FOK should not leave any lock");
    }

    #[test]
    fn unfilled_ioc_ask_releases_base_lock_no_leak() {
        let mut api = setup();
        let base0 = acct(&api, SELLER, BASE);
        assert_eq!(api.place_order(ask(3001, SELLER, 20_000, 600_000, OrderType::Ioc)), CommandResultCode::Success);
        assert_eq!(locked(&api, SELLER, BASE), 0, "the first unfilled IOC ASK should release all of the base lock");
        assert_eq!(api.place_order(ask(3002, SELLER, 20_000, 600_000, OrderType::Ioc)), CommandResultCode::Success, "lock already released, the second order should not be NSF (Java wrongly rejects it here due to a lock leak)");
        assert_eq!(locked(&api, SELLER, BASE), 0);
        assert_eq!(acct(&api, SELLER, BASE), base0, "unfilled should not change the balance");
    }
}
