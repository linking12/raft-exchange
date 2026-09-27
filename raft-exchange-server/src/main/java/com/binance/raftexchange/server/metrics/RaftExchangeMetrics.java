package com.binance.raftexchange.server.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** raft-exchange Micrometer 指标入口；按子域分嵌套类。 */
public final class RaftExchangeMetrics {
    private RaftExchangeMetrics() {}

    /** 触发各子类 static block，让 actuator/Prometheus 启动即可抓到 base gauge 初值。Kafka 由 owner 各自预热。 */
    public static void prewarmAll() {
        Snapshot.init();
        Raft.init();
        Grpc.init();
        ReadBarrier.init();
        Sidecar.init();
    }

    /** Snapshot save/load 指标。生产 1h 间隔，这是运维判断健康的唯一信号源。 */
    public static final class Snapshot {
        private Snapshot() {}

        public static void init() {}

        private static final Timer SAVE_DURATION = Timer.builder("raft.exchange.snapshot.save.duration")
            .publishPercentiles(0.5, 0.99).register(Metrics.globalRegistry);
        private static final Counter SAVE_SUCCESS =
            Metrics.counter("raft.exchange.snapshot.save.count", "status", "success");
        private static final Counter SAVE_FAILURE =
            Metrics.counter("raft.exchange.snapshot.save.count", "status", "failure");
        private static final DistributionSummary SAVE_SIZE_BYTES =
            DistributionSummary.builder("raft.exchange.snapshot.save.size.bytes").register(Metrics.globalRegistry);
        private static final AtomicLong LAST_SAVE_SUCCESS_EPOCH_SEC = new AtomicLong(0L);

        private static final Timer LOAD_DURATION = Timer.builder("raft.exchange.snapshot.load.duration")
            .publishPercentiles(0.5, 0.99).register(Metrics.globalRegistry);
        private static final Counter LOAD_SUCCESS =
            Metrics.counter("raft.exchange.snapshot.load.count", "status", "success");
        private static final Counter LOAD_FAILURE =
            Metrics.counter("raft.exchange.snapshot.load.count", "status", "failure");
        private static final AtomicLong LAST_LOAD_SUCCESS_EPOCH_SEC = new AtomicLong(0L);

        // 磁盘异常的早期信号——别再 catch+ignored 把它丢了。
        private static final Counter CLEANUP_FAILURE = Metrics.counter("raft.exchange.snapshot.cleanup.failure.count");
        private static final Counter SIZE_PROBE_FAILURE =
            Metrics.counter("raft.exchange.snapshot.size_probe.failure.count");

        // 理论恒为 0；非零 = 至少一次 partial swap 风险被截断。
        private static final Counter RECOVER_HALT = Metrics.counter("raft.exchange.snapshot.recover.halt.count");

        private static final Counter MARKER_OFFER_FAILURE =
            Metrics.counter("raft.exchange.snapshot.marker_offer.failure.count");

        static {
            Gauge.builder("raft.exchange.snapshot.save.last_success_epoch_sec", LAST_SAVE_SUCCESS_EPOCH_SEC,
                AtomicLong::get).register(Metrics.globalRegistry);
            Gauge.builder("raft.exchange.snapshot.load.last_success_epoch_sec", LAST_LOAD_SUCCESS_EPOCH_SEC,
                AtomicLong::get).register(Metrics.globalRegistry);
        }

        public static void recordSaveSuccess(long startNanos, long sizeBytes) {
            SAVE_DURATION.record(Duration.ofNanos(System.nanoTime() - startNanos));
            SAVE_SUCCESS.increment();
            SAVE_SIZE_BYTES.record(sizeBytes);
            LAST_SAVE_SUCCESS_EPOCH_SEC.set(System.currentTimeMillis() / 1000);
        }

        public static void recordSaveFailure(long startNanos) {
            SAVE_DURATION.record(Duration.ofNanos(System.nanoTime() - startNanos));
            SAVE_FAILURE.increment();
        }

