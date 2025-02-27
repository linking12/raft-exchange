package com.binance.raftexchange.server.grpc;

import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.SerializeHelper;
import com.binance.raftexchange.server.util.ThrowableFunction;
import com.google.protobuf.GeneratedMessageV3;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

class UniversalStreamObserver<Command extends GeneratedMessageV3, Result> implements StreamObserver<Command> {
	private static final Logger LOGGER = LoggerFactory.getLogger(UniversalStreamObserver.class);
	private static final Object PLACEHOLDER = new Object();
	private final StreamObserver<Result> responseObserver;
	private final RaftClusterContainer raftClusterContainer;

	private final ConcurrentHashMap<Command, Object> commandOnTheWay = new ConcurrentHashMap<>();
	private final AtomicBoolean isEnd = new AtomicBoolean(false);
	private final AtomicBoolean delayEnd = new AtomicBoolean();

	public UniversalStreamObserver(StreamObserver<Result> responseObserver, RaftClusterContainer raftClusterContainer) {
		this.responseObserver = responseObserver;
		this.raftClusterContainer = raftClusterContainer;
	}

	@Override
	public void onNext(Command command) {
		try {
			// todo 是否要保证顺序？
			commandOnTheWay.put(command, PLACEHOLDER);
			byte[] raftLog = SerializeHelper.serializeWithType(command);

			raftClusterContainer.requestConsensus(raftLog) // 等待共识
					.thenApply(ThrowableFunction.warp(b -> SerializeHelper.deserializeWithType(b, 0, b.length))) // 把状态机应用结果给下面
					.thenApply(msg -> ((Result) msg)).thenAccept(responseObserver::onNext).whenComplete((v, t) -> {
						commandOnTheWay.remove(command);
						// 对端已经onCompleted
						// 我方没有在途的command正在apply
						// 且只有一个才可以关闭
						if (isEnd.get() && allowEnd()) {
							responseObserver.onCompleted();
						}
					});

		} catch (Throwable e) {
			responseObserver.onError(e);
		}
	}

	@Override
	public void onError(Throwable throwable) {
		// 对端故障了。。。
		LOGGER.error("error ", throwable);
	}

	@Override
	public void onCompleted() {
		isEnd.set(true);
		// 不存在在途的请求了
		if (allowEnd()) {
			responseObserver.onCompleted();
		}
	}

	private boolean allowEnd() {
		return commandOnTheWay.isEmpty() && delayEnd.compareAndSet(false, true);
	}
}
