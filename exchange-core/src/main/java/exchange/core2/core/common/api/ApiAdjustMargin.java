package exchange.core2.core.common.api;

import exchange.core2.core.common.MarginMode;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public final class ApiAdjustMargin extends ApiCommand {

    public final long transactionId;
    public final long uid;
    public final int symbol;
    public final int currency; // 全仓补充保证金用到，也是赋给cmd.symbol
    public final long amount;
    @NonNull
    public final MarginMode marginMode;

    @Override
    public String toString() {

        return "[ADJUST_MARGIN " + uid + " "
                + (marginMode == MarginMode.ISOLATED ? "s(" + symbol + ")" : "c(" + currency + ")")
                + " " + amount + "]";
    }
}