//! 对应 Java: `exchange.core2.core.processors.LoanRatePricingProcessor`（`TwoStepCommandProcessor`
//! 的一个薄实例，全文移植）。`REPRICE_LOAN_RATES` 两步处理器：按全局利用率重定价浮动利率。
//!
//! 参考文档 §4.2："Reprice pipeline — TwoStepCommandProcessor pattern"。三段式：
//! - **R1** [`Self::collect_input`]：每 shard 把本地 `loanPoolBorrowed`/`loanPoolAvailable`
//!   写进同一张 map，靠 key 符号区分——borrowed 存 `key = currency`（≥0）、available 存
//!   `key = !currency`（按位取反，非负 currency id 取反恒为负数，故不会与 borrowed 的 key 冲突）。
//! - **merge** [`Self::build_matcher_events`]：跨 shard 求和后，按币种算 `util =
//!   FloatingRateModel::utilization_bps(totalBorrowed, totalAvailable)`，currency **升序**产出
//!   `(currency, util_bps)` 事件（跨节点确定性，对应 Java `currencies.toSortedArray()`）。
//! - **R2** [`Self::apply_event`]：每个事件先 `advance_accumulator`（用旧生效利率结清
//!   `[last_reprice_ts, tick_ts)` 这段旧区间）再 `reprice_currency`（把 util 过曲线写新生效利率）
//!   ——顺序不可颠倒，否则旧区间会被错误地按新利率结算（`FloatingRateModel::advance_accumulator`
//!   文档同一处强调）。`set_last_reprice_ts` **不**在这里调用——Java 版本是在
//!   `RiskEngine.handlerRiskRelease`（R2 编排层，`:906-913`）事件循环**之后、只调一次**，本移植
//!   镜像该结构，由 `RiskEngine::handler_risk_release` 负责（参考文档 §4.2 最后一段）。
//!
//! # 事件载体的移植偏差（Task 8 brief 明确允许的选择之一，需记录）
//! Java 版本靠 `OrderCommand.commonByShard[..].amounts`（R1 写）+ `OrderCommand.matcherEvent`
//! 单链表（merge 写、R2 消费，事件类型 `MatcherEventType.LOAN_REPRICE_EVENT`）在 R1→ME(merge)→R2
//! 三段之间传递数据。本移植的 `MatcherEventType`/`MatcherTradeEvent` 是撮合引擎的共享类型
//! （`risk_engine.rs::handle_matcher_event_margin` 等多处对其做穷尽 `match`），且
//! `MatchingEngineRouter`（ME 层）当前设计上不持有 `LoanService`——都不适合塞一个和撮合无关的
//! "reprice 事件"变体进去（会波及期货结算的穷尽匹配、且 ME 层拿不到 loan 池数据）。因此改用
//! `OrderCommand.loan_reprice_events: Vec<(i32, i64)>`（`(currency, util_bps)`）作为本命令类型
//! 专属的 R1→R2 载体：`RiskEngine::pre_process_command` 的 `is_non_trading()` 分支里
//! **一次性**完成 R1 collect_input + merge build_matcher_events（单 shard 下"跨 shard 求和"是恒等
//! 操作，但下面两个函数仍按"多 shard 输入"的形状实现，保留结构，供将来多 shard 时对齐 Java 的
//! 真实归并语义），结果写进 `cmd.loan_reprice_events`；`RiskEngine::handler_risk_release` 读取并
//! 消费它，驱动 R2。数值/顺序/语义与 Java 完全一致，只是数据搬运的物理载体不同。
use std::collections::{BTreeMap, BTreeSet};

use crate::core::processors::loan::loan_service::LoanService;
use crate::core::processors::loan::rate::floating_rate_model::FloatingRateModel;

/// 无状态处理器——所有方法都是关联函数，不持有任何字段（Java 版本持有 `riskEngine`/
/// `eventsHelper` 两个可选引用只是为了在 R1/R2 实例与 merge 实例之间做运行时门禁校验，本移植
/// 单线程同步调用模型不需要这层门禁，调用方按阶段直接调用对应方法即可）。
pub struct LoanRatePricingProcessor;

