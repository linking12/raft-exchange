package com.binance.raftexchange.server.raft.jraft.closure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.Test;

import com.alipay.sofa.jraft.Status;
import com.alipay.sofa.jraft.error.RaftError;
import com.binance.raftexchange.server.raft.RaftResponse;
import com.binance.raftexchange.server.raft.jraft.closure.BatchClosure.PendingCmd;

import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;

class BatchClosureTest {

    @Test
    void runFailureTriggersAllCallbacks() {
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger errCount = new AtomicInteger();
        BiConsumer<RaftResponse, Throwable> cb = (resp, err) -> {
            if (err != null)
                errCount.incrementAndGet();
            else
                okCount.incrementAndGet();
        };
        BatchClosure mc = new BatchClosure(new PendingCmd[] {new PendingCmd(new byte[] {1}, null, cb),
            new PendingCmd(new byte[] {2}, null, cb), new PendingCmd(new byte[] {3}, null, cb)});

        mc.run(new Status(RaftError.EPERM, "leader stepped down"));

        assertEquals(3, errCount.get());
        assertEquals(0, okCount.get());
    }

    @Test
    void runOkIsNoop() {
        AtomicInteger calls = new AtomicInteger();
        BiConsumer<RaftResponse, Throwable> cb = (resp, err) -> calls.incrementAndGet();
        BatchClosure mc = new BatchClosure(
            new PendingCmd[] {new PendingCmd(new byte[] {1}, null, cb), new PendingCmd(new byte[] {2}, null, cb)});

        mc.run(Status.OK());

        assertEquals(0, calls.get(), "raft OK 时由 sub callback 单独触发，run(OK) 不动");
    }

    @Test
    void failOneIsolatesOtherSubs() {
        AtomicReference<Throwable> err0 = new AtomicReference<>();
        AtomicReference<Throwable> err1 = new AtomicReference<>();
        BatchClosure mc =
            new BatchClosure(new PendingCmd[] {new PendingCmd(new byte[] {1}, null, (r, e) -> err0.set(e)),
                new PendingCmd(new byte[] {2}, null, (r, e) -> err1.set(e))});

        RuntimeException boom = new RuntimeException("boom");
        mc.failOne(0, boom);

        assertSame(boom, err0.get());
        assertNull(err1.get(), "其他 sub 不应被影响");
    }

    @Test
    void failAllTriggersEvery() {
        AtomicInteger errCount = new AtomicInteger();
        BiConsumer<RaftResponse, Throwable> cb = (resp, err) -> {
            if (err != null)
                errCount.incrementAndGet();
        };
        BatchClosure mc = new BatchClosure(
            new PendingCmd[] {new PendingCmd(new byte[] {1}, null, cb), new PendingCmd(new byte[] {2}, null, cb),
                new PendingCmd(new byte[] {3}, null, cb), new PendingCmd(new byte[] {4}, null, cb)});

        mc.failAll(new RuntimeException("bulk fail"));

        assertEquals(4, errCount.get());
    }

    @Test
    void subCallbackBuildsRaftResponse() {
        AtomicReference<RaftResponse> resp0 = new AtomicReference<>();
        AtomicReference<RaftResponse> resp1 = new AtomicReference<>();
        BatchClosure mc =
            new BatchClosure(new PendingCmd[] {new PendingCmd(new byte[] {1}, null, (r, e) -> resp0.set(r)),
                new PendingCmd(new byte[] {2}, null, (r, e) -> resp1.set(r))});

        OrderCommand cmd0 = new OrderCommand(1);
        cmd0.command = OrderCommandType.PLACE_ORDER;
        cmd0.resultCode = CommandResultCode.SUCCESS;
        mc.subCallback(0).accept(cmd0);

        assertNotNull(resp0.get());
        assertNotNull(resp0.get().serializer());
        assertNull(resp1.get(), "subCallback(0) 只触发第 0 个 sub 的 callback");
    }

    @Test
    void sizeAndAtMatchCtorArray() {
        PendingCmd p0 = new PendingCmd(new byte[] {1}, null, (r, e) -> {
        });
        PendingCmd p1 = new PendingCmd(new byte[] {2}, null, (r, e) -> {
        });
        BatchClosure mc = new BatchClosure(new PendingCmd[] {p0, p1});

        assertEquals(2, mc.size());
        assertSame(p0, mc.at(0));
        assertSame(p1, mc.at(1));
    }

    @Test
    void pendingCmdConvenienceCtorCapturesTimestamp() {
        long before = System.nanoTime();
        PendingCmd p = new PendingCmd(new byte[] {1}, null, (r, e) -> {
        });
        long after = System.nanoTime();
        assertEquals(true, p.submitNanos() >= before && p.submitNanos() <= after);
    }

