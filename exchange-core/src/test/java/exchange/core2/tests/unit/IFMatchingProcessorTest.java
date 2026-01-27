package exchange.core2.tests.unit;

import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.orderbook.OrderBookEventsHelper;
import exchange.core2.core.processors.liquidation.IFMatchingProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IFMatchingProcessor 单元测试
 * 测试 IF 撮合处理器的资金分配和事件生成
 */
class IFMatchingProcessorTest {

    private IFMatchingProcessor processor;
    private OrderBookEventsHelper eventsHelper;

    @BeforeEach
    void setUp() {
        eventsHelper = new OrderBookEventsHelper(() -> new MatcherTradeEvent());
        processor = new IFMatchingProcessor(eventsHelper);
    }

    // ========== 基本功能测试 ==========

    @Test
    void testProcess_SingleShardSufficientFunds() {
        // 测试单分片充足资金
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 10, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.ifPreviewCoverByShard = new long[]{1000}; // 1000 / 100 = 10

        CommandResultCode result = processor.process(cmd);

        assertEquals(CommandResultCode.SUCCESS, result);
        assertNotNull(cmd.matcherEvent);
        assertEquals(MatcherEventType.IF_EVENT, cmd.matcherEvent.eventType);
        assertEquals(10L, cmd.matcherEvent.size);
        assertEquals(0L, cmd.matcherEvent.matchedOrderUid); // shardId=0
        assertNull(cmd.matcherEvent.nextEvent);
    }

