package com.binance.raftexchange.client.grpc;

import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.response.CommandResult;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * ApiStream 是写命令流的"重连贴片"：leader 切换时 ExchangeClient 会调 {@link ApiStream#replaceInternalObserver(StreamObserver)} 把底层
 * gRPC observer 换掉。 这里钉死的 invariant：
 * <ul>
 * <li>第一次 replace（current=null）只装新的，不在不存在的旧 observer 上 onCompleted</li>
 * <li>第二次 replace 必须 onCompleted 旧 observer，让对端释放该 stream</li>
 * <li>replace 之后 onNext 路由到新 observer，旧 observer 不再收 onNext</li>
 * <li>未初始化就 onNext → IllegalStateException（暴露调用顺序错，不要 NPE 神秘失败）</li>
 * </ul>
 *
 * <p>
 * 注：grpc StreamObserver 规范本身就要求 caller 单线程串行，所以这里不再额外做 "replace 并发 onNext" 的 race 隔离——AtomicReference swap 已经把窗口缩到最小。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ApiStreamTest {

    @Mock
    private ExchangeClient root;
    @Mock
    private StreamObserver<CommandResult> resultObserver;
    @Mock
    private StreamObserver<ApiCommand> oldObserver;
    @Mock
    private StreamObserver<ApiCommand> newObserver;

    private ApiStream stream;

    @BeforeEach
    void setUp() {
        stream = new ApiStream(root, resultObserver);
    }

    @Test
    @DisplayName("未初始化 internalObserver 调 onNext → IllegalStateException")
    void onNextBeforeInitThrows() {
        ApiCommand cmd = ApiCommand.getDefaultInstance();
        assertThrows(IllegalStateException.class, () -> stream.onNext(cmd));
    }

    @Test
    @DisplayName("首次 replace（current=null）只装新的，不在不存在的旧 observer 上 onCompleted")
    void firstReplaceJustInstalls() {
        stream.replaceInternalObserver(newObserver);
        // 没有任何旧 observer 可被关
        verify(newObserver, never()).onCompleted();
        // 此后 onNext 走到新 observer
        ApiCommand cmd = ApiCommand.getDefaultInstance();
        stream.onNext(cmd);
        verify(newObserver).onNext(cmd);
    }

    @Test
    @DisplayName("二次 replace：旧 observer 收 onCompleted，新 observer 接管 onNext")
    void secondReplaceClosesOldAndRoutesToNew() {
        stream.replaceInternalObserver(oldObserver);
        stream.replaceInternalObserver(newObserver);

        verify(oldObserver).onCompleted();

        ApiCommand cmd = ApiCommand.getDefaultInstance();
        stream.onNext(cmd);
        verify(newObserver).onNext(cmd);
        // 关键：onNext 不能再落到旧 observer 上
        verify(oldObserver, never()).onNext(cmd);
    }

    @Test
    @DisplayName("onError 路由到当前 observer")
    void onErrorRoutesToCurrent() {
        stream.replaceInternalObserver(newObserver);
        RuntimeException err = new RuntimeException("boom");
        stream.onError(err);
        verify(newObserver).onError(err);
    }

    @Test
    @DisplayName("onCompleted 路由到当前 observer，并触发 root.endStream")
    void onCompletedRoutesAndUnregisters() {
        stream.replaceInternalObserver(newObserver);
        stream.onCompleted();
        verify(newObserver).onCompleted();
        verify(root).endStream(stream);
    }

    @Test
    @DisplayName("stress：并发 onNext + replace 时，没有一条 onNext 落到已 onCompleted 的旧 observer 上")
    void noOnNextEverArrivesAtCompletedObserver() throws Exception {
        // 反复跑 200 轮，每轮 dispatcher 跟 replacer 两线程同时发车。
        // 用 TrackingObserver 自验"onNext 不可能在 onCompleted 之后到达"。
        int iterations = 200;
        ApiCommand cmd = ApiCommand.getDefaultInstance();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < iterations; i++) {
                ApiStream s = new ApiStream(root, resultObserver);
                TrackingObserver obs1 = new TrackingObserver();
                TrackingObserver obs2 = new TrackingObserver();
                s.replaceInternalObserver(obs1);

                CountDownLatch go = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(2);

                pool.submit(() -> {
                    try {
                        go.await();
                        for (int n = 0; n < 100; n++) {
                            // race 修好以前这里会偶发把 onNext 落到 obs1 onCompleted 之后
                            try {
                                s.onNext(cmd);
                            } catch (RuntimeException ignored) {
                            }
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
                pool.submit(() -> {
                    try {
                        go.await();
                        s.replaceInternalObserver(obs2);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });

                go.countDown();
                assertTrue(done.await(5, TimeUnit.SECONDS), "stress 迭代 " + i + " 没完成");

                // TrackingObserver 自动捕获"onCompleted 之后又来了 onNext"
                assertNull(obs1.violation.get(),
                    "iter=" + i + " 旧 observer 收到 onCompleted 之后的 onNext: " + obs1.violation.get());
                assertNull(obs2.violation.get(),
                    "iter=" + i + " 新 observer 收到 onCompleted 之后的 onNext: " + obs2.violation.get());
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("replaceInternalObserver 持写锁期间，并发 onNext 必须阻塞等到锁释放")
    void readLockBlockedWhileWriteLockHeld() throws Exception {
        // 设计：replacer 先冲；oldObserver.onCompleted 故意 sleep 200ms 模拟写锁内做事。
        // dispatcher 在 replacer 抢到写锁之后启动 onNext —— 必须阻塞，等写锁释放。
        AtomicReference<Long> nextReturned = new AtomicReference<>();
        AtomicReference<Long> oldCompletedExit = new AtomicReference<>();
        ApiStream s = new ApiStream(root, resultObserver);

        // 信号：oldSlow.onCompleted 已经开始（说明 replacer 已持有写锁）
        CountDownLatch oldCompleteStarted = new CountDownLatch(1);
        StreamObserver<ApiCommand> oldSlow = new StreamObserver<ApiCommand>() {
            @Override
            public void onNext(ApiCommand cmd) {}

            @Override
            public void onError(Throwable t) {}

            @Override
            public void onCompleted() {
                oldCompleteStarted.countDown();
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                oldCompletedExit.set(System.nanoTime());
            }
        };
        s.replaceInternalObserver(oldSlow);

        TrackingObserver newObs = new TrackingObserver();

        Thread replacer = new Thread(() -> s.replaceInternalObserver(newObs), "replacer");
        Thread dispatcher = new Thread(() -> {
            try {
                oldCompleteStarted.await();
            } // 等 replacer 已持锁
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // 此时写锁还在持有中（onCompleted 还在 sleep），这一句必须阻塞 ~200ms
            s.onNext(ApiCommand.getDefaultInstance());
            nextReturned.set(System.nanoTime());
        }, "dispatcher");

        replacer.start();
        dispatcher.start();
        replacer.join(5_000);
        dispatcher.join(5_000);

        assertTrue(oldCompletedExit.get() != null && nextReturned.get() != null, "两个线程都应该跑完");
        assertTrue(nextReturned.get() >= oldCompletedExit.get(),
            "onNext 返回 (" + nextReturned.get() + ") 必须晚于 oldObserver.onCompleted 结束 (" + oldCompletedExit.get() + ")");
        assertTrue(newObs.onNextCount.get() == 1, "onNext 必须落到新 observer，onNextCount=" + newObs.onNextCount.get());
    }

    /**
     * 自验性 StreamObserver：onCompleted 之后再收到 onNext 会自动记下违例。
     */
    private static final class TrackingObserver implements StreamObserver<ApiCommand> {
        final AtomicBoolean completed = new AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicInteger onNextCount = new java.util.concurrent.atomic.AtomicInteger();
        final AtomicReference<String> violation = new AtomicReference<>();

        @Override
        public void onNext(ApiCommand cmd) {
            if (completed.get()) {
                violation.compareAndSet(null, "onNext after onCompleted");
            }
            onNextCount.incrementAndGet();
        }

        @Override
        public void onError(Throwable t) {}

        @Override
        public void onCompleted() {
            completed.set(true);
        }
    }
}
