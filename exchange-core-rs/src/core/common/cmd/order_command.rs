//! 对应 Java: exchange.core2.core.common.cmd.OrderCommand（P1：撮合相关字段 + Task 2：现货路由
//! 所需字段 + P4 Task 1：期货 `leverage`/`marginMode`/reduce-only 字段扩展）。
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::order_action::OrderAction;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::order_type::OrderType;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::matcher_trade_event::MatcherTradeEvent;
use crate::core::common::l2_market_data::L2MarketData;

/// 对应 Java `OrderCommand.FLAG_REDUCE_ONLY`：`orderFlags` 位标记，仅在 `PLACE_ORDER` 中表示
/// "只减仓"（不开新敞口）。
pub const FLAG_REDUCE_ONLY: i32 = 1;

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
    /// 对应 Java `OrderCommand.leverage`（`public int leverage = 1;`）。**Ruling P4-B**：
    /// Rust `#[derive(Default)]` 给出的零值是 `0`，不是 Java 字面初始值 `1`——两者在下游语义上
    /// 等价：`SymbolPositionRecord::update_leverage`/`CoreSymbolSpecification::calculate_init_margin`
    /// 等消费点都把 `leverage==0` 归一为 `1`（同 Java `updateLeverage(int)` 的 `0 -> 1`
    /// 归一规则），因此保留 derive、不手写 `Default` impl，未改变任何既有 `..Default::default()`
    /// 构造点的行为。
    pub leverage: i32,
    /// 对应 Java `OrderCommand.marginMode`（默认 `MarginMode.ISOLATED`，码值 0，与
    /// `MarginMode::default()` 一致，derive 得到的零值恰好是正确默认值）。
    pub margin_mode: MarginMode,
    pub result_code: Option<CommandResultCode>,
    pub matcher_event: Option<Box<MatcherTradeEvent>>,
    pub market_data: Option<L2MarketData>,
}

impl OrderCommand {
    /// 对应 Java `OrderCommand.isReduceOnly()`：`orderFlags & FLAG_REDUCE_ONLY != 0`。
    pub fn is_reduce_only(&self) -> bool {
        (self.order_flags & FLAG_REDUCE_ONLY) != 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_has_zero_leverage_and_isolated_margin_mode() {
        let cmd = OrderCommand::default();
        assert_eq!(cmd.leverage, 0); // 归一到 1 是消费点职责，见字段文档
        assert_eq!(cmd.margin_mode, MarginMode::Isolated);
        assert!(!cmd.is_reduce_only());
    }

    #[test]
    fn is_reduce_only_reads_flag_bit() {
        let mut cmd = OrderCommand { order_flags: FLAG_REDUCE_ONLY, ..Default::default() };
        assert!(cmd.is_reduce_only());

        cmd.order_flags = 0;
        assert!(!cmd.is_reduce_only());

        // 与其他 flag 位共存时仍应命中（按位与，不要求恰好等于 FLAG_REDUCE_ONLY）。
        cmd.order_flags = FLAG_REDUCE_ONLY | 0b10;
        assert!(cmd.is_reduce_only());
    }

    #[test]
    fn leverage_and_margin_mode_are_settable_via_struct_update_syntax() {
        let cmd = OrderCommand { leverage: 10, margin_mode: MarginMode::Cross, ..Default::default() };
        assert_eq!(cmd.leverage, 10);
        assert_eq!(cmd.margin_mode, MarginMode::Cross);
    }
}
