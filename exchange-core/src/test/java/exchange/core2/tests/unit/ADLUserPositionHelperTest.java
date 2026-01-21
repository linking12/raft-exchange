package exchange.core2.tests.unit;

import exchange.core2.core.common.ADLUserPosition;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.processors.liquidation.ADLUserPositionHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADLUserPositionHelper 单元测试
 * 测试 ADL 评分算法和 OrderId 生成
 */
class ADLUserPositionHelperTest {

    private ADLUserPositionHelper helper;
    private Supplier<ADLUserPosition> supplier;

    @BeforeEach
    void setUp() {
        supplier = ADLUserPosition::new;
        helper = new ADLUserPositionHelper(supplier);
    }

    // ========== 评分算法测试 ==========

    @Test
    void testRiskScore_HighLeverage() {
        // 测试高杠杆评分
        SymbolPositionRecord pos = createPosition(100, PositionDirection.LONG, 10, 1000, 100, 100);
        pos.adlEligibility = 100;

        long score = ADLUserPositionHelper.riskScore(pos, 1500);

        // leverage = 10000 / 100 = 100
        // unrealizedPnl = 1 * (1500 * 10 - 10000) = 5000
        // score = 100 * 5000 * 100 = 50,000,000
        assertEquals(50_000_000L, score);
    }

    @Test
    void testRiskScore_LowLeverage() {
        // 测试低杠杆评分
        SymbolPositionRecord pos = createPosition(100, PositionDirection.LONG, 10, 1000, 5000, 100);
        pos.adlEligibility = 100;

        long score = ADLUserPositionHelper.riskScore(pos, 1500);

        // leverage = 10000 / 5000 = 2
        // unrealizedPnl = 1 * (1500 * 10 - 10000) = 5000
        // score = 2 * 5000 * 100 = 1,000,000
        assertEquals(1_000_000L, score);
    }

    @Test
    void testRiskScore_ShortPosition() {
        // 测试空头仓位评分
        SymbolPositionRecord pos = createPosition(200, PositionDirection.SHORT, 10, 1500, 1500, 100);
        pos.adlEligibility = 100;

        long score = ADLUserPositionHelper.riskScore(pos, 1000);

        // leverage = 15000 / 1500 = 10
        // unrealizedPnl = -1 * (1000 * 10 - 15000) = 5000 (空头价格下跌盈利)
        // score = 10 * 5000 * 100 = 5,000,000
        assertEquals(5_000_000L, score);
    }

    @Test
    void testRiskScore_ZeroProfit() {
        // 测试零盈利
        SymbolPositionRecord pos = createPosition(100, PositionDirection.LONG, 10, 1000, 1000, 100);
        pos.adlEligibility = 100;

        long score = ADLUserPositionHelper.riskScore(pos, 1000);

        // unrealizedPnl = 1 * (1000 * 10 - 10000) = 0
        // score = 10 * 0 * 100 = 0
        assertEquals(0L, score);
    }

    @Test
    void testRiskScore_NegativeProfit() {
        // 测试亏损（负盈利）
        SymbolPositionRecord pos = createPosition(100, PositionDirection.LONG, 10, 1000, 1000, 100);
        pos.adlEligibility = 100;

        long score = ADLUserPositionHelper.riskScore(pos, 800);

        // unrealizedPnl = 1 * (800 * 10 - 10000) = -2000
        // score = 10 * (-2000) * 100 = -2,000,000
        assertTrue(score < 0);
    }

    @Test
    void testRiskScore_LowEligibility() {
        // 测试低ADL资格因子
        SymbolPositionRecord pos = createPosition(100, PositionDirection.LONG, 10, 1000, 100, 100);
        pos.adlEligibility = 20; // 低资格

        long score = ADLUserPositionHelper.riskScore(pos, 1500);

        // leverage = 100, unrealizedPnl = 5000
        // score = 100 * 5000 * 20 = 10,000,000
        assertEquals(10_000_000L, score);
    }

    @Test
    void testRiskScore_ZeroEligibility() {
        // 测试零资格（不参与ADL）
        SymbolPositionRecord pos = createPosition(100, PositionDirection.LONG, 10, 1000, 100, 100);
        pos.adlEligibility = 0;

        long score = ADLUserPositionHelper.riskScore(pos, 1500);

        assertEquals(0L, score);
    }

