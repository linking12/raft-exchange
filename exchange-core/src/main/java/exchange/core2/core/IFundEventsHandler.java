package exchange.core2.core;

import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.FundEvent.FundEventType;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.PositionDirection;
import lombok.AllArgsConstructor;
import lombok.Data;

public interface IFundEventsHandler {

    void fundEventReport(FundEventReport fundEventReport);

    @Data
    class FundEventReport {
        private final long uniId;
        private final long accountId;
        private final FundEventType eventType;
        private final BalanceSnapshot balances;
        private final PositionSnapshot positions;

        @Data
        public static class BalanceSnapshot {
            private final int currency;
            private final long currencyScakeK;
            private final long free;
            private final long locked;
        }

        @AllArgsConstructor
        @Data
        public static class PositionSnapshot {
            private final int symbolId;
            private final long baseScaleK;
            private final long quoteScaleK;
            private final PositionDirection direction;
            private final long quantity;          // openVolume
            private final long openPriceSum;
            private final long cumRealized;       // position.profit
            private final boolean isolated;       // 是否逐仓
            private final long isolatedWallet;    // 逐仓保证金
            private final int leverage;
            private final long openInitMarginSum;
            private final long markPrice;
            private final long unrealizedProfit;
            private final long liquidationPrice;
            private final long marginRatioScaleK;
        }

        public static FundEventReport fromFundEvent(FundEvent fundEvent, long uniId) {
            return new FundEventReport(uniId, fundEvent.uid, fundEvent.eventType,
                    new BalanceSnapshot(fundEvent.currency, fundEvent.currencyScakeK, fundEvent.free, fundEvent.locked),
                    new PositionSnapshot(fundEvent.symbol, fundEvent.baseScaleK, fundEvent.quoteScaleK, fundEvent.direction,
                            fundEvent.openVolume, fundEvent.openPriceSum, fundEvent.profit,
                            fundEvent.marginMode == MarginMode.ISOLATED, fundEvent.extraMargin,
                            fundEvent.leverage,
                            fundEvent.openInitMarginSum,
                            fundEvent.markPrice,
                            fundEvent.unrealizedProfit,
                            fundEvent.liquidationPrice,
                            fundEvent.marginRatioScaleK));
        }
    }
}
