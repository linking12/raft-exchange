package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * POOL_WITHDRAW —— 借贷池提现（运营命令，详见 loan.md §5.10.2）。
 * <p>Wire 语义同 {@link ApiPoolDeposit}。资金侧 pool.available -= amount，adjustments += amount 对冲。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public class ApiPoolWithdraw extends ApiCommand {

    public final long externalId;
    public final int shardId;
    public final int currency;
    public final long amount;

    @Override
    public String toString() {
        return "[POOL_WITHDRAW ext" + externalId + " shard=" + shardId + " c" + currency + " -" + amount + "]";
    }
}
