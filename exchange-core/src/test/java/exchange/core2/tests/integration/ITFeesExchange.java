/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package exchange.core2.tests.integration;

import exchange.core2.core.common.*;
import exchange.core2.core.common.api.ApiCancelOrder;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.utils.CoreArithmeticUtils;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static exchange.core2.core.common.OrderType.*;
import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TODO IOC reject tests
 * TODO GTC move matching
 * TODO
 */

@Slf4j
public abstract class ITFeesExchange {

    private final long step = SYMBOLSPECFEE_XBT_LTC.quoteScaleK;
    private final long makerFee = SYMBOLSPECFEE_XBT_LTC.makerFee;
    private final long takerFee = SYMBOLSPECFEE_XBT_LTC.takerFee;

    // configuration provided by child class
    public abstract PerformanceConfiguration getPerformanceConfiguration();

    public static final CoreSymbolSpecification symbol = CoreSymbolSpecification.builder()
            .symbolId(SYMBOL_EXCHANGE_FEE)
            .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
            .baseCurrency(CURRENECY_XBT)    // base = satoshi
            .quoteCurrency(CURRENECY_LTC)   // quote = litoshi
            .baseScaleK(1_000)
            .quoteScaleK(10)
            .takerFee(1900)                 // taker fee 1900 litoshi per 1 lot
            .makerFee(700)                  // maker fee 700 litoshi per 1 lot
            .build();

    @Test
    @Timeout(10)
    public void shouldRequireTakerFees_GtcCancel() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.addSymbol(symbol);
            container.addCurrency(symbol.baseCurrency, 8);
            container.addCurrency(symbol.quoteCurrency, 8);

            // ----------------- 1 test GTC BID cancel ------------------

            // create user - 3.42B litoshi (34.2 LTC)
            final long ltcAmount = 3_420_000_000L;
            final long needAmount = 399000L * symbol.baseScaleK * symbol.quoteScaleK;
            container.createUserWithMoney(UID_2, CURRENECY_LTC, ltcAmount);

            // submit BID order for 1000 lots - should be rejected because of the fee
            final ApiPlaceOrder order203 = ApiPlaceOrder.builder().uid(UID_2).orderId(203).price(11_400).reservePrice(11_400).size(30).action(OrderAction.BID).orderType(GTC).symbol(SYMBOL_EXCHANGE_FEE).marginMode(MarginMode.ISOLATED).build();
            container.submitCommandSync(order203, CommandResultCode.RISK_NSF);

            // add fee-1 - NSF
            container.addMoneyToUser(UID_2, CURRENECY_LTC, needAmount - ltcAmount - 1);
            container.submitCommandSync(order203, CommandResultCode.RISK_NSF);

            // add 1 extra - SUCCESS
            container.addMoneyToUser(UID_2, CURRENECY_LTC, 1);
            container.submitCommandSync(order203, CommandResultCode.SUCCESS);

