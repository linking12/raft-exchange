package exchange.core2.tests.integration;

import exchange.core2.core.common.*;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.utils.CoreArithmeticUtils;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.hamcrest.core.Is;
import org.junit.Test;
import org.junit.jupiter.api.Assertions;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static exchange.core2.core.common.OrderType.GTC;
import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertTrue;

public class ITExchangeCoreHedgeMode {
    private final CoreSymbolSpecification symbol = CoreSymbolSpecification.builder()
            .symbolId(10001)
            .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
            .baseCurrency(11).quoteCurrency(12)
            .baseScaleK(1).quoteScaleK(1)
            .makerFee(0).takerFee(0)
            .maintenanceMargin(TreeSortedMap.newMapWith(10_000L, 5L, 100_000L, 10L))
            .maintenanceMarginScaleK(1000)
            .maxLeverage(TreeSortedMap.newMapWith(10_000L, 75L, 100_000L, 40L))
            .build();

    private final int BNB_ID = 11;
    private final int USDT_ID = 12;

    private final CoreCurrencySpecification BNB = CoreCurrencySpecification.builder()
            .id(BNB_ID).name("BNB").digit(8).build();

    private final CoreCurrencySpecification USDT = CoreCurrencySpecification.builder()
            .id(USDT_ID).name("USDT").digit(6).build();

    private final CoreSymbolSpecification BNB_USDT = CoreSymbolSpecification.builder()
            .symbolId(10001)
            .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
            .baseCurrency(BNB_ID).baseScaleK(TenPowers.pow10(3))
            .quoteCurrency(USDT_ID).quoteScaleK(TenPowers.pow10(5))
            .makerFee(0).takerFee(0)
            .maintenanceMargin(TreeSortedMap.newMapWith(10_000 * TenPowers.pow10(3 + 5), 5L))
            .maintenanceMarginScaleK(1000)
            .maxLeverage(TreeSortedMap.newMapWith(10_000 * TenPowers.pow10(3 + 5), 75L))
            .build();

    private final CoreSymbolSpecification BNB_USDT_DELIVERY = CoreSymbolSpecification.builder()
            .symbolId(30001)
            .type(SymbolType.FUTURES_CONTRACT_DELIVERY)
            .baseCurrency(BNB_ID).baseScaleK(TenPowers.pow10(3))
            .quoteCurrency(USDT_ID).quoteScaleK(TenPowers.pow10(5))
            .makerFee(0).takerFee(0)
            .maintenanceMargin(TreeSortedMap.newMapWith(10_000 * TenPowers.pow10(3 + 5), 5L))
            .maintenanceMarginScaleK(1000)
            .maxLeverage(TreeSortedMap.newMapWith(10_000 * TenPowers.pow10(3 + 5), 75L))
            .build();

    private void initUsersAndSymbol(ExchangeTestContainer container) {
        container.addCurrency(BNB);
        container.addCurrency(USDT);
        container.addSymbol(BNB_USDT);
        // markPrice 750
        long markPrice = 750 * BNB_USDT.getQuoteScaleK();
        container.initMarkPrice(BNB_USDT.symbolId, markPrice);

        container.createUserWithMoney(UID_1, USDT_ID, 10000 * USDT.getCurrencyScaleK());
        container.createUserWithMoney(UID_2, USDT_ID, 10000 * USDT.getCurrencyScaleK());
        container.createUserWithMoney(UID_3, USDT_ID, 10000 * USDT.getCurrencyScaleK());
        container.createUserWithMoney(UID_4, USDT_ID, 10000 * USDT.getCurrencyScaleK());
        container.createUserWithMoney(UID_5, USDT_ID, 10000 * USDT.getCurrencyScaleK());
    }

    // 测试1: 默认单项持仓 - one-way, 默认只有1个仓位, 反向开单只会抵消之前持仓, 不会新开仓位
    @Test
    public void testDefaultSingleDirection() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            initUsersAndSymbol(container);

