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
package exchange.core2.core.processors;

import com.lmax.disruptor.*;
import exchange.core2.core.common.CoreWaitStrategy;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public final class TwoStepMasterProcessor implements EventProcessor {
    private static final int IDLE = 0;
    private static final int HALTED = IDLE + 1;
    private static final int RUNNING = HALTED + 1;

    private static final int MASTER_SPIN_LIMIT = 5000;

    private final AtomicInteger running = new AtomicInteger(IDLE);
    private final DataProvider<OrderCommand> dataProvider;
    private final SequenceBarrier sequenceBarrier;
    private final WaitSpinningHelper waitSpinningHelper;
    private final SimpleEventHandler eventHandler;
    private final ExceptionHandler<OrderCommand> exceptionHandler;
    private final String name;
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

    @Setter
    private TwoStepSlaveProcessor slaveProcessor;

    public TwoStepMasterProcessor(final RingBuffer<OrderCommand> ringBuffer,
                                  final SequenceBarrier sequenceBarrier,
                                  final SimpleEventHandler eventHandler,
                                  final ExceptionHandler<OrderCommand> exceptionHandler,
                                  final CoreWaitStrategy coreWaitStrategy,
                                  final String name) {
        this.dataProvider = ringBuffer;
        this.sequenceBarrier = sequenceBarrier;
        this.waitSpinningHelper = new WaitSpinningHelper(ringBuffer, sequenceBarrier, MASTER_SPIN_LIMIT, coreWaitStrategy);
        this.eventHandler = eventHandler;
        this.exceptionHandler = exceptionHandler;
        this.name = name;
    }

    @Override
    public Sequence getSequence() {
        return sequence;
    }

    @Override
    public void halt() {
        running.set(HALTED);
        sequenceBarrier.alert();
    }

    @Override
    public boolean isRunning() {
        return running.get() != IDLE;
    }


    /**
     * It is ok to have another thread rerun this method after a halt().
     *
     * @throws IllegalStateException if this object instance is already running in a thread
     */
    @Override
    public void run() {
        if (running.compareAndSet(IDLE, RUNNING)) {
            sequenceBarrier.clearAlert();

            try {
                if (running.get() == RUNNING) {
                    processEvents();
                }
            } finally {
                running.set(IDLE);
            }
        }
    }

    private void processEvents() {

        Thread.currentThread().setName("Thread-" + name);

        long nextSequence = sequence.get() + 1L;

        long currentSequenceGroup = 0;

        LongHashSet pendingR2Symbols = new LongHashSet();
        LongHashSet pendingR2UidSymbols = new LongHashSet(); // (uid, symbol)

        // wait until slave processor has instructed to run
        while (!slaveProcessor.isRunning()) {
            Thread.yield();
        }

        while (true) {
            OrderCommand cmd = null;
            try {

                // should spin and also check another barrier
                final long availableSequence = waitSpinningHelper.tryWaitFor(nextSequence);

                if (nextSequence <= availableSequence) {
                    while (nextSequence <= availableSequence) {
                        cmd = dataProvider.get(nextSequence);

                        // switch to next group - let slave processor start doing its handling cycle
                        if (cmd.eventsGroup != currentSequenceGroup) {
                            publishProgressAndTriggerSlaveProcessor(nextSequence);
                            currentSequenceGroup = cmd.eventsGroup;
                            pendingR2Symbols.clear();
                            pendingR2UidSymbols.clear();
                        }
                        /**
                         * @Modified 因为单子撮完后是延迟在R2更新position信息的，因此在这些cmd执行前，需要让R2先执行完
                         */
                        if (cmd.needSyncR2ForSymbol() && pendingR2Symbols.remove(cmd.symbol)) {
                            publishProgressAndTriggerSlaveProcessor(nextSequence);
                        }
                        if (cmd.needSyncR2ForUidSymbol() && pendingR2UidSymbols.remove(uidSymbolKey(cmd.uid, cmd.symbol))) {
                            publishProgressAndTriggerSlaveProcessor(nextSequence);
                        }

                        boolean forcedPublish = eventHandler.onEvent(nextSequence, cmd);
                        nextSequence++;

                        //  标记 R2 sideEffect
                        if (cmd.hasR2SideEffect()) {
                            pendingR2Symbols.add(cmd.symbol);
                            pendingR2UidSymbols.add(uidSymbolKey(cmd.uid, cmd.symbol));
                        }

                        if (forcedPublish) {
                            sequence.set(nextSequence - 1);
                            waitSpinningHelper.signalAllWhenBlocking();
                        }

                        if (cmd.command == OrderCommandType.SHUTDOWN_SIGNAL) {
                            // having all sequences aligned with the ringbuffer cursor is a requirement for proper shutdown
                            // let following processors to catch up
                            publishProgressAndTriggerSlaveProcessor(nextSequence);
                        }
                    }
                    sequence.set(availableSequence);
                    waitSpinningHelper.signalAllWhenBlocking();
                }
            } catch (final AlertException ex) {
                if (running.get() != RUNNING) {
                    break;
                }
            } catch (final Throwable ex) {
                exceptionHandler.handleEventException(ex, nextSequence, cmd);
                sequence.set(nextSequence);
                waitSpinningHelper.signalAllWhenBlocking();
                nextSequence++;
            }

        }
    }

    private long uidSymbolKey(long uid, int symbol) {
        return (uid << 32) | (symbol & 0xffffffffL);
    }

    private void publishProgressAndTriggerSlaveProcessor(final long nextSequence) {
        sequence.set(nextSequence - 1);
        waitSpinningHelper.signalAllWhenBlocking();
        slaveProcessor.handlingCycle(nextSequence);
    }


    @Override
    public String toString() {
        return "TwoStepMasterProcessor{" + name + "}";
    }
}