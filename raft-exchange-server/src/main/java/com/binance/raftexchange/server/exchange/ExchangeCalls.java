package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.server.raft.CommandRegistry;
import com.binance.raftexchange.server.util.SerializeHelper;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.api.ApiNop;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.ApiRecoverState;
import exchange.core2.core.common.api.binary.BinaryDataCommand;
import exchange.core2.core.common.api.reports.ReportQuery;
import exchange.core2.core.common.api.reports.ReportResult;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 整个 server 跟 ExchangeApi 唯一的耦合点。其他组件持本类、不持 ExchangeApi。 */
public final class ExchangeCalls {

    private final ExchangeApi api;
    private final CommandRegistry registry = new CommandRegistry();

    public ExchangeCalls(ExchangeApi api) {
        this.api = api;
    }

    public CompletableFuture<Supplier<byte[]>> apply(com.binance.raftexchange.stubs.request.ApiCommand cmd) {
        com.binance.raftexchange.stubs.request.ApiCommand.CommandCase cc = cmd.getCommandCase();
        if (cc == com.binance.raftexchange.stubs.request.ApiCommand.CommandCase.BINARY_DATA) {
            return applyBinaryData(cmd.getBinaryData().getData());
        }
        if (cc == com.binance.raftexchange.stubs.request.ApiCommand.CommandCase.NOP) {
            return callExchange(ApiNop.builder().build());
        }
        ApiCommand engineCmd = registry.toExchangeCommand(cmd);
        if (engineCmd == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported ApiCommand: " + cc));
        }
        return callExchange(engineCmd);
    }

    /** 未注册 case 返 null。 */
    public ApiCommand toExchangeCommand(com.binance.raftexchange.stubs.request.ApiCommand cmd) {
        return registry.toExchangeCommand(cmd);
    }

    public boolean canBatch(com.binance.raftexchange.stubs.request.ApiCommand cmd) {
        return registry.canBatch(cmd);
    }

    private CompletableFuture<Supplier<byte[]>>
        applyBinaryData(com.binance.raftexchange.stubs.request.BinaryDataCommand bdc) {
        return switch (bdc.getCommandCase()) {
            case ADD_ACCOUNTS -> callExchange(ApiCommandConverters.convertBatchAddAccounts(bdc.getAddAccounts()));
            case ADD_SYMBOLS -> callExchange(ApiCommandConverters.convertBatchAddSymbols(bdc.getAddSymbols()));
            case ADD_CURRENCIES -> callExchange(ApiCommandConverters.convertBatchAddCurrencies(bdc.getAddCurrencies()));
            case UPDATE_SYMBOL_LOAN_CONFIG ->
                callExchange(ApiCommandConverters.convertUpdateSymbolLoanConfig(bdc.getUpdateSymbolLoanConfig()));
            case UPDATE_LOAN_NUMERAIRE_CONFIG -> callExchange(
                ApiCommandConverters.convertUpdateLoanNumeraireConfig(bdc.getUpdateLoanNumeraireConfig()));
            default -> CompletableFuture
                .failedFuture(new IllegalArgumentException("Unsupported BinaryDataCommand: " + bdc.getCommandCase()));
        };
    }

    public CompletableFuture<Supplier<byte[]>> callExchange(ApiCommand apiCommand) {
        return api.submitCommandAsyncFullResponse(apiCommand).thenApply(ExchangeCalls::lazy);
    }

    public CompletableFuture<OrderCommand> callExchangeAsync(ApiCommand apiCommand) {
        return api.submitCommandAsyncFullResponse(apiCommand);
    }

    public CompletableFuture<Supplier<byte[]>> callExchange(ApiPersistState cmd) {
        return api.submitPersistCommandAsync(cmd).thenApply(ExchangeCalls::lazy);
    }

    public CompletableFuture<Supplier<byte[]>> callExchange(BinaryDataCommand binaryDataCommand) {
        return api.submitBinaryDataAsync(binaryDataCommand).thenApply(ExchangeCalls::lazy);
    }

    /** 序列化延到 supplier.get() 触发线程跑（一般是 gRPC 线程），不占 disruptor。 */
    private static Supplier<byte[]> lazy(OrderCommand cmd) {
        return () -> SerializeHelper.serializeToCommandResult(cmd);
    }

    private static Supplier<byte[]> lazy(CommandResultCode code) {
        return () -> SerializeHelper.serializeToCommandResult(code);
    }

    public <T extends ReportResult> CompletableFuture<T> callExchange(ReportQuery<T> reportQuery, int transferId) {
        return api.processReport(reportQuery, transferId);
    }

    public void submitBatchAsync(ApiCommand[] commands, Consumer<OrderCommand>[] callbacks, int size) {
        api.submitBatchAsync(commands, callbacks, size);
    }

    public void submitCommand(ApiCommand cmd) {
        api.submitCommand(cmd);
    }

    public CompletableFuture<CommandResultCode> submitRecoverCommandAsync(ApiRecoverState cmd) {
        return api.submitRecoverCommandAsync(cmd);
    }
}
