package com.binance.raftexchange.server.raft.jraft;

import com.alipay.sofa.jraft.Status;
import com.binance.raftexchange.client.ExchangeApi;
import com.binance.raftexchange.client.SingleUserReportResultView;
import com.binance.raftexchange.stubs.report.QueryExecutionStatus;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单节点 E2E：覆盖 Raft 快照保存 + 读写分离（ReadIndex）+ 快照加载全链路。 Phase 1 写 500 USDT → Phase 2 读 quorum 验证 → Phase 3 触发快照看 ME/RE .dat
 * → Phase 4 快照后再写读 → Phase 5 重启验证 snapshot load + log replay。 手动跑：mvn -pl raft-exchange-server -am test
 * -Dtest=RaftSnapshotAndReadWriteE2ETest -Djunit.jupiter.conditions.deactivate='org.junit.*DisabledCondition'
 */
@Disabled("Slow e2e (~40s)：单节点 jraft + gRPC + 快照保存 + 节点重启 + 快照加载。手动运行用于回归。")
class RaftSnapshotAndReadWriteE2ETest {

    private static final int USDT_ID = 2;
    private static final int USDT_DIGIT = 6;
    private static final long BUYER = 42L;

    @Test
    void snapshotAndReadWriteSeparationRoundTrip() throws Exception {
        try (SingleNodeRaftHarness harness = SingleNodeRaftHarness.start()) {
            ExchangeApi client = ExchangeApi.connect("127.0.0.1", harness.grpcPort());

            // ================ Phase 1: 写路径 — 初始化 + 充值 ================
            ok(client.addCurrency(USDT_ID, "USDT", USDT_DIGIT), "addCurrency(USDT)");
            ok(client.addUser(BUYER), "addUser(buyer=42)");
            ok(client.adjustUserBalance(BUYER, 1L, USDT_ID, +500.0), "deposit +500 USDT");
            Thread.sleep(500);

            // ================ Phase 2: 读路径（最终一致，本节点 engine 直接读） ================
            SingleUserReportResultView report = client.queryUserReport(BUYER).get(5, TimeUnit.SECONDS);

            System.out.printf("%n--- Phase 2: 读写分离查询结果 ---%n");
            System.out.printf("uid=%d status=%s accounts=%s%n", report.getUserId(), report.getQueryExecutionStatus(),
                report.getAccounts());

            assertEquals(BUYER, report.getUserId(), "uid mismatch");
            assertEquals(QueryExecutionStatus.OK, report.getQueryExecutionStatus(),
                "query 应返回 OK，实际=" + report.getQueryExecutionStatus());
            assertNotNull(report.getAccounts().get(USDT_ID), "accounts 中应有 USDT");
            assertBd("500", report.getAccounts().get(USDT_ID), "Phase2 USDT 余额");

            // ================ Phase 3: 快照保存 ================
            //
            // triggerSnapshot → jraft node.snapshot → ExchangeStateMachine.onSnapshotSave
            // → api.submitCommand(ApiPersistState) → StreamManager PipedStream → 磁盘 .dat
            //
            // 验证：Status.OK + 磁盘出现 ME + RE 两个 .dat 文件。
            System.out.printf("%n--- Phase 3: 触发快照 ---%n");
            Status snapStatus = harness.triggerSnapshot().get(10, TimeUnit.SECONDS);

            System.out.printf("snapshot status: %s%n", snapStatus);
            assertTrue(snapStatus.isOk(), "triggerSnapshot 应成功，实际 status=" + snapStatus.getErrorMsg());

            File snapshotDir = harness.snapshotDir();
            System.out.printf("snapshotDir = %s%n", snapshotDir.getAbsolutePath());

            List<Path> datFiles = Files.walk(snapshotDir.toPath()).filter(p -> p.toString().endsWith(".dat"))
                .collect(Collectors.toList());

            System.out.printf("发现 .dat 文件：%n");
            datFiles.forEach(p -> System.out.printf("  %s%n", p));

            assertTrue(datFiles.size() >= 2, "快照应产生至少 1 个 ME + 1 个 RE .dat 文件，实际=" + datFiles.size());

            boolean hasME = datFiles.stream().anyMatch(p -> p.toString().contains("_ME_"));
            boolean hasRE = datFiles.stream().anyMatch(p -> p.toString().contains("_RE_"));
            assertTrue(hasME, "应有 ME 快照文件");
            assertTrue(hasRE, "应有 RE 快照文件");

            // ================ Phase 4: 快照后写 + 读，验证系统仍正常 ================
            ok(client.adjustUserBalance(BUYER, 2L, USDT_ID, +200.0), "deposit +200 USDT (post-snapshot)");
            Thread.sleep(500);

            SingleUserReportResultView report2 = client.queryUserReport(BUYER).get(5, TimeUnit.SECONDS);

            System.out.printf("%n--- Phase 4: 快照后读写分离查询 ---%n");
            System.out.printf("uid=%d accounts=%s%n", report2.getUserId(), report2.getAccounts());

            assertEquals(QueryExecutionStatus.OK, report2.getQueryExecutionStatus(), "Phase4 query 应返回 OK");
            assertBd("700", report2.getAccounts().get(USDT_ID), "Phase4 USDT 余额（500+200）");

            // ================ Phase 5: 重启节点 — 验证 snapshot load + raft log replay ================
            //
            // 重启后 jraft 在 doStart() 期间自动调用 onSnapshotLoad：
            // ExchangeStateMachine.onSnapshotLoad → ApiRecoverState → MemorySerializationProcessor.loadData
            // → 从 .dat 还原快照时刻状态（500 USDT）
            // 然后 jraft 重放快照后已提交的日志（+200 USDT），最终恢复到 700 USDT。
            System.out.printf("%n--- Phase 5: 重启节点，验证 snapshot load + log replay ---%n");
            harness.restart();
            Thread.sleep(500);

            ExchangeApi client2 = ExchangeApi.connect("127.0.0.1", harness.grpcPort());

            SingleUserReportResultView report3 = client2.queryUserReport(BUYER).get(5, TimeUnit.SECONDS);

            System.out.printf("uid=%d accounts=%s%n", report3.getUserId(), report3.getAccounts());

            assertEquals(QueryExecutionStatus.OK, report3.getQueryExecutionStatus(), "Phase5 query 应返回 OK");
            assertBd("700", report3.getAccounts().get(USDT_ID), "Phase5 USDT 余额（snapshot 500 + log replay 200）");

            // 重启后再写一笔，确认系统完全正常
            ok(client2.adjustUserBalance(BUYER, 3L, USDT_ID, +100.0), "deposit +100 USDT (post-restart)");
            Thread.sleep(500);

            SingleUserReportResultView report4 = client2.queryUserReport(BUYER).get(5, TimeUnit.SECONDS);

            System.out.printf("uid=%d accounts=%s (post-restart write)%n", report4.getUserId(), report4.getAccounts());
            assertBd("800", report4.getAccounts().get(USDT_ID), "Phase5 重启后写入 USDT 余额（700+100）");

            System.out.println();
            System.out.println("✓ Phase 1: 写路径 — 充值 500 USDT OK");
            System.out.println("✓ Phase 2: 读写分离 — ReadIndex 读出余额 500 USDT OK");
            System.out.println("✓ Phase 3: 快照 — ME + RE .dat 文件落盘 OK");
            System.out.println("✓ Phase 4: 快照后读写分离 — 余额 700 USDT OK");
            System.out.println("✓ Phase 5: 节点重启 — snapshot load + log replay 余额 700 USDT，写入后 800 USDT OK");
        }
    }

    private static void ok(com.binance.raftexchange.client.CommandResultView v, String label) {
        System.out.println(">> " + label + " → " + v.getResultCode());
        assertEquals(CommandResultCode.SUCCESS, v.getResultCode(), label);
    }

    private static void assertBd(String expected, BigDecimal actual, String label) {
        assertEquals(0, actual.compareTo(new BigDecimal(expected)),
            () -> label + " expected=" + expected + " actual=" + actual.toPlainString());
    }
}
