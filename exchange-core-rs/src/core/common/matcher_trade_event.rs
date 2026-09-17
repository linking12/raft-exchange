use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::matcher_event_type::MatcherEventType;
use crate::core::common::order_type::OrderType;

/// 对应 Java `exchange.core2.core.common.MatcherTradeEvent`。撮合器产出的单条事件,通过
/// `next`(对应 Java `nextEvent`)链成单向链表,供 R2 风控阶段逐条回放结算。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MatcherTradeEvent {
    /// 对应 Java `eventType`:TRADE / REDUCE / REJECT(少见)/ BINARY_EVENT(报表用)。
    pub event_type: MatcherEventType,
    /// 对应 Java `activeOrderCompleted`:除 active 单被完全成交/移除/拒绝外恒为 false;
    /// REJECT 事件恒为 true;REDUCE 事件仅当由 CANCEL/REDUCE 命令直接触发时为 true。
    pub active_order_completed: bool,
    /// 对应 Java `matchedOrderId`:maker 单 id(仅 TRADE 事件有意义)。
    pub maker_order_id: i64,
    /// 对应 Java `matchedOrderCompleted`:除 maker 单被完全成交外恒为 false。
    pub maker_order_completed: bool,
    /// 对应 Java `price`:实际成交价(取自 maker 单);REJECT 事件为 0,原始价格可从 active 单本身取。
    pub price: i64,
    /// 对应 Java `size`:语义随 `event_type` 变化——TRADE 是成交量,REDUCE 是本次
    /// 有效减量(或 CANCEL 未成交剩余量),REJECT 是被拒单的未匹配剩余量。
    pub size: i64,
    /// Java 无对应字段;标记本次撮合的 taker 方向是否为 Bid(各 orderbook 实现在构造
    /// 事件时按 taker action 写入),目前仅写入未见下游读取。
    pub bid_gt_ask: bool,
    /// 对应 Java `bidderHoldPrice`:依 active 单方向冻结的 BID 方持有人价格。
    pub bidder_hold_price: i64,
    /// 对应 Java `matchedOrderUid`:maker 单所属用户;REJECT 事件为 0。
    pub matched_order_uid: i64,
    pub matched_order_command_type: OrderCommandType,
    /// 对应 Java `filled`/`filledNotional`(active 单侧累计成交量/名义值)。
    pub filled: i64,
    pub filled_notional: i64,
    pub matched_order_size: i64,
    pub matched_order_price: i64,
    pub matched_order_type: OrderType,
    pub matched_order_timestamp: i64,
    pub matched_user_cookie: i32,
    /// 对应 Java `matchedOrderFilled`/`matchedOrderFilledNotional`(maker 单侧累计成交量/名义值)。
    pub matched_order_filled: i64,
    pub matched_order_filled_notional: i64,
    /// 对应 Java `nextEvent`:同一命令产出的事件链表,按撮合顺序串联。
    pub next: Option<Box<MatcherTradeEvent>>,
}

impl Default for MatcherTradeEvent {
    fn default() -> Self {
        MatcherTradeEvent {
            event_type: MatcherEventType::BinaryEvent,
            active_order_completed: false,
            maker_order_id: 0,
            maker_order_completed: false,
            price: 0,
            size: 0,
            bid_gt_ask: false,
            bidder_hold_price: 0,
            matched_order_uid: 0,
            matched_order_command_type: OrderCommandType::PlaceOrder,
            filled: 0,
            filled_notional: 0,
            matched_order_size: 0,
            matched_order_price: 0,
            matched_order_type: OrderType::Gtc,
            matched_order_timestamp: 0,
            matched_user_cookie: 0,
            matched_order_filled: 0,
            matched_order_filled_notional: 0,
            next: None,
        }
    }
}
