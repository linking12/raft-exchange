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

    public long        executionId;       // 执行报告唯一 id
    public ExecType    executionType;     // 执行类型（NEW/TRADE/REDUCE/CANCEL/REJECT）
    public OrderStatus orderStatus;       // 订单状态
    public int         symbol;            // 现货 symbol id
    public long        accountId;         // 账户 uid
    public long        clOrdId;           // 客户端订单 id
    public long        orderId;           // 引擎订单 id
    public OrderType   orderType;         // 订单类型（GTC/IOC/FOK/…）
    public OrderAction side;              // 买 / 卖
    public long        orderCreationTime; // 下单时间（ms）
    public long        tradeId;           // 成交 id（仅 TRADE 事件有意义）
    public boolean     isMaker;           // 本次成交是否为 maker
    public boolean     workingIndicator;  // 订单是否仍挂在盘口

    // base scale
    public BigDecimal qty           = BigDecimal.ZERO; // 委托数量
    public BigDecimal lastQty       = BigDecimal.ZERO; // 本次成交数量
    public BigDecimal cumulativeQty = BigDecimal.ZERO; // 累计成交数量

    // quote scale
    public BigDecimal price         = BigDecimal.ZERO; // 委托价
    public BigDecimal lastPrice     = BigDecimal.ZERO; // 本次成交价
    public BigDecimal quoteOrderQty = BigDecimal.ZERO; // 报价币计的订单额（budget 单，实为 product scale，见类注释）

    // product scale
    public BigDecimal lastQuoteQty       = BigDecimal.ZERO; // 本次成交额（quote）
    public BigDecimal cumulativeQuoteQty = BigDecimal.ZERO; // 累计成交额（quote）
    public BigDecimal commission         = BigDecimal.ZERO; // 手续费（quote 币结算）

    // currency ID，pass-through
    public int commissionAsset; // 手续费币种 id

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
