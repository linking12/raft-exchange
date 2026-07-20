/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package exchange.core2.tests.unit;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SequenceBarrier;
import exchange.core2.core.common.CoreWaitStrategy;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.core.processors.GroupingProcessor;
import exchange.core2.core.processors.R2Sync;
import exchange.core2.core.processors.SharedPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁 R2Sync 的命令分类，及 GroupingProcessor 让 reprice 独占 group（reprice 的 R1 读跨 shard 聚合借贷池，须先冲完前组
 * 全部 R2 才读到确定值，否则各副本因 batch 边界漂移读到不同池值 → 利率 → 累加器永久分叉）。
 */
class GroupingProcessorRepriceSyncTest {

    @Test
    void needSyncR2Global_onlyReprice() {
        assertTrue(R2Sync.needSyncR2Global(cmd(OrderCommandType.REPRICE_LOAN_RATES)));
        assertFalse(R2Sync.needSyncR2Global(cmd(OrderCommandType.LOAN_CROSS_FORCE_LIQUIDATE)));
        assertFalse(R2Sync.needSyncR2Global(cmd(OrderCommandType.LOAN_CREATE)));
        assertFalse(R2Sync.needSyncR2Global(cmd(OrderCommandType.PLACE_ORDER)));
    }

    @Test
    void loanCommands_classification() {
        // 借款读借贷池 → symbol 级 consumer（key 走哨兵）；自身不是 R2 写方
        assertTrue(R2Sync.needSyncR2ForSymbol(cmd(OrderCommandType.LOAN_CREATE)));
        assertTrue(R2Sync.needSyncR2ForSymbol(cmd(OrderCommandType.LOAN_CROSS_BORROW)));
        assertFalse(R2Sync.takesEffectInR2(cmd(OrderCommandType.LOAN_CREATE)));
        // loan 强平在 R2 结算借贷池 → 写方；自身不是 symbol consumer
        assertTrue(R2Sync.takesEffectInR2(cmd(OrderCommandType.LOAN_FORCE_LIQUIDATE)));
        assertTrue(R2Sync.takesEffectInR2(cmd(OrderCommandType.LOAN_CROSS_FORCE_LIQUIDATE)));
        assertFalse(R2Sync.needSyncR2ForSymbol(cmd(OrderCommandType.LOAN_FORCE_LIQUIDATE)));
    }

    @Test
    void futuresCommands_classification() {
        assertTrue(R2Sync.takesEffectInR2(cmd(OrderCommandType.PLACE_ORDER)));
        assertTrue(R2Sync.needSyncR2ForSymbol(cmd(OrderCommandType.MARKPRICE_ADJUSTMENT)));
        assertTrue(R2Sync.needSyncR2ForUidSymbol(cmd(OrderCommandType.CLOSE_POSITION)));
        assertFalse(R2Sync.needSyncR2ForSymbol(cmd(OrderCommandType.PLACE_ORDER)));
        assertFalse(R2Sync.needSyncR2ForUidSymbol(cmd(OrderCommandType.PLACE_ORDER))); // 非 reduce-only
    }

    @Test
    @Timeout(10)
    void reprice_forcesOwnGroup_priorLiquidationDoesNot() throws Exception {
        final int size = 1024;
        final RingBuffer<OrderCommand> ringBuffer = RingBuffer.createSingleProducer(
            () -> new OrderCommand(1), size, CoreWaitStrategy.BLOCKING.getDisruptorWaitStrategyFactory().get());
        final SequenceBarrier barrier = ringBuffer.newBarrier();
        final PerformanceConfiguration perfCfg = PerformanceConfiguration.baseBuilder()
            .ringBufferSize(size)
            .msgsInGroupLimit(200)                  // 远大于本例命令数，不因条数切组
            .maxGroupDurationNs(Integer.MAX_VALUE)  // 关时间切组，只留命令类型边界，断言确定
            .build();
        final GroupingProcessor grouping = new GroupingProcessor(ringBuffer, barrier, perfCfg,
            CoreWaitStrategy.BLOCKING, SharedPool.createTestSharedPool());
        ringBuffer.addGatingSequences(grouping.getSequence());

        // 先 publish 全部命令再起消费线程：一次 inner-loop 处理完，命令间零空闲，切组仅由命令类型决定
        final OrderCommandType[] stream = {
            OrderCommandType.PLACE_ORDER,
            OrderCommandType.LOAN_CROSS_FORCE_LIQUIDATE, // 在 R2 结算借贷池，但自身不切组
            OrderCommandType.REPRICE_LOAN_RATES,         // 切组：独占新 group
            OrderCommandType.PLACE_ORDER,                // 归入 reprice 的新 group
        };
        long lastSeq = -1;
        for (OrderCommandType type : stream) {
            final long seq = ringBuffer.next();
            ringBuffer.get(seq).command = type;
            ringBuffer.publish(seq);
            lastSeq = seq;
        }

        final Thread thread = new Thread(grouping, "grouping-test");
        thread.start();
        try {
            final long deadline = System.currentTimeMillis() + 5_000;
            while (grouping.getSequence().get() < lastSeq && System.currentTimeMillis() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(lastSeq, grouping.getSequence().get(), "grouping 未在超时内处理完全部命令");

            final long gPlaceBefore = ringBuffer.get(0).eventsGroup;
            final long gLiquidation = ringBuffer.get(1).eventsGroup;
            final long gReprice = ringBuffer.get(2).eventsGroup;
            final long gPlaceAfter = ringBuffer.get(3).eventsGroup;

            assertEquals(gPlaceBefore, gLiquidation, "强平命令不应触发切组");
            assertEquals(gLiquidation + 1, gReprice, "REPRICE 必须独占新 group（组边界同步冲前组 R2 后再读池）");
            assertEquals(gReprice, gPlaceAfter, "reprice 之后的命令归入其新 group");
        } finally {
            grouping.halt();
            thread.join(2_000);
        }
    }

    private static OrderCommand cmd(OrderCommandType type) {
        final OrderCommand cmd = new OrderCommand(1);
        cmd.command = type;
        return cmd;
    }
}
