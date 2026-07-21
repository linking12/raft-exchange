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

    public long         uniId;          // 执行报告唯一 id
    public ExecType     executionType;  // 执行类型（NEW/TRADE/REDUCE/CANCEL/REJECT）
    public OrderStatus  orderStatus;    // 订单状态
    public int          symbol;         // 合约 symbol id
    public long         userId;         // 账户 uid
    public long         clOrdId;        // 客户端订单 id
    public long         orderId;        // 引擎订单 id
    public OrderType    orderType;      // 订单类型（GTC/IOC/FOK/…）
    public OrderAction  side;           // 买 / 卖
    public long         counterpartyId; // 对手方 uid
    public long         createTime;     // 下单时间（ms）
    public long         execId;         // 成交 id（仅 TRADE 事件有意义）
    public SymbolType   contractType;   // 合约类型（永续 / 交割）
    public PositionMode positionSide;   // 持仓方向（LONG/SHORT/BOTH）
    public boolean      isMaker;        // 本次成交是否为 maker

    // orderQty scale
    public BigDecimal orderQty = BigDecimal.ZERO; // 委托数量
    public BigDecimal lastQty  = BigDecimal.ZERO; // 本次成交量
    public BigDecimal cumQty   = BigDecimal.ZERO; // 累计成交量

    // price scale
    public BigDecimal price = BigDecimal.ZERO;  // 委托价
    public BigDecimal lastPx = BigDecimal.ZERO; // 本次成交价
    public BigDecimal avgPx = BigDecimal.ZERO;  // 累计成交均价

    // product scale
    public BigDecimal cumQuoteQty = BigDecimal.ZERO; // 累计成交额（quote）
    public BigDecimal fee         = BigDecimal.ZERO; // 手续费（quote 币结算）

    // currency ID，pass-through
    public int feeAssetId; // 手续费币种 id

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
