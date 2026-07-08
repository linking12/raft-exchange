package exchange.core2.tests.unit;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.CrossLoanRecord;
import exchange.core2.core.common.IsolatedLoanRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.liquidation.LiquidationService;
import exchange.core2.core.processors.loan.LoanService;
import net.openhft.chronicle.bytes.Bytes;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LoanService 单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>惰性计息 accrueTo（Isolated + Cross，含溢出保护 / 时钟倒退 / 累计）</li>
 *   <li>只读 calculateDisplayInterest（不修改 loan record）</li>
 *   <li>池子利用率 metric</li>
 *   <li>calculateEffectiveRateBps v1 stub + calculateCrossAccountLtvBps v1 skeleton</li>
 *   <li>force-sell OrderId 编码 / 分类（'L' 命名空间 + 'S'/'C' subtype，跟 IF 'I' / ADL 'A' 独占）</li>
 *   <li>reset + snapshot round-trip</li>
 * </ul>
 */
class LoanServiceTest {

    private LoanService svc;

    @BeforeEach
    void setUp() {
        svc = new LoanService();
    }

    // ================================================================
    // accrueTo(IsolatedLoanRecord)
    // 公式: interest = elapsed_ns × principal × rateBps / (YEAR_NS × 10000)
    // ================================================================

    @Test
    void accrueTo_isolated_fullYear_100pctRate_matchesPrincipal() {
        // 100% APR 一整年应该产生 100% 本金的利息
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 10000, 0L);
        loan.outstandingPrincipal = 1_000_000L;

        long delta = svc.accrueTo(loan, LoanService.YEAR_NS);

