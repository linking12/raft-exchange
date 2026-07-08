package exchange.core2.tests.unit;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.PositionMode;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.common.cmd.OrderCommandType;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖 UserProfile 在双向持仓（HEDGE）与单向持仓（ONEWAY）下，
 * createPositionsKey / countPositionRecord / processPositionRecord 的关键分支，
 * 以及 SymbolPositionRecord 的默认 MarginMode。
 *
 * 关键约定（见 UserProfile.createPositionsKey）：
 *   ONEWAY → key 永远为 symbol，与方向/命令无关
 *   HEDGE  → 普通下单按 BID=+symbol / ASK=-symbol；CLOSE_POSITION / FORCE_LIQUIDATION 时翻转，
 *           因为平仓单的方向与目标仓位相反（平多用 ASK，但要找的是 LONG 仓位 key=+symbol）。
 */
class UserProfileTest {

    private static final int SYMBOL = 1001;
    private static final long UID = 42L;

    private UserProfile profile;

    @BeforeEach
    void setUp() {
        profile = new UserProfile(UID, UserStatus.ACTIVE);
    }

    // ============== 默认状态 ==============

    @Test
    void testDefaults() {
        assertEquals(UID, profile.uid);
        assertEquals(PositionMode.ONEWAY, profile.positionMode);
        assertEquals(UserStatus.ACTIVE, profile.userStatus);
        assertEquals(0, profile.processedExternalIds.size());
        assertEquals(0, profile.positions.size());
        assertEquals(0, profile.accounts.size());
    }

    // ============== createPositionsKey(symbol, action, command) ==============

    @Test
    void testCreatePositionsKey_OneWay_IgnoresActionAndCommand() {
        // ONEWAY 下 key 恒为 symbol，与 action / command 全部无关。
        profile.positionMode = PositionMode.ONEWAY;
        assertEquals(SYMBOL, profile.createPositionsKey(SYMBOL, OrderAction.BID, OrderCommandType.PLACE_ORDER));
        assertEquals(SYMBOL, profile.createPositionsKey(SYMBOL, OrderAction.ASK, OrderCommandType.PLACE_ORDER));
        assertEquals(SYMBOL, profile.createPositionsKey(SYMBOL, OrderAction.BID, OrderCommandType.CLOSE_POSITION));
        assertEquals(SYMBOL, profile.createPositionsKey(SYMBOL, OrderAction.ASK, OrderCommandType.FORCE_LIQUIDATION));
    }

    @Test
    void testCreatePositionsKey_Hedge_PlaceOrder_BidIsPositive_AskIsNegative() {
        // HEDGE 普通下单：买开 -> 多仓 key=+symbol；卖开 -> 空仓 key=-symbol。
        profile.positionMode = PositionMode.HEDGE;
        assertEquals(SYMBOL, profile.createPositionsKey(SYMBOL, OrderAction.BID, OrderCommandType.PLACE_ORDER));
        assertEquals(-SYMBOL, profile.createPositionsKey(SYMBOL, OrderAction.ASK, OrderCommandType.PLACE_ORDER));
    }

    @Test
    void testCreatePositionsKey_Hedge_ClosePositionFlipsToOpposite() {
        // 平多仓时下卖单（ASK），但要找的是已有的多仓记录 +symbol；卖单原本会映射到 -symbol，所以要翻转。
        profile.positionMode = PositionMode.HEDGE;
        assertEquals(SYMBOL, profile.createPositionsKey(SYMBOL, OrderAction.ASK, OrderCommandType.CLOSE_POSITION));
        assertEquals(-SYMBOL, profile.createPositionsKey(SYMBOL, OrderAction.BID, OrderCommandType.CLOSE_POSITION));
    }

