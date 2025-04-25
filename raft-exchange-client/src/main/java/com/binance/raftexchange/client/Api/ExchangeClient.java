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
import io.grpc.internal.GrpcUtil;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.channel.EventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.Epoll;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.epoll.EpollSocketChannel;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class ExchangeClient implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExchangeClient.class);

    private ApiCommandServiceGrpc.ApiCommandServiceStub apiStub;

    private volatile ServerNode leaderNode;

    private final EventLoopGroup eventLoopgroup;

    private final ServerNodeServiceGrpc.ServerNodeServiceFutureStub nodeStub;

    private final Set<ApiStream> streams = ConcurrentHashMap.newKeySet();

    private final ScheduledFuture<?> flushTimer;

    private final ExchangeReadOnlyClient readOnlyClient;

    public ExchangeClient(String host, int port) {
        ThreadFactory threadFactory = GrpcUtil.getThreadFactory("grpc-worker-%d", true);
        int threadCount = Runtime.getRuntime().availableProcessors();
        this.eventLoopgroup = Epoll.isAvailable() ? new EpollEventLoopGroup(threadCount, threadFactory) : new NioEventLoopGroup(threadCount, threadFactory);
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
        return readOnlyClient.searchOrderBook(ApiOrderBookRequest.newBuilder().setSymbol(symbol).setSize(size).build());
    }

    public CompletableFuture<SingleUserReportResult> singleUserReport(int transferId, long userId) {
        return readOnlyClient.singleUserReport(transferId, SingleUserReportQuery.newBuilder().setUserId(userId).build());
    }

    public CompletableFuture<StateHashReportResult> stateHashReport(int transferId) {
        return readOnlyClient.stateHashReport(transferId, StateHashReportQuery.getDefaultInstance());
    }

    public CompletableFuture<TotalCurrencyBalanceReportResult> totalCurrencyBalanceReport(int transferId) {
        return readOnlyClient.totalCurrencyBalanceReport(transferId, TotalCurrencyBalanceReportQuery.getDefaultInstance());
    }

    private ManagedChannel createChannel(String host, int port) {
        return NettyChannelBuilder.forAddress(host, port).eventLoopGroup(eventLoopgroup)
            .channelType(eventLoopgroup instanceof EpollEventLoopGroup ? EpollSocketChannel.class : NioSocketChannel.class).usePlaintext().build();
    }

    private ScheduledFuture<?> flushNodesInfo() {
        flushNodes();
        return eventLoopgroup.scheduleWithFixedDelay(this::flushNodes, 30, 30, TimeUnit.MINUTES);
    }

    private void flushNodes() {
        ServerNode currentLeaderNode = this.leaderNode;
        Futures.addCallback(nodeStub.listNodes(NodeListCommand.getDefaultInstance()), new FutureCallback<NodeList>() {
            @Override
            public void onSuccess(@Nullable NodeList nodeList) {
                List<ServerNode> nodesList = nodeList.getNodesList();
                RaftNameResolverProvider.refresh(nodesList);
                Optional<ServerNode> optionalServerNode = nodesList.stream().filter(n -> n.getType() == NodeType.LEADER).findFirst();
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
        }, eventLoopgroup);
    }

    private ManagedChannel sniffLeaderChannel(String host, int port) {
        ManagedChannel tryChannel = createChannel(host, port);
        boolean trySuccess = false;
        try {
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

    void reportLeaderFresh(ServerNode leaderNode) {
        this.leaderNode = leaderNode;
        String leaderHost = leaderNode.getHost();
        int leaderPort = leaderNode.getPort();
        ManagedChannel leaderChannel = createChannel(leaderHost, leaderPort);
        ApiCommandServiceGrpc.ApiCommandServiceStub stub = ApiCommandServiceGrpc.newStub(leaderChannel);
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