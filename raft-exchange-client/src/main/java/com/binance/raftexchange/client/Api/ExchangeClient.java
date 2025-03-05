package com.binance.raftexchange.client.Api;

import com.binance.raftexchange.stubs.api.ApiCommandServiceGrpc;
import com.binance.raftexchange.stubs.api.ServerNodeServiceGrpc;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.NodeListCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.NodeList;
import com.binance.raftexchange.stubs.response.NodeType;
import com.binance.raftexchange.stubs.response.ServerNode;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioSocketChannel;
import io.grpc.netty.shaded.io.netty.util.concurrent.ScheduledFuture;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ExchangeClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExchangeClient.class);

    private static EventLoopGroup DEFAULT_EVENTLOOP_GROUP = new NioEventLoopGroup(1);

    private ApiCommandServiceGrpc.ApiCommandServiceStub apiStub;

    private volatile ServerNode leaderNode;

    private final ServerNodeServiceGrpc.ServerNodeServiceFutureStub nodeStub;

    private final Set<ApiStream> streams = ConcurrentHashMap.newKeySet();

    // todo close考虑关闭
    private final ScheduledFuture flushTimer;

    public ExchangeClient(String host, int port) {
        ManagedChannel leaderChannel = sniffLeaderChannel(host, port);
        this.nodeStub = ServerNodeServiceGrpc.newFutureStub(leaderChannel);
        this.apiStub = ApiCommandServiceGrpc.newStub(leaderChannel);
        this.flushTimer = flushLeaderNode();
    }

    public ApiStream createStream(StreamObserver<CommandResult> resultStreamObserver) {
        ApiStream stream = new ApiStream(this, resultStreamObserver);
        StreamObserver<ApiCommand> apiCommandStreamObserver = apiStub.execApiCommand(stream.toUserObserver());
        stream.replaceInternalObserver(apiCommandStreamObserver);
        streams.add(stream);
        return stream;
    }

    private ManagedChannel createChannel(String host, int port) {
        // 统一放在这里 以后在这里做负载均衡的配置
        // 目前先统一打到Leader上
        return NettyChannelBuilder.forAddress(host, port).eventLoopGroup(DEFAULT_EVENTLOOP_GROUP)
            .channelType(NioSocketChannel.class).usePlaintext().build();
    }

    private ScheduledFuture flushLeaderNode() {
        // todo 暂时放长一点
        return DEFAULT_EVENTLOOP_GROUP.schedule(this::flushLeaderNode0, 30, TimeUnit.DAYS);
    }

    private void flushLeaderNode0() {
        ServerNode currentLeaderNode = this.leaderNode;
        Futures.addCallback(nodeStub.listNodes(NodeListCommand.getDefaultInstance()), new FutureCallback<NodeList>() {
            @Override
            public void onSuccess(@Nullable NodeList nodeList) {
                Optional<ServerNode> optionalServerNode =
                    nodeList.getNodesList().stream().filter(n -> n.getType() == NodeType.LEADER).findFirst();
                if (!optionalServerNode.isPresent()) {
                    LOGGER.error("Cant find any leaderNode!");
                    return;
                }

                ServerNode leaderNode = optionalServerNode.get();
                if (isSameIp(leaderNode.getHost(), currentLeaderNode.getHost())
                    && currentLeaderNode.getPort() == leaderNode.getPort()) {
                    return;
                }

                LOGGER.info("leader change!");
                reportLeaderFresh(leaderNode);
            }

            @Override
            public void onFailure(Throwable throwable) {
                LOGGER.error("flashLeaderNode fail!", throwable);
            }
        }, DEFAULT_EVENTLOOP_GROUP); // 调度回去 可以省下很多streamObserver的状态同步开销
    }

    private ManagedChannel sniffLeaderChannel(String host, int port) {
        // 先任意连一个上去
        ManagedChannel tryChannel = createChannel(host, port);
        boolean trySuccess = false;

        try {
            // 这里用了阻塞的 或许之后可以改造为异步的
            NodeList nodeList =
                ServerNodeServiceGrpc.newBlockingStub(tryChannel).listNodes(NodeListCommand.getDefaultInstance());

            ServerNode leaderNode = nodeList.getNodesList().stream().filter(n -> n.getType() == NodeType.LEADER)
                .findFirst().orElseThrow(() -> new IllegalStateException("Cant find any leaderNode!"));
            this.leaderNode = leaderNode;
            if (isSameIp(host, leaderNode.getHost()) && port == leaderNode.getPort()) {
                trySuccess = true;
                return tryChannel;
            } else {
                String leaderHost = leaderNode.getHost();
                int leaderNodePort = leaderNode.getPort();
                return createChannel(leaderHost, leaderNodePort);
            }
        } finally {
            if (!trySuccess) {
                tryChannel.shutdown();
            }
        }
    }

    private boolean isSameIp(String host, String ip) {
        try {
            InetAddress[] allByName = InetAddress.getAllByName(host);
            for (InetAddress inetAddress : allByName) {
                String hostAddress = inetAddress.getHostAddress();
                if (hostAddress.equals(ip)) {
                    return true;
                }
            }
            return false;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    void endStream(ApiStream stream) {
        streams.remove(stream);
    }

    // 监测到leader切换 进行刷新stream 以确保用户调用无感
    void reportLeaderFresh(ServerNode leaderNode) {
        this.leaderNode = leaderNode;
        String leaderHost = leaderNode.getHost();
        int leaderPort = leaderNode.getPort();
        ManagedChannel leaderChannel = createChannel(leaderHost, leaderPort);
        ApiCommandServiceGrpc.ApiCommandServiceStub stub = ApiCommandServiceGrpc.newStub(leaderChannel);
        // node stub就不切了 每个节点都有全量的拓扑信息
        this.apiStub = stub;
        for (ApiStream stream : streams) {
            StreamObserver<ApiCommand> apiCommandStreamObserver = apiStub.execApiCommand(stream.toUserObserver());
            stream.replaceInternalObserver(apiCommandStreamObserver);
        }
    }
}
