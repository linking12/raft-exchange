package exchange.core2.core.processors;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.orderbook.OrderBookEventsHelper;

import java.util.Arrays;

/**
 * IF takeover 命令的两步处理器：
 * R1 各 shard 在 IF balance 上 reserve notional；
 * matcher 按 {@code floor(reserved / price)} 算各 shard 承接量产 {@link MatcherEventType#IF_EVENT}；
 * R2 落账 counterparty 仓位 + 关 taker + 释放 reserved 余量（跟 R1 对称）。
 */
public final class IFCommandProcessor extends TwoStepCommandProcessor {

    public IFCommandProcessor(RiskEngine riskEngine) {
        super(null, riskEngine);
    }

    public IFCommandProcessor(OrderBookEventsHelper eventsHelper) {
        super(eventsHelper, null);
    }

    @Override
    public void collectInput(OrderCommand cmd) {
        super.collectInput(cmd);
        long previewCover = riskEngine.getLiquidationService().reserveIFNotional(cmd.symbol, cmd.size, cmd.price);
        int shardId = riskEngine.getShardId();
        cmd.ifPreviewCoverByShard[shardId] = previewCover;
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

        // 不能用 notional 总和判够：每 shard 只能按 floor(reservedNotional / price) 承接，
        // 名义价值碎片无法跨 shard 合并，否则会出现"名义上够、实际接不满"。
        long totalCoverSize = Arrays.stream(reservedByShard).map(n -> n / price).sum();
        if (totalCoverSize < remainingSize) {
            cmd.matcherEvent = buildRejectEvent();
            return;
        }

        MatcherTradeEvent head = null;
        MatcherTradeEvent tail = null;
        for (int shardId = 0; shardId < reservedByShard.length && remainingSize > 0; shardId++) {
            long reservedNotional = reservedByShard[shardId];
            if (reservedNotional <= 0) {
                continue;
            }

            long maxSizeByNotional = reservedNotional / price;
            long takeSize = Math.min(maxSizeByNotional, remainingSize);

            MatcherTradeEvent ev = eventsHelper.newMatcherEvent();
            ev.eventType = MatcherEventType.IF_EVENT;
            ev.size = takeSize;
            ev.matchedOrderUid = shardId; // R2 路由用

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

    @Override
    public void applyEvent(OrderCommand cmd, MatcherTradeEvent ev,
        CoreSymbolSpecification spec, CoreCurrencySpecification currencySpec) {
        super.applyEvent(cmd, ev, spec, currencySpec);
        if (ev.eventType != MatcherEventType.IF_EVENT) {
            return;
        }
        int shardId = riskEngine.getShardId();
        if (ev.matchedOrderUid != shardId) {
            return;
        }
        PositionDirection direction = cmd.action == OrderAction.BID ? PositionDirection.LONG : PositionDirection.SHORT;
        riskEngine.getLiquidationService().acceptIFPosition(cmd.symbol, direction, ev.size, cmd.price);
    }

    @Override
    public void finalizeForCommand(OrderCommand cmd, UserProfile takerUp,
        SymbolPositionRecord takerSpr, CoreSymbolSpecification spec, CoreCurrencySpecification currencySpec) {
        super.finalizeForCommand(cmd, takerUp, takerSpr, spec, currencySpec);
        if (takerSpr != null && cmd.matcherEvent.eventType != MatcherEventType.REJECT) {
            takerSpr.closeCurrentPositionFutures(cmd.action.opposite(), cmd.size, cmd.price);
            long locked = riskEngine.calculateLocked(takerUp, spec.quoteCurrency);
            long free = takerUp.accounts.get(spec.quoteCurrency) - locked;
            riskEngine.getEventsHelper().sendIFClosePositionEvent(cmd, takerSpr, free, locked);
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
        // 释放本 shard reserved notional 余量——跟 R1 reserveIFNotional 对称
        int shardId = riskEngine.getShardId();
        long previewCover = cmd.ifPreviewCoverByShard[shardId];
        riskEngine.getLiquidationService().releaseReservedIFNotional(cmd.symbol, previewCover);
    }
}
