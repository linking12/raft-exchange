//! 对应 Java: exchange.core2.core.common.MatcherTradeEvent（撮合事件单链表）
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::matcher_event_type::MatcherEventType;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MatcherTradeEvent {
    pub event_type: MatcherEventType,
    pub active_order_completed: bool,
    pub maker_order_id: i64,
    pub maker_order_completed: bool,
    pub price: i64,
    pub size: i64,
    pub bid_gt_ask: bool,
    /// 对应 Java `bidderHoldPrice`：BID 一方冻结价；TRADE 取 BID 方 reserve_bid_price，REDUCE/REJECT 取自身 reserve_bid_price（ASK 恒 0）。
    pub bidder_hold_price: i64,
    /// 对应 Java `matchedOrderUid`：maker 的 uid；仅 TRADE 有意义，REDUCE/REJECT 恒为 0。
    pub matched_order_uid: i64,
    /// 对应 Java `matchedOrderCommandType`（`:52`）：maker 挂单时的原命令类型（`OrderBookEventsHelper.java:75`），非 taker 命令；`RiskEngine`（`.java:1450`）用它算 maker 侧 createPositionsKey；仅 TRADE 有意义，REDUCE/REJECT 恒默认 `PlaceOrder`。
    pub matched_order_command_type: OrderCommandType,
    pub next: Option<Box<MatcherTradeEvent>>,
}