            // cancel bid
            container.submitCommandSync(
                    ApiCancelOrder.builder().orderId(203).uid(UID_2).symbol(SYMBOL_EXCHANGE_FEE).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_LTC), is(needAmount));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(needAmount));
            assertThat(totalBal1.getFees().get(CURRENECY_LTC), is(0L));

            // ----------------- 2 test GTC ASK cancel ------------------

            // add 100M satoshi (1 BTC)
            final long btcAmount = 100_000_000L;
            container.addMoneyToUser(UID_2, CURRENECY_XBT, btcAmount);

            // can place ASK order, no extra is fee required for lock hold
            final ApiPlaceOrder order204 = ApiPlaceOrder.builder().uid(UID_2).orderId(204).price(11_400).reservePrice(11_400).size(100).action(OrderAction.ASK).orderType(GTC).symbol(SYMBOL_EXCHANGE_FEE).marginMode(MarginMode.ISOLATED).build();
            container.submitCommandSync(order204, CommandResultCode.SUCCESS);

            // cancel ask
            container.submitCommandSync(
                    ApiCancelOrder.builder().orderId(204).uid(UID_2).symbol(SYMBOL_EXCHANGE_FEE).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(btcAmount));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // no fees collected
            TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(needAmount));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(0L));
            assertThat(totalBal2.getFees().get(CURRENECY_XBT), is(0L));
        }
    }


    @Test
    @Timeout(10)
    public void shouldProcessFees_BidGtcMaker_AskIocTakerPartial() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.addSymbol(symbol);
            CoreCurrencySpecification baseSpec = container.addCurrency(symbol.baseCurrency, 8);
            CoreCurrencySpecification quoteSpec = container.addCurrency(symbol.quoteCurrency, 8);
            final long ltcAmount = 2_000_000_000L * quoteSpec.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, CURRENECY_LTC, ltcAmount); // 200B litoshi (2,000 LTC)

            // submit an GtC order - limit BUY 1,731 lots, price 115M (11,500 x10,000 step) for each lot 1M satoshi
            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(11_500L * symbol.quoteScaleK)
                    .reservePrice(11_553L * symbol.quoteScaleK)
                    .size(1731L * symbol.baseScaleK)
                    .action(OrderAction.BID)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long makerFee = CoreArithmeticUtils.calculateAmountBidTakerFee(order101.size, order101.reservePrice, symbol);
            long expectedFundsLtc = ltcAmount - CoreArithmeticUtils.sizePriceToCurrencyScale(makerFee, symbol, quoteSpec);
            // verify order placed with correct reserve price and account balance is updated accordingly
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_LTC), is(expectedFundsLtc));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
            });

            // create second user
            final long xbtAmount = 2_000L * baseSpec.getCurrencyScaleK();
            container.createUserWithMoney(UID_2, CURRENECY_XBT, xbtAmount);

            // no fees collected
            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(xbtAmount));
            assertThat(totalBal1.getFees().get(CURRENECY_LTC), is(0L));

            // submit an IoC order - sell 2,000 lots, price 114,930K (11,493 x10,000 step)
            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(11_493L * symbol.quoteScaleK)
                    .size(2000L * symbol.baseScaleK)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.IOC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // verify buyer maker balance
            long makerFeeU1 = CoreArithmeticUtils.calculateMakerFee(order101.size, order101.price, symbol);
            long finalFeeU1 = CoreArithmeticUtils.sizePriceToCurrencyScale(makerFeeU1, symbol, quoteSpec);
            // order
            long order = CoreArithmeticUtils.sizePriceToCurrencyScale(order101.size * order101.price, symbol, quoteSpec);
            // 收到1731个xbt
            long xbtBalance = CoreArithmeticUtils.symbolToCurrencyScale(order101.size, symbol, baseSpec);
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_LTC), is(ltcAmount - order - finalFeeU1));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(xbtBalance));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // verify seller taker balance
            long takerFeeU2 = CoreArithmeticUtils.calculateTakerFee(order101.size, order101.price, symbol);
            long finalFeeU2 = CoreArithmeticUtils.sizePriceToCurrencyScale(takerFeeU2, symbol, quoteSpec);
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_LTC), is(order - finalFeeU2));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(xbtAmount - xbtBalance));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // total balance remains the same
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            final long ltcFees = finalFeeU1 + finalFeeU2;
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(xbtAmount));
        }

    }


    @Test
    @Timeout(10)
    public void shouldProcessFees_BidGtcMakerPartial_AskIocTaker() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.addSymbol(symbol);
            CoreCurrencySpecification baseSpec = container.addCurrency(symbol.baseCurrency, 8);
            CoreCurrencySpecification quoteSpec = container.addCurrency(symbol.quoteCurrency, 8);
            final long ltcAmount = 2_000_000_000L * quoteSpec.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, CURRENECY_LTC, ltcAmount); // 200B litoshi (2,000 LTC)

            // submit an GtC order - limit BUY 1,731 lots, price 115M (11,500 x10,000 step) for each lot 1M satoshi
            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(11_500L * symbol.quoteScaleK)
                    .reservePrice(11_553L * symbol.quoteScaleK)
                    .size(1731L * symbol.baseScaleK)
                    .action(OrderAction.BID)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long takerFee = CoreArithmeticUtils.calculateAmountBidTakerFee(order101.size, order101.reservePrice, symbol);
            takerFee = CoreArithmeticUtils.sizePriceToCurrencyScale(takerFee, symbol, quoteSpec);
            long expectedFundsLtc = ltcAmount - takerFee;
            // verify order placed with correct reserve price and account balance is updated accordingly
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_LTC), is(expectedFundsLtc));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
            });

            // create second user
            final long btcAmount = 2_000L * baseSpec.getCurrencyScaleK();
            container.createUserWithMoney(UID_2, CURRENECY_XBT, btcAmount);

            // no fees collected
            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
            assertThat(totalBal1.getFees().get(CURRENECY_LTC), is(0L));

            // submit an IoC order - sell 1,000 lots, price 114,930K (11,493 x10,000 step)
            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(11_493L * symbol.quoteScaleK)
                    .size(1000L * symbol.baseScaleK)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.IOC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long makerFee1 = CoreArithmeticUtils.calculateMakerFee(order102.size, order101.price, symbol);
            makerFee1 = CoreArithmeticUtils.sizePriceToCurrencyScale(makerFee1, symbol, quoteSpec);
            long takerFee1 = CoreArithmeticUtils.calculateTakerFee(order101.size - order102.size, order101.reservePrice, symbol);
            takerFee1 = CoreArithmeticUtils.sizePriceToCurrencyScale(takerFee1, symbol, quoteSpec);
            long finalMakerFee = makerFee1 + takerFee1;

            long orderSpend = order102.size * order101.price + ((order101.size - order102.size) * order101.reservePrice);
            long order = CoreArithmeticUtils.sizePriceToCurrencyScale(orderSpend, symbol, quoteSpec);
            long gainedAmountInBaseCurrency = order102.size;
            long xbtAmount = CoreArithmeticUtils.symbolToCurrencyScale(gainedAmountInBaseCurrency, symbol, baseSpec);
            // verify buyer maker balance
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_LTC), is(ltcAmount - order - finalMakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(xbtAmount));
                assertFalse(profile.fetchIndexedOrders().isEmpty());
            });

            long takerFee2 = CoreArithmeticUtils.calculateTakerFee(order102.size, order101.price, symbol);
            takerFee2 = CoreArithmeticUtils.sizePriceToCurrencyScale(takerFee2, symbol, quoteSpec);
            // verify seller taker balance
            long finalTakerFee = takerFee2;
            long order1 = CoreArithmeticUtils.sizePriceToCurrencyScale(order102.size * order101.price, symbol, quoteSpec);
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_LTC), is(order1 - finalTakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(btcAmount - xbtAmount));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // total balance remains the same
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            final long ltcFees = makerFee1 + finalTakerFee;
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
        }

    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_AskGtcMaker_BidIocTakerPartial() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.addSymbol(symbol);
            CoreCurrencySpecification baseSpec = container.addCurrency(symbol.baseCurrency, 8);
            CoreCurrencySpecification quoteSpec = container.addCurrency(symbol.quoteCurrency, 8);

            final long btcAmount = 2_000L * baseSpec.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, CURRENECY_XBT, btcAmount);

            // submit an ASK GtC order, no fees, sell 2,000 lots, price 115,000K (11,500 x10,000 step)
            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(11_500L * symbol.quoteScaleK)
                    .reservePrice(11_500L * symbol.quoteScaleK)
                    .size(2000L * symbol.baseScaleK)
                    .action(OrderAction.ASK)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long expected = btcAmount - CoreArithmeticUtils.symbolToCurrencyScale(order101.size, symbol, baseSpec);
            // verify order placed
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(expected));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
            });

            // create second user
            final long ltcAmount = 2_000_000_000L * quoteSpec.getCurrencyScaleK();
            container.createUserWithMoney(UID_2, CURRENECY_LTC, ltcAmount);

            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
            assertThat(totalBal1.getFees().get(CURRENECY_LTC), is(0L));

            // submit an IoC order - ASK 2,197 lots, price 115,210K (11,521 x10,000 step) for each lot 1M satoshi
            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(11_521L * symbol.quoteScaleK)
                    .reservePrice(11_659L * symbol.quoteScaleK)
                    .size(2197L * symbol.baseScaleK)
                    .action(OrderAction.BID)
                    .orderType(OrderType.IOC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // verify seller maker balance
            long makerFee = CoreArithmeticUtils.calculateMakerFee(order101.size, order101.price, symbol);
            makerFee = CoreArithmeticUtils.sizePriceToCurrencyScale(makerFee, symbol, quoteSpec);
            long order = CoreArithmeticUtils.sizePriceToCurrencyScale(order101.size * order101.price, symbol, quoteSpec);
            long finalMakerFee = makerFee;
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertThat(profile.getAccounts().get(CURRENECY_LTC), is(order - finalMakerFee));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // verify buyer taker balance
            long takerFee1 = CoreArithmeticUtils.calculateTakerFee(order101.size, order101.price, symbol);
            takerFee1 = CoreArithmeticUtils.sizePriceToCurrencyScale(takerFee1, symbol, quoteSpec);
            long takerFee2 = CoreArithmeticUtils.calculateTakerFee(order102.size - order101.size, order102.reservePrice, symbol);
            takerFee2 = CoreArithmeticUtils.sizePriceToCurrencyScale(takerFee2, symbol, quoteSpec);
            long finalTakerFee = takerFee1 + takerFee2;

            long orderSpend = order101.size * order101.price + (order102.size - order101.size) * order102.reservePrice;
            long order2 = CoreArithmeticUtils.sizePriceToCurrencyScale(orderSpend, symbol, quoteSpec);
            long xbtAmount = CoreArithmeticUtils.symbolToCurrencyScale(order101.size, symbol, baseSpec);
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(xbtAmount));
                assertThat(profile.getAccounts().get(CURRENECY_LTC), is(ltcAmount - order2 - finalTakerFee));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // total balance remains the same
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            final long ltcFees = finalMakerFee + takerFee1;
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
        }

    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_AskGtcMakerPartial_BidIocTaker() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.addSymbol(symbol);
            CoreCurrencySpecification baseSpec = container.addCurrency(symbol.baseCurrency, 8);
            CoreCurrencySpecification quoteSpec = container.addCurrency(symbol.quoteCurrency, 8);

            final long btcAmount = 2_000L * baseSpec.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, CURRENECY_XBT, btcAmount);

            // submit an ASK GtC order, no fees, sell 2,000 lots, price 115,000K (11,500 x10,000 step)
            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(11_500L * symbol.quoteScaleK)
                    .reservePrice(11_500L * symbol.quoteScaleK)
                    .size(2000L * symbol.baseScaleK)
                    .action(OrderAction.ASK)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long hold = CoreArithmeticUtils.symbolToCurrencyScale(order101.size, symbol, baseSpec);
            // verify order placed
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(btcAmount - hold));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
            });

            // create second user
            final long ltcAmount = 2_000_000_000L * quoteSpec.getCurrencyScaleK();
            container.createUserWithMoney(UID_2, CURRENECY_LTC, ltcAmount);

            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertThat(totalBal1.getFees().get(CURRENECY_LTC), is(0L));
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));

            // submit an IoC order - ASK 1,997 lots, price 115,210K (11,521 x10,000 step) for each lot 1M satoshi
            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(11_521L * symbol.quoteScaleK)
                    .reservePrice(11_659L * symbol.quoteScaleK)
                    .size(1997L * symbol.baseScaleK)
                    .action(OrderAction.BID)
                    .orderType(OrderType.IOC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long makerFee = CoreArithmeticUtils.calculateMakerFee(order102.size, order101.price, symbol);
            makerFee = CoreArithmeticUtils.sizePriceToCurrencyScale(makerFee, symbol, quoteSpec);

            long orderSpend = order102.size * order101.price;
            long order = CoreArithmeticUtils.sizePriceToCurrencyScale(orderSpend, symbol, quoteSpec);
            // verify seller maker balance
            long finalMakerFee = makerFee;
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertThat(profile.getAccounts().get(CURRENECY_LTC), is(order - finalMakerFee));
                assertFalse(profile.fetchIndexedOrders().isEmpty());
            });

            long takerFee = CoreArithmeticUtils.calculateTakerFee(order102.size, order101.price, symbol);
            takerFee = CoreArithmeticUtils.sizePriceToCurrencyScale(takerFee, symbol, quoteSpec);
            long finalTakerFee = takerFee;

            long gainedAmountInBaseCurrency = order102.size;
            long xbtAmount = CoreArithmeticUtils.symbolToCurrencyScale(gainedAmountInBaseCurrency, symbol, baseSpec);
            // verify buyer taker balance
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(xbtAmount));
                assertThat(profile.getAccounts().get(CURRENECY_LTC), is(ltcAmount - order - finalTakerFee));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // total balance remains the same
            final long ltcFees = finalMakerFee + finalTakerFee;
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(ltcFees));
        }
    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_AskGtcMakerPartial_BidGtcTaker() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.addSymbol(symbol);
            CoreCurrencySpecification baseSpec = container.addCurrency(symbol.baseCurrency, 8);
            CoreCurrencySpecification quoteSpec = container.addCurrency(symbol.quoteCurrency, 8);

            final long btcAmount = 2_000L * baseSpec.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, CURRENECY_XBT, btcAmount);

            // submit an ASK GtC order, no fees, sell 2,000 lots, price 115,000K (11,500 x10,000 step)
            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(11_500L * symbol.quoteScaleK)
                    .reservePrice(11_500L * symbol.quoteScaleK)
                    .size(2000L * symbol.baseScaleK)
                    .action(OrderAction.ASK)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // verify order placed
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
            });

            // create second user
            final long ltcAmount = 2_000_000_000L * quoteSpec.getCurrencyScaleK();
            container.createUserWithMoney(UID_2, CURRENECY_LTC, ltcAmount);

            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertThat(totalBal1.getFees().get(CURRENECY_LTC), is(0L));
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));

            // submit an IoC order - ASK 1,997 lots, price 115,210K (11,521 x10,000 step) for each lot 1M satoshi
            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(11_521L * symbol.quoteScaleK)
                    .reservePrice(11_659L * symbol.quoteScaleK)
                    .size(1997L * symbol.baseScaleK)
                    .action(OrderAction.BID)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long makerFee = CoreArithmeticUtils.calculateMakerFee(order102.size, order101.price, symbol);
            makerFee = CoreArithmeticUtils.sizePriceToCurrencyScale(makerFee, symbol, quoteSpec);
            long finalMakerFee = makerFee;

            long orderSpend = order102.size * order101.price;
            long order = CoreArithmeticUtils.sizePriceToCurrencyScale(orderSpend, symbol, quoteSpec);
            // verify seller maker balance
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertThat(profile.getAccounts().get(CURRENECY_LTC), is(order - finalMakerFee));
                assertFalse(profile.fetchIndexedOrders().isEmpty());
            });

            long takerFee = CoreArithmeticUtils.calculateTakerFee(order102.size, order101.price, symbol);
            takerFee = CoreArithmeticUtils.sizePriceToCurrencyScale(takerFee, symbol, quoteSpec);
            long finalTakerFee = takerFee;

            long gainedAmountInBaseCurrency = order102.size;
            long xbtAmount = CoreArithmeticUtils.symbolToCurrencyScale(gainedAmountInBaseCurrency, symbol, baseSpec);
            // verify buyer taker balance
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(xbtAmount));
                assertThat(profile.getAccounts().get(CURRENECY_LTC), is(ltcAmount - order - finalTakerFee));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // total balance remains the same
            final long ltcFees = finalMakerFee + finalTakerFee;
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(ltcFees));
        }
    }

    @Test
    @Timeout(10)
    public void shouldNotProcessFees_AskGtcMakerPartial_BidFokTaker() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.addSymbol(symbol);
            CoreCurrencySpecification baseSpec = container.addCurrency(symbol.baseCurrency, 8);
            CoreCurrencySpecification quoteSpec = container.addCurrency(symbol.quoteCurrency, 8);

            final long btcAmount = 2_000L * baseSpec.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, CURRENECY_XBT, btcAmount);

            // submit an ASK GtC order, no fees, sell 2,000 lots, price 115,000K (11,500 x10,000 step)
            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(11_500L * symbol.quoteScaleK)
                    .reservePrice(11_500L * symbol.quoteScaleK)
                    .size(2000L * symbol.baseScaleK)
                    .action(OrderAction.ASK)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // verify order placed
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
            });

            // create second user
            final long ltcAmount = 2_000_000_000L * quoteSpec.getCurrencyScaleK();
            container.createUserWithMoney(UID_2, CURRENECY_LTC, ltcAmount);

            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertThat(totalBal1.getFees().get(CURRENECY_LTC), is(0L));
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));

            // submit an IoC order - ASK 1,997 lots, price 115,210K (11,521 x10,000 step) for each lot 1M satoshi
            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(11_500L * symbol.quoteScaleK)
                    .reservePrice(11_500L * symbol.quoteScaleK)
                    .size(1000L * symbol.baseScaleK)
                    .action(OrderAction.BID)
                    .orderType(FOK_BUDGET)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // verify seller maker balance
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertThat(profile.getAccounts().get(CURRENECY_LTC), is(0L));
                assertFalse(profile.fetchIndexedOrders().isEmpty());
            });

            // verify buyer taker balance
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertThat(profile.getAccounts().get(CURRENECY_LTC), is(ltcAmount));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // total balance remains the same
            final long ltcFees = 0;
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(ltcFees));
        }
    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_AskGtcMakerPartial_BidFokTaker() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.addSymbol(symbol);
            CoreCurrencySpecification baseSpec = container.addCurrency(symbol.baseCurrency, 8);
            CoreCurrencySpecification quoteSpec = container.addCurrency(symbol.quoteCurrency, 8);

            final long btcAmount = 2_000L * baseSpec.getCurrencyScaleK();
            container.createUserWithMoney(UID_1, CURRENECY_XBT, btcAmount);

            // submit an ASK GtC order, no fees, sell 2,000 lots, price 115,000K (11,500 x10,000 step)
            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(11_500L * symbol.quoteScaleK)
                    .reservePrice(11_500L * symbol.quoteScaleK)
                    .size(2000L * symbol.baseScaleK)
                    .action(OrderAction.ASK)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // verify order placed
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
            });

            // create second user
            final long ltcAmount = 2_000_000_000L * quoteSpec.getCurrencyScaleK();
            container.createUserWithMoney(UID_2, CURRENECY_LTC, ltcAmount);

            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertThat(totalBal1.getFees().get(CURRENECY_LTC), is(0L));
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));

            // submit an IoC order - ASK 1,997 lots, price 115,210K (11,521 x10,000 step) for each lot 1M satoshi
            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(11_500L * symbol.quoteScaleK * 1000L * symbol.baseScaleK)
                    .reservePrice(11_500L * symbol.quoteScaleK * 1000L * symbol.baseScaleK)
                    .size(1L)
                    .action(OrderAction.BID)
                    .orderType(FOK_BUDGET)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long makerFee1 = CoreArithmeticUtils.calculateMakerFee(1000L * symbol.baseScaleK, order101.price, symbol);
            makerFee1 = CoreArithmeticUtils.sizePriceToCurrencyScale(makerFee1, symbol, quoteSpec);
            long finalMakerFee = makerFee1;

            long orderSpend = 1000L * symbol.baseScaleK * 11_500L * symbol.quoteScaleK;
            long order = CoreArithmeticUtils.sizePriceToCurrencyScale(orderSpend, symbol, quoteSpec);
            // verify seller maker balance
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertThat(profile.getAccounts().get(CURRENECY_LTC), is(order - finalMakerFee));
                assertFalse(profile.fetchIndexedOrders().isEmpty());
            });

            long takerFee = CoreArithmeticUtils.calculateAmountBidTakerFeeForBudget(order102.size, order102.price, symbol);
            takerFee = CoreArithmeticUtils.sizePriceToCurrencyScale(takerFee, symbol, quoteSpec);
            long finalTakerFee = takerFee;

            long gainedAmountInBaseCurrency = order102.size;
            long xbtAmount = CoreArithmeticUtils.symbolToCurrencyScale(gainedAmountInBaseCurrency, symbol, baseSpec);
            // verify buyer taker balance
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(xbtAmount));
                assertThat(profile.getAccounts().get(CURRENECY_LTC), is(ltcAmount - order - finalTakerFee));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // total balance remains the same
            final long ltcFees = finalMakerFee + finalTakerFee;
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(ltcFees));
        }
    }

}
