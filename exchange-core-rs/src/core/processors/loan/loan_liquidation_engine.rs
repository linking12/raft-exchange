use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet};
use std::rc::Rc;

use crate::core::common::last_price_cache_record::LastPriceCacheRecord;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::cross_loan_record::CrossLoanRecord;
use crate::core::common::fund_event::{FundEvent, FundEventType};
use crate::core::common::isolated_loan_record::{IsolatedLoanRecord, LoanRateMode};
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;
use crate::core::common::user_profile::UserProfile;
use crate::core::processors::liquidation::command_submitter::{CommandSubmitter, CommandSubmitterHandle};
use crate::core::processors::liquidation::scheduler::covered_by_scan_slice;
use crate::core::processors::loan::loan_service::{
    LoanService, BPS_SCALE, ORDERID_SUBTYPE_CROSS, ORDERID_SUBTYPE_ISOLATED,
};
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::utils::core_arithmetic_utils::{add_exact, ceil_mul_div, mul_exact};

const MS_PER_DAY: i64 = 86_400 * 1_000;

#[derive(Debug, Default)]
pub struct LoanLiquidationEngine {
    pub isolated_loan_symbol_to_users: BTreeMap<i32, BTreeSet<i64>>,
    pub cross_loan_currency_to_users: BTreeMap<i32, BTreeSet<i64>>,
    command_submitter: CommandSubmitterHandle,
}

impl LoanLiquidationEngine {
    pub fn new() -> Self {
        LoanLiquidationEngine::default()
    }

    pub fn set_command_submitter(&mut self, submitter: Rc<RefCell<dyn CommandSubmitter>>) {
        self.command_submitter.set(submitter);
    }

    pub fn rebuild_indices(&mut self, ups: &UserProfileService) {
        self.isolated_loan_symbol_to_users.clear();
        self.cross_loan_currency_to_users.clear();
        for up in ups.users.values() {
            for loan in up.isolated_loans.values() {
                if !loan.is_empty() {
                    self.on_isolated_loan_opened(up.uid, loan.symbol_id);
                }
            }
            self.sync_cross_exposure(up);
        }
    }

    pub fn check_loans(
        &mut self,
        cmd: &OrderCommand,
        ups: &UserProfileService,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        loan_service: &LoanService,
        fund_events: &mut Vec<FundEvent>,
    ) {
        if cmd.symbol >= 0 {
            let spec = match ssp.get_symbol(cmd.symbol) {
                Some(s) => s,
                None => return,
            };
            let mut uids: BTreeSet<i64> = BTreeSet::new();
            if let Some(iso) = self.isolated_loan_symbol_to_users.get(&spec.symbol_id) {
                uids.extend(iso.iter().copied());
            }
            if let Some(base) = self.cross_loan_currency_to_users.get(&spec.base_currency) {
                uids.extend(base.iter().copied());
            }
            if let Some(quote) = self.cross_loan_currency_to_users.get(&spec.quote_currency) {
                uids.extend(quote.iter().copied());
            }
            for uid in uids {
                if let Some(up) = ups.get(uid) {
                    self.check_user(up, cmd.timestamp, ssp, last_price_cache, loan_service, fund_events);
                }
            }
            return;
        }
        for up in ups.users.values() {
            if !covered_by_scan_slice(cmd, up.uid) {
                continue;
            }
            self.check_user(up, cmd.timestamp, ssp, last_price_cache, loan_service, fund_events);
        }
    }

    fn check_user(
        &mut self,
        up: &UserProfile,
        ts: i64,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        loan_service: &LoanService,
        fund_events: &mut Vec<FundEvent>,
    ) {
        for loan in up.isolated_loans.values() {
            self.check_isolated(loan, ts, ssp, last_price_cache, loan_service, fund_events);
        }
        self.check_cross(up, ts, ssp, last_price_cache, loan_service, fund_events);
    }

