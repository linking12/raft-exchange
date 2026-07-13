package exchange.core2.core;

import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.FundEvent.FundEventType;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.PositionDirection;
import lombok.Getter;

import java.util.ArrayDeque;

public interface IFundEventsHandler {

    void fundEventReport(FundEventReport fundEventReport);

    default void process(FundEventReport fundEventReport) {
        fundEventReport(fundEventReport);
        fundEventReport.recycle();
    }

    @Getter
    class FundEventReport {
        private static final int POOL_SIZE = 1024;
        private static final ThreadLocal<ArrayDeque<FundEventReport>> POOL =
                ThreadLocal.withInitial(() -> new ArrayDeque<>(POOL_SIZE));

        private long uniId;
        private long accountId;
        private FundEventType eventType;
        private BalanceSnapshot balances;
        private PositionSnapshot positions;
        // loan 用户维度事件载荷（v3；LOAN_* 事件才有意义，其他事件 loan 字段全 0）
        private LoanSnapshot loan;

        private FundEventReport() {
        }

        private static FundEventReport borrow() {
            FundEventReport obj = POOL.get().pollFirst();
            return (obj != null) ? obj : new FundEventReport();
        }

        private void recycle() {
            clear();
            ArrayDeque<FundEventReport> pool = POOL.get();
            if (pool.size() < POOL_SIZE) {
                pool.addFirst(this);
            }
        }

        private void clear() {
            uniId = 0L;
            accountId = 0L;
            eventType = null;
            if (balances != null) {
                balances.recycle();
                balances = null;
            }
            if (positions != null) {
                positions.recycle();
                positions = null;
            }
            if (loan != null) {
                loan.recycle();
                loan = null;
            }
        }

        private FundEventReport fill(FundEvent fundEvent, long uniId) {
            this.uniId = uniId;
            this.accountId = fundEvent.uid;
            this.eventType = fundEvent.eventType;
            this.balances = BalanceSnapshot.borrow().fill(fundEvent);
            this.positions = PositionSnapshot.borrow().fill(fundEvent);
            this.loan = LoanSnapshot.borrow().fill(fundEvent);
            return this;
        }

        @Getter
        public static class BalanceSnapshot {
            private static final ThreadLocal<ArrayDeque<BalanceSnapshot>> POOL =
                    ThreadLocal.withInitial(() -> new ArrayDeque<>(POOL_SIZE));

            private int currency;
            private long currencyScaleK;
            private long free;
            private long locked;

            private BalanceSnapshot() {
            }

            private static BalanceSnapshot borrow() {
                BalanceSnapshot obj = POOL.get().pollFirst();
                return (obj != null) ? obj : new BalanceSnapshot();
            }

            private void recycle() {
                clear();
                ArrayDeque<BalanceSnapshot> pool = POOL.get();
                if (pool.size() < POOL_SIZE) {
                    pool.addFirst(this);
                }
            }

            private void clear() {
                currency = 0;
                currencyScaleK = 0;
                free = 0L;
                locked = 0L;
            }

            private BalanceSnapshot fill(FundEvent fundEvent) {
                this.currency = fundEvent.currency;
                this.currencyScaleK = fundEvent.currencyScaleK;
                this.free = fundEvent.free;
                this.locked = fundEvent.locked;
                return this;
            }
        }

        @Getter
        public static class PositionSnapshot {
            private static final ThreadLocal<ArrayDeque<PositionSnapshot>> POOL =
                    ThreadLocal.withInitial(() -> new ArrayDeque<>(POOL_SIZE));

            private int symbolId;
            private long baseScaleK;
            private long quoteScaleK;
            private PositionDirection direction;
            private long quantity;          // openVolume
            private long openPriceSum;
            private long cumRealized;       // position.profit
            private boolean isolated;       // 是否逐仓
            private long isolatedWallet;    // 逐仓保证金
            private int leverage;
            private long openInitMarginSum;
            private long markPrice;
            private long unrealizedProfit;
            private long liquidationPrice;
            private long marginRatioScaleK;
            private long bidsNotional; // 未成交部分名义价值，pendingBuySize * pendingBuyAvgPrice
            private long asksNotional;
            private long bidsQty;  // 剩余买单数量，pendingBuySize
            private long asksQty;

            private PositionSnapshot() {
            }

            private static PositionSnapshot borrow() {
                PositionSnapshot obj = POOL.get().pollFirst();
                return (obj != null) ? obj : new PositionSnapshot();
            }

            private void recycle() {
                clear();
                ArrayDeque<PositionSnapshot> pool = POOL.get();
                if (pool.size() < POOL_SIZE) {
                    pool.addFirst(this);
                }
            }

