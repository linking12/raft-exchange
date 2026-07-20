package exchange.core2.tests.unit;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.SymbolLoanSpecification;
import exchange.core2.core.common.CrossLoanRecord;
import exchange.core2.core.common.IsolatedLoanRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.api.ApiLoanCrossForceLiquidate;
import exchange.core2.core.common.api.ApiLoanForceLiquidate;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.processors.FundEventsHelper;
import org.mockito.ArgumentCaptor;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.UserProfileService;
import exchange.core2.core.processors.liquidation.LiquidationCommandSubmitter;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LoanLiquidationEngine 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li><b>Isolated / Cross 强平判定</b>：LTV / 期限越线 → force-sell IOC，marginCall 预警（无节流），容差爬梯。</li>
 *   <li><b>targeted 索引维护</b>：onIsolatedLoanOpened / onIsolatedLoanClosed / syncCrossExposure 驱动 checkLoans 命中。</li>
 * </ul>
 *
 * <p>注：动态利率 reprice 心跳已从 check 移到父类 LiquidationScheduledService.runOneIteration()，本类不再覆盖 reprice。
 * in-flight 去重车道（LaneState / publishTracked* / 节流）已从生产代码整体删除，本类不再覆盖。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoanLiquidationEngineTest {

    private static final long UID = 42L;
    private static final long LOAN_ID = 999L;
    private static final int SYMBOL = 100;
    private static final int BTC = 1;
    private static final int USDT = 2;
    // openedAtTs 近似当前时刻（ms）：避免 loanMaxTermDays 期限强平误触发（旧 sentinel 1000L = 1970 年，ms 语义下恒过期）。
    private static final long OPENED_AT_MS = System.currentTimeMillis();

    @Mock private LiquidationCommandSubmitter publisher;
    @Mock private FundEventsHelper eventsHelper;

    private LoanService loanService;
    private UserProfile up;
    private SymbolSpecificationProvider specProvider;
    private CurrencySpecificationProvider currencyProvider;
    private UserProfileService userProfileService;
    private IntObjectHashMap<LastPriceCacheRecord> priceCache;
    private LoanLiquidationEngine scanner;

    @BeforeEach
    void setUp() {
        loanService = new LoanService();
        up = new UserProfile(UID, UserStatus.ACTIVE);
        specProvider = new SymbolSpecificationProvider();
        currencyProvider = new CurrencySpecificationProvider();
        userProfileService = new UserProfileService();
        priceCache = new IntObjectHashMap<>();

        // collateralWeightBps 现为币种级：BTC 抵押折价率 9000（原挂在 SYMBOL 的 loanConfig 上）。
        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(BTC).name("BTC").digit(0).collateralWeightBps(9000).build());
        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(USDT).name("USDT").digit(0).build());

        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(BTC).quoteCurrency(USDT)
                .baseScaleK(1).quoteScaleK(1)
                .loanConfig(SymbolLoanSpecification.builder()
                        .initialLtvBps(6000)
                        .liquidationLtvBps(8000)
                        .marginCallLtvBps(7000)
                        .maxTermDays(90)
                        .build())
                .build();
        specProvider.registerSymbol(SYMBOL, spec);

        LastPriceCacheRecord price = new LastPriceCacheRecord();
        price.markPrice = 50_000L;
        priceCache.put(SYMBOL, price);

        userProfileService.getUserProfiles().put(UID, up);

        scanner = new LoanLiquidationEngine(eventsHelper, () -> publisher);
        scanner.updateProvider(specProvider, currencyProvider, userProfileService, priceCache, loanService);
    }

    private OrderCommand priceEventCmd(int symbol) {
        return OrderCommand.testBuilder(1)
                .command(OrderCommandType.MARKPRICE_ADJUSTMENT)
                .symbol(symbol)
                .timestamp(OPENED_AT_MS)
                .build();
    }

    // ================================================================
    // checkUser() —— skeleton invariant
    // ================================================================

    @Test
    void check_emptyProfile_noThrow_noPublish() {
        scanner.checkUser(up, OPENED_AT_MS);
        verify(publisher, never()).submit(any(), any());
    }

    @Test
    void check_isolatedHealthyLtv_noPublish() {
        // 2 BTC 抵押 30k USDT → LTV = 30k/(2*50k) = 30%，远低于阈值
        // openedAtTs 用当前时刻 + rateBps=0：既不触 loanMaxTermDays 期限强平，也无 pending interest 抬 realDebt
        long nowMs = System.currentTimeMillis();
        // symbolId=SYMBOL on record：scanner 用它 getSymbolSpecification 拿 spec
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, SYMBOL, BTC, USDT, 0, nowMs);
        loan.collateralAmount = 2L;
        loan.outstandingPrincipal = 30_000L;
        up.isolatedLoans.put(LOAN_ID, loan);

        scanner.checkUser(up, OPENED_AT_MS);
        verify(publisher, never()).submit(any(), any());
    }

    @Test
    void check_isolatedLtvOverLiquidation_publishesForceSell() {
        // 1 BTC 抵押 45k USDT → LTV = 90% ≥ 80% liquidation
        // symbolId=SYMBOL on record：scanner 用它 getSymbolSpecification 拿 spec
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, SYMBOL, BTC, USDT, 500, OPENED_AT_MS);
        loan.collateralAmount = 1L;
        loan.outstandingPrincipal = 45_000L;
        up.isolatedLoans.put(LOAN_ID, loan);

        scanner.checkUser(up, OPENED_AT_MS);
        // Isolated force-sell 实装：LTV 触线时 publish 一次 ApiLiquidationOrder
        verify(publisher).submit(any(), any());
    }

    @Test
    void check_isolatedLtvInMarginCallBand_emitsMarginCallEvent() {
        // 1 BTC 抵押 37.5k USDT → LTV=75%，处于 marginCall(70%)~liquidation(80%) 之间：只发预警、不强平
        // symbolId=SYMBOL on record：scanner 用它 getSymbolSpecification 拿 spec
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, SYMBOL, BTC, USDT, 0, OPENED_AT_MS);
        loan.collateralAmount = 1L;
        loan.outstandingPrincipal = 37_500L;
        up.isolatedLoans.put(LOAN_ID, loan);

        scanner.checkUser(up, OPENED_AT_MS);

        // scanner 用正确的 LTV=7500 / 阈值=7000 / mode=0 调 helper 发 MARGIN_CALL
        verify(eventsHelper).sendLoanMarginCallEvent(eq(UID), eq(LOAN_ID), eq((byte) 0), eq(USDT), eq(7500L), eq(7000L));
    }

    @Test
    void check_isolated_identityScale_publishesSizeEqualToCollateralLots() {
        // identity scale（BTC digit=0, baseScaleK=1）：1 lot = 1 currency unit，size 应 == collateral == 1
        // symbolId=SYMBOL on record：scanner 用它 getSymbolSpecification 拿 spec
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, SYMBOL, BTC, USDT, 0, OPENED_AT_MS);
        loan.collateralAmount = 1L;
        loan.outstandingPrincipal = 45_000L; // LTV 90% ≥ 80% liquidation
        up.isolatedLoans.put(LOAN_ID, loan);

        scanner.checkUser(up, OPENED_AT_MS);

        ArgumentCaptor<ApiCommand> captor = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher).submit(captor.capture(), any());
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
        // 抵押折价率 9000 现挂在 base 币 WBTC 上（原在 SYMBOL_NI 的 loanConfig）。
        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(WBTC).name("WBTC").digit(2).collateralWeightBps(9000).build());
        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_NI).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(WBTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
                .loanConfig(SymbolLoanSpecification.builder()
                        .initialLtvBps(6000).liquidationLtvBps(8000).marginCallLtvBps(7000)
                        .build()).build();
        specProvider.registerSymbol(SYMBOL_NI, spec);
        LastPriceCacheRecord price = new LastPriceCacheRecord();
        price.markPrice = 50_000L;
        priceCache.put(SYMBOL_NI, price);

        // 抵押 300（= 3 WBTC）, 债务 140k → collateralValue = 3×50000 = 150000, LTV = 93% ≥ 80% liquidation
        // symbolId=SYMBOL_NI on record：scanner 用它 getSymbolSpecification 拿 spec
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, SYMBOL_NI, WBTC, USDT, 0, OPENED_AT_MS);
        loan.collateralAmount = 300L;
        loan.outstandingPrincipal = 140_000L;
        up.isolatedLoans.put(LOAN_ID, loan);

        scanner.checkUser(up, OPENED_AT_MS);

        ArgumentCaptor<ApiCommand> captor = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher).submit(captor.capture(), any());
        ApiLoanForceLiquidate cmd = (ApiLoanForceLiquidate) captor.getValue();
        long expectedLots =
                LoanService.collateralAmountToLots(300L, spec, currencyProvider.getCurrencySpecification(WBTC));
        assertEquals(3L, expectedLots, "300 currencyScale ÷ 100 = 3 lot（sanity）");
        assertEquals(expectedLots, cmd.size,
                "scanner 下单 size 必须是张数(lot)=3，而非 currencyScale 抵押量 300（旧 bug 会填 300）");
        assertEquals(SYMBOL_NI, cmd.symbol);
    }

    @Test
    void check_isolatedEmptyLoan_skipped() {
        // isEmpty loan（全零壳）应跳过，不查 spec、不查 markPrice、不触发
        // symbolId=SYMBOL on record：scanner 用它 getSymbolSpecification 拿 spec
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, SYMBOL, BTC, USDT, 500, OPENED_AT_MS);
        // collateralAmount=0, principal=0, interest=0 → isEmpty
        up.isolatedLoans.put(LOAN_ID, loan);

        scanner.checkUser(up, OPENED_AT_MS);
        verify(publisher, never()).submit(any(), any());
    }

    @Test
    void check_isolatedMarkPriceMissing_skippedNoPublish() {
        priceCache.clear();
        // symbolId=SYMBOL on record：scanner 用它 getSymbolSpecification 拿 spec
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, SYMBOL, BTC, USDT, 500, OPENED_AT_MS);
        loan.collateralAmount = 1L;
        loan.outstandingPrincipal = 45_000L;
        up.isolatedLoans.put(LOAN_ID, loan);

        scanner.checkUser(up, OPENED_AT_MS);
        verify(publisher, never()).submit(any(), any());
    }

    @Test
    void check_crossEmpty_earlyExit_noLtvCompute() {
        // 无 crossLoans 应直接返回，不调 getLoanService 的 LTV 计算
        scanner.checkUser(up, OPENED_AT_MS);
        verify(publisher, never()).submit(any(), any());
    }

    @Test
    void check_crossNumeraireUnconfigured_returnsZeroLtv_noPublish() {
        // numeraireCurrency=0 sentinel → calculateCrossAccountLtvBps 保守返 0 → 不触发
        // symbolId=SYMBOL on record：scanner 用它 getSymbolSpecification 拿 spec
        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, SYMBOL, USDT, 500, OPENED_AT_MS);
        loan.outstandingPrincipal = 50_000L;
        up.crossLoans.put(LOAN_ID, loan);

        scanner.checkUser(up, OPENED_AT_MS);
        verify(publisher, never()).submit(any(), any());
    }

    @Test
    void check_crossLoanExpired_notTermLiquidated_crossHasNoTerm() {
        // §13：Cross 恒 Floating → 无期限。即便 loan 已远超 90d，也不触发期限强平（pickExpiredCrossLoan 已移除）；
        // 叠加 numeraire 未配 → LTV 路径保守跳过 → 完全不 publish（旧逻辑此处会期限强平）。
        long expiredOpenedMs = OPENED_AT_MS - 91L * 86_400_000L; // 91 天前 > 90d term
        // symbolId=SYMBOL on record：scanner 用它 getSymbolSpecification 拿 spec
        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, SYMBOL, USDT, 500, expiredOpenedMs);
        loan.outstandingPrincipal = 30_000L;
        up.crossLoans.put(LOAN_ID, loan);
        up.crossLoanCollateral.put(BTC, 1L); // 1 BTC 可卖

        scanner.checkUser(up, OPENED_AT_MS);

        verify(publisher, never()).submit(any(), any());
    }

    @Test
    void check_cross_nonIdentityScale_publishesLotScaledSizeCappedAtAvailable() {
        // ★ 回归护栏（Cross）：base 币 currencyScaleK=100（digit=2）+ baseScaleK=1。
        // 构造"债务需要 4 张但只有 1 张抵押可卖"：正确应封顶到可卖张数=1；
        // 旧 calculateCrossSellSize 把 available(currencyScale=100) 当张数封顶 → 会算出 4，撮合超卖。
        final int WBTC = 10;
        final int SYMBOL_NI = 200;
        // 抵押折价率 9000 现挂在 base 币 WBTC 上（原在 SYMBOL_NI 的 loanConfig）。
        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(WBTC).name("WBTC").digit(2).collateralWeightBps(9000).build());
        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_NI).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(WBTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
                .loanConfig(SymbolLoanSpecification.builder()
                        .initialLtvBps(6000).liquidationLtvBps(8000).marginCallLtvBps(7000)
                        .build()).build();
        specProvider.registerSymbol(SYMBOL_NI, spec);
        LastPriceCacheRecord price = new LastPriceCacheRecord();
        price.markPrice = 50_000L;
        priceCache.put(SYMBOL_NI, price);
        loanService.getGlobalConfig().numeraireCurrency = USDT;

        // 抵押 100（= 1 WBTC = 1 张可卖），债务 150000 → 账户 LTV 远超强平线，触发 publishCrossForceSell
        up.crossLoanCollateral.put(WBTC, 100L);
        // symbolId=SYMBOL_NI on record：scanner 用它 getSymbolSpecification 拿 spec
        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, SYMBOL_NI, USDT, 0, OPENED_AT_MS);
        loan.outstandingPrincipal = 150_000L;
        up.crossLoans.put(LOAN_ID, loan);

        scanner.checkUser(up, OPENED_AT_MS);

        ArgumentCaptor<ApiCommand> captor = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher).submit(captor.capture(), any());
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
        // failover 幂等：证明 scanner 决策是 replicated loan 状态的纯函数、与任何本地记忆无关——
        // 部分强平已 apply（抵押 3→1 WBTC，仍 underwater）后，全新 scanner 按剩余抵押重定 size，而非用原始量过量强平。
        final int WBTC = 10;
        final int SYMBOL_NI = 200;
        // 抵押折价率 9000 现挂在 base 币 WBTC 上（原在 SYMBOL_NI 的 loanConfig）。
        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(WBTC).name("WBTC").digit(2).collateralWeightBps(9000).build());
        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_NI).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(WBTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
                .loanConfig(SymbolLoanSpecification.builder()
                        .initialLtvBps(6000).liquidationLtvBps(8000).marginCallLtvBps(7000)
                        .build()).build();
        specProvider.registerSymbol(SYMBOL_NI, spec);
        LastPriceCacheRecord price = new LastPriceCacheRecord();
        price.markPrice = 50_000L;
        priceCache.put(SYMBOL_NI, price);

        // symbolId=SYMBOL_NI on record：scanner 用它 getSymbolSpecification 拿 spec
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, SYMBOL_NI, WBTC, USDT, 0, OPENED_AT_MS);
        loan.collateralAmount = 300L;          // 3 WBTC
        loan.outstandingPrincipal = 140_000L;  // LTV 93% ≥ 80% → underwater
        up.isolatedLoans.put(LOAN_ID, loan);

        // 第一次触发：publish 一次，size=3
        scanner.checkUser(up, OPENED_AT_MS);
        ArgumentCaptor<ApiCommand> cap = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher, times(1)).submit(cap.capture(), any());
        assertEquals(3L, ((ApiLoanForceLiquidate) cap.getValue()).size);

        // ===== 模拟 failover：一笔部分强平已 apply（抵押 3→1 WBTC，仍 underwater），新 leader 用全新 scanner 起步 =====
        loan.collateralAmount = 100L;         // 1 WBTC 剩余
        loan.outstandingPrincipal = 45_000L;  // LTV 90% 仍 ≥ 80%
        LoanLiquidationEngine freshLeaderScanner = new LoanLiquidationEngine(eventsHelper, () -> publisher);
        freshLeaderScanner.updateProvider(specProvider, currencyProvider, userProfileService, priceCache, loanService);

        freshLeaderScanner.checkUser(up, OPENED_AT_MS);
        // 新 leader 会 publish（第 2 次总量），但 size 必须是剩余的 1 张，而不是原始 3 张。
        ArgumentCaptor<ApiCommand> cap2 = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher, times(2)).submit(cap2.capture(), any());
        ApiLoanForceLiquidate reSized =
                (ApiLoanForceLiquidate) cap2.getAllValues().get(cap2.getAllValues().size() - 1);
        assertEquals(1L, reSized.size,
                "新 leader 按 apply 后剩余抵押(1 张)定 size，不会用原始 3 张 → 无双重/过量强平");
    }

    // ================================================================
    // 破产价定价（单次报价，无爬梯）
    // ================================================================

    private IsolatedLoanRecord underwaterLoan(long loanId) {
        // 1 BTC 抵押 45k → LTV 90% ≥ 80% liquidation
        // symbolId=SYMBOL on record：scanner 用它 getSymbolSpecification 拿 spec
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, loanId, SYMBOL, BTC, USDT, 0, OPENED_AT_MS);
        loan.collateralAmount = 1L;
        loan.outstandingPrincipal = 45_000L;
        return loan;
    }

    @Test
    void forceSellLimitPrice_isBankruptcyPrice() {
        // 1 BTC 抵押、债 45k、markPrice 50k → 抵押估值 50k、LTV 90%
        // 破产价 = markPrice × 债务 / 抵押估值 = 50000 × 45000 / 50000 = 45000（= LTV × markPrice）
        up.isolatedLoans.put(1L, underwaterLoan(1L));

        scanner.checkUser(up, OPENED_AT_MS);

        ArgumentCaptor<ApiCommand> cap = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher).submit(cap.capture(), any());
        ApiLoanForceLiquidate f = (ApiLoanForceLiquidate) cap.getValue();
        assertEquals(45_000L, f.price, "限价 = 破产价：卖到此价所得刚好覆盖债务，更低则池子亏损");
    }

    /** 2 BTC 抵押（markPrice 50k → 市值 100k）+ 债 76.5k，强平线 8500；weight 由入参定（设到 BTC 币种上），只影响加权 LTV。 */
    private CrossLoanRecord seedCrossAtLiquidationLine(int collateralWeightBps) {
        loanService.getGlobalConfig().numeraireCurrency = USDT;
        loanService.getGlobalConfig().crossLiquidationLtvBps = 8500;
        // weight 现为币种级：把入参折价率设到 base 币 BTC 上（原挂在 SYMBOL 的 loanConfig）。
        currencyProvider.getCurrencySpecification(BTC).updateCollateralWeight(collateralWeightBps);
        specProvider.registerSymbol(SYMBOL, CoreSymbolSpecification.builder()
            .symbolId(SYMBOL).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
            .baseCurrency(BTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
            .loanConfig(SymbolLoanSpecification.builder().initialLtvBps(6000).liquidationLtvBps(8000)
                .marginCallLtvBps(7000).maxTermDays(90).build())
            .build());
        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, SYMBOL, USDT, 0, OPENED_AT_MS);
        loan.outstandingPrincipal = 76_500L;
        up.crossLoans.put(LOAN_ID, loan);
        up.crossLoanCollateral.put(BTC, 2L);
        return loan;
    }

    private ApiLoanCrossForceLiquidate captureCrossForceSell() {
        ArgumentCaptor<ApiCommand> cap = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher).submit(cap.capture(), any());
        return (ApiLoanCrossForceLiquidate) cap.getValue();
    }

    @Test
    void crossForceSellLimitPrice_isBankruptcyPrice_onMarketValueNotWeighted() {
        // weight 9000 → 加权 LTV = 76.5k/90k = 8500 恰好越线；市值 LTV = 76.5k/100k = 7650 → 破产价 38250。
        // 误用加权 LTV 会得 42500，报价高 11%、白白降低成交概率。
        seedCrossAtLiquidationLine(9000);

        scanner.checkUser(up, OPENED_AT_MS);

        ApiLoanCrossForceLiquidate f = captureCrossForceSell();
        assertEquals(38_250L, f.price, "破产价按抵押市值算（7650 bps），不能按加权 LTV（8500 bps）算");
        assertEquals(2L, f.size, "卖量按破产价定：2 × 38250 = 76500 恰好覆盖债务");
    }

    @Test
    void crossForceSellLimitPrice_weightDoesNotLeakIntoPrice() {
        // 同一份抵押市值、同一笔债，只把 weight 从 90% 降到 75%：weight 只该影响「该不该强平」，不该影响「卖多少钱」。
        // 加权 LTV 从 8500 涨到 10200——若拿它定价会得 51000 > markPrice 50000，卖单挂到市价之上、永不成交，
        // 而此时抵押市值仍是债务的 1.31 倍，本该轻松卖掉，却只能一路走到 LIF 接管。
        seedCrossAtLiquidationLine(7500);

        scanner.checkUser(up, OPENED_AT_MS);

        ApiLoanCrossForceLiquidate f = captureCrossForceSell();
        assertEquals(38_250L, f.price, "weight 变了但抵押市值没变，破产价必须不变");
        assertTrue(f.price < 50_000L, "破产价必须低于 markPrice，否则挂到市价之上永不成交");
    }

    @Test
    void bankruptcyPrice_scalesWithDebt_notFixedDiscount() {
        // 同抵押不同债务 → 破产价随债务线性变化，证明它不是固定折扣
        IsolatedLoanRecord light = underwaterLoan(1L);
        light.outstandingPrincipal = 40_000L; // LTV 80% → 破产价 40000
        IsolatedLoanRecord heavy = underwaterLoan(2L);
        heavy.outstandingPrincipal = 49_000L; // LTV 98% → 破产价 49000
        up.isolatedLoans.put(1L, light);
        up.isolatedLoans.put(2L, heavy);

        scanner.checkUser(up, OPENED_AT_MS);

        ArgumentCaptor<ApiCommand> cap = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher, times(2)).submit(cap.capture(), any());
        java.util.Map<Long, Long> priceByLoan = new java.util.HashMap<>();
        for (ApiCommand c : cap.getAllValues()) {
            ApiLoanForceLiquidate f = (ApiLoanForceLiquidate) c;
            priceByLoan.put(f.loanId, f.price);
        }
        assertEquals(40_000L, priceByLoan.get(1L), "债越轻，破产价越低、安全垫越厚");
        assertEquals(49_000L, priceByLoan.get(2L), "债越重，破产价越贴近市价");
    }

    @Test
    void freshLoan_notThrottled_firesImmediately() {
        // 未卡的 loan（attempts=0）不走节流，首次即触发；现无节流窗口，连续两次扫描各发一次
        up.isolatedLoans.put(1L, underwaterLoan(1L));
        scanner.checkUser(up, OPENED_AT_MS);
        verify(publisher, times(1)).submit(any(), any());

        scanner.checkUser(up, OPENED_AT_MS);
        verify(publisher, times(2)).submit(any(), any());
    }

    // ================================================================
    // P0 补覆盖：Isolated 期限强平 / Cross force-sell abort 路径
    // ================================================================

    @Test
    void check_isolatedTermExpired_lockedLoan_forcesSellRegardlessOfLtv() {
        // LOCKED 贷款开仓于 91 天前 > maxTermDays(90) → 到期强平，即便 LTV 健康(30%)
        long openedMs = System.currentTimeMillis() - 91L * 86_400_000L;
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, SYMBOL, BTC, USDT, 0, openedMs);
        loan.rateMode = IsolatedLoanRecord.RATE_MODE_LOCKED;
        loan.collateralAmount = 2L;
        loan.outstandingPrincipal = 30_000L; // LTV 30% << liq 80%，仅靠期限触发
        up.isolatedLoans.put(LOAN_ID, loan);

        scanner.checkUser(up, System.currentTimeMillis());
        verify(publisher).submit(any(), any());
    }

    @Test
    void check_isolatedTermExpired_floatingLoan_notTermLiquidated() {
        // FLOATING 无期限：同样 91 天前开仓 + 健康 LTV → 不强平（期限只对 LOCKED 生效）
        long openedMs = System.currentTimeMillis() - 91L * 86_400_000L;
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, SYMBOL, BTC, USDT, 0, openedMs);
        loan.rateMode = IsolatedLoanRecord.RATE_MODE_FLOATING;
        loan.collateralAmount = 2L;
        loan.outstandingPrincipal = 30_000L;
        up.isolatedLoans.put(LOAN_ID, loan);

        scanner.checkUser(up, System.currentTimeMillis());
        verify(publisher, never()).submit(any(), any());
    }

    /** 用 spy 把 Cross LTV 钉在强平线上,以隔离测 publishCrossForceSell 内各 abort 分支。 */
    private LoanService spyWithCrossLtvOverLiquidation() {
        loanService.getGlobalConfig().crossLiquidationLtvBps = 9000;
        loanService.getGlobalConfig().numeraireCurrency = USDT; // Cross 账户不可能在 numeraire 未配时存在
        LoanService spied = spy(loanService);
        doReturn(9999L).when(spied)
            .calculateCrossAccountLtvBps(any(), anyLong(), any(), any(), any(), anyInt());
        scanner.updateProvider(specProvider, currencyProvider, userProfileService, priceCache, spied);
        return spied;
    }

    @Test
    void check_crossOverLiquidation_fullyValidSetup_publishes_provesSpyForcesEntry() {
        // 阳性对照：同一 spy 强制 LTV 越线 + 全部条件齐备（可卖抵押 + 有债 loan + 现货对 + markPrice）→ 必 publish。
        // 证明 spy 确实把流程带进 publishCrossForceSell，从而下面各 abort 用例的 never-publish 是真的走了 abort，而非未进入。
        spyWithCrossLtvOverLiquidation();
        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, SYMBOL, USDT, 500, OPENED_AT_MS);
        loan.outstandingPrincipal = 50_000L;
        up.crossLoans.put(LOAN_ID, loan);
        up.crossLoanCollateral.put(BTC, 2L);

        scanner.checkUser(up, OPENED_AT_MS);
        verify(publisher).submit(any(), any());
    }

    @Test
    void check_crossOverLiquidation_noSellableCollateral_abortsNoPublish() {
        // 穿仓但无任何可卖抵押 → sellingCurrency==0 → abort，不 publish（应走坏账而非强平）
        spyWithCrossLtvOverLiquidation();
        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, SYMBOL, USDT, 500, OPENED_AT_MS);
        loan.outstandingPrincipal = 50_000L;
        up.crossLoans.put(LOAN_ID, loan);
        // crossLoanCollateral 空

        scanner.checkUser(up, OPENED_AT_MS);
        verify(publisher, never()).submit(any(), any());
    }

    @Test
    void check_crossOverLiquidation_noTargetLoanWithDebt_abortsNoPublish() {
        // 有可卖抵押但无一笔有余额的 loan → targetLoan==null → abort
        spyWithCrossLtvOverLiquidation();
        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, SYMBOL, USDT, 500, OPENED_AT_MS);
        loan.outstandingPrincipal = 0L; // 无债 → pickCrossLoanToRepay 跳过
        up.crossLoans.put(LOAN_ID, loan);
        up.crossLoanCollateral.put(BTC, 1L);

        scanner.checkUser(up, OPENED_AT_MS);
        verify(publisher, never()).submit(any(), any());
    }

    @Test
    void check_crossOverLiquidation_noSpotPair_abortsNoPublish() {
        // 抵押 BTC 有权重可卖，但目标 loan 币种(ETH) 无 BTC/ETH 现货对 → findSpotSymbol null → abort
        final int ETH = 3;
        spyWithCrossLtvOverLiquidation();
        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, SYMBOL, ETH, 500, OPENED_AT_MS);
        loan.outstandingPrincipal = 50_000L;
        up.crossLoans.put(LOAN_ID, loan);
        up.crossLoanCollateral.put(BTC, 1L);

        scanner.checkUser(up, OPENED_AT_MS);
        verify(publisher, never()).submit(any(), any());
    }

    @Test
    void check_crossOverLiquidation_markPriceNotReady_abortsNoPublish() {
        // 找得到现货对(BTC/USDT)但该 symbol 无 markPrice → abort
        spyWithCrossLtvOverLiquidation();
        priceCache.remove(SYMBOL);
        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, SYMBOL, USDT, 500, OPENED_AT_MS);
        loan.outstandingPrincipal = 50_000L;
        up.crossLoans.put(LOAN_ID, loan);
        up.crossLoanCollateral.put(BTC, 1L);

        scanner.checkUser(up, OPENED_AT_MS);
        verify(publisher, never()).submit(any(), any());
    }

    @Test
    void check_crossOverLiquidation_topPickHasNoMarket_fallsBackToSellablePair_publishes() {
        // ★ 卡死回归护栏：最高息 loan 币种(ETH) 无 BTC/ETH 现货对，但存在 BTC/USDT 现货对可偿还次高息的 USDT loan。
        // 修复前：pickCrossLoanToRepay 只选最高息 ETH → findSpotSymbol 空 → 每次价格事件都重挑同一对、永久 abort，
        //         坏账永不结算、scanner 空转。修复后：回退到存在市场的(卖 BTC, 偿 USDT loan)组合 → publish。
        final int ETH = 3;
        spyWithCrossLtvOverLiquidation();

        CrossLoanRecord ethLoan = new CrossLoanRecord(UID, LOAN_ID, SYMBOL, ETH, 800, OPENED_AT_MS); // 最高息、无 BTC/ETH 市场
        ethLoan.outstandingPrincipal = 50_000L;
        up.crossLoans.put(LOAN_ID, ethLoan);

        CrossLoanRecord usdtLoan = new CrossLoanRecord(UID, LOAN_ID + 1, SYMBOL, USDT, 500, OPENED_AT_MS); // 次高息、有 BTC/USDT 市场
        usdtLoan.outstandingPrincipal = 50_000L;
        up.crossLoans.put(LOAN_ID + 1, usdtLoan);

        up.crossLoanCollateral.put(BTC, 2L); // 唯一抵押 BTC；BTC/USDT 现货对存在（setUp）

        scanner.checkUser(up, OPENED_AT_MS);

        ArgumentCaptor<ApiCommand> captor = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher).submit(captor.capture(), any());
        ApiLoanCrossForceLiquidate cmd = (ApiLoanCrossForceLiquidate) captor.getValue();
        assertEquals(SYMBOL, cmd.symbol, "回退到有市场的 BTC/USDT 现货对");
        assertEquals(LOAN_ID + 1, cmd.targetLoanId, "回退到可偿还的 USDT loan（非最高息但有市场）");
    }

    @Test
    void check_crossOverLiquidation_topCollateralHasNoMarket_fallsBackToLowerWeightCollateral_publishes() {
        // ★ 抵押级回退：最高权重抵押 XRP 偿不了任何债（无 XRP/USDT 现货对），退到次高权重 BTC（BTC/USDT 存在）。
        final int XRP = 4;
        final int SYMBOL_XRP_BTC = 102;
        spyWithCrossLtvOverLiquidation();
        // XRP/BTC 现货对：给 XRP 抵押权重 9500（> BTC 的 9000），但 quote=BTC 不是用户持有的债币(USDT)。
        // weight 现为币种级：折价率 9500 设到 base 币 XRP 上（原在 xrpBtc 的 loanConfig）。
        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(XRP).name("XRP").digit(0).collateralWeightBps(9500).build());
        CoreSymbolSpecification xrpBtc = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_XRP_BTC).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(XRP).quoteCurrency(BTC).baseScaleK(1).quoteScaleK(1)
                .loanConfig(SymbolLoanSpecification.builder()
                        .initialLtvBps(6000).liquidationLtvBps(8000).marginCallLtvBps(7000)
                        .build())
                .build();
        specProvider.registerSymbol(SYMBOL_XRP_BTC, xrpBtc);

        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, SYMBOL, USDT, 500, OPENED_AT_MS);
        loan.outstandingPrincipal = 50_000L;
        up.crossLoans.put(LOAN_ID, loan);
        up.crossLoanCollateral.put(XRP, 100L); // 最高权重，但无 XRP/USDT 市场
        up.crossLoanCollateral.put(BTC, 2L);   // 次高权重，有 BTC/USDT 市场

        scanner.checkUser(up, OPENED_AT_MS);

        ArgumentCaptor<ApiCommand> captor = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher).submit(captor.capture(), any());
        ApiLoanCrossForceLiquidate cmd = (ApiLoanCrossForceLiquidate) captor.getValue();
        assertEquals(SYMBOL, cmd.symbol, "最高权重 XRP 无市场 → 退到次高权重 BTC/USDT");
    }

    @Test
    void check_crossOverLiquidation_topLoanMarkPriceNotReady_fallsBackToReadyPair_publishes() {
        // ★ markPrice 前置约束：最高息 loan(C) 的 BTC/C 现货对存在但 markPrice 未就绪 → 视作不可成交，退到就绪的 USDT loan。
        final int C = 5;
        final int SYMBOL_BTC_C = 103;
        spyWithCrossLtvOverLiquidation();
        // base 币是 BTC（其抵押折价率 9000 已在 setUp 设好），C 是 quote 债币、无需权重。
        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(C).name("C").digit(0).build());
        CoreSymbolSpecification btcC = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_BTC_C).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(BTC).quoteCurrency(C).baseScaleK(1).quoteScaleK(1)
                .loanConfig(SymbolLoanSpecification.builder()
                        .initialLtvBps(6000).liquidationLtvBps(8000).marginCallLtvBps(7000)
                        .build())
                .build();
        specProvider.registerSymbol(SYMBOL_BTC_C, btcC);
        // 故意不为 SYMBOL_BTC_C 放 markPrice → 该现货对不就绪

        CrossLoanRecord cLoan = new CrossLoanRecord(UID, LOAN_ID, SYMBOL, C, 800, OPENED_AT_MS); // 最高息、市场未就绪
        cLoan.outstandingPrincipal = 50_000L;
        up.crossLoans.put(LOAN_ID, cLoan);
        CrossLoanRecord usdtLoan = new CrossLoanRecord(UID, LOAN_ID + 1, SYMBOL, USDT, 500, OPENED_AT_MS); // 次高息、就绪
        usdtLoan.outstandingPrincipal = 50_000L;
        up.crossLoans.put(LOAN_ID + 1, usdtLoan);
        up.crossLoanCollateral.put(BTC, 2L);

        scanner.checkUser(up, OPENED_AT_MS);

        ArgumentCaptor<ApiCommand> captor = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher).submit(captor.capture(), any());
        ApiLoanCrossForceLiquidate cmd = (ApiLoanCrossForceLiquidate) captor.getValue();
        assertEquals(SYMBOL, cmd.symbol, "退到就绪的 BTC/USDT 现货对");
        assertEquals(LOAN_ID + 1, cmd.targetLoanId, "最高息 loan 的现货对 markPrice 未就绪 → 退到就绪的 USDT loan");
    }

    // ================================================================
    // Cross tiebreak selectors: pickCrossCollateralToSell / pickCrossLoanToRepay
    // 全部用 spyWithCrossLtvOverLiquidation 强制进 publishCrossForceSell，
    // 再用 ArgumentCaptor 取 ApiLoanCrossForceLiquidate 反推所选卖出币 / 还款笔：
    //   - symbol = findSpotSymbol(sellingCurrency, loanCurrency) 的 symbolId；loanCurrency 恒 USDT，
    //     故 symbol 唯一指向所选卖出币（BTC/USDT vs ETH/USDT）。
    //   - targetLoanId = 所选还款 loan 的 loanId。
    // ================================================================

    /** ETH/USDT 现货对：既给 ETH 抵押权重（币种级）、又作 ETH 卖 USDT 的现货对。 */
    private int registerEthUsdtSpot(int ethCurrency, int symbolId, int weightBps) {
        // weight 现为币种级：折价率 weightBps 设到 base 币 ETH 上（原在 spec 的 loanConfig）。
        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(ethCurrency).name("ETH").digit(0).collateralWeightBps(weightBps).build());
        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(symbolId).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(ethCurrency).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
                .loanConfig(SymbolLoanSpecification.builder()
                        .initialLtvBps(6000).liquidationLtvBps(8000).marginCallLtvBps(7000)
                        .build())
                .build();
        specProvider.registerSymbol(symbolId, spec);
        LastPriceCacheRecord price = new LastPriceCacheRecord();
        price.markPrice = 50_000L;
        priceCache.put(symbolId, price);
        return symbolId;
    }

    @Test
    void check_crossPickCollateral_higherWeightCurrencySold() {
        // 两抵押币：BTC/USDT weight 9000 vs ETH/USDT weight 5000 → 选高权重 BTC 卖出。
        final int ETH = 3;
        final int SYMBOL_ETH = 101;
        spyWithCrossLtvOverLiquidation();
        registerEthUsdtSpot(ETH, SYMBOL_ETH, 5000); // 低权重

        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, SYMBOL, USDT, 500, OPENED_AT_MS);
        loan.outstandingPrincipal = 50_000L;
        up.crossLoans.put(LOAN_ID, loan);
        up.crossLoanCollateral.put(BTC, 2L); // weight 9000（setUp 的 BTC）
        up.crossLoanCollateral.put(ETH, 2L); // weight 5000

        scanner.checkUser(up, OPENED_AT_MS);

        ArgumentCaptor<ApiCommand> captor = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher).submit(captor.capture(), any());
        ApiLoanCrossForceLiquidate cmd = (ApiLoanCrossForceLiquidate) captor.getValue();
        assertEquals(SYMBOL, cmd.symbol, "高权重 BTC(9000) 优先卖 → symbol=BTC/USDT 现货对");
    }

    @Test
    void check_crossPickCollateral_equalWeightHigherAmountSold() {
        // 两抵押币权重相等(9000)：BTC amount 1 vs ETH amount 5 → amount 大者 ETH 胜出。
        final int ETH = 3;
        final int SYMBOL_ETH = 101;
        spyWithCrossLtvOverLiquidation();
        registerEthUsdtSpot(ETH, SYMBOL_ETH, 9000); // 与 BTC 同权重

        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, SYMBOL, USDT, 500, OPENED_AT_MS);
        loan.outstandingPrincipal = 50_000L;
        up.crossLoans.put(LOAN_ID, loan);
        up.crossLoanCollateral.put(BTC, 1L); // 权重同，量小
        up.crossLoanCollateral.put(ETH, 5L); // 权重同，量大 → 胜

        scanner.checkUser(up, OPENED_AT_MS);

        ArgumentCaptor<ApiCommand> captor = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher).submit(captor.capture(), any());
        ApiLoanCrossForceLiquidate cmd = (ApiLoanCrossForceLiquidate) captor.getValue();
        assertEquals(SYMBOL_ETH, cmd.symbol, "权重相等时高抵押量 ETH 胜出 → symbol=ETH/USDT 现货对");
    }

    @Test
    void check_crossPickLoan_higherRateLoanRepaid() {
        // 两笔 Cross loan（同币种 USDT），rate 500 vs 800 → 选高利率笔偿还。
        final long LOAN_LO = LOAN_ID;      // rate 500
        final long LOAN_HI = LOAN_ID + 1;  // rate 800 → 应选中
        spyWithCrossLtvOverLiquidation();

        CrossLoanRecord loanLo = new CrossLoanRecord(UID, LOAN_LO, SYMBOL, USDT, 500, OPENED_AT_MS);
        loanLo.outstandingPrincipal = 50_000L;
        up.crossLoans.put(LOAN_LO, loanLo);

        CrossLoanRecord loanHi = new CrossLoanRecord(UID, LOAN_HI, SYMBOL, USDT, 800, OPENED_AT_MS);
        loanHi.outstandingPrincipal = 50_000L;
        up.crossLoans.put(LOAN_HI, loanHi);

        up.crossLoanCollateral.put(BTC, 2L);

        scanner.checkUser(up, OPENED_AT_MS);

        ArgumentCaptor<ApiCommand> captor = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher).submit(captor.capture(), any());
        ApiLoanCrossForceLiquidate cmd = (ApiLoanCrossForceLiquidate) captor.getValue();
        assertEquals(LOAN_HI, cmd.targetLoanId, "高利率(800)笔优先偿还");
    }

    @Test
    void check_crossPickLoan_equalRateHigherPrincipalRepaid() {
        // 两笔同利率(500) Cross loan：principal 30k vs 60k → 选本金大者偿还。
        final long LOAN_SMALL = LOAN_ID;      // principal 30k
        final long LOAN_BIG = LOAN_ID + 1;    // principal 60k → 应选中
        spyWithCrossLtvOverLiquidation();

        CrossLoanRecord loanSmall = new CrossLoanRecord(UID, LOAN_SMALL, SYMBOL, USDT, 500, OPENED_AT_MS);
        loanSmall.outstandingPrincipal = 30_000L;
        up.crossLoans.put(LOAN_SMALL, loanSmall);

        CrossLoanRecord loanBig = new CrossLoanRecord(UID, LOAN_BIG, SYMBOL, USDT, 500, OPENED_AT_MS);
        loanBig.outstandingPrincipal = 60_000L;
        up.crossLoans.put(LOAN_BIG, loanBig);

        up.crossLoanCollateral.put(BTC, 2L);

        scanner.checkUser(up, OPENED_AT_MS);

        ArgumentCaptor<ApiCommand> captor = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher).submit(captor.capture(), any());
        ApiLoanCrossForceLiquidate cmd = (ApiLoanCrossForceLiquidate) captor.getValue();
        assertEquals(LOAN_BIG, cmd.targetLoanId, "利率相等时高本金(60k)笔优先偿还");
    }

    // ================================================================
    // 索引维护：onIsolatedLoanOpened / syncCrossExposure 驱动 checkLoans targeted 命中
    // ================================================================

    @Test
    void checkLoans_isolatedIndexed_targetedHitsUnderwaterUser() {
        // up 有一笔 underwater isolated loan；索引未登记前 checkLoans 不命中，登记后命中。
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, SYMBOL, BTC, USDT, 0, OPENED_AT_MS);
        loan.collateralAmount = 1L;
        loan.outstandingPrincipal = 45_000L; // LTV 90% ≥ 80% liquidation
        up.isolatedLoans.put(LOAN_ID, loan);

        OrderCommand cmd = priceEventCmd(SYMBOL);

        // 未登记索引：targeted 查不到该 uid，不 publish
        scanner.checkLoans(cmd);
        verify(publisher, never()).submit(any(), any());

        // 登记索引后：targeted 命中，publish force-sell
        scanner.onIsolatedLoanOpened(UID, SYMBOL);
        scanner.checkLoans(cmd);
        verify(publisher).submit(any(), any());
    }

    @Test
    void checkLoans_crossIndexed_targetedHitsViaCollateralCurrencySymbol() {
        // up 有 cross 抵押 + 借款，LTV 被 spy 钉在强平线上；抵押币(BTC)所在现货对(SYMBOL=BTC/USDT) 触发的价格事件应命中。
        spyWithCrossLtvOverLiquidation();
        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, SYMBOL, USDT, 500, OPENED_AT_MS);
        loan.outstandingPrincipal = 50_000L;
        up.crossLoans.put(LOAN_ID, loan);
        up.crossLoanCollateral.put(BTC, 2L);

        OrderCommand cmd = priceEventCmd(SYMBOL);

        // syncCrossExposure 之前：索引未登记，targeted 查不到该 uid
        scanner.checkLoans(cmd);
        verify(publisher, never()).submit(any(), any());

        // syncCrossExposure 登记后：BTC/USDT 价格事件（base=BTC 命中 crossLoanCurrencyToUsers[BTC]）targeted 命中
        scanner.syncCrossExposure(up);
        scanner.checkLoans(cmd);
        verify(publisher).submit(any(), any());
    }

    @Test
    void checkLoans_isolatedClosed_holdsOther_retainsUid() {
        // 同 SYMBOL 两笔 underwater loan；关掉一笔后另一笔仍非空 → holdsOther=true → 不摘除，targeted 仍命中。
        up.isolatedLoans.put(1L, underwaterLoan(1L));
        up.isolatedLoans.put(2L, underwaterLoan(2L));
        scanner.onIsolatedLoanOpened(UID, SYMBOL);

        up.isolatedLoans.remove(1L);
        scanner.onIsolatedLoanClosed(up, SYMBOL); // loan 2 仍在同 symbol 非空 → 保留 UID

        clearInvocations(publisher);
        scanner.checkLoans(priceEventCmd(SYMBOL));
        verify(publisher).submit(any(), any()); // 仍在索引 → 剩余 loan 被 force-sell
    }

    @Test
    void checkLoans_isolatedClosed_lastLoanOnSymbol_dropsFromThatSymbolTargeting() {
        // 用两个 symbol 观测摘除：UID 在 SYMBOL、SYMBOL2 各一笔 underwater。关掉 SYMBOL 上那笔后，
        // UID 从 SYMBOL 索引摘除（SYMBOL 上再无非空 loan），但仍在 SYMBOL2 索引。
        final int SYMBOL2 = 300;
        CoreSymbolSpecification spec2 = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL2).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(BTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
                .loanConfig(SymbolLoanSpecification.builder()
                        .initialLtvBps(6000).liquidationLtvBps(8000).marginCallLtvBps(7000)
                        .build())
                .build();
        specProvider.registerSymbol(SYMBOL2, spec2);
        LastPriceCacheRecord price2 = new LastPriceCacheRecord();
        price2.markPrice = 50_000L;
        priceCache.put(SYMBOL2, price2);

        up.isolatedLoans.put(1L, underwaterLoan(1L)); // symbolId=SYMBOL
        IsolatedLoanRecord loanB = underwaterLoan(2L);
        loanB.symbolId = SYMBOL2;
        up.isolatedLoans.put(2L, loanB);
        scanner.onIsolatedLoanOpened(UID, SYMBOL);
        scanner.onIsolatedLoanOpened(UID, SYMBOL2);

        up.isolatedLoans.remove(1L);
        scanner.onIsolatedLoanClosed(up, SYMBOL); // SYMBOL 上无其它非空 loan → 摘除 UID

        clearInvocations(publisher);
        // SYMBOL 价格事件：UID 已摘除 → 不命中，不 force-sell（尽管 UID 仍有 SYMBOL2 的 underwater loan）
        scanner.checkLoans(priceEventCmd(SYMBOL));
        verify(publisher, never()).submit(any(), any());
        // SYMBOL2 价格事件：UID 仍在 SYMBOL2 索引 → 命中 → force-sell
        scanner.checkLoans(priceEventCmd(SYMBOL2));
        verify(publisher).submit(any(), any());
    }

    @Test
    void checkLoans_backstop_scansUnindexedUser() {
        // backstop（cmd.symbol < 0）遍历 userProfileService，即便索引未登记也能捞到 underwater loan。
        up.isolatedLoans.put(LOAN_ID, underwaterLoan(LOAN_ID));
        // 故意不调 onIsolatedLoanOpened → 索引为空

        scanner.checkLoans(priceEventCmd(-1));
        verify(publisher).submit(any(), any()); // backstop 全扫命中，与索引无关
    }
}
