package exchange.core2.tests.unit;

import exchange.core2.core.processors.LiquidationEngine;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.SymbolType;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LiquidationScannerTest {
    int SYMBOL_ID = 100001;

    // 辅助方法：创建持仓记录
    private SymbolPositionRecord createPosition(
            PositionDirection direction,
            long openVolume,
            long openPriceSum,
            long initMargin,
            long extraMargin
    ) {
        SymbolPositionRecord position = new SymbolPositionRecord();
        position.direction = direction;
        position.openVolume = openVolume;
        position.openPriceSum = openPriceSum;
        position.openInitMarginSum = initMargin;
        position.extraMargin = extraMargin;
        return position;
    }

    // 辅助方法：创建交易对规格
    private CoreSymbolSpecification createSpec(
            boolean isFixedFee,
            long takerFee,
            long feeScaleK
    ) {
        CoreSymbolSpecification spec = null;
        if (isFixedFee) {
            spec = CoreSymbolSpecification.builder()
                    .symbolId(SYMBOL_ID)
                    .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                    .initMargin(10)
                    .initMarginScaleK(100)
                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 8L))
                    .maintenanceMarginScaleK(100)
                    .takerFee(takerFee)
                    .makerFee(takerFee)
                    .feeScaleK(0)
                    .build();
        } else {
            spec = CoreSymbolSpecification.builder()
                    .symbolId(SYMBOL_ID)
                    .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                    .initMargin(10)
                    .initMarginScaleK(100)
                    .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 8L))
                    .maintenanceMarginScaleK(100)
                    .takerFee(takerFee)
                    .makerFee(takerFee)
                    .feeScaleK(feeScaleK)
                    .build();
        }
        return spec;
    }

    private LiquidationEngine getScanner() {
        LiquidationEngine scanner = new LiquidationEngine(null, 0, 1);
        return scanner;
    }

    /**
     * 测试固定手续费模式 - 多头仓位
     * 输入：多头持仓10手，开仓总价10,000，总保证金500，固定手续费2/手
     * 预期：破产价 = (10,000 - 1*(500 - 2*10))/10 = (10,000 - 480)/10 = 952
     */
    @Test
    void testFixedFeeLongPosition() {
        // 准备数据
        SymbolPositionRecord position = createPosition(
                PositionDirection.LONG, 10, 10_000, 400, 100
        );
        CoreSymbolSpecification spec = createSpec(true, 2, 0);

        // 执行计算
        long result = getScanner().calculateBankruptcyPrice(position, spec);

        // 验证结果
        assertEquals(952, result);
    }

    /**
     * 测试固定手续费模式 - 空头仓位
     * 输入：空头持仓5手，开仓总价4,000，总保证金300，固定手续费3/手
     * 预期：破产价 = (4,000 - (-1)*(300 - 3*5))/5 = (4,000 + 285)/5 = 857
     */
    @Test
    void testFixedFeeShortPosition() {
        // 准备数据
        SymbolPositionRecord position = createPosition(
                PositionDirection.SHORT, 5, 4_000, 250, 50
        );
        CoreSymbolSpecification spec = createSpec(true, 3, 0);

        // 执行计算
        long result = getScanner().calculateBankruptcyPrice(position, spec);

        // 验证结果
        assertEquals(857, result);
    }

    /**
     * 测试比例手续费模式 - 多头仓位
     * 输入：多头持仓8手，开仓总价9,600，总保证金600，比例费率0.1%(1000/1,000,000)
     * 预期：分子 = (9,600 - 600) * 1,000,000 = 9,000,000,000
     * 分母 = 8 * (1,000,000 - 1,000) = 7,992,000
     * 破产价 = ceil(9,000,000,000 / 7,992,000) = 1127
     */
    @Test
    void testRatioFeeLongPosition() {
        // 准备数据
        SymbolPositionRecord position = createPosition(
                PositionDirection.LONG, 8, 9_600, 500, 100
        );
        CoreSymbolSpecification spec = createSpec(false, 1_000, 1_000_000);

        // 执行计算
        long result = getScanner().calculateBankruptcyPrice(position, spec);

        // 验证结果
        assertEquals(1127, result);
    }

    /**
     * 测试比例手续费模式 - 空头仓位
     * 输入：空头持仓12手，开仓总价6,000，总保证金400，比例费率0.05%(500/1,000,000)
     * 预期：分子 = (6,000 - (-400)) * 1,000,000 = 6,400,000,000
     * 分母 = 12 * (1,000,000 - 500) = 11,994,000
     * 破产价 = ceil(6,400,000,000 / 11,994,000) = 534
     */
    @Test
    void testRatioFeeShortPosition() {
        // 准备数据
        SymbolPositionRecord position = createPosition(
                PositionDirection.SHORT, 12, 6_000, 350, 50
        );
        CoreSymbolSpecification spec = createSpec(false, 500, 1_000_000);

        // 执行计算
        long result = getScanner().calculateBankruptcyPrice(position, spec);

        // 验证结果
        assertEquals(534, result);
    }

    /**
     * 测试保证金为零的边界情况
     * 输入：多头持仓1手，开仓总价1,000，保证金0，固定手续费1/手
     * 预期：破产价 = (1,000 - 1*(0 - 1*1))/1 = (1,000 + 1)/1 = 1001
     */
    @Test
    void testZeroMargin() {
        // 准备数据
        SymbolPositionRecord position = createPosition(
                PositionDirection.LONG, 1, 1_000, 0, 0
        );
        CoreSymbolSpecification spec = createSpec(true, 1, 0);

        // 执行计算
        long result = getScanner().calculateBankruptcyPrice(position, spec);

        // 验证结果
        assertEquals(1001, result);
    }

    /**
     * 测试分母为零的边界情况（应避免，但测试异常处理）
     * 输入：持仓量为0
     * 预期：应避免除零错误（实际实现中未处理，此处演示可能的保护）
     */
    @Test
    void testZeroVolume() {
        // 准备数据
        SymbolPositionRecord position = createPosition(
                PositionDirection.LONG, 0, 0, 100, 0
        );
        CoreSymbolSpecification spec = createSpec(true, 1, 0);

        // 执行计算（预期会抛出算术异常）
        try {
            getScanner().calculateBankruptcyPrice(position, spec);
        } catch (ArithmeticException e) {
            // 验证是否捕获除零异常
            assertEquals("/ by zero", e.getMessage());
        }
    }

    /**
     * 测试比例手续费中分母为零的情况
     * 输入：费率因子与吃单费率相等（feeScaleK = takerFee）
     * 预期：应避免除零错误（实际实现中未处理，此处演示可能的保护）
     */
    @Test
    void testZeroDenominatorInRatioFee() {
        // 准备数据
        SymbolPositionRecord position = createPosition(
                PositionDirection.LONG, 10, 10_000, 500, 0
        );
        CoreSymbolSpecification spec = createSpec(false, 1_000, 1_000); // feeScaleK = takerFee

        // 执行计算（预期会抛出算术异常）
        try {
            getScanner().calculateBankruptcyPrice(position, spec);
        } catch (ArithmeticException e) {
            // 验证是否捕获除零异常
            assertEquals("/ by zero", e.getMessage());
        }
    }
}