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
package exchange.core2.core.common;


import exchange.core2.core.common.cmd.OrderCommandType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// TODO move activeOrderCompleted, eventType, section into the order?
// TODO REDUCE needs remaining size (can write into size), bidderHoldPrice - can write into price
// TODO REJECT needs remaining size (can not write into size),

@AllArgsConstructor
@NoArgsConstructor
@Builder
public final class MatcherTradeEvent {

    public MatcherEventType eventType; // TRADE, REDUCE, REJECT (rare) or BINARY_EVENT (reports data)

    public int section;

    // TODO join (requires 11+ bits)
    // false, except when activeOrder is completely filled, removed or rejected
    // it is always true for REJECT event
    // it is true for REDUCE event if reduce was triggered by COMMAND
    public boolean activeOrderCompleted;

    // maker (for TRADE event type only)
    public long matchedOrderId;
    public long matchedOrderUid; // 0 for rejection
    public boolean matchedOrderCompleted; // false, except when matchedOrder is completely filled
    public OrderCommandType matchedOrderCommandType;
    public long matchedOrderFilled;
    public long matchedOrderFilledNotional;
    public OrderType matchedOrderType;
    public long matchedOrderPrice;
    public long matchedOrderSize;
    public int matchedUserCookie;
    public long matchedOrderTimestamp;

    // actual price of the deal (from maker order), 0 for rejection (price can be take from original order)
    public long price;

    // TRADE - trade size
    // REDUCE - effective reduce size of REDUCE command, or not filled size for CANCEL command
    // REJECT - unmatched size of rejected order
    public long size;

    public long filled;
    public long filledNotional;

    //public long timestamp; // same as activeOrder related event timestamp

    // frozen price from BID order owner (depends on activeOrderAction)
    public long bidderHoldPrice;

    public SymbolType symbolType;
    public long baseScaleK; // 基础货币的缩放系数（用于还原size）
    public long quoteScaleK; // 计价货币的缩放系数（用于还原price）

    // reference to next event in chain
    public MatcherTradeEvent nextEvent;


    // testing only
    public MatcherTradeEvent copy() {
        MatcherTradeEvent evt = new MatcherTradeEvent();
        evt.eventType = this.eventType;
        evt.section = this.section;
        evt.activeOrderCompleted = this.activeOrderCompleted;
        evt.matchedOrderId = this.matchedOrderId;
        evt.matchedOrderUid = this.matchedOrderUid;
        evt.matchedOrderCompleted = this.matchedOrderCompleted;
        evt.matchedOrderCommandType = this.matchedOrderCommandType;
        evt.matchedOrderFilled = this.matchedOrderFilled;
        evt.matchedOrderFilledNotional = this.matchedOrderFilledNotional;
        evt.matchedOrderType = this.matchedOrderType;
        evt.matchedOrderPrice = this.matchedOrderPrice;
        evt.matchedOrderSize = this.matchedOrderSize;
        evt.matchedUserCookie = this.matchedUserCookie;
        evt.matchedOrderTimestamp = this.matchedOrderTimestamp;
        evt.price = this.price;
        evt.size = this.size;
        evt.filled = this.filled;
        evt.filledNotional = this.filledNotional;
//        evt.timestamp = this.timestamp;
        evt.bidderHoldPrice = this.bidderHoldPrice;
        evt.symbolType = this.symbolType;
        evt.baseScaleK = this.baseScaleK;
        evt.quoteScaleK = this.quoteScaleK;
        return evt;
    }

    // testing only
    public MatcherTradeEvent findTail() {
        MatcherTradeEvent tail = this;
        while (tail.nextEvent != null) {
            tail = tail.nextEvent;
        }
        return tail;
    }

    public int getChainSize() {
        MatcherTradeEvent tail = this;
        int c = 1;
        while (tail.nextEvent != null) {
            tail = tail.nextEvent;
            c++;
        }
        return c;
    }

