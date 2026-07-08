package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * LOAN_CROSS_WITHDRAW_COLLATERAL —— Cross 抵押池减（详见 loan.md §5.7）。
 * <p>externalId → orderId / currency → symbol / amount → size。
 * 撤后账户级 LTV &lt; crossLiquidationLtvBps 才允许（服务器端 revert-on-fail）。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public class ApiLoanCrossWithdrawCollateral extends ApiCommand {

    public final long externalId;
    public final long uid;
    public final int currency;
    public final long amount;

    @Override
    public String toString() {
        return "[LOAN_X_WITH_COL ext" + externalId + " u" + uid + " c" + currency + " -" + amount + "]";
    }
}
