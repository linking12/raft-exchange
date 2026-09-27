package exchange.core2.core.common.api;

import exchange.core2.core.common.OrderAction;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public final class ApiClosePosition extends ApiCommand {

    public final long price;
    public final long size;
    public final long orderId;
    public final OrderAction action;//交易方向：平多=ASK，平空=BID
    public final long uid;
    public final int symbol;

    @Override
    public String toString() {
        return "[CLOSE_POSITION " + orderId + " u" + uid + " s" + symbol
                + " " + (action == OrderAction.ASK ? 'A' : 'B')
                + ":" + price + ":" + size + "]";
    }
}
