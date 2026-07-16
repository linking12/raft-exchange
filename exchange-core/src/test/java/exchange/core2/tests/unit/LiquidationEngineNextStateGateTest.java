package exchange.core2.tests.unit;

import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.liquidation.LiquidationCommandSubmitter;
import exchange.core2.core.processors.liquidation.LiquidationFlow.LiquidationState;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 {@link LiquidationEngine#advanceLiquidation} 的 gate 行为 + FORCE bootstrap 字段对齐。
 *
 * <p>这些不变量在 raft 复制路径上 follower 收到 cmd 时反复触发，单元层守住边界比集成层稳。
 */
class LiquidationEngineNextStateGateTest {

    private static final ExchangeConfiguration TEST_CFG = ExchangeConfiguration.defaultBuilder()
            .performanceCfg(PerformanceConfiguration.baseBuilder().build())
            .build();

    private LiquidationEngine le;

    @AfterEach
    void tearDown() {
        if (le != null) {
            le.stop();
        }
    }

    // 非强平类 cmd 的 gate 已上移到 RiskEngine.preProcessCommand 后处理段——本层不再守，
    // 这里只测 advanceLiquidation 本身在合法 cmd 上的行为。

    /** Follower 路径：FORCE 在 null flow 上 bootstrap，price/size 从 cmd 拷贝。REJECT 让 flow 留在 WAIT_IF 便于断言。 */
    @Test
    void forceLiquidation_bootstrapCtxOnNull_copiesPriceAndSize() {
        le = newRunningEngine();
        List<exchange.core2.core.common.api.ApiCommand> published = new ArrayList<>();
        le.setCommandSubmitter((cmd, onApplied) -> published.add(cmd));

        SymbolPositionRecord pos = newPosition();
        OrderCommand forceLiquidationCmd = newCmd(OrderCommandType.FORCE_LIQUIDATION, 12345L, 67L);
        forceLiquidationCmd.matcherEvent = newMatcherEvent(
                exchange.core2.core.common.MatcherEventType.REJECT, 60L);

        le.advanceLiquidation(forceLiquidationCmd, pos);

        assertEquals(12345L, pos.liquidationFlow.bankruptcyPrice, "flow.bankruptcyPrice 应从 cmd.price 取");
        // flow.size 在 REJECT 路径上被 onMarketDone 改成 firstEvent.size（matcher 剩余量）
        assertEquals(60L, pos.liquidationFlow.size, "REJECT 后 flow.size = firstEvent.size");
        assertSame(LiquidationState.WAIT_IF_EXECUTION, pos.liquidationFlow.state, "REJECT → WAIT_IF_EXECUTION");
    }

    /** IF on null flow 是非法跳跃：apply path 直接 skip，flow 保持 null，不 publish。 */
    @Test
    void ifTakeoverCmd_onNullCtx_isIllegalJump_skipped() {
        le = newRunningEngine();
        List<exchange.core2.core.common.api.ApiCommand> published = new ArrayList<>();
        le.setCommandSubmitter((cmd, onApplied) -> published.add(cmd));

        SymbolPositionRecord pos = newPosition();
        OrderCommand ifCmd = newCmd(OrderCommandType.IF_TAKEOVER, 200L, 5L);
        ifCmd.matcherEvent = newMatcherEvent(
                exchange.core2.core.common.MatcherEventType.REJECT, 5L);

        le.advanceLiquidation(ifCmd, pos);

        assertNull(pos.liquidationFlow, "IF on null flow 是非法跳跃，flow 保持 null");
        assertTrue(published.isEmpty(), "非法跳跃不应触发任何 publish");
    }

    /** start() 在 submitter 设置后正常启动；stop 清理 scheduler。 */
    @Test
    void start_withPublisher_doesNotThrow_andStopCleansUp() {
        le = new LiquidationEngine(null, 1, TEST_CFG);
        LiquidationCommandSubmitter noop = (cmd, onApplied) -> {};
        le.setCommandSubmitter(noop);

        le.start();
        le.stop();
    }

    // ---------- helpers ----------

    private LiquidationEngine newRunningEngine() {
        LiquidationEngine engine = new LiquidationEngine(null, 1, TEST_CFG);
        engine.start();
        return engine;
    }

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
