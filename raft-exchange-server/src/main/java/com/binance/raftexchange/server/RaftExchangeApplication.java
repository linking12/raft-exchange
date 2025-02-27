package com.binance.raftexchange.server;

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
import com.netflix.appinfo.ApplicationInfoManager;
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

	@Autowired
	private ApplicationInfoManager applicationInfoManager;

	private RaftClusterContainer raftClusterContainer;

	private GrpcServerContainer grpcServerContainer;

	@Override
	public void run(String... arg0) throws Exception {
		startRaft();
		// startGrpcServer();
	}

	public void startRaft() throws Exception {
		RaftClusterDiscovery raftClusterDiscovery = new RaftClusterDiscovery(applicationInfoManager, eurekaClient);
		RaftClusterContainer raftClusterContainer = new RaftClusterContainer(raftClusterDiscovery);
		raftClusterContainer.doStart();
		this.raftClusterContainer = raftClusterContainer;
	}

	public void startGrpcServer() throws Exception {
		GrpcServerContainer grpcServerContainer = new GrpcServerContainer();
		grpcServerContainer.doStart();
		this.grpcServerContainer = grpcServerContainer;
	}

	@Override
	public void shutdown() {
		if (this.raftClusterContainer != null) {
			try {
				this.raftClusterContainer.doStop();
			} catch (Exception e) {
				LOGGER.error(e.getMessage(), e);
			}
		}
		if (this.grpcServerContainer != null) {
			try {
				this.grpcServerContainer.doStop();
			} catch (Exception e) {
				LOGGER.error(e.getMessage(), e);
			}
		}
	}

}
