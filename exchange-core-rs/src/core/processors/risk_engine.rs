use std::collections::BTreeMap;

use crate::core::common::user_profile::UserProfile;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::fund_event::{FundEvent, FundEventType};
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::matcher_event_type::MatcherEventType;
use crate::core::common::order_action::OrderAction;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::order_type::OrderType;
use crate::core::common::position_direction::PositionDirection;
use crate::core::common::position_mode::PositionMode;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::common::symbol_type::SymbolType;
use crate::core::common::matcher_trade_event::MatcherTradeEvent;
use crate::core::common::last_price_cache_record::LastPriceCacheRecord;
use crate::core::common::batch_add_loan_command::BatchAddLoanCommand;
use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::processors::twostep_command_processor::{TwoStepCommandProcessor, TwoStepContext};
use crate::core::processors::adl_command_processor::AdlCommandProcessor;
use crate::core::processors::fundingfee_command_processor::FundingFeeCommandProcessor;
use crate::core::processors::if_command_processor::IfCommandProcessor;
use crate::core::processors::internaltransfer_command_processor::InternalTransferCommandProcessor;
use crate::core::processors::loanratepricing_command_processor::LoanRatePricingCommandProcessor;
use crate::core::processors::binary_commands_processor::BinaryCommandsProcessor;
use crate::core::processors::liquidation::liquidation_engine::LiquidationEngine;
use crate::core::processors::liquidation::liquidation_service::LiquidationService;
use crate::core::processors::loan::loan_command_dispatcher::LoanCommandDispatcher;
use crate::core::processors::risk_engine_command_dispatcher::RiskEngineCommandDispatcher;
use crate::core::processors::loan::loan_service::LoanService;
use crate::core::utils::core_arithmetic_utils as arithmetic;
use crate::core::utils::core_arithmetic_utils::{mul_exact, sub_exact};

#[derive(Debug, Default)]
pub struct RiskEngine {
    pub adjustments: BTreeMap<i32, i64>,
    pub fees: BTreeMap<i32, i64>,
    pub suspends: BTreeMap<i32, i64>,
    pub last_price_cache: BTreeMap<i32, LastPriceCacheRecord>,
    pub cfg_margin_trading_enabled: bool,
    pub loan_service: LoanService,
    pub liquidation_service: LiquidationService,
    pub liquidation_engine: LiquidationEngine,
    pub(crate) binary_cmd: BinaryCommandsProcessor,
}

impl RiskEngine {

    pub fn new() -> Self {
        RiskEngine {
            adjustments: BTreeMap::new(),
            fees: BTreeMap::new(),
            suspends: BTreeMap::new(),
            last_price_cache: BTreeMap::new(),
            cfg_margin_trading_enabled: true,
            loan_service: LoanService::new(),
            liquidation_service: LiquidationService::new(),
            liquidation_engine: LiquidationEngine::new(),
            binary_cmd: BinaryCommandsProcessor::new(),
        }
    }

    pub fn reset(&mut self) {
        self.adjustments.clear();
        self.fees.clear();
        self.suspends.clear();
        self.last_price_cache.clear();
        self.loan_service = LoanService::new();
        self.liquidation_service = LiquidationService::new();
    }

    pub fn pre_process_command(
        &mut self,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) {
        log::trace!("R1 pre_process_command: cmd={:?} uid={} symbol={}", cmd.command, cmd.uid, cmd.symbol);
        if cmd.command.is_loan() {
            cmd.result_code = Some(LoanCommandDispatcher::dispatch(self, cmd, ups, ssp));
            return;
        }
        if cmd.command.is_non_trading() {
            cmd.result_code = Some(RiskEngineCommandDispatcher::dispatch(self, cmd, ups, ssp));
            return;
        }

        if cmd.command == OrderCommandType::PlaceOrder {
            let rc = self.place_order_risk_check(cmd, ups, ssp);
            cmd.result_code = Some(rc);
            if rc == CommandResultCode::ValidForMatchingEngine {
                if let Some(spec) = ssp.get_symbol(cmd.symbol) {
                    let (oid, uid) = (cmd.order_id, cmd.uid);
                    if spec.symbol_type == SymbolType::CurrencyExchangePair {
                        let cur = if cmd.action == Some(OrderAction::Bid) { spec.quote_currency } else { spec.base_currency };
                        Self::push_spot_balance_event(cmd, ups, ssp, FundEventType::Locked, oid, uid, cur, spec.symbol_id);
                    } else if let Some(action) = cmd.action {
                        if let Some(up) = ups.get(uid) {
                            let key = up.create_positions_key(spec.symbol_id, action, cmd.command);
                            if let Some(pos) = up.positions.get(&key) {
                                Self::push_futures_event(&mut cmd.fund_events, &self.last_price_cache, FundEventType::LockPending, oid, pos, spec, up, ssp);
                            }
                        }
                    }
                }
            }
        } else if cmd.command == OrderCommandType::ClosePosition {
            cmd.result_code = Some(self.close_position_risk_check(cmd, ups, ssp));
        } else if cmd.command == OrderCommandType::SettleFundingfees {
            let mut ctx = TwoStepContext::new(self, ups, ssp);
            cmd.result_code = Some(FundingFeeCommandProcessor.collect(&mut ctx, cmd));
        } else if cmd.command == OrderCommandType::ForceLiquidation {
            cmd.result_code = Some(Self::normalize_cmd_position_size(cmd, ups));
        } else if cmd.command == OrderCommandType::IfTakeover {
            Self::normalize_cmd_position_size(cmd, ups);
            let mut ctx = TwoStepContext::new(self, ups, ssp);
            cmd.result_code = Some(IfCommandProcessor.collect(&mut ctx, cmd));
        } else if cmd.command == OrderCommandType::AutoDeleveraging {
            Self::normalize_cmd_position_size(cmd, ups);
            let mut ctx = TwoStepContext::new(self, ups, ssp);
            cmd.result_code = Some(AdlCommandProcessor.collect(&mut ctx, cmd));
        } else if cmd.command == OrderCommandType::LiquidationScan {
            let mut _alerts = Vec::new();
            self.liquidation_engine.check_positions(cmd, ups, ssp, &self.last_price_cache, &self.loan_service, &mut _alerts);
            cmd.fund_events.append(&mut _alerts);
            cmd.result_code = Some(CommandResultCode::Success);
        }
    }

