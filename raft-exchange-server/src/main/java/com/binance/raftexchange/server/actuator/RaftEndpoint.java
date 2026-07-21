package com.binance.raftexchange.server.actuator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import com.binance.raftexchange.server.RaftExchangeApplication;
import com.binance.raftexchange.server.metrics.RaftExchangeMetrics;
import com.binance.raftexchange.server.raft.AdminResult;
import com.binance.raftexchange.server.raft.RaftClusterContainer;
import com.binance.raftexchange.server.raft.RaftClusterDiscovery.ExtraPorts;
import com.binance.raftexchange.server.raft.RaftNode;
import com.binance.raftexchange.stubs.api.QueryServiceGrpc;
import com.binance.raftexchange.stubs.report.HashCodeEntry;
import com.binance.raftexchange.stubs.report.ReportQuery;
import com.binance.raftexchange.stubs.report.ReportResult;
import com.binance.raftexchange.stubs.report.StateHashReportQuery;
import com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportQuery;
import com.binance.raftexchange.stubs.report.TotalCurrencyBalanceReportResult;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;

/**
 * Raft 运维 actuator endpoint，子路径见 {@link #write} / {@link #read}。
 */
@Component
@Endpoint(id = "raft")
public class RaftEndpoint {
    private static final Logger LOG = LoggerFactory.getLogger(RaftEndpoint.class);

