//! 借贷强平引擎（对应 Java `exchange.core2.core.processors.loan.LoanLiquidationEngine`）。
//!
//! leader-local 扫描器：由 [`crate::core::processors::liquidation::liquidation_engine`] 委托
//! （调用方已过 leader gate），对 Isolated / Cross 借贷分别做 LTV 越线检测，产出待提交的强平
//! `OrderCommand`（Isolated: `LoanForceLiquidate`；Cross: `LoanCrossForceLiquidate`）以及预警
//! `FundEvent`（`LoanMarginCall`）。价格事件走 `symbolId`/`(base,quote)` targeted 索引只查受影响的
//! 持有者，`LIQUIDATION_SCAN` 全量兜底（按 [`covered_by_scan_slice`] 分片）。
//!
//! 与 Java 版的结构性差异：Java 通过 `commandSubmitter`/`eventsHelper` 把命令与事件直接提交/发送；
//! Rust 版把二者收集进 `pending_commands` / 调用方传入的 `fund_events: &mut Vec<FundEvent>`，
//! 由上层统一处理，本身不持有提交通道。

use std::collections::{BTreeMap, BTreeSet};

use crate::core::common::last_price_cache_record::LastPriceCacheRecord;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::cross_loan_record::CrossLoanRecord;
use crate::core::common::fund_event::{FundEvent, FundEventType};
use crate::core::common::isolated_loan_record::{IsolatedLoanRecord, LoanRateMode};
use crate::core::common::order_action::OrderAction;
use crate::core::common::order_type::OrderType;
use crate::core::common::user_profile::UserProfile;
use crate::core::processors::liquidation::scheduler::covered_by_scan_slice;
use crate::core::processors::loan::loan_service::{
    LoanService, BPS_SCALE, ORDERID_SUBTYPE_CROSS, ORDERID_SUBTYPE_ISOLATED,
};
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;
use crate::core::utils::core_arithmetic_utils::{add_exact, ceil_mul_div, mul_exact};

/// 天 → 毫秒，期限强平（LOCKED 超期）换算，对应 Java `MS_PER_DAY`。
const MS_PER_DAY: i64 = 86_400 * 1_000;

/// 对应 Java `LoanLiquidationEngine` 的字段：
/// - `isolated_loan_symbol_to_users`：symbolId → 持有该 symbol 非空 isolated loan 的 uid 集合。
/// - `cross_loan_currency_to_users`：currency（抵押币或借款币）→ 有该币种敞口的 uid 集合。
/// 两个索引均为 targeted 索引，由 dispatcher 在 apply 时确定性更新，不进快照（见 Java 注释：
/// "targeted 索引维护：由 LoanCommandDispatcher 在 apply 时确定性更新，不进 snapshot"）。
#[derive(Debug, Default)]
pub struct LoanLiquidationEngine {
    pub isolated_loan_symbol_to_users: BTreeMap<i32, BTreeSet<i64>>,
    pub cross_loan_currency_to_users: BTreeMap<i32, BTreeSet<i64>>,
    pub pending_commands: Vec<OrderCommand>,
}

impl LoanLiquidationEngine {
    pub fn new() -> Self {
        LoanLiquidationEngine::default()
    }

    /// 对应 Java `updateProvider(...)` 中索引重建部分：清空两个索引后，
    /// 按当前 `UserProfileService` 全量重放 isolated 开仓登记 + cross 敞口同步。
    /// 用于快照恢复 / 节点重启后从复制状态重建 leader-local 派生索引。
    pub fn rebuild_indices(&mut self, ups: &UserProfileService) {
        self.isolated_loan_symbol_to_users.clear();
        self.cross_loan_currency_to_users.clear();
        for up in ups.users.values() {
            for loan in up.isolated_loans.values() {
                if !loan.is_empty() {
                    self.on_isolated_loan_opened(up.uid, loan.symbol_id);
                }
            }
            self.sync_cross_exposure(up);
        }
    }

