package com.binance.raftexchange.server.grpc;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.server.util.SerializeHelper;
import com.binance.raftexchange.server.util.ThrowableFunction;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import com.binance.raftexchange.stubs.response.ServerNode;

import io.grpc.stub.StreamObserver;

class UniversalStreamObserver implements StreamObserver<ApiCommand> {
    private static final Logger LOGGER = LoggerFactory.getLogger(UniversalStreamObserver.class);
    private static final Object PLACEHOLDER = new Object();
    protected final StreamObserver<CommandResult> responseObserver;
    protected final RaftClusterContainer raftClusterContainer;
    protected final ConcurrentHashMap<ApiCommand, Object> commandOnTheWay = new ConcurrentHashMap<>();
    protected final AtomicBoolean isEnd = new AtomicBoolean(false);
    protected final AtomicBoolean delayEnd = new AtomicBoolean();

    public UniversalStreamObserver(StreamObserver<CommandResult> responseObserver,
        RaftClusterContainer raftClusterContainer) {
        this.responseObserver = responseObserver;
        this.raftClusterContainer = raftClusterContainer;
    }

    @Override
    public void onNext(ApiCommand command) {
        if (!raftClusterContainer.isLeader() && allowFollowExecute(command)) {
            RaftNode raftNode = raftClusterContainer.leaderNode();
            ServerNode leaderNode = Transformer.raftNodeTransform(raftNode);
            responseObserver.onNext(CommandResult.newBuilder().setResultCode(CommandResultCode.NEED_MOVE)
                .setLeaderNode(leaderNode).build());
            return;
        }
        try {
            commandOnTheWay.put(command, PLACEHOLDER);
            byte[] raftLog = SerializeHelper.serializeWithType(command);
            raftClusterContainer.requestConsensus(raftLog)
                .thenApply(ThrowableFunction.warp(b -> SerializeHelper.bytesToEnumProto(b, CommandResultCode.class))) // 把状态机应用结果给下面
                .whenComplete((v, t) -> {
                    if (v != null) {
                        responseObserver.onNext(CommandResult.newBuilder().setResultCode(v).build());
                    }
                    commandOnTheWay.remove(command);
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
        LOGGER.error("error ", throwable);
    }

    @Override
    public void onCompleted() {
        isEnd.set(true);
        if (allowEnd()) {
            responseObserver.onCompleted();
        }
    }

    private boolean allowEnd() {
        return commandOnTheWay.isEmpty() && delayEnd.compareAndSet(false, true);
    }

    protected boolean allowFollowExecute(ApiCommand command) {
        return false;
    }
}
