package exchange.core2.core.processors.liquidation;

import exchange.core2.core.common.ADLUserPosition;
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.common.SymbolPositionRecord;
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

    public static long riskScore(SymbolPositionRecord pos, long bankruptcyPrice) {
        int sign = pos.direction.getMultiplier();
        long unrealizedPnl = sign * (bankruptcyPrice * pos.openVolume - pos.openPriceSum);
        long actualLeverage = pos.openPriceSum / pos.openInitMarginSum;
        return actualLeverage * unrealizedPnl * pos.adlEligibility;
    }

    /**
     * | 63........32 | 31......12 | 11 | 10......0 |
     * |    symbol    |  uidHash   | s  |  tsPart   |
     */
    public static long generateADLOrderId(SymbolPositionRecord pos) {
        long uidHash = (pos.uid * 31 + 17) & 0xFFFFF; // 取前 20 bit
        long sideBit = (pos.direction == PositionDirection.SHORT) ? 1L : 0L;
        long tsPart = (System.currentTimeMillis() / 1000) & 0xFFF; // 取后11bit，支持2048秒 ≈ 34分钟内不重复
        return ((long) pos.symbol << 32) | (uidHash << 12) | (sideBit << 11) | tsPart;
    }

}
