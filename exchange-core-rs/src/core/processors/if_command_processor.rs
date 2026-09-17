//! 对应 Java `exchange.core2.core.processors.IFCommandProcessor`。
//!
//! IF（保险基金）接管命令的两步处理器：先按 `size * price` 名义价值在保险基金余额上预占
//! （`reserve_if_notional`），只有当预占额度能整单覆盖 `remaining_size` 时才接受接管，否则全拒
//! （不做部分接管，见 `build_matcher_events`）；接受后把仓位记到保险基金账本上（`accept_if_position`），
//! 并平掉发起方（taker）被接管的仓位，最后释放预占余量（与预占对称）。
//! Rust 单实例无 shard，故不像 Java 那样要按 `floor(reservedNotional / price)` 逐 shard 汇总覆盖量。

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::fund_event::FundEventType;
use crate::core::common::matcher_event_type::MatcherEventType;
use crate::core::common::matcher_trade_event::MatcherTradeEvent;
use crate::core::common::position_direction::PositionDirection;
use crate::core::processors::liquidation::liquidation_service::LiquidationService;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::twostep_command_processor::{TwoStepCommandProcessor, TwoStepContext};

/// 无状态标记类型，方法均为纯函数式的 `&self` 调用。
pub struct IfCommandProcessor;

impl TwoStepCommandProcessor for IfCommandProcessor {
    /// 对应 Java `collectInput`（预占 notional）+ matcher stage `buildMatcherEvents`（判断能否整单覆盖）的合并。
    /// 把预占额度写回 `cmd.if_preview_cover`（供 `apply` 结尾对称释放），把实际能接管的量（覆盖不足则为
    /// `None`）写回 `cmd.if_takeover_size`。
    fn collect(&self, ctx: &mut TwoStepContext, cmd: &mut OrderCommand) -> CommandResultCode {
        let preview = Self::collect_input(&mut ctx.risk.liquidation_service, cmd.symbol, cmd.size, cmd.price);
        cmd.if_preview_cover = preview;
        cmd.if_takeover_size = Self::build_matcher_events(preview, cmd.size, cmd.price);
        CommandResultCode::Success
    }

    /// 对应 Java R2 `applyEvent`（写入保险基金仓位）+ `finalizeForCommand`（平 taker 仓位 + 释放预占）的合并。
    /// `accepted_size` 为 `None`（覆盖不足被全拒）时只生成 REJECT 事件，不落账任何仓位；无论接受与否，
    /// 结尾都必须释放 `collect` 阶段的预占（与预占对称，否则保险基金 notional 额度会永久少一块）。
    fn apply(&self, ctx: &mut TwoStepContext, cmd: &mut OrderCommand) {
        let symbol = cmd.symbol;
        let price = cmd.price;
        let action = cmd.action.expect("IF_TAKEOVER requires action");
        let accepted_size = cmd.if_takeover_size.take();

        if let Some(size) = accepted_size {
            let direction = PositionDirection::of_action(action);
            Self::apply_event(&mut ctx.risk.liquidation_service, symbol, direction, size, price);

            let spec = ctx.ssp.get_symbol(symbol).cloned().unwrap_or_else(|| panic!("symbol spec missing for symbol {symbol}"));
            let currency_spec = ctx
                .ssp
                .get_currency(spec.quote_currency)
                .cloned()
                .unwrap_or_else(|| panic!("currency spec missing for currency {}", spec.quote_currency));

            let up = ctx.ups.get_or_add_suspended(cmd.uid);
            let position_key = up.create_positions_key(symbol, action, cmd.command);
            RiskEngine::close_and_settle_futures_position(
                up,
                position_key,
                action.opposite(),
                cmd.size,
                price,
                &spec,
                &currency_spec,
                &mut cmd.fund_events,
                &ctx.risk.last_price_cache,
                ctx.ssp,
                FundEventType::IfPositionClose,
                cmd.order_id,
            );
        } else {
            cmd.matcher_event = Some(Box::new(MatcherTradeEvent {
                event_type: MatcherEventType::Reject,
                ..Default::default()
            }));
        }

        ctx.risk.liquidation_service.release_reserved_if_notional(symbol, cmd.if_preview_cover);
    }
}

impl IfCommandProcessor {
    /// 对应 Java `collectInput`：在保险基金 notional 余额上为本次接管预占 `size * price`，返回实际预占到
    /// 的额度（余额不足时小于请求值）。
    fn collect_input(liquidation: &mut LiquidationService, symbol: i32, size: i64, price: i64) -> i64 {
        liquidation.reserve_if_notional(symbol, size, price)
    }

