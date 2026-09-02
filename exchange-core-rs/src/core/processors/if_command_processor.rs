//! 对应 Java: `exchange.core2.core.processors.IFCommandProcessor`（129 行，`TwoStepCommandProcessor`
//! 的一个薄实例）。`IF_TAKEOVER` 两步处理器：R1 各 shard 在 IF balance 上 reserve notional；
//! merge 按 `floor(reserved / price)` 算能承接多少（**全拒非部分拒**——见
//! [`IfCommandProcessor::build_matcher_event`]）；R2 落账 counterparty 仓位 + 关 taker +
//! 释放 reserved 余量（跟 R1 对称）。参考文档 §2.2。
//!
//! # 三段式与账户结算的落点分工（同 P6 既有先例）
//! 本文件只落地 R1/merge/R2-apply 三个**不触碰用户账户**的纯记账原语（全部委托
//! [`LiquidationService`] 的方法或纯函数）——R2 finalize（关 taker 仓 + refund extra margin +
//! 结算 profit + release reserved）需要同时借用 `UserProfileService`/
//! `SymbolSpecificationProvider`，跟 `FundingFeeCommandProcessor::settle_funding_fee`/
//! `InternalTransferProcessor::apply_event` 走的是同一条路线不同：那两个函数都在各自文件里就地
//! 完成账户落账，但 IF finalize 还需要判定 `taker_spr.is_empty()` 后触发的一整套仓位清理
//! （`refund_extra_margin`/`remove_position_record` 内联逻辑）——这套逻辑在 `risk_engine.rs`
//! 的 `settle_margin_position_event` 里已有一份几乎逐字相同的实现（TRADE 事件收尾），为避免
//! 引入第二套微妙不同的"仓位清空后结算"实现，`RiskEngine::if_takeover_apply`
//! 直接内联同款逻辑（少了 taker/maker 手续费那一段——IF 接管不收成交费，对应 Java
//! `finalizeForCommand` 确实没有算 fee），而不是把它拆成本文件里的第四个函数。
//!
//! # 事件载体的移植偏差（同 P5/P6 既有先例，Ruling P6-A/P6-C）
//! Java 用 `MatcherEventType::IF_EVENT`（`matchedOrderUid` 承载 shard id）在 R1→ME(merge)→R2
//! 之间传递数据，`OrderCommand.ifPreviewCoverByShard[]`（按 shard 下标数组）承载 R1 输出。本移植
//! 不扩 `MatcherEventType`（撮合引擎共享类型，Ruling P6-A），改用
//! `OrderCommand.if_preview_cover: i64`（R1 输出，单 shard 塌缩后退化为标量，不再是数组）+
//! `OrderCommand.if_takeover_size: Option<i64>`（merge 输出：`Some(size)` = 接管成功，`size`
//! 恒等于 `cmd.size`——单 shard 下"总覆盖量 ≥ 剩余量"这一前提一旦成立，
//! `min(maxSizeByNotional, remainingSize)` 就退化为 `remainingSize` 本身；`None` = 全拒，对应
//! Java `cmd.matcherEvent.eventType == REJECT`）。由 `RiskEngine::if_takeover_collect`
//! （R1+merge 合并，单 shard 下"跨 shard 归并"是恒等操作，同
//! `settle_funding_fees_collect`/`internal_transfer_collect` 先例）一次性完成，写入这两个字段；
//! `RiskEngine::if_takeover_apply` 消费。
//!
//! # 未移植：`normalizeCmdPositionSize`（Task 7 排期）
//! Java `RiskEngine.java:365-373`（`case IF_TAKEOVER`）在调用 `ifProcessor.collectInput(cmd)`
//! **之后**、R1 结果码落定**之前**，额外调用 `normalizeCmdPositionSize(cmd)`
//! （`RiskEngine.java:724-740`）——按 taker 当前 `openVolume` 收敛 `cmd.size`（不能接管超过
//! 实际持仓量），且"无仓位则直接 `SUCCESS` 短路，不进 matching engine"。参考文档把这个函数放在
//! §1.6（`LiquidationEngine` 一节），不在 §2（本 Task 范围的 `IFCommandProcessor`/
//! `LiquidationService`）——`normalizeCmdPositionSize` 与 `checkPositions`/`advanceLiquidation`
//! 同属 Task 7 的 `LiquidationEngine` 编排层职责（决定"什么时候构造/如何收敛一条 IF_TAKEOVER
//! 命令"），不是 `IFCommandProcessor` 自身的 R1/merge/R2 语义。本 Task 的
//! `RiskEngine::if_takeover_collect` 因此按调用方已给定的 `cmd.size` 直接 reserve，不做收敛——
//! Task 7 接入 `LiquidationEngine` 时需要在构造 `IF_TAKEOVER` 命令前先做这一步归一化（本 Task 的
//! `reserve_if_notional`/`build_matcher_event` 不关心 size 从哪来，收窄 size 只会让覆盖率更容易
//! 达标，不影响本 Task 已验证的 all-or-nothing/conservation 语义）。

