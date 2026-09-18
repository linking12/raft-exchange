//! 对应 Java `LiquidationEngine`（`exchange.core2.core.processors.liquidation
//! .LiquidationEngine`，继承 `LiquidationScheduledService`）。
//!
//! 期货强平引擎，每个 RiskEngine 分片一个。**事件驱动、on-lane 检测**——强平检查跑在
//! 命令 apply 路径里，只读一致复制态，无跨线程竞态（这一点在 Rust 版同样成立：
//! [`LiquidationEngine::check_positions`]/[`LiquidationEngine::advance_liquidation`]
//! 都是同步函数，由调用方在 apply 路径上驱动，不持有独立线程/定时器）。
//!
//! **检测流程**：入口 [`LiquidationEngine::check_positions`]——`cmd.symbol >= 0` 靠
//! `symbol_to_users` 索引只查该 symbol 的持有者（targeted，价格/资金费触发）；
//! `cmd.symbol < 0`（`LIQUIDATION_SCAN`）全量整扫兜底。判定破产的仓位提交
//! `FORCE_LIQUIDATION`；强平命令 apply 后由 [`LiquidationEngine::advance_liquidation`]
//! 推进 FORCE→IF→ADL 状态机。
//!
//! **leader gate**：`check_positions`/`advance_liquidation` 用 `is_running` 门控，
//! follower no-op（Java 版用父类 `LiquidationScheduledService.isRunning()`，Rust
//! 版把该字段直接放在 `LiquidationEngine` 自身，因为调度线程骨架本身没有翻译，见
//! `scheduler.rs` 文件头）。流程态 [`crate::core::processors::liquidation::liquidation_flow::LiquidationFlow`]
//! 挂在 `SymbolPositionRecord.liquidation_flow`，纯内存、不进 snapshot/state hash——
//! 换届后新 leader 侧为空，残余仓被当作破产仓重发 FORCE 恢复。
//!
//! **索引维护/重建**：`symbol_to_users`（symbol → uid）在开仓/平仓 apply 时由所有
//! 节点确定性维护（不 gate）、不进 snapshot；快照恢复需要调用方重建（Java 侧经
//! `updateProvider` 重建，本文件未见等价的重建入口，调用方需自行保证）。
//!
//! **loan 集成**：`loan_liquidation_engine` 是现货借贷强平子域委托对象；
//! `check_positions` 末尾委托其 `check_loans`。
//!
//! Java 版另持有 `eventsHelper`（`FundEventsHelper`，经 leader-local ringbuffer 发送
//! `ApiSystemLiquidationNotify` best-effort 通知事件）；Rust 版未见对应的独立通知
//! 出口，强平/预警事件改为通过 `fund_events: &mut Vec<FundEvent>` 参数直接返回给
//! 调用方（见 `check_positions`/`check_user` 的签名），未走 Java 版那条单独的
//! bypass-raft 通知旁路。
use std::collections::{BTreeMap, BTreeSet};

use crate::core::common::last_price_cache_record::LastPriceCacheRecord;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::matcher_event_type::MatcherEventType;
use crate::core::common::fund_event::{FundEvent, FundEventType};
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;
use crate::core::common::position_direction::PositionDirection;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::common::user_profile::UserProfile;
use crate::core::processors::liquidation::command_submitter::CommandSubmitter;
use crate::core::processors::liquidation::liquidation_flow::{LiquidationFlow, LiquidationState};
use crate::core::processors::liquidation::liquidation_service::LiquidationService;
use crate::core::processors::loan::loan_liquidation_engine::LoanLiquidationEngine;
use crate::core::processors::loan::loan_service::LoanService;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::utils::core_arithmetic_utils::{
    calculate_deficit_after_liquidate, calculate_size_to_liquidate, mul_exact, size_price_to_currency_scale,
};

/// 无直接 Java 对应类型：Rust 版把 Java `checkIsolated`/`forceCrossLiquidation` 里
/// 隐式构造的「待强平仓位」信息（bankruptcy price + size）显式化为一个值类型，
/// 便于把检测（只读复制态）与写入（改 `position.liquidation_flow`、发命令）分成
/// 两个阶段——见 `check_user` 中先收集 `decisions` 再逐个 apply 的写法。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct LiquidationDecision {
    position_key: i32,
    bankruptcy_price: i64,
    size: i64,
}

/// 无直接 Java 对应类型：对应 Java `checkIsolated` 内联的三路分支（强平 / 越预警线
/// / 健康），Rust 版把它显式建模为枚举返回值。
enum IsolatedCheck {
    Liquidate(LiquidationDecision),
    Alert,
    Healthy,
}

/// 对应 Java `LiquidationEngine` 的核心字段（不含继承自 `LiquidationScheduledService`
/// 的调度线程骨架字段，那部分未翻译，见 `scheduler.rs`）。
/// - `symbol_to_users`：对应 Java `symbolToUsers`，symbol -> 持有者 uid 集合，
///   由所有节点在开仓/平仓 apply 时确定性维护，不进 snapshot。
/// - `is_running`：对应 Java 父类 `isRunning()` 的 leader gate。
/// - `command_submitter`：命令提交出口回调，对应 Java `commandSubmitter`。生成
///   FORCE/IF/ADL 时调 `submit(cmd)` → 回调。`ExchangeCore::new` 注册成塞进
///   `pending_commands` sink（单节点）/ raft 提交（集群）/ collector（单测）。
/// - `loan_liquidation_engine`：对应 Java `loanLiquidationEngine`，现货借贷强平
///   子域委托对象（自持一份同 sink 的回调）。
#[derive(Debug, Default)]
pub struct LiquidationEngine {
    pub symbol_to_users: BTreeMap<i32, BTreeSet<i64>>,
    pub is_running: bool,
    pub loan_liquidation_engine: LoanLiquidationEngine,
    command_submitter: CommandSubmitter,
}

impl LiquidationEngine {
    pub fn new() -> Self {
        LiquidationEngine::default()
    }

    /// 注册命令提交出口。因 `Box<dyn FnMut>` 不可 clone，本引擎与 loan 子引擎各需一份捕获同一 sink 的
    /// 回调，故收工厂 `make`，各调一次 `make()` 铸自己那份（loan 子引擎在此一并注册，共用同一 sink）。
    pub fn set_command_submitter<F>(&mut self, make: F)
    where
        F: Fn() -> Box<dyn FnMut(OrderCommand)>,
    {
        self.command_submitter.set(make());
        self.loan_liquidation_engine.set_command_submitter(make());
    }

