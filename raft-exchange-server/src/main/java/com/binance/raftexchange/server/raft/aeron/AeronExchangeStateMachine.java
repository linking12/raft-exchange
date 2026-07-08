package com.binance.raftexchange.server.raft.aeron;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.status.AtomicCounter;
import org.agrona.concurrent.status.CountersReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.server.exchange.ExchangeCalls;
import com.binance.raftexchange.server.metrics.RaftExchangeMetrics;
import com.binance.raftexchange.server.raft.SnapshotHelper;
import com.binance.raftexchange.server.util.SerializeHelper;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;

import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.ApiRecoverState;
import exchange.core2.core.common.cmd.OrderCommand;
import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.Publication;
import io.aeron.cluster.ClusterControl;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;

/**
 * Aeron {@link ClusteredService}：ingress → exchange-core → egress；snapshot 写本地 .dat，缺时走 {@link SnapshotFetcher} 拉 peer。
 */
final class AeronExchangeStateMachine implements ClusteredService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AeronExchangeStateMachine.class);

    private static final int BATCH_CAPACITY = 128;
    private static final int PENDING_MAX = 1 << 16;
    private static final long DRAIN_TIMER_ID = -1L;
    private static final long DRAIN_INTERVAL_MS = 100L;
    private static final long RECOVER_TIMEOUT_SEC = 300L;
    private static final long MARKER_OFFER_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long MARKER_READ_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long SNAPSHOT_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(3600);
    private static final long SNAPSHOT_LOG_BYTES_MARGIN =
        Long.parseLong(System.getProperty("raftexchange.snapshot.logBytesMargin", "1073741824"));

    private final ExchangeCalls exchangeCalls;
    private final Path snapshotsRoot;
    private final SnapshotFetcher snapshotFetcher;
    private final SnapshotHelper snapshotHelper;

    private final CopyOnWriteArrayList<Consumer<Boolean>> roleListeners = new CopyOnWriteArrayList<>();
    private final ApplyBatchBuffer applyBatch = new ApplyBatchBuffer(BATCH_CAPACITY);
    private final Map<Long, PendingCommand> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean stepDownInProgress = new AtomicBoolean();

    private Cluster cluster;
    private boolean drainTimerScheduled;
    private long lastSnapshotLogPosition;
    private long nextSnapshotCheckNanos;

    private volatile long lastLogPosition;
    private volatile CompletableFuture<Void> snapshotCompletionFuture;

    AeronExchangeStateMachine(ExchangeCalls exchangeCalls, Path snapshotsRoot, SnapshotFetcher snapshotFetcher) {
        this(exchangeCalls, snapshotsRoot, snapshotFetcher, new SnapshotHelper());
    }

    AeronExchangeStateMachine(ExchangeCalls exchangeCalls, Path snapshotsRoot, SnapshotFetcher snapshotFetcher,
        SnapshotHelper snapshotHelper) {
        this.exchangeCalls = exchangeCalls;
        this.snapshotsRoot = snapshotsRoot;
        this.snapshotFetcher = snapshotFetcher;
        this.snapshotHelper = snapshotHelper;
    }

    void addRoleChangeListener(Consumer<Boolean> listener) {
        roleListeners.add(listener);
    }

    long lastLogPosition() {
        return lastLogPosition;
    }

    long pendingSize() {
        return pending.size();
    }

    CompletableFuture<Void> requestSnapshot() {
        CompletableFuture<Void> future = new CompletableFuture<>();
        snapshotCompletionFuture = future;
        return future;
    }

    AtomicBoolean stepDownInProgress() {
        return stepDownInProgress;
    }

    @Override
    public void onStart(Cluster cluster, Image snapshotImage) {
        this.cluster = cluster;
        pending.clear();
        applyBatch.reset();
        drainTimerScheduled = false;
        snapshotCompletionFuture = null;
        stepDownInProgress.set(false);
        lastSnapshotLogPosition = cluster.logPosition();
        lastLogPosition = cluster.logPosition();
        nextSnapshotCheckNanos = System.nanoTime() + ThreadLocalRandom.current().nextLong(SNAPSHOT_INTERVAL_NANOS);

        if (snapshotImage != null) {
            long deadline = System.nanoTime() + MARKER_READ_DEADLINE_NANOS;
            long[] snapshotIdHolder = {-1L};
            while (!snapshotImage.isClosed() && System.nanoTime() < deadline) {
                int fragments = snapshotImage.poll((buf, off, len, hdr) -> {
                    if (len >= Long.BYTES) {
                        snapshotIdHolder[0] = buf.getLong(off);
                    }
                }, 16);
                if (fragments == 0) {
                    if (snapshotImage.isEndOfStream())
                        break;
                    Thread.yield();
                }
            }
            long snapshotId = snapshotIdHolder[0];
            if (snapshotId > 0) {
                recoverFromSnapshot(snapshotId);
            } else {
                LOGGER.warn("snapshot image present but no valid marker found");
            }
        }

        boolean isLeader = cluster.role() == Cluster.Role.LEADER;
        for (Consumer<Boolean> listener : roleListeners) {
            try {
                listener.accept(isLeader);
            } catch (Throwable err) {
                LOGGER.warn("role listener failed on start", err);
            }
        }
        LOGGER.info("state machine started, memberId={}, role={}", cluster.memberId(), cluster.role());
    }

    private void recoverFromSnapshot(long snapshotId) {
        long startNanos = System.nanoTime();
        try {
            boolean present = false;
            if (Files.isDirectory(snapshotsRoot)) {
                String filePrefix = "snapshot_" + snapshotId + "_";
                try (Stream<Path> paths = Files.list(snapshotsRoot)) {
                    present = paths.anyMatch(path -> path.getFileName().toString().startsWith(filePrefix));
                } catch (IOException e) {
                    LOGGER.warn("list snapshotsRoot failed", e);
                }
            }
            if (!present) {
                LOGGER.info("snapshot id={} missing locally, fetching via sidecar", snapshotId);
                snapshotFetcher.fetch(snapshotId, snapshotsRoot);
            }
            SnapshotHelper.setSnapshotPath(snapshotsRoot.toString());
            var recoverResult =
                exchangeCalls.submitRecoverCommandAsync(ApiRecoverState.builder().snapshotId(snapshotId).build())
                    .get(RECOVER_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (recoverResult != exchange.core2.core.common.cmd.CommandResultCode.SUCCESS) {
                throw new IllegalStateException("recover non-success: " + recoverResult);
            }
            RaftExchangeMetrics.Snapshot.recordLoadSuccess(startNanos);
            LOGGER.info("snapshot id={} loaded", snapshotId);
        } catch (Throwable t) {
            RaftExchangeMetrics.Snapshot.recordLoadFailure(startNanos);
            RaftExchangeMetrics.Snapshot.recordRecoverHalt();
            LOGGER.error("snapshot load failed id={}, halting", snapshotId, t);
            Runtime.getRuntime().halt(137);
        }
    }

    @Override
    public void onSessionOpen(ClientSession session, long timestamp) {
        LOGGER.debug("session opened id={}", session.id());
        RaftExchangeMetrics.Raft.recordSessionOpen();
    }

    @Override
    public void onSessionClose(ClientSession session, long timestamp, CloseReason reason) {
        LOGGER.debug("session closed id={} reason={}", session.id(), reason);
        RaftExchangeMetrics.Raft.recordSessionClose(reason.name());
        long sessionId = session.id();
        pending.entrySet().removeIf(entry -> entry.getValue().sessionId == sessionId);
    }

    @Override
    public void onSessionMessage(ClientSession session, long timestamp, DirectBuffer buffer, int offset, int length,
        Header header) {
        if (length < AeronFrame.Ingress.HEADER_LENGTH) {
            LOGGER.warn("session msg too short: {}", length);
            return;
        }
        if (!drainTimerScheduled) {
            cluster.scheduleTimer(DRAIN_TIMER_ID, cluster.time() + DRAIN_INTERVAL_MS);
            drainTimerScheduled = true;
        }
        long correlationId = AeronFrame.Ingress.correlationId(buffer, offset);
        long sessionId = session.id();
        long startNanos = System.nanoTime();
        long entryLogPosition = cluster.logPosition();
        if (stepDownInProgress.get()) {
            LOGGER.warn("stepdown in progress, rejecting cid={}", correlationId);
            pending.put(correlationId, new PendingCommand(sessionId, startNanos, entryLogPosition, 0L, null));
            fillPending(correlationId, dropResponse());
            return;
        }

        if (pending.size() >= PENDING_MAX) {
            LOGGER.warn("pending map full ({}), rejecting cid={}", PENDING_MAX, correlationId);
            RaftExchangeMetrics.Raft.recordPendingReject();
            UnsafeBuffer reject = AeronFrame.Egress.encode(correlationId, entryLogPosition, 0L, dropResponse());
            session.offer(reject, 0, reject.capacity());
            return;
        }

        ApiCommand cmd;
        try {
            byte[] cmdBytes = AeronFrame.Ingress.payload(buffer, offset, length);
            cmd = ApiCommand.parseFrom(cmdBytes);
        } catch (Throwable t) {
            LOGGER.error("onSessionMessage parse failed, cid={}", correlationId, t);
            pending.put(correlationId, new PendingCommand(sessionId, startNanos, entryLogPosition, 0L, null));
            fillPending(correlationId, errorResponse());
            return;
        }

        pending.put(correlationId, new PendingCommand(sessionId, startNanos, entryLogPosition, 0L, null));
        dispatch(cmd, correlationId);
        drainPending();
        lastLogPosition = entryLogPosition;
    }

    @Override
    public void onTimerEvent(long correlationId, long timestamp) {
        if (correlationId != DRAIN_TIMER_ID) {
            return;
        }
        try {
            flushBatch();
            RaftExchangeMetrics.Raft.recordDrainCleared(drainPending());
            lastLogPosition = cluster.logPosition();

            if (cluster.role() == Cluster.Role.LEADER) {
                long nowNanos = System.nanoTime();
                boolean periodicDue = nowNanos >= nextSnapshotCheckNanos
                    && (lastLogPosition - lastSnapshotLogPosition) >= SNAPSHOT_LOG_BYTES_MARGIN;
                if (periodicDue || snapshotCompletionFuture != null) {
                    try {
                        CountersReader counters = cluster.context().aeron().countersReader();
                        AtomicCounter toggle =
                            ClusterControl.findControlToggle(counters, cluster.context().clusterId());
                        if (toggle == null) {
                            LOGGER.warn("snapshot: control toggle counter not found");
                        } else if (!ClusterControl.ToggleState.SNAPSHOT.toggle(toggle)) {
                            LOGGER.debug("snapshot toggle rejected: {}", ClusterControl.ToggleState.get(toggle));
                        } else {
                            LOGGER.info("snapshot toggle set, advance={} bytes",
                                lastLogPosition - lastSnapshotLogPosition);
                            if (periodicDue) {
                                nextSnapshotCheckNanos = nowNanos + SNAPSHOT_INTERVAL_NANOS;
                            }
                        }
                    } catch (Throwable t) {
                        LOGGER.warn("snapshot toggle failed", t);
                    }
                }
            }
        } finally {
            cluster.scheduleTimer(DRAIN_TIMER_ID, timestamp + DRAIN_INTERVAL_MS);
        }
    }

    @Override
    public void onTakeSnapshot(ExclusivePublication snapshotPublication) {
        long startNanos = System.nanoTime();
        long snapshotId = SnapshotHelper.genSnapshotId();
        try {
            flushBatch();
            Files.createDirectories(snapshotsRoot);
            exchangeCalls.submitCommand(ApiPersistState.builder().dumpId(snapshotId).build());
            List<String> files = snapshotHelper.saveSnapshot(snapshotId, snapshotsRoot.toString());

            UnsafeBuffer marker = new UnsafeBuffer(new byte[Long.BYTES]);
            marker.putLong(0, snapshotId);
            long offerDeadline = System.nanoTime() + MARKER_OFFER_DEADLINE_NANOS;
            long offerResult = Publication.BACK_PRESSURED;
            while (System.nanoTime() < offerDeadline) {
                offerResult = snapshotPublication.offer(marker, 0, marker.capacity());
                if (offerResult >= 0)
                    break;
                if (offerResult != Publication.BACK_PRESSURED && offerResult != Publication.ADMIN_ACTION)
                    break;
                Thread.yield();
            }
            if (offerResult < 0) {
                RaftExchangeMetrics.Snapshot.recordMarkerOfferFailure();
                throw new IllegalStateException("snapshot marker offer failed: " + offerResult);
            }
            long totalBytes = 0;
            for (String f : files) {
                try {
                    totalBytes += Files.size(snapshotsRoot.resolve(f));
                } catch (Exception sizeErr) {
                    RaftExchangeMetrics.Snapshot.recordSizeProbeFailure();
                    LOGGER.warn("snapshot file size probe failed: {}/{}", snapshotsRoot, f, sizeErr);
                }
            }
            RaftExchangeMetrics.Snapshot.recordSaveSuccess(startNanos, totalBytes);
            LOGGER.info("snapshot saved id={}", snapshotId);
            lastSnapshotLogPosition = lastLogPosition;
            CompletableFuture<Void> future = snapshotCompletionFuture;
            snapshotCompletionFuture = null;
            if (future != null) {
                future.complete(null);
            }
        } catch (Throwable e) {
            RaftExchangeMetrics.Snapshot.recordSaveFailure(startNanos);
            LOGGER.error("snapshot save failed id={}", snapshotId, e);
            SnapshotHelper.cleanSnapshots(snapshotsRoot.toString(), snapshotId);
            CompletableFuture<Void> future = snapshotCompletionFuture;
            snapshotCompletionFuture = null;
            if (future != null) {
                future.completeExceptionally(e);
            }
            throw new RuntimeException("snapshot save failed for id=" + snapshotId, e);
        }
    }

    @Override
    public void onRoleChange(Cluster.Role newRole) {
        LOGGER.info("role changed → {}", newRole);
        boolean isLeader = newRole == Cluster.Role.LEADER;
        for (Consumer<Boolean> listener : roleListeners) {
            try {
                listener.accept(isLeader);
            } catch (Throwable err) {
                LOGGER.warn("role listener failed", err);
            }
        }
    }

    @Override
    public void onTerminate(Cluster cluster) {
        LOGGER.info("state machine terminating");
    }

    private void dispatch(ApiCommand cmd, long correlationId) {
        ApiCommand.CommandCase commandCase = cmd.getCommandCase();
        if (commandCase == ApiCommand.CommandCase.BINARY_DATA || commandCase == ApiCommand.CommandCase.NOP) {
            flushBatch();
            exchangeCalls.apply(cmd).whenComplete((responseSupplier, asyncErr) -> {
                byte[] responseBytes = errorResponse();
                if (asyncErr != null) {
                    LOGGER.warn("async cmd failed cid={}", correlationId, asyncErr);
                } else {
                    try {
                        responseBytes = responseSupplier.get();
                    } catch (Throwable serializerErr) {
                        LOGGER.warn("supplier.get failed cid={}", correlationId, serializerErr);
                    }
                }
                fillPending(correlationId, responseBytes);
            });
            return;
        }
        exchange.core2.core.common.api.ApiCommand engineCmd = exchangeCalls.toExchangeCommand(cmd);
        if (engineCmd == null) {
            LOGGER.warn("Unsupported ApiCommand cid={} case={}", correlationId, commandCase);
            RaftExchangeMetrics.Raft.recordUnsupportedCommand();
            fillPending(correlationId, errorResponse());
            return;
        }
        applyBatch.append(engineCmd, correlationId,
            engineResult -> fillPending(correlationId, SerializeHelper.serializeToCommandResult(engineResult)));
        if (applyBatch.isFull()) {
            flushBatch();
        }
    }

    private void flushBatch() {
        if (applyBatch.isEmpty())
            return;
        int batchSize = applyBatch.size;
        RaftExchangeMetrics.Raft.recordApplyBatchSize(batchSize);
        try {
            exchangeCalls.submitBatchAsync(applyBatch.commands, applyBatch.callbacks, batchSize);
        } catch (Throwable submitErr) {
            LOGGER.error("submitBatchAsync failed size={}", batchSize, submitErr);
            RaftExchangeMetrics.Raft.recordSubmitBatchFailure(batchSize);
            byte[] errorBytes = errorResponse();
            for (int i = 0; i < batchSize; i++) {
                fillPending(applyBatch.correlationIds[i], errorBytes);
            }
        } finally {
            applyBatch.reset();
        }
    }

    private int drainPending() {
        int cleared = 0;
        Iterator<Map.Entry<Long, PendingCommand>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, PendingCommand> entry = iterator.next();
            PendingCommand cmd = entry.getValue();
            if (cmd.response == null)
                continue;

            ClientSession session = cluster.getClientSession(cmd.sessionId);
            if (session == null || session.isClosing()) {
                iterator.remove();
                cleared++;
                continue;
            }

            long correlationId = entry.getKey();
            UnsafeBuffer framedResponse = AeronFrame.Egress.encode(correlationId, cmd.entryLogPosition,
                cmd.engineNanosTaken, cmd.response);
            long offerResult = session.offer(framedResponse, 0, framedResponse.capacity());
            if (offerResult >= 0) {
                iterator.remove();
                cleared++;
            } else if (offerResult != Publication.BACK_PRESSURED && offerResult != Publication.ADMIN_ACTION) {
                LOGGER.warn("session.offer failed: {} cid={}", offerResult, correlationId);
                iterator.remove();
                cleared++;
            }
        }
        return cleared;
    }

    private void fillPending(long correlationId, byte[] response) {
        pending.computeIfPresent(correlationId, (cid, existing) -> {
            long engineNanosTaken = System.nanoTime() - existing.startNanos;
            RaftExchangeMetrics.Raft.recordApplyLatency(existing.startNanos);
            return new PendingCommand(existing.sessionId, existing.startNanos, existing.entryLogPosition, engineNanosTaken,
                response);
        });
    }

    private static byte[] errorResponse() {
        return CommandResult.newBuilder().setResultCode(CommandResultCode.INTERNAL_ERROR).build().toByteArray();
    }

    private static byte[] dropResponse() {
        return CommandResult.newBuilder().setResultCode(CommandResultCode.DROP).build().toByteArray();
    }

    private static final class PendingCommand {
        final long sessionId;
        final long startNanos;
        final long entryLogPosition;
        final long engineNanosTaken;
        final byte[] response;

        PendingCommand(long sessionId, long startNanos, long entryLogPosition, long engineNanosTaken, byte[] response) {
            this.sessionId = sessionId;
            this.startNanos = startNanos;
            this.entryLogPosition = entryLogPosition;
            this.engineNanosTaken = engineNanosTaken;
            this.response = response;
        }
    }

    private static final class ApplyBatchBuffer {
        final exchange.core2.core.common.api.ApiCommand[] commands;
        final Consumer<OrderCommand>[] callbacks;
        final long[] correlationIds;
        int size;

        @SuppressWarnings("unchecked")
        ApplyBatchBuffer(int capacity) {
            commands = new exchange.core2.core.common.api.ApiCommand[capacity];
            callbacks = new Consumer[capacity];
            correlationIds = new long[capacity];
        }

        void append(exchange.core2.core.common.api.ApiCommand cmd, long correlationId,
            Consumer<OrderCommand> callback) {
            commands[size] = cmd;
            callbacks[size] = callback;
            correlationIds[size] = correlationId;
            size++;
        }

        boolean isFull() {
            return size >= commands.length;
        }

        boolean isEmpty() {
            return size == 0;
        }

        void reset() {
            Arrays.fill(commands, 0, size, null);
            Arrays.fill(callbacks, 0, size, null);
            size = 0;
        }
    }
}
