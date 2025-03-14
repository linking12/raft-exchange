package com.binance.raftexchange.client.Api;

import com.binance.raftexchange.stubs.api.ApiCommandServiceGrpc;
import com.binance.raftexchange.stubs.api.ServerNodeServiceGrpc;
import com.binance.raftexchange.stubs.report.SingleUserReportQuery;
import com.binance.raftexchange.stubs.report.SingleUserReportResult;
import com.binance.raftexchange.stubs.report.StateHashReportQuery;
import com.binance.raftexchange.stubs.report.StateHashReportResult;
import com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportQuery;
import com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportResult;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiOrderBookRequest;
import com.binance.raftexchange.stubs.request.NodeListCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.NodeList;
import com.binance.raftexchange.stubs.response.NodeType;
import com.binance.raftexchange.stubs.response.ServerNode;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import io.grpc.Channel;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class ExchangeClient implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExchangeClient.class);

    private EventLoopGroup eventLoopgroup;

    private ApiCommandServiceGrpc.ApiCommandServiceStub apiStub;

    private volatile ServerNode leaderNode;

    private final ServerNodeServiceGrpc.ServerNodeServiceFutureStub nodeStub;

    private final Set<ApiStream> streams = ConcurrentHashMap.newKeySet();

    private final ScheduledFuture<?> flushTimer;

    private final ExchangeReadOnlyClient readOnlyClient;

    public ExchangeClient(String host, int port) {
        this.eventLoopgroup = new NioEventLoopGroup(1);
        ManagedChannel leaderChannel = sniffLeaderChannel(host, port);
        this.nodeStub = ServerNodeServiceGrpc.newFutureStub(leaderChannel);
        this.apiStub = ApiCommandServiceGrpc.newStub(leaderChannel);
        this.readOnlyClient = new ExchangeReadOnlyClient(eventLoopgroup);
        this.flushTimer = flushNodesInfo();
    }

    public ApiStream createStream(StreamObserver<CommandResult> resultStreamObserver) {
        ApiStream stream = new ApiStream(this, resultStreamObserver);
        StreamObserver<ApiCommand> apiCommandStreamObserver = apiStub.execApiCommand(stream.toUserObserver());
        stream.replaceInternalObserver(apiCommandStreamObserver);
        streams.add(stream);
        return stream;
    }

    public CompletableFuture<CommandResult> searchOrderBook(int symbol, int size) {
        return readOnlyClient.searchOrderBook(
                ApiOrderBookRequest.newBuilder()
                        .setSymbol(symbol)
                        .setSize(size)
                        .build()
        );
    }

    public CompletableFuture<SingleUserReportResult> singleUserReport(int transferId, long userId) {
        return readOnlyClient.singleUserReport(
                transferId,
                SingleUserReportQuery.newBuilder()
                        .setUserId(userId)
                        .build()
        );
    }

    public CompletableFuture<StateHashReportResult> stateHashReport(int transferId) {
        return readOnlyClient.stateHashReport(
                transferId,
                StateHashReportQuery.getDefaultInstance()
        );
    }

    public CompletableFuture<TotalCurrencyBalanceReportResult> totalCurrencyBalanceReport(int transferId) {
        return readOnlyClient.totalCurrencyBalanceReport(
                transferId,
                TotalCurrencyBalanceReportQuery.getDefaultInstance()
        );
    }

    private ManagedChannel createChannel(String host, int port) {
        // 统一放在这里 以后在这里做负载均衡的配置
        // 目前先统一打到Leader上
        return NettyChannelBuilder.forAddress(host, port).eventLoopGroup(eventLoopgroup).channelType(NioSocketChannel.class).usePlaintext().build();
    }

    private ScheduledFuture flushNodesInfo() {
        //先flush下确保client那边正常
        flushNodes();
        return eventLoopgroup.schedule(this::flushNodes, 30, TimeUnit.MINUTES);
    }

    private void flushNodes() {
        ServerNode currentLeaderNode = this.leaderNode;
        Futures.addCallback(nodeStub.listNodes(NodeListCommand.getDefaultInstance()), new FutureCallback<NodeList>() {
            @Override
            public void onSuccess(@Nullable NodeList nodeList) {
                List<ServerNode> nodesList = nodeList.getNodesList();
                RaftNameResolverProvider.refresh(nodesList);
                Optional<ServerNode> optionalServerNode =
                    nodesList.stream().filter(n -> n.getType() == NodeType.LEADER).findFirst();
                if (!optionalServerNode.isPresent()) {
                    LOGGER.error("Cant find any leaderNode!");
                    return;
                }

                ServerNode leaderNode = optionalServerNode.get();
                if (isSameIp(leaderNode.getHost(), currentLeaderNode.getHost()) && currentLeaderNode.getPort() == leaderNode.getPort()) {
                    return;
                }

                LOGGER.info("leader change!");
                reportLeaderFresh(leaderNode);
            }

            @Override
            public void onFailure(Throwable throwable) {
                LOGGER.error("flashLeaderNode fail!", throwable);
            }
        }, eventLoopgroup); // 调度回去 可以省下很多streamObserver的状态同步开销
    }

    private ManagedChannel sniffLeaderChannel(String host, int port) {
        // 先任意连一个上去
        ManagedChannel tryChannel = createChannel(host, port);
        boolean trySuccess = false;

        try {
            // 这里用了阻塞的 或许之后可以改造为异步的
            NodeList nodeList = ServerNodeServiceGrpc.newBlockingStub(tryChannel).listNodes(NodeListCommand.getDefaultInstance());

            ServerNode leaderNode = nodeList.getNodesList().stream().filter(n -> n.getType() == NodeType.LEADER).findFirst()
                .orElseThrow(() -> new IllegalStateException("Cant find any leaderNode!"));
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

    @Override
    public void close() throws Exception {
        flushTimer.cancel(false);
        Channel channel = apiStub.getChannel();
        ManagedChannel managedChannel = (ManagedChannel)channel;
        managedChannel.shutdown();
        readOnlyClient.close();
    }
}
