//! 对应 Java `ADLCommandProcessor`（两步处理器）：`AUTO_DELEVERAGING` R1 按 risk_score DESC 选盈利候选 + 预占 pending_adl_size，merge 消费出执行量，R2 关 counterparty 仓位 + 对称释放。
use std::collections::BTreeMap;

use crate::core::common::adl_user_position::AdlUserPosition;
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::fund_event::{FundEvent, FundEventType};
use crate::core::common::last_price_cache_record::LastPriceCacheRecord;
use crate::core::common::order_action::OrderAction;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::processors::liquidation::liquidation_service::LiquidationService;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::twostep_command_processor::{TwoStepCommandProcessor, TwoStepContext};
use crate::core::processors::user_profile_service::UserProfileService;

/// 无状态处理器——参见模块文档。
pub struct AdlCommandProcessor;

impl TwoStepCommandProcessor for AdlCommandProcessor {
    /// R1:候选取自 `compute_profitable_positions_by_symbol`,`collect_input` 排序+贪心分配,写回 `pending_adl_size`,
    /// `build_matcher_events` 产出 `cmd.adl_events` 并把 `cmd.size` 改写为实际消费量;结果码恒 `Success`。
    fn collect(&self, ctx: &mut TwoStepContext, cmd: &mut OrderCommand) -> CommandResultCode {
        cmd.adl_user_positions.clear();
        cmd.adl_events.clear();

        let symbol = cmd.symbol;
        let action = cmd.action.expect("AUTO_DELEVERAGING requires action");
        let bankruptcy_price = cmd.price;
        let remaining_size = cmd.size;
        if remaining_size <= 0 {
            return CommandResultCode::Success;
        }

        let mut candidates_map =
            LiquidationService::compute_profitable_positions_by_symbol(ctx.ups, ctx.ssp, &ctx.risk.last_price_cache);
        let candidates = candidates_map.remove(&symbol).unwrap_or_default();

        let picks = Self::collect_input(candidates, action, bankruptcy_price, remaining_size);

        // R1 写回：预占 pending_adl_size（与 finalize 对称释放）。
        for pick in &picks {
            if let Some(profile) = ctx.ups.users.get_mut(&pick.uid) {
                let position_key = profile.create_positions_key(symbol, action.opposite(), OrderCommandType::AutoDeleveraging);
                if let Some(pos) = profile.positions.get_mut(&position_key) {
                    pos.pending_adl_size += pick.volume;
                }
            }
        }

        let (events, consumed) = Self::build_matcher_events(&picks, remaining_size);
        cmd.adl_user_positions = picks;
        cmd.adl_events = events;
        cmd.size = consumed; // 真实平仓数量，R2 finalize 用它关 taker 自己的仓

        CommandResultCode::Success
    }

    /// R2:逐事件关 counterparty 仓（[`apply_event`](Self::apply_event)），再关 taker 仓 + 对称释放 pending
    /// （[`finalize_for_command`](Self::finalize_for_command)）。
    fn apply(&self, ctx: &mut TwoStepContext, cmd: &mut OrderCommand) {
        let symbol = cmd.symbol;
        let price = cmd.price;
        let action = cmd.action.expect("AUTO_DELEVERAGING requires action");

        let spec = ctx.ssp.get_symbol(symbol).cloned().unwrap_or_else(|| panic!("symbol spec missing for symbol {symbol}"));
        let currency_spec = ctx
            .ssp
            .get_currency(spec.quote_currency)
            .cloned()
            .unwrap_or_else(|| panic!("currency spec missing for currency {}", spec.quote_currency));

        let order_id = cmd.order_id;
        let events = std::mem::take(&mut cmd.adl_events);

        for &(uid, exec_size) in &events {
            Self::apply_event(
                ctx.ups, symbol, action, price, order_id, uid, exec_size, &spec, &currency_spec,
                &mut cmd.fund_events, &ctx.risk.last_price_cache, ctx.ssp,
            );
        }

        let adl_positions: Vec<AdlUserPosition> = std::mem::take(&mut cmd.adl_user_positions);
        Self::finalize_for_command(
            ctx.ups, symbol, action, price, order_id, cmd.uid, cmd.size, !events.is_empty(), &adl_positions,
            &spec, &currency_spec, &mut cmd.fund_events, &ctx.risk.last_price_cache, ctx.ssp,
        );
    }
}

