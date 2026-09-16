//! 翻译自 Java `exchange.core2.tests.integration.ITMixedIntegration`。
//! 混合现货/期货：同一用户同时持有期货挂单与现货挂单时，free/lock 校验正确、资金全局守恒。
//! 直连引擎（无 raft/Disruptor），逐条对拍 Java 黄金值。
//!
//! Java `initFutureSymbols().get(0)`：BTC/USD 永续 symbolId=10000, makerFee=10, takerFee=20,
//!   mm={1000:5,100000:10}@1000, maxLeverage={2000:5,100000:10}, initMargin=1@100。
//! Java `initExchangeSymbols().get(0)`：BTC/USD 现货 symbolId=10003, makerFee=10, takerFee=20, feeScaleK=0。
//! 两者 base=XBT(3762)/quote=USD(840)。
//!
//! 直接提交（directly-submitted）的 IF_DEPOSIT / IF_WITHDRAW 命令其余额效果 + 全局对账可直接断言，故已补齐：
//!   - testInsuranceFundDepositKeepsGlobalReconciliation  —— `submit(IF_DEPOSIT{symbol,price=amount,order_id=txid})`，
//!         IF available 入账 + adjustments 反向对冲 + is_global_zero；非法入参（负/零/未知 symbol）拒绝；多次累加。
//!   - testInsuranceFundWithdrawKeepsGlobalReconciliation —— `submit(IF_WITHDRAW{...})` 对称：available 扣减、
//!         超额 RiskIfInsufficient、非法入参拒绝、Deposit+Withdraw 组合。
//!   （均降级为**单分片**：Java 的 `shardId(0)` 定向 → 单 shard 直落；金额/对账黄金值逐位一致。）
//!
//! 不可复刻而 **SKIP** 的 @Test（harness 缺相应基础设施）：
//!   - testMixedExchangeAndMargin2 / testClosePositionWithProfit / testClosePositionWithProfit2
//!         —— 依赖 `updateCurrentPriceTo`（Java 会挂 CROSS 对敲单并推进 R2 撮合来"移动"价格），
//!            harness 的 set_mark_price 只设 mark 值、无等价物；且断言 report 层派生字段
//!            liquidationPrice / marginRatioScaleK / unrealizedProfit。needs harness extension。
//!   - testIsolatedLiquidationFullyMatchedWithFee / testCrossLiquidationFullyMatchedWithFee /
//!     testIsolatedLiquidationPartialMatchedWithIFTakeover(_FundingFee) /
//!     testIsolatedLiquidationPartialMatchedWithAdlTakeover
//!         —— 断言的核心是逐笔 LIQUIDATION_* / IF_* / ADL_* fund event 的字段（accountId/direction/cumRealized/
//!            liquidationPrice/marginRatioScaleK），这些走内部排空命令、last_fund_events() 不捕获；被强平者仓位移除
//!            + IF 承接 + 全局守恒的**状态等价物**已由 it_liquidation_tests / it_adl_tests 覆盖（同一 FORCE→IF→ADL
//!            级联路径），此处不重复。
//!   - testInsuranceFundDepositDistributesAcrossShards / testInsuranceFundWithdrawIsPerShardIsolated /
//!     testInsuranceFundReportQueryPerShardBreakdown
//!         —— 本质是**多分片**定向注资/抽资/逐 shard×逐 symbol 报表切分（含 LIF），单分片 harness 无法复刻跨 shard 独立性。
//!   - testCrossMarginAlert_isolatedMarginExcluded / testCrossBpMultiPositionAllocation_regressionLock
//!         —— 断言 MARGIN_ALERT / LIQUIDATION_ALERT fund event（强平扫描触发路径），last_fund_events() 不捕获内部扫描事件。
//!
//! 已翻译（3 条）：testMixedExchangeAndMargin（期货 pending + 现货挂单联合 free/lock 校验）
//! + IF 充值 / 抽资的全局对账闭环。

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
    use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
    use exchange_core_rs::core::common::cmd::order_command_type::OrderCommandType;
    use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
    use exchange_core_rs::core::common::margin_mode::MarginMode;
    use exchange_core_rs::core::common::order_action::OrderAction;
    use exchange_core_rs::core::common::order_type::OrderType;
    use exchange_core_rs::core::common::symbol_type::SymbolType;
    use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceFuturesOrderRequest, PlaceOrderRequest};

    const QUOTE_ID: i32 = 840; // USD
    const BASE_ID: i32 = 3762; // XBT
    const PERP_SYMBOL: i32 = 10000; // 期货 BTC/USD
    const EXCHANGE_SYMBOL: i32 = 10003; // 现货 BTC/USD

    const UID_1: i64 = 1001;

    fn perp_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: PERP_SYMBOL,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            fee_scale_k: 0,
            maintenance_margin: BTreeMap::from([(1_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1_000,
            max_leverage: BTreeMap::from([(2_000, 5), (100_000, 10)]),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    fn exchange_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: EXCHANGE_SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 10,
            taker_fee: 20,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    fn assert_conserved(api: &ExchangeApi) {
        for &cur in api.ssp().currencies.keys() {
            let mut total: i64 = api.ups().users.values().map(|p| p.account(cur)).sum();
            total += api.adjustments(cur);
            total += api.fees(cur);
            for p in api.ups().users.values() {
                for pos in p.positions.values() {
                    if pos.currency != cur {
                        continue;
                    }
                    let mark = api
                        .risk()
                        .mark_price(pos.symbol)
                        .unwrap_or_else(|| panic!("open position on symbol {} missing mark price", pos.symbol));
                    total += pos.estimate_pnl(mark);
                    total += pos.extra_margin;
                }
            }
            assert_eq!(total, 0, "全局守恒被打破：currency={cur} total={total}");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // testMixedExchangeAndMargin：userId1 既有期货挂单又有现货挂单，free/lock 校验正确（期货未开仓，纯 pending）。
    //
    //   deposit=10000。期货 BID 1@10000 CROSS(leverage=1)：pending 仓，required margin =
    //     initMargin(10000×1%)=100 + takerFee 预收(1×20)=20 = 120 → freeFuturesMargin=-120；accounts 不动。
    //   现货 BID 1@10000(reserve 10000)：lock = 1×(10000+takerFee 20) = 10020。
    //   现货下单 free 校验 = accounts − spotLock − 现货新锁(10020) + freeFuturesMargin(-120)：
    //     10000 - 10020 - 120 = -140 < 0 → RISK_NSF；补 139 仍 -1 → NSF；再补 1 → 0 → SUCCESS。
    //   终态：accounts=10140，exchangeLocked=10020，期货 pending 仓保留。
    // ─────────────────────────────────────────────────────────────
    #[test]
    fn mixed_exchange_and_margin() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, 10_000), CommandResultCode::Success);
        assert_eq!(api.add_symbol(exchange_spec()), CommandResultCode::Success);

        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 10_000, 1), CommandResultCode::Success);

        // 期货 BID 1@10000 CROSS —— 无对手方，pending 仓（不动 accounts）。
        assert_eq!(
            api.place_futures_order(PlaceFuturesOrderRequest {
                order_id: 1005, uid: UID_1, symbol: PERP_SYMBOL, price: 10_000, size: 1,
                action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
                margin_mode: MarginMode::Cross, reduce_only: false,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 10_000, "期货挂单不动 accounts");
        assert!(api.user_position(UID_1, PERP_SYMBOL).is_some(), "pending 仓已建");

        // 现货 BID 1@10000（reserve=price=10000）。
        let spot_order = PlaceOrderRequest {
            order_id: 112233, uid: UID_1, symbol: EXCHANGE_SYMBOL, price: 10_000, size: 1,
            reserve_bid_price: 10_000, action: OrderAction::Bid, order_type: OrderType::Gtc,
        };

        // 资金不足（差 140）：RISK_NSF。
        assert_eq!(api.place_order(spot_order.clone()), CommandResultCode::RiskNsf);

        // 补 139，仍差 1：RISK_NSF。
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 139, 2), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_order.clone()), CommandResultCode::RiskNsf);
        assert!(api.user_position(UID_1, PERP_SYMBOL).is_some());

        // 再补 1（total 10140）：SUCCESS。
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 1, 3), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_order), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), 10_140, "accounts=真实持有=10140");
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 10_020, "现货挂单冻结=10020");
        assert!(api.user_position(UID_1, PERP_SYMBOL).is_some(), "期货 pending 仓仍在");
        assert_conserved(&api);
    }

    // ─────────────────────────────────────────────────────────────
    // IF 充值/抽资 helper + 只读器（单分片：Java 的 shardId 参数在单 shard 下无关紧要）。
    // ─────────────────────────────────────────────────────────────

    /// IF_DEPOSIT：`price=currencyAmount`、`order_id=transactionId`。
    fn if_deposit(api: &mut ExchangeApi, symbol: i32, amount: i64, txid: i64) -> CommandResultCode {
        api.submit(OrderCommand {
            command: OrderCommandType::IfDeposit,
            symbol,
            price: amount,
            order_id: txid,
            ..Default::default()
        })
    }

    /// IF_WITHDRAW：字段同 IF_DEPOSIT。
    fn if_withdraw(api: &mut ExchangeApi, symbol: i32, amount: i64, txid: i64) -> CommandResultCode {
        api.submit(OrderCommand {
            command: OrderCommandType::IfWithdraw,
            symbol,
            price: amount,
            order_id: txid,
            ..Default::default()
        })
    }

    /// 期货 IF 可用余额（currency_scale_k=1 → notional==currency，逐位对齐 Java `getIFBalance`）。
    fn if_balance(api: &ExchangeApi, symbol: i32) -> i64 {
        api.insurance_fund().futures.get(&symbol).map(|e| e.available).unwrap_or(0)
    }

    /// 建 currencies + 期货 symbol（复用 perp_spec，symbolId=10000）+ 初始 mark。
    fn setup_futures_if() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, 10_000), CommandResultCode::Success);
        api
    }

    // ─────────────────────────────────────────────────────────────
    // testInsuranceFundDepositKeepsGlobalReconciliation（backfill）：
    //   1) IF available 按 amount 入账；2) adjustments 反向对冲 → is_global_zero；
    //   3) 非法入参（负/零/未知 symbol）拒绝且账本不动；4) 多次充值累加。
    // ─────────────────────────────────────────────────────────────
    #[test]
    fn insurance_fund_deposit_keeps_global_reconciliation() {
        let first_deposit = 1_000_000i64;
        let second_deposit = 500i64;

        let mut api = setup_futures_if();

        // 初始：IF 空，全局对账闭环。
        assert_eq!(if_balance(&api, PERP_SYMBOL), 0);
        assert!(api.total_balance().is_global_zero());

        // 第一笔充值。
        assert_eq!(if_deposit(&mut api, PERP_SYMBOL, first_deposit, 1), CommandResultCode::Success);
        assert_eq!(if_balance(&api, PERP_SYMBOL), first_deposit);
        assert_eq!(api.adjustments(QUOTE_ID), -first_deposit, "充值 → adjustments 记负对冲");
        assert!(api.total_balance().is_global_zero());

        // 非法入参：负数 / 零 / 未知 symbol。
        assert_eq!(if_deposit(&mut api, PERP_SYMBOL, -1, 2), CommandResultCode::RiskInvalidAmount);
        assert_eq!(if_deposit(&mut api, PERP_SYMBOL, 0, 3), CommandResultCode::RiskInvalidAmount);
        assert_eq!(if_deposit(&mut api, 99_999, 1_000, 4), CommandResultCode::InvalidSymbol);

        // 失败入参不改动账本。
        assert_eq!(if_balance(&api, PERP_SYMBOL), first_deposit);
        assert!(api.total_balance().is_global_zero());

        // 第二笔充值累加。
        assert_eq!(if_deposit(&mut api, PERP_SYMBOL, second_deposit, 5), CommandResultCode::Success);
        assert_eq!(if_balance(&api, PERP_SYMBOL), first_deposit + second_deposit);
        assert_eq!(api.adjustments(QUOTE_ID), -(first_deposit + second_deposit));
        assert!(api.total_balance().is_global_zero());
    }

    // ─────────────────────────────────────────────────────────────
    // testInsuranceFundWithdrawKeepsGlobalReconciliation（backfill）：
    //   1) 空池抽资 → RiskIfInsufficient；2) 正常抽资 available 扣减 + adjustments 对冲；
    //   3) 抽超余额 → RiskIfInsufficient 且账本不动；4) 非法入参拒绝；5) Deposit+Withdraw 组合。
    // ─────────────────────────────────────────────────────────────
    #[test]
    fn insurance_fund_withdraw_keeps_global_reconciliation() {
        let deposit = 1_000_000i64;
        let first_withdraw = 300_000i64;
        let second_withdraw = 200_000i64;

        let mut api = setup_futures_if();

        // 初始：IF 空，抽资立即 RiskIfInsufficient。
        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, 100, 1), CommandResultCode::RiskIfInsufficient);
        assert_eq!(if_balance(&api, PERP_SYMBOL), 0);
        assert!(api.total_balance().is_global_zero());

        // 先充值 1_000_000。
        assert_eq!(if_deposit(&mut api, PERP_SYMBOL, deposit, 2), CommandResultCode::Success);
        assert_eq!(if_balance(&api, PERP_SYMBOL), deposit);
        assert!(api.total_balance().is_global_zero());

        // 正常抽 300_000：available 剩 700_000，adjustments 反向对冲。
        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, first_withdraw, 3), CommandResultCode::Success);
        assert_eq!(if_balance(&api, PERP_SYMBOL), deposit - first_withdraw);
        assert_eq!(api.adjustments(QUOTE_ID), -(deposit - first_withdraw));
        assert!(api.total_balance().is_global_zero());

        // 抽超余额（700_000 < 800_000）→ RiskIfInsufficient，账本不动。
        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, 800_000, 4), CommandResultCode::RiskIfInsufficient);
        assert_eq!(if_balance(&api, PERP_SYMBOL), deposit - first_withdraw);
        assert!(api.total_balance().is_global_zero());

        // 非法入参：负数 / 零 / 未知 symbol。
        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, -1, 5), CommandResultCode::RiskInvalidAmount);
        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, 0, 6), CommandResultCode::RiskInvalidAmount);
        assert_eq!(if_withdraw(&mut api, 99_999, 1_000, 7), CommandResultCode::InvalidSymbol);
        assert_eq!(if_balance(&api, PERP_SYMBOL), deposit - first_withdraw);
        assert!(api.total_balance().is_global_zero());

        // 再抽 200_000：available 剩 500_000。
        assert_eq!(if_withdraw(&mut api, PERP_SYMBOL, second_withdraw, 8), CommandResultCode::Success);
        assert_eq!(if_balance(&api, PERP_SYMBOL), deposit - first_withdraw - second_withdraw);
        assert_eq!(api.adjustments(QUOTE_ID), -(deposit - first_withdraw - second_withdraw));
        assert!(api.total_balance().is_global_zero());
    }
}
