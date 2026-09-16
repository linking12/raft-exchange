//! 翻译自 Java `exchange.core2.tests.integration.ITLoanTargetedRecovery`（1 个 @Test）——
//! loan targeted 索引在 snapshot 恢复后仍工作：建仓 → 落盘快照 → 全新实例恢复（驱动 `updateProvider` →
//! `LoanLiquidationEngine` 从恢复出的用户态重建 isolatedLoanSymbolToUsers / crossLoanCurrencyToUsers）→
//! 恢复后仅发 MARKPRICE 抵押价暴跌（不发 LIQUIDATION_SCAN）→ 抵押被 targeted 强平消费。证明 targeted 索引不是
//! 构造时的一次性产物，recover 路径重建出的索引同样可用，新 leader 不会因索引空白而漏检。
//!
//! `ExchangeApi` 无 snapshot round-trip 入口，故直连 `ExchangeCore`：`to_snapshot_bytes()` /
//! `from_snapshot_bytes()` 即真实 bincode 复制态 round-trip，后者内部调 `restore_non_replicated_state`
//! （= Java `updateProvider`：`liquidation_engine` 是 `#[serde(skip)]` leader-local，反序列化后
//! `LoanLiquidationEngine::rebuild_indices` 从恢复出的 loan 记录重建 targeted 双索引）。恢复后置 `is_running`
//! + 喂 MARKPRICE_ADJUSTMENT 触发 targeted 扫描并同步 drain force-sell（对齐 Java enableLiquidationEngines +
//! updateCurrentPriceTo，无需轮询）。
//!
//! 货币/符号对齐 Java `TestConstants`：ETH=3928 XBT=3762 SYMBOL_EXCHANGE=9269；loan 配置 `ofSymbol(6000/8000/7000)`。
//! 开仓 mark=1000 → LTV 50% < 60% initial；暴跌 mark=500 → LTV 100% ≥ 80% liquidation → 触发。

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
        // ===== 原 leader：建仓，落盘快照 =====
        let snapshot = {
            let mut core = ExchangeCore::new();
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
            assert!(core.query_total_balance().is_global_zero(), "快照前应守恒");
            core.to_snapshot_bytes()
        };

        // ===== 全新实例：从快照恢复（驱动 rebuild_indices 重建 targeted 索引） =====
        let mut r = ExchangeCore::from_snapshot_bytes(&snapshot);

        // 恢复后 targeted 索引已重建：isolated loan symbol 索引里应有 BORROWER。
        assert!(
            r.risk
                .liquidation_engine
                .loan_liquidation_engine
                .isolated_loan_symbol_to_users
                .get(&SYMBOL)
                .is_some_and(|users| users.contains(&BORROWER)),
            "恢复后 isolated targeted 索引未重建/未命中 loan-only 用户"
        );
        assert!(r.query_total_balance().is_global_zero(), "恢复后应守恒");

        // LP 在暴跌价挂 BID（余额随快照恢复，这里只补挂单），接强平的 ASK IOC 卖单。
        assert_eq!(
            submit(&mut r, cmd_place_order(2000, LP, SYMBOL, CRASH_MARK, ETH_COLLATERAL, OrderAction::Bid, 1_500)),
            CommandResultCode::Success
        );

        r.risk.liquidation_engine.is_running = true; // is_running=true，但不发 scan

        // 关键：仅抵押 spot 对 MARKPRICE 暴跌 → targeted 触发；若恢复后索引未重建，本该命中的 loan-only 用户会被漏检。
        assert_eq!(submit(&mut r, cmd_markprice(SYMBOL, CRASH_MARK, 2_000)), CommandResultCode::Success);

        let collateral_now = r
            .ups
            .get(BORROWER)
            .and_then(|up| up.isolated_loans.get(&LOAN_ID))
            .map(|l| l.collateral_amount)
            .unwrap_or(0);
        assert!(
            collateral_now < ETH_COLLATERAL,
            "snapshot 恢复后抵押价暴跌应仍经 targeted 路径即时强平；抵押未减少说明恢复后 loan 索引未重建/未命中 (now={collateral_now})"
        );

        assert!(r.query_total_balance().is_global_zero(), "恢复后 targeted 强平仍守恒");
    }
}
