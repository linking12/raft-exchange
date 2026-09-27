package exchange.core2.core.processors;

import java.util.Objects;

import exchange.core2.core.common.StateHash;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesMarshallable;
import net.openhft.chronicle.bytes.BytesOut;

/**
 * 每 symbol 的最新价快照，进 raft snapshot、参与 stateHash。
 *
 * <p>
 * markPrice 按 symbol 类型分两个来源：
 * 
 * <b>期货</b>由 {@code MARKPRICE_ADJUSTMENT} 外部喂入（指数价）；
 * 
 * <b>现货</b>价格本就是本所撮合出来的，没有外部喂价方，
 * 
 * 故由 {@link #applyTradePrice} 用成交价维护。
 */
@ToString
public class LastPriceCacheRecord implements BytesMarshallable, StateHash {
    public static final long WINDOW_MS = 15_000L;
    public long askPrice = Long.MAX_VALUE;
    public long bidPrice = 0L;
    public long markPrice = 0L;
    public long markPriceTs = 0L;

    public LastPriceCacheRecord() {}

    public LastPriceCacheRecord(long askPrice, long bidPrice, long markPrice) {
        this.askPrice = askPrice;
        this.bidPrice = bidPrice;
        this.markPrice = markPrice;
    }

    public LastPriceCacheRecord(BytesIn bytes) {
        this.askPrice = bytes.readLong();
        this.bidPrice = bytes.readLong();
        this.markPrice = bytes.readLong();
        this.markPriceTs = bytes.readLong();
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeLong(askPrice);
        bytes.writeLong(bidPrice);
        bytes.writeLong(markPrice);
        bytes.writeLong(markPriceTs);
    }

    public LastPriceCacheRecord copy() {
        final LastPriceCacheRecord c = new LastPriceCacheRecord(askPrice, bidPrice, markPrice);
        c.markPriceTs = markPriceTs;
        return c;
    }

    public void applyTradePrice(long ts, long price) {
        if (price <= 0 || ts <= markPriceTs) {
            return;
        }
        final long dt = ts - markPriceTs;
        markPrice =
            (markPrice <= 0 || dt >= WINDOW_MS) ? price : (markPrice * (WINDOW_MS - dt) + price * dt) / WINDOW_MS;
        markPriceTs = ts;
    }

    public static LastPriceCacheRecord dummy = new LastPriceCacheRecord(42, 42, 42);

    @Override
    public int stateHash() {
        return Objects.hash(askPrice, bidPrice, markPrice, markPriceTs);
    }
}
