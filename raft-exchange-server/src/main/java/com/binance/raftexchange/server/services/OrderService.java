package com.binance.raftexchange.server.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.stubs.command.CommandResult;
import com.binance.raftexchange.stubs.command.CommandResultCode;
import com.binance.raftexchange.stubs.command.OrderCommand;
import com.binance.raftexchange.stubs.command.OrderCommandServiceGrpc;

import io.grpc.stub.StreamObserver;

public class OrderService extends OrderCommandServiceGrpc.OrderCommandServiceImplBase {
	static final Logger LOGGER = LoggerFactory.getLogger(ApiService.class);

	@Override
	public StreamObserver<OrderCommand> execOrderCommand(StreamObserver<CommandResult> responseObserver) {
		return new StreamObserver<OrderCommand>() {
			@Override
			public void onNext(OrderCommand apiCommand) {
				CommandResult result = CommandResult.newBuilder().setResultCode(CommandResultCode.ACCEPTED).build();
				responseObserver.onNext(result);
				responseObserver.onCompleted();
			}

			@Override
			public void onError(Throwable throwable) {
				// 先log下。。。
				LOGGER.error("error ", throwable);
			}

			@Override
			public void onCompleted() {

			}
		};
	}
}
