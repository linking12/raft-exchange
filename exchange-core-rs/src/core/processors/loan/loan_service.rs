use std::collections::BTreeMap;

use crate::core::common::last_price_cache_record::LastPriceCacheRecord;
use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::isolated_loan_record::LoanRateMode;
use crate::core::common::loan_record::LoanRecord;
use crate::core::common::user_profile::UserProfile;
use crate::core::processors::loan::loan_global_config::LoanGlobalConfig;
use crate::core::processors::loan::rate::fixed_rate_model::FixedRateModel;
use crate::core::processors::loan::rate::floating_rate_model::FloatingRateModel;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::utils::core_arithmetic_utils as arithmetic;
use crate::core::utils::core_arithmetic_utils::{add_exact, mul_exact};

pub const YEAR_MS: i64 = 365 * 24 * 3600 * 1_000;

pub const BPS_SCALE: i64 = 10_000;

pub const ORDERID_NAMESPACE_TAG: i64 = 0x4C;

pub const ORDERID_SUBTYPE_ISOLATED: i64 = 0x53;

pub const ORDERID_SUBTYPE_CROSS: i64 = 0x43;

const ORDERID_UID_MASK: i64 = 0xF_FFFF;

const ORDERID_LOANID_MASK: i64 = 0xFFFF;

const ORDERID_TS_MASK: i64 = 0xFFF;

