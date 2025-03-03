package com.binance.raftexchange.client;

import com.binance.raftexchange.client.Api.ApiStream;
import com.binance.raftexchange.client.Api.ExchangeClient;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiNop;
import com.binance.raftexchange.stubs.response.CommandResult;
import io.grpc.stub.StreamObserver;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import lombok.extern.slf4j.Slf4j;

@SpringBootApplication
@Slf4j
public class GrpcClientApplication {

    public static void main(String[] args) throws Exception {
        //让我们简化一下 不启动Spring了
        ExchangeClient exchangeClient = new ExchangeClient("localhost", 5001);
        ApiStream apiStream = exchangeClient.createStream(new StreamObserver<CommandResult>() {
            @Override
            public void onNext(CommandResult commandResult) {
                System.out.println(commandResult);
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
                        .setNop(ApiNop.getDefaultInstance())
                        .build()
        );
    }
}
