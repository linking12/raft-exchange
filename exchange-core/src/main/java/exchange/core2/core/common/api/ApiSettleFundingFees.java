package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public final class ApiSettleFundingFees extends ApiCommand {
    public final long transactionId;
    public final int symbol;
    public final long fundingRate;
    public final long rateScaleK;

    @Override
    public String toString() {
        return "[SETTLE_FUNDINGFEES s" + symbol + " id:" + transactionId + " rate:" + fundingRate + "/" + rateScaleK + "]";
    }
}
