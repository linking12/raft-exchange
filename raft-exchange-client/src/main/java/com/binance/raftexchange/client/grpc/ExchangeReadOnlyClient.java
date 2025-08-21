package com.binance.raftexchange.client.grpc;

import com.binance.raftexchange.stubs.api.QueryServiceGrpc;
import com.binance.raftexchange.stubs.report.ReportQuery;
import com.binance.raftexchange.stubs.report.ReportResult;
import com.binance.raftexchange.stubs.report.SingleUserReportQuery;
import com.binance.raftexchange.stubs.report.SingleUserReportResult;
import com.binance.raftexchange.stubs.report.StateHashReportQuery;
import com.binance.raftexchange.stubs.report.StateHashReportResult;
import com.binance.raftexchange.stubs.report.SymbolCurrencyReportQuery;
import com.binance.raftexchange.stubs.report.SymbolCurrencyReportResult;
import com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportQuery;
import com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportResult;
import com.binance.raftexchange.stubs.request.ApiOrderBookRequest;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import io.grpc.ManagedChannel;
import io.grpc.NameResolverRegistry;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollSocketChannel;
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;

class ExchangeReadOnlyClient implements AutoCloseable {

    final QueryServiceGrpc.QueryServiceFutureStub searchServiceFutureStub;

    ExchangeReadOnlyClient(EventLoopGroup masterLoopGroup) {
        this.searchServiceFutureStub = initStub(masterLoopGroup);
    }

    public CompletableFuture<CommandResult> searchOrderBook(ApiOrderBookRequest request) {
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        Futures.addCallback(searchServiceFutureStub.searchOrder(request), new FutureCallback<CommandResult>() {
            @Override
            public void onSuccess(@Nullable CommandResult commandResult) {
                future.complete(commandResult);
            }

            @Override
            public void onFailure(Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    public CompletableFuture<SingleUserReportResult> singleUserReport(int transferId, SingleUserReportQuery query) {
        return rawReporter(ReportQuery.newBuilder().setTransferId(transferId).setSingleUserReport(query).build()).thenApply(ReportResult::getSingleUserReport);
    }

    public CompletableFuture<StateHashReportResult> stateHashReport(int transferId, StateHashReportQuery query) {
        return rawReporter(ReportQuery.newBuilder().setTransferId(transferId).setStateHash(query).build()).thenApply(ReportResult::getStateHash);
    }

    public CompletableFuture<TotalCurrencyBalanceReportResult> totalCurrencyBalanceReport(int transferId, TotalCurrencyBalanceReportQuery query) {
        return rawReporter(ReportQuery.newBuilder().setTransferId(transferId).setTotalCurrencyBalance(query).build())
            .thenApply(ReportResult::getTotalCurrencyBalance);
    }

    public CompletableFuture<SymbolCurrencyReportResult> symbolCurrencyReport(int transferId, SymbolCurrencyReportQuery query) {
        return rawReporter(ReportQuery.newBuilder().setTransferId(transferId).setSymbolCurrencyReport(query).build())
            .thenApply(ReportResult::getSymbolCurrencyReport);
    }

    private CompletableFuture<ReportResult> rawReporter(ReportQuery request) {
        CompletableFuture<ReportResult> future = new CompletableFuture<>();
        Futures.addCallback(searchServiceFutureStub.query(request), new FutureCallback<ReportResult>() {
            @Override
            public void onSuccess(@Nullable ReportResult commandResult) {
                future.complete(commandResult);
            }

            @Override
            public void onFailure(Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    private QueryServiceGrpc.QueryServiceFutureStub initStub(EventLoopGroup masterLoopGroup) {
        ManagedChannel channel = NettyChannelBuilder.forTarget(RaftNameResolverProvider.SCHEMA + "://search").eventLoopGroup(masterLoopGroup)
            .defaultLoadBalancingPolicy("round_robin")
            .channelType(masterLoopGroup instanceof EpollEventLoopGroup ? EpollSocketChannel.class : NioSocketChannel.class).usePlaintext().build();
        return QueryServiceGrpc.newFutureStub(channel);
    }

    static {
        NameResolverRegistry.getDefaultRegistry().register(new RaftNameResolverProvider());
    }

    @Override
    public void close() throws Exception {
        ((ManagedChannel)searchServiceFutureStub.getChannel()).shutdown();
    }
}