use crate::core::processors::liquidation::liquidation_service::LiquidationService;

/// 无状态处理器——同 `FundingFeeCommandProcessor`/`InternalTransferProcessor` 先例，所有方法都是
/// 关联函数，不持有任何字段（Java 版本持有 `riskEngine`/`eventsHelper` 只是运行时门禁，单线程
/// 同步调用模型不需要）。
pub struct IfCommandProcessor;

impl IfCommandProcessor {
    /// R1：对应 Java `collectInput`（`:31-36`）——薄封装 [`LiquidationService::reserve_if_notional`]
    /// （`min(available-reserved, size*price)`，**caps to coverable，never over-promises**）。
    pub fn collect_input(liquidation: &mut LiquidationService, symbol: i32, size: i64, price: i64) -> i64 {
        liquidation.reserve_if_notional(symbol, size, price)
    }

    /// merge：对应 Java `buildMatcherEvents`（`:39-72`）——单 shard 塌缩版（Ruling P6-C）。
    ///
    /// Java 用 `remainingSize <= 0 || price <= 0 -> REJECT` 前置门（`:41-44`），随后
    /// `totalCoverSize = Σ (reservedByShard[i] / price)`（**floor division 逐 shard 算完再求和**
    /// ——碎片化的名义价值不能跨 shard 池化，Java 注释原文：不能用 notional 总和判够）。单 shard
    /// 下这个求和退化为对唯一一个 shard 的 `preview_cover / price` 取值，无求和可言，但语义
    /// 完全等价（多 shard 场景下的"逐 shard floor 再求和"与"单 shard floor"在 shard 数=1 时是
    /// 同一个表达式）。
    ///
    /// `total_cover_size < remaining_size` → 全拒（`None`，对应 Java `buildRejectEvent()`）——
    /// **all-or-nothing，不是部分接管**：哪怕能覆盖 99%，覆盖不满就整单拒绝，这是 IF undersize
    /// 场景降级到 ADL 的触发条件（Task 7 `advanceLiquidation` 消费，参考文档 §1.5）。
    ///
    /// 否则 `Some(take_size)`，`take_size = min(max_size_by_notional, remaining_size)`——单 shard
    /// 下 `max_size_by_notional = total_cover_size >= remaining_size`（刚通过上面的门），故
    /// `take_size` 恒等于 `remaining_size`（`cmd.size`）：单 shard collapse 下"接管"要么全额、
    /// 要么全拒，没有中间态，这正是多 shard 版本"per-shard 部分承接、累加凑够 remaining"这个循环
    /// 在 shard 数=1 时的退化结果，不是本移植额外简化的行为。
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

    /// R2 per-event：对应 Java `applyEvent`（`:75-86`，已过滤 `ev.matchedOrderUid == shardId` 的
    /// 单 shard 恒真情形）——薄封装 [`LiquidationService::accept_if_position`]。
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
