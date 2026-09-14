//! 对应 Java `FundingFeeCommandProcessor`（两步处理器）：`SETTLE_FUNDINGFEES` 资金费零和结算——payer 池精确算费、receiver 池 pro-rata 分摊，两者恒等。
//! R1 collect_input 产出 payer_amounts/receiver_notionals；merge build_matcher_events 做两级 pro-rata 第一级；R2 apply_event 做第二级并经 settle_funding_fee 落账（活仓记 profit，ghost 仓缩放进 accounts[quote_currency]）。

use std::collections::BTreeMap;

use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::order_action::OrderAction;
use crate::core::common::position_mode::PositionMode;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::common::user_status::UserStatus;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::utils::core_arithmetic_utils as arithmetic;
use crate::core::utils::core_arithmetic_utils::distribute_remainder_by_one;

/// 对应 Java `Math.multiplyExact`：溢出 panic。
fn mul_exact(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 * b as i128).unwrap_or_else(|_| panic!("overflow: {a} * {b}"))
}

/// 对应 Java `FundingPaymentAndRecvNotional`：单 shard 一份，`uid -> fee`（payer 侧，R1 已算好精确值）+ `uid -> raw notional`（receiver 侧，费用留到 merge/R2 再算）。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FundingPaymentAndRecvNotional {
    pub payer_amounts: BTreeMap<i64, i64>,
    pub receiver_notionals: BTreeMap<i64, i64>,
}

/// 无状态处理器——所有方法都是关联函数，不持有任何字段。
pub struct FundingFeeCommandProcessor;

