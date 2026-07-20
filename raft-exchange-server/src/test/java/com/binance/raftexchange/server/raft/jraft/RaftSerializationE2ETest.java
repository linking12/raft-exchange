package com.binance.raftexchange.server.raft.jraft;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Status;
import com.binance.raftexchange.stubs.BalanceAdjustmentType;
import com.binance.raftexchange.stubs.CoreCurrencySpecification;
import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiAdjustUserBalance;
import com.binance.raftexchange.stubs.request.ApiBinaryDataCommand;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.BatchAddCurrenciesCommand;
import com.binance.raftexchange.stubs.request.BinaryDataCommand;
import com.binance.raftexchange.server.exchange.ExchangeCalls;
import exchange.core2.core.ExchangeApi;
import exchange.core2.core.ExchangeCore;
import exchange.core2.core.IFundEventsHandler;
import exchange.core2.core.ITradeEventsHandler;
import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.config.ExchangeConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 真"走一遍 raft"的端到端测试：
 *
 * 客户端 double ─doubleToLong─► long │ ▼ protobuf ApiCommand.toByteArray() │ [模拟 raft log apply] │ ▼
 * JraftExchangeStateMachine.onApply(Iterator) │ SerializeHelper.deserializeWithType() convertToExchangeCommand() //
 * proto → core POJO flushBatch() → ExchangeApi.submitBatchAsync() │ ▼ ExchangeCore 处理 │ ▼ FundEvent (long, 携带
 * currencyScaleK) │ ▼ 还原: long / currencyScaleK = double
 *
 * 断言：还原值 == 客户端输入。
 *
 * 不做的：jraft 网络、gRPC、Kafka、disk persistence。这些都跟"精度/序列化"无关。 做的：proto 序列化、ApiCommand 反序列化、proto→POJO 转换、ESM
 * 批处理、ExchangeCore 处理、 FundEvent 缩放系数、FundEventRestorer 等价还原逻辑。
 *
 * 运行：mvn -pl raft-exchange-server test -Dtest=RaftSerializationE2ETest
 */
class RaftSerializationE2ETest {

    private static final int USDT_ID = 2;
    private static final int USDT_DIGIT = 6;
    private static final long USDT_SCALE = 1_000_000L; // 10^6
    private static final long UID = 42L;
    private static final double EPS = 1e-9;

    /** FundEventReport 是池化对象，handler 返回后立即被 recycle。立即拷贝出来。 */
    private static final class EventSnapshot {
        final FundEvent.FundEventType type;
        final long free;
        final long currencyScaleK;

        EventSnapshot(IFundEventsHandler.FundEventReport r) {
            this.type = r.getEventType();
            this.free = r.getBalances().getFree();
            this.currencyScaleK = r.getBalances().getCurrencyScaleK();
        }
    }

    @Test
    void roundTripThroughRaftSerialization() throws Exception {
        List<EventSnapshot> captured = new ArrayList<>();

        SimpleEventsProcessor processor = new SimpleEventsProcessor(new ITradeEventsHandler() {
            @Override
            public void spotExecutionReport(SpotExecutionReport r) {}

            @Override
            public void futuresExecutionReport(FuturesExecutionReport r) {}

            @Override
            public void orderBook(OrderBook ob) {}
        }, r -> captured.add(new EventSnapshot(r)));

        ExchangeCore core = ExchangeCore.builder().resultsConsumer(processor)
            .exchangeConfiguration(ExchangeConfiguration.defaultBuilder().build()).build();
        core.startup();
        ExchangeApi api = core.getApi();

        try {
            // ===== 客户端：把 5 个操作编成 proto ApiCommand =====
            // 1) 注册 USDT (BinaryData，非 batchable，走单独 apply 路径)
            ApiCommand addCur = buildAddCurrency(USDT_ID, "USDT", USDT_DIGIT);
            // 2) 创建用户 (batchable)
            ApiCommand addUser = buildAddUser(UID);
            // 3) deposit +1000.0 (batchable)
            ApiCommand dep1000 = buildAdjustBalance(UID, USDT_ID, +1000.0, USDT_DIGIT, 1);
            // 4) withdraw -2.3 (62f1142 回归点)
            ApiCommand wd23 = buildAdjustBalance(UID, USDT_ID, -2.3, USDT_DIGIT, 2);
            // 5) withdraw -100.123456 (多位小数)
            ApiCommand wdMulti = buildAdjustBalance(UID, USDT_ID, -100.123456, USDT_DIGIT, 3);

            ByteBuffer[] logEntries = {ByteBuffer.wrap(addCur.toByteArray()), ByteBuffer.wrap(addUser.toByteArray()),
                ByteBuffer.wrap(dep1000.toByteArray()), ByteBuffer.wrap(wd23.toByteArray()),
                ByteBuffer.wrap(wdMulti.toByteArray()),};

            for (int i = 0; i < logEntries.length; i++) {
                System.out.printf("[CLIENT  ] log[%d] %s   ApiCommand %d bytes%n", i, describe(i),
                    logEntries[i].remaining());
            }

            // 注入 in-process api 给 esm；CommandRegistry / processBinaryDataCommand 都用注入的 api，BINARY_DATA path 也走得通
            JraftExchangeStateMachine esm = new JraftExchangeStateMachine(new ExchangeCalls(api));
            esm.onApply(new FakeIter(logEntries));

            Thread.sleep(300);
        } finally {
            core.shutdown();
        }

        // ===== 服务端：FundEvent.free (long) → double (与 FundEventRestorer 等价) =====
        assertEquals(3, captured.size(),
            "期望收到 3 个余额事件 (DEPOSIT + 2 × WITHDRAW)，addCurrency 走 BINARY_DATA 不产生 FundEvent");

        double[] expectedRemaining = {1000.0, 997.7, 897.576544};
        for (int i = 0; i < captured.size(); i++) {
            EventSnapshot e = captured.get(i);
            double restored = e.free / (double)e.currencyScaleK;
            System.out.printf("[RESTORE ] event[%d] %-8s  free=%+,15d / 10^%d = %.6f USDT%n", i, e.type, e.free,
                USDT_DIGIT, restored);
            assertEquals(expectedRemaining[i], restored, EPS, "round-trip 失败 @ event " + i);
        }

        System.out.println();
        System.out.println("✓ proto 序列化 → ESM 反序列化/转换/批处理 → ExchangeCore → FundEvent 还原 整条链路对称");
    }

