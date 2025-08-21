package com.binance.raftexchange.client;

import com.binance.raftexchange.stubs.CoreSymbolSpecification;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import com.binance.raftexchange.stubs.response.L2MarketData;
import com.binance.raftexchange.stubs.response.MatcherEventType;
import com.binance.raftexchange.stubs.response.MatcherTradeEvent;
import com.binance.raftexchange.stubs.response.OrderCommand;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

import static com.binance.raftexchange.client.ExchangeApiHelper.longToDouble;

@Getter
public class CommandResultView {

    private final CommandResultCode resultCode;

    private MatcherTradeEventView matcherEvent;

    private L2MarketDataView marketData;

    private CommandResultView(CommandResultCode resultCode) {
        this.resultCode = resultCode;
    }

    public static CommandResultView build(CommandResult commandResult, ExchangeMetadataManager metadataManager) {
        if (commandResult.hasResultCode()) {
            return new CommandResultView(commandResult.getResultCode());
        }
        if (commandResult.hasOrderCommand()) {
            OrderCommand orderCommand = commandResult.getOrderCommand();
            CommandResultView view = new CommandResultView(orderCommand.getResultCode());
            if (orderCommand.hasMatcherEvent()) {
                view.matcherEvent = toMatcherEventView(orderCommand.getMatcherEvent());
            }
            if (orderCommand.hasMarketData()) {
                CoreSymbolSpecification spec = metadataManager.getSymbolSpec(orderCommand.getSymbol());
                view.marketData = toMarketDataView(orderCommand.getMarketData(), spec);
            }
            return view;
        }
        throw new IllegalStateException("Invalid CommandResult: neither resultCode nor orderCommand present");
    }

    private static MatcherTradeEventView toMatcherEventView(MatcherTradeEvent head) {
        if (head == null) {
            return null;
        }
        MatcherTradeEvent cur = head;
        MatcherTradeEventView headView = null;
        MatcherTradeEventView tailView = null;
        do {
            MatcherTradeEventView view = new MatcherTradeEventView();
            view.eventType = cur.getEventType();
            view.activeOrderCompleted = cur.getActiveOrderCompleted();
            view.matchedOrderId = cur.getMatchedOrderId();
            view.matchedOrderUid = cur.getMatchedOrderUid();
            view.matchedOrderCompleted = cur.getMatchedOrderCompleted();
            long baseScaleK = cur.getBaseScaleK();
            long quoteScaleK = cur.getQuoteScaleK();
            view.price = longToDouble(cur.getPrice(), quoteScaleK);
            view.size = longToDouble(cur.getSize(), baseScaleK);
            view.bidderHoldPrice = longToDouble(cur.getBidderHoldPrice(), quoteScaleK);

            if (headView == null) {
                headView = view;
            } else {
                tailView.nextEvent = view;
            }
            tailView = view;

            cur = cur.hasNextEvent() ? cur.getNextEvent() : null;
        } while (cur != null);
        return headView;
    }

    private static L2MarketDataView toMarketDataView(L2MarketData marketData, CoreSymbolSpecification spec) {
        L2MarketDataView view = new L2MarketDataView();
        view.askSize = marketData.getAskSizes();
        view.bidSize = marketData.getBidSizes();
        long baseScaleK = spec.getBaseScaleK();
        long quoteScaleK = spec.getQuoteScaleK();
        view.askPrices = marketData.getAskPricesList().stream().map(a -> longToDouble(a, quoteScaleK)).collect(Collectors.toList());
        view.askVolumes = marketData.getAskVolumesList().stream().map(a -> longToDouble(a, baseScaleK)).collect(Collectors.toList());
        view.askOrders = marketData.getAskOrdersList();
        view.bidPrices = marketData.getBidPricesList().stream().map(b -> longToDouble(b, quoteScaleK)).collect(Collectors.toList());
        view.bidVolumes = marketData.getBidVolumesList().stream().map(b ->longToDouble(b, baseScaleK)).collect(Collectors.toList());
        view.bidOrders = marketData.getBidOrdersList();
        return view;
    }

    @Getter
    public static class MatcherTradeEventView {
        private MatcherEventType eventType;
        private boolean activeOrderCompleted;
        private long matchedOrderId;
        private long matchedOrderUid;
        private boolean matchedOrderCompleted;
        private double price;
        private double size;
        private double bidderHoldPrice;
        private MatcherTradeEventView nextEvent;
    }

    @Getter
    public static class L2MarketDataView {
        private int askSize;
        private int bidSize;
        private List<Double> askPrices;
        private List<Double> askVolumes;
        private List<Long> askOrders;
        private List<Double> bidPrices;
        private List<Double> bidVolumes;
        private List<Long> bidOrders;
    }

}
