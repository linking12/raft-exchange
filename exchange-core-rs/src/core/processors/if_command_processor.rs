//! 对应 Java `IFCommandProcessor`（`TwoStepCommandProcessor` 薄实例）。`IF_TAKEOVER` 两步处理器：
//! R1 各 shard 在 IF balance 上 reserve notional，merge 按 floor(reserved/price) 算能承接多少
//! （全拒非部分拒），R2 落账 counterparty 仓位+关 taker+释放 reserved（参考文档 §2.2）。
//! R2 finalize 内联复用 `risk_engine.rs::settle_margin_position_event` 同款仓位清理逻辑（不收手续费），
//! 须经 `create_positions_key` 取 key。事件载体用 `if_preview_cover`/`if_takeover_size` 而非 Java
//! `MatcherEventType::IF_EVENT`（Ruling P6-A/P6-C）。`normalizeCmdPositionSize` 未移植，属 Task 7
//! `LiquidationEngine` 编排层职责，不影响本文件 all-or-nothing/conservation 语义。

use crate::core::processors::liquidation::liquidation_service::LiquidationService;

/// 无状态处理器——同 `FundingFeeCommandProcessor`/`InternalTransferProcessor` 先例，不持有字段。
pub struct IfCommandProcessor;

impl IfCommandProcessor {
    /// R1：对应 Java `collectInput`（`:31-36`）——薄封装 [`LiquidationService::reserve_if_notional`]（caps to coverable，never over-promises）。
    pub fn collect_input(liquidation: &mut LiquidationService, symbol: i32, size: i64, price: i64) -> i64 {
        liquidation.reserve_if_notional(symbol, size, price)
    }

    /// merge：对应 Java `buildMatcherEvents`（`:39-72`）——单 shard 塌缩版（Ruling P6-C）。前置门
    /// `remaining_size<=0 || price<=0` → 全拒；`floor(preview_cover/price) < remaining_size` → 全拒
    /// （all-or-nothing，覆盖不满即整单拒绝，是 IF undersize 降级到 ADL 的触发条件，参考文档 §1.5）；
    /// 否则 `Some(take_size)` 恒等于 remaining_size（单 shard 下无中间态）。
    pub fn build_matcher_event(preview_cover: i64, remaining_size: i64, price: i64) -> Option<i64> {
        if remaining_size <= 0 || price <= 0 {
            return None;
        }
        let max_size_by_notional = preview_cover / price; // floor
        if max_size_by_notional < remaining_size {
            return None; // 全拒（all-or-nothing）
        }
        Some(max_size_by_notional.min(remaining_size))
    }

    /// R2 per-event：对应 Java `applyEvent`（`:75-86`）——薄封装 [`LiquidationService::accept_if_position`]。
    pub fn apply_event(
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

    // ---- build_matcher_event：merge / all-or-nothing ----

    #[test]
    fn build_matcher_event_full_cover_accepts_entire_remaining_size() {
        // preview_cover=1000, price=100 -> max_size_by_notional=10 >= remaining_size=10 -> 全额接管
        let result = IfCommandProcessor::build_matcher_event(1_000, 10, 100);
        assert_eq!(result, Some(10));
    }

    #[test]
    fn build_matcher_event_exact_boundary_covers() {
        // 恰好覆盖边界（非严格小于）：max_size_by_notional == remaining_size -> 接管，非拒绝
        let result = IfCommandProcessor::build_matcher_event(900, 9, 100);
        assert_eq!(result, Some(9));
    }

    #[test]
    fn build_matcher_event_undersize_rejects_all_or_nothing() {
        // preview_cover=800, price=100 -> max_size_by_notional=8 < remaining_size=10 -> 全拒
        let result = IfCommandProcessor::build_matcher_event(800, 10, 100);
        assert_eq!(result, None, "覆盖不满即使能覆盖 80%，也必须整单拒绝，不是部分接管");
    }

    #[test]
    fn build_matcher_event_almost_covers_still_rejects() {
        // preview_cover 只差 1 个 price 单位就够 -> 仍是全拒，验证不是"差不多就行"
        let result = IfCommandProcessor::build_matcher_event(999, 10, 100); // max=9 < 10
        assert_eq!(result, None);
    }

    #[test]
    fn build_matcher_event_non_positive_remaining_size_or_price_rejects() {
        assert_eq!(IfCommandProcessor::build_matcher_event(1_000, 0, 100), None);
        assert_eq!(IfCommandProcessor::build_matcher_event(1_000, -1, 100), None);
        assert_eq!(IfCommandProcessor::build_matcher_event(1_000, 10, 0), None);
        assert_eq!(IfCommandProcessor::build_matcher_event(1_000, 10, -5), None);
    }

    #[test]
    fn build_matcher_event_zero_preview_cover_rejects_when_remaining_positive() {
        assert_eq!(IfCommandProcessor::build_matcher_event(0, 1, 100), None, "IF 完全没有可用余额 -> 全拒");
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

        let accepted = IfCommandProcessor::build_matcher_event(preview, 10, 100);
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

        let accepted = IfCommandProcessor::build_matcher_event(preview, 10, 100);
        assert_eq!(accepted, None, "500/100=5 < remaining 10 -> 全拒");

        // finalize：全拒路径也必须释放 preview（对称，不留下孤儿 reserved）
        s.release_reserved_if_notional(1, preview);
        assert_eq!(s.notionals[&1], IfNotional { available: 500, reserved: 0 }, "available 分毫未动（从未 accept），reserved 归零");
    }
}
