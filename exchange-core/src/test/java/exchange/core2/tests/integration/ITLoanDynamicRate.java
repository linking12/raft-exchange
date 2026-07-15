package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiLoanCreate;
import exchange.core2.core.common.api.ApiNop;
import exchange.core2.core.common.api.ApiPoolDeposit;
import exchange.core2.core.common.api.ApiRepriceLoanRates;
import exchange.core2.core.common.api.binary.BatchAddLoanCommand;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 动态利率端到端：① {@code ADD_LOAN} 的 rateCurve 配置流到开仓利率（FLOATING=曲线 base、LOCKED=base+lockedAdjust）；
 * ② {@code REPRICE_LOAN_RATES} 把池利用率过 kinked 曲线写进 currentRateBps，后续新 FLOATING 贷款按新曲线值开仓。
 *
 * <p>观测口径：贷款开仓利率 {@code rateBps} 锁定 = 开仓时的曲线现值，经 SingleUserReport 读出。identity scale，数字直算。
 */
class ITLoanDynamicRate {

    private static final int BTC = 1;              // 抵押币，digit 0
    private static final int USDT = 2;             // 借出币，digit 0
    private static final int SYMBOL = 100;
    private static final long MARK_PRICE = 50_000L;
    private static final long POOL_FUND = 10_000_000L;
    private static final long BORROWER = 8001L;

    /** 默认 kinked 曲线（对齐 FloatingRateModel 默认）。 */
    private static final int KINK = 8000, SLOPE1 = 400, SLOPE2 = 6000;

