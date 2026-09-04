//! 对应 Java `ADLCommandProcessor`（`TwoStepCommandProcessor` 实例）。`AUTO_DELEVERAGING` 两步处理器：
//! R1 按 risk_score DESC 选盈利候选+预占 pending_adl_size，merge best-of-N 消费出执行量，R2 关
//! counterparty 仓位+对称释放（参考文档 §3、§11.1）。事件载体用 `adl_user_positions`/`adl_events`
//! 而非 Java `MatcherEventType::ADL_EVENT`（Ruling P6-A/P6-C），单 shard 下 merge 只读迭代取代克隆+游标。
use crate::core::common::adl_user_position::AdlUserPosition;
use crate::core::common::order_action::OrderAction;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::processors::liquidation::liquidation_service::LiquidationService;

/// 无状态处理器——参见模块文档。
pub struct AdlCommandProcessor;

impl AdlCommandProcessor {
    /// R1：对应 Java `collectInput`（`:52-100`）——按 risk_score DESC（稳定排序）贪心分配，筛选反向+浮盈候选，直至 remaining_size 耗尽；不写回 pending_adl_size（调用方职责）。
    pub fn collect_input(
        candidates: Vec<SymbolPositionRecord>,
        symbol: i32,
        action: OrderAction,
        bankruptcy_price: i64,
        remaining_size: i64,
    ) -> Vec<AdlUserPosition> {
        let filtered: Vec<SymbolPositionRecord> = candidates
            .into_iter()
            .filter(|pos| {
                pos.open_volume > 0
                    && pos.open_volume > pos.pending_adl_size
                    && !pos.direction.is_same_as_action(action)
                    && LiquidationService::unrealized_pnl(pos, bankruptcy_price) > 0
            })
            .collect();

        // 逐字复刻 Java `sortThisByLong(riskScore).reverseThis()`（`:70`）：升序稳定排序再整体 reverse，同分 tie-break 会反转相对序，不等价于直接降序稳定排序。
        let mut scored: Vec<(i64, SymbolPositionRecord)> =
            filtered.into_iter().map(|pos| (LiquidationService::risk_score(&pos, bankruptcy_price), pos)).collect();
        scored.sort_by(|a, b| a.0.cmp(&b.0)); // 升序稳定（对应 sortThisByLong）
        scored.reverse(); // 整体反转（对应 reverseThis，同分序也反转）

        let mut remaining = remaining_size;
        let mut out = Vec::new();
        for (score, pos) in &scored {
            if remaining <= 0 {
                break;
            }
            let available = pos.open_volume - pos.pending_adl_size;
            let can_take = available.min(remaining);
            out.push(AdlUserPosition { uid: pos.uid, symbol, direction: pos.direction, volume: can_take, score: *score });
            remaining -= can_take;
        }
        out
    }

