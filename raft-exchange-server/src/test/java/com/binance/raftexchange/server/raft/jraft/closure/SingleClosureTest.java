package com.binance.raftexchange.server.raft.jraft.closure;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.error.RaftError;
import com.binance.raftexchange.server.raft.RaftResponse;

import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;

class SingleClosureTest {

    @Test
    void acceptSwallowsThrow() {
        BiConsumer<RaftResponse, Throwable> cb = (r, e) -> {
            throw new RuntimeException("boom");
        };
        SingleClosure rc = new SingleClosure(cb, null);
        rc.setApplyNanos(System.nanoTime());

        OrderCommand cmd = new OrderCommand(1);
        cmd.command = OrderCommandType.PLACE_ORDER;
        cmd.resultCode = CommandResultCode.SUCCESS;

        rc.accept(cmd);
    }

    @Test
    void acceptFutureSuccessSwallowsThrow() {
        BiConsumer<RaftResponse, Throwable> cb = (r, e) -> {
            throw new RuntimeException("boom");
        };
        SingleClosure rc = new SingleClosure(cb, null);
        rc.setApplyNanos(System.nanoTime());

        CompletableFuture<Supplier<byte[]>> fut = CompletableFuture.completedFuture(() -> new byte[] {1, 2, 3});
        rc.acceptFuture(fut);
    }

    @Test
    void acceptFutureFailureSwallowsThrow() {
        BiConsumer<RaftResponse, Throwable> cb = (r, e) -> {
            throw new RuntimeException("boom");
        };
        SingleClosure rc = new SingleClosure(cb, null);
        rc.setApplyNanos(System.nanoTime());

        CompletableFuture<Supplier<byte[]>> fut = new CompletableFuture<>();
        fut.completeExceptionally(new RuntimeException("upstream fail"));
        rc.acceptFuture(fut);
    }

    @Test
    void completeExceptionallySwallowsThrow() {
        BiConsumer<RaftResponse, Throwable> cb = (r, e) -> {
            throw new RuntimeException("boom");
        };
        SingleClosure rc = new SingleClosure(cb, null);
        rc.completeExceptionally(new RuntimeException("err"));
    }

    @Test
    void runFailureSwallowsThrow() {
        BiConsumer<RaftResponse, Throwable> cb = (r, e) -> {
            throw new RuntimeException("boom");
        };
        SingleClosure rc = new SingleClosure(cb, null);
        rc.run(new Status(RaftError.EPERM, "leader stepped down"));
    }

    @Test
    void runOkIsNoop() {
        AtomicReference<Throwable> err = new AtomicReference<>();
        AtomicReference<RaftResponse> resp = new AtomicReference<>();
        BiConsumer<RaftResponse, Throwable> cb = (r, e) -> {
            err.set(e);
            resp.set(r);
        };
        SingleClosure rc = new SingleClosure(cb, null);

        rc.run(Status.OK());

        assertNull(err.get(), "run(OK) 不应触发 callback");
        assertNull(resp.get(), "run(OK) 不应触发 callback");
    }

    @Test
    void acceptDeliversResponseOnHappyPath() {
        AtomicReference<RaftResponse> resp = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        BiConsumer<RaftResponse, Throwable> cb = (r, e) -> {
            resp.set(r);
            err.set(e);
        };
        SingleClosure rc = new SingleClosure(cb, null);
        rc.setApplyNanos(System.nanoTime());

        OrderCommand cmd = new OrderCommand(1);
        cmd.command = OrderCommandType.PLACE_ORDER;
        cmd.resultCode = CommandResultCode.SUCCESS;

        rc.accept(cmd);

        assertNotNull(resp.get());
        assertNull(err.get());
        assertNotNull(resp.get().serializer());
    }

    @Test
    void runFailurePropagatesErrorMessage() {
        AtomicReference<Throwable> err = new AtomicReference<>();
        BiConsumer<RaftResponse, Throwable> cb = (r, e) -> err.set(e);
        SingleClosure rc = new SingleClosure(cb, null);

        Status status = new Status(RaftError.EPERM, "stepped down");
        rc.run(status);

        assertNotNull(err.get());
        assertSame(RuntimeException.class, err.get().getClass());
    }
}