    private static String describe(int i) {
        return switch (i) {
            case 0 -> "addCurrency(USDT)";
            case 1 -> "addUser(42)";
            case 2 -> "deposit  +1000.000000";
            case 3 -> "withdraw -2.300000";
            case 4 -> "withdraw -100.123456";
            default -> "?";
        };
    }

    // ---- 客户端编码 (镜像 raft-exchange-client/ExchangeApi 构建逻辑) ----

    private static ApiCommand buildAddCurrency(int id, String name, int digit) {
        CoreCurrencySpecification c =
            CoreCurrencySpecification.newBuilder().setId(id).setName(name).setDigit(digit).build();
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
            .setBinaryData(ApiBinaryDataCommand.newBuilder().setTransferId(1).setData(BinaryDataCommand.newBuilder()
                .setAddCurrencies(BatchAddCurrenciesCommand.newBuilder().putCurrencies(id, c))))
            .build();
    }

    private static ApiCommand buildAddUser(long uid) {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
            .setAddUser(ApiAddUser.newBuilder().setUid(uid)).build();
    }

    private static ApiCommand buildAdjustBalance(long uid, int currency, double amount, int digit, long txId) {
        long currencyScaleK = pow10(digit);
        long amountLong = doubleToLong(amount, currencyScaleK);
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
            .setAdjustBalance(ApiAdjustUserBalance.newBuilder().setUid(uid).setCurrency(currency).setAmount(amountLong)
                .setTransactionId(txId).setAdjustmentType(BalanceAdjustmentType.ADJUSTMENT))
            .build();
    }

    /** 镜像 raft-exchange-client/ExchangeApiHelper#doubleToLong（62f1142 修复后版本）。 */
    private static long doubleToLong(double value, long valueScaleK) {
        if (!Double.isFinite(value)) {
            throw new ArithmeticException("Value must be finite: " + value);
        }
        double scaled = value * valueScaleK;
        if (scaled >= 9.223372036854776E18 || scaled < Long.MIN_VALUE) {
            throw new ArithmeticException("Value too large: " + value);
        }
        return Math.round(scaled);
    }

    private static long pow10(int digit) {
        long r = 1L;
        for (int i = 0; i < digit; i++)
            r *= 10;
        return r;
    }

    // ---- 极简 jraft Iterator 实现：只支撑 JraftExchangeStateMachine#onApply 真正用到的方法 ----

    private static final class FakeIter implements Iterator {
        private final ByteBuffer[] entries;
        private int idx = -1;

        FakeIter(ByteBuffer[] entries) {
            this.entries = entries;
        }

        @Override
        public boolean hasNext() {
            return idx + 1 < entries.length;
        }

        @Override
        public ByteBuffer next() {
            idx++;
            return entries[idx];
        }

        @Override
        public ByteBuffer getData() {
            return entries[idx + 1];
        } // ESM 在 next() 前调用

        @Override
        public long getIndex() {
            return idx + 1;
        }

        @Override
        public long getTerm() {
            return 1L;
        }

        @Override
        public Closure done() {
            return null;
        }

        @Override
        public void setAutoCommitPerLog(boolean status) {}

        @Override
        public boolean commit() {
            return true;
        }

        @Override
        public void commitAndSnapshotSync(Closure done) {
            done.run(Status.OK());
        }

        @Override
        public void setErrorAndRollback(long ntail, Status st) {
            throw new IllegalStateException("rollback in test: " + st + " ntail=" + ntail);
        }
    }
}