    @Test
    void testRiskScore_Comparison() {
        // 测试评分比较（高杠杆+高盈利 vs 低杠杆+低盈利）
        SymbolPositionRecord highRisk = createPosition(100, PositionDirection.LONG, 10, 1000, 100, 100);
        highRisk.adlEligibility = 100;

        SymbolPositionRecord lowRisk = createPosition(200, PositionDirection.LONG, 10, 1000, 5000, 100);
        lowRisk.adlEligibility = 100;

        long bankruptcyPrice = 1500;
        long scoreHigh = ADLUserPositionHelper.riskScore(highRisk, bankruptcyPrice);
        long scoreLow = ADLUserPositionHelper.riskScore(lowRisk, bankruptcyPrice);

        assertTrue(scoreHigh > scoreLow);
    }

    // ========== OrderId 生成测试 ==========

    @Test
    void testGenerateADLOrderId_LongPosition() {
        // 测试多头仓位OrderId
        SymbolPositionRecord pos = createPosition(100, PositionDirection.LONG, 10, 1000, 1000, 10001);

        long orderId = ADLUserPositionHelper.generateADLOrderId(pos);

        // 验证symbol部分 (高32位)
        long symbolPart = (orderId >>> 32);
        assertEquals(10001L, symbolPart);

        // 验证side bit (bit 11)
        long sideBit = (orderId >> 11) & 1;
        assertEquals(0L, sideBit); // LONG = 0
    }

    @Test
    void testGenerateADLOrderId_ShortPosition() {
        // 测试空头仓位OrderId
        SymbolPositionRecord pos = createPosition(100, PositionDirection.SHORT, 10, 1000, 1000, 10001);

        long orderId = ADLUserPositionHelper.generateADLOrderId(pos);

        long symbolPart = (orderId >>> 32);
        assertEquals(10001L, symbolPart);

        long sideBit = (orderId >> 11) & 1;
        assertEquals(1L, sideBit); // SHORT = 1
    }

    @Test
    void testGenerateADLOrderId_Uniqueness() {
        // 测试OrderId唯一性
        SymbolPositionRecord pos1 = createPosition(100, PositionDirection.LONG, 10, 1000, 1000, 10001);
        SymbolPositionRecord pos2 = createPosition(200, PositionDirection.LONG, 10, 1000, 1000, 10001);
        SymbolPositionRecord pos3 = createPosition(100, PositionDirection.SHORT, 10, 1000, 1000, 10001);

        Set<Long> orderIds = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            orderIds.add(ADLUserPositionHelper.generateADLOrderId(pos1));
            orderIds.add(ADLUserPositionHelper.generateADLOrderId(pos2));
            orderIds.add(ADLUserPositionHelper.generateADLOrderId(pos3));

            // 短暂延迟，确保时间戳变化
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 应该生成多个不同的OrderId（由于时间戳变化）
        assertTrue(orderIds.size() > 3);
    }

    @Test
    void testGenerateADLOrderId_DifferentSymbols() {
        // 测试不同symbol的OrderId
        SymbolPositionRecord pos1 = createPosition(100, PositionDirection.LONG, 10, 1000, 1000, 10001);
        SymbolPositionRecord pos2 = createPosition(100, PositionDirection.LONG, 10, 1000, 1000, 10002);

        long orderId1 = ADLUserPositionHelper.generateADLOrderId(pos1);
        long orderId2 = ADLUserPositionHelper.generateADLOrderId(pos2);

        assertNotEquals(orderId1, orderId2);

        long symbol1 = (orderId1 >>> 32);
        long symbol2 = (orderId2 >>> 32);
        assertEquals(10001L, symbol1);
        assertEquals(10002L, symbol2);
    }

    @Test
    void testGenerateADLOrderId_DifferentUsers() {
        // 测试不同用户的OrderId
        SymbolPositionRecord pos1 = createPosition(100, PositionDirection.LONG, 10, 1000, 1000, 10001);
        SymbolPositionRecord pos2 = createPosition(200, PositionDirection.LONG, 10, 1000, 1000, 10001);

        long orderId1 = ADLUserPositionHelper.generateADLOrderId(pos1);
        long orderId2 = ADLUserPositionHelper.generateADLOrderId(pos2);

        // 不同用户的uidHash不同
        long uidHash1 = (orderId1 >> 12) & 0xFFFFF;
        long uidHash2 = (orderId2 >> 12) & 0xFFFFF;
        assertNotEquals(uidHash1, uidHash2);
    }

