package com.binance.raftexchange.server.exchange.snapshot;

import exchange.core2.core.processors.journaling.ISerializationProcessor.SerializedModuleType;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

public class StreamManager {
    private static final Logger LOG = LoggerFactory.getLogger(StreamManager.class);

    private static final int PIPE_BUFFER_SIZE = 8192 * 1024; // 8MB

    /**
     * 用于存储PipedInputStream，注意"build -> get -> close"的调用顺序
     */
    private static final Map<StoreId, BlockingQueue<PipedInputStream>> storeInputStream = new ConcurrentHashMap<>();

    /**
     * 用于存储恢复数据的文件路径
      */
    private static final Map<StoreId, BlockingQueue<Path>> storeLoadFile = new ConcurrentHashMap<>();

    private StreamManager() {
    }

    public static OutputStream build(long snapshotId, SerializedModuleType type, int shardId) throws IOException {
        StoreId storeId = new StoreId(snapshotId, type, shardId);
        BlockingQueue<PipedInputStream> queue = storeInputStream.computeIfAbsent(storeId, k -> new ArrayBlockingQueue<>(1));
        if (!queue.isEmpty()) {
            throw new IllegalStateException("Another PipedInputStream is already present, storeId: " + storeId);
        }
        PipedInputStream inputStream = new PipedInputStream(PIPE_BUFFER_SIZE);
        queue.add(inputStream);
        return new PipedOutputStream(inputStream);
    }

    public static PipedInputStream get(long snapshotId, SerializedModuleType type, int shardId) {
        StoreId storeId = new StoreId(snapshotId, type, shardId);
        BlockingQueue<PipedInputStream> queue = storeInputStream.computeIfAbsent(storeId, k -> new ArrayBlockingQueue<>(1));
        try {
            return queue.take();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to get PipedInputStream, storeId: " + storeId, e);
        }
    }

    public static void close(long snapshotId, SerializedModuleType type, int shardId) {
        StoreId storeId = new StoreId(snapshotId, type, shardId);
        BlockingQueue<PipedInputStream> queue = storeInputStream.remove(storeId);
        if (queue == null) {
            LOG.warn("No PipedInputStream found to close, storeId: {}", storeId);
            return;
        }
        PipedInputStream inputStream = queue.poll();
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException e) {
                LOG.warn("Failed to close PipedInputStream", e);
            }
        }
    }

    public static Path getFilePathForLoadData(long snapshotId, SerializedModuleType type, int shardId) {
        StoreId storeId = new StoreId(snapshotId, type, shardId);
        BlockingQueue<Path> queue = storeLoadFile.computeIfAbsent(storeId, k -> new ArrayBlockingQueue<>(1));
        return queue.peek();
    }

    public static void saveFilePathForLoadData(long snapshotId, SerializedModuleType type, int shardId, Path path) {
        StoreId storeId = new StoreId(snapshotId, type, shardId);
        BlockingQueue<Path> queue = storeLoadFile.computeIfAbsent(storeId, k -> new ArrayBlockingQueue<>(1));
        if (!queue.isEmpty()) {
            throw new IllegalStateException("Another FilePath is already present, storeId: " + storeId);
        }
        queue.add(path);
    }

    public static void clearFilePathForLoadData(long snapshotId, SerializedModuleType type, int shardId) {
        StoreId storeId = new StoreId(snapshotId, type, shardId);
        storeLoadFile.remove(storeId);
    }

    /**
     * StoreId，用于区分store过程中的inputStream
     * 由于disruptor调用store本身是同步的，因此不需要记录seq，seq是disruptor中保证顺序的
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
