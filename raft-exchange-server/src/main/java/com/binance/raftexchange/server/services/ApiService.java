package com.binance.raftexchange.server.services;

import com.binance.raftexchange.stubs.api.ApiCommandServiceGrpc;
import com.binance.raftexchange.stubs.api.ApiMessage;
import com.binance.raftexchange.stubs.command.CommandResult;
import com.binance.raftexchange.stubs.command.CommandResultCode;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ApiService extends ApiCommandServiceGrpc.ApiCommandServiceImplBase {
    static final Logger LOGGER = LoggerFactory.getLogger(ApiService.class);

    @Override
    public StreamObserver<ApiMessage> execApiCommand(StreamObserver<CommandResult> responseObserver) {
        return new StreamObserver<ApiMessage>() {
            @Override
            public void onNext(ApiMessage apiMessage) {
                CommandResult result = CommandResult.newBuilder()
                        .setResultCode(CommandResultCode.ACCEPTED)
                        .build();
                responseObserver.onNext(result);
                responseObserver.onCompleted();
            }

            @Override
            public void onError(Throwable throwable) {
                //先log下。。。
                LOGGER.error("error ", throwable);
            }

            @Override
            public void onCompleted() {
            }
        };
    }
}
