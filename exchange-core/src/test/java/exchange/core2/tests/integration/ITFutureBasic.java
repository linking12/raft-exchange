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

import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.IFundEventsHandler;
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
    private ArgumentCaptor<ITradeEventsHandler.ApiCommandResult> commandResultCaptor;

    @Captor
    private ArgumentCaptor<ITradeEventsHandler.TradeEvent> tradeEventCaptor;

    @Captor
    private ArgumentCaptor<IFundEventsHandler.FundsEvent> fundEventCapor;

    @Captor
    private ArgumentCaptor<ITradeEventsHandler.ReduceEvent> reduceEventCaptor;

    @Captor
    private ArgumentCaptor<ITradeEventsHandler.RejectEvent> rejectEventCaptor;


    @BeforeEach
    public void before() {
        processor = new SimpleEventsProcessor4Test(handler);
    }

    @AfterEach()
    public void after() {

    }

    // -------------------------- fund tests ----------------------------------------
    // 用户资产增加时需要生产deposit fundEvent
    @Test
    public void testBalanceIncrease() {
        long delta = 100L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.setConsumer(processor);
            container.initOneUser(UID_1);
            container.initFutureSymbol(symbolId, quoteId);
            container.addMoneyToUser(UID_1, SYMBOL_MARGIN, delta);

            verify(handler, times(4)).commandResult(commandResultCaptor.capture());
            verify(handler, never()).reduceEvent(any());
            verify(handler, never()).tradeEvent(any());

        } finally {
            verify(handler, times(1)).fundsEvent(fundEventCapor.capture());

            IFundEventsHandler.FundsEvent fundEvent = fundEventCapor.getValue();
            assertThat(UID_1, Is.is(fundEvent.uid));
            assertThat(SYMBOL_MARGIN, Is.is(fundEvent.currency));
            assertThat(delta, Is.is(fundEvent.free));
            assertThat(0, Is.is(fundEvent.symbol));
            assertThat(0L, Is.is(fundEvent.fee));
            assertThat(PositionDirection.EMPTY, Is.is(fundEvent.direction));
            assertThat(FundEvent.FundEventType.DEPOSIT, Is.is(fundEvent.eventType));
            assertThat(0L, Is.is(fundEvent.locked));
            assertThat(0L, Is.is(fundEvent.openPriceAvg));
            assertThat(0L, Is.is(fundEvent.pnl));
            assertThat(0L, Is.is(fundEvent.position));
            assertThat(0L, Is.is(fundEvent.positionChanged));
            assertThat(0L, Is.is(fundEvent.tradePrice));
        }
    }

    // 用户资产减少时需要生产withdraw fundEvent
    @Test
    public void testBalanceDecrease() {
        long deposit = 100L;
        long withdraw = -40L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.initOneUser(UID_1);
            container.initFutureSymbol(symbolId, quoteId);
            container.addMoneyToUser(UID_1, SYMBOL_MARGIN, deposit);
            container.addMoneyToUser(UID_1, SYMBOL_MARGIN, withdraw);

            verify(handler, times(5)).commandResult(commandResultCaptor.capture());
            verify(handler, never()).reduceEvent(any());
            verify(handler, never()).tradeEvent(any());

        } finally {
            verify(handler, times(2)).fundsEvent(fundEventCapor.capture());

            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
            IFundEventsHandler.FundsEvent depositEvent = fundEvents.get(0);
            assertThat(UID_1, Is.is(depositEvent.uid));
            assertThat(SYMBOL_MARGIN, Is.is(depositEvent.currency));
            assertThat(deposit, Is.is(depositEvent.free));
            assertThat(0, Is.is(depositEvent.symbol));
            assertThat(0L, Is.is(depositEvent.fee));
            assertThat(PositionDirection.EMPTY, Is.is(depositEvent.direction));
            assertThat(FundEvent.FundEventType.DEPOSIT, Is.is(depositEvent.eventType));
            assertThat(0L, Is.is(depositEvent.locked));
            assertThat(0L, Is.is(depositEvent.openPriceAvg));
            assertThat(0L, Is.is(depositEvent.pnl));
            assertThat(0L, Is.is(depositEvent.position));
            assertThat(0L, Is.is(depositEvent.positionChanged));
            assertThat(0L, Is.is(depositEvent.tradePrice));

            IFundEventsHandler.FundsEvent withdrawEvent = fundEvents.get(1);
            assertThat(UID_1, Is.is(withdrawEvent.uid));
            assertThat(SYMBOL_MARGIN, Is.is(withdrawEvent.currency));
            assertThat(deposit + withdraw, Is.is(withdrawEvent.free));
            assertThat(0, Is.is(withdrawEvent.symbol));
            assertThat(0L, Is.is(withdrawEvent.fee));
            assertThat(PositionDirection.EMPTY, Is.is(withdrawEvent.direction));
            assertThat(FundEvent.FundEventType.WITHDRAW, Is.is(withdrawEvent.eventType));
            assertThat(0L, Is.is(withdrawEvent.locked));
            assertThat(0L, Is.is(withdrawEvent.openPriceAvg));
            assertThat(0L, Is.is(withdrawEvent.pnl));
            assertThat(0L, Is.is(withdrawEvent.position));
            assertThat(0L, Is.is(withdrawEvent.positionChanged));
            assertThat(0L, Is.is(withdrawEvent.tradePrice));
        }
    }

    @Test
    public void testCancelSuccess() {
        long deposit = 100L;
        long userId1 = 1003L;
        long orderId = 1005L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.initFutureSymbol(symbolId, quoteId);

            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createBidWithOrderId(orderId, userId1, 1, 10000, symbolId);

            // cancel order
            container.cancelOrder(userId1, orderId, symbolId);
            container.sleepSeconds(1);

            verify(handler, times(5)).commandResult(commandResultCaptor.capture());
            verify(handler, times(1)).reduceEvent(reduceEventCaptor.capture());
            verify(handler, never()).rejectEvent(any());

        } finally {
            verify(handler, times(3)).fundsEvent(fundEventCapor.capture());
            // deposit first
            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
            IFundEventsHandler.FundsEvent depositEvent = fundEvents.get(0);

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

            // lock pending second, should lock money
            IFundEventsHandler.FundsEvent placeEvent = fundEvents.get(1);
            assertThat(userId1, Is.is(placeEvent.uid));
            assertThat(quoteId, Is.is(placeEvent.currency));
            assertThat(symbolId, Is.is(placeEvent.symbol));
            assertThat(0L, Is.is(placeEvent.fee));
            assertThat(PositionDirection.EMPTY, Is.is(placeEvent.direction));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(placeEvent.eventType));
            assertThat(0L, Is.is(placeEvent.free));
            assertThat(100L, Is.is(placeEvent.locked));
            assertThat(0L, Is.is(placeEvent.openPriceAvg));
            assertThat(0L, Is.is(placeEvent.pnl));
            assertThat(0L, Is.is(placeEvent.position));
            assertThat(0L, Is.is(placeEvent.positionChanged));
            assertThat(0L, Is.is(placeEvent.tradePrice));

            // unlock pending event, free should be returned back
            IFundEventsHandler.FundsEvent unLockEvent = fundEvents.get(2);
            assertThat(userId1, Is.is(unLockEvent.uid));
            assertThat(quoteId, Is.is(unLockEvent.currency));
            assertThat(symbolId, Is.is(unLockEvent.symbol));
            assertThat(0L, Is.is(unLockEvent.fee));
            assertThat(PositionDirection.EMPTY, Is.is(unLockEvent.direction));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(unLockEvent.eventType));
            assertThat(100L, Is.is(unLockEvent.free));
            assertThat(0L, Is.is(unLockEvent.locked));
            assertThat(0L, Is.is(unLockEvent.openPriceAvg));
            assertThat(0L, Is.is(unLockEvent.pnl));
            assertThat(0L, Is.is(unLockEvent.position));
            assertThat(0L, Is.is(unLockEvent.positionChanged));
            assertThat(0L, Is.is(unLockEvent.tradePrice));
        }
    }

    // 开仓事件
    @Test
    public void testOpenPosition() throws InterruptedException {
        int deposit = 100;
        long userId1 = 1003L;
        long userId2 = 1004L;
        long makerOrderId = 1005L;
        long takerOrderId = 1006L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.initFutureSymbol(symbolId, quoteId);

            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            container.createBidWithOrderId(makerOrderId, userId1, 1, 10000, symbolId);
            container.createAskWithOrderId(takerOrderId, userId2, 1, 10000, symbolId);

            verify(handler, times(7)).commandResult(commandResultCaptor.capture());
            verify(handler, never()).reduceEvent(any());
            verify(handler, never()).rejectEvent(any());
            verify(handler, times(1)).tradeEvent(tradeEventCaptor.capture());

            final ITradeEventsHandler.TradeEvent tradeEvent = tradeEventCaptor.getAllValues().get(0);
            assertThat(tradeEvent.getSymbol(), Is.is(symbolId));
            assertThat(tradeEvent.getTotalVolume(), Is.is(1L));
            assertThat(tradeEvent.getTakerOrderId(), Is.is(takerOrderId));
            assertThat(tradeEvent.getTakerUid(), Is.is(userId2));
            assertThat(tradeEvent.getTakerAction(), Is.is(OrderAction.ASK));
            assertThat(tradeEvent.takeOrderCompleted, Is.is(true));
            assertThat(tradeEvent.trades.get(0).getMakerUid(), Is.is(userId1));
            assertThat(tradeEvent.trades.get(0).makerOrderId, Is.is(makerOrderId));
            assertThat(tradeEvent.trades.get(0).makerOrderCompleted, Is.is(true));
            assertThat(tradeEvent.trades.get(0).price, Is.is(10000L));
            assertThat(tradeEvent.trades.get(0).getVolume(), Is.is(1L));

        } finally {
            verify(handler, times(8)).fundsEvent(fundEventCapor.capture());
            // check fund event
            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
            IFundEventsHandler.FundsEvent takerEvent = fundEvents.get(2);

            // check lock_pending event for maker, orderId should be maker's
            assertThat(userId1, Is.is(takerEvent.uid));
            assertThat(quoteId, Is.is(takerEvent.currency));
            assertThat(symbolId, Is.is(takerEvent.symbol));
            assertThat(makerOrderId, Is.is(takerEvent.orderId));
            assertThat(0L, Is.is(takerEvent.fee));
            assertThat(PositionDirection.EMPTY, Is.is(takerEvent.direction));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(takerEvent.eventType));
            assertThat(0L, Is.is(takerEvent.free));
            assertThat(100L, Is.is(takerEvent.locked));
            assertThat(0L, Is.is(takerEvent.openPriceAvg));
            assertThat(0L, Is.is(takerEvent.pnl));
            assertThat(0L, Is.is(takerEvent.position));
            assertThat(0L, Is.is(takerEvent.positionChanged));
            assertThat(0L, Is.is(takerEvent.tradePrice));

            // check lock_pending event for taker, orderId should be maker's
            IFundEventsHandler.FundsEvent makerEvent = fundEvents.get(3);
            assertThat(userId2, Is.is(makerEvent.uid));
            assertThat(quoteId, Is.is(makerEvent.currency));
            assertThat(symbolId, Is.is(makerEvent.symbol));
            assertThat(makerOrderId, Is.is(takerEvent.orderId));
            assertThat(0L, Is.is(makerEvent.fee));
            assertThat(PositionDirection.EMPTY, Is.is(makerEvent.direction));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(makerEvent.eventType));
            assertThat(MAX_VALUE - 100, Is.is(makerEvent.free));
            assertThat(100L, Is.is(makerEvent.locked));
            assertThat(0L, Is.is(makerEvent.openPriceAvg));
            assertThat(0L, Is.is(makerEvent.pnl));
            assertThat(0L, Is.is(makerEvent.position));
            assertThat(0L, Is.is(makerEvent.positionChanged));
            assertThat(0L, Is.is(makerEvent.tradePrice));

            // check unlock_pending event for taker
            IFundEventsHandler.FundsEvent takerUnlockEvent = fundEvents.get(4);
            assertThat(userId2, Is.is(takerUnlockEvent.uid));
            assertThat(quoteId, Is.is(takerUnlockEvent.currency));
            assertThat(symbolId, Is.is(takerUnlockEvent.symbol));
            assertThat(takerOrderId, Is.is(takerUnlockEvent.orderId));
            assertThat(0L, Is.is(takerUnlockEvent.fee));
            assertThat(PositionDirection.EMPTY, Is.is(takerUnlockEvent.direction));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(takerUnlockEvent.eventType));
            assertThat(MAX_VALUE, Is.is(takerUnlockEvent.free));
            assertThat(0L, Is.is(takerUnlockEvent.locked));
            assertThat(0L, Is.is(takerUnlockEvent.openPriceAvg));
            assertThat(0L, Is.is(takerUnlockEvent.pnl));
            assertThat(0L, Is.is(takerUnlockEvent.position));
            assertThat(0L, Is.is(takerUnlockEvent.positionChanged));
            assertThat(0L, Is.is(takerUnlockEvent.tradePrice));

            // check open position event for taker
            IFundEventsHandler.FundsEvent takerOpenPositionEvent = fundEvents.get(5);
            assertThat(userId2, Is.is(takerOpenPositionEvent.uid));
            assertThat(quoteId, Is.is(takerOpenPositionEvent.currency));
            assertThat(symbolId, Is.is(takerOpenPositionEvent.symbol));
            assertThat(takerOrderId, Is.is(takerOpenPositionEvent.orderId));
            assertThat(20L, Is.is(takerOpenPositionEvent.fee));
            assertThat(PositionDirection.SHORT, Is.is(takerOpenPositionEvent.direction));
            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(takerOpenPositionEvent.eventType));
            // free = init value - cost - fee
            assertThat(MAX_VALUE - 100 - 20, Is.is(takerOpenPositionEvent.free));
            assertThat(100L, Is.is(takerOpenPositionEvent.locked));
            assertThat(0L, Is.is(takerOpenPositionEvent.openPriceAvg));
            assertThat(0L, Is.is(takerOpenPositionEvent.pnl));
            assertThat(1L, Is.is(takerOpenPositionEvent.position));
            assertThat(1L, Is.is(takerOpenPositionEvent.positionChanged));
            assertThat(10000L, Is.is(takerOpenPositionEvent.tradePrice));

            // check unlock_pending event for maker
            IFundEventsHandler.FundsEvent makerUnlockEvent = fundEvents.get(6);
            assertThat(userId1, Is.is(makerUnlockEvent.uid));
            assertThat(quoteId, Is.is(makerUnlockEvent.currency));
            assertThat(symbolId, Is.is(makerUnlockEvent.symbol));
            assertThat(makerOrderId, Is.is(makerUnlockEvent.orderId));
            assertThat(0L, Is.is(makerUnlockEvent.fee));
            assertThat(PositionDirection.EMPTY, Is.is(makerUnlockEvent.direction));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(makerUnlockEvent.eventType));
            assertThat(100L, Is.is(makerUnlockEvent.free));
            assertThat(0L, Is.is(makerUnlockEvent.locked));
            assertThat(0L, Is.is(makerUnlockEvent.openPriceAvg));
            assertThat(0L, Is.is(makerUnlockEvent.pnl));
            assertThat(0L, Is.is(makerUnlockEvent.position));
            assertThat(0L, Is.is(makerUnlockEvent.positionChanged));
            assertThat(0L, Is.is(makerUnlockEvent.tradePrice));

            // check open position event
            IFundEventsHandler.FundsEvent makerOpenPositionEvent = fundEvents.get(7);
            assertThat(userId1, Is.is(makerOpenPositionEvent.uid));
            assertThat(quoteId, Is.is(makerOpenPositionEvent.currency));
            assertThat(symbolId, Is.is(makerOpenPositionEvent.symbol));
            assertThat(makerOrderId, Is.is(makerOpenPositionEvent.orderId));
            assertThat(10L, Is.is(makerOpenPositionEvent.fee));
            assertThat(PositionDirection.LONG, Is.is(makerOpenPositionEvent.direction));
            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(makerOpenPositionEvent.eventType));
            // this is not a desire
            assertThat(-10L, Is.is(makerOpenPositionEvent.free));
            assertThat(100L, Is.is(makerOpenPositionEvent.locked));
            assertThat(0L, Is.is(makerOpenPositionEvent.openPriceAvg));
            assertThat(0L, Is.is(makerOpenPositionEvent.pnl));
            assertThat(1L, Is.is(makerOpenPositionEvent.position));
            assertThat(1L, Is.is(makerOpenPositionEvent.positionChanged));
            assertThat(10000L, Is.is(makerOpenPositionEvent.tradePrice));
        }
    }

    // 平仓事件
    @Test
    public void testClosePosition() {
        int deposit = 100;
        long userId1 = 1003L;
        long userId2 = 1004L;
        long makerOrderId1 = 1005L;
        long takerOrderId2 = 1006L;
        long makerOrderId3 = 1007L;
        long takerOrderId4 = 1008L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.initFutureSymbol(symbolId, quoteId);

            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            // 开仓成功
            container.createBidWithOrderId(makerOrderId1, userId1, 1, 10000, symbolId);
            container.createAskWithOrderId(takerOrderId2, userId2, 1, 10000, symbolId);

            // 平仓成功
            container.createAskWithOrderId(makerOrderId3, userId1, 1, 10500, symbolId);
            container.createBidWithOrderId(takerOrderId4, userId2, 1, 10500, symbolId);

            verify(handler, times(9)).commandResult(commandResultCaptor.capture());
            verify(handler, never()).reduceEvent(any());
            verify(handler, never()).rejectEvent(any());
            verify(handler, times(2)).tradeEvent(tradeEventCaptor.capture());

        } finally {
            verify(handler, times(14)).fundsEvent(fundEventCapor.capture());
            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();

            IFundEventsHandler.FundsEvent takerCloseEvent = fundEvents.get(11);
            assertThat(userId2, Is.is(takerCloseEvent.uid));
            assertThat(quoteId, Is.is(takerCloseEvent.currency));
            assertThat(symbolId, Is.is(takerCloseEvent.symbol));
            // 关仓不收手续费
            assertThat(0L, Is.is(takerCloseEvent.fee));
            assertThat(PositionDirection.EMPTY, Is.is(takerCloseEvent.direction));
            assertThat(FundEvent.FundEventType.CLOSE_POSITION, Is.is(takerCloseEvent.eventType));
            // free is not correct
            assertThat(3999980L, Is.is(takerCloseEvent.free));
            assertThat(0L, Is.is(takerCloseEvent.locked));
            assertThat(10000L, Is.is(takerCloseEvent.openPriceAvg));
            assertThat(-500L, Is.is(takerCloseEvent.pnl));
            assertThat(0L, Is.is(takerCloseEvent.position));
            assertThat(1L, Is.is(takerCloseEvent.positionChanged));
            // trade price?
            assertThat(10500L, Is.is(takerCloseEvent.tradePrice));

            IFundEventsHandler.FundsEvent makerCloseEvent = fundEvents.get(13);
            assertThat(userId1, Is.is(makerCloseEvent.uid));
            assertThat(quoteId, Is.is(makerCloseEvent.currency));
            assertThat(symbolId, Is.is(makerCloseEvent.symbol));
            assertThat(0L, Is.is(makerCloseEvent.fee));
            assertThat(PositionDirection.EMPTY, Is.is(makerCloseEvent.direction));
            assertThat(FundEvent.FundEventType.CLOSE_POSITION, Is.is(makerCloseEvent.eventType));
            // free = init money - fee
            assertThat(90L, Is.is(makerCloseEvent.free));
            assertThat(0L, Is.is(makerCloseEvent.locked));
            assertThat(10000L, Is.is(makerCloseEvent.openPriceAvg));
            assertThat(500L, Is.is(makerCloseEvent.pnl));
            assertThat(0L, Is.is(makerCloseEvent.position));
            assertThat(1L, Is.is(makerCloseEvent.positionChanged));
            // trade price?
            assertThat(10500L, Is.is(makerCloseEvent.tradePrice));
        }
    }

    // 强制平仓事件
    @Test
    public void testForceClosePosition() {
        int deposit = 100;
        long userId1 = 1003L;
        long userId2 = 1004L;
        long makerOrderId1 = 1005L;
        long takerOrderId2 = 1006L;
        int cnt = 500;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.initFutureSymbol(symbolId, quoteId);

            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            // 开仓成功
            container.createBidWithOrderId(makerOrderId1, userId1, 1, 10000, symbolId);
            container.createAskWithOrderId(takerOrderId2, userId2, 1, 10000, symbolId);

            // 模拟行情变动
            for (int i = 0; i < cnt; i++) {
                container.updateCurrentPriceTo(500, symbolId, quoteId);
            }

        } finally {
            verify(handler, times(cnt * 8 + 8)).fundsEvent(fundEventCapor.capture());
            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();

            IFundEventsHandler.FundsEvent takerCloseEvent = fundEvents.get(0);
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
            final ITradeEventsHandler.TradeEvent tradeEvent = tradeEventCaptor.getAllValues().get(0);
            assertThat(tradeEvent.getSymbol(), Is.is(symbolId));
            assertThat(tradeEvent.getTotalVolume(), Is.is(40L));
            assertThat(tradeEvent.getTakerOrderId(), Is.is(405L));
            assertThat(tradeEvent.getTakerUid(), Is.is(UID_4));
            assertThat(tradeEvent.getTakerAction(), Is.is(OrderAction.BID));
            assertThat(tradeEvent.isTakeOrderCompleted(), Is.is(rejectionCause == RejectionCause.NO_REJECTION)); // completed only if no rejection was happened

            final List<ITradeEventsHandler.Trade> trades = tradeEvent.getTrades();
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
            final ITradeEventsHandler.RejectEvent rejectEvent = rejectEventCaptor.getValue();
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
            final ITradeEventsHandler.TradeEvent tradeEvent = tradeEventCaptor.getAllValues().get(0);
            assertThat(tradeEvent.getSymbol(), Is.is(symbolId));
            assertThat(tradeEvent.getTotalVolume(), Is.is(22L));
            assertThat(tradeEvent.getTakerOrderId(), Is.is(405L));
            assertThat(tradeEvent.getTakerUid(), Is.is(UID_4));
            assertThat(tradeEvent.getTakerAction(), Is.is(ASK));
            assertThat(tradeEvent.isTakeOrderCompleted(), Is.is(rejectionCause == RejectionCause.NO_REJECTION)); // completed only if no rejection was happened

            final List<ITradeEventsHandler.Trade> trades = tradeEvent.getTrades();
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
            final ITradeEventsHandler.RejectEvent rejectEvent = rejectEventCaptor.getValue();
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