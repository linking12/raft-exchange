/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package exchange.core2.core.orderbook;

import exchange.core2.collections.objpool.ObjectsPool;
import exchange.core2.core.common.*;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.config.LoggingConfiguration;
import exchange.core2.core.utils.SerializationUtils;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

@Slf4j
public final class OrderBookNaiveImpl implements IOrderBook {
    private static final long[] EMPTY_LONGS = new long[0];

    private final NavigableMap<Long, OrdersBucketNaive> askBuckets;
    private final NavigableMap<Long, OrdersBucketNaive> bidBuckets;

    private final CoreSymbolSpecification symbolSpec;

    private final LongObjectHashMap<Order> idMap = new LongObjectHashMap<>();

    private final OrderBookEventsHelper eventsHelper;

    private final boolean logDebug;

    public OrderBookNaiveImpl(final CoreSymbolSpecification symbolSpec,
                              final ObjectsPool pool,
                              final OrderBookEventsHelper eventsHelper,
                              final LoggingConfiguration loggingCfg) {

        this.symbolSpec = symbolSpec;
        this.askBuckets = new TreeMap<>();
        this.bidBuckets = new TreeMap<>(Collections.reverseOrder());
        this.eventsHelper = eventsHelper;
        this.logDebug = loggingCfg.getLoggingLevels().contains(LoggingConfiguration.LoggingLevel.LOGGING_MATCHING_DEBUG);
    }

    public OrderBookNaiveImpl(final CoreSymbolSpecification symbolSpec,
                              final LoggingConfiguration loggingCfg) {

        this.symbolSpec = symbolSpec;
        this.askBuckets = new TreeMap<>();
        this.bidBuckets = new TreeMap<>(Collections.reverseOrder());
        this.eventsHelper = OrderBookEventsHelper.NON_POOLED_EVENTS_HELPER;
        this.logDebug = loggingCfg.getLoggingLevels().contains(LoggingConfiguration.LoggingLevel.LOGGING_MATCHING_DEBUG);
    }

    public OrderBookNaiveImpl(final BytesIn bytes, final LoggingConfiguration loggingCfg) {
        this.symbolSpec = new CoreSymbolSpecification(bytes);
        this.askBuckets = SerializationUtils.readLongMap(bytes, TreeMap::new, OrdersBucketNaive::new);
        this.bidBuckets = SerializationUtils.readLongMap(bytes, () -> new TreeMap<>(Collections.reverseOrder()), OrdersBucketNaive::new);

        this.eventsHelper = OrderBookEventsHelper.NON_POOLED_EVENTS_HELPER;
        // reconstruct ordersId-> Order cache
        // TODO check resulting performance
        askBuckets.values().forEach(bucket -> bucket.forEachOrder(order -> idMap.put(order.orderId, order)));
        bidBuckets.values().forEach(bucket -> bucket.forEachOrder(order -> idMap.put(order.orderId, order)));

        this.logDebug = loggingCfg.getLoggingLevels().contains(LoggingConfiguration.LoggingLevel.LOGGING_MATCHING_DEBUG);
        //validateInternalState();
    }

    @Override
    public void newOrder(final OrderCommand cmd) {

        switch (cmd.orderType) {
            case GTC:
                newOrderPlaceGtc(cmd);
                break;
            case IOC:
                newOrderMatchIoc(cmd);
                break;
            case FOK_BUDGET:
                newOrderMatchFokBudget(cmd);
                break;
            case IOC_BUDGET:
                newOrderMatchIocBudget(cmd);
                break;
            // TODO FOK support
            default:
                log.warn("Unsupported order type: {}", cmd);
                eventsHelper.attachRejectEvent(cmd, cmd.size, symbolSpec);
        }
    }

