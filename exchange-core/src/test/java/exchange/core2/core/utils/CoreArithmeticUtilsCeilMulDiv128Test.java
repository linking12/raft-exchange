package exchange.core2.core.utils;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 锁定 {@link CoreArithmeticUtils#ceilMulDiv128} 的正确性 —— 包内 fallback 路径，
 * 覆盖分块算法（{@link CoreArithmeticUtils#ceilMulDiv}）已经救不了的 a*b 溢出 long 场景。
 *
 * <p>每个非平凡 case 都用 {@link BigInteger} 当作 oracle 验证，避免手算错误。
 */
class CoreArithmeticUtilsCeilMulDiv128Test {

    // ---------- 小数值：行为应等同普通 ceil(a*b/c) ----------

    @Test
    void smallValues_matchScalarSemantics() {
        assertEquals(0L,  CoreArithmeticUtils.ceilMulDiv128(0, 5, 3));
        assertEquals(1L,  CoreArithmeticUtils.ceilMulDiv128(1, 1, 1));
        assertEquals(2L,  CoreArithmeticUtils.ceilMulDiv128(2, 3, 4));      // ceil(6/4) = 2
        assertEquals(13L, CoreArithmeticUtils.ceilMulDiv128(7, 9, 5));      // ceil(63/5) = 13
        assertEquals(18L, CoreArithmeticUtils.ceilMulDiv128(7, 5, 2));      // ceil(35/2) = 18
    }

    // ---------- a*b 不溢出 long 的整除场景 ----------

    @Test
    void ethTradeMakerFee_noOverflowInProduct() {
        // 1 ETH @ 3000：tradeAmount = 1e8 * 3e9 = 3e17 fits in long
        // 但 tradeAmount * 500 = 1.5e20 超出 long —— 我们救的就是这一步。
        long tradeAmount = 100_000_000L * 3_000_000_000L;       // 3e17
        long expected = 150_000_000_000_000L;                    // 1.5e14
        assertEquals(expected, CoreArithmeticUtils.ceilMulDiv128(tradeAmount, 500L, 1_000_000L));
    }

    // ---------- a × b 本身就溢出 long ----------

    @Test
    void productOverflowsLong_stillExact() {
        // 1e10 × 1e10 = 1e20 远超 long，÷ 1e6 = 1e14
        assertEquals(100_000_000_000_000L,
            CoreArithmeticUtils.ceilMulDiv128(10_000_000_000L, 10_000_000_000L, 1_000_000L));

        // 5e10 × 2e10 = 1e21，÷ 1e6 = 1e15
        assertEquals(1_000_000_000_000_000L,
            CoreArithmeticUtils.ceilMulDiv128(50_000_000_000L, 20_000_000_000L, 1_000_000L));

        // 1e12 × 5e10 = 5e22，÷ 1e6 = 5e16
        assertEquals(50_000_000_000_000_000L,
            CoreArithmeticUtils.ceilMulDiv128(1_000_000_000_000L, 50_000_000_000L, 1_000_000L));
    }

    // ---------- ceil 行为：余数非零时 +1 ----------

    @Test
    void ceilingRoundingOnLargeOverflow() {
        // a = 3e17 + 1 → a*500 = 1.5e20 + 500，÷ 1e6 = 1.5e14 + 0.0005 → ceil = 1.5e14 + 1
        assertEquals(150_000_000_000_001L,
            CoreArithmeticUtils.ceilMulDiv128(300_000_000_000_000_001L, 500L, 1_000_000L));
    }

    // ---------- 负值 ----------

    @Test
    void negative_smallValues() {
        // ceil(-15/2) = -7（向 +∞ 取整，更靠近 0）
        assertEquals(-7L, CoreArithmeticUtils.ceilMulDiv128(5, -3, 2));
        // 整除负无需 +1：ceil(-18/3) = -6
        assertEquals(-6L, CoreArithmeticUtils.ceilMulDiv128(6, -3, 3));
    }

    @Test
    void negative_productOverflowsLong() {
        // -1e10 × 1e10 = -1e20，÷ 1e6 = -1e14
        assertEquals(-100_000_000_000_000L,
            CoreArithmeticUtils.ceilMulDiv128(10_000_000_000L, -10_000_000_000L, 1_000_000L));

        // 负 + 余数：a = -(3e17+1) → a*500 = -(1.5e20+500) → ÷ 1e6 = -(1.5e14 + 0.0005), ceil = -1.5e14
        // （向 +∞ 取整：-1.5e14 - 0.0005 上取整 = -1.5e14）
        assertEquals(-150_000_000_000_000L,
            CoreArithmeticUtils.ceilMulDiv128(300_000_000_000_000_001L, -500L, 1_000_000L));
    }

    // ---------- 用 BigInteger 当 oracle 的 fuzz / 极端 case ----------

    @Test
    void fuzz_randomLargeValues_matchBigIntegerOracle() {
        long[][] cases = {
            { 1_000_000_000_000_000_000L, 9L, 1L },                    // 接近 long max × 9
            { Long.MAX_VALUE / 2, 3L, 7L },
            { 1_152_921_504_606_846_976L, 17L, 13L },                  // 2^60 × 17 / 13
            { 9_223_372_036_854_775L, 1000L, 1_000_000L },             // long-near × 1000
            { 7L, Long.MAX_VALUE / 7L, 11L },                          // b 接近 long max
        };
        for (long[] tc : cases) {
            long a = tc[0], b = tc[1], c = tc[2];
            BigInteger product = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b));
            BigInteger[] qr = product.divideAndRemainder(BigInteger.valueOf(c));
            // 向 +∞ ceil：q + (r > 0 ? 1 : 0)，符号与 BigInteger 一致
            BigInteger expected = qr[1].signum() > 0 ? qr[0].add(BigInteger.ONE) : qr[0];
            assertEquals(expected.longValueExact(),
                CoreArithmeticUtils.ceilMulDiv128(a, b, c),
                "mismatch on a=" + a + " b=" + b + " c=" + c);
        }
    }

    @Test
    void fuzz_negativeProducts_matchBigIntegerOracle() {
        long[][] cases = {
            { -1_000_000_000_000_000_000L, 9L, 7L },
            { 100_000_000L * 3_000_000_000L, -500L, 1_000_000L },      // ETH 卖方负 fee
            { -7L, 100_000_000L * 3_000_000_000L / 7L, 1_000_000L },
        };
        for (long[] tc : cases) {
            long a = tc[0], b = tc[1], c = tc[2];
            BigInteger product = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b));
            BigInteger[] qr = product.divideAndRemainder(BigInteger.valueOf(c));
            // BigInteger.divide 是向 0 截断 → q'；ceil = q' + (r > 0 ? 1 : 0)
            BigInteger expected = qr[1].signum() > 0 ? qr[0].add(BigInteger.ONE) : qr[0];
            assertEquals(expected.longValueExact(),
                CoreArithmeticUtils.ceilMulDiv128(a, b, c),
                "mismatch on a=" + a + " b=" + b + " c=" + c);
        }
    }

    // ---------- 错误参数 ----------

    @Test
    void invalidDivisor_throws() {
        assertThrows(ArithmeticException.class, () -> CoreArithmeticUtils.ceilMulDiv128(1, 1, 0));
        assertThrows(ArithmeticException.class, () -> CoreArithmeticUtils.ceilMulDiv128(1, 1, -1));
    }
}
