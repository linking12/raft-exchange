package exchange.core2.core.common.api;

import exchange.core2.core.common.OrderAction;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public final class ApiSettleFundingFees extends ApiCommand {
    public final long transactionId;
    public final int symbol;
    public final OrderAction action; // 方向，BID是多给空，ASK是空给多
    public final long fundingRate;
    public final long rateScaleK;

    @Override
    public String toString() {
        String direction = action == OrderAction.BID ? "L->S" : "S->L";
        return "[SETTLE_FUNDINGFEES s" + symbol + " " + direction + " id:" + transactionId + " rate:" + fundingRate + "/" + rateScaleK + "]";
    }
}
