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

    const WBTC: i32 = 710;
    const USDT: i32 = 711;
    const SYMBOL: i32 = 71010;
    const MARK_PRICE: i64 = 50_000;
    const POOL_FUND: i64 = 10_000_000;
    const BORROWER: i64 = 8001;
    const LP: i64 = 8002;

    const COLLATERAL_WBTC: i64 = 300;
    const COLLATERAL_LOTS: i64 = 3;
    const PRINCIPAL: i64 = 80_000;
    const CROSS_PRINCIPAL: i64 = 60_000;

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
            user_cookie: 0,
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

    #[test]
    fn full_liquidation_non_identity_scale_conserves() {
        let mut core = boot();
        assert_conserved(&core, "after setup");
        create_loan(&mut core, 1);
        assert_conserved(&core, "after loan create");
        place_lp_bid(&mut core, 1000, COLLATERAL_LOTS);

        let (rc, _) = submit(&mut core, cmd_loan_force_liquidate(2000, BORROWER, SYMBOL, 1, MARK_PRICE, COLLATERAL_LOTS, 2_000));
        assert_eq!(rc, CommandResultCode::Success);

        let borrower = core.ups.get(BORROWER).unwrap();
        assert_eq!(borrower.locked(WBTC), 0, "exchangeLocked[WBTC] 未归零");
        assert_eq!(borrower.account(WBTC), 0, "3 WBTC 抵押应全部卖出");
        assert_eq!(core.ups.get(LP).unwrap().account(WBTC), COLLATERAL_WBTC, "LP 应收 3 WBTC");

        let proceeds = COLLATERAL_LOTS * MARK_PRICE;
        let liq_fee = proceeds * 200 / 10_000;
        assert_eq!(core.ups.get(BORROWER).unwrap().account(USDT), proceeds - liq_fee, "借款人 USDT 结算额错（应为 proceeds−liqFee）");
        assert_conserved(&core, "after full liquidation");
    }

    #[test]
    fn partial_fill_liquidation_non_identity_scale_conserves() {
        let mut core = boot();
        create_loan(&mut core, 2);
        place_lp_bid(&mut core, 1000, 2);

        let (rc, _) = submit(&mut core, cmd_loan_force_liquidate(2000, BORROWER, SYMBOL, 2, MARK_PRICE, COLLATERAL_LOTS, 2_000));
        assert_eq!(rc, CommandResultCode::Success);

        let borrower = core.ups.get(BORROWER).unwrap();
        assert_eq!(borrower.locked(WBTC), 0, "partial-fill 后 exchangeLocked[WBTC] 未归零");
        assert_eq!(borrower.account(WBTC), 100, "只卖 2 lot，账户应剩 100（1 WBTC）");
        let proceeds = 2 * MARK_PRICE;
        assert_eq!(borrower.account(USDT), proceeds - proceeds * 200 / 10_000, "partial-fill 借款人 USDT 结算额错");
        assert_conserved(&core, "after partial-fill liquidation");
    }

    #[test]
    fn repay_non_identity_scale_conserves() {
        let mut core = boot();
        create_loan(&mut core, 3);
        assert_conserved(&core, "after create");
        assert_eq!(core.ups.get(BORROWER).unwrap().account(USDT), PRINCIPAL, "借入后 USDT 应 = 本金");

        let (rc, _) = submit(&mut core, cmd_loan_repay(20, BORROWER, 3, 0, 1_000));
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(core.ups.get(BORROWER).unwrap().account(USDT), 0, "全额还款后 USDT 应归零");
        assert_conserved(&core, "after full repay");
    }

    #[test]
    fn reset_fee_does_not_sweep_insurance_fund_conserves() {
        let mut core = boot();
        create_loan(&mut core, 4);
        place_lp_bid(&mut core, 1000, COLLATERAL_LOTS);
        let (rc, _) = submit(&mut core, cmd_loan_force_liquidate(2000, BORROWER, SYMBOL, 4, MARK_PRICE, COLLATERAL_LOTS, 2_000));
        assert_eq!(rc, CommandResultCode::Success);

        assert_eq!(*core.risk.fees.get(&USDT).unwrap_or(&0), 0, "撮合 fees 应为 0（takerFee/makerFee=0）");
        let adj_before = *core.risk.adjustments.get(&USDT).unwrap_or(&0);
        assert_conserved(&core, "after liquidation, before reset-fee");

        let (rc, _) = submit(&mut core, cmd_reset_fee(999));
        assert_eq!(rc, CommandResultCode::Success);

        assert_eq!(*core.risk.adjustments.get(&USDT).unwrap_or(&0), adj_before, "RESET_FEE 不得提取 LIF —— 它是准备金不是收入");
        assert_eq!(core.risk.loan_service.get_loan_insurance_fund(USDT), COLLATERAL_LOTS * MARK_PRICE * 200 / 10_000, "强平费应仍在 LIF");
        assert_conserved(&core, "after reset-fee");
    }

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

        place_lp_bid(&mut core, 2000, COLLATERAL_LOTS);
        let (rc, _) = submit(&mut core, cmd_loan_cross_force_liquidate(3000, BORROWER, SYMBOL, 10, MARK_PRICE, COLLATERAL_LOTS, 2_000));
        assert_eq!(rc, CommandResultCode::Success);

        let borrower = core.ups.get(BORROWER).unwrap();
        assert_eq!(borrower.locked(WBTC), 0, "cross 强平后 exchangeLocked[WBTC] 未归零");
        assert_eq!(borrower.account(WBTC), 0, "3 WBTC 抵押应全部卖出");
        let proceeds = COLLATERAL_LOTS * MARK_PRICE;
        assert_eq!(borrower.account(USDT), proceeds - proceeds * 200 / 10_000, "cross 借款人 USDT 结算额错");
        assert_conserved(&core, "after cross full liquidation");
    }

    #[test]
    fn cross_underwater_liquidation_non_identity_scale_conserves() {
        let mut core = boot();
        cross_borrow(&mut core, 11);
        core.risk.last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(5_000));
        let (rc, _) = submit(&mut core, cmd_place_order(2001, LP, SYMBOL, 5_000, COLLATERAL_LOTS, OrderAction::Bid, OrderType::Gtc));
        assert_eq!(rc, CommandResultCode::Success);
        let (rc, _) = submit(&mut core, cmd_loan_cross_force_liquidate(3000, BORROWER, SYMBOL, 11, 5_000, COLLATERAL_LOTS, 2_000));
        assert_eq!(rc, CommandResultCode::Success);

        let borrower = core.ups.get(BORROWER).unwrap();
        assert_eq!(borrower.locked(WBTC), 0, "underwater cross 强平后 exchangeLocked 未归零");
        assert_eq!(borrower.account(USDT), CROSS_PRINCIPAL, "underwater：借款人应保留全额本金");
        assert_conserved(&core, "after cross underwater liquidation");
    }

    #[test]
    fn cross_withdraw_and_repay_non_identity_scale_conserves() {
        let mut core = boot();
        cross_borrow(&mut core, 12);
        let (rc, _) = submit(&mut core, cmd_loan_cross_withdraw_collateral(22, BORROWER, WBTC, 100, 1_000));
        assert_eq!(rc, CommandResultCode::Success);
        assert_conserved(&core, "after cross withdraw collateral");
        let (rc, _) = submit(&mut core, cmd_loan_cross_repay(23, BORROWER, 12, 0, 1_000));
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(core.ups.get(BORROWER).unwrap().account(USDT), 0, "cross 全额还款后 USDT 归零");
        assert_conserved(&core, "after cross full repay");
    }

    #[test]
    fn duplicate_force_liquidate_second_rejected_by_guard_conserves() {
        let mut core = boot();
        create_loan(&mut core, 20);
        place_lp_bid(&mut core, 3000, 2);

        let (rc, _) = submit(&mut core, cmd_loan_force_liquidate(4000, BORROWER, SYMBOL, 20, MARK_PRICE, COLLATERAL_LOTS, 2_000));
        assert_eq!(rc, CommandResultCode::Success);
        assert_eq!(core.ups.get(BORROWER).unwrap().account(WBTC), 100, "X 后账户剩 1 WBTC");

        let (rc, _) = submit(&mut core, cmd_loan_force_liquidate(4001, BORROWER, SYMBOL, 20, MARK_PRICE, COLLATERAL_LOTS, 2_000));
        assert_eq!(rc, CommandResultCode::LoanInvalidAmount);
        assert_eq!(core.ups.get(BORROWER).unwrap().account(WBTC), 100, "Y 被拒，账户 WBTC 不变");
        assert_eq!(core.ups.get(BORROWER).unwrap().locked(WBTC), 0, "Y 被拒，exchangeLocked 不变");
        assert_conserved(&core, "after duplicate force-liquidate rejected");
    }
}
