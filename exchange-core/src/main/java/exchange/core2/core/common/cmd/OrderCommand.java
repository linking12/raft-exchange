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
package exchange.core2.core.common.cmd;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.IOrder;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public final class OrderCommand implements IOrder {

    @Getter
    public OrderCommandType command;

    @Getter
    public long orderId;

    public int symbol;
    public SymbolType symbolType;
    public long baseScaleK;
    public long quoteScaleK;
    public int baseCurrency; 
    public int quoteCurrency;

    @Getter
    public long price;

    @Getter
    public long size;

    @Getter
    // new orders INPUT - reserved price for fast moves of GTC bid orders in exchange mode
    public long reserveBidPrice;

    // required for PLACE_ORDER only;
    // for CANCEL/MOVE contains original order action (filled by orderbook)
    @Getter
    public OrderAction action;

    public OrderType orderType;

    @Getter
    public long uid;

    @Getter
    public long timestamp;

    public int userCookie;

    // 新增字段：杠杆倍数（默认 1 表示无杠杆）
    @Builder.Default
    public int leverage = 1;
    // 新增字段：仓位类型（默认 逐仓）
    @Builder.Default
    public MarginMode marginMode = MarginMode.ISOLATED;

    // filled by grouping processor:

    public long eventsGroup;
    public int serviceFlags;

    // result code of command execution - can also be used for saving intermediate state
    public CommandResultCode resultCode;

    // trade events chain
    public MatcherTradeEvent matcherEvent;

    /**
     * 资金转移事件
     * 【注意并发】risk后处理有可能涉及takerUid和多个makerUid，他们可能分布在多个shard上，这会导致taker和maker/多个maker之间同时发生add事件；
     *  risk的预处理只有1个uid，一定在某一个riskEngine上处理；
     *  OrderCmd上的fundEvents是线程安全的，因为matchEngine是按symbol分区的，一定只在一个matchEngine上。
     */
    public FundEvent takerFundEvents;
    /**
     * 【注意并发】防止多个maker之间的并发问题，把maker按照shard拆分；用数组维护，下标是shardId。
     */
    public FundEvent[] makerFundEventsByShard;

    // optional market data
    public L2MarketData marketData;

    // sequence of last available for this command
    //public long matcherEventSequence;
    // ---- potential false sharing section ------

    public static OrderCommand newOrder(OrderType orderType, long orderId, long uid, long price, long reserveBidPrice, long size, OrderAction action) {
        OrderCommand cmd = new OrderCommand();
        cmd.command = OrderCommandType.PLACE_ORDER;
        cmd.orderId = orderId;
        cmd.uid = uid;
        cmd.price = price;
        cmd.reserveBidPrice = reserveBidPrice;
        cmd.size = size;
        cmd.action = action;
        cmd.orderType = orderType;
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        return cmd;
    }

    public static OrderCommand cancel(long orderId, long uid) {
        OrderCommand cmd = new OrderCommand();
        cmd.command = OrderCommandType.CANCEL_ORDER;
        cmd.orderId = orderId;
        cmd.uid = uid;
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        return cmd;
    }

    public static OrderCommand reduce(long orderId, long uid, long reduceSize) {
        OrderCommand cmd = new OrderCommand();
        cmd.command = OrderCommandType.REDUCE_ORDER;
        cmd.orderId = orderId;
        cmd.uid = uid;
        cmd.size = reduceSize;
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        return cmd;
    }

    public static OrderCommand update(long orderId, long uid, long price) {
        OrderCommand cmd = new OrderCommand();
        cmd.command = OrderCommandType.MOVE_ORDER;
        cmd.orderId = orderId;
        cmd.uid = uid;
        cmd.price = price;
        cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
        return cmd;
    }

    /**
     * Handles full MatcherTradeEvent chain, without removing/revoking them
     *
     * @param handler - MatcherTradeEvent handler
     */
    public void processMatcherEvents(Consumer<MatcherTradeEvent> handler) {
        MatcherTradeEvent mte = this.matcherEvent;
        while (mte != null) {
            handler.accept(mte);
            mte = mte.nextEvent;
        }
    }

    /**
     * Produces garbage
     * For testing only !!!
     *
     * @return list of events
     */
    public List<MatcherTradeEvent> extractEvents() {
        List<MatcherTradeEvent> list = new ArrayList<>();
        processMatcherEvents(list::add);
        return list;
    }

    // Traverse and remove:
//    private void cleanMatcherEvents() {
//        MatcherTradeEvent ev = this.matcherEvent;
//        this.matcherEvent = null;
//        while (ev != null) {
//            MatcherTradeEvent tmp = ev;
//            ev = ev.nextEvent;
//            tmp.nextEvent = null;
//        }
//    }
//


    /**
     * Write only command data, not status or events
     *
     * @param cmd2 command to overwrite to
     */
    public void writeTo(OrderCommand cmd2) {
        cmd2.command = this.command;
        cmd2.orderId = this.orderId;
        cmd2.symbol = this.symbol;
        cmd2.symbolType = this.symbolType;
        cmd2.baseScaleK = this.baseScaleK;
        cmd2.quoteScaleK = this.quoteScaleK;
        cmd2.quoteCurrency = this.quoteCurrency;
        cmd2.uid = this.uid;
        cmd2.timestamp = this.timestamp;
        cmd2.leverage = this.leverage;
        cmd2.marginMode = this.marginMode;

        cmd2.reserveBidPrice = this.reserveBidPrice;
        cmd2.price = this.price;
        cmd2.size = this.size;
        cmd2.action = this.action;
        cmd2.orderType = this.orderType;
    }

    // slow - testing only
    public OrderCommand copy() {

        OrderCommand newCmd = new OrderCommand();
        writeTo(newCmd);
        newCmd.resultCode = this.resultCode;

        List<MatcherTradeEvent> events = extractEvents();

//        System.out.println(">>> events: " + events);
        for (MatcherTradeEvent event : events) {
            MatcherTradeEvent copy = event.copy();
            copy.nextEvent = newCmd.matcherEvent;
            newCmd.matcherEvent = copy;
//            System.out.println(">>> newCmd.matcherEvent: " + newCmd.matcherEvent);
        }

        if (marketData != null) {
            newCmd.marketData = marketData.copy();
        }

//        System.out.println(">>> newCmd: " + newCmd);
        return newCmd;
    }

    @Override
    public long getFilled() {
        return 0;
    }

    @Override
    public long getFilledNotional() {
        return 0;
    }

    @Override
    public int stateHash() {
        throw new UnsupportedOperationException("Command does not represents state");
    }
}
