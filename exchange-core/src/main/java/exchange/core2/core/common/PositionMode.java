package exchange.core2.core.common;

import lombok.Getter;

@Getter
public enum PositionMode {
    ONEWAY(0), // 单向持仓
    HEDGE(1);  // 双向持仓

    private byte code;

    PositionMode(int code) {
        this.code = (byte) code;
    }

    public static PositionMode of(byte code) {
        switch (code) {
            case 0:
                return ONEWAY;
            case 1:
                return HEDGE;
            default:
                throw new IllegalArgumentException("unknown PositionMode:" + code);
        }
    }
}