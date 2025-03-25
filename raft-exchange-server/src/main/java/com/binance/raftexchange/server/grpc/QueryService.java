package com.binance.raftexchange.server.grpc;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.exchange.SyncTradeAccountApiController;
import com.binance.raftexchange.server.exchange.SyncTradeMiscApiController;
import com.binance.raftexchange.server.exchange.SyncTradeOrdersApiController;
import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.util.SerializeHelper;
import com.binance.raftexchange.server.util.ThrowableFunction;
import com.binance.raftexchange.stubs.api.QueryServiceGrpc;
import com.binance.raftexchange.stubs.report.ReportQuery;
import com.binance.raftexchange.stubs.report.ReportResult;
import com.binance.raftexchange.stubs.request.ApiOrderBookRequest;
import com.binance.raftexchange.stubs.response.CommandResult;

import io.grpc.stub.StreamObserver;

public class QueryService extends QueryServiceGrpc.QueryServiceImplBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(QueryService.class);

    private final RaftClusterContainer raftClusterContainer;
    private final ExecutorService offloadWorker;

    public QueryService(RaftClusterContainer raftClusterContainer, ExecutorService offloadWorker) {
        this.raftClusterContainer = raftClusterContainer;
        this.offloadWorker = offloadWorker;
    }

    @Override
    public void searchOrder(ApiOrderBookRequest request, StreamObserver<CommandResult> responseObserver) {
        searchTemplate(request, responseObserver, SyncTradeOrdersApiController::getOrderBookAsync);
    }

    @Override
    public void query(ReportQuery request, StreamObserver<ReportResult> responseObserver) {
        searchTemplate(
                request, responseObserver,
                query -> {
                    int transferId = query.getTransferId();
                    ReportQuery.TypeCase queryTypeCase = query.getTypeCase();
                    switch (queryTypeCase) {
                        case SINGLE_USER_REPORT:
                            return SyncTradeAccountApiController.getUserState(request.getSingleUserReport(), transferId)
                                    .thenApply(SerializeHelper::serializeToPb);
                        case STATE_HASH:
                            return SyncTradeMiscApiController.getStateHash(request.getStateHash(), transferId)
                                    .thenApply(SerializeHelper::serializeToPb);
                        case TOTAL_CURRENCY_BALANCE:
                            return SyncTradeMiscApiController.getTotalCurrencyBalance(request.getTotalCurrencyBalance(), transferId)
                             .thenApply(SerializeHelper::serializeToPb);
                        default: return ThrowableFunction.failureFuture("Unsupported ReportQuery: " + queryTypeCase);
                    }
                }
        );
    }

    private <Request, Response> void searchTemplate(Request request, StreamObserver<Response> responseObserver, ThrowableFunction<Request, CompletableFuture<Response>> asyncOp) {
        raftClusterContainer.readFromQuorum()
                .thenCompose((_index) -> asyncOp.toFunction().apply(request))
                .whenCompleteAsync((result, t) -> {
                    if (t != null) {
                        LOGGER.warn("searchTemplate fail!", t);
                        responseObserver.onError(t);
                    } else {
                        responseObserver.onNext(result);
                        responseObserver.onCompleted();
                    }
                }, offloadWorker);

    }
}
