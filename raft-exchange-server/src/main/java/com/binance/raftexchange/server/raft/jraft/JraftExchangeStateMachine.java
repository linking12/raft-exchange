package com.binance.raftexchange.server.raft.jraft;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.core.StateMachineAdapter;
import com.alipay.sofa.jraft.error.RaftError;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotReader;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotWriter;
import com.binance.raftexchange.server.exchange.ExchangeCalls;
import com.binance.raftexchange.server.exchange.snapshot.StreamManager;
import com.binance.raftexchange.server.metrics.RaftExchangeMetrics;
import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.server.raft.SnapshotHelper;
import com.binance.raftexchange.server.raft.jraft.closure.BatchClosure;
import com.binance.raftexchange.server.raft.jraft.closure.SingleClosure;
import com.binance.raftexchange.server.util.SerializeHelper;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.google.protobuf.GeneratedMessageV3;

import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.ApiRecoverState;

/**
 * SOFA-JRaft {@link StateMachineAdapter} 实现：从 raft log 反序列化命令，分发到 exchange-core 引擎，触发 closure 回调返回响应。
 *
 * <p>
 * 单条命令走 {@link SingleClosure} fast path（pre-parsed msg 免重复反序列化）；envelope batch 走 {@link BatchClosure}（一条 raft entry
 * 内打包多条小命令，固定头开销分摊）。Apply batch buffer（容量 {@link #APPLY_BATCH_CAPACITY}）把多条命令成批提交进 disruptor。
 *
 * <p>
 * Snapshot save：{@link ApiPersistState} + {@link SnapshotHelper} 流式 piped → .dat；load 反向走 {@link ApiRecoverState}，失败
 * halt(137) 防落后节点带不一致状态服务请求。
 */
final class JraftExchangeStateMachine extends StateMachineAdapter {
    private static final Logger LOG = LoggerFactory.getLogger(JraftExchangeStateMachine.class);
    private static final Status STATUS_OK = Status.OK();
    private static final int APPLY_BATCH_CAPACITY = 1024;
    private static final long RECOVER_TIMEOUT_SEC = 300L;

    private final ExchangeCalls calls;
    private final SnapshotHelper snapshotHelper;

    private final CopyOnWriteArrayList<Consumer<RaftNode.NodeType>> roleListeners = new CopyOnWriteArrayList<>();
    private final ApplyBatchBuffer applyBatch = new ApplyBatchBuffer(APPLY_BATCH_CAPACITY);

    private volatile long lastAppliedIndex = 0L;
    private volatile long leaderTerm = -1L;

    public JraftExchangeStateMachine(ExchangeCalls calls) {
        this(calls, new SnapshotHelper());
    }

    public JraftExchangeStateMachine(ExchangeCalls calls, SnapshotHelper snapshotHelper) {
        this.calls = calls;
        this.snapshotHelper = snapshotHelper;
    }

    public boolean isLeader() {
        return this.leaderTerm > 0;
    }

    public long lastAppliedIndex() {
        return this.lastAppliedIndex;
    }

    public void addRoleChangeListener(Consumer<RaftNode.NodeType> listener) {
        roleListeners.add(listener);
        listener.accept(isLeader() ? RaftNode.NodeType.LEADER : RaftNode.NodeType.FOLLOWER);
    }

    public boolean canBatch(GeneratedMessageV3 msg) {
        return msg instanceof ApiCommand apiCmd && calls.canBatch(apiCmd);
    }

    @Override
    public void onApply(Iterator iter) {
        long lastIndex = 0L;
        long applyStartNanos = System.nanoTime();
        while (iter.hasNext()) {
            lastIndex = iter.getIndex();
            Closure done = iter.done();
            if (BatchCommandHelper.isBatchEntry(iter.getData())) {
                BatchClosure batchClosure = (done instanceof BatchClosure) ? (BatchClosure)done : null;
                handleEnvelopeEntry(iter.getData(), batchClosure);
            } else {
                handleSingleEntry(iter.getData(), done);
            }
            iter.next();
        }
        flushApplyBatch();
        if (lastIndex > 0L) {
            lastAppliedIndex = lastIndex;
            RaftExchangeMetrics.Raft.recordApplyLatency(applyStartNanos);
        }
    }

