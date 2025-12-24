package exchange.core2.core.processors;

import exchange.core2.core.common.ADLCandidate;
import lombok.RequiredArgsConstructor;

import java.util.function.Supplier;

import static exchange.core2.core.ExchangeCore.EVENTS_POOLING;

@RequiredArgsConstructor
public class ADLCandidateHelper {

    private final Supplier<ADLCandidate> eventSupplier;
    private ADLCandidate candidatesChainHead;

    public ADLCandidate newAdlCandidate() {
        if (EVENTS_POOLING) {
            if (candidatesChainHead == null) {
                candidatesChainHead = eventSupplier.get();
            }
            final ADLCandidate candidate = candidatesChainHead;
            candidatesChainHead = candidatesChainHead.nextCandidate;
            candidate.reset(); // 会断掉链表，借出的对象应该和下面new的对象等价
            return candidate;
        } else {
            return new ADLCandidate();
        }
    }
}