            private void clear() {
                symbolId = 0;
                baseScaleK = 0L;
                quoteScaleK = 0L;
                direction = null;
                quantity = 0L;
                openPriceSum = 0L;
                cumRealized = 0L;
                isolated = false;
                isolatedWallet = 0L;
                leverage = 0;
                openInitMarginSum = 0L;
                markPrice = 0L;
                unrealizedProfit = 0L;
                liquidationPrice = 0L;
                marginRatioScaleK = 0L;
                bidsNotional = 0L;
                asksNotional = 0L;
                bidsQty = 0L;
                asksQty = 0L;
            }

            private PositionSnapshot fill(FundEvent fundEvent) {
                this.symbolId = fundEvent.symbol;
                this.baseScaleK = fundEvent.baseScaleK;
                this.quoteScaleK = fundEvent.quoteScaleK;
                this.direction = fundEvent.direction;
                this.quantity = fundEvent.openVolume;
                this.openPriceSum = fundEvent.openPriceSum;
                this.cumRealized = fundEvent.profit;
                this.isolated = (fundEvent.marginMode == MarginMode.ISOLATED);
                this.isolatedWallet = fundEvent.extraMargin;
                this.leverage = fundEvent.leverage;
                this.openInitMarginSum = fundEvent.openInitMarginSum;
                this.markPrice = fundEvent.markPrice;
                this.unrealizedProfit = fundEvent.unrealizedProfit;
                this.liquidationPrice = fundEvent.liquidationPrice;
                this.marginRatioScaleK = fundEvent.marginRatioScaleK;
                this.bidsNotional = Math.multiplyExact(fundEvent.pendingBuySize, fundEvent.pendingBuyAvgPrice);
                this.asksNotional = Math.multiplyExact(fundEvent.pendingSellSize, fundEvent.pendingSellAvgPrice);
                this.bidsQty = fundEvent.pendingBuySize;
                this.asksQty = fundEvent.pendingSellSize;
                return this;
            }
        }

        /** loan 用户维度事件快照（v3；详见 FundEvent §9.5）——操作后 loan 状态 + 本次操作增量。非 loan 事件全 0。 */
        @Getter
        public static class LoanSnapshot {
            private static final ThreadLocal<ArrayDeque<LoanSnapshot>> POOL =
                    ThreadLocal.withInitial(() -> new ArrayDeque<>(POOL_SIZE));

            private byte mode;                 // 0=Isolated，1=Cross
            private int collateralCurrency;    // Isolated 抵押币；Cross = numeraire 估值币
            private long outstandingPrincipal; // 操作后剩余本金
            private long accumulatedInterest;  // 操作后剩余应计利息
            private long collateralAmount;     // 操作后剩余抵押
            private int rateBps;               // 贷款利率
            private long ltvBps;               // 操作后 LTV（MARGIN_CALL=当前 LTV）
            private long principalDelta;       // 本次本金变动（借+/还-）
            private long collateralDelta;      // 本次抵押变动（加+/减-）
            private long interestPaid;         // 本次偿还利息
            private long badDebt;              // 本次坏账核销量
            private long thresholdBps;         // 仅 MARGIN_CALL：预警阈值 bps

            private LoanSnapshot() {
            }

            private static LoanSnapshot borrow() {
                LoanSnapshot obj = POOL.get().pollFirst();
                return (obj != null) ? obj : new LoanSnapshot();
            }

            private void recycle() {
                clear();
                ArrayDeque<LoanSnapshot> pool = POOL.get();
                if (pool.size() < POOL_SIZE) {
                    pool.addFirst(this);
                }
            }

            private void clear() {
                mode = 0;
                collateralCurrency = 0;
                outstandingPrincipal = 0L;
                accumulatedInterest = 0L;
                collateralAmount = 0L;
                rateBps = 0;
                ltvBps = 0L;
                principalDelta = 0L;
                collateralDelta = 0L;
                interestPaid = 0L;
                badDebt = 0L;
                thresholdBps = 0L;
            }

            private LoanSnapshot fill(FundEvent fundEvent) {
                this.mode = fundEvent.loanMode;
                this.collateralCurrency = fundEvent.loanCollateralCurrency;
                this.outstandingPrincipal = fundEvent.loanOutstandingPrincipal;
                this.accumulatedInterest = fundEvent.loanAccumulatedInterest;
                this.collateralAmount = fundEvent.loanCollateralAmount;
                this.rateBps = fundEvent.loanRateBps;
                this.ltvBps = fundEvent.loanLtvBps;
                this.principalDelta = fundEvent.loanPrincipalDelta;
                this.collateralDelta = fundEvent.loanCollateralDelta;
                this.interestPaid = fundEvent.loanInterestPaid;
                this.badDebt = fundEvent.loanBadDebt;
                this.thresholdBps = fundEvent.loanThresholdBps;
                return this;
            }
        }

        public static FundEventReport fromFundEvent(FundEvent fundEvent, long uniId) {
            return FundEventReport.borrow().fill(fundEvent, uniId);
        }
    }
}
