package com.binance.raftexchange.server.raft;

import java.io.DataInput;
import java.io.DataOutput;

import org.jgroups.raft.StateMachine;
import org.jgroups.util.ByteArrayDataInputStream;
import org.jgroups.util.ByteArrayDataOutputStream;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.IEventsHandler;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.ExchangeConfiguration;

public class ExchangeStateMachine implements StateMachine {

    private final ExchangeApi api;

    public ExchangeStateMachine() {
        SimpleEventsProcessor eventsProcessor = new SimpleEventsProcessor(new IEventsHandler() {
            @Override
            public void tradeEvent(TradeEvent tradeEvent) {
                System.out.println("Trade event: " + tradeEvent);
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
            public void commandResult(ApiCommandResult commandResult) {
                System.out.println("Command result: " + commandResult);
            }

            @Override
            public void orderBook(OrderBook orderBook) {
                System.out.println("OrderBook event: " + orderBook);
            }
        });
        ExchangeConfiguration conf = ExchangeConfiguration.defaultBuilder().build();
        ExchangeCore exchangeCore = ExchangeCore.builder().resultsConsumer(eventsProcessor).exchangeConfiguration(conf).build();
        exchangeCore.startup();
        ExchangeApi api = exchangeCore.getApi();
        this.api = api;
    }

    @Override
    public byte[] apply(byte[] data, int offset, int length, boolean serialize_response) throws Exception {
        ByteArrayDataInputStream in = new ByteArrayDataInputStream(data, offset, length);
        // 构造出ApiCommand出来
        ApiCommand apiCommand = null;
        CommandResultCode commandResultCode = api.submitCommandAsync(apiCommand).get();
        ByteArrayDataOutputStream out = new ByteArrayDataOutputStream();
        if (commandResultCode != null) {
            // out.writeUTF(value);
        }
        return out.buffer();

    }

    @Override
    public void readContentFrom(DataInput in) throws Exception {

    }

    @Override
    public void writeContentTo(DataOutput out) throws Exception {

    }

}
