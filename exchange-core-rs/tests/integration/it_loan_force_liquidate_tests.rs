//! 翻译自 Java `exchange.core2.tests.integration.ITLoanForceLiquidatePipeline`（1 个 @Test）——
//! LOAN_FORCE_LIQUIDATE 整链集成测试：命令穿透 RiskEngine R1（pre-move 抵押→exchange_locked、挂 ASK IOC）→
//! MatchingEngineRouter → 现货簿撮合成 TRADE → R2 post_process 分账，验证抵押被 TRADE 消费而非滞留
//! exchange_locked（历史 bug 形态），且对手盘（LP）如数收到抵押币。
//!
//! 通过 `ExchangeApi` 门面复刻（单线程直调，无 raft/Disruptor）：loan 全套命令走 `submit(OrderCommand{..})`，
//! LP 挂单走 `place_order`，事件断言读 `last_matcher_event()`（Java `cmd.matcherEvent` 链），守恒读 `total_balance()`。
//!
//! 货币/符号逐字对齐 Java `TestConstants`：ETH=3928 XBT=3762 SYMBOL_EXCHANGE=9269，`SYMBOLSPEC_ETH_XBT`
//! base=ETH quote=XBT baseScaleK=1 quoteScaleK=1 零费。loan 配置 = `BatchAddLoanCommand.ofSymbol(SYMBOL,
//! initialLtv=6000, liquidationLtv=8500, marginCallLtv=7500, maxAmount=MAX, term=365, collateralWeight=10000)`，
//! 直接写 `spec.loan_config`（现货借贷唯一 mutation point 是 ADD_LOAN，此处等价预置）。

#[cfg(test)]
mod tests {
    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::matcher_event_type::MatcherEventType;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceOrderRequest};

    const ETH: i32 = 3928; // CURRENECY_ETH（digit 0 → scale_k 1）
    const XBT: i32 = 3762; // CURRENECY_XBT（digit 0 → scale_k 1）
    const SYMBOL: i32 = 9269; // SYMBOL_EXCHANGE
    const BORROWER: i64 = 5001;
    const LP: i64 = 5002;
    const LOAN_ID: i64 = 42;
    const MARK_PRICE: i64 = 1000;
    const ETH_COLLATERAL: i64 = 100;
    const XBT_PRINCIPAL: i64 = 50_000;
    const POOL_FUND: i64 = 1_000_000;

    /// `SYMBOLSPEC_ETH_XBT` + loan 配置（`ofSymbol(6000/8500/7500/MAX/365)`）。
    fn eth_xbt_loan_spec() -> CoreSymbolSpecification {
        let mut spec = CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: ETH,
            quote_currency: XBT,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        };
        spec.loan_config.update(6_000, 8_500, 7_500, i64::MAX, 365);
        spec
    }

    fn cmd_pool_deposit(order_id: i64, currency: i32, amount: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::PoolDeposit, order_id, symbol: currency, size: amount, ..Default::default() }
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
            user_cookie: 0, // LOCKED（非 FLOATING）
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

    #[test]
    fn force_liquidate_flows_through_orderbook_and_settles() {
        let mut api = ExchangeApi::new();
        api.add_currency(ETH, 1);
        api.add_currency(XBT, 1);
        assert_eq!(api.add_symbol(eth_xbt_loan_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(SYMBOL, MARK_PRICE, 0), CommandResultCode::Success);

        assert_eq!(api.submit(cmd_pool_deposit(5000, XBT, POOL_FUND)), CommandResultCode::Success);

        assert_eq!(api.add_user(BORROWER), CommandResultCode::Success);
        assert_eq!(api.add_user(LP), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(BORROWER, ETH, ETH_COLLATERAL, 1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(LP, XBT, ETH_COLLATERAL * MARK_PRICE * 2, 2), CommandResultCode::Success);

        // 开仓：抵押 100 ETH 借 50000 XBT，LTV = 50000/(100·1000) = 50% < 60% initial。
        assert_eq!(
            api.submit(cmd_loan_create(1_000_002, BORROWER, SYMBOL, LOAN_ID, ETH_COLLATERAL, XBT_PRINCIPAL, 1_000)),
            CommandResultCode::Success
        );

        // LP 在 mark 价挂 BID，接强平的 ASK IOC 卖单。
        assert_eq!(
            api.place_order(PlaceOrderRequest {
                order_id: 1000,
                uid: LP,
                symbol: SYMBOL,
                price: MARK_PRICE,
                size: ETH_COLLATERAL,
                reserve_bid_price: MARK_PRICE,
                action: OrderAction::Bid,
                order_type: OrderType::Gtc,
            }),
            CommandResultCode::Success
        );

        // 显式 LOAN_FORCE_LIQUIDATE（ASK IOC，size=lots=100）：R1 pre-move → ME 撮合 → R2 分账。
        assert_eq!(
            api.submit(cmd_loan_force_liquidate(2222, BORROWER, SYMBOL, LOAN_ID, MARK_PRICE, ETH_COLLATERAL, 1_000)),
            CommandResultCode::Success
        );

        // matcherEvent 链非空 + 至少一条 TRADE —— 表明 cmd 真的走到了 orderbook.newOrder（router/orderbook 分支缺失会在这里暴露）。
        let head = api.last_matcher_event().expect("matcherEvent 链为空 —— cmd 没走到 orderbook.newOrder");
        let mut trade_count = 0;
        let mut ev = Some(head);
        while let Some(e) = ev {
            if e.event_type == MatcherEventType::Trade {
                trade_count += 1;
            }
            ev = e.next.as_deref();
        }
        assert!(trade_count > 0, "预期至少 1 条 TRADE event，实际 {trade_count}");

        // 抵押被 TRADE 消费而非滞留 exchange_locked（!=0 即 pre-move 完没撮合的历史 bug）。
        assert_eq!(api.user_locked(BORROWER, ETH), 0, "抵押应被 TRADE 消费，不滞留 exchange_locked");
        // LP 如数收满 100 ETH 抵押。
        assert_eq!(api.user_account(LP, ETH), ETH_COLLATERAL, "LP 应收满 100 ETH");

        assert!(api.total_balance().is_global_zero(), "强平后全局守恒");
    }
}
