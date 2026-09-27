package com.binance.raftexchange.server.raft.jraft;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotReader;
import com.alipay.sofa.jraft.storage.snapshot.SnapshotWriter;
import com.binance.raftexchange.server.exchange.ExchangeCalls;
import com.binance.raftexchange.server.metrics.RaftExchangeMetrics;
import com.binance.raftexchange.server.raft.SnapshotHelper;
import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.api.ApiPersistState;
import exchange.core2.core.common.api.ApiRecoverState;
import exchange.core2.core.common.cmd.CommandResultCode;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证 JraftExchangeStateMachine 的 snapshot metric 在 save/load 路径上正确埋点： 1. save 成功 → save.count{status=success}++ +
 * save.duration 记录 + last_success_epoch_sec 更新 2. save 失败（任一 addFile 返 false）→ save.count{status=failure}++ + 不更新
 * last_success 3. load 成功 → load.count{status=success}++ + last_success_epoch_sec 更新 4. load 失败（integrity check fail）→
 * load.count{status=failure}++
 */
class JraftExchangeStateMachineSnapshotMetricsTest {

    private static final String SNAP_PATH = "/tmp/test-snap-metric";

    @BeforeAll
    static void registerMeter() {
        Metrics.globalRegistry.add(new SimpleMeterRegistry());
    }

    private static double counter(String name, String status) {
        return Metrics.globalRegistry.counter(name, "status", status).count();
    }

    private static JraftExchangeStateMachine newMachine(ExchangeApi api, SnapshotHelper helper) {
        return new JraftExchangeStateMachine(new ExchangeCalls(api), helper);
    }

    private static com.alipay.sofa.jraft.entity.RaftOutter.SnapshotMeta fakeMeta() {
        return com.alipay.sofa.jraft.entity.RaftOutter.SnapshotMeta.newBuilder().setLastIncludedIndex(42)
            .setLastIncludedTerm(1).build();
    }

    @Test
    void onSnapshotSave_success_incrementsSuccessCounterAndUpdatesLastEpoch() {
        SnapshotHelper helper = mock(SnapshotHelper.class);
        when(helper.saveSnapshot(anyLong(), anyString())).thenReturn(List.of());

        ExchangeApi api = mock(ExchangeApi.class);
        SnapshotWriter writer = mock(SnapshotWriter.class);
        when(writer.getPath()).thenReturn(SNAP_PATH);
        when(writer.addFile(anyString())).thenReturn(true);

        double sBefore = counter("raft.exchange.snapshot.save.count", "success");
        double fBefore = counter("raft.exchange.snapshot.save.count", "failure");
        long lastBefore = RaftExchangeMetrics.Snapshot.lastSaveSuccessEpochSec();

        newMachine(api, helper).onSnapshotSave(writer, mock(Closure.class));

        assertEquals(sBefore + 1, counter("raft.exchange.snapshot.save.count", "success"), 0.001);
        assertEquals(fBefore, counter("raft.exchange.snapshot.save.count", "failure"), 0.001);
        assertTrue(RaftExchangeMetrics.Snapshot.lastSaveSuccessEpochSec() >= lastBefore,
            "last_success_epoch_sec 必须更新（>= 之前的值）");
    }

    @Test
    void onSnapshotSave_addFileFails_incrementsFailureCounterAndPreservesLastEpoch() {
        SnapshotHelper helper = mock(SnapshotHelper.class);
        when(helper.saveSnapshot(anyLong(), anyString())).thenReturn(List.of("snapshot_x_ME_0.dat"));

        ExchangeApi api = mock(ExchangeApi.class);
        SnapshotWriter writer = mock(SnapshotWriter.class);
        when(writer.getPath()).thenReturn(SNAP_PATH);
        when(writer.addFile(anyString())).thenReturn(false);

        double fBefore = counter("raft.exchange.snapshot.save.count", "failure");
        long lastBefore = RaftExchangeMetrics.Snapshot.lastSaveSuccessEpochSec();

        newMachine(api, helper).onSnapshotSave(writer, mock(Closure.class));

        assertEquals(fBefore + 1, counter("raft.exchange.snapshot.save.count", "failure"), 0.001);
        assertEquals(lastBefore, RaftExchangeMetrics.Snapshot.lastSaveSuccessEpochSec(),
            "失败路径不能更新 last_success_epoch_sec");
    }

    @Test
    void onSnapshotLoad_success_incrementsSuccessCounterAndUpdatesLastEpoch() {
        SnapshotHelper helper = mock(SnapshotHelper.class);
        when(helper.checkSnapshotIntegrity(any())).thenReturn(true);

        ExchangeApi api = mock(ExchangeApi.class);
        when(api.submitRecoverCommandAsync(any(ApiRecoverState.class)))
            .thenReturn(CompletableFuture.completedFuture(CommandResultCode.SUCCESS));

        SnapshotReader reader = mock(SnapshotReader.class);
        when(reader.load()).thenReturn(fakeMeta());
        when(reader.getPath()).thenReturn(SNAP_PATH);
        when(reader.listFiles())
            .thenReturn(Set.of("snapshot_20250307105705748_ME_0.dat", "snapshot_20250307105705748_RE_0.dat"));

        double sBefore = counter("raft.exchange.snapshot.load.count", "success");
        long lastBefore = RaftExchangeMetrics.Snapshot.lastLoadSuccessEpochSec();

        boolean ok = newMachine(api, helper).onSnapshotLoad(reader);

        assertTrue(ok);
        assertEquals(sBefore + 1, counter("raft.exchange.snapshot.load.count", "success"), 0.001);
        assertTrue(RaftExchangeMetrics.Snapshot.lastLoadSuccessEpochSec() >= lastBefore);
    }

    @Test
    void onSnapshotLoad_integrityFail_incrementsFailureCounter() {
        SnapshotHelper helper = mock(SnapshotHelper.class);
        when(helper.checkSnapshotIntegrity(any())).thenReturn(false);

        SnapshotReader reader = mock(SnapshotReader.class);
        when(reader.load()).thenReturn(fakeMeta());
        when(reader.getPath()).thenReturn(SNAP_PATH);
        when(reader.listFiles()).thenReturn(Set.of("bad.dat"));

        double fBefore = counter("raft.exchange.snapshot.load.count", "failure");

        boolean ok = newMachine(mock(ExchangeApi.class), helper).onSnapshotLoad(reader);

        assertEquals(false, ok);
        assertEquals(fBefore + 1, counter("raft.exchange.snapshot.load.count", "failure"), 0.001);
    }

    @Test
    void durationTimer_isRegisteredAndUpdatedOnSave() {
        SnapshotHelper helper = mock(SnapshotHelper.class);
        when(helper.saveSnapshot(anyLong(), anyString())).thenReturn(List.of());
        ExchangeApi api = mock(ExchangeApi.class);
        SnapshotWriter writer = mock(SnapshotWriter.class);
        when(writer.getPath()).thenReturn(SNAP_PATH);
        when(writer.addFile(anyString())).thenReturn(true);

        long countBefore = Metrics.globalRegistry.timer("raft.exchange.snapshot.save.duration").count();
        newMachine(api, helper).onSnapshotSave(writer, mock(Closure.class));
        long countAfter = Metrics.globalRegistry.timer("raft.exchange.snapshot.save.duration").count();

        assertNotNull(Metrics.globalRegistry.timer("raft.exchange.snapshot.save.duration"));
        assertTrue(countAfter > countBefore, "save.duration timer 应该记录一次");
    }
}
