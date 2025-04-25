package com.binance.raftexchange.server;

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

    @Value("${raftexchange.kafka.boostrap.servers}")
    public String kafkaServers;

    @Value("${raftexchange.kafka.topic}")
    public String topic;

    @Override
    public void run(String... arg0) throws Exception {
        System.setProperty("localhost.default.nic.list", "bond0,eth0,em0,br0,en0,gpd0");
        System.setProperty("local-ip", NetUtil.getLocalHost());
        startKafkaSender();
        startRaftServer();
        startGrpcServer();

    }

    public void startRaftServer() throws Exception {
        RaftClusterDiscovery raftClusterDiscovery = new RaftClusterDiscovery(eurekaClient);
        RaftClusterContainer raftClusterContainer = new RaftClusterContainer(raftClusterDiscovery);
        raftClusterContainer.doStart();
        this.raftClusterContainer = raftClusterContainer;
    }

    public void startGrpcServer() throws Exception {
        GrpcServerContainer grpcServerContainer = new GrpcServerContainer();
        do {
            grpcServerContainer.setRaftClusterContainer(raftClusterContainer);
            TimeUnit.SECONDS.sleep(5);
        } while (!raftClusterContainer.started());
        grpcServerContainer.doStart();
    }

    public void startKafkaSender() {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaServers);
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.LongSerializer");
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.ByteArraySerializer");
        properties.setProperty(ProducerConfig.ACKS_CONFIG, "all");
        properties.setProperty(ProducerConfig.PARTITIONER_IGNORE_KEYS_CONFIG, "false");
        properties.setProperty(ProducerConfig.PARTITIONER_CLASS_CONFIG, IEventsHandlerByKafka.CommandPartitioner.class.getName());
        KafkaProducer<Long, byte[]> producer = new KafkaProducer<Long, byte[]>(properties);
        IEventsHandlerByKafka.init(producer, topic);
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
    }

}
