package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * LOAN_IF_WITHDRAW —— LIF 提取（运营，详见 loan.md §18.5）。字段映射同 {@link ApiLoanIfDeposit}；
 * loanInsuranceFund -= amount，adjustments += amount 对冲。
 * 余额不足即拒：LIF 允许为负是接管的被动结果，不是运营可主动透支的额度。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public class ApiLoanIfWithdraw extends ApiCommand {

    public final int shardId;
    public final int currency;
    public final long amount;

    @Override
    public String toString() {
        return "[LOAN_IF_WITHDRAW shard=" + shardId + " c" + currency + " -" + amount + "]";
    }
}
