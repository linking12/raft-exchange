package com.binance.raftexchange.server.raft.jraft;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.commons.io.FileUtils;
import org.apache.kafka.clients.producer.ProducerRecord;

import com.binance.raftexchange.server.exchange.ExchangeRuntime;
import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka;
import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka.TopicGroup;
import com.binance.raftexchange.server.grpc.GrpcServerContainer;
import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftClusterDiscovery;
import com.binance.raftexchange.server.util.AppHome;

/**
 * JVM 内拉起单节点 jraft + gRPC + 内存 Kafka mock 的 harness，专给 e2e 测试用。 全部组件构造期注入，没有跨实例的静态状态，所以可以多次 start 而不污染 JVM。 close()
 * 会停容器并清临时目录。
 */
public final class SingleNodeRaftHarness implements AutoCloseable {

    public static final TopicGroup FUND_GROUP = TopicGroup.FUND;

    private final int grpcPort;
    private final int raftPort;
    private final File dataDir;
    private final RaftClusterDiscovery discovery;
    private final Map<TopicGroup, List<ProducerRecord<Long, byte[]>>> capturedByGroup;

    private ExchangeRuntime exchangeRuntime;
    private RaftClusterContainer raftContainer;
    private GrpcServerContainer grpcContainer;

    public static SingleNodeRaftHarness start() throws Exception {
        return new SingleNodeRaftHarness();
    }

    private SingleNodeRaftHarness() throws Exception {
        this.raftPort = freePort();
        this.grpcPort = freePort();
        String selfPeer = "127.0.0.1:" + raftPort;

        System.setProperty("raft.port", String.valueOf(raftPort));
        System.setProperty("grpc.port", String.valueOf(grpcPort));
        System.setProperty("raftexchange.cluster.startupNodes", "1");

        this.capturedByGroup = new EnumMap<>(TopicGroup.class);
        for (TopicGroup group : TopicGroup.values()) {
            capturedByGroup.put(group, new CopyOnWriteArrayList<>());
        }

        this.discovery = mock(RaftClusterDiscovery.class);
        when(discovery.getRaftClusterName()).thenReturn("e2e-test-cluster-" + System.nanoTime());
        when(discovery.raftCurrentMember()).thenReturn(selfPeer);
        when(discovery.raftMemberCluster()).thenReturn(selfPeer);
        when(discovery.raftToGrpcPeerMap()).thenReturn(Map.of(selfPeer, "127.0.0.1:" + grpcPort));

        this.dataDir = new File(System.getProperty("user.dir"), discovery.getRaftClusterName() + "-DATA");

        startComponents();
    }

    /**
     * 停止当前节点并从同一数据目录重新启动，jraft 启动时会自动触发 onSnapshotLoad 并重放快照后的日志。 用于 E2E 测试中验证 snapshot load 路径。
     */
    public void restart() throws Exception {
        try {
            grpcContainer.doStop();
        } catch (Exception ignored) {
        }
        try {
            raftContainer.doStop();
        } catch (Exception ignored) {
        }
        try {
            exchangeRuntime.exchangeCore().shutdown();
        } catch (Exception ignored) {
        }
        Thread.sleep(1500); // 等待端口释放
        startComponents();
    }

    private void startComponents() throws Exception {
        // 构造期依赖链：handler → exchangeRuntime → raftContainer → grpc
        IEventsHandlerByKafka handler = KafkaMockSupport.buildCapturingHandler(capturedByGroup);

        this.exchangeRuntime = new ExchangeRuntime(handler);
        this.raftContainer = new JraftClusterContainer(discovery, exchangeRuntime);
        raftContainer.addRoleChangeListener(handler::onRoleChange);
        raftContainer.doStart();

        this.grpcContainer = new GrpcServerContainer();
        grpcContainer.setRaftClusterContainer(raftContainer);
        grpcContainer.doStart();

        long deadline = System.currentTimeMillis() + 15_000;
        while (raftContainer.leaderNode() == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        if (raftContainer.leaderNode() == null) {
            close();
            throw new IllegalStateException("Raft leader not elected within 15s");
        }
    }

    public int grpcPort() {
        return grpcPort;
    }

    public CompletableFuture<com.alipay.sofa.jraft.Status> triggerSnapshot() throws Exception {
        Field f = JraftClusterContainer.class.getDeclaredField("raftGroupService");
        f.setAccessible(true);
        com.alipay.sofa.jraft.RaftGroupService rgs = (com.alipay.sofa.jraft.RaftGroupService)f.get(raftContainer);
        CompletableFuture<com.alipay.sofa.jraft.Status> future = new CompletableFuture<>();
        rgs.getRaftNode().snapshot(future::complete);
        return future;
    }

    public File snapshotDir() {
        String selfPeer = "127.0.0.1:" + raftPort;
        return AppHome.isMacOs() ? new File(dataDir, selfPeer + File.separator + "snapshot")
            : new File(dataDir, "snapshot");
    }

    public List<ProducerRecord<Long, byte[]>> fundEventRecords() {
        return capturedByGroup.get(FUND_GROUP);
    }

    public List<ProducerRecord<Long, byte[]>> spotEventRecords() {
        return capturedByGroup.get(TopicGroup.SPOT);
    }

    public List<ProducerRecord<Long, byte[]>> perpEventRecords() {
        return capturedByGroup.get(TopicGroup.PERP);
    }

    @Override
    public void close() throws Exception {
        try {
            if (grpcContainer != null)
                grpcContainer.doStop();
        } catch (Exception ignored) {
        }
        try {
            if (raftContainer != null)
                raftContainer.doStop();
        } catch (Exception ignored) {
        }
        try {
            if (exchangeRuntime != null)
                exchangeRuntime.exchangeCore().shutdown();
        } catch (Exception ignored) {
        }
        if (dataDir != null && dataDir.exists()) {
            FileUtils.deleteDirectory(dataDir);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
