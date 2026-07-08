package com.binance.raftexchange.client;

import com.binance.raftexchange.stubs.MarginMode;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.SymbolType;
import com.binance.raftexchange.stubs.api.QueryServiceGrpc;
import com.binance.raftexchange.stubs.report.HashCodeEntry;
import com.binance.raftexchange.stubs.report.ReportQuery;
import com.binance.raftexchange.stubs.report.ReportResult;
import com.binance.raftexchange.stubs.report.StateHashReportQuery;
import com.binance.raftexchange.stubs.report.StateHashReportResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.binance.raftexchange.client.ExchangeApiHelper.buildSlotValueMap;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 期货专项 E2E 测试：永续合约开仓 / 资金费率 / 关仓 / 强平 / 交割 / reduce / 跨节点状态一致。
 *
 * 前置条件： 1. ./start-local-cluster.sh start full (margin trading 必须开) 2. 等集群选出 leader 3. 测试启动时自动从 /raft/cluster 发现 leader
 * grpc_port（可通过 -DGRPC_PORT=xxxx 手动覆盖）
 *
 * 运行： mvn -pl raft-exchange-client test -Dtest=LiveClusterFuturesE2ETest \ -Dsurefire.failIfNoSpecifiedTests=false \
 * "-Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition"
 *
 * 测试方法按序执行（@TestMethodOrder），共享集群初始化状态。 若集群已有同 ID 的用户/货币，addUser/addCurrency 会返回 DUPLICATE_NOP（无害）。
 */
