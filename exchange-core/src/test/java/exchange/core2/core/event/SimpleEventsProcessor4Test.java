package exchange.core2.core.event;

import exchange.core2.core.IEventsHandler;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.api.*;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.agrona.collections.MutableBoolean;
import org.agrona.collections.MutableLong;
import org.agrona.collections.MutableReference;

import java.util.ArrayList;
import java.util.List;

@Getter
@Slf4j
public class SimpleEventsProcessor4Test extends SimpleEventsProcessor {

    private IEventsHandler4Test eventsHandler;

    public SimpleEventsProcessor4Test(IEventsHandler4Test eventsHandler) {
        super(eventsHandler);
        this.eventsHandler = eventsHandler;
    }

    @Override
    public void sendTradeEvent(OrderCommand cmd) {

        final MutableBoolean takerOrderCompleted = new MutableBoolean(false);
        final MutableLong mutableLong = new MutableLong(0L);
        final List<IEventsHandler.Trade> trades = new ArrayList<>();

        final MutableReference<IEventsHandler.RejectEvent> rejectEvent = new MutableReference<>(null);

        cmd.processMatcherEvents(evt -> {

            if (evt.eventType == MatcherEventType.TRADE) {

                final IEventsHandler.Trade trade =
                        new IEventsHandler.Trade(evt.matchedOrderId, evt.matchedOrderUid, evt.matchedOrderCompleted, evt.price, evt.size);

                trades.add(trade);
                mutableLong.value += evt.size;

                if (evt.activeOrderCompleted) {
                    takerOrderCompleted.value = true;
                }

            } else if (evt.eventType == MatcherEventType.REJECT) {

                rejectEvent.set(new IEventsHandler.RejectEvent(cmd.symbol, evt.size, evt.price, cmd.orderId, cmd.uid, cmd.timestamp));
            }
            // 发送trade下面的fundEvent，主要是资金转移
            evt.fundEvents.forEach(this::sendFundEvents);
        });

        if (!trades.isEmpty()) {

            final IEventsHandler.TradeEvent evt = new IEventsHandler.TradeEvent(cmd.symbol, mutableLong.value, cmd.orderId, cmd.uid, cmd.action,
                    takerOrderCompleted.value, cmd.timestamp, trades);

            eventsHandler.tradeEvent(evt);
        }

        if (rejectEvent.ref != null) {
            eventsHandler.rejectEvent(rejectEvent.ref);
        }

    }
    @Override
    public void sendApiCommandResult(ApiCommand cmd, CommandResultCode resultCode, long timestamp, long seq) {
        cmd.timestamp = timestamp;
        final IEventsHandler4Test.ApiCommandResult commandResult = new IEventsHandler4Test.ApiCommandResult(cmd, resultCode, seq);
        eventsHandler.commandResult(commandResult);
    }
}
