package exchange.core2.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ObjLongConsumer;

import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import exchange.core2.core.IFundEventsHandler.FundEventReport;
import exchange.core2.core.ITradeEventsHandler.FuturesExecutionReport;
import exchange.core2.core.ITradeEventsHandler.SpotExecutionReport;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
public class SimpleEventsProcessor implements ObjLongConsumer<OrderCommand> {

    private final ITradeEventsHandler tradeEventsHandler;
    private final IFundEventsHandler fundEventsHandler;
    private final IntObjectHashMap<UserProfileService> userProfileServices = IntObjectHashMap.newMap();
    @Setter
    private SymbolSpecificationProvider symbolSpecificationProvider;
    @Setter
    private int numShards;

    @Override
    public void accept(OrderCommand cmd, long seq) {
        try {
            if (seq < 0) {
                // 来自R2风控阶段
                sendFundEvents(cmd, -seq);
            } else {
                // 主流程撮合结果处理
                sendExecutionReport(cmd, seq);
                sendFundEvents(cmd, seq);
                sendMarketData(cmd);
            }
        } catch (Exception ex) {
            log.error("Exception when handling command result data", ex);
        }
    }

    private void sendExecutionReport(OrderCommand cmd, long seq) {
        if (isReportableCommand(cmd.command)) {
            final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(cmd.symbol);
            switch (spec.type) {
                case CURRENCY_EXCHANGE_PAIR:
                    sendSpotExecutionReport(cmd, seq, spec);
                    break;
                case FUTURES_CONTRACT_PERPETUAL:
                case FUTURES_CONTRACT_DELIVERY:
                    sendFuturesExecutionReport(cmd, seq, spec);
                    break;
            }
        }
    }

    private void sendSpotExecutionReport(OrderCommand cmd, long seq, CoreSymbolSpecification spec) {
        final MatcherTradeEvent first = cmd.matcherEvent;
        // -------- 1) NEW（下单入口） --------
        if (cmd.command == OrderCommandType.PLACE_ORDER && cmd.resultCode == CommandResultCode.SUCCESS) {
            tradeEventsHandler.spotExecutionReport(SpotExecutionReport.placeOrder(cmd, seq, spec));
        }
        // ------------REJECT（下单拒绝）----------
        if (first != null && first.eventType == MatcherEventType.REJECT) {
            tradeEventsHandler.spotExecutionReport(SpotExecutionReport.rejectOrder(cmd, seq, spec));
        }
        // -------- 2) CANCELED（主动撤单/减少） --------
        if ((cmd.command == OrderCommandType.CANCEL_ORDER || cmd.command == OrderCommandType.REDUCE_ORDER) && cmd.resultCode == CommandResultCode.SUCCESS
            && first != null && first.eventType == MatcherEventType.REDUCE) {
            tradeEventsHandler.spotExecutionReport(SpotExecutionReport.reduceOrder(cmd, seq, spec, first));
            return; // 取消类指令处理完毕
        }
        // 没有成交事件直接返回
        if (first == null)
            return;
        // -------- 3) TRADE（逐笔撮合：taker + maker 各发一条） --------
        int tradeIndex = 0;
        for (MatcherTradeEvent ev = first; ev != null; ev = ev.nextEvent) {
            if (ev.eventType != MatcherEventType.TRADE)
                continue;
            tradeEventsHandler.spotExecutionReport(SpotExecutionReport.tradeTaker(cmd, seq, spec, ev, tradeIndex));
            tradeEventsHandler.spotExecutionReport(SpotExecutionReport.tradeMaker(cmd, seq, spec, ev, tradeIndex));
            tradeIndex++;
        }
    }

