#[cfg(test)]
mod tests {
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::matcher_event_type::MatcherEventType;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceOrderRequest};

    const ETH: i32 = 3928;
    const XBT: i32 = 3762;
    const SYMBOL: i32 = 9269;
    const BORROWER: i64 = 5001;
    const LP: i64 = 5002;
    const LOAN_ID: i64 = 42;
    const MARK_PRICE: i64 = 1000;
    const ETH_COLLATERAL: i64 = 100;
    const XBT_PRINCIPAL: i64 = 50_000;
    const POOL_FUND: i64 = 1_000_000;

    fn eth_xbt_loan_spec() -> CoreSymbolSpecification {
        let mut spec = CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: ETH,
            quote_currency: XBT,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        };
        spec.loan_config.update(6_000, 8_500, 7_500, i64::MAX, 365);
        spec
    }

    fn cmd_pool_deposit(order_id: i64, currency: i32, amount: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::PoolDeposit, order_id, symbol: currency, size: amount, ..Default::default() }
    }

    #[allow(clippy::too_many_arguments)]
    fn cmd_loan_create(order_id: i64, uid: i64, symbol: i32, loan_id: i64, collateral: i64, principal: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCreate,
            order_id,
            uid,
            symbol,
            size: collateral,
            price: principal,
            reserve_bid_price: loan_id,
            user_cookie: 0,
            timestamp: ts,
            ..Default::default()
        }
    }

    fn cmd_loan_force_liquidate(order_id: i64, uid: i64, symbol: i32, loan_id: i64, price: i64, lots: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanForceLiquidate,
            order_id,
            uid,
            symbol,
            price,
            size: lots,
            reserve_bid_price: loan_id,
            timestamp: ts,
            ..Default::default()
        }
    }

    #[test]
    fn force_liquidate_flows_through_orderbook_and_settles() {
        let mut api = ExchangeApi::new();
        api.add_currency(ETH, 1);
        api.add_currency(XBT, 1);
        assert_eq!(api.add_symbol(eth_xbt_loan_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL, MARK_PRICE), CommandResultCode::Success);

        assert_eq!(api.submit(cmd_pool_deposit(5000, XBT, POOL_FUND)), CommandResultCode::Success);

        assert_eq!(api.add_user(BORROWER), CommandResultCode::Success);
        assert_eq!(api.add_user(LP), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(BORROWER, ETH, ETH_COLLATERAL, 1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(LP, XBT, ETH_COLLATERAL * MARK_PRICE * 2, 2), CommandResultCode::Success);

        assert_eq!(
            api.submit(cmd_loan_create(1_000_002, BORROWER, SYMBOL, LOAN_ID, ETH_COLLATERAL, XBT_PRINCIPAL, 1_000)),
            CommandResultCode::Success
        );

        assert_eq!(
            api.place_order(PlaceOrderRequest {
                order_id: 1000,
                uid: LP,
                symbol: SYMBOL,
                price: MARK_PRICE,
                size: ETH_COLLATERAL,
                reserve_bid_price: MARK_PRICE,
                action: OrderAction::Bid,
                order_type: OrderType::Gtc,
            }),
            CommandResultCode::Success
        );

        assert_eq!(
            api.submit(cmd_loan_force_liquidate(2222, BORROWER, SYMBOL, LOAN_ID, MARK_PRICE, ETH_COLLATERAL, 1_000)),
            CommandResultCode::Success
        );

        let head = api.last_matcher_event().expect("matcherEvent chain is empty -- command never reached orderbook.newOrder");
        let mut trade_count = 0;
        let mut ev = Some(head);
        while let Some(e) = ev {
            if e.event_type == MatcherEventType::Trade {
                trade_count += 1;
            }
            ev = e.next.as_deref();
        }
        assert!(trade_count > 0, "expected at least 1 TRADE event, got {trade_count}");

        assert_eq!(api.user_locked(BORROWER, ETH), 0, "collateral should be consumed by the TRADE, not stuck in exchange_locked");
        assert_eq!(api.user_account(LP, ETH), ETH_COLLATERAL, "LP should receive the full 100 ETH");

        assert!(api.total_balance().is_global_zero(), "globally conserved after force liquidation");
    }
}