    /// 对应 Java `onPositionOpened`：开仓 apply 时把 uid 登记进 symbol -> 持有者索引
    /// （所有节点确定性维护，不 gate）。
    pub fn on_position_opened(&mut self, uid: i64, symbol: i32) {
        self.symbol_to_users.entry(symbol).or_default().insert(uid);
    }

    /// 对应 Java `onPositionClosed`：平仓 apply 时从索引摘除 uid。HEDGE 双向持仓下，
    /// 仅当该 symbol 已无其它方向仓位（`holds_other`）时才移除，避免误删仍有敞口
    /// 的持有者。
    pub fn on_position_closed(&mut self, profile: &UserProfile, symbol: i32, closed_key: i32) {
        let holds_other = profile.positions.iter().any(|(&k, p)| k != closed_key && p.symbol == symbol);
        if holds_other {
            return;
        }
        if let Some(s) = self.symbol_to_users.get_mut(&symbol) {
            s.remove(&profile.uid);
            if s.is_empty() {
                self.symbol_to_users.remove(&symbol);
            }
        }
    }

    /// 对应 Java `checkPositions`：强平检测入口，命令 apply 时调用（leader-only，
    /// `is_running` 门控）。`cmd.symbol >= 0` 只查该 symbol 的持有者（targeted，价格/
    /// 资金费触发）；`cmd.symbol < 0`（`LIQUIDATION_SCAN`）全量整扫兜底，按
    /// `covered_by_scan_slice` 把扫描负载分摊到多个 tick。末尾委托
    /// `loan_liquidation_engine.check_loans`，检测现货借贷侧强平（loan 子引擎经自己的
    /// `command_submitter` 回调提交，与本引擎同一 sink，无需再回收）。
    ///
    /// targeted 分支末尾对 `symbol_to_users` 做一次惰性清理（保留仍持有该 symbol
    /// 仓位的 uid），这是 Rust 版对索引一致性的主动校验，弥补没有严格依赖
    /// `on_position_closed` 单点维护的风险；Java 版未见等价的每次扫描后校验。
    #[allow(clippy::too_many_arguments)]
    pub fn check_positions(
        &mut self,
        cmd: &OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        loan_service: &LoanService,
        fund_events: &mut Vec<FundEvent>,
    ) {
        if !self.is_running {
            return;
        }
        let targeted = cmd.symbol >= 0;
        let uids: Vec<i64> = if targeted {
            match self.symbol_to_users.get(&cmd.symbol) {
                Some(holders) => holders.iter().copied().collect(),
                None => Vec::new(),
            }
        } else {
            ups.users.keys().copied().filter(|&uid| Self::covered_by_scan_slice(cmd, uid)).collect()
        };
        for uid in &uids {
            self.check_user(*uid, cmd.timestamp, ups, ssp, last_price_cache, fund_events);
        }

        if targeted {
            if let Some(holders) = self.symbol_to_users.get_mut(&cmd.symbol) {
                holders.retain(|uid| {
                    ups.get(*uid).is_some_and(|u| u.positions.values().any(|p| p.symbol == cmd.symbol))
                });
                if holders.is_empty() {
                    self.symbol_to_users.remove(&cmd.symbol);
                }
            }
        }
        self.loan_liquidation_engine.check_loans(cmd, ups, ssp, last_price_cache, loan_service, fund_events);
    }

    /// 对应 Java `checkUser`（逐仓分类）+ 部分 `checkIsolated`（越预警线事件生成）+
    /// `startLiquidationFlow` 调用点：ISOLATED 立即判定；CROSS 按 quote 币种分组，
    /// 交给 `check_cross_decisions` 统一算账户级风险。
    ///
    /// 分两阶段执行：先在一个只读借用块内收集全部 `decisions`（不改状态），再在第二
    /// 个循环里逐条对 `ups` 做可变借用去落地（写 `liquidation_flow`、发预警/强平
    /// 事件、入队 FORCE 命令）。这是 Rust 借用检查器强制的结构，但也顺带保证了
    /// “决策”与“生效”两步分离，与 Java 版在同一次遍历里直接读写没有语义差异。
    #[allow(clippy::too_many_arguments)]
    fn check_user(
        &mut self,
        uid: i64,
        ts: i64,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        fund_events: &mut Vec<FundEvent>,
    ) {
        let decisions: Vec<LiquidationDecision> = {
            let profile = match ups.get(uid) {
                Some(p) => p,
                None => return,
            };
            let mut decisions = Vec::new();
            let mut cross_by_currency: BTreeMap<i32, Vec<i32>> = BTreeMap::new();
            for (&key, position) in profile.positions.iter() {
                if position.open_volume == 0 {
                    continue;
                }
                let spec = match ssp.get_symbol(position.symbol) {
                    Some(s) => s,
                    None => continue,
                };
                if !spec.symbol_type.is_futures_contract() {
                    continue;
                }
                let mark_price = match last_price_cache.get(&position.symbol) {
                    Some(r) => r.mark_price,
                    None => continue,
                };
                if position.margin_mode == MarginMode::Isolated {
                    match Self::check_isolated_decision(key, position, spec, mark_price) {
                        IsolatedCheck::Liquidate(d) => decisions.push(d),
                        IsolatedCheck::Alert => fund_events.push(Self::notification_event(FundEventType::MarginAlert, uid, position)),
                        IsolatedCheck::Healthy => {}
                    }
                } else {
                    cross_by_currency.entry(spec.quote_currency).or_default().push(key);
                }
            }
            Self::check_cross_decisions(profile, &cross_by_currency, ssp, last_price_cache, &mut decisions);
            decisions
        };

        for d in decisions {
            let profile = match ups.get_mut(uid) {
                Some(p) => p,
                None => return,
            };
            if let Some(pos) = profile.positions.get(&d.position_key) {
                fund_events.push(Self::notification_event(FundEventType::LiquidationAlert, uid, pos));
            }
            self.start_liquidation_flow(profile, d, ts);
        }
    }

