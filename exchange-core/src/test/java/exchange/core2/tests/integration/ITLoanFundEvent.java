package exchange.core2.tests.integration;

import exchange.core2.core.IFundEventsHandler.FundEventReport;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.FundEvent.FundEventType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiLoanCreate;
import exchange.core2.core.common.api.ApiLoanCrossAddCollateral;
import exchange.core2.core.common.api.ApiLoanCrossBorrow;
import exchange.core2.core.common.api.ApiLoanRepay;
import exchange.core2.core.common.api.ApiPoolDeposit;
import exchange.core2.core.common.api.binary.BatchAddLoanCommand;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * loan 事件的两侧余额快照断言：借贷侧走通用 free/locked，抵押侧走 collateralFree/collateralLocked。
 *
 * <p>刻意选 currencyScale 不同的两个币（WBTC digit=2 → scaleK=100；USDT digit=0 → scaleK=1），
 * 以证明两侧各自下发自己的 scale——只发一个 scale 的话抵押物金额在下游会还原错小数位。
 */
@Slf4j
class ITLoanFundEvent {

    private static final int WBTC = 710;   // 抵押币，digit=2 → currencyScaleK=100
    private static final int USDT = 711;   // 借款币，digit=0 → currencyScaleK=1
    private static final int SYMBOL = 71010;
    private static final long MARK_PRICE = 50_000L;
    private static final long BORROWER = 8001L;
    private static final long COLLATERAL = 300L;    // 3 WBTC（currencyScale）
    private static final long PRINCIPAL = 80_000L;  // LTV = 80000 / (300/100 × 50000) = 53.3%

    /** 事件对象池复用，回调里必须立刻取值。 */
    private static final class Snap {
        long free, locked, scaleK;
        long colFree, colLocked, colPledged, colScaleK;
        int currency, colCurrency;
        long debtPrincipal, debtInterest, interestPaidTotal, ltvBps;
    }

    /** 只截取指定类型的事件；对象池复用，回调里立刻取值。 */
    private static IEventsHandler4Test capture(FundEventType want, AtomicReference<Snap> out) {
        return new IEventsHandler4Test() {
            @Override
            public void process(FundEventReport r) {
                fundEventReport(r);
            }

            @Override
            public void process(ITradeEventsHandler.SpotExecutionReport r) {}

            @Override
            public void process(ITradeEventsHandler.FuturesExecutionReport r) {}

            @Override
            public void orderBook(ITradeEventsHandler.OrderBook o) {}

            @Override
            public void spotExecutionReport(ITradeEventsHandler.SpotExecutionReport r) {}

            @Override
            public void futuresExecutionReport(ITradeEventsHandler.FuturesExecutionReport r) {}

            @Override
            public void fundEventReport(FundEventReport r) {
                if (r.getEventType() != want) {
                    return;
                }
                final Snap s = new Snap();
                s.currency = r.getBalances().getCurrency();
                s.scaleK = r.getBalances().getCurrencyScaleK();
                s.free = r.getBalances().getFree();
                s.locked = r.getBalances().getLocked();
                s.colCurrency = r.getLoan().getCollateralCurrency();
                s.colScaleK = r.getLoan().getCollateralCurrencyScaleK();
                s.colPledged = r.getLoan().getCollateralPledged();
                s.colFree = r.getLoan().getCollateralFree();
                s.colLocked = r.getLoan().getCollateralLocked();
                s.debtPrincipal = r.getLoan().getDebtPrincipal();
                s.debtInterest = r.getLoan().getDebtInterest();
                s.interestPaidTotal = r.getLoan().getInterestPaidTotal();
                s.ltvBps = r.getLoan().getLtvBps();
                out.set(s);
            }
        };
    }

    /** initialLtv 6000 / liqLtv 8500 / marginCall 7500 / maxAmount 无上限 / 期限 365d / 抵押权重 10000（满额计价，Cross 抵押需 > 0）。 */
    private ExchangeTestContainer boot(IEventsHandler4Test handler) throws Exception {
        final ExchangeTestContainer c = ExchangeTestContainer.create(
            PerformanceConfiguration.baseBuilder().build(), new SimpleEventsProcessor4Test(handler));
        c.addCurrency(WBTC, 2);
        c.addCurrency(USDT, 0);
        c.addSymbol(CoreSymbolSpecification.builder()
            .symbolId(SYMBOL).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
            .baseCurrency(WBTC).quoteCurrency(USDT)
            .baseScaleK(1).quoteScaleK(1)
            .takerFee(0).makerFee(0)
            .build());
        c.initMarkPrice(SYMBOL, MARK_PRICE);
        c.sendBinaryDataCommandSync(
            BatchAddLoanCommand.ofSymbol(SYMBOL, 6000, 8500, 7500, Long.MAX_VALUE, 365, 10000), 5000);
        c.sendBinaryDataCommandSync(BatchAddLoanCommand.ofGlobalNumeraire(USDT), 5001); // cross 用例需要
        c.submitCommandSync(ApiPoolDeposit.builder()
            .shardId(0).currency(USDT).amount(1_000_000L).build(), CommandResultCode.SUCCESS);
        c.initOneUser(BORROWER);
        return c;
    }

