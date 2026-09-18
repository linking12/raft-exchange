#[cfg(test)]
mod tests {
    use exchange_core_rs::core::common::last_price_cache_record::LastPriceCacheRecord;
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_currency_specification::CoreCurrencySpecification;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::fund_event::{FundEvent, FundEventType};
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_core::ExchangeCore;

    const WBTC: i32 = 710;
    const USDT: i32 = 711;
    const SYMBOL: i32 = 71010;
    const MARK_PRICE: i64 = 50_000;
    const BORROWER: i64 = 8001;
    const COLLATERAL: i64 = 300;
    const PRINCIPAL: i64 = 80_000;
    const YEAR_MS: i64 = 365 * 24 * 3600 * 1_000;

    fn submit(core: &mut ExchangeCore, mut cmd: OrderCommand) -> (CommandResultCode, OrderCommand) {
        core.process_command(&mut cmd);
        let rc = cmd.result_code.expect("every command produces a result code");
        (rc, cmd)
    }

    fn find_event(cmd: &OrderCommand, want: FundEventType) -> &FundEvent {
        cmd.fund_events.iter().find(|e| e.event_type == want).unwrap_or_else(|| panic!("did not receive {want:?} event"))
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

    fn cmd_loan_repay(order_id: i64, uid: i64, loan_id: i64, repay_amount: i64, ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::LoanRepay, order_id, uid, price: repay_amount, reserve_bid_price: loan_id, timestamp: ts, ..Default::default() }
    }

