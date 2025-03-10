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

import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.stubs.FundsEventPB;
import com.binance.raftexchange.stubs.OrderAction;
import com.binance.raftexchange.stubs.OrderBookPB;
import com.binance.raftexchange.stubs.OrderBookRecordPB;
import com.binance.raftexchange.stubs.ReduceEventPB;
import com.binance.raftexchange.stubs.TradeEventPB;
import com.binance.raftexchange.stubs.TradePB;

import exchange.core2.core.IEventsHandler;

public class KafkaSender implements IEventsHandler {

    static KafkaSender INSTANCE;

    private final KafkaProducer<Long, byte[]> sender;

    private final String topic;

    static final long IGNORE_UID = -1L;

    private AtomicBoolean isLeader = new AtomicBoolean(false);

    public KafkaSender(KafkaProducer<Long, byte[]> sender, String topic) {
        this.sender = sender;
        this.topic = topic;
        RaftChangeEventbus.INSTANCE.registerListener(nodeType -> isLeader.set(nodeType == RaftNode.NodeType.LEADER));
    }

    @Override
    public void tradeEvent(TradeEvent tradeEvent) {
        if (!isLeader.get()) {
            return;
        }
        TradeEventPB.Builder builder =
            TradeEventPB.newBuilder().setSymbol(tradeEvent.getSymbol()).setTotalVolume(tradeEvent.getTotalVolume())
                .setTakerOrderId(tradeEvent.getTakerOrderId()).setTakerUid(tradeEvent.getTakerUid())
                .setTakerAction(OrderAction.forNumber(tradeEvent.getTakerAction().getCode()))
                .setTimestamp(tradeEvent.getTimestamp());
        if (tradeEvent.getTrades() != null) {
            for (Trade trade : tradeEvent.getTrades()) {
                builder = builder.addTrades(TradePB.newBuilder().setMakerOrderId(trade.getMakerOrderId())
                    .setMakerUid(trade.getMakerUid()).setMakerOrderCompleted(trade.isMakerOrderCompleted())
                    .setPrice(trade.getPrice()).setVolume(trade.getVolume()).build());
            }
        }
        byte[] pbData = builder.build().toByteArray();
        sender.send(new ProducerRecord<>(topic, tradeEvent.getTakerUid(), pbData));
    }

    @Override
    public void reduceEvent(ReduceEvent reduceEvent) {
        if (!isLeader.get()) {
            return;
        }
        byte[] pbData = ReduceEventPB.newBuilder().setSymbol(reduceEvent.getSymbol())
            .setReducedVolume(reduceEvent.getReducedVolume()).setOrderCompleted(reduceEvent.isOrderCompleted())
            .setPrice(reduceEvent.getPrice()).setOrderId(reduceEvent.getOrderId()).setUid(reduceEvent.getOrderId())
            .setTimestamp(reduceEvent.getTimestamp()).build().toByteArray();
        sender.send(new ProducerRecord<>(topic, reduceEvent.uid, pbData));
    }

    @Override
    public void orderBook(OrderBook orderBook) {
        if (!isLeader.get()) {
            return;
        }
        OrderBookPB.Builder builder =
            OrderBookPB.newBuilder().setSymbol(orderBook.getSymbol()).setTimestamp(orderBook.getTimestamp());
        if (orderBook.getAsks() != null) {
            for (OrderBookRecord ask : orderBook.getAsks()) {
                builder = builder.addAsks(OrderBookRecordPB.newBuilder().setPrice(ask.getPrice())
                    .setVolume(ask.getVolume()).setOrders(ask.getOrders()).build());
            }
        }

        if (orderBook.getBids() != null) {
            for (OrderBookRecord bid : orderBook.getBids()) {
                builder = builder.addBids(OrderBookRecordPB.newBuilder().setPrice(bid.getPrice())
                    .setVolume(bid.getVolume()).setOrders(bid.getOrders()).build());
            }
        }

        byte[] pbData = builder.build().toByteArray();
        sender.send(new ProducerRecord<>(topic, IGNORE_UID, pbData));
    }

    @Override
    public void fundsEvent(FundsEvent fundsEvent) {
        if (!isLeader.get()) {
            return;
        }

        byte[] pbData = FundsEventPB.newBuilder().setOrderId(fundsEvent.getOrderId()).setUid(fundsEvent.getUid())
            .setCurrency(fundsEvent.getCurrency()).setFree(fundsEvent.getFree()).setLoked(fundsEvent.getLoked())
            .setPositionDelta(fundsEvent.getPositionDelta()).build().toByteArray();
        sender.send(new ProducerRecord<>(topic, fundsEvent.uid, pbData));
    }

    public static KafkaSender getInstance() {
        return INSTANCE;
    }

    public static class CommandPartitioner implements Partitioner {

        private AtomicInteger counter = new AtomicInteger(0); //

        @Override
        public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes,
            Cluster cluster) {
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
