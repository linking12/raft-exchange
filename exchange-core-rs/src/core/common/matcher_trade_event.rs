//! 对应 Java: exchange.core2.core.common.MatcherTradeEvent（撮合事件单链表）
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
    /// 对应 Java `MatcherTradeEvent.bidderHoldPrice`：该事件所涉 BID 一方冻结（保留）的价格。
    /// - TRADE：成交双方中 BID 那一方的 `reserve_bid_price`（对照 Java
    ///   `OrdersBucketNaive.match`：`order.action == ASK ? activeOrder.getReserveBidPrice() : order.reserveBidPrice`——
    ///   maker 是 ASK 时取 taker 的 reserve_bid_price，maker 是 BID 时取 maker 自己的）。
    /// - REDUCE：被减/撤挂单自身的 `reserve_bid_price`（对应 Java `OrderBookEventsHelper.sendReduceEvent`：
    ///   `event.bidderHoldPrice = order.getReserveBidPrice()`；ASK 挂单的 reserve_bid_price 恒为 0）。
    /// - REJECT：命令自身的 `reserve_bid_price`（对应 Java `attachRejectEvent`：
    ///   `event.bidderHoldPrice = cmd.reserveBidPrice`；ASK 命令同样恒为 0）。
    pub bidder_hold_price: i64,
    /// 对应 Java `MatcherTradeEvent.matchedOrderUid`：maker（挂单方）的 uid。
    /// 仅 TRADE 事件有意义；REDUCE/REJECT 事件恒为 0（Java 注释："0 for rejection"，
    /// sendReduceEvent 同样未赋值——即恒为默认 0）。
    pub matched_order_uid: i64,
    pub next: Option<Box<MatcherTradeEvent>>,
}
