package com.binance.raftexchange.server.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.raft.RaftClusterContainer;

import io.grpc.Server;
import io.grpc.ServerBuilder;

public class GrpcServerContainer {

	static final Logger LOGGER = LoggerFactory.getLogger(GrpcServerContainer.class);

	private RaftClusterContainer raftClusterContainer;

	private Server server;

	public void setRaftClusterContainer(RaftClusterContainer raftClusterContainer) {
		this.raftClusterContainer = raftClusterContainer;
	}

	public void doStart() throws Exception {
		this.server = ServerBuilder.forPort(5001).addService(new ApiService()).addService(new OrderService()).build();
		Server localServer = server.start();
		LOGGER.info("grpc server start {}", localServer.getPort());
	}

	public void doStop() throws Exception {
		server.shutdown();
	}
}
