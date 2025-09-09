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

import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.processors.LiquidationEngine;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.ApiAdjustUserBalance;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.core.Is;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static exchange.core2.core.common.OrderAction.ASK;
import static exchange.core2.core.common.OrderAction.BID;
import static exchange.core2.core.common.OrderType.*;
import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ITFutureCross extends ITFutureBase {

    private void initCurrencies(ExchangeTestContainer container) {
        container.addCurrency(BASE_CURRENCY_ID);
        container.addCurrency(quoteId);
    }

    // -------------------------- order tests ----------------------------------------
    // 1. isolated和cross margin不能混着下
    // 2. 取消订单后可以下新的订单(cross/isolated均可)
    @Test
    public void testCancelSuccess() {
        long deposit = 2000L;
        long userId1 = UID_1;
        int size = 1;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.initFutureSymbol(symbolId, quoteId);
            initCurrencies(container);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);

            ApiPlaceOrder order1 = container.genOrder(userId1, size, 10000, symbolId, BID, GTC, MarginMode.ISOLATED);
            container.submitCommandSync(order1, CommandResultCode.SUCCESS);
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().get(symbolId).get(0).getPendingBuySize(), is(1L));
                assertThat(profile.getPositions().get(symbolId).get(0).getMarginMode(), is(MarginMode.ISOLATED));
            });
            ApiPlaceOrder order2 = container.genOrder(userId1, size, 10000, symbolId, BID, GTC, MarginMode.CROSS);
            // 用户已有isolated 持仓，cross下单失败
            container.submitCommandSync(order2, CommandResultCode.RISK_MARGIN_MODE_MISMATCH);

            // cancel isolated order
            container.cancelOrder(userId1, order1.orderId, symbolId);
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(0));
            });
            // 取消订单后再下cross可以成功
            container.submitCommandSync(order2, CommandResultCode.SUCCESS);
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().get(symbolId).get(0).getPendingBuySize(), is(1L));
                assertThat(profile.getPositions().get(symbolId).get(0).getMarginMode(), is(MarginMode.CROSS));
            });
            // 用户已有cross 持仓，再下isolated下单失败
            container.submitCommandSync(order1, CommandResultCode.RISK_MARGIN_MODE_MISMATCH);
            // cancel cross margin
            container.cancelOrder(userId1, order2.orderId, symbolId);
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(0));
            });
            container.submitCommandSync(order1, CommandResultCode.SUCCESS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().get(symbolId).get(0).getPendingBuySize(), is(1L));
                assertThat(profile.getPositions().get(symbolId).get(0).getMarginMode(), is(MarginMode.ISOLATED));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // 默认为margin为isolated
    @Test
    public void testDefaultMargin() {
        long deposit = 20000L;
        long userId1 = UID_1;
        int size = 1;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.initFutureSymbol(symbolId, quoteId);
            initCurrencies(container);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);

            ApiPlaceOrder order1 = ApiPlaceOrder.builder()
                    .uid(userId1)
                    .orderId(container.getRandomTransactionId())
                    .action(OrderAction.BID)
                    .size(size)
                    .price(10000L)
                    .symbol(symbolId)
                    .orderType(OrderType.GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .build();
            container.submitCommandSync(order1, CommandResultCode.SUCCESS);
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().get(symbolId).get(0).getMarginMode(), is(MarginMode.ISOLATED));
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // 平仓后可以改margin mode
    @Test
    public void tesCloseMarginThenChangeMode() {
        int deposit = 1000;
        long userId1 = UID_1;
        long userId2 = UID_2;
        long makerOrderId1 = 1005L;
        long takerOrderId2 = 1006L;
        long makerOrderId3 = 1007L;
        long takerOrderId4 = 1008L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.initFutureSymbol(symbolId, quoteId);
            initCurrencies(container);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            // 开仓成功
            container.createBidWithOrderId(makerOrderId1, userId1, 1, 10000, symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(takerOrderId2, userId2, 1, 10000, symbolId, MarginMode.CROSS);
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(symbolId).get(0).getMarginMode(), is(MarginMode.CROSS));
            });

            // 平仓成功
            container.createAskWithOrderId(makerOrderId3, userId1, 1, 10500, symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(takerOrderId4, userId2, 1, 10500, symbolId, MarginMode.CROSS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(0));
            });

            // 开ISOLATION仓成功
            container.createBidWithOrderId(makerOrderId1, userId1, 1, 11000, symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(takerOrderId2, userId2, 1, 11000, symbolId, MarginMode.ISOLATED);
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(symbolId).get(0).getMarginMode(), is(MarginMode.ISOLATED));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // pendingSellAvgPrice和pendingBuyAvgPrice计算准确
    @Test
    public void testPendingAvgPrice() {
        long deposit = 10000L;
        long userId1 = UID_1;
        long userId2 = UID_2;
        long userId3 = UID_3;
        int size = 1;
        long price1 = 10000;
        long price2 = 15000;
        long makerOrderId1 = 1005L;
        long takerOrderId2 = 1006L;
        long makerOrderId3 = 1007L;
        long takerOrderId4 = 1008L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.initFutureSymbol(symbolId, quoteId);
            initCurrencies(container);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(userId3, MAX_VALUE, quoteId);

            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(makerOrderId3, userId1, size, price2, symbolId, MarginMode.CROSS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().get(symbolId).get(0).pendingBuyAvgPrice, is((price1 + price2) / 2));
            });

            container.createAskWithOrderId(takerOrderId4, userId2, size, price2, symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(takerOrderId2, userId2, size, price1, symbolId, MarginMode.CROSS);
            // 完全成交后avgPrice为0
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().get(symbolId).get(0).pendingBuyAvgPrice, is(0L));
            });
            // 完全成交后avgPrice为0
            container.validateUserState(userId2, profile -> {
                assertThat(profile.getPositions().get(symbolId).get(0).pendingSellAvgPrice, is(0L));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // withdraw要考虑全仓该币种所有当前期货持仓 -- 空仓
    @Test
    public void testCrossMarginWithdraw() {
        long deposit = 10000L;
        long userId1 = UID_1;
        int size = 1;
        long price1 = 10000;
        long price2 = 15000;
        long makerOrderId1 = 1005L;
        long takerOrderId2 = 1006L;
        long makerOrderId3 = 1007L;
        long takerOrderId4 = 1008L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);

            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(makerOrderId3, userId1, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit));
            });

            ApiAdjustUserBalance cmd = ApiAdjustUserBalance.builder().uid(userId1).transactionId(container.getRandomTransactionId() + 100).amount(-deposit).currency(quoteId).build();

            container.submitCommandSync(cmd, CommandResultCode.RISK_NSF);

            // locked margin is 570
            container.addMoneyToUser(userId1, quoteId, 569);
            container.submitCommandSync(cmd, CommandResultCode.RISK_NSF);

            container.addMoneyToUser(userId1, quoteId, 1);
            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // withdraw要考虑全仓该币种所有当前期货持仓 -- 持仓
    @Test
    public void testCrossMarginWithdraw2() {
        long deposit = 10000L;
        long userId1 = UID_1;
        long userId2 = UID_2;
        long userId3 = UID_3;
        int size = 1;
        long price1 = 10000;
        long price2 = 15000;
        long makerOrderId1 = 1005L;
        long takerOrderId2 = 1006L;
        long makerOrderId3 = 1007L;
        long takerOrderId4 = 1008L;
        long makerOrderId5 = 1009L;
        long takerOrderId6 = 1010L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(userId3, MAX_VALUE, quoteId);

            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(makerOrderId3, userId1, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);

            container.createAskWithOrderId(takerOrderId2, userId2, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(takerOrderId4, userId2, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);

            // symbol0 10 fixed maker fee
            long fee1 = symbols.get(0).makerFee;
            // symbol1 1% maker fee
            long fee2 = container.calculateFee(price2, 1, 1, symbols.get(1).makerFee, symbols.get(1).feeScaleK);
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee1 - fee2));
            });

            container.updateCurrentPriceTo(15000, symbols.get(0).symbolId, quoteId);
            container.updateCurrentPriceTo(5000, symbols.get(1).symbolId, quoteId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
            });

            ApiAdjustUserBalance cmd = ApiAdjustUserBalance.builder().uid(userId1).transactionId(container.getRandomTransactionId() + 500).amount(-deposit).currency(quoteId).build();
            // 用户profit比较高, 但是提现额度高于了account存的钱, 此时不应该允许用户提现
            container.submitCommandSync(cmd, CommandResultCode.RISK_NSF);

            // 用户平仓symbol0
            container.createAskWithOrderId(makerOrderId5, userId1, size, 15000, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(takerOrderId6, userId3, size, 15000, symbols.get(0).symbolId, MarginMode.CROSS);

            // 平仓不收手续费, 因为降低了整体风险
            long fee3 = 0;
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee1 - fee2 - fee3 + 5000L));
            });

            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // 下现货单时要考虑该币种所有当前期货持仓 -- 空仓
    @Test
    public void testPlaceExchange() {
        long deposit = 10000L;
        long userId1 = UID_1;
        int size = 1;
        long price1 = 10000;
        long price2 = 15000;
        long makerOrderId1 = 1005L;
        long makerOrderId2 = 1007L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            List<CoreSymbolSpecification> symbolsExchange = container.initExchangeSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);

            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);

            ApiPlaceOrder order = container.genOrder(userId1, 1, 10000, symbolsExchange.get(0).symbolId, BID, GTC);

            container.submitCommandSync(order, CommandResultCode.RISK_NSF);

            // fee is 20, margin required is 120, total = 140
            container.addMoneyToUser(userId1, quoteId, 140 - 1);
            container.submitCommandSync(order, CommandResultCode.RISK_NSF);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getOrders().size(), is(1));
            });

            container.addMoneyToUser(userId1, quoteId, 1);
            container.submitCommandSync(order, CommandResultCode.SUCCESS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                // exchange dedect balance and fee first
                assertThat(profile.getAccounts().get(quoteId), is(120L));
                assertThat(profile.getOrders().size(), is(2));
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // 下现货单时要考虑该币种所有当前期货持仓 -- 开仓成功且有profit
    @Test
    public void testPlaceExchange2() {
        long deposit = 10000L;
        long userId1 = UID_1;
        long userId2 = UID_2;
        int size = 1;
        long price1 = 10000;
        long price2 = 15000;
        long makerOrderId1 = 1005L;
        long takerOrderId2 = 1006L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            List<CoreSymbolSpecification> symbolsExchange = container.initExchangeSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(takerOrderId2, userId2, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);

            container.updateCurrentPriceTo(15000, symbols.get(0).symbolId, quoteId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
            });

            ApiPlaceOrder order = container.genOrder(userId1, 1, 10000, symbolsExchange.get(0).symbolId, BID, GTC);
            // 1. free必须 > 0
            // 2. margin亏的时候也要考虑
            container.submitCommandSync(order, CommandResultCode.RISK_NSF);

            container.addMoneyToUser(userId1, quoteId, 29);
            container.submitCommandSync(order, CommandResultCode.RISK_NSF);

            container.addMoneyToUser(userId1, quoteId, 1);
            container.submitCommandSync(order, CommandResultCode.RISK_NSF);

            // maintainance margin还少75块钱
            container.addMoneyToUser(userId1, quoteId, 74);
            container.submitCommandSync(order, CommandResultCode.RISK_NSF);

            container.addMoneyToUser(userId1, quoteId, 1);
            container.submitCommandSync(order, CommandResultCode.SUCCESS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
            });
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // cross liquidation - MA-4925 [Improvement] cross强平时最好能检查强平结果
    @Test
    public void testCrossMarginLiquidation() {
        long deposit = 10000L;
        long userId1 = UID_1;
        long userId2 = UID_2;
        long userId3 = UID_3;
        int size = 1;
        long price1 = 10000;
        long price2 = 15000;
        long makerOrderId1 = 1005L;
        long takerOrderId2 = 1006L;
        long makerOrderId3 = 1007L;
        long takerOrderId4 = 1008L;
        long makerOrderId5 = 1009L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(userId3, MAX_VALUE, quoteId);

            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(makerOrderId3, userId1, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);

            container.createAskWithOrderId(takerOrderId2, userId2, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(takerOrderId4, userId2, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
            });

            container.updateCurrentPriceTo(2000, symbols.get(0).symbolId, quoteId);
            container.updateCurrentPriceTo(35000, symbols.get(1).symbolId, quoteId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
            });

            // userId3先设置一个单子用于强制平仓, 此时
            container.createBidWithOrderId(makerOrderId5, userId3, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);

            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);
            // 期待结果makerOrderId5可以被吃掉, position数量为1
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // cross liquidation - check剩余订单的仓位信息
    @Test
    public void testCrossMarginLiquidation2() {
        long deposit = 10000L;
        long userId1 = UID_1;
        long userId2 = UID_2;
        long userId3 = UID_3;
        int size = 1;
        long price1 = 10000;
        long price2 = 15000;
        long makerOrderId1 = 1005L;
        long takerOrderId2 = 1006L;
        long makerOrderId3 = 1007L;
        long takerOrderId4 = 1008L;
        long makerOrderId5 = 1009L;
        long makerOrderId6 = 1010L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(userId3, MAX_VALUE, quoteId);

            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(makerOrderId3, userId1, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);

            container.createAskWithOrderId(takerOrderId2, userId2, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(takerOrderId4, userId2, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
            });

            container.updateCurrentPriceTo(8000, symbols.get(0).symbolId, quoteId);
            container.updateCurrentPriceTo(25000, symbols.get(1).symbolId, quoteId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
            });

            // userId3先设置一个单子用于强制平仓, 此时
            container.createBidWithOrderId(makerOrderId5, userId3, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(makerOrderId6, userId3, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);

            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);
            // 期待结果makerOrderId6可以被挂出的强平吃掉
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(9840L));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().getFirst().get(0).direction, is(PositionDirection.LONG));
                assertThat(profile.getPositions().getFirst().get(0).quoteCurrency, is(quoteId));
                assertThat(profile.getPositions().getFirst().get(0).openVolume, is(1L));
                assertThat(profile.getPositions().getFirst().get(0).openPriceSum, is(10000L));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // cross liquidation - 混合isolated和cross强平, isolated先被强平
    @Test
    public void testCrossMarginLiquidation3() {
        long deposit = 10000L;
        long userId1 = UID_1;
        long userId2 = UID_2;
        long userId3 = UID_3;
        long userId4 = UID_4;
        long userId5 = UID_5;
        long userId6 = UID_6;
        int size = 1;
        long price1 = 10000;
        long price2 = 15000;
        long makerOrderId1 = 1005L;
        long takerOrderId1 = 1006L;
        long makerOrderId2 = 1007L;
        long takerOrderId2 = 1008L;
        long makerOrderId3 = 1009L;
        long takerOrderId3 = 1010L;
        long makerOrderId4 = 1011L;
        long takerOrderId4 = 1012L;
        long makerOrderId5 = 1013L;
        long takerOrderId5 = 1014L;
        long makerOrderId6 = 1015L;
        long takerOrderId6 = 1016L;
        long makerOrderId7 = 1017L;
        long takerOrderId7 = 1018L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(userId3, deposit, quoteId);
            container.createUserWithSpecificMoney(userId4, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(userId5, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(userId6, MAX_VALUE, quoteId);

            // userId1 and userId2 match
            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(makerOrderId2, userId1, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);

            container.createAskWithOrderId(takerOrderId1, userId2, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(takerOrderId2, userId2, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);

            // userId3 and userId4 match
            container.createBidWithOrderId(makerOrderId3, userId3, size, price1, symbols.get(0).symbolId, MarginMode.ISOLATED);
            container.createAskWithOrderId(makerOrderId4, userId3, size, price2, symbols.get(1).symbolId, MarginMode.ISOLATED);

            container.createAskWithOrderId(takerOrderId3, userId4, size, price1, symbols.get(0).symbolId, MarginMode.ISOLATED);
            container.createBidWithOrderId(takerOrderId4, userId4, size, price2, symbols.get(1).symbolId, MarginMode.ISOLATED);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
            });

            container.validateUserState(userId3, profile -> {
                assertThat(profile.getPositions().size(), is(2));
            });

            // 价格降到时, userId3因为是isolated开始触发强平, 但是此时userId1因为开的是cross margin(symbol1做多)所以没达到强平
            // userId1 symbol0: 10000 - 100 = -9900
            //         symbol1: 15000 - 1000 = 5000
            //         total profit = -4900
            //         balance = 9840
            //         profit + balance > 50(symbol0 maintenance margin) + 100(symbol1 maintenance margin)
            // userId3 symbol0: 10000 - 100 = -9900
            //         profit: -9900
            //         init margin: 100
            //         profit + init margin < 50 所以会被强平
            container.updateCurrentPriceTo(100, symbols.get(0).symbolId, quoteId);
            container.updateCurrentPriceTo(10000, symbols.get(1).symbolId, quoteId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
            });

            container.validateUserState(userId3, profile -> {
                assertThat(profile.getPositions().size(), is(2));
            });

            // userId5先设置一个单子(两手)用于强制平仓, 此时userId3会被强平, userId1不会强平
            container.createBidWithOrderId(makerOrderId5, userId5, 2, 100, symbols.get(0).symbolId, MarginMode.CROSS);

            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(9840L));
                assertThat(profile.getPositions().size(), is(2));
            });
            // 期待结果makerOrderId5可以被挂出的强平吃掉
            container.validateUserState(userId3, profile -> {
                assertThat(profile.getPositions().size(), is(1));
            });
            // userId5挂上去的订单2手被吃掉1手
            container.validateUserState(userId5, profile -> {
                assertThat(profile.getOrders().size(), is(1));
                assertThat(profile.getOrders().getFirst().get(0).filled, is(1L));
                assertThat(profile.getOrders().getFirst().get(0).size, is(2L));
                assertThat(profile.getOrders().getFirst().get(0).price, is(100L));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(55)).fundEventReport(fundEventCaptor.capture());
            // check fund event
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport liquidationAlertEvt = fundEvents.get(47);
            assertThat(userId3, Is.is(liquidationAlertEvt.getAccountId()));
            assertThat(quoteId, Is.is(liquidationAlertEvt.getBalances().getCurrency()));
            assertThat(10000, Is.is(liquidationAlertEvt.getPositions().getSymbolId()));
//            assertThat(takerOrderId, Is.is(liquidationAlertEvt.orderId));
            assertThat(PositionDirection.LONG, Is.is(liquidationAlertEvt.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.LIQUIDATION_ALERT, Is.is(liquidationAlertEvt.getEventType()));
            assertThat(0L, Is.is(liquidationAlertEvt.getBalances().getFree()));
            assertThat(0L, Is.is(liquidationAlertEvt.getPositions().getCumRealized()));
            assertThat(0L, Is.is(liquidationAlertEvt.getBalances().getLocked()));
            assertThat(10000L, Is.is(liquidationAlertEvt.getPositions().getOpenPriceSum()));
            assertThat(1L, Is.is(liquidationAlertEvt.getPositions().getQuantity()));
            // 10000价格跌到100, unrealizedProfit = 100 - 10000 = -9900
            assertThat(-9900L, Is.is(liquidationAlertEvt.getPositions().getUnrealizedProfit()));
            // 逐仓强平价格计算
            assertThat(9900L, Is.is(liquidationAlertEvt.getPositions().getLiquidationPrice()));
            assertThat(-1000L, Is.is(liquidationAlertEvt.getPositions().getMarginRatioScaleK()));

            IFundEventsHandler.FundEventReport liquidationEvt = fundEvents.get(49);
//            assertThat(userId3, Is.is(liquidationEvt.uid));
            assertThat(quoteId, Is.is(liquidationEvt.getBalances().getCurrency()));
            assertThat(10000, Is.is(liquidationEvt.getPositions().getSymbolId()));
//            assertThat(takerOrderId, Is.is(liquidationEvt.orderId));
            assertThat(PositionDirection.LONG, Is.is(liquidationEvt.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.LIQUIDATION, Is.is(liquidationEvt.getEventType()));
            assertThat(9740L, Is.is(liquidationEvt.getBalances().getFree()));
            assertThat(100L, Is.is(liquidationEvt.getBalances().getLocked()));
            assertThat(0L, Is.is(liquidationEvt.getPositions().getOpenPriceSum()));
            assertThat(-9900L, Is.is(liquidationEvt.getPositions().getCumRealized()));
            assertThat(0L, Is.is(liquidationEvt.getPositions().getQuantity()));
            // 平仓后openVolume为0, 故unrealizedProfit/liquidationPrice/marginRatioScaleK均为0
            checkEvent(liquidationEvt);
        }
    }

    // cross liquidation - profit + balance < 1.2 * maintenance margin时需要触发报警
    @Test
    public void testCrossMarginLiquidationWarning() {
        long deposit = 10000L;
        long userId1 = UID_1;
        long userId2 = UID_2;
        long userId3 = UID_3;
        int size = 1;
        long price1 = 10000;
        long price2 = 15000;
        long makerOrderId1 = 1005L;
        long takerOrderId1 = 1006L;
        long makerOrderId2 = 1007L;
        long takerOrderId2 = 1008L;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(userId3, deposit, quoteId);

            // userId1 and userId2 match
            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(makerOrderId2, userId1, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);

            container.createAskWithOrderId(takerOrderId1, userId2, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(takerOrderId2, userId2, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
            });

            // 价格降到时, userId3因为是isolated开始触发强平, 但是此时userId1因为开的是cross margin(symbol1做多)所以没达到强平
            // userId1 symbol0: 10000 - 5300 = 4700
            //         symbol1: 15000 - 2000 = 5000
            //         total profit = -9700
            //         balance = 9840
            //         profit + balance = -9700 + 9840 = 140 < 151(126 * 1.2)此时会触发alert

            container.updateCurrentPriceTo(5300, symbols.get(0).symbolId, quoteId);
            container.updateCurrentPriceTo(20000, symbols.get(1).symbolId, quoteId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
            });

            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(9840L));
                assertThat(profile.getPositions().size(), is(2));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(34)).fundEventReport(fundEventCaptor.capture());
            // check fund event
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport event1 = null;
            for (int i = 0; i < fundEvents.size(); i++) {
                IFundEventsHandler.FundEventReport r = fundEvents.get(i);
                if (r.getEventType().equals(FundEvent.FundEventType.MARGIN_ALERT)) {
                    event1 = r;
                    break;
                }
            }
            assertThat(userId1, Is.is(event1.getAccountId()));
            assertThat(quoteId, Is.is(event1.getBalances().getCurrency()));
            assertThat(10000, Is.is(event1.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(event1.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.MARGIN_ALERT, Is.is(event1.getEventType()));
            assertThat(0L, Is.is(event1.getBalances().getFree()));
            assertThat(0L, Is.is(event1.getBalances().getLocked()));
            assertThat(10000L, Is.is(event1.getPositions().getOpenPriceSum()));
            assertThat(1L, Is.is(event1.getPositions().getQuantity()));
            assertThat(-4700L, Is.is(event1.getPositions().getUnrealizedProfit()));
            assertThat(-1L, Is.is(event1.getPositions().getLiquidationPrice()));
            assertThat(1L, Is.is(event1.getPositions().getMarginRatioScaleK()));
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
            initCurrencies(container);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            container.createBidWithOrderId(makerOrderId, userId1, size, 10000, symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(takerOrderId, userId2, size, 10000, symbolId, MarginMode.CROSS);

            verify(handler, never()).spotExecutionReport(any());
            verify(handler, times(4)).futuresExecutionReport(futuresEventCaptor.capture());

            List<ITradeEventsHandler.FuturesExecutionReport> events = futuresEventCaptor.getAllValues();
            ITradeEventsHandler.FuturesExecutionReport event0 = events.get(0);
            assertThat(event0.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
            assertThat(event0.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.NEW));
            assertThat(event0.symbolId, Is.is(2));
            assertThat(event0.orderQtyScale, Is.is(1L));
            assertThat(event0.priceScale, Is.is(1L));
            assertThat(event0.userId, Is.is(userId1));
            assertThat(event0.clOrderId, Is.is(0L));
            assertThat(event0.orderId, Is.is(makerOrderId));
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
            assertThat(event1.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
            assertThat(event1.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.NEW));
            assertThat(event1.symbolId, Is.is(2));
            assertThat(event1.orderQtyScale, Is.is(1L));
            assertThat(event1.priceScale, Is.is(1L));
            assertThat(event1.userId, Is.is(userId2));
            assertThat(event1.clOrderId, Is.is(0L));
            assertThat(event1.orderId, Is.is(takerOrderId));
            assertThat(event1.orderType, Is.is(GTC));
            assertThat(event1.side, Is.is(ASK));
            assertThat(event1.counterpartyId, Is.is(-1L));
            assertThat(event1.price, Is.is(10000L));
            assertThat(event1.orderQty, Is.is(1L));
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

            ITradeEventsHandler.FuturesExecutionReport event2 = events.get(2);
            assertThat(event2.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
            assertThat(event2.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.FILLED));
            assertThat(event2.symbolId, Is.is(2));
            assertThat(event2.orderQtyScale, Is.is(1L));
            assertThat(event2.priceScale, Is.is(1L));
            assertThat(event2.userId, Is.is(userId2));
            assertThat(event2.clOrderId, Is.is(0L));
            assertThat(event2.orderId, Is.is(takerOrderId));
            assertThat(event2.orderType, Is.is(GTC));
            assertThat(event2.side, Is.is(ASK));
            assertThat(event2.counterpartyId, Is.is(userId1));
            assertThat(event2.price, Is.is(10000L));
            assertThat(event2.orderQty, Is.is(1L));
            assertThat(event2.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event2.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event2.lastQty, Is.is(1L));
            assertThat(event2.lastPx, Is.is(10000L));
            assertThat(event2.cumQty, Is.is(1L));
            assertThat(event2.cumQuoteQty, Is.is(10000L));
            assertThat(event2.avgPx, Is.is(10000L));
            assertThat(event2.fee, Is.is(0L));
            assertThat(event2.feeAssetId, Is.is(840));
            assertThat(event2.isMaker, Is.is(false));

            ITradeEventsHandler.FuturesExecutionReport event3 = events.get(3);
            assertThat(event3.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
            assertThat(event3.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.FILLED));
            assertThat(event3.symbolId, Is.is(2));
            assertThat(event3.orderQtyScale, Is.is(1L));
            assertThat(event3.priceScale, Is.is(1L));
            assertThat(event3.userId, Is.is(userId1));
            assertThat(event3.clOrderId, Is.is(0L));
            assertThat(event3.orderId, Is.is(makerOrderId));
            assertThat(event3.orderType, Is.is(GTC));
            assertThat(event3.side, Is.is(BID));
            assertThat(event3.counterpartyId, Is.is(userId2));
            assertThat(event3.price, Is.is(10000L));
            assertThat(event3.orderQty, Is.is(1L));
            assertThat(event3.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event3.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event3.lastQty, Is.is(1L));
            assertThat(event3.lastPx, Is.is(10000L));
            assertThat(event3.cumQty, Is.is(1L));
            assertThat(event3.cumQuoteQty, Is.is(10000L));
            assertThat(event3.avgPx, Is.is(10000L));
            assertThat(event3.fee, Is.is(0L));
            assertThat(event3.feeAssetId, Is.is(840));
            assertThat(event3.isMaker, Is.is(true));

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
            // check fund event
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport takerEvent = fundEvents.get(2);

            // check lock_pending event for maker, orderId should be maker's
            assertThat(userId1, Is.is(takerEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(takerEvent.getEventType()));
            assertThat(quoteId, Is.is(takerEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(takerEvent.getBalances().getCurrencyScakeK()));
            // 1000(balance) - 100(open position) - 20(maker fee) = 880
            assertThat(deposit - 100L - size * 20L, Is.is(takerEvent.getBalances().getFree()));
            assertThat(100L + size * 20L, Is.is(takerEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(takerEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(takerEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(takerEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.LONG, Is.is(takerEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(takerEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(takerEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(takerEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(takerEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(takerEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(takerEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(takerEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(takerEvent.getPositions().getMarkPrice()));
            checkEvent(takerEvent);

            // check lock_pending event for taker, orderId should be maker's
            IFundEventsHandler.FundEventReport makerEvent = fundEvents.get(3);
            assertThat(userId2, Is.is(makerEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(makerEvent.getEventType()));
            assertThat(quoteId, Is.is(makerEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(makerEvent.getBalances().getCurrencyScakeK()));
            // 1000(balance) - 100(open position) - 20(maker fee) = 880
            assertThat(MAX_VALUE - 100L - size * 20L, Is.is(makerEvent.getBalances().getFree()));
            assertThat(100L + size * 20L, Is.is(makerEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(makerEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(makerEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(makerEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.SHORT, Is.is(makerEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(makerEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(makerEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(makerEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(makerEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(makerEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(makerEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(makerEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(makerEvent.getPositions().getMarkPrice()));
            checkEvent(makerEvent);

            // check unlock_pending event for taker
            IFundEventsHandler.FundEventReport takerUnlockEvent = fundEvents.get(4);
            assertThat(userId2, Is.is(takerUnlockEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(takerUnlockEvent.getEventType()));
            assertThat(quoteId, Is.is(takerUnlockEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(takerUnlockEvent.getBalances().getCurrencyScakeK()));
            // 1000(balance) - 100(open position) - 20(maker fee) = 880
            assertThat(MAX_VALUE, Is.is(takerUnlockEvent.getBalances().getFree()));
            assertThat(0L, Is.is(takerUnlockEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(takerUnlockEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(takerUnlockEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(takerUnlockEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.SHORT, Is.is(takerUnlockEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(takerUnlockEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(takerUnlockEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(takerUnlockEvent.getPositions().getMarkPrice()));
            checkEvent(takerUnlockEvent);

            // check open position event for taker
            IFundEventsHandler.FundEventReport takerOpenPositionEvent = fundEvents.get(5);
            assertThat(userId2, Is.is(takerOpenPositionEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(takerOpenPositionEvent.getEventType()));
            assertThat(quoteId, Is.is(takerOpenPositionEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(takerOpenPositionEvent.getBalances().getCurrencyScakeK()));
            // 1000(balance) - 100(open position) - 20(maker fee) = 880
            assertThat(MAX_VALUE - 100 - 20, Is.is(takerOpenPositionEvent.getBalances().getFree()));
            assertThat(100L, Is.is(takerOpenPositionEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(takerOpenPositionEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(takerOpenPositionEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(takerOpenPositionEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.SHORT, Is.is(takerOpenPositionEvent.getPositions().getDirection()));
            assertThat(1L, Is.is(takerOpenPositionEvent.getPositions().getQuantity()));
            assertThat(10000L, Is.is(takerOpenPositionEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(takerOpenPositionEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(takerOpenPositionEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(takerOpenPositionEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(takerOpenPositionEvent.getPositions().getLeverage()));
            assertThat(10000L, Is.is(takerOpenPositionEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(takerOpenPositionEvent.getPositions().getMarkPrice()));
            // 标记价格和开仓价格相同, 所以算出来的unrealizedProfit=0
            assertThat(0L, Is.is(takerOpenPositionEvent.getPositions().getUnrealizedProfit()));
            // maintenanceMargin = 50
            // liquidationPrice = direction * (maintenanceMargin - totalMargin) + openPriceSum = -1 * (50 - 100) + 10000 * 1 = 10050
            assertThat(3990029L, Is.is(takerOpenPositionEvent.getPositions().getLiquidationPrice()));
            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 50 / MAX_VALUE) = 0
            assertThat(0L, Is.is(takerOpenPositionEvent.getPositions().getMarginRatioScaleK()));

            // check unlock_pending event for maker
            IFundEventsHandler.FundEventReport makerUnlockEvent = fundEvents.get(6);
            assertThat(userId1, Is.is(makerUnlockEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(makerUnlockEvent.getEventType()));
            assertThat(quoteId, Is.is(makerUnlockEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(makerUnlockEvent.getBalances().getCurrencyScakeK()));
            // 1000(balance) - 100(open position) - 20(maker fee) = 880
            assertThat(1000L, Is.is(makerUnlockEvent.getBalances().getFree()));
            assertThat(0L, Is.is(makerUnlockEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(makerUnlockEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(makerUnlockEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(makerUnlockEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.LONG, Is.is(makerUnlockEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(makerUnlockEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(makerUnlockEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(makerUnlockEvent.getPositions().getMarkPrice()));
            checkEvent(makerUnlockEvent);

            // check open position event
            IFundEventsHandler.FundEventReport makerOpenPositionEvent = fundEvents.get(7);
            assertThat(userId1, Is.is(makerOpenPositionEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(makerOpenPositionEvent.getEventType()));
            assertThat(quoteId, Is.is(makerOpenPositionEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(makerOpenPositionEvent.getBalances().getCurrencyScakeK()));
            // 1000(balance) - 100(open position) - 10(maker fee) = 890
            assertThat(890L, Is.is(makerOpenPositionEvent.getBalances().getFree()));
            assertThat(100L, Is.is(makerOpenPositionEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(makerOpenPositionEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(makerOpenPositionEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(makerOpenPositionEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.LONG, Is.is(makerOpenPositionEvent.getPositions().getDirection()));
            assertThat(1L, Is.is(makerOpenPositionEvent.getPositions().getQuantity()));
            assertThat(10000L, Is.is(makerOpenPositionEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(makerOpenPositionEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(makerOpenPositionEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(makerOpenPositionEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(makerOpenPositionEvent.getPositions().getLeverage()));
            assertThat(10000L, Is.is(makerOpenPositionEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(makerOpenPositionEvent.getPositions().getMarkPrice()));
            // openVolume * markPrice - openPriceSum = 10000 - 10000 = 0
            assertThat(0L, Is.is(makerOpenPositionEvent.getPositions().getUnrealizedProfit()));
            // liquidationPrice = direction * (maintenanceMargin - totalMargin) + openPriceSum = 1 * (50 - 100) + 10000 * 1 = 9050
            assertThat(9055L, Is.is(makerOpenPositionEvent.getPositions().getLiquidationPrice()));
            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 50 / 990) = long (50.5) = 50
            assertThat(50L, Is.is(makerOpenPositionEvent.getPositions().getMarginRatioScaleK()));
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
            initCurrencies(container);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            container.createAskWithOrderId(makerOrderId, userId1, size, 10000, symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(takerOrderId, userId2, size, 10000, symbolId, MarginMode.CROSS);

            verify(handler, never()).spotExecutionReport(any());
            verify(handler, times(4)).futuresExecutionReport(futuresEventCaptor.capture());

            List<ITradeEventsHandler.FuturesExecutionReport> events = futuresEventCaptor.getAllValues();
            ITradeEventsHandler.FuturesExecutionReport event0 = events.get(0);
            assertThat(event0.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
            assertThat(event0.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.NEW));
            assertThat(event0.symbolId, Is.is(2));
            assertThat(event0.orderQtyScale, Is.is(1L));
            assertThat(event0.priceScale, Is.is(1L));
            assertThat(event0.userId, Is.is(userId1));
            assertThat(event0.clOrderId, Is.is(0L));
            assertThat(event0.orderId, Is.is(makerOrderId));
            assertThat(event0.orderType, Is.is(GTC));
            assertThat(event0.side, Is.is(ASK));
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
            assertThat(event1.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
            assertThat(event1.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.NEW));
            assertThat(event1.symbolId, Is.is(2));
            assertThat(event1.orderQtyScale, Is.is(1L));
            assertThat(event1.priceScale, Is.is(1L));
            assertThat(event1.userId, Is.is(userId2));
            assertThat(event1.clOrderId, Is.is(0L));
            assertThat(event1.orderId, Is.is(takerOrderId));
            assertThat(event1.orderType, Is.is(GTC));
            assertThat(event1.side, Is.is(BID));
            assertThat(event1.counterpartyId, Is.is(-1L));
            assertThat(event1.price, Is.is(10000L));
            assertThat(event1.orderQty, Is.is(1L));
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

            ITradeEventsHandler.FuturesExecutionReport event2 = events.get(2);
            assertThat(event2.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
            assertThat(event2.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.FILLED));
            assertThat(event2.symbolId, Is.is(2));
            assertThat(event2.orderQtyScale, Is.is(1L));
            assertThat(event2.priceScale, Is.is(1L));
            assertThat(event2.userId, Is.is(userId2));
            assertThat(event2.clOrderId, Is.is(0L));
            assertThat(event2.orderId, Is.is(takerOrderId));
            assertThat(event2.orderType, Is.is(GTC));
            assertThat(event2.side, Is.is(BID));
            assertThat(event2.counterpartyId, Is.is(userId1));
            assertThat(event2.price, Is.is(10000L));
            assertThat(event2.orderQty, Is.is(1L));
            assertThat(event2.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event2.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event2.lastQty, Is.is(1L));
            assertThat(event2.lastPx, Is.is(10000L));
            assertThat(event2.cumQty, Is.is(1L));
            assertThat(event2.cumQuoteQty, Is.is(10000L));
            assertThat(event2.avgPx, Is.is(10000L));
            assertThat(event2.fee, Is.is(0L));
            assertThat(event2.feeAssetId, Is.is(840));
            assertThat(event2.isMaker, Is.is(false));

            ITradeEventsHandler.FuturesExecutionReport event3 = events.get(3);
            assertThat(event3.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
            assertThat(event3.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.FILLED));
            assertThat(event3.symbolId, Is.is(2));
            assertThat(event3.orderQtyScale, Is.is(1L));
            assertThat(event3.priceScale, Is.is(1L));
            assertThat(event3.userId, Is.is(userId1));
            assertThat(event3.clOrderId, Is.is(0L));
            assertThat(event3.orderId, Is.is(makerOrderId));
            assertThat(event3.orderType, Is.is(GTC));
            assertThat(event3.side, Is.is(ASK));
            assertThat(event3.counterpartyId, Is.is(userId2));
            assertThat(event3.price, Is.is(10000L));
            assertThat(event3.orderQty, Is.is(1L));
            assertThat(event3.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event3.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event3.lastQty, Is.is(1L));
            assertThat(event3.lastPx, Is.is(10000L));
            assertThat(event3.cumQty, Is.is(1L));
            assertThat(event3.cumQuoteQty, Is.is(10000L));
            assertThat(event3.avgPx, Is.is(10000L));
            assertThat(event3.fee, Is.is(0L));
            assertThat(event3.feeAssetId, Is.is(840));
            assertThat(event3.isMaker, Is.is(true));

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
            // check fund event
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport takerEvent = fundEvents.get(2);

            // check lock_pending event for maker, orderId should be maker's
            assertThat(userId1, Is.is(takerEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(takerEvent.getEventType()));
            assertThat(quoteId, Is.is(takerEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(takerEvent.getBalances().getCurrencyScakeK()));
            // 1000(balance) - 100(open position) - 20(maker fee) = 880
            assertThat(deposit - 100L - size * 20L, Is.is(takerEvent.getBalances().getFree()));
            assertThat(100L + size * 20L, Is.is(takerEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(takerEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(takerEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(takerEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.SHORT, Is.is(takerEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(takerEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(takerEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(takerEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(takerEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(takerEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(takerEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(takerEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(takerEvent.getPositions().getMarkPrice()));
            checkEvent(takerEvent);

            // check lock_pending event for taker, orderId should be maker's
            IFundEventsHandler.FundEventReport makerEvent = fundEvents.get(3);
            assertThat(userId2, Is.is(makerEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(makerEvent.getEventType()));
            assertThat(quoteId, Is.is(makerEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(makerEvent.getBalances().getCurrencyScakeK()));
            // 1000(balance) - 100(open position) - 20(maker fee) = 880
            assertThat(MAX_VALUE - 100L - size * 20L, Is.is(makerEvent.getBalances().getFree()));
            assertThat(100L + size * 20L, Is.is(makerEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(makerEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(makerEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(makerEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.LONG, Is.is(makerEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(makerEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(makerEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(makerEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(makerEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(makerEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(makerEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(makerEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(makerEvent.getPositions().getMarkPrice()));
            checkEvent(makerEvent);

            // check unlock_pending event for taker
            IFundEventsHandler.FundEventReport takerUnlockEvent = fundEvents.get(4);
            assertThat(userId2, Is.is(takerUnlockEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(takerUnlockEvent.getEventType()));
            assertThat(quoteId, Is.is(takerUnlockEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(takerUnlockEvent.getBalances().getCurrencyScakeK()));
            // 1000(balance) - 100(open position) - 20(maker fee) = 880
            assertThat(MAX_VALUE, Is.is(takerUnlockEvent.getBalances().getFree()));
            assertThat(0L, Is.is(takerUnlockEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(takerUnlockEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(takerUnlockEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(takerUnlockEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.LONG, Is.is(takerUnlockEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(takerUnlockEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(takerUnlockEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(takerUnlockEvent.getPositions().getMarkPrice()));
            checkEvent(takerUnlockEvent);

            // check open position event for taker
            IFundEventsHandler.FundEventReport takerOpenPositionEvent = fundEvents.get(5);
            assertThat(userId2, Is.is(takerOpenPositionEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(takerOpenPositionEvent.getEventType()));
            assertThat(quoteId, Is.is(takerOpenPositionEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(takerOpenPositionEvent.getBalances().getCurrencyScakeK()));
            // 1000(balance) - 100(open position) - 20(maker fee) = 880
            assertThat(MAX_VALUE - 100 - 20, Is.is(takerOpenPositionEvent.getBalances().getFree()));
            assertThat(100L, Is.is(takerOpenPositionEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(takerOpenPositionEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(takerOpenPositionEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(takerOpenPositionEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.LONG, Is.is(takerOpenPositionEvent.getPositions().getDirection()));
            assertThat(1L, Is.is(takerOpenPositionEvent.getPositions().getQuantity()));
            assertThat(10000L, Is.is(takerOpenPositionEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(takerOpenPositionEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(takerOpenPositionEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(takerOpenPositionEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(takerOpenPositionEvent.getPositions().getLeverage()));
            assertThat(10000L, Is.is(takerOpenPositionEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(takerOpenPositionEvent.getPositions().getMarkPrice()));
            // 标记价格和开仓价格相同, 所以算出来的unrealizedProfit=0
            assertThat(0L, Is.is(takerOpenPositionEvent.getPositions().getUnrealizedProfit()));
            assertThat(-1L, Is.is(takerOpenPositionEvent.getPositions().getLiquidationPrice()));
            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 50 / MAX_VALUE) = 0
            assertThat(0L, Is.is(takerOpenPositionEvent.getPositions().getMarginRatioScaleK()));

            // check unlock_pending event for maker
            IFundEventsHandler.FundEventReport makerUnlockEvent = fundEvents.get(6);
            assertThat(userId1, Is.is(makerUnlockEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(makerUnlockEvent.getEventType()));
            assertThat(quoteId, Is.is(makerUnlockEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(makerUnlockEvent.getBalances().getCurrencyScakeK()));
            // 1000(balance) - 100(open position) - 20(maker fee) = 880
            assertThat(1000L, Is.is(makerUnlockEvent.getBalances().getFree()));
            assertThat(0L, Is.is(makerUnlockEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(makerUnlockEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(makerUnlockEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(makerUnlockEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.SHORT, Is.is(makerUnlockEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(makerUnlockEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(makerUnlockEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(makerUnlockEvent.getPositions().getMarkPrice()));
            checkEvent(makerUnlockEvent);

            // check open position event
            IFundEventsHandler.FundEventReport makerOpenPositionEvent = fundEvents.get(7);
            assertThat(userId1, Is.is(makerOpenPositionEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(makerOpenPositionEvent.getEventType()));
            assertThat(quoteId, Is.is(makerOpenPositionEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(makerOpenPositionEvent.getBalances().getCurrencyScakeK()));
            // 1000(balance) - 100(open position) - 10(maker fee) = 890
            assertThat(890L, Is.is(makerOpenPositionEvent.getBalances().getFree()));
            assertThat(100L, Is.is(makerOpenPositionEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(makerOpenPositionEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(makerOpenPositionEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(makerOpenPositionEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.SHORT, Is.is(makerOpenPositionEvent.getPositions().getDirection()));
            assertThat(1L, Is.is(makerOpenPositionEvent.getPositions().getQuantity()));
            assertThat(10000L, Is.is(makerOpenPositionEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(makerOpenPositionEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(makerOpenPositionEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(makerOpenPositionEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(makerOpenPositionEvent.getPositions().getLeverage()));
            assertThat(10000L, Is.is(makerOpenPositionEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(makerOpenPositionEvent.getPositions().getMarkPrice()));
            // openVolume * markPrice - openPriceSum = 10000 - 10000 = 0
            assertThat(0L, Is.is(makerOpenPositionEvent.getPositions().getUnrealizedProfit()));
            assertThat(10935L, Is.is(makerOpenPositionEvent.getPositions().getLiquidationPrice()));
            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 50 / 990) = long (50.5) = 50
            assertThat(50L, Is.is(makerOpenPositionEvent.getPositions().getMarginRatioScaleK()));
        }
    }

    // 开多个仓位, 测试部分成交, taker为Ask
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
            initCurrencies(container);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            container.createBidWithOrderId(makerOrderId, userId1, size, 10000, symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(takerOrderId, userId2, txSize, 10000, symbolId, MarginMode.CROSS);

            verify(handler, never()).spotExecutionReport(any());
            verify(handler, times(4)).futuresExecutionReport(futuresEventCaptor.capture());

            List<ITradeEventsHandler.FuturesExecutionReport> events = futuresEventCaptor.getAllValues();
            ITradeEventsHandler.FuturesExecutionReport event0 = events.get(0);
            assertThat(event0.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
            assertThat(event0.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.NEW));
            assertThat(event0.symbolId, Is.is(2));
            assertThat(event0.orderQtyScale, Is.is(1L));
            assertThat(event0.priceScale, Is.is(1L));
            assertThat(event0.userId, Is.is(userId1));
            assertThat(event0.clOrderId, Is.is(0L));
            assertThat(event0.orderId, Is.is(makerOrderId));
            assertThat(event0.orderType, Is.is(GTC));
            assertThat(event0.side, Is.is(BID));
            assertThat(event0.counterpartyId, Is.is(-1L));
            assertThat(event0.price, Is.is(10000L));
            assertThat(event0.orderQty, Is.is(size + 0L));
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
            assertThat(event1.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
            assertThat(event1.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.NEW));
            assertThat(event1.symbolId, Is.is(2));
            assertThat(event1.orderQtyScale, Is.is(1L));
            assertThat(event1.priceScale, Is.is(1L));
            assertThat(event1.userId, Is.is(userId2));
            assertThat(event1.clOrderId, Is.is(0L));
            assertThat(event1.orderId, Is.is(takerOrderId));
            assertThat(event1.orderType, Is.is(GTC));
            assertThat(event1.side, Is.is(ASK));
            assertThat(event1.counterpartyId, Is.is(-1L));
            assertThat(event1.price, Is.is(10000L));
            assertThat(event1.orderQty, Is.is(txSize + 0L));
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

            ITradeEventsHandler.FuturesExecutionReport event2 = events.get(2);
            assertThat(event2.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
            assertThat(event2.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.FILLED));
            assertThat(event2.symbolId, Is.is(2));
            assertThat(event2.orderQtyScale, Is.is(1L));
            assertThat(event2.priceScale, Is.is(1L));
            assertThat(event2.userId, Is.is(userId2));
            assertThat(event2.clOrderId, Is.is(0L));
            assertThat(event2.orderId, Is.is(takerOrderId));
            assertThat(event2.orderType, Is.is(GTC));
            assertThat(event2.side, Is.is(ASK));
            assertThat(event2.counterpartyId, Is.is(userId1));
            assertThat(event2.price, Is.is(10000L));
            assertThat(event2.orderQty, Is.is(txSize + 0L));
            assertThat(event2.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event2.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event2.lastQty, Is.is(txSize + 0L));
            assertThat(event2.lastPx, Is.is(10000L));
            assertThat(event2.cumQty, Is.is(txSize + 0L));
            assertThat(event2.cumQuoteQty, Is.is(10000L * txSize));
            assertThat(event2.avgPx, Is.is(10000L));
            assertThat(event2.fee, Is.is(0L));
            assertThat(event2.feeAssetId, Is.is(840));
            assertThat(event2.isMaker, Is.is(false));

            ITradeEventsHandler.FuturesExecutionReport event3 = events.get(3);
            assertThat(event3.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
            assertThat(event3.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.PARTIALLY_FILLED));
            assertThat(event3.symbolId, Is.is(2));
            assertThat(event3.orderQtyScale, Is.is(1L));
            assertThat(event3.priceScale, Is.is(1L));
            assertThat(event3.userId, Is.is(userId1));
            assertThat(event3.clOrderId, Is.is(0L));
            assertThat(event3.orderId, Is.is(makerOrderId));
            assertThat(event3.orderType, Is.is(GTC));
            assertThat(event3.side, Is.is(BID));
            assertThat(event3.counterpartyId, Is.is(userId2));
            assertThat(event3.price, Is.is(10000L));
            assertThat(event3.orderQty, Is.is(size + 0L));
            assertThat(event3.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event3.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event3.lastQty, Is.is(txSize + 0L));
            assertThat(event3.lastPx, Is.is(10000L));
            assertThat(event3.cumQty, Is.is(txSize + 0L));
            assertThat(event3.cumQuoteQty, Is.is(10000L * txSize));
            assertThat(event3.avgPx, Is.is(10000L));
            assertThat(event3.fee, Is.is(0L));
            assertThat(event3.feeAssetId, Is.is(840));
            assertThat(event3.isMaker, Is.is(true));
//            assertThat(event3.bidsNotional, Is.is(100000L));
//            assertThat(event3.asksNotional, Is.is(0L));
//            assertThat(event3.bidsQty, Is.is(10L));
//            assertThat(event3.asksQty, Is.is(0L));

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
            // check fund event
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport takerEvent = fundEvents.get(2);

            // check lock_pending event for maker
            assertThat(userId1, Is.is(takerEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(takerEvent.getEventType()));
            assertThat(quoteId, Is.is(takerEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(takerEvent.getBalances().getCurrencyScakeK()));
            // free = deposit - size * 100 (required margin) - size * 20 (taker fee)
            assertThat(deposit - size * 100L - size * 20L, Is.is(takerEvent.getBalances().getFree()));
            assertThat(size * 100L + size * 20L, Is.is(takerEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(takerEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(takerEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(takerEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.LONG, Is.is(takerEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(takerEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(takerEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(takerEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(takerEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(takerEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(takerEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(takerEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(takerEvent.getPositions().getMarkPrice()));
            checkEvent(takerEvent);

            // check lock_pending event for taker
            IFundEventsHandler.FundEventReport makerEvent = fundEvents.get(3);
            assertThat(userId2, Is.is(makerEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(makerEvent.getEventType()));
            assertThat(quoteId, Is.is(makerEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(makerEvent.getBalances().getCurrencyScakeK()));
            // free = MAX_VALUE - txSize * 100 (required margin) - txSize * 20 (taker fee)
            assertThat(MAX_VALUE - txSize * 100L - txSize * 20L, Is.is(makerEvent.getBalances().getFree()));
            assertThat(txSize * 100L + txSize * 20L, Is.is(makerEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(makerEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(makerEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(makerEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.SHORT, Is.is(makerEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(makerEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(makerEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(makerEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(makerEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(makerEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(makerEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(makerEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(makerEvent.getPositions().getMarkPrice()));
            checkEvent(makerEvent);

            // check unlock_pending event for taker
            IFundEventsHandler.FundEventReport takerUnlockEvent = fundEvents.get(4);
            assertThat(userId2, Is.is(takerUnlockEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(takerUnlockEvent.getEventType()));
            assertThat(quoteId, Is.is(takerUnlockEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(takerUnlockEvent.getBalances().getCurrencyScakeK()));
            assertThat(MAX_VALUE, Is.is(takerUnlockEvent.getBalances().getFree()));
            assertThat(0L, Is.is(takerUnlockEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(takerUnlockEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(takerUnlockEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(takerUnlockEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.SHORT, Is.is(takerUnlockEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(takerUnlockEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(takerUnlockEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(takerUnlockEvent.getPositions().getMarkPrice()));
            checkEvent(takerUnlockEvent);

            // check open position event for taker
            IFundEventsHandler.FundEventReport takerOpenPositionEvent = fundEvents.get(5);
            assertThat(userId2, Is.is(takerOpenPositionEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(takerOpenPositionEvent.getEventType()));
            assertThat(quoteId, Is.is(takerOpenPositionEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(takerOpenPositionEvent.getBalances().getCurrencyScakeK()));
            // free = MAX_VALUE - cost - fee
            assertThat(MAX_VALUE - txSize * 100L - txSize * 20L, Is.is(takerOpenPositionEvent.getBalances().getFree()));
            assertThat(txSize * 100L, Is.is(takerOpenPositionEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(takerOpenPositionEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(takerOpenPositionEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(takerOpenPositionEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.SHORT, Is.is(takerOpenPositionEvent.getPositions().getDirection()));
            assertThat(txSize + 0L, Is.is(takerOpenPositionEvent.getPositions().getQuantity()));
            assertThat(10000L * txSize, Is.is(takerOpenPositionEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(takerOpenPositionEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(takerOpenPositionEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(takerOpenPositionEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(takerOpenPositionEvent.getPositions().getLeverage()));
            assertThat(10000L * txSize, Is.is(takerOpenPositionEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(takerOpenPositionEvent.getPositions().getMarkPrice()));
            // openVolume * markPrice - openPriceSum = txSize * 10000 - txSize * 10000 = 0
            assertThat(0L, Is.is(takerOpenPositionEvent.getPositions().getUnrealizedProfit()));
            assertThat(1999980L, Is.is(takerOpenPositionEvent.getPositions().getLiquidationPrice()));
            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * (50 * txSize) / (MAX_VALUE)) = 0
            assertThat(0L, Is.is(takerOpenPositionEvent.getPositions().getMarginRatioScaleK()));

            // check unlock_pending event for maker
            IFundEventsHandler.FundEventReport makerUnlockEvent = fundEvents.get(6);
            assertThat(userId1, Is.is(makerUnlockEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(makerUnlockEvent.getEventType()));
            assertThat(quoteId, Is.is(makerUnlockEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(makerUnlockEvent.getBalances().getCurrencyScakeK()));
            // free = deposit - (size - txSize) * 100 (remaining margin) - (size - txSize) * 20 (taker fee)
            assertThat(deposit - (size - txSize) * 100L - (size - txSize) * 20L, Is.is(makerUnlockEvent.getBalances().getFree()));
            assertThat((size - txSize) * 100L + (size - txSize) * 20L, Is.is(makerUnlockEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(makerUnlockEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(makerUnlockEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(makerUnlockEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.LONG, Is.is(makerUnlockEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(makerUnlockEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(makerUnlockEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(makerUnlockEvent.getPositions().getMarkPrice()));
            checkEvent(makerUnlockEvent);

            // check open position event for maker
            IFundEventsHandler.FundEventReport makerOpenPositionEvent = fundEvents.get(7);
            assertThat(userId1, Is.is(makerOpenPositionEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(makerOpenPositionEvent.getEventType()));
            assertThat(quoteId, Is.is(makerOpenPositionEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(makerOpenPositionEvent.getBalances().getCurrencyScakeK()));
            // free = deposit - size * 100 (current position) - (size - txSize) * 20 (taker fee in advance) - txSize * 10 (maker fee)
            assertThat(deposit - size * 100L - (size - txSize) * 20L - txSize * 10L, Is.is(makerOpenPositionEvent.getBalances().getFree()));
            assertThat(size * 100L + (size - txSize) * 20L, Is.is(makerOpenPositionEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(makerOpenPositionEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(makerOpenPositionEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(makerOpenPositionEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.LONG, Is.is(makerOpenPositionEvent.getPositions().getDirection()));
            assertThat(txSize + 0L, Is.is(makerOpenPositionEvent.getPositions().getQuantity()));
            assertThat(10000L * txSize, Is.is(makerOpenPositionEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(makerOpenPositionEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(makerOpenPositionEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(makerOpenPositionEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(makerOpenPositionEvent.getPositions().getLeverage()));
            assertThat(10000L * txSize, Is.is(makerOpenPositionEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(makerOpenPositionEvent.getPositions().getMarkPrice()));
            // openVolume * markPrice - openPriceSum = txSize * 10000 - txSize * 10000 = 0
            assertThat(0L, Is.is(makerOpenPositionEvent.getPositions().getUnrealizedProfit()));
            assertThat(5035L, Is.is(makerOpenPositionEvent.getPositions().getLiquidationPrice()));
            assertThat(10L, Is.is(makerOpenPositionEvent.getPositions().getMarginRatioScaleK()));
        }
    }

    // 开多个仓位, 测试部分成交, taker为Bid
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
            initCurrencies(container);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            container.createAskWithOrderId(makerOrderId, userId1, size, 10000, symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(takerOrderId, userId2, txSize, 10000, symbolId, MarginMode.CROSS);

            verify(handler, never()).spotExecutionReport(any());
            verify(handler, times(4)).futuresExecutionReport(futuresEventCaptor.capture());

            List<ITradeEventsHandler.FuturesExecutionReport> events = futuresEventCaptor.getAllValues();
            ITradeEventsHandler.FuturesExecutionReport event0 = events.get(0);
            assertThat(event0.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
            assertThat(event0.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.NEW));
            assertThat(event0.symbolId, Is.is(2));
            assertThat(event0.orderQtyScale, Is.is(1L));
            assertThat(event0.priceScale, Is.is(1L));
            assertThat(event0.userId, Is.is(userId1));
            assertThat(event0.clOrderId, Is.is(0L));
            assertThat(event0.orderId, Is.is(makerOrderId));
            assertThat(event0.orderType, Is.is(GTC));
            assertThat(event0.side, Is.is(ASK));
            assertThat(event0.counterpartyId, Is.is(-1L));
            assertThat(event0.price, Is.is(10000L));
            assertThat(event0.orderQty, Is.is(size + 0L));
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
            assertThat(event1.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
            assertThat(event1.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.NEW));
            assertThat(event1.symbolId, Is.is(2));
            assertThat(event1.orderQtyScale, Is.is(1L));
            assertThat(event1.priceScale, Is.is(1L));
            assertThat(event1.userId, Is.is(userId2));
            assertThat(event1.clOrderId, Is.is(0L));
            assertThat(event1.orderId, Is.is(takerOrderId));
            assertThat(event1.orderType, Is.is(GTC));
            assertThat(event1.side, Is.is(BID));
            assertThat(event1.counterpartyId, Is.is(-1L));
            assertThat(event1.price, Is.is(10000L));
            assertThat(event1.orderQty, Is.is(txSize + 0L));
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

            ITradeEventsHandler.FuturesExecutionReport event2 = events.get(2);
            assertThat(event2.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
            assertThat(event2.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.FILLED));
            assertThat(event2.symbolId, Is.is(2));
            assertThat(event2.orderQtyScale, Is.is(1L));
            assertThat(event2.priceScale, Is.is(1L));
            assertThat(event2.userId, Is.is(userId2));
            assertThat(event2.clOrderId, Is.is(0L));
            assertThat(event2.orderId, Is.is(takerOrderId));
            assertThat(event2.orderType, Is.is(GTC));
            assertThat(event2.side, Is.is(BID));
            assertThat(event2.counterpartyId, Is.is(userId1));
            assertThat(event2.price, Is.is(10000L));
            assertThat(event2.orderQty, Is.is(txSize + 0L));
            assertThat(event2.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event2.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event2.lastQty, Is.is(txSize + 0L));
            assertThat(event2.lastPx, Is.is(10000L));
            assertThat(event2.cumQty, Is.is(txSize + 0L));
            assertThat(event2.cumQuoteQty, Is.is(10000L * txSize));
            assertThat(event2.avgPx, Is.is(10000L));
            assertThat(event2.fee, Is.is(0L));
            assertThat(event2.feeAssetId, Is.is(840));
            assertThat(event2.isMaker, Is.is(false));

            ITradeEventsHandler.FuturesExecutionReport event3 = events.get(3);
            assertThat(event3.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
            assertThat(event3.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.PARTIALLY_FILLED));
            assertThat(event3.symbolId, Is.is(2));
            assertThat(event3.orderQtyScale, Is.is(1L));
            assertThat(event3.priceScale, Is.is(1L));
            assertThat(event3.userId, Is.is(userId1));
            assertThat(event3.clOrderId, Is.is(0L));
            assertThat(event3.orderId, Is.is(makerOrderId));
            assertThat(event3.orderType, Is.is(GTC));
            assertThat(event3.side, Is.is(ASK));
            assertThat(event3.counterpartyId, Is.is(userId2));
            assertThat(event3.price, Is.is(10000L));
            assertThat(event3.orderQty, Is.is(size + 0L));
            assertThat(event3.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event3.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event3.lastQty, Is.is(txSize + 0L));
            assertThat(event3.lastPx, Is.is(10000L));
            assertThat(event3.cumQty, Is.is(txSize + 0L));
            assertThat(event3.cumQuoteQty, Is.is(10000L * txSize));
            assertThat(event3.avgPx, Is.is(10000L));
            assertThat(event3.fee, Is.is(0L));
            assertThat(event3.feeAssetId, Is.is(840));
            assertThat(event3.isMaker, Is.is(true));

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
            // check fund event
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport takerEvent = fundEvents.get(2);

            // check lock_pending event for maker
            assertThat(userId1, Is.is(takerEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(takerEvent.getEventType()));
            assertThat(quoteId, Is.is(takerEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(takerEvent.getBalances().getCurrencyScakeK()));
            // free = deposit - size * 100 (required margin) - size * 20 (taker fee)
            assertThat(deposit - size * 100L - size * 20L, Is.is(takerEvent.getBalances().getFree()));
            assertThat(size * 100L + size * 20L, Is.is(takerEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(takerEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(takerEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(takerEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.SHORT, Is.is(takerEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(takerEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(takerEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(takerEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(takerEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(takerEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(takerEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(takerEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(takerEvent.getPositions().getMarkPrice()));
            checkEvent(takerEvent);

            // check lock_pending event for taker
            IFundEventsHandler.FundEventReport makerEvent = fundEvents.get(3);
            assertThat(userId2, Is.is(makerEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.LOCK_PENDING, Is.is(makerEvent.getEventType()));
            assertThat(quoteId, Is.is(makerEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(makerEvent.getBalances().getCurrencyScakeK()));
            // free = MAX_VALUE - txSize * 100 (required margin) - txSize * 20 (taker fee)
            assertThat(MAX_VALUE - txSize * 100L - txSize * 20L, Is.is(makerEvent.getBalances().getFree()));
            assertThat(txSize * 100L + txSize * 20L, Is.is(makerEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(makerEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(makerEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(makerEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.LONG, Is.is(makerEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(makerEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(makerEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(makerEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(makerEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(makerEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(makerEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(makerEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(makerEvent.getPositions().getMarkPrice()));
            checkEvent(makerEvent);

            // check unlock_pending event for taker
            IFundEventsHandler.FundEventReport takerUnlockEvent = fundEvents.get(4);
            assertThat(userId2, Is.is(takerUnlockEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(takerUnlockEvent.getEventType()));
            assertThat(quoteId, Is.is(takerUnlockEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(takerUnlockEvent.getBalances().getCurrencyScakeK()));
            assertThat(MAX_VALUE, Is.is(takerUnlockEvent.getBalances().getFree()));
            assertThat(0L, Is.is(takerUnlockEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(takerUnlockEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(takerUnlockEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(takerUnlockEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.LONG, Is.is(takerUnlockEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(takerUnlockEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(takerUnlockEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(takerUnlockEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(takerUnlockEvent.getPositions().getMarkPrice()));
            checkEvent(takerUnlockEvent);

            // check open position event for taker
            IFundEventsHandler.FundEventReport takerOpenPositionEvent = fundEvents.get(5);
            assertThat(userId2, Is.is(takerOpenPositionEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(takerOpenPositionEvent.getEventType()));
            assertThat(quoteId, Is.is(takerOpenPositionEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(takerOpenPositionEvent.getBalances().getCurrencyScakeK()));
            // free = MAX_VALUE - cost - fee
            assertThat(MAX_VALUE - txSize * 100L - txSize * 20L, Is.is(takerOpenPositionEvent.getBalances().getFree()));
            assertThat(txSize * 100L, Is.is(takerOpenPositionEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(takerOpenPositionEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(takerOpenPositionEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(takerOpenPositionEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.LONG, Is.is(takerOpenPositionEvent.getPositions().getDirection()));
            assertThat(txSize + 0L, Is.is(takerOpenPositionEvent.getPositions().getQuantity()));
            assertThat(10000L * txSize, Is.is(takerOpenPositionEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(takerOpenPositionEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(takerOpenPositionEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(takerOpenPositionEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(takerOpenPositionEvent.getPositions().getLeverage()));
            assertThat(10000L * txSize, Is.is(takerOpenPositionEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(takerOpenPositionEvent.getPositions().getMarkPrice()));
            // openVolume * markPrice - openPriceSum = txSize * 10000 - txSize * 10000 = 0
            assertThat(0L, Is.is(takerOpenPositionEvent.getPositions().getUnrealizedProfit()));
            assertThat(-1L, Is.is(takerOpenPositionEvent.getPositions().getLiquidationPrice()));
            assertThat(0L, Is.is(takerOpenPositionEvent.getPositions().getMarginRatioScaleK()));

            // check unlock_pending event for maker
            IFundEventsHandler.FundEventReport makerUnlockEvent = fundEvents.get(6);
            assertThat(userId1, Is.is(makerUnlockEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.UNLOCK_PENDING, Is.is(makerUnlockEvent.getEventType()));
            assertThat(quoteId, Is.is(makerUnlockEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(makerUnlockEvent.getBalances().getCurrencyScakeK()));
            // free = deposit - (size - txSize) * 100 (remaining margin) - (size - txSize) * 20 (taker fee)
            assertThat(deposit - (size - txSize) * 100L - (size - txSize) * 20L, Is.is(makerUnlockEvent.getBalances().getFree()));
            assertThat((size - txSize) * 100L + (size - txSize) * 20L, Is.is(makerUnlockEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(makerUnlockEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(makerUnlockEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(makerUnlockEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.SHORT, Is.is(makerUnlockEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(makerUnlockEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(makerUnlockEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(makerUnlockEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(makerUnlockEvent.getPositions().getMarkPrice()));
            checkEvent(makerUnlockEvent);

            // check open position event for maker
            IFundEventsHandler.FundEventReport makerOpenPositionEvent = fundEvents.get(7);
            assertThat(userId1, Is.is(makerOpenPositionEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.OPEN_POSITION, Is.is(makerOpenPositionEvent.getEventType()));
            assertThat(quoteId, Is.is(makerOpenPositionEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(makerOpenPositionEvent.getBalances().getCurrencyScakeK()));
            // free = deposit - size * 100 (current position) - (size - txSize) * 20 (taker fee in advance) - txSize * 10 (maker fee)
            assertThat(deposit - size * 100L - (size - txSize) * 20L - txSize * 10L, Is.is(makerOpenPositionEvent.getBalances().getFree()));
            assertThat(size * 100L + (size - txSize) * 20L, Is.is(makerOpenPositionEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(makerOpenPositionEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(makerOpenPositionEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(makerOpenPositionEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.SHORT, Is.is(makerOpenPositionEvent.getPositions().getDirection()));
            assertThat(txSize + 0L, Is.is(makerOpenPositionEvent.getPositions().getQuantity()));
            assertThat(10000L * txSize, Is.is(makerOpenPositionEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(makerOpenPositionEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(makerOpenPositionEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(makerOpenPositionEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(makerOpenPositionEvent.getPositions().getLeverage()));
            assertThat(10000L * txSize, Is.is(makerOpenPositionEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(makerOpenPositionEvent.getPositions().getMarkPrice()));
            // openVolume * markPrice - openPriceSum = txSize * 10000 - txSize * 10000 = 0
            assertThat(0L, Is.is(makerOpenPositionEvent.getPositions().getUnrealizedProfit()));
            assertThat(14915L, Is.is(makerOpenPositionEvent.getPositions().getLiquidationPrice()));
            assertThat(10L, Is.is(makerOpenPositionEvent.getPositions().getMarginRatioScaleK()));
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
            initCurrencies(container);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            // 开仓成功
            container.createBidWithOrderId(makerOrderId1, userId1, 1, 10000, symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(takerOrderId2, userId2, 1, 10000, symbolId, MarginMode.CROSS);

            // 平仓成功
            container.createAskWithOrderId(makerOrderId3, userId1, 1, 10500, symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(takerOrderId4, userId2, 1, 10500, symbolId, MarginMode.CROSS);

            verify(handler, never()).spotExecutionReport(any());
            verify(handler, times(8)).futuresExecutionReport(futuresEventCaptor.capture());

            List<ITradeEventsHandler.FuturesExecutionReport> events = futuresEventCaptor.getAllValues();

            // Check NEW event for maker order (open position)
            ITradeEventsHandler.FuturesExecutionReport event0 = events.get(0);
            assertThat(event0.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
            assertThat(event0.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.NEW));
            assertThat(event0.symbolId, Is.is(2));
            assertThat(event0.orderQtyScale, Is.is(1L));
            assertThat(event0.priceScale, Is.is(1L));
            assertThat(event0.userId, Is.is(userId1));
            assertThat(event0.clOrderId, Is.is(0L));
            assertThat(event0.orderId, Is.is(makerOrderId1));
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

            // Check NEW event for taker order (open position)
            ITradeEventsHandler.FuturesExecutionReport event1 = events.get(1);
            assertThat(event1.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
            assertThat(event1.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.NEW));
            assertThat(event1.symbolId, Is.is(2));
            assertThat(event1.orderQtyScale, Is.is(1L));
            assertThat(event1.priceScale, Is.is(1L));
            assertThat(event1.userId, Is.is(userId2));
            assertThat(event1.clOrderId, Is.is(0L));
            assertThat(event1.orderId, Is.is(takerOrderId2));
            assertThat(event1.orderType, Is.is(GTC));
            assertThat(event1.side, Is.is(ASK));
            assertThat(event1.counterpartyId, Is.is(-1L));
            assertThat(event1.price, Is.is(10000L));
            assertThat(event1.orderQty, Is.is(1L));
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

            // Check TRADE event for taker order (open position)
            ITradeEventsHandler.FuturesExecutionReport event2 = events.get(2);
            assertThat(event2.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
            assertThat(event2.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.FILLED));
            assertThat(event2.symbolId, Is.is(2));
            assertThat(event2.orderQtyScale, Is.is(1L));
            assertThat(event2.priceScale, Is.is(1L));
            assertThat(event2.userId, Is.is(userId2));
            assertThat(event2.clOrderId, Is.is(0L));
            assertThat(event2.orderId, Is.is(takerOrderId2));
            assertThat(event2.orderType, Is.is(GTC));
            assertThat(event2.side, Is.is(ASK));
            assertThat(event2.counterpartyId, Is.is(userId1));
            assertThat(event2.price, Is.is(10000L));
            assertThat(event2.orderQty, Is.is(1L));
            assertThat(event2.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event2.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event2.lastQty, Is.is(1L));
            assertThat(event2.lastPx, Is.is(10000L));
            assertThat(event2.cumQty, Is.is(1L));
            assertThat(event2.cumQuoteQty, Is.is(10000L));
            assertThat(event2.avgPx, Is.is(10000L));
            assertThat(event2.fee, Is.is(0L));
            assertThat(event2.feeAssetId, Is.is(840));
            assertThat(event2.isMaker, Is.is(false));

            // Check TRADE event for maker order (open position)
            ITradeEventsHandler.FuturesExecutionReport event3 = events.get(3);
            assertThat(event3.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
            assertThat(event3.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.FILLED));
            assertThat(event3.symbolId, Is.is(2));
            assertThat(event3.orderQtyScale, Is.is(1L));
            assertThat(event3.priceScale, Is.is(1L));
            assertThat(event3.userId, Is.is(userId1));
            assertThat(event3.clOrderId, Is.is(0L));
            assertThat(event3.orderId, Is.is(makerOrderId1));
            assertThat(event3.orderType, Is.is(GTC));
            assertThat(event3.side, Is.is(BID));
            assertThat(event3.counterpartyId, Is.is(userId2));
            assertThat(event3.price, Is.is(10000L));
            assertThat(event3.orderQty, Is.is(1L));
            assertThat(event3.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event3.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event3.lastQty, Is.is(1L));
            assertThat(event3.lastPx, Is.is(10000L));
            assertThat(event3.cumQty, Is.is(1L));
            assertThat(event3.cumQuoteQty, Is.is(10000L));
            assertThat(event3.avgPx, Is.is(10000L));
            assertThat(event3.fee, Is.is(0L));
            assertThat(event3.feeAssetId, Is.is(840));
            assertThat(event3.isMaker, Is.is(true));

            // Check NEW event for maker order (close position)
            ITradeEventsHandler.FuturesExecutionReport event4 = events.get(4);
            assertThat(event4.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
            assertThat(event4.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.NEW));
            assertThat(event4.symbolId, Is.is(2));
            assertThat(event4.orderQtyScale, Is.is(1L));
            assertThat(event4.priceScale, Is.is(1L));
            assertThat(event4.userId, Is.is(userId1));
            assertThat(event4.clOrderId, Is.is(0L));
            assertThat(event4.orderId, Is.is(makerOrderId3));
            assertThat(event4.orderType, Is.is(GTC));
            assertThat(event4.side, Is.is(ASK));
            assertThat(event4.counterpartyId, Is.is(-1L));
            assertThat(event4.price, Is.is(10500L));
            assertThat(event4.orderQty, Is.is(1L));
            assertThat(event4.execId, Is.is(-1L));
            assertThat(event4.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event4.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event4.lastQty, Is.is(0L));
            assertThat(event4.lastPx, Is.is(0L));
            assertThat(event4.cumQty, Is.is(0L));
            assertThat(event4.cumQuoteQty, Is.is(0L));
            assertThat(event4.avgPx, Is.is(0L));
            assertThat(event4.fee, Is.is(0L));
            assertThat(event4.feeAssetId, Is.is(840));
            assertThat(event4.isMaker, Is.is(false));

            // Check NEW event for taker order (close position)
            ITradeEventsHandler.FuturesExecutionReport event5 = events.get(5);
            assertThat(event5.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
            assertThat(event5.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.NEW));
            assertThat(event5.symbolId, Is.is(2));
            assertThat(event5.orderQtyScale, Is.is(1L));
            assertThat(event5.priceScale, Is.is(1L));
            assertThat(event5.userId, Is.is(userId2));
            assertThat(event5.clOrderId, Is.is(0L));
            assertThat(event5.orderId, Is.is(takerOrderId4));
            assertThat(event5.orderType, Is.is(GTC));
            assertThat(event5.side, Is.is(BID));
            assertThat(event5.counterpartyId, Is.is(-1L));
            assertThat(event5.price, Is.is(10500L));
            assertThat(event5.orderQty, Is.is(1L));
            assertThat(event5.execId, Is.is(-1L));
            assertThat(event5.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event5.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event5.lastQty, Is.is(0L));
            assertThat(event5.lastPx, Is.is(0L));
            assertThat(event5.cumQty, Is.is(0L));
            assertThat(event5.cumQuoteQty, Is.is(0L));
            assertThat(event5.avgPx, Is.is(0L));
            assertThat(event5.fee, Is.is(0L));
            assertThat(event5.feeAssetId, Is.is(840));
            assertThat(event5.isMaker, Is.is(false));

            // Check TRADE event for taker order (close position)
            ITradeEventsHandler.FuturesExecutionReport event6 = events.get(6);
            assertThat(event6.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
            assertThat(event6.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.FILLED));
            assertThat(event6.symbolId, Is.is(2));
            assertThat(event6.orderQtyScale, Is.is(1L));
            assertThat(event6.priceScale, Is.is(1L));
            assertThat(event6.userId, Is.is(userId2));
            assertThat(event6.clOrderId, Is.is(0L));
            assertThat(event6.orderId, Is.is(takerOrderId4));
            assertThat(event6.orderType, Is.is(GTC));
            assertThat(event6.side, Is.is(BID));
            assertThat(event6.counterpartyId, Is.is(userId1));
            assertThat(event6.price, Is.is(10500L));
            assertThat(event6.orderQty, Is.is(1L));
            assertThat(event6.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event6.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event6.lastQty, Is.is(1L));
            assertThat(event6.lastPx, Is.is(10500L));
            assertThat(event6.cumQty, Is.is(1L));
            assertThat(event6.cumQuoteQty, Is.is(10500L));
            assertThat(event6.avgPx, Is.is(10500L));
            assertThat(event6.fee, Is.is(0L));
            assertThat(event6.feeAssetId, Is.is(840));
            assertThat(event6.isMaker, Is.is(false));

            // Check TRADE event for maker order (close position)
            ITradeEventsHandler.FuturesExecutionReport event7 = events.get(7);
            assertThat(event7.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
            assertThat(event7.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.FILLED));
            assertThat(event7.symbolId, Is.is(2));
            assertThat(event7.orderQtyScale, Is.is(1L));
            assertThat(event7.priceScale, Is.is(1L));
            assertThat(event7.userId, Is.is(userId1));
            assertThat(event7.clOrderId, Is.is(0L));
            assertThat(event7.orderId, Is.is(makerOrderId3));
            assertThat(event7.orderType, Is.is(GTC));
            assertThat(event7.side, Is.is(ASK));
            assertThat(event7.counterpartyId, Is.is(userId2));
            assertThat(event7.price, Is.is(10500L));
            assertThat(event7.orderQty, Is.is(1L));
            assertThat(event7.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event7.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event7.lastQty, Is.is(1L));
            assertThat(event7.lastPx, Is.is(10500L));
            assertThat(event7.cumQty, Is.is(1L));
            assertThat(event7.cumQuoteQty, Is.is(10500L));
            assertThat(event7.avgPx, Is.is(10500L));
            assertThat(event7.fee, Is.is(0L));
            assertThat(event7.feeAssetId, Is.is(840));
            assertThat(event7.isMaker, Is.is(true));

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
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();

            // Check CLOSE_POSITION event for taker
            IFundEventsHandler.FundEventReport takerCloseEvent = fundEvents.get(11);
            assertThat(userId2, Is.is(takerCloseEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.CLOSE_POSITION, Is.is(takerCloseEvent.getEventType()));
            assertThat(quoteId, Is.is(takerCloseEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(takerCloseEvent.getBalances().getCurrencyScakeK()));
            // free = MAX_VALUE - 20 (taker fee) - 500 (loss)
            assertThat(3999980L, Is.is(takerCloseEvent.getBalances().getFree()));
            assertThat(0L, Is.is(takerCloseEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(takerCloseEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(takerCloseEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(takerCloseEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.SHORT, Is.is(takerCloseEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(takerCloseEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(takerCloseEvent.getPositions().getOpenPriceSum()));
            assertThat(-500L, Is.is(takerCloseEvent.getPositions().getCumRealized())); // Loss: 10000 - 10500 = -500
            assertThat(false, Is.is(takerCloseEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(takerCloseEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(takerCloseEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(takerCloseEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(takerCloseEvent.getPositions().getMarkPrice()));
            checkEvent(takerCloseEvent);

            // Check CLOSE_POSITION event for maker
            IFundEventsHandler.FundEventReport makerCloseEvent = fundEvents.get(13);
            assertThat(userId1, Is.is(makerCloseEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.CLOSE_POSITION, Is.is(makerCloseEvent.getEventType()));
            assertThat(quoteId, Is.is(makerCloseEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(makerCloseEvent.getBalances().getCurrencyScakeK()));
            assertThat(deposit - 10 * 1L, Is.is(makerCloseEvent.getBalances().getFree()));
            assertThat(0L, Is.is(makerCloseEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(makerCloseEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(makerCloseEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(makerCloseEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.LONG, Is.is(makerCloseEvent.getPositions().getDirection()));
            assertThat(0L, Is.is(makerCloseEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(makerCloseEvent.getPositions().getOpenPriceSum()));
            assertThat(500L, Is.is(makerCloseEvent.getPositions().getCumRealized())); // Profit: 10500 - 10000 = 500
            assertThat(false, Is.is(makerCloseEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(makerCloseEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(makerCloseEvent.getPositions().getLeverage()));
            assertThat(0L, Is.is(makerCloseEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(makerCloseEvent.getPositions().getMarkPrice()));
            checkEvent(makerCloseEvent);
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
            initCurrencies(container);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            // 开仓成功
            container.createBidWithOrderId(makerOrderId1, userId1, 10, 10000, symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(takerOrderId2, userId2, 10, 10000, symbolId, MarginMode.CROSS);

            // 平仓一手成功
            container.createAskWithOrderId(makerOrderId3, userId1, 2, 10500, symbolId, MarginMode.CROSS);
            container.createBidWithOrderId(takerOrderId4, userId2, 1, 10500, symbolId, MarginMode.CROSS);

            verify(handler, never()).spotExecutionReport(any());
            verify(handler, times(8)).futuresExecutionReport(futuresEventCaptor.capture());

            List<ITradeEventsHandler.FuturesExecutionReport> events = futuresEventCaptor.getAllValues();

            // Check NEW event for maker order (open position)
            ITradeEventsHandler.FuturesExecutionReport event0 = events.get(0);
            assertThat(event0.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
            assertThat(event0.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.NEW));
            assertThat(event0.symbolId, Is.is(2));
            assertThat(event0.orderQtyScale, Is.is(1L));
            assertThat(event0.priceScale, Is.is(1L));
            assertThat(event0.userId, Is.is(userId1));
            assertThat(event0.clOrderId, Is.is(0L));
            assertThat(event0.orderId, Is.is(makerOrderId1));
            assertThat(event0.orderType, Is.is(GTC));
            assertThat(event0.side, Is.is(BID));
            assertThat(event0.counterpartyId, Is.is(-1L));
            assertThat(event0.price, Is.is(10000L));
            assertThat(event0.orderQty, Is.is(10L));
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

            // Check NEW event for taker order (open position)
            ITradeEventsHandler.FuturesExecutionReport event1 = events.get(1);
            assertThat(event1.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
            assertThat(event1.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.NEW));
            assertThat(event1.symbolId, Is.is(2));
            assertThat(event1.orderQtyScale, Is.is(1L));
            assertThat(event1.priceScale, Is.is(1L));
            assertThat(event1.userId, Is.is(userId2));
            assertThat(event1.clOrderId, Is.is(0L));
            assertThat(event1.orderId, Is.is(takerOrderId2));
            assertThat(event1.orderType, Is.is(GTC));
            assertThat(event1.side, Is.is(ASK));
            assertThat(event1.counterpartyId, Is.is(-1L));
            assertThat(event1.price, Is.is(10000L));
            assertThat(event1.orderQty, Is.is(10L));
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

            // Check TRADE event for taker order (open position)
            ITradeEventsHandler.FuturesExecutionReport event2 = events.get(2);
            assertThat(event2.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
            assertThat(event2.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.FILLED));
            assertThat(event2.symbolId, Is.is(2));
            assertThat(event2.orderQtyScale, Is.is(1L));
            assertThat(event2.priceScale, Is.is(1L));
            assertThat(event2.userId, Is.is(userId2));
            assertThat(event2.clOrderId, Is.is(0L));
            assertThat(event2.orderId, Is.is(takerOrderId2));
            assertThat(event2.orderType, Is.is(GTC));
            assertThat(event2.side, Is.is(ASK));
            assertThat(event2.counterpartyId, Is.is(userId1));
            assertThat(event2.price, Is.is(10000L));
            assertThat(event2.orderQty, Is.is(10L));
            assertThat(event2.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event2.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event2.lastQty, Is.is(10L));
            assertThat(event2.lastPx, Is.is(10000L));
            assertThat(event2.cumQty, Is.is(10L));
            assertThat(event2.cumQuoteQty, Is.is(100000L));
            assertThat(event2.avgPx, Is.is(10000L));
            assertThat(event2.fee, Is.is(0L));
            assertThat(event2.feeAssetId, Is.is(840));
            assertThat(event2.isMaker, Is.is(false));

            // Check TRADE event for maker order (open position)
            ITradeEventsHandler.FuturesExecutionReport event3 = events.get(3);
            assertThat(event3.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
            assertThat(event3.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.FILLED));
            assertThat(event3.symbolId, Is.is(2));
            assertThat(event3.orderQtyScale, Is.is(1L));
            assertThat(event3.priceScale, Is.is(1L));
            assertThat(event3.userId, Is.is(userId1));
            assertThat(event3.clOrderId, Is.is(0L));
            assertThat(event3.orderId, Is.is(makerOrderId1));
            assertThat(event3.orderType, Is.is(GTC));
            assertThat(event3.side, Is.is(BID));
            assertThat(event3.counterpartyId, Is.is(userId2));
            assertThat(event3.price, Is.is(10000L));
            assertThat(event3.orderQty, Is.is(10L));
            assertThat(event3.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event3.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event3.lastQty, Is.is(10L));
            assertThat(event3.lastPx, Is.is(10000L));
            assertThat(event3.cumQty, Is.is(10L));
            assertThat(event3.cumQuoteQty, Is.is(100000L));
            assertThat(event3.avgPx, Is.is(10000L));
            assertThat(event3.fee, Is.is(0L));
            assertThat(event3.feeAssetId, Is.is(840));
            assertThat(event3.isMaker, Is.is(true));

            // Check NEW event for maker order (close position)
            ITradeEventsHandler.FuturesExecutionReport event4 = events.get(4);
            assertThat(event4.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
            assertThat(event4.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.NEW));
            assertThat(event4.symbolId, Is.is(2));
            assertThat(event4.orderQtyScale, Is.is(1L));
            assertThat(event4.priceScale, Is.is(1L));
            assertThat(event4.userId, Is.is(userId1));
            assertThat(event4.clOrderId, Is.is(0L));
            assertThat(event4.orderId, Is.is(makerOrderId3));
            assertThat(event4.orderType, Is.is(GTC));
            assertThat(event4.side, Is.is(ASK));
            assertThat(event4.counterpartyId, Is.is(-1L));
            assertThat(event4.price, Is.is(10500L));
            assertThat(event4.orderQty, Is.is(2L));
            assertThat(event4.execId, Is.is(-1L));
            assertThat(event4.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event4.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event4.lastQty, Is.is(0L));
            assertThat(event4.lastPx, Is.is(0L));
            assertThat(event4.cumQty, Is.is(0L));
            assertThat(event4.cumQuoteQty, Is.is(0L));
            assertThat(event4.avgPx, Is.is(0L));
            assertThat(event4.fee, Is.is(0L));
            assertThat(event4.feeAssetId, Is.is(840));
            assertThat(event4.isMaker, Is.is(false));

            // Check NEW event for taker order (close position)
            ITradeEventsHandler.FuturesExecutionReport event5 = events.get(5);
            assertThat(event5.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
            assertThat(event5.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.NEW));
            assertThat(event5.symbolId, Is.is(2));
            assertThat(event5.orderQtyScale, Is.is(1L));
            assertThat(event5.priceScale, Is.is(1L));
            assertThat(event5.userId, Is.is(userId2));
            assertThat(event5.clOrderId, Is.is(0L));
            assertThat(event5.orderId, Is.is(takerOrderId4));
            assertThat(event5.orderType, Is.is(GTC));
            assertThat(event5.side, Is.is(BID));
            assertThat(event5.counterpartyId, Is.is(-1L));
            assertThat(event5.price, Is.is(10500L));
            assertThat(event5.orderQty, Is.is(1L));
            assertThat(event5.execId, Is.is(-1L));
            assertThat(event5.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event5.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event5.lastQty, Is.is(0L));
            assertThat(event5.lastPx, Is.is(0L));
            assertThat(event5.cumQty, Is.is(0L));
            assertThat(event5.cumQuoteQty, Is.is(0L));
            assertThat(event5.avgPx, Is.is(0L));
            assertThat(event5.fee, Is.is(0L));
            assertThat(event5.feeAssetId, Is.is(840));
            assertThat(event5.isMaker, Is.is(false));

            // Check TRADE event for taker order (close position)
            ITradeEventsHandler.FuturesExecutionReport event6 = events.get(6);
            assertThat(event6.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
            assertThat(event6.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.FILLED));
            assertThat(event6.symbolId, Is.is(2));
            assertThat(event6.orderQtyScale, Is.is(1L));
            assertThat(event6.priceScale, Is.is(1L));
            assertThat(event6.userId, Is.is(userId2));
            assertThat(event6.clOrderId, Is.is(0L));
            assertThat(event6.orderId, Is.is(takerOrderId4));
            assertThat(event6.orderType, Is.is(GTC));
            assertThat(event6.side, Is.is(BID));
            assertThat(event6.counterpartyId, Is.is(userId1));
            assertThat(event6.price, Is.is(10500L));
            assertThat(event6.orderQty, Is.is(1L));
            assertThat(event6.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event6.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event6.lastQty, Is.is(1L));
            assertThat(event6.lastPx, Is.is(10500L));
            assertThat(event6.cumQty, Is.is(1L));
            assertThat(event6.cumQuoteQty, Is.is(10500L));
            assertThat(event6.avgPx, Is.is(10500L));
            assertThat(event6.fee, Is.is(0L));
            assertThat(event6.feeAssetId, Is.is(840));
            assertThat(event6.isMaker, Is.is(false));

            // Check TRADE event for maker order (close position)
            ITradeEventsHandler.FuturesExecutionReport event7 = events.get(7);
            assertThat(event7.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
            assertThat(event7.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.PARTIALLY_FILLED));
            assertThat(event7.symbolId, Is.is(2));
            assertThat(event7.orderQtyScale, Is.is(1L));
            assertThat(event7.priceScale, Is.is(1L));
            assertThat(event7.userId, Is.is(userId1));
            assertThat(event7.clOrderId, Is.is(0L));
            assertThat(event7.orderId, Is.is(makerOrderId3));
            assertThat(event7.orderType, Is.is(GTC));
            assertThat(event7.side, Is.is(ASK));
            assertThat(event7.counterpartyId, Is.is(userId2));
            assertThat(event7.price, Is.is(10500L));
            assertThat(event7.orderQty, Is.is(2L));
            assertThat(event7.contractType, Is.is(SymbolType.FUTURES_CONTRACT_PERPETUAL));
            assertThat(event7.positionSide, Is.is(PositionMode.ONEWAY));
            assertThat(event7.lastQty, Is.is(1L));
            assertThat(event7.lastPx, Is.is(10500L));
            assertThat(event7.cumQty, Is.is(1L));
            assertThat(event7.cumQuoteQty, Is.is(10500L));
            assertThat(event7.avgPx, Is.is(10500L));
            assertThat(event7.fee, Is.is(0L));
            assertThat(event7.feeAssetId, Is.is(840));
            assertThat(event7.isMaker, Is.is(true));
//            assertThat(event7.bidsNotional, Is.is(100000L));
//            assertThat(event7.asksNotional, Is.is(21000L));
//            assertThat(event7.bidsQty, Is.is(10L));
//            assertThat(event7.asksQty, Is.is(2L));

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
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();

            // Check CLOSE_POSITION event for taker
            IFundEventsHandler.FundEventReport takerCloseEvent = fundEvents.get(11);
            assertThat(userId2, Is.is(takerCloseEvent.getAccountId()));
            assertThat(FundEvent.FundEventType.CLOSE_POSITION, Is.is(takerCloseEvent.getEventType()));
            assertThat(quoteId, Is.is(takerCloseEvent.getBalances().getCurrency()));
            assertThat(1L, Is.is(takerCloseEvent.getBalances().getCurrencyScakeK()));
            assertThat(3998900L, Is.is(takerCloseEvent.getBalances().getFree()));
            assertThat(900L, Is.is(takerCloseEvent.getBalances().getLocked()));
            // position check
            assertThat(symbolId, Is.is(takerCloseEvent.getPositions().getSymbolId()));
            assertThat(1L, Is.is(takerCloseEvent.getPositions().getBaseScaleK()));
            assertThat(1L, Is.is(takerCloseEvent.getPositions().getQuoteScaleK()));
            assertThat(PositionDirection.SHORT, Is.is(takerCloseEvent.getPositions().getDirection()));
            assertThat(9L, Is.is(takerCloseEvent.getPositions().getQuantity()));
            assertThat(89500L, Is.is(takerCloseEvent.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(takerCloseEvent.getPositions().getCumRealized()));
            assertThat(false, Is.is(takerCloseEvent.getPositions().isIsolated()));
            assertThat(0L, Is.is(takerCloseEvent.getPositions().getIsolatedWallet()));
            assertThat(1, Is.is(takerCloseEvent.getPositions().getLeverage()));
            assertThat(89500L, Is.is(takerCloseEvent.getPositions().getOpenPriceSum()));
            assertThat(10000L, Is.is(takerCloseEvent.getPositions().getMarkPrice()));
            // openVolume * markPrice - openPriceSum = 9 * 10000 - 90000 = 0
            assertThat(-500L, Is.is(takerCloseEvent.getPositions().getUnrealizedProfit()));
            // maintenanceMargin = 0.5% * 9 * 10000 = 450
            // liquidationPrice = (-1 * (450 - 900) + 90000) / 9 = 9994.4 ≈ 9994
            assertThat(452106L, Is.is(takerCloseEvent.getPositions().getLiquidationPrice()));
            // marginRatioScaleK = 1000 * 450 / MAX_VALUE ≈ 0
            assertThat(0L, Is.is(takerCloseEvent.getPositions().getMarginRatioScaleK()));
        }
    }

    @Test
    public void testGlobalBalance() {
        final int symbolId = SYMBOL_MARGIN;

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration())) {
            container.initFeeSymbols();
            container.initFeeSymbolsMarkPrice();
            container.initFeeUsers();


            container.submitCommandSync(builderPlace(symbolId, UID_1, ASK, GTC).orderId(101L).price(160000L).size(7L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_2, ASK, GTC).orderId(202L).price(159900L).size(10L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_3, ASK, GTC).orderId(303L).price(160000L).size(3L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_3, ASK, GTC).orderId(304L).price(160500L).size(20L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);

            long price = 160500L;
            int size = 20;

            container.submitCommandSync(builderPlace(symbolId, UID_4, BID, IOC).orderId(405L).price(price).reservePrice(price).size(size).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);

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

            container.submitCommandSync(builderPlace(symbolId, UID_1, ASK, GTC).orderId(101L).price(160000L).size(7L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_2, ASK, GTC).orderId(202L).price(159900L).size(10L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_3, ASK, GTC).orderId(303L).price(160000L).size(3L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_3, ASK, GTC).orderId(304L).price(160500L).size(20L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);

            long price = 160500L;
            if (orderType == FOK_BUDGET) {
                price = 160000L * 7L + 159900L * 10L + 160000L * 3L + 160500L * 20L + (rejectionCause == RejectionCause.REJECTION_BY_BUDGET ? -1 : 0);
            }

            container.submitCommandSync(builderPlace(symbolId, UID_4, BID, orderType).orderId(405L).price(price).reservePrice(price).size(size).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
        }

        doCheckEvtCnt(symbolSpec, orderType, rejectionCause);
    }

    @Override
    public void testMultiSell(final CoreSymbolSpecification symbolSpec, final OrderType orderType, final RejectionCause rejectionCause) {

        final int symbolId = symbolSpec.symbolId;

        final long size = 22L + (rejectionCause == RejectionCause.REJECTION_BY_SIZE ? 1 : 0);

        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor)) {

            doInit(container);


            long price = 159_900L;
            if (orderType == FOK_BUDGET) {
                price = 160_500L + 160_000L * 20L + 159_900L + (rejectionCause == RejectionCause.REJECTION_BY_BUDGET ? 1 : 0);
            }

            container.submitCommandSync(builderPlace(symbolId, UID_1, BID, GTC).orderId(101L).price(160_000L).reservePrice(166_000L).size(12L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_2, BID, GTC).orderId(202L).price(159_900L).reservePrice(166_000L).size(1L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_3, BID, GTC).orderId(303L).price(160_000L).reservePrice(166_000L).size(8L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);
            container.submitCommandSync(builderPlace(symbolId, UID_3, BID, GTC).orderId(304L).price(160_500L).reservePrice(166_000L).size(1L).marginMode(MarginMode.CROSS).build(), CommandResultCode.SUCCESS);

            container.submitCommandSync(builderPlace(symbolId, UID_4, ASK, orderType).orderId(405L).price(price).size(size).build(), CommandResultCode.SUCCESS);

            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
        }

        doCheckEvtCnt(symbolSpec, orderType, rejectionCause);
    }

}