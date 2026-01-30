package exchange.core2.tests.unit;

import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.orderbook.OrderBookEventsHelper;
import exchange.core2.core.processors.liquidation.IFSettlementProcessor;
import exchange.core2.core.processors.liquidation.LiquidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IF多分片协同测试
 * 测试多个分片的IF资金池协同工作场景
 */
class IFMultiShardTest {

    private static final int SYMBOL = 10001;
    private IFSettlementProcessor processor;
    private OrderBookEventsHelper eventsHelper;

    @BeforeEach
    void setUp() {
        eventsHelper = new OrderBookEventsHelper(() -> new MatcherTradeEvent());
        processor = new IFSettlementProcessor(eventsHelper);
    }

    // ========== 多分片均衡分配测试 ==========

    @Test
    void testMultiShard_EvenDistribution_4Shards() {
        // 测试4分片均衡分配：每个分片能力相等
        OrderCommand cmd = createIFCommand(SYMBOL, 40, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // 4个分片，每个有1000资金，可接管10张
        cmd.ifPreviewCoverByShard = new long[]{1000, 1000, 1000, 1000};

        processor.process(cmd);

        // 验证每个分片均衡接管
        MatcherTradeEvent event = cmd.matcherEvent;
        assertNotNull(event);

        for (int i = 0; i < 4; i++) {
            assertEquals(MatcherEventType.IF_EVENT, event.eventType);
            assertEquals(10L, event.size, "Shard " + i + " 应该接管10张");
            assertEquals(i, event.matchedOrderUid, "应该是shard " + i);

            if (i < 3) {
                event = event.nextEvent;
                assertNotNull(event, "应该还有下一个事件");
            }
        }

        assertNull(event.nextEvent, "不应该有更多事件");
    }

    @Test
    void testMultiShard_EvenDistribution_8Shards() {
        // 测试8分片均衡分配
        OrderCommand cmd = createIFCommand(SYMBOL, 80, 50);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // 8个分片，每个有500资金，可接管10张
        cmd.ifPreviewCoverByShard = new long[]{500, 500, 500, 500, 500, 500, 500, 500};

        processor.process(cmd);

        // 验证8个分片都参与
        MatcherTradeEvent event = cmd.matcherEvent;
        int eventCount = 0;
        long totalSize = 0;

        while (event != null) {
            assertEquals(MatcherEventType.IF_EVENT, event.eventType);
            assertEquals(10L, event.size);
            totalSize += event.size;
            eventCount++;
            event = event.nextEvent;
        }

        assertEquals(8, eventCount, "应该有8个事件");
        assertEquals(80L, totalSize, "总接管量应该是80");
    }

    // ========== 多分片不均衡分配测试 ==========

    @Test
    void testMultiShard_UnevenDistribution() {
        // 测试不均衡分配：分片能力差异大
        OrderCommand cmd = createIFCommand(SYMBOL, 30, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // 分片能力: 20, 5, 3, 2
        cmd.ifPreviewCoverByShard = new long[]{2000, 500, 300, 200};

        processor.process(cmd);

        // 验证按顺序分配
        MatcherTradeEvent event = cmd.matcherEvent;

        // Shard 0: 20张
        assertEquals(20L, event.size);
        assertEquals(0L, event.matchedOrderUid);

        // Shard 1: 5张
        event = event.nextEvent;
        assertEquals(5L, event.size);
        assertEquals(1L, event.matchedOrderUid);

        // Shard 2: 3张
        event = event.nextEvent;
        assertEquals(3L, event.size);
        assertEquals(2L, event.matchedOrderUid);

        // Shard 3: 2张
        event = event.nextEvent;
        assertEquals(2L, event.size);
        assertEquals(3L, event.matchedOrderUid);

        assertNull(event.nextEvent);
    }

    @Test
    void testMultiShard_OneDominantShard() {
        // 测试一个分片占主导地位
        OrderCommand cmd = createIFCommand(SYMBOL, 100, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // Shard 1有绝大部分资金
        cmd.ifPreviewCoverByShard = new long[]{500, 9000, 300, 200};

        processor.process(cmd);

        MatcherTradeEvent event = cmd.matcherEvent;

        // Shard 0: 5张
        assertEquals(5L, event.size);

        // Shard 1: 90张（主导）
        event = event.nextEvent;
        assertEquals(90L, event.size);
        assertEquals(1L, event.matchedOrderUid);

        // Shard 2: 3张
        event = event.nextEvent;
        assertEquals(3L, event.size);

        // Shard 3: 2张
        event = event.nextEvent;
        assertEquals(2L, event.size);

        assertNull(event.nextEvent);
    }

    // ========== 部分分片为0测试 ==========

    @Test
    void testMultiShard_SomeShardsZero() {
        // 测试部分分片资金为0
        OrderCommand cmd = createIFCommand(SYMBOL, 20, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // Shard 1和3为0
        cmd.ifPreviewCoverByShard = new long[]{1000, 0, 1000, 0};

        processor.process(cmd);

        // 验证跳过资金为0的分片
        MatcherTradeEvent event = cmd.matcherEvent;

        assertEquals(10L, event.size);
        assertEquals(0L, event.matchedOrderUid);

        event = event.nextEvent;
        assertEquals(10L, event.size);
        assertEquals(2L, event.matchedOrderUid);  // 跳过了shard 1

        assertNull(event.nextEvent);  // 跳过了shard 3
    }

    @Test
    void testMultiShard_AlternatingZeros() {
        // 测试交替为0的分片
        OrderCommand cmd = createIFCommand(SYMBOL, 20, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // 0, 有, 0, 有, 0, 有
        cmd.ifPreviewCoverByShard = new long[]{0, 800, 0, 700, 0, 500};

        processor.process(cmd);

        MatcherTradeEvent event = cmd.matcherEvent;

        // Shard 1: 8张
        assertEquals(8L, event.size);
        assertEquals(1L, event.matchedOrderUid);

        // Shard 3: 7张
        event = event.nextEvent;
        assertEquals(7L, event.size);
        assertEquals(3L, event.matchedOrderUid);

        // Shard 5: 5张
        event = event.nextEvent;
        assertEquals(5L, event.size);
        assertEquals(5L, event.matchedOrderUid);

        assertNull(event.nextEvent);
    }

    // ========== 边界分片数量测试 ==========

    @Test
    void testMultiShard_TwoShards_MinimumMultiShard() {
        // 测试最小多分片场景（2分片）
        OrderCommand cmd = createIFCommand(SYMBOL, 15, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        cmd.ifPreviewCoverByShard = new long[]{800, 700};

        processor.process(cmd);

        MatcherTradeEvent event = cmd.matcherEvent;
        assertEquals(8L, event.size);

        event = event.nextEvent;
        assertEquals(7L, event.size);

        assertNull(event.nextEvent);
    }

    @Test
    void testMultiShard_16Shards_LargeScale() {
        // 测试大规模分片（16分片）
        OrderCommand cmd = createIFCommand(SYMBOL, 160, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // 16个分片，每个1000资金
        long[] reserved = new long[16];
        Arrays.fill(reserved, 1000);
        cmd.ifPreviewCoverByShard = reserved;

        processor.process(cmd);

        // 验证所有分片都参与
        MatcherTradeEvent event = cmd.matcherEvent;
        int count = 0;
        long totalSize = 0;

        while (event != null) {
            assertEquals(10L, event.size);
            assertEquals(count, event.matchedOrderUid);
            totalSize += event.size;
            count++;
            event = event.nextEvent;
        }

        assertEquals(16, count);
        assertEquals(160L, totalSize);
    }

    @Test
    void testMultiShard_32Shards_ExtremeScale() {
        // 测试极大规模分片（32分片）
        OrderCommand cmd = createIFCommand(SYMBOL, 100, 50);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // 32个分片，但只需要前20个分片
        long[] reserved = new long[32];
        Arrays.fill(reserved, 250);  // 每个分片可接管5张
        cmd.ifPreviewCoverByShard = reserved;

        processor.process(cmd);

        // 验证只使用前20个分片
        MatcherTradeEvent event = cmd.matcherEvent;
        int count = 0;

        while (event != null) {
            assertEquals(5L, event.size);
            count++;
            event = event.nextEvent;
        }

        assertEquals(20, count, "应该只使用20个分片");
    }

    // ========== 跨分片边界测试 ==========

    @Test
    void testMultiShard_ExactBoundary() {
        // 测试精确边界：总能力恰好等于需求
        OrderCommand cmd = createIFCommand(SYMBOL, 30, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // 总能力恰好30张
        cmd.ifPreviewCoverByShard = new long[]{1000, 1000, 1000};

        processor.process(cmd);

        assertNotEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);

        // 验证精确分配
        long totalSize = 0;
        MatcherTradeEvent event = cmd.matcherEvent;
        while (event != null) {
            totalSize += event.size;
            event = event.nextEvent;
        }
        assertEquals(30L, totalSize);
    }

    @Test
    void testMultiShard_OneLessThanNeeded() {
        // 测试总能力少1张
        OrderCommand cmd = createIFCommand(SYMBOL, 30, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // 总能力29张（少1张）
        cmd.ifPreviewCoverByShard = new long[]{1000, 1000, 900};

        processor.process(cmd);

        assertEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);
    }

    @Test
    void testMultiShard_OneMoreThanNeeded() {
        // 测试总能力多1张
        OrderCommand cmd = createIFCommand(SYMBOL, 30, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // 总能力31张（多1张）
        cmd.ifPreviewCoverByShard = new long[]{1000, 1000, 1100};

        processor.process(cmd);

        assertNotEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);

        // 验证只取30张
        long totalSize = 0;
        MatcherTradeEvent event = cmd.matcherEvent;
        while (event != null) {
            totalSize += event.size;
            event = event.nextEvent;
        }
        assertEquals(30L, totalSize);
    }

    // ========== 名义价值碎片多分片测试 ==========

    @Test
    void testMultiShard_FragmentationAcrossShards() {
        // 测试多分片碎片问题
        OrderCommand cmd = createIFCommand(SYMBOL, 20, 101);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // 每个分片都有碎片
        // 550/101=5, 520/101=5, 530/101=5, 510/101=5
        cmd.ifPreviewCoverByShard = new long[]{550, 520, 530, 510};

        processor.process(cmd);

        // 总共20张，恰好满足
        MatcherTradeEvent event = cmd.matcherEvent;
        long totalSize = 0;

        while (event != null) {
            totalSize += event.size;
            event = event.nextEvent;
        }

        assertEquals(20L, totalSize);
    }

    @Test
    void testMultiShard_FragmentationCausesFailure() {
        // 测试碎片导致失败
        OrderCommand cmd = createIFCommand(SYMBOL, 20, 101);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // 名义价值总和够：550+520+530+400=2000 > 20*101=2020
        // 但实际：5+5+5+3=18 < 20
        cmd.ifPreviewCoverByShard = new long[]{550, 520, 530, 400};

        processor.process(cmd);

        assertEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);
    }

    @Test
    void testMultiShard_LargeFragments() {
        // 测试大碎片累积
        OrderCommand cmd = createIFCommand(SYMBOL, 10, 999);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // 每个分片碎片998，累计3992碎片，损失近4张
        // 1997/999=1, 总共4张 < 10张
        cmd.ifPreviewCoverByShard = new long[]{1997, 1997, 1997, 1997};

        processor.process(cmd);

        assertEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);
    }

    // ========== 并发多分片测试 ==========

    @Test
    void testMultiShard_ConcurrentReservation() throws InterruptedException {
        // 测试多线程并发预留多分片IF资金
        int numShards = 4;
        LiquidationService[] services = new LiquidationService[numShards];
        for (int i = 0; i < numShards; i++) {
            services[i] = new LiquidationService();
            services[i].creditLiquidationFee(SYMBOL, 10000);
        }

        int threadCount = 10;
        int reservePerThread = 10; // 每线程每分片预留10张
        long price = 100;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicLong totalReserved = new AtomicLong(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // 等待统一启动

                    long threadReserved = 0;
                    for (int shard = 0; shard < numShards; shard++) {
                        long reserved = services[shard].reserveIFNotional(
                                SYMBOL, reservePerThread, price);
                        threadReserved += reserved;
                    }

                    totalReserved.addAndGet(threadReserved);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // 统一启动
        assertTrue(endLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // 验证总预留不超过总可用（4个分片 × 10000 = 40000）
        // 每线程尝试预留: 10张×100×4分片 = 4000
        // 10线程×4000 = 40000 (理论值，但由于并发竞争，实际≤40000)
        long totalReservedValue = totalReserved.get();
        assertTrue(totalReservedValue <= 40000L,
                "总预留(" + totalReservedValue + ")不应超过总可用(40000)");
        // 验证大部分资金被成功预留（至少80%）
        assertTrue(totalReservedValue >= 32000L,
                "并发预留应该成功预留大部分资金，实际=" + totalReservedValue);
    }

    // ========== 分片释放测试 ==========

    @Test
    void testMultiShard_ReleaseAcrossShards() {
        // 测试跨分片释放
        LiquidationService[] services = new LiquidationService[3];
        for (int i = 0; i < 3; i++) {
            services[i] = new LiquidationService();
            services[i].creditLiquidationFee(SYMBOL, 5000);
        }

        // 各分片预留
        long reserved0 = services[0].reserveIFNotional(SYMBOL, 10, 100);
        long reserved1 = services[1].reserveIFNotional(SYMBOL, 10, 100);
        long reserved2 = services[2].reserveIFNotional(SYMBOL, 10, 100);

        assertEquals(1000L, reserved0);
        assertEquals(1000L, reserved1);
        assertEquals(1000L, reserved2);

        // 各分片释放
        services[0].releaseReservedIFNotional(SYMBOL, reserved0);
        services[1].releaseReservedIFNotional(SYMBOL, reserved1);
        services[2].releaseReservedIFNotional(SYMBOL, reserved2);

        // 验证可以再次预留
        long reserved0_2 = services[0].reserveIFNotional(SYMBOL, 10, 100);
        long reserved1_2 = services[1].reserveIFNotional(SYMBOL, 10, 100);
        long reserved2_2 = services[2].reserveIFNotional(SYMBOL, 10, 100);

        assertEquals(1000L, reserved0_2);
        assertEquals(1000L, reserved1_2);
        assertEquals(1000L, reserved2_2);
    }

    // ========== 多symbol多分片测试 ==========

    @Test
    void testMultiShard_MultiSymbol() {
        // 测试多symbol场景，每个symbol独立跨分片管理
        int symbol1 = 10001;
        int symbol2 = 10002;

        LiquidationService[] services = new LiquidationService[2];
        for (int i = 0; i < 2; i++) {
            services[i] = new LiquidationService();
            services[i].creditLiquidationFee(symbol1, 5000);
            services[i].creditLiquidationFee(symbol2, 3000);
        }

        // Symbol1 预留
        long s1_shard0 = services[0].reserveIFNotional(symbol1, 20, 100);
        long s1_shard1 = services[1].reserveIFNotional(symbol1, 20, 100);

        // Symbol2 预留 - 第一次预留30张，用完所有资金
        long s2_shard0 = services[0].reserveIFNotional(symbol2, 30, 100);
        long s2_shard1 = services[1].reserveIFNotional(symbol2, 30, 100);

        // 验证各symbol独立管理
        assertEquals(2000L, s1_shard0);
        assertEquals(2000L, s1_shard1);
        assertEquals(3000L, s2_shard0); // 30张 × 100 = 3000，全部预留
        assertEquals(3000L, s2_shard1);

        // Symbol1 还能预留（5000-2000=3000剩余）
        long s1_shard0_2 = services[0].reserveIFNotional(symbol1, 10, 100);
        assertEquals(1000L, s1_shard0_2);

        // Symbol2 资金耗尽（3000已全部预留）
        long s2_shard0_2 = services[0].reserveIFNotional(symbol2, 10, 100);
        assertEquals(0L, s2_shard0_2);

        // 验证Symbol1和Symbol2独立管理，互不影响
        // Symbol1在shard0还有2000可用（5000-2000-1000=2000）
        long s1_shard0_3 = services[0].reserveIFNotional(symbol1, 20, 100);
        assertEquals(2000L, s1_shard0_3);

        // Symbol2依然无可用资金
        long s2_shard0_3 = services[0].reserveIFNotional(symbol2, 1, 100);
        assertEquals(0L, s2_shard0_3);
    }

    // ========== 辅助方法 ==========

    private OrderCommand createIFCommand(int symbol, long size, long price) {
        OrderCommand cmd = new OrderCommand();
        cmd.command = OrderCommandType.IF_TAKEOVER;
        cmd.symbol = symbol;
        cmd.action = OrderAction.ASK;
        cmd.size = size;
        cmd.price = price;
        return cmd;
    }
}