    @Test
    void testCreatePositionsKey_Hedge_ForceLiquidationFlipsToOpposite() {
        // 强平和 CLOSE_POSITION 走同一翻转分支。
        profile.positionMode = PositionMode.HEDGE;
        assertEquals(SYMBOL, profile.createPositionsKey(SYMBOL, OrderAction.ASK, OrderCommandType.FORCE_LIQUIDATION));
        assertEquals(-SYMBOL, profile.createPositionsKey(SYMBOL, OrderAction.BID, OrderCommandType.FORCE_LIQUIDATION));
    }

    // ============== createPositionsKey(SymbolPositionRecord) ==============

    @Test
    void testCreatePositionsKey_FromRecord_OneWayUsesSymbol() {
        profile.positionMode = PositionMode.ONEWAY;
        SymbolPositionRecord longPos = newPosition(PositionDirection.LONG);
        SymbolPositionRecord shortPos = newPosition(PositionDirection.SHORT);
        // ONEWAY 不区分方向，key 都是 symbol。
        assertEquals(SYMBOL, profile.createPositionsKey(longPos));
        assertEquals(SYMBOL, profile.createPositionsKey(shortPos));
    }

    @Test
    void testCreatePositionsKey_FromRecord_HedgeUsesDirectionMultiplier() {
        profile.positionMode = PositionMode.HEDGE;
        // direction.multiplier: LONG=+1, SHORT=-1，所以 HEDGE 下直接 multiplier × symbol。
        assertEquals(SYMBOL, profile.createPositionsKey(newPosition(PositionDirection.LONG)));
        assertEquals(-SYMBOL, profile.createPositionsKey(newPosition(PositionDirection.SHORT)));
    }

    // ============== countPositionRecord ==============

    @Test
    void testCountPositionRecord_OneWayOnlyChecksPositiveKey() {
        // ONEWAY 即使误把记录写到 -symbol 也不会被计入。
        profile.positionMode = PositionMode.ONEWAY;
        profile.positions.put(SYMBOL, newPosition(PositionDirection.LONG));
        profile.positions.put(-SYMBOL, newPosition(PositionDirection.SHORT));

        assertEquals(1, profile.countPositionRecord(SYMBOL, p -> true));
        assertEquals(0, profile.countPositionRecord(SYMBOL, p -> p.direction == PositionDirection.SHORT));
    }

    @Test
    void testCountPositionRecord_HedgeChecksBothSides() {
        profile.positionMode = PositionMode.HEDGE;
        profile.positions.put(SYMBOL, newPosition(PositionDirection.LONG));
        profile.positions.put(-SYMBOL, newPosition(PositionDirection.SHORT));

        assertEquals(2, profile.countPositionRecord(SYMBOL, p -> true));
        assertEquals(1, profile.countPositionRecord(SYMBOL, p -> p.direction == PositionDirection.LONG));
        assertEquals(1, profile.countPositionRecord(SYMBOL, p -> p.direction == PositionDirection.SHORT));
    }

    @Test
    void testCountPositionRecord_HedgeOnlyOneSideExists() {
        // HEDGE 模式下 -symbol 不存在时 count 不会爆 NPE，只统计存在的那侧。
        profile.positionMode = PositionMode.HEDGE;
        profile.positions.put(SYMBOL, newPosition(PositionDirection.LONG));

        assertEquals(1, profile.countPositionRecord(SYMBOL, p -> true));
    }

    // ============== processPositionRecord ==============

    @Test
    void testProcessPositionRecord_OneWayOnlyTouchesPositiveKey() {
        profile.positionMode = PositionMode.ONEWAY;
        SymbolPositionRecord longPos = newPosition(PositionDirection.LONG);
        SymbolPositionRecord shortPos = newPosition(PositionDirection.SHORT);
        profile.positions.put(SYMBOL, longPos);
        profile.positions.put(-SYMBOL, shortPos);

        AtomicInteger count = new AtomicInteger();
        profile.processPositionRecord(SYMBOL, p -> {
            count.incrementAndGet();
            assertSame(longPos, p); // 必须是 +symbol 那条
        });
        assertEquals(1, count.get());
    }

