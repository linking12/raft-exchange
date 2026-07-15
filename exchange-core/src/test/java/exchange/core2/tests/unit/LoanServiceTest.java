package exchange.core2.tests.unit;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.SymbolLoanSpecification;
import exchange.core2.core.common.CrossLoanRecord;
import exchange.core2.core.common.IsolatedLoanRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.liquidation.LiquidationService;
import exchange.core2.core.processors.loan.LoanGlobalConfig;
import exchange.core2.core.processors.loan.LoanService;
import net.openhft.chronicle.bytes.Bytes;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
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
 *   <li>calculateCrossAccountLtvBps</li>
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
    // 公式: interest = elapsed_ms × principal × rateBps / (YEAR_MS × 10000)
    // ================================================================

    @Test
    void accrueTo_isolated_fullYear_100pctRate_matchesPrincipal() {
        // 100% APR 一整年应该产生 100% 本金的利息
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 10000, 0L);
        loan.outstandingPrincipal = 1_000_000L;

        long delta = svc.accrueTo(loan, LoanService.YEAR_MS);

        assertEquals(1_000_000L, delta, "100% APR 全年 = 本金");
        assertEquals(1_000_000L, loan.accumulatedInterest);
        assertEquals(LoanService.YEAR_MS, loan.lastAccrueTs);
    }

    @Test
    void accrueTo_isolated_halfYear_halfInterest() {
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 10000, 0L);
        loan.outstandingPrincipal = 1_000_000L;

        long delta = svc.accrueTo(loan, LoanService.YEAR_MS / 2);

        assertEquals(500_000L, delta, "100% APR 半年 = 半本金");
    }

    @Test
    void accrueTo_isolated_quarterYear_5pctRate_matchesFormula() {
        // 5% APR × 90 天 ≈ 1.25% 本金；用两步 truncMulDiv 验证不出现截断偏差
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 500, 0L);
        loan.outstandingPrincipal = 30_000_000L;

        long delta = svc.accrueTo(loan, LoanService.YEAR_MS / 4);

        // 期望: 30_000_000 × 5% / 4 = 375_000
        assertEquals(375_000L, delta);
    }

    @Test
    void accrueTo_isolated_zeroPrincipal_noInterest() {
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 500, 0L);
        loan.outstandingPrincipal = 0L;

        long delta = svc.accrueTo(loan, LoanService.YEAR_MS);

        assertEquals(0L, delta);
        assertEquals(0L, loan.accumulatedInterest);
        // 即使无 principal，lastAccrueTs 仍推进（避免下次 accrue 从太老的时间点算）
        assertEquals(LoanService.YEAR_MS, loan.lastAccrueTs);
    }

    @Test
    void accrueTo_isolated_zeroRate_noInterest() {
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 0, 0L);
        loan.outstandingPrincipal = 1_000_000L;

        long delta = svc.accrueTo(loan, LoanService.YEAR_MS);

        assertEquals(0L, delta);
        assertEquals(LoanService.YEAR_MS, loan.lastAccrueTs, "免息也推 lastAccrueTs");
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

        svc.accrueTo(loan, LoanService.YEAR_MS / 2);   // +500_000
        long delta2 = svc.accrueTo(loan, LoanService.YEAR_MS); // +500_000

        assertEquals(500_000L, delta2, "第二次半年利息");
        assertEquals(1_000_000L, loan.accumulatedInterest, "两次累积 = 全年利息");
    }

    @Test
    void accrueTo_isolated_frequentSubThresholdAccrual_doesNotZeroOutInterest() {
        // F1 回归：P=1e6、100% APR → 每 1 单位利息需 elapsed ≥ YEAR_MS/P = 31536ms。
        // 用 10512ms(< 阈值)高频 accrue：旧实现每步截断为 0 且游标照推 → 利息被永久抹成 0；
        // 修复后游标在"可计息却被截断"时保留，高频总利息应 == 单次一把算。
        final long stepMs = 10_512L;
        final long totalMs = 3_153_600L; // = 100 × 31536ms → 单次算 = 100 利息
        IsolatedLoanRecord frequent = new IsolatedLoanRecord(1L, 100L, 2, 3, 10000, 0L);
        frequent.outstandingPrincipal = 1_000_000L;
        for (long t = stepMs; t <= totalMs; t += stepMs) {
            svc.accrueTo(frequent, t);
        }
        IsolatedLoanRecord single = new IsolatedLoanRecord(1L, 101L, 2, 3, 10000, 0L);
        single.outstandingPrincipal = 1_000_000L;
        svc.accrueTo(single, totalMs);

        assertEquals(100L, single.accumulatedInterest, "单次一把算 = 100");
        assertTrue(frequent.accumulatedInterest > 0, "高频 accrue 不应把利息抹零(F1)");
        assertEquals(single.accumulatedInterest, frequent.accumulatedInterest,
            "高频 accrue 总利息应 == 单次(游标保留亚阈值时间)");
    }

    @Test
    void accrueTo_isolated_overflowProtected_via128BitFallback() {
        // 溢出保护验证：elapsed × principal 直乘会溢出 long（YEAR_MS ≈ 3.15e10, principal 1e11 → 3.15e21 ≫ 9.2e18）
        // truncMulDiv 会走 128-bit 慢路径避免 silent overflow
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 10000, 0L);
        loan.outstandingPrincipal = 100_000_000_000L; // 1e11

        long delta = svc.accrueTo(loan, LoanService.YEAR_MS);

        // 100% APR × 一年 = 本金
        assertEquals(100_000_000_000L, delta);
    }

    // ================================================================
    // accrueTo(CrossLoanRecord) —— 公式跟 Isolated 共享 accrueDelta 私有 helper，只需 smoke test 验证 wiring
    // ================================================================

    @Test
    void accrueTo_cross_floating_fullYear_matchesFormula() {
        // Cross 恒 FLOATING：计息走 FloatingRateModel 累加器（loan.md §13.5/§13.6），不看 loan.rateBps。
        // 把 ccy=3 当前活期利率钉到 100%（10000bps）、以开仓时刻为 reprice 基准，验证满一年利息 = 本金。
        final int ccy = 3;
        svc.getFloatingRate().getCurrentRateBps().put(ccy, 10000L);
        svc.getFloatingRate().setLastRepriceTs(1000L);
        CrossLoanRecord loan = new CrossLoanRecord(1L, 200L, ccy, 10000, 1000L);
        loan.outstandingPrincipal = 1_000_000L;
        svc.getFloatingRate().initOpenSnapshot(loan, 1000L); // 开仓快照 = liveAcc(1000) = 0

        long delta = svc.accrueTo(loan, 1000L + LoanService.YEAR_MS);

        assertEquals(1_000_000L, delta, "100% 活期利率满一年 = 本金");
        assertEquals(1_000_000L, loan.accumulatedInterest);
    }

    @Test
    void accrueTo_cross_floating_frequentSubThresholdAccrual_doesNotZeroOutInterest() {
        // F1 回归(FLOATING 累加器版)：同 P/rate，阈值同为 31536ms。高频 accrue 时 snapshot 应保留亚阈值增量，
        // 总利息 == 单次；旧实现无条件推 snapshot → 抹零。
        final int ccy = 3;
        svc.getFloatingRate().getCurrentRateBps().put(ccy, 10000L);
        svc.getFloatingRate().setLastRepriceTs(1000L);
        final long open = 1000L;
        final long stepMs = 10_512L;
        final long spanMs = 3_153_600L; // 100 × 31536ms

        CrossLoanRecord frequent = new CrossLoanRecord(1L, 200L, ccy, 10000, open);
        frequent.outstandingPrincipal = 1_000_000L;
        svc.getFloatingRate().initOpenSnapshot(frequent, open);
        for (long t = open + stepMs; t <= open + spanMs; t += stepMs) {
            svc.accrueTo(frequent, t);
        }
        CrossLoanRecord single = new CrossLoanRecord(1L, 201L, ccy, 10000, open);
        single.outstandingPrincipal = 1_000_000L;
        svc.getFloatingRate().initOpenSnapshot(single, open);
        svc.accrueTo(single, open + spanMs);

        assertEquals(100L, single.accumulatedInterest, "单次一把算 = 100");
        assertTrue(frequent.accumulatedInterest > 0, "高频 FLOATING accrue 不应抹零(F1)");
        assertEquals(single.accumulatedInterest, frequent.accumulatedInterest, "高频总利息 == 单次");
    }

    @Test
    void accrueTo_floating_integratesAcrossRateChange() {
        // 累加器的核心价值：利率在两次 reprice 之间变化时，利息 = ∫rate dt（分段积分），而非用单一利率线性。
        // [T0,T1) 10% 半年 + [T1,T2) 30% 半年 → 全年等效 20% 本金。
        final int ccy = 3;
        final long half = LoanService.YEAR_MS / 2;
        final long t0 = 1000L, t1 = t0 + half, t2 = t0 + LoanService.YEAR_MS;
        svc.getFloatingRate().setLastRepriceTs(t0);
        svc.getFloatingRate().getCurrentRateBps().put(ccy, 1000L); // [t0,t1) 用 10%

        CrossLoanRecord loan = new CrossLoanRecord(1L, 200L, ccy, 1000, t0);
        loan.outstandingPrincipal = 1_000_000L;
        svc.getFloatingRate().initOpenSnapshot(loan, t0);

        // t1 reprice：先用【旧率】10% 把累加器推到 t1，再切 30%（模拟 RiskEngine R2 的 advance→reprice→set 顺序）
        svc.getFloatingRate().advanceAccumulator(ccy, t1);
        svc.getFloatingRate().setLastRepriceTs(t1);
        svc.getFloatingRate().getCurrentRateBps().put(ccy, 3000L); // [t1,t2) 用 30%

        long delta = svc.accrueTo(loan, t2);

        assertEquals(200_000L, delta, "10% 半年 + 30% 半年 = 20% 本金（累加器分段积分，非线性）");
    }

    @Test
    void accrueTo_isolatedFloating_dispatchesToAccumulator_notLinearRate() {
        // Isolated 按 rateMode 分派：FLOATING → FloatingRateModel（累加器），不看 loan.rateBps。
        // 令 loan.rateBps=0（若误走线性会算出 0 利息），曲线现值=100% → 有利息即证明走了累加器。
        final int ccy = 3;
        svc.getFloatingRate().getCurrentRateBps().put(ccy, 10000L);
        svc.getFloatingRate().setLastRepriceTs(1000L);
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, ccy, 0, 1000L); // rateBps=0
        loan.rateMode = IsolatedLoanRecord.RATE_MODE_FLOATING;
        loan.outstandingPrincipal = 1_000_000L;
        svc.getFloatingRate().initOpenSnapshot(loan, 1000L);

        long delta = svc.accrueTo(loan, 1000L + LoanService.YEAR_MS);

        assertEquals(1_000_000L, delta, "Isolated FLOATING 走累加器（曲线 100%），非 loan.rateBps=0 线性");
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

        long displayed = svc.calculateDisplayInterest(loan, LoanService.YEAR_MS);

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
    void displayInterest_cross_floating_includesPending_doesNotMutate() {
        // Cross FLOATING 只读计息：accumulatedInterest + 到 now 的 pending（累加器差值），不 mutate loan。
        final int ccy = 3;
        svc.getFloatingRate().getCurrentRateBps().put(ccy, 10000L);
        svc.getFloatingRate().setLastRepriceTs(1000L);
        CrossLoanRecord loan = new CrossLoanRecord(1L, 200L, ccy, 10000, 1000L);
        loan.outstandingPrincipal = 1_000_000L;
        loan.accumulatedInterest = 500L;
        svc.getFloatingRate().initOpenSnapshot(loan, 1000L);

        long displayed = svc.calculateDisplayInterest(loan, 1000L + LoanService.YEAR_MS);

        assertEquals(1_000_500L, displayed, "已累计 500 + pending 1_000_000");
        assertEquals(500L, loan.accumulatedInterest, "cross 也不 mutate accumulatedInterest");
        assertEquals(0L, loan.getAccSnapshot(), "只读不推进 accSnapshot");
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
            LoanGlobalConfig.NUMERAIRE_UNSET));
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
            LoanGlobalConfig.NUMERAIRE_UNSET), "numeraire 未配置保守返回 0");
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
                .loanConfig(SymbolLoanSpecification.builder().collateralWeightBps(9000).build())  // 90% BTC 折价
                .build());
        p.registerSymbol(SYMBOL_ETH_USDT, CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_ETH_USDT).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(ETH).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
                .loanConfig(SymbolLoanSpecification.builder().collateralWeightBps(8000).build())  // 80% ETH 折价
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
    // OrderId encoding（对齐期货「身份 + 秒级 ts」，无状态）
    // 布局: | 'L' 0x4C | subtype | uidHash 20 | loanIdHash 16 | ts秒 12 |
    // ================================================================

    @Test
    void orderId_topByteIsL() {
        long orderId = LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_ISOLATED, 1L, 100L, 0L);
        assertEquals(LoanService.ORDERID_NAMESPACE_TAG, (orderId >>> 56) & 0xFFL);
    }

    @Test
    void orderId_isolatedSubtypeIsS() {
        long orderId = LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_ISOLATED, 1L, 1L, 0L);
        assertEquals(LoanService.ORDERID_SUBTYPE_ISOLATED, (orderId >>> 48) & 0xFFL, "Isolated subtype 'S'");
    }

    @Test
    void orderId_crossSubtypeIsC() {
        long orderId = LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_CROSS, 1L, 1L, 0L);
        assertEquals(LoanService.ORDERID_SUBTYPE_CROSS, (orderId >>> 48) & 0xFFL, "Cross subtype 'C'");
    }

    @Test
    void orderId_encodesIdentityAndTs() {
        // uid=5 → uidHash=(5*31+17)=172；loanId=100 → loanIdHash=(100*31+17)=3117；nowMs=7000 → ts秒=7
        long orderId = LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_ISOLATED, 5L, 100L, 7_000L);
        assertEquals(172L, (orderId >>> 28) & LoanService.ORDERID_UID_MASK, "uidHash 在 47..28");
        assertEquals(3117L, (orderId >>> 12) & LoanService.ORDERID_LOANID_MASK, "loanIdHash 在 27..12");
        assertEquals(7L, orderId & LoanService.ORDERID_TS_MASK, "ts秒 在 11..0");
    }

    @Test
    void orderId_differentIdentityOrTsProduceDifferentIds() {
        long base = LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_ISOLATED, 5L, 100L, 0L);
        assertNotEquals(base, LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_ISOLATED, 5L, 101L, 0L), "loanId 不同");
        assertNotEquals(base, LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_ISOLATED, 6L, 100L, 0L), "uid 不同");
        assertNotEquals(base, LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_ISOLATED, 5L, 100L, 60_000L), "ts 不同");
    }

    // ================================================================
    // OrderId 分类 & 非冲突性（顶字节 'L' 独占，避开期货 'I' / ADL 'A'）
    // ================================================================

    @Test
    void isLoanForceSellOrderId_trueForForceSell() {
        assertTrue(LoanService.isLoanForceSellOrderId(
            LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_ISOLATED, 1L, 1L, 0L)));
        assertTrue(LoanService.isLoanForceSellOrderId(
            LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_CROSS, 1L, 1L, 0L)));
    }

    @Test
    void isLoanForceSellOrderId_falseForRandomOrderIds() {
        assertFalse(LoanService.isLoanForceSellOrderId(0L));
        assertFalse(LoanService.isLoanForceSellOrderId(0x1234567890ABCDEFL));
        assertFalse(LoanService.isLoanForceSellOrderId(Long.MAX_VALUE));
    }

    @Test
    void isLoanForceSellOrderId_falseForIFOrderId() {
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
        assertEquals((byte) 'S', LoanService.loanForceSellSubtype(
            LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_ISOLATED, 1L, 1L, 0L)));
    }

    @Test
    void loanForceSellSubtype_returnsCForCross() {
        assertEquals((byte) 'C', LoanService.loanForceSellSubtype(
            LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_CROSS, 1L, 1L, 0L)));
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
        assertEquals(LoanGlobalConfig.DEFAULT_CROSS_LIQUIDATION_LTV_BPS, svc.getGlobalConfig().crossLiquidationLtvBps);
        assertEquals(LoanGlobalConfig.DEFAULT_CROSS_MARGIN_CALL_LTV_BPS, svc.getGlobalConfig().crossMarginCallLtvBps);
        assertEquals(LoanGlobalConfig.DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS, svc.getGlobalConfig().loanPoolUtilizationCapBps);
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
        svc.getGlobalConfig().loanLiquidationFeeBps = 150; // 非默认值，验证配置字段随快照往返

        Bytes<?> buf = Bytes.allocateElasticOnHeap(512);
        svc.writeMarshallable(buf);

        LoanService restored = new LoanService(buf);

        // 先算 stateHash（不 mutate 任何状态），再做 dedup 行为验证（会 mutate restored 的 ring）
        assertEquals(svc.stateHash(), restored.stateHash(), "stateHash 必须一致");

        assertEquals(100_000L, restored.getLoanPoolAvailable().get(3));
        assertEquals(50_000L, restored.getLoanPoolAvailable().get(4));
        assertEquals(20_000L, restored.getLoanPoolBorrowed().get(3));
        assertEquals(-5_000L, restored.getBadDebt().get(3));
        assertEquals(LoanGlobalConfig.DEFAULT_CROSS_LIQUIDATION_LTV_BPS, restored.getGlobalConfig().crossLiquidationLtvBps);
        assertEquals(LoanGlobalConfig.DEFAULT_CROSS_MARGIN_CALL_LTV_BPS, restored.getGlobalConfig().crossMarginCallLtvBps);
        assertEquals(LoanGlobalConfig.DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS, restored.getGlobalConfig().loanPoolUtilizationCapBps);
        assertEquals(150, restored.getGlobalConfig().loanLiquidationFeeBps, "自定义强平费率随快照往返");
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

    // ================================================================
    // applyDebtPayment —— 利息优先 → 本金抵债，clamp 到未偿本息
    // 公式: interestPart = min(fund, accInt); principalPart = min(fund-interestPart, principal)
    //       account[cur] -= paid; interestRevenue[cur] += interestPart;
    //       loanPoolAvailable[cur] += principalPart; loanPoolBorrowed[cur] -= principalPart
    // ================================================================

    @Test
    void applyDebtPayment_fundBelowInterest_interestOnly_principalAndPoolUntouched() {
        // loanCurrency=3；accInt=1000, principal=5000；fund=400 (< accInt) → 只还利息，本金/池不动
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 10000, 0L);
        loan.outstandingPrincipal = 5_000L;
        loan.accumulatedInterest = 1_000L;
        IntLongHashMap account = new IntLongHashMap();
        account.put(3, 10_000L);
        svc.getLoanPoolBorrowed().put(3, 5_000L);
        svc.getLoanPoolAvailable().put(3, 0L);

        long interestPart = svc.applyDebtPayment(loan, account, 400L);

        assertEquals(400L, interestPart, "返回值 = interestPart = min(400,1000)");
        assertEquals(9_600L, account.get(3), "account -= paid(400)");
        assertEquals(600L, loan.accumulatedInterest, "accInt -= 400");
        assertEquals(5_000L, loan.outstandingPrincipal, "principal 不动");
        assertEquals(400L, svc.getInterestRevenue().get(3), "interestRevenue += 400");
        assertEquals(0L, svc.getLoanPoolAvailable().get(3), "principalPart=0 → pool available 不动");
        assertEquals(5_000L, svc.getLoanPoolBorrowed().get(3), "principalPart=0 → pool borrowed 不动");
    }

    @Test
    void applyDebtPayment_fundCoversInterestAndPartialPrincipal_splitAndPoolMutated() {
        // accInt=1000, principal=5000；fund=3000 → 利息 1000 + 本金 2000，池按 2000 迁移
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 10000, 0L);
        loan.outstandingPrincipal = 5_000L;
        loan.accumulatedInterest = 1_000L;
        IntLongHashMap account = new IntLongHashMap();
        account.put(3, 10_000L);
        svc.getLoanPoolBorrowed().put(3, 5_000L);
        svc.getLoanPoolAvailable().put(3, 2_000L);

        long interestPart = svc.applyDebtPayment(loan, account, 3_000L);

        assertEquals(1_000L, interestPart, "返回值 = interestPart = 全部利息");
        assertEquals(7_000L, account.get(3), "account -= paid(3000)");
        assertEquals(0L, loan.accumulatedInterest, "利息清零");
        assertEquals(3_000L, loan.outstandingPrincipal, "principal -= 2000");
        assertEquals(1_000L, svc.getInterestRevenue().get(3), "interestRevenue += 1000");
        assertEquals(4_000L, svc.getLoanPoolAvailable().get(3), "available += principalPart(2000)");
        assertEquals(3_000L, svc.getLoanPoolBorrowed().get(3), "borrowed -= principalPart(2000)");
    }

    @Test
    void applyDebtPayment_fundExceedsOutstanding_clampsNoOverpay() {
        // accInt=500, principal=2000 (total 2500)；fund=10000 → paid clamp 到 2500，无超额扣款
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 10000, 0L);
        loan.outstandingPrincipal = 2_000L;
        loan.accumulatedInterest = 500L;
        IntLongHashMap account = new IntLongHashMap();
        account.put(3, 10_000L);
        svc.getLoanPoolBorrowed().put(3, 2_000L);
        svc.getLoanPoolAvailable().put(3, 0L);

        long interestPart = svc.applyDebtPayment(loan, account, 10_000L);

        assertEquals(500L, interestPart, "返回值 = interestPart = 全部利息");
        assertEquals(7_500L, account.get(3), "只扣 paid(2500)，剩余 7500 留在 account");
        assertEquals(0L, loan.accumulatedInterest, "利息清零");
        assertEquals(0L, loan.outstandingPrincipal, "本金清零");
        assertEquals(500L, svc.getInterestRevenue().get(3), "interestRevenue += 500");
        assertEquals(2_000L, svc.getLoanPoolAvailable().get(3), "available += principalPart(2000)");
        assertEquals(0L, svc.getLoanPoolBorrowed().get(3), "borrowed -= principalPart(2000)");
    }

    // ================================================================
    // settleLiquidationProceeds —— 抽强平费(ceil) → accrue → 抵债
    // ================================================================

    @Test
    void settleLiquidationProceeds_skimsCeilFee_accruesThenPaysDebt() {
        // liqFee=100bps(1%)；receivedQuote=100050 → fee=ceil(100050×100/10000)=ceil(1000.5)=1001
        svc.getGlobalConfig().loanLiquidationFeeBps = 100;
        // LOCKED 100% APR 满一年：accrueTo 补计利息 = principal = 50000
        IsolatedLoanRecord loan = new IsolatedLoanRecord(1L, 100L, 2, 3, 10000, 0L);
        loan.outstandingPrincipal = 50_000L;
        loan.accumulatedInterest = 0L;
        IntLongHashMap account = new IntLongHashMap();
        account.put(3, 200_000L);
        svc.getLoanPoolBorrowed().put(3, 50_000L);
        svc.getLoanPoolAvailable().put(3, 0L);

        // fund 抵债 = receivedQuote - liqFee = 100050 - 1001 = 99049
        // 抵债: interestPart=min(99049,50000)=50000; principalPart=min(49049,50000)=49049
        long interestPart = svc.settleLiquidationProceeds(loan, account, 100_050L, LoanService.YEAR_MS);

        assertEquals(50_000L, interestPart, "返回值 = 结算的利息部分");
        assertEquals(1_001L, svc.getLoanLiquidationFees().get(3), "强平费 ceil skim = 1001");
        assertEquals(LoanService.YEAR_MS, loan.lastAccrueTs, "accrueTo 已把游标推到 now");
        assertEquals(0L, loan.accumulatedInterest, "利息全部结清");
        assertEquals(951L, loan.outstandingPrincipal, "principal -= 49049 → 951");
        assertEquals(50_000L, svc.getInterestRevenue().get(3), "interestRevenue += 50000");
        assertEquals(49_049L, svc.getLoanPoolAvailable().get(3), "available += principalPart(49049)");
        assertEquals(951L, svc.getLoanPoolBorrowed().get(3), "borrowed -= 49049 → 951");
        // account: 200000 - liqFee(1001) - paid(99049) = 99950
        assertEquals(99_950L, account.get(3), "account 扣 liqFee 再扣 paid");
    }

    // ================================================================
    // calculateCrossAccountLtvBps 7-arg failClosed=true —— F2 fail-close guard
    // (BORROW/WITHDRAW 前置校验：价格/spec 未就绪时返回 MAX_VALUE 拒绝，而非 0 放行)
    // ================================================================

    @Test
    void crossLtv_failClosed_numeraireSpecMissing_returnsMaxValue() {
        UserProfile up = new UserProfile(1L, UserStatus.ACTIVE);
        up.crossLoanCollateral.put(BTC, 1L);
        CrossLoanRecord loan = new CrossLoanRecord(1L, 200L, USDT, 500, 0L);
        loan.outstandingPrincipal = 20_000L;
        up.crossLoans.put(200L, loan);

        // currencyProvider 缺 numeraire(USDT) 的 spec
        CurrencySpecificationProvider partial = new CurrencySpecificationProvider();
        partial.addCurrency(CoreCurrencySpecification.builder().id(BTC).name("BTC").digit(0).build());

        long ltv = svc.calculateCrossAccountLtvBps(up, 0L, setupSpecs(), partial,
                setupPrices(50_000L, 3_000L), USDT, true);
        assertEquals(Long.MAX_VALUE, ltv, "numeraireSpec 缺失 fail-close → 拒绝(MAX_VALUE)");
    }

    @Test
    void crossLtv_failClosed_markPriceMissing_returnsMaxValue() {
        UserProfile up = new UserProfile(1L, UserStatus.ACTIVE);
        up.crossLoanCollateral.put(BTC, 1L);
        CrossLoanRecord loan = new CrossLoanRecord(1L, 200L, USDT, 500, 0L);
        loan.outstandingPrincipal = 20_000L;
        up.crossLoans.put(200L, loan);

        // priceCache 空 → BTC 抵押折价拿不到 markPrice
        long ltv = svc.calculateCrossAccountLtvBps(up, 0L, setupSpecs(), setupCurrencies(),
                new IntObjectHashMap<>(), USDT, true);
        assertEquals(Long.MAX_VALUE, ltv, "markPrice 缺失 fail-close → 拒绝(MAX_VALUE)");
    }

    @Test
    void crossLtv_failClosed_allPresent_matchesFailOpenFiniteLtv() {
        UserProfile up = new UserProfile(1L, UserStatus.ACTIVE);
        // 与 crossLtv_happy 同构：1 BTC(weight 90%)=45000, 借 20000 USDT → LTV=4444 bps
        up.crossLoanCollateral.put(BTC, 1L);
        CrossLoanRecord loan = new CrossLoanRecord(1L, 200L, USDT, 500, 0L);
        loan.outstandingPrincipal = 20_000L;
        up.crossLoans.put(200L, loan);

        long ltvFailOpen = svc.calculateCrossAccountLtvBps(up, 0L, setupSpecs(), setupCurrencies(),
                setupPrices(50_000L, 3_000L), USDT);
        long ltvFailClosed = svc.calculateCrossAccountLtvBps(up, 0L, setupSpecs(), setupCurrencies(),
                setupPrices(50_000L, 3_000L), USDT, true);

        assertEquals(4444L, ltvFailOpen, "6-arg fail-open 有限 LTV");
        assertEquals(ltvFailOpen, ltvFailClosed, "价格齐全时 failClosed 与 failOpen 结果相同");
    }

    // ================================================================
    // LoanGlobalConfig 派生缓冲字段 (Task 1)
    // ================================================================

    @Test
    void loanGlobalConfig_defaultsIncludeDerivationBuffers() {
        LoanGlobalConfig g = new LoanGlobalConfig();
        assertEquals(2000, g.ltvLiquidationBufferBps, "默认 liquidation 缓冲 20%");
        assertEquals(1000, g.ltvMarginCallBufferBps, "默认 marginCall 缓冲 10%");
    }

    @Test
    void loanGlobalConfig_roundTrip_preservesBuffers() {
        LoanGlobalConfig g = new LoanGlobalConfig();
        g.ltvLiquidationBufferBps = 2500;
        g.ltvMarginCallBufferBps = 1200;
        Bytes<?> bytes = Bytes.allocateElasticOnHeap();
        g.writeMarshallable(bytes);
        LoanGlobalConfig restored = new LoanGlobalConfig(bytes);
        assertEquals(2500, restored.ltvLiquidationBufferBps);
        assertEquals(1200, restored.ltvMarginCallBufferBps);
        assertEquals(g.stateHash(), restored.stateHash());
    }
}
