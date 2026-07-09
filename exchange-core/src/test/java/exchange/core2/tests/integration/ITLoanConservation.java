package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.IsolatedLoanRecord;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiLoanCreate;
import exchange.core2.core.common.api.ApiLoanForceLiquidate;
import exchange.core2.core.common.api.ApiLoanRepay;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.ApiPoolDeposit;
import exchange.core2.core.common.api.binary.UpdateSymbolLoanConfigCommand;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.loan.LoanService;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loan 端到端资金守恒测试 —— 关键点：<b>base 币 currencyScale ≠ 撮合 lotScale（baseScaleK=1, digit=2）</b>，
 * 这是今年那个 scale bug 的真实条件，也是过去所有 loan 测试（identity scale）测不出来的盲区。
 *
 * <p>断言两件事：① 强平后 exchangeLocked 归零（pre-move 的抵押被真正撮合消费/释放，没漂）；
 * ② {@code totalBalanceReport().isGlobalBalancesAllZero()} —— 现在报告已把 loan 平台桶
 * （poolAvailable + interestRevenue + loanLiqFees）纳入对账，能真正看见 loan 的钱。
 */
@Slf4j
class ITLoanConservation {

    private static final int WBTC = 710;          // base：digit=2 → currencyScaleK=100
    private static final int USDT = 711;          // quote：digit=0 → currencyScaleK=1
    private static final int SYMBOL = 71010;       // baseScaleK=1 → 与 WBTC currencyScaleK=100 错配
    private static final long MARK_PRICE = 50_000L;
    private static final long POOL_FUND = 10_000_000L;
    private static final long BORROWER = 8001L;
    private static final long LP = 8002L;

    /** 3 WBTC 抵押（currencyScale=300）→ 换算成 3 lot 卖出。 */
    private static final long COLLATERAL_WBTC = 300L; // 3 WBTC
    private static final long COLLATERAL_LOTS = 3L;
    private static final long PRINCIPAL = 80_000L;    // LTV = 80000 / 150000 ≈ 53% < 60% initial

    private ExchangeTestContainer boot() throws Exception {
        ExchangeTestContainer c = ExchangeTestContainer.create(PerformanceConfiguration.baseBuilder().build());
        c.addCurrency(WBTC, 2);
        c.addCurrency(USDT, 0);
        c.addSymbol(CoreSymbolSpecification.builder()
            .symbolId(SYMBOL).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
            .baseCurrency(WBTC).quoteCurrency(USDT)
            .baseScaleK(1).quoteScaleK(1)
            .takerFee(0).makerFee(0)
            .build());
        c.initMarkPrice(SYMBOL, MARK_PRICE);
        // initialLtv 6000 / liqLtv 8500 / marginCall 7500 / rate 0（免利息，简化守恒断言）/ maxAmount MAX / term 365d / weight 10000
        c.sendBinaryDataCommandSync(
            new UpdateSymbolLoanConfigCommand(SYMBOL, 6000, 8500, 7500, 0, Long.MAX_VALUE, 365, 10000), 5000);
        c.submitCommandSync(ApiPoolDeposit.builder()
            .externalId(1_000_001L).shardId(0).currency(USDT).amount(POOL_FUND).build(), CommandResultCode.SUCCESS);
        c.initOneUser(BORROWER);
        c.initOneUser(LP);
        c.addMoneyToUser(BORROWER, WBTC, COLLATERAL_WBTC);
        c.addMoneyToUser(LP, USDT, POOL_FUND);
        return c;
    }

    private void createLoan(ExchangeTestContainer c, long loanId) {
        c.submitCommandSync(ApiLoanCreate.builder()
            .externalId(1_000_002L).uid(BORROWER).loanId(loanId).symbol(SYMBOL)
            .collateralAmount(COLLATERAL_WBTC).principal(PRINCIPAL).build(), CommandResultCode.SUCCESS);
    }

    private void placeLpBid(ExchangeTestContainer c, long orderId, long lots) {
        c.submitCommandSync(ApiPlaceOrder.builder()
            .uid(LP).orderId(orderId).action(OrderAction.BID).size(lots).price(MARK_PRICE).reservePrice(MARK_PRICE)
            .symbol(SYMBOL).orderType(OrderType.GTC).marginMode(MarginMode.ISOLATED).build(), CommandResultCode.SUCCESS);
    }

    private long forceSellOrderId(long loanId) {
        IsolatedLoanRecord id = new IsolatedLoanRecord();
        id.uid = BORROWER;
        id.loanId = loanId;
        return LoanService.generateIsolatedForceSellOrderId(id);
    }

    private void assertGlobalConserved(ExchangeTestContainer c, String where) {
        assertTrue(c.totalBalanceReport().isGlobalBalancesAllZero(),
            "全局资金守恒破裂 @ " + where + " —— loan 平台桶已纳入对账");
    }

