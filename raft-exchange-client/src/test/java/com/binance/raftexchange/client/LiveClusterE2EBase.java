package com.binance.raftexchange.client;

import com.binance.raftexchange.stubs.SymbolType;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LiveCluster E2E 的公共基座：连真集群、发现 leader、准备币种与交易对、生成本次运行专属的 id。
 *
 * <p>子类只写业务断言，不再各自抄一份 leader 探活与 bootstrap（此前 7 个 E2E 文件各抄一遍）。
 * 全部子类都需要本地三节点集群（{@code start-local-cluster.sh}），故一律 {@code @Disabled}，
 * 手动跑时用 {@code -Dtest=LiveClusterXxx -DGRPC_HOST=... -DGRPC_PORT=...} 或让基座自动探活。
 *
 * <p><b>id 隔离</b>：uid / symbolId / orderId / transactionId 都按 runId 生成，重跑不会因余额累加
 * 把断言挪位——集群是长驻的，状态不会在测试间清空。
 */
public abstract class LiveClusterE2EBase {

    // mgmt 用 loopback 探活（actuator 一般绑 0.0.0.0）；gRPC 用 cluster JSON 返回的真实 host
    private static final String MGMT_HOST = "127.0.0.1";
    private static final int[] MGMT_PORTS = {28081, 28082, 28083};

    /** 币种 id 全集群共享，ensureCurrency 幂等；digit 差异是刻意的——LTC 用 2 位小数压 scale 换算路径。 */
    protected static final int USDT_ID = 2;
    protected static final int BTC_ID = 3;
    protected static final int ETH_ID = 4;
    protected static final int BNB_ID = 5;
    protected static final int LTC_ID = 6;

    protected static final int USDT_DIGIT = 6;
    protected static final int BTC_DIGIT = 8;
    protected static final int ETH_DIGIT = 8;
    protected static final int BNB_DIGIT = 8;
    protected static final int LTC_DIGIT = 2; // 故意与 baseScaleK 不等，任何 scale 误用都会露出来

    /**
     * risk engine 分片数，取自 {@code PerformanceConfiguration.throughputPerformanceBuilder} 的 riskEnginesNum(2)。
     *
     * <p>按 shard 下发的运维命令（IF 存取、贷款池注资、LIF 垫资）必须覆盖每个分片：仓位/账户落在哪个
     * 分片不由调用方决定，漏一个分片就会在那个分片上资金为零。而且<b>打到不存在的分片不会报错</b>
     * ——多数命令静默返回 SUCCESS 空操作，少数直接没有结果，两种都不会让测试变红。
     */
    protected static final int RISK_SHARDS = 2;

    /**
     * 现货 pair 用固定 symbolId，<b>不能</b>用 per-run 的 {@link #symbolId}。
     *
     * <p>引擎不允许同一 (base, quote) 存在多个现货 pair——loan 按币种反查现货对走
     * {@code SymbolSpecificationProvider.findSpotSymbol}（provider 级 O(1) 索引），
     * {@code collateralWeightBps} 也已是币种级；多个 pair 会让估值落到任意一个上。
     * 每个 run 各建一套 BNB/USDT 就是在造这种重复，必须复用同一个 id。
     *
     * <p>代价是 markPrice、盘口残单等 symbol 级状态会跨测试类共享，故<b>砸价类用例必须自己复位</b>。
     */
    protected static final int SHARED_BTC_USDT = 900_001;
    protected static final int SHARED_ETH_USDT = 900_002;
    protected static final int SHARED_BNB_USDT = 900_003;
    protected static final int SHARED_LTC_USDT = 900_004;

    /**
     * 等待强平完成的统一超时（秒），必须<b>大于</b>兜底扫描周期
     * （{@code raftexchange.liquidation.interval}，默认 60s）。
     *
     * <p>强平有两条触发路径：markPrice 变动同步触发的快路径，和轮转扫描兜底。超时若小于<b>轮转一整轮</b>
     * 的时间（tick 周期 × 分片数），就等于要求每次都命中快路径——一旦某轮级联没在快路径里走完，
     * 用例必挂，且失败信息看起来像"强平不工作"，实际只是没轮到它。
     *
     * <p>故测试集群用 {@code SCAN_SLICES=5} 把一轮压到 10s（生产默认 30 分片 / 60s 一轮），
     * 本超时才敢取 30s。改其中任何一个值，另一个都要跟着算。
     */
    protected static final int LIQUIDATION_AWAIT_SEC = 30;

