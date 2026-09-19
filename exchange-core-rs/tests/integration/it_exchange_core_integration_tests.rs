#[cfg(test)]
mod tests {

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::matcher_event_type::MatcherEventType;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{
        CancelOrderRequest, ExchangeApi, MoveOrderRequest, PlaceOrderRequest,
    };

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;
    const UID_1: i64 = 1;
    const UID_2: i64 = 2;

    const SYMBOL_EX: i32 = 9269;
    const ETH: i32 = 3928;
    const XBT: i32 = 3762;

    fn setup_exchange() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(ETH, 1);
        api.add_currency(XBT, 10);
        let spec = CoreSymbolSpecification {
            symbol_id: SYMBOL_EX,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: ETH,
            quote_currency: XBT,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            ..Default::default()
        };
        assert_eq!(api.add_symbol(spec), CommandResultCode::Success);
        api
    }
    fn ex_ask(order_id: i64, uid: i64, price: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYMBOL_EX, price, size, reserve_bid_price: 0, action: OrderAction::Ask, order_type: ot }
    }
    fn ex_bid(order_id: i64, uid: i64, price: i64, reserve: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYMBOL_EX, price, size, reserve_bid_price: reserve, action: OrderAction::Bid, order_type: ot }
    }

    fn spot_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            ..Default::default()
        }
    }

    fn setup() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(api.add_symbol(spot_spec()), CommandResultCode::Success);
        for uid in [UID_1, UID_2] {
            assert_eq!(api.add_user(uid), CommandResultCode::Success);
            assert_eq!(api.balance_adjustment(uid, BASE, 1_000_000, uid * 10), CommandResultCode::Success);
            assert_eq!(api.balance_adjustment(uid, QUOTE, 100_000_000, uid * 10 + 1), CommandResultCode::Success);
        }
        api
    }

    fn bid(order_id: i64, uid: i64, price: i64, reserve: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYMBOL, price, size, reserve_bid_price: reserve, action: OrderAction::Bid, order_type: ot }
    }
    fn ask(order_id: i64, uid: i64, price: i64, size: i64, ot: OrderType) -> PlaceOrderRequest {
        PlaceOrderRequest { order_id, uid, symbol: SYMBOL, price, size, reserve_bid_price: 0, action: OrderAction::Ask, order_type: ot }
    }

    fn assert_l2(api: &mut ExchangeApi, asks: &[(i64, i64)], bids: &[(i64, i64)]) {
        let l2 = api.request_l2(SYMBOL, 10);
        assert_eq!(l2.ask_prices, asks.iter().map(|x| x.0).collect::<Vec<_>>(), "ask prices");
        assert_eq!(l2.ask_volumes, asks.iter().map(|x| x.1).collect::<Vec<_>>(), "ask volumes");
        assert_eq!(l2.bid_prices, bids.iter().map(|x| x.0).collect::<Vec<_>>(), "bid prices");
        assert_eq!(l2.bid_volumes, bids.iter().map(|x| x.1).collect::<Vec<_>>(), "bid volumes");
    }

    fn total(api: &ExchangeApi, cur: i32) -> i64 {
        api.ups().users.values().map(|p| p.account(cur)).sum::<i64>() + api.risk().fees.get(&cur).copied().unwrap_or(0)
    }

    #[test]
    fn basic_full_cycle_exchange() {
        let mut api = setup();
        let (base0, quote0) = (total(&api, BASE), total(&api, QUOTE));

        assert_eq!(api.place_order(ask(101, UID_1, 1600, 7, OrderType::Gtc)), CommandResultCode::Success);
        assert!(api.last_matcher_event().is_none(), "order 101 resting, no match");
        assert_eq!(api.place_order(bid(102, UID_1, 1550, 1561, 4, OrderType::Gtc)), CommandResultCode::Success);
        assert!(api.last_matcher_event().is_none(), "order 102 resting, no match");
        assert_l2(&mut api, &[(1600, 7)], &[(1550, 4)]);

        assert_eq!(api.place_order(bid(201, UID_2, 1700, 1800, 2, OrderType::Ioc)), CommandResultCode::Success);
        {
            let ev = api.last_matcher_event().expect("order 201 should produce exactly 1 TRADE event");
            assert!(ev.next.is_none(), "only 1 matching event");
            assert_eq!(ev.event_type, MatcherEventType::Trade);
            assert!(ev.active_order_completed, "taker 201 fully filled (2/2)");
            assert_eq!(ev.maker_order_id, 101, "counterparty = 101");
            assert_eq!(ev.matched_order_uid, UID_1);
            assert!(!ev.maker_order_completed, "maker 101 not fully filled (5 remaining)");
            assert_eq!(ev.size, 2);
            assert_eq!(ev.price, 1600);
        }
        assert_l2(&mut api, &[(1600, 5)], &[(1550, 4)]);

        assert_eq!(api.place_order(bid(202, UID_2, 1583, 1583, 4, OrderType::Gtc)), CommandResultCode::Success);
        assert!(api.last_matcher_event().is_none());
        assert_l2(&mut api, &[(1600, 5)], &[(1583, 4), (1550, 4)]);

        assert_eq!(api.move_order(MoveOrderRequest { order_id: 101, uid: UID_1, symbol: SYMBOL, new_price: 1580 }), CommandResultCode::Success);
        {
            let ev = api.last_matcher_event().expect("move-order should produce 1 TRADE event");
            assert!(ev.next.is_none());
            assert_eq!(ev.event_type, MatcherEventType::Trade);
            assert!(!ev.active_order_completed, "101 not fully filled (1 remaining)");
            assert_eq!(ev.maker_order_id, 202);
            assert_eq!(ev.matched_order_uid, UID_2);
            assert!(ev.maker_order_completed, "maker 202 fully filled");
            assert_eq!(ev.size, 4);
            assert_eq!(ev.price, 1583);
        }
        assert_l2(&mut api, &[(1580, 1)], &[(1550, 4)]);

        assert_eq!(total(&api, BASE), base0, "BASE conserved");
        assert_eq!(total(&api, QUOTE), quote0, "QUOTE conserved");
    }

    #[test]
    fn exchange_risk_basic_nsf_then_accept() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(api.add_symbol(spot_spec()), CommandResultCode::Success);
        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, QUOTE, 200_000, 1), CommandResultCode::Success);

        assert_eq!(
            api.place_order(bid(101, UID_1, 30_000, 30_000, 7, OrderType::Gtc)),
            CommandResultCode::RiskNsf,
            "insufficient balance should yield RISK_NSF"
        );
        assert_eq!(api.ups().get(UID_1).unwrap().account(QUOTE), 200_000, "account balance unchanged after rejection");
        assert_eq!(api.ups().get(UID_1).unwrap().locked(QUOTE), 0, "no funds locked after rejection");

        assert_eq!(api.balance_adjustment(UID_1, QUOTE, 100_000, 2), CommandResultCode::Success);
        assert_eq!(api.place_order(bid(101, UID_1, 30_000, 30_000, 7, OrderType::Gtc)), CommandResultCode::Success);
        assert!(api.last_matcher_event().is_none());
        assert_eq!(api.ups().get(UID_1).unwrap().account(QUOTE), 300_000, "accounts unchanged");
        assert_eq!(api.ups().get(UID_1).unwrap().locked(QUOTE), 210_000, "locked = 7×30000");
    }

    #[test]
    fn exchange_cancel_bid() {
        let mut api = setup_exchange();
        assert_eq!(api.add_user(UID_2), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_2, XBT, 94_000_000, 1), CommandResultCode::Success);

        assert_eq!(api.place_order(ex_bid(203, UID_2, 18_000, 18_500, 500, OrderType::Gtc)), CommandResultCode::Success);
        let xbt_lock = 10 * 18_500 * 500;
        assert_eq!(api.ups().get(UID_2).unwrap().account(XBT), 94_000_000, "accounts unchanged");
        assert_eq!(api.ups().get(UID_2).unwrap().locked(XBT), xbt_lock, "BID locks funds");

        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 203, uid: UID_2, symbol: SYMBOL_EX }), CommandResultCode::Success);
        {
            let ev = api.last_matcher_event().expect("cancel should produce a REDUCE event");
            assert_eq!(ev.event_type, MatcherEventType::Reduce);
            assert_eq!(ev.bidder_hold_price, 18_500);
            assert_eq!(ev.size, 500);
        }
        assert_eq!(api.ups().get(UID_2).unwrap().account(XBT), 94_000_000, "full 94M refunded after cancel");
        assert_eq!(api.ups().get(UID_2).unwrap().locked(XBT), 0, "cancel fully releases locked funds");
        assert!(api.total_balance().is_global_zero());
    }

    #[test]
    fn should_init_symbols() {

        let _spot = setup();
        let mut ex = setup_exchange();
        let l2 = ex.request_l2(SYMBOL_EX, 10);
        assert!(l2.ask_prices.is_empty(), "freshly initialized book must be empty");
        assert!(l2.bid_prices.is_empty(), "freshly initialized book must be empty");
    }

    #[test]
    fn should_init_users() {

        let api = setup();
        assert_eq!(api.ups().get(UID_1).unwrap().account(BASE), 1_000_000);
        assert_eq!(api.ups().get(UID_1).unwrap().account(QUOTE), 100_000_000);
        assert_eq!(api.ups().get(UID_2).unwrap().account(BASE), 1_000_000);
        assert_eq!(api.ups().get(UID_2).unwrap().account(QUOTE), 100_000_000);
    }

    #[test]
    fn exchange_risk_move() {
        let mut api = setup_exchange();
        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, ETH, 100_000_000, 1), CommandResultCode::Success);

        assert_eq!(api.place_order(ex_ask(202, UID_1, 30_000, 1000, OrderType::Gtc)), CommandResultCode::Success);
        assert!(api.last_matcher_event().is_none());
        assert_eq!(api.ups().get(UID_1).unwrap().account(ETH), 100_000_000, "ASK does not deduct accounts");
        assert_eq!(api.ups().get(UID_1).unwrap().locked(ETH), 1000, "ASK locks base = size");

        assert_eq!(api.move_order(MoveOrderRequest { order_id: 202, uid: UID_1, symbol: SYMBOL_EX, new_price: 40_000 }), CommandResultCode::Success);
        assert!(api.last_matcher_event().is_none(), "move-order does not match");
        assert_eq!(api.move_order(MoveOrderRequest { order_id: 202, uid: UID_1, symbol: SYMBOL_EX, new_price: 20_000 }), CommandResultCode::Success);
        assert!(api.last_matcher_event().is_none());
        assert_eq!(api.ups().get(UID_1).unwrap().account(ETH), 100_000_000);

        assert_eq!(api.add_user(UID_2), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_2, XBT, 94_000_000, 2), CommandResultCode::Success);

        assert_eq!(
            api.place_order(ex_bid(203, UID_2, 18_000, 19_000, 500, OrderType::Gtc)),
            CommandResultCode::RiskNsf,
            "reserve exceeding funds limit should yield RISK_NSF"
        );
        assert_eq!(api.ups().get(UID_2).unwrap().account(XBT), 94_000_000);
        assert_eq!(api.ups().get(UID_2).unwrap().locked(XBT), 0, "no lock after rejection");

        assert_eq!(api.place_order(ex_bid(203, UID_2, 18_000, 18_500, 500, OrderType::Gtc)), CommandResultCode::Success);
        assert!(api.last_matcher_event().is_none());
        let xbt_lock = 10 * 18_500 * 500;
        assert_eq!(api.ups().get(UID_2).unwrap().account(XBT), 94_000_000, "BID does not deduct accounts");
        assert_eq!(api.ups().get(UID_2).unwrap().locked(XBT), xbt_lock, "BID locks 92.5M");

        assert_eq!(api.move_order(MoveOrderRequest { order_id: 203, uid: UID_2, symbol: SYMBOL_EX, new_price: 15_000 }), CommandResultCode::Success);
        assert_eq!(api.ups().get(UID_2).unwrap().account(XBT), 94_000_000);

        assert_eq!(
            api.move_order(MoveOrderRequest { order_id: 203, uid: UID_2, symbol: SYMBOL_EX, new_price: 18_501 }),
            CommandResultCode::MatchingMoveFailedPriceOverRiskLimit,
            "moving price beyond reserveBidPrice should be rejected by risk check"
        );

        assert_eq!(api.move_order(MoveOrderRequest { order_id: 203, uid: UID_2, symbol: SYMBOL_EX, new_price: 18_500 }), CommandResultCode::Success);
        assert_eq!(api.move_order(MoveOrderRequest { order_id: 203, uid: UID_2, symbol: SYMBOL_EX, new_price: 17_500 }), CommandResultCode::Success);
        assert_eq!(api.ups().get(UID_2).unwrap().account(XBT), 94_000_000);

        assert_eq!(api.move_order(MoveOrderRequest { order_id: 202, uid: UID_1, symbol: SYMBOL_EX, new_price: 16_900 }), CommandResultCode::Success);
        {
            let ev = api.last_matcher_event().expect("move-order triggering a match should produce a TRADE event");
            assert_eq!(ev.event_type, MatcherEventType::Trade);
            assert!(!ev.active_order_completed, "taker 202 not fully filled (500 remaining)");
            assert_eq!(ev.maker_order_id, 203, "counterparty = 203");
            assert_eq!(ev.matched_order_uid, UID_2);
            assert!(ev.maker_order_completed, "maker 203 fully filled");
            assert_eq!(ev.price, 17_500, "trade price = maker's resting price");
            assert_eq!(ev.bidder_hold_price, 18_500, "bidder's original reserve price");
            assert_eq!(ev.size, 500);
        }
        assert_eq!(api.ups().get(UID_1).unwrap().account(XBT), 87_500_000);
        assert_eq!(api.ups().get(UID_1).unwrap().account(ETH), 99_999_500);
        assert_eq!(api.ups().get(UID_1).unwrap().locked(ETH), 500, "500 remaining, pending cancel/match");
        assert_eq!(api.ups().get(UID_2).unwrap().account(XBT), 6_500_000);
        assert_eq!(api.ups().get(UID_2).unwrap().account(ETH), 500);

        assert_eq!(api.cancel_order(CancelOrderRequest { order_id: 202, uid: UID_1, symbol: SYMBOL_EX }), CommandResultCode::Success);
        {
            let ev = api.last_matcher_event().expect("cancel should produce REDUCE");
            assert_eq!(ev.event_type, MatcherEventType::Reduce);
            assert_eq!(ev.size, 500);
        }
        assert_eq!(api.ups().get(UID_1).unwrap().account(XBT), 87_500_000);
        assert_eq!(api.ups().get(UID_1).unwrap().account(ETH), 99_999_500);
        assert_eq!(api.ups().get(UID_1).unwrap().locked(ETH), 0, "cancel releases remaining locked funds");
        assert!(api.total_balance().is_global_zero());
    }
}
