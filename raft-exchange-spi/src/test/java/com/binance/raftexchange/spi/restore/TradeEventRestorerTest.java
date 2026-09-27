package com.binance.raftexchange.spi.restore;

import com.binance.raftexchange.stubs.ExecType;
import com.binance.raftexchange.stubs.FuturesExecutionReportPB;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderStatus;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.SpotExecutionReportPB;
import com.binance.raftexchange.stubs.response.MatcherEventType;
import com.binance.raftexchange.stubs.response.MatcherTradeEvent;
import com.binance.raftexchange.spi.restore.model.RestoredFuturesExecution;
import com.binance.raftexchange.spi.restore.model.RestoredMatcherTradeEvent;
import com.binance.raftexchange.spi.restore.model.RestoredSpotExecution;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 与 {@link FundEventRestorerTest} 同思路：
 *   - BTC/USDT 典型 scale：base=10^4, quote=10^5, product=10^9
 *   - 边界：scale=0 时安全返回 0
 *   - 精度回归：base=10^8 × quote=10^8 = 10^16 product，验证 BigDecimal 不丢精度
 */
class TradeEventRestorerTest {

    private static final long BASE_SCALE_K    = 10_000L;
    private static final long QUOTE_SCALE_K   = 100_000L;
    private static final long PRODUCT_SCALE_K = BASE_SCALE_K * QUOTE_SCALE_K; // 10^9

    private static void assertBd(String expected, BigDecimal actual) {
        assertEquals(0, actual.compareTo(new BigDecimal(expected)),
                () -> "expected " + expected + " but was " + actual);
    }

    // ---------------- Spot ----------------

    @Test
    void restore_spotTrade_recoversAllScaleClasses() {
        // 场景：买 0.1 BTC @ 50000 USDT，本次成交 0.05 @ 50000，累计已成交 0.05
        //   qty                long = 0.1   × baseScale     = 1_000
        //   price              long = 50000 × quoteScale    = 5_000_000_000
        //   quoteOrderQty      long = 0  (限价单非 budget)
        //   lastQty            long = 0.05 × baseScale      = 500
        //   lastPrice          long = 50000 × quoteScale    = 5_000_000_000
        //   lastQuoteQty       long = 500 × 5_000_000_000   = 2_500_000_000_000 (product → 2500)
        //   cumulativeQty      long = 0.05 × baseScale      = 500
        //   cumulativeQuoteQty long = 500 × 5_000_000_000   = 2_500_000_000_000 (product → 2500)
        SpotExecutionReportPB pb = SpotExecutionReportPB.newBuilder()
                .setExecutionId(7L)
                .setExecutionType(ExecType.TRADE)
                .setOrderStatus(OrderStatus.PARTIALLY_FILLED)
                .setSymbol(1)
                .setBaseScaleK(BASE_SCALE_K)
                .setQuoteScaleK(QUOTE_SCALE_K)
                .setAccountId(42L)
                .setClOrdId(100L)
                .setOrderId(1001L)
                .setSide(OrderAction.BID)
                .setQty(1_000L)
                .setPrice(5_000_000_000L)
                .setQuoteOrderQty(0L)
                .setLastQty(500L)
                .setLastPrice(5_000_000_000L)
                .setLastQuoteQty(2_500_000_000_000L)
                .setCumulativeQty(500L)
                .setCumulativeQuoteQty(2_500_000_000_000L)
                // 0.1% taker fee on 2500 USDT notional = 2.5 USDT
                //   = (size×price×takerFee)/feeScaleK，落在 product scale
                //   = 2.5 × productScale(10^9) = 2_500_000_000
                .setCommission(2_500_000_000L)
                .setCommissionAsset(2)
                .setIsMaker(false)
                .setTradeId(99L)
                .build();

        RestoredSpotExecution v = TradeEventRestorer.restore(pb);

        assertEquals(7L, v.executionId);
        assertEquals(ExecType.TRADE, v.executionType);
        assertEquals(OrderStatus.PARTIALLY_FILLED, v.orderStatus);
        assertEquals(42L, v.accountId);
        assertEquals(OrderAction.BID, v.side);

        // base
        assertBd("0.1",  v.qty);
        assertBd("0.05", v.lastQty);
        assertBd("0.05", v.cumulativeQty);

        // quote
        assertBd("50000", v.price);
        assertBd("50000", v.lastPrice);
        assertBd("0",     v.quoteOrderQty);

        // product
        assertBd("2500", v.lastQuoteQty);
        assertBd("2500", v.cumulativeQuoteQty);

        // commission 按 product scale 还原；asset 是 currency ID
        assertBd("2.5", v.commission);
        assertEquals(2, v.commissionAsset);
    }

