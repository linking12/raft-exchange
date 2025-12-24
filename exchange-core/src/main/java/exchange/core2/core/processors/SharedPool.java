/*
 * Copyright 2020 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package exchange.core2.core.processors;

import exchange.core2.collections.queue.DisruptorBlockingQueue;
import exchange.core2.core.common.ADLCandidate;
import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.MatcherTradeEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class SharedPool {

    private final DisruptorBlockingQueue<MatcherTradeEvent> tradeEventChainsBuffer;

    private final DisruptorBlockingQueue<FundEvent> fundEventChainsBuffer;

    private final DisruptorBlockingQueue<ADLCandidate> adlCandidateChainsBuffer;

    @Getter
    private final int chainLength;

    public static SharedPool createTestSharedPool() {
        return new SharedPool(8, 4, 256);
    }

    /**
     * Create new shared pool
     *
     * @param poolMaxSize - max size of pool. Will skip new chains if chains buffer is full.
     * @param poolInitialSize - initial number of pre-generated chains. Recommended to set higher than number of modules
     *            - (RE+ME)*2.
     * @param chainLength - target chain length. Longer chain means rare requests for new chains. However longer chains
     *            can cause event placeholders starvation.
     */
    public SharedPool(final int poolMaxSize, final int poolInitialSize, final int chainLength) {
        if (poolInitialSize > poolMaxSize) {
            throw new IllegalArgumentException("too big poolInitialSize");
        }
        this.tradeEventChainsBuffer = new DisruptorBlockingQueue<>(poolMaxSize);
        this.fundEventChainsBuffer = new DisruptorBlockingQueue<>(poolMaxSize);
        this.adlCandidateChainsBuffer = new DisruptorBlockingQueue<>(poolMaxSize);
        this.chainLength = chainLength;
        for (int i = 0; i < poolInitialSize; i++) {
            this.tradeEventChainsBuffer.add(MatcherTradeEvent.createEventChain(chainLength));
            this.fundEventChainsBuffer.add(FundEvent.createEventChain(chainLength));
            this.adlCandidateChainsBuffer.add(ADLCandidate.createCandidateChain(chainLength));
        }

    }

    public MatcherTradeEvent getTradeEventChain() {
        MatcherTradeEvent poll = tradeEventChainsBuffer.poll();
        if (poll == null) {
            poll = MatcherTradeEvent.createEventChain(chainLength);
        }

        return poll;
    }

    public void putTradeEventChain(MatcherTradeEvent head) {
        tradeEventChainsBuffer.offer(head);
    }

    public FundEvent getFundEventChain() {
        FundEvent poll = fundEventChainsBuffer.poll();
        if (poll == null) {
            poll = FundEvent.createEventChain(chainLength);
        }
        return poll;
    }

    public void putFundEventChain(FundEvent head) {
        fundEventChainsBuffer.offer(head);
    }

    public ADLCandidate getAdlCandidateChain() {
        ADLCandidate poll = adlCandidateChainsBuffer.poll();
        if (poll == null) {
            poll = ADLCandidate.createCandidateChain(chainLength);
        }
        return poll;
    }

    public void putAdlCandidateChain(ADLCandidate head) {
        adlCandidateChainsBuffer.offer(head);
    }
}
