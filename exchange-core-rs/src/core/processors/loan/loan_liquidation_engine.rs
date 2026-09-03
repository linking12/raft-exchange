//! 对应 Java: `exchange.core2.core.processors.loan.LoanLiquidationEngine`（394 行）。**现货借贷强平
//! 扫描器**——决定何时提交 `LOAN_FORCE_LIQUIDATE`/`LOAN_CROSS_FORCE_LIQUIDATE`（P5 已移植其
//! handlers；本移植 P6 Task 8 补齐触发端）。参考文档 §6、§11.3。
//!
//! 由 [`crate::core::processors::liquidation::liquidation_engine::LiquidationEngine::check_positions`]
//! 尾部委托（调用方已过 leader 门）。`cmd.symbol >= 0` 走 targeted（三索引并集）；`cmd.symbol < 0`
//! （`LIQUIDATION_SCAN`）全量兜底、按共享 [`crate::core::processors::liquidation::scheduler::
//! covered_by_scan_slice`] 切片过滤。
//!
//! # 移植偏差（P6 既定 Ruling）
//! - **无事件总线**（P6-B）：`sendMarginCall`/`ApiSystemLiquidationNotify` 不移植——越预警线分支
//!   为 no-op（只判定不动作，告警外部拉 report）。
//! - **submit → 队列**：生成的 force-liquidate 命令进 [`Self::pending_commands`]，由父
//!   `LiquidationEngine.check_positions` 收拢进其队列、`ExchangeCore` 排空重喂（同 futures 侧）。
//! - **provider 传参不持有**（P3-B）：`ups`/`ssp`/`last_price_cache`/`loan_service` 均为方法参数。
//!   扫描器**只读 `ups`**（读 loans/collateral、算、提交命令，不改任何余额），故不需两阶段借用。
//! - **索引维护由 `LoanCommandDispatcher` 同步调用**（所有节点确定性、不 leader-gate，Task 8 接线）：
//!   [`Self::on_isolated_loan_opened`]/[`Self::on_isolated_loan_closed`]/[`Self::sync_cross_exposure`]。
use std::collections::{BTreeMap, BTreeSet};

use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::cross_loan_record::CrossLoanRecord;
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
use crate::core::utils::core_arithmetic_utils::ceil_mul_div;

/// 天→毫秒（对应 Java `MS_PER_DAY`），期限强平换算。
const MS_PER_DAY: i64 = 86_400 * 1_000;

/// 对应 Java `LoanLiquidationEngine`。只持有**非复制 leader-local 状态**：两个 targeted 索引 +
/// 提交队列（见模块文档）。
#[derive(Debug, Default)]
pub struct LoanLiquidationEngine {
    /// 对应 Java `isolatedLoanSymbolToUsers`：symbolId → 持有该 pair isolated loan 的 uid 集合。
    pub isolated_loan_symbol_to_users: BTreeMap<i32, BTreeSet<i64>>,
    /// 对应 Java `crossLoanCurrencyToUsers`：currency → 对该币种有 cross 敞口（抵押或借款）的 uid。
    pub cross_loan_currency_to_users: BTreeMap<i32, BTreeSet<i64>>,
    /// 提交队列（生成的 force-liquidate 命令，由父 `LiquidationEngine` 收拢，见模块文档）。
    pub pending_commands: Vec<OrderCommand>,
}

impl LoanLiquidationEngine {
    pub fn new() -> Self {
        LoanLiquidationEngine::default()
    }

    /// 对应 Java `updateProvider`（`:70-88`）：快照恢复时从头重建两个索引（本移植不持有 provider，
    /// 只做索引重建，provider 由调用方每次传入）。遍历所有 UserProfile：非空 isolated loan 登记进
    /// symbol 索引；每个 profile 走一遍 `sync_cross_exposure`。
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

