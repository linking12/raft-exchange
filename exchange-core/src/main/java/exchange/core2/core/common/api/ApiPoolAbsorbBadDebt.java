package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * POOL_ABSORB_BAD_DEBT —— 官方确认坏账，清 badDebt 审计追踪。
 * <p>字段映射跟 {@link ApiPoolDeposit} 同款：externalId → orderId（幂等 key）/ shardId → uid（承载定向 shard）
 * / currency → symbol / amount → size。所有 shard 都跑 handle，只 target shard 写 cmd.resultCode。
 * <p>语义：{@code absorbed = min(amount, badDebt[currency])}；{@code badDebt -= absorbed}。
 * poolAvailable 不动——损失在 force-sell underwater 时已反映到 poolAvailable。
 * badDebt 为 0 时 → LOAN_INVALID_AMOUNT。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public class ApiPoolAbsorbBadDebt extends ApiCommand {

    public final long externalId;
    public final int shardId;
    public final int currency;
    public final long amount;

    @Override
    public String toString() {
        return "[POOL_ABSORB_BAD_DEBT ext" + externalId + " shard=" + shardId + " c" + currency + " amt=" + amount + "]";
    }
}