fn checked_add_i64(a: i64, b: i64) -> Option<i64> {
    i64::try_from(a as i128 + b as i128).ok()
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct LoanService {
    pub loan_pool_available: BTreeMap<i32, i64>,
    pub loan_pool_borrowed: BTreeMap<i32, i64>,
    pub interest_revenue: BTreeMap<i32, i64>,
    pub loan_insurance_fund: BTreeMap<i32, i64>,
    pub global_config: LoanGlobalConfig,
    pub floating_rate: FloatingRateModel,
    pub fixed_rate: FixedRateModel,
}

impl LoanService {
    pub fn new() -> Self {
        LoanService::default()
    }

    pub fn accrue_to<L: LoanRecord>(&self, loan: &mut L, now: i64) -> i64 {
        if loan.is_fixed_rate() {
            self.fixed_rate.accrue(loan, now)
        } else {
            self.floating_rate.accrue(loan, now)
        }
    }

    pub fn calculate_display_interest<L: LoanRecord>(&self, loan: &L, now: i64) -> i64 {
        if loan.is_fixed_rate() {
            self.fixed_rate.display_interest(loan, now)
        } else {
            self.floating_rate.display_interest(loan, now)
        }
    }

    pub fn open_rate_bps(&self, rate_mode: LoanRateMode, loan_currency: i32) -> i32 {
        match rate_mode {
            LoanRateMode::Floating => self.floating_rate.open_rate_bps(loan_currency),
            LoanRateMode::Locked => self.fixed_rate.open_rate_bps(&self.floating_rate, loan_currency),
        }
    }

    pub fn verify_pool_capacity(&self, loan_currency: i32, principal: i64) -> CommandResultCode {
        let available = self.get_loan_pool_available(loan_currency);
        let borrowed = self.get_loan_pool_borrowed(loan_currency);
        if available < principal {
            return CommandResultCode::LoanPoolInsufficient;
        }
        let new_borrowed = add_exact(borrowed, principal);
        let total_pool = add_exact(available, borrowed);
        if total_pool > 0 {
            let new_utilization_scaled = mul_exact(new_borrowed, BPS_SCALE);
            let utilization_cap_scaled =
                mul_exact(total_pool, self.global_config.loan_pool_utilization_cap_bps as i64);
            if new_utilization_scaled > utilization_cap_scaled {
                return CommandResultCode::LoanPoolUtilizationExceeded;
            }
        }
        CommandResultCode::Success
    }

    pub fn disburse_loan(&mut self, up: &mut UserProfile, loan_currency: i32, principal: i64) {
        up.add_to_account(loan_currency, principal);
        self.add_to_loan_pool_available(loan_currency, -principal);
        self.add_to_loan_pool_borrowed(loan_currency, principal);
    }

    pub fn apply_debt_payment<L: LoanRecord>(
        &mut self,
        loan: &mut L,
        account: &mut BTreeMap<i32, i64>,
        fund: i64,
    ) -> i64 {
        let currency = loan.loan_currency();
        let interest_part = fund.min(loan.accumulated_interest());
        let fund_after_interest = fund - interest_part;
        let principal_part = fund_after_interest.min(loan.outstanding_principal());
        let paid = add_exact(interest_part, principal_part);
        *account.entry(currency).or_insert(0) -= paid;
        loan.set_accumulated_interest(loan.accumulated_interest() - interest_part);
        loan.set_outstanding_principal(loan.outstanding_principal() - principal_part);

        loan.set_cum_interest_paid(add_exact(loan.cum_interest_paid(), interest_part));
        self.add_to_interest_revenue(currency, interest_part);
        self.add_to_loan_pool_available(currency, principal_part);
        self.add_to_loan_pool_borrowed(currency, -principal_part);
        interest_part
    }

    pub fn collateral_value_in_quote_currency(
        amount: i64,
        spec: &CoreSymbolSpecification,
        mark_price: i64,
        base_currency_spec: Option<&CoreCurrencySpecification>,
        quote_currency_spec: Option<&CoreCurrencySpecification>,
    ) -> i64 {
        let (base_spec, quote_spec) = match (base_currency_spec, quote_currency_spec) {
            (Some(b), Some(q)) => (b, q),
            _ => return -1,
        };
        let base_amount = arithmetic::convert_scale(amount, base_spec.currency_scale_k, spec.base_scale_k);
        let notional = mul_exact(base_amount, mark_price);
        arithmetic::size_price_to_currency_scale(
            notional,
            spec.base_scale_k,
            spec.quote_scale_k,
            quote_spec.currency_scale_k,
        )
    }

    pub fn value_in_numeraire(
        currency: i32,
        amount: i64,
        numeraire_currency: i32,
        numeraire_spec: &CoreCurrencySpecification,
        ssp: &SymbolSpecificationProvider,
        price_cache: &std::collections::BTreeMap<i32, LastPriceCacheRecord>,
    ) -> i64 {
        if currency == numeraire_currency {
            return amount;
        }
        let spec = match ssp.find_spot_symbol(currency, numeraire_currency) {
            Some(s) => s,
            None => return -1,
        };
        let mark_price = match price_cache.get(&spec.symbol_id) {
            Some(r) if r.mark_price > 0 => r.mark_price,
            _ => return -1,
        };
        let currency_spec = ssp.get_currency(currency);
        Self::collateral_value_in_quote_currency(amount, spec, mark_price, currency_spec, Some(numeraire_spec))
    }

    pub fn calculate_cross_account_ltv_bps(
        &self,
        up: &UserProfile,
        now: i64,
        ssp: &SymbolSpecificationProvider,
        price_cache: &std::collections::BTreeMap<i32, LastPriceCacheRecord>,
        fail_closed_on_missing_price: bool,
    ) -> i64 {
        self.cross_ltv_bps(up, now, ssp, price_cache, fail_closed_on_missing_price, true)
    }

    pub fn calculate_cross_raw_ltv_bps(
        &self,
        up: &UserProfile,
        now: i64,
        ssp: &SymbolSpecificationProvider,
        price_cache: &std::collections::BTreeMap<i32, LastPriceCacheRecord>,
    ) -> i64 {
        self.cross_ltv_bps(up, now, ssp, price_cache, false, false)
    }

    pub fn lots_to_collateral_amount(
        lots: i64,
        spec: &CoreSymbolSpecification,
        base_spec: &CoreCurrencySpecification,
    ) -> i64 {
        arithmetic::symbol_to_currency_scale(lots, spec.base_scale_k, base_spec.currency_scale_k)
    }

    pub fn collateral_amount_to_lots(
        amount: i64,
        spec: &CoreSymbolSpecification,
        base_spec: &CoreCurrencySpecification,
    ) -> i64 {
        arithmetic::convert_scale(amount, base_spec.currency_scale_k, spec.base_scale_k)
    }

    pub fn quote_amount_to_lots(
        quote_amount: i64,
        mark_price: i64,
        spec: &CoreSymbolSpecification,
        quote_spec: &CoreCurrencySpecification,
    ) -> i64 {
        let notional = arithmetic::currency_to_size_price_scale(
            quote_amount,
            spec.base_scale_k,
            spec.quote_scale_k,
            quote_spec.currency_scale_k,
        );
        arithmetic::ceil_divide(notional, mark_price)
    }

    pub fn settle_liquidation_proceeds<L: LoanRecord>(
        &mut self,
        loan: &mut L,
        account: &mut BTreeMap<i32, i64>,
        received_quote: i64,
        now: i64,
    ) -> i64 {
        let fee_by_rate = arithmetic::ceil_mul_div(received_quote, self.global_config.loan_liquidation_fee_bps as i64, BPS_SCALE);
        let liq_fee = received_quote.min(fee_by_rate);
        let currency = loan.loan_currency();
        *account.entry(currency).or_insert(0) -= liq_fee;
        self.add_to_loan_insurance_fund(currency, liq_fee);
        self.accrue_to(loan, now);
        self.apply_debt_payment(loan, account, received_quote - liq_fee)
    }

    pub fn take_over_cross_loan(
        &mut self,
        up: &mut UserProfile,
        target_loan_id: i64,
        now: i64,
        ssp: &SymbolSpecificationProvider,
        price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
    ) -> bool {
        let numeraire_currency = self.global_config.numeraire_currency;
        if numeraire_currency == 0 {
            return false;
        }
        let numeraire_spec = match ssp.get_currency(numeraire_currency) {
            Some(s) => s,
            None => return false,
        };

        let (target_loan_currency, target_outstanding_principal) = match up.cross_loans.get(&target_loan_id) {
            Some(l) => (l.loan_currency, l.outstanding_principal),
            None => return false,
        };
        let target_debt = {
            let loan = up.cross_loans.get(&target_loan_id).expect("checked above");
            add_exact(loan.outstanding_principal, self.calculate_display_interest(loan, now))
        };
        let target_debt_in_num =
            Self::value_in_numeraire(target_loan_currency, target_debt, numeraire_currency, numeraire_spec, ssp, price_cache);
        if target_debt_in_num < 0 {
            return false;
        }

        let mut total_debt_in_num: i64 = 0;
        for loan in up.cross_loans.values() {
            let debt = add_exact(loan.outstanding_principal, self.calculate_display_interest(loan, now));
            if debt <= 0 {
                continue;
            }
            let v = Self::value_in_numeraire(loan.loan_currency, debt, numeraire_currency, numeraire_spec, ssp, price_cache);
            if v < 0 {
                return false;
            }
            total_debt_in_num = add_exact(total_debt_in_num, v);
        }
        if total_debt_in_num <= 0 {
            return false;
        }

        let mut ordered: Vec<i32> = up.cross_loan_collateral.keys().copied().collect();
        ordered.sort_by(|&a, &b| {
            let wa = Self::collateral_weight_for_base(a, ssp);
            let wb = Self::collateral_weight_for_base(b, ssp);
            wb.cmp(&wa).then(a.cmp(&b))
        });

        let mut total_collateral_in_num: i64 = 0;
        for &currency in &ordered {
            let amount = *up.cross_loan_collateral.get(&currency).unwrap_or(&0);

            if amount <= 0 || Self::collateral_weight_for_base(currency, ssp) <= 0 {
                continue;
            }
            let v = Self::value_in_numeraire(currency, amount, numeraire_currency, numeraire_spec, ssp, price_cache);
            if v < 0 {
                return false;
            }
            total_collateral_in_num = add_exact(total_collateral_in_num, v);
        }

        let mut remaining_to_take =
            arithmetic::trunc_mul_div(total_collateral_in_num, target_debt_in_num, total_debt_in_num);
        for &currency in &ordered {
            if remaining_to_take <= 0 {
                break;
            }
            let amount = *up.cross_loan_collateral.get(&currency).unwrap_or(&0);
            if amount <= 0 {
                continue;
            }
            let value_in_num = Self::value_in_numeraire(currency, amount, numeraire_currency, numeraire_spec, ssp, price_cache);
            if value_in_num <= 0 {
                continue;
            }
            let take = if value_in_num <= remaining_to_take {
                amount
            } else {
                arithmetic::trunc_mul_div(amount, remaining_to_take, value_in_num)
            };
            if take <= 0 {
                continue;
            }
            up.add_to_cross_loan_collateral(currency, -take);
            up.add_to_account(currency, -take);
            self.add_to_loan_insurance_fund(currency, take);
            remaining_to_take -= value_in_num.min(remaining_to_take);
        }

        self.add_to_loan_insurance_fund(target_loan_currency, -target_debt);
        self.add_to_loan_pool_available(target_loan_currency, target_outstanding_principal);
        self.add_to_loan_pool_borrowed(target_loan_currency, -target_outstanding_principal);
        self.add_to_interest_revenue(target_loan_currency, add_exact(target_debt, -target_outstanding_principal));
        true
    }

    pub fn get_loan_pool_available(&self, currency: i32) -> i64 {
        *self.loan_pool_available.get(&currency).unwrap_or(&0)
    }

    pub fn add_to_loan_pool_available(&mut self, currency: i32, delta: i64) {
        *self.loan_pool_available.entry(currency).or_insert(0) += delta;
    }

    pub fn get_loan_pool_borrowed(&self, currency: i32) -> i64 {
        *self.loan_pool_borrowed.get(&currency).unwrap_or(&0)
    }

    pub fn add_to_loan_pool_borrowed(&mut self, currency: i32, delta: i64) {
        *self.loan_pool_borrowed.entry(currency).or_insert(0) += delta;
    }

    pub fn get_interest_revenue(&self, currency: i32) -> i64 {
        *self.interest_revenue.get(&currency).unwrap_or(&0)
    }

    pub fn add_to_interest_revenue(&mut self, currency: i32, delta: i64) {
        *self.interest_revenue.entry(currency).or_insert(0) += delta;
    }

    pub fn get_loan_insurance_fund(&self, currency: i32) -> i64 {
        *self.loan_insurance_fund.get(&currency).unwrap_or(&0)
    }

    pub fn add_to_loan_insurance_fund(&mut self, currency: i32, delta: i64) {
        *self.loan_insurance_fund.entry(currency).or_insert(0) += delta;
    }

    pub fn collateral_weight_for_base(currency: i32, ssp: &SymbolSpecificationProvider) -> i32 {
        ssp.get_currency(currency).map(|s| s.collateral_weight_bps).unwrap_or(0)
    }

    pub fn force_sell_order_id(subtype: i64, uid: i64, loan_id: i64, tick_time_ms: i64) -> i64 {
        let uid_hash = (uid.wrapping_mul(31).wrapping_add(17)) & ORDERID_UID_MASK;
        let loan_id_hash = (loan_id.wrapping_mul(31).wrapping_add(17)) & ORDERID_LOANID_MASK;
        let ts_sec = (tick_time_ms / 1000) & ORDERID_TS_MASK;
        (ORDERID_NAMESPACE_TAG << 56) | (subtype << 48) | (uid_hash << 28) | (loan_id_hash << 12) | ts_sec
    }

    pub fn is_structurally_sellable(
        currency: i32,
        amount: i64,
        up: &UserProfile,
        ssp: &SymbolSpecificationProvider,
    ) -> bool {
        if amount <= 0 {
            return false;
        }
        let currency_spec = match ssp.get_currency(currency) {
            Some(s) if s.collateral_weight_bps > 0 => s,
            _ => return false,
        };

        for loan in up.cross_loans.values() {
            if loan.outstanding_principal <= 0 {
                continue;
            }
            if let Some(spec) = ssp.find_spot_symbol(currency, loan.loan_currency) {
                if Self::collateral_amount_to_lots(amount, spec, currency_spec) > 0 {
                    return true;
                }
            }
        }
        false
    }

    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        for (&cur, &amt) in &self.loan_pool_available {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(amt);
        }
        for (&cur, &amt) in &self.loan_pool_borrowed {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(amt);
        }
        for (&cur, &amt) in &self.interest_revenue {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(amt);
        }
        for (&cur, &amt) in &self.loan_insurance_fund {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(amt);
        }
        h = h.wrapping_mul(31).wrapping_add(self.global_config.state_hash() as i64);
        h = h.wrapping_mul(31).wrapping_add(self.floating_rate.state_hash() as i64);
        h = h.wrapping_mul(31).wrapping_add(self.fixed_rate.state_hash() as i64);
        ((h >> 32) as i32) ^ (h as i32)
    }

    fn cross_ltv_bps(
        &self,
        up: &UserProfile,
        now: i64,
        ssp: &SymbolSpecificationProvider,
        price_cache: &std::collections::BTreeMap<i32, LastPriceCacheRecord>,
        fail_closed_on_missing_price: bool,
        apply_weight: bool,
    ) -> i64 {
        let numeraire_currency = self.global_config.numeraire_currency;
        if up.cross_loans.is_empty() || numeraire_currency == 0 {
            return 0;
        }
        let unevaluable = if fail_closed_on_missing_price { i64::MAX } else { 0 };
        let numeraire_spec = match ssp.get_currency(numeraire_currency) {
            Some(s) => s,
            None => return unevaluable,
        };

        let mut total_debt: i64 = 0;
        for loan in up.cross_loans.values() {
            if loan.outstanding_principal <= 0 {
                continue;
            }
            let display_interest = self.calculate_display_interest(loan, now);
            let real_debt = match checked_add_i64(loan.outstanding_principal, display_interest) {
                Some(v) => v,
                None => return i64::MAX,
            };
            let value_in_num =
                Self::value_in_numeraire(loan.loan_currency, real_debt, numeraire_currency, numeraire_spec, ssp, price_cache);
            if value_in_num < 0 {

                return unevaluable;
            }
            total_debt = match checked_add_i64(total_debt, value_in_num) {
                Some(v) => v,

                None => return i64::MAX,
            };
        }

        let mut total_collateral: i64 = 0;
        for (&currency, &amount) in up.cross_loan_collateral.iter() {
            if amount <= 0 {
                continue;
            }
            let weight = Self::collateral_weight_for_base(currency, ssp);
            if weight <= 0 {

                continue;
            }
            let value_in_num = Self::value_in_numeraire(currency, amount, numeraire_currency, numeraire_spec, ssp, price_cache);
            if value_in_num < 0 {
                return unevaluable;
            }
            let contribution =
                if apply_weight { arithmetic::trunc_mul_div(value_in_num, weight as i64, BPS_SCALE) } else { value_in_num };
            total_collateral = match checked_add_i64(total_collateral, contribution) {
                Some(v) => v,

                None => return unevaluable,
            };
        }

        if total_collateral <= 0 {
            return i64::MAX;
        }
        arithmetic::trunc_mul_div(total_debt, BPS_SCALE, total_collateral)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_has_empty_buckets_and_default_config() {
        let s = LoanService::new();
        assert!(s.loan_pool_available.is_empty());
        assert!(s.loan_pool_borrowed.is_empty());
        assert!(s.interest_revenue.is_empty());
        assert!(s.loan_insurance_fund.is_empty());
        assert_eq!(s.global_config, LoanGlobalConfig::default());
        assert_eq!(s.floating_rate, FloatingRateModel::default());
        assert_eq!(s.fixed_rate, FixedRateModel::default());
    }

    #[test]
    fn get_defaults_to_zero_for_unseen_currency() {
        let s = LoanService::new();
        assert_eq!(s.get_loan_pool_available(1), 0);
        assert_eq!(s.get_loan_pool_borrowed(1), 0);
        assert_eq!(s.get_interest_revenue(1), 0);
        assert_eq!(s.get_loan_insurance_fund(1), 0);
    }

    #[test]
    fn add_to_loan_pool_available_accumulates_and_allows_negative_delta() {
        let mut s = LoanService::new();
        s.add_to_loan_pool_available(1, 1_000);
        s.add_to_loan_pool_available(1, -300);
        assert_eq!(s.get_loan_pool_available(1), 700);
        assert_eq!(s.get_loan_pool_available(2), 0);
    }

    #[test]
    fn add_to_loan_pool_borrowed_accumulates() {
        let mut s = LoanService::new();
        s.add_to_loan_pool_borrowed(1, 500);
        s.add_to_loan_pool_borrowed(1, 250);
        assert_eq!(s.get_loan_pool_borrowed(1), 750);
    }

    #[test]
    fn add_to_interest_revenue_accumulates() {
        let mut s = LoanService::new();
        s.add_to_interest_revenue(1, 10);
        s.add_to_interest_revenue(1, 5);
        assert_eq!(s.get_interest_revenue(1), 15);
    }

    #[test]
    fn add_to_loan_insurance_fund_allows_negative_balance() {
        let mut s = LoanService::new();
        s.add_to_loan_insurance_fund(1, -100);
        assert_eq!(s.get_loan_insurance_fund(1), -100);
        s.add_to_loan_insurance_fund(1, 40);
        assert_eq!(s.get_loan_insurance_fund(1), -60);
    }

    #[test]
    fn state_hash_deterministic_for_same_state() {
        let mut a = LoanService::new();
        a.add_to_loan_pool_available(1, 100);
        let mut b = LoanService::new();
        b.add_to_loan_pool_available(1, 100);
        assert_eq!(a.state_hash(), b.state_hash());
    }

    #[test]
    fn state_hash_changes_with_each_bucket_and_substructure() {
        let base = LoanService::new();
        let h0 = base.state_hash();

        let mut diff_available = LoanService::new();
        diff_available.add_to_loan_pool_available(1, 1);
        assert_ne!(h0, diff_available.state_hash());

        let mut diff_borrowed = LoanService::new();
        diff_borrowed.add_to_loan_pool_borrowed(1, 1);
        assert_ne!(h0, diff_borrowed.state_hash());

        let mut diff_interest = LoanService::new();
        diff_interest.add_to_interest_revenue(1, 1);
        assert_ne!(h0, diff_interest.state_hash());

        let mut diff_if = LoanService::new();
        diff_if.add_to_loan_insurance_fund(1, 1);
        assert_ne!(h0, diff_if.state_hash());

        let mut diff_config = LoanService::new();
        diff_config.global_config.numeraire_currency = 1;
        assert_ne!(h0, diff_config.state_hash());

        let mut diff_floating = LoanService::new();
        diff_floating.floating_rate.base_bps = 999;
        assert_ne!(h0, diff_floating.state_hash());

        let mut diff_fixed = LoanService::new();
        diff_fixed.fixed_rate.locked_rate_adjust_bps = 5;
        assert_ne!(h0, diff_fixed.state_hash());
    }

    use crate::core::common::isolated_loan_record::{IsolatedLoanRecord, LoanRateMode};

    #[test]
    fn accrue_to_dispatches_fixed_loans_to_fixed_rate_model() {
        let s = LoanService::new();
        let mut fixed_loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 5_000 , 0);
        fixed_loan.set_outstanding_principal(1_000_000);
        assert!(fixed_loan.is_fixed_rate());

        let delta = s.accrue_to(&mut fixed_loan, YEAR_MS);

        assert_eq!(delta, 500_000);
        assert_eq!(fixed_loan.accumulated_interest(), 500_000);
    }

    #[test]
    fn accrue_to_dispatches_floating_loans_to_floating_rate_model() {
        let mut s = LoanService::new();
        s.floating_rate.last_reprice_ts = 1_000;
        s.floating_rate.current_rate_bps.insert(20, 500);

        let mut floating_loan = IsolatedLoanRecord::new(2, 2, 100, 10, 20, 0, 1_000);
        floating_loan.rate_mode = LoanRateMode::Floating;
        floating_loan.set_outstanding_principal(315_360_000_000);
        assert!(!floating_loan.is_fixed_rate());

        let delta = s.accrue_to(&mut floating_loan, 3_000);

        assert_eq!(delta, 1_000);
        assert_eq!(floating_loan.accumulated_interest(), 1_000);
        assert_eq!(floating_loan.acc_snapshot(), 1_000_000);
    }

    #[test]
    fn calculate_display_interest_dispatches_by_is_fixed_rate_and_does_not_mutate() {
        let s = LoanService::new();

        let mut fixed_loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 5_000, 0);
        fixed_loan.set_outstanding_principal(1_000_000);
        assert_eq!(s.calculate_display_interest(&fixed_loan, YEAR_MS), 500_000);
        assert_eq!(fixed_loan.accumulated_interest(), 0);

        let mut floating_loan = IsolatedLoanRecord::new(2, 2, 100, 10, 20, 0, 1_000);
        floating_loan.rate_mode = LoanRateMode::Floating;
        floating_loan.set_outstanding_principal(315_360_000_000);
        let mut s2 = LoanService::new();
        s2.floating_rate.last_reprice_ts = 1_000;
        s2.floating_rate.current_rate_bps.insert(20, 500);
        assert_eq!(s2.calculate_display_interest(&floating_loan, 3_000), 1_000);
        assert_eq!(floating_loan.accumulated_interest(), 0);
    }

    #[test]
    fn open_rate_bps_dispatches_floating_directly() {
        let mut s = LoanService::new();
        s.floating_rate.current_rate_bps.insert(20, 777);
        assert_eq!(s.open_rate_bps(LoanRateMode::Floating, 20), 777);
    }

    #[test]
    fn open_rate_bps_locked_derives_from_floating_plus_spread() {
        let mut s = LoanService::new();
        s.floating_rate.current_rate_bps.insert(20, 700);
        s.fixed_rate.locked_rate_adjust_bps = 50;
        assert_eq!(s.open_rate_bps(LoanRateMode::Locked, 20), 750);
    }

    #[test]
    fn verify_pool_capacity_rejects_when_available_below_principal() {
        let mut s = LoanService::new();
        s.add_to_loan_pool_available(1, 100);
        assert_eq!(s.verify_pool_capacity(1, 101), CommandResultCode::LoanPoolInsufficient);
        assert_eq!(s.verify_pool_capacity(1, 90), CommandResultCode::Success);
    }

    #[test]
    fn verify_pool_capacity_rejects_when_utilization_exceeds_cap() {
        let mut s = LoanService::new();
        s.add_to_loan_pool_available(1, 1_000);
        s.add_to_loan_pool_borrowed(1, 8_000);
        assert_eq!(s.verify_pool_capacity(1, 200), CommandResultCode::LoanPoolUtilizationExceeded);
        assert_eq!(s.verify_pool_capacity(1, 100), CommandResultCode::Success);
    }

    #[test]
    fn verify_pool_capacity_skips_utilization_check_when_pool_untouched() {
        let s = LoanService::new();
        assert_eq!(s.verify_pool_capacity(1, 1), CommandResultCode::LoanPoolInsufficient);
        assert_eq!(s.verify_pool_capacity(1, 0), CommandResultCode::Success);
    }

    #[test]
    fn disburse_loan_moves_principal_from_pool_available_to_account_and_marks_borrowed() {
        let mut s = LoanService::new();
        s.add_to_loan_pool_available(1, 1_000);
        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);

        s.disburse_loan(&mut up, 1, 300);

        assert_eq!(up.account(1), 300);
        assert_eq!(s.get_loan_pool_available(1), 700);
        assert_eq!(s.get_loan_pool_borrowed(1), 300);
    }

    #[test]
    fn apply_debt_payment_pays_interest_before_principal() {
        let mut s = LoanService::new();
        let mut loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 500, 0);
        loan.set_outstanding_principal(1_000);
        loan.set_accumulated_interest(100);
        let mut accounts: BTreeMap<i32, i64> = BTreeMap::new();
        accounts.insert(20, 10_000);

        let interest_paid = s.apply_debt_payment(&mut loan, &mut accounts, 150);

        assert_eq!(interest_paid, 100);
        assert_eq!(loan.accumulated_interest(), 0);
        assert_eq!(loan.outstanding_principal(), 950);
        assert_eq!(loan.cum_interest_paid(), 100);
        assert_eq!(*accounts.get(&20).unwrap(), 10_000 - 150);
        assert_eq!(s.get_interest_revenue(20), 100);
        assert_eq!(s.get_loan_pool_available(20), 50);
        assert_eq!(s.get_loan_pool_borrowed(20), -50);
    }

    #[test]
    fn apply_debt_payment_full_payoff_zeroes_both_debt_fields() {
        let mut s = LoanService::new();
        let mut loan = IsolatedLoanRecord::new(1, 1, 100, 10, 20, 500, 0);
        loan.set_outstanding_principal(1_000);
        loan.set_accumulated_interest(100);
        let mut accounts: BTreeMap<i32, i64> = BTreeMap::new();

        s.apply_debt_payment(&mut loan, &mut accounts, 1_100);

        assert_eq!(loan.accumulated_interest(), 0);
        assert_eq!(loan.outstanding_principal(), 0);
        assert!(*accounts.get(&20).unwrap() < 0);
    }

    #[test]
    fn collateral_value_in_quote_currency_scales_through_symbol_and_currency() {
        let spec = CoreSymbolSpecification {
            symbol_id: 1,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        };
        let base_spec = CoreCurrencySpecification { currency: 1, currency_scale_k: 1, ..Default::default() };
        let quote_spec = CoreCurrencySpecification { currency: 2, currency_scale_k: 1, ..Default::default() };

        let value = LoanService::collateral_value_in_quote_currency(10, &spec, 5, Some(&base_spec), Some(&quote_spec));
        assert_eq!(value, 50);
    }

    #[test]
    fn collateral_value_in_quote_currency_returns_negative_one_when_a_currency_spec_is_missing() {
        let spec = CoreSymbolSpecification {
            symbol_id: 1,
            base_currency: 1,
            quote_currency: 2,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        };
        let base_spec = CoreCurrencySpecification { currency: 1, currency_scale_k: 1, ..Default::default() };

        assert_eq!(LoanService::collateral_value_in_quote_currency(10, &spec, 5, Some(&base_spec), None), -1);
        assert_eq!(LoanService::collateral_value_in_quote_currency(10, &spec, 5, None, Some(&base_spec)), -1);
        assert_eq!(LoanService::collateral_value_in_quote_currency(10, &spec, 5, None, None), -1);
    }

    use crate::core::common::cross_loan_record::CrossLoanRecord;

    const COLLATERAL_CUR: i32 = 1;
    const NUMERAIRE_CUR: i32 = 2;
    const SPOT_SYMBOL: i32 = 100;

    fn cross_fixture(weight_bps: i32) -> (SymbolSpecificationProvider, std::collections::BTreeMap<i32, LastPriceCacheRecord>) {
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_symbol(CoreSymbolSpecification {
            symbol_id: SPOT_SYMBOL,
            symbol_type: crate::core::common::symbol_type::SymbolType::CurrencyExchangePair,
            base_currency: COLLATERAL_CUR,
            quote_currency: NUMERAIRE_CUR,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        });
        ssp.add_currency(CoreCurrencySpecification {
            currency: COLLATERAL_CUR,
            currency_scale_k: 1,
            collateral_weight_bps: weight_bps,
            ..Default::default()
        });
        ssp.add_currency(CoreCurrencySpecification { currency: NUMERAIRE_CUR, currency_scale_k: 1, ..Default::default() });
        let mut price_cache = std::collections::BTreeMap::new();
        price_cache.insert(SPOT_SYMBOL, LastPriceCacheRecord::with_mark(1));
        (ssp, price_cache)
    }

    fn cross_loan(uid: i64, loan_id: i64, principal: i64, now: i64) -> CrossLoanRecord {
        let mut loan = CrossLoanRecord::new(uid, loan_id, SPOT_SYMBOL, NUMERAIRE_CUR, 0, now);
        loan.outstanding_principal = principal;
        loan
    }

    #[test]
    fn collateral_weight_for_base_reads_spec_and_defaults_to_zero_when_missing() {
        let (ssp, _) = cross_fixture(5_000);
        assert_eq!(LoanService::collateral_weight_for_base(COLLATERAL_CUR, &ssp), 5_000);
        assert_eq!(LoanService::collateral_weight_for_base(999, &ssp), 0);
    }

    #[test]
    fn value_in_numeraire_identity_for_same_currency() {
        let (ssp, price_cache) = cross_fixture(5_000);
        let numeraire_spec = ssp.get_currency(NUMERAIRE_CUR).unwrap();
        assert_eq!(
            LoanService::value_in_numeraire(NUMERAIRE_CUR, 12_345, NUMERAIRE_CUR, numeraire_spec, &ssp, &price_cache),
            12_345
        );
    }

    #[test]
    fn value_in_numeraire_converts_through_spot_symbol_and_mark_price() {
        let (ssp, price_cache) = cross_fixture(5_000);
        let numeraire_spec = ssp.get_currency(NUMERAIRE_CUR).unwrap();
        assert_eq!(
            LoanService::value_in_numeraire(COLLATERAL_CUR, 1_000, NUMERAIRE_CUR, numeraire_spec, &ssp, &price_cache),
            1_000
        );
    }

    #[test]
    fn value_in_numeraire_returns_negative_one_sentinel_when_spot_symbol_or_price_missing() {
        let (ssp, price_cache) = cross_fixture(5_000);
        let numeraire_spec = ssp.get_currency(NUMERAIRE_CUR).unwrap();
        assert_eq!(LoanService::value_in_numeraire(999, 1_000, NUMERAIRE_CUR, numeraire_spec, &ssp, &price_cache), -1);
        let empty_price_cache: std::collections::BTreeMap<i32, LastPriceCacheRecord> = std::collections::BTreeMap::new();
        assert_eq!(
            LoanService::value_in_numeraire(COLLATERAL_CUR, 1_000, NUMERAIRE_CUR, numeraire_spec, &ssp, &empty_price_cache),
            -1
        );
    }

    #[test]
    fn calculate_cross_account_ltv_bps_zero_when_no_cross_loans_or_numeraire_unset() {
        let (ssp, price_cache) = cross_fixture(5_000);
        let mut s = LoanService::new();
        s.global_config.numeraire_currency = NUMERAIRE_CUR;
        let up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        assert_eq!(s.calculate_cross_account_ltv_bps(&up, 1_000, &ssp, &price_cache, true), 0);

        let s2 = LoanService::new();
        let mut up2 = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        up2.cross_loans.insert(1, cross_loan(1, 1, 400, 1_000));
        assert_eq!(s2.calculate_cross_account_ltv_bps(&up2, 1_000, &ssp, &price_cache, true), 0);
    }

    #[test]
    fn weighted_and_raw_cross_ltv_diverge_when_collateral_weight_below_full() {
        let (ssp, price_cache) = cross_fixture(5_000);
        let mut s = LoanService::new();
        s.global_config.numeraire_currency = NUMERAIRE_CUR;

        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        up.cross_loans.insert(1, cross_loan(1, 1, 400, 1_000));
        up.cross_loan_collateral.insert(COLLATERAL_CUR, 1_000);

        let weighted = s.calculate_cross_account_ltv_bps(&up, 1_000, &ssp, &price_cache, false);
        let raw = s.calculate_cross_raw_ltv_bps(&up, 1_000, &ssp, &price_cache);

        assert_eq!(weighted, 8_000);
        assert_eq!(raw, 4_000);
        assert_ne!(weighted, raw);
        assert_eq!(weighted, raw * 2);
    }

    #[test]
    fn calculate_cross_account_ltv_bps_ignores_collateral_currency_with_zero_weight() {
        let (ssp, price_cache) = cross_fixture(0);
        let mut s = LoanService::new();
        s.global_config.numeraire_currency = NUMERAIRE_CUR;

        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        up.cross_loans.insert(1, cross_loan(1, 1, 400, 1_000));
        up.cross_loan_collateral.insert(COLLATERAL_CUR, 1_000);

        assert_eq!(s.calculate_cross_account_ltv_bps(&up, 1_000, &ssp, &price_cache, false), i64::MAX);
        assert_eq!(s.calculate_cross_raw_ltv_bps(&up, 1_000, &ssp, &price_cache), i64::MAX);
    }

    #[test]
    fn calculate_cross_account_ltv_bps_fail_closed_flag_controls_missing_price_sentinel() {
        let mut ssp = SymbolSpecificationProvider::new();
        let debt_currency = 3;
        ssp.add_currency(CoreCurrencySpecification { currency: NUMERAIRE_CUR, currency_scale_k: 1, ..Default::default() });
        let price_cache: std::collections::BTreeMap<i32, LastPriceCacheRecord> = std::collections::BTreeMap::new();

        let mut s = LoanService::new();
        s.global_config.numeraire_currency = NUMERAIRE_CUR;
        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        let mut loan = CrossLoanRecord::new(1, 1, SPOT_SYMBOL, debt_currency, 0, 1_000);
        loan.outstanding_principal = 400;
        up.cross_loans.insert(1, loan);

        assert_eq!(s.calculate_cross_account_ltv_bps(&up, 1_000, &ssp, &price_cache, true), i64::MAX);
        assert_eq!(s.calculate_cross_account_ltv_bps(&up, 1_000, &ssp, &price_cache, false), 0);
    }

    fn spec_scaled(base_scale_k: i64, quote_scale_k: i64) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SPOT_SYMBOL,
            symbol_type: crate::core::common::symbol_type::SymbolType::CurrencyExchangePair,
            base_currency: COLLATERAL_CUR,
            quote_currency: NUMERAIRE_CUR,
            base_scale_k,
            quote_scale_k,
            ..Default::default()
        }
    }

    #[test]
    fn lots_to_collateral_amount_and_back_round_trip_at_scale_identity() {
        let spec = spec_scaled(1, 1);
        let base_spec = CoreCurrencySpecification { currency: COLLATERAL_CUR, currency_scale_k: 1, ..Default::default() };
        assert_eq!(LoanService::lots_to_collateral_amount(10, &spec, &base_spec), 10);
        assert_eq!(LoanService::collateral_amount_to_lots(10, &spec, &base_spec), 10);
    }

    #[test]
    fn collateral_amount_to_lots_truncates_sub_lot_dust() {
        let spec = spec_scaled(1, 1);
        let base_spec = CoreCurrencySpecification { currency: COLLATERAL_CUR, currency_scale_k: 100, ..Default::default() };
        assert_eq!(LoanService::lots_to_collateral_amount(10, &spec, &base_spec), 1_000);
        assert_eq!(LoanService::collateral_amount_to_lots(1_050, &spec, &base_spec), 10);
        assert_eq!(LoanService::collateral_amount_to_lots(50, &spec, &base_spec), 0);
    }

    #[test]
    fn settle_liquidation_proceeds_skims_ceil_fee_before_debt_payment() {
        let mut s = LoanService::new();
        assert_eq!(s.global_config.loan_liquidation_fee_bps, 200);
        let mut loan = IsolatedLoanRecord::new(1, 1, SPOT_SYMBOL, COLLATERAL_CUR, NUMERAIRE_CUR, 0, 0);
        loan.outstanding_principal = 500;
        let mut account: BTreeMap<i32, i64> = BTreeMap::new();
        account.insert(NUMERAIRE_CUR, 10_000);

        let interest_paid = s.settle_liquidation_proceeds(&mut loan, &mut account, 1_000, 0);

        assert_eq!(interest_paid, 0);
        assert_eq!(loan.outstanding_principal, 0);
        assert_eq!(s.get_loan_insurance_fund(NUMERAIRE_CUR), 20);
        assert_eq!(s.get_loan_pool_available(NUMERAIRE_CUR), 500);
        assert_eq!(*account.get(&NUMERAIRE_CUR).unwrap(), 10_000 - 20 - 500);
    }

    #[test]
    fn settle_liquidation_proceeds_caps_fee_at_received_quote_when_rate_would_exceed_it() {
        let mut s = LoanService::new();
        s.global_config.loan_liquidation_fee_bps = 20_000;
        let mut loan = IsolatedLoanRecord::new(1, 1, SPOT_SYMBOL, COLLATERAL_CUR, NUMERAIRE_CUR, 0, 0);
        loan.outstanding_principal = 5;
        let mut account: BTreeMap<i32, i64> = BTreeMap::new();

        s.settle_liquidation_proceeds(&mut loan, &mut account, 100, 0);

        assert_eq!(s.get_loan_insurance_fund(NUMERAIRE_CUR), 100);
        assert_eq!(loan.outstanding_principal, 5);
    }

    #[test]
    fn is_structurally_sellable_requires_positive_weight_and_a_ready_spot_pair_to_outstanding_debt() {
        let (ssp, _) = cross_fixture(5_000);
        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        up.cross_loans.insert(1, cross_loan(1, 1, 400, 1_000));

        assert!(LoanService::is_structurally_sellable(COLLATERAL_CUR, 1_000, &up, &ssp));
        assert!(!LoanService::is_structurally_sellable(COLLATERAL_CUR, 0, &up, &ssp));
        assert!(!LoanService::is_structurally_sellable(999, 1_000, &up, &ssp));

        let (ssp0, _) = cross_fixture(0);
        assert!(!LoanService::is_structurally_sellable(COLLATERAL_CUR, 1_000, &up, &ssp0));
    }

    #[test]
    fn is_structurally_sellable_false_when_no_outstanding_cross_debt() {
        let (ssp, _) = cross_fixture(5_000);
        let up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        assert!(!LoanService::is_structurally_sellable(COLLATERAL_CUR, 1_000, &up, &ssp));
    }

    #[test]
    fn take_over_cross_loan_fails_closed_when_numeraire_unconfigured_and_leaves_state_untouched() {
        let (ssp, price_cache) = cross_fixture(5_000);
        let mut s = LoanService::new();
        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        up.cross_loans.insert(1, cross_loan(1, 1, 400, 1_000));
        up.cross_loan_collateral.insert(COLLATERAL_CUR, 1_000);

        let taken = s.take_over_cross_loan(&mut up, 1, 1_000, &ssp, &price_cache);

        assert!(!taken);
        assert_eq!(up.cross_loans.get(&1).unwrap().outstanding_principal, 400);
        assert_eq!(up.cross_loan_collateral.get(&COLLATERAL_CUR), Some(&1_000));
        assert_eq!(s.get_loan_insurance_fund(NUMERAIRE_CUR), 0);
    }

    #[test]
    fn take_over_cross_loan_fails_closed_when_a_debt_currency_price_is_missing() {
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_currency(CoreCurrencySpecification { currency: NUMERAIRE_CUR, currency_scale_k: 1, ..Default::default() });
        let debt_currency = 3;
        let price_cache: BTreeMap<i32, LastPriceCacheRecord> = BTreeMap::new();
        let mut s = LoanService::new();
        s.global_config.numeraire_currency = NUMERAIRE_CUR;
        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        let mut loan = CrossLoanRecord::new(1, 1, SPOT_SYMBOL, debt_currency, 0, 1_000);
        loan.outstanding_principal = 400;
        up.cross_loans.insert(1, loan);

        let taken = s.take_over_cross_loan(&mut up, 1, 1_000, &ssp, &price_cache);

        assert!(!taken);
        assert_eq!(up.cross_loans.get(&1).unwrap().outstanding_principal, 400);
    }

    #[test]
    fn take_over_cross_loan_moves_lif_two_currencies_and_physically_debits_collateral() {
        let (ssp, price_cache) = cross_fixture(5_000);
        let mut s = LoanService::new();
        s.global_config.numeraire_currency = NUMERAIRE_CUR;
        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        up.cross_loans.insert(1, cross_loan(1, 1, 400, 1_000));
        up.cross_loans.insert(2, cross_loan(1, 2, 600, 1_000));
        up.cross_loan_collateral.insert(COLLATERAL_CUR, 1_000);
        up.add_to_account(COLLATERAL_CUR, 1_000);

        let taken = s.take_over_cross_loan(&mut up, 1, 1_000, &ssp, &price_cache);

        assert!(taken);
        assert_eq!(up.cross_loans.get(&1).unwrap().outstanding_principal, 400);
        assert_eq!(up.cross_loans.get(&2).unwrap().outstanding_principal, 600);
        assert_eq!(s.get_loan_insurance_fund(NUMERAIRE_CUR), -400);
        assert_eq!(s.get_loan_insurance_fund(COLLATERAL_CUR), 400);
        assert_eq!(s.get_loan_pool_available(NUMERAIRE_CUR), 400);
        assert_eq!(s.get_loan_pool_borrowed(NUMERAIRE_CUR), -400);
        assert_eq!(s.get_interest_revenue(NUMERAIRE_CUR), 0);
        assert_eq!(up.cross_loan_collateral.get(&COLLATERAL_CUR), Some(&600));
        assert_eq!(up.account(COLLATERAL_CUR), 1_000 - 400);
    }

    #[test]
    fn take_over_cross_loan_takes_the_whole_pool_when_it_is_the_sole_debt() {
        let (ssp, price_cache) = cross_fixture(5_000);
        let mut s = LoanService::new();
        s.global_config.numeraire_currency = NUMERAIRE_CUR;
        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        up.cross_loans.insert(1, cross_loan(1, 1, 400, 1_000));
        up.cross_loan_collateral.insert(COLLATERAL_CUR, 1_000);
        up.add_to_account(COLLATERAL_CUR, 1_000);

        let taken = s.take_over_cross_loan(&mut up, 1, 1_000, &ssp, &price_cache);

        assert!(taken);
        assert_eq!(s.get_loan_insurance_fund(NUMERAIRE_CUR), -400);
        assert_eq!(s.get_loan_insurance_fund(COLLATERAL_CUR), 1_000);
        assert_eq!(up.cross_loan_collateral.get(&COLLATERAL_CUR), Some(&0));
        assert_eq!(up.account(COLLATERAL_CUR), 0);
    }

    #[test]
    fn take_over_cross_loan_caps_collateral_take_at_available_amount_when_undercollateralized() {
        let (ssp, price_cache) = cross_fixture(10_000);
        let mut s = LoanService::new();
        s.global_config.numeraire_currency = NUMERAIRE_CUR;
        let mut up = UserProfile::new(1, crate::core::common::user_status::UserStatus::Active);
        up.cross_loans.insert(1, cross_loan(1, 1, 1_000, 1_000));
        up.cross_loan_collateral.insert(COLLATERAL_CUR, 300);
        up.add_to_account(COLLATERAL_CUR, 300);

        let taken = s.take_over_cross_loan(&mut up, 1, 1_000, &ssp, &price_cache);

        assert!(taken);
        assert_eq!(up.cross_loan_collateral.get(&COLLATERAL_CUR), Some(&0));
        assert_eq!(up.account(COLLATERAL_CUR), 0);
        assert_eq!(s.get_loan_insurance_fund(COLLATERAL_CUR), 300);
        assert_eq!(s.get_loan_insurance_fund(NUMERAIRE_CUR), -1_000);
    }
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::ChronicleMarshallable;