    @Test
    void testProcess_SingleShardInsufficientFunds() {
        // 测试单分片资金不足
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 10, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.ifPreviewCoverByShard = new long[]{500}; // 500 / 100 = 5 < 10

        processor.process(cmd);

        assertEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);
    }

    @Test
    void testProcess_MultiShardSufficientFunds() {
        // 测试多分片充足资金
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 30, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.ifPreviewCoverByShard = new long[]{1000, 1200, 800}; // 10+12+8=30

        processor.process(cmd);

        // 验证事件链
        MatcherTradeEvent event = cmd.matcherEvent;
        assertNotNull(event);

        // Shard 0: 1000 / 100 = 10
        assertEquals(MatcherEventType.IF_EVENT, event.eventType);
        assertEquals(10L, event.size);
        assertEquals(0L, event.matchedOrderUid);

        // Shard 1: 1200 / 100 = 12
        event = event.nextEvent;
        assertNotNull(event);
        assertEquals(12L, event.size);
        assertEquals(1L, event.matchedOrderUid);

        // Shard 2: 800 / 100 = 8
        event = event.nextEvent;
        assertNotNull(event);
        assertEquals(8L, event.size);
        assertEquals(2L, event.matchedOrderUid);

        assertNull(event.nextEvent);
    }

    @Test
    void testProcess_MultiShardPartialAllocation() {
        // 测试多分片部分分配
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 15, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.ifPreviewCoverByShard = new long[]{1000, 1200, 800}; // 总共30，只需要15

        processor.process(cmd);

        MatcherTradeEvent event = cmd.matcherEvent;

        // Shard 0: 取10
        assertEquals(10L, event.size);

        // Shard 1: 取5 (15-10=5)
        event = event.nextEvent;
        assertEquals(5L, event.size);

        assertNull(event.nextEvent); // Shard 2 不需要
    }

    // ========== 名义价值碎片测试 ==========

    @Test
    void testProcess_NotionalFragmentation() {
        // 测试名义价值碎片问题
        // 错误：totalNotional = 1070 → maxSize = 10 ✓
        // 正确：shard0(5) + shard1(5) = 10 ✓
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 10, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.ifPreviewCoverByShard = new long[]{550, 520}; // 550/100=5, 520/100=5

        processor.process(cmd);

        MatcherTradeEvent event = cmd.matcherEvent;
        assertEquals(5L, event.size);

        event = event.nextEvent;
        assertEquals(5L, event.size);

        assertNull(event.nextEvent);
    }

    @Test
    void testProcess_NotionalFragmentation_EdgeCase() {
        // 边界情况：名义价值总和够，但实际size不够
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 10, 101);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.ifPreviewCoverByShard = new long[]{550, 520}; // 550/101=5, 520/101=5, 总共10

        processor.process(cmd);

        // 应该成功（恰好够）
        assertNotEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);
    }

    @Test
    void testProcess_NotionalFragmentation_Fail() {
        // 名义价值碎片导致失败
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 10, 101);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.ifPreviewCoverByShard = new long[]{550, 500}; // 550/101=5, 500/101=4, 总共9<10

        processor.process(cmd);

        assertEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);
    }

    // ========== 边界条件测试 ==========

    @Test
    void testProcess_ZeroSize() {
        // 测试size=0
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 0, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.ifPreviewCoverByShard = new long[]{1000};

        processor.process(cmd);

        assertEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);
    }

    @Test
    void testProcess_ZeroPrice() {
        // 测试price=0
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 10, 0);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.ifPreviewCoverByShard = new long[]{1000};

        processor.process(cmd);

        assertEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);
    }

    @Test
    void testProcess_NullReservedByShard() {
        // 测试null预留数组
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 10, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.ifPreviewCoverByShard = null;

        processor.process(cmd);

        assertEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);
    }

    @Test
    void testProcess_EmptyReservedByShard() {
        // 测试空预留数组
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 10, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.ifPreviewCoverByShard = new long[0];

        processor.process(cmd);

        assertEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);
    }

    @Test
    void testProcess_AllShardsZeroReserved() {
        // 测试所有分片预留为0
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 10, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.ifPreviewCoverByShard = new long[]{0, 0, 0};

        processor.process(cmd);

        assertEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);
    }

    @Test
    void testProcess_InvalidResultCode() {
        // 测试无效的resultCode
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 10, 100);
        cmd.resultCode = CommandResultCode.AUTH_INVALID_USER;
        cmd.ifPreviewCoverByShard = new long[]{1000};

        CommandResultCode result = processor.process(cmd);

        assertEquals(CommandResultCode.AUTH_INVALID_USER, result);
    }

    // ========== 多分片复杂场景测试 ==========

    @Test
    void testProcess_MultiShardUnbalanced() {
        // 测试分片不均衡
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 20, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.ifPreviewCoverByShard = new long[]{0, 1500, 0, 500}; // 只有shard 1和3有资金

        processor.process(cmd);

        MatcherTradeEvent event = cmd.matcherEvent;

        // Shard 0: 跳过
        // Shard 1: 1500 / 100 = 15
        assertEquals(15L, event.size);
        assertEquals(1L, event.matchedOrderUid);

        // Shard 2: 跳过
        // Shard 3: 500 / 100 = 5
        event = event.nextEvent;
        assertEquals(5L, event.size);
        assertEquals(3L, event.matchedOrderUid);

        assertNull(event.nextEvent);
    }

    @Test
    void testProcess_LargeNumberOfShards() {
        // 测试大量分片
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 100, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;

        // 16个分片，每个可接管10张
        long[] reserved = new long[16];
        for (int i = 0; i < 16; i++) {
            reserved[i] = 1000;
        }
        cmd.ifPreviewCoverByShard = reserved;

        processor.process(cmd);

        // 验证只取前10个分片
        MatcherTradeEvent event = cmd.matcherEvent;
        int eventCount = 0;
        while (event != null) {
            eventCount++;
            assertEquals(10L, event.size);
            event = event.nextEvent;
        }
        assertEquals(10, eventCount);
    }

    @Test
    void testProcess_HighPriceScenario() {
        // 测试高价格场景
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 5, 10000);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.ifPreviewCoverByShard = new long[]{30000, 20000}; // 3+2=5

        processor.process(cmd);

        MatcherTradeEvent event = cmd.matcherEvent;
        assertEquals(3L, event.size);

        event = event.nextEvent;
        assertEquals(2L, event.size);
    }

    @Test
    void testProcess_ExactMatch() {
        // 测试精确匹配
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 25, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.ifPreviewCoverByShard = new long[]{1000, 1500}; // 10+15=25

        processor.process(cmd);

        MatcherTradeEvent event = cmd.matcherEvent;
        assertEquals(10L, event.size);

        event = event.nextEvent;
        assertEquals(15L, event.size);

        assertNull(event.nextEvent);
    }

    @Test
    void testProcess_SmallFragments() {
        // 测试小碎片
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 1, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.ifPreviewCoverByShard = new long[]{99, 99, 99}; // 每个都不够1张，总共也不够

        processor.process(cmd);

        assertEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);
    }

    @Test
    void testProcess_SmallFragments_Success() {
        // 测试小碎片成功
        OrderCommand cmd = createIFCommand(10001, OrderAction.ASK, 1, 100);
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        cmd.ifPreviewCoverByShard = new long[]{150}; // 150 / 100 = 1

        processor.process(cmd);

        assertNotEquals(MatcherEventType.REJECT, cmd.matcherEvent.eventType);
        assertEquals(1L, cmd.matcherEvent.size);
    }

    // ========== 辅助方法 ==========

    private OrderCommand createIFCommand(int symbol, OrderAction action, long size, long price) {
        OrderCommand cmd = new OrderCommand();
        cmd.command = OrderCommandType.IF_TAKEOVER;
        cmd.symbol = symbol;
        cmd.action = action;
        cmd.size = size;
        cmd.price = price;
        return cmd;
    }
}
