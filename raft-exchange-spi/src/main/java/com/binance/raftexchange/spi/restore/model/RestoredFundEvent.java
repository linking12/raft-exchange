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

    // Position - ratio
    public BigDecimal marginRatio = BigDecimal.ZERO; // 保证金率 = marginRatioScaleK / maintenanceMarginScaleK

    // 衍生
    public BigDecimal avgOpenPrice = BigDecimal.ZERO;

    // ── Loan - 借贷侧（按 balances 的 currency scale 还原）
    public boolean    loanIsolated;                     // true = Isolated，false = Cross
    public BigDecimal debtPrincipal = BigDecimal.ZERO;  // 操作后未偿本金（负债）；放款时已计入 free，勿相加
    public BigDecimal debtInterest  = BigDecimal.ZERO;  // 操作后未付利息（负债）
    public BigDecimal interestPaidTotal = BigDecimal.ZERO; // 累计已付利息（单调递增，相减得本次量）

    // ── Loan - 抵押侧（按 collateralCurrencyScaleK 还原；Cross 的 BORROW/REPAY 与 MARGIN_CALL 整组为 0）
    public int        collateralCurrency;
    public BigDecimal collateralPledged = BigDecimal.ZERO; // 操作后已质押抵押物；已含在 collateralLocked 内
    public BigDecimal collateralFree    = BigDecimal.ZERO; // 抵押币账户可用余额
    public BigDecimal collateralLocked  = BigDecimal.ZERO; // 抵押币账户冻结额

    // ── Loan - 风险指标（bps 还原为比例，0.5333 = 53.33%）
    public BigDecimal ltv          = BigDecimal.ZERO; // 操作后 LTV =（未偿本金 + 应计利息）/ 抵押物市值
    public BigDecimal ltvThreshold = BigDecimal.ZERO; // 仅 MARGIN_CALL：触发本次预警的 LTV 阈值

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
            + ", marginRatio=" + marginRatio
            + ", bidsQty=" + bidsQty + ", asksQty=" + asksQty
            + ", bidsNotional=" + bidsNotional + ", asksNotional=" + asksNotional
            + ", loanIsolated=" + loanIsolated
            + ", debtPrincipal=" + debtPrincipal + ", debtInterest=" + debtInterest
            + ", interestPaidTotal=" + interestPaidTotal
            + ", collateralCurrency=" + collateralCurrency + ", collateralPledged=" + collateralPledged
            + ", collateralFree=" + collateralFree + ", collateralLocked=" + collateralLocked
            + ", ltv=" + ltv + ", ltvThreshold=" + ltvThreshold
            + '}';
    }
}
