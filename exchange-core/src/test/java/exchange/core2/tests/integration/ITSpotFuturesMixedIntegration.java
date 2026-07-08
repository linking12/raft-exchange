package exchange.core2.tests.integration;

import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.tests.util.ExchangeTestContainer;
import exchange.core2.tests.util.LatencyTools;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static exchange.core2.core.common.FundEvent.FundEventType.*;
import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 混合现货/期货场景：验证 exchangeLocked（现货挂单冻结）在各类期货事件后保持不变，资金全局守恒。
 *
 * <p>覆盖三类事件：
 * <ul>
 *   <li>强平（CROSS 多头被强平）</li>
 *   <li>资金费率结算（永续合约 fundingFee）</li>
 *   <li>交割结算（交割合约 delivery）</li>
 * </ul>
 *
 * <p>公共数字约定（baseScaleK=1, quoteScaleK=1, currencyScaleK=1）：
 * <pre>
 *   现货 BID 冻结 = size × (reservePrice + takerFee)   [固定费率，feeScaleK=0]
 *   期货手续费    = size × spec.makerFee / takerFee     [固定费率]
 *   fundingFee   = size × markPrice × fundingRate / rateScaleK
 *   deliveryPnl  = (settlePrice - entryPrice) × size
 * </pre>
 */
public final class ITSpotFuturesMixedIntegration {

    private static final int QUOTE_ID = CURRENECY_USD; // 840
    private static final int BASE_ID  = CURRENECY_XBT; // 3762

    private static final long UID_1 = 1001L;
    private static final long UID_2 = 1002L;

    /** XBT/USD 现货，makerFee=1 / takerFee=2（固定费率） */
    private static final CoreSymbolSpecification XBT_USD_SPOT = CoreSymbolSpecification.builder()
            .symbolId(20001)
            .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
            .baseCurrency(BASE_ID).baseScaleK(1)
            .quoteCurrency(QUOTE_ID).quoteScaleK(1)
            .makerFee(1).takerFee(2)
            .build();

    /** XBT/USD 交割合约，makerFee=5 / takerFee=10（固定费率） */
    private static final CoreSymbolSpecification XBT_USD_DELIVERY = CoreSymbolSpecification.builder()
            .symbolId(10010)
            .type(SymbolType.FUTURES_CONTRACT_DELIVERY)
            .baseCurrency(BASE_ID).baseScaleK(1)
            .quoteCurrency(QUOTE_ID).quoteScaleK(1)
            .makerFee(5).takerFee(10)
            .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
            .maintenanceMarginScaleK(1000)
            .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
            .initMargin(1).initMarginScaleK(100)
            .build();

