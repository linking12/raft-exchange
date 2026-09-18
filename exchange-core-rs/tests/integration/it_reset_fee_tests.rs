#[cfg(test)]
mod tests {
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::fund_event::FundEventType;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceOrderRequest};

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;
    const SELLER: i64 = 1;
    const BUYER: i64 = 2;
    const SYSTEM_TRIGGERED_ORDER_ID: i64 = -1;

    fn setup() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE, 1);
        api.add_currency(QUOTE, 1);
        assert_eq!(
            api.add_symbol(CoreSymbolSpecification {
                symbol_id: SYMBOL,
                symbol_type: SymbolType::CurrencyExchangePair,
                base_currency: BASE,
                quote_currency: QUOTE,
                base_scale_k: 1,
                quote_scale_k: 1,
                taker_fee: 2,
                maker_fee: 1,
                ..Default::default()
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.add_user(SELLER), CommandResultCode::Success);
        assert_eq!(api.add_user(BUYER), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(SELLER, BASE, 1_000_000, 1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(BUYER, QUOTE, 100_000_000, 2), CommandResultCode::Success);
        api
    }

    fn reset_fee(api: &mut ExchangeApi) -> CommandResultCode {
        api.submit(OrderCommand { command: OrderCommandType::ResetFee, order_id: 999, ..Default::default() })
    }

    #[test]
    fn reset_fee_aggregates_and_clears() {
        let mut api = setup();
        assert_eq!(api.place_order(PlaceOrderRequest { order_id: 101, uid: SELLER, symbol: SYMBOL, price: 50, size: 20, reserve_bid_price: 0, action: OrderAction::Ask, order_type: OrderType::Gtc }), CommandResultCode::Success);
        assert_eq!(api.place_order(PlaceOrderRequest { order_id: 102, uid: BUYER, symbol: SYMBOL, price: 50, size: 20, reserve_bid_price: 50, action: OrderAction::Bid, order_type: OrderType::Ioc }), CommandResultCode::Success);

        let quote_fees = api.fees(QUOTE);
        assert!(quote_fees > 0, "matching should have accumulated fees[QUOTE]");
        let adj_before = api.adjustments(QUOTE);

        assert_eq!(reset_fee(&mut api), CommandResultCode::Success);

        assert_eq!(api.fees(QUOTE), 0, "fees should be cleared after RESET_FEE");
        assert_eq!(api.adjustments(QUOTE), adj_before + quote_fees, "fee amount should move into adjustments");
        assert!(api.total_balance().is_global_zero(), "global conservation should hold");

        let reset_events: Vec<_> = api.last_fund_events().iter().filter(|e| e.event_type == FundEventType::ResetFee).collect();
        assert_eq!(reset_events.len(), 1, "should emit exactly 1 ResetFee event (single currency)");
        let ev = reset_events[0];
        assert_eq!(ev.currency, QUOTE);
        assert_eq!(ev.free, quote_fees);
        assert_eq!(ev.locked, 0);
        assert_eq!(ev.uid, 0);
        assert_eq!(ev.order_id, SYSTEM_TRIGGERED_ORDER_ID);
    }

    #[test]
    fn reset_fee_empty_no_events_idempotent() {
        let mut api = setup();
        assert_eq!(api.fees(QUOTE), 0);

        assert_eq!(reset_fee(&mut api), CommandResultCode::Success);
        assert!(
            !api.last_fund_events().iter().any(|e| e.event_type == FundEventType::ResetFee),
            "no ResetFee event should be emitted when there are no fees"
        );

        assert_eq!(reset_fee(&mut api), CommandResultCode::Success);
        assert!(!api.last_fund_events().iter().any(|e| e.event_type == FundEventType::ResetFee));
        assert!(api.total_balance().is_global_zero());
    }
}