    /** 同一 JVM 内按加载顺序给每个测试类分配互不重叠的 id 槽位。 */
    private static final java.util.concurrent.atomic.AtomicInteger CLASS_SLOT =
        new java.util.concurrent.atomic.AtomicInteger();

    protected ExchangeApi api;

    /** 本次运行专属前缀，避免与集群里既有状态相撞。 */
    protected long runId;
    protected long uidBase;
    protected int symbolBase;
    private long orderIdSeq;
    private long txIdSeq;

    @BeforeAll
    void connectCluster() {
        // runId 隔离「不同次运行」，slot 隔离「同一次运行里的不同测试类」。
        // 只靠 runId 不够：各类各自取 nanoTime，模完可能落进同一段窗口，而 addSymbol 撞号返回
        // SYMBOL_MGMT_SYMBOL_ALREADY_EXISTS 会被 isOk 当成功放过——期货用例就会默默跑在现货 symbol 上。
        final int slot = CLASS_SLOT.getAndIncrement();
        runId = System.nanoTime() & 0xFFFFL; // 16bit
        uidBase = 5_000_000_000L + runId * 1_000 + slot * 100;   // 每类 100 个 uid
        symbolBase = (int)(1_000_000 + (runId % 1_000) * 1_000 + slot * 20); // 每类 20 个 symbolId
        long base = System.currentTimeMillis() * 1_000L;
        txIdSeq = base;
        orderIdSeq = base + 5_000_000_000_000L;

        String[] leader = resolveLeader();
        api = ExchangeApi.connect(leader[0], Integer.parseInt(leader[1]),
            ExchangeApiOptions.builder().sendTimeout(Duration.ofSeconds(30)).build());
    }

    @AfterAll
    void disconnectCluster() throws Exception {
        if (api != null) {
            api.close();
        }
    }

    protected long nextOrderId() {
        return orderIdSeq++;
    }

    protected long nextTxId() {
        return txIdSeq++;
    }

    /** 第 n 个测试用户（n 从 1 起），同一 run × 同一测试类内唯一。 */
    protected long uid(int n) {
        return uidBase + n;
    }

    /** 第 n 个交易对（n 从 0 起），同一 run × 同一测试类内唯一。 */
    protected int symbolId(int n) {
        return symbolBase + n;
    }

    // ================================================================
    // bootstrap：币种 / 交易对 / 结果断言
    // ================================================================

    /** 集群是长驻的，币种可能已存在——存在即跳过，不当失败。 */
    protected void ensureCurrency(int id, String name, int digit) {
        if (!api.getMetadataManager().currencyExists(id)) {
            ok(api.addCurrency(id, name, digit), "addCurrency " + name);
        } else {
            log("[addCurrency " + name + " (already known, skipped)]");
        }
    }

    /** 四个基准币 + USDT 一次备齐；digit 见类常量。 */
    protected void ensureAllCurrencies() {
        ensureCurrency(USDT_ID, "USDT", USDT_DIGIT);
        ensureCurrency(BTC_ID, "BTC", BTC_DIGIT);
        ensureCurrency(ETH_ID, "ETH", ETH_DIGIT);
        ensureCurrency(BNB_ID, "BNB", BNB_DIGIT);
        ensureCurrency(LTC_ID, "LTC", LTC_DIGIT);
    }

    /**
     * 交易步长 1e-5（Binance BTCUSDT stepSize）与报价步长 0.01（tick）。
     *
     * <p>不能直接拿币种的 currencyScaleK 当 symbol scale：那等于「一手 = 1 聪、一档 = 1e-6 USDT」，
     * 撮合粒度细到不现实，而且 {@code size × price} 的乘积 scale 会达到 baseScaleK × quoteScaleK = 1e14，
     * 把 long 的可用区间烧掉 14 个数量级——BTC 单笔超过 4.6 个就会 {@code multiplyExact} 溢出，
     * 异常冒到 Disruptor 会让三个节点的撮合引擎同时停机。取真实档位后乘积 scale 降到 1e7，余量回到 1e7 BTC 量级。
     */
    private static final long MAX_LOT_SCALE_K = 100_000L;
    private static final long MAX_TICK_SCALE_K = 100L;