    @Test
    void restore_spotBudgetOrder_quoteOrderQtyOnProductScale() {
        // FOK_BUDGET / IOC_BUDGET 单：cmd.price 在引擎里被当作 product scale 的总预算
        // ITradeEventsHandler 把 cmd.price 透传到 result.quoteOrderQty，因此还原也走 product。
        SpotExecutionReportPB pb = SpotExecutionReportPB.newBuilder()
                .setBaseScaleK(BASE_SCALE_K)
                .setQuoteScaleK(QUOTE_SCALE_K)
                .setQuoteOrderQty(1_000L * PRODUCT_SCALE_K)
                .build();

        RestoredSpotExecution v = TradeEventRestorer.restore(pb);

        assertBd("1000", v.quoteOrderQty);
    }

    @Test
    void restore_spotEmpty_doesNotDivideByZero() {
        SpotExecutionReportPB pb = SpotExecutionReportPB.newBuilder().build();

        RestoredSpotExecution v = TradeEventRestorer.restore(pb);

        assertNotNull(v);
        assertBd("0", v.qty);
        assertBd("0", v.price);
        assertBd("0", v.cumulativeQuoteQty);
    }

    // ---------------- Futures ----------------

    @Test
    void restore_futuresTrade_recoversAllScaleClasses() {
        // 场景：开多 1 ETH 永续 @ 3000 USDT，本次成交 0.4 @ 3000
        //   orderQty    long = 1   × qScale     = 10_000
        //   price       long = 3000× pScale     = 300_000_000
        //   lastQty     long = 0.4 × qScale     = 4_000
        //   lastPx      long = 3000× pScale     = 300_000_000
        //   cumQty      long = 0.4 × qScale     = 4_000
        //   cumQuoteQty long = 4_000 × 300_000_000 = 1.2×10^12 (product → 1200)
        //   avgPx       long = cumQuoteQty / cumQty = 1.2×10^12 / 4_000 = 3×10^8 (→ 3000)
        FuturesExecutionReportPB pb = FuturesExecutionReportPB.newBuilder()
                .setUniId(5L)
                .setExecutionType(ExecType.TRADE)
                .setOrderStatus(OrderStatus.PARTIALLY_FILLED)
                .setSymbol(2)
                .setOrderQtyScale(BASE_SCALE_K)
                .setPriceScale(QUOTE_SCALE_K)
                .setUserId(42L)
                .setClOrdId(101L)
                .setOrderId(2002L)
                .setSide(OrderAction.BID)
                .setCounterpartyId(43L)
                .setPrice(300_000_000L)
                .setOrderQty(10_000L)
                .setLastQty(4_000L)
                .setLastPx(300_000_000L)
                .setCumQty(4_000L)
                .setCumQuoteQty(1_200_000_000_000L)
                .setAvgPx(300_000_000L)
                // 0.05% taker fee on 1200 USDT notional = 0.6 USDT
                //   = product scale = 0.6 × 10^9 = 600_000_000
                .setFee(600_000_000L)
                .setFeeAssetId(2)
                .setIsMaker(true)
                .build();

        RestoredFuturesExecution v = TradeEventRestorer.restore(pb);

        assertEquals(5L, v.uniId);
        assertEquals(ExecType.TRADE, v.executionType);
        assertEquals(42L, v.userId);

        // orderQty scale
        assertBd("1",   v.orderQty);
        assertBd("0.4", v.lastQty);
        assertBd("0.4", v.cumQty);

        // price scale
        assertBd("3000", v.price);
        assertBd("3000", v.lastPx);
        assertBd("3000", v.avgPx);

        // product scale
        assertBd("1200", v.cumQuoteQty);

        // fee 按 product scale 还原；asset 是 currency ID
        assertBd("0.6", v.fee);
        assertEquals(2, v.feeAssetId);
    }

