package com.binance.raftexchange.server;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;

import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftClusterDiscovery;
import com.binance.raftexchange.server.services.UserServiceImpl;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.discovery.EurekaClient;

import io.grpc.Server;
import io.grpc.ServerBuilder;

@EnableEurekaClient
@SpringBootApplication
public class RaftExchangeApplication implements CommandLineRunner {
	private static final Logger LOG = LoggerFactory.getLogger(RaftExchangeApplication.class);

	public static void main(String[] args) throws Exception {
		SpringApplication.run(RaftExchangeApplication.class, args);
	}

	@Autowired
	private EurekaClient eurekaClient;

	@Autowired
	private ApplicationInfoManager applicationInfoManager;

	private Server grpcServer;

	@Override
	public void run(String... arg0) throws Exception {
		RaftClusterDiscovery raftClusterDiscovery = new RaftClusterDiscovery(applicationInfoManager, eurekaClient);
		RaftClusterContainer raftClusterContainer = new RaftClusterContainer(raftClusterDiscovery);
		raftClusterContainer.doStart();

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
