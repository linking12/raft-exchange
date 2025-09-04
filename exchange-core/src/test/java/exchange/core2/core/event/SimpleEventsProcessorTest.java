package exchange.core2.core.event;

import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.*;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import lombok.extern.slf4j.Slf4j;
import org.hamcrest.core.Is;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.util.List;

import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
public final class SimpleEventsProcessorTest {

    private SimpleEventsProcessor4Test processor;

    @Mock
    private IEventsHandler4Test handler;

    @Captor
    private ArgumentCaptor<ITradeEventsHandler.SpotExecutionReport> tradeEventCaptor;

    @Captor
    private ArgumentCaptor<IFundEventsHandler.FundEventReport> fundEventCaptor;

    @BeforeEach
    public void before() {
        processor = new SimpleEventsProcessor4Test(handler);
        processor.getSymbolSpecificationMap().put(3, processor.fakeSpotSymbol(3));
        processor.getSymbolSpecificationMap().put(30, processor.fakeSpotSymbol(30));
    }

    @Test
    public void shouldHandleSimpleCommand() {

        OrderCommand cmd = sampleCancelCommand();
        cmd.matcherEvent = new MatcherTradeEvent(MatcherEventType.REDUCE, 0, true, 0, 0, true,
                OrderCommandType.PLACE_ORDER, 0, 0, OrderType.GTC, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);

        cmd.takerFundEvents = new FundEvent(false, FundEvent.FundEventType.LOCKED, 0L, 0L, 0, 0L, 0L, 0L, 1, 0L, 0L, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0, null);
        cmd.takerFundEvents.nextEvent = new FundEvent(false, FundEvent.FundEventType.UNLOCKED, 0L, 0L, 0, 0L, 0L, 0L, 1, 0L, 0L, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0, null);

        processor.accept(cmd, 192837L);

        verify(handler, times(1)).spotExecutionReport(tradeEventCaptor.capture());
        verify(handler, never()).futuresExecutionReport(any());
        verify(handler, times(2)).fundEventReport(fundEventCaptor.capture());

        ITradeEventsHandler.SpotExecutionReport report = tradeEventCaptor.getValue();
        assertThat(report.getOrderId(), Is.is(123L));
        assertThat(report.getSymbol(), Is.is(3));
        assertThat(report.getAccountId(), Is.is(29851L));

        List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
        assertThat(fundEvents.size(), Is.is(2));
        IFundEventsHandler.FundEventReport locked = fundEvents.get(0);
        assertThat(locked.getEventType(), Is.is(FundEvent.FundEventType.LOCKED));
        IFundEventsHandler.FundEventReport unlocked = fundEvents.get(1);
        assertThat(unlocked.getEventType(), Is.is(FundEvent.FundEventType.UNLOCKED));
    }

    @Test
    public void shouldHandleWithReduceCommand() {

        OrderCommand cmd = sampleReduceCommand();

        cmd.matcherEvent = MatcherTradeEvent.builder()
                .eventType(MatcherEventType.REDUCE)
                .section(0)
                .activeOrderCompleted(true)
                .matchedOrderId(0)
                .matchedOrderCompleted(false)
                .filled(100L)
                .filledNotional(10000L)
                .bidderHoldPrice(20100L)
                .nextEvent(null)
                .build();

        processor.accept(cmd, 192837L);

        verify(handler, times(1)).spotExecutionReport(tradeEventCaptor.capture());
        verify(handler, never()).futuresExecutionReport(any());
        verify(handler, never()).fundEventReport(any());

        ITradeEventsHandler.SpotExecutionReport report = tradeEventCaptor.getValue();
        assertThat(report.getOrderId(), Is.is(123L));
        assertThat(report.getLastQty(), Is.is(0L));
        assertThat(report.getSymbol(), Is.is(3));
        assertThat(report.executionType, Is.is(ITradeEventsHandler.ExecType.REDUCE));
        assertThat(report.getAccountId(), Is.is(29851L));
        assertThat(report.price, Is.is(52200L));
        assertThat(report.qty, Is.is(3200L));
        assertThat(report.cumulativeQty, Is.is(100L));
        assertThat(report.cumulativeQuoteQty, Is.is(10000L));
        assertThat(report.commissionAsset, Is.is(2));
        assertThat(report.isMaker, Is.is(false));
    }

