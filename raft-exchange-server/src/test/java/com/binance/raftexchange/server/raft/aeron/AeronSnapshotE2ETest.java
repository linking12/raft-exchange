package com.binance.raftexchange.server.raft.aeron;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.binance.raftexchange.server.raft.AdminResult;
import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiCommand;

@Disabled("Slow e2e (~10s): starts Aeron cluster + ExchangeCore + snapshot. 手动回归用。")
class AeronSnapshotE2ETest {

    private static final long CMD_TIMEOUT_MS = 10_000;
    private static final long SNAPSHOT_DEADLINE_MS = 30_000;

    @Test
    void triggerSnapshot_writesDatFiles() throws Exception {
        try (AeronSingleNodeHarness h = AeronSingleNodeHarness.start()) {
            ApiCommand addUser = ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
                .setAddUser(ApiAddUser.newBuilder().setUid(99L)).build();
            h.submitAndAwait(addUser.toByteArray(), CMD_TIMEOUT_MS);

            AdminResult result = h.container().triggerSnapshot().get(30, TimeUnit.SECONDS);
            assertTrue(result.success(), "triggerSnapshot 应成功：" + result.message());

            Path snapshotsRoot = h.container().snapshotsRoot();
            assertNotNull(snapshotsRoot, "aeron 必须暴露 snapshotsRoot");

            List<Path> datFiles = awaitDatFiles(snapshotsRoot);
            assertTrue(datFiles.stream().anyMatch(p -> p.getFileName().toString().contains("_ME_")),
                "ME shard .dat 应落盘，实际=" + datFiles);
            assertTrue(datFiles.stream().anyMatch(p -> p.getFileName().toString().contains("_RE_")),
                "RE shard .dat 应落盘，实际=" + datFiles);
        }
    }

    @Test
    void triggerSnapshot_whenNotLeader_returnsError() throws Exception {
        try (AeronSingleNodeHarness h = AeronSingleNodeHarness.start()) {
            assertTrue(h.container().isLeader(), "前置：单节点应为 leader");
            h.container().doStop();
            AdminResult result = h.container().triggerSnapshot().get(5, TimeUnit.SECONDS);
            assertFalse(result.success(), "stop 之后 triggerSnapshot 必须失败");
        }
    }

    private static List<Path> awaitDatFiles(Path snapshotsRoot) throws Exception {
        long deadline = System.currentTimeMillis() + SNAPSHOT_DEADLINE_MS;
        while (System.currentTimeMillis() < deadline) {
            try (Stream<Path> s = Files.list(snapshotsRoot)) {
                List<Path> files = s.filter(p -> p.getFileName().toString().endsWith(".dat")).toList();
                if (!files.isEmpty()) {
                    return files;
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError(".dat files 未在 " + SNAPSHOT_DEADLINE_MS + "ms 内落到 " + snapshotsRoot);
    }
}
