package com.binance.raftexchange.client;

import com.binance.raftexchange.stubs.report.HashCodeEntry;
import com.binance.raftexchange.stubs.report.ReportQuery;
import com.binance.raftexchange.stubs.report.ReportResult;
import com.binance.raftexchange.stubs.report.StateHashReportQuery;
import com.binance.raftexchange.stubs.report.StateHashReportResult;
import com.binance.raftexchange.stubs.report.SubmoduleType;
import com.binance.raftexchange.stubs.api.QueryServiceGrpc;
import com.google.gson.JsonObject;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * raft 基础设施验证：集群拓扑、三节点读写贯通、stateHash 收敛、快照（admin API 触发 + 健康）、
 * 故障注入（杀 follower 验 quorum + 重启重收敛）、换届（admin API 强制 leader 让位）。
 *
 * <p>快照/换届经 {@code RaftEndpoint} actuator（POST /raft/{snapshot,stepdown}、GET /raft/{snapshot,lag}）驱动；
 * 杀/起节点经 {@code start-local-cluster.sh stop-node/start-node}（见基类 {@code stopNode}/{@code startNode}）。
 *
 * <p>与业务 E2E（spot / futures / loan / mix）的分界是<b>是否依赖业务状态</b>：这里只做最小写入
 * （建两个用户 + 入金）来产生可复制的状态，不碰撮合、持仓、借贷。业务侧的三节点一致性检查
 * （如 {@code spot_3nodesReportSameBalance}）留在各自文件里，因为它们要断言的是该业务的数据。
 */
