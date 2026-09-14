//! 对应 Java: exchange.core2.core.common.MatcherTradeEvent（撮合事件单链表）
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
    /// 对应 Java `bidderHoldPrice`：BID 一方冻结价；TRADE 取 BID 方 reserve_bid_price，REDUCE/REJECT 取自身 reserve_bid_price（ASK 恒 0）。
    pub bidder_hold_price: i64,
    /// 对应 Java `matchedOrderUid`：maker 的 uid；仅 TRADE 有意义，REDUCE/REJECT 恒为 0。
    pub matched_order_uid: i64,
    /// 对应 Java `matchedOrderCommandType`：maker 挂单时的原命令类型（非 taker 命令），用于算 maker 侧 createPositionsKey；仅 TRADE 有意义，REDUCE/REJECT 恒默认 `PlaceOrder`。
    pub matched_order_command_type: OrderCommandType,
    /// 对应 Java `MatcherTradeEvent.filled`：taker（active order）本命令累计成交量（含本笔后）。
    pub filled: i64,
    /// taker 累计成交名义额（Σ trade×price，含本笔后）。
    pub filled_notional: i64,
    /// maker 挂单总量（对应 Java `matchedOrderSize`）；仅 TRADE 有意义。
    pub matched_order_size: i64,
    /// maker 挂单价（对应 Java `matchedOrderPrice`，等于成交价 `price`）；仅 TRADE 有意义。
    pub matched_order_price: i64,
    /// maker 挂单类型（对应 Java `matchedOrderType`）；仅 TRADE 有意义。
    pub matched_order_type: OrderType,
    /// maker 挂单时间戳（对应 Java `matchedOrderTimestamp`）；仅 TRADE 有意义。
    pub matched_order_timestamp: i64,
    /// maker userCookie（对应 Java `matchedUserCookie`）；仅 TRADE 有意义。
    pub matched_user_cookie: i32,
    /// maker 累计成交量（对应 Java `matchedOrderFilled`，含本笔后）；仅 TRADE 有意义。
    pub matched_order_filled: i64,
    /// maker 累计成交名义额（对应 Java `matchedOrderFilledNotional`，含本笔后）；仅 TRADE 有意义。
    pub matched_order_filled_notional: i64,
    pub next: Option<Box<MatcherTradeEvent>>,
}

impl Default for MatcherTradeEvent {
    /// 语义中性默认（`event_type` 为占位 `BinaryEvent`，构造时必被覆盖）：REJECT/REDUCE 站只填有意义字段，
    /// 其余 maker/taker 报告字段走此默认，避免逐站重复 9 行 `..0`。
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
