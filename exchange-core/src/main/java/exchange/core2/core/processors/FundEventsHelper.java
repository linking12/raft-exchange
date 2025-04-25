package exchange.core2.core.processors;

import static exchange.core2.core.ExchangeCore.EVENTS_POOLING;

import java.util.function.Supplier;

import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.FundEvent.FundEventType;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.cmd.OrderCommand;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class FundEventsHelper {
    private final Supplier<FundEvent> eventSupplier;
    private final int riskEngineShardId;
    private final int numShards;

    private FundEvent eventsChainHead;

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
    public FundEvent sendDepositEvent(OrderCommand cmd, int currency, long free) {
        FundEvent event = buildSpotEvent(cmd.orderId, cmd.uid, FundEventType.DEPOSIT, currency, free, 0);
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    // 提现
    public FundEvent sendWithdrawEvent(OrderCommand cmd, int currency, long free) {
        FundEvent event = buildSpotEvent(cmd.orderId, cmd.uid, FundEventType.WITHDRAW, currency, free, 0);
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    // 冻结
    public FundEvent sendLockEvent(OrderCommand cmd, int currency, long free, long locked) {
        FundEvent event = buildSpotEvent(cmd.orderId, cmd.uid, FundEventType.LOCKED, currency, free, locked);
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    // 解冻
    public FundEvent sendUnLockEvent(OrderCommand cmd, int currency, long free) {
        FundEvent event = buildSpotEvent(cmd.orderId, cmd.uid, FundEventType.UNLOCKED, currency, free, 0);
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    // 转移
    public FundEvent sendTransferEvent(OrderCommand cmd, long orderId, long uid, int currency, long free) {
        FundEvent event = newFundEvent();
        event.eventType = FundEventType.TRANSFER;
        event.orderId = orderId;
        event.uid = uid;
        event.currency = currency;
        event.free = free;
        event.locked = 0;
        addFundEvent(cmd, orderId, event);
        return event;
    }

    /**
     * ***********期货************
     */

    // 这里如果direction=empty说明是初始下单，还没有仓位
    public FundEvent sendLockPendingEvent(OrderCommand cmd, SymbolPositionRecord position, long free, long locked) {
        FundEvent event = buildFuturesEvent(cmd.orderId, FundEventType.LOCK_PENDING, position, free, locked);
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    public FundEvent sendUnlockPendingEvent(OrderCommand cmd, long orderId, SymbolPositionRecord position, long free, long locked) {
        FundEvent event = buildFuturesEvent(orderId, FundEventType.UNLOCK_PENDING, position, free, locked);
        addFundEvent(cmd, orderId, event);
        return event;
    }

    public FundEvent sendClosePositionEvent(OrderCommand cmd, long orderId, boolean isLiquidation, SymbolPositionRecord position, long free, long locked,
        long sizeClosed, long avgOpenPrice, long price, long fee, long pnl) {
        FundEvent event = buildFuturesEvent(orderId, isLiquidation ? FundEventType.LIQUIDATION : FundEventType.CLOSE_POSITION, position, free, locked);
        event.positionChanged = sizeClosed;
        event.openPriceAvg = avgOpenPrice;
        event.tradePrice = price;
        event.fee = fee;
        event.pnl = pnl;
        addFundEvent(cmd, orderId, event);
        return event;
    }

    public FundEvent sendOpenPositionEvent(OrderCommand cmd, long orderId, SymbolPositionRecord position, long free, long locked, long sizeOpened, long price,
        long fee) {
        FundEvent event = buildFuturesEvent(orderId, FundEventType.OPEN_POSITION, position, free, locked);
        event.positionChanged = sizeOpened;
        event.tradePrice = price;
        event.fee = fee;
        addFundEvent(cmd, orderId, event);
        return event;
    }

    // 通知调整保证金
    public FundEvent sendMarginAdjustmentEvent(SymbolPositionRecord position, long free, long locked) {
        FundEvent event = buildFuturesEvent(0, FundEventType.MARGIN_ALERT, position, free, locked);
        event.positionChanged = 0; // 无清算
        event.openPriceAvg = position.openVolume > 0 ? position.openPriceSum / position.openVolume : 0; // 平均开仓价格
        event.tradePrice = 0; // 无清算价格
        event.fee = 0; // 无交易费用
        event.pnl = 0; // 无盈亏变动
        return event;
    }

    // 通知强平
    public FundEvent sendLiquidationAlertEvent(long orderId, SymbolPositionRecord position, long free, long locked, long markPrice, long sizeToLiquidate) {
        FundEvent event = buildFuturesEvent(orderId, FundEventType.LIQUIDATION_ALERT, position, free, locked);
        event.positionChanged = sizeToLiquidate; // 清算仓位
        event.openPriceAvg = position.openVolume > 0 ? position.openPriceSum / position.openVolume : 0;
        event.tradePrice = markPrice; // 清算价格
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
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    private void addFundEvent(OrderCommand cmd, long orderId, FundEvent event) {
        if (orderId == cmd.orderId) {
            if (cmd.takerFundEvents == null) {
                cmd.takerFundEvents = event;
            } else {
                FundEvent current = cmd.takerFundEvents;
                while (current.nextEvent != null) {
                    current = current.nextEvent;
                }
                current.nextEvent = event;
            }
        } else {
            if (cmd.makerFundEventsByShard == null) {
                cmd.makerFundEventsByShard = new FundEvent[numShards];
            }
            FundEvent head = cmd.makerFundEventsByShard[riskEngineShardId];
            if (head == null) {
                cmd.makerFundEventsByShard[riskEngineShardId] = event;
            } else {
                FundEvent current = head;
                while (current.nextEvent != null) {
                    current = current.nextEvent;
                }
                current.nextEvent = event;
            }
        }
    }

    private FundEvent newFundEvent() {
        if (EVENTS_POOLING) {
            if (eventsChainHead == null) {
                eventsChainHead = eventSupplier.get();
            }
            final FundEvent event = eventsChainHead;
            eventsChainHead = eventsChainHead.nextEvent;
            event.nextEvent = null; // 断掉链表，借出的对象应该和下面new的对象等价
            event.processed = false;
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