    @NotNull
    public static MatcherTradeEvent createEventChain(int chainLength) {
        final MatcherTradeEvent head = new MatcherTradeEvent();
        MatcherTradeEvent prev = head;
        for (int j = 1; j < chainLength; j++) {
            MatcherTradeEvent nextEvent = new MatcherTradeEvent();
            prev.nextEvent = nextEvent;
            prev = nextEvent;
        }
        return head;
    }


    // testing only
    public static List<MatcherTradeEvent> asList(MatcherTradeEvent next) {
        List<MatcherTradeEvent> list = new ArrayList<>();
        while (next != null) {
            list.add(next);
            next = next.nextEvent;
        }
        return list;
    }

    /**
     * Compare next events chain as well.
     */
    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (o == null) return false;
        if (!(o instanceof MatcherTradeEvent)) return false;
        MatcherTradeEvent other = (MatcherTradeEvent) o;

        // ignore timestamp
        return section == other.section
                && activeOrderCompleted == other.activeOrderCompleted
                && matchedOrderId == other.matchedOrderId
                && matchedOrderUid == other.matchedOrderUid
                && matchedOrderCompleted == other.matchedOrderCompleted
                && matchedOrderCommandType == other.matchedOrderCommandType
                && matchedOrderFilled == other.matchedOrderFilled
                && matchedOrderFilledNotional == other.matchedOrderFilledNotional
                && matchedOrderType == other.matchedOrderType
                && matchedOrderPrice == other.matchedOrderPrice
                && matchedOrderSize == other.matchedOrderSize
                && matchedUserCookie == other.matchedUserCookie
                && matchedOrderTimestamp == other.matchedOrderTimestamp
                && price == other.price
                && size == other.size
                && filled == other.filled
                && filledNotional == other.filledNotional
                && bidderHoldPrice == other.bidderHoldPrice
                && symbolType == other.symbolType
                && baseScaleK == other.baseScaleK
                && quoteScaleK == other.quoteScaleK
                && ((nextEvent == null && other.nextEvent == null) || (nextEvent != null && nextEvent.equals(other.nextEvent)));
    }

    /**
     * Includes chaining events
     */
    @Override
    public int hashCode() {
        return Objects.hash(
                section,
                activeOrderCompleted,
                matchedOrderId,
                matchedOrderUid,
                matchedOrderCompleted,
                matchedOrderCommandType,
                matchedOrderFilled,
                matchedOrderFilledNotional,
                matchedOrderType,
                matchedOrderPrice,
                matchedOrderSize,
                matchedUserCookie,
                matchedOrderTimestamp,
                price,
                size,
                filled,
                filledNotional,
                bidderHoldPrice,
                symbolType,
                baseScaleK,
                quoteScaleK,
                nextEvent);
    }


    @Override
    public String toString() {
        return "MatcherTradeEvent{" +
                "eventType=" + eventType +
                ", section=" + section +
                ", activeOrderCompleted=" + activeOrderCompleted +
                ", matchedOrderId=" + matchedOrderId +
                ", matchedOrderUid=" + matchedOrderUid +
                ", matchedOrderCompleted=" + matchedOrderCompleted +
                ", matchedOrderCommandType=" + matchedOrderCommandType +
                ", matchedOrderFilled=" + matchedOrderFilled +
                ", matchedOrderFilledNotional=" + matchedOrderFilledNotional +
                ", matchedOrderType=" + matchedOrderType +
                ", matchedOrderPrice=" + matchedOrderPrice +
                ", matchedOrderSize=" + matchedOrderSize +
                ", matchedUserCookie=" + matchedUserCookie +
                ", matchedOrderTimestamp=" + matchedOrderTimestamp +
                ", price=" + price +
                ", size=" + size +
                ", filled=" + filled +
                ", filledNotional=" + filledNotional +
//                ", timestamp=" + timestamp +
                ", bidderHoldPrice=" + bidderHoldPrice +
                ", symbolType=" + symbolType +
                ", baseScaleK=" + baseScaleK +
                ", quoteScaleK=" + quoteScaleK +
                ", nextEvent=" + (nextEvent != null) +
                '}';
    }
}
