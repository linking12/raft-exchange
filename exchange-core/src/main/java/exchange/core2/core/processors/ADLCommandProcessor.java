package exchange.core2.core.processors;

import exchange.core2.core.common.ADLUserPosition;
import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.orderbook.OrderBookEventsHelper;
import exchange.core2.core.processors.liquidation.LiquidationService;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.list.mutable.FastList;

import static exchange.core2.core.ExchangeCore.EVENTS_POOLING;

/**
 * ADL 命令的两步处理器： R1 选盈利候选 + 预占 {@code pendingADLSize}，写 per-shard 链表； matcher 按 score 合并产
 * {@link MatcherEventType#ADL_EVENT}； R2 关 counterparty 仓位 + 走链释放 pendingADLSize（跟 R1 对称）。
 */
@Slf4j
public final class ADLCommandProcessor extends TwoStepCommandProcessor {

    private ADLUserPosition chainHead;

    public ADLCommandProcessor(RiskEngine riskEngine) {
        super(null, riskEngine);
    }

    public ADLCommandProcessor(OrderBookEventsHelper eventsHelper) {
        super(eventsHelper, null);
    }

    private ADLUserPosition newADLUserPosition() {
        if (EVENTS_POOLING) {
            if (chainHead == null) {
                chainHead = riskEngine.getSharedPool().getADLCandidateChain();
            }
            final ADLUserPosition adlUserPosition = chainHead;
            chainHead = chainHead.next;
            adlUserPosition.reset();
            return adlUserPosition;
        } else {
            return new ADLUserPosition();
        }
    }

    @Override
    public void collectInput(OrderCommand cmd) {
        super.collectInput(cmd);
        final int symbol = cmd.symbol;
        final long bankruptcyPrice = cmd.price;
        long remaining = cmd.size;
        if (remaining <= 0) {
            return;
        }
        MutableList<SymbolPositionRecord> profitablePositions = riskEngine.getLiquidationService()
            .computeProfitablePositionsBySymbol().getIfAbsentPut(symbol, FastList::new).select(pos -> {
                if (pos.openVolume <= 0)
                    return false;
                if (pos.openVolume <= pos.pendingADLSize)
                    return false;
                if (pos.direction.isSameAsAction(cmd.action))
                    return false;
                long unrealizedPnl = LiquidationService.unrealizedPnl(pos, bankruptcyPrice);
                return unrealizedPnl > 0;
            }).sortThisByLong(pos -> LiquidationService.riskScore(pos, bankruptcyPrice)).reverseThis();
        if (profitablePositions.isEmpty()) {
            return;
        }
        ADLUserPosition head = null;
        ADLUserPosition tail = null;
        for (SymbolPositionRecord pos : profitablePositions) {
            if (remaining <= 0)
                break;
            long available = pos.openVolume - pos.pendingADLSize;
            long canTake = Math.min(available, remaining);
            // 预占，防止同 cmd 内重复触发同一仓位；R2 finalize 走同一链对称释放
            pos.pendingADLSize += canTake;
            ADLUserPosition adlPos = newADLUserPosition();
            adlPos.uid = pos.uid;
            adlPos.symbol = symbol;
            adlPos.direction = pos.direction;
            adlPos.volume = canTake;
            adlPos.score = LiquidationService.riskScore(pos, bankruptcyPrice);
            if (head == null)
                head = adlPos;
            else
                tail.next = adlPos;
            tail = adlPos;
            remaining -= canTake;
        }
        if (head != null) {
            int shardId = riskEngine.getShardId();
            cmd.adlUserPositionsByShard[shardId] = head;
        }
    }

    @Override
    protected void buildMatcherEvents(OrderCommand cmd) {
        long remaining = cmd.size;
        if (remaining <= 0) {
            cmd.matcherEvent = buildRejectEvent();
            return;
        }
        final ADLUserPosition[] positionsByShard = cmd.adlUserPositionsByShard;

        // cursors 必须 clone，不能直接复用 cmd.adlUserPositionsByShard：循环会做 cursors[bestShard] = best.next
        // 推进游标，若复用相当于把 cmd 上的 head 指针往后挪，下游两条路径都会出问题——
        // 1. R2 finalize 从 head 走链释放 pendingADLSize，前段已消费的候选拿不回保证金，对账漂移；
        // 2. GroupingProcessor 也从 head 走链回收 ADLUserPosition，前段节点不会回到 sharedPool，对象池泄漏。
        // 数组浅拷贝即可，节点（.next / .volume）共享——matcher 只读/挪 head 不删节点。
        final ADLUserPosition[] cursors = positionsByShard.clone();
        MatcherTradeEvent head = null;
        MatcherTradeEvent tail = null;
        while (remaining > 0) {
            ADLUserPosition best = null;
            int bestShard = -1;
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
                break;
            }

            final long execSize = Math.min(best.volume, remaining);

            final MatcherTradeEvent ev = eventsHelper.newMatcherEvent();
            ev.eventType = MatcherEventType.ADL_EVENT;
            ev.matchedOrderUid = best.uid;
            ev.size = execSize;

            if (head == null) {
                head = ev;
            } else {
                tail.nextEvent = ev;
            }
            tail = ev;

            if (execSize == best.volume) {
                cursors[bestShard] = best.next;
            } else {
                best.volume -= execSize;
            }

            remaining -= execSize;
        }