        public static void recordLoadSuccess(long startNanos) {
            LOAD_DURATION.record(Duration.ofNanos(System.nanoTime() - startNanos));
            LOAD_SUCCESS.increment();
            LAST_LOAD_SUCCESS_EPOCH_SEC.set(System.currentTimeMillis() / 1000);
        }

        public static void recordLoadFailure(long startNanos) {
            LOAD_DURATION.record(Duration.ofNanos(System.nanoTime() - startNanos));
            LOAD_FAILURE.increment();
        }

        public static void recordCleanupFailure() {
            CLEANUP_FAILURE.increment();
        }

        public static void recordSizeProbeFailure() {
            SIZE_PROBE_FAILURE.increment();
        }

        public static void recordRecoverHalt() {
            RECOVER_HALT.increment();
        }

        public static void recordMarkerOfferFailure() {
            MARKER_OFFER_FAILURE.increment();
        }

        public static long lastSaveSuccessEpochSec() {
            return LAST_SAVE_SUCCESS_EPOCH_SEC.get();
        }

        public static long lastLoadSuccessEpochSec() {
            return LAST_LOAD_SUCCESS_EPOCH_SEC.get();
        }

        public static long saveSuccessCount() {
            return (long)SAVE_SUCCESS.count();
        }

        public static long saveFailureCount() {
            return (long)SAVE_FAILURE.count();
        }

        public static long loadSuccessCount() {
            return (long)LOAD_SUCCESS.count();
        }

        public static long loadFailureCount() {
            return (long)LOAD_FAILURE.count();
        }

        public static long cleanupFailureCount() {
            return (long)CLEANUP_FAILURE.count();
        }

        public static long sizeProbeFailureCount() {
            return (long)SIZE_PROBE_FAILURE.count();
        }

        public static long recoverHaltCount() {
            return (long)RECOVER_HALT.count();
        }

        public static long markerOfferFailureCount() {
            return (long)MARKER_OFFER_FAILURE.count();
        }

        /** Test-only: reset last-success timestamps so tests can assert "never". */
        public static void resetLastSuccessTimestampsForTesting() {
            LAST_SAVE_SUCCESS_EPOCH_SEC.set(0L);
            LAST_LOAD_SUCCESS_EPOCH_SEC.set(0L);
        }
    }

    /** Raft 节点核心指标：role / index / replication_lag / leader 切换 / apply 时延 / pending / stepDown 等。 */
    public static final class Raft {
        private Raft() {}

        public static void init() {}

        private static final Counter LEADER_CHANGE = Metrics.counter("raft.exchange.raft.leader_change.count");
        private static final AtomicLong LAST_LEADER_CHANGE_EPOCH_SEC = new AtomicLong(0L);

        private static final Timer APPLY_LATENCY = Timer.builder("raft.exchange.raft.apply.latency")
            .publishPercentiles(0.5, 0.99).minimumExpectedValue(Duration.ofNanos(10_000L))
            .maximumExpectedValue(Duration.ofSeconds(3L)).register(Metrics.globalRegistry);
        private static final DistributionSummary APPLY_BATCH_SIZE = DistributionSummary
            .builder("raft.exchange.raft.apply.batch.size").publishPercentiles(0.5, 0.99)
            .register(Metrics.globalRegistry);
        private static final Counter SUBMIT_BATCH_FAILURE = Metrics.counter("raft.exchange.raft.submit_batch.failure.count");
        private static final DistributionSummary SUBMIT_BATCH_FAILURE_SIZE = DistributionSummary
            .builder("raft.exchange.raft.submit_batch.failure.size").register(Metrics.globalRegistry);
        private static final Counter UNSUPPORTED_COMMAND =
            Metrics.counter("raft.exchange.raft.unsupported_command.count");

        private static final Counter PENDING_REJECT = Metrics.counter("raft.exchange.raft.pending.reject.count");
        private static final DistributionSummary DRAIN_PENDING_CLEARED =
            DistributionSummary.builder("raft.exchange.raft.drain.cleared").register(Metrics.globalRegistry);

