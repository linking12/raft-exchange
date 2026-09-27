package exchange.core2.core.processors.liquidation;

import lombok.ToString;

/**
 * 单个持仓正在进行的强平流程：leader-local 内存状态机（FORCE → IF → ADL）。
 *
 * <p>
 * 挂在 {@link exchange.core2.core.common.SymbolPositionRecord#liquidationFlow} 上，<b>不序列化、不进
 * stateHash</b>；流程闭环时置 {@code pos.liquidationFlow = null}。换届后新 leader 侧该字段为空，残余仓位会被
 * 当作破产仓重新走一遍 FORCE 恢复。
 */
@ToString
public final class LiquidationFlow {
    public LiquidationState state;
    public long bankruptcyPrice;
    public long size;
    public long originalOrderId; // FORCE 阶段生成的根 orderId，IF/ADL 的 orderId 由此派生

    public LiquidationFlow(long bankruptcyPrice, long size, long originalOrderId) {
        this.state = LiquidationState.LIQUIDATING;
        this.bankruptcyPrice = bankruptcyPrice;
        this.size = size;
        this.originalOrderId = originalOrderId;
    }

    /** 强平推进方向：先市价强平，失败转 IF 接管，再失败转 ADL 摊派。 */
    public enum LiquidationState {
        LIQUIDATING, WAIT_IF_EXECUTION, WAIT_ADL_EXECUTION
    }
}
