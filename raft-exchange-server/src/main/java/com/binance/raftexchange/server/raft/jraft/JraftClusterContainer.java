package com.binance.raftexchange.server.raft.jraft;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alipay.sofa.jraft.JRaftUtils;
import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.NodeManager;
import com.alipay.sofa.jraft.RaftGroupService;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.closure.ReadIndexClosure;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.entity.Task;
import com.alipay.sofa.jraft.option.NodeOptions;
import com.alipay.sofa.jraft.option.RaftOptions;
import com.alipay.sofa.jraft.option.ReadOnlyOption;
import com.alipay.sofa.jraft.util.Utils;
import com.alipay.sofa.jraft.util.concurrent.EventBusMode;
import com.binance.raftexchange.server.exchange.ExchangeCalls;
import com.binance.raftexchange.server.exchange.ExchangeRuntime;
import com.binance.raftexchange.server.metrics.RaftExchangeMetrics;
import com.binance.raftexchange.server.raft.AdminResult;
import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftClusterDiscovery;
import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.server.raft.RaftResponse;
import com.binance.raftexchange.server.raft.jraft.closure.BatchClosure;
import com.binance.raftexchange.server.raft.jraft.closure.BatchClosure.PendingCmd;
import com.binance.raftexchange.server.raft.jraft.closure.SingleClosure;
import com.binance.raftexchange.server.util.AppHome;
import com.binance.raftexchange.server.util.SerializeHelper;
import com.google.protobuf.GeneratedMessageV3;

import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.processors.liquidation.LiquidationScheduledService;

/**
 * SOFA-JRaft 作为 raft consensus backend 的容器实现：{@link RaftGroupService} +
 * {@link JraftExchangeStateMachine} + 可选 {@link BatchCommandHelper}（sysprop {@code raftexchange.batch.enabled}）。
 *
 * <p>默认调优：8M slot disruptor / applyBatch=128 / replicator inflight 2048 / lease read，匹配 5MT/s 撮合留余量。
 */
public class JraftClusterContainer implements RaftClusterContainer {

    private static final Logger LOGGER = LoggerFactory.getLogger(JraftClusterContainer.class);
    private static final int SNAPSHOT_INTERVAL_SECS = 3600;
    private static final int SNAPSHOT_LOG_INDEX_MARGIN =
        Integer.parseInt(System.getProperty("raftexchange.snapshot.logIndexMargin", "10000000"));
    private static final boolean BATCH_ENABLED =
        Boolean.parseBoolean(System.getProperty("raftexchange.batch.enabled", "false"));
    private static final byte[] EMPTY_REQUEST_CONTEXT = new byte[0];

    private final RaftClusterDiscovery raftClusterDiscovery;
    private final ExchangeRuntime exchangeRuntime;
    private final JraftExchangeStateMachine jraftExchangeStateMachine;
    private final BatchCommandHelper batchCommandHelper;

    private volatile RaftGroupService raftGroupService;

    public JraftClusterContainer(RaftClusterDiscovery raftClusterDiscovery, ExchangeRuntime exchangeRuntime) {
        this.raftClusterDiscovery = raftClusterDiscovery;
        this.exchangeRuntime = exchangeRuntime;
        this.jraftExchangeStateMachine = new JraftExchangeStateMachine(exchangeRuntime.exchangeCalls());
        this.batchCommandHelper = BATCH_ENABLED ? new BatchCommandHelper(this::publishEnvelopeToRaft) : null;

        List<LiquidationEngine> engines = exchangeRuntime.exchangeCore().getLiquidationEngines();
        if (engines == null) {
            return;
        }
        exchangeRuntime.overrideLiquidationCommandSubmitter(jraftExchangeStateMachine::isLeader,
            (log, onComplete) -> requestConsensus(log, (resp, err) -> onComplete.accept(err)));
        jraftExchangeStateMachine.addRoleChangeListener(role -> {
            if (role == RaftNode.NodeType.LEADER) {
                engines.forEach(LiquidationScheduledService::start);
            } else {
                engines.forEach(LiquidationScheduledService::stop);
            }
        });
    }

    @Override
    public boolean isLeader() {
        return jraftExchangeStateMachine.isLeader();
    }

