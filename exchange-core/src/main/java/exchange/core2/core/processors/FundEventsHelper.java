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

    // 存款
    public FundEvent sendDepositEvent(final OrderCommand cmd, long uid, int currency, long free) {
        FundEvent event = newFundEvent();
        event.eventType = FundEvent.FundEventType.DEPOSIT;
        event.uid = uid;
        event.currency = currency;
        event.free = free;
        event.orderId = cmd.orderId;
        event.loked = 0;
        event.positionDelta = 0;
        cmd.fundEvent = event;
        return event;
    }

    // 提现
    public FundEvent sendWithdrawEvent(final OrderCommand cmd, long uid, int currency, long free) {
        FundEvent event = newFundEvent();
        event.eventType = FundEvent.FundEventType.WITHDRAW;
        event.uid = uid;
        event.currency = currency;
        event.free = free;
        event.orderId = cmd.orderId;
        event.loked = 0;
        event.positionDelta = 0;
        cmd.fundEvent = event;
        return event;
    }

    // 冻结
    public FundEvent sendLockEvent(final OrderCommand cmd, long uid, int currency, long free, long locked) {
        FundEvent event = newFundEvent();
        event.eventType = FundEvent.FundEventType.LOCKED;
        event.uid = uid;
        event.currency = currency;
        event.free = locked;
        event.orderId = cmd.orderId;
        event.positionDelta = 0;
        cmd.fundEvent = event;
        return event;
    }

    // 解冻
    public FundEvent sendUnLockEvent(final OrderCommand cmd, long uid, int currency, long free) {
        FundEvent event = newFundEvent();
        event.eventType = FundEvent.FundEventType.UNLOCKED;
        event.uid = uid;
        event.currency = currency;
        event.free = free;
        event.orderId = cmd.orderId;
        event.loked = 0;
        event.positionDelta = 0;
        cmd.fundEvent = event;
        return event;
    }

    // 转移
    public FundEvent sendTransferEvent(final MatcherTradeEvent ev, long uid, int currency, long free) {
        FundEvent event = newFundEvent();
        event.eventType = FundEvent.FundEventType.TRANSFER;
        event.uid = uid;
        event.currency = currency;
        event.free = free;
        event.loked = 0;
        event.orderId = ev.matchedOrderId;
        event.positionDelta = 0;
        ev.fundEvent = event;
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
