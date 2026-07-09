package exchange.core2.tests.unit;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.IsolatedLoanRecord;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.processors.SharedPool;
import exchange.core2.core.processors.journaling.ISerializationProcessor;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.ObjLongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * RiskEngine.calculateLocked 定点测试 —— 覆盖 loan.md §9.2 新增 ③（Isolated 抵押）/ ④（Cross 抵押）扩项。
 *
 * <p>四阶段：
 * <ol>
 *   <li>期货 position margin</li>
 *   <li>spot exchangeLocked</li>
 *   <li>Isolated 借贷抵押（collateralCurrency == currency 的 loan record）</li>
 *   <li>Cross 借贷抵押（账户级多币种池）</li>
 * </ol>
 *
 * <p>③ 和 ④ 是本次改动新加的，重点验证：混合 4 项的 sum + 只匹配 collateralCurrency 的 loan 计入 + 跨币种 loan 不干扰。
 */
class RiskEngineCalculateLockedTest {

    private static final int BTC = 1;
    private static final int USDT = 2;
    private static final int ETH = 3;
    private static final long UID = 42L;

    private RiskEngine riskEngine;
    private UserProfile up;

    @BeforeEach
    void setUp() {
        SharedPool sharedPool = new SharedPool(1024, 128, 32);
        @SuppressWarnings("unchecked")
        ObjLongConsumer<OrderCommand> resultsConsumer = mock(ObjLongConsumer.class);
        ExchangeConfiguration cfg = ExchangeConfiguration.defaultBuilder().build();
        ISerializationProcessor serializationProcessor = mock(ISerializationProcessor.class);
        riskEngine = new RiskEngine(0, 1, serializationProcessor, sharedPool, cfg, resultsConsumer);

        // 注册 3 个 currency spec + 一个期货 spec + 一个现货 spec
        CoreCurrencySpecification btc =
            CoreCurrencySpecification.builder().id(BTC).name("BTC").digit(0).build();
        CoreCurrencySpecification usdt =
            CoreCurrencySpecification.builder().id(USDT).name("USDT").digit(0).build();
        CoreCurrencySpecification eth =
            CoreCurrencySpecification.builder().id(ETH).name("ETH").digit(0).build();
        riskEngine.getCurrencySpecificationProvider().addCurrency(btc);
        riskEngine.getCurrencySpecificationProvider().addCurrency(usdt);
        riskEngine.getCurrencySpecificationProvider().addCurrency(eth);

        // 期货 spec (USDT quote)：initMargin/scale 让 calculateLockedMargin 结果可控
        CoreSymbolSpecification futSpec = CoreSymbolSpecification.builder()
                .symbolId(1001).type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .quoteCurrency(USDT)
                .baseScaleK(1).quoteScaleK(1)
                .initMargin(10).initMarginScaleK(1)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 8L))
                .maintenanceMarginScaleK(1)
                .takerFee(0).makerFee(0).feeScaleK(1)
                .build();
        riskEngine.getSymbolSpecificationProvider().registerSymbol(1001, futSpec);

        // BTC/USDT 现货 spec（用于 loan collateral 场景）
        CoreSymbolSpecification spotSpec = CoreSymbolSpecification.builder()
                .symbolId(2001).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(BTC).quoteCurrency(USDT)
                .baseScaleK(1).quoteScaleK(1)
                .loanInitialLtvBps(6000).loanLiquidationLtvBps(8000)
                .loanRateBps(500).collateralWeightBps(9000)
                .build();
        riskEngine.getSymbolSpecificationProvider().registerSymbol(2001, spotSpec);

        up = new UserProfile(UID, UserStatus.ACTIVE);
        riskEngine.getUserProfileService().getUserProfiles().put(UID, up);
    }

    @Test
    void calculateLocked_emptyProfile_returnsZero() {
        assertEquals(0L, riskEngine.calculateLocked(up, BTC));
        assertEquals(0L, riskEngine.calculateLocked(up, USDT));
    }

    @Test
    void calculateLocked_spotExchangeLockedOnly_returnsExchangeLocked() {
        up.exchangeLocked.put(BTC, 5L);
        assertEquals(5L, riskEngine.calculateLocked(up, BTC), "② 单独 spot lock");
        assertEquals(0L, riskEngine.calculateLocked(up, USDT), "非匹配 currency 不受影响");
    }

    @Test
    void calculateLocked_isolatedLoanCollateralOnly_includedForCollateralCurrencyOnly() {
        // 2 个 loan：一个用 BTC 抵押，一个用 ETH 抵押；查 BTC 只该看到 1 个
        IsolatedLoanRecord btcLoan = new IsolatedLoanRecord(UID, 100L, BTC, USDT, 500, 0L);
        btcLoan.collateralAmount = 3L;
        btcLoan.outstandingPrincipal = 30_000L;
        up.isolatedLoans.put(100L, btcLoan);

        IsolatedLoanRecord ethLoan = new IsolatedLoanRecord(UID, 101L, ETH, USDT, 500, 0L);
        ethLoan.collateralAmount = 7L;
        ethLoan.outstandingPrincipal = 20_000L;
        up.isolatedLoans.put(101L, ethLoan);

        assertEquals(3L, riskEngine.calculateLocked(up, BTC), "③ 只 BTC 抵押的 loan 计入");
        assertEquals(7L, riskEngine.calculateLocked(up, ETH), "③ 只 ETH 抵押的 loan 计入");
        assertEquals(0L, riskEngine.calculateLocked(up, USDT), "loanCurrency 侧不作 collateral 计入 locked");
    }

    @Test
    void calculateLocked_multipleIsolatedLoansSameCollateralCurrency_summed() {
        IsolatedLoanRecord loan1 = new IsolatedLoanRecord(UID, 100L, BTC, USDT, 500, 0L);
        loan1.collateralAmount = 3L;
        up.isolatedLoans.put(100L, loan1);
        IsolatedLoanRecord loan2 = new IsolatedLoanRecord(UID, 101L, BTC, USDT, 500, 0L);
        loan2.collateralAmount = 5L;
        up.isolatedLoans.put(101L, loan2);

        assertEquals(8L, riskEngine.calculateLocked(up, BTC), "③ 同 currency 多 loan 累加");
    }

    @Test
    void calculateLocked_crossLoanCollateralOnly() {
        up.crossLoanCollateral.put(BTC, 4L);
        up.crossLoanCollateral.put(ETH, 6L);
        assertEquals(4L, riskEngine.calculateLocked(up, BTC), "④ Cross BTC 抵押计入");
        assertEquals(6L, riskEngine.calculateLocked(up, ETH), "④ Cross ETH 抵押计入");
        assertEquals(0L, riskEngine.calculateLocked(up, USDT));
    }

    @Test
    void calculateLocked_futuresMarginPlusLoanCollateral_summedIntoQuoteAndBase() {
        // 期货 position：quote=USDT，margin 会计入 USDT locked
        SymbolPositionRecord position = new SymbolPositionRecord();
        position.uid = UID;
        position.symbol = 1001;
        position.currency = USDT;
        position.direction = PositionDirection.LONG;
        position.openVolume = 100;
        position.openPriceSum = 100;
        position.openInitMarginSum = 500;
        up.positions.put(1001, position);

        // Isolated loan 用 BTC 抵押
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, 100L, BTC, USDT, 500, 0L);
        loan.collateralAmount = 2L;
        up.isolatedLoans.put(100L, loan);

        // Cross 用 BTC + USDT 抵押
        up.crossLoanCollateral.put(BTC, 3L);
        up.crossLoanCollateral.put(USDT, 1_000L);

        // Spot 挂单在 USDT 侧
        up.exchangeLocked.put(USDT, 200L);

        // BTC locked = 0 (①) + 0 (②) + 2 (③) + 3 (④) = 5
        assertEquals(5L, riskEngine.calculateLocked(up, BTC), "BTC：无 futures/spot lock，只 loan 抵押");
        // USDT locked = futures margin (①) + 200 (②) + 0 (③) + 1000 (④) = >0
        // futures margin 计算依赖 spec，具体数值不是本用例焦点；只断言"包含所有 4 项"关系：
        // USDT lock >= ② + ④ = 1200，且加了 ①
        long usdtLocked = riskEngine.calculateLocked(up, USDT);
        assertEquals(1_700L, usdtLocked, "USDT：①500 + ②200 + ③0 (BTC 是 collateralCurrency) + ④1000");
    }

    @Test
    void calculateLocked_isolatedAndCrossOnSameCurrency_summed() {
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, 100L, BTC, USDT, 500, 0L);
        loan.collateralAmount = 2L;
        up.isolatedLoans.put(100L, loan);
        up.crossLoanCollateral.put(BTC, 3L);

        assertEquals(5L, riskEngine.calculateLocked(up, BTC), "③ + ④ 同 currency 累加");
    }

    @Test
    void calculateLocked_isolatedLoanWithZeroCollateral_contributesZero() {
        // 死壳（collateralAmount=0）不贡献 locked
        IsolatedLoanRecord empty = new IsolatedLoanRecord(UID, 100L, BTC, USDT, 500, 0L);
        empty.collateralAmount = 0L;
        up.isolatedLoans.put(100L, empty);

        assertEquals(0L, riskEngine.calculateLocked(up, BTC));
    }
}
