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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一账户全流程：现货 / 期货 / 借贷<b>共花同一份钱</b>，并在同一账户上经历强平。
 *
 * <p>这是最接近真实用户的一条线：入金 → 现货挂单 → 期货开仓 → 抵押借款 → 行情剧烈波动 →
 * 期货被强平（损失沿 FORCE → IF → ADL 传递）→ 抵押跌破强平线借贷也被强平 → 收尾平账。
 * 全程校验 {@code calculateLocked} 三方占用算得准、全局守恒不破。
 *
 * <p>四对共用币种：BTC/USDT 现货与 BTC/USDT 永续是<b>同一对币、不同 symbolId</b>
 * （symbolId → spec 一对一、type 单值，合约必须另给 id）。LTC digit=2 与其余 8/6 不同，顺带压 scale 换算。
 */
@EnabledIfSystemProperty(named = "livecluster", matches = "true") // 需本地三节点集群
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveClusterMixE2ETest extends LiveClusterE2EBase {

    private long trader;   // 主角：三域同时持仓，最终被强平
    private long winner;   // 盈利对手方，IF 不足时被 ADL 摊派
    private long lp;       // 提供深度

    private int btcUsdtSpot;
    private int btcUsdtPerp;  // 与现货同币对，不同 symbolId
    private int bnbUsdtSpot;  // 借贷抵押 pair
    private int ltcUsdtSpot;  // Cross 抵押（digit 2）

    private double btcPrice;
    private double bnbPrice;
    private static final double PERP_SIZE = 0.1;   // notional 保持在安全量级
    private static final int LEVERAGE = 10;

    // ================================================================
    // 一、建市场与入金
    // ================================================================

    @Test
    @Order(1)
    void setup_sharedCurrencyMarkets() {
        log("=== 统一账户 setup：四对共用币种 ===");
        ensureAllCurrencies();

        trader = uid(1);
        winner = uid(2);
        lp = uid(3);
        btcUsdtSpot = SHARED_BTC_USDT;
        btcUsdtPerp = symbolId(1);
        bnbUsdtSpot = SHARED_BNB_USDT;
        ltcUsdtSpot = SHARED_LTC_USDT;
        btcPrice = 20_000.0 + (runId % 5_000);
        bnbPrice = 600.0;

        for (long u : new long[] {trader, winner, lp}) {
            ok(api.addUser(u), "addUser " + u);
        }

        addSpotSymbol(btcUsdtSpot, BTC_ID, USDT_ID, "BTC/USDT");
        addSpotSymbol(bnbUsdtSpot, BNB_ID, USDT_ID, "BNB/USDT");
        addSpotSymbol(ltcUsdtSpot, LTC_ID, USDT_ID, "LTC/USDT");
        addPerpOnSameCurrencies(btcUsdtPerp, "BTC/USDT PERP");

        // 三域全花 USDT；BNB 既是现货资产也是抵押品
        ok(api.adjustUserBalance(trader, nextTxId(), USDT_ID, +50_000.0), "trader +50000 USDT");
        ok(api.adjustUserBalance(trader, nextTxId(), BNB_ID, +100.0), "trader +100 BNB");
        ok(api.adjustUserBalance(trader, nextTxId(), LTC_ID, +500.0), "trader +500 LTC");
        ok(api.adjustUserBalance(winner, nextTxId(), USDT_ID, +500_000.0), "winner +500000 USDT");
        ok(api.adjustUserBalance(lp, nextTxId(), USDT_ID, +5_000_000.0), "lp +5M USDT");
        ok(api.adjustUserBalance(lp, nextTxId(), BNB_ID, +1_000.0), "lp +1000 BNB");

        ok(api.adjustMarkPrice(btcUsdtPerp, btcPrice), "markPrice PERP");
        ok(api.adjustMarkPrice(bnbUsdtSpot, bnbPrice), "markPrice BNB/USDT");
        ok(api.adjustMarkPrice(ltcUsdtSpot, 100.0), "markPrice LTC/USDT");

        // numeraire 是全局配置，Cross 借款/撤抵押 fail-closed 依赖它；各测试都设 USDT，取值一致故互不干扰
        ok(api.addLoanConfig(SpotLoanGlobalConfig.newBuilder().setNumeraireCcy(USDT_ID).build(),
            null, null), "配置 numeraire = USDT");
        ok(api.addLoanConfig(null, SpotLoanConfig.newBuilder()
            .setSymbolId(bnbUsdtSpot).setLoanInitialLtvBps(6000).build(), null), "enable loan BNB/USDT");
        ok(api.addLoanConfig(null, SpotLoanConfig.newBuilder()
            .setSymbolId(ltcUsdtSpot).setLoanInitialLtvBps(6000).build(), null), "enable loan LTC/USDT");
        for (int shard = 0; shard < RISK_SHARDS; shard++) {
            ok(api.poolDeposit(shard, USDT_ID, 300_000.0), "poolDeposit shard" + shard);
            ok(api.loanIfDeposit(shard, USDT_ID, 100_000.0), "LIF 垫资 shard" + shard);
        }
        assertGlobalConserved("after setup");
    }

    // ================================================================
    // 二、一份钱供三域使用
    // ================================================================

    @Test
    @Order(2)
    void oneUsdtBalance_servesSpotFreeze_futuresMargin_andLoan() throws Exception {
        log("=== 一份 USDT 同时供三域使用 ===");
        BigDecimal usdtStart = balanceOf(trader, USDT_ID);

        ok(api.placeOrder(trader, nextOrderId(), btcUsdtSpot, OrderAction.BID, OrderType.GTC,
            btcPrice * 0.5, btcPrice * 0.5, 0.5, MarginMode.ISOLATED, 1, false), "现货挂单冻结 USDT");
        openPerp(trader, winner, PERP_SIZE);
        ok(api.loanCreate(trader, 7001L, bnbUsdtSpot, 50.0, 10_000.0), "借贷：50 BNB → 10000 USDT");
        awaitApplied();

        var report = api.queryUserReport(trader).get(10, TimeUnit.SECONDS);
        assertTrue(report.getAccounts().get(USDT_ID).compareTo(usdtStart) > 0,
            "借出的 USDT 进账户后应净增：start=" + usdtStart);
        assertEquals(1, report.getIsolatedLoans().size(), "应有一笔贷款");
        assertNotNull(report.getPositions().get(btcUsdtPerp), "应有期货仓位");
        assertGlobalConserved("after three domains share USDT");
    }

    @Test
    @Order(3)
    void collateralLockedBnb_cannotBeSoldOnSpot() {
        log("=== 同一份 BNB：抵押锁定后不可现货卖出 ===");
        var rejected = api.placeOrder(trader, nextOrderId(), bnbUsdtSpot, OrderAction.ASK, OrderType.GTC,
            bnbPrice, 0.0, 60.0, MarginMode.ISOLATED, 1, false);
        assertEquals(CommandResultCode.RISK_NSF, rejected.getResultCode(),
            "100 BNB 里 50 已抵押，卖 60 应被拒");
        ok(api.placeOrder(trader, nextOrderId(), bnbUsdtSpot, OrderAction.ASK, OrderType.GTC,
            bnbPrice * 2, 0.0, 40.0, MarginMode.ISOLATED, 1, false), "卖 40 在未抵押部分内，应放行");
    }

    @Test
    @Order(4)
    void crossLoan_fundsFuturesMargin() throws Exception {
        log("=== 借来的钱直接做期货保证金——统一账户的意义 ===");
        ok(api.loanCrossAddCollateral(trader, LTC_ID, 400.0), "Cross +400 LTC 抵押");
        ok(api.loanCrossBorrow(trader, 7002L, ltcUsdtSpot, 5_000.0), "Cross 借 5000 USDT");
        awaitApplied();

        var report = api.queryUserReport(trader).get(10, TimeUnit.SECONDS);
        assertEquals(0, new BigDecimal("400").compareTo(report.getCrossLoanCollateral().get(LTC_ID)),
            "LTC 抵押池应按 digit=2 还原");
        openPerp(trader, winner, PERP_SIZE);
        assertGlobalConserved("after cross loan funds futures");
    }

    // ================================================================
    // 三、行情崩塌：期货强平与借贷强平先后发生在同一账户
    // ================================================================

    @Test
    @Order(5)
    void btcCrash_liquidatesFuturesPosition_ifAbsorbs() throws Exception {
        log("=== BTC 暴跌 → 期货强平，IF 接管 ===");
        for (int shard = 0; shard < RISK_SHARDS; shard++) {
            ok(api.insuranceFundDeposit(btcUsdtPerp, shard, 1_000_000.0), "IF 预存 shard" + shard);
        }
        BigDecimal winnerVolBefore = perpVolumeOf(api, winner);

        // 不挂接盘单：FORCE 撮合无对手 → 转 IF
        ok(api.adjustMarkPrice(btcUsdtPerp, btcPrice * 0.85), "markPrice -15%");
        awaitPerpFlat(trader, 30);

        assertEquals(0, winnerVolBefore.compareTo(perpVolumeOf(api, winner)),
            "IF 足够时不应触发 ADL，盈利方仓位应保留");
        assertGlobalConserved("after futures liquidation via IF");
    }

    @Test
    @Order(6)
    void ifDepleted_cascadeReachesAdl() throws Exception {
        log("=== IF 耗尽 → ADL 摊派给盈利方 ===");
        ok(api.adjustMarkPrice(btcUsdtPerp, btcPrice), "markPrice 复位");
        drainPerpIf();
        awaitApplied();

        ok(api.adjustUserBalance(trader, nextTxId(), USDT_ID, +5_000.0), "trader 补仓资金");
        openPerp(trader, winner, PERP_SIZE);
        BigDecimal winnerVolBefore = perpVolumeOf(api, winner);

        ok(api.adjustMarkPrice(btcUsdtPerp, btcPrice * 0.85), "markPrice -15%");

        // 本用例的命题是"级联走到 ADL"，判据就是盈利方被摊派——不要求 victim 完全平掉：
        // IF 未必一次抽干净，残额会吸收一部分，剩余敞口要好几轮扫描才归零，
        // 那是节奏问题，跟"有没有走到 ADL"无关。
        BigDecimal winnerVolAfter = winnerVolBefore;
        long deadline = System.currentTimeMillis() + 120_000L;
        while (System.currentTimeMillis() < deadline) {
            winnerVolAfter = perpVolumeOf(api, winner);
            if (winnerVolAfter.abs().compareTo(winnerVolBefore.abs()) < 0) {
                break;
            }
            TimeUnit.MILLISECONDS.sleep(1000L);
        }
        log("  winner 仓位 " + winnerVolBefore + " → " + winnerVolAfter);
        assertTrue(winnerVolAfter.abs().compareTo(winnerVolBefore.abs()) < 0,
            "IF 抽干后级联应走到 ADL，盈利方敞口须被摊派减少："
                + winnerVolBefore + " → " + winnerVolAfter);
        assertGlobalConserved("after ADL");
    }

    @Test
    @Order(7)
    void bnbCrash_liquidatesLoan_futuresUnaffected() throws Exception {
        log("=== BNB 暴跌 → 借贷强平；同账户的期货仓位不受牵连 ===");
        ok(api.adjustMarkPrice(btcUsdtPerp, btcPrice), "BTC markPrice 复位");
        ok(api.insuranceFundDeposit(btcUsdtPerp, 0, 1_000_000.0), "IF 补回");
        ok(api.adjustUserBalance(trader, nextTxId(), USDT_ID, +5_000.0), "trader 补仓资金");
        openPerp(trader, winner, PERP_SIZE);
        BigDecimal perpVolBefore = perpVolumeOf(api, trader);
        assertTrue(perpVolBefore.compareTo(BigDecimal.ZERO) != 0, "应先有期货仓位");

        // lp 在低价挂 BID 接强平卖单，让抵押能真卖掉
        ok(api.placeOrder(lp, nextOrderId(), bnbUsdtSpot, OrderAction.BID, OrderType.GTC,
            bnbPrice * 0.4, bnbPrice * 0.4, 60.0, MarginMode.ISOLATED, 1, false), "lp 低价挂 BID 接盘");

        // 抵押腰斩：50 BNB 从 600 跌到 300，债 10000 → LTV 从 33% 冲到 67%，越强平线
        // 强平线 8000bps：10000 债 ÷ 80% = 12500 抵押上限 → 50 BNB 单价须跌破 250
        ok(api.adjustMarkPrice(bnbUsdtSpot, bnbPrice * 0.3), "BNB 跌到三折，越 80% 强平线");
        awaitLoanClosed(trader, 7001L, 30);

        assertEquals(0, perpVolBefore.compareTo(perpVolumeOf(api, trader)),
            "借贷强平不应动到期货仓位——两域记账必须隔离");
        assertGlobalConserved("after loan liquidation");
    }

    @Test
    @Order(8)
    void reopenAfterLiquidation_noStaleContext() throws Exception {
        log("=== 强平后重开仓：上一轮上下文必须已清干净 ===");
        ok(api.adjustMarkPrice(btcUsdtPerp, btcPrice), "markPrice 复位");
        ok(api.adjustMarkPrice(bnbUsdtSpot, bnbPrice), "BNB markPrice 复位");
        ok(api.adjustUserBalance(trader, nextTxId(), USDT_ID, +5_000.0), "trader 补仓资金");

        openPerp(trader, winner, PERP_SIZE);
        assertTrue(perpVolumeOf(api, trader).compareTo(BigDecimal.ZERO) != 0, "应能重新建仓");

        ok(api.adjustMarkPrice(btcUsdtPerp, btcPrice * 0.85), "再次砸价");
        awaitPerpFlat(trader, 30);
        log("✓ 可重复强平，无残留上下文");
        assertGlobalConserved("after reopen and re-liquidate");
    }

    // ================================================================
    // 四、收尾与一致性
    // ================================================================

    @Test
    @Order(9)
    void withdrawal_blockedBy_combinedThreeDomainClaims() throws Exception {
        log("=== 提现受三域合计占用约束：calculateLocked 必须把三方都算进去 ===");
        // 重新在三个域各压一笔占用
        ok(api.adjustMarkPrice(btcUsdtPerp, btcPrice), "markPrice 复位");
        ok(api.adjustUserBalance(trader, nextTxId(), USDT_ID, +20_000.0), "trader 补资金");
        long resting = nextOrderId();
        ok(api.placeOrder(trader, resting, btcUsdtSpot, OrderAction.BID, OrderType.GTC,
            btcPrice * 0.5, btcPrice * 0.5, 1.0, MarginMode.ISOLATED, 1, false), "现货挂单占 USDT");
        openPerp(trader, winner, PERP_SIZE);
        awaitApplied();

        // accounts 是含冻结的总额，全额提走必然踩到三域占用之和
        BigDecimal gross = balanceOf(trader, USDT_ID);
        var rejected = api.adjustUserBalance(trader, nextTxId(), USDT_ID, -gross.doubleValue());
        assertEquals(CommandResultCode.RISK_NSF, rejected.getResultCode(),
            "全额提现应被三域合计占用挡住，实际 " + rejected.getResultCode());

        ok(api.cancelOrder(trader, resting, btcUsdtSpot), "收尾撤单");
        assertGlobalConserved("after combined-claim withdrawal check");
    }

    @Test
    @Order(10)
    void spotFrozenBnb_cannotBePledgedAsCollateral() throws Exception {
        log("=== 反方向：BNB 先被现货挂单冻结，再拿去抵押应被拒 ===");
        log("    （@Order(3) 测的是先抵押后卖出，这里补另一半）");
        ok(api.adjustMarkPrice(bnbUsdtSpot, bnbPrice), "BNB markPrice 复位");
        // 用专属用户：trader 的 BNB 有一部分已作抵押，"自由额"不确定，
        // 挂卖全额会先撞 RISK_NSF，测到的就不是"冻结后不可抵押"这件事了
        long pledgeUser = uid(4);
        ok(api.addUser(pledgeUser), "addUser pledgeUser");
        ok(api.adjustUserBalance(pledgeUser, nextTxId(), BNB_ID, +100.0), "pledgeUser +100 BNB");
        awaitApplied();

        // 高价挂卖单：不会成交，但把这 100 BNB 全额冻在 exchangeLocked 里
        long sellAll = nextOrderId();
        ok(api.placeOrder(pledgeUser, sellAll, bnbUsdtSpot, OrderAction.ASK, OrderType.GTC,
            bnbPrice * 3, 0.0, 100.0, MarginMode.ISOLATED, 1, false), "现货挂卖全部 100 BNB");
        awaitApplied();

        var rejected = api.loanCreate(pledgeUser, nextTxId(), bnbUsdtSpot, 100.0, 100.0);
        assertTrue(rejected.getResultCode() != CommandResultCode.SUCCESS,
            "已被现货冻结的 BNB 不应还能抵押，实际 " + rejected.getResultCode());

        // 收尾撤单不做断言：这单是否还在簿上取决于前序用例留下的对手盘，与本用例的结论无关
        api.cancelOrder(pledgeUser, sellAll, bnbUsdtSpot);
        assertGlobalConserved("after spot-freeze vs collateral check");
    }

    @Test
    @Order(11)
    void simultaneousCrash_futuresAndLoanLiquidateTogether() throws Exception {
        log("=== 两个域同时崩：期货强平与借贷强平并发发生在同一账户 ===");
        ok(api.adjustMarkPrice(btcUsdtPerp, btcPrice), "BTC 复位");
        ok(api.adjustMarkPrice(bnbUsdtSpot, bnbPrice), "BNB 复位");
        for (int shard = 0; shard < RISK_SHARDS; shard++) {
            ok(api.insuranceFundDeposit(btcUsdtPerp, shard, 1_000_000.0), "IF 补足 shard" + shard);
        }
        ok(api.adjustUserBalance(trader, nextTxId(), USDT_ID, +20_000.0), "trader 补资金");
        ok(api.adjustUserBalance(trader, nextTxId(), BNB_ID, +100.0), "补充 BNB 抵押品");

        // 同一账户上同时压期货仓位与借贷负债
        openPerp(trader, winner, PERP_SIZE);
        long loanId = nextTxId();
        ok(api.loanCreate(trader, loanId, bnbUsdtSpot, 50.0, 10_000.0), "借贷：50 BNB → 10000 USDT");
        ok(api.placeOrder(lp, nextOrderId(), bnbUsdtSpot, OrderAction.BID, OrderType.GTC,
            bnbPrice * 0.4, bnbPrice * 0.4, 60.0, MarginMode.ISOLATED, 1, false), "lp 挂 BID 接强平卖单");
        awaitApplied();
        assertTrue(perpVolumeOf(api, trader).compareTo(BigDecimal.ZERO) != 0, "前置：应有期货仓位");

        // 两个 symbol 的 markPrice 连续下发，两个域的 scanner 会在同一账户上同时动手
        ok(api.adjustMarkPrice(btcUsdtPerp, btcPrice * 0.85), "BTC -15%");
        ok(api.adjustMarkPrice(bnbUsdtSpot, bnbPrice * 0.3), "BNB 跌到三折");

        awaitPerpFlat(trader, 30);
        awaitLoanClosed(trader, loanId, 30);
        // 两域同时强平最容易出的错是重复扣款或漏扣，守恒是唯一能兜住的判据
        assertGlobalConserved("after simultaneous dual-domain liquidation");
    }

    @Test
    @Order(12)
    void suspendUser_withThreeDomainExposure_rejected() throws Exception {
        log("=== 三域都有敞口时不得暂停用户 ===");
        ok(api.adjustMarkPrice(btcUsdtPerp, btcPrice), "markPrice 复位");
        ok(api.adjustUserBalance(trader, nextTxId(), USDT_ID, +20_000.0), "trader 补资金");
        openPerp(trader, winner, PERP_SIZE);
        awaitApplied();

        var rejected = api.suspendUser(trader);
        assertTrue(rejected.getResultCode() == CommandResultCode.USER_MGMT_USER_NOT_SUSPENDABLE_HAS_POSITIONS
                || rejected.getResultCode() == CommandResultCode.USER_MGMT_USER_NOT_SUSPENDABLE_NON_EMPTY_ACCOUNTS,
            "有持仓/余额时暂停应被拒，实际 " + rejected.getResultCode());
    }

    @Test
    @Order(13)
    void unwind_allDomains_conservationHolds() throws Exception {
        log("=== 三域全部平掉 ===");
        ok(api.adjustMarkPrice(btcUsdtPerp, btcPrice), "markPrice 复位");
        ok(api.loanCrossRepay(trader, 7002L, USDT_ID, 0), "Cross 还清");
        ok(api.loanCrossWithdrawCollateral(trader, LTC_ID, 400.0), "撤回 LTC 抵押");
        awaitApplied();

        var report = api.queryUserReport(trader).get(10, TimeUnit.SECONDS);
        assertTrue(report.getCrossLoans().isEmpty(), "Cross 贷款应已关闭");
        assertTrue(report.getIsolatedLoans().isEmpty(), "Isolated 贷款已在强平中关闭");
        assertGlobalConserved("after full unwind");
    }

    @Test
    @Order(14)
    void allDomains_convergedOnThreeNodes() throws Exception {
        log("=== 三节点收敛：余额 / 持仓 / 贷款 ===");
        List<String[]> nodes = resolveAllNodes();
        assertTrue(nodes.size() >= 3, "应至少 3 节点");

        BigDecimal expectedUsdt = null;
        BigDecimal expectedVol = null;
        Integer expectedLoans = null;
        for (String[] node : nodes) {
            try (ExchangeApi nodeApi = ExchangeApi.connect(node[0], Integer.parseInt(node[1]),
                ExchangeApiOptions.builder().sendTimeout(java.time.Duration.ofSeconds(10)).build())) {
                var rep = nodeApi.queryUserReport(trader).get(10, TimeUnit.SECONDS);
                BigDecimal usdt = rep.getAccounts().getOrDefault(USDT_ID, BigDecimal.ZERO);
                BigDecimal vol = perpVolumeOf(nodeApi, trader);
                int loans = rep.getIsolatedLoans().size() + rep.getCrossLoans().size();
                log("  node " + node[0] + ":" + node[1] + "(" + node[2] + ") usdt=" + usdt
                    + " perpVol=" + vol + " loans=" + loans);
                if (expectedUsdt == null) {
                    expectedUsdt = usdt;
                    expectedVol = vol;
                    expectedLoans = loans;
                } else {
                    assertEquals(0, expectedUsdt.compareTo(usdt), "各节点 USDT 余额应一致");
                    assertEquals(0, expectedVol.compareTo(vol), "各节点持仓应一致");
                    assertEquals(expectedLoans, loans, "各节点贷款数应一致");
                }
            }
        }
        log("✓ 统一账户全流程通过");
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 与现货同币对的永续：symbolId 必须另给（一个 symbolId 只能有一个 type）。 */
    private void addPerpOnSameCurrencies(int symbol, String label) {
        addFuturesSymbolSpec(symbol, SymbolType.FUTURES_CONTRACT_PERPETUAL, BTC_ID, USDT_ID, label);
    }

    /** longSide 开多、shortSide 开空，撮合后双方各持一边。 */
    private void openPerp(long longSide, long shortSide, double size) throws Exception {
        ok(api.placeOrder(longSide, nextOrderId(), btcUsdtPerp, OrderAction.BID, OrderType.GTC,
            btcPrice, 0.0, size, MarginMode.ISOLATED, LEVERAGE, false), "开多");
        ok(api.placeOrder(shortSide, nextOrderId(), btcUsdtPerp, OrderAction.ASK, OrderType.GTC,
            btcPrice, 0.0, size, MarginMode.ISOLATED, LEVERAGE, false), "开空");
        awaitApplied();
    }

    private BigDecimal balanceOf(long uid, int currency) throws Exception {
        BigDecimal v = api.queryUserReport(uid).get(10, TimeUnit.SECONDS).getAccounts().get(currency);
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal perpVolumeOf(ExchangeApi client, long uid) throws Exception {
        var positions = client.queryUserReport(uid).get(10, TimeUnit.SECONDS).getPositions().get(btcUsdtPerp);
        return (positions == null || positions.isEmpty()) ? BigDecimal.ZERO : positions.get(0).getOpenVolume();
    }

    /** 强平由 scanner 异步多轮推进，只能轮询——固定 sleep 要么误判要么拖慢。 */
    private void awaitPerpFlat(long uid, int timeoutSec) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (perpVolumeOf(api, uid).compareTo(BigDecimal.ZERO) == 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(500L);
        }
        throw new AssertionError("期货仓位在 " + timeoutSec + "s 内未被强平：uid=" + uid);
    }

    private void awaitLoanClosed(long uid, long loanId, int timeoutSec) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            boolean open = api.queryUserReport(uid).get(10, TimeUnit.SECONDS).getIsolatedLoans().stream()
                .anyMatch(l -> l.getLoanId() == loanId);
            if (!open) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(500L);
        }
        throw new AssertionError("贷款 " + loanId + " 在 " + timeoutSec + "s 内未被强平关闭");
    }

    /**
     * 全局守恒：八个资金桶之和恒为零。
     *
     * <p>八个桶缺一不可——少加一个桶，那个桶里的错账就永远抓不到。尤其 loanBalances（贷款池 +
     * 利息收入 + LIF）与 ifBalances（期货保险基金）：混合测试的价值正是跨域记账，漏掉它们
     * 等于把要测的东西排除在外。
     */
    /** 按各 shard 实际可用额抽干期货 IF——写死金额会因分片余额不同撞 RISK_IF_INSUFFICIENT。 */
    private void drainPerpIf() {
        api.queryInsuranceFundReport().join().getByShardMap().forEach((shard, perShard) -> {
            var e = perShard.getFuturesInsuranceFundMap().get(btcUsdtPerp);
            if (e != null && e.getAvailable() > 0) {
                ok(api.insuranceFundWithdraw(btcUsdtPerp, shard, e.getAvailable() / 1_000_000.0),
                    "抽干 IF shard" + shard);
            }
        });
    }

    private void assertGlobalConserved(String stage) {
        var report = api.queryTotalCurrencyBalanceReport().join();
        long sum = 0;
        for (var bucket : List.of(report.getAccountBalancesMap(), report.getExtraMarginMap(),
            report.getExchangeLockedMap(), report.getLoanBalancesMap(), report.getFeesMap(),
            report.getAdjustmentsMap(), report.getSuspendsMap(), report.getIfBalancesMap())) {
            for (long v : bucket.values()) {
                sum += v;
            }
        }
        assertEquals(0L, sum, "全局守恒被破坏 @" + stage);
    }

    private void awaitApplied() throws Exception {
        TimeUnit.MILLISECONDS.sleep(300L);
    }
}