impl LoanRatePricingProcessor {
    /// R1：对应 Java `collectInput`（`LoanRatePricingProcessor.java:35-53`）。读
    /// `loan_service` 的两个池子桶，编码进一张 `BTreeMap<i32,i64>`——`borrowed` 存在
    /// `key = currency`，`available` 存在 `key = !currency`。跳过值为 0 的条目（对应 Java `if (v
    /// != 0)` 守卫，纯粹是稀疏化，不影响正确性——merge 阶段缺失键按 0 处理）。
    pub fn collect_input(loan_service: &LoanService) -> BTreeMap<i32, i64> {
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

    /// merge：对应 Java `buildMatcherEvents`（`:56-91`）。`shard_data` 是每个 shard 各自的
    /// [`Self::collect_input`] 输出（本移植单 shard 场景下调用方传长度为 1 的切片）；按 key 符号
    /// 拆回 borrowed/available 并跨 shard 累加，再按币种算利用率，currency **升序**
    /// （`BTreeSet` 天然有序迭代）输出 `(currency, util_bps)`。空池（没有任何 currency）返回空
    /// `Vec`——对应 Java `currencies.isEmpty()` 时 `cmd.matcherEvent = null` 的效果：调用方
    /// （`RiskEngine::handler_risk_release`）据此判断"本次 reprice 无事可做"，连
    /// `set_last_reprice_ts` 都不推进（详见该函数文档，镜像 Java `handlerRiskRelease` 顶层
    /// `mte == null` 早退）。
    pub fn build_matcher_events(shard_data: &[BTreeMap<i32, i64>]) -> Vec<(i32, i64)> {
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

    /// R2 per-event：对应 Java `applyEvent`（`:93-104`）。**顺序不可颠倒**：先
    /// `advance_accumulator`（用推进前的旧生效利率结清旧区间）再 `reprice_currency`（写新生效
    /// 利率）。`set_last_reprice_ts` 不在这里调用——由调用方在所有事件处理完后统一调用一次
    /// （对应 Java `RiskEngine.handlerRiskRelease` 事件循环外的 `setLastRepriceTs`，`:906-913`）。
    pub fn apply_event(loan_service: &mut LoanService, currency: i32, util_bps: i64, tick_ts: i64) {
        loan_service.floating_rate.advance_accumulator(currency, tick_ts);
        loan_service.floating_rate.reprice_currency(currency, util_bps);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn loan_service_with_pools(entries: &[(i32, i64, i64)]) -> LoanService {
        // entries: (currency, borrowed, available)
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

    // ---- R1 collect_input: key-sign encoding ----

    #[test]
    fn collect_input_encodes_borrowed_at_currency_and_available_at_bitwise_complement() {
        let s = loan_service_with_pools(&[(5, 8_000, 2_000)]);
        let shard_data = LoanRatePricingProcessor::collect_input(&s);
        assert_eq!(shard_data.get(&5), Some(&8_000)); // borrowed @ key = currency
        assert_eq!(shard_data.get(&!5), Some(&2_000)); // available @ key = !currency
        assert_eq!(shard_data.len(), 2);
    }

    #[test]
    fn collect_input_skips_zero_valued_buckets() {
        let mut s = LoanService::new();
        s.loan_pool_borrowed.insert(5, 0); // explicit zero entry, must not be encoded
        s.loan_pool_available.insert(5, 100);
        let shard_data = LoanRatePricingProcessor::collect_input(&s);
        assert_eq!(shard_data.get(&5), None);
        assert_eq!(shard_data.get(&!5), Some(&100));
    }

    #[test]
    fn collect_input_no_collision_across_several_currencies() {
        let s = loan_service_with_pools(&[(0, 100, 50), (1, 200, 0), (2, 0, 300)]);
        let shard_data = LoanRatePricingProcessor::collect_input(&s);
        assert_eq!(shard_data.get(&0), Some(&100));
        assert_eq!(shard_data.get(&!0), Some(&50));
        assert_eq!(shard_data.get(&1), Some(&200));
        assert_eq!(shard_data.get(&2), None); // borrowed=0 -> skipped
        assert_eq!(shard_data.get(&!2), Some(&300));
    }

    // ---- merge build_matcher_events: util computation + ascending order ----

    #[test]
    fn build_matcher_events_single_currency_computes_correct_utilization() {
        let s = loan_service_with_pools(&[(7, 8_000, 2_000)]); // 80% util
        let shard_data = LoanRatePricingProcessor::collect_input(&s);
        let events = LoanRatePricingProcessor::build_matcher_events(&[shard_data]);
        assert_eq!(events, vec![(7, 8_000)]);
    }

    #[test]
    fn build_matcher_events_sums_across_multiple_shard_maps() {
        // Two "shards" each contributing half the pool for the same currency.
        let shard_a = LoanRatePricingProcessor::collect_input(&loan_service_with_pools(&[(3, 4_000, 1_000)]));
        let shard_b = LoanRatePricingProcessor::collect_input(&loan_service_with_pools(&[(3, 4_000, 1_000)]));
        let events = LoanRatePricingProcessor::build_matcher_events(&[shard_a, shard_b]);
        // total borrowed=8000, available=2000 -> util=8000bps, same as single-shard equivalent.
        assert_eq!(events, vec![(3, 8_000)]);
    }

    #[test]
    fn build_matcher_events_multiple_currencies_emitted_in_ascending_order() {
        let s = loan_service_with_pools(&[(9, 100, 100), (2, 100, 100), (5, 100, 100)]);
        let shard_data = LoanRatePricingProcessor::collect_input(&s);
        let events = LoanRatePricingProcessor::build_matcher_events(&[shard_data]);
        let currencies: Vec<i32> = events.iter().map(|&(c, _)| c).collect();
        assert_eq!(currencies, vec![2, 5, 9], "currency 必须升序，跨副本确定性");
    }

    #[test]
    fn build_matcher_events_empty_pool_yields_no_events() {
        let s = LoanService::new();
        let shard_data = LoanRatePricingProcessor::collect_input(&s);
        let events = LoanRatePricingProcessor::build_matcher_events(&[shard_data]);
        assert!(events.is_empty());
    }

    #[test]
    fn build_matcher_events_currency_present_only_via_available_or_only_via_borrowed_still_emits() {
        // currency 4 has only borrowed, currency 6 has only available.
        let s = loan_service_with_pools(&[(4, 500, 0), (6, 0, 500)]);
        let shard_data = LoanRatePricingProcessor::collect_input(&s);
        let events = LoanRatePricingProcessor::build_matcher_events(&[shard_data]);
        assert_eq!(events, vec![(4, FloatingRateModel::utilization_bps(500, 0)), (6, FloatingRateModel::utilization_bps(0, 500))]);
    }

    // ---- R2 apply_event: advance_accumulator strictly before reprice_currency ----

    #[test]
    fn apply_event_settles_old_interval_at_old_rate_before_repricing() {
        let mut s = LoanService::new();
        let cur = 3;
        s.floating_rate.last_reprice_ts = 1_000;
        s.floating_rate.current_rate_bps.insert(cur, 300); // old rate 3%

        LoanRatePricingProcessor::apply_event(&mut s, cur, 9_000, 2_000); // util above kink -> new rate very different

        // Old 1000ms interval must have been settled at the OLD rate (300bps), not the new one.
        let acc = *s.floating_rate.acc_rate_bps_ms.get(&cur).unwrap();
        assert_eq!(acc, 300 * 1_000, "advance_accumulator must run before reprice_currency, settling at the old rate");

        // New rate must now reflect the curve applied to util=9000.
        let new_rate = s.floating_rate.current_rate_bps_or_base(cur);
        assert_ne!(new_rate as i64, 300);
        assert_eq!(new_rate as i64, s.floating_rate.curve_rate_bps(9_000));
    }

    #[test]
    fn apply_event_updates_current_rate_bps_per_curve_for_computed_utilization() {
        let mut s = loan_service_with_pools(&[(11, 8_000, 2_000)]); // util = 8000 bps (80%)
        let shard_data = LoanRatePricingProcessor::collect_input(&s);
        let events = LoanRatePricingProcessor::build_matcher_events(&[shard_data]);
        assert_eq!(events, vec![(11, 8_000)]);

        let expected_rate = s.floating_rate.curve_rate_bps(8_000);
        for (currency, util) in events {
            LoanRatePricingProcessor::apply_event(&mut s, currency, util, 5_000);
        }
        assert_eq!(s.floating_rate.current_rate_bps_or_base(11), expected_rate as i32);
    }
}
