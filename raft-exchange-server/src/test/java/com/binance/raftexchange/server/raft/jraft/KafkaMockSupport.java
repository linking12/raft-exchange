package com.binance.raftexchange.server.raft.jraft;

import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka;
import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka.TopicGroup;
import com.binance.raftexchange.server.exchange.events.KafkaEventSink;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Test helpers for constructing IEventsHandlerByKafka without a real Kafka cluster. */
final class KafkaMockSupport {

    private KafkaMockSupport() {}

    /**
     * Returns a handler whose sink captures every enqueued event into {@code captureSink}. Callers can inspect captured
     * records per TopicGroup after the fact.
     */
    static IEventsHandlerByKafka
        buildCapturingHandler(Map<TopicGroup, List<ProducerRecord<Long, byte[]>>> captureSink) {
        Map<TopicGroup, String> topics = new EnumMap<>(TopicGroup.class);
        for (TopicGroup g : TopicGroup.values()) {
            topics.put(g, "test-" + g.name().toLowerCase());
        }
        KafkaEventSink sink =
            (group, key, payload) -> captureSink.get(group).add(new ProducerRecord<>(topics.get(group), key, payload));
        return new IEventsHandlerByKafka(sink);
    }

    /** Returns a handler whose sink silently discards all events. */
    static IEventsHandlerByKafka buildDiscardingHandler() {
        return new IEventsHandlerByKafka((group, key, payload) -> {
        });
    }
}
