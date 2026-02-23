package com.binance.raftexchange.server.raft;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alipay.sofa.jraft.Closure;
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
import com.binance.platform.common.EnvUtil;
import com.binance.raftexchange.server.util.SerializeHelper;
import com.google.protobuf.GeneratedMessageV3;

import exchange.core2.core.common.cmd.OrderCommand;

public class RaftClusterContainer {
    private static final Logger LOGGER = LoggerFactory.getLogger(RaftClusterContainer.class);
    private static final byte[] EMPTY_REQUEST_CONTEXT = new byte[0];
    private final RaftClusterDiscovery raftClusterDiscovery;
    private final String raftClusterName;
    private RaftGroupService raftGroupService;

    public RaftClusterContainer(RaftClusterDiscovery raftClusterDiscovery) {
        this.raftClusterName = raftClusterDiscovery.getRaftClusterName();
        this.raftClusterDiscovery = raftClusterDiscovery;
    }

    public void doStart() throws Exception {
        String raftCurrentMember = raftClusterDiscovery.raftCurrentMember();
        String raftMemberCluster = null;
        do {
            raftMemberCluster = raftClusterDiscovery.raftMemberCluster();
            TimeUnit.SECONDS.sleep(5);
            LOGGER.info("Starting raft: {}", raftCurrentMember);
        } while (raftMemberCluster == null);
        String dataPath = System.getProperty("user.dir") + File.separator + raftClusterName + "-DATA";
        if (EnvUtil.isMacOs()) {
            dataPath += File.separator + raftCurrentMember;
        }
        FileUtils.forceMkdir(new File(dataPath));
        PeerId selfPeer = JRaftUtils.getPeerId(raftCurrentMember);
        Configuration conf = JRaftUtils.getConfiguration(raftMemberCluster);
        NodeOptions nodeOptions = new NodeOptions();
        nodeOptions.setFsm(new ExchangeStateMachine());
        nodeOptions.setLogUri(dataPath + File.separator + "log");
        nodeOptions.setSnapshotUri(dataPath + File.separator + "snapshot");
        nodeOptions.setRaftMetaUri(dataPath + File.separator + "meta");
        RaftOptions raftOptions = new RaftOptions();
        raftOptions.setDisruptorBufferSize(8 * 1024 * 1024); // 撮合5MT/s，buffer给到8M条，每条log(用户数据[ApiCommand]+元数据[idx term
                                                             // type])大概是128字节，大约1G内存
        raftOptions.setReadOnlyOptions(ReadOnlyOption.ReadOnlyLeaseBased);
        raftOptions.setApplyBatch(128); // 逻辑聚合，默认32
        raftOptions.setMaxAppendBufferSize(256 * 1024); // 物理聚合，默认256k。我们的指令比较小，128的ApplyBatch配256k比较好。
        raftOptions.setSync(false); // 多副本模式下，log已经广播确认了，不需要同步落盘。只有所有节点都断电才会丢失log。
        raftOptions.setOpenStatistics(false); // 关闭 rocksdb statistics，这个要用Kill -s SIGUSR2才能触发。
        raftOptions.setEventBusMode(EventBusMode.MPSC);
        nodeOptions.setRaftOptions(raftOptions);
        nodeOptions.setInitialConf(conf);
        int snapshotIntervalSecs = Integer.parseInt(System.getProperty("raft-exchange.snapshot.interval", "28800")); // 8h
        nodeOptions.setSnapshotIntervalSecs(snapshotIntervalSecs);
        nodeOptions.setDisableCli(true);
        nodeOptions.setRaftRpcThreadPoolSize(Math.max(Utils.cpus() << 3, 32));// 默认值是6倍cpu，处理raft请求(日志复制、心跳检测、选举)
        raftGroupService = new RaftGroupService(raftClusterName, selfPeer, nodeOptions);
        Node node = raftGroupService.start();
        node.resetPeers(conf); // 防止eks环境下，snapshot中的peers信息不一致，以当前节点列表为准
        LOGGER.info("SOFA-JRaft Node started on {}", selfPeer);
    }

    public void doStop() throws Exception {
        if (raftGroupService != null) {
            raftGroupService.shutdown();
            raftGroupService.join();
        }
    }

    public boolean started() {
        return raftGroupService != null && raftGroupService.isStarted();
    }

    public void requestConsensus(byte[] log, BiConsumer<RaftResponse, Throwable> callback) {
        ByteBuffer data = ByteBuffer.wrap(log);
        // Leader 提前反序列化, 传入 ReturnableClosure, onApply 时直接使用
        GeneratedMessageV3 msg;
        try {
            msg = SerializeHelper.deserializeWithType(data);
        } catch (Exception e) {
            LOGGER.warn("Failed to pre-parse for leader fast path", e);
            callback.accept(null, e);
            return;
        }
        raftGroupService.getRaftNode().apply(new Task(data, new ReturnableClosure(callback, msg)));
    }

