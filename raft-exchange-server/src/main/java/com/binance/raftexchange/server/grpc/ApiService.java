package com.binance.raftexchange.server.grpc;

import com.binance.raftexchange.server.raft.RaftClusterContainer;

import com.binance.raftexchange.stubs.api.ApiCommandServiceGrpc;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;

import com.binance.raftexchange.stubs.response.CommandResultCode;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.stub.StreamObserver;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class ApiService extends ApiCommandServiceGrpc.ApiCommandServiceImplBase {

    private final RaftClusterContainer raftClusterContainer;

    public ApiService(RaftClusterContainer raftClusterContainer) {
        this.raftClusterContainer = raftClusterContainer;
    }

    @Override
    public StreamObserver<ApiCommand> execApiCommand(StreamObserver<CommandResult> responseObserver) {
        return new UniversalStreamObserver(responseObserver, raftClusterContainer);
    }

    public ServerServiceDefinition transform() {
        return ServerInterceptors.intercept(
                ServerInterceptors.useInputStreamMessages(this.bindService()),
                new ServerInterceptor() {
                    @Override
                    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                        ServerCall.Listener<ReqT> reqTListener = next.startCall(call, headers);
                        return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(reqTListener) {
                            @Override
                            public void onMessage(ReqT message) {
                                InputStream stream = (InputStream) message;
                                stream.markSupported();
                                try {
                                    ApiCommand apiCommand = ApiCommand.parseFrom(stream);
                                    System.out.println(apiCommand);
                                    call.sendHeaders(new Metadata());
                                    call.sendMessage((RespT) new ByteArrayInputStream(CommandResult.newBuilder().setResultCode(CommandResultCode.ACCEPTED).build().toByteArray()));
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        };
                    }
                }
        );

    }
}
