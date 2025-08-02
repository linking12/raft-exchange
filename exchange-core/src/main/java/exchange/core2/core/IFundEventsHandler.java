package exchange.core2.core;

import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.PositionDirection;
import lombok.Data;

public interface IFundEventsHandler {

    /**
     * Method is called if order execution was resulted to one or more trades.
     *
     * @param tradeEvent - immutable object describing event details
     */
    void fundsEvent(FundsEvent fundsEvent);

    @Data
    class FundsEvent {
        public final FundEvent.FundEventType eventType; // 事件类型
        public final long orderId; // 订单 ID
        public final long uid; // 用户 ID
        public final int currency; // 变动货币
        public final long currencyScaleK; // currency缩放系数（用于还原金额）
        public final long free; // 用户可用余额
        public final long locked; // 用户冻结余额

        public final int symbol; // 交易对 ID
        public final long baseScaleK; // 基础货币的缩放系数（用于还原size）
        public final long quoteScaleK; // 计价货币的缩放系数（用于还原price）
        public final PositionDirection direction; // 仓位方向
        public final long openVolume;
        public final long openInitMarginSum; //初始保证金总额
        public final long openPriceSum; //持仓总成本，openPriceSum/openVolume=平均持仓成本
        public final long profit; //已实现盈亏
        public final long pendingSellSize;
        public final long pendingBuySize;
        public final long pendingSellAvgPrice;
        public final long pendingBuyAvgPrice;
        public final int leverage;
        public final MarginMode marginMode;
        public final long extraMargin;
        // 仓位计算字段
        public final long unrealizedProfit;
        public final long liquidationPrice;
        public final long marginRatioScaleK;
        // 额外字段
        public final long markPrice; // 标记价格
        // 变化字段
        public final long tradeSize; // 本次交易数量
        public final long tradePrice; // 本次交易价格
        public final long fee; // 手续费
    }

}
