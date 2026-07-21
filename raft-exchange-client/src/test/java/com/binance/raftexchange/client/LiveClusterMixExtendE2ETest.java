package com.binance.raftexchange.client;

import com.binance.raftexchange.stubs.MarginMode;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.SymbolType;
import com.binance.raftexchange.stubs.request.SpotLoanConfig;
import com.binance.raftexchange.stubs.request.SpotLoanGlobalConfig;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一账户的<b>并发</b>验证：现货 / 期货 / 借贷三条流同时打同一个账户。
 *
 * <p>与 {@link LiveClusterMixE2ETest} 的分工——那边是<b>顺序</b>走完整剧情（入金 → 三域建仓 →
 * 行情崩塌 → 级联强平 → 收尾），断言精确，读起来是一条故事线；这里不讲故事，只做一件事：
 * 让三个域<b>真正同时</b>争抢同一份 USDT，看引擎在并发下还守不守恒。
 *
 * <p><b>为什么这里的断言必须比各域 E2E 松</b>：共享账户下期货资金费、借贷利息、现货冻结都在改同一份
 * USDT，任何「余额精确等于 X」或「某值没变」的断言都会被<b>他域的正常行为</b>打红，归因不到引擎。
 * 故三条流内只断结果码与本域独占量（持仓敞口、贷款 outstanding），真正的资金正确性交给并发结束后的
 * 全局守恒——守恒等式覆盖所有资金桶，任何一域算错都会让它不为零。
 */