    /// 对应 Java `checkLoans(OrderCommand)`：强平检测入口，由上层强平引擎委托（调用方已过
    /// leader gate）。`cmd.symbol >= 0` 时查索引，只检 targeted 索引命中（受该 symbol 价格变动
    /// 影响）的 isolated 持有者 ∪ 该 symbol 两条腿币种上有 cross 敞口的持有者；`cmd.symbol < 0` 时
    /// 走 `LIQUIDATION_SCAN` 全量兜底，按 [`covered_by_scan_slice`] 分片遍历全体用户。
    pub fn check_loans(
        &mut self,
        cmd: &OrderCommand,
        ups: &UserProfileService,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        loan_service: &LoanService,
        fund_events: &mut Vec<FundEvent>,
    ) {
        if cmd.symbol >= 0 {
            let spec = match ssp.get_symbol(cmd.symbol) {
                Some(s) => s,
                None => return,
            };
            let mut uids: BTreeSet<i64> = BTreeSet::new();
            if let Some(iso) = self.isolated_loan_symbol_to_users.get(&spec.symbol_id) {
                uids.extend(iso.iter().copied());
            }
            if let Some(base) = self.cross_loan_currency_to_users.get(&spec.base_currency) {
                uids.extend(base.iter().copied());
            }
            if let Some(quote) = self.cross_loan_currency_to_users.get(&spec.quote_currency) {
                uids.extend(quote.iter().copied());
            }
            for uid in uids {
                if let Some(up) = ups.get(uid) {
                    self.check_user(up, cmd.timestamp, ssp, last_price_cache, loan_service, fund_events);
                }
            }
            return;
        }
        for up in ups.users.values() {
            if !covered_by_scan_slice(cmd, up.uid) {
                continue;
            }
            self.check_user(up, cmd.timestamp, ssp, last_price_cache, loan_service, fund_events);
        }
    }

    /// 对应 Java `checkUser(UserProfile, long)`：对单个用户依次检查其全部 isolated loan
    /// 与 cross 借贷敞口。
    fn check_user(
        &mut self,
        up: &UserProfile,
        ts: i64,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        loan_service: &LoanService,
        fund_events: &mut Vec<FundEvent>,
    ) {
        for loan in up.isolated_loans.values() {
            self.check_isolated(loan, ts, ssp, last_price_cache, loan_service, fund_events);
        }
        self.check_cross(up, ts, ssp, last_price_cache, loan_service, fund_events);
    }

    /// 对应 Java `checkIsolated(IsolatedLoanRecord, long)`：单笔 isolated loan 的 LTV/期限检测。
    /// 触发条件二选一即强平：①定息 LOCKED 且超过 `max_term_days`（期限强平，不看 LTV）；
    /// ②`real_debt/collateral_value >= liquidation_ltv_bps`。真实债务含利息
    /// （`outstanding_principal` + 已计提/pending 的 accrue 利息），防止靠拖欠计提利息规避强平线。
    /// 未越强平线但越预警线（`margin_call_ltv_bps`）时只产出 `LoanMarginCall` 事件，不下单。
    fn check_isolated(
        &mut self,
        loan: &IsolatedLoanRecord,
        ts: i64,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        loan_service: &LoanService,
        fund_events: &mut Vec<FundEvent>,
    ) {
        if loan.is_empty() {
            return;
        }
        let spec = match ssp.get_symbol(loan.symbol_id) {
            Some(s) => s,
            None => return,
        };
        let mark_price = match last_price_cache.get(&loan.symbol_id) {
            Some(r) if r.mark_price != 0 => r.mark_price,
            _ => return,
        };
        let base_spec = ssp.get_currency(loan.collateral_currency);
        let loan_currency_spec = ssp.get_currency(loan.loan_currency);
        let collateral_value =
            LoanService::collateral_value_in_quote_currency(loan.collateral_amount, spec, mark_price, base_spec, loan_currency_spec);
        if collateral_value <= 0 {
            // 抵押估值为 0 无法定破产价（除零）
            return;
        }
        let real_debt = add_exact(loan.outstanding_principal, loan_service.calculate_display_interest(loan, ts));
        let ltv_scaled = mul_exact(real_debt, BPS_SCALE);

        // 期限强平仅对 Isolated LOCKED（定息）生效；FLOATING 无期限
        let term_expired = loan.rate_mode == LoanRateMode::Locked
            && spec.loan_config.max_term_days > 0
            && (ts - loan.opened_at_ts) > spec.loan_config.max_term_days as i64 * MS_PER_DAY;

        if term_expired || ltv_scaled >= mul_exact(collateral_value, spec.loan_config.liquidation_ltv_bps as i64) {
            let base_spec = match base_spec {
                Some(b) => b,
                None => return,
            };
            // 抵押 → 下单张数；不足一张的尘埃卖不掉，留在 collateral_amount 跳过本轮
            let sell_size_lots = LoanService::collateral_amount_to_lots(loan.collateral_amount, spec, base_spec);
            if sell_size_lots <= 0 {
                return;
            }
            // orderId 空间与 futures 强平隔离，见 LoanService::force_sell_order_id
            let order_id = LoanService::force_sell_order_id(ORDERID_SUBTYPE_ISOLATED, loan.uid, loan.loan_id, ts);
            // 破产价 = markPrice × 债务 / 抵押估值，即卖出所得刚好覆盖债务的地板价（ceil 取整确保
            // 地板不低于真实盈亏平衡点）；对应 Java 私有方法 bankruptcyPrice
            let limit_price = ceil_mul_div(mark_price, real_debt, collateral_value);
            self.pending_commands.push(OrderCommand {
                command: OrderCommandType::LoanForceLiquidate,
                order_id,
                uid: loan.uid,
                symbol: spec.symbol_id,
                price: limit_price,
                size: sell_size_lots,
                reserve_bid_price: loan.loan_id,
                action: Some(OrderAction::Ask),
                order_type: Some(OrderType::Ioc),
                timestamp: ts,
                ..Default::default()
            });
        } else if spec.loan_config.margin_call_ltv_bps > 0
            && ltv_scaled >= mul_exact(collateral_value, spec.loan_config.margin_call_ltv_bps as i64)
        {
            fund_events.push(FundEvent {
                event_type: FundEventType::LoanMarginCall,
                order_id: loan.loan_id,
                uid: loan.uid,
                currency: loan.loan_currency,
                loan_mode: 0,
                loan_ltv_bps: if collateral_value == 0 { 0 } else { ltv_scaled / collateral_value },
                loan_threshold_bps: spec.loan_config.margin_call_ltv_bps as i64,
                loan_collateral_currency: loan.collateral_currency,
                loan_collateral_pledged: loan.collateral_amount,
                ..Default::default()
            });
        }
    }

