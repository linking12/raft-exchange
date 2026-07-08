package exchange.core2.core.processors.liquidation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiredArgsConstructor
@Slf4j
public abstract class SimpleScheduledService {

    private final long delay;
    private final TimeUnit unit;
    private final ThreadFactory threadFactory;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;

    private final AtomicBoolean running = new AtomicBoolean(false);

    protected SimpleScheduledService(long delay, TimeUnit unit, String name, ThreadFactory threadFactory) {
        this(delay, unit, threadFactory);
    }

    /**
     * 要实现的业务逻辑
     */
    protected abstract void runOneIteration() throws Exception;

    /**
     * 可选：启动前动作
     */
    protected void beforeStart() {}

    /**
     * 可选：停止后动作
     */
    protected void afterStop() {}

    /**
     * 异常默认打印，可重写
     */
    protected void handleError(Throwable t) {
        log.warn("Scheduled service error: ", t);
    }

    /**
     * ======== 生命周期 ========
     */

    public synchronized void start() {
        if (running.get()) {
            return;
        }

        running.set(true);

        beforeStart();

        scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory);

        future = scheduler.scheduleWithFixedDelay(() -> {
            try {
                runOneIteration();
            } catch (Throwable t) {
                handleError(t);
            }
        }, delay, delay, unit);
    }

    public synchronized void stop() {
        stop(1, TimeUnit.MINUTES);
    }

    public synchronized void stop(long timeout, TimeUnit timeUnit) {
        if (!running.get()) {
            return;
        }

        running.set(false);

        if (future != null) {
            future.cancel(false);
        }

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(timeout, timeUnit)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        afterStop();
    }

    public boolean isRunning() {
        return running.get();
    }
}