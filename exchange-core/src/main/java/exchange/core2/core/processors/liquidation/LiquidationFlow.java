package exchange.core2.core.processors.liquidation;

import lombok.ToString;

/**
 * 进行中强平流程的 leader-local 内存状态（FORCE→IF→ADL 状态机）：
 * state / bankruptcyPrice / size / originalOrderId。
 * <p>
 * 挂在 {@link exchange.core2.core.common.SymbolPositionRecord#liquidationFlow} 上，
 * <b>不序列化、不进 stateHash</b>；闭环时置 {@code pos.liquidationFlow = null}。
 * 换届后新 leader 侧为空，残余仓被当作破产仓重发 FORCE 恢复。
 */
@ToString
public final class LiquidationFlow {
    public LiquidationState state;
    public long bankruptcyPrice;
    public long size;
    public long originalOrderId;

    public LiquidationFlow(long bankruptcyPrice, long size, long originalOrderId) {
        this.state = LiquidationState.LIQUIDATING;
        this.bankruptcyPrice = bankruptcyPrice;
        this.size = size;
        this.originalOrderId = originalOrderId;
    }

    /** 强平推进状态：先市价强平，失败转 IF 接管，再失败转 ADL 摊派。 */
    public enum LiquidationState {
        LIQUIDATING, WAIT_IF_EXECUTION, WAIT_ADL_EXECUTION
    }
}
