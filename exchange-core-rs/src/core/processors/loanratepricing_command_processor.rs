use std::collections::{BTreeMap, BTreeSet};

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::processors::loan::loan_service::LoanService;
use crate::core::processors::loan::rate::floating_rate_model::FloatingRateModel;
use crate::core::processors::twostep_command_processor::{TwoStepCommandProcessor, TwoStepContext};

pub struct LoanRatePricingCommandProcessor;

impl TwoStepCommandProcessor for LoanRatePricingCommandProcessor {

    fn collect(&self, ctx: &mut TwoStepContext, cmd: &mut OrderCommand) -> CommandResultCode {
        let shard_data = Self::collect_input(&ctx.risk.loan_service);
        cmd.loan_reprice_events = Self::build_matcher_events(&[shard_data]);
        CommandResultCode::Success
    }

    fn apply(&self, ctx: &mut TwoStepContext, cmd: &mut OrderCommand) {
        let events = std::mem::take(&mut cmd.loan_reprice_events);
        if events.is_empty() {
            return;
        }
        for (currency, util_bps) in events {
            Self::apply_event(&mut ctx.risk.loan_service, currency, util_bps, cmd.timestamp);
        }
        ctx.risk.loan_service.floating_rate.set_last_reprice_ts(cmd.timestamp);
    }
}

impl LoanRatePricingCommandProcessor {

    fn collect_input(loan_service: &LoanService) -> BTreeMap<i32, i64> {
        let mut shard_data = BTreeMap::new();
        for (&currency, &v) in &loan_service.loan_pool_borrowed {
            if v != 0 {
                shard_data.insert(currency, v);
            }
        }
        for (&currency, &v) in &loan_service.loan_pool_available {
            if v != 0 {
                shard_data.insert(!currency, v);
            }
        }
        shard_data
    }

    fn build_matcher_events(shard_data: &[BTreeMap<i32, i64>]) -> Vec<(i32, i64)> {
        let mut total_borrowed: BTreeMap<i32, i64> = BTreeMap::new();
        let mut total_available: BTreeMap<i32, i64> = BTreeMap::new();
        for shard in shard_data {
            for (&k, &v) in shard {
                if k >= 0 {
                    *total_borrowed.entry(k).or_insert(0) += v;
                } else {
                    *total_available.entry(!k).or_insert(0) += v;
                }
            }
        }
        let mut currencies: BTreeSet<i32> = BTreeSet::new();
        currencies.extend(total_borrowed.keys().copied());
        currencies.extend(total_available.keys().copied());
        currencies
            .into_iter()
            .map(|currency| {
                let borrowed = *total_borrowed.get(&currency).unwrap_or(&0);
                let available = *total_available.get(&currency).unwrap_or(&0);
                let util = FloatingRateModel::utilization_bps(borrowed, available);
                (currency, util)
            })
            .collect()
    }

