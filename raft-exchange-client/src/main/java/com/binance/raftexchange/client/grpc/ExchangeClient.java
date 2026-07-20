package com.binance.raftexchange.client.grpc;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.stubs.api.ApiCommandServiceGrpc;
import com.binance.raftexchange.stubs.api.ServerNodeServiceGrpc;
import com.binance.raftexchange.stubs.report.SingleUserReportQuery;
import com.binance.raftexchange.stubs.report.SingleUserReportResult;
import com.binance.raftexchange.stubs.report.StateHashReportQuery;
import com.binance.raftexchange.stubs.report.StateHashReportResult;
import com.binance.raftexchange.stubs.report.SymbolCurrencyReportQuery;
import com.binance.raftexchange.stubs.report.SymbolCurrencyReportResult;
import com.binance.raftexchange.stubs.report.FeeReportQuery;
import com.binance.raftexchange.stubs.report.InsuranceFundReportQuery;
import com.binance.raftexchange.stubs.report.InsuranceFundReportResult;
import com.binance.raftexchange.stubs.report.LoanPlatformReportQuery;
import com.binance.raftexchange.stubs.report.LoanPlatformReportResult;
import com.binance.raftexchange.stubs.report.FeeReportResult;
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

public class ExchangeClient implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExchangeClient.class);

    public static final Duration DEFAULT_NODES_FLUSH_INTERVAL = Duration.ofMinutes(1);

    // === leader 状态：随 NEED_MOVE / poll 切换变动 ===
    private volatile ApiCommandServiceGrpc.ApiCommandServiceStub apiStub;
    private volatile ServerNodeServiceGrpc.ServerNodeServiceFutureStub nodeStub;
    private volatile ServerNode leaderNode;

    // === 共享资源：构造期建，close 时释放 ===
    private final EventLoopGroup eventLoopGroup;
    private final ExchangeReadOnlyClient readOnlyClient;
    private final Set<ApiStream> streams = ConcurrentHashMap.newKeySet();
    private final ScheduledFuture<?> flushTimer;

    // === 配置 ===
    private final Duration nodesFlushInterval;

    public ExchangeClient(String host, int port) {
        this(host, port, DEFAULT_NODES_FLUSH_INTERVAL);
    }

    public ExchangeClient(String host, int port, Duration nodesFlushInterval) {
        this.nodesFlushInterval = requirePositive(nodesFlushInterval, "nodesFlushInterval");
        ThreadFactory threadFactory = GrpcUtil.getThreadFactory("grpc-worker-%d", true);
        this.eventLoopGroup =
            Epoll.isAvailable() ? new EpollEventLoopGroup(1, threadFactory) : new NioEventLoopGroup(1, threadFactory);
        ManagedChannel leaderChannel = bootstrapLeaderChannel(host, port);
        this.nodeStub = ServerNodeServiceGrpc.newFutureStub(leaderChannel);
        this.apiStub = ApiCommandServiceGrpc.newStub(leaderChannel);
        this.readOnlyClient = new ExchangeReadOnlyClient(eventLoopGroup);
        this.flushTimer = startNodesFlushTimer();
    }

    ExchangeClient(EventLoopGroup eventLoopGroup, ManagedChannel initialLeaderChannel, ServerNode initialLeaderNode,
        ExchangeReadOnlyClient readOnlyClient) {
        this.nodesFlushInterval = DEFAULT_NODES_FLUSH_INTERVAL;
        this.eventLoopGroup = eventLoopGroup;
        this.leaderNode = initialLeaderNode;
        this.nodeStub = ServerNodeServiceGrpc.newFutureStub(initialLeaderChannel);
        this.apiStub = ApiCommandServiceGrpc.newStub(initialLeaderChannel);
        this.readOnlyClient = readOnlyClient;
        this.flushTimer = null;
    }

    public Duration nodesFlushInterval() {
        return nodesFlushInterval;
    }

    private static Duration requirePositive(Duration d, String name) {
        if (d == null || d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(name + " must be positive, got " + d);
        }
        return d;
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
        return readOnlyClient.singleUserReport(transferId,
            SingleUserReportQuery.newBuilder().setUserId(userId).build());
    }

    public CompletableFuture<StateHashReportResult> stateHashReport(int transferId) {
        return readOnlyClient.stateHashReport(transferId, StateHashReportQuery.getDefaultInstance());
    }

    public CompletableFuture<TotalCurrencyBalanceReportResult> totalCurrencyBalanceReport(int transferId) {
        return readOnlyClient.totalCurrencyBalanceReport(transferId,
            TotalCurrencyBalanceReportQuery.getDefaultInstance());
    }

    public CompletableFuture<SymbolCurrencyReportResult> symbolCurrencyReport(int transferId) {
        return readOnlyClient.symbolCurrencyReport(transferId, SymbolCurrencyReportQuery.getDefaultInstance());
    }

    public CompletableFuture<InsuranceFundReportResult> insuranceFundReport(int transferId) {
        return readOnlyClient.insuranceFundReport(transferId, InsuranceFundReportQuery.getDefaultInstance());
    }

    public CompletableFuture<LoanPlatformReportResult> loanPlatformReport(int transferId) {
        return readOnlyClient.loanPlatformReport(transferId, LoanPlatformReportQuery.getDefaultInstance());
    }

    public CompletableFuture<FeeReportResult> feeReport(int transferId) {
        return readOnlyClient.feeReport(transferId, FeeReportQuery.getDefaultInstance());
    }

    protected ManagedChannel createChannel(String host, int port) {
        return NettyChannelBuilder.forAddress(host, port).eventLoopGroup(eventLoopGroup)
            .channelType(
                eventLoopGroup instanceof EpollEventLoopGroup ? EpollSocketChannel.class : NioSocketChannel.class)
            .usePlaintext().build();
    }

    protected ScheduledFuture<?> startNodesFlushTimer() {
        flushNodes();
        long intervalMs = nodesFlushInterval.toMillis();
        return eventLoopGroup.scheduleWithFixedDelay(this::flushNodes, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    protected void flushNodes() {
        ServerNode currentLeaderNode = this.leaderNode;
        Futures.addCallback(nodeStub.listNodes(NodeListCommand.getDefaultInstance()), new FutureCallback<NodeList>() {
            @Override
            public void onSuccess(NodeList nodeList) {
                onNodeListReceived(nodeList, currentLeaderNode);
            }

            @Override
            public void onFailure(Throwable throwable) {
                LOGGER.error("flushLeaderNode fail", throwable);
            }
        }, eventLoopGroup);
    }

    protected void onNodeListReceived(NodeList nodeList, ServerNode currentLeaderNode) {
        List<ServerNode> nodesList = nodeList.getNodesList();
        RaftNameResolverProvider.refresh(nodesList);
        Optional<ServerNode> optionalServerNode =
            nodesList.stream().filter(n -> n.getType() == NodeType.LEADER).findFirst();
        if (!optionalServerNode.isPresent()) {
            LOGGER.error("Cant find any leaderNode! nodes={}", nodesList.size());
            return;
        }
        ServerNode leaderNode = optionalServerNode.get();
        if (isSameIp(leaderNode.getHost(), currentLeaderNode.getHost())
            && currentLeaderNode.getPort() == leaderNode.getPort()) {
            return;
        }
        LOGGER.info("Leader changed: {}:{} -> {}:{}", currentLeaderNode.getHost(), currentLeaderNode.getPort(),
            leaderNode.getHost(), leaderNode.getPort());
        switchToNewLeader(leaderNode);
    }

    protected ManagedChannel bootstrapLeaderChannel(String host, int port) {
        ManagedChannel tryChannel = createChannel(host, port);
        boolean trySuccess = false;
        try {
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

    protected boolean isSameIp(String host, String ip) {
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

    protected void endStream(ApiStream stream) {
        streams.remove(stream);
    }

    protected void switchToNewLeader(ServerNode leaderNode) {
        long t0 = System.nanoTime();
        ManagedChannel oldChannel = (ManagedChannel)apiStub.getChannel();
        this.leaderNode = leaderNode;
        ManagedChannel leaderChannel = createChannel(leaderNode.getHost(), leaderNode.getPort());
        this.apiStub = ApiCommandServiceGrpc.newStub(leaderChannel);
        this.nodeStub = ServerNodeServiceGrpc.newFutureStub(leaderChannel);
        int rebound = 0;
        for (ApiStream stream : streams) {
            StreamObserver<ApiCommand> apiCommandStreamObserver = apiStub.execApiCommand(stream.toUserObserver());
            stream.replaceInternalObserver(apiCommandStreamObserver);
            rebound++;
        }
        oldChannel.shutdown();
        LOGGER.info("leader channel swap done: rebound={} stream(s), elapsedMs={}", rebound,
            (System.nanoTime() - t0) / 1_000_000);
    }

    @Override
    public void close() throws Exception {
        if (flushTimer != null) {
            flushTimer.cancel(false);
        }
        readOnlyClient.close();
        ManagedChannel apiChannel = (ManagedChannel)apiStub.getChannel();
        apiChannel.shutdown();
        if (!apiChannel.awaitTermination(2, TimeUnit.SECONDS)) {
            apiChannel.shutdownNow();
        }
        eventLoopGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
    }
}