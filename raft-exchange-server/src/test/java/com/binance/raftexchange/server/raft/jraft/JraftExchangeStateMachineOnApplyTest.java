package com.binance.raftexchange.server.raft.jraft;

import com.alipay.sofa.jraft.Closure;
import com.alipay.sofa.jraft.Iterator;
import com.alipay.sofa.jraft.Status;
import com.binance.raftexchange.server.exchange.ExchangeCalls;
import com.binance.raftexchange.stubs.request.ApiAddUser;
import com.binance.raftexchange.stubs.request.ApiBinaryDataCommand;
import com.binance.raftexchange.stubs.request.ApiCommand;
import com.binance.raftexchange.stubs.request.ApiPlaceOrder;
import com.binance.raftexchange.stubs.request.BatchAddCurrenciesCommand;
import com.binance.raftexchange.stubs.request.BinaryDataCommand;
import exchange.core2.core.ExchangeApi;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JraftExchangeStateMachine.onApply 多分支： 1. 一连串 batchable cmd 应该攒到批里一次 flush 2. 非 batchable (BINARY_DATA) 命令到来时必须先
 * flush 已攒批，再单独 apply 3. Closure 在 batch 路径上由 flushBatch 调 run(STATUS_OK)
 */
class JraftExchangeStateMachineOnApplyTest {

    @Test
    void allBatchableCommands_singleFlushAtEnd() {
        ExchangeApi api = mock(ExchangeApi.class);
        JraftExchangeStateMachine esm = new JraftExchangeStateMachine(new ExchangeCalls(api));

        // 3 个 batchable (AddUser)
        ByteBuffer[] entries = {wrap(addUser(1L)), wrap(addUser(2L)), wrap(addUser(3L))};
        esm.onApply(new FakeIter(entries, null));

        // 攒到尾再 flush 一次，size=3
        verify(api).submitBatchAsync(any(), any(), eq3());
        verify(api).submitBatchAsync(any(), any(), anyInt()); // 总共只 1 次
    }

    @Test
    void binaryData_flushesPendingBatchFirst_thenAppliesSeparately() {
        ExchangeApi api = mock(ExchangeApi.class);
        when(api.submitBinaryDataAsync(any(exchange.core2.core.common.api.binary.BinaryDataCommand.class)))
            .thenReturn(CompletableFuture.completedFuture(exchange.core2.core.common.cmd.CommandResultCode.SUCCESS));
        JraftExchangeStateMachine esm = new JraftExchangeStateMachine(new ExchangeCalls(api));

        // [AddUser, AddUser, BINARY_DATA(addCurrency), AddUser]
        ByteBuffer[] entries =
            {wrap(addUser(1L)), wrap(addUser(2L)), wrap(addCurrencyBinary(2, "USDT", 6)), wrap(addUser(3L)),};
        esm.onApply(new FakeIter(entries, null));

        // 第一次 flush: 2 个 (前面 2 个 AddUser) — BINARY_DATA 触发先 flush 已攒批
        // 第二次 flush: 1 个 (尾部 AddUser)
        // BINARY_DATA 通过 submitBinaryDataAsync 单独 apply 一次
        verify(api).submitBinaryDataAsync(any(exchange.core2.core.common.api.binary.BinaryDataCommand.class));
        verify(api).submitBatchAsync(any(), any(), eq2());
        verify(api).submitBatchAsync(any(), any(), eq1());
    }

    @Test
    void batchableCommands_closuresRunWithStatusOk() {
        ExchangeApi api = mock(ExchangeApi.class);
        JraftExchangeStateMachine esm = new JraftExchangeStateMachine(new ExchangeCalls(api));

        AtomicInteger okCount = new AtomicInteger();
        AtomicReference<Status> lastStatus = new AtomicReference<>();
        Closure recorder = status -> {
            if (status.isOk())
                okCount.incrementAndGet();
            lastStatus.set(status);
        };

        ByteBuffer[] entries = {wrap(addUser(1L)), wrap(addUser(2L)), wrap(addUser(3L))};
        esm.onApply(new FakeIter(entries, recorder));

        assertEquals(3, okCount.get(), "batch flush 时每个 closure 必须 run(STATUS_OK) 一次");
        assertTrue(lastStatus.get().isOk());
    }

    @Test
    void onlyBinaryData_noBatchFlush() {
        ExchangeApi api = mock(ExchangeApi.class);
        when(api.submitBinaryDataAsync(any(exchange.core2.core.common.api.binary.BinaryDataCommand.class)))
            .thenReturn(CompletableFuture.completedFuture(exchange.core2.core.common.cmd.CommandResultCode.SUCCESS));
        JraftExchangeStateMachine esm = new JraftExchangeStateMachine(new ExchangeCalls(api));

        ByteBuffer[] entries = {wrap(addCurrencyBinary(2, "USDT", 6))};
        esm.onApply(new FakeIter(entries, null));

        verify(api).submitBinaryDataAsync(any(exchange.core2.core.common.api.binary.BinaryDataCommand.class));
        verify(api, never()).submitBatchAsync(any(), any(), anyInt());
    }

    @Test
    void emptyIter_noOp() {
        ExchangeApi api = mock(ExchangeApi.class);
        JraftExchangeStateMachine esm = new JraftExchangeStateMachine(new ExchangeCalls(api));

        esm.onApply(new FakeIter(new ByteBuffer[0], null));

        verify(api, never()).submitBatchAsync(any(), any(), anyInt());
        verify(api, never()).submitBinaryDataAsync(any());
    }

    // ---- helpers ----

    private static ByteBuffer wrap(ApiCommand cmd) {
        return ByteBuffer.wrap(cmd.toByteArray());
    }

    private static ApiCommand addUser(long uid) {
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
            .setAddUser(ApiAddUser.newBuilder().setUid(uid)).build();
    }

    private static ApiCommand addCurrencyBinary(int id, String name, int digit) {
        com.binance.raftexchange.stubs.CoreCurrencySpecification c =
            com.binance.raftexchange.stubs.CoreCurrencySpecification.newBuilder().setId(id).setName(name)
                .setDigit(digit).build();
        return ApiCommand.newBuilder().setTimestamp(System.currentTimeMillis())
            .setBinaryData(ApiBinaryDataCommand.newBuilder().setTransferId(1).setData(BinaryDataCommand.newBuilder()
                .setAddCurrencies(BatchAddCurrenciesCommand.newBuilder().putCurrencies(id, c))))
            .build();
    }

    private static int eq3() {
        return org.mockito.ArgumentMatchers.intThat(i -> i == 3);
    }

    private static int eq2() {
        return org.mockito.ArgumentMatchers.intThat(i -> i == 2);
    }

    private static int eq1() {
        return org.mockito.ArgumentMatchers.intThat(i -> i == 1);
    }

    /** 最小化的 jraft Iterator，只支撑 ESM.onApply 真正用到的方法。 */
    private static final class FakeIter implements Iterator {
        private final ByteBuffer[] entries;
        private final Closure doneCallback;
        private int idx = -1;
        final List<Status> capturedDoneStatuses = new ArrayList<>();

        FakeIter(ByteBuffer[] entries, Closure doneCallback) {
            this.entries = entries;
            this.doneCallback = doneCallback;
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
        }

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
            return doneCallback == null ? null : status -> {
                capturedDoneStatuses.add(status);
                doneCallback.run(status);
            };
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
