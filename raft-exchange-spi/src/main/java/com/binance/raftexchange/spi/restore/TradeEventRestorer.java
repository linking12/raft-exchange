package com.binance.raftexchange.spi.restore;

import com.binance.raftexchange.stubs.FuturesExecutionReportPB;
import com.binance.raftexchange.stubs.OrderType;
import com.binance.raftexchange.stubs.SpotExecutionReportPB;
import com.binance.raftexchange.stubs.response.MatcherTradeEvent;
import com.binance.raftexchange.spi.restore.model.RestoredFuturesExecution;
import com.binance.raftexchange.spi.restore.model.RestoredMatcherTradeEvent;
import com.binance.raftexchange.spi.restore.model.RestoredSpotExecution;
import com.google.protobuf.InvalidProtocolBufferException;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 将 Spot / Futures ExecutionReportPB 中的裸 long 字段按所属 scale 体系还原为 BigDecimal。
 *
 * <p>
 * 设计与 {@link FundEventRestorer} 对称：相同的 long+scale 还原模式， 输出对象以 {@code Restored} 前缀区别于 PB 层的 long 编码态。
 * </p>
 *
 * <p>
 * commission / fee 按 product scale 还原（百分比费率模式下的实际编码）。 固定费率模式（{@code spec.feeScaleK == 0}）下编码不同，本工具不予特殊处理， 详见
 * {@link RestoredSpotExecution} / {@link RestoredFuturesExecution}。
 * </p>
 */
public final class TradeEventRestorer {

    private TradeEventRestorer() {}

    /** Kafka 现货执行报告主题：byte[] → PB → Restored。 */
    public static RestoredSpotExecution restoreSpot(byte[] bytes) throws InvalidProtocolBufferException {
        return restore(SpotExecutionReportPB.parseFrom(bytes));
    }

    /** Kafka 期货执行报告主题：byte[] → PB → Restored。 */
    public static RestoredFuturesExecution restoreFutures(byte[] bytes) throws InvalidProtocolBufferException {
        return restore(FuturesExecutionReportPB.parseFrom(bytes));
    }

    public static RestoredSpotExecution restore(SpotExecutionReportPB pb) {
        RestoredSpotExecution v = new RestoredSpotExecution();
        long base = pb.getBaseScaleK();
        long quote = pb.getQuoteScaleK();
        long product = (base != 0 && quote != 0) ? Math.multiplyExact(base, quote) : 0;

        v.executionId = pb.getExecutionId();
        v.executionType = pb.getExecutionType();
        v.orderStatus = pb.getOrderStatus();
        v.symbol = pb.getSymbol();
        v.accountId = pb.getAccountId();
        v.clOrdId = pb.getClOrdId();
        v.orderId = pb.getOrderId();
        v.orderType = pb.getOrderType();
        v.side = pb.getSide();
        v.orderCreationTime = pb.getOrderCreationTime();
        v.tradeId = pb.getTradeId();
        v.isMaker = pb.getIsMaker();
        v.workingIndicator = pb.getWorkingIndicator();

        // base scale
        v.qty = div(pb.getQty(), base);
        v.lastQty = div(pb.getLastQty(), base);
        v.cumulativeQty = div(pb.getCumulativeQty(), base);

        // quote scale
        v.price = div(pb.getPrice(), quote);
        v.lastPrice = div(pb.getLastPrice(), quote);

        // product scale (size × price，含百分比 fee；以及 BUDGET 单的 quoteOrderQty 总预算)
        v.quoteOrderQty = div(pb.getQuoteOrderQty(), product);
        v.lastQuoteQty = div(pb.getLastQuoteQty(), product);
        v.cumulativeQuoteQty = div(pb.getCumulativeQuoteQty(), product);
        v.commission = div(pb.getCommission(), product);

        // pass-through (currency ID)
        v.commissionAsset = pb.getCommissionAsset();

        return v;
    }

