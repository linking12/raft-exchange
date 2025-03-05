package com.binance.raftexchange.server.exchange.eventsProcessor;

import com.binance.platform.eventbus.common.model.BinanceEvent;
import com.binance.platform.eventbus.zeromq.ZMQEventBusListener;
import exchange.core2.core.IEventsHandler;

public class EventBusSender implements IEventsHandler {

    static EventBusSender SENDER;

    private final ZMQEventBusListener zmqEventBusListener;

    private final String topic;

    public EventBusSender(ZMQEventBusListener zmqEventBusListener, String topic) {
        this.zmqEventBusListener = zmqEventBusListener;
        this.topic = topic;
    }

    @Override
    public void commandResult(ApiCommandResult commandResult) {
        zmqEventBusListener.handle(
                BinanceEvent.builder()
                        .eventType(topic)
                        .eventName("ApiCommand")
                        .userId(9173325L) //看文档说必须传入 我随便写一个吧
                        .data(commandResult)
                        .build()
        );
    }

    @Override
    public void tradeEvent(TradeEvent tradeEvent) {
        zmqEventBusListener.handle(
                BinanceEvent.builder()
                        .eventType(topic)
                        .eventName("tradeEvent")
                        .userId(9173325L) //看文档说必须传入 我随便写一个吧
                        .data(tradeEvent)
                        .build()
        );
    }

    @Override
    public void reduceEvent(ReduceEvent reduceEvent) {
        System.out.println("Reduce event: " + reduceEvent);
    }

    @Override
    public void rejectEvent(RejectEvent rejectEvent) {
        System.out.println("Reject event: " + rejectEvent);
    }

    @Override
    public void orderBook(OrderBook orderBook) {
        zmqEventBusListener.handle(
                BinanceEvent.builder()
                        .eventType(topic)
                        .eventName("orderBook")
                        .userId(9173325L) //看文档说必须传入 我随便写一个吧
                        .data(orderBook)
                        .build()
        );
    }

    public static EventBusSender getInstance() {
        return SENDER;
    }
}
