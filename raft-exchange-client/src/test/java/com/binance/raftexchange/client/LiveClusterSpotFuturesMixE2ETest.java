package com.binance.raftexchange.client;

import com.binance.raftexchange.stubs.MarginMode;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.SymbolType;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.binance.raftexchange.client.ExchangeApiHelper.buildSlotValueMap;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 现货+期货混合 E2E：同一用户在两个市场并存交易，验证账本/资金按 currency 隔离。
 *
 * 前置条件： 1. ./start-local-cluster.sh start full (margin trading 必须开) 2. 等集群选出 leader 3. 测试启动时自动从 /raft/cluster 发现 leader
 * grpc_port（可通过 -DGRPC_PORT=xxxx 手动覆盖）
 *
 * 运行： mvn -pl raft-exchange-client test -Dtest=LiveClusterSpotFuturesMixE2ETest \
 * -Dsurefire.failIfNoSpecifiedTests=false \ "-Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition"
 *
 * 测试方法按序执行（@TestMethodOrder），共享集群初始化状态。 若集群已有同 ID 的用户/货币，addUser/addCurrency 会返回 DUPLICATE_NOP（无害）。
 */
@Disabled("需要本地三节点集群运行中")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveClusterSpotFuturesMixE2ETest {

    // ── 连接 ──────────────────────────────────────────────────────────────────
    private static final String HOST = "127.0.0.1";
    // mgmt_port = 28080 + n (n=1,2,3)，任意一个都能返回完整 cluster 信息，包含当前 leader 的 grpc_port
    private static final int[] MGMT_PORTS = {28081, 28082, 28083};
    private static final int GRPC_PORT = resolveLeaderGrpcPort();

    // ── 货币：digit=0 → scale=1，便于期货参数计算 ──────────────────────────
    private static final int PFUND_ID = 10; // futures 结算货币
    private static final int PBASE_ID = 11; // futures 基础货币

    // ── 合约 ──────────────────────────────────────────────────────────────────
    private static final int PERP_SYMBOL = 200;
    private static final int DELIVERY_SYMBOL = 201;

    // ── 现货扩展（使用原有 USDT/BTC，新 symbol 避免复用旧 state）────────────
    private static final int USDT_ID = 2;
    private static final int BTC_ID = 3;
    private static final int SPOT_SYMBOL2 = 102;

    // ── 用户（动态 ID，避免跨 run 余额累加导致 LIQ_USER 不再触发强平）────
    // @BeforeAll 内基于 nanoTime 算 runId，每次跑都是 fresh users。
    // currency / symbol ID 保持 static（ensureCurrency/ensureSymbol 已经做 idempotent skip）。
    private long SPOT_A;
    private long SPOT_B;
    private long TRADER_A; // perp long / delivery long
    private long TRADER_B; // perp short / delivery short
    private long LP; // 流动性提供者
    private long LIQ_USER; // 被强平目标用户

    // ── 期货合约规格（baseScaleK=quoteScaleK=1，价格/数量即 raw 值）────────
    // notional = size * price = 5 * 10000 = 50000 raw
    // initMarginRate = initMargin / initMarginScaleK = 1/100 = 1%
    // maintenanceMarginRate = 5 / 100 = 5%（key 远大于实际 notional → 用第一档）
    // maxLeverage = 20x
    private static final long BASE_SCALE = 1L;
    private static final long QUOTE_SCALE = 1L;
    private static final Map<Long, Long> MM_MAP = buildSlotValueMap(1_000_000_000L, 5L);
    private static final Map<Long, Long> LEV_MAP = buildSlotValueMap(1_000_000_000L, 20L);

    private static final double OPEN_PRICE = 10_000.0;
    private static final double OPEN_SIZE = 5.0;
    private static final int LEVERAGE = 10;

    // ── 顺序 ID 避免冲突（txId/orderId 用完整毫秒时间戳基底，跨 run 不撞）──
    private long orderId;
    private long txId;

    private long nextOrder() {
        return orderId++;
    }

    private long nextTx() {
        return txId++;
    }

    // ── 共享连接（整个测试类生命周期内复用，metadata manager 保留所有 symbol/currency 信息）──
    private ExchangeApi api;

    @BeforeAll
    void openConnection() {
        // 动态 ID 让测试可重复跑：用户 fresh, txId/orderId 用完整时间戳避免撞老的。
        long runId = System.nanoTime() & 0xFFFFFL; // 20 bit
        SPOT_A = 4_000_000_000L + runId * 100 + 1;
        SPOT_B = 4_000_000_000L + runId * 100 + 2;
        TRADER_A = 3_000_000_000L + runId * 100 + 1;
        TRADER_B = 3_000_000_000L + runId * 100 + 2;
        LP = 3_000_000_000L + runId * 100 + 3;
        LIQ_USER = 3_000_000_000L + runId * 100 + 4;
        long base = System.currentTimeMillis() * 1_000L;
        txId = base;
        orderId = base + 5_000_000_000_000L; // 跟 txId 错开范围

        api = connect();
    }

    @AfterAll
    void closeConnection() throws Exception {
        if (api != null) {
            api.close();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 初始化（所有 advanced 测试共用的一次性 setup）
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(0)
    void setup_currenciesSymbolsUsers() throws Exception {
        log("=== setup: currencies ===");
        ensureCurrency(USDT_ID, "USDT", 6);
        ensureCurrency(BTC_ID, "BTC", 8);
        ensureCurrency(PFUND_ID, "PFUND", 0);
        ensureCurrency(PBASE_ID, "PBASE", 0);

        log("=== setup: users ===");
        ok(api.addUser(SPOT_A), "addUser SPOT_A=" + SPOT_A);
        ok(api.addUser(SPOT_B), "addUser SPOT_B=" + SPOT_B);
        ok(api.addUser(TRADER_A), "addUser TRADER_A=" + TRADER_A);
        ok(api.addUser(TRADER_B), "addUser TRADER_B=" + TRADER_B);
        ok(api.addUser(LP), "addUser LP=" + LP);
        ok(api.addUser(LIQ_USER), "addUser LIQ_USER=" + LIQ_USER);

        log("=== setup: deposit ===");
        // 现货用户（USDT + BTC，沿用原 digit=6/8 货币）
        ok(api.adjustUserBalance(SPOT_A, nextTx(), USDT_ID, +50000.0), "SPOT_A +50000 USDT");
        ok(api.adjustUserBalance(SPOT_B, nextTx(), BTC_ID, +2.0), "SPOT_B +2 BTC");

        // 期货用户（PFUND digit=0）
        ok(api.adjustUserBalance(TRADER_A, nextTx(), PFUND_ID, +100_000.0), "TRADER_A +100000 PFUND");
        ok(api.adjustUserBalance(TRADER_B, nextTx(), PFUND_ID, +100_000.0), "TRADER_B +100000 PFUND");
        ok(api.adjustUserBalance(LP, nextTx(), PFUND_ID, +10_000_000.0), "LP +10M PFUND");
        // LIQ_USER: 仅够 1 手 10x 逐仓保证金（5000 raw + 少量手续费缓冲）
        ok(api.adjustUserBalance(LIQ_USER, nextTx(), PFUND_ID, +6_000.0), "LIQ_USER +6000 PFUND");

        log("=== setup: spot symbol 2 ===");
        ensureSymbol(SPOT_SYMBOL2, SymbolType.CURRENCY_EXCHANGE_PAIR, BTC_ID, USDT_ID, 100_000_000L, 1_000_000L, 1_000L,
            500L, 0L, 1_000_000L, 0L, 0L, null, 0L, null, "addSymbol BTC/USDT spot#2");

        log("=== setup: perp symbol ===");
        ensureSymbol(PERP_SYMBOL, SymbolType.FUTURES_CONTRACT_PERPETUAL, PBASE_ID, PFUND_ID, BASE_SCALE, QUOTE_SCALE,
            10L, 5L, 5L, 10_000L, 1L, 100L, MM_MAP, 100L, LEV_MAP, "addSymbol PERP");

        log("=== setup: delivery symbol ===");
        ensureSymbol(DELIVERY_SYMBOL, SymbolType.FUTURES_CONTRACT_DELIVERY, PBASE_ID, PFUND_ID, BASE_SCALE, QUOTE_SCALE,
            10L, 5L, 5L, 10_000L, 1L, 100L, MM_MAP, 100L, LEV_MAP, "addSymbol DELIVERY");

        log("=== setup: set perp mark price ===");
        ok(api.adjustMarkPrice(PERP_SYMBOL, OPEN_PRICE), "adjustMarkPrice PERP 10000");
        ok(api.adjustMarkPrice(DELIVERY_SYMBOL, OPEN_PRICE), "adjustMarkPrice DELIVERY 10000");

        log("✓ setup complete");
    }

    // Test 1: 现货+期货混合：同用户双币种持仓，验证账本互不串
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(1)
    void mixed_spotAndPerp_sameUser_accountsIsolated() throws Exception {
        log("=== mixed spot+perp: same user, isolated accounts per currency ===");

        // 用 SPOT_A 同时玩现货（USDT）+ 期货（PFUND）
        // SPOT_A 已有 USDT 50000；补 PFUND 10000 给它开期货
        long mixUser = SPOT_A;
        ok(api.adjustUserBalance(mixUser, nextTx(), PFUND_ID, +10_000.0), "MIX user +10000 PFUND (futures collateral)");
        Thread.sleep(200);

        SingleUserReportResultView beforeRep = api.queryUserReport(mixUser).get(5, TimeUnit.SECONDS);
        BigDecimal usdtBefore = beforeRep.getAccounts().get(USDT_ID);
        BigDecimal pfundBefore = beforeRep.getAccounts().get(PFUND_ID);
        assertNotNull(usdtBefore, "mix user 应有 USDT 余额");
        assertNotNull(pfundBefore, "mix user 应有 PFUND 余额");
        log("  before: USDT=" + usdtBefore + " PFUND=" + pfundBefore);

        // === 现货：mix user 挂 BID 0.01 BTC @ 50000 (锁 500 USDT) ===
        ok(api.placeOrder(mixUser, nextOrder(), SPOT_SYMBOL2, OrderAction.BID, OrderType.GTC, 50000.0, 50001.0, 0.01,
            null, 0, false), "MIX user spot BID 0.01 BTC @ 50000 (locks 500 USDT)");

        // === 期货：mix user 开多 size=1 @ markPrice 10000 (ISOLATED 10x, 锁 100 PFUND notional/10) ===
        // LP 挂 ASK 接单
        ok(api.placeOrder(LP, nextOrder(), PERP_SYMBOL, OrderAction.ASK, OrderType.GTC, OPEN_PRICE, 0.0, 1.0,
            MarginMode.CROSS, LEVERAGE, false), "LP ASK 1@10000 (perp counterpart)");
        ok(api.placeOrder(mixUser, nextOrder(), PERP_SYMBOL, OrderAction.BID, OrderType.GTC, OPEN_PRICE, 0.0, 1.0,
            MarginMode.ISOLATED, LEVERAGE, false),
            "MIX user perp BID 1@10000 ISOLATED 10x (locks 100 PFUND margin + fees)");
        Thread.sleep(500);

        SingleUserReportResultView afterRep = api.queryUserReport(mixUser).get(5, TimeUnit.SECONDS);
        BigDecimal usdtAfter = afterRep.getAccounts().get(USDT_ID);
        BigDecimal pfundAfter = afterRep.getAccounts().get(PFUND_ID);
        log("  after:  USDT=" + usdtAfter + " PFUND=" + pfundAfter);

        // accounts 字段是总余额（free + locked），现货挂单只锁定不消耗，USDT total 不变
        assertEquals(0, usdtBefore.compareTo(usdtAfter), "USDT 总余额应不变（现货挂单仅 lock，accounts=total）");
        // 期货开仓：margin 从 accounts 移入 position，PFUND total 减少
        assertTrue(pfundAfter.compareTo(pfundBefore) < 0, "PFUND 总余额应减少（期货保证金进入 position）");
        // 验证持仓存在
        assertNotNull(afterRep.getPositions().get(PERP_SYMBOL), "mix user 应有 perp LONG 持仓");
        log("✓ 现货 USDT（accounts 不变，仅 lock）和期货 PFUND（margin 消耗）互不串账");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 从任意一个管理端口查询 /raft/cluster，取 leader 的 grpc_port。 若显式传入 -DGRPC_PORT，直接用该值（方便手动指定）。 若自动发现失败，回退到 5001。
     */
    private static int resolveLeaderGrpcPort() {
        String explicit = System.getProperty("GRPC_PORT");
        if (explicit != null) {
            return Integer.parseInt(explicit);
        }
        for (int mgmt : MGMT_PORTS) {
            try {
                HttpURLConnection conn =
                    (HttpURLConnection)new URL("http://" + HOST + ":" + mgmt + "/raft/cluster").openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                if (conn.getResponseCode() == 200) {
                    String body = new String(conn.getInputStream().readAllBytes());
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    int grpcPort = json.getAsJsonObject("leader").get("grpc_port").getAsInt();
                    System.out.println("[E2E] leader discovered at grpc_port=" + grpcPort + " via mgmt:" + mgmt);
                    return grpcPort;
                }
            } catch (Exception ignored) {
            }
        }
        System.out.println("[E2E] leader discovery failed, falling back to grpc_port=5001");
        return 5001;
    }

    private ExchangeApi connect() {
        return ExchangeApi.connect(HOST, GRPC_PORT,
            ExchangeApiOptions.builder().sendTimeout(Duration.ofSeconds(30)).build());
    }

    /** addCurrency, skip if metadata manager already knows this id (from refreshAll on connect). */
    private void ensureCurrency(int id, String name, int digit) {
        if (!api.getMetadataManager().currencyExists(id)) {
            ok(api.addCurrency(id, name, digit), "addCurrency " + name);
        } else {
            log("[addCurrency " + name + " (already known, skipped)]");
        }
    }

    /** addSymbol, skip if metadata manager already knows this id. */
    private void ensureSymbol(int id, SymbolType type, int base, int quote, long baseScaleK, long quoteScaleK,
        long takerFee, long makerFee, long liquidationFee, long feeScaleK, long initMargin, long initMarginScaleK,
        Map<Long, Long> mmMap, long mmScaleK, Map<Long, Long> levMap, String label) {
        if (!api.getMetadataManager().symbolExists(id)) {
            ok(api.addSymbol(id, type, base, quote, baseScaleK, quoteScaleK, takerFee, makerFee, liquidationFee,
                feeScaleK, initMargin, initMarginScaleK, mmMap, mmScaleK, levMap), label);
        } else {
            log("[" + label + " (already known, skipped)]");
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

    private static void log(String msg) {
        System.out.println(msg);
    }
}
