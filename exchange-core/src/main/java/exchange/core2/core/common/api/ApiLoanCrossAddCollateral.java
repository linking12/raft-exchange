package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * LOAN_CROSS_ADD_COLLATERAL —— Cross 账户级抵押池增（详见 loan.md §5.6）。
 * <p>
 * externalId → orderId / currency → symbol / amount → size。 Cross 多笔 debt 共享该多币种抵押池。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public class ApiLoanCrossAddCollateral extends ApiCommand {

    public final long externalId;
    public final long uid;
    public final int currency;
    public final long amount;

    @Override
    public String toString() {
        return "[LOAN_X_ADD_COL ext" + externalId + " u" + uid + " c" + currency + " +" + amount + "]";
    }
}
