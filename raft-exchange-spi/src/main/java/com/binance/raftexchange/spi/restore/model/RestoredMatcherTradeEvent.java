package com.binance.raftexchange.spi.restore.model;

import com.binance.raftexchange.stubs.response.MatcherEventType;

import java.math.BigDecimal;

/**
 * {@link com.binance.raftexchange.spi.restore.TradeEventRestorer} 从
 * {@link com.binance.raftexchange.stubs.response.MatcherTradeEvent} 还原出的撮合事件业务视图。
 *
 * <p>Scale 体系：
 *   base scale (baseScaleK)  → size
 *   quote scale (quoteScaleK) → price / bidderHoldPrice
 * </p>
 *
 * <p>PB 内的 next_event 链表会在还原阶段递归展开为 {@link #next} 链；
 * 链尾 next 为 null。</p>
 */
public final class RestoredMatcherTradeEvent {

    public MatcherEventType eventType;
    public int              section;
    public boolean          activeOrderCompleted;
    public long             matchedOrderId;
    public long             matchedOrderUid;
    public boolean          matchedOrderCompleted;

    public BigDecimal price          = BigDecimal.ZERO;  // quote scale
    public BigDecimal size           = BigDecimal.ZERO;  // base scale
    public BigDecimal bidderHoldPrice = BigDecimal.ZERO; // quote scale

    /** 链表下一项；链尾为 null。 */
    public RestoredMatcherTradeEvent next;

    @Override
    public String toString() {
        return "RestoredMatcherTradeEvent{"
            + "eventType=" + eventType
            + ", matchedOrderId=" + matchedOrderId
            + ", matchedOrderUid=" + matchedOrderUid
            + ", size=" + size + ", price=" + price
            + ", bidderHoldPrice=" + bidderHoldPrice
            + ", activeOrderCompleted=" + activeOrderCompleted
            + ", matchedOrderCompleted=" + matchedOrderCompleted
            + ", section=" + section
            + ", hasNext=" + (next != null)
            + '}';
    }
}
