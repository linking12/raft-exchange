package com.binance.raftexchange.client.demo;

import com.binance.raftexchange.client.CommandResultView;
import com.binance.raftexchange.client.ExchangeApi;
import com.binance.raftexchange.client.ExchangeApiOptions;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Raft throughput probe.
 *
 * <p>
 * 启动方式：
 * 
 * <pre>
 *   ./start-local-cluster.sh start spot
 *   mvn -pl raft-exchange-client test-compile
 *   java -cp "raft-exchange-client/target/test-classes:raft-exchange-client/target/classes:$(mvn -q -pl raft-exchange-client dependency:build-classpath -DincludeScope=test -Dmdep.outputFile=/dev/stdout 2>/dev/null)" \
 *        com.binance.raftexchange.client.demo.ThroughputProbe [mode] [concurrency] [durationSec]
 *   modes: seq    单 client 顺序（前一条 commit 后再发下一条）
 *          burst  N 并发，每个 client 一直灌
 *          all    依次跑 seq + burst(1/10/100/500/1000)
 * </pre>
 *
 * 命令选用：BALANCE_ADJUSTMENT。fresh uid + tx id，每次走完整 raft commit + RiskEngine.adjustBalance。 没有撮合，纯压 raft + balance 调整路径。
 */
public final class ThroughputProbe {

    private static final String MGMT_HOST = "127.0.0.1";
    private static final int[] MGMT_PORTS = {28081, 28082, 28083};
    private static final int USDT_ID = 1;

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "all";
        int concurrency = args.length > 1 ? Integer.parseInt(args[1]) : 100;
        int durationSec = args.length > 2 ? Integer.parseInt(args[2]) : 10;

        String[] leader = resolveLeader();
        System.out.println("connecting to leader=" + leader[0] + ":" + leader[1]);

