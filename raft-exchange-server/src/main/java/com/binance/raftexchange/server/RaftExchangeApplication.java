package com.binance.raftexchange.server;

import com.binance.raftexchange.server.raft.ClusterDiscoveryByEureka;
import com.binance.raftexchange.server.raft.JGroupsRaftClusterView;
import com.binance.raftexchange.server.services.UserServiceImpl;
import com.netflix.discovery.EurekaClient;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.cloud.netflix.eureka.EnableEurekaClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@EnableEurekaClient
@SpringBootApplication
public class RaftExchangeApplication implements CommandLineRunner {
    private static final Logger LOG = LoggerFactory.getLogger(RaftExchangeApplication.class);

    public static void main(String[] args) throws Exception {
        SpringApplication.run(RaftExchangeApplication.class, args);
    }

    @Autowired
    private EurekaClient eurekaClient;
    @Value("${spring.application.name}")
    private String jgroupsClusterName;
    @Value("${spring.cloud.client.ip-address}")
    private String localHost;
    @Value("${raft.port:7800}")
    private String jgroupPort;
    @Value("${raft.init-nodes:1}")
    private Integer startupNodes;

    private Server grpcServer;
    private JGroupsRaftClusterView jGroupsRaftClusterView;

    @Override
    public void run(String... arg0) throws Exception {
        ClusterDiscoveryByEureka clusterDiscoveryByEureka = new ClusterDiscoveryByEureka(eurekaClient, jgroupsClusterName, localHost, jgroupPort, startupNodes);
        jGroupsRaftClusterView = new JGroupsRaftClusterView(clusterDiscoveryByEureka, null, jgroupsClusterName);
        jGroupsRaftClusterView.doStart();

        //
    }

    public void startGrpcServer() {
        grpcServer = ServerBuilder.forPort(5001).addService(new UserServiceImpl()).build();
        try {
            grpcServer.start();
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    public void stopGrpcServer() {
        if (grpcServer != null) {
            LOG.info("Shutting down gRPC server...");
            grpcServer.shutdown();
            try {
                if (!grpcServer.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOG.warn("forcing shutdown...");
                    grpcServer.shutdownNow();
                }
            } catch (InterruptedException e) {
                grpcServer.shutdownNow();
                Thread.currentThread().interrupt();
            }
            LOG.info("gRPC server stopped successfully.");
        }
    }
}