    fn check_isolated(
        &mut self,
        loan: &IsolatedLoanRecord,
        ts: i64,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        loan_service: &LoanService,
        fund_events: &mut Vec<FundEvent>,
    ) {
        if loan.is_empty() {
            return;
        }
        let spec = match ssp.get_symbol(loan.symbol_id) {
            Some(s) => s,
            None => return,
        };
        let mark_price = match last_price_cache.get(&loan.symbol_id) {
            Some(r) if r.mark_price != 0 => r.mark_price,
            _ => return,
        };
        let base_spec = ssp.get_currency(loan.collateral_currency);
        let loan_currency_spec = ssp.get_currency(loan.loan_currency);
        let collateral_value =
            LoanService::collateral_value_in_quote_currency(loan.collateral_amount, spec, mark_price, base_spec, loan_currency_spec);
        if collateral_value <= 0 {

            return;
        }
        let real_debt = add_exact(loan.outstanding_principal, loan_service.calculate_display_interest(loan, ts));
        let ltv_scaled = mul_exact(real_debt, BPS_SCALE);

        let term_expired = loan.rate_mode == LoanRateMode::Locked
            && spec.loan_config.max_term_days > 0
            && (ts - loan.opened_at_ts) > spec.loan_config.max_term_days as i64 * MS_PER_DAY;

        if term_expired || ltv_scaled >= mul_exact(collateral_value, spec.loan_config.liquidation_ltv_bps as i64) {
            let base_spec = match base_spec {
                Some(b) => b,
                None => return,
            };

            let sell_size_lots = LoanService::collateral_amount_to_lots(loan.collateral_amount, spec, base_spec);
            if sell_size_lots <= 0 {
                return;
            }

            let order_id = LoanService::force_sell_order_id(ORDERID_SUBTYPE_ISOLATED, loan.uid, loan.loan_id, ts);

            let limit_price = ceil_mul_div(mark_price, real_debt, collateral_value);
            self.command_submitter.submit(OrderCommand {
                command: OrderCommandType::LoanForceLiquidate,
                order_id,
                uid: loan.uid,
                symbol: spec.symbol_id,
                price: limit_price,
                size: sell_size_lots,
                reserve_bid_price: loan.loan_id,
                action: Some(OrderAction::Ask),
                order_type: Some(OrderType::Ioc),
                timestamp: ts,
                ..Default::default()
            });
        } else if spec.loan_config.margin_call_ltv_bps > 0
            && ltv_scaled >= mul_exact(collateral_value, spec.loan_config.margin_call_ltv_bps as i64)
        {
            fund_events.push(FundEvent {
                event_type: FundEventType::LoanMarginCall,
                order_id: loan.loan_id,
                uid: loan.uid,
                currency: loan.loan_currency,
                currency_scale_k: loan_currency_spec.map(|s| s.currency_scale_k).unwrap_or(0),
                loan_mode: 0,
                loan_ltv_bps: if collateral_value == 0 { 0 } else { ltv_scaled / collateral_value },
                loan_threshold_bps: spec.loan_config.margin_call_ltv_bps as i64,
                ..Default::default()
            });
        }
    }

