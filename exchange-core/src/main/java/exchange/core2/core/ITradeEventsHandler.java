package exchange.core2.core;

import java.util.List;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.PositionMode;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import lombok.Data;

import static exchange.core2.core.ITradeEventsHandler.ExecutionIdGenerator.buildReduceExecId;
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
     * Method is called when order book snapshot (L2MarketData) was attached to commands by matching engine. That always
     * happens for ApiOrderBookRequest, sometimes for other commands.
     *
     * @param orderBook - immutable object containing L2 OrderBook snapshot
     */
    void orderBook(OrderBook orderBook);

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

    void spotExecutionReport(SpotExecutionReport executionReport);

    void futuresExecutionReport(FuturesExecutionReport executionReport);

    enum ExecType {NEW, TRADE, REDUCE, CANCEL, REJECT}

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

        public final long tradeId; // 一个matcherEvent对应一个tradeId, maker taker公用一个id
        public final long lastQty;
        public final long lastPrice;
        public final long lastQuoteQty;
        public final long cumulativeQty;
        public final long cumulativeQuoteQty;
        public final long commission;
        public final int commissionAsset;
        public final boolean isMaker;
        public final boolean workingIndicator; // 是否在订单簿

        public static SpotExecutionReport placeOrder(OrderCommand cmd, long seq, CoreSymbolSpecification spec) {
            boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            return new SpotExecutionReport(buildNewExecId(seq),
                    ExecType.NEW,
                    OrderStatus.NEW,
                    cmd.symbol,
                    spec.baseScaleK,
                    spec.quoteScaleK,
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
                    spec.quoteCurrency,
                    false,
                    cmd.orderType == OrderType.GTC);
        }

        public static SpotExecutionReport rejectOrder(OrderCommand cmd, long seq, CoreSymbolSpecification spec) {
            boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            return new SpotExecutionReport(buildRejectExecId(seq),
                    ExecType.REJECT,
                    OrderStatus.REJECTED,
                    cmd.symbol,
                    spec.baseScaleK,
                    spec.quoteScaleK,
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
                    spec.quoteCurrency,
                    false,
                    false);
        }

        public static SpotExecutionReport reduceOrder(OrderCommand cmd, long seq, CoreSymbolSpecification spec,
                                                      MatcherTradeEvent event) {
            boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            ExecType execType;
            OrderStatus orderStatus;
            if (cmd.command == OrderCommandType.REDUCE_ORDER) {
                execType = ExecType.REDUCE;
                if (event.filled == 0) {
                    orderStatus = OrderStatus.NEW;
                } else if (event.activeOrderCompleted) {
                    orderStatus = OrderStatus.CANCELED;
                } else {
                    orderStatus = OrderStatus.PARTIALLY_FILLED;
                }
            } else {
                execType = ExecType.CANCEL;
                orderStatus = OrderStatus.CANCELED;
            }
            return new SpotExecutionReport(buildReduceExecId(seq),
                    execType,
                    orderStatus,
                    cmd.symbol,
                    spec.baseScaleK,
                    spec.quoteScaleK,
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
                    spec.quoteCurrency,
                    false,
                    false);
        }

        public static SpotExecutionReport tradeTaker(OrderCommand cmd, long seq, CoreSymbolSpecification spec,
                                                     MatcherTradeEvent ev, int tradeIndex) {
            boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            return new SpotExecutionReport(buildTradeExecId(seq, tradeIndex, false),
                    ExecType.TRADE,
                    ev.activeOrderCompleted ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED,
                    cmd.symbol,
                    spec.baseScaleK,
                    spec.quoteScaleK,
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
                    spec.quoteCurrency,
                    false,
                    cmd.orderType == OrderType.GTC && !ev.activeOrderCompleted);
        }

        public static SpotExecutionReport tradeMaker(OrderCommand cmd, long seq, CoreSymbolSpecification spec,
                                                     MatcherTradeEvent ev, int tradeIndex) {
            boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            return new SpotExecutionReport(buildTradeExecId(seq, tradeIndex, true),
                    ExecType.TRADE,
                    ev.matchedOrderCompleted ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED,
                    cmd.symbol,
                    spec.baseScaleK,
                    spec.quoteScaleK,
                    ev.matchedOrderUid,
                    ev.matchedUserCookie,
                    ev.matchedOrderId,
                    ev.matchedOrderType,
                    cmd.action.opposite(),
                    ev.matchedOrderSize,
                    budgetOrder ? 0L : ev.matchedOrderPrice,
                    budgetOrder ? ev.matchedOrderPrice : 0L,
                    ev.matchedOrderTimestamp,
                    buildTradeId(seq, tradeIndex),
                    ev.size,
                    ev.price,
                    ev.size * ev.price,
                    ev.matchedOrderFilled,
                    ev.matchedOrderFilledNotional,
                    0L,
                    spec.quoteCurrency,
                    false,
                    ev.matchedOrderType == OrderType.GTC && !ev.matchedOrderCompleted);
        }
    }

    @Data
    class FuturesExecutionReport {
        public final long uniId;
        public final ExecType executionType;
        public final OrderStatus orderStatus;

        public final int symbolId;
        public final long orderQtyScale;
        public final long priceScale;
        public final long userId;
        public final long clOrderId; // userCookie
        public final long orderId;
        public final OrderType orderType;
        public final OrderAction side;
        public final long counterpartyId; // 对手方uid
        public final long price;
        public final long orderQty;
        public final long createTime;

        public final long execId; // 一个matcherEvent对应一个execId, maker taker公用一个id
        public final SymbolType contractType; //判断 PERPETUAL/DELIVERY
        public final PositionMode positionSide;
        public final long lastQty; // ev.size
        public final long lastPx; // ev.price
        public final long cumQty;
        public final long cumQuoteQty;
        public final long avgPx; // cumQuoteQty / cumQty
        public final long fee;
        public final int feeAssetId;
        public final boolean isMaker;
        public final long bidsNotional; // 未成交部分名义价值，pendingBuySize * pendingBuyAvgPrice
        public final long asksNotional;
        public final long bidsQty;  // 剩余买单数量，pendingBuySize
        public final long asksQty;

        public static FuturesExecutionReport placeOrder(OrderCommand cmd, long seq, CoreSymbolSpecification spec,
                                                        UserProfile userProfile) {
            boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            return new FuturesExecutionReport(buildNewExecId(seq),
                    ExecType.NEW,
                    OrderStatus.NEW,
                    cmd.symbol,
                    spec.baseScaleK,
                    spec.quoteScaleK,
                    cmd.uid,
                    cmd.userCookie,
                    cmd.orderId,
                    cmd.orderType,
                    cmd.action,
                    -1L,
                    budgetOrder ? 0 : cmd.price,
                    budgetOrder ? cmd.price : cmd.size,
                    cmd.timestamp,
                    -1L,
                    spec.type,
                    userProfile.positionMode,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    spec.quoteCurrency,
                    false,
                    0L,
                    0L,
                    0L,
                    0L);
        }

        public static FuturesExecutionReport rejectOrder(OrderCommand cmd, long seq, CoreSymbolSpecification spec,
                                                         UserProfile userProfile) {
            final boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            return new FuturesExecutionReport(buildRejectExecId(seq),
                    ExecType.REJECT,
                    OrderStatus.REJECTED,
                    cmd.symbol,
                    spec.baseScaleK,
                    spec.quoteScaleK,
                    cmd.uid,
                    cmd.userCookie,
                    cmd.orderId,
                    cmd.orderType,
                    cmd.action,
                    -1L,
                    budgetOrder ? 0L : cmd.price,
                    budgetOrder ? cmd.price : cmd.size,
                    cmd.timestamp,
                    -1L,
                    spec.type,
                    userProfile.positionMode,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    spec.quoteCurrency,
                    false,
                    0L,
                    0L,
                    0L,
                    0L);
        }

        public static FuturesExecutionReport reduceOrder(OrderCommand cmd, long seq, CoreSymbolSpecification spec,
                                                         UserProfile userProfile, MatcherTradeEvent event) {
            final boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            ExecType execType;
            OrderStatus orderStatus;
            if (cmd.command == OrderCommandType.REDUCE_ORDER) {
                execType = ExecType.REDUCE;
                if (event.filled == 0) {
                    orderStatus = OrderStatus.NEW;
                } else if (event.activeOrderCompleted) {
                    orderStatus = OrderStatus.CANCELED;
                } else {
                    orderStatus = OrderStatus.PARTIALLY_FILLED;
                }
            } else {
                execType = ExecType.CANCEL;
                orderStatus = OrderStatus.CANCELED;
            }
            return new FuturesExecutionReport(buildReduceExecId(seq),
                    execType,
                    orderStatus,
                    cmd.symbol,
                    spec.baseScaleK,
                    spec.quoteScaleK,
                    cmd.uid,
                    cmd.userCookie,
                    cmd.orderId,
                    cmd.orderType,
                    cmd.action,
                    -1L,
                    budgetOrder ? 0L : cmd.price,
                    budgetOrder ? cmd.price : cmd.size,
                    cmd.timestamp,
                    -1L,
                    spec.type,
                    userProfile.positionMode,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    0L,
                    spec.quoteCurrency,
                    false,
                    0L,
                    0L,
                    0L,
                    0L);
        }

        public static FuturesExecutionReport tradeTaker(OrderCommand cmd, long seq, CoreSymbolSpecification spec,
                                                        UserProfile userProfile, MatcherTradeEvent event, int tradeIndex) {
            final boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            long bidsNotional = -1;
            long asksNotional = -1;
            long bidsQty = -1;
            long asksQty = -1;
            if (event.activeOrderCompleted) {
                bidsNotional = 0;
                asksNotional = 0;
                bidsQty = 0;
                asksQty = 0;
            } else {
                SymbolPositionRecord pos = userProfile.positions.get(userProfile.createPositionsKey(cmd.symbol, cmd.action, cmd.command));
                if (pos != null) {
                    bidsNotional = pos.pendingBuySize * pos.pendingBuyAvgPrice;
                    asksNotional = pos.pendingSellSize * pos.pendingSellAvgPrice;
                    bidsQty = pos.pendingBuySize;
                    asksQty = pos.pendingSellSize;
                }
            }
            return new FuturesExecutionReport(buildTradeExecId(seq, tradeIndex, false),
                    ExecType.TRADE,
                    event.activeOrderCompleted ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED,
                    cmd.symbol,
                    spec.baseScaleK,
                    spec.quoteScaleK,
                    cmd.uid,
                    cmd.userCookie,
                    cmd.orderId,
                    cmd.orderType,
                    cmd.action,
                    event.matchedOrderUid,
                    budgetOrder ? 0L : cmd.price,
                    budgetOrder ? cmd.price : cmd.size,
                    cmd.timestamp,
                    buildTradeId(seq, tradeIndex),
                    spec.type,
                    userProfile.positionMode,
                    event.size,
                    event.price,
                    event.filled,
                    event.filledNotional,
                    event.filled == 0 ? 0L : event.filledNotional / event.filled,
                    0L,
                    spec.quoteCurrency,
                    false,
                    bidsNotional,
                    asksNotional,
                    bidsQty,
                    asksQty);
        }

        public static FuturesExecutionReport tradeMaker(OrderCommand cmd, long seq, CoreSymbolSpecification spec,
                                                        UserProfile makerProfile, MatcherTradeEvent event, int tradeIndex) {
            final boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            long bidsNotional = -1;
            long asksNotional = -1;
            long bidsQty = -1;
            long asksQty = -1;
            if (event.matchedOrderCompleted) {
                bidsNotional = 0;
                asksNotional = 0;
                bidsQty = 0;
                asksQty = 0;
            } else {
                SymbolPositionRecord pos = makerProfile.positions.get(makerProfile.createPositionsKey(cmd.symbol, cmd.action.opposite(), event.matchedOrderCommandType));
                if (pos != null) {
                    bidsNotional = pos.pendingBuySize * pos.pendingBuyAvgPrice;
                    asksNotional = pos.pendingSellSize * pos.pendingSellAvgPrice;
                    bidsQty = pos.pendingBuySize;
                    asksQty = pos.pendingSellSize;
                }
            }
            return new FuturesExecutionReport(buildTradeExecId(seq, tradeIndex, true),
                    ExecType.TRADE,
                    event.matchedOrderCompleted ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED,
                    cmd.symbol,
                    spec.baseScaleK,
                    spec.quoteScaleK,
                    event.matchedOrderUid,
                    event.matchedUserCookie,
                    event.matchedOrderUid,
                    event.matchedOrderType,
                    cmd.action.opposite(),
                    cmd.uid,
                    budgetOrder ? 0L : event.matchedOrderPrice,
                    budgetOrder ? event.matchedOrderPrice : event.matchedOrderSize,
                    event.matchedOrderTimestamp,
                    buildTradeId(seq, tradeIndex),
                    spec.type,
                    makerProfile.positionMode,
                    event.size,
                    event.price,
                    event.matchedOrderFilled,
                    event.matchedOrderFilledNotional,
                    event.matchedOrderFilled == 0 ? 0L : event.matchedOrderFilledNotional / event.matchedOrderFilled,
                    0L,
                    spec.quoteCurrency,
                    true,
                    bidsNotional,
                    asksNotional,
                    bidsQty,
                    asksQty);
        }
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
        public static long buildReduceExecId(long seq) {
            return (seq << SHIFT_BITS) | IDX_CANCEL;
        }

        /**
         * execId for TRADE（同一个 matcherEventIndex 对应两条，taker/maker 各一条）
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
