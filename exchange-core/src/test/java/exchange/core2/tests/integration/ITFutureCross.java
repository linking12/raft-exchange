///*
// * Copyright 2019 Maksim Zheravin
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//package exchange.core2.tests.integration;
//
//import exchange.core2.core.IFundEventsHandler;
//import exchange.core2.core.ITradeEventsHandler;
//import exchange.core2.core.common.*;
//import exchange.core2.core.common.api.ApiAdjustUserBalance;
//import exchange.core2.core.common.api.ApiPlaceOrder;
//import exchange.core2.core.common.api.reports.SingleUserReportResult;
//import exchange.core2.core.common.cmd.CommandResultCode;
//import exchange.core2.core.common.config.PerformanceConfiguration;
//import exchange.core2.core.event.IEventsHandler4Test;
//import exchange.core2.core.event.SimpleEventsProcessor4Test;
//import exchange.core2.tests.util.ExchangeTestContainer;
//import lombok.extern.slf4j.Slf4j;
//import org.hamcrest.core.Is;
//import org.junit.jupiter.api.AfterEach;
//import org.junit.jupiter.api.BeforeEach;
//import org.junit.jupiter.api.Test;
//import org.junit.jupiter.api.Timeout;
//import org.junit.jupiter.api.extension.ExtendWith;
//import org.mockito.ArgumentCaptor;
//import org.mockito.Captor;
//import org.mockito.Mock;
//import org.mockito.junit.jupiter.MockitoExtension;
//
//import java.util.List;
//import java.util.concurrent.ExecutionException;
//import java.util.concurrent.TimeUnit;
//
//import static exchange.core2.core.common.OrderAction.ASK;
//import static exchange.core2.core.common.OrderAction.BID;
//import static exchange.core2.core.common.OrderType.*;
//import static exchange.core2.tests.util.TestConstants.*;
//import static org.hamcrest.MatcherAssert.assertThat;
//import static org.hamcrest.core.Is.is;
//import static org.junit.jupiter.api.Assertions.assertTrue;
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.Mockito.*;
//
//@Slf4j
//@ExtendWith(MockitoExtension.class)
//class ITFutureCross {
//
//    private int symbolId = 2;
//    private int quoteId = 840;
//
//    private SimpleEventsProcessor4Test processor;
//
//    @Mock
//    private IEventsHandler4Test handler;
//
//    @Captor
//    private ArgumentCaptor<ITradeEventsHandler.ApiCommandResult> commandResultCaptor;
//
//    @Captor
//    private ArgumentCaptor<ITradeEventsHandler.TradeEvent> tradeEventCaptor;
//
//    @Captor
//    private ArgumentCaptor<IFundEventsHandler.FundsEvent> fundEventCapor;
//
//    @Captor
//    private ArgumentCaptor<ITradeEventsHandler.ReduceEvent> reduceEventCaptor;
//
//    @Captor
//    private ArgumentCaptor<ITradeEventsHandler.RejectEvent> rejectEventCaptor;
//
//
//    @BeforeEach
//    public void before() {
//        processor = new SimpleEventsProcessor4Test(handler);
//    }
//
//    @AfterEach()
//    public void after() {
//
//    }
//
//    private void initCurrencies(ExchangeTestContainer container) {
//        container.addCurrency(BASE_CURRENCY_ID);
//        container.addCurrency(quoteId);
//    }
//
//    // -------------------------- order tests ----------------------------------------
//    // 1. isolated和cross margin不能混着下
//    // 2. 取消订单后可以下新的订单(cross/isolated均可)
//    @Test
//    public void testCancelSuccess() {
//        long deposit = 2000L;
//        long userId1 = 1003L;
//        int size = 1;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            container.initFutureSymbol(symbolId, quoteId);
//            initCurrencies(container);
//            container.initMarkPrice(symbolId, 10000);
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//
//            ApiPlaceOrder order1 = container.genOrder(userId1, size, 10000, symbolId, BID, GTC, MarginMode.ISOLATED);
//            container.submitCommandSync(order1, CommandResultCode.SUCCESS);
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().get(symbolId).get(0).getPendingBuySize(), is(1L));
//                assertThat(profile.getPositions().get(symbolId).get(0).getMarginMode(), is(MarginMode.ISOLATED));
//            });
//            ApiPlaceOrder order2 = container.genOrder(userId1, size, 10000, symbolId, BID, GTC, MarginMode.CROSS);
//            // 用户已有isolated 持仓，cross下单失败
//            container.submitCommandSync(order2, CommandResultCode.RISK_MARGIN_MODE_MISMATCH);
//
//            // cancel isolated order
//            container.cancelOrder(userId1, order1.orderId, symbolId);
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(0));
//            });
//            // 取消订单后再下cross可以成功
//            container.submitCommandSync(order2, CommandResultCode.SUCCESS);
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().get(symbolId).get(0).getPendingBuySize(), is(1L));
//                assertThat(profile.getPositions().get(symbolId).get(0).getMarginMode(), is(MarginMode.CROSS));
//            });
//            // 用户已有cross 持仓，再下isolated下单失败
//            container.submitCommandSync(order1, CommandResultCode.RISK_MARGIN_MODE_MISMATCH);
//            // cancel cross margin
//            container.cancelOrder(userId1, order2.orderId, symbolId);
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(0));
//            });
//            container.submitCommandSync(order1, CommandResultCode.SUCCESS);
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().get(symbolId).get(0).getPendingBuySize(), is(1L));
//                assertThat(profile.getPositions().get(symbolId).get(0).getMarginMode(), is(MarginMode.ISOLATED));
//            });
//
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//    }
//
//    // 默认为margin为isolated
//    @Test
//    public void testDefaultMargin() {
//        long deposit = 20000L;
//        long userId1 = 1003L;
//        int size = 1;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            container.initFutureSymbol(symbolId, quoteId);
//            initCurrencies(container);
//            container.initMarkPrice(symbolId, 10000);
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//
//            ApiPlaceOrder order1 = ApiPlaceOrder.builder()
//                    .uid(userId1)
//                    .orderId(container.getRandomTransactionId())
//                    .action(OrderAction.BID)
//                    .size(size)
//                    .price(10000L)
//                    .symbol(symbolId)
//                    .orderType(OrderType.GTC)
//                    .marginMode(MarginMode.ISOLATED)
//                    .build();
//            container.submitCommandSync(order1, CommandResultCode.SUCCESS);
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().get(symbolId).get(0).getMarginMode(), is(MarginMode.ISOLATED));
//            });
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//    }
//
//    // 平仓后可以改margin mode
//    @Test
//    public void tesCloseMarginThenChangeMode() {
//        int deposit = 1000;
//        long userId1 = 1003L;
//        long userId2 = 1004L;
//        long makerOrderId1 = 1005L;
//        long takerOrderId2 = 1006L;
//        long makerOrderId3 = 1007L;
//        long takerOrderId4 = 1008L;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            container.initFutureSymbol(symbolId, quoteId);
//            initCurrencies(container);
//            container.initMarkPrice(symbolId, 10000);
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
//
//            // 开仓成功
//            container.createBidWithOrderId(makerOrderId1, userId1, 1, 10000, symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(takerOrderId2, userId2, 1, 10000, symbolId, MarginMode.CROSS);
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().get(symbolId).get(0).getMarginMode(), is(MarginMode.CROSS));
//            });
//
//            // 平仓成功
//            container.createAskWithOrderId(makerOrderId3, userId1, 1, 10500, symbolId, MarginMode.CROSS);
//            container.createBidWithOrderId(takerOrderId4, userId2, 1, 10500, symbolId, MarginMode.CROSS);
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(0));
//            });
//
//            // 开ISOLATION仓成功
//            container.createBidWithOrderId(makerOrderId1, userId1, 1, 11000, symbolId, MarginMode.ISOLATED);
//            container.createAskWithOrderId(takerOrderId2, userId2, 1, 11000, symbolId, MarginMode.ISOLATED);
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().get(symbolId).get(0).getMarginMode(), is(MarginMode.ISOLATED));
//            });
//
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//    }
//
//    // pendingSellAvgPrice和pendingBuyAvgPrice计算准确
//    @Test
//    public void testPendingAvgPrice() {
//        long deposit = 10000L;
//        long userId1 = 1003L;
//        long userId2 = 1004L;
//        long userId3 = 1005L;
//        int size = 1;
//        long price1 = 10000;
//        long price2 = 15000;
//        long makerOrderId1 = 1005L;
//        long takerOrderId2 = 1006L;
//        long makerOrderId3 = 1007L;
//        long takerOrderId4 = 1008L;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            container.initFutureSymbol(symbolId, quoteId);
//            initCurrencies(container);
//            container.initMarkPrice(symbolId, 10000);
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
//            container.createUserWithSpecificMoney(userId3, MAX_VALUE, quoteId);
//
//            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbolId, MarginMode.CROSS);
//            container.createBidWithOrderId(makerOrderId3, userId1, size, price2, symbolId, MarginMode.CROSS);
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().get(symbolId).get(0).pendingBuyAvgPrice, is((price1 + price2) / 2));
//            });
//
//            container.createAskWithOrderId(takerOrderId4, userId2, size, price2, symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(takerOrderId2, userId2, size, price1, symbolId, MarginMode.CROSS);
//            // 完全成交后avgPrice为0
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().get(symbolId).get(0).pendingBuyAvgPrice, is(0L));
//            });
//            // 完全成交后avgPrice为0
//            container.validateUserState(userId2, profile -> {
//                assertThat(profile.getPositions().get(symbolId).get(0).pendingSellAvgPrice, is(0L));
//            });
//
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//    }
//
//    // withdraw要考虑全仓该币种所有当前期货持仓 -- 空仓
//    @Test
//    public void testCrossMarginWithdraw() {
//        long deposit = 10000L;
//        long userId1 = 1003L;
//        int size = 1;
//        long price1 = 10000;
//        long price2 = 15000;
//        long makerOrderId1 = 1005L;
//        long takerOrderId2 = 1006L;
//        long makerOrderId3 = 1007L;
//        long takerOrderId4 = 1008L;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
//            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//
//            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(makerOrderId3, userId1, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(deposit));
//            });
//
//            ApiAdjustUserBalance cmd = ApiAdjustUserBalance.builder().uid(userId1).transactionId(container.getRandomTransactionId() + 100).amount(-deposit).currency(quoteId).build();
//
//            container.submitCommandSync(cmd, CommandResultCode.RISK_NSF);
//
//            // locked margin is 570
//            container.addMoneyToUser(userId1, quoteId, 569);
//            container.submitCommandSync(cmd, CommandResultCode.RISK_NSF);
//
//            container.addMoneyToUser(userId1, quoteId, 1);
//            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);
//
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//    }
//
//    // withdraw要考虑全仓该币种所有当前期货持仓 -- 持仓
//    @Test
//    public void testCrossMarginWithdraw2() {
//        long deposit = 10000L;
//        long userId1 = 1003L;
//        long userId2 = 1004L;
//        long userId3 = 1005L;
//        int size = 1;
//        long price1 = 10000;
//        long price2 = 15000;
//        long makerOrderId1 = 1005L;
//        long takerOrderId2 = 1006L;
//        long makerOrderId3 = 1007L;
//        long takerOrderId4 = 1008L;
//        long makerOrderId5 = 1009L;
//        long takerOrderId6 = 1010L;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
//            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
//            container.createUserWithSpecificMoney(userId3, MAX_VALUE, quoteId);
//
//            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(makerOrderId3, userId1, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);
//
//            container.createAskWithOrderId(takerOrderId2, userId2, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
//            container.createBidWithOrderId(takerOrderId4, userId2, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);
//
//            // symbol0 10 fixed maker fee
//            long fee1 = symbols.get(0).makerFee;
//            // symbol1 1% maker fee
//            long fee2 = container.calculateFee(price2, 1, 1, symbols.get(1).makerFee, symbols.get(1).feeScaleK);
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(2));
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee1 - fee2));
//            });
//
//            container.updateCurrentPriceTo(15000, symbols.get(0).symbolId, quoteId);
//            container.updateCurrentPriceTo(5000, symbols.get(1).symbolId, quoteId);
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(2));
//            });
//
//            ApiAdjustUserBalance cmd = ApiAdjustUserBalance.builder().uid(userId1).transactionId(container.getRandomTransactionId() + 500).amount(-deposit).currency(quoteId).build();
//            // 用户profit比较高, 但是提现额度高于了account存的钱, 此时不应该允许用户提现
//            container.submitCommandSync(cmd, CommandResultCode.RISK_NSF);
//
//            // 用户平仓symbol0
//            container.createAskWithOrderId(makerOrderId5, userId1, size, 15000, symbols.get(0).symbolId, MarginMode.CROSS);
//            container.createBidWithOrderId(takerOrderId6, userId3, size, 15000, symbols.get(0).symbolId, MarginMode.CROSS);
//
//            // 平仓不收手续费, 因为降低了整体风险
//            long fee3 = 0;
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee1 - fee2 - fee3 + 5000L));
//            });
//
//            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);
//
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//    }
//
//    // 下现货单时要考虑该币种所有当前期货持仓 -- 空仓
//    @Test
//    public void testPlaceExchange() {
//        long deposit = 10000L;
//        long userId1 = 1003L;
//        int size = 1;
//        long price1 = 10000;
//        long price2 = 15000;
//        long makerOrderId1 = 1005L;
//        long makerOrderId2 = 1007L;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
//            List<CoreSymbolSpecification> symbolsExchange = container.initExchangeSymbols();
//            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//
//            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
//
//            ApiPlaceOrder order = container.genOrder(userId1, 1, 10000, symbolsExchange.get(0).symbolId, BID, GTC);
//
//            container.submitCommandSync(order, CommandResultCode.RISK_NSF);
//
//            // fee is 20, margin required is 120, total = 140
//            container.addMoneyToUser(userId1, quoteId, 140 - 1);
//            container.submitCommandSync(order, CommandResultCode.RISK_NSF);
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getOrders().size(), is(1));
//            });
//
//            container.addMoneyToUser(userId1, quoteId, 1);
//            container.submitCommandSync(order, CommandResultCode.SUCCESS);
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(1));
//                // exchange dedect balance and fee first
//                assertThat(profile.getAccounts().get(quoteId), is(120L));
//                assertThat(profile.getOrders().size(), is(2));
//            });
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//    }
//
//    // 下现货单时要考虑该币种所有当前期货持仓 -- 开仓成功且有profit
//    @Test
//    public void testPlaceExchange2() {
//        long deposit = 10000L;
//        long userId1 = 1003L;
//        long userId2 = 1004L;
//        int size = 1;
//        long price1 = 10000;
//        long price2 = 15000;
//        long makerOrderId1 = 1005L;
//        long takerOrderId2 = 1006L;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
//            List<CoreSymbolSpecification> symbolsExchange = container.initExchangeSymbols();
//            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
//
//            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(takerOrderId2, userId2, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
//
//            container.updateCurrentPriceTo(15000, symbols.get(0).symbolId, quoteId);
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(1));
//            });
//
//            ApiPlaceOrder order = container.genOrder(userId1, 1, 10000, symbolsExchange.get(0).symbolId, BID, GTC);
//            // 1. free必须 > 0
//            // 2. margin亏的时候也要考虑
//            container.submitCommandSync(order, CommandResultCode.RISK_NSF);
//
//            container.addMoneyToUser(userId1, quoteId, 29);
//            container.submitCommandSync(order, CommandResultCode.RISK_NSF);
//
//            container.addMoneyToUser(userId1, quoteId, 1);
//            container.submitCommandSync(order, CommandResultCode.RISK_NSF);
//
//            // maintainance margin还少75块钱
//            container.addMoneyToUser(userId1, quoteId, 74);
//            container.submitCommandSync(order, CommandResultCode.RISK_NSF);
//
//            container.addMoneyToUser(userId1, quoteId, 1);
//            container.submitCommandSync(order, CommandResultCode.SUCCESS);
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(1));
//            });
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//    }
//
//    // cross liquidation - MA-4925 [Improvement] cross强平时最好能检查强平结果
//    @Test
//    public void testCrossMarginLiquidation() {
//        long deposit = 10000L;
//        long userId1 = 1003L;
//        long userId2 = 1004L;
//        long userId3 = 1005L;
//        int size = 1;
//        long price1 = 10000;
//        long price2 = 15000;
//        long makerOrderId1 = 1005L;
//        long takerOrderId2 = 1006L;
//        long makerOrderId3 = 1007L;
//        long takerOrderId4 = 1008L;
//        long makerOrderId5 = 1009L;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            container.getExchangeCore().getLiquidationScanner().stop(10, TimeUnit.MINUTES);
//            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
//            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
//            container.createUserWithSpecificMoney(userId3, MAX_VALUE, quoteId);
//
//            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(makerOrderId3, userId1, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);
//
//            container.createAskWithOrderId(takerOrderId2, userId2, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
//            container.createBidWithOrderId(takerOrderId4, userId2, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(2));
//            });
//
//            container.updateCurrentPriceTo(2000, symbols.get(0).symbolId, quoteId);
//            container.updateCurrentPriceTo(35000, symbols.get(1).symbolId, quoteId);
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(2));
//            });
//
//            // userId3先设置一个单子用于强制平仓, 此时
//            container.createBidWithOrderId(makerOrderId5, userId3, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
//
//            container.getExchangeCore().getLiquidationScanner().triggerOnce();
//            // 期待结果makerOrderId5可以被吃掉, position数量为1
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(1));
//            });
//
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//    }
//
//    // cross liquidation - check剩余订单的仓位信息
//    @Test
//    public void testCrossMarginLiquidation2() {
//        long deposit = 10000L;
//        long userId1 = 1003L;
//        long userId2 = 1004L;
//        long userId3 = 1005L;
//        int size = 1;
//        long price1 = 10000;
//        long price2 = 15000;
//        long makerOrderId1 = 1005L;
//        long takerOrderId2 = 1006L;
//        long makerOrderId3 = 1007L;
//        long takerOrderId4 = 1008L;
//        long makerOrderId5 = 1009L;
//        long makerOrderId6 = 1010L;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            container.getExchangeCore().getLiquidationScanner().stop(10, TimeUnit.MINUTES);
//            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
//            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
//            container.createUserWithSpecificMoney(userId3, MAX_VALUE, quoteId);
//
//            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(makerOrderId3, userId1, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);
//
//            container.createAskWithOrderId(takerOrderId2, userId2, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
//            container.createBidWithOrderId(takerOrderId4, userId2, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(2));
//            });
//
//            container.updateCurrentPriceTo(8000, symbols.get(0).symbolId, quoteId);
//            container.updateCurrentPriceTo(25000, symbols.get(1).symbolId, quoteId);
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(2));
//            });
//
//            // userId3先设置一个单子用于强制平仓, 此时
//            container.createBidWithOrderId(makerOrderId5, userId3, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(makerOrderId6, userId3, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);
//
//            container.getExchangeCore().getLiquidationScanner().triggerOnce();
//            // 期待结果makerOrderId6可以被挂出的强平吃掉
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(9840L));
//                assertThat(profile.getPositions().size(), is(1));
//                assertThat(profile.getPositions().getFirst().get(0).direction, is(PositionDirection.LONG));
//                assertThat(profile.getPositions().getFirst().get(0).quoteCurrency, is(quoteId));
//                assertThat(profile.getPositions().getFirst().get(0).openVolume, is(1L));
//                assertThat(profile.getPositions().getFirst().get(0).openPriceSum, is(10000L));
//            });
//
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        }
//    }
//
//    // cross liquidation - 混合isolated和cross强平, isolated先被强平
//    @Test
//    public void testCrossMarginLiquidation3() {
//        long deposit = 10000L;
//        long userId1 = 11003L;
//        long userId2 = 11004L;
//        long userId3 = 11005L;
//        long userId4 = 11006L;
//        long userId5 = 11007L;
//        long userId6 = 11008L;
//        int size = 1;
//        long price1 = 10000;
//        long price2 = 15000;
//        long makerOrderId1 = 1005L;
//        long takerOrderId1 = 1006L;
//        long makerOrderId2 = 1007L;
//        long takerOrderId2 = 1008L;
//        long makerOrderId3 = 1009L;
//        long takerOrderId3 = 1010L;
//        long makerOrderId4 = 1011L;
//        long takerOrderId4 = 1012L;
//        long makerOrderId5 = 1013L;
//        long takerOrderId5 = 1014L;
//        long makerOrderId6 = 1015L;
//        long takerOrderId6 = 1016L;
//        long makerOrderId7 = 1017L;
//        long takerOrderId7 = 1018L;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            container.getExchangeCore().getLiquidationScanner().stop(10, TimeUnit.MINUTES);
//            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
//            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
//            container.createUserWithSpecificMoney(userId3, deposit, quoteId);
//            container.createUserWithSpecificMoney(userId4, MAX_VALUE, quoteId);
//            container.createUserWithSpecificMoney(userId5, MAX_VALUE, quoteId);
//            container.createUserWithSpecificMoney(userId6, MAX_VALUE, quoteId);
//
//            // userId1 and userId2 match
//            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(makerOrderId2, userId1, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);
//
//            container.createAskWithOrderId(takerOrderId1, userId2, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
//            container.createBidWithOrderId(takerOrderId2, userId2, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);
//
//            // userId3 and userId4 match
//            container.createBidWithOrderId(makerOrderId3, userId3, size, price1, symbols.get(0).symbolId, MarginMode.ISOLATED);
//            container.createAskWithOrderId(makerOrderId4, userId3, size, price2, symbols.get(1).symbolId, MarginMode.ISOLATED);
//
//            container.createAskWithOrderId(takerOrderId3, userId4, size, price1, symbols.get(0).symbolId, MarginMode.ISOLATED);
//            container.createBidWithOrderId(takerOrderId4, userId4, size, price2, symbols.get(1).symbolId, MarginMode.ISOLATED);
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(2));
//            });
//
//            container.validateUserState(userId3, profile -> {
//                assertThat(profile.getPositions().size(), is(2));
//            });
//
//            // 价格降到时, userId3因为是isolated开始触发强平, 但是此时userId1因为开的是cross margin(symbol1做多)所以没达到强平
//            // userId1 symbol0: 10000 - 100 = -9900
//            //         symbol1: 15000 - 1000 = 5000
//            //         total profit = -4900
//            //         balance = 9840
//            //         profit + balance > 50(symbol0 maintenance margin) + 100(symbol1 maintenance margin)
//            // userId3 symbol0: 10000 - 100 = -9900
//            //         profit: -9900
//            //         init margin: 100
//            //         profit + init margin < 50 所以会被强平
//            container.updateCurrentPriceTo(100, symbols.get(0).symbolId, quoteId);
//            container.updateCurrentPriceTo(10000, symbols.get(1).symbolId, quoteId);
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(2));
//            });
//
//            container.validateUserState(userId3, profile -> {
//                assertThat(profile.getPositions().size(), is(2));
//            });
//
//            // userId5先设置一个单子(两手)用于强制平仓, 此时userId3会被强平, userId1不会强平
//            container.createBidWithOrderId(makerOrderId5, userId5, 2, 100, symbols.get(0).symbolId, MarginMode.CROSS);
//
//            container.getExchangeCore().getLiquidationScanner().triggerOnce();
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(9840L));
//                assertThat(profile.getPositions().size(), is(2));
//            });
//            // 期待结果makerOrderId5可以被挂出的强平吃掉
//            container.validateUserState(userId3, profile -> {
//                assertThat(profile.getPositions().size(), is(1));
//            });
//            // userId5挂上去的订单2手被吃掉1手
//            container.validateUserState(userId5, profile -> {
//                assertThat(profile.getOrders().size(), is(1));
//                assertThat(profile.getOrders().getFirst().get(0).filled, is(1L));
//                assertThat(profile.getOrders().getFirst().get(0).size, is(2L));
//                assertThat(profile.getOrders().getFirst().get(0).price, is(100L));
//            });
//
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            verify(handler, times(55)).fundsEvent(fundEventCapor.capture());
//            // check fund event
//            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
//            IFundEventsHandler.FundsEvent liquidationAlertEvt = fundEvents.get(47);
//            assertThat(userId3, Is.is(liquidationAlertEvt.uid));
//            assertThat(quoteId, Is.is(liquidationAlertEvt.currency));
//            assertThat(10000, Is.is(liquidationAlertEvt.symbol));
////            assertThat(takerOrderId, Is.is(liquidationAlertEvt.orderId));
//            assertThat(0L, Is.is(liquidationAlertEvt.fee));
//            assertThat(PositionDirection.LONG, Is.is(liquidationAlertEvt.direction));
//            assertThat(FundEvent.FundEventType.LIQUIDATION_ALERT, Is.is(liquidationAlertEvt.eventType));
//            assertThat(0L, Is.is(liquidationAlertEvt.free));
//            assertThat(0L, Is.is(liquidationAlertEvt.profit));
//            assertThat(0L, Is.is(liquidationAlertEvt.locked));
//            assertThat(10000L, Is.is(liquidationAlertEvt.openPriceSum));
//            assertThat(1L, Is.is(liquidationAlertEvt.openVolume));
//            assertThat(1L, Is.is(liquidationAlertEvt.tradeSize));
//            assertThat(100L, Is.is(liquidationAlertEvt.tradePrice));
//            // 10000价格跌到100, unrealizedProfit = 100 - 10000 = -9900
//            assertThat(-9900L, Is.is(liquidationAlertEvt.unrealizedProfit));
//            // 逐仓强平价格计算
//            assertThat(9900L, Is.is(liquidationAlertEvt.liquidationPrice));
//            assertThat(-1000L, Is.is(liquidationAlertEvt.marginRatioScaleK));
//
//            IFundEventsHandler.FundsEvent liquidationEvt = fundEvents.get(49);
////            assertThat(userId3, Is.is(liquidationEvt.uid));
//            assertThat(quoteId, Is.is(liquidationEvt.currency));
//            assertThat(10000, Is.is(liquidationEvt.symbol));
////            assertThat(takerOrderId, Is.is(liquidationEvt.orderId));
//            assertThat(0L, Is.is(liquidationEvt.fee));
//            assertThat(PositionDirection.LONG, Is.is(liquidationEvt.direction));
//            assertThat(FundEvent.FundEventType.LIQUIDATION, Is.is(liquidationEvt.eventType));
//            assertThat(9740L, Is.is(liquidationEvt.free));
//            assertThat(100L, Is.is(liquidationEvt.locked));
//            assertThat(0L, Is.is(liquidationEvt.openPriceSum));
//            assertThat(-9900L, Is.is(liquidationEvt.profit));
//            assertThat(0L, Is.is(liquidationEvt.openVolume));
//            assertThat(1L, Is.is(liquidationEvt.tradeSize));
//            assertThat(100L, Is.is(liquidationEvt.tradePrice));
//            // 平仓后openVolume为0, 故unrealizedProfit/liquidationPrice/marginRatioScaleK均为0
//            checkEvent(liquidationEvt);
//        }
//    }
//
//    // cross liquidation - profit + balance < 1.2 * maintenance margin时需要触发报警
//    @Test
//    public void testCrossMarginLiquidationWarning() {
//        long deposit = 10000L;
//        long userId1 = 11003L;
//        long userId2 = 11004L;
//        long userId3 = 11005L;
//        int size = 1;
//        long price1 = 10000;
//        long price2 = 15000;
//        long makerOrderId1 = 1005L;
//        long takerOrderId1 = 1006L;
//        long makerOrderId2 = 1007L;
//        long takerOrderId2 = 1008L;
//
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            container.getExchangeCore().getLiquidationScanner().stop(10, TimeUnit.MINUTES);
//            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
//            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
//            container.createUserWithSpecificMoney(userId3, deposit, quoteId);
//
//            // userId1 and userId2 match
//            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(makerOrderId2, userId1, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);
//
//            container.createAskWithOrderId(takerOrderId1, userId2, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
//            container.createBidWithOrderId(takerOrderId2, userId2, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(2));
//            });
//
//            // 价格降到时, userId3因为是isolated开始触发强平, 但是此时userId1因为开的是cross margin(symbol1做多)所以没达到强平
//            // userId1 symbol0: 10000 - 5300 = 4700
//            //         symbol1: 15000 - 2000 = 5000
//            //         total profit = -9700
//            //         balance = 9840
//            //         profit + balance = -9700 + 9840 = 140 < 151(126 * 1.2)此时会触发alert
//
//            container.updateCurrentPriceTo(5300, symbols.get(0).symbolId, quoteId);
//            container.updateCurrentPriceTo(20000, symbols.get(1).symbolId, quoteId);
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getPositions().size(), is(2));
//            });
//
//            container.getExchangeCore().getLiquidationScanner().triggerOnce();
//
//            container.validateUserState(userId1, profile -> {
//                assertThat(profile.getAccounts().get(quoteId), is(9840L));
//                assertThat(profile.getPositions().size(), is(2));
//            });
//
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            verify(handler, times(34)).fundsEvent(fundEventCapor.capture());
//            // check fund event
//            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
//            IFundEventsHandler.FundsEvent event1 = fundEvents.get(31);
////            assertThat(userId1, Is.is(event1.uid));
//            assertThat(quoteId, Is.is(event1.currency));
//            assertThat(10000, Is.is(event1.symbol));
//            assertThat(0L, Is.is(event1.orderId));
//            assertThat(0L, Is.is(event1.fee));
//            assertThat(PositionDirection.LONG, Is.is(event1.direction));
//            assertThat(FundEvent.FundEventType.MARGIN_ALERT, Is.is(event1.eventType));
//            assertThat(0L, Is.is(event1.free));
//            assertThat(0L, Is.is(event1.locked));
//            assertThat(10000L, Is.is(event1.openPriceSum));
//            assertThat(1L, Is.is(event1.openVolume));
//            assertThat(0L, Is.is(event1.tradeSize));
//            assertThat(0L, Is.is(event1.tradePrice));
//            assertThat(-4700L, Is.is(event1.unrealizedProfit));
//            assertThat(-1L, Is.is(event1.liquidationPrice));
//            assertThat(1L, Is.is(event1.marginRatioScaleK));
//        }
//    }
//
//    // check unrealizedProfit/liquidationPrice/marginRatioScaleK should be zero
//    private void checkEvent(IFundEventsHandler.FundsEvent evt) {
//        assertThat(0L, Is.is(evt.unrealizedProfit));
//        assertThat(0L, Is.is(evt.liquidationPrice));
//        assertThat(0L, Is.is(evt.marginRatioScaleK));
//    }
//
//    // 开仓事件, 完全成交, taker为Ask
//    @Test
//    public void testOpenPosition4Bid() throws InterruptedException {
//        int deposit = 1000;
//        long userId1 = 1003L;
//        long userId2 = 1004L;
//        long makerOrderId = 1005L;
//        long takerOrderId = 1006L;
//        int size = 1;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            container.initFutureSymbol(symbolId, quoteId);
//            initCurrencies(container);
//            container.initMarkPrice(symbolId, 10000);
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
//
//            container.createBidWithOrderId(makerOrderId, userId1, size, 10000, symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(takerOrderId, userId2, size, 10000, symbolId, MarginMode.CROSS);
//
//            verify(handler, times(9)).commandResult(commandResultCaptor.capture());
//            verify(handler, never()).reduceEvent(any());
//            verify(handler, never()).rejectEvent(any());
//            verify(handler, times(1)).tradeEvent(tradeEventCaptor.capture());
//
//            final ITradeEventsHandler.TradeEvent tradeEvent = tradeEventCaptor.getAllValues().get(0);
//            assertThat(tradeEvent.getSymbol(), Is.is(symbolId));
//            assertThat(tradeEvent.getTotalVolume(), Is.is(1L));
//            assertThat(tradeEvent.getTakerOrderId(), Is.is(takerOrderId));
//            assertThat(tradeEvent.getTakerUid(), Is.is(userId2));
//            assertThat(tradeEvent.getTakerAction(), Is.is(OrderAction.ASK));
//            assertThat(tradeEvent.takeOrderCompleted, Is.is(true));
//            assertThat(tradeEvent.trades.get(0).getMakerUid(), Is.is(userId1));
//            assertThat(tradeEvent.trades.get(0).makerOrderId, Is.is(makerOrderId));
//            assertThat(tradeEvent.trades.get(0).makerOrderCompleted, Is.is(true));
//            assertThat(tradeEvent.trades.get(0).price, Is.is(10000L));
//            assertThat(tradeEvent.trades.get(0).getVolume(), Is.is(1L));
//
//            // check balance
//            SingleUserReportResult user1Report = container.getUserProfile(userId1);
//            SingleUserReportResult user2Report = container.getUserProfile(userId2);
//            assertThat(user1Report.getAccounts().get(quoteId), Is.is((deposit - 10L)));
//            assertThat(user2Report.getAccounts().get(quoteId), Is.is((MAX_VALUE - 20L)));
//
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } finally {
//            verify(handler, times(8)).fundsEvent(fundEventCapor.capture());
//            // check fund event
//            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
//            IFundEventsHandler.FundsEvent takerEvent = fundEvents.get(2);
//
//            // check lock_pending event for maker, orderId should be maker's
//            assertThat(userId1, Is.is(takerEvent.uid));
//            assertThat(quoteId, Is.is(takerEvent.currency));
//            assertThat(symbolId, Is.is(takerEvent.symbol));
//            assertThat(makerOrderId, Is.is(takerEvent.orderId));
//            assertThat(0L, Is.is(takerEvent.fee));
//            assertThat(PositionDirection.LONG, Is.is(takerEvent.direction));
//            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(takerEvent.eventType));
//            // 1000(balance) - 100(open position) - 20(maker fee) = 880
//            assertThat(deposit - 100L - size * 20L, Is.is(takerEvent.free));
//            assertThat(100L + size * 20L, Is.is(takerEvent.locked));
//            assertThat(0L, Is.is(takerEvent.openPriceSum));
//            assertThat(0L, Is.is(takerEvent.openVolume));
//            assertThat(0L, Is.is(takerEvent.tradeSize));
//            assertThat(0L, Is.is(takerEvent.tradePrice));
//            checkEvent(takerEvent);
//
//            // check lock_pending event for taker, orderId should be maker's
//            IFundEventsHandler.FundsEvent makerEvent = fundEvents.get(3);
//            assertThat(userId2, Is.is(makerEvent.uid));
//            assertThat(quoteId, Is.is(makerEvent.currency));
//            assertThat(symbolId, Is.is(makerEvent.symbol));
//            assertThat(makerOrderId, Is.is(takerEvent.orderId));
//            assertThat(0L, Is.is(makerEvent.fee));
//            assertThat(PositionDirection.SHORT, Is.is(makerEvent.direction));
//            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(makerEvent.eventType));
//            assertThat(MAX_VALUE - 100L - size * 20L, Is.is(makerEvent.free));
//            assertThat(100L + size * 20L, Is.is(makerEvent.locked));
//            assertThat(0L, Is.is(makerEvent.openPriceSum));
//            assertThat(0L, Is.is(makerEvent.openVolume));
//            assertThat(0L, Is.is(makerEvent.tradeSize));
//            assertThat(0L, Is.is(makerEvent.tradePrice));
//            checkEvent(makerEvent);
//
//            // check unlock_pending event for taker
//            IFundEventsHandler.FundsEvent takerUnlockEvent = fundEvents.get(4);
//            assertThat(userId2, Is.is(takerUnlockEvent.uid));
//            assertThat(quoteId, Is.is(takerUnlockEvent.currency));
//            assertThat(symbolId, Is.is(takerUnlockEvent.symbol));
//            assertThat(takerOrderId, Is.is(takerUnlockEvent.orderId));
//            assertThat(0L, Is.is(takerUnlockEvent.fee));
//            assertThat(PositionDirection.SHORT, Is.is(takerUnlockEvent.direction));
//            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(takerUnlockEvent.eventType));
//            assertThat(MAX_VALUE, Is.is(takerUnlockEvent.free));
//            assertThat(0L, Is.is(takerUnlockEvent.locked));
//            assertThat(0L, Is.is(takerUnlockEvent.openPriceSum));
//            assertThat(0L, Is.is(takerUnlockEvent.openVolume));
//            assertThat(0L, Is.is(takerUnlockEvent.tradeSize));
//            assertThat(0L, Is.is(takerUnlockEvent.tradePrice));
//            checkEvent(takerUnlockEvent);
//
//            // check open position event for taker
//            IFundEventsHandler.FundsEvent takerOpenPositionEvent = fundEvents.get(5);
//            assertThat(userId2, Is.is(takerOpenPositionEvent.uid));
//            assertThat(quoteId, Is.is(takerOpenPositionEvent.currency));
//            assertThat(symbolId, Is.is(takerOpenPositionEvent.symbol));
//            assertThat(takerOrderId, Is.is(takerOpenPositionEvent.orderId));
//            assertThat(20L, Is.is(takerOpenPositionEvent.fee));
//            assertThat(PositionDirection.SHORT, Is.is(takerOpenPositionEvent.direction));
//            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(takerOpenPositionEvent.eventType));
//            // free = init value - cost - fee
//            assertThat(MAX_VALUE - 100 - 20, Is.is(takerOpenPositionEvent.free));
//            assertThat(100L, Is.is(takerOpenPositionEvent.locked));
//            assertThat(10000L, Is.is(takerOpenPositionEvent.openPriceSum));
//            assertThat(1L, Is.is(takerOpenPositionEvent.openVolume));
//            assertThat(1L, Is.is(takerOpenPositionEvent.tradeSize));
//            assertThat(10000L, Is.is(takerOpenPositionEvent.tradePrice));
//            // 标记价格和开仓价格相同, 所以算出来的unrealizedProfit=0
//            assertThat(0L, Is.is(takerOpenPositionEvent.unrealizedProfit));
//            // maintenanceMargin = 50
//            // liquidationPrice = direction * (maintenanceMargin - totalMargin) + openPriceSum = -1 * (50 - 100) + 10000 * 1 = 10050
//            assertThat(3990029L, Is.is(takerOpenPositionEvent.liquidationPrice));
//            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 50 / MAX_VALUE) = 0
//            assertThat(0L, Is.is(takerOpenPositionEvent.marginRatioScaleK));
//
//            // check unlock_pending event for maker
//            IFundEventsHandler.FundsEvent makerUnlockEvent = fundEvents.get(6);
//            assertThat(userId1, Is.is(makerUnlockEvent.uid));
//            assertThat(quoteId, Is.is(makerUnlockEvent.currency));
//            assertThat(symbolId, Is.is(makerUnlockEvent.symbol));
//            assertThat(makerOrderId, Is.is(makerUnlockEvent.orderId));
//            assertThat(0L, Is.is(makerUnlockEvent.fee));
//            assertThat(PositionDirection.LONG, Is.is(makerUnlockEvent.direction));
//            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(makerUnlockEvent.eventType));
//            assertThat(1000L, Is.is(makerUnlockEvent.free));
//            assertThat(0L, Is.is(makerUnlockEvent.locked));
//            assertThat(0L, Is.is(makerUnlockEvent.openPriceSum));
//            assertThat(0L, Is.is(makerUnlockEvent.openVolume));
//            assertThat(0L, Is.is(makerUnlockEvent.tradeSize));
//            assertThat(0L, Is.is(makerUnlockEvent.tradePrice));
//            checkEvent(makerUnlockEvent);
//
//            // check open position event
//            IFundEventsHandler.FundsEvent makerOpenPositionEvent = fundEvents.get(7);
//            assertThat(userId1, Is.is(makerOpenPositionEvent.uid));
//            assertThat(quoteId, Is.is(makerOpenPositionEvent.currency));
//            assertThat(symbolId, Is.is(makerOpenPositionEvent.symbol));
//            assertThat(makerOrderId, Is.is(makerOpenPositionEvent.orderId));
//            assertThat(10L, Is.is(makerOpenPositionEvent.fee));
//            assertThat(PositionDirection.LONG, Is.is(makerOpenPositionEvent.direction));
//            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(makerOpenPositionEvent.eventType));
//            // 1000(balance) - 100(open position) - 10(maker fee) = 890
//            assertThat(890L, Is.is(makerOpenPositionEvent.free));
//            assertThat(100L, Is.is(makerOpenPositionEvent.locked));
//            assertThat(10000L, Is.is(makerOpenPositionEvent.openPriceSum));
//            assertThat(1L, Is.is(makerOpenPositionEvent.openVolume));
//            assertThat(1L, Is.is(makerOpenPositionEvent.tradeSize));
//            assertThat(10000L, Is.is(makerOpenPositionEvent.tradePrice));
//            // openVolume * markPrice - openPriceSum = 10000 - 10000 = 0
//            assertThat(0L, Is.is(makerOpenPositionEvent.unrealizedProfit));
//            // liquidationPrice = direction * (maintenanceMargin - totalMargin) + openPriceSum = 1 * (50 - 100) + 10000 * 1 = 9050
//            assertThat(9055L, Is.is(makerOpenPositionEvent.liquidationPrice));
//            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 50 / 990) = long (50.5) = 50
//            assertThat(50L, Is.is(makerOpenPositionEvent.marginRatioScaleK));
//        }
//    }
//
//    // 开仓事件, 完全成交, taker为Bid
//    @Test
//    public void testOpenPosition4Ask() throws InterruptedException {
//        int deposit = 1000;
//        long userId1 = 1003L;
//        long userId2 = 1004L;
//        long makerOrderId = 1005L;
//        long takerOrderId = 1006L;
//        int size = 1;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            container.initFutureSymbol(symbolId, quoteId);
//            initCurrencies(container);
//            container.initMarkPrice(symbolId, 10000);
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
//
//            container.createAskWithOrderId(makerOrderId, userId1, size, 10000, symbolId, MarginMode.CROSS);
//            container.createBidWithOrderId(takerOrderId, userId2, size, 10000, symbolId, MarginMode.CROSS);
//
//            verify(handler, times(9)).commandResult(commandResultCaptor.capture());
//            verify(handler, never()).reduceEvent(any());
//            verify(handler, never()).rejectEvent(any());
//            verify(handler, times(1)).tradeEvent(tradeEventCaptor.capture());
//
//            final ITradeEventsHandler.TradeEvent tradeEvent = tradeEventCaptor.getAllValues().get(0);
//            assertThat(tradeEvent.getSymbol(), Is.is(symbolId));
//            assertThat(tradeEvent.getTotalVolume(), Is.is(1L));
//            assertThat(tradeEvent.getTakerOrderId(), Is.is(takerOrderId));
//            assertThat(tradeEvent.getTakerUid(), Is.is(userId2));
//            assertThat(tradeEvent.getTakerAction(), Is.is(OrderAction.BID));
//            assertThat(tradeEvent.takeOrderCompleted, Is.is(true));
//            assertThat(tradeEvent.trades.get(0).getMakerUid(), Is.is(userId1));
//            assertThat(tradeEvent.trades.get(0).makerOrderId, Is.is(makerOrderId));
//            assertThat(tradeEvent.trades.get(0).makerOrderCompleted, Is.is(true));
//            assertThat(tradeEvent.trades.get(0).price, Is.is(10000L));
//            assertThat(tradeEvent.trades.get(0).getVolume(), Is.is(1L));
//
//            // check balance
//            SingleUserReportResult user1Report = container.getUserProfile(userId1);
//            SingleUserReportResult user2Report = container.getUserProfile(userId2);
//            assertThat(user1Report.getAccounts().get(quoteId), Is.is((deposit - 10L)));
//            assertThat(user2Report.getAccounts().get(quoteId), Is.is((MAX_VALUE - 20L)));
//
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } finally {
//            verify(handler, times(8)).fundsEvent(fundEventCapor.capture());
//            // check fund event
//            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
//            IFundEventsHandler.FundsEvent takerEvent = fundEvents.get(2);
//
//            // check lock_pending event for maker, orderId should be maker's
//            assertThat(userId1, Is.is(takerEvent.uid));
//            assertThat(quoteId, Is.is(takerEvent.currency));
//            assertThat(symbolId, Is.is(takerEvent.symbol));
//            assertThat(makerOrderId, Is.is(takerEvent.orderId));
//            assertThat(0L, Is.is(takerEvent.fee));
//            assertThat(PositionDirection.SHORT, Is.is(takerEvent.direction));
//            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(takerEvent.eventType));
//            // free = 1000(deposit) - 100(required margin) - 20 * size(taker fee) = 880
//            assertThat(deposit - 100L - size * 20L, Is.is(takerEvent.free));
//            assertThat(100L + size * 20L, Is.is(takerEvent.locked));
//            assertThat(0L, Is.is(takerEvent.openPriceSum));
//            assertThat(0L, Is.is(takerEvent.openVolume));
//            assertThat(0L, Is.is(takerEvent.tradeSize));
//            assertThat(0L, Is.is(takerEvent.tradePrice));
//            checkEvent(takerEvent);
//
//            // check lock_pending event for taker, orderId should be maker's
//            IFundEventsHandler.FundsEvent makerEvent = fundEvents.get(3);
//            assertThat(userId2, Is.is(makerEvent.uid));
//            assertThat(quoteId, Is.is(makerEvent.currency));
//            assertThat(symbolId, Is.is(makerEvent.symbol));
//            assertThat(makerOrderId, Is.is(takerEvent.orderId));
//            assertThat(0L, Is.is(makerEvent.fee));
//            assertThat(PositionDirection.LONG, Is.is(makerEvent.direction));
//            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(makerEvent.eventType));
//            // free = MAX_VALUE(deposit) - 100(required margin) - 20 * size(taker fee)
//            assertThat(MAX_VALUE - 100L - size * 20L, Is.is(makerEvent.free));
//            // 注意, 下单时是按照taker fee做lock
//            assertThat(100L + size * 20L, Is.is(makerEvent.locked));
//            assertThat(0L, Is.is(makerEvent.openPriceSum));
//            assertThat(0L, Is.is(makerEvent.openVolume));
//            assertThat(0L, Is.is(makerEvent.tradeSize));
//            assertThat(0L, Is.is(makerEvent.tradePrice));
//            checkEvent(makerEvent);
//
//            // check unlock_pending event for taker
//            IFundEventsHandler.FundsEvent takerUnlockEvent = fundEvents.get(4);
//            assertThat(userId2, Is.is(takerUnlockEvent.uid));
//            assertThat(quoteId, Is.is(takerUnlockEvent.currency));
//            assertThat(symbolId, Is.is(takerUnlockEvent.symbol));
//            assertThat(takerOrderId, Is.is(takerUnlockEvent.orderId));
//            assertThat(0L, Is.is(takerUnlockEvent.fee));
//            assertThat(PositionDirection.LONG, Is.is(takerUnlockEvent.direction));
//            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(takerUnlockEvent.eventType));
//            assertThat(MAX_VALUE, Is.is(takerUnlockEvent.free));
//            assertThat(0L, Is.is(takerUnlockEvent.locked));
//            assertThat(0L, Is.is(takerUnlockEvent.openPriceSum));
//            assertThat(0L, Is.is(takerUnlockEvent.openVolume));
//            assertThat(0L, Is.is(takerUnlockEvent.tradeSize));
//            assertThat(0L, Is.is(takerUnlockEvent.tradePrice));
//            checkEvent(takerUnlockEvent);
//
//            // check open position event for taker
//            IFundEventsHandler.FundsEvent takerOpenPositionEvent = fundEvents.get(5);
//            assertThat(userId2, Is.is(takerOpenPositionEvent.uid));
//            assertThat(quoteId, Is.is(takerOpenPositionEvent.currency));
//            assertThat(symbolId, Is.is(takerOpenPositionEvent.symbol));
//            assertThat(takerOrderId, Is.is(takerOpenPositionEvent.orderId));
//            assertThat(20L, Is.is(takerOpenPositionEvent.fee));
//            assertThat(PositionDirection.LONG, Is.is(takerOpenPositionEvent.direction));
//            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(takerOpenPositionEvent.eventType));
//            // free = init value - cost - fee
//            assertThat(MAX_VALUE - 100 - 20, Is.is(takerOpenPositionEvent.free));
//            assertThat(100L, Is.is(takerOpenPositionEvent.locked));
//            assertThat(10000L, Is.is(takerOpenPositionEvent.openPriceSum));
//            assertThat(1L, Is.is(takerOpenPositionEvent.openVolume));
//            assertThat(1L, Is.is(takerOpenPositionEvent.tradeSize));
//            assertThat(10000L, Is.is(takerOpenPositionEvent.tradePrice));
//            // 标记价格和开仓价格相同, 所以算出来的unrealizedProfit=0
//            assertThat(0L, Is.is(takerOpenPositionEvent.unrealizedProfit));
//            // maintenanceMargin = 50
//            // liquidationPrice = direction * (maintenanceMargin - totalMargin) + openPriceSum = 1 * (50 - 100) + 10000 * 1 = 9050
//            assertThat(-1L, Is.is(takerOpenPositionEvent.liquidationPrice));
//            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 50 / MAX_VALUE) = 0
//            assertThat(0L, Is.is(takerOpenPositionEvent.marginRatioScaleK));
//
//            // check unlock_pending event for maker
//            IFundEventsHandler.FundsEvent makerUnlockEvent = fundEvents.get(6);
//            assertThat(userId1, Is.is(makerUnlockEvent.uid));
//            assertThat(quoteId, Is.is(makerUnlockEvent.currency));
//            assertThat(symbolId, Is.is(makerUnlockEvent.symbol));
//            assertThat(makerOrderId, Is.is(makerUnlockEvent.orderId));
//            assertThat(0L, Is.is(makerUnlockEvent.fee));
//            assertThat(PositionDirection.SHORT, Is.is(makerUnlockEvent.direction));
//            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(makerUnlockEvent.eventType));
//            assertThat(1000L, Is.is(makerUnlockEvent.free));
//            assertThat(0L, Is.is(makerUnlockEvent.locked));
//            assertThat(0L, Is.is(makerUnlockEvent.openPriceSum));
//            assertThat(0L, Is.is(makerUnlockEvent.openVolume));
//            assertThat(0L, Is.is(makerUnlockEvent.tradeSize));
//            assertThat(0L, Is.is(makerUnlockEvent.tradePrice));
//            checkEvent(makerUnlockEvent);
//
//            // check open position event
//            IFundEventsHandler.FundsEvent makerOpenPositionEvent = fundEvents.get(7);
//            assertThat(userId1, Is.is(makerOpenPositionEvent.uid));
//            assertThat(quoteId, Is.is(makerOpenPositionEvent.currency));
//            assertThat(symbolId, Is.is(makerOpenPositionEvent.symbol));
//            assertThat(makerOrderId, Is.is(makerOpenPositionEvent.orderId));
//            assertThat(10L, Is.is(makerOpenPositionEvent.fee));
//            assertThat(PositionDirection.SHORT, Is.is(makerOpenPositionEvent.direction));
//            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(makerOpenPositionEvent.eventType));
//            // 1000 - 100 - 10(taker fee)
//            assertThat(890L, Is.is(makerOpenPositionEvent.free));
//            assertThat(100L, Is.is(makerOpenPositionEvent.locked));
//            assertThat(10000L, Is.is(makerOpenPositionEvent.openPriceSum));
//            assertThat(1L, Is.is(makerOpenPositionEvent.openVolume));
//            assertThat(1L, Is.is(makerOpenPositionEvent.tradeSize));
//            assertThat(10000L, Is.is(makerOpenPositionEvent.tradePrice));
//            // openVolume * markPrice - openPriceSum = 10000 - 10000 = 0
//            assertThat(0L, Is.is(makerOpenPositionEvent.unrealizedProfit));
//            // liquidationPrice = direction * (maintenanceMargin - totalMargin) + openPriceSum = -1 * (50 - 100) + 10000 * 1 = 10050
//            assertThat(10935L, Is.is(makerOpenPositionEvent.liquidationPrice));
//            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 50 / 990) = long (50.5) = 50
//            assertThat(50L, Is.is(makerOpenPositionEvent.marginRatioScaleK));
//        }
//    }
//
//    // 开仓事件, 测试部分成单, taker为Ask
//    @Test
//    public void testOpenMultiplePosition4Bid() throws InterruptedException {
//        int size = 10;
//        int deposit = 1000 * size;
//        long userId1 = 1003L;
//        long userId2 = 1004L;
//        long makerOrderId = 1005L;
//        long takerOrderId = 1006L;
//        int txSize = 2;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            container.initFutureSymbol(symbolId, quoteId);
//            initCurrencies(container);
//            container.initMarkPrice(symbolId, 10000);
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
//
//            container.createBidWithOrderId(makerOrderId, userId1, size, 10000, symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(takerOrderId, userId2, txSize, 10000, symbolId, MarginMode.CROSS);
//
//            verify(handler, times(9)).commandResult(commandResultCaptor.capture());
//            verify(handler, never()).reduceEvent(any());
//            verify(handler, never()).rejectEvent(any());
//            verify(handler, times(1)).tradeEvent(tradeEventCaptor.capture());
//
//            final ITradeEventsHandler.TradeEvent tradeEvent = tradeEventCaptor.getAllValues().get(0);
//            assertThat(tradeEvent.getSymbol(), Is.is(symbolId));
//            assertThat(tradeEvent.getTotalVolume(), Is.is(2L));
//            assertThat(tradeEvent.getTakerOrderId(), Is.is(takerOrderId));
//            assertThat(tradeEvent.getTakerUid(), Is.is(userId2));
//            assertThat(tradeEvent.getTakerAction(), Is.is(OrderAction.ASK));
//            assertThat(tradeEvent.takeOrderCompleted, Is.is(true));
//            assertThat(tradeEvent.trades.get(0).getMakerUid(), Is.is(userId1));
//            assertThat(tradeEvent.trades.get(0).makerOrderId, Is.is(makerOrderId));
//            assertThat(tradeEvent.trades.get(0).makerOrderCompleted, Is.is(false));
//            assertThat(tradeEvent.trades.get(0).price, Is.is(10000L));
//            assertThat(tradeEvent.trades.get(0).getVolume(), Is.is(2L));
//
//            // check balance
//            SingleUserReportResult user1Report = container.getUserProfile(userId1);
//            SingleUserReportResult user2Report = container.getUserProfile(userId2);
//            assertThat(user1Report.getAccounts().get(quoteId), Is.is((deposit - 2 * 10L)));
//            assertThat(user2Report.getAccounts().get(quoteId), Is.is((MAX_VALUE - 2 * 20L)));
//
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } finally {
//            verify(handler, times(8)).fundsEvent(fundEventCapor.capture());
//            // check fund event
//            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
//            IFundEventsHandler.FundsEvent makerEvent = fundEvents.get(2);
//
//            // check lock_pending event for maker, orderId should be maker's
//            assertThat(userId1, Is.is(makerEvent.uid));
//            assertThat(quoteId, Is.is(makerEvent.currency));
//            assertThat(symbolId, Is.is(makerEvent.symbol));
//            assertThat(makerOrderId, Is.is(makerEvent.orderId));
//            assertThat(0L, Is.is(makerEvent.fee));
//            assertThat(PositionDirection.LONG, Is.is(makerEvent.direction));
//            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(makerEvent.eventType));
//            // free = 1000(deposit) - size * 100(required margin) - size * 20L(taker fee)
//            assertThat(deposit - size * 100L - size * 20L, Is.is(makerEvent.free));
//            assertThat(size * 100L + size * 20L, Is.is(makerEvent.locked));
//            assertThat(0L, Is.is(makerEvent.openPriceSum));
//            assertThat(0L, Is.is(makerEvent.openVolume));
//            assertThat(0L, Is.is(makerEvent.tradeSize));
//            assertThat(0L, Is.is(makerEvent.tradePrice));
//            checkEvent(makerEvent);
//
//            // check lock_pending event for taker, orderId should be maker's
//            IFundEventsHandler.FundsEvent takerEvent = fundEvents.get(3);
//            assertThat(userId2, Is.is(takerEvent.uid));
//            assertThat(quoteId, Is.is(takerEvent.currency));
//            assertThat(symbolId, Is.is(takerEvent.symbol));
//            assertThat(makerOrderId, Is.is(makerEvent.orderId));
//            assertThat(0L, Is.is(takerEvent.fee));
//            assertThat(PositionDirection.SHORT, Is.is(takerEvent.direction));
//            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(takerEvent.eventType));
//            // free = MAX_VALUE(deposit) - size * 100(required margin) - size * 20L(taker fee)
//            assertThat(MAX_VALUE - txSize * 100 - txSize * 20L, Is.is(takerEvent.free));
//            assertThat(txSize * 100L + txSize * 20L, Is.is(takerEvent.locked));
//            assertThat(0L, Is.is(takerEvent.openPriceSum));
//            assertThat(0L, Is.is(takerEvent.openVolume));
//            assertThat(0L, Is.is(takerEvent.tradeSize));
//            assertThat(0L, Is.is(takerEvent.tradePrice));
//            checkEvent(takerEvent);
//
//            // check unlock_pending event for taker
//            IFundEventsHandler.FundsEvent takerUnlockEvent = fundEvents.get(4);
//            assertThat(userId2, Is.is(takerUnlockEvent.uid));
//            assertThat(quoteId, Is.is(takerUnlockEvent.currency));
//            assertThat(symbolId, Is.is(takerUnlockEvent.symbol));
//            assertThat(takerOrderId, Is.is(takerUnlockEvent.orderId));
//            assertThat(0L, Is.is(takerUnlockEvent.fee));
//            assertThat(PositionDirection.SHORT, Is.is(takerUnlockEvent.direction));
//            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(takerUnlockEvent.eventType));
//            assertThat(MAX_VALUE, Is.is(takerUnlockEvent.free));
//            assertThat(0L, Is.is(takerUnlockEvent.locked));
//            assertThat(0L, Is.is(takerUnlockEvent.openPriceSum));
//            assertThat(0L, Is.is(takerUnlockEvent.openVolume));
//            assertThat(0L, Is.is(takerUnlockEvent.tradeSize));
//            assertThat(0L, Is.is(takerUnlockEvent.tradePrice));
//            checkEvent(takerUnlockEvent);
//
//            // check open position event for taker
//            IFundEventsHandler.FundsEvent takerOpenPositionEvent = fundEvents.get(5);
//            assertThat(userId2, Is.is(takerOpenPositionEvent.uid));
//            assertThat(quoteId, Is.is(takerOpenPositionEvent.currency));
//            assertThat(symbolId, Is.is(takerOpenPositionEvent.symbol));
//            assertThat(takerOrderId, Is.is(takerOpenPositionEvent.orderId));
//            assertThat(20L * 2, Is.is(takerOpenPositionEvent.fee));
//            assertThat(PositionDirection.SHORT, Is.is(takerOpenPositionEvent.direction));
//            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(takerOpenPositionEvent.eventType));
//            // free = init value - cost - fee
//            assertThat(MAX_VALUE - 200 - 20 * 2, Is.is(takerOpenPositionEvent.free));
//            assertThat(200L, Is.is(takerOpenPositionEvent.locked));
//            assertThat(20000L, Is.is(takerOpenPositionEvent.openPriceSum));
//            assertThat(2L, Is.is(takerOpenPositionEvent.openVolume));
//            assertThat(2L, Is.is(takerOpenPositionEvent.tradeSize));
//            assertThat(10000L, Is.is(takerOpenPositionEvent.tradePrice));
//            // openVolume * markPrice - openPriceSum = 2 * 10000 - 20000 = 0
//            assertThat(0L, Is.is(takerOpenPositionEvent.unrealizedProfit));
//            // maintenanceMargin = 100
//            // liquidationPrice = (direction * (maintenanceMargin - totalMargin) + openPriceSum) / openVolume = (-1 * (100 - 200) + 20000 * 1)/2 = 10050
//            assertThat(1999980L, Is.is(takerOpenPositionEvent.liquidationPrice));
//            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 100 / MAX_VALUE) = 0
//            assertThat(0L, Is.is(takerOpenPositionEvent.marginRatioScaleK));
//
//            // check unlock_pending event for maker
//            IFundEventsHandler.FundsEvent makerUnlockEvent = fundEvents.get(6);
//            assertThat(userId1, Is.is(makerUnlockEvent.uid));
//            assertThat(quoteId, Is.is(makerUnlockEvent.currency));
//            assertThat(symbolId, Is.is(makerUnlockEvent.symbol));
//            assertThat(makerOrderId, Is.is(makerUnlockEvent.orderId));
//            assertThat(0L, Is.is(makerUnlockEvent.fee));
//            assertThat(PositionDirection.LONG, Is.is(makerUnlockEvent.direction));
//            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(makerUnlockEvent.eventType));
//            // 10000 - 2 * 100 = 9800
//            assertThat(deposit - (size - txSize) * 100L - (size - txSize) * 20L, Is.is(makerUnlockEvent.free));
//            assertThat((size - txSize) * 100L + (size - txSize) * 20L, Is.is(makerUnlockEvent.locked));
//            assertThat(0L, Is.is(makerUnlockEvent.openPriceSum));
//            assertThat(0L, Is.is(makerUnlockEvent.openVolume));
//            assertThat(0L, Is.is(makerUnlockEvent.tradeSize));
//            assertThat(0L, Is.is(makerUnlockEvent.tradePrice));
//            checkEvent(makerUnlockEvent);
//
//            // check open position event
//            IFundEventsHandler.FundsEvent makerOpenPositionEvent = fundEvents.get(7);
//            assertThat(userId1, Is.is(makerOpenPositionEvent.uid));
//            assertThat(quoteId, Is.is(makerOpenPositionEvent.currency));
//            assertThat(symbolId, Is.is(makerOpenPositionEvent.symbol));
//            assertThat(makerOrderId, Is.is(makerOpenPositionEvent.orderId));
//            assertThat(2 * 10L, Is.is(makerOpenPositionEvent.fee));
//            assertThat(PositionDirection.LONG, Is.is(makerOpenPositionEvent.direction));
//            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(makerOpenPositionEvent.eventType));
//            // 10000(deposit) - 10 * 100(current position) - 8 * 20(taker fee in advance) - 2 * 10(maker fee)
//            assertThat(deposit - size * 100 - (size - txSize) * 20L - txSize * 10L, Is.is(makerOpenPositionEvent.free));
//            assertThat(size * 100L + (size - txSize) * 20L, Is.is(makerOpenPositionEvent.locked));
//            assertThat(20000L, Is.is(makerOpenPositionEvent.openPriceSum));
//            assertThat(2L, Is.is(makerOpenPositionEvent.openVolume));
//            assertThat(2L, Is.is(makerOpenPositionEvent.tradeSize));
//            assertThat(10000L, Is.is(makerOpenPositionEvent.tradePrice));
//            // openVolume * markPrice - openPriceSum = 20000 - 20000 = 0
//            assertThat(0L, Is.is(makerOpenPositionEvent.unrealizedProfit));
//            // liquidationPrice = (direction * (maintenanceMargin - totalMargin) + openPriceSum) / openVolume = (1 * (100 - 200) + 20000 )/2 = 9950L
//            assertThat(5035L, Is.is(makerOpenPositionEvent.liquidationPrice));
//            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 100 / 9980) = long (10.02) = 10
//            assertThat(10L, Is.is(makerOpenPositionEvent.marginRatioScaleK));
//        }
//    }
//
//    // 开多个仓位, 测试部分成交, taker为Bid
//    @Test
//    public void testOpenMultiplePosition4Ask() throws InterruptedException {
//        int size = 10;
//        int deposit = 1000 * size;
//        long userId1 = 1003L;
//        long userId2 = 1004L;
//        long makerOrderId = 1005L;
//        long takerOrderId = 1006L;
//        int txSize = 2;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            container.initFutureSymbol(symbolId, quoteId);
//            initCurrencies(container);
//            container.initMarkPrice(symbolId, 10000);
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
//
//            container.createAskWithOrderId(makerOrderId, userId1, size, 10000, symbolId, MarginMode.CROSS);
//            container.createBidWithOrderId(takerOrderId, userId2, txSize, 10000, symbolId, MarginMode.CROSS);
//
//            verify(handler, times(9)).commandResult(commandResultCaptor.capture());
//            verify(handler, never()).reduceEvent(any());
//            verify(handler, never()).rejectEvent(any());
//            verify(handler, times(1)).tradeEvent(tradeEventCaptor.capture());
//
//            final ITradeEventsHandler.TradeEvent tradeEvent = tradeEventCaptor.getAllValues().get(0);
//            assertThat(tradeEvent.getSymbol(), Is.is(symbolId));
//            assertThat(tradeEvent.getTotalVolume(), Is.is(2L));
//            assertThat(tradeEvent.getTakerOrderId(), Is.is(takerOrderId));
//            assertThat(tradeEvent.getTakerUid(), Is.is(userId2));
//            assertThat(tradeEvent.getTakerAction(), Is.is(OrderAction.BID));
//            assertThat(tradeEvent.takeOrderCompleted, Is.is(true));
//            assertThat(tradeEvent.trades.get(0).getMakerUid(), Is.is(userId1));
//            assertThat(tradeEvent.trades.get(0).makerOrderId, Is.is(makerOrderId));
//            assertThat(tradeEvent.trades.get(0).makerOrderCompleted, Is.is(false));
//            assertThat(tradeEvent.trades.get(0).price, Is.is(10000L));
//            assertThat(tradeEvent.trades.get(0).getVolume(), Is.is(2L));
//
//            // check balance
//            SingleUserReportResult user1Report = container.getUserProfile(userId1);
//            SingleUserReportResult user2Report = container.getUserProfile(userId2);
//            assertThat(user1Report.getAccounts().get(quoteId), Is.is((deposit - 2 * 10L)));
//            assertThat(user2Report.getAccounts().get(quoteId), Is.is((MAX_VALUE - 2 * 20L)));
//
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } finally {
//            verify(handler, times(8)).fundsEvent(fundEventCapor.capture());
//            // check fund event
//            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
//            IFundEventsHandler.FundsEvent takerEvent = fundEvents.get(2);
//
//            // check lock_pending event for maker, orderId should be maker's
//            assertThat(userId1, Is.is(takerEvent.uid));
//            assertThat(quoteId, Is.is(takerEvent.currency));
//            assertThat(symbolId, Is.is(takerEvent.symbol));
//            assertThat(makerOrderId, Is.is(takerEvent.orderId));
//            assertThat(0L, Is.is(takerEvent.fee));
//            assertThat(PositionDirection.SHORT, Is.is(takerEvent.direction));
//            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(takerEvent.eventType));
//            assertThat(deposit - size * 100L - size * 20L, Is.is(takerEvent.free));
//            assertThat(size * 100L + size * 20L, Is.is(takerEvent.locked));
//            assertThat(0L, Is.is(takerEvent.openPriceSum));
//            assertThat(0L, Is.is(takerEvent.openVolume));
//            assertThat(0L, Is.is(takerEvent.tradeSize));
//            assertThat(0L, Is.is(takerEvent.tradePrice));
//            checkEvent(takerEvent);
//
//            // check lock_pending event for taker, orderId should be maker's
//            IFundEventsHandler.FundsEvent makerEvent = fundEvents.get(3);
//            assertThat(userId2, Is.is(makerEvent.uid));
//            assertThat(quoteId, Is.is(makerEvent.currency));
//            assertThat(symbolId, Is.is(makerEvent.symbol));
//            assertThat(makerOrderId, Is.is(takerEvent.orderId));
//            assertThat(0L, Is.is(makerEvent.fee));
//            assertThat(PositionDirection.LONG, Is.is(makerEvent.direction));
//            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(makerEvent.eventType));
//            assertThat(MAX_VALUE - txSize * 100L - txSize * 20L, Is.is(makerEvent.free));
//            assertThat(txSize * 100L + txSize * 20L, Is.is(makerEvent.locked));
//            assertThat(0L, Is.is(makerEvent.openPriceSum));
//            assertThat(0L, Is.is(makerEvent.openVolume));
//            assertThat(0L, Is.is(makerEvent.tradeSize));
//            assertThat(0L, Is.is(makerEvent.tradePrice));
//            checkEvent(makerEvent);
//
//            // check unlock_pending event for taker
//            IFundEventsHandler.FundsEvent takerUnlockEvent = fundEvents.get(4);
//            assertThat(userId2, Is.is(takerUnlockEvent.uid));
//            assertThat(quoteId, Is.is(takerUnlockEvent.currency));
//            assertThat(symbolId, Is.is(takerUnlockEvent.symbol));
//            assertThat(takerOrderId, Is.is(takerUnlockEvent.orderId));
//            assertThat(0L, Is.is(takerUnlockEvent.fee));
//            assertThat(PositionDirection.LONG, Is.is(takerUnlockEvent.direction));
//            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(takerUnlockEvent.eventType));
//            assertThat(MAX_VALUE, Is.is(takerUnlockEvent.free));
//            assertThat(0L, Is.is(takerUnlockEvent.locked));
//            assertThat(0L, Is.is(takerUnlockEvent.openPriceSum));
//            assertThat(0L, Is.is(takerUnlockEvent.openVolume));
//            assertThat(0L, Is.is(takerUnlockEvent.tradeSize));
//            assertThat(0L, Is.is(takerUnlockEvent.tradePrice));
//            checkEvent(takerUnlockEvent);
//
//            // check open position event for taker
//            IFundEventsHandler.FundsEvent takerOpenPositionEvent = fundEvents.get(5);
//            assertThat(userId2, Is.is(takerOpenPositionEvent.uid));
//            assertThat(quoteId, Is.is(takerOpenPositionEvent.currency));
//            assertThat(symbolId, Is.is(takerOpenPositionEvent.symbol));
//            assertThat(takerOrderId, Is.is(takerOpenPositionEvent.orderId));
//            assertThat(20L * 2, Is.is(takerOpenPositionEvent.fee));
//            assertThat(PositionDirection.LONG, Is.is(takerOpenPositionEvent.direction));
//            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(takerOpenPositionEvent.eventType));
//            // free = init value - cost - fee
//            assertThat(MAX_VALUE - 200 - 20 * 2, Is.is(takerOpenPositionEvent.free));
//            assertThat(200L, Is.is(takerOpenPositionEvent.locked));
//            assertThat(20000L, Is.is(takerOpenPositionEvent.openPriceSum));
//            assertThat(2L, Is.is(takerOpenPositionEvent.openVolume));
//            assertThat(2L, Is.is(takerOpenPositionEvent.tradeSize));
//            assertThat(10000L, Is.is(takerOpenPositionEvent.tradePrice));
//            // openVolume * markPrice - openPriceSum = 20000 - 20000 = 0
//            assertThat(0L, Is.is(takerOpenPositionEvent.unrealizedProfit));
//            // liquidationPrice = (direction * (maintenanceMargin - totalMargin) + openPriceSum) / openVolume = (1 * (100 - 200) + 20000 )/2 = 9950L
//            assertThat(-1L, Is.is(takerOpenPositionEvent.liquidationPrice));
//            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 100 / MAX_VALUE) = 10
//            assertThat(0L, Is.is(takerOpenPositionEvent.marginRatioScaleK));
//
//            // check unlock_pending event for maker
//            IFundEventsHandler.FundsEvent makerUnlockEvent = fundEvents.get(6);
//            assertThat(userId1, Is.is(makerUnlockEvent.uid));
//            assertThat(quoteId, Is.is(makerUnlockEvent.currency));
//            assertThat(symbolId, Is.is(makerUnlockEvent.symbol));
//            assertThat(makerOrderId, Is.is(makerUnlockEvent.orderId));
//            assertThat(0L, Is.is(makerUnlockEvent.fee));
//            assertThat(PositionDirection.SHORT, Is.is(makerUnlockEvent.direction));
//            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(makerUnlockEvent.eventType));
//            // 下了10单, 成交2单, 剩余8单需要预先在仓位上扣除手续费
//            assertThat(deposit - (size - txSize) * 100L - (size - txSize) * 20L, Is.is(makerUnlockEvent.free));
//            assertThat((size - txSize) * 100L + (size - txSize) * 20L, Is.is(makerUnlockEvent.locked));
//            assertThat(0L, Is.is(makerUnlockEvent.openPriceSum));
//            assertThat(0L, Is.is(makerUnlockEvent.openVolume));
//            assertThat(0L, Is.is(makerUnlockEvent.tradeSize));
//            assertThat(0L, Is.is(makerUnlockEvent.tradePrice));
//            checkEvent(makerUnlockEvent);
//
//            // check open position event
//            IFundEventsHandler.FundsEvent makerOpenPositionEvent = fundEvents.get(7);
//            assertThat(userId1, Is.is(makerOpenPositionEvent.uid));
//            assertThat(quoteId, Is.is(makerOpenPositionEvent.currency));
//            assertThat(symbolId, Is.is(makerOpenPositionEvent.symbol));
//            assertThat(makerOrderId, Is.is(makerOpenPositionEvent.orderId));
//            assertThat(2 * 10L, Is.is(makerOpenPositionEvent.fee));
//            assertThat(PositionDirection.SHORT, Is.is(makerOpenPositionEvent.direction));
//            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(makerOpenPositionEvent.eventType));
//            // 10000(deposit) - 10 * 100(maker order) - 2 * 10(maker fee) - 8 * 20(taker fee) = 8820
//            assertThat(deposit - size * 100L - 2 * 10L - 8 * 20L, Is.is(makerOpenPositionEvent.free));
//            assertThat(size * 100L + 8 * 20L, Is.is(makerOpenPositionEvent.locked));
//            assertThat(20000L, Is.is(makerOpenPositionEvent.openPriceSum));
//            assertThat(2L, Is.is(makerOpenPositionEvent.openVolume));
//            assertThat(2L, Is.is(makerOpenPositionEvent.tradeSize));
//            assertThat(10000L, Is.is(makerOpenPositionEvent.tradePrice));
//            // openVolume * markPrice - openPriceSum = 2 * 10000 - 20000 = 0
//            assertThat(0L, Is.is(makerOpenPositionEvent.unrealizedProfit));
//            // maintenanceMargin = 100
//            // liquidationPrice = (direction * (maintenanceMargin - totalMargin) + openPriceSum) / openVolume = (-1 * (100 - 200) + 20000 * 1)/2 = 10050
//            assertThat(14915L, Is.is(makerOpenPositionEvent.liquidationPrice));
//            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 100 / 9980) = 0
//            assertThat(10L, Is.is(makerOpenPositionEvent.marginRatioScaleK));
//        }
//    }
//
//    // 平仓事件, 平仓所有position
//    @Test
//    public void testClosePosition() {
//        int deposit = 1000;
//        long userId1 = 1003L;
//        long userId2 = 1004L;
//        long makerOrderId1 = 1005L;
//        long takerOrderId2 = 1006L;
//        long makerOrderId3 = 1007L;
//        long takerOrderId4 = 1008L;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            container.initFutureSymbol(symbolId, quoteId);
//            initCurrencies(container);
//            container.initMarkPrice(symbolId, 10000);
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
//
//            // 开仓成功
//            container.createBidWithOrderId(makerOrderId1, userId1, 1, 10000, symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(takerOrderId2, userId2, 1, 10000, symbolId, MarginMode.CROSS);
//
//            // 平仓成功
//            container.createAskWithOrderId(makerOrderId3, userId1, 1, 10500, symbolId, MarginMode.CROSS);
//            container.createBidWithOrderId(takerOrderId4, userId2, 1, 10500, symbolId, MarginMode.CROSS);
//
//            verify(handler, times(11)).commandResult(commandResultCaptor.capture());
//            verify(handler, never()).reduceEvent(any());
//            verify(handler, never()).rejectEvent(any());
//            verify(handler, times(2)).tradeEvent(tradeEventCaptor.capture());
//
//            // check balance
//            SingleUserReportResult user1Report = container.getUserProfile(userId1);
//            SingleUserReportResult user2Report = container.getUserProfile(userId2);
//            // check平仓后利润, taker/maker fee分别为10/20
//            assertThat(user1Report.getAccounts().get(quoteId), Is.is((deposit - 10L + 500L)));
//            assertThat(user2Report.getAccounts().get(quoteId), Is.is((MAX_VALUE - 20L - 500L)));
//
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            verify(handler, times(14)).fundsEvent(fundEventCapor.capture());
//            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
//
//            IFundEventsHandler.FundsEvent takerCloseEvent = fundEvents.get(11);
//            assertThat(userId2, Is.is(takerCloseEvent.uid));
//            assertThat(quoteId, Is.is(takerCloseEvent.currency));
//            assertThat(symbolId, Is.is(takerCloseEvent.symbol));
//            // 关仓不收手续费
//            assertThat(0L, Is.is(takerCloseEvent.fee));
//            assertThat(PositionDirection.SHORT, Is.is(takerCloseEvent.direction));
//            assertThat(FundEvent.FundEventType.CLOSE_POSITION, Is.is(takerCloseEvent.eventType));
//            // free is not correct
//            assertThat(3999980L, Is.is(takerCloseEvent.free));
//            assertThat(0L, Is.is(takerCloseEvent.locked));
//            assertThat(0L, Is.is(takerCloseEvent.openPriceSum));
//            assertThat(0L, Is.is(takerCloseEvent.openVolume));
//            assertThat(1L, Is.is(takerCloseEvent.tradeSize));
//            // trade price?
//            assertThat(10500L, Is.is(takerCloseEvent.tradePrice));
//            checkEvent(takerCloseEvent);
//
//            IFundEventsHandler.FundsEvent makerCloseEvent = fundEvents.get(13);
//            assertThat(userId1, Is.is(makerCloseEvent.uid));
//            assertThat(quoteId, Is.is(makerCloseEvent.currency));
//            assertThat(symbolId, Is.is(makerCloseEvent.symbol));
//            assertThat(0L, Is.is(makerCloseEvent.fee));
//            assertThat(PositionDirection.LONG, Is.is(makerCloseEvent.direction));
//            assertThat(FundEvent.FundEventType.CLOSE_POSITION, Is.is(makerCloseEvent.eventType));
//            // free = init money - fee
//            assertThat(deposit - 10 * 1L, Is.is(makerCloseEvent.free));
//            assertThat(0L, Is.is(makerCloseEvent.locked));
//            assertThat(0L, Is.is(makerCloseEvent.openPriceSum));
//            assertThat(0L, Is.is(makerCloseEvent.openVolume));
//            assertThat(1L, Is.is(makerCloseEvent.tradeSize));
//            // CLOSE_POSITION时为0
//            checkEvent(makerCloseEvent);
//        }
//    }
//
//    // 平仓事件, 平仓部分订单
//    @Test
//    public void testPartialClosePosition() {
//        int deposit = 10000;
//        long userId1 = 1003L;
//        long userId2 = 1004L;
//        long makerOrderId1 = 1005L;
//        long takerOrderId2 = 1006L;
//        long makerOrderId3 = 1007L;
//        long takerOrderId4 = 1008L;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            container.initFutureSymbol(symbolId, quoteId);
//            initCurrencies(container);
//            container.initMarkPrice(symbolId, 10000);
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
//
//            // 开仓成功
//            container.createBidWithOrderId(makerOrderId1, userId1, 10, 10000, symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(takerOrderId2, userId2, 10, 10000, symbolId, MarginMode.CROSS);
//
//            // 平仓一手成功
//            container.createAskWithOrderId(makerOrderId3, userId1, 2, 10500, symbolId, MarginMode.CROSS);
//            container.createBidWithOrderId(takerOrderId4, userId2, 1, 10500, symbolId, MarginMode.CROSS);
//
//            verify(handler, times(11)).commandResult(commandResultCaptor.capture());
//            verify(handler, never()).reduceEvent(any());
//            verify(handler, never()).rejectEvent(any());
//            verify(handler, times(2)).tradeEvent(tradeEventCaptor.capture());
//
//            // check balance
//            SingleUserReportResult user1Report = container.getUserProfile(userId1);
//            SingleUserReportResult user2Report = container.getUserProfile(userId2);
//            // 只有全平掉时才在account balance加钱, taker/maker fee分别为10/20
//            assertThat(user1Report.getAccounts().get(quoteId), Is.is((deposit - 10L * 10)));
//            assertThat(user2Report.getAccounts().get(quoteId), Is.is((MAX_VALUE - 20L * 10)));
//
//        } catch (ExecutionException e) {
//            throw new RuntimeException(e);
//        } catch (InterruptedException e) {
//            throw new RuntimeException(e);
//        } finally {
//            verify(handler, times(14)).fundsEvent(fundEventCapor.capture());
//            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
//
//            IFundEventsHandler.FundsEvent takerCloseEvent = fundEvents.get(11);
//            assertThat(userId2, Is.is(takerCloseEvent.uid));
//            assertThat(quoteId, Is.is(takerCloseEvent.currency));
//            assertThat(symbolId, Is.is(takerCloseEvent.symbol));
//            // 关仓不收手续费
//            assertThat(0L, Is.is(takerCloseEvent.fee));
//            assertThat(PositionDirection.SHORT, Is.is(takerCloseEvent.direction));
//            assertThat(FundEvent.FundEventType.CLOSE_POSITION, Is.is(takerCloseEvent.eventType));
//            // openVolume * markPrice - openPriceSum = -1 * (9 * 10000 - 89500) = 500
//            assertThat(-500L, Is.is(takerCloseEvent.unrealizedProfit));
//            // maintenanceMargin = 0.5% * 9 * 10000 = 450
//            // liquidationPrice = (direction * (maintenanceMargin - totalMargin) + openPriceSum) / openVolume = (-1 * (450 - 900) + 89500)/9 = long(9994.4) = 9994L
//            assertThat(452106L, Is.is(takerCloseEvent.liquidationPrice));
//            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 450 / MAX_VALUE) = 0
//            assertThat(0L, Is.is(takerCloseEvent.marginRatioScaleK));
//        }
//    }
//
//    // 强制平仓事件
//    @Test
//    public void testForceClosePosition() {
//        int deposit = 1000;
//        long userId1 = 1003L;
//        long userId2 = 1004L;
//        long makerOrderId1 = 1005L;
//        long takerOrderId2 = 1006L;
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
//            container.setConsumer(processor);
//            container.initFutureSymbol(symbolId, quoteId);
//            initCurrencies(container);
//            container.initMarkPrice(symbolId, 10000);
//            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
//            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
//
//            // 开仓成功
//            container.createBidWithOrderId(makerOrderId1, userId1, 1, 10000, symbolId, MarginMode.CROSS);
//            container.createAskWithOrderId(takerOrderId2, userId2, 1, 10000, symbolId, MarginMode.CROSS);
//
//            // 模拟行情变动
//            container.updateCurrentPriceTo(500, symbolId, quoteId);
//
//
//        } finally {
//            verify(handler, times(16)).fundsEvent(fundEventCapor.capture());
//            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
//
//            IFundEventsHandler.FundsEvent takerCloseEvent = fundEvents.get(0);
//        }
//    }
//
//    @Test
//    public void testGlobalBalance() {
//        final int symbolId = SYMBOL_MARGIN;
//
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
//            container.initFeeSymbols();
//            container.initFeeSymbolsMarkPrice();
//            container.initFeeUsers();
//
//            container.setConsumer(processor);
//
//            container.submitCommandSync(builderPlace(symbolId, UID_1, ASK, GTC).orderId(101L).price(160000L).size(7L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
//            container.submitCommandSync(builderPlace(symbolId, UID_2, ASK, GTC).orderId(202L).price(159900L).size(10L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
//            container.submitCommandSync(builderPlace(symbolId, UID_3, ASK, GTC).orderId(303L).price(160000L).size(3L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
//            container.submitCommandSync(builderPlace(symbolId, UID_3, ASK, GTC).orderId(304L).price(160500L).size(20L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
//
//            long price = 160500L;
//            int size = 20;
//
//            container.submitCommandSync(builderPlace(symbolId, UID_4, BID, IOC).orderId(405L).price(price).reservePrice(price).size(size).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
//
//            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
//        }
//    }
//
//    // -------------------------- buy no rejection tests -----------------------------
//
//    @Test
//    @Timeout(5)
//    public void testMultiBuyNoRejectionMarginGtc() {
//        testMultiBuy(SYMBOLSPECFEE_USD_JPY, GTC, RejectionCause.NO_REJECTION);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiBuyNoRejectionExchangeGtc() {
//        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, GTC, RejectionCause.NO_REJECTION);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiBuyNoRejectionExchangeIoc() {
//        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, IOC, RejectionCause.NO_REJECTION);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiBuyNoRejectionMarginIoc() {
//        testMultiBuy(SYMBOLSPECFEE_USD_JPY, IOC, RejectionCause.NO_REJECTION);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiBuyNoRejectionExchangeFokB() {
//        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.NO_REJECTION);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiBuyNoRejectionMarginFokB() {
//        testMultiBuy(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.NO_REJECTION);
//    }
//
//    // -------------------------- buy with rejection tests -----------------------------
//
//    @Test
//    @Timeout(5)
//    public void testMultiBuyWithRejectionMarginGtc() {
//        testMultiBuy(SYMBOLSPECFEE_USD_JPY, GTC, RejectionCause.REJECTION_BY_SIZE);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiBuyWithRejectionExchangeGtc() {
//        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, GTC, RejectionCause.REJECTION_BY_SIZE);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiBuyWithRejectionExchangeIoc() {
//        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, IOC, RejectionCause.REJECTION_BY_SIZE);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiBuyWithRejectionMarginIoc() {
//        testMultiBuy(SYMBOLSPECFEE_USD_JPY, IOC, RejectionCause.REJECTION_BY_SIZE);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiBuyWithSizeRejectionExchangeFokB() {
//        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.REJECTION_BY_SIZE);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiBuyWithSizeRejectionMarginFokB() {
//        testMultiBuy(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.REJECTION_BY_SIZE);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiBuyWithBudgetRejectionExchangeFokB() {
//        testMultiBuy(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.REJECTION_BY_BUDGET);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiBuyWithBudgetRejectionMarginFokB() {
//        testMultiBuy(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.REJECTION_BY_BUDGET);
//    }
//
//    // -------------------------- sell no rejection tests -----------------------------
//
//    @Test
//    @Timeout(5)
//    public void testMultiSellNoRejectionMarginGtc() {
//        testMultiSell(SYMBOLSPECFEE_USD_JPY, GTC, RejectionCause.NO_REJECTION);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiSellNoRejectionExchangeGtc() {
//        testMultiSell(SYMBOLSPECFEE_XBT_LTC, GTC, RejectionCause.NO_REJECTION);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiSellNoRejectionMarginIoc() {
//        testMultiSell(SYMBOLSPECFEE_USD_JPY, IOC, RejectionCause.NO_REJECTION);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiSellNoRejectionExchangeIoc() {
//        testMultiSell(SYMBOLSPECFEE_XBT_LTC, IOC, RejectionCause.NO_REJECTION);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiSellNoRejectionMarginFokB() {
//        testMultiSell(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.NO_REJECTION);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiSellNoRejectionExchangeFokB() {
//        testMultiSell(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.NO_REJECTION);
//    }
//
//    // -------------------------- sell with rejection tests -----------------------------
//
//    @Test
//    @Timeout(5)
//    public void testMultiSellWithRejectionMarginGtc() {
//        testMultiSell(SYMBOLSPECFEE_USD_JPY, GTC, RejectionCause.REJECTION_BY_SIZE);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiSellWithRejectionExchangeGtc() {
//        testMultiSell(SYMBOLSPECFEE_XBT_LTC, GTC, RejectionCause.REJECTION_BY_SIZE);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiSellWithRejectionMarginIoc() {
//        testMultiSell(SYMBOLSPECFEE_USD_JPY, IOC, RejectionCause.REJECTION_BY_SIZE);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiSellWithRejectionExchangeIoc() {
//        testMultiSell(SYMBOLSPECFEE_XBT_LTC, IOC, RejectionCause.REJECTION_BY_SIZE);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiSellWithSizeRejectionMarginFokB() {
//        testMultiSell(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.REJECTION_BY_SIZE);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiSellWithSizeRejectionExchangeFokB() {
//        testMultiSell(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.REJECTION_BY_SIZE);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiSellWithExpectationRejectionMarginFokB() {
//        testMultiSell(SYMBOLSPECFEE_USD_JPY, FOK_BUDGET, RejectionCause.REJECTION_BY_BUDGET);
//    }
//
//    @Test
//    @Timeout(5)
//    public void testMultiSellWithExpectationRejectionExchangeFokB() {
//        testMultiSell(SYMBOLSPECFEE_XBT_LTC, FOK_BUDGET, RejectionCause.REJECTION_BY_BUDGET);
//    }
//
//    private PerformanceConfiguration getPerformanceConfiguration() {
//        return PerformanceConfiguration.baseBuilder().build();
//    }
//    // ------------------------------------------------------------------------------
//
//    private ApiPlaceOrder.ApiPlaceOrderBuilder builderPlace(int symbolId, long uid, OrderAction action, OrderType type) {
//        return ApiPlaceOrder.builder().uid(uid).action(action).orderType(type).symbol(symbolId).marginMode(MarginMode.ISOLATED);
//    }
//
//    private ApiPlaceOrder.ApiPlaceOrderBuilder builderPlace(int symbolId, long uid, OrderAction action, OrderType type, MarginMode mode) {
//        return ApiPlaceOrder.builder().uid(uid).action(action).orderType(type).symbol(symbolId).marginMode(mode);
//    }
//
//    // TODO count/verify number of commands and events
//    private void testMultiBuy(final CoreSymbolSpecification symbolSpec, final OrderType orderType, final RejectionCause rejectionCause) {
//
//        final int symbolId = symbolSpec.symbolId;
//
//        final long size = 40L + (rejectionCause == RejectionCause.REJECTION_BY_SIZE ? 1 : 0);
//
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
//            container.initFeeSymbols();
//            container.initFeeSymbolsMarkPrice();
//            container.initFeeUsers();
//
//            container.setConsumer(processor);
//
//            container.submitCommandSync(builderPlace(symbolId, UID_1, ASK, GTC).orderId(101L).price(160000L).size(7L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
//            container.submitCommandSync(builderPlace(symbolId, UID_2, ASK, GTC).orderId(202L).price(159900L).size(10L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
//            container.submitCommandSync(builderPlace(symbolId, UID_3, ASK, GTC).orderId(303L).price(160000L).size(3L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
//            container.submitCommandSync(builderPlace(symbolId, UID_3, ASK, GTC).orderId(304L).price(160500L).size(20L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
//
//            long price = 160500L;
//            if (orderType == FOK_BUDGET) {
//                price = 160000L * 7L + 159900L * 10L + 160000L * 3L + 160500L * 20L + (rejectionCause == RejectionCause.REJECTION_BY_BUDGET ? -1 : 0);
//            }
//
//            container.submitCommandSync(builderPlace(symbolId, UID_4, BID, orderType).orderId(405L).price(price).reservePrice(price).size(size).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
//
////            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
//        }
//
//        verify(handler, times(5)).commandResult(commandResultCaptor.capture());
//        verify(handler, never()).reduceEvent(any());
//
//        if (orderType == FOK_BUDGET && rejectionCause != RejectionCause.NO_REJECTION) {
//            // no trades for FoK
//            verify(handler, never()).tradeEvent(any());
//
//        } else {
//            verify(handler, times(1)).tradeEvent(tradeEventCaptor.capture());
//
//            // validating first event
//            final ITradeEventsHandler.TradeEvent tradeEvent = tradeEventCaptor.getAllValues().get(0);
//            assertThat(tradeEvent.getSymbol(), Is.is(symbolId));
//            assertThat(tradeEvent.getTotalVolume(), Is.is(40L));
//            assertThat(tradeEvent.getTakerOrderId(), Is.is(405L));
//            assertThat(tradeEvent.getTakerUid(), Is.is(UID_4));
//            assertThat(tradeEvent.getTakerAction(), Is.is(OrderAction.BID));
//            assertThat(tradeEvent.isTakeOrderCompleted(), Is.is(rejectionCause == RejectionCause.NO_REJECTION)); // completed only if no rejection was happened
//
//            final List<ITradeEventsHandler.Trade> trades = tradeEvent.getTrades();
//            assertThat(trades.size(), Is.is(4));
//
//            assertThat(trades.get(0).getMakerOrderId(), Is.is(202L));
//            assertThat(trades.get(0).getMakerUid(), Is.is(UID_2));
//            assertTrue(trades.get(0).isMakerOrderCompleted());
//            assertThat(trades.get(0).getPrice(), Is.is(159900L));
//            assertThat(trades.get(0).getVolume(), Is.is(10L));
//
//            assertThat(trades.get(1).getMakerOrderId(), Is.is(101L));
//            assertThat(trades.get(1).getMakerUid(), Is.is(UID_1));
//            assertTrue(trades.get(1).isMakerOrderCompleted());
//            assertThat(trades.get(1).getPrice(), Is.is(160000L));
//            assertThat(trades.get(1).getVolume(), Is.is(7L));
//
//            assertThat(trades.get(2).getMakerOrderId(), Is.is(303L));
//            assertThat(trades.get(2).getMakerUid(), Is.is(UID_3));
//            assertTrue(trades.get(2).isMakerOrderCompleted());
//            assertThat(trades.get(2).getPrice(), Is.is(160000L));
//            assertThat(trades.get(2).getVolume(), Is.is(3L));
//
//            assertThat(trades.get(3).getMakerOrderId(), Is.is(304L));
//            assertThat(trades.get(3).getMakerUid(), Is.is(UID_3));
//            assertTrue(trades.get(3).isMakerOrderCompleted());
//            assertThat(trades.get(3).getPrice(), Is.is(160500L));
//            assertThat(trades.get(3).getVolume(), Is.is(20L));
//        }
//
//        if (rejectionCause != RejectionCause.NO_REJECTION && orderType != GTC) { // rejection can not happen for GTC orders
//            verify(handler, times(1)).rejectEvent(rejectEventCaptor.capture());
//            final ITradeEventsHandler.RejectEvent rejectEvent = rejectEventCaptor.getValue();
//            assertThat(rejectEvent.getSymbol(), Is.is(symbolId));
//            assertThat(rejectEvent.getRejectedVolume(), Is.is((orderType == FOK_BUDGET) ? size : 1L));
//            assertThat(rejectEvent.getOrderId(), Is.is(405L));
//            assertThat(rejectEvent.getUid(), Is.is(UID_4));
//        } else {
//            verify(handler, never()).rejectEvent(any());
//        }
//
//    }
//
//    private void testMultiSell(final CoreSymbolSpecification symbolSpec, final OrderType orderType, final RejectionCause rejectionCause) {
//
//        final int symbolId = symbolSpec.symbolId;
//
//        final long size = 22L + (rejectionCause == RejectionCause.REJECTION_BY_SIZE ? 1 : 0);
//
//        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
//            container.initFeeSymbols();
//            container.initFeeSymbolsMarkPrice();
//            container.initFeeUsers();
//
//            container.setConsumer(processor);
//
//            long price = 159_900L;
//            if (orderType == FOK_BUDGET) {
//                price = 160_500L + 160_000L * 20L + 159_900L + (rejectionCause == RejectionCause.REJECTION_BY_BUDGET ? 1 : 0);
//            }
//
//            container.submitCommandSync(builderPlace(symbolId, UID_1, BID, GTC).orderId(101L).price(160_000L).reservePrice(166_000L).size(12L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
//            container.submitCommandSync(builderPlace(symbolId, UID_2, BID, GTC).orderId(202L).price(159_900L).reservePrice(166_000L).size(1L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
//            container.submitCommandSync(builderPlace(symbolId, UID_3, BID, GTC).orderId(303L).price(160_000L).reservePrice(166_000L).size(8L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
//            container.submitCommandSync(builderPlace(symbolId, UID_3, BID, GTC).orderId(304L).price(160_500L).reservePrice(166_000L).size(1L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
//
//            container.submitCommandSync(builderPlace(symbolId, UID_4, ASK, orderType).orderId(405L).price(price).size(size).build(), CommandResultCode.SUCCESS);
//
////            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
//        }
//
//        verify(handler, times(5)).commandResult(commandResultCaptor.capture());
//        verify(handler, never()).reduceEvent(any());
//
//        if (orderType == FOK_BUDGET && rejectionCause != RejectionCause.NO_REJECTION) {
//            // no trades for FoK
//            verify(handler, never()).tradeEvent(any());
//
//        } else {
//            verify(handler, times(1)).tradeEvent(tradeEventCaptor.capture());
//
//            // validating first event
//            final ITradeEventsHandler.TradeEvent tradeEvent = tradeEventCaptor.getAllValues().get(0);
//            assertThat(tradeEvent.getSymbol(), Is.is(symbolId));
//            assertThat(tradeEvent.getTotalVolume(), Is.is(22L));
//            assertThat(tradeEvent.getTakerOrderId(), Is.is(405L));
//            assertThat(tradeEvent.getTakerUid(), Is.is(UID_4));
//            assertThat(tradeEvent.getTakerAction(), Is.is(ASK));
//            assertThat(tradeEvent.isTakeOrderCompleted(), Is.is(rejectionCause == RejectionCause.NO_REJECTION)); // completed only if no rejection was happened
//
//            final List<ITradeEventsHandler.Trade> trades = tradeEvent.getTrades();
//            assertThat(trades.size(), Is.is(4));
//
//            assertThat(trades.get(0).getMakerOrderId(), Is.is(304L));
//            assertThat(trades.get(0).getMakerUid(), Is.is(UID_3));
//            assertTrue(trades.get(0).isMakerOrderCompleted());
//            assertThat(trades.get(0).getPrice(), Is.is(160500L));
//            assertThat(trades.get(0).getVolume(), Is.is(1L));
//
//            assertThat(trades.get(1).getMakerOrderId(), Is.is(101L));
//            assertThat(trades.get(1).getMakerUid(), Is.is(UID_1));
//            assertTrue(trades.get(1).isMakerOrderCompleted());
//            assertThat(trades.get(1).getPrice(), Is.is(160000L));
//            assertThat(trades.get(1).getVolume(), Is.is(12L));
//
//            assertThat(trades.get(2).getMakerOrderId(), Is.is(303L));
//            assertThat(trades.get(2).getMakerUid(), Is.is(UID_3));
//            assertTrue(trades.get(2).isMakerOrderCompleted());
//            assertThat(trades.get(2).getPrice(), Is.is(160000L));
//            assertThat(trades.get(2).getVolume(), Is.is(8L));
//
//            assertThat(trades.get(3).getMakerOrderId(), Is.is(202L));
//            assertThat(trades.get(3).getMakerUid(), Is.is(UID_2));
//            assertTrue(trades.get(3).isMakerOrderCompleted());
//            assertThat(trades.get(3).getPrice(), Is.is(159900L));
//            assertThat(trades.get(3).getVolume(), Is.is(1L));
//        }
//
//        if (rejectionCause != RejectionCause.NO_REJECTION && orderType != GTC) { // rejection can not happen for GTC orders
//            verify(handler, times(1)).rejectEvent(rejectEventCaptor.capture());
//            final ITradeEventsHandler.RejectEvent rejectEvent = rejectEventCaptor.getValue();
//            assertThat(rejectEvent.getSymbol(), Is.is(symbolId));
//            assertThat(rejectEvent.getRejectedVolume(), Is.is((orderType == FOK_BUDGET) ? size : 1L));
//            assertThat(rejectEvent.getOrderId(), Is.is(405L));
//            assertThat(rejectEvent.getUid(), Is.is(UID_4));
//        } else {
//            verify(handler, never()).rejectEvent(any());
//        }
//
//    }
//
//    enum RejectionCause {
//        NO_REJECTION,
//        REJECTION_BY_SIZE,
//        REJECTION_BY_BUDGET
//    }
//
//}