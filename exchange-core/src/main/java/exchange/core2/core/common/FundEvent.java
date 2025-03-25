package exchange.core2.core.common;

import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

/**
 * 资金事件类，用于记录现货和期货的资金及仓位变动。 现货和期货通过部署上下文和 FundEventType 区分，locked 的含义根据事件类型动态解释。
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
    public long free; // 用户可用余额
    public long locked; // 用户冻结余额（订单锁定金额，如挂单预留） 期货: 冻结保证金（持仓锁定的总保证金）
    // 期货使用字段
    public int symbol; // 交易对 ID
    public PositionDirection direction; // 仓位方向
    public long position; // 剩余持仓量
    public long positionLiquidated; // 本次强平/平仓数量
    public long openPriceAvg; // 平均开仓价格（替代 openPriceSum / openVolume）
    public long liquidationPrice; // 强平/平仓价格
    public long fee; // 手续费
    public long pnl; // 本次事件的盈亏金额

    /**
     * 事件类型枚举。 前五种为现货事件，后五种为期货事件。
     */
    public enum FundEventType {
        // 现货事件
        DEPOSIT(1), LOCKED(2), TRANSFER(3), UNLOCKED(4), WITHDRAW(5),
        // 期货事件
        OPEN_POSITION(6), CLOSE_POSITION(7), LIQUIDATION(8), MARGIN_ADJUSTMENT(9), PNL_SETTLEMENT(10);

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
        bytes.writeLong(positionLiquidated);
        bytes.writeLong(openPriceAvg);
        bytes.writeLong(liquidationPrice);
        bytes.writeLong(fee);
        bytes.writeLong(pnl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventType, section, orderId, uid, currency, free, locked, symbol, direction, position, positionLiquidated, openPriceAvg,
            liquidationPrice, fee, pnl);
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
            && positionLiquidated == other.positionLiquidated && openPriceAvg == other.openPriceAvg && liquidationPrice == other.liquidationPrice
            && fee == other.fee && pnl == other.pnl;
    }

    @Override
    public String toString() {
        return "FundEvent [eventType=" + eventType + ", section=" + section + ", orderId=" + orderId + ", uid=" + uid + ", currency=" + currency + ", free="
            + free + ", locked=" + locked + ", symbol=" + symbol + ", direction=" + direction + ", position=" + position + ", positionLiquidated="
            + positionLiquidated + ", openPriceAvg=" + openPriceAvg + ", liquidationPrice=" + liquidationPrice + ", fee=" + fee + ", pnl=" + pnl + "]";
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
        this.positionLiquidated = bytes.readLong();
        this.openPriceAvg = bytes.readLong();
        this.liquidationPrice = bytes.readLong();
        this.fee = bytes.readLong();
        this.pnl = bytes.readLong();
    }
}