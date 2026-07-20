package com.binance.raftexchange.client.grpc;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.SettableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExchangeReadOnlyClient#toCompletable} 是把 5 个只读 RPC 都共用的 {@code ListenableFuture → CompletableFuture} 适配器（合并掉原来
 * 2 段重复代码）。 这里直接测它的 success / failure / 既已完成 三种状态传递。
 */
class ExchangeReadOnlyClientTest {

    @Test
    @DisplayName("success：ListenableFuture.set → CompletableFuture.complete")
    void propagatesSuccess() throws ExecutionException, InterruptedException {
        SettableFuture<String> source = SettableFuture.create();
        CompletableFuture<String> target = ExchangeReadOnlyClient.toCompletable(source);

        assertFalse(target.isDone());
        source.set("hello");
        assertTrue(target.isDone());
        assertEquals("hello", target.get());
    }

    @Test
    @DisplayName("failure：ListenableFuture.setException → CompletableFuture.completeExceptionally")
    void propagatesFailure() {
        SettableFuture<String> source = SettableFuture.create();
        CompletableFuture<String> target = ExchangeReadOnlyClient.toCompletable(source);

        RuntimeException boom = new RuntimeException("boom");
        source.setException(boom);

        assertTrue(target.isCompletedExceptionally());
        CompletionException thrown = assertThrows(CompletionException.class, target::join);
        assertSame(boom, thrown.getCause(), "原始异常必须穿透，不能被包一层");
    }

    @Test
    @DisplayName("即时完成的 ListenableFuture：CompletableFuture 也立即完成")
    void immediateFutureCompletesImmediately() throws Exception {
        CompletableFuture<Integer> target = ExchangeReadOnlyClient.toCompletable(Futures.immediateFuture(42));
        assertTrue(target.isDone());
        assertEquals(42, target.get());
    }

    @Test
    @DisplayName("即时失败的 ListenableFuture：CompletableFuture 立即异常完成")
    void immediateFailedFutureFailsImmediately() {
        Exception ex = new Exception("fail");
        CompletableFuture<Integer> target = ExchangeReadOnlyClient.toCompletable(Futures.immediateFailedFuture(ex));
        assertTrue(target.isCompletedExceptionally());
        CompletionException thrown = assertThrows(CompletionException.class, target::join);
        assertSame(ex, thrown.getCause());
    }
}
