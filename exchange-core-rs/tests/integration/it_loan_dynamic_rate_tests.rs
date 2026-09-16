//! 翻译自 Java `exchange.core2.tests.integration.ITLoanDynamicRate`（7 个 @Test）——动态利率端到端：
//! ① `ADD_LOAN` 的 rateCurve 配置流到开仓利率（FLOATING=曲线 base、LOCKED=base+lockedAdjust）；
//! ② `REPRICE_LOAN_RATES` 把池利用率过 kinked 曲线写进 currentRateBps，后续新 FLOATING 贷款按新曲线值开仓；
//! ③ 非法 rateCurve/symbol 配置被 `apply_add_loan` 的 `valid()`/`resolve().valid()` 静默跳过，既有 good 配置原样保留；
//! ④ `ofMarket` 只设 initialLtv，liquidation/marginCall/weight 走 `resolve` 派生并被 LTV 校验实际强制。
//!
//! **harness 说明**：Java 的 `BatchAddLoanCommand`（binary data command）在 Rust 无组帧命令入口，直接经
//! `RiskEngine::apply_add_loan(&cmd, &mut ssp)` 配置（`ExchangeCore` 直连，全 `pub`）——它正是承载 valid()/resolve()
//! 校验的落地点，故非法配置的"跳过保留"路径由此忠实覆盖。Java 的 `ApiNop` 排空屏障在单线程直调引擎里不需要：
//! REPRICE 的 R1(collect)+R2(apply) 在同一次 `process_command` 内顺序完成，reprice 后 currentRateBps 立即可读。
//! 观测口径：贷款开仓利率 `rate_bps` 锁定 = 开仓时曲线现值，直读 loan 记录。identity scale，数字直算。

#[cfg(test)]
mod tests {
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

    const BTC: i32 = 1; // 抵押币，digit 0 → scaleK 1
    const USDT: i32 = 2; // 借出币，digit 0 → scaleK 1
    const SYMBOL: i32 = 100;
    const MARK_PRICE: i64 = 50_000;
    const POOL_FUND: i64 = 10_000_000;
    const BORROWER: i64 = 8001;

    // 默认 kinked 曲线参数（对齐 FloatingRateModel 默认 slope/kink）。
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

    /// 对拍 Java `BatchAddLoanCommand.ofGlobalNumeraire`：只设 numeraire，其余 0 = 不改。
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

    /// 对拍 `ofSymbol(symbolId, initial, liq, marginCall, maxAmount, termDays, weight)`。
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

    /// 对拍 `ofMarket(symbolId, initialLtv)`：只给 initialLtv，其余 UNSET → resolve 派生。
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

    /// 对拍 `ofRateCurve(base, kink, slope1, slope2, lockedAdjust)`。
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
            size: 100, // collateralAmount=100
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

