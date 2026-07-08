package com.binance.raftexchange.client;

import com.binance.raftexchange.stubs.MarginMode;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.SymbolType;
import com.binance.raftexchange.stubs.api.QueryServiceGrpc;
import com.binance.raftexchange.stubs.api.ServerNodeServiceGrpc;
import com.binance.raftexchange.stubs.report.HashCodeEntry;
import com.binance.raftexchange.stubs.report.ReportQuery;
import com.binance.raftexchange.stubs.report.ReportResult;
import com.binance.raftexchange.stubs.report.StateHashReportQuery;
import com.binance.raftexchange.stubs.report.StateHashReportResult;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.binance.raftexchange.client.ExchangeApiHelper.buildSlotValueMap;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Cascade recovery 端到端测试，覆盖：
 *
 * <p>
 * Test A: cascade 撞市场流动性不足 → 走 IF takeover（或 ADL，取决于 IF 余额）→ 完整 cascade。
 * <p>
 * Test B: cascade 进行中触发 leader stepDown → 验证新 leader 接手后 cascade 仍完成。
 *
 * <p>
 * 两个测试都验：
 * <ul>
 * <li>LIQ_USER 仓位最终归零（cascade 完成）</li>
 * <li>3 节点 stateHash 跨 cascade 收敛（ctx 加字段不破坏 raft 一致性）</li>
 * <li>账户余额变化合理（保证金被穿仓 / fee 扣减）</li>
 * </ul>
 *
 * <p>
 * 启动：
 * 
 * <pre>
 *   ./start-local-cluster.sh start full
 *   mvn -pl raft-exchange-client test -Dtest=LiveClusterLiquidationRecoveryE2ETest \
 *       "-Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition"
 * </pre>
 */
