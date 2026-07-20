package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * LOAN_CROSS_BORROW —— Cross 借新债；共享账户级抵押池（详见 loan.md §5.8）。
 * <p>
 * transactionId → orderId / loanId → reserveBidPrice / symbolId → symbol / principal → price。
 * 服务端据 symbolId 反推 loanCurrency = spec.quoteCurrency。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public class ApiLoanCrossBorrow extends ApiCommand {

    public final long transactionId;
    public final long uid;
    public final long loanId;
    public final int symbolId;
    public final long principal;

    @Override
    public String toString() {
        return "[LOAN_X_BORROW ext" + transactionId + " u" + uid + " id" + loanId + " sym" + symbolId + " p="
            + principal + "]";
    }
}
