package exchange.core2.core.processors.liquidation;

public enum LiquidationState {
    LIQUIDATING,
    WAIT_IF_JUDGEMENT,
    WAIT_ADL_EXECUTION,
    CLOSED
}