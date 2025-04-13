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

import exchange.core2.core.IEventsHandler;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.core.Is;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.List;

import org.mockito.junit.jupiter.MockitoExtension;

import static exchange.core2.core.common.OrderAction.ASK;
import static exchange.core2.core.common.OrderAction.BID;
import static exchange.core2.core.common.OrderType.*;
import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ITFutureBasic {

    private int symbolId = 2;
    private int quoteId = 840;

    private SimpleEventsProcessor4Test processor;

    @Mock
    private IEventsHandler4Test handler;

    @Captor
    private ArgumentCaptor<IEventsHandler.ApiCommandResult> commandResultCaptor;

    @Captor
    private ArgumentCaptor<IEventsHandler.TradeEvent> tradeEventCaptor;

    @Captor
    private ArgumentCaptor<IEventsHandler.FundsEvent> fundEventCapor;

    @Captor
    private ArgumentCaptor<IEventsHandler.ReduceEvent> reduceEventCaptor;

    @Captor
    private ArgumentCaptor<IEventsHandler.RejectEvent> rejectEventCaptor;


    @BeforeEach
    public void before() {
        processor = new SimpleEventsProcessor4Test(handler);
    }

    @AfterEach()
    public void after(){

    }

    // -------------------------- fund tests ----------------------------------------
    // 用户资产增加时需要生产deposit fundEvent
    @Test
    public void testBalanceIncrease() {
        long delta = 100L;
        final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());
        container.setConsumer(processor);
        container.initOneUser(UID_1);
        container.initFutureSymbol(symbolId, quoteId);
        container.addMoneyToUser(UID_1, SYMBOL_MARGIN, delta);

        verify(handler, times(3)).commandResult(commandResultCaptor.capture());
        verify(handler, never()).reduceEvent(any());
        verify(handler, never()).tradeEvent(any());
        verify(handler, times(1)).fundsEvent(fundEventCapor.capture());

        IEventsHandler.FundsEvent fundEvent = fundEventCapor.getValue();
        assertThat(SYMBOL_MARGIN, Is.is(fundEvent.currency));
        assertThat(0, Is.is(fundEvent.symbol));
        assertThat(0L, Is.is(fundEvent.fee));
        assertThat(PositionDirection.EMPTY, Is.is(fundEvent.direction));
        assertThat(UID_1, Is.is(fundEvent.uid));
        assertThat(FundEvent.FundEventType.DEPOSIT, Is.is(fundEvent.eventType));
        assertThat(delta, Is.is(fundEvent.free));
        assertThat(0L, Is.is(fundEvent.locked));
        assertThat(0L, Is.is(fundEvent.openPriceAvg));
        assertThat(0L, Is.is(fundEvent.pnl));
        assertThat(0L, Is.is(fundEvent.position));
        assertThat(0L, Is.is(fundEvent.positionChanged));
        assertThat(0L, Is.is(fundEvent.tradePrice));
    }

    // 用户资产减少时需要生产withdraw fundEvent
    @Test
    public void testBalanceDecrease() {
        long deposit = 100L;
        long withdraw = -40L;
        final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());
        container.setConsumer(processor);
        container.initOneUser(UID_1);
        container.initFutureSymbol(symbolId, quoteId);
        container.addMoneyToUser(UID_1, SYMBOL_MARGIN, deposit);
        container.addMoneyToUser(UID_1, SYMBOL_MARGIN, withdraw);

        verify(handler, times(4)).commandResult(commandResultCaptor.capture());
        verify(handler, never()).reduceEvent(any());
        verify(handler, never()).tradeEvent(any());
        verify(handler, times(2)).fundsEvent(fundEventCapor.capture());

        List<IEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
        IEventsHandler.FundsEvent depositEvent = fundEvents.get(0);
        assertThat(UID_1, Is.is(depositEvent.uid));
        assertThat(SYMBOL_MARGIN, Is.is(depositEvent.currency));
        assertThat(0, Is.is(depositEvent.symbol));
        assertThat(0L, Is.is(depositEvent.fee));
        assertThat(PositionDirection.EMPTY, Is.is(depositEvent.direction));
        assertThat(FundEvent.FundEventType.DEPOSIT, Is.is(depositEvent.eventType));
        assertThat(deposit, Is.is(depositEvent.free));
        assertThat(0L, Is.is(depositEvent.locked));
        assertThat(0L, Is.is(depositEvent.openPriceAvg));
        assertThat(0L, Is.is(depositEvent.pnl));
        assertThat(0L, Is.is(depositEvent.position));
        assertThat(0L, Is.is(depositEvent.positionChanged));
        assertThat(0L, Is.is(depositEvent.tradePrice));

        IEventsHandler.FundsEvent withdrawEvent = fundEvents.get(1);
        assertThat(UID_1, Is.is(withdrawEvent.uid));
        assertThat(SYMBOL_MARGIN, Is.is(withdrawEvent.currency));
        assertThat(0, Is.is(withdrawEvent.symbol));
        assertThat(0L, Is.is(withdrawEvent.fee));
        assertThat(PositionDirection.EMPTY, Is.is(withdrawEvent.direction));
        assertThat(FundEvent.FundEventType.WITHDRAW, Is.is(withdrawEvent.eventType));
        assertThat(deposit + withdraw, Is.is(withdrawEvent.free));
        assertThat(0L, Is.is(withdrawEvent.locked));
        assertThat(0L, Is.is(withdrawEvent.openPriceAvg));
        assertThat(0L, Is.is(withdrawEvent.pnl));
        assertThat(0L, Is.is(withdrawEvent.position));
        assertThat(0L, Is.is(withdrawEvent.positionChanged));
        assertThat(0L, Is.is(withdrawEvent.tradePrice));
    }

    @Test
    public void testCancelSuccess() {
        final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());
        container.setConsumer(processor);
        container.initFutureSymbol(symbolId, quoteId);

        long deposit = 100L;
        long userId1 = container.createRandomUserWithMoney(deposit, quoteId);

        long orderId = container.createBid(userId1, 1, 10000, symbolId);
        // cancel order
        container.cancelOrder(userId1, orderId, symbolId);
        container.sleepSeconds(1);

        verify(handler, times(5)).commandResult(commandResultCaptor.capture());
        verify(handler, times(1)).reduceEvent(reduceEventCaptor.capture());
        verify(handler, never()).rejectEvent(any());
        verify(handler, times(2)).fundsEvent(fundEventCapor.capture());

        // deposit first
        List<IEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
        IEventsHandler.FundsEvent depositEvent = fundEvents.get(0);

        assertThat(userId1, Is.is(depositEvent.uid));
        assertThat(quoteId, Is.is(depositEvent.currency));
        assertThat(0, Is.is(depositEvent.symbol));
        assertThat(0L, Is.is(depositEvent.fee));
        assertThat(PositionDirection.EMPTY, Is.is(depositEvent.direction));
        assertThat(FundEvent.FundEventType.DEPOSIT, Is.is(depositEvent.eventType));
        assertThat(deposit, Is.is(depositEvent.free));
        assertThat(0L, Is.is(depositEvent.locked));
        assertThat(0L, Is.is(depositEvent.openPriceAvg));
        assertThat(0L, Is.is(depositEvent.pnl));
        assertThat(0L, Is.is(depositEvent.position));
        assertThat(0L, Is.is(depositEvent.positionChanged));
        assertThat(0L, Is.is(depositEvent.tradePrice));

        // place order second
        IEventsHandler.FundsEvent placeEvent = fundEvents.get(1);
        assertThat(userId1, Is.is(placeEvent.uid));
        assertThat(quoteId, Is.is(placeEvent.currency));
        assertThat(symbolId, Is.is(placeEvent.symbol));
        assertThat(0L, Is.is(placeEvent.fee));
        assertThat(PositionDirection.EMPTY, Is.is(placeEvent.direction));
        assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(placeEvent.eventType));
        assertThat(deposit, Is.is(placeEvent.free));
        assertThat(0L, Is.is(placeEvent.locked));
        assertThat(0L, Is.is(placeEvent.openPriceAvg));
        assertThat(0L, Is.is(placeEvent.pnl));
        assertThat(0L, Is.is(placeEvent.position));
        assertThat(0L, Is.is(placeEvent.positionChanged));
        assertThat(0L, Is.is(placeEvent.tradePrice));

        // TODO: should we fire UNLOCKED_PENDING here
        List<IEventsHandler.FundsEvent> events = fundEventCapor.getAllValues();
        IEventsHandler.FundsEvent event = fundEvents.get(0);

    }

    // 开仓事件
    @Test
    public void testOpenPosition() throws InterruptedException {
        final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());
        container.setConsumer(processor);
        container.initFutureSymbol(symbolId, quoteId);

        long deposit = 100L;
        long userId1 = container.createRandomUserWithMoney(deposit, quoteId);
        long userId2 = container.createRandomUserWithMoney(MAX_VALUE, quoteId);

        container.createBid(userId1, 1, 10000, symbolId);
        container.createAsk(userId2, 1, 10000, symbolId);
        Thread.sleep(1000L);

        verify(handler, times(7)).commandResult(commandResultCaptor.capture());
        verify(handler, never()).reduceEvent(any());
        verify(handler, never()).rejectEvent(any());
        verify(handler, times(1)).tradeEvent(tradeEventCaptor.capture());

        final IEventsHandler.TradeEvent tradeEvent = tradeEventCaptor.getAllValues().get(0);
        assertThat(tradeEvent.getSymbol(), Is.is(symbolId));
        assertThat(tradeEvent.getTotalVolume(), Is.is(1L));
        assertThat(tradeEvent.getTakerUid(), Is.is(userId2));
        assertThat(tradeEvent.getTakerAction(), Is.is(OrderAction.ASK));
        assertThat(tradeEvent.takeOrderCompleted, Is.is(true));
        assertThat(tradeEvent.trades.get(0).getMakerUid(), Is.is(userId1));
        assertThat(tradeEvent.trades.get(0).makerOrderCompleted, Is.is(true));
        assertThat(tradeEvent.trades.get(0).price, Is.is(10000L));
        assertThat(tradeEvent.trades.get(0).getVolume(), Is.is(1L));

        verify(handler, times(4)).fundsEvent(fundEventCapor.capture());

        // check fund event
        List<IEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
        IEventsHandler.FundsEvent takerEvent = fundEvents.get(2);
        assertThat(userId1, Is.is(takerEvent.uid));
        assertThat(quoteId, Is.is(takerEvent.currency));
        assertThat(symbolId, Is.is(takerEvent.symbol));
        assertThat(0L, Is.is(takerEvent.fee));
        assertThat(PositionDirection.EMPTY, Is.is(takerEvent.direction));
        assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(takerEvent.eventType));
        assertThat(deposit, Is.is(takerEvent.free));
        assertThat(0L, Is.is(takerEvent.locked));
        assertThat(0L, Is.is(takerEvent.openPriceAvg));
        assertThat(0L, Is.is(takerEvent.pnl));
        assertThat(0L, Is.is(takerEvent.position));
        assertThat(0L, Is.is(takerEvent.positionChanged));
        assertThat(0L, Is.is(takerEvent.tradePrice));

        IEventsHandler.FundsEvent makerEvent = fundEvents.get(3);
        assertThat(userId2, Is.is(makerEvent.uid));
        assertThat(quoteId, Is.is(makerEvent.currency));
        assertThat(symbolId, Is.is(makerEvent.symbol));
        assertThat(0L, Is.is(makerEvent.fee));
        assertThat(PositionDirection.EMPTY, Is.is(makerEvent.direction));
        assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(makerEvent.eventType));
        assertThat(MAX_VALUE, Is.is(makerEvent.free));
        assertThat(0L, Is.is(makerEvent.locked));
        assertThat(0L, Is.is(makerEvent.openPriceAvg));
        assertThat(0L, Is.is(makerEvent.pnl));
        assertThat(0L, Is.is(makerEvent.position));
        assertThat(0L, Is.is(makerEvent.positionChanged));
        assertThat(0L, Is.is(makerEvent.tradePrice));
    }

    // 平仓事件
    @Test
    public void testClosePosition()  {
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.initFutureSymbol(symbolId, quoteId);

            long deposit = 100L;
            long userId1 = container.createRandomUserWithMoney(deposit, quoteId);
            long userId2 = container.createRandomUserWithMoney(MAX_VALUE, quoteId);

            // 开仓成功
            long initId = container.createBid(userId1, 1, 10000, symbolId);
            long initId2 = container.createAsk(userId2, 1, 10000, symbolId);

            // 模拟市价波动
            int cnt = 1;
            for (int i = 0; i < cnt; i++) {
                container.updateCurrentPriceTo(10500, symbolId, quoteId);
            }

            // 平仓成功
            long askId = container.createAsk(userId1, 1, 10500, symbolId);
            long orderTmp = container.createBid(userId2, 1, 10500, symbolId);
            System.out.println(askId);
            System.out.println(orderTmp);
            Thread.sleep(1000L);

            verify(handler, times(cnt * 6 + 9)).commandResult(commandResultCaptor.capture());
            verify(handler, never()).reduceEvent(any());
            verify(handler, never()).rejectEvent(any());
            verify(handler, times(cnt + 2)).tradeEvent(tradeEventCaptor.capture());
            verify(handler, times(cnt * 4 + 6)).fundsEvent(fundEventCapor.capture());

//            container.printUser(userId1);
            // check fund event
            List<IEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();

            IEventsHandler.FundsEvent takerEvent = fundEvents.get(cnt * 4 + 6 - 2);
            assertThat(userId1, Is.is(takerEvent.uid));
            assertThat(quoteId, Is.is(takerEvent.currency));
            assertThat(symbolId, Is.is(takerEvent.symbol));
            assertThat(0L, Is.is(takerEvent.fee));
            assertThat(PositionDirection.EMPTY, Is.is(takerEvent.direction));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(takerEvent.eventType));
            assertThat(deposit, Is.is(takerEvent.free));
            assertThat(0L, Is.is(takerEvent.locked));
            assertThat(0L, Is.is(takerEvent.openPriceAvg));
            assertThat(0L, Is.is(takerEvent.pnl));
            assertThat(0L, Is.is(takerEvent.position));
            assertThat(0L, Is.is(takerEvent.positionChanged));
            assertThat(0L, Is.is(takerEvent.tradePrice));

            IEventsHandler.FundsEvent makerEvent = fundEvents.get(cnt * 4 + 6 - 1);
            assertThat(userId2, Is.is(makerEvent.uid));
            assertThat(quoteId, Is.is(makerEvent.currency));
            assertThat(symbolId, Is.is(makerEvent.symbol));
            assertThat(0L, Is.is(makerEvent.fee));
            assertThat(PositionDirection.EMPTY, Is.is(makerEvent.direction));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(makerEvent.eventType));
            assertThat(MAX_VALUE, Is.is(makerEvent.free));
            assertThat(0L, Is.is(makerEvent.locked));
            assertThat(0L, Is.is(makerEvent.openPriceAvg));
            assertThat(0L, Is.is(makerEvent.pnl));
            assertThat(0L, Is.is(makerEvent.position));
            assertThat(0L, Is.is(makerEvent.positionChanged));
            assertThat(0L, Is.is(makerEvent.tradePrice));

        } catch (Exception e) {

        }
    }

        @Test
    public void testAdjustment() {
        final int symbolId = SYMBOL_MARGIN;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initFeeSymbols();
            container.initFeeUsers();

            container.setConsumer(processor);

            container.submitCommandSync(builderPlace(symbolId, UID_1, ASK, GTC).orderId(101L).price(160000L).size(7L).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_2, ASK, GTC).orderId(202L).price(159900L).size(10L).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_3, ASK, GTC).orderId(303L).price(160000L).size(3L).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_3, ASK, GTC).orderId(304L).price(160500L).size(20L).build(), CommandResultCode.SUCCESS);


            long price = 160500L;
            int size = 20;

            container.submitCommandSync(builderPlace(symbolId, UID_4, BID, IOC).orderId(405L).price(price).reservePrice(price).size(size).build(), CommandResultCode.SUCCESS);

            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
        }
    }

    // -------------------------- buy no rejection tests -----------------------------

    @Test
    @Timeout(5)
    public void testMultiBuyNoRejectionMarginGtc() {
        testMultiBuy(SYMBOLSPECFEE_USD_JPY, GTC, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyNoRejectionExchangeGtc() {
        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, GTC, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyNoRejectionExchangeIoc() {
        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, IOC, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyNoRejectionMarginIoc() {
        testMultiBuy(SYMBOLSPECFEE_USD_JPY, IOC, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyNoRejectionExchangeFokB() {
        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyNoRejectionMarginFokB() {
        testMultiBuy(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.NO_REJECTION);
    }

    // -------------------------- buy with rejection tests -----------------------------

    @Test
    @Timeout(5)
    public void testMultiBuyWithRejectionMarginGtc() {
        testMultiBuy(SYMBOLSPECFEE_USD_JPY, GTC, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyWithRejectionExchangeGtc() {
        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, GTC, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyWithRejectionExchangeIoc() {
        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, IOC, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyWithRejectionMarginIoc() {
        testMultiBuy(SYMBOLSPECFEE_USD_JPY, IOC, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyWithSizeRejectionExchangeFokB() {
        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyWithSizeRejectionMarginFokB() {
        testMultiBuy(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyWithBudgetRejectionExchangeFokB() {
        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.REJECTION_BY_BUDGET);
    }

    @Test
    @Timeout(5)
    public void testMultiBuyWithBudgetRejectionMarginFokB() {
        testMultiBuy(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.REJECTION_BY_BUDGET);
    }

    // -------------------------- sell no rejection tests -----------------------------

    @Test
    @Timeout(5)
    public void testMultiSellNoRejectionMarginGtc() {
        testMultiSell(SYMBOLSPECFEE_USD_JPY, GTC, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiSellNoRejectionExchangeGtc() {
        testMultiSell(SYMBOLSPECFEE_XBT_LTC, GTC, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiSellNoRejectionMarginIoc() {
        testMultiSell(SYMBOLSPECFEE_USD_JPY, IOC, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiSellNoRejectionExchangeIoc() {
        testMultiSell(SYMBOLSPECFEE_XBT_LTC, IOC, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiSellNoRejectionMarginFokB() {
        testMultiSell(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.NO_REJECTION);
    }

    @Test
    @Timeout(5)
    public void testMultiSellNoRejectionExchangeFokB() {
        testMultiSell(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.NO_REJECTION);
    }

    // -------------------------- sell with rejection tests -----------------------------

    @Test
    @Timeout(5)
    public void testMultiSellWithRejectionMarginGtc() {
        testMultiSell(SYMBOLSPECFEE_USD_JPY, GTC, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiSellWithRejectionExchangeGtc() {
        testMultiSell(SYMBOLSPECFEE_XBT_LTC, GTC, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiSellWithRejectionMarginIoc() {
        testMultiSell(SYMBOLSPECFEE_USD_JPY, IOC, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiSellWithRejectionExchangeIoc() {
        testMultiSell(SYMBOLSPECFEE_XBT_LTC, IOC, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiSellWithSizeRejectionMarginFokB() {
        testMultiSell(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiSellWithSizeRejectionExchangeFokB() {
        testMultiSell(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.REJECTION_BY_SIZE);
    }

    @Test
    @Timeout(5)
    public void testMultiSellWithExpectationRejectionMarginFokB() {
        testMultiSell(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.REJECTION_BY_BUDGET);
    }

    @Test
    @Timeout(5)
    public void testMultiSellWithExpectationRejectionExchangeFokB() {
        testMultiSell(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.REJECTION_BY_BUDGET);
    }

    private PerformanceConfiguration getPerformanceConfiguration() {
        return PerformanceConfiguration.baseBuilder().build();
    }
    // ------------------------------------------------------------------------------

    private ApiPlaceOrder.ApiPlaceOrderBuilder builderPlace(int symbolId, long uid, OrderAction action, OrderType type) {
        return ApiPlaceOrder.builder().uid(uid).action(action).orderType(type).symbol(symbolId);
    }

    // TODO count/verify number of commands and events
    private void testMultiBuy(final CoreSymbolSpecification symbolSpec, final OrderType orderType, final RejectionCause rejectionCause) {

        final int symbolId = symbolSpec.symbolId;

        final long size = 40L + (rejectionCause == RejectionCause.REJECTION_BY_SIZE ? 1 : 0);

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initFeeSymbols();
            container.initFeeUsers();

            container.setConsumer(processor);

            container.submitCommandSync(builderPlace(symbolId, UID_1, ASK, GTC).orderId(101L).price(160000L).size(7L).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_2, ASK, GTC).orderId(202L).price(159900L).size(10L).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_3, ASK, GTC).orderId(303L).price(160000L).size(3L).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_3, ASK, GTC).orderId(304L).price(160500L).size(20L).build(), CommandResultCode.SUCCESS);


            long price = 160500L;
            if (orderType == FOK_BUDGET) {
                price = 160000L * 7L + 159900L * 10L + 160000L * 3L + 160500L * 20L + (rejectionCause == RejectionCause.REJECTION_BY_BUDGET ? -1 : 0);
            }

            container.submitCommandSync(builderPlace(symbolId, UID_4, BID, orderType).orderId(405L).price(price).reservePrice(price).size(size).build(), CommandResultCode.SUCCESS);

            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
        }

        verify(handler, times(5)).commandResult(commandResultCaptor.capture());
        verify(handler, never()).reduceEvent(any());

        if (orderType == FOK_BUDGET && rejectionCause != RejectionCause.NO_REJECTION) {
            // no trades for FoK
            verify(handler, never()).tradeEvent(any());

        } else {
            verify(handler, times(1)).tradeEvent(tradeEventCaptor.capture());

            // validating first event
            final IEventsHandler.TradeEvent tradeEvent = tradeEventCaptor.getAllValues().get(0);
            assertThat(tradeEvent.getSymbol(), Is.is(symbolId));
            assertThat(tradeEvent.getTotalVolume(), Is.is(40L));
            assertThat(tradeEvent.getTakerOrderId(), Is.is(405L));
            assertThat(tradeEvent.getTakerUid(), Is.is(UID_4));
            assertThat(tradeEvent.getTakerAction(), Is.is(OrderAction.BID));
            assertThat(tradeEvent.isTakeOrderCompleted(), Is.is(rejectionCause == RejectionCause.NO_REJECTION)); // completed only if no rejection was happened

            final List<IEventsHandler.Trade> trades = tradeEvent.getTrades();
            assertThat(trades.size(), Is.is(4));

            assertThat(trades.get(0).getMakerOrderId(), Is.is(202L));
            assertThat(trades.get(0).getMakerUid(), Is.is(UID_2));
            assertTrue(trades.get(0).isMakerOrderCompleted());
            assertThat(trades.get(0).getPrice(), Is.is(159900L));
            assertThat(trades.get(0).getVolume(), Is.is(10L));

            assertThat(trades.get(1).getMakerOrderId(), Is.is(101L));
            assertThat(trades.get(1).getMakerUid(), Is.is(UID_1));
            assertTrue(trades.get(1).isMakerOrderCompleted());
            assertThat(trades.get(1).getPrice(), Is.is(160000L));
            assertThat(trades.get(1).getVolume(), Is.is(7L));

            assertThat(trades.get(2).getMakerOrderId(), Is.is(303L));
            assertThat(trades.get(2).getMakerUid(), Is.is(UID_3));
            assertTrue(trades.get(2).isMakerOrderCompleted());
            assertThat(trades.get(2).getPrice(), Is.is(160000L));
            assertThat(trades.get(2).getVolume(), Is.is(3L));

            assertThat(trades.get(3).getMakerOrderId(), Is.is(304L));
            assertThat(trades.get(3).getMakerUid(), Is.is(UID_3));
            assertTrue(trades.get(3).isMakerOrderCompleted());
            assertThat(trades.get(3).getPrice(), Is.is(160500L));
            assertThat(trades.get(3).getVolume(), Is.is(20L));
        }

        if (rejectionCause != RejectionCause.NO_REJECTION && orderType != GTC) { // rejection can not happen for GTC orders
            verify(handler, times(1)).rejectEvent(rejectEventCaptor.capture());
            final IEventsHandler.RejectEvent rejectEvent = rejectEventCaptor.getValue();
            assertThat(rejectEvent.getSymbol(), Is.is(symbolId));
            assertThat(rejectEvent.getRejectedVolume(), Is.is((orderType == FOK_BUDGET) ? size : 1L));
            assertThat(rejectEvent.getOrderId(), Is.is(405L));
            assertThat(rejectEvent.getUid(), Is.is(UID_4));
        } else {
            verify(handler, never()).rejectEvent(any());
        }

    }

    private void testMultiSell(final CoreSymbolSpecification symbolSpec, final OrderType orderType, final RejectionCause rejectionCause) {

        final int symbolId = symbolSpec.symbolId;

        final long size = 22L + (rejectionCause == RejectionCause.REJECTION_BY_SIZE ? 1 : 0);

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initFeeSymbols();
            container.initFeeUsers();

            container.setConsumer(processor);

            long price = 159_900L;
            if (orderType == FOK_BUDGET) {
                price = 160_500L + 160_000L * 20L + 159_900L + (rejectionCause == RejectionCause.REJECTION_BY_BUDGET ? 1 : 0);
            }

            container.submitCommandSync(builderPlace(symbolId, UID_1, BID, GTC).orderId(101L).price(160_000L).reservePrice(166_000L).size(12L).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_2, BID, GTC).orderId(202L).price(159_900L).reservePrice(166_000L).size(1L).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_3, BID, GTC).orderId(303L).price(160_000L).reservePrice(166_000L).size(8L).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_3, BID, GTC).orderId(304L).price(160_500L).reservePrice(166_000L).size(1L).build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(builderPlace(symbolId, UID_4, ASK, orderType).orderId(405L).price(price).size(size).build(), CommandResultCode.SUCCESS);

            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
        }

        verify(handler, times(5)).commandResult(commandResultCaptor.capture());
        verify(handler, never()).reduceEvent(any());

        if (orderType == FOK_BUDGET && rejectionCause != RejectionCause.NO_REJECTION) {
            // no trades for FoK
            verify(handler, never()).tradeEvent(any());

        } else {
            verify(handler, times(1)).tradeEvent(tradeEventCaptor.capture());

            // validating first event
            final IEventsHandler.TradeEvent tradeEvent = tradeEventCaptor.getAllValues().get(0);
            assertThat(tradeEvent.getSymbol(), Is.is(symbolId));
            assertThat(tradeEvent.getTotalVolume(), Is.is(22L));
            assertThat(tradeEvent.getTakerOrderId(), Is.is(405L));
            assertThat(tradeEvent.getTakerUid(), Is.is(UID_4));
            assertThat(tradeEvent.getTakerAction(), Is.is(ASK));
            assertThat(tradeEvent.isTakeOrderCompleted(), Is.is(rejectionCause == RejectionCause.NO_REJECTION)); // completed only if no rejection was happened

            final List<IEventsHandler.Trade> trades = tradeEvent.getTrades();
            assertThat(trades.size(), Is.is(4));

            assertThat(trades.get(0).getMakerOrderId(), Is.is(304L));
            assertThat(trades.get(0).getMakerUid(), Is.is(UID_3));
            assertTrue(trades.get(0).isMakerOrderCompleted());
            assertThat(trades.get(0).getPrice(), Is.is(160500L));
            assertThat(trades.get(0).getVolume(), Is.is(1L));

            assertThat(trades.get(1).getMakerOrderId(), Is.is(101L));
            assertThat(trades.get(1).getMakerUid(), Is.is(UID_1));
            assertTrue(trades.get(1).isMakerOrderCompleted());
            assertThat(trades.get(1).getPrice(), Is.is(160000L));
            assertThat(trades.get(1).getVolume(), Is.is(12L));

            assertThat(trades.get(2).getMakerOrderId(), Is.is(303L));
            assertThat(trades.get(2).getMakerUid(), Is.is(UID_3));
            assertTrue(trades.get(2).isMakerOrderCompleted());
            assertThat(trades.get(2).getPrice(), Is.is(160000L));
            assertThat(trades.get(2).getVolume(), Is.is(8L));

            assertThat(trades.get(3).getMakerOrderId(), Is.is(202L));
            assertThat(trades.get(3).getMakerUid(), Is.is(UID_2));
            assertTrue(trades.get(3).isMakerOrderCompleted());
            assertThat(trades.get(3).getPrice(), Is.is(159900L));
            assertThat(trades.get(3).getVolume(), Is.is(1L));
        }

        if (rejectionCause != RejectionCause.NO_REJECTION && orderType != GTC) { // rejection can not happen for GTC orders
            verify(handler, times(1)).rejectEvent(rejectEventCaptor.capture());
            final IEventsHandler.RejectEvent rejectEvent = rejectEventCaptor.getValue();
            assertThat(rejectEvent.getSymbol(), Is.is(symbolId));
            assertThat(rejectEvent.getRejectedVolume(), Is.is((orderType == FOK_BUDGET) ? size : 1L));
            assertThat(rejectEvent.getOrderId(), Is.is(405L));
            assertThat(rejectEvent.getUid(), Is.is(UID_4));
        } else {
            verify(handler, never()).rejectEvent(any());
        }

    }

    enum RejectionCause {
        NO_REJECTION,
        REJECTION_BY_SIZE,
        REJECTION_BY_BUDGET
    }

}