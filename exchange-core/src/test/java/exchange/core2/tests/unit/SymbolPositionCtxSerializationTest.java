package exchange.core2.tests.unit;

import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.processors.liquidation.LiquidationContext;
import exchange.core2.core.processors.liquidation.LiquidationContext.LiquidationState;
import net.openhft.chronicle.bytes.Bytes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 锁定 {@link LiquidationContext} 跟着 {@link SymbolPositionRecord} 进 raft snapshot 的序列化往返不丢字段。
 *
 * <p>关键场景：cascade 进行中（state=WAIT_IF_EXECUTION 之类）打 snapshot，节点重启 / failover 加载 snapshot
 * 后必须能从 ctx 恢复出原阶段；否则下一条 IF/ADL cmd apply 时会被 {@code nextLiquidationState} 的
 * illegal-jump gate 静默 skip，cascade 永久卡死。
 */
class SymbolPositionCtxSerializationTest {

    private static final long UID = 9527L;

    @Test
    void ctxRoundTrip_inWaitIfExecution_preservesAllFields() {
        SymbolPositionRecord pos = newPositionWithCtx(LiquidationState.WAIT_IF_EXECUTION, 12345L, 67L, 88888L, 1700000000L);

        SymbolPositionRecord restored = roundTrip(pos);

        assertNotNull(restored.liquidationCtx, "ctx 不应丢");
        assertSame(LiquidationState.WAIT_IF_EXECUTION, restored.liquidationCtx.state);
        assertEquals(12345L, restored.liquidationCtx.price);
        assertEquals(67L, restored.liquidationCtx.size);
        assertEquals(88888L, restored.liquidationCtx.originalOrderId);
        assertEquals(1700000000L, restored.liquidationCtx.lastTransitionAt);
    }

    @Test
    void ctxRoundTrip_inWaitAdlExecution_preservesAllFields() {
        SymbolPositionRecord pos = newPositionWithCtx(LiquidationState.WAIT_ADL_EXECUTION, 700L, 3L, 99999L, 1700001234L);

        SymbolPositionRecord restored = roundTrip(pos);

        assertNotNull(restored.liquidationCtx);
        assertSame(LiquidationState.WAIT_ADL_EXECUTION, restored.liquidationCtx.state);
        assertEquals(700L, restored.liquidationCtx.price);
        assertEquals(3L, restored.liquidationCtx.size);
        assertEquals(99999L, restored.liquidationCtx.originalOrderId);
        assertEquals(1700001234L, restored.liquidationCtx.lastTransitionAt);
    }

    @Test
    void ctxRoundTrip_inLiquidating_preservesAllFields() {
        SymbolPositionRecord pos = newPositionWithCtx(LiquidationState.LIQUIDATING, 1000L, 10L, 12345L, 1700002000L);

        SymbolPositionRecord restored = roundTrip(pos);

        assertSame(LiquidationState.LIQUIDATING, restored.liquidationCtx.state);
        assertEquals(1000L, restored.liquidationCtx.price);
        assertEquals(10L, restored.liquidationCtx.size);
    }

    @Test
    void ctxRoundTrip_nullCtx_staysNull() {
        SymbolPositionRecord pos = newPosition();
        pos.liquidationCtx = null;

        SymbolPositionRecord restored = roundTrip(pos);

        assertNull(restored.liquidationCtx, "无 cascade 时 ctx 应保持 null");
    }

    /** stateHash 必须感知 ctx 状态变化——否则跨节点 cascade 阶段发散无法检测。 */
    @Test
    void stateHash_sensitiveToCtxState() {
        SymbolPositionRecord a = newPositionWithCtx(LiquidationState.WAIT_IF_EXECUTION, 100L, 5L, 1L, 0L);
        SymbolPositionRecord b = newPositionWithCtx(LiquidationState.WAIT_IF_EXECUTION, 100L, 5L, 1L, 0L);
        assertEquals(a.stateHash(), b.stateHash(), "同 ctx 状态，hash 必须一致");

        b.liquidationCtx.state = LiquidationState.WAIT_ADL_EXECUTION;
        assertNotEquals(a.stateHash(), b.stateHash(), "ctx.state 不同，hash 必须不同");
    }

    /** ctx 从 null 变成持有 → hash 必变；不变则 leader/follower 发散无法被发散检测捕获。 */
    @Test
    void stateHash_sensitiveToCtxPresence() {
        SymbolPositionRecord noCtx = newPosition();
        SymbolPositionRecord withCtx = newPositionWithCtx(LiquidationState.LIQUIDATING, 100L, 5L, 1L, 0L);

        assertNotEquals(noCtx.stateHash(), withCtx.stateHash(),
                "ctx==null vs ctx 持有 必须哈希不同");
    }

    // ============== helpers ==============

    private static SymbolPositionRecord newPosition() {
        SymbolPositionRecord pos = new SymbolPositionRecord();
        pos.uid = UID;
        pos.symbol = 100001;
        pos.currency = 840;
        pos.direction = PositionDirection.LONG;
        pos.openVolume = 10;
        pos.openInitMarginSum = 1000;
        pos.openPriceSum = 50000;
        pos.profit = 0;
        pos.pendingSellSize = 0;
        pos.pendingBuySize = 0;
        pos.pendingSellAvgPrice = 0;
        pos.pendingBuyAvgPrice = 0;
        pos.updateLeverage(10);
        pos.marginMode = MarginMode.ISOLATED;
        pos.extraMargin = 0;
        return pos;
    }

    private static SymbolPositionRecord newPositionWithCtx(LiquidationState state, long price, long size,
            long originalOrderId, long lastTransitionAt) {
        SymbolPositionRecord pos = newPosition();
        LiquidationContext ctx = new LiquidationContext(price, size, originalOrderId, lastTransitionAt);
        ctx.state = state;
        pos.liquidationCtx = ctx;
        return pos;
    }

    private static SymbolPositionRecord roundTrip(SymbolPositionRecord pos) {
        Bytes<?> buf = Bytes.allocateElasticOnHeap(256);
        pos.writeMarshallable(buf);
        return new SymbolPositionRecord(pos.uid, buf);
    }
}
