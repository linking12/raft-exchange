package com.binance.raftexchange.server.exchange.snapshot;

import com.binance.raftexchange.server.raft.SnapshotHelper;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.processors.journaling.ISerializationProcessor.SerializedModuleType;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MemorySerializationProcessor 是 snapshot 写入/恢复的核心。 storeData 通过 StreamManager.build() 拿到 PipedOutputStream 写入；外部
 * saveSnapshot 线程通过 StreamManager.get() 拿到对应的 PipedInputStream 把字节落盘。两个线程通过管道汇合。 测试涵盖 round-trip / 压缩开关 / 损坏文件加载。
 */
class MemorySerializationProcessorTest {

    private Path tempDir;
    private static final SerializedModuleType TYPE = SerializedModuleType.MATCHING_ENGINE_ROUTER;
    private static final long SNAPSHOT_ID = 12345L;
    private static final int INSTANCE_ID = 0;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("msp-test-");
        SnapshotHelper.setSnapshotPath(tempDir.toString());
        // 上一个测试可能开了压缩；每次清零，让构造期决策走到 default 路径
        System.clearProperty("raftexchange.snapshot.compression");
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("raftexchange.snapshot.compression");
        if (tempDir != null)
            FileUtils.deleteDirectory(tempDir.toFile());
    }

    @Test
    void roundTrip_uncompressed_recoverableLong() throws Exception {
        long expected = 0xCAFEBABE_DEADBEEFL;

        CompletableFuture<Void> drain = startDrain(SNAPSHOT_ID, TYPE, INSTANCE_ID);

        MemorySerializationProcessor processor = newProcessor();
        boolean stored = processor.storeData(SNAPSHOT_ID, 0, 0, TYPE, INSTANCE_ID, (out) -> out.writeLong(expected));
        assertTrue(stored, "storeData 必须返 true");

        drain.get(5, TimeUnit.SECONDS);
        assertTrue(Files.exists(snapshotFile(SNAPSHOT_ID, TYPE, INSTANCE_ID)), "drain 线程必须把流写到磁盘");

        AtomicLong got = new AtomicLong();
        processor.loadData(SNAPSHOT_ID, TYPE, INSTANCE_ID, in -> {
            got.set(in.readLong());
            return null;
        });
        assertEquals(expected, got.get(), "round-trip 必须保持完全相等");
    }

    @Test
    void roundTrip_compressed_autoDetectionPicksLz4OnLoad() throws Exception {
        // 开压缩写文件，loadData autoDetectInputStream 必须靠 magic byte 识别 LZ4
        System.setProperty("raftexchange.snapshot.compression", "true");

        long expected = 42L;

        CompletableFuture<Void> drain = startDrain(SNAPSHOT_ID, TYPE, INSTANCE_ID);

        MemorySerializationProcessor processor = newProcessor();
        processor.storeData(SNAPSHOT_ID, 0, 0, TYPE, INSTANCE_ID, (out) -> out.writeLong(expected));
        drain.get(5, TimeUnit.SECONDS);

        AtomicLong got = new AtomicLong();
        processor.loadData(SNAPSHOT_ID, TYPE, INSTANCE_ID, in -> {
            got.set(in.readLong());
            return null;
        });
        assertEquals(expected, got.get());
    }

    @Test
    void roundTrip_largePayload_doesNotDeadlock_onPipeBackpressure() throws Exception {
        // pipe buffer 是 8MB；写更多必须由 drain 线程及时消费才不会死锁
        CompletableFuture<Void> drain = startDrain(SNAPSHOT_ID, TYPE, INSTANCE_ID);

        MemorySerializationProcessor processor = newProcessor();
        int N = 200_000; // 200k longs = 1.6 MB；够测一般情况，避免测试太慢
        boolean stored = processor.storeData(SNAPSHOT_ID, 0, 0, TYPE, INSTANCE_ID, out -> {
            for (int i = 0; i < N; i++)
                out.writeLong(i);
        });
        assertTrue(stored);
        drain.get(10, TimeUnit.SECONDS);

        AtomicReference<long[]> got = new AtomicReference<>();
        processor.loadData(SNAPSHOT_ID, TYPE, INSTANCE_ID, in -> {
            long[] arr = new long[N];
            for (int i = 0; i < N; i++)
                arr[i] = in.readLong();
            got.set(arr);
            return null;
        });
        long[] arr = got.get();
        assertNotNull(arr);
        assertEquals(0L, arr[0]);
        assertEquals(N - 1, arr[N - 1]);
    }

    @Test
    void loadData_corruptFile_throwsIllegalStateException() throws Exception {
        // 手动写一个损坏的文件（不符合 WireType.RAW 格式），loadData 必须 fail 而非 silently 返回默认值
        Path corruptFile = snapshotFile(SNAPSHOT_ID, TYPE, INSTANCE_ID);
        Files.write(corruptFile, new byte[] {(byte)0xFF, (byte)0xFF, 0x01, 0x02, 0x03}, StandardOpenOption.CREATE);

        MemorySerializationProcessor processor = newProcessor();
        assertThrows(IllegalStateException.class, () -> processor.loadData(SNAPSHOT_ID, TYPE, INSTANCE_ID, in -> {
            in.readLong();
            return null;
        }), "格式损坏必须抛 IllegalStateException 让上层重 sync");
    }

    @Test
    void loadData_missingFile_throwsIllegalStateException() {
        MemorySerializationProcessor processor = newProcessor();
        assertThrows(IllegalStateException.class, () -> processor.loadData(99999L, TYPE, INSTANCE_ID, in -> null),
            "snapshot 文件不存在必须 fail 而非返默认值");
    }

    // ---- helpers ----

    private MemorySerializationProcessor newProcessor() {
        return new MemorySerializationProcessor(ExchangeConfiguration.defaultBuilder().build());
    }

    private Path snapshotFile(long snapshotId, SerializedModuleType type, int instanceId) {
        return tempDir.resolve(SnapshotHelper.genSnapshotFileName(snapshotId, type, instanceId));
    }

    /** 模拟 saveSnapshot 干的事：把 StreamManager 给的 PipedInputStream 写到磁盘文件。 */
    private CompletableFuture<Void> startDrain(long snapshotId, SerializedModuleType type, int instanceId) {
        return CompletableFuture.runAsync(() -> {
            try {
                PipedInputStream pis = StreamManager.get(snapshotId, type, instanceId);
                try (
                    OutputStream fos = Files.newOutputStream(snapshotFile(snapshotId, type, instanceId),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    OutputStream bos = new BufferedOutputStream(fos)) {
                    copy(pis, bos);
                }
                StreamManager.close(snapshotId, type, instanceId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1)
            out.write(buf, 0, n);
        out.flush();
    }
}
