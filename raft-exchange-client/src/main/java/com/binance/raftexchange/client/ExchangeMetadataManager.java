package com.binance.raftexchange.client;

import com.binance.raftexchange.client.grpc.ExchangeClient;
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

    private static final long REFRESH_DEBOUNCE_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

    private final ConcurrentMap<Integer, CoreSymbolSpecification> symbolCache = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, CoreCurrencySpecification> currencyCache = new ConcurrentHashMap<>();

    private final ExchangeClient exchangeClient;
    private final AtomicInteger reqIdGen;

    private final Object refreshLock = new Object();
    private volatile long lastRefreshNanos = 0L;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "exchange-metadata-refresh");
        t.setDaemon(true);
        return t;
    });

    public ExchangeMetadataManager(ExchangeClient exchangeClient, AtomicInteger reqIdGen) {
        this.exchangeClient = exchangeClient;
        this.reqIdGen = reqIdGen;
        refreshAll();
        scheduler.scheduleAtFixedRate(this::refreshAll, 5, 5, TimeUnit.MINUTES);
    }

    private void refreshAll() {
        try {
            exchangeClient.symbolCurrencyReport(reqIdGen.getAndIncrement()).thenAccept(result -> {
                symbolCache.putAll(result.getSymbolSpecsMap());
                currencyCache.putAll(result.getCurrencySpecsMap());
            }).get(2, TimeUnit.SECONDS);
        } catch (Exception ex) {
            log.warn("refresh symbol&currency failed.", ex);
        }
    }

    // 只对 miss-driven refresh 做去抖；初始构造和周期刷新不更新 lastRefreshNanos，
    // 否则启动几十毫秒后查新 symbol 会被误挡
    private void triggerRefreshOnMiss() {
        if (System.nanoTime() - lastRefreshNanos < REFRESH_DEBOUNCE_NANOS) {
            return;
        }
        synchronized (refreshLock) {
            if (System.nanoTime() - lastRefreshNanos < REFRESH_DEBOUNCE_NANOS) {
                return;
            }
            try {
                refreshAll();
            } finally {
                lastRefreshNanos = System.nanoTime();
            }
        }
    }

    public void addSymbolSpec(CoreSymbolSpecification spec) {
        if (spec == null || spec.getSymbolId() <= 0) {
            throw new IllegalArgumentException("Invalid symbol specification");
        }
        symbolCache.put(spec.getSymbolId(), spec);
    }

    public void addCurrencySpec(CoreCurrencySpecification currencySpec) {
        if (currencySpec == null || currencySpec.getId() <= 0) {
            throw new IllegalArgumentException("Invalid currency specification");
        }
        currencyCache.put(currencySpec.getId(), currencySpec);
    }

    public CoreSymbolSpecification getSymbolSpec(int symbolId) {
        CoreSymbolSpecification spec = symbolCache.get(symbolId);
        if (spec == null) {
            triggerRefreshOnMiss();
            spec = symbolCache.get(symbolId);
        }
        if (spec == null) {
            throw new IllegalArgumentException("unknow symbol: " + symbolId);
        }
        return spec;
    }

    public CoreCurrencySpecification getCurrencySpec(int currencyId) {
        CoreCurrencySpecification currencySpec = currencyCache.get(currencyId);
        if (currencySpec == null) {
            triggerRefreshOnMiss();
            currencySpec = currencyCache.get(currencyId);
        }
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