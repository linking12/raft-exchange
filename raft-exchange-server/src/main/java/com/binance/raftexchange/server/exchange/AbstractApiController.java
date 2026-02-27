package com.binance.raftexchange.server.exchange;

import exchange.core2.core.common.cmd.OrderCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.util.SerializeHelper;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.binary.BinaryDataCommand;
import exchange.core2.core.common.api.reports.ReportQuery;
import exchange.core2.core.common.api.reports.ReportResult;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public abstract class AbstractApiController {
    protected static final Logger LOG = LoggerFactory.getLogger(AbstractApiController.class);

    public static CompletableFuture<Supplier<byte[]>> callExchange(ApiCommand apiCommand) {
        if (apiCommand instanceof ApiPersistState apiPersistState) {
            return callExchange(apiPersistState);
        }
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        // 序列化延迟到外部 gRPC 线程执行，exchange-core 线程只捕获 lambda 引用
        return api.submitCommandAsyncFullResponse(apiCommand)
                .thenApply(cmd -> () -> SerializeHelper.serializeToCommandResult(cmd));
    }

    public static CompletableFuture<OrderCommand> callExchangeAsync(ApiCommand apiCommand) {
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        return api.submitCommandAsyncFullResponse(apiCommand);
    }

    public static CompletableFuture<Supplier<byte[]>> callExchange(ApiPersistState cmd) {
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        return api.submitPersistCommandAsync(cmd)
                .thenApply(result -> () -> SerializeHelper.serializeToCommandResult(result));
    }

    public static CompletableFuture<Supplier<byte[]>> callExchange(BinaryDataCommand binaryDataCommand) {
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        return api.submitBinaryDataAsync(binaryDataCommand)
                .thenApply(cmd -> () -> SerializeHelper.serializeToCommandResult(cmd));
    }

    public static <T extends ReportResult> CompletableFuture<T> callExchange(ReportQuery<T> reportQuery, int transferId) {
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        return api.processReport(reportQuery, transferId);
    }
}
