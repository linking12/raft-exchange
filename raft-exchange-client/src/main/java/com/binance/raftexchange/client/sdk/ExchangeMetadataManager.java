package com.binance.raftexchange.client.sdk;

import com.binance.raftexchange.client.Api.ExchangeClient;
import com.binance.raftexchange.stubs.CoreCurrencySpecification;
import com.binance.raftexchange.stubs.CoreSymbolSpecification;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class ExchangeMetadataManager {

    private final ConcurrentMap<Integer, CoreSymbolSpecification> symbolCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, CoreCurrencySpecification> currencyCache = new ConcurrentHashMap<>();

    private final ExchangeClient exchangeClient;
    private final AtomicInteger reqIdGen;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> {
                Thread t = new Thread(r, "exchange-metadata-refresh");
                t.setDaemon(true);
                return t;
            }
    );

    public ExchangeMetadataManager(ExchangeClient exchangeClient, AtomicInteger reqIdGen) {
        this.exchangeClient = exchangeClient;
        this.reqIdGen = reqIdGen;
        refreshAll();
        scheduler.scheduleAtFixedRate(this::refreshAll, 5, 5, TimeUnit.MINUTES);
    }

    public void refreshAll() {
        try {
            exchangeClient.symbolCurrencyReport(reqIdGen.getAndIncrement())
                    .thenAccept(result -> {
                        symbolCache.putAll(result.getSymbolSpecsMap());
                        currencyCache.putAll(result.getCurrencySpecsMap());
                    });
        } catch (Exception ex) {
            log.warn("refresh symbol&currency failed.", ex);
        }
    }

    public CoreSymbolSpecification getSymbolSpec(int symbolId) {
        CoreSymbolSpecification spec = symbolCache.get(symbolId);
        if (spec == null) {
            throw new IllegalArgumentException("unknow symbol: " + symbolId);
        }
        return spec;
    }

    public CoreCurrencySpecification getCurrencySpec(int currencyId) {
        CoreCurrencySpecification currencySpec = currencyCache.get(currencyId);
        if (currencySpec == null) {
            throw new IllegalArgumentException("unknow currency:" + currencyId);
        }
        return currencySpec;
    }

    public boolean symbolExists(int symbolId) {
        return symbolCache.containsKey(symbolId);
    }

    public boolean currencyExists(int currencyId) {
        return currencyCache.containsKey(currencyId);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

}