package exchange.core2.tests.integration;

import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.*;
import exchange.core2.core.common.api.ApiAdjustMargin;
import exchange.core2.core.common.api.ApiAdjustUserBalance;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.reports.SingleUserReportResult;
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
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

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

            assertThat(userId1, Is.is(event1.uid));
            assertThat(quoteId, Is.is(event1.currency));
            assertThat(0, Is.is(event1.symbol));
            assertThat(10001L, Is.is(event1.orderId));
            assertThat(0L, Is.is(event1.fee));
            assertThat(PositionDirection.EMPTY, Is.is(event1.direction));
            assertThat(FundEvent.FundEventType.DEPOSIT, Is.is(event1.eventType));
            assertThat(3000L, Is.is(event1.free));
            assertThat(0L, Is.is(event1.locked));
            assertThat(0L, Is.is(event1.openPriceSum));
            assertThat(0L, Is.is(event1.pnl));
            assertThat(0L, Is.is(event1.position));
            assertThat(0L, Is.is(event1.positionChanged));
            assertThat(0L, Is.is(event1.tradePrice));
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
                    .action(OrderAction.BID)
                    .size(size)
                    .price(10000L)
                    .symbol(symbolId)
                    .orderType(OrderType.GTC)
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
                    .action(OrderAction.BID)
                    .size(size)
                    .price(10000L)
                    .symbol(symbolId)
                    .orderType(OrderType.GTC)
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

            assertThat(userId1, Is.is(event1.uid));
            assertThat(quoteId, Is.is(event1.currency));
            assertThat(symbolId, Is.is(event1.symbol));
            assertThat(10001L, Is.is(event1.orderId));
            assertThat(0L, Is.is(event1.fee));
            assertThat(PositionDirection.EMPTY, Is.is(event1.direction));
            assertThat(FundEvent.FundEventType.MARGIN_ADJUST, Is.is(event1.eventType));
            assertThat(1880L, Is.is(event1.free));
            assertThat(120L, Is.is(event1.locked));
            assertThat(0L, Is.is(event1.openPriceSum));
            assertThat(0L, Is.is(event1.pnl));
            assertThat(0L, Is.is(event1.position));
            assertThat(0L, Is.is(event1.positionChanged));
            assertThat(0L, Is.is(event1.tradePrice));
            assertThat(1000L, Is.is(event1.extra));
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
            assertThat(userId1, Is.is(marginAdjust.uid));
            assertThat(quoteId, Is.is(marginAdjust.currency));
            assertThat(symbolId, Is.is(marginAdjust.symbol));
            assertThat(0L, Is.is(marginAdjust.fee));
            assertThat(PositionDirection.LONG, Is.is(marginAdjust.direction));
            assertThat(FundEvent.FundEventType.MARGIN_ADJUST, Is.is(marginAdjust.eventType));
            assertThat(890L, Is.is(marginAdjust.free));
            assertThat(100L, Is.is(marginAdjust.locked));
            assertThat(0L, Is.is(marginAdjust.openPriceSum));
            assertThat(0L, Is.is(marginAdjust.pnl));
            assertThat(1L, Is.is(marginAdjust.position));
            assertThat(0L, Is.is(marginAdjust.positionChanged));
            assertThat(500L, Is.is(marginAdjust.extra));

            IFundEventsHandler.FundsEvent marginRefund = fundEvents.get(15);
            assertThat(userId1, Is.is(marginRefund.uid));
            assertThat(quoteId, Is.is(marginRefund.currency));
            assertThat(symbolId, Is.is(marginRefund.symbol));
            assertThat(0L, Is.is(marginRefund.fee));
            assertThat(PositionDirection.EMPTY, Is.is(marginRefund.direction));
            assertThat(FundEvent.FundEventType.MARGIN_REFUND, Is.is(marginRefund.eventType));
            assertThat(deposit - fee + price2 - price1, Is.is(marginRefund.free));
            assertThat(0L, Is.is(marginRefund.locked));
            assertThat(0L, Is.is(marginRefund.openPriceSum));
            assertThat(0L, Is.is(marginRefund.pnl));
            assertThat(0L, Is.is(marginRefund.position));
            assertThat(0L, Is.is(marginRefund.positionChanged));
            assertThat(0L, Is.is(marginRefund.tradePrice));
            assertThat(500L, Is.is(marginRefund.extra));
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
    
}