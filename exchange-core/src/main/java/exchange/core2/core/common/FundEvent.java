package exchange.core2.core.common;

import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

/**
 * 资金事件类，用于记录现货和期货的资金及仓位变动。
 * free + locked = total。
 * 现货和期货通过FundEventType区分。
 * 如果是在前置处理中产生的事件，就挂在cmd下，如果是后置处理产生的事件，就挂在matcherEvent下。
 */
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FundEvent implements WriteBytesMarshallable {
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
    public long openPriceAvg; // 平均开仓价格（替代 openPriceSum / openVolume）
    public long tradePrice; // 本次成交价格（开仓或平仓价）
    public long fee; // 手续费
    public long pnl; // 本次事件的盈亏金额

    /**
     * 事件类型枚举。 前五种为现货事件，后五种为期货事件。
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
        // 通知类事件
        MARGIN_ALERT(12),       // 通知追加保证金
        LIQUIDATION_ALERT(13);  // 通知强平单创建

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

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeByte((byte)eventType.getCode());
        bytes.writeInt(section);
        bytes.writeLong(orderId);
        bytes.writeLong(uid);
        bytes.writeInt(currency);
        bytes.writeLong(free);
        bytes.writeLong(locked);
        bytes.writeInt(symbol);
        bytes.writeByte((byte)direction.getMultiplier());
        bytes.writeLong(position);
        bytes.writeLong(positionChanged);
        bytes.writeLong(openPriceAvg);
        bytes.writeLong(tradePrice);
        bytes.writeLong(fee);
        bytes.writeLong(pnl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventType, section, orderId, uid, currency, free, locked, symbol, direction, position, positionChanged, openPriceAvg,
            tradePrice, fee, pnl);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        FundEvent other = (FundEvent)obj;
        return eventType == other.eventType && section == other.section && orderId == other.orderId && uid == other.uid && currency == other.currency
            && free == other.free && locked == other.locked && symbol == other.symbol && direction == other.direction && position == other.position
            && positionChanged == other.positionChanged && openPriceAvg == other.openPriceAvg && tradePrice == other.tradePrice
            && fee == other.fee && pnl == other.pnl;
    }

    @Override
    public String toString() {
        return "FundEvent [eventType=" + eventType + ", section=" + section + ", orderId=" + orderId + ", uid=" + uid + ", currency=" + currency + ", free="
            + free + ", locked=" + locked + ", symbol=" + symbol + ", direction=" + direction + ", position=" + position + ", positionChanged="
            + positionChanged + ", openPriceAvg=" + openPriceAvg + ", tradePrice=" + tradePrice + ", fee=" + fee + ", pnl=" + pnl + "]";
    }

    public FundEvent(BytesIn bytes) {
        this.eventType = FundEventType.of(bytes.readByte());
        this.section = bytes.readInt();
        this.orderId = bytes.readLong();
        this.uid = bytes.readLong();
        this.currency = bytes.readInt();
        this.free = bytes.readLong();
        this.locked = bytes.readLong();
        this.symbol = bytes.readInt();
        this.direction = PositionDirection.of(bytes.readByte());
        this.position = bytes.readLong();
        this.positionChanged = bytes.readLong();
        this.openPriceAvg = bytes.readLong();
        this.tradePrice = bytes.readLong();
        this.fee = bytes.readLong();
        this.pnl = bytes.readLong();
    }
}