package com.binance.raftexchange.server.raft.aeron;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.File;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.io.FileUtils;
import org.apache.kafka.clients.producer.ProducerRecord;

import com.binance.raftexchange.server.exchange.ExchangeRuntime;
import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka;
import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka.TopicGroup;
import com.binance.raftexchange.server.exchange.events.KafkaEventSink;
import com.binance.raftexchange.server.raft.RaftClusterDiscovery;
import com.binance.raftexchange.server.raft.RaftResponse;
import com.binance.raftexchange.server.util.AppHome;

/**
 * In-process 多节点 Aeron 集群 harness。N 个 AeronClusterContainer 在同 JVM 起。 每节点占 6 个连续 UDP 端口（base..base+5）； 节点间 clusterName
 * 不同（dataDir 隔离），但共享同一个 clusterMembers 字符串组成 Aeron cluster。 close() 关全部并清各 dataDir。
 */
public final class AeronMultiNodeHarness implements AutoCloseable {

    public static final int DEFAULT_CLUSTER_SIZE = 3;
    private static final int AERON_PORT_SPAN = 6;
    private static final int LEADER_TIMEOUT_MS = 30_000;

    private final List<NodeHolder> nodes;
    private final List<File> dataDirs;

    public static AeronMultiNodeHarness startCluster(int size) throws Exception {
        return new AeronMultiNodeHarness(size);
    }

    private AeronMultiNodeHarness(int size) throws Exception {
        // 一次性占好 size 段连续 UDP 端口
        int[] basePorts = new int[size];
        for (int i = 0; i < size; i++) {
            basePorts[i] = freeContiguousUdpBasePort(AERON_PORT_SPAN);
        }
        String clusterMembers =
            IntStream.range(0, size).mapToObj(i -> "127.0.0.1:" + basePorts[i]).collect(Collectors.joining(","));

        this.nodes = new ArrayList<>(size);
        this.dataDirs = new ArrayList<>(size);

        // 先构造好全部 container（不 start）
        List<AeronClusterContainer> toStart = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String selfPeer = "127.0.0.1:" + basePorts[i];
            String clusterName = "aeron-multi-test-node" + i + "-" + System.nanoTime();

            Map<TopicGroup, List<ProducerRecord<Long, byte[]>>> captured = new EnumMap<>(TopicGroup.class);
            for (TopicGroup g : TopicGroup.values()) {
                captured.put(g, new CopyOnWriteArrayList<>());
            }
            IEventsHandlerByKafka handler = buildCapturingHandler(captured);
            ExchangeRuntime runtime = new ExchangeRuntime(handler);

            RaftClusterDiscovery discovery = mock(RaftClusterDiscovery.class);
            when(discovery.getRaftClusterName()).thenReturn(clusterName);
            when(discovery.raftCurrentMember()).thenReturn(selfPeer);
            when(discovery.raftMemberCluster()).thenReturn(clusterMembers);
            when(discovery.raftToGrpcPeerMap()).thenReturn(Map.of(selfPeer, "127.0.0.1:0"));

            AeronClusterContainer container = new AeronClusterContainer(discovery, runtime);
            container.addRoleChangeListener(handler::onRoleChange);

            dataDirs.add(new File(AppHome.get().toFile(), clusterName + "-DATA"));
            toStart.add(container);
            nodes.add(new NodeHolder(i, selfPeer, container, runtime, captured));
        }

