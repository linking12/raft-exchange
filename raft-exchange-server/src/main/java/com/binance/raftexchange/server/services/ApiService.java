package com.binance.raftexchange.server.services;

import com.binance.raftexchange.stubs.api.ApiCommand;
import com.binance.raftexchange.stubs.api.ApiCommandServiceGrpc;
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
    public StreamObserver<ApiCommand> execApiCommand(StreamObserver<CommandResult> responseObserver) {
        return new StreamObserver<ApiCommand>() {
            @Override
            public void onNext(ApiCommand apiCommand) {
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
