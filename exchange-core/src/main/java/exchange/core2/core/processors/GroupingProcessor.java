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
import exchange.core2.core.common.ADLCandidate;
import exchange.core2.core.common.CoreWaitStrategy;
import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.common.config.PerformanceConfiguration;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

import static exchange.core2.core.ExchangeCore.EVENTS_POOLING;

@Slf4j
public final class GroupingProcessor implements EventProcessor {
    private static final int IDLE = 0;
    private static final int HALTED = IDLE + 1;
    private static final int RUNNING = HALTED + 1;

    private static final int GROUP_SPIN_LIMIT = 1000;

    // TODO move into configuration
    private static final int L2_PUBLISH_INTERVAL_NS = 10_000_000;

    private final AtomicInteger running = new AtomicInteger(IDLE);
    private final RingBuffer<OrderCommand> ringBuffer;
    private final SequenceBarrier sequenceBarrier;
    private final WaitSpinningHelper waitSpinningHelper;
    private final Sequence sequence = new Sequence(Sequencer.INITIAL_CURSOR_VALUE);

    private final SharedPool sharedPool;

    private final int msgsInGroupLimit;
    private final long maxGroupDurationNs;

    public GroupingProcessor(RingBuffer<OrderCommand> ringBuffer,
                             SequenceBarrier sequenceBarrier,
                             PerformanceConfiguration perfCfg,
                             CoreWaitStrategy coreWaitStrategy,
                             SharedPool sharedPool) {

        if (perfCfg.getMsgsInGroupLimit() > perfCfg.getRingBufferSize() / 4) {
            throw new IllegalArgumentException("msgsInGroupLimit should be less than quarter ringBufferSize");
        }

        this.ringBuffer = ringBuffer;
        this.sequenceBarrier = sequenceBarrier;
        this.waitSpinningHelper = new WaitSpinningHelper(ringBuffer, sequenceBarrier, GROUP_SPIN_LIMIT, coreWaitStrategy);
        this.msgsInGroupLimit = perfCfg.getMsgsInGroupLimit();
        this.maxGroupDurationNs = perfCfg.getMaxGroupDurationNs();
        this.sharedPool = sharedPool;
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
        } else {
            // This is a little bit of guess work.  The running state could of changed to HALTED by
            // this point.  However, Java does not have compareAndExchange which is the only way
            // to get it exactly correct.
            if (running.get() == RUNNING) {
                throw new IllegalStateException("Thread is already running");
            }
        }
    }

    private void processEvents() {
        long nextSequence = sequence.get() + 1L;

        long groupCounter = 0;
        long msgsInGroup = 0;

        long groupLastNs = 0;

        long l2dataLastNs = 0;
        boolean triggerL2DataRequest = false;

        final int tradeEventChainLengthTarget = sharedPool.getChainLength();
        MatcherTradeEvent tradeEventHead = null;
        MatcherTradeEvent tradeEventTail = null;
        int tradeEventCounter = 0; // counter
        FundEvent fundEventHead = null;
        FundEvent fundEventTail = null;
        int fundEventCounter = 0;
        ADLCandidate adlCandidateHead = null;
        ADLCandidate adlCandidateTail = null;
        int adlCandidateCounter = 0;

        boolean groupingEnabled = true;

        while (true) {
            try {

                // should spin and also check another barrier
                long availableSequence = waitSpinningHelper.tryWaitFor(nextSequence);

                if (nextSequence <= availableSequence) {
                    while (nextSequence <= availableSequence) {

                        final OrderCommand cmd = ringBuffer.get(nextSequence);

                        nextSequence++;

                        if (cmd.command == OrderCommandType.GROUPING_CONTROL) {
                            groupingEnabled = cmd.orderId == 1;
                            cmd.resultCode = CommandResultCode.SUCCESS;
                        }

                        if (!groupingEnabled) {
                            // TODO pooling
                            cmd.matcherEvent = null;
                            cmd.marketData = null;
                            continue;
                        }

                        // some commands should trigger R2 stage to avoid unprocessed events that could affect accounting state
                        if (cmd.command == OrderCommandType.RESET
                                || cmd.command == OrderCommandType.PERSIST_STATE_MATCHING
                                || cmd.command == OrderCommandType.GROUPING_CONTROL) {
                            groupCounter++;
                            msgsInGroup = 0;
                        }

                        // report/binary commands also should trigger R2 stage, but only for last message
                        if ((cmd.command == OrderCommandType.BINARY_DATA_COMMAND || cmd.command == OrderCommandType.BINARY_DATA_QUERY) && cmd.symbol == -1) {
                            groupCounter++;
                            msgsInGroup = 0;
                        }

                        cmd.eventsGroup = groupCounter;


                        if (triggerL2DataRequest) {
                            triggerL2DataRequest = false;
                            cmd.serviceFlags = 1;
                        } else {
                            cmd.serviceFlags = 0;
                        }

                        // cleaning attached events
                        if (EVENTS_POOLING && cmd.matcherEvent != null) {
                            if (tradeEventTail == null) {
                                tradeEventHead = cmd.matcherEvent; //?
                            } else {
                                tradeEventTail.nextEvent = cmd.matcherEvent;
                            }
                            tradeEventTail = cmd.matcherEvent;
                            tradeEventCounter++;

                            // find last element in the chain and update tail accordingly
                            while (tradeEventTail.nextEvent != null) {
                                tradeEventTail = tradeEventTail.nextEvent;
                                tradeEventCounter++;
                            }

                            if (tradeEventCounter >= tradeEventChainLengthTarget) {
                                // chain is big enough -> send to the shared pool
                                tradeEventCounter = 0;
                                sharedPool.putTradeEventChain(tradeEventHead);
                                tradeEventTail = null;
                                tradeEventHead = null;
                            }
                        }

                        /**
                         * @modify 回收当前orderCommand下面的fundEvent
                         */
                        if (EVENTS_POOLING) {
                            if (cmd.takerFundEvents != null && cmd.command != OrderCommandType.SYSTEM_LIQUIDATION_NOTIFY) {
                                if (fundEventTail == null) {
                                    fundEventHead = cmd.takerFundEvents;
                                } else {
                                    fundEventTail.nextEvent = cmd.takerFundEvents;
                                }
                                fundEventTail = cmd.takerFundEvents;
                                fundEventCounter++;
                                // 找到 tail
                                while (fundEventTail.nextEvent != null) {
                                    fundEventTail = fundEventTail.nextEvent;
                                    fundEventCounter++;
                                }
                                cmd.takerFundEvents = null;
                            }

                            // 回收每个 maker shard 的链
                            if (cmd.makerFundEventsByShard != null) {
                                for (int i = 0; i < cmd.makerFundEventsByShard.length; i++) {
                                    FundEvent shardHead = cmd.makerFundEventsByShard[i];
                                    if (shardHead != null) {
                                        if (fundEventTail == null) {
                                            fundEventHead = shardHead;
                                        } else {
                                            fundEventTail.nextEvent = shardHead;
                                        }
                                        fundEventTail = shardHead;
                                        fundEventCounter++;

                                        while (fundEventTail.nextEvent != null) {
                                            fundEventTail = fundEventTail.nextEvent;
                                            fundEventCounter++;
                                        }

                                        cmd.makerFundEventsByShard[i] = null;
                                    }
                                }
                            }

                            if (fundEventCounter >= tradeEventChainLengthTarget) {
                                sharedPool.putFundEventChain(fundEventHead);
                                fundEventHead = null;
                                fundEventTail = null;
                                fundEventCounter = 0;
                            }
                        }

                        /**
                         * @modify 回收当前orderCommand下面的adlCandidate
                         */
                        if (EVENTS_POOLING) {
                            // 回收每个 adlCandidate shard 的链
                            if (cmd.adlCandidatesByShard != null) {
                                for (int i = 0; i < cmd.adlCandidatesByShard.length; i++) {
                                    ADLCandidate shardHead = cmd.adlCandidatesByShard[i];
                                    if (shardHead != null) {
                                        if (adlCandidateTail == null) {
                                            adlCandidateHead = shardHead;
                                        } else {
                                            adlCandidateTail.nextCandidate = shardHead;
                                        }
                                        adlCandidateTail = shardHead;
                                        adlCandidateCounter++;

                                        while (adlCandidateTail.nextCandidate != null) {
                                            adlCandidateTail = adlCandidateTail.nextCandidate;
                                            adlCandidateCounter++;
                                        }

                                        cmd.adlCandidatesByShard[i] = null;
                                    }
                                }
                            }

                            if (adlCandidateCounter >= tradeEventChainLengthTarget) {
                                sharedPool.putAdlCandidateChain(adlCandidateHead);
                                adlCandidateHead = null;
                                adlCandidateTail = null;
                                adlCandidateCounter = 0;
                            }
                        }

                        cmd.matcherEvent = null;
                        cmd.takerFundEvents = null;
                        cmd.makerFundEventsByShard = null;
                        cmd.adlCandidatesByShard = null;
                        // TODO collect to shared buffer
                        cmd.marketData = null;

                        msgsInGroup++;

                        // switch group after each N messages
                        // avoid changing groups when PERSIST_STATE_MATCHING is already executing
                        if (msgsInGroup >= msgsInGroupLimit && cmd.command != OrderCommandType.PERSIST_STATE_RISK) {
                            groupCounter++;
                            msgsInGroup = 0;
                        }

                    }
                    sequence.set(availableSequence);
                    waitSpinningHelper.signalAllWhenBlocking();
                    groupLastNs = System.nanoTime() + maxGroupDurationNs;

                } else {
                    final long t = System.nanoTime();
                    if (msgsInGroup > 0 && t > groupLastNs) {
                        // switch group after T microseconds elapsed, if group is non empty
                        groupCounter++;
                        msgsInGroup = 0;
                    }

                    if (t > l2dataLastNs) {
                        // TODO fix order best price updating mechanism,
                        //  this does not work for multi-symbol configuration

                        l2dataLastNs = t + L2_PUBLISH_INTERVAL_NS; // trigger L2 data every 10ms
                        triggerL2DataRequest = true;
                    }
                }

            } catch (final AlertException ex) {
                if (running.get() != RUNNING) {
                    break;
                }
            } catch (final Throwable ex) {
                sequence.set(nextSequence);
                waitSpinningHelper.signalAllWhenBlocking();
                nextSequence++;
            }
        }
    }

    @Override
    public String toString() {
        return "GroupingProcessor{" +
                "GL=" + msgsInGroupLimit +
                '}';
    }
}