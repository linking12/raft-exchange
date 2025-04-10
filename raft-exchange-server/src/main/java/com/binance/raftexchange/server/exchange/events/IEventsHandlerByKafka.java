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

import exchange.core2.core.IEventsHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IEventsHandlerByKafka implements IEventsHandler {
    private static final Logger LOG = LoggerFactory.getLogger(IEventsHandlerByKafka.class);
    private static final long IGNORE_UID = -1L;
    private static final ProtoBuilderPool builderPool;
    private static IEventsHandlerByKafka INSTANCE;

    static {
        builderPool = new ProtoBuilderPool();
        builderPool.register(TradeEventPB.Builder.class, TradeEventPB::newBuilder);
        builderPool.register(ReduceEventPB.Builder.class, ReduceEventPB::newBuilder);
        builderPool.register(OrderBookPB.Builder.class, OrderBookPB::newBuilder);
        builderPool.register(FundsEventPB.Builder.class, FundsEventPB::newBuilder);
    }

    private final KafkaProducer<Long, byte[]> sender;
    private final String topic;
    private final AtomicBoolean isLeader = new AtomicBoolean(false);

    public static void init(KafkaProducer<Long, byte[]> sender, String topic) {
        INSTANCE = new IEventsHandlerByKafka(sender, topic);
    }

    private IEventsHandlerByKafka(KafkaProducer<Long, byte[]> sender, String topic) {
        this.sender = sender;
        this.topic = topic;
        RoleChangeEventbus.INSTANCE.registerListener(nodeType -> isLeader.set(nodeType == RaftNode.NodeType.LEADER));
    }

    @Override
    public void tradeEvent(TradeEvent tradeEvent) {
        if (!isLeader.get()) {
            return;
        }
        TradeEventPB.Builder builder = builderPool.get(TradeEventPB.Builder.class).setSymbol(tradeEvent.getSymbol()).setTotalVolume(tradeEvent.getTotalVolume())
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
        sender.send(new ProducerRecord<>(topic, tradeEvent.getTakerUid(), pbData));
    }

    @Override
    public void reduceEvent(ReduceEvent reduceEvent) {
        if (!isLeader.get()) {
            return;
        }
        ReduceEventPB pbObject = builderPool.get(ReduceEventPB.Builder.class).setSymbol(reduceEvent.getSymbol()).setReducedVolume(reduceEvent.getReducedVolume())
            .setOrderCompleted(reduceEvent.isOrderCompleted()).setPrice(reduceEvent.getPrice()).setOrderId(reduceEvent.getOrderId())
            .setUid(reduceEvent.getOrderId()).setTimestamp(reduceEvent.getTimestamp()).build();
        if (LOG.isDebugEnabled()) {
            String formateString = pbObject.toString();
            LOG.debug("reduceEvent: {}", formateString);
        }
        byte[] pbData = pbObject.toByteArray();
        sender.send(new ProducerRecord<>(topic, reduceEvent.uid, pbData));
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
        sender.send(new ProducerRecord<>(topic, IGNORE_UID, pbData));
    }

    @Override
    public void fundsEvent(FundsEvent fundsEvent) {
        if (!isLeader.get()) {
            return;
        }
        FundsEventPB pbObject = builderPool.get(FundsEventPB.Builder.class).setOrderId(fundsEvent.getOrderId()).setUid(fundsEvent.getUid())
            .setCurrency(fundsEvent.getCurrency()).setFree(fundsEvent.getFree()).setLocked(fundsEvent.getLocked())
            .setEventTypeValue(fundsEvent.getEventType().getCode()).setSymbol(fundsEvent.getSymbol())
            .setDirectionValue(fundsEvent.getDirection().getMultiplier() & 0xFF).setPosition(fundsEvent.getPosition())
            .setPositionChanged(fundsEvent.getPositionChanged()).setOpenPriceAvg(fundsEvent.getOpenPriceAvg())
            .setTradePrice(fundsEvent.getTradePrice()).setFee(fundsEvent.getFee()).setPnl(fundsEvent.getPnl()).build();
        if (LOG.isDebugEnabled()) {
            String formateString = pbObject.toString();
            LOG.debug("fundsEvent: {}", formateString);
        }
        byte[] pbData = pbObject.toByteArray();
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
