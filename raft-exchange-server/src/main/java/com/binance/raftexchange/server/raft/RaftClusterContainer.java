package com.binance.raftexchange.server.raft;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
        int applyBatch = 128;
        nodeOptions.setFsm(new ExchangeStateMachine(applyBatch));
        nodeOptions.setLogUri(dataPath + File.separator + "log");
        nodeOptions.setSnapshotUri(dataPath + File.separator + "snapshot");
        nodeOptions.setRaftMetaUri(dataPath + File.separator + "meta");
        RaftOptions raftOptions = new RaftOptions();
        raftOptions.setDisruptorBufferSize(8 * 1024 * 1024); // 撮合5MT/s，buffer给到8M条，每条log(用户数据[ApiCommand]+元数据[idx term
                                                             // type])大概是128字节，大约1G内存
        raftOptions.setReadOnlyOptions(ReadOnlyOption.ReadOnlyLeaseBased);
        raftOptions.setApplyBatch(applyBatch); // 逻辑聚合，默认32
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

    public CompletableFuture<RaftResponse> requestConsensus(byte[] log) {
        CompletableFuture<RaftResponse> future = new CompletableFuture<>();
        raftGroupService.getRaftNode().apply(new Task(ByteBuffer.wrap(log), new ReturnableClosure(future)));
        return future;
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

    public static class ReturnableClosure implements Closure {
        private final CompletableFuture<RaftResponse> future;
        private final long submitTime = System.nanoTime();

        public ReturnableClosure(CompletableFuture<RaftResponse> future) {
            this.future = future;
        }

        public void setResult(long beginTime, CompletableFuture<Supplier<byte[]>> result) {
            result.whenComplete((supplier, ex) -> {
                if (ex == null) {
                    long now = System.nanoTime();
                    future.complete(new RaftResponse(supplier, now - submitTime, now - beginTime));
                } else {
                    future.completeExceptionally(ex);
                }
            });
        }

        @Override
        public void run(Status status) {
            if (!status.isOk()) {
                future.completeExceptionally(new RuntimeException(status.getErrorMsg()));
            }
        }
    }
}
