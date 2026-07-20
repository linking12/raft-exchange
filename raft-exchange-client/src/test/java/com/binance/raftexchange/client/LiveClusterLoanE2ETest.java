package com.binance.raftexchange.client;

import com.binance.raftexchange.stubs.MarginMode;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.report.InsuranceFundReportResult;
import com.binance.raftexchange.stubs.report.LoanPlatformReportResult;
import com.binance.raftexchange.stubs.request.SpotLoanConfig;
import com.binance.raftexchange.stubs.request.SpotLoanGlobalConfig;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.util.List;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 现货借贷（loan）在真集群上的端到端验证——此前 loan 全部测试都在 exchange-core 进程内，
 * raft 复制 / 换届 / 快照恢复这条路上从没跑过。
 *
 * <p>覆盖顺序即业务顺序：配置下发 → Isolated 全流程 → Cross 全流程 → 运营充提 → 强平与 LIF 接管 → 报表回读。
 * 每个阶段后校验全局守恒（{@code TOTAL_CURRENCY_BALANCE} 全零），这是唯一能跨 shard 发现记账错的手段。
 *
 * <p>抵押用 BNB（digit 8）、借款用 USDT（digit 6），两者精度不同——若 client 的 scale 换算取错币种，
 * 金额会差若干个数量级，第一个断言就会失败。
 */
