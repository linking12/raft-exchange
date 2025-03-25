package exchange.core2.core.processors;

import static exchange.core2.core.ExchangeCore.EVENTS_POOLING;

import java.util.function.Supplier;

import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.cmd.OrderCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class FundEventsHelper {
    public static final FundEventsHelper NON_POOLED_EVENTS_HELPER = new FundEventsHelper(FundEvent::new);
    private final Supplier<FundEvent> eventSupplier;
    private FundEvent eventPooled;

    /**
     * ***********现货************
     */
    // 存款
    public FundEvent sendDepositEvent(final OrderCommand cmd, long uid, int currency, long free) {
        FundEvent event = newFundEvent();
        event.eventType = FundEvent.FundEventType.DEPOSIT;
        event.uid = uid;
        event.currency = currency;
        event.free = free;
        event.locked = 0;
        event.orderId = cmd.orderId;
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
        event.locked = 0;
        event.orderId = cmd.orderId;
        cmd.fundEvent = event;
        return event;
    }

    // 冻结
    public FundEvent sendLockEvent(final OrderCommand cmd, long uid, int currency, long free, long locked) {
        FundEvent event = newFundEvent();
        event.eventType = FundEvent.FundEventType.LOCKED;
        event.uid = uid;
        event.currency = currency;
        event.free = free;
        event.locked = locked;
        event.orderId = cmd.orderId;
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
        event.locked = 0;
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
        event.locked = 0;
        event.orderId = ev.matchedOrderId;
        ev.fundEvent = event;
        return event;
    }

    /**
     * ***********现货************
     */

    // 开仓
    public FundEvent sendOpenPositionEvent(final OrderCommand cmd, long uid, int symbol, PositionDirection direction, long position, long price, int currency,
        long free, long locked, long marginDelta) {
        FundEvent event = newFundEvent();
        event.eventType = FundEvent.FundEventType.OPEN_POSITION;
        event.uid = uid;
        event.currency = currency;
        event.free = free;
        event.locked = locked; // 当前冻结保证金
        event.symbol = symbol;
        event.direction = direction;
        event.position = position; // 用户剩余持仓
        event.price = price;
        event.orderId = cmd.orderId;
        cmd.fundEvent = event;
        return event;
    }

    // 平仓
    public FundEvent sendClosePositionEvent(final MatcherTradeEvent ev, long uid, int symbol, PositionDirection direction, long position, long price, long pnl,
        int currency, long free, long locked) {
        FundEvent event = newFundEvent();
        event.eventType = FundEvent.FundEventType.CLOSE_POSITION;
        event.uid = uid;
        event.currency = currency;
        event.free = free;
        event.locked = locked; // 更新后的冻结保证金
        event.symbol = symbol;
        event.direction = direction;
        event.position = position; // 用户剩余持仓
        event.price = price;
        event.pnl = pnl;
        event.orderId = ev.matchedOrderId;
        ev.fundEvent = event;
        return event;
    }

    // 强平
    public FundEvent sendLiquidationEvent(final OrderCommand cmd, SymbolPositionRecord positionRecored, long free, long locked, long positon) {
        FundEvent event = newFundEvent();
        event.eventType = FundEvent.FundEventType.LIQUIDATION;
        event.uid = positionRecored.uid;
        event.currency = positionRecored.currency;
        event.free = free;
        event.locked = locked; // 更新后的冻结保证金
        event.symbol = positionRecored.symbol;
        event.direction = positionRecored.direction;
        event.position = positon; // 用户剩余持仓
        event.price = positionRecored.openPriceSum;
        event.orderId = cmd.orderId;
        cmd.fundEvent = event;
        return event;
    }

    // 保证金调整事件
    public FundEvent sendMarginAdjustmentEvent(final OrderCommand cmd, long uid, int currency, long marginDelta, long free, long locked) {
        FundEvent event = newFundEvent();
        event.eventType = FundEvent.FundEventType.MARGIN_ADJUSTMENT;
        event.uid = uid;
        event.currency = currency;
        event.free = free;
        event.locked = locked; // 更新后的冻结保证金
        event.orderId = cmd.orderId;
        cmd.fundEvent = event;
        return event;
    }

    // 盈亏结算事件
    public FundEvent sendPnlSettlementEvent(final OrderCommand cmd, long uid, int symbol, long pnl, int currency, long free, long locked, long timestamp,
        long orderId) {
        FundEvent event = newFundEvent();
        event.eventType = FundEvent.FundEventType.PNL_SETTLEMENT;
        event.uid = uid;
        event.currency = currency;
        event.free = free;
        event.locked = locked; // 当前冻结保证金
        event.symbol = symbol;
        event.pnl = pnl;
        event.orderId = orderId;
        event.timestamp = timestamp; // 手动指定时间戳
        cmd.fundEvent = event;
        return event;
    }

    private FundEvent newFundEvent() {
        if (EVENTS_POOLING) {
            if (eventPooled == null) {
                eventPooled = eventSupplier.get();
            }
            final FundEvent event = eventPooled;
            eventPooled = null;
            event.eventType = null;
            event.section = 0;
            event.orderId = 0;
            event.uid = 0;
            event.currency = 0;
            event.free = 0;
            event.locked = 0;
            event.symbol = 0;
            event.direction = PositionDirection.EMPTY;
            event.position = 0;
            event.price = 0;
            event.pnl = 0;
            event.timestamp = 0;
            return event;
        } else {
            return new FundEvent();
        }
    }
}
