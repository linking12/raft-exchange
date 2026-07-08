package com.binance.raftexchange.server.exchange.events;

import com.binance.raftexchange.server.exchange.events.IEventsHandlerByKafka.TopicGroup;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link KafkaEventQueue} records success/failure/latency metrics after the sender thread delivers (or
 * fails to deliver) an event.
 */
class IEventsHandlerByKafkaMetricsTest {

    @BeforeAll
    static void registerSimpleMeter() {
        Metrics.globalRegistry.add(new SimpleMeterRegistry());
        if (System.getProperty("app.home") == null) {
            System.setProperty("app.home", System.getProperty("user.dir"));
        }
    }

    private static final TopicGroup G = TopicGroup.SPOT;

    private Map<TopicGroup, KafkaProducer<Long, byte[]>> producers;
    private Map<TopicGroup, String> topics;
    private KafkaEventQueue queue;
    private String clusterName;

    @BeforeEach
    void setUp() throws Exception {
        clusterName = "test-metrics-" + System.nanoTime();
        producers = new EnumMap<>(TopicGroup.class);
        topics = new EnumMap<>(TopicGroup.class);
        for (TopicGroup g : TopicGroup.values()) {
            @SuppressWarnings("unchecked")
            KafkaProducer<Long, byte[]> mock = mock(KafkaProducer.class);
            producers.put(g, mock);
            topics.put(g, "test-" + g.name().toLowerCase());
        }
        queue = new KafkaEventQueue(clusterName, producers, topics);
    }

    @AfterEach
    void tearDown() throws Exception {
        queue.close();
        FileUtils.deleteDirectory(new File(System.getProperty("user.dir"), clusterName + "-EVENT"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private double successCount(TopicGroup g) {
        return Metrics.globalRegistry.counter("raft.exchange.kafka.send", "topic_group", g.name(), "status", "success")
            .count();
    }

    private double failureCount(TopicGroup g) {
        return Metrics.globalRegistry.counter("raft.exchange.kafka.send", "topic_group", g.name(), "status", "failure")
            .count();
    }

    private long latencyRecorded(TopicGroup g) {
        return Metrics.globalRegistry.find("raft.exchange.kafka.send.latency").tag("topic_group", g.name()).timer()
            .count();
    }

    private static RecordMetadata fakeMeta() {
        return new RecordMetadata(new TopicPartition("t", 0), 0L, 0, 0L, 0, 0);
    }

    /** Busy-poll until condition is true or 2 s timeout. */
    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2_000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "Timed out waiting for condition");
            Thread.sleep(20);
        }
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void successfulSend_incrementsSuccessCounterAndRecordsLatency() throws Exception {
        when(producers.get(G).send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(fakeMeta()));

        double sBefore = successCount(G);
        long lBefore = latencyRecorded(G);

        queue.enqueue(G, 42L, new byte[] {1, 2});

        waitFor(() -> successCount(G) > sBefore);
        assertEquals(sBefore + 1, successCount(G), 0.001);
        assertEquals(lBefore + 1, latencyRecorded(G));
    }

    @Test
    void failedSend_thenSucceeds_incrementsBothCounters() throws Exception {
        AtomicBoolean firstCall = new AtomicBoolean(true);
        when(producers.get(G).send(any(ProducerRecord.class)))
            .thenAnswer((Answer<CompletableFuture<RecordMetadata>>)inv -> {
                if (firstCall.compareAndSet(true, false)) {
                    CompletableFuture<RecordMetadata> f = new CompletableFuture<>();
                    f.completeExceptionally(new RuntimeException("broker down"));
                    return f;
                }
                return CompletableFuture.completedFuture(fakeMeta());
            });

        double fBefore = failureCount(G);
        double sBefore = successCount(G);

        queue.enqueue(G, 42L, new byte[] {1});

        waitFor(() -> successCount(G) > sBefore);
        assertEquals(fBefore + 1, failureCount(G), 0.001);
        assertEquals(sBefore + 1, successCount(G), 0.001);
    }

    @Test
    void successCounterTaggedByGroup_doesNotBleedAcrossGroups() throws Exception {
        when(producers.get(TopicGroup.SPOT).send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(fakeMeta()));

        double sSpotBefore = successCount(TopicGroup.SPOT);
        double sFundBefore = successCount(TopicGroup.FUND);

        queue.enqueue(TopicGroup.SPOT, 1L, new byte[] {1});

        waitFor(() -> successCount(TopicGroup.SPOT) > sSpotBefore);
        assertEquals(sSpotBefore + 1, successCount(TopicGroup.SPOT), 0.001);
        assertEquals(sFundBefore, successCount(TopicGroup.FUND), 0.001,
            "FUND counter must not be touched by a SPOT enqueue");
    }

    @Test
    void multipleSuccessfulSends_allCounted() throws Exception {
        when(producers.get(G).send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture(fakeMeta()));

        double sBefore = successCount(G);
        int N = 5;
        for (int i = 0; i < N; i++) {
            queue.enqueue(G, (long)i, new byte[] {(byte)i});
        }

        waitFor(() -> successCount(G) >= sBefore + N);
        assertEquals(sBefore + N, successCount(G), 0.001);
    }

    @Test
    void singleMetricFamily_oneNamePerGroupPerStatus() {
        assertTrue(Metrics.globalRegistry.find("raft.exchange.kafka.send").tag("topic_group", "SPOT")
            .tag("status", "success").counters().size() >= 1,
            "raft.exchange.kafka.send{topic_group=SPOT,status=success} must have a meter");
    }
}