impl AdlCommandProcessor {
    /// R1：对应 Java `collectInput`——按 risk_score DESC 贪心分配，筛选反向+浮盈候选，直至 remaining_size 耗尽；不写回 pending_adl_size（调用方职责）。
    fn collect_input(
        candidates: Vec<SymbolPositionRecord>,
        action: OrderAction,
        bankruptcy_price: i64,
        remaining_size: i64,
    ) -> Vec<AdlUserPosition> {
        let filtered: Vec<SymbolPositionRecord> = candidates
            .into_iter()
            .filter(|pos| {
                pos.open_volume > 0
                    && pos.open_volume > pos.pending_adl_size
                    && !pos.direction.is_same_as_action(action)
                    && LiquidationService::unrealized_pnl(pos, bankruptcy_price) > 0
            })
            .collect();

        // 逐字复刻 Java `sortThisByLong(riskScore).reverseThis()`：升序稳定排序再整体 reverse，同分 tie-break 会反转相对序，不等价于直接降序稳定排序。
        let mut scored: Vec<(i64, SymbolPositionRecord)> =
            filtered.into_iter().map(|pos| (LiquidationService::risk_score(&pos, bankruptcy_price), pos)).collect();
        scored.sort_by(|a, b| a.0.cmp(&b.0)); // 升序稳定
        scored.reverse(); // 整体反转（同分序也反转）

        let mut remaining = remaining_size;
        let mut out = Vec::new();
        for (_, pos) in &scored {
            if remaining <= 0 {
                break;
            }
            let available = pos.open_volume - pos.pending_adl_size;
            let can_take = available.min(remaining);
            out.push(AdlUserPosition { uid: pos.uid, volume: can_take });
            remaining -= can_take;
        }
        out
    }

    /// merge：对应 Java `buildMatcherEvents`（单 shard 塌缩版）——顺序遍历已排序候选取 exec=min(volume,remaining)；返回 (events, total_consumed)，空/耗尽时返回空 events。
    fn build_matcher_events(candidates: &[AdlUserPosition], remaining_size: i64) -> (Vec<(i64, i64)>, i64) {
        let mut remaining = remaining_size;
        let mut events = Vec::new();
        for node in candidates {
            if remaining <= 0 {
                break;
            }
            let exec = node.volume.min(remaining);
            if exec <= 0 {
                continue;
            }
            events.push((node.uid, exec));
            remaining -= exec;
        }
        let consumed = remaining_size.max(0) - remaining.max(0);
        (events, consumed)
    }

    /// R2 per-event：对应 Java `applyEvent`——关一个 counterparty(反向浮盈对手)仓 + 结算。
    /// counterparty 的 UserProfile / 仓位在 R1→R2 之间可能已消失,best-effort skip(非 error)。
    #[allow(clippy::too_many_arguments)]
    fn apply_event(
        ups: &mut UserProfileService,
        symbol: i32,
        action: OrderAction,
        price: i64,
        order_id: i64,
        uid: i64,
        exec_size: i64,
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
        fund_events: &mut Vec<FundEvent>,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        ssp: &SymbolSpecificationProvider,
    ) {
        let Some(up) = ups.users.get_mut(&uid) else {
            return; // counterparty UserProfile 已消失 -> skip
        };
        let position_key = up.create_positions_key(symbol, action.opposite(), OrderCommandType::AutoDeleveraging);
        if !up.positions.contains_key(&position_key) {
            return; // counterparty 仓位已被关掉 -> skip
        }
        RiskEngine::close_and_settle_futures_position(
            up, position_key, action, exec_size, price, spec, currency_spec, fund_events, last_price_cache, ssp,
            FundEventType::AdlPositionClose, order_id,
        );
    }