    /// 对拍 Java boot(base, lockedAdjust)：建币/建 symbol/markPrice/ofSymbol/ofGlobalNumeraire/ofRateCurve/池/建户。
    fn boot(base: i32, locked_adjust: i32) -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: BTC, currency_scale_k: 1, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: USDT, currency_scale_k: 1, ..Default::default() });
        let spec = spot_spec();
        assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&spec);
        core.risk.last_price_cache.insert(SYMBOL, MARK_PRICE);

        apply_batch(&mut core, of_symbol(SYMBOL, 6000, 8500, 7500, i64::MAX, 365, 10_000));
        apply_batch(&mut core, of_global_numeraire(USDT));
        apply_batch(&mut core, of_rate_curve(base, KINK, SLOPE1, SLOPE2, locked_adjust));

        let (rc, _) = submit(&mut core, cmd_pool_deposit(1, USDT, POOL_FUND));
        assert_eq!(rc, CommandResultCode::Success);
        core.ups.add_empty_user_profile(BORROWER);
        let (rc, _) = submit(&mut core, cmd_balance_adjustment(5, BORROWER, BTC, 300)); // 够 3 笔 100 BTC 抵押
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

    // ==========================================================================================
    // 1. rateCurveConfig_flowsToOpenRate
    // ==========================================================================================

    #[test]
    fn rate_curve_config_flows_to_open_rate() {
        // base=300 + lockedAdjust=50；未 reprice → currentRate 空 → 回退曲线 base
        let mut core = boot(300, 50);
        assert_eq!(create_loan(&mut core, 10, 1, 1_000_000, true), CommandResultCode::Success); // FLOATING
        assert_eq!(create_loan(&mut core, 11, 2, 1_000_000, false), CommandResultCode::Success); // LOCKED
        assert_eq!(loan_rate_bps(&core, 1), 300, "FLOATING 开仓率 = 曲线 base（未 reprice 回退）");
        assert_eq!(loan_rate_bps(&core, 2), 350, "LOCKED 开仓率 = base + lockedAdjust");
    }

    // ==========================================================================================
    // 2. reprice_utilizationToCurve_updatesNextFloatingOpenRate
    // ==========================================================================================

    #[test]
    fn reprice_utilization_to_curve_updates_next_floating_open_rate() {
        // 默认曲线 base=200 / kink=8000 / slope1=400 / slope2=6000
        let mut core = boot(200, 0);
        // loan1 借 800000 → util = 800000/10_000_000 = 800 bps；创建早于 reprice → 率 = base = 200
        assert_eq!(create_loan(&mut core, 10, 1, 800_000, true), CommandResultCode::Success);
        assert_eq!(loan_rate_bps(&core, 1), 200, "reprice 前 FLOATING 率 = base");

        // reprice：util=800（<kink）→ 200 + 400×800/8000 = 240，写入 currentRateBps[USDT]
        let (rc, _) = submit(&mut core, cmd_reprice(1_000));
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(*core.risk.loan_service.floating_rate.current_rate_bps.get(&USDT).unwrap(), 240, "reprice 写入曲线值 240");

        // loan2 创建于 reprice 后 → 率 = 曲线值 240（单线程 R1/R2 同步，无需 Java 的 ApiNop 屏障）
        assert_eq!(create_loan(&mut core, 11, 2, 100_000, true), CommandResultCode::Success);
        assert_eq!(loan_rate_bps(&core, 2), 240, "reprice 后新 FLOATING 率 = curve(util) = 240");
    }

    // ==========================================================================================
    // 3. rateCurveConfig_invalidRejected_keepsGoodCurve —— 非法曲线（kink=100% 越界）被 valid() 跳过。
    // ==========================================================================================

    #[test]
    fn rate_curve_config_invalid_rejected_keeps_good_curve() {
        let mut core = boot(300, 50); // good 曲线 base=300 / lockedAdjust=50
        // 非法曲线：kink=10000（=100%）越界 → RateCurveConfig::valid() = false → 跳过；base=999 是"若误应用"的哨兵
        apply_batch(&mut core, of_rate_curve(999, 10_000, 400, 6000, 0));

        assert_eq!(create_loan(&mut core, 10, 1, 1_000_000, true), CommandResultCode::Success); // FLOATING
        assert_eq!(create_loan(&mut core, 11, 2, 1_000_000, false), CommandResultCode::Success); // LOCKED
        assert_eq!(loan_rate_bps(&core, 1), 300, "非法曲线被跳过：FLOATING 率仍为 good base=300");
        assert_eq!(loan_rate_bps(&core, 2), 350, "非法曲线被跳过：LOCKED 率仍为 good base+adjust=350");
    }

    // ==========================================================================================
    // 4. symbolConfig_invalidRejected_keepsGoodConfig —— 非法阈值（liquidation ≤ initial）被 resolve().valid() 跳过。
    // ==========================================================================================

    #[test]
    fn symbol_config_invalid_rejected_keeps_good_config() {
        let mut core = boot(300, 50);
        // 非法 symbol：liquidation=5000 ≤ initial=6000 → Resolved::valid() = false → 跳过。maxAmount=1 是哨兵。
        apply_batch(&mut core, of_symbol(SYMBOL, 6000, 5000, 0, 1, 365, 10_000));

        // good 配置 maxAmount=MAX 未被覆盖 → principal=1_000_000 借款仍应 SUCCESS（哨兵生效则会 LoanPrincipalExceedsLimit）
        assert_eq!(create_loan(&mut core, 10, 1, 1_000_000, true), CommandResultCode::Success); // FLOATING
        assert_eq!(loan_rate_bps(&core, 1), 300, "非法 symbol 配置被跳过：借款按 good 配置正常开仓，率=曲线 base=300");
    }

    // ==========================================================================================
    // 5. ofMarket_derivesThresholds_borrowRespectsDerivedInitialLtv
    // ==========================================================================================

    #[test]
    fn of_market_derives_thresholds_borrow_respects_derived_initial_ltv() {
        let mut core = boot(200, 0);
        // 覆盖 boot 的 symbol 配置：改用 ofMarket 最小配置（只给 initialLtv=6000，其余派生）
        apply_batch(&mut core, of_market(SYMBOL, 6000));
        // markPrice=50000，抵押 100 BTC → 抵押价值 5,000,000；initialLtv=6000（60%）→ 上限 3,000,000。
        // principal=2,900,000 < 上限 → SUCCESS。
        assert_eq!(create_loan(&mut core, 50, 50, 2_900_000, true), CommandResultCode::Success);
    }

    // ==========================================================================================
    // 6. ofMarket_borrowAboveDerivedInitialLtv_rejected
    // ==========================================================================================

    #[test]
    fn of_market_borrow_above_derived_initial_ltv_rejected() {
        let mut core = boot(200, 0);
        apply_batch(&mut core, of_market(SYMBOL, 6000));
        // 借 3,100,000 > 上限 3,000,000 → LTV 超 initial → 拒绝
        assert_eq!(create_loan(&mut core, 51, 51, 3_100_000, true), CommandResultCode::LoanLtvTooHigh);
    }

    // ==========================================================================================
    // 7. ofRateCurvePreset_standard_floatingOpensAtPresetBase —— STANDARD 预设 base=200。
    // ==========================================================================================

    #[test]
    fn of_rate_curve_preset_standard_floating_opens_at_preset_base() {
        let mut core = boot(999, 0); // boot 设了个非标 base=999
        // 用 STANDARD 预设覆盖 → base 变回 200（kink=8000/slope1=400/slope2=6000/adjust=0）
        apply_batch(&mut core, of_rate_curve(200, 8000, 400, 6000, 0));
        assert_eq!(create_loan(&mut core, 52, 52, 100_000, true), CommandResultCode::Success); // FLOATING
        assert_eq!(loan_rate_bps(&core, 52), 200, "STANDARD 预设 base=200");
    }
}