    // ─────────────────────────────────────────────────────────────
    // Test 1：强平 + 现货挂单
    //
    // 设计要点：exchangeLocked 使有效余额降低，导致原本不会被强平的仓位被强平。
    // 验证：
    //   (a) 期货仓位被清空（强平完成）
    //   (b) 现货挂单 exchangeLocked 不受影响，仍等于下单时冻结量
    //   (c) 全局资金守恒
    //
    // 关键数字（initFutureSymbols symbol 0，makerFee=10/unit，takerFee=20/unit）：
    //   UID_1 deposit=400, 开多 10@1000，makerFee=100 → accounts=300
    //   现货挂单：BID 1 BTC @ 100 USD
    //     exchangeLocked = 1 × (100 + 2[takerFee]) = 102
    //   RISK_NSF 检查：balance(300) - existingLocked(0) - lock(102) + freeFuturesMargin(-100) = 98 ≥ 0 ✓
    //   markPrice 下调至 984：
    //     equity(有锁) = (300-102) + (984-1000)*10 = 198-160 = 38  ≤ mm(49.2) → 强平！
    //     equity(无锁) = 300 + (984-1000)*10 = 140 > mm(49.2) → 不强平
    // ─────────────────────────────────────────────────────────────
    @Test
    public void testSpotLockSurvivesLiquidation() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);

            List<CoreSymbolSpecification> perpSymbols = container.initFutureSymbols();
            CoreSymbolSpecification perp = perpSymbols.get(0); // symbolId=10000, makerFee=10
            container.addSymbol(XBT_USD_SPOT);
            container.initMarkPrice(perp.symbolId, 1000);

            container.createUserWithSpecificMoney(UID_1, 400, QUOTE_ID);
            container.createUserWithSpecificMoney(UID_2, 100_000, QUOTE_ID);

            long orderId = 10001L;
            // UID_1 BID 10 (maker, fee=100) ↔ UID_2 ASK 10 (taker, fee=200)
            container.createBidWithOrderId(orderId++, UID_1, 10, 1000, perp.symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(orderId++, UID_2, 10, 1000, perp.symbolId, MarginMode.CROSS);
            // 跨分片 maker 结果异步落账，用 groupingControl 等待 UID_1 的 fee 扣减落地
            container.getApi().groupingControl(0, 1);
            // UID_1 accounts = 400 - 100 = 300, CROSS LONG 10 @ 1000

            // 现货挂买单：1 BTC @ 100 USD，冻结 = 1 × (100 + 2) = 102 USD（无对手方，不成交）
            long spotLock = 1L * (100 + XBT_USD_SPOT.takerFee); // = 102
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(orderId++)
                    .symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(100).reservePrice(100).size(1).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, r ->
                    assertThat("下完现货单后 exchangeLocked=" + spotLock,
                            r.getExchangeLocked().get(QUOTE_ID), is(spotLock)));

            // 下调标记价格至 984 → 带锁时触发强平，无锁时不触发
            container.updateCurrentPriceTo(984, perp.symbolId, QUOTE_ID);

            // CROSS BP: wallet=400-100(makerFee)-102(spotLock)=198, fixedFee → BP=ceil((10000-(198-200))/10)=1001
            // BID 挂在 updateCurrentPriceTo 之后，避免被 UPDATE_PRICE_USER2 的 ASK@984 消耗
            container.createBidWithOrderId(orderId++, UID_2, 10, 1001, perp.symbolId, MarginMode.CROSS);

            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);
            LatencyTools.waitForCondition(15_000, () -> {
                try {
                    return container.getUserProfile(UID_1).getPositions().isEmpty();
                } catch (Exception e) {
                    return false;
                }
            });
            container.getApi().groupingControl(0, 1);
            Thread.sleep(200);

            container.validateUserState(UID_1, r -> {
                assertTrue(r.getPositions().isEmpty(), "强平后期货仓位应清空");
                assertThat("现货 exchangeLocked 不受强平影响，仍为 " + spotLock,
                        r.getExchangeLocked().get(QUOTE_ID), is(spotLock));
            });
            assertThat("全局资金守恒", container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 2：资金费率结算 + 现货挂单
    //
    // 验证：
    //   (a) position.profit 按资金费率正确更新
    //   (b) accounts 不变（funding 落到 position.profit，不直接扣 accounts）
    //   (c) exchangeLocked 不受影响
    //   (d) 全局守恒
    //
    // 数字（initFutureSymbols，makerFee=10，takerFee=20）：
    //   UID_1 deposit=20000，开多 10@1000，makerFee=100 → accounts=19900
    //   现货挂买单：BID 5 BTC @ 1000
    //     exchangeLocked = 5 × (1000 + 2[takerFee]) = 5010
    //   fundingFee = size(10) × markPrice(1000) × rate(1) / rateScaleK(100) = 100
    //   UID_1 多头付费 → profit=-100；UID_2 空头收费 → profit=+100
    // ─────────────────────────────────────────────────────────────
    @Test
    public void testSpotLockUnchangedAfterFundingFeeSettlement() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            List<CoreSymbolSpecification> perpSymbols = container.initFutureSymbols();
            CoreSymbolSpecification perp = perpSymbols.get(0);
            container.addSymbol(XBT_USD_SPOT);
            container.initMarkPrice(perp.symbolId, 1000);

            container.createUserWithSpecificMoney(UID_1, 20_000, QUOTE_ID);
            container.createUserWithSpecificMoney(UID_2, 20_000, QUOTE_ID);

            long orderId = 20001L;
            container.createBidWithOrderId(orderId++, UID_1, 10, 1000, perp.symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(orderId++, UID_2, 10, 1000, perp.symbolId, MarginMode.CROSS);
            // UID_1 accounts=19900 (makerFee=100), UID_2 accounts=19800 (takerFee=200)

            // 现货挂买单：BID 5 BTC @ 1000，冻结 = 5 × (1000 + 2) = 5010 USD（无对手方）
            long spotLock = 5L * (1000 + XBT_USD_SPOT.takerFee); // = 5010
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(orderId++)
                    .symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(1000).reservePrice(1000).size(5).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, r ->
                    assertThat(r.getExchangeLocked().get(QUOTE_ID), is(spotLock)));

            // 资金费率结算：BID=多头付费，rate=1%, markPrice=1000
            long expectedFundingFee = 10L * 1000 * 1 / 100; // = 100
            container.submitCommandSync(ApiSettleFundingFees.builder()
                    .transactionId(orderId++)
                    .action(OrderAction.BID)
                    .symbol(perp.symbolId)
                    .rateScaleK(100).fundingRate(1)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, r -> {
                assertThat("accounts 不变（funding 落入 position.profit）",
                        r.getAccounts().get(QUOTE_ID), is(19900L));
                assertThat("exchangeLocked 不受资金费率影响",
                        r.getExchangeLocked().get(QUOTE_ID), is(spotLock));
                assertThat("多头 profit = -fundingFee",
                        r.getPositions().get(perp.symbolId).get(0).profit, is(-expectedFundingFee));
            });
            container.validateUserState(UID_2, r -> {
                assertThat("UID_2 accounts 不变",
                        r.getAccounts().get(QUOTE_ID), is(19800L));
                assertThat("UID_2 无现货挂单",
                        r.getExchangeLocked().get(QUOTE_ID), is(0L));
                assertThat("空头 profit = +fundingFee",
                        r.getPositions().get(perp.symbolId).get(0).profit, is(expectedFundingFee));
            });
            assertThat("全局资金守恒", container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 3：交割结算 + 现货挂单
    //
    // 验证：
    //   (a) 仓位清空，accounts 正确更新（含 PnL）
    //   (b) exchangeLocked 不受交割影响
    //   (c) 全局守恒
    //
    // 数字（XBT_USD_DELIVERY，makerFee=5，takerFee=10）：
    //   UID_1 BID 10@1000 (maker)：fee=50  → accounts=9950
    //   UID_2 ASK 10@1000 (taker)：fee=100 → accounts=9900
    //   现货挂买单：BID 3 BTC @ 500
    //     exchangeLocked = 3 × (500 + 2[spot takerFee]) = 1506
    //   交割价 1500 → pnl：
    //     UID_1 多头 pnl = (1500-1000)×10 = +5000 → accounts=14950
    //     UID_2 空头 pnl = (1000-1500)×10 = -5000 → accounts=4900
    // ─────────────────────────────────────────────────────────────
    @Test
    public void testSpotLockSurvivesDelivery() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.initFutureSymbols(); // 仅用于注册 XBT/USD 货币，不使用返回的永续合约
            container.addSymbol(XBT_USD_DELIVERY);
            container.addSymbol(XBT_USD_SPOT);
            container.initMarkPrice(XBT_USD_DELIVERY.symbolId, 1000);

            container.createUserWithSpecificMoney(UID_1, 10_000, QUOTE_ID);
            container.createUserWithSpecificMoney(UID_2, 10_000, QUOTE_ID);

            long orderId = 30001L;
            // UID_1 (maker, makerFee=50), UID_2 (taker, takerFee=100)
            container.createBidWithOrderId(orderId++, UID_1, 10, 1000, XBT_USD_DELIVERY.symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(orderId++, UID_2, 10, 1000, XBT_USD_DELIVERY.symbolId, MarginMode.CROSS);
            // UID_1 accounts = 10000 - 50 = 9950, UID_2 accounts = 10000 - 100 = 9900

            // 现货挂买单：BID 3 BTC @ 500，冻结 = 3 × (500 + 2) = 1506 USD（无对手方）
            long spotLock = 3L * (500 + XBT_USD_SPOT.takerFee); // = 1506
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(orderId++)
                    .symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(500).reservePrice(500).size(3).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, r -> {
                assertThat(r.getPositions().size(), is(1));
                assertThat(r.getExchangeLocked().get(QUOTE_ID), is(spotLock));
            });

            // 交割结算：价格 1500（多头盈 +5000，空头亏 -5000）
            container.submitCommandSync(ApiSettlePNL.builder()
                    .symbol(XBT_USD_DELIVERY.symbolId)
                    .settlePrice(1500L)
                    .build(), CommandResultCode.SUCCESS);

            long uid1AccountsAfterDelivery = 9950 + 5000; // = 14950
            long uid2AccountsAfterDelivery = 9900 - 5000; // = 4900

            container.validateUserState(UID_1, r -> {
                assertTrue(r.getPositions().isEmpty(), "交割后 UID_1 仓位应清空");
                assertThat("UID_1 accounts = (deposit-makerFee) + pnl = 9950 + 5000",
                        r.getAccounts().get(QUOTE_ID), is(uid1AccountsAfterDelivery));
                assertThat("现货 exchangeLocked 不受交割影响，仍为 " + spotLock,
                        r.getExchangeLocked().get(QUOTE_ID), is(spotLock));
            });
            container.validateUserState(UID_2, r -> {
                assertTrue(r.getPositions().isEmpty(), "交割后 UID_2 仓位应清空");
                assertThat("UID_2 accounts = (deposit-takerFee) + pnl = 9900 - 5000",
                        r.getAccounts().get(QUOTE_ID), is(uid2AccountsAfterDelivery));
                assertThat("UID_2 无现货挂单", r.getExchangeLocked().get(QUOTE_ID), is(0L));
            });
            assertThat("全局资金守恒", container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 4：取消现货挂单 → exchangeLocked 释放
    //
    // 验证：
    //   (a) 挂单后 exchangeLocked = size × (reservePrice + takerFee)
    //   (b) 取消后 exchangeLocked = 0
    //   (c) accounts 全程不变（ 挂单不扣 accounts）
    //
    // 数字：BID 5@100，takerFee=2 → lock = 5×102 = 510
    // ─────────────────────────────────────────────────────────────
    @Test
    public void testSpotCancelReleasesLock() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.initFutureSymbols();
            container.addSymbol(XBT_USD_SPOT);

            container.createUserWithSpecificMoney(UID_1, 1_000, QUOTE_ID);

            long orderId = 40001L;
            long lock = 5L * (100 + XBT_USD_SPOT.takerFee); // = 510
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(orderId)
                    .symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(100).reservePrice(100).size(5).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, r -> {
                assertThat("挂单后 exchangeLocked", r.getExchangeLocked().get(QUOTE_ID), is(lock));
                assertThat("accounts 不变", r.getAccounts().get(QUOTE_ID), is(1_000L));
            });

            container.cancelOrder(UID_1, orderId, XBT_USD_SPOT.symbolId);

            container.validateUserState(UID_1, r -> {
                assertThat("取消后 exchangeLocked=0", r.getExchangeLocked().get(QUOTE_ID), is(0L));
                assertThat("accounts 仍不变", r.getAccounts().get(QUOTE_ID), is(1_000L));
            });
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 5：多笔现货挂单 → exchangeLocked 逐笔累加，逐笔释放
    //
    // 三笔挂单（不同价格）：
    //   o1: BID 3@100 → lock = 3×102 = 306
    //   o2: BID 4@50  → lock = 4×52  = 208
    //   o3: BID 2@80  → lock = 2×82  = 164
    //   total = 678
    // 取消 o1 → 372；全部取消 → 0
    // ─────────────────────────────────────────────────────────────
    @Test
    public void testMultipleSpotOrdersLockAccumulates() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.initFutureSymbols();
            container.addSymbol(XBT_USD_SPOT);

            container.createUserWithSpecificMoney(UID_1, 2_000, QUOTE_ID);

            long o1 = 50001L, o2 = 50002L, o3 = 50003L;
            long lock1 = 3L * (100 + XBT_USD_SPOT.takerFee); // = 306
            long lock2 = 4L * (50  + XBT_USD_SPOT.takerFee); // = 208
            long lock3 = 2L * (80  + XBT_USD_SPOT.takerFee); // = 164

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(o1).symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(100).reservePrice(100).size(3).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(o2).symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(50).reservePrice(50).size(4).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(o3).symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(80).reservePrice(80).size(2).build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, r ->
                    assertThat("三笔累加 " + (lock1+lock2+lock3),
                            r.getExchangeLocked().get(QUOTE_ID), is(lock1 + lock2 + lock3)));

            container.cancelOrder(UID_1, o1, XBT_USD_SPOT.symbolId);
            container.validateUserState(UID_1, r ->
                    assertThat("取消 o1 后", r.getExchangeLocked().get(QUOTE_ID), is(lock2 + lock3)));

            container.cancelOrder(UID_1, o2, XBT_USD_SPOT.symbolId);
            container.cancelOrder(UID_1, o3, XBT_USD_SPOT.symbolId);
            container.validateUserState(UID_1, r ->
                    assertThat("全部取消后", r.getExchangeLocked().get(QUOTE_ID), is(0L)));

            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 6：exchangeLocked 阻止超额提现（精确边界）
    //
    // deposit=1000，现货挂单 lock=510，可支配 free=490
    //   提现 491 → RISK_NSF（超 1）
    //   提现 490 → SUCCESS（恰好边界）
    //   提现后 accounts=510，order 仍在挂，exchangeLocked 不变
    // ─────────────────────────────────────────────────────────────
    @Test
    public void testWithdrawalBlockedBySpotLock() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.initFutureSymbols();
            container.addSymbol(XBT_USD_SPOT);

            container.createUserWithSpecificMoney(UID_1, 1_000, QUOTE_ID);

            long orderId = 60001L;
            long lock = 5L * (100 + XBT_USD_SPOT.takerFee); // = 510
            long free = 1_000L - lock;                        // = 490

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(orderId)
                    .symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(100).reservePrice(100).size(5).build(),
                    CommandResultCode.SUCCESS);

            // 超出可支配部分：RISK_NSF
            container.submitCommandSync(
                    ApiAdjustUserBalance.builder().uid(UID_1).transactionId(60002L)
                            .amount(-(free + 1)).currency(QUOTE_ID).build(),
                    CommandResultCode.RISK_NSF);

            // 恰好等于可支配部分：SUCCESS
            container.submitCommandSync(
                    ApiAdjustUserBalance.builder().uid(UID_1).transactionId(60003L)
                            .amount(-free).currency(QUOTE_ID).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, r -> {
                assertThat("提现后 accounts = 1000 - 490", r.getAccounts().get(QUOTE_ID), is(1_000L - free));
                assertThat("exchangeLocked 不受提现影响", r.getExchangeLocked().get(QUOTE_ID), is(lock));
            });
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 7：现货 ASK 冻结 base 货币（XBT），与 QUOTE 无关
    //
    // ASK 卖出 base：exchangeLocked[BASE] = size（无手续费预留，fee 在成交时从收到的 quote 扣）
    // 取消后 BASE 锁释放，accounts[BASE] 全程不变
    //
    // 数字：UID_1 持有 10 XBT，ASK 5@100 → BASE lock=5
    // ─────────────────────────────────────────────────────────────
    @Test
    public void testSpotAskLockBaseCurrency() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.initFutureSymbols();
            container.addSymbol(XBT_USD_SPOT);

            container.createUserWithSpecificMoney(UID_1, 10, BASE_ID); // 10 XBT

            long orderId = 70001L;
            long baseLock = 5L; // ASK lock = size (base only)
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(orderId)
                    .symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(100).size(5).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, r -> {
                assertThat("ASK 冻结 BASE", r.getExchangeLocked().get(BASE_ID), is(baseLock));
                assertThat("QUOTE lock 为 0", r.getExchangeLocked().get(QUOTE_ID), is(0L));
                assertThat("BASE accounts 不变", r.getAccounts().get(BASE_ID), is(10L));
            });

            container.cancelOrder(UID_1, orderId, XBT_USD_SPOT.symbolId);

            container.validateUserState(UID_1, r -> {
                assertThat("取消后 BASE lock=0", r.getExchangeLocked().get(BASE_ID), is(0L));
                assertThat("BASE accounts 仍不变", r.getAccounts().get(BASE_ID), is(10L));
            });
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 8：现货成交 → 双方 exchangeLocked 归零，accounts 精确更新
    //
    // UID_1 BID 5@100（resting=maker）：lock = 510
    // UID_2 ASK 5@100（aggressive=taker）
    //
    // 成交后（固定费率 makerFee=1, takerFee=2）：
    //   lockedForMatched = 5×102 = 510
    //   amountDiffRelease = 5×(takerFee-makerFee) = 5   [holdPrice==price，无差价退款]
    //   UID_1 accounts[QUOTE] = 1000 + (5-510) = 495
    //   UID_1 accounts[BASE]  = 0 + 5 = 5
    //   UID_2 accounts[QUOTE] = 0 + (500-takerFee(10)) = 490
    //   UID_2 accounts[BASE]  = 10 - 5 = 5
    //   fees = makerFee(5) + takerFee(10) = 15 USD
    // ─────────────────────────────────────────────────────────────
    @Test
    public void testSpotFillReleasesLock() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.initFutureSymbols();
            container.addSymbol(XBT_USD_SPOT);

            container.createUserWithSpecificMoney(UID_1, 1_000, QUOTE_ID); // 1000 USD
            container.createUserWithSpecificMoney(UID_2, 10,    BASE_ID);  // 10 XBT

            long o1 = 80001L, o2 = 80002L;
            long bidLock = 5L * (100 + XBT_USD_SPOT.takerFee); // = 510

            // UID_1 挂买单（resting → maker）
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(o1).symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(100).reservePrice(100).size(5).build(),
                    CommandResultCode.SUCCESS);
            container.validateUserState(UID_1, r ->
                    assertThat("成交前 QUOTE lock", r.getExchangeLocked().get(QUOTE_ID), is(bidLock)));

            // UID_2 挂卖单 → 成交（taker）
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2).orderId(o2).symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(100).size(5).build(),
                    CommandResultCode.SUCCESS);

            // maker 退还差额 = 5×(takerFee-makerFee)=5；实付 = 510-5 = 505
            long uid1QuoteAfter = 1_000 - 505; // = 495
            // taker fee = 5×2 = 10；收到 500-10 = 490 USD
            long uid2QuoteAfter = 490;

            container.validateUserState(UID_1, r -> {
                assertThat("成交后 QUOTE lock=0", r.getExchangeLocked().get(QUOTE_ID), is(0L));
                assertThat("UID_1 QUOTE accounts", r.getAccounts().get(QUOTE_ID), is(uid1QuoteAfter));
                assertThat("UID_1 BASE accounts",  r.getAccounts().get(BASE_ID),  is(5L));
            });
            container.validateUserState(UID_2, r -> {
                assertThat("成交后 BASE lock=0", r.getExchangeLocked().get(BASE_ID), is(0L));
                assertThat("UID_2 QUOTE accounts", r.getAccounts().get(QUOTE_ID), is(uid2QuoteAfter));
                assertThat("UID_2 BASE accounts",  r.getAccounts().get(BASE_ID),  is(5L));
            });
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 9：现货 lock + 期货保证金 共同约束提现上限
    //
    // UID_1 deposit=5000，开 CROSS LONG 10@1000（makerFee=100）→ accounts=4900
    //   freeFuturesMargin = min(0+0-initMargin(100), 0-mm(50)) = -100
    // 现货挂单 BID 5@100 → lock=510
    //   可提金额 = accounts(4900) - lock(510) + freeFuturesMargin(-100) = 4290
    //   提现 4291 → RISK_NSF；提现 4290 → SUCCESS
    // ─────────────────────────────────────────────────────────────
    @Test
    public void testSpotLockAndFuturesMarginBothConstrainWithdrawal() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            List<CoreSymbolSpecification> perpSymbols = container.initFutureSymbols();
            CoreSymbolSpecification perp = perpSymbols.get(0); // makerFee=10
            container.addSymbol(XBT_USD_SPOT);
            container.initMarkPrice(perp.symbolId, 1000);

            container.createUserWithSpecificMoney(UID_1, 5_000, QUOTE_ID);
            container.createUserWithSpecificMoney(UID_2, 100_000, QUOTE_ID);

            AtomicLong orderId = new AtomicLong(90001L);
            // 开 CROSS LONG 10@1000（makerFee=100）
            container.createBidWithOrderId(orderId.getAndIncrement(), UID_1, 10, 1000, perp.symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(orderId.getAndIncrement(), UID_2, 10, 1000, perp.symbolId, MarginMode.CROSS);
            container.getApi().groupingControl(0, 1); // 等待 maker 侧 fee 落账
            // UID_1 accounts = 5000 - 100 = 4900

            long spotLock = 5L * (100 + XBT_USD_SPOT.takerFee); // = 510
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(orderId.getAndIncrement())
                    .symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(100).reservePrice(100).size(5).build(),
                    CommandResultCode.SUCCESS);

            // 可提上限 = 4900 - 510 + (-100) = 4290
            long maxWithdraw = 4290L;

            container.submitCommandSync(
                    ApiAdjustUserBalance.builder().uid(UID_1).transactionId(orderId.getAndIncrement())
                            .amount(-(maxWithdraw + 1)).currency(QUOTE_ID).build(),
                    CommandResultCode.RISK_NSF);

            container.submitCommandSync(
                    ApiAdjustUserBalance.builder().uid(UID_1).transactionId(orderId.getAndIncrement())
                            .amount(-maxWithdraw).currency(QUOTE_ID).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, r -> {
                assertThat("提现后 accounts = 4900 - 4290",
                        r.getAccounts().get(QUOTE_ID), is(4900L - maxWithdraw));
                assertThat("现货 exchangeLocked 不受提现影响",
                        r.getExchangeLocked().get(QUOTE_ID), is(spotLock));
            });
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 10：部分成交 → exchangeLocked 只释放已成交部分，余量取消后彻底清零
    //
    // UID_1 BID 10@100（resting=maker），lock = 10×102 = 1020；deposit=1500 > lock
    // UID_2 ASK 4@100（taker）→ 4 手成交
    //   释放 lock = 4×102 = 408 → 剩余 lock = 612
    //   UID_1 accounts[QUOTE] += (4×(takerFee-makerFee) - 4×102) = -404 → 1096
    //   UID_1 accounts[BASE] += 4
    // 取消余量 6 手 → lock = 612 - 6×102 = 0
    // ─────────────────────────────────────────────────────────────
    @Test
    public void testPartialFillReleasesPartialLock() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.initFutureSymbols();
            container.addSymbol(XBT_USD_SPOT);

            container.createUserWithSpecificMoney(UID_1, 1_500, QUOTE_ID); // 需大于 lock=1020
            container.createUserWithSpecificMoney(UID_2, 10,    BASE_ID);

            long o1 = 100001L, o2 = 100002L;
            long fullLock   = 10L * (100 + XBT_USD_SPOT.takerFee); // = 1020
            long remainLock =  6L * (100 + XBT_USD_SPOT.takerFee); // = 612

            // UID_1 挂买单 10 手（resting）
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(o1).symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(100).reservePrice(100).size(10).build(),
                    CommandResultCode.SUCCESS);
            container.validateUserState(UID_1, r ->
                    assertThat("挂单后 lock=1020", r.getExchangeLocked().get(QUOTE_ID), is(fullLock)));

            // UID_2 挂卖单 4 手 → 部分成交
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2).orderId(o2).symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(100).size(4).build(),
                    CommandResultCode.SUCCESS);

            // 部分成交后：lock 减少已成交部分 → 剩余 612
            // UID_1 实付：4×price(100) + 4×makerFee(1) = 404；退还 takerFee-makerFee 差 = 4
            long uid1QuoteAfterPartial = 1_500 - 404; // = 1096
            container.validateUserState(UID_1, r -> {
                assertThat("部分成交后 lock=612", r.getExchangeLocked().get(QUOTE_ID), is(remainLock));
                assertThat("UID_1 QUOTE accounts", r.getAccounts().get(QUOTE_ID), is(uid1QuoteAfterPartial));
                assertThat("UID_1 BASE accounts",  r.getAccounts().get(BASE_ID),  is(4L));
            });

            // 取消余量 6 手 → lock 完全释放
            container.cancelOrder(UID_1, o1, XBT_USD_SPOT.symbolId);
            container.validateUserState(UID_1, r ->
                    assertThat("取消余量后 lock=0", r.getExchangeLocked().get(QUOTE_ID), is(0L)));

            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 11：RISK_NSF 拒绝下单 → exchangeLocked 不被污染
    //
    // 余额不足时，RiskEngine 在 canPlace 检查失败后直接返回，
    // exchangeLocked.addToValue 不会被执行 → lock 仍为 0。
    //
    // 数字：deposit=100，尝试 BID 5@100 → lock 需 510 > 100 → RISK_NSF
    // ─────────────────────────────────────────────────────────────
    @Test
    public void testRejectedOrderDoesNotModifyLock() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.initFutureSymbols();
            container.addSymbol(XBT_USD_SPOT);

            container.createUserWithSpecificMoney(UID_1, 100, QUOTE_ID);

            long lock = 5L * (100 + XBT_USD_SPOT.takerFee); // = 510，远超 100

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(110001L).symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(100).reservePrice(100).size(5).build(),
                    CommandResultCode.RISK_NSF);

            container.validateUserState(UID_1, r -> {
                assertThat("被拒后 exchangeLocked 不变（仍为 0）",
                        r.getExchangeLocked().get(QUOTE_ID), is(0L));
                assertThat("accounts 不变", r.getAccounts().get(QUOTE_ID), is(100L));
            });
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 12：BID + ASK 同时挂单 → QUOTE 和 BASE 两个 exchangeLocked 独立维护
    //
    // 使用不交叉的价格（BID 低于 ASK）避免自成交：
    //   BID 3@90  → exchangeLocked[QUOTE] = 3×(90+2) = 276
    //   ASK 2@110 → exchangeLocked[BASE]  = 2
    // 取消 BID → QUOTE lock=0，BASE lock 不受影响
    // 取消 ASK → BASE lock=0
    // ─────────────────────────────────────────────────────────────
    @Test
    public void testBidAndAskLocksAreCurrencyIndependent() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.initFutureSymbols();
            container.addSymbol(XBT_USD_SPOT);

            container.createUserWithSpecificMoney(UID_1, 1_000, QUOTE_ID);
            container.createUserWithSpecificMoney(UID_1, 10,    BASE_ID);

            long bidOrderId = 120001L, askOrderId = 120002L;
            long quoteLock = 3L * (90 + XBT_USD_SPOT.takerFee); // = 3×92 = 276
            long baseLock  = 2L;                                  // ASK: size only

            // BID@90 < ASK@110，不会自成交
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(bidOrderId).symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(90).reservePrice(90).size(3).build(),
                    CommandResultCode.SUCCESS);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(askOrderId).symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(110).size(2).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, r -> {
                assertThat("QUOTE lock=306", r.getExchangeLocked().get(QUOTE_ID), is(quoteLock));
                assertThat("BASE  lock=2",  r.getExchangeLocked().get(BASE_ID),  is(baseLock));
            });

            // 取消 BID → QUOTE lock 清零，BASE lock 不受影响
            container.cancelOrder(UID_1, bidOrderId, XBT_USD_SPOT.symbolId);
            container.validateUserState(UID_1, r -> {
                assertThat("取消 BID 后 QUOTE lock=0", r.getExchangeLocked().get(QUOTE_ID), is(0L));
                assertThat("BASE lock 不变",           r.getExchangeLocked().get(BASE_ID),  is(baseLock));
            });

            // 取消 ASK → BASE lock 清零
            container.cancelOrder(UID_1, askOrderId, XBT_USD_SPOT.symbolId);
            container.validateUserState(UID_1, r -> {
                assertThat("取消 ASK 后 BASE lock=0",  r.getExchangeLocked().get(BASE_ID),  is(0L));
                assertThat("QUOTE accounts 不变", r.getAccounts().get(QUOTE_ID), is(1_000L));
                assertThat("BASE  accounts 不变", r.getAccounts().get(BASE_ID),  is(10L));
            });
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 13：fundEvent LOCKED / UNLOCKED 事件字段准确性
    //
    // 验证现货挂单（BID）触发的 LOCKED 事件中：
    //   free  = accounts - exchangeLocked = 1000 - 510 = 490
    //   locked = calculateLocked = exchangeLocked = 510
    // 取消后 UNLOCKED 事件：free=1000, locked=0
    // 同时验证 DEPOSIT 事件在无挂单时：free=1000, locked=0
    // ─────────────────────────────────────────────────────────────
    @Test
    public void testFundEventBidLockUnlock() throws Exception {
        List<FundEventSnap> events = Collections.synchronizedList(new ArrayList<>());
        IEventsHandler4Test localHandler = createCapturingHandler(events);
        SimpleEventsProcessor4Test processor = new SimpleEventsProcessor4Test(localHandler);

        try (ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.baseBuilder().build(), processor)) {
            container.initFutureSymbols();
            container.addSymbol(XBT_USD_SPOT);
            container.createUserWithSpecificMoney(UID_1, 1_000, QUOTE_ID);

            long orderId = 130001L;
            long lock = 5L * (100 + XBT_USD_SPOT.takerFee); // = 510

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(orderId)
                    .symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(100).reservePrice(100).size(5).build(),
                    CommandResultCode.SUCCESS);

            container.cancelOrder(UID_1, orderId, XBT_USD_SPOT.symbolId);
            container.getUserProfile(UID_1); // trigger R2 group change to flush UNLOCKED event
            LatencyTools.waitForCondition(1_000, () -> events.size() >= 3);
            assertThat("共 3 个 fund events：DEPOSIT + LOCKED + UNLOCKED", events.size(), is(3));

            FundEventSnap dep = events.get(0);
            assertThat("DEPOSIT uid", dep.uid, is(UID_1));
            assertThat("DEPOSIT type", dep.type, is(DEPOSIT));
            assertThat("DEPOSIT currency", dep.currency, is(QUOTE_ID));
            assertThat("DEPOSIT free=1000", dep.free, is(1_000L));
            assertThat("DEPOSIT locked=0（无挂单）", dep.locked, is(0L));

            FundEventSnap loc = events.get(1);
            assertThat("LOCKED uid", loc.uid, is(UID_1));
            assertThat("LOCKED type", loc.type, is(LOCKED));
            assertThat("LOCKED currency", loc.currency, is(QUOTE_ID));
            assertThat("LOCKED free=490", loc.free, is(1_000L - lock));
            assertThat("LOCKED locked=510", loc.locked, is(lock));

            FundEventSnap unl = events.get(2);
            assertThat("UNLOCKED uid", unl.uid, is(UID_1));
            assertThat("UNLOCKED type", unl.type, is(UNLOCKED));
            assertThat("UNLOCKED currency", unl.currency, is(QUOTE_ID));
            assertThat("UNLOCKED free=1000（全部恢复）", unl.free, is(1_000L));
            assertThat("UNLOCKED locked=0", unl.locked, is(0L));

            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 14：现货成交时 TRANSFER 事件字段准确性
    //
    // 成交后：
    //   maker(UID_1 BID)  TRANSFER[QUOTE]: free=495, locked=0
    //   maker(UID_1 BID)  TRANSFER[BASE]:  free=5,   locked=0
    //   taker(UID_2 ASK)  TRANSFER[QUOTE]: free=490, locked=0
    //   taker(UID_2 ASK)  TRANSFER[BASE]:  free=5,   locked=0
    //
    // ASK 成交前 LOCKED[BASE] 事件：
    //   UID_2 LOCKED[BASE]: free=5, locked=5
    //
    // 总计 9 个事件（2 DEPOSIT + 1 LOCKED[BID] + 1 LOCKED[ASK] + 1 UNLOCKED[maker 差价/费率退回] + 4 TRANSFER）
    // ─────────────────────────────────────────────────────────────
    @Test
    public void testFundEventSpotFillTransfers() throws Exception {
        List<FundEventSnap> events = Collections.synchronizedList(new ArrayList<>());
        IEventsHandler4Test localHandler = createCapturingHandler(events);
        SimpleEventsProcessor4Test processor = new SimpleEventsProcessor4Test(localHandler);

        try (ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.baseBuilder().build(), processor)) {
            container.initFutureSymbols();
            container.addSymbol(XBT_USD_SPOT);

            container.createUserWithSpecificMoney(UID_1, 1_000, QUOTE_ID);
            container.createUserWithSpecificMoney(UID_2, 10,    BASE_ID);

            long o1 = 140001L, o2 = 140002L;
            long bidLock = 5L * (100 + XBT_USD_SPOT.takerFee); // = 510

            // UID_1 挂买（resting = maker）
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(o1).symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(100).reservePrice(100).size(5).build(),
                    CommandResultCode.SUCCESS);

            // UID_2 挂卖 → 立即成交（aggressive = taker）
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2).orderId(o2).symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(100).size(5).build(),
                    CommandResultCode.SUCCESS);

            container.getUserProfile(UID_1); // trigger R2 group change to flush TRANSFER events

            // 事件顺序：
            //  [0] DEPOSIT  UID_1 QUOTE   free=1000  locked=0
            //  [1] DEPOSIT  UID_2 BASE    free=10    locked=0
            //  [2] LOCKED   UID_1 QUOTE   free=490   locked=510  （BID 挂单）
            //  [3] LOCKED   UID_2 BASE    free=5     locked=5    （ASK 挂单起始）
            //  [4] TRANSFER UID_2 QUOTE   free=490   locked=0    （taker 收 quote）
            //  [5] TRANSFER UID_2 BASE    free=5     locked=0    （taker 付 base）
            //  [6] UNLOCKED UID_1 QUOTE   free=495   locked=0    （maker 享 maker 费率，超额冻结 5 回退到 free）
            //  [7] TRANSFER UID_1 QUOTE   free=495   locked=0    （maker 付 quote；差价退 5）
            //  [8] TRANSFER UID_1 BASE    free=5     locked=0    （maker 收 base）
            LatencyTools.waitForCondition(1_000, () -> events.size() >= 9);
            assertThat("共 9 个 fund events", events.size(), is(9));

            // ──── DEPOSIT ────
            assertSnap(events.get(0), UID_1, DEPOSIT,  QUOTE_ID, 1_000L,   0L);
            assertSnap(events.get(1), UID_2, DEPOSIT,  BASE_ID,  10L,       0L);

            // ──── LOCKED（挂单冻结）────
            assertSnap(events.get(2), UID_1, LOCKED,   QUOTE_ID, 1_000L - bidLock, bidLock); // free=490, locked=510
            assertSnap(events.get(3), UID_2, LOCKED,   BASE_ID,  5L,        5L);             // ASK lock=5

            // ──── TRANSFER（成交转账）────
            // taker(UID_2)：收 490 USD（takerFee=2×5=10 扣了），付 5 BTC；BASE lock 归零
            assertSnap(events.get(4), UID_2, TRANSFER, QUOTE_ID, 490L,      0L);
            assertSnap(events.get(5), UID_2, TRANSFER, BASE_ID,  5L,        0L);
            // maker(UID_1)：fee 差额 5 USD 解冻回 free（按 taker 费率冻结、按 maker 费率结算）
            assertSnap(events.get(6), UID_1, UNLOCKED, QUOTE_ID, 495L,      0L);
            // maker(UID_1)：实付 505 USD（lockedForMatched=510, 差价退5），收 5 BTC；QUOTE lock 归零
            assertSnap(events.get(7), UID_1, TRANSFER, QUOTE_ID, 495L,      0L);
            assertSnap(events.get(8), UID_1, TRANSFER, BASE_ID,  5L,        0L);

            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 14b：现货成交事件 — BID taker（买方主动），验证 totalAdjustment > 0 触发的 UnLockEvent
    //
    // 场景：
    //   UID_1 挂 ASK price=100，size=5 （resting maker）
    //   UID_2 主动 BID reservePrice=110, price=110, size=5 （aggressive taker）
    //   takerFee=2 / makerFee=1，本金差 = (110-100) × 5 = 50，fee 差 = (2-2) × 5 = 0
    //   ⇒ totalAdjustment = 50 > 0，但 leftover = 0；旧条件不发 UNLOCKED，新条件发
    //
    // 总计 9 个事件（2 DEPOSIT + 1 LOCKED[ASK] + 1 LOCKED[BID] + 1 UNLOCKED[taker 本金差退回] + 4 TRANSFER）
    // ─────────────────────────────────────────────────────────────
    @Test
    public void testFundEventSpotFillTransfersBidTaker() throws Exception {
        List<FundEventSnap> events = Collections.synchronizedList(new ArrayList<>());
        IEventsHandler4Test localHandler = createCapturingHandler(events);
        SimpleEventsProcessor4Test processor = new SimpleEventsProcessor4Test(localHandler);

        try (ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.baseBuilder().build(), processor)) {
            container.initFutureSymbols();
            container.addSymbol(XBT_USD_SPOT);

            container.createUserWithSpecificMoney(UID_1, 10,    BASE_ID);
            container.createUserWithSpecificMoney(UID_2, 1_000, QUOTE_ID);

            long o1 = 160001L, o2 = 160002L;
            long bidLock = 5L * (110 + XBT_USD_SPOT.takerFee); // = 560

            // UID_1 挂卖（resting = maker, price=100）
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(o1).symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(100).reservePrice(100).size(5).build(),
                    CommandResultCode.SUCCESS);

            // UID_2 挂买 → 立即成交（aggressive = taker, hold=110）
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2).orderId(o2).symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(110).reservePrice(110).size(5).build(),
                    CommandResultCode.SUCCESS);

            container.getUserProfile(UID_1); // trigger R2 group change to flush TRANSFER events

            // 事件顺序：
            //  [0] DEPOSIT  UID_1 BASE    free=10    locked=0
            //  [1] DEPOSIT  UID_2 QUOTE   free=1000  locked=0
            //  [2] LOCKED   UID_1 BASE    free=5     locked=5    （ASK 挂单）
            //  [3] LOCKED   UID_2 QUOTE   free=440   locked=560  （BID 下单按 reservePrice=110 估高冻结）
            //  [4] UNLOCKED UID_2 QUOTE   free=490   locked=0    （taker 本金差 50 解冻回 free）
            //  [5] TRANSFER UID_2 QUOTE   free=490   locked=0    （taker 实付 510 USD）
            //  [6] TRANSFER UID_2 BASE    free=5     locked=0    （taker 收 5 BTC）
            //  [7] TRANSFER UID_1 QUOTE   free=495   locked=0    （maker 收 quote, makerFee=5 扣后）
            //  [8] TRANSFER UID_1 BASE    free=5     locked=0    （maker 付 5 BTC）
            LatencyTools.waitForCondition(1_000, () -> events.size() >= 9);
            assertThat("共 9 个 fund events", events.size(), is(9));

            assertSnap(events.get(0), UID_1, DEPOSIT,  BASE_ID,  10L,               0L);
            assertSnap(events.get(1), UID_2, DEPOSIT,  QUOTE_ID, 1_000L,            0L);
            assertSnap(events.get(2), UID_1, LOCKED,   BASE_ID,  5L,                5L);
            assertSnap(events.get(3), UID_2, LOCKED,   QUOTE_ID, 1_000L - bidLock,  bidLock); // free=440, locked=560
            assertSnap(events.get(4), UID_2, UNLOCKED, QUOTE_ID, 490L,              0L);
            assertSnap(events.get(5), UID_2, TRANSFER, QUOTE_ID, 490L,              0L);
            assertSnap(events.get(6), UID_2, TRANSFER, BASE_ID,  5L,                0L);
            assertSnap(events.get(7), UID_1, TRANSFER, QUOTE_ID, 495L,              0L);
            assertSnap(events.get(8), UID_1, TRANSFER, BASE_ID,  5L,                0L);

            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Test 15：存款/提现事件在有现货挂单冻结时字段准确
    //
    // 有 exchangeLocked=510 时：
    //   DEPOSIT(+500)  → free = (1000+500) - 510 = 990，locked = 510
    //   WITHDRAW(-300) → free = (1500-300) - 510 = 690，locked = 510
    // ─────────────────────────────────────────────────────────────
    @Test
    public void testFundEventDepositWithdrawReflectLock() throws Exception {
        List<FundEventSnap> events = Collections.synchronizedList(new ArrayList<>());
        IEventsHandler4Test localHandler = createCapturingHandler(events);
        SimpleEventsProcessor4Test processor = new SimpleEventsProcessor4Test(localHandler);

        try (ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT, processor)) {
            container.initFutureSymbols();
            container.addSymbol(XBT_USD_SPOT);
            container.createUserWithSpecificMoney(UID_1, 1_000, QUOTE_ID);

            long orderId = 150001L;
            long lock = 5L * (100 + XBT_USD_SPOT.takerFee); // = 510

            // 现货挂单 → exchangeLocked = 510
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(orderId)
                    .symbol(XBT_USD_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(100).reservePrice(100).size(5).build(),
                    CommandResultCode.SUCCESS);

            // 再存款 500（accounts=1500，lock 不变）
            container.submitCommandSync(
                    ApiAdjustUserBalance.builder().uid(UID_1).transactionId(150002L)
                            .amount(500).currency(QUOTE_ID).build(),
                    CommandResultCode.SUCCESS);

            // 提款 300（accounts=1200，lock 不变）
            container.submitCommandSync(
                    ApiAdjustUserBalance.builder().uid(UID_1).transactionId(150003L)
                            .amount(-300).currency(QUOTE_ID).build(),
                    CommandResultCode.SUCCESS);

            LatencyTools.waitForCondition(5_000, () -> events.size() >= 4);
            assertThat("共 4 个 fund events：DEPOSIT×2 + LOCKED + WITHDRAW", events.size(), is(4));

            // 初始存款（无挂单）：locked=0
            assertSnap(events.get(0), UID_1, DEPOSIT,  QUOTE_ID, 1_000L, 0L);
            // 挂单冻结
            assertSnap(events.get(1), UID_1, LOCKED,   QUOTE_ID, 490L,   lock);
            // 追加存款（有挂单）：free = 1500 - 510 = 990
            assertSnap(events.get(2), UID_1, DEPOSIT,  QUOTE_ID, 990L,   lock);
            // 提现（有挂单）：free = 1200 - 510 = 690
            assertSnap(events.get(3), UID_1, WITHDRAW, QUOTE_ID, 690L,   lock);

            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 辅助：捕获 fundEvent 前提取快照（避免对象池回收后字段被清零）
    // ─────────────────────────────────────────────────────────────

    private static IEventsHandler4Test createCapturingHandler(List<FundEventSnap> events) {
        return new IEventsHandler4Test() {
            @Override
            public void process(IFundEventsHandler.FundEventReport report) {
                IFundEventsHandler.FundEventReport.BalanceSnapshot b = report.getBalances();
                if (b != null) {
                    events.add(new FundEventSnap(report.getAccountId(), report.getEventType(),
                            b.getCurrency(), b.getFree(), b.getLocked()));
                }
            }
            @Override
            public void fundEventReport(IFundEventsHandler.FundEventReport r) {}
            @Override
            public void orderBook(ITradeEventsHandler.OrderBook ob) {}
            @Override
            public void spotExecutionReport(ITradeEventsHandler.SpotExecutionReport r) {}
            @Override
            public void futuresExecutionReport(ITradeEventsHandler.FuturesExecutionReport r) {}
        };
    }

    private static void assertSnap(FundEventSnap snap, long uid, FundEvent.FundEventType type,
                                   int currency, long free, long locked) {
        assertThat("uid",      snap.uid,      is(uid));
        assertThat("type",     snap.type,     is(type));
        assertThat("currency", snap.currency, is(currency));
        assertThat("free",     snap.free,     is(free));
        assertThat("locked",   snap.locked,   is(locked));
    }

    private static class FundEventSnap {
        final long uid;
        final FundEvent.FundEventType type;
        final int currency;
        final long free;
        final long locked;

        FundEventSnap(long uid, FundEvent.FundEventType type, int currency, long free, long locked) {
            this.uid = uid;
            this.type = type;
            this.currency = currency;
            this.free = free;
            this.locked = locked;
        }

        @Override
        public String toString() {
            return String.format("FundEventSnap{uid=%d, type=%s, currency=%d, free=%d, locked=%d}",
                    uid, type, currency, free, locked);
        }
    }
}