@EnabledIfSystemProperty(named = "livecluster", matches = "true") // 需本地三节点集群
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveClusterMixExtendE2ETest extends LiveClusterE2EBase {

    private long trader;     // 主角：三条流共用这一个账户
    private long lp;         // 对手方与深度

    private int btcUsdtSpot;
    private int btcUsdtPerp; // 与现货同币对，不同 symbolId
    private int bnbUsdtSpot; // 借贷 pair

    private double btcPrice;

    /** 基类的 nextOrderId/nextTxId 非线程安全，并发段一律走这两个。 */
    private final AtomicLong concurrentOrderIds = new AtomicLong();
    private final AtomicLong concurrentTxIds = new AtomicLong();

    @Test
    @Order(1)
    void setup_sharedAccountMarkets() {
        log("=== mix-extend setup：一个账户，三个域 ===");
        ensureAllCurrencies();

        trader = uid(1);
        lp = uid(2);
        btcUsdtSpot = SHARED_BTC_USDT;
        btcUsdtPerp = symbolId(1);
        bnbUsdtSpot = SHARED_BNB_USDT;
        btcPrice = 20_000.0 + (runId % 5_000);
        concurrentOrderIds.set(System.currentTimeMillis() * 1_000L);
        concurrentTxIds.set(System.currentTimeMillis() * 1_000L + 500_000_000L);

        ok(api.addUser(trader), "addUser trader");
        ok(api.addUser(lp), "addUser lp");

        addSpotSymbol(btcUsdtSpot, BTC_ID, USDT_ID, "BTC/USDT");
        addSpotSymbol(bnbUsdtSpot, BNB_ID, USDT_ID, "BNB/USDT");
        addSpotSymbol(SHARED_LTC_USDT, LTC_ID, USDT_ID, "LTC/USDT");
        addPerp(btcUsdtPerp, "BTC/USDT PERP");

        // 三条流同时抽同一份 USDT，余额必须给足——否则先跑到的那条把钱花光，
        // 后面的流会拿到 RISK_NSF，测出来的是"钱不够"而不是"并发下算错"
        ok(api.adjustUserBalance(trader, nextTxId(), USDT_ID, +500_000.0), "trader +500000 USDT");
        ok(api.adjustUserBalance(trader, nextTxId(), BNB_ID, +500.0), "trader +500 BNB");
        ok(api.adjustUserBalance(trader, nextTxId(), LTC_ID, +1_000.0), "trader +1000 LTC");
        ok(api.adjustUserBalance(lp, nextTxId(), USDT_ID, +5_000_000.0), "lp +5M USDT");
        ok(api.adjustUserBalance(lp, nextTxId(), BTC_ID, +100.0), "lp +100 BTC");

        ok(api.adjustMarkPrice(btcUsdtPerp, btcPrice), "markPrice PERP");
        ok(api.adjustMarkPrice(bnbUsdtSpot, 600.0), "markPrice BNB/USDT");

        ok(api.addLoanConfig(SpotLoanGlobalConfig.newBuilder().setNumeraireCcy(USDT_ID).build(),
            null, null), "配置 numeraire = USDT");
        ok(api.addLoanConfig(null, SpotLoanConfig.newBuilder()
            .setSymbolId(bnbUsdtSpot).setLoanInitialLtvBps(6000).build(), null), "enable loan BNB/USDT");
        ok(api.addLoanConfig(null, SpotLoanConfig.newBuilder()
            .setSymbolId(SHARED_LTC_USDT).setLoanInitialLtvBps(6000).build(), null), "enable loan LTC/USDT（LTC 作 Cross 抵押）");
        for (int shard = 0; shard < RISK_SHARDS; shard++) {
            ok(api.poolDeposit(shard, USDT_ID, 300_000.0), "poolDeposit shard" + shard);
            ok(api.loanIfDeposit(shard, USDT_ID, 100_000.0), "LIF 垫资 shard" + shard);
            ok(api.insuranceFundDeposit(btcUsdtPerp, shard, 200_000.0), "IF 预存 shard" + shard);
        }
        assertGlobalConserved("after setup");
    }

    @Test
    @Order(2)
    void threeDomains_concurrentlyOnOneAccount_conservationHolds() throws Exception {
        log("=== 三域并发打同一账户 ===");
        List<Callable<Void>> flows = new ArrayList<>();
        flows.add(() -> {
            spotFlow();
            return null;
        });
        flows.add(() -> {
            futuresFlow();
            return null;
        });
        flows.add(() -> {
            loanFlow();
            return null;
        });

        ExecutorService pool = Executors.newFixedThreadPool(flows.size());
        try {
            List<Future<Void>> results = pool.invokeAll(flows, 5, TimeUnit.MINUTES);
            // 逐个 get()：invokeAll 不抛，异常都憋在 Future 里，不 get 就会静默通过
            for (Future<Void> f : results) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // 并发段唯一的资金正确性判据
        assertGlobalConserved("after concurrent three-domain run");
        log("✓ 三域并发后全局守恒成立");
    }

    @Test
    @Order(3)
    void concurrentRun_convergedOnThreeNodes() throws Exception {
        log("=== 并发后三节点收敛 ===");
        List<String[]> nodes = resolveAllNodes();
        assertTrue(nodes.size() >= 3, "应至少 3 节点");

        BigDecimal expectedUsdt = null;
        Integer expectedLoans = null;
        for (String[] node : nodes) {
            try (ExchangeApi nodeApi = ExchangeApi.connect(node[0], Integer.parseInt(node[1]),
                ExchangeApiOptions.builder().sendTimeout(java.time.Duration.ofSeconds(10)).build())) {
                var rep = nodeApi.queryUserReport(trader).get(10, TimeUnit.SECONDS);
                BigDecimal usdt = rep.getAccounts().getOrDefault(USDT_ID, BigDecimal.ZERO);
                int loans = rep.getIsolatedLoans().size() + rep.getCrossLoans().size();
                log("  node " + node[0] + ":" + node[1] + "(" + node[2] + ") usdt=" + usdt + " loans=" + loans);
                if (expectedUsdt == null) {
                    expectedUsdt = usdt;
                    expectedLoans = loans;
                } else {
                    assertEquals(0, expectedUsdt.compareTo(usdt), "各节点 USDT 应一致");
                    assertEquals(expectedLoans, loans, "各节点贷款数应一致");
                }
            }
        }
        log("✓ mix-extend 并发驱动通过");
    }

    // ================================================================
    // 三条并发流：现货 / 期货 / 借贷
    // ================================================================

    /** 挂远价单 → 改价 → 减量 → 真实撮合 → 撤单。 */
    private void spotFlow() throws Exception {
        log("  [spot] start");

        // 远离市价挂单：不会成交，纯粹压 exchangeLocked 的占用与释放
        long resting = nextConcurrentOrderId();
        expectOk(api.placeOrder(trader, resting, btcUsdtSpot, OrderAction.BID, OrderType.GTC,
            btcPrice * 0.5, btcPrice * 0.7, 1.0, MarginMode.ISOLATED, 1, false), "spot 挂远价 BID");
        // 冻结基准是 reserveBidPrice(0.7p)，改价必须落在它之内，越过会被风控挡
        expectOk(api.moveOrder(trader, resting, btcUsdtSpot, btcPrice * 0.6), "spot 改价（reserve 以内）");
        expectOk(api.reduceOrder(trader, resting, btcUsdtSpot, 0.5), "spot 减量");

        BigDecimal btcBefore = balanceOf(trader, BTC_ID);
        expectOk(api.placeOrder(lp, nextConcurrentOrderId(), btcUsdtSpot, OrderAction.ASK, OrderType.GTC,
            btcPrice, 0.0, 0.5, MarginMode.ISOLATED, 1, false), "spot 对手挂 ASK");
        expectOk(api.placeOrder(trader, nextConcurrentOrderId(), btcUsdtSpot, OrderAction.BID, OrderType.GTC,
            btcPrice, btcPrice, 0.5, MarginMode.ISOLATED, 1, false), "spot 吃单 BID");
        settle();

        // BTC 只有现货这条流在动，可以断方向；USDT 是三域共享的，不断
        assertTrue(balanceOf(trader, BTC_ID).compareTo(btcBefore) > 0, "[spot] 吃单后应收到 BTC");

        expectOk(api.cancelOrder(trader, resting, btcUsdtSpot), "spot 收尾撤单");
        log("  [spot] done");
    }

    /** 开仓 → 追加保证金 → 砸价强平（走完 FORCE → IF）。 */
    private void futuresFlow() throws Exception {
        log("  [futures] start");

        expectOk(api.adjustMarkPrice(btcUsdtPerp, btcPrice), "futures markPrice 复位");
        expectOk(api.placeOrder(trader, nextConcurrentOrderId(), btcUsdtPerp, OrderAction.BID, OrderType.GTC,
            btcPrice, 0.0, 0.1, MarginMode.ISOLATED, 10, false), "futures 开多");
        expectOk(api.placeOrder(lp, nextConcurrentOrderId(), btcUsdtPerp, OrderAction.ASK, OrderType.GTC,
            btcPrice, 0.0, 0.1, MarginMode.ISOLATED, 10, false), "futures 对手 ASK");
        settle();
        assertTrue(openVolume(api, trader, btcUsdtPerp).compareTo(BigDecimal.ZERO) != 0, "[futures] 应建仓");

        // ISOLATED 追加保证金进的是仓位 extraMargin，不受他域动 accounts 影响
        expectOk(api.adjustMargin(trader, MarginMode.ISOLATED, btcUsdtPerp, +200.0), "futures 追加保证金");
        settle();

        expectOk(api.adjustMarkPrice(btcUsdtPerp, btcPrice * 0.80), "futures 砸价 -20%");
        awaitFlat(trader, btcUsdtPerp, 30);
        expectOk(api.adjustMarkPrice(btcUsdtPerp, btcPrice), "futures markPrice 复位");
        log("  [futures] done");
    }

    /** 逐仓借 → 部分还 → 结清 → 全仓加抵押。 */
    private void loanFlow() throws Exception {
        log("  [loan] start");

        long loanId = nextConcurrentTxId();
        expectOk(api.loanCreate(trader, loanId, bnbUsdtSpot, 10.0, 100.0), "loan 逐仓借款");
        settle();
        assertNotNull(isolatedLoan(trader, loanId), "[loan] 逐仓贷款应存在");

        expectOk(api.loanRepay(trader, loanId, bnbUsdtSpot, 40.0), "loan 部分还款");
        settle();
        // outstanding 是这笔贷款独占的，他域碰不到
        assertTrue(isolatedLoan(trader, loanId).getOutstandingPrincipal().compareTo(BigDecimal.ZERO) > 0,
            "[loan] 部分还款后仍应有余额");

        expectOk(api.loanRepay(trader, loanId, bnbUsdtSpot, 100.0), "loan 结清");
        settle();

        expectOk(api.loanCrossAddCollateral(trader, LTC_ID, 200.0), "loan 全仓加抵押");
        settle();
        log("  [loan] done");
    }

    // ================================================================
    // helpers
    // ================================================================

    private long nextConcurrentOrderId() {
        return concurrentOrderIds.getAndIncrement();
    }

    private long nextConcurrentTxId() {
        return concurrentTxIds.getAndIncrement();
    }

    /** 断言信息带 label，否则三条流并发时看不出是谁挂的。 */
    private static void expectOk(CommandResultView v, String label) {
        assertTrue(isOk(v.getResultCode()) || v.getResultCode() == CommandResultCode.VALID_FOR_MATCHING_ENGINE,
            label + " 期望成功，实际 " + v.getResultCode());
    }

    private BigDecimal balanceOf(long uid, int currency) throws Exception {
        BigDecimal v = api.queryUserReport(uid).get(10, TimeUnit.SECONDS).getAccounts().get(currency);
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal openVolume(ExchangeApi client, long uid, int symbol) throws Exception {
        var positions = client.queryUserReport(uid).get(10, TimeUnit.SECONDS).getPositions().get(symbol);
        return (positions == null || positions.isEmpty()) ? BigDecimal.ZERO : positions.get(0).getOpenVolume();
    }

    private SingleUserReportResultView.IsolatedLoanView isolatedLoan(long uid, long loanId) throws Exception {
        return api.queryUserReport(uid).get(10, TimeUnit.SECONDS).getIsolatedLoans().stream()
            .filter(l -> l.getLoanId() == loanId).findFirst().orElse(null);
    }

    private void awaitFlat(long uid, int symbol, int timeoutSec) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (openVolume(api, uid, symbol).compareTo(BigDecimal.ZERO) == 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(500L);
        }
        throw new AssertionError("[futures] 仓位 " + timeoutSec + "s 内未被强平：uid=" + uid + " symbol=" + symbol);
    }

    private void addPerp(int symbol, String label) {
        addFuturesSymbolSpec(symbol, SymbolType.FUTURES_CONTRACT_PERPETUAL, BTC_ID, USDT_ID, label);
    }

    /** accountBalances + fees + adjustments 之和恒为零；任何一域算错都会让它不为零。 */
    /**
     * 全局守恒：八个资金桶之和恒为零。
     *
     * <p>八个桶缺一不可——少加一个桶，那个桶里的错账就永远抓不到。尤其 loanBalances（贷款池 +
     * 利息收入 + LIF）与 ifBalances（期货保险基金）：混合测试的价值正是跨域记账，漏掉它们
     * 等于把要测的东西排除在外。
     */
    private void assertGlobalConserved(String stage) {
        var report = api.queryTotalCurrencyBalanceReport().join();
        long sum = 0;
        for (var bucket : List.of(report.getAccountBalancesMap(), report.getExtraMarginMap(),
            report.getExchangeLockedMap(), report.getLoanBalancesMap(), report.getLoanCollateralMap(),
            report.getFeesMap(), report.getAdjustmentsMap(), report.getSuspendsMap(), report.getIfBalancesMap())) {
            for (long v : bucket.values()) {
                sum += v;
            }
        }
        assertEquals(0L, sum, "全局守恒被破坏 @" + stage);
    }

    private static void settle() throws Exception {
        TimeUnit.MILLISECONDS.sleep(400L);
    }
}
