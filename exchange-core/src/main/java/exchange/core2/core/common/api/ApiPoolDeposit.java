package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * POOL_DEPOSIT —— 借贷池注资（运营命令，详见 loan.md §5.10.2）。
 * <p>
 * Wire 层 externalId → orderId（幂等 key）/ shardId → uid（借用该字段承载定向 shard，无真实 uid）/ currency → symbol / amount → size。跟
 * {@link ApiInsuranceFundDeposit} 同款"复用 uid 字段"pattern。
 * <p>
 * 所有 shard 都跑 handle（内部按 shardId 短路），但只 target shard 写 cmd.resultCode。 资金侧 pool.available += amount 同时 adjustments -=
 * amount 对冲，保守恒。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public class ApiPoolDeposit extends ApiCommand {

    public final long externalId;
    public final int shardId;
    public final int currency;
    public final long amount;

    @Override
    public String toString() {
        return "[POOL_DEPOSIT ext" + externalId + " shard=" + shardId + " c" + currency + " +" + amount + "]";
    }
}
