package com.binance.raftexchange.client.example;

import com.binance.raftexchange.client.grpc.ExchangeClient;
import com.binance.raftexchange.stubs.response.CommandResult;

import java.util.concurrent.CompletableFuture;

public class GrpcClientSearchApplication {
    public static void main(String[] args) throws Exception {
        try (ExchangeClient exchangeClient = new ExchangeClient("localhost", 5001)) {
            CompletableFuture<CommandResult> orderBook = exchangeClient.searchOrderBook(1, 1);
            CommandResult commandResult = orderBook.get();
            System.out.println(commandResult);
        }
    }
}