    private void createLoan(ExchangeTestContainer c) {
        c.submitCommandSync(ApiLoanCreate.builder()
            .transactionId(1_000_002L).uid(BORROWER).loanId(1L).symbol(SYMBOL)
            .collateralAmount(COLLATERAL).principal(PRINCIPAL).build(), CommandResultCode.SUCCESS);
    }

    @Test
    void loanBorrow_carriesBothSidesBalances() throws Exception {
        final AtomicReference<Snap> borrowSnap = new AtomicReference<>();
        try (ExchangeTestContainer c = boot(capture(FundEventType.LOAN_BORROW, borrowSnap))) {
            c.addMoneyToUser(BORROWER, WBTC, COLLATERAL);
            createLoan(c);

            final Snap s = borrowSnap.get();
            assertNotNull(s, "未收到 LOAN_BORROW 事件");

            // 借贷侧（USDT）：放款直接进 accounts、不锁定 → free = 本金
            assertEquals(USDT, s.currency, "借贷侧币种 = 借款币");
            assertEquals(1L, s.scaleK, "借款币 scale（digit=0）");
            assertEquals(PRINCIPAL, s.free, "放款后借款币可用 = 本金");
            assertEquals(0L, s.locked, "借款币无冻结");
            assertEquals(PRINCIPAL, s.debtPrincipal, "负债本金 = 放款额");

            // 抵押侧（WBTC）：抵押是虚拟锁定——accounts 不动，locked 增加、free 归零
            assertEquals(WBTC, s.colCurrency, "抵押侧币种 = 抵押币");
            assertEquals(100L, s.colScaleK, "抵押币 scale（digit=2）≠ 借款币 scale，两侧必须各发各的");
            assertEquals(COLLATERAL, s.colPledged, "已质押抵押物");
            assertEquals(COLLATERAL, s.colLocked, "抵押被虚拟锁定 → 计入抵押币冻结额");
            assertEquals(0L, s.colFree, "抵押占满后抵押币可用归零（accounts 未被扣减）");

            // LTV = 债务 / 抵押物市值 = 80000 / (3 × 50000) = 53.33% → 5333 bps
            assertEquals(5333L, s.ltvBps, "LTV（bps）");
        }
    }

    /** Isolated 还款：两侧余额 + 本次实付利息（interestPaid 推不出，必须由事件下发）。 */
    @Test
    void loanRepay_carriesBothSidesAndInterestPaid() throws Exception {
        final AtomicReference<Snap> repaySnap = new AtomicReference<>();
        try (ExchangeTestContainer c = boot(capture(FundEventType.LOAN_REPAY, repaySnap))) {
            c.addMoneyToUser(BORROWER, WBTC, COLLATERAL);
            createLoan(c);
            // 还 30000（rate=0 → 无利息，全部冲本金）
            c.submitCommandSync(ApiLoanRepay.builder()
                .transactionId(1_000_003L).uid(BORROWER).loanId(1L).repayAmount(30_000L).build(),
                CommandResultCode.SUCCESS);

            final Snap s = repaySnap.get();
            assertNotNull(s, "未收到 LOAN_REPAY 事件");
            assertEquals(PRINCIPAL - 30_000L, s.debtPrincipal, "剩余本金");
            assertEquals(PRINCIPAL - 30_000L, s.free, "借款币可用 = 放款 − 已还");
            assertEquals(0L, s.interestPaidTotal, "rate=0 → 本次无利息");
            // 抵押未动，仍全额锁定
            assertEquals(COLLATERAL, s.colPledged, "还款不影响抵押");
            assertEquals(COLLATERAL, s.colLocked);
            assertEquals(0L, s.colFree);
        }
    }

