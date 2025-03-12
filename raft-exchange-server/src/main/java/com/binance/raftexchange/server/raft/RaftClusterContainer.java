package com.binance.raftexchange.server.raft;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.JRaftUtils;
import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.NodeManager;
import com.alipay.sofa.jraft.RaftGroupService;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.entity.Task;
import com.alipay.sofa.jraft.option.NodeOptions;
import com.alipay.sofa.jraft.option.RaftOptions;
import com.alipay.sofa.jraft.util.Utils;

public class RaftClusterContainer {
    private static final Logger LOGGER = LoggerFactory.getLogger(RaftClusterContainer.class);
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
        FileUtils.forceMkdir(new File(dataPath));
        PeerId selfPeer = JRaftUtils.getPeerId(raftCurrentMember);
        Configuration conf = JRaftUtils.getConfiguration(raftMemberCluster);
        NodeOptions nodeOptions = new NodeOptions();
        nodeOptions.setFsm(new ExchangeStateMachine());
        nodeOptions.setLogUri(dataPath + File.separator + "log");
        nodeOptions.setSnapshotUri(dataPath + File.separator + "snapshot");
        nodeOptions.setRaftMetaUri(dataPath + File.separator + "meta");
        nodeOptions.setRaftOptions(new RaftOptions());
        nodeOptions.setInitialConf(conf);

        // test
        nodeOptions.setSnapshotIntervalSecs(30);

        nodeOptions.setDisableCli(true);
        nodeOptions.setRaftRpcThreadPoolSize(Math.max(Utils.cpus() << 3, 32));// 默认值是6倍cpu，处理raft请求(日志复制、心跳检测、选举)
        raftGroupService = new RaftGroupService(raftClusterName, selfPeer, nodeOptions);
        raftGroupService.start();
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

    public CompletableFuture<byte[]> requestConsensus(byte[] log) {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        raftGroupService.getRaftNode().apply(new Task(ByteBuffer.wrap(log), new ReturnableClosure(future)));
        return future;
    }

    public boolean isLeader() {
        Node raftNode = raftGroupService.getRaftNode();
        return raftNode != null && raftNode.isLeader();
    }

    public List<RaftNode> listNodes() {
        List<RaftNode> raftNodes = new ArrayList<>();
        for (Node node : NodeManager.getInstance().getNodesByGroupId(raftClusterName)) {
            PeerId peerId = node.getNodeId().getPeerId();
            raftNodes.add(new RaftNode(peerId.getIp(), getGrpcPort(peerId.getIp()), node.isLeader() ? RaftNode.NodeType.LEADER : RaftNode.NodeType.FOLLOWER));
        }
        return raftNodes;
    }

    private int getGrpcPort(String raftIp) {
        for (String grpcWorker : raftClusterDiscovery.raftGrpcWorkers()) {
            if (grpcWorker.startsWith(raftIp)) {
                return Integer.parseInt(grpcWorker.split(":")[1]);
            }
        }
        return -1;
    }

    @Nullable
    public RaftNode leaderNode() {
        Node raftNode = raftGroupService.getRaftNode();
        PeerId leaderId = raftNode.getLeaderId();
        if (leaderId == null) {
            return null;
        }
        return new RaftNode(leaderId.getIp(), getGrpcPort(leaderId.getIp()), RaftNode.NodeType.LEADER);
    }

    public static class ReturnableClosure implements Closure {
        private final CompletableFuture<byte[]> future;
        private byte[] result;

        public ReturnableClosure(CompletableFuture<byte[]> future) {
            this.future = future;
        }

        public void setResult(byte[] result) {
            this.result = result;
        }

        @Override
        public void run(Status status) {
            if (status.isOk()) {
                future.complete(result);
            } else {
                future.completeExceptionally(new RuntimeException(status.getErrorMsg()));
            }
        }
    }
}
