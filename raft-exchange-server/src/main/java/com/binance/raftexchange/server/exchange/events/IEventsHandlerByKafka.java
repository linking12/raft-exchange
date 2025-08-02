package com.binance.raftexchange.server.exchange.events;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.PartitionInfo;

import com.binance.raftexchange.server.raft.RoleChangeEventbus;
import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.server.util.ProtoBuilderPool;
import com.binance.raftexchange.stubs.FundsEventPB;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderBookPB;
import com.binance.raftexchange.stubs.OrderBookRecordPB;
import com.binance.raftexchange.stubs.ReduceEventPB;
import com.binance.raftexchange.stubs.TradeEventPB;
import com.binance.raftexchange.stubs.TradePB;
import com.binance.raftexchange.stubs.response.OrderCommand;

import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.common.FundEvent.FundEventType;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.api.ApiMoveOrder;
import exchange.core2.core.common.api.ApiPlaceOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IEventsHandlerByKafka implements ITradeEventsHandler, IFundEventsHandler {
    private static final Logger LOG = LoggerFactory.getLogger(IEventsHandlerByKafka.class);
    private static final long IGNORE_UID = -1L;
    private static final ProtoBuilderPool builderPool;
    private static IEventsHandlerByKafka INSTANCE;

    static {
        builderPool = new ProtoBuilderPool();
        builderPool.register(OrderCommand.Builder.class, OrderCommand::newBuilder);
        builderPool.register(TradeEventPB.Builder.class, TradeEventPB::newBuilder);
        builderPool.register(ReduceEventPB.Builder.class, ReduceEventPB::newBuilder);
        builderPool.register(OrderBookPB.Builder.class, OrderBookPB::newBuilder);
        builderPool.register(FundsEventPB.Builder.class, FundsEventPB::newBuilder);
    }

    private final KafkaProducer<Long, byte[]> sender;
    private final AtomicBoolean isLeader = new AtomicBoolean(false);
    private final String orderEventTopic;
    private final String tradeEventTopic;
    private final String fundEventTopic;
    private final String alertEventTopic;
    private final String marketDataTopic;

    public static void init(KafkaProducer<Long, byte[]> sender, String topicPrefix) {
        INSTANCE = new IEventsHandlerByKafka(sender, topicPrefix);
    }

    private IEventsHandlerByKafka(KafkaProducer<Long, byte[]> sender, String topicPrefix) {
        this.sender = sender;
        this.orderEventTopic = topicPrefix + "-order-event";
        this.tradeEventTopic = topicPrefix + "-trade-event";
        this.fundEventTopic = topicPrefix + "-fund-event";
        this.alertEventTopic = topicPrefix + "-alert-event";
        this.marketDataTopic = topicPrefix + "-market-data";
        RoleChangeEventbus.INSTANCE.registerListener(nodeType -> isLeader.set(nodeType == RaftNode.NodeType.LEADER));
    }

    @Override
    public void commandResult(ApiCommandResult commandResult) {
        if (!isLeader.get()) {
            return;
        }
        OrderCommand.Builder builder = builderPool.get(OrderCommand.Builder.class);
        ApiCommand apiCommand = commandResult.getCommand();
        if (apiCommand instanceof ApiPlaceOrder) {
            builder.setPrice(((ApiPlaceOrder)apiCommand).price).setSize(((ApiPlaceOrder)apiCommand).size).setOrderId(((ApiPlaceOrder)apiCommand).orderId)
                .setActionValue(((ApiPlaceOrder)apiCommand).action.getCode()).setOrderTypeValue(((ApiPlaceOrder)apiCommand).orderType.getCode())
                .setUid(((ApiPlaceOrder)apiCommand).uid).setSymbol(((ApiPlaceOrder)apiCommand).symbol).setUserCookie(((ApiPlaceOrder)apiCommand).userCookie)
                .setLeverage(((ApiPlaceOrder)apiCommand).leverage).setReserveBidPrice(((ApiPlaceOrder)apiCommand).reservePrice);
        } else if (apiCommand instanceof ApiMoveOrder) {
            builder.setOrderId(((ApiMoveOrder)apiCommand).orderId).setPrice(((ApiMoveOrder)apiCommand).newPrice).setUid(((ApiMoveOrder)apiCommand).uid)
                .setSymbol(((ApiMoveOrder)apiCommand).symbol);
        } else {
            // reduce和cancel会从reduceEvent发出来，其他命令暂时不关注
            return;
        }
        builder.setResultCodeValue(Math.abs(commandResult.getResultCode().getCode()));
        OrderCommand command = builder.build();
        sender.send(new ProducerRecord<>(orderEventTopic, command.getUid(), command.toByteArray()));
    }

    @Override
    public void tradeEvent(TradeEvent tradeEvent) {
        if (!isLeader.get()) {
            return;
        }
        TradeEventPB.Builder builder = builderPool.get(TradeEventPB.Builder.class).setSymbol(tradeEvent.getSymbol())
            .setBaseScaleK(tradeEvent.getBaseScaleK()).setQuoteScaleK(tradeEvent.getQuoteScaleK()).setTotalVolume(tradeEvent.getTotalVolume())
            .setTakerOrderId(tradeEvent.getTakerOrderId()).setTakerUid(tradeEvent.getTakerUid())
            .setTakerAction(OrderAction.forNumber(tradeEvent.getTakerAction().getCode())).setTimestamp(tradeEvent.getTimestamp());
        if (tradeEvent.getTrades() != null) {
            for (Trade trade : tradeEvent.getTrades()) {
                builder.addTrades(TradePB.newBuilder().setMakerOrderId(trade.getMakerOrderId()).setMakerUid(trade.getMakerUid())
                    .setMakerOrderCompleted(trade.isMakerOrderCompleted()).setPrice(trade.getPrice()).setVolume(trade.getVolume()).build());
            }
        }
        TradeEventPB pbObject = builder.build();
        if (LOG.isDebugEnabled()) {
            String formateString = pbObject.toString();
            LOG.debug("tradeEvent: {}", formateString);
        }
        byte[] pbData = pbObject.toByteArray();
        sender.send(new ProducerRecord<>(tradeEventTopic, tradeEvent.getTakerUid(), pbData));
    }

    @Override
    public void reduceEvent(ReduceEvent reduceEvent) {
        if (!isLeader.get()) {
            return;
        }
        ReduceEventPB pbObject = builderPool.get(ReduceEventPB.Builder.class).setSymbol(reduceEvent.getSymbol())
            .setBaseScaleK(reduceEvent.getBaseScaleK()).setQuoteScaleK(reduceEvent.getQuoteScaleK())
            .setReducedVolume(reduceEvent.getReducedVolume()).setOrderCompleted(reduceEvent.isOrderCompleted()).setPrice(reduceEvent.getPrice())
            .setOrderId(reduceEvent.getOrderId()).setUid(reduceEvent.getUid()).setTimestamp(reduceEvent.getTimestamp()).build();
        if (LOG.isDebugEnabled()) {
            String formateString = pbObject.toString();
            LOG.debug("reduceEvent: {}", formateString);
        }
        byte[] pbData = pbObject.toByteArray();
        sender.send(new ProducerRecord<>(orderEventTopic, reduceEvent.uid, pbData));
    }

    @Override
    public void orderBook(OrderBook orderBook) {
        if (!isLeader.get()) {
            return;
        }
        OrderBookPB.Builder builder = builderPool.get(OrderBookPB.Builder.class).setSymbol(orderBook.getSymbol()).setTimestamp(orderBook.getTimestamp());
        if (orderBook.getAsks() != null) {
            for (OrderBookRecord ask : orderBook.getAsks()) {
                builder.addAsks(OrderBookRecordPB.newBuilder().setPrice(ask.getPrice()).setVolume(ask.getVolume()).setOrders(ask.getOrders()).build());
            }
        }
        if (orderBook.getBids() != null) {
            for (OrderBookRecord bid : orderBook.getBids()) {
                builder.addBids(OrderBookRecordPB.newBuilder().setPrice(bid.getPrice()).setVolume(bid.getVolume()).setOrders(bid.getOrders()).build());
            }
        }
        OrderBookPB pbObject = builder.build();
        if (LOG.isDebugEnabled()) {
            String formateString = pbObject.toString();
            LOG.debug("orderBook: {}", formateString);
        }
        byte[] pbData = pbObject.toByteArray();
        sender.send(new ProducerRecord<>(marketDataTopic, IGNORE_UID, pbData));
    }

    @Override
    public void fundsEvent(FundsEvent fundsEvent) {
        if (!isLeader.get()) {
            return;
        }
        FundsEventPB pbObject =
            builderPool.get(FundsEventPB.Builder.class).setOrderId(fundsEvent.getOrderId()).setUid(fundsEvent.getUid()).setCurrency(fundsEvent.getCurrency())
                .setCurrencyScakeK(fundsEvent.getCurrencyScaleK()).setFree(fundsEvent.getFree()).setLocked(fundsEvent.getLocked()).setEventTypeValue(fundsEvent.getEventType().getCode())
                .setSymbol(fundsEvent.getSymbol()).setBaseScaleK(fundsEvent.getBaseScaleK()).setQuoteScaleK(fundsEvent.getQuoteScaleK())
                .setDirectionValue(fundsEvent.getDirection().getMultiplier() & 0xFF).setOpenVolume(fundsEvent.getOpenVolume())
                .setOpenInitMarginSum(fundsEvent.getOpenInitMarginSum()).setOpenPriceSum(fundsEvent.getOpenPriceSum()).setProfit(fundsEvent.getProfit())
                .setPendingSellSize(fundsEvent.getPendingSellSize()).setPendingBuySize(fundsEvent.getPendingBuySize())
                .setPendingSellAvgPrice(fundsEvent.getPendingSellAvgPrice()).setPendingBuyAvgPrice(fundsEvent.getPendingBuyAvgPrice())
                .setLeverage(fundsEvent.getLeverage()).setMarginModeValue(fundsEvent.getMarginMode().ordinal()).setExtraMargin(fundsEvent.getExtraMargin())
                .setUnrealizedProfit(fundsEvent.getUnrealizedProfit()).setLiquidationPrice(fundsEvent.getLiquidationPrice())
                .setMarginRatioScaleK(fundsEvent.getMarginRatioScaleK()).setMarkPrice(fundsEvent.getMarkPrice()).setTradeSize(fundsEvent.getTradeSize())
                .setTradePrice(fundsEvent.getTradePrice()).setFee(fundsEvent.getFee()).build();
        if (LOG.isDebugEnabled()) {
            String formateString = pbObject.toString();
            LOG.debug("fundsEvent: {}", formateString);
        }
        byte[] pbData = pbObject.toByteArray();
        String topic = fundsEvent.getEventType().getCode() >= FundEventType.MARGIN_ALERT.getCode() ? alertEventTopic : fundEventTopic;
        sender.send(new ProducerRecord<>(topic, fundsEvent.uid, pbData));
    }

    public static IEventsHandlerByKafka getInstance() {
        if (INSTANCE == null) {
            throw new IllegalStateException("IEventsHandlerByKafka has not been initialized. Call init(sender, topic) first.");
        }
        return INSTANCE;
    }

    public static class CommandPartitioner implements Partitioner {
        private final AtomicInteger counter = new AtomicInteger(0);

        @Override
        public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {
            List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
            int numPartitions = partitions.size();
            Long uid = (Long)key;
            if (uid == IGNORE_UID) {
                return counter.getAndIncrement() % numPartitions;
            }
            return (int)(uid % numPartitions);
        }

        @Override
        public void close() {}

        @Override
        public void configure(Map<String, ?> configs) {}
    }

}
