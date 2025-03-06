package exchange.core2.core.processors;

import static exchange.core2.core.ExchangeCore.EVENTS_POOLING;

import java.util.function.Supplier;

import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.cmd.OrderCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class FundEventsHelper {
    public static final FundEventsHelper NON_POOLED_EVENTS_HELPER = new FundEventsHelper(FundEvent::new);
    private final Supplier<FundEvent> eventSupplier;
    private FundEvent eventPooled;

    public FundEvent sendFreezeEvent(final OrderCommand cmd, long uid, int currency, long free, long locked) {
        FundEvent event = newFundEvent();
        event.eventType = FundEvent.FundEventType.FREEZE;
        event.uid = uid;
        event.currency = currency;
        event.free = locked;
        event.orderId = cmd.orderId;
        event.fee = 0;
        event.positionDelta = 0;
        return event;
    }

    public FundEvent sendReleaseEvent(final OrderCommand cmd, long uid, int currency, long free, long locked) {
        FundEvent event = newFundEvent();
        event.eventType = FundEvent.FundEventType.RELEASE;
        event.uid = uid;
        event.currency = currency;
        event.free = free;
        event.loked = locked;
        event.orderId = cmd.orderId;
        event.fee = 0;
        event.positionDelta = 0;
        return event;
    }

    public FundEvent sendTransferEvent(final MatcherTradeEvent ev, long uid, int currency, long free, long locked,
        long fee) {
        FundEvent event = newFundEvent();
        event.eventType = FundEvent.FundEventType.TRANSFER;
        event.uid = uid;
        event.currency = currency;
        event.free = free;
        event.fee = fee;
        event.loked = locked;
        event.orderId = ev.matchedOrderId;
        event.fee = 0;
        event.positionDelta = 0;
        return event;
    }

    private FundEvent newFundEvent() {
        if (EVENTS_POOLING) {
            if (eventPooled == null) {
                eventPooled = eventSupplier.get();
            }
            final FundEvent event = eventPooled;
            eventPooled = null;
            return event;
        } else {
            return new FundEvent();
        }
    }
}
