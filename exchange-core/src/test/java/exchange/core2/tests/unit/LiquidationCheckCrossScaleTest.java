package exchange.core2.tests.unit;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.UserProfileService;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.processors.loan.LoanService;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 锁定 {@link LiquidationEngine#checkCross} 的一个数值边界：单仓维持保证金缩放到 quote 币种记账单位后归零。
 *
 * <p>{@code checkCross} 先按撮合内部单位（baseScaleK×quoteScaleK）算原始 MM 并守 {@code ==0}，再 down-scale
 * 到 currency 记账单位后拿它做风险度除数。当 {@code baseScaleK×quoteScaleK ≫ currencyScaleK} 时，一个很小的
 * 非零原始 MM 会被整数除法截断成 0——此时若不额外守卫就会在 leader 的 on-lane apply 上抛除零异常，
 * 中断命令 apply。这是 targeted 检测每个 cross 期货持有者、且在 equity 早退之前必经的路径，健康账户也不例外。
 */
class LiquidationCheckCrossScaleTest {

    private static final ExchangeConfiguration TEST_CFG = ExchangeConfiguration.defaultBuilder()
            .performanceCfg(PerformanceConfiguration.baseBuilder().build())
            .build();

    private static final int SYMBOL = 5001;
    private static final int BASE_CCY = 10;
    private static final int QUOTE_CCY = 20;

    private LiquidationEngine le;

    @AfterEach
    void tearDown() {
        if (le != null) {
            le.stop();
        }
    }

    /**
     * 原始 MM 非零、缩放后归零的 cross 期货持有者收到价格触发时，{@code checkCross} 不得因除零崩溃。
     */
    @Test
    void checkCross_scaledMaintenanceTruncatesToZero_noDivideByZero() {
        // baseScaleK×quoteScaleK = 100×100 = 10^4；quote 币种 digit=0 → currencyScaleK = 10^0 → down-scale 除以 10^4。
        // 原始 MM = truncMulDiv(notional=1e6, rate=5, scaleK=1000) = 5000，缩放后 5000 / 10^4 = 0。
        final TreeSortedMap<Long, Long> mm = new TreeSortedMap<>();
        mm.put(10_000_000L, 5L);
        final CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL).type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .baseCurrency(BASE_CCY).quoteCurrency(QUOTE_CCY)
                .baseScaleK(100L).quoteScaleK(100L)
                .takerFee(0L).makerFee(0L).liquidationFee(0L).feeScaleK(0L)
                .initMargin(1L).initMarginScaleK(1L)
                .maintenanceMargin(mm).maintenanceMarginScaleK(1000L)
                .maxLeverage(new TreeSortedMap<>())
                .build();
        final SymbolSpecificationProvider symbolSpec = new SymbolSpecificationProvider();
        symbolSpec.addSymbol(spec);

        final CurrencySpecificationProvider currencySpec = new CurrencySpecificationProvider();
        currencySpec.addCurrency(CoreCurrencySpecification.builder().id(BASE_CCY).name("BASE").digit(0).build());
        currencySpec.addCurrency(CoreCurrencySpecification.builder().id(QUOTE_CCY).name("QUOTE").digit(0).build());

        final IntObjectHashMap<LastPriceCacheRecord> priceCache = new IntObjectHashMap<>();
        final LastPriceCacheRecord price = new LastPriceCacheRecord();
        price.markPrice = 1_000_000L;
        price.bidPrice = 1_000_000L;
        price.askPrice = 1_000_000L;
        priceCache.put(SYMBOL, price);

        final UserProfileService ups = new UserProfileService();
        final UserProfile up = new UserProfile(42L, UserStatus.ACTIVE);
        final SymbolPositionRecord pos = new SymbolPositionRecord();
        pos.uid = 42L;
        pos.symbol = SYMBOL;
        pos.currency = QUOTE_CCY;
        pos.direction = PositionDirection.LONG;
        pos.openVolume = 1L;
        pos.openInitMarginSum = 1L;
        pos.openPriceSum = 1_000_000L;
        pos.marginMode = MarginMode.CROSS;
        up.positions.put(SYMBOL, pos);
        ups.getUserProfiles().put(42L, up);

        le = new LiquidationEngine(null, 1, TEST_CFG);
        le.updateProvider(symbolSpec, currencySpec, ups, priceCache, new LoanService());
        le.setCommandSubmitter((cmd, onApplied) -> {});
        le.onPositionOpened(up, pos);
        le.start();

        final OrderCommand targeted = new OrderCommand(1);
        targeted.command = OrderCommandType.MARKPRICE_ADJUSTMENT;
        targeted.symbol = SYMBOL;
        targeted.timestamp = 1_000L;

        assertDoesNotThrow(() -> le.checkPositions(targeted),
                "缩放后维持保证金归零不应导致 checkCross 除零崩溃");
    }
}
