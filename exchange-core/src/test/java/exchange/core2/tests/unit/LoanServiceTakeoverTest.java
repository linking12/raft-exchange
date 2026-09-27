package exchange.core2.tests.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.CrossLoanRecord;
import exchange.core2.core.common.SymbolLoanSpecification;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.loan.LoanService;

/**
 * {@link LoanService#takeOverCrossLoan} 直接单测：LIF 按债务占比承接的分摊数学。
 *
 * <p>此前只经 postProcess 间接覆盖，分摊比例 / 扣减顺序 / 尘埃截断 / fail-closed 都没有独立断言。
 */
class LoanServiceTakeoverTest {

    private static final int BTC = 1;
    private static final int USDT = 2;
    private static final int ETH = 3;
    private static final int SYMBOL_BTC_USDT = 101;
    private static final int SYMBOL_ETH_USDT = 102;

    private LoanService svc;
    private UserProfile up;

    @BeforeEach
    void setUp() {
        svc = new LoanService();
        svc.getGlobalConfig().numeraireCurrency = USDT;
        up = new UserProfile(1L, UserStatus.ACTIVE);
    }

    private static SymbolSpecificationProvider specs() {
        SymbolSpecificationProvider p = new SymbolSpecificationProvider();
        p.registerSymbol(SYMBOL_BTC_USDT, CoreSymbolSpecification.builder()
            .symbolId(SYMBOL_BTC_USDT).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
            .baseCurrency(BTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
            .loanConfig(SymbolLoanSpecification.builder().build()).build());
        p.registerSymbol(SYMBOL_ETH_USDT, CoreSymbolSpecification.builder()
            .symbolId(SYMBOL_ETH_USDT).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
            .baseCurrency(ETH).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
            .loanConfig(SymbolLoanSpecification.builder().build()).build());
        return p;
    }

    private static CurrencySpecificationProvider currencies() {
        CurrencySpecificationProvider p = new CurrencySpecificationProvider();
        // Cross 抵押折价率是币种级：权重挂在各 pair 的 base 币上
        p.addCurrency(CoreCurrencySpecification.builder().id(BTC).name("BTC").digit(0).collateralWeightBps(9000).build());
        p.addCurrency(CoreCurrencySpecification.builder().id(USDT).name("USDT").digit(0).build());
        p.addCurrency(CoreCurrencySpecification.builder().id(ETH).name("ETH").digit(0).collateralWeightBps(8000).build());
        return p;
    }

    private static IntObjectHashMap<LastPriceCacheRecord> prices(long btc, long eth) {
        IntObjectHashMap<LastPriceCacheRecord> c = new IntObjectHashMap<>();
        LastPriceCacheRecord b = new LastPriceCacheRecord();
        b.markPrice = btc;
        c.put(SYMBOL_BTC_USDT, b);
        LastPriceCacheRecord e = new LastPriceCacheRecord();
        e.markPrice = eth;
        c.put(SYMBOL_ETH_USDT, e);
        return c;
    }

    private CrossLoanRecord addLoan(long loanId, long principal) {
        // loanCurrency=USDT=quoteCurrency，匹配的现货 pair 是 BTC/USDT；takeOver 走 numeraire 直估不读此 symbolId，非零占位即可
        CrossLoanRecord loan = new CrossLoanRecord(1L, loanId, SYMBOL_BTC_USDT, USDT, 0, 0L);
        loan.outstandingPrincipal = principal;
        up.crossLoans.put(loanId, loan);
        return loan;
    }

    private boolean takeOver(CrossLoanRecord target) {
        return svc.takeOverCrossLoan(up, target, 0L, specs(), currencies(), prices(50_000L, 3_000L));
    }

    @Test
    void takeOver_singleLoan_takesAllCollateral_andRepaysPoolFromLif() {
        up.accounts.put(BTC, 2L);
        up.crossLoanCollateral.put(BTC, 2L); // 市值 100000
        svc.getLoanPoolBorrowed().put(USDT, 60_000L);
        CrossLoanRecord loan = addLoan(200L, 60_000L);

        assertTrue(takeOver(loan));

        assertEquals(0L, up.crossLoanCollateral.get(BTC), "唯一一笔债 → 占比 1 → 抵押全取");
        assertEquals(0L, up.accounts.get(BTC), "抵押由虚拟锁定转为真实扣走");
        assertEquals(2L, svc.getLoanInsuranceFund().get(BTC));
        assertEquals(-60_000L, svc.getLoanInsuranceFund().get(USDT), "LIF 垫付本息");
        assertEquals(60_000L, svc.getLoanPoolAvailable().get(USDT), "池子全额回血");
        assertEquals(0L, svc.getLoanPoolBorrowed().get(USDT));
    }

    @Test
    void takeOver_multiLoan_splitsCollateralByDebtShare() {
        up.accounts.put(BTC, 4L);
        up.crossLoanCollateral.put(BTC, 4L); // 市值 200000
        CrossLoanRecord target = addLoan(200L, 30_000L);
        addLoan(201L, 90_000L); // 总债 120000，target 占 1/4

        assertTrue(takeOver(target));

        assertEquals(1L, svc.getLoanInsuranceFund().get(BTC), "200000 × 30000/120000 = 50000 → 1 BTC");
        assertEquals(3L, up.crossLoanCollateral.get(BTC), "其余留给借款人，未触及强平线的债不被牵连");
    }

    @Test
    void takeOver_multiCurrency_deductsByWeightDescThenCurrencyAsc() {
        up.accounts.put(BTC, 1L);
        up.accounts.put(ETH, 20L);
        up.crossLoanCollateral.put(BTC, 1L); // 50000，weight 9000
        up.crossLoanCollateral.put(ETH, 20L); // 60000，weight 8000
        CrossLoanRecord target = addLoan(200L, 55_000L); // 唯一债 → 应取满 110000

        assertTrue(takeOver(target));

        assertEquals(1L, svc.getLoanInsuranceFund().get(BTC), "weight 高者先扣");
        assertEquals(20L, svc.getLoanInsuranceFund().get(ETH));
        assertEquals(0L, up.crossLoanCollateral.get(BTC));
        assertEquals(0L, up.crossLoanCollateral.get(ETH));
    }

    @Test
    void takeOver_zeroWeightCollateral_notTaken() {
        SymbolSpecificationProvider p = new SymbolSpecificationProvider();
        p.registerSymbol(SYMBOL_BTC_USDT, CoreSymbolSpecification.builder()
            .symbolId(SYMBOL_BTC_USDT).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
            .baseCurrency(BTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
            .loanConfig(SymbolLoanSpecification.builder().build()).build());
        // BTC 不配抵押权重（=0）表示不能作 Cross 抵押
        CurrencySpecificationProvider zeroWeightCurrencies = new CurrencySpecificationProvider();
        zeroWeightCurrencies.addCurrency(CoreCurrencySpecification.builder().id(BTC).name("BTC").digit(0).build());
        zeroWeightCurrencies.addCurrency(CoreCurrencySpecification.builder().id(USDT).name("USDT").digit(0).build());
        up.accounts.put(BTC, 2L);
        up.crossLoanCollateral.put(BTC, 2L);
        CrossLoanRecord target = addLoan(200L, 10_000L);

        assertTrue(svc.takeOverCrossLoan(up, target, 0L, p, zeroWeightCurrencies, prices(50_000L, 3_000L)));

        assertEquals(2L, up.crossLoanCollateral.get(BTC), "零权重币从未支撑过借款额度，接管也不能扣");
        assertEquals(0L, svc.getLoanInsuranceFund().get(BTC));
        assertEquals(-10_000L, svc.getLoanInsuranceFund().get(USDT), "债照样代偿，只是收不到抵押");
    }

    @Test
    void takeOver_noNumeraire_failsClosed_stateUntouched() {
        svc.getGlobalConfig().numeraireCurrency = 0;
        up.accounts.put(BTC, 2L);
        up.crossLoanCollateral.put(BTC, 2L);
        CrossLoanRecord target = addLoan(200L, 10_000L);

        assertFalse(takeOver(target), "估值基准缺失 → 拒绝接管");
        assertEquals(2L, up.crossLoanCollateral.get(BTC), "不用失真价格扣用户抵押");
        assertTrue(svc.getLoanInsuranceFund().isEmpty());
    }

    @Test
    void takeOver_missingPrice_failsClosed() {
        up.accounts.put(BTC, 2L);
        up.crossLoanCollateral.put(BTC, 2L);
        CrossLoanRecord target = addLoan(200L, 10_000L);

        assertFalse(svc.takeOverCrossLoan(up, target, 0L, specs(), currencies(), new IntObjectHashMap<>()));
        assertEquals(2L, up.crossLoanCollateral.get(BTC));
    }

    @Test
    void takeOver_interestGoesToRevenue_principalToPool() {
        up.accounts.put(BTC, 1L);
        up.crossLoanCollateral.put(BTC, 1L);
        svc.getLoanPoolBorrowed().put(USDT, 10_000L);
        CrossLoanRecord loan = addLoan(200L, 10_000L);
        loan.accumulatedInterest = 500L;

        assertTrue(takeOver(loan));

        assertEquals(-10_500L, svc.getLoanInsuranceFund().get(USDT), "LIF 付本息合计");
        assertEquals(10_000L, svc.getLoanPoolAvailable().get(USDT), "本金回池");
        assertEquals(500L, svc.getInterestRevenue().get(USDT), "利息落收入");
        assertEquals(0L, svc.getLoanPoolBorrowed().get(USDT));
    }
}
