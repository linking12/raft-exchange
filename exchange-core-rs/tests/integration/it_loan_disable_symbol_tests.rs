#[cfg(test)]

mod tests {
    use exchange_core_rs::core::common::last_price_cache_record::LastPriceCacheRecord;
    use exchange_core_rs::core::common::batch_add_loan_command::{BatchAddLoanCommand, SymbolLoanConfig, UNSET, UNSET_AMOUNT};
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_currency_specification::CoreCurrencySpecification;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_core::ExchangeCore;

    const ETH: i32 = 3928;
    const XBT: i32 = 3762;
    const SYMBOL: i32 = 9269;
    const BORROWER: i64 = 6101;
    const LOAN_ID: i64 = 88;
    const MARK: i64 = 1000;
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

    fn cmd_markprice(symbol: i32, price: i64, ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol, price, timestamp: ts, ..Default::default() }
    }

    fn disable_symbol_cmd(symbol_id: i32) -> BatchAddLoanCommand {
        BatchAddLoanCommand {
            global: None,
            symbol: Some(SymbolLoanConfig {
                symbol_id,
                loan_initial_ltv_bps: 0,
                loan_liquidation_ltv_bps: UNSET,
                loan_margin_call_ltv_bps: UNSET,
                loan_max_amount: UNSET_AMOUNT,
                loan_max_term_days: UNSET,
                collateral_weight_bps: UNSET,
            }),
            rate_curve: None,
        }
    }

    #[test]
    fn disable_symbol_blocks_new_loans_but_keeps_existing_unliquidated() {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: ETH, currency_scale_k: 1, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: XBT, currency_scale_k: 1, ..Default::default() });
        let spec = eth_xbt_loan_spec();
        assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&spec);
        core.risk.last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(MARK));

        assert_eq!(submit(&mut core, cmd_pool_deposit(5000, XBT, POOL_FUND)), CommandResultCode::Success);

        core.ups.add_empty_user_profile(BORROWER);
        assert_eq!(submit(&mut core, cmd_balance_adjustment(1, BORROWER, ETH, ETH_COLLATERAL * 2)), CommandResultCode::Success);

        assert_eq!(
            submit(&mut core, cmd_loan_create(1_000_001, BORROWER, SYMBOL, LOAN_ID, ETH_COLLATERAL, XBT_PRINCIPAL, 1_000)),
            CommandResultCode::Success
        );

        core.risk.apply_add_loan(&disable_symbol_cmd(SYMBOL), &mut core.ssp);
        {
            let cfg = core.ssp.get_symbol(SYMBOL).unwrap().loan_config;
            assert_eq!(cfg.initial_ltv_bps, 0, "disabling loans -> initial LTV zeroed");
            assert_eq!(cfg.liquidation_ltv_bps, 8_000, "liquidation LTV must be preserved, otherwise existing loans get liquidated as collateral damage");
            assert_eq!(cfg.margin_call_ltv_bps, 7_000, "margin-call LTV must be preserved");
        }

        assert_eq!(
            submit(&mut core, cmd_loan_create(1_000_002, BORROWER, SYMBOL, LOAN_ID + 1, ETH_COLLATERAL, XBT_PRINCIPAL, 1_000)),
            CommandResultCode::LoanNotEnabled
        );

        core.risk.liquidation_engine.is_running = true;
        assert_eq!(submit(&mut core, cmd_markprice(SYMBOL, MARK, 2_000)), CommandResultCode::Success);

        let collateral = core
            .ups
            .get(BORROWER)
            .and_then(|up| up.isolated_loans.get(&LOAN_ID))
            .map(|l| l.collateral_amount)
            .unwrap_or(-1);
        assert_eq!(
            collateral, ETH_COLLATERAL,
            "disabling loans must not touch existing balances: if liquidationLtv zeroed along with initialLtv, this collateral would be consumed by liquidation"
        );

        assert!(core.query_total_balance().is_global_zero(), "conservation must hold throughout the disable-loans flow");
    }
}
