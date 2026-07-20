package com.binance.raftexchange.server.raft.aeron;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.binance.raftexchange.server.exchange.ExchangeCalls;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiNop;
import com.binance.raftexchange.stubs.response.CommandResult;

import exchange.core2.core.ExchangeApi;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;

import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;

/**
 * 不起 Aeron 进程的纯单元测试。验证 framing、CommandRegistry 调度、错误兜底、roleSink 触发。
 *
 * <p>
 * 状态机改异步后，offer 不在 {@code onSessionMessage} 同步触发，要在 drain timer fire 时回放， 所以本测试调 onStart 注入 mock Cluster，再
 * onSessionMessage → onTimerEvent(DRAIN_TIMER_ID, ...) 走完整链路。
 */
class AeronExchangeStateMachineUnitTest {

    private static final long DRAIN_TIMER_ID = -1L;

    @Test
    void onSessionMessage_nop_writesFramedResponseWithSameCorrelationId() throws Exception {
        long sessionId = 7L;
        ClientSession session = mock(ClientSession.class);
        when(session.id()).thenReturn(sessionId);
        when(session.offer(any(DirectBuffer.class), anyInt(), anyInt())).thenReturn(1L);
        Cluster cluster = mockCluster(sessionId, session);

        AeronExchangeStateMachine sm = newStateMachine(b -> {
        });
        sm.onStart(cluster, null);

        long correlationId = 0xDEADBEEFCAFEBABEL;
        UnsafeBuffer in = frame(correlationId, nopCommand().toByteArray());
        sm.onSessionMessage(session, 0L, in, 0, in.capacity(), null);

        // NOP 经 ExchangeCalls.callExchange 完成 future（mock 即时返回），pending 有 response；drain timer fire 后 offer
        sm.onTimerEvent(DRAIN_TIMER_ID, 1L);

        ArgumentCaptor<DirectBuffer> bufCap = ArgumentCaptor.forClass(DirectBuffer.class);
        ArgumentCaptor<Integer> offCap = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> lenCap = ArgumentCaptor.forClass(Integer.class);
        verify(session).offer(bufCap.capture(), offCap.capture(), lenCap.capture());

        DirectBuffer out = bufCap.getValue();
        int off = offCap.getValue();
        int len = lenCap.getValue();
        assertEquals(correlationId, AeronFrame.Egress.correlationId(out, off), "correlationId 必须原样回带");

        // egress 帧头是 [cid][leaderLogPos][engineNanos]（24B）；用 AeronFrame.Egress 解 payload，别手动跳 8B
        byte[] respBytes = AeronFrame.Egress.payload(out, off, len);
        CommandResult.parseFrom(respBytes); // 必须是合法 CommandResult，否则抛
    }

    @Test
    void onSessionMessage_tooShortFrame_dropsWithoutOffer() {
        ClientSession session = mock(ClientSession.class);
        Cluster cluster = mockCluster(1L, session);
        AeronExchangeStateMachine sm = newStateMachine(b -> {
        });
        sm.onStart(cluster, null);

        UnsafeBuffer tooShort = new UnsafeBuffer(new byte[4]);
        sm.onSessionMessage(session, 0L, tooShort, 0, tooShort.capacity(), null);
        sm.onTimerEvent(DRAIN_TIMER_ID, 1L);

        verify(session, never()).offer(any(DirectBuffer.class), anyInt(), anyInt());
    }

    @Test
    void onSessionMessage_malformedProto_stillWritesErrorResponse() {
        long sessionId = 11L;
        ClientSession session = mock(ClientSession.class);
        when(session.id()).thenReturn(sessionId);
        when(session.offer(any(DirectBuffer.class), anyInt(), anyInt())).thenReturn(1L);
        Cluster cluster = mockCluster(sessionId, session);

        AeronExchangeStateMachine sm = newStateMachine(b -> {
        });
        sm.onStart(cluster, null);

        long correlationId = 42L;
        UnsafeBuffer in = frame(correlationId, new byte[] {(byte)0xFF, 0x00, 0x01, 0x02});
        sm.onSessionMessage(session, 0L, in, 0, in.capacity(), null);
        sm.onTimerEvent(DRAIN_TIMER_ID, 1L);

        ArgumentCaptor<DirectBuffer> cap = ArgumentCaptor.forClass(DirectBuffer.class);
        ArgumentCaptor<Integer> off = ArgumentCaptor.forClass(Integer.class);
        verify(session).offer(cap.capture(), off.capture(), anyInt());
        assertEquals(correlationId, cap.getValue().getLong(off.getValue()), "malformed proto 也应回带 correlationId");
    }

