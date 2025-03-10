package com.binance.raftexchange.server.raft;

import com.binance.raftexchange.server.exchange.snapshot.StreamManager;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.journaling.ISerializationProcessor.SerializedModuleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class SnapshotHelper {
    private static final Logger LOG = LoggerFactory.getLogger(SnapshotHelper.class);

    private final ExecutorService snapshotExecutor;
    private final int matchingEnginesNum;
    private final int riskEnginesNum;

    public SnapshotHelper() {
        matchingEnginesNum = PerformanceConfiguration.DEFAULT.getMatchingEnginesNum();
        riskEnginesNum = PerformanceConfiguration.DEFAULT.getRiskEnginesNum();
        int maxThreads = matchingEnginesNum + riskEnginesNum;
        snapshotExecutor = new ThreadPoolExecutor(0, maxThreads, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(maxThreads),
                new ThreadFactoryBuilder().setNameFormat("SnapshotHelper-%d").setDaemon(true).build(),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    /**
     * 校验snp的分区文件数和当前的shard数是否一致
     * @param files
     * @return
     */
    public boolean checkSnapshotIntegrity(Set<String> files) {
        long meCount = files.stream().filter(f -> f.contains(moduleTypeToCode(SerializedModuleType.MATCHING_ENGINE_ROUTER))).count();
        long reCount = files.stream().filter(f -> f.contains(moduleTypeToCode(SerializedModuleType.RISK_ENGINE))).count();
        return (meCount == matchingEnginesNum) && (reCount == riskEnginesNum);
    }

    public void loadSnapshotPath(Set<String> files, String root) {
        for (String file : files) {
            String[] parts = file.substring(0, file.lastIndexOf(".dat")).split("_");
            if (parts.length != 4) {
                throw new RuntimeException("Invalid snapshot file: " + file);
            }
            long snapshotId = 0; // 撮合load的时候snapshotId永远是0
            SerializedModuleType type = codeToModuleType(parts[2]);
            int instanceId = Integer.parseInt(parts[3]);
            StreamManager.saveFilePathForLoadData(snapshotId, type, instanceId, Paths.get(root, file));
        }
    }

    public List<String> saveSnapshot(long snapshotId, String root) {
        List<CompletableFuture<String>> futures = new ArrayList<>(matchingEnginesNum + riskEnginesNum);
        futures.addAll(saveSnapshot(snapshotId, SerializedModuleType.MATCHING_ENGINE_ROUTER, matchingEnginesNum, root));
        futures.addAll(saveSnapshot(snapshotId, SerializedModuleType.RISK_ENGINE, riskEnginesNum, root));
        return futures.stream().map(CompletableFuture::join).collect(Collectors.toList());
    }

    private List<CompletableFuture<String>> saveSnapshot(long snapshotId, SerializedModuleType type, int shardNum, String root) {
        return IntStream.range(0, shardNum)
                .mapToObj(shardId -> CompletableFuture.supplyAsync(() -> {
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
                }, snapshotExecutor))
                .collect(Collectors.toList());
    }

    /**
     * snapshot_20250307105705748_RE_0.dat
     *
     * @param snapshotId 时间
     * @param type       ME/RE
     * @param shardId    分区
     * @return
     */
    private static String genSnapshotFileName(long snapshotId, SerializedModuleType type, int shardId) {
        return String.format("snapshot_%s_%s_%s.dat", snapshotId, moduleTypeToCode(type), shardId);
    }

    private static void writeStreamToFile(InputStream inputStream, Path filePath) {
        try (OutputStream fileOutputStream = Files.newOutputStream(filePath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             BufferedOutputStream bos = new BufferedOutputStream(fileOutputStream)) {
            byte[] buffer = new byte[8192]; // 8K缓冲区
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }
            bos.flush();
        } catch (IOException e) {
            LOG.error("Failed to write stream to file", e);
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
                } catch (IOException ignored) {//ignore
                }
            });
        } catch (IOException ignored) {//ignore
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
}
