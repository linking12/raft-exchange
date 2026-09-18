use std::collections::BTreeMap;
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::fund_event::{FundEvent, FundEventType, SYSTEM_TRIGGERED_ORDER_ID};
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::order_action::OrderAction;
use crate::core::common::position_direction::PositionDirection;
use crate::core::common::position_mode::PositionMode;
use crate::core::common::symbol_type::SymbolType;
use crate::core::processors::internaltransfer_command_processor::InternalTransferCommandProcessor;
use crate::core::processors::loanratepricing_command_processor::LoanRatePricingCommandProcessor;
use crate::core::processors::twostep_command_processor::{TwoStepCommandProcessor, TwoStepContext};
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::utils::core_arithmetic_utils as arithmetic;

pub struct RiskEngineCommandDispatcher;

impl RiskEngineCommandDispatcher {

    pub fn dispatch(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let rc = match cmd.command {
            OrderCommandType::AddUser => Self::add_user(engine, cmd, ups),
            OrderCommandType::BalanceAdjustment => Self::balance_adjustment(engine, cmd, ups, ssp),
            OrderCommandType::MarginAdjustment => Self::margin_adjustment(engine, cmd, ups, ssp),
            OrderCommandType::LeverageAdjustment => Self::leverage_adjustment(engine, cmd, ups, ssp),
            OrderCommandType::MarkpriceAdjustment => Self::markprice_adjustment(engine, cmd, ups, ssp),
            OrderCommandType::RepriceLoanRates => {
                let mut ctx = TwoStepContext::new(engine, ups, ssp);
                LoanRatePricingCommandProcessor.collect(&mut ctx, cmd)
            }
            OrderCommandType::InternalTransfer => {
                let mut ctx = TwoStepContext::new(engine, ups, ssp);
                InternalTransferCommandProcessor.collect(&mut ctx, cmd)
            }
            OrderCommandType::IfDeposit => Self::if_deposit(engine, cmd, ssp),
            OrderCommandType::IfWithdraw => Self::if_withdraw(engine, cmd, ssp),
            OrderCommandType::SettlePnl => Self::settle_pnl(engine, cmd, ups, ssp),
            OrderCommandType::SuspendUser => Self::suspend_user(engine, cmd, ups),
            OrderCommandType::ResumeUser => ups.resume_user_profile(cmd.uid),
            OrderCommandType::PositionModeAdjustment => Self::position_mode_adjustment(engine, cmd, ups),
            OrderCommandType::ResetFee => Self::reset_fee(engine, cmd, ssp),
            OrderCommandType::SystemLiquidationNotify => CommandResultCode::Success,
            _ => CommandResultCode::MatchingUnsupportedCommand,
        };
        if rc == CommandResultCode::Success {
            match cmd.command {
                OrderCommandType::BalanceAdjustment => Self::emit_balance_adjustment_event(engine, cmd, ups, ssp),
                OrderCommandType::MarginAdjustment => Self::emit_margin_adjust_events(engine, cmd, ups, ssp),
                _ => {}
            }
        }
        rc
    }

    pub fn add_user(_engine: &mut RiskEngine, cmd: &OrderCommand, ups: &mut UserProfileService) -> CommandResultCode {
        ups.add_empty_user_profile(cmd.uid)
    }

