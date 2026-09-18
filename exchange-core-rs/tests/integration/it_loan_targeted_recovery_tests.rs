#[cfg(test)]
mod tests {
    use exchange_core_rs::core::common::last_price_cache_record::LastPriceCacheRecord;
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_currency_specification::CoreCurrencySpecification;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_core::ExchangeCore;
    use exchange_core_rs::core::snapshot::serialization_processor::InMemorySerializationProcessor;

    const ETH: i32 = 3928;
    const XBT: i32 = 3762;
    const SYMBOL: i32 = 9269;
    const BORROWER: i64 = 7001;
    const LP: i64 = 7002;
    const LOAN_ID: i64 = 88;
    const OPEN_MARK: i64 = 1000;
    const CRASH_MARK: i64 = 500;
    const ETH_COLLATERAL: i64 = 100;
    const XBT_PRINCIPAL: i64 = 50_000;
    const POOL_FUND: i64 = 1_000_000;

    fn submit(core: &mut ExchangeCore, mut cmd: OrderCommand) -> CommandResultCode {
        core.process_command(&mut cmd);
        cmd.result_code.expect("every command produces a result code")
    }

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

    fn cmd_balance_adjustment(order_id: i64, uid: i64, currency: i32, amount: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::BalanceAdjustment, order_id, uid, symbol: currency, price: amount, ..Default::default() }
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

    fn cmd_place_order(order_id: i64, uid: i64, symbol: i32, price: i64, size: i64, action: OrderAction, ts: i64) -> OrderCommand {
        let reserve_bid_price = if action == OrderAction::Bid { price } else { 0 };
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id,
            uid,
            symbol,
            price,
            size,
            reserve_bid_price,
            action: Some(action),
            order_type: Some(OrderType::Gtc),
            timestamp: ts,
            ..Default::default()
        }
    }

    fn cmd_markprice(symbol: i32, price: i64, ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol, price, timestamp: ts, ..Default::default() }
    }

    #[test]
    fn loan_index_rebuilds_after_snapshot_recovery_targeted_still_triggers_force_sell() {

        let shared = InMemorySerializationProcessor::new();
        {
            let mut core = ExchangeCore::new(); core.with_serialization_processor(Box::new(shared.clone()));
            core.ssp.add_currency(CoreCurrencySpecification { currency: ETH, currency_scale_k: 1, ..Default::default() });
            core.ssp.add_currency(CoreCurrencySpecification { currency: XBT, currency_scale_k: 1, ..Default::default() });
            let spec = eth_xbt_loan_spec();
            assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
            core.matching.add_symbol(&spec);
            core.risk.last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(OPEN_MARK));

            assert_eq!(submit(&mut core, cmd_pool_deposit(5000, XBT, POOL_FUND)), CommandResultCode::Success);
            core.ups.add_empty_user_profile(BORROWER);
            core.ups.add_empty_user_profile(LP);
            assert_eq!(submit(&mut core, cmd_balance_adjustment(1, BORROWER, ETH, ETH_COLLATERAL)), CommandResultCode::Success);
            assert_eq!(submit(&mut core, cmd_balance_adjustment(2, LP, XBT, ETH_COLLATERAL * OPEN_MARK * 2)), CommandResultCode::Success);

            assert_eq!(
                submit(&mut core, cmd_loan_create(2_000_002, BORROWER, SYMBOL, LOAN_ID, ETH_COLLATERAL, XBT_PRINCIPAL, 1_000)),
                CommandResultCode::Success
            );
            assert!(core.query_total_balance().is_global_zero(), "should be conserved before snapshot");
            assert!(core.persist(1, 0));
        }

        let mut r = ExchangeCore::new(); r.with_serialization_processor(Box::new(shared.clone()));
        r.recover(1, 0);

        assert!(
            r.risk
                .liquidation_engine
                .loan_liquidation_engine
                .isolated_loan_symbol_to_users
                .get(&SYMBOL)
                .is_some_and(|users| users.contains(&BORROWER)),
            "after recovery, isolated targeted index was not rebuilt or did not match the loan-only user"
        );
        assert!(r.query_total_balance().is_global_zero(), "should be conserved after recovery");

        assert_eq!(
            submit(&mut r, cmd_place_order(2000, LP, SYMBOL, CRASH_MARK, ETH_COLLATERAL, OrderAction::Bid, 1_500)),
            CommandResultCode::Success
        );

        r.risk.liquidation_engine.is_running = true;

        assert_eq!(submit(&mut r, cmd_markprice(SYMBOL, CRASH_MARK, 2_000)), CommandResultCode::Success);

        let collateral_now = r
            .ups
            .get(BORROWER)
            .and_then(|up| up.isolated_loans.get(&LOAN_ID))
            .map(|l| l.collateral_amount)
            .unwrap_or(0);
        assert!(
            collateral_now < ETH_COLLATERAL,
            "after snapshot recovery, a collateral price crash should still trigger immediate liquidation via the targeted path; unchanged collateral means the loan index was not rebuilt/missed after recovery (now={collateral_now})"
        );

        assert!(r.query_total_balance().is_global_zero(), "global balance should still be conserved after targeted liquidation post-recovery");
    }
}
