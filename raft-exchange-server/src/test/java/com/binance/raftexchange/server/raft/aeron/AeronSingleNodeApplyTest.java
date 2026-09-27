package com.binance.raftexchange.server.raft.aeron;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

import com.binance.raftexchange.server.raft.RaftResponse;

import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;

/**
 * 启一个 in-process 单节点 Aeron cluster（MediaDriver+Archive+ConsensusModule+ServiceContainer）， 注入最小
 * RecordingClusteredService 收 payload 并 echo 回。验证： 1. AeronClusterClient.send → cluster ingress →
 * ClusteredService.onSessionMessage 整链路通 2. egress 携 correlationId + payload 回到 client callback 3. listNodes /
 * leaderNode 给出正确的单节点 LEADER 视图 不依赖 ExchangeRuntime / CommandRegistry，纯 raft 框架验证。
 */
class AeronSingleNodeApplyTest {

    private static final int PORT_RANGE = 6;
    private static final int LEADER_ELECTION_TIMEOUT_MS = 20_000;
    private static final int APPLY_TIMEOUT_MS = 10_000;

    @Test
    void singleNodeAeron_clientSend_invokesServiceAndEchoesResponse() throws Exception {
        int basePort = freeContiguousUdpBasePort(PORT_RANGE);
        String peer = "127.0.0.1:" + basePort;
        AeronClusterContainer.MemberSpec self = new AeronClusterContainer.MemberSpec(0, peer);
        List<AeronClusterContainer.MemberSpec> members = List.of(self);

        Path clusterDir = Files.createTempDirectory("aeron-single-node-apply-");
        RecordingClusteredService fsm = new RecordingClusteredService();

        AeronClusterServer server = new AeronClusterServer(clusterDir, self, members);
        server.setClusteredService(fsm);

        AeronClusterClient client = null;
        try {
            server.start();
            client = new AeronClusterClient(clusterDir, self, members);
            client.start();

            // 单节点起来后必须立刻能查到 leaderMemberId=0
            assertTrue(waitForLeader(client, LEADER_ELECTION_TIMEOUT_MS),
                LEADER_ELECTION_TIMEOUT_MS + "ms 内必须选出 leader");
            assertEquals(0, client.leaderMemberId());

            byte[][] payloads = {"hello".getBytes(), "world".getBytes(), "aeron".getBytes(),};

            CountDownLatch latch = new CountDownLatch(payloads.length);
            ConcurrentHashMap<Integer, byte[]> respByIdx = new ConcurrentHashMap<>();
            ConcurrentHashMap<Integer, Throwable> errByIdx = new ConcurrentHashMap<>();
            for (int i = 0; i < payloads.length; i++) {
                final int idx = i;
                client.send(payloads[i], (RaftResponse resp, Throwable err) -> {
                    if (err != null)
                        errByIdx.put(idx, err);
                    else
                        respByIdx.put(idx, resp.serializer().get());
                    latch.countDown();
                });
            }

            assertTrue(latch.await(APPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                APPLY_TIMEOUT_MS + "ms 内 3 条 send 的 callback 必须 fire；当前 fsm 收到 " + fsm.received.size() + " 条，已完成 "
                    + (payloads.length - latch.getCount()));

            assertTrue(errByIdx.isEmpty(), "callback 不应失败：" + errByIdx);
            assertEquals(payloads.length, fsm.received.size(), "ClusteredService 必须收到全部 payload");
            for (int i = 0; i < payloads.length; i++) {
                assertArrayEquals(payloads[i], fsm.received.get(i), "payload " + i + " 必须原样穿透到 FSM");
                assertArrayEquals(echoOf(payloads[i]), respByIdx.get(i), "echo 响应必须按 correlationId 路由回对应 callback");
            }
        } finally {
            if (client != null)
                client.close();
            server.close();
            FileUtils.deleteDirectory(clusterDir.toFile());
        }
    }

    // ---- helpers ----

    private static byte[] echoOf(byte[] payload) {
        byte[] out = new byte[payload.length + 1];
        out[0] = (byte)0xAA;
        System.arraycopy(payload, 0, out, 1, payload.length);
        return out;
    }

    private static boolean waitForLeader(AeronClusterClient client, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (client.leaderMemberId() >= 0)
                return true;
            Thread.sleep(100);
        }
        return false;
    }

    /**
     * 找 6 个连续可用的 UDP 端口起点。Aeron Cluster 一个节点占 basePort..basePort+5。
     */
    private static int freeContiguousUdpBasePort(int span) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            int candidate;
            try (DatagramSocket probe = new DatagramSocket(0)) {
                candidate = probe.getLocalPort();
            }
            // 把候选段全部探测一遍，全部 bind 成功才用
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

    /** 收到的 cmdBytes 原样存到 received 列表；响应 = {0xAA} + cmdBytes（echo with marker）。 */
    static final class RecordingClusteredService implements ClusteredService {
        final List<byte[]> received = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void onStart(Cluster cluster, Image snapshotImage) {}

        @Override
        public void onSessionOpen(ClientSession session, long timestamp) {}

        @Override
        public void onSessionClose(ClientSession session, long timestamp, CloseReason reason) {}

        @Override
        public void onSessionMessage(ClientSession session, long timestamp, DirectBuffer buffer, int offset, int length,
            Header header) {
            // 复制 framing：[8 字节 correlationId | cmdBytes]
            assertTrue(length >= Long.BYTES, "msg framing 必须含 correlationId");
            long correlationId = buffer.getLong(offset);
            byte[] cmdBytes = new byte[length - Long.BYTES];
            buffer.getBytes(offset + Long.BYTES, cmdBytes);
            received.add(cmdBytes);

            // egress 必须用当前帧格式 [8B cid][8B leaderLogPos][8B engineNanos][payload]，否则 client 按 HEADER_LENGTH(24) 判 too-short 丢弃
            byte[] resp = echoOf(cmdBytes);
            UnsafeBuffer out = AeronFrame.Egress.encode(correlationId, 0L, 0L, resp);

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (true) {
                long r = session.offer(out, 0, out.capacity());
                if (r >= 0)
                    return;
                if (r != Publication.BACK_PRESSURED && r != Publication.ADMIN_ACTION)
                    return;
                if (System.nanoTime() > deadline)
                    return;
            }
        }

        @Override
        public void onTimerEvent(long correlationId, long timestamp) {}

        @Override
        public void onTakeSnapshot(ExclusivePublication snapshotPublication) {}

        @Override
        public void onRoleChange(Cluster.Role newRole) {}

        @Override
        public void onTerminate(Cluster cluster) {}
    }
}
