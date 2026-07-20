package com.binance.raftexchange.server.raft.jraft;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

import org.jctools.queues.MpscBlockingConsumerArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.raft.jraft.closure.BatchClosure;
import com.binance.raftexchange.server.raft.jraft.closure.BatchClosure.PendingCmd;

/**
 * Raft log 聚合器：把短小高频的单条命令攒成一批，打包成一条 raft entry 提交，摊薄 raft 协议的固定头开销。
 *
 * <p>
 * 单 flusher 线程从 MPSC 队列消费；达到 maxBatchSize 或 maxLinger 任一就 flush 出一个 {@link BatchClosure} 走 raft。Entry 头一字节
 * {@link #BATCH_MAGIC} 0x00 跟普通 ApiCommand proto 区分（proto 字段头不会以 0x00 开头）。
 *
 * <p>{@link #close()}：拒新提交 → 等队列 drain → 中断 flusher。
 */
final class BatchCommandHelper implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(BatchCommandHelper.class);

    private static final int DEFAULT_MAX_BATCH_SIZE = 32;
    private static final long DEFAULT_MAX_LINGER_NANOS = TimeUnit.MICROSECONDS.toNanos(500L);
    private static final int DEFAULT_QUEUE_CAPACITY = 32 * 1024;

    private static final long IDLE_POLL_MS = 50;
    private static final long SHUTDOWN_DRAIN_GRACE_MS = 5000;
    private static final long SHUTDOWN_JOIN_MS = 2000;
    private static final long DRAIN_WAIT_TICK_NANOS = 1_000_000L;
    private static final byte BATCH_MAGIC = 0x00;

    private final int maxBatchSize;
    private final long maxLingerNanos;
    private final Consumer<BatchClosure> batchSink;
    private final MpscBlockingConsumerArrayQueue<PendingCmd> inboundCmds;
    private final Thread flusherThread;
    private volatile boolean acceptingNewCmds = true;
    private volatile boolean running = true;

    public BatchCommandHelper(Consumer<BatchClosure> batchSink) {
        this(DEFAULT_MAX_BATCH_SIZE, DEFAULT_MAX_LINGER_NANOS, DEFAULT_QUEUE_CAPACITY, batchSink);
    }

    BatchCommandHelper(int maxBatchSize, long maxLingerNanos, int queueCapacity, Consumer<BatchClosure> batchSink) {
        this.maxBatchSize = maxBatchSize;
        this.maxLingerNanos = maxLingerNanos;
        this.batchSink = batchSink;
        this.inboundCmds = new MpscBlockingConsumerArrayQueue<>(queueCapacity);
        this.flusherThread = new Thread(this::runFlushLoop, "raft-batch-flusher");
        this.flusherThread.setDaemon(true);
        this.flusherThread.start();
        LOG.info("BatchCommandHelper started: maxBatchSize={} maxLingerNanos={} queueCapacity={}", maxBatchSize,
            maxLingerNanos, queueCapacity);
    }

    public boolean submit(PendingCmd cmd) throws InterruptedException {
        if (!acceptingNewCmds) {
            return false;
        }
        inboundCmds.put(cmd);
        return true;
    }

    @Override
    public void close() {
        acceptingNewCmds = false;
        waitForFlusherToDrain();

        running = false;
        flusherThread.interrupt();
        try {
            flusherThread.join(SHUTDOWN_JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (flusherThread.isAlive()) {
            LOG.warn("BatchCommandHelper flusher did not terminate within {}ms", SHUTDOWN_JOIN_MS);
        }

        int rejected = failRemainingOnShutdown();
        if (rejected > 0) {
            LOG.warn("Rejected {} cmd(s) left in queue at shutdown; callers notified with failure", rejected);
        }
    }

    private void waitForFlusherToDrain() {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_DRAIN_GRACE_MS);
        while (!inboundCmds.isEmpty() && System.nanoTime() < deadlineNanos) {
            LockSupport.parkNanos(DRAIN_WAIT_TICK_NANOS);
        }
    }

    private int failRemainingOnShutdown() {
        IllegalStateException shutdownErr = new IllegalStateException("BatchCommandHelper is shutting down");
        int count = 0;
        PendingCmd cmd;
        while ((cmd = inboundCmds.poll()) != null) {
            try {
                cmd.callback().accept(null, shutdownErr);
            } catch (Throwable t) {
                LOG.error("Shutdown failure callback threw for cmd; continuing", t);
            }
            count++;
        }
        return count;
    }

    private void runFlushLoop() {
        final List<PendingCmd> batchBuffer = new ArrayList<>(maxBatchSize);
        while (running) {
            try {
                fillBatch(batchBuffer);
                flushBatch(batchBuffer);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                LOG.error("Batch flusher loop error", t);
            }
        }
        drainOnShutdown(batchBuffer);
    }

    private void fillBatch(List<PendingCmd> batchBuffer) throws InterruptedException {
        PendingCmd first = inboundCmds.poll(IDLE_POLL_MS, TimeUnit.MILLISECONDS);
        if (first == null) {
            return;
        }
        batchBuffer.add(first);
        long lingerDeadlineNanos = first.submitNanos() + maxLingerNanos;
        while (batchBuffer.size() < maxBatchSize) {
            long remainingNanos = lingerDeadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                break;
            }
            PendingCmd next = inboundCmds.poll(remainingNanos, TimeUnit.NANOSECONDS);
            if (next == null) {
                break;
            }
            batchBuffer.add(next);
        }
    }

    private void drainOnShutdown(List<PendingCmd> batchBuffer) {
        PendingCmd cmd;
        while ((cmd = inboundCmds.poll()) != null) {
            batchBuffer.add(cmd);
            if (batchBuffer.size() >= maxBatchSize) {
                flushBatch(batchBuffer);
            }
        }
        flushBatch(batchBuffer);
    }

    private void flushBatch(List<PendingCmd> batchBuffer) {
        if (batchBuffer.isEmpty()) {
            return;
        }
        BatchClosure batchClosure = new BatchClosure(batchBuffer.toArray(new PendingCmd[0]));
        try {
            batchSink.accept(batchClosure);
        } catch (Throwable t) {
            LOG.error("Batch sink failed (size={}); notifying sub-callbacks with error", batchBuffer.size(), t);
            batchClosure.failAll(t);
        } finally {
            batchBuffer.clear();
        }
    }

    public static boolean isBatchEntry(ByteBuffer data) {
        return data.hasRemaining() && data.get(data.position()) == BATCH_MAGIC;
    }

    public static byte[] packBatchEntry(BatchClosure batchClosure) {
        int subCount = batchClosure.size();
        int totalBytes = 1 + Integer.BYTES;
        for (int i = 0; i < subCount; i++) {
            totalBytes += Integer.BYTES + batchClosure.at(i).cmdBytes().length;
        }
        ByteBuffer buf = ByteBuffer.allocate(totalBytes);
        buf.put(BATCH_MAGIC).putInt(subCount);
        for (int i = 0; i < subCount; i++) {
            byte[] cmdBytes = batchClosure.at(i).cmdBytes();
            buf.putInt(cmdBytes.length).put(cmdBytes);
        }
        return buf.array();
    }

    public static byte[][] unpackBatchEntry(ByteBuffer data) {
        if (data.get() != BATCH_MAGIC) {
            throw new IllegalStateException("not a batch envelope");
        }
        int subCount = data.getInt();
        byte[][] subBytes = new byte[subCount][];
        for (int i = 0; i < subCount; i++) {
            subBytes[i] = new byte[data.getInt()];
            data.get(subBytes[i]);
        }
        return subBytes;
    }
}
