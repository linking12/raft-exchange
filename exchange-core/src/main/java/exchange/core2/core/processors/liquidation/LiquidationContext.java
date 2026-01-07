package exchange.core2.core.processors.liquidation;

import lombok.ToString;

@ToString
public final class LiquidationContext {

    public LiquidationState state;
    public long price; // liquidation price
    public long size; // liquidation size

    public LiquidationContext(long price, long size) {
        this.state = LiquidationState.LIQUIDATING;
        this.price = price;
        this.size = size;
    }

    public enum LiquidationState {
        LIQUIDATING,
        WAIT_IF_JUDGEMENT,
        WAIT_ADL_EXECUTION,
        CLOSED
    }

}