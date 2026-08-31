//! 对应 Java: exchange.core2.core.common.MatcherTradeEvent（撮合事件单链表）
use crate::api::enums::MatcherEventType;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MatcherTradeEvent {
    pub event_type: MatcherEventType,
    pub active_order_completed: bool,
    pub maker_order_id: i64,
    pub maker_order_completed: bool,
    pub price: i64,
    pub size: i64,
    pub bid_gt_ask: bool,
    pub next: Option<Box<MatcherTradeEvent>>,
}
