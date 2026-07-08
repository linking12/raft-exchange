package com.binance.raftexchange.client;

import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.SymbolType;
import com.binance.raftexchange.stubs.api.ServerNodeServiceGrpc;
import com.binance.raftexchange.stubs.request.NodeListCommand;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import com.binance.raftexchange.stubs.response.NodeList;
import com.binance.raftexchange.stubs.response.NodeType;
import com.binance.raftexchange.stubs.response.ServerNode;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leader failover E2E：让 leader 主动让位（POST /raft/stepdown），验证： - 集群在 ≤10s 内选出新 leader（不同节点） - 新 leader
 * 接受写命令：adjustUserBalance + placeOrder + cancel 三类命令均落账成功 - 多交易对（BTC/USDT, ETH/USDT）的 orderbook 在新 leader 上都可用 - 3 节点仍然
 * healthy（不需要重启任何进程）
 *
 * <p>
 * 区别于 kill -9：stepDown 是优雅切换，集群保持 3 节点。跑完不需要任何重启操作， 可以反复跑，每次 leader 在三个节点之间漂移。
 *
 * <p>
 * 启动：
 * 
 * <pre>
 *   ./start-local-cluster.sh start full
 *   mvn -pl raft-exchange-client test -Dtest=LiveClusterFailoverE2ETest \
 *       "-Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition"
 * </pre>
 */
