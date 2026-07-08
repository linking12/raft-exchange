package com.binance.raftexchange.spi.restore.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link com.binance.raftexchange.spi.restore.OrderBookRestorer} 还原出的盘口快照。
 *
 * <p>注意 OrderBookPB 本身不携带 base/quote scale，调用方需自行从 symbol 字典查得，
 * 通过 {@link com.binance.raftexchange.spi.restore.OrderBookRestorer#restore(
 * com.binance.raftexchange.stubs.OrderBookPB, long, long)} 传入。</p>
 */
public final class RestoredOrderBook {

    public int  symbol;
    public long timestamp;

    public List<Record> asks = new ArrayList<>();
    public List<Record> bids = new ArrayList<>();

    /** 单档行情记录：价格、累计挂单量、订单数。 */
    public static final class Record {
        public BigDecimal price  = BigDecimal.ZERO;  // quote scale
        public BigDecimal volume = BigDecimal.ZERO;  // base scale
        public int        orders;                    // 订单数，无 scale

        @Override
        public String toString() {
            return "Record{price=" + price + ", volume=" + volume + ", orders=" + orders + '}';
        }
    }

    @Override
    public String toString() {
        return "RestoredOrderBook{symbol=" + symbol + ", timestamp=" + timestamp
            + ", asks=" + asks.size() + ", bids=" + bids.size() + '}';
    }
}