@EnabledIfSystemProperty(named = "livecluster", matches = "true") // 需本地三节点集群；@Disabled 在本模块被 junit-platform.properties 关掉了，用系统属性 gate
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveClusterLoanE2ETest extends LiveClusterE2EBase {

    /** 抵押品基准价：砸价类用例结束后必须复位到这里，否则后续用例沿用崩盘价，LTV 全线超标。 */
    private static final double BNB_BASE_PRICE = 600.0;
    private static final double LTC_BASE_PRICE = 100.0;

    private long borrower;
    private long lp;          // 强平时在簿上挂 BID 接盘
    private int bnbUsdt;      // Isolated 抵押 BNB 借 USDT
    private int ltcUsdt;      // Cross 抵押 LTC（digit 2，压 scale 换算）
    private final long isolatedLoanId = 4001L;
    private final long crossLoanId = 4002L;

    @Test
    @Order(1)
    void setup_currencies_symbols_users() {
        log("=== loan E2E setup ===");
        ensureAllCurrencies();

        borrower = uid(1);
        lp = uid(2);
        bnbUsdt = SHARED_BNB_USDT;
        ltcUsdt = SHARED_LTC_USDT;

        ok(api.addUser(borrower), "addUser borrower=" + borrower);
        ok(api.addUser(lp), "addUser lp=" + lp);

        addSpotSymbol(bnbUsdt, BNB_ID, USDT_ID, "BNB/USDT");
        addSpotSymbol(ltcUsdt, LTC_ID, USDT_ID, "LTC/USDT");

        // borrower 持抵押品；lp 持 USDT 用于强平时接盘
        ok(api.adjustUserBalance(borrower, nextTxId(), BNB_ID, +100.0), "borrower +100 BNB");
        ok(api.adjustUserBalance(borrower, nextTxId(), LTC_ID, +500.0), "borrower +500 LTC");
        ok(api.adjustUserBalance(lp, nextTxId(), USDT_ID, +200_000.0), "lp +200000 USDT");

        // 基准价走真实成交：现货 markPrice 由本所成交维护（LastPriceCacheRecord.applyTradePrice），
        // 用 adjustMarkPrice 设价只是管理覆盖，走不到生产路径。首笔成交时 markPrice<=0，直接采纳成交价。
        seedPriceByTrade(bnbUsdt, BNB_BASE_PRICE, 1.0);
        seedPriceByTrade(ltcUsdt, LTC_BASE_PRICE, 1.0);
    }

    /**
     * 用一笔真实成交把 symbol 的 markPrice 立起来。
     *
     * <p>后续「砸价」仍只能用 {@code adjustMarkPrice}：平滑窗口 15s，而测试在毫秒内连续成交，
     * 新价权重只有 {@code dt/WINDOW ≈ 0.01%}，价格几乎不动——那条路径无法在 E2E 时间尺度上模拟。
     */
    private void seedPriceByTrade(int symbol, double price, double size) {
        // 卖方用 borrower（持 base 币）、买方用 lp（持 USDT）；成交量取小，不影响后续抵押余额
        ok(api.placeOrder(borrower, nextOrderId(), symbol, OrderAction.ASK, OrderType.GTC,
            price, 0.0, size, MarginMode.ISOLATED, 1, false), "建价 ASK @" + price);
        ok(api.placeOrder(lp, nextOrderId(), symbol, OrderAction.BID, OrderType.GTC,
            price, price, size, MarginMode.ISOLATED, 1, false), "建价 BID @" + price);
    }

    @Test
    @Order(2)
    void configure_loan_markets_and_fund_pool() {
        // 两个 pair 都开放借贷；阈值走全局缓冲派生（proto 只带 symbolId + initialLtv）
        // numeraire 是全局配置，Cross 借款/撤抵押 fail-closed 依赖它；各测试都设 USDT，取值一致故互不干扰
        ok(api.addLoanConfig(SpotLoanGlobalConfig.newBuilder().setNumeraireCcy(USDT_ID).build(),
            null, null), "配置 numeraire = USDT");
        ok(api.addLoanConfig(null, SpotLoanConfig.newBuilder()
            .setSymbolId(bnbUsdt).setLoanInitialLtvBps(6000).build(), null), "enable loan BNB/USDT");
        ok(api.addLoanConfig(null, SpotLoanConfig.newBuilder()
            .setSymbolId(ltcUsdt).setLoanInitialLtvBps(6000).build(), null), "enable loan LTC/USDT");

        // 借贷池注资：三个 shard 各注一份，借款才可能落到任意 shard 的用户
        for (int shard = 0; shard < RISK_SHARDS; shard++) {
            ok(api.poolDeposit(shard, USDT_ID, 500_000.0), "poolDeposit shard" + shard + " 500000 USDT");
        }
        assertGlobalConserved("after pool funding");
    }

    @Test
    @Order(3)
    void isolated_loan_fullLifecycle() throws Exception {
        // 10 BNB 抵押（digit 8）借 1000 USDT（digit 6）——两种精度在同一条命令里
        ok(api.loanCreate(borrower, isolatedLoanId, bnbUsdt, 10.0, 1000.0), "loanCreate 10 BNB → 1000 USDT");
        awaitApplied();

        var report = api.queryUserReport(borrower).join();
        assertNotNull(report, "borrower 报表可读");
        assertGlobalConserved("after isolated create");

        ok(api.loanAddCollateral(borrower, isolatedLoanId, bnbUsdt, 5.0), "addCollateral +5 BNB");
        ok(api.loanReleaseCollateral(borrower, isolatedLoanId, bnbUsdt, 2.0), "releaseCollateral -2 BNB");
        assertGlobalConserved("after collateral adjust");

        ok(api.loanRepay(borrower, isolatedLoanId, bnbUsdt, 400.0), "partial repay 400 USDT");
        ok(api.loanRepay(borrower, isolatedLoanId, bnbUsdt, 0), "payoff 剩余本息");
        awaitApplied();
        assertGlobalConserved("after isolated payoff");
    }

    @Test
    @Order(4)
    void cross_loan_fullLifecycle() throws Exception {
        // LTC digit=2，与 baseScaleK 不等；任何 scale 误用都会让抵押量偏若干数量级
        ok(api.loanCrossAddCollateral(borrower, LTC_ID, 300.0), "cross +300 LTC 抵押");
        assertGlobalConserved("after cross add collateral");

        ok(api.loanCrossBorrow(borrower, crossLoanId, ltcUsdt, 500.0), "cross borrow 500 USDT");
        awaitApplied();
        assertGlobalConserved("after cross borrow");

        ok(api.loanCrossRepay(borrower, crossLoanId, USDT_ID, 0), "cross payoff");
        ok(api.loanCrossWithdrawCollateral(borrower, LTC_ID, 300.0), "cross 撤回 300 LTC");
        awaitApplied();
        assertGlobalConserved("after cross payoff");
    }

    @Test
    @Order(5)
    void lif_deposit_and_withdraw() throws Exception {
        ok(api.loanIfDeposit(0, USDT_ID, 50_000.0), "LIF 垫资 50000 USDT @shard0");
        awaitApplied();

        InsuranceFundReportResult before = api.queryInsuranceFundReport().join();
        long lifBefore = before.getByShardMap().get(0).getLoanInsuranceFundMap().getOrDefault(USDT_ID, 0L);
        assertTrue(lifBefore > 0, "LIF 注资后应为正，实际 " + lifBefore);

        ok(api.loanIfWithdraw(0, USDT_ID, 20_000.0), "LIF 提取 20000 USDT");
        awaitApplied();

        long lifAfter = api.queryInsuranceFundReport().join()
            .getByShardMap().get(0).getLoanInsuranceFundMap().getOrDefault(USDT_ID, 0L);
        assertTrue(lifAfter < lifBefore, "提取后 LIF 应减少");
        assertGlobalConserved("after LIF ops");
    }

    @Test
    @Order(6)
    void liquidation_marketAbsorbs_atBankruptcyPrice() throws Exception {
        long loanId = 4100L;
        // 抵押 20 BNB 借 1000 USDT；markPrice 100 → LTV 50%，安全
        ok(api.adjustMarkPrice(bnbUsdt, 100.0), "markPrice BNB/USDT = 100");
        ok(api.loanCreate(borrower, loanId, bnbUsdt, 20.0, 1000.0), "loanCreate 20 BNB → 1000 USDT");
        awaitApplied();

        // lp 在低价挂 BID 接盘，保证强平单能成交
        ok(api.placeOrder(lp, nextOrderId(), bnbUsdt, OrderAction.BID, OrderType.GTC,
            40.0, 40.0, 20.0, MarginMode.ISOLATED, 1, false), "lp BID 20 BNB @40");

        // 砸价：markPrice 60 → LTV = 1000/(20×60) = 83% 越强平线，破产价 = 0.83×60 ≈ 50，簿上 40 的买盘接不满
        ok(api.adjustMarkPrice(bnbUsdt, 60.0), "markPrice 暴跌到 60");

        // scanner 每 tick 扫，等它出手
        awaitLoanClosed(borrower, loanId, 30);
        assertGlobalConserved("after liquidation");
        ok(api.adjustMarkPrice(bnbUsdt, BNB_BASE_PRICE), "BNB markPrice 复位");
    }

    @Test
    @Order(7)
    void liquidation_noBid_fallsBackToLifTakeover() throws Exception {
        long loanId = 4200L;
        ok(api.adjustMarkPrice(ltcUsdt, 100.0), "markPrice LTC/USDT = 100");
        ok(api.loanCreate(borrower, loanId, ltcUsdt, 100.0, 5000.0), "loanCreate 100 LTC → 5000 USDT");
        awaitApplied();

        long lifBefore = lifBalance(USDT_ID);

        // 簿上完全没有买盘 → 强平单全拒 → LIF 按债务全额承接
        ok(api.adjustMarkPrice(ltcUsdt, 55.0), "markPrice 暴跌到 55，无人接盘");

        awaitLoanClosed(borrower, loanId, 30);
        long lifAfter = lifBalance(USDT_ID);
        assertTrue(lifAfter < lifBefore,
            "无人接盘时 LIF 应垫付债务（转负方向），before=" + lifBefore + " after=" + lifAfter);
        assertGlobalConserved("after LIF takeover");
        ok(api.adjustMarkPrice(ltcUsdt, LTC_BASE_PRICE), "LTC markPrice 复位");
    }

    @Test
    @Order(8)
    void guards_ltvTooHigh_and_insufficientCollateral() throws Exception {
        log("=== 拒绝路径：LTV 超线 / 抵押不足 ===");
        // initialLtv 6000 → 10 BNB @600 = 6000 USDT 估值，最多借 3600；借 5000 必拒
        var overLtv = api.loanCreate(borrower, 4300L, bnbUsdt, 10.0, 5_000.0);
        assertEquals(CommandResultCode.LOAN_LTV_TOO_HIGH, overLtv.getResultCode(), "超 initialLtv 应被拒");

        // 账户里没有那么多 BNB
        var noCollateral = api.loanCreate(borrower, 4301L, bnbUsdt, 1_000_000.0, 1.0);
        assertEquals(CommandResultCode.LOAN_COLLATERAL_INSUFFICIENT, noCollateral.getResultCode(),
            "抵押不足应被拒");
        assertGlobalConserved("after guard rejections");
    }

    @Test
    @Order(9)
    void releaseCollateral_rejectedWhenItWouldCrossLiquidationLine() throws Exception {
        log("=== 减抵押不得撤到强平线以下 ===");
        long loanId = 4400L;
        ok(api.loanCreate(borrower, loanId, bnbUsdt, 20.0, 6_000.0), "建仓 20 BNB → 6000 USDT");
        awaitApplied();

        // 撤走绝大部分抵押会让 LTV 冲破强平线
        var rejected = api.loanReleaseCollateral(borrower, loanId, bnbUsdt, 18.0);
        assertEquals(CommandResultCode.LOAN_LTV_TOO_HIGH_AFTER_RELEASE, rejected.getResultCode(),
            "撤后 LTV 超强平线应被拒");

        // 小额撤回在安全范围内应放行
        ok(api.loanReleaseCollateral(borrower, loanId, bnbUsdt, 1.0), "小额撤回应放行");
        ok(api.loanRepay(borrower, loanId, bnbUsdt, 0), "还清收尾");
        awaitApplied();
        assertGlobalConserved("after release guard");
    }

    @Test
    @Order(10)
    void crossLoan_multiCurrencyCollateral_accountLevelLtv() throws Exception {
        log("=== Cross 多币种抵押：账户级 LTV 汇总 ===");
        ok(api.loanCrossAddCollateral(borrower, BNB_ID, 10.0), "Cross +10 BNB");
        ok(api.loanCrossAddCollateral(borrower, LTC_ID, 200.0), "Cross +200 LTC");
        ok(api.loanCrossBorrow(borrower, 4500L, bnbUsdt, 3_000.0), "Cross 借 3000 USDT");
        awaitApplied();

        var report = api.queryUserReport(borrower).get(10, TimeUnit.SECONDS);
        assertEquals(2, report.getCrossLoanCollateral().size(), "抵押池应含两个币种");
        assertTrue(report.getCrossAccountLtvBps() > 0, "配了 numeraire 后账户级 LTV 应可算出");

        ok(api.loanCrossRepay(borrower, 4500L, USDT_ID, 0), "还清");
        ok(api.loanCrossWithdrawCollateral(borrower, BNB_ID, 10.0), "撤回 BNB");
        ok(api.loanCrossWithdrawCollateral(borrower, LTC_ID, 200.0), "撤回 LTC");
        awaitApplied();
        assertGlobalConserved("after multi-currency cross");
    }

    @Test
    @Order(11)
    void interestAccrues_andRepaymentSplitsInterestFirst() throws Exception {
        log("=== 计息与还款分账：利息优先 ===");
        long loanId = 4600L;
        ok(api.loanCreate(borrower, loanId, bnbUsdt, 20.0, 3_000.0), "建仓");
        awaitApplied();

        var opened = loanOf(borrower, loanId);
        assertNotNull(opened, "贷款应存在");
        // displayInterest 含未落账 pending，随时间单调增长
        TimeUnit.SECONDS.sleep(2);
        var later = loanOf(borrower, loanId);
        assertTrue(later.getDisplayInterest().compareTo(BigDecimal.ZERO) >= 0, "displayInterest 不应为负");

        // 小额还款先冲利息，本金基本不动
        ok(api.loanRepay(borrower, loanId, bnbUsdt, 1.0), "还 1 USDT，应先冲利息");
        awaitApplied();
        var afterPartial = loanOf(borrower, loanId);
        assertTrue(afterPartial.getOutstandingPrincipal().compareTo(new BigDecimal("2999")) > 0,
            "小额还款应主要冲利息，本金几乎不减");

        ok(api.loanRepay(borrower, loanId, bnbUsdt, 0), "payoff");
        awaitApplied();
        assertGlobalConserved("after interest settlement");
    }

    @Test
    @Order(12)
    void disableMarket_blocksNewLoans_butKeepsExisting() throws Exception {
        log("=== 停借：只关新开仓，不动存量 ===");
        long keepAlive = 4700L;
        ok(api.loanCreate(borrower, keepAlive, ltcUsdt, 100.0, 2_000.0), "停借前先建一笔");
        awaitApplied();

        // initialLtvBps=0 即停借；其余风控参数须保持，否则存量会被连带强平
        ok(api.addLoanConfig(null, SpotLoanConfig.newBuilder()
            .setSymbolId(ltcUsdt).setLoanInitialLtvBps(0).build(), null), "停借 LTC/USDT");
        awaitApplied();

        var blocked = api.loanCreate(borrower, 4701L, ltcUsdt, 100.0, 1_000.0);
        assertEquals(CommandResultCode.LOAN_NOT_ENABLED, blocked.getResultCode(), "停借后新贷款应被拒");
        assertNotNull(loanOf(borrower, keepAlive), "存量贷款不应被连带清掉");

        ok(api.loanRepay(borrower, keepAlive, ltcUsdt, 0), "存量仍可正常还清");
        // 恢复开放，后续用例不受影响
        ok(api.addLoanConfig(null, SpotLoanConfig.newBuilder()
            .setSymbolId(ltcUsdt).setLoanInitialLtvBps(6000).build(), null), "恢复开放");
        awaitApplied();
        assertGlobalConserved("after disable/enable market");
    }

    @Test
    @Order(13)
    void loanState_survivesOnAllThreeNodes() throws Exception {
        log("=== loan 状态在三节点一致 ===");
        long loanId = 4800L;
        ok(api.loanCreate(borrower, loanId, bnbUsdt, 15.0, 4_000.0), "建仓供跨节点比对");
        awaitApplied();

        List<String[]> nodes = resolveAllNodes();
        BigDecimal expected = null;
        for (String[] node : nodes) {
            try (ExchangeApi nodeApi = ExchangeApi.connect(node[0], Integer.parseInt(node[1]),
                ExchangeApiOptions.builder().sendTimeout(java.time.Duration.ofSeconds(10)).build())) {
                var loan = nodeApi.queryUserReport(borrower).get(10, TimeUnit.SECONDS)
                    .getIsolatedLoans().stream().filter(l -> l.getLoanId() == loanId).findFirst().orElse(null);
                assertNotNull(loan, "各节点都应看到该贷款：" + node[0] + ":" + node[1]);
                log("  node " + node[0] + ":" + node[1] + "(" + node[2] + ") principal="
                    + loan.getOutstandingPrincipal());
                if (expected == null) {
                    expected = loan.getOutstandingPrincipal();
                } else {
                    assertEquals(0, expected.compareTo(loan.getOutstandingPrincipal()), "各节点本金应一致");
                }
            }
        }
        ok(api.loanRepay(borrower, loanId, bnbUsdt, 0), "还清收尾");
        awaitApplied();
        assertGlobalConserved("after cross-node loan check");
    }

    @Test
    @Order(14)
    void crossLoan_liquidation_partialDeleverage() throws Exception {
        log("=== Cross 强平：渐进去杠杆，卖一个币还一笔债 ===");
        long loanId = 4900L;
        // Cross 是账户级 LTV：与前 13 个用例共用 borrower 时，抵押池和残债都不确定，
        // 借款额离上限稍近就会被前序状态顶过线。专属用户才能让这里的算术是确定的。
        long xUser = uid(10);
        ok(api.addUser(xUser), "addUser xUser");
        ok(api.adjustMarkPrice(bnbUsdt, 600.0), "BNB markPrice 600");
        ok(api.adjustUserBalance(xUser, nextTxId(), BNB_ID, +100.0), "xUser +100 BNB");
        ok(api.loanCrossAddCollateral(xUser, BNB_ID, 50.0), "Cross +50 BNB（市值 30000）");
        // 上限 = 市值 × weight(60%) × initialLtv(60%) = 市值的 36% → 10800；取 4000 留足余量
        ok(api.loanCrossBorrow(xUser, loanId, bnbUsdt, 4_000.0), "Cross 借 4000 USDT");
        awaitApplied();

        // lp 挂低价 BID 接强平卖单
        ok(api.placeOrder(lp, nextOrderId(), bnbUsdt, OrderAction.BID, OrderType.GTC,
            200.0, 200.0, 30.0, MarginMode.ISOLATED, 1, false), "lp 低价挂 BID 接盘");

        // BNB 跌到 450：抵押 9000、加权后更低，账户 LTV 越强平线
        // 强平线 8000bps（initialLtv 6000 + 缓冲 2000），Cross 抵押还要先打 weight 60%：
        // 4000 债 ÷ 80% = 5000 加权上限 → 市值上限 8333 → 20 BNB 单价须跌破 416
        // 强平线 8000bps：4000 债 ÷ 80% = 5000 加权上限 → 市值上限 8333 → 50 BNB 单价须跌破 166
        ok(api.adjustMarkPrice(bnbUsdt, 140.0), "BNB 跌到 140，加权 LTV 越 80% 线");
        awaitCrossLoanClosed(xUser, loanId, 30);

        var report = api.queryUserReport(xUser).get(10, TimeUnit.SECONDS);
        log("  强平后 Cross 抵押池=" + report.getCrossLoanCollateral() + " 贷款数=" + report.getCrossLoans().size());
        assertGlobalConserved("after cross liquidation");
        ok(api.adjustMarkPrice(bnbUsdt, BNB_BASE_PRICE), "BNB markPrice 复位");
    }

    @Test
    @Order(15)
    void crossLoan_noBid_lifTakesOverAndGoesNegative() throws Exception {
        log("=== Cross 无人接盘 → LIF 承接，且 LIF 转负 ===");
        long loanId = 4950L;
        // 同上：账户级 LTV 必须跑在专属用户上才确定
        long xUser = uid(11);
        ok(api.addUser(xUser), "addUser xUser");
        ok(api.adjustMarkPrice(ltcUsdt, 100.0), "LTC markPrice 100");
        ok(api.adjustUserBalance(xUser, nextTxId(), LTC_ID, +500.0), "xUser +500 LTC");

        ok(api.loanCrossAddCollateral(xUser, LTC_ID, 400.0), "Cross +400 LTC（市值 40000）");
        // 上限 = 40000 × 36% = 14400；取 4000 留足余量
        ok(api.loanCrossBorrow(xUser, loanId, ltcUsdt, 4_000.0), "Cross 借 4000 USDT");
        awaitApplied();

        long lifBefore = lifBalance(USDT_ID);
        // 簿上完全无买盘，且 LTC 腰斩 → 强平单全拒 → LIF 按债务承接
        // 4000 债 ÷ 80% = 5000 加权上限 → 市值上限 8333 → 400 LTC 单价须跌破 20
        ok(api.adjustMarkPrice(ltcUsdt, 15.0), "LTC 跌到 15，无人接盘");
        awaitCrossLoanClosed(xUser, loanId, 30);

        long lifAfter = lifBalance(USDT_ID);
        log("  LIF " + lifBefore + " → " + lifAfter);
        assertTrue(lifAfter < lifBefore, "LIF 应垫付债务，余额向负方向移动");
        assertGlobalConserved("after cross LIF takeover");
        // 砸价用例必须自己收尾：markPrice 是 symbol 级全局状态，留着崩盘价会让后续用例（乃至别的测试类）
        // 的抵押估值直接失真——这是本轮 Mix 偶发 LOAN_LTV_TOO_HIGH_AFTER_BORROW 的根因
        ok(api.adjustMarkPrice(ltcUsdt, LTC_BASE_PRICE), "LTC markPrice 复位");
    }

    @Test
    @Order(16)
    void liquidation_replenishesLoanPool() throws Exception {
        log("=== 强平后借贷池应回血：本金必须回到 poolAvailable ===");
        ok(api.adjustMarkPrice(bnbUsdt, 600.0), "BNB 复位");
        // 前序强平已把抵押品真卖掉、Cross 抵押也还锁着，这里必须补货——否则测到的是"没币"而不是本用例的逻辑
        ok(api.adjustUserBalance(borrower, nextTxId(), BNB_ID, +100.0), "补充 BNB 抵押品");
        long poolBefore = poolAvailable(USDT_ID);

        long loanId = 4960L;
        ok(api.loanCreate(borrower, loanId, bnbUsdt, 20.0, 6_000.0), "建仓借 6000");
        awaitApplied();
        long poolAfterBorrow = poolAvailable(USDT_ID);
        assertTrue(poolAfterBorrow < poolBefore, "放款后池子应减少");

        ok(api.placeOrder(lp, nextOrderId(), bnbUsdt, OrderAction.BID, OrderType.GTC,
            300.0, 300.0, 25.0, MarginMode.ISOLATED, 1, false), "lp 挂 BID 接盘");
        // 逐仓无 weight 折价：6000 债 ÷ 80% = 7500 抵押上限 → 20 BNB 单价须跌破 375
        ok(api.adjustMarkPrice(bnbUsdt, 340.0), "BNB 跌到 340 触发强平");
        awaitLoanClosed(borrower, loanId, 30);

        long poolAfterLiq = poolAvailable(USDT_ID);
        log("  池子 " + poolBefore + " → 放款后 " + poolAfterBorrow + " → 强平后 " + poolAfterLiq);
        assertTrue(poolAfterLiq > poolAfterBorrow, "强平后本金应回补池子");
        assertGlobalConserved("after pool replenish");
    }

    @Test
    @Order(17)
    void marginCallBand_warnsButDoesNotLiquidate() throws Exception {
        log("=== 负向验证：进入预警带但未越强平线，不得强平 ===");
        ok(api.adjustMarkPrice(bnbUsdt, 600.0), "BNB 复位");
        // 前序强平已把抵押品真卖掉、Cross 抵押也还锁着，这里必须补货——否则测到的是"没币"而不是本用例的逻辑
        ok(api.adjustUserBalance(borrower, nextTxId(), BNB_ID, +100.0), "补充 BNB 抵押品");
        long loanId = 4970L;
        // 20 BNB @600 = 12000 抵押，借 6000 → LTV 50%
        ok(api.loanCreate(borrower, loanId, bnbUsdt, 20.0, 6_000.0), "建仓 LTV 50%");
        awaitApplied();

        // 跌到 500：抵押 10000，LTV 60% —— 高于 marginCall 线但低于强平线（initialLtv 6000 + buffer）
        ok(api.adjustMarkPrice(bnbUsdt, 500.0), "BNB 跌到 500，LTV 升到 60%");
        TimeUnit.SECONDS.sleep(3); // 给 scanner 几轮机会

        assertNotNull(loanOf(borrower, loanId), "未越强平线的贷款不该被强平");
        ok(api.adjustMarkPrice(bnbUsdt, 600.0), "复位");
        ok(api.loanRepay(borrower, loanId, bnbUsdt, 0), "收尾还清");
        awaitApplied();
        assertGlobalConserved("after margin call band check");
    }

    @Test
    @Order(18)
    void platform_report_reflectsPoolAndLif() {
        LoanPlatformReportResult report = api.queryLoanPlatformReport().join();
        assertNotNull(report, "LOAN_PLATFORM 报表可读");
        assertTrue(report.getByShardMap().size() > 0, "至少一个 shard 有 loan 平台数据");

        long totalAvailable = 0;
        for (var perShard : report.getByShardMap().values()) {
            totalAvailable += perShard.getPoolAvailableMap().getOrDefault(USDT_ID, 0L);
        }
        assertTrue(totalAvailable > 0, "池子注资后 poolAvailable 应为正，实际 " + totalAvailable);
        log("✓ loan E2E 全流程通过");
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 从用户报表里取指定 loanId 的 Isolated 贷款，不存在返回 null。 */
    private SingleUserReportResultView.IsolatedLoanView loanOf(long uid, long loanId) throws Exception {
        return api.queryUserReport(uid).get(10, TimeUnit.SECONDS).getIsolatedLoans().stream()
            .filter(l -> l.getLoanId() == loanId).findFirst().orElse(null);
    }

    /** 借贷池可借余额，跨 shard 求和。 */
    private long poolAvailable(int currency) {
        long sum = 0;
        for (var perShard : api.queryLoanPlatformReport().join().getByShardMap().values()) {
            sum += perShard.getPoolAvailableMap().getOrDefault(currency, 0L);
        }
        return sum;
    }

    /** 轮询到该 Cross 贷款从报表中消失。 */
    private void awaitCrossLoanClosed(long uid, long loanId, int timeoutSec) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            boolean open = api.queryUserReport(uid).get(10, TimeUnit.SECONDS).getCrossLoans().stream()
                .anyMatch(l -> l.getLoanId() == loanId);
            if (!open) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(500L);
        }
        throw new AssertionError("Cross 贷款 " + loanId + " 在 " + timeoutSec + "s 内未被强平关闭");
    }

    private long lifBalance(int currency) {
        long sum = 0;
        for (var perShard : api.queryInsuranceFundReport().join().getByShardMap().values()) {
            sum += perShard.getLoanInsuranceFundMap().getOrDefault(currency, 0L);
        }
        return sum;
    }

    /** 命令经 raft 复制后各 shard 才可见，报表查询前留出窗口。 */
    private void awaitApplied() throws Exception {
        TimeUnit.MILLISECONDS.sleep(300L);
    }

    /** 轮询到该 loan 从用户报表中消失（被还清或被强平/接管关闭）。 */
    private void awaitLoanClosed(long uid, long loanId, int timeoutSec) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            var view = api.queryUserReport(uid).join();
            boolean stillOpen = view.getIsolatedLoans().stream().anyMatch(l -> l.getLoanId() == loanId);
            if (!stillOpen) {
                log("✓ loan " + loanId + " 已关闭");
                return;
            }
            TimeUnit.MILLISECONDS.sleep(500L);
        }
        throw new AssertionError("loan " + loanId + " 在 " + timeoutSec + "s 内未被强平/接管关闭");
    }

    /** 全局资金守恒：所有桶求和必须为 0，跨 shard 记账错在这里现形。 */
    /**
     * 全局守恒：八个资金桶之和恒为零。
     *
     * <p>八个桶缺一不可——少加一个桶，那个桶里的钱就会被当成凭空消失。只算
     * accountBalances + fees + adjustments 时，期货 IF 与贷款池的余额全被漏掉，
     * 在跑过期货用例的长驻集群上会直接报出上千万的假差额。
     */
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
}
