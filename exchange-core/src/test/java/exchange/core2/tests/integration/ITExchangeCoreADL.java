package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.processors.liquidation.LiquidationService;
import exchange.core2.core.processors.liquidation.LiquidationService.IFPositionRecord;
import exchange.core2.core.utils.ReflectionUtils;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.Test;

import java.util.List;
import java.util.function.BooleanSupplier;

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
            container.getExchangeCore().getLiquidationEngines().forEach(le -> le.setIfEnabled(false));

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
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);
            // 等强平触发完成
            waitForCondition(100, () -> {
                try {
                    return container.getUserProfile(UID_LOSER).getPositions().isEmpty();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

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

    public static void waitForCondition(long timeoutMillis, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for condition", e);
            }
        }
        throw new AssertionError("Condition not met within " + timeoutMillis + " ms");
    }

    @Test
    public void testIFTakeover() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            // 停止自动调度，手动触发
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);
            LiquidationService liquidationServiceShard0 = ReflectionUtils.extractField(LiquidationEngine.class, container.getExchangeCore().getLiquidationEngines().get(0), "liquidationService");

            container.addSymbol(symbol);
            container.addCurrency(symbol.baseCurrency, 0);
            container.addCurrency(symbol.quoteCurrency, 0);

            long UID_LOSER  = UID_1;
            long UID_MAKER  = UID_2;

            container.createUserWithMoney(UID_LOSER, symbol.quoteCurrency, 5_000);
            container.createUserWithMoney(UID_MAKER, symbol.quoteCurrency, MAX_VALUE);

            // === 初始 mark price ===
            long markPrice = 1000;
            container.initMarkPrice(symbol.symbolId, markPrice);

            // 1. LOSER 建仓（多头）
            container.createBidWithOrderId(1, UID_LOSER, 5, 1000, symbol.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(2, UID_MAKER, 5, 1000, symbol.symbolId, MarginMode.CROSS);

            container.validateUserState(UID_LOSER, profile -> {
                assertThat(profile.getPositions().get(symbol.symbolId).get(0).getOpenVolume(), is(5L));
            });

            // 2. IF充值
            liquidationServiceShard0.creditLiquidationFee(symbol.symbolId, 5 * 1000);

            // 3. 价格暴跌，触发强平
            container.updateCurrentPriceTo(600, symbol.symbolId, symbol.quoteCurrency);

            // 手动触发清算
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            // 等待 LOSER 清仓
            waitForCondition(200, () -> {
                try {
                    return container.getUserProfile(UID_LOSER).getPositions().isEmpty();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // === 断言：LOSER 清仓 ===
            container.validateUserState(UID_LOSER, profile -> {
                assertThat(profile.getPositions().isEmpty(), is(true));
            });

            // === 断言：没有 ADL 发生（系统内只有 IF）===
            container.validateUserState(UID_MAKER, profile -> {
                assertThat(profile.getPositions().get(symbol.symbolId).get(0).openVolume, is(5L));
            });

            // === 断言：IF 内部仓位 ===
            IntObjectHashMap<IFPositionRecord> positions = ReflectionUtils.extractField(LiquidationService.class, liquidationServiceShard0, "positions");
            IFPositionRecord ifPos = positions.get(symbol.symbolId * PositionDirection.LONG.getMultiplier());
            assertThat(ifPos.openVolume, is(5L));
        }
    }

    @Test
    public void testIFMultiShardBoundary() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            // 关闭自动调度，手动触发
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);

            container.addSymbol(symbol);
            container.addCurrency(symbol.baseCurrency, 0);
            container.addCurrency(symbol.quoteCurrency, 0);

            long UID_LOSER = UID_1;
            long UID_MAKER = UID_2;

            container.createUserWithMoney(UID_LOSER, symbol.quoteCurrency, 5_000);
            container.createUserWithMoney(UID_MAKER, symbol.quoteCurrency, MAX_VALUE);

            // 初始价格
            container.initMarkPrice(symbol.symbolId, 1000);

            // LOSER 建 5 张多仓
            container.createBidWithOrderId(1, UID_LOSER, 5, 1000, symbol.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(2, UID_MAKER, 5, 1000, symbol.symbolId, MarginMode.CROSS);

            // === 给每个 shard 注入有限 IF 余额（价格600时能接3张）===
            List<LiquidationEngine> engines = container.getExchangeCore().getLiquidationEngines();

            for (LiquidationEngine engine : engines) {
                LiquidationService svc = ReflectionUtils.extractField(LiquidationEngine.class, engine, "liquidationService");
                svc.creditLiquidationFee(symbol.symbolId, 2 * 1000);
            }

            // 价格暴跌，触发强平
            container.updateCurrentPriceTo(600, symbol.symbolId, symbol.quoteCurrency);
            engines.forEach(LiquidationEngine::triggerOnce);

            // 等待强平完成
            waitForCondition(300, () -> {
                try {
                    return container.getUserProfile(UID_LOSER).getPositions().isEmpty();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // === 断言：LOSER 清仓 ===
            container.validateUserState(UID_LOSER, p -> {
                assertThat(p.getPositions().isEmpty(), is(true));
            });

            // === 断言：单一 shard 无法接满5 （一个接3 一个接2） ===
            for (LiquidationEngine engine : engines) {
                LiquidationService svc = ReflectionUtils.extractField(LiquidationEngine.class, engine, "liquidationService");
                IntObjectHashMap<LiquidationService.IFPositionRecord> pos = ReflectionUtils.extractField(LiquidationService.class, svc, "positions");
                // IF 不应接 5
                LiquidationService.IFPositionRecord ifPos = pos.get(symbol.symbolId * PositionDirection.LONG.getMultiplier());
                if (ifPos != null) {
                    assertThat(ifPos.openVolume < 5, is(true));
                }
            }
        }
    }
}
