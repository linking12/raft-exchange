package com.binance.raftexchange.server.util;

import com.binance.raftexchange.stubs.response.CommandResult;
import com.binance.raftexchange.stubs.response.CommandResultCode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtoBuilderPoolTest {

    @Test
    void get_unregistered_throws() {
        ProtoBuilderPool pool = new ProtoBuilderPool();
        assertThrows(IllegalStateException.class, () -> pool.get(CommandResult.Builder.class));
    }

    @Test
    void get_afterRegister_returnsBuilder() {
        ProtoBuilderPool pool = new ProtoBuilderPool();
        pool.register(CommandResult.Builder.class, CommandResult::newBuilder);

        CommandResult.Builder b = pool.get(CommandResult.Builder.class);

        assertNotNull(b);
    }

    @Test
    void get_secondCallSameThread_reusesAndClearsBuilder() {
        ProtoBuilderPool pool = new ProtoBuilderPool();
        pool.register(CommandResult.Builder.class, CommandResult::newBuilder);

        CommandResult.Builder first = pool.get(CommandResult.Builder.class);
        first.setResultCode(CommandResultCode.NO_LEADER);

        CommandResult.Builder second = pool.get(CommandResult.Builder.class);

        assertSame(first, second, "同线程必须复用同一 builder 实例（FastThreadLocal）");
        assertEquals(CommandResultCode.values()[0], second.getResultCode(),
            "get() 必须 clear()，下一个 caller 拿到的是 proto 默认值（0-valued enum）");
    }

    @Test
    void get_differentThreads_returnDifferentBuilders() throws Exception {
        ProtoBuilderPool pool = new ProtoBuilderPool();
        pool.register(CommandResult.Builder.class, CommandResult::newBuilder);

        CommandResult.Builder main = pool.get(CommandResult.Builder.class);

        AtomicReference<CommandResult.Builder> other = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            other.set(pool.get(CommandResult.Builder.class));
            done.countDown();
        });
        t.start();
        done.await();

        assertNotSame(main, other.get(), "不同线程的 ThreadLocal 必须互相隔离");
    }

    @Test
    void register_twice_secondCallIgnored() {
        ProtoBuilderPool pool = new ProtoBuilderPool();
        pool.register(CommandResult.Builder.class, CommandResult::newBuilder);

        CommandResult.Builder first = pool.get(CommandResult.Builder.class);

        // 第二次 register 应被 putIfAbsent 忽略
        pool.register(CommandResult.Builder.class, () -> {
            throw new AssertionError("第二次 register 不应被使用");
        });

        // 再 get 仍走第一次注册的 supplier；同线程拿到的是已缓存的实例
        assertSame(first, pool.get(CommandResult.Builder.class));
    }
}
