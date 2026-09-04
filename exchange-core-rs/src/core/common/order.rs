//! 对应 Java: exchange.core2.core.common.Order（撮合所需字段子集）
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::order_action::OrderAction;

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct Order {
    pub order_id: i64,
    pub price: i64,
    pub size: i64,
    pub filled: i64,
    pub reserve_bid_price: i64,
    pub action: OrderAction,
    pub uid: i64,
    pub timestamp: i64,
    /// 该挂单最初下单命令的类型（对应 Java `Order.command`），P6-G：撮合时写入 `MatcherTradeEvent.matched_order_command_type`。
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
            reserve_bid_price: 0,
            action: OrderAction::Bid,
            uid: 7,
            timestamp: 0,
            command: OrderCommandType::PlaceOrder,
        };
        assert_eq!(o.remaining(), 7);
    }
}
