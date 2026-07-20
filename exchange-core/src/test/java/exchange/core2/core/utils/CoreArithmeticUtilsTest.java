package exchange.core2.core.utils;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.SymbolType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定 ceilMulDiv 对 long 溢出场景的正确性。
 *
 * <p>原 {@link CoreArithmeticUtils#ceilDivide} 直接接 {@code tradeAmount × fee} 在 size×price
 * 接近 1e17 量级时静默溢出（spot 1 ETH @ 3000 raw scale 下 tradeAmount=3e17，乘 fee=500 后超 1.5e20
 * 远超 Long.MAX_VALUE ≈ 9.22e18），导致 fee 计算偏离实际值 ~62 倍。
 * 替换成 {@link CoreArithmeticUtils#ceilMulDiv} 后必须返回数学上正确的 {@code ceil(a×b/c)}。
 */
class CoreArithmeticUtilsTest {

    /** 1 ETH @ 3000 USDT, raw scale baseScaleK=1e8 quoteScaleK=1e6, makerFee=500 feeScaleK=1e6。 */
    @Test
    void ceilMulDiv_ethTradeMakerFee_noOverflow() {
        long size = 100_000_000L;        // 1e8 (1 ETH)
        long price = 3_000_000_000L;     // 3e9 (3000 USDT in raw)
        long tradeAmount = size * price;  // 3e17 ✓ 仍在 long 内
        long expected = 150_000_000_000_000L;  // 1.5e14 (product scale)

        // 验证：原 ceilDivide(tradeAmount * 500, 1e6) 会溢出给出 2_426_047_410_324
        assertEquals(2_426_047_410_324L,
            CoreArithmeticUtils.ceilDivide(tradeAmount * 500L, 1_000_000L),
            "原 ceilDivide 在 tradeAmount*500 溢出后给出错误值（验证 bug 存在）");

        // 新 ceilMulDiv 返回正确值
        assertEquals(expected,
            CoreArithmeticUtils.ceilMulDiv(tradeAmount, 500L, 1_000_000L),
            "ceilMulDiv 应该返回数学正确的 1.5e14");
    }

    /** 0.1 BTC @ 50000 USDT 同样的溢出场景。 */
    @Test
    void ceilMulDiv_btcTradeTakerFee_noOverflow() {
        long size = 10_000_000L;          // 0.1 BTC raw (1e7)
        long price = 50_000_000_000L;     // 50000 USDT raw (5e10)
        long tradeAmount = size * price;   // 5e17
        long expected = 500_000_000_000_000L;  // 5e14 = ceil(5e17 * 1000 / 1e6)

        assertEquals(expected,
            CoreArithmeticUtils.ceilMulDiv(tradeAmount, 1000L, 1_000_000L));
    }

    /** 不整除时的向上取整。 */
    @Test
    void ceilMulDiv_ceilingRounding() {
        // (1e6 + 1) * 3 / 7 = 3000003/7 = 428571.857... ceil = 428572
        assertEquals(428572L,
            CoreArithmeticUtils.ceilMulDiv(1_000_001L, 3L, 7L));

        // 1e6 * 7 / 3 = 7e6/3 = 2333333.33... ceil = 2333334
        assertEquals(2_333_334L,
            CoreArithmeticUtils.ceilMulDiv(1_000_000L, 7L, 3L));
    }

    /** 整除场景 ceil == 普通除法。 */
    @Test
    void ceilMulDiv_exactDivision() {
        assertEquals(150_000_000_000_000L,
            CoreArithmeticUtils.ceilMulDiv(300_000_000_000_000_000L, 500L, 1_000_000L));
        assertEquals(0L, CoreArithmeticUtils.ceilMulDiv(0L, 500L, 1_000_000L));
    }

    /** b 为负数：用于 calculateAmountBidReleaseCorrMaker 中 takerFee < makerFee 的退化场景。 */
    @Test
    void ceilMulDiv_negativeB_truncateTowardZeroEqualsCeil() {
        // ceil(5 * (-3) / 2) = ceil(-7.5) = -7
        assertEquals(-7L, CoreArithmeticUtils.ceilMulDiv(5L, -3L, 2L));
        // ceil(6 * (-3) / 2) = ceil(-9) = -9 整除
        assertEquals(-9L, CoreArithmeticUtils.ceilMulDiv(6L, -3L, 2L));
        // 大数值负 b：3e17 * -500 / 1e6 = -1.5e14
        assertEquals(-150_000_000_000_000L,
            CoreArithmeticUtils.ceilMulDiv(300_000_000_000_000_000L, -500L, 1_000_000L));
    }

    // ========================================================================
    // P0 #2：isAskPriceTooLow 不再因 price*takerFee 溢出错判
    // ========================================================================

    /** 高 price + 高 takerFee 在原版会溢出导致误判；新版用 ceilDivide(f, t) 不做大乘法。 */
    @Test
    void isAskPriceTooLow_noOverflow_highPriceHighFee() {
        // price=1e12 (raw, e.g. 1M USDT with quoteScaleK=1e6), takerFee=1e7, feeScaleK=1e6
        // 原 p*t = 1e19 溢出；正确语义：p < ceil(f/t) = ceil(1e6/1e7) = 1，即 p<1 才太低
        // 我们 p=1e12 远大于 1，应判定 NOT too low
        CoreSymbolSpecification spec = mockSpec(10_000_000L, 1_000_000L);
        assertFalse(CoreArithmeticUtils.isAskPriceTooLow(1_000_000_000_000L, spec),
            "高 price + 高 fee 不应被误判为 too low（原版会因溢出错判）");
    }

    /** 边界：price 正好等于 ceil(feeScaleK/takerFee) 应该 NOT too low；小 1 则 too low。 */
    @Test
    void isAskPriceTooLow_boundaryAtFeeRate() {
        // feeScaleK=1000, takerFee=3 → ceil(1000/3)=334；price=334 → not too low, price=333 → too low
        CoreSymbolSpecification spec = mockSpec(3L, 1000L);
        assertFalse(CoreArithmeticUtils.isAskPriceTooLow(334L, spec), "price=334 不应 too low");
        assertTrue(CoreArithmeticUtils.isAskPriceTooLow(333L, spec), "price=333 应 too low");
    }

    /** takerFee=0 + feeScaleK>0 是免手续费配置，所有 price 都不算 too low（fee=0 时不存在该 invariant）。 */
    @Test
    void isAskPriceTooLow_zeroTakerFeeProportional_returnsFalse() {
        CoreSymbolSpecification spec = mockSpec(0L, 1_000_000L);
        assertFalse(CoreArithmeticUtils.isAskPriceTooLow(1L, spec));
        assertFalse(CoreArithmeticUtils.isAskPriceTooLow(1_000_000_000_000L, spec));
    }

    /** takerFee=0 + feeScaleK=0（固定模式 fee=0）：price >= 0 都通过。 */
    @Test
    void isAskPriceTooLow_zeroTakerFeeFixed_returnsFalse() {
        CoreSymbolSpecification spec = mockSpec(0L, 0L);
        assertFalse(CoreArithmeticUtils.isAskPriceTooLow(0L, spec));
        assertFalse(CoreArithmeticUtils.isAskPriceTooLow(1L, spec));
    }

    // ========================================================================
    // ceilMulDiv hybrid：分块子项溢出时 fallback 到 Int128
    // ========================================================================

    /** 分块算法 r × b 子项超 long 时，hybrid 自动 fallback 到 Int128。 */
    @Test
    void ceilMulDiv_blockSubproductOverflows_fallbackToInt128() {
        // 设计：q*b 不溢出但 r*b 溢出，且最终结果落 long 内
        //   a = 2e10 - 1 = 19_999_999_999, b = 1e9, c = 1e10
        //   q = a/c = 1, r = 9_999_999_999; q*b = 1e9 (OK)；r*b ≈ 1e19 → 溢出 long
        //   真实结果 = ceil((2e10-1)*1e9 / 1e10) = ceil(1.9999999999e9) = 2_000_000_000
        assertEquals(2_000_000_000L,
            CoreArithmeticUtils.ceilMulDiv(19_999_999_999L, 1_000_000_000L, 10_000_000_000L));
    }

    /** ceilMulMulDiv 4-arg helper：a*b 溢出时重排到 Int128。 */
    @Test
    void ceilMulMulDiv_fastPath_smallValues() {
        // a*b*c/d = 1e8 * 3e9 * 500 / 1e6 = 1.5e14
        assertEquals(150_000_000_000_000L,
            CoreArithmeticUtils.ceilMulMulDiv(100_000_000L, 3_000_000_000L, 500L, 1_000_000L));
    }

    @Test
    void ceilMulMulDiv_slowPath_largeNotional() {
        // a*b = 1e10 * 1e10 = 1e20 溢出 long；总公式 1e10 * 1e10 * 500 / 1e6 = 5e16
        assertEquals(50_000_000_000_000_000L,
            CoreArithmeticUtils.ceilMulMulDiv(10_000_000_000L, 10_000_000_000L, 500L, 1_000_000L));
    }

    private static CoreSymbolSpecification mockSpec(long takerFee, long feeScaleK) {
        return CoreSymbolSpecification.builder()
                .symbolId(1).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(1).quoteCurrency(2)
                .baseScaleK(1L).quoteScaleK(1L)
                .takerFee(takerFee).makerFee(0L).liquidationFee(0L).feeScaleK(feeScaleK)
                .build();
    }
}
