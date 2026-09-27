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

import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.api.ApiCancelOrder;
import exchange.core2.core.common.api.ApiResetFee;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static exchange.core2.core.common.OrderType.FOK_BUDGET;
import static exchange.core2.core.common.OrderType.GTC;
import static exchange.core2.tests.util.ExchangeTestContainer.available;
import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
public abstract class ITFeesDynamicExchange {

    private final long step = SYMBOLSPEC_DYNAMIC_FEE_XBT_LTC.quoteScaleK;
    private final long makerFee = SYMBOLSPEC_DYNAMIC_FEE_XBT_LTC.makerFee;
    private final long takerFee = SYMBOLSPEC_DYNAMIC_FEE_XBT_LTC.takerFee;
    private final long scaleFee = SYMBOLSPEC_DYNAMIC_FEE_XBT_LTC.feeScaleK;

    // configuration provided by child class
    public abstract PerformanceConfiguration getPerformanceConfiguration();

    private void checkFeeAfterResetFee(ExchangeTestContainer container) {
        container.submitCommandSync(ApiResetFee.builder().build(), CommandResultCode.SUCCESS);
        assertThat(container.totalBalanceReport().getFees().get(CURRENECY_LTC), is(0L));
        assertThat(container.totalBalanceReport().getFees().get(CURRENECY_XBT), is(0L));
        assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
    }

    @Test
    @Timeout(10)
    public void shouldRequireTakerFees_GtcCancel1() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();

            // ----------------- 1 test GTC BID cancel ------------------

            // create user - 3.42B litoshi (34.2 LTC)
            final long ltcAmount = 3_420_00L;
            final long price = 11_400L;
            final long size = 30;
            container.createUserWithMoney(UID_2, CURRENECY_LTC, ltcAmount);

            // submit BID order for 1000 lots - should be rejected because of the fee
            final ApiPlaceOrder order203 = ApiPlaceOrder.builder().uid(UID_2).orderId(203).price(price).reservePrice(price).size(size).action(OrderAction.BID).orderType(GTC).symbol(SYMBOL_EXCHANGE_FEE).marginMode(MarginMode.ISOLATED).build();
            container.submitCommandSync(order203, CommandResultCode.RISK_NSF);

            // add fee-1 - NSF
            container.addMoneyToUser(UID_2, CURRENECY_LTC, price * size * step * takerFee / scaleFee - 1);
            container.submitCommandSync(order203, CommandResultCode.RISK_NSF);

            // add 1 extra - SUCCESS
            container.addMoneyToUser(UID_2, CURRENECY_LTC, 1);
            container.submitCommandSync(order203, CommandResultCode.SUCCESS);

