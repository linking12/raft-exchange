use std::collections::BTreeMap;

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::fund_event::{FundEventType, SYSTEM_TRIGGERED_ORDER_ID};
use crate::core::common::order_action::OrderAction;
use crate::core::common::position_direction::PositionDirection;
use crate::core::common::position_mode::PositionMode;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::common::symbol_type::SymbolType;
use crate::core::common::user_status::UserStatus;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::twostep_command_processor::{TwoStepCommandProcessor, TwoStepContext};
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::utils::core_arithmetic_utils as arithmetic;
use crate::core::utils::core_arithmetic_utils::{distribute_remainder_by_one, mul_exact};

fn sum_i64_checked<'a>(vals: impl Iterator<Item = &'a i64>) -> i64 {
    let s: i128 = vals.map(|&v| v as i128).sum();
    i64::try_from(s).unwrap_or_else(|_| panic!("overflow: funding notional sum {s}"))
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FundingPaymentAndRecvNotional {
    pub payer_amounts: BTreeMap<i64, i64>,
    pub receiver_notionals: BTreeMap<i64, i64>,
}

pub struct FundingFeeCommandProcessor;

impl TwoStepCommandProcessor for FundingFeeCommandProcessor {

    fn collect(&self, ctx: &mut TwoStepContext, cmd: &mut OrderCommand) -> CommandResultCode {
        let spec = match ctx.ssp.get_symbol(cmd.symbol) {
            Some(s) if s.symbol_type == SymbolType::FuturesContractPerpetual => s,
            _ => return CommandResultCode::InvalidSymbol,
        };
        let mark_price = match ctx.risk.mark_price(cmd.symbol) {
            Some(p) => p,
            None => return CommandResultCode::RiskMarkpriceNotAvailable,
        };
        if cmd.size <= 0 {
            return CommandResultCode::RiskInvalidAmount;
        }
        let action = cmd.action.expect("SETTLE_FUNDINGFEES requires action");
        let symbol = spec.symbol_id;
        let shard = Self::collect_input(ctx.ups, symbol, mark_price, action, cmd.price, cmd.size);
        let events = Self::build_matcher_events(std::slice::from_ref(&shard));
        if let Some(&(_shard_id, amount)) = events.first() {
            cmd.funding_fee_event = Some((shard.payer_amounts, shard.receiver_notionals, amount));
        }
        CommandResultCode::Success
    }

    fn apply(&self, ctx: &mut TwoStepContext, cmd: &mut OrderCommand) {
        let Some((payer_amounts, receiver_notionals, shard_recv_amount)) = cmd.funding_fee_event.take() else {
            return;
        };
        let symbol = cmd.symbol;
        let action = cmd.action.expect("SETTLE_FUNDINGFEES requires action");
        let spec = ctx.ssp.get_symbol(symbol).cloned().unwrap_or_else(|| panic!("symbol spec missing for symbol {symbol}"));
        let currency_spec = ctx
            .ssp
            .get_currency(spec.quote_currency)
            .cloned()
            .unwrap_or_else(|| panic!("currency spec missing for currency {}", spec.quote_currency));
        let receiver_fees = Self::apply_event(
            ctx.ups,
            symbol,
            action,
            &payer_amounts,
            &receiver_notionals,
            shard_recv_amount,
            &spec,
            &currency_spec,
        );

        let order_id = SYSTEM_TRIGGERED_ORDER_ID;
        let mark_price = ctx.risk.mark_price(symbol).unwrap_or(0);
        let lpc = &ctx.risk.last_price_cache;
        let payer_dir = PositionDirection::of_action(action);
        let recv_dir = PositionDirection::of_action(action.opposite());
        for (&uid, dir) in payer_amounts
            .keys()
            .map(|u| (u, payer_dir))
            .chain(receiver_fees.iter().filter(|(_, &f)| f != 0).map(|(u, _)| (u, recv_dir)))
        {
            if let Some(up) = ctx.ups.get(uid) {
                if let Some(pos) = up.positions.values().find(|p| p.symbol == symbol && p.open_volume != 0 && p.direction == dir) {
                    RiskEngine::push_futures_event(&mut cmd.fund_events, lpc, FundEventType::FundingfeeSettlement, order_id, pos, &spec, up, ctx.ssp);
                } else {
                    let mut ev = RiskEngine::spot_snapshot_event(
                        FundEventType::FundingfeeSettlement, order_id, up, spec.quote_currency, ctx.ssp, &currency_spec, symbol,
                    );
                    ev.base_scale_k = spec.base_scale_k;
                    ev.quote_scale_k = spec.quote_scale_k;
                    ev.mark_price = mark_price;
                    cmd.fund_events.push(ev);
                }
            }
        }
    }
}

impl FundingFeeCommandProcessor {

    fn collect_input(
        ups: &UserProfileService,
        symbol: i32,
        mark_price: i64,
        action: OrderAction,
        rate: i64,
        rate_scale_k: i64,
    ) -> FundingPaymentAndRecvNotional {
        let mut shard = FundingPaymentAndRecvNotional::default();
        for user in ups.users.values() {
            if user.user_status != UserStatus::Active {
                continue;
            }
            let uid = user.uid;
            let mut process = |position: &SymbolPositionRecord| {
                if position.open_volume == 0 {
                    return;
                }
                let notional = mul_exact(position.open_volume, mark_price);
                if position.direction.is_same_as_action(action) {
                    let fee = arithmetic::trunc_mul_div(notional, rate, rate_scale_k);
                    if fee > 0 {
                        shard.payer_amounts.insert(uid, fee);
                    }
                } else {
                    shard.receiver_notionals.insert(uid, notional);
                }
            };
            if let Some(position) = user.positions.get(&symbol) {
                process(position);
            }
            if user.position_mode == PositionMode::Hedge {
                if let Some(position) = user.positions.get(&-symbol) {
                    process(position);
                }
            }
        }
        shard
    }

    fn build_matcher_events(shards_data: &[FundingPaymentAndRecvNotional]) -> Vec<(usize, i64)> {
        let total_pay = sum_i64_checked(shards_data.iter().flat_map(|s| s.payer_amounts.values()));
        let total_recv_notional = sum_i64_checked(shards_data.iter().flat_map(|s| s.receiver_notionals.values()));
        if total_pay == 0 || total_recv_notional == 0 {
            return Vec::new();
        }

        let mut weights: BTreeMap<usize, i64> = BTreeMap::new();
        for (shard_id, shard) in shards_data.iter().enumerate() {
            if !shard.receiver_notionals.is_empty() {
                weights.insert(shard_id, sum_i64_checked(shard.receiver_notionals.values()));
            }
        }
        let shard_recv_amount = distribute_remainder_by_one(total_pay, &weights);

        let mut events = Vec::new();
        for (shard_id, shard) in shards_data.iter().enumerate() {
            let amount = *shard_recv_amount.get(&shard_id).unwrap_or(&0);
            let has_payers = !shard.payer_amounts.is_empty();
            if amount <= 0 && !has_payers {
                continue;
            }
            events.push((shard_id, amount));
        }
        events
    }

    #[allow(clippy::too_many_arguments)]
    fn apply_event(
        ups: &mut UserProfileService,
        symbol: i32,
        action: OrderAction,
        payer_amounts: &BTreeMap<i64, i64>,
        receiver_notionals: &BTreeMap<i64, i64>,
        shard_recv_amount: i64,
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
    ) -> BTreeMap<i64, i64> {
        for (&uid, &fee) in payer_amounts {
            Self::settle_funding_fee(ups, symbol, action, uid, fee, true, spec, currency_spec);
        }

        if shard_recv_amount <= 0 || receiver_notionals.is_empty() {
            return BTreeMap::new();
        }
        let receiver_fees = distribute_remainder_by_one(shard_recv_amount, receiver_notionals);
        for (&uid, &fee) in &receiver_fees {
            if fee == 0 {
                continue;
            }
            Self::settle_funding_fee(ups, symbol, action, uid, fee, false, spec, currency_spec);
        }
        receiver_fees
    }

    #[allow(clippy::too_many_arguments)]
    fn settle_funding_fee(
        ups: &mut UserProfileService,
        symbol: i32,
        action: OrderAction,
        uid: i64,
        fee: i64,
        is_payer: bool,
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
    ) {
        let Some(user) = ups.get_mut(uid) else {
            return;
        };
        if user.user_status != UserStatus::Active {
            return;
        }

        let position_side = if is_payer { action } else { action.opposite() };
        let signed_fee = if is_payer { -fee } else { fee };

        let primary_matches =
            user.positions.get(&symbol).is_some_and(|p| p.direction.is_same_as_action(position_side));
        let lookup_symbol = if primary_matches { symbol } else { -symbol };

        let has_active_position = user
            .positions
            .get(&lookup_symbol)
            .is_some_and(|p| p.open_volume > 0 && p.direction.is_same_as_action(position_side));

        if has_active_position {
            user.positions.get_mut(&lookup_symbol).expect("checked present above").profit += signed_fee;
        } else {
            let scaled_fee = arithmetic::size_price_to_currency_scale(
                signed_fee,
                spec.base_scale_k,
                spec.quote_scale_k,
                currency_spec.currency_scale_k,
            );
            user.add_to_account(spec.quote_currency, scaled_fee);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::margin_mode::MarginMode;
    use crate::core::common::position_direction::PositionDirection;
    use crate::core::common::symbol_position_record::SymbolPositionRecord;
    use crate::core::common::symbol_type::SymbolType;

    const SYMBOL: i32 = 500;
    const BASE: i32 = 10;
    const QUOTE: i32 = 20;

    fn spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        }
    }

    fn currency_spec() -> CoreCurrencySpecification {
        CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() }
    }

    fn ups_with_user(uid: i64) -> UserProfileService {
        let mut ups = UserProfileService::new();
        assert_eq!(ups.add_empty_user_profile(uid), CommandResultCode::Success);
        ups
    }

    fn position(uid: i64, direction: PositionDirection, open_volume: i64) -> SymbolPositionRecord {
        SymbolPositionRecord {
            direction,
            open_volume,
            ..SymbolPositionRecord::new(uid, SYMBOL, QUOTE, MarginMode::Isolated, 1)
        }
    }

    #[test]
    fn collect_input_payer_side_computes_exact_fee_and_skips_zero_fee() {
        let mut ups = ups_with_user(1);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 5, 1000);
        assert_eq!(shard.payer_amounts.get(&1), Some(&5));
        assert!(shard.receiver_notionals.is_empty());
    }

    #[test]
    fn collect_input_payer_side_skips_when_computed_fee_not_positive() {
        let mut ups = ups_with_user(1);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 0, 1000);
        assert!(shard.payer_amounts.is_empty());
    }

    #[test]
    fn collect_input_receiver_side_records_raw_notional_not_fee() {
        let mut ups = ups_with_user(2);
        ups.get_mut(2).unwrap().positions.insert(SYMBOL, position(2, PositionDirection::Short, 100));
        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 5, 1000);
        assert_eq!(shard.receiver_notionals.get(&2), Some(&1000));
        assert!(shard.payer_amounts.is_empty());
    }

    #[test]
    fn collect_input_skips_flat_and_missing_positions_and_inactive_users() {
        let mut ups = ups_with_user(1);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Empty, 0));
        assert_eq!(ups.add_empty_user_profile(2), CommandResultCode::Success);
        assert_eq!(ups.add_empty_user_profile(3), CommandResultCode::Success);
        ups.get_mut(3).unwrap().positions.insert(SYMBOL, position(3, PositionDirection::Long, 100));
        ups.get_mut(3).unwrap().user_status = crate::core::common::user_status::UserStatus::Suspended;

        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 5, 1000);
        assert!(shard.payer_amounts.is_empty());
        assert!(shard.receiver_notionals.is_empty());
    }

    #[test]
    fn collect_input_hedge_processes_both_long_and_short_legs() {
        let mut ups = ups_with_user(1);
        ups.get_mut(1).unwrap().position_mode = PositionMode::Hedge;
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        ups.get_mut(1).unwrap().positions.insert(-SYMBOL, position(1, PositionDirection::Short, 100));

        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 5, 1000);
        assert_eq!(shard.payer_amounts.get(&1), Some(&5), "the long leg must go into the payer pool");
        assert_eq!(shard.receiver_notionals.get(&1), Some(&1000), "HEDGE short leg (-symbol) must be settled into the receiver pool");
    }

    #[test]
    fn collect_input_hedge_collects_lone_short_leg_at_negative_symbol() {
        let mut ups = ups_with_user(2);
        ups.get_mut(2).unwrap().position_mode = PositionMode::Hedge;
        ups.get_mut(2).unwrap().positions.insert(-SYMBOL, position(2, PositionDirection::Short, 100));

        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 5, 1000);
        assert_eq!(shard.receiver_notionals.get(&2), Some(&1000), "a lone short leg must also be settled");
        assert!(shard.payer_amounts.is_empty());
    }

    #[test]
    fn collect_input_oneway_ignores_negative_symbol_key() {
        let mut ups = ups_with_user(3);
        ups.get_mut(3).unwrap().positions.insert(-SYMBOL, position(3, PositionDirection::Short, 100));

        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 5, 1000);
        assert!(shard.receiver_notionals.is_empty(), "ONEWAY must not read -symbol");
        assert!(shard.payer_amounts.is_empty());
    }

    #[test]
    fn build_matcher_events_single_shard_full_pay_goes_to_single_shard_no_remainder() {
        let mut shard = FundingPaymentAndRecvNotional::default();
        shard.payer_amounts.insert(1, 100);
        shard.receiver_notionals.insert(2, 500);
        let events = FundingFeeCommandProcessor::build_matcher_events(&[shard]);
        assert_eq!(events, vec![(0, 100)], "single shard: its own recv-notional share is always 100%, no truncation loss");
    }

    #[test]
    fn build_matcher_events_no_event_when_either_pool_empty() {
        let mut only_payers = FundingPaymentAndRecvNotional::default();
        only_payers.payer_amounts.insert(1, 100);
        assert!(FundingFeeCommandProcessor::build_matcher_events(&[only_payers]).is_empty(), "receiver pool empty -> no event");

        let mut only_receivers = FundingPaymentAndRecvNotional::default();
        only_receivers.receiver_notionals.insert(2, 500);
        assert!(
            FundingFeeCommandProcessor::build_matcher_events(&[only_receivers]).is_empty(),
            "payer pool empty -> no event"
        );

        assert!(FundingFeeCommandProcessor::build_matcher_events(&[]).is_empty());
    }

    #[test]
    fn build_matcher_events_multi_shard_pro_rata_by_receiver_notional_with_deterministic_remainder() {
        let mut shard0 = FundingPaymentAndRecvNotional::default();
        shard0.payer_amounts.insert(1, 100);
        shard0.receiver_notionals.insert(10, 30);
        let mut shard1 = FundingPaymentAndRecvNotional::default();
        shard1.receiver_notionals.insert(20, 70);
        let events = FundingFeeCommandProcessor::build_matcher_events(&[shard0, shard1]);
        assert_eq!(events, vec![(0, 30), (1, 70)]);
    }

    #[test]
    fn build_matcher_events_multi_shard_remainder_goes_to_lowest_shard_id() {
        let mut shard0 = FundingPaymentAndRecvNotional::default();
        shard0.payer_amounts.insert(1, 10);
        shard0.receiver_notionals.insert(10, 1);
        let mut shard1 = FundingPaymentAndRecvNotional::default();
        shard1.receiver_notionals.insert(20, 1);
        let mut shard2 = FundingPaymentAndRecvNotional::default();
        shard2.receiver_notionals.insert(30, 1);
        let events = FundingFeeCommandProcessor::build_matcher_events(&[shard0, shard1, shard2]);
        assert_eq!(events, vec![(0, 4), (1, 3), (2, 3)], "the 1-unit remainder must go to the lowest shard_id, shard 0");
    }

    #[test]
    fn apply_event_one_payer_one_receiver_full_transfer_is_zero_sum() {
        let mut ups = ups_with_user(1);
        assert_eq!(ups.add_empty_user_profile(2), CommandResultCode::Success);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        ups.get_mut(2).unwrap().positions.insert(SYMBOL, position(2, PositionDirection::Short, 100));

        let payer_amounts = BTreeMap::from([(1i64, 50i64)]);
        let receiver_notionals = BTreeMap::from([(2i64, 1000i64)]);
        FundingFeeCommandProcessor::apply_event(
            &mut ups,
            SYMBOL,
            OrderAction::Bid,
            &payer_amounts,
            &receiver_notionals,
            50,
            &spec(),
            &currency_spec(),
        );

        let payer_profit = ups.get(1).unwrap().positions.get(&SYMBOL).unwrap().profit;
        let receiver_profit = ups.get(2).unwrap().positions.get(&SYMBOL).unwrap().profit;
        assert_eq!(payer_profit, -50, "payer is debited exactly 50");
        assert_eq!(receiver_profit, 50, "receiver receives the full 50");
        assert_eq!(payer_profit + receiver_profit, 0, "zero-sum: payer loss == receiver gain");
    }

    #[test]
    fn apply_event_multiple_receivers_pro_rata_with_deterministic_remainder_uid() {
        let mut ups = ups_with_user(1);
        for uid in [10i64, 20, 30] {
            assert_eq!(ups.add_empty_user_profile(uid), CommandResultCode::Success);
        }
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        ups.get_mut(10).unwrap().positions.insert(SYMBOL, position(10, PositionDirection::Short, 100));
        ups.get_mut(20).unwrap().positions.insert(SYMBOL, position(20, PositionDirection::Short, 100));
        ups.get_mut(30).unwrap().positions.insert(SYMBOL, position(30, PositionDirection::Short, 100));

        let payer_amounts = BTreeMap::from([(1i64, 10i64)]);
        let receiver_notionals = BTreeMap::from([(10i64, 1i64), (20i64, 1i64), (30i64, 1i64)]);
        FundingFeeCommandProcessor::apply_event(
            &mut ups,
            SYMBOL,
            OrderAction::Bid,
            &payer_amounts,
            &receiver_notionals,
            10,
            &spec(),
            &currency_spec(),
        );

        let p10 = ups.get(10).unwrap().positions.get(&SYMBOL).unwrap().profit;
        let p20 = ups.get(20).unwrap().positions.get(&SYMBOL).unwrap().profit;
        let p30 = ups.get(30).unwrap().positions.get(&SYMBOL).unwrap().profit;
        assert_eq!((p10, p20, p30), (4, 3, 3), "the 1-unit remainder must go to the lowest uid, 10 -- deterministic and predictable");
        assert_eq!(p10 + p20 + p30, 10, "total receiver gain must equal shard_recv_amount");
        let payer_profit = ups.get(1).unwrap().positions.get(&SYMBOL).unwrap().profit;
        assert_eq!(payer_profit + p10 + p20 + p30, 0, "zero-sum conservation");
    }

    #[test]
    fn apply_event_payer_or_receiver_pool_empty_produces_no_settlement() {
        let mut ups = ups_with_user(1);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        let empty: BTreeMap<i64, i64> = BTreeMap::new();
        let receivers = BTreeMap::from([(2i64, 1000i64)]);

        FundingFeeCommandProcessor::apply_event(
            &mut ups,
            SYMBOL,
            OrderAction::Bid,
            &empty,
            &receivers,
            0,
            &spec(),
            &currency_spec(),
        );
        assert_eq!(ups.get(1).unwrap().positions.get(&SYMBOL).unwrap().profit, 0, "no payer_amounts -> no debit");
    }

    #[test]
    fn apply_event_position_closed_between_r1_and_r2_routes_fee_to_accounts_not_ghost_position() {
        let mut ups = ups_with_user(1);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        let p = ups.get_mut(1).unwrap().positions.get_mut(&SYMBOL).unwrap();
        p.open_volume = 0;
        p.direction = PositionDirection::Empty;

        let payer_amounts = BTreeMap::from([(1i64, 42i64)]);
        let empty: BTreeMap<i64, i64> = BTreeMap::new();
        FundingFeeCommandProcessor::apply_event(
            &mut ups, SYMBOL, OrderAction::Bid, &payer_amounts, &empty, 0, &spec(), &currency_spec(),
        );

        let up = ups.get(1).unwrap();
        assert_eq!(up.positions.get(&SYMBOL).unwrap().profit, 0, "a ghost position must not be written to");
        assert_eq!(up.account(QUOTE), -42, "fee follows the money: debited directly into accounts[quote_currency]");
    }

    #[test]
    fn apply_event_receiver_position_closed_between_r1_and_r2_credits_accounts() {
        let mut ups = ups_with_user(1);
        assert_eq!(ups.add_empty_user_profile(2), CommandResultCode::Success);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));

        let payer_amounts = BTreeMap::from([(1i64, 50i64)]);
        let receiver_notionals = BTreeMap::from([(2i64, 1000i64)]);
        FundingFeeCommandProcessor::apply_event(
            &mut ups, SYMBOL, OrderAction::Bid, &payer_amounts, &receiver_notionals, 50, &spec(), &currency_spec(),
        );

        let receiver = ups.get(2).unwrap();
        assert!(receiver.positions.get(&SYMBOL).is_none(), "must not create a position out of thin air");
        assert_eq!(receiver.account(QUOTE), 50, "a ghost receiver's gain is credited directly to the account");
    }

    #[test]
    fn apply_event_hedge_dual_direction_lookup_falls_back_to_negative_symbol() {
        let mut ups = ups_with_user(1);
        let neg_symbol = -SYMBOL;
        ups.get_mut(1).unwrap().positions.insert(
            neg_symbol,
            SymbolPositionRecord {
                direction: PositionDirection::Short,
                open_volume: 100,
                ..SymbolPositionRecord::new(1, neg_symbol, QUOTE, MarginMode::Isolated, 1)
            },
        );

        let payer_amounts = BTreeMap::from([(1i64, 30i64)]);
        let empty: BTreeMap<i64, i64> = BTreeMap::new();
        FundingFeeCommandProcessor::apply_event(
            &mut ups, SYMBOL, OrderAction::Ask, &payer_amounts, &empty, 0, &spec(), &currency_spec(),
        );

        assert_eq!(
            ups.get(1).unwrap().positions.get(&neg_symbol).unwrap().profit,
            -30,
            "HEDGE: SYMBOL misses the active position, falls back to -SYMBOL which hits and is credited"
        );
        assert_eq!(ups.get(1).unwrap().account(QUOTE), 0, "accounts must not change when an active position is hit");
    }
}
