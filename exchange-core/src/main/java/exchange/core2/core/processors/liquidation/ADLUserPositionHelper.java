package exchange.core2.core.processors.liquidation;

import exchange.core2.core.common.ADLUserPosition;
import lombok.RequiredArgsConstructor;

import java.util.function.Supplier;

import static exchange.core2.core.ExchangeCore.EVENTS_POOLING;

@RequiredArgsConstructor
public class ADLUserPositionHelper {

    private final Supplier<ADLUserPosition> eventSupplier;
    private ADLUserPosition chainHead;

    public ADLUserPosition newADLUserPosition() {
        if (EVENTS_POOLING) {
            if (chainHead == null) {
                chainHead = eventSupplier.get();
            }
            final ADLUserPosition adlUserPosition = chainHead;
            chainHead = chainHead.next;
            adlUserPosition.reset(); // 会断掉链表，借出的对象应该和下面new的对象等价
            return adlUserPosition;
        } else {
            return new ADLUserPosition();
        }
    }
}
