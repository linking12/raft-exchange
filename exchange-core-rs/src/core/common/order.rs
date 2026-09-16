//! 对应 Java: exchange.core2.core.common.Order（撮合所需字段子集）
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Order {
    pub order_id: i64,
    pub price: i64,
    pub size: i64,
    pub filled: i64,
    /// 已成交名义额（Σ trade×price），供 `MatcherTradeEvent.matched_order_filled_notional` 填充。
    pub filled_notional: i64,
    pub reserve_bid_price: i64,
    pub action: OrderAction,
    /// 对应 Java `Order.orderType`：挂单类型，写入 `MatcherTradeEvent.matched_order_type`。
    pub order_type: OrderType,
    pub uid: i64,
    pub timestamp: i64,
    /// 对应 Java `Order.userCookie`：写入 `MatcherTradeEvent.matched_user_cookie`。
    pub user_cookie: i32,
    /// 该挂单最初下单命令的类型（对应 Java `Order.command`）：撮合时写入 `MatcherTradeEvent.matched_order_command_type`。
    pub command: OrderCommandType,
}

impl Order {
    /// 未成交量 = size - filled（对应 Java Order.size - Order.filled）
    pub fn remaining(&self) -> i64 {
        self.size - self.filled
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::order_action::OrderAction;

    #[test]
    fn order_remaining_size() {
        let o = Order {
            order_id: 1,
            price: 100,
            size: 10,
            filled: 3,
            filled_notional: 0,
            reserve_bid_price: 0,
            action: OrderAction::Bid,
            order_type: OrderType::Gtc,
            uid: 7,
            timestamp: 0,
            user_cookie: 0,
            command: OrderCommandType::PlaceOrder,
        };
        assert_eq!(o.remaining(), 7);
    }
}
