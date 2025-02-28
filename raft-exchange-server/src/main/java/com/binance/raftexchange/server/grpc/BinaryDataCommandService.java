package com.binance.raftexchange.server.grpc;

import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.stubs.api.BinaryDataCommandServiceGrpc.BinaryDataCommandServiceImplBase;
import com.binance.raftexchange.stubs.request.BinaryDataCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import io.grpc.stub.StreamObserver;

public class BinaryDataCommandService extends BinaryDataCommandServiceImplBase {

    private final RaftClusterContainer raftClusterContainer;

    public BinaryDataCommandService(RaftClusterContainer raftClusterContainer) {
        this.raftClusterContainer = raftClusterContainer;
    }

    @Override
    public StreamObserver<BinaryDataCommand> execBinaryDataCommand(StreamObserver<CommandResult> responseObserver) {
        return new UniversalStreamObserver<>(responseObserver, raftClusterContainer);
    }
}
