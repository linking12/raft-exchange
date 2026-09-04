//! 对应 Java `LiquidationEngine`（+ 父类 `LiquidationScheduledService`）：期货强平引擎，事件驱动、
//! on-lane 检测（命令 apply 内跑，只读复制态），FORCE→IF→ADL 状态机，参考文档 §1、§7。
//! 移植偏差：无事件总线/预警 no-op（Ruling P6-B）；submit→pending_commands 队列（无 disruptor）；
//! provider 传参不持有（Ruling P3-B）；is_running 替代 ScheduledExecutorService（Ruling P6-F）。
use std::collections::{BTreeMap, BTreeSet};

use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::matcher_event_type::MatcherEventType;
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;
use crate::core::common::position_direction::PositionDirection;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::common::user_profile::UserProfile;
use crate::core::processors::liquidation::liquidation_flow::{LiquidationFlow, LiquidationState};
use crate::core::processors::liquidation::liquidation_service::LiquidationService;
use crate::core::processors::loan::loan_liquidation_engine::LoanLiquidationEngine;
use crate::core::processors::loan::loan_service::LoanService;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::utils::core_arithmetic_utils::{
    calculate_deficit_after_liquidate, calculate_size_to_liquidate, size_price_to_currency_scale,
};

/// 待执行的强平决策（检测阶段产出，应用阶段消费）；`position_key` = ONEWAY symbol / HEDGE ±symbol。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct LiquidationDecision {
    position_key: i32,
    bankruptcy_price: i64,
    size: i64,
}

/// 对应 Java `LiquidationEngine`：只持有非复制 leader-local 状态（provider 传参不持有）。
#[derive(Debug, Default)]
pub struct LiquidationEngine {
    /// 对应 Java `symbolToUsers`：symbol → 持有者 uid 集合，非复制、不进 state_hash，`BTreeSet` 保确定序。
    pub symbol_to_users: BTreeMap<i32, BTreeSet<i64>>,
    /// 对应 Java 父类 `LiquidationScheduledService.isRunning()`：leader 门，raft leadership 切换时 toggle（P6-F）。
    pub is_running: bool,
    /// 提交队列（替代 Java disruptor `submit`）：FORCE/IF/ADL 命令由 driver 排空重喂管线。
    pub pending_commands: Vec<OrderCommand>,
    /// 对应 Java `LiquidationEngine.loanLiquidationEngine`：现货借贷强平扫描器委托对象，产出命令收拢进 pending_commands。
    pub loan_liquidation_engine: LoanLiquidationEngine,
}

impl LiquidationEngine {
    pub fn new() -> Self {
        LiquidationEngine::default()
    }

    /// 期货 + 借贷扫描器共用的切片过滤，委托 [`scheduler::covered_by_scan_slice`]。
    pub fn covered_by_scan_slice(cmd: &OrderCommand, uid: i64) -> bool {
        crate::core::processors::liquidation::scheduler::covered_by_scan_slice(cmd, uid)
    }

    /// 对应 Java `onPositionOpened`（`:151-153`）：开仓 apply 登记 uid 进 symbol→持有者索引。
    pub fn on_position_opened(&mut self, uid: i64, symbol: i32) {
        self.symbol_to_users.entry(symbol).or_default().insert(uid);
    }

    /// 对应 Java `onPositionClosed`（`:155-168`）：平仓 apply 摘除 uid，HEDGE 安全（同 symbol 仍有其它方向仓位则不删）。
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

    /// 对应 Java `checkPositions(cmd)`（`:130-148`）：强平检测入口（leader-only）。targeted（`symbol>=0`）查索引；`LIQUIDATION_SCAN`（`symbol<0`）全量整扫+切片过滤兜底。
    pub fn check_positions(
        &mut self,
        cmd: &OrderCommand,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, i64>,
        loan_service: &LoanService,
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
            self.check_user(*uid, cmd.timestamp, ups, ssp, last_price_cache);
        }

