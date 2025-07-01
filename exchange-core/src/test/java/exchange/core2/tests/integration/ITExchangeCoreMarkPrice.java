package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAdjustMarkPrice;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.Test;

import static exchange.core2.tests.util.TestConstants.UID_1;
import static exchange.core2.tests.util.TestConstants.UID_2;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public final class ITExchangeCoreMarkPrice {

    @Test
    public void testMarkPrice() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {

            CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                    .symbolId(10001)
                    .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                    .baseCurrency(11).quoteCurrency(12)
                    .makerFee(0).takerFee(0)
                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                    .maintenanceMarginScaleK(1000)
                    .maxLeverage(TreeSortedMap.newMapWith(1000L, 75L, 100000L, 40L))
                    .build();

            container.addSymbol(spec);
            container.createUserWithMoney(UID_1, spec.quoteCurrency, 6_800);
            container.createUserWithMoney(UID_2, spec.quoteCurrency, 50_000);
            // markPrice 650
            container.submitCommandSync(ApiAdjustMarkPrice.builder().transactionId(1001).symbol(10001).markPrice(650).build(), CommandResultCode.SUCCESS);

            // 挂单680
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10001L)
                    .price(680)
                    .size(10)
                    .symbol(spec.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10) // 不加杠杆他只能开10手，10倍杠杆才能开出100手
                    .build(), CommandResultCode.SUCCESS);

            // 未成单的时候，按照挂单价估算，名义价值 680 * 10
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(spec.symbolId).getPendingBuySize(), is(10L));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingBuyAvgPrice(), is(680L));
            });

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10002L)
                    .price(680)
                    .size(1)
                    .symbol(spec.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .build(), CommandResultCode.SUCCESS);


            // 有1单成交，openInitMarginSum 650 * 1 / 10 ；剩余pending仍按挂单价680 估算
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(spec.symbolId).getOpenInitMarginSum(), is(65L));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingBuySize(), is(9L));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingBuyAvgPrice(), is(680L));
            });

            // 这时markPrice更新到 670
            container.submitCommandSync(ApiAdjustMarkPrice.builder().transactionId(1001).symbol(10001).markPrice(670).build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10002L)
                    .price(680)
                    .size(9)
                    .symbol(spec.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .build(), CommandResultCode.SUCCESS);


            // 剩余9单成交，openInitMarginSum 65 + 670 * 9 / 10 = 668
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(spec.symbolId).getOpenInitMarginSum(), is(668L));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingBuySize(), is(0L));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingBuyAvgPrice(), is(0L));
            });

            // 这时用户1减仓1单
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10001L)
                    .price(680)
                    .size(1)
                    .symbol(spec.symbolId)
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
                    .symbol(spec.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .build(), CommandResultCode.SUCCESS);

            // 减仓1单，openInitMarginSum -= 668 * 1/10（-=66）
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(spec.symbolId).getOpenInitMarginSum(), is(602L));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingBuySize(), is(0L));
                assertThat(profile.getPositions().get(spec.symbolId).getPendingBuyAvgPrice(), is(0L));
            });
        }
    }
}