    /// 对应 Java matcher stage `buildMatcherEvents`：按 `floor(preview_cover / price)` 算出预占额度能覆盖
    /// 的最大数量；只有覆盖量 ≥ `remaining_size` 才整单接受，否则返回 `None` 全拒——保险基金接管是
    /// all-or-nothing，不做部分接管。
    fn build_matcher_events(preview_cover: i64, remaining_size: i64, price: i64) -> Option<i64> {
        if remaining_size <= 0 || price <= 0 {
            return None;
        }
        let max_size_by_notional = preview_cover / price;
        if max_size_by_notional < remaining_size {
            return None;
        }
        Some(max_size_by_notional.min(remaining_size))
    }

    /// 对应 Java R2 `applyEvent`：把接受的接管量记到保险基金在该 symbol/方向上的仓位账本。
    fn apply_event(
        liquidation: &mut LiquidationService,
        symbol: i32,
        direction: crate::core::common::position_direction::PositionDirection,
        size: i64,
        price: i64,
    ) {
        liquidation.accept_if_position(symbol, direction, size, price);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::position_direction::PositionDirection;
    use crate::core::processors::liquidation::liquidation_service::IfNotional;

    #[test]
    fn collect_input_delegates_to_reserve_if_notional() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 1_000);
        let preview = IfCommandProcessor::collect_input(&mut s, 1, 5, 100);
        assert_eq!(preview, 500);
        assert_eq!(s.notionals[&1].reserved, 500);
    }

    #[test]
    fn build_matcher_events_full_cover_accepts_entire_remaining_size() {
        let result = IfCommandProcessor::build_matcher_events(1_000, 10, 100);
        assert_eq!(result, Some(10));
    }

    #[test]
    fn build_matcher_events_exact_boundary_covers() {
        let result = IfCommandProcessor::build_matcher_events(900, 9, 100);
        assert_eq!(result, Some(9));
    }

    #[test]
    fn build_matcher_events_undersize_rejects_all_or_nothing() {
        let result = IfCommandProcessor::build_matcher_events(800, 10, 100);
        assert_eq!(result, None, "even if coverage reaches 80%, an under-covered order must be rejected in full, not partially taken over");
    }

    #[test]
    fn build_matcher_events_almost_covers_still_rejects() {
        let result = IfCommandProcessor::build_matcher_events(999, 10, 100);
        assert_eq!(result, None);
    }

    #[test]
    fn build_matcher_events_non_positive_remaining_size_or_price_rejects() {
        assert_eq!(IfCommandProcessor::build_matcher_events(1_000, 0, 100), None);
        assert_eq!(IfCommandProcessor::build_matcher_events(1_000, -1, 100), None);
        assert_eq!(IfCommandProcessor::build_matcher_events(1_000, 10, 0), None);
        assert_eq!(IfCommandProcessor::build_matcher_events(1_000, 10, -5), None);
    }

    #[test]
    fn build_matcher_events_zero_preview_cover_rejects_when_remaining_positive() {
        assert_eq!(IfCommandProcessor::build_matcher_events(0, 1, 100), None, "IF has zero available balance -> reject entirely");
    }

    #[test]
    fn apply_event_delegates_to_accept_if_position() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 1_000);
        s.reserve_if_notional(1, 5, 100);
        IfCommandProcessor::apply_event(&mut s, 1, PositionDirection::Long, 5, 100);
        assert_eq!(s.positions[&1i64].open_volume, 5);
        assert_eq!(s.notionals[&1].available, 500);
    }

    #[test]
    fn end_to_end_full_cover_then_release_leaves_if_notional_consistent() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 1_000);

        let preview = IfCommandProcessor::collect_input(&mut s, 1, 10, 100);
        assert_eq!(preview, 1_000);

        let accepted = IfCommandProcessor::build_matcher_events(preview, 10, 100);
        assert_eq!(accepted, Some(10));

        IfCommandProcessor::apply_event(&mut s, 1, PositionDirection::Long, accepted.unwrap(), 100);
        s.release_reserved_if_notional(1, preview);

        assert_eq!(s.notionals[&1], IfNotional { available: 0, reserved: 0 });
    }

    #[test]
    fn end_to_end_undersize_rejects_but_finalize_still_releases_preview() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 500);

        let preview = IfCommandProcessor::collect_input(&mut s, 1, 10, 100);
        assert_eq!(preview, 500);
        assert_eq!(s.notionals[&1].reserved, 500);

        let accepted = IfCommandProcessor::build_matcher_events(preview, 10, 100);
        assert_eq!(accepted, None, "500/100=5 < remaining 10 -> reject entirely");

        s.release_reserved_if_notional(1, preview);
        assert_eq!(s.notionals[&1], IfNotional { available: 500, reserved: 0 }, "available is untouched (never accepted), reserved is zeroed");
    }
}