    fn check_cross(
        &mut self,
        up: &UserProfile,
        ts: i64,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        loan_service: &LoanService,
        fund_events: &mut Vec<FundEvent>,
    ) {
        if up.cross_loans.is_empty() {
            return;
        }
        let ltv_bps = loan_service.calculate_cross_account_ltv_bps(up, ts, ssp, last_price_cache, false);
        if ltv_bps < loan_service.global_config.cross_liquidation_ltv_bps as i64 {
            if ltv_bps >= loan_service.global_config.cross_margin_call_ltv_bps as i64 {

                fund_events.push(FundEvent {
                    event_type: FundEventType::LoanMarginCall,
                    uid: up.uid,
                    loan_mode: 1,
                    loan_ltv_bps: ltv_bps,
                    loan_threshold_bps: loan_service.global_config.cross_margin_call_ltv_bps as i64,
                    ..Default::default()
                });
            }
            return;
        }
        let selling_currency = match self.pick_cross_collateral_to_sell(up, ssp, last_price_cache) {
            Some(c) => c,
            None => return,
        };
        let target_loan = match self.pick_cross_loan_to_repay(up, selling_currency, ssp, last_price_cache) {
            Some(l) => l,
            None => return,
        };

        let spec = ssp.find_spot_symbol(selling_currency, target_loan.loan_currency).expect("pick guarantees the spot pair exists");
        let mark_price = last_price_cache.get(&spec.symbol_id).expect("pick guarantees markPrice is ready").mark_price;
        let available_collateral = up.cross_loan_collateral(selling_currency);
        let selling_currency_spec = match ssp.get_currency(selling_currency) {
            Some(s) => s,
            None => return,
        };
        let loan_currency_spec = match ssp.get_currency(target_loan.loan_currency) {
            Some(s) => s,
            None => return,
        };

        let raw_ltv_bps = loan_service.calculate_cross_raw_ltv_bps(up, ts, ssp, last_price_cache);
        let pricing_ltv_bps = if raw_ltv_bps > 0 { raw_ltv_bps } else { ltv_bps };
        let limit_price = ceil_mul_div(mark_price, pricing_ltv_bps, BPS_SCALE);
        let sell_size = Self::calculate_cross_sell_size(
            &target_loan,
            spec,
            limit_price,
            available_collateral,
            ts,
            loan_service,
            selling_currency_spec,
            loan_currency_spec,
        );
        if sell_size <= 0 {
            return;
        }
        let order_id = LoanService::force_sell_order_id(ORDERID_SUBTYPE_CROSS, up.uid, target_loan.loan_id, ts);
        self.command_submitter.submit(OrderCommand {
            command: OrderCommandType::LoanCrossForceLiquidate,
            order_id,
            uid: up.uid,
            symbol: spec.symbol_id,
            price: limit_price,
            size: sell_size,
            reserve_bid_price: target_loan.loan_id,
            action: Some(OrderAction::Ask),
            order_type: Some(OrderType::Ioc),
            timestamp: ts,
            ..Default::default()
        });
    }

    pub fn on_isolated_loan_opened(&mut self, uid: i64, symbol_id: i32) {
        self.isolated_loan_symbol_to_users.entry(symbol_id).or_default().insert(uid);
    }

    pub fn on_isolated_loan_closed(&mut self, up: &UserProfile, symbol_id: i32) {
        let holds_other = up.isolated_loans.values().any(|l| !l.is_empty() && l.symbol_id == symbol_id);
        if holds_other {
            return;
        }
        if let Some(s) = self.isolated_loan_symbol_to_users.get_mut(&symbol_id) {
            s.remove(&up.uid);
            if s.is_empty() {
                self.isolated_loan_symbol_to_users.remove(&symbol_id);
            }
        }
    }

    pub fn sync_cross_exposure(&mut self, up: &UserProfile) {
        for (&currency, &amount) in up.cross_loan_collateral.iter() {
            if amount > 0 {
                self.cross_loan_currency_to_users.entry(currency).or_default().insert(up.uid);
            }
        }
        for loan in up.cross_loans.values() {
            if !loan.is_empty() {
                self.cross_loan_currency_to_users.entry(loan.loan_currency).or_default().insert(up.uid);
            }
        }
        let has_loan = up.cross_loans.values().any(|l| !l.is_empty());
        let has_collateral = up.cross_loan_collateral.values().any(|&a| a > 0);
        if !has_loan && !has_collateral {
            let currencies: Vec<i32> = self.cross_loan_currency_to_users.keys().copied().collect();
            for currency in currencies {
                if let Some(s) = self.cross_loan_currency_to_users.get_mut(&currency) {
                    s.remove(&up.uid);
                    if s.is_empty() {
                        self.cross_loan_currency_to_users.remove(&currency);
                    }
                }
            }
        }
    }

