package com.binance.raftexchange.server.raft.aeron;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.exchange.ExchangeCalls;
import com.binance.raftexchange.server.exchange.ExchangeRuntime;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiNop;
import com.binance.raftexchange.server.metrics.RaftExchangeMetrics;
import com.binance.raftexchange.server.raft.AdminResult;
import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftClusterDiscovery;
import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.server.raft.RaftResponse;
import com.binance.raftexchange.server.util.AppHome;

import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.processors.liquidation.LiquidationScheduledService;

/**
 * Aeron raft backend 容器：封装 {@link AeronClusterServer} + {@link AeronClusterClient}，按 {@link RaftClusterContainer} 对外暴露。
 */
public class AeronClusterContainer implements RaftClusterContainer {

    private static final Logger LOGGER = LoggerFactory.getLogger(AeronClusterContainer.class);
    private static final long SNAPSHOT_TIMEOUT_SEC = 300L;
    private static final long STEPDOWN_RELAUNCH_WAIT_MS = 3000L;
    private static final long READ_BARRIER_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(5);
    private static final long READ_BARRIER_POLL_INTERVAL_MS = 1L;

    private final RaftClusterDiscovery raftClusterDiscovery;
    private final ExchangeRuntime exchangeRuntime;
    private final CopyOnWriteArrayList<Consumer<RaftNode.NodeType>> roleListeners = new CopyOnWriteArrayList<>();

    private volatile List<MemberSpec> members = List.of();
    private volatile MemberSpec self;
    private volatile Path snapshotsRoot;
    private volatile AeronClusterServer server;
    private volatile AeronClusterClient client;
    private volatile AeronExchangeStateMachine sm;
    private volatile boolean isLeader;

    public AeronClusterContainer(RaftClusterDiscovery raftClusterDiscovery, ExchangeRuntime exchangeRuntime) {
        this.raftClusterDiscovery = raftClusterDiscovery;
        this.exchangeRuntime = exchangeRuntime;

        List<LiquidationEngine> engines = exchangeRuntime.exchangeCore().getLiquidationEngines();
        if (engines == null) {
            return;
        }
        exchangeRuntime.overrideLiquidationCommandSubmitter(this::isLeader,
            (log, onComplete) -> requestConsensus(log, (resp, err) -> onComplete.accept(err)));
        addRoleChangeListener(role -> {
            if (role == RaftNode.NodeType.LEADER) {
                engines.forEach(LiquidationScheduledService::start);
            } else {
                engines.forEach(LiquidationScheduledService::stop);
            }
        });
    }

    @Override
    public boolean isLeader() {
        return isLeader;
    }

    @Override
    public void addRoleChangeListener(Consumer<RaftNode.NodeType> listener) {
        roleListeners.add(listener);
        listener.accept(isLeader ? RaftNode.NodeType.LEADER : RaftNode.NodeType.FOLLOWER);
    }

    @Override
    public ExchangeCalls exchangeCalls() {
        return exchangeRuntime.exchangeCalls();
    }

