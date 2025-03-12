package com.binance.raftexchange.server.grpc;

import com.binance.raftexchange.server.exchange.SyncTradeOrdersApiController;
import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.util.ThrowableFunction;
import com.binance.raftexchange.stubs.api.SearchServiceGrpc;
import com.binance.raftexchange.stubs.request.ApiOrderBookRequest;
import com.binance.raftexchange.stubs.response.CommandResult;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SearchService extends SearchServiceGrpc.SearchServiceImplBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(SearchService.class);

    private final RaftClusterContainer raftClusterContainer;

    public SearchService(RaftClusterContainer raftClusterContainer) {
        this.raftClusterContainer = raftClusterContainer;
    }

    @Override
    public void searchOrder(ApiOrderBookRequest request, StreamObserver<CommandResult> responseObserver) {
        raftClusterContainer.readFromQuorum()
                .thenCompose(ThrowableFunction.warp((_readIndex) -> SyncTradeOrdersApiController.getOrderBookAsync(request)))
                .whenComplete((result, t) -> {
                    if (t != null) {
                        LOGGER.warn("search order fail!", t);
                        responseObserver.onError(t);
                    } else {
                        responseObserver.onNext(result);
                        responseObserver.onCompleted();
                    }
                });
    }
}