    /// R2 finalize：对应 Java `finalizeForCommand`——有实际成交时关 taker(loser)自身仓,再对称释放本命令
    /// 全部候选(R1 原始表)的 `pending_adl_size`(与 R1 `+=` 对称,不管 apply 实际消费多少)。
    #[allow(clippy::too_many_arguments)]
    fn finalize_for_command(
        ups: &mut UserProfileService,
        symbol: i32,
        action: OrderAction,
        price: i64,
        order_id: i64,
        taker_uid: i64,
        taker_size: i64,
        had_events: bool,
        adl_user_positions: &[AdlUserPosition],
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
        fund_events: &mut Vec<FundEvent>,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        ssp: &SymbolSpecificationProvider,
    ) {
        if had_events {
            let up = ups.get_or_add_suspended(taker_uid);
            let taker_key = up.create_positions_key(symbol, action, OrderCommandType::AutoDeleveraging);
            if up.positions.contains_key(&taker_key) {
                RiskEngine::close_and_settle_futures_position(
                    up, taker_key, action.opposite(), taker_size, price, spec, currency_spec, fund_events,
                    last_price_cache, ssp, FundEventType::AdlOriginClose, order_id,
                );
            }
        }
        for pick in adl_user_positions {
            if let Some(up) = ups.users.get_mut(&pick.uid) {
                let position_key = up.create_positions_key(symbol, action.opposite(), OrderCommandType::AutoDeleveraging);
                if let Some(pos) = up.positions.get_mut(&position_key) {
                    if pos.pending_adl_size > 0 {
                        pos.pending_adl_size -= pick.volume;
                    }
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::margin_mode::MarginMode;
    use crate::core::common::position_direction::PositionDirection;

    fn candidate(uid: i64, direction: PositionDirection, open_volume: i64, open_price_sum: i64, open_init_margin_sum: i64, adl_eligibility: i64, pending_adl_size: i64) -> SymbolPositionRecord {
        let mut p = SymbolPositionRecord::new(uid, 100, 2, MarginMode::Isolated, 1);
        p.direction = direction;
        p.open_volume = open_volume;
        p.open_price_sum = open_price_sum;
        p.open_init_margin_sum = open_init_margin_sum;
        p.adl_eligibility = adl_eligibility;
        p.pending_adl_size = pending_adl_size;
        p
    }

    // ---- collect_input：筛选条件 ----

    #[test]
    fn collect_input_filters_out_same_direction_as_action() {
        // action=Bid（做多）触发 ADL 时，只吃 SHORT 候选（反向）；LONG 候选（同向）必须被过滤掉。
        let long_pos = candidate(1, PositionDirection::Long, 10, 500, 100, 100, 0); // bankruptcy=100 -> unrealizedPnl=(100*10-500)*1=500>0 但同向
        let short_pos = candidate(2, PositionDirection::Short, 10, 1500, 100, 100, 0); // unrealizedPnl=(100*10-1500)*-1=500>0 反向
        let picks = AdlCommandProcessor::collect_input(vec![long_pos, short_pos], OrderAction::Bid, 100, 100);
        assert_eq!(picks.len(), 1);
        assert_eq!(picks[0].uid, 2);
    }

    #[test]
    fn collect_input_filters_out_non_positive_unrealized_pnl() {
        // SHORT 候选但按破产价已经不盈利（unrealizedPnl<=0）-> 过滤掉
        let losing_short = candidate(1, PositionDirection::Short, 10, 500, 100, 100, 0); // (100*10-500)*-1 = -500 <=0
        let picks = AdlCommandProcessor::collect_input(vec![losing_short], OrderAction::Bid, 100, 100);
        assert!(picks.is_empty());
    }

    #[test]
    fn collect_input_filters_out_when_pending_adl_size_covers_open_volume() {
        // openVolume <= pendingADLSize -> 已经没有余量可摊派
        let fully_reserved = candidate(1, PositionDirection::Short, 10, 1500, 100, 100, 10);
        let picks = AdlCommandProcessor::collect_input(vec![fully_reserved], OrderAction::Bid, 100, 100);
        assert!(picks.is_empty());
    }

    // ---- collect_input：risk_score 排序 + 贪心分配 ----

    #[test]
    fn collect_input_sorts_by_risk_score_descending() {
        // 两个 SHORT 候选，eligibility 不同 -> risk_score 不同，验证选取顺序按分值 DESC
        let low_score = candidate(1, PositionDirection::Short, 5, 750, 100, 10, 0); // eligibility=10
        let high_score = candidate(2, PositionDirection::Short, 5, 750, 100, 90, 0); // eligibility=90，其余相同 -> 分值更高
        let picks = AdlCommandProcessor::collect_input(vec![low_score, high_score], OrderAction::Bid, 100, 3);
        assert_eq!(picks[0].uid, 2, "高分候选必须排第一个被选中");
    }

    #[test]
    fn collect_input_tie_break_reverses_input_order_like_java_reverse_this() {
        // 分值相同时 Java reverseThis 会反转相对序，先扫到的 uid1 反而排后——逐字对齐 tie-break。
        let a = candidate(1, PositionDirection::Short, 5, 750, 100, 50, 0);
        let b = candidate(2, PositionDirection::Short, 5, 750, 100, 50, 0);
        let picks = AdlCommandProcessor::collect_input(vec![a, b], OrderAction::Bid, 100, 10);
        assert_eq!(picks.len(), 2);
        assert_eq!(picks[0].uid, 2, "reverseThis 把同分序反转：后扫到的 uid2 排第一");
        assert_eq!(picks[1].uid, 1);
    }

    #[test]
    fn collect_input_greedy_take_caps_at_min_available_and_remaining() {
        let big_candidate = candidate(1, PositionDirection::Short, 100, 15000, 100, 100, 0); // available=100
        let picks = AdlCommandProcessor::collect_input(vec![big_candidate], OrderAction::Bid, 100, 30);
        assert_eq!(picks.len(), 1);
        assert_eq!(picks[0].volume, 30, "canTake = min(available=100, remaining=30) = 30");
    }

    #[test]
    fn collect_input_stops_once_remaining_exhausted() {
        let a = candidate(1, PositionDirection::Short, 5, 750, 100, 100, 0);
        let b = candidate(2, PositionDirection::Short, 5, 750, 100, 90, 0);
        let c = candidate(3, PositionDirection::Short, 5, 750, 100, 80, 0);
        // remaining=7: a(可用5)+b(可用2, 部分)后耗尽，c 完全不入选
        let picks = AdlCommandProcessor::collect_input(vec![a, b, c], OrderAction::Bid, 100, 7);
        assert_eq!(picks.len(), 2);
        assert_eq!(picks[0].uid, 1);
        assert_eq!(picks[0].volume, 5);
        assert_eq!(picks[1].uid, 2);
        assert_eq!(picks[1].volume, 2, "部分消费：min(5, remaining=2)=2");
    }

    #[test]
    fn collect_input_remaining_non_positive_returns_empty() {
        let a = candidate(1, PositionDirection::Short, 5, 750, 100, 100, 0);
        let picks = AdlCommandProcessor::collect_input(vec![a], OrderAction::Bid, 100, 0);
        assert!(picks.is_empty());
    }

    // ---- build_matcher_events：merge / cmd.size 改写 ----

    #[test]
    fn build_matcher_events_full_consumption_when_sum_covers_remaining() {
        let picks = vec![
            AdlUserPosition { uid: 1, volume: 5 },
            AdlUserPosition { uid: 2, volume: 2 },
        ];
        let (events, consumed) = AdlCommandProcessor::build_matcher_events(&picks, 7);
        assert_eq!(events, vec![(1, 5), (2, 2)]);
        assert_eq!(consumed, 7);
    }

    #[test]
    fn build_matcher_events_partial_candidate_list_consumes_only_available() {
        // 候选总量(5) < remaining_size(20) -> 只能消费到候选耗尽，不是全部 remaining
        let picks = vec![AdlUserPosition { uid: 1, volume: 5 }];
        let (events, consumed) = AdlCommandProcessor::build_matcher_events(&picks, 20);
        assert_eq!(events, vec![(1, 5)]);
        assert_eq!(consumed, 5, "候选不够，实际消费 < 原始请求量，cmd.size 应改写为这个真实值");
    }

    #[test]
    fn build_matcher_events_empty_candidates_rejects() {
        let (events, consumed) = AdlCommandProcessor::build_matcher_events(&[], 10);
        assert!(events.is_empty());
        assert_eq!(consumed, 0);
    }

    // ---- end-to-end R1+merge（无账户/仓位部分，纯选择+归并算法）----

    #[test]
    fn end_to_end_r1_then_merge_consumes_exactly_what_r1_selected_in_single_shard() {
        // 单 shard 下每个候选都被完整消费，不存在"部分消费改 volume"分支（见模块文档）。
        let a = candidate(1, PositionDirection::Short, 5, 750, 100, 100, 0);
        let b = candidate(2, PositionDirection::Short, 5, 750, 100, 90, 0);
        let picks = AdlCommandProcessor::collect_input(vec![a, b], OrderAction::Bid, 100, 8);
        assert_eq!(picks.iter().map(|p| p.volume).sum::<i64>(), 8);

        let (events, consumed) = AdlCommandProcessor::build_matcher_events(&picks, 8);
        assert_eq!(consumed, 8, "R1 已经选到刚好 8，merge 应当把全部候选完整消费掉，不裁剪任何一个");
        for (ev, pick) in events.iter().zip(picks.iter()) {
            assert_eq!(ev.1, pick.volume, "每个候选的 exec_volume 必须等于 R1 预占量，不是被 merge 部分裁剪后的值");
        }
    }
}
