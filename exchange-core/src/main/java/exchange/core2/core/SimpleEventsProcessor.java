package exchange.core2.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ObjLongConsumer;

import org.agrona.collections.MutableBoolean;
import org.agrona.collections.MutableLong;
import org.agrona.collections.MutableReference;

import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.api.ApiAddUser;
import exchange.core2.core.common.api.ApiAdjustUserBalance;
import exchange.core2.core.common.api.ApiBinaryDataCommand;
import exchange.core2.core.common.api.ApiCancelOrder;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.api.ApiMoveOrder;
import exchange.core2.core.common.api.ApiOrderBookRequest;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.ApiReduceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Getter
@Slf4j
public class SimpleEventsProcessor implements ObjLongConsumer<OrderCommand> {

    private final IEventsHandler eventsHandler;

    @Override
    public void accept(OrderCommand cmd, long seq) {
        try {
            sendCommandResult(cmd, seq);
            sendTradeEvents(cmd);
            sendOrderFundEvents(cmd);
            sendMarketData(cmd);
        } catch (Exception ex) {
            log.error("Exception when handling command result data", ex);
        }
    }

    // 发送order下面的fundEvent，主要是资金冻结及恢复
    private void sendOrderFundEvents(OrderCommand cmd) {
        sendFundEvents(cmd.fundEvent);
    }

    private void sendTradeEvents(OrderCommand cmd) {
        final MatcherTradeEvent firstEvent = cmd.matcherEvent;
        if (firstEvent == null) {
            return;
        }

        if (firstEvent.eventType == MatcherEventType.REDUCE) {

            final IEventsHandler.ReduceEvent evt = new IEventsHandler.ReduceEvent(cmd.symbol, firstEvent.size,
                firstEvent.activeOrderCompleted, firstEvent.price, cmd.orderId, cmd.uid, cmd.timestamp);

            eventsHandler.reduceEvent(evt);

            if (firstEvent.nextEvent != null) {
                throw new IllegalStateException("Only single REDUCE event is expected");
            }

            return;
        }

        sendTradeEvent(cmd);
    }

    private void sendFundEvents(FundEvent fundEvent) {
        if (fundEvent == null) {
            return;
        }
        final IEventsHandler.FundsEvent evt = new IEventsHandler.FundsEvent(fundEvent.orderId, fundEvent.uid,
            fundEvent.currency, fundEvent.free, fundEvent.loked, fundEvent.positionDelta);
        eventsHandler.fundsEvent(evt);
    }

    private void sendTradeEvent(OrderCommand cmd) {

        final MutableBoolean takerOrderCompleted = new MutableBoolean(false);
        final MutableLong mutableLong = new MutableLong(0L);
        final List<IEventsHandler.Trade> trades = new ArrayList<>();

        final MutableReference<IEventsHandler.RejectEvent> rejectEvent = new MutableReference<>(null);

        cmd.processMatcherEvents(evt -> {

            if (evt.eventType == MatcherEventType.TRADE) {

                final IEventsHandler.Trade trade = new IEventsHandler.Trade(evt.matchedOrderId, evt.matchedOrderUid,
                    evt.matchedOrderCompleted, evt.price, evt.size);

                trades.add(trade);
                mutableLong.value += evt.size;

                if (evt.activeOrderCompleted) {
                    takerOrderCompleted.value = true;
                }

            } else if (evt.eventType == MatcherEventType.REJECT) {

                rejectEvent.set(new IEventsHandler.RejectEvent(cmd.symbol, evt.size, evt.price, cmd.orderId, cmd.uid,
                    cmd.timestamp));
            }
            // 发送trade下面的fundEvent，主要是资金转移
            sendFundEvents(evt.fundEvent);
        });

        if (!trades.isEmpty()) {

            final IEventsHandler.TradeEvent evt = new IEventsHandler.TradeEvent(cmd.symbol, mutableLong.value,
                cmd.orderId, cmd.uid, cmd.action, takerOrderCompleted.value, cmd.timestamp, trades);

            eventsHandler.tradeEvent(evt);
        }

    }

    private void sendMarketData(OrderCommand cmd) {
        final L2MarketData marketData = cmd.marketData;
        if (marketData != null) {
            final List<IEventsHandler.OrderBookRecord> asks = new ArrayList<>(marketData.askSize);
            for (int i = 0; i < marketData.askSize; i++) {
                asks.add(new IEventsHandler.OrderBookRecord(marketData.askPrices[i], marketData.askVolumes[i],
                    (int)marketData.askOrders[i]));
            }

            final List<IEventsHandler.OrderBookRecord> bids = new ArrayList<>(marketData.bidSize);
            for (int i = 0; i < marketData.bidSize; i++) {
                bids.add(new IEventsHandler.OrderBookRecord(marketData.bidPrices[i], marketData.bidVolumes[i],
                    (int)marketData.bidOrders[i]));
            }

            eventsHandler.orderBook(new IEventsHandler.OrderBook(cmd.symbol, asks, bids, cmd.timestamp));
        }
    }

    private void sendCommandResult(OrderCommand cmd, long seq) {

        switch (cmd.command) {
            case PLACE_ORDER:
                sendApiCommandResult(new ApiPlaceOrder(cmd.price, cmd.size, cmd.orderId, cmd.action, cmd.orderType,
                    cmd.uid, cmd.symbol, cmd.userCookie, cmd.reserveBidPrice), cmd.resultCode, cmd.timestamp, seq);
                break;

            case MOVE_ORDER:
                sendApiCommandResult(new ApiMoveOrder(cmd.orderId, cmd.price, cmd.uid, cmd.symbol), cmd.resultCode,
                    cmd.timestamp, seq);
                break;

            case CANCEL_ORDER:
                sendApiCommandResult(new ApiCancelOrder(cmd.orderId, cmd.uid, cmd.symbol), cmd.resultCode,
                    cmd.timestamp, seq);
                break;

            case REDUCE_ORDER:
                sendApiCommandResult(new ApiReduceOrder(cmd.orderId, cmd.uid, cmd.symbol, cmd.size), cmd.resultCode,
                    cmd.timestamp, seq);
                break;

            case ADD_USER:
                sendApiCommandResult(new ApiAddUser(cmd.uid), cmd.resultCode, cmd.timestamp, seq);
                break;

            case BALANCE_ADJUSTMENT:
                sendApiCommandResult(new ApiAdjustUserBalance(cmd.uid, cmd.symbol, cmd.price, cmd.orderId),
                    cmd.resultCode, cmd.timestamp, seq);
                break;

            case BINARY_DATA_COMMAND:
                if (cmd.resultCode != CommandResultCode.ACCEPTED) {
                    sendApiCommandResult(new ApiBinaryDataCommand(cmd.userCookie, null), cmd.resultCode, cmd.timestamp,
                        seq);
                }
                break;

            case ORDER_BOOK_REQUEST:
                sendApiCommandResult(new ApiOrderBookRequest(cmd.symbol, (int)cmd.size), cmd.resultCode, cmd.timestamp,
                    seq);
                break;

            // TODO add rest of commands

        }

    }

    private void sendApiCommandResult(ApiCommand cmd, CommandResultCode resultCode, long timestamp, long seq) {
        cmd.timestamp = timestamp;
    }
}
