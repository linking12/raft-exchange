package com.binance.raftexchange.server.exchange.snapshot;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import exchange.core2.core.processors.journaling.ISerializationProcessor.SerializedModuleType;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.ToString;

public class StreamManager {
    private static final Logger LOG = LoggerFactory.getLogger(StreamManager.class);

    private static final int PIPE_BUFFER_SIZE = 8192 * 1024; // 8MB

    /** build() → get() 之前：流在此队列中等待消费者 */
    private static final Map<StoreId, BlockingQueue<PipedInputStream>> storeInputStream = new ConcurrentHashMap<>();

    /** get() → close() or closeAll() 之间：流已被取走，正在被 writeStreamToFile 读取 */
    private static final Map<StoreId, PipedInputStream> checkedOut = new ConcurrentHashMap<>();

    /**
     * closeAll() 已被调用的 snapshotId 集合。 get() 在将流放入 checkedOut 后检查此集合，若 snapshotId 已中止则主动关闭流并抛出， 防止 closeAll()
     * 两阶段扫描之间的竞态窗口（poll→checkedOut.put）导致流泄漏。 snapshotId 为时间戳，不重复，无需清理。
     */
    private static final Set<Long> abortedSnapshots = ConcurrentHashMap.newKeySet();

    private StreamManager() {}

    public static OutputStream build(long snapshotId, SerializedModuleType type, int shardId) throws IOException {
        StoreId storeId = new StoreId(snapshotId, type, shardId);
        BlockingQueue<PipedInputStream> queue =
            storeInputStream.computeIfAbsent(storeId, k -> new ArrayBlockingQueue<>(1));
        if (!queue.isEmpty()) {
            throw new IllegalStateException("Another PipedInputStream is already present, storeId: " + storeId);
        }
        // 顺序关键：必须先 connect（PipedOutputStream 构造内部调 inputStream.connect(this)），再 queue.add。
        // 反过来的话存在窗口：consumer 通过 get() poll 出来立刻 read() → 看到 connected=false → 抛
        // "Pipe not connected"（特别在空快照 / cold JIT 时序下，consumer 比 producer 的 new PipedOutputStream 快）。
        PipedInputStream inputStream = new PipedInputStream(PIPE_BUFFER_SIZE);
        PipedOutputStream outputStream = new PipedOutputStream(inputStream);
        queue.add(inputStream);
        return outputStream;
    }

    private static final long GET_TIMEOUT_SECONDS = 60;

    public static PipedInputStream get(long snapshotId, SerializedModuleType type, int shardId) {
        StoreId storeId = new StoreId(snapshotId, type, shardId);
        BlockingQueue<PipedInputStream> queue =
            storeInputStream.computeIfAbsent(storeId, k -> new ArrayBlockingQueue<>(1));
        try {
            PipedInputStream stream = queue.poll(GET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (stream == null) {
                // Use conditional remove so we don't evict an entry that build() just populated
                // between the poll() timeout and this call.
                storeInputStream.remove(storeId, queue);
                throw new IllegalStateException(
                    "Timed out after " + GET_TIMEOUT_SECONDS + "s waiting for PipedInputStream, storeId: " + storeId);
            }
            checkedOut.put(storeId, stream);
            // Guard against the TOCTOU window: closeAll() may have run its two scan loops
            // between our queue.poll() and this checkedOut.put(), missing this stream entirely.
            // If the snapshot was aborted, close the stream ourselves and fail fast.
            if (abortedSnapshots.contains(storeId.snapshotId)) {
                PipedInputStream leaked = checkedOut.remove(storeId);
                if (leaked != null) {
                    try {
                        leaked.close();
                    } catch (IOException ignored) {
                    }
                }
                throw new IllegalStateException(
                    "Snapshot " + storeId.snapshotId + " was already aborted, storeId: " + storeId);
            }
            return stream;
        } catch (InterruptedException e) {
            // Conditional remove: only evict if nobody else replaced the queue in the meantime.
            if (storeInputStream.remove(storeId, queue)) {
                PipedInputStream leftover = queue.poll();
                if (leftover != null) {
                    try {
                        leftover.close();
                    } catch (IOException ignored) {
                    }
                }
            }
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for PipedInputStream, storeId: " + storeId, e);
        }
    }

    /**
     * Closes and removes all pipe entries for the given snapshotId. Called on snapshot-save failure to unblock
     * exchange-core producer threads. Covers two populations: 1. streams still in storeInputStream queues (not yet
     * get()'d) 2. streams already checked out by get() but still being read by writeStreamToFile
     */
    public static void closeAll(long snapshotId) {
        // Mark first so any get() thread in the poll→checkedOut.put gap will self-close on exit.
        abortedSnapshots.add(snapshotId);
        // Population 1: streams not yet consumed by get()
        storeInputStream.entrySet().removeIf(entry -> {
            if (entry.getKey().snapshotId != snapshotId) {
                return false;
            }
            PipedInputStream stream = entry.getValue().poll();
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
            return true;
        });
        // Population 2: streams already polled by get(), currently mid-read
        checkedOut.entrySet().removeIf(entry -> {
            if (entry.getKey().snapshotId != snapshotId) {
                return false;
            }
            try {
                entry.getValue().close();
            } catch (IOException ignored) {
            }
            return true;
        });
    }

    public static void close(long snapshotId, SerializedModuleType type, int shardId) {
        StoreId storeId = new StoreId(snapshotId, type, shardId);
        storeInputStream.remove(storeId);
        PipedInputStream stream = checkedOut.remove(storeId);
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                LOG.warn("Failed to close PipedInputStream, storeId: {}", storeId, e);
            }
        } else {
            LOG.warn("close() called for unknown or already-closed storeId: {}", storeId);
        }
    }

    /**
     * StoreId，用于区分store过程中的inputStream 由于disruptor调用store本身是同步的，因此不需要记录seq，seq是disruptor中保证顺序的
     */
    @AllArgsConstructor
    @EqualsAndHashCode
    @ToString
    private static class StoreId {
        long snapshotId;
        SerializedModuleType type;
        int instanceId;
    }
}