    @Test
    void restore_futuresBudgetOrder_orderQtyOverloadedToProductScale() {
        // 期货 BUDGET 单的 result.orderQty 被引擎重载为 cmd.price（=product-scale 总预算）。
        // Restorer 应识别 orderType 并按 product scale 还原（而非按 orderQtyScale 走 limit 路径）。
        // 例：budget=1000 USDT → orderQty_long = 1000 × productScale(10^9) = 10^12
        FuturesExecutionReportPB pb = FuturesExecutionReportPB.newBuilder()
                .setOrderQtyScale(BASE_SCALE_K)
                .setPriceScale(QUOTE_SCALE_K)
                .setOrderType(OrderType.FOK_BUDGET)
                .setOrderQty(1_000L * PRODUCT_SCALE_K)
                .build();

        RestoredFuturesExecution v = TradeEventRestorer.restore(pb);

        assertBd("1000", v.orderQty);
    }

    @Test
    void restore_futuresEmpty_doesNotDivideByZero() {
        FuturesExecutionReportPB pb = FuturesExecutionReportPB.newBuilder().build();

        RestoredFuturesExecution v = TradeEventRestorer.restore(pb);

        assertNotNull(v);
        assertBd("0", v.orderQty);
        assertBd("0", v.price);
        assertBd("0", v.cumQuoteQty);
    }

    // ---------------- BigDecimal 精度回归 ----------------

    @Test
    void restore_spotLargeProductScaleValue_keepsExactPrecision() {
        // base=10^8 × quote=10^8 = 10^16 product；double 在此区间已开始丢精度，
        // BigDecimal 必须精确还原 17 位有效数字。
        long base    = 100_000_000L;
        long quote   = 100_000_000L;
        long notional = 12_345_678_901_234_567L;

        SpotExecutionReportPB pb = SpotExecutionReportPB.newBuilder()
                .setBaseScaleK(base)
                .setQuoteScaleK(quote)
                .setCumulativeQuoteQty(notional)
                .build();

        RestoredSpotExecution v = TradeEventRestorer.restore(pb);

        assertBd("1.2345678901234567", v.cumulativeQuoteQty);
    }

    // ---------------- MatcherTradeEvent（链表自带 scale） ----------------

    @Test
    void restore_matcherTradeEvent_singleNodeRecoversScales() {
        // 单档成交：0.05 BTC @ 50000 USDT
        //   size_long   = 0.05 × baseScale  = 500
        //   price_long  = 50000 × quoteScale = 5_000_000_000
        //   bidderHoldPrice_long = 51000 × quoteScale = 5_100_000_000 (买方原本冻结的高价)
        MatcherTradeEvent pb = MatcherTradeEvent.newBuilder()
                .setEventType(MatcherEventType.TRADE)
                .setSection(0)
                .setActiveOrderCompleted(false)
                .setMatchedOrderId(2001L)
                .setMatchedOrderUid(42L)
                .setMatchedOrderCompleted(true)
                .setBaseScaleK(BASE_SCALE_K)
                .setQuoteScaleK(QUOTE_SCALE_K)
                .setSize(500L)
                .setPrice(5_000_000_000L)
                .setBidderHoldPrice(5_100_000_000L)
                .build();

        RestoredMatcherTradeEvent v = TradeEventRestorer.restore(pb);

        assertEquals(MatcherEventType.TRADE, v.eventType);
        assertEquals(2001L, v.matchedOrderId);
        assertEquals(42L,   v.matchedOrderUid);
        assertEquals(true,  v.matchedOrderCompleted);

        assertBd("0.05",  v.size);
        assertBd("50000", v.price);
        assertBd("51000", v.bidderHoldPrice);

        assertNull(v.next);
    }