    /** 币种最小单位与真实档位取小：digit 小的币（如 LTC digit=2）不能被放大到超出自身精度。 */
    private static long lotScaleK(int currency) {
        return Math.min(currencyScaleK(currency), MAX_LOT_SCALE_K);
    }

    private static long tickScaleK(int currency) {
        return Math.min(currencyScaleK(currency), MAX_TICK_SCALE_K);
    }

    private static long currencyScaleK(int currency) {
        final int digit;
        switch (currency) {
            case USDT_ID: digit = USDT_DIGIT; break;
            case BTC_ID: digit = BTC_DIGIT; break;
            case ETH_ID: digit = ETH_DIGIT; break;
            case BNB_ID: digit = BNB_DIGIT; break;
            case LTC_ID: digit = LTC_DIGIT; break;
            default: throw new IllegalArgumentException("unknown currency " + currency);
        }
        long k = 1;
        for (int i = 0; i < digit; i++) {
            k *= 10;
        }
        return k;
    }

    /**
     * 现货交易对，scale 取真实档位（见 {@link #MAX_LOT_SCALE_K}）。
     *
     * <p>已存在即跳过：client 侧对重复 addSymbol 是<b>抛异常</b>而非返回结果码，{@code ok()} 接不住、会直接打断 setup。
     */
    protected void addSpotSymbol(int symbol, int baseCurrency, int quoteCurrency, String label) {
        if (api.getMetadataManager().symbolExists(symbol)) {
            log("[addSymbol " + label + " (already known, skipped)]");
            return;
        }
        ok(api.addSymbol(symbol, SymbolType.CURRENCY_EXCHANGE_PAIR, baseCurrency, quoteCurrency,
            lotScaleK(baseCurrency), tickScaleK(quoteCurrency), 1_000L, 500L, 0L, 1_000_000L,
            0L, 0L, null, 0L, null),
            "addSymbol " + label + " spot " + symbol);
    }

    /** 合约交易对：scale 与现货同源，另带保证金率与杠杆档位。 */
    protected void addFuturesSymbolSpec(int symbol, SymbolType type, int baseCurrency, int quoteCurrency,
        String label) {
        java.util.Map<Long, Long> mmMap = new java.util.HashMap<>();
        mmMap.put(1L, 5L);
        java.util.Map<Long, Long> levMap = new java.util.HashMap<>();
        levMap.put(1L, 20L);
        // initMargin/initMarginScaleK 是叠加在 1/leverage 之上的倍数（见 CoreSymbolSpecification#calculateInitMargin：
        // notional × initMargin / (initMarginScaleK × leverage)），要拿到标准的 notional/leverage 就必须让它等于 1。
        // 写成 1/100 会把保证金再缩小 100 倍，仓位一开就在强平线下方（liqPrice 反而高于开仓价）。
        ok(api.addSymbol(symbol, type, baseCurrency, quoteCurrency,
            lotScaleK(baseCurrency), tickScaleK(quoteCurrency), 1_000L, 500L, 500L, 1_000_000L,
            100L, 100L, mmMap, 100L, levMap),
            "addSymbol " + label);
    }

    /** 命令结果断言：幂等码（已存在 / 已应用）与 SUCCESS 同样算通过——集群长驻，重跑必然撞上。 */
    protected static void ok(CommandResultView v, String label) {
        log(String.format("[%-50s] %s", label, v.getResultCode()));
        assertTrue(isOk(v.getResultCode()), label + " expected SUCCESS (or idempotent) but got " + v.getResultCode());
    }

    protected static boolean isOk(CommandResultCode code) {
        return code == CommandResultCode.SUCCESS || code == CommandResultCode.USER_MGMT_USER_ALREADY_EXISTS
            || code == CommandResultCode.SYMBOL_MGMT_SYMBOL_ALREADY_EXISTS
            || code == CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
    }