    @Test
    void onSessionMessage_schedulesTimerOnFirstCall() {
        ClientSession session = mock(ClientSession.class);
        when(session.id()).thenReturn(1L);
        Cluster cluster = mockCluster(1L, session);
        AeronExchangeStateMachine sm = newStateMachine(b -> {
        });
        sm.onStart(cluster, null);
        verify(cluster, never()).scheduleTimer(anyLong(), anyLong());

        UnsafeBuffer in = frame(1L, nopCommand().toByteArray());
        sm.onSessionMessage(session, 0L, in, 0, in.capacity(), null);
        verify(cluster).scheduleTimer(eqDrainId(), anyLong());

        sm.onSessionMessage(session, 0L, in, 0, in.capacity(), null);
        verify(cluster).scheduleTimer(eqDrainId(), anyLong()); // 第二条消息不重复调度
    }

    @Test
    void onRoleChange_pushesLeaderFollowerToRoleSink() {
        AtomicReference<Boolean> last = new AtomicReference<>();
        AeronExchangeStateMachine sm = newStateMachine(last::set);
        Cluster cluster = mockCluster(0L, mock(ClientSession.class));
        sm.onStart(cluster, null);

        sm.onRoleChange(Cluster.Role.LEADER);
        assertTrue(last.get());

        sm.onRoleChange(Cluster.Role.FOLLOWER);
        assertFalse(last.get());

        sm.onRoleChange(Cluster.Role.CANDIDATE);
        assertFalse(last.get(), "非 LEADER 都视为非 leader");
    }

    @Test
    void onRoleChange_noListeners_noNpe() {
        AeronExchangeStateMachine sm = newStateMachine(null);
        sm.onStart(mockCluster(0L, mock(ClientSession.class)), null);
        assertDoesNotThrow(() -> sm.onRoleChange(Cluster.Role.LEADER));
    }

    @Test
    void onRoleChange_listenerThrows_swallowed() {
        AeronExchangeStateMachine sm = newStateMachine(b -> {
            throw new RuntimeException("boom");
        });
        sm.onStart(mockCluster(0L, mock(ClientSession.class)), null);
        assertDoesNotThrow(() -> sm.onRoleChange(Cluster.Role.LEADER));
    }

    @Test
    void onSessionOpenAndClose_doNotThrow() {
        AeronExchangeStateMachine sm = newStateMachine(b -> {
        });
        ClientSession session = mock(ClientSession.class);
        when(session.id()).thenReturn(1L);
        assertDoesNotThrow(() -> {
            sm.onSessionOpen(session, 0L);
            sm.onSessionClose(session, 0L, CloseReason.CLIENT_ACTION);
        });
    }

    @Test
    void onTerminate_doNotThrow() {
        AeronExchangeStateMachine sm = newStateMachine(b -> {
        });
        assertDoesNotThrow(() -> sm.onTerminate(null));
    }

    // ---- helpers ----

    private static AeronExchangeStateMachine newStateMachine(Consumer<Boolean> roleListener) {
        Path tmp;
        try {
            tmp = Files.createTempDirectory("aeron-sm-test-snapshots");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        tmp.toFile().deleteOnExit();
        SnapshotFetcher noopFetcher = (id, dir) -> {
            throw new IllegalStateException("fetcher should not be invoked in unit tests");
        };
        // 命令经 ExchangeCalls → api.submitCommandAsyncFullResponse（含 NOP，走 callExchange）；mock 须返回已完成 future，否则 thenApply NPE
        ExchangeApi api = mock(ExchangeApi.class);
        OrderCommand done = new OrderCommand(1);
        done.command = OrderCommandType.NOP;
        done.resultCode = CommandResultCode.SUCCESS;
        when(api.submitCommandAsyncFullResponse(any())).thenReturn(CompletableFuture.completedFuture(done));
        AeronExchangeStateMachine sm = new AeronExchangeStateMachine(new ExchangeCalls(api), tmp, noopFetcher);
        if (roleListener != null) {
            sm.addRoleChangeListener(roleListener);
        }
        return sm;
    }

    private static Cluster mockCluster(long sessionId, ClientSession session) {
        Cluster c = mock(Cluster.class);
        when(c.memberId()).thenReturn(0);
        when(c.role()).thenReturn(Cluster.Role.LEADER);
        when(c.time()).thenReturn(0L);
        when(c.scheduleTimer(anyLong(), anyLong())).thenReturn(true);
        when(c.getClientSession(sessionId)).thenReturn(session);
        return c;
    }

    private static long eqDrainId() {
        return org.mockito.ArgumentMatchers.longThat(v -> v == DRAIN_TIMER_ID);
    }

    private static UnsafeBuffer frame(long correlationId, byte[] payload) {
        UnsafeBuffer buf = new UnsafeBuffer(new byte[Long.BYTES + payload.length]);
        buf.putLong(0, correlationId);
        buf.putBytes(Long.BYTES, payload);
        return buf;
    }

    private static ApiCommand nopCommand() {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis()).setNop(ApiNop.getDefaultInstance())
            .build();
    }
}
