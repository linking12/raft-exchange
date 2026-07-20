package com.binance.raftexchange.client;

import com.binance.raftexchange.client.grpc.ExchangeClient;
import com.binance.raftexchange.stubs.CoreCurrencySpecification;
import com.binance.raftexchange.stubs.CoreSymbolSpecification;
import com.binance.raftexchange.stubs.report.SymbolCurrencyReportResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExchangeMetadataManager 单测。重点：
 * <ul>
 * <li>构造时同步刷一次（initial refresh）</li>
 * <li>cache miss 时同步触发一次 refresh，仍 miss 才抛</li>
 * <li>500ms 去抖：短时间内连续 miss 只放第一次 RPC 穿透</li>
 * <li>并发 miss 单飞：同时多个线程 miss 也只放一次 RPC</li>
 * <li>addSymbolSpec/addCurrencySpec 直接灌缓存、不触发 RPC</li>
 * </ul>
 *
 * <p>
 * {@link ExchangeClient} 用 {@code Mockito.mock(...)} 直接绕过它的真实构造（不需要网络）。 所有 spec 都是
 * {@code com.binance.raftexchange.stubs.*} 下的 PB 类型， 不要混进 {@code exchange.core2.core.common.*} 的引擎类型。
 * </p>
 */
class ExchangeMetadataManagerTest {

    private static final long DEBOUNCE_BUFFER_MS = 700L; // 比 manager 里 500ms 大留 buffer

    private ExchangeClient exchangeClient;
    private AtomicReference<SymbolCurrencyReportResult> nextResponse;
    private ExchangeMetadataManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    /** 用初始内容构造 manager。后续 RPC 返回值通过 {@code nextResponse.set(...)} 改。 */
    private void initManagerWith(SymbolCurrencyReportResult initial) {
        exchangeClient = mock(ExchangeClient.class);
        nextResponse = new AtomicReference<>(initial);
        when(exchangeClient.symbolCurrencyReport(anyInt()))
            .thenAnswer(inv -> CompletableFuture.completedFuture(nextResponse.get()));
        manager = new ExchangeMetadataManager(exchangeClient, new AtomicInteger(1));
    }

    private static SymbolCurrencyReportResult emptyReport() {
        return SymbolCurrencyReportResult.getDefaultInstance();
    }

    private static SymbolCurrencyReportResult reportWithSymbol(int symbolId) {
        return SymbolCurrencyReportResult.newBuilder()
            .putSymbolSpecs(symbolId, CoreSymbolSpecification.newBuilder().setSymbolId(symbolId).build()).build();
    }

    private static SymbolCurrencyReportResult reportWithCurrency(int currencyId) {
        return SymbolCurrencyReportResult.newBuilder().putCurrencySpecs(currencyId,
            CoreCurrencySpecification.newBuilder().setId(currencyId).setName("X").setDigit(6).build()).build();
    }

    // ---------- 初始刷新 + 命中 ----------

    @Test
    @DisplayName("构造时同步拉一次 SymbolCurrencyReport，缓存被填充")
    void constructorTriggersInitialRefresh() {
        SymbolCurrencyReportResult initial = SymbolCurrencyReportResult.newBuilder()
            .putSymbolSpecs(11, CoreSymbolSpecification.newBuilder().setSymbolId(11).build())
            .putCurrencySpecs(2, CoreCurrencySpecification.newBuilder().setId(2).setName("USDT").setDigit(6).build())
            .build();
        initManagerWith(initial);

        // 初始 refresh 1 次
        verify(exchangeClient, times(1)).symbolCurrencyReport(anyInt());

        CoreSymbolSpecification spec = manager.getSymbolSpec(11);
        CoreCurrencySpecification cur = manager.getCurrencySpec(2);

        assertNotNull(spec);
        assertEquals(11, spec.getSymbolId());
        assertNotNull(cur);
        assertEquals(2, cur.getId());
        assertEquals("USDT", cur.getName());

        // 命中后不再触发 RPC
        verify(exchangeClient, times(1)).symbolCurrencyReport(anyInt());
    }

    // ---------- on-miss 触发刷新 ----------

    @Test
    @DisplayName("getSymbolSpec miss：触发一次 refresh，refresh 拿到的就是结果")
    void getSymbolSpecMissTriggersRefresh() {
        initManagerWith(emptyReport()); // RPC = 1
        verify(exchangeClient, times(1)).symbolCurrencyReport(anyInt());

        nextResponse.set(reportWithSymbol(11)); // 下一次 refresh 把 11 加上
        CoreSymbolSpecification spec = manager.getSymbolSpec(11); // miss → refresh → hit

        assertNotNull(spec);
        assertEquals(11, spec.getSymbolId());
        verify(exchangeClient, times(2)).symbolCurrencyReport(anyInt());
    }

    @Test
    @DisplayName("getCurrencySpec miss：同样触发 refresh")
    void getCurrencySpecMissTriggersRefresh() {
        initManagerWith(emptyReport());
        nextResponse.set(reportWithCurrency(2));

        assertEquals(2, manager.getCurrencySpec(2).getId());
        verify(exchangeClient, times(2)).symbolCurrencyReport(anyInt());
    }