            // cancel bid
            container.submitCommandSync(
                    ApiCancelOrder.builder().orderId(203).uid(UID_2).symbol(SYMBOL_EXCHANGE_FEE).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_2, profile -> {
                assertThat(available(profile, CURRENECY_LTC), is(ltcAmount + price * size * step * takerFee / scaleFee));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount + price * size * step * takerFee / scaleFee));
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
                assertThat(available(profile, CURRENECY_XBT), is(btcAmount));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // no fees collected
            TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount + price * size * step * takerFee / scaleFee));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(0L));
            assertThat(totalBal2.getFees().get(CURRENECY_XBT), is(0L));

            checkFeeAfterResetFee(container);
        }
    }

    private long calculateFee(long price, long size, long step, long sideFee, long scale) {
        return price * size * step * sideFee / scale;
    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_BidGtcMaker_AskIocTakerPartial() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();
            final long ltcAmount = 10000000L;
            container.createUserWithMoney(UID_1, CURRENECY_LTC, ltcAmount);

            long price = 10000L;
            long reservePrice = 10005L;
            long size = 500L;

            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(price)
                    .reservePrice(reservePrice)
                    .size(size)
                    .action(OrderAction.BID)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));
            // 挂单先按照takerFee收, 下单时也可能作为taker, 所以预先按照Taker Fee来收是为了确保资金充足
            long fee = calculateFee(reservePrice, size, step, takerFee, scaleFee);
            long tradeAmount = size * reservePrice * step;
            long expectedFundsLtc = ltcAmount - tradeAmount - fee;
            // verify order placed with correct reserve price and account balance is updated accordingly
            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_LTC), is(expectedFundsLtc));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
            });

            // create second user
            final long btcAmount = 2_000L;
            container.createUserWithMoney(UID_2, CURRENECY_XBT, btcAmount);

            // no fees collected
            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
            assertThat(totalBal1.getFees().get(CURRENECY_LTC), is(0L));

            long askPrice = 10000L;
            long askSize = 2000L;

            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(askPrice)
                    .size(askSize)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.IOC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            // 订单成交价格10000, size 500. 此时uid1(1440001)为maker, uid2(1440002)为taker
            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // maker需要把多扣的手续费加还回来
            // a. 开始时算的fee是用reservePrice, 真实成交时为price
            // b. 一开始计算fee时用的takerFee, 现在要换成makerFee
            long actualMakerFee = calculateFee(price, size, step, makerFee, scaleFee);
            long expectedLtcAmount = ltcAmount - price * size * step - actualMakerFee;
            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_LTC), is(expectedLtcAmount));
                assertThat(available(profile, CURRENECY_XBT), is(size * SYMBOLSPEC_DYNAMIC_FEE_XBT_LTC.baseScaleK));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // verify seller taker balance
            // taker手续费: 因为是IOC订单所以只收成交部分的费用
            long actualTakerFee = calculateFee(size, price, step, takerFee, scaleFee);
            container.validateUserState(UID_2, profile -> {
                assertThat(available(profile, CURRENECY_LTC), is(size * price - actualTakerFee));
                assertThat(available(profile, CURRENECY_XBT), is(btcAmount - size * SYMBOLSPEC_DYNAMIC_FEE_XBT_LTC.baseScaleK));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // total balance remains the same
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            final long ltcFees = actualMakerFee + actualTakerFee;
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));

            container.submitCommandSync(ApiResetFee.builder().build(), CommandResultCode.SUCCESS);
            assertThat(container.totalBalanceReport().getFees().get(CURRENECY_LTC), is(0L));
            assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));

            checkFeeAfterResetFee(container);
        }

    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_BidGtcMakerPartial_AskIocTaker() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();

            long price = 10000L;
            long reservePrice = 10005L;
            long size = 500L;

            final long ltcAmount = 10000000L;
            container.createUserWithMoney(UID_1, CURRENECY_LTC, ltcAmount);

            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(price)
                    .reservePrice(reservePrice)
                    .size(size)
                    .action(OrderAction.BID)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long fee = calculateFee(reservePrice, size, step, takerFee, scaleFee);
            long tradeAmount = size * reservePrice * step;
            long expectedFundsLtc = ltcAmount - tradeAmount - fee;
            // verify order placed with correct reserve price and account balance is updated accordingly
            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_LTC), is(expectedFundsLtc));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
            });

            // create second user
            final long btcAmount = 100L;
            container.createUserWithMoney(UID_2, CURRENECY_XBT, btcAmount);

            // no fees collected
            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
            assertThat(totalBal1.getFees().get(CURRENECY_LTC), is(0L));

            long askSize = 100L;
            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(price)
                    .size(askSize)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.IOC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // 真实成交要作为maker被收的钱
            long actualMakerPart = calculateFee(price, askSize, step, makerFee, scaleFee);
            // 未成交部分暂时还按照taker身份扣除
            long takerHoldPart = calculateFee(reservePrice, size - askSize, step, takerFee, scaleFee);
            // 用户视角 真实锁定资金 = actualMakerPart + takerHoldPart
            long totalOccupiedFee = actualMakerPart + takerHoldPart;
            long current = ltcAmount - price * askSize - reservePrice * (size - askSize) - totalOccupiedFee;
            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_LTC), is(current));
                assertThat(available(profile, CURRENECY_XBT), is(askSize * SYMBOLSPEC_DYNAMIC_FEE_XBT_LTC.baseScaleK));
                assertFalse(profile.fetchIndexedOrders().isEmpty());
            });

            // verify seller taker balance
            long actualTakerFee = calculateFee(price, askSize, step, takerFee, scaleFee);
            container.validateUserState(UID_2, profile -> {
                assertThat(available(profile, CURRENECY_LTC), is(price * askSize * step - actualTakerFee));
                assertThat(available(profile, CURRENECY_XBT), is(btcAmount - askSize * SYMBOLSPEC_DYNAMIC_FEE_XBT_LTC.baseScaleK));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // total balance remains the same
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            // 作为交易所，只有100真实成交，针对它，收取2w taker费和1w maker费
            final long ltcFees = actualMakerPart + actualTakerFee;
            // below two lines failed which are not desired
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));

            checkFeeAfterResetFee(container);
        }
    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_BidGtcMaker_AskIocTaker_FullyMatch() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();

            long price = 10000L;
            long reservePrice = 10005L;
            long size = 500L;

            final long ltcAmount = 10000000L;
            container.createUserWithMoney(UID_1, CURRENECY_LTC, ltcAmount);

            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(price)
                    .reservePrice(reservePrice)
                    .size(size)
                    .action(OrderAction.BID)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long fee = calculateFee(reservePrice, size, step, takerFee, scaleFee);
            long tradeAmount = size * reservePrice * step;
            long expectedFundsLtc = ltcAmount - tradeAmount - fee;
            // verify order placed with correct reserve price and account balance is updated accordingly
            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_LTC), is(expectedFundsLtc));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
            });

            // create second user
            final long btcAmount = 500L;
            container.createUserWithMoney(UID_2, CURRENECY_XBT, btcAmount);

            // no fees collected
            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
            assertThat(totalBal1.getFees().get(CURRENECY_LTC), is(0L));

            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(price)
                    .size(size)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.IOC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // maker需要的手续费 = 成交部分手续费(maker) + 未成交部分手续费(taker)
            long makerPart = calculateFee(price, size, step, makerFee, scaleFee);
            long takerPart = 0; // uid1下的单完全成交且没有剩余, 所以takerPart为0
            long actualMakerFee = makerPart + takerPart;
            long current = ltcAmount - price * size - actualMakerFee;
            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_LTC), is(current));
                assertThat(available(profile, CURRENECY_XBT), is(size * SYMBOLSPEC_DYNAMIC_FEE_XBT_LTC.baseScaleK));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // verify seller taker balance
            long actualTakerFee = calculateFee(price, size, step, takerFee, scaleFee);
            container.validateUserState(UID_2, profile -> {
                assertThat(available(profile, CURRENECY_LTC), is(price * size * step - actualTakerFee));
                assertThat(available(profile, CURRENECY_XBT), is(btcAmount - size * SYMBOLSPEC_DYNAMIC_FEE_XBT_LTC.baseScaleK));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // total balance remains the same
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            final long ltcFees = actualMakerFee + actualTakerFee;
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));

            checkFeeAfterResetFee(container);
        }
    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_AskGtcMaker_BidIocTakerPartial() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();

            final long btcAmount = 2_000L;
            container.createUserWithMoney(UID_1, CURRENECY_XBT, btcAmount);

            long price = 10000L;
            long reservePrice = 10005L;
            long size = 100L;
            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(price)
                    .reservePrice(reservePrice)
                    .size(size)
                    .action(OrderAction.ASK)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // verify order placed
            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(btcAmount - size));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
            });

            // create second user
            final long ltcAmount = 10000000L;
            container.createUserWithMoney(UID_2, CURRENECY_LTC, ltcAmount);

            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
            assertThat(totalBal1.getFees().get(CURRENECY_LTC), is(0L));

            long bidSize = 500;
            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(price)
                    .reservePrice(reservePrice)
                    .size(bidSize)
                    .action(OrderAction.BID)
                    .orderType(OrderType.IOC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // uid1挂卖单不收fee, 后续作为maker需要按照makerFee来收
            long actualMakerFee = calculateFee(price, size, step, makerFee, scaleFee);
            long tradeAmount = size * price * step;
            long expectedFundsLtc = tradeAmount - actualMakerFee;

            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(btcAmount - size));
                assertThat(available(profile, CURRENECY_LTC), is(expectedFundsLtc));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // uid2挂买单, 只成交了部分. 因为是IOC订单, 不再继续收taker后续未成交部分
            long takerPart1 = calculateFee(size, price, step, takerFee, scaleFee);
            long takerPart2 = 0;
            long actualTakerFee = takerPart1 + takerPart2;
            long expected = ltcAmount - price * size * step - actualTakerFee;

            container.validateUserState(UID_2, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(size));
                assertThat(available(profile, CURRENECY_LTC), is(expected));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // total balance remains the same
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            final long ltcFees = actualMakerFee + actualTakerFee;
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));

            checkFeeAfterResetFee(container);
        }

    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_AskGtcMakerPartial_BidIocTaker() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();

            final long btcAmount = 2_000L;
            container.createUserWithMoney(UID_1, CURRENECY_XBT, btcAmount);

            long price = 10000L;
            long reservePrice = 10005L;
            long size = 500L;

            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(price)
                    .reservePrice(reservePrice)
                    .size(size)
                    .action(OrderAction.ASK)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // verify order placed
            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(btcAmount - size));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
            });

            // create second user
            final long ltcAmount = 10000000L;
            container.createUserWithMoney(UID_2, CURRENECY_LTC, ltcAmount);

            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertThat(totalBal1.getFees().get(CURRENECY_LTC), is(0L));
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));

            long bidSize = 100;
            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(price)
                    .reservePrice(reservePrice)
                    .size(bidSize)
                    .action(OrderAction.BID)
                    .orderType(OrderType.IOC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // uid1挂卖单不收fee, 后续作为maker需要按照makerFee来收
            long actualMakerFee = calculateFee(price, bidSize, step, makerFee, scaleFee);
            long tradeAmount = bidSize * price * step;
            long expectedFundsLtc = tradeAmount - actualMakerFee;

            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(btcAmount - size));
                assertThat(available(profile, CURRENECY_LTC), is(expectedFundsLtc));
                assertFalse(profile.fetchIndexedOrders().isEmpty());
            });

            // uid2挂买单, 只成交了部分. 因为是IOC订单, 不再继续收taker后续未成交部分
            long actualTakerFee = calculateFee(bidSize, price, step, takerFee, scaleFee);
            long expected = ltcAmount - price * bidSize * step - actualTakerFee;

            container.validateUserState(UID_2, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(bidSize));
                assertThat(available(profile, CURRENECY_LTC), is(expected));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // total balance remains the same
            long ltcFees = actualMakerFee + actualTakerFee;
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(ltcFees));

            checkFeeAfterResetFee(container);
        }
    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_AskGtcMakerPartial_BidGtcTaker() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();

            final long btcAmount = 2_000L;
            container.createUserWithMoney(UID_1, CURRENECY_XBT, btcAmount);

            long price = 10000L;
            long reservePrice = 10005L;
            long size = 500L;

            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(price)
                    .reservePrice(reservePrice)
                    .size(size)
                    .action(OrderAction.ASK)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // verify order placed
            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(btcAmount - size));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
            });

            // create second user
            final long ltcAmount = 10000000L;// 260B litoshi (2,600 LTC)
            container.createUserWithMoney(UID_2, CURRENECY_LTC, ltcAmount);

            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertThat(totalBal1.getFees().get(CURRENECY_LTC), is(0L));
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));

            long bidSize = 100;
            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(price)
                    .reservePrice(reservePrice)
                    .size(bidSize)
                    .action(OrderAction.BID)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // uid1挂卖单不收fee, 后续作为maker需要按照makerFee来收
            long actualMakerFee = calculateFee(price, bidSize, step, makerFee, scaleFee);
            long tradeAmount = bidSize * price * step;
            long expectedFundsLtc = tradeAmount - actualMakerFee;

            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(btcAmount - size));
                assertThat(available(profile, CURRENECY_LTC), is(expectedFundsLtc));
                assertFalse(profile.fetchIndexedOrders().isEmpty());
            });

            // uid2挂买单, 只成交了部分. 因为是IOC订单, 不再继续收taker后续未成交部分
            long actualTakerFee = calculateFee(bidSize, price, step, takerFee, scaleFee);
            long expected = ltcAmount - price * bidSize * step - actualTakerFee;

            container.validateUserState(UID_2, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(bidSize));
                assertThat(available(profile, CURRENECY_LTC), is(expected));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // total balance remains the same
            long ltcFees = actualMakerFee + actualTakerFee;
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(ltcFees));

            checkFeeAfterResetFee(container);
        }
    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_AskGtcMaker_BidGtcTakerPartial() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();

            final long btcAmount = 2_000L;
            container.createUserWithMoney(UID_1, CURRENECY_XBT, btcAmount);

            long price = 10000L;
            long reservePrice = 10005L;
            long size = 100L;

            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(price)
                    .reservePrice(reservePrice)
                    .size(size)
                    .action(OrderAction.ASK)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // verify order placed
            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(btcAmount - size));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
            });

            // create second user
            final long ltcAmount = 10000000L;// 260B litoshi (2,600 LTC)
            container.createUserWithMoney(UID_2, CURRENECY_LTC, ltcAmount);

            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertThat(totalBal1.getFees().get(CURRENECY_LTC), is(0L));
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));

            long bidSize = 500;
            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(price)
                    .reservePrice(reservePrice)
                    .size(bidSize)
                    .action(OrderAction.BID)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // uid1挂卖单不收fee, 后续作为maker需要按照makerFee来收
            long actualMakerFee = calculateFee(price, size, step, makerFee, scaleFee);
            long tradeAmount = size * price * step;
            long expectedFundsLtc = tradeAmount - actualMakerFee;

            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(btcAmount - size));
                assertThat(available(profile, CURRENECY_LTC), is(expectedFundsLtc));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // 真实成交要作为taker被收的钱
            long actualTakerFee = calculateFee(price, size, step, takerFee, scaleFee); // 成交的部分按照成单价格收
            // 未成交部分暂时还按照taker身份扣除
            long takerHoldPart = calculateFee(reservePrice, bidSize - size, step, takerFee, scaleFee); // 未成交的部分需要按照reservePrice来收
            // 用户视角 真实锁定资金 = actualTakerPart + takerHoldPart
            long totalOccupiedFee = actualTakerFee + takerHoldPart;
            long expected = ltcAmount - price * size - reservePrice * (bidSize - size) - totalOccupiedFee;

            container.validateUserState(UID_2, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(size));
                assertThat(available(profile, CURRENECY_LTC), is(expected));
                assertFalse(profile.fetchIndexedOrders().isEmpty());
            });

            // total balance remains the same
            long ltcFees = actualMakerFee + actualTakerFee;
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(ltcFees));

            checkFeeAfterResetFee(container);
        }
    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_AskGtcMakerPartial_BidFokTaker() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();

            final long btcAmount = 2_000L;
            container.createUserWithMoney(UID_1, CURRENECY_XBT, btcAmount);

            long price = 10000L;
            long reservePrice = 10005L;
            long size = 500L;

            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(price)
                    .reservePrice(reservePrice)
                    .size(size)
                    .action(OrderAction.ASK)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // verify order placed
            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(btcAmount - size));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
            });

            // create second user
            final long ltcAmount = 10000000L;// 260B litoshi (2,600 LTC)
            container.createUserWithMoney(UID_2, CURRENECY_LTC, ltcAmount);

            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertThat(totalBal1.getFees().get(CURRENECY_LTC), is(0L));
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));

            long bidSize = 1;
            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(price)
                    .reservePrice(price)
                    .size(bidSize)
                    .action(OrderAction.BID)
                    .orderType(FOK_BUDGET)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // uid1挂卖单不收fee, 后续作为maker需要按照makerFee来收
            long actualMakerFee = calculateFee(price, bidSize, step, makerFee, scaleFee);
            long tradeAmount = bidSize * price * step;
            long expectedFundsLtc = tradeAmount - actualMakerFee;

            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(btcAmount - size));
                assertThat(available(profile, CURRENECY_LTC), is(expectedFundsLtc));
                assertFalse(profile.fetchIndexedOrders().isEmpty());
            });

            // uid2挂买单, 只成交了部分. 因为是IOC订单, 不再继续收taker后续未成交部分
            long actualTakerFee = calculateFee(bidSize, price, step, takerFee, scaleFee);
            long expected = ltcAmount - price * bidSize * step - actualTakerFee;

            // verify buyer taker balance
            container.validateUserState(UID_2, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(bidSize));
                assertThat(available(profile, CURRENECY_LTC), is(expected));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // total balance remains the same
            long ltcFees = actualMakerFee + actualTakerFee;
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(ltcFees));

            checkFeeAfterResetFee(container);
        }
    }

    @Test
    @Timeout(10)
    public void shouldNotProcessFees_AskGtcMakerPartial_BidFokTaker() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();

            final long btcAmount = 2_000L;
            container.createUserWithMoney(UID_1, CURRENECY_XBT, btcAmount);

            long price = 10000L;
            long reservePrice = 10005L;
            long size = 500L;

            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(price)
                    .reservePrice(reservePrice)
                    .size(size)
                    .action(OrderAction.ASK)
                    .orderType(GTC)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // verify order placed
            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(btcAmount - size));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
            });

            // create second user
            final long ltcAmount = 10000000L;// 260B litoshi (2,600 LTC)
            container.createUserWithMoney(UID_2, CURRENECY_LTC, ltcAmount);

            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertThat(totalBal1.getFees().get(CURRENECY_LTC), is(0L));
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));

            long bidSize = 10;
            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(price)
                    .reservePrice(price)
                    .size(bidSize)
                    .action(OrderAction.BID)
                    .orderType(FOK_BUDGET)
                    .symbol(SYMBOL_EXCHANGE_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // fok订单没成交, 不应该收手续费
            long actualMakerFee = 0;
            container.validateUserState(UID_1, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(btcAmount - size));
                assertThat(available(profile, CURRENECY_LTC), is(0L));
                assertFalse(profile.fetchIndexedOrders().isEmpty());
            });

            // uid2挂买单, 只成交了部分. 因为是IOC订单, 不再继续收taker后续未成交部分
            long actualTakerFee = 0L;
            long expected = ltcAmount;

            // verify buyer taker balance
            container.validateUserState(UID_2, profile -> {
                assertThat(available(profile, CURRENECY_XBT), is(0L));
                assertThat(available(profile, CURRENECY_LTC), is(expected));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // total balance remains the same
            long ltcFees = actualMakerFee + actualTakerFee;
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_LTC), is(ltcAmount - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(btcAmount));
            assertThat(totalBal2.getFees().get(CURRENECY_LTC), is(ltcFees));

            checkFeeAfterResetFee(container);
        }
    }

}
