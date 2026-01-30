package exchange.core2.core.processors.liquidation;

import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.orderbook.OrderBookEventsHelper;

import java.util.Arrays;

/**
 * IFMatchingProcessor resolves IF takeover execution by
 * merging per-shard IF reserved notional and producing IF_TAKEOVER events.
 *
 * <p>
 * It DOES NOT modify IF balances or positions.
 * It DOES NOT modify user positions.
 * It ONLY produces matcher events for R2 execution.
 */
public class IFSettlementProcessor extends AbstractSettlementProcessor {

    public IFSettlementProcessor(OrderBookEventsHelper eventsHelper) {
        super(eventsHelper);
    }

    @Override
    protected void buildMatcherEvents(OrderCommand cmd) {
        long remainingSize = cmd.size;
        final long price = cmd.price;
        if (remainingSize <= 0 || price <= 0) {
            cmd.matcherEvent = buildRejectEvent();
            return;
        }
        final long[] reservedByShard = cmd.ifPreviewCoverByShard;
        if (reservedByShard == null || reservedByShard.length == 0) {
            cmd.matcherEvent = buildRejectEvent();
            return;
        }

        // 注意：IF 接管能力不能用名义价值总和判断。
        // 各分片只能按 floor(reservedNotional / price) 提供合约数量，
        // 名义价值的碎片无法跨分片合并，否则会出现“名义上够、实际接不满”的情况。
        long totalCoverSize = Arrays.stream(reservedByShard).map(n -> n / price).sum();
        if (totalCoverSize < remainingSize) {
            cmd.matcherEvent = buildRejectEvent();
            return;
        }

        MatcherTradeEvent head = null;
        MatcherTradeEvent tail = null;
        // 逐 shard 分摊
        for (int shardId = 0; shardId < reservedByShard.length && remainingSize > 0; shardId++) {
            long reservedNotional = reservedByShard[shardId];
            if (reservedNotional <= 0) {
                continue;
            }

            // 本 shard 最多能接多少 size
            long maxSizeByNotional = reservedNotional / price;
            long takeSize = Math.min(maxSizeByNotional, remainingSize);

            // 生成 IF takeover matcher event
            MatcherTradeEvent ev = eventsHelper.newMatcherEvent();
            ev.eventType = MatcherEventType.IF_EVENT;
            ev.size = takeSize;
            ev.matchedOrderUid = shardId; // 记录R2时，由哪个分片处理

            // 尾插
            if (head == null) {
                head = ev;
            } else {
                tail.nextEvent = ev;
            }
            tail = ev;

            remainingSize -= takeSize;
        }
        cmd.matcherEvent = head;
    }

}