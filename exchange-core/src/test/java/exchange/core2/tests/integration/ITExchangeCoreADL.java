package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiInsuranceFundDeposit;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.ApiRecoverState;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.processors.liquidation.LiquidationService;
import exchange.core2.core.processors.liquidation.LiquidationService.IFNotional;
import exchange.core2.core.processors.liquidation.LiquidationService.IFPositionRecord;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.ObjLongConsumer;

import exchange.core2.tests.util.LatencyTools;
import static exchange.core2.tests.util.TestConstants.MAX_VALUE;
import static exchange.core2.tests.util.TestConstants.UID_1;
import static exchange.core2.tests.util.TestConstants.UID_2;
import static exchange.core2.tests.util.TestConstants.UID_3;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

            // 强制触发清算：LiquidationEngine.stop() 后 scheduler 关闭，多步强平 (FORCE→IF→ADL)
            // 需要 caller 通过 onTick 主动 drive；参考 LatencyTools.waitForCondition JavaDoc。
            Runnable trigger = () -> container.triggerLiquidation();
            trigger.run();
            LatencyTools.waitForCondition(5_000, () -> {
                try {
                    return container.getUserProfile(UID_LOSER).getPositions().isEmpty();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, trigger, 100);

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

            // 2. IF 充值：定向给每 shard 各充 5*1000，让每 shard 都有足够资金承接 takeover
            int numShardsTakeover = container.getExchangeCore().getLiquidationEngines().size();
            for (int s = 0; s < numShardsTakeover; s++) {
                container.submitCommandSync(
                        ApiInsuranceFundDeposit.builder()
                                .shardId(s)
                                .transactionId(s + 1L)
                                .symbol(symbol.symbolId)
                                .currencyAmount(5 * 1000L)
                                .build(),
                        CommandResultCode.SUCCESS);
            }

            // 3. 价格暴跌，触发强平
            container.updateCurrentPriceTo(600, symbol.symbolId, symbol.quoteCurrency);

            // 手动触发清算（多步强平靠 onTick 重发 drive，同 testFuturesLiquidationFullLifecycleConservation）
            Runnable trigger = () -> container.triggerLiquidation();
            trigger.run();

            // 等待 LOSER 清仓
            LatencyTools.waitForCondition(5_000, () -> {
                try {
                    return container.getUserProfile(UID_LOSER).getPositions().isEmpty();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, trigger, 100);

            // === 断言：LOSER 清仓 ===
            container.validateUserState(UID_LOSER, profile -> {
                assertThat(profile.getPositions().isEmpty(), is(true));
            });

            // === 断言：没有 ADL 发生（系统内只有 IF）===
            container.validateUserState(UID_MAKER, profile -> {
                assertThat(profile.getPositions().get(symbol.symbolId).get(0).openVolume, is(5L));
            });

            // === 断言：IF 持仓跨 shard 聚合后总 openVolume == 5 ===
            // takeover 由 IFCommandProcessor 按 shardId 顺序分配，
            // 单 shard 可能只承担一部分，故按全部 shard 聚合校验
            long totalIfOpenVolume = sumIfOpenVolume(container, symbol.symbolId, PositionDirection.LONG);
            assertThat(totalIfOpenVolume, is(5L));
        }
    }

    /** 聚合所有 shard 的 IF 持仓 openVolume（按 symbol + direction）。 */
    private static long sumIfOpenVolume(ExchangeTestContainer container, int symbolId, PositionDirection direction) {
        long total = 0;
        for (RiskEngine engine : container.getExchangeCore().getRiskEngines()) {
            LiquidationService svc = engine.getLiquidationService();
            IFPositionRecord pos = svc.getPositions().get(symbolId * direction.getMultiplier());
            if (pos != null) {
                total += pos.openVolume;
            }
        }
        return total;
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

            // === 给每个 shard 定向注入 2000 IF 余额（价格600时能接3张）===
            // 通过 admin 命令定向充值：每 shard 独立入账 + 同步反向记账 adjustments
            List<LiquidationEngine> engines = container.getExchangeCore().getLiquidationEngines();
            int numShards = engines.size();
            for (int s = 0; s < numShards; s++) {
                container.submitCommandSync(
                        ApiInsuranceFundDeposit.builder()
                                .shardId(s)
                                .transactionId(s + 1L)
                                .symbol(symbol.symbolId)
                                .currencyAmount(2 * 1000L)
                                .build(),
                        CommandResultCode.SUCCESS);
            }

            // 价格暴跌，触发强平：多步强平靠 onTick 重发 drive
            container.updateCurrentPriceTo(600, symbol.symbolId, symbol.quoteCurrency);
            Runnable trigger = () -> container.triggerLiquidation();
            trigger.run();

            // 等待强平完成。多步流程 (FORCE→IF→ADL) 中 IF 接管后还需 republish 继续推进，
            // 100ms 节奏 + 5s 总长（最多 50 次重发）覆盖 OS 调度抖动场景。
            LatencyTools.waitForCondition(5_000, () -> {
                try {
                    return container.getUserProfile(UID_LOSER).getPositions().isEmpty();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, trigger, 100);

            // === 断言：LOSER 清仓 ===
            container.validateUserState(UID_LOSER, p -> {
                assertThat(p.getPositions().isEmpty(), is(true));
            });

            // === 断言：单一 shard 无法接满5 （一个接3 一个接2） ===
            for (RiskEngine riskEngine : container.getExchangeCore().getRiskEngines()) {
                LiquidationService svc = riskEngine.getLiquidationService();
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

            int numShards = container.getExchangeCore().getLiquidationEngines().size();

            /* ================= 第一次 ================= */

            // IF 注入资金（admin 命令，定向给每 shard 各充 3*1000）
            for (int s = 0; s < numShards; s++) {
                container.submitCommandSync(
                        ApiInsuranceFundDeposit.builder()
                                .shardId(s)
                                .transactionId(s + 1L)
                                .symbol(symbol.symbolId)
                                .currencyAmount(3 * 1000L)
                                .build(),
                        CommandResultCode.SUCCESS);
            }

            // 建仓 5 张
            container.createBidWithOrderId(1, UID_LOSER, 5, 1000, symbol.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(2, UID_MAKER, 5, 1000, symbol.symbolId, MarginMode.CROSS);

            // 触发强平（第一次）：多步强平靠 onTick 重发 drive
            container.updateCurrentPriceTo(600, symbol.symbolId, symbol.quoteCurrency);
            Runnable trigger1 = () -> container.triggerLiquidation();
            trigger1.run();

            LatencyTools.waitForCondition(5_000, () -> {
                try {
                    return container.getUserProfile(UID_LOSER).getPositions().isEmpty();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, trigger1, 100);

            // 断言：第一次清仓完成
            container.validateUserState(UID_LOSER, p -> {
                assertThat(p.getPositions().isEmpty(), is(true));
            });

            /* ================= 第二次 ================= */

            // 再次注入 IF 资金，定向给每 shard 各充 2*1000
            for (int s = 0; s < numShards; s++) {
                container.submitCommandSync(
                        ApiInsuranceFundDeposit.builder()
                                .shardId(s)
                                .transactionId(100L + s)
                                .symbol(symbol.symbolId)
                                .currencyAmount(2 * 1000L)
                                .build(),
                        CommandResultCode.SUCCESS);
            }

            // 用户重新建仓 4 张
            container.createBidWithOrderId(3, UID_LOSER, 4, 700, symbol.symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(4, UID_MAKER, 4, 700, symbol.symbolId, MarginMode.CROSS);

            // 再次暴跌（第二次）：多步强平靠 onTick 重发 drive
            container.updateCurrentPriceTo(400, symbol.symbolId, symbol.quoteCurrency);
            Runnable trigger2 = () -> container.triggerLiquidation();
            trigger2.run();

            LatencyTools.waitForCondition(5_000, () -> {
                try {
                    return container.getUserProfile(UID_LOSER).getPositions().isEmpty();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, trigger2, 100);

            // 断言：第二次也清仓
            container.validateUserState(UID_LOSER, p -> {
                assertThat(p.getPositions().isEmpty(), is(true));
            });

            // 断言：跨所有 shard 的 reserved 都已释放，没有 pending 泄漏
            for (RiskEngine riskEngine : container.getExchangeCore().getRiskEngines()) {
                LiquidationService svc = riskEngine.getLiquidationService();
                IFNotional notional = svc.getNotionals().get(symbol.symbolId);
                if (notional != null) {
                    assertThat(notional.reserved, is(0L));
                }
            }
        }
    }

    @Test
    public void testPersistenceAndRecovery() throws Exception {

        long stateId;
        int originalStateHash;
        IfAggregate aggregateBefore;
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

            // IF 充值：admin 命令定向给每 shard 各充 5*1000
            int numShards = container.getExchangeCore().getLiquidationEngines().size();
            for (int s = 0; s < numShards; s++) {
                container.submitCommandSync(
                        ApiInsuranceFundDeposit.builder()
                                .shardId(s)
                                .transactionId(s + 1L)
                                .symbol(symbolId)
                                .currencyAmount(5 * 1000L)
                                .build(),
                        CommandResultCode.SUCCESS);
            }

            // 触发强平：多步强平靠 onTick 重发 drive
            container.updateCurrentPriceTo(600, symbolId, symbol.quoteCurrency);
            Runnable trigger = () -> container.triggerLiquidation();
            trigger.run();

            // 等 LOSER 清仓
            LatencyTools.waitForCondition(5_000, () -> {
                try {
                    return container.getUserProfile(UID_LOSER).getPositions().isEmpty();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, trigger, 100);

            // ====== 强平后断言（snapshot 前）—— 跨 shard 聚合 ======
            // 注：实际成交价取决于 priceRecord 在 triggerLiquidation 读取时刻的 bid/ask 状态，
            // 这是 updateCurrentPriceTo 的 R2 路径和 triggerLiquidation 之间的 race，因 JVM 时序而异
            // （bid 价存在时按 600 成交，回落到 openAvg 时按 1000 成交）。
            // 这里只校验：IF 确实接走了 5 张多仓、无 reserved 残留；
            // 具体 openPriceSum/available 用变量保存，留到 recovery 后断言"前后完全一致"。
            aggregateBefore = aggregateIf(container, symbolId, PositionDirection.LONG);
            assertThat(aggregateBefore.openVolume, is(5L));
            assertThat(aggregateBefore.reservedSum, is(0L));

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

            // ====== 恢复后业务断言：跨 shard 聚合，与 snapshot 前严格一致 ======
            IfAggregate aggregateAfter = aggregateIf(restored, symbolId, PositionDirection.LONG);
            assertThat(aggregateAfter.openVolume, is(aggregateBefore.openVolume));
            assertThat(aggregateAfter.openPriceSum, is(aggregateBefore.openPriceSum));
            assertThat(aggregateAfter.availableSum, is(aggregateBefore.availableSum));
            assertThat(aggregateAfter.reservedSum, is(aggregateBefore.reservedSum));
        }
    }

    /** 聚合所有 shard 的 IF 状态（按 symbol + direction）。 */
    private static IfAggregate aggregateIf(ExchangeTestContainer container, int symbolId, PositionDirection direction) {
        IfAggregate agg = new IfAggregate();
        // LiquidationService 已经从 LiquidationEngine 搬到 RiskEngine（per-shard），直接走 @Getter，免反射。
        for (RiskEngine engine : container.getExchangeCore().getRiskEngines()) {
            LiquidationService svc = engine.getLiquidationService();
            IFPositionRecord pos = svc.getPositions().get(symbolId * direction.getMultiplier());
            if (pos != null) {
                agg.openVolume += pos.openVolume;
                agg.openPriceSum += pos.openPriceSum;
            }
            IFNotional notional = svc.getNotionals().get(symbolId);
            if (notional != null) {
                agg.availableSum += notional.available;
                agg.reservedSum += notional.reserved;
            }
        }
        return agg;
    }

    private static final class IfAggregate {
        long openVolume;
        long openPriceSum;
        long availableSum;
        long reservedSum;
    }
}
