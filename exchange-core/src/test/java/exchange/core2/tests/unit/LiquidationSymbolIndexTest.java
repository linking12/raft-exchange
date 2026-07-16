package exchange.core2.tests.unit;

import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.UserProfileService;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.processors.loan.LoanService;
import org.eclipse.collections.api.set.primitive.MutableLongSet;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定重构核心——{@link LiquidationEngine#symbolToUsers}（symbol → 持有者 uid）索引的维护语义，
 * 以及 {@link LiquidationEngine#checkPositions} 靠该索引做 targeted 检测时的两个守卫（leader gate / 空 holders）。
 *
 * <p>索引由所有节点在开/平仓 apply 时确定性维护（不 gate），是 targeted 强平检测只查该 symbol 持有者的前提；
 * 索引错了会漏检破产仓或空扫，故在单元层守住增删边界。
 */
class LiquidationSymbolIndexTest {

    private static final ExchangeConfiguration TEST_CFG = ExchangeConfiguration.defaultBuilder()
            .performanceCfg(PerformanceConfiguration.baseBuilder().build())
            .build();

    private static final int SYMBOL = 100001;

    private LiquidationEngine le;

    @AfterEach
    void tearDown() {
        if (le != null) {
            le.stop();
        }
    }

    // ---------- symbolToUsers 索引维护 ----------

    /** 开仓 apply：uid 登记进 symbol → 持有者集合。 */
    @Test
    void onPositionOpened_registersUidUnderSymbol() {
        le = new LiquidationEngine(null, 1, TEST_CFG);
        UserProfile up = new UserProfile(7L, UserStatus.ACTIVE);
        SymbolPositionRecord pos = newPosition(7L, SYMBOL, PositionDirection.LONG);

        le.onPositionOpened(up, pos);

        MutableLongSet holders = le.getSymbolToUsers().get(SYMBOL);
        assertTrue(holders != null && holders.contains(7L), "开仓后 uid 应登记进该 symbol 的持有者集合");
    }

    /** 同一 symbol 多个持有者都登记；集合去重（同 uid 重复开仓不膨胀）。 */
    @Test
    void onPositionOpened_multipleHolders_allRegistered_andDeduped() {
        le = new LiquidationEngine(null, 1, TEST_CFG);
        UserProfile a = new UserProfile(1L, UserStatus.ACTIVE);
        UserProfile b = new UserProfile(2L, UserStatus.ACTIVE);

        le.onPositionOpened(a, newPosition(1L, SYMBOL, PositionDirection.LONG));
        le.onPositionOpened(b, newPosition(2L, SYMBOL, PositionDirection.SHORT));
        le.onPositionOpened(a, newPosition(1L, SYMBOL, PositionDirection.LONG)); // 重复登记

        MutableLongSet holders = le.getSymbolToUsers().get(SYMBOL);
        assertTrue(holders.contains(1L) && holders.contains(2L), "两个 uid 都应登记");
        assertTrue(holders.size() == 2, "重复登记不应膨胀集合");
    }

    /** 平仓 apply：最后一个持有者摘除后，整个 symbol key 被清掉（避免空集合残留）。 */
    @Test
    void onPositionClosed_lastHolder_dropsSymbolKey() {
        le = new LiquidationEngine(null, 1, TEST_CFG);
        UserProfile up = new UserProfile(7L, UserStatus.ACTIVE);
        SymbolPositionRecord pos = newPosition(7L, SYMBOL, PositionDirection.LONG);
        le.onPositionOpened(up, pos);

        le.onPositionClosed(up, pos);

        assertNull(le.getSymbolToUsers().get(SYMBOL), "最后一个持有者平仓后 symbol key 应被整体移除");
    }

    /** 平仓只摘自己：其它持有者仍在索引里。 */
    @Test
    void onPositionClosed_keepsSymbolKeyWhenOtherHoldersRemain() {
        le = new LiquidationEngine(null, 1, TEST_CFG);
        UserProfile a = new UserProfile(1L, UserStatus.ACTIVE);
        UserProfile b = new UserProfile(2L, UserStatus.ACTIVE);
        SymbolPositionRecord posA = newPosition(1L, SYMBOL, PositionDirection.LONG);
        le.onPositionOpened(a, posA);
        le.onPositionOpened(b, newPosition(2L, SYMBOL, PositionDirection.SHORT));

        le.onPositionClosed(a, posA);

        MutableLongSet holders = le.getSymbolToUsers().get(SYMBOL);
        assertTrue(holders != null && holders.contains(2L), "另一持有者应保留");
        assertFalse(holders.contains(1L), "平仓者应被摘除");
    }

    /**
     * HEDGE holdsOther 守卫：同 symbol 双向持仓时，平掉其中一个方向不能把 uid 从索引摘掉——
     * 另一方向仍有敞口需要被强平检测覆盖。
     */
    @Test
    void onPositionClosed_hedgeOtherDirectionRemains_retainsUid() {
        le = new LiquidationEngine(null, 1, TEST_CFG);
        UserProfile up = new UserProfile(7L, UserStatus.ACTIVE);
        SymbolPositionRecord longPos = newPosition(7L, SYMBOL, PositionDirection.LONG);
        SymbolPositionRecord shortPos = newPosition(7L, SYMBOL, PositionDirection.SHORT);
        // HEDGE：双向持仓用 ±symbol 区分挂在 positions 上（apply 时两条记录同时在 map 中）
        up.positions.put(SYMBOL, longPos);
        up.positions.put(-SYMBOL, shortPos);
        le.onPositionOpened(up, longPos);
        le.onPositionOpened(up, shortPos);

        le.onPositionClosed(up, longPos); // 平掉多头，空头仍在

        MutableLongSet holders = le.getSymbolToUsers().get(SYMBOL);
        assertTrue(holders != null && holders.contains(7L), "同 symbol 反向仓仍在，uid 不应被摘除");
    }

    /** 承接上例：另一方向也平掉后（map 中已无该 symbol 仓位），uid 才真正摘除、key 清空。 */
    @Test
    void onPositionClosed_hedgeBothDirectionsClosed_dropsUid() {
        le = new LiquidationEngine(null, 1, TEST_CFG);
        UserProfile up = new UserProfile(7L, UserStatus.ACTIVE);
        SymbolPositionRecord longPos = newPosition(7L, SYMBOL, PositionDirection.LONG);
        SymbolPositionRecord shortPos = newPosition(7L, SYMBOL, PositionDirection.SHORT);
        up.positions.put(SYMBOL, longPos);
        up.positions.put(-SYMBOL, shortPos);
        le.onPositionOpened(up, longPos);
        le.onPositionOpened(up, shortPos);

        // 平多头：apply 时该记录仍在 map（holdsOther 看到空头）→ 保留
        le.onPositionClosed(up, longPos);
        up.positions.removeKey(SYMBOL);
        // 平空头：此时 map 中已无同 symbol 其它仓 → 摘除
        le.onPositionClosed(up, shortPos);

        assertNull(le.getSymbolToUsers().get(SYMBOL), "双向都平掉后 symbol key 应被移除");
    }

    /** 平一个从未登记过的 symbol：索引无该 key，不应抛异常。 */
    @Test
    void onPositionClosed_unknownSymbol_isNoOp() {
        le = new LiquidationEngine(null, 1, TEST_CFG);
        UserProfile up = new UserProfile(7L, UserStatus.ACTIVE);
        SymbolPositionRecord pos = newPosition(7L, 999999, PositionDirection.LONG);

        assertDoesNotThrow(() -> le.onPositionClosed(up, pos), "摘除未登记 symbol 不应抛异常");
    }

    // ---------- checkPositions 守卫 ----------

    /**
     * leader gate：未 start（follower）时 checkPositions 直接 no-op——即便是全量整扫命令、
     * 且尚未注入 userProfileService，也不应因走进扫描体而 NPE。
     */
    @Test
    void checkPositions_notLeader_isNoOp_evenForFullScan() {
        le = new LiquidationEngine(null, 1, TEST_CFG); // 未 start → isRunning()==false
        OrderCommand fullScan = newCmd(OrderCommandType.LIQUIDATION_SCAN, -1);

        assertDoesNotThrow(() -> le.checkPositions(fullScan), "follower 应在读用户态前 no-op 返回");
    }

    /**
     * targeted 空 holders 守卫：leader 收到某 symbol 的价格触发，但该 symbol 无任何持有者
     * （索引里没有 key）→ holders==null 分支直接返回，不触碰 userProfileService。
     */
    @Test
    void checkPositions_targetedUnknownSymbol_isNoOp() {
        le = new LiquidationEngine(null, 1, TEST_CFG);
        // 与生产一致：checkPositions 前必已 updateProvider（futures + loan 子引擎都拿到 provider），
        // 否则末尾委托的 checkLoans 会解引用 null provider。空 provider 即可，未知 symbol 走 spec-null 守卫。
        le.updateProvider(new SymbolSpecificationProvider(), new CurrencySpecificationProvider(),
            new UserProfileService(), new IntObjectHashMap<>(), new LoanService());
        le.setCommandSubmitter((cmd, onApplied) -> {});
        le.start();
        OrderCommand targeted = newCmd(OrderCommandType.MOVE_ORDER, SYMBOL); // 该 symbol 无持有者

        assertDoesNotThrow(() -> le.checkPositions(targeted), "无持有者的 symbol 应走 null 守卫、不 NPE");
    }

    // ---------- helpers ----------

    private static SymbolPositionRecord newPosition(long uid, int symbol, PositionDirection direction) {
        SymbolPositionRecord pos = new SymbolPositionRecord();
        pos.uid = uid;
        pos.symbol = symbol;
        pos.direction = direction;
        pos.openVolume = 10;
        pos.marginMode = MarginMode.ISOLATED;
        return pos;
    }

    private static OrderCommand newCmd(OrderCommandType type, int symbol) {
        OrderCommand cmd = new OrderCommand(1);
        cmd.command = type;
        cmd.symbol = symbol;
        cmd.timestamp = 1_000L;
        return cmd;
    }
}
