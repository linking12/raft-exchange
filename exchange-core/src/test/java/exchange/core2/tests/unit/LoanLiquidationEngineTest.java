package exchange.core2.tests.unit;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.CrossLoanRecord;
import exchange.core2.core.common.IsolatedLoanRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.api.ApiLoanCrossForceLiquidate;
import exchange.core2.core.common.api.ApiLoanForceLiquidate;
import org.mockito.ArgumentCaptor;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.liquidation.LiquidationCmdPublisher;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.processors.loan.LoanLiquidationEngine;
import exchange.core2.core.processors.loan.LoanService;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LoanLiquidationEngine 单元测试。
 *
 * <p>覆盖两大块：
 * <ul>
 *   <li><b>in-flight 生命周期</b>：publishTrackedIsolated / publishTrackedCross 的加入 / onApplied 清 / 异常回滚。</li>
 *   <li><b>Scanner skeleton</b>：check() 各分支保 no-throw；当前 log.debug 占位下"永不 publish"作为 skeleton
 *   invariant——force-sell 实装后这些"never publish" 断言会失败作提示。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoanLiquidationEngineTest {

    private static final long UID = 42L;
    private static final long LOAN_ID = 999L;
    private static final int SYMBOL = 100;
    private static final int BTC = 1;
    private static final int USDT = 2;

    @Mock private LiquidationEngine engine;
    @Mock private LiquidationCmdPublisher publisher;

    private LoanService loanService;
    private UserProfile up;
    private SymbolSpecificationProvider specProvider;
    private CurrencySpecificationProvider currencyProvider;
    private IntObjectHashMap<LastPriceCacheRecord> priceCache;
    private LoanLiquidationEngine scanner;

    @BeforeEach
    void setUp() {
        loanService = new LoanService();
        up = new UserProfile(UID, UserStatus.ACTIVE);
        specProvider = new SymbolSpecificationProvider();
        currencyProvider = new CurrencySpecificationProvider();
        priceCache = new IntObjectHashMap<>();

        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(BTC).name("BTC").digit(0).build());
        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(USDT).name("USDT").digit(0).build());

        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(BTC).quoteCurrency(USDT)
                .baseScaleK(1).quoteScaleK(1)
                .loanInitialLtvBps(6000)
                .loanLiquidationLtvBps(8000)
                .loanMarginCallLtvBps(7000)
                .loanRateBps(500)
                .loanMaxTermDays(90)
                .collateralWeightBps(9000)
                .build();
        specProvider.registerSymbol(SYMBOL, spec);

        LastPriceCacheRecord price = new LastPriceCacheRecord();
        price.markPrice = 50_000L;
        priceCache.put(SYMBOL, price);

        when(engine.getSymbolSpecificationProvider()).thenReturn(specProvider);
        when(engine.getCurrencySpecificationProvider()).thenReturn(currencyProvider);
        when(engine.getLastPriceCache()).thenReturn(priceCache);
        when(engine.getLoanService()).thenReturn(loanService);
        when(engine.getLiquidationCmdPublisher()).thenReturn(publisher);

        scanner = new LoanLiquidationEngine(engine);
    }

    // ================================================================
    // in-flight guard 初始状态
    // ================================================================

    @Test
    void inFlight_startsEmpty_forBothLanes() {
        assertFalse(scanner.isIsolatedLoanInFlight(LOAN_ID));
        assertFalse(scanner.isCrossLoanInFlight(UID));
    }

    // ================================================================
    // publishTrackedIsolated
    // ================================================================

    @Test
    void publishTrackedIsolated_addsLoanIdToInFlight() {
        ApiCommand cmd = mock(ApiCommand.class);
        scanner.publishTrackedIsolated(cmd, LOAN_ID);
        assertTrue(scanner.isIsolatedLoanInFlight(LOAN_ID), "publish 后 in-flight");
        verify(publisher).publish(eq(cmd), any(Runnable.class));
    }

    @Test
    void publishTrackedIsolated_onAppliedCallbackClearsInFlight() {
        ApiCommand cmd = mock(ApiCommand.class);
        AtomicReference<Runnable> capturedOnApplied = new AtomicReference<>();
        doAnswer(inv -> {
            capturedOnApplied.set(inv.getArgument(1));
            return null;
        }).when(publisher).publish(any(), any(Runnable.class));

        scanner.publishTrackedIsolated(cmd, LOAN_ID);
        assertTrue(scanner.isIsolatedLoanInFlight(LOAN_ID));
        assertNotNull(capturedOnApplied.get());

        // 模拟 raft apply 完成
        capturedOnApplied.get().run();
        assertFalse(scanner.isIsolatedLoanInFlight(LOAN_ID), "onApplied 清 in-flight");
    }

    @Test
    void publishTrackedIsolated_publisherThrows_cleansUpAndRethrows() {
        ApiCommand cmd = mock(ApiCommand.class);
        RuntimeException boom = new RuntimeException("publish failed");
        doThrow(boom).when(publisher).publish(any(), any(Runnable.class));

        assertThrows(RuntimeException.class, () -> scanner.publishTrackedIsolated(cmd, LOAN_ID));
        assertFalse(scanner.isIsolatedLoanInFlight(LOAN_ID), "异常路径清 in-flight 避免死值");
    }

    // ================================================================
    // publishTrackedCross
    // ================================================================

    @Test
    void publishTrackedCross_addsUidToInFlight() {
        ApiCommand cmd = mock(ApiCommand.class);
        scanner.publishTrackedCross(cmd, UID);
        assertTrue(scanner.isCrossLoanInFlight(UID));
        verify(publisher).publish(eq(cmd), any(Runnable.class));
    }

    @Test
    void publishTrackedCross_onAppliedCallbackClearsInFlight() {
        ApiCommand cmd = mock(ApiCommand.class);
        AtomicReference<Runnable> capturedOnApplied = new AtomicReference<>();
        doAnswer(inv -> {
            capturedOnApplied.set(inv.getArgument(1));
            return null;
        }).when(publisher).publish(any(), any(Runnable.class));

        scanner.publishTrackedCross(cmd, UID);
        assertTrue(scanner.isCrossLoanInFlight(UID));

        capturedOnApplied.get().run();
        assertFalse(scanner.isCrossLoanInFlight(UID));
    }

    @Test
    void publishTrackedCross_publisherThrows_cleansUpAndRethrows() {
        ApiCommand cmd = mock(ApiCommand.class);
        doThrow(new RuntimeException("boom")).when(publisher).publish(any(), any(Runnable.class));

        assertThrows(RuntimeException.class, () -> scanner.publishTrackedCross(cmd, UID));
        assertFalse(scanner.isCrossLoanInFlight(UID));
    }

    // ================================================================
    // check() —— skeleton invariant: 从不调 publisher.publish
    // 未来 force-sell 实装后这些用例会失败作为 pending-work 提示（见 loan.md §7.5 / §7.6）
    // ================================================================

    @Test
    void check_emptyProfile_noThrow_noPublish() {
        scanner.check(up);
        verify(publisher, never()).publish(any(), any());
    }

    @Test
    void check_isolatedHealthyLtv_noPublish() {
        // 2 BTC 抵押 30k USDT → LTV = 30k/(2*50k) = 30%，远低于阈值
        // openedAtTs 用当前时刻 + rateBps=0：既不触 loanMaxTermDays 期限强平，也无 pending interest 抬 realDebt
        long nowNs = System.currentTimeMillis() * 1_000_000L;
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, BTC, USDT, 0, nowNs);
        loan.collateralAmount = 2L;
        loan.outstandingPrincipal = 30_000L;
        up.isolatedLoans.put(LOAN_ID, loan);

        scanner.check(up);
        verify(publisher, never()).publish(any(), any());
    }

    @Test
    void check_isolatedLtvOverLiquidation_publishesForceSell() {
        // 1 BTC 抵押 45k USDT → LTV = 90% ≥ 80% liquidation
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, BTC, USDT, 500, 1000L);
        loan.collateralAmount = 1L;
        loan.outstandingPrincipal = 45_000L;
        up.isolatedLoans.put(LOAN_ID, loan);

        scanner.check(up);
        // Isolated force-sell 实装：LTV 触线时 publish 一次 ApiLiquidationOrder
        verify(publisher).publish(any(), any());
    }

    @Test
    void check_isolated_identityScale_publishesSizeEqualToCollateralLots() {
        // identity scale（BTC digit=0, baseScaleK=1）：1 lot = 1 currency unit，size 应 == collateral == 1
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, BTC, USDT, 0, 1000L);
        loan.collateralAmount = 1L;
        loan.outstandingPrincipal = 45_000L; // LTV 90% ≥ 80% liquidation
        up.isolatedLoans.put(LOAN_ID, loan);

        scanner.check(up);

        ArgumentCaptor<ApiCommand> captor = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher).publish(captor.capture(), any());
        ApiLoanForceLiquidate cmd = (ApiLoanForceLiquidate) captor.getValue();
        assertEquals(1L, cmd.size, "identity scale 下下单 size = 抵押张数 = 1");
        assertEquals(SYMBOL, cmd.symbol);
        assertEquals(LOAN_ID, cmd.loanId);
    }

    @Test
    void check_isolated_nonIdentityScale_publishesLotScaledSizeNotCurrencyAmount() {
        // ★ 回归护栏：base 币 currencyScaleK=100（digit=2）+ baseScaleK=1 → 抵押金额(currencyScale) ≠ 下单张数(lot)。
        // 旧代码 scanner 用 .size(loan.collateralAmount) 会填 300（撮合多卖 100×）；正确应填 collateralAmountToLots=3。
        final int WBTC = 10;
        final int SYMBOL_NI = 200;
        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(WBTC).name("WBTC").digit(2).build());
        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_NI).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(WBTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
                .loanInitialLtvBps(6000).loanLiquidationLtvBps(8000).loanMarginCallLtvBps(7000)
                .loanRateBps(0).collateralWeightBps(9000).build();
        specProvider.registerSymbol(SYMBOL_NI, spec);
        LastPriceCacheRecord price = new LastPriceCacheRecord();
        price.markPrice = 50_000L;
        priceCache.put(SYMBOL_NI, price);

        // 抵押 300（= 3 WBTC）, 债务 140k → collateralValue = 3×50000 = 150000, LTV = 93% ≥ 80% liquidation
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, WBTC, USDT, 0, 1000L);
        loan.collateralAmount = 300L;
        loan.outstandingPrincipal = 140_000L;
        up.isolatedLoans.put(LOAN_ID, loan);

        scanner.check(up);

        ArgumentCaptor<ApiCommand> captor = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher).publish(captor.capture(), any());
        ApiLoanForceLiquidate cmd = (ApiLoanForceLiquidate) captor.getValue();
        long expectedLots =
                LoanService.collateralAmountToLots(300L, spec, currencyProvider.getCurrencySpecification(WBTC));
        assertEquals(3L, expectedLots, "300 currencyScale ÷ 100 = 3 lot（sanity）");
        assertEquals(expectedLots, cmd.size,
                "scanner 下单 size 必须是张数(lot)=3，而非 currencyScale 抵押量 300（旧 bug 会填 300）");
        assertEquals(SYMBOL_NI, cmd.symbol);
    }

    @Test
    void check_isolatedInFlightLoan_skipsWithoutTouchingPriceCache() {
        // pre-populate in-flight → scanner 应早退，不查 markPrice
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, BTC, USDT, 500, 1000L);
        loan.collateralAmount = 1L;
        loan.outstandingPrincipal = 45_000L;
        up.isolatedLoans.put(LOAN_ID, loan);

        // 先 publish 一次让 loanId 进 in-flight（真实场景下这是 scanner 上一 tick 触发的）
        ApiCommand cmd = mock(ApiCommand.class);
        scanner.publishTrackedIsolated(cmd, LOAN_ID);
        assertTrue(scanner.isIsolatedLoanInFlight(LOAN_ID));

        // 现在 check —— 该 loan 应被 in-flight guard 跳过
        scanner.check(up);
        // 只 publish 过 1 次（setup 那次），check 期间没再 publish
        verify(publisher).publish(any(), any());  // 1 次总量
    }

    @Test
    void check_isolatedEmptyLoan_skipped() {
        // isEmpty loan（全零壳）应跳过，不查 spec、不查 markPrice、不触发
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, BTC, USDT, 500, 1000L);
        // collateralAmount=0, principal=0, interest=0 → isEmpty
        up.isolatedLoans.put(LOAN_ID, loan);

        scanner.check(up);
        verify(publisher, never()).publish(any(), any());
    }

    @Test
    void check_isolatedMarkPriceMissing_skippedNoPublish() {
        priceCache.clear();
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, BTC, USDT, 500, 1000L);
        loan.collateralAmount = 1L;
        loan.outstandingPrincipal = 45_000L;
        up.isolatedLoans.put(LOAN_ID, loan);

        scanner.check(up);
        verify(publisher, never()).publish(any(), any());
    }

    @Test
    void check_crossEmpty_earlyExit_noLtvCompute() {
        // 无 crossLoans 应直接返回，不调 getLoanService 的 LTV 计算
        scanner.check(up);
        verify(publisher, never()).publish(any(), any());
    }

    @Test
    void check_crossNumeraireUnconfigured_returnsZeroLtv_noPublish() {
        // numeraireCurrency=0 sentinel → calculateCrossAccountLtvBps 保守返 0 → 不触发
        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, USDT, 500, 1000L);
        loan.outstandingPrincipal = 50_000L;
        up.crossLoans.put(LOAN_ID, loan);

        scanner.check(up);
        verify(publisher, never()).publish(any(), any());
    }

    @Test
    void check_crossInFlight_skipsLtvCompute() {
        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, USDT, 500, 1000L);
        loan.outstandingPrincipal = 50_000L;
        up.crossLoans.put(LOAN_ID, loan);

        // 先 publish 让 uid 进 in-flight
        ApiCommand cmd = mock(ApiCommand.class);
        scanner.publishTrackedCross(cmd, UID);
        assertTrue(scanner.isCrossLoanInFlight(UID));

        scanner.check(up);
        // check 期间不应再 publish
        verify(publisher).publish(any(), any());  // 1 次总量
    }

    @Test
    void check_cross_nonIdentityScale_publishesLotScaledSizeCappedAtAvailable() {
        // ★ 回归护栏（Cross）：base 币 currencyScaleK=100（digit=2）+ baseScaleK=1。
        // 构造"债务需要 4 张但只有 1 张抵押可卖"：正确应封顶到可卖张数=1；
        // 旧 calculateCrossSellSize 把 available(currencyScale=100) 当张数封顶 → 会算出 4，撮合超卖。
        final int WBTC = 10;
        final int SYMBOL_NI = 200;
        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(WBTC).name("WBTC").digit(2).build());
        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_NI).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(WBTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
                .loanInitialLtvBps(6000).loanLiquidationLtvBps(8000).loanMarginCallLtvBps(7000)
                .loanRateBps(0).collateralWeightBps(9000).build();
        specProvider.registerSymbol(SYMBOL_NI, spec);
        LastPriceCacheRecord price = new LastPriceCacheRecord();
        price.markPrice = 50_000L;
        priceCache.put(SYMBOL_NI, price);
        loanService.setNumeraireCurrency(USDT);

        // 抵押 100（= 1 WBTC = 1 张可卖），债务 150000 → 账户 LTV 远超强平线，触发 publishCrossForceSell
        up.crossLoanCollateral.put(WBTC, 100L);
        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, USDT, 0, 1000L);
        loan.outstandingPrincipal = 150_000L;
        up.crossLoans.put(LOAN_ID, loan);

        scanner.check(up);

        ArgumentCaptor<ApiCommand> captor = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher).publish(captor.capture(), any());
        ApiLoanCrossForceLiquidate cmd = (ApiLoanCrossForceLiquidate) captor.getValue();
        // 可卖张数 = collateralAmountToLots(100) = 1；债务需 4 张但只有 1 张 → 封顶 1
        long availableLots =
                LoanService.collateralAmountToLots(100L, spec, currencyProvider.getCurrencySpecification(WBTC));
        assertEquals(1L, availableLots, "100 currencyScale ÷ 100 = 1 lot（sanity）");
        assertEquals(availableLots, cmd.size,
                "cross 强平 size 必须封顶到可卖张数=1，而非把 currencyScale 抵押量当张数（旧 bug 会算出 4）");
        assertEquals(SYMBOL_NI, cmd.symbol);
    }

    @Test
    void failover_freshScanner_reSizesFromReducedState_noDoubleLiquidation() {
        // failover 幂等：证明 scanner 决策是 replicated loan 状态的纯函数、与 in-flight 记忆无关。
        // ① 同一 leader：in-flight 去重挡住重复 publish；
        // ② 新 leader（全新 scanner、空 in-flight）：按"apply 后减少的抵押"重新定 size，不会用原始量过量强平。
        final int WBTC = 10;
        final int SYMBOL_NI = 200;
        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(WBTC).name("WBTC").digit(2).build());
        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_NI).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(WBTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
                .loanInitialLtvBps(6000).loanLiquidationLtvBps(8000).loanMarginCallLtvBps(7000)
                .loanRateBps(0).collateralWeightBps(9000).build();
        specProvider.registerSymbol(SYMBOL_NI, spec);
        LastPriceCacheRecord price = new LastPriceCacheRecord();
        price.markPrice = 50_000L;
        priceCache.put(SYMBOL_NI, price);

        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, WBTC, USDT, 0, 1000L);
        loan.collateralAmount = 300L;          // 3 WBTC
        loan.outstandingPrincipal = 140_000L;  // LTV 93% ≥ 80% → underwater
        up.isolatedLoans.put(LOAN_ID, loan);

        // 原 leader 首次触发：publish 一次，size=3
        scanner.check(up);
        ArgumentCaptor<ApiCommand> cap = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher, times(1)).publish(cap.capture(), any());
        assertEquals(3L, ((ApiLoanForceLiquidate) cap.getValue()).size);

        // 同一 leader 再扫：loanId 在 in-flight → 不重复 publish
        scanner.check(up);
        verify(publisher, times(1)).publish(any(), any());

        // ===== 模拟 failover：一笔部分强平已 apply（抵押 3→1 WBTC，仍 underwater），新 leader 空 in-flight 起步 =====
        loan.collateralAmount = 100L;         // 1 WBTC 剩余
        loan.outstandingPrincipal = 45_000L;  // LTV 90% 仍 ≥ 80%
        LoanLiquidationEngine freshLeaderScanner = new LoanLiquidationEngine(engine);

        freshLeaderScanner.check(up);
        // 新 leader 会 publish（第 2 次总量），但 size 必须是剩余的 1 张，而不是原始 3 张。
        // 用独立 captor，取最后一次 publish（新 leader 那次）。
        ArgumentCaptor<ApiCommand> cap2 = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher, times(2)).publish(cap2.capture(), any());
        ApiLoanForceLiquidate reSized =
                (ApiLoanForceLiquidate) cap2.getAllValues().get(cap2.getAllValues().size() - 1);
        assertEquals(1L, reSized.size,
                "新 leader 按 apply 后剩余抵押(1 张)定 size，不会用原始 3 张 → 无双重/过量强平");
    }

    // ================================================================
    // P0-3 卡单容差爬梯 + 节流
    // ================================================================

    private IsolatedLoanRecord underwaterLoan(long loanId, int stuckAttempts) {
        // 1 BTC 抵押 45k → LTV 90% ≥ 80% liquidation
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, loanId, BTC, USDT, 0, 1000L);
        loan.collateralAmount = 1L;
        loan.outstandingPrincipal = 45_000L;
        loan.stuckLiqAttempts = stuckAttempts;
        return loan;
    }

    @Test
    void toleranceLadder_widensWithStuckAttempts() {
        // markPrice=50000。容差 1%/2%/5% → 限价 49500/49000/47500。切档 3/6。
        up.isolatedLoans.put(1L, underwaterLoan(1L, 0));  // tier0 → 1%
        up.isolatedLoans.put(2L, underwaterLoan(2L, 3));  // tier1 → 2%
        up.isolatedLoans.put(3L, underwaterLoan(3L, 6));  // tier2 → 5%

        scanner.check(up);

        ArgumentCaptor<ApiCommand> cap = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher, times(3)).publish(cap.capture(), any());
        java.util.Map<Long, Long> priceByLoan = new java.util.HashMap<>();
        for (ApiCommand c : cap.getAllValues()) {
            ApiLoanForceLiquidate f = (ApiLoanForceLiquidate) c;
            priceByLoan.put(f.loanId, f.price);
        }
        assertEquals(49_500L, priceByLoan.get(1L), "0 次卡 → 1% 容差");
        assertEquals(49_000L, priceByLoan.get(2L), "3 次卡 → 2% 容差");
        assertEquals(47_500L, priceByLoan.get(3L), "6 次卡 → 5% 容差");
    }

    @Test
    void stuckLoan_reFireThrottled_withinWindow() {
        // 卡住的 loan（attempts>0）在节流窗口内不重发。用 onApplied 清 in-flight，隔离出 throttle 逻辑。
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(1)).run();
            return null;
        }).when(publisher).publish(any(), any(Runnable.class));

        up.isolatedLoans.put(1L, underwaterLoan(1L, 1)); // 已卡 1 次

        scanner.check(up); // 第一次：未节流 → publish，记 lastLiqMs
        scanner.check(up); // 立即再扫：30s 窗口内 → 节流，不 publish

        verify(publisher, times(1)).publish(any(), any());
    }

    @Test
    void freshLoan_notThrottled_firesImmediately() {
        // 未卡的 loan（attempts=0）不走节流，首次即触发
        up.isolatedLoans.put(1L, underwaterLoan(1L, 0));
        scanner.check(up);
        verify(publisher, times(1)).publish(any(), any());
    }
}
