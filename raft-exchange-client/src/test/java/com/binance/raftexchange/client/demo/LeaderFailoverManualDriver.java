package com.binance.raftexchange.client.demo;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import com.binance.raftexchange.client.CommandResultView;
import com.binance.raftexchange.client.ExchangeApi;
import com.binance.raftexchange.client.ExchangeApiOptions;

/**
 * 手动跑：起一个长寿命 ExchangeApi 连任意节点，每秒发一笔 adjustUserBalance。 期间你在终端用 curl 触发 leader stepDown，观察 client 端日志能否看到完整 trace： 1.
 * ApiStream "NEED_MOVE received from server" 2. ExchangeClient "switchToNewLeader begin/swapped/done" 触发 stepDown
 * 的命令（leader mgmt 端口对应 grpc port + 23080，比如 5001 -> 28081）： curl -X POST http://localhost:28081/raft/stepdown
 *
 * <p>
 * 用法：
 * 
 * <pre>
 *   mvn -pl raft-exchange-client test-compile
 *   mvn -pl raft-exchange-client exec:java \
 *       -Dexec.classpathScope=test \
 *       -Dexec.mainClass=com.binance.raftexchange.client.demo.LeaderFailoverManualDriver \
 *       -Dexec.args="localhost 5001"
 * </pre>
 * 
 * 没装 exec-plugin 时直接拿 IDE 跑这个 main 即可。
 *
 * <p>
 * Ctrl+C 退出，driver 会优雅关闭 ExchangeApi。
 */
public final class LeaderFailoverManualDriver {

    private static final int USDT_ID = 2;
    private static final long UID = 6_900_000_001L;
    private static final double TICK_AMOUNT = 1.0;
    private static final Duration TICK_INTERVAL = Duration.ofSeconds(1);

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: LeaderFailoverManualDriver <host> <grpcPort> [uid]");
            System.exit(1);
        }
        String host = args[0];
        int grpcPort = Integer.parseInt(args[1]);
        long uid = args.length >= 3 ? Long.parseLong(args[2]) : UID;

        System.out.println("[driver] connecting to " + host + ":" + grpcPort + " uid=" + uid);
        ExchangeApi api = ExchangeApi.connect(host, grpcPort,
            ExchangeApiOptions.builder().sendTimeout(Duration.ofSeconds(15)).build());

        // 初始化（幂等：已存在的 currency / user 跳过）
        try {
            if (!api.getMetadataManager().currencyExists(USDT_ID)) {
                CommandResultView r = api.addCurrency(USDT_ID, "USDT", 6);
                System.out.println("[driver] addCurrency USDT -> " + r.getResultCode());
            }
            CommandResultView addUserResult = api.addUser(uid);
            System.out.println("[driver] addUser " + uid + " -> " + addUserResult.getResultCode());
        } catch (Exception e) {
            System.out.println("[driver] init step ignored (probably already exists): " + e.getMessage());
        }

        AtomicLong txSeq = new AtomicLong(System.currentTimeMillis());
        Thread shutdownHook = new Thread(() -> {
            System.out.println("[driver] shutting down ExchangeApi ...");
            try {
                api.close();
            } catch (Exception e) {
                System.err.println("[driver] close error: " + e.getMessage());
            }
        }, "driver-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        System.out.println("[driver] tick loop starts; trigger stepDown anytime via:");
        System.out.println("         curl -X POST http://<leader-host>:<leader-mgmt-port>/raft/stepdown");
        System.out.println();

        int tick = 0;
        while (!Thread.currentThread().isInterrupted()) {
            long tx = txSeq.incrementAndGet();
            long startNs = System.nanoTime();
            try {
                CommandResultView result = api.adjustUserBalance(uid, tx, USDT_ID, TICK_AMOUNT);
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
                System.out.printf("[driver] tick=%d tx=%d code=%s elapsedMs=%d%n", tick, tx, result.getResultCode(),
                    elapsedMs);
            } catch (Exception e) {
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
                System.out.printf("[driver] tick=%d tx=%d ERROR elapsedMs=%d cause=%s%n", tick, tx, elapsedMs,
                    e.getClass().getSimpleName() + ":" + e.getMessage());
            }
            tick++;
            try {
                Thread.sleep(TICK_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        System.out.println("[driver] exit");
    }

    private LeaderFailoverManualDriver() {}
}