    /// 对应 Java `checkCross(UserProfile, long)`：Cross 账户级强平判定，越线后渐进去杠杆，
    /// 每轮只选一对（卖出抵押币, 偿还目标 loan）成交，多轮收敛（每次调用最多产出一条命令）。
    ///
    /// “存在就绪现货对”必须是 pick 的过滤条件而非事后校验：两个 pick（见
    /// [`Self::pick_cross_collateral_to_sell`] / [`Self::pick_cross_loan_to_repay`]）只看权重/利率、
    /// 与价格无关，因而完全确定性——若先各挑最优再校验市场，选出的那对没有现货对时会每轮重选出
    /// 同一对、永久空转。下沉进 pick 后，最优组合无市场会自动退到次优可成交组合。
    fn check_cross(
        &mut self,
        up: &UserProfile,
        ts: i64,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
        loan_service: &LoanService,
        fund_events: &mut Vec<FundEvent>,
    ) {
        if up.cross_loans.is_empty() {
            return;
        }
        let ltv_bps = loan_service.calculate_cross_account_ltv_bps(up, ts, ssp, last_price_cache, false);
        if ltv_bps < loan_service.global_config.cross_liquidation_ltv_bps as i64 {
            if ltv_bps >= loan_service.global_config.cross_margin_call_ltv_bps as i64 {
                // Cross 账户级预警：loanId / loanCurrency 无单笔归属，留默认值（0）
                fund_events.push(FundEvent {
                    event_type: FundEventType::LoanMarginCall,
                    uid: up.uid,
                    loan_mode: 1,
                    loan_ltv_bps: ltv_bps,
                    loan_threshold_bps: loan_service.global_config.cross_margin_call_ltv_bps as i64,
                    ..Default::default()
                });
            }
            return;
        }
        let selling_currency = match self.pick_cross_collateral_to_sell(up, ssp, last_price_cache) {
            Some(c) => c,
            None => return,
        };
        let target_loan = match self.pick_cross_loan_to_repay(up, selling_currency, ssp, last_price_cache) {
            Some(l) => l,
            None => return,
        };
        // pick 的前置约束已保证该现货对存在且 markPrice 就绪，此处直接取用、无需再判空
        let spec = ssp.find_spot_symbol(selling_currency, target_loan.loan_currency).expect("pick guarantees the spot pair exists");
        let mark_price = last_price_cache.get(&spec.symbol_id).expect("pick guarantees markPrice is ready").mark_price;
        let available_collateral = up.cross_loan_collateral(selling_currency);
        let selling_currency_spec = match ssp.get_currency(selling_currency) {
            Some(s) => s,
            None => return,
        };
        let loan_currency_spec = match ssp.get_currency(target_loan.loan_currency) {
            Some(s) => s,
            None => return,
        };
        // 破产价按市值口径 LTV 定（触发用加权 LTV，见 calculate_cross_account_ltv_bps）；卖量随
        // 该价算，按 markPrice 算则打折卖必然不够还债。市值口径估不出来（某抵押币无 numeraire
        // 估值路径）时退回加权 LTV：报价偏保守，但绝不因此放弃强平
        let raw_ltv_bps = loan_service.calculate_cross_raw_ltv_bps(up, ts, ssp, last_price_cache);
        let pricing_ltv_bps = if raw_ltv_bps > 0 { raw_ltv_bps } else { ltv_bps };
        let limit_price = ceil_mul_div(mark_price, pricing_ltv_bps, BPS_SCALE);
        let sell_size = Self::calculate_cross_sell_size(
            &target_loan,
            spec,
            limit_price,
            available_collateral,
            ts,
            loan_service,
            selling_currency_spec,
            loan_currency_spec,
        );
        if sell_size <= 0 {
            return;
        }
        let order_id = LoanService::force_sell_order_id(ORDERID_SUBTYPE_CROSS, up.uid, target_loan.loan_id, ts);
        self.pending_commands.push(OrderCommand {
            command: OrderCommandType::LoanCrossForceLiquidate,
            order_id,
            uid: up.uid,
            symbol: spec.symbol_id,
            price: limit_price,
            size: sell_size,
            reserve_bid_price: target_loan.loan_id,
            action: Some(OrderAction::Ask),
            order_type: Some(OrderType::Ioc),
            timestamp: ts,
            ..Default::default()
        });
    }

