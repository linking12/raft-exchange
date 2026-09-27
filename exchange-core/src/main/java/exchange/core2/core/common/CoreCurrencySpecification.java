package exchange.core2.core.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

import java.util.Objects;

@Builder
@AllArgsConstructor
@Getter
@ToString
public class CoreCurrencySpecification implements WriteBytesMarshallable, StateHash {
    public final int id;
    public final String name;
    public final int digit; // 精度位数
    public int collateralWeightBps; // Cross 抵押折价率（bps）；0 = 该币不能作 Cross 抵押

    public CoreCurrencySpecification(int id, String name, int digit) {
        this(id, name, digit, 0);
    }

    public CoreCurrencySpecification(BytesIn bytes) {
        this.id = bytes.readInt();
        this.name = bytes.readUtf8();
        this.digit = bytes.readInt();
        this.collateralWeightBps = bytes.readInt();
    }

    /** collateralWeightBps 唯一 mutation point；仅供 ADD_LOAN 装配调用。 */
    public void updateCollateralWeight(int collateralWeightBps) {
        this.collateralWeightBps = collateralWeightBps;
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeInt(id);
        bytes.writeUtf8(name);
        bytes.writeInt(digit);
        bytes.writeInt(collateralWeightBps);
    }

    public long getCurrencyScaleK() {
        return TenPowers.pow10(digit);
    }

    @Override
    public int stateHash() {
        return Objects.hash(id, name, digit, collateralWeightBps);
    }

}
