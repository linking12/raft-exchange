package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.core.processors.support.SimpleScheduledService;
import com.binance.raftexchange.server.exchange.snapshot.MemorySerializationProcessor;
import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.server.raft.RoleChangeEventbus;

public class ExchangeApiInstance {

    private final ExchangeApi exchangeApi;
    private final ExchangeCore exchangeCore;

    private static final ExchangeApiInstance INSTANCE = new ExchangeApiInstance();

    private ExchangeApiInstance() {
        SimpleEventsProcessor eventsProcessor = new SimpleEventsProcessor(IEventsHandlerByKafka.getInstance(), IEventsHandlerByKafka.getInstance());
        SerializationConfiguration serializationCfg =
            SerializationConfiguration.builder().enableJournaling(false).serializationProcessorFactory(MemorySerializationProcessor::new).build();
        ExchangeConfiguration conf = ExchangeConfiguration.defaultBuilder().serializationCfg(serializationCfg).build();
        exchangeCore = ExchangeCore.builder().resultsConsumer(eventsProcessor).exchangeConfiguration(conf).build();
        exchangeCore.startup();
        exchangeApi = exchangeCore.getApi();
        RoleChangeEventbus.INSTANCE.registerListener(nodeType -> {
            if (nodeType == RaftNode.NodeType.LEADER) {
                exchangeCore.getLiquidationEngines().forEach(SimpleScheduledService::start);
            } else {
                exchangeCore.getLiquidationEngines().forEach(SimpleScheduledService::stop);
            }
        });
    }

    public static ExchangeApi exchangeApi() {
        return INSTANCE.exchangeApi;
    }

    public static ExchangeCore exchangeCore() {
        return INSTANCE.exchangeCore;
    }

}
