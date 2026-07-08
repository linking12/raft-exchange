package com.binance.raftexchange.client;

import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.SymbolType;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 现货专项 E2E（spot-only 模式下可跑，full 模式也兼容）。
 *
 * <p>
 * 启动方式：
 * 
 * <pre>
 *   ./start-local-cluster.sh start spot   # margin 关，仅现货（最轻量）
 *   或
 *   ./start-local-cluster.sh start full   # 现货+期货全开
 *
 *   mvn -pl raft-exchange-client test -Dtest=LiveClusterSpotE2ETest \
 *       "-Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition"
 * </pre>
 *
 * <p>
 * 用 dynamic IDs（基于 nanoTime），每次跑 fresh users + fresh balance，不需要重启集群。
 */
@Disabled("需要本地三节点集群运行中")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveClusterSpotE2ETest {

    // mgmt 用 loopback 探活（actuator 一般会绑 0.0.0.0）；gRPC 用 cluster JSON 返回的真实 host
    private static final String MGMT_HOST = "127.0.0.1";
    private static final int[] MGMT_PORTS = {28081, 28082, 28083};
    private static final String[] LEADER = resolveLeader(); // [host, grpcPort]
    private static final String LEADER_HOST = LEADER[0];
    private static final int LEADER_GRPC = Integer.parseInt(LEADER[1]);

    // 货币：USDT/BTC/ETH 全集群共享，ensureCurrency 幂等
    private static final int USDT_ID = 2;
    private static final int BTC_ID = 3;
    private static final int ETH_ID = 4;

    // 动态 ID：每次跑 fresh，避免余额累加导致后续断言挪位
    private long SPOT_BUYER;
    private long SPOT_SELLER;
    private int SPOT_SYMBOL; // BTC/USDT
    private int ETH_SPOT_SYMBOL; // ETH/USDT —— 验证多交易对并行
    private long orderId;
    private long txId;

    private ExchangeApi api;

    private long nextOrder() {
        return orderId++;
    }

    private long nextTx() {
        return txId++;
    }

    @BeforeAll
    void setUp() {
        long runId = System.nanoTime() & 0xFFFFFL; // 20bit
        SPOT_BUYER = 5_000_000_000L + runId * 100 + 1;
        SPOT_SELLER = 5_000_000_000L + runId * 100 + 2;
        SPOT_SYMBOL = (int)(1_000_000 + runId % 100_000); // BTC/USDT，避开 Advanced 用的 100/102
        ETH_SPOT_SYMBOL = SPOT_SYMBOL + 1; // ETH/USDT 紧跟其后，同一 runId 下唯一
        long base = System.currentTimeMillis() * 1_000L;
        txId = base;
        orderId = base + 5_000_000_000_000L;

        api = ExchangeApi.connect(LEADER_HOST, LEADER_GRPC,
            ExchangeApiOptions.builder().sendTimeout(Duration.ofSeconds(30)).build());
    }

    @AfterAll
    void tearDown() throws Exception {
        if (api != null)
            api.close();
    }

    @Test
    @Order(0)
    void setup_currency_symbol_users_balance() throws Exception {
        log("=== setup spot currencies/symbol/users ===");

        ensureCurrency(USDT_ID, "USDT", 6);
        ensureCurrency(BTC_ID, "BTC", 8);
        ensureCurrency(ETH_ID, "ETH", 8);

        ok(api.addUser(SPOT_BUYER), "addUser BUYER=" + SPOT_BUYER);
        ok(api.addUser(SPOT_SELLER), "addUser SELLER=" + SPOT_SELLER);

        // BUYER 多充 USDT 同时覆盖 BTC + ETH 撮合；SELLER 同时存有 BTC 和 ETH
        ok(api.adjustUserBalance(SPOT_BUYER, nextTx(), USDT_ID, +25000.0), "BUYER +25000 USDT");
        ok(api.adjustUserBalance(SPOT_SELLER, nextTx(), BTC_ID, +1.0), "SELLER +1 BTC");
        ok(api.adjustUserBalance(SPOT_SELLER, nextTx(), ETH_ID, +5.0), "SELLER +5 ETH");

        ok(api.addSymbol(SPOT_SYMBOL, SymbolType.CURRENCY_EXCHANGE_PAIR, BTC_ID, USDT_ID, 100_000_000L, 1_000_000L,
            1_000L, 500L, 0L, 1_000_000L, 0L, 0L, null, 0L, null), "addSymbol BTC/USDT spot " + SPOT_SYMBOL);

        ok(api.addSymbol(ETH_SPOT_SYMBOL, SymbolType.CURRENCY_EXCHANGE_PAIR, ETH_ID, USDT_ID, 100_000_000L, 1_000_000L,
            1_000L, 500L, 0L, 1_000_000L, 0L, 0L, null, 0L, null), "addSymbol ETH/USDT spot " + ETH_SPOT_SYMBOL);

        log("✓ setup ok");
    }

    @Test
    @Order(1)
    void spot_placeOrder_match_balancesUpdated() throws Exception {
        log("=== spot place + match ===");
        BigDecimal usdtBefore = balance(SPOT_BUYER, USDT_ID);
        BigDecimal btcBefore = balance(SPOT_SELLER, BTC_ID);

        // SELLER ASK 0.1 BTC @ 50000
        ok(api.placeOrder(SPOT_SELLER, nextOrder(), SPOT_SYMBOL, OrderAction.ASK, OrderType.GTC, 50000.0, 0.0, 0.1,
            null, 0, false), "SELLER ASK 0.1 BTC @ 50000");

        // BUYER BID 0.1 BTC @ 50000 (taker)
        ok(api.placeOrder(SPOT_BUYER, nextOrder(), SPOT_SYMBOL, OrderAction.BID, OrderType.GTC, 50000.0, 50001.0, 0.1,
            null, 0, false), "BUYER BID 0.1 BTC @ 50000 (taker)");

        Thread.sleep(500);

        BigDecimal usdtAfter = balance(SPOT_BUYER, USDT_ID);
        BigDecimal btcAfter = balance(SPOT_SELLER, BTC_ID);
        log("  BUYER  USDT " + usdtBefore + " → " + usdtAfter);
        log("  SELLER BTC  " + btcBefore + " → " + btcAfter);

        assertTrue(usdtAfter.compareTo(usdtBefore) < 0, "BUYER USDT 应减少（买入消耗）");
        assertTrue(btcAfter.compareTo(btcBefore) < 0, "SELLER BTC 应减少（卖出消耗）");
    }

    @Test
    @Order(2)
    void spot_cancelOrder_restoresLockedFunds() throws Exception {
        log("=== spot cancel restores locked funds ===");
        BigDecimal usdtBefore = balance(SPOT_BUYER, USDT_ID);

        long oid = nextOrder();
        ok(api.placeOrder(SPOT_BUYER, oid, SPOT_SYMBOL, OrderAction.BID, OrderType.GTC, 40000.0, 40001.0, 0.05, null, 0,
            false), "BUYER BID 0.05 BTC @ 40000 (will cancel)");

        Thread.sleep(200);
        ok(api.cancelOrder(SPOT_BUYER, oid, SPOT_SYMBOL), "cancelOrder " + oid);
        Thread.sleep(300);

        BigDecimal usdtAfter = balance(SPOT_BUYER, USDT_ID);
        log("  USDT " + usdtBefore + " → " + usdtAfter);
        assertEquals(0, usdtBefore.compareTo(usdtAfter), "cancel 后 USDT 余额应恢复（locked 释放回 free）");
    }

    @Test
    @Order(3)
    void spot_withdraw_decreasesBalance() throws Exception {
        log("=== spot withdraw ===");
        BigDecimal before = balance(SPOT_BUYER, USDT_ID);
        double withdrawAmount = 500.0;

        ok(api.adjustUserBalance(SPOT_BUYER, nextTx(), USDT_ID, -withdrawAmount), "BUYER withdraw 500 USDT");
        Thread.sleep(300);

        BigDecimal after = balance(SPOT_BUYER, USDT_ID);
        log("  USDT " + before + " → " + after);
        assertEquals(0, before.subtract(new BigDecimal("500")).compareTo(after), "提现后余额应减 500");
    }

    @Test
    @Order(4)
    void spot_partialFill_cancelLeftover_unlocksRemainder() throws Exception {
        log("=== spot partial fill + cancel leftover ===");

        // 用新一对临时用户，避免污染前置 SPOT_BUYER/SPOT_SELLER 余额
        long buyer = SPOT_BUYER + 10;
        long seller = SPOT_SELLER + 10;
        ok(api.addUser(buyer), "addUser tmpBuyer");
        ok(api.addUser(seller), "addUser tmpSeller");
        ok(api.adjustUserBalance(buyer, nextTx(), USDT_ID, +10000.0), "tmpBuyer +10000 USDT");
        ok(api.adjustUserBalance(seller, nextTx(), BTC_ID, +0.5), "tmpSeller +0.5 BTC");

        // SELLER 只挂 0.04 BTC @ 50000；BUYER 下 BID 0.1 → 应成交 0.04，挂单 0.06 残留在簿子里
        ok(api.placeOrder(seller, nextOrder(), SPOT_SYMBOL, OrderAction.ASK, OrderType.GTC, 50000.0, 0.0, 0.04, null, 0,
            false), "tmpSeller ASK 0.04 BTC @ 50000");

        long bigBidId = nextOrder();
        ok(api.placeOrder(buyer, bigBidId, SPOT_SYMBOL, OrderAction.BID, OrderType.GTC, 50000.0, 50001.0, 0.1, null, 0,
            false), "tmpBuyer BID 0.1 BTC @ 50000 (partial fill: 0.04 成交, 0.06 残留)");
        Thread.sleep(300);

        // 部分成交后：accounts 减少 ~2000 USDT（0.04*50000 + fee），剩余 0.06*50000=3000 在 exchangeLocked
        SingleUserReportResultView mid = api.queryUserReport(buyer).get(5, TimeUnit.SECONDS);
        BigDecimal usdtTotal = mid.getAccounts().get(USDT_ID);
        BigDecimal usdtLocked = mid.getExchangeLocked().getOrDefault(USDT_ID, BigDecimal.ZERO);
        log("  after partial fill: USDT total=" + usdtTotal + " locked=" + usdtLocked);
        assertTrue(
            usdtLocked.compareTo(new BigDecimal("3000")) >= 0 && usdtLocked.compareTo(new BigDecimal("3010")) <= 0,
            "残留 0.06 BTC 挂单应锁定约 3000 USDT，实际 locked=" + usdtLocked);

        // 撤剩余挂单 → exchangeLocked 应清零
        ok(api.cancelOrder(buyer, bigBidId, SPOT_SYMBOL), "cancel leftover " + bigBidId);
        Thread.sleep(300);
        SingleUserReportResultView after = api.queryUserReport(buyer).get(5, TimeUnit.SECONDS);
        BigDecimal lockedAfter = after.getExchangeLocked().getOrDefault(USDT_ID, BigDecimal.ZERO);
        log("  after cancel leftover: locked=" + lockedAfter);
        // reservePrice=50001 比 limit=50000 多 1 USDT buffer，partial fill 后按 raw 比例
        // 折算可能留 1~2 raw（0.000001 USDT）的 dust，业务上视为 0
        assertTrue(lockedAfter.compareTo(new BigDecimal("0.000010")) <= 0,
            "cancel 残留后 exchangeLocked 应接近 0（允许 raw 级 dust），实际=" + lockedAfter);
        // total 不应该因为 cancel 多动一分钱
        assertEquals(0, after.getAccounts().get(USDT_ID).compareTo(usdtTotal), "cancel 残留不应影响 total 余额");
    }

    @Test
    @Order(5)
    void spot_iocOrder_noLiquidityCancelsImmediately() throws Exception {
        log("=== spot IOC no liquidity ===");

        // 用全新一对用户，盘口为空
        long iocBuyer = SPOT_BUYER + 20;
        ok(api.addUser(iocBuyer), "addUser iocBuyer");
        ok(api.adjustUserBalance(iocBuyer, nextTx(), USDT_ID, +5000.0), "iocBuyer +5000 USDT");

        // 用一个不会被人吃的低价 BID + IOC：无对手 → 应立即取消，无 hold 残留
        BigDecimal before = balance(iocBuyer, USDT_ID);
        ok(api.placeOrder(iocBuyer, nextOrder(), SPOT_SYMBOL, OrderAction.BID, OrderType.IOC, 10.0, 11.0, 0.01, null, 0,
            false), "iocBuyer BID 0.01 @ 10 IOC (no liquidity)");
        Thread.sleep(300);

        SingleUserReportResultView rep = api.queryUserReport(iocBuyer).get(5, TimeUnit.SECONDS);
        BigDecimal after = rep.getAccounts().get(USDT_ID);
        BigDecimal locked = rep.getExchangeLocked().getOrDefault(USDT_ID, BigDecimal.ZERO);
        log("  after IOC no-fill: USDT=" + after + " locked=" + locked);
        assertEquals(0, before.compareTo(after), "IOC 无成交不应扣余额");
        assertEquals(0, locked.compareTo(BigDecimal.ZERO), "IOC 无成交不应留 hold");
        // 同 orderId 立即 cancel 应该报 unknown order（已经自动 cancel 了）
        CommandResultView dup = api.cancelOrder(iocBuyer, /*同 id 不会有*/9999_999_999_999L, SPOT_SYMBOL);
        log("  cancel unknown order code=" + dup.getResultCode());
        assertEquals(CommandResultCode.MATCHING_UNKNOWN_ORDER_ID, dup.getResultCode(),
            "cancel 不存在的订单应返回 MATCHING_UNKNOWN_ORDER_ID");
    }

    @Test
    @Order(6)
    void spot_insufficientFunds_RISK_NSF() throws Exception {
        log("=== spot insufficient funds → RISK_NSF ===");

        long poorBuyer = SPOT_BUYER + 30;
        ok(api.addUser(poorBuyer), "addUser poorBuyer");
        ok(api.adjustUserBalance(poorBuyer, nextTx(), USDT_ID, +100.0), "poorBuyer +100 USDT (only fund)");

        // 100 USDT 不够买 0.1 BTC @ 50000 = 5000 USDT
        CommandResultView v = api.placeOrder(poorBuyer, nextOrder(), SPOT_SYMBOL, OrderAction.BID, OrderType.GTC,
            50000.0, 50001.0, 0.1, null, 0, false);
        log("  result=" + v.getResultCode());
        assertEquals(CommandResultCode.RISK_NSF, v.getResultCode(), "余额不足时应返回 RISK_NSF，实际=" + v.getResultCode());

        // RISK_NSF 拒绝后：余额完整保留，无 hold
        SingleUserReportResultView rep = api.queryUserReport(poorBuyer).get(5, TimeUnit.SECONDS);
        assertEquals(0, rep.getAccounts().get(USDT_ID).compareTo(new BigDecimal("100")), "RISK_NSF 拒绝后余额不应变化");
        assertTrue(rep.getExchangeLocked().getOrDefault(USDT_ID, BigDecimal.ZERO).compareTo(BigDecimal.ZERO) == 0,
            "RISK_NSF 拒绝后不应留 hold");
    }

    @Test
    @Order(7)
    void spot_doubleCancelRejected() throws Exception {
        log("=== spot double cancel ===");

        long u = SPOT_BUYER + 40;
        ok(api.addUser(u), "addUser " + u);
        ok(api.adjustUserBalance(u, nextTx(), USDT_ID, +5000.0), "u +5000 USDT");

        long oid = nextOrder();
        ok(api.placeOrder(u, oid, SPOT_SYMBOL, OrderAction.BID, OrderType.GTC, 30000.0, 30001.0, 0.01, null, 0, false),
            "u BID 0.01 @ 30000 GTC");
        Thread.sleep(200);

        ok(api.cancelOrder(u, oid, SPOT_SYMBOL), "first cancel " + oid);
        Thread.sleep(200);

        CommandResultView second = api.cancelOrder(u, oid, SPOT_SYMBOL);
        log("  second cancel code=" + second.getResultCode());
        assertEquals(CommandResultCode.MATCHING_UNKNOWN_ORDER_ID, second.getResultCode(),
            "对同一 orderId 二次 cancel 应返回 MATCHING_UNKNOWN_ORDER_ID");
    }

    @Test
    @Order(8)
    void spot_exchangeLocked_movesFreeToLockedAndBack() throws Exception {
        log("=== spot exchangeLocked end-to-end ===");

        long u = SPOT_BUYER + 50;
        ok(api.addUser(u), "addUser " + u);
        ok(api.adjustUserBalance(u, nextTx(), USDT_ID, +1000.0), "u +1000 USDT");

        // 初始：accounts=1000, locked=0
        SingleUserReportResultView t0 = api.queryUserReport(u).get(5, TimeUnit.SECONDS);
        assertEquals(0, t0.getExchangeLocked().getOrDefault(USDT_ID, BigDecimal.ZERO).compareTo(BigDecimal.ZERO),
            "初始 locked 应为 0");

        // 挂 0.01 BTC @ 40000 → 锁 400 USDT
        long oid = nextOrder();
        ok(api.placeOrder(u, oid, SPOT_SYMBOL, OrderAction.BID, OrderType.GTC, 40000.0, 40001.0, 0.01, null, 0, false),
            "u BID 0.01 @ 40000");
        Thread.sleep(200);

        SingleUserReportResultView t1 = api.queryUserReport(u).get(5, TimeUnit.SECONDS);
        BigDecimal locked1 = t1.getExchangeLocked().getOrDefault(USDT_ID, BigDecimal.ZERO);
        log("  after place: total=" + t1.getAccounts().get(USDT_ID) + " locked=" + locked1);
        assertTrue(locked1.compareTo(new BigDecimal("400")) >= 0 && locked1.compareTo(new BigDecimal("401")) <= 0,
            "挂单后 locked 应在 [400, 401] 区间（含 reserve），实际=" + locked1);
        assertEquals(0, t1.getAccounts().get(USDT_ID).compareTo(new BigDecimal("1000")), "挂单不消耗 total，total 仍 1000");

        // cancel → locked 释放回 free
        ok(api.cancelOrder(u, oid, SPOT_SYMBOL), "cancel " + oid);
        Thread.sleep(200);
        SingleUserReportResultView t2 = api.queryUserReport(u).get(5, TimeUnit.SECONDS);
        assertEquals(0, t2.getExchangeLocked().getOrDefault(USDT_ID, BigDecimal.ZERO).compareTo(BigDecimal.ZERO),
            "cancel 后 locked 应清零");
        assertEquals(0, t2.getAccounts().get(USDT_ID).compareTo(new BigDecimal("1000")), "cancel 后 total 完整");
    }

    /**
     * 验证多交易对并行：ETH/USDT 跑一遍独立撮合，确认两个 symbol 的 orderbook/余额相互隔离， 且 SELLER 的 ETH 余额仅被 ETH 交易影响（BTC 余额不动）。
     */
    @Test
    @Order(10) // 在 3-node consistency (9) 之后跑，避免与现有 @Order(8) 冲突
    void spot_secondSymbol_ethUsdtMatch_isolated() throws Exception {
        log("=== spot 第二交易对 ETH/USDT 撮合（与 BTC/USDT 隔离）===");
        BigDecimal usdtBefore = balance(SPOT_BUYER, USDT_ID);
        BigDecimal ethBefore = balance(SPOT_SELLER, ETH_ID);
        BigDecimal btcBefore = balance(SPOT_SELLER, BTC_ID);

        // SELLER ASK 1 ETH @ 3000
        ok(api.placeOrder(SPOT_SELLER, nextOrder(), ETH_SPOT_SYMBOL, OrderAction.ASK, OrderType.GTC, 3000.0, 0.0, 1.0,
            null, 0, false), "SELLER ASK 1 ETH @ 3000");

        // BUYER BID 1 ETH @ 3000 (taker)
        ok(api.placeOrder(SPOT_BUYER, nextOrder(), ETH_SPOT_SYMBOL, OrderAction.BID, OrderType.GTC, 3000.0, 3001.0, 1.0,
            null, 0, false), "BUYER BID 1 ETH @ 3000 (taker)");

        Thread.sleep(500);

        BigDecimal usdtAfter = balance(SPOT_BUYER, USDT_ID);
        BigDecimal ethAfter = balance(SPOT_SELLER, ETH_ID);
        BigDecimal btcAfter = balance(SPOT_SELLER, BTC_ID);
        log("  BUYER  USDT " + usdtBefore + " → " + usdtAfter);
        log("  SELLER ETH  " + ethBefore + " → " + ethAfter);
        log("  SELLER BTC  " + btcBefore + " → " + btcAfter + "（应保持不变）");

        assertTrue(usdtAfter.compareTo(usdtBefore) < 0, "BUYER USDT 应减少（吃 ETH 单消耗）");
        assertTrue(ethAfter.compareTo(ethBefore) < 0, "SELLER ETH 应减少（卖出消耗）");
        assertEquals(0, btcAfter.compareTo(btcBefore), "SELLER BTC 不应被 ETH 交易触及（交易对隔离）");
    }

    @Test
    @Order(9)
    void spot_3nodesReportSameBalance() throws Exception {
        log("=== 3-node consistency: BUYER USDT balance ===");
        List<String[]> nodes = resolveAllNodes(); // each: [host, grpcPort, role]
        assertTrue(nodes.size() >= 3, "应至少 3 节点，实际 " + nodes.size());

        BigDecimal first = null;
        for (String[] node : nodes) {
            String host = node[0];
            int port = Integer.parseInt(node[1]);
            String role = node[2];
            try (ExchangeApi nodeApi = ExchangeApi.connect(host, port,
                ExchangeApiOptions.builder().sendTimeout(Duration.ofSeconds(10)).build())) {
                BigDecimal vol =
                    nodeApi.queryUserReport(SPOT_BUYER).get(10, TimeUnit.SECONDS).getAccounts().get(USDT_ID);
                log("  node " + host + ":" + port + " (" + role + ") BUYER USDT=" + vol);
                if (first == null)
                    first = vol;
                else
                    assertEquals(0, vol.compareTo(first), "节点 " + host + ":" + port + " 余额 " + vol + " ≠ 首节点 " + first);
            }
        }
    }

    // ---------- helpers ----------

    private BigDecimal balance(long uid, int currency) throws Exception {
        BigDecimal b = api.queryUserReport(uid).get(5, TimeUnit.SECONDS).getAccounts().get(currency);
        return b == null ? BigDecimal.ZERO : b;
    }

    private void ensureCurrency(int id, String name, int digit) {
        if (!api.getMetadataManager().currencyExists(id)) {
            ok(api.addCurrency(id, name, digit), "addCurrency " + name);
        } else {
            log("[addCurrency " + name + " (already known, skipped)]");
        }
    }

    private static void ok(CommandResultView v, String label) {
        log(String.format("[%-50s] %s", label, v.getResultCode()));
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

    /** [leaderHost, leaderGrpcPort]，从任意一个 mgmt 端口的 /raft/cluster 拿。 */
    private static String[] resolveLeader() {
        String explicitHost = System.getProperty("GRPC_HOST");
        String explicitPort = System.getProperty("GRPC_PORT");
        if (explicitHost != null && explicitPort != null)
            return new String[] {explicitHost, explicitPort};
        for (int mgmt : MGMT_PORTS) {
            try {
                HttpURLConnection conn =
                    (HttpURLConnection)new URL("http://" + MGMT_HOST + ":" + mgmt + "/raft/cluster").openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                if (conn.getResponseCode() == 200) {
                    String body = new String(conn.getInputStream().readAllBytes());
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    JsonObject leader = json.getAsJsonObject("leader");
                    return new String[] {leader.get("host").getAsString(),
                        String.valueOf(leader.get("grpc_port").getAsInt())};
                }
            } catch (Exception ignored) {
            }
        }
        return new String[] {"127.0.0.1", "5001"};
    }

    /** 每个节点：[host, grpcPort, role]，host 取自 cluster JSON。 */
    private static List<String[]> resolveAllNodes() {
        for (int mgmt : MGMT_PORTS) {
            try {
                HttpURLConnection conn =
                    (HttpURLConnection)new URL("http://" + MGMT_HOST + ":" + mgmt + "/raft/cluster").openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                if (conn.getResponseCode() == 200) {
                    String body = new String(conn.getInputStream().readAllBytes());
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    List<String[]> ret = new ArrayList<>();
                    for (var el : json.getAsJsonArray("nodes")) {
                        JsonObject n = el.getAsJsonObject();
                        ret.add(new String[] {n.get("host").getAsString(),
                            String.valueOf(n.get("grpc_port").getAsInt()), n.get("role").getAsString()});
                    }
                    return ret;
                }
            } catch (Exception ignored) {
            }
        }
        throw new IllegalStateException("无法发现节点：mgmt ports=" + java.util.Arrays.toString(MGMT_PORTS));
    }
}
