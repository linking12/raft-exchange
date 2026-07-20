package com.binance.raftexchange.server.exchange.events;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 把 5 个 TopicGroup 的 KafkaProducer 装配集中起来，让 RaftExchangeApplication 只关心配置注入。 调用方拿到的 {@link Set} 持有 producers +
 * topics，shutdown 时统一关闭。
 */
public final class KafkaProducerFactory {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaProducerFactory.class);

    /** 每个 TopicGroup 对应的 Kafka 集群地址 + 主题。 */
    public record TopicConfig(IEventsHandlerByKafka.TopicGroup group, String bootstrapServers, String topic) {}

    /** 装配好的 producers + topics，封装统一的 closeAll。 */
    public record Set(Map<IEventsHandlerByKafka.TopicGroup, KafkaProducer<Long, byte[]>> producers,
        Map<IEventsHandlerByKafka.TopicGroup, String> topics) {

        public void closeAll(Duration timeout) {
            for (KafkaProducer<Long, byte[]> producer : producers.values()) {
                try {
                    producer.close(timeout);
                } catch (Exception e) {
                    LOG.warn("Error closing Kafka producer", e);
                }
            }
        }
    }

    private KafkaProducerFactory() {}

    public static Set build(List<TopicConfig> configs) {
        Properties base = baseProperties();
        Map<IEventsHandlerByKafka.TopicGroup, KafkaProducer<Long, byte[]>> producers =
            new EnumMap<>(IEventsHandlerByKafka.TopicGroup.class);
        Map<IEventsHandlerByKafka.TopicGroup, String> topics = new EnumMap<>(IEventsHandlerByKafka.TopicGroup.class);
        for (TopicConfig c : configs) {
            Properties props = new Properties();
            props.putAll(base);
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, c.bootstrapServers());
            producers.put(c.group(), new KafkaProducer<>(props));
            topics.put(c.group(), c.topic());
        }
        return new Set(producers, topics);
    }

    private static Properties baseProperties() {
        Properties p = new Properties();
        p.put("retries", 3);
        p.put("retry.backoff.ms", 500);
        p.put("linger.ms", 100);
        p.put("batch.size", 512 * 1024);
        p.put("buffer.memory", 512 * 1024 * 1024);
        p.put("compression.type", "lz4");
        p.put("max.request.size", 2 * 1024 * 1024);
        p.put("request.timeout.ms", 5000);
        p.put("delivery.timeout.ms", 20 * 1000);
        // max.block.ms: send() 在 metadata 不可达或 buffer 满时的最长阻塞时间。
        // 对齐 request.timeout.ms，broker 挂掉时快速失败，避免阻塞 disruptor 线程 60s（默认值）。
        p.put("max.block.ms", 5000);
        p.put("auto.include.jmx.reporter", false);
        p.put("max.in.flight.requests.per.connection", 5);
        // idempotence=false：重试 / leader 切换 / broker 挂掉时允许重复 + 局部乱序
        p.put("enable.idempotence", false);
        p.put("connections.max.idle.ms", 60000);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.LongSerializer");
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.ByteArraySerializer");
        p.setProperty(ProducerConfig.ACKS_CONFIG, "all");
        p.setProperty(ProducerConfig.PARTITIONER_IGNORE_KEYS_CONFIG, "false");
        p.setProperty(ProducerConfig.PARTITIONER_CLASS_CONFIG,
            IEventsHandlerByKafka.CommandPartitioner.class.getName());
        return p;
    }
}
