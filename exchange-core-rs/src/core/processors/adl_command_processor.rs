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

pub struct AdlCommandProcessor;

impl TwoStepCommandProcessor for AdlCommandProcessor {

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
        cmd.size = consumed;

        CommandResultCode::Success
    }

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

        let mut scored: Vec<(i64, SymbolPositionRecord)> =
            filtered.into_iter().map(|pos| (LiquidationService::risk_score(&pos, bankruptcy_price), pos)).collect();
        scored.sort_by(|a, b| a.0.cmp(&b.0));
        scored.reverse();

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
            return;
        };
        let position_key = up.create_positions_key(symbol, action.opposite(), OrderCommandType::AutoDeleveraging);
        if !up.positions.contains_key(&position_key) {
            return;
        }
        RiskEngine::close_and_settle_futures_position(
            up, position_key, action, exec_size, price, spec, currency_spec, fund_events, last_price_cache, ssp,
            FundEventType::AdlPositionClose, order_id,
        );
    }

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

    #[test]
    fn collect_input_filters_out_same_direction_as_action() {
        let long_pos = candidate(1, PositionDirection::Long, 10, 500, 100, 100, 0);
        let short_pos = candidate(2, PositionDirection::Short, 10, 1500, 100, 100, 0);
        let picks = AdlCommandProcessor::collect_input(vec![long_pos, short_pos], OrderAction::Bid, 100, 100);
        assert_eq!(picks.len(), 1);
        assert_eq!(picks[0].uid, 2);
    }

    #[test]
    fn collect_input_filters_out_non_positive_unrealized_pnl() {
        let losing_short = candidate(1, PositionDirection::Short, 10, 500, 100, 100, 0);
        let picks = AdlCommandProcessor::collect_input(vec![losing_short], OrderAction::Bid, 100, 100);
        assert!(picks.is_empty());
    }

    #[test]
    fn collect_input_filters_out_when_pending_adl_size_covers_open_volume() {
        let fully_reserved = candidate(1, PositionDirection::Short, 10, 1500, 100, 100, 10);
        let picks = AdlCommandProcessor::collect_input(vec![fully_reserved], OrderAction::Bid, 100, 100);
        assert!(picks.is_empty());
    }

    #[test]
    fn collect_input_sorts_by_risk_score_descending() {
        let low_score = candidate(1, PositionDirection::Short, 5, 750, 100, 10, 0);
        let high_score = candidate(2, PositionDirection::Short, 5, 750, 100, 90, 0);
        let picks = AdlCommandProcessor::collect_input(vec![low_score, high_score], OrderAction::Bid, 100, 3);
        assert_eq!(picks[0].uid, 2, "the higher-score candidate must be picked first");
    }

    #[test]
    fn collect_input_tie_break_reverses_input_order_like_java_reverse_this() {
        let a = candidate(1, PositionDirection::Short, 5, 750, 100, 50, 0);
        let b = candidate(2, PositionDirection::Short, 5, 750, 100, 50, 0);
        let picks = AdlCommandProcessor::collect_input(vec![a, b], OrderAction::Bid, 100, 10);
        assert_eq!(picks.len(), 2);
        assert_eq!(picks[0].uid, 2, "reverseThis reverses same-score order: uid2, scanned later, ranks first");
        assert_eq!(picks[1].uid, 1);
    }

    #[test]
    fn collect_input_greedy_take_caps_at_min_available_and_remaining() {
        let big_candidate = candidate(1, PositionDirection::Short, 100, 15000, 100, 100, 0);
        let picks = AdlCommandProcessor::collect_input(vec![big_candidate], OrderAction::Bid, 100, 30);
        assert_eq!(picks.len(), 1);
        assert_eq!(picks[0].volume, 30, "canTake = min(available=100, remaining=30) = 30");
    }

    #[test]
    fn collect_input_stops_once_remaining_exhausted() {
        let a = candidate(1, PositionDirection::Short, 5, 750, 100, 100, 0);
        let b = candidate(2, PositionDirection::Short, 5, 750, 100, 90, 0);
        let c = candidate(3, PositionDirection::Short, 5, 750, 100, 80, 0);
        let picks = AdlCommandProcessor::collect_input(vec![a, b, c], OrderAction::Bid, 100, 7);
        assert_eq!(picks.len(), 2);
        assert_eq!(picks[0].uid, 1);
        assert_eq!(picks[0].volume, 5);
        assert_eq!(picks[1].uid, 2);
        assert_eq!(picks[1].volume, 2, "partial consumption: min(5, remaining=2)=2");
    }

    #[test]
    fn collect_input_remaining_non_positive_returns_empty() {
        let a = candidate(1, PositionDirection::Short, 5, 750, 100, 100, 0);
        let picks = AdlCommandProcessor::collect_input(vec![a], OrderAction::Bid, 100, 0);
        assert!(picks.is_empty());
    }

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
        let picks = vec![AdlUserPosition { uid: 1, volume: 5 }];
        let (events, consumed) = AdlCommandProcessor::build_matcher_events(&picks, 20);
        assert_eq!(events, vec![(1, 5)]);
        assert_eq!(consumed, 5, "not enough candidates: actual consumption < original requested size; cmd.size should be rewritten to this real value");
    }

    #[test]
    fn build_matcher_events_empty_candidates_rejects() {
        let (events, consumed) = AdlCommandProcessor::build_matcher_events(&[], 10);
        assert!(events.is_empty());
        assert_eq!(consumed, 0);
    }

    #[test]
    fn end_to_end_r1_then_merge_consumes_exactly_what_r1_selected_in_single_shard() {
        let a = candidate(1, PositionDirection::Short, 5, 750, 100, 100, 0);
        let b = candidate(2, PositionDirection::Short, 5, 750, 100, 90, 0);
        let picks = AdlCommandProcessor::collect_input(vec![a, b], OrderAction::Bid, 100, 8);
        assert_eq!(picks.iter().map(|p| p.volume).sum::<i64>(), 8);

        let (events, consumed) = AdlCommandProcessor::build_matcher_events(&picks, 8);
        assert_eq!(consumed, 8, "R1 already picked exactly 8; merge should fully consume all candidates without trimming any");
        for (ev, pick) in events.iter().zip(picks.iter()) {
            assert_eq!(ev.1, pick.volume, "each candidate's exec_volume must equal the R1 reserved amount, not a value partially trimmed by merge");
        }
    }
}
