package com.binance.raftexchange.server.raft.aeron;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.raft.RaftResponse;

import io.aeron.Publication;
import io.aeron.cluster.client.AeronCluster;

/**
 * 转发本地请求进 cluster ingress，按 correlationId demux egress 回 callback。 消息帧：{@code [8 字节 correlationId | payload]}。SDK 自动路由
 * leader / 切主 redirect。
 */
final class AeronClusterClient implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(AeronClusterClient.class);
    private static final long SEND_DEADLINE_NANOS = 5_000_000_000L;
    private static final long POLL_IDLE_SLEEP_MS = 1L;
    private static final long KEEPALIVE_INTERVAL_NANOS = 1_000_000_000L;
    private static final long CONNECT_MESSAGE_TIMEOUT_NANOS = 30_000_000_000L;

    private final Path clusterDir;
    private final AeronClusterContainer.MemberSpec self;
    private final List<AeronClusterContainer.MemberSpec> members;

    private final ConcurrentHashMap<Long, PendingCallback> pending = new ConcurrentHashMap<>();
    private final AtomicLong correlationIdGen = new AtomicLong();

    private AeronCluster aeronCluster;
    private Thread egressPoller;

    AeronClusterClient(Path clusterDir, AeronClusterContainer.MemberSpec self,
        List<AeronClusterContainer.MemberSpec> members) {
        this.clusterDir = clusterDir;
        this.self = self;
        this.members = members;
    }

    void start() {
        String aeronDir = clusterDir.resolve("aeron").toFile().getAbsolutePath();

        String ingressEndpoints =
            members.stream().map(member -> member.memberId() + "=" + member.peer()).collect(Collectors.joining(","));

        aeronCluster = AeronCluster.connect(new AeronCluster.Context().aeronDirectoryName(aeronDir)
            .ingressChannel("aeron:udp?term-length=64k").ingressEndpoints(ingressEndpoints)
            .egressChannel("aeron:udp?endpoint=" + self.host() + ":0").messageTimeoutNs(CONNECT_MESSAGE_TIMEOUT_NANOS)
            .egressListener((sessionId, ts, buf, off, len, hdr) -> dispatchEgress(buf, off, len)));

        egressPoller = new Thread(this::pollEgressLoop, "aeron-egress-poller");
        egressPoller.setDaemon(true);
        egressPoller.start();
    }

    /** callback 保证恰好调用一次（成功 / 失败 / close）。 */
    void send(byte[] cmdBytes, BiConsumer<RaftResponse, Throwable> callback) {
        long correlationId = correlationIdGen.incrementAndGet();
        pending.put(correlationId, new PendingCallback(callback, System.nanoTime()));

        UnsafeBuffer framed = AeronFrame.Ingress.encode(correlationId, cmdBytes);
        long deadline = System.nanoTime() + SEND_DEADLINE_NANOS;
        while (true) {
            long offerResult = aeronCluster.offer(framed, 0, framed.capacity());
            if (offerResult >= 0)
                return;
            if (offerResult != Publication.BACK_PRESSURED && offerResult != Publication.ADMIN_ACTION) {
                failPending(correlationId, "aeron offer failed: " + offerResult);
                return;
            }
            if (System.nanoTime() > deadline) {
                failPending(correlationId, "aeron offer back-pressured > 5s");
                return;
            }
        }
    }

    /** 连接未就绪或调用失败返 -1。 */
    int leaderMemberId() {
        if (aeronCluster == null)
            return -1;
        try {
            return aeronCluster.leaderMemberId();
        } catch (Throwable t) {
            return -1;
        }
    }

    @Override
    public void close() {
        if (egressPoller != null) {
            egressPoller.interrupt();
            try {
                egressPoller.join(3_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        IllegalStateException closedErr = new IllegalStateException("AeronClusterClient closed");
        pending.forEach((correlationId, entry) -> safeAccept(correlationId, entry.callback, null, closedErr));
        pending.clear();
        if (aeronCluster != null)
            aeronCluster.close();
    }

    private void pollEgressLoop() {
        long nextKeepAlive = System.nanoTime() + KEEPALIVE_INTERVAL_NANOS;
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (aeronCluster.pollEgress() == 0)
                    Thread.sleep(POLL_IDLE_SLEEP_MS);
                if (System.nanoTime() >= nextKeepAlive) {
                    aeronCluster.sendKeepAlive();
                    nextKeepAlive = System.nanoTime() + KEEPALIVE_INTERVAL_NANOS;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                LOGGER.warn("egress poll error", t);
            }
        }
    }

    private void dispatchEgress(DirectBuffer buffer, int offset, int length) {
        if (length < AeronFrame.Egress.HEADER_LENGTH) {
            LOGGER.warn("egress msg too short: {}", length);
            return;
        }
        long correlationId = AeronFrame.Egress.correlationId(buffer, offset);
        PendingCallback entry = pending.remove(correlationId);
        if (entry == null) {
            LOGGER.warn("no pending callback for correlationId={}", correlationId);
            return;
        }
        long leaderLogPosition = AeronFrame.Egress.leaderLogPosition(buffer, offset);
        long exchangeLatencyNanos = AeronFrame.Egress.engineNanosTaken(buffer, offset);
        byte[] responseBytes = AeronFrame.Egress.payload(buffer, offset, length);
        long raftLatencyNanos = System.nanoTime() - entry.sendNanos;
        safeAccept(correlationId, entry.callback,
            new RaftResponse(() -> responseBytes, raftLatencyNanos, exchangeLatencyNanos, leaderLogPosition), null);
    }

    private void failPending(long correlationId, String reason) {
        PendingCallback entry = pending.remove(correlationId);
        if (entry != null)
            safeAccept(correlationId, entry.callback, null, new IllegalStateException(reason));
    }

    private record PendingCallback(BiConsumer<RaftResponse, Throwable> callback, long sendNanos) {}

    private static void safeAccept(long correlationId, BiConsumer<RaftResponse, Throwable> callback,
        RaftResponse response, Throwable err) {
        try {
            callback.accept(response, err);
        } catch (Throwable callbackErr) {
            LOGGER.error("callback failed for correlationId={}", correlationId, callbackErr);
        }
    }

}