@EnabledIfSystemProperty(named = "livecluster", matches = "true") // 需本地三节点集群；@Disabled 在本模块被 junit-platform.properties 关掉了，用系统属性 gate
@Order(Integer.MAX_VALUE) // 破坏性用例（杀节点/换届），整个套件最后跑，见 junit-platform.properties
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveClusterRaftInfraE2ETest extends LiveClusterE2EBase {

    private long userA;
    private long userB;

    @Test
    @Order(1)
    void setup_minimalReplicatedState() {
        log("=== raft infra setup：制造一点可复制的状态 ===");
        ensureCurrency(USDT_ID, "USDT", USDT_DIGIT);

        userA = uid(1);
        userB = uid(2);
        ok(api.addUser(userA), "addUser A=" + userA);
        ok(api.addUser(userB), "addUser B=" + userB);
        ok(api.adjustUserBalance(userA, nextTxId(), USDT_ID, +12_345.678), "A +12345.678 USDT");
        ok(api.adjustUserBalance(userB, nextTxId(), USDT_ID, +999.5), "B +999.5 USDT");
    }

    @Test
    @Order(2)
    void clusterTopology_3nodes_1leader() {
        log("=== cluster topology sanity check ===");
        JsonObject cluster = fetchClusterJson();
        assertTrue(cluster != null, "无法读到 /raft/cluster，集群是否已启动？");

        assertEquals(3, cluster.get("node_count").getAsInt(), "node_count 应为 3");

        int leaderCount = 0;
        for (var el : cluster.getAsJsonArray("nodes")) {
            if ("LEADER".equals(el.getAsJsonObject().get("role").getAsString())) {
                leaderCount++;
            }
        }
        assertEquals(1, leaderCount, "整集群应有且仅有 1 个 LEADER");
        log("✓ cluster healthy: 3 nodes, 1 leader");
    }

    @Test
    @Order(3)
    void readWrite_visibleOnAllThreeNodes() throws Exception {
        log("=== 三节点读写贯通 ===");
        List<String[]> nodes = resolveAllNodes();
        assertTrue(nodes.size() >= 3, "应至少 3 节点，实际 " + nodes.size());

        BigDecimal expected = null;
        for (String[] node : nodes) {
            try (ExchangeApi nodeApi = connectTo(node)) {
                BigDecimal balance = nodeApi.queryUserReport(userA).get(10, TimeUnit.SECONDS)
                    .getAccounts().get(USDT_ID);
                log("  node " + node[0] + ":" + node[1] + "(" + node[2] + ") A balance=" + balance);
                if (expected == null) {
                    expected = balance;
                } else {
                    assertEquals(0, expected.compareTo(balance), "各节点余额应一致");
                }
            }
        }
        assertTrue(expected != null && expected.signum() > 0, "写入后余额应为正");
    }

    @Test
    @Order(4)
    void stateHash_convergesAcrossAllNodes() throws Exception {
        log("=== 3-node stateHash strict convergence ===");
        List<String[]> nodes = resolveAllNodes();
        assertTrue(nodes.size() >= 3, "至少 3 节点，实际 " + nodes.size());

        List<String> lastDivergences = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            Map<String, Map<Long, Integer>> perNode = new LinkedHashMap<>();
            for (String[] node : nodes) {
                Map<Long, Integer> flat = new HashMap<>();
                for (HashCodeEntry e : queryStateHashDirect(node[0], Integer.parseInt(node[1])).getHashCodesList()) {
                    long k = ((long)e.getKey().getModuleId() << 32) | (e.getKey().getSubmoduleTypeValue() & 0xFFFF_FFFFL);
                    flat.put(k, e.getValue());
                }
                perNode.put(node[0] + ":" + node[1] + "(" + node[2] + ")", flat);
            }

            Map.Entry<String, Map<Long, Integer>> baseEntry = perNode.entrySet().iterator().next();
            Map<Long, Integer> base = baseEntry.getValue();
            assertFalse(base.isEmpty(), "stateHash 应至少包含一个 submodule，实际为空");

            List<String> divergences = new ArrayList<>();
            for (Map.Entry<String, Map<Long, Integer>> entry : perNode.entrySet()) {
                if (entry.getKey().equals(baseEntry.getKey())) {
                    continue;
                }
                Map<Long, Integer> other = entry.getValue();
                if (base.size() != other.size()) {
                    divergences.add(entry.getKey() + " submodule count " + other.size() + " ≠ base " + base.size());
                    continue;
                }
                for (Map.Entry<Long, Integer> kv : base.entrySet()) {
                    Integer otherVal = other.get(kv.getKey());
                    if (!Objects.equals(kv.getValue(), otherVal)) {
                        divergences.add(entry.getKey() + " submodule " + decodeSubmoduleKey(kv.getKey()) + " hash="
                            + otherVal + " ≠ base " + baseEntry.getKey() + " hash=" + kv.getValue());
                    }
                }
            }
            if (divergences.isEmpty()) {
                log("  ✓ all nodes converged on " + base.size() + " submodule hashes (attempt " + attempt + ")");
                return;
            }
            log("  attempt " + attempt + ": " + divergences.size() + " divergences, waiting 1s ...");
            lastDivergences = divergences;
            TimeUnit.SECONDS.sleep(1);
        }

        log("  ✗ DIVERGENCES (" + lastDivergences.size() + ") after 10s:");
        lastDivergences.forEach(d -> log("    " + d));
        dumpUsersOnAllNodes(nodes);
        fail("raft 状态机 10s 内未收敛，最后一轮 " + lastDivergences.size() + " 个 submodule hash 不一致：" + lastDivergences);
    }

    // 快照测试只在小 margin 集群跑：JRaft 要求距上次快照的 log 距离 ≥ snapshotLogIndexMargin 才真落盘，
    // 生产默认 1000 万本地打不到。起集群时设 SNAPSHOT_MARGIN=<小值> 即可（见 start-local-cluster.sh）。
    private static final int MAX_TESTABLE_MARGIN = 500;

    /** 向 userB 连写 count 笔，推进 log index（快照前 burst 用）。 */
    private void advanceLog(int count) {
        for (int i = 0; i < count; i++) {
            ok(api.adjustUserBalance(userB, nextTxId(), USDT_ID, +0.001), "advance log #" + i);
        }
    }

    @Test
    @Order(5)
    void snapshot_triggerViaAdminApi_succeedsAndHealthy() {
        log("=== 快照：admin API 触发 + 健康 ===");
        int leaderMgmt = leaderMgmtPort();
        int margin = snapshotLogIndexMargin(leaderMgmt);
        org.junit.jupiter.api.Assumptions.assumeTrue(margin <= MAX_TESTABLE_MARGIN,
            "快照测试需小 SNAPSHOT_MARGIN 集群，当前 margin=" + margin + "，跳过");

        long saveBefore = snapshotStatus(leaderMgmt).get("save.success_count").getAsLong();
        advanceLog(margin + 50); // 写够 log 距离，越过 margin 门槛
        JsonObject r = triggerSnapshot();
        assertEquals("success", r.get("status").getAsString(), "POST /raft/snapshot 应成功: " + r);

        JsonObject after = snapshotStatus(leaderMgmt);
        assertTrue(after.get("save.success_count").getAsLong() > saveBefore,
            "save.success_count 应递增: " + saveBefore + " → " + after.get("save.success_count"));
        assertTrue(after.get("load.healthy").getAsBoolean(), "load 应健康(无失败): " + after);
        log("✓ 快照触发成功，save_count " + saveBefore + " → " + after.get("save.success_count"));
    }

    @Test
    @Order(6)
    void killFollower_quorumHolds_thenRejoinsAndConverges() throws Exception {
        log("=== 故障注入：杀 follower → quorum 2/3 可写 → 重启重收敛 ===");
        String followerGrpc = null;
        for (String[] n : resolveAllNodes()) {
            if ("FOLLOWER".equals(n[2])) {
                followerGrpc = n[1];
                break;
            }
        }
        assertTrue(followerGrpc != null, "应存在 follower");
        int followerIdx = nodeIndexOfGrpcPort(followerGrpc);

        try {
            stopNode(followerIdx);
            clearNodeData(followerIdx); // 诊断：清本地快照/日志，逼重启后从 leader 全量 install-snapshot
            // 3 节点挂 1 仍达 quorum(2/3)，写入应成功（client 收到 NEED_MOVE 自动指向 leader）
            ok(api.adjustUserBalance(userA, nextTxId(), USDT_ID, +7.0), "杀 follower 后仍可写(quorum 2/3)");
        } finally {
            startNode(followerIdx); // 无论断言成败都把节点拉起来，别污染后续用例
        }
        // 重启的 follower 需从快照 + 日志恢复并追平；先等余额、再等全 submodule stateHash 严格收敛
        awaitBalanceConvergence(userA, 60_000L);
        awaitStateHashConverged(90_000L); // 强判据：重启后所有 submodule 必须一致（隔离"重启本身是否分叉"）
        log("✓ follower 重启后三节点 stateHash 严格收敛");
    }

    @Test
    @Order(7)
    void failover_stepDownViaAdminApi_newLeaderElected() throws Exception {
        log("=== 换届：admin API 强制 leader 让位 ===");
        String oldLeader = leaderOf(resolveAllNodes());
        assertTrue(oldLeader != null, "换届前应存在 leader");

        JsonObject r = stepDownLeader();
        assertEquals("success", r.get("status").getAsString(), "POST /raft/stepdown 应成功: " + r);

        long deadline = System.currentTimeMillis() + 30_000L;
        String newLeader = oldLeader;
        while (System.currentTimeMillis() < deadline) {
            TimeUnit.SECONDS.sleep(1);
            newLeader = leaderOf(resolveAllNodes());
            if (newLeader != null && !newLeader.equals(oldLeader)) {
                break;
            }
        }
        assertNotEquals(oldLeader, newLeader, "stepdown 后应选出新 leader");
        ok(api.adjustUserBalance(userA, nextTxId(), USDT_ID, +1.0), "换届后写入仍成功");
        awaitStateHashConverged(60_000L); // 换届不应引入分叉
        log("✓ leader " + oldLeader + " → " + newLeader + "，写入未中断且 stateHash 收敛");
    }

    @Test
    @Order(8)
    void consecutiveSnapshots_noCleanupFailure() {
        log("=== 连续快照无泄漏 ===");
        int leaderMgmt = leaderMgmtPort();
        int margin = snapshotLogIndexMargin(leaderMgmt);
        org.junit.jupiter.api.Assumptions.assumeTrue(margin <= MAX_TESTABLE_MARGIN,
            "快照测试需小 SNAPSHOT_MARGIN 集群，当前 margin=" + margin + "，跳过");

        for (int i = 1; i <= 3; i++) {
            advanceLog(margin + 50); // 每次快照前都要重新推进 log 距离，否则 JRaft 拒绝
            JsonObject r = triggerSnapshot();
            assertEquals("success", r.get("status").getAsString(), "第 " + i + " 次快照应成功: " + r);
        }
        long cleanupFailures = raftLag(leaderMgmt).get("snapshot_cleanup_failures").getAsLong();
        assertEquals(0, cleanupFailures, "连续快照不应有清理失败(旧快照目录泄漏)");
        awaitStateHashConverged(60_000L); // 连续快照不应引入分叉
        log("✓ 连续 3 次快照，cleanup 失败数 = 0 且 stateHash 收敛");
    }

    /** 轮询直到三节点对 uid 的 USDT 余额一致，或超时 fail（重启节点追平后的收敛判据）。 */
    private void awaitBalanceConvergence(long uid, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = "";
        while (System.currentTimeMillis() < deadline) {
            List<String[]> nodes = resolveAllNodes();
            if (nodes.size() >= 3) {
                BigDecimal expected = null;
                boolean converged = true;
                StringBuilder sb = new StringBuilder();
                for (String[] node : nodes) {
                    try (ExchangeApi nodeApi = connectTo(node)) {
                        BigDecimal bal = nodeApi.queryUserReport(uid).get(10, TimeUnit.SECONDS)
                            .getAccounts().get(USDT_ID);
                        sb.append(node[1]).append("(").append(node[2]).append(")=").append(bal).append(" ");
                        if (expected == null) {
                            expected = bal;
                        } else if (bal == null || expected.compareTo(bal) != 0) {
                            converged = false;
                        }
                    } catch (Exception e) {
                        converged = false;
                        sb.append(node[1]).append("=ERR ");
                    }
                }
                last = sb.toString();
                if (converged && expected != null) {
                    return;
                }
            }
            TimeUnit.SECONDS.sleep(2);
        }
        fail("三节点 " + timeoutMs + "ms 内未对 uid=" + uid + " 收敛，最后: " + last);
    }

    // ================================================================
    // helpers
    // ================================================================

    private ExchangeApi connectTo(String[] node) {
        return ExchangeApi.connect(node[0], Integer.parseInt(node[1]),
            ExchangeApiOptions.builder().sendTimeout(java.time.Duration.ofSeconds(10)).build());
    }

    private static String leaderOf(List<String[]> nodes) {
        for (String[] n : nodes) {
            if ("LEADER".equals(n[2])) {
                return n[0] + ":" + n[1];
            }
        }
        return null;
    }

    private void dumpUsersOnAllNodes(List<String[]> nodes) {
        for (long uid : new long[] {userA, userB}) {
            log("  ── uid=" + uid + " per-node state ──");
            for (String[] node : nodes) {
                String tag = node[0] + ":" + node[1] + "(" + node[2] + ")";
                try (ExchangeApi nodeApi = connectTo(node)) {
                    log("    " + tag + ": accounts="
                        + nodeApi.queryUserReport(uid).get(10, TimeUnit.SECONDS).getAccounts());
                } catch (Exception e) {
                    log("    " + tag + ": ERR " + e.getMessage());
                }
            }
        }
    }

    /** 绕过 client 的 leader 路由，直连指定节点拿 stateHash——收敛检查必须逐节点问。 */
    private static StateHashReportResult queryStateHashDirect(String host, int grpcPort) {
        ManagedChannel ch = NettyChannelBuilder.forAddress(host, grpcPort).usePlaintext().build();
        try {
            ReportResult r = QueryServiceGrpc.newBlockingStub(ch)
                .query(ReportQuery.newBuilder().setTransferId((int)(System.nanoTime() & 0x7FFF_FFFFL))
                    .setStateHash(StateHashReportQuery.getDefaultInstance()).build());
            return r.getStateHash();
        } finally {
            ch.shutdownNow();
        }
    }

    private static String decodeSubmoduleKey(long k) {
        int moduleId = (int)(k >>> 32);
        int submoduleType = (int)k;
        SubmoduleType st;
        try {
            st = SubmoduleType.forNumber(submoduleType);
        } catch (Exception e) {
            st = null;
        }
        return "(module=" + moduleId + "," + (st != null ? st.name() : "code=" + submoduleType) + ")";
    }
}
