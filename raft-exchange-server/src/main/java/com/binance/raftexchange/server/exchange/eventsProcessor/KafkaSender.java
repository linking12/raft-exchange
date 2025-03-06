package com.binance.raftexchange.server.exchange.eventsProcessor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import exchange.core2.core.IEventsHandler;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.PartitionInfo;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class KafkaSender implements IEventsHandler {

    static KafkaSender INSTANCE;

    private final KafkaProducer<Long, byte[]> sender;

    private final String topic;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    static final long IGNORE_UID = -1L;

    public KafkaSender(KafkaProducer<Long, byte[]> sender, String topic) {
        this.sender = sender;
        this.topic = topic;
    }

    @Override
    public void commandResult(ApiCommandResult commandResult) {
        sender.send(new ProducerRecord<>(topic, IGNORE_UID, toJson(commandResult)));
    }

    @Override
    public void tradeEvent(TradeEvent tradeEvent) {
        sender.send(new ProducerRecord<>(topic, tradeEvent.getTakerUid(), toJson(tradeEvent)));
    }

    @Override
    public void rejectEvent(RejectEvent rejectEvent) {
        sender.send(new ProducerRecord<>(topic, rejectEvent.uid, toJson(rejectEvent)));
    }

    @Override
    public void reduceEvent(ReduceEvent reduceEvent) {
        sender.send(new ProducerRecord<>(topic, reduceEvent.uid, toJson(reduceEvent)));
    }

    @Override
    public void orderBook(OrderBook orderBook) {
        sender.send(new ProducerRecord<>(topic, IGNORE_UID, toJson(orderBook)));
    }

    private byte[] toJson(Object o) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(o);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static KafkaSender getInstance() {
        return INSTANCE;
    }

    public static class CommandPartitioner implements Partitioner {

        private AtomicInteger counter = new AtomicInteger(0); //

        @Override
        public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {
            List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
            int numPartitions = partitions.size();
            Long uid = (Long) key;

            //部分command没有uid那么我们进行打散操作
            //均匀进行分配
            if(uid == IGNORE_UID) {
                return counter.getAndIncrement() % numPartitions;
            }

            return (int) (uid % numPartitions);
        }

        @Override
        public void close() {

        }

        @Override
        public void configure(Map<String, ?> configs) {

        }
    }
}
