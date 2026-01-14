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
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.list.mutable.FastList;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public class LiquidationService implements WriteBytesMarshallable, StateHash {

    // symbol -> IFNotional
    private final IntObjectHashMap<IFNotional> notionals;

    // symbol -> IFPosition
    // +symbol -> long; -symbol -> short
    private final IntObjectHashMap<IFPositionRecord> positions;

    /**
     * symbol -> [position...]
     * 本分片的盈利仓位
     */
    private final AtomicReference<IntObjectHashMap<MutableList<SymbolPositionRecord>>> profitablePositionsBySymbol = new AtomicReference<>(IntObjectHashMap.newMap());

    public LiquidationService() {
        notionals = new IntObjectHashMap<>(1024);
        positions = new IntObjectHashMap<>(1024);
    }

    public LiquidationService(BytesIn bytes) {
        notionals = SerializationUtils.readIntHashMap(bytes, IFNotional::new);
        positions = SerializationUtils.readIntHashMap(bytes, IFPositionRecord::new);
    }

    // 将强平手续费计入 IF 可用资金池
    public void creditLiquidationFee(int symbol, long notionalFee) {
        IFNotional notional = notionals.getIfAbsentPut(symbol, () -> new IFNotional(0, 0));
        notional.available += notionalFee;
    }

    // R1：预冻结 IF 可用名义金额
    public long reserveIFNotional(int symbol, long requestSize, long price) {
        IFNotional notional = notionals.getIfAbsentPut(symbol, () -> new IFNotional(0, 0));
        long available = notional.available - notional.reserved;
        long needed = requestSize * price;
        long canCover = Math.min(available, needed);
        // 冻结待处理的额度，防止超卖
        notional.reserved += canCover;
        return canCover;
    }

    // R2：释放 R1 预冻结的名义金额
    public void releaseReservedIFNotional(int symbol, long reservedNotional) {
        IFNotional notional = notionals.get(symbol);
        notional.reserved -= reservedNotional;
    }

    // R2：IF 接管仓位
    public void acceptIFPosition(int symbol, PositionDirection direction, long size, long price) {
        IFNotional notional = notionals.get(symbol);
        long spend = size * price;
        notional.available -= spend;
        IFPositionRecord position = positions.getIfAbsentPut(direction.getMultiplier() * symbol,
                () -> new IFPositionRecord(symbol, direction, 0, 0));
        position.openVolume += size;
        position.openPriceSum += spend;
    }

    public MutableList<SymbolPositionRecord> getProfitablePositionsBySymbol(int symbol) {
        return profitablePositionsBySymbol.get().getIfAbsent(symbol, FastList::new);
    }

    public void setProfitablePositionsBySymbol(IntObjectHashMap<MutableList<SymbolPositionRecord>> snapshot) {
        profitablePositionsBySymbol.set(snapshot);
    }

    public void reset() {
        notionals.clear();
        positions.clear();
        profitablePositionsBySymbol.get().clear();
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
        public long available;
        public long reserved;

        public IFNotional(BytesIn bytes) {
            available = bytes.readLong();
            reserved = bytes.readLong();
        }

        @Override
        public int stateHash() {
            return Objects.hash(available, reserved);
        }

        @Override
        public void writeMarshallable(BytesOut bytes) {
            bytes.writeLong(available);
            bytes.writeLong(reserved);
        }
    }

    @AllArgsConstructor
    public static final class IFPositionRecord implements WriteBytesMarshallable, StateHash {
        public int symbol;
        public PositionDirection direction;
        public long openVolume;
        public long openPriceSum;

        public IFPositionRecord(BytesIn bytes) {
            symbol = bytes.readInt();
            direction = PositionDirection.of(bytes.readByte());
            openVolume = bytes.readLong();
            openPriceSum = bytes.readLong();
        }

        @Override
        public int stateHash() {
            return Objects.hash(symbol, direction, openVolume, openPriceSum);
        }

        @Override
        public void writeMarshallable(BytesOut bytes) {
            bytes.writeInt(symbol);
            bytes.writeByte((byte) direction.getMultiplier());
            bytes.writeLong(openVolume);
            bytes.writeLong(openPriceSum);
        }
    }
}
