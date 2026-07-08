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
import exchange.core2.core.processors.liquidation.LiquidationCmdPublisher;
import exchange.core2.core.processors.liquidation.LiquidationContext;
import exchange.core2.core.processors.liquidation.LiquidationContext.LiquidationState;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 publisher 的 onApplied 回调在所有路径（成功 / raft 失败 / publisher 同步异常）上都能清掉
 * in-flight 集合，否则集合积累死值，scanner 会永久 skip 该 position。
 */
class LiquidationCallbackBehaviorTest {

    private static final ExchangeConfiguration TEST_CFG = ExchangeConfiguration.defaultBuilder()
            .performanceCfg(PerformanceConfiguration.baseBuilder().build()).build();

    /** publisher 成功路径触发 onApplied → 集合清。 */
    @Test
    void onApplied_invokedOnSuccess_removesPositionFromInFlight() throws Exception {
        LiquidationEngine le = new LiquidationEngine(null, 0, TEST_CFG);
        // 捕获 onApplied 回调但延迟触发，模拟 raft 异步完成
        List<Runnable> pendingCallbacks = new ArrayList<>();
        le.setLiquidationCmdPublisher((cmd, onApplied) -> {
            if (onApplied != null) {
                pendingCallbacks.add(onApplied);
            }
        });

        SymbolPositionRecord pos = newPositionWithCtx(LiquidationState.LIQUIDATING);
        OrderCommand cmd = newCmd(OrderCommandType.FORCE_LIQUIDATION, MatcherEventType.REJECT, 4L);

        // 触发 apply → onMarketDone → publishTracked → 加入 in-flight
        le.nextLiquidationState(cmd, pos);

        Set<SymbolPositionRecord> inFlight = readInFlightSet(le);
        assertTrue(inFlight.contains(pos), "publish 后 position 应在 in-flight 集合内");
        assertEquals(1, pendingCallbacks.size(), "应有一条待回调的 IF cmd");

        // 模拟 raft apply 成功 → callback 触发
        pendingCallbacks.get(0).run();

        assertFalse(inFlight.contains(pos), "成功 onApplied 后 position 应从 in-flight 集合移除");
    }

    /** raft 失败路径：publisher 也必须触发 onApplied（带 null 或非 null 都行），集合清。 */
    @Test
    void onApplied_invokedOnFailure_alsoRemovesPositionFromInFlight() throws Exception {
        LiquidationEngine le = new LiquidationEngine(null, 0, TEST_CFG);
        // 模拟 raft 异常路径：publisher 立刻同步触发 onApplied（生产实现里 err != null 也会触发）
        List<ApiCommand> published = new ArrayList<>();
        le.setLiquidationCmdPublisher((cmd, onApplied) -> {
            published.add(cmd);
            if (onApplied != null) {
                onApplied.run();
            }
        });

        SymbolPositionRecord pos = newPositionWithCtx(LiquidationState.LIQUIDATING);
        OrderCommand cmd = newCmd(OrderCommandType.FORCE_LIQUIDATION, MatcherEventType.REJECT, 4L);

        le.nextLiquidationState(cmd, pos);

        Set<SymbolPositionRecord> inFlight = readInFlightSet(le);
        assertFalse(inFlight.contains(pos),
                "raft 失败 publisher 同步触发 onApplied 后，position 不应停留在 in-flight 集合");
        assertEquals(1, published.size(), "publish 仍发生了，只是 raft 异常");
    }

    /** publisher 同步抛异常：publishTracked 的 catch 必须回滚 in-flight 集合。 */
    @Test
    void publisherSyncThrow_rollsBackInFlight() throws Exception {
        LiquidationEngine le = new LiquidationEngine(null, 0, TEST_CFG);
        RuntimeException injected = new RuntimeException("publisher rejected");
        le.setLiquidationCmdPublisher((cmd, onApplied) -> {
            throw injected;
        });

        SymbolPositionRecord pos = newPositionWithCtx(LiquidationState.LIQUIDATING);
        OrderCommand cmd = newCmd(OrderCommandType.FORCE_LIQUIDATION, MatcherEventType.REJECT, 4L);

        Throwable caught = null;
        try {
            le.nextLiquidationState(cmd, pos);
        } catch (Throwable t) {
            caught = t;
        }
        assertSame(injected, caught, "publisher 抛的异常应原样抛出");

        Set<SymbolPositionRecord> inFlight = readInFlightSet(le);
        assertFalse(inFlight.contains(pos), "publish 同步异常时 in-flight 集合必须回滚，避免死值");
    }

    /** null onApplied（如 notify cmd 走 publishUntracked）不应 NPE 也不影响其他流程。 */
    @Test
    void publishUntracked_withNullOnApplied_doesNotThrow() throws Exception {
        LiquidationEngine le = new LiquidationEngine(null, 0, TEST_CFG);
        // publisher 收到 null onApplied 时绝不尝试调用
        List<ApiCommand> published = new ArrayList<>();
        le.setLiquidationCmdPublisher((cmd, onApplied) -> {
            published.add(cmd);
            // 这里故意不 invoke onApplied——因为 untracked 路径传的就是 null
            if (onApplied != null) {
                onApplied.run();
            }
        });

        // 触发 onMarketDone REJECT + IF disabled → enterAdlPhase 路径，同时会有 notify 发出去
        // （间接验证）。直接调一个会触发 ADL publish 的路径也行。
        SymbolPositionRecord pos = newPositionWithCtx(LiquidationState.WAIT_IF_EXECUTION);
        OrderCommand cmd = newCmd(OrderCommandType.IF_TAKEOVER, MatcherEventType.REJECT, 2L);
        le.nextLiquidationState(cmd, pos);

        assertEquals(1, published.size(), "应该 publish 一条 ADL cmd");
    }

    // ============== helpers ==============

    @SuppressWarnings("unchecked")
    private static Set<SymbolPositionRecord> readInFlightSet(LiquidationEngine le) throws Exception {
        Field f = LiquidationEngine.class.getDeclaredField("inFlightLiquidationCmd");
        f.setAccessible(true);
        return (Set<SymbolPositionRecord>) f.get(le);
    }

    private static SymbolPositionRecord newPositionWithCtx(LiquidationState state) {
        SymbolPositionRecord pos = new SymbolPositionRecord();
        pos.uid = 1L;
        pos.symbol = 100001;
        pos.direction = PositionDirection.LONG;
        pos.openVolume = 10;
        pos.marginMode = MarginMode.ISOLATED;
        LiquidationContext ctx = new LiquidationContext(100L, 5L, 7777L, 0L);
        ctx.state = state;
        pos.liquidationCtx = ctx;
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
