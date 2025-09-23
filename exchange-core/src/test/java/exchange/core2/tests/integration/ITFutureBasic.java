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
import exchange.core2.core.common.api.reports.SingleUserReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.core.Is;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

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
class ITFutureBasic extends ITFutureBase {

    // -------------------------- fund tests ----------------------------------------
    // 用户资产增加时需要生产deposit fundEvent
    @Test
    public void testBalanceIncrease() {
        long delta = 100L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {
            container.initOneUser(UID_1);
            container.addCurrency(SYMBOL_MARGIN);
            container.initFutureSymbol(symbolId, quoteId);
            container.addMoneyToUser(UID_1, SYMBOL_MARGIN, delta);

            verify(handler, never()).spotExecutionReport(any());
            verify(handler, never()).futuresExecutionReport(any());

        } finally {
            verify(handler, times(1)).fundEventReport(fundEventCaptor.capture());

            IFundEventsHandler.FundEventReport fundEvent = fundEventCaptor.getValue();
            assertThat(UID_1, Is.is(fundEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.DEPOSIT, Is.is(fundEvent.getEventType()));
            assertThat(SYMBOL_MARGIN, Is.is(fundEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(fundEvent.getBalances().getCurrencyScaleK()));
            assertThat(delta, Is.is(fundEvent.getBalances().getFree()));
            assertThat(0L, Is.is(fundEvent.getBalances().getLocked()));
            assertThat(0, Is.is(fundEvent.getPositions().getSymbolId()));
            assertThat(0L, Is.is(fundEvent.getPositions().getBaseScaleK()));
            assertThat(0L, Is.is(fundEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.EMPTY, Is.is(fundEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(fundEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(fundEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(fundEvent.getPositions().getCumRealized()));
            assertThat(true, Is.is(fundEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(fundEvent.getPositions().getIsolatedWallet()));
            assertThat(0, Is.is(fundEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(fundEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(fundEvent.getPositions().getMarkPrice()));
            checkEvent(fundEvent);
        }
    }

    // 用户资产减少时需要生产withdraw fundEvent
    @Test
    public void testBalanceDecrease() {
        long deposit = 100L;
        long withdraw = -40L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.initOneUser(UID_1);
            container.initFutureSymbol(symbolId, quoteId);
            container.addCurrency(BASE_CURRENCY_ID);
            container.addCurrency(quoteId);
            container.addCurrency(SYMBOL_MARGIN);
            container.addMoneyToUser(UID_1, SYMBOL_MARGIN, deposit);
            container.addMoneyToUser(UID_1, SYMBOL_MARGIN, withdraw);

            verify(handler, never()).spotExecutionReport(any());
            verify(handler, never()).futuresExecutionReport(any());

            // check balance
            SingleUserReportResult user1Report = container.getUserProfile(UID_1);
            assertThat(user1Report.getAccounts().get(SYMBOL_MARGIN), Is.is((deposit + withdraw)));

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(2)).fundEventReport(fundEventCaptor.capture());

            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport depositEvent = fundEvents.get(0);
            assertThat(UID_1, Is.is(depositEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.DEPOSIT, Is.is(depositEvent.getEventType()));
            assertThat(SYMBOL_MARGIN, Is.is(depositEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(depositEvent.getBalances().getCurrencyScaleK()));
            assertThat(deposit, Is.is(depositEvent.getBalances().getFree()));
            assertThat(0L, Is.is(depositEvent.getBalances().getLocked()));
            assertThat(0, Is.is(depositEvent.getPositions().getSymbolId()));
            assertThat(0L, Is.is(depositEvent.getPositions().getBaseScaleK()));
            assertThat(0L, Is.is(depositEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.EMPTY, Is.is(depositEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(depositEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(depositEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(depositEvent.getPositions().getCumRealized()));
            assertThat(true, Is.is(depositEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(depositEvent.getPositions().getIsolatedWallet()));
            assertThat(0, Is.is(depositEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(depositEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(depositEvent.getPositions().getMarkPrice()));
            checkEvent(depositEvent);

            IFundEventsHandler.FundEventReport withdrawEvent = fundEvents.get(1);
            assertThat(UID_1, Is.is(withdrawEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.WITHDRAW, Is.is(withdrawEvent.getEventType()));
            assertThat(SYMBOL_MARGIN, Is.is(withdrawEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(withdrawEvent.getBalances().getCurrencyScaleK()));
            assertThat(deposit + withdraw, Is.is(withdrawEvent.getBalances().getFree()));
            assertThat(0L, Is.is(withdrawEvent.getBalances().getLocked()));
            assertThat(0, Is.is(withdrawEvent.getPositions().getSymbolId()));
            assertThat(0L, Is.is(withdrawEvent.getPositions().getBaseScaleK()));
            assertThat(0L, Is.is(withdrawEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.EMPTY, Is.is(withdrawEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(withdrawEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(withdrawEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(withdrawEvent.getPositions().getCumRealized()));
            assertThat(true, Is.is(withdrawEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(withdrawEvent.getPositions().getIsolatedWallet()));
            assertThat(0, Is.is(withdrawEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(withdrawEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(withdrawEvent.getPositions().getMarkPrice()));
            checkEvent(withdrawEvent);
        }
    }

    @Test
    public void testCancelSuccess() {
        long deposit = 1000L;
        long userId1 = UID_1;
        long orderId = 1005L;
        int size = 1;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.initFutureSymbol(symbolId, quoteId);
            container.addCurrency(BASE_CURRENCY_ID);
            container.addCurrency(quoteId);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createBidWithOrderId(orderId, userId1, size, 10000, symbolId);

            // cancel order
            container.cancelOrder(userId1, orderId, symbolId);
            container.sleepSeconds(1);

            verify(handler, never()).spotExecutionReport(any());
            verify(handler, times(2)).futuresExecutionReport(futuresEventCaptor.capture());

            List<ITradeEventsHandler.FuturesExecutionReport> events = futuresEventCaptor.getAllValues();
            ITradeEventsHandler.FuturesExecutionReport event0 = events.get(0);
            assertThat(event0.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
            assertThat(event0.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.NEW));
            assertThat(event0.symbolId, Is.is(2));
            assertThat(event0.orderQtyScale, Is.is(1L));
            assertThat(event0.priceScale, Is.is(1L));
            assertThat(event0.userId, Is.is(userId1));
            assertThat(event0.clOrderId, Is.is(0L));
            assertThat(event0.orderId, Is.is(orderId));
            assertThat(event0.orderType, Is.is(GTC));
            assertThat(event0.side, Is.is(BID));
            assertThat(event0.counterpartyId, Is.is(-1L));
            assertThat(event0.price, Is.is(10000L));
            assertThat(event0.orderQty, Is.is(1L));
            assertThat(event0.execId, Is.is(-1L));
            assertThat(event0.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event0.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event0.lastQty, Is.is(0L));
            assertThat(event0.lastPx, Is.is(0L));
            assertThat(event0.cumQty, Is.is(0L));
            assertThat(event0.cumQuoteQty, Is.is(0L));
            assertThat(event0.avgPx, Is.is(0L));
            assertThat(event0.fee, Is.is(0L));
            assertThat(event0.feeAssetId, Is.is(840));
            assertThat(event0.isMaker, Is.is(false));

            ITradeEventsHandler.FuturesExecutionReport event1 = events.get(1);
            assertThat(event1.executionType, Is.is(ITradeEventsHandler.ExecType.CANCEL));
            assertThat(event1.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.CANCELED));
            assertThat(event1.symbolId, Is.is(2));
            assertThat(event1.orderQtyScale, Is.is(1L));
            assertThat(event1.priceScale, Is.is(1L));
            assertThat(event1.userId, Is.is(userId1));
            assertThat(event1.clOrderId, Is.is(0L));
            assertThat(event1.orderId, Is.is(orderId));
            assertThat(event1.orderType == null, Is.is(true));
            assertThat(event1.side, Is.is(BID));
            assertThat(event1.counterpartyId, Is.is(-1L));
            assertThat(event1.price, Is.is(0L));
            assertThat(event1.orderQty, Is.is(0L));
            assertThat(event1.execId, Is.is(-1L));
            assertThat(event1.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event1.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event1.lastQty, Is.is(0L));
            assertThat(event1.lastPx, Is.is(0L));
            assertThat(event1.cumQty, Is.is(0L));
            assertThat(event1.cumQuoteQty, Is.is(0L));
            assertThat(event1.avgPx, Is.is(0L));
            assertThat(event1.fee, Is.is(0L));
            assertThat(event1.feeAssetId, Is.is(840));
            assertThat(event1.isMaker, Is.is(false));

            // check balance
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), Is.is((deposit)));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(3)).fundEventReport(fundEventCaptor.capture());
            // deposit first
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport depositEvent = fundEvents.get(0);
            assertThat(userId1, Is.is(depositEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.DEPOSIT, Is.is(depositEvent.getEventType()));
            assertThat(quoteId, Is.is(depositEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(depositEvent.getBalances().getCurrencyScaleK()));
            // 1000(balance) - 100(open position) - 20(maker fee) = 880
            assertThat(deposit, Is.is(depositEvent.getBalances().getFree()));
            assertThat(0L, Is.is(depositEvent.getBalances().getLocked()));
            // position check
            assertThat(0, Is.is(depositEvent.getPositions().getSymbolId()));
            assertThat(0L, Is.is(depositEvent.getPositions().getBaseScaleK()));
            assertThat(0L, Is.is(depositEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.EMPTY, Is.is(depositEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(depositEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(depositEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(depositEvent.getPositions().getCumRealized()));
            assertThat(true, Is.is(depositEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(depositEvent.getPositions().getIsolatedWallet()));
            assertThat(0, Is.is(depositEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(depositEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(depositEvent.getPositions().getMarkPrice()));
            checkEvent(depositEvent);

            // lock pending second, should lock money
            IFundEventsHandler.FundEventReport lockEvent = fundEvents.get(1);
            assertThat(userId1, Is.is(lockEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(lockEvent.getEventType()));
            assertThat(quoteId, Is.is(lockEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(lockEvent.getBalances().getCurrencyScaleK()));
            // 1000(balance) - 100(open position) - 20(maker fee) = 880
            assertThat(deposit - 100L - size * 20L, Is.is(lockEvent.getBalances().getFree()));
            assertThat(100L + size * 20L, Is.is(lockEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(lockEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(lockEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(lockEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.LONG, Is.is(lockEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(lockEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(lockEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(lockEvent.getPositions().getCumRealized()));
            assertThat(true, Is.is(lockEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(lockEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(lockEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(lockEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(lockEvent.getPositions().getMarkPrice()));
            checkEvent(lockEvent);

            // unlock pending event, free should be returned back
            IFundEventsHandler.FundEventReport unLockEvent = fundEvents.get(2);
            assertThat(userId1, Is.is(unLockEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(unLockEvent.getEventType()));
            assertThat(quoteId, Is.is(unLockEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(unLockEvent.getBalances().getCurrencyScaleK()));
            assertThat(deposit, Is.is(unLockEvent.getBalances().getFree()));
            assertThat(0L, Is.is(unLockEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(unLockEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(unLockEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(unLockEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.LONG, Is.is(unLockEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(unLockEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(unLockEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(unLockEvent.getPositions().getCumRealized()));
            assertThat(true, Is.is(unLockEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(unLockEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(unLockEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(unLockEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(unLockEvent.getPositions().getMarkPrice()));
            checkEvent(unLockEvent);
        }
    }

    // 开仓事件, 完全成交, taker为Ask
    @Test
    public void testOpenPosition4Bid() throws InterruptedException {
        int deposit = 1000;
        long userId1 = UID_1;
        long userId2 = UID_2;
        long makerOrderId = 1005L;
        long takerOrderId = 1006L;
        int size = 1;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.initFutureSymbol(symbolId, quoteId);
            container.addCurrency(BASE_CURRENCY_ID);
            container.addCurrency(quoteId);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            container.createBidWithOrderId(makerOrderId, userId1, size, 10000, symbolId);
            container.createAskWithOrderId(takerOrderId, userId2, size, 10000, symbolId);

            verify(handler, never()).spotExecutionReport(any());
            verify(handler, times(4)).futuresExecutionReport(futuresEventCaptor.capture());

            // check balance
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), Is.is((deposit - 10L)));
            });
            container.validateUserState(userId2, profile -> {
                assertThat(profile.getAccounts().get(quoteId), Is.is((MAX_VALUE - 20L)));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(8)).fundEventReport(fundEventCaptor.capture());
        }
    }

    // 开仓事件, 完全成交, taker为Bid
    @Test
    public void testOpenPosition4Ask() throws InterruptedException {
        int deposit = 1000;
        long userId1 = UID_1;
        long userId2 = UID_2;
        long makerOrderId = 1005L;
        long takerOrderId = 1006L;
        int size = 1;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.initFutureSymbol(symbolId, quoteId);
            container.addCurrency(BASE_CURRENCY_ID);
            container.addCurrency(quoteId);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            container.createAskWithOrderId(makerOrderId, userId1, size, 10000, symbolId);
            container.createBidWithOrderId(takerOrderId, userId2, size, 10000, symbolId);

            verify(handler, never()).spotExecutionReport(any());
            verify(handler, times(4)).futuresExecutionReport(futuresEventCaptor.capture());

            // check balance
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), Is.is((deposit - 10L)));
            });
            container.validateUserState(userId2, profile -> {
                assertThat(profile.getAccounts().get(quoteId), Is.is((MAX_VALUE - 20L)));
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(8)).fundEventReport(fundEventCaptor.capture());
        }
    }

    // 开仓事件, 测试部分成单, taker为Ask
    @Test
    public void testOpenMultiplePosition4Bid() throws InterruptedException {
        int size = 10;
        int deposit = 1000 * size;
        long userId1 = UID_1;
        long userId2 = UID_2;
        long makerOrderId = 1005L;
        long takerOrderId = 1006L;
        int txSize = 2;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.initFutureSymbol(symbolId, quoteId);
            container.addCurrency(BASE_CURRENCY_ID);
            container.addCurrency(quoteId);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            container.createBidWithOrderId(makerOrderId, userId1, size, 10000, symbolId);
            container.createAskWithOrderId(takerOrderId, userId2, txSize, 10000, symbolId);

            verify(handler, never()).spotExecutionReport(any());
            verify(handler, times(4)).futuresExecutionReport(futuresEventCaptor.capture());

            // check balance
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), Is.is((deposit - txSize * 10L)));
            });
            container.validateUserState(userId2, profile -> {
                assertThat(profile.getAccounts().get(quoteId), Is.is((MAX_VALUE - txSize * 20L)));
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(8)).fundEventReport(fundEventCaptor.capture());
        }
    }

    // 开仓事件, 测试部分成交, taker为Bid
    @Test
    public void testOpenMultiplePosition4Ask() throws InterruptedException {
        int size = 10;
        int deposit = 1000 * size;
        long userId1 = UID_1;
        long userId2 = UID_2;
        long makerOrderId = 1005L;
        long takerOrderId = 1006L;
        int txSize = 2;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.initFutureSymbol(symbolId, quoteId);
            container.addCurrency(BASE_CURRENCY_ID);
            container.addCurrency(quoteId);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            container.createAskWithOrderId(makerOrderId, userId1, size, 10000, symbolId);
            container.createBidWithOrderId(takerOrderId, userId2, txSize, 10000, symbolId);

            verify(handler, never()).spotExecutionReport(any());
            verify(handler, times(4)).futuresExecutionReport(futuresEventCaptor.capture());

            // check balance
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), Is.is((deposit - txSize * 10L)));
            });
            container.validateUserState(userId2, profile -> {
                assertThat(profile.getAccounts().get(quoteId), Is.is((MAX_VALUE - txSize * 20L)));
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(8)).fundEventReport(fundEventCaptor.capture());
        }
    }

    // 平仓事件, 平仓所有position
    @Test
    public void testClosePosition() {
        int deposit = 1000;
        long userId1 = UID_1;
        long userId2 = UID_2;
        long makerOrderId1 = 1005L;
        long takerOrderId2 = 1006L;
        long makerOrderId3 = 1007L;
        long takerOrderId4 = 1008L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
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

            verify(handler, never()).spotExecutionReport(any());
            verify(handler, times(8)).futuresExecutionReport(futuresEventCaptor.capture());

            // check balance
            container.validateUserState(userId1, profile -> {
                // Profit: 10500 - 10000 = 500, minus maker fee 10
                assertThat(profile.getAccounts().get(quoteId), Is.is((deposit - 10L + 500L)));
            });
            container.validateUserState(userId2, profile -> {
                // Loss: 10000 - 10500 = -500, minus taker fee 20
                assertThat(profile.getAccounts().get(quoteId), Is.is((MAX_VALUE - 20L - 500L)));
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(14)).fundEventReport(fundEventCaptor.capture());
        }
    }

    // 平仓事件, 平仓部分订单
    @Test
    public void testPartialClosePosition() {
        int deposit = 10000;
        long userId1 = UID_1;
        long userId2 = UID_2;
        long makerOrderId1 = 1005L;
        long takerOrderId2 = 1006L;
        long makerOrderId3 = 1007L;
        long takerOrderId4 = 1008L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
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

            verify(handler, never()).spotExecutionReport(any());
            verify(handler, times(8)).futuresExecutionReport(futuresEventCaptor.capture());

            // check balance
            container.validateUserState(userId1, profile -> {
                // Only maker fee deducted, profit not realized until fully closed
                assertThat(profile.getAccounts().get(quoteId), Is.is((deposit - 10L * 10)));
            });
            container.validateUserState(userId2, profile -> {
                // Only taker fee deducted, loss not realized until fully closed
                assertThat(profile.getAccounts().get(quoteId), Is.is((MAX_VALUE - 20L * 10)));
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(14)).fundEventReport(fundEventCaptor.capture());
        }
    }

    // 强制平仓事件
    @Test
    public void testForceClosePosition() {
        int deposit = 100000;
        int size = 10;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
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
            verify(handler, times(16)).fundEventReport(fundEventCaptor.capture());
        }
    }

    @Test
    public void testAdjustment() {
        final int symbolId = SYMBOL_MARGIN;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initFeeSymbols();
            container.initFeeSymbolsMarkPrice();
            container.initFeeUsers();

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

    // ------------------------------------------------------------------------------
    @Override
    public void testMultiBuy(final CoreSymbolSpecification symbolSpec, final OrderType orderType, final RejectionCause rejectionCause) {

        final int symbolId = symbolSpec.symbolId;

        final long size = 40L + (rejectionCause == RejectionCause.REJECTION_BY_SIZE ? 1 : 0);

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {

            doInit(container);

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
        doCheckEvtCnt(symbolSpec, orderType, rejectionCause);
    }

    @Override
    public void testMultiSell(final CoreSymbolSpecification symbolSpec, final OrderType orderType, final RejectionCause rejectionCause) {

        final int symbolId = symbolSpec.symbolId;

        long size = 22L + (rejectionCause == RejectionCause.REJECTION_BY_SIZE ? 1 : 0);

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {

            doInit(container);


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
        doCheckEvtCnt(symbolSpec, orderType, rejectionCause);
    }

}