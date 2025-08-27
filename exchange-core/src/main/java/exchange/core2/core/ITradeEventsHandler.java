package exchange.core2.core;

import java.util.List;

import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import lombok.Data;

import static exchange.core2.core.ITradeEventsHandler.ExecutionIdGenerator.buildCancelExecId;
import static exchange.core2.core.ITradeEventsHandler.ExecutionIdGenerator.buildNewExecId;
import static exchange.core2.core.ITradeEventsHandler.ExecutionIdGenerator.buildRejectExecId;
import static exchange.core2.core.ITradeEventsHandler.ExecutionIdGenerator.buildTradeExecId;
import static exchange.core2.core.ITradeEventsHandler.ExecutionIdGenerator.buildTradeId;

/**
 * Convenient events handler interface for non latency-critical applications.<br>
 * Custom handler implementation should be attached to SimpleEventProcessor.<br>
 * Handler method are invoked from single thread in following order:
 * <table summary="execution order">
 * <tr>
 * <td>1.</td>
 * <td>commandResult</td>
 * </tr>
 * <tr>
 * <td>2A.</td>
 * <td>optional reduceEvent
 * <td>optional tradeEvent</td>
 * </tr>
 * <tr>
 * <td>2B.</td>
 * <td>
 * <td>optional rejectEvent</td>
 * </tr>
 * <tr>
 * <td>3.</td>
 * <td>orderBook - mandatory for ApiOrderBookRequest, optional for other commands</td>
 * </tr>
 * </table>
 * Events processing will stop immediately if any handler throws an exception - you should consider wrapping logic into
 * try-catch block if necessary.
 */
public interface ITradeEventsHandler {

    /**
     * Method is called after each commands execution.
     *
     * @param commandResult - immutable object describing original command, result code, and assigned sequence number.
     */
    void commandResult(ApiCommandResult commandResult);

    /**
     * Method is called if order execution was resulted to one or more trades.
     *
     * @param tradeEvent - immutable object describing event details
     */
    void tradeEvent(TradeEvent tradeEvent);

    /**
     * Method is called if Cancel or Reduce command was successfully executed.
     *
     * @param reduceEvent - immutable object describing event details
     */
    void reduceEvent(ReduceEvent reduceEvent);

    /**
     * Method is called when order book snapshot (L2MarketData) was attached to commands by matching engine. That always
     * happens for ApiOrderBookRequest, sometimes for other commands.
     *
     * @param orderBook - immutable object containing L2 OrderBook snapshot
     */
    void orderBook(OrderBook orderBook);

    @Data
    class ApiCommandResult {
        public final ApiCommand command;
        public final CommandResultCode resultCode;
        public final long seq;
    }

    @Data
    class TradeEvent {
        public final int symbol;
        public final long baseScaleK;
        public final long quoteScaleK;
        public final long totalVolume;
        public final long takerOrderId;
        public final long takerUid;
        public final OrderAction takerAction;
        public final boolean takeOrderCompleted;
        public final long timestamp;
        public final List<Trade> trades;
    }

    @Data
    class Trade {
        public final long makerOrderId;
        public final long makerUid;
        public final boolean makerOrderCompleted;
        public final long price;
        public final long volume;
    }

    @Data
    class ReduceEvent {
        public final int symbol;
        public final long baseScaleK;
        public final long quoteScaleK;
        public final long reducedVolume;
        public final boolean orderCompleted;
        public final long price;
        public final long orderId;
        public final long uid;
        public final long timestamp;
    }

    @Data
    class RejectEvent {
        public final int symbol;
        public final long baseScaleK;
        public final long quoteScaleK;
        public final long rejectedVolume;
        public final long price;
        public final long orderId;
        public final long uid;
        public final long timestamp;
    }

    @Data
    class CommandExecutionResult {
        public final int symbol;
        public final long volume;
        public final long price;
        public final long orderId;
        public final long uid;
        public final long timestamp;
    }

    @Data
    class OrderBook {
        public final int symbol;
        public final List<OrderBookRecord> asks;
        public final List<OrderBookRecord> bids;
        public final long timestamp;
    }

    @Data
    class OrderBookRecord {
        public final long price;
        public final long volume;
        public final int orders;
    }

    default void executionReport(SpotExecutionReport executionReport) {

    }

    enum ExecType {NEW, TRADE, CANCELED, REJECT}

    enum OrderStatus {NEW, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED}

    @Data
    class SpotExecutionReport {
        public final long executionId; // 本记录id
        public final ExecType executionType;
        public final OrderStatus orderStatus;

        public final int symbol;
        public final long baseScaleK;
        public final long quoteScaleK;
        public final long accountId;
        public final long clOrdId; // userCookie
        public final long orderId;
        public final OrderType orderType;
        public final OrderAction side;
        public final long qty;
        public final long price;
        public final long quoteOrderQty; // 以quote计价的订单总预算，用于budget单
        public final long orderCreationTime;

        public final long tradeId; // 一个matcherEvent对应一个tradeId
        public final long lastQty;
        public final long lastPrice;
        public final long lastQuoteQty;
        public final long cumulativeQty;
        public final long cumulativeQuoteQty;
        public final long commission;
        public final int commissionAsset;
        public final boolean isMaker;
        public final boolean workingIndicator; // 是否在订单簿

