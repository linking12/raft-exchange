package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public final class ApiSettlePNL extends ApiCommand {

    public final long transactionId;
    public final int symbol;
    public final long settlePrice;

    @Override
    public String toString() {
        return "[SETTLE_PNL s" + symbol + " id:" + transactionId + " price:" + settlePrice + "]";
    }

}
