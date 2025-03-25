package com.binance.raftexchange.server.grpc;

import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.stubs.api.ApiCommandServiceGrpc;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.stub.StreamObserver;

public class ApiService extends ApiCommandServiceGrpc.ApiCommandServiceImplBase {
    private final RaftClusterContainer raftClusterContainer;
    private final EventLoopGroup offloadWorkers;

    public ApiService(RaftClusterContainer raftClusterContainer, EventLoopGroup offloadWorkers) {
        this.raftClusterContainer = raftClusterContainer;
        this.offloadWorkers = offloadWorkers;
    }

    @Override
    public StreamObserver<ApiCommand> execApiCommand(StreamObserver<CommandResult> responseObserver) {
        return null;
    }

    public ServerServiceDefinition transform() {
        return ServerInterceptors.intercept(ServerInterceptors.useInputStreamMessages(this.bindService()), new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                ServerCall.Listener<ReqT> reqTListener = next.startCall(call, headers);
                call.sendHeaders(new Metadata());
                return new UniversalInterceptor<ReqT, RespT>(raftClusterContainer, offloadWorkers.next(), reqTListener, call);
            }
        });
    }
}
