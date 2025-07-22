package exchange.core2.tests.unit;

import exchange.core2.core.common.*;
import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.utils.CoreArithmeticUtils;
import org.eclipse.collections.api.map.sorted.MutableSortedMap;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.jupiter.api.Test;

import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SizeToLiquidateTest {

    @Test
    void testCalculateSizeToLiquidate_LongPosition() {
        // 准备持仓记录（多头）
        SymbolPositionRecord position = new SymbolPositionRecord();
        position.direction = PositionDirection.LONG;
        position.openVolume = 10;
        position.openPriceSum = 1000; // 平均开仓价100
        position.openInitMarginSum = 100;

        // 准备交易对规格（维持保证金率10%）
        MutableSortedMap<Long, Long> marginMap = TreeSortedMap.newMap(Comparator.naturalOrder());
        marginMap.put(0L, 100L); // 任何名义价值都按10%计算（100/1000=10%）

        CoreSymbolSpecification spec = CoreSymbolSpecification.builder().symbolId(1).type(SymbolType.FUTURES_CONTRACT_PERPETUAL).maintenanceMargin(marginMap).maintenanceMarginScaleK(1000) // 缩放因子1000
                .build();

        // 准备最新价格（标记价格90）
        RiskEngine.LastPriceCacheRecord priceRecord = new RiskEngine.LastPriceCacheRecord();
        priceRecord.markPrice = 90;

        // 计算强平数量
        long sizeToLiquidate = CoreArithmeticUtils.calculateSizeToLiquidate(position, spec, priceRecord);

        // 验证：应强平全部持仓（10）
        assertEquals(10, sizeToLiquidate);
    }

    @Test
    void testCalculateSizeToLiquidate_ShortPosition() {
        // 准备持仓记录（空头）
        SymbolPositionRecord position = new SymbolPositionRecord();
        position.direction = PositionDirection.SHORT;
        position.openVolume = 5;
        position.openPriceSum = 500; // 平均开仓价100
        position.openInitMarginSum = 50;

        // 准备交易对规格（维持保证金率10%）
        MutableSortedMap<Long, Long> marginMap = TreeSortedMap.newMap(Comparator.naturalOrder());
        marginMap.put(0L, 100L); // 任何名义价值都按10%计算

        CoreSymbolSpecification spec = CoreSymbolSpecification.builder().symbolId(1).type(SymbolType.FUTURES_CONTRACT_PERPETUAL).maintenanceMargin(marginMap).maintenanceMarginScaleK(1000) // 缩放因子1000
                .build();

        // 准备最新价格（标记价格110）
        RiskEngine.LastPriceCacheRecord priceRecord = new RiskEngine.LastPriceCacheRecord();
        priceRecord.markPrice = 110;

        // 计算强平数量
        long sizeToLiquidate = CoreArithmeticUtils.calculateSizeToLiquidate(position, spec, priceRecord);

        // 验证：应强平全部持仓（5）
        assertEquals(5, sizeToLiquidate);
    }

    //    @Test
    void testCalculateSizeToLiquidate_ZeroDenominator() {
        // 准备持仓记录（分母为零的情况）
        SymbolPositionRecord position = new SymbolPositionRecord();
        position.direction = PositionDirection.LONG;
        position.openVolume = 10;
        position.openPriceSum = 1000;
        position.openInitMarginSum = 100;

        // 设置规格使分母计算为零
        MutableSortedMap<Long, Long> marginMap = TreeSortedMap.newMap(Comparator.naturalOrder());
        marginMap.put(0L, 100L);

        CoreSymbolSpecification spec = CoreSymbolSpecification.builder().symbolId(1).type(SymbolType.FUTURES_CONTRACT_PERPETUAL).maintenanceMargin(marginMap).maintenanceMarginScaleK(1000).build();

        // 设置标记价格使分母为零
        RiskEngine.LastPriceCacheRecord priceRecord = new RiskEngine.LastPriceCacheRecord();
        priceRecord.markPrice = 100; // 使分母计算为零

        // 计算强平数量（应避免除零错误）
        long sizeToLiquidate = CoreArithmeticUtils.calculateSizeToLiquidate(position, spec, priceRecord);

        // 验证：返回0（不执行强平）
        assertEquals(0, sizeToLiquidate);
    }

    @Test
    void testCalculateSizeToLiquidate1() {
        // 多头持仓 - 部分强平
        SymbolPositionRecord position = new SymbolPositionRecord();
        position.direction = PositionDirection.LONG;
        position.openVolume = 100;
        position.openPriceSum = 10_000; // 平均开仓价100
        position.openInitMarginSum = 2_000; // 初始保证金2000

        // 维持保证金率5%
        MutableSortedMap<Long, Long> marginMap = TreeSortedMap.newMap(Comparator.naturalOrder());
        marginMap.put(0L, 50L); // 5% (50/1000=0.05)

        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(1)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .maintenanceMargin(marginMap)
                .maintenanceMarginScaleK(1000)
                .build();

        // 标记价格下跌到95（亏损状态）
        RiskEngine.LastPriceCacheRecord priceRecord = new RiskEngine.LastPriceCacheRecord();
        priceRecord.markPrice = 95;

        /*
         * 计算：
         * 未实现盈亏 = (95-100)*100 = -500
         * 权益 = 2000 - 500 = 1500
         * 维持保证金 = 95*100*0.05 = 475
         * 分子 = (1500-475)*100 = 102,500
         * 分母 = 2000 + (1*95*100) - 475 - (1*10,000)
         *     = 2000 + 9500 - 475 - 10,000 = 1,025
         * 强平数量 = 102,500 / 1,025 = 100
         */
        long sizeToLiquidate = CoreArithmeticUtils.calculateSizeToLiquidate(position, spec, priceRecord);

        // 应强平100个（全部）
        assertEquals(100, sizeToLiquidate);
    }

    @Test
    void testCalculateSizeToLiquidate2() {
        // 多头持仓 - 部分强平（更小亏损）
        SymbolPositionRecord position = new SymbolPositionRecord();
        position.direction = PositionDirection.LONG;
        position.openVolume = 200;
        position.openPriceSum = 20_000; // 平均开仓价100
        position.openInitMarginSum = 4_000; // 初始保证金4000

        // 维持保证金率4%
        MutableSortedMap<Long, Long> marginMap = TreeSortedMap.newMap(Comparator.naturalOrder());
        marginMap.put(0L, 40L); // 4% (40/1000=0.04)

        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(1)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .maintenanceMargin(marginMap)
                .maintenanceMarginScaleK(1000)
                .build();

        // 标记价格98（亏损2%）
        RiskEngine.LastPriceCacheRecord priceRecord = new RiskEngine.LastPriceCacheRecord();
        priceRecord.markPrice = 98;

        /*
         * 计算：
         * 未实现盈亏 = (98-100)*200 = -400
         * 权益 = 4000 - 400 = 3600
         * 维持保证金 = 98*200*0.04 = 784
         * 分子 = (3600-784)*200 = 563,200
         * 分母 = 4000 + (1*98*200) - 784 - (1*20,000)
         *     = 4000 + 19,600 - 784 - 20,000 = 2,816
         * 强平数量 = 563,200 / 2,816 = 200
         */
        long sizeToLiquidate = CoreArithmeticUtils.calculateSizeToLiquidate(position, spec, priceRecord);

        // 应强平200个（全部）
        assertEquals(200, sizeToLiquidate);
    }

    @Test
    void testCalculateSizeToLiquidate3() {
        // 空头持仓 - 部分强平
        SymbolPositionRecord position = new SymbolPositionRecord();
        position.direction = PositionDirection.SHORT;
        position.openVolume = 150;
        position.openPriceSum = 15_000; // 平均开仓价100
        position.openInitMarginSum = 3_000; // 初始保证金3000

        // 维持保证金率5%
        MutableSortedMap<Long, Long> marginMap = TreeSortedMap.newMap(Comparator.naturalOrder());
        marginMap.put(0L, 50L); // 5% (50/1000=0.05)

        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(1)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .maintenanceMargin(marginMap)
                .maintenanceMarginScaleK(1000)
                .build();

        // 标记价格上涨到105（亏损5%）
        RiskEngine.LastPriceCacheRecord priceRecord = new RiskEngine.LastPriceCacheRecord();
        priceRecord.markPrice = 105;

        /*
         * 计算：
         * 未实现盈亏 = (100-105)*150 = -750
         * 权益 = 3000 - 750 = 2250
         * 维持保证金 = 105*150*0.05 = 787.5 -> 787（整数）
         * 分子 = (2250-787)*150 = 219,450
         * 分母 = 3000 + (-1*105*150) - 787 - (-1*15,000)
         *     = 3000 - 15,750 - 787 + 15,000 = 1,463
         * 强平数量 = 219,450 / 1,463 ≈ 150
         */
        long sizeToLiquidate = CoreArithmeticUtils.calculateSizeToLiquidate(position, spec, priceRecord);

        // 应强平150个（全部）
        assertEquals(150, sizeToLiquidate);
    }

    @Test
    void testCalculateSizeToLiquidate4() {
        // 空头持仓 - 部分强平（更小亏损）
        SymbolPositionRecord position = new SymbolPositionRecord();
        position.direction = PositionDirection.SHORT;
        position.openVolume = 300;
        position.openPriceSum = 30_000; // 平均开仓价100
        position.openInitMarginSum = 6_000; // 初始保证金6000

        // 维持保证金率3%
        MutableSortedMap<Long, Long> marginMap = TreeSortedMap.newMap(Comparator.naturalOrder());
        marginMap.put(0L, 30L); // 3% (30/1000=0.03)

        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(1)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .maintenanceMargin(marginMap)
                .maintenanceMarginScaleK(1000)
                .build();

        // 标记价格上涨到102（亏损2%）
        RiskEngine.LastPriceCacheRecord priceRecord = new RiskEngine.LastPriceCacheRecord();
        priceRecord.markPrice = 102;

        /*
         * 计算：
         * 未实现盈亏 = (100-102)*300 = -600
         * 权益 = 6000 - 600 = 5400
         * 维持保证金 = 102*300*0.03 = 918
         * 分子 = (5400-918)*300 = 1,344,600
         * 分母 = 6000 + (-1*102*300) - 918 - (-1*30,000)
         *     = 6000 - 30,600 - 918 + 30,000 = 4,482
         * 强平数量 = 1,344,600 / 4,482 ≈ 300
         */
        long sizeToLiquidate = CoreArithmeticUtils.calculateSizeToLiquidate(position, spec, priceRecord);

        // 应强平300个（全部）
        assertEquals(300, sizeToLiquidate);
    }
}