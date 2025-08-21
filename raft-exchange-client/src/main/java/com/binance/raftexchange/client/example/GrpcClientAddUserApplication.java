package com.binance.raftexchange.client.example;

import com.binance.raftexchange.client.grpc.ApiStream;
import com.binance.raftexchange.client.grpc.ExchangeClient;
import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import io.grpc.stub.StreamObserver;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

@Slf4j
public class GrpcClientAddUserApplication {

    public static void main(String[] args) throws Exception {
        //让我们简化一下 不启动Spring了
        try (ExchangeClient exchangeClient = new ExchangeClient("localhost", 5001)) {
            CompletableFuture<CommandResult> future = new CompletableFuture<>();
            ApiStream apiStream = exchangeClient.createStream(new StreamObserver<CommandResult>() {
                @Override
                public void onNext(CommandResult commandResult) {
                    future.complete(commandResult);
                }

                @Override
                public void onError(Throwable throwable) {
                    throwable.printStackTrace();
                }

                @Override
                public void onCompleted() {
                    System.out.println("end");
                }
            });
            apiStream.onNext(
                    ApiCommand.newBuilder()
                            .setAddUser(
                                    ApiAddUser.newBuilder()
                                            .setUid(200102014)
                                            .build()
                            )
                            .build()
            );

            CommandResult commandResult = future.get();
            System.out.println(commandResult);
        }
    }
}