    /// 对应 Java `onIsolatedLoanOpened(long, int)`：isolated loan 开仓，uid 登记进 symbolId 索引。
    pub fn on_isolated_loan_opened(&mut self, uid: i64, symbol_id: i32) {
        self.isolated_loan_symbol_to_users.entry(symbol_id).or_default().insert(uid);
    }

    /// 对应 Java `onIsolatedLoanClosed(UserProfile, int)`：isolated loan 清空，仅当 uid 在该
    /// symbolId 上已无其它非空 loan 时才摘除（一 uid 可持多笔同 symbol 的 loan）。
    pub fn on_isolated_loan_closed(&mut self, up: &UserProfile, symbol_id: i32) {
        let holds_other = up.isolated_loans.values().any(|l| !l.is_empty() && l.symbol_id == symbol_id);
        if holds_other {
            return;
        }
        if let Some(s) = self.isolated_loan_symbol_to_users.get_mut(&symbol_id) {
            s.remove(&up.uid);
            if s.is_empty() {
                self.isolated_loan_symbol_to_users.remove(&symbol_id);
            }
        }
    }

    /// 对应 Java `syncCrossExposure(UserProfile)`：cross 敞口变更后 reconcile 索引，登记当前
    /// 敞口币种（抵押 > 0 或有借款）；账户全退出（无 loan 且无抵押）时才从各币种桶精确摘除。
    /// 部分币种退出容忍 stale（无害 over-trigger，下次 rebuild 清），只有全退出才逐桶清理。
    pub fn sync_cross_exposure(&mut self, up: &UserProfile) {
        for (&currency, &amount) in up.cross_loan_collateral.iter() {
            if amount > 0 {
                self.cross_loan_currency_to_users.entry(currency).or_default().insert(up.uid);
            }
        }
        for loan in up.cross_loans.values() {
            if !loan.is_empty() {
                self.cross_loan_currency_to_users.entry(loan.loan_currency).or_default().insert(up.uid);
            }
        }
        let has_loan = up.cross_loans.values().any(|l| !l.is_empty());
        let has_collateral = up.cross_loan_collateral.values().any(|&a| a > 0);
        if !has_loan && !has_collateral {
            let currencies: Vec<i32> = self.cross_loan_currency_to_users.keys().copied().collect();
            for currency in currencies {
                if let Some(s) = self.cross_loan_currency_to_users.get_mut(&currency) {
                    s.remove(&up.uid);
                    if s.is_empty() {
                        self.cross_loan_currency_to_users.remove(&currency);
                    }
                }
            }
        }
    }

