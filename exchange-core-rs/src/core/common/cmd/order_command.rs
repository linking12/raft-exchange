//! 对应 Java: exchange.core2.core.common.cmd.OrderCommand（撮合字段 + 现货路由字段
//! + 期货 `leverage`/`marginMode`/reduce-only 扩展）。
use std::collections::BTreeMap;

use crate::core::common::adl_user_position::AdlUserPosition;
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::order_action::OrderAction;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::order_type::OrderType;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::matcher_trade_event::MatcherTradeEvent;
use crate::core::common::l2_market_data::L2MarketData;
use crate::core::common::fund_event::FundEvent;

/// 对应 Java `OrderCommand.FLAG_REDUCE_ONLY`：`orderFlags` 位标记，仅在 `PLACE_ORDER` 中表示"只减仓"（不开新敞口）。
pub const FLAG_REDUCE_ONLY: i32 = 1;

#[derive(Debug, Clone, Default)]
pub struct OrderCommand {
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
    /// derive 零值 `0` 在消费点归一为 `1`（同 Java `updateLeverage` 的 `0 -> 1`），故不手写 Default。
    pub leverage: i32,
    pub margin_mode: MarginMode,
    /// `LOAN_CREATE` 用低字节承载 rateMode（见 `loan_command_dispatcher::handle_loan_create`）。
    pub user_cookie: i32,
    pub result_code: Option<CommandResultCode>,
    pub matcher_event: Option<Box<MatcherTradeEvent>>,
    pub market_data: Option<L2MarketData>,
    /// `REPRICE_LOAN_RATES` 专属 R1→R2 载体 `(currency, util_bps)`，currency 升序。
    pub loan_reprice_events: Vec<(i32, i64)>,
    /// `INTERNAL_TRANSFER` 专属 R1→R2 载体 `(to_uid, currency, amount)`。
    pub internal_transfer_event: Option<(i64, i32, i64)>,
    /// `SETTLE_FUNDINGFEES` 专属 R1+merge→R2 载体 `(payer_amounts, receiver_notionals, shard_recv_amount)`；
    /// `None` = 无可结算事件，R2 早退。
    #[allow(clippy::type_complexity)]
    pub funding_fee_event: Option<(BTreeMap<i64, i64>, BTreeMap<i64, i64>, i64)>,
    /// `IF_TAKEOVER` R1 载体：预留的 IF notional，R2 finalize 无条件释放（跟 R1 对称）。
    pub if_preview_cover: i64,
    /// `IF_TAKEOVER` merge 载体：`Some(size)` = 接管成功，`None` = 全拒。
    pub if_takeover_size: Option<i64>,
    /// `AUTO_DELEVERAGING` R1 载体：候选仓按 risk_score DESC 排序后的选中序；R2 finalize 用 `take` 整体对称
    /// 释放 `pending_adl_size`（不管 `adl_events` 实际消费多少），故 merge 只读不改本字段。
    pub adl_user_positions: Vec<AdlUserPosition>,
    /// `AUTO_DELEVERAGING` merge 载体 `(uid, exec_volume)`；空 = 全拒，R2 据此跳过 taker 平仓。
    pub adl_events: Vec<(i64, i64)>,

    pub fund_events: Vec<FundEvent>,
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
