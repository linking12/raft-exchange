//! 对应 Java 测试类 `ITLoanDynamicRate.java` 的移植：验证动态利率端到端链路——
//! ① `ADD_LOAN` 的 rateCurve 配置流到开仓利率（FLOATING = 曲线 base，LOCKED = base + lockedAdjust）；
//! ② `REPRICE_LOAN_RATES` 把池利用率经 kinked 曲线写进 `currentRateBps`，后续新 FLOATING 贷款按新曲线值开仓；
//! ③ 非法的 rateCurve / symbol 配置被静默跳过，不覆盖既有的 good 配置；
//! ④ `ofMarket` 派生阈值、`ofRateCurvePreset` 预设曲线在借款开仓时被实际强制执行。

#[cfg(test)]
mod tests {
    use exchange_core_rs::core::common::last_price_cache_record::LastPriceCacheRecord;
    use exchange_core_rs::core::common::batch_add_loan_command::{
        BatchAddLoanCommand, GlobalLoanConfig, RateCurveConfig, SymbolLoanConfig, UNSET, UNSET_AMOUNT,
    };
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_currency_specification::CoreCurrencySpecification;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::isolated_loan_record::LoanRateMode;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_core::ExchangeCore;

    const BTC: i32 = 1;
    const USDT: i32 = 2;
    const SYMBOL: i32 = 100;
    const MARK_PRICE: i64 = 50_000;
    const POOL_FUND: i64 = 10_000_000;
    const BORROWER: i64 = 8001;

    const KINK: i32 = 8000;
    const SLOPE1: i32 = 400;
    const SLOPE2: i32 = 6000;

    fn submit(core: &mut ExchangeCore, mut cmd: OrderCommand) -> (CommandResultCode, OrderCommand) {
        core.process_command(&mut cmd);
        let rc = cmd.result_code.expect("every command produces a result code");
        (rc, cmd)
    }

    fn apply_batch(core: &mut ExchangeCore, cmd: BatchAddLoanCommand) {
        core.risk.apply_add_loan(&cmd, &mut core.ssp);
    }

    fn of_global_numeraire(currency: i32) -> BatchAddLoanCommand {
        BatchAddLoanCommand {
            global: Some(GlobalLoanConfig {
                numeraire_currency: currency,
                cross_liquidation_ltv_bps: 0,
                cross_margin_call_ltv_bps: 0,
                loan_pool_utilization_cap_bps: 0,
                loan_liquidation_fee_bps: 0,
                ltv_liquidation_buffer_bps: 0,
                ltv_margin_call_buffer_bps: 0,
            }),
            ..Default::default()
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn of_symbol(symbol_id: i32, initial: i32, liq: i32, margin_call: i32, max_amount: i64, term_days: i32, weight: i32) -> BatchAddLoanCommand {
        BatchAddLoanCommand {
            symbol: Some(SymbolLoanConfig {
                symbol_id,
                loan_initial_ltv_bps: initial,
                loan_liquidation_ltv_bps: liq,
                loan_margin_call_ltv_bps: margin_call,
                loan_max_amount: max_amount,
                loan_max_term_days: term_days,
                collateral_weight_bps: weight,
            }),
            ..Default::default()
        }
    }

    fn of_market(symbol_id: i32, initial: i32) -> BatchAddLoanCommand {
        BatchAddLoanCommand {
            symbol: Some(SymbolLoanConfig {
                symbol_id,
                loan_initial_ltv_bps: initial,
                loan_liquidation_ltv_bps: UNSET,
                loan_margin_call_ltv_bps: UNSET,
                loan_max_amount: UNSET_AMOUNT,
                loan_max_term_days: UNSET,
                collateral_weight_bps: UNSET,
            }),
            ..Default::default()
        }
    }

    fn of_rate_curve(base: i32, kink: i32, slope1: i32, slope2: i32, locked_adjust: i32) -> BatchAddLoanCommand {
        BatchAddLoanCommand {
            rate_curve: Some(RateCurveConfig { base_bps: base, kink_util_bps: kink, slope1_bps: slope1, slope2_bps: slope2, locked_rate_adjust_bps: locked_adjust }),
            ..Default::default()
        }
    }

    fn cmd_pool_deposit(order_id: i64, currency: i32, amount: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::PoolDeposit, order_id, symbol: currency, size: amount, ..Default::default() }
    }

    fn cmd_balance_adjustment(order_id: i64, uid: i64, currency: i32, amount: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::BalanceAdjustment, order_id, uid, symbol: currency, price: amount, ..Default::default() }
    }

