package com.binance.raftexchange.server.exchange;

import com.binance.raftexchange.stubs.report.StateHashReportQuery;
import com.binance.raftexchange.stubs.report.SymbolCurrencyReportQuery;
import com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportQuery;
import com.binance.raftexchange.stubs.request.ApiCommand;
import exchange.core2.core.common.api.ApiNop;
import exchange.core2.core.common.api.ApiResetFee;
import exchange.core2.core.common.api.reports.StateHashReportResult;
import exchange.core2.core.common.api.reports.SymbolCurrencyReportResult;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportResult;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class SyncTradeMiscApiController extends AbstractApiController {

    /**
     * 对各个risk引擎中的各个数据进行哈希计算，用于风控或校验
     */
    public static CompletableFuture<StateHashReportResult> getStateHash(StateHashReportQuery grpcStateHashReportQuery, int transferId) {
        exchange.core2.core.common.api.reports.StateHashReportQuery stateHashReportQuery =
            new exchange.core2.core.common.api.reports.StateHashReportQuery();
        LOG.debug("stateHashReportQuery applied");

        return callExchange(stateHashReportQuery, transferId);
    }

    /**
     * 统计整个撮合系统的资金总额分布，用于风控或对账
     */
    public static CompletableFuture<TotalCurrencyBalanceReportResult> getTotalCurrencyBalance(TotalCurrencyBalanceReportQuery grpcTotalCurrencyBalanceReportQuery, int transferId) {
        exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportQuery totalCurrencyBalanceReportQuery =
            new exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportQuery();
        LOG.debug("totalCurrencyBalanceReportQuery applied");

        return callExchange(totalCurrencyBalanceReportQuery, transferId);
    }

    public static CompletableFuture<SymbolCurrencyReportResult> getSymbolCurrencyReport(SymbolCurrencyReportQuery grpcSymbolCurrencyReportQuery, int transferId) {
        exchange.core2.core.common.api.reports.SymbolCurrencyReportQuery symbolCurrencyReportQuery =
                new exchange.core2.core.common.api.reports.SymbolCurrencyReportQuery();
        LOG.debug("symbolCurrencyReportQuery applied");

        return callExchange(symbolCurrencyReportQuery, transferId);
    }

    public static CompletableFuture<Supplier<byte[]>> resetFee(ApiCommand apiCommand) {
        LOG.debug("apiFeeReset applied");
        return callExchange(convertResetFee(apiCommand));
    }

    public static exchange.core2.core.common.api.ApiResetFee convertResetFee(ApiCommand apiCommand) {
        ApiResetFee apiResetFee = ApiResetFee.builder().build();
        apiResetFee.updateTimestamp(apiCommand.getTimestamp());
        return apiResetFee;
    }

    public static exchange.core2.core.common.api.ApiNop convertNop(ApiCommand apiCommand) {
        ApiNop apiNop = exchange.core2.core.common.api.ApiNop.builder().build();
        apiNop.updateTimestamp(apiCommand.getTimestamp());
        return apiNop;
    }
}