    @Test
    public void fullLiquidation_nonIdentityScale_conserves() throws Exception {
        final long loanId = 1L;
        try (ExchangeTestContainer c = boot()) {
            assertGlobalConserved(c, "after setup");
            createLoan(c, loanId);
            assertGlobalConserved(c, "after loan create");
            placeLpBid(c, 1000L, COLLATERAL_LOTS); // 吃满 3 lot

            c.submitCommandSync(ApiLoanForceLiquidate.builder()
                .uid(BORROWER).symbol(SYMBOL).loanId(loanId).price(MARK_PRICE).size(COLLATERAL_LOTS)
                .orderId(forceSellOrderId(loanId)).action(OrderAction.ASK).orderType(OrderType.IOC).build(),
                CommandResultCode.SUCCESS);

            // 抵押被撮合消费，exchangeLocked 必须归零（scale 换算对了才不会漂）
            SingleUserReportResult borrower = c.getUserProfile(BORROWER);
            assertEquals(0L, borrower.getExchangeLocked().get(WBTC), "exchangeLocked[WBTC] 未归零");
            assertEquals(0L, borrower.getAccounts().get(WBTC), "3 WBTC 抵押应全部卖出");
            // LP 收到 3 WBTC（300 currencyScale）
            assertEquals(COLLATERAL_WBTC, c.getUserProfile(LP).getAccounts().get(WBTC), "LP 应收 3 WBTC");
            // 结算数学：proceeds=3×50000=150000，liqFee=2%=3000，本金 80000 回池，overpay 留用户。
            // 借款人 USDT = 借入本金 + proceeds − liqFee − 回池本金 = proceeds − liqFee = 147000。
            final long proceeds = COLLATERAL_LOTS * MARK_PRICE;
            final long liqFee = proceeds * 200L / 10000L;
            assertEquals(proceeds - liqFee, borrower.getAccounts().get(USDT),
                "借款人 USDT 结算额错（应为 proceeds−liqFee）");
            assertGlobalConserved(c, "after full liquidation");
        }
    }

    @Test
    public void partialFillLiquidation_nonIdentityScale_conserves() throws Exception {
        final long loanId = 2L;
        try (ExchangeTestContainer c = boot()) {
            createLoan(c, loanId);
            placeLpBid(c, 1000L, 2L); // 只吃 2 lot，第 3 lot IOC 拒单

            c.submitCommandSync(ApiLoanForceLiquidate.builder()
                .uid(BORROWER).symbol(SYMBOL).loanId(loanId).price(MARK_PRICE).size(COLLATERAL_LOTS)
                .orderId(forceSellOrderId(loanId)).action(OrderAction.ASK).orderType(OrderType.IOC).build(),
                CommandResultCode.SUCCESS);

            // 2 lot 成交 + 1 lot 拒单回填：exchangeLocked 仍须归零（成交扣 + 拒单释放两侧同尺）
            SingleUserReportResult borrower = c.getUserProfile(BORROWER);
            assertEquals(0L, borrower.getExchangeLocked().get(WBTC), "partial-fill 后 exchangeLocked[WBTC] 未归零");
            // 卖出 2 lot = 200 currencyScale，账户 base 从 300 降到 100（剩 1 WBTC 仍是抵押，拒单回填留在 loan）
            assertEquals(100L, borrower.getAccounts().get(WBTC), "只卖 2 lot，账户应剩 100（1 WBTC）");
            // proceeds=2×50000=100000，liqFee=2%=2000，本金 80000 回池 → 借款人 USDT = 100000−2000 = 98000
            final long proceeds = 2L * MARK_PRICE;
            assertEquals(proceeds - proceeds * 200L / 10000L, borrower.getAccounts().get(USDT),
                "partial-fill 借款人 USDT 结算额错");
            assertGlobalConserved(c, "after partial-fill liquidation");
        }
    }

    @Test
    public void repay_nonIdentityScale_conserves() throws Exception {
        final long loanId = 3L;
        try (ExchangeTestContainer c = boot()) {
            createLoan(c, loanId);
            assertGlobalConserved(c, "after create");
            // 借入后借款人 USDT = PRINCIPAL
            assertEquals(PRINCIPAL, c.getUserProfile(BORROWER).getAccounts().get(USDT), "借入后 USDT 应 = 本金");
            // 全额还款（repayAmount=0 = payoff 本息）
            c.submitCommandSync(ApiLoanRepay.builder()
                .externalId(1_000_009L).uid(BORROWER).loanId(loanId).repayAmount(0L).build(), CommandResultCode.SUCCESS);
            // rate=0 → 无利息，还清后借款人 USDT 归零（本金全额还回池）
            assertEquals(0L, c.getUserProfile(BORROWER).getAccounts().get(USDT), "全额还款后 USDT 应归零");
            assertGlobalConserved(c, "after full repay");
        }
    }
}