    @Test
    void testProcessPositionRecord_HedgeTouchesBothSides() {
        profile.positionMode = PositionMode.HEDGE;
        SymbolPositionRecord longPos = newPosition(PositionDirection.LONG);
        SymbolPositionRecord shortPos = newPosition(PositionDirection.SHORT);
        profile.positions.put(SYMBOL, longPos);
        profile.positions.put(-SYMBOL, shortPos);

        AtomicInteger longSeen = new AtomicInteger();
        AtomicInteger shortSeen = new AtomicInteger();
        profile.processPositionRecord(SYMBOL, p -> {
            if (p.direction == PositionDirection.LONG) longSeen.incrementAndGet();
            else if (p.direction == PositionDirection.SHORT) shortSeen.incrementAndGet();
        });
        assertEquals(1, longSeen.get());
        assertEquals(1, shortSeen.get());
    }

    @Test
    void testProcessPositionRecord_HedgeOnlyOneSideExists() {
        // HEDGE 下另一侧仓位为空时不会触发 consumer，也不会 NPE。
        profile.positionMode = PositionMode.HEDGE;
        SymbolPositionRecord shortPos = newPosition(PositionDirection.SHORT);
        profile.positions.put(-SYMBOL, shortPos);

        AtomicInteger count = new AtomicInteger();
        profile.processPositionRecord(SYMBOL, p -> {
            count.incrementAndGet();
            assertSame(shortPos, p);
        });
        assertEquals(1, count.get());
    }

    // ============== MarginMode 默认值 ==============

    @Test
    void testNewPositionRecordDefaultsToIsolatedMargin() {
        // SymbolPositionRecord 默认 marginMode = ISOLATED（见类字段声明）。
        // 这个默认对默认逐仓的产品语义很关键，破坏它会让全仓/逐仓判断悄悄翻车。
        SymbolPositionRecord pos = new SymbolPositionRecord();
        assertEquals(MarginMode.ISOLATED, pos.marginMode);
    }

    // ============== processedExternalIds 集成 ==============

    /**
     * snapshot round-trip：UserProfile 序列化 + 反序列化后，processedExternalIds 内的去重历史必须保留——
     * 否则 leader 切换 / snapshot install 后，BALANCE_ADJUSTMENT / MARGIN_ADJUSTMENT 的幂等保护会失效，
     * 已处理过的外部事件会被当成新事件再次落账。
     */
    @Test
    void serialization_preservesProcessedExternalIds() {
        profile.processedExternalIds.tryClaim(1001L);
        profile.processedExternalIds.tryClaim(1002L);
        profile.processedExternalIds.tryClaim(1003L);
        // 其他字段也填一点，防 ctor 默认值意外掩盖 dedup 序列化失败
        profile.accounts.put(840, 5000L);

        net.openhft.chronicle.bytes.Bytes<?> buf = net.openhft.chronicle.bytes.Bytes.allocateElasticOnHeap(256);
        profile.writeMarshallable(buf);

        UserProfile restored = new UserProfile(buf);
        assertEquals(3, restored.processedExternalIds.size(), "dedup 大小必须保留");
        // 已 claim 的 ID 再 claim 仍应被拒，证明 round-trip 后 dedup 状态完整
        assertFalse(restored.processedExternalIds.tryClaim(1001L), "1001 已 claim");
        assertFalse(restored.processedExternalIds.tryClaim(1002L), "1002 已 claim");
        assertFalse(restored.processedExternalIds.tryClaim(1003L), "1003 已 claim");
        // 新 ID 仍能 claim，证明集合可用
        assertTrue(restored.processedExternalIds.tryClaim(9999L), "新 ID 应可 claim");
    }

