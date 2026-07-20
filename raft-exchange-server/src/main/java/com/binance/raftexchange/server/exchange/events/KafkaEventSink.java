package com.binance.raftexchange.server.exchange.events;

/**
 * Decouples the disruptor hot-path from Kafka I/O. The disruptor thread calls enqueue(); actual Kafka sends happen on a
 * separate thread.
 */
@FunctionalInterface
public interface KafkaEventSink {
    void enqueue(IEventsHandlerByKafka.TopicGroup group, long key, byte[] payload);
}
