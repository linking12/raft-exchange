package exchange.core2.core.processors.liquidation;

import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.orderbook.OrderBookEventsHelper;

import java.util.Arrays;

public class FundingFeeMatchingProcessor extends AbstractLiquidationMatchingProcessor {

    public FundingFeeMatchingProcessor(OrderBookEventsHelper eventsHelper) {
        super(eventsHelper);
    }

    @Override
    protected void buildMatcherEvents(OrderCommand cmd) {
        final long[] fundingPayAmountByShard = cmd.fundingPayAmountByShard;
        final long[] fundingRecvNotionalByShard = cmd.fundingRecvNotionalByShard;

        long totalPayAmount = Arrays.stream(fundingPayAmountByShard).sum();
        long totalRecvNotional = Arrays.stream(fundingRecvNotionalByShard).sum();

        int numShards = fundingRecvNotionalByShard.length;
        long[] shardRecvAmount = new long[numShards];
        long distributed = 0;
        for (int i = 0; i < numShards; i++) {
            long notional = fundingRecvNotionalByShard[i];
            if (notional == 0) continue;
            long amount = totalPayAmount * notional / totalRecvNotional; // floor
            shardRecvAmount[i] = amount;
            distributed += amount;
        }
        // 尾差处理
        long remainder = totalPayAmount - distributed;
        for (int i = 0; remainder > 0 && i < numShards; i++) {
            if (fundingRecvNotionalByShard[i] > 0) {
                shardRecvAmount[i]++;
                remainder--;
            }
        }

        MatcherTradeEvent head = null;
        MatcherTradeEvent tail = null;
        // 逐 shard 分摊
        for (int shardId = 0; shardId < numShards; shardId++) {
            long amount = shardRecvAmount[shardId];
            if (amount <= 0) continue;

            MatcherTradeEvent ev = eventsHelper.newMatcherEvent();
            ev.eventType = MatcherEventType.FUNDING_EVENT;
            ev.price = amount;
            ev.matchedOrderUid = shardId; // 记录R2时，由哪个分片处理

            // 尾插
            if (head == null) {
                head = ev;
            } else {
                tail.nextEvent = ev;
            }
            tail = ev;
        }
        cmd.matcherEvent = head;
    }
}
