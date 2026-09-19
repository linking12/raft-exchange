use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet};
use std::rc::Rc;

use crate::core::common::last_price_cache_record::LastPriceCacheRecord;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::matcher_event_type::MatcherEventType;
use crate::core::common::fund_event::{FundEvent, FundEventType};
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;
use crate::core::common::position_direction::PositionDirection;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::common::user_profile::UserProfile;
use crate::core::processors::liquidation::command_submitter::{CommandSubmitter, CommandSubmitterHandle};
use crate::core::processors::liquidation::liquidation_flow::{LiquidationFlow, LiquidationState};
use crate::core::processors::liquidation::liquidation_service::LiquidationService;
use crate::core::processors::loan::loan_liquidation_engine::LoanLiquidationEngine;
use crate::core::processors::loan::loan_service::LoanService;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::utils::core_arithmetic_utils::{
    calculate_deficit_after_liquidate, calculate_size_to_liquidate, mul_exact, size_price_to_currency_scale,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct LiquidationDecision {
    position_key: i32,
    bankruptcy_price: i64,
    size: i64,
}

enum IsolatedCheck {
    Liquidate(LiquidationDecision),
    Alert,
    Healthy,
}

#[derive(Debug, Default)]
pub struct LiquidationEngine {
    pub symbol_to_users: BTreeMap<i32, BTreeSet<i64>>,
    pub is_running: bool,
    pub loan_liquidation_engine: LoanLiquidationEngine,
    command_submitter: CommandSubmitterHandle,
}

impl LiquidationEngine {
    pub fn new() -> Self {
        LiquidationEngine::default()
    }

    pub fn set_command_submitter(&mut self, submitter: Rc<RefCell<dyn CommandSubmitter>>) {
        self.command_submitter.set(submitter.clone());
        self.loan_liquidation_engine.set_command_submitter(submitter);
    }

    pub fn on_position_opened(&mut self, uid: i64, symbol: i32) {
        self.symbol_to_users.entry(symbol).or_default().insert(uid);
    }

    pub fn on_position_closed(&mut self, profile: &UserProfile, symbol: i32, closed_key: i32) {
        let holds_other = profile.positions.iter().any(|(&k, p)| k != closed_key && p.symbol == symbol);
        if holds_other {
            return;
        }
        if let Some(s) = self.symbol_to_users.get_mut(&symbol) {
            s.remove(&profile.uid);
            if s.is_empty() {
                self.symbol_to_users.remove(&symbol);
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn check_positions(
        &mut self,
        cmd: &OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        loan_service: &LoanService,
        fund_events: &mut Vec<FundEvent>,
    ) {
        if !self.is_running {
            return;
        }
        let targeted = cmd.symbol >= 0;
        let uids: Vec<i64> = if targeted {
            match self.symbol_to_users.get(&cmd.symbol) {
                Some(holders) => holders.iter().copied().collect(),
                None => Vec::new(),
            }
        } else {
            ups.users.keys().copied().filter(|&uid| Self::covered_by_scan_slice(cmd, uid)).collect()
        };
        for uid in &uids {
            self.check_user(*uid, cmd.timestamp, ups, ssp, last_price_cache, fund_events);
        }

        if targeted {
            if let Some(holders) = self.symbol_to_users.get_mut(&cmd.symbol) {
                holders.retain(|uid| {
                    ups.get(*uid).is_some_and(|u| u.positions.values().any(|p| p.symbol == cmd.symbol))
                });
                if holders.is_empty() {
                    self.symbol_to_users.remove(&cmd.symbol);
                }
            }
        }
        self.loan_liquidation_engine.check_loans(cmd, ups, ssp, last_price_cache, loan_service, fund_events);
    }

    #[allow(clippy::too_many_arguments)]
    fn check_user(
        &mut self,
        uid: i64,
        ts: i64,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        fund_events: &mut Vec<FundEvent>,
    ) {
        let decisions: Vec<LiquidationDecision> = {
            let profile = match ups.get(uid) {
                Some(p) => p,
                None => return,
            };
            let mut decisions = Vec::new();
            let mut cross_by_currency: BTreeMap<i32, Vec<i32>> = BTreeMap::new();
            for (&key, position) in profile.positions.iter() {
                if position.open_volume == 0 {
                    continue;
                }
                let spec = match ssp.get_symbol(position.symbol) {
                    Some(s) => s,
                    None => continue,
                };
                if !spec.symbol_type.is_futures_contract() {
                    continue;
                }
                let mark_price = match last_price_cache.get(&position.symbol) {
                    Some(r) => r.mark_price,
                    None => continue,
                };
                if position.margin_mode == MarginMode::Isolated {
                    match Self::check_isolated_decision(key, position, spec, mark_price) {
                        IsolatedCheck::Liquidate(d) => decisions.push(d),
                        IsolatedCheck::Alert => fund_events.push(Self::notification_event(FundEventType::MarginAlert, uid, position, spec, profile, ssp, last_price_cache)),
                        IsolatedCheck::Healthy => {}
                    }
                } else {
                    cross_by_currency.entry(spec.quote_currency).or_default().push(key);
                }
            }
            Self::check_cross_decisions(uid, profile, &cross_by_currency, ssp, last_price_cache, &mut decisions, fund_events);
            decisions
        };

        for d in decisions {
            let profile = match ups.get_mut(uid) {
                Some(p) => p,
                None => return,
            };
            if let Some(pos) = profile.positions.get(&d.position_key) {
                if let Some(spec) = ssp.get_symbol(pos.symbol) {
                    fund_events.push(Self::notification_event(FundEventType::LiquidationAlert, uid, pos, spec, profile, ssp, last_price_cache));
                }
            }
            self.start_liquidation_flow(profile, d, ts);
        }
    }

    fn check_isolated_decision(
        position_key: i32,
        position: &SymbolPositionRecord,
        spec: &CoreSymbolSpecification,
        mark_price: i64,
    ) -> IsolatedCheck {
        let profit = position.estimate_unrealized_profit(mark_price);
        let equity = position.open_init_margin_sum + profit + position.extra_margin;
        let maintenance_margin = position.calculate_maintenance_margin(spec, mark_price);
        if equity >= maintenance_margin {
            if maintenance_margin > 0 && equity < maintenance_margin.saturating_mul(12) / 10 {
                return IsolatedCheck::Alert;
            }
            return IsolatedCheck::Healthy;
        }
        let bankruptcy_price = position.calculate_bankruptcy_price(spec, |_| 0);
        let size_to_liquidate = position.open_volume.min(Self::size_to_liquidate_for(position, maintenance_margin, mark_price));
        if size_to_liquidate <= 0 {
            return IsolatedCheck::Healthy;
        }
        IsolatedCheck::Liquidate(LiquidationDecision { position_key, bankruptcy_price, size: size_to_liquidate })
    }

    #[allow(clippy::too_many_arguments)]
    fn check_cross_decisions(
        uid: i64,
        profile: &UserProfile,
        cross_by_currency: &BTreeMap<i32, Vec<i32>>,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        decisions: &mut Vec<LiquidationDecision>,
        fund_events: &mut Vec<FundEvent>,
    ) {
        if cross_by_currency.is_empty() {
            return;
        }
        let alloc = profile.cross_margin_base_allocation(
            |s| ssp.get_symbol(s),
            |c| ssp.get_currency(c),
            |s| last_price_cache.get(&s).map(|r| r.mark_price),
        );

        for (&currency, keys) in cross_by_currency.iter() {
            let currency_spec = match ssp.get_currency(currency) {
                Some(c) => c,
                None => continue,
            };
            let mut total_profit: i64 = 0;
            let mut total_maintenance: i64 = 0;
            let mut risk_pairs: Vec<(i64, i32)> = Vec::new();
            for &key in keys {
                let position = &profile.positions[&key];
                let spec = match ssp.get_symbol(position.symbol) {
                    Some(s) => s,
                    None => continue,
                };
                let mark_price = match last_price_cache.get(&position.symbol) {
                    Some(r) => r.mark_price,
                    None => continue,
                };
                let raw_maintenance = position.calculate_maintenance_margin(spec, mark_price);
                if raw_maintenance == 0 {

                    continue;
                }
                let profit = size_price_to_currency_scale(
                    position.estimate_pnl(mark_price),
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    currency_spec.currency_scale_k,
                );
                let maintenance = size_price_to_currency_scale(
                    raw_maintenance,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    currency_spec.currency_scale_k,
                );
                total_profit += profit;
                total_maintenance += maintenance;
                if maintenance != 0 {

                    let risk = mul_exact(profit - maintenance, 100) / maintenance;
                    risk_pairs.push((risk, key));
                }
            }
            let equity = total_profit
                + profile.calculate_cross_available(currency, currency_spec, |s| ssp.get_symbol(s));
            let warning_threshold = mul_exact(total_maintenance, 6) / 5;
            if equity >= warning_threshold {
                continue;
            }
            risk_pairs.sort_by_key(|p| p.0);
            if equity >= total_maintenance {
                if let Some((position, spec)) = risk_pairs
                    .first()
                    .and_then(|&(_, key)| profile.positions.get(&key))
                    .and_then(|p| ssp.get_symbol(p.symbol).map(|s| (p, s)))
                {
                    fund_events.push(Self::notification_event(
                        FundEventType::MarginAlert, uid, position, spec, profile, ssp, last_price_cache,
                    ));
                }
                continue;
            }

            Self::force_cross_decisions(
                profile,
                &risk_pairs,
                total_maintenance - equity,
                &alloc,
                ssp,
                last_price_cache,
                decisions,
            );
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn force_cross_decisions(
        profile: &UserProfile,
        risk_pairs: &[(i64, i32)],
        deficit: i64,
        alloc: &BTreeMap<i32, i64>,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        decisions: &mut Vec<LiquidationDecision>,
    ) {
        let mut margin_released: i64 = 0;
        for &(_risk, key) in risk_pairs {
            if margin_released >= deficit {
                break;
            }
            let position = &profile.positions[&key];
            let spec = match ssp.get_symbol(position.symbol) {
                Some(s) => s,
                None => continue,
            };
            let mark_price = match last_price_cache.get(&position.symbol) {
                Some(r) => r.mark_price,
                None => continue,
            };

            let bankruptcy_price = position.calculate_bankruptcy_price(spec, |_| alloc.get(&key).copied().unwrap_or(0));
            let maintenance_margin = position.calculate_maintenance_margin(spec, mark_price);
            let size_to_liquidate =
                position.open_volume.min(Self::size_to_liquidate_for(position, maintenance_margin, mark_price));
            if size_to_liquidate > 0 {
                margin_released += Self::deficit_after_for(position, spec, size_to_liquidate, mark_price);
                decisions.push(LiquidationDecision { position_key: key, bankruptcy_price, size: size_to_liquidate });
            }
        }
    }

    fn start_liquidation_flow(&mut self, profile: &mut UserProfile, d: LiquidationDecision, ts: i64) {
        let uid = profile.uid;
        let position = match profile.positions.get_mut(&d.position_key) {
            Some(p) => p,
            None => return,
        };
        if position.liquidation_flow.is_some() {
            return;
        }
        let order_id =
            LiquidationService::generate_liquidation_order_id(uid, position.symbol, position.direction, ts);
        position.liquidation_flow = Some(LiquidationFlow::new(d.bankruptcy_price, d.size, order_id));
        let force_cmd = Self::build_force_cmd(uid, position.symbol, position.direction, order_id, d.bankruptcy_price, d.size, ts);
        self.command_submitter.submit(force_cmd);
    }

    pub fn advance_liquidation(&mut self, cmd: &OrderCommand, pos: &mut SymbolPositionRecord) {
        if !self.is_running {
            return;
        }
        match pos.liquidation_flow {
            None => {
                if cmd.command != OrderCommandType::ForceLiquidation {
                    return;
                }
                pos.liquidation_flow = Some(LiquidationFlow::new(cmd.price, cmd.size, cmd.order_id));
            }
            Some(flow) => {
                let expected = match cmd.command {
                    OrderCommandType::ForceLiquidation => Some(LiquidationState::Liquidating),
                    OrderCommandType::IfTakeover => Some(LiquidationState::WaitIfExecution),
                    OrderCommandType::AutoDeleveraging => Some(LiquidationState::WaitAdlExecution),
                    _ => None,
                };
                if Some(flow.state) != expected {
                    return;
                }
            }
        }
        match cmd.command {
            OrderCommandType::ForceLiquidation => self.on_force_applied(cmd, pos),
            OrderCommandType::IfTakeover => self.on_if_takeover_applied(cmd, pos),
            OrderCommandType::AutoDeleveraging => pos.liquidation_flow = None,
            _ => {}
        }
    }

    fn on_force_applied(&mut self, cmd: &OrderCommand, pos: &mut SymbolPositionRecord) {
        let rejected = matches!(&cmd.matcher_event, Some(ev) if ev.event_type == MatcherEventType::Reject);
        if !rejected {
            pos.liquidation_flow = None;
            return;
        }
        let remaining = cmd.matcher_event.as_ref().map(|e| e.size).unwrap_or(0);
        if let Some(flow) = pos.liquidation_flow.as_mut() {
            flow.size = remaining;
            flow.state = LiquidationState::WaitIfExecution;
        }
        if let Some(flow) = pos.liquidation_flow {
            let if_cmd = Self::build_if_cmd(pos.uid, pos.symbol, pos.direction, &flow, cmd.timestamp);
            self.command_submitter.submit(if_cmd);
        }
    }

    fn on_if_takeover_applied(&mut self, cmd: &OrderCommand, pos: &mut SymbolPositionRecord) {
        let rejected = matches!(&cmd.matcher_event, Some(ev) if ev.event_type == MatcherEventType::Reject);
        if !rejected {
            pos.liquidation_flow = None;
            return;
        }
        if let Some(flow) = pos.liquidation_flow.as_mut() {
            flow.state = LiquidationState::WaitAdlExecution;
        }
        if let Some(flow) = pos.liquidation_flow {
            let adl_cmd = Self::build_adl_cmd(pos.uid, pos.symbol, pos.direction, &flow, cmd.timestamp);
            self.command_submitter.submit(adl_cmd);
        }
    }

    pub fn covered_by_scan_slice(cmd: &OrderCommand, uid: i64) -> bool {
        crate::core::processors::liquidation::scheduler::covered_by_scan_slice(cmd, uid)
    }

    fn notification_event(
        event_type: FundEventType,
        uid: i64,
        position: &SymbolPositionRecord,
        spec: &CoreSymbolSpecification,
        up: &UserProfile,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
    ) -> FundEvent {
        let (upnl, liq, mr, mmsk) = RiskEngine::futures_estimates(last_price_cache, up, position, spec, ssp);
        let mark = last_price_cache.get(&position.symbol).map(|r| r.mark_price).unwrap_or(0);
        FundEvent {
            event_type,
            uid,
            symbol: position.symbol,
            currency: position.currency,
            base_scale_k: spec.base_scale_k,
            quote_scale_k: spec.quote_scale_k,
            direction: position.direction,
            open_volume: position.open_volume,
            open_init_margin_sum: position.open_init_margin_sum,
            open_price_sum: position.open_price_sum,
            leverage: position.leverage,
            margin_mode: position.margin_mode,
            extra_margin: position.extra_margin,
            unrealized_profit: upnl,
            liquidation_price: liq,
            margin_ratio_scale_k: mr,
            maintenance_margin_scale_k: mmsk,
            mark_price: mark,
            ..Default::default()
        }
    }

    fn size_to_liquidate_for(position: &SymbolPositionRecord, maintenance_margin: i64, mark_price: i64) -> i64 {
        let equity = position.open_init_margin_sum + position.estimate_unrealized_profit(mark_price);
        calculate_size_to_liquidate(
            equity,
            maintenance_margin,
            position.open_init_margin_sum,
            position.open_volume,
            position.open_price_sum,
            mark_price,
            position.direction.multiplier() as i64,
        )
    }

    fn deficit_after_for(
        position: &SymbolPositionRecord,
        spec: &CoreSymbolSpecification,
        size: i64,
        mark_price: i64,
    ) -> i64 {
        let notional_now = mul_exact(position.open_volume, mark_price);
        let notional_after = mul_exact(position.open_volume - size, mark_price);
        calculate_deficit_after_liquidate(
            size,
            position.direction.multiplier() as i64,
            position.open_init_margin_sum,
            position.open_volume,
            position.open_price_sum,
            mark_price,
            spec.calculate_maintenance_margin(notional_now),
            spec.calculate_maintenance_margin(notional_after),
        )
    }

    fn build_force_cmd(uid: i64, symbol: i32, direction: PositionDirection, order_id: i64, price: i64, size: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::ForceLiquidation,
            order_id,
            uid,
            symbol,
            price,
            size,
            action: Some(if direction == PositionDirection::Long { OrderAction::Ask } else { OrderAction::Bid }),
            order_type: Some(OrderType::Ioc),
            timestamp: ts,
            ..Default::default()
        }
    }

    fn build_if_cmd(uid: i64, symbol: i32, direction: PositionDirection, flow: &LiquidationFlow, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::IfTakeover,
            order_id: LiquidationService::generate_if_order_id(flow.original_order_id),
            uid,
            symbol,
            price: flow.bankruptcy_price,
            size: flow.size,
            action: Some(if direction == PositionDirection::Long { OrderAction::Bid } else { OrderAction::Ask }),
            timestamp: ts,
            ..Default::default()
        }
    }

    fn build_adl_cmd(uid: i64, symbol: i32, direction: PositionDirection, flow: &LiquidationFlow, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::AutoDeleveraging,
            order_id: LiquidationService::generate_adl_order_id(flow.original_order_id),
            uid,
            symbol,
            price: flow.bankruptcy_price,
            size: flow.size,
            action: Some(if direction == PositionDirection::Long { OrderAction::Bid } else { OrderAction::Ask }),
            timestamp: ts,
            ..Default::default()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::matcher_trade_event::MatcherTradeEvent;
    use crate::core::common::symbol_type::SymbolType;
    use std::cell::RefCell;
    use std::rc::Rc;

    const FUT_SYMBOL: i32 = 200;
    const FUT_QUOTE: i32 = 2;
    const FUT_BASE: i32 = 1;
    const UID: i64 = 1;

    fn attach_collector(engine: &mut LiquidationEngine) -> Rc<RefCell<Vec<OrderCommand>>> {
        use crate::core::processors::liquidation::command_submitter::VecCommandSink;
        let collected = Rc::new(RefCell::new(Vec::new()));
        engine.set_command_submitter(Rc::new(RefCell::new(VecCommandSink(collected.clone()))));
        collected
    }

    fn futures_spec() -> CoreSymbolSpecification {
        let mut mm = BTreeMap::new();
        mm.insert(i64::MAX, 500);
        CoreSymbolSpecification {
            symbol_id: FUT_SYMBOL,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: FUT_BASE,
            quote_currency: FUT_QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            maintenance_margin: mm,
            maintenance_margin_scale_k: 10_000,
            ..Default::default()
        }
    }

    fn seeded() -> (LiquidationEngine, UserProfileService, SymbolSpecificationProvider, BTreeMap<i32, LastPriceCacheRecord>) {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1, ..Default::default() });
        ssp.add_symbol(futures_spec());
        let mut ups = UserProfileService::new();
        ups.add_empty_user_profile(UID);
        let last_price_cache = BTreeMap::new();
        (engine, ups, ssp, last_price_cache)
    }

    fn insert_long(ups: &mut UserProfileService, uid: i64) {
        let pos = SymbolPositionRecord {
            direction: PositionDirection::Long,
            open_volume: 10,
            open_price_sum: 1_000,
            open_init_margin_sum: 100,
            ..SymbolPositionRecord::new(uid, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
        };
        ups.get_mut(uid).unwrap().positions.insert(FUT_SYMBOL, pos);
    }

    fn markprice_cmd(symbol: i32, ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol, timestamp: ts, ..Default::default() }
    }

    fn mte(event_type: MatcherEventType, size: i64) -> Box<MatcherTradeEvent> {
        Box::new(MatcherTradeEvent {
            event_type,
            active_order_completed: false,
            maker_order_id: 0,
            maker_order_completed: false,
            price: 0,
            size,
            bid_gt_ask: false,
            bidder_hold_price: 0,
            matched_order_uid: 0,
            matched_order_command_type: OrderCommandType::PlaceOrder,
            filled: 0,
            filled_notional: 0,
            matched_order_size: 0,
            matched_order_price: 0,
            matched_order_type: crate::core::common::order_type::OrderType::Gtc,
            matched_order_timestamp: 0,
            matched_user_cookie: 0,
            matched_order_filled: 0,
            matched_order_filled_notional: 0,
            next: None,
        })
    }

    #[test]
    fn covered_by_scan_slice_non_scan_always_covered() {
        let cmd = markprice_cmd(FUT_SYMBOL, 0);
        assert!(LiquidationEngine::covered_by_scan_slice(&cmd, 12345));
    }

    #[test]
    fn covered_by_scan_slice_matches_and_misses() {
        let cmd = OrderCommand { command: OrderCommandType::LiquidationScan, symbol: -1, uid: 3, size: 10, ..Default::default() };
        assert!(LiquidationEngine::covered_by_scan_slice(&cmd, 13), "13 mod 10 == 3 -> in slice");
        assert!(!LiquidationEngine::covered_by_scan_slice(&cmd, 14), "14 mod 10 == 4 != 3 -> out of slice");
        let full = OrderCommand { command: OrderCommandType::LiquidationScan, symbol: -1, uid: 0, size: 0, ..Default::default() };
        assert!(LiquidationEngine::covered_by_scan_slice(&full, 999));
    }

    #[test]
    fn on_position_opened_registers_uid() {
        let (mut engine, _ups, _ssp, _lpc) = seeded();
        engine.on_position_opened(UID, FUT_SYMBOL);
        assert!(engine.symbol_to_users.get(&FUT_SYMBOL).unwrap().contains(&UID));
    }

    #[test]
    fn on_position_closed_removes_uid_when_no_other_position() {
        let (mut engine, mut ups, _ssp, _lpc) = seeded();
        engine.on_position_opened(UID, FUT_SYMBOL);
        insert_long(&mut ups, UID);
        engine.on_position_closed(ups.get(UID).unwrap(), FUT_SYMBOL, FUT_SYMBOL);
        assert!(engine.symbol_to_users.get(&FUT_SYMBOL).is_none(), "no other position -> uid removed + empty set cleaned up");
    }

    #[test]
    fn on_position_closed_hedge_keeps_uid_when_other_side_exists() {
        let (mut engine, mut ups, _ssp, _lpc) = seeded();
        engine.on_position_opened(UID, FUT_SYMBOL);
        insert_long(&mut ups, UID);
        let short = SymbolPositionRecord {
            direction: PositionDirection::Short,
            open_volume: 5,
            ..SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
        };
        ups.get_mut(UID).unwrap().positions.insert(-FUT_SYMBOL, short);
        engine.on_position_closed(ups.get(UID).unwrap(), FUT_SYMBOL, FUT_SYMBOL);
        assert!(
            engine.symbol_to_users.get(&FUT_SYMBOL).unwrap().contains(&UID),
            "an opposite-side position on the same symbol still exists -> the holder must not be mistakenly removed (HEDGE safety)"
        );
    }

    #[test]
    fn check_positions_leader_gate_off_is_noop() {
        let (mut engine, mut ups, ssp, mut lpc) = seeded();
        engine.is_running = false;
        let out = attach_collector(&mut engine);
        engine.on_position_opened(UID, FUT_SYMBOL);
        insert_long(&mut ups, UID);
        lpc.insert(FUT_SYMBOL, LastPriceCacheRecord::with_mark(50));
        let cmd = markprice_cmd(FUT_SYMBOL, 1_000);
        engine.check_positions(&cmd, &mut ups, &ssp, &lpc, &LoanService::new(), &mut Vec::new());
        assert!(out.borrow().is_empty(), "a follower neither detects nor submits");
        assert!(ups.get(UID).unwrap().positions[&FUT_SYMBOL].liquidation_flow.is_none());
    }

    #[test]
    fn check_positions_targeted_isolated_underwater_queues_force_and_sets_flow() {
        let (mut engine, mut ups, ssp, mut lpc) = seeded();
        let out = attach_collector(&mut engine);
        engine.on_position_opened(UID, FUT_SYMBOL);
        insert_long(&mut ups, UID);
        lpc.insert(FUT_SYMBOL, LastPriceCacheRecord::with_mark(50));
        let cmd = markprice_cmd(FUT_SYMBOL, 5_000);

        engine.check_positions(&cmd, &mut ups, &ssp, &lpc, &LoanService::new(), &mut Vec::new());

        assert_eq!(out.borrow().len(), 1, "one FORCE is triggered");
        let force = out.borrow()[0].clone();
        assert_eq!(force.command, OrderCommandType::ForceLiquidation);
        assert_eq!(force.uid, UID);
        assert_eq!(force.symbol, FUT_SYMBOL);
        assert_eq!(force.action, Some(OrderAction::Ask), "LONG liquidation -> ASK (opposite of the closing direction)");
        assert_eq!(force.order_type, Some(OrderType::Ioc));
        assert_eq!(force.size, 10, "size_to_liquidate = min(open_volume, calc) = 10");
        let flow = ups.get(UID).unwrap().positions[&FUT_SYMBOL].liquidation_flow.expect("flow set");
        assert_eq!(flow.state, LiquidationState::Liquidating);
        assert_eq!(flow.size, 10);
        assert_eq!(force.price, flow.bankruptcy_price, "FORCE limit price = bankruptcy price");
        assert_eq!(force.order_id, flow.original_order_id);
    }

    #[test]
    fn check_positions_healthy_position_no_force() {
        let (mut engine, mut ups, ssp, mut lpc) = seeded();
        let out = attach_collector(&mut engine);
        engine.on_position_opened(UID, FUT_SYMBOL);
        insert_long(&mut ups, UID);
        lpc.insert(FUT_SYMBOL, LastPriceCacheRecord::with_mark(100));
        let cmd = markprice_cmd(FUT_SYMBOL, 1_000);

        engine.check_positions(&cmd, &mut ups, &ssp, &lpc, &LoanService::new(), &mut Vec::new());

        assert!(out.borrow().is_empty(), "a healthy position does not trigger anything");
        assert!(ups.get(UID).unwrap().positions[&FUT_SYMBOL].liquidation_flow.is_none());
    }

    #[test]
    fn check_positions_idempotent_second_scan_no_double_submit() {
        let (mut engine, mut ups, ssp, mut lpc) = seeded();
        let out = attach_collector(&mut engine);
        engine.on_position_opened(UID, FUT_SYMBOL);
        insert_long(&mut ups, UID);
        lpc.insert(FUT_SYMBOL, LastPriceCacheRecord::with_mark(50));
        let cmd = markprice_cmd(FUT_SYMBOL, 5_000);

        engine.check_positions(&cmd, &mut ups, &ssp, &lpc, &LoanService::new(), &mut Vec::new());
        engine.check_positions(&cmd, &mut ups, &ssp, &lpc, &LoanService::new(), &mut Vec::new());

        assert_eq!(out.borrow().len(), 1, "flow already in progress -> the second scan does not resubmit (idempotency gate)");
    }

    #[test]
    fn check_positions_scan_slice_filters_users() {
        let (mut engine, mut ups, ssp, mut lpc) = seeded();
        let out = attach_collector(&mut engine);
        ups.add_empty_user_profile(2);
        insert_long(&mut ups, 1);
        insert_long(&mut ups, 2);
        lpc.insert(FUT_SYMBOL, LastPriceCacheRecord::with_mark(50));
        let scan = OrderCommand { command: OrderCommandType::LiquidationScan, symbol: -1, uid: 1, size: 2, timestamp: 5_000, ..Default::default() };

        engine.check_positions(&scan, &mut ups, &ssp, &lpc, &LoanService::new(), &mut Vec::new());

        assert_eq!(out.borrow().len(), 1, "only uid=1 is within the slice");
        assert_eq!(out.borrow()[0].uid, 1);
        assert!(ups.get(2).unwrap().positions[&FUT_SYMBOL].liquidation_flow.is_none(), "uid=2 is outside the slice and was not touched");
    }

    #[test]
    fn check_cross_scaled_maintenance_truncates_to_zero_no_panic_no_force() {
        const SYMBOL: i32 = 5001;
        const BASE_CCY: i32 = 10;
        const QUOTE_CCY: i32 = 20;
        const U: i64 = 42;

        let mut mm = BTreeMap::new();
        mm.insert(10_000_000i64, 5i64);
        let spec = CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_CCY,
            quote_currency: QUOTE_CCY,
            base_scale_k: 100,
            quote_scale_k: 100,
            init_margin: 1,
            init_margin_scale_k: 1,
            maintenance_margin: mm,
            maintenance_margin_scale_k: 1000,
            ..Default::default()
        };

        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_currency(CoreCurrencySpecification { currency: BASE_CCY, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: QUOTE_CCY, currency_scale_k: 1, ..Default::default() });
        ssp.add_symbol(spec);

        let mut ups = UserProfileService::new();
        ups.add_empty_user_profile(U);
        let pos = SymbolPositionRecord {
            direction: PositionDirection::Long,
            open_volume: 1,
            open_price_sum: 1_000_000,
            open_init_margin_sum: 1,
            ..SymbolPositionRecord::new(U, SYMBOL, QUOTE_CCY, MarginMode::Cross, 1)
        };
        ups.get_mut(U).unwrap().positions.insert(SYMBOL, pos);
        engine.on_position_opened(U, SYMBOL);

        let mut lpc = BTreeMap::new();
        lpc.insert(SYMBOL, LastPriceCacheRecord::with_mark(1_000_000i64));
        let cmd = markprice_cmd(SYMBOL, 1_000);

        engine.check_positions(&cmd, &mut ups, &ssp, &lpc, &LoanService::new(), &mut Vec::new());
        assert!(out.borrow().is_empty(), "a healthy CROSS account whose scaled maintenance truncates to zero must not be mistakenly liquidated (and must not divide by zero)");
        assert!(ups.get(U).unwrap().positions[&SYMBOL].liquidation_flow.is_none());
    }

    fn pos_with_flow(state: LiquidationState) -> SymbolPositionRecord {
        let mut pos = SymbolPositionRecord {
            direction: PositionDirection::Long,
            open_volume: 10,
            open_price_sum: 1_000,
            open_init_margin_sum: 100,
            ..SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
        };
        let mut flow = LiquidationFlow::new(45, 10, 777);
        flow.state = state;
        pos.liquidation_flow = Some(flow);
        pos
    }

    fn force_apply_cmd(event: Option<Box<MatcherTradeEvent>>) -> OrderCommand {
        OrderCommand { command: OrderCommandType::ForceLiquidation, uid: UID, symbol: FUT_SYMBOL, matcher_event: event, timestamp: 6_000, ..Default::default() }
    }

    #[test]
    fn advance_force_non_reject_closes_flow() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut pos = pos_with_flow(LiquidationState::Liquidating);
        let cmd = force_apply_cmd(Some(mte(MatcherEventType::Trade, 10)));
        engine.advance_liquidation(&cmd, &mut pos);
        assert!(pos.liquidation_flow.is_none(), "FORCE fully filled -> flow closed");
        assert!(out.borrow().is_empty());
    }

    #[test]
    fn advance_force_reject_transitions_to_wait_if_and_queues_if() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut pos = pos_with_flow(LiquidationState::Liquidating);
        let cmd = force_apply_cmd(Some(mte(MatcherEventType::Reject, 7)));
        engine.advance_liquidation(&cmd, &mut pos);
        let flow = pos.liquidation_flow.expect("flow is retained");
        assert_eq!(flow.state, LiquidationState::WaitIfExecution);
        assert_eq!(flow.size, 7, "flow.size is updated to the REJECT remaining size");
        assert_eq!(out.borrow().len(), 1);
        let ifc = out.borrow()[0].clone();
        assert_eq!(ifc.command, OrderCommandType::IfTakeover);
        assert_eq!(ifc.size, 7);
        assert_eq!(ifc.action, Some(OrderAction::Bid), "IF takeover of a LONG -> BID (perspective flip)");
        assert_eq!(ifc.order_id, LiquidationService::generate_if_order_id(777));
    }

    #[test]
    fn advance_if_reject_transitions_to_wait_adl_and_queues_adl() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut pos = pos_with_flow(LiquidationState::WaitIfExecution);
        let cmd = OrderCommand { command: OrderCommandType::IfTakeover, uid: UID, symbol: FUT_SYMBOL, matcher_event: Some(mte(MatcherEventType::Reject, 7)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        let flow = pos.liquidation_flow.expect("flow is retained");
        assert_eq!(flow.state, LiquidationState::WaitAdlExecution);
        assert_eq!(out.borrow().len(), 1);
        let adl = out.borrow()[0].clone();
        assert_eq!(adl.command, OrderCommandType::AutoDeleveraging);
        assert_eq!(adl.action, Some(OrderAction::Bid));
        assert_eq!(adl.order_id, LiquidationService::generate_adl_order_id(777));
    }

    #[test]
    fn advance_if_non_reject_closes_flow() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut pos = pos_with_flow(LiquidationState::WaitIfExecution);
        let cmd = OrderCommand { command: OrderCommandType::IfTakeover, uid: UID, symbol: FUT_SYMBOL, matcher_event: Some(mte(MatcherEventType::Trade, 7)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        assert!(pos.liquidation_flow.is_none(), "IF takeover succeeded -> flow closed");
        assert!(out.borrow().is_empty());
    }

    #[test]
    fn advance_adl_is_terminal() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut pos = pos_with_flow(LiquidationState::WaitAdlExecution);
        let cmd = OrderCommand { command: OrderCommandType::AutoDeleveraging, uid: UID, symbol: FUT_SYMBOL, matcher_event: Some(mte(MatcherEventType::Trade, 7)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        assert!(pos.liquidation_flow.is_none(), "ADL is always a terminal state");
        assert!(out.borrow().is_empty());
    }

    #[test]
    fn advance_null_flow_non_force_skips() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
        let cmd = OrderCommand { command: OrderCommandType::IfTakeover, uid: UID, symbol: FUT_SYMBOL, matcher_event: Some(mte(MatcherEventType::Reject, 7)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        assert!(pos.liquidation_flow.is_none());
        assert!(out.borrow().is_empty());
    }

    #[test]
    fn advance_null_flow_force_recovers_new_flow() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut pos = SymbolPositionRecord {
            direction: PositionDirection::Long,
            open_volume: 10,
            ..SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
        };
        let cmd = OrderCommand { command: OrderCommandType::ForceLiquidation, uid: UID, symbol: FUT_SYMBOL, price: 45, size: 8, order_id: 555, matcher_event: Some(mte(MatcherEventType::Reject, 8)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        let flow = pos.liquidation_flow.expect("recovery created a flow");
        assert_eq!(flow.state, LiquidationState::WaitIfExecution);
        assert_eq!(flow.original_order_id, 555, "the recovery flow uses cmd.order_id as the root orderId");
        assert_eq!(out.borrow().len(), 1);
        assert_eq!(out.borrow()[0].command, OrderCommandType::IfTakeover);
    }

    #[test]
    fn advance_out_of_order_command_skips() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut pos = pos_with_flow(LiquidationState::Liquidating);
        let cmd = OrderCommand { command: OrderCommandType::IfTakeover, uid: UID, symbol: FUT_SYMBOL, matcher_event: Some(mte(MatcherEventType::Reject, 7)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        let flow = pos.liquidation_flow.expect("flow is unchanged");
        assert_eq!(flow.state, LiquidationState::Liquidating, "an out-of-order command is skipped, state is unchanged");
        assert!(out.borrow().is_empty());
    }

    #[test]
    fn advance_leader_gate_off_is_noop() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = false;
        let mut pos = pos_with_flow(LiquidationState::Liquidating);
        let cmd = force_apply_cmd(Some(mte(MatcherEventType::Trade, 10)));
        engine.advance_liquidation(&cmd, &mut pos);
        assert_eq!(pos.liquidation_flow.map(|f| f.state), Some(LiquidationState::Liquidating), "a follower does not advance the flow");
    }
}
