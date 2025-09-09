package exchange.core2.tests.integration;

import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.processors.LiquidationEngine;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.*;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static exchange.core2.core.common.OrderAction.BID;
import static exchange.core2.core.common.OrderType.*;
import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ITExtraMarginIntegration {
    private int symbolId = 2;
    private int quoteId = 840;

    private SimpleEventsProcessor4Test processor;

    @Captor
    ArgumentCaptor<IFundEventsHandler.FundEventReport> fundEventCaptor;

    @Mock
    private IEventsHandler4Test handler;

    @BeforeEach
    public void before() {
        processor = new SimpleEventsProcessor4Test(handler);
    }

    @AfterEach()
    public void after() {
    }

    private void checkEvent(IFundEventsHandler.FundEventReport evt) {
        assertThat(0L, Is.is(evt.getPositions().getUnrealizedProfit()));
        assertThat(0L, Is.is(evt.getPositions().getLiquidationPrice()));
        assertThat(0L, Is.is(evt.getPositions().getMarginRatioScaleK()));
    }

    private void checkEventPending(IFundEventsHandler.FundEventReport evt) {
        assertThat(0L, Is.is(evt.getPositions().getBidsNotional()));
        assertThat(0L, Is.is(evt.getPositions().getAsksNotional()));
        assertThat(0L, Is.is(evt.getPositions().getBidsQty()));
        assertThat(0L, Is.is(evt.getPositions().getAsksQty()));
    }

    private PerformanceConfiguration getPerformanceConfiguration() {
        return PerformanceConfiguration.baseBuilder().build();
    }

    // inactive用户不允许增加extraMargin
    @Test
    public void testInactiveUser() {
        long deposit = 10000L;
        long userId1 = UID_1;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();

            container.createUserWithSpecificMoney(userId1, 0, quoteId);

            // suspend user
            ApiSuspendUser suspend = ApiSuspendUser.builder().uid(userId1).build();
            container.submitCommandSync(suspend, CommandResultCode.SUCCESS);

            ApiAdjustMargin adjustMargin = ApiAdjustMargin.builder().transactionId(1345L).symbol(symbolId).uid(userId1).amount(deposit).currency(quoteId).marginMode(MarginMode.CROSS).build();
            container.submitCommandSync(adjustMargin, CommandResultCode.AUTH_INVALID_USER);

            container.validateUserState(userId1, profile -> {
                assertThat("inactive user account should be null", profile.getAccounts() == null);
            });

            // resume user
            ApiResumeUser resume = ApiResumeUser.builder().uid(userId1).build();
            container.submitCommandSync(resume, CommandResultCode.SUCCESS);

            container.submitCommandSync(adjustMargin, CommandResultCode.SUCCESS);

            container.validateUserState(userId1, profile -> {
                assertThat("active user profile should not be null", profile.getAccounts() != null);
                assertThat(profile.getAccounts().get(quoteId), is(deposit));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // 1. 用户增加保证金后extraMargin应相应增加, 包括事件发送正确 -- cross margin
    @Test
    public void testExtraMarin4Cross() {
        long deposit1 = 2000L;
        long deposit2 = 1000L;
        long userId1 = UID_1;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.initFutureSymbol(symbolId, quoteId);
            container.addCurrency(1);
            container.addCurrency(quoteId);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit1, quoteId);

            // cross直接加到balance上
            ApiAdjustMargin cmd = ApiAdjustMargin.builder().transactionId(10001L).symbol(symbolId)
                    .uid(userId1).amount(deposit2).currency(quoteId)
                    .marginMode(MarginMode.CROSS)
                    .build();

            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit1 + deposit2));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(1)).fundEventReport(fundEventCaptor.capture());
            // check fund event
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport event1 = fundEvents.get(0);

            assertThat(userId1, Is.is(event1.getAccountId()));
            assertThat(quoteId, Is.is(event1.getBalances().getCurrency()));
            assertThat(0, Is.is(event1.getPositions().getSymbolId()));
            assertThat(PositionDirection.EMPTY, Is.is(event1.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.DEPOSIT, Is.is(event1.getEventType()));
            assertThat(2000L, Is.is(event1.getBalances().getFree()));
            assertThat(0L, Is.is(event1.getPositions().getCumRealized()));
            assertThat(0L, Is.is(event1.getBalances().getLocked()));
            assertThat(0L, Is.is(event1.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(event1.getPositions().getQuantity()));
            checkEvent(event1);
            checkEventPending(event1);
        }
    }

    // 1. 用户增加保证金后extraMargin应相应增加, 包括事件发送正确 -- cross margin
    @Test
    public void testExtraMarin4Isolated() {
        long deposit1 = 2000L;
        long deposit2 = 1000L;
        long userId1 = UID_1;
        long size = 1;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.initFutureSymbol(symbolId, quoteId);
            container.addCurrency(BASE_CURRENCY_ID, 0);
            container.addCurrency(quoteId, 0);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit1, quoteId);

            // isolated需要加到相应position上
            ApiAdjustMargin cmd = ApiAdjustMargin.builder().transactionId(10001L).symbol(symbolId)
                    .uid(userId1).amount(deposit2).currency(quoteId)
                    .marginMode(MarginMode.ISOLATED)
                    .build();
            // 仓位不存在时不允许加保证金
            container.submitCommandSync(cmd, CommandResultCode.RISK_MARGIN_POSITION_NOT_EXISTS);

            ApiPlaceOrder order1 = ApiPlaceOrder.builder()
                    .uid(userId1)
                    .orderId(container.getRandomTransactionId())
                    .action(BID)
                    .size(size)
                    .price(10000L)
                    .symbol(symbolId)
                    .orderType(GTC)
                    .marginMode(MarginMode.CROSS)
                    .build();
            container.submitCommandSync(order1, CommandResultCode.SUCCESS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });
            // order1类型为cross, 此时增加isolated保证金会报mismatch的错误
            container.submitCommandSync(cmd, CommandResultCode.RISK_MARGIN_MODE_MISMATCH);

            container.cancelOrder(userId1, order1.orderId, symbolId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(0));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            ApiPlaceOrder order2 = ApiPlaceOrder.builder()
                    .uid(userId1)
                    .orderId(container.getRandomTransactionId())
                    .action(BID)
                    .size(size)
                    .price(10000L)
                    .symbol(symbolId)
                    .orderType(GTC)
                    .marginMode(MarginMode.ISOLATED)
                    .build();
            container.submitCommandSync(order2, CommandResultCode.SUCCESS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
            });
            // 1. postion存在 2. position类型匹配 此时增加保证金成功
            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().getFirst().get(0).extraMargin, is(deposit2));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(5)).fundEventReport(fundEventCaptor.capture());
            // check fund event
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport event1 = fundEvents.get(4);

            assertThat(userId1, Is.is(event1.getAccountId()));
            assertThat(quoteId, Is.is(event1.getBalances().getCurrency()));
            assertThat(symbolId, Is.is(event1.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(event1.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.MARGIN_ADJUST, Is.is(event1.getEventType()));
            assertThat(880L, Is.is(event1.getBalances().getFree()));
            assertThat(120L, Is.is(event1.getBalances().getLocked()));
            assertThat(0L, Is.is(event1.getPositions().getCumRealized()));
            assertThat(0L, Is.is(event1.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(event1.getPositions().getQuantity()));
            assertThat(10000L, Is.is(event1.getPositions().getBidsNotional()));
            assertThat(0L, Is.is(event1.getPositions().getAsksNotional()));
            assertThat(1L, Is.is(event1.getPositions().getBidsQty()));
            assertThat(0L, Is.is(event1.getPositions().getAsksQty()));
            checkEvent(event1);
        }
    }

    // 平仓后extraMargin可提取, 包括事件发送准确
    @Test
    public void testIsolatedClosePosition() {
        int deposit = 1000;
        long deposit2 = 500L;
        long fee = 10;
        long userId1 = UID_1;
        long userId2 = UID_2;
        long price1 = 10000;
        long price2 = 10500;
        long makerOrderId1 = 1005L;
        long takerOrderId2 = 1006L;
        long makerOrderId3 = 1007L;
        long takerOrderId4 = 1008L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.addCurrency(BASE_CURRENCY_ID, 0);
            container.addCurrency(quoteId, 0);
            container.initFutureSymbol(symbolId, quoteId);
            container.initMarkPrice(symbolId, 10000);
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);

            // 开仓成功
            container.createBidWithOrderId(makerOrderId1, userId1, 1, 10000, symbolId);
            container.createAskWithOrderId(takerOrderId2, userId2, 1, 10000, symbolId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().getFirst().get(0).extraMargin, is(0L));
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            ApiAdjustMargin cmd = ApiAdjustMargin.builder().transactionId(10001L).symbol(symbolId)
                    .uid(userId1).amount(deposit2).currency(quoteId)
                    .marginMode(MarginMode.ISOLATED)
                    .build();
            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().getFirst().get(0).extraMargin, is(deposit2));
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee - deposit2));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            // 平仓成功
            container.createAskWithOrderId(makerOrderId3, userId1, 1, 10500, symbolId);
            container.createBidWithOrderId(takerOrderId4, userId2, 1, 10500, symbolId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(0));
                assertThat(profile.getAccounts().get(quoteId), is(deposit + deposit2 + price2 - price1 - fee - deposit2));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(16)).fundEventReport(fundEventCaptor.capture());
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();

            IFundEventsHandler.FundEventReport marginAdjust = fundEvents.get(8);
            assertThat(userId1, Is.is(marginAdjust.getAccountId()));
            assertThat(quoteId, Is.is(marginAdjust.getBalances().getCurrency()));
            assertThat(symbolId, Is.is(marginAdjust.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(marginAdjust.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.MARGIN_ADJUST, Is.is(marginAdjust.getEventType()));
            assertThat(390L, Is.is(marginAdjust.getBalances().getFree()));
            assertThat(100L, Is.is(marginAdjust.getBalances().getLocked()));
            assertThat(0L, Is.is(marginAdjust.getPositions().getCumRealized()));
            assertThat(10000L, Is.is(marginAdjust.getPositions().getOpenPriceSum()));
            assertThat(1L, Is.is(marginAdjust.getPositions().getQuantity()));
            assertThat(0L, Is.is(marginAdjust.getPositions().getBidsNotional()));
            assertThat(0L, Is.is(marginAdjust.getPositions().getAsksNotional()));
            assertThat(0L, Is.is(marginAdjust.getPositions().getBidsQty()));
            assertThat(0L, Is.is(marginAdjust.getPositions().getAsksQty()));
            // 标记价格没变, openVolume * markPrice - openPriceSum = 10000 - 10000 = 0
            assertThat(0L, Is.is(marginAdjust.getPositions().getUnrealizedProfit()));
            // maintenanceMargin = 50
            // totalMargin = openInitMarginSum + extraMargin = 100 + 500 = 600
            // liquidationPrice = direction * (maintenanceMargin - totalMargin) + openPriceSum = 1 * (50 - 600) + 10000 * 1 = 9450L
            assertThat(9450L, Is.is(marginAdjust.getPositions().getLiquidationPrice()));
            // marginRatioScaleK = maintenanceMarginScaleK * maintenanceMargin / totalMargin = long (1000 * 50 / 600) = long(83.3) = 83
            assertThat(83L, Is.is(marginAdjust.getPositions().getMarginRatioScaleK()));

            IFundEventsHandler.FundEventReport marginRefund = fundEvents.get(15);
            assertThat(userId1, Is.is(marginRefund.getAccountId()));
            assertThat(quoteId, Is.is(marginRefund.getBalances().getCurrency()));
            assertThat(symbolId, Is.is(marginRefund.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(marginRefund.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.MARGIN_REFUND, Is.is(marginRefund.getEventType()));
            assertThat(deposit - fee + price2 - price1 - deposit2, Is.is(marginRefund.getBalances().getFree()));
            assertThat(0L, Is.is(marginRefund.getBalances().getLocked()));
            assertThat(500L, Is.is(marginRefund.getPositions().getCumRealized()));
            assertThat(0L, Is.is(marginRefund.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(marginRefund.getPositions().getQuantity()));
            // 仓位平完后, openVolume为0, 所以estimateUnrealizedProfit, estimateLiquidationPrice, marginRatioScaleK均为0
            checkEvent(marginRefund);
            checkEventPending(marginRefund);
        }
    }

    // 每个仓位维护自己的extraMargin, 且相互独立
    @Test
    public void tesMultipleExtraMargin() {
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
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(userId3, MAX_VALUE, quoteId);

            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId);
            container.createAskWithOrderId(makerOrderId3, userId1, size, price2, symbols.get(1).symbolId);
            // userId1开出来BTCUSDT, ETHUSDT isolated单
            container.createAskWithOrderId(takerOrderId2, userId2, size, price1, symbols.get(0).symbolId);
            container.createBidWithOrderId(takerOrderId4, userId2, size, price2, symbols.get(1).symbolId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).extraMargin, is(0L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).direction, is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(symbols.get(1).symbolId).get(0).extraMargin, is(0L));
                assertThat(profile.getPositions().get(symbols.get(1).symbolId).get(0).direction, is(PositionDirection.SHORT));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            container.placeExtraMargin(userId1, symbols.get(0).quoteCurrency, symbols.get(0).symbolId, 200L, MarginMode.ISOLATED);
            container.placeExtraMargin(userId1, symbols.get(1).quoteCurrency, symbols.get(1).symbolId, 300L, MarginMode.ISOLATED);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).extraMargin, is(200L));
                assertThat(profile.getPositions().get(symbols.get(1).symbolId).get(0).extraMargin, is(300L));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            container.placeExtraMargin(userId1, symbols.get(0).quoteCurrency, symbols.get(0).symbolId, 300L, MarginMode.ISOLATED);
            container.placeExtraMargin(userId1, symbols.get(1).quoteCurrency, symbols.get(1).symbolId, 200L, MarginMode.ISOLATED);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).extraMargin, is(500L));
                assertThat(profile.getPositions().get(symbols.get(1).symbolId).get(0).extraMargin, is(500L));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // Isolated
    // 当价格波动时, 计算1.2 * maitainance margin时要将extraMargin算进来,
    // 第一次如果没有增加保证金会发margin_alert事件
    // 第二次加了足够的保证金不会再触发margin_alert事件
    @Test
    public void testIsolatedMarginLiquidationWarning() {
        long deposit = 10000L;
        long userId1 = UID_1;
        long userId2 = UID_2;
        long userId3 = UID_3;
        int size = 1;
        long price1 = 10000;
        long makerOrderId1 = 1005L;
        long takerOrderId1 = 1006L;
        long fee = 10;
        long extraMarginDeposit = 10L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));

            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(userId3, deposit, quoteId);

            // userId1 and userId2 match
            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId);

            container.createAskWithOrderId(takerOrderId1, userId2, size, price1, symbols.get(0).symbolId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            // 价格降到时, userId3因为是isolated开始触发强平, 但是此时userId1因为开的是cross margin(symbol1做多)所以没达到强平
            // userId1 symbol0: 10000 - 9950 = 50
            //         profit + balance = -50 + 100 = 50 < 60(50 * 1.2)此时会触发alert

            container.updateCurrentPriceTo(9950, symbols.get(0).symbolId, quoteId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).extraMargin, is(0L));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            container.placeExtraMargin(userId1, quoteId, symbols.get(0).symbolId, extraMarginDeposit, MarginMode.ISOLATED);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee - extraMarginDeposit));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).extraMargin, is(extraMarginDeposit));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee - extraMarginDeposit));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).extraMargin, is(extraMarginDeposit));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(19)).fundEventReport(fundEventCaptor.capture());
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport alertEvent = fundEvents.get(17);
            assertThat(userId1, Is.is(alertEvent.getAccountId()));
            assertThat(quoteId, Is.is(alertEvent.getBalances().getCurrency()));
            assertThat(10000, Is.is(alertEvent.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(alertEvent.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.MARGIN_ALERT, Is.is(alertEvent.getEventType()));
            assertThat(0L, Is.is(alertEvent.getBalances().getFree()));
            assertThat(0L, Is.is(alertEvent.getBalances().getLocked()));
            assertThat(0L, Is.is(alertEvent.getPositions().getCumRealized()));
            assertThat(10000L, Is.is(alertEvent.getPositions().getOpenPriceSum()));
            assertThat(1L, Is.is(alertEvent.getPositions().getQuantity()));
            assertThat(0L, Is.is(alertEvent.getPositions().getBidsNotional()));
            assertThat(0L, Is.is(alertEvent.getPositions().getAsksNotional()));
            assertThat(0L, Is.is(alertEvent.getPositions().getBidsQty()));
            assertThat(0L, Is.is(alertEvent.getPositions().getAsksQty()));
            assertThat(-50L, Is.is(alertEvent.getPositions().getUnrealizedProfit()));
            assertThat(9949L, Is.is(alertEvent.getPositions().getLiquidationPrice()));
            assertThat(980L, Is.is(alertEvent.getPositions().getMarginRatioScaleK()));

            IFundEventsHandler.FundEventReport marginAdjust = fundEvents.get(18);
            assertThat(userId1, Is.is(marginAdjust.getAccountId()));
            assertThat(quoteId, Is.is(marginAdjust.getBalances().getCurrency()));
            assertThat(10000, Is.is(marginAdjust.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(marginAdjust.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.MARGIN_ADJUST, Is.is(marginAdjust.getEventType()));
            assertThat(deposit - 100 - fee - extraMarginDeposit, Is.is(marginAdjust.getBalances().getFree()));
            assertThat(100L, Is.is(marginAdjust.getBalances().getLocked()));
            assertThat(0L, Is.is(marginAdjust.getPositions().getCumRealized()));
            assertThat(10000L, Is.is(marginAdjust.getPositions().getOpenPriceSum()));
            assertThat(1L, Is.is(marginAdjust.getPositions().getQuantity()));
            assertThat(-50L, Is.is(marginAdjust.getPositions().getUnrealizedProfit()));
            assertThat(9939L, Is.is(marginAdjust.getPositions().getLiquidationPrice()));
            assertThat(816L, Is.is(marginAdjust.getPositions().getMarginRatioScaleK()));
            checkEventPending(marginAdjust);
        }
    }

    // Isolated
    // 当价格波动时, 计算1.2 * maitainance margin时要将extraMargin算进来,
    // 第一次如果没有增加保证金会发margin_alert事件
    // 第二次加了保证金，但是不够1.2, 此时还是会再触发margin_alert事件
    @Test
    public void testIsolatedMarginLiquidationWarning2() {
        long deposit = 10000L;
        long userId1 = UID_1;
        long userId2 = UID_2;
        long userId3 = UID_3;
        int size = 1;
        long price1 = 10000;
        long makerOrderId1 = 1005L;
        long takerOrderId1 = 1006L;
        long fee = 10;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(userId3, deposit, quoteId);

            // userId1 and userId2 match
            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId);

            container.createAskWithOrderId(takerOrderId1, userId2, size, price1, symbols.get(0).symbolId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            // 价格降到时, userId3因为是isolated开始触发强平, 但是此时userId1因为开的是cross margin(symbol1做多)所以没达到强平
            // userId1 symbol0: 10000 - 9950 = 50
            //         profit + initMargin  = -50 + 100 = 50 < 58(49 * 1.2)此时会触发alert

            container.updateCurrentPriceTo(9950, symbols.get(0).symbolId, quoteId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).extraMargin, is(0L));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            long marginDeposit = 7;
            container.placeExtraMargin(userId1, quoteId, symbols.get(0).symbolId, marginDeposit, MarginMode.ISOLATED);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee - marginDeposit));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).extraMargin, is(marginDeposit));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee - marginDeposit));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).extraMargin, is(marginDeposit));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(20)).fundEventReport(fundEventCaptor.capture());
            // check fund event
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport alertEvent = fundEvents.get(17);

            assertThat(userId1, Is.is(alertEvent.getAccountId()));
            assertThat(quoteId, Is.is(alertEvent.getBalances().getCurrency()));
            assertThat(10000, Is.is(alertEvent.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(alertEvent.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.MARGIN_ALERT, Is.is(alertEvent.getEventType()));
            assertThat(0L, Is.is(alertEvent.getBalances().getFree()));
            assertThat(0L, Is.is(alertEvent.getBalances().getLocked()));
            assertThat(0L, Is.is(alertEvent.getPositions().getCumRealized()));
            assertThat(10000L, Is.is(alertEvent.getPositions().getOpenPriceSum()));
            assertThat(1L, Is.is(alertEvent.getPositions().getQuantity()));
            assertThat(-50L, Is.is(alertEvent.getPositions().getUnrealizedProfit()));
            assertThat(9949L, Is.is(alertEvent.getPositions().getLiquidationPrice()));
            assertThat(980L, Is.is(alertEvent.getPositions().getMarginRatioScaleK()));
            checkEventPending(alertEvent);
        }
    }

    // Cross
    // 当价格波动时, 计算1.2 * maitainance margin时要将extraMargin算进来,
    // 第一次如果没有增加保证金会发margin_alert事件
    // 第二次加了足够的保证金不会再触发margin_alert事件
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
        long takerOrderId2 = 1006L;
        long makerOrderId3 = 1007L;
        long takerOrderId4 = 1008L;
        long makerOrderId5 = 1009L;
        long makerOrderId6 = 1010L;
        int fee = 10;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
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
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            container.updateCurrentPriceTo(9000, symbols.get(0).symbolId, quoteId);
            container.updateCurrentPriceTo(23660, symbols.get(1).symbolId, quoteId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            // userId3先设置一个单子用于强制平仓, 此时
            container.createBidWithOrderId(makerOrderId5, userId3, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(makerOrderId6, userId3, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);

            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);
            // 期待结果makerOrderId6可以被挂出的强平吃掉
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(9840L));
                assertThat(profile.getPositions().size(), is(2));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            container.placeExtraMargin(userId1, quoteId, symbols.get(0).symbolId, 20L, MarginMode.CROSS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(9860L));
                assertThat(profile.getPositions().size(), is(2));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(9860L));
                assertThat(profile.getPositions().size(), is(2));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(40)).fundEventReport(fundEventCaptor.capture());
            // check fund event, 因为已经补充过保证金了所以只会发一次补充保证金事件
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport alertEvent = fundEvents.get(33);

            assertThat(userId1, Is.is(alertEvent.getAccountId()));
            assertThat(quoteId, Is.is(alertEvent.getBalances().getCurrency()));
            assertThat(10001, Is.is(alertEvent.getPositions().getSymbolId()));
            assertThat(PositionDirection.SHORT, Is.is(alertEvent.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.MARGIN_ALERT, Is.is(alertEvent.getEventType()));
            assertThat(0L, Is.is(alertEvent.getBalances().getFree()));
            assertThat(0L, Is.is(alertEvent.getBalances().getLocked()));
            assertThat(0L, Is.is(alertEvent.getPositions().getCumRealized()));
            assertThat(15000L, Is.is(alertEvent.getPositions().getOpenPriceSum()));
            assertThat(1L, Is.is(alertEvent.getPositions().getQuantity()));
            assertThat(-8660L, Is.is(alertEvent.getPositions().getUnrealizedProfit()));
            assertThat(38191L, Is.is(alertEvent.getPositions().getLiquidationPrice()));
            assertThat(7L, Is.is(alertEvent.getPositions().getMarginRatioScaleK()));
            checkEventPending(alertEvent);
        }
    }

    // Cross
    // 当价格波动时, 计算1.2 * maitainance margin时要将extraMargin算进来,
    // 第一次如果没有增加保证金会发margin_alert事件
    // 第二次加了保证金，但是不够1.2, 此时还是会再触发margin_alert事件
    @Test
    public void testCrossMarginLiquidationWarning2() {
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
        int fee = 10;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
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
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            container.updateCurrentPriceTo(9000, symbols.get(0).symbolId, quoteId);
            container.updateCurrentPriceTo(23660, symbols.get(1).symbolId, quoteId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            // userId3先设置一个单子用于强制平仓, 此时
            container.createBidWithOrderId(makerOrderId5, userId3, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(makerOrderId6, userId3, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);

            long initialBalance = 9840L;
            long marginDeposit = 12L;
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);
            // 期待结果makerOrderId6可以被挂出的强平吃掉
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(initialBalance));
                assertThat(profile.getPositions().size(), is(2));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            container.placeExtraMargin(userId1, quoteId, symbols.get(0).symbolId, marginDeposit, MarginMode.CROSS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(initialBalance + marginDeposit));
                assertThat(profile.getPositions().size(), is(2));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(initialBalance + marginDeposit));
                assertThat(profile.getPositions().size(), is(2));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(41)).fundEventReport(fundEventCaptor.capture());
            // check fund event, 因为已经补充过保证金了所以只会发一次补充保证金事件
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport alertEvent = fundEvents.get(33);
            assertThat(userId1, Is.is(alertEvent.getAccountId()));
            assertThat(quoteId, Is.is(alertEvent.getBalances().getCurrency()));
            assertThat(10001, Is.is(alertEvent.getPositions().getSymbolId()));
            assertThat(PositionDirection.SHORT, Is.is(alertEvent.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.MARGIN_ALERT, Is.is(alertEvent.getEventType()));
            assertThat(0L, Is.is(alertEvent.getBalances().getFree()));
            assertThat(0L, Is.is(alertEvent.getBalances().getLocked()));
            assertThat(0L, Is.is(alertEvent.getPositions().getCumRealized()));
            assertThat(15000L, Is.is(alertEvent.getPositions().getOpenPriceSum()));
            assertThat(1L, Is.is(alertEvent.getPositions().getQuantity()));
            assertThat(-8660L, Is.is(alertEvent.getPositions().getUnrealizedProfit()));
            assertThat(38191L, Is.is(alertEvent.getPositions().getLiquidationPrice()));
            assertThat(7L, Is.is(alertEvent.getPositions().getMarginRatioScaleK()));
            checkEventPending(alertEvent);

            IFundEventsHandler.FundEventReport alertEvent2 = fundEvents.get(38);
            assertThat(userId1, Is.is(alertEvent2.getAccountId()));
            assertThat(quoteId, Is.is(alertEvent2.getBalances().getCurrency()));
            assertThat(10001, Is.is(alertEvent2.getPositions().getSymbolId()));
            assertThat(PositionDirection.SHORT, Is.is(alertEvent2.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.MARGIN_ALERT, Is.is(alertEvent2.getEventType()));
            assertThat(0L, Is.is(alertEvent2.getBalances().getFree()));
            assertThat(0L, Is.is(alertEvent2.getBalances().getLocked()));
            assertThat(0L, Is.is(alertEvent2.getPositions().getCumRealized()));
            assertThat(15000L, Is.is(alertEvent2.getPositions().getOpenPriceSum()));
            assertThat(1L, Is.is(alertEvent2.getPositions().getQuantity()));
            assertThat(-8660L, Is.is(alertEvent2.getPositions().getUnrealizedProfit()));
            assertThat(38203L, Is.is(alertEvent2.getPositions().getLiquidationPrice()));
            assertThat(7L, Is.is(alertEvent2.getPositions().getMarginRatioScaleK()));
            checkEventPending(alertEvent2);
        }
    }

    // isolated liquidation - 强平后返还extraMargin, 包括发送margin_refund event
    @Test
    public void testIsolatedMarginLiquidation2() {
        long deposit = 10000L;
        long userId1 = UID_1;
        long userId2 = UID_2;
        long userId3 = UID_3;
        int size = 1;
        int price1 = 10000;
        int price2 = 15000;
        long makerOrderId1 = 1005L;
        long takerOrderId2 = 1006L;
        long makerOrderId3 = 1007L;
        long takerOrderId4 = 1008L;
        long makerOrderId5 = 1009L;
        long makerOrderId6 = 1010L;
        int delta1 = 1000;
        int delta2 = 3000;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::stop);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(userId3, MAX_VALUE, quoteId);

            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId);
            container.createAskWithOrderId(makerOrderId3, userId1, size, price2, symbols.get(1).symbolId);

            container.createAskWithOrderId(takerOrderId2, userId2, size, price1, symbols.get(0).symbolId);
            container.createBidWithOrderId(takerOrderId4, userId2, size, price2, symbols.get(1).symbolId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
            });

            // 模拟userId1看多亏损delta1, 看空亏损delta2
            container.updateCurrentPriceTo(price1 - delta1, symbols.get(0).symbolId, quoteId);
            container.updateCurrentPriceTo(price2 + delta2, symbols.get(1).symbolId, quoteId);

            container.placeExtraMargin(userId1, quoteId, symbols.get(0).symbolId, 900, MarginMode.ISOLATED);
            container.placeExtraMargin(userId1, quoteId, symbols.get(1).symbolId, 2900, MarginMode.ISOLATED);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).getExtraMargin(), is(900L));
                assertThat(profile.getPositions().get(symbols.get(1).symbolId).get(0).getExtraMargin(), is(2900L));
            });

            // userId3先设置一个单子用于强制平仓, 此时
            container.createBidWithOrderId(makerOrderId5, userId3, size, 9000, symbols.get(0).symbolId);
            container.createAskWithOrderId(makerOrderId6, userId3, size, 18000, symbols.get(1).symbolId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).getExtraMargin(), is(900L));
                assertThat(profile.getPositions().get(symbols.get(1).symbolId).get(0).getExtraMargin(), is(2900L));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });
            container.validateUserState(userId3, profile -> {
                assertThat(profile.getPositions().size(), is(2));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).direction, is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(symbols.get(1).symbolId).get(0).direction, is(PositionDirection.SHORT));
            });

            container.getExchangeCore().getLiquidationEngines().forEach(LiquidationEngine::triggerOnce);

            container.validateUserState(userId3, profile -> {
                assertThat(profile.getPositions().size(), is(2));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).direction, is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(symbols.get(1).symbolId).get(0).direction, is(PositionDirection.SHORT));
            });

            long fee1 = 10; // symbol0 1手 maker, 固定手续费10
            long fee2 = container.calculateFee(price2, 1, 1, symbols.get(1).makerFee, symbols.get(1).feeScaleK); // symbol1 1手maker, 动态手续费
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(0));
                // 金额包括:
                // 充值金额 = deposit + adjustMargin1 + adjustMargin1
                // fee = fee1 + fee2
                // 做多做空delta = delta1 + delta2
                // balance = deposit - fee - delta1 - delta2 + adjustMargin1 + adjustMargin2
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee1 - fee2 - delta1 - delta2));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(47)).fundEventReport(fundEventCaptor.capture());
            // check fund event
            List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
            IFundEventsHandler.FundEventReport refund1 = null;

            for (int i = 0; i < fundEvents.size(); i++) {
                IFundEventsHandler.FundEventReport report = fundEvents.get(i);
                if (report.getEventType().equals(FundEvent.FundEventType.MARGIN_REFUND)) {
                    refund1 = report;
                    break;
                }
            }

            assertThat(userId1, Is.is(refund1.getAccountId()));
            assertThat(quoteId, Is.is(refund1.getBalances().getCurrency()));
            assertThat(10000, Is.is(refund1.getPositions().getSymbolId()));
            assertThat(PositionDirection.LONG, Is.is(refund1.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.MARGIN_REFUND, Is.is(refund1.getEventType()));
            assertThat(6840L, Is.is(refund1.getBalances().getFree()));
            assertThat(100L, Is.is(refund1.getBalances().getLocked()));
            assertThat(-1000L, Is.is(refund1.getPositions().getCumRealized()));
            assertThat(0L, Is.is(refund1.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(refund1.getPositions().getQuantity()));
            checkEvent(refund1);
            checkEventPending(refund1);

            IFundEventsHandler.FundEventReport refund2 = fundEvents.get(44);
            assertThat(userId1, Is.is(refund2.getAccountId()));
            assertThat(quoteId, Is.is(refund2.getBalances().getCurrency()));
            assertThat(10001, Is.is(refund2.getPositions().getSymbolId()));
            assertThat(PositionDirection.SHORT, Is.is(refund2.getPositions().getDirection()));
            assertThat(FundEvent.FundEventType.MARGIN_REFUND, Is.is(refund2.getEventType()));
            assertThat(8840L, Is.is(refund2.getBalances().getFree()));
            assertThat(0L, Is.is(refund2.getBalances().getLocked()));
            assertThat(-3000L, Is.is(refund2.getPositions().getCumRealized()));
            assertThat(0L, Is.is(refund2.getPositions().getOpenPriceSum()));
            assertThat(0L, Is.is(refund2.getPositions().getQuantity()));
            checkEvent(refund2);
            checkEventPending(refund2);
        }
    }

    // extraMargin为专款专用, 和withdraw和无关
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
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration(), processor);) {
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();
            symbols.forEach(s -> container.initMarkPrice(s.symbolId, 10000));
            container.createUserWithSpecificMoney(userId1, deposit, quoteId);

            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId);
            container.createAskWithOrderId(makerOrderId3, userId1, size, price2, symbols.get(1).symbolId);
            // 此时划转会失败
            container.placeExtraMargin(userId1, quoteId, symbols.get(0).symbolId, deposit, MarginMode.ISOLATED);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).get(0).extraMargin, is(0L));
                assertThat(profile.getPositions().get(symbols.get(1).symbolId).get(0).extraMargin, is(0L));
                assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
            });

            ApiAdjustUserBalance cmd = ApiAdjustUserBalance.builder().uid(userId1).transactionId(container.getRandomTransactionId() + 100).amount(-deposit).currency(quoteId).build();

            container.submitCommandSync(cmd, CommandResultCode.RISK_NSF);

            // locked margin is 570
            container.addMoneyToUser(userId1, quoteId, 569);
            container.submitCommandSync(cmd, CommandResultCode.RISK_NSF);

            container.addMoneyToUser(userId1, quoteId, 1);
            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);
            assertTrue(container.totalBalanceReport().isGlobalBalancesAllZero());
        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}