        private static final Counter SESSION_OPEN = Metrics.counter("raft.exchange.raft.session.open.count");
        private static final ConcurrentHashMap<String, Counter> SESSION_CLOSE = new ConcurrentHashMap<>();

        private static final Counter STEPDOWN_SUCCESS =
            Metrics.counter("raft.exchange.raft.stepdown.count", "status", "success");
        private static final Counter STEPDOWN_FAILURE =
            Metrics.counter("raft.exchange.raft.stepdown.count", "status", "failure");
        private static final Timer STEPDOWN_DURATION = Timer.builder("raft.exchange.raft.stepdown.duration")
            .publishPercentiles(0.5, 0.99).register(Metrics.globalRegistry);

        static {
            Gauge.builder("raft.exchange.raft.leader_change.last_epoch_sec", LAST_LEADER_CHANGE_EPOCH_SEC,
                AtomicLong::get).register(Metrics.globalRegistry);
        }

        public static void register(BooleanSupplier isLeader, LongSupplier committedIndex, LongSupplier appliedIndex) {
            gauge("raft.exchange.raft.role", () -> isLeader.getAsBoolean() ? 1L : 0L);
            gauge("raft.exchange.raft.committed_index", committedIndex);
            gauge("raft.exchange.raft.applied_index", appliedIndex);
            gauge("raft.exchange.raft.replication_lag",
                () -> Math.max(0L, committedIndex.getAsLong() - appliedIndex.getAsLong()));
        }

        public static void registerPendingSizeSupplier(LongSupplier supplier) {
            gauge("raft.exchange.raft.pending.size", supplier);
        }

        public static void recordLeaderChange() {
            LEADER_CHANGE.increment();
            LAST_LEADER_CHANGE_EPOCH_SEC.set(System.currentTimeMillis() / 1000);
        }

        public static void recordApplyLatency(long startNanos) {
            APPLY_LATENCY.record(Duration.ofNanos(System.nanoTime() - startNanos));
        }

        public static void recordApplyBatchSize(int size) {
            APPLY_BATCH_SIZE.record(size);
        }

        public static void recordSubmitBatchFailure(int size) {
            SUBMIT_BATCH_FAILURE.increment();
            SUBMIT_BATCH_FAILURE_SIZE.record(size);
        }

        public static void recordUnsupportedCommand() {
            UNSUPPORTED_COMMAND.increment();
        }

        public static void recordPendingReject() {
            PENDING_REJECT.increment();
        }

        public static void recordDrainCleared(int cleared) {
            DRAIN_PENDING_CLEARED.record(cleared);
        }

        public static void recordSessionOpen() {
            SESSION_OPEN.increment();
        }

        public static void recordSessionClose(String reason) {
            SESSION_CLOSE.computeIfAbsent(reason,
                r -> Metrics.counter("raft.exchange.raft.session.close.count", "reason", r)).increment();
        }

        public static void recordStepDownSuccess(long startNanos) {
            STEPDOWN_DURATION.record(Duration.ofNanos(System.nanoTime() - startNanos));
            STEPDOWN_SUCCESS.increment();
        }

        public static void recordStepDownFailure(long startNanos) {
            STEPDOWN_DURATION.record(Duration.ofNanos(System.nanoTime() - startNanos));
            STEPDOWN_FAILURE.increment();
        }

        public static long leaderChangeCount() { return (long)LEADER_CHANGE.count(); }
        public static long lastLeaderChangeEpochSec() { return LAST_LEADER_CHANGE_EPOCH_SEC.get(); }
        public static long submitBatchFailureCount() { return (long)SUBMIT_BATCH_FAILURE.count(); }
        public static long unsupportedCommandCount() { return (long)UNSUPPORTED_COMMAND.count(); }
        public static long pendingRejectCount() { return (long)PENDING_REJECT.count(); }
        public static long sessionOpenCount() { return (long)SESSION_OPEN.count(); }
        public static long stepDownSuccessCount() { return (long)STEPDOWN_SUCCESS.count(); }
        public static long stepDownFailureCount() { return (long)STEPDOWN_FAILURE.count(); }

