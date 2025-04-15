package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import com.binance.raftexchange.server.exchange.snapshot.MemorySerializationProcessor;

public class ExchangeApiInstance {

    private final ExchangeApi exchangeApi;

    private static final ExchangeApiInstance INSTANCE = new ExchangeApiInstance();

    private ExchangeApiInstance() {
        SimpleEventsProcessor eventsProcessor = new SimpleEventsProcessor(IEventsHandlerByKafka.getInstance(), IEventsHandlerByKafka.getInstance());
        SerializationConfiguration serializationCfg =
            SerializationConfiguration.builder().enableJournaling(false).serializationProcessorFactory(MemorySerializationProcessor::new).build();
        ExchangeConfiguration conf = ExchangeConfiguration.defaultBuilder().serializationCfg(serializationCfg).build();
        ExchangeCore exchangeCore = ExchangeCore.builder().resultsConsumer(eventsProcessor).exchangeConfiguration(conf).build();
        exchangeCore.startup();
        exchangeApi = exchangeCore.getApi();
    }

    public static ExchangeApi exchangeApi() {
        return INSTANCE.exchangeApi;
    }

}
