package com.binance.raftexchange.server;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.Environment;

import com.binance.raftexchange.server.exchange.ExchangeRuntime;
import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka;
import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka.TopicGroup;
import com.binance.raftexchange.server.exchange.events.KafkaEventQueue;
import com.binance.raftexchange.server.exchange.events.KafkaProducerFactory;
import com.binance.raftexchange.server.grpc.GrpcServerContainer;
import com.binance.raftexchange.server.metrics.RaftExchangeMetrics;
import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftClusterDiscovery;
import com.binance.raftexchange.server.util.AppHome;
import com.netflix.discovery.EurekaClient;

@SpringBootApplication
public class RaftExchangeApplication implements CommandLineRunner, ApplicationListener<ContextClosedEvent> {
    static {
        System.setProperty("app.home", AppHome.get().toString());
        System.setProperty("localhost.default.nic.list", "bond0,eth0,em0,br0,en0,gpd0");
        RaftExchangeMetrics.prewarmAll();
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(RaftExchangeApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(RaftExchangeApplication.class, args);
    }

    @Autowired
    private EurekaClient eurekaClient;
    @Autowired
    private Environment env;

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
    @Value("${raftexchange.kafka.enabled:true}")
    private boolean kafkaEnabled;

    private KafkaProducerFactory.Set kafkaSet;
    private KafkaEventQueue kafkaEventQueue;

    private ExchangeRuntime exchangeRuntime;
    private IEventsHandlerByKafka eventsHandler;
    private RaftClusterDiscovery raftClusterDiscovery;
    private RaftClusterContainer raftClusterContainer;
    private GrpcServerContainer grpcServerContainer;

    public RaftClusterContainer getRaftClusterContainer() {
        return raftClusterContainer;
    }

    public RaftClusterDiscovery getRaftClusterDiscovery() {
        return raftClusterDiscovery;
    }

    @Override
    public void run(String... args) throws Exception {
        raftClusterDiscovery = new RaftClusterDiscovery(eurekaClient, env);
        exchangeRuntime = startExchange();
        raftClusterContainer = startRaftClusterContainer();
        grpcServerContainer = startGrpc();
    }

    private ExchangeRuntime startExchange() throws Exception {
        kafkaEventQueue = new KafkaEventQueue(raftClusterDiscovery.getRaftClusterName());
        eventsHandler = new IEventsHandlerByKafka(kafkaEventQueue);
        if (!kafkaEnabled) {
            LOGGER.info("Kafka disabled via raftexchange.kafka.enabled=false; events accumulate in Chronicle only");
        } else {
            Thread kafkaInit = new Thread(() -> {
                try {
                    kafkaSet = KafkaProducerFactory
                        .build(List.of(new KafkaProducerFactory.TopicConfig(TopicGroup.SPOT, spotServers, spotTopic),
                            new KafkaProducerFactory.TopicConfig(TopicGroup.PERP, perpServers, perpTopic),
                            new KafkaProducerFactory.TopicConfig(TopicGroup.DELIVERY, deliveryServers, deliveryTopic),
                            new KafkaProducerFactory.TopicConfig(TopicGroup.FUND, fundServers, fundTopic),
                            new KafkaProducerFactory.TopicConfig(TopicGroup.OTHER, otherServers, otherTopic)));
                    kafkaEventQueue.bindProducers(kafkaSet.producers(), kafkaSet.topics());
                    LOGGER.info("Kafka producers ready, sender resumed");
                } catch (Exception e) {
                    LOGGER.error("Kafka producer init failed; events will accumulate in Chronicle queue", e);
                }
            }, "kafka-init");
            kafkaInit.setDaemon(true);
            kafkaInit.start();
        }
        return new ExchangeRuntime(eventsHandler);
    }

    private RaftClusterContainer startRaftClusterContainer() throws Exception {
        RaftClusterContainer container = RaftClusterContainer.create(raftClusterDiscovery, exchangeRuntime);
        container.doStart();
        container.addRoleChangeListener(eventsHandler::onRoleChange);
        return container;
    }

    private GrpcServerContainer startGrpc() throws Exception {
        GrpcServerContainer grpc = new GrpcServerContainer();
        grpc.setRaftClusterContainer(raftClusterContainer);
        grpc.doStart();
        return grpc;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        stopQuietly("gRPC server", grpcServerContainer == null ? null : grpcServerContainer::doStop);
        stopQuietly("Raft cluster", raftClusterContainer == null ? null : raftClusterContainer::doStop);
        stopQuietly("Exchange core", exchangeRuntime == null ? null : () -> exchangeRuntime.exchangeCore().shutdown());
        if (kafkaEventQueue != null) {
            kafkaEventQueue.close();
        }
        if (kafkaSet != null) {
            kafkaSet.closeAll(Duration.ofSeconds(5));
        }
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static void stopQuietly(String what, CheckedRunnable r) {
        if (r == null)
            return;
        try {
            r.run();
        } catch (Exception e) {
            LOGGER.error("Failed to stop {}", what, e);
        }
    }
}