    private void handleSingleEntry(ByteBuffer data, Closure done) {
        SingleClosure singleClosure = (done instanceof SingleClosure) ? (SingleClosure)done : null;
        long applyNanos = (singleClosure != null) ? System.nanoTime() : 0L;
        try {
            GeneratedMessageV3 msg =
                (singleClosure != null) ? singleClosure.message() : SerializeHelper.deserializeWithType(data);
            exchange.core2.core.common.api.ApiCommand exchangeCmd = convertToExchangeCommand(msg);
            if (exchangeCmd != null) {
                if (singleClosure != null) {
                    singleClosure.setApplyNanos(applyNanos);
                }
                appendToApplyBatch(exchangeCmd, singleClosure, done);
                return;
            }
            flushApplyBatch();
            CompletableFuture<Supplier<byte[]>> result;
            if (msg instanceof ApiCommand apiCommand) {
                result = calls.apply(apiCommand);
            } else {
                RaftExchangeMetrics.Raft.recordUnsupportedCommand();
                result = CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Unsupported msg: " + (msg == null ? "null" : msg.getClass().getName())));
            }
            if (done != null) {
                if (singleClosure != null) {
                    singleClosure.setApplyNanos(applyNanos);
                    singleClosure.acceptFuture(result);
                }
                done.run(STATUS_OK);
            }
        } catch (Throwable e) {
            LOG.error("Fail to apply", e);
            flushApplyBatch();
            if (done != null) {
                if (singleClosure != null) {
                    singleClosure.completeExceptionally(e);
                }
                done.run(STATUS_OK);
            }
        }
    }

    private void handleEnvelopeEntry(ByteBuffer data, BatchClosure batchClosure) {
        byte[][] subBytes;
        try {
            subBytes = BatchCommandHelper.unpackBatchEntry(data);
        } catch (Throwable t) {
            LOG.error("Failed to unpack batch envelope", t);
            if (batchClosure != null) {
                batchClosure.failAll(t);
            }
            return;
        }

        int subCount = subBytes.length;
        for (int i = 0; i < subCount; i++) {
            try {
                GeneratedMessageV3 msg = (batchClosure != null) ? batchClosure.at(i).preParsed()
                    : SerializeHelper.deserializeWithType(ByteBuffer.wrap(subBytes[i]));
                exchange.core2.core.common.api.ApiCommand exchangeCmd = convertToExchangeCommand(msg);
                if (exchangeCmd == null) {
                    String type = msg == null ? "null" : msg.getClass().getSimpleName();
                    LOG.error("envelope sub-cmd #{} not convertible: {}", i, type);
                    RaftExchangeMetrics.Raft.recordUnsupportedCommand();
                    if (batchClosure != null) {
                        batchClosure.failOne(i,
                            new IllegalArgumentException("sub-cmd #" + i + " unsupported: " + type));
                    }
                    continue;
                }
                appendToApplyBatch(exchangeCmd, batchClosure != null ? batchClosure.subCallback(i) : null,
                    batchClosure);
            } catch (Throwable t) {
                LOG.error("Failed to process envelope sub-cmd #{}", i, t);
                if (batchClosure != null) {
                    batchClosure.failOne(i, t);
                }
            }
        }
    }

    private void appendToApplyBatch(exchange.core2.core.common.api.ApiCommand cmd,
        Consumer<exchange.core2.core.common.cmd.OrderCommand> callback, Closure closure) {
        applyBatch.append(cmd, callback, closure);
        if (applyBatch.isFull()) {
            flushApplyBatch();
        }
    }