    /// 对应 Java `checkIsolated` 的判定部分（不含 `startLiquidationFlow` 落地）：
    /// `equity < maintenance_margin` 判破产，破产价用 `NO_CROSS`（无全仓分摊，隔离
    /// 仓自成一体）；否则若 `equity < 1.2 × maintenance_margin` 视为越预警线。
    fn check_isolated_decision(
        position_key: i32,
        position: &SymbolPositionRecord,
        spec: &CoreSymbolSpecification,
        mark_price: i64,
    ) -> IsolatedCheck {
        let profit = position.estimate_unrealized_profit(mark_price);
        let equity = position.open_init_margin_sum + profit + position.extra_margin;
        let maintenance_margin = position.calculate_maintenance_margin(spec, mark_price);
        if equity >= maintenance_margin {
            if maintenance_margin > 0 && equity < maintenance_margin.saturating_mul(12) / 10 {
                return IsolatedCheck::Alert;
            }
            return IsolatedCheck::Healthy;
        }
        let bankruptcy_price = position.calculate_bankruptcy_price(spec, |_| 0);
        let size_to_liquidate = position.open_volume.min(Self::size_to_liquidate_for(position, maintenance_margin, mark_price));
        if size_to_liquidate <= 0 {
            return IsolatedCheck::Healthy;
        }
        IsolatedCheck::Liquidate(LiquidationDecision { position_key, bankruptcy_price, size: size_to_liquidate })
    }

    /// 对应 Java `checkCross`：逐 quote 币种判定全仓联合风险——equity 跌破维持保证金
    /// 则从最危险仓位起逐仓强平至覆盖亏空（转交 `force_cross_decisions`）。
    ///
    /// 与 Java 版的一处可观察差异：Java `checkCross` 在
    /// `totalMaintenanceMargin <= equity < warningThreshold` 区间会调用
    /// `sendWarningEvent` 发一条 CROSS 越预警线通知；本函数在等价区间
    /// （`equity >= total_maintenance` 但 `< warning_threshold`）只是 `continue`，
    /// 未见生成对应的 `FundEventType::MarginAlert` 事件（该签名也没有
    /// `fund_events` 参数可写）——ISOLATED 分支的越预警线通知（`IsolatedCheck::Alert`）
    /// 则确有翻译，见 `check_isolated_decision`。
    fn check_cross_decisions(
        profile: &UserProfile,
        cross_by_currency: &BTreeMap<i32, Vec<i32>>,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        decisions: &mut Vec<LiquidationDecision>,
    ) {
        if cross_by_currency.is_empty() {
            return;
        }
        let alloc = profile.cross_margin_base_allocation(
            |s| ssp.get_symbol(s),
            |c| ssp.get_currency(c),
            |s| last_price_cache.get(&s).map(|r| r.mark_price),
        );

        for (&currency, keys) in cross_by_currency.iter() {
            let currency_spec = match ssp.get_currency(currency) {
                Some(c) => c,
                None => continue,
            };
            let mut total_profit: i64 = 0;
            let mut total_maintenance: i64 = 0;
            let mut risk_pairs: Vec<(i64, i32)> = Vec::new();
            for &key in keys {
                let position = &profile.positions[&key];
                let spec = match ssp.get_symbol(position.symbol) {
                    Some(s) => s,
                    None => continue,
                };
                let mark_price = match last_price_cache.get(&position.symbol) {
                    Some(r) => r.mark_price,
                    None => continue,
                };
                let raw_maintenance = position.calculate_maintenance_margin(spec, mark_price);
                if raw_maintenance == 0 {
                    // no maintenance margin requirement: does not contribute to account risk
                    continue;
                }
                let profit = size_price_to_currency_scale(
                    position.estimate_pnl(mark_price),
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    currency_spec.currency_scale_k,
                );
                let maintenance = size_price_to_currency_scale(
                    raw_maintenance,
                    spec.base_scale_k,
                    spec.quote_scale_k,
                    currency_spec.currency_scale_k,
                );
                total_profit += profit;
                total_maintenance += maintenance;
                if maintenance != 0 {
                    // scaled-to-zero cannot be a divisor: PnL is already folded into the
                    // totals, it is just excluded from risk ranking
                    let risk = mul_exact(profit - maintenance, 100) / maintenance;
                    risk_pairs.push((risk, key));
                }
            }
            let equity = total_profit
                + profile.calculate_cross_available(currency, currency_spec, |s| ssp.get_symbol(s));
            let warning_threshold = mul_exact(total_maintenance, 6) / 5; // 1.2x maintenance margin: warning line
            if equity >= warning_threshold {
                continue;
            }
            if equity >= total_maintenance {
                continue;
            }
            // ascending risk order: the most at-risk position is liquidated first
            risk_pairs.sort_by_key(|p| p.0);
            Self::force_cross_decisions(
                profile,
                &risk_pairs,
                total_maintenance - equity,
                &alloc,
                ssp,
                last_price_cache,
                decisions,
            );
        }
    }

    /// 对应 Java `forceCrossLiquidation`：按风险度升序（最危险优先）逐仓强平，直至
    /// 释放保证金覆盖 `deficit` 或仓位耗尽。
    #[allow(clippy::too_many_arguments)]
    fn force_cross_decisions(
        profile: &UserProfile,
        risk_pairs: &[(i64, i32)],
        deficit: i64,
        alloc: &BTreeMap<i32, i64>,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        decisions: &mut Vec<LiquidationDecision>,
    ) {
        let mut margin_released: i64 = 0;
        for &(_risk, key) in risk_pairs {
            if margin_released >= deficit {
                break;
            }
            let position = &profile.positions[&key];
            let spec = match ssp.get_symbol(position.symbol) {
                Some(s) => s,
                None => continue,
            };
            let mark_price = match last_price_cache.get(&position.symbol) {
                Some(r) => r.mark_price,
                None => continue,
            };
            let bankruptcy_price = position.calculate_bankruptcy_price(spec, |p| alloc.get(&Self::pos_key(p)).copied().unwrap_or(0));
            let maintenance_margin = position.calculate_maintenance_margin(spec, mark_price);
            let size_to_liquidate =
                position.open_volume.min(Self::size_to_liquidate_for(position, maintenance_margin, mark_price));
            if size_to_liquidate > 0 {
                margin_released += Self::deficit_after_for(position, spec, size_to_liquidate, mark_price);
                decisions.push(LiquidationDecision { position_key: key, bankruptcy_price, size: size_to_liquidate });
            }
        }
    }