impl ChronicleMarshallable for LoanService {

    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_int_long_map(&self.loan_pool_available);
        w.write_int_long_map(&self.loan_pool_borrowed);
        w.write_int_long_map(&self.interest_revenue);
        w.write_int_long_map(&self.loan_insurance_fund);
        self.global_config.chronicle_write(w);
        self.floating_rate.chronicle_write(w);
        self.fixed_rate.chronicle_write(w);
    }

    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        Ok(LoanService {
            loan_pool_available: crate::core::snapshot::marshalling::to_btree_i32(r.read_int_long_map()?),
            loan_pool_borrowed: crate::core::snapshot::marshalling::to_btree_i32(r.read_int_long_map()?),
            interest_revenue: crate::core::snapshot::marshalling::to_btree_i32(r.read_int_long_map()?),
            loan_insurance_fund: crate::core::snapshot::marshalling::to_btree_i32(r.read_int_long_map()?),
            global_config: LoanGlobalConfig::chronicle_read(r)?,
            floating_rate: FloatingRateModel::chronicle_read(r)?,
            fixed_rate: FixedRateModel::chronicle_read(r)?,
        })
    }
}

#[cfg(test)]
mod java_parity {
    use super::*;

    #[test]
    fn floating_open_rate_falls_back_to_base_then_uses_current() {
        let mut svc = LoanService::new();
        assert_eq!(svc.floating_rate.open_rate_bps(2), 200, "before any reprice -> falls back to curve base=200");
        svc.floating_rate.current_rate_bps.insert(2, 555);
        assert_eq!(svc.floating_rate.open_rate_bps(2), 555, "after reprice -> uses the effective rate");
    }

