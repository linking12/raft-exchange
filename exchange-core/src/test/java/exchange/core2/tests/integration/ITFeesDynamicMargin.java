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
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.api.ApiCancelOrder;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.ApiResetFee;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static exchange.core2.core.common.OrderType.FOK_BUDGET;
import static exchange.core2.core.common.OrderType.GTC;
import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
public abstract class ITFeesDynamicMargin {

    private final long step = SYMBOLSPEC_DYNAMIC_FEE_XBT_USD.quoteScaleK;
    private final long makerFee = SYMBOLSPEC_DYNAMIC_FEE_XBT_USD.makerFee;
    private final long takerFee = SYMBOLSPEC_DYNAMIC_FEE_XBT_USD.takerFee;
    private final long scaleFee = SYMBOLSPEC_DYNAMIC_FEE_XBT_USD.feeScaleK;

    // configuration provided by child class
    public abstract PerformanceConfiguration getPerformanceConfiguration();

    private long calculateFee(long price, long size, long step, long sideFee, long scale) {
        return price * size * step * sideFee / scale;
    }

    private long calculateMarginFee(long price, long size) {
        return price * size * SYMBOLSPEC_DYNAMIC_FEE_XBT_USD.initMargin / SYMBOLSPEC_DYNAMIC_FEE_XBT_USD.initMarginScaleK;
    }

    private void checkFeeAfterResetFee(ExchangeTestContainer container) {
        container.submitCommandSync(ApiResetFee.builder().build(), CommandResultCode.SUCCESS);
        assertThat(container.totalBalanceReport().getFees().get(CURRENECY_USD), is(0L));
        assertThat(container.totalBalanceReport().getFees().get(CURRENECY_XBT), is(0L));
        assertThat(container.totalBalanceReport().isGlobalBalancesAllZero(), is(true));
    }

    @Test
    @Timeout(10)
    public void shouldRequireTakerFees_GtcCancel1() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();
            container.initDynamicFeeSymbolsMarkPrice();
            // ----------------- 1 test GTC BID cancel ------------------
            long usdtAmount = 100L;
            long price = 10000L;
            long size = 1;
            container.createUserWithMoney(UID_2, CURRENECY_USD, usdtAmount);

            // submit BID order for 1000 lots - should be rejected because of the fee
            final ApiPlaceOrder order203 = ApiPlaceOrder.builder().uid(UID_2).orderId(203).price(price).reservePrice(price).size(size).action(OrderAction.BID).orderType(GTC).symbol(SYMBOL_MARGIN_FEE).marginMode(MarginMode.ISOLATED).build();
            container.submitCommandSync(order203, CommandResultCode.RISK_NSF);

            // add fee-1 - NSF
            long fee = calculateFee(price, size, step, takerFee, scaleFee);
            container.addMoneyToUser(UID_2, CURRENECY_USD, -usdtAmount);
            container.addMoneyToUser(UID_2, CURRENECY_USD, fee - 1);
            container.submitCommandSync(order203, CommandResultCode.RISK_NSF);

            // add 1 extra - NSF
            container.addMoneyToUser(UID_2, CURRENECY_USD, 1);
            container.submitCommandSync(order203, CommandResultCode.RISK_NSF);

            long initMargin = calculateMarginFee(price, size);
            // add margin required - SUCCESS
            container.addMoneyToUser(UID_2, CURRENECY_USD, initMargin);
            container.submitCommandSync(order203, CommandResultCode.SUCCESS);

