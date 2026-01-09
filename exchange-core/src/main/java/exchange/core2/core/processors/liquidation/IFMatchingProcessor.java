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
public class IFMatchingProcessor extends AbstractLiquidationMatchingProcessor {

    public IFMatchingProcessor(OrderBookEventsHelper eventsHelper) {
        super(eventsHelper);
    }

    @Override
    protected void buildMatcherEvents(OrderCommand cmd) {
        long remainingSize = cmd.size;
        if (remainingSize <= 0) {
            cmd.matcherEvent = buildRejectEvent();
            return;
        }
        final long price = cmd.price;
        final long[] reservedByShard = cmd.ifPreviewCoverByShard;
        if (reservedByShard == null || reservedByShard.length == 0) {
            cmd.matcherEvent = buildRejectEvent();
            return;
        }

        long neededNotional = remainingSize * price;
        long previewCoveredNotional = Arrays.stream(reservedByShard).sum();
        if (previewCoveredNotional < neededNotional) {
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
            ev.matchedOrderUid = 0L; // IF 是系统行为，无真实 uid

            // 尾插
            if (head == null) {
                head = ev;
            } else {
                tail.nextEvent = ev;
            }
            tail = ev;

            remainingSize -= takeSize;
        }

        if (head == null) {
            // 没有执行出任何 ADL event，视为 reject（保证 matcherEvent 非空）
            cmd.matcherEvent = buildRejectEvent();
            return;
        }
        // 更新真实被 IF 接管的 size
        cmd.size -= remainingSize;
        cmd.matcherEvent = head;
    }

}