    /// merge：对应 Java `buildMatcherEvents`（`:102-165`）——单 shard 塌缩版（Ruling P6-C），顺序遍历
    /// 已排序候选取 exec=min(volume,remaining)；返回 (events, total_consumed)，空/耗尽时返回空 events（对应 Java `buildRejectEvent()`）。
    pub fn build_matcher_events(candidates: &[AdlUserPosition], remaining_size: i64) -> (Vec<(i64, i64)>, i64) {
        let mut remaining = remaining_size;
        let mut events = Vec::new();
        for node in candidates {
            if remaining <= 0 {
                break;
            }
            let exec = node.volume.min(remaining);
            if exec <= 0 {
                continue;
            }
            events.push((node.uid, exec));
            remaining -= exec;
        }
        let consumed = remaining_size.max(0) - remaining.max(0);
        (events, consumed)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::margin_mode::MarginMode;
    use crate::core::common::position_direction::PositionDirection;

    fn candidate(uid: i64, direction: PositionDirection, open_volume: i64, open_price_sum: i64, open_init_margin_sum: i64, adl_eligibility: i64, pending_adl_size: i64) -> SymbolPositionRecord {
        let mut p = SymbolPositionRecord::new(uid, 100, 2, MarginMode::Isolated, 1);
        p.direction = direction;
        p.open_volume = open_volume;
        p.open_price_sum = open_price_sum;
        p.open_init_margin_sum = open_init_margin_sum;
        p.adl_eligibility = adl_eligibility;
        p.pending_adl_size = pending_adl_size;
        p
    }

    // ---- collect_input：筛选条件 ----

    #[test]
    fn collect_input_filters_out_same_direction_as_action() {
        // action=Bid（做多）触发 ADL 时，只吃 SHORT 候选（反向）；LONG 候选（同向）必须被过滤掉。
        let long_pos = candidate(1, PositionDirection::Long, 10, 500, 100, 100, 0); // bankruptcy=100 -> unrealizedPnl=(100*10-500)*1=500>0 但同向
        let short_pos = candidate(2, PositionDirection::Short, 10, 1500, 100, 100, 0); // unrealizedPnl=(100*10-1500)*-1=500>0 反向
        let picks = AdlCommandProcessor::collect_input(vec![long_pos, short_pos], 100, OrderAction::Bid, 100, 100);
        assert_eq!(picks.len(), 1);
        assert_eq!(picks[0].uid, 2);
    }

    #[test]
    fn collect_input_filters_out_non_positive_unrealized_pnl() {
        // SHORT 候选但按破产价已经不盈利（unrealizedPnl<=0）-> 过滤掉
        let losing_short = candidate(1, PositionDirection::Short, 10, 500, 100, 100, 0); // (100*10-500)*-1 = -500 <=0
        let picks = AdlCommandProcessor::collect_input(vec![losing_short], 100, OrderAction::Bid, 100, 100);
        assert!(picks.is_empty());
    }

    #[test]
    fn collect_input_filters_out_when_pending_adl_size_covers_open_volume() {
        // openVolume <= pendingADLSize -> 已经没有余量可摊派
        let fully_reserved = candidate(1, PositionDirection::Short, 10, 1500, 100, 100, 10);
        let picks = AdlCommandProcessor::collect_input(vec![fully_reserved], 100, OrderAction::Bid, 100, 100);
        assert!(picks.is_empty());
    }

    // ---- collect_input：risk_score 排序 + 贪心分配 ----

    #[test]
    fn collect_input_sorts_by_risk_score_descending() {
        // 两个 SHORT 候选，eligibility 不同 -> risk_score 不同，验证选取顺序按分值 DESC
        let low_score = candidate(1, PositionDirection::Short, 5, 750, 100, 10, 0); // eligibility=10
        let high_score = candidate(2, PositionDirection::Short, 5, 750, 100, 90, 0); // eligibility=90，其余相同 -> 分值更高
        let picks = AdlCommandProcessor::collect_input(vec![low_score, high_score], 100, OrderAction::Bid, 100, 3);
        assert_eq!(picks[0].uid, 2, "高分候选必须排第一个被选中");
    }

    #[test]
    fn collect_input_tie_break_reverses_input_order_like_java_reverse_this() {
        // 分值相同时 Java reverseThis 会反转相对序，先扫到的 uid1 反而排后——逐字对齐 tie-break。
        let a = candidate(1, PositionDirection::Short, 5, 750, 100, 50, 0);
        let b = candidate(2, PositionDirection::Short, 5, 750, 100, 50, 0);
        let picks = AdlCommandProcessor::collect_input(vec![a, b], 100, OrderAction::Bid, 100, 10);
        assert_eq!(picks.len(), 2);
        assert_eq!(picks[0].uid, 2, "reverseThis 把同分序反转：后扫到的 uid2 排第一");
        assert_eq!(picks[1].uid, 1);
    }

    #[test]
    fn collect_input_greedy_take_caps_at_min_available_and_remaining() {
        let big_candidate = candidate(1, PositionDirection::Short, 100, 15000, 100, 100, 0); // available=100
        let picks = AdlCommandProcessor::collect_input(vec![big_candidate], 100, OrderAction::Bid, 100, 30);
        assert_eq!(picks.len(), 1);
        assert_eq!(picks[0].volume, 30, "canTake = min(available=100, remaining=30) = 30");
    }

    #[test]
    fn collect_input_stops_once_remaining_exhausted() {
        let a = candidate(1, PositionDirection::Short, 5, 750, 100, 100, 0);
        let b = candidate(2, PositionDirection::Short, 5, 750, 100, 90, 0);
        let c = candidate(3, PositionDirection::Short, 5, 750, 100, 80, 0);
        // remaining=7: a(可用5)+b(可用2, 部分)后耗尽，c 完全不入选
        let picks = AdlCommandProcessor::collect_input(vec![a, b, c], 100, OrderAction::Bid, 100, 7);
        assert_eq!(picks.len(), 2);
        assert_eq!(picks[0].uid, 1);
        assert_eq!(picks[0].volume, 5);
        assert_eq!(picks[1].uid, 2);
        assert_eq!(picks[1].volume, 2, "部分消费：min(5, remaining=2)=2");
    }

    #[test]
    fn collect_input_remaining_non_positive_returns_empty() {
        let a = candidate(1, PositionDirection::Short, 5, 750, 100, 100, 0);
        let picks = AdlCommandProcessor::collect_input(vec![a], 100, OrderAction::Bid, 100, 0);
        assert!(picks.is_empty());
    }

    // ---- build_matcher_events：merge / cmd.size 改写 ----

    #[test]
    fn build_matcher_events_full_consumption_when_sum_covers_remaining() {
        let picks = vec![
            AdlUserPosition { uid: 1, symbol: 100, direction: PositionDirection::Short, volume: 5, score: 100 },
            AdlUserPosition { uid: 2, symbol: 100, direction: PositionDirection::Short, volume: 2, score: 90 },
        ];
        let (events, consumed) = AdlCommandProcessor::build_matcher_events(&picks, 7);
        assert_eq!(events, vec![(1, 5), (2, 2)]);
        assert_eq!(consumed, 7);
    }

    #[test]
    fn build_matcher_events_partial_candidate_list_consumes_only_available() {
        // 候选总量(5) < remaining_size(20) -> 只能消费到候选耗尽，不是全部 remaining
        let picks = vec![AdlUserPosition { uid: 1, symbol: 100, direction: PositionDirection::Short, volume: 5, score: 100 }];
        let (events, consumed) = AdlCommandProcessor::build_matcher_events(&picks, 20);
        assert_eq!(events, vec![(1, 5)]);
        assert_eq!(consumed, 5, "候选不够，实际消费 < 原始请求量，cmd.size 应改写为这个真实值");
    }

    #[test]
    fn build_matcher_events_empty_candidates_rejects() {
        let (events, consumed) = AdlCommandProcessor::build_matcher_events(&[], 10);
        assert!(events.is_empty());
        assert_eq!(consumed, 0);
    }

    // ---- end-to-end R1+merge（无账户/仓位部分，纯选择+归并算法）----

    #[test]
    fn end_to_end_r1_then_merge_consumes_exactly_what_r1_selected_in_single_shard() {
        // 单 shard 下每个候选都被完整消费，不存在"部分消费改 volume"分支（见模块文档）。
        let a = candidate(1, PositionDirection::Short, 5, 750, 100, 100, 0);
        let b = candidate(2, PositionDirection::Short, 5, 750, 100, 90, 0);
        let picks = AdlCommandProcessor::collect_input(vec![a, b], 100, OrderAction::Bid, 100, 8);
        assert_eq!(picks.iter().map(|p| p.volume).sum::<i64>(), 8);

        let (events, consumed) = AdlCommandProcessor::build_matcher_events(&picks, 8);
        assert_eq!(consumed, 8, "R1 已经选到刚好 8，merge 应当把全部候选完整消费掉，不裁剪任何一个");
        for (ev, pick) in events.iter().zip(picks.iter()) {
            assert_eq!(ev.1, pick.volume, "每个候选的 exec_volume 必须等于 R1 预占量，不是被 merge 部分裁剪后的值");
        }
    }
}