            // cancel bid
            container.submitCommandSync(
                    ApiCancelOrder.builder().orderId(203).uid(UID_2).symbol(SYMBOL_MARGIN_FEE).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(fee + initMargin));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + fee));
            assertThat(totalBal1.getFees().get(CURRENECY_USD), is(0L));

            // ----------------- 2 test GTC ASK cancel ------------------
            // can place ASK order, no extra is fee required for lock hold
            final ApiPlaceOrder order204 = ApiPlaceOrder.builder().uid(UID_2).orderId(204).price(price).reservePrice(price).size(size).action(OrderAction.ASK).orderType(GTC).symbol(SYMBOL_MARGIN_FEE).marginMode(MarginMode.ISOLATED).build();
            container.submitCommandSync(order204, CommandResultCode.SUCCESS);

            // cancel ask
            container.submitCommandSync(
                    ApiCancelOrder.builder().orderId(204).uid(UID_2).symbol(SYMBOL_MARGIN_FEE).build(),
                    CommandResultCode.SUCCESS);

            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(fee + initMargin));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // no fees collected
            TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + fee));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));
            assertThat(totalBal2.getFees().get(CURRENECY_USD), is(0L));
            assertThat(totalBal2.getFees().get(CURRENECY_XBT), is(0L));

            checkFeeAfterResetFee(container);
        }
    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_BidGtcMaker_AskIocTakerPartial() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();
            container.initDynamicFeeSymbolsMarkPrice();
            final long usdtAmount = 10000000L;
            container.createUserWithMoney(UID_1, CURRENECY_USD, usdtAmount);

            long price = 10000L;
            long reservePrice = 10005L;
            long size = 100L;

            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(UID_1)
                    .orderId(101L)
                    .price(price)
                    .reservePrice(reservePrice)
                    .size(size)
                    .action(OrderAction.BID)
                    .orderType(GTC)
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(size));
            });

            // create second user
            container.createUserWithMoney(UID_2, CURRENECY_USD, MAX_VALUE);

            // no fees collected
            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));
            assertThat(totalBal1.getFees().get(CURRENECY_USD), is(0L));

            long askPrice = 10000L;
            long askSize = 500L;

            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(askPrice)
                    .size(askSize)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.IOC)
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            // 订单成交价格10000, size 500. 此时uid1(1440001)为maker, uid2(1440002)为taker
            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long actualMakerFee = calculateFee(price, size, step, makerFee, scaleFee);
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount - actualMakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openVolume, is(size));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).profit, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openPriceSum, is(size * price));
            });

            // verify seller taker balance
            // taker手续费: 因为是IOC订单所以只收成交部分的费用
            long actualTakerFee = calculateFee(size, price, step, takerFee, scaleFee);
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(MAX_VALUE - actualTakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openVolume, is(size));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).profit, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openPriceSum, is(size * price));
            });

            // total balance remains the same
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            final long ltcFees = actualMakerFee + actualTakerFee;
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getFees().get(CURRENECY_USD), is(ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));

            checkFeeAfterResetFee(container);
        }

    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_BidGtcMakerPartial_AskIocTaker() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();
            container.initDynamicFeeSymbolsMarkPrice();
            final long usdtAmount = 10000000L;
            container.createUserWithMoney(UID_1, CURRENECY_USD, usdtAmount);

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
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(size));
            });

            // create second user
            container.createUserWithMoney(UID_2, CURRENECY_USD, MAX_VALUE);

            // no fees collected
            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));
            assertThat(totalBal1.getFees().get(CURRENECY_USD), is(0L));

            long askPrice = 10000L;
            long askSize = 100L;

            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(askPrice)
                    .size(askSize)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.IOC)
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            // 订单成交价格10000, size 500. 此时uid1(1440001)为maker, uid2(1440002)为taker
            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long actualMakerFee = calculateFee(price, askSize, step, makerFee, scaleFee);
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount - actualMakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertFalse(profile.fetchIndexedOrders().isEmpty());
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(size - askSize));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openVolume, is(askSize));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).profit, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openPriceSum, is(askSize * price));
            });

            // verify seller taker balance
            // taker手续费: 因为是IOC订单所以只收成交部分的费用
            long actualTakerFee = calculateFee(askSize, price, step, takerFee, scaleFee);
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(MAX_VALUE - actualTakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openVolume, is(askSize));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).profit, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openPriceSum, is(askSize * price));
            });

            // total balance remains the same
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            final long ltcFees = actualMakerFee + actualTakerFee;
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getFees().get(CURRENECY_USD), is(ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));

            checkFeeAfterResetFee(container);
        }
    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_BidGtcMaker_AskIocTaker_FullyMatch() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();
            container.initDynamicFeeSymbolsMarkPrice();
            final long usdtAmount = 10000000L;
            container.createUserWithMoney(UID_1, CURRENECY_USD, usdtAmount);

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
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(size));
            });

            // create second user
            container.createUserWithMoney(UID_2, CURRENECY_USD, MAX_VALUE);

            // no fees collected
            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));
            assertThat(totalBal1.getFees().get(CURRENECY_USD), is(0L));

            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(price)
                    .size(size)
                    .action(OrderAction.ASK)
                    .orderType(OrderType.IOC)
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            // 订单成交价格10000, size 500. 此时uid1(1440001)为maker, uid2(1440002)为taker
            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long actualMakerFee = calculateFee(price, size, step, makerFee, scaleFee);
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount - actualMakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openVolume, is(size));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).profit, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openPriceSum, is(size * price));
            });

            // verify seller taker balance
            // taker手续费: 因为是IOC订单所以只收成交部分的费用
            long actualTakerFee = calculateFee(size, price, step, takerFee, scaleFee);
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(MAX_VALUE - actualTakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openVolume, is(size));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).profit, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openPriceSum, is(size * price));
            });

            // total balance remains the same
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            final long ltcFees = actualMakerFee + actualTakerFee;
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getFees().get(CURRENECY_USD), is(ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));

            checkFeeAfterResetFee(container);
        }
    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_AskGtcMaker_BidIocTakerPartial() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();
            container.initDynamicFeeSymbolsMarkPrice();
            final long usdtAmount = 10000000L;
            container.createUserWithMoney(UID_1, CURRENECY_USD, usdtAmount);

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
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(size));
            });

            // create second user
            container.createUserWithMoney(UID_2, CURRENECY_USD, MAX_VALUE);

            // no fees collected
            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));
            assertThat(totalBal1.getFees().get(CURRENECY_USD), is(0L));

            long askPrice = 10000L;
            long askSize = 500L;

            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(askPrice)
                    .size(askSize)
                    .action(OrderAction.BID)
                    .orderType(OrderType.IOC)
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            // 订单成交价格10000, size 500. 此时uid1(1440001)为maker, uid2(1440002)为taker
            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long actualMakerFee = calculateFee(price, size, step, makerFee, scaleFee);
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount - actualMakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openVolume, is(size));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).profit, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openPriceSum, is(size * price));
            });

            // verify seller taker balance
            // taker手续费: 因为是IOC订单所以只收成交部分的费用
            long actualTakerFee = calculateFee(size, price, step, takerFee, scaleFee);
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(MAX_VALUE - actualTakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openVolume, is(size));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).profit, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openPriceSum, is(size * price));
            });

            // total balance remains the same
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            final long ltcFees = actualMakerFee + actualTakerFee;
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getFees().get(CURRENECY_USD), is(ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));

            checkFeeAfterResetFee(container);
        }

    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_AskGtcMakerPartial_BidIocTaker() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();
            container.initDynamicFeeSymbolsMarkPrice();
            final long usdtAmount = 10000000L;
            container.createUserWithMoney(UID_1, CURRENECY_USD, usdtAmount);

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
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(size));
            });

            // create second user
            container.createUserWithMoney(UID_2, CURRENECY_USD, MAX_VALUE);

            // no fees collected
            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));
            assertThat(totalBal1.getFees().get(CURRENECY_USD), is(0L));

            long askPrice = 10000L;
            long askSize = 100L;

            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(askPrice)
                    .size(askSize)
                    .action(OrderAction.BID)
                    .orderType(OrderType.IOC)
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            // 订单成交价格10000, size 500. 此时uid1(1440001)为maker, uid2(1440002)为taker
            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long actualMakerFee = calculateFee(price, askSize, step, makerFee, scaleFee);
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount - actualMakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertFalse(profile.fetchIndexedOrders().isEmpty());
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(size - askSize));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openVolume, is(askSize));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).profit, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openPriceSum, is(askSize * price));
            });

            // verify seller taker balance
            // taker手续费: 因为是IOC订单所以只收成交部分的费用
            long actualTakerFee = calculateFee(askSize, price, step, takerFee, scaleFee);
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(MAX_VALUE - actualTakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openVolume, is(askSize));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).profit, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openPriceSum, is(askSize * price));
            });

            // total balance remains the same
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            final long ltcFees = actualMakerFee + actualTakerFee;
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getFees().get(CURRENECY_USD), is(ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));

            checkFeeAfterResetFee(container);
        }
    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_AskGtcMakerPartial_BidGtcTaker() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();
            container.initDynamicFeeSymbolsMarkPrice();
            final long usdtAmount = 10000000L;
            container.createUserWithMoney(UID_1, CURRENECY_USD, usdtAmount);

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
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(size));
            });

            // create second user
            container.createUserWithMoney(UID_2, CURRENECY_USD, MAX_VALUE);

            // no fees collected
            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));
            assertThat(totalBal1.getFees().get(CURRENECY_USD), is(0L));

            long askPrice = 10000L;
            long askSize = 100L;

            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(askPrice)
                    .size(askSize)
                    .action(OrderAction.BID)
                    .orderType(GTC)
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            // 订单成交价格10000, size 500. 此时uid1(1440001)为maker, uid2(1440002)为taker
            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long actualMakerFee = calculateFee(price, askSize, step, makerFee, scaleFee);
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount - actualMakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertFalse(profile.fetchIndexedOrders().isEmpty());
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(size - askSize));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openVolume, is(askSize));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).profit, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openPriceSum, is(askSize * price));
            });

            // verify seller taker balance
            // taker手续费: 因为是IOC订单所以只收成交部分的费用
            long actualTakerFee = calculateFee(askSize, price, step, takerFee, scaleFee);
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(MAX_VALUE - actualTakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openVolume, is(askSize));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).profit, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openPriceSum, is(askSize * price));
            });

            // total balance remains the same
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            final long ltcFees = actualMakerFee + actualTakerFee;
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getFees().get(CURRENECY_USD), is(ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));

            checkFeeAfterResetFee(container);
        }
    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_AskGtcMaker_BidGtcTakerPartial() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();
            container.initDynamicFeeSymbolsMarkPrice();
            final long usdtAmount = 10000000L;
            container.createUserWithMoney(UID_1, CURRENECY_USD, usdtAmount);

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
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(size));
            });

            // create second user
            container.createUserWithMoney(UID_2, CURRENECY_USD, MAX_VALUE);

            // no fees collected
            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));
            assertThat(totalBal1.getFees().get(CURRENECY_USD), is(0L));

            long askPrice = 10000L;
            long askSize = 500L;

            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(askPrice)
                    .size(askSize)
                    .action(OrderAction.BID)
                    .orderType(OrderType.GTC)
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            // 订单成交价格10000, size 500. 此时uid1(1440001)为maker, uid2(1440002)为taker
            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long actualMakerFee = calculateFee(price, size, step, makerFee, scaleFee);
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount - actualMakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openVolume, is(size));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).profit, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openPriceSum, is(size * price));
            });

            // verify seller taker balance
            long actualTakerFee = calculateFee(size, price, step, takerFee, scaleFee);
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(MAX_VALUE - actualTakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertFalse(profile.fetchIndexedOrders().isEmpty());
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(askSize - size));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openVolume, is(size));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).profit, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openPriceSum, is(size * price));
            });

            // total balance remains the same
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            final long ltcFees = actualMakerFee + actualTakerFee;
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getFees().get(CURRENECY_USD), is(ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));

            checkFeeAfterResetFee(container);
        }
    }

    @Test
    @Timeout(10)
    public void shouldProcessFees_AskGtcMakerPartial_BidFokTaker() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();
            container.initDynamicFeeSymbolsMarkPrice();
            final long usdtAmount = 10000000L;
            container.createUserWithMoney(UID_1, CURRENECY_USD, usdtAmount);

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
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(size));
            });

            // create second user
            container.createUserWithMoney(UID_2, CURRENECY_USD, MAX_VALUE);

            // no fees collected
            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));
            assertThat(totalBal1.getFees().get(CURRENECY_USD), is(0L));

            long askPrice = 10000L;
            long askSize = 1L;

            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(askPrice)
                    .size(askSize)
                    .action(OrderAction.BID)
                    .orderType(FOK_BUDGET)
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            long actualMakerFee = calculateFee(price, askSize, step, makerFee, scaleFee);
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount - actualMakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertFalse(profile.fetchIndexedOrders().isEmpty());
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(size - askSize));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openVolume, is(askSize));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).profit, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openPriceSum, is(askSize * price));
            });

            // verify seller taker balance
            // taker手续费: 因为是IOC订单所以只收成交部分的费用
            long actualTakerFee = calculateFee(askSize, price, step, takerFee, scaleFee);
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(MAX_VALUE - actualTakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openVolume, is(askSize));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).profit, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openPriceSum, is(askSize * price));
            });

            // total balance remains the same
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            final long ltcFees = actualMakerFee + actualTakerFee;
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getFees().get(CURRENECY_USD), is(ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));

            checkFeeAfterResetFee(container);
        }
    }

    @Test
    @Timeout(10)
    public void shouldNotProcessFees_AskGtcMakerPartial_BidFokTaker() throws Exception {

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initDynamicFeeSymbols();
            container.initDynamicFeeSymbolsMarkPrice();
            final long usdtAmount = 10000000L;
            container.createUserWithMoney(UID_1, CURRENECY_USD, usdtAmount);

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
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount));
                assertThat(profile.fetchIndexedOrders().get(101L).price, is(order101.price));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
            });

            // create second user
            container.createUserWithMoney(UID_2, CURRENECY_USD, MAX_VALUE);

            // no fees collected
            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertTrue(totalBal1.isGlobalBalancesAllZero());
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE));
            assertThat(totalBal1.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));
            assertThat(totalBal1.getFees().get(CURRENECY_USD), is(0L));

            long askPrice = 10000L;
            long askSize = 100L;

            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(UID_2)
                    .orderId(102)
                    .price(askPrice)
                    .size(askSize)
                    .action(OrderAction.BID)
                    .orderType(OrderType.FOK)
                    .symbol(SYMBOL_MARGIN_FEE)
                    .marginMode(MarginMode.ISOLATED)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // 没成交, fee应该为0
            long actualMakerFee = 0L;
            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(usdtAmount - actualMakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertFalse(profile.fetchIndexedOrders().isEmpty());
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).direction, is(PositionDirection.SHORT));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingBuySize, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).pendingSellSize, is(size));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openVolume, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).profit, is(0L));
                assertThat(profile.getPositions().get(SYMBOL_MARGIN_FEE).get(0).openPriceSum, is(0L));
            });

            // verify seller taker balance
            // taker手续费: 因为是IOC订单所以只收成交部分的费用
            long actualTakerFee = 0L;
            container.validateUserState(UID_2, profile -> {
                assertThat(profile.getAccounts().get(CURRENECY_USD), is(MAX_VALUE - actualTakerFee));
                assertThat(profile.getAccounts().get(CURRENECY_XBT), is(0L));
                assertTrue(profile.fetchIndexedOrders().isEmpty());
            });

            // total balance remains the same
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            final long ltcFees = actualMakerFee + actualTakerFee;
            assertTrue(totalBal2.isGlobalBalancesAllZero());
            assertThat(totalBal2.getFees().get(CURRENECY_USD), is(ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_USD), is(usdtAmount + MAX_VALUE - ltcFees));
            assertThat(totalBal2.getClientsBalancesSum().get(CURRENECY_XBT), is(0L));

            checkFeeAfterResetFee(container);
        }
    }

}
