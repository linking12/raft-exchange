package com.binance.raftexchange.server.exchange.events;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka.TopicGroup.DELIVERY;
import static com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka.TopicGroup.FUND;
import static com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka.TopicGroup.OTHER;
import static com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka.TopicGroup.PERP;
import static com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka.TopicGroup.SPOT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaProducerFactoryTest {

    @Test
    void buildsOneProducerPerTopicConfig() {
        KafkaProducerFactory.Set set = KafkaProducerFactory
            .build(List.of(new KafkaProducerFactory.TopicConfig(SPOT, "localhost:9092", "spot-topic"),
                new KafkaProducerFactory.TopicConfig(PERP, "localhost:9092", "perp-topic"),
                new KafkaProducerFactory.TopicConfig(DELIVERY, "localhost:9092", "delivery-topic"),
                new KafkaProducerFactory.TopicConfig(FUND, "localhost:9092", "fund-topic"),
                new KafkaProducerFactory.TopicConfig(OTHER, "localhost:9092", "other-topic")));

        assertEquals(5, set.producers().size(), "每个 TopicGroup 一个 producer");
        assertEquals(5, set.topics().size());
        assertEquals("spot-topic", set.topics().get(SPOT));
        assertEquals("perp-topic", set.topics().get(PERP));
        assertEquals("delivery-topic", set.topics().get(DELIVERY));
        assertEquals("fund-topic", set.topics().get(FUND));
        assertEquals("other-topic", set.topics().get(OTHER));

        for (KafkaProducer<Long, byte[]> p : set.producers().values()) {
            assertNotNull(p);
            p.close(Duration.ofSeconds(1));
        }
    }

    @Test
    void differentTopicGroupsGetDistinctProducers() {
        KafkaProducerFactory.Set set =
            KafkaProducerFactory.build(List.of(new KafkaProducerFactory.TopicConfig(SPOT, "localhost:9092", "t1"),
                new KafkaProducerFactory.TopicConfig(PERP, "localhost:9092", "t2")));

        assertNotSame(set.producers().get(SPOT), set.producers().get(PERP),
            "每个 TopicGroup 必须独立 producer 实例（不同的 bootstrap 池）");

        set.closeAll(Duration.ofSeconds(1));
    }

    @Test
    void closeAllSwallowsExceptionsFromAnyProducer() {
        KafkaProducerFactory.Set set =
            KafkaProducerFactory.build(List.of(new KafkaProducerFactory.TopicConfig(SPOT, "localhost:9092", "t")));
        // 第一次 close 应该正常
        set.closeAll(Duration.ofSeconds(1));
        // 第二次也不应抛（KafkaProducer.close 幂等，但即使抛 closeAll 也应 swallow）
        set.closeAll(Duration.ofSeconds(1));
    }

    @Test
    void emptyConfigList_producesEmptySet() {
        KafkaProducerFactory.Set set = KafkaProducerFactory.build(List.of());
        assertTrue(set.producers().isEmpty());
        assertTrue(set.topics().isEmpty());
        set.closeAll(Duration.ofSeconds(1));
    }

    @Test
    void duplicateTopicGroup_lastConfigWins() {
        KafkaProducerFactory.Set set =
            KafkaProducerFactory.build(List.of(new KafkaProducerFactory.TopicConfig(SPOT, "localhost:9092", "first"),
                new KafkaProducerFactory.TopicConfig(SPOT, "localhost:9093", "second")));

        // 后写的覆盖前面：EnumMap.put 行为
        assertEquals("second", set.topics().get(SPOT));
        set.closeAll(Duration.ofSeconds(1));
    }

    @Test
    void nullConfigList_throwsNpe() {
        assertThrows(NullPointerException.class, () -> KafkaProducerFactory.build(null));
    }
}
