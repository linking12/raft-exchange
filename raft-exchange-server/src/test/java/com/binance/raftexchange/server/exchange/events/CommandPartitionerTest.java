package com.binance.raftexchange.server.exchange.events;

import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link IEventsHandlerByKafka.CommandPartitioner} 的两条路由分支： 1. uid == -1 (IGNORE_UID) 走 round-robin 2. 其余 uid 走
 * floorMod(uid, numPartitions) — 保证同一 uid 永远进同一分区
 */
class CommandPartitionerTest {

    private static final String TOPIC = "test-topic";
    private static final int NUM_PARTITIONS = 4;

    private IEventsHandlerByKafka.CommandPartitioner partitioner;
    private Cluster cluster;

    @BeforeEach
    void setUp() {
        partitioner = new IEventsHandlerByKafka.CommandPartitioner();
        cluster = mock(Cluster.class);
        Node dummy = mock(Node.class);
        when(cluster.partitionsForTopic(TOPIC))
            .thenReturn(List.of(new PartitionInfo(TOPIC, 0, dummy, new Node[0], new Node[0]),
                new PartitionInfo(TOPIC, 1, dummy, new Node[0], new Node[0]),
                new PartitionInfo(TOPIC, 2, dummy, new Node[0], new Node[0]),
                new PartitionInfo(TOPIC, 3, dummy, new Node[0], new Node[0])));
    }

    @Test
    void knownUid_isFloorModNumPartitions() {
        // 8 % 4 == 0
        assertEquals(0, partitioner.partition(TOPIC, 8L, null, null, null, cluster));
        // 5 % 4 == 1
        assertEquals(1, partitioner.partition(TOPIC, 5L, null, null, null, cluster));
        // 同一 uid 必须稳定 → 同一分区
        assertEquals(1, partitioner.partition(TOPIC, 5L, null, null, null, cluster));
    }

    @Test
    void ignoreUid_doesRoundRobin() {
        int p0 = partitioner.partition(TOPIC, -1L, null, null, null, cluster);
        int p1 = partitioner.partition(TOPIC, -1L, null, null, null, cluster);
        int p2 = partitioner.partition(TOPIC, -1L, null, null, null, cluster);
        int p3 = partitioner.partition(TOPIC, -1L, null, null, null, cluster);
        int p4 = partitioner.partition(TOPIC, -1L, null, null, null, cluster);

        assertEquals(0, p0);
        assertEquals(1, p1);
        assertEquals(2, p2);
        assertEquals(3, p3);
        assertEquals(0, p4, "round-robin 必须 wrap 回 0");
    }

    @Test
    void ignoreUid_andKnownUid_useIndependentCounters() {
        // 已知 uid 不应消耗 round-robin counter
        partitioner.partition(TOPIC, 100L, null, null, null, cluster);
        partitioner.partition(TOPIC, 200L, null, null, null, cluster);

        int ignoreFirst = partitioner.partition(TOPIC, -1L, null, null, null, cluster);
        assertEquals(0, ignoreFirst, "IGNORE_UID 的 counter 不该被已知 uid 推进");
    }

    @Test
    void negativeUid_neverGoesNegative() {
        // Math.floorMod 保证非负；如果用 % 操作，-7 % 4 = -3 会爆 ArrayIndexOutOfBounds
        int part = partitioner.partition(TOPIC, -7L, null, null, null, cluster);
        assertNotEquals(-1, part);
        assertEquals(1, part, "floorMod(-7,4) == 1");
    }
}