@Disabled("需要本地三节点集群运行中")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveClusterFailoverE2ETest {

    private static final String HOST = "127.0.0.1";
    private static final int[] GRPC_PORTS = {5001, 5002, 5003};

    // 共享货币：USDT 用于充值断言；BTC/ETH 给 2 个 spot 交易对做底仓，验证多 symbol orderbook 在 failover 后可用
    private static final int USDT_ID = 2;
    private static final int BTC_ID = 3;
    private static final int ETH_ID = 4;

    private long uid;
    private long txId;
    private long orderId;
    private int spotA; // BTC/USDT，动态 id 避开其他 E2E
    private int spotB; // ETH/USDT

    private long nextTx() {
        return txId++;
    }

    private long nextOrder() {
        return orderId++;
    }

    @BeforeAll
    void setup() {
        long runId = System.nanoTime() & 0xFFFFFL;
        uid = 6_000_000_000L + runId;
        txId = System.currentTimeMillis() * 1_000L;
        orderId = txId + 7_000_000_000_000L;
        spotA = (int)(2_000_000 + runId % 100_000);
        spotB = spotA + 1;
    }

    @Test
    @Order(1)
    void failover_stepDown_newLeaderElected_andAcceptsWrites() throws Exception {
        log("=== leader failover via /raft/stepdown ===");

        ServerNode oldLeader = fetchLeader();
        String oldLeaderHost = oldLeader.getHost();
        int oldLeaderGrpc = oldLeader.getPort();
        int oldLeaderMgmt = grpcToMgmt(oldLeaderGrpc);
        log("  current leader: " + oldLeaderHost + " grpc=" + oldLeaderGrpc + " mgmt=" + oldLeaderMgmt);

        // 在旧 leader 上准备：货币 / 用户 / 余额 / 2 个 spot symbol（failover 后用来验 matching engine）
        try (ExchangeApi oldApi = connect(oldLeaderHost, oldLeaderGrpc)) {
            ensureCurrency(oldApi, USDT_ID, "USDT", 6);
            ensureCurrency(oldApi, BTC_ID, "BTC", 8);
            ensureCurrency(oldApi, ETH_ID, "ETH", 8);
            ok(oldApi.addUser(uid), "addUser " + uid + " on old leader");
            ok(oldApi.adjustUserBalance(uid, nextTx(), USDT_ID, +1000.0), "old leader +1000 USDT");
            // 给 BTC/ETH 各一点底仓，failover 后用做 ASK 单的 base 来源
            ok(oldApi.adjustUserBalance(uid, nextTx(), BTC_ID, +0.1), "old leader +0.1 BTC (底仓)");
            ok(oldApi.adjustUserBalance(uid, nextTx(), ETH_ID, +1.0), "old leader +1 ETH (底仓)");

            ensureSymbol(oldApi, spotA, BTC_ID, "BTC/USDT spot " + spotA);
            ensureSymbol(oldApi, spotB, ETH_ID, "ETH/USDT spot " + spotB);

            BigDecimal balBefore = oldApi.queryUserReport(uid).get(5, TimeUnit.SECONDS).getAccounts().get(USDT_ID);
            assertEquals(0, balBefore.compareTo(new BigDecimal("1000")), "failover 前余额应为 1000，实际=" + balBefore);
        }

        // 触发 leader 让位
        String resp = postLeadershipStepDown(oldLeaderMgmt);
        log("  stepDown response: " + resp);
        assertTrue(resp.contains("\"status\":\"success\""), "POST /raft/stepdown 应返回 success，实际=" + resp);

        // 轮询等待新 leader 选出（不同节点）
        log("  polling for new leader ...");
        String newLeaderHost = null;
        int newLeaderGrpc = -1;
        for (int i = 0; i < 20; i++) {
            Thread.sleep(500);
            Optional<ServerNode> leaderOpt = tryFetchLeader();
            if (leaderOpt.isEmpty()) {
                log("  poll " + i + ": no leader yet");
                continue;
            }
            ServerNode leader = leaderOpt.get();
            String h = leader.getHost();
            int p = leader.getPort();
            if (!h.equals(oldLeaderHost) || p != oldLeaderGrpc) {
                newLeaderHost = h;
                newLeaderGrpc = p;
                log("  poll " + i + ": new leader elected = " + h + ":" + p);
                break;
            }
            log("  poll " + i + ": leader still old (" + h + ":" + p + ")");
        }
        assertNotNull(newLeaderHost, "10s 内未选出新 leader");
        assertNotEquals(oldLeaderGrpc, newLeaderGrpc, "新 leader 不应是旧 leader");

        // 在新 leader 上下三类写命令，验证集群完整可写
        try (ExchangeApi newApi = connect(newLeaderHost, newLeaderGrpc)) {
            // 等新 leader 完成 raft 初始化（避免命中 NEED_MOVE / NO_LEADER）
            Thread.sleep(500);

            // 1) BALANCE_ADJUSTMENT
            ok(newApi.adjustUserBalance(uid, nextTx(), USDT_ID, +500.0), "new leader +500 USDT");
            BigDecimal balAfter = newApi.queryUserReport(uid).get(5, TimeUnit.SECONDS).getAccounts().get(USDT_ID);
            log("  USDT after failover write: " + balAfter);
            assertEquals(0, balAfter.compareTo(new BigDecimal("1500")),
                "failover 后余额应为 1500（旧 1000 + 新 500），实际=" + balAfter);

            // 2) PLACE_ORDER + CANCEL on 两个 spot symbol，验证 matching engine 在新 leader 上也通
            long oidA = nextOrder();
            ok(newApi.placeOrder(uid, oidA, spotA, OrderAction.ASK, OrderType.GTC, 50000.0, 0.0, 0.01, null, 0, false),
                "new leader ASK 0.01 BTC @ 50000 on spotA");
            ok(newApi.cancelOrder(uid, oidA, spotA), "new leader CANCEL ASK on spotA");

            long oidB = nextOrder();
            ok(newApi.placeOrder(uid, oidB, spotB, OrderAction.ASK, OrderType.GTC, 3000.0, 0.0, 0.1, null, 0, false),
                "new leader ASK 0.1 ETH @ 3000 on spotB");
            ok(newApi.cancelOrder(uid, oidB, spotB), "new leader CANCEL ASK on spotB");
        }

        // 健康检查：仍然 3 节点 1 leader
        List<ServerNode> after = fetchAllNodes();
        assertEquals(3, after.size(), "failover 后仍应有 3 节点");
        int leaderCount = (int)after.stream().filter(n -> n.getType() == NodeType.LEADER).count();
        assertEquals(1, leaderCount, "整集群应有且仅 1 个 leader，实际 " + leaderCount);
        log("✓ failover OK: leader " + oldLeaderHost + ":" + oldLeaderGrpc + " → " + newLeaderHost + ":" + newLeaderGrpc
            + ", cluster 3 nodes healthy");
    }

    @AfterAll
    void teardown() {
        // 无外部资源；ExchangeApi 都已 try-with-resources 关闭
    }

    // ---------- helpers ----------

    private static int grpcToMgmt(int grpcPort) {
        // start-local-cluster.sh：grpc=5000+n, mgmt=28080+n
        return 28080 + (grpcPort - 5000);
    }

    private static ExchangeApi connect(String host, int grpcPort) {
        return ExchangeApi.connect(host, grpcPort,
            ExchangeApiOptions.builder().sendTimeout(Duration.ofSeconds(15)).build());
    }

    private static void ensureCurrency(ExchangeApi api, int id, String name, int digit) {
        if (!api.getMetadataManager().currencyExists(id)) {
            ok(api.addCurrency(id, name, digit), "addCurrency " + name);
        }
    }

    /** 注册 spot symbol（base→quote = USDT）；幂等：已存在则跳过。统一 takerFee/makerFee 配置，跟其他 spot E2E 对齐。 */
    private static void ensureSymbol(ExchangeApi api, int symbolId, int baseCurrency, String label) {
        if (!api.getMetadataManager().symbolExists(symbolId)) {
            ok(api.addSymbol(symbolId, SymbolType.CURRENCY_EXCHANGE_PAIR, baseCurrency, USDT_ID, 100_000_000L,
                1_000_000L, 1_000L, 500L, 0L, 1_000_000L, 0L, 0L, null, 0L, null), "addSymbol " + label);
        }
    }

    private static void ok(CommandResultView v, String label) {
        log(String.format("[%-50s] %s", label, v.getResultCode()));
        assertTrue(isOk(v.getResultCode()), label + " expected SUCCESS (or idempotent), got " + v.getResultCode());
    }

    private static boolean isOk(CommandResultCode code) {
        return code == CommandResultCode.SUCCESS || code == CommandResultCode.USER_MGMT_USER_ALREADY_EXISTS
            || code == CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME
            || code == CommandResultCode.SYMBOL_MGMT_SYMBOL_ALREADY_EXISTS;
    }

    private static void log(String m) {
        System.out.println(m);
    }

    /**
     * 走生产 client 同款的 ServerNodeService.listNodes：本地读 raft 拓扑，无 ReadIndex、无 fsm 等待。 任一 grpc 端口可用即返回该节点视角的节点列表。
     */
    private static List<ServerNode> fetchAllNodes() {
        for (int grpc : GRPC_PORTS) {
            ManagedChannel ch = NettyChannelBuilder.forAddress(HOST, grpc).usePlaintext().build();
            try {
                NodeList nodes = ServerNodeServiceGrpc.newBlockingStub(ch).withDeadlineAfter(2, TimeUnit.SECONDS)
                    .listNodes(NodeListCommand.getDefaultInstance());
                return nodes.getNodesList();
            } catch (Exception ignored) {
                // try next port
            } finally {
                ch.shutdownNow();
            }
        }
        throw new IllegalStateException("无法从任何 grpc 端口拿到 listNodes: " + java.util.Arrays.toString(GRPC_PORTS));
    }

    /** 必须拿到 leader——找不到直接抛（初始状态 / 终态都要求集群已稳定）。 */
    private static ServerNode fetchLeader() {
        return tryFetchLeader().orElseThrow(() -> new IllegalStateException("集群无 leader 或所有 grpc 端口不可达"));
    }

    /** 容忍选举窗口：所有端口可达但暂无 leader、或个别端口不可达，都返回 Optional.empty()。 */
    private static Optional<ServerNode> tryFetchLeader() {
        try {
            return fetchAllNodes().stream().filter(n -> n.getType() == NodeType.LEADER).findFirst();
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
    }

    /** POST /raft/stepdown 让指定节点 step down。 */
    private static String postLeadershipStepDown(int mgmtPort) throws Exception {
        HttpURLConnection conn =
            (HttpURLConnection)new URL("http://" + HOST + ":" + mgmtPort + "/raft/stepdown").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(2000);
        conn.setReadTimeout(5000);
        conn.setDoOutput(true);
        conn.getOutputStream().write("{}".getBytes());
        conn.getOutputStream().close();
        int status = conn.getResponseCode();
        java.io.InputStream is = status == 200 ? conn.getInputStream() : conn.getErrorStream();
        return is == null ? "" : new String(is.readAllBytes());
    }
}