    pub fn balance_adjustment(
        engine: &mut RiskEngine,
        cmd: &OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let currency = cmd.symbol;
        let amount_diff = cmd.price;

        let user_profile = match ups.get_mut(cmd.uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };

        if amount_diff < 0 {
            let withdrawal_amount = -amount_diff;
            if engine.withdrawable_balance(user_profile, currency, ssp) - withdrawal_amount < 0 {
                return CommandResultCode::RiskNsf;
            }
        }

        if amount_diff < 0 && user_profile.account(currency) + amount_diff < 0 {
            return CommandResultCode::UserMgmtAccountBalanceAdjustmentNsf;
        }

        if !user_profile.try_claim_tx(cmd.order_id, cmd.timestamp) {
            return CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame;
        }

        user_profile.add_to_account(currency, amount_diff);

        let adj_type = cmd
            .order_type
            .map(|ot| crate::core::common::balance_adjustment_type::BalanceAdjustmentType::of(ot.code()))
            .unwrap_or(crate::core::common::balance_adjustment_type::BalanceAdjustmentType::Adjustment);
        let bucket = match adj_type {
            crate::core::common::balance_adjustment_type::BalanceAdjustmentType::Suspend => &mut engine.suspends,
            crate::core::common::balance_adjustment_type::BalanceAdjustmentType::Adjustment => &mut engine.adjustments,
        };
        *bucket.entry(currency).or_insert(0) -= amount_diff;

        CommandResultCode::Success
    }

    fn suspend_user(engine: &mut RiskEngine, cmd: &OrderCommand, ups: &mut UserProfileService) -> CommandResultCode {
        const DUST_SAFETY_LIMIT: i64 = 1000;
        if let Some(up) = ups.get_mut(cmd.uid) {
            if !up.positions.values().any(|p| !p.is_empty()) {
                let mut eligible = up
                    .exchange_locked
                    .iter()
                    .all(|(&c, &locked)| locked < DUST_SAFETY_LIMIT && up.accounts.get(&c).copied().unwrap_or(0) == locked);
                if eligible {
                    eligible = up
                        .accounts
                        .iter()
                        .all(|(&c, &acc)| acc == 0 || up.exchange_locked.get(&c).copied().unwrap_or(0) == acc);
                }
                if eligible {
                    let dust: Vec<(i32, i64)> =
                        up.exchange_locked.iter().filter(|(_, &v)| v > 0).map(|(&c, &v)| (c, v)).collect();
                    for (c, d) in dust {
                        *up.accounts.entry(c).or_insert(0) -= d;
                        *engine.fees.entry(c).or_insert(0) += d;
                    }
                    up.exchange_locked.clear();
                }
            }
        }
        ups.suspend_user_profile(cmd.uid)
    }

    fn position_mode_adjustment(_engine: &mut RiskEngine, cmd: &OrderCommand, ups: &mut UserProfileService) -> CommandResultCode {
        let Some(up) = ups.get_mut(cmd.uid) else {
            return CommandResultCode::AuthInvalidUser;
        };
        let target = PositionMode::of_code(cmd.action.map(|a| a.code()).unwrap_or(0));
        if up.position_mode == target {
            return CommandResultCode::Success;
        }
        if !up.positions.is_empty() {
            return CommandResultCode::RiskMarginPositionExists;
        }
        up.position_mode = target;
        CommandResultCode::Success
    }

    fn reset_fee(engine: &mut RiskEngine, cmd: &mut OrderCommand, ssp: &SymbolSpecificationProvider) -> CommandResultCode {
        let mut harvested: BTreeMap<i32, i64> = BTreeMap::new();
        RiskEngine::harvest_into(&mut engine.fees, &mut engine.adjustments, &mut harvested);
        RiskEngine::harvest_into(&mut engine.loan_service.interest_revenue, &mut engine.adjustments, &mut harvested);
        for (c, amount) in harvested {
            let cur_scale = ssp.get_currency(c).map(|s| s.currency_scale_k).unwrap_or(0);
            let mut ev = FundEvent::spot(FundEventType::ResetFee, SYSTEM_TRIGGERED_ORDER_ID, 0, c, amount, 0);
            ev.currency_scale_k = cur_scale;
            cmd.fund_events.push(ev);
        }
        CommandResultCode::Success
    }