    /**
     * UserProfile.stateHash 必须把 processedExternalIds 算进去——否则不同节点上同一用户的 dedup 状态发散
     * （罕见但理论上可能：scanner-set 时序差异 + ctx transient bug 等），raft 集群层面就检测不到。
     */
    @Test
    void stateHash_sensitiveToProcessedExternalIds() {
        UserProfile a = new UserProfile(UID, UserStatus.ACTIVE);
        UserProfile b = new UserProfile(UID, UserStatus.ACTIVE);
        assertEquals(a.stateHash(), b.stateHash(), "起点相同");

        a.processedExternalIds.tryClaim(42L);
        assertNotEquals(a.stateHash(), b.stateHash(),
                "a 多 claim 了一个 ID，stateHash 必须不同——否则跨节点 dedup 发散无法检测");

        b.processedExternalIds.tryClaim(42L);
        assertEquals(a.stateHash(), b.stateHash(), "同样 claim 42 后，stateHash 应重新相等");
    }

    // ============== calculateCrossAvailable ==============
    // 三条路径共享（LiquidationEngine / SingleUserReportQuery / FundEventsHelper）—— 边界回归保护

    private static final int USD = 840;
    private static final int BTC_SYMBOL = 2001;
    private static final int LTC_SYMBOL = 2002;

    @Test
    void calculateCrossAvailable_noIsolatedPositions_equalsAccountsMinusExchangeLocked() {
        // 无 ISO 仓时 crossAvailable = accounts − exchangeLocked（跟旧裸算一致）
        profile.accounts.put(USD, 1000L);
        profile.exchangeLocked.put(USD, 200L);
        long avail = profile.calculateCrossAvailable(USD, currencySpec(USD), symbolSpecLookup());
        assertEquals(800L, avail);
    }

    @Test
    void calculateCrossAvailable_crossPositionDoesNotAffect_onlyIsolatedGetsStripped() {
        // CROSS 仓的 margin 不减（跟 doc §3 一致：cross margin 从 crossBalance 内部共享，不再独立扣）
        profile.accounts.put(USD, 1000L);
        profile.exchangeLocked.put(USD, 0L);
        SymbolPositionRecord crossPos = newFuturesPosition(BTC_SYMBOL, MarginMode.CROSS, USD);
        crossPos.openVolume = 1;
        crossPos.openInitMarginSum = 300;
        crossPos.openPriceSum = 3000;
        crossPos.direction = PositionDirection.LONG;
        profile.positions.put(BTC_SYMBOL, crossPos);

        long avail = profile.calculateCrossAvailable(USD, currencySpec(USD),
            symbolSpecLookup(BTC_SYMBOL, futuresSpec(BTC_SYMBOL)));
        assertEquals(1000L, avail); // CROSS 未剥离
    }

    @Test
    void calculateCrossAvailable_isolatedMarginStripped() {
        // ISO 仓的 calculateRequiredMarginForFutures 从 crossAvailable 扣
        profile.accounts.put(USD, 1000L);
        profile.exchangeLocked.put(USD, 50L);
        SymbolPositionRecord isoPos = newFuturesPosition(BTC_SYMBOL, MarginMode.ISOLATED, USD);
        isoPos.openVolume = 1;
        isoPos.openInitMarginSum = 200; // ISO 虚拟锁定
        isoPos.openPriceSum = 2000;
        isoPos.direction = PositionDirection.LONG;
        profile.positions.put(BTC_SYMBOL, isoPos);

        long avail = profile.calculateCrossAvailable(USD, currencySpec(USD),
            symbolSpecLookup(BTC_SYMBOL, futuresSpec(BTC_SYMBOL)));
        // accounts(1000) − exchangeLocked(50) − ISO required(200) = 750
        assertEquals(750L, avail);
    }

