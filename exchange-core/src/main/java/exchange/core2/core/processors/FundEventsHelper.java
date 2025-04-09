package exchange.core2.core.processors;

import static exchange.core2.core.ExchangeCore.EVENTS_POOLING;

import java.util.function.Supplier;

import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.FundEvent.FundEventType;
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

    private FundEvent buildSpotEvent(long orderId, long uid, FundEventType type, int currency, long free, long locked) {
        FundEvent event = newFundEvent();
        event.orderId = orderId;
        event.uid = uid;
        event.eventType = type;
        event.currency = currency;
        event.free = free;
        event.locked = locked;
        return event;
    }

    private FundEvent buildFuturesEvent(long orderId, FundEventType type, SymbolPositionRecord position, long free, long locked) {
        FundEvent event = newFundEvent();
        event.orderId = orderId;
        event.eventType = type;
        event.uid = position.uid;
        event.currency = position.currency;
        event.symbol = position.symbol;
        event.direction = position.direction;
        event.position = position.openVolume;
        event.free = free;
        event.locked = locked;
        return event;
    }

    /**
     * ***********现货************
     */
    // 存款
    public FundEvent sendDepositEvent(final OrderCommand cmd, int currency, long free) {
        FundEvent event = buildSpotEvent(cmd.orderId, cmd.uid, FundEventType.DEPOSIT, currency, free, 0);
        cmd.fundEvents.add(event);
        return event;
    }

    // 提现
    public FundEvent sendWithdrawEvent(final OrderCommand cmd, int currency, long free) {
        FundEvent event = buildSpotEvent(cmd.orderId, cmd.uid, FundEventType.WITHDRAW, currency, free, 0);
        cmd.fundEvents.add(event);
        return event;
    }

    // 冻结
    public FundEvent sendLockEvent(final OrderCommand cmd, int currency, long free, long locked) {
        FundEvent event = buildSpotEvent(cmd.orderId, cmd.uid, FundEventType.LOCKED, currency, free, locked);
        cmd.fundEvents.add(event);
        return event;
    }

    // 解冻
    public FundEvent sendUnLockEvent(final MatcherTradeEvent ev, int currency, long free) {
        FundEvent event = buildSpotEvent(ev.matchedOrderId, ev.matchedOrderUid, FundEventType.UNLOCKED, currency, free, 0);
        ev.fundEvents.add(event);
        return event;
    }

    // 转移
    public FundEvent sendTransferEvent(final MatcherTradeEvent ev, long uid, int currency, long free) {
        FundEvent event = newFundEvent();
        event.eventType = FundEventType.TRANSFER;
        event.uid = uid;
        event.currency = currency;
        event.free = free;
        event.locked = 0;
        event.orderId = ev.matchedOrderId;
        ev.fundEvents.add(event);
        return event;
    }

    /**
     * ***********期货************
     */

    // 这里如果direction=empty说明是初始下单，还没有仓位
    public FundEvent sendLockPendingEvent(OrderCommand cmd, SymbolPositionRecord position, long free, long locked) {
        FundEvent event = buildFuturesEvent(cmd.orderId, FundEventType.LOCK_PENDING, position, free, locked);
        cmd.fundEvents.add(event);
        return event;
    }

    public FundEvent sendUnlockPendingEvent(MatcherTradeEvent ev, SymbolPositionRecord position, long free, long locked) {
        FundEvent event = buildFuturesEvent(ev.matchedOrderId, FundEventType.UNLOCK_PENDING, position, free, locked);
        ev.fundEvents.add(event);
        return event;
    }

    public FundEvent sendClosePositionEvent(MatcherTradeEvent ev, boolean isLiquidation, SymbolPositionRecord position, long free, long locked,
                                            long sizeClosed, long avgOpenPrice, long price, long fee, long pnl) {
        FundEvent event = buildFuturesEvent(ev.matchedOrderId, isLiquidation ? FundEventType.LIQUIDATION : FundEventType.CLOSE_POSITION, position, free, locked);
        event.positionChanged = sizeClosed;
        event.openPriceAvg = avgOpenPrice;
        event.tradePrice = price;
        event.fee = fee;
        event.pnl = pnl;
        ev.fundEvents.add(event);
        return event;
    }

    public FundEvent sendOpenPositionEvent(MatcherTradeEvent ev, SymbolPositionRecord position, long free, long locked,
                                           long sizeOpened, long price, long fee) {
        FundEvent event = buildFuturesEvent(ev.matchedOrderId, FundEventType.OPEN_POSITION, position, free, locked);
        event.positionChanged = sizeOpened;
        event.tradePrice = price;
        event.fee = fee;
        ev.fundEvents.add(event);
        return event;
    }

    // 通知调整保证金
    public FundEvent sendMarginAdjustmentEvent(final SymbolPositionRecord position, long free, long locked) {
        FundEvent event = buildFuturesEvent(0, FundEventType.MARGIN_ALERT, position, free, locked);
        event.positionChanged = 0; // 无清算
        event.openPriceAvg = position.openVolume > 0 ? position.openPriceSum / position.openVolume : 0; // 平均开仓价格
        event.tradePrice = 0; // 无清算价格
        event.fee = 0; // 无交易费用
        event.pnl = 0; // 无盈亏变动
        return event;
    }

    // 通知强平
    public FundEvent sendLiquidationAlertEvent(long orderId, SymbolPositionRecord position, long free, long locked,
                                               long markPrice, long sizeToLiquidate) {
        FundEvent event = buildFuturesEvent(orderId, FundEventType.LIQUIDATION_ALERT, position, free, locked);
        event.positionChanged = sizeToLiquidate; // 清算仓位
        event.openPriceAvg = position.openVolume > 0 ? position.openPriceSum / position.openVolume : 0;
        event.tradePrice = markPrice;  // 清算价格
        event.fee = 0; // 无交易费用
        event.pnl = 0; // 无盈亏变动
        return event;
    }

    // 生成盈亏结算事件 (PNL_SETTLEMENT)。
    public FundEvent sendPnlSettlementEvent(OrderCommand cmd, SymbolPositionRecord position, long free, long locked, long settledPnl) {
        FundEvent event = buildFuturesEvent(cmd.orderId, FundEventType.PNL_SETTLEMENT, position, free, locked);
        event.positionChanged = 0;
        event.openPriceAvg = position.openVolume > 0 ? position.openPriceSum / position.openVolume : 0;
        event.tradePrice = 0;
        event.fee = 0;
        event.pnl = settledPnl; // 结算盈亏
        cmd.fundEvents.add(event);
        return event;
    }

    private FundEvent newFundEvent() {
        if (EVENTS_POOLING) {
            final FundEvent event = eventSupplier.get();
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
            event.positionChanged = 0;
            event.openPriceAvg = 0;
            event.tradePrice = 0;
            event.fee = 0;
            event.pnl = 0;
            return event;
        } else {
            return new FundEvent();
        }
    }
}
