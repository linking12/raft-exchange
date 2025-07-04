package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAdjustLeverage;
import exchange.core2.core.common.api.ApiAdjustMarkPrice;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static exchange.core2.tests.util.TestConstants.UID_1;
import static exchange.core2.tests.util.TestConstants.UID_2;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public final class ITExchangeCoreMarkPrice {

    private final CoreSymbolSpecification symbol = CoreSymbolSpecification.builder()
            .symbolId(10001)
            .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
            .baseCurrency(11).quoteCurrency(12)
            .makerFee(0).takerFee(0)
            .maintenanceMargin(TreeSortedMap.newMapWith(10_000L, 5L, 100_000L, 10L))
            .maintenanceMarginScaleK(1000)
            .maxLeverage(TreeSortedMap.newMapWith(10_000L, 75L, 100_000L, 40L))
            .build();

    @Test
    public void testInitMarginAndMaintenanceMargin() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.getExchangeCore().getLiquidationScanner().stop(1, TimeUnit.MINUTES);

            container.addSymbol(symbol);
            container.createUserWithMoney(UID_1, symbol.quoteCurrency, 6_800);
            container.createUserWithMoney(UID_2, symbol.quoteCurrency, 50_000);
            int txId = 1000;
            // markPrice 650
            container.submitCommandSync(ApiAdjustMarkPrice.builder().transactionId(txId++).symbol(symbol.symbolId).markPrice(650).build(), CommandResultCode.SUCCESS);

            // 挂单680
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10001L)
                    .price(680)
                    .size(10)
                    .symbol(symbol.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10) // 不加杠杆他只能开10手，10倍杠杆才能开出100手
                    .build(), CommandResultCode.SUCCESS);

            // 未成单的时候，按照挂单价估算，名义价值 680 * 10
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(symbol.symbolId).getPendingBuySize(), is(10L));
                assertThat(profile.getPositions().get(symbol.symbolId).getPendingBuyAvgPrice(), is(680L));
            });

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10002L)
                    .price(680)
                    .size(1)
                    .symbol(symbol.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .build(), CommandResultCode.SUCCESS);


            // 有1单成交，openInitMarginSum 650 * 1 / 10 ；剩余pending仍按挂单价680 估算
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(symbol.symbolId).getOpenInitMarginSum(), is(65L));
                assertThat(profile.getPositions().get(symbol.symbolId).getPendingBuySize(), is(9L));
                assertThat(profile.getPositions().get(symbol.symbolId).getPendingBuyAvgPrice(), is(680L));
            });

            // 这时markPrice更新到 670
            container.submitCommandSync(ApiAdjustMarkPrice.builder().transactionId(txId++).symbol(symbol.symbolId).markPrice(670).build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10002L)
                    .price(680)
                    .size(9)
                    .symbol(symbol.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .build(), CommandResultCode.SUCCESS);


            // 剩余9单成交，openInitMarginSum 65 + 670 * 9 / 10 = 668
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(symbol.symbolId).getOpenInitMarginSum(), is(668L));
                assertThat(profile.getPositions().get(symbol.symbolId).getPendingBuySize(), is(0L));
                assertThat(profile.getPositions().get(symbol.symbolId).getPendingBuyAvgPrice(), is(0L));
            });

            // 这时用户1减仓1单
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10001L)
                    .price(680)
                    .size(1)
                    .symbol(symbol.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10) // 不加杠杆他只能开10手，10倍杠杆才能开出100手
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10002L)
                    .price(680)
                    .size(1)
                    .symbol(symbol.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .build(), CommandResultCode.SUCCESS);

            // 减仓1单，openInitMarginSum -= 668 * 1/10（-=66）
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(symbol.symbolId).getOpenInitMarginSum(), is(602L));
                assertThat(profile.getPositions().get(symbol.symbolId).getPendingBuySize(), is(0L));
                assertThat(profile.getPositions().get(symbol.symbolId).getPendingBuyAvgPrice(), is(0L));
            });

            // 这时仓位 notional = 670 * 9 = 6030, 维持保证金率是0.5%，维持保证金 = 6030 * 0.5% = 30.15
            // 当 602 + pnl < 30.15 时强平，即 pnl = -572 时触发强平。
            // 用户的开仓成本 680 * 9 = 6120； 6120 - 572 = 5548，5548 / 9 = 616.44，即当 markPrice < 616.44 时触发强平。

            // markPrice 更新到 617，仓位不变
            container.submitCommandSync(ApiAdjustMarkPrice.builder().transactionId(txId++).symbol(symbol.symbolId).markPrice(617).build(), CommandResultCode.SUCCESS);
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(symbol.symbolId).getOpenVolume(), is(9L));
            });

            // markPrice 更新到 616，会触发强平，减1手仓位
            // 注意 bidPrice也要更新
            container.updateCurrentPriceTo(616, symbol.symbolId, symbol.quoteCurrency);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10002L)
                    .price(616)
                    .size(10)
                    .symbol(symbol.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .build(), CommandResultCode.SUCCESS);
            container.getUserProfile(UID_1); // 触发R2做完，再触发强平检查
            container.getExchangeCore().getLiquidationScanner().triggerOnce();
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(symbol.symbolId).getOpenVolume(), is(8L));
            });
        }
    }

    @Test
    public void testTieredMaintenanceMargin() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.getExchangeCore().getLiquidationScanner().stop(1, TimeUnit.MINUTES);

            container.addSymbol(symbol);
            container.createUserWithMoney(UID_1, symbol.quoteCurrency, 500_000);
            container.createUserWithMoney(UID_2, symbol.quoteCurrency, 500_000);
            int txId = 1000;
            // markPrice 650
            container.submitCommandSync(ApiAdjustMarkPrice.builder().transactionId(txId++).symbol(symbol.symbolId).markPrice(650).build(), CommandResultCode.SUCCESS);

            // 挂单680，开68w
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10001L)
                    .price(680)
                    .size(1000)
                    .symbol(symbol.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10002L)
                    .price(680)
                    .size(1000)
                    .symbol(symbol.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            // 全部成交，openInitMarginSum 650 * 1000 / 10 = 65000
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(symbol.symbolId).getOpenInitMarginSum(), is(65000L));
                assertThat(profile.getPositions().get(symbol.symbolId).getPendingBuySize(), is(0L));
                assertThat(profile.getPositions().get(symbol.symbolId).getPendingBuyAvgPrice(), is(0L));
            });


            // 这时仓位 notional = 650 * 1000 = 65w, 维持保证金率是1%，维持保证金 = 6500
            // 当 650*1000/10 + pnl < 6500 时强平，即 pnl = -58500 时触发强平。
            // 用户的开仓成本 680 * 1000 = 68w； 68w - 5.85w = 62.15w，62.15w / 1k = 621.5，即当 markPrice <621.5 时触发强平。

            // markPrice 更新到 622，仓位不变
            container.submitCommandSync(ApiAdjustMarkPrice.builder().transactionId(txId++).symbol(symbol.symbolId).markPrice(622).build(), CommandResultCode.SUCCESS);
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(symbol.symbolId).getOpenVolume(), is(1000L));
            });

            // markPrice 更新到 621，会触发强平，减1手仓位
            // 注意 bidPrice也要更新
            container.updateCurrentPriceTo(621, symbol.symbolId, symbol.quoteCurrency);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10002L)
                    .price(621)
                    .size(1000)
                    .symbol(symbol.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);
            container.getUserProfile(UID_1); // 触发R2做完，再触发强平检查
            container.getExchangeCore().getLiquidationScanner().triggerOnce();
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(symbol.symbolId).getOpenVolume(), is(999L));
            });
        }
    }


    @Test
    public void testTieredLeverage() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.getExchangeCore().getLiquidationScanner().stop(1, TimeUnit.MINUTES);

            container.addSymbol(symbol);
            container.createUserWithMoney(UID_1, symbol.quoteCurrency, 500_000);
            container.createUserWithMoney(UID_2, symbol.quoteCurrency, 500_000);
            int txId = 1000;
            int orderId = 10000;
            // markPrice 1000
            container.submitCommandSync(ApiAdjustMarkPrice.builder().transactionId(txId++).symbol(symbol.symbolId).markPrice(1000).build(), CommandResultCode.SUCCESS);

            // 挂单1000，开10w，最大75x
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(orderId++)
                    .price(1000)
                    .size(100)
                    .symbol(symbol.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(75)
                    .build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(orderId++)
                    .price(1000)
                    .size(100)
                    .symbol(symbol.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            // 再挂1单，超过10w，只能开40x，开75x会失败
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(orderId++)
                    .price(1000)
                    .size(1)
                    .symbol(symbol.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(75)
                    .build(), CommandResultCode.RISK_INVALID_LEVERAGE);

            // 调整杠杆到40x，开1手
            container.submitCommandSync(ApiAdjustLeverage.builder().uid(UID_1).symbol(symbol.symbolId).leverage(40).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(orderId++)
                    .price(1000)
                    .size(1)
                    .symbol(symbol.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(40)
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(orderId++)
                    .price(1000)
                    .size(1)
                    .symbol(symbol.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            // 全部成交，openInitMarginSum 1000 * 100 / 75 + 1000 * 1 / 40 = 1358.33
            // 保证金比率 1000 * 101 * 1% / 1358 = 0.07437， scaleK = 743
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(symbol.symbolId).getOpenInitMarginSum(), is(1358L));
                assertThat(profile.getPositions().get(symbol.symbolId).getMarginRatioScaleK(), is(743L));
                assertThat(profile.getPositions().get(symbol.symbolId).getPendingBuySize(), is(0L));
                assertThat(profile.getPositions().get(symbol.symbolId).getPendingBuyAvgPrice(), is(0L));
            });

        }
    }
}