    @Test
    void calculateCrossAvailable_isoSpecMissing_fallsBackToNoDeduction() {
        // spec 缺失时该 ISO 仓不扣（宁可 equity 略高估也不 NPE）
        profile.accounts.put(USD, 1000L);
        profile.exchangeLocked.put(USD, 0L);
        SymbolPositionRecord isoPos = newFuturesPosition(BTC_SYMBOL, MarginMode.ISOLATED, USD);
        isoPos.openVolume = 1;
        isoPos.openInitMarginSum = 500;
        isoPos.openPriceSum = 5000;
        isoPos.direction = PositionDirection.LONG;
        profile.positions.put(BTC_SYMBOL, isoPos);

        long avail = profile.calculateCrossAvailable(USD, currencySpec(USD), sym -> null); // spec 全 null
        assertEquals(1000L, avail); // ISO 未扣，无 NPE
    }

    @Test
    void calculateCrossAvailable_differentCurrenciesIsolated_onlyMatchingCurrencyStripped() {
        // ISO 仓属于不同 currency 时不扣（跨 currency 独立结算）
        int eur = 978;
        profile.accounts.put(USD, 1000L);
        profile.accounts.put(eur, 500L);

        SymbolPositionRecord isoUsd = newFuturesPosition(BTC_SYMBOL, MarginMode.ISOLATED, USD);
        isoUsd.openVolume = 1;
        isoUsd.openInitMarginSum = 100;
        isoUsd.openPriceSum = 1000;
        isoUsd.direction = PositionDirection.LONG;
        profile.positions.put(BTC_SYMBOL, isoUsd);

        SymbolPositionRecord isoEur = newFuturesPosition(LTC_SYMBOL, MarginMode.ISOLATED, eur);
        isoEur.openVolume = 1;
        isoEur.openInitMarginSum = 300; // EUR 仓不影响 USD 可支配
        isoEur.openPriceSum = 3000;
        isoEur.direction = PositionDirection.LONG;
        profile.positions.put(LTC_SYMBOL, isoEur);

        Map<Integer, CoreSymbolSpecification> specs = new HashMap<>();
        specs.put(BTC_SYMBOL, futuresSpec(BTC_SYMBOL));
        specs.put(LTC_SYMBOL, futuresSpec(LTC_SYMBOL));
        long usdAvail = profile.calculateCrossAvailable(USD, currencySpec(USD), specs::get);
        assertEquals(900L, usdAvail); // 1000 − 0 − 100 (USD ISO)，跟 EUR ISO 无关
    }

    // ============== helpers ==============

    private static SymbolPositionRecord newPosition(PositionDirection direction) {
        SymbolPositionRecord pos = new SymbolPositionRecord();
        pos.symbol = SYMBOL;
        pos.direction = direction;
        return pos;
    }

    private static SymbolPositionRecord newFuturesPosition(int symbol, MarginMode marginMode, int currency) {
        SymbolPositionRecord pos = new SymbolPositionRecord();
        pos.symbol = symbol;
        pos.currency = currency;
        pos.marginMode = marginMode;
        pos.updateLeverage(10);
        return pos;
    }

    private static CoreSymbolSpecification futuresSpec(int symbolId) {
        return CoreSymbolSpecification.builder()
            .symbolId(symbolId)
            .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
            .baseScaleK(1)
            .quoteScaleK(1)
            .takerFee(0)
            .makerFee(0)
            .feeScaleK(0)
            .initMargin(1)
            .initMarginScaleK(1)
            .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L))
            .maintenanceMarginScaleK(1000)
            .build();
    }

    private static CoreCurrencySpecification currencySpec(int id) {
        // digit=0 → currencyScaleK = 10^0 = 1
        return CoreCurrencySpecification.builder().id(id).name("C" + id).digit(0).build();
    }

    private static IntFunction<CoreSymbolSpecification> symbolSpecLookup() {
        return sym -> null;
    }

    private static IntFunction<CoreSymbolSpecification> symbolSpecLookup(int symbol, CoreSymbolSpecification spec) {
        return sym -> sym == symbol ? spec : null;
    }
}