        assertEquals(1_000_000L, delta, "100% APR 全年 = 本金");
        assertEquals(1_000_000L, loan.accumulatedInterest);
        assertEquals(LoanService.YEAR_NS, loan.lastAccrueTs);
    }

    @Test
    void accrueTo_isolated_halfYear_halfInterest() {
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 10000, 0L);
        loan.outstandingPrincipal = 1_000_000L;

        long delta = svc.accrueTo(loan, LoanService.YEAR_NS / 2);

        assertEquals(500_000L, delta, "100% APR 半年 = 半本金");
    }

    @Test
    void accrueTo_isolated_quarterYear_5pctRate_matchesFormula() {
        // 5% APR × 90 天 ≈ 1.25% 本金；用两步 truncMulDiv 验证不出现截断偏差
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 500, 0L);
        loan.outstandingPrincipal = 30_000_000L;

        long delta = svc.accrueTo(loan, LoanService.YEAR_NS / 4);

        // 期望: 30_000_000 × 5% / 4 = 375_000
        assertEquals(375_000L, delta);
    }

    @Test
    void accrueTo_isolated_zeroPrincipal_noInterest() {
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 500, 0L);
        loan.outstandingPrincipal = 0L;

        long delta = svc.accrueTo(loan, LoanService.YEAR_NS);

        assertEquals(0L, delta);
        assertEquals(0L, loan.accumulatedInterest);
        // 即使无 principal，lastAccrueTs 仍推进（避免下次 accrue 从太老的时间点算）
        assertEquals(LoanService.YEAR_NS, loan.lastAccrueTs);
    }

    @Test
    void accrueTo_isolated_zeroRate_noInterest() {
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 0, 0L);
        loan.outstandingPrincipal = 1_000_000L;

        long delta = svc.accrueTo(loan, LoanService.YEAR_NS);

        assertEquals(0L, delta);
        assertEquals(LoanService.YEAR_NS, loan.lastAccrueTs, "免息也推 lastAccrueTs");
    }

    @Test
    void accrueTo_isolated_zeroElapsed_noopNoAdvance() {
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 500, 5000L);
        loan.outstandingPrincipal = 1_000_000L;

        long delta = svc.accrueTo(loan, 5000L);

        assertEquals(0L, delta);
        assertEquals(5000L, loan.lastAccrueTs, "同一时间点重复 accrue 不推进");
    }

    @Test
    void accrueTo_isolated_clockBackwards_noopNoRegression() {
        // 时钟倒退保护：now < lastAccrueTs 时 accrue 为 0，lastAccrueTs 不倒退
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 500, 10_000L);
        loan.outstandingPrincipal = 1_000_000L;

        long delta = svc.accrueTo(loan, 5000L);

        assertEquals(0L, delta);
        assertEquals(10_000L, loan.lastAccrueTs, "时钟倒退时 lastAccrueTs 不倒退");
    }

    @Test
    void accrueTo_isolated_cumulative_addsToExistingAccrued() {
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 10000, 0L);
        loan.outstandingPrincipal = 1_000_000L;

        svc.accrueTo(loan, LoanService.YEAR_NS / 2);   // +500_000
        long delta2 = svc.accrueTo(loan, LoanService.YEAR_NS); // +500_000

        assertEquals(500_000L, delta2, "第二次半年利息");
        assertEquals(1_000_000L, loan.accumulatedInterest, "两次累积 = 全年利息");
    }

    @Test
    void accrueTo_isolated_overflowProtected_via128BitFallback() {
        // 溢出保护验证：elapsed × principal 直乘会溢出 long（YEAR_NS ≈ 3.15e16, principal 1e11 → 3.15e27 ≫ 9.2e18）
        // truncMulDiv 会走 128-bit 慢路径避免 silent overflow
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 10000, 0L);
        loan.outstandingPrincipal = 100_000_000_000L; // 1e11

        long delta = svc.accrueTo(loan, LoanService.YEAR_NS);

        // 100% APR × 一年 = 本金
        assertEquals(100_000_000_000L, delta);
    }

    // ================================================================
    // accrueTo(CrossLoanRecord) —— 公式跟 Isolated 共享 accrueDelta 私有 helper，只需 smoke test 验证 wiring
    // ================================================================

    @Test
    void accrueTo_cross_fullYear_matchesFormula() {
        CrossLoanRecord loan = new CrossLoanRecord(1L, 200L, 3, 10000, 0L);
        loan.outstandingPrincipal = 1_000_000L;

        long delta = svc.accrueTo(loan, LoanService.YEAR_NS);

        assertEquals(1_000_000L, delta);
        assertEquals(1_000_000L, loan.accumulatedInterest);
        assertEquals(LoanService.YEAR_NS, loan.lastAccrueTs);
    }

    @Test
    void accrueTo_cross_clockBackwards_noopNoRegression() {
        CrossLoanRecord loan = new CrossLoanRecord(1L, 200L, 3, 500, 10_000L);
        loan.outstandingPrincipal = 1_000_000L;

        long delta = svc.accrueTo(loan, 5000L);

        assertEquals(0L, delta);
        assertEquals(10_000L, loan.lastAccrueTs);
    }

    // ================================================================
    // calculateDisplayInterest —— 只读，含 pending，不修改 loan
    // ================================================================

    @Test
    void displayInterest_isolated_includesPending_doesNotMutate() {
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 10000, 0L);
        loan.outstandingPrincipal = 1_000_000L;
        loan.accumulatedInterest = 100L;

        long displayed = svc.calculateDisplayInterest(loan, LoanService.YEAR_NS);

        assertEquals(1_000_100L, displayed, "已累计 100 + pending 1_000_000");
        // 关键：loan 状态不被读接口修改
        assertEquals(100L, loan.accumulatedInterest, "不修改 accumulatedInterest");
        assertEquals(0L, loan.lastAccrueTs, "不推 lastAccrueTs");
    }

    @Test
    void displayInterest_isolated_clockBackwards_returnsOnlyAccrued() {
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 500, 10_000L);
        loan.outstandingPrincipal = 1_000_000L;
        loan.accumulatedInterest = 42L;

        long displayed = svc.calculateDisplayInterest(loan, 5000L);

        assertEquals(42L, displayed, "时钟倒退只返回已计入部分");
    }

    @Test
    void displayInterest_cross_smokeTest() {
        CrossLoanRecord loan = new CrossLoanRecord(1L, 200L, 3, 10000, 0L);
        loan.outstandingPrincipal = 1_000_000L;
        loan.accumulatedInterest = 500L;

        long displayed = svc.calculateDisplayInterest(loan, LoanService.YEAR_NS);

        assertEquals(1_000_500L, displayed);
        assertEquals(500L, loan.accumulatedInterest, "cross 也不 mutate");
    }

    // ================================================================
    // calculatePoolUtilizationBps
    // ================================================================

    @Test
    void poolUtil_unknownCurrency_returnsZero() {
        assertEquals(0, svc.calculatePoolUtilizationBps(999));
    }

    @Test
    void poolUtil_allAvailableNoBorrowed_returnsZero() {
        svc.getLoanPoolAvailable().put(3, 100_000L);
        assertEquals(0, svc.calculatePoolUtilizationBps(3));
    }

    @Test
    void poolUtil_halfBorrowed_returns5000Bps() {
        svc.getLoanPoolAvailable().put(3, 5000L);
        svc.getLoanPoolBorrowed().put(3, 5000L);
        assertEquals(5000, svc.calculatePoolUtilizationBps(3), "50% 利用率");
    }

    @Test
    void poolUtil_fullBorrowed_returns10000Bps() {
        svc.getLoanPoolAvailable().put(3, 0L);
        svc.getLoanPoolBorrowed().put(3, 10_000L);
        assertEquals(10000, svc.calculatePoolUtilizationBps(3), "100% 利用率");
    }

    @Test
    void poolUtil_thirtyPercent_returns3000Bps() {
        svc.getLoanPoolAvailable().put(3, 7_000L);
        svc.getLoanPoolBorrowed().put(3, 3_000L);
        assertEquals(3000, svc.calculatePoolUtilizationBps(3));
    }

    // ================================================================
    // calculateEffectiveRateBps —— v1 stub 永远返回 0
    // ================================================================

    @Test
    void effectiveRate_v1_alwaysZero_regardlessOfCurrency() {
        assertEquals(0, svc.calculateEffectiveRateBps(1));
        assertEquals(0, svc.calculateEffectiveRateBps(840));
        assertEquals(0, svc.calculateEffectiveRateBps(0));
    }

    // ================================================================
    // calculateCrossAccountLtvBps —— 空 crossLoans / numeraire 未配置 保守返回 0
    // ================================================================

    @Test
    void crossLtv_emptyCrossLoans_returnsZero() {
        exchange.core2.core.common.UserProfile up =
            new exchange.core2.core.common.UserProfile(1L, exchange.core2.core.common.UserStatus.ACTIVE);
        assertEquals(0L, svc.calculateCrossAccountLtvBps(up, 0L,
            new exchange.core2.core.processors.SymbolSpecificationProvider(),
            new exchange.core2.core.processors.CurrencySpecificationProvider(),
            new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<>(),
            LoanService.NUMERAIRE_UNSET));
    }

    @Test
    void crossLtv_nonEmptyCrossLoans_numeraireUnconfigured_returnsZero() {
        // NUMERAIRE_UNSET (0) 传给 calculateCrossAccountLtvBps → 保守返回 0，避免误触发强平
        exchange.core2.core.common.UserProfile up =
            new exchange.core2.core.common.UserProfile(1L, exchange.core2.core.common.UserStatus.ACTIVE);
        CrossLoanRecord loan = new CrossLoanRecord(1L, 200L, 3, 500, 0L);
        loan.outstandingPrincipal = 1_000_000L;
        up.crossLoans.put(200L, loan);

        assertEquals(0L, svc.calculateCrossAccountLtvBps(up, 0L,
            new exchange.core2.core.processors.SymbolSpecificationProvider(),
            new exchange.core2.core.processors.CurrencySpecificationProvider(),
            new org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap<>(),
            LoanService.NUMERAIRE_UNSET), "numeraire 未配置保守返回 0");
    }

    // ================================================================
    // calculateCrossAccountLtvBps —— 配置好 numeraire 后的完整路径
    // ================================================================

    // Test helper 常量（BTC/USDT/ETH digit=0，spec scale=1，让算术保持 identity）
    private static final int BTC = 1;
    private static final int USDT = 2;   // 用作 numeraire
    private static final int ETH = 3;
    private static final int SYMBOL_BTC_USDT = 2001;
    private static final int SYMBOL_ETH_USDT = 2002;

    private static SymbolSpecificationProvider setupSpecs() {
        SymbolSpecificationProvider p = new SymbolSpecificationProvider();
        p.registerSymbol(SYMBOL_BTC_USDT, CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_BTC_USDT).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(BTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
                .collateralWeightBps(9000)  // 90% BTC 折价
                .build());
        p.registerSymbol(SYMBOL_ETH_USDT, CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_ETH_USDT).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(ETH).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
                .collateralWeightBps(8000)  // 80% ETH 折价
                .build());
        return p;
    }

    private static CurrencySpecificationProvider setupCurrencies() {
        CurrencySpecificationProvider p = new CurrencySpecificationProvider();
        p.addCurrency(CoreCurrencySpecification.builder().id(BTC).name("BTC").digit(0).build());
        p.addCurrency(CoreCurrencySpecification.builder().id(USDT).name("USDT").digit(0).build());
        p.addCurrency(CoreCurrencySpecification.builder().id(ETH).name("ETH").digit(0).build());
        return p;
    }

    private static IntObjectHashMap<LastPriceCacheRecord> setupPrices(long btcPrice, long ethPrice) {
        IntObjectHashMap<LastPriceCacheRecord> cache = new IntObjectHashMap<>();
        LastPriceCacheRecord btcRec = new LastPriceCacheRecord();
        btcRec.markPrice = btcPrice;
        cache.put(SYMBOL_BTC_USDT, btcRec);
        LastPriceCacheRecord ethRec = new LastPriceCacheRecord();
        ethRec.markPrice = ethPrice;
        cache.put(SYMBOL_ETH_USDT, ethRec);
        return cache;
    }

    @Test
    void crossLtv_happy_singleLoanSingleCollateral_configuredNumeraire() {
        UserProfile up = new UserProfile(1L, UserStatus.ACTIVE);
        // 1 BTC 抵押, markPrice 50000 USDT, weight 90% → 45000 USDT weighted
        up.crossLoanCollateral.put(BTC, 1L);
        // 借 20000 USDT
        CrossLoanRecord loan = new CrossLoanRecord(1L, 200L, USDT, 500, 0L);
        loan.outstandingPrincipal = 20_000L;
        up.crossLoans.put(200L, loan);

        long ltv = svc.calculateCrossAccountLtvBps(up, 0L, setupSpecs(), setupCurrencies(),
                setupPrices(50_000L, 3_000L), USDT);
        // LTV = 20000 × 10000 / 45000 = 4444 bps
        assertEquals(4444L, ltv);
    }

    @Test
    void crossLtv_multiLoans_debtSummed() {
        UserProfile up = new UserProfile(1L, UserStatus.ACTIVE);
        up.crossLoanCollateral.put(BTC, 1L);  // 45000 weighted
        // 两笔 loan 都在 USDT，principal 分别 10000 + 15000 = 25000
        CrossLoanRecord loan1 = new CrossLoanRecord(1L, 200L, USDT, 500, 0L);
        loan1.outstandingPrincipal = 10_000L;
        up.crossLoans.put(200L, loan1);
        CrossLoanRecord loan2 = new CrossLoanRecord(1L, 201L, USDT, 500, 0L);
        loan2.outstandingPrincipal = 15_000L;
        up.crossLoans.put(201L, loan2);

        long ltv = svc.calculateCrossAccountLtvBps(up, 0L, setupSpecs(), setupCurrencies(),
                setupPrices(50_000L, 3_000L), USDT);
        // 25000 × 10000 / 45000 = 5555 bps
        assertEquals(5555L, ltv);
    }

    @Test
    void crossLtv_multiCollaterals_weightedSummed() {
        UserProfile up = new UserProfile(1L, UserStatus.ACTIVE);
        // 1 BTC (weight 90%) + 10 ETH (weight 80%)
        up.crossLoanCollateral.put(BTC, 1L);
        up.crossLoanCollateral.put(ETH, 10L);
        CrossLoanRecord loan = new CrossLoanRecord(1L, 200L, USDT, 500, 0L);
        loan.outstandingPrincipal = 60_000L;
        up.crossLoans.put(200L, loan);

        long ltv = svc.calculateCrossAccountLtvBps(up, 0L, setupSpecs(), setupCurrencies(),
                setupPrices(50_000L, 3_000L), USDT);
        // BTC weighted = 50000 × 0.9 = 45000；ETH weighted = 10 × 3000 × 0.8 = 24000
        // total weighted = 69000；LTV = 60000 × 10000 / 69000 = 8695 bps
        assertEquals(8695L, ltv);
    }

    @Test
    void crossLtv_collateralInNumeraireCurrency_identityConversion() {
        UserProfile up = new UserProfile(1L, UserStatus.ACTIVE);
        // 直接用 USDT (numeraire) 作抵押 → 免换算路径；但 USDT 作 base 找不到 spec，weight=0
        // 所以此路径 collateralWeightForBase(USDT) 返回 0，抵押不计入 —— 走的是"必须匹配 base=c 的 spec"契约
        up.crossLoanCollateral.put(USDT, 100_000L);
        CrossLoanRecord loan = new CrossLoanRecord(1L, 200L, USDT, 500, 0L);
        loan.outstandingPrincipal = 20_000L;
        up.crossLoans.put(200L, loan);

        long ltv = svc.calculateCrossAccountLtvBps(up, 0L, setupSpecs(), setupCurrencies(),
                setupPrices(50_000L, 3_000L), USDT);
        // USDT 没作为 base 的 spec → weight=0 → 抵押被 skip → weightedCollateral=0 有 debt → Long.MAX_VALUE
        assertEquals(Long.MAX_VALUE, ltv);
    }

    @Test
    void crossLtv_noCollateralWithDebt_returnsMaxValue() {
        UserProfile up = new UserProfile(1L, UserStatus.ACTIVE);
        CrossLoanRecord loan = new CrossLoanRecord(1L, 200L, USDT, 500, 0L);
        loan.outstandingPrincipal = 10_000L;
        up.crossLoans.put(200L, loan);

        long ltv = svc.calculateCrossAccountLtvBps(up, 0L, setupSpecs(), setupCurrencies(),
                setupPrices(50_000L, 3_000L), USDT);
        assertEquals(Long.MAX_VALUE, ltv, "无抵押挂债务 → 无穷 LTV");
    }

    @Test
    void crossLtv_markPriceMissing_returnsZeroConservative() {
        UserProfile up = new UserProfile(1L, UserStatus.ACTIVE);
        up.crossLoanCollateral.put(BTC, 1L);
        CrossLoanRecord loan = new CrossLoanRecord(1L, 200L, USDT, 500, 0L);
        loan.outstandingPrincipal = 20_000L;
        up.crossLoans.put(200L, loan);

        // priceCache 完全空
        long ltv = svc.calculateCrossAccountLtvBps(up, 0L, setupSpecs(), setupCurrencies(),
                new IntObjectHashMap<>(), USDT);
        assertEquals(0L, ltv, "markPrice 缺失保守返回 0，避免误触发");
    }

    @Test
    void crossLtv_numeraireSpecMissing_returnsZeroConservative() {
        UserProfile up = new UserProfile(1L, UserStatus.ACTIVE);
        up.crossLoanCollateral.put(BTC, 1L);
        CrossLoanRecord loan = new CrossLoanRecord(1L, 200L, USDT, 500, 0L);
        loan.outstandingPrincipal = 20_000L;
        up.crossLoans.put(200L, loan);

        // currencyProvider 缺 USDT (numeraire)
        CurrencySpecificationProvider partial = new CurrencySpecificationProvider();
        partial.addCurrency(CoreCurrencySpecification.builder().id(BTC).name("BTC").digit(0).build());
        assertNull(partial.getCurrencySpecification(USDT));

        long ltv = svc.calculateCrossAccountLtvBps(up, 0L, setupSpecs(), partial,
                setupPrices(50_000L, 3_000L), USDT);
        assertEquals(0L, ltv);
    }

    // ================================================================
    // collateralValueInQuoteCurrency —— 静态 helper 直接测（新 public API）
    // ================================================================

    @Test
    void collateralValueInQuoteCurrency_identity_digitZero() {
        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_BTC_USDT).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(BTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
                .build();
        CoreCurrencySpecification btcSpec =
                CoreCurrencySpecification.builder().id(BTC).name("BTC").digit(0).build();
        CoreCurrencySpecification usdtSpec =
                CoreCurrencySpecification.builder().id(USDT).name("USDT").digit(0).build();

        // 2 BTC × 50000 = 100000 USDT
        assertEquals(100_000L,
                LoanService.collateralValueInQuoteCurrency(2L, spec, 50_000L, btcSpec, usdtSpec));
    }

    @Test
    void collateralValueInQuoteCurrency_baseSpecMissing_returnsMinusOne() {
        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_BTC_USDT).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(BTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1).build();
        CoreCurrencySpecification usdtSpec =
                CoreCurrencySpecification.builder().id(USDT).name("USDT").digit(0).build();

        assertEquals(-1L, LoanService.collateralValueInQuoteCurrency(1L, spec, 50_000L, null, usdtSpec));
    }

    @Test
    void collateralValueInQuoteCurrency_quoteSpecMissing_returnsMinusOne() {
        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_BTC_USDT).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(BTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1).build();
        CoreCurrencySpecification btcSpec =
                CoreCurrencySpecification.builder().id(BTC).name("BTC").digit(0).build();

        assertEquals(-1L, LoanService.collateralValueInQuoteCurrency(1L, spec, 50_000L, btcSpec, null));
    }

    // ================================================================
    // findSpotSpec —— 静态 helper 直接测（新 public API）
    // ================================================================

    @Test
    void findSpotSpec_matchesByBaseAndQuote() {
        SymbolSpecificationProvider p = setupSpecs();
        CoreSymbolSpecification found = LoanService.findSpotSpec(BTC, USDT, p);
        assertEquals(SYMBOL_BTC_USDT, found.symbolId);
    }

    @Test
    void findSpotSpec_wrongDirection_returnsNull() {
        SymbolSpecificationProvider p = setupSpecs();
        // base=USDT / quote=BTC 反向找不到（BTC/USDT 是唯一方向）
        assertNull(LoanService.findSpotSpec(USDT, BTC, p));
    }

    @Test
    void findSpotSpec_unknownCurrency_returnsNull() {
        SymbolSpecificationProvider p = setupSpecs();
        assertNull(LoanService.findSpotSpec(999, 998, p));
    }

    // ================================================================
    // OrderId encoding: Isolated
    // 布局: | 'L' 0x4C | 'S' 0x53 | payload 24 | uidHash 20 | ts 4 |
    // ================================================================

    @Test
    void isolatedOrderId_topByteIsL() {
        IsolatedLoanRecord loan = new IsolatedLoanRecord(42L, 999L, 2, 3, 500, 0L);
        long orderId = LoanService.generateIsolatedForceSellOrderId(loan);
        assertEquals(LoanService.ORDERID_NAMESPACE_TAG, (orderId >>> 56) & 0xFFL);
    }

    @Test
    void isolatedOrderId_secondByteIsS() {
        IsolatedLoanRecord loan = new IsolatedLoanRecord(42L, 999L, 2, 3, 500, 0L);
        long orderId = LoanService.generateIsolatedForceSellOrderId(loan);
        assertEquals(LoanService.ORDERID_SUBTYPE_ISOLATED, (orderId >>> 48) & 0xFFL, "Isolated subtype 'S'");
    }

    @Test
    void isolatedOrderId_encodesLoanIdInPayload() {
        long loanId = 0xABCDEFL;
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, loanId, 2, 3, 500, 0L);
        long orderId = LoanService.generateIsolatedForceSellOrderId(loan);
        long payload = (orderId >>> 24) & 0xFFFFFFL;
        assertEquals(loanId, payload);
    }

    @Test
    void isolatedOrderId_encodesUidHash() {
        long uid = 42L;
        IsolatedLoanRecord loan = new IsolatedLoanRecord(uid, 999L, 2, 3, 500, 0L);
        long orderId = LoanService.generateIsolatedForceSellOrderId(loan);
        long expected = (uid * 31 + 17) & 0xFFFFFL;
        long actual = (orderId >>> 4) & 0xFFFFFL;
        assertEquals(expected, actual);
    }

    @Test
    void isolatedOrderId_differentLoansProduceDifferentIds() {
        IsolatedLoanRecord loanA = new IsolatedLoanRecord(1L, 100L, 2, 3, 500, 0L);
        IsolatedLoanRecord loanB = new IsolatedLoanRecord(1L, 101L, 2, 3, 500, 0L);
        assertNotEquals(LoanService.generateIsolatedForceSellOrderId(loanA),
            LoanService.generateIsolatedForceSellOrderId(loanB));
    }

    // ================================================================
    // OrderId encoding: Cross
    // ================================================================

    @Test
    void crossOrderId_topByteIsL() {
        long orderId = LoanService.generateCrossForceSellOrderId(42L, 3);
        assertEquals(LoanService.ORDERID_NAMESPACE_TAG, (orderId >>> 56) & 0xFFL);
    }

    @Test
    void crossOrderId_secondByteIsC() {
        long orderId = LoanService.generateCrossForceSellOrderId(42L, 3);
        assertEquals(LoanService.ORDERID_SUBTYPE_CROSS, (orderId >>> 48) & 0xFFL, "Cross subtype 'C'");
    }

    @Test
    void crossOrderId_encodesSellingCcyInPayload() {
        int sellingCcy = 0x123456;
        long orderId = LoanService.generateCrossForceSellOrderId(42L, sellingCcy);
        long payload = (orderId >>> 24) & 0xFFFFFFL;
        assertEquals(sellingCcy, payload);
    }

    @Test
    void crossOrderId_differentSellingCcyProducesDifferentIds() {
        long orderIdA = LoanService.generateCrossForceSellOrderId(42L, 3);
        long orderIdB = LoanService.generateCrossForceSellOrderId(42L, 4);
        assertNotEquals(orderIdA, orderIdB);
    }

    // ================================================================
    // OrderId classification & 非冲突性
    // ================================================================

    @Test
    void isLoanForceSellOrderId_trueForIsolated() {
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 500, 0L);
        long orderId = LoanService.generateIsolatedForceSellOrderId(loan);
        assertTrue(LoanService.isLoanForceSellOrderId(orderId));
    }

    @Test
    void isLoanForceSellOrderId_trueForCross() {
        long orderId = LoanService.generateCrossForceSellOrderId(42L, 3);
        assertTrue(LoanService.isLoanForceSellOrderId(orderId));
    }

    @Test
    void isLoanForceSellOrderId_falseForRandomOrderIds() {
        // 顶字节非 'L' 都应该 false
        assertFalse(LoanService.isLoanForceSellOrderId(0L));
        assertFalse(LoanService.isLoanForceSellOrderId(0x1234567890ABCDEFL));
        assertFalse(LoanService.isLoanForceSellOrderId(Long.MAX_VALUE));
    }

    @Test
    void isLoanForceSellOrderId_falseForIFOrderId() {
        // LiquidationService.generateIFOrderId 顶字节是 'I' 0x49，不应该被认为是 loan force-sell
        long ifOrderId = LiquidationService.generateIFOrderId(0x00_1234_5678_9ABCL);
        assertFalse(LoanService.isLoanForceSellOrderId(ifOrderId), "IF orderId 顶字节是 'I' 不是 'L'");
    }

    @Test
    void isLoanForceSellOrderId_falseForADLOrderId() {
        long adlOrderId = LiquidationService.generateADLOrderId(0x00_1234_5678_9ABCL);
        assertFalse(LoanService.isLoanForceSellOrderId(adlOrderId), "ADL orderId 顶字节是 'A' 不是 'L'");
    }

    @Test
    void loanForceSellSubtype_returnsSForIsolated() {
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 500, 0L);
        long orderId = LoanService.generateIsolatedForceSellOrderId(loan);
        assertEquals((byte) 'S', LoanService.loanForceSellSubtype(orderId));
    }

    @Test
    void loanForceSellSubtype_returnsCForCross() {
        long orderId = LoanService.generateCrossForceSellOrderId(42L, 3);
        assertEquals((byte) 'C', LoanService.loanForceSellSubtype(orderId));
    }

    // ================================================================
    // reset
    // ================================================================

    @Test
    void reset_clearsPoolsAndBadDebt_restoresDefaults() {
        svc.getLoanPoolAvailable().put(3, 100_000L);
        svc.getLoanPoolBorrowed().put(3, 50_000L);
        svc.getBadDebt().put(3, -1_000L);

        svc.reset();

        assertTrue(svc.getLoanPoolAvailable().isEmpty());
        assertTrue(svc.getLoanPoolBorrowed().isEmpty());
        assertTrue(svc.getBadDebt().isEmpty());
        assertEquals(LoanService.DEFAULT_CROSS_LIQUIDATION_LTV_BPS, svc.getCrossLiquidationLtvBps());
        assertEquals(LoanService.DEFAULT_CROSS_MARGIN_CALL_LTV_BPS, svc.getCrossMarginCallLtvBps());
        assertEquals(LoanService.DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS, svc.getLoanPoolUtilizationCapBps());
    }

    // ================================================================
    // Snapshot round-trip：write → read → 状态等价 + stateHash 相同
    // ================================================================

    @Test
    void snapshot_roundTrip_preservesAllState() {
        svc.getLoanPoolAvailable().put(3, 100_000L);
        svc.getLoanPoolAvailable().put(4, 50_000L);
        svc.getLoanPoolBorrowed().put(3, 20_000L);
        svc.getBadDebt().put(3, -5_000L);
        svc.getPoolProcessedExternalIds().tryClaim(1001L);
        svc.getPoolProcessedExternalIds().tryClaim(1002L);

        Bytes<?> buf = Bytes.allocateElasticOnHeap(512);
        svc.writeMarshallable(buf);

        LoanService restored = new LoanService(buf);

        // 先算 stateHash（不 mutate 任何状态），再做 dedup 行为验证（会 mutate restored 的 ring）
        assertEquals(svc.stateHash(), restored.stateHash(), "stateHash 必须一致");

        assertEquals(100_000L, restored.getLoanPoolAvailable().get(3));
        assertEquals(50_000L, restored.getLoanPoolAvailable().get(4));
        assertEquals(20_000L, restored.getLoanPoolBorrowed().get(3));
        assertEquals(-5_000L, restored.getBadDebt().get(3));
        assertEquals(LoanService.DEFAULT_CROSS_LIQUIDATION_LTV_BPS, restored.getCrossLiquidationLtvBps());
        assertEquals(LoanService.DEFAULT_CROSS_MARGIN_CALL_LTV_BPS, restored.getCrossMarginCallLtvBps());
        assertEquals(LoanService.DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS, restored.getLoanPoolUtilizationCapBps());
        assertFalse(restored.getPoolProcessedExternalIds().tryClaim(1001L), "dedup 1001 已 claim");
        assertFalse(restored.getPoolProcessedExternalIds().tryClaim(1002L), "dedup 1002 已 claim");
        assertTrue(restored.getPoolProcessedExternalIds().tryClaim(1003L), "新 ID 仍可 claim");
    }

    @Test
    void snapshot_roundTrip_emptyService() {
        // 冷启无状态时 round-trip 也要成立
        Bytes<?> buf = Bytes.allocateElasticOnHeap(256);
        svc.writeMarshallable(buf);

        LoanService restored = new LoanService(buf);

        assertTrue(restored.getLoanPoolAvailable().isEmpty());
        assertTrue(restored.getLoanPoolBorrowed().isEmpty());
        assertTrue(restored.getBadDebt().isEmpty());
        assertEquals(svc.stateHash(), restored.stateHash());
    }
}
