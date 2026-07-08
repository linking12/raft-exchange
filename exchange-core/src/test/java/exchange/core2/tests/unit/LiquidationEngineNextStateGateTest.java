package exchange.core2.tests.unit;

import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.liquidation.LiquidationCmdPublisher;
import exchange.core2.core.processors.liquidation.LiquidationContext;
import exchange.core2.core.processors.liquidation.LiquidationContext.LiquidationState;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 {@link LiquidationEngine#nextLiquidationState} 的 gate 行为 + FORCE bootstrap 字段对齐 +
 * {@link LiquidationEngine#start()} fail-fast。
 *
 * <p>这些不变量在 raft 复制路径上 follower 收到 cmd 时反复触发，单元层守住边界比集成层稳。
 */
class LiquidationEngineNextStateGateTest {

    private static final ExchangeConfiguration TEST_CFG = ExchangeConfiguration.defaultBuilder()
            .performanceCfg(PerformanceConfiguration.baseBuilder().build())
            .build();

    // 非强平类 cmd 的 gate 已上移到 RiskEngine.preProcessCommand 后处理段——本层不再守，
    // 这里只测 nextLiquidationState 本身在合法 cmd 上的行为。

    /** Follower 路径：FORCE 在 null ctx 上 bootstrap，price/size 从 cmd 拷贝。REJECT 让 ctx 留在 WAIT_IF 便于断言。 */
    @Test
    void forceLiquidation_bootstrapCtxOnNull_copiesPriceAndSize() {
        LiquidationEngine le = new LiquidationEngine(null, 0, TEST_CFG);
        List<exchange.core2.core.common.api.ApiCommand> published = new ArrayList<>();
        le.setLiquidationCmdPublisher((cmd, onApplied) -> published.add(cmd));

        SymbolPositionRecord pos = newPosition();
        OrderCommand forceLiquidationCmd = newCmd(OrderCommandType.FORCE_LIQUIDATION, 12345L, 67L);
        forceLiquidationCmd.matcherEvent = newMatcherEvent(
                exchange.core2.core.common.MatcherEventType.REJECT, 60L);

        le.nextLiquidationState(forceLiquidationCmd, pos);

        assertEquals(12345L, pos.liquidationCtx.price, "ctx.price 应从 cmd.price 取");
        // ctx.size 在 REJECT 路径上被 onMarketDone 改成 firstEvent.size（matcher 剩余量）
        assertEquals(60L, pos.liquidationCtx.size, "REJECT 后 ctx.size = firstEvent.size");
        assertSame(LiquidationState.WAIT_IF_EXECUTION, pos.liquidationCtx.state, "REJECT → WAIT_IF_EXECUTION");
    }

    /** IF on null ctx 是非法跳跃：apply path 直接 skip，ctx 保持 null，不 publish。 */
    @Test
    void ifTakeoverCmd_onNullCtx_isIllegalJump_skipped() {
        LiquidationEngine le = new LiquidationEngine(null, 0, TEST_CFG);
        List<exchange.core2.core.common.api.ApiCommand> published = new ArrayList<>();
        le.setLiquidationCmdPublisher((cmd, onApplied) -> published.add(cmd));

        SymbolPositionRecord pos = newPosition();
        OrderCommand ifCmd = newCmd(OrderCommandType.IF_TAKEOVER, 200L, 5L);
        ifCmd.matcherEvent = newMatcherEvent(
                exchange.core2.core.common.MatcherEventType.REJECT, 5L);

        le.nextLiquidationState(ifCmd, pos);

        assertNull(pos.liquidationCtx, "IF on null ctx 是非法跳跃，ctx 保持 null");
        assertTrue(published.isEmpty(), "非法跳跃不应触发任何 publish");
    }

    /** start() 在 publisher 未设置时必须 fail-fast，避免 scheduler 跑起来后才在 accept(cmd) 触发 NPE。 */
    @Test
    void start_withoutPublisher_failsFast() {
        LiquidationEngine le = new LiquidationEngine(null, 0, TEST_CFG);
        NullPointerException ex = assertThrows(NullPointerException.class, le::start);
        assertTrue(ex.getMessage().contains("liquidationCmdPublisher"),
                "异常消息应明确指向 liquidationCmdPublisher: " + ex.getMessage());
    }

    /** start() 在 publisher 设置后正常启动；stop 清理 scheduler。 */
    @Test
    void start_withPublisher_doesNotThrow_andStopCleansUp() {
        LiquidationEngine le = new LiquidationEngine(null, 0, TEST_CFG);
        LiquidationCmdPublisher noop = (cmd, onApplied) -> {};
        le.setLiquidationCmdPublisher(noop);

        le.start();
        le.stop();
    }

    // ---------- helpers ----------

    private static SymbolPositionRecord newPosition() {
        SymbolPositionRecord pos = new SymbolPositionRecord();
        pos.uid = 1L;
        pos.symbol = 100001;
        pos.direction = PositionDirection.LONG;
        pos.openVolume = 10;
        return pos;
    }

    private static OrderCommand newCmd(OrderCommandType type, long price, long size) {
        OrderCommand cmd = new OrderCommand(1);
        cmd.command = type;
        cmd.price = price;
        cmd.size = size;
        return cmd;
    }

    private static exchange.core2.core.common.MatcherTradeEvent newMatcherEvent(
            exchange.core2.core.common.MatcherEventType type, long size) {
        exchange.core2.core.common.MatcherTradeEvent e = new exchange.core2.core.common.MatcherTradeEvent();
        e.eventType = type;
        e.size = size;
        return e;
    }
}
