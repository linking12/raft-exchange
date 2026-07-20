package exchange.core2.tests.unit;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 锁定 {@code LEVERAGE_ADJUSTMENT} 的杠杆入参校验。
 *
 * <p>{@code adjustLeverage} 把 {@code cmd.leverage} 原样喂进 {@link CoreSymbolSpecification#calculateInitMargin}（= notional/leverage），
 * 但 {@code isValidLeverage} 在 symbol 未配 maxLeverage 档时对任意 {@code leverage >= 0} 放行、甚至表空时连负数都放行。
 * 于是 {@code leverage == 0} 会在命令 apply 上确定性除零抛异常——这是所有副本会同时崩的 poison-pill；
 * {@code leverage < 0} 则悄悄把仓位杠杆改成负数、污染后续保证金/破产价。
 *
 * <p>apply 侧本就用 {@code updateLeverage}（0→1 归一）落地，故正确行为是：0 归一为 1x（SUCCESS），负数直接拒。
 */
class RiskEngineAdjustLeverageTest {

    private static final int BTC = 1;
    private static final int USDT = 2;
    private static final int SYMBOL = 1001;
    private static final long UID = 42L;

    private RiskEngine riskEngine;
    private UserProfile up;

    @BeforeEach
    void setUp() {
        SharedPool sharedPool = new SharedPool(1024, 128, 32);
        @SuppressWarnings("unchecked")
        ObjLongConsumer<OrderCommand> resultsConsumer = mock(ObjLongConsumer.class);
        // adjustLeverage 前置要求 margin trading 开启
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

        // 期货 spec：故意不配 maxLeverage 档 —— 正是 isValidLeverage 放行 0/负数的现实场景
        CoreSymbolSpecification futSpec = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL).type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .quoteCurrency(USDT)
                .baseScaleK(1).quoteScaleK(1)
                .initMargin(10).initMarginScaleK(1)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 8L))
                .maintenanceMarginScaleK(1)
                .takerFee(0).makerFee(0).feeScaleK(1)
                .build();
        riskEngine.getSymbolSpecificationProvider().registerSymbol(SYMBOL, futSpec);

        up = new UserProfile(UID, UserStatus.ACTIVE);
        SymbolPositionRecord position = new SymbolPositionRecord();
        position.uid = UID;
        position.symbol = SYMBOL;
        position.currency = USDT;
        position.direction = PositionDirection.LONG;
        position.openVolume = 100;
        position.openPriceSum = 100_000;
        position.openInitMarginSum = 500;
        position.marginMode = MarginMode.ISOLATED;
        position.updateLeverage(20);
        up.positions.put(SYMBOL, position);
        up.accounts.addToValue(USDT, 100_000_000L); // 充足余额，确保 NSF 不挡（隔离验的是杠杆校验本身）
        riskEngine.getUserProfileService().getUserProfiles().put(UID, up);

        riskEngine.getLastPriceCache().put(SYMBOL, new LastPriceCacheRecord(1000L, 1000L, 1000L));
    }

    private OrderCommand leverageCmd(int leverage) {
        OrderCommand cmd = new OrderCommand(1);
        cmd.command = OrderCommandType.LEVERAGE_ADJUSTMENT;
        cmd.uid = UID;
        cmd.symbol = SYMBOL;
        cmd.leverage = leverage;
        cmd.resultCode = CommandResultCode.NEW;
        return cmd;
    }

    /** leverage=0 不得在 apply 上除零崩溃；应按 0→1 归一（与 updateLeverage 一致）成功。 */
    @Test
    void adjustLeverage_zeroLeverage_coercedToOne_noDivideByZero() {
        OrderCommand cmd = leverageCmd(0);

        assertDoesNotThrow(() -> riskEngine.preProcessCommand(1L, cmd),
                "leverage=0 不应导致 calculateInitMargin 除零崩溃（poison pill）");
        assertEquals(CommandResultCode.SUCCESS, cmd.resultCode, "0 应归一为 1x 并成功");
        assertTrue(up.positions.get(SYMBOL).isSameLeverage(1), "仓位杠杆应被设为 1x");
    }

    /** leverage<0 必须拒（updateLeverage 不会归一负数，落地会污染保证金），且不改仓位杠杆。 */
    @Test
    void adjustLeverage_negativeLeverage_rejected_leaveLeverageUnchanged() {
        OrderCommand cmd = leverageCmd(-5);

        assertDoesNotThrow(() -> riskEngine.preProcessCommand(1L, cmd));
        assertEquals(CommandResultCode.RISK_INVALID_LEVERAGE, cmd.resultCode, "负杠杆应被拒");
        assertTrue(up.positions.get(SYMBOL).isSameLeverage(20), "被拒后仓位杠杆保持原值 20x");
    }
}
