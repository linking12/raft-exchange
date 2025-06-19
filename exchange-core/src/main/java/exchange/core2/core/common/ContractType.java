package exchange.core2.core.common;

import lombok.Getter;

import java.util.Arrays;

@Getter
public enum ContractType {
    PERPETUAL(0),       // 永续合约
    DELIVERY(1);        // 交割合约

    private final byte code;

    ContractType(int code) {
        this.code = (byte) code;
    }

    public static ContractType of(int code) {
        return Arrays.stream(values())
                .filter(c -> c.code == (byte) code)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown ContractType code: " + code));
    }
}