    @Test
    void restore_matcherTradeEvent_chainExpandsToLinkedList() {
        // 一个吃单连吃三档：50000 / 50100 / 50200
        MatcherTradeEvent third = MatcherTradeEvent.newBuilder()
                .setEventType(MatcherEventType.TRADE)
                .setBaseScaleK(BASE_SCALE_K).setQuoteScaleK(QUOTE_SCALE_K)
                .setSize(200L).setPrice(5_020_000_000L).build();
        MatcherTradeEvent second = MatcherTradeEvent.newBuilder()
                .setEventType(MatcherEventType.TRADE)
                .setBaseScaleK(BASE_SCALE_K).setQuoteScaleK(QUOTE_SCALE_K)
                .setSize(300L).setPrice(5_010_000_000L)
                .setNextEvent(third).build();
        MatcherTradeEvent head = MatcherTradeEvent.newBuilder()
                .setEventType(MatcherEventType.TRADE)
                .setBaseScaleK(BASE_SCALE_K).setQuoteScaleK(QUOTE_SCALE_K)
                .setSize(500L).setPrice(5_000_000_000L)
                .setNextEvent(second).build();

        RestoredMatcherTradeEvent v = TradeEventRestorer.restore(head);

        assertBd("0.05",  v.size); assertBd("50000", v.price);
        assertNotNull(v.next);
        assertBd("0.03",  v.next.size); assertBd("50100", v.next.price);
        assertNotNull(v.next.next);
        assertBd("0.02",  v.next.next.size); assertBd("50200", v.next.next.price);
        assertNull(v.next.next.next);
    }

    @Test
    void restore_matcherTradeEvent_zeroScalesSafe() {
        MatcherTradeEvent pb = MatcherTradeEvent.newBuilder().setSize(123L).setPrice(456L).build();

        RestoredMatcherTradeEvent v = TradeEventRestorer.restore(pb);

        assertBd("0", v.size);
        assertBd("0", v.price);
    }

    @Test
    void restore_futuresLargeProductScaleValue_keepsExactPrecision() {
        long qScale = 100_000_000L;
        long pScale = 100_000_000L;
        long notional = 12_345_678_901_234_567L;

        FuturesExecutionReportPB pb = FuturesExecutionReportPB.newBuilder()
                .setOrderQtyScale(qScale)
                .setPriceScale(pScale)
                .setCumQuoteQty(notional)
                .build();

        RestoredFuturesExecution v = TradeEventRestorer.restore(pb);

        assertBd("1.2345678901234567", v.cumQuoteQty);
    }

    // ---------------- Kafka 消费者 byte[] 入口 ----------------

    @Test
    void restoreSpotFromBytes_equivalentToPb() throws InvalidProtocolBufferException {
        SpotExecutionReportPB pb = SpotExecutionReportPB.newBuilder()
                .setBaseScaleK(BASE_SCALE_K).setQuoteScaleK(QUOTE_SCALE_K)
                .setSymbol(1).setAccountId(42L).setOrderId(100L)
                .setQty(1_000L).setPrice(5_000_000_000L)
                .build();

        RestoredSpotExecution fromPb    = TradeEventRestorer.restore(pb);
        RestoredSpotExecution fromBytes = TradeEventRestorer.restoreSpot(pb.toByteArray());

        assertEquals(fromPb.symbol, fromBytes.symbol);
        assertEquals(fromPb.orderId, fromBytes.orderId);
        assertEquals(0, fromPb.qty.compareTo(fromBytes.qty));
        assertEquals(0, fromPb.price.compareTo(fromBytes.price));
    }

    @Test
    void restoreFuturesFromBytes_equivalentToPb() throws InvalidProtocolBufferException {
        FuturesExecutionReportPB pb = FuturesExecutionReportPB.newBuilder()
                .setOrderQtyScale(BASE_SCALE_K).setPriceScale(QUOTE_SCALE_K)
                .setSymbol(12).setUserId(42L).setOrderId(200L)
                .setOrderType(OrderType.GTC)
                .setOrderQty(1_000L).setPrice(5_000_000_000L)
                .build();

        RestoredFuturesExecution fromPb    = TradeEventRestorer.restore(pb);
        RestoredFuturesExecution fromBytes = TradeEventRestorer.restoreFutures(pb.toByteArray());

        assertEquals(fromPb.symbol, fromBytes.symbol);
        assertEquals(fromPb.orderId, fromBytes.orderId);
        assertEquals(0, fromPb.orderQty.compareTo(fromBytes.orderQty));
        assertEquals(0, fromPb.price.compareTo(fromBytes.price));
    }

    @Test
    void restoreSpotFromBytes_malformed_throws() {
        assertThrows(InvalidProtocolBufferException.class,
                () -> TradeEventRestorer.restoreSpot(new byte[]{(byte) 0xff, (byte) 0xff}));
    }

    @Test
    void restoreFuturesFromBytes_malformed_throws() {
        assertThrows(InvalidProtocolBufferException.class,
                () -> TradeEventRestorer.restoreFutures(new byte[]{(byte) 0xff, (byte) 0xff}));
    }
}
