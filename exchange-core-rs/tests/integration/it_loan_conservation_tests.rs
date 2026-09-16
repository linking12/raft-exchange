//! 翻译自 Java `exchange.core2.tests.integration.ITLoanConservation`（8 个 @Test）——loan 端到端
//! **资金守恒**集成测试。关键盲区：base 币 currencyScale ≠ 撮合 lotScale（WBTC digit=2 → currencyScaleK=100，
//! 而 SYMBOL baseScaleK=1），过去所有 identity-scale loan 测试测不出的 scale bug 真实条件。
//!
//! 断言两件事：① 强平后 exchangeLocked 归零（pre-move 的抵押被真正撮合消费/释放，没漂）；
//! ② `total_balance().is_global_zero()`（对拍 Java `totalBalanceReport().isGlobalBalancesAllZero()`）——
//! 报告已把 loan 平台桶（poolAvailable + interestRevenue + loanInsuranceFund）+ exchangeLocked + loanCollateral
//! 纳入对账。
//!
//! **harness 说明**：loan 全局配置（numeraire 基准币、base 币 collateral_weight_bps、rate curve）只能经
//! `ExchangeCore` 直连配置——`ExchangeApi` 无对应可变入口，故本文件直连引擎（`ExchangeCore`，全 `pub`），
//! 命令构造 helper 逐字复刻 `loan_e2e_tests.rs`。Java `BatchAddLoanCommand.ofSymbol(..., weight)` 的 weight
//! 落到 base 币的 `collateral_weight_bps`；`ofGlobalNumeraire` 落到 `global_config.numeraire_currency`。
//! markPrice 直写 `last_price_cache`（loan 估值读此处）。rate=0（无利息，简化守恒断言），fee=0。

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

    // 常量逐字对齐 Java ITLoanConservation。
    const WBTC: i32 = 710; // base：digit=2 → currencyScaleK=100
    const USDT: i32 = 711; // quote：digit=0 → currencyScaleK=1
    const SYMBOL: i32 = 71010; // baseScaleK=1 → 与 WBTC currencyScaleK=100 错配
    const MARK_PRICE: i64 = 50_000;
    const POOL_FUND: i64 = 10_000_000;
    const BORROWER: i64 = 8001;
    const LP: i64 = 8002;

    const COLLATERAL_WBTC: i64 = 300; // 3 WBTC（currencyScale）
    const COLLATERAL_LOTS: i64 = 3;
    const PRINCIPAL: i64 = 80_000; // LTV = 80000/150000 ≈ 53% < 60% initial
    const CROSS_PRINCIPAL: i64 = 60_000; // LTV = 60000/150000 = 40% < 60% initial

    // ==========================================================================================
    // 命令构造 helper（字段映射逐字复刻 loan_e2e_tests.rs）。
    // ==========================================================================================

    fn submit(core: &mut ExchangeCore, mut cmd: OrderCommand) -> (CommandResultCode, OrderCommand) {
        core.process_command(&mut cmd);
        let rc = cmd.result_code.expect("every command produces a result code");
        (rc, cmd)
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
            user_cookie: 0, // LOCKED
            timestamp: ts,
            ..Default::default()
        }
    }

    fn cmd_loan_repay(order_id: i64, uid: i64, loan_id: i64, repay_amount: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanRepay,
            order_id,
            uid,
            price: repay_amount,
            reserve_bid_price: loan_id,
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

    fn cmd_loan_cross_withdraw_collateral(order_id: i64, uid: i64, currency: i32, amount: i64, ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::LoanCrossWithdrawCollateral, order_id, uid, symbol: currency, size: amount, timestamp: ts, ..Default::default() }
    }

    fn cmd_loan_cross_borrow(order_id: i64, uid: i64, symbol: i32, loan_id: i64, principal: i64, ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::LoanCrossBorrow, order_id, uid, symbol, price: principal, reserve_bid_price: loan_id, timestamp: ts, ..Default::default() }
    }

    fn cmd_loan_cross_repay(order_id: i64, uid: i64, loan_id: i64, repay_amount: i64, ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::LoanCrossRepay, order_id, uid, price: repay_amount, reserve_bid_price: loan_id, timestamp: ts, ..Default::default() }
    }

    #[allow(clippy::too_many_arguments)]
    fn cmd_loan_cross_force_liquidate(order_id: i64, uid: i64, symbol: i32, target_loan_id: i64, price: i64, lots: i64, ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::LoanCrossForceLiquidate, order_id, uid, symbol, price, size: lots, reserve_bid_price: target_loan_id, timestamp: ts, ..Default::default() }
    }

    fn cmd_pool_deposit(order_id: i64, currency: i32, amount: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::PoolDeposit, order_id, symbol: currency, size: amount, ..Default::default() }
    }

    fn cmd_balance_adjustment(order_id: i64, uid: i64, currency: i32, amount: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::BalanceAdjustment, order_id, uid, symbol: currency, price: amount, ..Default::default() }
    }

    #[allow(clippy::too_many_arguments)]
    fn cmd_place_order(order_id: i64, uid: i64, symbol: i32, price: i64, size: i64, action: OrderAction, order_type: OrderType) -> OrderCommand {
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
            order_type: Some(order_type),
            ..Default::default()
        }
    }

    fn cmd_reset_fee(order_id: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::ResetFee, order_id, ..Default::default() }
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

    fn assert_conserved(core: &ExchangeCore, whence: &str) {
        assert!(core.query_total_balance().is_global_zero(), "全局资金守恒破裂 @ {whence} —— loan 平台桶已纳入对账");
    }

    // ==========================================================================================
    // boot：对拍 Java boot()——建币/建 symbol（loanConfig 6000/8500/7500/MAX/365, weight 10000）/
    // numeraire=USDT/池 10M/两用户/BORROWER 3 WBTC/LP 10M USDT。
    // ==========================================================================================

    fn boot() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: WBTC, currency_scale_k: 100, collateral_weight_bps: 10_000, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: USDT, currency_scale_k: 1, ..Default::default() });

        let mut spec = spot_spec(SYMBOL, WBTC, USDT);
        // ofSymbol(6000 initial / 8500 liq / 7500 marginCall / MAX / 365d)
        spec.loan_config.update(6_000, 8_500, 7_500, i64::MAX, 365);
        assert_eq!(core.ssp.add_symbol(spec.clone()), CommandResultCode::Success);
        core.matching.add_symbol(&spec);

        core.risk.last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(MARK_PRICE));
        core.risk.loan_service.global_config.numeraire_currency = USDT; // ofGlobalNumeraire(USDT)

        let (rc, _) = submit(&mut core, cmd_pool_deposit(1, USDT, POOL_FUND));
        assert_eq!(rc, CommandResultCode::Success);

        core.ups.add_empty_user_profile(BORROWER);
        core.ups.add_empty_user_profile(LP);

        let (rc, _) = submit(&mut core, cmd_balance_adjustment(5, BORROWER, WBTC, COLLATERAL_WBTC));
        assert_eq!(rc, CommandResultCode::Success);
        let (rc, _) = submit(&mut core, cmd_balance_adjustment(6, LP, USDT, POOL_FUND));
        assert_eq!(rc, CommandResultCode::Success);
        core
    }

    fn create_loan(core: &mut ExchangeCore, loan_id: i64) {
        let (rc, _) = submit(core, cmd_loan_create(10, BORROWER, SYMBOL, loan_id, COLLATERAL_WBTC, PRINCIPAL, 1_000));
        assert_eq!(rc, CommandResultCode::Success);
    }

    fn place_lp_bid(core: &mut ExchangeCore, order_id: i64, lots: i64) {
        let (rc, _) = submit(core, cmd_place_order(order_id, LP, SYMBOL, MARK_PRICE, lots, OrderAction::Bid, OrderType::Gtc));
        assert_eq!(rc, CommandResultCode::Success);
    }

    // ==========================================================================================
    // 1. fullLiquidation_nonIdentityScale_conserves
    // ==========================================================================================

    #[test]
    fn full_liquidation_non_identity_scale_conserves() {
        let mut core = boot();
        assert_conserved(&core, "after setup");
        create_loan(&mut core, 1);
        assert_conserved(&core, "after loan create");
        place_lp_bid(&mut core, 1000, COLLATERAL_LOTS); // 吃满 3 lot

        let (rc, _) = submit(&mut core, cmd_loan_force_liquidate(2000, BORROWER, SYMBOL, 1, MARK_PRICE, COLLATERAL_LOTS, 2_000));
        assert_eq!(rc, CommandResultCode::Success);

        let borrower = core.ups.get(BORROWER).unwrap();
        assert_eq!(borrower.locked(WBTC), 0, "exchangeLocked[WBTC] 未归零");
        assert_eq!(borrower.account(WBTC), 0, "3 WBTC 抵押应全部卖出");
        assert_eq!(core.ups.get(LP).unwrap().account(WBTC), COLLATERAL_WBTC, "LP 应收 3 WBTC");

        // proceeds=3×50000=150000，liqFee=2%=3000，本金 80000 回池，overpay 留用户 → 借款人 USDT = proceeds − liqFee = 147000。
        let proceeds = COLLATERAL_LOTS * MARK_PRICE;
        let liq_fee = proceeds * 200 / 10_000;
        assert_eq!(core.ups.get(BORROWER).unwrap().account(USDT), proceeds - liq_fee, "借款人 USDT 结算额错（应为 proceeds−liqFee）");
        assert_conserved(&core, "after full liquidation");
    }

    // ==========================================================================================
    // 2. partialFillLiquidation_nonIdentityScale_conserves
    // ==========================================================================================

    #[test]
    fn partial_fill_liquidation_non_identity_scale_conserves() {
        let mut core = boot();
        create_loan(&mut core, 2);
        place_lp_bid(&mut core, 1000, 2); // 只吃 2 lot，第 3 lot IOC 拒单

        let (rc, _) = submit(&mut core, cmd_loan_force_liquidate(2000, BORROWER, SYMBOL, 2, MARK_PRICE, COLLATERAL_LOTS, 2_000));
        assert_eq!(rc, CommandResultCode::Success);

        let borrower = core.ups.get(BORROWER).unwrap();
        assert_eq!(borrower.locked(WBTC), 0, "partial-fill 后 exchangeLocked[WBTC] 未归零");
        // 卖 2 lot=200 currencyScale，账户 base 从 300 降到 100（剩 1 WBTC 拒单回填留在 loan）
        assert_eq!(borrower.account(WBTC), 100, "只卖 2 lot，账户应剩 100（1 WBTC）");
        // proceeds=2×50000=100000，liqFee=2000，本金 80000 回池 → 借款人 USDT = 98000
        let proceeds = 2 * MARK_PRICE;
        assert_eq!(borrower.account(USDT), proceeds - proceeds * 200 / 10_000, "partial-fill 借款人 USDT 结算额错");
        assert_conserved(&core, "after partial-fill liquidation");
    }

    // ==========================================================================================
    // 3. repay_nonIdentityScale_conserves
    // ==========================================================================================

    #[test]
    fn repay_non_identity_scale_conserves() {
        let mut core = boot();
        create_loan(&mut core, 3);
        assert_conserved(&core, "after create");
        assert_eq!(core.ups.get(BORROWER).unwrap().account(USDT), PRINCIPAL, "借入后 USDT 应 = 本金");

        // 全额还款（repayAmount=0 = payoff 本息）；rate=0 → 无利息
        let (rc, _) = submit(&mut core, cmd_loan_repay(20, BORROWER, 3, 0, 1_000));
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(core.ups.get(BORROWER).unwrap().account(USDT), 0, "全额还款后 USDT 应归零");
        assert_conserved(&core, "after full repay");
    }

    // ==========================================================================================
    // 4. resetFee_doesNotSweepInsuranceFund_conserves —— 强平费划归 LIF 准备金，RESET_FEE 不得提取它。
    // ==========================================================================================

    #[test]
    fn reset_fee_does_not_sweep_insurance_fund_conserves() {
        let mut core = boot();
        create_loan(&mut core, 4);
        place_lp_bid(&mut core, 1000, COLLATERAL_LOTS);
        let (rc, _) = submit(&mut core, cmd_loan_force_liquidate(2000, BORROWER, SYMBOL, 4, MARK_PRICE, COLLATERAL_LOTS, 2_000));
        assert_eq!(rc, CommandResultCode::Success);

        // 全额强平：liqFee=2%=3000 进 LIF；takerFee/makerFee=0 → fees=0
        assert_eq!(*core.risk.fees.get(&USDT).unwrap_or(&0), 0, "撮合 fees 应为 0（takerFee/makerFee=0）");
        let adj_before = *core.risk.adjustments.get(&USDT).unwrap_or(&0);
        assert_conserved(&core, "after liquidation, before reset-fee");

        let (rc, _) = submit(&mut core, cmd_reset_fee(999));
        assert_eq!(rc, CommandResultCode::Success);

        // LIF 不被扫走：adjustments 纹丝不动（fees=0 → RESET_FEE 无可清扫），强平费仍留在 LIF 桶内
        assert_eq!(*core.risk.adjustments.get(&USDT).unwrap_or(&0), adj_before, "RESET_FEE 不得提取 LIF —— 它是准备金不是收入");
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(USDT), COLLATERAL_LOTS * MARK_PRICE * 200 / 10_000, "强平费应仍在 LIF");
        assert_conserved(&core, "after reset-fee");
    }

    // ==========================================================================================
    // Cross 模式（账户级抵押 + numeraire 估值）—— 非 identity scale 守恒
    // ==========================================================================================

    fn cross_borrow(core: &mut ExchangeCore, loan_id: i64) {
        let (rc, _) = submit(core, cmd_loan_cross_add_collateral(20, BORROWER, WBTC, COLLATERAL_WBTC, 1_000));
        assert_eq!(rc, CommandResultCode::Success);
        let (rc, _) = submit(core, cmd_loan_cross_borrow(21, BORROWER, SYMBOL, loan_id, CROSS_PRINCIPAL, 1_000));
        assert_eq!(rc, CommandResultCode::Success);
    }

    #[test]
    fn cross_full_liquidation_non_identity_scale_conserves() {
        let mut core = boot();
        cross_borrow(&mut core, 10);
        assert_conserved(&core, "after cross borrow");
        assert_eq!(core.ups.get(BORROWER).unwrap().account(USDT), CROSS_PRINCIPAL, "借入后 USDT = 本金");

        place_lp_bid(&mut core, 2000, COLLATERAL_LOTS); // 吃满 3 lot
        let (rc, _) = submit(&mut core, cmd_loan_cross_force_liquidate(3000, BORROWER, SYMBOL, 10, MARK_PRICE, COLLATERAL_LOTS, 2_000));
        assert_eq!(rc, CommandResultCode::Success);

        let borrower = core.ups.get(BORROWER).unwrap();
        assert_eq!(borrower.locked(WBTC), 0, "cross 强平后 exchangeLocked[WBTC] 未归零");
        assert_eq!(borrower.account(WBTC), 0, "3 WBTC 抵押应全部卖出");
        // proceeds=150000, liqFee=3000, 本金 60000 回池, overpay 留用户 → USDT = proceeds − liqFee
        let proceeds = COLLATERAL_LOTS * MARK_PRICE;
        assert_eq!(borrower.account(USDT), proceeds - proceeds * 200 / 10_000, "cross 借款人 USDT 结算额错");
        assert_conserved(&core, "after cross full liquidation");
    }

    #[test]
    fn cross_underwater_liquidation_non_identity_scale_conserves() {
        let mut core = boot();
        cross_borrow(&mut core, 11);
        // 砸价到 5000：proceeds=3×5000=15000 << 债务 60000 → underwater → LIF 接管。
        core.risk.last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(5_000));
        let (rc, _) = submit(&mut core, cmd_place_order(2001, LP, SYMBOL, 5_000, COLLATERAL_LOTS, OrderAction::Bid, OrderType::Gtc));
        assert_eq!(rc, CommandResultCode::Success);
        let (rc, _) = submit(&mut core, cmd_loan_cross_force_liquidate(3000, BORROWER, SYMBOL, 11, 5_000, COLLATERAL_LOTS, 2_000));
        assert_eq!(rc, CommandResultCode::Success);

        let borrower = core.ups.get(BORROWER).unwrap();
        assert_eq!(borrower.locked(WBTC), 0, "underwater cross 强平后 exchangeLocked 未归零");
        // 借款人保留全部借入本金（overpay=0），缺口由 LIF 垫付；全局仍守恒
        assert_eq!(borrower.account(USDT), CROSS_PRINCIPAL, "underwater：借款人应保留全额本金");
        assert_conserved(&core, "after cross underwater liquidation");
    }

    #[test]
    fn cross_withdraw_and_repay_non_identity_scale_conserves() {
        let mut core = boot();
        cross_borrow(&mut core, 12);
        // 撤回 1 WBTC 抵押（撤后 LTV = 60000/(2×50000)=60% < 85% liquidation → 允许）
        let (rc, _) = submit(&mut core, cmd_loan_cross_withdraw_collateral(22, BORROWER, WBTC, 100, 1_000));
        assert_eq!(rc, CommandResultCode::Success);
        assert_conserved(&core, "after cross withdraw collateral");
        // 全额还款
        let (rc, _) = submit(&mut core, cmd_loan_cross_repay(23, BORROWER, 12, 0, 1_000));
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(core.ups.get(BORROWER).unwrap().account(USDT), 0, "cross 全额还款后 USDT 归零");
        assert_conserved(&core, "after cross full repay");
    }

    // ==========================================================================================
    // 8. duplicateForceLiquidate_secondRejectedByGuard_conserves —— failover 幂等命门：
    //    重复强平被 R1 抵押边界（compare-and-consume）挡下。
    // ==========================================================================================

    #[test]
    fn duplicate_force_liquidate_second_rejected_by_guard_conserves() {
        let mut core = boot();
        create_loan(&mut core, 20);
        place_lp_bid(&mut core, 3000, 2); // 只吃 2 lot → 部分成交，loan 保留 1 WBTC

        // X：部分强平成功（模拟已 apply 的那条）
        let (rc, _) = submit(&mut core, cmd_loan_force_liquidate(4000, BORROWER, SYMBOL, 20, MARK_PRICE, COLLATERAL_LOTS, 2_000));
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(core.ups.get(BORROWER).unwrap().account(WBTC), 100, "X 后账户剩 1 WBTC");

        // Y：failover 新 leader 按旧状态又发一条 3 张 → 抵押只剩 1 WBTC，pre-move 拒
        let (rc, _) = submit(&mut core, cmd_loan_force_liquidate(4001, BORROWER, SYMBOL, 20, MARK_PRICE, COLLATERAL_LOTS, 2_000));
        assert_eq!(rc, CommandResultCode::LoanInvalidAmount);
        // Y 被拒、无副作用，账户不变、守恒
        assert_eq!(core.ups.get(BORROWER).unwrap().account(WBTC), 100, "Y 被拒，账户 WBTC 不变");
        assert_eq!(core.ups.get(BORROWER).unwrap().locked(WBTC), 0, "Y 被拒，exchangeLocked 不变");
        assert_conserved(&core, "after duplicate force-liquidate rejected");
    }
}
