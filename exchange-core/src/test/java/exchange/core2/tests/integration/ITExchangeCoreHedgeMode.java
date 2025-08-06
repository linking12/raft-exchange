package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.PositionMode;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAdjustMarkPrice;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.Test;

import static exchange.core2.core.common.OrderType.GTC;
import static exchange.core2.tests.util.TestConstants.UID_1;
import static exchange.core2.tests.util.TestConstants.UID_2;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

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
                assertThat(profile.getPositions().get(symbol.symbolId).openVolume, is(1L));
                assertThat(profile.getPositions().get(-symbol.symbolId).openVolume, is(1L));
            });
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getPositions().isEmpty(), is(true));
            });
        }
    }
}
