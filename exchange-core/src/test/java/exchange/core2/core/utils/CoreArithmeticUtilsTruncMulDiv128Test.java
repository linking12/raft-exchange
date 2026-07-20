package exchange.core2.core.utils;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 锁定 {@link CoreArithmeticUtils#truncMulDiv128} 的正确性 —— 覆盖全仓强平价计算中
 * {@code numerator × notional / denom} 溢出场景（SHORT 时 denom &lt; 0）。
 *
 * <p>每个非平凡 case 都用 {@link BigInteger} 当 oracle 验证。
 * truncMulDiv 的截断语义 == {@link BigInteger#divide}（向零截断）。
 */
class CoreArithmeticUtilsTruncMulDiv128Test {

    // ---------- 小数值：行为应等同普通 a*b/c（向零截断）----------

    @Test
    void smallValues_matchScalarSemantics() {
        assertEquals(0L,  CoreArithmeticUtils.truncMulDiv128(0, 5, 3));
        assertEquals(1L,  CoreArithmeticUtils.truncMulDiv128(1, 1, 1));
        assertEquals(12L, CoreArithmeticUtils.truncMulDiv128(7, 9, 5));   // trunc(63/5) = 12
        assertEquals(17L, CoreArithmeticUtils.truncMulDiv128(7, 5, 2));   // trunc(35/2) = 17
        assertEquals(6L,  CoreArithmeticUtils.truncMulDiv128(6, 3, 3));   // 整除
    }

    // ---------- a × b 溢出 long，c > 0 ----------

    @Test
    void productOverflowsLong_positiveDenom_exact() {
        // 1e10 × 1e10 = 1e20，÷ 1e6 = 1e14（整除）
        assertEquals(100_000_000_000_000L,
            CoreArithmeticUtils.truncMulDiv128(10_000_000_000L, 10_000_000_000L, 1_000_000L));

        // 1e12 × 5e10 = 5e22，÷ 1e6 = 5e16
        assertEquals(50_000_000_000_000_000L,
            CoreArithmeticUtils.truncMulDiv128(1_000_000_000_000L, 50_000_000_000L, 1_000_000L));
    }

    @Test
    void productOverflowsLong_positiveDenom_truncation() {
        // 3e17+1 × 500 = 1.5e20+500，÷ 1e6 → trunc = 1.5e14（余 500，截断不加 1）
        assertEquals(150_000_000_000_000L,
            CoreArithmeticUtils.truncMulDiv128(300_000_000_000_000_001L, 500L, 1_000_000L));
    }

    // ---------- 负号：c < 0（SHORT 场景 denom < 0）----------

    @Test
    void negativeDenom_small() {
        // 15 / -2 = trunc(-7.5) = -7
        assertEquals(-7L, CoreArithmeticUtils.truncMulDiv128(5, 3, -2));
        // -15 / -2 = trunc(7.5) = 7
        assertEquals(7L,  CoreArithmeticUtils.truncMulDiv128(5, -3, -2));
        // 整除负 denom：18 / -3 = -6
        assertEquals(-6L, CoreArithmeticUtils.truncMulDiv128(6, 3, -3));
    }

    @Test
    void negativeDenom_productOverflows() {
        // -1e10 × 1e10 = -1e20，÷ -1e6 = 1e14
        assertEquals(100_000_000_000_000L,
            CoreArithmeticUtils.truncMulDiv128(10_000_000_000L, -10_000_000_000L, -1_000_000L));

        // 1e10 × 1e10 = 1e20，÷ -1e6 = -1e14
        assertEquals(-100_000_000_000_000L,
            CoreArithmeticUtils.truncMulDiv128(10_000_000_000L, 10_000_000_000L, -1_000_000L));
    }

    // ---------- 负 a 或 b ----------

    @Test
    void negativeA_productOverflows() {
        // -1e10 × 1e10 = -1e20，÷ 1e6 = -1e14
        assertEquals(-100_000_000_000_000L,
            CoreArithmeticUtils.truncMulDiv128(-10_000_000_000L, 10_000_000_000L, 1_000_000L));
    }

    @Test
    void negativeA_truncation() {
        // -(3e17+1) × 500 = -(1.5e20+500)，÷ 1e6 → trunc = -1.5e14（余 -500，截断不减 1）
        assertEquals(-150_000_000_000_000L,
            CoreArithmeticUtils.truncMulDiv128(-300_000_000_000_000_001L, 500L, 1_000_000L));
    }

    // ---------- BigInteger oracle fuzz ----------

    @Test
    void fuzz_positiveDenom_matchBigIntegerOracle() {
        long[][] cases = {
            { 1_000_000_000_000_000_000L, 9L, 7L },
            { Long.MAX_VALUE / 2, 3L, 7L },
            { 1_152_921_504_606_846_976L, 17L, 13L },
            { 9_223_372_036_854_775L, 1000L, 1_000_000L },
            { 7L, Long.MAX_VALUE / 7L, 11L },
        };
        for (long[] tc : cases) {
            assertMatchesOracle(tc[0], tc[1], tc[2]);
        }
    }

    @Test
    void fuzz_negativeDenom_matchBigIntegerOracle() {
        // 全仓强平价：numerator 可正可负，notional 正，denom 负（SHORT）
        long[][] cases = {
            {  100_000_000L * 3_000_000_000L,  500L, -1_000_000L },
            { -100_000_000L * 3_000_000_000L,  500L, -1_000_000L },
            {  10_000_000_000L, -10_000_000_000L, -1_000_000L },
            {  300_000_000_000_000_001L, 500L, -1_000_000L },
            { -300_000_000_000_000_001L, 500L, -999_999L },
        };
        for (long[] tc : cases) {
            assertMatchesOracle(tc[0], tc[1], tc[2]);
        }
    }

    @Test
    void fuzz_allSignCombinations_matchBigIntegerOracle() {
        long a = 1_234_567_890_123L;
        long b = 9_876_543_210L;
        long c = 7_777_777L;
        for (int sa : new int[]{1, -1}) {
            for (int sb : new int[]{1, -1}) {
                for (int sc : new int[]{1, -1}) {
                    assertMatchesOracle(sa * a, sb * b, sc * c);
                }
            }
        }
    }

    // ---------- 错误参数 ----------

    @Test
    void zeroDivisor_throws() {
        assertThrows(ArithmeticException.class, () -> CoreArithmeticUtils.truncMulDiv128(1, 1, 0));
    }

    // ---------- truncMulDiv fast/slow path 切换 ----------

    @Test
    void truncMulDiv_fastPath_noOverflow() {
        // 3 × 4 / 5 = 2（不溢出，走 fast path）
        assertEquals(2L, CoreArithmeticUtils.truncMulDiv(3, 4, 5));
    }

    @Test
    void truncMulDiv_slowPath_overflowFallback() {
        // 1e10 × 1e10 溢出，走 slow path
        assertEquals(100_000_000_000_000L,
            CoreArithmeticUtils.truncMulDiv(10_000_000_000L, 10_000_000_000L, 1_000_000L));
    }

    @Test
    void truncMulDiv_negativeDenom_slowPath() {
        assertEquals(-100_000_000_000_000L,
            CoreArithmeticUtils.truncMulDiv(10_000_000_000L, 10_000_000_000L, -1_000_000L));
    }

    // ---------- helper ----------

    private static void assertMatchesOracle(long a, long b, long c) {
        long expected = BigInteger.valueOf(a).multiply(BigInteger.valueOf(b))
            .divide(BigInteger.valueOf(c))
            .longValueExact();
        assertEquals(expected, CoreArithmeticUtils.truncMulDiv128(a, b, c),
            "mismatch on a=" + a + " b=" + b + " c=" + c);
    }
}