        public static SpotExecutionReport placeOrder(OrderCommand cmd, long seq) {
            boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            return new SpotExecutionReport(buildNewExecId(seq),
                    ExecType.NEW,
                    OrderStatus.NEW,
                    cmd.symbol,
                    cmd.baseScaleK,
                    cmd.quoteScaleK,
                    cmd.uid,
                    cmd.userCookie,
                    cmd.orderId,
                    cmd.orderType,
                    cmd.action,
                    cmd.size,
                    budgetOrder ? 0L : cmd.price,
                    budgetOrder ? cmd.price : 0L,
                    cmd.timestamp,
                    -1L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    cmd.quoteCurrency,
                    false,
                    cmd.orderType == OrderType.GTC);
        }

        public static SpotExecutionReport rejectOrder(OrderCommand cmd, long seq) {
            boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            return new SpotExecutionReport(buildRejectExecId(seq),
                    ExecType.REJECT,
                    OrderStatus.REJECTED,
                    cmd.symbol,
                    cmd.baseScaleK,
                    cmd.quoteScaleK,
                    cmd.uid,
                    cmd.userCookie,
                    cmd.orderId,
                    cmd.orderType,
                    cmd.action,
                    cmd.size,
                    budgetOrder ? 0L : cmd.price,
                    budgetOrder ? cmd.price : 0L,
                    cmd.timestamp,
                    -1L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    cmd.quoteCurrency,
                    false,
                    false);
        }

        public static SpotExecutionReport reduceOrder(OrderCommand cmd, long seq, MatcherTradeEvent event) {
            boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            return new SpotExecutionReport(buildCancelExecId(seq),
                    ExecType.CANCELED,
                    OrderStatus.CANCELED,
                    cmd.symbol,
                    cmd.baseScaleK,
                    cmd.quoteScaleK,
                    cmd.uid,
                    cmd.userCookie,
                    cmd.orderId,
                    cmd.orderType,
                    cmd.action,
                    cmd.size,
                    budgetOrder ? 0L : cmd.price,
                    budgetOrder ? cmd.price : 0L,
                    cmd.timestamp,
                    -1L,
                    0L,
                    0L,
                    0L,
                    event.filled,
                    event.filledNotional,
                    0L,
                    cmd.quoteCurrency,
                    false,
                    false);
        }

        public static SpotExecutionReport tradeTaker(OrderCommand cmd, long seq, MatcherTradeEvent ev, int tradeIndex) {
            boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            return new SpotExecutionReport(buildTradeExecId(seq, tradeIndex, false),
                    ExecType.TRADE,
                    ev.activeOrderCompleted ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED,
                    cmd.symbol,
                    cmd.baseScaleK,
                    cmd.quoteScaleK,
                    cmd.uid,
                    cmd.userCookie,
                    cmd.orderId,
                    cmd.orderType,
                    cmd.action,
                    cmd.size,
                    budgetOrder ? 0L : cmd.price,
                    budgetOrder ? cmd.price : 0L,
                    cmd.timestamp,
                    buildTradeId(seq, tradeIndex),
                    ev.size,
                    ev.price,
                    ev.size * ev.price,
                    ev.filled,
                    ev.filledNotional,
                    0L,
                    cmd.quoteCurrency,
                    false,
                    (cmd.orderType == OrderType.GTC) && !ev.activeOrderCompleted);
        }
    }

    @Data
    class FuturesExecutionReport {




    }

    class ExecutionIdGenerator {

        // 子序号最多 4096 个（12 位）
        private static final int SHIFT_BITS = 12;

        // 固定子序号分配约定
        private static final int IDX_NEW = 0;
        private static final int IDX_REJECT = 1;
        private static final int IDX_CANCEL = 2;
        // TRADE 从 3 开始，taker/maker 成对
        private static final int IDX_TRADE_BASE = 3;

        private ExecutionIdGenerator() {
        }

        /**
         * NEW
         */
        public static long buildNewExecId(long seq) {
            return (seq << SHIFT_BITS) | IDX_NEW;
        }

        /**
         * REJECT
         */
        public static long buildRejectExecId(long seq) {
            return (seq << SHIFT_BITS) | IDX_REJECT;
        }

        /**
         * CANCEL / REDUCE
         */
        public static long buildCancelExecId(long seq) {
            return (seq << SHIFT_BITS) | IDX_CANCEL;
        }

        /**
         * TRADE（同一个 matcherEventIndex 对应两条，taker/maker 各一条）
         *
         * @param seq        全局命令序号
         * @param tradeIndex 第几笔成交（从 0 开始）
         * @param isMaker    true=maker 侧，false=taker 侧
         */
        public static long buildTradeExecId(long seq, int tradeIndex, boolean isMaker) {
            int localIdx = IDX_TRADE_BASE + (tradeIndex << 1) + (isMaker ? 1 : 0);
            return (seq << SHIFT_BITS) | localIdx;
        }

        public static long buildTradeId(long seq, int tradeIndex) {
            int localIdx = (1 << (SHIFT_BITS - 1)) + tradeIndex;
            return (seq << SHIFT_BITS) | localIdx;
        }
    }

}
