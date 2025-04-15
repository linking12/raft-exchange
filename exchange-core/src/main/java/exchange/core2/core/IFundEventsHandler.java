package exchange.core2.core;

import exchange.core2.core.common.FundEvent;
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
        public final long free; // 用户可用余额
        public final long locked; // 用户冻结余额

        public final int symbol; // 交易对 ID
        public final PositionDirection direction; // 仓位方向
        public final long position; // 剩余持仓量
        public final long positionChanged; // 本次变动的仓位（如平仓/开仓数量）
        public final long openPriceAvg; // 平均开仓价格（替代 openPriceSum / openVolume）
        public final long tradePrice; // 本次成交价格（开仓或平仓价）
        public final long fee; // 手续费
        public final long pnl; // 本次事件的盈亏金额
    }

}
