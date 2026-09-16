//! 翻译自 Java `exchange.core2.tests.integration.ITLoanDisableSymbol`（1 个 @Test）——
//! 停借（ADD_LOAN 把 initialLtvBps 置 0）只关新开仓的闸，不得动存量贷款：liquidation/marginCall/collateralWeight
//! 都从 initialLtv 派生，若跟着归零会把该 pair 存量贷款连带强平（运营最自然的动作就会引爆），故钉死。
//!
//! `ExchangeApi` 未暴露 ADD_LOAN 配置入口（`apply_add_loan` 需 `&mut RiskEngine + &mut ssp`，门面只给只读
//! `ssp()`/`risk()`），故本文件直连 `ExchangeCore`（对齐 in-crate `loan_e2e_tests.rs` 风格）：停借走
//! `RiskEngine::apply_add_loan` 的 kill-switch 段（`SymbolLoanConfig{ loan_initial_ltv_bps: 0, 余 UNSET }`
//! resolve 后 initial==0 → 只清 initial、保留 liquidation/marginCall/maxAmount/maxTermDays）。强平触发靠
//! 置 `is_running` + 喂一条 MARKPRICE_ADJUSTMENT（价格没动），同步 drain（对齐 Java enableLiquidationEngines +
//! updateCurrentPriceTo，无需轮询）。
//!
//! 货币/符号对齐 Java `TestConstants`：ETH=3928 XBT=3762 SYMBOL_EXCHANGE=9269；loan 配置 `ofSymbol(6000/8000/7000)`。
//! 存量贷款 LTV = 50000/(100·1000) = 50%，远低于原 80% 强平线；停借后 liquidation 仍 8000，价格没动也不该被碰。

#[cfg(test)]
mod tests {
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

    /// Java `BatchAddLoanCommand.ofMarket(SYMBOL, 0)`：只给 initialLtv=0，其余 UNSET 走派生（这正是踩雷的用法）。
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
        core.risk.last_price_cache.insert(SYMBOL, MARK);

        assert_eq!(submit(&mut core, cmd_pool_deposit(5000, XBT, POOL_FUND)), CommandResultCode::Success);

        core.ups.add_empty_user_profile(BORROWER);
        assert_eq!(submit(&mut core, cmd_balance_adjustment(1, BORROWER, ETH, ETH_COLLATERAL * 2)), CommandResultCode::Success);

        // 存量贷款：LTV = 50000/(100·1000) = 50% < 60% initial → 成功。
        assert_eq!(
            submit(&mut core, cmd_loan_create(1_000_001, BORROWER, SYMBOL, LOAN_ID, ETH_COLLATERAL, XBT_PRINCIPAL, 1_000)),
            CommandResultCode::Success
        );

        // 停借：kill-switch 只清 initial_ltv_bps，liquidation(8000)/marginCall(7000) 保留。
        core.risk.apply_add_loan(&disable_symbol_cmd(SYMBOL), &mut core.ssp);
        {
            let cfg = core.ssp.get_symbol(SYMBOL).unwrap().loan_config;
            assert_eq!(cfg.initial_ltv_bps, 0, "停借 → initial 归零");
            assert_eq!(cfg.liquidation_ltv_bps, 8_000, "liquidation 必须保留，否则存量被连带强平");
            assert_eq!(cfg.margin_call_ltv_bps, 7_000, "marginCall 必须保留");
        }

        // 停借后新开仓被拒。
        assert_eq!(
            submit(&mut core, cmd_loan_create(1_000_002, BORROWER, SYMBOL, LOAN_ID + 1, ETH_COLLATERAL, XBT_PRINCIPAL, 1_000)),
            CommandResultCode::LoanNotEnabled
        );

        // 存量贷款：价格没动（仍 MARK），LTV 50% < 保留的 80% 强平线 → 不该被碰。
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
            "停借不得动存量：liquidationLtv 若跟着 initialLtv 归零，此处抵押会被强平消费掉"
        );

        assert!(core.query_total_balance().is_global_zero(), "停借全程守恒");
    }
}
