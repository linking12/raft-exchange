package exchange.core2.tests.examples;

import exchange.core2.core.common.*;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.reports.SymbolCurrencyReportResult;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.junit.Test;

import static exchange.core2.core.utils.CoreArithmeticUtils.sizePriceToCurrencyScale;
import static exchange.core2.core.utils.CoreArithmeticUtils.symbolToCurrencyScale;
import static exchange.core2.tests.util.TestConstants.UID_1;
import static exchange.core2.tests.util.TestConstants.UID_2;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ShibUsdtScaleExample {

    private final int SHIB_ID = 31;
    private final int USDT_ID = 32;

    // SHIB: 交易所内部使用18位精度
    private final CoreCurrencySpecification SHIB = CoreCurrencySpecification.builder()
            .id(SHIB_ID).name("SHIB").digit(10).build();

    // USDT: 交易所内部使用6位精度
    private final CoreCurrencySpecification USDT = CoreCurrencySpecification.builder()
            .id(USDT_ID).name("USDT").digit(6).build();

    // SHIB/USDT 交易对配置
    private final CoreSymbolSpecification SHIB_USDT = CoreSymbolSpecification.builder()
            .symbolId(30001)
            .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
            .baseCurrency(SHIB_ID)
            .quoteCurrency(USDT_ID)
            .baseScaleK(10L) // 1手 = 100 SHIB内部单位 (缩放因子100)
            .quoteScaleK(10)  // 1步长 = 1 USDT内部单位
            .takerFee(10)    // Taker手续费: 10 USDT内部单位/手
            .makerFee(5)     // Maker手续费: 5 USDT内部单位/手
            .build();

    @Test
    public void testShibUsdtTradeWithFees() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            // 添加币种和交易对
            container.addCurrency(SHIB);
            container.addCurrency(USDT);
            container.addSymbol(SHIB_USDT);

            // 用户1存入100 USDT (100 * 10^6)
            long depositUsdt = 10000 * USDT.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, depositUsdt);

            // 用户2存入100_000L SHIB (10 * 10^10)
            long depositShib = 100_000L * SHIB.getCurrencyScaleK();
            container.createUserWithMoney(UID_2, SHIB_ID, depositShib);

            // 验证初始余额
            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(depositUsdt));
                assertThat(report.getAccounts().get(SHIB_ID), is(0L));
            });
            container.validateUserState(UID_2, report -> {
                assertThat(report.getAccounts().get(SHIB_ID), is(depositShib));
                assertThat(report.getAccounts().get(USDT_ID), is(0L));
            });

            // 挂单参数设置
            long orderSize = 10 * SHIB_USDT.getBaseScaleK(); // 10手 = 10 * 10 = 100 SHIB内部单位
            long orderPrice = 100 * SHIB_USDT.getQuoteScaleK(); // 1000 USDT内部单位/SHIB手

            // 用户1挂买单 (10手 @ 100 USDT内部单位/手)
            container.submitCommandSync(ApiPlaceOrder.builder()
                            .uid(UID_1)
                            .orderId(10001L)
                            .symbol(SHIB_USDT.symbolId)
                            .action(OrderAction.BID)
                            .orderType(OrderType.GTC)
                            .price(orderPrice)
                            .reservePrice(orderPrice)
                            .size(orderSize)
                            .build(),
                    CommandResultCode.SUCCESS);

            // 用户2挂卖单 (10手 @ 100 USDT内部单位/手)
            container.submitCommandSync(ApiPlaceOrder.builder()
                            .uid(UID_2)
                            .orderId(10002L)
                            .symbol(SHIB_USDT.symbolId)
                            .action(OrderAction.ASK)
                            .orderType(OrderType.GTC)
                            .price(orderPrice)
                            .size(orderSize)
                            .build(),
                    CommandResultCode.SUCCESS);

            // 计算预期结果
            long tradeAmount = sizePriceToCurrencyScale(orderSize * orderPrice, SHIB_USDT, USDT); // 交易额 = 10 * 100 = 1000 USDT内部单位
            long takerFee = sizePriceToCurrencyScale(SHIB_USDT.takerFee * orderSize, SHIB_USDT, USDT); // Taker手续费 = 10 * 10 = 100 USDT内部单位
            long makerFee = sizePriceToCurrencyScale(SHIB_USDT.makerFee * orderSize, SHIB_USDT, USDT); // Maker手续费 = 10 * 5 = 50 USDT内部单位
            long shibTransferred = symbolToCurrencyScale(orderSize, SHIB_USDT, SHIB); // SHIB数量 = 10 * 100 = 1000 SHIB内部单位

            // 验证用户1余额 (买家)
            container.validateUserState(UID_1, report -> {
                // 支出: 交易额 + Maker手续费
                long expectedUsdt = depositUsdt - tradeAmount - makerFee;
                assertThat(report.getAccounts().get(USDT_ID), is(expectedUsdt));

                // 收到: SHIB
                assertThat(report.getAccounts().get(SHIB_ID), is(shibTransferred));
            });

            // 验证用户2余额 (卖家)
            container.validateUserState(UID_2, report -> {
                // 支出: SHIB
                assertThat(report.getAccounts().get(SHIB_ID), is(depositShib - shibTransferred));

                // 收入: 交易额 - Taker手续费
                long expectedUsdt = tradeAmount - takerFee;
                assertThat(report.getAccounts().get(USDT_ID), is(expectedUsdt));
            });

            // 全局资金平衡检查
            TotalCurrencyBalanceReportResult totalReport = container.totalBalanceReport();

            // SHIB总量守恒
            long totalShib = totalReport.getClientsBalancesSum().get(SHIB_ID) + totalReport.getFees().get(SHIB_ID);
            assertThat(totalShib, is(depositShib));

            // USDT总量守恒
            long totalUsdt = totalReport.getClientsBalancesSum().get(USDT_ID) + totalReport.getFees().get(USDT_ID);
            assertThat(totalUsdt, is(depositUsdt));

            // 验证手续费收取
            assertThat(totalReport.getFees().get(USDT_ID), is(takerFee + makerFee));
            assertThat(totalReport.getFees().get(SHIB_ID), is(0L));
            assertTrue(totalReport.isGlobalBalancesAllZero());

            // 验证报告中的交易对信息
            SymbolCurrencyReportResult symbolReport = container.getSymbolCurrencyReport();
            assertThat(symbolReport.getSymbolSpecs().get(SHIB_USDT.symbolId).getSymbolId(), is(SHIB_USDT.symbolId));
            assertThat(symbolReport.getCurrencySpecs().get(SHIB_ID).digit, is(10));
            assertThat(symbolReport.getCurrencySpecs().get(USDT_ID).digit, is(6));
        }
    }
}