    fn cmd_loan_cross_add_collateral(order_id: i64, uid: i64, currency: i32, amount: i64, ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::LoanCrossAddCollateral, order_id, uid, symbol: currency, size: amount, timestamp: ts, ..Default::default() }
    }

    fn cmd_loan_cross_borrow(order_id: i64, uid: i64, symbol: i32, loan_id: i64, principal: i64, ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::LoanCrossBorrow, order_id, uid, symbol, price: principal, reserve_bid_price: loan_id, timestamp: ts, ..Default::default() }
    }

    fn cmd_pool_deposit(order_id: i64, currency: i32, amount: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::PoolDeposit, order_id, symbol: currency, size: amount, ..Default::default() }
    }

    fn cmd_balance_adjustment(order_id: i64, uid: i64, currency: i32, amount: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::BalanceAdjustment, order_id, uid, symbol: currency, price: amount, ..Default::default() }
    }

    fn spot_spec(symbol_id: i32, base: i32, quote: i32) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: base,
            quote_currency: quote,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    fn boot() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: WBTC, currency_scale_k: 100, collateral_weight_bps: 10_000, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: USDT, currency_scale_k: 1, ..Default::default() });
        let mut spec = spot_spec(SYMBOL, WBTC, USDT);
        spec.loan_config.update(6_000, 8_500, 7_500, i64::MAX, 365);
        assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&spec);
        core.risk.last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(MARK_PRICE));
        core.risk.loan_service.global_config.numeraire_currency = USDT;
        let (rc, _) = submit(&mut core, cmd_pool_deposit(1, USDT, 1_000_000));
        assert_eq!(rc, CommandResultCode::Success);
        core.ups.add_empty_user_profile(BORROWER);
        core
    }

    fn create_loan(core: &mut ExchangeCore, ts: i64) -> (CommandResultCode, OrderCommand) {
        submit(core, cmd_loan_create(10, BORROWER, SYMBOL, 1, COLLATERAL, PRINCIPAL, ts))
    }

    #[test]
    fn loan_borrow_carries_both_sides_balances() {
        let mut core = boot();
        let (rc, _) = submit(&mut core, cmd_balance_adjustment(5, BORROWER, WBTC, COLLATERAL));
        assert_eq!(rc, CommandResultCode::Success);
        let (rc, cmd) = create_loan(&mut core, 1_000);
        assert_eq!(rc, CommandResultCode::Success);

        let s = find_event(&cmd, FundEventType::LoanBorrow);
        assert_eq!(s.currency, USDT, "debt side currency = loan currency");
        assert_eq!(s.currency_scale_k, 1, "loan currency scale (digit=0)");
        assert_eq!(s.free, PRINCIPAL, "loan currency free after disbursement = principal");
        assert_eq!(s.locked, 0, "loan currency has no lock");
        assert_eq!(s.loan_debt_principal, PRINCIPAL, "debt principal = disbursed amount");
        assert_eq!(s.loan_collateral_currency, WBTC, "collateral side currency = collateral currency");
        assert_eq!(s.loan_collateral_currency_scale_k, 100, "collateral currency scale (digit=2) != loan currency scale");
        assert_eq!(s.loan_collateral_pledged, COLLATERAL, "pledged collateral");
        assert_eq!(s.loan_collateral_locked, COLLATERAL, "collateral is virtually locked -> counted into collateral currency locked amount");
        assert_eq!(s.loan_collateral_free, 0, "collateral currency free drops to zero once fully pledged (accounts balance untouched)");
        assert_eq!(s.loan_ltv_bps, 5333, "LTV (bps)");
    }

    #[test]
    fn loan_repay_carries_both_sides_and_interest_paid() {
        let mut core = boot();
        let (rc, _) = submit(&mut core, cmd_balance_adjustment(5, BORROWER, WBTC, COLLATERAL));
        assert_eq!(rc, CommandResultCode::Success);
        create_loan(&mut core, 1_000);
        let (rc, cmd) = submit(&mut core, cmd_loan_repay(11, BORROWER, 1, 30_000, 1_000));
        assert_eq!(rc, CommandResultCode::Success);

        let s = find_event(&cmd, FundEventType::LoanRepay);
        assert_eq!(s.loan_debt_principal, PRINCIPAL - 30_000, "remaining principal");
        assert_eq!(s.free, PRINCIPAL - 30_000, "loan currency free = disbursed - repaid");
        assert_eq!(s.loan_interest_paid_total, 0, "same timestamp -> no interest this time");
        assert_eq!(s.loan_collateral_pledged, COLLATERAL, "repayment doesn't affect collateral");
        assert_eq!(s.loan_collateral_locked, COLLATERAL);
        assert_eq!(s.loan_collateral_free, 0);
    }

    #[test]
    fn interest_paid_total_is_monotonic_cumulative() {
        let mut core = boot();
        core.risk.loan_service.floating_rate.base_bps = 1_200;
        core.risk.loan_service.floating_rate.kink_util_bps = 8_000;
        core.risk.loan_service.floating_rate.slope1_bps = 0;
        core.risk.loan_service.floating_rate.slope2_bps = 0;

        let (rc, _) = submit(&mut core, cmd_balance_adjustment(5, BORROWER, WBTC, COLLATERAL));
        assert_eq!(rc, CommandResultCode::Success);
        create_loan(&mut core, 1_000);
        let (rc, _) = submit(&mut core, cmd_balance_adjustment(6, BORROWER, USDT, 50_000));
        assert_eq!(rc, CommandResultCode::Success);

        let (rc, cmd1) = submit(&mut core, cmd_loan_repay(12, BORROWER, 1, 10_000, 1_000 + YEAR_MS));
        assert_eq!(rc, CommandResultCode::Success);
        let cum1 = find_event(&cmd1, FundEventType::LoanRepay).loan_interest_paid_total;

        let (rc, cmd2) = submit(&mut core, cmd_loan_repay(13, BORROWER, 1, 10_000, 1_000 + 2 * YEAR_MS));
        assert_eq!(rc, CommandResultCode::Success);
        let cum2 = find_event(&cmd2, FundEventType::LoanRepay).loan_interest_paid_total;

        assert!(cum2 >= cum1, "cumulative interest paid must be monotonically non-decreasing: cum1={cum1} cum2={cum2}");
        assert!(cum2 - cum1 >= 0, "difference between adjacent snapshots = interest actually paid this time");
        assert!(cum1 > 0, "with base=1200bps accruing for 1yr, this repayment should indeed carry interest");
    }

    #[test]
    fn cross_borrow_collateral_side_all_zero() {
        let mut core = boot();
        let (rc, _) = submit(&mut core, cmd_balance_adjustment(5, BORROWER, WBTC, COLLATERAL));
        assert_eq!(rc, CommandResultCode::Success);
        let (rc, _) = submit(&mut core, cmd_loan_cross_add_collateral(10, BORROWER, WBTC, COLLATERAL, 1_000));
        assert_eq!(rc, CommandResultCode::Success);
        let (rc, cmd) = submit(&mut core, cmd_loan_cross_borrow(11, BORROWER, SYMBOL, 9, PRINCIPAL, 1_000));
        assert_eq!(rc, CommandResultCode::Success);

        let s = find_event(&cmd, FundEventType::LoanBorrow);
        assert_eq!(s.currency, USDT);
        assert_eq!(s.free, PRINCIPAL, "disbursement goes into loan currency free");
        assert_eq!(s.loan_debt_principal, PRINCIPAL);
        assert_eq!(s.loan_collateral_currency, 0, "cross borrow has no single collateral currency -> currency is 0");
        assert_eq!(s.loan_collateral_currency_scale_k, 0, "currency is 0 -> scale is also 0 (zero-guard in effect)");
        assert_eq!(s.loan_collateral_pledged, 0);
        assert_eq!(s.loan_collateral_free, 0);
        assert_eq!(s.loan_collateral_locked, 0);
    }

    #[test]
    fn cross_add_collateral_debt_side_zero_collateral_side_filled() {
        let mut core = boot();
        let (rc, _) = submit(&mut core, cmd_balance_adjustment(5, BORROWER, WBTC, COLLATERAL));
        assert_eq!(rc, CommandResultCode::Success);
        let (rc, cmd) = submit(&mut core, cmd_loan_cross_add_collateral(10, BORROWER, WBTC, COLLATERAL, 1_000));
        assert_eq!(rc, CommandResultCode::Success);

        let s = find_event(&cmd, FundEventType::LoanCollateralChange);
        assert_eq!(s.currency, 0, "cross add-collateral has no single loan currency -> currency is 0");
        assert_eq!(s.currency_scale_k, 0, "currency is 0 -> scale is also 0 (zero-guard in effect)");
        assert_eq!(s.free, 0);
        assert_eq!(s.locked, 0);
        assert_eq!(s.loan_debt_principal, 0);
        assert_eq!(s.loan_collateral_currency, WBTC);
        assert_eq!(s.loan_collateral_currency_scale_k, 100);
        assert_eq!(s.loan_collateral_pledged, COLLATERAL, "account-level collateral pool balance for this currency");
        assert_eq!(s.loan_collateral_locked, COLLATERAL, "collateral virtually locked");
        assert_eq!(s.loan_collateral_free, 0);
    }
}
