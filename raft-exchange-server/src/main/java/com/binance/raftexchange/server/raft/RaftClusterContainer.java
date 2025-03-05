package com.binance.raftexchange.server.raft;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.JRaftUtils;
import com.alipay.sofa.jraft.Node;
import com.alipay.sofa.jraft.NodeManager;
import com.alipay.sofa.jraft.RaftGroupService;
import com.alipay.sofa.jraft.RouteTable;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.conf.Configuration;
import com.alipay.sofa.jraft.entity.PeerId;
import com.alipay.sofa.jraft.entity.Task;
import com.alipay.sofa.jraft.option.NodeOptions;
import com.alipay.sofa.jraft.option.RaftOptions;
import com.alipay.sofa.jraft.util.Utils;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        String raftMemberCluster = raftClusterDiscovery.raftMemberCluster();
        while (raftMemberCluster == null) {
            TimeUnit.SECONDS.sleep(5);
            LOGGER.info("Starting raft: {}", raftCurrentMember);
            raftMemberCluster = raftClusterDiscovery.raftMemberCluster();
        }

        // Start JRaft
        String dataPath = System.getProperty("user.dir") + File.separator + raftClusterName + "_data";
        FileUtils.forceMkdir(new File(dataPath));

        PeerId selfPeer = JRaftUtils.getPeerId(raftCurrentMember);
        Configuration conf = JRaftUtils.getConfiguration(raftMemberCluster);

        NodeOptions nodeOptions = new NodeOptions();
        nodeOptions.setFsm(new ExchangeStateMachine());
        //@see DefaultJRaftServiceFactory，log存rocksdb，snapshot和meta存文件
        nodeOptions.setLogUri(dataPath + File.separator + "log");
        nodeOptions.setSnapshotUri(dataPath + File.separator + "snapshot");
        nodeOptions.setRaftMetaUri(dataPath + File.separator + "meta");

        nodeOptions.setRaftOptions(new RaftOptions());
        nodeOptions.setInitialConf(conf);

        nodeOptions.setDisableCli(false);
        nodeOptions.setCliRpcThreadPoolSize(Utils.cpus());// 同默认值一样，处理cli请求(leader查询、管理)
        nodeOptions.setRaftRpcThreadPoolSize(Math.max(Utils.cpus() << 3, 32));// 默认值是6倍cpu，处理raft请求(日志复制、心跳检测、选举)

        raftGroupService = new RaftGroupService(raftClusterName, selfPeer, nodeOptions);
        raftGroupService.start();

        LOGGER.info("SOFA-JRaft Node started on {}", selfPeer);

        NodeManager.getInstance().addAddress(selfPeer.getEndpoint());
        //注册RouteTable
        RouteTable.getInstance().updateConfiguration(raftClusterName, conf);

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



    //todo 不再throw exception，调用方注意调整下
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

        return null;
    }

    public RaftNode leaderNode() {
//        RouteTable.getInstance().refreshLeader()
//        RouteTable.getInstance().selectLeader();
        return null;
    }

    static class ReturnableClosure implements Closure {
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
