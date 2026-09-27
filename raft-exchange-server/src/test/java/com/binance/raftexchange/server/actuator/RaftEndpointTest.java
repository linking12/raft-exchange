package com.binance.raftexchange.server.actuator;

import com.binance.raftexchange.server.RaftExchangeApplication;
import com.binance.raftexchange.server.raft.AdminResult;
import com.binance.raftexchange.server.raft.RaftClusterContainer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * POST /raft/snapshot / POST /raft/stepdown / GET /raft/snapshot： - snapshot 失败路径：raft 未启动 / 返 error / future 抛 -
 * leadership 失败路径：raft 未启动 / 返 error - 未知 operation 返回 error - GET /raft/snapshot 返回 metric 快照
 */
class RaftEndpointTest {

    // ===== POST /raft/snapshot =====

    @Test
    void snapshot_raftNotStarted_returnsError() {
        RaftExchangeApplication app = mock(RaftExchangeApplication.class);
        when(app.getRaftClusterContainer()).thenReturn(null);

        Map<String, Object> resp = new RaftEndpoint(app).write("snapshot");

        assertEquals("error", resp.get("status"));
        assertNotNull(resp.get("message"));
    }

    @Test
    void snapshot_succeeds_returnsSuccessWithDuration() {
        RaftClusterContainer raft = mock(RaftClusterContainer.class);
        when(raft.triggerSnapshot()).thenReturn(CompletableFuture.completedFuture(AdminResult.ok()));
        RaftExchangeApplication app = mock(RaftExchangeApplication.class);
        when(app.getRaftClusterContainer()).thenReturn(raft);

        Map<String, Object> resp = new RaftEndpoint(app).write("snapshot");

        assertEquals("success", resp.get("status"));
        assertEquals("OK", resp.get("message"));
        assertTrue((Long)resp.get("duration_ms") >= 0);
        verify(raft).triggerSnapshot();
    }

    @Test
    void snapshot_backendReturnsError_returnsErrorWithMessage() {
        RaftClusterContainer raft = mock(RaftClusterContainer.class);
        when(raft.triggerSnapshot()).thenReturn(CompletableFuture.completedFuture(AdminResult.error("disk full")));
        RaftExchangeApplication app = mock(RaftExchangeApplication.class);
        when(app.getRaftClusterContainer()).thenReturn(raft);

        Map<String, Object> resp = new RaftEndpoint(app).write("snapshot");

        assertEquals("error", resp.get("status"));
        assertTrue(((String)resp.get("message")).contains("disk full"));
    }

    @Test
    void snapshot_futureThrows_returnsError() {
        RaftClusterContainer raft = mock(RaftClusterContainer.class);
        CompletableFuture<AdminResult> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("ring buffer full"));
        when(raft.triggerSnapshot()).thenReturn(failed);
        RaftExchangeApplication app = mock(RaftExchangeApplication.class);
        when(app.getRaftClusterContainer()).thenReturn(raft);

        Map<String, Object> resp = new RaftEndpoint(app).write("snapshot");

        assertEquals("error", resp.get("status"));
        assertTrue(((String)resp.get("message")).contains("ring buffer full"));
    }

    // ===== POST /raft/stepdown =====

    @Test
    void leadership_raftNotStarted_returnsError() {
        RaftExchangeApplication app = mock(RaftExchangeApplication.class);
        when(app.getRaftClusterContainer()).thenReturn(null);

        Map<String, Object> resp = new RaftEndpoint(app).write("stepdown");

        assertEquals("error", resp.get("status"));
    }

    @Test
    void leadership_succeeds_returnsSuccess() {
        RaftClusterContainer raft = mock(RaftClusterContainer.class);
        when(raft.stepDownLeadership()).thenReturn(AdminResult.ok());
        RaftExchangeApplication app = mock(RaftExchangeApplication.class);
        when(app.getRaftClusterContainer()).thenReturn(raft);

        Map<String, Object> resp = new RaftEndpoint(app).write("stepdown");

        assertEquals("success", resp.get("status"));
        verify(raft).stepDownLeadership();
    }

    @Test
    void leadership_backendReturnsError_returnsError() {
        RaftClusterContainer raft = mock(RaftClusterContainer.class);
        when(raft.stepDownLeadership()).thenReturn(AdminResult.error("transfer in progress"));
        RaftExchangeApplication app = mock(RaftExchangeApplication.class);
        when(app.getRaftClusterContainer()).thenReturn(raft);

        Map<String, Object> resp = new RaftEndpoint(app).write("stepdown");

        assertEquals("error", resp.get("status"));
        assertTrue(((String)resp.get("message")).contains("transfer in progress"));
    }

    // ===== unknown operation =====

    @Test
    void write_unknownOperation_returnsError() {
        Map<String, Object> resp = new RaftEndpoint(mock(RaftExchangeApplication.class)).write("unknown");

        assertEquals("error", resp.get("status"));
        assertTrue(((String)resp.get("message")).contains("unknown"));
    }

    // ===== GET /raft/snapshot =====

    @Test
    void read_snapshot_returnsMetrics() {
        com.binance.raftexchange.server.metrics.RaftExchangeMetrics.Snapshot.resetLastSuccessTimestampsForTesting();
        Map<String, Object> s = new RaftEndpoint(mock(RaftExchangeApplication.class)).read("snapshot");

        assertNotNull(s.get("save.healthy"));
        assertNotNull(s.get("save.success_count"));
        assertNotNull(s.get("save.failure_count"));
        assertNotNull(s.get("save.last_success_at"));
        assertNotNull(s.get("save.last_success_age"));
        assertNotNull(s.get("load.healthy"));
        assertNotNull(s.get("load.success_count"));
        assertNotNull(s.get("load.failure_count"));
        assertNotNull(s.get("load.last_success_at"));
        assertNotNull(s.get("load.last_success_age"));
        // 从未 snapshot 过时显示 never（重置时间戳后断言）
        assertEquals("never", s.get("save.last_success_at"));
        assertEquals("never", s.get("load.last_success_at"));
    }

    @Test
    void read_unknownOperation_returnsError() {
        Map<String, Object> resp = new RaftEndpoint(mock(RaftExchangeApplication.class)).read("unknown");

        assertEquals("error", resp.get("status"));
    }
}
