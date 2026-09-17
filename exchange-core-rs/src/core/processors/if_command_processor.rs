//! 对应 Java `IFCommandProcessor`（两步处理器）：`IF_TAKEOVER` R1 在 IF balance 上 reserve notional，merge 按 floor(reserved/price) 算能承接多少（全拒非部分拒），R2 落账 counterparty 仓位 + 关 taker + 释放 reserved。

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::fund_event::FundEventType;
use crate::core::common::matcher_event_type::MatcherEventType;
use crate::core::common::matcher_trade_event::MatcherTradeEvent;
use crate::core::common::position_direction::PositionDirection;
use crate::core::processors::liquidation::liquidation_service::LiquidationService;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::twostep_command_processor::{TwoStepCommandProcessor, TwoStepContext};

/// 无状态处理器——不持有字段。
pub struct IfCommandProcessor;

impl TwoStepCommandProcessor for IfCommandProcessor {
    /// R1：`preview=min(available-reserved,size*price)` 写 `cmd.if_preview_cover`,覆盖不满→None（全拒）否则
    /// `Some(cmd.size)` 写 `cmd.if_takeover_size`;结果码恒 `Success`（REJECT 是事件级信号）。
    fn collect(&self, ctx: &mut TwoStepContext, cmd: &mut OrderCommand) -> CommandResultCode {
        let preview = Self::collect_input(&mut ctx.risk.liquidation_service, cmd.symbol, cmd.size, cmd.price);
        cmd.if_preview_cover = preview;
        cmd.if_takeover_size = Self::build_matcher_events(preview, cmd.size, cmd.price);
        CommandResultCode::Success
    }

    /// R2：成功则按 `create_positions_key` 关 taker 仓（不收手续费）+ 结算退款,全拒则合成 REJECT 供升级 ADL;
    /// 释放 reserved 无论成败都执行（与 R1 对称）。
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
            // 按 create_positions_key 查 taker 仓（非裸 symbol），ONEWAY 下等价 no-op，为 HEDGE 铺好正确接线。
            let position_key = up.create_positions_key(symbol, action, cmd.command);
            // 关仓 + 结算走与 ADL 共用的 helper（不收手续费，close_event_type=IfPositionClose）。
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
            // IF 全拒：合成 REJECT 供 advance_liquidation 升级 ADL（接受走 if_takeover_size 载体、拒绝仍经 matcher_event）。
            cmd.matcher_event = Some(Box::new(MatcherTradeEvent {
                event_type: MatcherEventType::Reject,
                ..Default::default()
            }));
        }

        // finalize 后半：无论接管成功/全拒都释放本命令预冻结的 reserved（跟 R1 对称）。
        ctx.risk.liquidation_service.release_reserved_if_notional(symbol, cmd.if_preview_cover);
    }
}

impl IfCommandProcessor {
    /// R1：对应 Java `collectInput`——薄封装 [`LiquidationService::reserve_if_notional`]（自限、永不超额承诺）。
    fn collect_input(liquidation: &mut LiquidationService, symbol: i32, size: i64, price: i64) -> i64 {
        liquidation.reserve_if_notional(symbol, size, price)
    }

    /// merge：对应 Java `buildMatcherEvents`（单 shard 塌缩版）。前置门 `remaining_size<=0 || price<=0` → 全拒；`floor(preview_cover/price) < remaining_size` → 全拒（all-or-nothing，是 IF undersize 降级到 ADL 的触发条件）；否则 `Some(take_size)` 恒等于 remaining_size。
    fn build_matcher_events(preview_cover: i64, remaining_size: i64, price: i64) -> Option<i64> {
        if remaining_size <= 0 || price <= 0 {
            return None;
        }
        let max_size_by_notional = preview_cover / price; // floor
        if max_size_by_notional < remaining_size {
            return None; // 全拒（all-or-nothing）
        }
        Some(max_size_by_notional.min(remaining_size))
    }

    /// R2 per-event：对应 Java `applyEvent`——薄封装 [`LiquidationService::accept_if_position`]。
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

    // ---- collect_input ----