        if (head == null) {
            cmd.matcherEvent = buildRejectEvent();
            return;
        }
        cmd.size -= remaining; // 真实平仓数量，R2 用它关原始仓位
        cmd.matcherEvent = head;
    }

    @Override
    public void applyEvent(OrderCommand cmd, MatcherTradeEvent ev, CoreSymbolSpecification spec,
        CoreCurrencySpecification currencySpec) {
        super.applyEvent(cmd, ev, spec, currencySpec);
        if (ev.eventType != MatcherEventType.ADL_EVENT) {
            return;
        }
        final long uid = ev.matchedOrderUid;
        if (!riskEngine.uidForThisHandler(uid)) {
            return;
        }
        final UserProfile up = riskEngine.getUserProfileService().getUserProfile(uid);
        if (up == null) {
            // candidate uid 在 submit→apply 之间被注销/移除
            log.warn("[CASCADE-DEBUG] ADL applyEvent skip: counterparty UserProfile null uid={} cmd={} symbol={}", uid,
                cmd.command, cmd.symbol);
            return;
        }

        OrderAction adlPosSide = cmd.action.opposite();
        final SymbolPositionRecord pos = up.positions.get(up.createPositionsKey(cmd.symbol, adlPosSide, cmd.command));
        if (pos == null) {
            // candidate 仓位在 submit→apply 之间已被关掉——不修 pendingADLSize（R1 校验时也会 skip 不预占）。
            log.warn(
                "[CASCADE-DEBUG] ADL applyEvent skip: counterparty position null uid={} cmd={} symbol={} action={}",
                uid, cmd.command, cmd.symbol, cmd.action);
            return;
        }

        pos.closeCurrentPositionFutures(adlPosSide.opposite(), ev.size, cmd.price);

        long locked = riskEngine.calculateLocked(up, spec.quoteCurrency);
        long free = up.accounts.get(spec.quoteCurrency) - locked;
        riskEngine.getEventsHelper().sendADLClosePositionEvent(cmd, pos, free, locked);
        if (pos.isEmpty()) {
            long profitToSettle = pos.profit;
            riskEngine.refundExtraMargin(cmd, cmd.orderId, FundEventsHelper.SYSTEM_TRIGGERED_ORDER_ID, spec, pos, up,
                currencySpec, locked);
            riskEngine.removePositionRecord(spec, pos, up, currencySpec);
            if (profitToSettle != 0) {
                long lockedAfter = riskEngine.calculateLocked(up, spec.quoteCurrency);
                long balanceAfter = up.accounts.get(spec.quoteCurrency);
                riskEngine.getEventsHelper().sendPnlSettlementEvent(cmd, cmd.orderId,
                    FundEventsHelper.SYSTEM_TRIGGERED_ORDER_ID, pos, balanceAfter - lockedAfter, lockedAfter);
            }
        }
    }

    @Override
    public void finalizeForCommand(OrderCommand cmd, UserProfile takerUp, SymbolPositionRecord takerSpr,
        CoreSymbolSpecification spec, CoreCurrencySpecification currencySpec) {
        super.finalizeForCommand(cmd, takerUp, takerSpr, spec, currencySpec);
        if (takerSpr != null && cmd.matcherEvent.eventType != MatcherEventType.REJECT) {
            takerSpr.closeCurrentPositionFutures(cmd.action.opposite(), cmd.size, cmd.price);
            long locked = riskEngine.calculateLocked(takerUp, spec.quoteCurrency);
            long free = takerUp.accounts.get(spec.quoteCurrency) - locked;
            riskEngine.getEventsHelper().sendADLClosePositionEvent(cmd, takerSpr, free, locked);
            if (takerSpr.isEmpty()) {
                long profitToSettle = takerSpr.profit;
                riskEngine.refundExtraMargin(cmd, cmd.orderId, FundEventsHelper.SYSTEM_TRIGGERED_ORDER_ID, spec,
                    takerSpr, takerUp, currencySpec, locked);
                riskEngine.removePositionRecord(spec, takerSpr, takerUp, currencySpec);
                if (profitToSettle != 0) {
                    long lockedAfter = riskEngine.calculateLocked(takerUp, spec.quoteCurrency);
                    long balanceAfter = takerUp.accounts.get(spec.quoteCurrency);
                    riskEngine.getEventsHelper().sendPnlSettlementEvent(cmd, cmd.orderId,
                        FundEventsHelper.SYSTEM_TRIGGERED_ORDER_ID, takerSpr, balanceAfter - lockedAfter, lockedAfter);
                }
            }
        }
        // 释放本 shard 的所有 pendingADLSize 预占——跟 R1 +=canTake 对称
        int shardId = riskEngine.getShardId();
        ADLUserPosition head = cmd.adlUserPositionsByShard[shardId];
        while (head != null) {
            UserProfile up = riskEngine.getUserProfileService().getUserProfile(head.uid);
            if (up == null) {
                log.error("ADL pendingADLSize release skip: counterparty UserProfile null uid={} cmd={} symbol={}",
                    head.uid, cmd.command, cmd.symbol);
                head = head.next;
                continue;
            }
            SymbolPositionRecord pos =
                up.positions.get(up.createPositionsKey(head.symbol, cmd.action.opposite(), cmd.command));
            if (pos != null && pos.pendingADLSize > 0) {
                pos.pendingADLSize -= head.volume;
            }
            head = head.next;
        }
    }
}
