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

import org.apache.commons.io.FileUtils;
import org.apache.kafka.clients.producer.ProducerRecord;

import com.binance.raftexchange.server.exchange.ExchangeRuntime;
import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka;
import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka.TopicGroup;
import com.binance.raftexchange.server.exchange.events.KafkaEventSink;
import com.binance.raftexchange.server.raft.RaftClusterDiscovery;
import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.server.raft.RaftResponse;
import com.binance.raftexchange.server.util.AppHome;

import java.util.function.BiConsumer;

/**
 * In-process Aeron 单节点 harness：MediaDriver + Archive + ConsensusModule + ServiceContainer + ExchangeRuntime 全启， mock
 * Eureka discovery 给本地 selfPeer。占 6 个连续 UDP 端口（basePort..basePort+5）。 close() 关全部组件并清 dataDir。
 */
public final class AeronSingleNodeHarness implements AutoCloseable {

    private static final int AERON_PORT_SPAN = 6;
    private static final int LEADER_TIMEOUT_MS = 20_000;

    private final String clusterName;
    private final int aeronBasePort;
    private final Map<TopicGroup, List<ProducerRecord<Long, byte[]>>> capturedByGroup;

    private final ExchangeRuntime exchangeRuntime;
    private final AeronClusterContainer container;

    public static AeronSingleNodeHarness start() throws Exception {
        return new AeronSingleNodeHarness();
    }

    private AeronSingleNodeHarness() throws Exception {
        this.aeronBasePort = freeContiguousUdpBasePort(AERON_PORT_SPAN);
        String selfPeer = "127.0.0.1:" + aeronBasePort;
        this.clusterName = "aeron-e2e-test-" + System.nanoTime();

        this.capturedByGroup = new EnumMap<>(TopicGroup.class);
        for (TopicGroup g : TopicGroup.values()) {
            capturedByGroup.put(g, new CopyOnWriteArrayList<>());
        }

        RaftClusterDiscovery discovery = mock(RaftClusterDiscovery.class);
        when(discovery.getRaftClusterName()).thenReturn(clusterName);
        when(discovery.raftCurrentMember()).thenReturn(selfPeer);
        when(discovery.raftMemberCluster()).thenReturn(selfPeer);
        when(discovery.raftToGrpcPeerMap()).thenReturn(Map.of(selfPeer, "127.0.0.1:0"));

        IEventsHandlerByKafka handler = buildCapturingHandler(capturedByGroup);
        this.exchangeRuntime = new ExchangeRuntime(handler);

        this.container = new AeronClusterContainer(discovery, exchangeRuntime);
        container.addRoleChangeListener(handler::onRoleChange);
        container.doStart();

        long deadline = System.currentTimeMillis() + LEADER_TIMEOUT_MS;
        while (!container.isLeader() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        if (!container.isLeader()) {
            close();
            throw new IllegalStateException(LEADER_TIMEOUT_MS + "ms 内未选出 leader");
        }
    }

    public AeronClusterContainer container() {
        return container;
    }

    public ExchangeRuntime exchangeRuntime() {
        return exchangeRuntime;
    }

    public RaftNode leaderNode() {
        return container.leaderNode();
    }

    public List<ProducerRecord<Long, byte[]>> fundEventRecords() {
        return capturedByGroup.get(TopicGroup.FUND);
    }

    public List<ProducerRecord<Long, byte[]>> spotEventRecords() {
        return capturedByGroup.get(TopicGroup.SPOT);
    }

    public List<ProducerRecord<Long, byte[]>> perpEventRecords() {
        return capturedByGroup.get(TopicGroup.PERP);
    }

    /** 同步提交一条 raft log，等待 callback。返回 RaftResponse 或抛错。 */
    public RaftResponse submitAndAwait(byte[] log, long timeoutMs) throws Exception {
        CompletableFuture<RaftResponse> f = new CompletableFuture<>();
        BiConsumer<RaftResponse, Throwable> cb = (resp, err) -> {
            if (err != null)
                f.completeExceptionally(err);
            else
                f.complete(resp);
        };
        container.requestConsensus(log, cb);
        return f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() throws Exception {
        try {
            container.doStop();
        } catch (Exception ignored) {
        }
        try {
            exchangeRuntime.exchangeCore().shutdown();
        } catch (Exception ignored) {
        }
        File appHomeData = new File(AppHome.get().toFile(), clusterName + "-DATA");
        if (appHomeData.exists()) {
            FileUtils.deleteDirectory(appHomeData);
        }
    }

    // ---- helpers ----

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
}