    #[test]
    fn collect_input_delegates_to_reserve_if_notional() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 1_000);
        let preview = IfCommandProcessor::collect_input(&mut s, 1, 5, 100);
        assert_eq!(preview, 500);
        assert_eq!(s.notionals[&1].reserved, 500);
    }

    // ---- build_matcher_events：merge / all-or-nothing ----

    #[test]
    fn build_matcher_events_full_cover_accepts_entire_remaining_size() {
        // preview_cover=1000, price=100 -> max_size_by_notional=10 >= remaining_size=10 -> 全额接管
        let result = IfCommandProcessor::build_matcher_events(1_000, 10, 100);
        assert_eq!(result, Some(10));
    }

    #[test]
    fn build_matcher_events_exact_boundary_covers() {
        // 恰好覆盖边界（非严格小于）：max_size_by_notional == remaining_size -> 接管，非拒绝
        let result = IfCommandProcessor::build_matcher_events(900, 9, 100);
        assert_eq!(result, Some(9));
    }

    #[test]
    fn build_matcher_events_undersize_rejects_all_or_nothing() {
        // preview_cover=800, price=100 -> max_size_by_notional=8 < remaining_size=10 -> 全拒
        let result = IfCommandProcessor::build_matcher_events(800, 10, 100);
        assert_eq!(result, None, "覆盖不满即使能覆盖 80%，也必须整单拒绝，不是部分接管");
    }

    #[test]
    fn build_matcher_events_almost_covers_still_rejects() {
        // preview_cover 只差 1 个 price 单位就够 -> 仍是全拒，验证不是"差不多就行"
        let result = IfCommandProcessor::build_matcher_events(999, 10, 100); // max=9 < 10
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
        assert_eq!(IfCommandProcessor::build_matcher_events(0, 1, 100), None, "IF 完全没有可用余额 -> 全拒");
    }

    // ---- apply_event ----

    #[test]
    fn apply_event_delegates_to_accept_if_position() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 1_000);
        s.reserve_if_notional(1, 5, 100);
        IfCommandProcessor::apply_event(&mut s, 1, PositionDirection::Long, 5, 100);
        assert_eq!(s.positions[&1i64].open_volume, 5);
        assert_eq!(s.notionals[&1].available, 500);
    }

    // ---- end-to-end R1+merge+R2 wiring (无账户/仓位部分，纯 LiquidationService 状态) ----

    #[test]
    fn end_to_end_full_cover_then_release_leaves_if_notional_consistent() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 1_000);

        let preview = IfCommandProcessor::collect_input(&mut s, 1, 10, 100); // reserve 1000
        assert_eq!(preview, 1_000);

        let accepted = IfCommandProcessor::build_matcher_events(preview, 10, 100);
        assert_eq!(accepted, Some(10));

        IfCommandProcessor::apply_event(&mut s, 1, PositionDirection::Long, accepted.unwrap(), 100);
        // finalize：始终释放 preview（跟 R1 对称）
        s.release_reserved_if_notional(1, preview);

        assert_eq!(s.notionals[&1], IfNotional { available: 0, reserved: 0 });
    }

    #[test]
    fn end_to_end_undersize_rejects_but_finalize_still_releases_preview() {
        let mut s = LiquidationService::new();
        s.deposit_to_insurance_fund(1, 500); // 不够覆盖 remaining_size=10 @ price=100 (需要 1000)

        let preview = IfCommandProcessor::collect_input(&mut s, 1, 10, 100); // reserve = min(500,1000) = 500
        assert_eq!(preview, 500);
        assert_eq!(s.notionals[&1].reserved, 500);

        let accepted = IfCommandProcessor::build_matcher_events(preview, 10, 100);
        assert_eq!(accepted, None, "500/100=5 < remaining 10 -> 全拒");

        // finalize：全拒路径也必须释放 preview（对称，不留下孤儿 reserved）
        s.release_reserved_if_notional(1, preview);
        assert_eq!(s.notionals[&1], IfNotional { available: 500, reserved: 0 }, "available 分毫未动（从未 accept），reserved 归零");
    }
}
