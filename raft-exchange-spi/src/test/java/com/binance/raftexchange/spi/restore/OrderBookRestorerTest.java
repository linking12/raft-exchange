package com.binance.raftexchange.spi.restore;

import com.binance.raftexchange.stubs.OrderBookPB;
import com.binance.raftexchange.stubs.OrderBookRecordPB;
import com.binance.raftexchange.spi.restore.model.RestoredOrderBook;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderBookRestorerTest {

    private static final long BASE_SCALE_K  = 10_000L;   // 10^4
    private static final long QUOTE_SCALE_K = 100_000L;  // 10^5

    private static void assertBd(String expected, BigDecimal actual) {
        assertEquals(0, actual.compareTo(new BigDecimal(expected)),
                () -> "expected " + expected + " but was " + actual);
    }

    @Test
    void restore_typicalBook_recoversAsksAndBids() {
        // 2 档 ask + 2 档 bid，BTC/USDT
        OrderBookPB pb = OrderBookPB.newBuilder()
                .setSymbol(1)
                .setTimestamp(1_700_000_000_000L)
                .setBaseScaleK(BASE_SCALE_K).setQuoteScaleK(QUOTE_SCALE_K)
                .addAsks(OrderBookRecordPB.newBuilder()
                        .setPrice(5_010_000_000L)  // 50100
                        .setVolume(3_000L)         // 0.3 BTC
                        .setOrders(5))
                .addAsks(OrderBookRecordPB.newBuilder()
                        .setPrice(5_020_000_000L)  // 50200
                        .setVolume(2_000L)         // 0.2 BTC
                        .setOrders(2))
                .addBids(OrderBookRecordPB.newBuilder()
                        .setPrice(4_990_000_000L)  // 49900
                        .setVolume(1_500L)         // 0.15 BTC
                        .setOrders(3))
                .addBids(OrderBookRecordPB.newBuilder()
                        .setPrice(4_980_000_000L)  // 49800
                        .setVolume(1_000L)         // 0.1 BTC
                        .setOrders(1))
                .build();

        RestoredOrderBook v = OrderBookRestorer.restore(pb);

        assertEquals(1, v.symbol);
        assertEquals(1_700_000_000_000L, v.timestamp);

        assertEquals(2, v.asks.size());
        assertBd("50100", v.asks.get(0).price);
        assertBd("0.3",   v.asks.get(0).volume);
        assertEquals(5,   v.asks.get(0).orders);
        assertBd("50200", v.asks.get(1).price);
        assertBd("0.2",   v.asks.get(1).volume);

        assertEquals(2, v.bids.size());
        assertBd("49900", v.bids.get(0).price);
        assertBd("0.15",  v.bids.get(0).volume);
        assertEquals(3,   v.bids.get(0).orders);
        assertBd("49800", v.bids.get(1).price);
        assertBd("0.1",   v.bids.get(1).volume);
    }

    @Test
    void restore_emptyBook_returnsEmptyLists() {
        OrderBookPB pb = OrderBookPB.newBuilder().setSymbol(2)
                .setBaseScaleK(BASE_SCALE_K).setQuoteScaleK(QUOTE_SCALE_K).build();

        RestoredOrderBook v = OrderBookRestorer.restore(pb);

        assertEquals(2, v.symbol);
        assertEquals(0, v.asks.size());
        assertEquals(0, v.bids.size());
    }

    @Test
    void restore_missingScale_doesNotDivideByZero() {
        // PB 没填 scale（旧版本生产者 / 缺省值），不应抛异常，price/volume 安全置 0
        OrderBookPB pb = OrderBookPB.newBuilder()
                .addAsks(OrderBookRecordPB.newBuilder().setPrice(123L).setVolume(456L).setOrders(1))
                .build();

        RestoredOrderBook v = OrderBookRestorer.restore(pb);

        assertBd("0", v.asks.get(0).price);
        assertBd("0", v.asks.get(0).volume);
    }

    @Test
    void restore_largeQuoteScaleValue_keepsExactPrecision() {
        // quote=10^8 时单档价格仍然精确还原
        long base  = 100_000_000L;
        long quote = 100_000_000L;
        long priceLong = 123_456_789_012_345_678L;  // 17 位有效数字
        OrderBookPB pb = OrderBookPB.newBuilder()
                .setBaseScaleK(base).setQuoteScaleK(quote)
                .addAsks(OrderBookRecordPB.newBuilder().setPrice(priceLong).setVolume(0L))
                .build();

        RestoredOrderBook v = OrderBookRestorer.restore(pb);

        assertBd("1234567890.12345678", v.asks.get(0).price);
    }

    // ---------------- Kafka 消费者 byte[] 入口 ----------------

    @Test
    void restoreFromBytes_equivalentToPb() throws InvalidProtocolBufferException {
        OrderBookPB pb = OrderBookPB.newBuilder()
                .setSymbol(1).setTimestamp(1_700_000_000_000L)
                .setBaseScaleK(BASE_SCALE_K).setQuoteScaleK(QUOTE_SCALE_K)
                .addAsks(OrderBookRecordPB.newBuilder().setPrice(5_010_000_000L).setVolume(3_000L).setOrders(5))
                .build();

        RestoredOrderBook fromPb    = OrderBookRestorer.restore(pb);
        RestoredOrderBook fromBytes = OrderBookRestorer.restore(pb.toByteArray());

        assertEquals(fromPb.symbol, fromBytes.symbol);
        assertEquals(fromPb.timestamp, fromBytes.timestamp);
        assertEquals(fromPb.asks.size(), fromBytes.asks.size());
        assertBd(fromPb.asks.get(0).price.toPlainString(), fromBytes.asks.get(0).price);
        assertBd(fromPb.asks.get(0).volume.toPlainString(), fromBytes.asks.get(0).volume);
    }

    @Test
    void restoreFromBytes_malformed_throws() {
        assertThrows(InvalidProtocolBufferException.class,
                () -> OrderBookRestorer.restore(new byte[]{(byte) 0xff, (byte) 0xff}));
    }
}
