package com.binance.raftexchange.server;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;

import com.binance.platform.common.autoconfigure.AlarmAutoConfiguration;
import com.binance.platform.common.autoconfigure.OldMasterCommonConfig;
import com.binance.platform.common.shutdown.GracefulShutdownHook;
import com.binance.raftexchange.server.exchange.ExchangeApiInstance;
import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka;
import com.binance.raftexchange.server.grpc.GrpcServerContainer;
import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftClusterDiscovery;
import com.netflix.discovery.EurekaClient;
import com.vip.vjtools.vjkit.net.NetUtil;

@EnableEurekaClient
@SpringBootApplication(exclude = {AlarmAutoConfiguration.class, OldMasterCommonConfig.class})
public class RaftExchangeApplication implements CommandLineRunner, GracefulShutdownHook {
    private static final Logger LOGGER = LoggerFactory.getLogger(RaftExchangeApplication.class);

    public static void main(String[] args) throws Exception {
        SpringApplication.run(RaftExchangeApplication.class, args);
    }

    @Autowired
    private EurekaClient eurekaClient;

    private RaftClusterContainer raftClusterContainer;

    private GrpcServerContainer grpcServerContainer;

    private final Map<IEventsHandlerByKafka.TopicGroup, KafkaProducer<Long, byte[]>> producers = new EnumMap<>(IEventsHandlerByKafka.TopicGroup.class);
    private final Map<IEventsHandlerByKafka.TopicGroup, String> topics = new EnumMap<>(IEventsHandlerByKafka.TopicGroup.class);

    @Value("${raftexchange.kafka.spot.bootstrap.servers}")
    private String spotServers;
    @Value("${raftexchange.kafka.spot.topic}")
    private String spotTopic;

    @Value("${raftexchange.kafka.perp.bootstrap.servers}")
    private String perpServers;
    @Value("${raftexchange.kafka.perp.topic}")
    private String perpTopic;

    @Value("${raftexchange.kafka.delivery.bootstrap.servers}")
    private String deliveryServers;
    @Value("${raftexchange.kafka.delivery.topic}")
    private String deliveryTopic;

    @Value("${raftexchange.kafka.fund.bootstrap.servers}")
    private String fundServers;
    @Value("${raftexchange.kafka.fund.topic}")
    private String fundTopic;

    @Value("${raftexchange.kafka.other.bootstrap.servers}")
    private String otherServers;
    @Value("${raftexchange.kafka.other.topic}")
    private String otherTopic;

    @Override
    public void run(String... arg0) throws Exception {
        System.setProperty("localhost.default.nic.list", "bond0,eth0,em0,br0,en0,gpd0");
        System.setProperty("local-ip", NetUtil.getLocalHost());
        this.doStart();
    }

    private void doStart() throws Exception {
        startKafkaSender();
        startRaftServer();
        startGrpcServer();
    }

    private void startRaftServer() throws Exception {
        RaftClusterDiscovery raftClusterDiscovery = new RaftClusterDiscovery(eurekaClient);
        RaftClusterContainer raftClusterContainer = new RaftClusterContainer(raftClusterDiscovery);
        raftClusterContainer.doStart();
        this.raftClusterContainer = raftClusterContainer;
    }

    private void startGrpcServer() throws Exception {
        GrpcServerContainer grpcServerContainer = new GrpcServerContainer();
        do {
            grpcServerContainer.setRaftClusterContainer(raftClusterContainer);
            TimeUnit.SECONDS.sleep(5);
        } while (!raftClusterContainer.started());
        grpcServerContainer.doStart();
        this.grpcServerContainer = grpcServerContainer;
    }

    private void startKafkaSender() {
        Properties properties = new Properties();
        properties.put("retries", 3);
        properties.put("retry.backoff.ms", 500);
        properties.put("linger.ms", 100);
        properties.put("batch.size", 512 * 1024);
        properties.put("buffer.memory", 512 * 1024 * 1024);
        properties.put("compression.type", "lz4");
        properties.put("max.request.size", 2 * 1024 * 1024);
        properties.put("request.timeout.ms", 5000);
        properties.put("delivery.timeout.ms", 20 * 1000);
        properties.put("auto.include.jmx.reporter", false);
        properties.put("max.in.flight.requests.per.connection", 5); // 控制并发inflight请求数量
        properties.put("enable.idempotence", false);  // 禁止幂等（重试/leader切换/broker挂掉导致重复，以及局部乱序）
        properties.put("connections.max.idle.ms", 60000); // 保持连接活跃1分钟
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.LongSerializer");
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArraySerializer");
        properties.setProperty(ProducerConfig.ACKS_CONFIG, "all");
        properties.setProperty(ProducerConfig.PARTITIONER_IGNORE_KEYS_CONFIG, "false");
        properties.setProperty(ProducerConfig.PARTITIONER_CLASS_CONFIG, IEventsHandlerByKafka.CommandPartitioner.class.getName());

        createKafkaProducer(IEventsHandlerByKafka.TopicGroup.SPOT, properties, spotServers, spotTopic);
        createKafkaProducer(IEventsHandlerByKafka.TopicGroup.PERP, properties, perpServers, perpTopic);
        createKafkaProducer(IEventsHandlerByKafka.TopicGroup.DELIVERY, properties, deliveryServers, deliveryTopic);
        createKafkaProducer(IEventsHandlerByKafka.TopicGroup.FUND, properties, fundServers, fundTopic);
        createKafkaProducer(IEventsHandlerByKafka.TopicGroup.OTHER, properties, otherServers, otherTopic);

        IEventsHandlerByKafka.init(producers, topics);
    }

    private void createKafkaProducer(IEventsHandlerByKafka.TopicGroup group, Properties base, String bootstrapServers, String topic) {
        Properties props = new Properties();
        props.putAll(base);
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        KafkaProducer<Long, byte[]> producer = new KafkaProducer<>(props);
        producers.put(group, producer);
        topics.put(group, topic);
    }

    @Override
    public void shutdown() {
        if (this.grpcServerContainer != null) {
            try {
                this.grpcServerContainer.doStop();
            } catch (Exception e) {
                LOGGER.error(e.getMessage(), e);
            }
        }
        if (this.raftClusterContainer != null) {
            try {
                this.raftClusterContainer.doStop();
            } catch (Exception e) {
                LOGGER.error(e.getMessage(), e);
            }
        }
        try {
            ExchangeApiInstance.exchangeCore().shutdown();
        } catch (Exception e) {
            LOGGER.error(e.getMessage(), e);
        }
        for (KafkaProducer<Long, byte[]> producer : producers.values()) {
            try {
                producer.close(Duration.ofSeconds(5));
            } catch (Exception e) {
                LOGGER.warn("Error closing Kafka producer", e);
            }
        }
    }

}
