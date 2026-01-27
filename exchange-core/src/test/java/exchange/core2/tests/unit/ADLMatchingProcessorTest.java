package exchange.core2.tests.unit;

import exchange.core2.core.common.*;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.orderbook.OrderBookEventsHelper;
import exchange.core2.core.processors.liquidation.ADLMatchingProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADLMatchingProcessor 单元测试
 * 测试 ADL 撮合处理器的候选人合并和事件生成
 */
class ADLMatchingProcessorTest {

    private ADLMatchingProcessor processor;
    private OrderBookEventsHelper eventsHelper;

    @BeforeEach
    void setUp() {
        eventsHelper = new OrderBookEventsHelper(() -> new MatcherTradeEvent());
        processor = new ADLMatchingProcessor(eventsHelper);
    }

    // ========== 基本功能测试 ==========

    @Test
    void testProcess_SingleShardSingleCandidate() {
        // 测试单分片单候选人
        OrderCommand cmd = createADLCommand(10001, OrderAction.ASK, 10, 1000);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        ADLUserPosition candidate = createCandidate(100, 10, 1000);
        cmd.adlUserPositionsByShard = new ADLUserPosition[]{candidate};

        CommandResultCode result = processor.process(cmd);

        assertEquals(CommandResultCode.SUCCESS, result);
        assertNotNull(cmd.matcherEvent);
        assertEquals(MatcherEventType.ADL_EVENT, cmd.matcherEvent.eventType);
        assertEquals(10L, cmd.matcherEvent.size);
        assertEquals(100L, cmd.matcherEvent.matchedOrderUid);
        assertNull(cmd.matcherEvent.nextEvent); // 只有一个事件
    }

    @Test
    void testProcess_SingleShardMultipleCandidates() {
        // 测试单分片多候选人
        OrderCommand cmd = createADLCommand(10001, OrderAction.ASK, 30, 1000);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // 3个候选人，按score降序排列
        ADLUserPosition candidate1 = createCandidate(100, 10, 3000);
        ADLUserPosition candidate2 = createCandidate(200, 15, 2000);
        ADLUserPosition candidate3 = createCandidate(300, 20, 1000);
        candidate1.next = candidate2;
        candidate2.next = candidate3;

        cmd.adlUserPositionsByShard = new ADLUserPosition[]{candidate1};

        processor.process(cmd);

        // 验证事件链
        MatcherTradeEvent event = cmd.matcherEvent;
        assertNotNull(event);

        // Event 1: uid=100, size=10, score=3000
        assertEquals(MatcherEventType.ADL_EVENT, event.eventType);
        assertEquals(100L, event.matchedOrderUid);
        assertEquals(10L, event.size);

        // Event 2: uid=200, size=15, score=2000
        event = event.nextEvent;
        assertNotNull(event);
        assertEquals(200L, event.matchedOrderUid);
        assertEquals(15L, event.size);

        // Event 3: uid=300, size=5 (只需要5，总共30)
        event = event.nextEvent;
        assertNotNull(event);
        assertEquals(300L, event.matchedOrderUid);
        assertEquals(5L, event.size);

        assertNull(event.nextEvent); // 没有更多事件
    }

    @Test
    void testProcess_MultiShardMerge() {
        // 测试多分片归并排序
        OrderCommand cmd = createADLCommand(10001, OrderAction.ASK, 50, 1000);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // Shard 0: 两个候选人
        ADLUserPosition shard0_1 = createCandidate(100, 10, 5000);
        ADLUserPosition shard0_2 = createCandidate(101, 15, 2000);
        shard0_1.next = shard0_2;

        // Shard 1: 两个候选人
        ADLUserPosition shard1_1 = createCandidate(200, 20, 4000);
        ADLUserPosition shard1_2 = createCandidate(201, 10, 1000);
        shard1_1.next = shard1_2;

        // Shard 2: 一个候选人
        ADLUserPosition shard2_1 = createCandidate(300, 25, 3000);

        cmd.adlUserPositionsByShard = new ADLUserPosition[]{shard0_1, shard1_1, shard2_1};

        processor.process(cmd);

        // 验证归并排序结果：score降序
        // 预期顺序: 100(5000) -> 200(4000) -> 300(3000) -> 101(2000) -> 201(1000)
        MatcherTradeEvent event = cmd.matcherEvent;
        
        assertEquals(100L, event.matchedOrderUid);
        assertEquals(10L, event.size);

        event = event.nextEvent;
        assertEquals(200L, event.matchedOrderUid);
        assertEquals(20L, event.size);

        event = event.nextEvent;
        assertEquals(300L, event.matchedOrderUid);
        assertEquals(20L, event.size); // 只需要20，总共50

        assertNull(event.nextEvent);
    }

    @Test
    void testProcess_PartialCandidateConsumption() {
        // 测试候选人部分消费
        OrderCommand cmd = createADLCommand(10001, OrderAction.ASK, 5, 1000);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        ADLUserPosition candidate = createCandidate(100, 10, 1000); // volume=10, 但只需要5
        cmd.adlUserPositionsByShard = new ADLUserPosition[]{candidate};

        processor.process(cmd);

        MatcherTradeEvent event = cmd.matcherEvent;
        assertEquals(5L, event.size);
        assertEquals(5L, cmd.size); // cmd.size 应该更新为实际执行量
    }

    // ========== 边界条件测试 ==========

