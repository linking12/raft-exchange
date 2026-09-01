//! 对应 Java: exchange.core2.core.common.cmd.OrderCommand（P1：撮合相关字段 + Task 2：现货路由所需字段）
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::order_action::OrderAction;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::order_type::OrderType;
use crate::core::common::matcher_trade_event::MatcherTradeEvent;
use crate::core::common::l2_market_data::L2MarketData;

#[derive(Debug, Clone, Default)]
pub struct OrderCommand {
    /// 对应 Java `OrderCommand.command`：驱动 R1/R2 路由分派
    /// （`isNonTrading()`/`isLoan()` 门守 + 主 switch），Task 2 之前的 P1 撮合路径未含此字段。
    pub command: OrderCommandType,
    pub order_id: i64,
    pub symbol: i32,
    pub price: i64,
    pub size: i64,
    pub reserve_bid_price: i64,
    pub action: Option<OrderAction>,
    pub order_type: Option<OrderType>,
    pub uid: i64,
    pub timestamp: i64,
    pub order_flags: i32,
    pub result_code: Option<CommandResultCode>,
    pub matcher_event: Option<Box<MatcherTradeEvent>>,
    pub market_data: Option<L2MarketData>,
}
