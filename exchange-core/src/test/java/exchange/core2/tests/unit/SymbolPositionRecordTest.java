package exchange.core2.tests.unit;

import exchange.core2.core.common.*;
import exchange.core2.core.common.api.*;
import exchange.core2.core.processors.RiskEngine;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class SymbolPositionRecordTest {

    private static final int SYMBOL_ID = 1001;
    private CoreSymbolSpecification spec;
    private RiskEngine.LastPriceCacheRecord priceRecord;

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
        priceRecord = new RiskEngine.LastPriceCacheRecord();
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
        long maintenanceMargin = (long) (notional * 0.05); // 5%维持保证金率

        long result = position.estimateLiquidationPrice(
                spec, priceRecord,
                100000L, // 账户余额
                -5000L,  // 总未实现盈亏
                maintenanceMargin + 40000L // 总维持保证金
        );

        assertEquals(46739, result, "normal case");
    }

    @Test
    void testNormalCase2() {
        SymbolPositionRecord position = createPosition(MarginMode.CROSS, PositionDirection.LONG, 10);
        // 开仓总成本 BTC 50000@10
        position.openPriceSum = 500000L;

        priceRecord.markPrice = 50000L; // $50,000
        long notional = 10 * 50000L;
        long maintenanceMargin = (long) (notional * 0.05); // 5%维持保证金率

        long result = position.estimateLiquidationPrice(
                spec, priceRecord,
                100000L, // 账户余额
                5000L,  // 总未实现盈亏
                maintenanceMargin + 40000L // 总维持保证金
        );

        assertEquals(45652, result, "normal case");
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
        long maintenanceMargin = (long) (notional * 0.08); // 8%维持保证金率

        long pnl = position.estimateUnrealizedProfit(priceRecord);

        long result = position.estimateLiquidationPrice(
                spec, priceRecord,
                60000L, // 账户余额
                pnl + 0L,  // 总未实现盈亏
                maintenanceMargin + 40000L // 总维持保证金
        );

        assertEquals(-1, result, "normal case");
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
}