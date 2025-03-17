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

public abstract class AbstractApiController {
    protected static final Logger LOG = LoggerFactory.getLogger(AbstractApiController.class);

    public static byte[] callExchange(ApiCommand apiCommand) throws Exception {
        if (apiCommand instanceof ApiPersistState) {
            return callExchange((ApiPersistState)apiCommand);
        }
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        return api.submitCommandAsyncFullResponse(apiCommand).thenApply(SerializeHelper::serializeToCommandResult).get();
    }

    public static CompletableFuture<OrderCommand> callExchangeAsync(ApiCommand apiCommand) throws Exception {
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        return api.submitCommandAsyncFullResponse(apiCommand); // submitCommandAsyncFullResponse 跟 非full版本接受的command一致
    }

    public static byte[] callExchange(ApiPersistState cmd) throws Exception {
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        return api.submitPersistCommandAsync(cmd).thenApply(SerializeHelper::serializeToCommandResult).get();
    }

    public static byte[] callExchange(BinaryDataCommand binaryDataCommand) throws Exception {
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        exchange.core2.core.common.cmd.CommandResultCode resultCode = api.submitBinaryDataAsync(binaryDataCommand).get();
        LOG.info("{} called, result: {}", binaryDataCommand.getClass().getSimpleName(), resultCode);
        return SerializeHelper.serializeToCommandResult(resultCode);
    }

    public static <T extends ReportResult> CompletableFuture<T> callExchange(ReportQuery<T> reportQuery, int transferId) throws Exception {
        ExchangeApi api = ExchangeApiInstance.exchangeApi();
        return api.processReport(reportQuery, transferId);
    }
}
