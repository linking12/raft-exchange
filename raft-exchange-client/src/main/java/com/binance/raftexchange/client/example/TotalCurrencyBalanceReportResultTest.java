package com.binance.raftexchange.client.example;

import com.binance.raftexchange.client.Api.ExchangeClient;
import com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportResult;

import java.util.concurrent.CompletableFuture;

public class TotalCurrencyBalanceReportResultTest {
    public static void main(String[] args) throws Exception {
        try (ExchangeClient exchangeClient = new ExchangeClient("localhost", 5001)) {
            CompletableFuture<TotalCurrencyBalanceReportResult> result = exchangeClient.totalCurrencyBalanceReport(80);
            TotalCurrencyBalanceReportResult commandResult = result.get();
            System.out.println(commandResult);
        }
    }
}
