//! 对应 Java `exchange.core2.core.orderbook.IOrderBook` 接口。
//!
//! Java 版本方法很多（含 `writeMarshallable`/`getOrdersNum`/`validateInternalState`/
//! `askOrdersStream` 等测试与序列化辅助方法，以及 `processCommand` 静态分发方法），
//! 这里只保留了核心撮合行为对应的方法子集；命令分发、快照序列化等职责在 Rust 侧
//! 由其他模块（如 processors、snapshot）承担，未必挂在这个 trait 上。

use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::l2_market_data::L2MarketData;
use crate::core::common::order::Order;

/// 订单簿通用行为，由 `OrderBookDirectImpl`（以及朴素实现）实现。
/// 对应 Java `IOrderBook` 接口中的核心方法。
pub trait IOrderBook {
    /// 处理新订单：按价格判断是否可成交（marketable），可成交部分与对手方 GTC 订单撮合，
    /// 剩余部分按订单类型决定是拒单（IOC）还是挂单（GTC）。
    /// 对应 Java `IOrderBook.newOrder(OrderCommand cmd)`；
    /// 注意 Java 版本返回 void（结果写回 cmd），此处直接返回 `CommandResultCode`。
    fn new_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode;

    /// 完全撤销订单。找不到订单时返回 MATCHING_UNKNOWN_ORDER_ID，否则 SUCCESS。
    /// 对应 Java `IOrderBook.cancelOrder(OrderCommand cmd)`。
    fn cancel_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode;

    /// 按指定数量减少订单剩余量。
    /// 对应 Java `IOrderBook.reduceOrder(OrderCommand cmd)`。
    fn reduce_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode;

    /// 移动订单价格（若新价为 0 或与原价相同则不移动）。
    /// 对应 Java `IOrderBook.moveOrder(OrderCommand cmd)`。
    fn move_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode;

    /// 获取 L2 深度快照（按价位聚合的 ask/bid 数量与量），每侧最多 `size` 档。
    /// 对应 Java `IOrderBook.getL2MarketDataSnapshot(int size)`（内部会调用 fillAsks/fillBids）。
    fn fill_l2(&self, size: i32) -> L2MarketData;

    /// 计算订单簿状态哈希，用于跨节点/跨实现一致性校验（与具体实现无关）。
    /// 对应 Java `IOrderBook.stateHash()` 默认方法（基于 ask/bid 订单流 + symbol spec 哈希）。
    fn state_hash(&self) -> i32;

    /// 查找指定用户的全部订单。
    /// 对应 Java `IOrderBook.findUserOrders(long uid)`；Java 文档注明该方法较慢（未维护 uid 索引），
    /// 仅用于查询场景，不应在撮合热路径调用。
    fn find_user_orders(&self, uid: i64) -> Vec<Order>;
}