    private void sendFuturesExecutionReport(OrderCommand cmd, long seq, CoreSymbolSpecification spec) {
        final MatcherTradeEvent first = cmd.matcherEvent;
        UserProfile userProfile = userProfileServices.get(shardIdOfUid(cmd.uid)).getUserProfile(cmd.uid);
        if (cmd.command == OrderCommandType.PLACE_ORDER && cmd.resultCode == CommandResultCode.SUCCESS) {
            tradeEventsHandler.futuresExecutionReport(FuturesExecutionReport.placeOrder(cmd, seq, spec, userProfile));
        }
        if (first != null && first.eventType == MatcherEventType.REJECT) {
            tradeEventsHandler.futuresExecutionReport(FuturesExecutionReport.rejectOrder(cmd, seq, spec, userProfile));
        }
        if ((cmd.command == OrderCommandType.CANCEL_ORDER || cmd.command == OrderCommandType.REDUCE_ORDER) && cmd.resultCode == CommandResultCode.SUCCESS
            && first != null && first.eventType == MatcherEventType.REDUCE) {
            tradeEventsHandler.futuresExecutionReport(FuturesExecutionReport.reduceOrder(cmd, seq, spec, userProfile, first));
            return;
        }
        // 没有成交事件直接返回
        if (first == null)
            return;

        int tradeIndex = 0;
        for (MatcherTradeEvent ev = first; ev != null; ev = ev.nextEvent) {
            if (ev.eventType != MatcherEventType.TRADE)
                continue;
            tradeEventsHandler.futuresExecutionReport(FuturesExecutionReport.tradeTaker(cmd, seq, spec, userProfile, ev, tradeIndex));
            UserProfile makerProfile = userProfileServices.get(shardIdOfUid(ev.matchedOrderUid)).getUserProfile(ev.matchedOrderUid);
            tradeEventsHandler.futuresExecutionReport(FuturesExecutionReport.tradeMaker(cmd, seq, spec, makerProfile, ev, tradeIndex));
            tradeIndex++;
        }
    }

    private boolean isReportableCommand(OrderCommandType commandType) {
        switch (commandType) {
            // 纯订单生命周期
            case PLACE_ORDER:
            case CANCEL_ORDER:
            case MOVE_ORDER:
            case REDUCE_ORDER:
                // 特殊订单，但也是调用了orderBook.newOrder
            case CLOSE_POSITION:
            case FORCE_LIQUIDATION:
                return true;
            default:
                return false;
        }
    }

    private void sendFundEvents(OrderCommand cmd, long seq) {
        FundEvent event = cmd.takerFundEvents;
        int index = 0;
        while (event != null) {
            if (!event.processed) {
                event.processed = true;
                long uniId = ITradeEventsHandler.ExecutionIdGenerator.buildTradeExecId(seq, index, false);
                fundEventsHandler.fundEventReport(FundEventReport.fromFundEvent(event, uniId));
            }
            event = event.nextEvent;
            index++;
        }
        if (cmd.makerFundEventsByShard != null) {
            for (FundEvent shardHead : cmd.makerFundEventsByShard) {
                FundEvent e = shardHead;
                while (e != null) {
                    if (!e.processed) {
                        e.processed = true;
                        long uniId = ITradeEventsHandler.ExecutionIdGenerator.buildTradeExecId(seq, index, true);
                        fundEventsHandler.fundEventReport(FundEventReport.fromFundEvent(e, uniId));
                    }
                    e = e.nextEvent;
                }
            }
        }
    }

    private void sendMarketData(OrderCommand cmd) {
        final L2MarketData marketData = cmd.marketData;
        if (marketData != null) {
            final List<ITradeEventsHandler.OrderBookRecord> asks = new ArrayList<>(marketData.askSize);
            for (int i = 0; i < marketData.askSize; i++) {
                asks.add(new ITradeEventsHandler.OrderBookRecord(marketData.askPrices[i], marketData.askVolumes[i], (int)marketData.askOrders[i]));
            }

            final List<ITradeEventsHandler.OrderBookRecord> bids = new ArrayList<>(marketData.bidSize);
            for (int i = 0; i < marketData.bidSize; i++) {
                bids.add(new ITradeEventsHandler.OrderBookRecord(marketData.bidPrices[i], marketData.bidVolumes[i], (int)marketData.bidOrders[i]));
            }

            tradeEventsHandler.orderBook(new ITradeEventsHandler.OrderBook(cmd.symbol, asks, bids, cmd.timestamp));
        }
    }

    public int shardIdOfUid(long uid) {
        int shardMask = numShards - 1;
        return (int)(uid & shardMask);
    }

    public synchronized void saveUserProfileService(int shardId, UserProfileService userProfileService) {
        userProfileServices.put(shardId, userProfileService);
    }
}
