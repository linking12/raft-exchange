//! 订单簿接口。对应 Java: orderbook/IOrderBook.java
use crate::api::command::OrderCommand;
use crate::api::enums::CommandResultCode;
use crate::api::l2::L2MarketData;

/// 订单簿 trait，定义撮合引擎与订单簿的交互接口。
pub trait IOrderBook {
    /// 新增订单（GTC / IOC / FOK 等）。实现者负责构建 maker/taker 订单、调用撮合、填充 market_data。
    fn new_order(&mut self, cmd: &mut OrderCommand);

    /// 撤销已存订单。返回撤销结果（Success / MatchingUnknownOrderId）。
    fn cancel_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode;

    /// 部分撤销已存订单（减量）。返回减量结果。
    fn reduce_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode;

    /// 移动（移价 + 保持 uid）已存订单。返回移动结果。
    fn move_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode;

    /// 填充 L2 盘口快照。size 指取多少档（从最优价开始）。
    fn fill_l2(&self, size: i32) -> L2MarketData;

    /// 返回订单簿的状态 hash（用于一致性检查）。
    fn state_hash(&self) -> i32;
}