        try (ExchangeApi api = ExchangeApi.connect(leader[0], Integer.parseInt(leader[1]),
            ExchangeApiOptions.builder().sendTimeout(Duration.ofSeconds(30)).build())) {

            long runId = System.nanoTime() & 0xFFFFFL;
            long probeUid = 9_000_000_000L + runId;

            // 一次性创建 probe user + 初始充值，后续 adjustments 都打 probeUid
            warmupUser(api, probeUid);

            switch (mode) {
                case "seq":
                    runSeq(api, probeUid, durationSec);
                    break;
                case "burst":
                    runBurst(api, probeUid, concurrency, durationSec);
                    break;
                case "all":
                default:
                    runSeq(api, probeUid, durationSec);
                    for (int c : new int[] {1, 10, 100, 500, 1000}) {
                        runBurst(api, probeUid, c, durationSec);
                    }
                    break;
            }
        }
    }

    private static void warmupUser(ExchangeApi api, long uid) {
        if (!api.getMetadataManager().currencyExists(USDT_ID)) {
            CommandResultView cur = api.addCurrency(USDT_ID, "USDT", 6);
            System.out.println("addCurrency USDT code=" + cur.getResultCode());
        }
        CommandResultView add = api.addUser(uid);
        System.out.println("addUser uid=" + uid + " code=" + add.getResultCode());
        CommandResultView dep = api.adjustUserBalance(uid, System.nanoTime(), USDT_ID, 1_000_000_000.0);
        System.out.println("warmup deposit code=" + dep.getResultCode());
    }

    /** 顺序模式：1 个连接，前一条完成再发下一条。测同步路径 fsync 延迟下限。 */
    private static void runSeq(ExchangeApi api, long uid, int durationSec) {
        long endNanos = System.nanoTime() + durationSec * 1_000_000_000L;
        long count = 0;
        long latSum = 0;
        long latMax = 0;
        AtomicLong txId = new AtomicLong(System.currentTimeMillis() * 1_000L);
        long start = System.nanoTime();
        while (System.nanoTime() < endNanos) {
            long t0 = System.nanoTime();
            CommandResultView res = api.adjustUserBalance(uid, txId.getAndIncrement(), USDT_ID, 0.01);
            long latNanos = System.nanoTime() - t0;
            latSum += latNanos;
            if (latNanos > latMax)
                latMax = latNanos;
            count++;
            if (res == null)
                break;
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        double tps = count * 1000.0 / elapsedMs;
        double avgLatMs = count == 0 ? 0 : (latSum / count) / 1_000_000.0;
        System.out.printf("[seq]    count=%d elapsed=%dms tps=%.0f avgLat=%.2fms maxLat=%.2fms%n", count, elapsedMs,
            tps, avgLatMs, latMax / 1_000_000.0);
    }

    /** 并发模式：concurrency 个 worker 各自异步灌，统计累计 commit 数。测 jraft 内部 batching 能跑多快。 */
    private static void runBurst(ExchangeApi api, long uid, int concurrency, int durationSec) {
        long endNanos = System.nanoTime() + durationSec * 1_000_000_000L;
        AtomicLong txId = new AtomicLong(System.currentTimeMillis() * 1_000L + 1_000_000_000_000L);
        LongAdder completed = new LongAdder();
        LongAdder failed = new LongAdder();
        LongAdder latSumNs = new LongAdder();
        AtomicLong latMaxNs = new AtomicLong(0);

        long start = System.nanoTime();
        List<CompletableFuture<Void>> workers = new ArrayList<>(concurrency);
        for (int i = 0; i < concurrency; i++) {
            workers.add(CompletableFuture.runAsync(() -> {
                while (System.nanoTime() < endNanos) {
                    long t0 = System.nanoTime();
                    CompletableFuture<CommandResultView> fut =
                        api.adjustUserBalanceAsync(uid, txId.getAndIncrement(), USDT_ID, 0.01);
                    try {
                        CommandResultView res = fut.get(30, java.util.concurrent.TimeUnit.SECONDS);
                        long latNs = System.nanoTime() - t0;
                        latSumNs.add(latNs);
                        long prev = latMaxNs.get();
                        while (latNs > prev && !latMaxNs.compareAndSet(prev, latNs))
                            prev = latMaxNs.get();
                        if (res != null && res.getResultCode().name().startsWith("SUCCESS")) {
                            completed.increment();
                        } else if (res == null) {
                            failed.increment();
                        } else {
                            completed.increment();
                        }
                    } catch (Exception e) {
                        failed.increment();
                    }
                }
            }));
        }
        for (CompletableFuture<Void> f : workers) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long total = completed.sum() + failed.sum();
        double tps = total * 1000.0 / elapsedMs;
        double avgLatMs = total == 0 ? 0 : (latSumNs.sum() / (double)total) / 1_000_000.0;
        System.out.printf("[burst c=%d] total=%d ok=%d fail=%d elapsed=%dms tps=%.0f avgLat=%.2fms maxLat=%.2fms%n",
            concurrency, total, completed.sum(), failed.sum(), elapsedMs, tps, avgLatMs, latMaxNs.get() / 1_000_000.0);
    }

    private static String[] resolveLeader() {
        String explicitHost = System.getProperty("GRPC_HOST");
        String explicitPort = System.getProperty("GRPC_PORT");
        if (explicitHost != null && explicitPort != null)
            return new String[] {explicitHost, explicitPort};
        for (int mgmt : MGMT_PORTS) {
            try {
                HttpURLConnection conn =
                    (HttpURLConnection)new URL("http://" + MGMT_HOST + ":" + mgmt + "/raft/cluster").openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                if (conn.getResponseCode() == 200) {
                    String body = new String(conn.getInputStream().readAllBytes());
                    JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                    JsonObject leader = json.getAsJsonObject("leader");
                    return new String[] {leader.get("host").getAsString(),
                        String.valueOf(leader.get("grpc_port").getAsInt())};
                }
            } catch (Exception ignored) {
            }
        }
        return new String[] {"127.0.0.1", "5001"};
    }
}
