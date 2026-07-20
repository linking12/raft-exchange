package com.binance.raftexchange.server.util;

import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProtoBuilderPool 的并发不变量： 1. 多线程 get() 各自拿自己的 builder 实例（FastThreadLocal 隔离） 2. 同线程多次 get() 拿同一个，每次自动 clear（验证不会泄漏状态）
 * 3. 并发 register 同一 key 不 race（putIfAbsent）
 */
class ProtoBuilderPoolConcurrencyTest {

    @Test
    void multiThreadGet_returnsThreadLocalInstancesAndClearsState() throws Exception {
        ProtoBuilderPool pool = new ProtoBuilderPool();
        pool.register(CommandResult.Builder.class, CommandResult::newBuilder);

        int threads = 16;
        int iters = 500;
        ExecutorService es = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<Integer> identities = ConcurrentHashMap.newKeySet();
        AtomicInteger nonClearCount = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            es.submit(() -> {
                try {
                    start.await();
                    CommandResult.Builder mine = null;
                    for (int i = 0; i < iters; i++) {
                        CommandResult.Builder b = pool.get(CommandResult.Builder.class);
                        // 每次拿到必须是 clear 状态（resultCode 是 0-valued 默认）
                        if (b.getResultCode().getNumber() != 0) {
                            nonClearCount.incrementAndGet();
                        }
                        if (mine == null) {
                            mine = b;
                            identities.add(System.identityHashCode(mine));
                        } else if (b != mine) {
                            // 同线程内换实例 — 不应发生
                            identities.add(System.identityHashCode(b));
                        }
                        // 污染当前 builder，下次 get 必须 clear
                        b.setResultCode(CommandResultCode.NO_LEADER);
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS));

        assertEquals(0, nonClearCount.get(), "每次 get() 必须返回 clear 过的 builder（不应携带上次的 NO_LEADER 状态）");
        assertEquals(threads, identities.size(), "每个线程必须拿到独立 builder 实例");

        es.shutdown();
    }

    @Test
    void concurrentRegisterSameKey_firstSupplierWins() throws Exception {
        ProtoBuilderPool pool = new ProtoBuilderPool();
        int registrars = 32;
        ExecutorService es = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(registrars);
        AtomicInteger supplierCalls = new AtomicInteger();

        for (int t = 0; t < registrars; t++) {
            es.submit(() -> {
                try {
                    start.await();
                    pool.register(CommandResult.Builder.class, () -> {
                        supplierCalls.incrementAndGet();
                        return CommandResult.newBuilder();
                    });
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));

        // 第一次 register 之后 putIfAbsent 拒绝后续覆盖；supplier 还没被调用
        assertEquals(0, supplierCalls.get(), "register 不该立刻调用 supplier");

        // 单线程拿一次，supplier 才被调用 1 次（懒加载）
        pool.get(CommandResult.Builder.class);
        assertEquals(1, supplierCalls.get(), "首次 get 触发 supplier，但只调一次（已注册的版本）");

        es.shutdown();
    }
}