        // lazy-prune：targeted 检测时顺带清理已无该 symbol 仓位的持有者（等效 Java eager 摘除，索引精度不影响正确性）。
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
        // 尾部委托借贷扫描器（对应 Java `checkPositions:147` loanLiquidationEngine.checkLoans）。
        self.loan_liquidation_engine.check_loans(cmd, ups, ssp, last_price_cache, loan_service);
        self.pending_commands.append(&mut self.loan_liquidation_engine.pending_commands);
    }

    /// 对应 Java `checkUser`（`:171-197`）：逐仓分类，ISOLATED 立即判定、CROSS 交给 `check_cross_decisions`；拆两阶段（只读算决策/`&mut` 应用）应对 Rust 借用规则，行为与 Java 一步到位等价。
    fn check_user(
        &mut self,
        uid: i64,
        ts: i64,
        ups: &mut UserProfileService,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, i64>,
    ) {
        // ---- 阶段 1：只读 profile，算全部决策 ----
        let decisions: Vec<LiquidationDecision> = {
            let profile = match ups.get(uid) {
                Some(p) => p,
                None => return,
            };
            let mut decisions = Vec::new();
            // CROSS 按 quote_currency 分组；ISOLATED 直接判定。
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
                    Some(&p) => p,
                    None => continue,
                };
                if position.margin_mode == MarginMode::Isolated {
                    if let Some(d) = Self::check_isolated_decision(key, position, spec, mark_price) {
                        decisions.push(d);
                    }
                } else {
                    cross_by_currency.entry(spec.quote_currency).or_default().push(key);
                }
            }
            Self::check_cross_decisions(profile, &cross_by_currency, ssp, last_price_cache, &mut decisions);
            decisions
        };

        // ---- 阶段 2：&mut 逐条应用（幂等门 + 置 flow + 入队 FORCE）----
        for d in decisions {
            let profile = match ups.get_mut(uid) {
                Some(p) => p,
                None => return,
            };
            self.start_liquidation_flow(profile, d, ts);
        }
    }

    /// 对应 Java `checkIsolated`（`:199-215`）——纯判定版：`equity`（含 extra_margin）`< MM` 触发；逐字对齐 Java，equity 与 `calculate_size_to_liquidate` 内部的 E（不含 extra_margin）是两个不同量。越预警线 no-op（Ruling P6-B）。
    fn check_isolated_decision(
        position_key: i32,
        position: &SymbolPositionRecord,
        spec: &CoreSymbolSpecification,
        mark_price: i64,
    ) -> Option<LiquidationDecision> {
        let profit = position.estimate_unrealized_profit(mark_price);
        let equity = position.open_init_margin_sum + profit + position.extra_margin;
        let maintenance_margin = position.calculate_maintenance_margin(spec, mark_price);
        if equity >= maintenance_margin {
            return None; // >= MM：健康或仅越预警线（P6-B no-op）
        }
        let bankruptcy_price = position.calculate_bankruptcy_price(spec, |_| 0); // NO_CROSS
        let size_to_liquidate = position.open_volume.min(Self::size_to_liquidate_for(position, maintenance_margin, mark_price));
        if size_to_liquidate <= 0 {
            return None;
        }
        Some(LiquidationDecision { position_key, bankruptcy_price, size: size_to_liquidate })
    }

    /// 对应 Java `checkCross`（`:218-264`）+ `forceCrossLiquidation`（`:266-286`）——纯判定版：逐 quote 币种算账户级 equity/风险度，`equity < MM` 时按风险度升序逐仓强平至覆盖 deficit；`MM<=equity<1.2×MM` 仅预警（P6-B no-op）。
    fn check_cross_decisions(
        profile: &UserProfile,
        cross_by_currency: &BTreeMap<i32, Vec<i32>>,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, i64>,
        decisions: &mut Vec<LiquidationDecision>,
    ) {
        if cross_by_currency.is_empty() {
            return;
        }
        // alloc：整账户 CROSS 仓破产价 marginBase 回调（P4 已移植）。
        let alloc = profile.cross_margin_base_allocation(
            |s| ssp.get_symbol(s),
            |c| ssp.get_currency(c),
            |s| last_price_cache.get(&s).copied(),
        );

        for (&currency, keys) in cross_by_currency.iter() {
            let currency_spec = match ssp.get_currency(currency) {
                Some(c) => c,
                None => continue,
            };
            let mut total_profit: i64 = 0;
            let mut total_maintenance: i64 = 0;
            // (风险度, position_key)，升序排序（最危险优先）。
            let mut risk_pairs: Vec<(i64, i32)> = Vec::new();
            for &key in keys {
                let position = &profile.positions[&key];
                let spec = match ssp.get_symbol(position.symbol) {
                    Some(s) => s,
                    None => continue,
                };
                let mark_price = match last_price_cache.get(&position.symbol) {
                    Some(&p) => p,
                    None => continue,
                };
                let raw_maintenance = position.calculate_maintenance_margin(spec, mark_price);
                if raw_maintenance == 0 {
                    continue; // 无 MM 要求：不占账户风险
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
                    // 缩放后归零不能做除数，仅不参与风险排序（逐字对齐 Java :246）。
                    let risk = mul_exact_local(profit - maintenance, 100) / maintenance;
                    risk_pairs.push((risk, key));
                }
            }
            let equity = total_profit
                + profile.calculate_cross_available(currency, currency_spec, |s| ssp.get_symbol(s));
            let warning_threshold = mul_exact_local(total_maintenance, 6) / 5; // 1.2×
            if equity >= warning_threshold {
                continue;
            }
            if equity >= total_maintenance {
                continue; // MM <= equity < 1.2×MM：仅预警（P6-B no-op）
            }
            // 风险度升序（最危险优先），稳定排序对齐 Java Comparator。
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

    /// 对应 Java `forceCrossLiquidation`（`:266-286`）——纯判定版：风险度升序逐仓强平直至覆盖 deficit；marginReleased 无条件累加（逐字对齐 Java）。
    #[allow(clippy::too_many_arguments)]
    fn force_cross_decisions(
        profile: &UserProfile,
        risk_pairs: &[(i64, i32)],
        deficit: i64,
        alloc: &BTreeMap<i32, i64>,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, i64>,
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
                Some(&p) => p,
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

    /// position → `alloc` map 键（ONEWAY=symbol / HEDGE=±symbol，与 `cross_margin_base_allocation` 产出键一致）。
    fn pos_key(p: &SymbolPositionRecord) -> i32 {
        match p.direction {
            PositionDirection::Short => -p.symbol,
            _ => p.symbol,
        }
    }

    /// [`calculate_size_to_liquidate`] 标量提取（对齐 Java）：E 不含 extra_margin（见 `check_isolated_decision` 注）。
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

    /// [`calculate_deficit_after_liquidate`] 标量提取：两次查 spec 分档 MM（notionalNow/notionalAfter）。
    fn deficit_after_for(
        position: &SymbolPositionRecord,
        spec: &CoreSymbolSpecification,
        size: i64,
        mark_price: i64,
    ) -> i64 {
        let notional_now = mul_exact_local(position.open_volume, mark_price);
        let notional_after = mul_exact_local(position.open_volume - size, mark_price);
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

    /// 对应 Java `startLiquidationFlow`（`:288-299`）：幂等提交 FORCE（已有 flow 则跳过），预警通知不移植（Ruling P6-B）。
    fn start_liquidation_flow(&mut self, profile: &mut UserProfile, d: LiquidationDecision, ts: i64) {
        let uid = profile.uid;
        let position = match profile.positions.get_mut(&d.position_key) {
            Some(p) => p,
            None => return, // 决策与应用间仓位已消失（极端时序）——skip
        };
        if position.liquidation_flow.is_some() {
            return; // 幂等门
        }
        let order_id =
            LiquidationService::generate_liquidation_order_id(uid, position.symbol, position.direction, ts);
        position.liquidation_flow = Some(LiquidationFlow::new(d.bankruptcy_price, d.size, order_id));
        let force_cmd = Self::build_force_cmd(uid, position.symbol, position.direction, order_id, d.bankruptcy_price, d.size, ts);
        self.pending_commands.push(force_cmd);
    }

    /// 对应 Java `advanceLiquidation(cmd, pos)`（`:310-345`）：强平命令 apply 后推进 FORCE→IF→ADL 状态机（leader-only），flow=None 时 FORCE 命令触发换届残余仓恢复，否则校验 state 合法性防重复/错序。
    pub fn advance_liquidation(&mut self, cmd: &OrderCommand, pos: &mut SymbolPositionRecord) {
        if !self.is_running {
            return;
        }
        match pos.liquidation_flow {
            None => {
                if cmd.command != OrderCommandType::ForceLiquidation {
                    return; // 非法：无进行中流程却来非 FORCE——skip（对齐 Java log.warn）
                }
                // 换届后残余仓恢复：新建流程（对齐 Java :324）。
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
                    // 重复/错序推进——skip（对齐 Java log.warn + return）。
                    return;
                }
            }
        }
        match cmd.command {
            OrderCommandType::ForceLiquidation => self.on_force_applied(cmd, pos),
            OrderCommandType::IfTakeover => self.on_if_takeover_applied(cmd, pos),
            OrderCommandType::AutoDeleveraging => pos.liquidation_flow = None, // ADL 恒终态
            _ => {}
        }
    }

    /// 对应 Java `onForceApplied`（`:347-361`）：非 REJECT→闭环；REJECT（剩余量）→转 `WaitIfExecution`、入队 IF。
    fn on_force_applied(&mut self, cmd: &OrderCommand, pos: &mut SymbolPositionRecord) {
        let rejected = matches!(&cmd.matcher_event, Some(ev) if ev.event_type == MatcherEventType::Reject);
        if !rejected {
            pos.liquidation_flow = None;
            return;
        }
        let remaining = cmd.matcher_event.as_ref().map(|e| e.size).unwrap_or(0);
        // flow 一定存在（advance_liquidation 已保证）。
        if let Some(flow) = pos.liquidation_flow.as_mut() {
            flow.size = remaining;
            flow.state = LiquidationState::WaitIfExecution;
        }
        if let Some(flow) = pos.liquidation_flow {
            let if_cmd = Self::build_if_cmd(pos.uid, pos.symbol, pos.direction, &flow, cmd.timestamp);
            self.pending_commands.push(if_cmd);
        }
    }

    /// 对应 Java `onIfTakeoverApplied`（`:363-374`）：IF 单 apply 回调。非 REJECT（接管成功）→ 闭环；
    /// REJECT（IF 池不足、仅部分接管）→ 转 `WaitAdlExecution`、入队 ADL 命令。
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
            self.pending_commands.push(adl_cmd);
        }
    }

    /// 对应 Java `buildForceCmd`（`:376-380`）：IOC → `FORCE_LIQUIDATION`，action 与持仓方向相反。
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

    /// 对应 Java `buildIFCmd`（`:382-386`）：→ `IF_TAKEOVER`，orderId 由根强平 orderId 派生（`'I'`），action 为接管方向（perspective-flip，§1.4）。
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

    /// 对应 Java `buildADLCmd`（`:388-393`）：→ `AUTO_DELEVERAGING`，orderId 由根强平 orderId 派生（`'A'`），action 同 IF 接管方向。
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

/// 对应 Java `Math.multiplyExact`：`i128` 中间精度、溢出 panic（本文件本地重复一份，同 `liquidation_service.rs` 风格）。
fn mul_exact_local(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 * b as i128).unwrap_or_else(|_| panic!("overflow: {a} * {b}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::matcher_trade_event::MatcherTradeEvent;
    use crate::core::common::symbol_type::SymbolType;

    const FUT_SYMBOL: i32 = 200;
    const FUT_QUOTE: i32 = 2;
    const FUT_BASE: i32 = 1;
    const UID: i64 = 1;

    /// 期货 spec：MM 单档 5%（rate=500, scale=10000）；base/quote scale=1（恒等缩放）。
    fn futures_spec() -> CoreSymbolSpecification {
        let mut mm = BTreeMap::new();
        mm.insert(i64::MAX, 500); // 单档 5%
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

    fn seeded() -> (LiquidationEngine, UserProfileService, SymbolSpecificationProvider, BTreeMap<i32, i64>) {
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

    /// 插入一个 ISOLATED LONG 仓（open_volume=10、avg=100、margin=100）。
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
            next: None,
        })
    }

    // ---------------- covered_by_scan_slice ----------------

    #[test]
    fn covered_by_scan_slice_non_scan_always_covered() {
        let cmd = markprice_cmd(FUT_SYMBOL, 0);
        assert!(LiquidationEngine::covered_by_scan_slice(&cmd, 12345));
    }

    #[test]
    fn covered_by_scan_slice_matches_and_misses() {
        // LIQUIDATION_SCAN：cmd.size = sliceCount = 10，cmd.uid = scanSlice = 3。
        let cmd = OrderCommand { command: OrderCommandType::LiquidationScan, symbol: -1, uid: 3, size: 10, ..Default::default() };
        assert!(LiquidationEngine::covered_by_scan_slice(&cmd, 13), "13 mod 10 == 3 -> in slice");
        assert!(!LiquidationEngine::covered_by_scan_slice(&cmd, 14), "14 mod 10 == 4 != 3 -> out of slice");
        // sliceCount<=0 -> 全扫
        let full = OrderCommand { command: OrderCommandType::LiquidationScan, symbol: -1, uid: 0, size: 0, ..Default::default() };
        assert!(LiquidationEngine::covered_by_scan_slice(&full, 999));
    }

    // ---------------- symbol_to_users 索引 ----------------

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
        // profile 有单仓，关掉它（closed_key = FUT_SYMBOL，且无其它同 symbol 仓）。
        insert_long(&mut ups, UID);
        engine.on_position_closed(ups.get(UID).unwrap(), FUT_SYMBOL, FUT_SYMBOL);
        assert!(engine.symbol_to_users.get(&FUT_SYMBOL).is_none(), "无其它仓 -> uid 移除 + 空集清理");
    }

    #[test]
    fn on_position_closed_hedge_keeps_uid_when_other_side_exists() {
        let (mut engine, mut ups, _ssp, _lpc) = seeded();
        engine.on_position_opened(UID, FUT_SYMBOL);
        // HEDGE：同 symbol 两方向（key=+symbol LONG、key=-symbol SHORT）。关掉 +symbol，-symbol 仍在。
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
            "同 symbol 反向仓仍在 -> 不得误删持有者（HEDGE 安全）"
        );
    }

    // ---------------- leader gate ----------------

    #[test]
    fn check_positions_leader_gate_off_is_noop() {
        let (mut engine, mut ups, ssp, mut lpc) = seeded();
        engine.is_running = false; // follower
        engine.on_position_opened(UID, FUT_SYMBOL);
        insert_long(&mut ups, UID);
        lpc.insert(FUT_SYMBOL, 50); // 深度水下
        let cmd = markprice_cmd(FUT_SYMBOL, 1_000);
        engine.check_positions(&cmd, &mut ups, &ssp, &lpc, &LoanService::new());
        assert!(engine.pending_commands.is_empty(), "follower 不检测、不提交");
        assert!(ups.get(UID).unwrap().positions[&FUT_SYMBOL].liquidation_flow.is_none());
    }

    // ---------------- ISOLATED 检测（targeted） ----------------

    #[test]
    fn check_positions_targeted_isolated_underwater_queues_force_and_sets_flow() {
        let (mut engine, mut ups, ssp, mut lpc) = seeded();
        engine.on_position_opened(UID, FUT_SYMBOL);
        insert_long(&mut ups, UID);
        lpc.insert(FUT_SYMBOL, 50); // mark=50：profit=-500，equity=-400 < MM=25 -> 触发
        let cmd = markprice_cmd(FUT_SYMBOL, 5_000);

        engine.check_positions(&cmd, &mut ups, &ssp, &lpc, &LoanService::new());

        assert_eq!(engine.pending_commands.len(), 1, "触发一条 FORCE");
        let force = &engine.pending_commands[0];
        assert_eq!(force.command, OrderCommandType::ForceLiquidation);
        assert_eq!(force.uid, UID);
        assert_eq!(force.symbol, FUT_SYMBOL);
        assert_eq!(force.action, Some(OrderAction::Ask), "LONG 强平 -> ASK（平仓方向相反）");
        assert_eq!(force.order_type, Some(OrderType::Ioc));
        assert_eq!(force.size, 10, "size_to_liquidate = min(open_volume, calc) = 10");
        // flow 已置，price 一致。
        let flow = ups.get(UID).unwrap().positions[&FUT_SYMBOL].liquidation_flow.expect("flow set");
        assert_eq!(flow.state, LiquidationState::Liquidating);
        assert_eq!(flow.size, 10);
        assert_eq!(force.price, flow.bankruptcy_price, "FORCE 限价 = 破产价");
        assert_eq!(force.order_id, flow.original_order_id);
    }

    #[test]
    fn check_positions_healthy_position_no_force() {
        let (mut engine, mut ups, ssp, mut lpc) = seeded();
        engine.on_position_opened(UID, FUT_SYMBOL);
        insert_long(&mut ups, UID);
        lpc.insert(FUT_SYMBOL, 100); // mark=100：profit=0，equity=100 >= MM=50 -> 健康
        let cmd = markprice_cmd(FUT_SYMBOL, 1_000);

        engine.check_positions(&cmd, &mut ups, &ssp, &lpc, &LoanService::new());

        assert!(engine.pending_commands.is_empty(), "健康仓不触发");
        assert!(ups.get(UID).unwrap().positions[&FUT_SYMBOL].liquidation_flow.is_none());
    }

    #[test]
    fn check_positions_idempotent_second_scan_no_double_submit() {
        let (mut engine, mut ups, ssp, mut lpc) = seeded();
        engine.on_position_opened(UID, FUT_SYMBOL);
        insert_long(&mut ups, UID);
        lpc.insert(FUT_SYMBOL, 50);
        let cmd = markprice_cmd(FUT_SYMBOL, 5_000);

        engine.check_positions(&cmd, &mut ups, &ssp, &lpc, &LoanService::new());
        engine.check_positions(&cmd, &mut ups, &ssp, &lpc, &LoanService::new()); // 第二次：flow 已存在 -> 幂等跳过

        assert_eq!(engine.pending_commands.len(), 1, "flow 已在 -> 第二次不重复提交（幂等门）");
    }

    #[test]
    fn check_positions_scan_slice_filters_users() {
        let (mut engine, mut ups, ssp, mut lpc) = seeded();
        // 两个水下用户：uid=1（mod 2 == 1）、uid=2（mod 2 == 0）。
        ups.add_empty_user_profile(2);
        insert_long(&mut ups, 1);
        insert_long(&mut ups, 2);
        lpc.insert(FUT_SYMBOL, 50);
        // scan slice：sliceCount=2、scanSlice=1 -> 只查 uid mod 2 == 1（uid=1），跳过 uid=2。
        let scan = OrderCommand { command: OrderCommandType::LiquidationScan, symbol: -1, uid: 1, size: 2, timestamp: 5_000, ..Default::default() };

        engine.check_positions(&scan, &mut ups, &ssp, &lpc, &LoanService::new());

        assert_eq!(engine.pending_commands.len(), 1, "只 uid=1 在切片内");
        assert_eq!(engine.pending_commands[0].uid, 1);
        assert!(ups.get(2).unwrap().positions[&FUT_SYMBOL].liquidation_flow.is_none(), "uid=2 不在切片内，未触碰");
    }

    // ---------------- 状态机 advance_liquidation ----------------

    /// 造一个带进行中 flow（Liquidating）的 LONG 仓，返回 (engine, pos)。
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
        let mut pos = pos_with_flow(LiquidationState::Liquidating);
        let cmd = force_apply_cmd(Some(mte(MatcherEventType::Trade, 10)));
        engine.advance_liquidation(&cmd, &mut pos);
        assert!(pos.liquidation_flow.is_none(), "FORCE 完全成交 -> 闭环");
        assert!(engine.pending_commands.is_empty());
    }

    #[test]
    fn advance_force_reject_transitions_to_wait_if_and_queues_if() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let mut pos = pos_with_flow(LiquidationState::Liquidating);
        // REJECT 携带剩余未成交量 = 7。
        let cmd = force_apply_cmd(Some(mte(MatcherEventType::Reject, 7)));
        engine.advance_liquidation(&cmd, &mut pos);
        let flow = pos.liquidation_flow.expect("flow 保留");
        assert_eq!(flow.state, LiquidationState::WaitIfExecution);
        assert_eq!(flow.size, 7, "flow.size 更新为 REJECT 剩余量");
        assert_eq!(engine.pending_commands.len(), 1);
        let ifc = &engine.pending_commands[0];
        assert_eq!(ifc.command, OrderCommandType::IfTakeover);
        assert_eq!(ifc.size, 7);
        assert_eq!(ifc.action, Some(OrderAction::Bid), "LONG 的 IF 接管 -> BID（perspective flip）");
        assert_eq!(ifc.order_id, LiquidationService::generate_if_order_id(777));
    }

    #[test]
    fn advance_if_reject_transitions_to_wait_adl_and_queues_adl() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let mut pos = pos_with_flow(LiquidationState::WaitIfExecution);
        let cmd = OrderCommand { command: OrderCommandType::IfTakeover, uid: UID, symbol: FUT_SYMBOL, matcher_event: Some(mte(MatcherEventType::Reject, 7)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        let flow = pos.liquidation_flow.expect("flow 保留");
        assert_eq!(flow.state, LiquidationState::WaitAdlExecution);
        assert_eq!(engine.pending_commands.len(), 1);
        let adl = &engine.pending_commands[0];
        assert_eq!(adl.command, OrderCommandType::AutoDeleveraging);
        assert_eq!(adl.action, Some(OrderAction::Bid));
        assert_eq!(adl.order_id, LiquidationService::generate_adl_order_id(777));
    }

    #[test]
    fn advance_if_non_reject_closes_flow() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let mut pos = pos_with_flow(LiquidationState::WaitIfExecution);
        let cmd = OrderCommand { command: OrderCommandType::IfTakeover, uid: UID, symbol: FUT_SYMBOL, matcher_event: Some(mte(MatcherEventType::Trade, 7)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        assert!(pos.liquidation_flow.is_none(), "IF 接管成功 -> 闭环");
        assert!(engine.pending_commands.is_empty());
    }

    #[test]
    fn advance_adl_is_terminal() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let mut pos = pos_with_flow(LiquidationState::WaitAdlExecution);
        let cmd = OrderCommand { command: OrderCommandType::AutoDeleveraging, uid: UID, symbol: FUT_SYMBOL, matcher_event: Some(mte(MatcherEventType::Trade, 7)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        assert!(pos.liquidation_flow.is_none(), "ADL 恒终态");
        assert!(engine.pending_commands.is_empty());
    }

    #[test]
    fn advance_null_flow_non_force_skips() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let mut pos = SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1);
        // flow=None + IF 命令 -> 非法，skip（不 panic、不建 flow）。
        let cmd = OrderCommand { command: OrderCommandType::IfTakeover, uid: UID, symbol: FUT_SYMBOL, matcher_event: Some(mte(MatcherEventType::Reject, 7)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        assert!(pos.liquidation_flow.is_none());
        assert!(engine.pending_commands.is_empty());
    }

    #[test]
    fn advance_null_flow_force_recovers_new_flow() {
        // 换届后残余仓恢复：flow=None + FORCE -> 新建 flow 再推进。
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let mut pos = SymbolPositionRecord {
            direction: PositionDirection::Long,
            open_volume: 10,
            ..SymbolPositionRecord::new(UID, FUT_SYMBOL, FUT_QUOTE, MarginMode::Isolated, 1)
        };
        // FORCE REJECT：recovery 建 flow(Liquidating)，随即 onForceApplied REJECT -> WaitIf + 入队 IF。
        let cmd = OrderCommand { command: OrderCommandType::ForceLiquidation, uid: UID, symbol: FUT_SYMBOL, price: 45, size: 8, order_id: 555, matcher_event: Some(mte(MatcherEventType::Reject, 8)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        let flow = pos.liquidation_flow.expect("recovery 建了 flow");
        assert_eq!(flow.state, LiquidationState::WaitIfExecution);
        assert_eq!(flow.original_order_id, 555, "recovery flow 用 cmd.order_id 作根 orderId");
        assert_eq!(engine.pending_commands.len(), 1);
        assert_eq!(engine.pending_commands[0].command, OrderCommandType::IfTakeover);
    }

    #[test]
    fn advance_out_of_order_command_skips() {
        // flow.state=Liquidating 却来了 IF 命令（期望 WaitIfExecution）-> 重复/错序，skip。
        let mut engine = LiquidationEngine::new();
        engine.is_running = true;
        let mut pos = pos_with_flow(LiquidationState::Liquidating);
        let cmd = OrderCommand { command: OrderCommandType::IfTakeover, uid: UID, symbol: FUT_SYMBOL, matcher_event: Some(mte(MatcherEventType::Reject, 7)), timestamp: 6_000, ..Default::default() };
        engine.advance_liquidation(&cmd, &mut pos);
        let flow = pos.liquidation_flow.expect("flow 不动");
        assert_eq!(flow.state, LiquidationState::Liquidating, "错序命令被跳过，state 不变");
        assert!(engine.pending_commands.is_empty());
    }

    #[test]
    fn advance_leader_gate_off_is_noop() {
        let mut engine = LiquidationEngine::new();
        engine.is_running = false;
        let mut pos = pos_with_flow(LiquidationState::Liquidating);
        let cmd = force_apply_cmd(Some(mte(MatcherEventType::Trade, 10)));
        engine.advance_liquidation(&cmd, &mut pos);
        assert_eq!(pos.liquidation_flow.map(|f| f.state), Some(LiquidationState::Liquidating), "follower 不推进");
    }
}