    /// 对应 Java `startLiquidationFlow`：提交 FORCE_LIQUIDATION、建立 leader-local
    /// `liquidation_flow` 状态机。已有进行中流程（`liquidation_flow.is_some()`）则
    /// 跳过——幂等保护，防止同一仓位被重复扫描时重复提交 FORCE。
    ///
    /// Java 版在此还会经 `eventsHelper` 发一条 `ApiSystemLiquidationNotify` 强平预警
    /// 通知；本文件未见等价的通知事件（见文件头说明），仅 `check_user` 里对应
    /// decision 的 `FundEventType::LiquidationAlert` 已翻译。
    fn start_liquidation_flow(&mut self, profile: &mut UserProfile, d: LiquidationDecision, ts: i64) {
        let uid = profile.uid;
        let position = match profile.positions.get_mut(&d.position_key) {
            Some(p) => p,
            None => return,
        };
        if position.liquidation_flow.is_some() {
            return;
        }
        let order_id =
            LiquidationService::generate_liquidation_order_id(uid, position.symbol, position.direction, ts);
        position.liquidation_flow = Some(LiquidationFlow::new(d.bankruptcy_price, d.size, order_id));
        let force_cmd = Self::build_force_cmd(uid, position.symbol, position.direction, order_id, d.bankruptcy_price, d.size, ts);
        self.command_submitter.submit(force_cmd);
    }

    /// 对应 Java `advanceLiquidation`：强平命令 apply 后推进 FORCE→IF→ADL 状态机
    /// （leader-only，`is_running` 门控）。flow 为空且命令是 FORCE 时新建流程（含
    /// 换届后残余仓恢复——新 leader 上 `liquidation_flow` 丢失，遇到一条落地的
    /// FORCE 命令即按 `cmd.price`/`cmd.size`/`cmd.order_id` 重建，语义与原发起方
    /// 一致，因为这些字段本就来自当初 `build_force_cmd` 写入、经 raft 复制的命令）；
    /// 否则按当前 `flow.state` 校验命令是否是该状态下预期的下一条命令，防止
    /// 重复/错序推进（`Some(flow.state) != expected` 即跳过，不 panic——错序命令
    /// 被视为可能的重放或竞态残留，静默忽略而不是使流程崩溃）。
    pub fn advance_liquidation(&mut self, cmd: &OrderCommand, pos: &mut SymbolPositionRecord) {
        if !self.is_running {
            return;
        }
        match pos.liquidation_flow {
            None => {
                if cmd.command != OrderCommandType::ForceLiquidation {
                    return;
                }
                pos.liquidation_flow = Some(LiquidationFlow::new(cmd.price, cmd.size, cmd.order_id));
            }
            Some(flow) => {
                let expected = match cmd.command {
                    OrderCommandType::ForceLiquidation => Some(LiquidationState::Liquidating),
                    OrderCommandType::IfTakeover => Some(LiquidationState::WaitIfExecution),
                    OrderCommandType::AutoDeleveraging => Some(LiquidationState::WaitAdlExecution),
                    _ => None,
                };
                if Some(flow.state) != expected {
                    return;
                }
            }
        }
        match cmd.command {
            OrderCommandType::ForceLiquidation => self.on_force_applied(cmd, pos),
            OrderCommandType::IfTakeover => self.on_if_takeover_applied(cmd, pos),
            OrderCommandType::AutoDeleveraging => pos.liquidation_flow = None,
            _ => {}
        }
    }

    /// 对应 Java `onForceApplied`：FORCE 单 apply 回调——完全成交（非 REJECT）则
    /// 闭环（`liquidation_flow = None`）；REJECT（部分成交剩余）则转 IF 接管，
    /// `flow.size` 更新为 REJECT 事件携带的剩余量。
    fn on_force_applied(&mut self, cmd: &OrderCommand, pos: &mut SymbolPositionRecord) {
        let rejected = matches!(&cmd.matcher_event, Some(ev) if ev.event_type == MatcherEventType::Reject);
        if !rejected {
            pos.liquidation_flow = None;
            return;
        }
        let remaining = cmd.matcher_event.as_ref().map(|e| e.size).unwrap_or(0);
        if let Some(flow) = pos.liquidation_flow.as_mut() {
            flow.size = remaining;
            flow.state = LiquidationState::WaitIfExecution;
        }
        if let Some(flow) = pos.liquidation_flow {
            let if_cmd = Self::build_if_cmd(pos.uid, pos.symbol, pos.direction, &flow, cmd.timestamp);
            self.command_submitter.submit(if_cmd);
        }
    }

    /// 对应 Java `onIfTakeoverApplied`：IF 单 apply 回调——接管成功（非 REJECT）则
    /// 闭环；REJECT 则转 ADL 摊派。
    fn on_if_takeover_applied(&mut self, cmd: &OrderCommand, pos: &mut SymbolPositionRecord) {
        let rejected = matches!(&cmd.matcher_event, Some(ev) if ev.event_type == MatcherEventType::Reject);
        if !rejected {
            pos.liquidation_flow = None;
            return;
        }
        if let Some(flow) = pos.liquidation_flow.as_mut() {
            flow.state = LiquidationState::WaitAdlExecution;
        }
        if let Some(flow) = pos.liquidation_flow {
            let adl_cmd = Self::build_adl_cmd(pos.uid, pos.symbol, pos.direction, &flow, cmd.timestamp);
            self.command_submitter.submit(adl_cmd);
        }
    }

    /// 转发到 `scheduler::covered_by_scan_slice`（对应 Java
    /// `LiquidationScheduledService.coveredByScanSlice`）；保留此方法是为了让调用方
    /// 能通过 `LiquidationEngine::covered_by_scan_slice` 这个路径访问，与 Java 版
    /// `LiquidationEngine` 继承 `LiquidationScheduledService` 因而天然拥有该静态
    /// 方法的效果一致。
    pub fn covered_by_scan_slice(cmd: &OrderCommand, uid: i64) -> bool {
        crate::core::processors::liquidation::scheduler::covered_by_scan_slice(cmd, uid)
    }

