package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.ApiRecoverState;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.processors.liquidation.LiquidationService;
import exchange.core2.core.processors.liquidation.LiquidationService.IFNotional;
import exchange.core2.core.processors.liquidation.LiquidationService.IFPositionRecord;
import exchange.core2.core.utils.ReflectionUtils;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.Test;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.ObjLongConsumer;

import exchange.core2.tests.util.LatencyTools;
import static exchange.core2.tests.util.TestConstants.MAX_VALUE;
import static exchange.core2.tests.util.TestConstants.UID_1;
import static exchange.core2.tests.util.TestConstants.UID_2;
import static exchange.core2.tests.util.TestConstants.UID_3;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertNotNull;

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
            LatencyTools.waitForCondition(100, () -> {
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
            LatencyTools.waitForCondition(200, () -> {
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
            IntObjectHashMap<IFPositionRecord> positions = liquidationServiceShard0.getPositions();
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
            LatencyTools.waitForCondition(1000, () -> {
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
                IntObjectHashMap<IFPositionRecord> pos = svc.getPositions();
                // IF 不应接 5
                IFPositionRecord ifPos = pos.get(symbol.symbolId * PositionDirection.LONG.getMultiplier());
                if (ifPos != null) {
                    assertThat(ifPos.openVolume < 5, is(true));
                }
            }
        }
    }


    @Test
    public void testLiquidationReopenAndReliquidate() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {

            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);

            container.addSymbol(symbol);
            container.addCurrency(symbol.baseCurrency, 0);
            container.addCurrency(symbol.quoteCurrency, 0);

            long UID_LOSER = UID_1;
            long UID_MAKER = UID_2;

            container.createUserWithMoney(UID_LOSER, symbol.quoteCurrency, 20_000);
            container.createUserWithMoney(UID_MAKER, symbol.quoteCurrency, MAX_VALUE);

            container.initMarkPrice(symbol.symbolId, 1000);

            LiquidationEngine engine = container.getExchangeCore().getLiquidationEngines().get(0);
            LiquidationService svc = ReflectionUtils.extractField(LiquidationEngine.class, engine, "liquidationService");

            /* ================= 第一次 ================= */

            // IF 注入资金
            svc.creditLiquidationFee(symbol.symbolId, 3 * 1000);

            // 建仓 5 张
            container.createBidWithOrderId(1, UID_LOSER, 5, 1000, symbol.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(2, UID_MAKER, 5, 1000, symbol.symbolId, MarginMode.CROSS);

            // 触发强平
            container.updateCurrentPriceTo(600, symbol.symbolId, symbol.quoteCurrency);
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            LatencyTools.waitForCondition(300, () -> {
                try {
                    return container.getUserProfile(UID_LOSER).getPositions().isEmpty();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // 断言：第一次清仓完成
            container.validateUserState(UID_LOSER, p -> {
                assertThat(p.getPositions().isEmpty(), is(true));
            });

            /* ================= 第二次 ================= */

            // 再次注入 IF 资金
            svc.creditLiquidationFee(symbol.symbolId, 2 * 1000);

            // 用户重新建仓 4 张
            container.createBidWithOrderId(3, UID_LOSER, 4, 700, symbol.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(4, UID_MAKER, 4, 700, symbol.symbolId, MarginMode.CROSS);

            // 再次暴跌
            container.updateCurrentPriceTo(400, symbol.symbolId, symbol.quoteCurrency);
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            LatencyTools.waitForCondition(300, () -> {
                try {
                    return container.getUserProfile(UID_LOSER).getPositions().isEmpty();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // 断言：第二次也清仓
            container.validateUserState(UID_LOSER, p -> {
                assertThat(p.getPositions().isEmpty(), is(true));
            });

            // 断言：pending 没泄漏
            IntObjectHashMap<IFNotional> notionals = svc.getNotionals();
            IFNotional notional = notionals.get(symbol.symbolId);
            assertThat(notional.reserved, is(0L));
        }
    }

    @Test
    public void testPersistenceAndRecovery() throws Exception {

        long stateId;
        int originalStateHash;
        ObjLongConsumer<OrderCommand> emptyConsumer = (cmd, seq) -> {};

        final String exchangeId = String.format("%012X", System.currentTimeMillis());
        final InitialStateConfiguration firstStartConfig = InitialStateConfiguration.cleanStart(exchangeId);
        final int symbolId = symbol.symbolId;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT,
                firstStartConfig, SerializationConfiguration.DISK_SNAPSHOT_ONLY, emptyConsumer)) {

            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);

            container.addSymbol(symbol);
            container.addCurrency(symbol.baseCurrency, 0);
            container.addCurrency(symbol.quoteCurrency, 0);

            // 取 shard0 的 LiquidationService
            LiquidationService liquidationService = ReflectionUtils.extractField(LiquidationEngine.class,
                    container.getExchangeCore().getLiquidationEngines().get(0), "liquidationService");

            long UID_LOSER = UID_1;
            long UID_MAKER = UID_2;

            container.createUserWithMoney(UID_LOSER, symbol.quoteCurrency, 10_000);
            container.createUserWithMoney(UID_MAKER, symbol.quoteCurrency, MAX_VALUE);

            // 初始价格
            container.initMarkPrice(symbolId, 1000);

            // LOSER 建仓
            container.createBidWithOrderId(1, UID_LOSER, 5, 1000, symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(2, UID_MAKER, 5, 1000, symbolId, MarginMode.CROSS);

            container.validateUserState(UID_LOSER, profile -> {
                assertThat(profile.getPositions().get(symbolId).get(0).getOpenVolume(), is(5L));
            });

            // IF 充值（模拟强平手续费进入 IF）
            liquidationService.creditLiquidationFee(symbolId, 5 * 1000);

            // 触发强平
            container.updateCurrentPriceTo(600, symbolId, symbol.quoteCurrency);
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            // 等 LOSER 清仓
            LatencyTools.waitForCondition(300, () -> {
                try {
                    return container.getUserProfile(UID_LOSER).getPositions().isEmpty();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            // ====== 强平后断言（snapshot 前）======
            IntObjectHashMap<IFPositionRecord> positionsBefore = liquidationService.getPositions();
            IFPositionRecord ifPosBefore = positionsBefore.get(symbolId * PositionDirection.LONG.getMultiplier());

            assertNotNull(ifPosBefore);
            assertThat(ifPosBefore.openVolume, is(5L));
            assertThat(ifPosBefore.openPriceSum, is(3_000L));

            IntObjectHashMap<IFNotional> notionals = liquidationService.getNotionals();
            LiquidationService.IFNotional notionalBefore = notionals.get(symbolId);

            assertThat(notionalBefore.available, is(2_000L));
            assertThat(notionalBefore.reserved, is(0L));

            // ====== Snapshot ======
            stateId = System.currentTimeMillis() * 1000;
            ApiPersistState persist = ApiPersistState.builder().dumpId(stateId).build();

            assertThat(container.getApi().submitCommandAsync(persist).get(), is(CommandResultCode.SUCCESS));

            container.totalBalanceReport();
            originalStateHash = container.requestStateHash();
        }

        System.gc();
        Thread.sleep(200);

        // ====== 从 snapshot 恢复 ======
        InitialStateConfiguration fromSnapshot = InitialStateConfiguration.fromSnapshotOnly(exchangeId, stateId, 0);

        try (final ExchangeTestContainer restored = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT,
                fromSnapshot, SerializationConfiguration.DISK_SNAPSHOT_ONLY, emptyConsumer)) {

            restored.getExchangeCore().liquidationEngines.forEach(LiquidationEngine::stop);

            restored.getApi().submitRecoverCommandAsync(ApiRecoverState.builder().snapshotId(fromSnapshot.getSnapshotId()).build()).get();

            // 总体验证
            restored.totalBalanceReport();
            assertThat(restored.requestStateHash(), is(originalStateHash));

            // ====== 恢复后业务断言 ======
            LiquidationService restoredService = ReflectionUtils.extractField(LiquidationEngine.class,
                    restored.getExchangeCore().liquidationEngines.get(0), "liquidationService");

            IntObjectHashMap<IFPositionRecord> positionsAfter = restoredService.getPositions();
            IFPositionRecord ifPosAfter = positionsAfter.get(symbolId * PositionDirection.LONG.getMultiplier());

            assertNotNull(ifPosAfter);
            assertThat(ifPosAfter.openVolume, is(5L));
            assertThat(ifPosAfter.openPriceSum, is(3_000L));

            IntObjectHashMap<IFNotional> notionalsAfter = restoredService.getNotionals();
            IFNotional notionalAfter = notionalsAfter.get(symbolId);

            assertThat(notionalAfter.available, is(2_000L));
            assertThat(notionalAfter.reserved, is(0L));
        }
    }
}