    /**
     * IOC_BUDGET：用预算上限买单（cmd.price 是 product-scale 总预算）。
     * 走到 size 上限或预算耗尽就停，剩余部分 cancel。
     * <p>语义上仅对 BID 有意义（用预算买）；ASK IOC_BUDGET 语义模糊（最低收入约束无法部分成交），暂不支持。
     */
    private void newOrderMatchIocBudget(final OrderCommand cmd) {
        if (cmd.action != OrderAction.BID) {
            log.warn("IOC_BUDGET only supports BID action: {}", cmd);
            eventsHelper.attachRejectEvent(cmd, cmd.size, symbolSpec);
            return;
        }
        // 用 askBuckets 全集（无价格上限），budget 由 tryMatchInstantlyWithBudget 内约束
        final SortedMap<Long, OrdersBucketNaive> matchingBuckets = askBuckets;
        final long[] matchResult = tryMatchInstantlyWithBudget(cmd, matchingBuckets, cmd, cmd.price);
        final long filledSize = matchResult.length == 0 ? cmd.getFilled() : matchResult[0];
        final long rejectedSize = cmd.size - filledSize;
        if (rejectedSize != 0) {
            eventsHelper.attachRejectEvent(cmd, rejectedSize, symbolSpec);
        }
    }

    private void newOrderPlaceGtc(final OrderCommand cmd) {

        final OrderAction action = cmd.action;
        final long price = cmd.price;
        final long size = cmd.size;

        // check if order is marketable (if there are opposite matching orders)
        final long[] matchResult = tryMatchInstantly(cmd, subtreeForMatching(action, price), cmd);
        final long filledSize = matchResult.length == 0 ? cmd.getFilled() : matchResult[0];
        final long filledNotional = matchResult.length == 0 ? cmd.getFilledNotional() : matchResult[1];
        if (filledSize == size) {
            // order was matched completely - nothing to place - can just return
            return;
        }

        long newOrderId = cmd.orderId;
        if (idMap.containsKey(newOrderId)) {
            // duplicate order id - can match, but can not place
            eventsHelper.attachRejectEvent(cmd, cmd.size - filledSize, symbolSpec);
            log.warn("duplicate order id: {}", cmd);
            return;
        }

        // normally placing regular GTC limit order
        final Order orderRecord = new Order(
                newOrderId,
                price,
                size,
                filledSize,
                filledNotional,
                cmd.reserveBidPrice,
                action,
                cmd.orderType,
                cmd.command,
                cmd.uid,
                cmd.timestamp,
                cmd.userCookie);

        getBucketsByAction(action)
                .computeIfAbsent(price, p -> new OrdersBucketNaive(symbolSpec, p))
                .put(orderRecord);

        idMap.put(newOrderId, orderRecord);
    }

    private void newOrderMatchIoc(final OrderCommand cmd) {

        final long[] matchResult = tryMatchInstantly(cmd, subtreeForMatching(cmd.action, cmd.price), cmd);
        final long filledSize = matchResult.length == 0 ? cmd.getFilled() : matchResult[0];

        final long rejectedSize = cmd.size - filledSize;

        if (rejectedSize != 0) {
            // was not matched completely - send reject for not-completed IoC order
            eventsHelper.attachRejectEvent(cmd, rejectedSize, symbolSpec);
        }
    }

    private void newOrderMatchFokBudget(final OrderCommand cmd) {

        final long size = cmd.size;

        final SortedMap<Long, OrdersBucketNaive> subtreeForMatching =
                cmd.action == OrderAction.ASK ? bidBuckets : askBuckets;

        final Optional<Long> budget = checkBudgetToFill(size, subtreeForMatching);

        if (logDebug) log.debug("Budget calc: {} requested: {}", budget, cmd.price);

        if (budget.isPresent() && isBudgetLimitSatisfied(cmd.action, budget.get(), cmd.price)) {
            tryMatchInstantly(cmd, subtreeForMatching, cmd);
        } else {
            eventsHelper.attachRejectEvent(cmd, size, symbolSpec);
        }
    }

    private boolean isBudgetLimitSatisfied(final OrderAction orderAction, final long calculated, final long limit) {
        return calculated == limit || (orderAction == OrderAction.BID ^ calculated > limit);
    }


