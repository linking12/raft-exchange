package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * LOAN_CROSS_REPAY —— Cross 还款（详见 loan.md §5.9）。
 * <p>externalId → orderId / loanId → reserveBidPrice / repayAmount → price（0 表示 payoff）。
 * 逻辑同 Isolated REPAY，但不释放抵押（Cross 抵押是账户级共享）。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public class ApiLoanCrossRepay extends ApiCommand {

    public final long externalId;
    public final long uid;
    public final long loanId;
    public final long repayAmount;

    @Override
    public String toString() {
        return "[LOAN_X_REPAY ext" + externalId + " u" + uid + " id" + loanId + " repay=" + repayAmount + "]";
    }
}
