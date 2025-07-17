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

    public static class TenPowers {
        private TenPowers() {
        }

        // 预先准备好 0…18 次幂
        private static final long[] POW10 = {
                1L,               // 10^0
                10L,              // 10^1
                100L,             // 10^2
                1_000L,           // 10^3
                10_000L,          // 10^4
                100_000L,         // 10^5
                1_000_000L,       // 10^6
                10_000_000L,      // 10^7
                100_000_000L,     // 10^8
                1_000_000_000L,   // 10^9
                10_000_000_000L,  // 10^10
                100_000_000_000L, // 10^11
                1_000_000_000_000L,      // 10^12
                10_000_000_000_000L,     // 10^13
                100_000_000_000_000L,    // 10^14
                1_000_000_000_000_000L,  // 10^15
                10_000_000_000_000_000L, // 10^16
                100_000_000_000_000_000L,// 10^17
                1_000_000_000_000_000_000L // 10^18
        };

        /**
         * 返回 10^n，0 <= n <= 18
         */
        public static long pow10(int n) {
            if (n < 0 || n >= POW10.length) {
                throw new IllegalArgumentException("n out of range: " + n);
            }
            return POW10[n];
        }
    }
}
