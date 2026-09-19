use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Order {
    pub order_id: i64,
    pub price: i64,
    pub size: i64,
    pub filled: i64,
    pub filled_notional: i64,
    pub reserve_bid_price: i64,
    pub action: OrderAction,
    pub order_type: OrderType,
    pub uid: i64,
    pub timestamp: i64,
    pub user_cookie: i32,
    pub command: OrderCommandType,
}

impl Order {

    pub fn remaining(&self) -> i64 {
        self.size - self.filled
    }
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

impl ChronicleMarshallable for Order {
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