    @Override
    public void addRoleChangeListener(Consumer<RaftNode.NodeType> listener) {
        jraftExchangeStateMachine.addRoleChangeListener(listener);
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
        Path base = AppHome.appHome().resolve(raftClusterDiscovery.getRaftClusterName() + "-DATA");
        String dataPath = (AppHome.isMacOs() ? base.resolve(raftCurrentMember) : base).toString();
        FileUtils.forceMkdir(new File(dataPath));
        PeerId selfPeer = JRaftUtils.getPeerId(raftCurrentMember);
        Configuration conf = JRaftUtils.getConfiguration(raftMemberCluster);
        NodeOptions nodeOptions = new NodeOptions();
        nodeOptions.setFsm(jraftExchangeStateMachine);
        nodeOptions.setLogUri(dataPath + File.separator + "log");
        nodeOptions.setSnapshotUri(dataPath + File.separator + "snapshot");
        nodeOptions.setRaftMetaUri(dataPath + File.separator + "meta");
        RaftOptions raftOptions = new RaftOptions();
        // disruptor + apply batching：8M slots ≈ 1G RAM @ 128B/slot；撮合 5MT/s 留余量
        raftOptions.setDisruptorBufferSize(8 * 1024 * 1024);
        raftOptions.setApplyBatch(128); // 逻辑聚合，默认 32
        raftOptions.setMaxAppendBufferSize(256 * 1024); // 物理聚合，配套 applyBatch
        // 单 RPC 帧：1MB body / 8K entries / 2MB frame（默认 128KB / 1024 / 1MB）→ RPC 次数 ~8× 减少
        raftOptions.setMaxByteCountPerRpc(1024 * 1024);
        raftOptions.setMaxEntriesSize(8192);
        raftOptions.setMaxBodySize(2 * 1024 * 1024);
        // pipelining：inflight 256 → 2048
        raftOptions.setReplicatorPipeline(true);
        raftOptions.setMaxReplicatorInflightMsgs(2048);
        raftOptions.setReadOnlyOptions(ReadOnlyOption.ReadOnlyLeaseBased);
        // 多副本广播确认，无需同步落盘
        raftOptions.setSync(false);
        raftOptions.setOpenStatistics(false);
        raftOptions.setEventBusMode(EventBusMode.MPSC);
        nodeOptions.setRaftOptions(raftOptions);
        nodeOptions.setInitialConf(conf);
        nodeOptions.setSnapshotIntervalSecs(SNAPSHOT_INTERVAL_SECS);
        nodeOptions.setSnapshotLogIndexMargin(SNAPSHOT_LOG_INDEX_MARGIN);
        nodeOptions.setDisableCli(true);
        nodeOptions.setRaftRpcThreadPoolSize(Math.max(Utils.cpus() * 8, 32));
        raftGroupService = new RaftGroupService(raftClusterDiscovery.getRaftClusterName(), selfPeer, nodeOptions);
        Node node = raftGroupService.start();
        node.resetPeers(conf);
        RaftExchangeMetrics.Raft.register(node::isLeader, node::getLastCommittedIndex,
            jraftExchangeStateMachine::lastAppliedIndex);
        LOGGER.info("SOFA-JRaft Node started on {}", selfPeer);
    }

    @Override
    public void doStop() throws Exception {
        if (batchCommandHelper != null) {
            batchCommandHelper.close();
        }
        if (raftGroupService != null) {
            raftGroupService.shutdown();
            raftGroupService.join();
        }
    }