    private Optional<Long> checkBudgetToFill(
            long size,
            final SortedMap<Long, OrdersBucketNaive> matchingBuckets) {

        long budget = 0;

        for (final OrdersBucketNaive bucket : matchingBuckets.values()) {

            final long availableSize = bucket.getTotalVolume();
            final long price = bucket.getPrice();

            if (size > availableSize) {
                size -= availableSize;
                budget += Math.multiplyExact(availableSize, price);
                if (logDebug) log.debug("add    {} * {} -> {}", price, availableSize, budget);
            } else {
                final long result = budget + Math.multiplyExact(size, price);
                if (logDebug) log.debug("return {} * {} -> {}", price, size, result);
                return Optional.of(result);
            }
        }
        if (logDebug) log.debug("not enough liquidity to fill size={}", size);
        return Optional.empty();
    }

    private SortedMap<Long, OrdersBucketNaive> subtreeForMatching(final OrderAction action, final long price) {
        return (action == OrderAction.ASK ? bidBuckets : askBuckets)
                .headMap(price, true);
    }

    /**
     * Match the order instantly to specified sorted buckets map
     * Fully matching orders are removed from orderId index
     * Should any trades occur - they sent to tradesConsumer
     *
     * @param activeOrder     - GTC or IOC order to match
     * @param matchingBuckets - sorted buckets map
     * @param triggerCmd      - triggered command (taker)
     * @return [filledSize, filledNotional]
     */
    private long[] tryMatchInstantly(
            final IOrder activeOrder,
            final SortedMap<Long, OrdersBucketNaive> matchingBuckets,
            final OrderCommand triggerCmd) {

//        log.info("matchInstantly: {} {}", order, matchingBuckets);

        if (matchingBuckets.size() == 0) {
            return EMPTY_LONGS;
        }

        final long orderSize = activeOrder.getSize();

        long filled = activeOrder.getFilled();
        long filledNotional = activeOrder.getFilledNotional();
        MatcherTradeEvent eventsTail = null;
        List<Long> emptyBuckets = new ArrayList<>();
        for (final OrdersBucketNaive bucket : matchingBuckets.values()) {

//            log.debug("Matching bucket: {} ...", bucket);
//            log.debug("... with order: {}", activeOrder);

            final long sizeLeft = orderSize - filled;

            final OrdersBucketNaive.MatcherResult bucketMatchings = bucket.match(sizeLeft, activeOrder, eventsHelper);

            bucketMatchings.ordersToRemove.forEach(idMap::remove);

            filled += bucketMatchings.volume;
            filledNotional += bucketMatchings.notional;

            // attach chain received from bucket matcher
            if (eventsTail == null) {
                triggerCmd.matcherEvent = bucketMatchings.eventsChainHead;
            } else {
                eventsTail.nextEvent = bucketMatchings.eventsChainHead;
            }
            eventsTail = bucketMatchings.eventsChainTail;

//            log.debug("Matching orders: {}", matchingOrders);
//            log.debug("order.filled: {}", activeOrder.filled);

            long price = bucket.getPrice();

            // remove empty buckets
            if (bucket.getTotalVolume() == 0) {
                emptyBuckets.add(price);
            }

            if (filled == orderSize) {
                // enough matched
                break;
            }
        }

        // remove empty buckets (is it necessary?)
        // TODO can remove through iterator ??
        emptyBuckets.forEach(matchingBuckets::remove);

//        log.debug("emptyBuckets: {}", emptyBuckets);
//        log.debug("matchingRecords: {}", matchingRecords);

        return new long[]{filled, filledNotional};
    }

