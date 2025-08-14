package exchange.core2.tests.integration;

import exchange.core2.core.common.*;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.hamcrest.core.Is;
import org.junit.Test;

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
}