            // 默认单向模式，无法同时开多空
            // 挂多单 1@750 10x
            long size = 2 * BNB_USDT.getBaseScaleK();
            long price = 750 * BNB_USDT.getQuoteScaleK();
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10001L)
                    .price(price)
                    .size(size)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10002L)
                    .price(price)
                    .size(size)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), Is.is(1));
            });

            // 尝试开反向仓位
            // 挂多单 1@750 10x
            long size2 = 1 * BNB_USDT.getBaseScaleK();
            long price2 = 780 * BNB_USDT.getQuoteScaleK();
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10003L)
                    .price(price2)
                    .size(size2)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), Is.is(1)); // 只有一个仓位
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
            });

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_3)
                    .orderId(10004L)
                    .price(price2)
                    .size(size2)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), Is.is(1)); // 只有一个仓位
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
            });
        }
    }

    // 测试2: 可以通过命令改持仓类型（从单向改双向）
    @Test
    public void testChangePositionMode() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            initUsersAndSymbol(container);

            // 切换到双向模式
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(UID_1)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            // 挂多单 1@750 10x
            long size = 2 * BNB_USDT.getBaseScaleK();
            long price = 750 * BNB_USDT.getQuoteScaleK();
            // 现在可以开多空仓位
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10001L)
                    .price(price)
                    .size(size)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            long size2 = 10 * BNB_USDT.getBaseScaleK();
            long price2 = 800 * BNB_USDT.getQuoteScaleK();

            // check双向持仓时margin类型需要一致
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10002L)
                    .price(price2)
                    .size(size2)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.CROSS)
                    .leverage(10)
                    .build(), CommandResultCode.RISK_MARGIN_MODE_MISMATCH);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10003L)
                    .price(price2)
                    .size(size2)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), Is.is(2)); // 两个仓位：LONG和SHORT
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG)); // 两个仓位：LONG和SHORT
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).pendingBuySize, Is.is(size)); // 两个仓位：LONG和SHORT
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).direction, Is.is(PositionDirection.SHORT)); // 两个仓位：LONG和SHORT
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).pendingSellSize, Is.is(size2)); // 两个仓位：LONG和SHORT
            });

            // check双向持仓时能开仓成功, 且pendingBuySize和pendingSellSize都减少
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_3)
                    .orderId(10004L)
                    .price(price)
                    .size(size / 2)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_3)
                    .orderId(10005L)
                    .price(price2)
                    .size(size2)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), Is.is(2)); // 两个仓位：LONG和SHORT
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG)); // 两个仓位：LONG和SHORT
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).pendingBuySize, Is.is(size / 2)); // 两个仓位：LONG和SHORT
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).direction, Is.is(PositionDirection.SHORT)); // 两个仓位：LONG和SHORT
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).pendingSellSize, Is.is(0L)); // 两个仓位：LONG和SHORT
            });
        }
    }

    // 测试3: 有仓位时不允许改持仓类型
    @Test
    public void testCannotChangeModeWithPosition() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            initUsersAndSymbol(container);

            // 挂多单 1@750 10x
            long size = 2 * BNB_USDT.getBaseScaleK();
            long price = 750 * BNB_USDT.getQuoteScaleK();
            // 开仓
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10001L)
                    .price(price)
                    .size(size)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.CROSS)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10002L)
                    .price(75000000L)
                    .size(100L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.CROSS)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            // 尝试切换模式，应该失败
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(UID_1)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.RISK_MARGIN_POSITION_EXISTS);

            container.validateUserState(UID_1, report -> {
                assertThat(report.getPositions().get(BNB_USDT.symbolId).size(), Is.is(1)); // 仍为单向
            });
        }
    }

    // 双向持仓正向测试用例, 允许用户同时开多/开空, 且会存储到不同的postion上
    @Test
    public void testMarginHedgeMode() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addSymbol(symbol);
            container.addCurrency(symbol.baseCurrency, 0);
            container.addCurrency(symbol.quoteCurrency, 0);
            container.createUserWithMoney(UID_1, symbol.quoteCurrency, 10000);
            container.createUserWithMoney(UID_2, symbol.quoteCurrency, 10000);

            container.adjustPositionMode(UID_1, PositionMode.HEDGE);

            int txId = 1000;
            container.submitCommandSync(ApiAdjustMarkPrice.builder().transactionId(txId++).symbol(symbol.symbolId).markPrice(650).build(), CommandResultCode.SUCCESS);

            ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(652)
                    .size(1)
                    .action(OrderAction.BID)
                    .orderType(GTC)
                    .symbol(symbol.symbolId)
                    .marginMode(MarginMode.ISOLATED)
                    .build();
            container.submitCommandSync(order101, CommandResultCode.SUCCESS);

            ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102L)
                    .price(652)
                    .size(1)
                    .action(OrderAction.ASK)
                    .orderType(GTC)
                    .symbol(symbol.symbolId)
                    .marginMode(MarginMode.ISOLATED)
                    .build();
            container.submitCommandSync(order102, CommandResultCode.SUCCESS);

            ApiPlaceOrder order103 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(103L)
                    .price(655)
                    .size(1)
                    .action(OrderAction.ASK)
                    .orderType(GTC)
                    .symbol(symbol.symbolId)
                    .marginMode(MarginMode.ISOLATED)
                    .build();
            container.submitCommandSync(order103, CommandResultCode.SUCCESS);

            ApiPlaceOrder order104 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(104L)
                    .price(655)
                    .size(1)
                    .action(OrderAction.BID)
                    .orderType(GTC)
                    .symbol(symbol.symbolId)
                    .marginMode(MarginMode.ISOLATED)
                    .build();
            container.submitCommandSync(order104, CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(symbol.symbolId).get(0).openVolume, is(1L));
                assertThat(profile.getPositions().get(symbol.symbolId).get(0).direction, is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(symbol.symbolId).get(1).openVolume, is(1L));
                assertThat(profile.getPositions().get(symbol.symbolId).get(1).direction, is(PositionDirection.SHORT));
            });
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getPositions().isEmpty(), is(true));
            });
        }
    }

    // 测试4: 双向持仓position信息要正确
    // 1. 因为多空存储在不同仓位, 所以允许不同杠杆
    // 2. 多空marginMode不能不同, 要么都是全仓, 要么都是逐仓
    @Test
    public void testDualPositionSign() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            initUsersAndSymbol(container);

            // 切换到双向
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(UID_1)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            // 开多
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10001L)
                    .price(75000000L)
                    .size(100L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            // 双向持仓中的单向仓位不允许leverage不同
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10002L)
                    .price(75000000L)
                    .size(100L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(11)
                    .build(), CommandResultCode.RISK_LEVERAGE_MISMATCH);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10003L)
                    .price(75000000L)
                    .size(100L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .build(), CommandResultCode.SUCCESS);

            // 开空失败, 双向持仓不允许开不同类型margin
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10004L)
                    .price(75000000L)
                    .size(50L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.CROSS)
                    .leverage(11)
                    .build(), CommandResultCode.RISK_MARGIN_MODE_MISMATCH);

            // 开空失败, 双向持仓杠杆倍数必须一致
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10005L)
                    .price(75000000L)
                    .size(50L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(11)
                    .build(), CommandResultCode.RISK_LEVERAGE_MISMATCH);

            // 开空成功
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10006L)
                    .price(75000000L)
                    .size(50L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10007L)
                    .price(75000000L)
                    .size(50L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), Is.is(2));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(100L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(50L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).direction, Is.is(PositionDirection.SHORT));
            });
        }
    }

    // 测试5: 测试关仓
    @Test
    public void testClosePosition() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            initUsersAndSymbol(container);

            // 切换到双向
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(UID_1)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            initHedgeOrders(container);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(100L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(50L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).direction, Is.is(PositionDirection.SHORT));
            });

            // 关多仓
            ApiClosePosition closeLong = ApiClosePosition.builder()
                    .orderId(10005L)
                    .uid(UID_1)
                    .symbol(BNB_USDT.symbolId)
                    .price(81000000L)
                    .size(30L)
                    .action(OrderAction.ASK)
                    .build();

            container.submitCommandSync(closeLong, CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_4)
                    .orderId(10006L)
                    .price(81000000L)
                    .size(30L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), Is.is(2));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(70L)); // LONG
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(50L)); // SHORT
            });

            // 关空仓
            ApiClosePosition closeShort = ApiClosePosition.builder()
                    .orderId(10007L)
                    .uid(UID_1)
                    .symbol(BNB_USDT.symbolId)
                    .price(74000000L)
                    .size(30L)
                    .action(OrderAction.BID)
                    .build();

            container.submitCommandSync(closeShort, CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_5)
                    .orderId(10008L)
                    .price(74000000L)
                    .size(30L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), Is.is(2));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(70L)); // LONG
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(20L)); // SHORT
            });
        }
    }

    // 测试5.2: 双向持仓之间互相match
    @Test
    public void testHedgeModeMatch() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            initUsersAndSymbol(container);

            // 切换到双向
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(UID_1)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10001L)
                    .price(75000000L)
                    .size(100L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10003L)
                    .price(80000000L)
                    .size(50L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(0L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(0L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).direction, Is.is(PositionDirection.SHORT));
            });

            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(UID_5)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            // 关多仓
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_5)
                    .orderId(10003L)
                    .price(75000000L)
                    .size(50L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_5)
                    .orderId(10004L)
                    .price(80000000L)
                    .size(50L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), Is.is(2));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(50L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(50L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).direction, Is.is(PositionDirection.SHORT));
            });
            container.validateUserState(UID_5, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), Is.is(2));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(50L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(50L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).direction, Is.is(PositionDirection.SHORT));
            });
        }
    }

    // 测试6: 混合多种持仓，发送资金费率命令，检查多空持仓加减profit正确
    @Test
    public void testMixedFundingRate() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            initUsersAndSymbol(container);

            // 切换到双向
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(UID_1)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            initHedgeOrders(container);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(100L)); // LONG
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(50L)); // SHORT
            });

            // 假设发送资金费率命令（funding rate >0，多头付空头）
            container.submitCommandSync(ApiSettleFundingFees.builder() // 假设有这个API
                    .transactionId(container.getRandomTransactionId())
                    .symbol(BNB_USDT.symbolId)
                    .fundingRate(1L)
                    .rateScaleK(100)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                // 多头profit减少，空头profit增加
                assertTrue(profile.getPositions().get(BNB_USDT.symbolId).get(0).profit < 0); // LONG profit -
                assertTrue(profile.getPositions().get(BNB_USDT.symbolId).get(1).profit > 0); // SHORT profit +
            });
        }
    }

    // 测试7: 发起settlePnl, 交割后持仓正确
    @Test
    public void testSettlePnl() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT_DELIVERY);
            // markPrice 750
            long markPrice = 750 * BNB_USDT_DELIVERY.getQuoteScaleK();
            container.initMarkPrice(BNB_USDT_DELIVERY.symbolId, markPrice);

            long deposit = 10000 * USDT.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, deposit);
            container.createUserWithMoney(UID_2, USDT_ID, deposit);
            container.createUserWithMoney(UID_3, USDT_ID, deposit);

            // 切换到双向
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(UID_1)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            // 开多100
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10001L)
                    .price(75000000L)
                    .size(100L)
                    .symbol(BNB_USDT_DELIVERY.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10002L)
                    .price(75000000L)
                    .size(100L)
                    .symbol(BNB_USDT_DELIVERY.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT_DELIVERY.symbolId).get(0).openVolume, Is.is(100L));
            });

            // 开空50
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10003L)
                    .price(80000000L)
                    .size(50L)
                    .symbol(BNB_USDT_DELIVERY.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_3)
                    .orderId(10004L)
                    .price(80000000L)
                    .size(50L)
                    .symbol(BNB_USDT_DELIVERY.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            // 模拟markPrice涨到800
            long markPrice2 = 800 * BNB_USDT_DELIVERY.getQuoteScaleK();
            container.initMarkPrice(BNB_USDT_DELIVERY.symbolId, markPrice2);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(USDT_ID), Is.is(deposit));
                assertThat(profile.getPositions().get(BNB_USDT_DELIVERY.symbolId).size(), Is.is(2));
            });

            // 结算pnl
            container.submitCommandSync(ApiSettlePNL.builder()
                    .symbol(BNB_USDT_DELIVERY.symbolId)
                    .settlePrice(markPrice)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().size(), Is.is(0));
                // balance增加pnl
                assertThat(profile.getAccounts().get(USDT_ID), Is.is(10002500000L));
            });
        }
    }

    // 测试8: 有双向持仓时打快照，通过快照恢复双向持仓仓位依然正确
    // 在exchange.core2.core.snapshot.PersistenceTests已经覆盖

    // 测试9: total balance正确（包括pnl）
    @Test
    public void testTotalBalance() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            initUsersAndSymbol(container);

            // 切换到双向
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(UID_1)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            initHedgeOrders(container);

            TotalCurrencyBalanceReportResult totalBal = container.totalBalanceReport();
            Assertions.assertTrue(totalBal.isGlobalBalancesAllZero());
        }
    }

    // 测试10: 双向持仓时增加保证金能正确加到相应仓位上
    @Test
    public void testAddExtraMarginToDualPosition() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            initUsersAndSymbol(container);

            // 切换到双向
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(UID_1)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            initHedgeOrders(container);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(USDT_ID), Is.is(10000 * USDT.getCurrencyScaleK()));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(100L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).extraMargin, Is.is(0L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(50L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).direction, Is.is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).extraMargin, Is.is(0L));
            });

            // 加到LONG
            long depositLong = 100 * USDT.getCurrencyScaleK();
            long scaledLong = CoreArithmeticUtils.currencyToSymbolScale(depositLong, BNB_USDT, USDT);
            container.submitCommandSync(ApiAdjustMargin.builder()
                    .transactionId(container.getRandomTransactionId())
                    .uid(UID_1)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .amount(depositLong)
                    .marginMode(MarginMode.ISOLATED)
                    .build(), CommandResultCode.SUCCESS);

            // 加到SHORT
            long depositShort = 200 * USDT.getCurrencyScaleK();
            long scaledShort = CoreArithmeticUtils.currencyToSymbolScale(depositShort, BNB_USDT, USDT);
            container.submitCommandSync(ApiAdjustMargin.builder()
                    .transactionId(container.getRandomTransactionId())
                    .uid(UID_1)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .amount(depositShort)
                    .marginMode(MarginMode.ISOLATED)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).extraMargin, Is.is(scaledLong)); // LONG
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).extraMargin, Is.is(scaledShort)); // SHORT
                // 一开始充值的1000, Isolated补充保证金相当于从balance划转到期货账户的extraMargin下
                assertThat(profile.getAccounts().get(USDT_ID), Is.is(10000 * USDT.getCurrencyScaleK() - depositLong - depositShort));
            });

            // 如果cross margin, 直接加到balance上
            long depositCross = 500 * USDT.getCurrencyScaleK();
            container.submitCommandSync(ApiAdjustMargin.builder()
                    .transactionId(container.getRandomTransactionId())
                    .uid(UID_1)
                    .currency(USDT_ID)
                    .action(OrderAction.BID)
                    .amount(depositCross)
                    .marginMode(MarginMode.CROSS)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).extraMargin, Is.is(scaledLong)); // LONG
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).extraMargin, Is.is(scaledShort)); // SHORT
                assertThat(profile.getAccounts().get(USDT_ID), Is.is(10000 * USDT.getCurrencyScaleK() - depositLong - depositShort + depositCross));
            });
        }
    }

    private void initHedgeOrders(ExchangeTestContainer container) throws ExecutionException, InterruptedException {
        // 开多100
        container.submitCommandSync(ApiPlaceOrder.builder()
                .uid(UID_1)
                .orderId(10001L)
                .price(75000000L)
                .size(100L)
                .symbol(BNB_USDT.symbolId)
                .action(OrderAction.BID)
                .orderType(OrderType.GTC)
                .marginMode(MarginMode.ISOLATED)
                .leverage(10)
                .build(), CommandResultCode.SUCCESS);

        container.submitCommandSync(ApiPlaceOrder.builder()
                .uid(UID_2)
                .orderId(10002L)
                .price(75000000L)
                .size(100L)
                .symbol(BNB_USDT.symbolId)
                .action(OrderAction.ASK)
                .orderType(OrderType.GTC)
                .marginMode(MarginMode.ISOLATED)
                .leverage(10)
                .build(), CommandResultCode.SUCCESS);

        container.validateUserState(UID_1, report -> {
            assertThat(report.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(100L));
        });

        // 开空50
        container.submitCommandSync(ApiPlaceOrder.builder()
                .uid(UID_1)
                .orderId(10003L)
                .price(80000000L)
                .size(50L)
                .symbol(BNB_USDT.symbolId)
                .action(OrderAction.ASK)
                .orderType(OrderType.GTC)
                .marginMode(MarginMode.ISOLATED)
                .leverage(10)
                .build(), CommandResultCode.SUCCESS);

        container.submitCommandSync(ApiPlaceOrder.builder()
                .uid(UID_3)
                .orderId(10004L)
                .price(80000000L)
                .size(50L)
                .symbol(BNB_USDT.symbolId)
                .action(OrderAction.BID)
                .orderType(OrderType.GTC)
                .marginMode(MarginMode.ISOLATED)
                .leverage(10)
                .build(), CommandResultCode.SUCCESS);
    }

    // 测试11: 调整杠杆，两个仓位是否都能调
    @Test
    public void testAdjustLeverageDual() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            initUsersAndSymbol(container);

            // 切换到双向
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(UID_1)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            initHedgeOrders(container);

            // 调整杠杆（假设调整应用到所有方向）
            container.submitCommandSync(ApiAdjustLeverage.builder()
                    .uid(UID_1)
                    .symbol(BNB_USDT.symbolId)
                    .leverage(20)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, report -> {
                assertThat(report.getPositions().get(BNB_USDT.symbolId).get(0).getLeverage(), Is.is(20)); // LONG
                assertThat(report.getPositions().get(BNB_USDT.symbolId).get(1).getLeverage(), Is.is(20)); // SHORT
            });
        }
    }

    // 测试13: 强平, 全仓时双向持仓的强平
    @Test
    public void testLiquidationLoop() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.getExchangeCore().liquidationScanner.stop(1, TimeUnit.MINUTES);

            initUsersAndSymbol(container);

            // 切换到双向
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(UID_1)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            initHedgeOrders(container);

            // 更新价格触发强平
            container.submitCommandSync(ApiAdjustMarkPrice.builder()
                    .transactionId(1L)
                    .symbol(BNB_USDT.symbolId)
                    .markPrice(95000000L) // 假设触发LONG强平
                    .build(), CommandResultCode.SUCCESS);

            // UID_4挂单准备吃掉强平单
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_4)
                    .orderId(20001L)
                    .price(80000000L)
                    .size(150L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                // 假设LONG被强平，SHORT保留
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(100L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(50L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).direction, Is.is(PositionDirection.SHORT));
            });

            container.validateUserState(UID_4, report -> {
                // 假设LONG被强平，SHORT保留
                assertThat(report.getPositions().get(BNB_USDT.symbolId).size(), is(1));
                assertThat(report.getPositions().get(BNB_USDT.symbolId).get(0).direction, is(PositionDirection.SHORT));
                assertThat(report.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, is(0L));
            });

            container.getExchangeCore().getLiquidationScanner().triggerOnce();

            container.validateUserState(UID_4, report -> {
                // UID_4吃掉UID_2和UID_1开出来的空单
                assertThat(report.getPositions().get(BNB_USDT.symbolId).size(), is(1));
                assertThat(report.getPositions().get(BNB_USDT.symbolId).get(0).direction, is(PositionDirection.SHORT));
                assertThat(report.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, is(150L));
            });

            container.validateUserState(UID_1, profile -> {
                // 假设LONG被强平，SHORT保留
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), is(1));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, is(PositionDirection.LONG));
            });
        }
    }

    // 测试12: 强平双向持仓开仓时强平
    @Test
    public void testLiquidationLoop2() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.getExchangeCore().liquidationScanner.stop(1, TimeUnit.MINUTES);

            initUsersAndSymbol(container);

            // UID_1, UID2切换到双向
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(UID_1)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(UID_2)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            // 开多100
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10001L)
                    .price(75000000L)
                    .size(100L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10002L)
                    .price(75000000L)
                    .size(100L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            // 开空50
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10003L)
                    .price(80000000L)
                    .size(50L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10004L)
                    .price(80000000L)
                    .size(50L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), Is.is(2));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(100L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(50L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).direction, Is.is(PositionDirection.SHORT));
            });

            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), Is.is(2));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(50L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(100L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).direction, Is.is(PositionDirection.SHORT));
            });

            // 更新价格触发强平
            container.submitCommandSync(ApiAdjustMarkPrice.builder()
                    .transactionId(1L)
                    .symbol(BNB_USDT.symbolId)
                    .markPrice(95000000L) // 假设触发LONG强平
                    .build(), CommandResultCode.SUCCESS);

            // UID_4挂单准备吃掉强平单
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_4)
                    .orderId(20001L)
                    .price(80000000L)
                    .size(180L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(100L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(50L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).direction, Is.is(PositionDirection.SHORT));
            });

            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(50L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(100L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).direction, Is.is(PositionDirection.SHORT));
            });

            container.validateUserState(UID_4, report -> {
                // 假设LONG被强平，SHORT保留
                assertThat(report.getPositions().get(BNB_USDT.symbolId).size(), is(1));
                assertThat(report.getPositions().get(BNB_USDT.symbolId).get(0).direction, is(PositionDirection.SHORT));
                assertThat(report.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, is(0L));
            });

            container.getExchangeCore().getLiquidationScanner().triggerOnce();

            container.validateUserState(UID_4, report -> {
                // UID_4吃掉UID_2和UID_1开出来的空单
                assertThat(report.getPositions().get(BNB_USDT.symbolId).size(), is(1));
                assertThat(report.getPositions().get(BNB_USDT.symbolId).get(0).direction, is(PositionDirection.SHORT));
                assertThat(report.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, is(150L));
            });

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), is(1));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, is(PositionDirection.LONG));
            });

            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), is(1));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, is(PositionDirection.LONG));
            });
        }
    }

    // 测试12: 强平看下loop是否符合预期
    @Test
    public void testLiquidationLoop3() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.getExchangeCore().liquidationScanner.stop(1, TimeUnit.MINUTES);

            initUsersAndSymbol(container);

            // UID_1, UID2切换到双向
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(UID_1)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(ApiAdjustPositionMode.builder()
                    .uid(UID_2)
                    .positionMode(PositionMode.HEDGE)
                    .build(), CommandResultCode.SUCCESS);

            // 开多100
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10001L)
                    .price(75000000L)
                    .size(100L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10002L)
                    .price(75000000L)
                    .size(100L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            // 开空50
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10003L)
                    .price(80000000L)
                    .size(50L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10004L)
                    .price(80000000L)
                    .size(50L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), Is.is(2));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(100L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(50L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).direction, Is.is(PositionDirection.SHORT));
            });

            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), Is.is(2));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(50L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(100L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).direction, Is.is(PositionDirection.SHORT));
            });

            // 更新价格触发强平
            container.submitCommandSync(ApiAdjustMarkPrice.builder()
                    .transactionId(1L)
                    .symbol(BNB_USDT.symbolId)
                    .markPrice(95000000L) // 假设触发LONG强平
                    .build(), CommandResultCode.SUCCESS);

            // UID_4挂单准备吃掉强平单
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_4)
                    .orderId(20001L)
                    .price(80000000L)
                    .size(180L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(100L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(50L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).direction, Is.is(PositionDirection.SHORT));
            });

            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, Is.is(50L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, Is.is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).openVolume, Is.is(100L));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(1).direction, Is.is(PositionDirection.SHORT));
            });

            container.validateUserState(UID_4, report -> {
                // 假设LONG被强平，SHORT保留
                assertThat(report.getPositions().get(BNB_USDT.symbolId).size(), is(1));
                assertThat(report.getPositions().get(BNB_USDT.symbolId).get(0).direction, is(PositionDirection.SHORT));
                assertThat(report.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, is(0L));
            });

            container.getExchangeCore().getLiquidationScanner().triggerOnce();

            container.validateUserState(UID_4, report -> {
                // UID_4吃掉UID_2和UID_1开出来的空单
                assertThat(report.getPositions().get(BNB_USDT.symbolId).size(), is(1));
                assertThat(report.getPositions().get(BNB_USDT.symbolId).get(0).direction, is(PositionDirection.SHORT));
                assertThat(report.getPositions().get(BNB_USDT.symbolId).get(0).openVolume, is(150L));
            });

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), is(1));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, is(PositionDirection.LONG));
            });

            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).size(), is(1));
                assertThat(profile.getPositions().get(BNB_USDT.symbolId).get(0).direction, is(PositionDirection.LONG));
            });
        }
    }
}
