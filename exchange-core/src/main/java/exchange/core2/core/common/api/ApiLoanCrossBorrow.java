package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * LOAN_CROSS_BORROW —— Cross 借新债；共享账户级抵押池（详见 loan.md §5.8）。
 * <p>externalId → orderId / loanId → reserveBidPrice / loanCcy → symbol / principal → price。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public class ApiLoanCrossBorrow extends ApiCommand {

    public final long externalId;
    public final long uid;
    public final long loanId;
    public final int loanCcy;
    public final long principal;

    @Override
    public String toString() {
        return "[LOAN_X_BORROW ext" + externalId + " u" + uid + " id" + loanId + " ccy" + loanCcy + " p=" + principal + "]";
    }
}