        // 并行 doStart：每个 AeronCluster.connect 都要等 cluster 达 quorum 才返回；串行会死锁
        try {
            List<Thread> starters = new ArrayList<>(size);
            List<Throwable> errors = new CopyOnWriteArrayList<>();
            for (int i = 0; i < size; i++) {
                final AeronClusterContainer c = toStart.get(i);
                Thread t = new Thread(() -> {
                    try {
                        c.doStart();
                    } catch (Throwable th) {
                        errors.add(th);
                    }
                }, "aeron-harness-start-" + i);
                starters.add(t);
                t.start();
            }
            for (Thread t : starters) {
                t.join(LEADER_TIMEOUT_MS);
            }
            if (!errors.isEmpty()) {
                throw new IllegalStateException("doStart failed: " + errors.get(0).getMessage(), errors.get(0));
            }
            awaitLeaderElected(LEADER_TIMEOUT_MS);
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    public int clusterSize() {
        return nodes.size();
    }

    public AeronClusterContainer container(int idx) {
        return nodes.get(idx).container;
    }

    public List<ProducerRecord<Long, byte[]>> fundEventRecords(int idx) {
        return nodes.get(idx).capturedByGroup.get(TopicGroup.FUND);
    }

    /** 返回当前 leader 的 0-based 下标；找不到返 -1。 */
    public int currentLeaderIndex() {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).container.isLeader()) {
                return i;
            }
        }
        return -1;
    }

    /** 同步提交一条 log，等待 callback。 */
    public RaftResponse submitAndAwait(int viaNodeIdx, byte[] log, long timeoutMs) throws Exception {
        CompletableFuture<RaftResponse> f = new CompletableFuture<>();
        BiConsumer<RaftResponse, Throwable> cb = (resp, err) -> {
            if (err != null)
                f.completeExceptionally(err);
            else
                f.complete(resp);
        };
        nodes.get(viaNodeIdx).container.requestConsensus(log, cb);
        return f.get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        for (NodeHolder n : nodes) {
            try {
                n.container.doStop();
            } catch (Exception ignored) {
            }
            try {
                n.runtime.exchangeCore().shutdown();
            } catch (Exception ignored) {
            }
        }
        // Aeron MediaDriver close 不同步释放底层 UDP socket，紧跟着下一个 test 的端口探测可能撞 Address already in use
        try {
            Thread.sleep(2_000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        for (File d : dataDirs) {
            try {
                if (d.exists())
                    FileUtils.deleteDirectory(d);
            } catch (Exception ignored) {
            }
        }
    }

    // ---- internals ----

    private void awaitLeaderElected(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int leaders = 0;
            for (NodeHolder n : nodes) {
                if (n.container.isLeader())
                    leaders++;
            }
            if (leaders == 1)
                return;
            if (leaders > 1) {
                throw new IllegalStateException("split-brain: " + leaders + " leaders elected");
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("no leader elected within " + timeoutMs + "ms");
    }

    private static IEventsHandlerByKafka
        buildCapturingHandler(Map<TopicGroup, List<ProducerRecord<Long, byte[]>>> captureSink) {
        Map<TopicGroup, String> topics = new EnumMap<>(TopicGroup.class);
        for (TopicGroup g : TopicGroup.values()) {
            topics.put(g, "test-" + g.name().toLowerCase());
        }
        KafkaEventSink sink =
            (group, key, payload) -> captureSink.get(group).add(new ProducerRecord<>(topics.get(group), key, payload));
        return new IEventsHandlerByKafka(sink);
    }

    private static int freeContiguousUdpBasePort(int span) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            int candidate;
            try (DatagramSocket probe = new DatagramSocket(0)) {
                candidate = probe.getLocalPort();
            }
            List<DatagramSocket> holds = new ArrayList<>(span);
            boolean ok = true;
            try {
                for (int i = 0; i < span; i++) {
                    try {
                        holds.add(new DatagramSocket(candidate + i));
                    } catch (Exception e) {
                        ok = false;
                        break;
                    }
                }
                if (ok)
                    return candidate;
            } finally {
                for (DatagramSocket s : holds)
                    s.close();
            }
        }
        throw new IllegalStateException("no contiguous UDP port range of " + span + " available");
    }

    private static final class NodeHolder {
        final int idx;
        final String peer;
        final AeronClusterContainer container;
        final ExchangeRuntime runtime;
        final Map<TopicGroup, List<ProducerRecord<Long, byte[]>>> capturedByGroup;

        NodeHolder(int idx, String peer, AeronClusterContainer container, ExchangeRuntime runtime,
            Map<TopicGroup, List<ProducerRecord<Long, byte[]>>> capturedByGroup) {
            this.idx = idx;
            this.peer = peer;
            this.container = container;
            this.runtime = runtime;
            this.capturedByGroup = capturedByGroup;
        }
    }
}