    fn pick_cross_collateral_to_sell(
        &self,
        up: &UserProfile,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
    ) -> Option<i32> {
        let mut best_currency: Option<i32> = None;
        let mut best_weight: i32 = -1;
        let mut best_amount: i64 = -1;
        for (&currency, &amount) in up.cross_loan_collateral.iter() {
            if amount <= 0 {
                continue;
            }
            let weight = LoanService::collateral_weight_for_base(currency, ssp);
            if weight <= 0 {
                continue;
            }

            let can_repay_some = up
                .cross_loans
                .values()
                .any(|l| l.outstanding_principal > 0 && Self::has_ready_spot_market(currency, l.loan_currency, ssp, last_price_cache));
            if !can_repay_some {
                continue;
            }
            let better = weight > best_weight
                || (weight == best_weight && amount > best_amount)
                || (weight == best_weight && amount == best_amount && best_currency.is_some_and(|bc| currency < bc));
            if best_currency.is_none() || better {
                best_currency = Some(currency);
                best_weight = weight;
                best_amount = amount;
            }
        }
        best_currency
    }

    fn pick_cross_loan_to_repay(
        &self,
        up: &UserProfile,
        selling_currency: i32,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
    ) -> Option<CrossLoanRecord> {
        let mut best: Option<&CrossLoanRecord> = None;
        for loan in up.cross_loans.values() {
            if loan.outstanding_principal <= 0 {
                continue;
            }
            if !Self::has_ready_spot_market(selling_currency, loan.loan_currency, ssp, last_price_cache) {
                continue;
            }
            let better = match best {
                None => true,
                Some(b) => {
                    loan.rate_bps > b.rate_bps
                        || (loan.rate_bps == b.rate_bps && loan.outstanding_principal > b.outstanding_principal)
                        || (loan.rate_bps == b.rate_bps
                            && loan.outstanding_principal == b.outstanding_principal
                            && loan.loan_id < b.loan_id)
                }
            };
            if better {
                best = Some(loan);
            }
        }
        best.cloned()
    }

