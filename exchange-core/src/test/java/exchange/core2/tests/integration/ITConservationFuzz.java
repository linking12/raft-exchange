package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.PositionMode;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAdjustMargin;
import exchange.core2.core.common.api.ApiAdjustMarkPrice;
import exchange.core2.core.common.api.ApiAdjustPositionMode;
import exchange.core2.core.common.api.ApiCancelOrder;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.ApiSettleFundingFees;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static exchange.core2.tests.util.TestConstants.CURRENECY_USD;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全局守恒不变量 fuzz 测试。
 *
 * 思路：随机生成 N 个命令序列（开仓 / 平仓 / 撤单 / 追加保证金 / 标记价格变动 / 资金费率），
 * 每条命令处理完后立即检查 isGlobalBalancesAllZero —— 任何 scale 错配 / 计算漏算 / 路径
 * 不一致都会让守恒方程瞬间破裂，比 hand-written lifecycle 测试覆盖广。
 *
 * 使用固定 seed，失败可复现；同时打印 seed 让 Bug 容易追。
 *
 * 不断言 SUCCESS —— 大部分随机命令会被 RiskEngine 合理拒绝（如余额不足、撤不存在的单等），
 * 那也是引擎正确性的一部分（拒绝不应破坏状态）。
 */
@ExtendWith(MockitoExtension.class)
@Slf4j
class ITConservationFuzz {

    @Mock
    private IEventsHandler4Test handler;

    private SimpleEventsProcessor4Test processor;

    @BeforeEach
    public void before() {
        processor = new SimpleEventsProcessor4Test(handler, true);
    }

    /**
     * 单分片 fuzz —— 重点覆盖 scale 一致性（ApiAdjustMargin / fee 计算 / PnL settle）。
     * 不跑强平触发（手动控强平在另一个测试），但通过 mark 大幅变动让 PnL 波动产生 scale 暴露。
     */
    @Test
    @Timeout(60)
    public void fuzzSingleShardConservation() {
        long seed = Long.parseLong(System.getProperty("fuzz.seed", "20260605"));
        runFuzz(seed, /*numUsers*/ 6, /*numOps*/ 300, /*numShards*/ 1, /*allowFundingSettle*/ false);
    }

    /**
     * 多分片 fuzz —— 重点暴露 cross-shard 路径的 corner case：
     * - 跨 shard 撮合（taker shard0 吃 maker shard1）
     * - 资金费率结算的 shard 聚合（fundingPayAmountByShard / fundingRecvNotionalByShard）
     * - userProfileServices map 在 disruptor pipeline 下的可见性
     */
    @Test
    @Timeout(120)
    public void fuzzMultiShardConservation() {
        long seed = Long.parseLong(System.getProperty("fuzz.seed", "20260605"));
        runFuzz(seed, /*numUsers*/ 8, /*numOps*/ 300, /*numShards*/ 4, /*allowFundingSettle*/ true);
    }