        private static void gauge(String name, LongSupplier supplier) {
            Gauge.builder(name, supplier::getAsLong).strongReference(true).register(Metrics.globalRegistry);
        }
    }

    /** gRPC 入口指标。matching latency 桶为 µs 级，grpc/raft 为 ms 级。 */
    public static final class Grpc {
        private Grpc() {}

        public static void init() {}

        private static final Counter SERVER_QPS = Metrics.counter("raft.exchange.grpc.server.counter");
        private static final Timer LATENCY = msScaleTimer("raft.exchange.grpc.latency");
        private static final Timer RAFT_LATENCY = msScaleTimer("raft.exchange.raft.latency");
        private static final Timer MATCHING_LATENCY =
            Timer.builder("raft.exchange.matching.latency").publishPercentiles(0.99)
                .minimumExpectedValue(Duration.ofNanos(10_000L)).maximumExpectedValue(Duration.ofMillis(100L))
                .publishPercentileHistogram(false).register(Metrics.globalRegistry);

        private static final LongAdder INFLIGHT = new LongAdder();
        static {
            Gauge.builder("raft.exchange.grpc.inflight", INFLIGHT, LongAdder::sum).register(Metrics.globalRegistry);
        }

        public static void recordRequest() {
            SERVER_QPS.increment();
        }

        public static void recordGrpcLatency(long nanos) {
            LATENCY.record(nanos, TimeUnit.NANOSECONDS);
        }

        public static void recordRaftLatency(long nanos) {
            RAFT_LATENCY.record(nanos, TimeUnit.NANOSECONDS);
        }

        public static void recordMatchingLatency(long nanos) {
            MATCHING_LATENCY.record(nanos, TimeUnit.NANOSECONDS);
        }

        public static void inflightAcquire(long n) {
            INFLIGHT.add(n);
        }

        public static void inflightRelease() {
            INFLIGHT.decrement();
        }

        public static void inflightRelease(long n) {
            INFLIGHT.add(-n);
        }

        private static Timer msScaleTimer(String name) {
            return Timer.builder(name).publishPercentiles(0.99).minimumExpectedValue(Duration.ofMillis(1L))
                .maximumExpectedValue(Duration.ofSeconds(3L)).publishPercentileHistogram(false)
                .register(Metrics.globalRegistry);
        }
    }

    /** QueryService 进 engine 前的 read barrier。jraft ReadIndex 真值，aeron no-op 不 record。 */
    public static final class ReadBarrier {
        private ReadBarrier() {}

        public static void init() {}

        private static final Timer DURATION = Timer.builder("raft.exchange.read.barrier.duration")
            .publishPercentiles(0.5, 0.99).minimumExpectedValue(Duration.ofNanos(100_000L))
            .maximumExpectedValue(Duration.ofSeconds(1L)).register(Metrics.globalRegistry);
        private static final Counter SUCCESS = Metrics.counter("raft.exchange.read.barrier.count", "status", "success");
        private static final Counter FAILURE = Metrics.counter("raft.exchange.read.barrier.count", "status", "failure");

        public static void recordSuccess(long startNanos) {
            DURATION.record(Duration.ofNanos(System.nanoTime() - startNanos));
            SUCCESS.increment();
        }

        public static void recordFailure(long startNanos) {
            DURATION.record(Duration.ofNanos(System.nanoTime() - startNanos));
            FAILURE.increment();
        }

        public static long successCount() { return (long)SUCCESS.count(); }
        public static long failureCount() { return (long)FAILURE.count(); }
    }

    /** Aeron sidecar gRPC snapshot transfer。fetch=follower 拉 peer，serve=本节点发出；都是兜底路径，非零应 alert。 */
    public static final class Sidecar {
        private Sidecar() {}

        public static void init() {}

