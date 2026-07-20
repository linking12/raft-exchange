package exchange.core2.tests.unit;

import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.liquidation.LiquidationFlow;
import exchange.core2.core.processors.liquidation.LiquidationFlow.LiquidationState;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 untracked publish 路径（onApplied 传 null）不会 NPE，且仍能发出对应命令（如 ADL）。
 */
class LiquidationCallbackBehaviorTest {

    private static final ExchangeConfiguration TEST_CFG = ExchangeConfiguration.defaultBuilder()
            .performanceCfg(PerformanceConfiguration.baseBuilder().build()).build();

    private LiquidationEngine le;

    @AfterEach
    void tearDown() {
        if (le != null) {
            le.stop();
        }
    }

    /** null onApplied（如 notify cmd 走 publishUntracked）不应 NPE 也不影响其他流程。 */
    @Test
    void publishUntracked_withNullOnApplied_doesNotThrow() throws Exception {
        le = newRunningEngine();
        // submitter 收到 null onApplied 时绝不尝试调用
        List<ApiCommand> published = new ArrayList<>();
        le.setCommandSubmitter((cmd, onApplied) -> {
            published.add(cmd);
            // 这里故意不 invoke onApplied——因为 untracked 路径传的就是 null
            if (onApplied != null) {
                onApplied.run();
            }
        });

        // 触发 onIFTakeoverDone REJECT → enterAdlPhase 路径，会有 ADL publish 发出去
        SymbolPositionRecord pos = newPositionWithCtx(LiquidationState.WAIT_IF_EXECUTION);
        OrderCommand cmd = newCmd(OrderCommandType.IF_TAKEOVER, MatcherEventType.REJECT, 2L);
        le.advanceLiquidation(cmd, pos);

        assertEquals(1, published.size(), "应该 publish 一条 ADL cmd");
    }

    // ============== helpers ==============

    private LiquidationEngine newRunningEngine() {
        LiquidationEngine engine = new LiquidationEngine(null, 1, TEST_CFG);
        engine.start();
        return engine;
    }

    private static SymbolPositionRecord newPositionWithCtx(LiquidationState state) {
        SymbolPositionRecord pos = new SymbolPositionRecord();
        pos.uid = 1L;
        pos.symbol = 100001;
        pos.direction = PositionDirection.LONG;
        pos.openVolume = 10;
        pos.marginMode = MarginMode.ISOLATED;
        LiquidationFlow ctx = new LiquidationFlow(100L, 5L, 7777L);
        ctx.state = state;
        pos.liquidationFlow = ctx;
        return pos;
    }

    private static OrderCommand newCmd(OrderCommandType type, MatcherEventType eventType, long firstEventSize) {
        OrderCommand cmd = new OrderCommand(1);
        cmd.command = type;
        cmd.uid = 1L;
        cmd.symbol = 100001;
        cmd.orderId = 12345L;
        cmd.action = OrderAction.ASK;
        cmd.price = 100L;
        cmd.size = firstEventSize;
        MatcherTradeEvent ev = new MatcherTradeEvent();
        ev.eventType = eventType;
        ev.size = firstEventSize;
        cmd.matcherEvent = ev;
        return cmd;
    }
}
