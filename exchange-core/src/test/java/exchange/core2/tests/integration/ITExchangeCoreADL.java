package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.Test;

import static exchange.core2.tests.util.TestConstants.MAX_VALUE;
import static exchange.core2.tests.util.TestConstants.UID_1;
import static exchange.core2.tests.util.TestConstants.UID_2;
import static exchange.core2.tests.util.TestConstants.UID_3;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public final class ITExchangeCoreADL {

    private final CoreSymbolSpecification symbol = CoreSymbolSpecification.builder()
            .symbolId(10001)
            .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
            .baseCurrency(11).quoteCurrency(12)
            .baseScaleK(1).quoteScaleK(1)
            .takerFee(2).feeScaleK(1000)
            .initMargin(1)
            .initMarginScaleK(100)
            .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100_000L, 10L))
            .maintenanceMarginScaleK(1000)
            .maxLeverage(TreeSortedMap.newMapWith(1000L, 75L, 100_000L, 40L))
            .build();

    @Test
    public void testADL() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);

            container.addSymbol(symbol);
            container.addCurrency(symbol.baseCurrency, 0);
            container.addCurrency(symbol.quoteCurrency, 0);

            long UID_LOSER = UID_1;   // 会被强平
            long UID_WINNER = UID_2;  // 盈利，参与 ADL
            long UID_MAKER = UID_3;   // 对手方
            container.createUserWithMoney(UID_LOSER, symbol.quoteCurrency, 5_000);
            container.createUserWithMoney(UID_WINNER, symbol.quoteCurrency, 50_000);
            container.createUserWithMoney(UID_MAKER, symbol.quoteCurrency, MAX_VALUE);

            // === 初始 mark price ===
            long markPrice = 1000;
            container.initMarkPrice(symbol.symbolId, markPrice);

            // 1. LOSER 开高杠杆多仓
            container.createBidWithOrderId(1, UID_LOSER, 5, 1000, symbol.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(2, UID_MAKER, 5, 1000, symbol.symbolId, MarginMode.CROSS);

            container.validateUserState(UID_LOSER, profile -> {
                assertThat(profile.getPositions().get(symbol.symbolId).get(0).getOpenVolume(), is(5L));
            });

            // 2. WINNER 开低杠杆盈利空仓（将来盈利）
            container.createAskWithOrderId(3, UID_WINNER, 10, 1000, symbol.symbolId, MarginMode.ISOLATED);
            container.createBidWithOrderId(4, UID_MAKER, 10, 1000, symbol.symbolId, MarginMode.CROSS);

            container.validateUserState(UID_WINNER, profile -> {
                assertThat(profile.getPositions().get(symbol.symbolId).get(0).getOpenVolume(), is(10L));
            });

            // 3. 价格暴跌，LOSER 巨亏，WINNER 盈利
            container.updateCurrentPriceTo(600, symbol.symbolId, symbol.quoteCurrency);

            // 强制触发一次清算
            container.getApi().groupingControl(0, 1);
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);
            // 等强平触发完成
            Thread.sleep(1_000);

            // 4. 校验loser仓位清仓
            container.validateUserState(UID_LOSER, profile -> {
                assertThat(profile.getPositions().isEmpty(), is(true));
            });

            // winner仓位减仓
            container.validateUserState(UID_WINNER, profile -> {
                SingleUserReportResult.Position position = profile.getPositions().get(symbol.symbolId).get(0);
                assertThat(position.getOpenVolume(), is(5L));
            });

        }
    }

}
