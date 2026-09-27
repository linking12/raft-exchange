package com.binance.raftexchange.client.grpc;

import com.binance.raftexchange.stubs.api.QueryServiceGrpc;
import com.binance.raftexchange.stubs.report.InsuranceFundReportQuery;
import com.binance.raftexchange.stubs.report.InsuranceFundReportResult;
import com.binance.raftexchange.stubs.report.LoanPlatformReportQuery;
import com.binance.raftexchange.stubs.report.LoanPlatformReportResult;
import com.binance.raftexchange.stubs.report.ReportQuery;
import com.binance.raftexchange.stubs.report.ReportResult;
import com.binance.raftexchange.stubs.report.SingleUserReportQuery;
import com.binance.raftexchange.stubs.report.SingleUserReportResult;
import com.binance.raftexchange.stubs.report.StateHashReportQuery;
import com.binance.raftexchange.stubs.report.StateHashReportResult;
import com.binance.raftexchange.stubs.report.SymbolCurrencyReportQuery;
import com.binance.raftexchange.stubs.report.SymbolCurrencyReportResult;
import com.binance.raftexchange.stubs.report.FeeReportQuery;
import com.binance.raftexchange.stubs.report.FeeReportResult;
import com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportQuery;
import com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportResult;
import com.binance.raftexchange.stubs.request.ApiOrderBookRequest;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.ManagedChannel;
import io.grpc.NameResolverRegistry;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollSocketChannel;
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel;

import javax.annotation.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

class ExchangeReadOnlyClient implements AutoCloseable {

    final QueryServiceGrpc.QueryServiceFutureStub searchServiceFutureStub;

    ExchangeReadOnlyClient(EventLoopGroup masterLoopGroup) {
        this.searchServiceFutureStub = initStub(masterLoopGroup);
    }

    public CompletableFuture<CommandResult> searchOrderBook(ApiOrderBookRequest request) {
        return toCompletable(searchServiceFutureStub.searchOrder(request));
    }

    public CompletableFuture<SingleUserReportResult> singleUserReport(int transferId, SingleUserReportQuery query) {
        return query(ReportQuery.newBuilder().setTransferId(transferId).setSingleUserReport(query).build())
            .thenApply(ReportResult::getSingleUserReport);
    }

    public CompletableFuture<StateHashReportResult> stateHashReport(int transferId, StateHashReportQuery query) {
        return query(ReportQuery.newBuilder().setTransferId(transferId).setStateHash(query).build())
            .thenApply(ReportResult::getStateHash);
    }

    public CompletableFuture<TotalCurrencyBalanceReportResult> totalCurrencyBalanceReport(int transferId,
        TotalCurrencyBalanceReportQuery query) {
        return query(ReportQuery.newBuilder().setTransferId(transferId).setTotalCurrencyBalance(query).build())
            .thenApply(ReportResult::getTotalCurrencyBalance);
    }

    public CompletableFuture<SymbolCurrencyReportResult> symbolCurrencyReport(int transferId,
        SymbolCurrencyReportQuery query) {
        return query(ReportQuery.newBuilder().setTransferId(transferId).setSymbolCurrencyReport(query).build())
            .thenApply(ReportResult::getSymbolCurrencyReport);
    }

    public CompletableFuture<FeeReportResult> feeReport(int transferId, FeeReportQuery query) {
        return query(ReportQuery.newBuilder().setTransferId(transferId).setFeeReport(query).build())
            .thenApply(ReportResult::getFeeReport);
    }

    public CompletableFuture<InsuranceFundReportResult> insuranceFundReport(int transferId,
        InsuranceFundReportQuery query) {
        return query(ReportQuery.newBuilder().setTransferId(transferId).setInsuranceFund(query).build())
            .thenApply(ReportResult::getInsuranceFund);
    }

    public CompletableFuture<LoanPlatformReportResult> loanPlatformReport(int transferId,
        LoanPlatformReportQuery query) {
        return query(ReportQuery.newBuilder().setTransferId(transferId).setLoanPlatform(query).build())
            .thenApply(ReportResult::getLoanPlatform);
    }

    private CompletableFuture<ReportResult> query(ReportQuery request) {
        return toCompletable(searchServiceFutureStub.query(request));
    }

    static <T> CompletableFuture<T> toCompletable(ListenableFuture<T> source) {
        CompletableFuture<T> future = new CompletableFuture<>();
        Futures.addCallback(source, new FutureCallback<T>() {
            @Override
            public void onSuccess(@Nullable T result) {
                future.complete(result);
            }

            @Override
            public void onFailure(Throwable t) {
                future.completeExceptionally(t);
            }
        }, MoreExecutors.directExecutor());
        return future;
    }

    private QueryServiceGrpc.QueryServiceFutureStub initStub(EventLoopGroup masterLoopGroup) {
        ManagedChannel channel = NettyChannelBuilder.forTarget(RaftNameResolverProvider.SCHEMA + "://search")
            .eventLoopGroup(masterLoopGroup).defaultLoadBalancingPolicy("round_robin")
            .channelType(
                masterLoopGroup instanceof EpollEventLoopGroup ? EpollSocketChannel.class : NioSocketChannel.class)
            .usePlaintext().build();
        return QueryServiceGrpc.newFutureStub(channel);
    }

    static {
        NameResolverRegistry.getDefaultRegistry().register(new RaftNameResolverProvider());
    }

    @Override
    public void close() throws Exception {
        ManagedChannel channel = (ManagedChannel)searchServiceFutureStub.getChannel();
        channel.shutdown();
        if (!channel.awaitTermination(2, TimeUnit.SECONDS)) {
            channel.shutdownNow();
        }
    }
}
