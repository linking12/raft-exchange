package com.binance.raftexchange.client;

import com.binance.raftexchange.stubs.SymbolType;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Raft snapshot 跨重启状态保留验证（人工运维 smoke test，分两个阶段）。
 *
 * <p>
 * 用 fixed ID 是必要的——重启集群后才能用同样的 ID 查到状态。
 *
 * <p>
 * 用 EnabledIfSystemProperty 而非 @Disabled gate：避免全局 conditions.deactivate=*DisabledCondition 把这个 cumulative-state 敏感测试卷进
 * full-suite 回归（其他 E2E 测试会污染集群状态， 导致 currency/user/orderId/余额各种冲突）。手动跑时显式开 -Dsnapshot.recovery=true。
 *
 * <p>
 * <b>使用流程：</b>
 * 
 * <pre>
 * # 阶段 1：建状态（要求干净集群）
 * ./start-local-cluster.sh start full
 * mvn -pl raft-exchange-client test \
 *     -Dtest=LiveClusterSnapshotRecoveryE2ETest#basicReadWriteAcrossThreeNodes \
 *     -Dsnapshot.recovery=true
 *
 * # 手动触发 snapshot
 * curl -X POST http://127.0.0.1:28081/raft/snapshot
 *
 * # 重启集群（保留 RAFT-EXCHANGE-DATA，验证 snapshot 加载）
 * ./start-local-cluster.sh stop
 * ./start-local-cluster.sh start full
 *
 * # 阶段 2：验证状态恢复
 * mvn -pl raft-exchange-client test \
 *     -Dtest=LiveClusterSnapshotRecoveryE2ETest#snapshotRecovery_stateRestoredAfterRestart \
 *     -Dsnapshot.recovery=true
 * </pre>
 *
 * <p>
 * <b>已知覆盖盲区</b>：JRaft snapshot 是每节点独立的；上面只 curl 了 28081 一个 mgmt 端口， 实际只有该节点真正跑了 snapshot save+load 完整闭环；其他 2 节点本地无
 * snapshot 文件， 重启时直接走 raft log 全量 replay。所以这条测试"通过 + 3 节点 stateHash 收敛"只能证明 1 个节点的 snapshot codec round-trip 没问题，不能证明
 * install-snapshot RPC / 跨节点 snapshot 加载的正确性。要全覆盖：
 * <ul>
 * <li>curl 3 个 mgmt 端口（28081/28082/28083）让每节点各自打 snapshot；</li>
 * <li>或 phase1 末尾先 {@code POST /raft/stepdown} 把 leader 转给非 28081 节点， 验跨节点 leader 切换 + snapshot install 路径。</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "snapshot.recovery", matches = "true")
class LiveClusterSnapshotRecoveryE2ETest {

    // 连接 leader（node1 gRPC 端口）
    private static final String HOST = "127.0.0.1";
    private static final int GRPC_PORT = 5001;

    private static final int USDT_ID = 2;
    private static final int BTC_ID = 3;
    private static final int SYMBOL_ID = 100;
    private static final long BUYER = 1001L;
    private static final long SELLER = 1002L;

