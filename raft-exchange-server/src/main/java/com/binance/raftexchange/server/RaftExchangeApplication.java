package com.binance.raftexchange.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.EnableEurekaClient;

import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftClusterDiscovery;
import com.binance.raftexchange.server.services.GrpcServerContainer;
import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.discovery.EurekaClient;

@EnableEurekaClient
@SpringBootApplication
public class RaftExchangeApplication implements CommandLineRunner {
	private static final Logger LOGGER = LoggerFactory.getLogger(RaftExchangeApplication.class);

	public static void main(String[] args) throws Exception {
		SpringApplication.run(RaftExchangeApplication.class, args);
	}

	@Autowired
	private EurekaClient eurekaClient;

	@Autowired
	private ApplicationInfoManager applicationInfoManager;

	private RaftClusterContainer raftClusterContainer;

	@Override
	public void run(String... arg0) throws Exception {
		// startRaft();
		startGrpcServer();
	}

	public void startRaft() throws Exception {
		RaftClusterDiscovery raftClusterDiscovery = new RaftClusterDiscovery(applicationInfoManager, eurekaClient);
		RaftClusterContainer raftClusterContainer = new RaftClusterContainer(raftClusterDiscovery);
		raftClusterContainer.doStart();

	}

	public void startGrpcServer() throws Exception {
		GrpcServerContainer grpcServerContainer = new GrpcServerContainer(raftClusterContainer);
		grpcServerContainer.doStart();
	}

}