    @Test
    @DisplayName("refresh 后仍未命中：抛 IllegalArgumentException")
    void getSymbolSpecStillMissingAfterRefreshThrows() {
        initManagerWith(emptyReport());
        assertThrows(IllegalArgumentException.class, () -> manager.getSymbolSpec(999));
        // 初始 1 + miss 触发 1 = 2
        verify(exchangeClient, times(2)).symbolCurrencyReport(anyInt());
    }

    // ---------- 去抖 ----------

    @Test
    @DisplayName("500ms 内连续 miss：第 2 次直接抛而不再触发 RPC（debounce）")
    void debounceSuppressesSecondRefreshWithinWindow() {
        initManagerWith(emptyReport()); // RPC = 1

        assertThrows(IllegalArgumentException.class, () -> manager.getSymbolSpec(999)); // RPC = 2
        verify(exchangeClient, times(2)).symbolCurrencyReport(anyInt());

        assertThrows(IllegalArgumentException.class, () -> manager.getSymbolSpec(999)); // 被 debounce 挡
        verify(exchangeClient, times(2)).symbolCurrencyReport(anyInt());
    }

    @Test
    @DisplayName("过了 debounce 窗口后再 miss：会再触发一次 RPC")
    void afterDebounceWindowAnotherRefreshFires() throws InterruptedException {
        initManagerWith(emptyReport()); // RPC = 1
        assertThrows(IllegalArgumentException.class, () -> manager.getSymbolSpec(999)); // RPC = 2

        Thread.sleep(DEBOUNCE_BUFFER_MS); // 越过 500ms

        assertThrows(IllegalArgumentException.class, () -> manager.getSymbolSpec(999)); // RPC = 3
        verify(exchangeClient, times(3)).symbolCurrencyReport(anyInt());
    }

    // ---------- 并发单飞 ----------

    @Test
    @DisplayName("并发 miss：N 个线程同时 miss 也只放一次 RPC 穿透（单飞）")
    void concurrentMissesAreSingleFlight() throws Exception {
        initManagerWith(emptyReport()); // RPC = 1

        int n = 16;
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);

        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    try {
                        manager.getSymbolSpec(999);
                    } catch (IllegalArgumentException ignored) {
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(2, TimeUnit.SECONDS), "线程没准备好");
        go.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS), "线程没跑完");
        pool.shutdownNow();

        // 初始 1 次 + 并发 miss 单飞 1 次 = 2 次
        verify(exchangeClient, times(2)).symbolCurrencyReport(anyInt());
    }

    // ---------- addSpec / shortcuts ----------

    @Test
    @DisplayName("addSymbolSpec：直接灌缓存，不触发 RPC，可立即命中")
    void addSymbolSpecPopulatesCacheWithoutRpc() {
        initManagerWith(emptyReport());
        verify(exchangeClient, times(1)).symbolCurrencyReport(anyInt());

        CoreSymbolSpecification injected = CoreSymbolSpecification.newBuilder().setSymbolId(77).build();
        manager.addSymbolSpec(injected);

        assertSame(injected, manager.getSymbolSpec(77));
        assertTrue(manager.symbolExists(77));
        assertFalse(manager.symbolExists(78));
        verify(exchangeClient, times(1)).symbolCurrencyReport(anyInt());
    }

    @Test
    @DisplayName("addCurrencySpec：同上")
    void addCurrencySpecPopulatesCacheWithoutRpc() {
        initManagerWith(emptyReport());

        CoreCurrencySpecification injected =
            CoreCurrencySpecification.newBuilder().setId(7).setName("Z").setDigit(8).build();
        manager.addCurrencySpec(injected);

        assertSame(injected, manager.getCurrencySpec(7));
        assertTrue(manager.currencyExists(7));
        assertFalse(manager.currencyExists(8));
        verify(exchangeClient, times(1)).symbolCurrencyReport(anyInt());
    }

    @Test
    @DisplayName("addSymbolSpec：null / 非法 symbolId 抛 IllegalArgumentException")
    void addSymbolSpecRejectsInvalid() {
        initManagerWith(emptyReport());
        assertThrows(IllegalArgumentException.class, () -> manager.addSymbolSpec(null));
        assertThrows(IllegalArgumentException.class,
            () -> manager.addSymbolSpec(CoreSymbolSpecification.newBuilder().setSymbolId(0).build()));
    }

    @Test
    @DisplayName("addCurrencySpec：null / 非法 id 抛 IllegalArgumentException")
    void addCurrencySpecRejectsInvalid() {
        initManagerWith(emptyReport());
        assertThrows(IllegalArgumentException.class, () -> manager.addCurrencySpec(null));
        assertThrows(IllegalArgumentException.class,
            () -> manager.addCurrencySpec(CoreCurrencySpecification.newBuilder().setId(0).build()));
    }
}
