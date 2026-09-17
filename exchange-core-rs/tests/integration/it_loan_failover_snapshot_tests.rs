#[cfg(test)]
mod tests {
    use exchange_core_rs::core::common::last_price_cache_record::LastPriceCacheRecord;
    use exchange_core_rs::core::common::batch_add_loan_command::{BatchAddLoanCommand, RateCurveConfig};
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_currency_specification::CoreCurrencySpecification;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::isolated_loan_record::LoanRateMode;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_core::ExchangeCore;

    fn submit(core: &mut ExchangeCore, mut cmd: OrderCommand) -> CommandResultCode {
        core.process_command(&mut cmd);
        cmd.result_code.expect("every command produces a result code")
    }

    fn spot_loan_spec(symbol_id: i32, base: i32, quote: i32, initial: i32, liquidation: i32, margin_call: i32) -> CoreSymbolSpecification {
        let mut spec = CoreSymbolSpecification {
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
        };
        spec.loan_config.update(initial, liquidation, margin_call, i64::MAX, 365);
        spec
    }

    fn cmd_pool_deposit(order_id: i64, currency: i32, amount: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::PoolDeposit, order_id, symbol: currency, size: amount, ..Default::default() }
    }

    fn cmd_balance_adjustment(order_id: i64, uid: i64, currency: i32, amount: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::BalanceAdjustment, order_id, uid, symbol: currency, price: amount, ..Default::default() }
    }

    #[allow(clippy::too_many_arguments)]
    fn cmd_loan_create(order_id: i64, uid: i64, symbol: i32, loan_id: i64, collateral: i64, principal: i64, floating: bool, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCreate,
            order_id,
            uid,
            symbol,
            size: collateral,
            price: principal,
            reserve_bid_price: loan_id,
            user_cookie: if floating { LoanRateMode::Floating.code() as i32 } else { 0 },
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

    fn cmd_loan_cross_add_collateral(order_id: i64, uid: i64, currency: i32, amount: i64, ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::LoanCrossAddCollateral, order_id, uid, symbol: currency, size: amount, timestamp: ts, ..Default::default() }
    }

    fn cmd_loan_cross_borrow(order_id: i64, uid: i64, symbol: i32, loan_id: i64, principal: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCrossBorrow,
            order_id,
            uid,
            symbol,
            price: principal,
            reserve_bid_price: loan_id,
            timestamp: ts,
            ..Default::default()
        }
    }

    fn cmd_reprice(ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::RepriceLoanRates, timestamp: ts, ..Default::default() }
    }

    const WBTC: i32 = 720;
    const USDT: i32 = 721;
    const SYMBOL: i32 = 72010;
    const MARK: i64 = 50_000;
    const POOL_FUND: i64 = 10_000_000;
    const BORROWER: i64 = 9001;
    const LP: i64 = 9002;

    #[test]
    fn loan_state_survives_snapshot_restore_identical_bytes_and_conserved() {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: WBTC, currency_scale_k: 100, collateral_weight_bps: 10_000, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: USDT, currency_scale_k: 1, ..Default::default() });
        let spec = spot_loan_spec(SYMBOL, WBTC, USDT, 6_000, 8_500, 7_500);
        assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&spec);
        core.risk.last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(MARK));
        core.risk.loan_service.global_config.numeraire_currency = USDT;

        assert_eq!(submit(&mut core, cmd_pool_deposit(5000, USDT, POOL_FUND)), CommandResultCode::Success);
        core.ups.add_empty_user_profile(BORROWER);
        core.ups.add_empty_user_profile(LP);
        assert_eq!(submit(&mut core, cmd_balance_adjustment(1, BORROWER, WBTC, 1_000)), CommandResultCode::Success);
        assert_eq!(submit(&mut core, cmd_balance_adjustment(2, LP, USDT, POOL_FUND)), CommandResultCode::Success);

        assert_eq!(
            submit(&mut core, cmd_loan_create(1_000_002, BORROWER, SYMBOL, 1, 300, 80_000, false, 1_000)),
            CommandResultCode::Success
        );
        assert_eq!(
            submit(&mut core, cmd_loan_force_liquidate(2, BORROWER, SYMBOL, 1, MARK, 3, 1_000)),
            CommandResultCode::Success
        );
        assert!(!core.ups.get(BORROWER).unwrap().isolated_loans.contains_key(&1), "全拒 → LIF 接管 → loan1 移除");

        assert_eq!(submit(&mut core, cmd_loan_cross_add_collateral(1_000_003, BORROWER, WBTC, 300, 1_000)), CommandResultCode::Success);
        assert_eq!(submit(&mut core, cmd_loan_cross_borrow(1_000_004, BORROWER, SYMBOL, 2, 60_000, 1_000)), CommandResultCode::Success);

        assert!(core.query_total_balance().is_global_zero(), "快照前应守恒");
        let (re, me) = core.to_snapshot_bytes();

        let recovered = ExchangeCore::from_snapshot_bytes(&re, &me);
        assert_eq!(
            recovered.to_snapshot_bytes(),
            (re.clone(), me.clone()),
            "恢复后复制态必须与原 leader 逐字节一致（loan records + 池子/LIF 桶都在快照里）"
        );
        assert!(recovered.query_total_balance().is_global_zero(), "恢复后应守恒");

        let up = recovered.ups.get(BORROWER).unwrap();
        assert_eq!(up.cross_loans.get(&2).map(|l| l.outstanding_principal), Some(60_000), "cross loan2 本金随快照存活");
        assert!(!up.isolated_loans.contains_key(&1));
        assert_eq!(recovered.risk.loan_service.get_loan_insurance_fund(USDT), -80_000, "接管产生的负 LIF 随快照存活");
    }

    const RC_BTC: i32 = 730;
    const RC_USDT: i32 = 731;
    const RC_SYMBOL: i32 = 73010;
    const RC_MARK: i64 = 50_000;
    const RC_POOL: i64 = 10_000_000;
    const RC_BORROWER: i64 = 9101;
    const RC_EXPECTED: i32 = 240;

    fn floating_loan_rate_bps(core: &ExchangeCore, loan_id: i64) -> i32 {
        core.ups.get(RC_BORROWER).unwrap().isolated_loans.get(&loan_id).expect("isolated loan not found").rate_bps
    }

    #[test]
    fn loan_rate_state_survives_snapshot_restore_repriced_curve_rate_preserved() {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: RC_BTC, currency_scale_k: 1, collateral_weight_bps: 10_000, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: RC_USDT, currency_scale_k: 1, ..Default::default() });
        let spec = spot_loan_spec(RC_SYMBOL, RC_BTC, RC_USDT, 6_000, 8_500, 7_500);
        assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&spec);
        core.risk.last_price_cache.insert(RC_SYMBOL, LastPriceCacheRecord::with_mark(RC_MARK));
        core.risk.loan_service.global_config.numeraire_currency = RC_USDT;
        core.risk.apply_add_loan(
            &BatchAddLoanCommand {
                global: None,
                symbol: None,
                rate_curve: Some(RateCurveConfig { base_bps: 200, kink_util_bps: 8_000, slope1_bps: 400, slope2_bps: 6_000, locked_rate_adjust_bps: 0 }),
            },
            &mut core.ssp,
        );

        assert_eq!(submit(&mut core, cmd_pool_deposit(5000, RC_USDT, RC_POOL)), CommandResultCode::Success);
        core.ups.add_empty_user_profile(RC_BORROWER);
        assert_eq!(submit(&mut core, cmd_balance_adjustment(1, RC_BORROWER, RC_BTC, 400)), CommandResultCode::Success);

        assert_eq!(
            submit(&mut core, cmd_loan_create(1_000_102, RC_BORROWER, RC_SYMBOL, 1, 100, 800_000, true, 1_000)),
            CommandResultCode::Success
        );

        assert_eq!(submit(&mut core, cmd_reprice(1_000)), CommandResultCode::Success);

        assert_eq!(
            submit(&mut core, cmd_loan_create(1_000_103, RC_BORROWER, RC_SYMBOL, 2, 100, 100_000, true, 1_000)),
            CommandResultCode::Success
        );
        assert_eq!(floating_loan_rate_bps(&core, 2), RC_EXPECTED, "快照前：reprice 后新 FLOATING 率 = curve(util) = 240");

        assert!(core.query_total_balance().is_global_zero(), "快照前应守恒");
        let (re, me) = core.to_snapshot_bytes();

        let mut r = ExchangeCore::from_snapshot_bytes(&re, &me);
        assert_eq!(
            r.to_snapshot_bytes(),
            (re.clone(), me.clone()),
            "恢复后复制态必须逐字节一致（currentRateBps / lastRepriceTs 都在快照里）"
        );

        assert_eq!(
            submit(&mut r, cmd_loan_create(1_000_104, RC_BORROWER, RC_SYMBOL, 3, 100, 100_000, true, 1_000)),
            CommandResultCode::Success
        );
        assert_eq!(
            floating_loan_rate_bps(&r, 3),
            RC_EXPECTED,
            "恢复后新 FLOATING 率 = curve(util) = 240（证明 currentRateBps 随快照存活，未重置为 base 200）"
        );
        assert!(r.query_total_balance().is_global_zero(), "恢复后应守恒");
    }
}