    pub fn margin_adjustment(
        engine: &mut RiskEngine,
        cmd: &OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        if !engine.cfg_margin_trading_enabled {
            return CommandResultCode::RiskMarginTradingDisabled;
        }
        if cmd.price <= 0 {
            return CommandResultCode::RiskInvalidAmount;
        }

        if cmd.margin_mode == MarginMode::Cross {
            return Self::balance_adjustment(engine, cmd, ups, ssp);
        }

        let user_profile = match ups.get_mut(cmd.uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };
        let action = cmd.action.expect("MARGIN_ADJUSTMENT (ISOLATED) requires action");
        let position_key = user_profile.create_positions_key(cmd.symbol, action, cmd.command);
        let (currency, pos_margin_mode, symbol) = match user_profile.positions.get(&position_key) {
            Some(p) => (p.currency, p.margin_mode, p.symbol),
            None => return CommandResultCode::RiskMarginPositionNotExists,
        };
        if pos_margin_mode != cmd.margin_mode {
            return CommandResultCode::RiskMarginModeMismatch;
        }

        if engine.withdrawable_balance(user_profile, currency, ssp) - cmd.price < 0 {
            return CommandResultCode::RiskNsf;
        }

        if !user_profile.try_claim_tx(cmd.order_id, cmd.timestamp) {
            return CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame;
        }

        user_profile.add_to_account(currency, -cmd.price);
        let spec = ssp
            .get_symbol(symbol)
            .unwrap_or_else(|| panic!("symbol spec missing for symbol {symbol}"));
        let currency_spec = ssp
            .get_currency(currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {currency}"));
        let extra_margin_delta = arithmetic::currency_to_size_price_scale(
            cmd.price,
            spec.base_scale_k,
            spec.quote_scale_k,
            currency_spec.currency_scale_k,
        );
        user_profile.positions.get_mut(&position_key).unwrap().extra_margin += extra_margin_delta;

        CommandResultCode::Success
    }

    pub fn leverage_adjustment(
        engine: &mut RiskEngine,
        cmd: &OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        if !engine.cfg_margin_trading_enabled {
            return CommandResultCode::RiskMarginTradingDisabled;
        }
        let user_profile = match ups.get_mut(cmd.uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };
        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s) => s,
            None => return CommandResultCode::InvalidSymbol,
        };

        if user_profile.count_position_record(cmd.symbol, |_| true) == 0 {
            return CommandResultCode::Success;
        }

        let mark_price = engine
            .mark_price(cmd.symbol)
            .unwrap_or_else(|| panic!("mark price missing for symbol {} with existing position", cmd.symbol));
        let effective_leverage = if cmd.leverage == 0 { 1 } else { cmd.leverage };

        let mut invalid_leverage = false;
        let mut old_required: i64 = 0;
        let mut new_required: i64 = 0;
        user_profile.process_position_record(cmd.symbol, |position| {
            if invalid_leverage {
                return;
            }
            let notional = position.estimate_notional_for_order(OrderAction::Bid, 0, mark_price);
            if !spec.is_valid_leverage(notional, effective_leverage) {
                invalid_leverage = true;
                return;
            }
            old_required += position.calculate_required_margin_for_futures(spec);
            new_required += position.calculate_required_margin_for_futures_with_leverage(spec, effective_leverage);
        });
        if invalid_leverage {
            return CommandResultCode::RiskInvalidLeverage;
        }

        if new_required > old_required {
            let currency_spec = ssp
                .get_currency(spec.quote_currency)
                .unwrap_or_else(|| panic!("currency spec missing for currency {}", spec.quote_currency));
            let diff = arithmetic::size_price_to_currency_scale(
                new_required - old_required,
                spec.base_scale_k,
                spec.quote_scale_k,
                currency_spec.currency_scale_k,
            );
            let balance = user_profile.account(spec.quote_currency);
            let locked = RiskEngine::calculate_locked(user_profile, spec.quote_currency, ssp, currency_spec);
            if diff > balance - locked {
                return CommandResultCode::RiskNsf;
            }
        }

        user_profile.process_position_record(cmd.symbol, |position| {
            position.update_leverage(effective_leverage);
        });

