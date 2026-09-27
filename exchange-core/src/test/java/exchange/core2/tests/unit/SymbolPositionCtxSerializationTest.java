package exchange.core2.tests.unit;

import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.processors.liquidation.LiquidationFlow;
import exchange.core2.core.processors.liquidation.LiquidationFlow.LiquidationState;
import net.openhft.chronicle.bytes.Bytes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 锁定不变量：强平流程状态 {@link LiquidationFlow} 是 <b>leader-local、纯内存</b> 字段——
 * <b>不进 raft snapshot、不进 stateHash</b>，{@code writeMarshallable} / 读构造都不含它。
 *
 * <p>生产已从"ctx 跟随 pos 进 snapshot + stateHash"<b>反转</b>为 depersist：换届后新 leader 侧
 * {@code liquidationFlow} 为空，残余仓被当作破产仓重发 FORCE 恢复（正确性靠 R1 对 size 的夹取保证），
 * 因此流程态无需、也不应参与复制态。本测试守住两条反转后的核心不变量：
 * <ol>
 *   <li>序列化往返后 {@code restored.liquidationFlow} 恒为 {@code null}（即便原 pos 持有 flow）；</li>
 *   <li>{@code stateHash()} 与 flow 无关：同一 pos，flow=null vs 持有 flow，hash 必须相等。</li>
 * </ol>
 */
class SymbolPositionCtxSerializationTest {

    private static final long UID = 9527L;

    /** flow 不序列化：即便原 pos 设了 flow，往返后 restored.liquidationFlow 必为 null。 */
    @Test
    void roundTrip_dropsLiquidationFlow() {
        SymbolPositionRecord pos = newPositionWithFlow(LiquidationState.WAIT_IF_EXECUTION, 12345L, 67L, 88888L);

        SymbolPositionRecord restored = roundTrip(pos);

        assertNull(restored.liquidationFlow, "liquidationFlow 是 leader-local，不进 snapshot，往返后必为 null");
    }

    /** flow=null 的 pos 往返后仍为 null（回归对照）。 */
    @Test
    void roundTrip_nullFlow_staysNull() {
        SymbolPositionRecord pos = newPosition();
        pos.liquidationFlow = null;

        SymbolPositionRecord restored = roundTrip(pos);

        assertNull(restored.liquidationFlow);
    }

    /** stateHash 与 flow 无关：同一 pos，flow=null vs 持有 flow，hash 必须相等（证明 flow 不参与 hash）。 */
    @Test
    void stateHash_independentOfFlowPresence() {
        SymbolPositionRecord noFlow = newPosition();
        SymbolPositionRecord withFlow = newPositionWithFlow(LiquidationState.LIQUIDATING, 100L, 5L, 1L);

        assertEquals(noFlow.stateHash(), withFlow.stateHash(),
                "liquidationFlow 不进 stateHash：持有 flow 不应改变 hash");
    }

    /** stateHash 与 flow.state 无关：同一 pos，不同 flow 状态，hash 必须相等。 */
    @Test
    void stateHash_independentOfFlowState() {
        SymbolPositionRecord a = newPositionWithFlow(LiquidationState.WAIT_IF_EXECUTION, 100L, 5L, 1L);
        SymbolPositionRecord b = newPositionWithFlow(LiquidationState.WAIT_ADL_EXECUTION, 100L, 5L, 1L);

        assertEquals(a.stateHash(), b.stateHash(), "flow.state 不同不应改变 hash");
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

    private static SymbolPositionRecord newPositionWithFlow(LiquidationState state, long bankruptcyPrice, long size,
            long originalOrderId) {
        SymbolPositionRecord pos = newPosition();
        LiquidationFlow flow = new LiquidationFlow(bankruptcyPrice, size, originalOrderId);
        flow.state = state;
        pos.liquidationFlow = flow;
        return pos;
    }

    private static SymbolPositionRecord roundTrip(SymbolPositionRecord pos) {
        Bytes<?> buf = Bytes.allocateElasticOnHeap(256);
        pos.writeMarshallable(buf);
        return new SymbolPositionRecord(pos.uid, buf);
    }
}
