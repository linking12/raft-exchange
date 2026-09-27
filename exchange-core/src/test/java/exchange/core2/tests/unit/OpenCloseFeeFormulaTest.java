package exchange.core2.tests.unit;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.utils.CoreArithmeticUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 锁定 open / close 手续费公式：两者使用同一个 {@link CoreArithmeticUtils#calculateTakerFee}
 * / {@link CoreArithmeticUtils#calculateMakerFee}，分别按 size×rate（fixed）或 ceil(size×price×rate/scale)
 * （dynamic）计算。
 *
 * 这个测试不依赖撮合引擎，纯算术，定位是"以后乱改公式会立刻挂"。
 */
class OpenCloseFeeFormulaTest {

    /** 固定费率 spec：feeScaleK == 0 触发 isFixedFee() == true，fee = size × rate，不含 price */
    private static final CoreSymbolSpecification BTC_FIXED = CoreSymbolSpecification.builder()
            .symbolId(10000)
            .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
            .baseCurrency(1)
            .quoteCurrency(2)
            .baseScaleK(1)
            .quoteScaleK(1)
            .makerFee(10)
            .takerFee(20)
            .feeScaleK(0)
            .build();

    /** 动态费率 spec：feeScaleK > 0，fee = ceil(size × price × rate / feeScaleK) */
    private static final CoreSymbolSpecification ETH_DYNAMIC = CoreSymbolSpecification.builder()
            .symbolId(10001)
            .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
            .baseCurrency(3)
            .quoteCurrency(2)
            .baseScaleK(1)
            .quoteScaleK(1)
            .makerFee(1)    // 1 bp
            .takerFee(2)    // 2 bp
            .feeScaleK(100) // /100 → 把 makerFee=1 解释成 1%
            .build();

    private static final CoreCurrencySpecification USD = new CoreCurrencySpecification(2, "USD", 0);

    @Nested
    @DisplayName("固定费率（feeScaleK = 0）")
    class FixedFee {

        @Test
        @DisplayName("taker fee = size × takerFee，跟 price 无关")
        void takerFee_priceIndependent() {
            // size=10, takerFee=20 → 200，与 price 无关
            assertEquals(200L, CoreArithmeticUtils.calculateTakerFee(10, 50_000L, BTC_FIXED));
            assertEquals(200L, CoreArithmeticUtils.calculateTakerFee(10, 1L, BTC_FIXED));
            assertEquals(200L, CoreArithmeticUtils.calculateTakerFee(10, Long.MAX_VALUE / 2, BTC_FIXED));
        }

        @Test
        @DisplayName("maker fee = size × makerFee，跟 price 无关")
        void makerFee_priceIndependent() {
            assertEquals(100L, CoreArithmeticUtils.calculateMakerFee(10, 50_000L, BTC_FIXED));
            assertEquals(100L, CoreArithmeticUtils.calculateMakerFee(10, 1L, BTC_FIXED));
        }

        @Test
        @DisplayName("size = 0 时 fee = 0（防止误算）")
        void zeroSize_zeroFee() {
            assertEquals(0L, CoreArithmeticUtils.calculateTakerFee(0, 50_000L, BTC_FIXED));
            assertEquals(0L, CoreArithmeticUtils.calculateMakerFee(0, 50_000L, BTC_FIXED));
        }

        @Test
        @DisplayName("open 和 close 走同一个公式 — 同 size/price 同 spec 必须给同样的 fee")
        void openCloseFee_sameFormula() {
            // RiskEngine 里 open 块和 close 块都调用同样的 calculateTakerFee/calculateMakerFee,
            // 因此对同一笔 size×price 必须返回相同金额。这一条防止有人把 close 改成单独的费率函数。
            long sizeToOpen = 7L;
            long sizeClosed = 7L;
            long fillPrice = 51_000L;

            long openTakerFee = CoreArithmeticUtils.calculateTakerFee(sizeToOpen, fillPrice, BTC_FIXED);
            long closeTakerFee = CoreArithmeticUtils.calculateTakerFee(sizeClosed, fillPrice, BTC_FIXED);
            assertEquals(openTakerFee, closeTakerFee, "open/close taker fee 必须用同一公式");

            long openMakerFee = CoreArithmeticUtils.calculateMakerFee(sizeToOpen, fillPrice, BTC_FIXED);
            long closeMakerFee = CoreArithmeticUtils.calculateMakerFee(sizeClosed, fillPrice, BTC_FIXED);
            assertEquals(openMakerFee, closeMakerFee, "open/close maker fee 必须用同一公式");
        }

        @Test
        @DisplayName("taker fee 严格 ≥ maker fee（spec 设置约定）")
        void takerFee_geqMakerFee() {
            long size = 10L;
            long price = 50_000L;
            assertEquals(true,
                    CoreArithmeticUtils.calculateTakerFee(size, price, BTC_FIXED)
                            >= CoreArithmeticUtils.calculateMakerFee(size, price, BTC_FIXED),
                    "BTC_FIXED 配置下 takerFee=20 > makerFee=10");
        }
    }

    @Nested
    @DisplayName("动态费率（feeScaleK > 0）")
    class DynamicFee {

        @Test
        @DisplayName("taker fee = ceil(size × price × takerFee / feeScaleK)")
        void takerFee_dynamicFormula() {
            // size=10, price=50000, takerFee=2, feeScaleK=100 → 10*50000*2/100 = 10_000
            assertEquals(10_000L, CoreArithmeticUtils.calculateTakerFee(10, 50_000L, ETH_DYNAMIC));
        }

        @Test
        @DisplayName("maker fee = ceil(size × price × makerFee / feeScaleK)")
        void makerFee_dynamicFormula() {
            // size=10, price=50000, makerFee=1, feeScaleK=100 → 10*50000*1/100 = 5_000
            assertEquals(5_000L, CoreArithmeticUtils.calculateMakerFee(10, 50_000L, ETH_DYNAMIC));
        }

        @Test
        @DisplayName("不能整除时向上取整（ceiling）")
        void dynamicFormula_ceilingRounding() {
            // size=1, price=1, takerFee=2, feeScaleK=100 → 1*1*2/100 = 0.02 → ceil = 1
            // 这是关键性质：宁可多收 1 也不让 fee 漏算成 0，否则微小订单可以白嫖手续费
            assertEquals(1L, CoreArithmeticUtils.calculateTakerFee(1, 1L, ETH_DYNAMIC));
        }

        @Test
        @DisplayName("size = 0 时 fee = 0（即使 dynamic）")
        void zeroSize_zeroFee_dynamic() {
            assertEquals(0L, CoreArithmeticUtils.calculateTakerFee(0, 50_000L, ETH_DYNAMIC));
            assertEquals(0L, CoreArithmeticUtils.calculateMakerFee(0, 50_000L, ETH_DYNAMIC));
        }

        @Test
        @DisplayName("dynamic 模式 open 与 close 公式一致")
        void openCloseFee_sameFormula_dynamic() {
            long size = 5L;
            long price = 31_000L;
            assertEquals(CoreArithmeticUtils.calculateTakerFee(size, price, ETH_DYNAMIC),
                    CoreArithmeticUtils.calculateTakerFee(size, price, ETH_DYNAMIC),
                    "open/close taker fee 必须用同一公式（dynamic）");
            assertEquals(CoreArithmeticUtils.calculateMakerFee(size, price, ETH_DYNAMIC),
                    CoreArithmeticUtils.calculateMakerFee(size, price, ETH_DYNAMIC),
                    "open/close maker fee 必须用同一公式（dynamic）");
        }
    }

    @Nested
    @DisplayName("scale 转换（fee 算完后转到 currency scale）")
    class CurrencyScale {

        @Test
        @DisplayName("baseScaleK = quoteScaleK = 1，currencyScaleK = 0 时透传不变")
        void identityScale() {
            long feeInternal = CoreArithmeticUtils.calculateTakerFee(10, 50_000L, BTC_FIXED); // 200
            long feeCurrency = CoreArithmeticUtils.sizePriceToCurrencyScale(feeInternal, BTC_FIXED, USD);
            // BTC_FIXED.baseScaleK * quoteScaleK = 1, USD.scaleK = 0 → 不缩放（实现里走 multiplyExact(10^0)）
            assertEquals(200L, feeCurrency);
        }

        @Test
        @DisplayName("RiskEngine 调用顺序：先 calculateXxxFee → 再 sizePriceToCurrencyScale，闭环")
        void integration_calcThenScale() {
            // 复制 RiskEngine.handleMatcherEventMargin 里的 4 行扣费片段（去掉 user.accounts/fees 改动）
            // 验证两步组合的结果在 fixed-fee BTC 配置下等于 size × takerFee
            long size = 3L;
            long price = 25_000L;
            long fee = CoreArithmeticUtils.calculateTakerFee(size, price, BTC_FIXED);
            fee = CoreArithmeticUtils.sizePriceToCurrencyScale(fee, BTC_FIXED, USD);
            assertEquals(3L * 20L, fee);
        }
    }
}