    pub fn place_order_risk_check(
        &mut self,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let user_profile = match ups.get_mut(cmd.uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };
        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s) => s,
            None => return CommandResultCode::InvalidSymbol,
        };
        self.place_order(cmd, user_profile, spec, ssp)
    }

    pub fn close_position_risk_check(
        &mut self,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let user_profile = match ups.get_mut(cmd.uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };
        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s) => s,
            None => return CommandResultCode::InvalidSymbol,
        };
        if !spec.symbol_type.is_futures_contract() {
            return CommandResultCode::UnsupportedSymbolType;
        }
        if !self.cfg_margin_trading_enabled {
            return CommandResultCode::RiskMarginTradingDisabled;
        }

        let action = cmd.action.expect("CLOSE_POSITION requires action");
        let position_key = user_profile.create_positions_key(spec.symbol_id, action, cmd.command);
        let position = match user_profile.positions.get_mut(&position_key) {
            Some(p) => p,
            None => return CommandResultCode::Success,
        };
        let close_size = Self::max_closable_size(position, action, cmd.size);
        if close_size <= 0 {
            return CommandResultCode::Success;
        }
        cmd.size = close_size;
        cmd.leverage = position.leverage;
        cmd.margin_mode = position.margin_mode;

        position.pending_hold(action, cmd.size, cmd.price);

        let oid = cmd.order_id;
        if let Some(up) = ups.get(cmd.uid) {
            if let Some(pos) = up.positions.get(&position_key) {
                Self::push_futures_event(
                    &mut cmd.fund_events,
                    &self.last_price_cache,
                    FundEventType::LockPending,
                    oid,
                    pos,
                    spec,
                    up,
                    ssp,
                );
            }
        }
        CommandResultCode::ValidForMatchingEngine
    }

    pub fn handler_risk_release(
        &mut self,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) {
        log::trace!("R2 handler_risk_release: cmd={:?} uid={} symbol={} result={:?}", cmd.command, cmd.uid, cmd.symbol, cmd.result_code);
        if cmd.command == OrderCommandType::RepriceLoanRates {
            let mut ctx = TwoStepContext::new(self, ups, ssp);
            LoanRatePricingCommandProcessor.apply(&mut ctx, cmd);
            return;
        }
        if cmd.command == OrderCommandType::InternalTransfer {
            let mut ctx = TwoStepContext::new(self, ups, ssp);
            InternalTransferCommandProcessor.apply(&mut ctx, cmd);
            return;
        }
        if cmd.command == OrderCommandType::SettleFundingfees {

            let had_funding_event = cmd.funding_fee_event.is_some();
            {
                let mut ctx = TwoStepContext::new(self, ups, ssp);
                FundingFeeCommandProcessor.apply(&mut ctx, cmd);
            }
            if had_funding_event {
                let mut _alerts = Vec::new();
                self.liquidation_engine.check_positions(cmd, ups, ssp, &self.last_price_cache, &self.loan_service, &mut _alerts);
                cmd.fund_events.append(&mut _alerts);
            }
            return;
        }
        if cmd.command == OrderCommandType::IfTakeover {
            {
                let mut ctx = TwoStepContext::new(self, ups, ssp);
                IfCommandProcessor.apply(&mut ctx, cmd);
            }
            Self::advance_liquidation_for(&mut self.liquidation_engine, cmd, ups);
            return;
        }
        if cmd.command == OrderCommandType::AutoDeleveraging {
            {
                let mut ctx = TwoStepContext::new(self, ups, ssp);
                AdlCommandProcessor.apply(&mut ctx, cmd);
            }
            Self::advance_liquidation_for(&mut self.liquidation_engine, cmd, ups);
            return;
        }
        if cmd.command.is_non_trading() {
            return;
        }
        let mark_price_for_futures = self.mark_price(cmd.symbol).unwrap_or(0);
        let fees = &mut self.fees;
        let last_price_cache = &self.last_price_cache;

        let mte_owned = cmd.matcher_event.take();
        let mte = match mte_owned.as_deref() {
            Some(m) => m,
            None => return,
        };
        if mte.event_type == MatcherEventType::BinaryEvent {
            cmd.matcher_event = mte_owned;
            return;
        }
        let spec = ssp
            .get_symbol(cmd.symbol)
            .unwrap_or_else(|| panic!("symbol spec missing for symbol {}", cmd.symbol))
            .clone();
        if spec.symbol_type != SymbolType::CurrencyExchangePair {
            let taker_action = cmd.action.expect("futures matcher event requires taker action");
            let quote_currency_spec = ssp
                .get_currency(spec.quote_currency)
                .unwrap_or_else(|| panic!("currency spec missing for currency {}", spec.quote_currency))
                .clone();

            let is_force = cmd.command == OrderCommandType::ForceLiquidation;
            let (force_taker_size, force_taker_size_price) = if is_force {
                let mut taker_size: i64 = 0;
                let mut taker_size_price: i128 = 0;
                let mut cursor = Some(mte);
                while let Some(ev) = cursor {
                    if ev.event_type == MatcherEventType::Trade {
                        taker_size += ev.size;
                        taker_size_price += ev.size as i128 * ev.price as i128;
                    }
                    cursor = ev.next.as_deref();
                }
                (taker_size, taker_size_price)
            } else {
                (0i64, 0i128)
            };

            let cmd_uid = cmd.uid;
            let cmd_command = cmd.command;
            let cmd_order_id = cmd.order_id;
            let fund_events = &mut cmd.fund_events;
            Self::handle_matcher_event_margin(
                cmd_uid,
                cmd_command,
                fund_events,
                ssp,
                last_price_cache,
                mte,
                &spec,
                taker_action,
                ups,
                fees,
                &quote_currency_spec,
                mark_price_for_futures,
                is_force,
                cmd_order_id,
            );

            cmd.matcher_event = mte_owned;
            if is_force {
                if force_taker_size > 0 {
                    let avg_price = (force_taker_size_price / force_taker_size as i128) as i64;
                    let notional_fee = arithmetic::calculate_liquidation_fee(
                        force_taker_size,
                        avg_price,
                        spec.liquidation_fee,
                        spec.fee_scale_k,
                    );
                    let quote_fee = arithmetic::size_price_to_currency_scale(
                        notional_fee,
                        spec.base_scale_k,
                        spec.quote_scale_k,
                        quote_currency_spec.currency_scale_k,
                    );
                    let liq_fee_uid = cmd.uid;
                    let liq_fee_order_id = cmd.order_id;
                    let liq_fee_currency = spec.quote_currency;
                    let liq_fee_symbol = spec.symbol_id;
                    if let Some(taker) = ups.get_mut(liq_fee_uid) {
                        taker.add_to_account(spec.quote_currency, -quote_fee);
                        self.liquidation_service.credit_liquidation_fee(cmd.symbol, notional_fee);
                    }
                    if let Some(taker) = ups.get(liq_fee_uid) {
                        match taker.positions.values().find(|p| p.symbol == liq_fee_symbol && p.open_volume != 0) {
                            Some(pos) => Self::push_futures_event(
                                &mut cmd.fund_events, last_price_cache, FundEventType::LiquidationFee, liq_fee_order_id, pos, &spec, taker, ssp,
                            ),
                            None => {
                                let ev = Self::spot_snapshot_event(
                                    FundEventType::LiquidationFee, liq_fee_order_id, taker, liq_fee_currency, ssp, &quote_currency_spec, liq_fee_symbol,
                                );
                                cmd.fund_events.push(ev);
                            }
                        }
                    }
                }
                Self::advance_liquidation_for(&mut self.liquidation_engine, cmd, ups);
            }
            Self::refresh_price_record(&mut self.last_price_cache, cmd, false);
            return;
        }
        let taker_sell = matches!(cmd.action, Some(OrderAction::Ask));

        let is_loan_force_liquidate = matches!(
            cmd.command,
            OrderCommandType::LoanForceLiquidate | OrderCommandType::LoanCrossForceLiquidate
        );
        let (loan_traded_size, loan_traded_notional, loan_rejected_size) = if is_loan_force_liquidate {
            let mut traded_size: i64 = 0;
            let mut traded_notional: i128 = 0;
            let mut rejected_size: i64 = 0;
            let mut cursor = Some(mte);
            while let Some(ev) = cursor {
                match ev.event_type {
                    MatcherEventType::Trade => {
                        traded_size = traded_size
                            .checked_add(ev.size)
                            .unwrap_or_else(|| panic!("overflow: loan force-liquidate traded_size"));
                        traded_notional += ev.size as i128 * ev.price as i128;
                    }
                    MatcherEventType::Reject => {
                        rejected_size = rejected_size
                            .checked_add(ev.size)
                            .unwrap_or_else(|| panic!("overflow: loan force-liquidate rejected_size"));
                    }
                    _ => {}
                }
                cursor = ev.next.as_deref();
            }
            (traded_size, traded_notional, rejected_size)
        } else {
            (0i64, 0i128, 0i64)
        };

        let next: Option<&MatcherTradeEvent> =
            if mte.event_type == MatcherEventType::Reduce
                || mte.event_type == MatcherEventType::Reject
            {
                let currency = if taker_sell { spec.base_currency } else { spec.quote_currency };
                let currency_spec = ssp
                    .get_currency(currency)
                    .unwrap_or_else(|| panic!("currency spec missing for currency {currency}"))
                    .clone();
                let mut reject_events = Vec::new();
                let taker_up = ups.get_or_add_suspended(cmd.uid);
                Self::handle_matcher_reject_reduce_event_exchange(
                    cmd,
                    mte,
                    &spec,
                    &currency_spec,
                    taker_sell,
                    taker_up,
                    &mut reject_events,
                    ssp,
                );
                cmd.fund_events.append(&mut reject_events);
                mte.next.as_deref()
            } else {
                Some(mte)
            };

        if let Some(remaining) = next {
            let base_currency_spec = ssp
                .get_currency(spec.base_currency)
                .unwrap_or_else(|| {
                    panic!("currency spec missing for currency {}", spec.base_currency)
                })
                .clone();
            let quote_currency_spec = ssp
                .get_currency(spec.quote_currency)
                .unwrap_or_else(|| {
                    panic!("currency spec missing for currency {}", spec.quote_currency)
                })
                .clone();
            let mut spot_events = Vec::new();
            if taker_sell {
                Self::handle_matcher_events_exchange_sell(
                    cmd,
                    remaining,
                    &spec,
                    &base_currency_spec,
                    &quote_currency_spec,
                    ups,
                    fees,
                    &mut spot_events,
                    ssp,
                );
            } else {
                Self::handle_matcher_events_exchange_buy(
                    cmd,
                    remaining,
                    &spec,
                    &base_currency_spec,
                    &quote_currency_spec,
                    ups,
                    fees,
                    &mut spot_events,
                    ssp,
                );
            }
            cmd.fund_events.append(&mut spot_events);
        }

        if is_loan_force_liquidate {
            let taker_up = ups.get_or_add_suspended(cmd.uid);
            match cmd.command {
                OrderCommandType::LoanForceLiquidate => {
                    LoanCommandDispatcher::post_process_loan_force_liquidate(
                        self,
                        cmd,
                        &spec,
                        taker_up,
                        ssp,
                        loan_traded_size,
                        loan_traded_notional,
                        loan_rejected_size,
                    );
                }
                OrderCommandType::LoanCrossForceLiquidate => {
                    LoanCommandDispatcher::post_process_loan_cross_force_liquidate(
                        self,
                        cmd,
                        &spec,
                        taker_up,
                        ssp,
                        loan_traded_size,
                        loan_traded_notional,
                        loan_rejected_size,
                    );
                }
                _ => unreachable!("is_loan_force_liquidate implies one of the two force-liquidate codes"),
            }
        }

        cmd.matcher_event = mte_owned;

        Self::refresh_price_record(&mut self.last_price_cache, cmd, true);
    }

    pub fn set_mark_price(&mut self, symbol: i32, price: i64) {
        self.last_price_cache.entry(symbol).or_default().mark_price = price;
    }

    fn refresh_price_record(cache: &mut BTreeMap<i32, LastPriceCacheRecord>, cmd: &OrderCommand, is_spot: bool) {
        let has_book = cmd
            .market_data
            .as_ref()
            .map_or(false, |md| !md.ask_prices.is_empty() && !md.bid_prices.is_empty());
        let trade_price = if is_spot || !has_book {
            let mut cur = cmd.matcher_event.as_deref();
            loop {
                match cur {
                    Some(ev) if ev.event_type == MatcherEventType::Trade => break ev.price,
                    Some(ev) => cur = ev.next.as_deref(),
                    None => break 0,
                }
            }
        } else {
            0
        };
        let record = cache.entry(cmd.symbol).or_default();
        if has_book {
            let md = cmd.market_data.as_ref().unwrap();
            record.ask_price = md.ask_prices[0];
            record.bid_price = md.bid_prices[0];
        } else if trade_price > 0 {
            record.ask_price = trade_price;
            record.bid_price = trade_price;
        }
        if is_spot {
            record.apply_trade_price(cmd.timestamp, trade_price);
        }
    }

    pub fn apply_add_loan(&mut self, cmd: &BatchAddLoanCommand, ssp: &mut SymbolSpecificationProvider) {
        if let Some(g) = &cmd.global {
            let current_liq = self.loan_service.global_config.cross_liquidation_ltv_bps;
            let current_mc = self.loan_service.global_config.cross_margin_call_ltv_bps;
            let numeraire_ok = g.numeraire_currency <= 0 || ssp.get_currency(g.numeraire_currency).is_some();
            if numeraire_ok && g.thresholds_valid_given_current(current_liq, current_mc) {
                let config = &mut self.loan_service.global_config;
                if g.numeraire_currency > 0 {
                    config.numeraire_currency = g.numeraire_currency;
                }
                if g.cross_liquidation_ltv_bps > 0 {
                    config.cross_liquidation_ltv_bps = g.cross_liquidation_ltv_bps;
                }
                if g.cross_margin_call_ltv_bps > 0 {
                    config.cross_margin_call_ltv_bps = g.cross_margin_call_ltv_bps;
                }
                if g.loan_pool_utilization_cap_bps > 0 {
                    config.loan_pool_utilization_cap_bps = g.loan_pool_utilization_cap_bps;
                }
                if g.loan_liquidation_fee_bps > 0 {
                    config.loan_liquidation_fee_bps = g.loan_liquidation_fee_bps;
                }
                if g.ltv_liquidation_buffer_bps > 0 {
                    config.ltv_liquidation_buffer_bps = g.ltv_liquidation_buffer_bps;
                }
                if g.ltv_margin_call_buffer_bps > 0 {
                    config.ltv_margin_call_buffer_bps = g.ltv_margin_call_buffer_bps;
                }
            }
        }

        if let Some(s) = &cmd.symbol {
            let gc = self.loan_service.global_config;
            let resolved = s.resolve(gc.ltv_liquidation_buffer_bps, gc.ltv_margin_call_buffer_bps);
            let spec_ok = match ssp.symbols.get(&s.symbol_id) {
                Some(spec) => spec.symbol_type == SymbolType::CurrencyExchangePair,
                None => false,
            };
            if spec_ok && resolved.valid() {
                let base_currency = ssp.symbols.get(&s.symbol_id).unwrap().base_currency;
                let spec = ssp.symbols.get_mut(&s.symbol_id).unwrap();
                if resolved.initial_ltv_bps == 0 {
                    let cur = spec.loan_config;
                    spec.loan_config.update(
                        0,
                        cur.liquidation_ltv_bps,
                        cur.margin_call_ltv_bps,
                        cur.max_amount,
                        cur.max_term_days,
                    );
                } else {
                    spec.loan_config.update(
                        resolved.initial_ltv_bps,
                        resolved.liquidation_ltv_bps,
                        resolved.margin_call_ltv_bps,
                        resolved.max_amount,
                        resolved.max_term_days,
                    );
                    if let Some(base_spec) = ssp.currencies.get_mut(&base_currency) {
                        base_spec.collateral_weight_bps = resolved.collateral_weight_bps;
                    }
                }
            }
        }

        if let Some(rc) = &cmd.rate_curve {
            if rc.valid() {
                self.loan_service.floating_rate.base_bps = rc.base_bps;
                self.loan_service.floating_rate.kink_util_bps = rc.kink_util_bps;
                self.loan_service.floating_rate.slope1_bps = rc.slope1_bps;
                self.loan_service.floating_rate.slope2_bps = rc.slope2_bps;
                self.loan_service.fixed_rate.locked_rate_adjust_bps = rc.locked_rate_adjust_bps;
            }
        }
    }

    pub fn mark_price(&self, symbol: i32) -> Option<i64> {
        self.last_price_cache.get(&symbol).map(|r| r.mark_price).filter(|&p| p != 0)
    }

    pub fn calculate_locked(
        user_profile: &UserProfile,
        currency: i32,
        ssp: &SymbolSpecificationProvider,
        currency_spec: &CoreCurrencySpecification,
    ) -> i64 {
        let mut locked: i64 = 0;
        for position in user_profile.positions.values() {
            if position.currency == currency {
                let spec = ssp
                    .get_symbol(position.symbol)
                    .unwrap_or_else(|| panic!("symbol spec missing for symbol {}", position.symbol));
                locked += Self::calculate_locked_margin(position, spec, currency_spec);
            }
        }
        locked += user_profile.locked(currency);
        locked += Self::loan_collateral_locked(user_profile, currency);
        locked
    }

    pub fn calculate_free_futures_margin(
        &self,
        user_profile: &UserProfile,
        currency: i32,
        ssp: &SymbolSpecificationProvider,
    ) -> i64 {
        self.calculate_free_futures_margin_for_symbol(user_profile, currency, -1, ssp)
    }

    pub(crate) fn futures_estimates(last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>, up: &UserProfile, pos: &SymbolPositionRecord, spec: &CoreSymbolSpecification, ssp: &SymbolSpecificationProvider) -> (i64, i64, i64, i64) {
        if pos.open_volume == 0 {
            return (0, 0, 0, 0);
        }
        let mark = Self::mark_of(last_price_cache, pos.symbol);
        let upnl = pos.estimate_unrealized_profit(mark);
        let mmsk = spec.maintenance_margin_scale_k;
        if pos.margin_mode == MarginMode::Isolated {
            let total_margin = pos.open_init_margin_sum + upnl + pos.extra_margin;
            let liq = pos.estimate_liquidation_price(spec, mark, 0, 0, 0);
            let mr = pos.estimate_margin_ratio_scale_k(spec, mark, total_margin);
            return (upnl, liq, mr, mmsk);
        }
        let mut total_pnl = 0i64;
        let mut total_mm = 0i64;
        for p in up.positions.values() {
            if p.margin_mode != MarginMode::Cross || p.currency != pos.currency {
                continue;
            }
            let Some(p_spec) = ssp.get_symbol(p.symbol) else { continue };
            let p_mark = Self::mark_of(last_price_cache, p.symbol);
            total_pnl += p.estimate_pnl(p_mark);
            total_mm += p.calculate_maintenance_margin(p_spec, p_mark);
        }
        let Some(cspec) = ssp.get_currency(pos.currency) else { return (upnl, 0, 0, mmsk) };
        let balance_ccy = up.calculate_cross_available(pos.currency, cspec, |sid| ssp.get_symbol(sid));
        let balance = crate::core::utils::core_arithmetic_utils::currency_to_size_price_scale(balance_ccy, spec.base_scale_k, spec.quote_scale_k, cspec.currency_scale_k);
        let total_margin = balance + total_pnl;
        let liq = pos.estimate_liquidation_price(spec, mark, balance, total_pnl, total_mm);
        let mr = pos.estimate_margin_ratio_scale_k(spec, mark, total_margin);
        (upnl, liq, mr, mmsk)
    }

    pub(crate) fn withdrawable_balance(
        &self,
        user_profile: &UserProfile,
        currency: i32,
        ssp: &SymbolSpecificationProvider,
    ) -> i64 {
        let free_futures_margin = if self.cfg_margin_trading_enabled {
            self.calculate_free_futures_margin(user_profile, currency, ssp)
        } else {
            0
        };
        user_profile.account(currency) - user_profile.locked(currency)
            - Self::loan_collateral_locked(user_profile, currency)
            + free_futures_margin
    }

    fn place_order(
        &mut self,
        cmd: &mut OrderCommand,
        user_profile: &mut UserProfile,
        spec: &CoreSymbolSpecification,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        if spec.symbol_type == SymbolType::CurrencyExchangePair {
            let currency = if matches!(cmd.action, Some(OrderAction::Bid)) {
                spec.quote_currency
            } else {
                spec.base_currency
            };
            let currency_spec = ssp
                .get_currency(currency)
                .unwrap_or_else(|| panic!("currency spec missing for currency {currency}"));
            return self.place_exchange_order(cmd, user_profile, spec, currency_spec, ssp);
        }
        if !spec.symbol_type.is_futures_contract() {
            return CommandResultCode::UnsupportedSymbolType;
        }
        if !self.cfg_margin_trading_enabled {
            return CommandResultCode::RiskMarginTradingDisabled;
        }
        let mark_price = match self.mark_price(cmd.symbol) {
            Some(p) => p,
            None => return CommandResultCode::RiskMarkpriceNotAvailable,
        };
        let action = cmd.action.expect("PLACE_ORDER requires action");

        if user_profile.count_position_record(spec.symbol_id, |pos| pos.margin_mode != cmd.margin_mode) > 0 {
            return CommandResultCode::RiskMarginModeMismatch;
        }
        if user_profile.count_position_record(spec.symbol_id, |pos| !pos.is_same_leverage(cmd.leverage)) > 0 {
            return CommandResultCode::RiskLeverageMismatch;
        }

        let position_key = user_profile.create_positions_key(spec.symbol_id, action, cmd.command);
        let is_new_position = !user_profile.positions.contains_key(&position_key);
        let mut position = match user_profile.positions.get(&position_key) {
            Some(existing) => existing.clone(),
            None => {
                let mut p = SymbolPositionRecord::default();
                p.initialize(user_profile.uid, spec.symbol_id, spec.quote_currency, action, cmd.leverage, cmd.margin_mode);
                p
            }
        };

        if user_profile.position_mode == PositionMode::OneWay && cmd.is_reduce_only() {
            cmd.size = Self::max_closable_size(&position, action, cmd.size);
            if cmd.size <= 0 {
                return CommandResultCode::Success;
            }
        }

        let currency_spec = ssp
            .get_currency(position.currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {}", position.currency));

        let notional = position.estimate_notional_for_order(action, cmd.size, mark_price);
        if !spec.is_valid_leverage(notional, cmd.leverage) {
            return CommandResultCode::RiskInvalidLeverage;
        }

        if !self.can_place_margin_order(cmd, user_profile, spec, &position, position_key, currency_spec, ssp) {
            return CommandResultCode::RiskNsf;
        }

        if matches!(cmd.order_type, Some(OrderType::FokBudget) | Some(OrderType::IocBudget)) {
            position.pending_hold_budget(action, cmd.size, cmd.price);
        } else {
            position.pending_hold(action, cmd.size, cmd.price);
        }
        user_profile.positions.insert(position_key, position);
        if is_new_position {
            self.liquidation_engine.on_position_opened(user_profile.uid, spec.symbol_id);
        }

        CommandResultCode::ValidForMatchingEngine
    }

    #[allow(clippy::too_many_arguments)]
    fn can_place_margin_order(
        &self,
        cmd: &OrderCommand,
        user_profile: &UserProfile,
        spec: &CoreSymbolSpecification,
        position: &SymbolPositionRecord,
        position_key: i32,
        currency_spec: &CoreCurrencySpecification,
        ssp: &SymbolSpecificationProvider,
    ) -> bool {
        let action = cmd.action.expect("PLACE_ORDER requires action");
        let is_budget_order = matches!(cmd.order_type, Some(OrderType::FokBudget) | Some(OrderType::IocBudget));

        let order_notional = if is_budget_order { cmd.price } else { mul_exact(cmd.size, cmd.price) };
        let new_order_margin = position.calculate_required_margin_for_order(spec, action, order_notional);
        let position_margin = if new_order_margin == -1 {
            position.calculate_required_margin_for_futures(spec)
        } else {
            new_order_margin
        };

        let mut cross_free_margin: i64 = 0;
        for (&key, pos_record) in user_profile.positions.iter() {
            if key == position_key {
                if pos_record.margin_mode == MarginMode::Cross {
                    let mark = self.mark_price(pos_record.symbol).unwrap_or_else(|| {
                        panic!("mark price missing for open position symbol {}", pos_record.symbol)
                    });
                    cross_free_margin += arithmetic::size_price_to_currency_scale(
                        pos_record.estimate_pnl(mark),
                        spec.base_scale_k,
                        spec.quote_scale_k,
                        currency_spec.currency_scale_k,
                    );
                }
            } else if pos_record.currency == spec.quote_currency {
                let other_spec = ssp
                    .get_symbol(pos_record.symbol)
                    .unwrap_or_else(|| panic!("symbol spec missing for symbol {}", pos_record.symbol));
                if pos_record.margin_mode == MarginMode::Cross {
                    let mark = self.mark_price(pos_record.symbol).unwrap_or_else(|| {
                        panic!("mark price missing for open position symbol {}", pos_record.symbol)
                    });
                    cross_free_margin += arithmetic::size_price_to_currency_scale(
                        pos_record.estimate_pnl(mark),
                        other_spec.base_scale_k,
                        other_spec.quote_scale_k,
                        currency_spec.currency_scale_k,
                    );
                }
                cross_free_margin -= arithmetic::size_price_to_currency_scale(
                    pos_record.calculate_required_margin_for_futures(other_spec),
                    other_spec.base_scale_k,
                    other_spec.quote_scale_k,
                    currency_spec.currency_scale_k,
                );
            }
        }

        let pending_fee = if is_budget_order {
            position.calculate_pending_fee_for_order_budget(spec, action, cmd.size, cmd.price)
        } else {
            position.calculate_pending_fee_for_order(spec, action, cmd.size, cmd.price)
        };

        let mut open_loss: i64 = 0;
        if !is_budget_order {
            let opposite_to_pos = user_profile.position_mode == PositionMode::OneWay
                && position.open_volume > 0
                && ((action == OrderAction::Bid && position.direction == PositionDirection::Short)
                    || (action == OrderAction::Ask && position.direction == PositionDirection::Long));
            let opening_size =
                if opposite_to_pos { 0i64.max(sub_exact(cmd.size, position.open_volume)) } else { cmd.size };
            if opening_size > 0 {
                let mark_price = self
                    .mark_price(cmd.symbol)
                    .unwrap_or_else(|| panic!("mark price missing for symbol {} (checked by caller)", cmd.symbol));
                let order_cost = mul_exact(opening_size, cmd.price);
                let mark_cost = mul_exact(opening_size, mark_price);
                open_loss = match action {
                    OrderAction::Bid => 0i64.max(sub_exact(order_cost, mark_cost)),
                    OrderAction::Ask => 0i64.max(sub_exact(mark_cost, order_cost)),
                };
            }
        }

        let currency = position.currency;
        let spendable = user_profile.account(currency) - user_profile.locked(currency)
            - Self::loan_collateral_locked(user_profile, currency);
        let required = arithmetic::size_price_to_currency_scale(
            position_margin + pending_fee + open_loss,
            spec.base_scale_k,
            spec.quote_scale_k,
            currency_spec.currency_scale_k,
        ) - cross_free_margin;
        required <= spendable
    }

    fn loan_collateral_locked(user_profile: &UserProfile, currency: i32) -> i64 {
        let mut locked: i64 = 0;
        for loan in user_profile.isolated_loans.values() {
            if loan.collateral_currency == currency {
                locked += loan.collateral_amount;
            }
        }
        locked += user_profile.cross_loan_collateral.get(&currency).copied().unwrap_or(0);
        locked
    }

    fn calculate_locked_margin(
        position: &SymbolPositionRecord,
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
    ) -> i64 {
        let required = position.calculate_required_margin_for_futures(spec);
        arithmetic::size_price_to_currency_scale(
            required,
            spec.base_scale_k,
            spec.quote_scale_k,
            currency_spec.currency_scale_k,
        )
    }

    fn calculate_free_futures_margin_for_symbol(
        &self,
        user_profile: &UserProfile,
        currency: i32,
        cur_pos_symbol: i32,
        ssp: &SymbolSpecificationProvider,
    ) -> i64 {
        if !user_profile.positions.values().any(|p| p.currency == currency) {
            return 0;
        }
        let currency_spec = ssp
            .get_currency(currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {currency}"));

        let mut realized_pnl: i64 = 0;
        let mut unrealized_pnl: i64 = 0;
        let mut isolated_required_margin: i64 = 0;
        let mut cross_initial_margin: i64 = 0;
        let mut cross_maintenance_margin: i64 = 0;

        for position in user_profile.positions.values() {
            if position.currency != currency {
                continue;
            }
            let spec = ssp
                .get_symbol(position.symbol)
                .unwrap_or_else(|| panic!("symbol spec missing for symbol {}", position.symbol));
            let mark = self
                .mark_price(position.symbol)
                .unwrap_or_else(|| panic!("mark price missing for open position symbol {}", position.symbol));

            realized_pnl += arithmetic::size_price_to_currency_scale(
                position.profit,
                spec.base_scale_k,
                spec.quote_scale_k,
                currency_spec.currency_scale_k,
            );

            if position.margin_mode == MarginMode::Cross {
                unrealized_pnl += arithmetic::size_price_to_currency_scale(
                    position.estimate_unrealized_profit(mark),
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    currency_spec.currency_scale_k,
                );
                let initial_margin = position.calculate_required_margin_for_futures(spec);
                cross_initial_margin += arithmetic::size_price_to_currency_scale(
                    initial_margin,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    currency_spec.currency_scale_k,
                );
                let maintenance_margin = initial_margin - position.open_init_margin_sum
                    + position.calculate_maintenance_margin(spec, mark);
                cross_maintenance_margin += arithmetic::size_price_to_currency_scale(
                    maintenance_margin,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    currency_spec.currency_scale_k,
                );
            } else {
                if position.symbol == cur_pos_symbol {
                    unrealized_pnl += arithmetic::size_price_to_currency_scale(
                        position.estimate_unrealized_profit(mark),
                        spec.base_scale_k,
                        spec.quote_scale_k,
                        currency_spec.currency_scale_k,
                    );
                }
                isolated_required_margin += Self::calculate_locked_margin(position, spec, currency_spec);
            }
        }

        (realized_pnl + unrealized_pnl - cross_initial_margin - isolated_required_margin)
            .min(realized_pnl - cross_maintenance_margin - isolated_required_margin)
    }

    fn max_closable_size(pos: &SymbolPositionRecord, action: OrderAction, requested_size: i64) -> i64 {
        if !pos.direction.is_opposite_to_action(action) {
            return 0;
        }
        requested_size.min(pos.open_volume)
    }

    fn place_exchange_order(
        &mut self,
        cmd: &OrderCommand,
        user_profile: &mut UserProfile,
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let is_bid = matches!(cmd.action, Some(OrderAction::Bid));
        let currency = if is_bid { spec.quote_currency } else { spec.base_currency };
        let size = cmd.size;

        let order_lock_amount = if is_bid {
            let is_budget =
                matches!(cmd.order_type, Some(OrderType::FokBudget) | Some(OrderType::IocBudget));
            let raw = if is_budget {
                if cmd.reserve_bid_price != cmd.price {
                    return CommandResultCode::RiskInvalidReserveBidPrice;
                }
                arithmetic::calculate_amount_bid_taker_fee_for_budget(
                    size,
                    cmd.price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                )
            } else {
                if cmd.reserve_bid_price < cmd.price {
                    return CommandResultCode::RiskInvalidReserveBidPrice;
                }
                arithmetic::calculate_amount_bid_taker_fee(
                    size,
                    cmd.reserve_bid_price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                )
            };
            arithmetic::size_price_to_currency_scale(
                raw,
                spec.base_scale_k,
                spec.quote_scale_k,
                currency_spec.currency_scale_k,
            )
        } else {
            if arithmetic::is_ask_price_too_low(cmd.price, spec.taker_fee, spec.fee_scale_k) {
                return CommandResultCode::RiskAskPriceLowerThanFee;
            }
            let raw = arithmetic::calculate_amount_ask(size);
            arithmetic::symbol_to_currency_scale(raw, spec.base_scale_k, currency_spec.currency_scale_k)
        };

        let balance = user_profile.account(currency);
        let existing_locked = user_profile.locked(currency);
        let free_futures_margin = if self.cfg_margin_trading_enabled {
            self.calculate_free_futures_margin(user_profile, currency, ssp)
        } else {
            0
        };
        let loan_locked = Self::loan_collateral_locked(user_profile, currency);
        if balance - existing_locked - loan_locked - order_lock_amount + free_futures_margin < 0 {
            return CommandResultCode::RiskNsf;
        }
        user_profile.add_to_locked(currency, order_lock_amount);
        CommandResultCode::ValidForMatchingEngine
    }

    #[allow(clippy::too_many_arguments)]
    fn handle_matcher_reject_reduce_event_exchange(
        cmd: &OrderCommand,
        mte: &MatcherTradeEvent,
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
        taker_sell: bool,
        taker_up: &mut UserProfile,
        fund_events: &mut Vec<FundEvent>,
        ssp: &SymbolSpecificationProvider,
    ) {
        let currency = if taker_sell { spec.base_currency } else { spec.quote_currency };

        let release = if taker_sell {
            let raw = arithmetic::calculate_amount_ask(mte.size);
            arithmetic::symbol_to_currency_scale(raw, spec.base_scale_k, currency_spec.currency_scale_k)
        } else {
            let is_fok_budget = cmd.command == OrderCommandType::PlaceOrder
                && matches!(cmd.order_type, Some(OrderType::FokBudget));
            let is_ioc_budget = matches!(cmd.order_type, Some(OrderType::IocBudget));
            let release_sp = if is_fok_budget {
                arithmetic::calculate_amount_bid_taker_fee_for_budget(
                    mte.size,
                    mte.price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                )
            } else if is_ioc_budget && mte.next.is_none() {
                arithmetic::calculate_amount_bid_taker_fee_for_budget(
                    cmd.size,
                    cmd.price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                )
            } else if is_ioc_budget {
                0
            } else {
                arithmetic::calculate_amount_bid_taker_fee(
                    mte.size,
                    mte.bidder_hold_price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                )
            };
            arithmetic::size_price_to_currency_scale(
                release_sp,
                spec.base_scale_k,
                spec.quote_scale_k,
                currency_spec.currency_scale_k,
            )
        };

        taker_up.add_to_locked(currency, -release);

        if release > 0 {
            if let Some(cspec) = ssp.get_currency(currency) {
                fund_events.push(Self::spot_snapshot_event(FundEventType::Unlocked, cmd.order_id, taker_up, currency, ssp, cspec, spec.symbol_id));
            }
        }
    }

    pub(crate) fn spot_snapshot_event(
        event_type: FundEventType,
        order_id: i64,
        up: &UserProfile,
        currency: i32,
        ssp: &SymbolSpecificationProvider,
        currency_spec: &CoreCurrencySpecification,
        symbol_id: i32,
    ) -> FundEvent {
        let locked = Self::calculate_locked(up, currency, ssp, currency_spec);
        let mut ev = FundEvent::spot(event_type, order_id, up.uid, currency, up.account(currency) - locked, locked);
        ev.symbol = symbol_id;
        ev.currency_scale_k = currency_spec.currency_scale_k;
        ev
    }

    #[allow(clippy::too_many_arguments)]
    fn handle_matcher_events_exchange_sell(
        cmd: &OrderCommand,
        first_trade_mte: &MatcherTradeEvent,
        spec: &CoreSymbolSpecification,
        base_currency_spec: &CoreCurrencySpecification,
        quote_currency_spec: &CoreCurrencySpecification,
        ups: &mut UserProfileService,
        fees: &mut BTreeMap<i32, i64>,
        fund_events: &mut Vec<FundEvent>,
        ssp: &SymbolSpecificationProvider,
    ) {
        let base_currency = spec.base_currency;
        let quote_currency = spec.quote_currency;

        let mut taker_notional: i128 = 0;
        let mut taker_size: i64 = 0;
        let mut maker_notional: i128 = 0;
        let mut maker_size: i64 = 0;

        let mut node = Some(first_trade_mte);
        while let Some(ev) = node {
            debug_assert_eq!(ev.event_type, MatcherEventType::Trade);

            taker_notional += ev.size as i128 * ev.price as i128;
            taker_size += ev.size;

            {
                let maker_up = ups.get_or_add_suspended(ev.matched_order_uid);

                let hold_quote_raw = arithmetic::calculate_amount_bid_taker_fee(
                    ev.size,
                    ev.bidder_hold_price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                );
                let hold_quote = arithmetic::size_price_to_currency_scale(
                    hold_quote_raw,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    quote_currency_spec.currency_scale_k,
                );

                let quote_refund_raw = arithmetic::calculate_amount_bid_release_corr_maker(
                    ev.size,
                    ev.bidder_hold_price,
                    ev.price,
                    spec.taker_fee,
                    spec.maker_fee,
                    spec.fee_scale_k,
                );
                let quote_refund = arithmetic::size_price_to_currency_scale(
                    quote_refund_raw,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    quote_currency_spec.currency_scale_k,
                );

                maker_up.add_to_locked(quote_currency, -hold_quote);
                maker_up.add_to_account(quote_currency, quote_refund - hold_quote);

                let base_gained = arithmetic::symbol_to_currency_scale(
                    arithmetic::calculate_amount_ask(ev.size),
                    spec.base_scale_k,
                    base_currency_spec.currency_scale_k,
                );
                maker_up.add_to_account(base_currency, base_gained);

                if quote_refund > 0 {
                    fund_events.push(Self::spot_snapshot_event(FundEventType::Unlocked, ev.maker_order_id, maker_up, quote_currency, ssp, quote_currency_spec, spec.symbol_id));
                }
                fund_events.push(Self::spot_snapshot_event(FundEventType::Transfer, ev.maker_order_id, maker_up, quote_currency, ssp, quote_currency_spec, spec.symbol_id));
                fund_events.push(Self::spot_snapshot_event(FundEventType::Transfer, ev.maker_order_id, maker_up, base_currency, ssp, base_currency_spec, spec.symbol_id));
            }

            maker_notional += ev.size as i128 * ev.price as i128;
            maker_size += ev.size;

            node = ev.next.as_deref();
        }

        let avg_taker_price = if taker_size > 0 {
            i64::try_from(taker_notional / taker_size as i128)
                .unwrap_or_else(|_| panic!("overflow narrowing avg_taker_price"))
        } else {
            0
        };
        let taker_fee = arithmetic::calculate_taker_fee(
            taker_size,
            avg_taker_price,
            spec.taker_fee,
            spec.fee_scale_k,
        );

        {
            let taker_up = ups.get_or_add_suspended(cmd.uid);

            let base_paid = arithmetic::symbol_to_currency_scale(
                arithmetic::calculate_amount_ask(taker_size),
                spec.base_scale_k,
                base_currency_spec.currency_scale_k,
            );
            taker_up.add_to_locked(base_currency, -base_paid);
            taker_up.add_to_account(base_currency, -base_paid);

            let net_notional_raw = i64::try_from(taker_notional - taker_fee as i128)
                .unwrap_or_else(|_| panic!("overflow narrowing taker net notional"));
            let to_be_added = arithmetic::size_price_to_currency_scale(
                net_notional_raw,
                spec.base_scale_k,
                spec.quote_scale_k,
                quote_currency_spec.currency_scale_k,
            );
            taker_up.add_to_account(quote_currency, to_be_added);

            fund_events.push(Self::spot_snapshot_event(FundEventType::Transfer, cmd.order_id, taker_up, quote_currency, ssp, quote_currency_spec, spec.symbol_id));
            fund_events.push(Self::spot_snapshot_event(FundEventType::Transfer, cmd.order_id, taker_up, base_currency, ssp, base_currency_spec, spec.symbol_id));
        }

        if taker_size != 0 || maker_size != 0 {
            let avg_maker_price = if maker_size > 0 {
                i64::try_from(maker_notional / maker_size as i128)
                    .unwrap_or_else(|_| panic!("overflow narrowing avg_maker_price"))
            } else {
                0
            };
            let maker_fee = arithmetic::calculate_maker_fee(
                maker_size,
                avg_maker_price,
                spec.maker_fee,
                spec.fee_scale_k,
            );

            let fee_sum = taker_fee
                .checked_add(maker_fee)
                .unwrap_or_else(|| panic!("overflow: taker_fee + maker_fee"));
            let fee_scaled = arithmetic::size_price_to_currency_scale(
                fee_sum,
                spec.base_scale_k,
                spec.quote_scale_k,
                quote_currency_spec.currency_scale_k,
            );
            *fees.entry(quote_currency).or_insert(0) += fee_scaled;
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn handle_matcher_events_exchange_buy(
        cmd: &OrderCommand,
        first_trade_mte: &MatcherTradeEvent,
        spec: &CoreSymbolSpecification,
        base_currency_spec: &CoreCurrencySpecification,
        quote_currency_spec: &CoreCurrencySpecification,
        ups: &mut UserProfileService,
        fees: &mut BTreeMap<i32, i64>,
        fund_events: &mut Vec<FundEvent>,
        ssp: &SymbolSpecificationProvider,
    ) {
        let base_currency = spec.base_currency;
        let quote_currency = spec.quote_currency;

        let mut taker_notional: i128 = 0;
        let mut taker_hold_notional: i128 = 0;
        let mut taker_size: i64 = 0;
        let mut maker_notional: i128 = 0;
        let mut maker_size: i64 = 0;

        let mut node = Some(first_trade_mte);
        while let Some(ev) = node {
            debug_assert_eq!(ev.event_type, MatcherEventType::Trade);

            taker_notional += ev.size as i128 * ev.price as i128;
            taker_hold_notional += ev.size as i128 * ev.bidder_hold_price as i128;
            taker_size += ev.size;

            {
                let maker_up = ups.get_or_add_suspended(ev.matched_order_uid);

                let quote_gained = arithmetic::calculate_amount_bid(ev.size, ev.price);

                let base_paid = arithmetic::symbol_to_currency_scale(
                    arithmetic::calculate_amount_ask(ev.size),
                    spec.base_scale_k,
                    base_currency_spec.currency_scale_k,
                );
                maker_up.add_to_locked(base_currency, -base_paid);
                maker_up.add_to_account(base_currency, -base_paid);

                let fee = arithmetic::calculate_maker_fee(
                    ev.size,
                    ev.price,
                    spec.maker_fee,
                    spec.fee_scale_k,
                );
                let to_be_added = arithmetic::size_price_to_currency_scale(
                    quote_gained - fee,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    quote_currency_spec.currency_scale_k,
                );
                maker_up.add_to_account(quote_currency, to_be_added);

                fund_events.push(Self::spot_snapshot_event(FundEventType::Transfer, ev.maker_order_id, maker_up, quote_currency, ssp, quote_currency_spec, spec.symbol_id));
                fund_events.push(Self::spot_snapshot_event(FundEventType::Transfer, ev.maker_order_id, maker_up, base_currency, ssp, base_currency_spec, spec.symbol_id));
            }

            maker_notional += ev.size as i128 * ev.price as i128;
            maker_size += ev.size;

            node = ev.next.as_deref();
        }

        let avg_taker_price = if taker_size > 0 {
            i64::try_from(taker_notional / taker_size as i128)
                .unwrap_or_else(|_| panic!("overflow narrowing avg_taker_price"))
        } else {
            0
        };
        let taker_fee = arithmetic::calculate_taker_fee(
            taker_size,
            avg_taker_price,
            spec.taker_fee,
            spec.fee_scale_k,
        );

        {
            let taker_notional_i64 = i64::try_from(taker_notional)
                .unwrap_or_else(|_| panic!("overflow narrowing taker_notional"));

            let is_budget = cmd.command == OrderCommandType::PlaceOrder
                && matches!(cmd.order_type, Some(OrderType::FokBudget) | Some(OrderType::IocBudget));

            let (leftover, hold_quote, effective_hold_notional) = if is_budget {
                let held_total = arithmetic::calculate_amount_bid_taker_fee_for_budget(
                    cmd.size,
                    cmd.price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                );
                let leftover = held_total - (taker_notional_i64 + taker_fee);
                let hold_quote = arithmetic::size_price_to_currency_scale(
                    held_total,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    quote_currency_spec.currency_scale_k,
                );
                (leftover, hold_quote, taker_notional_i64)
            } else {
                let taker_hold_notional_i64 = i64::try_from(taker_hold_notional)
                    .unwrap_or_else(|_| panic!("overflow narrowing taker_hold_notional"));
                let avg_hold_price = taker_hold_notional_i64 / taker_size;
                let fee_held = arithmetic::calculate_taker_fee(
                    taker_size,
                    avg_hold_price,
                    spec.taker_fee,
                    spec.fee_scale_k,
                );
                let leftover = fee_held - taker_fee;
                let hold_quote = arithmetic::size_price_to_currency_scale(
                    taker_hold_notional_i64 + fee_held,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    quote_currency_spec.currency_scale_k,
                );
                (leftover, hold_quote, taker_hold_notional_i64)
            };

            let quote_refund = arithmetic::size_price_to_currency_scale(
                effective_hold_notional - taker_notional_i64 + leftover,
                spec.base_scale_k,
                spec.quote_scale_k,
                quote_currency_spec.currency_scale_k,
            );

            let taker_up = ups.get_or_add_suspended(cmd.uid);

            taker_up.add_to_locked(quote_currency, -hold_quote);
            taker_up.add_to_account(quote_currency, quote_refund - hold_quote);

            let to_be_added = arithmetic::symbol_to_currency_scale(
                taker_size,
                spec.base_scale_k,
                base_currency_spec.currency_scale_k,
            );
            taker_up.add_to_account(base_currency, to_be_added);

            if quote_refund > 0 {
                fund_events.push(Self::spot_snapshot_event(FundEventType::Unlocked, cmd.order_id, taker_up, quote_currency, ssp, quote_currency_spec, spec.symbol_id));
            }
            fund_events.push(Self::spot_snapshot_event(FundEventType::Transfer, cmd.order_id, taker_up, quote_currency, ssp, quote_currency_spec, spec.symbol_id));
            fund_events.push(Self::spot_snapshot_event(FundEventType::Transfer, cmd.order_id, taker_up, base_currency, ssp, base_currency_spec, spec.symbol_id));
        }

        if taker_size != 0 || maker_size != 0 {
            let avg_maker_price = if maker_size > 0 {
                i64::try_from(maker_notional / maker_size as i128)
                    .unwrap_or_else(|_| panic!("overflow narrowing avg_maker_price"))
            } else {
                0
            };
            let maker_fee = arithmetic::calculate_maker_fee(
                maker_size,
                avg_maker_price,
                spec.maker_fee,
                spec.fee_scale_k,
            );

            let fee_sum = taker_fee
                .checked_add(maker_fee)
                .unwrap_or_else(|| panic!("overflow: taker_fee + maker_fee"));
            let fee_scaled = arithmetic::size_price_to_currency_scale(
                fee_sum,
                spec.base_scale_k,
                spec.quote_scale_k,
                quote_currency_spec.currency_scale_k,
            );
            *fees.entry(quote_currency).or_insert(0) += fee_scaled;
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn handle_matcher_event_margin(
        cmd_uid: i64,
        cmd_command: OrderCommandType,
        fund_events: &mut Vec<FundEvent>,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        first_mte: &MatcherTradeEvent,
        spec: &CoreSymbolSpecification,
        taker_action: OrderAction,
        ups: &mut UserProfileService,
        fees: &mut BTreeMap<i32, i64>,
        quote_currency_spec: &CoreCurrencySpecification,
        mark_price: i64,
        is_liquidation: bool,
        cmd_order_id: i64,
    ) {
        let mut node = Some(first_mte);
        while let Some(ev) = node {
            Self::handle_matcher_event_margin_one(
                cmd_uid,
                cmd_command,
                fund_events,
                ssp,
                last_price_cache,
                ev,
                spec,
                taker_action,
                ups,
                fees,
                quote_currency_spec,
                mark_price,
                is_liquidation,
                cmd_order_id,
            );
            node = ev.next.as_deref();
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn handle_matcher_event_margin_one(
        cmd_uid: i64,
        cmd_command: OrderCommandType,
        fund_events: &mut Vec<FundEvent>,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        mte: &MatcherTradeEvent,
        spec: &CoreSymbolSpecification,
        taker_action: OrderAction,
        ups: &mut UserProfileService,
        fees: &mut BTreeMap<i32, i64>,
        quote_currency_spec: &CoreCurrencySpecification,
        mark_price: i64,
        is_liquidation: bool,
        cmd_order_id: i64,
    ) {
        {
            let taker_up = ups.get_or_add_suspended(cmd_uid);
            let position_key = taker_up.create_positions_key(spec.symbol_id, taker_action, cmd_command);
            Self::settle_margin_position_event(
                fund_events,
                ssp,
                last_price_cache,
                taker_up,
                position_key,
                 false,
                mte,
                spec,
                taker_action,
                fees,
                quote_currency_spec,
                mark_price,
                 true,
                is_liquidation,
                cmd_order_id,
            );
        }

        if mte.event_type == MatcherEventType::Trade {
            let maker_action = taker_action.opposite();
            let maker_up = ups.get_or_add_suspended(mte.matched_order_uid);
            let position_key =
                maker_up.create_positions_key(spec.symbol_id, maker_action, mte.matched_order_command_type);
            Self::settle_margin_position_event(
                fund_events,
                ssp,
                last_price_cache,
                maker_up,
                position_key,
                 true,
                mte,
                spec,
                maker_action,
                fees,
                quote_currency_spec,
                mark_price,
                 false,
                 false,
                cmd_order_id,
            );
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn settle_margin_position_event(
        fund_events: &mut Vec<FundEvent>,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        up: &mut UserProfile,
        position_key: i32,
        required: bool,
        mte: &MatcherTradeEvent,
        spec: &CoreSymbolSpecification,
        action: OrderAction,
        fees: &mut BTreeMap<i32, i64>,
        quote_currency_spec: &CoreCurrencySpecification,
        mark_price: i64,
        is_taker: bool,
        is_liquidation: bool,
        cmd_order_id: i64,
    ) {
        let event_order_id = if is_taker { cmd_order_id } else { mte.maker_order_id };
        if !up.positions.contains_key(&position_key) {
            if required {
                panic!(
                    "handle_matcher_event_margin: maker position record missing for key {position_key} \
                     (matched_order_uid={})",
                    up.uid
                );
            }
            return;
        }

        let quote_currency = spec.quote_currency;

        match mte.event_type {
            MatcherEventType::Trade => {
                let pre_volume = up.positions.get(&position_key).unwrap().open_volume;
                let pending_released = up.positions.get_mut(&position_key).unwrap().pending_release(action, mte.size);

                if pending_released > 0 {
                    Self::push_futures_event(fund_events, last_price_cache, FundEventType::UnlockPending, event_order_id, up.positions.get(&position_key).unwrap(), spec, up, ssp);
                }

                let size_to_open = up
                    .positions
                    .get_mut(&position_key)
                    .unwrap()
                    .close_current_position_futures(action, mte.size, mte.price);
                let closed_size = 0i64.max(pre_volume - up.positions.get(&position_key).unwrap().open_volume);

                if closed_size > 0 {
                    let raw_fee = if is_taker {
                        arithmetic::calculate_taker_fee(closed_size, mte.price, spec.taker_fee, spec.fee_scale_k)
                    } else {
                        arithmetic::calculate_maker_fee(closed_size, mte.price, spec.maker_fee, spec.fee_scale_k)
                    };
                    let fee = arithmetic::size_price_to_currency_scale(
                        raw_fee,
                        spec.base_scale_k,
                        spec.quote_scale_k,
                        quote_currency_spec.currency_scale_k,
                    );
                    up.add_to_account(quote_currency, -fee);
                    *fees.entry(quote_currency).or_insert(0) += fee;

                    let close_type = if is_liquidation { FundEventType::LiquidationClose } else { FundEventType::ClosePosition };
                    Self::push_futures_event(fund_events, last_price_cache, close_type, event_order_id, up.positions.get(&position_key).unwrap(), spec, up, ssp);
                }

                if size_to_open > 0 {
                    up.positions.get_mut(&position_key).unwrap().open_position_margin(
                        action,
                        size_to_open,
                        mte.price,
                        spec,
                        mark_price,
                    );

                    let raw_fee = if is_taker {
                        arithmetic::calculate_taker_fee(size_to_open, mte.price, spec.taker_fee, spec.fee_scale_k)
                    } else {
                        arithmetic::calculate_maker_fee(size_to_open, mte.price, spec.maker_fee, spec.fee_scale_k)
                    };
                    let fee = arithmetic::size_price_to_currency_scale(
                        raw_fee,
                        spec.base_scale_k,
                        spec.quote_scale_k,
                        quote_currency_spec.currency_scale_k,
                    );
                    up.add_to_account(quote_currency, -fee);
                    *fees.entry(quote_currency).or_insert(0) += fee;

                    Self::push_futures_event(fund_events, last_price_cache, FundEventType::OpenPosition, event_order_id, up.positions.get(&position_key).unwrap(), spec, up, ssp);
                }
            }
            MatcherEventType::Reject | MatcherEventType::Reduce => {
                up.positions.get_mut(&position_key).unwrap().pending_release(action, mte.size);
                Self::push_futures_event(fund_events, last_price_cache, FundEventType::UnlockPending, event_order_id, up.positions.get(&position_key).unwrap(), spec, up, ssp);
            }
            MatcherEventType::BinaryEvent => {
            }
        }

        let is_empty = up.positions.get(&position_key).unwrap().is_empty();
        if is_empty {
            let currency = up.positions.get(&position_key).unwrap().currency;

            let extra_margin = up.positions.get(&position_key).unwrap().extra_margin;
            if extra_margin > 0 {
                let refund = arithmetic::size_price_to_currency_scale(
                    extra_margin,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    quote_currency_spec.currency_scale_k,
                );
                up.add_to_account(currency, refund);
                Self::push_futures_event(fund_events, last_price_cache, FundEventType::MarginRefund, event_order_id, up.positions.get(&position_key).unwrap(), spec, up, ssp);
                up.positions.get_mut(&position_key).unwrap().extra_margin = 0;
            }

            let profit = up.positions.get(&position_key).unwrap().profit;
            if profit != 0 {
                let profit_scaled = arithmetic::size_price_to_currency_scale(
                    profit,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    quote_currency_spec.currency_scale_k,
                );
                up.add_to_account(currency, profit_scaled);
                Self::push_futures_event(fund_events, last_price_cache, FundEventType::PnlSettlement, event_order_id, up.positions.get(&position_key).unwrap(), spec, up, ssp);
            }

            up.positions.remove(&position_key);
        }
    }

    pub(crate) fn harvest_into(map: &mut BTreeMap<i32, i64>, adjustments: &mut BTreeMap<i32, i64>, harvested: &mut BTreeMap<i32, i64>) {
        for (&c, v) in map.iter_mut() {
            let amount = std::mem::replace(v, 0);
            if amount != 0 {
                *adjustments.entry(c).or_insert(0) += amount;
                *harvested.entry(c).or_insert(0) += amount;
            }
        }
    }

    fn mark_of(last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>, symbol: i32) -> i64 {
        last_price_cache.get(&symbol).map(|r| r.mark_price).unwrap_or(0)
    }

    #[allow(clippy::too_many_arguments)]
    pub(crate) fn push_futures_event(
        fund_events: &mut Vec<FundEvent>,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        event_type: FundEventType,
        order_id: i64,
        pos: &SymbolPositionRecord,
        spec: &CoreSymbolSpecification,
        up: &UserProfile,
        ssp: &SymbolSpecificationProvider,
    ) {
        let (upnl, liq, mr, mmsk) = Self::futures_estimates(last_price_cache, up, pos, spec, ssp);
        let (free, locked, cur_scale) = match ssp.get_currency(pos.currency) {
            Some(cspec) => {
                let locked = Self::calculate_locked(up, pos.currency, ssp, cspec);
                (up.account(pos.currency) - locked, locked, cspec.currency_scale_k)
            }
            None => (0, 0, 0),
        };
        fund_events.push(FundEvent {
            event_type,
            order_id,
            uid: pos.uid,
            currency: pos.currency,
            currency_scale_k: cur_scale,
            free,
            locked,
            symbol: pos.symbol,
            base_scale_k: spec.base_scale_k,
            quote_scale_k: spec.quote_scale_k,
            direction: pos.direction,
            open_volume: pos.open_volume,
            open_init_margin_sum: pos.open_init_margin_sum,
            open_price_sum: pos.open_price_sum,
            profit: pos.profit,
            leverage: pos.leverage,
            margin_mode: pos.margin_mode,
            extra_margin: pos.extra_margin,
            unrealized_profit: upnl,
            liquidation_price: liq,
            margin_ratio_scale_k: mr,
            maintenance_margin_scale_k: mmsk,
            mark_price: Self::mark_of(last_price_cache, pos.symbol),
            pending_buy_size: pos.pending_buy_size,
            pending_buy_avg_price: pos.pending_buy_avg_price,
            pending_sell_size: pos.pending_sell_size,
            pending_sell_avg_price: pos.pending_sell_avg_price,
            ..Default::default()
        });
    }

    #[allow(clippy::too_many_arguments)]
    pub(crate) fn close_and_settle_futures_position(
        up: &mut UserProfile,
        position_key: i32,
        close_action: OrderAction,
        size: i64,
        price: i64,
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
        fund_events: &mut Vec<FundEvent>,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        ssp: &SymbolSpecificationProvider,
        close_event_type: FundEventType,
        order_id: i64,
    ) {
        if !up.positions.contains_key(&position_key) {
            return;
        }
        up.positions.get_mut(&position_key).unwrap().close_current_position_futures(close_action, size, price);

        Self::push_futures_event(fund_events, last_price_cache, close_event_type, order_id, up.positions.get(&position_key).unwrap(), spec, up, ssp);

        let is_empty = up.positions.get(&position_key).map(|p| p.is_empty()).unwrap_or(false);
        if !is_empty {
            return;
        }
        let currency = up.positions.get(&position_key).unwrap().currency;

        let extra_margin = up.positions.get(&position_key).unwrap().extra_margin;
        if extra_margin > 0 {
            let refund = arithmetic::size_price_to_currency_scale(
                extra_margin,
                spec.base_scale_k,
                spec.quote_scale_k,
                currency_spec.currency_scale_k,
            );
            up.add_to_account(currency, refund);
            Self::push_futures_event(fund_events, last_price_cache, FundEventType::MarginRefund, order_id, up.positions.get(&position_key).unwrap(), spec, up, ssp);
            up.positions.get_mut(&position_key).unwrap().extra_margin = 0;
        }

        let profit = up.positions.get(&position_key).unwrap().profit;
        if profit != 0 {
            let profit_scaled = arithmetic::size_price_to_currency_scale(
                profit,
                spec.base_scale_k,
                spec.quote_scale_k,
                currency_spec.currency_scale_k,
            );
            up.add_to_account(currency, profit_scaled);
            Self::push_futures_event(fund_events, last_price_cache, FundEventType::PnlSettlement, order_id, up.positions.get(&position_key).unwrap(), spec, up, ssp);
        }
        up.positions.remove(&position_key);
    }

    #[allow(clippy::too_many_arguments)]
    pub(crate) fn push_spot_balance_event(
        cmd: &mut OrderCommand,
        ups: &UserProfileService,
        ssp: &SymbolSpecificationProvider,
        event_type: FundEventType,
        order_id: i64,
        uid: i64,
        currency: i32,
        symbol_id: i32,
    ) {
        let Some(cspec) = ssp.get_currency(currency) else { return };
        let Some(up) = ups.get(uid) else { return };
        cmd.fund_events.push(Self::spot_snapshot_event(event_type, order_id, up, currency, ssp, cspec, symbol_id));
    }

    fn normalize_cmd_position_size(cmd: &mut OrderCommand, ups: &UserProfileService) -> CommandResultCode {
        let action = match cmd.action {
            Some(a) => a,
            None => return CommandResultCode::Success,
        };
        let profile = match ups.get(cmd.uid) {
            Some(p) => p,
            None => return CommandResultCode::AuthInvalidUser,
        };
        let key = profile.create_positions_key(cmd.symbol, action, cmd.command);
        let Some(position) = profile.positions.get(&key) else {
            cmd.size = 0;
            return CommandResultCode::Success;
        };
        cmd.size = cmd.size.min(position.open_volume);
        CommandResultCode::ValidForMatchingEngine
    }

    fn advance_liquidation_for(engine: &mut LiquidationEngine, cmd: &OrderCommand, ups: &mut UserProfileService) {
        let action = match cmd.action {
            Some(a) => a,
            None => return,
        };
        let key = match ups.get(cmd.uid) {
            Some(u) => u.create_positions_key(cmd.symbol, action, cmd.command),
            None => return,
        };
        if let Some(u) = ups.get_mut(cmd.uid) {
            if let Some(pos) = u.positions.get_mut(&key) {
                engine.advance_liquidation(cmd, pos);
            }
        }
    }

}

use crate::core::exchange_core::ExchangeCore;
use crate::core::snapshot::chronicle_reader::{ChronicleError as SnapChronicleError, ChronicleReader as SnapChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter as SnapChronicleWriter;
use crate::core::snapshot::marshalling::{to_btree_i32 as snap_to_btree_i32, ChronicleMarshallable};

pub fn write_risk_engine_payload(core: &ExchangeCore) -> Vec<u8> {
    let mut w = SnapChronicleWriter::new();
    w.write_i32(0);
    w.write_i64(0);
    core.ssp.chronicle_write(&mut w);
    core.ups.chronicle_write(&mut w);
    let risk = &core.risk;
    risk.liquidation_service.chronicle_write(&mut w);
    risk.loan_service.chronicle_write(&mut w);
    risk.binary_cmd.chronicle_write(&mut w);
    w.write_int_keyed_map(&risk.last_price_cache, |vw, v| v.chronicle_write(vw));
    w.write_int_long_map(&risk.fees);
    w.write_int_long_map(&risk.adjustments);
    w.write_int_long_map(&risk.suspends);
    w.into_bytes()
}

pub fn read_risk_engine_payload(payload: &[u8], core: &mut ExchangeCore) -> Result<(), SnapChronicleError> {
    let mut r = SnapChronicleReader::new(payload);
    let _shard_id = r.read_i32()?;
    let _shard_mask = r.read_i64()?;
    core.ssp = SymbolSpecificationProvider::chronicle_read(&mut r)?;
    core.ups = UserProfileService::chronicle_read(&mut r)?;
    core.risk.liquidation_service = LiquidationService::chronicle_read(&mut r)?;
    core.risk.loan_service = LoanService::chronicle_read(&mut r)?;
    core.risk.binary_cmd = BinaryCommandsProcessor::chronicle_read(&mut r)?;
    core.risk.last_price_cache = snap_to_btree_i32(r.read_int_keyed_map(LastPriceCacheRecord::chronicle_read)?);
    core.risk.fees = snap_to_btree_i32(r.read_int_long_map()?);
    core.risk.adjustments = snap_to_btree_i32(r.read_int_long_map()?);
    core.risk.suspends = snap_to_btree_i32(r.read_int_long_map()?);
    debug_assert!(r.is_empty(), "RE payload not fully consumed; field layout may have drifted");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::cmd::order_command::OrderCommand;
    use crate::core::common::cmd::order_command_type::OrderCommandType;
    use crate::core::common::symbol_type::SymbolType;

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;
    const UID: i64 = 7;

    fn spec_with_fee(taker_fee: i64, fee_scale_k: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 100,
            quote_scale_k: 1_000_000,
            taker_fee,
            maker_fee: 0,
            fee_scale_k,
            ..Default::default()
        }
    }

    fn setup(
        taker_fee: i64,
        fee_scale_k: i64,
        quote_balance: i64,
        base_balance: i64,
    ) -> (UserProfileService, SymbolSpecificationProvider) {
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(
            ssp.add_symbol(spec_with_fee(taker_fee, fee_scale_k)),
            CommandResultCode::Success
        );
        ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 100, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification {
            currency: QUOTE,
            currency_scale_k: 1_000_000, ..Default::default()
        });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        let p = ups.get_mut(UID).unwrap();
        p.add_to_account(QUOTE, quote_balance);
        p.add_to_account(BASE, base_balance);
        (ups, ssp)
    }

    fn bid_cmd(size: i64, price: i64, reserve_bid_price: i64, order_type: OrderType) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: SYMBOL,
            price,
            size,
            reserve_bid_price,
            action: Some(OrderAction::Bid),
            order_type: Some(order_type),
            uid: UID,
            ..Default::default()
        }
    }

    fn ask_cmd(size: i64, price: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: SYMBOL,
            price,
            size,
            reserve_bid_price: 0,
            action: Some(OrderAction::Ask),
            order_type: Some(OrderType::Gtc),
            uid: UID,
            ..Default::default()
        }
    }

    #[test]
    fn bid_limit_order_sufficient_balance_locks_notional_plus_fixed_fee() {
        let (mut ups, ssp) = setup(2, 0, 1_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 50, OrderType::Gtc);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        let raw = arithmetic::calculate_amount_bid_taker_fee(1000, 50, 2, 0);
        let expected = arithmetic::size_price_to_currency_scale(raw, 100, 1_000_000, 1_000_000);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(QUOTE), expected);
        assert_eq!(p.account(QUOTE), 1_000_000, "accounts 不动");
    }

    #[test]
    fn accepted_spot_place_order_emits_locked_fund_event() {
        let (mut ups, ssp) = setup(2, 0, 1_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 50, OrderType::Gtc);

        engine.pre_process_command(&mut cmd, &mut ups, &ssp);

        assert_eq!(cmd.result_code, Some(CommandResultCode::ValidForMatchingEngine));
        assert!(
            cmd.fund_events.iter().any(|e| e.event_type == FundEventType::Locked),
            "被接受的现货下单必须发 Locked 资金事件"
        );
    }

    #[test]
    fn bid_limit_order_insufficient_balance_returns_nsf_and_locks_nothing() {
        let (mut ups, ssp) = setup(2, 0, 100, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 50, OrderType::Gtc);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskNsf);
        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(QUOTE), 0);
        assert_eq!(p.account(QUOTE), 100);
    }

    #[test]
    fn bid_limit_order_reserve_less_than_price_returns_invalid_reserve_price() {
        let (mut ups, ssp) = setup(2, 0, 1_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 49, OrderType::Gtc);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskInvalidReserveBidPrice);
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), 0);
    }

    #[test]
    fn bid_limit_order_proportional_fee_locks_notional_plus_ceil_fee() {
        let (mut ups, ssp) = setup(500, 1_000_000, 1_000_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 60, OrderType::Gtc);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        let raw = arithmetic::calculate_amount_bid_taker_fee(1000, 60, 500, 1_000_000);
        let expected = arithmetic::size_price_to_currency_scale(raw, 100, 1_000_000, 1_000_000);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), expected);
    }

    #[test]
    fn bid_budget_order_reserve_equals_price_locks_budget_plus_fee() {
        let (mut ups, ssp) = setup(500, 1_000_000, 1_000_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 60_000, 60_000, OrderType::FokBudget);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        let raw = arithmetic::calculate_amount_bid_taker_fee_for_budget(1000, 60_000, 500, 1_000_000);
        let expected = arithmetic::size_price_to_currency_scale(raw, 100, 1_000_000, 1_000_000);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), expected);
    }

    #[test]
    fn bid_budget_order_reserve_mismatch_returns_invalid_reserve_price() {
        let (mut ups, ssp) = setup(500, 1_000_000, 1_000_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 60_000, 60_001, OrderType::IocBudget);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskInvalidReserveBidPrice);
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), 0);
    }

    #[test]
    fn ask_order_locks_base_size_scaled_fixed_fee() {
        let (mut ups, ssp) = setup(2, 0, 0, 1_000_000);
        let mut engine = RiskEngine::new();
        let mut cmd = ask_cmd(1000, 50);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        let raw = arithmetic::calculate_amount_ask(1000);
        let expected = arithmetic::symbol_to_currency_scale(raw, 100, 100);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(BASE), expected);
        assert_eq!(p.account(BASE), 1_000_000, "accounts 不动");
    }

    #[test]
    fn ask_order_locks_base_size_scaled_proportional_fee() {
        let (mut ups, ssp) = setup(500, 1_000_000, 0, 1_000_000);
        let mut engine = RiskEngine::new();
        let mut cmd = ask_cmd(1000, 50);

        cmd.price = 2000;
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        let raw = arithmetic::calculate_amount_ask(1000);
        let expected = arithmetic::symbol_to_currency_scale(raw, 100, 100);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        assert_eq!(ups.get(UID).unwrap().locked(BASE), expected);
    }

    #[test]
    fn ask_price_too_low_fixed_fee_returns_error() {
        let (mut ups, ssp) = setup(5, 0, 0, 1_000_000);
        let mut engine = RiskEngine::new();
        let mut cmd = ask_cmd(1000, 1);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskAskPriceLowerThanFee);
        assert_eq!(ups.get(UID).unwrap().locked(BASE), 0);
    }

    #[test]
    fn ask_price_too_low_proportional_fee_returns_error() {
        let (mut ups, ssp) = setup(500, 1_000_000, 0, 1_000_000);
        let mut engine = RiskEngine::new();
        let mut cmd = ask_cmd(1000, 1999);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskAskPriceLowerThanFee);
        assert_eq!(ups.get(UID).unwrap().locked(BASE), 0);
    }

    #[test]
    fn ask_order_insufficient_base_balance_returns_nsf() {
        let (mut ups, ssp) = setup(2, 0, 0, 10);
        let mut engine = RiskEngine::new();
        let mut cmd = ask_cmd(1000, 50);

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskNsf);
        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(BASE), 0);
        assert_eq!(p.account(BASE), 10);
    }

    #[test]
    fn auth_invalid_user_when_profile_missing() {
        let (mut ups, ssp) = setup(2, 0, 1_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 50, OrderType::Gtc);
        cmd.uid = 999;

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::AuthInvalidUser);
    }

    #[test]
    fn invalid_symbol_when_spec_missing() {
        let (mut ups, ssp) = setup(2, 0, 1_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 50, OrderType::Gtc);
        cmd.symbol = 999;

        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::InvalidSymbol);
    }

    fn reject_or_reduce_event(
        event_type: MatcherEventType,
        size: i64,
        price: i64,
        bidder_hold_price: i64,
        next: Option<Box<MatcherTradeEvent>>,
    ) -> Box<MatcherTradeEvent> {
        Box::new(MatcherTradeEvent {
            event_type,
            active_order_completed: false,
            maker_order_id: 0,
            maker_order_completed: false,
            price,
            size,
            bid_gt_ask: false,
            bidder_hold_price,
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
            next,
        })
    }

    #[test]
    fn bid_plain_limit_pure_reject_releases_full_lock_and_leaves_accounts_untouched() {
        let (mut ups, ssp) = setup(2, 0, 1_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 50, 50, OrderType::Gtc);
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let locked_after_place = ups.get(UID).unwrap().locked(QUOTE);
        assert!(locked_after_place > 0, "前置条件：下单必须产生非零冻结");

        cmd.matcher_event =
            Some(reject_or_reduce_event(MatcherEventType::Reject, 1000, 50, 50, None));

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(QUOTE), 0, "纯 REJECT 应把冻结全额释放回 0");
        assert_eq!(p.account(QUOTE), 1_000_000, "accounts 不动");
        assert_eq!(p.account(BASE), 0, "accounts 不动");
        assert!(cmd.matcher_event.is_some(), "R2 只读不消费，链保留供事件处理");
    }

    #[test]
    fn ask_order_reduce_remainder_releases_partial_lock_and_leaves_accounts_untouched() {
        let (mut ups, ssp) = setup(2, 0, 0, 1_000_000);
        let mut engine = RiskEngine::new();
        let mut cmd = ask_cmd(1000, 50);
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let locked_after_place = ups.get(UID).unwrap().locked(BASE);
        assert_eq!(locked_after_place, 1000);

        cmd.matcher_event =
            Some(reject_or_reduce_event(MatcherEventType::Reduce, 300, 50, 0, None));

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        let p = ups.get(UID).unwrap();
        assert_eq!(p.locked(BASE), locked_after_place - 300, "REDUCE 只释放剩余量对应的锁定");
        assert_eq!(p.account(BASE), 1_000_000, "accounts 不动");
        assert_eq!(p.account(QUOTE), 0, "accounts 不动");
        assert!(cmd.matcher_event.is_some(), "R2 只读不消费，链保留供事件处理");
    }

    #[test]
    fn bid_ioc_budget_full_reject_no_prior_trade_releases_full_budget() {
        let (mut ups, ssp) = setup(500, 1_000_000, 1_000_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 60_000, 60_000, OrderType::IocBudget);
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let locked_after_place = ups.get(UID).unwrap().locked(QUOTE);
        assert!(locked_after_place > 0);

        cmd.matcher_event =
            Some(reject_or_reduce_event(MatcherEventType::Reject, 1000, 60_000, 0, None));

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), 0, "全拒应释放整份预算冻结");
    }

    #[test]
    fn bid_ioc_budget_partial_fill_then_reduce_releases_zero_to_avoid_double_release() {
        let (mut ups, ssp) = setup(500, 1_000_000, 1_000_000_000, 0);
        let mut engine = RiskEngine::new();
        let mut cmd = bid_cmd(1000, 60_000, 60_000, OrderType::IocBudget);
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        let locked_after_place = ups.get(UID).unwrap().locked(QUOTE);
        assert!(locked_after_place > 0);

        let trailing_trade =
            reject_or_reduce_event(MatcherEventType::Trade, 400, 55, 60_000, None);
        cmd.matcher_event = Some(reject_or_reduce_event(
            MatcherEventType::Reduce,
            600,
            60_000,
            0,
            Some(trailing_trade),
        ));

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        assert_eq!(
            ups.get(UID).unwrap().locked(QUOTE),
            0,
            "REDUCE 不重复释放 + buy 结算全额释放 held_total，taker quote 冻结应归零"
        );
        assert!(cmd.matcher_event.is_some(), "R2 只读不消费，TRADE 链保留供事件处理");
    }

    const BUYER1: i64 = 8;
    const BUYER2: i64 = 9;

    fn spec_with_fees(taker_fee: i64, maker_fee: i64, fee_scale_k: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 100,
            quote_scale_k: 1_000_000,
            taker_fee,
            maker_fee,
            fee_scale_k,
            ..Default::default()
        }
    }

    fn setup_sell(
        taker_fee: i64,
        maker_fee: i64,
        fee_scale_k: i64,
        seller_base_balance: i64,
        buyers_quote_balance: &[(i64, i64)],
    ) -> (UserProfileService, SymbolSpecificationProvider) {
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(
            ssp.add_symbol(spec_with_fees(taker_fee, maker_fee, fee_scale_k)),
            CommandResultCode::Success
        );
        ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 100, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification {
            currency: QUOTE,
            currency_scale_k: 100 * 1_000_000, ..Default::default()
        });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        ups.get_mut(UID).unwrap().add_to_account(BASE, seller_base_balance);
        for &(uid, quote_balance) in buyers_quote_balance {
            assert_eq!(ups.add_empty_user_profile(uid), CommandResultCode::Success);
            ups.get_mut(uid).unwrap().add_to_account(QUOTE, quote_balance);
        }
        (ups, ssp)
    }

    fn trade_event(
        size: i64,
        price: i64,
        bidder_hold_price: i64,
        matched_order_uid: i64,
        next: Option<Box<MatcherTradeEvent>>,
    ) -> Box<MatcherTradeEvent> {
        Box::new(MatcherTradeEvent {
            event_type: MatcherEventType::Trade,
            active_order_completed: false,
            maker_order_id: 0,
            maker_order_completed: false,
            price,
            size,
            bid_gt_ask: false,
            bidder_hold_price,
            matched_order_uid,
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
            next,
        })
    }

    fn assert_conserved(
        base_deltas: &[i64],
        quote_deltas: &[i64],
        fees_quote_delta: i64,
    ) {
        let base_sum: i64 = base_deltas.iter().sum();
        assert_eq!(base_sum, 0, "base 腿必须逐笔精确守恒（无 fee）");
        let quote_sum: i64 = quote_deltas.iter().sum();
        assert_eq!(quote_sum + fees_quote_delta, 0, "quote 腿守恒 modulo fees[quote]");
    }

    #[test]
    fn sell_single_maker_fixed_fee_price_improvement_refund_and_conservation() {
        let (mut ups, ssp) = setup_sell(3, 1, 0, 1_000_000, &[(BUYER1, 1_000_000)]);
        let mut engine = RiskEngine::new();

        let mut seller_cmd = ask_cmd(1000, 50);
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        assert_eq!(ups.get(UID).unwrap().locked(BASE), 1000);

        let mut buyer_cmd = bid_cmd(1000, 55, 55, OrderType::Gtc);
        buyer_cmd.uid = BUYER1;
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let hold_quote = ups.get(BUYER1).unwrap().locked(QUOTE);
        assert_eq!(hold_quote, 58_000, "58000 = size*holdPrice(55000) + size*takerFee(3000)");

        let seller_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let seller_base_before = ups.get(UID).unwrap().account(BASE);
        let buyer_quote_before = ups.get(BUYER1).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(BUYER1).unwrap().account(BASE);

        seller_cmd.matcher_event = Some(trade_event(1000, 50, 55, BUYER1, None));
        engine.handler_risk_release(&mut seller_cmd, &mut ups, &ssp);

        let buyer = ups.get(BUYER1).unwrap();
        assert_eq!(buyer.locked(QUOTE), 0, "maker quote 冻结应全额释放");
        assert_eq!(buyer.account(QUOTE) - buyer_quote_before, -51_000);
        assert_eq!(buyer.account(BASE) - buyer_base_before, 1000);

        let seller = ups.get(UID).unwrap();
        assert_eq!(seller.locked(BASE), 0, "taker base 冻结应全额释放");
        assert_eq!(seller.account(BASE) - seller_base_before, -1000);
        assert_eq!(seller.account(QUOTE) - seller_quote_before, 47_000);

        assert_eq!(*engine.fees.get(&QUOTE).unwrap(), 4000);

        assert!(seller_cmd.matcher_event.is_some(), "R2 只读不消费，TRADE 链保留供事件处理");

        assert_conserved(&[-1000, 1000], &[47_000, -51_000], 4000);
    }

    #[test]
    fn sell_single_maker_proportional_fee_and_conservation() {
        let (mut ups, ssp) = setup_sell(100, 20, 10_000, 1_000_000, &[(BUYER1, 1_000_000_000)]);
        let mut engine = RiskEngine::new();

        let mut seller_cmd = ask_cmd(1000, 2000);
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let mut buyer_cmd = bid_cmd(1000, 60, 60, OrderType::Gtc);
        buyer_cmd.uid = BUYER1;
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        assert_eq!(ups.get(BUYER1).unwrap().locked(QUOTE), 60_600);

        let seller_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let seller_base_before = ups.get(UID).unwrap().account(BASE);
        let buyer_quote_before = ups.get(BUYER1).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(BUYER1).unwrap().account(BASE);

        seller_cmd.matcher_event = Some(trade_event(1000, 50, 60, BUYER1, None));
        engine.handler_risk_release(&mut seller_cmd, &mut ups, &ssp);

        let buyer = ups.get(BUYER1).unwrap();
        assert_eq!(buyer.locked(QUOTE), 0);
        assert_eq!(buyer.account(QUOTE) - buyer_quote_before, -50_100);
        assert_eq!(buyer.account(BASE) - buyer_base_before, 1000);

        let seller = ups.get(UID).unwrap();
        assert_eq!(seller.locked(BASE), 0);
        assert_eq!(seller.account(BASE) - seller_base_before, -1000);
        assert_eq!(seller.account(QUOTE) - seller_quote_before, 49_500);

        assert_eq!(*engine.fees.get(&QUOTE).unwrap(), 600);

        assert_conserved(&[-1000, 1000], &[49_500, -50_100], 600);
    }

    #[test]
    fn sell_two_makers_fixed_fee_avg_price_platform_fee_and_conservation() {
        let (mut ups, ssp) = setup_sell(
            3,
            1,
            0,
            1_000_000,
            &[(BUYER1, 1_000_000), (BUYER2, 1_000_000)],
        );
        let mut engine = RiskEngine::new();

        let mut seller_cmd = ask_cmd(2000, 50);
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let mut buyer1_cmd = bid_cmd(1000, 55, 55, OrderType::Gtc);
        buyer1_cmd.uid = BUYER1;
        assert_eq!(
            engine.place_order_risk_check(&mut buyer1_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let mut buyer2_cmd = bid_cmd(1000, 60, 60, OrderType::Gtc);
        buyer2_cmd.uid = BUYER2;
        assert_eq!(
            engine.place_order_risk_check(&mut buyer2_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let seller_base_before = ups.get(UID).unwrap().account(BASE);
        let seller_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer1_quote_before = ups.get(BUYER1).unwrap().account(QUOTE);
        let buyer1_base_before = ups.get(BUYER1).unwrap().account(BASE);
        let buyer2_quote_before = ups.get(BUYER2).unwrap().account(QUOTE);
        let buyer2_base_before = ups.get(BUYER2).unwrap().account(BASE);

        let event2 = trade_event(1000, 60, 60, BUYER2, None);
        seller_cmd.matcher_event = Some(trade_event(1000, 50, 55, BUYER1, Some(event2)));
        engine.handler_risk_release(&mut seller_cmd, &mut ups, &ssp);

        let buyer1 = ups.get(BUYER1).unwrap();
        assert_eq!(buyer1.locked(QUOTE), 0);
        let buyer1_quote_delta = buyer1.account(QUOTE) - buyer1_quote_before;
        assert_eq!(buyer1_quote_delta, -51_000);
        let buyer1_base_delta = buyer1.account(BASE) - buyer1_base_before;
        assert_eq!(buyer1_base_delta, 1000);

        let buyer2 = ups.get(BUYER2).unwrap();
        assert_eq!(buyer2.locked(QUOTE), 0);
        let buyer2_quote_delta = buyer2.account(QUOTE) - buyer2_quote_before;
        assert_eq!(buyer2_quote_delta, -61_000);
        let buyer2_base_delta = buyer2.account(BASE) - buyer2_base_before;
        assert_eq!(buyer2_base_delta, 1000);

        let seller = ups.get(UID).unwrap();
        assert_eq!(seller.locked(BASE), 0);
        let seller_base_delta = seller.account(BASE) - seller_base_before;
        assert_eq!(seller_base_delta, -2000);
        let seller_quote_delta = seller.account(QUOTE) - seller_quote_before;
        assert_eq!(seller_quote_delta, 104_000);

        let fees_delta = *engine.fees.get(&QUOTE).unwrap();
        assert_eq!(fees_delta, 8000);

        assert_conserved(
            &[seller_base_delta, buyer1_base_delta, buyer2_base_delta],
            &[seller_quote_delta, buyer1_quote_delta, buyer2_quote_delta],
            fees_delta,
        );
    }

    #[test]
    fn sell_two_makers_proportional_fee_avg_price_platform_fee_and_conservation() {
        let (mut ups, ssp) = setup_sell(
            100,
            20,
            10_000,
            1_000_000,
            &[(BUYER1, 1_000_000_000), (BUYER2, 1_000_000_000)],
        );
        let mut engine = RiskEngine::new();

        let mut seller_cmd = ask_cmd(2000, 2000);
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let mut buyer1_cmd = bid_cmd(1000, 40, 40, OrderType::Gtc);
        buyer1_cmd.uid = BUYER1;
        assert_eq!(
            engine.place_order_risk_check(&mut buyer1_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let mut buyer2_cmd = bid_cmd(1000, 60, 60, OrderType::Gtc);
        buyer2_cmd.uid = BUYER2;
        assert_eq!(
            engine.place_order_risk_check(&mut buyer2_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let seller_base_before = ups.get(UID).unwrap().account(BASE);
        let seller_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer1_quote_before = ups.get(BUYER1).unwrap().account(QUOTE);
        let buyer1_base_before = ups.get(BUYER1).unwrap().account(BASE);
        let buyer2_quote_before = ups.get(BUYER2).unwrap().account(QUOTE);
        let buyer2_base_before = ups.get(BUYER2).unwrap().account(BASE);

        let event2 = trade_event(1000, 60, 60, BUYER2, None);
        seller_cmd.matcher_event = Some(trade_event(1000, 40, 40, BUYER1, Some(event2)));
        engine.handler_risk_release(&mut seller_cmd, &mut ups, &ssp);

        let buyer1 = ups.get(BUYER1).unwrap();
        assert_eq!(buyer1.locked(QUOTE), 0);
        let buyer1_quote_delta = buyer1.account(QUOTE) - buyer1_quote_before;
        assert_eq!(buyer1_quote_delta, -40_080);
        let buyer1_base_delta = buyer1.account(BASE) - buyer1_base_before;
        assert_eq!(buyer1_base_delta, 1000);

        let buyer2 = ups.get(BUYER2).unwrap();
        assert_eq!(buyer2.locked(QUOTE), 0);
        let buyer2_quote_delta = buyer2.account(QUOTE) - buyer2_quote_before;
        assert_eq!(buyer2_quote_delta, -60_120);
        let buyer2_base_delta = buyer2.account(BASE) - buyer2_base_before;
        assert_eq!(buyer2_base_delta, 1000);

        let seller = ups.get(UID).unwrap();
        assert_eq!(seller.locked(BASE), 0);
        let seller_base_delta = seller.account(BASE) - seller_base_before;
        assert_eq!(seller_base_delta, -2000);
        let seller_quote_delta = seller.account(QUOTE) - seller_quote_before;
        assert_eq!(seller_quote_delta, 99_000);

        let fees_delta = *engine.fees.get(&QUOTE).unwrap();
        assert_eq!(fees_delta, 1200);

        assert_conserved(
            &[seller_base_delta, buyer1_base_delta, buyer2_base_delta],
            &[seller_quote_delta, buyer1_quote_delta, buyer2_quote_delta],
            fees_delta,
        );
    }

    const SELLER1: i64 = 10;
    const SELLER2: i64 = 11;

    fn setup_buy(
        taker_fee: i64,
        maker_fee: i64,
        fee_scale_k: i64,
        taker_quote_balance: i64,
        sellers_base_balance: &[(i64, i64)],
    ) -> (UserProfileService, SymbolSpecificationProvider) {
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(
            ssp.add_symbol(spec_with_fees(taker_fee, maker_fee, fee_scale_k)),
            CommandResultCode::Success
        );
        ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 100, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification {
            currency: QUOTE,
            currency_scale_k: 100 * 1_000_000, ..Default::default()
        });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        ups.get_mut(UID).unwrap().add_to_account(QUOTE, taker_quote_balance);
        for &(uid, base_balance) in sellers_base_balance {
            assert_eq!(ups.add_empty_user_profile(uid), CommandResultCode::Success);
            ups.get_mut(uid).unwrap().add_to_account(BASE, base_balance);
        }
        (ups, ssp)
    }

    #[test]
    fn buy_single_maker_fixed_fee_price_improvement_refund_and_conservation() {
        let (mut ups, ssp) = setup_buy(3, 1, 0, 1_000_000, &[(SELLER1, 1_000_000)]);
        let mut engine = RiskEngine::new();

        let mut seller_cmd = ask_cmd(1000, 50);
        seller_cmd.uid = SELLER1;
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        assert_eq!(ups.get(SELLER1).unwrap().locked(BASE), 1000);

        let mut buyer_cmd = bid_cmd(1000, 55, 55, OrderType::Gtc);
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let hold_quote = ups.get(UID).unwrap().locked(QUOTE);
        assert_eq!(hold_quote, 58_000, "58000 = size*holdPrice(55000) + size*takerFee(3000)");

        let buyer_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(UID).unwrap().account(BASE);
        let seller_quote_before = ups.get(SELLER1).unwrap().account(QUOTE);
        let seller_base_before = ups.get(SELLER1).unwrap().account(BASE);

        buyer_cmd.matcher_event = Some(trade_event(1000, 50, 55, SELLER1, None));
        engine.handler_risk_release(&mut buyer_cmd, &mut ups, &ssp);

        let seller = ups.get(SELLER1).unwrap();
        assert_eq!(seller.locked(BASE), 0, "maker base 冻结应全额释放");
        assert_eq!(seller.account(BASE) - seller_base_before, -1000);
        assert_eq!(seller.account(QUOTE) - seller_quote_before, 49_000);

        let buyer = ups.get(UID).unwrap();
        assert_eq!(buyer.locked(QUOTE), 0, "taker quote 冻结应全额释放");
        assert_eq!(buyer.account(QUOTE) - buyer_quote_before, -53_000);
        assert_eq!(buyer.account(BASE) - buyer_base_before, 1000);

        assert_eq!(*engine.fees.get(&QUOTE).unwrap(), 4000);

        assert!(buyer_cmd.matcher_event.is_some(), "R2 只读不消费，TRADE 链保留供事件处理");

        assert_conserved(&[1000, -1000], &[-53_000, 49_000], 4000);
    }

    #[test]
    fn buy_single_maker_proportional_fee_and_conservation() {
        let (mut ups, ssp) = setup_buy(100, 20, 10_000, 1_000_000_000, &[(SELLER1, 1_000_000)]);
        let mut engine = RiskEngine::new();

        let mut seller_cmd = ask_cmd(1000, 2000);
        seller_cmd.uid = SELLER1;
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let mut buyer_cmd = bid_cmd(1000, 60, 60, OrderType::Gtc);
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), 60_600);

        let buyer_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(UID).unwrap().account(BASE);
        let seller_quote_before = ups.get(SELLER1).unwrap().account(QUOTE);
        let seller_base_before = ups.get(SELLER1).unwrap().account(BASE);

        buyer_cmd.matcher_event = Some(trade_event(1000, 50, 60, SELLER1, None));
        engine.handler_risk_release(&mut buyer_cmd, &mut ups, &ssp);

        let seller = ups.get(SELLER1).unwrap();
        assert_eq!(seller.locked(BASE), 0);
        assert_eq!(seller.account(BASE) - seller_base_before, -1000);
        assert_eq!(seller.account(QUOTE) - seller_quote_before, 49_900);

        let buyer = ups.get(UID).unwrap();
        assert_eq!(buyer.locked(QUOTE), 0);
        assert_eq!(buyer.account(QUOTE) - buyer_quote_before, -50_500);
        assert_eq!(buyer.account(BASE) - buyer_base_before, 1000);

        assert_eq!(*engine.fees.get(&QUOTE).unwrap(), 600);

        assert_conserved(&[1000, -1000], &[-50_500, 49_900], 600);
    }

    #[test]
    fn buy_two_makers_fixed_fee_avg_price_platform_fee_and_conservation() {
        let (mut ups, ssp) = setup_buy(
            3,
            1,
            0,
            1_000_000,
            &[(SELLER1, 1_000_000), (SELLER2, 1_000_000)],
        );
        let mut engine = RiskEngine::new();

        let mut seller1_cmd = ask_cmd(1000, 50);
        seller1_cmd.uid = SELLER1;
        assert_eq!(
            engine.place_order_risk_check(&mut seller1_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let mut seller2_cmd = ask_cmd(1000, 60);
        seller2_cmd.uid = SELLER2;
        assert_eq!(
            engine.place_order_risk_check(&mut seller2_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let mut buyer_cmd = bid_cmd(2000, 60, 60, OrderType::Gtc);
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let buyer_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(UID).unwrap().account(BASE);
        let seller1_quote_before = ups.get(SELLER1).unwrap().account(QUOTE);
        let seller1_base_before = ups.get(SELLER1).unwrap().account(BASE);
        let seller2_quote_before = ups.get(SELLER2).unwrap().account(QUOTE);
        let seller2_base_before = ups.get(SELLER2).unwrap().account(BASE);

        let event2 = trade_event(1000, 60, 60, SELLER2, None);
        buyer_cmd.matcher_event = Some(trade_event(1000, 50, 60, SELLER1, Some(event2)));
        engine.handler_risk_release(&mut buyer_cmd, &mut ups, &ssp);

        let seller1 = ups.get(SELLER1).unwrap();
        assert_eq!(seller1.locked(BASE), 0);
        let seller1_quote_delta = seller1.account(QUOTE) - seller1_quote_before;
        assert_eq!(seller1_quote_delta, 49_000);
        let seller1_base_delta = seller1.account(BASE) - seller1_base_before;
        assert_eq!(seller1_base_delta, -1000);

        let seller2 = ups.get(SELLER2).unwrap();
        assert_eq!(seller2.locked(BASE), 0);
        let seller2_quote_delta = seller2.account(QUOTE) - seller2_quote_before;
        assert_eq!(seller2_quote_delta, 59_000);
        let seller2_base_delta = seller2.account(BASE) - seller2_base_before;
        assert_eq!(seller2_base_delta, -1000);

        let buyer = ups.get(UID).unwrap();
        assert_eq!(buyer.locked(QUOTE), 0);
        let buyer_quote_delta = buyer.account(QUOTE) - buyer_quote_before;
        assert_eq!(buyer_quote_delta, -116_000);
        let buyer_base_delta = buyer.account(BASE) - buyer_base_before;
        assert_eq!(buyer_base_delta, 2000);

        let fees_delta = *engine.fees.get(&QUOTE).unwrap();
        assert_eq!(fees_delta, 8000);

        assert_conserved(
            &[buyer_base_delta, seller1_base_delta, seller2_base_delta],
            &[buyer_quote_delta, seller1_quote_delta, seller2_quote_delta],
            fees_delta,
        );
    }

    #[test]
    fn buy_two_makers_proportional_fee_avg_price_platform_fee_and_conservation() {
        let (mut ups, ssp) = setup_buy(
            100,
            20,
            10_000,
            1_000_000_000,
            &[(SELLER1, 1_000_000), (SELLER2, 1_000_000)],
        );
        let mut engine = RiskEngine::new();

        let mut seller1_cmd = ask_cmd(1000, 2000);
        seller1_cmd.uid = SELLER1;
        assert_eq!(
            engine.place_order_risk_check(&mut seller1_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let mut seller2_cmd = ask_cmd(1000, 2000);
        seller2_cmd.uid = SELLER2;
        assert_eq!(
            engine.place_order_risk_check(&mut seller2_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let mut buyer_cmd = bid_cmd(2000, 60, 60, OrderType::Gtc);
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let buyer_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(UID).unwrap().account(BASE);
        let seller1_quote_before = ups.get(SELLER1).unwrap().account(QUOTE);
        let seller1_base_before = ups.get(SELLER1).unwrap().account(BASE);
        let seller2_quote_before = ups.get(SELLER2).unwrap().account(QUOTE);
        let seller2_base_before = ups.get(SELLER2).unwrap().account(BASE);

        let event2 = trade_event(1000, 60, 60, SELLER2, None);
        buyer_cmd.matcher_event = Some(trade_event(1000, 40, 60, SELLER1, Some(event2)));
        engine.handler_risk_release(&mut buyer_cmd, &mut ups, &ssp);

        let seller1 = ups.get(SELLER1).unwrap();
        assert_eq!(seller1.locked(BASE), 0);
        let seller1_quote_delta = seller1.account(QUOTE) - seller1_quote_before;
        assert_eq!(seller1_quote_delta, 39_920);
        let seller1_base_delta = seller1.account(BASE) - seller1_base_before;
        assert_eq!(seller1_base_delta, -1000);

        let seller2 = ups.get(SELLER2).unwrap();
        assert_eq!(seller2.locked(BASE), 0);
        let seller2_quote_delta = seller2.account(QUOTE) - seller2_quote_before;
        assert_eq!(seller2_quote_delta, 59_880);
        let seller2_base_delta = seller2.account(BASE) - seller2_base_before;
        assert_eq!(seller2_base_delta, -1000);

        let buyer = ups.get(UID).unwrap();
        assert_eq!(buyer.locked(QUOTE), 0);
        let buyer_quote_delta = buyer.account(QUOTE) - buyer_quote_before;
        assert_eq!(buyer_quote_delta, -101_000);
        let buyer_base_delta = buyer.account(BASE) - buyer_base_before;
        assert_eq!(buyer_base_delta, 2000);

        let fees_delta = *engine.fees.get(&QUOTE).unwrap();
        assert_eq!(fees_delta, 1200);

        assert_conserved(
            &[buyer_base_delta, seller1_base_delta, seller2_base_delta],
            &[buyer_quote_delta, seller1_quote_delta, seller2_quote_delta],
            fees_delta,
        );
    }

    #[test]
    fn buy_ioc_budget_partial_fill_releases_full_held_total_matching_task5_assumption() {
        let (mut ups, ssp) = setup_buy(500, 100, 1_000_000, 1_000_000_000, &[(SELLER1, 1_000_000)]);
        let mut engine = RiskEngine::new();

        let mut seller_cmd = ask_cmd(400, 2000);
        seller_cmd.uid = SELLER1;
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let mut buyer_cmd = bid_cmd(1000, 60_000, 60_000, OrderType::IocBudget);
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let held_total = arithmetic::calculate_amount_bid_taker_fee_for_budget(1000, 60_000, 500, 1_000_000);
        assert_eq!(held_total, 60_030);
        let locked_after_place = ups.get(UID).unwrap().locked(QUOTE);
        assert_eq!(locked_after_place, held_total, "下单锁定 = held_total（乘积 scale 恒等）");

        let buyer_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(UID).unwrap().account(BASE);
        let seller_quote_before = ups.get(SELLER1).unwrap().account(QUOTE);
        let seller_base_before = ups.get(SELLER1).unwrap().account(BASE);

        buyer_cmd.matcher_event = Some(trade_event(400, 50, 60_000, SELLER1, None));
        engine.handler_risk_release(&mut buyer_cmd, &mut ups, &ssp);

        let buyer = ups.get(UID).unwrap();
        assert_eq!(
            buyer.locked(QUOTE),
            0,
            "IOC_BUDGET 部分成交后 taker quote 冻结必须清零（全额释放 held_total，Task 5 依赖此前提）"
        );

        let seller = ups.get(SELLER1).unwrap();
        assert_eq!(seller.locked(BASE), 0);
        let seller_base_delta = seller.account(BASE) - seller_base_before;
        assert_eq!(seller_base_delta, -400);
        let seller_quote_delta = seller.account(QUOTE) - seller_quote_before;
        assert_eq!(seller_quote_delta, 19_998);

        let buyer_quote_delta = buyer.account(QUOTE) - buyer_quote_before;
        assert_eq!(buyer_quote_delta, -20_010);
        let buyer_base_delta = buyer.account(BASE) - buyer_base_before;
        assert_eq!(buyer_base_delta, 400);

        let fees_delta = *engine.fees.get(&QUOTE).unwrap();
        assert_eq!(fees_delta, 12);

        assert_conserved(&[buyer_base_delta, seller_base_delta], &[buyer_quote_delta, seller_quote_delta], fees_delta);
    }

    #[test]
    fn buy_fok_budget_full_fill_proportional_fee_releases_held_total_and_conservation() {
        let (mut ups, ssp) = setup_buy(500, 100, 1_000_000, 1_000_000_000, &[(SELLER1, 1_000_000)]);
        let mut engine = RiskEngine::new();

        let mut seller_cmd = ask_cmd(1000, 2000);
        seller_cmd.uid = SELLER1;
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let mut buyer_cmd = bid_cmd(1000, 60_000, 60_000, OrderType::FokBudget);
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let held_total = arithmetic::calculate_amount_bid_taker_fee_for_budget(1000, 60_000, 500, 1_000_000);
        assert_eq!(held_total, 60_030);
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), held_total);

        let buyer_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(UID).unwrap().account(BASE);
        let seller_quote_before = ups.get(SELLER1).unwrap().account(QUOTE);
        let seller_base_before = ups.get(SELLER1).unwrap().account(BASE);

        buyer_cmd.matcher_event = Some(trade_event(1000, 55, 60_000, SELLER1, None));
        engine.handler_risk_release(&mut buyer_cmd, &mut ups, &ssp);

        let seller = ups.get(SELLER1).unwrap();
        assert_eq!(seller.locked(BASE), 0);
        let seller_base_delta = seller.account(BASE) - seller_base_before;
        assert_eq!(seller_base_delta, -1000);
        let seller_quote_delta = seller.account(QUOTE) - seller_quote_before;
        assert_eq!(seller_quote_delta, 54_994);

        let buyer = ups.get(UID).unwrap();
        assert_eq!(buyer.locked(QUOTE), 0, "全额成交也应把整份预算冻结全部释放");
        let buyer_quote_delta = buyer.account(QUOTE) - buyer_quote_before;
        assert_eq!(buyer_quote_delta, -55_028);
        let buyer_base_delta = buyer.account(BASE) - buyer_base_before;
        assert_eq!(buyer_base_delta, 1000);

        let fees_delta = *engine.fees.get(&QUOTE).unwrap();
        assert_eq!(fees_delta, 34);

        assert_conserved(&[buyer_base_delta, seller_base_delta], &[buyer_quote_delta, seller_quote_delta], fees_delta);
    }

    #[test]
    fn buy_fok_budget_full_fill_fixed_fee_releases_held_total_and_conservation() {
        let (mut ups, ssp) = setup_buy(3, 1, 0, 1_000_000, &[(SELLER1, 1_000_000)]);
        let mut engine = RiskEngine::new();

        let mut seller_cmd = ask_cmd(1000, 50);
        seller_cmd.uid = SELLER1;
        assert_eq!(
            engine.place_order_risk_check(&mut seller_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let mut buyer_cmd = bid_cmd(1000, 60_000, 60_000, OrderType::FokBudget);
        assert_eq!(
            engine.place_order_risk_check(&mut buyer_cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        let held_total = arithmetic::calculate_amount_bid_taker_fee_for_budget(1000, 60_000, 3, 0);
        assert_eq!(held_total, 63_000);
        assert_eq!(ups.get(UID).unwrap().locked(QUOTE), held_total);

        let buyer_quote_before = ups.get(UID).unwrap().account(QUOTE);
        let buyer_base_before = ups.get(UID).unwrap().account(BASE);
        let seller_quote_before = ups.get(SELLER1).unwrap().account(QUOTE);
        let seller_base_before = ups.get(SELLER1).unwrap().account(BASE);

        buyer_cmd.matcher_event = Some(trade_event(1000, 55, 60_000, SELLER1, None));
        engine.handler_risk_release(&mut buyer_cmd, &mut ups, &ssp);

        let seller = ups.get(SELLER1).unwrap();
        assert_eq!(seller.locked(BASE), 0);
        let seller_base_delta = seller.account(BASE) - seller_base_before;
        assert_eq!(seller_base_delta, -1000);
        let seller_quote_delta = seller.account(QUOTE) - seller_quote_before;
        assert_eq!(seller_quote_delta, 54_000);

        let buyer = ups.get(UID).unwrap();
        assert_eq!(buyer.locked(QUOTE), 0);
        let buyer_quote_delta = buyer.account(QUOTE) - buyer_quote_before;
        assert_eq!(buyer_quote_delta, -58_000);
        let buyer_base_delta = buyer.account(BASE) - buyer_base_before;
        assert_eq!(buyer_base_delta, 1000);

        let fees_delta = *engine.fees.get(&QUOTE).unwrap();
        assert_eq!(fees_delta, 4000);

        assert_conserved(&[buyer_base_delta, seller_base_delta], &[buyer_quote_delta, seller_quote_delta], fees_delta);
    }

    fn add_user_cmd(uid: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::AddUser, uid, ..Default::default() }
    }

    fn balance_adjustment_cmd(uid: i64, currency: i32, amount: i64, order_id: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::BalanceAdjustment,
            uid,
            symbol: currency,
            price: amount,
            order_id,
            ..Default::default()
        }
    }

    #[test]
    fn add_user_creates_empty_account() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let cmd = add_user_cmd(UID);
        assert_eq!(RiskEngineCommandDispatcher::add_user(&mut engine, &cmd, &mut ups), CommandResultCode::Success);
        let profile = ups.get(UID).unwrap();
        assert_eq!(profile.account(QUOTE), 0);
        assert_eq!(profile.locked(QUOTE), 0);
    }

    #[test]
    fn add_user_rejects_duplicate_uid() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        assert_eq!(RiskEngineCommandDispatcher::add_user(&mut engine, &add_user_cmd(UID), &mut ups), CommandResultCode::Success);
        assert_eq!(
            RiskEngineCommandDispatcher::add_user(&mut engine, &add_user_cmd(UID), &mut ups),
            CommandResultCode::UserMgmtUserAlreadyExists
        );
    }

    #[test]
    fn balance_adjustment_deposit_increases_account_and_conserves_globally() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let ssp = SymbolSpecificationProvider::new();
        RiskEngineCommandDispatcher::add_user(&mut engine, &add_user_cmd(UID), &mut ups);

        let cmd = balance_adjustment_cmd(UID, QUOTE, 1000, 1);
        assert_eq!(RiskEngineCommandDispatcher::balance_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::Success);

        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 1000);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), -1000);
        assert_eq!(ups.get(UID).unwrap().account(QUOTE) + engine.adjustments.get(&QUOTE).unwrap(), 0);
    }

    #[test]
    fn balance_adjustment_withdrawal_exceeding_withdrawable_is_nsf_and_noop() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let ssp = SymbolSpecificationProvider::new();
        RiskEngineCommandDispatcher::add_user(&mut engine, &add_user_cmd(UID), &mut ups);
        RiskEngineCommandDispatcher::balance_adjustment(&mut engine, &balance_adjustment_cmd(UID, QUOTE, 1000, 1), &mut ups, &ssp);
        ups.get_mut(UID).unwrap().add_to_locked(QUOTE, 500);

        let before_account = ups.get(UID).unwrap().account(QUOTE);
        let before_adjustments = *engine.adjustments.get(&QUOTE).unwrap_or(&0);

        let withdraw_cmd = balance_adjustment_cmd(UID, QUOTE, -600, 2);
        assert_eq!(RiskEngineCommandDispatcher::balance_adjustment(&mut engine, &withdraw_cmd, &mut ups, &ssp), CommandResultCode::RiskNsf);

        assert_eq!(ups.get(UID).unwrap().account(QUOTE), before_account);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap_or(&0), before_adjustments);
    }

    #[test]
    fn balance_adjustment_withdrawal_within_withdrawable_succeeds() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let ssp = SymbolSpecificationProvider::new();
        RiskEngineCommandDispatcher::add_user(&mut engine, &add_user_cmd(UID), &mut ups);
        RiskEngineCommandDispatcher::balance_adjustment(&mut engine, &balance_adjustment_cmd(UID, QUOTE, 1000, 1), &mut ups, &ssp);

        let withdraw_cmd = balance_adjustment_cmd(UID, QUOTE, -400, 2);
        assert_eq!(RiskEngineCommandDispatcher::balance_adjustment(&mut engine, &withdraw_cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 600);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), -600);
    }

    #[test]
    fn balance_adjustment_duplicate_order_id_is_already_applied_same_noop() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let ssp = SymbolSpecificationProvider::new();
        RiskEngineCommandDispatcher::add_user(&mut engine, &add_user_cmd(UID), &mut ups);

        let cmd = balance_adjustment_cmd(UID, QUOTE, 1000, 42);
        assert_eq!(RiskEngineCommandDispatcher::balance_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 1000);

        let repeat = balance_adjustment_cmd(UID, QUOTE, 1000, 42);
        assert_eq!(
            RiskEngineCommandDispatcher::balance_adjustment(&mut engine, &repeat, &mut ups, &ssp),
            CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame
        );
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 1000);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), -1000);

        let different_id = balance_adjustment_cmd(UID, QUOTE, 500, 43);
        assert_eq!(RiskEngineCommandDispatcher::balance_adjustment(&mut engine, &different_id, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 1500);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), -1500);
    }

    #[test]
    fn balance_adjustment_nsf_does_not_claim_id_so_same_id_retry_after_funding_succeeds() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let ssp = SymbolSpecificationProvider::new();
        RiskEngineCommandDispatcher::add_user(&mut engine, &add_user_cmd(UID), &mut ups);
        RiskEngineCommandDispatcher::balance_adjustment(&mut engine, &balance_adjustment_cmd(UID, QUOTE, 500, 1), &mut ups, &ssp);

        let nsf_attempt = balance_adjustment_cmd(UID, QUOTE, -600, 99);
        assert_eq!(RiskEngineCommandDispatcher::balance_adjustment(&mut engine, &nsf_attempt, &mut ups, &ssp), CommandResultCode::RiskNsf);

        RiskEngineCommandDispatcher::balance_adjustment(&mut engine, &balance_adjustment_cmd(UID, QUOTE, 1000, 2), &mut ups, &ssp);
        let retry = balance_adjustment_cmd(UID, QUOTE, -600, 99);
        assert_eq!(RiskEngineCommandDispatcher::balance_adjustment(&mut engine, &retry, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 900);
    }

    #[test]
    fn balance_adjustment_unknown_user_is_auth_invalid_user() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let ssp = SymbolSpecificationProvider::new();
        let cmd = balance_adjustment_cmd(999, QUOTE, 100, 1);
        assert_eq!(RiskEngineCommandDispatcher::balance_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::AuthInvalidUser);
    }

    const FUT_SYMBOL: i32 = 200;
    const FUT_BASE: i32 = 1;
    const FUT_QUOTE: i32 = 2;

    fn futures_spec_for(symbol_id: i32, taker_fee: i64, fee_scale_k: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: FUT_BASE,
            quote_currency: FUT_QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee,
            maker_fee: 0,
            fee_scale_k,
            ..Default::default()
        }
    }

    fn futures_spec(taker_fee: i64, fee_scale_k: i64) -> CoreSymbolSpecification {
        futures_spec_for(FUT_SYMBOL, taker_fee, fee_scale_k)
    }

    fn futures_spec_with_fees(taker_fee: i64, maker_fee: i64, fee_scale_k: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification { maker_fee, ..futures_spec_for(FUT_SYMBOL, taker_fee, fee_scale_k) }
    }

    fn setup_futures(
        taker_fee: i64,
        fee_scale_k: i64,
        quote_balance: i64,
        mark_price: i64,
    ) -> (RiskEngine, UserProfileService, SymbolSpecificationProvider) {
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(ssp.add_symbol(futures_spec(taker_fee, fee_scale_k)), CommandResultCode::Success);
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1, ..Default::default() });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        ups.get_mut(UID).unwrap().add_to_account(FUT_QUOTE, quote_balance);

        let mut engine = RiskEngine::new();
        engine.last_price_cache.insert(FUT_SYMBOL, LastPriceCacheRecord::with_mark(mark_price));
        (engine, ups, ssp)
    }

    fn futures_place_cmd(
        action: OrderAction,
        size: i64,
        price: i64,
        leverage: i32,
        margin_mode: MarginMode,
        reduce_only: bool,
    ) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: FUT_SYMBOL,
            price,
            size,
            action: Some(action),
            order_type: Some(OrderType::Gtc),
            uid: UID,
            leverage,
            margin_mode,
            order_flags: if reduce_only {
                crate::core::common::cmd::order_command::FLAG_REDUCE_ONLY
            } else {
                0
            },
            ..Default::default()
        }
    }

    #[test]
    fn futures_place_order_long_sufficient_margin_fixed_fee_is_valid_and_records_pending() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 10_000, 100);
        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);

        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        let position =
            ups.get_mut(UID).unwrap().positions.get(&FUT_SYMBOL).expect("NSF 通过后 position 必须已提交入 map");
        assert_eq!(position.pending_buy_size, 10);
        assert_eq!(position.pending_buy_avg_price, 100);
        assert_eq!(position.open_volume, 0);
        assert_eq!(position.leverage, 1);
        assert_eq!(position.margin_mode, MarginMode::Isolated);
    }

    #[test]
    fn futures_place_order_insufficient_margin_fixed_fee_is_nsf_and_position_not_created() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 1_000, 100);
        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);

        assert_eq!(engine.place_order_risk_check(&mut cmd, &mut ups, &ssp), CommandResultCode::RiskNsf);
        assert!(
            !ups.get_mut(UID).unwrap().positions.contains_key(&FUT_SYMBOL),
            "NSF 失败不得插入 position（NSF 前不插入是 P4 Task 3 的核心约束）"
        );
    }

    #[test]
    fn futures_place_order_proportional_fee_sufficient_margin_is_valid() {
        let (mut engine, mut ups, ssp) = setup_futures(100, 10_000, 2_000, 100);
        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);
        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
    }

    #[test]
    fn futures_place_order_proportional_fee_insufficient_margin_is_nsf() {
        let (mut engine, mut ups, ssp) = setup_futures(100, 10_000, 1_005, 100);
        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);
        assert_eq!(engine.place_order_risk_check(&mut cmd, &mut ups, &ssp), CommandResultCode::RiskNsf);
    }

    #[test]
    fn futures_place_order_leverage_exceeds_tier_is_invalid_leverage() {
        let mut spec = futures_spec(2, 0);
        spec.max_leverage.insert(0, 5);
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(ssp.add_symbol(spec), CommandResultCode::Success);
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1, ..Default::default() });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        ups.get_mut(UID).unwrap().add_to_account(FUT_QUOTE, 100_000);

        let mut engine = RiskEngine::new();
        engine.last_price_cache.insert(FUT_SYMBOL, LastPriceCacheRecord::with_mark(100));

        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 10, MarginMode::Isolated, false);
        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::RiskInvalidLeverage
        );
    }

    #[test]
    fn futures_place_order_option_symbol_type_returns_unsupported_not_panic() {
        const OPTION_SYMBOL: i32 = 202;
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(
            ssp.add_symbol(CoreSymbolSpecification {
                symbol_id: OPTION_SYMBOL,
                symbol_type: SymbolType::Option,
                base_currency: FUT_BASE,
                quote_currency: FUT_QUOTE,
                base_scale_k: 1,
                quote_scale_k: 1,
                ..Default::default()
            }),
            CommandResultCode::Success
        );
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1, ..Default::default() });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        ups.get_mut(UID).unwrap().add_to_account(FUT_QUOTE, 100_000);

        let mut engine = RiskEngine::new();

        let mut cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: OPTION_SYMBOL,
            price: 100,
            size: 10,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: UID,
            ..Default::default()
        };

        engine.pre_process_command(&mut cmd, &mut ups, &ssp);
        assert_eq!(cmd.result_code, Some(CommandResultCode::UnsupportedSymbolType));
        assert!(
            !ups.get_mut(UID).unwrap().positions.contains_key(&OPTION_SYMBOL),
            "不支持的 symbol 类型不得创建 position"
        );
    }

    #[test]
    fn futures_place_order_margin_mode_mismatch_against_existing_position() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 100_000, 100);
        ups.get_mut(UID)
            .unwrap()
            .positions
            .insert(FUT_SYMBOL, SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 3));

        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 3, MarginMode::Cross, false);
        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::RiskMarginModeMismatch
        );
    }

    #[test]
    fn futures_place_order_leverage_mismatch_against_existing_position() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 100_000, 100);
        ups.get_mut(UID)
            .unwrap()
            .positions
            .insert(FUT_SYMBOL, SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 3));

        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 5, MarginMode::Isolated, false);
        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::RiskLeverageMismatch
        );
    }

    #[test]
    fn futures_place_order_oneway_reduce_only_clamps_size_to_open_volume_and_succeeds() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 100_000, 100);
        let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
        pos.direction = PositionDirection::Long;
        pos.open_volume = 5;
        pos.open_price_sum = 500;
        pos.open_init_margin_sum = 500;
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, pos);

        let mut cmd = futures_place_cmd(OrderAction::Ask, 20, 100, 1, MarginMode::Isolated, true);
        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        assert_eq!(cmd.size, 5, "reduce-only 请求量必须被夹到可平量 openVolume");

        let updated = ups.get_mut(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(updated.pending_sell_size, 5);
    }

    #[test]
    fn futures_place_order_oneway_reduce_only_no_position_clamps_to_zero_is_noop_success() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 100_000, 100);

        let mut cmd = futures_place_cmd(OrderAction::Ask, 5, 100, 1, MarginMode::Isolated, true);
        assert_eq!(engine.place_order_risk_check(&mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert!(!ups.get_mut(UID).unwrap().positions.contains_key(&FUT_SYMBOL));
    }

    #[test]
    fn futures_place_order_cross_position_unrealized_profit_credits_other_symbol_order() {
        const OTHER_SYMBOL: i32 = 201;
        let (mut engine, mut ups, mut ssp) = setup_futures(2, 0, 600, 100);
        assert_eq!(ssp.add_symbol(futures_spec_for(OTHER_SYMBOL, 2, 0)), CommandResultCode::Success);
        engine.last_price_cache.insert(OTHER_SYMBOL, LastPriceCacheRecord::with_mark(200));

        let mut other_pos = SymbolPositionRecord::new(UID, OTHER_SYMBOL, FUT_QUOTE, MarginMode::Cross, 1);
        other_pos.direction = PositionDirection::Long;
        other_pos.open_volume = 10;
        other_pos.open_price_sum = 1000;
        other_pos.open_init_margin_sum = 500;
        ups.get_mut(UID).unwrap().positions.insert(OTHER_SYMBOL, other_pos);

        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);
        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
    }

    #[test]
    fn futures_place_order_isolated_other_position_margin_is_deducted_without_pnl_credit() {
        const OTHER_SYMBOL: i32 = 201;
        let (mut engine, mut ups, mut ssp) = setup_futures(2, 0, 1_000, 100);
        assert_eq!(ssp.add_symbol(futures_spec_for(OTHER_SYMBOL, 2, 0)), CommandResultCode::Success);
        engine.last_price_cache.insert(OTHER_SYMBOL, LastPriceCacheRecord::with_mark(200));

        let mut other_pos = SymbolPositionRecord::new(UID, OTHER_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
        other_pos.direction = PositionDirection::Long;
        other_pos.open_volume = 10;
        other_pos.open_price_sum = 1000;
        other_pos.open_init_margin_sum = 500;
        ups.get_mut(UID).unwrap().positions.insert(OTHER_SYMBOL, other_pos);

        let mut cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);
        assert_eq!(engine.place_order_risk_check(&mut cmd, &mut ups, &ssp), CommandResultCode::RiskNsf);
    }

    #[test]
    fn close_position_risk_check_no_position_is_noop_success() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 100_000, 100);
        let mut cmd = OrderCommand {
            command: OrderCommandType::ClosePosition,
            symbol: FUT_SYMBOL,
            action: Some(OrderAction::Ask),
            size: 10,
            price: 100,
            uid: UID,
            ..Default::default()
        };
        assert_eq!(engine.close_position_risk_check(&mut cmd, &mut ups, &ssp), CommandResultCode::Success);
    }

    #[test]
    fn close_position_risk_check_existing_position_clamps_size_and_forces_leverage_margin_mode() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 100_000, 100);
        let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Cross, 7);
        pos.direction = PositionDirection::Long;
        pos.open_volume = 5;
        pos.open_price_sum = 500;
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, pos);

        let mut cmd = OrderCommand {
            command: OrderCommandType::ClosePosition,
            symbol: FUT_SYMBOL,
            action: Some(OrderAction::Ask),
            size: 999,
            price: 100,
            uid: UID,
            leverage: 1,
            margin_mode: MarginMode::Isolated,
            ..Default::default()
        };

        assert_eq!(
            engine.close_position_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        assert_eq!(cmd.size, 5);
        assert_eq!(cmd.leverage, 7);
        assert_eq!(cmd.margin_mode, MarginMode::Cross);

        let updated = ups.get_mut(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(updated.pending_sell_size, 5);
    }

    #[test]
    fn close_position_risk_check_unsupported_symbol_type_for_spot() {
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(
            ssp.add_symbol(CoreSymbolSpecification {
                symbol_id: SYMBOL,
                symbol_type: SymbolType::CurrencyExchangePair,
                base_currency: BASE,
                quote_currency: QUOTE,
                base_scale_k: 1,
                quote_scale_k: 1,
                ..Default::default()
            }),
            CommandResultCode::Success
        );
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        let mut engine = RiskEngine::new();

        let mut cmd = OrderCommand {
            command: OrderCommandType::ClosePosition,
            symbol: SYMBOL,
            action: Some(OrderAction::Ask),
            size: 10,
            price: 100,
            uid: UID,
            ..Default::default()
        };
        assert_eq!(
            engine.close_position_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::UnsupportedSymbolType
        );
    }

    #[test]
    fn pre_process_command_routes_close_position_to_close_position_risk_check() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 100_000, 100);
        let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
        pos.direction = PositionDirection::Long;
        pos.open_volume = 5;
        pos.open_price_sum = 500;
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, pos);

        let mut cmd = OrderCommand {
            command: OrderCommandType::ClosePosition,
            symbol: FUT_SYMBOL,
            action: Some(OrderAction::Ask),
            size: 999,
            price: 100,
            uid: UID,
            ..Default::default()
        };
        engine.pre_process_command(&mut cmd, &mut ups, &ssp);
        assert_eq!(cmd.result_code, Some(CommandResultCode::ValidForMatchingEngine));
        assert_eq!(cmd.size, 5);
    }

    const FUT2_MAKER_UID: i64 = 12;

    fn fut_currency_spec() -> CoreCurrencySpecification {
        CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() }
    }

    fn fut_trade_event(size: i64, price: i64, matched_order_uid: i64) -> MatcherTradeEvent {
        fut_trade_event_with_command(size, price, matched_order_uid, OrderCommandType::PlaceOrder)
    }

    fn fut_trade_event_with_command(
        size: i64,
        price: i64,
        matched_order_uid: i64,
        matched_order_command_type: OrderCommandType,
    ) -> MatcherTradeEvent {
        MatcherTradeEvent {
            event_type: MatcherEventType::Trade,
            active_order_completed: false,
            maker_order_id: 0,
            maker_order_completed: false,
            price,
            size,
            bid_gt_ask: false,
            bidder_hold_price: 0,
            matched_order_uid,
            matched_order_command_type,
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
        }
    }

    fn seed_pending_position(ups: &mut UserProfileService, uid: i64, action: OrderAction, size: i64, price: i64) {
        if ups.get(uid).is_none() {
            ups.add_empty_user_profile(uid);
        }
        let up = ups.get_mut(uid).unwrap();
        let mut pos = up
            .positions
            .get(&FUT_SYMBOL)
            .cloned()
            .unwrap_or_else(|| SymbolPositionRecord::new(uid, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1));
        pos.pending_hold(action, size, price);
        up.positions.insert(FUT_SYMBOL, pos);
    }

    fn fut_reject_reduce_event(event_type: MatcherEventType, size: i64) -> MatcherTradeEvent {
        MatcherTradeEvent {
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
        }
    }

    #[test]
    fn settle_margin_open_new_position_no_pnl_charges_taker_fee_exact_conservation() {
        let spec = futures_spec(2, 0);
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_account(FUT_QUOTE, 10_000);
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.pending_buy_size = 10;
            pos.pending_buy_avg_price = 100;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        let mte = fut_trade_event(10, 100, 0);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(&mut Vec::new(), &SymbolSpecificationProvider::new(), &std::collections::BTreeMap::new(),
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Bid, &mut fees, &currency_spec, 100, true, false, 0,
        );

        let pos = up.positions.get(&FUT_SYMBOL).expect("open 后仍非空（open_volume>0），不应被拆记录");
        assert_eq!(pos.direction, PositionDirection::Long);
        assert_eq!(pos.open_volume, 10);
        assert_eq!(pos.open_price_sum, 1000);
        assert_eq!(pos.open_init_margin_sum, 1000);
        assert_eq!(pos.profit, 0, "开仓不产生已实现盈亏");

        assert_eq!(up.account(FUT_QUOTE), 10_000 - 20);
        assert_eq!(*fees.get(&FUT_QUOTE).unwrap(), 20);
        assert_eq!((up.account(FUT_QUOTE) - 10_000) + *fees.get(&FUT_QUOTE).unwrap(), 0, "唯一移动是费用配对");
    }

    #[test]
    fn settle_margin_partial_close_defers_pnl_into_cost_basis_no_realization() {
        let spec = futures_spec(2, 0);
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_account(FUT_QUOTE, 10_000);
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.direction = PositionDirection::Long;
            pos.open_volume = 20;
            pos.open_price_sum = 2000;
            pos.open_init_margin_sum = 2000;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        let mte = fut_trade_event(5, 110, 0);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(&mut Vec::new(), &SymbolSpecificationProvider::new(), &std::collections::BTreeMap::new(),
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Ask, &mut fees, &currency_spec, 100, true, false, 0,
        );

        let pos = up.positions.get(&FUT_SYMBOL).expect("部分平仍非空，不应拆记录");
        assert_eq!(pos.open_volume, 15);
        assert_eq!(pos.open_init_margin_sum, 1500, "trunc(2000*5/20)=500 释放，剩 1500");
        assert_eq!(pos.open_price_sum, 1450, "2000 - tradeSize(5)*tradePrice(110)=2000-550");
        assert_eq!(pos.profit, 0, "部分平不实现盈亏");

        assert_eq!(up.account(FUT_QUOTE), 10_000 - 10);
        assert_eq!(*fees.get(&FUT_QUOTE).unwrap(), 10);
    }

    #[test]
    fn settle_margin_full_close_realizes_pnl_and_removes_position_on_teardown() {
        let spec = futures_spec(2, 0);
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_account(FUT_QUOTE, 10_000);
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.direction = PositionDirection::Long;
            pos.open_volume = 10;
            pos.open_price_sum = 1000;
            pos.open_init_margin_sum = 1000;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        let mte = fut_trade_event(10, 120, 0);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(&mut Vec::new(), &SymbolSpecificationProvider::new(), &std::collections::BTreeMap::new(),
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Ask, &mut fees, &currency_spec, 100, true, false, 0,
        );

        assert!(!up.positions.contains_key(&FUT_SYMBOL), "全平且无残余挂单 → isEmpty → 拆记录");
        assert_eq!(up.account(FUT_QUOTE), 10_000 - 20 + 200);
        assert_eq!(*fees.get(&FUT_QUOTE).unwrap(), 20);
    }

    #[test]
    fn settle_margin_flip_closes_full_then_reopens_reverse_direction_defers_profit_payout() {
        let spec = futures_spec(2, 0);
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_account(FUT_QUOTE, 10_000);
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.direction = PositionDirection::Long;
            pos.open_volume = 10;
            pos.open_price_sum = 1000;
            pos.open_init_margin_sum = 1000;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        let mte = fut_trade_event(15, 120, 0);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(&mut Vec::new(), &SymbolSpecificationProvider::new(), &std::collections::BTreeMap::new(),
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Ask, &mut fees, &currency_spec, 100, true, false, 0,
        );

        let pos = up.positions.get(&FUT_SYMBOL).expect("翻仓后新方向仓位非空，不拆记录");
        assert_eq!(pos.direction, PositionDirection::Short);
        assert_eq!(pos.open_volume, 5);
        assert_eq!(pos.open_price_sum, 600);
        assert_eq!(pos.open_init_margin_sum, 500);
        assert_eq!(pos.profit, 200, "平仓腿已实现盈亏累进 profit，但因新仓非空未结算入账户");

        assert_eq!(up.account(FUT_QUOTE), 10_000 - 30);
        assert_eq!(*fees.get(&FUT_QUOTE).unwrap(), 30);
    }

    #[test]
    fn settle_margin_reject_reduce_only_releases_pending_no_account_change() {
        let spec = futures_spec(2, 0);
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_account(FUT_QUOTE, 5_000);
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.pending_buy_size = 10;
            pos.pending_buy_avg_price = 100;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        let mte = fut_reject_reduce_event(MatcherEventType::Reject, 4);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(&mut Vec::new(), &SymbolSpecificationProvider::new(), &std::collections::BTreeMap::new(),
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Bid, &mut fees, &currency_spec, 100, true, false, 0,
        );

        let pos = up.positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(pos.pending_buy_size, 6);
        assert_eq!(up.account(FUT_QUOTE), 5_000, "REJECT/REDUCE 只退 pending，不动账户");
        assert!(fees.is_empty());
    }

    #[test]
    fn settle_margin_reduce_full_pending_release_triggers_teardown_refunds_extra_margin_and_leftover_profit() {
        let spec = futures_spec(2, 0);
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_account(FUT_QUOTE, 1_000);
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.pending_sell_size = 3;
            pos.profit = 50;
            pos.extra_margin = 30;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        let mte = fut_reject_reduce_event(MatcherEventType::Reduce, 3);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(&mut Vec::new(), &SymbolSpecificationProvider::new(), &std::collections::BTreeMap::new(),
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Ask, &mut fees, &currency_spec, 100, true, false, 0,
        );

        assert!(!up.positions.contains_key(&FUT_SYMBOL), "isEmpty 后应拆记录");
        assert_eq!(up.account(FUT_QUOTE), 1_000 + 30 + 50, "extraMargin(30) + profit(50) 一次性入账");
        assert!(fees.is_empty(), "本次无成交，无 fee 移动");
    }

    #[test]
    fn settle_margin_missing_position_required_false_is_noop() {
        let spec = futures_spec(2, 0);
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        let mte = fut_trade_event(10, 100, 0);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(&mut Vec::new(), &SymbolSpecificationProvider::new(), &std::collections::BTreeMap::new(),
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Bid, &mut fees, &currency_spec, 100, true, false, 0,
        );

        assert!(!up.positions.contains_key(&FUT_SYMBOL), "缺失 position 时 required=false 应静默跳过");
        assert_eq!(up.account(FUT_QUOTE), 0);
        assert!(fees.is_empty());
    }

    #[test]
    #[should_panic(expected = "maker position record missing")]
    fn settle_margin_missing_position_required_true_panics() {
        let spec = futures_spec(2, 0);
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        let mte = fut_trade_event(10, 100, 0);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(&mut Vec::new(), &SymbolSpecificationProvider::new(), &std::collections::BTreeMap::new(),
            up, FUT_SYMBOL, true, &mte, &spec, OrderAction::Bid, &mut fees, &currency_spec, 100, false, false, 0,
        );
    }

    #[test]
    fn settle_margin_maker_side_uses_maker_fee_rate_not_taker_rate() {
        let spec = futures_spec_with_fees(10, 3, 0);
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_account(FUT_QUOTE, 10_000);
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.pending_buy_size = 10;
            pos.pending_buy_avg_price = 100;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        let mte = fut_trade_event(10, 100, 0);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(&mut Vec::new(), &SymbolSpecificationProvider::new(), &std::collections::BTreeMap::new(),
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Bid, &mut fees, &currency_spec, 100,
            false,
            false,
            0,
        );

        assert_eq!(up.account(FUT_QUOTE), 10_000 - 30);
        assert_eq!(*fees.get(&FUT_QUOTE).unwrap(), 30);
    }

    #[test]
    fn settle_margin_proportional_fee_is_exact_no_ceil_drift_taker_and_maker() {
        let spec = futures_spec_with_fees(333, 111, 10_000);
        let currency_spec = fut_currency_spec();
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_account(FUT_QUOTE, 100_000);
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.pending_buy_size = 7;
            pos.pending_buy_avg_price = 101;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let mut fees: BTreeMap<i32, i64> = BTreeMap::new();
        let mte = fut_trade_event(7, 101, 0);

        let up = ups.get_mut(UID).unwrap();
        RiskEngine::settle_margin_position_event(&mut Vec::new(), &SymbolSpecificationProvider::new(), &std::collections::BTreeMap::new(),
            up, FUT_SYMBOL, false, &mte, &spec, OrderAction::Bid, &mut fees, &currency_spec, 100, true, false, 0,
        );
        let taker_fee = arithmetic::calculate_taker_fee(7, 101, 333, 10_000);
        assert_ne!(taker_fee, 0);
        assert_eq!(up.account(FUT_QUOTE), 100_000 - taker_fee, "借记的就是算出来的那一个值，逐位精确");
        assert_eq!(*fees.get(&FUT_QUOTE).unwrap(), taker_fee, "贷记的也是同一个值，精确配对");
        assert_eq!((up.account(FUT_QUOTE) - 100_000) + *fees.get(&FUT_QUOTE).unwrap(), 0, "EXACT 守恒，非近似");
    }

    #[test]
    fn handler_risk_release_futures_trade_opens_both_sides_and_conserves() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 10_000, 100);
        assert_eq!(ups.add_empty_user_profile(FUT2_MAKER_UID), CommandResultCode::Success);
        ups.get_mut(FUT2_MAKER_UID).unwrap().add_to_account(FUT_QUOTE, 10_000);
        seed_pending_position(&mut ups, UID, OrderAction::Bid, 10, 100);
        seed_pending_position(&mut ups, FUT2_MAKER_UID, OrderAction::Ask, 10, 100);

        let mut cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            symbol: FUT_SYMBOL,
            action: Some(OrderAction::Bid),
            uid: UID,
            ..Default::default()
        };
        cmd.matcher_event = Some(Box::new(fut_trade_event(10, 100, FUT2_MAKER_UID)));

        let taker_before = ups.get(UID).unwrap().account(FUT_QUOTE);
        let maker_before = ups.get(FUT2_MAKER_UID).unwrap().account(FUT_QUOTE);

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        assert!(cmd.matcher_event.is_some(), "R2 只读不消费，TRADE 链保留供事件处理");
        let taker_pos = ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(taker_pos.direction, PositionDirection::Long);
        assert_eq!(taker_pos.open_volume, 10);
        let maker_pos = ups.get(FUT2_MAKER_UID).unwrap().positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(maker_pos.direction, PositionDirection::Short);
        assert_eq!(maker_pos.open_volume, 10);

        let taker_delta = ups.get(UID).unwrap().account(FUT_QUOTE) - taker_before;
        let maker_delta = ups.get(FUT2_MAKER_UID).unwrap().account(FUT_QUOTE) - maker_before;
        let fees_delta = *engine.fees.get(&FUT_QUOTE).unwrap_or(&0);
        assert_eq!(taker_delta, -20, "taker_fee(2)*size(10)");
        assert_eq!(maker_delta, 0, "本 fixture maker_fee=0");
        assert_eq!(taker_delta + maker_delta + fees_delta, 0, "开仓：唯一移动是费用配对");
    }

    #[test]
    fn handler_risk_release_futures_trade_oneway_unaffected_by_matched_order_command_type_switch() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 10_000, 100);
        assert_eq!(ups.add_empty_user_profile(FUT2_MAKER_UID), CommandResultCode::Success);
        ups.get_mut(FUT2_MAKER_UID).unwrap().add_to_account(FUT_QUOTE, 10_000);
        seed_pending_position(&mut ups, UID, OrderAction::Bid, 10, 100);
        seed_pending_position(&mut ups, FUT2_MAKER_UID, OrderAction::Ask, 10, 100);

        let mut cmd = OrderCommand {
            command: OrderCommandType::ForceLiquidation,
            symbol: FUT_SYMBOL,
            action: Some(OrderAction::Bid),
            uid: UID,
            ..Default::default()
        };
        cmd.matcher_event = Some(Box::new(fut_trade_event_with_command(
            10,
            100,
            FUT2_MAKER_UID,
            OrderCommandType::PlaceOrder,
        )));

        let taker_before = ups.get(UID).unwrap().account(FUT_QUOTE);
        let maker_before = ups.get(FUT2_MAKER_UID).unwrap().account(FUT_QUOTE);

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        assert!(cmd.matcher_event.is_some(), "R2 只读不消费，TRADE 链保留供事件处理");
        let taker_pos = ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(taker_pos.direction, PositionDirection::Long);
        assert_eq!(taker_pos.open_volume, 10);
        let maker_pos = ups.get(FUT2_MAKER_UID).unwrap().positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(maker_pos.direction, PositionDirection::Short);
        assert_eq!(maker_pos.open_volume, 10);

        let taker_delta = ups.get(UID).unwrap().account(FUT_QUOTE) - taker_before;
        let maker_delta = ups.get(FUT2_MAKER_UID).unwrap().account(FUT_QUOTE) - maker_before;
        let fees_delta = *engine.fees.get(&FUT_QUOTE).unwrap_or(&0);
        assert_eq!(taker_delta, -20, "与 taker=PlaceOrder 基线完全一致（ONEWAY 忽略 command）");
        assert_eq!(maker_delta, 0);
        assert_eq!(taker_delta + maker_delta + fees_delta, 0);
    }

    #[test]
    fn handler_risk_release_futures_full_round_trip_open_then_close_is_zero_sum_between_counterparties() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 10_000, 100);
        assert_eq!(ups.add_empty_user_profile(FUT2_MAKER_UID), CommandResultCode::Success);
        ups.get_mut(FUT2_MAKER_UID).unwrap().add_to_account(FUT_QUOTE, 10_000);
        seed_pending_position(&mut ups, UID, OrderAction::Bid, 10, 100);
        seed_pending_position(&mut ups, FUT2_MAKER_UID, OrderAction::Ask, 10, 100);

        let mut open_cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            symbol: FUT_SYMBOL,
            action: Some(OrderAction::Bid),
            uid: UID,
            ..Default::default()
        };
        open_cmd.matcher_event = Some(Box::new(fut_trade_event(10, 100, FUT2_MAKER_UID)));
        engine.handler_risk_release(&mut open_cmd, &mut ups, &ssp);

        let taker_after_open = ups.get(UID).unwrap().account(FUT_QUOTE);
        let maker_after_open = ups.get(FUT2_MAKER_UID).unwrap().account(FUT_QUOTE);

        seed_pending_position(&mut ups, UID, OrderAction::Ask, 10, 120);
        seed_pending_position(&mut ups, FUT2_MAKER_UID, OrderAction::Bid, 10, 120);

        let mut close_cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            symbol: FUT_SYMBOL,
            action: Some(OrderAction::Ask),
            uid: UID,
            ..Default::default()
        };
        close_cmd.matcher_event = Some(Box::new(fut_trade_event(10, 120, FUT2_MAKER_UID)));
        engine.handler_risk_release(&mut close_cmd, &mut ups, &ssp);

        assert!(!ups.get(UID).unwrap().positions.contains_key(&FUT_SYMBOL), "taker 全平应拆记录");
        assert!(
            !ups.get(FUT2_MAKER_UID).unwrap().positions.contains_key(&FUT_SYMBOL),
            "maker 全平应拆记录"
        );

        let taker_final = ups.get(UID).unwrap().account(FUT_QUOTE);
        let maker_final = ups.get(FUT2_MAKER_UID).unwrap().account(FUT_QUOTE);

        assert_eq!(taker_final - taker_after_open, 200 - 20);
        assert_eq!(maker_final - maker_after_open, -200);

        let total_user_delta = (taker_final - 10_000) + (maker_final - 10_000);
        let fees_total = *engine.fees.get(&FUT_QUOTE).unwrap();
        assert_eq!(total_user_delta + fees_total, 0);
    }

    #[test]
    fn handler_risk_release_futures_chain_head_reduce_then_trade_applies_both_in_one_call() {
        let (mut engine, mut ups, ssp) = setup_futures(2, 0, 10_000, 100);
        assert_eq!(ups.add_empty_user_profile(FUT2_MAKER_UID), CommandResultCode::Success);
        ups.get_mut(FUT2_MAKER_UID).unwrap().add_to_account(FUT_QUOTE, 10_000);

        {
            let up = ups.get_mut(UID).unwrap();
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.pending_buy_size = 10;
            pos.pending_buy_avg_price = 100;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        seed_pending_position(&mut ups, FUT2_MAKER_UID, OrderAction::Ask, 6, 100);

        let mut cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            symbol: FUT_SYMBOL,
            action: Some(OrderAction::Bid),
            uid: UID,
            ..Default::default()
        };
        let trade = fut_trade_event(6, 100, FUT2_MAKER_UID);
        let mut reduce = fut_reject_reduce_event(MatcherEventType::Reduce, 4);
        reduce.next = Some(Box::new(trade));
        cmd.matcher_event = Some(Box::new(reduce));

        engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

        let taker_pos = ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(taker_pos.pending_buy_size, 0, "REDUCE(4) + TRADE(6) 应耗尽全部 10 挂单");
        assert_eq!(taker_pos.open_volume, 6, "TRADE 事件应正常开仓 6 手，未被链头 REDUCE 影响");

        let maker_pos = ups.get(FUT2_MAKER_UID).unwrap().positions.get(&FUT_SYMBOL).unwrap();
        assert_eq!(maker_pos.open_volume, 6, "maker 只在 TRADE 事件参与，REDUCE 与其无关");
    }

    #[test]
    fn calculate_locked_zero_when_no_positions_and_no_exchange_locked() {
        let (_engine, ups, ssp) = setup_futures(0, 0, 0, 100);
        let up = ups.get(UID).unwrap();
        let currency_spec = ssp.get_currency(FUT_QUOTE).unwrap();
        assert_eq!(RiskEngine::calculate_locked(up, FUT_QUOTE, &ssp, currency_spec), 0);
    }

    #[test]
    fn calculate_locked_sums_futures_margin_and_exchange_locked() {
        let (_engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_locked(FUT_QUOTE, 200);
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.direction = PositionDirection::Long;
            pos.open_volume = 10;
            pos.open_price_sum = 1000;
            pos.open_init_margin_sum = 1000;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let up = ups.get(UID).unwrap();
        let currency_spec = ssp.get_currency(FUT_QUOTE).unwrap();
        assert_eq!(RiskEngine::calculate_locked(up, FUT_QUOTE, &ssp, currency_spec), 1200);
    }

    fn isolated_loan_with_collateral(loan_id: i64, collateral_currency: i32, collateral_amount: i64)
        -> crate::core::common::isolated_loan_record::IsolatedLoanRecord {
        crate::core::common::isolated_loan_record::IsolatedLoanRecord {
            loan_id,
            collateral_currency,
            collateral_amount,
            ..Default::default()
        }
    }

    #[test]
    fn loan_collateral_locked_sums_isolated_and_cross_by_currency() {
        let (_engine, mut ups, _ssp) = setup_futures(0, 0, 0, 100);
        {
            let up = ups.get_mut(UID).unwrap();
            up.isolated_loans.insert(1, isolated_loan_with_collateral(1, FUT_QUOTE, 300));
            up.isolated_loans.insert(2, isolated_loan_with_collateral(2, FUT_QUOTE, 50));
            up.isolated_loans.insert(3, isolated_loan_with_collateral(3, FUT_BASE, 999));
            up.cross_loan_collateral.insert(FUT_QUOTE, 100);
        }
        let up = ups.get(UID).unwrap();
        assert_eq!(RiskEngine::loan_collateral_locked(up, FUT_QUOTE), 450);
        assert_eq!(RiskEngine::loan_collateral_locked(up, FUT_BASE), 999);
        assert_eq!(RiskEngine::loan_collateral_locked(up, 12345), 0);
    }

    #[test]
    fn loan_collateral_locked_zero_for_user_without_loans() {
        let (_engine, ups, _ssp) = setup_futures(0, 0, 1_000, 100);
        assert_eq!(RiskEngine::loan_collateral_locked(ups.get(UID).unwrap(), FUT_QUOTE), 0);
    }

    #[test]
    fn calculate_locked_includes_loan_collateral() {
        let (_engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_locked(FUT_QUOTE, 200);
            up.isolated_loans.insert(1, isolated_loan_with_collateral(1, FUT_QUOTE, 300));
        }
        let up = ups.get(UID).unwrap();
        let currency_spec = ssp.get_currency(FUT_QUOTE).unwrap();
        assert_eq!(RiskEngine::calculate_locked(up, FUT_QUOTE, &ssp, currency_spec), 500);
    }

    #[test]
    fn calculate_locked_java_combined_four_components_summed_into_quote_and_base() {
        let (_engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        {
            let up = ups.get_mut(UID).unwrap();
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.direction = PositionDirection::Long;
            pos.open_volume = 100;
            pos.open_price_sum = 100;
            pos.open_init_margin_sum = 500;
            up.positions.insert(FUT_SYMBOL, pos);
            up.add_to_locked(FUT_QUOTE, 200);
            up.isolated_loans.insert(100, isolated_loan_with_collateral(100, FUT_BASE, 2));
            up.cross_loan_collateral.insert(FUT_BASE, 3);
            up.cross_loan_collateral.insert(FUT_QUOTE, 1_000);
        }
        let up = ups.get(UID).unwrap();
        assert_eq!(RiskEngine::calculate_locked(up, FUT_BASE, &ssp, ssp.get_currency(FUT_BASE).unwrap()), 5);
        assert_eq!(RiskEngine::calculate_locked(up, FUT_QUOTE, &ssp, ssp.get_currency(FUT_QUOTE).unwrap()), 1_700);
    }

    #[test]
    fn calculate_locked_java_isolated_loan_only_counted_for_its_collateral_currency() {
        const ETH: i32 = 3;
        let (_engine, mut ups, mut ssp) = setup_futures(0, 0, 0, 100);
        ssp.add_currency(CoreCurrencySpecification { currency: ETH, currency_scale_k: 1, ..Default::default() });
        {
            let up = ups.get_mut(UID).unwrap();
            up.isolated_loans.insert(100, isolated_loan_with_collateral(100, FUT_BASE, 3));
            up.isolated_loans.insert(101, isolated_loan_with_collateral(101, ETH, 7));
        }
        let up = ups.get(UID).unwrap();
        assert_eq!(RiskEngine::calculate_locked(up, FUT_BASE, &ssp, ssp.get_currency(FUT_BASE).unwrap()), 3);
        assert_eq!(RiskEngine::calculate_locked(up, ETH, &ssp, ssp.get_currency(ETH).unwrap()), 7);
        assert_eq!(RiskEngine::calculate_locked(up, FUT_QUOTE, &ssp, ssp.get_currency(FUT_QUOTE).unwrap()), 0);
    }

    #[test]
    fn place_margin_order_java_h3_other_cross_position_different_scale_not_blown_up() {
        const USDT: i32 = 2;
        const BASE_A: i32 = 10;
        const BASE_B: i32 = 11;
        const SYMBOL_A: i32 = 7001;
        const SYMBOL_B: i32 = 7002;
        fn fut(symbol_id: i32, base: i32, bsk: i64, qsk: i64) -> CoreSymbolSpecification {
            let mut mm = std::collections::BTreeMap::new();
            mm.insert(1_000_000_000i64, 8i64);
            CoreSymbolSpecification {
                symbol_id,
                symbol_type: SymbolType::FuturesContractPerpetual,
                base_currency: base,
                quote_currency: USDT,
                base_scale_k: bsk,
                quote_scale_k: qsk,
                init_margin: 10,
                init_margin_scale_k: 1_000,
                maintenance_margin: mm,
                maintenance_margin_scale_k: 1_000,
                taker_fee: 0,
                maker_fee: 0,
                fee_scale_k: 0,
                ..Default::default()
            }
        }
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(ssp.add_symbol(fut(SYMBOL_A, BASE_A, 1, 1)), CommandResultCode::Success);
        assert_eq!(ssp.add_symbol(fut(SYMBOL_B, BASE_B, 100, 100)), CommandResultCode::Success);
        ssp.add_currency(CoreCurrencySpecification { currency: USDT, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: BASE_A, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: BASE_B, currency_scale_k: 1, ..Default::default() });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(UID).unwrap();
            let mut pos_b = SymbolPositionRecord::new(UID, SYMBOL_B, USDT, MarginMode::Cross, 1);
            pos_b.direction = PositionDirection::Long;
            pos_b.open_volume = 1;
            pos_b.open_price_sum = 10_000;
            pos_b.open_init_margin_sum = 10_000;
            up.positions.insert(SYMBOL_B, pos_b);
            up.add_to_account(USDT, 100);
        }
        let mut engine = RiskEngine::new();
        engine.last_price_cache.insert(SYMBOL_A, LastPriceCacheRecord::with_mark(100));
        engine.last_price_cache.insert(SYMBOL_B, LastPriceCacheRecord::with_mark(10_000));

        let mut cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 9001,
            symbol: SYMBOL_A,
            price: 100,
            size: 1,
            reserve_bid_price: 100,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: UID,
            leverage: 1,
            margin_mode: MarginMode::Cross,
            ..Default::default()
        };
        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine,
            "B 仓保证金应按其自身 scale 折算(=1 USDT)，不应被放大 10000× 导致误拒"
        );
    }

    const JP_BTC: i32 = 1;
    const JP_USDT: i32 = 2;
    const JP_ETH: i32 = 3;
    const JP_UID: i64 = 42;
    const JP_FUT: i32 = 1001;
    const JP_SPOT: i32 = 2001;

    fn jp_locked_env() -> (SymbolSpecificationProvider, UserProfileService) {
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_currency(CoreCurrencySpecification { currency: JP_BTC, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: JP_USDT, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: JP_ETH, currency_scale_k: 1, ..Default::default() });

        let mut fut = CoreSymbolSpecification {
            symbol_id: JP_FUT,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: JP_BTC,
            quote_currency: JP_USDT,
            base_scale_k: 1,
            quote_scale_k: 1,
            init_margin: 10,
            init_margin_scale_k: 1,
            maintenance_margin_scale_k: 1,
            ..Default::default()
        };
        fut.maintenance_margin.insert(1000, 8);
        assert_eq!(ssp.add_symbol(fut), CommandResultCode::Success);

        let spot = CoreSymbolSpecification {
            symbol_id: JP_SPOT,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: JP_BTC,
            quote_currency: JP_USDT,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        };
        assert_eq!(ssp.add_symbol(spot), CommandResultCode::Success);

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(JP_UID), CommandResultCode::Success);
        (ssp, ups)
    }

    fn jp_locked(ups: &UserProfileService, ssp: &SymbolSpecificationProvider, currency: i32) -> i64 {
        let up = ups.get(JP_UID).unwrap();
        let cspec = ssp.get_currency(currency).unwrap();
        RiskEngine::calculate_locked(up, currency, ssp, cspec)
    }

    #[test]
    fn parity_calculate_locked_empty_profile_returns_zero() {
        let (ssp, ups) = jp_locked_env();
        assert_eq!(jp_locked(&ups, &ssp, JP_BTC), 0);
        assert_eq!(jp_locked(&ups, &ssp, JP_USDT), 0);
    }

    #[test]
    fn parity_calculate_locked_spot_exchange_locked_only() {
        let (ssp, mut ups) = jp_locked_env();
        ups.get_mut(JP_UID).unwrap().add_to_locked(JP_BTC, 5);
        assert_eq!(jp_locked(&ups, &ssp, JP_BTC), 5, "② 单独 spot lock");
        assert_eq!(jp_locked(&ups, &ssp, JP_USDT), 0, "非匹配 currency 不受影响");
    }

    #[test]
    fn parity_calculate_locked_isolated_loan_collateral_only() {
        let (ssp, mut ups) = jp_locked_env();
        {
            let up = ups.get_mut(JP_UID).unwrap();
            up.isolated_loans.insert(100, isolated_loan_with_collateral(100, JP_BTC, 3));
            up.isolated_loans.insert(101, isolated_loan_with_collateral(101, JP_ETH, 7));
        }
        assert_eq!(jp_locked(&ups, &ssp, JP_BTC), 3, "③ 只 BTC 抵押的 loan 计入");
        assert_eq!(jp_locked(&ups, &ssp, JP_ETH), 7, "③ 只 ETH 抵押的 loan 计入");
        assert_eq!(jp_locked(&ups, &ssp, JP_USDT), 0, "loanCurrency 侧不作 collateral 计入 locked");
    }

    #[test]
    fn parity_calculate_locked_multiple_isolated_same_currency_summed() {
        let (ssp, mut ups) = jp_locked_env();
        {
            let up = ups.get_mut(JP_UID).unwrap();
            up.isolated_loans.insert(100, isolated_loan_with_collateral(100, JP_BTC, 3));
            up.isolated_loans.insert(101, isolated_loan_with_collateral(101, JP_BTC, 5));
        }
        assert_eq!(jp_locked(&ups, &ssp, JP_BTC), 8, "③ 同 currency 多 loan 累加");
    }

    #[test]
    fn parity_calculate_locked_cross_loan_collateral_only() {
        let (ssp, mut ups) = jp_locked_env();
        {
            let up = ups.get_mut(JP_UID).unwrap();
            up.cross_loan_collateral.insert(JP_BTC, 4);
            up.cross_loan_collateral.insert(JP_ETH, 6);
        }
        assert_eq!(jp_locked(&ups, &ssp, JP_BTC), 4, "④ Cross BTC 抵押计入");
        assert_eq!(jp_locked(&ups, &ssp, JP_ETH), 6, "④ Cross ETH 抵押计入");
        assert_eq!(jp_locked(&ups, &ssp, JP_USDT), 0);
    }

    #[test]
    fn parity_calculate_locked_futures_margin_plus_loan_collateral() {
        let (ssp, mut ups) = jp_locked_env();
        {
            let up = ups.get_mut(JP_UID).unwrap();
            let mut pos = SymbolPositionRecord::new(JP_UID, JP_FUT, JP_USDT, MarginMode::Isolated, 1);
            pos.direction = PositionDirection::Long;
            pos.open_volume = 100;
            pos.open_price_sum = 100;
            pos.open_init_margin_sum = 500;
            up.positions.insert(JP_FUT, pos);

            up.isolated_loans.insert(100, isolated_loan_with_collateral(100, JP_BTC, 2));
            up.cross_loan_collateral.insert(JP_BTC, 3);
            up.cross_loan_collateral.insert(JP_USDT, 1_000);
            up.add_to_locked(JP_USDT, 200);
        }
        assert_eq!(jp_locked(&ups, &ssp, JP_BTC), 5, "BTC = ③2 + ④3");
        assert_eq!(jp_locked(&ups, &ssp, JP_USDT), 1_700, "USDT = ①500 + ②200 + ④1000");
    }

    #[test]
    fn parity_calculate_locked_isolated_and_cross_same_currency_summed() {
        let (ssp, mut ups) = jp_locked_env();
        {
            let up = ups.get_mut(JP_UID).unwrap();
            up.isolated_loans.insert(100, isolated_loan_with_collateral(100, JP_BTC, 2));
            up.cross_loan_collateral.insert(JP_BTC, 3);
        }
        assert_eq!(jp_locked(&ups, &ssp, JP_BTC), 5, "③ + ④ 同 currency 累加");
    }

    #[test]
    fn parity_calculate_locked_isolated_zero_collateral_contributes_zero() {
        let (ssp, mut ups) = jp_locked_env();
        ups.get_mut(JP_UID).unwrap().isolated_loans.insert(100, isolated_loan_with_collateral(100, JP_BTC, 0));
        assert_eq!(jp_locked(&ups, &ssp, JP_BTC), 0);
    }

    const JP_BASE_A: i32 = 10;
    const JP_BASE_B: i32 = 11;
    const JP_SYM_A: i32 = 7001;
    const JP_SYM_B: i32 = 7002;

    fn jp_cross_scale_spec(symbol_id: i32, base_currency: i32, base_scale_k: i64, quote_scale_k: i64) -> CoreSymbolSpecification {
        let mut s = CoreSymbolSpecification {
            symbol_id,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency,
            quote_currency: JP_USDT,
            base_scale_k,
            quote_scale_k,
            init_margin: 10,
            init_margin_scale_k: 1000,
            maintenance_margin_scale_k: 1000,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        };
        s.maintenance_margin.insert(1_000_000_000, 8);
        s
    }

    #[test]
    fn parity_place_margin_order_other_cross_position_different_scale_accepted() {
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_currency(CoreCurrencySpecification { currency: JP_USDT, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: JP_BASE_A, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: JP_BASE_B, currency_scale_k: 1, ..Default::default() });
        assert_eq!(ssp.add_symbol(jp_cross_scale_spec(JP_SYM_A, JP_BASE_A, 1, 1)), CommandResultCode::Success);
        assert_eq!(ssp.add_symbol(jp_cross_scale_spec(JP_SYM_B, JP_BASE_B, 100, 100)), CommandResultCode::Success);

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(JP_UID), CommandResultCode::Success);
        {
            let up = ups.get_mut(JP_UID).unwrap();
            let mut pos_b = SymbolPositionRecord::new(JP_UID, JP_SYM_B, JP_USDT, MarginMode::Cross, 1);
            pos_b.direction = PositionDirection::Long;
            pos_b.open_volume = 1;
            pos_b.open_price_sum = 10_000;
            pos_b.open_init_margin_sum = 10_000;
            up.positions.insert(JP_SYM_B, pos_b);
            up.add_to_account(JP_USDT, 100);
        }

        let mut engine = RiskEngine::new();
        engine.last_price_cache.insert(JP_SYM_A, LastPriceCacheRecord::with_mark(100));
        engine.last_price_cache.insert(JP_SYM_B, LastPriceCacheRecord::with_mark(10_000));

        let mut cmd = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 9001,
            symbol: JP_SYM_A,
            price: 100,
            size: 1,
            reserve_bid_price: 100,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: JP_UID,
            leverage: 1,
            margin_mode: MarginMode::Cross,
            ..Default::default()
        };
        assert_eq!(
            engine.place_order_risk_check(&mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine,
            "B 仓保证金应按其自身 scale 折算(=1 USDT)，不应被放大 10000× 导致误拒"
        );
    }

    #[test]
    fn place_exchange_order_nsf_when_loan_collateral_locks_the_balance() {
        let (mut engine, mut ups, mut ssp) = setup_futures(0, 0, 100, 100);
        add_spot_symbol_sharing_fut_quote(&mut ssp);
        ups.get_mut(UID).unwrap().isolated_loans.insert(1, isolated_loan_with_collateral(1, FUT_QUOTE, 100));

        let mut cmd = spot_bid_cmd(50, 1);
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);
        assert_eq!(result, CommandResultCode::RiskNsf, "借贷抵押锁走余额后现货挂单应 NSF");
        assert_eq!(ups.get(UID).unwrap().locked(FUT_QUOTE), 0, "NSF 不得锁定任何额度");
    }

    #[test]
    fn place_exchange_order_succeeds_when_free_balance_covers_order_despite_loan() {
        let (mut engine, mut ups, mut ssp) = setup_futures(0, 0, 200, 100);
        add_spot_symbol_sharing_fut_quote(&mut ssp);
        ups.get_mut(UID).unwrap().isolated_loans.insert(1, isolated_loan_with_collateral(1, FUT_QUOTE, 100));

        let mut cmd = spot_bid_cmd(50, 1);
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);
        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        assert_eq!(ups.get(UID).unwrap().locked(FUT_QUOTE), 50);
    }

    #[test]
    fn withdrawable_balance_deducts_loan_collateral() {
        let (engine, mut ups, ssp) = setup_futures(0, 0, 500, 100);
        ups.get_mut(UID).unwrap().isolated_loans.insert(1, isolated_loan_with_collateral(1, FUT_QUOTE, 300));
        let up = ups.get(UID).unwrap();
        assert_eq!(engine.withdrawable_balance(up, FUT_QUOTE, &ssp), 200);
    }

    #[test]
    fn calculate_free_futures_margin_zero_for_user_with_no_positions() {
        let (engine, ups, ssp) = setup_futures(0, 0, 1_000, 100);
        let up = ups.get(UID).unwrap();
        assert_eq!(engine.calculate_free_futures_margin(up, FUT_QUOTE, &ssp), 0);
    }

    #[test]
    fn calculate_free_futures_margin_isolated_position_never_credits_its_own_upnl() {
        let (engine, mut ups, ssp) = setup_futures(0, 0, 0, 300);
        {
            let up = ups.get_mut(UID).unwrap();
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
            pos.direction = PositionDirection::Long;
            pos.open_volume = 10;
            pos.open_price_sum = 900;
            pos.open_init_margin_sum = 200;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let up = ups.get(UID).unwrap();
        assert_eq!(engine.calculate_free_futures_margin(up, FUT_QUOTE, &ssp), -200);
    }

    #[test]
    fn calculate_free_futures_margin_cross_position_takes_min_of_two_conservative_estimates() {
        let mut fut_spec = futures_spec(0, 0);
        fut_spec.maintenance_margin_scale_k = 1000;
        fut_spec.maintenance_margin.insert(i64::MAX, 50);
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(ssp.add_symbol(fut_spec), CommandResultCode::Success);
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1, ..Default::default() });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        let mut engine = RiskEngine::new();
        engine.last_price_cache.insert(FUT_SYMBOL, LastPriceCacheRecord::with_mark(200));
        {
            let up = ups.get_mut(UID).unwrap();
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Cross, 1);
            pos.direction = PositionDirection::Long;
            pos.open_volume = 10;
            pos.open_price_sum = 900;
            pos.open_init_margin_sum = 200;
            pos.profit = 500;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let up = ups.get(UID).unwrap();
        assert_eq!(engine.calculate_free_futures_margin(up, FUT_QUOTE, &ssp), 400);
    }

    #[test]
    fn calculate_free_futures_margin_flat_cross_position_with_carried_profit() {
        let (engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        {
            let up = ups.get_mut(UID).unwrap();
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Cross, 1);
            pos.profit = 500;
            up.positions.insert(FUT_SYMBOL, pos);
        }
        let up = ups.get(UID).unwrap();
        assert_eq!(engine.calculate_free_futures_margin(up, FUT_QUOTE, &ssp), 500);
    }

    const SPOT_SYMBOL: i32 = 300;
    const SPOT_BASE: i32 = 3;

    fn add_spot_symbol_sharing_fut_quote(ssp: &mut SymbolSpecificationProvider) {
        let spot_spec = CoreSymbolSpecification {
            symbol_id: SPOT_SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: SPOT_BASE,
            quote_currency: FUT_QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        };
        assert_eq!(ssp.add_symbol(spot_spec), CommandResultCode::Success);
        ssp.add_currency(CoreCurrencySpecification { currency: SPOT_BASE, currency_scale_k: 1, ..Default::default() });
    }

    fn spot_bid_cmd(size: i64, price: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 1,
            symbol: SPOT_SYMBOL,
            price,
            size,
            reserve_bid_price: price,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: UID,
            ..Default::default()
        }
    }

    #[test]
    fn place_exchange_order_position_less_user_nsf_is_unaffected_by_wiring() {
        let (mut engine, mut ups, mut ssp) = setup_futures(0, 0, 0, 100);
        add_spot_symbol_sharing_fut_quote(&mut ssp);

        let mut cmd = spot_bid_cmd(100, 1);
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::RiskNsf);
        assert_eq!(ups.get(UID).unwrap().locked(FUT_QUOTE), 0);
    }

    #[test]
    fn place_exchange_order_spot_nsf_topped_up_by_futures_cross_profit() {
        let (mut engine, mut ups, mut ssp) = setup_futures(0, 0, 0, 100);
        add_spot_symbol_sharing_fut_quote(&mut ssp);
        {
            let up = ups.get_mut(UID).unwrap();
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Cross, 1);
            pos.profit = 500;
            up.positions.insert(FUT_SYMBOL, pos);
        }

        let mut cmd = spot_bid_cmd(100, 1);
        let result = engine.place_order_risk_check(&mut cmd, &mut ups, &ssp);

        assert_eq!(result, CommandResultCode::ValidForMatchingEngine);
        assert_eq!(ups.get(UID).unwrap().locked(FUT_QUOTE), 100);
    }

    #[test]
    fn withdrawable_position_less_user_nsf_is_unaffected_by_wiring() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let ssp = SymbolSpecificationProvider::new();
        RiskEngineCommandDispatcher::add_user(&mut engine, &add_user_cmd(UID), &mut ups);
        RiskEngineCommandDispatcher::balance_adjustment(&mut engine, &balance_adjustment_cmd(UID, QUOTE, 100, 1), &mut ups, &ssp);

        let withdraw = balance_adjustment_cmd(UID, QUOTE, -400, 2);
        assert_eq!(RiskEngineCommandDispatcher::balance_adjustment(&mut engine, &withdraw, &mut ups, &ssp), CommandResultCode::RiskNsf);
        assert_eq!(ups.get(UID).unwrap().account(QUOTE), 100);
    }

    #[test]
    fn withdrawable_topped_up_by_futures_cross_profit() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 500, 100);
        {
            let up = ups.get_mut(UID).unwrap();
            up.add_to_locked(FUT_QUOTE, 200);
            let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Cross, 1);
            pos.profit = 500;
            up.positions.insert(FUT_SYMBOL, pos);
        }

        let withdraw_cmd = OrderCommand {
            command: OrderCommandType::BalanceAdjustment,
            uid: UID,
            symbol: FUT_QUOTE,
            price: -400,
            order_id: 2,
            ..Default::default()
        };
        assert_eq!(RiskEngineCommandDispatcher::balance_adjustment(&mut engine, &withdraw_cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE), 500 - 400);
    }

    fn margin_adjustment_cmd(
        action: OrderAction,
        symbol: i32,
        price: i64,
        margin_mode: MarginMode,
        order_id: i64,
    ) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::MarginAdjustment,
            uid: UID,
            symbol,
            price,
            action: Some(action),
            margin_mode,
            order_id,
            ..Default::default()
        }
    }

    fn isolated_position(leverage: i32) -> SymbolPositionRecord {
        SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, leverage)
    }

    #[test]
    fn margin_adjustment_isolated_add_debits_accounts_credits_extra_margin_and_conserves() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, isolated_position(1));

        let cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 200, MarginMode::Isolated, 1);
        assert_eq!(RiskEngineCommandDispatcher::margin_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::Success);

        let up = ups.get(UID).unwrap();
        assert_eq!(up.account(FUT_QUOTE), 1_000 - 200, "accounts 物理扣 200");
        assert_eq!(up.positions.get(&FUT_SYMBOL).unwrap().extra_margin, 200, "extraMargin 收到等额 200");
        assert_eq!(*engine.adjustments.get(&FUT_QUOTE).unwrap_or(&0), 0, "ISOLATED 不touch adjustments 桶");
    }

    #[test]
    fn margin_adjustment_isolated_nsf_rejects_and_leaves_state_unchanged() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 50, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, isolated_position(1));

        let cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 200, MarginMode::Isolated, 1);
        assert_eq!(RiskEngineCommandDispatcher::margin_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::RiskNsf);

        let up = ups.get(UID).unwrap();
        assert_eq!(up.account(FUT_QUOTE), 50);
        assert_eq!(up.positions.get(&FUT_SYMBOL).unwrap().extra_margin, 0);
    }

    #[test]
    fn margin_adjustment_isolated_position_not_exists_returns_error() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);

        let cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 200, MarginMode::Isolated, 1);
        assert_eq!(RiskEngineCommandDispatcher::margin_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::RiskMarginPositionNotExists);
    }

    #[test]
    fn margin_adjustment_invalid_amount_when_price_non_positive() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, isolated_position(1));

        let zero_cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 0, MarginMode::Isolated, 1);
        assert_eq!(RiskEngineCommandDispatcher::margin_adjustment(&mut engine, &zero_cmd, &mut ups, &ssp), CommandResultCode::RiskInvalidAmount);

        let negative_cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, -1, MarginMode::Isolated, 2);
        assert_eq!(RiskEngineCommandDispatcher::margin_adjustment(&mut engine, &negative_cmd, &mut ups, &ssp), CommandResultCode::RiskInvalidAmount);
    }

    #[test]
    fn margin_adjustment_margin_trading_disabled_rejects() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, isolated_position(1));
        engine.cfg_margin_trading_enabled = false;

        let cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 200, MarginMode::Isolated, 1);
        assert_eq!(RiskEngineCommandDispatcher::margin_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::RiskMarginTradingDisabled);
    }

    #[test]
    fn margin_adjustment_duplicate_order_id_is_already_applied_same_noop() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, isolated_position(1));

        let cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 200, MarginMode::Isolated, 1);
        assert_eq!(RiskEngineCommandDispatcher::margin_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(
            RiskEngineCommandDispatcher::margin_adjustment(&mut engine, &cmd, &mut ups, &ssp),
            CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame
        );

        let up = ups.get(UID).unwrap();
        assert_eq!(up.account(FUT_QUOTE), 1_000 - 200);
        assert_eq!(up.positions.get(&FUT_SYMBOL).unwrap().extra_margin, 200);
    }

    #[test]
    fn margin_adjustment_margin_mode_mismatch_when_position_is_cross_but_cmd_says_isolated() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
        ups.get_mut(UID)
            .unwrap()
            .positions
            .insert(FUT_SYMBOL, SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Cross, 1));

        let cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 200, MarginMode::Isolated, 1);
        assert_eq!(RiskEngineCommandDispatcher::margin_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::RiskMarginModeMismatch);
    }

    #[test]
    fn margin_adjustment_cross_credits_account_directly_and_touches_adjustments_bucket() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);

        let cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_QUOTE, 300, MarginMode::Cross, 1);
        assert_eq!(RiskEngineCommandDispatcher::margin_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::Success);

        assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE), 1_000 + 300);
        assert_eq!(*engine.adjustments.get(&FUT_QUOTE).unwrap(), -300);
        assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE) + engine.adjustments.get(&FUT_QUOTE).unwrap(), 1_000);
    }

    #[test]
    fn margin_adjustment_unknown_user_is_auth_invalid_user() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let ssp = SymbolSpecificationProvider::new();

        let cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 200, MarginMode::Isolated, 1);
        assert_eq!(RiskEngineCommandDispatcher::margin_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::AuthInvalidUser);
    }

    #[test]
    fn margin_adjustment_routes_through_pre_process_command() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, isolated_position(1));

        let mut cmd = margin_adjustment_cmd(OrderAction::Bid, FUT_SYMBOL, 200, MarginMode::Isolated, 1);
        engine.pre_process_command(&mut cmd, &mut ups, &ssp);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        assert_eq!(ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().extra_margin, 200);
    }

    fn markprice_adjustment_cmd(symbol: i32, price: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol, price, ..Default::default() }
    }

    #[test]
    fn markprice_adjustment_sets_last_price_cache() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        let mut cmd = markprice_adjustment_cmd(FUT_SYMBOL, 250);
        assert_eq!(RiskEngineCommandDispatcher::markprice_adjustment(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(engine.mark_price(FUT_SYMBOL), Some(250));
    }

    #[test]
    fn markprice_adjustment_unknown_symbol_is_invalid_symbol_and_does_not_write_cache() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        let mut cmd = markprice_adjustment_cmd(9999, 250);
        assert_eq!(RiskEngineCommandDispatcher::markprice_adjustment(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::InvalidSymbol);
        assert_eq!(engine.mark_price(9999), None);
    }

    #[test]
    fn markprice_adjustment_then_place_order_risk_check_sees_new_mark_price() {
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(ssp.add_symbol(futures_spec(0, 0)), CommandResultCode::Success);
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1, ..Default::default() });
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        ups.get_mut(UID).unwrap().add_to_account(FUT_QUOTE, 10_000);
        let mut engine = RiskEngine::new();

        let mut place_cmd = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);
        assert_eq!(
            engine.place_order_risk_check(&mut place_cmd, &mut ups, &ssp),
            CommandResultCode::RiskMarkpriceNotAvailable,
            "mark price 未设时期货下单必须被拒"
        );

        let mut mark_cmd = markprice_adjustment_cmd(FUT_SYMBOL, 100);
        engine.pre_process_command(&mut mark_cmd, &mut ups, &ssp);
        assert_eq!(mark_cmd.result_code, Some(CommandResultCode::Success));
        assert_eq!(engine.mark_price(FUT_SYMBOL), Some(100));

        let mut place_cmd2 = futures_place_cmd(OrderAction::Bid, 10, 100, 1, MarginMode::Isolated, false);
        assert_eq!(
            engine.place_order_risk_check(&mut place_cmd2, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine,
            "MARKPRICE_ADJUSTMENT 落地后 R1 应能看到新 mark price 并放行"
        );
    }

    #[test]
    fn set_mark_price_test_hook_writes_cache_without_symbol_validation() {
        let mut engine = RiskEngine::new();
        engine.set_mark_price(424_242, 777);
        assert_eq!(engine.mark_price(424_242), Some(777));
    }

    #[test]
    fn markprice_adjustment_rejects_zero_price_and_prior_mark_survives_without_panic() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, isolated_position(1));

        let mut zero = markprice_adjustment_cmd(FUT_SYMBOL, 0);
        assert_eq!(RiskEngineCommandDispatcher::markprice_adjustment(&mut engine, &mut zero, &mut ups, &ssp), CommandResultCode::RiskInvalidAmount);
        assert_eq!(engine.mark_price(FUT_SYMBOL), Some(100), "被拒的 0 标记价不得污染缓存");

        let free = engine.calculate_free_futures_margin(ups.get(UID).unwrap(), FUT_QUOTE, &ssp);
        assert_eq!(free, 0, "空逐仓仓位(open_volume=0)净期货盈余为 0，且未 panic");
    }

    #[test]
    fn markprice_adjustment_rejects_negative_price_and_keeps_cache() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
        let mut neg = markprice_adjustment_cmd(FUT_SYMBOL, -5);
        assert_eq!(RiskEngineCommandDispatcher::markprice_adjustment(&mut engine, &mut neg, &mut ups, &ssp), CommandResultCode::RiskInvalidAmount);
        assert_eq!(engine.mark_price(FUT_SYMBOL), Some(100));
    }

    fn leverage_adjustment_cmd(symbol: i32, leverage: i32) -> OrderCommand {
        OrderCommand { command: OrderCommandType::LeverageAdjustment, uid: UID, symbol, leverage, ..Default::default() }
    }

    fn position_with_pending_buy(leverage: i32, pending_size: i64, pending_price: i64) -> SymbolPositionRecord {
        let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, leverage);
        pos.pending_buy_size = pending_size;
        pos.pending_buy_avg_price = pending_price;
        pos
    }

    #[test]
    fn leverage_adjustment_no_position_is_noop_success() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        let cmd = leverage_adjustment_cmd(FUT_SYMBOL, 5);
        assert_eq!(RiskEngineCommandDispatcher::leverage_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::Success);
    }

    #[test]
    fn leverage_adjustment_increase_never_needs_nsf_and_updates_position() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, position_with_pending_buy(1, 10, 100));

        let cmd = leverage_adjustment_cmd(FUT_SYMBOL, 2);
        assert_eq!(RiskEngineCommandDispatcher::leverage_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().leverage, 2);
    }

    #[test]
    fn leverage_adjustment_decrease_sufficient_balance_succeeds_and_updates() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 2_000, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, position_with_pending_buy(2, 10, 100));

        let cmd = leverage_adjustment_cmd(FUT_SYMBOL, 1);
        assert_eq!(RiskEngineCommandDispatcher::leverage_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().leverage, 1);
    }

    #[test]
    fn leverage_adjustment_decrease_insufficient_balance_is_nsf_and_leaves_leverage_unchanged() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 600, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, position_with_pending_buy(2, 10, 100));

        let cmd = leverage_adjustment_cmd(FUT_SYMBOL, 1);
        assert_eq!(RiskEngineCommandDispatcher::leverage_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::RiskNsf);
        assert_eq!(ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().leverage, 2);
    }

    #[test]
    fn leverage_adjustment_invalid_leverage_returns_error_and_leaves_leverage_unchanged() {
        let mut spec = futures_spec(0, 0);
        spec.max_leverage.insert(0, 1);
        let mut ssp = SymbolSpecificationProvider::new();
        assert_eq!(ssp.add_symbol(spec), CommandResultCode::Success);
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1, ..Default::default() });

        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(UID), CommandResultCode::Success);
        ups.get_mut(UID).unwrap().add_to_account(FUT_QUOTE, 100_000);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, position_with_pending_buy(1, 10, 100));

        let mut engine = RiskEngine::new();
        engine.last_price_cache.insert(FUT_SYMBOL, LastPriceCacheRecord::with_mark(100));

        let cmd = leverage_adjustment_cmd(FUT_SYMBOL, 5);
        assert_eq!(RiskEngineCommandDispatcher::leverage_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::RiskInvalidLeverage);
        assert_eq!(ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().leverage, 1);
    }

    #[test]
    fn leverage_adjustment_zero_normalizes_to_one() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, position_with_pending_buy(2, 10, 100));

        let cmd = leverage_adjustment_cmd(FUT_SYMBOL, 0);
        assert_eq!(RiskEngineCommandDispatcher::leverage_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::RiskNsf);
    }

    #[test]
    fn leverage_adjustment_margin_trading_disabled_rejects() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, position_with_pending_buy(1, 10, 100));
        engine.cfg_margin_trading_enabled = false;

        let cmd = leverage_adjustment_cmd(FUT_SYMBOL, 2);
        assert_eq!(RiskEngineCommandDispatcher::leverage_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::RiskMarginTradingDisabled);
    }

    #[test]
    fn leverage_adjustment_unknown_user_is_auth_invalid_user() {
        let mut ups = UserProfileService::new();
        let mut engine = RiskEngine::new();
        let ssp = SymbolSpecificationProvider::new();
        let cmd = leverage_adjustment_cmd(FUT_SYMBOL, 2);
        assert_eq!(RiskEngineCommandDispatcher::leverage_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::AuthInvalidUser);
    }

    #[test]
    fn leverage_adjustment_unknown_symbol_is_invalid_symbol() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        let cmd = leverage_adjustment_cmd(999_999, 2);
        assert_eq!(RiskEngineCommandDispatcher::leverage_adjustment(&mut engine, &cmd, &mut ups, &ssp), CommandResultCode::InvalidSymbol);
    }

    #[test]
    fn leverage_adjustment_routes_through_pre_process_command() {
        let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
        ups.get_mut(UID).unwrap().positions.insert(FUT_SYMBOL, position_with_pending_buy(1, 10, 100));

        let mut cmd = leverage_adjustment_cmd(FUT_SYMBOL, 2);
        engine.pre_process_command(&mut cmd, &mut ups, &ssp);

        assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
        assert_eq!(ups.get(UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().leverage, 2);
    }

    mod add_loan_tests {
        use super::*;
        use crate::core::common::batch_add_loan_command::{
            BatchAddLoanCommand, GlobalLoanConfig, RateCurveConfig, SymbolLoanConfig, UNSET, UNSET_AMOUNT,
        };
        use crate::core::processors::loan::loan_global_config::LoanGlobalConfig;

        fn add_loan_symbol_spec() -> CoreSymbolSpecification {
            CoreSymbolSpecification {
                symbol_id: SYMBOL,
                symbol_type: SymbolType::CurrencyExchangePair,
                base_currency: BASE,
                quote_currency: QUOTE,
                base_scale_k: 1,
                quote_scale_k: 1,
                ..Default::default()
            }
        }

        fn add_loan_setup() -> (RiskEngine, SymbolSpecificationProvider) {
            let engine = RiskEngine::new();
            let mut ssp = SymbolSpecificationProvider::new();
            ssp.add_symbol(add_loan_symbol_spec());
            ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
            ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
            (engine, ssp)
        }

        fn no_change_global() -> GlobalLoanConfig {
            GlobalLoanConfig {
                numeraire_currency: 0,
                cross_liquidation_ltv_bps: 0,
                cross_margin_call_ltv_bps: 0,
                loan_pool_utilization_cap_bps: 0,
                loan_liquidation_fee_bps: 0,
                ltv_liquidation_buffer_bps: 0,
                ltv_margin_call_buffer_bps: 0,
            }
        }

        fn unset_symbol_config(symbol_id: i32, initial: i32, weight: i32) -> SymbolLoanConfig {
            SymbolLoanConfig {
                symbol_id,
                loan_initial_ltv_bps: initial,
                loan_liquidation_ltv_bps: UNSET,
                loan_margin_call_ltv_bps: UNSET,
                loan_max_amount: UNSET_AMOUNT,
                loan_max_term_days: UNSET,
                collateral_weight_bps: weight,
            }
        }

        #[test]
        fn global_section_applies_partial_update_fields_and_skips_non_positive_ones() {
            let (mut engine, mut ssp) = add_loan_setup();
            let g = GlobalLoanConfig {
                numeraire_currency: QUOTE,
                cross_liquidation_ltv_bps: 9000,
                cross_margin_call_ltv_bps: 8500,
                loan_pool_utilization_cap_bps: 0,
                loan_liquidation_fee_bps: 300,
                ltv_liquidation_buffer_bps: 0,
                ltv_margin_call_buffer_bps: 1500,
            };
            let cmd = BatchAddLoanCommand { global: Some(g), symbol: None, rate_curve: None };
            engine.apply_add_loan(&cmd, &mut ssp);

            let cfg = engine.loan_service.global_config;
            assert_eq!(cfg.numeraire_currency, QUOTE);
            assert_eq!(cfg.cross_liquidation_ltv_bps, 9000);
            assert_eq!(cfg.cross_margin_call_ltv_bps, 8500);
            assert_eq!(cfg.loan_pool_utilization_cap_bps, LoanGlobalConfig::default().loan_pool_utilization_cap_bps);
            assert_eq!(cfg.loan_liquidation_fee_bps, 300);
            assert_eq!(cfg.ltv_liquidation_buffer_bps, LoanGlobalConfig::default().ltv_liquidation_buffer_bps);
            assert_eq!(cfg.ltv_margin_call_buffer_bps, 1500);
        }

        #[test]
        fn global_section_rejects_invalid_thresholds_and_leaves_config_untouched() {
            let (mut engine, mut ssp) = add_loan_setup();
            let g = GlobalLoanConfig {
                cross_liquidation_ltv_bps: 8000,
                cross_margin_call_ltv_bps: 8000,
                ..no_change_global()
            };
            let cmd = BatchAddLoanCommand { global: Some(g), symbol: None, rate_curve: None };
            engine.apply_add_loan(&cmd, &mut ssp);
            assert_eq!(engine.loan_service.global_config, LoanGlobalConfig::default());
        }

        #[test]
        fn global_section_rejects_when_numeraire_currency_spec_missing() {
            let (mut engine, mut ssp) = add_loan_setup();
            let g = GlobalLoanConfig { numeraire_currency: 999, ..no_change_global() };
            let cmd = BatchAddLoanCommand { global: Some(g), symbol: None, rate_curve: None };
            engine.apply_add_loan(&cmd, &mut ssp);
            assert_eq!(engine.loan_service.global_config, LoanGlobalConfig::default());
        }

        #[test]
        fn symbol_section_resolves_unset_fields_and_writes_collateral_weight_to_base_currency() {
            let (mut engine, mut ssp) = add_loan_setup();
            let s = unset_symbol_config(SYMBOL, 6_000, 7_000);
            let cmd = BatchAddLoanCommand { global: None, symbol: Some(s), rate_curve: None };
            engine.apply_add_loan(&cmd, &mut ssp);

            let spec = ssp.symbols.get(&SYMBOL).unwrap();
            assert_eq!(spec.loan_config.initial_ltv_bps, 6_000);
            assert_eq!(spec.loan_config.liquidation_ltv_bps, 8_000);
            assert_eq!(spec.loan_config.margin_call_ltv_bps, 7_000);
            assert_eq!(spec.loan_config.max_amount, 0);
            assert_eq!(spec.loan_config.max_term_days, 0);
            assert_eq!(ssp.currencies.get(&BASE).unwrap().collateral_weight_bps, 7_000);
            assert_eq!(ssp.currencies.get(&QUOTE).unwrap().collateral_weight_bps, 0);
        }

        #[test]
        fn symbol_section_kill_switch_zeroes_only_initial_and_preserves_the_rest() {
            let (mut engine, mut ssp) = add_loan_setup();
            ssp.symbols.get_mut(&SYMBOL).unwrap().loan_config.update(6_000, 8_000, 7_000, 500_000, 30);

            let s = unset_symbol_config(SYMBOL, 0, UNSET);
            let cmd = BatchAddLoanCommand { global: None, symbol: Some(s), rate_curve: None };
            engine.apply_add_loan(&cmd, &mut ssp);

            let spec = ssp.symbols.get(&SYMBOL).unwrap();
            assert_eq!(spec.loan_config.initial_ltv_bps, 0);
            assert_eq!(spec.loan_config.liquidation_ltv_bps, 8_000);
            assert_eq!(spec.loan_config.margin_call_ltv_bps, 7_000);
            assert_eq!(spec.loan_config.max_amount, 500_000);
            assert_eq!(spec.loan_config.max_term_days, 30);
            assert_eq!(ssp.currencies.get(&BASE).unwrap().collateral_weight_bps, 0);
        }

        #[test]
        fn symbol_section_rejects_collateral_weight_above_10000_and_applies_nothing() {
            let (mut engine, mut ssp) = add_loan_setup();
            let s = SymbolLoanConfig {
                symbol_id: SYMBOL,
                loan_initial_ltv_bps: 6_000,
                loan_liquidation_ltv_bps: 8_000,
                loan_margin_call_ltv_bps: 7_000,
                loan_max_amount: 0,
                loan_max_term_days: 0,
                collateral_weight_bps: 10_001,
            };
            let cmd = BatchAddLoanCommand { global: None, symbol: Some(s), rate_curve: None };
            engine.apply_add_loan(&cmd, &mut ssp);

            let spec = ssp.symbols.get(&SYMBOL).unwrap();
            assert_eq!(spec.loan_config, Default::default());
            assert_eq!(ssp.currencies.get(&BASE).unwrap().collateral_weight_bps, 0);
        }

        #[test]
        fn symbol_section_rejects_negative_collateral_weight_and_applies_nothing() {
            let (mut engine, mut ssp) = add_loan_setup();
            let s = SymbolLoanConfig {
                symbol_id: SYMBOL,
                loan_initial_ltv_bps: 6_000,
                loan_liquidation_ltv_bps: 8_000,
                loan_margin_call_ltv_bps: 7_000,
                loan_max_amount: 0,
                loan_max_term_days: 0,
                collateral_weight_bps: -2,
            };
            let cmd = BatchAddLoanCommand { global: None, symbol: Some(s), rate_curve: None };
            engine.apply_add_loan(&cmd, &mut ssp);

            let spec = ssp.symbols.get(&SYMBOL).unwrap();
            assert_eq!(spec.loan_config, Default::default());
        }

        #[test]
        fn symbol_section_rejects_unregistered_or_non_spot_symbol() {
            let (mut engine, mut ssp) = add_loan_setup();
            let missing = unset_symbol_config(999_999, 6_000, 5_000);
            let cmd = BatchAddLoanCommand { global: None, symbol: Some(missing), rate_curve: None };
            engine.apply_add_loan(&cmd, &mut ssp);
            assert_eq!(ssp.symbols.get(&999_999), None);
        }

        #[test]
        fn rate_curve_section_replaces_floating_curve_and_fixed_spread() {
            let (mut engine, mut ssp) = add_loan_setup();
            let rc = RateCurveConfig {
                base_bps: 300,
                kink_util_bps: 7_500,
                slope1_bps: 500,
                slope2_bps: 7_000,
                locked_rate_adjust_bps: -100,
            };
            let cmd = BatchAddLoanCommand { global: None, symbol: None, rate_curve: Some(rc) };
            engine.apply_add_loan(&cmd, &mut ssp);

            assert_eq!(engine.loan_service.floating_rate.base_bps, 300);
            assert_eq!(engine.loan_service.floating_rate.kink_util_bps, 7_500);
            assert_eq!(engine.loan_service.floating_rate.slope1_bps, 500);
            assert_eq!(engine.loan_service.floating_rate.slope2_bps, 7_000);
            assert_eq!(engine.loan_service.fixed_rate.locked_rate_adjust_bps, -100);
        }

        #[test]
        fn rate_curve_section_rejects_invalid_kink_and_leaves_curve_untouched() {
            let (mut engine, mut ssp) = add_loan_setup();
            let default_floating = engine.loan_service.floating_rate.clone();
            let rc = RateCurveConfig {
                base_bps: 300,
                kink_util_bps: 0,
                slope1_bps: 500,
                slope2_bps: 7_000,
                locked_rate_adjust_bps: -100,
            };
            let cmd = BatchAddLoanCommand { global: None, symbol: None, rate_curve: Some(rc) };
            engine.apply_add_loan(&cmd, &mut ssp);

            assert_eq!(engine.loan_service.floating_rate, default_floating);
            assert_eq!(engine.loan_service.fixed_rate.locked_rate_adjust_bps, 0);
        }

        #[test]
        fn one_invalid_section_does_not_prevent_the_other_two_from_applying() {
            let (mut engine, mut ssp) = add_loan_setup();
            let valid_global = GlobalLoanConfig {
                numeraire_currency: QUOTE,
                cross_liquidation_ltv_bps: 9_000,
                cross_margin_call_ltv_bps: 8_500,
                ..no_change_global()
            };
            let invalid_symbol = SymbolLoanConfig {
                symbol_id: SYMBOL,
                loan_initial_ltv_bps: 6_000,
                loan_liquidation_ltv_bps: 8_000,
                loan_margin_call_ltv_bps: 7_000,
                loan_max_amount: 0,
                loan_max_term_days: 0,
                collateral_weight_bps: 50_000,
            };
            let valid_rate_curve = RateCurveConfig {
                base_bps: 300,
                kink_util_bps: 7_500,
                slope1_bps: 500,
                slope2_bps: 7_000,
                locked_rate_adjust_bps: 25,
            };
            let cmd = BatchAddLoanCommand {
                global: Some(valid_global),
                symbol: Some(invalid_symbol),
                rate_curve: Some(valid_rate_curve),
            };
            engine.apply_add_loan(&cmd, &mut ssp);

            assert_eq!(engine.loan_service.global_config.numeraire_currency, QUOTE);
            assert_eq!(engine.loan_service.global_config.cross_liquidation_ltv_bps, 9_000);
            assert_eq!(engine.loan_service.floating_rate.base_bps, 300);
            assert_eq!(engine.loan_service.fixed_rate.locked_rate_adjust_bps, 25);
            assert_eq!(ssp.symbols.get(&SYMBOL).unwrap().loan_config, Default::default());
        }
    }

    mod reprice_loan_rates_tests {
        use super::*;
        use crate::core::processors::loan::rate::floating_rate_model::FloatingRateModel;

        fn reprice_cmd(timestamp: i64) -> OrderCommand {
            OrderCommand { command: OrderCommandType::RepriceLoanRates, timestamp, ..Default::default() }
        }

        fn run_full_pipeline(engine: &mut RiskEngine, cmd: &mut OrderCommand) {
            let mut ups = UserProfileService::new();
            let ssp = SymbolSpecificationProvider::new();
            engine.pre_process_command(cmd, &mut ups, &ssp);
            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            engine.handler_risk_release(cmd, &mut ups, &ssp);
        }

        #[test]
        fn single_currency_borrowed_and_available_reprices_to_curve_rate_for_computed_utilization() {
            let mut engine = RiskEngine::new();
            let cur = 5;
            engine.loan_service.loan_pool_borrowed.insert(cur, 8_000);
            engine.loan_service.loan_pool_available.insert(cur, 2_000);
            let expected_rate = engine.loan_service.floating_rate.curve_rate_bps(8_000);

            let mut cmd = reprice_cmd(1_000);
            run_full_pipeline(&mut engine, &mut cmd);

            assert_eq!(
                engine.loan_service.floating_rate.current_rate_bps_or_base(cur),
                expected_rate as i32,
                "R2 必须把 util 过曲线写成新生效利率"
            );
            assert_eq!(engine.loan_service.floating_rate.last_reprice_ts, 1_000);
        }

        #[test]
        fn advance_accumulator_runs_before_reprice_settling_the_old_interval_at_the_old_rate() {
            let mut engine = RiskEngine::new();
            let cur = 3;
            engine.loan_service.floating_rate.last_reprice_ts = 1_000;
            engine.loan_service.floating_rate.current_rate_bps.insert(cur, 300);
            engine.loan_service.loan_pool_borrowed.insert(cur, 9_000);
            engine.loan_service.loan_pool_available.insert(cur, 1_000);

            let mut cmd = reprice_cmd(2_000);
            run_full_pipeline(&mut engine, &mut cmd);

            let acc = *engine.loan_service.floating_rate.acc_rate_bps_ms.get(&cur).unwrap();
            assert_eq!(acc, 300 * 1_000, "旧区间必须按旧利率（300bps）结清");

            let new_rate = engine.loan_service.floating_rate.current_rate_bps_or_base(cur);
            assert_ne!(new_rate as i64, 300, "sanity: reprice 必须真正改变利率，测试才有意义");
        }

        #[test]
        fn multiple_currencies_all_reprice_correctly_regardless_of_processing_order() {
            let mut engine = RiskEngine::new();
            for &(cur, borrowed, available) in &[(9, 100, 900), (2, 500, 500), (5, 9_000, 1_000)] {
                engine.loan_service.loan_pool_borrowed.insert(cur, borrowed);
                engine.loan_service.loan_pool_available.insert(cur, available);
            }
            let expected: Vec<(i32, i64)> = vec![
                (2, FloatingRateModel::utilization_bps(500, 500)),
                (5, FloatingRateModel::utilization_bps(9_000, 1_000)),
                (9, FloatingRateModel::utilization_bps(100, 900)),
            ];

            let mut cmd = reprice_cmd(4_000);
            run_full_pipeline(&mut engine, &mut cmd);

            for (cur, util) in expected {
                let expected_rate = engine.loan_service.floating_rate.curve_rate_bps(util);
                assert_eq!(
                    engine.loan_service.floating_rate.current_rate_bps_or_base(cur),
                    expected_rate as i32,
                    "currency {cur} 的生效利率必须按其自身 util 算出，不受其它币种/处理顺序影响"
                );
            }
            assert_eq!(engine.loan_service.floating_rate.last_reprice_ts, 4_000, "全部事件处理完后只设一次");
        }

        #[test]
        fn last_reprice_ts_is_set_exactly_once_after_the_loop_not_stale_from_before() {
            let mut engine = RiskEngine::new();
            engine.loan_service.floating_rate.last_reprice_ts = 1;
            for &cur in &[1, 2, 3] {
                engine.loan_service.loan_pool_borrowed.insert(cur, 100);
                engine.loan_service.loan_pool_available.insert(cur, 100);
            }
            let mut cmd = reprice_cmd(9_999);
            run_full_pipeline(&mut engine, &mut cmd);
            assert_eq!(engine.loan_service.floating_rate.last_reprice_ts, 9_999);
        }

        #[test]
        fn empty_pool_does_not_advance_last_reprice_ts_mirroring_java_null_matcher_event_early_return() {
            let mut engine = RiskEngine::new();
            engine.loan_service.floating_rate.last_reprice_ts = 42;

            let mut cmd = reprice_cmd(9_000);
            run_full_pipeline(&mut engine, &mut cmd);

            assert_eq!(engine.loan_service.floating_rate.last_reprice_ts, 42, "空事件早退：last_reprice_ts 保持不变");
            assert!(cmd.loan_reprice_events.is_empty());
        }

        #[test]
        fn cold_start_before_any_reprice_current_rate_falls_back_to_base_then_reprice_sets_real_rate() {
            let mut engine = RiskEngine::new();
            let cur = 1;
            assert_eq!(engine.loan_service.floating_rate.current_rate_bps_or_base(cur), engine.loan_service.floating_rate.base_bps);

            engine.loan_service.loan_pool_borrowed.insert(cur, 4_000);
            engine.loan_service.loan_pool_available.insert(cur, 4_000);
            let mut cmd = reprice_cmd(500);
            run_full_pipeline(&mut engine, &mut cmd);

            let expected_rate = engine.loan_service.floating_rate.curve_rate_bps(5_000);
            assert_eq!(engine.loan_service.floating_rate.current_rate_bps_or_base(cur), expected_rate as i32);
            assert_ne!(expected_rate as i32, engine.loan_service.floating_rate.base_bps, "sanity: util=5000 必须偏离 base");
        }
    }

    mod internal_transfer_tests {
        use super::*;

        const TO_UID: i64 = 99;

        fn transfer_cmd(from_uid: i64, to_uid: i64, currency: i32, amount: i64, order_id: i64) -> OrderCommand {
            OrderCommand {
                command: OrderCommandType::InternalTransfer,
                uid: from_uid,
                size: to_uid,
                symbol: currency,
                price: amount,
                order_id,
                ..Default::default()
            }
        }

        fn run_full_pipeline(
            engine: &mut RiskEngine,
            cmd: &mut OrderCommand,
            ups: &mut UserProfileService,
            ssp: &SymbolSpecificationProvider,
        ) {
            engine.pre_process_command(cmd, ups, ssp);
            engine.handler_risk_release(cmd, ups, ssp);
        }

        #[test]
        fn successful_transfer_debits_from_credits_to_and_conserves_total() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
            let mut cmd = transfer_cmd(UID, TO_UID, FUT_QUOTE, 300, 1);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE), 700);
            assert_eq!(ups.get(TO_UID).unwrap().account(FUT_QUOTE), 300);
            assert_eq!(
                ups.get(UID).unwrap().account(FUT_QUOTE) + ups.get(TO_UID).unwrap().account(FUT_QUOTE),
                1_000,
                "conservation: from -= amount, to += amount"
            );
        }

        #[test]
        fn transfer_to_never_seen_uid_auto_creates_suspended_profile() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
            assert!(ups.get(TO_UID).is_none(), "sanity: to_uid must not pre-exist");
            let mut cmd = transfer_cmd(UID, TO_UID, FUT_QUOTE, 300, 1);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            let to = ups.get(TO_UID).expect("R2 must auto-create the target profile");
            assert_eq!(to.user_status, crate::core::common::user_status::UserStatus::Suspended);
            assert_eq!(to.account(FUT_QUOTE), 300);
        }

        #[test]
        fn self_transfer_rejected_before_any_balance_change() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
            let mut cmd = transfer_cmd(UID, UID, FUT_QUOTE, 300, 1);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::InternalTransferInvalidSelf));
            assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE), 1_000, "self-transfer must not touch the balance");
        }

        #[test]
        fn non_positive_amount_rejected() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
            let mut cmd = transfer_cmd(UID, TO_UID, FUT_QUOTE, 0, 1);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::RiskInvalidAmount));
            assert!(ups.get(TO_UID).is_none(), "rejected transfer must not auto-create the target");
        }

        #[test]
        fn negative_amount_rejected() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
            let mut cmd = transfer_cmd(UID, TO_UID, FUT_QUOTE, -5, 1);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::RiskInvalidAmount));
        }

        #[test]
        fn missing_from_profile_rejected_with_auth_invalid_user() {
            let mut ssp = SymbolSpecificationProvider::new();
            ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
            let mut ups = UserProfileService::new();
            let mut engine = RiskEngine::new();
            let mut cmd = transfer_cmd(UID, TO_UID, FUT_QUOTE, 300, 1);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::AuthInvalidUser));
            assert!(ups.get(TO_UID).is_none());
        }

        #[test]
        fn plain_insufficient_balance_rejected_with_nsf() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 100, 100);
            let mut cmd = transfer_cmd(UID, TO_UID, FUT_QUOTE, 300, 1);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::RiskNsf));
            assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE), 100, "NSF must not debit");
            assert!(ups.get(TO_UID).is_none());
        }

        #[test]
        fn loan_collateral_lock_makes_an_otherwise_sufficient_balance_nsf() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 300, 100);
            ups.get_mut(UID).unwrap().isolated_loans.insert(1, isolated_loan_with_collateral(1, FUT_QUOTE, 300));
            let mut cmd = transfer_cmd(UID, TO_UID, FUT_QUOTE, 300, 1);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::RiskNsf));
            assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE), 300, "NSF must not debit");
        }

        #[test]
        fn same_order_id_twice_is_claim_and_keep_not_double_debited() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 1_000, 100);
            let mut cmd1 = transfer_cmd(UID, TO_UID, FUT_QUOTE, 100, 42);
            run_full_pipeline(&mut engine, &mut cmd1, &mut ups, &ssp);
            assert_eq!(cmd1.result_code, Some(CommandResultCode::Success));
            assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE), 900);

            let mut cmd2 = transfer_cmd(UID, TO_UID, FUT_QUOTE, 100, 42);
            run_full_pipeline(&mut engine, &mut cmd2, &mut ups, &ssp);

            assert_eq!(
                cmd2.result_code,
                Some(CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame)
            );
            assert_eq!(ups.get(UID).unwrap().account(FUT_QUOTE), 900, "must not be double-debited");
            assert_eq!(ups.get(TO_UID).unwrap().account(FUT_QUOTE), 100, "must not be double-credited");
        }
    }

    mod funding_fee_tests {
        use super::*;
        use crate::core::common::position_direction::PositionDirection;

        const PAYER_UID: i64 = 1;
        const RECEIVER_UID: i64 = 2;

        fn funding_cmd(action: OrderAction, rate: i64, rate_scale_k: i64) -> OrderCommand {
            OrderCommand {
                command: OrderCommandType::SettleFundingfees,
                symbol: FUT_SYMBOL,
                action: Some(action),
                price: rate,
                size: rate_scale_k,
                order_id: 1,
                ..Default::default()
            }
        }

        fn run_full_pipeline(
            engine: &mut RiskEngine,
            cmd: &mut OrderCommand,
            ups: &mut UserProfileService,
            ssp: &SymbolSpecificationProvider,
        ) {
            engine.pre_process_command(cmd, ups, ssp);
            engine.handler_risk_release(cmd, ups, ssp);
        }

        fn setup_with_payer_and_receiver(
            mark_price: i64,
            payer_volume: i64,
            receiver_volume: i64,
        ) -> (RiskEngine, UserProfileService, SymbolSpecificationProvider) {
            let (engine, mut ups, ssp) = setup_futures(0, 0, 0, mark_price);
            ups.users.clear();
            assert_eq!(ups.add_empty_user_profile(PAYER_UID), CommandResultCode::Success);
            assert_eq!(ups.add_empty_user_profile(RECEIVER_UID), CommandResultCode::Success);
            ups.get_mut(PAYER_UID).unwrap().positions.insert(
                FUT_SYMBOL,
                SymbolPositionRecord {
                    direction: PositionDirection::Long,
                    open_volume: payer_volume,
                    ..SymbolPositionRecord::new(PAYER_UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
                },
            );
            ups.get_mut(RECEIVER_UID).unwrap().positions.insert(
                FUT_SYMBOL,
                SymbolPositionRecord {
                    direction: PositionDirection::Short,
                    open_volume: receiver_volume,
                    ..SymbolPositionRecord::new(RECEIVER_UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
                },
            );
            (engine, ups, ssp)
        }

        fn total_conserved(ups: &UserProfileService) -> i64 {
            ups.users
                .values()
                .map(|u| {
                    let accounts_sum: i64 = u.accounts.values().sum();
                    let profit_sum: i64 = u.positions.values().map(|p| p.profit).sum();
                    accounts_sum + profit_sum
                })
                .sum()
        }

        #[test]
        fn invalid_symbol_rejected() {
            let (mut engine, mut ups, ssp) = setup_with_payer_and_receiver(100, 100, 100);
            let mut cmd = funding_cmd(OrderAction::Bid, 5, 1000);
            cmd.symbol = 99_999;
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);
            assert_eq!(cmd.result_code, Some(CommandResultCode::InvalidSymbol));
        }

        #[test]
        fn missing_mark_price_rejected_before_size_gate() {
            let mut ssp = SymbolSpecificationProvider::new();
            assert_eq!(ssp.add_symbol(futures_spec(0, 0)), CommandResultCode::Success);
            ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
            ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1, ..Default::default() });
            let mut ups = UserProfileService::new();
            let mut engine = RiskEngine::new();
            let mut cmd = funding_cmd(OrderAction::Bid, 5, 0);
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);
            assert_eq!(cmd.result_code, Some(CommandResultCode::RiskMarkpriceNotAvailable));
        }

        #[test]
        fn non_positive_rate_scale_k_rejected_with_invalid_amount() {
            let (mut engine, mut ups, ssp) = setup_with_payer_and_receiver(100, 100, 100);
            let mut cmd = funding_cmd(OrderAction::Bid, 5, 0);
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);
            assert_eq!(cmd.result_code, Some(CommandResultCode::RiskInvalidAmount));
        }

        #[test]
        fn full_pipeline_settles_zero_sum_and_conserves_total() {
            let (mut engine, mut ups, ssp) = setup_with_payer_and_receiver(10, 100, 100);
            let before = total_conserved(&ups);
            let mut cmd = funding_cmd(OrderAction::Bid, 5, 1000);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert_eq!(ups.get(PAYER_UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().profit, -5);
            assert_eq!(ups.get(RECEIVER_UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().profit, 5);
            assert_eq!(total_conserved(&ups), before, "zero-sum: Σaccounts + Σposition.profit unchanged");
        }

        #[test]
        fn empty_receiver_pool_produces_no_event_and_no_state_change() {
            let (engine0, mut ups, ssp) = setup_with_payer_and_receiver(10, 100, 100);
            let mut engine = engine0;
            ups.get_mut(RECEIVER_UID).unwrap().positions.remove(&FUT_SYMBOL);
            let before = total_conserved(&ups);
            let mut cmd = funding_cmd(OrderAction::Bid, 5, 1000);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert_eq!(ups.get(PAYER_UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().profit, 0, "no event -> no settlement");
            assert_eq!(total_conserved(&ups), before);
        }
    }

    mod if_takeover_tests {
        use super::*;
        use crate::core::common::position_direction::PositionDirection;
        use crate::core::processors::liquidation::liquidation_service::IfNotional;

        const TAKER_UID: i64 = 42;

        fn run_full_pipeline(
            engine: &mut RiskEngine,
            cmd: &mut OrderCommand,
            ups: &mut UserProfileService,
            ssp: &SymbolSpecificationProvider,
        ) {
            engine.pre_process_command(cmd, ups, ssp);
            engine.handler_risk_release(cmd, ups, ssp);
        }

        fn if_takeover_cmd(action: OrderAction, size: i64, price: i64) -> OrderCommand {
            OrderCommand {
                command: OrderCommandType::IfTakeover,
                symbol: FUT_SYMBOL,
                uid: TAKER_UID,
                action: Some(action),
                size,
                price,
                order_id: 1,
                ..Default::default()
            }
        }

        fn setup_with_taker_long_position(
            mark_price: i64,
            open_volume: i64,
            open_price_sum: i64,
        ) -> (RiskEngine, UserProfileService, SymbolSpecificationProvider) {
            let (engine, mut ups, ssp) = setup_futures(0, 0, 0, mark_price);
            ups.users.clear();
            assert_eq!(ups.add_empty_user_profile(TAKER_UID), CommandResultCode::Success);
            ups.get_mut(TAKER_UID).unwrap().positions.insert(
                FUT_SYMBOL,
                SymbolPositionRecord {
                    direction: PositionDirection::Long,
                    open_volume,
                    open_price_sum,
                    ..SymbolPositionRecord::new(TAKER_UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
                },
            );
            (engine, ups, ssp)
        }

        #[test]
        fn full_cover_accepts_position_closes_taker_and_settles_pnl() {
            let (mut engine, mut ups, ssp) = setup_with_taker_long_position(100, 100, 9_000);
            engine.liquidation_service.deposit_to_insurance_fund(FUT_SYMBOL, 100_000);
            let taker_account_before = ups.get(TAKER_UID).unwrap().account(FUT_QUOTE);

            let mut cmd = if_takeover_cmd(OrderAction::Bid, 100, 100);
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));

            let key = FUT_SYMBOL as i64;
            let if_pos = engine.liquidation_service.positions.get(&key).expect("IF position must be recorded");
            assert_eq!(if_pos.open_volume, 100);
            assert_eq!(if_pos.open_price_sum, 100 * 100, "spend = size*price = 10000");

            assert_eq!(
                engine.liquidation_service.notionals[&FUT_SYMBOL],
                IfNotional { available: 100_000 - 10_000, reserved: 0 },
                "finalize 必须释放 reserved（即使接管成功也要释放，跟 R1 对称）"
            );

            assert!(!ups.get(TAKER_UID).unwrap().positions.contains_key(&FUT_SYMBOL), "全平仓后仓位记录必须被移除");
            assert_eq!(
                ups.get(TAKER_UID).unwrap().account(FUT_QUOTE),
                taker_account_before + 1_000,
                "已实现 PnL 必须精确结算进账户（currency scale 恒等换算，因 base/quote/currency_scale_k 全为 1）"
            );
        }

        #[test]
        fn undersize_rejects_all_or_nothing_but_still_releases_preview() {
            let (mut engine, mut ups, ssp) = setup_with_taker_long_position(100, 100, 9_000);
            engine.liquidation_service.deposit_to_insurance_fund(FUT_SYMBOL, 500);

            let mut cmd = if_takeover_cmd(OrderAction::Bid, 100, 100);
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success), "REJECT 是 matcher-event 级别信号，命令级结果码仍是 Success（同 ADL/FundingFee 先例）");

            assert!(engine.liquidation_service.positions.is_empty(), "全拒不产生任何 IFPositionRecord");

            assert_eq!(
                engine.liquidation_service.notionals[&FUT_SYMBOL],
                IfNotional { available: 500, reserved: 0 },
                "available 分毫未动（从未 accept），reserved 必须归零（finalize 全拒路径仍释放 preview）"
            );

            let taker_spr = ups.get(TAKER_UID).unwrap().positions.get(&FUT_SYMBOL).expect("REJECT 不应关闭 taker 仓位");
            assert_eq!(taker_spr.open_volume, 100);
            assert_eq!(taker_spr.open_price_sum, 9_000);
        }

        #[test]
        fn undersize_reject_escalates_flow_to_adl() {
            use crate::core::common::matcher_event_type::MatcherEventType;
            use crate::core::processors::liquidation::liquidation_flow::{LiquidationFlow, LiquidationState};

            let (mut engine, mut ups, ssp) = setup_with_taker_long_position(100, 100, 9_000);
            engine.liquidation_service.deposit_to_insurance_fund(FUT_SYMBOL, 500);
            engine.liquidation_engine.is_running = true;

            {
                let mut flow = LiquidationFlow::new(100, 100, 1);
                flow.state = LiquidationState::WaitIfExecution;
                ups.get_mut(TAKER_UID).unwrap().positions.get_mut(&FUT_SYMBOL).unwrap().liquidation_flow = Some(flow);
            }

            let generated: std::rc::Rc<std::cell::RefCell<Vec<OrderCommand>>> = std::rc::Rc::new(std::cell::RefCell::new(Vec::new()));
            engine.liquidation_engine.set_command_submitter(std::rc::Rc::new(std::cell::RefCell::new(
                crate::core::processors::liquidation::command_submitter::VecCommandSink(generated.clone()),
            )));

            let mut cmd = if_takeover_cmd(OrderAction::Bid, 100, 100);
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert!(
                matches!(cmd.matcher_event.as_deref(), Some(ev) if ev.event_type == MatcherEventType::Reject),
                "IF 全拒必须合成 REJECT matcher_event 供状态机读"
            );
            let flow = ups.get(TAKER_UID).unwrap().positions.get(&FUT_SYMBOL).unwrap().liquidation_flow.expect("flow 应仍存在");
            assert_eq!(flow.state, LiquidationState::WaitAdlExecution, "IF 不足必须升级到 WAIT_ADL");
            assert!(
                generated.borrow().iter().any(|c| c.command == OrderCommandType::AutoDeleveraging),
                "IF 不足必须入队 ADL 命令（ADL 后备不能静默失效）"
            );
        }

        #[test]
        fn if_deposit_and_withdraw_hedge_adjustments_and_conserve() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);

            let mut deposit_cmd = OrderCommand {
                command: OrderCommandType::IfDeposit,
                symbol: FUT_SYMBOL,
                price: 700,
                order_id: 1,
                ..Default::default()
            };
            engine.pre_process_command(&mut deposit_cmd, &mut ups, &ssp);
            assert_eq!(deposit_cmd.result_code, Some(CommandResultCode::Success));
            assert_eq!(engine.liquidation_service.notionals[&FUT_SYMBOL].available, 700);
            assert_eq!(*engine.adjustments.get(&FUT_QUOTE).unwrap(), -700, "对冲桶反向记账");

            let mut withdraw_cmd = OrderCommand {
                command: OrderCommandType::IfWithdraw,
                symbol: FUT_SYMBOL,
                price: 300,
                order_id: 2,
                ..Default::default()
            };
            engine.pre_process_command(&mut withdraw_cmd, &mut ups, &ssp);
            assert_eq!(withdraw_cmd.result_code, Some(CommandResultCode::Success));
            assert_eq!(engine.liquidation_service.notionals[&FUT_SYMBOL].available, 400);
            assert_eq!(*engine.adjustments.get(&FUT_QUOTE).unwrap(), -400, "withdraw 反向抵消一部分 deposit 的对冲");

            assert_eq!(
                engine.liquidation_service.notionals[&FUT_SYMBOL].available + engine.adjustments[&FUT_QUOTE],
                0,
                "IF 充提必须与 adjustments 桶精确对冲闭环"
            );
        }

        #[test]
        fn if_withdraw_over_available_is_rejected_and_leaves_state_unchanged() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
            let mut deposit_cmd = OrderCommand {
                command: OrderCommandType::IfDeposit,
                symbol: FUT_SYMBOL,
                price: 100,
                order_id: 1,
                ..Default::default()
            };
            engine.pre_process_command(&mut deposit_cmd, &mut ups, &ssp);

            let mut withdraw_cmd = OrderCommand {
                command: OrderCommandType::IfWithdraw,
                symbol: FUT_SYMBOL,
                price: 101,
                order_id: 2,
                ..Default::default()
            };
            engine.pre_process_command(&mut withdraw_cmd, &mut ups, &ssp);

            assert_eq!(withdraw_cmd.result_code, Some(CommandResultCode::RiskIfInsufficient));
            assert_eq!(engine.liquidation_service.notionals[&FUT_SYMBOL].available, 100, "拒绝的提取不改状态");
            assert_eq!(*engine.adjustments.get(&FUT_QUOTE).unwrap(), -100, "拒绝的提取不触碰 adjustments");
        }

        #[test]
        fn if_deposit_unknown_symbol_is_invalid_symbol() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
            let mut cmd = OrderCommand {
                command: OrderCommandType::IfDeposit,
                symbol: 99_999,
                price: 100,
                order_id: 1,
                ..Default::default()
            };
            engine.pre_process_command(&mut cmd, &mut ups, &ssp);
            assert_eq!(cmd.result_code, Some(CommandResultCode::InvalidSymbol));
        }

        #[test]
        fn if_deposit_non_positive_amount_is_invalid_amount() {
            let (mut engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
            let mut cmd =
                OrderCommand { command: OrderCommandType::IfDeposit, symbol: FUT_SYMBOL, price: 0, order_id: 1, ..Default::default() };
            engine.pre_process_command(&mut cmd, &mut ups, &ssp);
            assert_eq!(cmd.result_code, Some(CommandResultCode::RiskInvalidAmount));
        }

        #[test]
        fn taker_position_lookup_goes_through_create_positions_key_not_raw_symbol() {
            let (mut engine, mut ups, ssp) = setup_with_taker_long_position(100, 100, 9_000);
            engine.liquidation_service.deposit_to_insurance_fund(FUT_SYMBOL, 100_000);

            let position_key = ups.get(TAKER_UID).unwrap().create_positions_key(FUT_SYMBOL, OrderAction::Bid, OrderCommandType::IfTakeover);
            assert_eq!(position_key, FUT_SYMBOL, "ONEWAY: create_positions_key 退化为裸 symbol");

            let mut cmd = if_takeover_cmd(OrderAction::Bid, 100, 100);
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert!(!ups.get(TAKER_UID).unwrap().positions.contains_key(&position_key));
        }
    }

    mod adl_tests {
        use super::*;
        use crate::core::common::position_direction::PositionDirection;

        const TAKER_UID: i64 = 42;
        const CP_A: i64 = 100;
        const CP_B: i64 = 101;
        const CP_C: i64 = 102;

        fn run_full_pipeline(
            engine: &mut RiskEngine,
            cmd: &mut OrderCommand,
            ups: &mut UserProfileService,
            ssp: &SymbolSpecificationProvider,
        ) {
            engine.pre_process_command(cmd, ups, ssp);
            engine.handler_risk_release(cmd, ups, ssp);
        }

        fn adl_cmd(action: OrderAction, size: i64, bankruptcy_price: i64) -> OrderCommand {
            OrderCommand {
                command: OrderCommandType::AutoDeleveraging,
                symbol: FUT_SYMBOL,
                uid: TAKER_UID,
                action: Some(action),
                size,
                price: bankruptcy_price,
                order_id: 1,
                ..Default::default()
            }
        }

        fn short_position(uid: i64, open_volume: i64, open_price_sum: i64, open_init_margin_sum: i64) -> SymbolPositionRecord {
            SymbolPositionRecord {
                direction: PositionDirection::Short,
                open_volume,
                open_price_sum,
                open_init_margin_sum,
                ..SymbolPositionRecord::new(uid, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
            }
        }

        fn setup_taker_and_three_candidates() -> (RiskEngine, UserProfileService, SymbolSpecificationProvider) {
            let (engine, mut ups, ssp) = setup_futures(0, 0, 0, 100);
            ups.users.clear();
            for uid in [TAKER_UID, CP_A, CP_B, CP_C] {
                assert_eq!(ups.add_empty_user_profile(uid), CommandResultCode::Success);
            }
            ups.get_mut(TAKER_UID).unwrap().positions.insert(
                FUT_SYMBOL,
                SymbolPositionRecord {
                    direction: PositionDirection::Long,
                    open_volume: 100,
                    open_price_sum: 9_000,
                    ..SymbolPositionRecord::new(TAKER_UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
                },
            );
            ups.get_mut(CP_A).unwrap().positions.insert(FUT_SYMBOL, short_position(CP_A, 60, 8_000, 800));
            ups.get_mut(CP_B).unwrap().positions.insert(FUT_SYMBOL, short_position(CP_B, 80, 10_400, 2_000));
            ups.get_mut(CP_C).unwrap().positions.insert(FUT_SYMBOL, short_position(CP_C, 50, 6_000, 6_000));
            (engine, ups, ssp)
        }

        #[test]
        fn r1_selects_by_risk_score_desc_and_stops_once_size_exhausted() {
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            let mut cmd = adl_cmd(OrderAction::Bid, 100, 100);

            engine.pre_process_command(&mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert_eq!(cmd.adl_user_positions.len(), 2, "只有 A、B 被选中，C 预算耗尽前摸不到");
            assert_eq!(cmd.adl_user_positions[0].uid, CP_A, "得分最高的候选必须排第一个被选中");
            assert_eq!(cmd.adl_user_positions[0].volume, 60);
            assert_eq!(cmd.adl_user_positions[1].uid, CP_B);
            assert_eq!(cmd.adl_user_positions[1].volume, 40, "B 只能部分取：min(available=80, remaining=40)=40");

            assert_eq!(ups.get(CP_A).unwrap().positions[&FUT_SYMBOL].pending_adl_size, 60);
            assert_eq!(ups.get(CP_B).unwrap().positions[&FUT_SYMBOL].pending_adl_size, 40);
            assert_eq!(ups.get(CP_C).unwrap().positions[&FUT_SYMBOL].pending_adl_size, 0, "预算耗尽前摸不到的候选，pending_adl_size 必须保持 0");
        }

        #[test]
        fn merge_rewrites_cmd_size_to_actual_consumed_when_candidates_fall_short() {
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            ups.get_mut(TAKER_UID).unwrap().positions.get_mut(&FUT_SYMBOL).unwrap().open_volume = 300;
            ups.get_mut(TAKER_UID).unwrap().positions.get_mut(&FUT_SYMBOL).unwrap().open_price_sum = 27_000;
            let mut cmd = adl_cmd(OrderAction::Bid, 200, 100);

            engine.pre_process_command(&mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert_eq!(cmd.size, 190, "merge 必须把 cmd.size 改写为实际消费总量，不是原始请求量");
            assert_eq!(cmd.adl_user_positions.len(), 3, "预算够大，A/B/C 全部入选");
            let total_events: i64 = cmd.adl_events.iter().map(|(_, v)| v).sum();
            assert_eq!(total_events, 190, "events 里每条 exec_volume 之和必须等于改写后的 cmd.size");
        }

        #[test]
        fn finalize_releases_pending_adl_size_symmetrically_for_every_selected_candidate() {
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            let mut cmd = adl_cmd(OrderAction::Bid, 100, 100);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert!(!ups.get(CP_A).unwrap().positions.contains_key(&FUT_SYMBOL), "A 60 手全部被吃，仓位清空后移除");
            let b_pos = &ups.get(CP_B).unwrap().positions[&FUT_SYMBOL];
            assert_eq!(b_pos.pending_adl_size, 0, "finalize 必须把 B 的 pending_adl_size 释放回 0");
            assert_eq!(b_pos.open_volume, 40, "B 原 80 手，被吃 40，剩 40");
            assert_eq!(ups.get(CP_C).unwrap().positions[&FUT_SYMBOL].pending_adl_size, 0);
            assert_eq!(ups.get(CP_C).unwrap().positions[&FUT_SYMBOL].open_volume, 50, "C 完全未受影响");
        }

        #[test]
        fn full_pipeline_closes_taker_and_settles_realized_pnl_exactly() {
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            let mut cmd = adl_cmd(OrderAction::Bid, 100, 100);

            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert!(!ups.get(TAKER_UID).unwrap().positions.contains_key(&FUT_SYMBOL));
            assert_eq!(ups.get(TAKER_UID).unwrap().account(FUT_QUOTE), 1_000);
            assert!(!ups.get(CP_A).unwrap().positions.contains_key(&FUT_SYMBOL));
            assert_eq!(ups.get(CP_A).unwrap().account(FUT_QUOTE), 2_000);
            assert_eq!(ups.get(CP_B).unwrap().account(FUT_QUOTE), 0);
            assert_eq!(ups.get(CP_B).unwrap().positions[&FUT_SYMBOL].profit, 0, "部分平仓不实现盈亏");
            assert_eq!(ups.get(CP_C).unwrap().account(FUT_QUOTE), 0);
            assert_eq!(ups.get(CP_C).unwrap().positions[&FUT_SYMBOL].open_volume, 50);
        }

        #[test]
        fn counterparty_profile_vanished_between_r1_and_r2_is_skipped_not_error() {
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            let mut cmd = adl_cmd(OrderAction::Bid, 100, 100);

            engine.pre_process_command(&mut cmd, &mut ups, &ssp);

            ups.users.remove(&CP_B);

            engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success), "counterparty 消失不影响命令级结果码");
            assert!(!ups.get(CP_A).unwrap().positions.contains_key(&FUT_SYMBOL));
            assert!(!ups.get(TAKER_UID).unwrap().positions.contains_key(&FUT_SYMBOL));
        }

        #[test]
        fn counterparty_position_vanished_between_r1_and_r2_is_skipped_not_error() {
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            let mut cmd = adl_cmd(OrderAction::Bid, 100, 100);

            engine.pre_process_command(&mut cmd, &mut ups, &ssp);

            ups.get_mut(CP_B).unwrap().positions.remove(&FUT_SYMBOL);
            let b_account_before = ups.get(CP_B).unwrap().account(FUT_QUOTE);

            engine.handler_risk_release(&mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert_eq!(ups.get(CP_B).unwrap().account(FUT_QUOTE), b_account_before, "仓位已消失，apply 与 finalize 释放都必须 no-op，不能凭空造账");
            assert!(!ups.get(CP_A).unwrap().positions.contains_key(&FUT_SYMBOL), "A 不受 B 消失影响，正常结算");
        }

        #[test]
        fn hedge_mode_uses_create_positions_key_not_raw_symbol() {
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            ups.get_mut(TAKER_UID).unwrap().position_mode = PositionMode::Hedge;
            ups.get_mut(CP_A).unwrap().position_mode = PositionMode::Hedge;
            ups.get_mut(TAKER_UID).unwrap().positions.get_mut(&FUT_SYMBOL).unwrap().open_volume = 60;
            ups.get_mut(TAKER_UID).unwrap().positions.get_mut(&FUT_SYMBOL).unwrap().open_price_sum = 5_400;
            let taker_pos = ups.get_mut(TAKER_UID).unwrap().positions.remove(&FUT_SYMBOL).unwrap();
            ups.get_mut(TAKER_UID).unwrap().positions.insert(FUT_SYMBOL, taker_pos);
            let cp_a_pos = ups.get_mut(CP_A).unwrap().positions.remove(&FUT_SYMBOL).unwrap();
            ups.get_mut(CP_A).unwrap().positions.insert(-FUT_SYMBOL, cp_a_pos);

            let taker_key = ups.get(TAKER_UID).unwrap().create_positions_key(FUT_SYMBOL, OrderAction::Bid, OrderCommandType::AutoDeleveraging);
            let cp_a_key = ups.get(CP_A).unwrap().create_positions_key(FUT_SYMBOL, OrderAction::Ask, OrderCommandType::AutoDeleveraging);
            assert_eq!(taker_key, FUT_SYMBOL);
            assert_eq!(cp_a_key, -FUT_SYMBOL, "HEDGE 下 counterparty(SHORT) 的 key 必须是 -symbol");

            let mut cmd = adl_cmd(OrderAction::Bid, 60, 100);
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert!(!ups.get(TAKER_UID).unwrap().positions.contains_key(&taker_key), "taker 必须按 create_positions_key 算出的 key 被关闭，不是巧合命中裸 symbol");
            assert!(!ups.get(CP_A).unwrap().positions.contains_key(&cp_a_key), "counterparty 必须按 -symbol 这个 key 被关闭，证明查找路径确实走了 create_positions_key 而非裸 symbol");
        }

        #[test]
        fn non_positive_size_is_noop_success() {
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            let mut cmd = adl_cmd(OrderAction::Bid, 0, 100);
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);
            assert_eq!(cmd.result_code, Some(CommandResultCode::Success));
            assert!(cmd.adl_user_positions.is_empty());
            assert!(cmd.adl_events.is_empty());
            assert!(ups.get(TAKER_UID).unwrap().positions.contains_key(&FUT_SYMBOL), "size<=0 不应关掉 taker 的仓");
        }

        #[test]
        fn no_eligible_candidates_rejects_without_touching_taker_position() {
            let (mut engine, mut ups, ssp) = setup_taker_and_three_candidates();
            for uid in [CP_A, CP_B, CP_C] {
                ups.get_mut(uid).unwrap().positions.get_mut(&FUT_SYMBOL).unwrap().direction = PositionDirection::Long;
            }
            let mut cmd = adl_cmd(OrderAction::Bid, 100, 100);
            run_full_pipeline(&mut engine, &mut cmd, &mut ups, &ssp);

            assert_eq!(cmd.result_code, Some(CommandResultCode::Success), "空候选是 matcher-event 级别的全拒，不是命令失败（同 IF_TAKEOVER 先例）");
            assert!(cmd.adl_events.is_empty());
            assert!(ups.get(TAKER_UID).unwrap().positions.contains_key(&FUT_SYMBOL), "全拒（events 为空）时 finalize 不应关闭 taker 自己的仓");
        }
    }
}