    public List<RaftNode> listNodes() {
        // nodeManager只能拿到本机节点
        List<Node> nodes = NodeManager.getInstance().getNodesByGroupId(raftClusterName);
        if (nodes.isEmpty()) {
            LOGGER.warn("No raft node found for group: {}", raftClusterName);
            return Collections.emptyList();
        }
        Node node = nodes.get(0);
        PeerId leaderId = node.getLeaderId();
        // jraft配置禁用了cli，因此没有动态加入的节点，initConf中就是所有节点
        List<PeerId> peers = node.getOptions().getInitialConf().getPeers();
        List<RaftNode> raftNodes = new ArrayList<>(peers.size());
        for (PeerId peerId : peers) {
            raftNodes.add(new RaftNode(peerId.getIp(), getGrpcPort(peerId.getEndpoint().toString()), peerId.equals(leaderId) ? RaftNode.NodeType.LEADER : RaftNode.NodeType.FOLLOWER));
        }
        return raftNodes;
    }

    public CompletableFuture<Long> readFromQuorum() {
        CompletableFuture<Long> future = new CompletableFuture<>();
        raftGroupService.getRaftNode().readIndex(EMPTY_REQUEST_CONTEXT, new ReadIndexClosure() {
            @Override
            public void run(Status status, long index, byte[] reqCtx) {
                if (status.isOk()) {
                    future.complete(index);
                } else {
                    future.completeExceptionally(new RuntimeException(status.getErrorMsg()));
                }
            }
        });
        return future;
    }

    private int getGrpcPort(String raftPeer) {
        String grpcPeer = raftClusterDiscovery.raftToGrpcPeerMap().get(raftPeer);
        if (grpcPeer != null) {
            return Integer.parseInt(grpcPeer.split(":")[1]);
        }
        return -1;
    }

    public RaftNode leaderNode() {
        Node raftNode = raftGroupService.getRaftNode();
        PeerId leaderId = raftNode.getLeaderId();
        if (leaderId == null) {
            return null;
        }
        return new RaftNode(leaderId.getIp(), getGrpcPort(leaderId.getIp()), RaftNode.NodeType.LEADER);
    }

    /**
     * 携带 Supplier（延迟序列化）和预计算的 raft/exchange 耗时，传递到 gRPC 层统一记录 metric
     */
    public record RaftResponse(Supplier<byte[]> serializer, long raftLatencyNanos, long exchangeLatencyNanos) {}

    /**
     * Closure that doubles as a direct Consumer&lt;OrderCommand&gt; callback for the batch path,
     * eliminating all intermediate CompletableFuture allocations.
     * <p>
     * Batch path: registered directly in PromiseBuffer as Consumer&lt;OrderCommand&gt;,
     * ResultsHandler calls {@link #accept(OrderCommand)} which fires the BiConsumer callback.
     * <p>
     * Non-batch path (BINARY_DATA): uses {@link #accept} with CompletableFuture based wiring.
     */
    public static class ReturnableClosure implements Closure, Consumer<OrderCommand> {
        private final BiConsumer<RaftResponse, Throwable> callback;
        private final long submitTime;
        private final GeneratedMessageV3 message;
        private long applyTime; // set during onApply, before registering in PromiseBuffer

        public ReturnableClosure(BiConsumer<RaftResponse, Throwable> callback, GeneratedMessageV3 message) {
            this.callback = callback;
            this.submitTime = System.nanoTime();
            this.message = message;
        }

        public GeneratedMessageV3 message() { return message; }

        public void setApplyTime(long applyTime) { this.applyTime = applyTime; }

        /**
         * Batch path: called by ResultsHandler via PromiseBuffer — zero-alloc except the Supplier lambda.
         */
        @Override
        public void accept(OrderCommand cmd) {
            long now = System.nanoTime();
            callback.accept(new RaftResponse(() -> SerializeHelper.serializeToCommandResult(cmd), now - submitTime, now - applyTime), null);
        }

        /**
         * Non-batch path (BINARY_DATA): Future based wiring
         */
        public void accept(CompletableFuture<Supplier<byte[]>> result) {
            result.whenComplete((supplier, ex) -> {
                if (ex == null) {
                    long now = System.nanoTime();
                    callback.accept(new RaftResponse(supplier, now - submitTime, now - applyTime), null);
                } else {
                    callback.accept(null, ex);
                }
            });
        }

        /**
         * Error path in onApply: propagate exception directly to gRPC layer.
         */
        public void completeExceptionally(Throwable ex) {
            callback.accept(null, ex);
        }

        /**
         * Raft-level callback: only fires on consensus failure (e.g. leader change).
         */
        @Override
        public void run(Status status) {
            if (!status.isOk()) {
                callback.accept(null, new RuntimeException(status.getErrorMsg()));
            }
        }
    }
}