    public static RestoredFuturesExecution restore(FuturesExecutionReportPB pb) {
        RestoredFuturesExecution v = new RestoredFuturesExecution();
        long qScale = pb.getOrderQtyScale();
        long pScale = pb.getPriceScale();
        long product = (qScale != 0 && pScale != 0) ? Math.multiplyExact(qScale, pScale) : 0;

        v.uniId = pb.getUniId();
        v.executionType = pb.getExecutionType();
        v.orderStatus = pb.getOrderStatus();
        v.symbol = pb.getSymbol();
        v.userId = pb.getUserId();
        v.clOrdId = pb.getClOrdId();
        v.orderId = pb.getOrderId();
        v.orderType = pb.getOrderType();
        v.side = pb.getSide();
        v.counterpartyId = pb.getCounterpartyId();
        v.createTime = pb.getCreateTime();
        v.execId = pb.getExecId();
        v.contractType = pb.getContractType();
        v.positionSide = pb.getPositionSide();
        v.isMaker = pb.getIsMaker();

        // BUDGET 单的 result.orderQty 被引擎重载为 cmd.price（=product-scale 总预算 notional）；
        // 限价/IOC 单的 result.orderQty = cmd.size（orderQty scale）。
        // 见 ITradeEventsHandler 各 futures 分支 `result.orderQty = budgetOrder ? cmd.price : cmd.size`。
        final boolean budgetOrder = pb.getOrderType() == OrderType.FOK_BUDGET
                || pb.getOrderType() == OrderType.IOC_BUDGET;

        v.orderQty = div(pb.getOrderQty(), budgetOrder ? product : qScale);
        v.lastQty = div(pb.getLastQty(), qScale);
        v.cumQty = div(pb.getCumQty(), qScale);

        // price scale
        v.price = div(pb.getPrice(), pScale);
        v.lastPx = div(pb.getLastPx(), pScale);
        v.avgPx = div(pb.getAvgPx(), pScale);

        // product scale (filledNotional = cum size × price，含百分比 fee)
        v.cumQuoteQty = div(pb.getCumQuoteQty(), product);
        v.fee = div(pb.getFee(), product);

        // pass-through (currency ID)
        v.feeAssetId = pb.getFeeAssetId();

        return v;
    }

    /**
     * 还原 MatcherTradeEvent 链表。每个节点用 PB 自带的 baseScaleK/quoteScaleK， 沿 next_event 链迭代展开（非递归，避免长链栈溢出）。
     */
    public static RestoredMatcherTradeEvent restore(MatcherTradeEvent pb) {
        RestoredMatcherTradeEvent head = restoreSingle(pb);
        RestoredMatcherTradeEvent tail = head;
        MatcherTradeEvent cur = pb;
        while (cur.hasNextEvent()) {
            cur = cur.getNextEvent();
            tail.next = restoreSingle(cur);
            tail = tail.next;
        }
        return head;
    }

    private static RestoredMatcherTradeEvent restoreSingle(MatcherTradeEvent pb) {
        RestoredMatcherTradeEvent v = new RestoredMatcherTradeEvent();
        long base = pb.getBaseScaleK();
        long quote = pb.getQuoteScaleK();

        v.eventType = pb.getEventType();
        v.section = pb.getSection();
        v.activeOrderCompleted = pb.getActiveOrderCompleted();
        v.matchedOrderId = pb.getMatchedOrderId();
        v.matchedOrderUid = pb.getMatchedOrderUid();
        v.matchedOrderCompleted = pb.getMatchedOrderCompleted();

        v.size = div(pb.getSize(), base);
        v.price = div(pb.getPrice(), quote);
        v.bidderHoldPrice = div(pb.getBidderHoldPrice(), quote);

        return v;
    }

    private static BigDecimal div(long value, long divisor) {
        if (divisor == 0)
            return BigDecimal.ZERO;
        return BigDecimal.valueOf(value).divide(BigDecimal.valueOf(divisor), MathContext.DECIMAL128);
    }
}
