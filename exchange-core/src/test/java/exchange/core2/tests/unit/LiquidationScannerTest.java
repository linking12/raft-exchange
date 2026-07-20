package exchange.core2.tests.unit;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.config.ExchangeConfiguration;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    private static final ExchangeConfiguration TEST_CFG = ExchangeConfiguration.defaultBuilder()
            .performanceCfg(PerformanceConfiguration.baseBuilder().build())
            .build();

    private LiquidationEngine getScanner() {
        LiquidationEngine scanner = new LiquidationEngine(null, 2, TEST_CFG);
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
        long result = position.calculateBankruptcyPrice(spec, p -> 0L);

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
        long result = position.calculateBankruptcyPrice(spec, p -> 0L);

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
        long result = position.calculateBankruptcyPrice(spec, p -> 0L);

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
        long result = position.calculateBankruptcyPrice(spec, p -> 0L);

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
        long result = position.calculateBankruptcyPrice(spec, p -> 0L);

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
        assertThrows(ArithmeticException.class, () -> position.calculateBankruptcyPrice(spec, p -> 0L));
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
        assertThrows(ArithmeticException.class, () -> position.calculateBankruptcyPrice(spec, p -> 0L));
    }

    /**
     * 测试负保证金情况（风险：负值处理，鲁棒性）
     * 输入：多头持仓10手，开仓总价10,000，总保证金-100，固定手续费2/手
     * 预期：破产价 = (10,000 - 1*(-100 - 2*10))/10 = (10,000 + 120)/10 = 1012
     * 覆盖：负保证金可能导致的计算异常或不合理结果
     */
    @Test
    void testNegativeMarginFixedFee() {
        SymbolPositionRecord position = createPosition(
                PositionDirection.LONG, 10, 10_000, -200, 100  // totalMargin = -200 + 100 = -100
        );
        CoreSymbolSpecification spec = createSpec(true, 2, 0);

        long result = position.calculateBankruptcyPrice(spec, p -> 0L);
        assertEquals(1012, result);
    }

    /**
     * 测试负开仓价格总和（风险：负值处理，期货价格通常正，但测试边界）
     * 输入：空头持仓5手，开仓总价-4,000，总保证金300，固定手续费3/手
     * 预期：计算应正常进行，但结果可能负（视业务是否允许负价）
     */
    @Test
    void testNegativeOpenPriceSum() {
        SymbolPositionRecord position = createPosition(
                PositionDirection.SHORT, 5, -4_000, 250, 50
        );
        CoreSymbolSpecification spec = createSpec(true, 3, 0);

        long result = position.calculateBankruptcyPrice(spec, p -> 0L);
        assertEquals(-743, result);  // 预期负值，视实现是否需添加正值检查
    }

    /**
     * 测试极大值输入（风险：long溢出）
     * 输入：多头持仓极大值，开仓总价极大，总保证金极大，固定手续费大
     * 预期：不抛OverflowException，计算正确或抛ArithmeticException如果溢出
     */
    @Test
    void testLargeValuesFixedFee() {
        long largeVolume = Long.MAX_VALUE / 100;  // 避免除零或溢出
        long largePriceSum = largeVolume * 1000;
        long largeMargin = largeVolume * 10;

        SymbolPositionRecord position = createPosition(
                PositionDirection.LONG, largeVolume, largePriceSum, largeMargin / 2, largeMargin / 2
        );
        CoreSymbolSpecification spec = createSpec(true, 100, 0);

        // 预期不抛异常，如果实现有溢出保护
        Assertions.assertDoesNotThrow(() -> position.calculateBankruptcyPrice(spec, p -> 0L));
    }

    /**
     * 测试向上取整逻辑（风险：ceilDivide精度）
     * 输入：比例手续费，导致非整数结果
     * 预期：向上取整
     */
    @Test
    void testCeilDivideInRatioFee() {
        SymbolPositionRecord position = createPosition(
                PositionDirection.LONG, 1, 1000, 100, 0
        );
        CoreSymbolSpecification spec = createSpec(false, 1, 100);  // 小规模测试向上取整

        long result = position.calculateBankruptcyPrice(spec, p -> 0L);
        // 手动计算：maxLoss=100, numerator=(1000-100)*100=90000, denominator=1*(100-1)=99, 90000/99≈909.09 -> ceil=910
        assertEquals(910, result);
    }

    /**
     * 测试并发调用（风险：线程安全）
     * 输入：多线程并发计算同一实例
     * 预期：无竞争异常，结果一致
     */
    @Test
    void testConcurrentCalculations() throws InterruptedException {
        LiquidationEngine scanner = getScanner();
        SymbolPositionRecord position = createPosition(
                PositionDirection.LONG, 10, 10_000, 400, 100
        );
        CoreSymbolSpecification spec = createSpec(true, 2, 0);

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        long[] results = new long[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    results[idx] = position.calculateBankruptcyPrice(spec, p -> 0L);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // 验证所有结果一致
        for (long res : results) {
            assertEquals(952, res);
        }
    }

    /**
     * 测试零手续费（风险：边界，简化计算）
     * 输入：takerFee=0
     * 预期：正常计算
     */
    @Test
    void testZeroFee() {
        SymbolPositionRecord position = createPosition(
                PositionDirection.LONG, 10, 10_000, 400, 100
        );
        CoreSymbolSpecification spec = createSpec(true, 0, 0);

        long result = position.calculateBankruptcyPrice(spec, p -> 0L);
        assertEquals(950, result);  // 无费，maxLoss=500, (10000 - 500)/10=950
    }

    /**
     * 测试极大费率（风险：溢出或负值）
     * 输入：takerFee极大
     * 预期：不抛异常
     */
    @Test
    void testLargeFeeRatio() {
        SymbolPositionRecord position = createPosition(
                PositionDirection.LONG, 1, 1000, 100, 0
        );
        CoreSymbolSpecification spec = createSpec(false, Long.MAX_VALUE / 2, Long.MAX_VALUE);

        Assertions.assertDoesNotThrow(() -> position.calculateBankruptcyPrice(spec, p -> 0L));
    }

    // ========== CROSS marginBase（非零 alloc）破产价 ==========
    // 现有用例都传 p -> 0L（零抵扣，逐仓视角）。CROSS 强平走 forceCrossLiquidation 里的 alloc::get，
    // marginBase 来自 crossMarginBaseAllocation 而非 openInitMarginSum+extraMargin。下面用例把逐仓字段
    // 填垃圾值，证明 CROSS 路径确实忽略它们、只用 alloc fn 提供的 marginBase。

    /** CROSS 逐仓字段是垃圾值，破产价必须由 alloc 提供的 marginBase 决定（固定费、多头）。 */
    @Test
    void testCrossFixedFeeLong_usesAllocatedMarginBase() {
        SymbolPositionRecord pos = createCrossPosition(PositionDirection.LONG, 10, 10_000);
        CoreSymbolSpecification spec = createSpec(true, 2, 0);
        // alloc marginBase=480：maxLoss=480-2*10=460, numer=10000-460=9540, BP=ceil(9540/10)=954
        // 若错用逐仓字段(999_999) 结果会是天文数字，绝不会等于 954
        long result = pos.calculateBankruptcyPrice(spec, p -> 480L);
        assertEquals(954, result);
    }

    /** CROSS 固定费、空头：sign=-1。 */
    @Test
    void testCrossFixedFeeShort_usesAllocatedMarginBase() {
        SymbolPositionRecord pos = createCrossPosition(PositionDirection.SHORT, 5, 4_000);
        CoreSymbolSpecification spec = createSpec(true, 3, 0);
        // marginBase=300：maxLoss=300-3*5=285, numer=4000-(-1)*285=4285, BP=ceil(4285/5)=857
        long result = pos.calculateBankruptcyPrice(spec, p -> 300L);
        assertEquals(857, result);
    }

    /** CROSS 比例费、多头：走 ceilMulDiv 分支，marginBase 仍取自 alloc。 */
    @Test
    void testCrossRatioFeeLong_usesAllocatedMarginBase() {
        SymbolPositionRecord pos = createCrossPosition(PositionDirection.LONG, 8, 9_600);
        CoreSymbolSpecification spec = createSpec(false, 1_000, 1_000_000);
        // marginBase=600：numer=9600-600=9000, denom=8*(1e6-1000)=7_992_000, BP=ceil(9000*1e6/7_992_000)=1127
        long result = pos.calculateBankruptcyPrice(spec, p -> 600L);
        assertEquals(1127, result);
    }

    /** alloc 越大（可用抵押越多）→ 多头破产价越低（更抗跌）：证明 alloc 的 marginBase 真正驱动结果。 */
    @Test
    void testCrossBankruptcyPrice_variesWithAllocation() {
        SymbolPositionRecord pos = createCrossPosition(PositionDirection.LONG, 10, 10_000);
        CoreSymbolSpecification spec = createSpec(true, 2, 0);
        long lowMargin = pos.calculateBankruptcyPrice(spec, p -> 300L);  // maxLoss=280, numer=9720, BP=972
        long highMargin = pos.calculateBankruptcyPrice(spec, p -> 800L); // maxLoss=780, numer=9220, BP=922
        assertEquals(972, lowMargin);
        assertEquals(922, highMargin);
        Assertions.assertTrue(highMargin < lowMargin, "marginBase 越大，多头破产价越低");
    }

    /** totalFee = takerFee + liquidationFee：破产价须把清算费也算进 maxLoss（固定费、多头、CROSS）。 */
    @Test
    void testCrossFixedFee_addsLiquidationFeeToTotalFee() {
        SymbolPositionRecord pos = createCrossPosition(PositionDirection.LONG, 10, 10_000);
        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_ID)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .initMargin(10)
                .initMarginScaleK(100)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 8L))
                .maintenanceMarginScaleK(100)
                .takerFee(2)
                .makerFee(2)
                .liquidationFee(3)
                .feeScaleK(0)
                .build();
        // totalFee=2+3=5, maxLoss=500-5*10=450, numer=10000-450=9550, BP=ceil(9550/10)=955
        // 若漏掉 liquidationFee(totalFee=2) 则得 952，故 955 锁住"清算费计入"
        long result = pos.calculateBankruptcyPrice(spec, p -> 500L);
        assertEquals(955, result);
    }

    /** CROSS 持仓：逐仓保证金字段填垃圾值（openInitMarginSum/extraMargin），确保被 CROSS 路径忽略。 */
    private SymbolPositionRecord createCrossPosition(PositionDirection direction, long openVolume, long openPriceSum) {
        SymbolPositionRecord pos = new SymbolPositionRecord();
        pos.direction = direction;
        pos.openVolume = openVolume;
        pos.openPriceSum = openPriceSum;
        pos.marginMode = MarginMode.CROSS;
        pos.openInitMarginSum = 999_999L; // 垃圾值：CROSS 必须忽略
        pos.extraMargin = 999_999L;       // 垃圾值：CROSS 必须忽略
        return pos;
    }
}