    fn cmd_reprice(ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::RepriceLoanRates, timestamp: ts, ..Default::default() }
    }

    #[allow(clippy::too_many_arguments)]
    fn cmd_loan_create(order_id: i64, loan_id: i64, principal: i64, floating: bool, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCreate,
            order_id,
            uid: BORROWER,
            symbol: SYMBOL,
            size: 100,
            price: principal,
            reserve_bid_price: loan_id,
            user_cookie: if floating { LoanRateMode::Floating.code() as i32 } else { 0 },
            timestamp: ts,
            ..Default::default()
        }
    }

    fn spot_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BTC,
            quote_currency: USDT,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    // 搭建带贷款配置的现货 symbol：good symbol loanConfig + numeraire=USDT + rateCurve(base, locked_adjust)，
    // 池子注资 POOL_FUND，借款人预存 300 BTC 抵押。
    fn boot(base: i32, locked_adjust: i32) -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: BTC, currency_scale_k: 1, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: USDT, currency_scale_k: 1, ..Default::default() });
        let spec = spot_spec();
        assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&spec);
        core.risk.last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(MARK_PRICE));

        apply_batch(&mut core, of_symbol(SYMBOL, 6000, 8500, 7500, i64::MAX, 365, 10_000));
        apply_batch(&mut core, of_global_numeraire(USDT));
        apply_batch(&mut core, of_rate_curve(base, KINK, SLOPE1, SLOPE2, locked_adjust));

        let (rc, _) = submit(&mut core, cmd_pool_deposit(1, USDT, POOL_FUND));
        assert_eq!(rc, CommandResultCode::Success);
        core.ups.add_empty_user_profile(BORROWER);
        let (rc, _) = submit(&mut core, cmd_balance_adjustment(5, BORROWER, BTC, 300));
        assert_eq!(rc, CommandResultCode::Success);
        core
    }

    fn create_loan(core: &mut ExchangeCore, order_id: i64, loan_id: i64, principal: i64, floating: bool) -> CommandResultCode {
        submit(core, cmd_loan_create(order_id, loan_id, principal, floating, 1_000)).0
    }

    fn loan_rate_bps(core: &ExchangeCore, loan_id: i64) -> i32 {
        core.ups
            .get(BORROWER)
            .unwrap()
            .isolated_loans
            .get(&loan_id)
            .unwrap_or_else(|| panic!("isolated loan not found: {loan_id}"))
            .rate_bps
    }

    // 对应 Java rateCurveConfig_flowsToOpenRate()：未 reprice 时 FLOATING 开仓率回退曲线 base，
    // LOCKED 开仓率 = base + lockedAdjust。
    #[test]
    fn rate_curve_config_flows_to_open_rate() {
        let mut core = boot(300, 50);
        assert_eq!(create_loan(&mut core, 10, 1, 1_000_000, true), CommandResultCode::Success);
        assert_eq!(create_loan(&mut core, 11, 2, 1_000_000, false), CommandResultCode::Success);
        assert_eq!(loan_rate_bps(&core, 1), 300, "FLOATING open rate = curve base (fallback before reprice)");
        assert_eq!(loan_rate_bps(&core, 2), 350, "LOCKED open rate = base + lockedAdjust");
    }

    // 对应 Java reprice_utilizationToCurve_updatesNextFloatingOpenRate()：REPRICE_LOAN_RATES 把池利用率
    // 经 kinked 曲线写入 currentRateBps 后，之后新建的 FLOATING 贷款按新曲线值开仓。
    #[test]
    fn reprice_utilization_to_curve_updates_next_floating_open_rate() {
        let mut core = boot(200, 0);
        assert_eq!(create_loan(&mut core, 10, 1, 800_000, true), CommandResultCode::Success);
        assert_eq!(loan_rate_bps(&core, 1), 200, "FLOATING rate before reprice = base");

        let (rc, _) = submit(&mut core, cmd_reprice(1_000));
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(*core.risk.loan_service.floating_rate.current_rate_bps.get(&USDT).unwrap(), 240, "reprice writes curve value 240");

        assert_eq!(create_loan(&mut core, 11, 2, 100_000, true), CommandResultCode::Success);
        assert_eq!(loan_rate_bps(&core, 2), 240, "new FLOATING rate after reprice = curve(util) = 240");
    }

    // 对应 Java rateCurveConfig_invalidRejected_keepsGoodCurve()：非法曲线（kink 越界）被 RiskEngine dispatch
    // 静默跳过，既有 good 曲线（base=300 / base+adjust=350）原样保留，不被哨兵值 999 覆盖。
    #[test]
    fn rate_curve_config_invalid_rejected_keeps_good_curve() {
        let mut core = boot(300, 50);
        apply_batch(&mut core, of_rate_curve(999, 10_000, 400, 6000, 0));

        assert_eq!(create_loan(&mut core, 10, 1, 1_000_000, true), CommandResultCode::Success);
        assert_eq!(create_loan(&mut core, 11, 2, 1_000_000, false), CommandResultCode::Success);
        assert_eq!(loan_rate_bps(&core, 1), 300, "invalid curve skipped: FLOATING rate stays at good base=300");
        assert_eq!(loan_rate_bps(&core, 2), 350, "invalid curve skipped: LOCKED rate stays at good base+adjust=350");
    }

    // 对应 Java symbolConfig_invalidRejected_keepsGoodConfig()：非法 symbol 配置（liquidation ≤ initial）
    // 被静默跳过，既有 good 配置（含 loanMaxAmount=MAX）原样保留，大额借款仍能正常开仓。
    #[test]
    fn symbol_config_invalid_rejected_keeps_good_config() {
        let mut core = boot(300, 50);
        apply_batch(&mut core, of_symbol(SYMBOL, 6000, 5000, 0, 1, 365, 10_000));

        assert_eq!(create_loan(&mut core, 10, 1, 1_000_000, true), CommandResultCode::Success);
        assert_eq!(loan_rate_bps(&core, 1), 300, "invalid symbol config skipped: loan opens normally under good config, rate = curve base=300");
    }

    // 对应 Java ofMarket_derivesThresholds_borrowRespectsDerivedInitialLtv()：ofMarket 只设 initialLtv，
    // 其余阈值走派生，借款在派生出的 initialLtv 上限内应成功。
    #[test]
    fn of_market_derives_thresholds_borrow_respects_derived_initial_ltv() {
        let mut core = boot(200, 0);
        apply_batch(&mut core, of_market(SYMBOL, 6000));
        assert_eq!(create_loan(&mut core, 50, 50, 2_900_000, true), CommandResultCode::Success);
    }

    // 对应 Java ofMarket_borrowAboveDerivedInitialLtv_rejected()：借款超过派生出的 initialLtv 上限被拒绝。
    #[test]
    fn of_market_borrow_above_derived_initial_ltv_rejected() {
        let mut core = boot(200, 0);
        apply_batch(&mut core, of_market(SYMBOL, 6000));
        assert_eq!(create_loan(&mut core, 51, 51, 3_100_000, true), CommandResultCode::LoanLtvTooHigh);
    }

    // 对应 Java ofRateCurvePreset_standard_floatingOpensAtPresetBase()：用 STANDARD 预设覆盖既有的
    // 非标曲线（base=999）后，新开的 FLOATING 贷款按预设 base=200 开仓。
    #[test]
    fn of_rate_curve_preset_standard_floating_opens_at_preset_base() {
        let mut core = boot(999, 0);
        apply_batch(&mut core, of_rate_curve(200, 8000, 400, 6000, 0));
        assert_eq!(create_loan(&mut core, 52, 52, 100_000, true), CommandResultCode::Success);
        assert_eq!(loan_rate_bps(&core, 52), 200, "STANDARD preset base=200");
    }
}
