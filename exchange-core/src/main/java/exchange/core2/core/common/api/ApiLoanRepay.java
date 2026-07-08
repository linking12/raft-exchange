package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * LOAN_REPAY —— Isolated 还款（详见 loan.md §5.3）。
 * <p>externalId → orderId / loanId → reserveBidPrice / repayAmount → price（0 表示 payoff 全部本息）。
 * 部分还款不释放抵押。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public class ApiLoanRepay extends ApiCommand {

    public final long externalId;
    public final long uid;
    public final long loanId;
    public final long repayAmount;

    @Override
    public String toString() {
        return "[LOAN_REPAY ext" + externalId + " u" + uid + " id" + loanId + " repay=" + repayAmount + "]";
    }
}
