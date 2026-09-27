package exchange.core2.tests.unit;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * 锁定 {@code canPlaceMarginOrder} 的跨 symbol scale 一致性（H3）。
 *
 * <p>下单 NSF 会把账户里<b>其它</b> CROSS 期货仓的浮盈/所需保证金汇入 {@code crossFreeMargin} 抵扣。旧实现把不同 symbol 的
 * 贡献以<b>各自 sizePrice 单位</b>（baseScaleK×quoteScaleK）裸加、最后只用<b>新单 symbol</b> 的 scale 折算一次——当两个 symbol
 * 的 scale 不同（同 quote 币、不同 lot/step）时就串味：其它仓的保证金被放大/缩小数量级，导致本该拒的单放行、或本该放的单误拒。
 *
 * <p>本例：账户持有 symbol B（scale=10^4）的 CROSS 仓、占用保证金折 1 USDT；在 symbol A（identity scale）上下小单。
 * 修复前 B 的保证金被当成 10000 USDT 计入需求 → 误拒；修复后按 B 自身 scale 折算=1 USDT → 正常放行。
 */
class RiskEngineCrossMarginScaleTest {

    private static final int USDT = 2;
    private static final int BASE_A = 10;
    private static final int BASE_B = 11;
    private static final int SYMBOL_A = 7001; // identity scale：下单标的
    private static final int SYMBOL_B = 7002; // scale=10^4：已持有 CROSS 仓
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
                .addCurrency(CoreCurrencySpecification.builder().id(USDT).name("USDT").digit(0).build());
        riskEngine.getCurrencySpecificationProvider()
                .addCurrency(CoreCurrencySpecification.builder().id(BASE_A).name("A").digit(0).build());
        riskEngine.getCurrencySpecificationProvider()
                .addCurrency(CoreCurrencySpecification.builder().id(BASE_B).name("B").digit(0).build());

        riskEngine.getSymbolSpecificationProvider().registerSymbol(SYMBOL_A, futSpec(SYMBOL_A, BASE_A, 1L, 1L));
        riskEngine.getSymbolSpecificationProvider().registerSymbol(SYMBOL_B, futSpec(SYMBOL_B, BASE_B, 100L, 100L));

        up = new UserProfile(UID, UserStatus.ACTIVE);
        // symbol B 的 CROSS 仓：无 pending → 所需保证金 = openInitMarginSum = 10000（sizePrice, B scale）= 1 USDT。
        // markPrice=10000、openPriceSum=10000、openVolume=1 → 未实现盈亏 = 0，隔离出"保证金 scale"这一变量。
        SymbolPositionRecord posB = new SymbolPositionRecord();
        posB.uid = UID;
        posB.symbol = SYMBOL_B;
        posB.currency = USDT;
        posB.direction = PositionDirection.LONG;
        posB.openVolume = 1;
        posB.openPriceSum = 10_000L;
        posB.openInitMarginSum = 10_000L;
        posB.marginMode = MarginMode.CROSS;
        posB.updateLeverage(1);
        up.positions.put(SYMBOL_B, posB);
        up.accounts.addToValue(USDT, 100L); // 够覆盖正确口径(≈2)、远不够被放大 10000× 的错误口径(≈10001)
        riskEngine.getUserProfileService().getUserProfiles().put(UID, up);

        riskEngine.getLastPriceCache().put(SYMBOL_A, new LastPriceCacheRecord(100L, 100L, 100L));
        riskEngine.getLastPriceCache().put(SYMBOL_B, new LastPriceCacheRecord(10_000L, 10_000L, 10_000L));
    }

    private static CoreSymbolSpecification futSpec(int symbolId, int baseCurrency, long baseScaleK, long quoteScaleK) {
        return CoreSymbolSpecification.builder()
                .symbolId(symbolId).type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .baseCurrency(baseCurrency).quoteCurrency(USDT)
                .baseScaleK(baseScaleK).quoteScaleK(quoteScaleK)
                .initMargin(10).initMarginScaleK(1000)
                .maintenanceMargin(TreeSortedMap.newMapWith(1_000_000_000L, 8L)).maintenanceMarginScaleK(1000)
                .takerFee(0).makerFee(0).feeScaleK(1)
                .build();
    }

    /** 在 identity-scale 的 A 上下小单，账户另持 scale=10^4 的 B 仓：B 的保证金必须按 B 自身 scale 折算，单应放行、不被误拒。 */
    @Test
    void placeMarginOrder_otherCrossPositionDifferentScale_marginNotBlownUp_orderAccepted() {
        OrderCommand cmd = new OrderCommand(1);
        cmd.command = OrderCommandType.PLACE_ORDER;
        cmd.uid = UID;
        cmd.symbol = SYMBOL_A;
        cmd.orderId = 9001L;
        cmd.action = OrderAction.BID;
        cmd.orderType = OrderType.GTC;
        cmd.size = 1;
        cmd.price = 100;
        cmd.reserveBidPrice = 100;
        cmd.marginMode = MarginMode.CROSS;
        cmd.leverage = 1;
        cmd.resultCode = CommandResultCode.NEW;

        riskEngine.preProcessCommand(1L, cmd);

        assertEquals(CommandResultCode.VALID_FOR_MATCHING_ENGINE, cmd.resultCode,
                "B 仓保证金应按其自身 scale 折算(=1 USDT)，不应被放大 10000× 导致误拒");
    }
}
