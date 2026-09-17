//! 对应 Java: exchange.core2.core.common.Order（撮合所需字段子集）
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;

#[derive(Debug, Clone, PartialEq, Eq)]
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


// ---- Chronicle 快照读写(见 crate::core::snapshot;字段序照 Java writeMarshallable)----
use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

impl ChronicleMarshallable for Order {
    /// Java `DirectOrder.writeMarshallable`:orderId,price,size,filled,filledNotional,reserveBidPrice,
    /// action(byte),orderType(byte),command(byte),uid,timestamp,userCookie(int)。
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_i64(self.order_id);
        w.write_i64(self.price);
        w.write_i64(self.size);
        w.write_i64(self.filled);
        w.write_i64(self.filled_notional);
        w.write_i64(self.reserve_bid_price);
        w.write_u8(self.action.code() as u8);
        w.write_u8(self.order_type.code() as u8);
        w.write_u8(self.command.code() as u8);
        w.write_i64(self.uid);
        w.write_i64(self.timestamp);
        w.write_i32(self.user_cookie);
    }
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        use crate::core::common::cmd::order_command_type::OrderCommandType;
        Ok(Order {
            order_id: r.read_i64()?,
            price: r.read_i64()?,
            size: r.read_i64()?,
            filled: r.read_i64()?,
            filled_notional: r.read_i64()?,
            reserve_bid_price: r.read_i64()?,
            action: OrderAction::from_code(r.read_u8()? as i8).expect("OrderAction code"),
            order_type: OrderType::from_code(r.read_u8()? as i8).expect("OrderType code"),
            command: OrderCommandType::from_code(r.read_u8()? as i8),
            uid: r.read_i64()?,
            timestamp: r.read_i64()?,
            user_cookie: r.read_i32()?,
        })
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
