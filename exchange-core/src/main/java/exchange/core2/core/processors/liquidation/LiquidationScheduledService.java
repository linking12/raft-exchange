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
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * 强平发令器父类：off-lane、leader-local 的定时调度骨架。
 *
 * <p>
 * <b>只发命令、绝不读用户态</b>——shard 0 每 tick submit {@code LIQUIDATION_SCAN}（全量兜底整扫）与
 * {@code REPRICE_LOAN_RATES}（动态利率重定价）。真正的检测/扫描都在子类的 on-lane apply 路径里跑（由 {@code cmd.timestamp} 驱动）；命令经 raft 复制后各节点确定性
 * apply，从根上消除调度线程与 apply 线程之间的竞态。
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
    private final int scanSliceCount;
    private final long repriceEveryNTicks;
    private long scanTick;

    protected LiquidationScheduledService(long delay, TimeUnit unit, ThreadFactory threadFactory, int shardId,
        int scanSliceCount, long repriceEveryNTicks) {
        this.delay = delay;
        this.unit = unit;
        this.threadFactory = threadFactory;
        this.shardId = shardId;
        this.scanSliceCount = Math.max(1, scanSliceCount);
        this.repriceEveryNTicks = Math.max(1, repriceEveryNTicks);
    }

    protected void runOneIteration() {
        if (shardId != 0) {
            return;
        }
        final int slice = (int)Math.floorMod(scanTick, scanSliceCount);
        submit(ApiLiquidationScan.builder().scanSlice(slice).sliceCount(scanSliceCount).build(), null);
        if (scanTick % repriceEveryNTicks == 0) {
            submit(ApiRepriceLoanRates.builder().build(), null);
        }
        scanTick++;
    }

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

    public static boolean coveredByScanSlice(OrderCommand cmd, long uid) {
        if (cmd.command != OrderCommandType.LIQUIDATION_SCAN || cmd.size <= 0) {
            return true;
        }
        return Math.floorMod(uid, cmd.size) == cmd.uid;
    }
}
