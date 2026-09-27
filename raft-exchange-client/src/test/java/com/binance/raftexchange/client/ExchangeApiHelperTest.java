package com.binance.raftexchange.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 纯函数级单测。覆盖 long ↔ scale 的两条还原通路（{@code longToDouble}/{@code longToBigDecimal} 与 {@code doubleToLong}），以及
 * {@code multiplyScaleExact} / {@code pow10} / {@code buildSlotValueMap} / {@code getFloorValue} 这几个 helper 的边界。
 *
 * <p>
 * 断言策略：BigDecimal 等值比较一律用 {@link BigDecimal#compareTo} 而非 {@code equals}， 后者会因为 scale 不同把数值相等的两个 BigDecimal 判为不等。
 * </p>
 */
class ExchangeApiHelperTest {

    @Nested
    @DisplayName("longToBigDecimal")
    class LongToBigDecimal {

        @Test
        @DisplayName("常规正数：value/scale 还原为精确十进制")
        void restoresPositive() {
            BigDecimal v = ExchangeApiHelper.longToBigDecimal(12345L, 100L);
            assertEquals(0, v.compareTo(new BigDecimal("123.45")));
        }

        @Test
        @DisplayName("常规负数：保留符号")
        void restoresNegative() {
            BigDecimal v = ExchangeApiHelper.longToBigDecimal(-12345L, 100L);
            assertEquals(0, v.compareTo(new BigDecimal("-123.45")));
        }

        @Test
        @DisplayName("value = 0 → ZERO")
        void zeroValue() {
            BigDecimal v = ExchangeApiHelper.longToBigDecimal(0L, 1000L);
            assertEquals(0, v.compareTo(BigDecimal.ZERO));
        }

        @Test
        @DisplayName("scaleK = 0 → ZERO 兜底，不抛 ArithmeticException")
        void zeroScaleFallback() {
            assertSame(BigDecimal.ZERO, ExchangeApiHelper.longToBigDecimal(999L, 0L));
            assertSame(BigDecimal.ZERO, ExchangeApiHelper.longToBigDecimal(0L, 0L));
        }

        @Test
        @DisplayName("|value| > 2^53 仍保留全精度（这是改 BigDecimal 的核心动机）")
        void preservesPrecisionBeyondDoubleRange() {
            // 2^53 = 9_007_199_254_740_992；选一个超过它且末位非零的值
            long raw = 9_999_999_999_999_999L;
            BigDecimal restored = ExchangeApiHelper.longToBigDecimal(raw, 100L);
            // longToDouble 在此处会丢精度；longToBigDecimal 必须保留
            BigDecimal expected = new BigDecimal(raw).divide(BigDecimal.valueOf(100L));
            assertEquals(0, restored.compareTo(expected));
            // 末位 9 必须还在
            assertEquals(9, restored.unscaledValue().mod(java.math.BigInteger.TEN).intValueExact());
        }

        @Test
        @DisplayName("scaleK = 10^18：long.MAX_VALUE 被还原仍精确")
        void hugeScaleStillExact() {
            long scale = ExchangeApiHelper.pow10(18);
            BigDecimal v = ExchangeApiHelper.longToBigDecimal(Long.MAX_VALUE, scale);
            BigDecimal expected =
                new BigDecimal(Long.MAX_VALUE).divide(new BigDecimal(scale), java.math.MathContext.DECIMAL128);
            assertEquals(0, v.compareTo(expected));
        }
    }

    @Nested
    @DisplayName("doubleToLong")
    class DoubleToLong {

        @Test
        @DisplayName("常规四舍五入：123.4567 × 1000 → 123457")
        void basicRounding() {
            assertEquals(123457L, ExchangeApiHelper.doubleToLong(123.4567, 1000L));
        }

        @Test
        @DisplayName("浮点经典坑：2.3 × 10 不能得 22（Math.round 是正确选择）")
        void roundsFloatRepresentationBoundary() {
            // 2.3 实际是 2.2999999999999998，× 10 = 22.999999999999996，
            // 直接整数转换会变 22；Math.round 把它正确归 23。
            assertEquals(23L, ExchangeApiHelper.doubleToLong(2.3, 10L));
        }

        @Test
        @DisplayName("远超 long.MAX_VALUE → 立刻抛 ArithmeticException，不静默 clamp")
        void overflowThrows() {
            assertThrows(ArithmeticException.class, () -> ExchangeApiHelper.doubleToLong(1e20, 1L));
        }

        @ParameterizedTest
        @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        @DisplayName("非有限值（NaN/Infinity）→ 抛 ArithmeticException")
        void nonFiniteThrows(double v) {
            assertThrows(ArithmeticException.class, () -> ExchangeApiHelper.doubleToLong(v, 1000L));
        }
    }

    @Nested
    @DisplayName("multiplyScaleExact")
    class MultiplyScaleExact {

        @Test
        @DisplayName("常规乘法")
        void basic() {
            assertEquals(20_000L, ExchangeApiHelper.multiplyScaleExact(100L, 200L));
        }

        @Test
        @DisplayName("任一边为 0 → 返回 0（缺 scale 当作未配置）")
        void zeroSideReturnsZero() {
            assertEquals(0L, ExchangeApiHelper.multiplyScaleExact(0L, 9999L));
            assertEquals(0L, ExchangeApiHelper.multiplyScaleExact(9999L, 0L));
            assertEquals(0L, ExchangeApiHelper.multiplyScaleExact(0L, 0L));
        }

        @Test
        @DisplayName("溢出 long.MAX_VALUE → ArithmeticException（不是静默回绕）")
        void overflowThrows() {
            assertThrows(ArithmeticException.class, () -> ExchangeApiHelper.multiplyScaleExact(Long.MAX_VALUE, 2L));
        }
    }

    @Nested
    @DisplayName("pow10")
    class Pow10 {

        @ParameterizedTest
        @CsvSource({"0,  1", "1,  10", "6,  1000000", "18, 1000000000000000000",})
        void exactPowers(int digit, long expected) {
            assertEquals(expected, ExchangeApiHelper.pow10(digit));
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 19, 100})
        @DisplayName("digit 越界 → IllegalArgumentException")
        void outOfRangeThrows(int digit) {
            assertThrows(IllegalArgumentException.class, () -> ExchangeApiHelper.pow10(digit));
        }
    }

    @Nested
    @DisplayName("buildSlotValueMap")
    class BuildSlotValueMap {

        @Test
        @DisplayName("常规：偶数长度、严格递增 → 顺序构建成功")
        void basic() {
            NavigableMap<Long, Long> m = ExchangeApiHelper.buildSlotValueMap(100L, 10L, 200L, 20L, 300L, 30L);
            assertEquals(3, m.size());
            assertEquals(Long.valueOf(10L), m.get(100L));
            assertEquals(Long.valueOf(20L), m.get(200L));
            assertEquals(Long.valueOf(30L), m.get(300L));
        }

        @Test
        @DisplayName("奇数长度 → IllegalArgumentException")
        void oddLengthThrows() {
            assertThrows(IllegalArgumentException.class, () -> ExchangeApiHelper.buildSlotValueMap(100L, 10L, 200L));
        }

        @Test
        @DisplayName("slot 非严格递增 → IllegalArgumentException")
        void nonIncreasingSlotThrows() {
            assertThrows(IllegalArgumentException.class,
                () -> ExchangeApiHelper.buildSlotValueMap(100L, 10L, 100L, 20L));
            assertThrows(IllegalArgumentException.class,
                () -> ExchangeApiHelper.buildSlotValueMap(200L, 10L, 100L, 20L));
        }
    }

    @Nested
    @DisplayName("getFloorValue")
    class GetFloorValue {

        @Test
        @DisplayName("命中 floor：250 → 200 对应值")
        void floorHit() {
            NavigableMap<Long, Long> m = ExchangeApiHelper.buildSlotValueMap(100L, 10L, 200L, 20L, 300L, 30L);
            assertEquals(Long.valueOf(20L), ExchangeApiHelper.getFloorValue(m, 250L));
        }

        @Test
        @DisplayName("key 命中某个 slot：300 → 30")
        void exactKey() {
            NavigableMap<Long, Long> m = ExchangeApiHelper.buildSlotValueMap(100L, 10L, 200L, 20L, 300L, 30L);
            assertEquals(Long.valueOf(30L), ExchangeApiHelper.getFloorValue(m, 300L));
        }

        @Test
        @DisplayName("key 比所有 slot 都小 → 退回到 firstKey 对应值（既定语义）")
        void belowAllReturnsFirst() {
            NavigableMap<Long, Long> m = ExchangeApiHelper.buildSlotValueMap(100L, 10L, 200L, 20L);
            assertEquals(Long.valueOf(10L), ExchangeApiHelper.getFloorValue(m, 1L));
        }

        @Test
        @DisplayName("非 NavigableMap 入参：内部包一层 TreeMap，照样能 floor")
        void worksOnPlainMap() {
            Map<Integer, String> m = new LinkedHashMap<>();
            m.put(1, "a");
            m.put(5, "b");
            m.put(10, "c");
            assertEquals("b", ExchangeApiHelper.getFloorValue(m, 7));
        }

        @Test
        @DisplayName("空 map → IllegalArgumentException")
        void emptyThrows() {
            assertThrows(IllegalArgumentException.class, () -> ExchangeApiHelper.getFloorValue(new TreeMap<>(), 1L));
            assertThrows(IllegalArgumentException.class, () -> ExchangeApiHelper.getFloorValue(new HashMap<>(), 1L));
        }
    }
}
