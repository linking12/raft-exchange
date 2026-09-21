package com.binance.raftexchange.server.raft;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiNop;

/**
 * Leader-only scheduled {@link ApiNop} injector. Quiet books (no MM) can leave R2 /
 * fund TRANSFER pending until another command arrives; NOP advances the disruptor with
 * no matching/balance side effects. Same command shape as
 * {@code AeronClusterContainer.readConsistencyBarrier()}. Off when intervalMs &lt;= 0
 * (recommended when MM traffic is present).
 */
public final class NopHeartbeat implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(NopHeartbeat.class);
    private static final byte[] NOP_BYTES =
        ApiCommand.newBuilder().setNop(ApiNop.getDefaultInstance()).build().toByteArray();

    private final RaftClusterContainer raft;
    private final long intervalMs;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;

    public NopHeartbeat(RaftClusterContainer raft, long intervalMs) {
        this.raft = raft;
        this.intervalMs = intervalMs;
    }

    public void start() {
        if (intervalMs <= 0) {
            LOGGER.info("NOP heartbeat disabled (raftexchange.heartbeat.interval-ms={})", intervalMs);
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "raft-nop-heartbeat");
            t.setDaemon(true);
            return t;
        });
        future = scheduler.scheduleAtFixedRate(this::tick, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        LOGGER.info("NOP heartbeat started: intervalMs={} (leader-only)", intervalMs);
    }

    private void tick() {
        try {
            if (!raft.isLeader()) {
                return;
            }
            raft.requestConsensus(NOP_BYTES, (resp, err) -> {
                if (err != null) {
                    LOGGER.debug("NOP heartbeat failed: {}", err.toString());
                }
            });
        } catch (Throwable t) {
            LOGGER.debug("NOP heartbeat tick error: {}", t.toString());
        }
    }

    @Override
    public void close() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        started.set(false);
    }
}
