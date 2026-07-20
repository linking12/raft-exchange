package com.binance.raftexchange.spi.restore.model;

import com.binance.raftexchange.stubs.ExecType;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderStatus;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.PositionMode;
import com.binance.raftexchange.stubs.SymbolType;

import java.math.BigDecimal;

/**
 * {@link com.binance.raftexchange.spi.restore.TradeEventRestorer} 从 FuturesExecutionReportPB
 * 还原出的合约成交业务视图，所有金额/数量已按对应 scale 体系从 long 还原为 BigDecimal。
 *
 * <p>Scale 体系：
 *   orderQty scale (orderQtyScale)         → orderQty / lastQty / cumQty
 *   price scale (priceScale)               → price / lastPx / avgPx
 *   product scale (orderQty × price)       → cumQuoteQty / fee
 * </p>
 *
 * <p>fee 始终以 quote 币种结算（{@code feeAssetId = spec.quoteCurrency}），
 * 引擎计算为 {@code (size × price × takerFee) / feeScaleK}，结果落在 product scale。
 * feeAssetId 是 currency ID，无 scale 概念，直接 pass-through。</p>
 *
 * <p><b>边界</b>：固定费率模式（{@code spec.feeScaleK == 0}）下 fee 编码为
 * {@code size × takerFee}，scale 不再是 product；该模式多用于测试/历史合约，
 * 此 View 不针对其特殊还原，需要时请单独处理。</p>
 */
public final class RestoredFuturesExecution {

    public long         uniId;
    public ExecType     executionType;
    public OrderStatus  orderStatus;
    public int          symbol;
    public long         userId;
    public long         clOrdId;
    public long         orderId;
    public OrderType    orderType;
    public OrderAction  side;
    public long         counterpartyId;
    public long         createTime;
    public long         execId;
    public SymbolType   contractType;
    public PositionMode positionSide;
    public boolean      isMaker;

    // orderQty scale
    public BigDecimal orderQty = BigDecimal.ZERO;
    public BigDecimal lastQty  = BigDecimal.ZERO;
    public BigDecimal cumQty   = BigDecimal.ZERO;

    // price scale
    public BigDecimal price = BigDecimal.ZERO;
    public BigDecimal lastPx = BigDecimal.ZERO;
    public BigDecimal avgPx = BigDecimal.ZERO;

    // product scale
    public BigDecimal cumQuoteQty = BigDecimal.ZERO;
    public BigDecimal fee         = BigDecimal.ZERO;

    // currency ID，pass-through
    public int feeAssetId;

    @Override
    public String toString() {
        return "RestoredFuturesExecution{"
            + "uniId=" + uniId
            + ", executionType=" + executionType
            + ", orderStatus=" + orderStatus
            + ", symbol=" + symbol
            + ", userId=" + userId
            + ", clOrdId=" + clOrdId
            + ", orderId=" + orderId
            + ", orderType=" + orderType
            + ", side=" + side
            + ", contractType=" + contractType
            + ", positionSide=" + positionSide
            + ", counterpartyId=" + counterpartyId
            + ", orderQty=" + orderQty + ", price=" + price
            + ", lastQty=" + lastQty + ", lastPx=" + lastPx
            + ", cumQty=" + cumQty + ", cumQuoteQty=" + cumQuoteQty
            + ", avgPx=" + avgPx
            + ", fee=" + fee + "(asset=" + feeAssetId + ")"
            + ", isMaker=" + isMaker
            + ", execId=" + execId
            + ", createTime=" + createTime
            + '}';
    }
}
