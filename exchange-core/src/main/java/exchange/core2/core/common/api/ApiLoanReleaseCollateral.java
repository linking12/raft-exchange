package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * LOAN_RELEASE_COLLATERAL —— Isolated 减抵押（详见 loan.md §5.5）。
 * <p>
 * transactionId → orderId / loanId → reserveBidPrice / amount → size。 减后 LTV &lt; loanLiquidationLtvBps 才允许；抵押减到 0
 * 且本息全清则死壳清理（对象池归还）。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public class ApiLoanReleaseCollateral extends ApiCommand {

    public final long transactionId;
    public final long uid;
    public final long loanId;
    public final long amount;

    @Override
    public String toString() {
        return "[LOAN_REL_COL ext" + transactionId + " u" + uid + " id" + loanId + " -" + amount + "]";
    }
}
