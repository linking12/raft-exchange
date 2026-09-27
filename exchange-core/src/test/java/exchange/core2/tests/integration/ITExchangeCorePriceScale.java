package exchange.core2.tests.integration;

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
import org.junit.jupiter.api.Test;

import static exchange.core2.tests.util.ExchangeTestContainer.available;
import static exchange.core2.tests.util.TestConstants.CURRENECY_LTC;
import static exchange.core2.tests.util.TestConstants.CURRENECY_XBT;
import static exchange.core2.tests.util.TestConstants.SYMBOLSPECFEE_XBT_LTC;
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
                assertThat(available(report, USDT_ID), is(usdtDeposit));
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
                assertThat(available(report, USDT_ID), is(usdtDeposit / 2));
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

    // 校验：提现可支配上限必须按 (accounts - exchangeLocked) 算，
    // 不能动用现货挂单冻结的部分。覆盖 RiskEngine#BALANCE_ADJUSTMENT withdraw。
    @Test
    public void testWithdrawBlockedBySpotLock() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT_SPOT);

            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);

            // 现货 BID 1 BNB @ 500 USDT → 冻结 500 USDT 进 exchangeLocked
            long size = 1 * BNB_USDT_SPOT.getBaseScaleK();
            long price = 500 * BNB_USDT_SPOT.getQuoteScaleK();
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(10001L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(price).reservePrice(price).size(size).build(),
                    CommandResultCode.SUCCESS);

            long expectedLock = 500 * USDT.getCurrencyScaleK();
            long freeAfterLock = usdtDeposit - expectedLock;
            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit));
                assertThat(report.getExchangeLocked().get(USDT_ID), is(expectedLock));
                assertThat(available(report, USDT_ID), is(freeAfterLock));
            });

            // 提现 free + 1 → NSF（关键断言：不能挪用 exchangeLocked）
            container.submitCommandSync(ApiAdjustUserBalance.builder()
                    .uid(UID_1).transactionId(container.getRandomTransactionId())
                    .amount(-(freeAfterLock + 1)).currency(USDT_ID).build(),
                    CommandResultCode.RISK_NSF);

            // 提现 free → SUCCESS
            container.submitCommandSync(ApiAdjustUserBalance.builder()
                    .uid(UID_1).transactionId(container.getRandomTransactionId())
                    .amount(-freeAfterLock).currency(USDT_ID).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, report -> {
                // 提走 free 后剩下的恰好等于 lock
                assertThat(report.getAccounts().get(USDT_ID), is(expectedLock));
                assertThat(report.getExchangeLocked().get(USDT_ID), is(expectedLock));
                assertThat(available(report, USDT_ID), is(0L));
            });
        }
    }

    // 校验：现货挂单冻结后再下期货单，期货保证金校验必须按 (accounts - exchangeLocked) 算。
    // 覆盖 RiskEngine#canPlaceMarginOrder 修复，对称于 testPlaceExchangeWhenMarginExist。
    @Test
    public void testPlaceMarginOrderBlockedBySpotLock() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT_SPOT);
            container.addSymbol(BNB_USDT);

            long markPrice = 500 * BNB_USDT.getQuoteScaleK();
            container.initMarkPrice(BNB_USDT.symbolId, markPrice);

            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);

            // 现货 BID 1 BNB @ 500 USDT → 冻结 500 USDT
            long size = 1 * BNB_USDT_SPOT.getBaseScaleK();
            long price = 500 * BNB_USDT_SPOT.getQuoteScaleK();
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(10001L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(price).reservePrice(price).size(size).build(),
                    CommandResultCode.SUCCESS);

            // 期货 BID：名义价值 1 BNB * 501 USDT，按 75x leverage 需要的保证金 ≈ 501/75 ≈ 6.68 USDT
            // 名义价值(501) + openLoss(price 501 vs mark 500 → 1) 超 free=500 → NSF
            long futuresPrice501 = 501 * BNB_USDT.getQuoteScaleK();
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(20001L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(futuresPrice501).reservePrice(futuresPrice501)
                    .size(1 * BNB_USDT.getBaseScaleK())
                    .marginMode(MarginMode.CROSS).build(),
                    CommandResultCode.RISK_NSF);

            // 充入 2 USDT 后 free=502 ≥ 名义价值 501 + openLoss 1 → 成功
            container.submitCommandSync(ApiAdjustUserBalance.builder()
                    .uid(UID_1).transactionId(container.getRandomTransactionId())
                    .amount(2 * USDT.getCurrencyScaleK()).currency(USDT_ID).build(),
                    CommandResultCode.SUCCESS);

            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(20002L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(futuresPrice501).reservePrice(futuresPrice501)
                    .size(1 * BNB_USDT.getBaseScaleK())
                    .marginMode(MarginMode.CROSS).build(),
                    CommandResultCode.SUCCESS);
        }
    }

    // 校验：现货挂单冻结后做 ISOLATED MARGIN_ADJUSTMENT (期货逐仓加保证金)，
    // 加保证金额必须 ≤ (accounts - exchangeLocked + freeFuturesMargin)，
    // 否则相当于把现货冻结的钱挪走。覆盖 RiskEngine#MARGIN_ADJUSTMENT ISOLATED 修复。
    @Test
    public void testIsolatedMarginAdjustBlockedBySpotLock() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BTC);
            container.addCurrency(USDT);
            container.addSymbol(BTC_USDT);
            container.addSymbol(BNB_USDT_SPOT);
            container.addCurrency(BNB);

            long markPrice = 10_000 * BTC_USDT.getQuoteScaleK();
            container.initMarkPrice(BTC_USDT.symbolId, markPrice);

            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);
            container.createUserWithMoney(UID_2, USDT_ID, 100_000_000L * USDT.getCurrencyScaleK());

            // UID_2 当对手方挂 ASK 提供流动性
            long openSize = 1 * BTC_USDT.getBaseScaleK();
            long openPrice = 10_000 * BTC_USDT.getQuoteScaleK();
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2).orderId(30001L)
                    .symbol(BTC_USDT.symbolId)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(openPrice).reservePrice(openPrice).size(openSize)
                    .marginMode(MarginMode.CROSS).build(),
                    CommandResultCode.SUCCESS);

            // UID_1 开 ISOLATED BTC 多仓 10x（按 1 BTC * 10_000 USDT / 10x ≈ 1000 USDT margin，
            // 这里只开 1/10 size 控制 margin 占用，避免 spot lock 也参与的初始 NSF）
            long openSizeSmall = openSize / 10;
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(30002L)
                    .symbol(BTC_USDT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(openPrice).reservePrice(openPrice).size(openSizeSmall)
                    .marginMode(MarginMode.ISOLATED).leverage(10).build(),
                    CommandResultCode.SUCCESS);

            // 现货冻结一部分 USDT
            long spotSize = 1 * BNB_USDT_SPOT.getBaseScaleK();
            long spotPrice = 500 * BNB_USDT_SPOT.getQuoteScaleK();
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(30003L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(spotPrice).reservePrice(spotPrice).size(spotSize).build(),
                    CommandResultCode.SUCCESS);

            long spotLock = 500 * USDT.getCurrencyScaleK();
            container.validateUserState(UID_1, report -> {
                assertThat(report.getExchangeLocked().get(USDT_ID), is(spotLock));
            });

            // 尝试 isolated margin adjustment 加 free + 1 USDT 进 BTC 仓位 → NSF
            // free = accounts - spotLock - (BTC仓位已占的 isolated margin)
            // 直接试加 (accounts - spotLock + 1) 这个上限就够触发 NSF（freeFuturesMargin 在该路径只算同 currency 期货 PnL，
            // BTC isolated 仓位 PnL=0 时 freeFuturesMargin 可视为 0）
            long tooMuchMargin = usdtDeposit - spotLock + 1;
            container.submitCommandSync(ApiAdjustMargin.builder()
                    .uid(UID_1).transactionId(container.getRandomTransactionId())
                    .symbol(BTC_USDT.symbolId).action(OrderAction.BID)
                    .currency(USDT_ID).marginMode(MarginMode.ISOLATED)
                    .amount(tooMuchMargin).build(),
                    CommandResultCode.RISK_NSF);
        }
    }

    // 生命周期：现货 BID 部分成交时，exchangeLocked 按成交比例释放，accounts 按实际成交净额扣减。
    // 校验 maker 视角的"按比例释放"语义（RiskEngine.handleMatcherEventsExchangeSell:1746-1779）。
    @Test
    public void testSpotLockPartialReleaseOnPartialFill() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT_SPOT);

            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            long bnbDeposit = 2 * BNB.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);
            container.createUserWithMoney(UID_2, BNB_ID, bnbDeposit);

            // UID_1 BID 1 BNB @ 500 USDT，初始 lock 500 USDT
            long fullSize = 1 * BNB_USDT_SPOT.getBaseScaleK();
            long price = 500 * BNB_USDT_SPOT.getQuoteScaleK();
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(10001L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(price).reservePrice(price).size(fullSize).build(),
                    CommandResultCode.SUCCESS);

            long fullLock = 500 * USDT.getCurrencyScaleK();
            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit));
                assertThat(report.getExchangeLocked().get(USDT_ID), is(fullLock));
            });

            // UID_2 ASK 0.4 BNB @ 500 USDT，部分撮合 UID_1 的挂单
            long partialSize = (long) (0.4 * BNB_USDT_SPOT.getBaseScaleK());
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2).orderId(10002L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.ASK).orderType(OrderType.IOC)
                    .price(price).size(partialSize).build(),
                    CommandResultCode.SUCCESS);

            // UID_1 (maker BID) 部分成交后：lock 释放 0.4×500=200 USDT，accounts 扣 200 USDT，得 0.4 BNB
            long matchedQuote = 200 * USDT.getCurrencyScaleK();
            long matchedBase = (long) (0.4 * BNB.getCurrencyScaleK());
            long remainingLock = fullLock - matchedQuote;
            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit - matchedQuote));
                assertThat(report.getExchangeLocked().get(USDT_ID), is(remainingLock));
                assertThat(report.getAccounts().get(BNB_ID), is(matchedBase));
                assertThat(available(report, USDT_ID), is(usdtDeposit - matchedQuote - remainingLock));
            });
            // 全局守恒：lock 进守恒等式
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // 生命周期：现货 cancel 后 exchangeLocked 必须归零、accounts 不动（下单时本来就没扣）。
    // 校验 RiskEngine.handleMatcherRejectReduceEventExchange:1670-1700。
    @Test
    public void testSpotLockFullReleaseOnCancel() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT_SPOT);

            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);

            long size = 1 * BNB_USDT_SPOT.getBaseScaleK();
            long price = 500 * BNB_USDT_SPOT.getQuoteScaleK();
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(10001L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(price).reservePrice(price).size(size).build(),
                    CommandResultCode.SUCCESS);

            long lock = 500 * USDT.getCurrencyScaleK();
            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit));
                assertThat(report.getExchangeLocked().get(USDT_ID), is(lock));
            });

            container.submitCommandSync(ApiCancelOrder.builder()
                    .uid(UID_1).orderId(10001L).symbol(BNB_USDT_SPOT.symbolId).build(),
                    CommandResultCode.SUCCESS);

            // cancel 后 lock 归零，accounts 还原完整 free（下单时本就没扣 accounts）
            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit));
                assertThat(report.getExchangeLocked().get(USDT_ID), is(0L));
                assertThat(available(report, USDT_ID), is(usdtDeposit));
            });
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // 生命周期：同一 currency 多个未成交挂单时 exchangeLocked 累加，cancel 其中一个只释放对应额度。
    @Test
    public void testSpotLockAccumulatesAcrossMultipleOrders() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT_SPOT);

            long usdtDeposit = 2_000 * USDT.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);

            // 单 1: BID 1 BNB @ 500 USDT，lock 500
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(10001L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(500 * BNB_USDT_SPOT.getQuoteScaleK())
                    .reservePrice(500 * BNB_USDT_SPOT.getQuoteScaleK())
                    .size(1 * BNB_USDT_SPOT.getBaseScaleK()).build(),
                    CommandResultCode.SUCCESS);

            // 单 2: BID 0.5 BNB @ 400 USDT，lock 200
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(10002L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(400 * BNB_USDT_SPOT.getQuoteScaleK())
                    .reservePrice(400 * BNB_USDT_SPOT.getQuoteScaleK())
                    .size((long) (0.5 * BNB_USDT_SPOT.getBaseScaleK())).build(),
                    CommandResultCode.SUCCESS);

            long totalLock = (500 + 200) * USDT.getCurrencyScaleK();
            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit));
                assertThat(report.getExchangeLocked().get(USDT_ID), is(totalLock));
                assertThat(available(report, USDT_ID), is(usdtDeposit - totalLock));
            });

            // cancel 单 2 → 只释放 200，单 1 的 500 仍冻结
            container.submitCommandSync(ApiCancelOrder.builder()
                    .uid(UID_1).orderId(10002L).symbol(BNB_USDT_SPOT.symbolId).build(),
                    CommandResultCode.SUCCESS);

            long remaining = 500 * USDT.getCurrencyScaleK();
            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit));
                assertThat(report.getExchangeLocked().get(USDT_ID), is(remaining));
                assertThat(available(report, USDT_ID), is(usdtDeposit - remaining));
            });
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // 校验：spot-only 部署（marginTradingMode=DISABLED）下提现仍必须扣 exchangeLocked。
    // 防止 RiskEngine#BALANCE_ADJUSTMENT 的 spotLocked 守卫被 cfgMarginTradingEnabled 包裹绕过。
    @Test
    public void testWithdrawBlockedBySpotLockOnSpotOnlyMode() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.createSpotOnly(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT_SPOT);

            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);

            // 现货 BID 1 BNB @ 500 USDT → 冻结 500 USDT
            long size = 1 * BNB_USDT_SPOT.getBaseScaleK();
            long price = 500 * BNB_USDT_SPOT.getQuoteScaleK();
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(10001L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(price).reservePrice(price).size(size).build(),
                    CommandResultCode.SUCCESS);

            long expectedLock = 500 * USDT.getCurrencyScaleK();
            long freeAfterLock = usdtDeposit - expectedLock;

            // 提现 free + 1 → NSF（关键：margin trading 关闭也必须守住 spot 冻结）
            container.submitCommandSync(ApiAdjustUserBalance.builder()
                    .uid(UID_1).transactionId(container.getRandomTransactionId())
                    .amount(-(freeAfterLock + 1)).currency(USDT_ID).build(),
                    CommandResultCode.RISK_NSF);

            // 提现 free → SUCCESS
            container.submitCommandSync(ApiAdjustUserBalance.builder()
                    .uid(UID_1).transactionId(container.getRandomTransactionId())
                    .amount(-freeAfterLock).currency(USDT_ID).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(expectedLock));
                assertThat(report.getExchangeLocked().get(USDT_ID), is(expectedLock));
                assertThat(available(report, USDT_ID), is(0L));
            });
        }
    }

    // BUDGET 单 lock 释放：FOK_BUDGET 全成交后 exchangeLocked 必须归零（不残留）。
    @Test
    public void testFokBudgetFullFillReleasesExchangeLocked() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT_SPOT);

            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            long bnbDeposit = 5 * BNB.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);
            container.createUserWithMoney(UID_2, BNB_ID, bnbDeposit);

            // Maker: ASK 1 BNB @ 200 USDT
            long size = 1 * BNB_USDT_SPOT.getBaseScaleK();
            long unitPrice = 200 * BNB_USDT_SPOT.getQuoteScaleK();
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2).orderId(20001L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(unitPrice).reservePrice(unitPrice).size(size).build(),
                    CommandResultCode.SUCCESS);

            // Taker FOK_BUDGET：cmd.price = 总预算 (sizePrice 量级) = size × unitPrice
            long budget = size * unitPrice;
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(20002L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.FOK_BUDGET)
                    .price(budget).reservePrice(budget).size(size).build(),
                    CommandResultCode.SUCCESS);

            long actualPaid = 200 * USDT.getCurrencyScaleK();
            long actualBaseReceived = 1 * BNB.getCurrencyScaleK();
            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit - actualPaid));
                assertThat(report.getExchangeLocked().get(USDT_ID), is(0L));
                assertThat(report.getAccounts().get(BNB_ID), is(actualBaseReceived));
                assertTrue(report.fetchIndexedOrders().isEmpty());
            });
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // BUDGET 单 lock 释放：FOK_BUDGET 不能 fill 整体被拒，exchangeLocked 必须完全释放。
    @Test
    public void testFokBudgetFullRejectReleasesExchangeLocked() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT_SPOT);

            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            long bnbDeposit = 5 * BNB.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);
            container.createUserWithMoney(UID_2, BNB_ID, bnbDeposit);

            // Maker: ASK 1 BNB @ 250 USDT（高于 taker budget 单价 → FOK 无法 fill）
            long makerSize = 1 * BNB_USDT_SPOT.getBaseScaleK();
            long makerPrice = 250 * BNB_USDT_SPOT.getQuoteScaleK();
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2).orderId(21001L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(makerPrice).reservePrice(makerPrice).size(makerSize).build(),
                    CommandResultCode.SUCCESS);

            // Taker FOK_BUDGET：要 1 BNB，budget 只够 200 USDT/BNB 价位 → FOK 整体拒绝
            long takerSize = 1 * BNB_USDT_SPOT.getBaseScaleK();
            long takerUnitPrice = 200 * BNB_USDT_SPOT.getQuoteScaleK();
            long budget = takerSize * takerUnitPrice;
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(21002L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.FOK_BUDGET)
                    .price(budget).reservePrice(budget).size(takerSize).build(),
                    CommandResultCode.SUCCESS); // FOK 命令本身成功，但内部 REJECT

            container.validateUserState(UID_1, report -> {
                assertThat("整体拒绝后 accounts 不变", report.getAccounts().get(USDT_ID), is(usdtDeposit));
                assertThat("整体拒绝后 exchangeLocked 必须归零", report.getExchangeLocked().get(USDT_ID), is(0L));
                assertThat("base 没有任何变化", report.getAccounts().get(BNB_ID), is(0L));
                assertTrue(report.fetchIndexedOrders().isEmpty(), "订单不应留下");
            });
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // BUDGET 单 lock 释放：IOC_BUDGET 部分成交+剩余拒绝，exchangeLocked 必须完全释放。
    // 这是 audit 报告里最复杂的路径：REJECT handler 释放 0，BUY handler 释放整笔预算。
    @Test
    public void testIocBudgetPartialFillReleasesExchangeLocked() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT_SPOT);

            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            long bnbDeposit = 5 * BNB.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);
            container.createUserWithMoney(UID_2, BNB_ID, bnbDeposit);

            // Maker: ASK 仅 0.4 BNB @ 200 USDT
            long makerSize = (long) (0.4 * BNB_USDT_SPOT.getBaseScaleK());
            long unitPrice = 200 * BNB_USDT_SPOT.getQuoteScaleK();
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2).orderId(22001L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(unitPrice).reservePrice(unitPrice).size(makerSize).build(),
                    CommandResultCode.SUCCESS);

            // Taker IOC_BUDGET：想要 1 BNB，预算 1 BNB × 200 = 200 USDT → 实际成交 0.4 BNB，剩 0.6 拒
            long takerSize = 1 * BNB_USDT_SPOT.getBaseScaleK();
            long budget = takerSize * unitPrice;
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(22002L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.IOC_BUDGET)
                    .price(budget).reservePrice(budget).size(takerSize).build(),
                    CommandResultCode.SUCCESS);

            long actualPaid = 80 * USDT.getCurrencyScaleK(); // 0.4 × 200 = 80 USDT
            long actualBaseReceived = (long) (0.4 * BNB.getCurrencyScaleK());
            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit - actualPaid));
                assertThat("部分成交后 exchangeLocked 必须完全释放（已成交 + 剩余拒绝 共归零）",
                        report.getExchangeLocked().get(USDT_ID), is(0L));
                assertThat(report.getAccounts().get(BNB_ID), is(actualBaseReceived));
                assertTrue(report.fetchIndexedOrders().isEmpty());
            });
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // BUDGET 单 lock 释放：IOC_BUDGET 撞空 orderbook，完全无法成交。
    // 这条走 handleMatcherRejectReduceEventExchange 的 ev.nextEvent==null 路径，
    // 按 (cmd.size, cmd.price) 释放整笔预算锁定。
    @Test
    public void testIocBudgetFullRejectReleasesExchangeLocked() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT_SPOT);

            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);

            // 无 maker → orderbook 空，IOC_BUDGET 整笔 REJECT (ev.nextEvent == null)
            long takerSize = 1 * BNB_USDT_SPOT.getBaseScaleK();
            long takerUnitPrice = 200 * BNB_USDT_SPOT.getQuoteScaleK();
            long budget = takerSize * takerUnitPrice;
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(23002L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.IOC_BUDGET)
                    .price(budget).reservePrice(budget).size(takerSize).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, report -> {
                assertThat("完全拒绝后 accounts 不变", report.getAccounts().get(USDT_ID), is(usdtDeposit));
                assertThat("完全拒绝后 exchangeLocked 必须归零 (走 ev.nextEvent==null 路径)",
                        report.getExchangeLocked().get(USDT_ID), is(0L));
                assertThat(report.getAccounts().get(BNB_ID), is(0L));
                assertTrue(report.fetchIndexedOrders().isEmpty(), "订单不应留下");
            });
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // 精度行为：lock 一次性 floor，release 每 ev 独立 floor，floor 亚加→残留。
    //
    // SYMBOLSPECFEE_XBT_LTC: fixed-fee, takerFee=1900, baseScaleK=1e6, quoteScaleK=1e4
    //   sizePriceScale=1e10, currency digit=8 → currencyScaleK=1e8, X=100
    //   reservePrice=1933, 每 lot 总额 = 3833
    //   单次 lock(size=4): floor(15332/100) = 153
    //   逐 ev release × 4 (size=1): floor(3833/100) × 4 = 152
    //   残留 = 1 LTC currency 单位 留在 maker.exchangeLocked
    //
    // 为何不修：全局守恒不受影响。`accountBalances` bucket 在聚合时 `= raw_accounts - exchangeLocked`
    //   (TotalCurrencyBalanceReportQuery:99-103)，所以 +1 在 exchangeLocked 同时 -1 给 accountBalances，
    //   自动抵消。维 `available = accounts - exchangeLocked` 不变量也成立 — 用户看到的
    //   可支配 free 正确，只是 exchangeLocked 字段会展示极小残留值。
    //
    // 长期方案讨论：要彻底归零需要把"漂移 1 LTC"注入 fees bucket（涉及修改 fees 计算逻辑），
    //   架构改造较大，超出 当前重构范围。当前残留是 conservation-correct 的会计 artifact。
    @Test
    public void testMakerBidExchangeLockedPrecisionDriftOnPartialFills() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addSymbol(SYMBOLSPECFEE_XBT_LTC);
            container.addCurrency(SYMBOLSPECFEE_XBT_LTC.baseCurrency, 8);
            container.addCurrency(SYMBOLSPECFEE_XBT_LTC.quoteCurrency, 8);

            long ltcAmount = 100_000L;
            long xbtAmount = 100_000_000L * 100_000_000L;
            container.createUserWithMoney(UID_1, CURRENECY_LTC, ltcAmount);
            container.createUserWithMoney(UID_2, CURRENECY_XBT, xbtAmount);

            long price = 1933L;
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(40001L)
                    .symbol(SYMBOLSPECFEE_XBT_LTC.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(price).reservePrice(price).size(4L).build(),
                    CommandResultCode.SUCCESS);

            long expectedInitialLock = 153L;
            container.validateUserState(UID_1, report -> {
                assertThat(report.getExchangeLocked().get(CURRENECY_LTC), is(expectedInitialLock));
            });

            // 4 个独立 ASK cmd 各 size=1 撮 BID → maker 收到 4 个 trade events
            for (int i = 0; i < 4; i++) {
                container.submitCommandSync(ApiPlaceOrder.builder()
                        .uid(UID_2).orderId(40100L + i)
                        .symbol(SYMBOLSPECFEE_XBT_LTC.symbolId)
                        .action(OrderAction.ASK).orderType(OrderType.IOC)
                        .price(price).size(1L).build(),
                        CommandResultCode.SUCCESS);
            }

            container.validateUserState(UID_1, report -> {
                assertThat("BID 全部成交，无挂单残量",
                        report.fetchIndexedOrders().isEmpty(), is(true));
                assertThat("精度漂移：4 × floor(3833/100)=152，初始 lock=153，残留 1 LTC 单位",
                        report.getExchangeLocked().get(CURRENECY_LTC), is(1L));
            });
            // 全局守恒仍成立：accountBalances bucket = raw_accounts - exchangeLocked，
            // 残留 1 同时贡献 +1 给 exchangeLocked 和 -1 给 accountBalances，互相抵消
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // 精度 dust 流向：用户在部分成交后 cancel 残量，dust 是否回到 accounts？
    //
    // 答案：**不会**。cancel 公式按 unmatched 部分 floor 算（与 trade 路径同公式），
    // 残量释放 = floor(2×3833/100) = 76，但 exchangeLocked 当时是 77（含已成交 dust 1）。
    // 释放 76 后，exchangeLocked 仍 = 1。dust 仍然卡在 exchangeLocked，
    // accounts 不会因 cancel 而获得 1 LTC 单位的"返还"。
    //
    // 用户的 available = accounts - exchangeLocked 比"理想值"少 1 dust unit（永久）。
    // 唯一"消化"dust 的途径：再下新单时撮合的 floor 漂移恰好抵消（不保证）。
    @Test
    public void testMakerBidExchangeLockedDustStaysAfterCancelOfRemainder() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addSymbol(SYMBOLSPECFEE_XBT_LTC);
            container.addCurrency(SYMBOLSPECFEE_XBT_LTC.baseCurrency, 8);
            container.addCurrency(SYMBOLSPECFEE_XBT_LTC.quoteCurrency, 8);

            long ltcAmount = 100_000L;
            long xbtAmount = 100_000_000L * 100_000_000L;
            container.createUserWithMoney(UID_1, CURRENECY_LTC, ltcAmount);
            container.createUserWithMoney(UID_2, CURRENECY_XBT, xbtAmount);

            long price = 1933L;
            long bidOrderId = 50001L;
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(bidOrderId)
                    .symbol(SYMBOLSPECFEE_XBT_LTC.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(price).reservePrice(price).size(4L).build(),
                    CommandResultCode.SUCCESS);

            // 部分成交 2 lots（2 个独立 ASK cmd × size=1）
            for (int i = 0; i < 2; i++) {
                container.submitCommandSync(ApiPlaceOrder.builder()
                        .uid(UID_2).orderId(50100L + i)
                        .symbol(SYMBOLSPECFEE_XBT_LTC.symbolId)
                        .action(OrderAction.ASK).orderType(OrderType.IOC)
                        .price(price).size(1L).build(),
                        CommandResultCode.SUCCESS);
            }

            // partial 后 exchangeLocked 应当为 77 (=153 - 38×2)，含 dust 1
            container.validateUserState(UID_1, report -> {
                assertThat(report.getExchangeLocked().get(CURRENECY_LTC), is(77L));
            });
            long accountsBeforeCancel = ltcAmount - 26L * 2; // 99948
            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(CURRENECY_LTC), is(accountsBeforeCancel));
            });

            // Cancel 残量
            container.submitCommandSync(ApiCancelOrder.builder()
                    .uid(UID_1).orderId(bidOrderId)
                    .symbol(SYMBOLSPECFEE_XBT_LTC.symbolId).build(),
                    CommandResultCode.SUCCESS);

            // Cancel 释放 = floor(unmatched 2 × 3833 / 100) = 76 → exchangeLocked: 77 - 76 = 1
            // dust 仍在 exchangeLocked，accounts 不动
            container.validateUserState(UID_1, report -> {
                assertThat("cancel 释放按 unmatched 算 = 76，dust 1 单位仍留在 exchangeLocked",
                        report.getExchangeLocked().get(CURRENECY_LTC), is(1L));
                assertThat("cancel 不影响 accounts（已支付的就是已支付的，不会因 cancel 退回 dust）",
                        report.getAccounts().get(CURRENECY_LTC), is(accountsBeforeCancel));
                assertThat("available = accounts - exchangeLocked 比理想值少 1 dust unit",
                        available(report, CURRENECY_LTC), is(accountsBeforeCancel - 1L));
                assertTrue(report.fetchIndexedOrders().isEmpty(), "BID 应已被 cancel 清除");
            });
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // 精度：完全没成交直接 cancel —— dust 不产生，exchangeLocked 精确归零。
    @Test
    public void testMakerBidCancelWithoutAnyFillFullyReleasesExchangeLocked() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addSymbol(SYMBOLSPECFEE_XBT_LTC);
            container.addCurrency(SYMBOLSPECFEE_XBT_LTC.baseCurrency, 8);
            container.addCurrency(SYMBOLSPECFEE_XBT_LTC.quoteCurrency, 8);

            long ltcAmount = 100_000L;
            container.createUserWithMoney(UID_1, CURRENECY_LTC, ltcAmount);

            long price = 1933L;
            long bidOrderId = 60001L;
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(bidOrderId)
                    .symbol(SYMBOLSPECFEE_XBT_LTC.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(price).reservePrice(price).size(4L).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, report -> {
                assertThat(report.getExchangeLocked().get(CURRENECY_LTC), is(153L));
            });

            // 直接 cancel，没有任何成交
            container.submitCommandSync(ApiCancelOrder.builder()
                    .uid(UID_1).orderId(bidOrderId)
                    .symbol(SYMBOLSPECFEE_XBT_LTC.symbolId).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, report -> {
                assertThat("cancel 释放 = floor(4×3833/100) = 153 = lock，精确归零",
                        report.getExchangeLocked().get(CURRENECY_LTC), is(0L));
                assertThat(report.getAccounts().get(CURRENECY_LTC), is(ltcAmount));
                assertThat(available(report, CURRENECY_LTC), is(ltcAmount));
            });
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // SUSPEND 时 dust sweep：
    // 用户 cancel 所有挂单 + withdraw 所有 free 后，accounts=dust, exchangeLocked=dust。
    // SUSPEND 触发 sweep：accounts -= dust, exchangeLocked = 0, fees += dust。守恒成立。
    @Test
    public void testSuspendSweepsDustToFees() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addSymbol(SYMBOLSPECFEE_XBT_LTC);
            container.addCurrency(SYMBOLSPECFEE_XBT_LTC.baseCurrency, 8);
            container.addCurrency(SYMBOLSPECFEE_XBT_LTC.quoteCurrency, 8);

            long ltcAmount = 100_000L;
            long xbtAmount = 100_000_000L * 100_000_000L;
            container.createUserWithMoney(UID_1, CURRENECY_LTC, ltcAmount);
            container.createUserWithMoney(UID_2, CURRENECY_XBT, xbtAmount);

            long price = 1933L;
            // Maker BID size=4 → 4 个 size=1 ASK 撮合 → 残留 1 LTC dust
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(70001L)
                    .symbol(SYMBOLSPECFEE_XBT_LTC.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(price).reservePrice(price).size(4L).build(),
                    CommandResultCode.SUCCESS);
            for (int i = 0; i < 4; i++) {
                container.submitCommandSync(ApiPlaceOrder.builder()
                        .uid(UID_2).orderId(70100L + i)
                        .symbol(SYMBOLSPECFEE_XBT_LTC.symbolId)
                        .action(OrderAction.ASK).orderType(OrderType.IOC)
                        .price(price).size(1L).build(),
                        CommandResultCode.SUCCESS);
            }
            // 撮合完: accounts[LTC]=99896, exchangeLocked[LTC]=1, accounts[XBT]=400
            container.validateUserState(UID_1, report -> {
                assertThat(report.getExchangeLocked().get(CURRENECY_LTC), is(1L));
            });

            // 提现所有 LTC free (= 99895) → accounts[LTC]=1, exchangeLocked[LTC]=1
            container.submitCommandSync(ApiAdjustUserBalance.builder()
                    .uid(UID_1).transactionId(container.getRandomTransactionId())
                    .amount(-(99_895L)).currency(CURRENECY_LTC).build(),
                    CommandResultCode.SUCCESS);
            // 提现所有 XBT (400) → accounts[XBT]=0
            container.submitCommandSync(ApiAdjustUserBalance.builder()
                    .uid(UID_1).transactionId(container.getRandomTransactionId())
                    .amount(-400L).currency(CURRENECY_XBT).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_1, report -> {
                assertThat("LTC 已 withdraw 完 free，剩 dust 1", report.getAccounts().get(CURRENECY_LTC), is(1L));
                assertThat("LTC dust 仍在 lock", report.getExchangeLocked().get(CURRENECY_LTC), is(1L));
                assertThat("XBT 已全提", report.getAccounts().get(CURRENECY_XBT), is(0L));
            });

            // SUSPEND 触发 dust sweep
            container.submitCommandSync(ApiSuspendUser.builder().uid(UID_1).build(),
                    CommandResultCode.SUCCESS);

            // user 已被 remove；用 totalBalanceReport 验证守恒和 fees 涨 1
            assertThat("SUSPEND 后全局守恒仍成立",
                    container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
            // 撮合产生 fees = 26×4=104；sweep 多 1 → 105
            assertThat("dust 1 已 sweep 到 fees bucket",
                    container.totalBalanceReport().getFees().get(CURRENECY_LTC), is(105L));
        }
    }

    // 用户有真实 accounts（不只是 dust）时，sweep 守卫不通过，SUSPEND 仍被原校验挡。
    @Test
    public void testSuspendDoesNotSweepWhenUserHasRealAccounts() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT_SPOT);

            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);

            // 现货下单冻结一部分，user 仍有 free
            long size = 1 * BNB_USDT_SPOT.getBaseScaleK();
            long price = 500 * BNB_USDT_SPOT.getQuoteScaleK();
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(80001L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(price).reservePrice(price).size(size).build(),
                    CommandResultCode.SUCCESS);

            // accounts=1000, exchangeLocked=500：accounts != exchangeLocked，不是 dust-only 状态
            // SUSPEND 必须被守卫拒绝（accounts 非 0 触发 NON_EMPTY_ACCOUNTS）
            container.submitCommandSync(ApiSuspendUser.builder().uid(UID_1).build(),
                    CommandResultCode.USER_MGMT_USER_NOT_SUSPENDABLE_NON_EMPTY_ACCOUNTS);

            // sweep 也没发生：exchangeLocked 没动，accounts 没动
            container.validateUserState(UID_1, report -> {
                assertThat(report.getAccounts().get(USDT_ID), is(usdtDeposit));
                assertThat(report.getExchangeLocked().get(USDT_ID), is(500L * USDT.getCurrencyScaleK()));
            });
        }
    }

    // 完全干净的账户（accounts 全 0、exchangeLocked 全 0）SUSPEND 直接通过，sweep no-op。
    @Test
    public void testSuspendCleanAccountDoesNotTriggerSweep() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(USDT);
            // 创建用户但不充值
            container.submitCommandSync(ApiAddUser.builder().uid(UID_1).build(), CommandResultCode.SUCCESS);

            // 直接 SUSPEND 应通过；fees 不应有任何变化
            long feesBefore = container.totalBalanceReport().getFees().get(USDT_ID);
            container.submitCommandSync(ApiSuspendUser.builder().uid(UID_1).build(),
                    CommandResultCode.SUCCESS);

            assertThat("fees bucket 没有 dust sweep 进入",
                    container.totalBalanceReport().getFees().get(USDT_ID), is(feesBefore));
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // SingleUserReportQuery.process() 计算 CROSS 强平价格时必须用 accounts - exchangeLocked，
    // 否则有现货挂单的用户会拿到偏低（偏乐观）的强平价，与 LiquidationEngine 实际触发口径不一致。
    @Test
    public void testCrossLiquidationPriceAccountsForSpotLock() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT);      // 期货
            container.addSymbol(BNB_USDT_SPOT); // 现货

            long markPrice = 500 * BNB_USDT.getQuoteScaleK();
            container.initMarkPrice(BNB_USDT.symbolId, markPrice);

            // UID_1 仅存 300 USDT + leverage=10：openPriceSum(500 USDT) > balance(300 USDT)，强平价才为正。
            // 若存 10,000 USDT，balance 20× 大于仓位成本，liq price 为负值（数学上不会被强平），返回 -1。
            long uid1Deposit = 300 * USDT.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, uid1Deposit);
            container.createUserWithMoney(UID_2, USDT_ID, 10_000 * USDT.getCurrencyScaleK() * 100);

            // UID_2 提供 ASK 流动性
            long futSize = 1 * BNB_USDT.getBaseScaleK();
            long futPrice = 500 * BNB_USDT.getQuoteScaleK();
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2).orderId(50001L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(futPrice).reservePrice(futPrice).size(futSize)
                    .marginMode(MarginMode.CROSS).build(),
                    CommandResultCode.SUCCESS);

            // UID_1 CROSS BID 开多仓，leverage=10：initMargin≈50 USDT，300 USDT 足够开仓
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(50002L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.IOC)
                    .price(futPrice).reservePrice(futPrice).size(futSize)
                    .marginMode(MarginMode.CROSS).leverage(10).build(),
                    CommandResultCode.SUCCESS);

            // 无现货挂单时的强平价格
            long[] liqPriceNoSpot = new long[1];
            container.validateUserState(UID_1, report -> {
                assertThat("CROSS 仓位已开", report.getPositions().containsKey(BNB_USDT.symbolId), is(true));
                assertThat("无现货挂单冻结", report.getExchangeLocked().get(USDT_ID), is(0L));
                liqPriceNoSpot[0] = report.getPositions().get(BNB_USDT.symbolId).get(0).liquidationPrice;
                assertTrue(liqPriceNoSpot[0] > 0, "强平价格必须为正值");
            });

            // UID_1 现货挂买单，冻结 100 USDT（无对手方，不会成交）
            long spotLock = 100 * USDT.getCurrencyScaleK();
            long spotPrice = 50 * BNB_USDT_SPOT.getQuoteScaleK();  // 低于市价，不会成交
            long spotSize = 2 * BNB_USDT_SPOT.getBaseScaleK();     // 2 BNB @ 50 = 100 USDT
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(50003L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(spotPrice).reservePrice(spotPrice).size(spotSize).build(),
                    CommandResultCode.SUCCESS);

            // 有现货挂单时的强平价格：必须 > 无现货挂单时（有效余额变少，强平触发价更高）
            container.validateUserState(UID_1, report -> {
                assertThat("现货 lock 已冻结", report.getExchangeLocked().get(USDT_ID), is(spotLock));
                long liqPriceWithSpot = report.getPositions().get(BNB_USDT.symbolId).get(0).liquidationPrice;
                assertTrue(liqPriceWithSpot > liqPriceNoSpot[0],
                        "有现货挂单冻结时强平价(" + liqPriceWithSpot + ")应高于无冻结时(" + liqPriceNoSpot[0] + ")；" +
                        "若相等说明 SingleUserReportQuery 未从 accounts 中扣减 exchangeLocked");
            });

            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

    // 跨条线独立性：期货下单/成交不应影响现货 exchangeLocked。
    // 这个测试也副带验证产品决策——期货成交流程不会去 cancel 用户的现货挂单。
    @Test
    public void testFuturesFillDoesNotAffectSpotLock() throws Exception {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(PerformanceConfiguration.DEFAULT)) {
            container.addCurrency(BNB);
            container.addCurrency(USDT);
            container.addSymbol(BNB_USDT);          // 期货
            container.addSymbol(BNB_USDT_SPOT);     // 现货

            long markPrice = 500 * BNB_USDT.getQuoteScaleK();
            container.initMarkPrice(BNB_USDT.symbolId, markPrice);

            long usdtDeposit = 1_000 * USDT.getCurrencyScaleK();
            long counterDeposit = 100_000_000L * USDT.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, USDT_ID, usdtDeposit);
            container.createUserWithMoney(UID_2, USDT_ID, counterDeposit);

            // 步骤 1：UID_1 现货 BID 1 BNB @ 100 USDT，冻结 100 USDT
            long spotPrice = 100 * BNB_USDT_SPOT.getQuoteScaleK();
            long spotSize = 1 * BNB_USDT_SPOT.getBaseScaleK();
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(40001L)
                    .symbol(BNB_USDT_SPOT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.GTC)
                    .price(spotPrice).reservePrice(spotPrice).size(spotSize).build(),
                    CommandResultCode.SUCCESS);

            long spotLock = 100 * USDT.getCurrencyScaleK();
            container.validateUserState(UID_1, report -> {
                assertThat(report.getExchangeLocked().get(USDT_ID), is(spotLock));
            });

            // 步骤 2：UID_2 当对手方挂 ASK 提供流动性
            long futSize = 1 * BNB_USDT.getBaseScaleK();
            long futPrice = 500 * BNB_USDT.getQuoteScaleK();
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_2).orderId(40002L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.ASK).orderType(OrderType.GTC)
                    .price(futPrice).reservePrice(futPrice).size(futSize)
                    .marginMode(MarginMode.CROSS).build(),
                    CommandResultCode.SUCCESS);

            // 步骤 3：UID_1 CROSS BID 撮合成交，开期货仓位
            container.submitCommandSync(ApiPlaceOrder.builder()
                    .uid(UID_1).orderId(40003L)
                    .symbol(BNB_USDT.symbolId)
                    .action(OrderAction.BID).orderType(OrderType.IOC)
                    .price(futPrice).reservePrice(futPrice).size(futSize)
                    .marginMode(MarginMode.CROSS).build(),
                    CommandResultCode.SUCCESS);

            // 关键断言：期货撮合完后，UID_1 的现货 exchangeLocked 仍为 100 USDT 不变
            container.validateUserState(UID_1, report -> {
                assertThat("期货持仓应开成", report.getPositions().size(), is(1));
                assertThat("现货 lock 不应被期货流程影响",
                        report.getExchangeLocked().get(USDT_ID), is(spotLock));
                assertThat("现货挂单仍在",
                        report.fetchIndexedOrders().containsKey(40001L), is(true));
            });
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
        }
    }

}
