package com.binance.raftexchange.client.Api;

import com.binance.raftexchange.stubs.api.SearchServiceGrpc;
import com.binance.raftexchange.stubs.request.ApiOrderBookRequest;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.NameResolverRegistry;

import javax.annotation.Nullable;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

class ExchangeReadOnlyClient {

    final SearchServiceGrpc.SearchServiceFutureStub searchServiceFutureStub;

    ExchangeReadOnlyClient() {
        this.searchServiceFutureStub = initStub();
    }

    public CompletableFuture<CommandResult> searchOrderBook(ApiOrderBookRequest request) {
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        //转一下。。 ListenableFuture是真的难用
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

    private SearchServiceGrpc.SearchServiceFutureStub initStub() {
        ManagedChannel channel = ManagedChannelBuilder.forTarget(RaftExchangeNameResolver.SCHEMA + "://search")
                .defaultLoadBalancingPolicy("round_robin")
                .usePlaintext()
                .build();
        return SearchServiceGrpc.newFutureStub(channel);
    }

    static {
        NameResolverRegistry.getDefaultRegistry().register(new NameResolverProvider() {
            @Override
            protected boolean isAvailable() {
                return true;
            }

            @Override
            protected int priority() {
                return 0;
            }

            @Override
            public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
                return new RaftExchangeNameResolver(targetUri);
            }

            @Override
            public String getDefaultScheme() {
                return RaftExchangeNameResolver.SCHEMA;
            }
        });
    }
}
