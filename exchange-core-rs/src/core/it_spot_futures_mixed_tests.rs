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
//! 不可复刻而 **SKIP** 的 @Test（harness 缺相应基础设施）：
//!   - testSpotLockSurvivesLiquidation           —— 依赖强平触发（triggerLiquidation + updateCurrentPriceTo
//!                                                   挂对敲单推进 R2），set_mark_price 只设价、无等价物。
//!   - testSpotLockUnchangedAfterFundingFeeSettlement —— 依赖 SETTLE_FUNDINGFEES 命令，harness 未暴露。
//!   - testSpotLockSurvivesDelivery              —— 依赖 SETTLE_PNL（交割结算）命令，harness 未暴露。
//!   - testFundEventBidLockUnlock / testFundEventSpotFillTransfers /
//!     testFundEventSpotFillTransfersBidTaker / testFundEventDepositWithdrawReflectLock
//!                                                —— 依赖跨命令累计 fund event 捕获 + balance snapshot(free/locked)；
//!                                                   harness 只暴露 last_fund_events()（最近一条命令），且
//!                                                   BALANCE_ADJUSTMENT 在 Rust 引擎不产 fund event（无 DEPOSIT 事件），
//!                                                   无法复刻 DEPOSIT+LOCKED+UNLOCKED 累计序列。needs harness extension。
//!
//! 已翻译（9 条）：现货冻结的加/减/撤/成交/部分成交/拒单不污染/币种独立/提现边界（含期货保证金联合约束）。

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::margin_mode::MarginMode;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::exchange_api::{
        CancelOrderRequest, ExchangeApi, PlaceFuturesOrderRequest, PlaceOrderRequest,
    };

    // Java TestConstants：CURRENECY_USD=840, CURRENECY_XBT=3762。UID 取任意互异值。
    const QUOTE_ID: i32 = 840; // USD
    const BASE_ID: i32 = 3762; // XBT
    const SPOT_SYMBOL: i32 = 20001;
    const SPOT_TAKER_FEE: i64 = 2;

    const PERP_SYMBOL: i32 = 10000; // test 9 专用期货 symbol（initFutureSymbols().get(0)）

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
        assert_eq!(api.set_mark_price(PERP_SYMBOL, 1_000), CommandResultCode::Success);

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
}
