package exchange.core2.tests.unit;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.utils.CoreArithmeticUtils;
import org.junit.jupiter.api.Test;

import static exchange.core2.core.common.SymbolType.CURRENCY_EXCHANGE_PAIR;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CoreArithmeticUtilsScaleTest {

    // 测试配置：BTC/USD 交易对
    private CoreSymbolSpecification createBtcUsdSymbol() {
        return CoreSymbolSpecification.builder()
                .symbolId(1).type(CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(1)  // BTC
                .quoteCurrency(2) // USD
                .baseScaleK(100_000L)  // 1手 = 100,000 satoshi (0.001 BTC)
                .quoteScaleK(10L)      // 1步长 = 0.1 USD
                .build();
    }

    private CoreCurrencySpecification createCurrency(int currencyId, int digit) {
        return new CoreCurrencySpecification(currencyId, "CUR" + currencyId, digit);
    }

    //--- 测试 sizePriceToCurrencyScale (撮合内部单位 → 币种最小单位) ---//
    @Test
    void sizePriceToCurrencyScale_ConvertToQuoteCurrency() {
        // 配置
        CoreSymbolSpecification spec = createBtcUsdSymbol();
        CoreCurrencySpecification usd = createCurrency(2, 2); // USD (1 USD = 100 cents)

        // 测试：1内部单位 = (1 * 100) / (100,000 * 10) = 0.0001 USD → 0.01 cents (截断为0)
        long result = CoreArithmeticUtils.sizePriceToCurrencyScale(1, spec, usd);
        assertEquals(0, result); // 0.0001 USD = 0.01 cents → 截断为0

        // 测试：1e6内部单位 = (1e6 * 100) / 1e6 = 100 cents (1 USD)
        result = CoreArithmeticUtils.sizePriceToCurrencyScale(1_000_000L, spec, usd);
        assertEquals(100, result);
    }

    //--- 测试 symbolToCurrencyScale (交易单位 → 币种最小单位) ---//
    @Test
    void symbolToCurrencyScale_BaseToBaseCurrency() {
        // 配置
        CoreSymbolSpecification spec = createBtcUsdSymbol();
        CoreCurrencySpecification btc = createCurrency(1, 8); // BTC (1 BTC = 100,000,000 satoshi)

        // 测试：1手 = 1 * 100,000,000 / 100,000 = 1,000 satoshi
        long result = CoreArithmeticUtils.symbolToCurrencyScale(1, spec, btc);
        assertEquals(1_000, result);

        // 测试：2手 = 2,000 satoshi
        result = CoreArithmeticUtils.symbolToCurrencyScale(2, spec, btc);
        assertEquals(2_000, result);
    }

    @Test
    void symbolToCurrencyScale_QuoteToQuoteCurrency() {
        // 配置
        CoreSymbolSpecification spec = createBtcUsdSymbol();
        CoreCurrencySpecification usd = createCurrency(2, 2); // USD (1 USD = 100 cents)

        // 测试：1步长 = 1 * 100 / 10 = 10 cents (0.1 USD)
        long result = CoreArithmeticUtils.symbolToCurrencyScale(1, spec, usd);
        assertEquals(10, result);

        // 测试：2步长 = 20 cents
        result = CoreArithmeticUtils.symbolToCurrencyScale(2, spec, usd);
        assertEquals(20, result);
    }

    //--- 测试 currencyToSymbolScale (币种最小单位 → 交易单位) ---//
    @Test
    void currencyToSymbolScale_BaseCurrencyToBase() {
        // 配置
        CoreSymbolSpecification spec = createBtcUsdSymbol();
        CoreCurrencySpecification btc = createCurrency(1, 8); // BTC

        // 测试：1,000 satoshi = (1,000 * 100,000) / 100,000,000 = 1手
        long result = CoreArithmeticUtils.currencyToSymbolScale(1_000, spec, btc);
        assertEquals(1, result);

        // 测试：1,500 satoshi = 1.5手 → 截断为1手
        result = CoreArithmeticUtils.currencyToSymbolScale(1_500, spec, btc);
        assertEquals(1, result);
    }

    @Test
    void currencyToSymbolScale_QuoteCurrencyToQuote() {
        // 配置
        CoreSymbolSpecification spec = createBtcUsdSymbol();
        CoreCurrencySpecification usd = createCurrency(2, 2); // USD

        // 测试：10 cents = (10 * 10) / 100 = 1步长
        long result = CoreArithmeticUtils.currencyToSymbolScale(10, spec, usd);
        assertEquals(1, result);

        // 测试：15 cents = 1.5步长 → 截断为1步长
        result = CoreArithmeticUtils.currencyToSymbolScale(15, spec, usd);
        assertEquals(1, result);
    }

    //--- 边界测试：零值和货币精度为0 ---//
    @Test
    void scaleMethods_WithZeroInput() {
        CoreSymbolSpecification spec = createBtcUsdSymbol();
        CoreCurrencySpecification btc = createCurrency(1, 8);
        CoreCurrencySpecification usd = createCurrency(2, 2);

        // sizePriceToCurrencyScale 零输入
        assertEquals(0, CoreArithmeticUtils.sizePriceToCurrencyScale(0, spec, usd));

        // symbolToCurrencyScale 零输入
        assertEquals(0, CoreArithmeticUtils.symbolToCurrencyScale(0, spec, btc));
        assertEquals(0, CoreArithmeticUtils.symbolToCurrencyScale(0, spec, usd));

        // currencyToSymbolScale 零输入
        assertEquals(0, CoreArithmeticUtils.currencyToSymbolScale(0, spec, btc));
        assertEquals(0, CoreArithmeticUtils.currencyToSymbolScale(0, spec, usd));
    }

    @Test
    void scaleMethods_WithZeroDigitCurrency() {
        CoreSymbolSpecification spec = createBtcUsdSymbol();
        CoreCurrencySpecification zeroDigitCurr = createCurrency(1, 0); // 1单位 = 1最小单位

        // 测试：1内部单位 → (1 * 1) / 1,000,000 = 0
        long result = CoreArithmeticUtils.sizePriceToCurrencyScale(1, spec, zeroDigitCurr);
        assertEquals(0, result);

        // 测试：1手 → (1 * 1) / 100,000 = 0
        result = CoreArithmeticUtils.symbolToCurrencyScale(1, spec, zeroDigitCurr);
        assertEquals(0, result);

        // 测试：1最小单位 → (1 * 100,000) / 1 = 100,000手
        result = CoreArithmeticUtils.currencyToSymbolScale(1, spec, zeroDigitCurr);
        assertEquals(100_000, result);
    }
}