    protected static void log(String m) {
        System.out.println(m);
    }

    // ================================================================
    // 集群发现
    // ================================================================

    /** 显式 GRPC_HOST/GRPC_PORT 优先；否则探 mgmt 端口读 cluster JSON；都失败回落默认端口。 */
    protected static String[] resolveLeader() {
        String explicitHost = System.getProperty("GRPC_HOST");
        String explicitPort = System.getProperty("GRPC_PORT");
        if (explicitHost != null && explicitPort != null) {
            return new String[] {explicitHost, explicitPort};
        }
        JsonObject cluster = fetchClusterJson();
        if (cluster != null) {
            JsonObject leader = cluster.getAsJsonObject("leader");
            return new String[] {leader.get("host").getAsString(),
                String.valueOf(leader.get("grpc_port").getAsInt())};
        }
        return new String[] {"127.0.0.1", "5001"};
    }

    /** 每个节点：[host, grpcPort, role]。failover 类用例需要逐节点操作。 */
    protected static List<String[]> resolveAllNodes() {
        JsonObject cluster = fetchClusterJson();
        if (cluster == null) {
            throw new IllegalStateException("无法发现节点：mgmt ports=" + java.util.Arrays.toString(MGMT_PORTS));
        }
        List<String[]> nodes = new ArrayList<>();
        for (var el : cluster.getAsJsonArray("nodes")) {
            JsonObject n = el.getAsJsonObject();
            nodes.add(new String[] {n.get("host").getAsString(),
                String.valueOf(n.get("grpc_port").getAsInt()), n.get("role").getAsString()});
        }
        return nodes;
    }

    /** 任意 mgmt 端口的 /raft/cluster 快照；探不到返回 null，由调用方决定回落还是抛。 */
    protected static JsonObject fetchClusterJson() {
        for (int mgmt : MGMT_PORTS) {
            try {
                HttpURLConnection conn =
                    (HttpURLConnection)new URL("http://" + MGMT_HOST + ":" + mgmt + "/raft/cluster").openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                if (conn.getResponseCode() == 200) {
                    return JsonParser.parseString(new String(conn.getInputStream().readAllBytes())).getAsJsonObject();
                }
            } catch (Exception ignored) {
                // 逐个端口试探，全失败由调用方决定回落还是抛
            }
        }
        return null;
    }

    // ================================================================
    // Raft 运维 admin API（RaftEndpoint actuator：POST /raft/{snapshot|stepdown}、GET /raft/{snapshot|lag}）
    // ================================================================

    protected static final int GRPC_PORT_BASE = 5000; // grpc_port = 5000 + nodeIndex（见 start-local-cluster.sh）

    /** 从 grpc_port 反推节点号（1/2/3）。 */
    protected static int nodeIndexOfGrpcPort(String grpcPort) {
        return Integer.parseInt(grpcPort) - GRPC_PORT_BASE;
    }

    /** 当前 leader 的 mgmt_port。 */
    protected static int leaderMgmtPort() {
        JsonObject cluster = fetchClusterJson();
        if (cluster == null || cluster.get("leader") == null || cluster.get("leader").isJsonNull()) {
            throw new IllegalStateException("无法确定 leader mgmt_port");
        }
        return cluster.getAsJsonObject("leader").get("mgmt_port").getAsInt();
    }

    /** POST /raft/snapshot 到 leader，触发一次快照；返回 actuator 响应（status/message/duration_ms）。 */
    protected static JsonObject triggerSnapshot() {
        return mgmtCall(leaderMgmtPort(), "snapshot", "POST");
    }

    /** POST /raft/stepdown 到 leader，强制让位触发换届。 */
    protected static JsonObject stepDownLeader() {
        return mgmtCall(leaderMgmtPort(), "stepdown", "POST");
    }

    /** GET /raft/snapshot：save/load 成功计数与健康。 */
    protected static JsonObject snapshotStatus(int mgmtPort) {
        return mgmtCall(mgmtPort, "snapshot", "GET");
    }

    /** GET /raft/lag：committed/applied index、复制延迟、snapshot_cleanup_failures。 */
    protected static JsonObject raftLag(int mgmtPort) {
        return mgmtCall(mgmtPort, "lag", "GET");
    }

