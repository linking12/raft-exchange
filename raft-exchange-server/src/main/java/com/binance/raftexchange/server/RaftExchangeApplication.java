package com.binance.raftexchange.server;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;

import com.binance.platform.common.shutdown.GracefulShutdownHook;
import com.binance.raftexchange.server.grpc.GrpcServerContainer;
import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftClusterDiscovery;
import com.netflix.discovery.EurekaClient;

@EnableEurekaClient
@SpringBootApplication
public class RaftExchangeApplication implements CommandLineRunner, GracefulShutdownHook {
    private static final Logger LOGGER = LoggerFactory.getLogger(RaftExchangeApplication.class);

    public static void main(String[] args) throws Exception {
        SpringApplication.run(RaftExchangeApplication.class, args);
    }

    @Autowired
    private EurekaClient eurekaClient;

    private RaftClusterContainer raftClusterContainer;

    private GrpcServerContainer grpcServerContainer;

    @Override
    public void run(String... arg0) throws Exception {
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
        GrpcServerContainer grpcServerContainer = null;
        do {
            grpcServerContainer = new GrpcServerContainer(raftClusterContainer);
            TimeUnit.SECONDS.sleep(5);
        } while (raftClusterContainer.startSuccessed());
        if (grpcServerContainer != null) {
            grpcServerContainer.doStart();
        }
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