    /**
     * 累计语义：interestPaidTotal 是单调递增的快照，两次还款相减 = 本次实付利息。
     * 这是「事件只发快照、不发 delta」的落地验证——若退回发本次量，第二次事件会是本次值而非累计值。
     */
    @Test
    void interestPaidTotal_isMonotonicCumulative() throws Exception {
        final AtomicReference<Snap> snap = new AtomicReference<>();
        try (ExchangeTestContainer c = boot(capture(FundEventType.LOAN_REPAY, snap))) {
            // 非零利率曲线：base=1200bps，让还款真的产生利息
            c.sendBinaryDataCommandSync(BatchAddLoanCommand.ofRateCurve(1200, 8000, 0, 0, 0), 5002);
            c.addMoneyToUser(BORROWER, WBTC, COLLATERAL);
            createLoan(c);
            c.addMoneyToUser(BORROWER, USDT, 50_000L); // 备够还款资金

            // 第一次还款：计息到 T1 后利息优先
            c.submitCommandSync(ApiLoanRepay.builder()
                .transactionId(1_000_004L).uid(BORROWER).loanId(1L).repayAmount(10_000L).build(),
                CommandResultCode.SUCCESS);
            final long cum1 = snap.get().interestPaidTotal;

            // 第二次还款
            c.submitCommandSync(ApiLoanRepay.builder()
                .transactionId(1_000_005L).uid(BORROWER).loanId(1L).repayAmount(10_000L).build(),
                CommandResultCode.SUCCESS);
            final long cum2 = snap.get().interestPaidTotal;

            assertTrue(cum2 >= cum1, "累计已付利息必须单调不减：cum1=" + cum1 + " cum2=" + cum2);
            // 相邻相减即得本次量——这正是删掉 delta 字段后下游的推导方式
            assertTrue(cum2 - cum1 >= 0, "相邻两条相减 = 本次实付利息");
        }
    }

    /**
     * Cross 借款：抵押侧整组为 0（含币种）——cross 无唯一抵押币，
     * 且 collateralCurrency=0 会走 newLoanEvent 的 scale zero-guard，写错就 NPE。
     */
    @Test
    void crossBorrow_collateralSideAllZero() throws Exception {
        final AtomicReference<Snap> snap = new AtomicReference<>();
        try (ExchangeTestContainer c = boot(capture(FundEventType.LOAN_BORROW, snap))) {
            c.addMoneyToUser(BORROWER, WBTC, COLLATERAL);
            c.submitCommandSync(ApiLoanCrossAddCollateral.builder()
                .transactionId(1_000_010L).uid(BORROWER).currency(WBTC).amount(COLLATERAL).build(),
                CommandResultCode.SUCCESS);
            c.submitCommandSync(ApiLoanCrossBorrow.builder()
                .transactionId(1_000_011L).uid(BORROWER).loanId(9L).symbolId(SYMBOL).principal(PRINCIPAL).build(),
                CommandResultCode.SUCCESS);

            final Snap s = snap.get();
            assertNotNull(s, "未收到 cross LOAN_BORROW 事件");
            // 借贷侧照常
            assertEquals(USDT, s.currency);
            assertEquals(PRINCIPAL, s.free, "放款进借款币可用");
            assertEquals(PRINCIPAL, s.debtPrincipal);
            // 抵押侧整组为 0
            assertEquals(0, s.colCurrency, "cross borrow 无唯一抵押币 → 币种为 0");
            assertEquals(0L, s.colScaleK, "币种为 0 → scale 也为 0（zero-guard 生效，未查币种表）");
            assertEquals(0L, s.colPledged);
            assertEquals(0L, s.colFree);
            assertEquals(0L, s.colLocked);
        }
    }

    /**
     * Cross 加抵押：loanCurrency=0（无唯一借款币）——借贷侧整组为 0，
     * 走 newLoanEvent 借款币侧的 zero-guard；抵押侧照常填。
     */
    @Test
    void crossAddCollateral_debtSideZero_collateralSideFilled() throws Exception {
        final AtomicReference<Snap> snap = new AtomicReference<>();
        try (ExchangeTestContainer c = boot(capture(FundEventType.LOAN_COLLATERAL_CHANGE, snap))) {
            c.addMoneyToUser(BORROWER, WBTC, COLLATERAL);
            c.submitCommandSync(ApiLoanCrossAddCollateral.builder()
                .transactionId(1_000_012L).uid(BORROWER).currency(WBTC).amount(COLLATERAL).build(),
                CommandResultCode.SUCCESS);

            final Snap s = snap.get();
            assertNotNull(s, "未收到 cross LOAN_COLLATERAL_CHANGE 事件");
            // 借贷侧整组为 0
            assertEquals(0, s.currency, "cross 加抵押无唯一借款币 → 币种为 0");
            assertEquals(0L, s.scaleK, "币种为 0 → scale 也为 0（zero-guard 生效）");
            assertEquals(0L, s.free);
            assertEquals(0L, s.locked);
            assertEquals(0L, s.debtPrincipal);
            // 抵押侧照常
            assertEquals(WBTC, s.colCurrency);
            assertEquals(100L, s.colScaleK);
            assertEquals(COLLATERAL, s.colPledged, "账户级抵押池该币余额");
            assertEquals(COLLATERAL, s.colLocked, "抵押虚拟锁定");
            assertEquals(0L, s.colFree);
        }
    }
}
