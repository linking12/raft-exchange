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
import io.grpc.stub.StreamObserver;

public class ApiService extends ApiCommandServiceGrpc.ApiCommandServiceImplBase {
    private final RaftClusterContainer raftClusterContainer;

    public ApiService(RaftClusterContainer raftClusterContainer) {
        this.raftClusterContainer = raftClusterContainer;
    }

    @Override
    public StreamObserver<ApiCommand> execApiCommand(StreamObserver<CommandResult> responseObserver) {
        // 这里无关紧要。。已经被ServerInterceptors掏空了
        return null;
    }

    public ServerServiceDefinition transform() {
        return ServerInterceptors.intercept(
            // 让我们简单把第一个参数转为inputstream
            ServerInterceptors.useInputStreamMessages(this.bindService()), new ServerInterceptor() {
                @Override
                public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call,
                    Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                    ServerCall.Listener<ReqT> reqTListener = next.startCall(call, headers);
                    call.request(Integer.MAX_VALUE);
                    call.sendHeaders(new Metadata());
                    return new UniversalInterceptor<ReqT, RespT>(raftClusterContainer, reqTListener, call);
                }
            });
    }
}
