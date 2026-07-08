package com.binance.raftexchange.spi.restore.model;

import com.binance.raftexchange.stubs.ExecType;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderStatus;
import com.binance.raftexchange.stubs.OrderType;

import java.math.BigDecimal;

/**
 * {@link com.binance.raftexchange.spi.restore.TradeEventRestorer} 从 SpotExecutionReportPB
 * 还原出的现货成交业务视图，所有金额/数量已按对应 scale 体系从 long 还原为 BigDecimal。
 *
 * <p>Scale 体系：
 *   base scale (baseScaleK)              → qty / lastQty / cumulativeQty
 *   quote scale (quoteScaleK)            → price / lastPrice
 *   product scale (base × quote)         → quoteOrderQty / lastQuoteQty
 *                                          / cumulativeQuoteQty / commission
 * </p>
 *
 * <p>{@code quoteOrderQty} 看名字像 quote scale，但实际是 product scale —— 引擎对
 * FOK_BUDGET/IOC_BUDGET 把 {@code cmd.price} 当作总预算的 product-scale notional
 * 来使用（见 RiskEngine 的 extraNotional / takerSizePriceHeldSum 计算），
 * 并直接透传到 {@code result.quoteOrderQty}。</p>
 *
 * <p>commission 始终以 quote 币种结算（{@code commissionAsset = spec.quoteCurrency}），
 * 引擎计算为 {@code (size × price × takerFee) / feeScaleK}，结果落在 product scale。
 * commissionAsset 是 currency ID，无 scale 概念，直接 pass-through。</p>
 *
 * <p><b>边界</b>：固定费率模式（{@code spec.feeScaleK == 0}）下 commission 编码为
 * {@code size × takerFee}，scale 不再是 product；该模式多用于测试/历史合约，
 * 此 View 不针对其特殊还原，需要时请单独处理。</p>
 */
public final class RestoredSpotExecution {

    public long        executionId;
    public ExecType    executionType;
    public OrderStatus orderStatus;
    public int         symbol;
    public long        accountId;
    public long        clOrdId;
    public long        orderId;
    public OrderType   orderType;
    public OrderAction side;
    public long        orderCreationTime;
    public long        tradeId;
    public boolean     isMaker;
    public boolean     workingIndicator;

    // base scale
    public BigDecimal qty           = BigDecimal.ZERO;
    public BigDecimal lastQty       = BigDecimal.ZERO;
    public BigDecimal cumulativeQty = BigDecimal.ZERO;

    // quote scale
    public BigDecimal price         = BigDecimal.ZERO;
    public BigDecimal lastPrice     = BigDecimal.ZERO;
    public BigDecimal quoteOrderQty = BigDecimal.ZERO;

    // product scale
    public BigDecimal lastQuoteQty       = BigDecimal.ZERO;
    public BigDecimal cumulativeQuoteQty = BigDecimal.ZERO;
    public BigDecimal commission         = BigDecimal.ZERO;

    // currency ID，pass-through
    public int commissionAsset;

    @Override
    public String toString() {
        return "RestoredSpotExecution{"
            + "executionId=" + executionId
            + ", executionType=" + executionType
            + ", orderStatus=" + orderStatus
            + ", symbol=" + symbol
            + ", accountId=" + accountId
            + ", clOrdId=" + clOrdId
            + ", orderId=" + orderId
            + ", orderType=" + orderType
            + ", side=" + side
            + ", qty=" + qty + ", price=" + price
            + ", quoteOrderQty=" + quoteOrderQty
            + ", lastQty=" + lastQty + ", lastPrice=" + lastPrice
            + ", lastQuoteQty=" + lastQuoteQty
            + ", cumulativeQty=" + cumulativeQty + ", cumulativeQuoteQty=" + cumulativeQuoteQty
            + ", commission=" + commission + "(asset=" + commissionAsset + ")"
            + ", tradeId=" + tradeId
            + ", isMaker=" + isMaker + ", workingIndicator=" + workingIndicator
            + ", orderCreationTime=" + orderCreationTime
            + '}';
    }
}