    fn has_ready_spot_market(
        selling_currency: i32,
        loan_currency: i32,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
    ) -> bool {
        match ssp.find_spot_symbol(selling_currency, loan_currency) {
            Some(spec) => matches!(last_price_cache.get(&spec.symbol_id), Some(r) if r.mark_price > 0),
            None => false,
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn calculate_cross_sell_size(
        target_loan: &CrossLoanRecord,
        spec: &crate::core::common::core_symbol_specification::CoreSymbolSpecification,
        limit_price: i64,
        available: i64,
        now: i64,
        loan_service: &LoanService,
        selling_currency_spec: &crate::core::common::core_currency_specification::CoreCurrencySpecification,
        loan_currency_spec: &crate::core::common::core_currency_specification::CoreCurrencySpecification,
    ) -> i64 {
        let real_debt = add_exact(target_loan.outstanding_principal, loan_service.calculate_display_interest(target_loan, now));
        if real_debt <= 0 || limit_price <= 0 {
            return 0;
        }
        let needed_lots = LoanService::quote_amount_to_lots(real_debt, limit_price, spec, loan_currency_spec);
        let available_lots = LoanService::collateral_amount_to_lots(available, spec, selling_currency_spec);
        available_lots.min(needed_lots)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::symbol_loan_specification::SymbolLoanSpecification;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::common::user_status::UserStatus;
    use std::cell::RefCell;
    use std::rc::Rc;

    const COLL: i32 = 1;
    const LOANC: i32 = 2;
    const SYMBOL: i32 = 100;
    const UID: i64 = 7;

    fn attach_collector(e: &mut LoanLiquidationEngine) -> Rc<RefCell<Vec<OrderCommand>>> {
        use crate::core::processors::liquidation::command_submitter::VecCommandSink;
        let collected = Rc::new(RefCell::new(Vec::new()));
        e.set_command_submitter(Rc::new(RefCell::new(VecCommandSink(collected.clone()))));
        collected
    }

    fn spot_spec(liquidation_ltv_bps: i32, margin_call_ltv_bps: i32, max_term_days: i32) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: COLL,
            quote_currency: LOANC,
            base_scale_k: 1,
            quote_scale_k: 1,
            loan_config: SymbolLoanSpecification {
                initial_ltv_bps: 5000,
                liquidation_ltv_bps,
                margin_call_ltv_bps,
                max_amount: 0,
                max_term_days,
            },
            ..Default::default()
        }
    }

    fn seeded_ssp() -> SymbolSpecificationProvider {
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_currency(CoreCurrencySpecification { currency: COLL, currency_scale_k: 1, collateral_weight_bps: 8000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: LOANC, currency_scale_k: 1, ..Default::default() });
        ssp.add_symbol(spot_spec(8000, 7000, 0));
        ssp
    }

    fn price_cache() -> BTreeMap<i32, LastPriceCacheRecord> {
        let mut m = BTreeMap::new();
        m.insert(SYMBOL, LastPriceCacheRecord::with_mark(1));
        m
    }

    fn profile(uid: i64) -> UserProfile {
        UserProfile::new(uid, UserStatus::Active)
    }

    fn iso_loan(loan_id: i64, collateral: i64, principal: i64) -> IsolatedLoanRecord {
        let mut l = IsolatedLoanRecord::new(UID, loan_id, SYMBOL, COLL, LOANC, 0, 0);
        l.collateral_amount = collateral;
        l.outstanding_principal = principal;
        l
    }

    #[test]
    fn on_isolated_loan_opened_and_closed_multi_loan_safe() {
        let mut e = LoanLiquidationEngine::new();
        e.on_isolated_loan_opened(UID, SYMBOL);
        assert!(e.isolated_loan_symbol_to_users.get(&SYMBOL).unwrap().contains(&UID));

        let mut up = profile(UID);
        up.isolated_loans.insert(1, iso_loan(1, 1000, 500));
        up.isolated_loans.insert(2, iso_loan(2, 0, 0));
        e.on_isolated_loan_closed(&up, SYMBOL);
        assert!(
            e.isolated_loan_symbol_to_users.get(&SYMBOL).unwrap().contains(&UID),
            "another non-empty loan on the same symbol still exists -> must not be removed"
        );

        up.isolated_loans.insert(1, iso_loan(1, 0, 0));
        e.on_isolated_loan_closed(&up, SYMBOL);
        assert!(e.isolated_loan_symbol_to_users.get(&SYMBOL).is_none(), "no non-empty loan left -> uid removed + empty bucket cleaned up");
    }

    #[test]
    fn sync_cross_exposure_registers_and_full_exit_sweeps() {
        let mut e = LoanLiquidationEngine::new();
        let mut up = profile(UID);
        up.add_to_cross_loan_collateral(COLL, 1000);
        let mut loan = CrossLoanRecord::new(UID, 1, SYMBOL, LOANC, 0, 0);
        loan.outstanding_principal = 500;
        up.cross_loans.insert(1, loan);
        e.sync_cross_exposure(&up);
        assert!(e.cross_loan_currency_to_users.get(&COLL).unwrap().contains(&UID), "collateral currency is registered");
        assert!(e.cross_loan_currency_to_users.get(&LOANC).unwrap().contains(&UID), "borrowed currency is registered");

        let mut empty = profile(UID);
        empty.cross_loans.insert(1, CrossLoanRecord::new(UID, 1, SYMBOL, LOANC, 0, 0));
        e.sync_cross_exposure(&empty);
        assert!(e.cross_loan_currency_to_users.is_empty(), "full exit -> every currency bucket precisely removed");
    }

    fn run_check_loans(e: &mut LoanLiquidationEngine, up: UserProfile, ssp: &SymbolSpecificationProvider) -> Vec<OrderCommand> {
        let out = attach_collector(e);
        let mut ups = UserProfileService::new();
        ups.users.insert(UID, up);
        let ls = LoanService::new();
        let cmd = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: SYMBOL, timestamp: 5_000, ..Default::default() };
        e.check_loans(&cmd, &ups, ssp, &price_cache(), &ls, &mut Vec::new());
        let collected = out.borrow().clone();
        collected
    }

