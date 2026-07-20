package com.binance.raftexchange.server.raft.jraft.closure;

import com.binance.raftexchange.server.raft.RaftResponse;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.alipay.sofa.jraft.Status;
import com.binance.raftexchange.server.util.SerializeHelper;
import com.google.protobuf.GeneratedMessageV3;

import exchange.core2.core.common.cmd.OrderCommand;

/**
 * 单条 raft 命令的 closure：raft replicate 完成或 fsm apply 完成时通知调用方。
 *
 * <p>
 * 同时实现 {@link Consumer}&lt;OrderCommand&gt;——engine apply 结果直接 {@code accept} 进来 → 序列化 → 触发 callback。
 * pre-parsed message 缓存避开 fsm apply 时再反序列化一次。延迟 metric 覆盖 submit→accept 和 apply→accept 两段。
 */
public final class SingleClosure implements RaftClosure, Consumer<OrderCommand> {
    private final BiConsumer<RaftResponse, Throwable> callback;
    private final GeneratedMessageV3 message;
    private final long submitNanos;
    private long applyNanos;

    public SingleClosure(BiConsumer<RaftResponse, Throwable> callback, GeneratedMessageV3 message) {
        this.callback = callback;
        this.message = message;
        this.submitNanos = System.nanoTime();
    }

    public GeneratedMessageV3 message() {
        return message;
    }

    public void setApplyNanos(long applyNanos) {
        this.applyNanos = applyNanos;
    }

    @Override
    public void accept(OrderCommand cmd) {
        fire(buildResponse(() -> SerializeHelper.serializeToCommandResult(cmd)), null, "accept");
    }

    public void acceptFuture(CompletableFuture<Supplier<byte[]>> serializerFuture) {
        serializerFuture.whenComplete((serializer, err) -> {
            if (err != null) {
                fire(null, err, "acceptFuture");
            } else {
                fire(buildResponse(serializer), null, "acceptFuture");
            }
        });
    }

    public void completeExceptionally(Throwable err) {
        fire(null, err, "completeExceptionally");
    }

    @Override
    public void run(Status status) {
        if (status.isOk()) {
            return;
        }
        fire(null, new RuntimeException(status.getErrorMsg()), "run");
    }

    private RaftResponse buildResponse(Supplier<byte[]> serializer) {
        long now = System.nanoTime();
        return new RaftResponse(serializer, now - submitNanos, now - applyNanos, 0L);
    }

    private void fire(RaftResponse resp, Throwable err, String op) {
        RaftClosure.safeInvoke(callback, resp, err, "SingleClosure." + op);
    }
}
