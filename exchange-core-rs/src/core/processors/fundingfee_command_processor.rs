//! 对应 Java `exchange.core2.core.processors.FundingFeeCommandProcessor`。
//!
//! SETTLE_FUNDINGFEES（永续合约资金费率结算）的两步处理器：先扫描该 symbol 下所有活跃用户的仓位，
//! 按方向分成"付方"（跟触发 `action` 同向，欠资金费）和"收方"（反向，应收资金费）两组，付方按
//! `rate`（`cmd.price`）算出精确应付金额，收方只记名义价值（`collect`，对应 Java R1 `collectInput`
//! + matcher stage `buildMatcherEvents` 的合并，其中 `build_matcher_events` 还要按收方名义价值占比
//! 把总付款额 pro-rata 分给各 shard，余数按 shard id 升序分配，保证跨节点确定性且零和守恒）；
//! `apply` 先扣付方精确金额，再把该 shard 应收的总额按收方名义价值占比 pro-rata 分给各收方（余数按
//! uid 升序分配），落到持仓的 `profit` 字段（若 R1→R2 之间仓位已平仓/变向则改记 `accounts`）
//! （对应 Java R2 `applyEvent`）。Rust 单实例无 shard，故 `build_matcher_events` 接收的 shard 数据切片
//! 通常只有一个元素，但仍保留切片形态以复刻 Java 的跨 shard pro-rata 分配语义。
//! HEDGE 持仓模式下同一 symbol 可能同时持有多/空两条腿（`symbol` 与 `-symbol`），两条腿都要单独结算。

use std::collections::BTreeMap;

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::fund_event::FundEventType;
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

/// 用 i128 做加总防中间溢出，再收窄回 i64；溢出（金额天文数字，实际不可能发生）时直接 panic。
fn sum_i64_checked<'a>(vals: impl Iterator<Item = &'a i64>) -> i64 {
    let s: i128 = vals.map(|&v| v as i128).sum();
    i64::try_from(s).unwrap_or_else(|_| panic!("overflow: funding notional sum {s}"))
}

/// 对应 Java `exchange.core2.core.common.FundingPaymentAndRecvNotional`：单个 shard 收集到的资金费率
/// 结算数据——`payer_amounts` 是 uid → 精确应付金额（已按 rate 算好），`receiver_notionals` 是
/// uid → 持仓名义价值（收方按此值 pro-rata 分账，尚未算出具体金额）。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FundingPaymentAndRecvNotional {
    pub payer_amounts: BTreeMap<i64, i64>,
    pub receiver_notionals: BTreeMap<i64, i64>,
}

/// 无状态标记类型，方法均为纯函数式的 `&self` 调用。
pub struct FundingFeeCommandProcessor;

impl TwoStepCommandProcessor for FundingFeeCommandProcessor {
    /// 对应 Java `collectInput`（扫描持仓分付方/收方）+ matcher stage `buildMatcherEvents`（pro-rata 分账）
    /// 的合并。前置校验：symbol 必须是永续合约、mark price 必须可用、`cmd.size`（rate 的 scale 分母）
    /// 必须为正。校验通过后把 `(payer_amounts, receiver_notionals, 本 shard 应收总额)` 写入
    /// `cmd.funding_fee_event`；若没有任何收方分到款项（`build_matcher_events` 返回空），则不写入，
    /// `apply` 阶段会因此直接跳过整个结算。
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

    /// 对应 Java R2 `applyEvent`：先按 `payer_amounts` 逐个精确扣付方，再把本 shard 应收总额按
    /// `receiver_notionals` pro-rata 分给收方（`apply_event` 内部处理），随后为每个涉及本次结算、且
    /// 仍持有该 symbol 活跃仓位的用户补一条 futures 结算事件；不再持有活跃仓位的（R1→R2 之间已平仓）
    /// 改发一条 spot 余额快照事件。若 `collect` 未写入 `funding_fee_event`（无收方/无付方），直接跳过。
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

        let order_id = cmd.order_id;
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
                    let ev = RiskEngine::spot_snapshot_event(
                        FundEventType::FundingfeeSettlement, order_id, up, spec.quote_currency, ctx.ssp, &currency_spec, symbol,
                    );
                    cmd.fund_events.push(ev);
                }
            }
        }
    }
}

impl FundingFeeCommandProcessor {
    /// 对应 Java `collectInput` 内的扫描逻辑：遍历所有 ACTIVE 用户，对该 symbol（ONEWAY 模式）或
    /// 该 symbol 与 `-symbol` 两条腿（HEDGE 模式）分别处理：方向与触发 `action` 相同的仓位算作付方，
    /// 精确应付金额 = `notional * rate / rate_scale_k`（`trunc_mul_div`，截断取整），金额为正才记入；
    /// 方向相反的仓位算作收方，只记原始名义价值（`open_volume * mark_price`），具体应收金额留到
    /// `build_matcher_events`/`apply_event` 里 pro-rata 分配。`open_volume == 0` 的空仓跳过。
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

    /// 对应 Java matcher stage `buildMatcherEvents`：先求跨 shard 的付款总额与收方名义价值总额，
    /// 任一为零则不产生任何事件（无需结算）；否则按各 shard 收方名义价值占总收方名义价值的比例，把
    /// 付款总额 pro-rata 分给各 shard（`distribute_remainder_by_one` 内部处理截断产生的余数，按
    /// shard id 升序分配，保证跨节点确定性）。只跳过既无应收份额、又无付方的 shard；有付方的 shard
    /// 即使本 shard 应收为 0 也要产出事件（否则该 shard 的付方扣款永远不会在 `apply` 里执行）。
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

    /// 对应 Java R2 `applyEvent` 主体：先逐个精确结算付方（`payer_amounts` 里的金额已经算好，直接扣）；
    /// 再把本 shard 应收总额（`shard_recv_amount`）按收方名义价值 `receiver_notionals` pro-rata 分配
    /// （`distribute_remainder_by_one` 处理余数，按 uid 升序分配，保证确定性），逐个结算收方；返回
    /// 实际分给每个收方的金额（用于调用方补发结算事件），跳过分到 0 的收方。
    /// `shard_recv_amount <= 0` 或收方为空时直接返回空 map，不结算任何收方。
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

    /// 对应 Java 私有 `settleFundingFee`：把一笔资金费（付方为负、收方为正）结到单个用户身上。
    /// 用户不存在或非 ACTIVE 状态直接跳过（R1→R2 之间被注销/挂起）。
    /// `lookup_symbol` 的选择处理 HEDGE 模式的双腿：先看 `symbol` 上的仓位方向是否与
    /// `position_side` 一致，一致则用 `symbol`，否则回落到 `-symbol`（多/空两条腿分别记账）。
    /// 若命中的仓位确实是"活跃仓位"（`open_volume > 0` 且方向匹配），费用记入该仓位的 `profit`
    /// 字段（后续随平仓结算）；否则说明 R1→R2 之间该仓位已被平掉或方向已变化，改为把费用按
    /// 币种精度换算后直接记入用户的 `accounts`（钱跟着走，不追着一个可能已不存在的仓位）。
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
