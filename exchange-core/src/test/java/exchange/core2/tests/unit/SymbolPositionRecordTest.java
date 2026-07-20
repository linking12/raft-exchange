package exchange.core2.tests.unit;

import exchange.core2.core.common.*;
import exchange.core2.core.processors.LastPriceCacheRecord;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class SymbolPositionRecordTest {

    private static final int SYMBOL_ID = 1001;
    private CoreSymbolSpecification spec;
    private LastPriceCacheRecord priceRecord;

    @BeforeEach
    void setUp() {
        // 创建合约规格：维持保证金率1%
        spec = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_ID)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .initMargin(10)
                .initMarginScaleK(100)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 8L))
                .maintenanceMarginScaleK(100)
                .build();

        // 初始标记价格
        priceRecord = new LastPriceCacheRecord();
        priceRecord.markPrice = 50000; // $50,000
    }

    // =============== 测试用例生成器 ===============
    static Stream<Arguments> isolatedMarginCases() {
        return Stream.of(
                // 多头案例
                arguments(
                        PositionDirection.LONG, // 方向
                        10L,                    // 持仓量
                        500000L,                // 开仓总成本 ($50,000 * 10)
                        100000L,                // 初始保证金 (合约价值10%)
                        20000L,                 // 追加保证金
                        50000L,                 // 维持保证金 (名义价值50,000*10*1% = 5,000)
                        49000L                  // 预期强平价格
                ),
                // 空头案例
                arguments(
                        PositionDirection.SHORT,
                        5L,
                        300000L,                // 开仓总成本 ($60,000 * 5)
                        60000L,                 // 初始保证金 (合约价值10%)
                        10000L,
                        25000L,                 // 维持保证金 (50,000*5*1% = 2,500)
                        61000L                  // 预期强平价格
                )
        );
    }

    static Stream<Arguments> crossMarginCases() {
        return Stream.of(
                // 多头案例
                arguments(
                        PositionDirection.LONG,
                        5L,                     // 持仓量
                        250000L,                // 开仓总成本 ($50,000 * 5)
                        50000L,                 // 当前名义价值 (50,000*5)
                        1000L,                  // 账户余额
                        5000L,                  // 总未实现盈亏
                        10000L,                 // 总维持保证金
                        6000L,                  // 当前仓位维持保证金 (50,000*5*1% = 2,500)
                        43636L                  // 预期强平价格 (近似值)
                ),
                // 空头案例
                arguments(
                        PositionDirection.SHORT,
                        8L,
                        400000L,                // 开仓总成本 ($50,000 * 8)
                        400000L,                // 当前名义价值 (50,000*8)
                        5000L,
                        15000L,
                        30000L,
                        32000L,                 // 当前仓位维持保证金 (50,000*8*1% = 4,000)
                        56818L                  // 预期强平价格 (近似值)
                )
        );
    }

    // =============== 测试用例 ===============

    // 测试1: 无持仓时返回0
    @Test
    void shouldReturnZeroWhenNoPosition() {
        SymbolPositionRecord position = createPosition(MarginMode.ISOLATED, PositionDirection.EMPTY, 0);
        long result = position.estimateLiquidationPrice(spec, priceRecord, 0, 0, 0);
        assertEquals(0, result);
    }

    @Test
    void testNormalCase() {
        SymbolPositionRecord position = createPosition(MarginMode.CROSS, PositionDirection.LONG, 10);
        // 开仓总成本 BTC 50000@10
        position.openPriceSum = 500000L;

        priceRecord.markPrice = 50000L; // $50,000
        long notional = 10 * 50000L;
        long maintenanceMargin = spec.calculateMaintenanceMargin(notional); // 5%维持保证金率

        long result = position.estimateLiquidationPrice(
                spec, priceRecord,
                100000L, // 账户余额
                -5000L,  // 总未实现盈亏
                maintenanceMargin + 40000L // 总维持保证金
        );

        assertEquals(48369, result, "normal case");
    }

    @Test
    void testShortCase() {
        SymbolPositionRecord position = createPosition(MarginMode.CROSS, PositionDirection.SHORT, 10);
        // 开仓总成本 BTC 50000@10
        position.openPriceSum = 500000L;
        position.openInitMarginSum = 50000L;
        priceRecord.markPrice = 50000L;
        long notional = 10 * 50000L;
        long maintenanceMargin = spec.calculateMaintenanceMargin(notional);

        long result = position.estimateLiquidationPrice(
                spec, priceRecord,
                100000L, // 账户余额
                0,  // 总未实现盈亏
                maintenanceMargin // 总维持保证金
        );

        assertEquals(55555, result, "normal case");
    }

    @Test
    void testShortCase2() {
        SymbolPositionRecord position = createPosition(MarginMode.ISOLATED, PositionDirection.SHORT, 10);
        // 开仓总成本 BTC 50000@10
        position.openPriceSum = 500000L;
        position.openInitMarginSum = 50000L;
        priceRecord.markPrice = 50000L;
        long notional = 10 * 50000L;
        long maintenanceMargin = spec.calculateMaintenanceMargin(notional);

        long result = position.estimateLiquidationPrice(
                spec, priceRecord,
                100000L, // 账户余额
                0L,  // 总未实现盈亏
                maintenanceMargin // 总维持保证金
        );

        // 迭代法精确解（旧近似给 51000，用 MM(MP) 常量；精确解在同 bracket 内收敛到 50926）
        assertEquals(50926, result, "normal case");
    }

    @Test
    void testNormalCase2() {
        SymbolPositionRecord position = createPosition(MarginMode.CROSS, PositionDirection.LONG, 10);
        // 开仓总成本 BTC 50000@10
        position.openPriceSum = 500000L;

        priceRecord.markPrice = 50000L; // $50,000
        long notional = 10 * 50000L;
        long maintenanceMargin = spec.calculateMaintenanceMargin(notional); // 5%维持保证金率

        long result = position.estimateLiquidationPrice(
                spec, priceRecord,
                100000L, // 账户余额
                5000L,  // 总未实现盈亏
                maintenanceMargin + 40000L // 总维持保证金
        );

        assertEquals(47282, result, "normal case");
    }

    @Test
    void testNormalCase3() {
        SymbolPositionRecord position = createPosition(MarginMode.CROSS, PositionDirection.LONG, 10);
        // 开仓总成本 BTC 50000@10
        position.openPriceSum = 500000L;

        priceRecord.bidPrice = 50000L;
        priceRecord.askPrice = 50000L;
        priceRecord.markPrice = 50000L; // $50,000
        long notional = 10 * 50000L;
        long pnl = position.estimateUnrealizedProfit(priceRecord);
        long maintenanceMargin = spec.calculateMaintenanceMargin(notional); // 5%维持保证金率

        long result = position.estimateLiquidationPrice(
                spec, priceRecord,
                60000L, // 账户余额
                pnl + 0L,  // 总未实现盈亏
                maintenanceMargin + 40000L // 总维持保证金
        );

        assertEquals(-1, result, "normal case");
    }

    @Test
    void testIsolatedNormalCase() {
        SymbolPositionRecord position = createPosition(MarginMode.ISOLATED, PositionDirection.LONG, 10);
        // 开仓总成本 BTC 50000@10
        position.openPriceSum = 500000L;
        position.openInitMarginSum = 50000L;
        priceRecord.markPrice = 50000L; // $50,000
        long notional = 10 * 50000L;
        long maintenanceMargin = spec.calculateMaintenanceMargin(notional); // 5%维持保证金率

        long result = position.estimateLiquidationPrice(
                spec, priceRecord,
                60000L, // 账户余额
                0L,  // 总未实现盈亏
                maintenanceMargin + 0L // 总维持保证金
        );

        // 迭代法精确解（旧近似给 49000，用 MM(MP) 常量；精确解在同 bracket 内收敛到 48913）
        assertEquals(48913, result, "normal case");
    }

    // =============== pendingHoldBudget (E2: futures BUDGET 单) ===============

    @Test
    void pendingHoldBudget_emptyState_avgPriceEqualsBudgetOverSize() {
        // 空 pending 上发 BUDGET BID：size=10, budget=1000 → avgPrice = ceil(1000/10) = 100
        // 不变式：pendingSize × pendingAvgPrice ≈ budget
        SymbolPositionRecord position = createPosition(MarginMode.ISOLATED, PositionDirection.EMPTY, 0);

        position.pendingHoldBudget(OrderAction.BID, 10L, 1000L);

        assertEquals(10L, position.pendingBuySize);
        assertEquals(100L, position.pendingBuyAvgPrice);
        // 不变式：size × avg = 10 × 100 = 1000 = 原 budget
        assertEquals(1000L, position.pendingBuySize * position.pendingBuyAvgPrice);
    }

    @Test
    void pendingHoldBudget_overExistingLimit_maintainsTotalNotional() {
        // 已有 limit BID：100 unit @ 10 → notional = 1000
        // 再加 BUDGET BID：size=50, budget=600 → 新 notional 应≈ 1600
        SymbolPositionRecord position = createPosition(MarginMode.ISOLATED, PositionDirection.EMPTY, 0);
        position.pendingHold(OrderAction.BID, 100L, 10L);
        assertEquals(1000L, position.pendingBuySize * position.pendingBuyAvgPrice, "limit notional baseline");

        position.pendingHoldBudget(OrderAction.BID, 50L, 600L);

        assertEquals(150L, position.pendingBuySize);
        // 不变式：avg = ceil((1000+600)/150) = ceil(10.666...) = 11
        assertEquals(11L, position.pendingBuyAvgPrice);
        // size × avg = 150 × 11 = 1650，保守覆盖实际 1600 notional（向上取整偏保守 +50）
        assertEquals(1650L, position.pendingBuySize * position.pendingBuyAvgPrice);
    }

    @Test
    void pendingHoldBudget_askSide_independentFromBid() {
        // ASK BUDGET：size=20, budget=400 → avgPrice = ceil(400/20) = 20
        SymbolPositionRecord position = createPosition(MarginMode.ISOLATED, PositionDirection.EMPTY, 0);

        position.pendingHoldBudget(OrderAction.ASK, 20L, 400L);

        assertEquals(20L, position.pendingSellSize);
        assertEquals(20L, position.pendingSellAvgPrice);
        // BID 状态完全不受影响
        assertEquals(0L, position.pendingBuySize);
        assertEquals(0L, position.pendingBuyAvgPrice);
    }

    @Test
    void pendingHoldBudget_zeroSize_noop() {
        // size=0 是 noop（防御）
        SymbolPositionRecord position = createPosition(MarginMode.ISOLATED, PositionDirection.EMPTY, 0);

        position.pendingHoldBudget(OrderAction.BID, 0L, 100L);

        assertEquals(0L, position.pendingBuySize);
        assertEquals(0L, position.pendingBuyAvgPrice);
    }

    @Test
    void pendingHoldBudget_thenRelease_clearsState() {
        // BUDGET hold 后立即 release：pending 状态归零，下游一致性保持
        SymbolPositionRecord position = createPosition(MarginMode.ISOLATED, PositionDirection.EMPTY, 0);
        position.pendingHoldBudget(OrderAction.BID, 10L, 1000L);

        long released = position.pendingRelease(OrderAction.BID, 10L);

        assertEquals(10L, released);
        assertEquals(0L, position.pendingBuySize);
        assertEquals(0L, position.pendingBuyAvgPrice);
    }

    // =============== BP fee 集成回归 =================
    // 核心：锁 calculateBankruptcyPrice 里 takerFee+liquidationFee 合并 + SHORT dynamic 分母 sign 修正
    // 未来若把 totalFee 拆回单项、或把 SHORT 分母 sign 去掉，这几条测试会 catch 到

    /**
     * Fixed fee + liquidationFee>0 的 LONG BP：
     * maxLoss = totalMargin − (takerFee+liquidationFee)×Q = 100 − (1+5)×10 = 40
     * BP = (openPriceSum − sign×maxLoss)/Q = (1000 − 40)/10 = 96
     * 若未来漏加 liquidationFee → maxLoss=90 → BP=91（差 5）
     */
    @Test
    void bp_isolated_long_fixedFee_withLiquidationFee() {
        CoreSymbolSpecification s = fixedFeeSpec(1, 5); // takerFee=1, liquidationFee=5
        SymbolPositionRecord pos = createPosition(MarginMode.ISOLATED, PositionDirection.LONG, 10);
        pos.openPriceSum = 1000;
        pos.openInitMarginSum = 100;
        assertEquals(96, pos.calculateBankruptcyPrice(s, p -> 0L));
    }

    /**
     * Fixed fee + liquidationFee>0 的 SHORT BP：
     * BP = (openPriceSum − (−1)×maxLoss)/Q = (1000 + 40)/10 = 104
     */
    @Test
    void bp_isolated_short_fixedFee_withLiquidationFee() {
        CoreSymbolSpecification s = fixedFeeSpec(1, 5);
        SymbolPositionRecord pos = createPosition(MarginMode.ISOLATED, PositionDirection.SHORT, 10);
        pos.openPriceSum = 1000;
        pos.openInitMarginSum = 100;
        assertEquals(104, pos.calculateBankruptcyPrice(s, p -> 0L));
    }

    /**
     * Dynamic fee + liquidationFee>0 的 LONG BP：
     * BP = feeScaleK×(openPriceSum − marginBase)/(Q×(feeScaleK − totalFee))
     *    = 1000×(1000−100)/(10×(1000−50)) = 900000/9500 → ceil 95
     */
    @Test
    void bp_isolated_long_dynamicFee_withLiquidationFee() {
        CoreSymbolSpecification s = dynamicFeeSpec(20, 30, 1000); // takerFee=20 (2%), liqFee=30 (3%)
        SymbolPositionRecord pos = createPosition(MarginMode.ISOLATED, PositionDirection.LONG, 10);
        pos.openPriceSum = 1000;
        pos.openInitMarginSum = 100;
        assertEquals(95, pos.calculateBankruptcyPrice(s, p -> 0L));
    }

    /**
     * Dynamic fee + liquidationFee>0 的 SHORT BP —— **本 session SHORT 分母 sign 修复的锁定测试**：
     * 正确 denom = feeScaleK − sign×totalFee = 1000 − (−1)×50 = 1050
     * BP = 1000×(1000 − (−1)×100)/(10×1050) = 1000×1100/10500 = 1100000/10500 → ceil 105
     * 若未来 sign 去掉（denom=950）→ BP = 1100000/9500 → ceil 116（差 11，容易 catch）
     */
    @Test
    void bp_isolated_short_dynamicFee_withLiquidationFee() {
        CoreSymbolSpecification s = dynamicFeeSpec(20, 30, 1000);
        SymbolPositionRecord pos = createPosition(MarginMode.ISOLATED, PositionDirection.SHORT, 10);
        pos.openPriceSum = 1000;
        pos.openInitMarginSum = 100;
        assertEquals(105, pos.calculateBankruptcyPrice(s, p -> 0L));
    }

    // =============== 辅助方法 ===============
    private SymbolPositionRecord createPosition(MarginMode marginMode, PositionDirection direction, long volume) {
        SymbolPositionRecord position = new SymbolPositionRecord();
        position.symbol = SYMBOL_ID;
        position.marginMode = marginMode;
        position.direction = direction;
        position.openVolume = volume;
        return position;
    }

    private CoreSymbolSpecification fixedFeeSpec(long takerFee, long liquidationFee) {
        return CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_ID)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .takerFee(takerFee)
                .liquidationFee(liquidationFee)
                .feeScaleK(0) // fixed
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 8L))
                .maintenanceMarginScaleK(100)
                .initMargin(10)
                .initMarginScaleK(100)
                .build();
    }

    private CoreSymbolSpecification dynamicFeeSpec(long takerFee, long liquidationFee, long feeScaleK) {
        return CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_ID)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .takerFee(takerFee)
                .liquidationFee(liquidationFee)
                .feeScaleK(feeScaleK) // dynamic
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 8L))
                .maintenanceMarginScaleK(100)
                .initMargin(10)
                .initMarginScaleK(100)
                .build();
    }
}