    @Test
    void testProcess_NoSize() {
        // 测试size=0
        OrderCommand cmd = createADLCommand(10001, OrderAction.ASK, 0, 1000);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        processor.process(cmd);

        assertEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);
    }

    @Test
    void testProcess_NullCandidates() {
        // 测试无候选人
        OrderCommand cmd = createADLCommand(10001, OrderAction.ASK, 10, 1000);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.adlUserPositionsByShard = null;

        processor.process(cmd);

        assertEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);
    }

    @Test
    void testProcess_EmptyCandidates() {
        // 测试空候选人数组
        OrderCommand cmd = createADLCommand(10001, OrderAction.ASK, 10, 1000);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.adlUserPositionsByShard = new ADLUserPosition[0];

        processor.process(cmd);

        assertEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);
    }

    @Test
    void testProcess_AllShardsEmpty() {
        // 测试所有分片候选人都为null
        OrderCommand cmd = createADLCommand(10001, OrderAction.ASK, 10, 1000);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.adlUserPositionsByShard = new ADLUserPosition[]{null, null, null};

        processor.process(cmd);

        assertEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);
    }

    @Test
    void testProcess_InsufficientCandidates() {
        // 测试候选人不足
        OrderCommand cmd = createADLCommand(10001, OrderAction.ASK, 100, 1000);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        ADLUserPosition candidate1 = createCandidate(100, 10, 1000);
        ADLUserPosition candidate2 = createCandidate(200, 15, 900);
        candidate1.next = candidate2;

        cmd.adlUserPositionsByShard = new ADLUserPosition[]{candidate1};

        processor.process(cmd);

        // 应该消费所有候选人
        MatcherTradeEvent event = cmd.matcherEvent;
        assertNotNull(event);
        assertEquals(10L, event.size);

        event = event.nextEvent;
        assertNotNull(event);
        assertEquals(15L, event.size);

        assertNull(event.nextEvent);

        // cmd.size 应该更新为实际执行量
        assertEquals(25L, cmd.size); // 100 - 75 (remaining)
    }

    @Test
    void testProcess_InvalidResultCode() {
        // 测试无效的resultCode
        OrderCommand cmd = createADLCommand(10001, OrderAction.ASK, 10, 1000);
        cmd.resultCode = CommandResultCode.AUTH_INVALID_USER;

        ADLUserPosition candidate = createCandidate(100, 10, 1000);
        cmd.adlUserPositionsByShard = new ADLUserPosition[]{candidate};

        CommandResultCode result = processor.process(cmd);

        assertEquals(CommandResultCode.AUTH_INVALID_USER, result);
        // matcherEvent 不应该被设置
    }

    // ========== 多分片复杂场景测试 ==========

    @Test
    void testProcess_MultiShardUnbalanced() {
        // 测试分片不均衡（某些分片无候选人）
        OrderCommand cmd = createADLCommand(10001, OrderAction.ASK, 30, 1000);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // Shard 0: 有候选人
        ADLUserPosition shard0_1 = createCandidate(100, 20, 3000);

        // Shard 1: 无候选人
        // Shard 2: 有候选人
        ADLUserPosition shard2_1 = createCandidate(300, 15, 2000);

        cmd.adlUserPositionsByShard = new ADLUserPosition[]{shard0_1, null, shard2_1};

        processor.process(cmd);

        // 验证跳过null分片
        MatcherTradeEvent event = cmd.matcherEvent;
        assertEquals(100L, event.matchedOrderUid);
        assertEquals(20L, event.size);

        event = event.nextEvent;
        assertEquals(300L, event.matchedOrderUid);
        assertEquals(10L, event.size);

        assertNull(event.nextEvent);
    }

    @Test
    void testProcess_ScoreTieBreaking() {
        // 测试相同score的候选人处理（应该按分片顺序）
        OrderCommand cmd = createADLCommand(10001, OrderAction.ASK, 30, 1000);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        ADLUserPosition shard0 = createCandidate(100, 10, 1000);
        ADLUserPosition shard1 = createCandidate(200, 10, 1000); // 相同score
        ADLUserPosition shard2 = createCandidate(300, 10, 1000); // 相同score

        cmd.adlUserPositionsByShard = new ADLUserPosition[]{shard0, shard1, shard2};

        processor.process(cmd);

        // 相同score时，先遇到的先处理
        MatcherTradeEvent event = cmd.matcherEvent;
        assertEquals(100L, event.matchedOrderUid); // shard 0

        event = event.nextEvent;
        assertEquals(200L, event.matchedOrderUid); // shard 1

        event = event.nextEvent;
        assertEquals(300L, event.matchedOrderUid); // shard 2
    }

    // ========== 辅助方法 ==========

    private OrderCommand createADLCommand(int symbol, OrderAction action, long size, long price) {
        OrderCommand cmd = new OrderCommand();
        cmd.command = OrderCommandType.AUTO_DELEVERAGING;
        cmd.symbol = symbol;
        cmd.action = action;
        cmd.size = size;
        cmd.price = price;
        return cmd;
    }

    private ADLUserPosition createCandidate(long uid, long volume, long score) {
        ADLUserPosition candidate = new ADLUserPosition();
        candidate.uid = uid;
        candidate.volume = volume;
        candidate.score = score;
        candidate.direction = PositionDirection.LONG;
        return candidate;
    }
}