    /**
     * 强平/IF/ADL 多 shard fuzz —— 专门覆盖之前 unified pre-alloc 修复但缺少直接验证的 3 个数组：
     *   - {@code ifPreviewCoverByShard}（由 collectIFPreviewData 写）
     *   - {@code adlUserPositionsByShard}（由 collectADLProfitablePositions 写）
     *   - {@code makerFundEventsByShard}（由 FundEventsHelper.sendMakerFundEvent 写，跨 shard maker 撮合时触发）
     *
     * 验证思路：
     *   1) 多 shard 设置（4 shards），每 shard 都有 user 持仓
     *   2) 关掉自动强平，手动 triggerOnce 控制时序
     *   3) 反复"建仓 → 暴跌/暴涨 → 触发强平 → 验证守恒"
     *   4) 若 race 仍存在，会通过 IF 接管 / ADL 路径漂账 → 守恒断言立刻报错
     */
    @Test
    @Timeout(120)
    public void fuzzLiquidationConservation() throws Exception {
        long seed = Long.parseLong(System.getProperty("fuzz.seed", "20260605"));
        final int numShards = 4;
        final int numVictims = 4;       // 持仓被强平的用户
        final int numLPs = 4;           // 提供流动性 + IF/ADL 接盘
        final int numCycles = 8;        // 多轮"建仓→崩盘→强平"循环

        final PerformanceConfiguration perfCfg = PerformanceConfiguration.baseBuilder()
                .ringBufferSize(16 * 1024)
                .matchingEnginesNum(numShards)
                .riskEnginesNum(numShards)
                .build();

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(perfCfg, processor)) {
            // 关自动强平，由 fuzz 手动控时序
            container.getExchangeCore().getLiquidationEngines().forEach(exchange.core2.core.processors.liquidation.LiquidationEngine::stop);

            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            final int symbolId = symbols.get(0).symbolId;
            long markPrice = 50_000L;
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 50_000L));

            final Random rng = new Random(seed);

            // 撒 uid 跨所有 shard：uid & (numShards-1) 决定 shard
            final long[] victimUids = new long[numVictims];
            final long[] lpUids = new long[numLPs];
            for (int i = 0; i < numVictims; i++) {
                victimUids[i] = 200_000L + i;     // shard = i % 4
                container.createUserWithSpecificMoney(victimUids[i], 50_000L, CURRENECY_USD);
            }
            for (int i = 0; i < numLPs; i++) {
                lpUids[i] = 300_000L + i;
                container.createUserWithSpecificMoney(lpUids[i], 500_000_000L, CURRENECY_USD);
            }
            assertConserved(container, "after deposit", -1L, seed);

            long orderIdCounter = 1_000_000L;

            for (int cycle = 0; cycle < numCycles; cycle++) {
                // ===== Phase 1: LP 挂 ASK，victim 用 ISOLATED BID 高杠杆开多 =====
                for (int i = 0; i < numLPs; i++) {
                    container.getApi().submitCommandAsync(
                            ApiPlaceOrder.builder()
                                    .uid(lpUids[i]).orderId(orderIdCounter++)
                                    .price(markPrice).size(5L)
                                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                                    .symbol(symbolId).marginMode(MarginMode.CROSS)
                                    .build()).join();
                }
                for (int i = 0; i < numVictims; i++) {
                    container.getApi().submitCommandAsync(
                            ApiPlaceOrder.builder()
                                    .uid(victimUids[i]).orderId(orderIdCounter++)
                                    .price(markPrice).reservePrice(markPrice).size(5L)
                                    .action(OrderAction.BID).orderType(OrderType.GTC)
                                    .symbol(symbolId).marginMode(MarginMode.ISOLATED).leverage(10)
                                    .build()).join();
                }
                assertConserved(container, "cycle " + cycle + " after open", cycle, seed);

                // ===== Phase 2: 预挂 acceptor BID 在低价（不一定够吃强平卖单，迫使 IF/ADL 介入）=====
                long crashPrice = markPrice / 2 + rng.nextInt(1000);
                // 50% 概率给少量流动性，50% 不给（强迫 IF takeover）
                if (rng.nextBoolean()) {
                    for (int i = 0; i < 2; i++) {
                        container.getApi().submitCommandAsync(
                                ApiPlaceOrder.builder()
                                        .uid(lpUids[i]).orderId(orderIdCounter++)
                                        .price(crashPrice).reservePrice(crashPrice).size(5L)
                                        .action(OrderAction.BID).orderType(OrderType.GTC)
                                        .symbol(symbolId).marginMode(MarginMode.CROSS)
                                        .build()).join();
                    }
                }

                // ===== Phase 3: 砸到 crashPrice，触发强平 =====
                container.updateCurrentPriceTo((int) crashPrice, symbolId, CURRENECY_USD);
                markPrice = crashPrice;

                // 手动触发所有 shard 的 LiquidationEngine —— 这条路径会走 collectIFPreviewData
                // 写入 ifPreviewCoverByShard，可能进而触发 collectADLProfitablePositions 写
                // adlUserPositionsByShard
                container.getExchangeCore().getLiquidationEngines()
                        .forEach(exchange.core2.core.processors.liquidation.LiquidationEngine::triggerOnce);
                // 多 shard 强平 / IF / ADL 链路：用 ITLiquidationIntegration 的成熟同步模式 ——
                // sleep 给 pipeline 时间排空 + groupingControl 强制 flush + 二次 sleep 收尾。
                // 比 waitForCondition 稳：trigger 触发的命令在 disruptor 多 stage 内异步排队，
                // shard 间触发顺序无保证；groupingControl 才能保证所有 shard 都被推进。
                Thread.sleep(500L);
                container.getApi().groupingControl(0, 1);
                Thread.sleep(500L);

                assertConserved(container, "cycle " + cycle + " after liquidation", cycle, seed);

                // ===== Phase 4: 价格回升，准备下一轮 =====
                markPrice = 50_000L + rng.nextInt(5000);
                container.updateCurrentPriceTo((int) markPrice, symbolId, CURRENECY_USD);

                assertConserved(container, "cycle " + cycle + " after price recover", cycle, seed);
            }

            log.info("Liquidation fuzz seed={} shards={} cycles={} —— IF/ADL 路径全程守恒",
                    seed, numShards, numCycles);
        }
    }

    private void runFuzz(long seed, int numUsers, int numOps, int numShards, boolean allowFundingSettle) {
        final Random rng = new Random(seed);
        final PerformanceConfiguration perfCfg = PerformanceConfiguration.baseBuilder()
                .ringBufferSize(16 * 1024)
                .matchingEnginesNum(numShards)
                .riskEnginesNum(numShards)
                .build();

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(perfCfg, processor)) {
            // ===== Setup =====
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            final int symbolId = symbols.get(0).symbolId;
            final long initialMarkPrice = 50_000L;
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, initialMarkPrice));

            // 给 user UID 撒上 shardMask 的所有 bit，确保跨 shard 分布
            // UID 用 100000..100000+numUsers，shard = uid & (numShards-1)
            final long[] uids = new long[numUsers];
            final long initialDeposit = 100_000_000L; // 1e8 currency 单位，给充足的活动空间
            for (int i = 0; i < numUsers; i++) {
                uids[i] = 100_000L + i;
                container.createUserWithSpecificMoney(uids[i], initialDeposit, CURRENECY_USD);
            }
            // 初始 deposit 之后状态应该全平
            assertConserved(container, "after deposit", -1L, seed);

            // 跟踪每个 user 已用的 orderId（避免冲突）+ 已挂的 orderIds（供撤单）
            final long[] nextOrderId = new long[numUsers];
            for (int i = 0; i < numUsers; i++) nextOrderId[i] = 1_000_000L + i * 100_000L;
            final List<long[]> openOrders = new ArrayList<>(); // [userIdx, orderId, symbolId]

            long markPrice = initialMarkPrice;

            // ===== Fuzz loop =====
            for (int step = 0; step < numOps; step++) {
                int op = rng.nextInt(allowFundingSettle ? 100 : 90);
                ApiCommand cmd = null;
                try {
                    if (op < 50) {
                        // 50%: place order
                        int u = rng.nextInt(numUsers);
                        OrderAction action = rng.nextBoolean() ? OrderAction.BID : OrderAction.ASK;
                        long size = 1L + rng.nextInt(10);
                        // 价格围绕 markPrice ±10%，部分会立刻撮合，部分挂单
                        long priceJitter = rng.nextInt(10000) - 5000;
                        long price = Math.max(1L, markPrice + priceJitter);
                        MarginMode mm = rng.nextBoolean() ? MarginMode.ISOLATED : MarginMode.CROSS;
                        long orderId = nextOrderId[u]++;
                        ApiPlaceOrder.ApiPlaceOrderBuilder b = ApiPlaceOrder.builder()
                                .uid(uids[u]).orderId(orderId)
                                .price(price).size(size)
                                .action(action).orderType(OrderType.GTC)
                                .symbol(symbolId).marginMode(mm);
                        if (action == OrderAction.BID) b.reservePrice(price);
                        cmd = b.build();
                        // 记录可能的挂单（失败也无所谓，撤单失败也无害）
                        openOrders.add(new long[]{u, orderId, symbolId});
                    } else if (op < 60 && !openOrders.isEmpty()) {
                        // 10%: cancel a random tracked order
                        int idx = rng.nextInt(openOrders.size());
                        long[] od = openOrders.remove(idx);
                        cmd = ApiCancelOrder.builder()
                                .uid(uids[(int) od[0]]).orderId(od[1]).symbol((int) od[2])
                                .build();
                    } else if (op < 75) {
                        // 15%: ApiAdjustMargin —— 这是 scale bug 高发区
                        int u = rng.nextInt(numUsers);
                        long amount = 100L + rng.nextInt(10000);
                        MarginMode mm = rng.nextBoolean() ? MarginMode.ISOLATED : MarginMode.CROSS;
                        OrderAction action = rng.nextBoolean() ? OrderAction.BID : OrderAction.ASK;
                        cmd = ApiAdjustMargin.builder()
                                .transactionId(container.getRandomTransactionId())
                                .uid(uids[u])
                                .symbol(mm == MarginMode.ISOLATED ? symbolId : CURRENECY_USD)
                                .currency(CURRENECY_USD)
                                .action(action)
                                .amount(amount)
                                .marginMode(mm)
                                .build();
                    } else if (op < 85) {
                        // 10%: update mark price (jitter ±5%)
                        long mpJitter = rng.nextInt(5000) - 2500;
                        markPrice = Math.max(1L, markPrice + mpJitter);
                        cmd = ApiAdjustMarkPrice.builder()
                                .transactionId(container.getRandomTransactionId())
                                .markPrice(markPrice)
                                .symbol(symbolId)
                                .build();
                    } else if (op < 90) {
                        // 5%: switch position mode (HEDGE/ONEWAY) —— 暴露 HEDGE 路径的 corner case
                        int u = rng.nextInt(numUsers);
                        PositionMode mode = rng.nextBoolean() ? PositionMode.HEDGE : PositionMode.ONEWAY;
                        cmd = ApiAdjustPositionMode.builder()
                                .uid(uids[u]).positionMode(mode)
                                .build();
                    } else if (op < 100 && allowFundingSettle) {
                        // 10% (multi-shard only): settle funding fees —— 测试 fundingPayByShard 聚合
                        OrderAction direction = rng.nextBoolean() ? OrderAction.BID : OrderAction.ASK;
                        long fundingRate = 1L + rng.nextInt(10);
                        long rateScaleK = 1000L + rng.nextInt(9000);
                        cmd = ApiSettleFundingFees.builder()
                                .transactionId(container.getRandomTransactionId())
                                .symbol(symbolId)
                                .action(direction)
                                .fundingRate(fundingRate)
                                .rateScaleK(rateScaleK)
                                .build();
                    }
                    if (cmd != null) {
                        container.getApi().submitCommandAsync(cmd).join();
                    }
                } catch (Exception e) {
                    // 命令失败本身无害（risk engine 拒绝是合理的），但守恒必须仍成立
                    log.debug("step {} cmd {} threw: {}", step, cmd, e.toString());
                }

                // ===== Invariant check =====
                assertConserved(container, "step " + step + " op=" + op + " cmd=" + cmd, step, seed);
            }

            log.info("Fuzz seed={} shards={} users={} ops={} —— 全程守恒",
                    seed, numShards, numUsers, numOps);
        }
    }

    private void assertConserved(ExchangeTestContainer container, String where, long step, long seed) {
        TotalCurrencyBalanceReportResult bal = container.totalBalanceReport();
        if (!bal.isGlobalBalancesAllZero()) {
            // 输出详细 breakdown 帮助定位是哪个分量漂了
            String diag = String.format(
                    "全局守恒破裂 (seed=%d step=%d at %s): accountBalances=%s extraMargin=%s "
                            + "exchangeLocked=%s fees=%s adjustments=%s suspends=%s ifBalances=%s",
                    seed, step, where,
                    bal.getAccountBalances(), bal.getExtraMargin(),
                    bal.getExchangeLocked(), bal.getFees(),
                    bal.getAdjustments(), bal.getSuspends(), bal.getIfBalances());
            log.error(diag);
            assertTrue(false, diag);
        }
    }

    /**
     * Failed-placeOrder fuzz —— 专门覆盖 deferred-insert 改动：
     * 多 user × 多 shard 反复随机提交 NSF / leverage / reduce-only-zero 三类必拒下单，
     * 验证：① 全局守恒恒成立；② accounts 不变；③ exchangeLocked 不被污染；④ positions map 不残留空 record。
     * 任何一处 fail-path 漏 cleanup 或误删现有仓位都会立刻报错。
     */
    @Test
    @Timeout(60)
    public void fuzzFailedPlaceOrdersConserveBalance() throws Exception {
        long seed = Long.parseLong(System.getProperty("fuzz.seed", "20260628"));
        final int numUsers = 5;
        final int numShards = 4;
        final int numRounds = 50; // 每 user 每轮 3 次拒单 → 5 × 50 × 3 = 750 次拒单
        final long initialDeposit = 1_000L; // 故意做小，让 NSF 容易触发

        final PerformanceConfiguration perfCfg = PerformanceConfiguration.baseBuilder()
                .ringBufferSize(16 * 1024)
                .matchingEnginesNum(numShards)
                .riskEnginesNum(numShards)
                .build();

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(perfCfg, processor)) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            final int symbolId = symbols.get(0).symbolId;
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 50_000L));

            final long[] uids = new long[numUsers];
            for (int i = 0; i < numUsers; i++) {
                uids[i] = 100_000L + i; // shard = uid & (numShards-1)，覆盖所有 shard
                container.createUserWithSpecificMoney(uids[i], initialDeposit, CURRENECY_USD);
            }
            assertConserved(container, "after deposit", -1L, seed);

            final Random rng = new Random(seed);
            long orderIdCounter = 1L;

            for (int round = 0; round < numRounds; round++) {
                for (long uid : uids) {
                    final OrderAction action = rng.nextBoolean() ? OrderAction.BID : OrderAction.ASK;

                    // 拒单 1: leverage 超限（spec maxLeverage tier ≤ 10，传 200 必拒）
                    container.getApi().submitCommandAsync(
                            ApiPlaceOrder.builder()
                                    .uid(uid).orderId(orderIdCounter++)
                                    .action(action).size(1).price(50_000L).reservePrice(50_000L)
                                    .symbol(symbolId).orderType(OrderType.GTC)
                                    .marginMode(MarginMode.ISOLATED).leverage(200)
                                    .build()).join();

                    // 拒单 2: NSF（巨量下单，deposit 1000 远不够开仓）
                    container.getApi().submitCommandAsync(
                            ApiPlaceOrder.builder()
                                    .uid(uid).orderId(orderIdCounter++)
                                    .action(action).size(10_000L).price(50_000L).reservePrice(50_000L)
                                    .symbol(symbolId).orderType(OrderType.GTC)
                                    .marginMode(MarginMode.ISOLATED).leverage(5)
                                    .build()).join();

                    // 拒单 3: reduce-only 无仓位（→ SUCCESS no-op，但不应在 map 留 record）
                    container.getApi().submitCommandAsync(
                            ApiPlaceOrder.builder()
                                    .uid(uid).orderId(orderIdCounter++)
                                    .action(action).size(1).price(50_000L).reservePrice(50_000L)
                                    .symbol(symbolId).orderType(OrderType.GTC)
                                    .marginMode(MarginMode.ISOLATED).reduceOnly(true)
                                    .build()).join();
                }
                if (round % 10 == 0) {
                    assertConserved(container, "round " + round, round, seed);
                }
            }

            // 最终断言：所有 user 状态干净。
            assertConserved(container, "after all rejected orders", numRounds, seed);
            for (long uid : uids) {
                container.validateUserState(uid, profile -> {
                    assertTrue(profile.getAccounts().get(CURRENECY_USD) == initialDeposit,
                            "uid " + uid + " accounts changed by rejected orders");
                    assertTrue(profile.getExchangeLocked().get(CURRENECY_USD) == 0L,
                            "uid " + uid + " exchangeLocked polluted by rejected orders");
                    assertTrue(profile.getPositions().get(symbolId) == null,
                            "uid " + uid + " positions polluted by rejected orders");
                });
            }
            log.info("fuzzFailedPlaceOrders seed={} 全 {} 次失败下单后守恒成立、positions 全 null", seed,
                    numUsers * numRounds * 3);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 下面 3 个 fuzz 专门暴露 audit 报告 M1-M4（dust 级守恒漂移）。
    // 关键差异：使用 baseScaleK × quoteScaleK > currencyScaleK 的 scale 错配 spec，
    // 以及动态费率（feeScaleK > 0），让 sizePriceToCurrencyScale 真的发生 floor 截断。
    // 已知 audit 报告里 initFutureSymbols 默认是 baseScaleK=quoteScaleK=1，
    // currencyScaleK=1 → convertScale 不截断，所以现有 fuzz 走不到这些路径。
    // ─────────────────────────────────────────────────────────────────

    /** Scale 错配的期货合约 spec，sizePrice → currency 必发生 floor 截断。 */
    private CoreSymbolSpecification scaleMismatchedFutures(int symbolId, int baseCurrencyId, int quoteCurrencyId) {
        // baseScaleK × quoteScaleK = 10000，currencyScaleK = 1 → convertScale 除 10000
        return CoreSymbolSpecification.builder()
                .symbolId(symbolId)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .baseCurrency(baseCurrencyId).baseScaleK(100)
                .quoteCurrency(quoteCurrencyId).quoteScaleK(100)
                .takerFee(1).makerFee(1).feeScaleK(100)
                .initMargin(1).initMarginScaleK(100)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100_000_000L, 10L))
                .maintenanceMarginScaleK(1000)
                .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100_000_000L, 10L))
                .build();
    }

    /** M1: 多轮部分平仓 + 全平 → settlePnl / removePositionRecord 在 scale 错配下截断 dust。 */
    @Test
    @Timeout(60)
    public void fuzzM1FuturesScaleMismatchSettle() {
        long seed = Long.parseLong(System.getProperty("fuzz.m1.seed", "20260622"));
        final Random rng = new Random(seed);

        final int baseCurrencyId = 900;
        final int quoteCurrencyId = CURRENECY_USD;
        final int symbolId = 70001;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT, processor)) {
            container.addCurrency(new CoreCurrencySpecification(baseCurrencyId, "BASE", 0));
            container.addCurrency(new CoreCurrencySpecification(quoteCurrencyId, "USD", 0));
            CoreSymbolSpecification spec = scaleMismatchedFutures(symbolId, baseCurrencyId, quoteCurrencyId);
            container.addSymbol(spec);
            container.initMarkPrice(spec.symbolId, 50_000L);

            final int numUsers = 6;
            final long[] uids = new long[numUsers];
            for (int i = 0; i < numUsers; i++) {
                uids[i] = 700_000L + i;
                container.createUserWithSpecificMoney(uids[i], 100_000_000L, quoteCurrencyId);
            }
            assertConserved(container, "M1 after deposit", -1L, seed);

            long markPrice = 50_000L;
            long nextOrderId = 8_000_000L;
            final int numCycles = 20;

            for (int cycle = 0; cycle < numCycles; cycle++) {
                // 一对一 BID/ASK 开仓
                int longU = rng.nextInt(numUsers);
                int shortU = (longU + 1 + rng.nextInt(numUsers - 1)) % numUsers;
                long size = 5L + rng.nextInt(20);

                container.getApi().submitCommandAsync(ApiPlaceOrder.builder()
                        .uid(uids[longU]).orderId(nextOrderId++)
                        .price(markPrice).reservePrice(markPrice).size(size)
                        .action(OrderAction.BID).orderType(OrderType.GTC)
                        .symbol(symbolId).marginMode(MarginMode.CROSS).build()).join();
                container.getApi().submitCommandAsync(ApiPlaceOrder.builder()
                        .uid(uids[shortU]).orderId(nextOrderId++)
                        .price(markPrice).size(size)
                        .action(OrderAction.ASK).orderType(OrderType.GTC)
                        .symbol(symbolId).marginMode(MarginMode.CROSS).build()).join();
                assertConserved(container, "M1 cycle " + cycle + " after open", cycle, seed);

                // 价格抖动（让 PnL 不为 0，且非整除）
                long newPrice = Math.max(1L, markPrice + (rng.nextInt(7777) - 3888));
                container.updateCurrentPriceTo((int) newPrice, symbolId, quoteCurrencyId);
                markPrice = newPrice;

                // 部分平仓（按 size 的 1/3）
                long partial = Math.max(1L, size / 3);
                container.getApi().submitCommandAsync(ApiPlaceOrder.builder()
                        .uid(uids[longU]).orderId(nextOrderId++)
                        .price(markPrice).size(partial)
                        .action(OrderAction.ASK).orderType(OrderType.GTC)
                        .symbol(symbolId).marginMode(MarginMode.CROSS).build()).join();
                container.getApi().submitCommandAsync(ApiPlaceOrder.builder()
                        .uid(uids[shortU]).orderId(nextOrderId++)
                        .price(markPrice).reservePrice(markPrice).size(partial)
                        .action(OrderAction.BID).orderType(OrderType.GTC)
                        .symbol(symbolId).marginMode(MarginMode.CROSS).build()).join();
                assertConserved(container, "M1 cycle " + cycle + " after partial", cycle, seed);

                // 全平 → 触发 removePositionRecord 路径
                long remaining = size - partial;
                container.getApi().submitCommandAsync(ApiPlaceOrder.builder()
                        .uid(uids[longU]).orderId(nextOrderId++)
                        .price(markPrice).size(remaining)
                        .action(OrderAction.ASK).orderType(OrderType.GTC)
                        .symbol(symbolId).marginMode(MarginMode.CROSS).build()).join();
                container.getApi().submitCommandAsync(ApiPlaceOrder.builder()
                        .uid(uids[shortU]).orderId(nextOrderId++)
                        .price(markPrice).reservePrice(markPrice).size(remaining)
                        .action(OrderAction.BID).orderType(OrderType.GTC)
                        .symbol(symbolId).marginMode(MarginMode.CROSS).build()).join();
                assertConserved(container, "M1 cycle " + cycle + " after full close", cycle, seed);
            }
            log.info("M1 fuzz seed={} cycles={} —— scale 错配期货部分平仓 + 全平守恒",
                    seed, numCycles);
        }
    }

    /** M2: 小 funding rate + scale 错配 → 接收方 scaledFee 截断到 0，付款方仍扣 dust。 */
    @Test
    @Timeout(60)
    public void fuzzM2FundingSmallRate() {
        long seed = Long.parseLong(System.getProperty("fuzz.m2.seed", "20260622"));
        final Random rng = new Random(seed);

        final int baseCurrencyId = 901;
        final int quoteCurrencyId = CURRENECY_USD;
        final int symbolId = 70101;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT, processor)) {
            container.addCurrency(new CoreCurrencySpecification(baseCurrencyId, "BASE", 0));
            container.addCurrency(new CoreCurrencySpecification(quoteCurrencyId, "USD", 0));
            CoreSymbolSpecification spec = scaleMismatchedFutures(symbolId, baseCurrencyId, quoteCurrencyId);
            container.addSymbol(spec);
            container.initMarkPrice(spec.symbolId, 50_000L);

            final int numUsers = 4;
            final long[] uids = new long[numUsers];
            for (int i = 0; i < numUsers; i++) {
                uids[i] = 710_000L + i;
                container.createUserWithSpecificMoney(uids[i], 100_000_000L, quoteCurrencyId);
            }

            // 建初始持仓（两方对等开多空，让 funding 有双方收付）
            long nextOrderId = 8_100_000L;
            long markPrice = 50_000L;
            for (int pair = 0; pair < numUsers / 2; pair++) {
                int longU = pair * 2;
                int shortU = pair * 2 + 1;
                long size = 2L + rng.nextInt(5);
                container.getApi().submitCommandAsync(ApiPlaceOrder.builder()
                        .uid(uids[longU]).orderId(nextOrderId++)
                        .price(markPrice).reservePrice(markPrice).size(size)
                        .action(OrderAction.BID).orderType(OrderType.GTC)
                        .symbol(symbolId).marginMode(MarginMode.CROSS).build()).join();
                container.getApi().submitCommandAsync(ApiPlaceOrder.builder()
                        .uid(uids[shortU]).orderId(nextOrderId++)
                        .price(markPrice).size(size)
                        .action(OrderAction.ASK).orderType(OrderType.GTC)
                        .symbol(symbolId).marginMode(MarginMode.CROSS).build()).join();
            }
            assertConserved(container, "M2 after init positions", -1L, seed);

            // 多轮 funding，故意挑小 rate / 大 rateScaleK 让 scaledFee 截断到 0
            final int numFunding = 30;
            for (int i = 0; i < numFunding; i++) {
                // fundingRate=1, rateScaleK=10000000 → 0.00001% 每周期
                long fundingRate = 1L;
                long rateScaleK = 10_000_000L;
                OrderAction direction = rng.nextBoolean() ? OrderAction.BID : OrderAction.ASK;
                container.getApi().submitCommandAsync(ApiSettleFundingFees.builder()
                        .transactionId(container.getRandomTransactionId())
                        .symbol(symbolId).action(direction)
                        .fundingRate(fundingRate).rateScaleK(rateScaleK)
                        .build()).join();
                assertConserved(container, "M2 funding cycle " + i, i, seed);
            }
            log.info("M2 fuzz seed={} funding cycles={} —— 小费率 funding 守恒", seed, numFunding);
        }
    }

    /** M3 + M4: 现货 + 动态费率 + 跨档/部分成交 → maker ceil-ceil 减法 / taker floor-floor 减法 dust。 */
    @Test
    @Timeout(60)
    public void fuzzM3M4SpotDynamicFee() {
        long seed = Long.parseLong(System.getProperty("fuzz.m3m4.seed", "20260622"));
        final Random rng = new Random(seed);

        final int baseCurrencyId = 902;
        final int quoteCurrencyId = CURRENECY_USD;
        final int symbolId = 70201;

        // 现货动态费率 spec：baseScaleK × quoteScaleK = 10000，currencyScaleK = 1
        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(symbolId)
                .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(baseCurrencyId).baseScaleK(100)
                .quoteCurrency(quoteCurrencyId).quoteScaleK(100)
                .takerFee(100).makerFee(50).feeScaleK(10000)  // 1% / 0.5%
                .build();

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT, processor)) {
            container.addCurrency(new CoreCurrencySpecification(baseCurrencyId, "BASE", 0));
            container.addCurrency(new CoreCurrencySpecification(quoteCurrencyId, "USD", 0));
            container.addSymbol(spec);

            final int numUsers = 6;
            final long[] uids = new long[numUsers];
            for (int i = 0; i < numUsers; i++) {
                uids[i] = 720_000L + i;
                container.createUserWithSpecificMoney(uids[i], 100_000_000L, quoteCurrencyId);
                container.addMoneyToUser(uids[i], baseCurrencyId, 1_000_000L);
            }
            assertConserved(container, "M3M4 after deposit", -1L, seed);

            long nextOrderId = 8_200_000L;
            final int numOps = 200;
            final List<long[]> openOrders = new ArrayList<>();

            for (int step = 0; step < numOps; step++) {
                int op = rng.nextInt(100);
                ApiCommand cmd = null;
                try {
                    if (op < 70) {
                        // 70% 下单：BID/ASK 等概率，价格围绕 100 上下抖
                        int u = rng.nextInt(numUsers);
                        OrderAction action = rng.nextBoolean() ? OrderAction.BID : OrderAction.ASK;
                        long size = 1L + rng.nextInt(7);
                        // 价格 80-130（满足 isAskPriceTooLow 阈值 ceilDiv(10000,100)=100，故 ASK ≥ 100）
                        long price;
                        if (action == OrderAction.ASK) {
                            price = 100L + rng.nextInt(31);
                        } else {
                            price = 80L + rng.nextInt(51);
                        }
                        long orderId = nextOrderId++;
                        ApiPlaceOrder.ApiPlaceOrderBuilder b = ApiPlaceOrder.builder()
                                .uid(uids[u]).orderId(orderId)
                                .price(price).size(size)
                                .action(action).orderType(OrderType.GTC)
                                .symbol(symbolId);
                        if (action == OrderAction.BID) b.reservePrice(price);
                        cmd = b.build();
                        openOrders.add(new long[]{u, orderId});
                    } else if (op < 90 && !openOrders.isEmpty()) {
                        // 20% 撤单
                        int idx = rng.nextInt(openOrders.size());
                        long[] od = openOrders.remove(idx);
                        cmd = ApiCancelOrder.builder()
                                .uid(uids[(int) od[0]]).orderId(od[1]).symbol(symbolId)
                                .build();
                    }
                    if (cmd != null) {
                        container.getApi().submitCommandAsync(cmd).join();
                    }
                } catch (Exception e) {
                    log.debug("M3M4 step {} cmd {} threw: {}", step, cmd, e.toString());
                }
                assertConserved(container, "M3M4 step " + step + " op=" + op, step, seed);
            }
            log.info("M3M4 fuzz seed={} ops={} —— 现货动态费率守恒", seed, numOps);
        }
    }
}