    private void flushApplyBatch() {
        if (applyBatch.isEmpty()) {
            return;
        }
        int size = applyBatch.size;
        RaftExchangeMetrics.Raft.recordApplyBatchSize(size);
        try {
            try {
                calls.submitBatchAsync(applyBatch.commands, applyBatch.callbacks, size);
            } catch (Throwable e) {
                LOG.error("submitBatchAsync failed for apply batch of size {}, notifying closures with error", size, e);
                RaftExchangeMetrics.Raft.recordSubmitBatchFailure(size);
                for (int i = 0; i < size; i++) {
                    if (applyBatch.closures[i] instanceof SingleClosure singleClosure) {
                        singleClosure.completeExceptionally(e);
                    } else if (applyBatch.closures[i] instanceof BatchClosure batchClosure) {
                        batchClosure.failAll(e);
                    } else if (applyBatch.closures[i] != null) {
                        applyBatch.closures[i].run(new Status(RaftError.EINTERNAL, e.toString()));
                    }
                }
                return;
            }
            for (int i = 0; i < size; i++) {
                if (applyBatch.closures[i] != null) {
                    applyBatch.closures[i].run(STATUS_OK);
                }
            }
        } finally {
            applyBatch.reset();
        }
    }

    private exchange.core2.core.common.api.ApiCommand convertToExchangeCommand(GeneratedMessageV3 msg) {
        if (!(msg instanceof ApiCommand apiCommand)) {
            return null;
        }
        return calls.toExchangeCommand(apiCommand);
    }

    private static final class ApplyBatchBuffer {
        final exchange.core2.core.common.api.ApiCommand[] commands;
        final Consumer<exchange.core2.core.common.cmd.OrderCommand>[] callbacks;
        final Closure[] closures;
        int size;

        @SuppressWarnings("unchecked")
        ApplyBatchBuffer(int capacity) {
            commands = new exchange.core2.core.common.api.ApiCommand[capacity];
            callbacks = new Consumer[capacity];
            closures = new Closure[capacity];
        }

        void append(exchange.core2.core.common.api.ApiCommand cmd,
            Consumer<exchange.core2.core.common.cmd.OrderCommand> callback, Closure closure) {
            commands[size] = cmd;
            callbacks[size] = callback;
            closures[size] = closure;
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
            Arrays.fill(closures, 0, size, null);
            size = 0;
        }
    }

    @Override
    public boolean onSnapshotLoad(SnapshotReader reader) {
        if (isLeader()) {
            LOG.warn("Leader is not supposed to load snapshot");
            return false;
        }
        long startNanos = System.nanoTime();
        boolean ok = false;
        try {
            com.alipay.sofa.jraft.entity.RaftOutter.SnapshotMeta meta = reader.load();
            LOG.info("Loading snapshot, lastIncludedIndex={}, lastIncludedTerm={}", meta.getLastIncludedIndex(),
                meta.getLastIncludedTerm());
            SnapshotHelper.setSnapshotPath(reader.getPath());
            Set<String> files = reader.listFiles();
            if (!snapshotHelper.checkSnapshotIntegrity(files)) {
                LOG.error(
                    "Snapshot shard count mismatch! Update PerformanceConfiguration.DEFAULT config to match snapshot files or delete existing snapshot files.");
                return false;
            }
            CompletableFuture<exchange.core2.core.common.cmd.CommandResultCode> recoverFuture;
            try {
                long snapshotId = SnapshotHelper.getSnapshotId(files);
                recoverFuture =
                    calls.submitRecoverCommandAsync(ApiRecoverState.builder().snapshotId(snapshotId).build());
            } catch (Exception e) {
                LOG.error("Snapshot recovery submit failed", e);
                return false;
            }
            try {
                exchange.core2.core.common.cmd.CommandResultCode result =
                    recoverFuture.get(RECOVER_TIMEOUT_SEC, TimeUnit.SECONDS);
                if (result != exchange.core2.core.common.cmd.CommandResultCode.SUCCESS) {
                    return haltAfterUnrecoverableSnapshotLoad("non-success result " + result);
                }
                ok = true;
                return true;
            } catch (TimeoutException e) {
                recoverFuture.cancel(true);
                LOG.error("Snapshot recovery timed out after {}s", RECOVER_TIMEOUT_SEC, e);
                return haltAfterUnrecoverableSnapshotLoad("timeout " + RECOVER_TIMEOUT_SEC + "s");
            } catch (InterruptedException e) {
                recoverFuture.cancel(true);
                Thread.currentThread().interrupt();
                LOG.error("Snapshot recovery interrupted", e);
                return haltAfterUnrecoverableSnapshotLoad("interrupted");
            } catch (Exception e) {
                LOG.error("Snapshot recovery await failed", e);
                return haltAfterUnrecoverableSnapshotLoad("await exception: " + e);
            }
        } finally {
            if (ok) {
                RaftExchangeMetrics.Snapshot.recordLoadSuccess(startNanos);
            } else {
                RaftExchangeMetrics.Snapshot.recordLoadFailure(startNanos);
            }
        }
    }

