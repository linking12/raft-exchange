package com.binance.raftexchange.spi.restore;

import com.binance.raftexchange.stubs.OrderBookPB;
import com.binance.raftexchange.stubs.OrderBookRecordPB;
import com.binance.raftexchange.spi.restore.model.RestoredOrderBook;
import com.google.protobuf.InvalidProtocolBufferException;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 将 OrderBookPB 中的裸 long 字段（price/volume）按 PB 自带的 baseScaleK / quoteScaleK
 * 还原为 BigDecimal。与 {@link FundEventRestorer} / {@link TradeEventRestorer} 风格一致——
 * 消费端不再需要外部维护 symbol→scale 字典。
 */
public final class OrderBookRestorer {

    private OrderBookRestorer() {}

    /** Kafka 订单簿主题：byte[] → PB → Restored。 */
    public static RestoredOrderBook restore(byte[] bytes) throws InvalidProtocolBufferException {
        return restore(OrderBookPB.parseFrom(bytes));
    }

    public static RestoredOrderBook restore(OrderBookPB pb) {
        long base  = pb.getBaseScaleK();
        long quote = pb.getQuoteScaleK();

        RestoredOrderBook v = new RestoredOrderBook();
        v.symbol    = pb.getSymbol();
        v.timestamp = pb.getTimestamp();

        for (OrderBookRecordPB r : pb.getAsksList()) {
            v.asks.add(restoreRecord(r, base, quote));
        }
        for (OrderBookRecordPB r : pb.getBidsList()) {
            v.bids.add(restoreRecord(r, base, quote));
        }
        return v;
    }

    private static RestoredOrderBook.Record restoreRecord(OrderBookRecordPB pb, long base, long quote) {
        RestoredOrderBook.Record r = new RestoredOrderBook.Record();
        r.price  = div(pb.getPrice(),  quote);
        r.volume = div(pb.getVolume(), base);
        r.orders = pb.getOrders();
        return r;
    }

    private static BigDecimal div(long value, long divisor) {
        if (divisor == 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(divisor), MathContext.DECIMAL128);
    }
}
