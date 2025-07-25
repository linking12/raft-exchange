package exchange.core2.tests.integration;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreCurrencySpecification.TenPowers;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.api.ApiAdjustMarkPrice;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.reports.SymbolCurrencyReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.utils.CoreArithmeticUtils;
import exchange.core2.tests.util.ExchangeTestContainer;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
import org.junit.Test;

import static exchange.core2.tests.util.TestConstants.UID_1;
import static exchange.core2.tests.util.TestConstants.UID_2;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public final class ITExchangeCorePriceScale {

    private final int BNB_ID = 11;
    private final int USDT_ID = 12;

    private final CoreCurrencySpecification BNB = CoreCurrencySpecification.builder()
            .id(BNB_ID).name("BNB").digit(8).build();

    private final CoreCurrencySpecification USDT = CoreCurrencySpecification.builder()
            .id(USDT_ID).name("USDT").digit(6).build();

    private final CoreSymbolSpecification BNB_USDT = CoreSymbolSpecification.builder()
            .symbolId(10001)
            .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
            .baseCurrency(BNB_ID).baseScaleK(TenPowers.pow10(3))
            .quoteCurrency(USDT_ID).quoteScaleK(TenPowers.pow10(5))
            .makerFee(0).takerFee(0)
            // MM的设置基于名义价值，单位是base*quote
            .maintenanceMargin(TreeSortedMap.newMapWith(10_000 * TenPowers.pow10(3 + 5), 5L))
            .maintenanceMarginScaleK(1000)
            // 杠杆的设置基于名义价值，单位是base*quote
            .maxLeverage(TreeSortedMap.newMapWith(10_000 * TenPowers.pow10(3 + 5), 75L))
            .build();

    private final CoreSymbolSpecification BNB_USDT_SPOT = CoreSymbolSpecification.builder()
            .symbolId(20001)
            .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
            .baseCurrency(BNB_ID).baseScaleK(TenPowers.pow10(3))
            .quoteCurrency(USDT_ID).quoteScaleK(TenPowers.pow10(5))
            .makerFee(0).takerFee(0)
            .build();


    @Test
    public void testMarginTradePriceScale() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT);

            // 用户1 & 2 冲入750.55 USDT
            long chargeUsdtAmount = (long) (750.55 * USDT.getCurrencyScaleK());
            container.createUserWithMoney(UID_1, USDT_ID, chargeUsdtAmount);
            container.createUserWithMoney(UID_2, USDT_ID, chargeUsdtAmount);

            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(chargeUsdtAmount));
            });

            int txId = 1000;
            // markPrice 750
            long markPrice = 750 * BNB_USDT.getQuoteScaleK();
            container.submitCommandSync(ApiAdjustMarkPrice.builder().transactionId(txId++).symbol(BNB_USDT.symbolId).markPrice(markPrice).build(), CommandResultCode.SUCCESS);

            // 挂多单 0.1@753.4 10x
            long size = (long) (0.1 * BNB_USDT.getBaseScaleK());
            long price = (long) (753.4 * BNB_USDT.getQuoteScaleK());
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10001L)
                    .price(price)
                    .size(size)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(10002L)
                    .price(price)
                    .size(size)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .leverage(10)
                    .build(), CommandResultCode.SUCCESS);

            // 开出仓位，未实现盈亏 (750 - 753.4) * 0.1 = -0.34
            long unrealizedPnL = (long) (-0.34 * TenPowers.pow10(3 + 5));
            // 初始保证金
            long initialMargin = (long) (0.1 * 750 / 10 * TenPowers.pow10(3 + 5));
            // 持仓总成本
            long openPriceSum = (long) (0.1 * 753.4 * TenPowers.pow10(3 + 5));
            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(chargeUsdtAmount));
                assertThat(report.getPositions().get(BNB_USDT.symbolId).unrealizedProfit, is(unrealizedPnL));
                assertThat(report.getPositions().get(BNB_USDT.symbolId).openInitMarginSum, is(initialMargin));
                assertThat(report.getPositions().get(BNB_USDT.symbolId).openPriceSum, is(openPriceSum));
            });

            // 转回用户视角
            assertThat(CoreArithmeticUtils.sizePriceToCurrencyScale(unrealizedPnL, BNB_USDT, USDT) * 1.0 / USDT.getCurrencyScaleK(), is(-0.34));
        }
    }

    @Test
    public void testSpotTradePriceScale() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT_SPOT);

            // 用户1 存入 1000 USDT，用户2 存入 2 BNB
            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            long bnbDeposit = 2 * BNB.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);
            container.createUserWithMoney(UID_2, BNB_ID,  bnbDeposit);

            // 检验初始余额
            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit));
                assertThat(report.getAccounts().get(BNB_ID),  is(0L));
            });
            container.validateUserState(UID_2, report -> {
                assertThat(report.getAccounts().get(BNB_ID),  is(bnbDeposit));
                assertThat(report.getAccounts().get(USDT_ID), is(0L));
            });

            // 用户1 挂买单 0.05 BNB  @ 745.123 USDT
            long size = (long) (0.05 * BNB_USDT_SPOT.getBaseScaleK());
            long price = (long) (745.123 * BNB_USDT_SPOT.getQuoteScaleK());

            container.submitCommandSync(ApiPlaceOrder.builder()
                            .uid(UID_1)
                            .orderId(10001L)
                            .symbol(BNB_USDT_SPOT.symbolId)
                            .action(OrderAction.BID)
                            .orderType(OrderType.GTC)
                            .price(price)
                            .reservePrice(price)
                            .size(size)
                            .build(),
                    CommandResultCode.SUCCESS);

            // 用户2 挂卖单 0.05 BNB @ 745.123 USDT
            container.submitCommandSync(ApiPlaceOrder.builder()
                            .uid(UID_2)
                            .orderId(10002L)
                            .symbol(BNB_USDT_SPOT.symbolId)
                            .action(OrderAction.ASK)
                            .orderType(OrderType.GTC)
                            .price(price)
                            .size(size)
                            .build(),
                    CommandResultCode.SUCCESS);

            // 交易额 = 0.05 * 745.123 = 37.25615
            long tradeAmountCurrency = CoreArithmeticUtils.sizePriceToCurrencyScale(price * size, BNB_USDT_SPOT, USDT);
            // 再还原成浮点校验：
            double tradeAmountFloat = tradeAmountCurrency * 1.0 / USDT.getCurrencyScaleK();
            assertThat(tradeAmountFloat, is(37.25615));

            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit - tradeAmountCurrency));
                assertThat(report.getAccounts().get(BNB_ID),  is(size));
            });
            container.validateUserState(UID_2, report -> {
                assertThat(report.getAccounts().get(BNB_ID),  is(bnbDeposit - size));
                assertThat(report.getAccounts().get(USDT_ID), is(tradeAmountCurrency));
            });

            SymbolCurrencyReportResult symbolCurrencyReport = container.getSymbolCurrencyReport();
            assertThat(symbolCurrencyReport.getSymbolSpecs().get(BNB_USDT_SPOT.symbolId).getSymbolId(), is(BNB_USDT_SPOT.symbolId));
        }
    }
}