impl FundingFeeCommandProcessor {
    /// R1：对应 Java `collectInput`。前置门禁（`cmd.size<=0`/mark price 缺失）挪到调用方 [`crate::core::processors::risk_engine::RiskEngine::settle_funding_fees_collect`]；本函数只扫 ACTIVE 用户产出 payer/receiver 两个 map。
    /// 仓位遍历对齐 [`UserProfile::process_position_record`]：ONEWAY 只处理 `symbol`，HEDGE 追加处理 `-symbol` 空头腿——否则空头腿不进池、破坏零和（settle 侧已按 `-symbol` 结算，collect 须对称）。HEDGE 多空两腿方向恒相反，按 uid 键不会互相覆盖。
    pub fn collect_input(
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

    /// merge：对应 Java `buildMatcherEvents`——两级 pro-rata 的第一级：`total_pay`（跨 shard payer 费用求和）按各 shard 的 receiver notional 占比截断分配 + [`distribute_remainder_by_one`] 余数分配（shard-id 升序，确定性）。
    /// `total_pay==0 || total_recv_notional==0` → 返回空 `Vec`（无可结算的东西）。参与分配的判定统一用 `receiver_notionals` 非空（notional 恒 >0）。`amount<=0 且 payer_amounts 为空` 的 shard 跳过。
    pub fn build_matcher_events(shards_data: &[FundingPaymentAndRecvNotional]) -> Vec<(usize, i64)> {
        let total_pay: i64 = shards_data.iter().map(|s| s.payer_amounts.values().sum::<i64>()).sum();
        let total_recv_notional: i64 = shards_data.iter().map(|s| s.receiver_notionals.values().sum::<i64>()).sum();
        if total_pay == 0 || total_recv_notional == 0 {
            return Vec::new();
        }

        let mut weights: BTreeMap<usize, i64> = BTreeMap::new();
        for (shard_id, shard) in shards_data.iter().enumerate() {
            if !shard.receiver_notionals.is_empty() {
                weights.insert(shard_id, shard.receiver_notionals.values().sum());
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

    /// R2：对应 Java `applyEvent`——两级 pro-rata 的第二级：先无条件精确扣 `payer_amounts`（逐用户 [`Self::settle_funding_fee`]），再把 `shard_recv_amount` 按 `receiver_notionals` 占比二次截断分配（[`distribute_remainder_by_one`]，weights 换成 uid -> notional），`fee==0` 的用户跳过。
    /// `shard_recv_amount<=0 || receiver_notionals.is_empty()` → 直接返回（跳过第二级，但 payer 侧已无条件处理）。
    #[allow(clippy::too_many_arguments)]
    pub fn apply_event(
        ups: &mut UserProfileService,
        symbol: i32,
        action: OrderAction,
        payer_amounts: &BTreeMap<i64, i64>,
        receiver_notionals: &BTreeMap<i64, i64>,
        shard_recv_amount: i64,
        spec: &CoreSymbolSpecification,
        currency_spec: &CoreCurrencySpecification,
    ) {
        for (&uid, &fee) in payer_amounts {
            Self::settle_funding_fee(ups, symbol, action, uid, fee, true, spec, currency_spec);
        }

        if shard_recv_amount <= 0 || receiver_notionals.is_empty() {
            return;
        }
        let receiver_fees = distribute_remainder_by_one(shard_recv_amount, receiver_notionals);
        for (&uid, &fee) in &receiver_fees {
            if fee == 0 {
                continue;
            }
            Self::settle_funding_fee(ups, symbol, action, uid, fee, false, spec, currency_spec);
        }
    }

    /// 对应 Java `settleFundingFee`：payer/receiver 共用的落账逻辑。
    ///
    /// `position_side`：payer 用 `action` 本身，receiver 用 `action.opposite()`。`signed_fee`：payer 为负（扣），receiver 为正（加）。
    /// HEDGE 双向查找：先查 `symbol`，方向不符则退查 `-symbol`。活仓命中（`open_volume>0` 且方向一致）→ 直接 `profit += signed_fee`（同 scale，不缩放）；ghost 回落（未命中活仓）→ `size_price_to_currency_scale` 缩放进 `accounts[quote_currency]`，费跟着钱走。用户缺失或非 ACTIVE 直接跳过。
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

    // ---- R1 collect_input ----

    #[test]
    fn collect_input_payer_side_computes_exact_fee_and_skips_zero_fee() {
        let mut ups = ups_with_user(1);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        // action=Bid（多付空）: LONG 持仓与 action 同向 -> payer。notional=100*10=1000,
        // rate=5, rate_scale_k=1000 -> fee = trunc(1000*5/1000) = 5.
        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 5, 1000);
        assert_eq!(shard.payer_amounts.get(&1), Some(&5));
        assert!(shard.receiver_notionals.is_empty());
    }

    #[test]
    fn collect_input_payer_side_skips_when_computed_fee_not_positive() {
        let mut ups = ups_with_user(1);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        // notional=1000, rate=0 -> fee=0，不记入 payer_amounts（对应 Java `if (fundingFee > 0)`）。
        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 0, 1000);
        assert!(shard.payer_amounts.is_empty());
    }

    #[test]
    fn collect_input_receiver_side_records_raw_notional_not_fee() {
        let mut ups = ups_with_user(2);
        ups.get_mut(2).unwrap().positions.insert(SYMBOL, position(2, PositionDirection::Short, 100));
        // action=Bid: SHORT 持仓与 action 反向 -> receiver。记原始 notional=100*10=1000（不是 fee）。
        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 5, 1000);
        assert_eq!(shard.receiver_notionals.get(&2), Some(&1000));
        assert!(shard.payer_amounts.is_empty());
    }

    #[test]
    fn collect_input_skips_flat_and_missing_positions_and_inactive_users() {
        let mut ups = ups_with_user(1);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Empty, 0));
        assert_eq!(ups.add_empty_user_profile(2), CommandResultCode::Success); // no position on SYMBOL at all
        assert_eq!(ups.add_empty_user_profile(3), CommandResultCode::Success);
        ups.get_mut(3).unwrap().positions.insert(SYMBOL, position(3, PositionDirection::Long, 100));
        ups.get_mut(3).unwrap().user_status = crate::core::common::user_status::UserStatus::Suspended;

        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 5, 1000);
        assert!(shard.payer_amounts.is_empty());
        assert!(shard.receiver_notionals.is_empty());
    }

    #[test]
    fn collect_input_hedge_processes_both_long_and_short_legs() {
        // HEDGE 用户同时持 +symbol 多腿与 -symbol 空腿：两腿方向恒相反，必然一腿 payer、一腿 receiver。
        // 对应 Java UserProfile.processPositionRecord 在 HEDGE 下追加处理 -symbol（回归 collect 漏结空头腿的 bug）。
        let mut ups = ups_with_user(1);
        ups.get_mut(1).unwrap().position_mode = PositionMode::Hedge;
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        ups.get_mut(1).unwrap().positions.insert(-SYMBOL, position(1, PositionDirection::Short, 100));

        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 5, 1000);
        // action=Bid: LONG 同向 -> payer fee=trunc(1000*5/1000)=5；SHORT 反向 -> receiver notional=1000。
        assert_eq!(shard.payer_amounts.get(&1), Some(&5), "多腿必须进 payer 池");
        assert_eq!(shard.receiver_notionals.get(&1), Some(&1000), "HEDGE 空腿（-symbol）必须被结算进 receiver 池");
    }

    #[test]
    fn collect_input_hedge_collects_lone_short_leg_at_negative_symbol() {
        // 只有 -symbol 空腿的 HEDGE 用户：修复前 collect 只查 +symbol，会整条漏掉，破坏零和。
        let mut ups = ups_with_user(2);
        ups.get_mut(2).unwrap().position_mode = PositionMode::Hedge;
        ups.get_mut(2).unwrap().positions.insert(-SYMBOL, position(2, PositionDirection::Short, 100));

        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 5, 1000);
        assert_eq!(shard.receiver_notionals.get(&2), Some(&1000), "孤立空腿也必须被结算");
        assert!(shard.payer_amounts.is_empty());
    }

    #[test]
    fn collect_input_oneway_ignores_negative_symbol_key() {
        // ONEWAY 用户即便 map 里意外存在 -symbol 记录，也不得被读取（对齐 processPositionRecord 仅 HEDGE 追加 -symbol）。
        let mut ups = ups_with_user(3);
        // position_mode 默认 OneWay。
        ups.get_mut(3).unwrap().positions.insert(-SYMBOL, position(3, PositionDirection::Short, 100));

        let shard = FundingFeeCommandProcessor::collect_input(&ups, SYMBOL, 10, OrderAction::Bid, 5, 1000);
        assert!(shard.receiver_notionals.is_empty(), "ONEWAY 不得读取 -symbol");
        assert!(shard.payer_amounts.is_empty());
    }

    // ---- merge build_matcher_events ----

    #[test]
    fn build_matcher_events_single_shard_full_pay_goes_to_single_shard_no_remainder() {
        let mut shard = FundingPaymentAndRecvNotional::default();
        shard.payer_amounts.insert(1, 100);
        shard.receiver_notionals.insert(2, 500);
        let events = FundingFeeCommandProcessor::build_matcher_events(&[shard]);
        assert_eq!(events, vec![(0, 100)], "单 shard: 自己的 recv notional 占比恒 100%，无截断损失");
    }

    #[test]
    fn build_matcher_events_no_event_when_either_pool_empty() {
        let mut only_payers = FundingPaymentAndRecvNotional::default();
        only_payers.payer_amounts.insert(1, 100);
        assert!(FundingFeeCommandProcessor::build_matcher_events(&[only_payers]).is_empty(), "receiver 池为空 -> 无事件");

        let mut only_receivers = FundingPaymentAndRecvNotional::default();
        only_receivers.receiver_notionals.insert(2, 500);
        assert!(
            FundingFeeCommandProcessor::build_matcher_events(&[only_receivers]).is_empty(),
            "payer 池为空 -> 无事件"
        );

        assert!(FundingFeeCommandProcessor::build_matcher_events(&[]).is_empty());
    }

    #[test]
    fn build_matcher_events_multi_shard_pro_rata_by_receiver_notional_with_deterministic_remainder() {
        // shard 0: payer 100; receiver notional sum = 30
        // shard 1: payer 0;   receiver notional sum = 70
        // total_pay=100, total_recv_notional=100 -> shard0 trunc(100*30/100)=30, shard1 trunc(100*70/100)=70，整除无余数。
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
        // shard 0: payer 10; receiver notional 1
        // shard 1: payer 0;  receiver notional 1
        // shard 2: payer 0;  receiver notional 1
        // total_pay=10, total_recv_notional=3 -> trunc(10*1/3)=3 每 shard，distributed=9，remainder=1 -> shard 0（升序最小）多拿 1。
        let mut shard0 = FundingPaymentAndRecvNotional::default();
        shard0.payer_amounts.insert(1, 10);
        shard0.receiver_notionals.insert(10, 1);
        let mut shard1 = FundingPaymentAndRecvNotional::default();
        shard1.receiver_notionals.insert(20, 1);
        let mut shard2 = FundingPaymentAndRecvNotional::default();
        shard2.receiver_notionals.insert(30, 1);
        let events = FundingFeeCommandProcessor::build_matcher_events(&[shard0, shard1, shard2]);
        assert_eq!(events, vec![(0, 4), (1, 3), (2, 3)], "余数 1 单位必须分给 shard_id 升序最小的 shard 0");
    }

    // ---- R2 apply_event / settle_funding_fee ----

    #[test]
    fn apply_event_one_payer_one_receiver_full_transfer_is_zero_sum() {
        let mut ups = ups_with_user(1);
        assert_eq!(ups.add_empty_user_profile(2), CommandResultCode::Success);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100)); // payer
        ups.get_mut(2).unwrap().positions.insert(SYMBOL, position(2, PositionDirection::Short, 100)); // receiver

        let payer_amounts = BTreeMap::from([(1i64, 50i64)]);
        let receiver_notionals = BTreeMap::from([(2i64, 1000i64)]);
        FundingFeeCommandProcessor::apply_event(
            &mut ups,
            SYMBOL,
            OrderAction::Bid,
            &payer_amounts,
            &receiver_notionals,
            50, // shard_recv_amount == total_pay (single receiver gets it all)
            &spec(),
            &currency_spec(),
        );

        let payer_profit = ups.get(1).unwrap().positions.get(&SYMBOL).unwrap().profit;
        let receiver_profit = ups.get(2).unwrap().positions.get(&SYMBOL).unwrap().profit;
        assert_eq!(payer_profit, -50, "payer 精确扣 50");
        assert_eq!(receiver_profit, 50, "receiver 全额收到 50");
        assert_eq!(payer_profit + receiver_profit, 0, "零和：payer 损失 == receiver 收益");
    }

    #[test]
    fn apply_event_multiple_receivers_pro_rata_with_deterministic_remainder_uid() {
        let mut ups = ups_with_user(1); // payer
        for uid in [10i64, 20, 30] {
            assert_eq!(ups.add_empty_user_profile(uid), CommandResultCode::Success);
        }
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        ups.get_mut(10).unwrap().positions.insert(SYMBOL, position(10, PositionDirection::Short, 100));
        ups.get_mut(20).unwrap().positions.insert(SYMBOL, position(20, PositionDirection::Short, 100));
        ups.get_mut(30).unwrap().positions.insert(SYMBOL, position(30, PositionDirection::Short, 100));

        let payer_amounts = BTreeMap::from([(1i64, 10i64)]);
        // notionals 1:1:1 -> trunc(10*1/3)=3 each, distributed=9, remainder=1 -> uid=10 (升序最小) 拿到 +1.
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
        assert_eq!((p10, p20, p30), (4, 3, 3), "余数 1 单位必须分给 uid 升序最小的 10，确定性可预测");
        assert_eq!(p10 + p20 + p30, 10, "receiver 总收益必须等于 shard_recv_amount");
        let payer_profit = ups.get(1).unwrap().positions.get(&SYMBOL).unwrap().profit;
        assert_eq!(payer_profit + p10 + p20 + p30, 0, "零和守恒");
    }

    #[test]
    fn apply_event_payer_or_receiver_pool_empty_produces_no_settlement() {
        let mut ups = ups_with_user(1);
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        let empty: BTreeMap<i64, i64> = BTreeMap::new();
        let receivers = BTreeMap::from([(2i64, 1000i64)]);

        // shard_recv_amount<=0 -> 第二级分配完全跳过（即使 receiver_notionals 非空）。
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
        assert_eq!(ups.get(1).unwrap().positions.get(&SYMBOL).unwrap().profit, 0, "无 payer_amounts -> 无扣款");
    }

    #[test]
    fn apply_event_position_closed_between_r1_and_r2_routes_fee_to_accounts_not_ghost_position() {
        let mut ups = ups_with_user(1);
        // R1 时刻：用户在 SYMBOL 上有仓（模拟 collect_input 观察到的状态）。
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        // 模拟 R1/R2 之间该仓被平掉（open_volume 归零，direction 归 Empty，同真实平仓后的状态）。
        let p = ups.get_mut(1).unwrap().positions.get_mut(&SYMBOL).unwrap();
        p.open_volume = 0;
        p.direction = PositionDirection::Empty;

        let payer_amounts = BTreeMap::from([(1i64, 42i64)]);
        let empty: BTreeMap<i64, i64> = BTreeMap::new();
        FundingFeeCommandProcessor::apply_event(
            &mut ups, SYMBOL, OrderAction::Bid, &payer_amounts, &empty, 0, &spec(), &currency_spec(),
        );

        let up = ups.get(1).unwrap();
        assert_eq!(up.positions.get(&SYMBOL).unwrap().profit, 0, "ghost 仓不应被写入");
        assert_eq!(up.account(QUOTE), -42, "费跟着钱走：直接扣进 accounts[quote_currency]");
    }

    #[test]
    fn apply_event_receiver_position_closed_between_r1_and_r2_credits_accounts() {
        let mut ups = ups_with_user(1); // payer
        assert_eq!(ups.add_empty_user_profile(2), CommandResultCode::Success); // receiver, ghost by R2
        ups.get_mut(1).unwrap().positions.insert(SYMBOL, position(1, PositionDirection::Long, 100));
        // uid=2 从未在 SYMBOL 开过仓（等价于"已平仓"——两次查找 symbol/-symbol 都不命中活仓）。

        let payer_amounts = BTreeMap::from([(1i64, 50i64)]);
        let receiver_notionals = BTreeMap::from([(2i64, 1000i64)]);
        FundingFeeCommandProcessor::apply_event(
            &mut ups, SYMBOL, OrderAction::Bid, &payer_amounts, &receiver_notionals, 50, &spec(), &currency_spec(),
        );

        let receiver = ups.get(2).unwrap();
        assert!(receiver.positions.get(&SYMBOL).is_none(), "不应凭空建仓");
        assert_eq!(receiver.account(QUOTE), 50, "ghost receiver 的收益直接入账户");
    }

    #[test]
    fn apply_event_hedge_dual_direction_lookup_falls_back_to_negative_symbol() {
        // HEDGE：payer 在 -SYMBOL（空头腿）上持有匹配方向的仓，SYMBOL（多头腿）不存在或方向不符。
        let mut ups = ups_with_user(1);
        // action=Ask（空付多，payer 方向应为 Short）。SYMBOL 上没有仓，只在 -SYMBOL 上有 SHORT 仓。
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
            "HEDGE: SYMBOL 未命中活仓，回落到 -SYMBOL 命中并入账"
        );
        assert_eq!(ups.get(1).unwrap().account(QUOTE), 0, "命中活仓时不应改动 accounts");
    }
}