    @Override
    public void onSnapshotSave(SnapshotWriter writer, Closure done) {
        long startNanos = System.nanoTime();
        long indexAtSave = lastAppliedIndex;
        String root = writer.getPath();
        long snapshotId = SnapshotHelper.genSnapshotId();
        try {
            ApiPersistState apiPersist = ApiPersistState.builder().dumpId(snapshotId).build();
            calls.submitCommand(apiPersist);
            List<String> files = snapshotHelper.saveSnapshot(snapshotId, root);
            for (String fileName : files) {
                if (!writer.addFile(fileName)) {
                    SnapshotHelper.cleanSnapshots(root, snapshotId);
                    RaftExchangeMetrics.Snapshot.recordSaveFailure(startNanos);
                    done.run(new Status(RaftError.EIO, "Fail to add snapshot file: " + fileName));
                    return;
                }
            }
            long totalBytes = 0;
            for (String fileName : files) {
                try {
                    totalBytes += Files.size(Paths.get(root, fileName));
                } catch (Exception sizeErr) {
                    RaftExchangeMetrics.Snapshot.recordSizeProbeFailure();
                    LOG.warn("Failed to read snapshot file size: {}/{}", root, fileName, sizeErr);
                }
            }
            RaftExchangeMetrics.Snapshot.recordSaveSuccess(startNanos, totalBytes);
            LOG.info("Snapshot saved, lastIncludedIndex={}, snapshotId={}", indexAtSave, snapshotId);
            done.run(STATUS_OK);
        } catch (Exception e) {
            LOG.error("Snapshot save failed for snapshotId={}, cleaning up streams and partial files", snapshotId, e);
            StreamManager.closeAll(snapshotId);
            SnapshotHelper.cleanSnapshots(root, snapshotId);
            RaftExchangeMetrics.Snapshot.recordSaveFailure(startNanos);
            done.run(new Status(RaftError.EIO, "Snapshot save exception: " + e.toString()));
        }
    }

    private boolean haltAfterUnrecoverableSnapshotLoad(String reason) {
        RaftExchangeMetrics.Snapshot.recordRecoverHalt();
        LOG.error("Snapshot recovery left exchange-core in indeterminate state, halting: {}", reason);
        Runtime.getRuntime().halt(137);
        return false;
    }

    @Override
    public void onLeaderStart(long term) {
        this.leaderTerm = term;
        RaftExchangeMetrics.Raft.recordLeaderChange();
        roleListeners.forEach(listener -> listener.accept(RaftNode.NodeType.LEADER));
    }

    @Override
    public void onLeaderStop(Status status) {
        this.leaderTerm = -1;
        RaftExchangeMetrics.Raft.recordLeaderChange();
        roleListeners.forEach(listener -> listener.accept(RaftNode.NodeType.FOLLOWER));
    }
}
