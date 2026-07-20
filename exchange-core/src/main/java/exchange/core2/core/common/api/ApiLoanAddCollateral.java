package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * LOAN_ADD_COLLATERAL —— Isolated 补抵押降 LTV（详见 loan.md §5.4）。
 * <p>
 * transactionId → orderId / loanId → reserveBidPrice / amount → size。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public class ApiLoanAddCollateral extends ApiCommand {
    public final long transactionId;
    public final long uid;
    public final long loanId;
    public final long amount;

    @Override
    public String toString() {
        return "[LOAN_ADD_COL ext" + transactionId + " u" + uid + " id" + loanId + " +" + amount + "]";
    }
}
