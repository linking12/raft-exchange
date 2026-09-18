use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::l2_market_data::L2MarketData;
use crate::core::common::order::Order;

pub trait IOrderBook {

    fn new_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode;

    fn cancel_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode;

    fn reduce_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode;

    fn move_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode;

    fn fill_l2(&self, size: i32) -> L2MarketData;

    fn state_hash(&self) -> i32;

    fn find_user_orders(&self, uid: i64) -> Vec<Order>;
}
