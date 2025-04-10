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

import java.util.List;

import exchange.core2.collections.queue.DisruptorBlockingQueue;
import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.MatcherTradeEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class SharedPool {

    private final DisruptorBlockingQueue<MatcherTradeEvent> eventChainsBuffer;

    private final DisruptorBlockingQueue<FundEvent> fundEventBuffer;

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

        this.eventChainsBuffer = new DisruptorBlockingQueue<>(poolMaxSize);
        this.fundEventBuffer = new DisruptorBlockingQueue<>(poolMaxSize);
        this.chainLength = chainLength;

        for (int i = 0; i < poolInitialSize; i++) {
            this.eventChainsBuffer.add(MatcherTradeEvent.createEventChain(chainLength));
        }

        for (int i = 0; i < poolInitialSize; i++) {
            this.fundEventBuffer.add(new FundEvent());
        }

    }

    /**
     * Request next chain from buffer Threadsafe
     *
     * @return chain, otherwise null
     */
    public MatcherTradeEvent getChain() {
        MatcherTradeEvent poll = eventChainsBuffer.poll();
        // log.debug("<<< POLL CHAIN HEAD size={}", poll == null ? 0 : poll.getChainSize());
        if (poll == null) {
            poll = MatcherTradeEvent.createEventChain(chainLength);
        }

        return poll;
    }

    /**
     * Offers next chain. Threadsafe (single producer safety is sufficient)
     *
     * @param head - pointer to the first element
     */
    public void putChain(MatcherTradeEvent head) {
        boolean offer = eventChainsBuffer.offer(head);
        // log.debug(">>> OFFER CHAIN HEAD size={} orrder={}", head.getChainSize(), offer);
    }

    public FundEvent getFundEventPool() {
        FundEvent poll = fundEventBuffer.poll();
        if (poll == null) {
            poll = new FundEvent();
        }
        return poll;
    }

    public void putFundEventPool(FundEvent polled) {
        boolean offer = fundEventBuffer.offer(polled);
        // log.debug(">>> OFFER CHAIN HEAD size={} orrder={}", head.getChainSize(), offer);
    }

    public void putFundEventPool(List<FundEvent> polled) {
        polled.forEach(fundEvent -> {
            boolean offer = fundEventBuffer.offer(fundEvent);
        });
        // log.debug(">>> OFFER CHAIN HEAD size={} orrder={}", head.getChainSize(), offer);
    }

}