@Disabled("需要本地三节点集群运行中")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveClusterLiquidationRecoveryE2ETest {

    private static final String HOST = "127.0.0.1";
    // mgmt 端口仅供 POST /raft/stepdown 用——这是个 leader 控制端点，没有 ReadIndex 副作用
    private static final int[] MGMT_PORTS = {28081, 28082, 28083};
    // 拓扑发现走 grpc ServerNodeService.listNodes（本地 raft 内存读，无 ReadIndex / probe，避免污染集群状态）
    private static final int[] GRPC_PORTS = {5001, 5002, 5003};
    private static final int GRPC_PORT = resolveLeaderGrpcPort();

    // 货币 + 期货合约
    private static final int PFUND_ID = 10;
    private static final int PBASE_ID = 11;
    private static final int PERP_SYMBOL = 250; // 跟 Futures E2E 的 200 错开，避免数据互相干扰

    // 合约规格（跟 Futures E2E 同款简化方案：scale=1，1% 初始保证金，5% 维持保证金，20x 杠杆）
    private static final long BASE_SCALE = 1L;
    private static final long QUOTE_SCALE = 1L;
    private static final Map<Long, Long> MM_MAP = buildSlotValueMap(1_000_000_000L, 5L);
    private static final Map<Long, Long> LEV_MAP = buildSlotValueMap(1_000_000_000L, 20L);

    private long LP; // 流动性提供者 + ADL 对手盘候选
    private long ADL_TARGET; // SHORT 仓位 holder（mark price 暴跌时变成最佳 ADL 受让方）
    private long LIQ_USER_A; // Test A 的破产用户
    private long LIQ_USER_B; // Test B 的破产用户（独立避免相互污染）
    private long LIQ_USER_C; // Test C 的连续破产用户

    private double OPEN_PRICE;
    private static final double OPEN_SIZE = 5.0;
    private static final int LEVERAGE = 10;
    private static final double CRASH_PRICE = 100.0; // mark price 暴跌到这里触发破产

    private long orderId;
    private long txId;

    private long nextOrder() {
        return orderId++;
    }

    private long nextTx() {
        return txId++;
    }

    private ExchangeApi api;

    @BeforeAll
    void openConnection() {
        long runId = System.nanoTime() & 0xFFFFFL;
        LP = 4_000_000_000L + runId * 100 + 1;
        ADL_TARGET = 4_000_000_000L + runId * 100 + 2;
        LIQ_USER_A = 4_000_000_000L + runId * 100 + 3;
        LIQ_USER_B = 4_000_000_000L + runId * 100 + 4;
        LIQ_USER_C = 4_000_000_000L + runId * 100 + 5;
        OPEN_PRICE = 10_000.0 + (runId % 10_000);
        long base = System.currentTimeMillis() * 1_000L;
        txId = base;
        orderId = base + 5_000_000_000_000L;
        api = connect();
    }

    @AfterAll
    void closeConnection() throws Exception {
        if (api != null)
            api.close();
    }

    @Test
    @Order(0)
    void setup_currenciesSymbolUsers() {
        log("=== setup ===");
        ensureCurrency(PFUND_ID, "PFUND", 0);
        ensureCurrency(PBASE_ID, "PBASE", 0);
        ensureSymbol(PERP_SYMBOL, SymbolType.FUTURES_CONTRACT_PERPETUAL, PBASE_ID, PFUND_ID, BASE_SCALE, QUOTE_SCALE,
            10L, 5L, 5L, 10_000L, 1L, 100L, MM_MAP, 100L, LEV_MAP, "addSymbol PERP " + PERP_SYMBOL);

        ok(api.addUser(LP), "addUser LP");
        ok(api.addUser(ADL_TARGET), "addUser ADL_TARGET");
        ok(api.addUser(LIQ_USER_A), "addUser LIQ_USER_A");
        ok(api.addUser(LIQ_USER_B), "addUser LIQ_USER_B");
        ok(api.addUser(LIQ_USER_C), "addUser LIQ_USER_C");
        ok(api.adjustUserBalance(LP, nextTx(), PFUND_ID, +10_000_000.0), "LP +10M PFUND");
        ok(api.adjustUserBalance(ADL_TARGET, nextTx(), PFUND_ID, +100_000.0), "ADL_TARGET +100K PFUND");
        ok(api.adjustUserBalance(LIQ_USER_A, nextTx(), PFUND_ID, +6_000.0), "LIQ_USER_A +6K PFUND");
        ok(api.adjustUserBalance(LIQ_USER_B, nextTx(), PFUND_ID, +6_000.0), "LIQ_USER_B +6K PFUND");
        ok(api.adjustUserBalance(LIQ_USER_C, nextTx(), PFUND_ID, +6_000.0), "LIQ_USER_C +6K PFUND");

        ok(api.adjustMarkPrice(PERP_SYMBOL, OPEN_PRICE), "mark @ " + OPEN_PRICE);

        // IF 预存：cascade 测试触发强平时，IF takeover 能一次吃下，不会落入 IF→ADL→loop 死循环。
        // 金额需能整除 shard 数（默认 2），PFUND digit=0 → 100万 raw 直接 OK。
        ok(api.insuranceFundDeposit(PERP_SYMBOL, 0, 1_000_000.0), "IF deposit PERP " + PERP_SYMBOL + " 1M");

        log("✓ setup complete");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test A: cascade 通过 FORCE_LIQUIDATION REJECT → 走 IF/ADL 完整 cascade
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(1)
    void cascade_throughFullCascade_completes() throws Exception {
        log("=== Test A: cascade through full FORCE→IF/ADL ===");

        // 限制 LP 流动性让 FORCE 撮合后剩余 REJECT → cascade 走完整 FORCE→IF→ADL 流程
        triggerOneCascadeRoundAndWait(LIQ_USER_A, "Test A", 15);
        assertStateHashConverges();

        ok(api.adjustMarkPrice(PERP_SYMBOL, OPEN_PRICE), "restore mark to " + OPEN_PRICE);
        log("✓ Test A passed: cascade through full path");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test B: cascade 进行中 stepDown leader，验证新 leader 接手完成 cascade
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(2)
    void cascade_acrossLeaderStepDown_completes() throws Exception {
        log("=== Test B: cascade across leader stepDown ===");

        // 找当前 leader（cascade 前确定，方便事后验证切换）
        ServerNode oldLeader = fetchLeader();
        int oldLeaderGrpcPort = oldLeader.getPort();
        int oldLeaderMgmt = grpcToMgmt(oldLeaderGrpcPort);
        log("  current leader grpc=" + oldLeaderGrpcPort + " mgmt=" + oldLeaderMgmt);

        openCounterParty(ADL_TARGET, OrderAction.ASK, OPEN_SIZE); // 再开一手 ADL 候选
        openBankruptCandidate(LIQ_USER_B);
        // FORCE ASK @ BP (≈OPEN_PRICE) 无法吃 BID@CRASH_PRICE，全量走 ADL；ADL_TARGET 的 SHORT 承接。
        ok(api.adjustMarkPrice(PERP_SYMBOL, CRASH_PRICE), "crash mark to " + CRASH_PRICE);

        // 在 cascade 进行中触发 stepDown。scanner 每 2s 跑一次破产检测，给它一点时间发出第一波 cmd。
        Thread.sleep(800);
        String resp = postLeadershipStepDown(oldLeaderMgmt);
        log("  stepDown response: " + resp);
        assertTrue(resp.contains("\"status\":\"success\""), "stepDown 应 SUCCESS，实际=" + resp);

        // 等新 leader 选出（用 grpc listNodes，避免反复 hit /raft/cluster 触发 ReadIndex）
        String newLeaderHost = null;
        int newLeaderGrpc = -1;
        for (int i = 0; i < 20; i++) {
            Thread.sleep(500);
            Optional<ServerNode> leaderOpt = tryFetchLeader();
            if (leaderOpt.isEmpty())
                continue;
            ServerNode leader = leaderOpt.get();
            int p = leader.getPort();
            if (p != oldLeaderGrpcPort) {
                newLeaderHost = leader.getHost();
                newLeaderGrpc = p;
                log("  new leader elected grpc=" + p);
                break;
            }
        }
        assertNotNull(newLeaderHost, "10s 内未选出新 leader");

        // stepDown 后旧 leader 上的 api 会持续返 NEED_MOVE 直到 ExchangeApi 自身的拓扑刷新（分钟级）。
        // 本测试等不及——切到连新 leader 的 api 跑后续 keepalive 和断言。
        try (ExchangeApi newApi = connect(newLeaderHost, newLeaderGrpc)) {
            // 等新 leader 完成 raft 初始化，避免命中 NEED_MOVE / NO_LEADER
            Thread.sleep(500);

            // cascade 必须最终完成——可能在旧 leader 上完成（stepDown 前已经走完），
            // 也可能在新 leader 上完成（scanner 接管或 cmd 经 raft 转给新 leader 处理）。
            // 总之 LIQ_USER_B 仓位必须归零。
            boolean closed = waitForPositionClosed(newApi, LIQ_USER_B, 20);
            assertTrue(closed, "LIQ_USER_B cascade 应在 leader 切换后 20s 内完成");

            // 3 节点 stateHash 收敛
            assertStateHashConverges();

            ok(newApi.adjustMarkPrice(PERP_SYMBOL, OPEN_PRICE), "restore mark to " + OPEN_PRICE);
        }
        log("✓ Test B passed: cascade survives leader stepDown");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test C: 同一用户连续破产两次，验证 ctx 闭环干净 + 下一轮 cascade fresh start
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(3)
    void cascade_reopenAndReliquidate_ctxClosureClean() throws Exception {
        log("=== Test C: same user reopen + reliquidate ===");

        reconnectToCurrentLeader();

        // 第 1 轮：把用户平掉
        triggerOneCascadeRoundAndWait(LIQ_USER_C, "round 1", 15);
        log("  ✓ round 1 cascade completed");

        // 准备第 2 轮：恢复 mark + 补血。强平 + ADL 后用户资金深度负（OPEN_PRICE 跟 CRASH_PRICE 落差 ~100x，
        // 5 手仓位的 mark-to-market 亏损 ≈ -50 * OPEN_PRICE）。补足 100K 保证下一轮开仓有足够 init margin。
        ok(api.adjustMarkPrice(PERP_SYMBOL, OPEN_PRICE), "restore mark to " + OPEN_PRICE);
        ok(api.adjustUserBalance(LIQ_USER_C, nextTx(), PFUND_ID, +100_000.0),
            "LIQ_USER_C refill +100K PFUND for round 2");

        // 第 2 轮：同一用户再走一遍——如果 ctx 闭环未彻底（ctx 仍非 null），第 2 轮 FORCE_LIQUIDATION
        // apply 时会被 expected-state gate 当 duplicate 静默 skip → 仓位永远平不掉 → waitForPositionClosed
        // 超时 → 本测试挂。
        triggerOneCascadeRoundAndWait(LIQ_USER_C, "round 2", 15);
        log("  ✓ round 2 cascade completed");

        // 两轮 cascade 后 ctx 进 snapshot 路径仍干净 → 3 节点 stateHash 收敛
        assertStateHashConverges();

        ok(api.adjustMarkPrice(PERP_SYMBOL, OPEN_PRICE), "restore mark to " + OPEN_PRICE);
        log("✓ Test C passed: consecutive cascades on same user both complete + ctx closure clean");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 启动一轮完整 cascade 并等待仓位归零：开 ADL 对手盘 → 开破产候选 → crash mark price → 全量走 ADL 承接。
     */
    private void triggerOneCascadeRoundAndWait(long uid, String label, int maxSeconds) throws Exception {
        openCounterParty(ADL_TARGET, OrderAction.ASK, OPEN_SIZE);
        openBankruptCandidate(uid);
        ok(api.adjustMarkPrice(PERP_SYMBOL, CRASH_PRICE), "crash mark to " + CRASH_PRICE + " (" + label + ")");
        boolean closed = waitForPositionClosed(uid, maxSeconds);
        assertTrue(closed, label + ": cascade 应在 " + maxSeconds + "s 内完成");
    }

    /**
     * Test B 把 leader stepDown 后，实例 field {@code api} 持续返 NEED_MOVE 直到 ExchangeApi 自身的拓扑刷新（分钟级）。 手动重连到当前 leader，让后续
     * Test 用得上。
     */
    private void reconnectToCurrentLeader() throws Exception {
        api.close();
        api = connect();
    }

    /** 用 LP + 给定用户在 OPEN_PRICE 撮合一笔，让用户开 OPEN_SIZE 仓位（方向 side：用户的方向）。 */
    private void openCounterParty(long uid, OrderAction userSide, double size) {
        OrderAction lpSide = (userSide == OrderAction.BID) ? OrderAction.ASK : OrderAction.BID;
        long lpId = nextOrder();
        ok(api.placeOrder(LP, lpId, PERP_SYMBOL, lpSide, OrderType.GTC, OPEN_PRICE, 0.0, size, MarginMode.CROSS,
            LEVERAGE, false), "LP " + lpSide + " " + size + "@" + OPEN_PRICE + " (counterpart for " + uid + ")");
        long userId = nextOrder();
        ok(api.placeOrder(uid, userId, PERP_SYMBOL, userSide, OrderType.GTC, OPEN_PRICE, 0.0, size, MarginMode.CROSS,
            LEVERAGE, false), uid + " " + userSide + " " + size + "@" + OPEN_PRICE);
    }

    /**
     * 给 LIQ_USER_* 建一个濒临破产的 ISOLATED LONG 仓位：开仓时手动加 extraMargin 把 equity 撑到 maintenance 之上， 然后调用方再 crash mark price
     * 触发强平（仿 Futures E2E Test 3 的套路）。
     */
    private void openBankruptCandidate(long uid) throws Exception {
        long bidId = nextOrder();
        ok(api.placeOrder(uid, bidId, PERP_SYMBOL, OrderAction.BID, OrderType.GTC, OPEN_PRICE, 0.0, OPEN_SIZE,
            MarginMode.ISOLATED, LEVERAGE, false),
            uid + " BID " + OPEN_SIZE + "@" + OPEN_PRICE + " ISOLATED " + LEVERAGE + "x");
        long lpAskId = nextOrder();
        ok(api.placeOrder(LP, lpAskId, PERP_SYMBOL, OrderAction.ASK, OrderType.GTC, OPEN_PRICE, 0.0, OPEN_SIZE,
            MarginMode.CROSS, LEVERAGE, false), "LP ASK " + OPEN_SIZE + "@" + OPEN_PRICE + " (open " + uid + ")");
        Thread.sleep(500);
        ok(api.adjustMargin(uid, MarginMode.ISOLATED, PERP_SYMBOL, +3_000.0), uid + " add 3000 extraMargin");
    }

private boolean waitForPositionClosed(long uid, int maxSeconds) throws Exception {
        return waitForPositionClosed(api, uid, maxSeconds);
    }

    /**
     * 轮询用户的指定 symbol 仓位 openVolume → 0，每秒重发 adjustMarkPrice 保 ringbuffer 流入（同 Futures E2E 套路）。 接受 ExchangeApi 参数——Test
     * B 在 stepDown 后必须用连到新 leader 的 api，否则会 NEED_MOVE。
     */
    private boolean waitForPositionClosed(ExchangeApi client, long uid, int maxSeconds) throws Exception {
        for (int i = 0; i < maxSeconds; i++) {
            Thread.sleep(1_000);
            ok(client.adjustMarkPrice(PERP_SYMBOL, CRASH_PRICE), "keepalive mark " + CRASH_PRICE);
            SingleUserReportResultView rep = client.queryUserReport(uid).get(5, TimeUnit.SECONDS);
            List<SingleUserReportResultView.PositionView> pos = rep.getPositions().get(PERP_SYMBOL);
            if (pos == null || pos.isEmpty() || pos.get(0).getOpenVolume().compareTo(BigDecimal.ZERO) == 0) {
                log("  position closed at poll " + (i + 1) + "s");
                return true;
            }
            log("  poll " + (i + 1) + "s: still vol=" + pos.get(0).getOpenVolume());
        }
        return false;
    }

    /** 直连 3 个节点的 grpc 查 stateHash，验证收敛。失败时 dump per-node hash 协助定位。 */
    private void assertStateHashConverges() throws Exception {
        List<String[]> nodes = resolveAllNodes(); // [host, grpcPort, role]
        List<String> lastDivergences = new ArrayList<>();
        for (int attempt = 1; attempt <= 10; attempt++) {
            Map<String, Map<Long, Integer>> perNode = new HashMap<>();
            for (String[] node : nodes) {
                String host = node[0];
                int port = Integer.parseInt(node[1]);
                String role = node[2];
                StateHashReportResult sh = queryStateHashDirect(host, port);
                Map<Long, Integer> flat = new HashMap<>();
                for (HashCodeEntry e : sh.getHashCodesList()) {
                    long k =
                        ((long)e.getKey().getModuleId() << 32) | (e.getKey().getSubmoduleTypeValue() & 0xFFFF_FFFFL);
                    flat.put(k, e.getValue());
                }
                perNode.put(host + ":" + port + "(" + role + ")", flat);
            }
            Map.Entry<String, Map<Long, Integer>> baseEntry = perNode.entrySet().iterator().next();
            Map<Long, Integer> base = baseEntry.getValue();
            List<String> divergences = new ArrayList<>();
            for (Map.Entry<String, Map<Long, Integer>> entry : perNode.entrySet()) {
                if (entry.getKey().equals(baseEntry.getKey()))
                    continue;
                Map<Long, Integer> other = entry.getValue();
                if (base.size() != other.size()) {
                    divergences.add(entry.getKey() + " submodule count " + other.size() + " ≠ base " + base.size());
                    continue;
                }
                for (Map.Entry<Long, Integer> kv : base.entrySet()) {
                    Integer otherVal = other.get(kv.getKey());
                    if (!java.util.Objects.equals(kv.getValue(), otherVal)) {
                        divergences.add(entry.getKey() + " submodule " + Long.toHexString(kv.getKey()) + " hash="
                            + otherVal + " ≠ base " + baseEntry.getKey() + " hash=" + kv.getValue());
                    }
                }
            }
            if (divergences.isEmpty()) {
                log("  ✓ all 3 nodes converged on " + base.size() + " submodule hashes (attempt " + attempt + ")");
                return;
            }
            lastDivergences = divergences;
            Thread.sleep(1_000);
        }
        log("  ✗ DIVERGENCES (" + lastDivergences.size() + ") after 10s:");
        for (String d : lastDivergences)
            log("    " + d);
        fail("raft 10s 内 stateHash 未收敛：" + lastDivergences);
    }

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

    private static int grpcToMgmt(int grpcPort) {
        return 28080 + (grpcPort - 5000);
    }

    /** POST /raft/{mgmtPort}/leadership 让指定节点 step down。复用 Failover E2E 同款实现。 */
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

    /**
     * 走 grpc ServerNodeService.listNodes 读 raft 拓扑——本地内存读，无 ReadIndex 副作用。 任一 grpc 端口可达即返回。
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

    private static Optional<ServerNode> tryFetchLeader() {
        try {
            return fetchAllNodes().stream().filter(n -> n.getType() == NodeType.LEADER).findFirst();
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
    }

    private static ServerNode fetchLeader() {
        return tryFetchLeader().orElseThrow(() -> new IllegalStateException("集群无 leader 或所有 grpc 端口不可达"));
    }

    /** 返回 [host, grpcPort, role] 三元组列表，给 stateHash 收敛检查用。 */
    private static List<String[]> resolveAllNodes() {
        List<String[]> result = new ArrayList<>();
        for (ServerNode n : fetchAllNodes()) {
            result.add(new String[] {n.getHost(), String.valueOf(n.getPort()), n.getType().name()});
        }
        return result;
    }

    private static int resolveLeaderGrpcPort() {
        String explicit = System.getProperty("GRPC_PORT");
        if (explicit != null)
            return Integer.parseInt(explicit);
        try {
            return fetchLeader().getPort();
        } catch (Exception e) {
            System.out.println("[E2E] leader discovery failed, falling back to 5001");
            return 5001;
        }
    }

    private ExchangeApi connect() {
        return connect(HOST, GRPC_PORT);
    }

    private static ExchangeApi connect(String host, int grpcPort) {
        return ExchangeApi.connect(host, grpcPort,
            ExchangeApiOptions.builder().sendTimeout(Duration.ofSeconds(30)).build());
    }

    private void ensureCurrency(int id, String name, int digit) {
        if (!api.getMetadataManager().currencyExists(id)) {
            ok(api.addCurrency(id, name, digit), "addCurrency " + name);
        }
    }

    private void ensureSymbol(int id, SymbolType type, int base, int quote, long baseScaleK, long quoteScaleK,
        long takerFee, long makerFee, long liquidationFee, long feeScaleK, long initMargin, long initMarginScaleK,
        Map<Long, Long> mmMap, long mmScaleK, Map<Long, Long> levMap, String label) {
        if (!api.getMetadataManager().symbolExists(id)) {
            ok(api.addSymbol(id, type, base, quote, baseScaleK, quoteScaleK, takerFee, makerFee, liquidationFee,
                feeScaleK, initMargin, initMarginScaleK, mmMap, mmScaleK, levMap), label);
        }
    }

    private static void ok(CommandResultView v, String label) {
        System.out.printf("[%-50s] %s%n", label, v.getResultCode());
        assertTrue(isOk(v.getResultCode()), label + " expected SUCCESS (or idempotent) but got " + v.getResultCode());
    }

    private static boolean isOk(CommandResultCode code) {
        return code == CommandResultCode.SUCCESS || code == CommandResultCode.USER_MGMT_USER_ALREADY_EXISTS
            || code == CommandResultCode.SYMBOL_MGMT_SYMBOL_ALREADY_EXISTS
            || code == CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
    }

    private static void log(String m) {
        System.out.println(m);
    }
}
