package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public final class ApiAdjustPrice extends ApiCommand {

    public final long transactionId;
    public final int symbol;
    public final long markPrice;

    @Override
    public String toString() {

        return "[ADJUST_PRICE s" + symbol + " id:" + transactionId + " price:" + markPrice + "]";
    }
}