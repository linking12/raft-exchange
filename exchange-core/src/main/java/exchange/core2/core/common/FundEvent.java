package exchange.core2.core.common;

import java.util.Objects;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FundEvent {
    public FundEventType eventType; // 事件类型
    public int section;
    public long orderId; // 订单 ID
    public long uid; // 用户 ID
    public int currency; // 变动货币
    public long free; // 用户可用余额
    public long loked; // 用户冻结余额
    public long positionDelta; // 持仓变化（期货用，现金交易为 0）

    public enum FundEventType {
        FREEZE, TRANSFER, RELEASE
    }

    @Override
    public int hashCode() {
        return Objects.hash(currency, eventType, free, loked, positionDelta, section, uid);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        FundEvent other = (FundEvent)obj;
        return currency == other.currency && eventType == other.eventType && free == other.free && loked == other.loked
            && positionDelta == other.positionDelta && section == other.section && uid == other.uid;
    }

    @Override
    public String toString() {
        return "FundEvent [eventType=" + eventType + ", section=" + section + ", uid=" + uid + ", currency=" + currency
            + ", free=" + free + ", loked=" + loked + ", positionDelta=" + positionDelta + "]";
    }

}
