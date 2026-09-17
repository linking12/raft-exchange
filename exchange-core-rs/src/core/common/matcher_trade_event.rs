use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::matcher_event_type::MatcherEventType;
use crate::core::common::order_type::OrderType;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MatcherTradeEvent {
    pub event_type: MatcherEventType,
    pub active_order_completed: bool,
    pub maker_order_id: i64,
    pub maker_order_completed: bool,
    pub price: i64,
    pub size: i64,
    pub bid_gt_ask: bool,
    pub bidder_hold_price: i64,
    pub matched_order_uid: i64,
    pub matched_order_command_type: OrderCommandType,
    pub filled: i64,
    pub filled_notional: i64,
    pub matched_order_size: i64,
    pub matched_order_price: i64,
    pub matched_order_type: OrderType,
    pub matched_order_timestamp: i64,
    pub matched_user_cookie: i32,
    pub matched_order_filled: i64,
    pub matched_order_filled_notional: i64,
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
