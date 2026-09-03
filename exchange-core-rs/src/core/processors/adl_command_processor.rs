//! 对应 Java: `exchange.core2.core.processors.ADLCommandProcessor`（257 行，`TwoStepCommandProcessor`
//! 的一个实例）。`AUTO_DELEVERAGING` 两步处理器：R1 按 `risk_score` DESC 选盈利候选 + 预占
//! `pending_adl_size`；merge 按分值 best-of-N 消费出实际执行量；R2 关 counterparty 仓位 + 走原表
//! 对称释放 `pending_adl_size`。参考文档 §3、§11.1。
//!
//! # 事件载体的移植偏差（同 P5/P6 既有先例，Ruling P6-A/P6-C）
//! Java 用 `MatcherEventType::ADL_EVENT`（`matchedOrderUid`/`size` 承载 uid / 实际消费量）在
//! R1→ME(merge)→R2 之间传递数据，`OrderCommand.adlUserPositionsByShard[]`（按 shard 下标数组，
//! 每项单链表头）承载 R1 输出。本移植不扩 `MatcherEventType`（Ruling P6-A），改用
//! `OrderCommand.adl_user_positions: Vec<AdlUserPosition>`（R1 输出，单 shard 塌缩后退化为单个
//! 有序列表，见 `adl_user_position.rs` 模块文档）+ `OrderCommand.adl_events: Vec<(i64, i64)>`
//! （merge 输出：`(uid, exec_volume)`，空 = 全拒，对应 Java `REJECT` 事件）。由
//! `RiskEngine::adl_collect`（R1+merge 合并，单 shard 下"跨 shard 归并"是恒等操作，同
//! `if_takeover_collect`/`settle_funding_fees_collect` 先例）一次性完成，写入这两个字段；
//! `RiskEngine::adl_apply`（R2 apply+finalize 合并，同 `if_takeover_apply` 先例）消费。
//!
//! 本文件只落地**不触碰用户账户/UserProfileService**的纯选择 + 归并算法（选候选 / 排序 / 贪心分配
//! / merge 消费）——`RiskEngine::adl_collect`/`adl_apply` 负责账户结算落点（`pending_adl_size`
//! 写回、counterparty 平仓、taker 平仓、extra_margin 退款、profit 结算），同 `if_command_processor.rs`
//! 的三段式分工。
//!
//! # 无状态处理器
//! 同 `IfCommandProcessor`/`FundingFeeCommandProcessor` 先例：所有方法都是关联函数，不持有任何
//! 字段（Java 版本持有 `riskEngine`/`eventsHelper` 只是运行时门禁，单线程同步调用模型不需要）。
//!
//! # merge 的"克隆 vs 原表"——为什么本移植不需要真的 clone
//! Java `buildMatcherEvents`（`:111-115` 注释）强调 merge 的游标数组**必须 clone**
//! `cmd.adlUserPositionsByShard`：merge 循环会推进游标（`cursors[bestShard] = best.next`）、还会
//! 在部分消费某个候选时原地 `best.volume -= execSize`——若不 clone、直接复用原数组，会污染
//! R2 finalize 需要重新遍历的"原始 R1 表"（对称释放要按 R1 预占量释放，不是按 merge 实际消费量）。
//!
//! 本移植用 `Vec<AdlUserPosition>` + **只读迭代**取代链表 + 游标克隆：[`AdlCommandProcessor
//! ::build_matcher_events`] 从不修改传入的 `candidates` 切片，只在本地累加一个 `remaining`
//! 计数器、产出一份独立的 `(uid, exec_volume)` 列表——天然不存在"污染原表"的风险，不需要显式
//! clone 就已经满足"R2 finalize 读到的是 R1 原始预占量"这条不变式。
//!
//! 另外，Java 的"部分消费导致原地改 `.volume`"这条分支（`execSize < best.volume`）**只有在跨
//! 多个 shard 独立预占、总和超过 `remaining_size` 时才可能触发**——因为每个 shard 的 R1 各自拿
//! `cmd.size` 当满额度独立预占，互不知晓对方，merge 阶段才需要跨 shard 裁剪。单 shard 塌缩下
//! （Ruling P6-C）只有一份候选列表，R1 选取阶段本身已经用同一个 `remaining` 计数器顺序消费到 0
//! 为止（[`AdlCommandProcessor::collect_input`]），列表内所有 `volume` 之和天然
//! `<= remaining_size`；merge 阶段（[`AdlCommandProcessor::build_matcher_events`]）重新按同一个
//! `remaining_size` 走一遍同一列表，因此**每个被摸到的候选都会被完整消费**，"部分消费"分支在单
//! shard 下数学上不可达——不是本移植简化掉了这条分支，而是它在当前架构下本来就不会发生。
use crate::core::common::adl_user_position::AdlUserPosition;
use crate::core::common::order_action::OrderAction;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::processors::liquidation::liquidation_service::LiquidationService;