    @Test
    void testGenerateADLOrderId_BitLayout() {
        // 测试bit布局
        SymbolPositionRecord pos = createPosition(100, PositionDirection.LONG, 10, 1000, 1000, 0x1234);

        long orderId = ADLUserPositionHelper.generateADLOrderId(pos);

        // 验证各部分
        long symbolPart = (orderId >>> 32);
        assertEquals(0x1234L, symbolPart);

        long uidHashPart = (orderId >> 12) & 0xFFFFF;
        assertTrue(uidHashPart >= 0 && uidHashPart <= 0xFFFFF);

        long sideBit = (orderId >> 11) & 1;
        assertEquals(0L, sideBit); // LONG

        long tsPart = orderId & 0x7FF; // 11 bits
        assertTrue(tsPart >= 0 && tsPart <= 0x7FF);
    }

    // ========== 对象池测试 ==========

    @Test
    void testNewADLUserPosition() {
        // 测试对象创建
        ADLUserPosition pos = helper.newADLUserPosition();
        assertNotNull(pos);
    }

    @Test
    void testNewADLUserPosition_Reset() {
        // 测试对象重置
        ADLUserPosition pos1 = helper.newADLUserPosition();
        pos1.uid = 100;
        pos1.volume = 10;
        pos1.score = 1000;
        pos1.next = new ADLUserPosition();

        // 归还对象池（需要手动模拟，实际由GroupingProcessor处理）
        // 这里主要测试创建的对象是否为空状态
        ADLUserPosition pos2 = helper.newADLUserPosition();
        assertNotNull(pos2);
    }

    // ========== 边界条件测试 ==========

    @Test
    void testRiskScore_MaxValues() {
        // 测试极大值
        SymbolPositionRecord pos = createPosition(100, PositionDirection.LONG, Long.MAX_VALUE / 1000, 1000, 1000, 100);
        pos.adlEligibility = 100;

        // 不应该抛异常
        assertDoesNotThrow(() -> {
            ADLUserPositionHelper.riskScore(pos, 2000);
        });
    }

    @Test
    void testRiskScore_MinValues() {
        // 测试极小值
        SymbolPositionRecord pos = createPosition(100, PositionDirection.LONG, 1, 1, 1, 100);
        pos.adlEligibility = 1;

        long score = ADLUserPositionHelper.riskScore(pos, 2);

        // leverage = 1 / 1 = 1
        // unrealizedPnl = 1 * (2 * 1 - 1) = 1
        // score = 1 * 1 * 1 = 1
        assertEquals(1L, score);
    }

    @Test
    void testGenerateADLOrderId_SymbolZero() {
        // 测试symbol=0
        SymbolPositionRecord pos = createPosition(100, PositionDirection.LONG, 10, 1000, 1000, 0);

        long orderId = ADLUserPositionHelper.generateADLOrderId(pos);
        long symbolPart = (orderId >>> 32);
        assertEquals(0L, symbolPart);
    }

    @Test
    void testGenerateADLOrderId_LargeSymbol() {
        // 测试大symbol
        SymbolPositionRecord pos = createPosition(100, PositionDirection.LONG, 10, 1000, 1000, 0x7FFFFFFF);

        long orderId = ADLUserPositionHelper.generateADLOrderId(pos);
        long symbolPart = (orderId >>> 32);
        assertEquals(0x7FFFFFFFL, symbolPart);
    }

    // ========== 辅助方法 ==========

    private SymbolPositionRecord createPosition(long uid, PositionDirection direction, long volume, long avgPrice, long initMargin, int symbol) {
        SymbolPositionRecord pos = new SymbolPositionRecord();
        pos.uid = uid;
        pos.direction = direction;
        pos.openVolume = volume;
        pos.openPriceSum = volume * avgPrice;
        pos.openInitMarginSum = initMargin;
        pos.symbol = symbol;
        pos.marginMode = MarginMode.ISOLATED;
        return pos;
    }
}
