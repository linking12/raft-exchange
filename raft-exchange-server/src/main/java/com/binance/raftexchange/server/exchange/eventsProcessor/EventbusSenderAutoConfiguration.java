package com.binance.raftexchange.server.exchange.eventsProcessor;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Properties;

@Configuration
public class EventbusSenderAutoConfiguration implements InitializingBean {

    @Value("${raftexchange.kafka.boostrap.servers}")
    public String servers;

    @Value("${raftexchange.eventbus.topic}")
    public String topic;

    public KafkaProducer<Long, byte[]> produce() {
        //手动搞下 避免spring kafka引入的复杂度
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.LongSerializer");
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArraySerializer");
        properties.setProperty(ProducerConfig.ACKS_CONFIG, "all");
        properties.setProperty(ProducerConfig.PARTITIONER_IGNORE_KEYS_CONFIG, "false");
        properties.setProperty(ProducerConfig.PARTITIONER_CLASS_CONFIG, KafkaSender.CommandPartitioner.class.getName());
        return new KafkaProducer<Long, byte[]>(properties);
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        KafkaProducer<Long, byte[]> produce = produce();
        KafkaSender.INSTANCE = new KafkaSender(produce, topic);
    }
}
