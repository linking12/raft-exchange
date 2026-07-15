package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiLoanCreate;
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

            // loan2 创建于 reprice 后 → 率 = 曲线值 240
            createLoan(c, 1_000_021L, 2L, 100_000L, (byte) 1);
            assertEquals(240, loanRateBps(c, 2L), "reprice 后新 FLOATING 率 = curve(util) = 240");
        }
    }
}
