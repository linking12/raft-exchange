package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * LOAN_IF_DEPOSIT —— LIF 注资（运营，详见 loan.md §18.5）。
 * shardId → uid（借该字段承载定向 shard，无真实 uid）/ currency → symbol / amount → size，同 {@link ApiPoolDeposit}。
 * loanInsuranceFund += amount，adjustments -= amount 对冲。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public class ApiLoanIfDeposit extends ApiCommand {

    public final int shardId;
    public final int currency;
    public final long amount;

    @Override
    public String toString() {
        return "[LOAN_IF_DEPOSIT shard=" + shardId + " c" + currency + " +" + amount + "]";
    }
}
