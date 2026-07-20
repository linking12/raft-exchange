package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * Admin-issued command to top up the per-symbol Insurance Fund (IF) available pool on a specific shard.
 * {@code currencyAmount} is denominated in the symbol's quote-currency scale and must be positive.
 * {@code shardId} 定向指定目标 shard——只有匹配的 RiskEngine 会入账，其他 shard 静默 SUCCESS no-op。
 * Wire 层用 {@code OrderCommand.uid} 字段承载 shardId（IF_DEPOSIT/WITHDRAW 无真实 uid，复用该字段无冲突）。
 * 运营侧通过 {@link exchange.core2.core.common.api.reports.InsuranceFundReportQuery} 查各 shard 明细，
 * 决定往哪个 shard 定向注资，应对分片不均衡。
 * The RiskEngine offsets the deposit with a matching negative entry in {@code adjustments}
 * so the global reconciliation invariant ({@code getGlobalBalancesSum() == 0}) is preserved.
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public class ApiInsuranceFundDeposit extends ApiCommand {

    public final long transactionId;
    public final int shardId;
    public final int symbol;
    public final long currencyAmount;

    @Override
    public String toString() {
        return "[IF_DEPOSIT t" + transactionId + " shard=" + shardId + " s" + symbol + " amount=" + currencyAmount + "]";
    }
}
