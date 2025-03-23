package com.binance.raftexchange.client.tests;

import com.binance.raftexchange.client.Api.ApiStream;
import com.binance.raftexchange.client.Api.ExchangeClient;
import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

public class GeneralTest {
    @Test
    public void test() {
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
                                            .setUid(10000001)
                                            .build()
                            )
                            .build()
            );

            CommandResult commandResult = future.get();
            System.out.println(commandResult);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