    /// 无直接 Java 单一对应方法：对应 Java `FundEventsHelper.sendLiquidationAlertEvent`/
    /// `sendMarginAlertEvent` 构造事件快照那部分逻辑；Rust 版把两种通知合并成同一个
    /// 构造函数，靠调用方传入的 `event_type` 区分强平预警/保证金预警。
    fn notification_event(event_type: FundEventType, uid: i64, position: &SymbolPositionRecord) -> FundEvent {
        FundEvent {
            event_type,
            uid,
            symbol: position.symbol,
            currency: position.currency,
            direction: position.direction,
            open_volume: position.open_volume,
            open_price_sum: position.open_price_sum,
            margin_mode: position.margin_mode,
            ..Default::default()
        }
    }

    /// 无直接 Java 对应方法：Java 版 `alloc`（`cross_margin_base_allocation` 的返回值）
    /// 是以 `SymbolPositionRecord` 对象本身（引用相等）为 key 的
    /// `ObjectLongHashMap`，直接用 `alloc::get` 查询；Rust 版的 `alloc` 是
    /// `BTreeMap<i32, i64>`，需要一个可比较的整数 key，这里用「方向符号 × symbol」
    /// 编码（与 `LiquidationService`/`IfPositionRecord` 里同一套编码约定一致），
    /// 供 `force_cross_decisions` 里 `calculate_bankruptcy_price` 的分摊回调使用。
    fn pos_key(p: &SymbolPositionRecord) -> i32 {
        match p.direction {
            PositionDirection::Short => -p.symbol,
            _ => p.symbol,
        }
    }

    /// 无直接 Java 单一对应方法：对应 Java `CoreArithmeticUtils.calculateSizeToLiquidate(position, spec, priceRecord)`
    /// 调用点，这里把从 `position`/`spec`/`priceRecord` 取值的过程内联为显式参数，
    /// 转调 `calculate_size_to_liquidate`。
    fn size_to_liquidate_for(position: &SymbolPositionRecord, maintenance_margin: i64, mark_price: i64) -> i64 {
        let equity = position.open_init_margin_sum + position.estimate_unrealized_profit(mark_price);
        calculate_size_to_liquidate(
            equity,
            maintenance_margin,
            position.open_init_margin_sum,
            position.open_volume,
            position.open_price_sum,
            mark_price,
            position.direction.multiplier() as i64,
        )
    }

    /// 无直接 Java 单一对应方法：对应 Java `CoreArithmeticUtils.calculateDeficitAfterLiquidate(sizeToLiquidate, position, spec, priceRecord)`
    /// 调用点，先在此算出清算前后的名义价值/维持保证金，再转调
    /// `calculate_deficit_after_liquidate`。
    fn deficit_after_for(
        position: &SymbolPositionRecord,
        spec: &CoreSymbolSpecification,
        size: i64,
        mark_price: i64,
    ) -> i64 {
        let notional_now = mul_exact(position.open_volume, mark_price);
        let notional_after = mul_exact(position.open_volume - size, mark_price);
        calculate_deficit_after_liquidate(
            size,
            position.direction.multiplier() as i64,
            position.open_init_margin_sum,
            position.open_volume,
            position.open_price_sum,
            mark_price,
            spec.calculate_maintenance_margin(notional_now),
            spec.calculate_maintenance_margin(notional_after),
        )
    }

