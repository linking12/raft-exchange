package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.server.exchange.eventsProcessor.KafkaSender;
import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.config.ExchangeConfiguration;

public class ExchangeApiInstance {

    private final ExchangeApi exchangeApi;

    private static final ExchangeApiInstance INSTANCE = new ExchangeApiInstance();

    private ExchangeApiInstance() {
        SimpleEventsProcessor eventsProcessor = new SimpleEventsProcessor(KafkaSender.getInstance());
        ExchangeConfiguration conf = ExchangeConfiguration.defaultBuilder().build();
        ExchangeCore exchangeCore =
            ExchangeCore.builder().resultsConsumer(eventsProcessor).exchangeConfiguration(conf).build();
        exchangeCore.startup();
        ExchangeApi api = exchangeCore.getApi();
        this.exchangeApi = api;
    }

    public ExchangeApi getExchangeApi() {
        return this.exchangeApi;
    }

    public static ExchangeApi exchangeApi() {
        return INSTANCE.getExchangeApi();
    }

}
