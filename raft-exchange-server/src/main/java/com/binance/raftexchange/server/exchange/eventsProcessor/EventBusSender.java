package com.binance.raftexchange.server.exchange.eventsProcessor;

import com.binance.platform.eventbus.common.model.BinanceEvent;
import com.binance.platform.eventbus.zeromq.ZMQEventBusListener;
import exchange.core2.core.IEventsHandler;

public class EventBusSender implements IEventsHandler {

    static EventBusSender SENDER;

    private final ZMQEventBusListener zmqEventBusListener;

    private final String topic;

    private final String appName;

    public EventBusSender(ZMQEventBusListener zmqEventBusListener, String topic, String appName) {
        this.zmqEventBusListener = zmqEventBusListener;
        this.topic = topic;
        this.appName = appName;
    }

    @Override
    public void commandResult(ApiCommandResult commandResult) {
        zmqEventBusListener.handle(
                BinanceEvent.builder()
                        .eventType(topic)
                        .appName(appName)
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
                        .appName(appName)
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
                        .appName(appName)
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
