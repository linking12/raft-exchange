//! 翻译自 Java `exchange.core2.tests.integration.ITLoanTargetedLiquidation`（1 个 @Test）——
//! loan 强平的 targeted 事件驱动端到端：纯 loan 用户（无期货持仓），抵押 spot 对 MARKPRICE 暴跌即时触发强平，
//! 全程不发 LIQUIDATION_SCAN。证明 loan targeted 索引把价格驱动强平覆盖到 loan-only 用户（整链
//! MARKPRICE → check_positions → check_loans → force-sell → 撮合结算）。
//!
//! 通过 `ExchangeApi` 复刻：`enable_liquidation()` 置 leader 门（对齐 Java `enableLiquidationEngines()`），
//! `set_mark_price_at(sym, price, ts)` 提交 MARKPRICE_ADJUSTMENT —— 其 R1 触发 targeted 扫描，产出的
//! LOAN_FORCE_LIQUIDATE 入 pending 队列并在同一 `process_command` 内自动 drain（对齐 Java force-sell 级联，
//! 无需 Java 里的 groupingControl 轮询）。LOAN_CREATE 经 dispatch 时 reconcile 出 isolated targeted 索引，
//! 故 loan-only 用户会被 markprice 命中。
//!
//! 货币/符号对齐 Java `TestConstants`：ETH=3928 XBT=3762 SYMBOL_EXCHANGE=9269，`SYMBOLSPEC_ETH_XBT` 零费；
//! loan 配置 `ofSymbol(6000/8000/7000/MAX/365)`。开仓 mark=1000 → LTV 50% < 60% initial；暴跌 mark=500 →
//! LTV = 50000/(100·500) = 100% ≥ 80% liquidation → 触发。

#[cfg(test)]
mod tests {
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceOrderRequest};

    const ETH: i32 = 3928;
    const XBT: i32 = 3762;
    const SYMBOL: i32 = 9269;
    const BORROWER: i64 = 6001;
    const LP: i64 = 6002;
    const LOAN_ID: i64 = 77;
    const OPEN_MARK: i64 = 1000;
    const CRASH_MARK: i64 = 500;
    const ETH_COLLATERAL: i64 = 100;
    const XBT_PRINCIPAL: i64 = 50_000;
    const POOL_FUND: i64 = 1_000_000;

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

    #[test]
    fn collateral_price_crash_targeted_triggers_force_sell_without_scan() {
        let mut api = ExchangeApi::new();
        api.add_currency(ETH, 1);
        api.add_currency(XBT, 1);
        assert_eq!(api.add_symbol(eth_xbt_loan_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL, OPEN_MARK, 0), CommandResultCode::Success);

        assert_eq!(api.submit(cmd_pool_deposit(5000, XBT, POOL_FUND)), CommandResultCode::Success);

        assert_eq!(api.add_user(BORROWER), CommandResultCode::Success);
        assert_eq!(api.add_user(LP), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(BORROWER, ETH, ETH_COLLATERAL, 1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(LP, XBT, ETH_COLLATERAL * OPEN_MARK * 2, 2), CommandResultCode::Success);

        assert_eq!(
            api.submit(cmd_loan_create(1_000_002, BORROWER, SYMBOL, LOAN_ID, ETH_COLLATERAL, XBT_PRINCIPAL, 1_000)),
            CommandResultCode::Success
        );

        // LP 在暴跌价挂 BID，接强平的 ASK IOC 卖单。
        assert_eq!(
            api.place_order(PlaceOrderRequest {
                order_id: 1000,
                uid: LP,
                symbol: SYMBOL,
                price: CRASH_MARK,
                size: ETH_COLLATERAL,
                reserve_bid_price: CRASH_MARK,
                action: OrderAction::Bid,
                order_type: OrderType::Gtc,
            }),
            CommandResultCode::Success
        );

        api.enable_liquidation(); // is_running=true，但不主动发 scan

        // 关键：仅抵押 spot 对 MARKPRICE 暴跌 → targeted 触发 + 同步 drain 出的 force-sell 撮合掉抵押。
        assert_eq!(api.set_mark_price(SYMBOL, CRASH_MARK, 2_000), CommandResultCode::Success);

        // 抵押被 targeted 强平消费：loan 全平后从 map 移除（或残留 < 初始）。
        let collateral_now = api
            .ups()
            .get(BORROWER)
            .and_then(|up| up.isolated_loans.get(&LOAN_ID))
            .map(|l| l.collateral_amount)
            .unwrap_or(0);
        assert!(
            collateral_now < ETH_COLLATERAL,
            "抵押价暴跌应经 targeted 路径即时强平（无 scan）；抵押未减少说明 loan 索引未命中 loan-only 用户 (now={collateral_now})"
        );

        assert!(api.total_balance().is_global_zero(), "targeted 强平后全局守恒");
    }
}
