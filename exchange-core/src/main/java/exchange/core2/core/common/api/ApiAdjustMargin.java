package exchange.core2.core.common.api;

import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
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
    public final OrderAction action; // 双向持仓时，逐仓，判断是给多仓还是空仓补充保证金
    public final int currency; // 全仓补充保证金用到，也是赋给cmd.symbol
    public final long amount;
    @NonNull
    public final MarginMode marginMode;

    @Override
    public String toString() {

        String symbolStr = String.valueOf(action == OrderAction.BID ? symbol : -symbol);
        return "[ADJUST_MARGIN " + uid + " "
                + (marginMode == MarginMode.ISOLATED ? "s(" + symbolStr + ")" : "c(" + currency + ")")
                + " " + amount + "]";
    }
}