/// 无状态处理器——参见模块文档。
pub struct AdlCommandProcessor;

impl AdlCommandProcessor {
    /// R1：对应 Java `collectInput`（`:52-100`）——从候选池里选出本次 ADL 可摊派的仓位，按
    /// `risk_score` DESC（稳定排序，同分保持原相对序，对应 Java `sortThisByLong(...).reverseThis()`
    /// 是稳定排序，参考文档 §11.1）贪心分配，直至 `remaining_size` 耗尽或候选耗尽。
    ///
    /// 筛选条件（逐字对齐 Java `:60-70`）：`open_volume > 0`、`open_volume > pending_adl_size`
    /// （还有余量可摊派）、候选自身方向与 `action` **相反**（`!is_same_as_action`）、
    /// `unrealized_pnl(pos, bankruptcy_price) > 0`（按破产价估算仍有浮盈，只吃赚钱的仓位）。
    ///
    /// **不写回 `pending_adl_size`**——本函数是纯选择算法，不持有 `UserProfileService`，写回是
    /// 调用方（`RiskEngine::adl_collect`）的职责：用返回值里每个 [`AdlUserPosition::volume`]
    /// （= 本次预占量 `can_take`）去重新查活记录并 `+=`（见模块文档"事件载体的移植偏差"）。
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

        // 逐字复刻 Java `sortThisByLong(riskScore).reverseThis()`（`ADLCommandProcessor.java:70`）：
        // **升序稳定排序，再整体 reverse**。注意这**不等价于**直接降序稳定排序——`reverseThis()` 会
        // 把同分（tied）元素的相对序也一并反转（升序稳定后同分保持原序 [A,B]，reverse 后变 [B,A]），
        // 而直接降序稳定排序会保留 [A,B]。同分时二者选中的对手方不同，故必须照抄 Java 的两步式以
        // 保证跨实现 tie-break 一致（参考文档 §11.1）。只算一次分值，不在比较器里重复调用。
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

    /// merge：对应 Java `buildMatcherEvents`（`:102-165`）——单 shard 塌缩版（Ruling P6-C，见模块
    /// 文档"merge 的克隆 vs 原表"）。`candidates` 已经是 R1 按 `risk_score` DESC 排好的全局最优序
    /// 列表，因此"跨 shard best-of-N 挑最高分候选"退化为顺序遍历这一个列表：对每个候选取
    /// `exec = min(candidate.volume, remaining)`，`remaining` 归零即停。
    ///
    /// 返回 `(events, total_consumed)`：`events` 是 `(uid, exec_volume)` 列表（对应 Java 每消费一个
    /// 候选产出一个 `ADL_EVENT`），`total_consumed` 是实际消费总量（调用方用它改写 `cmd.size`，
    /// 对应 Java `cmd.size -= remaining`）。`candidates` 为空或 `remaining_size<=0` 时返回空
    /// `events`（对应 Java `buildRejectEvent()`）。
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
        // 分值相同（uid=1 与 uid=2 完全同构）时，Java `sortThisByLong().reverseThis()` 会**反转**
        // 同分元素的相对序：升序稳定后为 [uid1, uid2]，reverseThis 后为 [uid2, uid1]。故先扫到的
        // uid1 反而排在后面。这是逐字对齐 Java tie-break（不是直接降序稳定排序的"保留原序"）。
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
        // 单 shard 下：R1 已经用同一个 remaining 计数器顺序选到耗尽，merge 重新走一遍同一列表，
        // 每个候选都被完整消费——不存在"部分消费导致原地改 volume"的分支（见模块文档）。
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