    @Test
    void subCallbackSwallowsThrow() {
        AtomicInteger reached = new AtomicInteger();
        BatchClosure mc = new BatchClosure(new PendingCmd[] {new PendingCmd(new byte[] {1}, null, (r, e) -> {
            throw new RuntimeException("boom");
        }), new PendingCmd(new byte[] {2}, null, (r, e) -> reached.incrementAndGet())});

        OrderCommand cmd = new OrderCommand(1);
        cmd.command = OrderCommandType.PLACE_ORDER;
        cmd.resultCode = CommandResultCode.SUCCESS;

        mc.subCallback(0).accept(cmd);
        mc.subCallback(1).accept(cmd);

        assertEquals(1, reached.get(), "sub0 抛异常被吞掉后 sub1 仍能正常触发");
    }

    @Test
    void failOneSwallowsThrow() {
        AtomicInteger reached = new AtomicInteger();
        BatchClosure mc = new BatchClosure(new PendingCmd[] {new PendingCmd(new byte[] {1}, null, (r, e) -> {
            throw new RuntimeException("boom");
        }), new PendingCmd(new byte[] {2}, null, (r, e) -> reached.incrementAndGet())});

        mc.failOne(0, new RuntimeException("err"));
        mc.failOne(1, new RuntimeException("err"));

        assertEquals(1, reached.get(), "failOne(0) 抛异常被吞掉后 failOne(1) 仍能触发");
    }

    @Test
    void subCallbackAndFailAllAreMutuallyIdempotent() {
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger errCount = new AtomicInteger();
        BiConsumer<RaftResponse, Throwable> cb = (r, e) -> {
            if (e != null) errCount.incrementAndGet(); else okCount.incrementAndGet();
        };
        BatchClosure mc = new BatchClosure(new PendingCmd[] {new PendingCmd(new byte[] {1}, null, cb),
            new PendingCmd(new byte[] {2}, null, cb), new PendingCmd(new byte[] {3}, null, cb)});
        OrderCommand cmd = new OrderCommand(1);
        cmd.command = OrderCommandType.PLACE_ORDER;
        cmd.resultCode = CommandResultCode.SUCCESS;

        mc.subCallback(0).accept(cmd);
        mc.failAll(new RuntimeException("late fail"));
        mc.failAll(new RuntimeException("redundant"));
        mc.subCallback(1).accept(cmd);

        assertEquals(1, okCount.get(), "sub0 success 后 failAll 不能重复触发 sub0");
        assertEquals(2, errCount.get(), "failAll 只触发尚未 fired 的 sub1/sub2，第二次 failAll 和 sub1.accept 都被吞");
    }

    @Test
    void failOneBlocksSubsequentSubCallback() {
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger errCount = new AtomicInteger();
        BiConsumer<RaftResponse, Throwable> cb = (r, e) -> {
            if (e != null) errCount.incrementAndGet(); else okCount.incrementAndGet();
        };
        BatchClosure mc = new BatchClosure(
            new PendingCmd[] {new PendingCmd(new byte[] {1}, null, cb), new PendingCmd(new byte[] {2}, null, cb)});
        OrderCommand cmd = new OrderCommand(1);
        cmd.command = OrderCommandType.PLACE_ORDER;
        cmd.resultCode = CommandResultCode.SUCCESS;

        mc.failOne(0, new RuntimeException("rejected"));
        mc.subCallback(0).accept(cmd);

        assertEquals(1, errCount.get());
        assertEquals(0, okCount.get(), "failOne 后 subCallback 同 index 再触发应被吞");
    }

    @Test
    void failAllContinuesAfterCallbackThrows() {
        AtomicInteger reached = new AtomicInteger();
        BiConsumer<RaftResponse, Throwable> throwing = (r, e) -> {
            throw new RuntimeException("boom");
        };
        BiConsumer<RaftResponse, Throwable> counter = (r, e) -> reached.incrementAndGet();
        BatchClosure mc = new BatchClosure(new PendingCmd[] {new PendingCmd(new byte[] {1}, null, counter),
            new PendingCmd(new byte[] {2}, null, throwing), new PendingCmd(new byte[] {3}, null, counter),
            new PendingCmd(new byte[] {4}, null, throwing), new PendingCmd(new byte[] {5}, null, counter)});

        mc.failAll(new RuntimeException("bulk"));

        assertEquals(3, reached.get(), "中间 callback 抛异常不影响后续 callback 被调用");
    }
}
