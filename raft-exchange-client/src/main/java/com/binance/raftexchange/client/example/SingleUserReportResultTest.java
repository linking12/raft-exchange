package com.binance.raftexchange.client.example;

import com.binance.raftexchange.client.grpc.ExchangeClient;
import com.binance.raftexchange.stubs.report.SingleUserReportResult;

import java.util.concurrent.CompletableFuture;

public class SingleUserReportResultTest {
    public static void main(String[] args) throws Exception {
        try (ExchangeClient exchangeClient = new ExchangeClient("localhost", 5001)) {
            CompletableFuture<SingleUserReportResult> result = exchangeClient.singleUserReport(100, 11000001);
            SingleUserReportResult commandResult = result.get();
            System.out.println(commandResult);
        }
    }
}
