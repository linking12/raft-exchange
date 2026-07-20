package exchange.core2.tests.unit;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.IsolatedLoanRecord;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.OrdersProcessingConfiguration;
import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.processors.LastPriceCacheRecord;
import exchange.core2.core.processors.SharedPool;
import exchange.core2.core.processors.journaling.ISerializationProcessor;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.ObjLongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * 锁定"贷款抵押不能被下游 NSF 当自由资金花掉 / 提走"这一风控隔离不变量。
 *
 * <p>抵押采用虚拟锁定：{@code LOAN_CREATE} / cross add-collateral 不把抵押移出 {@code accounts}，只登记
 * {@code collateralAmount} / {@code crossLoanCollateral}，靠 {@link RiskEngine#calculateLocked} 的 ③④ 项算作 locked。
 * 但提现（{@code BALANCE_ADJUSTMENT}）、逐仓加保证金、期货下单三处 NSF 用的是 {@code accounts − exchangeLocked (+ freeFuturesMargin)}，
 * <b>没走 {@code calculateLocked}</b>，于是贷款抵押可被提走或顶保证金，贷款变裸债、池子承损。此测试守住提现侧。
 */
class RiskEngineLoanCollateralNsfTest {

    private static final int BTC = 1;
    private static final int USDT = 2;
    private static final int BTC_USDT = 1001; // 贷款所属交易对占位 symbolId（本测试不按 symbolId 取价/强平）
    private static final long UID = 42L;

    private RiskEngine riskEngine;
    private UserProfile up;

    @BeforeEach
    void setUp() {
        SharedPool sharedPool = new SharedPool(1024, 128, 32);
        @SuppressWarnings("unchecked")
        ObjLongConsumer<OrderCommand> resultsConsumer = mock(ObjLongConsumer.class);
        ExchangeConfiguration cfg = ExchangeConfiguration.defaultBuilder()
                .ordersProcessingCfg(OrdersProcessingConfiguration.builder()
                        .marginTradingMode(OrdersProcessingConfiguration.MarginTradingMode.MARGIN_TRADING_ENABLED)
                        .build())
                .build();
        ISerializationProcessor serializationProcessor = mock(ISerializationProcessor.class);
        riskEngine = new RiskEngine(0, 1, serializationProcessor, sharedPool, cfg, resultsConsumer);

        riskEngine.getCurrencySpecificationProvider()
                .addCurrency(CoreCurrencySpecification.builder().id(BTC).name("BTC").digit(0).build());
        riskEngine.getCurrencySpecificationProvider()
                .addCurrency(CoreCurrencySpecification.builder().id(USDT).name("USDT").digit(0).build());

        up = new UserProfile(UID, UserStatus.ACTIVE);
        riskEngine.getUserProfileService().getUserProfiles().put(UID, up);
    }

    /** BALANCE_ADJUSTMENT 提现：price<0 = 提现额，orderType GTC(code 0)→ADJUSTMENT。 */
    private OrderCommand withdrawCmd(int currency, long amount) {
        OrderCommand cmd = new OrderCommand(1);
        cmd.command = OrderCommandType.BALANCE_ADJUSTMENT;
        cmd.uid = UID;
        cmd.symbol = currency;
        cmd.price = -amount;
        cmd.orderId = 7001L;
        cmd.orderType = OrderType.GTC;
        cmd.resultCode = CommandResultCode.NEW;
        return cmd;
    }

    /** 100 USDT 全额抵押在一笔 isolated 贷款上 → 提这 100 必须被 NSF 拒，账户不被抽干。 */
    @Test
    void withdraw_pledgedIsolatedCollateral_rejectedByNsf() {
        up.accounts.addToValue(USDT, 100L);
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, 100L, BTC_USDT, USDT, BTC, 500, 0L); // 抵押 USDT，借 BTC
        loan.collateralAmount = 100L;
        loan.outstandingPrincipal = 1L;
        up.isolatedLoans.put(100L, loan);

        OrderCommand cmd = withdrawCmd(USDT, 100L);
        riskEngine.preProcessCommand(1L, cmd);

        assertEquals(CommandResultCode.RISK_NSF, cmd.resultCode, "提取已质押的 isolated 抵押必须被 NSF 拒");
        assertEquals(100L, up.accounts.get(USDT), "被拒后账户余额不应被抽干");
    }

    /** 100 USDT 全额在 cross 抵押池 → 提这 100 必须被 NSF 拒。 */
    @Test
    void withdraw_pledgedCrossCollateral_rejectedByNsf() {
        up.accounts.addToValue(USDT, 100L);
        up.crossLoanCollateral.addToValue(USDT, 100L);

        OrderCommand cmd = withdrawCmd(USDT, 100L);
        riskEngine.preProcessCommand(1L, cmd);

        assertEquals(CommandResultCode.RISK_NSF, cmd.resultCode, "提取已质押的 cross 抵押必须被 NSF 拒");
        assertEquals(100L, up.accounts.get(USDT), "被拒后账户余额不应被抽干");
    }

    /** 抵押之外的自由资金仍可正常提取（不误伤）。 */
    @Test
    void withdraw_freeFundsAboveCollateral_allowed() {
        up.accounts.addToValue(USDT, 150L);
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, 100L, BTC_USDT, USDT, BTC, 500, 0L);
        loan.collateralAmount = 100L; // 100 锁定，50 自由
        loan.outstandingPrincipal = 1L;
        up.isolatedLoans.put(100L, loan);

        OrderCommand cmd = withdrawCmd(USDT, 50L);
        riskEngine.preProcessCommand(1L, cmd);

        assertEquals(CommandResultCode.SUCCESS, cmd.resultCode, "抵押之外的 50 自由资金应可提取");
        assertEquals(100L, up.accounts.get(USDT), "提走 50 后剩 100（仍覆盖抵押）");
    }

    /** 100 USDT 全额质押在 isolated 贷款上 → 下一张需保证金的期货单必须被 canPlaceMarginOrder NSF 拒（抵押不能顶期货保证金）。 */
    @Test
    void futuresOrder_pledgedCollateralCannotBackMargin_rejectedByNsf() {
        final int FUT = 5001;
        // initMargin 率 = 10/1000 = 1% → notional 5000 需保证金 50；余额 100 全被抵押锁 → free=0 → 拒
        CoreSymbolSpecification fut = CoreSymbolSpecification.builder()
                .symbolId(FUT).type(SymbolType.FUTURES_CONTRACT_PERPETUAL).quoteCurrency(USDT)
                .baseScaleK(1).quoteScaleK(1).initMargin(10).initMarginScaleK(1000)
                .maintenanceMargin(TreeSortedMap.newMapWith(1_000_000L, 8L)).maintenanceMarginScaleK(1000)
                .takerFee(0).makerFee(0).feeScaleK(1).build();
        riskEngine.getSymbolSpecificationProvider().registerSymbol(FUT, fut);
        riskEngine.getLastPriceCache().put(FUT, new LastPriceCacheRecord(1000L, 1000L, 1000L));

        up.accounts.addToValue(USDT, 100L);
        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, 100L, BTC_USDT, USDT, BTC, 500, 0L);
        loan.collateralAmount = 100L; // 全额质押
        loan.outstandingPrincipal = 1L;
        up.isolatedLoans.put(100L, loan);

        OrderCommand cmd = new OrderCommand(1);
        cmd.command = OrderCommandType.PLACE_ORDER;
        cmd.uid = UID;
        cmd.symbol = FUT;
        cmd.orderId = 8001L;
        cmd.action = OrderAction.BID;
        cmd.orderType = OrderType.GTC;
        cmd.size = 5;
        cmd.price = 1000;
        cmd.reserveBidPrice = 1000;
        cmd.marginMode = MarginMode.ISOLATED;
        cmd.leverage = 1;
        cmd.resultCode = CommandResultCode.NEW;

        riskEngine.preProcessCommand(1L, cmd);
        assertEquals(CommandResultCode.RISK_NSF, cmd.resultCode, "质押的 isolated 抵押不能顶期货保证金");
    }
}
