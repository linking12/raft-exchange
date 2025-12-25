package exchange.core2.core.processors;

import exchange.core2.core.common.ADLUserPosition;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.orderbook.OrderBookEventsHelper;
import lombok.RequiredArgsConstructor;

/**
 * ADLMatchingProcessor is responsible for resolving auto-deleveraging execution
 * by merging per-shard ADL candidates and producing ADL matcher events.
 * <p>
 * It DOES NOT modify positions or accounts.
 * It DOES NOT perform order matching.
 */
@RequiredArgsConstructor
public class ADLMatchingProcessor {

    private final OrderBookEventsHelper eventsHelper;

    public CommandResultCode processADLCommand(OrderCommand cmd) {
        if (cmd.resultCode == CommandResultCode.VALID_FOR_MATCHING_ENGINE) {
            matchADLPositions(cmd);
            return CommandResultCode.SUCCESS;
        } else {
            return cmd.resultCode;
        }
    }

    private void matchADLPositions(OrderCommand cmd) {
        long remaining = cmd.size;
        if (remaining <= 0) {
            return;
        }
        final ADLUserPosition[] positionsByShard = cmd.adlUserPositionsByShard;
        if (positionsByShard == null || positionsByShard.length == 0) {
            return;
        }

        // 每个 shard 一个 cursor，指向该 shard 的当前 candidate
        final ADLUserPosition[] cursors = positionsByShard.clone(); // 浅拷贝
        MatcherTradeEvent head = null;
        MatcherTradeEvent tail = null;
        while (remaining > 0) {
            ADLUserPosition best = null;
            int bestShard = -1;

            // 在所有 shard 的当前 candidate 中选 score 最大的
            for (int i = 0; i < cursors.length; i++) {
                final ADLUserPosition c = cursors[i];
                if (c == null) {
                    continue;
                }
                if (best == null || c.score > best.score) {
                    best = c;
                    bestShard = i;
                }
            }
            if (best == null) {
                break; // 所有候选集走完了，退出
            }

            // 计算本次执行 size
            final long execSize = Math.min(best.volume, remaining);

            // 生成 ADL event
            final MatcherTradeEvent ev = eventsHelper.newMatcherEvent();
            ev.eventType = MatcherEventType.ADL_EVENT;
            ev.matchedOrderUid = best.uid;
            ev.size = execSize;

            // 尾插
            if (head == null) {
                head = ev;
            } else {
                tail.nextEvent = ev;
            }
            tail = ev;

            if (execSize == best.volume) {
                // 用完了，切到下一个
                cursors[bestShard] = best.next;
            } else {
                // 只用了一部分，更新剩余量，cursor 不动
                best.volume -= execSize;
            }

            remaining -= execSize;
        }

        cmd.size -= remaining; // 更新真实平仓数量，即R2阶段原始仓位关仓数量
        cmd.matcherEvent = head;
    }
}
