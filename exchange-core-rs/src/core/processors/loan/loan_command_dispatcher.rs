#[cfg(test)]
use crate::core::common::last_price_cache_record::LastPriceCacheRecord;
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::fund_event::{FundEvent, FundEventType};
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::cross_loan_record::CrossLoanRecord;
use crate::core::common::isolated_loan_record::{IsolatedLoanRecord, LoanRateMode};
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;
use crate::core::common::symbol_type::SymbolType;
use crate::core::common::user_profile::UserProfile;
use crate::core::common::user_status::UserStatus;
use crate::core::processors::loan::loan_service::{LoanService, BPS_SCALE};
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::utils::core_arithmetic_utils as arithmetic;
use crate::core::utils::core_arithmetic_utils::{add_exact, mul_exact};

pub struct LoanCommandDispatcher;

impl LoanCommandDispatcher {

    pub fn dispatch(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let rc = match cmd.command {
            OrderCommandType::LoanCreate => Self::handle_loan_create(engine, cmd, ups, ssp),
            OrderCommandType::LoanRepay => Self::handle_loan_repay(engine, cmd, ups, ssp),
            OrderCommandType::LoanAddCollateral => Self::handle_loan_add_collateral(engine, cmd, ups, ssp),
            OrderCommandType::LoanReleaseCollateral => {
                Self::handle_loan_release_collateral(engine, cmd, ups, ssp)
            }
            OrderCommandType::LoanCrossAddCollateral => {
                Self::handle_loan_cross_add_collateral(engine, cmd, ups, ssp)
            }
            OrderCommandType::LoanCrossWithdrawCollateral => {
                Self::handle_loan_cross_withdraw_collateral(engine, cmd, ups, ssp)
            }
            OrderCommandType::LoanCrossBorrow => Self::handle_loan_cross_borrow(engine, cmd, ups, ssp),
            OrderCommandType::LoanCrossRepay => Self::handle_loan_cross_repay(engine, cmd, ups, ssp),
            OrderCommandType::LoanForceLiquidate => Self::handle_loan_force_liquidate(engine, cmd, ups, ssp),
            OrderCommandType::LoanCrossForceLiquidate => {
                Self::handle_loan_cross_force_liquidate(engine, cmd, ups, ssp)
            }
            OrderCommandType::PoolDeposit => Self::handle_pool_deposit(engine, cmd),
            OrderCommandType::PoolWithdraw => Self::handle_pool_withdraw(engine, cmd),
            OrderCommandType::LoanIfDeposit => Self::handle_loan_if_deposit(engine, cmd),
            OrderCommandType::LoanIfWithdraw => Self::handle_loan_if_withdraw(engine, cmd),
            _ => unreachable!("non-loan command dispatched to LoanCommandDispatcher: {:?}", cmd.command),
        };
        if matches!(
            cmd.command,
            OrderCommandType::LoanCreate
                | OrderCommandType::LoanRepay
                | OrderCommandType::LoanAddCollateral
                | OrderCommandType::LoanReleaseCollateral
                | OrderCommandType::LoanCrossAddCollateral
                | OrderCommandType::LoanCrossWithdrawCollateral
                | OrderCommandType::LoanCrossBorrow
                | OrderCommandType::LoanCrossRepay
                | OrderCommandType::LoanForceLiquidate
                | OrderCommandType::LoanCrossForceLiquidate
        ) {
            Self::reconcile_loan_indices(engine, ups, cmd.uid);
        }
        rc
    }