    #[test]
    fn check_isolated_triggers_force_liquidate_when_ltv_breaches() {
        let ssp = seeded_ssp();
        let mut e = LoanLiquidationEngine::new();
        e.on_isolated_loan_opened(UID, SYMBOL);
        let mut up = profile(UID);
        up.isolated_loans.insert(1, iso_loan(1, 1000, 900));
        let cmds = run_check_loans(&mut e, up, &ssp);

        assert_eq!(cmds.len(), 1, "LTV breach -> FORCE command submitted");
        let c = &cmds[0];
        assert_eq!(c.command, OrderCommandType::LoanForceLiquidate);
        assert_eq!(c.uid, UID);
        assert_eq!(c.symbol, SYMBOL);
        assert_eq!(c.action, Some(OrderAction::Ask));
        assert_eq!(c.order_type, Some(OrderType::Ioc));
        assert_eq!(c.reserve_bid_price, 1, "loan_id is carried in reserve_bid_price");
        assert_eq!(c.size, 1000, "sellSizeLots = collateral_amount_to_lots(1000) = 1000");
        assert_eq!(c.price, 1, "bankruptcy price = ceil_mul_div(mark=1, debt=900, coll_value=1000) = 1");
    }

    #[test]
    fn check_isolated_healthy_loan_no_command() {
        let ssp = seeded_ssp();
        let mut e = LoanLiquidationEngine::new();
        e.on_isolated_loan_opened(UID, SYMBOL);
        let mut up = profile(UID);
        up.isolated_loans.insert(1, iso_loan(1, 1000, 500));
        let cmds = run_check_loans(&mut e, up, &ssp);
        assert!(cmds.is_empty(), "a healthy loan does not trigger");
    }

    #[test]
    fn check_isolated_zero_collateral_value_skips_no_divzero() {
        let ssp = seeded_ssp();
        let mut e = LoanLiquidationEngine::new();
        e.on_isolated_loan_opened(UID, SYMBOL);
        let mut up = profile(UID);
        up.isolated_loans.insert(1, iso_loan(1, 0, 900));
        let cmds = run_check_loans(&mut e, up, &ssp);
        assert!(cmds.is_empty(), "collateral value<=0 -> skip, no division by zero, no command submitted");
    }

    #[test]
    fn check_isolated_term_expired_triggers_even_when_ltv_healthy() {
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_currency(CoreCurrencySpecification { currency: COLL, currency_scale_k: 1, collateral_weight_bps: 8000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: LOANC, currency_scale_k: 1, ..Default::default() });
        ssp.add_symbol(spot_spec(8000, 7000, 1));

        let mut e = LoanLiquidationEngine::new();
        let out = attach_collector(&mut e);
        e.on_isolated_loan_opened(UID, SYMBOL);
        let mut up = profile(UID);
        let mut loan = iso_loan(1, 1000, 500);
        loan.rate_mode = LoanRateMode::Locked;
        loan.opened_at_ts = 0;
        up.isolated_loans.insert(1, loan);

        let mut ups = UserProfileService::new();
        ups.users.insert(UID, up);
        let ls = LoanService::new();
        let cmd = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: SYMBOL, timestamp: 2 * MS_PER_DAY, ..Default::default() };
        e.check_loans(&cmd, &ups, &ssp, &price_cache(), &ls, &mut Vec::new());