    @Override
    public void doStart() throws Exception {
        String raftCurrentMember = raftClusterDiscovery.raftCurrentMember();
        LOGGER.info("Waiting for raft cluster discovery, self={}", raftCurrentMember);
        String raftMemberCluster = raftClusterDiscovery.raftMemberCluster();
        while (raftMemberCluster == null) {
            TimeUnit.SECONDS.sleep(5);
            raftMemberCluster = raftClusterDiscovery.raftMemberCluster();
        }
        LOGGER.info("Raft cluster discovered: {}", raftMemberCluster);

        List<String> sortedPeers = Arrays.stream(raftMemberCluster.split(",")).sorted().toList();
        members = new ArrayList<>(sortedPeers.size());
        for (int i = 0; i < sortedPeers.size(); i++) {
            members.add(new MemberSpec(i, sortedPeers.get(i)));
        }
        int selfId = sortedPeers.indexOf(raftCurrentMember);
        if (selfId < 0) {
            throw new IllegalStateException("self (" + raftCurrentMember + ") not in members " + members);
        }
        self = members.get(selfId);

        Path base = AppHome.appHome().resolve(raftClusterDiscovery.getRaftClusterName() + "-DATA");
        Path clusterDir = AppHome.isMacOs() ? base.resolve(raftCurrentMember) : base;
        FileUtils.forceMkdir(clusterDir.toFile());
        snapshotsRoot = clusterDir.resolve("snapshots");
        FileUtils.forceMkdir(snapshotsRoot.toFile());
        LOGGER.info("Aeron Cluster starting: self={} members={} dir={}", self, members, clusterDir);

        SnapshotFetcher fetcher = new SnapshotFetcherImpl(raftClusterDiscovery);
        sm = new AeronExchangeStateMachine(exchangeRuntime.exchangeCalls(), snapshotsRoot, fetcher);
        sm.addRoleChangeListener(this::onRoleChange);
        server = new AeronClusterServer(clusterDir, self, members);
        client = new AeronClusterClient(clusterDir, self, members);
        server.setClusteredService(sm);
        server.start();
        client.start();
        RaftExchangeMetrics.Raft.register(this::isLeader, this::currentLogPosition, this::currentLogPosition);
        RaftExchangeMetrics.Raft.registerPendingSizeSupplier(this::currentPendingSize);
        LOGGER.info("Aeron Cluster started on {}, leaderMemberId={}", self, client.leaderMemberId());
    }

    @Override
    public Path snapshotsRoot() {
        return snapshotsRoot;
    }

    @Override
    public void doStop() throws Exception {
        if (server != null) {
            server.close();
            server = null;
        }
        if (client != null) {
            client.close();
            client = null;
        }
        sm = null;
    }

    private long currentLogPosition() {
        AeronExchangeStateMachine currentSm = sm;
        return currentSm == null ? 0L : currentSm.lastLogPosition();
    }

    private long currentPendingSize() {
        AeronExchangeStateMachine currentSm = sm;
        return currentSm == null ? 0L : currentSm.pendingSize();
    }

    @Override
    public void requestConsensus(byte[] cmdBytes, BiConsumer<RaftResponse, Throwable> callback) {
        AeronClusterClient currentClient = client;
        if (currentClient == null) {
            callback.accept(null, new IllegalStateException("AeronClusterContainer not started"));
            return;
        }
        currentClient.send(cmdBytes, callback);
    }

    @Override
    public List<RaftNode> listNodes() {
        AeronClusterClient currentClient = client;
        int leaderId = currentClient == null ? -1 : currentClient.leaderMemberId();
        List<RaftNode> nodes = new ArrayList<>(members.size());
        for (MemberSpec member : members) {
            nodes.add(toRaftNode(member,
                member.memberId() == leaderId ? RaftNode.NodeType.LEADER : RaftNode.NodeType.FOLLOWER));
        }
        return nodes;
    }

    @Override
    public RaftNode leaderNode() {
        AeronClusterClient currentClient = client;
        int leaderId = currentClient == null ? -1 : currentClient.leaderMemberId();
        return leaderId < 0 ? null : toRaftNode(members.get(leaderId), RaftNode.NodeType.LEADER);
    }

    @Override
    public CompletableFuture<AdminResult> triggerSnapshot() {
        AeronExchangeStateMachine currentSm = sm;
        if (currentSm == null) {
            return CompletableFuture.completedFuture(AdminResult.error("aeron not started"));
        }
        if (!isLeader) {
            return CompletableFuture.completedFuture(AdminResult.error("not leader"));
        }
        if (currentSm.stepDownInProgress().get()) {
            return CompletableFuture.completedFuture(AdminResult.error("stepdown in progress"));
        }
        return currentSm.requestSnapshot().thenApply(v -> AdminResult.ok())
            .exceptionally(t -> AdminResult.error(t.getMessage()));
    }

