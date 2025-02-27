package com.binance.raftexchange.server.grpc;

import com.binance.raftexchange.server.raft.RaftClusterContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.stubs.command.CommandResult;
import com.binance.raftexchange.stubs.command.CommandResultCode;
import com.binance.raftexchange.stubs.command.OrderCommand;
import com.binance.raftexchange.stubs.command.OrderCommandServiceGrpc;

import io.grpc.stub.StreamObserver;

public class OrderService extends OrderCommandServiceGrpc.OrderCommandServiceImplBase {
	static final Logger LOGGER = LoggerFactory.getLogger(ApiService.class);

	private final RaftClusterContainer raftClusterContainer;

    public OrderService(RaftClusterContainer raftClusterContainer) {
        this.raftClusterContainer = raftClusterContainer;
    }

    @Override
	public StreamObserver<OrderCommand> execOrderCommand(StreamObserver<CommandResult> responseObserver) {
		return new UniversalStreamObserver<>(responseObserver, raftClusterContainer);
	}
}
