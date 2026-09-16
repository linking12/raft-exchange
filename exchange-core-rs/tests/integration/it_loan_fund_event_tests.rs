//! 翻译自 Java `exchange.core2.tests.integration.ITLoanFundEvent`（5 个 @Test）——loan 事件的两侧
//! 余额快照断言：借贷侧走通用 free/locked，抵押侧走 collateralFree/collateralLocked。
//!
//! 刻意选 currencyScale 不同的两个币（WBTC digit=2 → scaleK=100；USDT digit=0 → scaleK=1），证明两侧
//! 各自下发自己的 scale——只发一个 scale 的话抵押物金额在下游会还原错小数位。
//!
//! **harness 说明**：同 `it_loan_conservation_tests.rs`，loan 全局配置（numeraire / base 币 weight / rate
//! curve）经 `ExchangeCore` 直连（`ExchangeApi` 无可变入口）。fund event 从 `process_command` 后的 `cmd.fund_events`
//! 直接读取（对拍 Java `FundEventReport` 回调捕获）。Java 无 rateCurve 的用例走引擎默认 base=200bps，但配合
//! **同一时间戳**开仓+还款 → elapsed=0 → 利息恰为 0（对齐 Java 容器实时 ~0 elapsed 的 interest=0 断言）。

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

    const WBTC: i32 = 710; // 抵押币，digit=2 → currencyScaleK=100
    const USDT: i32 = 711; // 借款币，digit=0 → currencyScaleK=1
    const SYMBOL: i32 = 71010;
    const MARK_PRICE: i64 = 50_000;
    const BORROWER: i64 = 8001;
    const COLLATERAL: i64 = 300; // 3 WBTC（currencyScale）
    const PRINCIPAL: i64 = 80_000; // LTV = 80000 / (3 × 50000) = 53.3%
    const YEAR_MS: i64 = 365 * 24 * 3600 * 1_000;

    fn submit(core: &mut ExchangeCore, mut cmd: OrderCommand) -> (CommandResultCode, OrderCommand) {
        core.process_command(&mut cmd);
        let rc = cmd.result_code.expect("every command produces a result code");
        (rc, cmd)
    }

    fn find_event(cmd: &OrderCommand, want: FundEventType) -> &FundEvent {
        cmd.fund_events.iter().find(|e| e.event_type == want).unwrap_or_else(|| panic!("未收到 {want:?} 事件"))
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
            user_cookie: 0, // LOCKED（对齐 Java ApiLoanCreate 默认 rateMode）
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

    /// 对拍 Java boot()：loanConfig 6000/8500/7500/MAX/365, weight 10000；numeraire USDT；池 1M；建 BORROWER。
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

    // ==========================================================================================
    // 1. loanBorrow_carriesBothSidesBalances
    // ==========================================================================================

    #[test]
    fn loan_borrow_carries_both_sides_balances() {
        let mut core = boot();
        let (rc, _) = submit(&mut core, cmd_balance_adjustment(5, BORROWER, WBTC, COLLATERAL));
        assert_eq!(rc, CommandResultCode::Success);
        let (rc, cmd) = create_loan(&mut core, 1_000);
        assert_eq!(rc, CommandResultCode::Success);

        let s = find_event(&cmd, FundEventType::LoanBorrow);
        // 借贷侧（USDT）：放款直接进 accounts、不锁定 → free = 本金
        assert_eq!(s.currency, USDT, "借贷侧币种 = 借款币");
        assert_eq!(s.currency_scale_k, 1, "借款币 scale（digit=0）");
        assert_eq!(s.free, PRINCIPAL, "放款后借款币可用 = 本金");
        assert_eq!(s.locked, 0, "借款币无冻结");
        assert_eq!(s.loan_debt_principal, PRINCIPAL, "负债本金 = 放款额");
        // 抵押侧（WBTC）：抵押是虚拟锁定——accounts 不动，locked 增加、free 归零
        assert_eq!(s.loan_collateral_currency, WBTC, "抵押侧币种 = 抵押币");
        assert_eq!(s.loan_collateral_currency_scale_k, 100, "抵押币 scale（digit=2）≠ 借款币 scale");
        assert_eq!(s.loan_collateral_pledged, COLLATERAL, "已质押抵押物");
        assert_eq!(s.loan_collateral_locked, COLLATERAL, "抵押被虚拟锁定 → 计入抵押币冻结额");
        assert_eq!(s.loan_collateral_free, 0, "抵押占满后抵押币可用归零（accounts 未被扣减）");
        // LTV = 债务 / 抵押物市值 = 80000 / (3 × 50000) = 53.33% → 5333 bps
        assert_eq!(s.loan_ltv_bps, 5333, "LTV（bps）");
    }

    // ==========================================================================================
    // 2. loanRepay_carriesBothSidesAndInterestPaid —— 同一时间戳还款 → rate 无 elapsed → 利息 0。
    // ==========================================================================================

    #[test]
    fn loan_repay_carries_both_sides_and_interest_paid() {
        let mut core = boot();
        let (rc, _) = submit(&mut core, cmd_balance_adjustment(5, BORROWER, WBTC, COLLATERAL));
        assert_eq!(rc, CommandResultCode::Success);
        create_loan(&mut core, 1_000);
        // 还 30000（同一时间戳 → 0 利息，全部冲本金）
        let (rc, cmd) = submit(&mut core, cmd_loan_repay(11, BORROWER, 1, 30_000, 1_000));
        assert_eq!(rc, CommandResultCode::Success);

        let s = find_event(&cmd, FundEventType::LoanRepay);
        assert_eq!(s.loan_debt_principal, PRINCIPAL - 30_000, "剩余本金");
        assert_eq!(s.free, PRINCIPAL - 30_000, "借款币可用 = 放款 − 已还");
        assert_eq!(s.loan_interest_paid_total, 0, "同一时间戳 → 本次无利息");
        // 抵押未动，仍全额锁定
        assert_eq!(s.loan_collateral_pledged, COLLATERAL, "还款不影响抵押");
        assert_eq!(s.loan_collateral_locked, COLLATERAL);
        assert_eq!(s.loan_collateral_free, 0);
    }

    // ==========================================================================================
    // 3. interestPaidTotal_isMonotonicCumulative —— interestPaidTotal 单调递增快照，两次相减 = 本次实付利息。
    // ==========================================================================================

    #[test]
    fn interest_paid_total_is_monotonic_cumulative() {
        let mut core = boot();
        // 非零利率曲线 base=1200bps（LOCKED 贷款开仓即锁定此率），让还款真的产生利息
        core.risk.loan_service.floating_rate.base_bps = 1_200;
        core.risk.loan_service.floating_rate.kink_util_bps = 8_000;
        core.risk.loan_service.floating_rate.slope1_bps = 0;
        core.risk.loan_service.floating_rate.slope2_bps = 0;

        let (rc, _) = submit(&mut core, cmd_balance_adjustment(5, BORROWER, WBTC, COLLATERAL));
        assert_eq!(rc, CommandResultCode::Success);
        create_loan(&mut core, 1_000);
        let (rc, _) = submit(&mut core, cmd_balance_adjustment(6, BORROWER, USDT, 50_000)); // 备够还款资金
        assert_eq!(rc, CommandResultCode::Success);

        // 第一次还款：计息到 T1（+1yr）后利息优先
        let (rc, cmd1) = submit(&mut core, cmd_loan_repay(12, BORROWER, 1, 10_000, 1_000 + YEAR_MS));
        assert_eq!(rc, CommandResultCode::Success);
        let cum1 = find_event(&cmd1, FundEventType::LoanRepay).loan_interest_paid_total;

        // 第二次还款（再 +1yr）
        let (rc, cmd2) = submit(&mut core, cmd_loan_repay(13, BORROWER, 1, 10_000, 1_000 + 2 * YEAR_MS));
        assert_eq!(rc, CommandResultCode::Success);
        let cum2 = find_event(&cmd2, FundEventType::LoanRepay).loan_interest_paid_total;

        assert!(cum2 >= cum1, "累计已付利息必须单调不减：cum1={cum1} cum2={cum2}");
        assert!(cum2 - cum1 >= 0, "相邻两条相减 = 本次实付利息");
        assert!(cum1 > 0, "base=1200bps 计息 1yr 后本次还款应确有利息");
    }

    // ==========================================================================================
    // 4. crossBorrow_collateralSideAllZero —— cross 无唯一抵押币 → 抵押侧整组为 0（含币种）。
    // ==========================================================================================

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
        // 借贷侧照常
        assert_eq!(s.currency, USDT);
        assert_eq!(s.free, PRINCIPAL, "放款进借款币可用");
        assert_eq!(s.loan_debt_principal, PRINCIPAL);
        // 抵押侧整组为 0
        assert_eq!(s.loan_collateral_currency, 0, "cross borrow 无唯一抵押币 → 币种为 0");
        assert_eq!(s.loan_collateral_currency_scale_k, 0, "币种为 0 → scale 也为 0（zero-guard 生效）");
        assert_eq!(s.loan_collateral_pledged, 0);
        assert_eq!(s.loan_collateral_free, 0);
        assert_eq!(s.loan_collateral_locked, 0);
    }

    // ==========================================================================================
    // 5. crossAddCollateral_debtSideZero_collateralSideFilled —— cross 无唯一借款币 → 借贷侧整组为 0。
    // ==========================================================================================

    #[test]
    fn cross_add_collateral_debt_side_zero_collateral_side_filled() {
        let mut core = boot();
        let (rc, _) = submit(&mut core, cmd_balance_adjustment(5, BORROWER, WBTC, COLLATERAL));
        assert_eq!(rc, CommandResultCode::Success);
        let (rc, cmd) = submit(&mut core, cmd_loan_cross_add_collateral(10, BORROWER, WBTC, COLLATERAL, 1_000));
        assert_eq!(rc, CommandResultCode::Success);

        let s = find_event(&cmd, FundEventType::LoanCollateralChange);
        // 借贷侧整组为 0
        assert_eq!(s.currency, 0, "cross 加抵押无唯一借款币 → 币种为 0");
        assert_eq!(s.currency_scale_k, 0, "币种为 0 → scale 也为 0（zero-guard 生效）");
        assert_eq!(s.free, 0);
        assert_eq!(s.locked, 0);
        assert_eq!(s.loan_debt_principal, 0);
        // 抵押侧照常
        assert_eq!(s.loan_collateral_currency, WBTC);
        assert_eq!(s.loan_collateral_currency_scale_k, 100);
        assert_eq!(s.loan_collateral_pledged, COLLATERAL, "账户级抵押池该币余额");
        assert_eq!(s.loan_collateral_locked, COLLATERAL, "抵押虚拟锁定");
        assert_eq!(s.loan_collateral_free, 0);
    }
}
