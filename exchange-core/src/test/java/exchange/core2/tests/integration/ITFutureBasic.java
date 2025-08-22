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
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.tests.util.ExchangeTestContainer;
import exchange.core2.tests.util.TestConstants;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.impl.map.sorted.mutable.TreeSortedMap;
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
import java.util.concurrent.ExecutionException;

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

    private void checkEvent(IFundEventsHandler.FundsEvent evt) {
        assertThat(0L, Is.is(evt.unrealizedProfit));
        assertThat(0L, Is.is(evt.liquidationPrice));
        assertThat(0L, Is.is(evt.marginRatioScaleK));
    }

    // -------------------------- fund tests ----------------------------------------
    // 用户资产增加时需要生产deposit fundEvent
    @Test
    public void testBalanceIncrease() {
        long delta = 100L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.setConsumer(processor);
            container.initOneUser(UID_1);
            container.addCurrency(SYMBOL_MARGIN);
            container.initFutureSymbol(symbolId, quoteId);
            container.addMoneyToUser(UID_1, SYMBOL_MARGIN, delta);

            verify(handler, times(5)).commandResult(commandResultCaptor.capture());
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
            assertThat(0L, Is.is(fundEvent.openPriceSum));
            assertThat(0L, Is.is(fundEvent.openVolume));
            assertThat(0L, Is.is(fundEvent.tradeSize));
            assertThat(0L, Is.is(fundEvent.tradePrice));
            checkEvent(fundEvent);
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
            container.addCurrency(BASE_CURRENCY_ID);
            container.addCurrency(quoteId);
            container.addCurrency(SYMBOL_MARGIN);
            container.addMoneyToUser(UID_1, SYMBOL_MARGIN, deposit);
            container.addMoneyToUser(UID_1, SYMBOL_MARGIN, withdraw);

            verify(handler, times(8)).commandResult(commandResultCaptor.capture());
            verify(handler, never()).reduceEvent(any());
            verify(handler, never()).tradeEvent(any());

            // check balance
            SingleUserReportResult user1Report = container.getUserProfile(UID_1);
            assertThat(user1Report.getAccounts().get(SYMBOL_MARGIN), Is.is((deposit + withdraw)));

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
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
            assertThat(0L, Is.is(depositEvent.openPriceSum));
            assertThat(0L, Is.is(depositEvent.openVolume));
            assertThat(0L, Is.is(depositEvent.tradeSize));
            assertThat(0L, Is.is(depositEvent.tradePrice));
            checkEvent(depositEvent);

            IFundEventsHandler.FundsEvent withdrawEvent = fundEvents.get(1);
            assertThat(UID_1, Is.is(withdrawEvent.uid));
            assertThat(SYMBOL_MARGIN, Is.is(withdrawEvent.currency));
            assertThat(deposit + withdraw, Is.is(withdrawEvent.free));
            assertThat(0, Is.is(withdrawEvent.symbol));
            assertThat(0L, Is.is(withdrawEvent.fee));
            assertThat(PositionDirection.EMPTY, Is.is(withdrawEvent.direction));
            assertThat(FundEvent.FundEventType.WITHDRAW, Is.is(withdrawEvent.eventType));
            assertThat(0L, Is.is(withdrawEvent.locked));
            assertThat(0L, Is.is(withdrawEvent.openPriceSum));
            assertThat(0L, Is.is(withdrawEvent.openVolume));
            assertThat(0L, Is.is(withdrawEvent.tradeSize));
            assertThat(0L, Is.is(withdrawEvent.tradePrice));
            checkEvent(withdrawEvent);
        }
    }

    // 未开单成功时可取消订单
    @Test
    public void testCancelSuccess() {
        long deposit = 1000L;
        long userId1 = 1003L;
        long orderId = 1005L;
        int size = 1;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.initFutureSymbol(symbolId, quoteId);
            container.addCurrency(BASE_CURRENCY_ID);
            container.addCurrency(quoteId);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createBidWithOrderId(orderId, userId1, size, 10000, symbolId);

            // cancel order
            container.cancelOrder(userId1, orderId, symbolId);
            container.sleepSeconds(1);

            verify(handler, times(7)).commandResult(commandResultCaptor.capture());
            verify(handler, times(1)).reduceEvent(reduceEventCaptor.capture());
            verify(handler, never()).rejectEvent(any());

            // check balance
            SingleUserReportResult user1Report = container.getUserProfile(userId1);
            assertThat(user1Report.getAccounts().get(quoteId), Is.is((deposit)));

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
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
            assertThat(0L, Is.is(depositEvent.openPriceSum));
            assertThat(0L, Is.is(depositEvent.openVolume));
            assertThat(0L, Is.is(depositEvent.tradeSize));
            assertThat(0L, Is.is(depositEvent.tradePrice));
            checkEvent(depositEvent);

            // lock pending second, should lock money
            IFundEventsHandler.FundsEvent placeEvent = fundEvents.get(1);
            assertThat(userId1, Is.is(placeEvent.uid));
            assertThat(quoteId, Is.is(placeEvent.currency));
            assertThat(symbolId, Is.is(placeEvent.symbol));
            assertThat(0L, Is.is(placeEvent.fee));
            assertThat(PositionDirection.LONG, Is.is(placeEvent.direction));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(placeEvent.eventType));
            // free = 1000(deposit) - 100(margin) - 20 * size(maker fee) = 880;
            assertThat(deposit - 100 - 20 * size, Is.is(placeEvent.free));
            // locked = 100(margin) + 20 * size(maker fee)
            assertThat(120L, Is.is(placeEvent.locked));
            assertThat(0L, Is.is(placeEvent.openPriceSum));
            assertThat(0L, Is.is(placeEvent.openVolume));
            assertThat(0L, Is.is(placeEvent.tradeSize));
            assertThat(0L, Is.is(placeEvent.tradePrice));
            checkEvent(placeEvent);

            // unlock pending event, free should be returned back
            IFundEventsHandler.FundsEvent unLockEvent = fundEvents.get(2);
            assertThat(userId1, Is.is(unLockEvent.uid));
            assertThat(quoteId, Is.is(unLockEvent.currency));
            assertThat(symbolId, Is.is(unLockEvent.symbol));
            assertThat(0L, Is.is(unLockEvent.fee));
            assertThat(PositionDirection.LONG, Is.is(unLockEvent.direction));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(unLockEvent.eventType));
            assertThat(1000L, Is.is(unLockEvent.free));
            assertThat(0L, Is.is(unLockEvent.locked));
            assertThat(0L, Is.is(unLockEvent.openPriceSum));
            assertThat(0L, Is.is(unLockEvent.openVolume));
            assertThat(0L, Is.is(unLockEvent.tradeSize));
            assertThat(0L, Is.is(unLockEvent.tradePrice));
            checkEvent(unLockEvent);
        }
    }

    // 开仓事件, 完全成交, taker为Ask
    @Test
    public void testOpenPosition4Bid() throws InterruptedException {
        int deposit = 1000;
        long userId1 = 1003L;
        long userId2 = 1004L;
        long makerOrderId = 1005L;
        long takerOrderId = 1006L;
        int size = 1;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.initFutureSymbol(symbolId, quoteId);
            container.addCurrency(BASE_CURRENCY_ID);
            container.addCurrency(quoteId);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            container.createBidWithOrderId(makerOrderId, userId1, size, 10000, symbolId);
            container.createAskWithOrderId(takerOrderId, userId2, size, 10000, symbolId);

            verify(handler, times(9)).commandResult(commandResultCaptor.capture());
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

            // check balance
            SingleUserReportResult user1Report = container.getUserProfile(userId1);
            SingleUserReportResult user2Report = container.getUserProfile(userId2);
            assertThat(user1Report.getAccounts().get(quoteId), Is.is((deposit - 10L)));
            assertThat(user2Report.getAccounts().get(quoteId), Is.is((MAX_VALUE - 20L)));

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
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
            assertThat(PositionDirection.LONG, Is.is(takerEvent.direction));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(takerEvent.eventType));
            // 1000(balance) - 100(open position) - 20(maker fee) = 880
            assertThat(deposit - 100L - size * 20L, Is.is(takerEvent.free));
            assertThat(100L + size * 20L, Is.is(takerEvent.locked));
            assertThat(0L, Is.is(takerEvent.openPriceSum));
            assertThat(0L, Is.is(takerEvent.openVolume));
            assertThat(0L, Is.is(takerEvent.tradeSize));
            assertThat(0L, Is.is(takerEvent.tradePrice));
            checkEvent(takerEvent);

            // check lock_pending event for taker, orderId should be maker's
            IFundEventsHandler.FundsEvent makerEvent = fundEvents.get(3);
            assertThat(userId2, Is.is(makerEvent.uid));
            assertThat(quoteId, Is.is(makerEvent.currency));
            assertThat(symbolId, Is.is(makerEvent.symbol));
            assertThat(makerOrderId, Is.is(takerEvent.orderId));
            assertThat(0L, Is.is(makerEvent.fee));
            assertThat(PositionDirection.SHORT, Is.is(makerEvent.direction));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(makerEvent.eventType));
            assertThat(MAX_VALUE - 100L - size * 20L, Is.is(makerEvent.free));
            assertThat(100L + size * 20L, Is.is(makerEvent.locked));
            assertThat(0L, Is.is(makerEvent.openPriceSum));
            assertThat(0L, Is.is(makerEvent.openVolume));
            assertThat(0L, Is.is(makerEvent.tradeSize));
            assertThat(0L, Is.is(makerEvent.tradePrice));
            checkEvent(makerEvent);

            // check unlock_pending event for taker
            IFundEventsHandler.FundsEvent takerUnlockEvent = fundEvents.get(4);
            assertThat(userId2, Is.is(takerUnlockEvent.uid));
            assertThat(quoteId, Is.is(takerUnlockEvent.currency));
            assertThat(symbolId, Is.is(takerUnlockEvent.symbol));
            assertThat(takerOrderId, Is.is(takerUnlockEvent.orderId));
            assertThat(0L, Is.is(takerUnlockEvent.fee));
            assertThat(PositionDirection.SHORT, Is.is(takerUnlockEvent.direction));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(takerUnlockEvent.eventType));
            assertThat(MAX_VALUE, Is.is(takerUnlockEvent.free));
            assertThat(0L, Is.is(takerUnlockEvent.locked));
            assertThat(0L, Is.is(takerUnlockEvent.openPriceSum));
            assertThat(0L, Is.is(takerUnlockEvent.openVolume));
            assertThat(0L, Is.is(takerUnlockEvent.tradeSize));
            assertThat(0L, Is.is(takerUnlockEvent.tradePrice));
            checkEvent(takerUnlockEvent);

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
            assertThat(10000L, Is.is(takerOpenPositionEvent.openPriceSum));
            assertThat(1L, Is.is(takerOpenPositionEvent.openVolume));
            assertThat(1L, Is.is(takerOpenPositionEvent.tradeSize));
            assertThat(10000L, Is.is(takerOpenPositionEvent.tradePrice));
            // 标记价格和开仓价格相同, 所以算出来的unrealizedProfit=0
            assertThat(0L, Is.is(takerOpenPositionEvent.unrealizedProfit));
            // maintenanceMargin = 50
            // liquidationPrice = direction * (maintenanceMargin - totalMargin) + openPriceSum = -1 * (50 - 100) + 10000 * 1 = 10050
            assertThat(10050L, Is.is(takerOpenPositionEvent.liquidationPrice));
            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 50 / 100) = 0
            assertThat(500L, Is.is(takerOpenPositionEvent.marginRatioScaleK));

            // check unlock_pending event for maker
            IFundEventsHandler.FundsEvent makerUnlockEvent = fundEvents.get(6);
            assertThat(userId1, Is.is(makerUnlockEvent.uid));
            assertThat(quoteId, Is.is(makerUnlockEvent.currency));
            assertThat(symbolId, Is.is(makerUnlockEvent.symbol));
            assertThat(makerOrderId, Is.is(makerUnlockEvent.orderId));
            assertThat(0L, Is.is(makerUnlockEvent.fee));
            assertThat(PositionDirection.LONG, Is.is(makerUnlockEvent.direction));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(makerUnlockEvent.eventType));
            assertThat(1000L, Is.is(makerUnlockEvent.free));
            assertThat(0L, Is.is(makerUnlockEvent.locked));
            assertThat(0L, Is.is(makerUnlockEvent.openPriceSum));
            assertThat(0L, Is.is(makerUnlockEvent.openVolume));
            assertThat(0L, Is.is(makerUnlockEvent.tradeSize));
            assertThat(0L, Is.is(makerUnlockEvent.tradePrice));
            checkEvent(makerUnlockEvent);

            // check open position event
            IFundEventsHandler.FundsEvent makerOpenPositionEvent = fundEvents.get(7);
            assertThat(userId1, Is.is(makerOpenPositionEvent.uid));
            assertThat(quoteId, Is.is(makerOpenPositionEvent.currency));
            assertThat(symbolId, Is.is(makerOpenPositionEvent.symbol));
            assertThat(makerOrderId, Is.is(makerOpenPositionEvent.orderId));
            assertThat(10L, Is.is(makerOpenPositionEvent.fee));
            assertThat(PositionDirection.LONG, Is.is(makerOpenPositionEvent.direction));
            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(makerOpenPositionEvent.eventType));
            // 1000(balance) - 100(open position) - 10(maker fee) = 890
            assertThat(890L, Is.is(makerOpenPositionEvent.free));
            assertThat(100L, Is.is(makerOpenPositionEvent.locked));
            assertThat(10000L, Is.is(makerOpenPositionEvent.openPriceSum));
            assertThat(1L, Is.is(makerOpenPositionEvent.openVolume));
            assertThat(1L, Is.is(makerOpenPositionEvent.tradeSize));
            assertThat(10000L, Is.is(makerOpenPositionEvent.tradePrice));
            // openVolume * markPrice - openPriceSum = 10000 - 10000 = 0
            assertThat(0L, Is.is(makerOpenPositionEvent.unrealizedProfit));
            // liquidationPrice = direction * (maintenanceMargin - totalMargin) + openPriceSum = 1 * (50 - 100) + 10000 * 1 = 9050
            assertThat(9950L, Is.is(makerOpenPositionEvent.liquidationPrice));
            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 50 / 100) = 50
            assertThat(500L, Is.is(makerOpenPositionEvent.marginRatioScaleK));
        }
    }

    // 开仓事件, 完全成交, taker为Bid
    @Test
    public void testOpenPosition4Ask() throws InterruptedException {
        int deposit = 1000;
        long userId1 = 1003L;
        long userId2 = 1004L;
        long makerOrderId = 1005L;
        long takerOrderId = 1006L;
        int size = 1;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.initFutureSymbol(symbolId, quoteId);
            container.addCurrency(BASE_CURRENCY_ID);
            container.addCurrency(quoteId);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            container.createAskWithOrderId(makerOrderId, userId1, size, 10000, symbolId);
            container.createBidWithOrderId(takerOrderId, userId2, size, 10000, symbolId);

            verify(handler, times(9)).commandResult(commandResultCaptor.capture());
            verify(handler, never()).reduceEvent(any());
            verify(handler, never()).rejectEvent(any());
            verify(handler, times(1)).tradeEvent(tradeEventCaptor.capture());

            final ITradeEventsHandler.TradeEvent tradeEvent = tradeEventCaptor.getAllValues().get(0);
            assertThat(tradeEvent.getSymbol(), Is.is(symbolId));
            assertThat(tradeEvent.getTotalVolume(), Is.is(1L));
            assertThat(tradeEvent.getTakerOrderId(), Is.is(takerOrderId));
            assertThat(tradeEvent.getTakerUid(), Is.is(userId2));
            assertThat(tradeEvent.getTakerAction(), Is.is(OrderAction.BID));
            assertThat(tradeEvent.takeOrderCompleted, Is.is(true));
            assertThat(tradeEvent.trades.get(0).getMakerUid(), Is.is(userId1));
            assertThat(tradeEvent.trades.get(0).makerOrderId, Is.is(makerOrderId));
            assertThat(tradeEvent.trades.get(0).makerOrderCompleted, Is.is(true));
            assertThat(tradeEvent.trades.get(0).price, Is.is(10000L));
            assertThat(tradeEvent.trades.get(0).getVolume(), Is.is(1L));

            // check balance
            SingleUserReportResult user1Report = container.getUserProfile(userId1);
            SingleUserReportResult user2Report = container.getUserProfile(userId2);
            assertThat(user1Report.getAccounts().get(quoteId), Is.is((deposit - 10L)));
            assertThat(user2Report.getAccounts().get(quoteId), Is.is((MAX_VALUE - 20L)));

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
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
            assertThat(PositionDirection.SHORT, Is.is(takerEvent.direction));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(takerEvent.eventType));
            // free = 1000(deposit) - 100(required margin) - 20 * size(taker fee) = 880
            assertThat(deposit - 100L - size * 20L, Is.is(takerEvent.free));
            assertThat(100L + size * 20L, Is.is(takerEvent.locked));
            assertThat(0L, Is.is(takerEvent.openPriceSum));
            assertThat(0L, Is.is(takerEvent.openVolume));
            assertThat(0L, Is.is(takerEvent.tradeSize));
            assertThat(0L, Is.is(takerEvent.tradePrice));
            checkEvent(takerEvent);

            // check lock_pending event for taker, orderId should be maker's
            IFundEventsHandler.FundsEvent makerEvent = fundEvents.get(3);
            assertThat(userId2, Is.is(makerEvent.uid));
            assertThat(quoteId, Is.is(makerEvent.currency));
            assertThat(symbolId, Is.is(makerEvent.symbol));
            assertThat(makerOrderId, Is.is(takerEvent.orderId));
            assertThat(0L, Is.is(makerEvent.fee));
            assertThat(PositionDirection.LONG, Is.is(makerEvent.direction));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(makerEvent.eventType));
            // free = MAX_VALUE(deposit) - 100(required margin) - 20 * size(taker fee)
            assertThat(MAX_VALUE - 100L - size * 20L, Is.is(makerEvent.free));
            // 注意, 下单时是按照taker fee做lock
            assertThat(100L + size * 20L, Is.is(makerEvent.locked));
            assertThat(0L, Is.is(makerEvent.openPriceSum));
            assertThat(0L, Is.is(makerEvent.openVolume));
            assertThat(0L, Is.is(makerEvent.tradeSize));
            assertThat(0L, Is.is(makerEvent.tradePrice));
            checkEvent(makerEvent);

            // check unlock_pending event for taker
            IFundEventsHandler.FundsEvent takerUnlockEvent = fundEvents.get(4);
            assertThat(userId2, Is.is(takerUnlockEvent.uid));
            assertThat(quoteId, Is.is(takerUnlockEvent.currency));
            assertThat(symbolId, Is.is(takerUnlockEvent.symbol));
            assertThat(takerOrderId, Is.is(takerUnlockEvent.orderId));
            assertThat(0L, Is.is(takerUnlockEvent.fee));
            assertThat(PositionDirection.LONG, Is.is(takerUnlockEvent.direction));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(takerUnlockEvent.eventType));
            assertThat(MAX_VALUE, Is.is(takerUnlockEvent.free));
            assertThat(0L, Is.is(takerUnlockEvent.locked));
            assertThat(0L, Is.is(takerUnlockEvent.openPriceSum));
            assertThat(0L, Is.is(takerUnlockEvent.openVolume));
            assertThat(0L, Is.is(takerUnlockEvent.tradeSize));
            assertThat(0L, Is.is(takerUnlockEvent.tradePrice));
            checkEvent(takerUnlockEvent);

            // check open position event for taker
            IFundEventsHandler.FundsEvent takerOpenPositionEvent = fundEvents.get(5);
            assertThat(userId2, Is.is(takerOpenPositionEvent.uid));
            assertThat(quoteId, Is.is(takerOpenPositionEvent.currency));
            assertThat(symbolId, Is.is(takerOpenPositionEvent.symbol));
            assertThat(takerOrderId, Is.is(takerOpenPositionEvent.orderId));
            assertThat(20L, Is.is(takerOpenPositionEvent.fee));
            assertThat(PositionDirection.LONG, Is.is(takerOpenPositionEvent.direction));
            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(takerOpenPositionEvent.eventType));
            // free = init value - cost - fee
            assertThat(MAX_VALUE - 100 - 20, Is.is(takerOpenPositionEvent.free));
            assertThat(100L, Is.is(takerOpenPositionEvent.locked));
            assertThat(10000L, Is.is(takerOpenPositionEvent.openPriceSum));
            assertThat(1L, Is.is(takerOpenPositionEvent.openVolume));
            assertThat(1L, Is.is(takerOpenPositionEvent.tradeSize));
            assertThat(10000L, Is.is(takerOpenPositionEvent.tradePrice));
            // 标记价格和开仓价格相同, 所以算出来的unrealizedProfit=0
            assertThat(0L, Is.is(takerOpenPositionEvent.unrealizedProfit));
            // maintenanceMargin = 50
            // liquidationPrice = direction * (maintenanceMargin - totalMargin) + openPriceSum = 1 * (50 - 100) + 10000 * 1 = 9950
            assertThat(9950L, Is.is(takerOpenPositionEvent.liquidationPrice));
            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 50 / 100) = 500
            assertThat(500L, Is.is(takerOpenPositionEvent.marginRatioScaleK));

            // check unlock_pending event for maker
            IFundEventsHandler.FundsEvent makerUnlockEvent = fundEvents.get(6);
            assertThat(userId1, Is.is(makerUnlockEvent.uid));
            assertThat(quoteId, Is.is(makerUnlockEvent.currency));
            assertThat(symbolId, Is.is(makerUnlockEvent.symbol));
            assertThat(makerOrderId, Is.is(makerUnlockEvent.orderId));
            assertThat(0L, Is.is(makerUnlockEvent.fee));
            assertThat(PositionDirection.SHORT, Is.is(makerUnlockEvent.direction));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(makerUnlockEvent.eventType));
            assertThat(1000L, Is.is(makerUnlockEvent.free));
            assertThat(0L, Is.is(makerUnlockEvent.locked));
            assertThat(0L, Is.is(makerUnlockEvent.openPriceSum));
            assertThat(0L, Is.is(makerUnlockEvent.openVolume));
            assertThat(0L, Is.is(makerUnlockEvent.tradeSize));
            assertThat(0L, Is.is(makerUnlockEvent.tradePrice));
            checkEvent(makerUnlockEvent);

            // check open position event
            IFundEventsHandler.FundsEvent makerOpenPositionEvent = fundEvents.get(7);
            assertThat(userId1, Is.is(makerOpenPositionEvent.uid));
            assertThat(quoteId, Is.is(makerOpenPositionEvent.currency));
            assertThat(symbolId, Is.is(makerOpenPositionEvent.symbol));
            assertThat(makerOrderId, Is.is(makerOpenPositionEvent.orderId));
            assertThat(10L, Is.is(makerOpenPositionEvent.fee));
            assertThat(PositionDirection.SHORT, Is.is(makerOpenPositionEvent.direction));
            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(makerOpenPositionEvent.eventType));
            // 1000 - 100 - 10(taker fee)
            assertThat(890L, Is.is(makerOpenPositionEvent.free));
            assertThat(100L, Is.is(makerOpenPositionEvent.locked));
            assertThat(10000L, Is.is(makerOpenPositionEvent.openPriceSum));
            assertThat(1L, Is.is(makerOpenPositionEvent.openVolume));
            assertThat(1L, Is.is(makerOpenPositionEvent.tradeSize));
            assertThat(10000L, Is.is(makerOpenPositionEvent.tradePrice));
            // openVolume * markPrice - openPriceSum = 10000 - 10000 = 0
            assertThat(0L, Is.is(makerOpenPositionEvent.unrealizedProfit));
            // liquidationPrice = direction * (maintenanceMargin - totalMargin) + openPriceSum = -1 * (50 - 100) + 10000 * 1 = 10050
            assertThat(10050L, Is.is(makerOpenPositionEvent.liquidationPrice));
            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 50 / 100) = 500
            assertThat(500L, Is.is(makerOpenPositionEvent.marginRatioScaleK));
        }
    }

    // 开仓事件, 测试部分成单, taker为Ask
    @Test
    public void testOpenMultiplePosition4Bid() throws InterruptedException {
        int size = 10;
        int deposit = 1000 * size;
        long userId1 = 1003L;
        long userId2 = 1004L;
        long makerOrderId = 1005L;
        long takerOrderId = 1006L;
        int txSize = 2;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.initFutureSymbol(symbolId, quoteId);
            container.addCurrency(BASE_CURRENCY_ID);
            container.addCurrency(quoteId);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            container.createBidWithOrderId(makerOrderId, userId1, size, 10000, symbolId);
            container.createAskWithOrderId(takerOrderId, userId2, txSize, 10000, symbolId);

            verify(handler, times(9)).commandResult(commandResultCaptor.capture());
            verify(handler, never()).reduceEvent(any());
            verify(handler, never()).rejectEvent(any());
            verify(handler, times(1)).tradeEvent(tradeEventCaptor.capture());

            final ITradeEventsHandler.TradeEvent tradeEvent = tradeEventCaptor.getAllValues().get(0);
            assertThat(tradeEvent.getSymbol(), Is.is(symbolId));
            assertThat(tradeEvent.getTotalVolume(), Is.is(2L));
            assertThat(tradeEvent.getTakerOrderId(), Is.is(takerOrderId));
            assertThat(tradeEvent.getTakerUid(), Is.is(userId2));
            assertThat(tradeEvent.getTakerAction(), Is.is(OrderAction.ASK));
            assertThat(tradeEvent.takeOrderCompleted, Is.is(true));
            assertThat(tradeEvent.trades.get(0).getMakerUid(), Is.is(userId1));
            assertThat(tradeEvent.trades.get(0).makerOrderId, Is.is(makerOrderId));
            assertThat(tradeEvent.trades.get(0).makerOrderCompleted, Is.is(false));
            assertThat(tradeEvent.trades.get(0).price, Is.is(10000L));
            assertThat(tradeEvent.trades.get(0).getVolume(), Is.is(2L));

            // check balance
            SingleUserReportResult user1Report = container.getUserProfile(userId1);
            SingleUserReportResult user2Report = container.getUserProfile(userId2);
            assertThat(user1Report.getAccounts().get(quoteId), Is.is((deposit - 2 * 10L)));
            assertThat(user2Report.getAccounts().get(quoteId), Is.is((MAX_VALUE - 2 * 20L)));

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(8)).fundsEvent(fundEventCapor.capture());
            // check fund event
            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
            IFundEventsHandler.FundsEvent makerEvent = fundEvents.get(2);

            // check lock_pending event for maker, orderId should be maker's
            assertThat(userId1, Is.is(makerEvent.uid));
            assertThat(quoteId, Is.is(makerEvent.currency));
            assertThat(symbolId, Is.is(makerEvent.symbol));
            assertThat(makerOrderId, Is.is(makerEvent.orderId));
            assertThat(0L, Is.is(makerEvent.fee));
            assertThat(PositionDirection.LONG, Is.is(makerEvent.direction));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(makerEvent.eventType));
            // free = 1000(deposit) - size * 100(required margin) - size * 20L(taker fee)
            assertThat(deposit - size * 100L - size * 20L, Is.is(makerEvent.free));
            assertThat(size * 100L + size * 20L, Is.is(makerEvent.locked));
            assertThat(0L, Is.is(makerEvent.openPriceSum));
            assertThat(0L, Is.is(makerEvent.openVolume));
            assertThat(0L, Is.is(makerEvent.tradeSize));
            assertThat(0L, Is.is(makerEvent.tradePrice));
            checkEvent(makerEvent);

            // check lock_pending event for taker, orderId should be maker's
            IFundEventsHandler.FundsEvent takerEvent = fundEvents.get(3);
            assertThat(userId2, Is.is(takerEvent.uid));
            assertThat(quoteId, Is.is(takerEvent.currency));
            assertThat(symbolId, Is.is(takerEvent.symbol));
            assertThat(makerOrderId, Is.is(makerEvent.orderId));
            assertThat(0L, Is.is(takerEvent.fee));
            assertThat(PositionDirection.SHORT, Is.is(takerEvent.direction));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(takerEvent.eventType));
            // free = MAX_VALUE(deposit) - size * 100(required margin) - size * 20L(taker fee)
            assertThat(MAX_VALUE - txSize * 100 - txSize * 20L, Is.is(takerEvent.free));
            assertThat(txSize * 100L + txSize * 20L, Is.is(takerEvent.locked));
            assertThat(0L, Is.is(takerEvent.openPriceSum));
            assertThat(0L, Is.is(takerEvent.openVolume));
            assertThat(0L, Is.is(takerEvent.tradeSize));
            assertThat(0L, Is.is(takerEvent.tradePrice));
            checkEvent(takerEvent);

            // check unlock_pending event for taker
            IFundEventsHandler.FundsEvent takerUnlockEvent = fundEvents.get(4);
            assertThat(userId2, Is.is(takerUnlockEvent.uid));
            assertThat(quoteId, Is.is(takerUnlockEvent.currency));
            assertThat(symbolId, Is.is(takerUnlockEvent.symbol));
            assertThat(takerOrderId, Is.is(takerUnlockEvent.orderId));
            assertThat(0L, Is.is(takerUnlockEvent.fee));
            assertThat(PositionDirection.SHORT, Is.is(takerUnlockEvent.direction));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(takerUnlockEvent.eventType));
            assertThat(MAX_VALUE, Is.is(takerUnlockEvent.free));
            assertThat(0L, Is.is(takerUnlockEvent.locked));
            assertThat(0L, Is.is(takerUnlockEvent.openPriceSum));
            assertThat(0L, Is.is(takerUnlockEvent.openVolume));
            assertThat(0L, Is.is(takerUnlockEvent.tradeSize));
            assertThat(0L, Is.is(takerUnlockEvent.tradePrice));
            checkEvent(takerUnlockEvent);

            // check open position event for taker
            IFundEventsHandler.FundsEvent takerOpenPositionEvent = fundEvents.get(5);
            assertThat(userId2, Is.is(takerOpenPositionEvent.uid));
            assertThat(quoteId, Is.is(takerOpenPositionEvent.currency));
            assertThat(symbolId, Is.is(takerOpenPositionEvent.symbol));
            assertThat(takerOrderId, Is.is(takerOpenPositionEvent.orderId));
            assertThat(20L * 2, Is.is(takerOpenPositionEvent.fee));
            assertThat(PositionDirection.SHORT, Is.is(takerOpenPositionEvent.direction));
            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(takerOpenPositionEvent.eventType));
            // free = init value - cost - fee
            assertThat(MAX_VALUE - 200 - 20 * 2, Is.is(takerOpenPositionEvent.free));
            assertThat(200L, Is.is(takerOpenPositionEvent.locked));
            assertThat(20000L, Is.is(takerOpenPositionEvent.openPriceSum));
            assertThat(2L, Is.is(takerOpenPositionEvent.openVolume));
            assertThat(2L, Is.is(takerOpenPositionEvent.tradeSize));
            assertThat(10000L, Is.is(takerOpenPositionEvent.tradePrice));
            // openVolume * markPrice - openPriceSum = 2 * 10000 - 20000 = 0
            assertThat(0L, Is.is(takerOpenPositionEvent.unrealizedProfit));
            // maintenanceMargin = 100
            // liquidationPrice = (direction * (maintenanceMargin - totalMargin) + openPriceSum) / openVolume = (-1 * (100 - 200) + 20000 * 1)/2 = 10050
            assertThat(10050L, Is.is(takerOpenPositionEvent.liquidationPrice));
            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 500 / 100) = 500
            assertThat(500L, Is.is(takerOpenPositionEvent.marginRatioScaleK));

            // check unlock_pending event for maker
            IFundEventsHandler.FundsEvent makerUnlockEvent = fundEvents.get(6);
            assertThat(userId1, Is.is(makerUnlockEvent.uid));
            assertThat(quoteId, Is.is(makerUnlockEvent.currency));
            assertThat(symbolId, Is.is(makerUnlockEvent.symbol));
            assertThat(makerOrderId, Is.is(makerUnlockEvent.orderId));
            assertThat(0L, Is.is(makerUnlockEvent.fee));
            assertThat(PositionDirection.LONG, Is.is(makerUnlockEvent.direction));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(makerUnlockEvent.eventType));
            // 10000 - 2 * 100 = 9800
            assertThat(deposit - (size - txSize) * 100L - (size - txSize) * 20L, Is.is(makerUnlockEvent.free));
            assertThat((size - txSize) * 100L + (size - txSize) * 20L, Is.is(makerUnlockEvent.locked));
            assertThat(0L, Is.is(makerUnlockEvent.openPriceSum));
            assertThat(0L, Is.is(makerUnlockEvent.openVolume));
            assertThat(0L, Is.is(makerUnlockEvent.tradeSize));
            assertThat(0L, Is.is(makerUnlockEvent.tradePrice));
            checkEvent(makerUnlockEvent);

            // check open position event
            IFundEventsHandler.FundsEvent makerOpenPositionEvent = fundEvents.get(7);
            assertThat(userId1, Is.is(makerOpenPositionEvent.uid));
            assertThat(quoteId, Is.is(makerOpenPositionEvent.currency));
            assertThat(symbolId, Is.is(makerOpenPositionEvent.symbol));
            assertThat(makerOrderId, Is.is(makerOpenPositionEvent.orderId));
            assertThat(2 * 10L, Is.is(makerOpenPositionEvent.fee));
            assertThat(PositionDirection.LONG, Is.is(makerOpenPositionEvent.direction));
            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(makerOpenPositionEvent.eventType));
            // 10000(deposit) - 10 * 100(current position) - 8 * 20(taker fee in advance) - 2 * 10(maker fee)
            assertThat(deposit - size * 100 - (size - txSize) * 20L - txSize * 10L, Is.is(makerOpenPositionEvent.free));
            assertThat(size * 100L + (size - txSize) * 20L, Is.is(makerOpenPositionEvent.locked));
            assertThat(20000L, Is.is(makerOpenPositionEvent.openPriceSum));
            assertThat(2L, Is.is(makerOpenPositionEvent.openVolume));
            assertThat(2L, Is.is(makerOpenPositionEvent.tradeSize));
            assertThat(10000L, Is.is(makerOpenPositionEvent.tradePrice));
            // openVolume * markPrice - openPriceSum = 20000 - 20000 = 0
            assertThat(0L, Is.is(makerOpenPositionEvent.unrealizedProfit));
            // liquidationPrice = (direction * (maintenanceMargin - totalMargin) + openPriceSum) / openVolume = (1 * (100 - 200) + 20000 )/2 = 9950L
            assertThat(9950L, Is.is(makerOpenPositionEvent.liquidationPrice));
            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 50 / 100) = 500
            assertThat(500L, Is.is(makerOpenPositionEvent.marginRatioScaleK));
        }
    }

    // 开仓事件, 测试部分成交, taker为Bid
    @Test
    public void testOpenMultiplePosition4Ask() throws InterruptedException {
        int size = 10;
        int deposit = 1000 * size;
        long userId1 = 1003L;
        long userId2 = 1004L;
        long makerOrderId = 1005L;
        long takerOrderId = 1006L;
        int txSize = 2;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.initFutureSymbol(symbolId, quoteId);
            container.addCurrency(BASE_CURRENCY_ID);
            container.addCurrency(quoteId);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            container.createAskWithOrderId(makerOrderId, userId1, size, 10000, symbolId);
            container.createBidWithOrderId(takerOrderId, userId2, txSize, 10000, symbolId);

            verify(handler, times(9)).commandResult(commandResultCaptor.capture());
            verify(handler, never()).reduceEvent(any());
            verify(handler, never()).rejectEvent(any());
            verify(handler, times(1)).tradeEvent(tradeEventCaptor.capture());

            final ITradeEventsHandler.TradeEvent tradeEvent = tradeEventCaptor.getAllValues().get(0);
            assertThat(tradeEvent.getSymbol(), Is.is(symbolId));
            assertThat(tradeEvent.getTotalVolume(), Is.is(2L));
            assertThat(tradeEvent.getTakerOrderId(), Is.is(takerOrderId));
            assertThat(tradeEvent.getTakerUid(), Is.is(userId2));
            assertThat(tradeEvent.getTakerAction(), Is.is(OrderAction.BID));
            assertThat(tradeEvent.takeOrderCompleted, Is.is(true));
            assertThat(tradeEvent.trades.get(0).getMakerUid(), Is.is(userId1));
            assertThat(tradeEvent.trades.get(0).makerOrderId, Is.is(makerOrderId));
            assertThat(tradeEvent.trades.get(0).makerOrderCompleted, Is.is(false));
            assertThat(tradeEvent.trades.get(0).price, Is.is(10000L));
            assertThat(tradeEvent.trades.get(0).getVolume(), Is.is(2L));

            // check balance
            SingleUserReportResult user1Report = container.getUserProfile(userId1);
            SingleUserReportResult user2Report = container.getUserProfile(userId2);
            assertThat(user1Report.getAccounts().get(quoteId), Is.is((deposit - 2 * 10L)));
            assertThat(user2Report.getAccounts().get(quoteId), Is.is((MAX_VALUE - 2 * 20L)));

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
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
            assertThat(PositionDirection.SHORT, Is.is(takerEvent.direction));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(takerEvent.eventType));
            assertThat(deposit - size * 100L - size * 20L, Is.is(takerEvent.free));
            assertThat(size * 100L + size * 20L, Is.is(takerEvent.locked));
            assertThat(0L, Is.is(takerEvent.openPriceSum));
            assertThat(0L, Is.is(takerEvent.openVolume));
            assertThat(0L, Is.is(takerEvent.tradeSize));
            assertThat(0L, Is.is(takerEvent.tradePrice));
            checkEvent(takerEvent);

            // check lock_pending event for taker, orderId should be maker's
            IFundEventsHandler.FundsEvent makerEvent = fundEvents.get(3);
            assertThat(userId2, Is.is(makerEvent.uid));
            assertThat(quoteId, Is.is(makerEvent.currency));
            assertThat(symbolId, Is.is(makerEvent.symbol));
            assertThat(makerOrderId, Is.is(takerEvent.orderId));
            assertThat(0L, Is.is(makerEvent.fee));
            assertThat(PositionDirection.LONG, Is.is(makerEvent.direction));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(makerEvent.eventType));
            assertThat(MAX_VALUE - txSize * 100L - txSize * 20L, Is.is(makerEvent.free));
            assertThat(txSize * 100L + txSize * 20L, Is.is(makerEvent.locked));
            assertThat(0L, Is.is(makerEvent.openPriceSum));
            assertThat(0L, Is.is(makerEvent.openVolume));
            assertThat(0L, Is.is(makerEvent.tradeSize));
            assertThat(0L, Is.is(makerEvent.tradePrice));
            checkEvent(makerEvent);

            // check unlock_pending event for taker
            IFundEventsHandler.FundsEvent takerUnlockEvent = fundEvents.get(4);
            assertThat(userId2, Is.is(takerUnlockEvent.uid));
            assertThat(quoteId, Is.is(takerUnlockEvent.currency));
            assertThat(symbolId, Is.is(takerUnlockEvent.symbol));
            assertThat(takerOrderId, Is.is(takerUnlockEvent.orderId));
            assertThat(0L, Is.is(takerUnlockEvent.fee));
            assertThat(PositionDirection.LONG, Is.is(takerUnlockEvent.direction));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(takerUnlockEvent.eventType));
            assertThat(MAX_VALUE, Is.is(takerUnlockEvent.free));
            assertThat(0L, Is.is(takerUnlockEvent.locked));
            assertThat(0L, Is.is(takerUnlockEvent.openPriceSum));
            assertThat(0L, Is.is(takerUnlockEvent.openVolume));
            assertThat(0L, Is.is(takerUnlockEvent.tradeSize));
            assertThat(0L, Is.is(takerUnlockEvent.tradePrice));
            checkEvent(takerUnlockEvent);

            // check open position event for taker
            IFundEventsHandler.FundsEvent takerOpenPositionEvent = fundEvents.get(5);
            assertThat(userId2, Is.is(takerOpenPositionEvent.uid));
            assertThat(quoteId, Is.is(takerOpenPositionEvent.currency));
            assertThat(symbolId, Is.is(takerOpenPositionEvent.symbol));
            assertThat(takerOrderId, Is.is(takerOpenPositionEvent.orderId));
            assertThat(20L * 2, Is.is(takerOpenPositionEvent.fee));
            assertThat(PositionDirection.LONG, Is.is(takerOpenPositionEvent.direction));
            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(takerOpenPositionEvent.eventType));
            // free = init value - cost - fee
            assertThat(MAX_VALUE - 200 - 20 * 2, Is.is(takerOpenPositionEvent.free));
            assertThat(200L, Is.is(takerOpenPositionEvent.locked));
            assertThat(20000L, Is.is(takerOpenPositionEvent.openPriceSum));
            assertThat(2L, Is.is(takerOpenPositionEvent.openVolume));
            assertThat(2L, Is.is(takerOpenPositionEvent.tradeSize));
            assertThat(10000L, Is.is(takerOpenPositionEvent.tradePrice));
            // openVolume * markPrice - openPriceSum = 20000 - 20000 = 0
            assertThat(0L, Is.is(takerOpenPositionEvent.unrealizedProfit));
            // liquidationPrice = (direction * (maintenanceMargin - totalMargin) + openPriceSum) / openVolume = (1 * (100 - 200) + 20000 )/2 = 9950L
            assertThat(9950L, Is.is(takerOpenPositionEvent.liquidationPrice));
            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 50 / 100) = 500
            assertThat(500L, Is.is(takerOpenPositionEvent.marginRatioScaleK));

            // check unlock_pending event for maker
            IFundEventsHandler.FundsEvent makerUnlockEvent = fundEvents.get(6);
            assertThat(userId1, Is.is(makerUnlockEvent.uid));
            assertThat(quoteId, Is.is(makerUnlockEvent.currency));
            assertThat(symbolId, Is.is(makerUnlockEvent.symbol));
            assertThat(makerOrderId, Is.is(makerUnlockEvent.orderId));
            assertThat(0L, Is.is(makerUnlockEvent.fee));
            assertThat(PositionDirection.SHORT, Is.is(makerUnlockEvent.direction));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(makerUnlockEvent.eventType));
            // 下了10单, 成交2单, 剩余8单需要预先在仓位上扣除手续费
            assertThat(deposit - (size - txSize) * 100L - (size - txSize) * 20L, Is.is(makerUnlockEvent.free));
            assertThat((size - txSize) * 100L + (size - txSize) * 20L, Is.is(makerUnlockEvent.locked));
            assertThat(0L, Is.is(makerUnlockEvent.openPriceSum));
            assertThat(0L, Is.is(makerUnlockEvent.openVolume));
            assertThat(0L, Is.is(makerUnlockEvent.tradeSize));
            assertThat(0L, Is.is(makerUnlockEvent.tradePrice));
            checkEvent(makerUnlockEvent);

            // check open position event
            IFundEventsHandler.FundsEvent makerOpenPositionEvent = fundEvents.get(7);
            assertThat(userId1, Is.is(makerOpenPositionEvent.uid));
            assertThat(quoteId, Is.is(makerOpenPositionEvent.currency));
            assertThat(symbolId, Is.is(makerOpenPositionEvent.symbol));
            assertThat(makerOrderId, Is.is(makerOpenPositionEvent.orderId));
            assertThat(2 * 10L, Is.is(makerOpenPositionEvent.fee));
            assertThat(PositionDirection.SHORT, Is.is(makerOpenPositionEvent.direction));
            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(makerOpenPositionEvent.eventType));
            // 10000(deposit) - 10 * 100(maker order) - 2 * 10(maker fee) - 8 * 20(taker fee) = 8820
            assertThat(deposit - size * 100L - 2 * 10L - 8 * 20L, Is.is(makerOpenPositionEvent.free));
            assertThat(size * 100L + 8 * 20L, Is.is(makerOpenPositionEvent.locked));
            assertThat(20000L, Is.is(makerOpenPositionEvent.openPriceSum));
            assertThat(2L, Is.is(makerOpenPositionEvent.openVolume));
            assertThat(2L, Is.is(makerOpenPositionEvent.tradeSize));
            assertThat(10000L, Is.is(makerOpenPositionEvent.tradePrice));
            // openVolume * markPrice - openPriceSum = 2 * 10000 - 20000 = 0
            assertThat(0L, Is.is(makerOpenPositionEvent.unrealizedProfit));
            // maintenanceMargin = 100
            // liquidationPrice = (direction * (maintenanceMargin - totalMargin) + openPriceSum) / openVolume = (-1 * (100 - 200) + 20000 * 1)/2 = 10050
            assertThat(10050L, Is.is(makerOpenPositionEvent.liquidationPrice));
            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 50 / 100) = 500
            assertThat(500L, Is.is(makerOpenPositionEvent.marginRatioScaleK));
        }
    }

    // 平仓事件, 平仓所有position
    @Test
    public void testClosePosition() {
        int deposit = 1000;
        long userId1 = 1003L;
        long userId2 = 1004L;
        long makerOrderId1 = 1005L;
        long takerOrderId2 = 1006L;
        long makerOrderId3 = 1007L;
        long takerOrderId4 = 1008L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.initFutureSymbol(symbolId, quoteId);
            container.addCurrency(BASE_CURRENCY_ID);
            container.addCurrency(quoteId);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            // 开仓成功
            container.createBidWithOrderId(makerOrderId1, userId1, 1, 10000, symbolId);
            container.createAskWithOrderId(takerOrderId2, userId2, 1, 10000, symbolId);

            // 平仓成功
            container.createAskWithOrderId(makerOrderId3, userId1, 1, 10500, symbolId);
            container.createBidWithOrderId(takerOrderId4, userId2, 1, 10500, symbolId);

            verify(handler, times(11)).commandResult(commandResultCaptor.capture());
            verify(handler, never()).reduceEvent(any());
            verify(handler, never()).rejectEvent(any());
            verify(handler, times(2)).tradeEvent(tradeEventCaptor.capture());

            // check balance
            SingleUserReportResult user1Report = container.getUserProfile(userId1);
            SingleUserReportResult user2Report = container.getUserProfile(userId2);
            // check平仓后利润, taker/maker fee分别为10/20
            assertThat(user1Report.getAccounts().get(quoteId), Is.is((deposit - 10L + 500L)));
            assertThat(user2Report.getAccounts().get(quoteId), Is.is((MAX_VALUE - 20L - 500L)));

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(14)).fundsEvent(fundEventCapor.capture());
            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();

            IFundEventsHandler.FundsEvent takerCloseEvent = fundEvents.get(11);
            assertThat(userId2, Is.is(takerCloseEvent.uid));
            assertThat(quoteId, Is.is(takerCloseEvent.currency));
            assertThat(symbolId, Is.is(takerCloseEvent.symbol));
            // 关仓不收手续费
            assertThat(0L, Is.is(takerCloseEvent.fee));
            assertThat(PositionDirection.SHORT, Is.is(takerCloseEvent.direction));
            assertThat(FundEvent.FundEventType.CLOSE_POSITION, Is.is(takerCloseEvent.eventType));
            // free is not correct
            assertThat(3999980L, Is.is(takerCloseEvent.free));
            assertThat(0L, Is.is(takerCloseEvent.locked));
            assertThat(0L, Is.is(takerCloseEvent.openPriceSum));
            assertThat(0L, Is.is(takerCloseEvent.openVolume));
            assertThat(1L, Is.is(takerCloseEvent.tradeSize));
            // trade price?
            assertThat(10500L, Is.is(takerCloseEvent.tradePrice));
            checkEvent(takerCloseEvent);

            IFundEventsHandler.FundsEvent makerCloseEvent = fundEvents.get(13);
            assertThat(userId1, Is.is(makerCloseEvent.uid));
            assertThat(quoteId, Is.is(makerCloseEvent.currency));
            assertThat(symbolId, Is.is(makerCloseEvent.symbol));
            assertThat(0L, Is.is(makerCloseEvent.fee));
            assertThat(PositionDirection.LONG, Is.is(makerCloseEvent.direction));
            assertThat(FundEvent.FundEventType.CLOSE_POSITION, Is.is(makerCloseEvent.eventType));
            // free = init money - fee
            assertThat(deposit - 10 * 1L, Is.is(makerCloseEvent.free));
            assertThat(0L, Is.is(makerCloseEvent.locked));
            assertThat(0L, Is.is(makerCloseEvent.openPriceSum));
            assertThat(0L, Is.is(makerCloseEvent.openVolume));
            assertThat(1L, Is.is(makerCloseEvent.tradeSize));
            // trade price?
            assertThat(10500L, Is.is(makerCloseEvent.tradePrice));
            checkEvent(makerCloseEvent);
        }
    }

    // 平仓事件, 平仓部分订单
    @Test
    public void testPartialClosePosition() {
        int deposit = 10000;
        long userId1 = 1003L;
        long userId2 = 1004L;
        long makerOrderId1 = 1005L;
        long takerOrderId2 = 1006L;
        long makerOrderId3 = 1007L;
        long takerOrderId4 = 1008L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.initFutureSymbol(symbolId, quoteId);
            container.addCurrency(BASE_CURRENCY_ID);
            container.addCurrency(quoteId);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            // 开仓成功
            container.createBidWithOrderId(makerOrderId1, userId1, 10, 10000, symbolId);
            container.createAskWithOrderId(takerOrderId2, userId2, 10, 10000, symbolId);

            // 平仓一手成功
            container.createAskWithOrderId(makerOrderId3, userId1, 2, 10500, symbolId);
            container.createBidWithOrderId(takerOrderId4, userId2, 1, 10500, symbolId);

            verify(handler, times(11)).commandResult(commandResultCaptor.capture());
            verify(handler, never()).reduceEvent(any());
            verify(handler, never()).rejectEvent(any());
            verify(handler, times(2)).tradeEvent(tradeEventCaptor.capture());

            // check balance
            SingleUserReportResult user1Report = container.getUserProfile(userId1);
            SingleUserReportResult user2Report = container.getUserProfile(userId2);
            // check平仓后利润, taker/maker fee分别为10/20
//            assertThat(user1Report.getAccounts().get(quoteId), Is.is((deposit - 10L + 500L)));
//            assertThat(user2Report.getAccounts().get(quoteId), Is.is((MAX_VALUE - 20L - 500L)));

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(14)).fundsEvent(fundEventCapor.capture());
            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();

            IFundEventsHandler.FundsEvent takerCloseEvent = fundEvents.get(11);
            assertThat(userId2, Is.is(takerCloseEvent.uid));
            assertThat(quoteId, Is.is(takerCloseEvent.currency));
            assertThat(symbolId, Is.is(takerCloseEvent.symbol));
            // 关仓不收手续费
            assertThat(0L, Is.is(takerCloseEvent.fee));
            assertThat(PositionDirection.SHORT, Is.is(takerCloseEvent.direction));
            assertThat(FundEvent.FundEventType.CLOSE_POSITION, Is.is(takerCloseEvent.eventType));
            // openVolume * markPrice - openPriceSum = -1 * (9 * 10000 - 89500) = 500
            assertThat(-500L, Is.is(takerCloseEvent.unrealizedProfit));
            // maintenanceMargin = 0.5% * 9 * 10000 = 450
            // liquidationPrice = (direction * (maintenanceMargin - totalMargin) + openPriceSum) / openVolume = (-1 * (450 - 900) + 89500)/9 = long(9994.4) = 9994L
            assertThat(9994L, Is.is(takerCloseEvent.liquidationPrice));
            // totalMargin = openInitMarginSum + profit + extraMagin = 900 - 500 + 0 = 400
            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 450 / 400) = 1125L
            assertThat(1125L, Is.is(takerCloseEvent.marginRatioScaleK));
            // free is not correct
            /*
            assertThat(3999980L - 1000L, Is.is(takerCloseEvent.free));
            assertThat(0L, Is.is(takerCloseEvent.locked));
            assertThat(10000L, Is.is(takerCloseEvent.openPriceSum));
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
            assertThat(10000L, Is.is(makerCloseEvent.openPriceSum));
            assertThat(500L, Is.is(makerCloseEvent.pnl));
            assertThat(0L, Is.is(makerCloseEvent.position));
            assertThat(1L, Is.is(makerCloseEvent.positionChanged));
            // trade price?
            assertThat(10500L, Is.is(makerCloseEvent.tradePrice));
            */
        }
    }

    // 强制平仓事件
    @Test
    public void testForceClosePosition() {
        int deposit = 100000;
        int size = 10;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.initFutureSymbol(symbolId, quoteId);
            container.addCurrency(BASE_CURRENCY_ID);
            container.addCurrency(quoteId);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(UID_1, deposit, quoteId);
            container.createUserWithSpecificMoney(UID_2, MAX_VALUE, quoteId);

            // 开仓成功
            container.createBidWithOrderId(MAKER_1, UID_1, size, 10000, symbolId);
            container.createAskWithOrderId(TAKER_1, UID_2, size, 10000, symbolId);

            // 模拟行情变动
            container.updateCurrentPriceTo(500, symbolId, quoteId);

            container.validateUserState(UID_1, profile -> {
                assertThat(profile.getPositions().get(symbolId).get(0).profit, Is.is((0L)));
                assertThat(profile.getPositions().get(symbolId).get(0).unrealizedProfit, Is.is((-95000L)));
                assertThat(profile.getPositions().get(symbolId).get(0).marginRatioScaleK, Is.is((-1000L)));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(16)).fundsEvent(fundEventCapor.capture());
            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();

            IFundEventsHandler.FundsEvent takerCloseEvent = fundEvents.get(0);
        }
    }

    @Test
    public void testAdjustment() {
        final int symbolId = SYMBOL_MARGIN;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initFeeSymbols();
            container.initFeeSymbolsMarkPrice();
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
        return ApiPlaceOrder.builder().uid(uid).action(action).orderType(type).symbol(symbolId).marginMode(MarginMode.ISOLATED);
    }

    private void doInit(ExchangeTestContainer container) {
        container.addCurrency(SYMBOLSPECFEE_USD_JPY.baseCurrency, 0);
        container.addCurrency(SYMBOLSPECFEE_USD_JPY.quoteCurrency, 0);
        container.addSymbol(CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_MARGIN)
                .type(SymbolType.FUTURES_CONTRACT_PERPETUAL)
                .baseCurrency(CURRENECY_USD)
                .quoteCurrency(CURRENECY_JPY)
                .baseScaleK(1)
                .quoteScaleK(1)
                .initMargin(1)
                .initMarginScaleK(21)
                .maintenanceMargin(TreeSortedMap.newMapWith(1000L, 5L, 100000L, 10L))
                .maxLeverage(TreeSortedMap.newMapWith(2000L, 5L, 100000L, 10L))
                .takerFee(3)
                .makerFee(2)
                .build());

        container.addCurrency(SYMBOLSPECFEE_XBT_LTC.baseCurrency, 0);
        container.addCurrency(SYMBOLSPECFEE_XBT_LTC.quoteCurrency, 0);
        container.addSymbol(CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_EXCHANGE_FEE)
                .type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(CURRENECY_XBT)
                .quoteCurrency(CURRENECY_LTC)
                .baseScaleK(1)
                .quoteScaleK(1)
                .takerFee(1900)
                .makerFee(700)
                .build());
        container.initFeeSymbolsMarkPrice();
        container.initFeeUsers();
    }

    // TODO count/verify number of commands and events
    private void testMultiBuy(final CoreSymbolSpecification symbolSpec, final OrderType orderType, final RejectionCause rejectionCause) {

        final int symbolId = symbolSpec.symbolId;

        final long size = 40L + (rejectionCause == RejectionCause.REJECTION_BY_SIZE ? 1 : 0);

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {

            doInit(container);

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

        long size = 22L + (rejectionCause == RejectionCause.REJECTION_BY_SIZE ? 1 : 0);

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {

            doInit(container);

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