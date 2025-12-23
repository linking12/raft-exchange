package exchange.core2.core.common.api;


import exchange.core2.core.common.OrderAction;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public final class ApiAutoDeleveraging extends ApiCommand {

    public final long orderId;
    public final long uid;
    public final int symbol;
    public final OrderAction action; // 仓位方向
    public final long size;
    public final long price; // 破产价

    @Override
    public String toString() {
        return "[ADL o" + orderId + " s" + symbol + " u" + uid + " " + (action == OrderAction.ASK ? 'A' : 'B')
                + ":" + price + ":" + size + "]";
    }
}