    /// 对应 Java `checkLoans(cmd)`（`:98-126`）：强平检测入口。`cmd.symbol >= 0` 查三索引并集
    /// （isolated[symbolId] ∪ cross[baseCurrency] ∪ cross[quoteCurrency]，dedup）；`cmd.symbol < 0`
    /// 全量整扫、按切片过滤。
    pub fn check_loans(
        &mut self,
        cmd: &OrderCommand,
        ups: &UserProfileService,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, i64>,
        loan_service: &LoanService,
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
                    self.check_user(up, cmd.timestamp, ssp, last_price_cache, loan_service);
                }
            }
            return;
        }
        for up in ups.users.values() {
            if !covered_by_scan_slice(cmd, up.uid) {
                continue;
            }
            self.check_user(up, cmd.timestamp, ssp, last_price_cache, loan_service);
        }
    }

    /// 对应 Java `checkUser`（`:128-134`）：对该用户的每笔 isolated loan 跑 `check_isolated`，再跑
    /// 一次账户级 `check_cross`。
    fn check_user(
        &mut self,
        up: &UserProfile,
        ts: i64,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, i64>,
        loan_service: &LoanService,
    ) {
        for loan in up.isolated_loans.values() {
            self.check_isolated(loan, ts, ssp, last_price_cache, loan_service);
        }
        self.check_cross(up, ts, ssp, last_price_cache, loan_service);
    }

    /// 对应 Java `checkIsolated`（`:136-186`）：单笔 isolated loan 触发判定。realDebt 含 pending
    /// 利息；`termExpired` 仅对 LOCKED（定息）+ `maxTermDays>0` 生效；触发（越 liquidationLtv 或
    /// 期限到）则提交 `LOAN_FORCE_LIQUIDATE`（ASK/IOC，限价=破产价）。sub-lot 尘埃（`sellSizeLots<=0`）
    /// skip（留到 LIF 接管时吸收）。越 marginCall 线仅预警（P6-B no-op）。
    fn check_isolated(
        &mut self,
        loan: &IsolatedLoanRecord,
        ts: i64,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, i64>,
        loan_service: &LoanService,
    ) {
        if loan.is_empty() {
            return;
        }
        let spec = match ssp.get_symbol(loan.symbol_id) {
            Some(s) => s,
            None => return,
        };
        let mark_price = match last_price_cache.get(&loan.symbol_id) {
            Some(&p) if p != 0 => p,
            _ => return,
        };
        let base_spec = ssp.get_currency(loan.collateral_currency);
        let loan_currency_spec = ssp.get_currency(loan.loan_currency);
        let collateral_value =
            LoanService::collateral_value_in_quote_currency(loan.collateral_amount, spec, mark_price, base_spec, loan_currency_spec);
        if collateral_value <= 0 {
            return; // 抵押估值 <=0 无法定破产价（除零）
        }
        let real_debt = loan.outstanding_principal + loan_service.calculate_display_interest(loan, ts);
        let ltv_scaled = mul_exact_local(real_debt, BPS_SCALE);

        let term_expired = loan.rate_mode == LoanRateMode::Locked
            && spec.loan_config.max_term_days > 0
            && (ts - loan.opened_at_ts) > spec.loan_config.max_term_days as i64 * MS_PER_DAY;

        if term_expired || ltv_scaled >= mul_exact_local(collateral_value, spec.loan_config.liquidation_ltv_bps as i64) {
            let base_spec = match base_spec {
                Some(b) => b,
                None => return, // collateral_value>0 已保证 spec 存在，防御性
            };
            let sell_size_lots = LoanService::collateral_amount_to_lots(loan.collateral_amount, spec, base_spec);
            if sell_size_lots <= 0 {
                return; // sub-lot 尘埃，本轮 skip
            }
            let order_id = LoanService::force_sell_order_id(ORDERID_SUBTYPE_ISOLATED, loan.uid, loan.loan_id, ts);
            let limit_price = ceil_mul_div(mark_price, real_debt, collateral_value); // 破产价（地板价）
            self.pending_commands.push(OrderCommand {
                command: OrderCommandType::LoanForceLiquidate,
                order_id,
                uid: loan.uid,
                symbol: spec.symbol_id,
                price: limit_price,
                size: sell_size_lots,
                reserve_bid_price: loan.loan_id, // P5 约定：loan_id 走 reserve_bid_price
                action: Some(OrderAction::Ask),
                order_type: Some(OrderType::Ioc),
                timestamp: ts,
                ..Default::default()
            });
        }
        // 越 marginCall 线：仅预警（P6-B no-op，不移植 send_margin_call）——无动作。
    }

    /// 对应 Java `checkCross`（`:195-247`）：账户级 cross 强平——越 crossLiquidationLtv 则每 tick 选
    /// 一对 (卖出抵押币, 偿还目标 loan) 提交 `LOAN_CROSS_FORCE_LIQUIDATE`，多 tick 收敛。触发用加权
    /// LTV，定价用 raw LTV（缺则回落加权）。越 marginCall 线仅预警（no-op）。
    fn check_cross(
        &mut self,
        up: &UserProfile,
        ts: i64,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, i64>,
        loan_service: &LoanService,
    ) {
        if up.cross_loans.is_empty() {
            return;
        }
        // 触发用加权 LTV（scanner 走 fail_closed=false：缺价保守返 0 不误强平）。
        let ltv_bps = loan_service.calculate_cross_account_ltv_bps(up, ts, ssp, last_price_cache, false);
        if ltv_bps < loan_service.global_config.cross_liquidation_ltv_bps as i64 {
            // 越 marginCall 线仅预警（P6-B no-op）。
            return;
        }
        let selling_currency = match self.pick_cross_collateral_to_sell(up, ssp, last_price_cache) {
            Some(c) => c,
            None => return, // 无可卖抵押币（无就绪现货对）
        };
        let target_loan = match self.pick_cross_loan_to_repay(up, selling_currency, ssp, last_price_cache) {
            Some(l) => l,
            None => return, // 无可偿目标 loan
        };
        // pick 已保证现货对存在且 markPrice 就绪。
        let spec = ssp.find_spot_symbol(selling_currency, target_loan.loan_currency).expect("pick 保证现货对存在");
        let mark_price = *last_price_cache.get(&spec.symbol_id).expect("pick 保证 markPrice 就绪");
        let available_collateral = up.cross_loan_collateral(selling_currency);
        let selling_currency_spec = match ssp.get_currency(selling_currency) {
            Some(s) => s,
            None => return,
        };
        let loan_currency_spec = match ssp.get_currency(target_loan.loan_currency) {
            Some(s) => s,
            None => return,
        };
        // 破产价按市值口径 raw LTV 定；缺则回落加权 ltv（偏保守但绝不放弃强平）。
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
            reserve_bid_price: target_loan.loan_id, // P5 约定：target_loan_id 走 reserve_bid_price
            action: Some(OrderAction::Ask),
            order_type: Some(OrderType::Ioc),
            timestamp: ts,
            ..Default::default()
        });
    }

    /// 对应 Java `pickCrossCollateralToSell`（`:305-327`）：选卖出抵押币——权重 DESC → 数量 DESC →
    /// 币种 ASC，且该币须能偿到某笔债（有就绪现货对）。无合格者返回 `None`。
    fn pick_cross_collateral_to_sell(
        &self,
        up: &UserProfile,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, i64>,
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
            // 卖此币能偿到某笔非空债（有就绪现货对）——否则每轮空转，必须在此过滤（§6.5）。
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

    /// 对应 Java `pickCrossLoanToRepay`（`:329-346`）：选偿还目标 loan——利率 DESC → 本金 DESC →
    /// loanId ASC，且与 sellingCurrency 有就绪现货对。无则 `None`。返回 loan 的克隆（避免持 up 借用）。
    fn pick_cross_loan_to_repay(
        &self,
        up: &UserProfile,
        selling_currency: i32,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, i64>,
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

    /// 对应 Java `hasReadySpotMarket`（`:347-356`）：卖 `selling_currency` 偿 `loan_currency` 的现货
    /// 对存在且 markPrice 就绪（可真正成交的前提）。
    fn has_ready_spot_market(
        selling_currency: i32,
        loan_currency: i32,
        ssp: &SymbolSpecificationProvider,
        last_price_cache: &BTreeMap<i32, i64>,
    ) -> bool {
        match ssp.find_spot_symbol(selling_currency, loan_currency) {
            Some(spec) => matches!(last_price_cache.get(&spec.symbol_id), Some(&p) if p > 0),
            None => false,
        }
    }

    /// 对应 Java `calculateCrossSellSize`（`:361-371`）：下单张数 = min(可卖抵押, 覆盖真实债务所需)。
    /// **按 `limit_price`（破产折价）而非 markPrice 折算所需张数**——按市价定量却按折价卖必收不回债
    /// （故"多 tick 收敛"，§6.6）。
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
        let real_debt = target_loan.outstanding_principal + loan_service.calculate_display_interest(target_loan, now);
        if real_debt <= 0 || limit_price <= 0 {
            return 0;
        }
        let needed_lots = LoanService::quote_amount_to_lots(real_debt, limit_price, spec, loan_currency_spec);
        let available_lots = LoanService::collateral_amount_to_lots(available, spec, selling_currency_spec);
        available_lots.min(needed_lots)
    }

    // ================================================================
    // 索引维护（由 LoanCommandDispatcher 在 apply 时确定性调用，不进 snapshot）
    // ================================================================

    /// 对应 Java `onIsolatedLoanOpened`（`:262-264`）：isolated loan 开仓登记进 symbol 索引。
    pub fn on_isolated_loan_opened(&mut self, uid: i64, symbol_id: i32) {
        self.isolated_loan_symbol_to_users.entry(symbol_id).or_default().insert(uid);
    }

    /// 对应 Java `onIsolatedLoanClosed`（`:266-277`）：isolated loan 清空——该 uid 在此 symbol 上已无
    /// 其它**非空** loan 时才摘除（一 uid 可持多笔同 symbol）。
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

    /// 对应 Java `syncCrossExposure`（`:279-299`）：cross 敞口变更后 reconcile 索引。登记当前敞口币种
    /// （抵押>0 或有非空借款）；**非对称容忍**（§6.7）——部分币种退出留 stale（无害 over-trigger，
    /// 下次 rebuild 清）；仅账户全退出（零抵押且零借款）才从各币种桶精确摘除。
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
            // 账户全退出：从每个币种桶精确摘除该 uid。
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
}

