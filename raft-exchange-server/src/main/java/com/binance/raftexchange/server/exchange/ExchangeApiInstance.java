package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.InitialStateConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import com.binance.raftexchange.server.exchange.snapshot.JRaftAdaptiveSerializationProcessor;

import java.util.concurrent.atomic.AtomicReference;

public class ExchangeApiInstance {

    private static final AtomicReference<ExchangeCore> INSTANCE = new AtomicReference<>();

    private ExchangeApiInstance() {
    }

    public static void snapshotStart(long snapshotId) {
        SimpleEventsProcessor eventsProcessor = new SimpleEventsProcessor(IEventsHandlerByKafka.getInstance());
        SerializationConfiguration serializationCfg = SerializationConfiguration.builder()
                .enableJournaling(false)
                .serializationProcessorFactory(JRaftAdaptiveSerializationProcessor::new)
                .build();
        ExchangeConfiguration conf = ExchangeConfiguration.defaultBuilder()
                // exchangeId没用到，baseSeq只有journal才会用到
                .initStateCfg(InitialStateConfiguration.fromSnapshotOnly(null, snapshotId, 0))
                .serializationCfg(serializationCfg)
                .build();
        ExchangeCore exchangeCore =
            ExchangeCore.builder().resultsConsumer(eventsProcessor).exchangeConfiguration(conf).build();
        exchangeCore.startup();
        ExchangeCore old = INSTANCE.getAndSet(exchangeCore);
        if (old != null) {
            old.shutdown();
        }
    }

    public static ExchangeApi exchangeApi() {
        if (INSTANCE.get() == null) {
            snapshotStart(0);
        }
        return INSTANCE.get().getApi();
    }

}
