package com.binance.raftexchange.spi.restore;

import com.binance.raftexchange.stubs.BalanceSnapshot;
import com.binance.raftexchange.stubs.FundEventReportPB;
import com.binance.raftexchange.stubs.LoanSnapshot;
import com.binance.raftexchange.stubs.PositionSnapshot;
import com.binance.raftexchange.spi.restore.model.RestoredFundEvent;
import com.google.protobuf.InvalidProtocolBufferException;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 将 FundEventReportPB 中的裸 long 字段按所属 scale 体系还原为 BigDecimal。
 *
 * Scale 体系：
 *   currency scale (10^digit)       → free / locked
 *                                     / loan 借贷侧 debtPrincipal / debtInterest / interestPaid
 *   base scale    (baseScaleK)      → quantity / bidsQty / asksQty
 *   quote scale   (quoteScaleK)     → markPrice / liquidationPrice
 *   product scale (base × quote)    → openPriceSum / openInitMarginSum / cumRealized
 *                                     / unrealizedProfit / isolatedWallet / *Notional
 *   collateral currency scale       → loan 抵押侧 collateralPledged / collateralFree / collateralLocked
 *   bps (10000 = 100%)              → ltv / ltvThreshold
 *   marginRatioScaleK / maintenanceMarginScaleK → marginRatio（比例，两者相除）
 *
 * loan 借贷侧金额与 free/locked 同属借款币，共用 balances.currencyScaleK；抵押侧是另一个币种，
 * 用 LoanSnapshot 自带的 collateralCurrencyScaleK。
 */
public final class FundEventRestorer {

    private static final long BPS_SCALE = 10_000L;

    private FundEventRestorer() {}

    /** Kafka 消费者入口：byte[] → PB → Restored，转发 protobuf parse 异常给调用方处理。 */
    public static RestoredFundEvent restore(byte[] bytes) throws InvalidProtocolBufferException {
        return restore(FundEventReportPB.parseFrom(bytes));
    }

    public static RestoredFundEvent restore(FundEventReportPB pb) {
        RestoredFundEvent v = new RestoredFundEvent();
        v.accountId = pb.getAccountId();
        v.eventType = pb.getEventType();

        // 借款币 scale：loan 借贷侧金额也用它还原，故提到块外
        long currencyScaleK = 0;
        if (pb.hasBalances()) {
            BalanceSnapshot b = pb.getBalances();
            currencyScaleK = b.getCurrencyScaleK();
            v.currency = b.getCurrency();
            v.free   = div(b.getFree(),   currencyScaleK);
            v.locked = div(b.getLocked(), currencyScaleK);
        }

        if (pb.hasPositions()) {
            PositionSnapshot p = pb.getPositions();
            long base    = p.getBaseScaleK();
            long quote   = p.getQuoteScaleK();
            long product = (base != 0 && quote != 0) ? Math.multiplyExact(base, quote) : 0;

            v.symbolId  = p.getSymbolId();
            v.direction = p.getDirection();
            v.leverage  = p.getLeverage();
            v.isolated  = p.getIsolated();

            // base
            v.quantity = div(p.getQuantity(), base);
            v.bidsQty  = div(p.getBidsQty(),  base);
            v.asksQty  = div(p.getAsksQty(),  base);

            // quote
            v.markPrice        = div(p.getMarkPrice(),        quote);
            v.liquidationPrice = div(p.getLiquidationPrice(), quote);

            // product
            v.openPriceSum      = div(p.getOpenPriceSum(),      product);
            v.openInitMarginSum = div(p.getOpenInitMarginSum(), product);
            v.cumRealized       = div(p.getCumRealized(),       product);
            v.unrealizedProfit  = div(p.getUnrealizedProfit(),  product);
            v.isolatedWallet    = div(p.getIsolatedWallet(),    product);
            v.bidsNotional      = div(p.getBidsNotional(),      product);
            v.asksNotional      = div(p.getAsksNotional(),      product);

            // ratio：marginRatio = marginRatioScaleK / maintenanceMarginScaleK
            v.marginRatio = div(p.getMarginRatioScaleK(), p.getMaintenanceMarginScaleK());

            // 衍生 avgOpenPrice = openPriceSum / (quote × quantity_long)
            // 推导：openPriceSum 在 product scale，quantity 在 base scale，
            //       avgPrice = (openPriceSum/product) / (quantity/base) = openPriceSum / (quote × quantity_long)
            if (p.getQuantity() != 0 && quote != 0) {
                v.avgOpenPrice = BigDecimal.valueOf(p.getOpenPriceSum())
                        .divide(BigDecimal.valueOf(quote).multiply(BigDecimal.valueOf(p.getQuantity())),
                                MathContext.DECIMAL128);
            }
        }

        if (pb.hasLoan()) {
            LoanSnapshot l = pb.getLoan();
            long collateral = l.getCollateralCurrencyScaleK();

            v.loanIsolated = l.getMode() == 0;

            // 借贷侧：与 free/locked 同为借款币
            v.debtPrincipal = div(l.getDebtPrincipal(), currencyScaleK);
            v.debtInterest  = div(l.getDebtInterest(),  currencyScaleK);
            v.interestPaidTotal = div(l.getInterestPaidTotal(), currencyScaleK);

            // 抵押侧
            v.collateralCurrency = l.getCollateralCurrency();
            v.collateralPledged  = div(l.getCollateralPledged(), collateral);
            v.collateralFree     = div(l.getCollateralFree(),    collateral);
            v.collateralLocked   = div(l.getCollateralLocked(),  collateral);

            // bps → 比例
            v.ltv          = div(l.getLtvBps(),       BPS_SCALE);
            v.ltvThreshold = div(l.getThresholdBps(), BPS_SCALE);
        }
        return v;
    }

    private static BigDecimal div(long value, long divisor) {
        if (divisor == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(divisor), MathContext.DECIMAL128);
    }
}