    private ExchangeTestContainer boot(int base, int lockedAdjust) throws Exception {
        ExchangeTestContainer c = ExchangeTestContainer.create(PerformanceConfiguration.baseBuilder().build());
        c.addCurrency(BTC, 0);
        c.addCurrency(USDT, 0);
        c.addSymbol(CoreSymbolSpecification.builder()
            .symbolId(SYMBOL).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
            .baseCurrency(BTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
            .takerFee(0).makerFee(0).build());
        c.initMarkPrice(SYMBOL, MARK_PRICE);
        c.sendBinaryDataCommandSync(
            BatchAddLoanCommand.ofSymbol(SYMBOL, 6000, 8500, 7500, Long.MAX_VALUE, 365, 10000), 5000);
        c.sendBinaryDataCommandSync(BatchAddLoanCommand.ofGlobalNumeraire(USDT), 5001);
        c.sendBinaryDataCommandSync(BatchAddLoanCommand.ofRateCurve(base, KINK, SLOPE1, SLOPE2, lockedAdjust), 5002);
        c.submitCommandSync(ApiPoolDeposit.builder()
            .externalId(1_000_001L).shardId(0).currency(USDT).amount(POOL_FUND).build(), CommandResultCode.SUCCESS);
        c.initOneUser(BORROWER);
        c.addMoneyToUser(BORROWER, BTC, 300L); // 够 2 笔 100 BTC 抵押
        return c;
    }

    private void createLoan(ExchangeTestContainer c, long extId, long loanId, long principal, byte rateMode)
        throws Exception {
        c.submitCommandSync(ApiLoanCreate.builder()
            .externalId(extId).uid(BORROWER).loanId(loanId).symbol(SYMBOL)
            .collateralAmount(100L).principal(principal).rateMode(rateMode).build(), CommandResultCode.SUCCESS);
    }

    private int loanRateBps(ExchangeTestContainer c, long loanId) throws Exception {
        for (SingleUserReportResult.IsolatedLoan l : c.getUserProfile(BORROWER).getIsolatedLoans()) {
            if (l.loanId == loanId) {
                return l.rateBps;
            }
        }
        throw new AssertionError("isolated loan not found: " + loanId);
    }

    @Test
    public void rateCurveConfig_flowsToOpenRate() throws Exception {
        // base=300 + lockedAdjust=50；未 reprice → currentRate 空 → 回退曲线 base
        try (ExchangeTestContainer c = boot(300, 50)) {
            createLoan(c, 1_000_010L, 1L, 1_000_000L, (byte) 1); // FLOATING
            createLoan(c, 1_000_011L, 2L, 1_000_000L, (byte) 0); // LOCKED
            assertEquals(300, loanRateBps(c, 1L), "FLOATING 开仓率 = 曲线 base（未 reprice 回退）");
            assertEquals(350, loanRateBps(c, 2L), "LOCKED 开仓率 = base + lockedAdjust");
        }
    }

    @Test
    public void reprice_utilizationToCurve_updatesNextFloatingOpenRate() throws Exception {
        // 默认曲线 base=200 / kink=8000 / slope1=400 / slope2=6000
        try (ExchangeTestContainer c = boot(200, 0)) {
            // loan1 借 800000 → util = 800000/10_000_000 = 800 bps；创建早于 reprice → 率 = base = 200
            createLoan(c, 1_000_020L, 1L, 800_000L, (byte) 1);
            assertEquals(200, loanRateBps(c, 1L), "reprice 前 FLOATING 率 = base");

            // reprice：util=800（<kink）→ 200 + 400×800/8000 = 240，写入 currentRateBps[USDT]
            c.submitCommandSync(ApiRepriceLoanRates.builder().build(), CommandResultCode.SUCCESS);
            // REPRICE 的生效写落在 R2（撮合后）阶段，而其 SUCCESS 结果在撮合阶段即发布 —— submitCommandSync 返回时 R2 可能尚未执行。
            // 若紧接着建 loan2，其 R1 读 currentRateBps 可能早于 reprice 的 R2 写，读到 base（200）而非曲线值。ApiNop 屏障排空管道，
            // 保证 reprice 的 R2 已应用后再建 loan2（否则该断言在共享 JVM 高负载下偶发 200，见 flaky 排查）。
            c.submitCommandSync(ApiNop.builder().build(), CommandResultCode.SUCCESS);

            // loan2 创建于 reprice 后 → 率 = 曲线值 240
            createLoan(c, 1_000_021L, 2L, 100_000L, (byte) 1);
            assertEquals(240, loanRateBps(c, 2L), "reprice 后新 FLOATING 率 = curve(util) = 240");
        }
    }

    /**
     * ADD_LOAN rateCurve 部分独立校验：非法曲线（kink=100% 越界，见 {@code RateCurveConfig.valid()}）被
     * RiskEngine dispatch 静默跳过（warn，无 reject 码），既有 good 曲线原样保留。
     *
     * <p>观测口径：非法 ofRateCurve 携带 base=999/adjust=0；若被误应用，FLOATING 开仓率会变 999、LOCKED 变 999。
     * 断言仍为 good 曲线的 base=300 / base+adjust=350，证明非法配置未覆盖既有曲线。
     */
    @Test
    public void rateCurveConfig_invalidRejected_keepsGoodCurve() throws Exception {
        // boot 已应用 good 曲线 base=300 / lockedAdjust=50
        try (ExchangeTestContainer c = boot(300, 50)) {
            // 非法曲线：kink=10000（=100%）越界 → RateCurveConfig.valid() 返回 false → dispatch 跳过应用。
            // base=999 是"若误应用"会被观测到的哨兵值。
            c.sendBinaryDataCommandSync(BatchAddLoanCommand.ofRateCurve(999, 10000, 400, 6000, 0), 5100);

            // 未 reprice → currentRate 空 → FLOATING 回退曲线 base；仍为 good 的 300 而非非法哨兵 999。
            createLoan(c, 1_000_030L, 1L, 1_000_000L, (byte) 1); // FLOATING
            createLoan(c, 1_000_031L, 2L, 1_000_000L, (byte) 0); // LOCKED
            assertEquals(300, loanRateBps(c, 1L), "非法曲线被跳过：FLOATING 率仍为 good base=300");
            assertEquals(350, loanRateBps(c, 2L), "非法曲线被跳过：LOCKED 率仍为 good base+adjust=350");
        }
    }

    /**
     * ADD_LOAN symbol 部分独立校验：非法阈值（liquidation ≤ initial，见 {@code SymbolLoanConfig.fieldsValid()}）
     * 被 RiskEngine dispatch 静默跳过，既有 good symbol loanConfig（含 loanMaxAmount=MAX）原样保留。
     *
     * <p>观测口径：非法 ofSymbol 携带 loanMaxAmount=1；若被误应用（原子改写整块配置），principal=1_000_000
     * 的借款会撞 {@code LOAN_PRINCIPAL_EXCEEDS_LIMIT}。断言该借款仍 SUCCESS，证明 good 阈值未被非法配置污染。
     */
    @Test
    public void symbolConfig_invalidRejected_keepsGoodConfig() throws Exception {
        try (ExchangeTestContainer c = boot(300, 50)) {
            // 非法 symbol 配置：liquidation=5000 ≤ initial=6000 → fieldsValid() 返回 false → dispatch 跳过。
            // loanMaxAmount=1 是"若误应用"会拦截大额借款的哨兵值。
            c.sendBinaryDataCommandSync(
                BatchAddLoanCommand.ofSymbol(SYMBOL, 6000, 5000, 0, 1L, 365, 10000), 5101);

            // good 配置 loanMaxAmount=Long.MAX_VALUE 未被覆盖 → principal=1_000_000 借款仍应 SUCCESS
            // （createLoan 内部断言 CommandResultCode.SUCCESS；若哨兵 maxAmount=1 生效则会 LOAN_PRINCIPAL_EXCEEDS_LIMIT）。
            createLoan(c, 1_000_040L, 1L, 1_000_000L, (byte) 1); // FLOATING
            assertEquals(300, loanRateBps(c, 1L), "非法 symbol 配置被跳过：借款按 good 配置正常开仓，率=曲线 base=300");
        }
    }
}