    #[test]
    fn fixed_open_rate_applies_adjust_with_floor() {
        let mut svc = LoanService::new();
        svc.floating_rate.current_rate_bps.insert(2, 500);
        assert_eq!(svc.fixed_rate.open_rate_bps(&svc.floating_rate, 2), 500, "adjust=0 -> same as Floating");
        svc.fixed_rate.locked_rate_adjust_bps = 50;
        assert_eq!(svc.fixed_rate.open_rate_bps(&svc.floating_rate, 2), 550, "Fixed = Floating + adjust");
        svc.fixed_rate.locked_rate_adjust_bps = -600;
        assert_eq!(svc.fixed_rate.open_rate_bps(&svc.floating_rate, 2), 0, "negative adjust that crosses zero is floored at 0");
    }

    #[test]
    fn serialization_round_trips_rate_subsystem() {
        let mut orig = LoanService::new();
        orig.floating_rate.current_rate_bps.insert(2, 480);
        orig.floating_rate.current_rate_bps.insert(5, 3600);
        orig.floating_rate.base_bps = 150;
        orig.floating_rate.kink_util_bps = 7500;
        orig.floating_rate.slope1_bps = 350;
        orig.floating_rate.slope2_bps = 5000;
        orig.fixed_rate.locked_rate_adjust_bps = -25;
        orig.floating_rate.last_reprice_ts = 1_700_000_000_000;
        orig.floating_rate.acc_rate_bps_ms.insert(2, 987_654);

        let mut w = ChronicleWriter::new();
        orig.chronicle_write(&mut w);
        let bytes = w.into_bytes();
        let parsed = LoanService::chronicle_read(&mut ChronicleReader::new(&bytes)).expect("chronicle_read");

        assert_eq!(parsed.floating_rate.current_rate_bps.get(&2), Some(&480));
        assert_eq!(parsed.floating_rate.current_rate_bps.get(&5), Some(&3600));
        assert_eq!(parsed.floating_rate.base_bps, 150);
        assert_eq!(parsed.floating_rate.kink_util_bps, 7500);
        assert_eq!(parsed.floating_rate.slope1_bps, 350);
        assert_eq!(parsed.floating_rate.slope2_bps, 5000);
        assert_eq!(parsed.fixed_rate.locked_rate_adjust_bps, -25);
        assert_eq!(parsed.floating_rate.last_reprice_ts, 1_700_000_000_000);
        assert_eq!(parsed.floating_rate.acc_rate_bps_ms.get(&2), Some(&987_654));
        assert_eq!(orig.state_hash(), parsed.state_hash(), "stateHash is unchanged after a serialization round-trip");
    }
}
