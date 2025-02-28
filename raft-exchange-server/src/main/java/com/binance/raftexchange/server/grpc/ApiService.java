package com.binance.raftexchange.server.grpc;

import com.binance.raftexchange.server.raft.RaftClusterContainer;

import com.binance.raftexchange.stubs.api.ApiCommandServiceGrpc;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;

import io.grpc.stub.StreamObserver;

public class ApiService extends ApiCommandServiceGrpc.ApiCommandServiceImplBase {

    private final RaftClusterContainer raftClusterContainer;

    public ApiService(RaftClusterContainer raftClusterContainer) {
        this.raftClusterContainer = raftClusterContainer;
    }

    @Override
    public StreamObserver<ApiCommand> execApiCommand(StreamObserver<CommandResult> responseObserver) {
        return new UniversalStreamObserver<>(responseObserver, raftClusterContainer);
    }

}