    /// 对应 Java `pickCrossCollateralToSell(UserProfile)`：选卖出抵押币，排序
    /// 权重 DESC → 数量 DESC → 币种 ASC，且须能偿到某笔债（与目标 loan 币种间有就绪现货对）；
    /// 无合格者返回 `None`（Java 用哨兵值 0 表示，Rust 用 `Option`）。
    fn pick_cross_collateral_to_sell(
        &self,
        up: &UserProfile,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
    ) -> Option<i32> {
        let mut best_currency: Option<i32> = None;
        let mut best_weight: i32 = -1;
        let mut best_amount: i64 = -1;
        for (&currency, &amount) in up.cross_loan_collateral.iter() {
            if amount <= 0 {
                continue;
            }
            let weight = LoanService::collateral_weight_for_base(currency, ssp);
            if weight <= 0 {
                continue;
            }
            // 卖此币偿不了任何债（无现货对/markPrice 未就绪）→ 跳过，避免选中后每轮空转
            let can_repay_some = up
                .cross_loans
                .values()
                .any(|l| l.outstanding_principal > 0 && Self::has_ready_spot_market(currency, l.loan_currency, ssp, last_price_cache));
            if !can_repay_some {
                continue;
            }
            let better = weight > best_weight
                || (weight == best_weight && amount > best_amount)
                || (weight == best_weight && amount == best_amount && best_currency.is_some_and(|bc| currency < bc));
            if best_currency.is_none() || better {
                best_currency = Some(currency);
                best_weight = weight;
                best_amount = amount;
            }
        }
        best_currency
    }

    /// 对应 Java `pickCrossLoanToRepay(UserProfile, int)`：选偿还目标 loan，排序
    /// 利率 DESC → 本金 DESC → loanId ASC，且须与 `selling_currency` 有就绪现货对；无则 `None`。
    fn pick_cross_loan_to_repay(
        &self,
        up: &UserProfile,
        selling_currency: i32,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
    ) -> Option<CrossLoanRecord> {
        let mut best: Option<&CrossLoanRecord> = None;
        for loan in up.cross_loans.values() {
            if loan.outstanding_principal <= 0 {
                continue;
            }
            if !Self::has_ready_spot_market(selling_currency, loan.loan_currency, ssp, last_price_cache) {
                continue;
            }
            let better = match best {
                None => true,
                Some(b) => {
                    loan.rate_bps > b.rate_bps
                        || (loan.rate_bps == b.rate_bps && loan.outstanding_principal > b.outstanding_principal)
                        || (loan.rate_bps == b.rate_bps
                            && loan.outstanding_principal == b.outstanding_principal
                            && loan.loan_id < b.loan_id)
                }
            };
            if better {
                best = Some(loan);
            }
        }
        best.cloned()
    }

    /// 对应 Java `hasReadySpotMarket(int, int)`：卖 `selling_currency` 偿 `loan_currency` 的
    /// 现货对存在且 markPrice 就绪（可真正成交的前提）。
    fn has_ready_spot_market(
        selling_currency: i32,
        loan_currency: i32,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, LastPriceCacheRecord>,
    ) -> bool {
        match ssp.find_spot_symbol(selling_currency, loan_currency) {
            Some(spec) => matches!(last_price_cache.get(&spec.symbol_id), Some(r) if r.mark_price > 0),
            None => false,
        }
    }

