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

    public CoreCurrencySpecification(BytesIn bytes) {
        this.id = bytes.readInt();
        this.name = bytes.readUtf8();
        this.digit = bytes.readInt();
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeInt(id);
        bytes.writeUtf8(name);
        bytes.writeInt(digit);
    }

    public long getCurrencyScaleK() {
        return TenPowers.pow10(digit);
    }

    @Override
    public int stateHash() {
        return Objects.hash(id, name, digit);
    }

}