    @Test
    void basicReadWriteAcrossThreeNodes() throws Exception {
        ExchangeApiOptions opts = ExchangeApiOptions.builder().sendTimeout(Duration.ofSeconds(30)).build();
        try (ExchangeApi client = ExchangeApi.connect(HOST, GRPC_PORT, opts)) {

            // ---- Phase 1: 初始化货币 + 用户 ----
            log("=== Phase 1: addCurrency / addUser ===");
            ok(client.addCurrency(USDT_ID, "USDT", 6), "addCurrency USDT");
            ok(client.addCurrency(BTC_ID, "BTC", 8), "addCurrency BTC");
            ok(client.addUser(BUYER), "addUser BUYER=" + BUYER);
            ok(client.addUser(SELLER), "addUser SELLER=" + SELLER);

            // ---- Phase 2: 充值 ----
            log("=== Phase 2: adjustUserBalance ===");
            ok(client.adjustUserBalance(BUYER, 1L, USDT_ID, +10000.0), "BUYER +10000 USDT");
            ok(client.adjustUserBalance(BUYER, 2L, USDT_ID, -100.5), "BUYER -100.5 USDT");
            ok(client.adjustUserBalance(SELLER, 3L, BTC_ID, +1.5), "SELLER +1.5 BTC");

            Thread.sleep(300);

            // ---- Phase 3: queryUserReport 验证余额 ----
            log("=== Phase 3: queryUserReport ===");
            SingleUserReportResultView buyerReport = client.queryUserReport(BUYER).get(5, TimeUnit.SECONDS);
            assertNotNull(buyerReport, "BUYER report should not be null");
            assertEquals(com.binance.raftexchange.stubs.report.QueryExecutionStatus.OK,
                buyerReport.getQueryExecutionStatus(), "query status should be OK");

            Map<Integer, BigDecimal> buyerAccounts = buyerReport.getAccounts();
            BigDecimal usdtBalance = buyerAccounts.get(USDT_ID);
            assertNotNull(usdtBalance, "BUYER USDT balance should exist");
            log("  BUYER USDT balance = " + usdtBalance);
            assertEquals(0, new BigDecimal("9899.5").compareTo(usdtBalance),
                "BUYER USDT expected=9899.5 actual=" + usdtBalance);

            SingleUserReportResultView sellerReport = client.queryUserReport(SELLER).get(5, TimeUnit.SECONDS);
            BigDecimal btcBalance = sellerReport.getAccounts().get(BTC_ID);
            assertNotNull(btcBalance, "SELLER BTC balance should exist");
            log("  SELLER BTC  balance = " + btcBalance);
            assertEquals(0, new BigDecimal("1.5").compareTo(btcBalance),
                "SELLER BTC expected=1.5 actual=" + btcBalance);

            // ---- Phase 4: 现货撮合 ----
            log("=== Phase 4: placeOrder spot ===");
            ok(client.addSymbol(SYMBOL_ID, SymbolType.CURRENCY_EXCHANGE_PAIR, BTC_ID, USDT_ID,
                /*baseScaleK*/ 100_000_000L, /*quoteScaleK*/ 1_000_000L, /*takerFee*/ 1_000L, /*makerFee*/ 500L,
                /*liquidationFee*/ 0L, /*feeScaleK*/ 1_000_000L, /*initMargin*/ 0L, /*initMarginScaleK*/ 0L,
                /*maintenanceMargin*/ null, /*maintenanceMarginScaleK*/ 0L, /*maxLeverage*/ null),
                "addSymbol BTC/USDT spot");

            // seller 挂 ASK 0.1 BTC @ 60000
            ok(client.placeOrder(SELLER, 1L, SYMBOL_ID, com.binance.raftexchange.stubs.OrderAction.ASK,
                com.binance.raftexchange.stubs.OrderType.GTC, 60000.0, 0.0, 0.1, null, 0, false),
                "SELLER ASK 0.1 BTC @ 60000");

            // buyer 吃单 BID 0.1 BTC @ 60000
            ok(client.placeOrder(BUYER, 2L, SYMBOL_ID, com.binance.raftexchange.stubs.OrderAction.BID,
                com.binance.raftexchange.stubs.OrderType.GTC, 60000.0, 60001.0, 0.1, null, 0, false),
                "BUYER  BID 0.1 BTC @ 60000 (taker)");

            Thread.sleep(500);

            // 撮合后：BUYER 花掉 6000 USDT (+ taker fee 6 USDT) → 剩 9899.5 - 6006 = 3893.5
            SingleUserReportResultView afterTrade = client.queryUserReport(BUYER).get(5, TimeUnit.SECONDS);
            BigDecimal usdtAfter = afterTrade.getAccounts().get(USDT_ID);
            log("  BUYER USDT after trade = " + usdtAfter);
            // 仅断言余额减少了，具体费率由引擎决定
            assert usdtAfter.compareTo(usdtBalance) < 0 : "BUYER USDT should decrease after buying BTC";

            log("");
            log("✓ Phase 1: 货币/用户初始化 OK");
            log("✓ Phase 2: 充值/提现写入 OK（经 raft 共识落盘）");
            log("✓ Phase 3: queryUserReport 余额正确");
            log("✓ Phase 4: 现货 GTC 撮合 + 余额变化 OK");
        }
    }

    /**
     * 验证 snapshot load 后状态恢复：先跑 basicReadWriteAcrossThreeNodes，触发 snapshot， 重启集群，再跑此测试——余额应仍存在。
     */
    @Test
    void snapshotRecovery_stateRestoredAfterRestart() throws Exception {
        ExchangeApiOptions opts = ExchangeApiOptions.builder().sendTimeout(Duration.ofSeconds(10)).build();
        try (ExchangeApi client = ExchangeApi.connect(HOST, GRPC_PORT, opts)) {
            log("=== Snapshot Recovery: queryUserReport after restart ===");

            SingleUserReportResultView buyerReport = client.queryUserReport(BUYER).get(5, TimeUnit.SECONDS);
            assertNotNull(buyerReport, "BUYER report should not be null after snapshot recovery");
            assertEquals(com.binance.raftexchange.stubs.report.QueryExecutionStatus.OK,
                buyerReport.getQueryExecutionStatus());

            Map<Integer, BigDecimal> accounts = buyerReport.getAccounts();
            BigDecimal usdt = accounts.get(USDT_ID);
            assertNotNull(usdt, "BUYER USDT balance must exist — state not recovered from snapshot");
            log("  BUYER USDT after recovery = " + usdt);
            // 经过 Phase2 充值 +10000 -100.5 = 9899.5，Phase4 买 0.1 BTC 后余额 < 9899.5
            assert usdt.compareTo(BigDecimal.ZERO) > 0 : "BUYER USDT should be > 0";
            assert usdt.compareTo(new BigDecimal("9900")) < 0 : "BUYER USDT should be < 9900 (trade happened)";

            SingleUserReportResultView sellerReport = client.queryUserReport(SELLER).get(5, TimeUnit.SECONDS);
            BigDecimal btc = sellerReport.getAccounts().get(BTC_ID);
            assertNotNull(btc, "SELLER BTC balance must exist — state not recovered from snapshot");
            log("  SELLER BTC  after recovery = " + btc);

            log("✓ Snapshot load: BUYER/SELLER balances restored correctly");
        }
    }

    private static void ok(CommandResultView v, String label) {
        System.out.printf("[%-40s] %s%n", label, v.getResultCode());
        assertEquals(CommandResultCode.SUCCESS, v.getResultCode(),
            label + " expected SUCCESS but got " + v.getResultCode());
    }

    private static void log(String msg) {
        System.out.println(msg);
    }
}