        assert_eq!(out.borrow().len(), 1, "LOCKED loan past term -> liquidated regardless of LTV");
    }

    #[test]
    fn pick_cross_collateral_prefers_higher_weight_then_amount_then_lower_currency() {
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_currency(CoreCurrencySpecification { currency: 10, currency_scale_k: 1, collateral_weight_bps: 5000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: 11, currency_scale_k: 1, collateral_weight_bps: 9000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: 12, currency_scale_k: 1, collateral_weight_bps: 9000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: LOANC, currency_scale_k: 1, ..Default::default() });
        for c in [10, 11, 12] {
            let mut s = spot_spec(8000, 7000, 0);
            s.symbol_id = 1000 + c;
            s.base_currency = c;
            s.quote_currency = LOANC;
            ssp.add_symbol(s);
        }
        let mut pc = BTreeMap::new();
        for c in [10, 11, 12] {
            pc.insert(1000 + c, LastPriceCacheRecord::with_mark(1));
        }

        let e = LoanLiquidationEngine::new();
        let mut up = profile(UID);
        up.add_to_cross_loan_collateral(10, 1000);
        up.add_to_cross_loan_collateral(11, 500);
        up.add_to_cross_loan_collateral(12, 500);
        up.cross_loans.insert(1, {
            let mut l = CrossLoanRecord::new(UID, 1, 1010, LOANC, 0, 0);
            l.outstanding_principal = 100;
            l
        });

        assert_eq!(e.pick_cross_collateral_to_sell(&up, &ssp, &pc), Some(11));
    }

    #[test]
    fn pick_cross_collateral_skips_currency_with_no_ready_market() {
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_currency(CoreCurrencySpecification { currency: 10, currency_scale_k: 1, collateral_weight_bps: 5000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: 11, currency_scale_k: 1, collateral_weight_bps: 9000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: LOANC, currency_scale_k: 1, ..Default::default() });
        let mut s = spot_spec(8000, 7000, 0);
        s.symbol_id = 1010;
        s.base_currency = 10;
        s.quote_currency = LOANC;
        ssp.add_symbol(s);
        let mut pc = BTreeMap::new();
        pc.insert(1010, LastPriceCacheRecord::with_mark(1));

        let e = LoanLiquidationEngine::new();
        let mut up = profile(UID);
        up.add_to_cross_loan_collateral(10, 1000);
        up.add_to_cross_loan_collateral(11, 5000);
        up.cross_loans.insert(1, {
            let mut l = CrossLoanRecord::new(UID, 1, 1010, LOANC, 0, 0);
            l.outstanding_principal = 100;
            l
        });

        assert_eq!(
            e.pick_cross_collateral_to_sell(&up, &ssp, &pc),
            Some(10),
            "a higher-weight currency with no ready spot market is skipped, falling back to the next-best one that has a market"
        );
    }

    #[test]
    fn pick_cross_loan_prefers_higher_rate_then_principal_then_lower_id() {
        let ssp = {
            let mut s = SymbolSpecificationProvider::new();
            s.add_currency(CoreCurrencySpecification { currency: COLL, currency_scale_k: 1, collateral_weight_bps: 8000, ..Default::default() });
            s.add_currency(CoreCurrencySpecification { currency: LOANC, currency_scale_k: 1, ..Default::default() });
            s.add_symbol(spot_spec(8000, 7000, 0));
            s
        };
        let e = LoanLiquidationEngine::new();
        let mut up = profile(UID);
        let mut l5 = CrossLoanRecord::new(UID, 5, SYMBOL, LOANC, 300, 0);
        l5.outstanding_principal = 200;
        let mut l3 = CrossLoanRecord::new(UID, 3, SYMBOL, LOANC, 300, 0);
        l3.outstanding_principal = 200;
        up.cross_loans.insert(5, l5);
        up.cross_loans.insert(3, l3);
        let pick = e.pick_cross_loan_to_repay(&up, COLL, &ssp, &price_cache()).expect("a repayable loan must exist");
        assert_eq!(pick.loan_id, 3, "same rate, same principal -> tie-broken by loanId ASC");
    }

    #[test]
    fn check_loans_targeted_unions_isolated_and_cross_currency_indices() {
        let ssp = seeded_ssp();
        let mut e = LoanLiquidationEngine::new();
        let out = attach_collector(&mut e);
        const UID_B: i64 = 8;
        e.on_isolated_loan_opened(UID, SYMBOL);
        let mut up_a = profile(UID);
        up_a.isolated_loans.insert(1, iso_loan(1, 1000, 900));

        let mut up_b = profile(UID_B);
        up_b.add_to_cross_loan_collateral(COLL, 1000);
        e.sync_cross_exposure(&up_b);

        let mut ups = UserProfileService::new();
        ups.users.insert(UID, up_a);
        ups.users.insert(UID_B, up_b);
        let ls = LoanService::new();
        let cmd = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: SYMBOL, timestamp: 5_000, ..Default::default() };
        e.check_loans(&cmd, &ups, &ssp, &price_cache(), &ls, &mut Vec::new());

        assert_eq!(out.borrow().len(), 1);
        assert_eq!(out.borrow()[0].uid, UID, "user A, present in the union, is detected and liquidated");
    }
}