    /// 对应 Java `calculateCrossSellSize(...)`：下单张数 = min(可卖抵押, 覆盖真实债务所需)
    /// （均换算成 lot）。用 `limit_price`（破产价）而非 markPrice 折算所需张数——按市价定量却按
    /// 折价卖，必然收不回债务。
    #[allow(clippy::too_many_arguments)]
    fn calculate_cross_sell_size(
        target_loan: &CrossLoanRecord,
        spec: &crate::core::common::core_symbol_specification::CoreSymbolSpecification,
        limit_price: i64,
        available: i64,
        now: i64,
        loan_service: &LoanService,
        selling_currency_spec: &crate::core::common::core_currency_specification::CoreCurrencySpecification,
        loan_currency_spec: &crate::core::common::core_currency_specification::CoreCurrencySpecification,
    ) -> i64 {
        let real_debt = add_exact(target_loan.outstanding_principal, loan_service.calculate_display_interest(target_loan, now));
        if real_debt <= 0 || limit_price <= 0 {
            return 0;
        }
        let needed_lots = LoanService::quote_amount_to_lots(real_debt, limit_price, spec, loan_currency_spec);
        let available_lots = LoanService::collateral_amount_to_lots(available, spec, selling_currency_spec);
        available_lots.min(needed_lots)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::symbol_loan_specification::SymbolLoanSpecification;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::common::user_status::UserStatus;

    const COLL: i32 = 1;
    const LOANC: i32 = 2;
    const SYMBOL: i32 = 100;
    const UID: i64 = 7;

    fn spot_spec(liquidation_ltv_bps: i32, margin_call_ltv_bps: i32, max_term_days: i32) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: COLL,
            quote_currency: LOANC,
            base_scale_k: 1,
            quote_scale_k: 1,
            loan_config: SymbolLoanSpecification {
                initial_ltv_bps: 5000,
                liquidation_ltv_bps,
                margin_call_ltv_bps,
                max_amount: 0,
                max_term_days,
            },
            ..Default::default()
        }
    }

    fn seeded_ssp() -> SymbolSpecificationProvider {
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_currency(CoreCurrencySpecification { currency: COLL, currency_scale_k: 1, collateral_weight_bps: 8000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: LOANC, currency_scale_k: 1, ..Default::default() });
        ssp.add_symbol(spot_spec(8000, 7000, 0));
        ssp
    }

    fn price_cache() -> BTreeMap<i32, LastPriceCacheRecord> {
        let mut m = BTreeMap::new();
        m.insert(SYMBOL, LastPriceCacheRecord::with_mark(1));
        m
    }

    fn profile(uid: i64) -> UserProfile {
        UserProfile::new(uid, UserStatus::Active)
    }

    fn iso_loan(loan_id: i64, collateral: i64, principal: i64) -> IsolatedLoanRecord {
        let mut l = IsolatedLoanRecord::new(UID, loan_id, SYMBOL, COLL, LOANC, 0, 0);
        l.collateral_amount = collateral;
        l.outstanding_principal = principal;
        l
    }

    #[test]
    fn on_isolated_loan_opened_and_closed_multi_loan_safe() {
        let mut e = LoanLiquidationEngine::new();
        e.on_isolated_loan_opened(UID, SYMBOL);
        assert!(e.isolated_loan_symbol_to_users.get(&SYMBOL).unwrap().contains(&UID));

        let mut up = profile(UID);
        up.isolated_loans.insert(1, iso_loan(1, 1000, 500));
        up.isolated_loans.insert(2, iso_loan(2, 0, 0));
        e.on_isolated_loan_closed(&up, SYMBOL);
        assert!(
            e.isolated_loan_symbol_to_users.get(&SYMBOL).unwrap().contains(&UID),
            "another non-empty loan on the same symbol still exists -> must not be removed"
        );

        up.isolated_loans.insert(1, iso_loan(1, 0, 0));
        e.on_isolated_loan_closed(&up, SYMBOL);
        assert!(e.isolated_loan_symbol_to_users.get(&SYMBOL).is_none(), "no non-empty loan left -> uid removed + empty bucket cleaned up");
    }

    #[test]
    fn sync_cross_exposure_registers_and_full_exit_sweeps() {
        let mut e = LoanLiquidationEngine::new();
        let mut up = profile(UID);
        up.add_to_cross_loan_collateral(COLL, 1000);
        let mut loan = CrossLoanRecord::new(UID, 1, SYMBOL, LOANC, 0, 0);
        loan.outstanding_principal = 500;
        up.cross_loans.insert(1, loan);
        e.sync_cross_exposure(&up);
        assert!(e.cross_loan_currency_to_users.get(&COLL).unwrap().contains(&UID), "collateral currency is registered");
        assert!(e.cross_loan_currency_to_users.get(&LOANC).unwrap().contains(&UID), "borrowed currency is registered");

        let mut empty = profile(UID);
        empty.cross_loans.insert(1, CrossLoanRecord::new(UID, 1, SYMBOL, LOANC, 0, 0));
        e.sync_cross_exposure(&empty);
        assert!(e.cross_loan_currency_to_users.is_empty(), "full exit -> every currency bucket precisely removed");
    }

    fn run_check_loans(e: &mut LoanLiquidationEngine, up: UserProfile, ssp: &SymbolSpecificationProvider) {
        let mut ups = UserProfileService::new();
        ups.users.insert(UID, up);
        let ls = LoanService::new();
        let cmd = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: SYMBOL, timestamp: 5_000, ..Default::default() };
        e.check_loans(&cmd, &ups, ssp, &price_cache(), &ls, &mut Vec::new());
    }

    #[test]
    fn check_isolated_triggers_force_liquidate_when_ltv_breaches() {
        let ssp = seeded_ssp();
        let mut e = LoanLiquidationEngine::new();
        e.on_isolated_loan_opened(UID, SYMBOL);
        let mut up = profile(UID);
        up.isolated_loans.insert(1, iso_loan(1, 1000, 900));
        run_check_loans(&mut e, up, &ssp);

        assert_eq!(e.pending_commands.len(), 1, "LTV breach -> FORCE command submitted");
        let c = &e.pending_commands[0];
        assert_eq!(c.command, OrderCommandType::LoanForceLiquidate);
        assert_eq!(c.uid, UID);
        assert_eq!(c.symbol, SYMBOL);
        assert_eq!(c.action, Some(OrderAction::Ask));
        assert_eq!(c.order_type, Some(OrderType::Ioc));
        assert_eq!(c.reserve_bid_price, 1, "loan_id is carried in reserve_bid_price");
        assert_eq!(c.size, 1000, "sellSizeLots = collateral_amount_to_lots(1000) = 1000");
        assert_eq!(c.price, 1, "bankruptcy price = ceil_mul_div(mark=1, debt=900, coll_value=1000) = 1");
    }

    #[test]
    fn check_isolated_healthy_loan_no_command() {
        let ssp = seeded_ssp();
        let mut e = LoanLiquidationEngine::new();
        e.on_isolated_loan_opened(UID, SYMBOL);
        let mut up = profile(UID);
        up.isolated_loans.insert(1, iso_loan(1, 1000, 500));
        run_check_loans(&mut e, up, &ssp);
        assert!(e.pending_commands.is_empty(), "a healthy loan does not trigger");
    }

    #[test]
    fn check_isolated_zero_collateral_value_skips_no_divzero() {
        let ssp = seeded_ssp();
        let mut e = LoanLiquidationEngine::new();
        e.on_isolated_loan_opened(UID, SYMBOL);
        let mut up = profile(UID);
        up.isolated_loans.insert(1, iso_loan(1, 0, 900));
        run_check_loans(&mut e, up, &ssp);
        assert!(e.pending_commands.is_empty(), "collateral value<=0 -> skip, no division by zero, no command submitted");
    }

    #[test]
    fn check_isolated_term_expired_triggers_even_when_ltv_healthy() {
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_currency(CoreCurrencySpecification { currency: COLL, currency_scale_k: 1, collateral_weight_bps: 8000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: LOANC, currency_scale_k: 1, ..Default::default() });
        ssp.add_symbol(spot_spec(8000, 7000, 1));

        let mut e = LoanLiquidationEngine::new();
        e.on_isolated_loan_opened(UID, SYMBOL);
        let mut up = profile(UID);
        let mut loan = iso_loan(1, 1000, 500);
        loan.rate_mode = LoanRateMode::Locked;
        loan.opened_at_ts = 0;
        up.isolated_loans.insert(1, loan);

        let mut ups = UserProfileService::new();
        ups.users.insert(UID, up);
        let ls = LoanService::new();
        let cmd = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: SYMBOL, timestamp: 2 * MS_PER_DAY, ..Default::default() };
        e.check_loans(&cmd, &ups, &ssp, &price_cache(), &ls, &mut Vec::new());

        assert_eq!(e.pending_commands.len(), 1, "LOCKED loan past term -> liquidated regardless of LTV");
    }

    #[test]
    fn pick_cross_collateral_prefers_higher_weight_then_amount_then_lower_currency() {
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_currency(CoreCurrencySpecification { currency: 10, currency_scale_k: 1, collateral_weight_bps: 5000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: 11, currency_scale_k: 1, collateral_weight_bps: 9000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: 12, currency_scale_k: 1, collateral_weight_bps: 9000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: LOANC, currency_scale_k: 1, ..Default::default() });
        for c in [10, 11, 12] {
            let mut s = spot_spec(8000, 7000, 0);
            s.symbol_id = 1000 + c;
            s.base_currency = c;
            s.quote_currency = LOANC;
            ssp.add_symbol(s);
        }
        let mut pc = BTreeMap::new();
        for c in [10, 11, 12] {
            pc.insert(1000 + c, LastPriceCacheRecord::with_mark(1));
        }

        let e = LoanLiquidationEngine::new();
        let mut up = profile(UID);
        up.add_to_cross_loan_collateral(10, 1000);
        up.add_to_cross_loan_collateral(11, 500);
        up.add_to_cross_loan_collateral(12, 500);
        up.cross_loans.insert(1, {
            let mut l = CrossLoanRecord::new(UID, 1, 1010, LOANC, 0, 0);
            l.outstanding_principal = 100;
            l
        });

        assert_eq!(e.pick_cross_collateral_to_sell(&up, &ssp, &pc), Some(11));
    }

    #[test]
    fn pick_cross_collateral_skips_currency_with_no_ready_market() {
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_currency(CoreCurrencySpecification { currency: 10, currency_scale_k: 1, collateral_weight_bps: 5000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: 11, currency_scale_k: 1, collateral_weight_bps: 9000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: LOANC, currency_scale_k: 1, ..Default::default() });
        let mut s = spot_spec(8000, 7000, 0);
        s.symbol_id = 1010;
        s.base_currency = 10;
        s.quote_currency = LOANC;
        ssp.add_symbol(s);
        let mut pc = BTreeMap::new();
        pc.insert(1010, LastPriceCacheRecord::with_mark(1));

        let e = LoanLiquidationEngine::new();
        let mut up = profile(UID);
        up.add_to_cross_loan_collateral(10, 1000);
        up.add_to_cross_loan_collateral(11, 5000);
        up.cross_loans.insert(1, {
            let mut l = CrossLoanRecord::new(UID, 1, 1010, LOANC, 0, 0);
            l.outstanding_principal = 100;
            l
        });

        assert_eq!(
            e.pick_cross_collateral_to_sell(&up, &ssp, &pc),
            Some(10),
            "a higher-weight currency with no ready spot market is skipped, falling back to the next-best one that has a market"
        );
    }

    #[test]
    fn pick_cross_loan_prefers_higher_rate_then_principal_then_lower_id() {
        let ssp = {
            let mut s = SymbolSpecificationProvider::new();
            s.add_currency(CoreCurrencySpecification { currency: COLL, currency_scale_k: 1, collateral_weight_bps: 8000, ..Default::default() });
            s.add_currency(CoreCurrencySpecification { currency: LOANC, currency_scale_k: 1, ..Default::default() });
            s.add_symbol(spot_spec(8000, 7000, 0));
            s
        };
        let e = LoanLiquidationEngine::new();
        let mut up = profile(UID);
        let mut l5 = CrossLoanRecord::new(UID, 5, SYMBOL, LOANC, 300, 0);
        l5.outstanding_principal = 200;
        let mut l3 = CrossLoanRecord::new(UID, 3, SYMBOL, LOANC, 300, 0);
        l3.outstanding_principal = 200;
        up.cross_loans.insert(5, l5);
        up.cross_loans.insert(3, l3);
        let pick = e.pick_cross_loan_to_repay(&up, COLL, &ssp, &price_cache()).expect("a repayable loan must exist");
        assert_eq!(pick.loan_id, 3, "same rate, same principal -> tie-broken by loanId ASC");
    }

    #[test]
    fn check_loans_targeted_unions_isolated_and_cross_currency_indices() {
        let ssp = seeded_ssp();
        let mut e = LoanLiquidationEngine::new();
        const UID_B: i64 = 8;
        e.on_isolated_loan_opened(UID, SYMBOL);
        let mut up_a = profile(UID);
        up_a.isolated_loans.insert(1, iso_loan(1, 1000, 900));

        let mut up_b = profile(UID_B);
        up_b.add_to_cross_loan_collateral(COLL, 1000);
        e.sync_cross_exposure(&up_b);

        let mut ups = UserProfileService::new();
        ups.users.insert(UID, up_a);
        ups.users.insert(UID_B, up_b);
        let ls = LoanService::new();
        let cmd = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: SYMBOL, timestamp: 5_000, ..Default::default() };
        e.check_loans(&cmd, &ups, &ssp, &price_cache(), &ls, &mut Vec::new());

        assert_eq!(e.pending_commands.len(), 1);
        assert_eq!(e.pending_commands[0].uid, UID, "user A, present in the union, is detected and liquidated");
    }
}
