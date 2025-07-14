package exchange.core2.core.processors;

import static exchange.core2.core.ExchangeCore.EVENTS_POOLING;

import java.util.function.Supplier;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.FundEvent.FundEventType;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

@Slf4j
@RequiredArgsConstructor
public class FundEventsHelper {
    private final Supplier<FundEvent> eventSupplier;
    private final int riskEngineShardId;
    private final int numShards;
    @Setter
    private SymbolSpecificationProvider symbolSpecificationProvider;
    @Setter
    private UserProfileService userProfileService;
    @Setter
    private IntObjectHashMap<LastPriceCacheRecord> lastPriceCache;

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
        event.openVolume = position.openVolume;
        event.openInitMarginSum = position.openInitMarginSum;
        event.openPriceSum = position.openPriceSum;
        event.profit = position.profit;
        event.pendingSellSize = position.pendingSellSize;
        event.pendingBuySize = position.pendingBuySize;
        event.pendingSellAvgPrice = position.pendingSellAvgPrice;
        event.pendingBuyAvgPrice = position.pendingBuyAvgPrice;
        event.leverage = position.getLeverage();
        event.marginMode = position.marginMode;
        event.extraMargin = position.extraMargin;
        LastPriceCacheRecord priceRecord = lastPriceCache.get(position.symbol);
        CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
        calc(event, position, spec, priceRecord);
        event.markPrice = priceRecord.markPrice;
        event.free = free;
        event.locked = locked;
        return event;
    }

    private void calc(FundEvent event, SymbolPositionRecord position, CoreSymbolSpecification spec, LastPriceCacheRecord priceRecord) {
        if (position.openVolume == 0) {
            return; // 无持仓，不计算
        }
        long balance = 0;
        long totalPnl = 0;
        long totalMM = 0;
        long totalMargin = 0;
        if (position.marginMode == MarginMode.CROSS) {
            UserProfile userProfile = userProfileService.getUserProfile(position.uid);
            totalPnl = userProfile.positions.select(pos -> pos.marginMode == MarginMode.CROSS && pos.currency == position.currency)
                .sumOfLong(pos -> pos.estimateProfit(priceRecord));
            totalMM = userProfile.positions.select(pos -> pos.marginMode == MarginMode.CROSS && pos.currency == position.currency)
                .sumOfLong(pos -> pos.calculateMaintenanceMargin(spec, priceRecord));
            balance = userProfile.accounts.get(position.currency);
            totalMargin = balance + totalPnl;
        } else {
            totalMargin = position.openInitMarginSum + position.estimateUnrealizedProfit(priceRecord) + position.extraMargin;
        }
        event.unrealizedProfit = position.estimateUnrealizedProfit(priceRecord);
        event.liquidationPrice = position.estimateLiquidationPrice(spec, priceRecord, balance, totalPnl, totalMM);
        event.marginRatioScaleK = position.estimateMarginRatioScaleK(spec, priceRecord, totalMargin);
    }

    /**
     * ***********现货************
     */
    // 存款
    public FundEvent sendDepositEvent(OrderCommand cmd, int currency, long free, long locked) {
        FundEvent event = buildSpotEvent(cmd.orderId, cmd.uid, FundEventType.DEPOSIT, currency, free, locked);
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    // 提现
    public FundEvent sendWithdrawEvent(OrderCommand cmd, int currency, long free, long locked) {
        FundEvent event = buildSpotEvent(cmd.orderId, cmd.uid, FundEventType.WITHDRAW, currency, free, locked);
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    // 冻结
    public FundEvent sendLockEvent(OrderCommand cmd, int symbol, int currency, long free, long locked) {
        FundEvent event = buildSpotEvent(cmd.orderId, cmd.uid, FundEventType.LOCKED, currency, free, locked);
        event.symbol = symbol;
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    // 解冻
    public FundEvent sendUnLockEvent(OrderCommand cmd, int symbol, int currency, long free, long locked) {
        FundEvent event = buildSpotEvent(cmd.orderId, cmd.uid, FundEventType.UNLOCKED, currency, free, locked);
        event.symbol = symbol;
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    // 转移
    public FundEvent sendTransferEvent(OrderCommand cmd, long orderId, long uid, int symbol, int currency, long free, long locked) {
        FundEvent event = buildSpotEvent(orderId, uid, FundEventType.TRANSFER, currency, free, locked);
        event.symbol = symbol;
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
        long sizeClosed, long price, long fee) {
        FundEvent event = buildFuturesEvent(orderId, isLiquidation ? FundEventType.LIQUIDATION : FundEventType.CLOSE_POSITION, position, free, locked);
        event.tradeSize = sizeClosed;
        event.tradePrice = price;
        event.fee = fee;
        addFundEvent(cmd, orderId, event);
        return event;
    }

    public FundEvent sendOpenPositionEvent(OrderCommand cmd, long orderId, SymbolPositionRecord position, long free, long locked, long sizeOpened, long price,
        long fee) {
        FundEvent event = buildFuturesEvent(orderId, FundEventType.OPEN_POSITION, position, free, locked);
        event.tradeSize = sizeOpened;
        event.tradePrice = price;
        event.fee = fee;
        addFundEvent(cmd, orderId, event);
        return event;
    }

    // 通知调整保证金
    public FundEvent sendMarginAlertEvent(SymbolPositionRecord position) {
        return buildFuturesEvent(0, FundEventType.MARGIN_ALERT, position, 0, 0);
    }

    // 通知强平
    public FundEvent sendLiquidationAlertEvent(long orderId, SymbolPositionRecord position, long markPrice, long sizeToLiquidate) {
        FundEvent event = buildFuturesEvent(orderId, FundEventType.LIQUIDATION_ALERT, position, 0, 0);
        event.tradeSize = sizeToLiquidate;
        event.tradePrice = markPrice;
        return event;
    }

    public FundEvent sendMarginAdjustmentEvent(OrderCommand cmd, SymbolPositionRecord position, long free, long locked) {
        FundEvent event = buildFuturesEvent(cmd.orderId, FundEventType.MARGIN_ADJUST, position, free, locked);
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    public FundEvent sendMarginRefundEvent(OrderCommand cmd, long orderId, SymbolPositionRecord position, long free, long locked) {
        FundEvent event = buildFuturesEvent(cmd.orderId, FundEventType.MARGIN_REFUND, position, free, locked);
        addFundEvent(cmd, orderId, event);
        return event;
    }

    public FundEvent sendFundingFeeEvent(OrderCommand cmd, SymbolPositionRecord position, long fee) {
        FundEvent event = buildFuturesEvent(cmd.orderId, FundEventType.FUNDINGFEE_SETTLEMENT, position, 0, 0);
        event.fee = fee; // 资金费用
        addFundEvent(cmd, cmd.orderId, event);
        return event;
    }

    // 生成盈亏结算事件 (PNL_SETTLEMENT)。
    public FundEvent sendPnlSettlementEvent(OrderCommand cmd, SymbolPositionRecord position, long free, long locked, long settledPrice) {
        FundEvent event = buildFuturesEvent(cmd.orderId, FundEventType.PNL_SETTLEMENT, position, free, locked);
        event.tradePrice = settledPrice; // 结算价格
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
            event.orderId = 0;
            event.uid = 0;
            event.currency = 0;
            event.free = 0;
            event.locked = 0;
            event.symbol = 0;
            event.direction = PositionDirection.EMPTY;
            event.openVolume = 0;
            event.openInitMarginSum = 0;
            event.openPriceSum = 0;
            event.profit = 0;
            event.pendingSellSize = 0;
            event.pendingBuySize = 0;
            event.pendingSellAvgPrice = 0;
            event.pendingBuyAvgPrice = 0;
            event.leverage = 0;
            event.marginMode = null;
            event.extraMargin = 0;
            event.unrealizedProfit = 0;
            event.liquidationPrice = 0;
            event.marginRatioScaleK = 0;
            event.tradeSize = 0;
            event.tradePrice = 0;
            event.fee = 0;
            event.markPrice = 0;
            return event;
        } else {
            return new FundEvent();
        }
    }
}
