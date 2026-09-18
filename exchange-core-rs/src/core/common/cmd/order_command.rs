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
    pub leverage: i32,
    pub margin_mode: MarginMode,
    pub user_cookie: i32,
    pub result_code: Option<CommandResultCode>,
    pub matcher_event: Option<Box<MatcherTradeEvent>>,
    pub market_data: Option<L2MarketData>,
    pub loan_reprice_events: Vec<(i32, i64)>,
    pub internal_transfer_event: Option<(i64, i32, i64)>,
    #[allow(clippy::type_complexity)]
    pub funding_fee_event: Option<(BTreeMap<i64, i64>, BTreeMap<i64, i64>, i64)>,
    pub if_preview_cover: i64,
    pub if_takeover_size: Option<i64>,
    pub adl_user_positions: Vec<AdlUserPosition>,
    pub adl_events: Vec<(i64, i64)>,
    pub fund_events: Vec<FundEvent>,
}

impl OrderCommand {

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
        assert_eq!(cmd.leverage, 0);
        assert_eq!(cmd.margin_mode, MarginMode::Isolated);
        assert!(!cmd.is_reduce_only());
    }

    #[test]
    fn is_reduce_only_reads_flag_bit() {
        let mut cmd = OrderCommand { order_flags: FLAG_REDUCE_ONLY, ..Default::default() };
        assert!(cmd.is_reduce_only());

        cmd.order_flags = 0;
        assert!(!cmd.is_reduce_only());

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