    /** GET /raft/config：consensus 后端、snapshot.log_index_margin 等运行时配置。 */
    protected static JsonObject raftConfig(int mgmtPort) {
        return mgmtCall(mgmtPort, "config", "GET");
    }

    /** 当前集群的 snapshotLogIndexMargin——决定要写多少 log 才能真正触发快照落盘。 */
    protected static int snapshotLogIndexMargin(int mgmtPort) {
        return raftConfig(mgmtPort).getAsJsonObject("snapshot").get("log_index_margin").getAsInt();
    }

    private static JsonObject mgmtCall(int mgmtPort, String op, String method) {
        try {
            HttpURLConnection conn =
                (HttpURLConnection)new URL("http://" + MGMT_HOST + ":" + mgmtPort + "/raft/" + op).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(620_000); // snapshot 触发在 server 侧上限 600s，读超时给足
            // actuator @WriteOperation 无 body：不能 setDoOutput（会带 x-www-form-urlencoded → 415），
            // 纯 POST 空 body 即可（对齐 curl -X POST）
            int code = conn.getResponseCode();
            java.io.InputStream is = code < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = is == null ? "" : new String(is.readAllBytes());
            return JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException(method + " /raft/" + op + " @" + mgmtPort + " 失败: " + e, e);
        }
    }

    // ================================================================
    // 节点生命周期（E2E 故障注入：shell 调 start-local-cluster.sh stop-node/start-node）
    // ================================================================

    /**
     * 轮询直到 {@code /raft/cluster} 报告 state_hash_converged=true，或超时抛错（打印分叉明细）。
     * 比"各节点余额相等"强得多：覆盖全部 submodule（订单簿/手续费/用户档案/loan…）的 stateHash 严格一致。
     */
    protected static void awaitStateHashConverged(long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        JsonObject last = null;
        while (System.currentTimeMillis() < deadline) {
            last = fetchClusterJson();
            if (last != null && last.has("state_hash_converged") && last.get("state_hash_converged").getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        final Object div =
            last != null && last.has("state_hash_divergences") ? last.get("state_hash_divergences") : "(无集群响应)";
        throw new AssertionError("stateHash 在 " + timeoutMs + "ms 内未收敛，分叉: " + div);
    }

    /** 停单节点（1/2/3）。 */
    protected static void stopNode(int nodeIndex) {
        runClusterScript("stop-node", String.valueOf(nodeIndex));
    }

    /** 起单节点（沿用集群 mode/consensus）。 */
    protected static void startNode(int nodeIndex) {
        runClusterScript("start-node", String.valueOf(nodeIndex));
    }

    /** 删单节点 raft 数据目录（log+snapshot+meta），逼其重启后从 leader 全量 install-snapshot（诊断用）。 */
    protected static void clearNodeData(int nodeIndex) {
        runClusterScript("clear-node", String.valueOf(nodeIndex));
    }

    private static void runClusterScript(String... args) {
        try {
            java.io.File script = findClusterScript();
            List<String> cmd = new ArrayList<>(List.of("bash", script.getAbsolutePath()));
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).directory(script.getParentFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            log("  [cluster] " + String.join(" ", args) + " → exit " + code
                + (out.isBlank() ? "" : " | " + out.strip().replace('\n', ';')));
            if (code != 0) {
                throw new IllegalStateException("start-local-cluster.sh " + String.join(" ", args) + " 失败: " + out);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("调用 start-local-cluster.sh 失败: " + e, e);
        }
    }

    /** 从 user.dir 向上找 start-local-cluster.sh（surefire cwd 可能是模块目录或仓库根）。 */
    private static java.io.File findClusterScript() {
        java.io.File dir = new java.io.File(System.getProperty("user.dir"));
        for (int i = 0; i < 6 && dir != null; i++) {
            java.io.File s = new java.io.File(dir, "start-local-cluster.sh");
            if (s.isFile()) {
                return s;
            }
            dir = dir.getParentFile();
        }
        throw new IllegalStateException("找不到 start-local-cluster.sh（从 " + System.getProperty("user.dir") + " 上溯）");
    }
}
