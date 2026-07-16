package exchange.core2.core.processors.liquidation;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.api.ApiLiquidationScan;
import exchange.core2.core.common.api.ApiRepriceLoanRates;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * 强平发令器父类：off-lane、leader-local 的定时调度骨架。
 *
 * <p>
 * <b>只发命令、绝不读用户态</b>——shard 0 每 tick submit {@code LIQUIDATION_SCAN}（全量兜底整扫）与
 * {@code REPRICE_LOAN_RATES}（动态利率重定价）。真正的检测/扫描都在子类的 on-lane apply 路径里跑（由
 * {@code cmd.timestamp} 驱动）；命令经 raft 复制后各节点确定性 apply，从根上消除调度线程与 apply 线程之间的竞态。
 *
 * <p>
 * {@link #isRunning()}（start→true / stop→false）复用为 leader gate：server 按 leader 身份 start/stop。
 */
@Slf4j
public abstract class LiquidationScheduledService {
    private final long delay;
    private final TimeUnit unit;
    private final ThreadFactory threadFactory;
    @Getter
    protected final int shardId;
    @Setter
    @Getter
    protected LiquidationCommandSubmitter commandSubmitter;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;
    private final AtomicBoolean running = new AtomicBoolean(false);

    protected LiquidationScheduledService(long delay, TimeUnit unit, ThreadFactory threadFactory, int shardId) {
        this.delay = delay;
        this.unit = unit;
        this.threadFactory = threadFactory;
        this.shardId = shardId;
    }

    /** 每 tick 的固定发令；仅 shard 0 触发——其余分片没有全局兜底职责。 */
    protected void runOneIteration() {
        if (shardId != 0) {
            return;
        }
        submit(ApiLiquidationScan.builder().build(), null);
        submit(ApiRepriceLoanRates.builder().build(), null);
    }

    /** 经 submitter 提交命令；submitter 尚未 set（未就绪）时 no-op。 */
    protected final void submit(ApiCommand cmd, Runnable onApplied) {
        if (commandSubmitter != null) {
            commandSubmitter.submit(cmd, onApplied);
        }
    }

    protected void beforeStart() {}

    protected void afterStop() {}

    protected void handleError(Throwable t) {
        log.warn("Scheduled service error: ", t);
    }

    /** 幂等启动：已运行则 no-op；否则起单线程调度器，按固定延迟循环跑 {@link #runOneIteration()}。 */
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

    /** 停止并等待收尾，默认超时 1 分钟。 */
    public synchronized void stop() {
        stop(1, TimeUnit.MINUTES);
    }

    /** 幂等停止：取消定时任务、关闭调度器（超时未退则强制 shutdownNow），再回调 {@link #afterStop()}。 */
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
