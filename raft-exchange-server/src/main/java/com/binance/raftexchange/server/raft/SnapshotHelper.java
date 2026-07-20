package com.binance.raftexchange.server.raft;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.exchange.snapshot.StreamManager;
import com.binance.raftexchange.server.metrics.RaftExchangeMetrics;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.journaling.ISerializationProcessor.SerializedModuleType;

public class SnapshotHelper {
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotHelper.class);
    private static String snapshotPath;
    private final ExecutorService snapshotExecutor;
    private final int matchingEnginesNum;
    private final int riskEnginesNum;

    /** 单 shard 写盘 watchdog 上限：超过则中断 reader 线程，防 producer hang 导致 FSMCaller 永久 block。test-only via reflection。 */
    static volatile long writeTimeoutSec = 300L;

    /** saveSnapshot 全部 shard 总上限：任一 shard 超时则 cancelAll + 抛。test-only via reflection。 */
    static volatile long saveDeadlineSec = 300L;

    public SnapshotHelper() {
        matchingEnginesNum = PerformanceConfiguration.DEFAULT.getMatchingEnginesNum();
        riskEnginesNum = PerformanceConfiguration.DEFAULT.getRiskEnginesNum();
        int maxThreads = matchingEnginesNum + riskEnginesNum;
        snapshotExecutor =
            new ThreadPoolExecutor(0, maxThreads, 60L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(maxThreads),
                new ThreadFactoryBuilder().setNameFormat("SnapshotHelper-%d").setDaemon(true).build(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 校验snp的分区文件数和当前的shard数是否一致
     * 
     * @param files
     * @return
     */
    public boolean checkSnapshotIntegrity(Set<String> files) {
        long meCount = files.stream()
            .filter(f -> f.contains(moduleTypeToCode(SerializedModuleType.MATCHING_ENGINE_ROUTER))).count();
        long reCount =
            files.stream().filter(f -> f.contains(moduleTypeToCode(SerializedModuleType.RISK_ENGINE))).count();
        return (meCount == matchingEnginesNum) && (reCount == riskEnginesNum);
    }

    public List<String> saveSnapshot(long snapshotId, String root) {
        List<CompletableFuture<String>> futures = new ArrayList<>(matchingEnginesNum + riskEnginesNum);
        futures.addAll(saveSnapshot(snapshotId, SerializedModuleType.MATCHING_ENGINE_ROUTER, matchingEnginesNum, root));
        futures.addAll(saveSnapshot(snapshotId, SerializedModuleType.RISK_ENGINE, riskEnginesNum, root));

        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(saveDeadlineSec);
        List<String> results = new ArrayList<>(futures.size());
        try {
            for (CompletableFuture<String> f : futures) {
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new TimeoutException("Snapshot save overall deadline " + saveDeadlineSec
                        + "s exceeded for snapshotId=" + snapshotId);
                }
                results.add(f.get(remainingNanos, TimeUnit.NANOSECONDS));
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelAll(futures);
            throw new RuntimeException("saveSnapshot interrupted for snapshotId=" + snapshotId, e);
        } catch (TimeoutException e) {
            cancelAll(futures);
            throw new RuntimeException("saveSnapshot timeout for snapshotId=" + snapshotId, e);
        } catch (ExecutionException e) {
            cancelAll(futures);
            // unwrap RuntimeException 让上层 ESM 的 catch 拿到真正的 cause
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException)
                throw (RuntimeException)cause;
            throw new RuntimeException("saveSnapshot failed for snapshotId=" + snapshotId, cause);
        }
    }

    private static void cancelAll(List<CompletableFuture<String>> futures) {
        // cancel(true) 只标 future + 中断 worker；卡在 PipedInputStream.read() 的实际解锁靠 watchdog。
        for (CompletableFuture<String> f : futures) {
            if (!f.isDone())
                f.cancel(true);
        }
    }

    private List<CompletableFuture<String>> saveSnapshot(long snapshotId, SerializedModuleType type, int shardNum,
        String root) {
        return IntStream.range(0, shardNum).mapToObj(shardId -> CompletableFuture.supplyAsync(() -> {
            String fileName = genSnapshotFileName(snapshotId, type, shardId);
            Path path = Paths.get(root, fileName);
            try {
                PipedInputStream pipedInputStream = StreamManager.get(snapshotId, type, shardId);
                writeStreamToFile(pipedInputStream, path);
                StreamManager.close(snapshotId, type, shardId);
                return fileName;
            } catch (Exception e) {
                throw new RuntimeException("saveSnapshot file " + path + " failed", e);
            }
        }, snapshotExecutor)).collect(Collectors.toList());
    }

    public static long getSnapshotId(Set<String> files) {
        if (files == null || files.isEmpty()) {
            throw new RuntimeException("Cannot extract snapshot ID: file set is null or empty");
        }
        Long snapshotId = null;
        for (String file : files) {
            if (!file.startsWith("snapshot_")) {
                continue;
            }
            String[] parts = file.split("_");
            if (parts.length != 4) {
                LOG.warn("Skipping file with unexpected name format: {}", file);
                continue;
            }
            long id;
            try {
                id = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                LOG.warn("Skipping file with non-numeric snapshot ID '{}': {}", parts[1], file);
                continue;
            }
            if (snapshotId == null) {
                snapshotId = id;
            } else if (snapshotId != id) {
                throw new RuntimeException("Inconsistent snapshot IDs in directory: found both " + snapshotId + " and "
                    + id + ". Directory may contain leftover files from a previous snapshot.");
            }
        }
        if (snapshotId == null) {
            throw new RuntimeException("No valid snapshot files found in set: " + files);
        }
        return snapshotId;
    }

    /** 示例：snapshot_20250307105705748_RE_0.dat */
    public static String genSnapshotFileName(long snapshotId, SerializedModuleType type, int shardId) {
        return String.format("snapshot_%s_%s_%s.dat", snapshotId, moduleTypeToCode(type), shardId);
    }

    static void writeStreamToFile(InputStream inputStream, Path filePath) {
        // PipedInputStream.read() 只看 closedByWriter，close() 设的 closedByReader 解不开阻塞；
        // watchdog 用 reader.interrupt() 让 read() 内的 wait() 抛 InterruptedIOException，下面 catch 接住。
        // 再 close 一下 pipe 是为了释放资源（同步也无副作用）。
        AtomicBoolean timedOut = new AtomicBoolean(false);
        long timeoutMs = TimeUnit.SECONDS.toMillis(writeTimeoutSec);
        Thread reader = Thread.currentThread();
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(timeoutMs);
                timedOut.set(true);
                reader.interrupt();
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
                LOG.error("writeStreamToFile timed out after {}s for {}, interrupted reader", writeTimeoutSec,
                    filePath);
            } catch (InterruptedException ignored) {
                // 写完了，主线程通过 finally 块 interrupt 我们退出
            }
        }, "snapshot-write-watchdog-" + filePath.getFileName());
        watchdog.setDaemon(true);
        watchdog.start();

        try (
            OutputStream fileOutputStream =
                Files.newOutputStream(filePath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            BufferedOutputStream bos = new BufferedOutputStream(fileOutputStream)) {
            byte[] buffer = new byte[8192]; // 8K缓冲区
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }
            bos.flush();
        } catch (IOException e) {
            if (timedOut.get()) {
                Thread.interrupted(); // 清掉 interrupt 标志，避免污染线程下次调用
                LOG.error("Snapshot write timed out after {}s for {}", writeTimeoutSec, filePath);
                throw new RuntimeException("Snapshot write timed out after " + writeTimeoutSec + "s for " + filePath,
                    e);
            }
            LOG.error("Failed to write stream to file", e);
            throw new RuntimeException("Failed to write stream to file", e);
        } finally {
            watchdog.interrupt();
        }
    }

    public static long genSnapshotId() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
        String formattedDate = LocalDateTime.now().format(formatter);
        return Long.parseLong(formattedDate);
    }

    public static void cleanSnapshots(String root, long snapshotId) {
        String prefix = "snapshot_" + snapshotId + "_";
        try (Stream<Path> files = Files.list(Paths.get(root))) {
            files.filter(file -> {
                String fileName = file.getFileName().toString();
                return fileName.startsWith(prefix) && fileName.endsWith(".dat");
            }).forEach(file -> {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    RaftExchangeMetrics.Snapshot.recordCleanupFailure();
                    LOG.warn("Failed to delete snapshot file {}", file, e);
                }
            });
        } catch (IOException e) {
            RaftExchangeMetrics.Snapshot.recordCleanupFailure();
            LOG.warn("Failed to list snapshot dir {} for cleanup of snapshotId={}", root, snapshotId, e);
        }
    }

    private static SerializedModuleType codeToModuleType(String code) {
        switch (code) {
            case "ME":
                return SerializedModuleType.MATCHING_ENGINE_ROUTER;
            case "RE":
                return SerializedModuleType.RISK_ENGINE;
            default:
                throw new RuntimeException("Invalid code: " + code);
        }
    }

    private static String moduleTypeToCode(SerializedModuleType type) {
        switch (type) {
            case MATCHING_ENGINE_ROUTER:
                return "ME";
            case RISK_ENGINE:
                return "RE";
            default:
                throw new RuntimeException("Invalid SerializedModuleType: " + type);
        }
    }

    public static String getSnapshotPath() {
        return snapshotPath;
    }

    public static void setSnapshotPath(String snapshotPath) {
        SnapshotHelper.snapshotPath = snapshotPath;
    }
}