    fn apply_event(loan_service: &mut LoanService, currency: i32, util_bps: i64, tick_ts: i64) {
        loan_service.floating_rate.advance_accumulator(currency, tick_ts);
        loan_service.floating_rate.reprice_currency(currency, util_bps);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn loan_service_with_pools(entries: &[(i32, i64, i64)]) -> LoanService {
        let mut s = LoanService::new();
        for &(cur, borrowed, available) in entries {
            if borrowed != 0 {
                s.loan_pool_borrowed.insert(cur, borrowed);
            }
            if available != 0 {
                s.loan_pool_available.insert(cur, available);
            }
        }
        s
    }

    #[test]
    fn collect_input_encodes_borrowed_at_currency_and_available_at_bitwise_complement() {
        let s = loan_service_with_pools(&[(5, 8_000, 2_000)]);
        let shard_data = LoanRatePricingCommandProcessor::collect_input(&s);
        assert_eq!(shard_data.get(&5), Some(&8_000));
        assert_eq!(shard_data.get(&!5), Some(&2_000));
        assert_eq!(shard_data.len(), 2);
    }

    #[test]
    fn collect_input_skips_zero_valued_buckets() {
        let mut s = LoanService::new();
        s.loan_pool_borrowed.insert(5, 0);
        s.loan_pool_available.insert(5, 100);
        let shard_data = LoanRatePricingCommandProcessor::collect_input(&s);
        assert_eq!(shard_data.get(&5), None);
        assert_eq!(shard_data.get(&!5), Some(&100));
    }

    #[test]
    fn collect_input_no_collision_across_several_currencies() {
        let s = loan_service_with_pools(&[(0, 100, 50), (1, 200, 0), (2, 0, 300)]);
        let shard_data = LoanRatePricingCommandProcessor::collect_input(&s);
        assert_eq!(shard_data.get(&0), Some(&100));
        assert_eq!(shard_data.get(&!0), Some(&50));
        assert_eq!(shard_data.get(&1), Some(&200));
        assert_eq!(shard_data.get(&2), None);
        assert_eq!(shard_data.get(&!2), Some(&300));
    }

    #[test]
    fn build_matcher_events_single_currency_computes_correct_utilization() {
        let s = loan_service_with_pools(&[(7, 8_000, 2_000)]);
        let shard_data = LoanRatePricingCommandProcessor::collect_input(&s);
        let events = LoanRatePricingCommandProcessor::build_matcher_events(&[shard_data]);
        assert_eq!(events, vec![(7, 8_000)]);
    }

    #[test]
    fn build_matcher_events_sums_across_multiple_shard_maps() {
        let shard_a = LoanRatePricingCommandProcessor::collect_input(&loan_service_with_pools(&[(3, 4_000, 1_000)]));
        let shard_b = LoanRatePricingCommandProcessor::collect_input(&loan_service_with_pools(&[(3, 4_000, 1_000)]));
        let events = LoanRatePricingCommandProcessor::build_matcher_events(&[shard_a, shard_b]);
        assert_eq!(events, vec![(3, 8_000)]);
    }

    #[test]
    fn build_matcher_events_multiple_currencies_emitted_in_ascending_order() {
        let s = loan_service_with_pools(&[(9, 100, 100), (2, 100, 100), (5, 100, 100)]);
        let shard_data = LoanRatePricingCommandProcessor::collect_input(&s);
        let events = LoanRatePricingCommandProcessor::build_matcher_events(&[shard_data]);
        let currencies: Vec<i32> = events.iter().map(|&(c, _)| c).collect();
        assert_eq!(currencies, vec![2, 5, 9], "currency must be ascending, for cross-replica determinism");
    }

    #[test]
    fn build_matcher_events_empty_pool_yields_no_events() {
        let s = LoanService::new();
        let shard_data = LoanRatePricingCommandProcessor::collect_input(&s);
        let events = LoanRatePricingCommandProcessor::build_matcher_events(&[shard_data]);
        assert!(events.is_empty());
    }

    #[test]
    fn build_matcher_events_currency_present_only_via_available_or_only_via_borrowed_still_emits() {
        let s = loan_service_with_pools(&[(4, 500, 0), (6, 0, 500)]);
        let shard_data = LoanRatePricingCommandProcessor::collect_input(&s);
        let events = LoanRatePricingCommandProcessor::build_matcher_events(&[shard_data]);
        assert_eq!(events, vec![(4, FloatingRateModel::utilization_bps(500, 0)), (6, FloatingRateModel::utilization_bps(0, 500))]);
    }

    #[test]
    fn apply_event_settles_old_interval_at_old_rate_before_repricing() {
        let mut s = LoanService::new();
        let cur = 3;
        s.floating_rate.last_reprice_ts = 1_000;
        s.floating_rate.current_rate_bps.insert(cur, 300);

        LoanRatePricingCommandProcessor::apply_event(&mut s, cur, 9_000, 2_000);

        let acc = *s.floating_rate.acc_rate_bps_ms.get(&cur).unwrap();
        assert_eq!(acc, 300 * 1_000, "advance_accumulator must run before reprice_currency, settling at the old rate");

        let new_rate = s.floating_rate.current_rate_bps_or_base(cur);
        assert_ne!(new_rate as i64, 300);
        assert_eq!(new_rate as i64, s.floating_rate.curve_rate_bps(9_000));
    }

    #[test]
    fn apply_event_updates_current_rate_bps_per_curve_for_computed_utilization() {
        let mut s = loan_service_with_pools(&[(11, 8_000, 2_000)]);
        let shard_data = LoanRatePricingCommandProcessor::collect_input(&s);
        let events = LoanRatePricingCommandProcessor::build_matcher_events(&[shard_data]);
        assert_eq!(events, vec![(11, 8_000)]);

        let expected_rate = s.floating_rate.curve_rate_bps(8_000);
        for (currency, util) in events {
            LoanRatePricingCommandProcessor::apply_event(&mut s, currency, util, 5_000);
        }
        assert_eq!(s.floating_rate.current_rate_bps_or_base(11), expected_rate as i32);
    }
}
