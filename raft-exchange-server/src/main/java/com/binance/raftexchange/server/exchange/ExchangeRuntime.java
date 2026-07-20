package com.binance.raftexchange.server.exchange;

import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka;
import com.binance.raftexchange.server.exchange.snapshot.MemorySerializationProcessor;

import exchange.core2.core.ExchangeCore;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.api.ApiSystemLiquidationNotify;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.SerializationConfiguration;
import exchange.core2.core.processors.liquidation.LiquidationCommandSubmitter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ExchangeRuntime {

    private final ExchangeCalls exchangeCalls;
    private final ExchangeCore exchangeCore;

    public ExchangeRuntime(IEventsHandlerByKafka eventsHandler) {
        SimpleEventsProcessor eventsProcessor = new SimpleEventsProcessor(eventsHandler, eventsHandler);
        SerializationConfiguration serializationCfg = SerializationConfiguration.builder().enableJournaling(false)
            .serializationProcessorFactory(MemorySerializationProcessor::new).build();
        ExchangeConfiguration conf = ExchangeConfiguration.defaultBuilder().serializationCfg(serializationCfg).build();
        exchangeCore = ExchangeCore.builder().resultsConsumer(eventsProcessor).exchangeConfiguration(conf).build();
        exchangeCore.startup();
        exchangeCalls = new ExchangeCalls(exchangeCore.getApi());
    }

    public ExchangeCalls exchangeCalls() {
        return exchangeCalls;
    }

    public ExchangeCore exchangeCore() {
        return exchangeCore;
    }

    public void overrideLiquidationCommandSubmitter(BooleanSupplier isLeader,
        BiConsumer<byte[], Consumer<Throwable>> commit) {
        LiquidationCommandSubmitter submitter = (cmd, onApplied) -> {
            if (!isLeader.getAsBoolean()) {
                if (onApplied != null)
                    onApplied.run();
                return;
            }
            if (cmd instanceof ApiSystemLiquidationNotify) {
                // 下游事件不改 RiskEngine 状态，无需 raft；走 leader 本地 ringbuffer
                exchangeCalls.submitCommand(cmd);
                if (onApplied != null)
                    onApplied.run();
                return;
            }
            commit.accept(ApiCommandConverters.liquidationCmdToRaftLog(cmd, cmd.timestamp), err -> {
                if (err != null) {
                    log.warn("Liquidation raft consensus failed: cmd={}", cmd, err);
                }
                if (onApplied != null)
                    onApplied.run();
            });
        };
        exchangeCore.getLiquidationEngines().forEach(le -> le.setCommandSubmitter(submitter));
    }
}
