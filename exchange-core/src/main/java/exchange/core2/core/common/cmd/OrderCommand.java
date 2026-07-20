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

import exchange.core2.core.common.ADLUserPosition;
import exchange.core2.core.common.CommonByShard;
import exchange.core2.core.common.FundingPaymentAndRecvNotional;
import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.IOrder;
import exchange.core2.core.common.L2MarketData;
import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import lombok.Getter;
import lombok.ToString;

@ToString
public final class OrderCommand implements IOrder {
    public static final int FLAG_REDUCE_ONLY = 1;

    @Getter
    public OrderCommandType command;

    @Getter
    public long orderId;

    public int symbol;

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

    @Getter
    public OrderType orderType;

    @Getter
    public long uid;

    @Getter
    public long timestamp;

    @Getter
    public int userCookie;

    // 新增字段：杠杆倍数（默认 1 表示无杠杆）
    public int leverage = 1;
    // 新增字段：仓位类型（默认 逐仓）
    public MarginMode marginMode = MarginMode.ISOLATED;
    // 新增字段：订单flag 目前仅在PLACE_ORDER中表示只减仓
    public int orderFlags;

    // filled by grouping processor:

    public long eventsGroup;
    public int serviceFlags;

    // result code of command execution - can also be used for saving intermediate state
    public CommandResultCode resultCode;

    // trade events chain
    public MatcherTradeEvent matcherEvent;

    /**
     * Taker 桶——单链表。
     * 只在"真实 user-initiated single-shard order"路径写（spot 自发操作 / 期货 placeOrder /
     * handleMatcherEventMargin taker 块的 user PLACE 路径）。由 cmd.uid hash 决定 owner shard，
     * 单写无 race；其余（FORCE / IF/ADL / 批量系统事件）走 {@link #makerFundEventsByShard}。
     */
    public FundEvent takerFundEvents;
    /**
     * Maker 桶——按 RiskEngine shardId 分槽的数组。
     * 凡满足以下任一即落这里：真实 maker（撮合对手方）/ 系统触发无 user order / 多 shard 并发写同一 cmd。
     * 各 shard 独写自己的 slot 无 race。
     */
    public FundEvent[] makerFundEventsByShard;

    /**
     * 【注意并发】IF 最大可覆盖名义价值，按shard分组；用数组维护，下标是shardId。
     */
    public long[] ifPreviewCoverByShard;

    /**
     * 【注意并发】ADL候选列表，按shard分组；用数组维护，下标是shardId。
     */
    public ADLUserPosition[] adlUserPositionsByShard;

    /**
     * 【注意并发】资金费结算数据，按shard分组；每个元素包含本shard付款方总额(payAmount)
     * 和接收方快照(uid→R1 notional map)。
     * GroupingProcessor 在每次 cmd 处理后调用 reset() 清零复用；
     * R1 填入，buildMatcherEvents 读各shard notional sum 做跨shard分配，R2 读 uid→notional 发放资金费。
     */
    public FundingPaymentAndRecvNotional[] fundingPaymentAndRecvNotionalByShard;

    /**
     * 【注意并发】通用 per-shard IntLongHashMap 槽；GroupingProcessor 每 cmd 处理后 reset() 清零。
     * 首个用户：RESET_FEE 的 currency→cleared amount。将来同形态命令直接复用。
     */
    public CommonByShard[] commonByShard;

    /**
     * 用于 RingBuffer event factory：在 ringbuffer slot 创建时一次性分配所有 per-shard 数组。
     * 这些数组的生命周期跟 cmd 槽位一致（被 disruptor 复用），里面的元素由 R1 在每次 cmd
     * 处理时按 shardId 写入；GroupingProcessor 负责清除链表式数组（maker/adl）的元素，
     * 数值类数组（funding/ifPreview）由 R1 直接覆写。
     *
     * 这样设计的好处：彻底消除 R1 多 shard 并行时对这些数组的 lazy-init 竞态（多个 R1
     * 同时看到 null 并各自 new 数组导致后写者覆盖先写者），避免对账漂移。
     */
    public OrderCommand(int numShards) {
        this.makerFundEventsByShard = new FundEvent[numShards];
        this.ifPreviewCoverByShard = new long[numShards];
        this.adlUserPositionsByShard = new ADLUserPosition[numShards];
        this.fundingPaymentAndRecvNotionalByShard = FundingPaymentAndRecvNotional.createArray(numShards);
        this.commonByShard = CommonByShard.createArray(numShards);
    }

    /**
     * 单元测试专用的链式构造入口。强制传入 numShards 以保证 per-shard 数组非 null，
     * 让生产路径上的数组访问可以省去防御性 null check。
     * 生产代码请直接使用 {@link #OrderCommand(int)} 构造器。
     */
    public static TestBuilder testBuilder(int numShards) {
        return new TestBuilder(new OrderCommand(numShards));
    }

    public static final class TestBuilder {
        private final OrderCommand cmd;
        private TestBuilder(OrderCommand cmd) { this.cmd = cmd; }
        public TestBuilder command(OrderCommandType v) { cmd.command = v; return this; }
        public TestBuilder orderId(long v) { cmd.orderId = v; return this; }
        public TestBuilder symbol(int v) { cmd.symbol = v; return this; }
        public TestBuilder price(long v) { cmd.price = v; return this; }
        public TestBuilder size(long v) { cmd.size = v; return this; }
        public TestBuilder reserveBidPrice(long v) { cmd.reserveBidPrice = v; return this; }
        public TestBuilder action(OrderAction v) { cmd.action = v; return this; }
        public TestBuilder orderType(OrderType v) { cmd.orderType = v; return this; }
        public TestBuilder uid(long v) { cmd.uid = v; return this; }
        public TestBuilder timestamp(long v) { cmd.timestamp = v; return this; }
        public TestBuilder userCookie(int v) { cmd.userCookie = v; return this; }
        public TestBuilder leverage(int v) { cmd.leverage = v; return this; }
        public TestBuilder marginMode(MarginMode v) { cmd.marginMode = v; return this; }
        public TestBuilder orderFlags(int v) { cmd.orderFlags = v; return this; }
        public TestBuilder resultCode(CommandResultCode v) { cmd.resultCode = v; return this; }
        public TestBuilder matcherEvent(MatcherTradeEvent v) { cmd.matcherEvent = v; return this; }
        public TestBuilder marketData(L2MarketData v) { cmd.marketData = v; return this; }
        public OrderCommand build() { return cmd; }
    }

    // optional market data
    public L2MarketData marketData;

    // sequence of last available for this command
    //public long matcherEventSequence;
    // ---- potential false sharing section ------

    public boolean isReduceOnly() {
        return (orderFlags & FLAG_REDUCE_ONLY) != 0;
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

    /**
     * Write only command data, not status or events
     *
     * @param cmd2 command to overwrite to
     */
    public void writeTo(OrderCommand cmd2) {
        cmd2.command = this.command;
        cmd2.orderId = this.orderId;
        cmd2.symbol = this.symbol;
        cmd2.price = this.price;
        cmd2.size = this.size;
        cmd2.reserveBidPrice = this.reserveBidPrice;
        cmd2.action = this.action;
        cmd2.orderType = this.orderType;
        cmd2.uid = this.uid;
        cmd2.timestamp = this.timestamp;
        cmd2.leverage = this.leverage;
        cmd2.marginMode = this.marginMode;
        cmd2.orderFlags = this.orderFlags;
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
