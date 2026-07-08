package com.binance.raftexchange.spi.restore.model;

import com.binance.raftexchange.stubs.FundEventType;
import com.binance.raftexchange.stubs.PositionDirection;

import java.math.BigDecimal;

/**
 * {@link com.binance.raftexchange.spi.restore.FundEventRestorer} 从 FundEventReportPB 还原出的
 * 资金事件业务视图：触发事件时的账户余额（Balance）与持仓（Position）状态，所有金额已按对应
 * scale 体系从 long 还原为 BigDecimal。
 *
 * <p>命名上以「Restored」前缀区别于代码库其他地方表示 long 编码态的
 * BalanceSnapshot / PositionSnapshot / FundEventReport。</p>
 */
public final class RestoredFundEvent {

    public long              accountId;
    public FundEventType     eventType;

    // Balance（currency scale）
    public int        currency;
    public BigDecimal free   = BigDecimal.ZERO;
    public BigDecimal locked = BigDecimal.ZERO;

    // Position - 基础
    public int               symbolId;
    public PositionDirection direction;
    public int               leverage;
    public boolean           isolated;

    // Position - base scale
    public BigDecimal quantity = BigDecimal.ZERO;
    public BigDecimal bidsQty  = BigDecimal.ZERO;
    public BigDecimal asksQty  = BigDecimal.ZERO;

    // Position - quote scale
    public BigDecimal markPrice        = BigDecimal.ZERO;
    public BigDecimal liquidationPrice = BigDecimal.ZERO;

    // Position - product scale
    public BigDecimal openPriceSum      = BigDecimal.ZERO;
    public BigDecimal openInitMarginSum = BigDecimal.ZERO;
    public BigDecimal cumRealized       = BigDecimal.ZERO;
    public BigDecimal unrealizedProfit  = BigDecimal.ZERO;
    public BigDecimal isolatedWallet    = BigDecimal.ZERO;
    public BigDecimal bidsNotional      = BigDecimal.ZERO;
    public BigDecimal asksNotional      = BigDecimal.ZERO;

    // 衍生
    public BigDecimal avgOpenPrice = BigDecimal.ZERO;

    @Override
    public String toString() {
        return "RestoredFundEvent{"
            + "accountId=" + accountId
            + ", eventType=" + eventType
            + ", currency=" + currency + ", free=" + free + ", locked=" + locked
            + ", symbolId=" + symbolId + ", direction=" + direction
            + ", leverage=" + leverage + ", isolated=" + isolated
            + ", quantity=" + quantity + ", avgOpenPrice=" + avgOpenPrice
            + ", openPriceSum=" + openPriceSum + ", openInitMarginSum=" + openInitMarginSum
            + ", cumRealized=" + cumRealized + ", unrealizedProfit=" + unrealizedProfit
            + ", isolatedWallet=" + isolatedWallet
            + ", markPrice=" + markPrice + ", liquidationPrice=" + liquidationPrice
            + ", bidsQty=" + bidsQty + ", asksQty=" + asksQty
            + ", bidsNotional=" + bidsNotional + ", asksNotional=" + asksNotional
            + '}';
    }
}