    @Test
    public void shouldHandleWithSingleTrade() {

        OrderCommand cmd = samplePlaceOrderCommand();

        cmd.matcherEvent = MatcherTradeEvent.builder()
                .eventType(MatcherEventType.TRADE)
                .activeOrderCompleted(false)
                .matchedOrderId(276810L)
                .matchedOrderUid(10332L)
                .matchedOrderCompleted(true)
                .matchedOrderCommandType(OrderCommandType.PLACE_ORDER)
                .matchedOrderFilled(123L)
                .matchedOrderFilledNotional(1000L)
                .matchedOrderType(OrderType.GTC)
                .matchedOrderPrice(12233L)
                .matchedOrderSize(23L)
                .matchedUserCookie(778899)
                .matchedOrderTimestamp(177777777777L)
                .price(20100L)
                .size(8272L)
                .filled(123L)
                .filledNotional(1000L)
                .bidderHoldPrice(13233L)
                .nextEvent(null)
                .build();

        cmd.takerFundEvents = new FundEvent(false, FundEvent.FundEventType.LOCKED, 10L, 100L, 10, 1000L, 0L, 10L, 1, 1000L, 1000L, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0, null);
        cmd.takerFundEvents.nextEvent = new FundEvent(false, FundEvent.FundEventType.UNLOCKED, 10L, 100L, 10, 1000L, 0L, 10L, 1, 1000L, 10000L, PositionDirection.LONG, 1, 2, 3, 4, 5, 6, 7, 8, 9, MarginMode.ISOLATED, 10, 11, 12, 13, 14, 15, 16, 17, null);

        processor.accept(cmd, 192837L);

        verify(handler, times(3)).spotExecutionReport(tradeEventCaptor.capture());
        verify(handler, never()).futuresExecutionReport(any());
        verify(handler, times(2)).fundEventReport(fundEventCaptor.capture());

        List<ITradeEventsHandler.SpotExecutionReport> reports = tradeEventCaptor.getAllValues();

        ITradeEventsHandler.SpotExecutionReport newOrder = reports.get(0);
        assertThat(newOrder.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
        assertThat(newOrder.getOrderId(), Is.is(123L));
        assertThat(newOrder.getSymbol(), Is.is(3));
        assertThat(newOrder.getAccountId(), Is.is(29851L));
        assertThat(newOrder.commissionAsset, Is.is(2));
        assertThat(newOrder.getSymbol(), Is.is(3));
        assertThat(newOrder.price, Is.is(52200L));
        assertThat(newOrder.qty, Is.is(3200L));
        assertThat(newOrder.cumulativeQty, Is.is(0L));
        assertThat(newOrder.cumulativeQuoteQty, Is.is(0L));
        assertThat(newOrder.commissionAsset, Is.is(2));
        assertThat(newOrder.isMaker, Is.is(false));

        ITradeEventsHandler.SpotExecutionReport maker = reports.get(1);
        assertThat(maker.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
        assertThat(maker.getOrderId(), Is.is(123L));
        assertThat(maker.getSymbol(), Is.is(3));
        assertThat(maker.getAccountId(), Is.is(29851L));
        assertThat(maker.commissionAsset, Is.is(2));
        assertThat(maker.getSymbol(), Is.is(3));
        assertThat(maker.price, Is.is(52200L));
        assertThat(maker.qty, Is.is(3200L));
        assertThat(maker.cumulativeQty, Is.is(123L));
        assertThat(maker.cumulativeQuoteQty, Is.is(1000L));
        assertThat(maker.commissionAsset, Is.is(2));
        assertThat(maker.isMaker, Is.is(false));

        ITradeEventsHandler.SpotExecutionReport taker = reports.get(2);
        assertThat(taker.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
        assertThat(taker.getOrderId(), Is.is(276810L));
        assertThat(taker.getSymbol(), Is.is(3));
        assertThat(taker.getAccountId(), Is.is(10332L));
        assertThat(taker.commissionAsset, Is.is(2));
        assertThat(taker.getSymbol(), Is.is(3));
        assertThat(taker.price, Is.is(12233L));
        assertThat(taker.qty, Is.is(23L));
        assertThat(taker.cumulativeQty, Is.is(123L));
        assertThat(taker.cumulativeQuoteQty, Is.is(1000L));
        assertThat(taker.commissionAsset, Is.is(2));
        assertThat(taker.isMaker, Is.is(false));

        List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
        assertThat(fundEvents.size(), Is.is(2));
        IFundEventsHandler.FundEventReport locked = fundEvents.get(0);
        assertThat(locked.getEventType(), Is.is(FundEvent.FundEventType.LOCKED));
        assertThat(locked.getBalances().getLocked(), Is.is(10L));
        assertThat(locked.getBalances().getFree(), Is.is(0L));
        assertThat(locked.getBalances().getCurrency(), Is.is(10));
        assertThat(locked.getBalances().getCurrencyScakeK(), Is.is(1000L));

        IFundEventsHandler.FundEventReport unlocked = fundEvents.get(1);
        assertThat(unlocked.getEventType(), Is.is(FundEvent.FundEventType.UNLOCKED));
        assertThat(unlocked.getPositions().getSymbolId(), Is.is(1));
        assertThat(unlocked.getPositions().getBaseScaleK(), Is.is(1000L));
        assertThat(unlocked.getPositions().getQuoteScaleK(), Is.is(10000L));
        assertThat(unlocked.getPositions().getDirection(), Is.is(PositionDirection.LONG));
        assertThat(unlocked.getPositions().getQuantity(), Is.is(1L));
        assertThat(unlocked.getPositions().getOpenPriceSum(), Is.is(3L));
        assertThat(unlocked.getPositions().getCumRealized(), Is.is(4L));
        assertThat(unlocked.getPositions().isIsolated(), Is.is(true));
        assertThat(unlocked.getPositions().getIsolatedWallet(), Is.is(10L));
        assertThat(unlocked.getPositions().getLeverage(), Is.is(9));
        assertThat(unlocked.getPositions().getOpenInitMarginSum(), Is.is(2L));
        assertThat(unlocked.getPositions().getMarkPrice(), Is.is(14L));
        assertThat(unlocked.getPositions().getUnrealizedProfit(), Is.is(11L));
        assertThat(unlocked.getPositions().getLiquidationPrice(), Is.is(12L));
        assertThat(unlocked.getPositions().getMarginRatioScaleK(), Is.is(13L));
    }

    @Test
    public void shouldHandleWithTwoTrades() {

        OrderCommand cmd = samplePlaceOrderCommand();

        MatcherTradeEvent firstTrade = MatcherTradeEvent.builder()
                .eventType(MatcherEventType.TRADE)
                .activeOrderCompleted(false)
                .matchedOrderId(276810L)
                .matchedOrderUid(10332L)
                .matchedOrderCompleted(true)
                .matchedOrderCommandType(OrderCommandType.PLACE_ORDER)
                .matchedOrderFilled(123L)
                .matchedOrderFilledNotional(1000L)
                .matchedOrderType(OrderType.GTC)
                .matchedOrderPrice(12233L)
                .matchedOrderSize(23L)
                .matchedUserCookie(778899)
                .matchedOrderTimestamp(177777777777L)
                .price(20100L)
                .size(8272L)
                .filled(123L)
                .filledNotional(1000L)
                .bidderHoldPrice(13233L)
                .nextEvent(null)
                .build();

        MatcherTradeEvent secondTrade = MatcherTradeEvent.builder()
                .eventType(MatcherEventType.TRADE)
                .activeOrderCompleted(false)
                .matchedOrderId(276811L)
                .matchedOrderUid(10333L)
                .matchedOrderCompleted(false)
                .matchedOrderCommandType(OrderCommandType.PLACE_ORDER)
                .matchedOrderFilled(223L)
                .matchedOrderFilledNotional(1100L)
                .matchedOrderType(OrderType.GTC)
                .matchedOrderPrice(12233L)
                .matchedOrderSize(13L)
                .matchedUserCookie(778999)
                .matchedOrderTimestamp(177777777778L)
                .price(20101L)
                .size(8273L)
                .filled(124L)
                .filledNotional(10000L)
                .bidderHoldPrice(13233L)
                .nextEvent(null)
                .build();

        cmd.matcherEvent = firstTrade;
        firstTrade.nextEvent = secondTrade;

        cmd.takerFundEvents = new FundEvent(false, FundEvent.FundEventType.LOCKED, 10L, 100L, 10, 1000L, 0L, 10L, 1, 1000L, 1000L, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0, null);
        cmd.takerFundEvents.nextEvent = new FundEvent(false, FundEvent.FundEventType.UNLOCKED, 10L, 100L, 10, 1000L, 0L, 10L, 1, 1000L, 10000L, PositionDirection.LONG, 1, 2, 3, 4, 5, 6, 7, 8, 9, MarginMode.ISOLATED, 10, 11, 12, 13, 14, 15, 16, 17, null);

        processor.accept(cmd, 12981721239L);

        verify(handler, times(5)).spotExecutionReport(tradeEventCaptor.capture());
        verify(handler, never()).futuresExecutionReport(any());
        verify(handler, times(2)).fundEventReport(fundEventCaptor.capture());

        List<ITradeEventsHandler.SpotExecutionReport> reports = tradeEventCaptor.getAllValues();
        assertThat(reports.size(), Is.is(5));
        ITradeEventsHandler.SpotExecutionReport report0 = reports.get(0);
        assertThat(report0.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));

        ITradeEventsHandler.SpotExecutionReport maker = reports.get(3);
        assertThat(maker.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
        assertThat(maker.getOrderId(), Is.is(123L));
        assertThat(maker.getSymbol(), Is.is(3));
        assertThat(maker.getAccountId(), Is.is(29851L));
        assertThat(maker.commissionAsset, Is.is(2));
        assertThat(maker.getSymbol(), Is.is(3));
        assertThat(maker.price, Is.is(52200L));
        assertThat(maker.qty, Is.is(3200L));
        assertThat(maker.cumulativeQty, Is.is(124L));
        assertThat(maker.cumulativeQuoteQty, Is.is(10000L));
        assertThat(maker.commissionAsset, Is.is(2));
        assertThat(maker.isMaker, Is.is(false));

        ITradeEventsHandler.SpotExecutionReport taker = reports.get(4);
        assertThat(taker.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
        assertThat(taker.getOrderId(), Is.is(276811L));
        assertThat(taker.getSymbol(), Is.is(3));
        assertThat(taker.getAccountId(), Is.is(10333L));
        assertThat(taker.commissionAsset, Is.is(2));
        assertThat(taker.getSymbol(), Is.is(3));
        assertThat(taker.price, Is.is(12233L));
        assertThat(taker.qty, Is.is(13L));
        assertThat(taker.cumulativeQty, Is.is(223L));
        assertThat(taker.cumulativeQuoteQty, Is.is(1100L));
        assertThat(taker.commissionAsset, Is.is(2));
        assertThat(taker.isMaker, Is.is(false));

        List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
        assertThat(fundEvents.size(), Is.is(2));
        IFundEventsHandler.FundEventReport locked = fundEvents.get(0);
        assertThat(locked.getEventType(), Is.is(FundEvent.FundEventType.LOCKED));

        IFundEventsHandler.FundEventReport unlocked = fundEvents.get(1);
        assertThat(unlocked.getEventType(), Is.is(FundEvent.FundEventType.UNLOCKED));
    }

    @Test
    public void shouldHandleWithTwoTradesAndReject() {

        OrderCommand cmd = samplePlaceOrderCommand();

        MatcherTradeEvent firstTrade = MatcherTradeEvent.builder()
                .eventType(MatcherEventType.TRADE)
                .activeOrderCompleted(false)
                .matchedOrderId(276810L)
                .matchedOrderUid(10332L)
                .matchedOrderCompleted(true)
                .matchedOrderCommandType(OrderCommandType.PLACE_ORDER)
                .matchedOrderFilled(123L)
                .matchedOrderFilledNotional(1000L)
                .matchedOrderType(OrderType.GTC)
                .matchedOrderPrice(12233L)
                .matchedOrderSize(23L)
                .matchedUserCookie(778899)
                .matchedOrderTimestamp(177777777777L)
                .price(20100L)
                .size(8272L)
                .filled(123L)
                .filledNotional(1000L)
                .bidderHoldPrice(13233L)
                .nextEvent(null)
                .build();

        MatcherTradeEvent secondTrade = MatcherTradeEvent.builder()
                .eventType(MatcherEventType.TRADE)
                .activeOrderCompleted(false)
                .matchedOrderId(276811L)
                .matchedOrderUid(10333L)
                .matchedOrderCompleted(false)
                .matchedOrderCommandType(OrderCommandType.PLACE_ORDER)
                .matchedOrderFilled(223L)
                .matchedOrderFilledNotional(1100L)
                .matchedOrderType(OrderType.GTC)
                .matchedOrderPrice(12233L)
                .matchedOrderSize(13L)
                .matchedUserCookie(778999)
                .matchedOrderTimestamp(177777777778L)
                .price(20101L)
                .size(8273L)
                .filled(124L)
                .filledNotional(10000L)
                .bidderHoldPrice(13233L)
                .nextEvent(null)
                .build();

        MatcherTradeEvent reject = MatcherTradeEvent.builder()
                .eventType(MatcherEventType.REJECT)
                .activeOrderCompleted(true)
                .size(8272L)
                .nextEvent(null)
                .build();

        cmd.matcherEvent = firstTrade;
        firstTrade.nextEvent = secondTrade;
        secondTrade.nextEvent = reject;

        processor.accept(cmd, 12981721239L);

        verify(handler, times(5)).spotExecutionReport(tradeEventCaptor.capture());
        verify(handler, never()).futuresExecutionReport(any());
        verify(handler, never()).fundEventReport(any());

        List<ITradeEventsHandler.SpotExecutionReport> reports = tradeEventCaptor.getAllValues();
        assertThat(reports.size(), Is.is(5));
        ITradeEventsHandler.SpotExecutionReport report0 = reports.get(0);
        assertThat(report0.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));

        ITradeEventsHandler.SpotExecutionReport maker = reports.get(3);
        assertThat(maker.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
        assertThat(maker.getOrderId(), Is.is(123L));
        assertThat(maker.getSymbol(), Is.is(3));
        assertThat(maker.getAccountId(), Is.is(29851L));

        ITradeEventsHandler.SpotExecutionReport taker = reports.get(4);
        assertThat(taker.executionType, Is.is(ITradeEventsHandler.ExecType.TRADE));
        assertThat(taker.getOrderId(), Is.is(276811L));
        assertThat(taker.getSymbol(), Is.is(3));
        assertThat(taker.getAccountId(), Is.is(10333L));

    }


    @Test
    public void shouldHandlerWithSingleReject() {

        OrderCommand cmd = samplePlaceOrderCommand();

        cmd.matcherEvent = MatcherTradeEvent.builder()
                .eventType(MatcherEventType.REJECT)
                .activeOrderCompleted(true)
                .size(8272L)
                .price(52201L)
                .nextEvent(null)
                .build();

        processor.accept(cmd, 192837L);

        verify(handler, times(2)).spotExecutionReport(tradeEventCaptor.capture());
        verify(handler, never()).futuresExecutionReport(any());
        verify(handler, never()).fundEventReport(any());

        List<ITradeEventsHandler.SpotExecutionReport> reports = tradeEventCaptor.getAllValues();
        assertThat(reports.size(), Is.is(2));

        ITradeEventsHandler.SpotExecutionReport newOrder = reports.get(0);
        assertThat(newOrder.executionType, Is.is(ITradeEventsHandler.ExecType.NEW));
        assertThat(newOrder.getOrderId(), Is.is(123L));
        assertThat(newOrder.getSymbol(), Is.is(3));
        assertThat(newOrder.getAccountId(), Is.is(29851L));
        assertThat(newOrder.commissionAsset, Is.is(2));
        assertThat(newOrder.getSymbol(), Is.is(3));
        assertThat(newOrder.price, Is.is(52200L));
        assertThat(newOrder.qty, Is.is(3200L));
        assertThat(newOrder.cumulativeQty, Is.is(0L));
        assertThat(newOrder.cumulativeQuoteQty, Is.is(0L));
        assertThat(newOrder.commissionAsset, Is.is(2));
        assertThat(newOrder.isMaker, Is.is(false));

        ITradeEventsHandler.SpotExecutionReport reject = reports.get(1);
        assertThat(reject.executionType, Is.is(ITradeEventsHandler.ExecType.REJECT));
        assertThat(reject.orderStatus, Is.is(ITradeEventsHandler.OrderStatus.REJECTED));
        assertThat(reject.getOrderId(), Is.is(123L));
        assertThat(reject.getSymbol(), Is.is(3));
        assertThat(reject.getAccountId(), Is.is(29851L));
        assertThat(reject.commissionAsset, Is.is(2));
        assertThat(reject.getSymbol(), Is.is(3));
        assertThat(reject.price, Is.is(52200L));
        assertThat(reject.qty, Is.is(3200L));
        assertThat(reject.cumulativeQty, Is.is(0L));
        assertThat(reject.cumulativeQuoteQty, Is.is(0L));
        assertThat(reject.commissionAsset, Is.is(2));
        assertThat(reject.isMaker, Is.is(false));
    }

    private OrderCommand sampleCancelCommand() {

        return OrderCommand.builder()
                .command(OrderCommandType.CANCEL_ORDER)
                .orderId(123L)
                .symbol(3)
                .price(12800L)
                .size(3L)
                .reserveBidPrice(12800L)
                .action(OrderAction.BID)
                .orderType(OrderType.GTC)
                .uid(29851L)
                .timestamp(1578930983745201L)
                .userCookie(44188)
                .resultCode(CommandResultCode.SUCCESS)
                .matcherEvent(null)
                .marketData(null)
                .build();
    }

    private OrderCommand sampleReduceCommand() {

        return OrderCommand.builder()
                .command(OrderCommandType.REDUCE_ORDER)
                .orderId(123L)
                .symbol(3)
                .price(52200L)
                .size(3200L)
                .reserveBidPrice(12800L)
                .action(OrderAction.BID)
                .orderType(OrderType.GTC)
                .uid(29851L)
                .timestamp(1578930983745201L)
                .userCookie(44188)
                .resultCode(CommandResultCode.SUCCESS)
                .matcherEvent(null)
                .marketData(null)
                .build();
    }

    private OrderCommand samplePlaceOrderCommand() {

        return OrderCommand.builder()
                .command(OrderCommandType.PLACE_ORDER)
                .orderId(123L)
                .symbol(3)
                .price(52200L)
                .size(3200L)
                .reserveBidPrice(12800L)
                .action(OrderAction.BID)
                .orderType(OrderType.IOC)
                .uid(29851L)
                .timestamp(1578930983745201L)
                .userCookie(44188)
                .resultCode(CommandResultCode.SUCCESS)
                .matcherEvent(null)
                .marketData(null)
                .marginMode(MarginMode.ISOLATED)
                .build();
    }

    private void verifyOriginalFields(OrderCommand source, OrderCommand result) {

        assertThat(source.command, Is.is(result.command));
        assertThat(source.orderId, Is.is(result.orderId));
        assertThat(source.symbol, Is.is(result.symbol));
        assertThat(source.price, Is.is(result.price));
        assertThat(source.size, Is.is(result.size));
        assertThat(source.reserveBidPrice, Is.is(result.reserveBidPrice));
        assertThat(source.action, Is.is(result.action));
        assertThat(source.orderType, Is.is(result.orderType));
        assertThat(source.uid, Is.is(result.uid));
        assertThat(source.timestamp, Is.is(result.timestamp));
        assertThat(source.userCookie, Is.is(result.userCookie));
        assertThat(source.resultCode, Is.is(result.resultCode));
    }

    private OrderCommand sampleBalanceAdjustCommand() {
        OrderCommand cmd = OrderCommand.builder()
                .command(OrderCommandType.BALANCE_ADJUSTMENT)
                .uid(301L)
                .symbol(30)
                .orderId(13143L)
                .price(12800L)
                .timestamp(1978930983745201L)
                .resultCode(CommandResultCode.SUCCESS).build();

        return cmd;
    }

    @Test
    public void shouldGenFundEventWhenBalanceChange() {
        OrderCommand cmd = sampleBalanceAdjustCommand();
        FundEvent event = FundEvent.builder()
                .uid(301L)
                .symbol(30)
                .orderId(13143L)
                .currency(20000)
                .build();
        cmd.takerFundEvents = event;

        processor.accept(cmd, 192837L);

        verify(handler, times(0)).spotExecutionReport(tradeEventCaptor.capture());
        verify(handler, never()).futuresExecutionReport(any());
        verify(handler, times(1)).fundEventReport(fundEventCaptor.capture());

        List<IFundEventsHandler.FundEventReport> fundEvents = fundEventCaptor.getAllValues();
        assertThat(fundEvents.size(), Is.is(1));
        IFundEventsHandler.FundEventReport report = fundEvents.get(0);
        assertThat(report.getEventType() == null, Is.is(true));
        assertThat(report.getBalances().getCurrency(), Is.is(20000));
    }
}