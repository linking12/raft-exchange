package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * Admin-issued command to withdraw from the per-symbol Insurance Fund (IF) available pool on a specific shard.
 * {@code currencyAmount} is denominated in the symbol's quote-currency scale and must be positive.
 * {@code shardId} 定向指定目标 shard——只有匹配的 RiskEngine 会扣账，其他 shard 静默 SUCCESS no-op。
 * Wire 层用 {@code OrderCommand.uid} 字段承载 shardId（IF_DEPOSIT/WITHDRAW 无真实 uid，复用该字段无冲突）。
 * IF available 余额不足以覆盖时返回 {@code RISK_IF_INSUFFICIENT}。仅从 available 扣，不动 reserved。
 * The RiskEngine offsets the withdraw with a matching positive entry in {@code adjustments}
 * so the global reconciliation invariant is preserved.
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public class ApiInsuranceFundWithdraw extends ApiCommand {

    public final long transactionId;
    public final int shardId;
    public final int symbol;
    public final long currencyAmount;

    @Override
    public String toString() {
        return "[IF_WITHDRAW t" + transactionId + " shard=" + shardId + " s" + symbol + " amount=" + currencyAmount + "]";
    }
}