        private static final Timer FETCH_DURATION = Timer.builder("raft.exchange.sidecar.fetch.duration")
            .publishPercentiles(0.5, 0.99).register(Metrics.globalRegistry);
        private static final Counter FETCH_SUCCESS =
            Metrics.counter("raft.exchange.sidecar.fetch.count", "status", "success");
        private static final Counter FETCH_FAILURE =
            Metrics.counter("raft.exchange.sidecar.fetch.count", "status", "failure");
        private static final DistributionSummary FETCH_BYTES =
            DistributionSummary.builder("raft.exchange.sidecar.fetch.bytes").register(Metrics.globalRegistry);

        private static final Counter SERVE_SUCCESS =
            Metrics.counter("raft.exchange.sidecar.serve.count", "status", "success");
        private static final Counter SERVE_FAILURE =
            Metrics.counter("raft.exchange.sidecar.serve.count", "status", "failure");
        private static final DistributionSummary SERVE_BYTES =
            DistributionSummary.builder("raft.exchange.sidecar.serve.bytes").register(Metrics.globalRegistry);

        public static void recordFetchSuccess(long startNanos, long bytes) {
            FETCH_DURATION.record(Duration.ofNanos(System.nanoTime() - startNanos));
            FETCH_SUCCESS.increment();
            FETCH_BYTES.record(bytes);
        }

        public static void recordFetchFailure(long startNanos) {
            FETCH_DURATION.record(Duration.ofNanos(System.nanoTime() - startNanos));
            FETCH_FAILURE.increment();
        }

        public static void recordServeSuccess(long bytes) {
            SERVE_SUCCESS.increment();
            SERVE_BYTES.record(bytes);
        }

        public static void recordServeFailure() {
            SERVE_FAILURE.increment();
        }

        public static long fetchSuccessCount() { return (long)FETCH_SUCCESS.count(); }
        public static long fetchFailureCount() { return (long)FETCH_FAILURE.count(); }
        public static long serveSuccessCount() { return (long)SERVE_SUCCESS.count(); }
        public static long serveFailureCount() { return (long)SERVE_FAILURE.count(); }
    }

    /** Kafka 投递指标。Counter/Timer 按 topic_group lazy 注册。 */
    public static final class Kafka {
        private Kafka() {}

        private static final ConcurrentHashMap<String, Counter> SEND_SUCCESS = new ConcurrentHashMap<>();
        private static final ConcurrentHashMap<String, Counter> SEND_FAILURE = new ConcurrentHashMap<>();
        private static final ConcurrentHashMap<String, Timer> SEND_LATENCY = new ConcurrentHashMap<>();

        /** 启动期 eager 注册全 group，避免 dashboard 看不到尚未触发的 metric。 */
        public static void prewarm(Iterable<String> topicGroups) {
            for (String g : topicGroups) {
                successCounter(g);
                failureCounter(g);
                latencyTimer(g);
            }
        }

        public static void recordSendSuccess(String topicGroup, long latencyNanos) {
            latencyTimer(topicGroup).record(latencyNanos, TimeUnit.NANOSECONDS);
            successCounter(topicGroup).increment();
        }

        public static void recordSendFailure(String topicGroup) {
            failureCounter(topicGroup).increment();
        }

        private static Counter successCounter(String g) {
            return SEND_SUCCESS.computeIfAbsent(g,
                t -> Metrics.counter("raft.exchange.kafka.send", "topic_group", t, "status", "success"));
        }

        private static Counter failureCounter(String g) {
            return SEND_FAILURE.computeIfAbsent(g,
                t -> Metrics.counter("raft.exchange.kafka.send", "topic_group", t, "status", "failure"));
        }

        private static Timer latencyTimer(String g) {
            return SEND_LATENCY.computeIfAbsent(g, Kafka::buildLatencyTimer);
        }

        public static void registerBacklogGauge(String raftCluster, LongSupplier backlog) {
            Gauge.builder("raft.exchange.kafka.queue.backlog", backlog::getAsLong).tag("raft_cluster", raftCluster)
                .strongReference(true).register(Metrics.globalRegistry);
        }

        private static Timer buildLatencyTimer(String topicGroup) {
            return Timer.builder("raft.exchange.kafka.send.latency").tag("topic_group", topicGroup)
                .publishPercentiles(0.99).register(Metrics.globalRegistry);
        }
    }

}