    /**
     * IOC_BUDGET 专用撮合：结构与 {@link #tryMatchInstantly(IOrder, SortedMap, OrderCommand)} 同构，
     * 区别是每个 bucket 按 {@code remainingBudget / bucketPrice} 限制可购量，预算耗尽即停；
     * 未成交剩余 size 由调用方走 reject 事件。
     * <p>刻意复制一份而不是与主 {@code tryMatchInstantly} 合并，避免污染限价/IOC/FOK_BUDGET 主路径。
     * <p>仅在 IOC_BUDGET BID 路径下调用（{@code newOrderMatchIocBudget} 已守卫 ASK）。
     */
    private long[] tryMatchInstantlyWithBudget(
            final IOrder activeOrder,
            final SortedMap<Long, OrdersBucketNaive> matchingBuckets,
            final OrderCommand triggerCmd,
            long remainingBudget) {

        if (matchingBuckets.size() == 0) {
            return EMPTY_LONGS;
        }

        final long orderSize = activeOrder.getSize();

        long filled = activeOrder.getFilled();
        long filledNotional = activeOrder.getFilledNotional();
        MatcherTradeEvent eventsTail = null;
        List<Long> emptyBuckets = new ArrayList<>();
        for (final OrdersBucketNaive bucket : matchingBuckets.values()) {

            final long bucketPrice = bucket.getPrice();
            // budget 约束：本档可购量上限 = remainingBudget / bucketPrice（向下取整）。
            // bucketPrice 为 0 时跳过约束以防除零。
            final long affordableAtBucket = (bucketPrice == 0)
                    ? Long.MAX_VALUE
                    : remainingBudget / bucketPrice;
            final long sizeLeft = Math.min(orderSize - filled, affordableAtBucket);
            if (sizeLeft == 0) {
                // budget 不足以再吃一个最小成交单位
                break;
            }

            final OrdersBucketNaive.MatcherResult bucketMatchings = bucket.match(sizeLeft, activeOrder, eventsHelper);

            bucketMatchings.ordersToRemove.forEach(idMap::remove);

            filled += bucketMatchings.volume;
            filledNotional += bucketMatchings.notional;
            remainingBudget -= bucketMatchings.notional;

            if (eventsTail == null) {
                triggerCmd.matcherEvent = bucketMatchings.eventsChainHead;
            } else {
                eventsTail.nextEvent = bucketMatchings.eventsChainHead;
            }
            eventsTail = bucketMatchings.eventsChainTail;

            if (bucket.getTotalVolume() == 0) {
                emptyBuckets.add(bucketPrice);
            }

            if (filled == orderSize) {
                break;
            }
        }

        emptyBuckets.forEach(matchingBuckets::remove);

        return new long[]{filled, filledNotional};
    }

    /**
     * Remove an order.<p>
     *
     * @param cmd cancel command (orderId - order to remove)
     * @return true if order removed, false if not found (can be removed/matched earlier)
     */
    public CommandResultCode cancelOrder(OrderCommand cmd) {
        final long orderId = cmd.orderId;

        final Order order = idMap.get(orderId);
        if (order == null || order.uid != cmd.uid) {
            // order already matched and removed from order book previously
            return CommandResultCode.MATCHING_UNKNOWN_ORDER_ID;
        }

        // now can remove it
        idMap.remove(orderId);

        final NavigableMap<Long, OrdersBucketNaive> buckets = getBucketsByAction(order.action);
        final long price = order.price;
        final OrdersBucketNaive ordersBucket = buckets.get(price);
        if (ordersBucket == null) {
            // not possible state
            throw new IllegalStateException("Can not find bucket for order price=" + price + " for order " + order);
        }

        // remove order and whole bucket if its empty
        ordersBucket.remove(orderId, cmd.uid);
        if (ordersBucket.getTotalVolume() == 0) {
            buckets.remove(price);
        }

        // send reduce event
        cmd.matcherEvent = eventsHelper.sendReduceEvent(order, order.getSize() - order.getFilled(), true, symbolSpec);

        // fill action fields (for events handling)
        cmd.action = order.getAction();

        return CommandResultCode.SUCCESS;
    }

