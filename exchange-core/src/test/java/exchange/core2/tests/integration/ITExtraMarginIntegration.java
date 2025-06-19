package exchange.core2.tests.integration;

import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.event.IEventsHandler4Test;
import exchange.core2.core.event.SimpleEventsProcessor4Test;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.TimeUnit;

import static exchange.core2.core.common.OrderAction.BID;
import static exchange.core2.core.common.OrderType.*;
import static exchange.core2.tests.util.TestConstants.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
class ITExtraMarginIntegration {

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

    private PerformanceConfiguration getPerformanceConfiguration() {
        return PerformanceConfiguration.baseBuilder().build();
    }

    // inactive用户不允许增加extraMargin
    @Test
    public void testInactiveUser() {
        long deposit = 10000L;
        long userId1 = 1003L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
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
        long userId1 = 1003L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.initFutureSymbol(symbolId, quoteId);

            container.createUserWithSpecificMoney(userId1, deposit1, quoteId);

            // cross直接加到balance上
            ApiAdjustMargin cmd = ApiAdjustMargin.builder().transactionId(10001L).symbol(symbolId)
                    .uid(userId1).amount(deposit2).currency(quoteId)
                    .marginMode(MarginMode.CROSS)
                    .build();

            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit1 + deposit2));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(2)).fundsEvent(fundEventCapor.capture());
            // check fund event
            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
            IFundEventsHandler.FundsEvent event1 = fundEvents.get(1);

            assertThat(userId1, is(event1.uid));
            assertThat(quoteId, is(event1.currency));
            assertThat(0, is(event1.symbol));
            assertThat(10001L, is(event1.orderId));
            assertThat(0L, is(event1.fee));
            assertThat(PositionDirection.EMPTY, is(event1.direction));
            assertThat(FundEvent.FundEventType.DEPOSIT, is(event1.eventType));
            assertThat(3000L, is(event1.free));
            assertThat(0L, is(event1.locked));
            assertThat(0L, is(event1.openPriceSum));
            assertThat(0L, is(event1.pnl));
            assertThat(0L, is(event1.position));
            assertThat(0L, is(event1.positionChanged));
            assertThat(0L, is(event1.tradePrice));
        }
    }

    // 1. 用户增加保证金后extraMargin应相应增加, 包括事件发送正确 -- cross margin
    @Test
    public void testExtraMarin4Isolated() {
        long deposit1 = 2000L;
        long deposit2 = 1000L;
        long userId1 = 1003L;
        long size = 1;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.initFutureSymbol(symbolId, quoteId);

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
            });
            // order1类型为cross, 此时增加isolated保证金会报mismatch的错误
            container.submitCommandSync(cmd, CommandResultCode.RISK_MARGIN_MODE_MISMATCH);

            container.cancelOrder(userId1, order1.orderId, symbolId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(0));
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
                assertThat(profile.getPositions().getFirst().extraMargin, is(deposit2));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(5)).fundsEvent(fundEventCapor.capture());
            // check fund event
            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
            IFundEventsHandler.FundsEvent event1 = fundEvents.get(4);

            assertThat(userId1, is(event1.uid));
            assertThat(quoteId, is(event1.currency));
            assertThat(symbolId, is(event1.symbol));
            assertThat(10001L, is(event1.orderId));
            assertThat(0L, is(event1.fee));
            assertThat(PositionDirection.EMPTY, is(event1.direction));
            assertThat(FundEvent.FundEventType.MARGIN_ADJUST, is(event1.eventType));
            assertThat(1880L, is(event1.free));
            assertThat(120L, is(event1.locked));
            assertThat(0L, is(event1.openPriceSum));
            assertThat(0L, is(event1.pnl));
            assertThat(0L, is(event1.position));
            assertThat(0L, is(event1.positionChanged));
            assertThat(0L, is(event1.tradePrice));
            assertThat(1000L, is(event1.extra));
        }
    }

    // 平仓后extraMargin可提取, 包括事件发送准确
    @Test
    public void testIsolatedClosePosition() {
        int deposit = 1000;
        long deposit2 = 500L;
        long fee = 10;
        long userId1 = 1003L;
        long userId2 = 1004L;
        long price1 = 10000;
        long price2 = 10500;
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

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().getFirst().extraMargin, is(0L));
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee));
            });

            ApiAdjustMargin cmd = ApiAdjustMargin.builder().transactionId(10001L).symbol(symbolId)
                    .uid(userId1).amount(deposit2).currency(quoteId)
                    .marginMode(MarginMode.ISOLATED)
                    .build();
            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().getFirst().extraMargin, is(deposit2));
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee));
            });

            // 平仓成功
            container.createAskWithOrderId(makerOrderId3, userId1, 1, 10500, symbolId);
            container.createBidWithOrderId(takerOrderId4, userId2, 1, 10500, symbolId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(0));
                assertThat(profile.getAccounts().get(quoteId), is(deposit + deposit2 + price2 - price1 - fee));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(16)).fundsEvent(fundEventCapor.capture());
            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();

            IFundEventsHandler.FundsEvent marginAdjust = fundEvents.get(8);
            assertThat(userId1, is(marginAdjust.uid));
            assertThat(quoteId, is(marginAdjust.currency));
            assertThat(symbolId, is(marginAdjust.symbol));
            assertThat(0L, is(marginAdjust.fee));
            assertThat(PositionDirection.LONG, is(marginAdjust.direction));
            assertThat(FundEvent.FundEventType.MARGIN_ADJUST, is(marginAdjust.eventType));
            assertThat(890L, is(marginAdjust.free));
            assertThat(100L, is(marginAdjust.locked));
            assertThat(0L, is(marginAdjust.openPriceSum));
            assertThat(0L, is(marginAdjust.pnl));
            assertThat(1L, is(marginAdjust.position));
            assertThat(0L, is(marginAdjust.positionChanged));
            assertThat(500L, is(marginAdjust.extra));

            IFundEventsHandler.FundsEvent marginRefund = fundEvents.get(15);
            assertThat(userId1, is(marginRefund.uid));
            assertThat(quoteId, is(marginRefund.currency));
            assertThat(symbolId, is(marginRefund.symbol));
            assertThat(0L, is(marginRefund.fee));
            assertThat(PositionDirection.EMPTY, is(marginRefund.direction));
            assertThat(FundEvent.FundEventType.MARGIN_REFUND, is(marginRefund.eventType));
            assertThat(deposit - fee + price2 - price1, is(marginRefund.free));
            assertThat(0L, is(marginRefund.locked));
            assertThat(0L, is(marginRefund.openPriceSum));
            assertThat(0L, is(marginRefund.pnl));
            assertThat(0L, is(marginRefund.position));
            assertThat(0L, is(marginRefund.positionChanged));
            assertThat(0L, is(marginRefund.tradePrice));
            assertThat(500L, is(marginRefund.extra));
        }
    }

    // 每个仓位维护自己的extraMargin, 且相互独立
    @Test
    public void tesMultipleExtraMargin() {
        long deposit = 10000L;
        long userId1 = 1003L;
        long userId2 = 1004L;
        long userId3 = 1005L;
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
            container.setConsumer(processor);
            container.getExchangeCore().getLiquidationScanner().stop(10, TimeUnit.MINUTES);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();

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
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).extraMargin, is(0L));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).direction, is(PositionDirection.LONG));
                assertThat(profile.getPositions().get(symbols.get(1).symbolId).extraMargin, is(0L));
                assertThat(profile.getPositions().get(symbols.get(1).symbolId).direction, is(PositionDirection.SHORT));
            });

            container.placeExtraMargin(userId1, symbols.get(0).quoteCurrency, symbols.get(0).symbolId, 200L, MarginMode.ISOLATED);
            container.placeExtraMargin(userId1, symbols.get(1).quoteCurrency, symbols.get(1).symbolId, 300L, MarginMode.ISOLATED);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).extraMargin, is(200L));
                assertThat(profile.getPositions().get(symbols.get(1).symbolId).extraMargin, is(300L));
            });

            container.placeExtraMargin(userId1, symbols.get(0).quoteCurrency, symbols.get(0).symbolId, 300L, MarginMode.ISOLATED);
            container.placeExtraMargin(userId1, symbols.get(1).quoteCurrency, symbols.get(1).symbolId, 200L, MarginMode.ISOLATED);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).extraMargin, is(500L));
                assertThat(profile.getPositions().get(symbols.get(1).symbolId).extraMargin, is(500L));
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
        long userId1 = 11003L;
        long userId2 = 11004L;
        long userId3 = 11005L;
        int size = 1;
        long price1 = 10000;
        long makerOrderId1 = 1005L;
        long takerOrderId1 = 1006L;
        long fee = 10;
        long adjustMarginOrderId = 0;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.getExchangeCore().getLiquidationScanner().stop(10, TimeUnit.MINUTES);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();

            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(userId3, deposit, quoteId);

            // userId1 and userId2 match
            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId);

            container.createAskWithOrderId(takerOrderId1, userId2, size, price1, symbols.get(0).symbolId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
            });

            // 价格降到时, userId3因为是isolated开始触发强平, 但是此时userId1因为开的是cross margin(symbol1做多)所以没达到强平
            // userId1 symbol0: 10000 - 9950 = 50
            //         profit + balance = -50 + 100 = 50 < 60(50 * 1.2)此时会触发alert

            container.updateCurrentPriceTo(9950, symbols.get(0).symbolId, quoteId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
            });

            container.getExchangeCore().getLiquidationScanner().triggerOnce();

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).extraMargin, is(0L));
            });

            adjustMarginOrderId = container.placeExtraMargin(userId1, quoteId, symbols.get(0).symbolId, 10, MarginMode.ISOLATED);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).extraMargin, is(10L));
            });

            container.getExchangeCore().getLiquidationScanner().triggerOnce();

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).extraMargin, is(10L));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(25)).fundsEvent(fundEventCapor.capture());
            // check fund event
            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
            IFundEventsHandler.FundsEvent alertEvent = fundEvents.get(23);
            assertThat(userId1, is(alertEvent.uid));
            assertThat(quoteId, is(alertEvent.currency));
            assertThat(10000, is(alertEvent.symbol));
            assertThat(0L, is(alertEvent.orderId));
            assertThat(0L, is(alertEvent.fee));
            assertThat(PositionDirection.LONG, is(alertEvent.direction));
            assertThat(FundEvent.FundEventType.MARGIN_ALERT, is(alertEvent.eventType));
            assertThat(0L, is(alertEvent.free));
            assertThat(0L, is(alertEvent.locked));
            assertThat(10000L, is(alertEvent.openPriceSum));
            assertThat(0L, is(alertEvent.pnl));
            assertThat(1L, is(alertEvent.position));
            assertThat(0L, is(alertEvent.positionChanged));
            assertThat(0L, is(alertEvent.tradePrice));

            IFundEventsHandler.FundsEvent marginAdjust = fundEvents.get(24);
            assertThat(userId1, is(marginAdjust.uid));
            assertThat(quoteId, is(marginAdjust.currency));
            assertThat(10000, is(marginAdjust.symbol));
            assertThat(adjustMarginOrderId, is(marginAdjust.orderId));
            assertThat(0L, is(marginAdjust.fee));
            assertThat(PositionDirection.LONG, is(marginAdjust.direction));
            assertThat(FundEvent.FundEventType.MARGIN_ADJUST, is(marginAdjust.eventType));
            assertThat(deposit - 100 - fee, is(marginAdjust.free));
            assertThat(100L, is(marginAdjust.locked));
            assertThat(0L, is(marginAdjust.openPriceSum));
            assertThat(0L, is(marginAdjust.pnl));
            assertThat(1L, is(marginAdjust.position));
            assertThat(0L, is(marginAdjust.positionChanged));
            assertThat(0L, is(marginAdjust.tradePrice));
        }
    }

    // Isolated
    // 当价格波动时, 计算1.2 * maitainance margin时要将extraMargin算进来,
    // 第一次如果没有增加保证金会发margin_alert事件
    // 第二次加了保证金，但是不够1.2, 此时还是会再触发margin_alert事件
    @Test
    public void testIsolatedMarginLiquidationWarning2() {
        long deposit = 10000L;
        long userId1 = 11003L;
        long userId2 = 11004L;
        long userId3 = 11005L;
        int size = 1;
        long price1 = 10000;
        long makerOrderId1 = 1005L;
        long takerOrderId1 = 1006L;
        long fee = 10;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.getExchangeCore().getLiquidationScanner().stop(10, TimeUnit.MINUTES);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();

            container.createUserWithSpecificMoney(userId1, deposit, quoteId);
            container.createUserWithSpecificMoney(userId2, MAX_VALUE, quoteId);
            container.createUserWithSpecificMoney(userId3, deposit, quoteId);

            // userId1 and userId2 match
            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId);

            container.createAskWithOrderId(takerOrderId1, userId2, size, price1, symbols.get(0).symbolId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
            });

            // 价格降到时, userId3因为是isolated开始触发强平, 但是此时userId1因为开的是cross margin(symbol1做多)所以没达到强平
            // userId1 symbol0: 10000 - 9950 = 50
            //         profit + balance = -50 + 100 = 50 < 60(50 * 1.2)此时会触发alert

            container.updateCurrentPriceTo(9950, symbols.get(0).symbolId, quoteId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(1));
            });

            container.getExchangeCore().getLiquidationScanner().triggerOnce();

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).extraMargin, is(0L));
            });

            container.placeExtraMargin(userId1, quoteId, symbols.get(0).symbolId, 9, MarginMode.ISOLATED);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).extraMargin, is(9L));
            });

            container.getExchangeCore().getLiquidationScanner().triggerOnce();

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee));
                assertThat(profile.getPositions().size(), is(1));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).extraMargin, is(9L));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(26)).fundsEvent(fundEventCapor.capture());
            // check fund event
            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
            IFundEventsHandler.FundsEvent alertEvent = fundEvents.get(25);
            assertThat(userId1, is(alertEvent.uid));
            assertThat(quoteId, is(alertEvent.currency));
            assertThat(10000, is(alertEvent.symbol));
            assertThat(0L, is(alertEvent.orderId));
            assertThat(0L, is(alertEvent.fee));
            assertThat(PositionDirection.LONG, is(alertEvent.direction));
            assertThat(FundEvent.FundEventType.MARGIN_ALERT, is(alertEvent.eventType));
            assertThat(0L, is(alertEvent.free));
            assertThat(0L, is(alertEvent.locked));
            assertThat(10000L, is(alertEvent.openPriceSum));
            assertThat(0L, is(alertEvent.pnl));
            assertThat(1L, is(alertEvent.position));
            assertThat(0L, is(alertEvent.positionChanged));
            assertThat(0L, is(alertEvent.tradePrice));
        }
    }

    // Cross
    // 当价格波动时, 计算1.2 * maitainance margin时要将extraMargin算进来,
    // 第一次如果没有增加保证金会发margin_alert事件
    // 第二次加了足够的保证金不会再触发margin_alert事件
    @Test
    public void testCrossMarginLiquidationWarning() {
        long deposit = 10000L;
        long userId1 = 1003L;
        long userId2 = 1004L;
        long userId3 = 1005L;
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
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.getExchangeCore().getLiquidationScanner().stop(10, TimeUnit.MINUTES);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();

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

            container.updateCurrentPriceTo(9000, symbols.get(0).symbolId, quoteId);
            container.updateCurrentPriceTo(23680, symbols.get(1).symbolId, quoteId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
            });

            // userId3先设置一个单子用于强制平仓, 此时
            container.createBidWithOrderId(makerOrderId5, userId3, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(makerOrderId6, userId3, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);

            container.getExchangeCore().getLiquidationScanner().triggerOnce();
            // 期待结果makerOrderId6可以被挂出的强平吃掉
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(9840L));
                assertThat(profile.getPositions().size(), is(2));
            });

            container.placeExtraMargin(userId1, quoteId, symbols.get(0).symbolId, 20L, MarginMode.CROSS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(9860L));
                assertThat(profile.getPositions().size(), is(2));
            });

            container.getExchangeCore().getLiquidationScanner().triggerOnce();
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(9860L));
                assertThat(profile.getPositions().size(), is(2));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(51)).fundsEvent(fundEventCapor.capture());
            // check fund event, 因为已经补充过保证金了所以只会发一次补充保证金事件
            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
            IFundEventsHandler.FundsEvent alertEvent = fundEvents.get(45);
            assertThat(userId1, is(alertEvent.uid));
            assertThat(quoteId, is(alertEvent.currency));
            assertThat(10001, is(alertEvent.symbol));
            assertThat(0L, is(alertEvent.orderId));
            assertThat(0L, is(alertEvent.fee));
            assertThat(PositionDirection.SHORT, is(alertEvent.direction));
            assertThat(FundEvent.FundEventType.MARGIN_ALERT, is(alertEvent.eventType));
            assertThat(0L, is(alertEvent.free));
            assertThat(0L, is(alertEvent.locked));
            assertThat(15000L, is(alertEvent.openPriceSum));
            assertThat(0L, is(alertEvent.pnl));
            assertThat(1L, is(alertEvent.position));
            assertThat(0L, is(alertEvent.positionChanged));
            assertThat(0L, is(alertEvent.tradePrice));
            assertThat(0L, is(alertEvent.extra));
        }
    }

    // Cross
    // 当价格波动时, 计算1.2 * maitainance margin时要将extraMargin算进来,
    // 第一次如果没有增加保证金会发margin_alert事件
    // 第二次加了保证金，但是不够1.2, 此时还是会再触发margin_alert事件
    @Test
    public void testCrossMarginLiquidationWarning2() {
        long deposit = 10000L;
        long userId1 = 1003L;
        long userId2 = 1004L;
        long userId3 = 1005L;
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
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.getExchangeCore().getLiquidationScanner().stop(10, TimeUnit.MINUTES);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();

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

            container.updateCurrentPriceTo(9000, symbols.get(0).symbolId, quoteId);
            container.updateCurrentPriceTo(23680, symbols.get(1).symbolId, quoteId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
            });

            // userId3先设置一个单子用于强制平仓, 此时
            container.createBidWithOrderId(makerOrderId5, userId3, size, price1, symbols.get(0).symbolId, MarginMode.CROSS);
            container.createAskWithOrderId(makerOrderId6, userId3, size, price2, symbols.get(1).symbolId, MarginMode.CROSS);

            container.getExchangeCore().getLiquidationScanner().triggerOnce();
            // 期待结果makerOrderId6可以被挂出的强平吃掉
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(9840L));
                assertThat(profile.getPositions().size(), is(2));
            });

            container.placeExtraMargin(userId1, quoteId, symbols.get(0).symbolId, 19L, MarginMode.CROSS);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(9859L));
                assertThat(profile.getPositions().size(), is(2));
            });

            container.getExchangeCore().getLiquidationScanner().triggerOnce();
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(9859L));
                assertThat(profile.getPositions().size(), is(2));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(52)).fundsEvent(fundEventCapor.capture());
            // check fund event, 因为已经补充过保证金了所以只会发一次补充保证金事件
            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
            IFundEventsHandler.FundsEvent alertEvent = fundEvents.get(45);
            assertThat(userId1, is(alertEvent.uid));
            assertThat(quoteId, is(alertEvent.currency));
            assertThat(10001, is(alertEvent.symbol));
            assertThat(0L, is(alertEvent.orderId));
            assertThat(0L, is(alertEvent.fee));
            assertThat(PositionDirection.SHORT, is(alertEvent.direction));
            assertThat(FundEvent.FundEventType.MARGIN_ALERT, is(alertEvent.eventType));
            assertThat(0L, is(alertEvent.free));
            assertThat(0L, is(alertEvent.locked));
            assertThat(15000L, is(alertEvent.openPriceSum));
            assertThat(0L, is(alertEvent.pnl));
            assertThat(1L, is(alertEvent.position));
            assertThat(0L, is(alertEvent.positionChanged));
            assertThat(0L, is(alertEvent.tradePrice));
            assertThat(0L, is(alertEvent.extra));

            IFundEventsHandler.FundsEvent alertEvent2 = fundEvents.get(49);
            assertThat(userId1, is(alertEvent2.uid));
            assertThat(quoteId, is(alertEvent2.currency));
            assertThat(10001, is(alertEvent2.symbol));
            assertThat(0L, is(alertEvent2.orderId));
            assertThat(0L, is(alertEvent2.fee));
            assertThat(PositionDirection.SHORT, is(alertEvent2.direction));
            assertThat(FundEvent.FundEventType.MARGIN_ALERT, is(alertEvent2.eventType));
            assertThat(0L, is(alertEvent2.free));
            assertThat(0L, is(alertEvent2.locked));
            assertThat(15000L, is(alertEvent2.openPriceSum));
            assertThat(0L, is(alertEvent2.pnl));
            assertThat(1L, is(alertEvent2.position));
            assertThat(0L, is(alertEvent2.positionChanged));
            assertThat(0L, is(alertEvent2.tradePrice));
            assertThat(0L, is(alertEvent.extra));
        }
    }

    // isolated liquidation - 强平后返还extraMargin, 包括发送margin_refund event
    @Test
    public void testIsolatedMarginLiquidation2() {
        long deposit = 10000L;
        long userId1 = 1003L;
        long userId2 = 1004L;
        long userId3 = 1005L;
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
        long adjustMargin1 = 900L;
        long adjustMargin2 = 2900L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            container.getExchangeCore().getLiquidationScanner().stop(10, TimeUnit.MINUTES);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();

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
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).getExtraMargin(), is(900L));
                assertThat(profile.getPositions().get(symbols.get(1).symbolId).getExtraMargin(), is(2900L));
            });

            // userId3先设置一个单子用于强制平仓, 此时
            container.createBidWithOrderId(makerOrderId5, userId3, size, 9000, symbols.get(0).symbolId);
            container.createAskWithOrderId(makerOrderId6, userId3, size, 18000, symbols.get(1).symbolId);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(2));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).getExtraMargin(), is(900L));
                assertThat(profile.getPositions().get(symbols.get(1).symbolId).getExtraMargin(), is(2900L));
            });

            container.getExchangeCore().getLiquidationScanner().triggerOnce();

            long fee1 = 10; // symbol0 1手 maker, 固定手续费10
            long fee2 = container.calculateFee(price2, 1, 1, symbols.get(1).makerFee, symbols.get(1).feeScaleK); // symbol1 1手maker, 动态手续费
            container.validateUserState(userId1, profile -> {
                assertThat(profile.getPositions().size(), is(0));
                // 金额包括:
                // 充值金额 = deposit + adjustMargin1 + adjustMargin1
                // fee = fee1 + fee2
                // 做多做空delta = delta1 + delta2
                // balance = deposit - fee - delta1 - delta2 + adjustMargin1 + adjustMargin2
                assertThat(profile.getAccounts().get(quoteId), is(deposit - fee1 - fee2 - delta1 - delta2 + adjustMargin1 + adjustMargin2));
            });

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            verify(handler, times(59)).fundsEvent(fundEventCapor.capture());
            // check fund event
            List<IFundEventsHandler.FundsEvent> fundEvents = fundEventCapor.getAllValues();
            IFundEventsHandler.FundsEvent refund1 = fundEvents.get(52);
            assertThat(userId1, is(refund1.uid));
            assertThat(quoteId, is(refund1.currency));
            assertThat(10000, is(refund1.symbol));
            assertThat(0L, is(refund1.fee));
            assertThat(PositionDirection.EMPTY, is(refund1.direction));
            assertThat(FundEvent.FundEventType.MARGIN_REFUND, is(refund1.eventType));
            assertThat(10640L, is(refund1.free));
            assertThat(100L, is(refund1.locked));
            assertThat(0L, is(refund1.openPriceSum));
            assertThat(0L, is(refund1.pnl));
            assertThat(0L, is(refund1.position));
            assertThat(0L, is(refund1.positionChanged));
            assertThat(0L, is(refund1.tradePrice));
            assertThat(900L, is(refund1.extra));

            IFundEventsHandler.FundsEvent refund2 = fundEvents.get(56);
            assertThat(userId1, is(refund2.uid));
            assertThat(quoteId, is(refund2.currency));
            assertThat(10001, is(refund2.symbol));
            assertThat(0L, is(refund2.fee));
            assertThat(PositionDirection.EMPTY, is(refund2.direction));
            assertThat(FundEvent.FundEventType.MARGIN_REFUND, is(refund2.eventType));
            assertThat(12640L, is(refund2.free));
            assertThat(0L, is(refund2.locked));
            assertThat(0L, is(refund2.openPriceSum));
            assertThat(0L, is(refund2.pnl));
            assertThat(0L, is(refund2.position));
            assertThat(0L, is(refund2.positionChanged));
            assertThat(0L, is(refund2.tradePrice));
            assertThat(2900L, is(refund2.extra));
        }
    }

    // extraMargin为专款专用, 和withdraw和无关
    @Test
    public void testCrossMarginWithdraw() {
        long deposit = 10000L;
        long userId1 = 1003L;
        int size = 1;
        long price1 = 10000;
        long price2 = 15000;
        long makerOrderId1 = 1005L;
        long takerOrderId2 = 1006L;
        long makerOrderId3 = 1007L;
        long takerOrderId4 = 1008L;
        try (final ExchangeTestContainer container = ExchangeTestContainer.create(getPerformanceConfiguration());) {
            container.setConsumer(processor);
            List<CoreSymbolSpecification> symbols = container.initFutureSymbols();

            container.createUserWithSpecificMoney(userId1, deposit, quoteId);

            container.createBidWithOrderId(makerOrderId1, userId1, size, price1, symbols.get(0).symbolId);
            container.createAskWithOrderId(makerOrderId3, userId1, size, price2, symbols.get(1).symbolId);

            container.placeExtraMargin(userId1, quoteId, symbols.get(0).symbolId, deposit, MarginMode.ISOLATED);

            container.validateUserState(userId1, profile -> {
                assertThat(profile.getAccounts().get(quoteId), is(deposit));
                assertThat(profile.getPositions().get(symbols.get(0).symbolId).extraMargin, is(deposit));
                assertThat(profile.getPositions().get(symbols.get(1).symbolId).extraMargin, is(0L));
            });

            ApiAdjustUserBalance cmd = ApiAdjustUserBalance.builder().uid(userId1).transactionId(container.getRandomTransactionId() + 100).amount(-deposit).currency(quoteId).build();

            container.submitCommandSync(cmd, CommandResultCode.RISK_NSF);

            // locked margin is 520
            container.addMoneyToUser(userId1, quoteId, 519);
            container.submitCommandSync(cmd, CommandResultCode.RISK_NSF);

            container.addMoneyToUser(userId1, quoteId, 1);
            container.submitCommandSync(cmd, CommandResultCode.SUCCESS);

        } catch (ExecutionException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}