/// 对应 Java `Math.multiplyExact`：`i128` 中间精度、溢出 panic（同仓库既有 helper 风格）。
fn mul_exact_local(a: i64, b: i64) -> i64 {
    i64::try_from(a as i128 * b as i128).unwrap_or_else(|_| panic!("overflow: {a} * {b}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
    use crate::core::common::symbol_loan_specification::SymbolLoanSpecification;
    use crate::core::common::symbol_type::SymbolType;
    use crate::core::common::user_status::UserStatus;

    const COLL: i32 = 1; // 抵押币（spot base）
    const LOANC: i32 = 2; // 借款币（spot quote）
    const SYMBOL: i32 = 100; // COLL/LOANC 现货对
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

    fn price_cache() -> BTreeMap<i32, i64> {
        let mut m = BTreeMap::new();
        m.insert(SYMBOL, 1); // markPrice 1（1 COLL = 1 LOANC）
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

    // ---------------- 索引维护 ----------------

    #[test]
    fn on_isolated_loan_opened_and_closed_multi_loan_safe() {
        let mut e = LoanLiquidationEngine::new();
        e.on_isolated_loan_opened(UID, SYMBOL);
        assert!(e.isolated_loan_symbol_to_users.get(&SYMBOL).unwrap().contains(&UID));

        // 用户在同 symbol 持两笔 loan；关掉一笔，另一笔仍非空 -> 不摘除。
        let mut up = profile(UID);
        up.isolated_loans.insert(1, iso_loan(1, 1000, 500)); // 仍非空
        up.isolated_loans.insert(2, iso_loan(2, 0, 0)); // 已空（被关的那笔）
        e.on_isolated_loan_closed(&up, SYMBOL);
        assert!(
            e.isolated_loan_symbol_to_users.get(&SYMBOL).unwrap().contains(&UID),
            "同 symbol 还有另一笔非空 loan -> 不得摘除"
        );

        // 两笔都空 -> 摘除。
        up.isolated_loans.insert(1, iso_loan(1, 0, 0));
        e.on_isolated_loan_closed(&up, SYMBOL);
        assert!(e.isolated_loan_symbol_to_users.get(&SYMBOL).is_none(), "无非空 loan -> uid 摘除 + 空桶清理");
    }

    #[test]
    fn sync_cross_exposure_registers_and_full_exit_sweeps() {
        let mut e = LoanLiquidationEngine::new();
        let mut up = profile(UID);
        up.add_to_cross_loan_collateral(COLL, 1000);
        let mut loan = CrossLoanRecord::new(UID, 1, SYMBOL, LOANC, 0, 0);
        loan.outstanding_principal = 500; // 非空，才登记借款币桶
        up.cross_loans.insert(1, loan);
        e.sync_cross_exposure(&up);
        assert!(e.cross_loan_currency_to_users.get(&COLL).unwrap().contains(&UID), "抵押币登记");
        assert!(e.cross_loan_currency_to_users.get(&LOANC).unwrap().contains(&UID), "借款币登记");

        // 账户全退出（零抵押 + 零借款）-> 从各桶精确摘除。
        let mut empty = profile(UID);
        empty.cross_loans.insert(1, CrossLoanRecord::new(UID, 1, SYMBOL, LOANC, 0, 0)); // is_empty（principal 0）
        e.sync_cross_exposure(&empty);
        assert!(e.cross_loan_currency_to_users.is_empty(), "全退出 -> 各币种桶精确摘除");
    }

    // ---------------- checkIsolated ----------------

    fn run_check_loans(e: &mut LoanLiquidationEngine, up: UserProfile, ssp: &SymbolSpecificationProvider) {
        let mut ups = UserProfileService::new();
        ups.users.insert(UID, up);
        let ls = LoanService::new();
        let cmd = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: SYMBOL, timestamp: 5_000, ..Default::default() };
        // targeted：需索引里有 uid。
        e.check_loans(&cmd, &ups, ssp, &price_cache(), &ls);
    }

    #[test]
    fn check_isolated_triggers_force_liquidate_when_ltv_breaches() {
        let ssp = seeded_ssp();
        let mut e = LoanLiquidationEngine::new();
        e.on_isolated_loan_opened(UID, SYMBOL);
        let mut up = profile(UID);
        up.isolated_loans.insert(1, iso_loan(1, 1000, 900)); // collateral 1000 @mark1 -> value 1000；debt 900 -> LTV 90% >= 80%
        run_check_loans(&mut e, up, &ssp);

        assert_eq!(e.pending_commands.len(), 1, "LTV 越线 -> 提交 FORCE");
        let c = &e.pending_commands[0];
        assert_eq!(c.command, OrderCommandType::LoanForceLiquidate);
        assert_eq!(c.uid, UID);
        assert_eq!(c.symbol, SYMBOL);
        assert_eq!(c.action, Some(OrderAction::Ask));
        assert_eq!(c.order_type, Some(OrderType::Ioc));
        assert_eq!(c.reserve_bid_price, 1, "loan_id 走 reserve_bid_price");
        assert_eq!(c.size, 1000, "sellSizeLots = collateral_amount_to_lots(1000) = 1000");
        assert_eq!(c.price, 1, "破产价 = ceil_mul_div(mark=1, debt=900, coll_value=1000) = 1");
    }

    #[test]
    fn check_isolated_healthy_loan_no_command() {
        let ssp = seeded_ssp();
        let mut e = LoanLiquidationEngine::new();
        e.on_isolated_loan_opened(UID, SYMBOL);
        let mut up = profile(UID);
        up.isolated_loans.insert(1, iso_loan(1, 1000, 500)); // LTV 50% < 80% -> 健康
        run_check_loans(&mut e, up, &ssp);
        assert!(e.pending_commands.is_empty(), "健康 loan 不触发");
    }

    #[test]
    fn check_isolated_zero_collateral_value_skips_no_divzero() {
        let ssp = seeded_ssp();
        let mut e = LoanLiquidationEngine::new();
        e.on_isolated_loan_opened(UID, SYMBOL);
        let mut up = profile(UID);
        up.isolated_loans.insert(1, iso_loan(1, 0, 900)); // collateral 0 -> value<=0 -> skip（防除零）
        run_check_loans(&mut e, up, &ssp);
        assert!(e.pending_commands.is_empty(), "抵押估值<=0 -> skip，不除零、不提交");
    }

    #[test]
    fn check_isolated_term_expired_triggers_even_when_ltv_healthy() {
        // LOCKED + maxTermDays>0 + 超期 -> 即便 LTV 健康也强平。
        let mut ssp = SymbolSpecificationProvider::new();
        ssp.add_currency(CoreCurrencySpecification { currency: COLL, currency_scale_k: 1, collateral_weight_bps: 8000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: LOANC, currency_scale_k: 1, ..Default::default() });
        ssp.add_symbol(spot_spec(8000, 7000, 1)); // maxTermDays=1

        let mut e = LoanLiquidationEngine::new();
        e.on_isolated_loan_opened(UID, SYMBOL);
        let mut up = profile(UID);
        let mut loan = iso_loan(1, 1000, 500); // LTV 50% 健康
        loan.rate_mode = LoanRateMode::Locked;
        loan.opened_at_ts = 0;
        up.isolated_loans.insert(1, loan);

        // ts = 2 天后（> 1 天期限）。
        let mut ups = UserProfileService::new();
        ups.users.insert(UID, up);
        let ls = LoanService::new();
        let cmd = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: SYMBOL, timestamp: 2 * MS_PER_DAY, ..Default::default() };
        e.check_loans(&cmd, &ups, &ssp, &price_cache(), &ls);

        assert_eq!(e.pending_commands.len(), 1, "LOCKED 超期 -> 强平（不看 LTV）");
    }

    // ---------------- pick tie-break + ready-market ----------------

    #[test]
    fn pick_cross_collateral_prefers_higher_weight_then_amount_then_lower_currency() {
        let mut ssp = SymbolSpecificationProvider::new();
        // 三种抵押币，构造 tie-break。COLL2/COLL3 权重相同、数量相同 -> 第三级按 currency ASC。
        ssp.add_currency(CoreCurrencySpecification { currency: 10, currency_scale_k: 1, collateral_weight_bps: 5000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: 11, currency_scale_k: 1, collateral_weight_bps: 9000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: 12, currency_scale_k: 1, collateral_weight_bps: 9000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: LOANC, currency_scale_k: 1, ..Default::default() });
        // 现货对：每种抵押币 -> LOANC 都有就绪市场。
        for c in [10, 11, 12] {
            let mut s = spot_spec(8000, 7000, 0);
            s.symbol_id = 1000 + c;
            s.base_currency = c;
            s.quote_currency = LOANC;
            ssp.add_symbol(s);
        }
        let mut pc = BTreeMap::new();
        for c in [10, 11, 12] {
            pc.insert(1000 + c, 1);
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

        // weight: 11/12 都 9000 > 10 的 5000；11 与 12 数量相同(500) -> currency ASC -> 选 11。
        assert_eq!(e.pick_cross_collateral_to_sell(&up, &ssp, &pc), Some(11));
    }

    #[test]
    fn pick_cross_collateral_skips_currency_with_no_ready_market() {
        let mut ssp = SymbolSpecificationProvider::new();
        // 高权重币 11 无现货对 -> 应跳过，退到低权重币 10（有市场）。
        ssp.add_currency(CoreCurrencySpecification { currency: 10, currency_scale_k: 1, collateral_weight_bps: 5000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: 11, currency_scale_k: 1, collateral_weight_bps: 9000, ..Default::default() });
        ssp.add_currency(CoreCurrencySpecification { currency: LOANC, currency_scale_k: 1, ..Default::default() });
        let mut s = spot_spec(8000, 7000, 0);
        s.symbol_id = 1010;
        s.base_currency = 10;
        s.quote_currency = LOANC;
        ssp.add_symbol(s); // 只有 10->LOANC 有对
        let mut pc = BTreeMap::new();
        pc.insert(1010, 1);

        let e = LoanLiquidationEngine::new();
        let mut up = profile(UID);
        up.add_to_cross_loan_collateral(10, 1000);
        up.add_to_cross_loan_collateral(11, 5000); // 权重更高但无市场
        up.cross_loans.insert(1, {
            let mut l = CrossLoanRecord::new(UID, 1, 1010, LOANC, 0, 0);
            l.outstanding_principal = 100;
            l
        });

        assert_eq!(
            e.pick_cross_collateral_to_sell(&up, &ssp, &pc),
            Some(10),
            "高权重但无就绪现货对的币被跳过，退到有市场的次优"
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
        // 两笔同利率同本金、不同 loanId（乱序插入）-> 第三级 loanId ASC。
        let mut l5 = CrossLoanRecord::new(UID, 5, SYMBOL, LOANC, 300, 0);
        l5.outstanding_principal = 200;
        let mut l3 = CrossLoanRecord::new(UID, 3, SYMBOL, LOANC, 300, 0);
        l3.outstanding_principal = 200;
        up.cross_loans.insert(5, l5);
        up.cross_loans.insert(3, l3);
        let pick = e.pick_cross_loan_to_repay(&up, COLL, &ssp, &price_cache()).expect("有可偿 loan");
        assert_eq!(pick.loan_id, 3, "同 rate 同 principal -> loanId ASC");
    }

    // ---------------- targeted 三索引并集 ----------------

    #[test]
    fn check_loans_targeted_unions_isolated_and_cross_currency_indices() {
        let ssp = seeded_ssp();
        let mut e = LoanLiquidationEngine::new();
        // 用户 A(=UID) 有 isolated loan（进 isolated 索引）；用户 B 有 cross 抵押币=COLL（进 cross[COLL]）。
        const UID_B: i64 = 8;
        e.on_isolated_loan_opened(UID, SYMBOL);
        let mut up_a = profile(UID);
        up_a.isolated_loans.insert(1, iso_loan(1, 1000, 900)); // 越线

        let mut up_b = profile(UID_B);
        up_b.add_to_cross_loan_collateral(COLL, 1000);
        e.sync_cross_exposure(&up_b);

        let mut ups = UserProfileService::new();
        ups.users.insert(UID, up_a);
        ups.users.insert(UID_B, up_b);
        let ls = LoanService::new();
        // markprice on SYMBOL(base=COLL, quote=LOANC)：并集 = isolated[SYMBOL] ∪ cross[COLL] ∪ cross[LOANC]。
        let cmd = OrderCommand { command: OrderCommandType::MarkpriceAdjustment, symbol: SYMBOL, timestamp: 5_000, ..Default::default() };
        e.check_loans(&cmd, &ups, &ssp, &price_cache(), &ls);

        // A 的 isolated loan 越线 -> 一条 FORCE；B 无 cross loan（只有抵押无借款）-> checkCross 早退。
        assert_eq!(e.pending_commands.len(), 1);
        assert_eq!(e.pending_commands[0].uid, UID, "并集里 A 被检出并强平");
    }
}
