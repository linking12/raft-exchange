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
    public int section; // 分区 ID
    public long orderId; // 订单 ID
    public long uid; // 用户 ID
    public int currency; // 变动货币
    public long free; // 当前可用余额
    public long locked; // 当前冻结金额（隐式保证金+挂单预扣）

    // 期货使用字段（能根据期货事件 反向构建出用户的仓位）
    public int symbol; // 交易对 ID
    public PositionDirection direction; // 仓位方向
    public long position; // 剩余持仓量
    public long positionChanged; // 本次变动的仓位（如平仓/开仓数量）
    public long openPriceSum; // 开仓总价
    public long openVolume; // 开仓数量
    public long tradePrice; // 本次成交价格（开仓或平仓价）
    public long fee; // 手续费
    public long pnl; // 本次事件的盈亏金额
    public long extra; // 补充保证金

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
        PNL_SETTLEMENT(11),
        // 补充保证金事件
        MARGIN_ADJUST(12),      // 逐仓追加补充保证金
        MARGIN_REFUND(14),      // 逐仓平仓返还补充保证金
        // 通知类事件
        MARGIN_ALERT(14),       // 通知追加保证金
        LIQUIDATION_ALERT(15);  // 通知强平单创建

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
        return Objects.hash(processed, eventType, section, orderId, uid, currency, free, locked, symbol, direction,
            position, positionChanged, openPriceSum, openVolume, tradePrice, fee, pnl, extra, nextEvent);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        FundEvent other = (FundEvent)obj;
        return processed == other.processed && eventType == other.eventType && section == other.section && orderId == other.orderId && uid == other.uid && currency == other.currency
            && free == other.free && locked == other.locked && symbol == other.symbol && direction == other.direction && position == other.position
            && positionChanged == other.positionChanged && openPriceSum == other.openPriceSum && openVolume == other.openVolume && tradePrice == other.tradePrice
            && fee == other.fee && pnl == other.pnl && extra == other.extra
            && ((nextEvent == null && other.nextEvent == null) || (nextEvent != null && nextEvent.equals(other.nextEvent)));
    }

    @Override
    public String toString() {
        return "FundEvent [processed=" + processed + ", eventType=" + eventType + ", section=" + section + ", orderId=" + orderId + ", uid=" + uid + ", currency=" + currency + ", free="
            + free + ", locked=" + locked + ", symbol=" + symbol + ", direction=" + direction + ", position=" + position + ", positionChanged="
            + positionChanged + ", openPriceSum=" + openPriceSum + ", openVolume=" + openVolume + ", tradePrice=" + tradePrice + ", fee=" + fee + ", pnl=" + pnl + ", extra=" + extra
            + ", nextEvent=" + (nextEvent != null) + "]";
    }

}