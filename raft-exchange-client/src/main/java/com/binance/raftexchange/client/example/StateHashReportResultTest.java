package com.binance.raftexchange.client.example;

import com.binance.raftexchange.client.Api.ExchangeClient;

import java.util.concurrent.CompletableFuture;

public class StateHashReportResultTest {
    public static void main(String[] args) throws Exception {
        try (ExchangeClient exchangeClient = new ExchangeClient("localhost", 5001)) {
            CompletableFuture<com.binance.raftexchange.stubs.report.StateHashReportResult> result = exchangeClient.stateHashReport(20);
            com.binance.raftexchange.stubs.report.StateHashReportResult commandResult = result.get();
            System.out.println(commandResult);
        }
    }
}
