package com.binance.raftexchange.server.raft.jraft.closure;

import com.binance.raftexchange.server.raft.RaftResponse;

import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.alipay.sofa.jraft.Status;
import com.binance.raftexchange.server.util.SerializeHelper;
import com.google.protobuf.GeneratedMessageV3;

import exchange.core2.core.common.cmd.OrderCommand;

/**
 * Envelope batch 的 closure：一条 raft entry 内打包多条 {@link PendingCmd}，apply 时按 sub-callback 分别触发。
 * 每个 sub 自带 fired 标记保证 success / failAll / failOne 互斥，apply batch flush 失败时多路径触发 failAll 也幂等。
 */
public final class BatchClosure implements RaftClosure {

    public record PendingCmd(byte[] cmdBytes, GeneratedMessageV3 preParsed,
        BiConsumer<RaftResponse, Throwable> callback, long submitNanos) {
        public PendingCmd(byte[] cmdBytes, GeneratedMessageV3 preParsed, BiConsumer<RaftResponse, Throwable> callback) {
            this(cmdBytes, preParsed, callback, System.nanoTime());
        }
    }

    private final PendingCmd[] cmds;
    private final AtomicIntegerArray fired;
    private final long flushNanos;

    public BatchClosure(PendingCmd[] cmds) {
        this.cmds = cmds;
        this.fired = new AtomicIntegerArray(cmds.length);
        this.flushNanos = System.nanoTime();
    }

    public int size() {
        return cmds.length;
    }

    public PendingCmd at(int index) {
        return cmds[index];
    }

    public Consumer<OrderCommand> subCallback(int index) {
        return cmd -> {
            if (!claim(index)) {
                return;
            }
            fire(index,
                buildResponse(() -> SerializeHelper.serializeToCommandResult(cmd), cmds[index].submitNanos()), null,
                "subCallback");
        };
    }

    public void failOne(int index, Throwable err) {
        if (claim(index)) {
            fire(index, null, err, "failOne");
        }
    }

    public void failAll(Throwable err) {
        for (int i = 0; i < cmds.length; i++) {
            if (claim(i)) {
                fire(i, null, err, "failAll");
            }
        }
    }

    @Override
    public void run(Status status) {
        if (status.isOk()) {
            return;
        }
        failAll(new RuntimeException(status.getErrorMsg()));
    }

    private boolean claim(int index) {
        return fired.compareAndSet(index, 0, 1);
    }

    private RaftResponse buildResponse(Supplier<byte[]> serializer, long submitNanos) {
        long now = System.nanoTime();
        return new RaftResponse(serializer, now - submitNanos, now - flushNanos, 0L);
    }

    private void fire(int index, RaftResponse resp, Throwable err, String op) {
        RaftClosure.safeInvoke(cmds[index].callback(), resp, err, "BatchClosure." + op + "#" + index);
    }
}
