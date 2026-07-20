package com.binance.raftexchange.server.raft.aeron;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.binance.raftexchange.server.exchange.ExchangeRuntime;
import com.binance.raftexchange.server.raft.AdminResult;
import com.binance.raftexchange.server.raft.RaftClusterDiscovery;
import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.server.raft.RaftResponse;

import exchange.core2.core.ExchangeCore;

/**
 * 不起 Aeron。验证未启动时各 API 的失败语义 / role listener 注册即回调。
 */
class AeronClusterContainerUnitTest {

    @Test
    void triggerSnapshot_beforeStart_returnsError() throws Exception {
        AeronClusterContainer c = newContainer();
        AdminResult r = c.triggerSnapshot().get(1, TimeUnit.SECONDS);
        assertFalse(r.success());
        assertTrue(r.message().contains("not started"), "message 应提到 not started: " + r.message());
    }

    @Test
    void stepDownLeadership_beforeStart_returnsError() {
        AeronClusterContainer c = newContainer();
        AdminResult r = c.stepDownLeadership();
        assertFalse(r.success());
        assertTrue(r.message().contains("not started"), "message 应提到 not started: " + r.message());
    }

    @Test
    void requestConsensus_beforeStart_invokesCallbackWithIllegalState() {
        AeronClusterContainer c = newContainer();
        AtomicReference<Throwable> err = new AtomicReference<>();
        c.requestConsensus(new byte[] {1, 2, 3}, (RaftResponse resp, Throwable e) -> err.set(e));
        assertInstanceOf(IllegalStateException.class, err.get());
        assertTrue(err.get().getMessage().contains("not started"));
    }

    @Test
    void listNodes_beforeStart_isEmpty() {
        AeronClusterContainer c = newContainer();
        assertTrue(c.listNodes().isEmpty());
    }

    @Test
    void leaderNode_beforeStart_isNull() {
        AeronClusterContainer c = newContainer();
        assertNull(c.leaderNode());
    }

    @Test
    void isLeader_beforeStart_isFalse() {
        AeronClusterContainer c = newContainer();
        assertFalse(c.isLeader());
    }

    @Test
    void addRoleChangeListener_invokesListenerImmediatelyWithFollower() {
        AeronClusterContainer c = newContainer();
        AtomicReference<RaftNode.NodeType> first = new AtomicReference<>();
        AtomicInteger invocations = new AtomicInteger();
        c.addRoleChangeListener(role -> {
            invocations.incrementAndGet();
            first.compareAndSet(null, role);
        });
        assertEquals(1, invocations.get());
        assertEquals(RaftNode.NodeType.FOLLOWER, first.get(), "初始 isLeader=false，新 listener 必须立即收到 FOLLOWER");
    }

    @Test
    void memberSpec_hostAndAeronBasePortAreParsedFromPeer() {
        AeronClusterContainer.MemberSpec m = new AeronClusterContainer.MemberSpec(0, "10.0.0.5:9100");
        assertEquals("10.0.0.5", m.host());
        assertEquals(9100, m.aeronBasePort());
        assertEquals(0, m.memberId());
    }

    // ---- helpers ----

    private static AeronClusterContainer newContainer() {
        RaftClusterDiscovery discovery = mock(RaftClusterDiscovery.class);
        ExchangeRuntime runtime = mock(ExchangeRuntime.class);
        ExchangeCore core = mock(ExchangeCore.class);
        when(runtime.exchangeCore()).thenReturn(core);
        // getLiquidationEngines returns null → override liquidation submitter 早退
        when(core.getLiquidationEngines()).thenReturn(null);
        return new AeronClusterContainer(discovery, runtime);
    }
}
