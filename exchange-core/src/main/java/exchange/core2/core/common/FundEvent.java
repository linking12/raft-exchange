package exchange.core2.core.common;

import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

/**
 * 资金事件类，用于记录现货和期货的资金及仓位变动。
 * free + locked = total。
 * 现货和期货通过FundEventType区分。
 */
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FundEvent {
    public boolean processed; // 是否已处理

    // 基础字段
    public FundEventType eventType; // 事件类型
    public long orderId; // 订单 ID
    public long uid; // 用户 ID
    public int currency; // 变动货币
    public long currencyScaleK; // currency缩放系数（用于还原金额）
    public long free; // 该currency可用余额
    public long locked; // 该currency所有期货仓位的冻结金额（初始保证金+pending部分+pending部分产生的fee）

    // 期货使用字段（仓位字段同）
    public int symbol; // 交易对 ID
    public long baseScaleK; // 基础货币的缩放系数（用于还原size）
    public long quoteScaleK; // 计价货币的缩放系数（用于还原price）
    public PositionDirection direction; // 仓位方向
    public long openVolume; // 持仓数量
    public long openInitMarginSum; // 初始保证金总额
    public long openPriceSum; // 持仓总成本，openPriceSum/openVolume=平均持仓成本
    public long profit; // 已实现盈亏
    public long pendingSellSize; // 挂单卖出数量（未成交的卖单）
    public long pendingBuySize; // 挂单买入数量（未成交的买单）
    public long pendingSellAvgPrice; // 挂单卖出平均价格（未成交的卖单）
    public long pendingBuyAvgPrice; // 挂单买入平均价格（未成交的买单）
    public int leverage; // 杠杆倍数
    public MarginMode marginMode; // 模式：逐仓或全仓
    public long extraMargin; // 额外保证金（逐仓模式下的追加保证金）
    // 仓位计算字段
    public long unrealizedProfit; // 未实现盈亏
    public long liquidationPrice; // 强平价格
    public long marginRatioScaleK; // 保证金率，维持保证金/资金占用*缩放系数。全仓下资金占用=当前币种余额+该币种总体未实现盈亏；逐仓下资金占用=开仓保证金+extraMargin
    // 额外字段
    public long markPrice; // 标记价格

    public FundEvent nextEvent;

    /**
     * 事件类型枚举。 前五种为现货事件，后面为期货事件。
     */
    public enum FundEventType {
        // 现货事件
        DEPOSIT(1),         // 现货充值（free 增加）
        LOCKED(2),          // 现货下单前冻结（free -> locked）
        TRANSFER(3),        // 现货撮合成交后资产互换（买方减少quote，加base）
        UNLOCKED(4),        // 订单取消或未成交释放（locked -> free）
        WITHDRAW(5),        // 现货提现（free 减少）
        // 期货事件
        LOCK_PENDING(6),     // 提交期货订单冻结初始保证金（pendingHold）（free -> locked）
        UNLOCK_PENDING(7),   // 未成交释放初始保证金（pendingHold 释放，locked -> free）
        OPEN_POSITION(8),    // 新增持仓记录（仅标记持仓信息）
        CLOSE_POSITION(9),   // 平仓：释放保证金 + 盈亏落地 + 手续费
        LIQUIDATION(10),     // 强平（与 CLOSE_POSITION 类似，但来源特殊）
        FUNDINGFEE_SETTLEMENT(11),// 资金费率结算
        PNL_SETTLEMENT(12),       // 交割合约结算
        // 补充保证金事件
        MARGIN_ADJUST(13),      // 逐仓追加补充保证金
        MARGIN_REFUND(14),      // 逐仓平仓返还补充保证金
        // 其他事件
        RESET_FEE(15),          // 重置手续费
        // 通知类事件
        MARGIN_ALERT(20),       // 通知追加保证金
        LIQUIDATION_ALERT(21);  // 通知强平单创建

        private final int code;

        FundEventType(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static FundEventType of(int code) {
            for (FundEventType type : values()) {
                if (type.code == code)
                    return type;
            }
            throw new IllegalArgumentException("Unknown FundEventType code: " + code);
        }
    }

    public static FundEvent createEventChain(int chainLength) {
        final FundEvent head = new FundEvent();
        FundEvent current = head;
        for (int i = 1; i < chainLength; i++) {
            FundEvent next = new FundEvent();
            current.nextEvent = next;
            current = next;
        }
        return head;
    }

    @Override
    public int hashCode() {
        return Objects.hash(processed, eventType, orderId, uid, currency, currencyScaleK, free, locked, symbol, baseScaleK,
                quoteScaleK, direction, openVolume, openInitMarginSum, openPriceSum, profit, pendingSellSize, pendingBuySize,
                pendingSellAvgPrice, pendingBuyAvgPrice, leverage, marginMode, extraMargin, unrealizedProfit, liquidationPrice,
                marginRatioScaleK, markPrice, nextEvent);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        FundEvent other = (FundEvent)obj;
        return processed == other.processed && eventType == other.eventType && orderId == other.orderId
            && uid == other.uid && currency == other.currency && currencyScaleK == other.currencyScaleK && free == other.free
            && locked == other.locked && symbol == other.symbol && baseScaleK == other.baseScaleK && quoteScaleK == other.quoteScaleK
            && direction == other.direction && openVolume == other.openVolume && openInitMarginSum == other.openInitMarginSum
            && openPriceSum == other.openPriceSum && profit == other.profit && pendingSellSize == other.pendingSellSize
            && pendingBuySize == other.pendingBuySize && pendingSellAvgPrice == other.pendingSellAvgPrice && pendingBuyAvgPrice == other.pendingBuyAvgPrice
            && leverage == other.leverage && marginMode == other.marginMode && extraMargin == other.extraMargin && unrealizedProfit == other.unrealizedProfit
            && liquidationPrice == other.liquidationPrice && marginRatioScaleK == other.marginRatioScaleK && markPrice == other.markPrice
            && ((nextEvent == null && other.nextEvent == null) || (nextEvent != null && nextEvent.equals(other.nextEvent)));
    }

    @Override
    public String toString() {
        return "FundEvent [processed=" + processed + ", eventType=" + eventType + ", orderId=" + orderId + ", uid=" + uid
            + ", currency=" + currency + ", currencyScaleK=" + currencyScaleK + ", free=" + free + ", locked=" + locked
            + ", symbol=" + symbol + ", baseScaleK=" + baseScaleK + ", quoteScaleK=" + quoteScaleK
            + ", direction=" + direction + ", openVolume=" + openVolume + ", openInitMarginSum=" + openInitMarginSum
            + ", openPriceSum=" + openPriceSum + ", profit=" + profit + ", pendingSellSize=" + pendingSellSize
            + ", pendingBuySize=" + pendingBuySize + ", pendingSellAvgPrice=" + pendingSellAvgPrice + ", pendingBuyAvgPrice=" + pendingBuyAvgPrice
            + ", leverage=" + leverage + ", marginMode=" + marginMode + ", extraMargin=" + extraMargin + ", unrealizedProfit=" + unrealizedProfit
            + ", liquidationPrice=" + liquidationPrice + ", marginRatioScaleK=" + marginRatioScaleK + ", markPrice=" + markPrice
            + ", nextEvent=" + (nextEvent != null) + "]";
    }

}