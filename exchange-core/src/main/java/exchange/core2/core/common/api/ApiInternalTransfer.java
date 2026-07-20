package exchange.core2.core.common.api;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;

/**
 * INTERNAL_TRANSFER —— 用户间同币种原子转账。
 *
 * <p>Wire 映射：fromUid → uid / toUid → size / currency → symbol / amount → price / transactionId → orderId（幂等 key）。
 * from 与 to 可跨 shard；R1（from-shard）扣款，R2（to-shard）入账（详见 {@link exchange.core2.core.processors.InternalTransferProcessor}）。
 */
@Builder
@EqualsAndHashCode(callSuper = false)
@RequiredArgsConstructor
public final class ApiInternalTransfer extends ApiCommand {

    public final long fromUid;
    public final long toUid;
    public final int currency;
    public final long amount;
    public final long transactionId;

    @Override
    public String toString() {
        return "[INTERNAL_TRANSFER id:" + transactionId + " " + fromUid + "->" + toUid + " " + amount + " c" + currency
            + "]";
    }
}