    /// 对应 Java `buildForceCmd`（构造 `ApiLiquidationOrder`）：LONG 强平方向为 ASK、
    /// SHORT 为 BID（平仓方向与持仓方向相反），限价单价格 = 破产价，`OrderType::Ioc`
    /// 保证不留在盘口挂单。
    fn build_force_cmd(uid: i64, symbol: i32, direction: PositionDirection, order_id: i64, price: i64, size: i64, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::ForceLiquidation,
            order_id,
            uid,
            symbol,
            price,
            size,
            action: Some(if direction == PositionDirection::Long { OrderAction::Ask } else { OrderAction::Bid }),
            order_type: Some(OrderType::Ioc),
            timestamp: ts,
            ..Default::default()
        }
    }

    /// 对应 Java `buildIFCmd`（构造 `ApiIFTakeOver`）：orderId 由
    /// `generate_if_order_id` 从 `flow.original_order_id` 派生；action 相对
    /// `build_force_cmd` 翻转（LONG -> BID，即 IF 从「接管方」视角吃进原多头
    /// 仓位），这是 Java 注释里说的 perspective flip。
    fn build_if_cmd(uid: i64, symbol: i32, direction: PositionDirection, flow: &LiquidationFlow, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::IfTakeover,
            order_id: LiquidationService::generate_if_order_id(flow.original_order_id),
            uid,
            symbol,
            price: flow.bankruptcy_price,
            size: flow.size,
            action: Some(if direction == PositionDirection::Long { OrderAction::Bid } else { OrderAction::Ask }),
            timestamp: ts,
            ..Default::default()
        }
    }

    /// 对应 Java `buildADLCmd`（构造 `ApiAutoDeleveraging`）：orderId 由
    /// `generate_adl_order_id` 从 `flow.original_order_id` 派生，action 与
    /// `build_if_cmd` 同向（同为「接管方视角」）。
    fn build_adl_cmd(uid: i64, symbol: i32, direction: PositionDirection, flow: &LiquidationFlow, ts: i64) -> OrderCommand {
        OrderCommand {
            command: OrderCommandType::AutoDeleveraging,
            order_id: LiquidationService::generate_adl_order_id(flow.original_order_id),
            uid,
            symbol,
            price: flow.bankruptcy_price,
            size: flow.size,
            action: Some(if direction == PositionDirection::Long { OrderAction::Bid } else { OrderAction::Ask }),
            timestamp: ts,
            ..Default::default()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::matcher_trade_event::MatcherTradeEvent;
    use crate::core::common::symbol_type::SymbolType;
    use std::cell::RefCell;
    use std::rc::Rc;

    const FUT_SYMBOL: i32 = 200;
    const FUT_QUOTE: i32 = 2;
    const FUT_BASE: i32 = 1;
    const UID: i64 = 1;

    /// collector 出口：把 FORCE/IF/ADL 命令收进共享 Vec 供断言；必须在触发 `check_positions`/`advance_liquidation` 之前挂上。
    fn attach_collector(engine: &mut LiquidationEngine) -> Rc<RefCell<Vec<OrderCommand>>> {
        let collected = Rc::new(RefCell::new(Vec::new()));
        let sink = collected.clone();
        engine.set_command_submitter(move || {
            let s = sink.clone();
            Box::new(move |cmd| s.borrow_mut().push(cmd))
        });
        collected
    }

    fn futures_spec() -> CoreSymbolSpecification {
        let mut mm = BTreeMap::new();
        mm.insert(i64::MAX, 500);
        CoreSymbolSpecification {
            symbol_id: FUT_SYMBOL,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: FUT_BASE,
            quote_currency: FUT_QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            maintenance_margin: mm,
            maintenance_margin_scale_k: 10_000,
            ..Default::default()
        }
    }

    fn seeded() -> (LiquidationEngine, UserProfileService, SymbolSpecificationProvider, BTreeMap<i32, LastPriceCacheRecord>) {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_QUOTE, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: FUT_BASE, currency_scale_k: 1, ..Default::default() });
        ssp.add_symbol(futures_spec());
        let mut ups = UserProfileService::new();
        ups.add_empty_user_profile(UID);
        let last_price_cache = BTreeMap::new();
        (engine, ups, ssp, last_price_cache)
    }

    fn insert_long(ups: &mut UserProfileService, uid: i64) {
        let pos = SymbolPositionRecord {
            direction: PositionDirection::Long,
            open_volume: 10,
            open_price_sum: 1_000,
            open_init_margin_sum: 100,
            ..SymbolPositionRecord::new(uid, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
        };
        ups.get_mut(uid).unwrap().positions.insert(FUT_SYMBOL, pos);
    }

    fn markprice_cmd(symbol: i32, ts: i64) -> OrderCommand {
        OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol, timestamp: ts, ..Default::default() }
    }

    fn mte(event_type: MatcherEventType, size: i64) -> Box<MatcherTradeEvent> {
        Box::new(MatcherTradeEvent {
            event_type,
            active_order_completed: false,
            maker_order_id: 0,
            maker_order_completed: false,
            price: 0,
            size,
            bid_gt_ask: false,
            bidder_hold_price: 0,
            matched_order_uid: 0,
            matched_order_command_type: OrderCommandType::PlaceOrder,
            filled: 0,
            filled_notional: 0,
            matched_order_size: 0,
            matched_order_price: 0,
            matched_order_type: crate::core::common::order_type::OrderType::Gtc,
            matched_order_timestamp: 0,
            matched_user_cookie: 0,
            matched_order_filled: 0,
            matched_order_filled_notional: 0,
            next: None,
        })
    }

    #[test]
    fn covered_by_scan_slice_non_scan_always_covered() {
        let cmd = markprice_cmd(FUT_SYMBOL, 0);
        assert!(LiquidationEngine::covered_by_scan_slice(&cmd, 12345));
    }

    #[test]
    fn covered_by_scan_slice_matches_and_misses() {
        let cmd = OrderCommand { command: OrderCommandType::LiquidationScan, symbol: -1, uid: 3, size: 10, ..Default::default() };
        assert!(LiquidationEngine::covered_by_scan_slice(&cmd, 13), "13 mod 10 == 3 -> in slice");
        assert!(!LiquidationEngine::covered_by_scan_slice(&cmd, 14), "14 mod 10 == 4 != 3 -> out of slice");
        let full = OrderCommand { command: OrderCommandType::LiquidationScan, symbol: -1, uid: 0, size: 0, ..Default::default() };
        assert!(LiquidationEngine::covered_by_scan_slice(&full, 999));
    }

    #[test]
    fn on_position_opened_registers_uid() {
        let (mut engine, _ups, _ssp, _lpc) = seeded();
        engine.on_position_opened(UID, FUT_SYMBOL);
        assert!(engine.symbol_to_users.get(&FUT_SYMBOL).unwrap().contains(&UID));
    }

    #[test]
    fn on_position_closed_removes_uid_when_no_other_position() {
        let (mut engine, mut ups, _ssp, _lpc) = seeded();
        engine.on_position_opened(UID, FUT_SYMBOL);
        insert_long(&mut ups, UID);
        engine.on_position_closed(ups.get(UID).unwrap(), FUT_SYMBOL, FUT_SYMBOL);
        assert!(engine.symbol_to_users.get(&FUT_SYMBOL).is_none(), "no other position -> uid removed + empty set cleaned up");
    }

    #[test]
    fn on_position_closed_hedge_keeps_uid_when_other_side_exists() {
        let (mut engine, mut ups, _ssp, _lpc) = seeded();
        engine.on_position_opened(UID, FUT_SYMBOL);
        insert_long(&mut ups, UID);
        let short = SymbolPositionRecord {
            direction: PositionDirection::Short,
            open_volume: 5,
            ..SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
        };
        ups.get_mut(UID).unwrap().positions.insert(-FUT_SYMBOL, short);
        engine.on_position_closed(ups.get(UID).unwrap(), FUT_SYMBOL, FUT_SYMBOL);
        assert!(
            engine.symbol_to_users.get(&FUT_SYMBOL).unwrap().contains(&UID),
            "an opposite-side position on the same symbol still exists -> the holder must not be mistakenly removed (HEDGE safety)"
        );
    }

    #[test]
    fn check_positions_leader_gate_off_is_noop() {
        let (mut engine, mut ups, ssp, mut lpc) = seeded();
        engine.is_running = false;
        let out = attach_collector(&mut engine);
        engine.on_position_opened(UID, FUT_SYMBOL);
        insert_long(&mut ups, UID);
        lpc.insert(FUT_SYMBOL, LastPriceCacheRecord::with_mark(50));
        let cmd = markprice_cmd(FUT_SYMBOL, 1_000);
        engine.check_positions(&cmd, &mut ups, &ssp, &lpc, &LoanService::new(), &mut Vec::new());
        assert!(out.borrow().is_empty(), "a follower neither detects nor submits");
        assert!(ups.get(UID).unwrap().positions[&FUT_SYMBOL].liquidation_flow.is_none());
    }

    #[test]
    fn check_positions_targeted_isolated_underwater_queues_force_and_sets_flow() {
        let (mut engine, mut ups, ssp, mut lpc) = seeded();
        let out = attach_collector(&mut engine);
        engine.on_position_opened(UID, FUT_SYMBOL);
        insert_long(&mut ups, UID);
        lpc.insert(FUT_SYMBOL, LastPriceCacheRecord::with_mark(50));
        let cmd = markprice_cmd(FUT_SYMBOL, 5_000);

        engine.check_positions(&cmd, &mut ups, &ssp, &lpc, &LoanService::new(), &mut Vec::new());

        assert_eq!(out.borrow().len(), 1, "one FORCE is triggered");
        let force = out.borrow()[0].clone();
        assert_eq!(force.command, OrderCommandType::ForceLiquidation);
        assert_eq!(force.uid, UID);
        assert_eq!(force.symbol, FUT_SYMBOL);
        assert_eq!(force.action, Some(OrderAction::Ask), "LONG liquidation -> ASK (opposite of the closing direction)");
        assert_eq!(force.order_type, Some(OrderType::Ioc));
        assert_eq!(force.size, 10, "size_to_liquidate = min(open_volume, calc) = 10");
        let flow = ups.get(UID).unwrap().positions[&FUT_SYMBOL].liquidation_flow.expect("flow set");
        assert_eq!(flow.state, LiquidationState::Liquidating);
        assert_eq!(flow.size, 10);
        assert_eq!(force.price, flow.bankruptcy_price, "FORCE limit price = bankruptcy price");
        assert_eq!(force.order_id, flow.original_order_id);
    }

    #[test]
    fn check_positions_healthy_position_no_force() {
        let (mut engine, mut ups, ssp, mut lpc) = seeded();
        let out = attach_collector(&mut engine);
        engine.on_position_opened(UID, FUT_SYMBOL);
        insert_long(&mut ups, UID);
        lpc.insert(FUT_SYMBOL, LastPriceCacheRecord::with_mark(100));
        let cmd = markprice_cmd(FUT_SYMBOL, 1_000);

        engine.check_positions(&cmd, &mut ups, &ssp, &lpc, &LoanService::new(), &mut Vec::new());

        assert!(out.borrow().is_empty(), "a healthy position does not trigger anything");
        assert!(ups.get(UID).unwrap().positions[&FUT_SYMBOL].liquidation_flow.is_none());
    }

    #[test]
    fn check_positions_idempotent_second_scan_no_double_submit() {
        let (mut engine, mut ups, ssp, mut lpc) = seeded();
        let out = attach_collector(&mut engine);
        engine.on_position_opened(UID, FUT_SYMBOL);
        insert_long(&mut ups, UID);
        lpc.insert(FUT_SYMBOL, LastPriceCacheRecord::with_mark(50));
        let cmd = markprice_cmd(FUT_SYMBOL, 5_000);

        engine.check_positions(&cmd, &mut ups, &ssp, &lpc, &LoanService::new(), &mut Vec::new());
        engine.check_positions(&cmd, &mut ups, &ssp, &lpc, &LoanService::new(), &mut Vec::new());

        assert_eq!(out.borrow().len(), 1, "flow already in progress -> the second scan does not resubmit (idempotency gate)");
    }

    #[test]
    fn check_positions_scan_slice_filters_users() {
        let (mut engine, mut ups, ssp, mut lpc) = seeded();
        let out = attach_collector(&mut engine);
        ups.add_empty_user_profile(2);
        insert_long(&mut ups, 1);
        insert_long(&mut ups, 2);
        lpc.insert(FUT_SYMBOL, LastPriceCacheRecord::with_mark(50));
        let scan = OrderCommand { command: OrderCommandType::LiquidationScan, symbol: -1, uid: 1, size: 2, timestamp: 5_000, ..Default::default() };

        engine.check_positions(&scan, &mut ups, &ssp, &lpc, &LoanService::new(), &mut Vec::new());

        assert_eq!(out.borrow().len(), 1, "only uid=1 is within the slice");
        assert_eq!(out.borrow()[0].uid, 1);
        assert!(ups.get(2).unwrap().positions[&FUT_SYMBOL].liquidation_flow.is_none(), "uid=2 is outside the slice and was not touched");
    }

    #[test]
    fn check_cross_scaled_maintenance_truncates_to_zero_no_panic_no_force() {
        const SYMBOL: i32 = 5001;
        const BASE_CCY: i32 = 10;
        const QUOTE_CCY: i32 = 20;
        const U: i64 = 42;

        let mut mm = BTreeMap::new();
        mm.insert(10_000_000i64, 5i64);
        let spec = CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::FuturesContractPerpetual,
            base_currency: BASE_CCY,
            quote_currency: QUOTE_CCY,
            base_scale_k: 100,
            quote_scale_k: 100,
            init_margin: 1,
            init_margin_scale_k: 1,
            maintenance_margin: mm,
            maintenance_margin_scale_k: 1000,
            ..Default::default()
        };

        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_currency(CoreCurrencySpecification { currency: BASE_CCY, currency_scale_k: 1, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: QUOTE_CCY, currency_scale_k: 1, ..Default::default() });
        ssp.add_symbol(spec);

        let mut ups = UserProfileService::new();
        ups.add_empty_user_profile(U);
        let pos = SymbolPositionRecord {
            direction: PositionDirection::Long,
            open_volume: 1,
            open_price_sum: 1_000_000,
            open_init_margin_sum: 1,
            ..SymbolPositionRecord::new(U, SYMBOL, QUOTE_CCY, MarginMode::Cross, 1)
        };
        ups.get_mut(U).unwrap().positions.insert(SYMBOL, pos);
        engine.on_position_opened(U, SYMBOL);

        let mut lpc = BTreeMap::new();
        lpc.insert(SYMBOL, LastPriceCacheRecord::with_mark(1_000_000i64));
        let cmd = markprice_cmd(SYMBOL, 1_000);

        engine.check_positions(&cmd, &mut ups, &ssp, &lpc, &LoanService::new(), &mut Vec::new());
        assert!(out.borrow().is_empty(), "a healthy CROSS account whose scaled maintenance truncates to zero must not be mistakenly liquidated (and must not divide by zero)");
        assert!(ups.get(U).unwrap().positions[&SYMBOL].liquidation_flow.is_none());
    }

    fn pos_with_flow(state: LiquidationState) -> SymbolPositionRecord {
        let mut pos = SymbolPositionRecord {
            direction: PositionDirection::Long,
            open_volume: 10,
            open_price_sum: 1_000,
            open_init_margin_sum: 100,
            ..SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
        };
        let mut flow = LiquidationFlow::new(45, 10, 777);
        flow.state = state;
        pos.liquidation_flow = Some(flow);
        pos
    }

    fn force_apply_cmd(event: Option<Box<MatcherTradeEvent>>) -> OrderCommand {
        OrderCommand { command: OrderCommandType::ForceLiquidation, uid: UID, symbol: FUT_SYMBOL, matcher_event: event, timestamp: 6_000, ..Default::default() }
    }

    #[test]
    fn advance_force_non_reject_closes_flow() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut pos = pos_with_flow(LiquidationState::Liquidating);
        let cmd = force_apply_cmd(Some(mte(MatcherEventType::Trade, 10)));
        engine.advance_liquidation(&cmd, &mut pos);
        assert!(pos.liquidation_flow.is_none(), "FORCE fully filled -> flow closed");
        assert!(out.borrow().is_empty());
    }

    #[test]
    fn advance_force_reject_transitions_to_wait_if_and_queues_if() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut pos = pos_with_flow(LiquidationState::Liquidating);
        let cmd = force_apply_cmd(Some(mte(MatcherEventType::Reject, 7)));
        engine.advance_liquidation(&cmd, &mut pos);
        let flow = pos.liquidation_flow.expect("flow is retained");
        assert_eq!(flow.state, LiquidationState::WaitIfExecution);
        assert_eq!(flow.size, 7, "flow.size is updated to the REJECT remaining size");
        assert_eq!(out.borrow().len(), 1);
        let ifc = out.borrow()[0].clone();
        assert_eq!(ifc.command, OrderCommandType::IfTakeover);
        assert_eq!(ifc.size, 7);
        assert_eq!(ifc.action, Some(OrderAction::Bid), "IF takeover of a LONG -> BID (perspective flip)");
        assert_eq!(ifc.order_id, LiquidationService::generate_if_order_id(777));
    }

    #[test]
    fn advance_if_reject_transitions_to_wait_adl_and_queues_adl() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut pos = pos_with_flow(LiquidationState::WaitIfExecution);
        let cmd = OrderCommand { command: OrderCommandType::IfTakeover, uid: UID, symbol: FUT_SYMBOL, matcher_event: Some(mte(MatcherEventType::Reject, 7)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        let flow = pos.liquidation_flow.expect("flow is retained");
        assert_eq!(flow.state, LiquidationState::WaitAdlExecution);
        assert_eq!(out.borrow().len(), 1);
        let adl = out.borrow()[0].clone();
        assert_eq!(adl.command, OrderCommandType::AutoDeleveraging);
        assert_eq!(adl.action, Some(OrderAction::Bid));
        assert_eq!(adl.order_id, LiquidationService::generate_adl_order_id(777));
    }

    #[test]
    fn advance_if_non_reject_closes_flow() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut pos = pos_with_flow(LiquidationState::WaitIfExecution);
        let cmd = OrderCommand { command: OrderCommandType::IfTakeover, uid: UID, symbol: FUT_SYMBOL, matcher_event: Some(mte(MatcherEventType::Trade, 7)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        assert!(pos.liquidation_flow.is_none(), "IF takeover succeeded -> flow closed");
        assert!(out.borrow().is_empty());
    }

    #[test]
    fn advance_adl_is_terminal() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut pos = pos_with_flow(LiquidationState::WaitAdlExecution);
        let cmd = OrderCommand { command: OrderCommandType::AutoDeleveraging, uid: UID, symbol: FUT_SYMBOL, matcher_event: Some(mte(MatcherEventType::Trade, 7)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        assert!(pos.liquidation_flow.is_none(), "ADL is always a terminal state");
        assert!(out.borrow().is_empty());
    }

    #[test]
    fn advance_null_flow_non_force_skips() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
        let cmd = OrderCommand { command: OrderCommandType::IfTakeover, uid: UID, symbol: FUT_SYMBOL, matcher_event: Some(mte(MatcherEventType::Reject, 7)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        assert!(pos.liquidation_flow.is_none());
        assert!(out.borrow().is_empty());
    }

    #[test]
    fn advance_null_flow_force_recovers_new_flow() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut pos = SymbolPositionRecord {
            direction: PositionDirection::Long,
            open_volume: 10,
            ..SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
        };
        let cmd = OrderCommand { command: OrderCommandType::ForceLiquidation, uid: UID, symbol: FUT_SYMBOL, price: 45, size: 8, order_id: 555, matcher_event: Some(mte(MatcherEventType::Reject, 8)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        let flow = pos.liquidation_flow.expect("recovery created a flow");
        assert_eq!(flow.state, LiquidationState::WaitIfExecution);
        assert_eq!(flow.original_order_id, 555, "the recovery flow uses cmd.order_id as the root orderId");
        assert_eq!(out.borrow().len(), 1);
        assert_eq!(out.borrow()[0].command, OrderCommandType::IfTakeover);
    }

    #[test]
    fn advance_out_of_order_command_skips() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let out = attach_collector(&mut engine);
        let mut pos = pos_with_flow(LiquidationState::Liquidating);
        let cmd = OrderCommand { command: OrderCommandType::IfTakeover, uid: UID, symbol: FUT_SYMBOL, matcher_event: Some(mte(MatcherEventType::Reject, 7)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        let flow = pos.liquidation_flow.expect("flow is unchanged");
        assert_eq!(flow.state, LiquidationState::Liquidating, "an out-of-order command is skipped, state is unchanged");
        assert!(out.borrow().is_empty());
    }

    #[test]
    fn advance_leader_gate_off_is_noop() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = false;
        let mut pos = pos_with_flow(LiquidationState::Liquidating);
        let cmd = force_apply_cmd(Some(mte(MatcherEventType::Trade, 10)));
        engine.advance_liquidation(&cmd, &mut pos);
        assert_eq!(pos.liquidation_flow.map(|f| f.state), Some(LiquidationState::Liquidating), "a follower does not advance the flow");
    }
}
