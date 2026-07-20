package com.binance.raftexchange.server.grpc;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.exchange.ExchangeCalls;
import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.util.SerializeHelper;
import com.binance.raftexchange.stubs.api.QueryServiceGrpc;
import com.binance.raftexchange.stubs.report.ReportQuery;
import com.binance.raftexchange.stubs.report.ReportResult;
import com.binance.raftexchange.stubs.request.ApiOrderBookRequest;
import com.binance.raftexchange.stubs.response.CommandResult;

import exchange.core2.core.common.api.reports.FeeReportQuery;
import exchange.core2.core.common.api.reports.SingleUserReportQuery;
import exchange.core2.core.common.api.reports.StateHashReportQuery;
import exchange.core2.core.common.api.reports.SymbolCurrencyReportQuery;
import exchange.core2.core.common.api.reports.TotalCurrencyBalanceReportQuery;
import io.grpc.stub.StreamObserver;

public class QueryService extends QueryServiceGrpc.QueryServiceImplBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryService.class);

    private final RaftClusterContainer raftClusterContainer;

    public QueryService(RaftClusterContainer raftClusterContainer) {
        this.raftClusterContainer = raftClusterContainer;
    }

    @Override
    public void searchOrder(ApiOrderBookRequest request, StreamObserver<CommandResult> responseObserver) {
        runAsync(responseObserver, raftClusterContainer.readConsistencyBarrier().thenCompose(unused -> {
            ExchangeCalls calls = raftClusterContainer.exchangeCalls();
            return calls
                .callExchangeAsync(exchange.core2.core.common.api.ApiOrderBookRequest.builder()
                    .symbol(request.getSymbol()).size(request.getSize()).build())
                .thenApply(SerializeHelper::orderCommandToResult);
        }));
    }

    @Override
    public void query(ReportQuery request, StreamObserver<ReportResult> responseObserver) {
        runAsync(responseObserver, raftClusterContainer.readConsistencyBarrier().thenCompose(unused -> {
            ExchangeCalls calls = raftClusterContainer.exchangeCalls();
            int transferId = request.getTransferId();
            return switch (request.getTypeCase()) {
                case SINGLE_USER_REPORT -> calls
                    .callExchange(new SingleUserReportQuery(request.getSingleUserReport().getUserId()), transferId)
                    .thenApply(SerializeHelper::serializeToPb);
                case STATE_HASH -> calls.callExchange(new StateHashReportQuery(), transferId)
                    .thenApply(SerializeHelper::serializeToPb);
                case TOTAL_CURRENCY_BALANCE -> calls.callExchange(new TotalCurrencyBalanceReportQuery(), transferId)
                    .thenApply(SerializeHelper::serializeToPb);
                case SYMBOL_CURRENCY_REPORT -> calls.callExchange(new SymbolCurrencyReportQuery(), transferId)
                    .thenApply(SerializeHelper::serializeToPb);
                case FEE_REPORT -> calls.callExchange(new FeeReportQuery(), transferId)
                    .thenApply(SerializeHelper::serializeToPb);
                case INSURANCE_FUND -> calls
                    .callExchange(new exchange.core2.core.common.api.reports.InsuranceFundReportQuery(), transferId)
                    .thenApply(SerializeHelper::serializeToPb);
                case LOAN_PLATFORM -> calls
                    .callExchange(new exchange.core2.core.common.api.reports.LoanPlatformReportQuery(), transferId)
                    .thenApply(SerializeHelper::serializeToPb);
                default -> CompletableFuture
                    .failedFuture(new IllegalArgumentException("Unsupported ReportQuery: " + request.getTypeCase()));
            };
        }));
    }

    private static <T> void runAsync(StreamObserver<T> observer, CompletableFuture<T> future) {
        future.whenComplete((result, t) -> {
            if (t != null) {
                LOGGER.warn("query fail", t);
                observer.onError(t);
            } else {
                observer.onNext(result);
                observer.onCompleted();
            }
        });
    }
}