        CommandResultCode::Success
    }

    pub fn markprice_adjustment(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        if ssp.get_symbol(cmd.symbol).is_none() {
            return CommandResultCode::InvalidSymbol;
        }
        if cmd.price <= 0 {
            return CommandResultCode::RiskInvalidAmount;
        }
        engine.set_mark_price(cmd.symbol, cmd.price);
        engine.last_price_cache.entry(cmd.symbol).or_default().mark_price_ts = cmd.timestamp;
        let mut alerts = Vec::new();
        engine.liquidation_engine.check_positions(cmd, ups, ssp, &engine.last_price_cache, &engine.loan_service, &mut alerts);
        cmd.fund_events.append(&mut alerts);
        CommandResultCode::Success
    }

    fn settle_pnl(engine: &mut RiskEngine, cmd: &mut OrderCommand, ups: &mut UserProfileService, ssp: &SymbolSpecificationProvider) -> CommandResultCode {
        let symbol = cmd.symbol;
        let order_id = SYSTEM_TRIGGERED_ORDER_ID;
        let spec = match ssp.get_symbol(symbol) {
            Some(s) if s.symbol_type == SymbolType::FuturesContractDelivery => s.clone(),
            _ => return CommandResultCode::InvalidSymbol,
        };
        let currency_spec = match ssp.get_currency(spec.quote_currency) {
            Some(c) => c.clone(),
            None => return CommandResultCode::InvalidSymbol,
        };
        let price = cmd.price;
        for up in ups.users.values_mut() {
            let mut keys: Vec<i32> = up
                .positions
                .iter()
                .filter(|(_, p)| p.symbol == symbol && p.open_volume != 0)
                .map(|(&k, _)| k)
                .collect();
            keys.sort_unstable_by(|a, b| b.cmp(a));
            for key in keys {
                let (close_action, size) = {
                    let pos = &up.positions[&key];
                    let action = if pos.direction == PositionDirection::Long { OrderAction::Ask } else { OrderAction::Bid };
                    (action, pos.open_volume)
                };
                up.positions.get_mut(&key).unwrap().close_current_position_futures(close_action, size, price);
                let currency = up.positions.get(&key).unwrap().currency;

                let extra_margin = up.positions.get(&key).unwrap().extra_margin;
                if extra_margin > 0 {
                    let refund = arithmetic::size_price_to_currency_scale(
                        extra_margin,
                        spec.base_scale_k,
                        spec.quote_scale_k,
                        currency_spec.currency_scale_k,
                    );
                    up.add_to_account(currency, refund);
                    up.positions.get_mut(&key).unwrap().extra_margin = 0;
                    RiskEngine::push_futures_event(&mut cmd.fund_events, &engine.last_price_cache, FundEventType::MarginRefund, order_id, up.positions.get(&key).unwrap(), &spec, up, ssp);
                }

                let profit = up.positions.get(&key).unwrap().profit;
                if profit != 0 {
                    let profit_scaled = arithmetic::size_price_to_currency_scale(
                        profit,
                        spec.base_scale_k,
                        spec.quote_scale_k,
                        currency_spec.currency_scale_k,
                    );
                    up.add_to_account(currency, profit_scaled);
                }
                RiskEngine::push_futures_event(&mut cmd.fund_events, &engine.last_price_cache, FundEventType::PnlSettlement, order_id, up.positions.get(&key).unwrap(), &spec, up, ssp);
                up.positions.remove(&key);
            }
        }
        CommandResultCode::Success
    }

    fn if_deposit(engine: &mut RiskEngine, cmd: &OrderCommand, ssp: &SymbolSpecificationProvider) -> CommandResultCode {
        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s) => s,
            None => return CommandResultCode::InvalidSymbol,
        };
        let currency_amount = cmd.price;
        if currency_amount <= 0 {
            return CommandResultCode::RiskInvalidAmount;
        }
        let currency_spec = match ssp.get_currency(spec.quote_currency) {
            Some(c) => c,
            None => return CommandResultCode::InvalidSymbol,
        };
        let notional = arithmetic::currency_to_size_price_scale(
            currency_amount,
            spec.base_scale_k,
            spec.quote_scale_k,
            currency_spec.currency_scale_k,
        );
        let round_tripped = arithmetic::size_price_to_currency_scale(
            notional,
            spec.base_scale_k,
            spec.quote_scale_k,
            currency_spec.currency_scale_k,
        );
        if round_tripped != currency_amount {
            return CommandResultCode::RiskInvalidAmount;
        }
        let quote_currency = spec.quote_currency;
        engine.liquidation_service.deposit_to_insurance_fund(cmd.symbol, notional);
        *engine.adjustments.entry(quote_currency).or_insert(0) -= currency_amount;
        CommandResultCode::Success
    }

    fn if_withdraw(engine: &mut RiskEngine, cmd: &OrderCommand, ssp: &SymbolSpecificationProvider) -> CommandResultCode {
        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s) => s,
            None => return CommandResultCode::InvalidSymbol,
        };
        let currency_amount = cmd.price;
        if currency_amount <= 0 {
            return CommandResultCode::RiskInvalidAmount;
        }
        let currency_spec = match ssp.get_currency(spec.quote_currency) {
            Some(c) => c,
            None => return CommandResultCode::InvalidSymbol,
        };
        let notional = arithmetic::currency_to_size_price_scale(
            currency_amount,
            spec.base_scale_k,
            spec.quote_scale_k,
            currency_spec.currency_scale_k,
        );
        let round_tripped = arithmetic::size_price_to_currency_scale(
            notional,
            spec.base_scale_k,
            spec.quote_scale_k,
            currency_spec.currency_scale_k,
        );
        if round_tripped != currency_amount {
            return CommandResultCode::RiskInvalidAmount;
        }
        if !engine.liquidation_service.withdraw_from_insurance_fund(cmd.symbol, notional) {
            return CommandResultCode::RiskIfInsufficient;
        }
        let quote_currency = spec.quote_currency;
        *engine.adjustments.entry(quote_currency).or_insert(0) += currency_amount;
        CommandResultCode::Success
    }

    fn emit_balance_adjustment_event(_engine: &mut RiskEngine, cmd: &mut OrderCommand, ups: &UserProfileService, ssp: &SymbolSpecificationProvider) {
        let ev_type = if cmd.price > 0 { FundEventType::Deposit } else { FundEventType::Withdraw };
        RiskEngine::push_spot_balance_event(cmd, ups, ssp, ev_type, cmd.order_id, cmd.uid, cmd.symbol, 0);
    }

    fn emit_margin_adjust_events(engine: &mut RiskEngine, cmd: &mut OrderCommand, ups: &UserProfileService, ssp: &SymbolSpecificationProvider) {
        let Some(up) = ups.get(cmd.uid) else { return };
        let order_id = cmd.order_id;
        let lpc = &engine.last_price_cache;
        if cmd.margin_mode == MarginMode::Cross {
            let currency = cmd.symbol;
            for pos in up.positions.values() {
                if pos.margin_mode == MarginMode::Cross && pos.currency == currency {
                    if let Some(spec) = ssp.get_symbol(pos.symbol) {
                        RiskEngine::push_futures_event(&mut cmd.fund_events, lpc, FundEventType::MarginAdjust, order_id, pos, spec, up, ssp);
                    }
                }
            }
        } else if let Some(action) = cmd.action {
            let key = up.create_positions_key(cmd.symbol, action, cmd.command);
            if let Some(pos) = up.positions.get(&key) {
                if let Some(spec) = ssp.get_symbol(pos.symbol) {
                    RiskEngine::push_futures_event(&mut cmd.fund_events, lpc, FundEventType::MarginAdjust, order_id, pos, spec, up, ssp);
                }
            }
        }
    }
}
