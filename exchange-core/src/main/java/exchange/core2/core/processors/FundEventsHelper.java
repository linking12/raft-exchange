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
        cmd.fundEvents.add(event);
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
        cmd.fundEvents.add(event);
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
        cmd.fundEvents.add(event);
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
        cmd.fundEvents.add(event);
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
     * ***********期货************
     */
    // 强平
    public FundEvent sendLiquidationEvent(final OrderCommand cmd, SymbolPositionRecord position, long free, long locked, long remainingPosition,
        long sizeLiquidated, long liquidationPrice, long fee, long pnl) {
        FundEvent event = newFundEvent();
        event.eventType = FundEvent.FundEventType.LIQUIDATION;
        event.uid = position.uid;
        event.currency = position.currency;
        event.free = free;
        event.locked = locked;
        event.symbol = position.symbol;
        event.direction = position.direction;
        event.position = remainingPosition; // 剩余持仓
        event.positionLiquidated = sizeLiquidated; // 强平数量
        event.openPriceAvg = position.openVolume > 0 ? position.openPriceSum / position.openVolume : 0; // 剩余仓位均价
        event.liquidationPrice = liquidationPrice; // 强平价格
        event.fee = fee;
        event.pnl = pnl;
        event.orderId = cmd.orderId;
        cmd.fundEvents.add(event);
        return event;
    }

    // 调整保证金
    public FundEvent sendMarginAdjustmentEvent(final OrderCommand cmd, final SymbolPositionRecord position, long free, long locked) {
        FundEvent event = newFundEvent();
        event.eventType = FundEvent.FundEventType.MARGIN_ADJUSTMENT;
        event.uid = position.uid;
        event.currency = position.currency;
        event.free = free;
        event.locked = locked;
        event.symbol = position.symbol;
        event.direction = position.direction;
        event.position = position.openVolume; // 当前仓位数量
        event.positionLiquidated = 0; // 无清算
        event.openPriceAvg = position.openVolume > 0 ? position.openPriceSum / position.openVolume : 0; // 平均开仓价格
        event.liquidationPrice = 0; // 无清算价格
        event.fee = 0; // 无交易费用
        event.pnl = 0; // 无盈亏变动
        event.orderId = cmd.orderId;
        cmd.fundEvents.add(event);
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
            event.positionLiquidated = 0;
            event.openPriceAvg = 0;
            event.liquidationPrice = 0;
            event.fee = 0;
            event.pnl = 0;
            return event;
        } else {
            return new FundEvent();
        }
    }
}
