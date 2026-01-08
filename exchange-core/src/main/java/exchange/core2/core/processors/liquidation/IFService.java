package exchange.core2.core.processors.liquidation;

import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.StateHash;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.utils.HashingUtils;
import exchange.core2.core.utils.SerializationUtils;
import lombok.AllArgsConstructor;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.Objects;

public class IFService implements WriteBytesMarshallable, StateHash {

    // symbol -> IFNotional
    private final IntObjectHashMap<IFNotional> notionals;

    // symbol -> IFPosition
    // +symbol -> long; -symbol -> short
    private final IntObjectHashMap<IFPositionRecord> positions;

    public IFService() {
        notionals = new IntObjectHashMap<>(1024);
        positions = new IntObjectHashMap<>(1024);
    }

    public IFService(BytesIn bytes) {
        notionals = SerializationUtils.readIntHashMap(bytes, IFNotional::new);
        positions = SerializationUtils.readIntHashMap(bytes, IFPositionRecord::new);
    }

    public void addFee(int symbol, int currency, long notionalFee) {
        IFNotional notional = notionals.getIfAbsentPut(symbol, () -> new IFNotional(currency, 0, 0));
        notional.available += notionalFee;
    }

    // R1 ：本 shard 最多能贡献多少
    public long previewCover(int symbol, long requestSize, long price) {
        IFNotional notional = notionals.get(symbol);
        long available = notional.available - notional.pending;
        long needed = requestSize * price;
        long canCover = Math.min(available, needed);
        // 冻结待处理的额度，防止超卖
        notional.pending += canCover;
        return canCover;
    }

    // R2：释放pending
    public void releasePending(int symbol, long pendingNotional) {
        IFNotional notional = notionals.get(symbol);
        notional.pending -= pendingNotional;
    }

    // R2：真正接仓
    public void acceptPosition(SymbolPositionRecord pos, long size, long price) {
        IFNotional notional = notionals.get(pos.symbol);
        long spend = size * price;
        notional.available -= spend;
        IFPositionRecord position = positions.getIfAbsentPut(pos.direction.getMultiplier() * pos.symbol,
            () -> new IFPositionRecord(pos.symbol, pos.currency, pos.direction, 0, 0));
        position.openVolume += size;
        position.openPriceSum += spend;
    }

    public void reset() {
        notionals.clear();
        positions.clear();
    }

    @Override
    public int stateHash() {
        return Objects.hash(HashingUtils.stateHash(notionals), HashingUtils.stateHash(positions));
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        SerializationUtils.marshallIntHashMap(notionals, bytes);
        SerializationUtils.marshallIntHashMap(positions, bytes);
    }

    /**
     * | 63........56 | 55..................0 |
     * |   'I' tag    | liquidation tail     |
     */
    public static long generateIFOrderId(long liquidationOrderId) {
        long ifOrderTag = 0x49L; // 'I'
        return (ifOrderTag << 56) | (liquidationOrderId & 0x00FFFFFFFFFFFFFFL);
    }

    @AllArgsConstructor
    public static final class IFNotional implements WriteBytesMarshallable, StateHash {
        public int currency;
        public long available;
        public long pending;

        public IFNotional(BytesIn bytes) {
            currency = bytes.readInt();
            available = bytes.readLong();
            pending = bytes.readLong();
        }

        @Override
        public int stateHash() {
            return Objects.hash(currency, available, pending);
        }

        @Override
        public void writeMarshallable(BytesOut bytes) {
            bytes.writeInt(currency);
            bytes.writeLong(available);
            bytes.writeLong(pending);
        }
    }

    @AllArgsConstructor
    public static final class IFPositionRecord implements WriteBytesMarshallable, StateHash {
        public int symbol;
        public int currency;
        public PositionDirection direction;
        public long openVolume;
        public long openPriceSum;

        public IFPositionRecord(BytesIn bytes) {
            symbol = bytes.readInt();
            currency = bytes.readInt();
            direction = PositionDirection.of(bytes.readByte());
            openVolume = bytes.readLong();
            openPriceSum = bytes.readLong();
        }

        public boolean isEmpty() {
            return openVolume == 0 || direction == PositionDirection.EMPTY;
        }

        public long avgOpenPrice() {
            return openVolume == 0 ? 0 : openPriceSum / openVolume;
        }

        @Override
        public int stateHash() {
            return Objects.hash(symbol, currency, direction, openVolume, openPriceSum);
        }

        @Override
        public void writeMarshallable(BytesOut bytes) {
            bytes.writeInt(symbol);
            bytes.writeInt(currency);
            bytes.writeByte((byte) direction.getMultiplier());
            bytes.writeLong(openVolume);
            bytes.writeLong(openPriceSum);
        }
    }
}