    @Override
    public CommandResultCode reduceOrder(OrderCommand cmd) {
        final long orderId = cmd.orderId;
        final long requestedReduceSize = cmd.size;

        if (requestedReduceSize <= 0) {
            return CommandResultCode.MATCHING_REDUCE_FAILED_WRONG_SIZE;
        }

        final Order order = idMap.get(orderId);
        if (order == null || order.uid != cmd.uid) {
            // already matched, moved or cancelled
            return CommandResultCode.MATCHING_UNKNOWN_ORDER_ID;
        }

        final long remainingSize = order.size - order.filled;
        final long reduceBy = Math.min(remainingSize, requestedReduceSize);

        final NavigableMap<Long, OrdersBucketNaive> buckets = getBucketsByAction(order.action);
        final OrdersBucketNaive ordersBucket = buckets.get(order.price);
        if (ordersBucket == null) {
            // not possible state
            throw new IllegalStateException("Can not find bucket for order price=" + order.price + " for order " + order);
        }

        final boolean canRemove = (reduceBy == remainingSize);

        if (canRemove) {

            // now can remove order
            idMap.remove(orderId);

            // canRemove order and whole bucket if it is empty
            ordersBucket.remove(orderId, cmd.uid);
            if (ordersBucket.getTotalVolume() == 0) {
                buckets.remove(order.price);
            }

        } else {

            order.size -= reduceBy;
            ordersBucket.reduceSize(reduceBy);
        }

        // send reduce event
        cmd.matcherEvent = eventsHelper.sendReduceEvent(order, reduceBy, canRemove, symbolSpec);

        // fill action fields (for events handling)
        cmd.action = order.getAction();

        return CommandResultCode.SUCCESS;
    }

    @Override
    public CommandResultCode moveOrder(OrderCommand cmd) {

        final long orderId = cmd.orderId;
        final long newPrice = cmd.price;

        final Order order = idMap.get(orderId);
        if (order == null || order.uid != cmd.uid) {
            // already matched, moved or cancelled
            return CommandResultCode.MATCHING_UNKNOWN_ORDER_ID;
        }

        final long price = order.price;
        final NavigableMap<Long, OrdersBucketNaive> buckets = getBucketsByAction(order.action);
        final OrdersBucketNaive bucket = buckets.get(price);

        // fill action fields (for events handling)
        cmd.action = order.getAction();

        // reserved price risk check for exchange bids
        if (symbolSpec.type == SymbolType.CURRENCY_EXCHANGE_PAIR && order.action == OrderAction.BID && cmd.price > order.reserveBidPrice) {
            return CommandResultCode.MATCHING_MOVE_FAILED_PRICE_OVER_RISK_LIMIT;
        }

        // take order out of the original bucket and clean bucket if its empty
        bucket.remove(orderId, cmd.uid);

        if (bucket.getTotalVolume() == 0) {
            buckets.remove(price);
        }

        order.price = newPrice;

        // try match with new price
        final SortedMap<Long, OrdersBucketNaive> matchingArea = subtreeForMatching(order.action, newPrice);
        final long[] matchResult = tryMatchInstantly(order, matchingArea, cmd);
        final long filled = matchResult.length == 0 ? order.getFilled() : matchResult[0];
        if (filled == order.size) {
            // order was fully matched (100% marketable) - removing from order book
            idMap.remove(orderId);
            return CommandResultCode.SUCCESS;
        }
        order.filled = filled;

        // if not filled completely - put it into corresponding bucket
        final OrdersBucketNaive anotherBucket = buckets.computeIfAbsent(newPrice, p -> {
            OrdersBucketNaive b = new OrdersBucketNaive(symbolSpec, p);
            return b;
        });
        anotherBucket.put(order);

        return CommandResultCode.SUCCESS;
    }

    /**
     * Get bucket by order action
     *
     * @param action - action
     * @return bucket - navigable map
     */
    private NavigableMap<Long, OrdersBucketNaive> getBucketsByAction(OrderAction action) {
        return action == OrderAction.ASK ? askBuckets : bidBuckets;
    }


