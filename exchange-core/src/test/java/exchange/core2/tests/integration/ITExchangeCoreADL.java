package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAdjustMarkPrice;
import exchange.core2.core.common.api.ApiInsuranceFundDeposit;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.ApiRecoverState;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
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

    /**
     * ADL 级联守恒对照/护栏：精确复刻 Rust exchange-core-rs liquidation_e2e proptest shrink 出的 11 命令最小反例
     * （单分片 + 纯 ApiAdjustMarkPrice，不注入盘口流动性，强制 FORCE→IF→ADL）。mark 92 前四仓与 Rust 逐字段一致
     * （uid1 L9@864 / uid2 S6@506 / uid3 L6@506 profit68 / uid4 S9@808），级联命令也一致（含 ADL uid4@94 size7）。
     *
     * 该反例在 Rust 的【同步内联级联排空】下会造钱：uid1 的 ADL@92 先把 uid4 当对手方消耗掉，随后 uid4 自己的
     * ADL@94 用 stale size 多平 uid3、而 origin 已空 → 全局守恒 +12（Rust 已在 collect 阶段把 ADL 执行量夹到 taker
     * origin 实时 openVolume 修掉）。Java 的【异步 disruptor 级联排序】下不会驱动 uid4 走进该路径（uid3 不被误减），
     * 本测试断言 Java 全程守恒——既作 Java 侧守恒护栏，也对照确认这条 leak 是 Rust 同步塌缩特有、非 Java 侧缺陷。
     */
    @Test
    public void adlOriginConsumedMidCascadeConservation() throws Exception {
        final int base = 11, quote = 12, fut = 5001;
        final CoreSymbolSpecification futSpec = CoreSymbolSpecification.builder()
                .symbolId(fut).type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .baseCurrency(base).baseScaleK(1)
                .quoteCurrency(quote).quoteScaleK(1)
                .takerFee(0).makerFee(0).feeScaleK(10_000)
                .maintenanceMargin(TreeSortedMap.newMapWith(Long.MAX_VALUE, 500L))
                .maintenanceMarginScaleK(10_000)
                .liquidationFee(200)
                .maxLeverage(TreeSortedMap.newMapWith(Long.MAX_VALUE, 1000L))
                .build();

        final PerformanceConfiguration perfCfg = PerformanceConfiguration.baseBuilder()
                .ringBufferSize(16 * 1024)
                .matchingEnginesNum(1)
                .riskEnginesNum(1)
                .build();

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(perfCfg)) {
            container.enableLiquidationEngines();
            container.addCurrency(base, 0);
            container.addCurrency(quote, 0);
            container.addSymbol(futSpec);

            final long u1 = 1, u2 = 2, u3 = 3, u4 = 4;
            for (long uid : new long[]{u1, u2, u3, u4}) {
                container.createUserWithMoney(uid, quote, 1_000_000L);
            }
            setMark(container, fut, 100);
            drainCascade(container);

            long oid = 1000;
            placeIsolated(container, oid++, u3, 84, 5, true, fut); drainCascade(container);
            placeIsolated(container, oid++, u4, 95, 6, false, fut); drainCascade(container);
            setMark(container, fut, 60); drainCascade(container);
            placeIsolated(container, oid++, u4, 86, 5, false, fut); drainCascade(container);
            placeIsolated(container, oid++, u3, 86, 6, true, fut); drainCascade(container);
            placeIsolated(container, oid++, u2, 98, 3, false, fut); drainCascade(container);
            placeIsolated(container, oid++, u1, 98, 9, true, fut); drainCascade(container);
            setMark(container, fut, 100); drainCascade(container);
            setMark(container, fut, 60); drainCascade(container);
            placeIsolated(container, oid++, u2, 80, 14, false, fut); drainCascade(container);

            // mark 92 前四仓应与 Rust 逐字段一致（回归时若此处漂了，说明撮合/保证金分岔，先查这里）
            assertPos(container, u1, fut, PositionDirection.LONG, 9, 864);
            assertPos(container, u2, fut, PositionDirection.SHORT, 6, 506);
            assertPos(container, u3, fut, PositionDirection.LONG, 6, 506);
            assertPos(container, u4, fut, PositionDirection.SHORT, 9, 808);

            // 最后一次 mark 触发级联，drain 排空
            setMark(container, fut, 92);
            drainCascade(container);

            boolean conserved;
            String diag;
            try {
                final TotalCurrencyBalanceReportResult bal = container.totalBalanceReport();
                conserved = bal.isGlobalBalancesAllZero();
                diag = "globalBalancesSum=" + bal.getGlobalBalancesSum();
            } catch (IllegalStateException openInterestImbalance) {
                conserved = false;
                diag = "totalBalanceReport threw (open-interest imbalance): " + openInterestImbalance.getMessage();
            }
            assertThat("ADL origin 被前一轮清算消耗后 stale cmd.size 多平 counterparty，全局守恒破裂: " + diag,
                    conserved, is(true));
        }
    }

    private static void placeIsolated(ExchangeTestContainer container, long orderId, long uid, long price, long size,
                                      boolean bid, int symbolId) {
        final ApiPlaceOrder.ApiPlaceOrderBuilder b = ApiPlaceOrder.builder()
                .uid(uid).orderId(orderId)
                .price(price).size(size)
                .action(bid ? OrderAction.BID : OrderAction.ASK)
                .orderType(OrderType.GTC)
                .symbol(symbolId).marginMode(MarginMode.ISOLATED).leverage(10);
        if (bid) {
            b.reservePrice(price);
        }
        container.getApi().submitCommandAsync(b.build()).join();
    }

    private static void setMark(ExchangeTestContainer container, int symbolId, long markPrice) {
        container.submitCommandSync(ApiAdjustMarkPrice.builder()
                .transactionId(container.getRandomTransactionId())
                .symbol(symbolId).markPrice(markPrice).build(), CommandResultCode.SUCCESS);
    }

    /** 排空 on-lane 触发的 FORCE→IF→ADL 自驱级联：反复 flush 直到守恒报告不再抛 OI-imbalance（对齐 ITConservationFuzz）。 */
    private static void drainCascade(ExchangeTestContainer container) throws Exception {
        final long deadline = System.currentTimeMillis() + 5_000L;
        while (System.currentTimeMillis() < deadline) {
            container.getApi().groupingControl(0, 1);
            try {
                container.totalBalanceReport();
                return;
            } catch (IllegalStateException cascadeInFlight) {
                Thread.sleep(20L);
            }
        }
        container.getApi().groupingControl(0, 1);
    }

    private static void assertPos(ExchangeTestContainer container, long uid, int symbolId,
                                  PositionDirection dir, long openVolume, long openPriceSum) throws Exception {
        final SingleUserReportResult.Position pos = container.getUserProfile(uid).getPositions().get(symbolId).get(0);
        assertThat("uid" + uid + " direction", pos.direction, is(dir));
        assertThat("uid" + uid + " openVolume", pos.openVolume, is(openVolume));
        assertThat("uid" + uid + " openPriceSum", pos.openPriceSum, is(openPriceSum));
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
