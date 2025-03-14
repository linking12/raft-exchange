package com.binance.raftexchange.client.Api;

import com.binance.raftexchange.stubs.api.QueryServiceGrpc;
import com.binance.raftexchange.stubs.request.ApiOrderBookRequest;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import io.grpc.ManagedChannel;
import io.grpc.NameResolverRegistry;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
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

    private QueryServiceGrpc.QueryServiceFutureStub initStub(EventLoopGroup masterLoopGroup) {
        ManagedChannel channel = NettyChannelBuilder.forTarget(RaftNameResolverProvider.SCHEMA + "://search")
                .eventLoopGroup(masterLoopGroup)
                .defaultLoadBalancingPolicy("round_robin")
                .channelType(NioSocketChannel.class)
                .usePlaintext().build();
        return QueryServiceGrpc.newFutureStub(channel);
    }

    static {
        NameResolverRegistry.getDefaultRegistry().register(new RaftNameResolverProvider());
    }

    @Override
    public void close() throws Exception {
        ((ManagedChannel) searchServiceFutureStub.getChannel()).shutdown();
    }
}
