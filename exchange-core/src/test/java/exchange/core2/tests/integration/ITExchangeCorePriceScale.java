package exchange.core2.tests.integration;

import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.TenPowers;
import exchange.core2.core.common.api.*;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class ITExchangeCorePriceScale {

    private final int BTC_ID = 8;
    private final int ETH_ID = 9;
    private final int BNB_ID = 11;
    private final int USDT_ID = 12;

    private final CoreCurrencySpecification BTC = CoreCurrencySpecification.builder()
            .id(BNB_ID).name("BNB").digit(8).build();

    private final CoreCurrencySpecification ETH = CoreCurrencySpecification.builder()
            .id(BNB_ID).name("BNB").digit(8).build();

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

    private final CoreSymbolSpecification BTC_USDT = CoreSymbolSpecification.builder()
            .symbolId(10002)
            .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
            .baseCurrency(BTC_ID).baseScaleK(TenPowers.pow10(4))
            .quoteCurrency(USDT_ID).quoteScaleK(TenPowers.pow10(5))
            .makerFee(1).takerFee(2)
            // MM的设置基于名义价值，单位是base*quote
            .maintenanceMargin(TreeSortedMap.newMapWith(10_000 * TenPowers.pow10(4 + 5), 5L))
            .maintenanceMarginScaleK(1000)
            // 杠杆的设置基于名义价值，单位是base*quote
            .maxLeverage(TreeSortedMap.newMapWith(10_000 * TenPowers.pow10(4 + 5), 10L))
            .build();

    private final CoreSymbolSpecification ETH_USDT = CoreSymbolSpecification.builder()
            .symbolId(10003)
            .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
            .baseCurrency(ETH_ID).baseScaleK(TenPowers.pow10(3))
            .quoteCurrency(USDT_ID).quoteScaleK(TenPowers.pow10(5))
            .makerFee(1).takerFee(2)
            .feeScaleK(100)
            // MM的设置基于名义价值，单位是base*quote
            .maintenanceMargin(TreeSortedMap.newMapWith(10_000 * TenPowers.pow10(3 + 5), 5L))
            .maintenanceMarginScaleK(1000)
            // 杠杆的设置基于名义价值，单位是base*quote
            .maxLeverage(TreeSortedMap.newMapWith(10_000 * TenPowers.pow10(3 + 5), 50L))
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
                assertThat(report.getPositions().get(BNB_USDT.symbolId).get(0).unrealizedProfit, is(unrealizedPnL));
                assertThat(report.getPositions().get(BNB_USDT.symbolId).get(0).openInitMarginSum, is(initialMargin));
                assertThat(report.getPositions().get(BNB_USDT.symbolId).get(0).openPriceSum, is(openPriceSum));
            });

            // 转回用户视角
            assertThat(CoreArithmeticUtils.sizePriceToCurrencyScale(unrealizedPnL, BNB_USDT, USDT) * 1.0 / USDT.getCurrencyScaleK(), is(-0.34));
        }
    }

    @Test
    public void testSpotTradePriceScale() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.setConsumer(new SimpleEventsProcessor(new ITradeEventsHandler() {
                @Override
                public void orderBook(OrderBook orderBook) {

                }

                @Override
                public void spotExecutionReport(SpotExecutionReport executionReport) {

                }

                @Override
                public void futuresExecutionReport(FuturesExecutionReport executionReport) {

                }
            }, new IFundEventsHandler() {
                @Override
                public void fundEventReport(FundEventReport fundEventReport) {

                }
            }));
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT_SPOT);

            // 用户1 存入 1000 USDT，用户2 存入 2 BNB
            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            long bnbDeposit = 2 * BNB.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);
            container.createUserWithMoney(UID_2, BNB_ID, bnbDeposit);

            // 检验初始余额
            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit));
                assertThat(report.getAccounts().get(BNB_ID), is(0L));
            });
            container.validateUserState(UID_2, report -> {
                assertThat(report.getAccounts().get(BNB_ID), is(bnbDeposit));
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

            // 收入 0.05 BNB
            long bnbBalance = CoreArithmeticUtils.symbolToCurrencyScale(size, BNB_USDT_SPOT, BNB);
            // 再还原成浮点校验：
            double bnbBalanceFloat = bnbBalance * 1.0 / BNB.getCurrencyScaleK();
            assertThat(bnbBalanceFloat, is(0.05));

            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit - tradeAmountCurrency));
                assertThat(report.getAccounts().get(BNB_ID), is(bnbBalance));
            });
            container.validateUserState(UID_2, report -> {
                assertThat(report.getAccounts().get(BNB_ID), is(bnbDeposit - bnbBalance));
                assertThat(report.getAccounts().get(USDT_ID), is(tradeAmountCurrency));
            });

            SymbolCurrencyReportResult symbolCurrencyReport = container.getSymbolCurrencyReport();
            assertThat(symbolCurrencyReport.getSymbolSpecs().get(BNB_USDT_SPOT.symbolId).getSymbolId(), is(BNB_USDT_SPOT.symbolId));
        }
    }

    // 提现时要考虑期货持仓
    @Test
    public void testWithdrawWhenMarginExist() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT);
            // markPrice 750
            long markPrice = 750 * BNB_USDT.getQuoteScaleK();
            container.initMarkPrice(BNB_USDT.symbolId, markPrice);

            // 用户1 存入 1000 USDT，用户2 存入 2 BNB
            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            long bnbDeposit = 2 * BNB.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);
            container.createUserWithMoney(UID_2, BNB_ID, bnbDeposit);

            // 用户1 挂买单 1@750USDT
            long size = 1 * BNB_USDT_SPOT.getBaseScaleK();
            long price = 750 * BNB_USDT_SPOT.getQuoteScaleK();

            // 期货持仓隐式冻结750 USDT
            container.submitCommandSync(ApiPlaceOrder.builder()
                            .uid(UID_1)
                            .orderId(10001L)
                            .symbol(BNB_USDT.symbolId)
                            .action(OrderAction.BID)
                            .orderType(OrderType.GTC)
                            .price(price)
                            .reservePrice(price)
                            .size(size)
                            .build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit));
            });

            // 尝试提现
            ApiAdjustUserBalance cmd = ApiAdjustUserBalance.builder().uid(UID_1).transactionId(container.getRandomTransactionId()).amount(-usdtDeposit).currency(USDT_ID).build();
            container.submitCommandSync(cmd, CommandResultCode.RISK_NSF);
            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit));
            });

            // 期货持仓冻结750 USDT
            long extraUsdtDeposit = 750 * USDT.getCurrencyScaleK() - 1;
            container.addMoneyToUser(UID_1, USDT_ID, extraUsdtDeposit);
            container.submitCommandSync(cmd, CommandResultCode.RISK_NSF);

            container.addMoneyToUser(UID_1, USDT_ID, 1);
            cmd = ApiAdjustUserBalance.builder().uid(UID_1).transactionId(100L).amount(-usdtDeposit).currency(USDT_ID).build();
            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);
        }
    }

    // 现货下单时要考虑期货持仓
    @Test
    public void testPlaceExchangeWhenMarginExist() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT_SPOT);
            container.addSymbol(BNB_USDT);
            // markPrice 500
            long markPrice = 500 * BNB_USDT.getQuoteScaleK();
            container.initMarkPrice(BNB_USDT.symbolId, markPrice);

            // 用户1 存入 1000 USDT，用户2 存入 2 BNB
            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            long bnbDeposit = 2 * BNB.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);
            container.createUserWithMoney(UID_2, BNB_ID, bnbDeposit);

            // 用户1 挂买单 1@500USDT
            long size = 1 * BNB_USDT_SPOT.getBaseScaleK();
            long price = 500 * BNB_USDT_SPOT.getQuoteScaleK();

            // 冻结了500000000, 即500u, 充了1000, 后续下现货单只能下价值500u的订单
            container.submitCommandSync(ApiPlaceOrder.builder()
                            .uid(UID_1)
                            .orderId(10001L)
                            .symbol(BNB_USDT.symbolId)
                            .action(OrderAction.BID)
                            .orderType(OrderType.GTC)
                            .price(price)
                            .reservePrice(price)
                            .size(size)
                            .build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit));
            });

            // 尝试下现货单,以price + 1下单时名义价值会超过500u所以会失败
            container.submitCommandSync(ApiPlaceOrder.builder()
                            .uid(UID_1)
                            .orderId(10002L)
                            .symbol(BNB_USDT_SPOT.symbolId)
                            .action(OrderAction.BID)
                            .orderType(OrderType.GTC)
                            .price(price + 1)
                            .reservePrice(price + 1)
                            .size(size)
                            .build(),
                    CommandResultCode.RISK_NSF);

            container.submitCommandSync(ApiPlaceOrder.builder()
                            .uid(UID_1)
                            .orderId(10003L)
                            .symbol(BNB_USDT_SPOT.symbolId)
                            .action(OrderAction.BID)
                            .orderType(OrderType.GTC)
                            .price(price)
                            .reservePrice(price)
                            .size(size)
                            .build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit / 2));
            });
        }
    }

    // 全仓增加保证金, 差1不能下单, 补充保证金后可以正常下单
    @Test
    public void testExtraMargin4Cross() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT);
            // markPrice 500
            long markPrice = 500 * BNB_USDT.getQuoteScaleK();
            container.initMarkPrice(BNB_USDT.symbolId, markPrice);

            // 用户1 存入 1000 USDT，用户2 存入 2 BNB
            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            long bnbDeposit = 2 * BNB.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);
            container.createUserWithMoney(UID_2, BNB_ID, bnbDeposit);

            // 用户1 挂买单 1@500USDT
            long size = 2 * BNB_USDT.getBaseScaleK();
            long price = 500 * BNB_USDT.getQuoteScaleK();

            // 冻结了1000u, 后续再下单会失败
            container.submitCommandSync(ApiPlaceOrder.builder()
                            .uid(UID_1)
                            .orderId(10001L)
                            .symbol(BNB_USDT.symbolId)
                            .action(OrderAction.BID)
                            .orderType(OrderType.GTC)
                            .price(price)
                            .reservePrice(price)
                            .size(size)
                            .marginMode(MarginMode.CROSS)
                            .build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit));
            });

            long size2 = 1 * BNB_USDT.getBaseScaleK();
            ApiPlaceOrder order = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10002L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .price(price)
                    .reservePrice(price)
                    .size(size2)
                    .marginMode(MarginMode.CROSS)
                    .build();
            container.submitCommandSync(order, CommandResultCode.RISK_NSF);

            // 全仓补充保证金, 差1快时还是会失败
            long extraAmount = usdtDeposit / 2 - 1;
            ApiAdjustMargin cmd = ApiAdjustMargin.builder().transactionId(container.getRandomTransactionId()).symbol(USDT_ID)
                    .uid(UID_1).amount(extraAmount).currency(USDT_ID)
                    .marginMode(MarginMode.CROSS)
                    .build();
            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(USDT_ID), is(usdtDeposit + extraAmount));
            });

            container.submitCommandSync(order, CommandResultCode.RISK_NSF);

            // 补充1快后再下单会成功
            long extraAmount2 = 1;
            ApiAdjustMargin cmd2 = ApiAdjustMargin.builder().transactionId(container.getRandomTransactionId()).symbol(USDT_ID)
                    .uid(UID_1).amount(extraAmount2).currency(USDT_ID)
                    .marginMode(MarginMode.CROSS)
                    .build();
            container.submitCommandSync(cmd2, CommandResultCode.SUCCESS);

            container.submitCommandSync(order, CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit + extraAmount + 1));
            });
        }
    }

    // 逐仓增加保证金, 差1不能下单, 补充保证金后可以正常下单
    @Test
    public void testExtraMargin4Isolated() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT);
            // markPrice 500
            long markPrice = 500 * BNB_USDT.getQuoteScaleK();
            container.initMarkPrice(BNB_USDT.symbolId, markPrice);

            // 用户1 存入 1000 USDT，用户2 存入 2 BNB
            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            long bnbDeposit = 2 * BNB.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);
            container.createUserWithMoney(UID_2, BNB_ID, bnbDeposit);

            // 用户1 挂买单 1@500USDT
            long size = 2 * BNB_USDT.getBaseScaleK();
            long price = 500 * BNB_USDT.getQuoteScaleK();

            // 冻结了1000u, 后续再下单会失败
            container.submitCommandSync(ApiPlaceOrder.builder()
                            .uid(UID_1)
                            .orderId(10001L)
                            .symbol(BNB_USDT.symbolId)
                            .action(OrderAction.BID)
                            .orderType(OrderType.GTC)
                            .price(price)
                            .reservePrice(price)
                            .size(size)
                            .marginMode(MarginMode.ISOLATED)
                            .build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit));
            });

            long size2 = 1 * BNB_USDT.getBaseScaleK();
            ApiPlaceOrder order = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(10002L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .price(price)
                    .reservePrice(price)
                    .size(size2)
                    .marginMode(MarginMode.ISOLATED)
                    .build();
            container.submitCommandSync(order, CommandResultCode.RISK_NSF);

            // 全仓补充保证金, 差1快时还是会失败
            long extraAmount = usdtDeposit / 2 - 1;
            ApiAdjustMargin cmd = ApiAdjustMargin.builder().transactionId(container.getRandomTransactionId()).symbol(USDT_ID)
                    .uid(UID_1).amount(extraAmount).currency(USDT_ID)
                    .marginMode(MarginMode.CROSS)
                    .build();
            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(USDT_ID), is(usdtDeposit + extraAmount));
            });

            container.submitCommandSync(order, CommandResultCode.RISK_NSF);

            // 补充1快后再下单会成功
            long extraAmount2 = 1;
            ApiAdjustMargin cmd2 = ApiAdjustMargin.builder().transactionId(container.getRandomTransactionId()).symbol(USDT_ID)
                    .uid(UID_1).amount(extraAmount2).currency(USDT_ID)
                    .marginMode(MarginMode.CROSS)
                    .build();
            container.submitCommandSync(cmd2, CommandResultCode.SUCCESS);

            container.submitCommandSync(order, CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit + extraAmount + 1));
            });
        }
    }

    // 调整杠杆, 测试边缘场景
    @Test
    public void testAdjustLeverage() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT);
            // markPrice 500
            long markPrice = 500 * BNB_USDT.getQuoteScaleK();
            container.initMarkPrice(BNB_USDT.symbolId, markPrice);

            // 用户1 存入 100 USDT，刚好够开10倍杠杆
            long usdtDeposit = 100 * USDT.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);

            // 用户1 挂买单 1@500USDT
            long size = 2 * BNB_USDT_SPOT.getBaseScaleK();
            long price = 500 * BNB_USDT_SPOT.getQuoteScaleK();

            // 冻结了1000u, 后续再下单会失败
            container.submitCommandSync(ApiPlaceOrder.builder()
                            .uid(UID_1)
                            .orderId(10001L)
                            .symbol(BNB_USDT.symbolId)
                            .action(OrderAction.BID)
                            .orderType(OrderType.GTC)
                            .price(price)
                            .reservePrice(price)
                            .size(size)
                            .marginMode(MarginMode.ISOLATED)
                            .leverage(10)
                            .build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit));
            });

            // 更改杠杆到更高倍数可以成功, 因为资金占用更少
            container.submitCommandSync(ApiAdjustLeverage.builder()
                    .uid(UID_1)
                    .symbol(BNB_USDT.symbolId)
                    .leverage(11)
                    .build(), CommandResultCode.SUCCESS);

            // 更改杠杆到更底倍数会失败, 因为资金刚好只能开10倍杠杆
            container.submitCommandSync(ApiAdjustLeverage.builder()
                    .uid(UID_1)
                    .symbol(BNB_USDT.symbolId)
                    .leverage(9)
                    .build(), CommandResultCode.RISK_NSF);
        }
    }

}