    @Override
    public CompletableFuture<Void> readConsistencyBarrier() {
        AeronClusterClient currentClient = client;
        AeronExchangeStateMachine currentSm = sm;
        if (currentClient == null || currentSm == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("AeronClusterContainer not started"));
        }
        long startNanos = System.nanoTime();
        CompletableFuture<Void> future = new CompletableFuture<>();
        byte[] nopBytes = ApiCommand.newBuilder().setNop(ApiNop.getDefaultInstance()).build().toByteArray();
        currentClient.send(nopBytes, (resp, err) -> {
            if (err != null) {
                RaftExchangeMetrics.ReadBarrier.recordFailure(startNanos);
                future.completeExceptionally(err);
                return;
            }
            long targetLogPos = resp.leaderLogPosition();
            long deadlineNanos = startNanos + READ_BARRIER_TIMEOUT_NS;
            Executor poller = CompletableFuture.delayedExecutor(READ_BARRIER_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
            Runnable checkCaughtUp = new Runnable() {
                @Override
                public void run() {
                    long applied = currentSm.lastLogPosition();
                    if (applied >= targetLogPos) {
                        RaftExchangeMetrics.ReadBarrier.recordSuccess(startNanos);
                        future.complete(null);
                    } else if (System.nanoTime() > deadlineNanos) {
                        LOGGER.warn("readConsistencyBarrier timeout: targetLogPos={} applied={} gap={}", targetLogPos,
                            applied, targetLogPos - applied);
                        RaftExchangeMetrics.ReadBarrier.recordFailure(startNanos);
                        future.completeExceptionally(new IllegalStateException(
                            "readConsistencyBarrier timeout: targetLogPos=" + targetLogPos + " applied=" + applied));
                    } else {
                        poller.execute(this);
                    }
                }
            };
            checkCaughtUp.run();
        });
        return future;
    }

    @Override
    public AdminResult stepDownLeadership() {
        if (server == null) {
            return AdminResult.error("aeron not started");
        }
        if (!isLeader) {
            return AdminResult.ok();
        }
        AeronClusterServer currentServer = server;
        AeronExchangeStateMachine currentSm = sm;
        if (!currentSm.stepDownInProgress().compareAndSet(false, true)) {
            return AdminResult.error("stepdown already in progress");
        }
        long startNanos = System.nanoTime();
        new Thread(() -> {
            try {
                LOGGER.info("stepDownLeadership: snapshot then relaunch consensus");
                currentSm.requestSnapshot().get(SNAPSHOT_TIMEOUT_SEC, TimeUnit.SECONDS);
                currentServer.relaunchConsensus(STEPDOWN_RELAUNCH_WAIT_MS);
                RaftExchangeMetrics.Raft.recordStepDownSuccess(startNanos);
                LOGGER.info("stepDownLeadership: relaunch done, node rejoined as follower");
            } catch (InterruptedException e) {
                RaftExchangeMetrics.Raft.recordStepDownFailure(startNanos);
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                RaftExchangeMetrics.Raft.recordStepDownFailure(startNanos);
                LOGGER.error("stepDownLeadership failed; halting JVM for supervisor restart", t);
                Runtime.getRuntime().halt(1);
            } finally {
                currentSm.stepDownInProgress().set(false);
            }
        }, "aeron-stepdown").start();
        return AdminResult.ok();
    }

    private void onRoleChange(boolean leader) {
        if (this.isLeader != leader) {
            RaftExchangeMetrics.Raft.recordLeaderChange();
        }
        this.isLeader = leader;
        RaftNode.NodeType roleType = leader ? RaftNode.NodeType.LEADER : RaftNode.NodeType.FOLLOWER;
        for (Consumer<RaftNode.NodeType> listener : roleListeners) {
            try {
                listener.accept(roleType);
            } catch (Throwable listenerErr) {
                LOGGER.warn("role listener failed", listenerErr);
            }
        }
    }

    private RaftNode toRaftNode(MemberSpec member, RaftNode.NodeType nodeType) {
        String grpcPeer = raftClusterDiscovery.raftToGrpcPeerMap().get(member.peer());
        int grpcPort = grpcPeer != null ? Integer.parseInt(grpcPeer.split(":")[1]) : -1;
        return new RaftNode(member.host(), grpcPort, nodeType);
    }

    public record MemberSpec(int memberId, String peer) {
        public String host() {
            return peer.substring(0, peer.indexOf(':'));
        }

        public int aeronBasePort() {
            return Integer.parseInt(peer.substring(peer.indexOf(':') + 1));
        }
    }
}
