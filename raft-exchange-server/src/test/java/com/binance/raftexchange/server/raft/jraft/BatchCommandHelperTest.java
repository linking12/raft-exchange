package com.binance.raftexchange.server.raft.jraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.binance.raftexchange.server.raft.jraft.closure.BatchClosure;
import com.binance.raftexchange.server.raft.jraft.closure.BatchClosure.PendingCmd;

class BatchCommandHelperTest {

    @Test
    @Timeout(10)
    void batchFiresWhenSizeReached() throws InterruptedException {
        List<BatchClosure> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        BatchCommandHelper batcher = new BatchCommandHelper(3, TimeUnit.SECONDS.toNanos(10), 1024, c -> {
            received.add(c);
            latch.countDown();
        });
        try {
            for (int i = 0; i < 3; i++) {
                batcher.submit(new PendingCmd(new byte[] {(byte)i}, null, (r, e) -> {
                }));
            }
            assertTrue(latch.await(2, TimeUnit.SECONDS), "size 触发应立即 flush");
            assertEquals(1, received.size());
            assertEquals(3, received.get(0).size());
        } finally {
            batcher.close();
        }
    }

    @Test
    @Timeout(5)
    void batchFiresWhenWaitElapsed() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger size = new AtomicInteger();
        try (
            BatchCommandHelper batcher = new BatchCommandHelper(10_000, TimeUnit.MILLISECONDS.toNanos(100), 1024, c -> {
                size.set(c.size());
                latch.countDown();
            })) {
            batcher.submit(new PendingCmd(new byte[] {1}, null, (r, e) -> {
            }));
            assertTrue(latch.await(2, TimeUnit.SECONDS), "wait 触发应在 100ms 后 flush");
            assertEquals(1, size.get());
        }
    }

    @Test
    @Timeout(5)
    void sinkFailureFiresFailAll() throws InterruptedException {
        AtomicInteger errCount = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(2);
        try (BatchCommandHelper batcher = new BatchCommandHelper(2, TimeUnit.SECONDS.toNanos(10), 1024, c -> {
            throw new RuntimeException("sink boom");
        })) {
            for (int i = 0; i < 2; i++) {
                batcher.submit(new PendingCmd(new byte[] {(byte)i}, null, (r, e) -> {
                    if (e != null)
                        errCount.incrementAndGet();
                    latch.countDown();
                }));
            }
            assertTrue(latch.await(2, TimeUnit.SECONDS), "sink 失败时所有 sub callback 应收到 error");
            assertEquals(2, errCount.get());
        }
    }

    @Test
    @Timeout(5)
    void closeDrainsRemainingCmds() throws InterruptedException {
        List<BatchClosure> received = new CopyOnWriteArrayList<>();
        BatchCommandHelper batcher = new BatchCommandHelper(1000, TimeUnit.SECONDS.toNanos(10), 1024, received::add);
        try {
            batcher.submit(new PendingCmd(new byte[] {1}, null, (r, e) -> {
            }));
            batcher.submit(new PendingCmd(new byte[] {2}, null, (r, e) -> {
            }));
        } finally {
            batcher.close();
        }
        int total = received.stream().mapToInt(BatchClosure::size).sum();
        assertEquals(2, total, "shutdown drain 必须冲走所有未 flush cmd");
    }

    @Test
    @Timeout(5)
    void mixedSizeAndWaitTriggers() throws InterruptedException {
        List<BatchClosure> received = new CopyOnWriteArrayList<>();
        try (BatchCommandHelper batcher =
            new BatchCommandHelper(5, TimeUnit.MILLISECONDS.toNanos(100), 1024, received::add)) {
            batcher.submit(new PendingCmd(new byte[] {1}, null, (r, e) -> {
            }));
            batcher.submit(new PendingCmd(new byte[] {2}, null, (r, e) -> {
            }));
            Thread.sleep(200);
            for (int i = 0; i < 5; i++) {
                batcher.submit(new PendingCmd(new byte[] {(byte)i}, null, (r, e) -> {
                }));
            }
            Thread.sleep(50);
        }
        assertTrue(received.size() >= 2);
        int total = received.stream().mapToInt(BatchClosure::size).sum();
        assertEquals(7, total);
    }
}
