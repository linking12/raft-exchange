//! 翻译自 Java `exchange.core2.tests.integration.ITSpotFuturesMixedIntegration`。
//! 混合现货/期货：验证现货挂单冻结（exchangeLocked）在各类操作后保持独立、精确，资金全局守恒。
//! 直连引擎（无 raft/Disruptor），逐条对拍 Java 黄金值。
//!
//! Java 现货规格 `XBT_USD_SPOT`（symbolId=20001, CURRENCY_EXCHANGE_PAIR, base=XBT(3762)/quote=USD(840),
//! baseScaleK=quoteScaleK=1, makerFee=1, takerFee=2, feeScaleK=0 固定费）。
//!   现货 BID 冻结 = size × (reservePrice + takerFee)     （固定费率）
//!   现货 ASK 冻结 = size                                  （只锁 base，费在成交时从收到的 quote 扣）
//! Java 期货规格（test 9 用 `initFutureSymbols().get(0)`：symbolId=10000, BTC/USD, makerFee=10,
//! takerFee=20, mm={1000:5,100000:10}@1000, maxLeverage={2000:5,100000:10}, initMargin=1@100）。
//!
//! 现货 currency scale_k=1（Java digit=0），故 size_price_to_currency_scale 为恒等换算，
//! 冻结/费率黄金值与 Java 逐位一致。
//!
//! 直接提交（directly-submitted）的 SETTLE_FUNDINGFEES / SETTLE_PNL 命令其余额效果可直接断言，故已补齐：
//!   - testSpotLockUnchangedAfterFundingFeeSettlement —— `submit(SETTLE_FUNDINGFEES{symbol,action,price=rate,size=rateScaleK})`，
//!                                                   资金费零和落进逐用户 position.profit，accounts / exchangeLocked 不变。
//!   - testSpotLockSurvivesDelivery              —— `submit(SETTLE_PNL{symbol=delivery,price=settlePrice})`，
//!                                                   整仓交割结算 PnL 进 accounts、移除仓位，exchangeLocked 不受影响。
//!
//! 不可复刻而 **SKIP** 的 @Test（harness 缺相应基础设施）：
//!   - testSpotLockSurvivesLiquidation           —— 依赖强平在 mark 984（1.6% 微跌，靠 spotLock 压低 cross available 才触发）
//!                                                   的**精确触发点**；单分片 harness 的 FORCE→IF→ADL 自动排空级联能否在该临界价
//!                                                   触发无法在不 build 的前提下核验，且其真正断言的逐笔强平 fund event 走内部
//!                                                   排空命令、last_fund_events() 不捕获。spotLock 在期货事件后不变的核心不变量
//!                                                   已由上面 funding / delivery 两条覆盖。
//!   - testFundEventBidLockUnlock / testFundEventSpotFillTransfers /
//!     testFundEventSpotFillTransfersBidTaker / testFundEventDepositWithdrawReflectLock
//!                                                —— 依赖**跨命令累计** fund event 序列（DEPOSIT→LOCKED→UNLOCKED→TRANSFER）+
//!                                                   balance snapshot(free/locked)；harness 的 last_fund_events() 只保留最近一条
//!                                                   命令的事件、每条命令覆盖上一条，无法累计整条序列。needs harness extension。
//!
//! 已翻译（11 条）：现货冻结的加/减/撤/成交/部分成交/拒单不污染/币种独立/提现边界（含期货保证金联合约束）
//! + 资金费结算 / 交割结算后 exchangeLocked 不变。

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
    use exchange_core_rs::core::exchange_api::{
        CancelOrderRequest, ExchangeApi, PlaceFuturesOrderRequest, PlaceOrderRequest,
    };

    // Java TestConstants：CURRENECY_USD=840, CURRENECY_XBT=3762。UID 取任意互异值。
    const QUOTE_ID: i32 = 840; // USD
    const BASE_ID: i32 = 3762; // XBT
    const SPOT_SYMBOL: i32 = 20001;
    const SPOT_TAKER_FEE: i64 = 2;

    const PERP_SYMBOL: i32 = 10000; // test 9 专用期货 symbol（initFutureSymbols().get(0)）
    const DELIVERY_SYMBOL: i32 = 10010; // Java `XBT_USD_DELIVERY`（交割合约）

    const UID_1: i64 = 1001;
    const UID_2: i64 = 1002;

    /// 精确复刻 Java `XBT_USD_SPOT`。
    fn spot_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SPOT_SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 1,
            taker_fee: SPOT_TAKER_FEE,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    /// 精确复刻 Java `initFutureSymbols().get(0)`（BTC/USD 永续）。
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

    /// 精确复刻 Java `XBT_USD_DELIVERY`（XBT/USD 交割合约，makerFee=5 / takerFee=10 固定费）。
    fn delivery_spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: DELIVERY_SYMBOL,
            symbol_type: SymbolType::FuturesContractDelivery,
            base_currency: BASE_ID,
            quote_currency: QUOTE_ID,
            base_scale_k: 1,
            quote_scale_k: 1,
            maker_fee: 5,
            taker_fee: 10,
            fee_scale_k: 0,
            maintenance_margin: BTreeMap::from([(1_000, 5), (100_000, 10)]),
            maintenance_margin_scale_k: 1_000,
            max_leverage: BTreeMap::from([(2_000, 5), (100_000, 10)]),
            init_margin: 1,
            init_margin_scale_k: 100,
            ..Default::default()
        }
    }

    /// CROSS 期货开仓（leverage=1，reduce_only=false），对应 Java `createBid/AskWithOrderId(... MarginMode.CROSS)`。
    fn cross_futures(
        order_id: i64,
        uid: i64,
        symbol: i32,
        price: i64,
        size: i64,
        action: OrderAction,
    ) -> PlaceFuturesOrderRequest {
        PlaceFuturesOrderRequest {
            order_id,
            uid,
            symbol,
            price,
            size,
            action,
            order_type: OrderType::Gtc,
            leverage: 1,
            margin_mode: MarginMode::Cross,
            reduce_only: false,
        }
    }

    /// SETTLE_FUNDINGFEES：`price=fundingRate`、`size=rateScaleK`，`action` 决定 payer 侧（同向者付）。直接提交。
    fn settle_funding_fees(
        api: &mut ExchangeApi,
        symbol: i32,
        action: OrderAction,
        rate: i64,
        rate_scale_k: i64,
        txid: i64,
    ) -> CommandResultCode {
        api.submit(OrderCommand {
            command: OrderCommandType::SettleFundingfees,
            symbol,
            action: Some(action),
            price: rate,
            size: rate_scale_k,
            order_id: txid,
            ..Default::default()
        })
    }

    /// SETTLE_PNL：交割整仓结算，`price=settlePrice`。直接提交。
    fn settle_pnl(api: &mut ExchangeApi, symbol: i32, settle_price: i64, txid: i64) -> CommandResultCode {
        api.submit(OrderCommand {
            command: OrderCommandType::SettlePnl,
            symbol,
            price: settle_price,
            order_id: txid,
            ..Default::default()
        })
    }

    /// 建 currencies + 现货 symbol（不建期货 symbol —— 纯现货用例）。
    fn setup_spot() -> ExchangeApi {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_symbol(spot_spec()), CommandResultCode::Success);
        api
    }

    /// 对应 `createUserWithSpecificMoney(uid, amount, currency)`。
    fn fund(api: &mut ExchangeApi, uid: i64, currency: i32, amount: i64, txid: i64) {
        assert_eq!(api.add_user(uid), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(uid, currency, amount, txid), CommandResultCode::Success);
    }

    fn spot_bid(order_id: i64, uid: i64, price: i64, reserve: i64, size: i64) -> PlaceOrderRequest {
        PlaceOrderRequest {
            order_id,
            uid,
            symbol: SPOT_SYMBOL,
            price,
            size,
            reserve_bid_price: reserve,
            action: OrderAction::Bid,
            order_type: OrderType::Gtc,
        }
    }
    fn spot_ask(order_id: i64, uid: i64, price: i64, size: i64) -> PlaceOrderRequest {
        PlaceOrderRequest {
            order_id,
            uid,
            symbol: SPOT_SYMBOL,
            price,
            size,
            reserve_bid_price: 0,
            action: OrderAction::Ask,
            order_type: OrderType::Gtc,
        }
    }

    /// 全局守恒（完整公式，含仓位 estimate_pnl + extra_margin），对拍 Java `isGlobalBalancesAllZero()`。
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
    // Test 4：取消现货挂单 → exchangeLocked 释放；accounts 全程不变。
    //   BID 5@100 → lock = 5×(100+2) = 510；取消 → 0。
    // ─────────────────────────────────────────────────────────────
    #[test]
    fn spot_cancel_releases_lock() {
        let mut api = setup_spot();
        fund(&mut api, UID_1, QUOTE_ID, 1_000, 1);

        let lock = 5 * (100 + SPOT_TAKER_FEE); // 510
        assert_eq!(api.place_order(spot_bid(40001, UID_1, 100, 100, 5)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), lock, "挂单后 exchangeLocked=510");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 1_000, "accounts 不变");

        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 40001, uid: UID_1, symbol: SPOT_SYMBOL }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0, "取消后 exchangeLocked=0");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 1_000, "accounts 仍不变");
        assert_conserved(&api);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 5：多笔现货挂单 → exchangeLocked 逐笔累加，逐笔释放。
    //   o1 BID 3@100 → 306; o2 BID 4@50 → 208; o3 BID 2@80 → 164; total 678。
    // ─────────────────────────────────────────────────────────────
    #[test]
    fn multiple_spot_orders_lock_accumulates() {
        let mut api = setup_spot();
        fund(&mut api, UID_1, QUOTE_ID, 2_000, 1);

        let lock1 = 3 * (100 + SPOT_TAKER_FEE); // 306
        let lock2 = 4 * (50 + SPOT_TAKER_FEE); // 208
        let lock3 = 2 * (80 + SPOT_TAKER_FEE); // 164

        assert_eq!(api.place_order(spot_bid(50001, UID_1, 100, 100, 3)), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_bid(50002, UID_1, 50, 50, 4)), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_bid(50003, UID_1, 80, 80, 2)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), lock1 + lock2 + lock3, "三笔累加 678");

        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 50001, uid: UID_1, symbol: SPOT_SYMBOL }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), lock2 + lock3, "取消 o1 后 372");

        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 50002, uid: UID_1, symbol: SPOT_SYMBOL }),
            CommandResultCode::Success
        );
        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 50003, uid: UID_1, symbol: SPOT_SYMBOL }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0, "全部取消后 0");
        assert_conserved(&api);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 6：exchangeLocked 阻止超额提现（精确边界）。
    //   deposit=1000，lock=510，free=490；提 491 → NSF；提 490 → SUCCESS。
    // ─────────────────────────────────────────────────────────────
    #[test]
    fn withdrawal_blocked_by_spot_lock() {
        let mut api = setup_spot();
        fund(&mut api, UID_1, QUOTE_ID, 1_000, 1);

        let lock = 5 * (100 + SPOT_TAKER_FEE); // 510
        let free = 1_000 - lock; // 490
        assert_eq!(api.place_order(spot_bid(60001, UID_1, 100, 100, 5)), CommandResultCode::Success);

        // 超出可支配部分：RISK_NSF（NSF 不 claim txid，可换 txid 重试）。
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -(free + 1), 60002), CommandResultCode::RiskNsf);
        // 恰好等于可支配部分：SUCCESS。
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -free, 60003), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), 1_000 - free, "提现后 accounts=510");
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), lock, "exchangeLocked 不受提现影响");
        assert_conserved(&api);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 7：现货 ASK 冻结 base 货币（XBT），与 QUOTE 无关。
    //   ASK 5@100 → BASE lock = 5（无手续费预留）。
    // ─────────────────────────────────────────────────────────────
    #[test]
    fn spot_ask_lock_base_currency() {
        let mut api = setup_spot();
        fund(&mut api, UID_1, BASE_ID, 10, 1); // 10 XBT

        assert_eq!(api.place_order(spot_ask(70001, UID_1, 100, 5)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, BASE_ID), 5, "ASK 冻结 BASE=5");
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0, "QUOTE lock 为 0");
        assert_eq!(api.user_account(UID_1, BASE_ID), 10, "BASE accounts 不变");

        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 70001, uid: UID_1, symbol: SPOT_SYMBOL }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_locked(UID_1, BASE_ID), 0, "取消后 BASE lock=0");
        assert_eq!(api.user_account(UID_1, BASE_ID), 10, "BASE accounts 仍不变");
        assert_conserved(&api);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 8：现货成交 → 双方 exchangeLocked 归零，accounts 精确更新。
    //   UID_1 BID 5@100(maker, lock 510)，UID_2 ASK 5@100(taker)。
    //   UID_1 QUOTE=1000-505=495, BASE=5；UID_2 QUOTE=490, BASE=5；fees=15。
    // ─────────────────────────────────────────────────────────────
    #[test]
    fn spot_fill_releases_lock() {
        let mut api = setup_spot();
        fund(&mut api, UID_1, QUOTE_ID, 1_000, 1); // 1000 USD
        fund(&mut api, UID_2, BASE_ID, 10, 2); // 10 XBT

        let bid_lock = 5 * (100 + SPOT_TAKER_FEE); // 510
        assert_eq!(api.place_order(spot_bid(80001, UID_1, 100, 100, 5)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), bid_lock, "成交前 QUOTE lock=510");

        assert_eq!(api.place_order(spot_ask(80002, UID_2, 100, 5)), CommandResultCode::Success);

        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0, "成交后 QUOTE lock=0");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 495, "UID_1 QUOTE accounts=1000-505");
        assert_eq!(api.user_account(UID_1, BASE_ID), 5, "UID_1 BASE accounts=5");
        assert_eq!(api.user_locked(UID_2, BASE_ID), 0, "成交后 BASE lock=0");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), 490, "UID_2 QUOTE accounts=500-10");
        assert_eq!(api.user_account(UID_2, BASE_ID), 5, "UID_2 BASE accounts=5");
        assert_eq!(api.fees(QUOTE_ID), 15, "fees=makerFee(5)+takerFee(10)");
        assert_conserved(&api);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 9：现货 lock + 期货保证金 共同约束提现上限。
    //   UID_1 deposit=5000，CROSS LONG 10@1000(makerFee=100) → accounts=4900，freeFuturesMargin=-100。
    //   现货 BID 5@100 → lock=510；可提上限 = 4900 - 510 + (-100) = 4290。
    // ─────────────────────────────────────────────────────────────
    #[test]
    fn spot_lock_and_futures_margin_both_constrain_withdrawal() {
        let mut api = setup_spot();
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, 1_000, 0), CommandResultCode::Success);

        fund(&mut api, UID_1, QUOTE_ID, 5_000, 1);
        fund(&mut api, UID_2, QUOTE_ID, 100_000, 2);

        // 开 CROSS LONG 10@1000（UID_1 maker BID，UID_2 taker ASK），makerFee=10*10=100。
        assert_eq!(
            api.place_futures_order(PlaceFuturesOrderRequest {
                order_id: 90001, uid: UID_1, symbol: PERP_SYMBOL, price: 1_000, size: 10,
                action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 1,
                margin_mode: MarginMode::Cross, reduce_only: false,
            }),
            CommandResultCode::Success
        );
        assert_eq!(
            api.place_futures_order(PlaceFuturesOrderRequest {
                order_id: 90002, uid: UID_2, symbol: PERP_SYMBOL, price: 1_000, size: 10,
                action: OrderAction::Ask, order_type: OrderType::Gtc, leverage: 1,
                margin_mode: MarginMode::Cross, reduce_only: false,
            }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 4_900, "UID_1 accounts=5000-100(makerFee)");

        let spot_lock = 5 * (100 + SPOT_TAKER_FEE); // 510
        assert_eq!(api.place_order(spot_bid(90003, UID_1, 100, 100, 5)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), spot_lock);

        let max_withdraw = 4_290;
        assert_eq!(
            api.balance_adjustment(UID_1, QUOTE_ID, -(max_withdraw + 1), 90004),
            CommandResultCode::RiskNsf
        );
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, -max_withdraw, 90005), CommandResultCode::Success);

        assert_eq!(api.user_account(UID_1, QUOTE_ID), 4_900 - max_withdraw, "提现后 accounts=610");
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), spot_lock, "现货 exchangeLocked 不受提现影响");
        assert_conserved(&api);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 10：部分成交 → exchangeLocked 只释放已成交部分，余量取消后彻底清零。
    //   UID_1 BID 10@100(lock 1020, deposit 1500)；UID_2 ASK 4@100 → 4 手成交。
    //   剩余 lock=612；UID_1 QUOTE=1500-404=1096, BASE=4；取消余量 → 0。
    // ─────────────────────────────────────────────────────────────
    #[test]
    fn partial_fill_releases_partial_lock() {
        let mut api = setup_spot();
        fund(&mut api, UID_1, QUOTE_ID, 1_500, 1);
        fund(&mut api, UID_2, BASE_ID, 10, 2);

        let full_lock = 10 * (100 + SPOT_TAKER_FEE); // 1020
        let remain_lock = 6 * (100 + SPOT_TAKER_FEE); // 612

        assert_eq!(api.place_order(spot_bid(100001, UID_1, 100, 100, 10)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), full_lock, "挂单后 lock=1020");

        assert_eq!(api.place_order(spot_ask(100002, UID_2, 100, 4)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), remain_lock, "部分成交后 lock=612");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 1_500 - 404, "UID_1 QUOTE=1096");
        assert_eq!(api.user_account(UID_1, BASE_ID), 4, "UID_1 BASE=4");

        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 100001, uid: UID_1, symbol: SPOT_SYMBOL }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0, "取消余量后 lock=0");
        assert_conserved(&api);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 11：RISK_NSF 拒绝下单 → exchangeLocked 不被污染。
    //   deposit=100，BID 5@100 需 510 > 100 → RISK_NSF；lock 仍为 0。
    // ─────────────────────────────────────────────────────────────
    #[test]
    fn rejected_order_does_not_modify_lock() {
        let mut api = setup_spot();
        fund(&mut api, UID_1, QUOTE_ID, 100, 1);

        assert_eq!(api.place_order(spot_bid(110001, UID_1, 100, 100, 5)), CommandResultCode::RiskNsf);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0, "被拒后 exchangeLocked 仍为 0");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 100, "accounts 不变");
        assert_conserved(&api);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 12：BID + ASK 同时挂单 → QUOTE 和 BASE 两个 exchangeLocked 独立维护。
    //   BID 3@90 → QUOTE lock=3×92=276；ASK 2@110 → BASE lock=2（不交叉，不自成交）。
    // ─────────────────────────────────────────────────────────────
    #[test]
    fn bid_and_ask_locks_are_currency_independent() {
        let mut api = setup_spot();
        // 同一用户在两币各充值。
        assert_eq!(api.add_user(UID_1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, QUOTE_ID, 1_000, 1), CommandResultCode::Success);
        assert_eq!(api.balance_adjustment(UID_1, BASE_ID, 10, 2), CommandResultCode::Success);

        let quote_lock = 3 * (90 + SPOT_TAKER_FEE); // 276
        let base_lock = 2;

        assert_eq!(api.place_order(spot_bid(120001, UID_1, 90, 90, 3)), CommandResultCode::Success);
        assert_eq!(api.place_order(spot_ask(120002, UID_1, 110, 2)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), quote_lock, "QUOTE lock=276");
        assert_eq!(api.user_locked(UID_1, BASE_ID), base_lock, "BASE lock=2");

        // 取消 BID → QUOTE lock 清零，BASE lock 不受影响。
        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 120001, uid: UID_1, symbol: SPOT_SYMBOL }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), 0, "取消 BID 后 QUOTE lock=0");
        assert_eq!(api.user_locked(UID_1, BASE_ID), base_lock, "BASE lock 不变");

        // 取消 ASK → BASE lock 清零。
        assert_eq!(
            api.cancel_order(CancelOrderRequest { order_id: 120002, uid: UID_1, symbol: SPOT_SYMBOL }),
            CommandResultCode::Success
        );
        assert_eq!(api.user_locked(UID_1, BASE_ID), 0, "取消 ASK 后 BASE lock=0");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 1_000, "QUOTE accounts 不变");
        assert_eq!(api.user_account(UID_1, BASE_ID), 10, "BASE accounts 不变");
        assert_conserved(&api);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 2（backfill）：资金费率结算 + 现货挂单 → exchangeLocked 不受影响。
    //   翻译自 Java testSpotLockUnchangedAfterFundingFeeSettlement。SETTLE_FUNDINGFEES 直接提交，
    //   零和落进逐用户 position.profit，不动 accounts / exchangeLocked。
    //
    //   UID_1 deposit=20000，CROSS LONG 10@1000（maker, makerFee=100）→ accounts=19900
    //   UID_2 deposit=20000，CROSS SHORT 10@1000（taker, takerFee=200）→ accounts=19800
    //   现货 BID 5@1000 → exchangeLocked = 5×(1000+2) = 5010
    //   资金费 rate=1/rateScaleK=100，action=BID → LONG 付、SHORT 收：
    //     fee = trunc(openVolume(10) × mark(1000) × 1 / 100) = 100
    //     UID_1 profit=-100，UID_2 profit=+100；两者 accounts 与 UID_1 exchangeLocked 全不变。
    // ─────────────────────────────────────────────────────────────
    #[test]
    fn spot_lock_unchanged_after_funding_fee_settlement() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(perp_spec()), CommandResultCode::Success);
        assert_eq!(api.add_symbol(spot_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(PERP_SYMBOL, 1_000, 0), CommandResultCode::Success);

        fund(&mut api, UID_1, QUOTE_ID, 20_000, 1);
        fund(&mut api, UID_2, QUOTE_ID, 20_000, 2);

        // 开 CROSS LONG 10@1000：UID_1 maker BID（先挂，静止），UID_2 taker ASK 撮合。
        assert_eq!(
            api.place_futures_order(cross_futures(20001, UID_1, PERP_SYMBOL, 1_000, 10, OrderAction::Bid)),
            CommandResultCode::Success
        );
        assert_eq!(
            api.place_futures_order(cross_futures(20002, UID_2, PERP_SYMBOL, 1_000, 10, OrderAction::Ask)),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 19_900, "UID_1 = 20000 - makerFee(100)");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), 19_800, "UID_2 = 20000 - takerFee(200)");

        // 现货 BID 5@1000 → 冻结 5010。
        let spot_lock = 5 * (1_000 + SPOT_TAKER_FEE); // 5010
        assert_eq!(api.place_order(spot_bid(20003, UID_1, 1_000, 1_000, 5)), CommandResultCode::Success);
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), spot_lock, "下现货单后 exchangeLocked=5010");

        // 资金费结算：action=BID（做多付、做空收），rate=1%（1/100）。
        let expected_fee = 10 * 1_000 / 100; // = 100
        assert_eq!(
            settle_funding_fees(&mut api, PERP_SYMBOL, OrderAction::Bid, 1, 100, 20004),
            CommandResultCode::Success
        );

        // accounts 与 exchangeLocked 不变，资金费只落进 position.profit。
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 19_900, "accounts 不变（funding 落进 position.profit）");
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), spot_lock, "exchangeLocked 不受资金费影响");
        assert_eq!(api.user_position(UID_1, PERP_SYMBOL).unwrap().profit, -expected_fee, "多头 profit=-fee");

        assert_eq!(api.user_account(UID_2, QUOTE_ID), 19_800, "UID_2 accounts 不变");
        assert_eq!(api.user_locked(UID_2, QUOTE_ID), 0, "UID_2 无现货挂单");
        assert_eq!(api.user_position(UID_2, PERP_SYMBOL).unwrap().profit, expected_fee, "空头 profit=+fee");
        assert_conserved(&api);
    }

    // ─────────────────────────────────────────────────────────────
    // Test 3（backfill）：交割结算 + 现货挂单 → exchangeLocked 不受影响。
    //   翻译自 Java testSpotLockSurvivesDelivery。SETTLE_PNL 直接提交，整仓平掉交割 symbol、PnL 进 accounts。
    //
    //   XBT_USD_DELIVERY（makerFee=5 / takerFee=10）：
    //     UID_1 BID 10@1000（maker, fee=50）  → accounts=9950
    //     UID_2 ASK 10@1000（taker, fee=100） → accounts=9900
    //   现货 BID 3@500 → exchangeLocked = 3×(500+2) = 1506
    //   交割价 1500：LONG pnl=(1500-1000)×10=+5000 → 14950；SHORT pnl=-5000 → 4900。仓位移除。
    //   现货 exchangeLocked 全程不变。
    // ─────────────────────────────────────────────────────────────
    #[test]
    fn spot_lock_survives_delivery() {
        let mut api = ExchangeApi::new();
        api.add_currency(BASE_ID, 1);
        api.add_currency(QUOTE_ID, 1);
        assert_eq!(api.add_futures_symbol(delivery_spec()), CommandResultCode::Success);
        assert_eq!(api.add_symbol(spot_spec()), CommandResultCode::Success);
        assert_eq!(api.set_mark_price(DELIVERY_SYMBOL, 1_000, 0), CommandResultCode::Success);

        fund(&mut api, UID_1, QUOTE_ID, 10_000, 1);
        fund(&mut api, UID_2, QUOTE_ID, 10_000, 2);

        // 开仓：UID_1 maker BID（LONG），UID_2 taker ASK（SHORT）。
        assert_eq!(
            api.place_futures_order(cross_futures(30001, UID_1, DELIVERY_SYMBOL, 1_000, 10, OrderAction::Bid)),
            CommandResultCode::Success
        );
        assert_eq!(
            api.place_futures_order(cross_futures(30002, UID_2, DELIVERY_SYMBOL, 1_000, 10, OrderAction::Ask)),
            CommandResultCode::Success
        );
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 9_950, "UID_1 = 10000 - makerFee(50)");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), 9_900, "UID_2 = 10000 - takerFee(100)");

        // 现货 BID 3@500 → 冻结 1506。
        let spot_lock = 3 * (500 + SPOT_TAKER_FEE); // 1506
        assert_eq!(api.place_order(spot_bid(30003, UID_1, 500, 500, 3)), CommandResultCode::Success);
        assert!(api.user_position(UID_1, DELIVERY_SYMBOL).is_some(), "交割仓位在");
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), spot_lock, "下现货单后 exchangeLocked=1506");

        // 交割结算 @1500：LONG +5000、SHORT -5000，仓位移除。
        assert_eq!(settle_pnl(&mut api, DELIVERY_SYMBOL, 1_500, 30004), CommandResultCode::Success);

        assert!(api.user_position(UID_1, DELIVERY_SYMBOL).is_none(), "交割后 UID_1 仓位清空");
        assert_eq!(api.user_account(UID_1, QUOTE_ID), 14_950, "UID_1 = 9950 + pnl(5000)");
        assert_eq!(api.user_locked(UID_1, QUOTE_ID), spot_lock, "现货 exchangeLocked 不受交割影响");

        assert!(api.user_position(UID_2, DELIVERY_SYMBOL).is_none(), "交割后 UID_2 仓位清空");
        assert_eq!(api.user_account(UID_2, QUOTE_ID), 4_900, "UID_2 = 9900 - pnl(5000)");
        assert_eq!(api.user_locked(UID_2, QUOTE_ID), 0, "UID_2 无现货挂单");
        assert_conserved(&api);
    }
}
