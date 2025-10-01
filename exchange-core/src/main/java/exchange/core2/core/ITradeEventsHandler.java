package exchange.core2.core;

import java.util.ArrayDeque;
import java.util.List;

import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.PositionMode;
import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.Data;
import lombok.Getter;

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

    default void process(SpotExecutionReport executionReport) {
        spotExecutionReport(executionReport);
        executionReport.recycle();
    }

    void futuresExecutionReport(FuturesExecutionReport executionReport);

    default void process(FuturesExecutionReport executionReport) {
        futuresExecutionReport(executionReport);
        executionReport.recycle();
    }

    enum ExecType {NEW, TRADE, REDUCE, CANCEL, REJECT}

    enum OrderStatus {NEW, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED}

    @Getter
    class SpotExecutionReport {
        private static final int POOL_SIZE = 1024;
        private static final ArrayDeque<SpotExecutionReport> POOL = new ArrayDeque<>(POOL_SIZE);

        public long executionId; // 本记录id
        public ExecType executionType;
        public OrderStatus orderStatus;

        public int symbol;
        public long baseScaleK;
        public long quoteScaleK;
        public long accountId;
        public long clOrdId; // userCookie
        public long orderId;
        public OrderType orderType;
        public OrderAction side;
        public long qty;
        public long price;
        public long quoteOrderQty; // 以quote计价的订单总预算，用于budget单
        public long orderCreationTime;

        public long tradeId; // 一个matcherEvent对应一个tradeId, maker taker公用一个id
        public long lastQty;
        public long lastPrice;
        public long lastQuoteQty;
        public long cumulativeQty;
        public long cumulativeQuoteQty;
        public long commission;
        public int commissionAsset;
        public boolean isMaker;
        public boolean workingIndicator; // 是否在订单簿

        private SpotExecutionReport() {}

        private static SpotExecutionReport borrow() {
            SpotExecutionReport obj = POOL.pollFirst();
            return (obj != null) ? obj : new SpotExecutionReport();
        }

        private void clear() {
            executionId = 0L;
            executionType = null;
            orderStatus = null;
            symbol = 0;
            baseScaleK = 0L;
            quoteScaleK = 0L;
            accountId = 0L;
            clOrdId = 0L;
            orderId = 0L;
            orderType = null;
            side = null;
            qty = 0L;
            price = 0L;
            quoteOrderQty = 0L;
            orderCreationTime = 0L;
            tradeId = -1L;
            lastQty = 0L;
            lastPrice = 0L;
            lastQuoteQty = 0L;
            cumulativeQty = 0L;
            cumulativeQuoteQty = 0L;
            commission = 0L;
            commissionAsset = 0;
            isMaker = false;
            workingIndicator = false;
        }

        private void recycle() {
            clear();
            if (POOL.size() < POOL_SIZE) {
                POOL.addFirst(this);
            }
        }

        public static SpotExecutionReport placeOrder(OrderCommand cmd, long seq, CoreSymbolSpecification spec) {
            boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            SpotExecutionReport result = SpotExecutionReport.borrow();
            result.executionId = buildNewExecId(seq);
            result.executionType = ExecType.NEW;
            result.orderStatus = OrderStatus.NEW;
            result.symbol = cmd.symbol;
            result.baseScaleK = spec.baseScaleK;
            result.quoteScaleK = spec.quoteScaleK;
            result.accountId = cmd.uid;
            result.clOrdId = cmd.userCookie;
            result.orderId = cmd.orderId;
            result.orderType = cmd.orderType;
            result.side = cmd.action;
            result.qty = cmd.size;
            result.price = budgetOrder ? 0L : cmd.price;
            result.quoteOrderQty = budgetOrder ? cmd.price : 0L;
            result.orderCreationTime = cmd.timestamp;
            result.commissionAsset = spec.quoteCurrency;
            result.workingIndicator = cmd.orderType == OrderType.GTC;
            return result;
        }

        public static SpotExecutionReport rejectOrder(OrderCommand cmd, long seq, CoreSymbolSpecification spec) {
            boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            SpotExecutionReport result = SpotExecutionReport.borrow();
            result.executionId = buildRejectExecId(seq);
            result.executionType = ExecType.REJECT;
            result.orderStatus = OrderStatus.REJECTED;
            result.symbol = cmd.symbol;
            result.baseScaleK = spec.baseScaleK;
            result.quoteScaleK = spec.quoteScaleK;
            result.accountId = cmd.uid;
            result.clOrdId = cmd.userCookie;
            result.orderId = cmd.orderId;
            result.orderType = cmd.orderType;
            result.side = cmd.action;
            result.qty = cmd.size;
            result.price = budgetOrder ? 0L : cmd.price;
            result.quoteOrderQty = budgetOrder ? cmd.price : 0L;
            result.orderCreationTime = cmd.timestamp;
            result.commissionAsset = spec.quoteCurrency;
            return result;
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
            SpotExecutionReport result = SpotExecutionReport.borrow();
            result.executionId = buildReduceExecId(seq);
            result.executionType = execType;
            result.orderStatus = orderStatus;
            result.symbol = cmd.symbol;
            result.baseScaleK = spec.baseScaleK;
            result.quoteScaleK = spec.quoteScaleK;
            result.accountId = cmd.uid;
            result.clOrdId = cmd.userCookie;
            result.orderId = cmd.orderId;
            result.orderType = cmd.orderType;
            result.side = cmd.action;
            result.qty = cmd.size;
            result.price = budgetOrder ? 0L : cmd.price;
            result.quoteOrderQty = budgetOrder ? cmd.price : 0L;
            result.orderCreationTime = cmd.timestamp;
            result.cumulativeQty = event.filled;
            result.cumulativeQuoteQty = event.filledNotional;
            result.commissionAsset = spec.quoteCurrency;
            return result;
        }

        public static SpotExecutionReport tradeTaker(OrderCommand cmd, long seq, CoreSymbolSpecification spec,
                                                     MatcherTradeEvent ev, int tradeIndex) {
            boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            SpotExecutionReport result = SpotExecutionReport.borrow();
            result.executionId = buildTradeExecId(seq, tradeIndex, false);
            result.executionType = ExecType.TRADE;
            result.orderStatus = ev.activeOrderCompleted ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
            result.symbol = cmd.symbol;
            result.baseScaleK = spec.baseScaleK;
            result.quoteScaleK = spec.quoteScaleK;
            result.accountId = cmd.uid;
            result.clOrdId = cmd.userCookie;
            result.orderId = cmd.orderId;
            result.orderType = cmd.orderType;
            result.side = cmd.action;
            result.qty = cmd.size;
            result.price = budgetOrder ? 0L : cmd.price;
            result.quoteOrderQty = budgetOrder ? cmd.price : 0L;
            result.orderCreationTime = cmd.timestamp;
            result.tradeId = buildTradeId(seq, tradeIndex);
            result.lastQty = ev.size;
            result.lastPrice = ev.price;
            result.lastQuoteQty = ev.size * ev.price;
            result.cumulativeQty = ev.filled;
            result.cumulativeQuoteQty = ev.filledNotional;
            result.commission = CoreArithmeticUtils.calculateTakerFee(ev.size, ev.price, spec);
            result.commissionAsset = spec.quoteCurrency;
            result.isMaker = false;
            result.workingIndicator = cmd.orderType == OrderType.GTC && !ev.activeOrderCompleted;
            return result;
        }

        public static SpotExecutionReport tradeMaker(OrderCommand cmd, long seq, CoreSymbolSpecification spec,
                                                     MatcherTradeEvent ev, int tradeIndex) {
            boolean budgetOrder = ev.matchedOrderType == OrderType.FOK_BUDGET || ev.matchedOrderType == OrderType.IOC_BUDGET;
            SpotExecutionReport result = SpotExecutionReport.borrow();
            result.executionId = buildTradeExecId(seq, tradeIndex, true);
            result.executionType = ExecType.TRADE;
            result.orderStatus = ev.matchedOrderCompleted ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
            result.symbol = cmd.symbol;
            result.baseScaleK = spec.baseScaleK;
            result.quoteScaleK = spec.quoteScaleK;
            result.accountId = ev.matchedOrderUid;
            result.clOrdId = ev.matchedUserCookie;
            result.orderId = ev.matchedOrderId;
            result.orderType = ev.matchedOrderType;
            result.side = cmd.action.opposite();
            result.qty = ev.matchedOrderSize;
            result.price = budgetOrder ? 0L : ev.matchedOrderPrice;
            result.quoteOrderQty = budgetOrder ? ev.matchedOrderPrice : 0L;
            result.orderCreationTime = ev.matchedOrderTimestamp;
            result.tradeId = buildTradeId(seq, tradeIndex);
            result.lastQty = ev.size;
            result.lastPrice = ev.price;
            result.lastQuoteQty = ev.size * ev.price;
            result.cumulativeQty = ev.matchedOrderFilled;
            result.cumulativeQuoteQty = ev.matchedOrderFilledNotional;
            result.commission = CoreArithmeticUtils.calculateMakerFee(ev.size, ev.price, spec);
            result.commissionAsset = spec.quoteCurrency;
            result.isMaker = true;
            result.workingIndicator = ev.matchedOrderType == OrderType.GTC && !ev.matchedOrderCompleted;
            return result;
        }
    }

    @Getter
    class FuturesExecutionReport {
        private static final int POOL_SIZE = 1024;
        private static final ArrayDeque<FuturesExecutionReport> POOL = new ArrayDeque<>(POOL_SIZE);

        public long uniId;
        public ExecType executionType;
        public OrderStatus orderStatus;

        public int symbolId;
        public long orderQtyScale;
        public long priceScale;
        public long userId;
        public long clOrderId; // userCookie
        public long orderId;
        public OrderType orderType;
        public OrderAction side;
        public long counterpartyId; // 对手方uid
        public long price;
        public long orderQty;
        public long createTime;

        public long execId; // 一个matcherEvent对应一个execId, maker taker公用一个id
        public SymbolType contractType; //判断 PERPETUAL/DELIVERY
        public PositionMode positionSide;
        public long lastQty; // ev.size
        public long lastPx; // ev.price
        public long cumQty;
        public long cumQuoteQty;
        public long avgPx; // cumQuoteQty / cumQty
        public long fee;
        public int feeAssetId;
        public boolean isMaker;

        private FuturesExecutionReport() {}

        private static FuturesExecutionReport borrow() {
            FuturesExecutionReport obj = POOL.pollFirst();
            return (obj != null) ? obj : new FuturesExecutionReport();
        }

        private void clear() {
            uniId = 0L;
            executionType = null;
            orderStatus = null;
            symbolId = 0;
            orderQtyScale = 0L;
            priceScale = 0L;
            userId = 0L;
            clOrderId = 0L;
            orderId = 0L;
            orderType = null;
            side = null;
            counterpartyId = -1L;
            price = 0L;
            orderQty = 0L;
            createTime = 0L;
            execId = -1L;
            contractType = null;
            positionSide = null;
            lastQty = 0L;
            lastPx = 0L;
            cumQty = 0L;
            cumQuoteQty = 0L;
            avgPx = 0L;
            fee = 0L;
            feeAssetId = 0;
            isMaker = false;
        }

        private void recycle() {
            clear();
            if (POOL.size() < POOL_SIZE) {
                POOL.addFirst(this);
            }
        }

        public static FuturesExecutionReport placeOrder(OrderCommand cmd, long seq, CoreSymbolSpecification spec,
                                                        UserProfile userProfile) {
            boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            FuturesExecutionReport result = FuturesExecutionReport.borrow();
            result.uniId = buildNewExecId(seq);
            result.executionType = ExecType.NEW;
            result.orderStatus = OrderStatus.NEW;
            result.symbolId = cmd.symbol;
            result.orderQtyScale = spec.baseScaleK;
            result.priceScale = spec.quoteScaleK;
            result.userId = cmd.uid;
            result.clOrderId = cmd.userCookie;
            result.orderId = cmd.orderId;
            result.orderType = cmd.orderType;
            result.side = cmd.action;
            result.price = budgetOrder ? 0L : cmd.price;
            result.orderQty = budgetOrder ? cmd.price : cmd.size;
            result.createTime = cmd.timestamp;
            result.contractType = spec.type;
            result.positionSide = userProfile.positionMode;
            result.feeAssetId = spec.quoteCurrency;
            return result;
        }

        public static FuturesExecutionReport rejectOrder(OrderCommand cmd, long seq, CoreSymbolSpecification spec,
                                                         UserProfile userProfile) {
            final boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            FuturesExecutionReport result = FuturesExecutionReport.borrow();
            result.uniId = buildRejectExecId(seq);
            result.executionType = ExecType.REJECT;
            result.orderStatus = OrderStatus.REJECTED;
            result.symbolId = cmd.symbol;
            result.orderQtyScale = spec.baseScaleK;
            result.priceScale = spec.quoteScaleK;
            result.userId = cmd.uid;
            result.clOrderId = cmd.userCookie;
            result.orderId = cmd.orderId;
            result.orderType = cmd.orderType;
            result.side = cmd.action;
            result.price = budgetOrder ? 0L : cmd.price;
            result.orderQty = budgetOrder ? cmd.price : cmd.size;
            result.createTime = cmd.timestamp;
            result.contractType = spec.type;
            result.positionSide = userProfile.positionMode;
            result.feeAssetId = spec.quoteCurrency;
            return result;
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
            FuturesExecutionReport result = FuturesExecutionReport.borrow();
            result.uniId = buildReduceExecId(seq);
            result.executionType = execType;
            result.orderStatus = orderStatus;
            result.symbolId = cmd.symbol;
            result.orderQtyScale = spec.baseScaleK;
            result.priceScale = spec.quoteScaleK;
            result.userId = cmd.uid;
            result.clOrderId = cmd.userCookie;
            result.orderId = cmd.orderId;
            result.orderType = cmd.orderType;
            result.side = cmd.action;
            result.price = budgetOrder ? 0L : cmd.price;
            result.orderQty = budgetOrder ? cmd.price : cmd.size;
            result.createTime = cmd.timestamp;
            result.contractType = spec.type;
            result.positionSide = userProfile.positionMode;
            result.feeAssetId = spec.quoteCurrency;
            return result;
        }

        public static FuturesExecutionReport tradeTaker(OrderCommand cmd, long seq, CoreSymbolSpecification spec,
                                                        UserProfile userProfile, MatcherTradeEvent event, int tradeIndex) {
            final boolean budgetOrder = cmd.orderType == OrderType.FOK_BUDGET || cmd.orderType == OrderType.IOC_BUDGET;
            long sizeToOpen;
            SymbolPositionRecord pos = userProfile.positions.get(userProfile.createPositionsKey(cmd.symbol, cmd.action, cmd.command));
            if (pos == null || pos.direction == PositionDirection.of(cmd.action)) {
                // 仓位为空，或者方向相同 → 全部开仓
                sizeToOpen = event.size;
            } else {
                // 方向相反 → 先平仓，再看是否有剩余开仓
                sizeToOpen = calcSizeToOpen(event.size, cmd.action, pos);
            }
            FuturesExecutionReport result = FuturesExecutionReport.borrow();
            result.uniId = buildTradeExecId(seq, tradeIndex, false);
            result.executionType = ExecType.TRADE;
            result.orderStatus = event.activeOrderCompleted ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
            result.symbolId = cmd.symbol;
            result.orderQtyScale = spec.baseScaleK;
            result.priceScale = spec.quoteScaleK;
            result.userId = cmd.uid;
            result.clOrderId = cmd.userCookie;
            result.orderId = cmd.orderId;
            result.orderType = cmd.orderType;
            result.side = cmd.action;
            result.counterpartyId = event.matchedOrderUid;
            result.price = budgetOrder ? 0L : cmd.price;
            result.orderQty = budgetOrder ? cmd.price : cmd.size;
            result.createTime = cmd.timestamp;
            result.execId = buildTradeId(seq, tradeIndex);
            result.contractType = spec.type;
            result.positionSide = userProfile.positionMode;
            result.lastQty = event.size;
            result.lastPx = event.price;
            result.cumQty = event.filled;
            result.cumQuoteQty = event.filledNotional;
            result.avgPx = event.filled == 0 ? 0L : event.filledNotional / event.filled;
            result.fee = CoreArithmeticUtils.calculateTakerFee(sizeToOpen, event.price, spec);
            result.feeAssetId = spec.quoteCurrency;
            result.isMaker = false;
            return result;
        }

        private static long calcSizeToOpen(long tradeSize, OrderAction action, SymbolPositionRecord pos) {
            // 去掉本次成交对同向 pending 的影响，得到成交前的 pending
            long pbPre = pos.pendingBuySize - (action == OrderAction.BID ? tradeSize : 0);//10
            long psPre = pos.pendingSellSize - (action == OrderAction.ASK ? tradeSize : 0);//20

            // 计算成交前净仓位（多为正，空为负）：
            long netPre = pos.direction.getMultiplier() * pos.openVolume + (pbPre - psPre); // 成交前净仓位（含此前pending）

            long maxClosable = (action == OrderAction.BID)
                    ? Math.max(0, -netPre)   // 只有净空才可被买单平
                    : Math.max(0, netPre);  // 只有净多才可被卖单平

            long closeSize = Math.min(tradeSize, maxClosable);
            long sizeToOpen = tradeSize - closeSize;
            return sizeToOpen;
        }

        public static FuturesExecutionReport tradeMaker(OrderCommand cmd, long seq, CoreSymbolSpecification spec,
                                                        UserProfile makerProfile, MatcherTradeEvent event, int tradeIndex) {
            final boolean budgetOrder = event.matchedOrderType == OrderType.FOK_BUDGET || event.matchedOrderType == OrderType.IOC_BUDGET;
            long sizeToOpen;
            SymbolPositionRecord pos = makerProfile.positions.get(makerProfile.createPositionsKey(spec.symbolId, cmd.action.opposite(), event.matchedOrderCommandType));
            if (pos == null || pos.direction == PositionDirection.of(cmd.action.opposite())) {
                // 仓位为空，或者方向相同 → 全部开仓
                sizeToOpen = event.size;
            } else {
                // 方向相反 → 先平仓，再看是否有剩余开仓
                sizeToOpen = calcSizeToOpen(event.size, cmd.action.opposite(), pos);
            }
            FuturesExecutionReport result = FuturesExecutionReport.borrow();
            result.uniId = buildTradeExecId(seq, tradeIndex, true);
            result.executionType = ExecType.TRADE;
            result.orderStatus = event.matchedOrderCompleted ? OrderStatus.FILLED : OrderStatus.PARTIALLY_FILLED;
            result.symbolId = cmd.symbol;
            result.orderQtyScale = spec.baseScaleK;
            result.priceScale = spec.quoteScaleK;
            result.userId = event.matchedOrderUid;
            result.clOrderId = event.matchedUserCookie;
            result.orderId = event.matchedOrderId;
            result.orderType = event.matchedOrderType;
            result.side = cmd.action.opposite();
            result.counterpartyId = cmd.uid;
            result.price = budgetOrder ? 0L : event.matchedOrderPrice;
            result.orderQty = budgetOrder ? event.matchedOrderPrice : event.matchedOrderSize;
            result.createTime = event.matchedOrderTimestamp;
            result.execId = buildTradeId(seq, tradeIndex);
            result.contractType = spec.type;
            result.positionSide = makerProfile.positionMode;
            result.lastQty = event.size;
            result.lastPx = event.price;
            result.cumQty = event.matchedOrderFilled;
            result.cumQuoteQty = event.matchedOrderFilledNotional;
            result.avgPx = event.matchedOrderFilled == 0 ? 0L : event.matchedOrderFilledNotional / event.matchedOrderFilled;
            result.fee = CoreArithmeticUtils.calculateMakerFee(sizeToOpen, event.price, spec);
            result.feeAssetId = spec.quoteCurrency;
            result.isMaker = true;
            return result;
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