    fn handle_loan_create(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match Self::preamble(cmd, ups) {
            Ok(u) => u,
            Err(rc) => return rc,
        };

        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s) if s.symbol_type == SymbolType::CurrencyExchangePair => s,
            _ => return CommandResultCode::LoanNotEnabled,
        };
        if !spec.loan_config.is_enabled() {
            return CommandResultCode::LoanNotEnabled;
        }

        let loan_id = cmd.reserve_bid_price;
        if up.isolated_loans.contains_key(&loan_id) {
            return CommandResultCode::LoanAlreadyExists;
        }

        let collateral_amount = cmd.size;
        let principal = cmd.price;
        if principal <= 0 || collateral_amount <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }
        if spec.loan_config.max_amount != 0 && principal > spec.loan_config.max_amount {
            return CommandResultCode::LoanPrincipalExceedsLimit;
        }

        let mark_price = engine.mark_price(spec.symbol_id).unwrap_or(0);
        if mark_price <= 0 {
            return CommandResultCode::LoanMarkpriceNotReady;
        }

        let collateral_value_in_loan_currency =
            Self::eval_collateral_in_loan_currency(ssp, collateral_amount, spec, mark_price);
        if collateral_value_in_loan_currency < 0 {
            return CommandResultCode::LoanMarkpriceNotReady;
        }

        let lhs = mul_exact(principal, BPS_SCALE);
        let rhs = mul_exact(collateral_value_in_loan_currency, spec.loan_config.initial_ltv_bps as i64);
        if lhs > rhs {
            return CommandResultCode::LoanLtvTooHigh;
        }

        let collateral_currency = spec.base_currency;
        let loan_currency = spec.quote_currency;

        let collateral_currency_spec = ssp
            .get_currency(collateral_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {collateral_currency}"));
        let free_collateral_currency = up.account(collateral_currency)
            - RiskEngine::calculate_locked(up, collateral_currency, ssp, collateral_currency_spec);
        if free_collateral_currency < collateral_amount {
            return CommandResultCode::LoanCollateralInsufficient;
        }

        let pool_check = engine.loan_service.verify_pool_capacity(loan_currency, principal);
        if pool_check != CommandResultCode::Success {
            return pool_check;
        }

        let rate_mode = if (cmd.user_cookie as i8) == LoanRateMode::Floating.code() {
            LoanRateMode::Floating
        } else {
            LoanRateMode::Locked
        };
        let open_rate_bps = engine.loan_service.open_rate_bps(rate_mode, loan_currency);

        let mut loan = IsolatedLoanRecord::new(
            cmd.uid,
            loan_id,
            spec.symbol_id,
            collateral_currency,
            loan_currency,
            open_rate_bps,
            cmd.timestamp,
        );
        loan.rate_mode = rate_mode;
        if rate_mode == LoanRateMode::Floating {
            engine.loan_service.floating_rate.init_open_snapshot(&mut loan, cmd.timestamp);
        }
        loan.collateral_amount = collateral_amount;
        loan.outstanding_principal = principal;
        up.isolated_loans.insert(loan_id, loan);

        engine.loan_service.disburse_loan(up, loan_currency, principal);

        let loan_ref = up.isolated_loans.get(&loan_id).expect("just inserted");
        Self::push_isolated_loan_event(cmd, engine, ssp, up, loan_ref, FundEventType::LoanBorrow);

        CommandResultCode::Success
    }

    fn handle_loan_repay(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match Self::preamble(cmd, ups) {
            Ok(u) => u,
            Err(rc) => return rc,
        };

        let loan_id = cmd.reserve_bid_price;
        let loan_uid = match up.isolated_loans.get(&loan_id) {
            Some(l) => l.uid,
            None => return CommandResultCode::LoanNotFound,
        };
        if loan_uid != cmd.uid {
            return CommandResultCode::LoanUidMismatch;
        }

        let rc = Self::settle_repay_isolated(engine, up, loan_id, cmd, ssp);
        if rc != CommandResultCode::Success {
            return rc;
        }

        if let Some(loan_ref) = up.isolated_loans.get(&loan_id) {
            Self::push_isolated_loan_event(cmd, engine, ssp, up, loan_ref, FundEventType::LoanRepay);
        }

        let is_empty = up.isolated_loans.get(&loan_id).map(|l| l.is_empty()).unwrap_or(true);
        if is_empty {
            up.isolated_loans.remove(&loan_id);
        }
        CommandResultCode::Success
    }

    fn handle_loan_add_collateral(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match Self::preamble(cmd, ups) {
            Ok(u) => u,
            Err(rc) => return rc,
        };

        let loan_id = cmd.reserve_bid_price;
        let (loan_uid, collateral_currency) = match up.isolated_loans.get(&loan_id) {
            Some(l) => (l.uid, l.collateral_currency),
            None => return CommandResultCode::LoanNotFound,
        };
        if loan_uid != cmd.uid {
            return CommandResultCode::LoanUidMismatch;
        }

        let amount = cmd.size;
        if amount <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }

        let collateral_currency_spec = ssp
            .get_currency(collateral_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {collateral_currency}"));
        let free = up.account(collateral_currency)
            - RiskEngine::calculate_locked(up, collateral_currency, ssp, collateral_currency_spec);
        if free < amount {
            return CommandResultCode::LoanCollateralInsufficient;
        }

        let loan = up.isolated_loans.get_mut(&loan_id).expect("loan existence checked above");
        engine.loan_service.accrue_to(loan, cmd.timestamp);
        loan.collateral_amount = add_exact(loan.collateral_amount, amount);

        let loan_ref = up.isolated_loans.get(&loan_id).expect("just updated");
        Self::push_isolated_loan_event(cmd, engine, ssp, up, loan_ref, FundEventType::LoanCollateralChange);
        CommandResultCode::Success
    }

    fn handle_loan_release_collateral(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match Self::preamble(cmd, ups) {
            Ok(u) => u,
            Err(rc) => return rc,
        };

        let loan_id = cmd.reserve_bid_price;
        let (loan_uid, symbol_id, collateral_amount) = match up.isolated_loans.get(&loan_id) {
            Some(l) => (l.uid, l.symbol_id, l.collateral_amount),
            None => return CommandResultCode::LoanNotFound,
        };
        if loan_uid != cmd.uid {
            return CommandResultCode::LoanUidMismatch;
        }

        let amount = cmd.size;
        if amount <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }
        if amount > collateral_amount {
            return CommandResultCode::LoanCollateralExceedsLoan;
        }

        let spec = match ssp.get_symbol(symbol_id) {
            Some(s) => s,
            None => return CommandResultCode::LoanNotEnabled,
        };
        let mark_price = engine.mark_price(spec.symbol_id).unwrap_or(0);
        if mark_price <= 0 {
            return CommandResultCode::LoanMarkpriceNotReady;
        }

        let loan = up.isolated_loans.get_mut(&loan_id).expect("loan existence checked above");
        engine.loan_service.accrue_to(loan, cmd.timestamp);

        let real_debt =
            add_exact(loan.outstanding_principal, engine.loan_service.calculate_display_interest(loan, cmd.timestamp));
        let new_collateral = loan.collateral_amount - amount;

        if new_collateral == 0 && real_debt > 0 {
            return CommandResultCode::LoanLtvTooHighAfterRelease;
        }
        if new_collateral > 0 {
            let new_collateral_value_in_loan_currency =
                Self::eval_collateral_in_loan_currency(ssp, new_collateral, spec, mark_price);
            if new_collateral_value_in_loan_currency < 0 {
                return CommandResultCode::LoanMarkpriceNotReady;
            }
            let lhs = mul_exact(real_debt, BPS_SCALE);
            let rhs = mul_exact(new_collateral_value_in_loan_currency, spec.loan_config.liquidation_ltv_bps as i64);
            if lhs >= rhs {
                return CommandResultCode::LoanLtvTooHighAfterRelease;
            }
        }

        let loan = up.isolated_loans.get_mut(&loan_id).expect("loan existence checked above");
        loan.collateral_amount = new_collateral;
        let is_empty = loan.is_empty();

        let loan_ref = up.isolated_loans.get(&loan_id).expect("just updated");
        Self::push_isolated_loan_event(cmd, engine, ssp, up, loan_ref, FundEventType::LoanCollateralChange);

        if is_empty {
            up.isolated_loans.remove(&loan_id);
        }
        CommandResultCode::Success
    }

    fn handle_loan_force_liquidate(
        _engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match ups.get_mut(cmd.uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };

        let loan_id = cmd.reserve_bid_price;
        let (loan_uid, collateral_currency, loan_currency, collateral_amount) = match up.isolated_loans.get(&loan_id) {
            Some(l) => (l.uid, l.collateral_currency, l.loan_currency, l.collateral_amount),
            None => return CommandResultCode::LoanNotFound,
        };
        if loan_uid != cmd.uid {
            return CommandResultCode::LoanUidMismatch;
        }

        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s)
                if s.symbol_type == SymbolType::CurrencyExchangePair
                    && s.base_currency == collateral_currency
                    && s.quote_currency == loan_currency =>
            {
                s
            }
            _ => return CommandResultCode::LoanNotEnabled,
        };

        let collateral_spec = ssp
            .get_currency(collateral_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {collateral_currency}"));
        let sell_amount = LoanService::lots_to_collateral_amount(cmd.size, spec, collateral_spec);
        if sell_amount <= 0 || sell_amount > collateral_amount {
            return CommandResultCode::LoanInvalidAmount;
        }

        up.isolated_loans.get_mut(&loan_id).expect("existence checked above").collateral_amount -= sell_amount;
        up.add_to_locked(collateral_currency, sell_amount);

        cmd.action = Some(OrderAction::Ask);
        cmd.order_type = Some(OrderType::Ioc);
        CommandResultCode::ValidForMatchingEngine
    }

    #[allow(clippy::too_many_arguments)]
    pub fn post_process_loan_force_liquidate(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        spec: &CoreSymbolSpecification,
        taker_up: &mut UserProfile,
        ssp: &SymbolSpecificationProvider,
        traded_size: i64,
        traded_notional: i128,
        rejected_size: i64,
    ) {
        let loan_id = cmd.reserve_bid_price;
        let (loan_currency, collateral_currency) = match taker_up.isolated_loans.get(&loan_id) {
            Some(l) => (l.loan_currency, l.collateral_currency),
            None => return,
        };
        let loan_currency_spec = ssp
            .get_currency(loan_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {loan_currency}"));
        let base_spec = ssp
            .get_currency(collateral_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {collateral_currency}"));

        if rejected_size > 0 {
            let rejected_in_currency_scale =
                arithmetic::symbol_to_currency_scale(rejected_size, spec.base_scale_k, base_spec.currency_scale_k);
            let loan = taker_up.isolated_loans.get_mut(&loan_id).expect("checked above");
            loan.collateral_amount = add_exact(loan.collateral_amount, rejected_in_currency_scale);
        }

        if traded_size > 0 {
            let avg_taker_price = i64::try_from(traded_notional / traded_size as i128)
                .unwrap_or_else(|_| panic!("overflow narrowing avg_taker_price"));
            let taker_fee = arithmetic::calculate_taker_fee(traded_size, avg_taker_price, spec.taker_fee, spec.fee_scale_k);
            let traded_notional_i64 = i64::try_from(traded_notional)
                .unwrap_or_else(|_| panic!("overflow narrowing traded_notional"));
            let received_quote = arithmetic::size_price_to_currency_scale(
                traded_notional_i64 - taker_fee,
                spec.base_scale_k,
                spec.quote_scale_k,
                loan_currency_spec.currency_scale_k,
            );
            let loan = taker_up.isolated_loans.get_mut(&loan_id).expect("checked above");
            engine.loan_service.settle_liquidation_proceeds(loan, &mut taker_up.accounts, received_quote, cmd.timestamp);
        }

        let loan = taker_up.isolated_loans.get_mut(&loan_id).expect("checked above");

        engine.loan_service.accrue_to(loan, cmd.timestamp);
        let remain_debt = add_exact(loan.outstanding_principal, loan.accumulated_interest);

        let sellable_lots = LoanService::collateral_amount_to_lots(loan.collateral_amount, spec, base_spec);
        let (principal, interest, collateral, cum_interest_paid) =
            (loan.outstanding_principal, loan.accumulated_interest, loan.collateral_amount, loan.cum_interest_paid);

        let lif_takeover = remain_debt > 0 && (traded_size == 0 || sellable_lots == 0);
        if lif_takeover {
            Self::take_over_by_insurance_fund(
                engine,
                taker_up,
                principal,
                interest,
                loan_currency,
                collateral_currency,
                collateral,
            );
            let (free, locked, cur_scale) = Self::currency_free_locked(ssp, taker_up, loan_currency);
            let (coll_free, coll_locked, coll_scale) = Self::currency_free_locked(ssp, taker_up, collateral_currency);
            cmd.fund_events.push(FundEvent {
                event_type: FundEventType::LoanLiquidated,
                order_id: loan_id,
                uid: taker_up.uid,
                currency: loan_currency,
                currency_scale_k: cur_scale,
                free,
                locked,
                loan_mode: 0,
                loan_collateral_currency: collateral_currency,
                loan_collateral_currency_scale_k: coll_scale,
                loan_collateral_free: coll_free,
                loan_collateral_locked: coll_locked,
                loan_interest_paid_total: cum_interest_paid,
                ..Default::default()
            });
        } else if traded_size > 0 {
            if let Some(loan_ref) = taker_up.isolated_loans.get(&loan_id) {
                Self::push_isolated_loan_event(cmd, engine, ssp, taker_up, loan_ref, FundEventType::LoanLiquidated);
            }
        }
        if lif_takeover || (principal == 0 && interest == 0 && collateral == 0) {
            taker_up.isolated_loans.remove(&loan_id);
        }
        engine.liquidation_engine.loan_liquidation_engine.on_isolated_loan_closed(taker_up, spec.symbol_id);
    }

    fn handle_loan_cross_add_collateral(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match Self::preamble(cmd, ups) {
            Ok(u) => u,
            Err(rc) => return rc,
        };

        let currency = cmd.symbol;
        let amount = cmd.size;
        if amount <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }
        if LoanService::collateral_weight_for_base(currency, ssp) <= 0 {
            return CommandResultCode::LoanCollateralNotAllowed;
        }

        let currency_spec = ssp
            .get_currency(currency)
            .expect("collateral_weight_for_base>0 implies the currency spec exists");
        let free = up.account(currency) - RiskEngine::calculate_locked(up, currency, ssp, currency_spec);
        if free < amount {
            return CommandResultCode::LoanCollateralInsufficient;
        }

        up.add_to_cross_loan_collateral(currency, amount);

        let uid = cmd.uid;
        let ltv = engine.loan_service.calculate_cross_account_ltv_bps(up, cmd.timestamp, ssp, &engine.last_price_cache, false);
        let pledged = up.cross_loan_collateral.get(&currency).copied().unwrap_or(0);
        Self::push_cross_collateral_change_event(cmd, ssp, up, uid, currency, pledged, ltv);
        CommandResultCode::Success
    }

    fn handle_loan_cross_withdraw_collateral(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match Self::preamble(cmd, ups) {
            Ok(u) => u,
            Err(rc) => return rc,
        };

        let currency = cmd.symbol;
        let amount = cmd.size;
        if amount <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }
        if up.cross_loan_collateral(currency) < amount {
            return CommandResultCode::LoanCollateralExceedsLoan;
        }
        if !engine.loan_service.global_config.is_numeraire_configured() {
            return CommandResultCode::LoanNumeraireNotConfigured;
        }

        up.add_to_cross_loan_collateral(currency, -amount);
        let new_ltv =
            engine.loan_service.calculate_cross_account_ltv_bps(up, cmd.timestamp, ssp, &engine.last_price_cache, true);
        if new_ltv >= engine.loan_service.global_config.cross_liquidation_ltv_bps as i64 {
            up.add_to_cross_loan_collateral(currency, amount);
            return CommandResultCode::LoanCrossLtvTooHighAfterWithdraw;
        }

        let uid = cmd.uid;
        let pledged = up.cross_loan_collateral.get(&currency).copied().unwrap_or(0);
        Self::push_cross_collateral_change_event(cmd, ssp, up, uid, currency, pledged, new_ltv);
        CommandResultCode::Success
    }

    fn handle_loan_cross_borrow(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match Self::preamble(cmd, ups) {
            Ok(u) => u,
            Err(rc) => return rc,
        };

        let loan_id = cmd.reserve_bid_price;
        if up.cross_loans.contains_key(&loan_id) {
            return CommandResultCode::LoanAlreadyExists;
        }

        let symbol_id = cmd.symbol;
        let principal = cmd.price;
        if principal <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }

        let spec = match ssp.get_symbol(symbol_id) {
            Some(s) if s.symbol_type == SymbolType::CurrencyExchangePair && s.loan_config.is_enabled() => s,
            _ => return CommandResultCode::LoanNotEnabled,
        };
        let loan_currency = spec.quote_currency;
        if spec.loan_config.max_amount != 0 && principal > spec.loan_config.max_amount {
            return CommandResultCode::LoanPrincipalExceedsLimit;
        }

        if !engine.loan_service.global_config.is_numeraire_configured() {
            return CommandResultCode::LoanNumeraireNotConfigured;
        }
        let pool_check = engine.loan_service.verify_pool_capacity(loan_currency, principal);
        if pool_check != CommandResultCode::Success {
            return pool_check;
        }

        let open_rate_bps = engine.loan_service.floating_rate.open_rate_bps(loan_currency);
        let mut loan =
            CrossLoanRecord::new(cmd.uid, loan_id, spec.symbol_id, loan_currency, open_rate_bps, cmd.timestamp);
        engine.loan_service.floating_rate.init_open_snapshot(&mut loan, cmd.timestamp);
        loan.outstanding_principal = principal;
        up.cross_loans.insert(loan_id, loan);

        let new_ltv =
            engine.loan_service.calculate_cross_account_ltv_bps(up, cmd.timestamp, ssp, &engine.last_price_cache, true);
        if new_ltv > spec.loan_config.initial_ltv_bps as i64 {
            up.cross_loans.remove(&loan_id);
            return CommandResultCode::LoanLtvTooHighAfterBorrow;
        }

        engine.loan_service.disburse_loan(up, loan_currency, principal);

        let ts = cmd.timestamp;
        let loan_ref = up.cross_loans.get(&loan_id).expect("just inserted");
        Self::push_cross_loan_event(cmd, engine, ssp, up, loan_ref, FundEventType::LoanBorrow, ts, true);
        CommandResultCode::Success
    }

    fn handle_loan_cross_repay(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match Self::preamble(cmd, ups) {
            Ok(u) => u,
            Err(rc) => return rc,
        };

        let loan_id = cmd.reserve_bid_price;
        let loan_uid = match up.cross_loans.get(&loan_id) {
            Some(l) => l.uid,
            None => return CommandResultCode::LoanNotFound,
        };
        if loan_uid != cmd.uid {
            return CommandResultCode::LoanUidMismatch;
        }

        let rc = Self::settle_repay_cross(engine, up, loan_id, cmd, ssp);
        if rc != CommandResultCode::Success {
            return rc;
        }

        let ts = cmd.timestamp;
        if let Some(loan_ref) = up.cross_loans.get(&loan_id) {
            Self::push_cross_loan_event(cmd, engine, ssp, up, loan_ref, FundEventType::LoanRepay, ts, false);
        }

        let is_empty = up.cross_loans.get(&loan_id).map(|l| l.is_empty()).unwrap_or(true);
        if is_empty {
            up.cross_loans.remove(&loan_id);
        }
        CommandResultCode::Success
    }

    fn handle_loan_cross_force_liquidate(
        _engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let up = match ups.get_mut(cmd.uid) {
            Some(u) => u,
            None => return CommandResultCode::AuthInvalidUser,
        };

        let target_loan_id = cmd.reserve_bid_price;
        let (target_loan_uid, target_loan_currency) = match up.cross_loans.get(&target_loan_id) {
            Some(l) => (l.uid, l.loan_currency),
            None => return CommandResultCode::LoanNotFound,
        };
        if target_loan_uid != cmd.uid {
            return CommandResultCode::LoanUidMismatch;
        }

        let spec = match ssp.get_symbol(cmd.symbol) {
            Some(s) if s.symbol_type == SymbolType::CurrencyExchangePair && s.quote_currency == target_loan_currency => s,
            _ => return CommandResultCode::LoanNotEnabled,
        };

        let selling_currency = spec.base_currency;
        let available_collateral = up.cross_loan_collateral(selling_currency);

        let selling_currency_spec = ssp
            .get_currency(selling_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {selling_currency}"));
        let sell_amount = LoanService::lots_to_collateral_amount(cmd.size, spec, selling_currency_spec);
        if sell_amount <= 0 || sell_amount > available_collateral {
            return CommandResultCode::LoanInvalidAmount;
        }

        up.add_to_cross_loan_collateral(selling_currency, -sell_amount);
        up.add_to_locked(selling_currency, sell_amount);

        cmd.action = Some(OrderAction::Ask);
        cmd.order_type = Some(OrderType::Ioc);
        CommandResultCode::ValidForMatchingEngine
    }

    #[allow(clippy::too_many_arguments)]
    pub fn post_process_loan_cross_force_liquidate(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        spec: &CoreSymbolSpecification,
        taker_up: &mut UserProfile,
        ssp: &SymbolSpecificationProvider,
        traded_size: i64,
        traded_notional: i128,
        rejected_size: i64,
    ) {
        let target_loan_id = cmd.reserve_bid_price;
        let selling_currency = spec.base_currency;
        let loan_currency = match taker_up.cross_loans.get(&target_loan_id) {
            Some(l) => l.loan_currency,
            None => return,
        };
        let loan_currency_spec = ssp
            .get_currency(loan_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {loan_currency}"));
        let selling_currency_spec = ssp
            .get_currency(selling_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {selling_currency}"));

        if rejected_size > 0 {
            let rejected_in_currency_scale =
                arithmetic::symbol_to_currency_scale(rejected_size, spec.base_scale_k, selling_currency_spec.currency_scale_k);
            taker_up.add_to_cross_loan_collateral(selling_currency, rejected_in_currency_scale);
        }

        if traded_size > 0 {
            let avg_taker_price = i64::try_from(traded_notional / traded_size as i128)
                .unwrap_or_else(|_| panic!("overflow narrowing avg_taker_price"));
            let taker_fee = arithmetic::calculate_taker_fee(traded_size, avg_taker_price, spec.taker_fee, spec.fee_scale_k);
            let traded_notional_i64 = i64::try_from(traded_notional)
                .unwrap_or_else(|_| panic!("overflow narrowing traded_notional"));
            let received_quote = arithmetic::size_price_to_currency_scale(
                traded_notional_i64 - taker_fee,
                spec.base_scale_k,
                spec.quote_scale_k,
                loan_currency_spec.currency_scale_k,
            );
            let loan = taker_up.cross_loans.get_mut(&target_loan_id).expect("checked above");
            engine.loan_service.settle_liquidation_proceeds(loan, &mut taker_up.accounts, received_quote, cmd.timestamp);
        }

        let loan = taker_up.cross_loans.get_mut(&target_loan_id).expect("checked above");

        engine.loan_service.accrue_to(loan, cmd.timestamp);
        let remain_target_debt = add_exact(loan.outstanding_principal, loan.accumulated_interest);

        let currencies: Vec<i32> = taker_up.cross_loan_collateral.keys().copied().collect();
        let mut all_collateral_exhausted = true;
        for currency in currencies {
            let amount = taker_up.cross_loan_collateral(currency);
            if LoanService::is_structurally_sellable(currency, amount, taker_up, ssp) {
                all_collateral_exhausted = false;
                break;
            }
        }

        let ts = cmd.timestamp;
        if remain_target_debt > 0 && (traded_size == 0 || all_collateral_exhausted) {
            let taken_over =
                engine.loan_service.take_over_cross_loan(taker_up, target_loan_id, cmd.timestamp, ssp, &engine.last_price_cache);
            if taken_over {
                let liq = taker_up.cross_loans.get(&target_loan_id).map(|l| (l.loan_id, l.loan_currency, l.uid, l.cum_interest_paid));
                Self::close_and_recycle_cross_loan(taker_up, target_loan_id);
                if let Some((lid, lcur, luid, cip)) = liq {
                    Self::push_cross_loan_liquidated(cmd, engine, ssp, taker_up, lid, lcur, luid, 0, 0, cip, selling_currency, ts);
                }
            } else if traded_size > 0 {
                let liq = taker_up.cross_loans.get(&target_loan_id).map(|l| (l.loan_id, l.loan_currency, l.uid, l.outstanding_principal, l.accumulated_interest, l.cum_interest_paid));
                if let Some((lid, lcur, luid, prin, intr, cip)) = liq {
                    Self::push_cross_loan_liquidated(cmd, engine, ssp, taker_up, lid, lcur, luid, prin, intr, cip, selling_currency, ts);
                }
            }
        } else {
            if traded_size > 0 {
                let liq = taker_up.cross_loans.get(&target_loan_id).map(|l| (l.loan_id, l.loan_currency, l.uid, l.outstanding_principal, l.accumulated_interest, l.cum_interest_paid));
                if let Some((lid, lcur, luid, prin, intr, cip)) = liq {
                    Self::push_cross_loan_liquidated(cmd, engine, ssp, taker_up, lid, lcur, luid, prin, intr, cip, selling_currency, ts);
                }
            }
            let is_empty = {
                let l = taker_up.cross_loans.get(&target_loan_id).expect("checked above");
                l.outstanding_principal == 0 && l.accumulated_interest == 0
            };
            if is_empty {
                taker_up.cross_loans.remove(&target_loan_id);
            }
        }

        if all_collateral_exhausted {
            Self::take_over_remaining_cross_loans(engine, cmd, taker_up, cmd.timestamp, target_loan_id, selling_currency, ssp);
        }
        engine.liquidation_engine.loan_liquidation_engine.sync_cross_exposure(taker_up);
    }

    fn handle_pool_deposit(engine: &mut RiskEngine, cmd: &OrderCommand) -> CommandResultCode {
        if cmd.size <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }
        engine.loan_service.add_to_loan_pool_available(cmd.symbol, cmd.size);
        *engine.adjustments.entry(cmd.symbol).or_insert(0) -= cmd.size;
        CommandResultCode::Success
    }

    fn handle_pool_withdraw(engine: &mut RiskEngine, cmd: &OrderCommand) -> CommandResultCode {
        if cmd.size <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }
        if engine.loan_service.get_loan_pool_available(cmd.symbol) < cmd.size {
            return CommandResultCode::LoanPoolInsufficient;
        }
        engine.loan_service.add_to_loan_pool_available(cmd.symbol, -cmd.size);
        *engine.adjustments.entry(cmd.symbol).or_insert(0) += cmd.size;
        CommandResultCode::Success
    }

    fn handle_loan_if_deposit(engine: &mut RiskEngine, cmd: &OrderCommand) -> CommandResultCode {
        if cmd.size <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }
        engine.loan_service.add_to_loan_insurance_fund(cmd.symbol, cmd.size);
        *engine.adjustments.entry(cmd.symbol).or_insert(0) -= cmd.size;
        CommandResultCode::Success
    }

    fn handle_loan_if_withdraw(engine: &mut RiskEngine, cmd: &OrderCommand) -> CommandResultCode {
        if cmd.size <= 0 {
            return CommandResultCode::LoanInvalidAmount;
        }
        if engine.loan_service.get_loan_insurance_fund(cmd.symbol) < cmd.size {
            return CommandResultCode::LoanIfInsufficient;
        }
        engine.loan_service.add_to_loan_insurance_fund(cmd.symbol, -cmd.size);
        *engine.adjustments.entry(cmd.symbol).or_insert(0) += cmd.size;
        CommandResultCode::Success
    }

    fn reconcile_loan_indices(engine: &mut RiskEngine, ups: &UserProfileService, uid: i64) {
        let up = match ups.get(uid) {
            Some(u) => u,
            None => return,
        };
        let lle = &mut engine.liquidation_engine.loan_liquidation_engine;
        for loan in up.isolated_loans.values() {
            if !loan.is_empty() {
                lle.on_isolated_loan_opened(uid, loan.symbol_id);
            }
        }
        let indexed: Vec<i32> = lle
            .isolated_loan_symbol_to_users
            .iter()
            .filter(|(_, users)| users.contains(&uid))
            .map(|(&s, _)| s)
            .collect();
        for sym in indexed {
            lle.on_isolated_loan_closed(up, sym);
        }
        lle.sync_cross_exposure(up);
    }

    fn preamble<'a>(
        cmd: &OrderCommand,
        ups: &'a mut UserProfileService,
    ) -> Result<&'a mut UserProfile, CommandResultCode> {
        let up = ups.get_mut(cmd.uid).ok_or(CommandResultCode::AuthInvalidUser)?;
        if up.user_status == UserStatus::Suspended {
            return Err(CommandResultCode::LoanUserSuspended);
        }
        if !up.try_claim_tx(cmd.order_id, cmd.timestamp) {
            return Err(CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame);
        }
        Ok(up)
    }

    fn eval_collateral_in_loan_currency(
        ssp: &SymbolSpecificationProvider,
        amount: i64,
        spec: &CoreSymbolSpecification,
        mark_price: i64,
    ) -> i64 {
        let base_spec = ssp.get_currency(spec.base_currency);
        let quote_spec = ssp.get_currency(spec.quote_currency);
        LoanService::collateral_value_in_quote_currency(amount, spec, mark_price, base_spec, quote_spec)
    }

    fn currency_free_locked(ssp: &SymbolSpecificationProvider, up: &UserProfile, currency: i32) -> (i64, i64, i64) {
        match ssp.get_currency(currency) {
            Some(cspec) => {
                let locked = RiskEngine::calculate_locked(up, currency, ssp, cspec);
                (up.account(currency) - locked, locked, cspec.currency_scale_k)
            }
            None => (0, 0, 0),
        }
    }

    fn push_isolated_loan_event(
        cmd: &mut OrderCommand,
        engine: &RiskEngine,
        ssp: &SymbolSpecificationProvider,
        up: &UserProfile,
        loan: &IsolatedLoanRecord,
        event_type: FundEventType,
    ) {
        let ltv_bps = match ssp.get_symbol(loan.symbol_id) {
            Some(spec) => {
                let mark = engine.mark_price(loan.symbol_id).unwrap_or(0);
                let coll = Self::eval_collateral_in_loan_currency(ssp, loan.collateral_amount, spec, mark);
                let debt = add_exact(loan.outstanding_principal, loan.accumulated_interest);
                if coll > 0 { mul_exact(debt, BPS_SCALE) / coll } else { 0 }
            }
            None => 0,
        };
        let (free, locked, cur_scale) = Self::currency_free_locked(ssp, up, loan.loan_currency);
        let (coll_free, coll_locked, coll_scale) = Self::currency_free_locked(ssp, up, loan.collateral_currency);
        cmd.fund_events.push(FundEvent {
            event_type,
            order_id: loan.loan_id,
            uid: loan.uid,
            currency: loan.loan_currency,
            currency_scale_k: cur_scale,
            free,
            locked,
            loan_mode: 0,
            loan_debt_principal: loan.outstanding_principal,
            loan_debt_interest: loan.accumulated_interest,
            loan_interest_paid_total: if event_type == FundEventType::LoanCollateralChange { 0 } else { loan.cum_interest_paid },
            loan_ltv_bps: ltv_bps,
            loan_collateral_currency: loan.collateral_currency,
            loan_collateral_currency_scale_k: coll_scale,
            loan_collateral_pledged: loan.collateral_amount,
            loan_collateral_free: coll_free,
            loan_collateral_locked: coll_locked,
            ..Default::default()
        });
    }

    fn push_cross_loan_event(
        cmd: &mut OrderCommand,
        engine: &RiskEngine,
        ssp: &SymbolSpecificationProvider,
        up: &UserProfile,
        loan: &CrossLoanRecord,
        event_type: FundEventType,
        timestamp: i64,
        fail_closed: bool,
    ) {
        let ltv_bps = engine.loan_service.calculate_cross_account_ltv_bps(
            up,
            timestamp,
            ssp,
            &engine.last_price_cache,
            fail_closed,
        );
        let (free, locked, cur_scale) = Self::currency_free_locked(ssp, up, loan.loan_currency);
        cmd.fund_events.push(FundEvent {
            event_type,
            order_id: loan.loan_id,
            uid: loan.uid,
            currency: loan.loan_currency,
            currency_scale_k: cur_scale,
            free,
            locked,
            loan_mode: 1,
            loan_debt_principal: loan.outstanding_principal,
            loan_debt_interest: loan.accumulated_interest,
            loan_interest_paid_total: loan.cum_interest_paid,
            loan_ltv_bps: ltv_bps,
            ..Default::default()
        });
    }

    fn push_cross_collateral_change_event(
        cmd: &mut OrderCommand,
        ssp: &SymbolSpecificationProvider,
        up: &UserProfile,
        uid: i64,
        currency: i32,
        pledged: i64,
        ltv_bps: i64,
    ) {
        let (coll_free, coll_locked, coll_scale) = Self::currency_free_locked(ssp, up, currency);
        cmd.fund_events.push(FundEvent {
            event_type: FundEventType::LoanCollateralChange,
            uid,
            loan_mode: 1,
            loan_ltv_bps: ltv_bps,
            loan_collateral_currency: currency,
            loan_collateral_currency_scale_k: coll_scale,
            loan_collateral_pledged: pledged,
            loan_collateral_free: coll_free,
            loan_collateral_locked: coll_locked,
            ..Default::default()
        });
    }

    #[allow(clippy::too_many_arguments)]
    fn push_cross_loan_liquidated(
        cmd: &mut OrderCommand,
        engine: &RiskEngine,
        ssp: &SymbolSpecificationProvider,
        up: &UserProfile,
        loan_id: i64,
        loan_currency: i32,
        uid: i64,
        principal: i64,
        interest: i64,
        cum_interest_paid: i64,
        selling_currency: i32,
        timestamp: i64,
    ) {
        let (free, locked, cur_scale) = Self::currency_free_locked(ssp, up, loan_currency);
        let (coll_free, coll_locked, coll_scale) = Self::currency_free_locked(ssp, up, selling_currency);
        let ltv_bps =
            engine.loan_service.calculate_cross_account_ltv_bps(up, timestamp, ssp, &engine.last_price_cache, false);
        cmd.fund_events.push(FundEvent {
            event_type: FundEventType::LoanLiquidated,
            order_id: loan_id,
            uid,
            currency: loan_currency,
            currency_scale_k: cur_scale,
            free,
            locked,
            loan_mode: 1,
            loan_debt_principal: principal,
            loan_debt_interest: interest,
            loan_interest_paid_total: cum_interest_paid,
            loan_ltv_bps: ltv_bps,
            loan_collateral_currency: selling_currency,
            loan_collateral_currency_scale_k: coll_scale,
            loan_collateral_pledged: up.cross_loan_collateral(selling_currency),
            loan_collateral_free: coll_free,
            loan_collateral_locked: coll_locked,
            ..Default::default()
        });
    }

    fn settle_repay_isolated(
        engine: &mut RiskEngine,
        up: &mut UserProfile,
        loan_id: i64,
        cmd: &OrderCommand,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let requested_repay = cmd.price;
        if requested_repay < 0 {
            return CommandResultCode::LoanInvalidAmount;
        }

        let (loan_currency, payoff) = {
            let loan = up.isolated_loans.get_mut(&loan_id).expect("loan existence checked by caller");
            engine.loan_service.accrue_to(loan, cmd.timestamp);
            (loan.loan_currency, add_exact(loan.outstanding_principal, loan.accumulated_interest))
        };
        let actual_repay =
            if requested_repay == 0 || requested_repay >= payoff { payoff } else { requested_repay };

        let loan_currency_spec = ssp
            .get_currency(loan_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {loan_currency}"));
        let free = up.account(loan_currency) - RiskEngine::calculate_locked(up, loan_currency, ssp, loan_currency_spec);
        if free < actual_repay {
            return CommandResultCode::LoanAccountInsufficient;
        }

        let loan = up.isolated_loans.get_mut(&loan_id).expect("loan existence checked by caller");
        engine.loan_service.apply_debt_payment(loan, &mut up.accounts, actual_repay);
        CommandResultCode::Success
    }

    fn take_over_by_insurance_fund(
        engine: &mut RiskEngine,
        up: &mut UserProfile,
        principal: i64,
        interest: i64,
        loan_currency: i32,
        collateral_currency: i32,
        collateral: i64,
    ) {
        let debt = add_exact(principal, interest);
        engine.loan_service.add_to_loan_insurance_fund(loan_currency, -debt);
        engine.loan_service.add_to_loan_pool_available(loan_currency, principal);
        engine.loan_service.add_to_loan_pool_borrowed(loan_currency, -principal);
        engine.loan_service.add_to_interest_revenue(loan_currency, interest);
        if collateral > 0 {
            up.add_to_account(collateral_currency, -collateral);
            engine.loan_service.add_to_loan_insurance_fund(collateral_currency, collateral);
        }
    }

    fn settle_repay_cross(
        engine: &mut RiskEngine,
        up: &mut UserProfile,
        loan_id: i64,
        cmd: &OrderCommand,
        ssp: &SymbolSpecificationProvider,
    ) -> CommandResultCode {
        let requested_repay = cmd.price;
        if requested_repay < 0 {
            return CommandResultCode::LoanInvalidAmount;
        }

        let (loan_currency, payoff) = {
            let loan = up.cross_loans.get_mut(&loan_id).expect("loan existence checked by caller");
            engine.loan_service.accrue_to(loan, cmd.timestamp);
            (loan.loan_currency, add_exact(loan.outstanding_principal, loan.accumulated_interest))
        };
        let actual_repay =
            if requested_repay == 0 || requested_repay >= payoff { payoff } else { requested_repay };

        let loan_currency_spec = ssp
            .get_currency(loan_currency)
            .unwrap_or_else(|| panic!("currency spec missing for currency {loan_currency}"));
        let free = up.account(loan_currency) - RiskEngine::calculate_locked(up, loan_currency, ssp, loan_currency_spec);
        if free < actual_repay {
            return CommandResultCode::LoanAccountInsufficient;
        }

        let loan = up.cross_loans.get_mut(&loan_id).expect("loan existence checked by caller");
        engine.loan_service.apply_debt_payment(loan, &mut up.accounts, actual_repay);
        CommandResultCode::Success
    }

    fn close_and_recycle_cross_loan(up: &mut UserProfile, loan_id: i64) {
        up.cross_loans.remove(&loan_id);
    }

    #[allow(clippy::too_many_arguments)]
    fn take_over_remaining_cross_loans(
        engine: &mut RiskEngine,
        cmd: &mut OrderCommand,
        up: &mut UserProfile,
        now: i64,
        target_loan_id: i64,
        selling_currency: i32,
        ssp: &SymbolSpecificationProvider,
    ) {
        let loan_ids: Vec<i64> = up.cross_loans.keys().copied().collect();
        for loan_id in loan_ids {
            if loan_id == target_loan_id {
                continue;
            }
            let should_skip = match up.cross_loans.get(&loan_id) {
                Some(l) => l.outstanding_principal == 0 && l.accumulated_interest == 0,
                None => true,
            };
            if should_skip {
                continue;
            }
            let taken_over = engine.loan_service.take_over_cross_loan(up, loan_id, now, ssp, &engine.last_price_cache);
            if !taken_over {
                continue;
            }
            let liq = up.cross_loans.get(&loan_id).map(|l| (l.loan_id, l.loan_currency, l.uid, l.cum_interest_paid));
            if let Some((lid, lcur, luid, cip)) = liq {
                Self::push_cross_loan_liquidated(cmd, engine, ssp, up, lid, lcur, luid, 0, 0, cip, selling_currency, now);
            }
            Self::close_and_recycle_cross_loan(up, loan_id);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::loan_record::LoanRecord;
    use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;

    const BASE: i32 = 1;
    const QUOTE: i32 = 2;
    const SYMBOL: i32 = 100;
    const UID: i64 = 7;

    fn spec_with_loan(initial_ltv_bps: i32, liquidation_ltv_bps: i32, max_amount: i64) -> CoreSymbolSpecification {
        let mut spec = CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        };
        spec.loan_config.update(initial_ltv_bps, liquidation_ltv_bps, 0, max_amount, 0);
        spec
    }

    fn setup() -> (RiskEngine, UserProfileService, SymbolSpecificationProvider) {
        let mut engine = RiskEngine::new();
        engine.last_price_cache.insert(SYMBOL, LastPriceCacheRecord::with_mark(1));
        engine.loan_service.add_to_loan_pool_available(QUOTE, 1_000_000);

        let mut ups = UserProfileService::new();
        ups.add_empty_user_profile(UID);
        ups.get_mut(UID).unwrap().add_to_account(BASE, 10_000);

        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_symbol(spec_with_loan(5_000, 8_000, 0));
        ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });

        (engine, ups, ssp)
    }

    fn create_cmd(order_id: i64, loan_id: i64, collateral: i64, principal: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCreate,
            order_id,
            uid: UID,
            symbol: SYMBOL,
            size: collateral,
            price: principal,
            reserve_bid_price: loan_id,
            timestamp: 1_000,
            ..Default::default()
        }
    }

    fn conserved_quote_total(engine: &RiskEngine, ups: &UserProfileService) -> i64 {
        let accounts_quote: i64 = ups.users.values().map(|u| u.account(QUOTE)).sum();
        accounts_quote + engine.loan_service.get_loan_pool_available(QUOTE) + engine.loan_service.get_interest_revenue(QUOTE)
    }

    #[test]
    fn loan_create_success_disburses_and_records_loan() {
        let (mut engine, mut ups, ssp) = setup();
        let before = conserved_quote_total(&engine, &ups);

        let mut cmd = create_cmd(1, 42, 1_000, 400);
        let rc = LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp);

        assert_eq!(rc, CommandResultCode::Success);
        let up = ups.get(UID).unwrap();
        assert_eq!(up.account(QUOTE), 400);
        assert_eq!(up.account(BASE), 10_000);
        let loan = up.isolated_loans.get(&42).unwrap();
        assert_eq!(loan.collateral_amount, 1_000);
        assert_eq!(loan.outstanding_principal, 400);
        assert_eq!(loan.rate_mode, LoanRateMode::Locked);

        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_000 - 400);
        assert_eq!(engine.loan_service.get_loan_pool_borrowed(QUOTE), 400);
        assert_eq!(conserved_quote_total(&engine, &ups), before);
    }

    #[test]
    fn loan_create_floating_rate_mode_via_user_cookie_low_byte() {
        let (mut engine, mut ups, ssp) = setup();
        engine.loan_service.floating_rate.current_rate_bps.insert(QUOTE, 321);

        let mut cmd = create_cmd(1, 42, 1_000, 400);
        cmd.user_cookie = LoanRateMode::Floating.code() as i32;
        let rc = LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp);

        assert_eq!(rc, CommandResultCode::Success);
        let loan = ups.get(UID).unwrap().isolated_loans.get(&42).unwrap();
        assert_eq!(loan.rate_mode, LoanRateMode::Floating);
        assert_eq!(loan.rate_bps, 321);
        assert_eq!(loan.acc_snapshot, 0);
    }

    #[test]
    fn loan_create_rejects_auth_invalid_user() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = create_cmd(1, 42, 1_000, 400);
        cmd.uid = 999;
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::AuthInvalidUser
        );
    }

    #[test]
    fn loan_create_rejects_suspended_user() {
        let (mut engine, mut ups, ssp) = setup();
        ups.get_mut(UID).unwrap().user_status = UserStatus::Suspended;
        let mut cmd = create_cmd(1, 42, 1_000, 400);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanUserSuspended
        );
    }

    #[test]
    fn loan_create_idempotent_retry_of_same_order_id_is_rejected() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd1 = create_cmd(1, 42, 1_000, 400);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd1, &mut ups, &ssp), CommandResultCode::Success);

        let mut cmd2 = create_cmd(1, 43, 1_000, 400);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd2, &mut ups, &ssp),
            CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame
        );
        assert!(ups.get(UID).unwrap().isolated_loans.get(&43).is_none());
    }

    #[test]
    fn loan_create_rejects_not_enabled_when_symbol_missing() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = create_cmd(1, 42, 1_000, 400);
        cmd.symbol = 999;
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanNotEnabled
        );
    }

    #[test]
    fn loan_create_rejects_not_enabled_when_loan_config_disabled() {
        let (mut engine, mut ups, mut ssp) = setup();
        ssp.symbols.get_mut(&SYMBOL).unwrap().loan_config.update(0, 0, 0, 0, 0);
        let mut cmd = create_cmd(1, 42, 1_000, 400);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanNotEnabled
        );
    }

    #[test]
    fn loan_create_rejects_already_exists() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd1 = create_cmd(1, 42, 1_000, 400);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd1, &mut ups, &ssp), CommandResultCode::Success);

        let mut cmd2 = create_cmd(2, 42, 1_000, 400);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd2, &mut ups, &ssp),
            CommandResultCode::LoanAlreadyExists
        );
    }

    #[test]
    fn loan_create_rejects_invalid_amount() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = create_cmd(1, 42, 0, 400);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
        let mut cmd2 = create_cmd(2, 43, 1_000, 0);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd2, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
    }

    #[test]
    fn loan_create_rejects_principal_exceeds_limit() {
        let (mut engine, mut ups, mut ssp) = setup();
        ssp.symbols.get_mut(&SYMBOL).unwrap().loan_config.update(5_000, 8_000, 0, 100, 0);
        let mut cmd = create_cmd(1, 42, 1_000, 200);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanPrincipalExceedsLimit
        );
    }

    #[test]
    fn loan_create_rejects_markprice_not_ready() {
        let (mut engine, mut ups, ssp) = setup();
        engine.last_price_cache.remove(&SYMBOL);
        let mut cmd = create_cmd(1, 42, 1_000, 400);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanMarkpriceNotReady
        );
    }

    #[test]
    fn loan_create_rejects_ltv_too_high() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = create_cmd(1, 42, 1_000, 501);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanLtvTooHigh
        );
    }

    #[test]
    fn loan_create_rejects_collateral_insufficient() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = create_cmd(1, 42, 20_000, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanCollateralInsufficient
        );
    }

    #[test]
    fn loan_create_rejects_pool_insufficient() {
        let (mut engine, mut ups, ssp) = setup();
        engine.loan_service.loan_pool_available.insert(QUOTE, 100);
        let mut cmd = create_cmd(1, 42, 1_000, 400);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanPoolInsufficient
        );
    }

    #[test]
    fn loan_create_rejects_pool_utilization_exceeded() {
        let (mut engine, mut ups, ssp) = setup();
        engine.loan_service.loan_pool_available.insert(QUOTE, 1_000);
        engine.loan_service.loan_pool_borrowed.insert(QUOTE, 8_000);
        let mut cmd = create_cmd(1, 42, 1_000, 200);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanPoolUtilizationExceeded
        );
    }

    fn repay_cmd(order_id: i64, loan_id: i64, repay_amount: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanRepay,
            order_id,
            uid: UID,
            reserve_bid_price: loan_id,
            price: repay_amount,
            timestamp: ts,
            ..Default::default()
        }
    }

    fn open_loan(engine: &mut RiskEngine, ups: &mut UserProfileService, ssp: &SymbolSpecificationProvider, loan_id: i64, collateral: i64, principal: i64, rate_bps_seed: Option<i32>) {
        if let Some(rate) = rate_bps_seed {
            engine.loan_service.floating_rate.base_bps = 0;
            engine.loan_service.fixed_rate.locked_rate_adjust_bps = rate;
        }
        let mut cmd = create_cmd(loan_id, loan_id, collateral, principal);
        assert_eq!(LoanCommandDispatcher::dispatch(engine, &mut cmd, ups, ssp), CommandResultCode::Success);
    }

    #[test]
    fn loan_repay_interest_first_then_principal() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, Some(5_000));
        assert_eq!(ups.get(UID).unwrap().isolated_loans.get(&42).unwrap().rate_bps, 5_000);

        let mut cmd = repay_cmd(2, 42, 100, 1_000 + crate::core::processors::loan::loan_service::YEAR_MS);
        let rc = LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp);
        assert_eq!(rc, CommandResultCode::Success);

        let loan = ups.get(UID).unwrap().isolated_loans.get(&42).unwrap();
        assert_eq!(loan.accumulated_interest, 100);
        assert_eq!(loan.outstanding_principal, 400);
        assert_eq!(loan.cum_interest_paid, 100);
        assert_eq!(engine.loan_service.get_interest_revenue(QUOTE), 100);
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_000 - 400);
    }

    #[test]
    fn loan_repay_zero_means_full_payoff_but_collateral_survives_until_released() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, Some(5_000));
        let before = conserved_quote_total(&engine, &ups);

        ups.get_mut(UID).unwrap().add_to_account(QUOTE, 600);

        let mut cmd = repay_cmd(2, 42, 0, 1_000 + crate::core::processors::loan::loan_service::YEAR_MS);
        let rc = LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp);
        assert_eq!(rc, CommandResultCode::Success);

        let loan = ups.get(UID).unwrap().isolated_loans.get(&42).expect("collateral still pledged, loan stays");
        assert_eq!(loan.outstanding_principal, 0);
        assert_eq!(loan.accumulated_interest, 0);
        assert_eq!(loan.collateral_amount, 1_000);
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_000);
        assert_eq!(engine.loan_service.get_interest_revenue(QUOTE), 200);
        assert_eq!(conserved_quote_total(&engine, &ups), before + 600);
    }

    #[test]
    fn loan_repay_rejects_not_found() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = repay_cmd(1, 999, 0, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanNotFound
        );
    }

    #[test]
    fn loan_repay_rejects_uid_mismatch() {
        let (mut engine, mut ups, ssp) = setup();
        let mut foreign_loan = IsolatedLoanRecord::new(999, 42, SYMBOL, BASE, QUOTE, 0, 1_000);
        foreign_loan.outstanding_principal = 100;
        ups.get_mut(UID).unwrap().isolated_loans.insert(42, foreign_loan);

        let mut cmd = repay_cmd(2, 42, 0, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanUidMismatch
        );
    }

    #[test]
    fn loan_repay_rejects_invalid_amount_when_negative() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);
        let mut cmd = repay_cmd(2, 42, -1, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
    }

    #[test]
    fn loan_repay_rejects_account_insufficient() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);
        ups.get_mut(UID).unwrap().accounts.insert(QUOTE, 50);
        let mut cmd = repay_cmd(2, 42, 200, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanAccountInsufficient
        );
    }

    fn add_collateral_cmd(order_id: i64, loan_id: i64, amount: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanAddCollateral,
            order_id,
            uid: UID,
            reserve_bid_price: loan_id,
            size: amount,
            timestamp: 2_000,
            ..Default::default()
        }
    }

    #[test]
    fn loan_add_collateral_increases_amount_and_accrues() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, Some(5_000));

        let mut cmd = add_collateral_cmd(2, 42, 500);
        let rc = LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp);
        assert_eq!(rc, CommandResultCode::Success);

        let loan = ups.get(UID).unwrap().isolated_loans.get(&42).unwrap();
        assert_eq!(loan.collateral_amount, 1_500);
    }

    #[test]
    fn loan_add_collateral_rejects_not_found_and_invalid_amount() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        let mut not_found = add_collateral_cmd(2, 999, 100);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut not_found, &mut ups, &ssp),
            CommandResultCode::LoanNotFound
        );

        let mut bad_amount = add_collateral_cmd(3, 42, 0);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut bad_amount, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
    }

    #[test]
    fn loan_add_collateral_rejects_uid_mismatch() {
        let (mut engine, mut ups, ssp) = setup();
        let foreign_loan = IsolatedLoanRecord::new(999, 42, SYMBOL, BASE, QUOTE, 0, 1_000);
        ups.get_mut(UID).unwrap().isolated_loans.insert(42, foreign_loan);

        let mut mismatch = add_collateral_cmd(4, 42, 100);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut mismatch, &mut ups, &ssp),
            CommandResultCode::LoanUidMismatch
        );
    }

    #[test]
    fn loan_add_collateral_rejects_insufficient_free_balance() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 9_000, 400, None);
        let mut cmd = add_collateral_cmd(2, 42, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanCollateralInsufficient
        );
    }

    fn release_cmd(order_id: i64, loan_id: i64, amount: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanReleaseCollateral,
            order_id,
            uid: UID,
            reserve_bid_price: loan_id,
            size: amount,
            timestamp: ts,
            ..Default::default()
        }
    }

    #[test]
    fn loan_release_collateral_allows_down_to_just_above_liquidation_line() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        let mut cmd = release_cmd(2, 42, 499, 2_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().isolated_loans.get(&42).unwrap().collateral_amount, 501);
    }

    #[test]
    fn loan_release_collateral_rejects_at_or_below_liquidation_line() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        let mut cmd = release_cmd(2, 42, 500, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanLtvTooHighAfterRelease
        );
        assert_eq!(ups.get(UID).unwrap().isolated_loans.get(&42).unwrap().collateral_amount, 1_000);
    }

    #[test]
    fn loan_release_collateral_rejects_new_collateral_zero_with_remaining_debt() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        let mut cmd = release_cmd(2, 42, 1_000, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanLtvTooHighAfterRelease
        );
    }

    #[test]
    fn loan_release_collateral_full_release_allowed_when_debt_fully_repaid_first() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);
        ups.get_mut(UID).unwrap().add_to_account(QUOTE, 400);
        let mut repay = repay_cmd(2, 42, 0, 2_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut repay, &mut ups, &ssp), CommandResultCode::Success);
        assert!(ups.get(UID).unwrap().isolated_loans.get(&42).is_some());

        let mut cmd = release_cmd(3, 42, 1_000, 3_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert!(ups.get(UID).unwrap().isolated_loans.get(&42).is_none());
    }

    #[test]
    fn loan_release_collateral_rejects_exceeds_loan_and_not_found() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        let mut exceeds = release_cmd(2, 42, 1_001, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut exceeds, &mut ups, &ssp),
            CommandResultCode::LoanCollateralExceedsLoan
        );

        let mut not_found = release_cmd(3, 999, 1, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut not_found, &mut ups, &ssp),
            CommandResultCode::LoanNotFound
        );
    }

    #[test]
    fn loan_release_collateral_rejects_uid_mismatch() {
        let (mut engine, mut ups, ssp) = setup();
        let mut foreign_loan = IsolatedLoanRecord::new(999, 42, SYMBOL, BASE, QUOTE, 0, 1_000);
        foreign_loan.collateral_amount = 100;
        ups.get_mut(UID).unwrap().isolated_loans.insert(42, foreign_loan);

        let mut mismatch = release_cmd(4, 42, 1, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut mismatch, &mut ups, &ssp),
            CommandResultCode::LoanUidMismatch
        );
    }

    const CROSS_COLLATERAL_WEIGHT_BPS: i32 = 5_000;

    fn cross_setup_weight_only() -> (RiskEngine, UserProfileService, SymbolSpecificationProvider) {
        let (engine, ups, mut ssp) = setup();
        ssp.currencies.get_mut(&BASE).unwrap().collateral_weight_bps = CROSS_COLLATERAL_WEIGHT_BPS;
        (engine, ups, ssp)
    }

    fn cross_setup() -> (RiskEngine, UserProfileService, SymbolSpecificationProvider) {
        let (mut engine, ups, ssp) = cross_setup_weight_only();
        engine.loan_service.global_config.numeraire_currency = QUOTE;
        (engine, ups, ssp)
    }

    fn cross_add_cmd(order_id: i64, currency: i32, amount: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCrossAddCollateral,
            order_id,
            uid: UID,
            symbol: currency,
            size: amount,
            timestamp: 1_000,
            ..Default::default()
        }
    }

    fn cross_withdraw_cmd(order_id: i64, currency: i32, amount: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCrossWithdrawCollateral,
            order_id,
            uid: UID,
            symbol: currency,
            size: amount,
            timestamp: ts,
            ..Default::default()
        }
    }

    fn cross_borrow_cmd(order_id: i64, loan_id: i64, symbol_id: i32, principal: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCrossBorrow,
            order_id,
            uid: UID,
            symbol: symbol_id,
            price: principal,
            reserve_bid_price: loan_id,
            timestamp: ts,
            ..Default::default()
        }
    }

    fn cross_repay_cmd(order_id: i64, loan_id: i64, repay_amount: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCrossRepay,
            order_id,
            uid: UID,
            reserve_bid_price: loan_id,
            price: repay_amount,
            timestamp: ts,
            ..Default::default()
        }
    }

    fn pledge_cross_collateral(
        engine: &mut RiskEngine,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
        order_id: i64,
        amount: i64,
    ) {
        let mut cmd = cross_add_cmd(order_id, BASE, amount);
        assert_eq!(LoanCommandDispatcher::dispatch(engine, &mut cmd, ups, ssp), CommandResultCode::Success);
    }

    fn open_cross_loan(
        engine: &mut RiskEngine,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
        loan_id: i64,
        principal: i64,
        ts: i64,
    ) {
        let mut cmd = cross_borrow_cmd(loan_id, loan_id, SYMBOL, principal, ts);
        assert_eq!(LoanCommandDispatcher::dispatch(engine, &mut cmd, ups, ssp), CommandResultCode::Success);
    }

    #[test]
    fn cross_add_collateral_credits_account_level_pool_when_weight_positive() {
        let (mut engine, mut ups, ssp) = cross_setup();
        let mut cmd = cross_add_cmd(1, BASE, 1_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);

        let up = ups.get(UID).unwrap();
        assert_eq!(up.cross_loan_collateral(BASE), 1_000);
        assert_eq!(up.account(BASE), 10_000);
    }

    #[test]
    fn cross_add_collateral_rejects_currency_with_zero_collateral_weight() {
        let (mut engine, mut ups, ssp) = cross_setup();
        let mut cmd = cross_add_cmd(1, QUOTE, 100);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanCollateralNotAllowed
        );
        assert_eq!(ups.get(UID).unwrap().cross_loan_collateral(QUOTE), 0);
    }

    #[test]
    fn cross_add_collateral_rejects_invalid_amount_and_insufficient_balance() {
        let (mut engine, mut ups, ssp) = cross_setup();
        let mut zero = cross_add_cmd(1, BASE, 0);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut zero, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );

        let mut too_much = cross_add_cmd(2, BASE, 20_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut too_much, &mut ups, &ssp),
            CommandResultCode::LoanCollateralInsufficient
        );
    }

    #[test]
    fn cross_borrow_rejects_numeraire_not_configured() {
        let (mut engine, mut ups, ssp) = cross_setup_weight_only();
        let mut cmd = cross_borrow_cmd(1, 42, SYMBOL, 200, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanNumeraireNotConfigured
        );
        assert!(ups.get(UID).unwrap().cross_loans.is_empty());
    }

    #[test]
    fn cross_borrow_success_is_always_floating_and_disburses() {
        let (mut engine, mut ups, ssp) = cross_setup();
        engine.loan_service.floating_rate.current_rate_bps.insert(QUOTE, 321);
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);

        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let up = ups.get(UID).unwrap();
        let loan = up.cross_loans.get(&42).unwrap();
        assert!(!loan.is_fixed_rate());
        assert_eq!(loan.rate_bps, 321);
        assert_eq!(loan.outstanding_principal, 200);
        assert_eq!(loan.loan_currency, QUOTE);
        assert_eq!(up.account(QUOTE), 200);
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_000 - 200);
        assert_eq!(engine.loan_service.get_loan_pool_borrowed(QUOTE), 200);
    }

    #[test]
    fn cross_borrow_rolls_back_on_ltv_too_high_after_borrow_without_touching_pool() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 100);

        let mut cmd = cross_borrow_cmd(2, 42, SYMBOL, 200, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanLtvTooHighAfterBorrow
        );

        let up = ups.get(UID).unwrap();
        assert!(up.cross_loans.get(&42).is_none());
        assert_eq!(up.account(QUOTE), 0);
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_000);
        assert_eq!(engine.loan_service.get_loan_pool_borrowed(QUOTE), 0);
    }

    #[test]
    fn cross_borrow_rejects_already_exists_not_enabled_and_principal_exceeds_limit() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let mut dup = cross_borrow_cmd(2, 42, SYMBOL, 100, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut dup, &mut ups, &ssp),
            CommandResultCode::LoanAlreadyExists
        );

        let mut bad_symbol = cross_borrow_cmd(3, 43, 999, 100, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut bad_symbol, &mut ups, &ssp),
            CommandResultCode::LoanNotEnabled
        );

        let mut invalid_principal = cross_borrow_cmd(4, 44, SYMBOL, 0, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut invalid_principal, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
    }

    #[test]
    fn cross_borrow_rejects_principal_exceeds_limit_and_pool_insufficient() {
        let (mut engine, mut ups, mut ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        ssp.symbols.get_mut(&SYMBOL).unwrap().loan_config.update(5_000, 8_000, 0, 50, 0);
        let mut too_big = cross_borrow_cmd(2, 42, SYMBOL, 100, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut too_big, &mut ups, &ssp),
            CommandResultCode::LoanPrincipalExceedsLimit
        );

        ssp.symbols.get_mut(&SYMBOL).unwrap().loan_config.update(5_000, 8_000, 0, 0, 0);
        engine.loan_service.loan_pool_available.insert(QUOTE, 10);
        let mut pool_short = cross_borrow_cmd(3, 43, SYMBOL, 100, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut pool_short, &mut ups, &ssp),
            CommandResultCode::LoanPoolInsufficient
        );
    }

    #[test]
    fn cross_withdraw_collateral_rejects_invalid_amount_and_exceeds_pledged() {
        let (mut engine, mut ups, ssp) = cross_setup();
        let mut zero = cross_withdraw_cmd(1, BASE, 0, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut zero, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );

        let mut exceeds = cross_withdraw_cmd(2, BASE, 1, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut exceeds, &mut ups, &ssp),
            CommandResultCode::LoanCollateralExceedsLoan
        );
    }

    #[test]
    fn cross_withdraw_collateral_rejects_numeraire_not_configured() {
        let (mut engine, mut ups, ssp) = cross_setup_weight_only();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        let mut cmd = cross_withdraw_cmd(2, BASE, 100, 1_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanNumeraireNotConfigured
        );
        assert_eq!(ups.get(UID).unwrap().cross_loan_collateral(BASE), 1_000);
    }

    #[test]
    fn cross_withdraw_collateral_succeeds_when_new_ltv_stays_below_liquidation_line() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let mut cmd = cross_withdraw_cmd(2, BASE, 100, 2_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(ups.get(UID).unwrap().cross_loan_collateral(BASE), 900);
    }

    #[test]
    fn cross_withdraw_collateral_reverts_subtraction_when_new_ltv_too_high() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let mut cmd = cross_withdraw_cmd(2, BASE, 600, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanCrossLtvTooHighAfterWithdraw
        );
        assert_eq!(ups.get(UID).unwrap().cross_loan_collateral(BASE), 1_000);
    }

    #[test]
    fn cross_repay_full_payoff_removes_loan_but_never_touches_collateral_pool() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let before_collateral = ups.get(UID).unwrap().cross_loan_collateral(BASE);
        let mut cmd = cross_repay_cmd(2, 42, 0, 2_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);

        let up = ups.get(UID).unwrap();
        assert!(up.cross_loans.get(&42).is_none());
        assert_eq!(up.cross_loan_collateral(BASE), before_collateral);
        assert_eq!(up.account(QUOTE), 0);
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_000);
    }

    #[test]
    fn cross_repay_partial_reduces_principal_and_keeps_loan_open() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let mut cmd = cross_repay_cmd(2, 42, 50, 2_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);

        let up = ups.get(UID).unwrap();
        let loan = up.cross_loans.get(&42).expect("partial repay keeps the loan open");
        assert_eq!(loan.outstanding_principal, 150);
        assert_eq!(up.cross_loan_collateral(BASE), 1_000);
    }

    #[test]
    fn cross_repay_rejects_not_found_uid_mismatch_and_invalid_amount() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let mut not_found = cross_repay_cmd(2, 999, 0, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut not_found, &mut ups, &ssp),
            CommandResultCode::LoanNotFound
        );

        let mut negative = cross_repay_cmd(3, 42, -1, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut negative, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );

        let mut foreign = CrossLoanRecord::new(999, 77, SYMBOL, QUOTE, 0, 1_000);
        foreign.outstanding_principal = 10;
        ups.get_mut(UID).unwrap().cross_loans.insert(77, foreign);
        let mut mismatch = cross_repay_cmd(4, 77, 0, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut mismatch, &mut ups, &ssp),
            CommandResultCode::LoanUidMismatch
        );
    }

    #[test]
    fn cross_repay_rejects_account_insufficient() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);
        ups.get_mut(UID).unwrap().accounts.insert(QUOTE, 50);
        let mut cmd = cross_repay_cmd(2, 42, 100, 2_000);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanAccountInsufficient
        );
    }

    fn force_liquidate_cmd(order_id: i64, uid: i64, loan_id: i64, lots: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanForceLiquidate,
            order_id,
            uid,
            symbol: SYMBOL,
            size: lots,
            price: 1,
            reserve_bid_price: loan_id,
            ..Default::default()
        }
    }

    #[test]
    fn force_liquidate_r1_success_premoves_collateral_and_sets_ask_ioc() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        let mut cmd = force_liquidate_cmd(1, UID, 42, 600);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        assert_eq!(cmd.action, Some(OrderAction::Ask));
        assert_eq!(cmd.order_type, Some(OrderType::Ioc));
        let up = ups.get(UID).unwrap();
        assert_eq!(up.isolated_loans.get(&42).unwrap().collateral_amount, 400);
        assert_eq!(up.locked(BASE), 600);
        assert_eq!(up.account(BASE), 10_000);
    }

    #[test]
    fn force_liquidate_r1_rejects_not_found() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = force_liquidate_cmd(1, UID, 999, 100);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanNotFound
        );
    }

    #[test]
    fn force_liquidate_r1_rejects_uid_mismatch() {
        let (mut engine, mut ups, ssp) = setup();
        let mut foreign_loan = IsolatedLoanRecord::new(999, 42, SYMBOL, BASE, QUOTE, 0, 1_000);
        foreign_loan.collateral_amount = 1_000;
        ups.get_mut(UID).unwrap().isolated_loans.insert(42, foreign_loan);

        let mut cmd = force_liquidate_cmd(1, UID, 42, 100);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanUidMismatch
        );
    }

    #[test]
    fn force_liquidate_r1_rejects_not_enabled_when_symbol_or_currency_mismatch() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        let mut missing_symbol = force_liquidate_cmd(1, UID, 42, 100);
        missing_symbol.symbol = 999;
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut missing_symbol, &mut ups, &ssp),
            CommandResultCode::LoanNotEnabled
        );
    }

    #[test]
    fn force_liquidate_r1_rejects_invalid_amount_zero_or_exceeding_collateral() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        let mut zero = force_liquidate_cmd(1, UID, 42, 0);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut zero, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );

        let mut too_much = force_liquidate_cmd(2, UID, 42, 1_001);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut too_much, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
        assert_eq!(ups.get(UID).unwrap().isolated_loans.get(&42).unwrap().collateral_amount, 1_000);
    }

    #[test]
    fn force_liquidate_r1_duplicate_submission_is_compare_and_consume_idempotent() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);

        let mut first = force_liquidate_cmd(1, UID, 42, 700);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut first, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        assert_eq!(ups.get(UID).unwrap().isolated_loans.get(&42).unwrap().collateral_amount, 300);

        let mut second = force_liquidate_cmd(2, UID, 42, 700);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut second, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
        assert_eq!(ups.get(UID).unwrap().isolated_loans.get(&42).unwrap().collateral_amount, 300);
        assert_eq!(ups.get(UID).unwrap().locked(BASE), 700);
    }

    #[test]
    fn force_liquidate_r1_ignores_suspended_status_unlike_other_loan_commands() {
        let (mut engine, mut ups, ssp) = setup();
        open_loan(&mut engine, &mut ups, &ssp, 42, 1_000, 400, None);
        ups.get_mut(UID).unwrap().user_status = UserStatus::Suspended;

        let mut cmd = force_liquidate_cmd(1, UID, 42, 500);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
    }

    fn cross_force_liquidate_cmd(order_id: i64, uid: i64, target_loan_id: i64, lots: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::LoanCrossForceLiquidate,
            order_id,
            uid,
            symbol: SYMBOL,
            size: lots,
            price: 1,
            reserve_bid_price: target_loan_id,
            ..Default::default()
        }
    }

    #[test]
    fn cross_force_liquidate_r1_success_premoves_collateral_and_sets_ask_ioc() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let mut cmd = cross_force_liquidate_cmd(2, UID, 42, 600);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );

        assert_eq!(cmd.action, Some(OrderAction::Ask));
        assert_eq!(cmd.order_type, Some(OrderType::Ioc));
        let up = ups.get(UID).unwrap();
        assert_eq!(up.cross_loan_collateral(BASE), 400);
        assert_eq!(up.locked(BASE), 600);
        assert_eq!(up.account(BASE), 10_000);
    }

    #[test]
    fn cross_force_liquidate_r1_rejects_not_found() {
        let (mut engine, mut ups, ssp) = cross_setup();
        let mut cmd = cross_force_liquidate_cmd(1, UID, 999, 100);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanNotFound
        );
    }

    #[test]
    fn cross_force_liquidate_r1_rejects_uid_mismatch() {
        let (mut engine, mut ups, ssp) = cross_setup();
        let mut foreign_loan = CrossLoanRecord::new(999, 42, SYMBOL, QUOTE, 0, 1_000);
        foreign_loan.outstanding_principal = 200;
        ups.get_mut(UID).unwrap().cross_loans.insert(42, foreign_loan);
        ups.get_mut(UID).unwrap().add_to_cross_loan_collateral(BASE, 1_000);

        let mut cmd = cross_force_liquidate_cmd(2, UID, 42, 100);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanUidMismatch
        );
    }

    #[test]
    fn cross_force_liquidate_r1_rejects_not_enabled_when_quote_currency_mismatch() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let mut cmd = cross_force_liquidate_cmd(2, UID, 42, 100);
        cmd.symbol = 999;
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanNotEnabled
        );
    }

    #[test]
    fn cross_force_liquidate_r1_rejects_invalid_amount_zero_or_exceeding_available() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let mut zero = cross_force_liquidate_cmd(2, UID, 42, 0);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut zero, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
        let mut too_much = cross_force_liquidate_cmd(3, UID, 42, 1_001);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut too_much, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
        assert_eq!(ups.get(UID).unwrap().cross_loan_collateral(BASE), 1_000);
    }

    #[test]
    fn cross_force_liquidate_r1_duplicate_submission_is_compare_and_consume_idempotent() {
        let (mut engine, mut ups, ssp) = cross_setup();
        pledge_cross_collateral(&mut engine, &mut ups, &ssp, 1, 1_000);
        open_cross_loan(&mut engine, &mut ups, &ssp, 42, 200, 1_000);

        let mut first = cross_force_liquidate_cmd(2, UID, 42, 700);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut first, &mut ups, &ssp),
            CommandResultCode::ValidForMatchingEngine
        );
        assert_eq!(ups.get(UID).unwrap().cross_loan_collateral(BASE), 300);

        let mut second = cross_force_liquidate_cmd(3, UID, 42, 700);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut second, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
        assert_eq!(ups.get(UID).unwrap().cross_loan_collateral(BASE), 300);
    }

    fn pool_cmd(command: OrderCommandType, currency: i32, amount: i64) -> OrderCommand {
        OrderCommand { command, uid: 0, symbol: currency, size: amount, ..Default::default() }
    }

    #[test]
    fn pool_deposit_credits_available_and_hedges_adjustments() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = pool_cmd(OrderCommandType::PoolDeposit, QUOTE, 500);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_500);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), -500);
    }

    #[test]
    fn pool_deposit_rejects_invalid_amount() {
        let (mut engine, mut ups, ssp) = setup();
        let mut zero = pool_cmd(OrderCommandType::PoolDeposit, QUOTE, 0);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut zero, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
        let mut negative = pool_cmd(OrderCommandType::PoolDeposit, QUOTE, -5);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut negative, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
    }

    #[test]
    fn pool_withdraw_debits_available_and_hedges_adjustments() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = pool_cmd(OrderCommandType::PoolWithdraw, QUOTE, 300_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 700_000);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), 300_000);
    }

    #[test]
    fn pool_withdraw_rejects_amount_exceeding_available_and_leaves_bucket_untouched() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = pool_cmd(OrderCommandType::PoolWithdraw, QUOTE, 1_000_001);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanPoolInsufficient
        );
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_000);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap_or(&0), 0);
    }

    #[test]
    fn pool_deposit_then_withdraw_round_trip_hedges_back_to_zero() {
        let (mut engine, mut ups, ssp) = setup();
        let mut dep = pool_cmd(OrderCommandType::PoolDeposit, QUOTE, 1_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut dep, &mut ups, &ssp), CommandResultCode::Success);
        let mut wd = pool_cmd(OrderCommandType::PoolWithdraw, QUOTE, 1_000);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut wd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), 0);
        assert_eq!(engine.loan_service.get_loan_pool_available(QUOTE), 1_000_000);
    }

    #[test]
    fn loan_if_deposit_credits_and_hedges_adjustments() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = pool_cmd(OrderCommandType::LoanIfDeposit, QUOTE, 200);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(engine.loan_service.get_loan_insurance_fund(QUOTE), 200);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), -200);
    }

    #[test]
    fn loan_if_deposit_rejects_invalid_amount() {
        let (mut engine, mut ups, ssp) = setup();
        let mut cmd = pool_cmd(OrderCommandType::LoanIfDeposit, QUOTE, 0);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanInvalidAmount
        );
    }

    #[test]
    fn loan_if_withdraw_debits_and_hedges_adjustments() {
        let (mut engine, mut ups, ssp) = setup();
        engine.loan_service.add_to_loan_insurance_fund(QUOTE, 500);
        let mut cmd = pool_cmd(OrderCommandType::LoanIfWithdraw, QUOTE, 300);
        assert_eq!(LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp), CommandResultCode::Success);
        assert_eq!(engine.loan_service.get_loan_insurance_fund(QUOTE), 200);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap(), 300);
    }

    #[test]
    fn loan_if_withdraw_rejects_when_insufficient_and_never_pushes_more_negative() {
        let (mut engine, mut ups, ssp) = setup();
        engine.loan_service.add_to_loan_insurance_fund(QUOTE, -500);
        let mut cmd = pool_cmd(OrderCommandType::LoanIfWithdraw, QUOTE, 100);
        assert_eq!(
            LoanCommandDispatcher::dispatch(&mut engine, &mut cmd, &mut ups, &ssp),
            CommandResultCode::LoanIfInsufficient
        );
        assert_eq!(engine.loan_service.get_loan_insurance_fund(QUOTE), -500);
        assert_eq!(*engine.adjustments.get(&QUOTE).unwrap_or(&0), 0);
    }

}
