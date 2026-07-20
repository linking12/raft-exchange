package exchange.core2.core.common;

import lombok.Getter;

@Getter
public enum MarginMode {
    ISOLATED(0),  // 逐仓
    CROSS(1);     // 全仓

    private final byte code;

    MarginMode(final int code) {
        this.code = (byte) code;
    }

    public static MarginMode of(byte code) {
        switch (code) {
            case 0:
                return ISOLATED;
            case 1:
                return CROSS;
            default:
                throw new IllegalArgumentException("unknown MarginMode:" + code);
        }
    }
}