@Disabled("需要本地三节点集群运行中")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveClusterFuturesE2ETest {

    // ── 连接 ──────────────────────────────────────────────────────────────────
    private static final String HOST = "127.0.0.1";
    // mgmt_port = 28080 + n (n=1,2,3)，任意一个都能返回完整 cluster 信息，包含当前 leader 的 grpc_port
    private static final int[] MGMT_PORTS = {28081, 28082, 28083};
    private static final int GRPC_PORT = resolveLeaderGrpcPort();

    // ── 货币：digit=0 → scale=1，便于期货参数计算 ──────────────────────────
    private static final int PFUND_ID = 10; // futures 结算货币
    private static final int PBASE_ID = 11; // futures 基础货币
    private static final int PBASE2_ID = 12; // 第二个基础货币（PERP2 / DELIVERY2 用）

    // ── 合约：每类各 2 个交易对，验证多 symbol orderbook 隔离 ─────────────
    private static final int PERP_SYMBOL = 200; // PBASE/PFUND PERP
    private static final int DELIVERY_SYMBOL = 201; // PBASE/PFUND DELIVERY
    private static final int PERP2_SYMBOL = 202; // PBASE2/PFUND PERP
    private static final int DELIVERY2_SYMBOL = 203; // PBASE2/PFUND DELIVERY

    // ── 用户（动态 ID，避免跨 run 余额累加导致 LIQ_USER 不再触发强平）────
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

    // OPEN_PRICE 动态：跨 run 用不同价位避开前次 run 在 book 上的残留挂单。
    // symbol 复用是 prod 模型（BTC/USDT 永远同一个 ID），但 book 上随时有别人的单，
    // 测试不能假设 book 空——用 runId 派生价位让本 run 在自己的价位圈子里玩。
    private double OPEN_PRICE;
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
        TRADER_A = 3_000_000_000L + runId * 100 + 1;
        TRADER_B = 3_000_000_000L + runId * 100 + 2;
        LP = 3_000_000_000L + runId * 100 + 3;
        LIQ_USER = 3_000_000_000L + runId * 100 + 4;
        // 价位：本 run 在 10000 + runId%10000 这个独占价上玩，绕开前次 run 留在 10000 价位的残留挂单
        OPEN_PRICE = 10_000.0 + (runId % 10_000);
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
        ensureCurrency(PFUND_ID, "PFUND", 0);
        ensureCurrency(PBASE_ID, "PBASE", 0);
        ensureCurrency(PBASE2_ID, "PBASE2", 0);

        log("=== setup: users ===");
        ok(api.addUser(TRADER_A), "addUser TRADER_A=" + TRADER_A);
        ok(api.addUser(TRADER_B), "addUser TRADER_B=" + TRADER_B);
        ok(api.addUser(LP), "addUser LP=" + LP);
        ok(api.addUser(LIQ_USER), "addUser LIQ_USER=" + LIQ_USER);

        log("=== setup: deposit ===");
        ok(api.adjustUserBalance(TRADER_A, nextTx(), PFUND_ID, +100_000.0), "TRADER_A +100000 PFUND");
        ok(api.adjustUserBalance(TRADER_B, nextTx(), PFUND_ID, +100_000.0), "TRADER_B +100000 PFUND");
        ok(api.adjustUserBalance(LP, nextTx(), PFUND_ID, +10_000_000.0), "LP +10M PFUND");
        // LIQ_USER: 仅够 1 手 10x 逐仓保证金（5000 raw + 少量手续费缓冲）
        ok(api.adjustUserBalance(LIQ_USER, nextTx(), PFUND_ID, +6_000.0), "LIQ_USER +6000 PFUND");

        log("=== setup: perp symbols ===");
        ensureSymbol(PERP_SYMBOL, SymbolType.FUTURES_CONTRACT_PERPETUAL, PBASE_ID, PFUND_ID, BASE_SCALE, QUOTE_SCALE,
            10L, 5L, 5L, 10_000L, 1L, 100L, MM_MAP, 100L, LEV_MAP, "addSymbol PERP");
        ensureSymbol(PERP2_SYMBOL, SymbolType.FUTURES_CONTRACT_PERPETUAL, PBASE2_ID, PFUND_ID, BASE_SCALE, QUOTE_SCALE,
            10L, 5L, 5L, 10_000L, 1L, 100L, MM_MAP, 100L, LEV_MAP, "addSymbol PERP2");

        log("=== setup: delivery symbols ===");
        ensureSymbol(DELIVERY_SYMBOL, SymbolType.FUTURES_CONTRACT_DELIVERY, PBASE_ID, PFUND_ID, BASE_SCALE, QUOTE_SCALE,
            10L, 5L, 5L, 10_000L, 1L, 100L, MM_MAP, 100L, LEV_MAP, "addSymbol DELIVERY");
        ensureSymbol(DELIVERY2_SYMBOL, SymbolType.FUTURES_CONTRACT_DELIVERY, PBASE2_ID, PFUND_ID, BASE_SCALE,
            QUOTE_SCALE, 10L, 5L, 5L, 10_000L, 1L, 100L, MM_MAP, 100L, LEV_MAP, "addSymbol DELIVERY2");

        log("=== setup: set mark prices ===");
        ok(api.adjustMarkPrice(PERP_SYMBOL, OPEN_PRICE), "adjustMarkPrice PERP 10000");
        ok(api.adjustMarkPrice(DELIVERY_SYMBOL, OPEN_PRICE), "adjustMarkPrice DELIVERY 10000");
        ok(api.adjustMarkPrice(PERP2_SYMBOL, OPEN_PRICE), "adjustMarkPrice PERP2 10000");
        ok(api.adjustMarkPrice(DELIVERY2_SYMBOL, OPEN_PRICE), "adjustMarkPrice DELIVERY2 10000");

        // IF 预存：cascade 测试触发强平时，IF takeover 能一次吃下，避免走 ADL 死循环。
        // 金额需能整除 shard 数（默认 2），PFUND digit=0 → 100万 raw 直接 OK。
        log("=== setup: insurance fund deposits ===");
        ok(api.insuranceFundDeposit(PERP_SYMBOL, 0, 1_000_000.0), "IF deposit PERP 1M");
        ok(api.insuranceFundDeposit(DELIVERY_SYMBOL, 0, 1_000_000.0), "IF deposit DELIVERY 1M");
        ok(api.insuranceFundDeposit(PERP2_SYMBOL, 0, 1_000_000.0), "IF deposit PERP2 1M");
        ok(api.insuranceFundDeposit(DELIVERY2_SYMBOL, 0, 1_000_000.0), "IF deposit DELIVERY2 1M");

        log("✓ setup complete");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: 永续合约开仓 + 资金费率 + 平仓
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(2)
    void perp_openPosition_fundingFee_closePosition() throws Exception {
        log("=== Perp: cleanup TRADER_A pre-existing position ===");
        {
            SingleUserReportResultView preCheck = api.queryUserReport(TRADER_A).get(5, TimeUnit.SECONDS);
            List<SingleUserReportResultView.PositionView> prePos = preCheck.getPositions().get(PERP_SYMBOL);
            if (prePos != null && !prePos.isEmpty() && prePos.get(0).getOpenVolume().compareTo(BigDecimal.ZERO) > 0) {
                double existingVol = prePos.get(0).getOpenVolume().doubleValue();
                log("  TRADER_A has pre-existing LONG vol=" + existingVol + ", closing first");
                ok(api.placeOrder(LP, nextOrder(), PERP_SYMBOL, OrderAction.BID, OrderType.GTC, OPEN_PRICE, 0.0,
                    existingVol, MarginMode.CROSS, LEVERAGE, false), "LP BID (TRADER_A cleanup counterpart)");
                ok(api.closePosition(TRADER_A, nextOrder(), PERP_SYMBOL, OrderAction.ASK, OPEN_PRICE, existingVol),
                    "closePosition TRADER_A existing LONG");
                Thread.sleep(500);
            }
        }

        log("=== Perp: open positions ===");

        // TRADER_A 先挂 BID（maker），TRADER_B 挂 ASK（taker）→ 成交
        long bidId = nextOrder();
        ok(api.placeOrder(TRADER_A, bidId, PERP_SYMBOL, OrderAction.BID, OrderType.GTC, OPEN_PRICE, 0.0, OPEN_SIZE,
            MarginMode.CROSS, LEVERAGE, false), "TRADER_A BID 5@10000 CROSS");

        long askId = nextOrder();
        ok(api.placeOrder(TRADER_B, askId, PERP_SYMBOL, OrderAction.ASK, OrderType.GTC, OPEN_PRICE, 0.0, OPEN_SIZE,
            MarginMode.CROSS, LEVERAGE, false), "TRADER_B ASK 5@10000 CROSS");

        Thread.sleep(500);

        SingleUserReportResultView longReport = api.queryUserReport(TRADER_A).get(5, TimeUnit.SECONDS);
        SingleUserReportResultView shortReport = api.queryUserReport(TRADER_B).get(5, TimeUnit.SECONDS);
        assertNotNull(longReport.getPositions().get(PERP_SYMBOL), "TRADER_A should have perp position");
        assertNotNull(shortReport.getPositions().get(PERP_SYMBOL), "TRADER_B should have perp position");

        SingleUserReportResultView.PositionView longPos = longReport.getPositions().get(PERP_SYMBOL).get(0);
        SingleUserReportResultView.PositionView shortPos = shortReport.getPositions().get(PERP_SYMBOL).get(0);
        assertEquals(com.binance.raftexchange.stubs.PositionDirection.LONG, longPos.getDirection());
        assertEquals(com.binance.raftexchange.stubs.PositionDirection.SHORT, shortPos.getDirection());
        log("  TRADER_A LONG  vol=" + longPos.getOpenVolume() + " openPriceSum=" + longPos.getOpenPriceSum());
        log("  TRADER_B SHORT vol=" + shortPos.getOpenVolume() + " openPriceSum=" + shortPos.getOpenPriceSum());

        // ── Phase 2: 资金费率结算（正费率：多头付给空头，鼓励做空）─────────
        log("=== Perp: settleFundingFees (0.1% rate, longs pay shorts) ===");
        // 记录结算前 profit（正值+累计，负值-累计），结算后 LONG 的 profit 应减小，SHORT 应增大
        BigDecimal longProfitBefore = longPos.getProfit();
        BigDecimal shortProfitBefore = shortPos.getProfit();

        ok(api.settleFundingFees(PERP_SYMBOL, OrderAction.BID, 10L, 10_000L), "settleFundingFees 0.1%");

        Thread.sleep(300);
        SingleUserReportResultView longAfterFund = api.queryUserReport(TRADER_A).get(5, TimeUnit.SECONDS);
        SingleUserReportResultView shortAfterFund = api.queryUserReport(TRADER_B).get(5, TimeUnit.SECONDS);

        BigDecimal longProfitAfter = longAfterFund.getPositions().get(PERP_SYMBOL).get(0).getProfit();
        BigDecimal shortProfitAfter = shortAfterFund.getPositions().get(PERP_SYMBOL).get(0).getProfit();
        log("  TRADER_A profit before=" + longProfitBefore + " after=" + longProfitAfter + " (expect decrease)");
        log("  TRADER_B profit before=" + shortProfitBefore + " after=" + shortProfitAfter + " (expect increase)");
        assertTrue(longProfitAfter.compareTo(longProfitBefore) < 0, "LONG pays funding → profit decreases");
        assertTrue(shortProfitAfter.compareTo(shortProfitBefore) > 0, "SHORT receives funding → profit increases");

        // ── Phase 3: 平仓（TRADER_A 以同价格反向卖出，LP 接单）────────────
        log("=== Perp: closePosition ===");
        long lpBidId = nextOrder();
        ok(api.placeOrder(LP, lpBidId, PERP_SYMBOL, OrderAction.BID, OrderType.GTC, OPEN_PRICE, 0.0, OPEN_SIZE,
            MarginMode.CROSS, LEVERAGE, false), "LP BID 5@10000 (receive close)");

        long closeId = nextOrder();
        ok(api.closePosition(TRADER_A, closeId, PERP_SYMBOL, OrderAction.ASK, OPEN_PRICE, OPEN_SIZE),
            "TRADER_A closePosition ASK 5@10000");

        Thread.sleep(500);
        SingleUserReportResultView afterClose = api.queryUserReport(TRADER_A).get(5, TimeUnit.SECONDS);
        List<SingleUserReportResultView.PositionView> closedPositions = afterClose.getPositions().get(PERP_SYMBOL);
        assertTrue(
            closedPositions == null || closedPositions.isEmpty()
                || closedPositions.get(0).getOpenVolume().compareTo(BigDecimal.ZERO) == 0,
            "TRADER_A perp position should be closed");

        BigDecimal pfundAfter = afterClose.getAccounts().get(PFUND_ID);
        assertNotNull(pfundAfter, "TRADER_A PFUND balance should exist");
        // 扣了 funding fee (50 raw) 和手续费，余额应 < 100000
        assertTrue(pfundAfter.compareTo(new BigDecimal("100000")) < 0, "TRADER_A PFUND < 100000 after fees+funding");
        log("  TRADER_A PFUND after close = " + pfundAfter + " (expect < 100000)");

        log("✓ Perp open + fundingFee + closePosition OK");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: 强平（adjustMarkPrice 大幅下跌 → LiquidationEngine 自动强平）
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(3)
    void perp_liquidation_markPriceCrash() throws Exception {
        log("=== Perp Liquidation: cleanup any pre-existing LIQ_USER position ===");

        ok(api.adjustMarkPrice(PERP_SYMBOL, OPEN_PRICE), "reset mark to 10000");

        {
            SingleUserReportResultView preCheck = api.queryUserReport(LIQ_USER).get(5, TimeUnit.SECONDS);
            List<SingleUserReportResultView.PositionView> prePos = preCheck.getPositions().get(PERP_SYMBOL);
            if (prePos != null && !prePos.isEmpty() && prePos.get(0).getOpenVolume().compareTo(BigDecimal.ZERO) > 0) {
                double existingVol = prePos.get(0).getOpenVolume().doubleValue();
                log("  LIQ_USER has pre-existing LONG vol=" + existingVol + ", closing first");
                // LP 提供对手盘：closePosition 发 ASK，所以 LP 需要挂 BID 接单
                long cleanBid = nextOrder();
                ok(api.placeOrder(LP, cleanBid, PERP_SYMBOL, OrderAction.BID, OrderType.GTC, OPEN_PRICE, 0.0,
                    existingVol, MarginMode.CROSS, LEVERAGE, false), "LP BID (cleanup counterpart)");
                long cleanClose = nextOrder();
                ok(api.closePosition(LIQ_USER, cleanClose, PERP_SYMBOL, OrderAction.ASK, OPEN_PRICE, existingVol),
                    "closePosition LIQ_USER existing LONG");
                Thread.sleep(500);
            }
        }

        log("=== Perp Liquidation: open isolated position ===");

        // LIQ_USER: 逐仓 10x 开多仓，保证金 = notional/leverage = 50000/10 = 5000 raw
        // 存款 6000，勉强够保证金 + 手续费（余额不足时不报错，由服务端拦截）

        long liqBidId = nextOrder();
        ok(api.placeOrder(LIQ_USER, liqBidId, PERP_SYMBOL, OrderAction.BID, OrderType.GTC, OPEN_PRICE, 0.0, OPEN_SIZE,
            MarginMode.ISOLATED, LEVERAGE, false), "LIQ_USER BID 5@10000 ISOLATED 10x");

        // LP 提供对手方（ASK）
        long lpAskId = nextOrder();
        ok(api.placeOrder(LP, lpAskId, PERP_SYMBOL, OrderAction.ASK, OrderType.GTC, OPEN_PRICE, 0.0, OPEN_SIZE,
            MarginMode.CROSS, LEVERAGE, false), "LP ASK 5@10000 CROSS");

        Thread.sleep(500);
        SingleUserReportResultView beforeLiq = api.queryUserReport(LIQ_USER).get(5, TimeUnit.SECONDS);
        assertNotNull(beforeLiq.getPositions().get(PERP_SYMBOL),
            "LIQ_USER should have LONG position before liquidation");
        log("  LIQ_USER position before: vol=" + beforeLiq.getPositions().get(PERP_SYMBOL).get(0).getOpenVolume());

        // ISOLATED 模式下 equity = openInitMarginSum + profit + extraMargin（不含账户余额）。
        // 当前 spec：initMargin/initMarginScaleK=1% → openInitMarginSum = notional × 1% / leverage = 50；
        // 而 maintenance = notional × 5% = 2500。
        // 不补 extraMargin 的话开仓瞬间 equity=50 远小于 maintenance，直接触发强平 → 走不到 markPrice crash 场景。
        // 这里手动划 3000 到 position：equity=50+3000=3050 > 2500，开仓后健康；
        // adjustMarkPrice→100 时 profit=-49500，equity 转负，正常触发强平。
        ok(api.adjustMargin(LIQ_USER, MarginMode.ISOLATED, PERP_SYMBOL, +3000.0),
            "LIQ_USER add 3000 extraMargin to ISOLATED position");

        // FORCE 限价 = BP（bankruptcy price），对 LONG BP ≤ OPEN_PRICE；LP 挂 BID @ OPEN_PRICE 一定能承接。
        // size 恰好 = OPEN_SIZE，让 FORCE 吃完不留残单，避免影响后续测试。
        log("=== Step 1: LP BID @OPEN_PRICE ready to absorb FORCE IOC ===");
        long lpLiqBid = nextOrder();
        ok(api.placeOrder(LP, lpLiqBid, PERP_SYMBOL, OrderAction.BID, OrderType.GTC, OPEN_PRICE, 0.0, OPEN_SIZE,
            MarginMode.CROSS, LEVERAGE, false), "LP BID " + OPEN_SIZE + "@OPEN_PRICE (receive liquidation order)");

        log("=== Step 2: adjustMarkPrice to 100 (99% crash) → trigger liquidation ===");
        ok(api.adjustMarkPrice(PERP_SYMBOL, 100.0), "adjustMarkPrice 100");

        // 轮询直到 LIQ_USER 的仓位消失（最多等 15s）
        // 每轮重发一次同值 adjustMarkPrice 保持 ringbuffer 有 cmd 流入，
        // 否则 disruptor R2 stage 在空闲集群下不会被 R1 master trigger（详见 LiquidationEngine 文档）。
        log("  polling until position liquidated...");
        boolean liquidated = false;
        for (int i = 0; i < 15; i++) {
            Thread.sleep(1_000);
            ok(api.adjustMarkPrice(PERP_SYMBOL, 100.0), "keepalive adjustMarkPrice 100");
            SingleUserReportResultView poll = api.queryUserReport(LIQ_USER).get(5, TimeUnit.SECONDS);
            List<SingleUserReportResultView.PositionView> pos = poll.getPositions().get(PERP_SYMBOL);
            if (pos == null || pos.isEmpty() || pos.get(0).getOpenVolume().compareTo(BigDecimal.ZERO) == 0) {
                liquidated = true;
                log("  LIQ_USER position liquidated at poll " + (i + 1) + "s");
                break;
            }
            log("  poll " + (i + 1) + "s: still has pos vol=" + pos.get(0).getOpenVolume());
        }
        assertTrue(liquidated, "LIQ_USER position should be liquidated within 15s");

        // 强平触发后核心证据是仓位归零（已断言）；PFUND 损失值取决于 engine 路径：
        // - IF takeover（setUp 充了 IF 1M）：用户损 mark-to-market 差额 + 部分保证金，PFUND 减少有限
        // - force liquidation IOC 吃 LP BID @100：保证金穿仓，PFUND 接近 0
        // 当前 setUp 让 IF 优先吃仓，所以只断言「有损失」即可，不强求穿仓金额
        SingleUserReportResultView afterLiq = api.queryUserReport(LIQ_USER).get(5, TimeUnit.SECONDS);
        BigDecimal pfundLeft = afterLiq.getAccounts().get(PFUND_ID);
        log("  LIQ_USER PFUND after liquidation = " + pfundLeft + " (initial 6000)");
        assertNotNull(pfundLeft, "LIQ_USER PFUND balance must exist");
        assertTrue(pfundLeft.compareTo(new BigDecimal("6000")) < 0,
            "LIQ_USER PFUND should be < 6000 after liquidation (some loss expected)");

        // 恢复 mark price，让后续测试正常工作
        ok(api.adjustMarkPrice(PERP_SYMBOL, OPEN_PRICE), "restore mark to 10000");

        log("✓ Liquidation triggered and completed OK");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: 交割合约开仓 + settlePnl（多头盈利，空头亏损）
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(4)
    void delivery_openPosition_settlePnl() throws Exception {
        log("=== Delivery: open positions ===");

        BigDecimal aBalanceBefore = api.queryUserReport(TRADER_A).get(5, TimeUnit.SECONDS).getAccounts().get(PFUND_ID);
        BigDecimal bBalanceBefore = api.queryUserReport(TRADER_B).get(5, TimeUnit.SECONDS).getAccounts().get(PFUND_ID);
        log("  TRADER_A PFUND before = " + aBalanceBefore);
        log("  TRADER_B PFUND before = " + bBalanceBefore);

        // TRADER_A 做多（BID，maker）
        long dBidId = nextOrder();
        ok(api.placeOrder(TRADER_A, dBidId, DELIVERY_SYMBOL, OrderAction.BID, OrderType.GTC, OPEN_PRICE, 0.0, OPEN_SIZE,
            MarginMode.CROSS, LEVERAGE, false), "TRADER_A BID delivery 5@10000 CROSS");

        // TRADER_B 做空（ASK，taker）
        long dAskId = nextOrder();
        ok(api.placeOrder(TRADER_B, dAskId, DELIVERY_SYMBOL, OrderAction.ASK, OrderType.GTC, OPEN_PRICE, 0.0, OPEN_SIZE,
            MarginMode.CROSS, LEVERAGE, false), "TRADER_B ASK delivery 5@10000 CROSS");

        Thread.sleep(500);
        assertNotNull(api.queryUserReport(TRADER_A).get(5, TimeUnit.SECONDS).getPositions().get(DELIVERY_SYMBOL),
            "TRADER_A should have delivery position");
        assertNotNull(api.queryUserReport(TRADER_B).get(5, TimeUnit.SECONDS).getPositions().get(DELIVERY_SYMBOL),
            "TRADER_B should have delivery position");

        // ── settlePnl 高于开仓价 500 → 多头盈利 500*5=2500 raw（绝对值由 OPEN_PRICE 决定）
        double settlePrice = OPEN_PRICE + 500.0;
        log("=== Delivery: settlePnl @ " + settlePrice + " ===");
        ok(api.settlePnl(DELIVERY_SYMBOL, settlePrice), "settlePnl " + settlePrice);

        Thread.sleep(500);
        BigDecimal aBalanceAfter = api.queryUserReport(TRADER_A).get(5, TimeUnit.SECONDS).getAccounts().get(PFUND_ID);
        BigDecimal bBalanceAfter = api.queryUserReport(TRADER_B).get(5, TimeUnit.SECONDS).getAccounts().get(PFUND_ID);
        log("  TRADER_A PFUND after settle = " + aBalanceAfter + " Δ=" + aBalanceAfter.subtract(aBalanceBefore));
        log("  TRADER_B PFUND after settle = " + bBalanceAfter + " Δ=" + bBalanceAfter.subtract(bBalanceBefore));

        // 多头余额增加（profit - fees），空头余额减少（loss + fees）
        assertTrue(aBalanceAfter.compareTo(aBalanceBefore) > 0,
            "TRADER_A (long) PFUND should increase after profitable delivery settlement");
        assertTrue(bBalanceAfter.compareTo(bBalanceBefore) < 0,
            "TRADER_B (short) PFUND should decrease after losing delivery settlement");

        // 确认仓位已结算关闭
        List<SingleUserReportResultView.PositionView> aPos =
            api.queryUserReport(TRADER_A).get(5, TimeUnit.SECONDS).getPositions().get(DELIVERY_SYMBOL);
        assertTrue(aPos == null || aPos.isEmpty() || aPos.get(0).getOpenVolume().compareTo(BigDecimal.ZERO) == 0,
            "TRADER_A delivery position should be settled/closed");

        log("✓ Delivery settlePnl OK");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5: reduceOrder + 对账（整体账本应该平）
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(5)
    void perp_reduceOrder_andMetrics() throws Exception {
        log("=== Perp: reduceOrder (partial position close) ===");

        // 查询 TRADER_B 当前 SHORT 仓位（跨 run 可能积累）
        SingleUserReportResultView bBefore = api.queryUserReport(TRADER_B).get(5, TimeUnit.SECONDS);
        List<SingleUserReportResultView.PositionView> bPosBefore = bBefore.getPositions().get(PERP_SYMBOL);
        BigDecimal volBefore =
            (bPosBefore != null && !bPosBefore.isEmpty()) ? bPosBefore.get(0).getOpenVolume() : BigDecimal.ZERO;
        log("  TRADER_B SHORT vol before reduce = " + volBefore);

        if (volBefore.compareTo(BigDecimal.ZERO) > 0) {
            // 先让 LP 挂 ASK 提供对手盘
            long lpAsk2 = nextOrder();
            ok(api.placeOrder(LP, lpAsk2, PERP_SYMBOL, OrderAction.ASK, OrderType.GTC, OPEN_PRICE, 0.0, 3.0,
                MarginMode.CROSS, LEVERAGE, false), "LP ASK 3@10000 for TRADER_B partial close");

            // TRADER_B 下 reduceOnly BID 3 手 → 减仓（不能增仓）
            long reduceBidId = nextOrder();
            ok(api.placeOrder(TRADER_B, reduceBidId, PERP_SYMBOL, OrderAction.BID, OrderType.GTC, OPEN_PRICE, 0.0, 3.0,
                MarginMode.CROSS, LEVERAGE, true), "TRADER_B BID 3@10000 reduceOnly");

            Thread.sleep(500);
            SingleUserReportResultView bReport = api.queryUserReport(TRADER_B).get(5, TimeUnit.SECONDS);
            List<SingleUserReportResultView.PositionView> bPos = bReport.getPositions().get(PERP_SYMBOL);
            BigDecimal volAfter = (bPos != null && !bPos.isEmpty()) ? bPos.get(0).getOpenVolume() : BigDecimal.ZERO;
            log("  TRADER_B SHORT after reduce = " + volAfter + " (should be < " + volBefore + ")");
            assertTrue(volAfter.compareTo(volBefore) < 0, "TRADER_B position should decrease after reduceOnly");
        } else {
            log("  TRADER_B has no SHORT position, skipping reduce");
        }

        log("✓ reduceOrder partial close OK");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 6: 强平后显式连 3 个节点 query，验证 follower 状态跟 leader 一致
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(6)
    void liquidation_3nodesReportSameOpenVolume() throws Exception {
        log("=== 3-node consistency: explicitly query each node ===");

        // 拿到所有节点 [host, grpcPort, role]
        List<String[]> nodes = resolveAllNodes();
        assertTrue(nodes.size() >= 3, "应至少有 3 个节点，实际 " + nodes.size());

        // perp_liquidation_markPriceCrash (Order 3) 已让 LIQ_USER vol=0，3 节点都应该看到
        BigDecimal first = null;
        for (String[] node : nodes) {
            String host = node[0];
            int port = Integer.parseInt(node[1]);
            String role = node[2];
            try (ExchangeApi nodeApi = ExchangeApi.connect(host, port,
                ExchangeApiOptions.builder().sendTimeout(Duration.ofSeconds(10)).build())) {
                SingleUserReportResultView rep = nodeApi.queryUserReport(LIQ_USER).get(10, TimeUnit.SECONDS);
                List<SingleUserReportResultView.PositionView> pos = rep.getPositions().get(PERP_SYMBOL);
                BigDecimal vol = (pos == null || pos.isEmpty()) ? BigDecimal.ZERO : pos.get(0).getOpenVolume();
                log("  node " + host + ":" + port + " (" + role + ") LIQ_USER openVol=" + vol);

                if (first == null) {
                    first = vol;
                } else {
                    assertEquals(0, vol.compareTo(first),
                        "节点 " + host + ":" + port + " 的 openVolume=" + vol + " 跟首节点不一致 " + first + "—— raft 复制失效");
                }
            }
        }

        log("✓ 3 nodes all report consistent LIQ_USER openVolume = " + first);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 7: 集群拓扑健康检查（3 nodes, 1 leader）
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(7)
    void raft_clusterTopology_3nodes_1leader() throws Exception {
        log("=== cluster topology sanity check ===");
        JsonObject cluster = fetchClusterJson();

        int nodeCount = cluster.get("node_count").getAsInt();
        assertEquals(3, nodeCount, "node_count 应为 3，实际 " + nodeCount);

        int leaderCount = 0;
        for (var el : cluster.getAsJsonArray("nodes")) {
            if ("LEADER".equals(el.getAsJsonObject().get("role").getAsString()))
                leaderCount++;
        }
        assertEquals(1, leaderCount, "整集群应有且仅有 1 个 LEADER，实际 " + leaderCount);

        log("✓ cluster healthy: 3 nodes, 1 leader");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 8: 边缘 — 客户端杠杆校验（leverage > maxLeverage 直接 IllegalArgumentException）
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(8)
    void perp_clientLeverageGuard_aboveMaxRejected() {
        log("=== perp client-side leverage guard ===");
        // spec.maxLeverage = 20，21x 应该被客户端在 build 阶段拦截
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> api.placeOrder(TRADER_A, nextOrder(), PERP_SYMBOL, OrderAction.BID, OrderType.GTC, OPEN_PRICE, 0.0,
                1.0, MarginMode.ISOLATED, /*leverage*/ 21, false),
            "leverage>maxLeverage 应在客户端 build 阶段抛 IllegalArgumentException");
        log("  blocked at client: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Leverage"), "异常信息应包含 Leverage 关键字，实际=" + ex.getMessage());

        // leverage=0 也应被拒
        assertThrows(
            IllegalArgumentException.class, () -> api.placeOrder(TRADER_A, nextOrder(), PERP_SYMBOL, OrderAction.BID,
                OrderType.GTC, OPEN_PRICE, 0.0, 1.0, MarginMode.ISOLATED, /*leverage*/ 0, false),
            "leverage=0 应在客户端 build 阶段被拒");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 9: 边缘 — reduceOnly 无持仓时静默裁剪为 0（不建仓也不拒绝）
    //
    // ONEWAY 模式下 RiskEngine.maxClosableSize(pos, req) = min(req, pos.openVolume)
    // 无持仓 → openVolume=0 → size 被裁剪为 0 → 返回 SUCCESS 但不进撮合
    // 这是当前已知行为：reduceOnly 不做方向校验，纯靠 size 裁剪。
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(9)
    void perp_reduceOnly_noPosition_silentlyNullified() throws Exception {
        log("=== perp reduceOnly without position ===");
        long u = TRADER_A + 90;
        ok(api.addUser(u), "addUser " + u);
        ok(api.adjustUserBalance(u, nextTx(), PFUND_ID, +10_000.0), "u +10000 PFUND");

        CommandResultView v = api.placeOrder(u, nextOrder(), PERP_SYMBOL, OrderAction.BID, OrderType.GTC, OPEN_PRICE,
            0.0, 1.0, MarginMode.CROSS, LEVERAGE, /*reduceOnly*/ true);
        log("  reduceOnly@no-position result=" + v.getResultCode());
        assertEquals(CommandResultCode.SUCCESS, v.getResultCode(),
            "reduceOnly+无持仓应 SUCCESS（size 被裁为 0），实际=" + v.getResultCode());

        Thread.sleep(300);
        SingleUserReportResultView rep = api.queryUserReport(u).get(5, TimeUnit.SECONDS);
        List<SingleUserReportResultView.PositionView> pos = rep.getPositions().get(PERP_SYMBOL);
        assertTrue(pos == null || pos.isEmpty() || pos.get(0).getOpenVolume().compareTo(BigDecimal.ZERO) == 0,
            "reduceOnly+无持仓不应建仓，实际=" + pos);
        // 账户余额完整保留（无 hold）
        assertEquals(0, rep.getAccounts().get(PFUND_ID).compareTo(new BigDecimal("10000")), "reduceOnly 静默裁剪不应扣余额");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 10: 边缘 — 负费率（settleFundingFees action=ASK：空头付多头）
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(10)
    void perp_settleFundingFees_negativeRate_shortsPayLongs() throws Exception {
        log("=== perp settleFundingFees negative rate ===");

        long aLong = TRADER_A + 100;
        long aShort = TRADER_A + 101;
        ok(api.addUser(aLong), "addUser longUser");
        ok(api.addUser(aShort), "addUser shortUser");
        ok(api.adjustUserBalance(aLong, nextTx(), PFUND_ID, +50_000.0), "longUser +50000 PFUND");
        ok(api.adjustUserBalance(aShort, nextTx(), PFUND_ID, +50_000.0), "shortUser +50000 PFUND");

        // 恢复 markPrice，避免被前一个 liquidation 残留状态影响
        ok(api.adjustMarkPrice(PERP_SYMBOL, OPEN_PRICE), "reset mark to 10000");

        // longUser BID 5@10000 (maker), shortUser ASK 5@10000 (taker) → 各开 5 手
        ok(api.placeOrder(aLong, nextOrder(), PERP_SYMBOL, OrderAction.BID, OrderType.GTC, OPEN_PRICE, 0.0, OPEN_SIZE,
            MarginMode.CROSS, LEVERAGE, false), "longUser BID 5@10000");
        ok(api.placeOrder(aShort, nextOrder(), PERP_SYMBOL, OrderAction.ASK, OrderType.GTC, OPEN_PRICE, 0.0, OPEN_SIZE,
            MarginMode.CROSS, LEVERAGE, false), "shortUser ASK 5@10000");
        Thread.sleep(500);

        BigDecimal longProfitBefore =
            api.queryUserReport(aLong).get(5, TimeUnit.SECONDS).getPositions().get(PERP_SYMBOL).get(0).getProfit();
        BigDecimal shortProfitBefore =
            api.queryUserReport(aShort).get(5, TimeUnit.SECONDS).getPositions().get(PERP_SYMBOL).get(0).getProfit();
        log("  before: long profit=" + longProfitBefore + " short profit=" + shortProfitBefore);

        // 关键：action=ASK 表示空头方向是付方 → 空头付多头 = 负费率
        ok(api.settleFundingFees(PERP_SYMBOL, OrderAction.ASK, 10L, 10_000L),
            "settleFundingFees ASK 0.1% (shorts pay longs)");
        Thread.sleep(300);

        BigDecimal longProfitAfter =
            api.queryUserReport(aLong).get(5, TimeUnit.SECONDS).getPositions().get(PERP_SYMBOL).get(0).getProfit();
        BigDecimal shortProfitAfter =
            api.queryUserReport(aShort).get(5, TimeUnit.SECONDS).getPositions().get(PERP_SYMBOL).get(0).getProfit();
        log("  after:  long profit=" + longProfitAfter + " short profit=" + shortProfitAfter);

        assertTrue(longProfitAfter.compareTo(longProfitBefore) > 0, "负费率：LONG 收 funding → profit 增加");
        assertTrue(shortProfitAfter.compareTo(shortProfitBefore) < 0, "负费率：SHORT 付 funding → profit 减少");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 11: 边缘 — adjustMargin 超过 PFUND 余额应被拒绝（保证金不能划无中生有）
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(11)
    void perp_adjustMargin_exceedingBalance_rejected() throws Exception {
        log("=== perp adjustMargin exceeding balance ===");

        long u = TRADER_A + 110;
        ok(api.addUser(u), "addUser " + u);
        ok(api.adjustUserBalance(u, nextTx(), PFUND_ID, +5_000.0), "u +5000 PFUND");

        // 开 ISOLATED 仓位
        ok(api.placeOrder(u, nextOrder(), PERP_SYMBOL, OrderAction.BID, OrderType.GTC, OPEN_PRICE, 0.0, 1.0,
            MarginMode.ISOLATED, LEVERAGE, false), "u BID 1@10000 ISOLATED");
        ok(api.placeOrder(LP, nextOrder(), PERP_SYMBOL, OrderAction.ASK, OrderType.GTC, OPEN_PRICE, 0.0, 1.0,
            MarginMode.CROSS, LEVERAGE, false), "LP ASK 1@10000 (counterpart)");
        Thread.sleep(300);

        BigDecimal beforeBal = api.queryUserReport(u).get(5, TimeUnit.SECONDS).getAccounts().get(PFUND_ID);

        // 尝试划 100000 extraMargin（远超 5000 余额）→ 应被拒
        CommandResultView v = api.adjustMargin(u, MarginMode.ISOLATED, PERP_SYMBOL, +100_000.0);
        log("  adjustMargin huge: code=" + v.getResultCode());
        assertNotEquals(CommandResultCode.SUCCESS, v.getResultCode(),
            "余额不足时 adjustMargin 不应 SUCCESS，实际=" + v.getResultCode());

        BigDecimal afterBal = api.queryUserReport(u).get(5, TimeUnit.SECONDS).getAccounts().get(PFUND_ID);
        assertEquals(0, beforeBal.compareTo(afterBal), "adjustMargin 被拒时余额不应变化");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 13: 第二个 PERP 合约（PBASE2/PFUND）基础撮合，验证多 symbol 隔离
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(13)
    void perp_secondSymbol_basicMatch_isolated() throws Exception {
        log("=== perp2 (PBASE2/PFUND) basic match ===");
        long bidId = nextOrder();
        ok(api.placeOrder(TRADER_A, bidId, PERP2_SYMBOL, OrderAction.BID, OrderType.GTC, OPEN_PRICE, 0.0, 1.0,
            MarginMode.CROSS, LEVERAGE, false), "TRADER_A BID 1@10000 PERP2 CROSS");
        long askId = nextOrder();
        ok(api.placeOrder(TRADER_B, askId, PERP2_SYMBOL, OrderAction.ASK, OrderType.GTC, OPEN_PRICE, 0.0, 1.0,
            MarginMode.CROSS, LEVERAGE, false), "TRADER_B ASK 1@10000 PERP2 CROSS");
        Thread.sleep(500);

        SingleUserReportResultView longReport = api.queryUserReport(TRADER_A).get(5, TimeUnit.SECONDS);
        SingleUserReportResultView shortReport = api.queryUserReport(TRADER_B).get(5, TimeUnit.SECONDS);
        assertNotNull(longReport.getPositions().get(PERP2_SYMBOL), "TRADER_A 应有 PERP2 仓位");
        assertNotNull(shortReport.getPositions().get(PERP2_SYMBOL), "TRADER_B 应有 PERP2 仓位");
        log("  ✓ PERP2 撮合成功，TRADER_A LONG / TRADER_B SHORT 各 1 手");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 14: 第二个 DELIVERY 合约（PBASE2/PFUND）基础撮合，验证多 symbol 隔离
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(14)
    void delivery_secondSymbol_basicMatch_isolated() throws Exception {
        log("=== delivery2 (PBASE2/PFUND) basic match ===");
        long bidId = nextOrder();
        ok(api.placeOrder(TRADER_A, bidId, DELIVERY2_SYMBOL, OrderAction.BID, OrderType.GTC, OPEN_PRICE, 0.0, 1.0,
            MarginMode.CROSS, LEVERAGE, false), "TRADER_A BID 1@10000 DELIVERY2 CROSS");
        long askId = nextOrder();
        ok(api.placeOrder(TRADER_B, askId, DELIVERY2_SYMBOL, OrderAction.ASK, OrderType.GTC, OPEN_PRICE, 0.0, 1.0,
            MarginMode.CROSS, LEVERAGE, false), "TRADER_B ASK 1@10000 DELIVERY2 CROSS");
        Thread.sleep(500);

        SingleUserReportResultView longReport = api.queryUserReport(TRADER_A).get(5, TimeUnit.SECONDS);
        SingleUserReportResultView shortReport = api.queryUserReport(TRADER_B).get(5, TimeUnit.SECONDS);
        assertNotNull(longReport.getPositions().get(DELIVERY2_SYMBOL), "TRADER_A 应有 DELIVERY2 仓位");
        assertNotNull(shortReport.getPositions().get(DELIVERY2_SYMBOL), "TRADER_B 应有 DELIVERY2 仓位");
        log("  ✓ DELIVERY2 撮合成功");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 15: 3 节点 stateHash 全模块严格比对
    //
    // 放在最后跑，覆盖 PERP/PERP2/DELIVERY/DELIVERY2 全部 orderbook 活动后的状态收敛。
    // 直连每个节点的 QueryService（server 端走 readFromQuorum 保证强一致读本地 state）。
    // 期望所有 submodule 在 3 节点上完全一致；10s 收敛窗口容忍 readIndex 后 apply 短暂滞后。
    // 任一 submodule 持续漂移即 fail —— 这是 raft FSM 状态机不一致或 hash 算法非确定性的红线。
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    @Order(15)
    void raft_3nodesStateHashFullyConverged() throws Exception {
        log("=== 3-node stateHash strict convergence ===");
        List<String[]> nodes = resolveAllNodes();
        assertTrue(nodes.size() >= 3, "至少 3 节点，实际 " + nodes.size());

        List<String> lastDivergences = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            Map<String, Map<Long, Integer>> perNode = new java.util.LinkedHashMap<>();
            for (String[] node : nodes) {
                String host = node[0];
                int port = Integer.parseInt(node[1]);
                String role = node[2];
                StateHashReportResult sh = queryStateHashDirect(host, port);
                Map<Long, Integer> flat = new java.util.HashMap<>();
                for (HashCodeEntry e : sh.getHashCodesList()) {
                    long k =
                        ((long)e.getKey().getModuleId() << 32) | (e.getKey().getSubmoduleTypeValue() & 0xFFFF_FFFFL);
                    flat.put(k, e.getValue());
                }
                perNode.put(host + ":" + port + "(" + role + ")", flat);
            }
            Map.Entry<String, Map<Long, Integer>> baseEntry = perNode.entrySet().iterator().next();
            Map<Long, Integer> base = baseEntry.getValue();
            assertFalse(base.isEmpty(), "stateHash 应至少包含一个 submodule，实际为空");

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
                        divergences.add(entry.getKey() + " submodule " + decodeSubmoduleKey(kv.getKey()) + " hash="
                            + otherVal + " ≠ base " + baseEntry.getKey() + " hash=" + kv.getValue());
                    }
                }
            }
            if (divergences.isEmpty()) {
                log("  ✓ all 3 nodes converged on " + base.size() + " submodule hashes (attempt " + attempt + ")");
                return;
            }
            log("  attempt " + attempt + ": " + divergences.size() + " divergences, waiting 1s ...");
            lastDivergences = divergences;
            Thread.sleep(1_000);
        }
        log("  ✗ DIVERGENCES (" + lastDivergences.size() + ") after 10s:");
        for (String d : lastDivergences)
            log("    " + d);
        // dump 4 个测试用户在 3 节点上的状态，定位实际差异字段
        long[] dumpUids = {TRADER_A, TRADER_B, LP, LIQ_USER};
        String[] dumpNames = {"TRADER_A", "TRADER_B", "LP", "LIQ_USER"};
        for (int i = 0; i < dumpUids.length; i++) {
            log("  ── " + dumpNames[i] + " (uid=" + dumpUids[i] + ") per-node state ──");
            for (String[] node : nodes) {
                String tag = node[0] + ":" + node[1] + "(" + node[2] + ")";
                try {
                    log("    " + tag + ": " + dumpUserOnNode(node[0], Integer.parseInt(node[1]), dumpUids[i]));
                } catch (Exception e) {
                    log("    " + tag + ": ERR " + e.getMessage());
                }
            }
        }
        fail("raft 状态机 10s 内未收敛，最后一轮 " + lastDivergences.size() + " 个 submodule hash 不一致：" + lastDivergences);
    }

    /** 直连节点 dump 用户余额 + 所有 symbol 上的 position 关键字段，用 toString 便于 diff。 */
    private static String dumpUserOnNode(String host, int grpcPort, long uid) {
        ManagedChannel ch = NettyChannelBuilder.forAddress(host, grpcPort).usePlaintext().build();
        try {
            com.binance.raftexchange.stubs.report.SingleUserReportResult r = QueryServiceGrpc.newBlockingStub(ch)
                .query(ReportQuery.newBuilder().setTransferId((int)(System.nanoTime() & 0x7FFF_FFFFL))
                    .setSingleUserReport(
                        com.binance.raftexchange.stubs.report.SingleUserReportQuery.newBuilder().setUserId(uid).build())
                    .build())
                .getSingleUserReport();
            StringBuilder sb = new StringBuilder();
            sb.append("accounts=").append(r.getAccountsMap());
            sb.append(" positions={");
            for (Map.Entry<Integer, com.binance.raftexchange.stubs.report.PositionList> e : r.getPositionsMap()
                .entrySet()) {
                for (com.binance.raftexchange.stubs.report.Position p : e.getValue().getPositionsList()) {
                    sb.append("[sym").append(e.getKey()).append(" dir=").append(p.getDirection()).append(" vol=")
                        .append(p.getOpenVolume()).append(" priceSum=").append(p.getOpenPriceSum()).append(" profit=")
                        .append(p.getProfit()).append(" pendB=").append(p.getPendingBuySize()).append("@")
                        .append(p.getPendingBuyAvgPrice()).append(" pendS=").append(p.getPendingSellSize()).append("@")
                        .append(p.getPendingSellAvgPrice()).append(" exM=").append(p.getExtraMargin()).append("]");
                }
            }
            sb.append("}");
            return sb.toString();
        } finally {
            ch.shutdownNow();
        }
    }

    /** key = moduleId<<32 | submoduleTypeValue，反向解出便于诊断。 */
    private static String decodeSubmoduleKey(long k) {
        int moduleId = (int)(k >>> 32);
        int submoduleType = (int)k;
        com.binance.raftexchange.stubs.report.SubmoduleType st;
        try {
            st = com.binance.raftexchange.stubs.report.SubmoduleType.forNumber(submoduleType);
        } catch (Exception e) {
            st = null;
        }
        return "(module=" + moduleId + "," + (st != null ? st.name() : "code=" + submoduleType) + ")";
    }

    /** 直连指定节点的 QueryService，绕开 ExchangeClient 内部的 round_robin，确保打到该节点。 */
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

    /** 返回每个节点 [host, grpcPort, role]，host/role 取自 cluster JSON。 */
    private static List<String[]> resolveAllNodes() {
        JsonObject cluster = fetchClusterJson();
        List<String[]> result = new ArrayList<>();
        for (var el : cluster.getAsJsonArray("nodes")) {
            JsonObject n = el.getAsJsonObject();
            result.add(new String[] {n.get("host").getAsString(), String.valueOf(n.get("grpc_port").getAsInt()),
                n.get("role").getAsString()});
        }
        return result;
    }

    /** 从任意一个 mgmt 端口拿 /raft/cluster 的 JSON。 */
    private static JsonObject fetchClusterJson() {
        for (int mgmt : MGMT_PORTS) {
            try {
                HttpURLConnection conn =
                    (HttpURLConnection)new URL("http://" + HOST + ":" + mgmt + "/raft/cluster").openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                if (conn.getResponseCode() == 200) {
                    String body = new String(conn.getInputStream().readAllBytes());
                    return JsonParser.parseString(body).getAsJsonObject();
                }
            } catch (Exception ignored) {
            }
        }
        throw new IllegalStateException("无法从任何 mgmt 端口拿到 /raft/cluster: " + java.util.Arrays.toString(MGMT_PORTS));
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
