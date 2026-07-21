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

    public int  symbol;    // symbol id
    public long timestamp; // 盘口快照时间（ms）

    public List<Record> asks = new ArrayList<>(); // 卖档
    public List<Record> bids = new ArrayList<>(); // 买档

    /** 单档行情记录：价格、累计挂单量、订单数。 */
    public static final class Record {
        public BigDecimal price  = BigDecimal.ZERO;  // 档位价格（quote scale）
        public BigDecimal volume = BigDecimal.ZERO;  // 该档累计挂单量（base scale）
        public int        orders;                    // 该档订单数，无 scale

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