    @Override
    public void requestConsensus(byte[] cmdBytes, BiConsumer<RaftResponse, Throwable> callback) {
        ByteBuffer data = ByteBuffer.wrap(cmdBytes);
        GeneratedMessageV3 msg;
        try {
            msg = SerializeHelper.deserializeWithType(data);
        } catch (Exception e) {
            LOGGER.warn("Failed to pre-parse for leader fast path", e);
            callback.accept(null, e);
            return;
        }
        if (batchCommandHelper == null || !jraftExchangeStateMachine.canBatch(msg)) {
            raftGroupService.getRaftNode().apply(new Task(data, new SingleClosure(callback, msg)));
            return;
        }
        try {
            if (!batchCommandHelper.submit(new PendingCmd(cmdBytes, msg, callback))) {
                callback.accept(null, new IllegalStateException("raft batch helper is shutting down"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            callback.accept(null, e);
        }
    }

    private void publishEnvelopeToRaft(BatchClosure batchClosure) {
        byte[] entry = BatchCommandHelper.packBatchEntry(batchClosure);
        raftGroupService.getRaftNode().apply(new Task(ByteBuffer.wrap(entry), batchClosure));
    }

    @Override
    public List<RaftNode> listNodes() {
        List<Node> nodes = NodeManager.getInstance().getNodesByGroupId(raftClusterDiscovery.getRaftClusterName());
        if (nodes.isEmpty()) {
            LOGGER.warn("No raft node found for group: {}", raftClusterDiscovery.getRaftClusterName());
            return Collections.emptyList();
        }
        Node node = nodes.get(0);
        PeerId leaderId = node.getLeaderId();
        List<PeerId> peers = node.getOptions().getInitialConf().getPeers();
        List<RaftNode> raftNodes = new ArrayList<>(peers.size());
        for (PeerId peerId : peers) {
            raftNodes.add(new RaftNode(peerId.getIp(), getGrpcPort(peerId.getEndpoint().toString()),
                peerId.equals(leaderId) ? RaftNode.NodeType.LEADER : RaftNode.NodeType.FOLLOWER));
        }
        return raftNodes;
    }

    @Override
    public RaftNode leaderNode() {
        if (raftGroupService == null) {
            return null;
        }
        PeerId leaderId = raftGroupService.getRaftNode().getLeaderId();
        if (leaderId == null) {
            return null;
        }
        return new RaftNode(leaderId.getIp(), getGrpcPort(leaderId.getEndpoint().toString()), RaftNode.NodeType.LEADER);
    }

    @Override
    public CompletableFuture<Void> readConsistencyBarrier() {
        if (raftGroupService == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("RaftClusterContainer not started"));
        }
        long startNanos = System.nanoTime();
        CompletableFuture<Void> future = new CompletableFuture<>();
        raftGroupService.getRaftNode().readIndex(EMPTY_REQUEST_CONTEXT, new ReadIndexClosure() {
            @Override
            public void run(Status status, long index, byte[] reqCtx) {
                if (status.isOk()) {
                    RaftExchangeMetrics.ReadBarrier.recordSuccess(startNanos);
                    future.complete(null);
                } else {
                    RaftExchangeMetrics.ReadBarrier.recordFailure(startNanos);
                    future.completeExceptionally(new RuntimeException(status.getErrorMsg()));
                }
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<AdminResult> triggerSnapshot() {
        if (raftGroupService == null) {
            return CompletableFuture.completedFuture(AdminResult.error("RaftClusterContainer not started"));
        }
        CompletableFuture<AdminResult> future = new CompletableFuture<>();
        raftGroupService.getRaftNode().snapshot(status -> future.complete(toAdminResult(status)));
        return future;
    }

    @Override
    public AdminResult stepDownLeadership() {
        if (raftGroupService == null) {
            return AdminResult.error("RaftClusterContainer not started");
        }
        Node node = raftGroupService.getRaftNode();
        if (!node.isLeader()) {
            return AdminResult.ok();
        }
        PeerId self = node.getNodeId().getPeerId();
        PeerId target = node.getOptions().getInitialConf().getPeers().stream().filter(peer -> !peer.equals(self))
            .findFirst().orElse(null);
        if (target == null) {
            return AdminResult.error("no other peers available for leadership transfer");
        }
        long startNanos = System.nanoTime();
        Status status = node.transferLeadershipTo(target);
        if (status.isOk()) {
            RaftExchangeMetrics.Raft.recordStepDownSuccess(startNanos);
        } else {
            RaftExchangeMetrics.Raft.recordStepDownFailure(startNanos);
        }
        return toAdminResult(status);
    }

    private int getGrpcPort(String raftPeer) {
        String peer = raftClusterDiscovery.raftToGrpcPeerMap().get(raftPeer);
        return peer != null ? Integer.parseInt(peer.split(":")[1]) : -1;
    }

    private static AdminResult toAdminResult(Status status) {
        return status.isOk() ? AdminResult.ok() : AdminResult.error(status.getErrorMsg());
    }
}