    private static final long SNAPSHOT_TRIGGER_TIMEOUT_SEC = 600L;
    private static final long SNAPSHOT_HEALTHY_AGE_SEC = 28_800L;
    private static final long PROBE_TIMEOUT_SEC = 5L;
    private static final DateTimeFormatter DT_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final ExecutorService PROBE_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "raft-probe");
        t.setDaemon(true);
        return t;
    });

    private final RaftExchangeApplication app;

    public RaftEndpoint(RaftExchangeApplication app) {
        this.app = app;
    }

    /** POST /raft/{snapshot|stepdown} */
    @WriteOperation
    public Map<String, Object> write(@Selector String operation) {
        return switch (operation) {
            case "snapshot" -> doTriggerSnapshot();
            case "stepdown" -> doStepDownLeadership();
            default -> error("unknown operation: " + operation);
        };
    }

    /** GET /raft/{snapshot|cluster|config|lag} */
    @ReadOperation
    public Map<String, Object> read(@Selector String operation) {
        return switch (operation) {
            case "snapshot" -> doSnapshotStatus();
            case "cluster" -> doClusterTopology();
            case "config" -> doConfig();
            case "lag" -> doLag();
            default -> error("unknown operation: " + operation);
        };
    }

    private static Map<String, Object> error(String message) {
        return Map.of("status", "error", "message", message);
    }

    private Map<String, Object> doTriggerSnapshot() {
        RaftClusterContainer raft = app.getRaftClusterContainer();
        if (raft == null)
            return error("raft cluster not started");
        Map<String, Object> resp = new LinkedHashMap<>();
        long startMs = System.currentTimeMillis();
        try {
            AdminResult result = raft.triggerSnapshot().get(SNAPSHOT_TRIGGER_TIMEOUT_SEC, TimeUnit.SECONDS);
            long elapsedMs = System.currentTimeMillis() - startMs;
            resp.put("status", result.success() ? "success" : "error");
            resp.put("message", result.message());
            resp.put("duration_ms", elapsedMs);
            LOG.info("Snapshot triggered via actuator, result={}, duration_ms={}", result, elapsedMs);
            return resp;
        } catch (TimeoutException e) {
            resp.put("status", "timeout");
            resp.put("message", "Snapshot did not complete within " + SNAPSHOT_TRIGGER_TIMEOUT_SEC + "s");
            resp.put("duration_ms", System.currentTimeMillis() - startMs);
            LOG.error("Snapshot trigger timed out via actuator");
            return resp;
        } catch (Exception e) {
            resp.put("status", "error");
            resp.put("message", String.valueOf(e));
            resp.put("duration_ms", System.currentTimeMillis() - startMs);
            LOG.error("Snapshot trigger failed via actuator", e);
            return resp;
        }
    }

    private Map<String, Object> doStepDownLeadership() {
        RaftClusterContainer raft = app.getRaftClusterContainer();
        if (raft == null)
            return error("raft cluster not started");
        Map<String, Object> resp = new LinkedHashMap<>();
        AdminResult result = raft.stepDownLeadership();
        resp.put("status", result.success() ? "success" : "error");
        resp.put("message", result.success() ? "stepdown initiated" : result.message());
        LOG.info("Step down leadership via actuator, result={}", result);
        return resp;
    }

    private Map<String, Object> doSnapshotStatus() {
        Map<String, Object> resp = new LinkedHashMap<>();
        long now = System.currentTimeMillis() / 1000;
        long lastSave = RaftExchangeMetrics.Snapshot.lastSaveSuccessEpochSec();
        long lastLoad = RaftExchangeMetrics.Snapshot.lastLoadSuccessEpochSec();
        long saveAge = lastSave == 0 ? -1 : now - lastSave;
        long loadAge = lastLoad == 0 ? -1 : now - lastLoad;

        resp.put("save.healthy", lastSave > 0 && saveAge <= SNAPSHOT_HEALTHY_AGE_SEC);
        resp.put("save.success_count", RaftExchangeMetrics.Snapshot.saveSuccessCount());
        resp.put("save.failure_count", RaftExchangeMetrics.Snapshot.saveFailureCount());
        resp.put("save.last_success_at", lastSave == 0 ? "never" : DT_FMT.format(Instant.ofEpochSecond(lastSave)));
        resp.put("save.last_success_age", formatAge(saveAge));
        resp.put("load.healthy", RaftExchangeMetrics.Snapshot.loadFailureCount() == 0);
        resp.put("load.success_count", RaftExchangeMetrics.Snapshot.loadSuccessCount());
        resp.put("load.failure_count", RaftExchangeMetrics.Snapshot.loadFailureCount());
        resp.put("load.last_success_at", lastLoad == 0 ? "never" : DT_FMT.format(Instant.ofEpochSecond(lastLoad)));
        resp.put("load.last_success_age", formatAge(loadAge));
        return resp;
    }

    private Map<String, Object> doConfig() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("consensus",
            System.getProperty(RaftClusterContainer.CONSENSUS_PROP, RaftClusterContainer.CONSENSUS_DEFAULT));
        resp.put("batch_enabled", Boolean.parseBoolean(System.getProperty("raftexchange.batch.enabled", "false")));
        resp.put("kafka_enabled", Boolean.parseBoolean(System.getProperty("raftexchange.kafka.enabled", "true")));
        resp.put("startup_nodes", Integer.parseInt(System.getProperty("raftexchange.cluster.startupNodes", "3")));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("log_index_margin",
            Integer.parseInt(System.getProperty("raftexchange.snapshot.logIndexMargin", "10000000")));
        snapshot.put("compression",
            Boolean.parseBoolean(System.getProperty("raftexchange.snapshot.compression", "false")));
        resp.put("snapshot", snapshot);
        return resp;
    }

    private Map<String, Object> doLag() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("is_leader", (long)gauge("raft.exchange.raft.role"));
        resp.put("committed_index", (long)gauge("raft.exchange.raft.committed_index"));
        resp.put("applied_index", (long)gauge("raft.exchange.raft.applied_index"));
        resp.put("replication_lag", (long)gauge("raft.exchange.raft.replication_lag"));
        resp.put("sidecar_fetch_success", RaftExchangeMetrics.Sidecar.fetchSuccessCount());
        resp.put("sidecar_fetch_failure", RaftExchangeMetrics.Sidecar.fetchFailureCount());
        resp.put("snapshot_cleanup_failures", RaftExchangeMetrics.Snapshot.cleanupFailureCount());
        return resp;
    }

    private static double gauge(String name) {
        Gauge gauge = Metrics.globalRegistry.find(name).gauge();
        return gauge == null ? 0 : gauge.value();
    }

    private Map<String, Object> doClusterTopology() {
        RaftClusterContainer raft = app.getRaftClusterContainer();
        if (raft == null)
            return error("raft cluster not started");
        Map<String, Object> resp = new LinkedHashMap<>();
        Map<Integer, ExtraPorts> extraPorts = app.getRaftClusterDiscovery().grpcPortToExtraPorts();
        RaftNode leader = raft.leaderNode();
        List<RaftNode> nodes = raft.listNodes();

        Map<String, NodeProbeResult> probeResults = probeAllNodes(nodes);

        List<Map<String, Object>> nodeMaps = nodes.stream().map(node -> {
            Map<String, Object> nodeInfo = nodeMap(node, extraPorts);
            NodeProbeResult probe = probeResults.get(node.host() + ":" + node.port());
            if (probe != null) {
                nodeInfo.put("state_hash_submodules", probe.stateHash() != null ? probe.stateHash().size() : -1);
                if (probe.totalBalance() != null) {
                    nodeInfo.put("balances", balancesToMap(probe.totalBalance()));
                }
                if (probe.error() != null)
                    nodeInfo.put("probe_error", probe.error());
            }
            return nodeInfo;
        }).toList();

        resp.put("cluster_started", true);
        resp.put("self_is_leader", raft.isLeader());
        resp.put("leader", leader == null ? null : nodeMap(leader, extraPorts));
        resp.put("nodes", nodeMaps);
        resp.put("node_count", nodes.size());

        if (nodes.size() >= 2) {
            List<String> divergences = collectStateHashDivergences(probeResults);
            resp.put("state_hash_converged", divergences.isEmpty());
            if (!divergences.isEmpty())
                resp.put("state_hash_divergences", divergences);
        }
        resp.put("probed_at", DT_FMT.format(Instant.now()));
        return resp;
    }

    private static Map<String, NodeProbeResult> probeAllNodes(List<RaftNode> nodes) {
        Map<String, CompletableFuture<NodeProbeResult>> futures = new LinkedHashMap<>();
        for (RaftNode n : nodes) {
            String ep = n.host() + ":" + n.port();
            futures.put(ep, CompletableFuture.supplyAsync(() -> probeNode(n.host(), n.port()), PROBE_EXECUTOR));
        }
        // single global deadline — prevents N×timeout stacking when collecting sequentially
        try {
            CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new)).get(PROBE_TIMEOUT_SEC + 1,
                TimeUnit.SECONDS);
        } catch (TimeoutException ignored) {
            // gRPC deadline fires first; this is a safety net for hangs
        } catch (Exception e) {
            LOG.warn("Unexpected error waiting for node probes", e);
        }
        // probeNode always returns a result (catches all exceptions), so getNow default is only hit on timeout
        NodeProbeResult timeout = new NodeProbeResult(null, null, "probe did not complete in time");
        Map<String, NodeProbeResult> results = new LinkedHashMap<>();
        futures.forEach((ep, f) -> results.put(ep, f.getNow(timeout)));
        return results;
    }

    private static NodeProbeResult probeNode(String host, int grpcPort) {
        ManagedChannel ch = NettyChannelBuilder.forAddress(host, grpcPort).usePlaintext().build();
        try {
            QueryServiceGrpc.QueryServiceBlockingStub stub =
                QueryServiceGrpc.newBlockingStub(ch).withDeadlineAfter(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS);
            int txId = (int)(System.nanoTime() & 0x7FFF_FFFFL);

            ReportResult stateHashResult = stub.query(ReportQuery.newBuilder().setTransferId(txId)
                .setStateHash(StateHashReportQuery.getDefaultInstance()).build());
            Map<Long, Integer> stateHash = new HashMap<>();
            for (HashCodeEntry e : stateHashResult.getStateHash().getHashCodesList()) {
                long k = ((long)e.getKey().getModuleId() << 32) | (e.getKey().getSubmoduleTypeValue() & 0xFFFF_FFFFL);
                stateHash.put(k, e.getValue());
            }

            ReportResult balResult = stub.query(ReportQuery.newBuilder().setTransferId(txId + 1)
                .setTotalCurrencyBalance(TotalCurrencyBalanceReportQuery.getDefaultInstance()).build());

            return new NodeProbeResult(stateHash, balResult.getTotalCurrencyBalance(), null);
        } catch (Exception e) {
            LOG.warn("Probe failed for {}:{}: {}", host, grpcPort, e.toString());
            return new NodeProbeResult(null, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            ch.shutdown();
        }
    }

    private static Map<String, Object> balancesToMap(TotalCurrencyBalanceReportResult r) {
        Map<Integer, String> ccy = currencyNames(r);
        Map<Integer, Integer> dig = currencyDigits(r);
        Map<Integer, String> sym = symbolNames(r, ccy);

        // 账户/通用：cash、手续费、充提调整、冻结——现货/期货/loan 共用
        Map<String, Object> account = new LinkedHashMap<>();
        putScaled(account, "account_balances", r.getAccountBalancesMap(), ccy, dig);
        putScaled(account, "fees", r.getFeesMap(), ccy, dig);
        putScaled(account, "adjustments", r.getAdjustmentsMap(), ccy, dig);
        putScaled(account, "suspends", r.getSuspendsMap(), ccy, dig);

        // 现货：挂单锁定
        Map<String, Object> spot = new LinkedHashMap<>();
        putScaled(spot, "exchange_locked", r.getExchangeLockedMap(), ccy, dig);

        // 期货：额外保证金、保险基金、持仓量（OI 是合约张数、保持 raw）
        Map<String, Object> futures = new LinkedHashMap<>();
        putScaled(futures, "extra_margin", r.getExtraMarginMap(), ccy, dig);
        putScaled(futures, "if_balances", r.getIfBalancesMap(), ccy, dig);
        putRaw(futures, "open_interest_long", r.getOpenInterestLongMap(), sym);
        putRaw(futures, "open_interest_short", r.getOpenInterestShortMap(), sym);
        putRaw(futures, "if_open_interest_long", r.getIfOpenInterestLongMap(), sym);
        putRaw(futures, "if_open_interest_short", r.getIfOpenInterestShortMap(), sym);

        // loan：平台桶（池可用 + 利息 + LIF），不含 loanPoolBorrowed（钱在借款人 account）
        Map<String, Object> loan = new LinkedHashMap<>();
        putScaled(loan, "loan_balances", r.getLoanBalancesMap(), ccy, dig);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("account", account);
        m.put("spot", spot);
        m.put("futures", futures);
        m.put("loan", loan);
        m.put("balance_zero", computeGlobalSum(r, ccy, dig)); // 各币种跨全部现金桶净额 = 0
        return m;
    }

    /** currency 桶：非空则按 digit 缩放成可读小数放入 out。 */
    private static void putScaled(Map<String, Object> out, String key, Map<Integer, Long> raw,
        Map<Integer, String> names, Map<Integer, Integer> digits) {
        if (!raw.isEmpty())
            out.put(key, scaledByCurrency(raw, names, digits));
    }

    /** symbol 桶（合约张数/volume）：非空则保持 raw 放入 out。 */
    private static void putRaw(Map<String, Object> out, String key, Map<Integer, Long> raw, Map<Integer, String> names) {
        if (!raw.isEmpty())
            out.put(key, keyById(raw, names));
    }

    /** currency id→name. Spec 缺失时 fallback "id=N"。 */
    private static Map<Integer, String> currencyNames(TotalCurrencyBalanceReportResult r) {
        Map<Integer, String> names = new HashMap<>();
        r.getCurrencySpecsMap().forEach((id, spec) -> names.put(id, spec.getName()));
        return names;
    }

    /** currency id→digit（小数位数）。raw = 真实值 × 10^digit；spec 缺失时按 0（不缩放）。 */
    private static Map<Integer, Integer> currencyDigits(TotalCurrencyBalanceReportResult r) {
        Map<Integer, Integer> digits = new HashMap<>();
        r.getCurrencySpecsMap().forEach((id, spec) -> digits.put(id, spec.getDigit()));
        return digits;
    }

    /** symbol id→"BASE/QUOTE"（如 BTC/USDT）。base 或 quote currency spec 缺失时 fallback "id=N"。 */
    private static Map<Integer, String> symbolNames(TotalCurrencyBalanceReportResult r,
        Map<Integer, String> currencyNames) {
        Map<Integer, String> names = new HashMap<>();
        r.getSymbolSpecsMap().forEach((id, spec) -> {
            String base = currencyNames.get(spec.getBaseCurrency());
            String quote = currencyNames.get(spec.getQuoteCurrency());
            names.put(id, (base != null && quote != null) ? base + "/" + quote : "id=" + id);
        });
        return names;
    }

    /** name 查不到时 fallback "id=N"。 */
    private static String nameOf(Map<Integer, String> names, int id) {
        return names.getOrDefault(id, "id=" + id);
    }

    private static Map<String, Long> keyById(Map<Integer, Long> raw, Map<Integer, String> names) {
        Map<String, Long> out = new LinkedHashMap<>();
        raw.forEach((id, v) -> out.put(nameOf(names, id), v));
        return out;
    }

    /** currency 键控的 raw 值按币种 digit 缩放成可读小数（raw / 10^digit），并按 name 键控。 */
    private static Map<String, BigDecimal> scaledByCurrency(Map<Integer, Long> raw, Map<Integer, String> names,
        Map<Integer, Integer> digits) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        raw.forEach((id, v) -> out.put(nameOf(names, id), BigDecimal.valueOf(v).movePointLeft(digits.getOrDefault(id, 0))));
        return out;
    }

    /**
     * 守恒方程 accountBalances + extraMargin + exchangeLocked + loanBalances + fees + adjustments + suspends + ifBalances
     * = 0（同 core isGlobalBalancesAllZero；同币种同 scale，先按 raw 求和再缩放）。open_interest_* 是 symbol→volume，不参与。
     */
    private static Map<String, BigDecimal> computeGlobalSum(TotalCurrencyBalanceReportResult r,
        Map<Integer, String> names, Map<Integer, Integer> digits) {
        Map<Integer, Long> sum = new TreeMap<>();
        Stream
            .<Map<Integer, Long>>of(r.getAccountBalancesMap(), r.getExtraMarginMap(), r.getExchangeLockedMap(),
                r.getLoanBalancesMap(), r.getFeesMap(), r.getAdjustmentsMap(), r.getSuspendsMap(), r.getIfBalancesMap())
            .forEach(m -> m.forEach((c, v) -> sum.merge(c, v, Long::sum)));
        return scaledByCurrency(sum, names, digits);
    }

    private static List<String> collectStateHashDivergences(Map<String, NodeProbeResult> probeResults) {
        List<String> divergences = new ArrayList<>();
        Map.Entry<String, NodeProbeResult> baseEntry = null;
        for (Map.Entry<String, NodeProbeResult> e : probeResults.entrySet()) {
            if (e.getValue().stateHash() != null) {
                baseEntry = e;
                break;
            }
        }
        if (baseEntry == null)
            return divergences;
        Map<Long, Integer> base = baseEntry.getValue().stateHash();
        for (Map.Entry<String, NodeProbeResult> e : probeResults.entrySet()) {
            if (e.getKey().equals(baseEntry.getKey()))
                continue;
            Map<Long, Integer> other = e.getValue().stateHash();
            if (other == null) {
                divergences.add(e.getKey() + " probe failed, cannot compare");
                continue;
            }
            if (base.size() != other.size()) {
                divergences.add(e.getKey() + " submodule count " + other.size() + " ≠ base " + base.size());
                continue;
            }
            for (Map.Entry<Long, Integer> kv : base.entrySet()) {
                if (!Objects.equals(kv.getValue(), other.get(kv.getKey()))) {
                    divergences.add(e.getKey() + " submodule 0x" + Long.toHexString(kv.getKey()) + " hash="
                        + other.get(kv.getKey()) + " ≠ base " + baseEntry.getKey() + " hash=" + kv.getValue());
                }
            }
        }
        return divergences;
    }

    private static Map<String, Object> nodeMap(RaftNode n, Map<Integer, ExtraPorts> extraPorts) {
        ExtraPorts ports = extraPorts.getOrDefault(n.port(), ExtraPorts.UNKNOWN);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", n.host());
        m.put("grpc_port", n.port());
        m.put("raft_port", ports.raft());
        m.put("http_port", ports.http());
        m.put("mgmt_port", ports.mgmt());
        m.put("role", n.nodeType().name());
        return m;
    }

    private record NodeProbeResult(Map<Long, Integer> stateHash, TotalCurrencyBalanceReportResult totalBalance,
        String error) {}

    private static String formatAge(long ageSec) {
        if (ageSec < 0)
            return "never";
        long h = ageSec / 3600;
        long m = (ageSec % 3600) / 60;
        long s = ageSec % 60;
        if (h > 0)
            return h + "h " + m + "m";
        if (m > 0)
            return m + "m " + s + "s";
        return s + "s";
    }
}
