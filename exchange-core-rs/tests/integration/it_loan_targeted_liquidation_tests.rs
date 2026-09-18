#[cfg(test)]
mod tests {
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceOrderRequest};

    const ETH: i32 = 3928;
    const XBT: i32 = 3762;
    const SYMBOL: i32 = 9269;
    const BORROWER: i64 = 6001;
    const LP: i64 = 6002;
    const LOAN_ID: i64 = 77;
    const OPEN_MARK: i64 = 1000;
    const CRASH_MARK: i64 = 500;
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
            ..Default::default()
        };
        spec.loan_config.update(6_000, 8_000, 7_000, i64::MAX, 365);
        spec
    }

    fn cmd_pool_deposit(order_id: i64, currency: i32, amount: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::PoolDeposit, order_id, symbol: currency, size: amount, ..Default::default() }
    }

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

    #[test]
    fn collateral_price_crash_targeted_triggers_force_sell_without_scan() {
        let mut api = ExchangeApi::new();
        api.add_currency(ETH, 1);
        api.add_currency(XBT, 1);
        assert_eq!(api.add_symbol(eth_xbt_loan_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL, OPEN_MARK), CommandResultCode::Success);

        assert_eq!(api.submit(cmd_pool_deposit(5000, XBT, POOL_FUND)), CommandResultCode::Success);

        assert_eq!(api.add_user(BORROWER), CommandResultCode::Success);
        assert_eq!(api.add_user(LP), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(BORROWER, ETH, ETH_COLLATERAL, 1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(LP, XBT, ETH_COLLATERAL * OPEN_MARK * 2, 2), CommandResultCode::Success);

        assert_eq!(
            api.submit(cmd_loan_create(1_000_002, BORROWER, SYMBOL, LOAN_ID, ETH_COLLATERAL, XBT_PRINCIPAL, 1_000)),
            CommandResultCode::Success
        );

        assert_eq!(
            api.place_order(PlaceOrderRequest {
                order_id: 1000,
                uid: LP,
                symbol: SYMBOL,
                price: CRASH_MARK,
                size: ETH_COLLATERAL,
                reserve_bid_price: CRASH_MARK,
                action: OrderAction::Bid,
                order_type: OrderType::Gtc,
            }),
            CommandResultCode::Success
        );

        api.enable_liquidation();

        assert_eq!(api.set_mark_price(SYMBOL, CRASH_MARK), CommandResultCode::Success);

        let collateral_now = api
            .ups()
            .get(BORROWER)
            .and_then(|up| up.isolated_loans.get(&LOAN_ID))
            .map(|l| l.collateral_amount)
            .unwrap_or(0);
        assert!(
            collateral_now < ETH_COLLATERAL,
            "collateral price crash should trigger immediate liquidation via the targeted path (no scan); unchanged collateral means the loan index missed the loan-only user (now={collateral_now})"
        );

        assert!(api.total_balance().is_global_zero(), "global balance should be conserved after targeted liquidation");
    }
}
