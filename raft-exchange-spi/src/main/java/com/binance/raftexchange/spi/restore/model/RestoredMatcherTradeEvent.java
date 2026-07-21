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

    public MatcherEventType eventType;             // 撮合事件类型（TRADE/REDUCE/REJECT…）
    public int              section;               // 事件分段（taker / maker 桶归属）
    public boolean          activeOrderCompleted;  // 主动单（taker）是否已完全成交
    public long             matchedOrderId;        // 被撮合的对手单 id
    public long             matchedOrderUid;       // 对手单 uid
    public boolean          matchedOrderCompleted; // 对手单是否已完全成交

    public BigDecimal price          = BigDecimal.ZERO;  // 成交价（quote scale）
    public BigDecimal size           = BigDecimal.ZERO;  // 成交量（base scale）
    public BigDecimal bidderHoldPrice = BigDecimal.ZERO; // 买方冻结价，撤单返还差价用（quote scale）

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