    /**
     * Get order from internal map
     *
     * @param orderId - order Id
     * @return order from map
     */
    @Override
    public IOrder getOrderById(long orderId) {
        return idMap.get(orderId);
    }

    @Override
    public void fillAsks(final int size, L2MarketData data) {
        if (size == 0) {
            data.askSize = 0;
            return;
        }

        int i = 0;
        for (OrdersBucketNaive bucket : askBuckets.values()) {
            data.askPrices[i] = bucket.getPrice();
            data.askVolumes[i] = bucket.getTotalVolume();
            data.askOrders[i] = bucket.getNumOrders();
            if (++i == size) {
                break;
            }
        }
        data.askSize = i;
    }

    @Override
    public void fillBids(final int size, L2MarketData data) {
        if (size == 0) {
            data.bidSize = 0;
            return;
        }

        int i = 0;
        for (OrdersBucketNaive bucket : bidBuckets.values()) {
            data.bidPrices[i] = bucket.getPrice();
            data.bidVolumes[i] = bucket.getTotalVolume();
            data.bidOrders[i] = bucket.getNumOrders();
            if (++i == size) {
                break;
            }
        }
        data.bidSize = i;
    }

    @Override
    public int getTotalAskBuckets(final int limit) {
        return Math.min(limit, askBuckets.size());
    }

    @Override
    public int getTotalBidBuckets(final int limit) {
        return Math.min(limit, bidBuckets.size());
    }

    @Override
    public void validateInternalState() {
        askBuckets.values().forEach(OrdersBucketNaive::validate);
        bidBuckets.values().forEach(OrdersBucketNaive::validate);
    }

    @Override
    public OrderBookImplType getImplementationType() {
        return OrderBookImplType.NAIVE;
    }

    @Override
    public List<Order> findUserOrders(final long uid) {
        List<Order> list = new ArrayList<>();
        Consumer<OrdersBucketNaive> bucketConsumer = bucket -> bucket.forEachOrder(order -> {
            if (order.uid == uid) {
                list.add(order);
            }
        });
        askBuckets.values().forEach(bucketConsumer);
        bidBuckets.values().forEach(bucketConsumer);
        return list;
    }

    @Override
    public CoreSymbolSpecification getSymbolSpec() {
        return symbolSpec;
    }

    @Override
    public Stream<IOrder> askOrdersStream(final boolean sorted) {
        return askBuckets.values().stream().flatMap(bucket -> bucket.getAllOrders().stream());
    }

    @Override
    public Stream<IOrder> bidOrdersStream(final boolean sorted) {
        return bidBuckets.values().stream().flatMap(bucket -> bucket.getAllOrders().stream());
    }

    // for testing only
    @Override
    public int getOrdersNum(OrderAction action) {
        final NavigableMap<Long, OrdersBucketNaive> buckets = action == OrderAction.ASK ? askBuckets : bidBuckets;
        return buckets.values().stream().mapToInt(OrdersBucketNaive::getNumOrders).sum();
//        int askOrders = askBuckets.values().stream().mapToInt(OrdersBucketNaive::getNumOrders).sum();
//        int bidOrders = bidBuckets.values().stream().mapToInt(OrdersBucketNaive::getNumOrders).sum();
        //log.debug("idMap:{} askOrders:{} bidOrders:{}", idMap.size(), askOrders, bidOrders);
//        int knownOrders = idMap.size();
//        assert knownOrders == askOrders + bidOrders : "inconsistent known orders";
    }

    @Override
    public long getTotalOrdersVolume(OrderAction action) {
        final NavigableMap<Long, OrdersBucketNaive> buckets = action == OrderAction.ASK ? askBuckets : bidBuckets;
        return buckets.values().stream().mapToLong(OrdersBucketNaive::getTotalVolume).sum();
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeByte(getImplementationType().getCode());
        symbolSpec.writeMarshallable(bytes);
        SerializationUtils.marshallLongMap(askBuckets, bytes);
        SerializationUtils.marshallLongMap(bidBuckets, bytes);
    }
}
