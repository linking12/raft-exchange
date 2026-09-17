use std::collections::BTreeMap;

use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::position_direction::PositionDirection;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::common::order::Order;
use crate::core::common::user_profile::UserProfile;
use crate::core::common::user_status::UserStatus;
use crate::core::exchange_core::ExchangeCore;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::loan::loan_service::{LoanService, BPS_SCALE as LOAN_BPS_SCALE};
use crate::core::utils::core_arithmetic_utils::size_price_to_currency_scale;

// 对应 Java `exchange.core2.core.common.api.reports.*`(各 ReportQuery/ReportResult DTO) +
// `RiskEngine`/`ReportQueriesHandler` 里的聚合查询逻辑。Java 因为 RiskEngine 按 uid 分片，报表要
// 把各 shard 的部分结果 merge 后再输出；Rust 单 shard 塌缩后一次遍历即得全量，不需要跨 shard 合并步骤。
// 各报表金额均是"原始整数 + scale_k"的定点表示，换算成人类可读值需要调用方按对应 currency/symbol 的
// scale_k 自行还原（约定与 Java CoreArithmeticUtils 的定点算术一致）。

#[inline]
fn add(map: &mut BTreeMap<i32, i64>, k: i32, v: i64) {
    *map.entry(k).or_insert(0) += v;
}

/// 对应 Java `TotalCurrencyBalanceReportResult`：全平台按币种汇总的资产负债表，用于跨节点/跨语言
/// 守恒对拍。各桶均以币种为键；`global_balances_sum`/`is_global_zero` 把"账户+额外保证金+手续费+
/// 调整+挂起+交易所锁定+loan 余额+loan 抵押+IF 余额"这些互斥桶相加，理论上恒为 0（资金零和不变式）。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TotalCurrencyBalanceReport {
    pub currency_balances: BTreeMap<i32, i64>,
    pub extra_margin: BTreeMap<i32, i64>,
    pub fees: BTreeMap<i32, i64>,
    pub adjustments: BTreeMap<i32, i64>,
    pub suspends: BTreeMap<i32, i64>,
    pub exchange_locked: BTreeMap<i32, i64>,
    pub loan_balances: BTreeMap<i32, i64>,
    pub loan_collateral: BTreeMap<i32, i64>,
    pub symbol_open_interest_long: BTreeMap<i32, i64>,
    pub symbol_open_interest_short: BTreeMap<i32, i64>,
    pub if_balances: BTreeMap<i32, i64>,
    pub if_open_interest_long: BTreeMap<i32, i64>,
    pub if_open_interest_short: BTreeMap<i32, i64>,
}

impl TotalCurrencyBalanceReport {
    /// 按币种把互斥资金桶（不含 `symbol_open_interest_*`/`if_open_interest_*`——那两组是数量而非资金）
    /// 逐一相加，守恒态下每个币种理应为 0。用于测试/运维对拍，对应 Java 侧报表守恒校验惯例。
    pub fn global_balances_sum(&self) -> BTreeMap<i32, i64> {
        let mut sum = BTreeMap::new();
        for bucket in [
            &self.currency_balances,
            &self.extra_margin,
            &self.fees,
            &self.adjustments,
            &self.suspends,
            &self.exchange_locked,
            &self.loan_balances,
            &self.loan_collateral,
            &self.if_balances,
        ] {
            for (&c, &v) in bucket {
                add(&mut sum, c, v);
            }
        }
        sum
    }

    pub fn is_global_zero(&self) -> bool {
        self.global_balances_sum().values().all(|&v| v == 0)
    }
}

/// 对应 Java `SingleUserReportResult` 内嵌的期货仓位视图：估值字段（`mark_price`/`unrealized_pnl`/
/// `liquidation_price`/`margin_ratio_scale_k`）由 `RiskEngine::futures_estimates` 现算现取，非存量字段。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PositionView {
    pub symbol: i32,
    pub direction: PositionDirection,
    pub open_volume: i64,
    pub open_price_sum: i64,
    pub open_init_margin_sum: i64,
    pub profit: i64,
    pub extra_margin: i64,
    pub leverage: i32,
    pub margin_mode: crate::core::common::margin_mode::MarginMode,
    pub pending_sell_size: i64,
    pub pending_buy_size: i64,
    pub pending_sell_avg_price: i64,
    pub pending_buy_avg_price: i64,
    pub mark_price: i64,
    pub unrealized_pnl: i64,
    pub liquidation_price: i64,
    pub margin_ratio_scale_k: i64,
    pub maintenance_margin_scale_k: i64,
}

/// 对应 Java `SingleUserReportResult`：单用户视图（账户/仓位/loan/挂单）。`found=false` 时其余字段
/// 全为默认值，对应 Java 查不到 UserProfile 时的空结果分支。`isolated_loans`/`cross_loans` 用位置元组
/// 而非具名结构体承载各字段，字段顺序见 `query_single_user` 里对应 tuple 的构造注释。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SingleUserReport {
    pub uid: i64,
    pub found: bool,
    pub user_status: UserStatus,
    pub accounts: BTreeMap<i32, i64>,
    pub exchange_locked: BTreeMap<i32, i64>,
    pub positions: Vec<PositionView>,
    pub cross_account_ltv_bps: i64,
    // (loan_id, symbol_id, loan_currency, collateral_currency, collateral_amount,
    //  outstanding_principal, accumulated_interest, rate_bps, opened_at_ts,
    //  display_interest, ltv_bps, mark_price)
    pub isolated_loans: Vec<(i64, i32, i32, i32, i64, i64, i64, i32, i64, i64, i64, i64)>,
    // (loan_id, symbol_id, loan_currency, outstanding_principal, accumulated_interest,
    //  rate_bps, opened_at_ts, display_interest)
    pub cross_loans: Vec<(i64, i32, i32, i64, i64, i32, i64, i64)>,
    pub cross_loan_collateral: BTreeMap<i32, i64>,
    pub orders: Vec<(i32, Order)>,
}

/// 对应 Java `FeeReportResult`。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FeeReport {
    pub fees: BTreeMap<i32, i64>,
}

/// 对应 Java `InsuranceFundReportResult` 内嵌的单 symbol 保险基金条目。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FuturesIfEntry {
    pub available: i64,
    pub reserved: i64,
    pub position_value: i64,
}

/// 对应 Java `InsuranceFundReportResult`：期货 IF（按 symbol）+ loan IF（按币种）+ 各 symbol 最新标记价。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct InsuranceFundReport {
    pub futures: BTreeMap<i32, FuturesIfEntry>,
    pub loan_insurance_fund: BTreeMap<i32, i64>,
    pub mark_price: BTreeMap<i32, i64>,
}

/// 对应 Java `LoanPlatformReportResult` 内嵌的单币种 loan 平台条目。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct LoanPlatformEntry {
    pub interest_revenue: i64,
    pub loan_insurance_fund: i64,
    pub pool_available: i64,
    pub pool_borrowed: i64,
}

/// 对应 Java `LoanPlatformReportResult`。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct LoanPlatformReport {
    pub per_currency: BTreeMap<i32, LoanPlatformEntry>,
}

/// 对应 Java `SymbolCurrencyReportResult`：全量 symbol/currency 配置快照。
#[derive(Debug, Clone, Default, PartialEq)]
pub struct SymbolCurrencyReport {
    pub symbols: Vec<CoreSymbolSpecification>,
    pub currencies: Vec<CoreCurrencySpecification>,
}

/// 对应 Java `StateHashReportResult`：分组件（loan_service/liquidation_service/风控三桶/symbol specs/
/// currency specs/user profiles/mark price/order books 等）的哈希，供跨节点/跨语言逐组件比对定位分歧；
/// `merged()` 再把各组件按名称排序（`BTreeMap` 天然有序）滚成一个总哈希，对应 Java 侧 state hash 的
/// 合并算法（31 进制滚动哈希，seed=17）。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct StateHashReport {
    pub components: BTreeMap<String, i64>,
}

impl StateHashReport {
    pub fn merged(&self) -> i64 {
        let mut h: i64 = 17;
        for (name, &hash) in &self.components {
            for b in name.as_bytes() {
                h = h.wrapping_mul(31).wrapping_add(*b as i64);
            }
            h = h.wrapping_mul(31).wrapping_add(hash);
        }
        h
    }
}

impl ExchangeCore {
    /// 对应 Java `RiskEngine` 处理 `TotalCurrencyBalanceReportQuery` 的聚合逻辑：遍历全量用户+风控侧
    /// 各桶，把仓位 PnL/额外保证金/IF 头寸等以 symbol 计价的量通过 `size_price_to_currency` 折算成对应
    /// quote 币种再汇总。`currency_balances` 已扣除被锁定/被质押的部分（对应字段各自独立记账，
    /// 相减确保总额不重复计入），因此 `global_balances_sum` 各桶相加应恒为 0。
    pub fn query_total_balance(&self) -> TotalCurrencyBalanceReport {
        let mut r = TotalCurrencyBalanceReport::default();
        let mut pnl_by_symbol: BTreeMap<i32, i64> = BTreeMap::new();
        let mut extra_margin_by_symbol: BTreeMap<i32, i64> = BTreeMap::new();

        for up in self.ups.users.values() {
            for (&c, &v) in &up.accounts {
                add(&mut r.currency_balances, c, v);
            }
            for (&c, &v) in &up.exchange_locked {
                add(&mut r.currency_balances, c, -v);
                add(&mut r.exchange_locked, c, v);
            }
            for (&c, &v) in &up.cross_loan_collateral {
                add(&mut r.currency_balances, c, -v);
                add(&mut r.loan_collateral, c, v);
            }
            for loan in up.isolated_loans.values() {
                if loan.collateral_amount != 0 {
                    add(&mut r.currency_balances, loan.collateral_currency, -loan.collateral_amount);
                    add(&mut r.loan_collateral, loan.collateral_currency, loan.collateral_amount);
                }
            }
            for pos in up.positions.values() {
                let mark = self.risk.last_price_cache.get(&pos.symbol).map(|r| r.mark_price).unwrap_or(0);
                add(&mut pnl_by_symbol, pos.symbol, pos.estimate_pnl(mark));
                if pos.extra_margin > 0 {
                    add(&mut extra_margin_by_symbol, pos.symbol, pos.extra_margin);
                }
                match pos.direction {
                    PositionDirection::Long => add(&mut r.symbol_open_interest_long, pos.symbol, pos.open_volume),
                    PositionDirection::Short => add(&mut r.symbol_open_interest_short, pos.symbol, pos.open_volume),
                    PositionDirection::Empty => {}
                }
            }
        }

        for (&sym, &acc) in &pnl_by_symbol {
            if let Some((ccy, v)) = self.size_price_to_currency(acc, sym) {
                add(&mut r.currency_balances, ccy, v);
            }
        }
        for (&sym, &acc) in &extra_margin_by_symbol {
            if let Some((ccy, v)) = self.size_price_to_currency(acc, sym) {
                add(&mut r.extra_margin, ccy, v);
            }
        }

        r.fees = self.risk.fees.clone();
        r.adjustments = self.risk.adjustments.clone();
        r.suspends = self.risk.suspends.clone();

        for (&c, &v) in &self.risk.loan_service.loan_pool_available {
            add(&mut r.loan_balances, c, v);
        }
        for (&c, &v) in &self.risk.loan_service.interest_revenue {
            add(&mut r.loan_balances, c, v);
        }
        for (&c, &v) in &self.risk.loan_service.loan_insurance_fund {
            add(&mut r.loan_balances, c, v);
        }

        for (&sym, notional) in &self.risk.liquidation_service.notionals {
            if let Some((ccy, v)) = self.size_price_to_currency(notional.available, sym) {
                add(&mut r.if_balances, ccy, v);
            }
        }
        for pos in self.risk.liquidation_service.positions.values() {
            match pos.direction {
                PositionDirection::Long => add(&mut r.if_open_interest_long, pos.symbol, pos.open_volume),
                PositionDirection::Short => add(&mut r.if_open_interest_short, pos.symbol, pos.open_volume),
                PositionDirection::Empty => {}
            }
            let mark = self.risk.last_price_cache.get(&pos.symbol).map(|r| r.mark_price).unwrap_or(0);
            let position_value = pos.position_value(mark);
            if let Some((ccy, v)) = self.size_price_to_currency(position_value, pos.symbol) {
                add(&mut r.if_balances, ccy, v);
            }
        }

        r
    }

    /// 对应 Java `RiskEngine` 处理 `SingleUserReportQuery` 的逻辑。`now_ms` 用于 loan 展示利息
    /// （`calculate_display_interest`）按当前时刻计提，不改任何持久状态，是纯只读投影。
    pub fn query_single_user(&self, uid: i64, now_ms: i64) -> SingleUserReport {
        let Some(up) = self.ups.get(uid) else {
            return SingleUserReport {
                uid,
                found: false,
                user_status: UserStatus::Suspended,
                accounts: BTreeMap::new(),
                exchange_locked: BTreeMap::new(),
                positions: Vec::new(),
                cross_account_ltv_bps: 0,
                isolated_loans: Vec::new(),
                cross_loans: Vec::new(),
                cross_loan_collateral: BTreeMap::new(),
                orders: Vec::new(),
            };
        };
        let positions = up
            .positions
            .values()
            .map(|pos| {
                let mark = self.risk.last_price_cache.get(&pos.symbol).map(|r| r.mark_price).unwrap_or(0);
                let (liquidation_price, margin_ratio_scale_k, maintenance_margin_scale_k) =
                    self.position_estimates(up, pos);
                PositionView {
                    symbol: pos.symbol,
                    direction: pos.direction,
                    open_volume: pos.open_volume,
                    open_price_sum: pos.open_price_sum,
                    open_init_margin_sum: pos.open_init_margin_sum,
                    profit: pos.profit,
                    extra_margin: pos.extra_margin,
                    leverage: pos.leverage,
                    margin_mode: pos.margin_mode,
                    pending_sell_size: pos.pending_sell_size,
                    pending_buy_size: pos.pending_buy_size,
                    pending_sell_avg_price: pos.pending_sell_avg_price,
                    pending_buy_avg_price: pos.pending_buy_avg_price,
                    mark_price: mark,
                    unrealized_pnl: pos.estimate_unrealized_profit(mark),
                    liquidation_price,
                    margin_ratio_scale_k,
                    maintenance_margin_scale_k,
                }
            })
            .collect();
        let isolated_loans = up
            .isolated_loans
            .values()
            .map(|l| {
                let display_interest = self.risk.loan_service.calculate_display_interest(l, now_ms);
                let mark_price = self.risk.last_price_cache.get(&l.symbol_id).map(|r| r.mark_price).unwrap_or(0);
                let mut ltv_bps = 0i64;
                if mark_price > 0 {
                    if let Some(spec) = self.ssp.get_symbol(l.symbol_id) {
                        let base_spec = self.ssp.get_currency(l.collateral_currency);
                        let quote_spec = self.ssp.get_currency(l.loan_currency);
                        let collateral_value = LoanService::collateral_value_in_quote_currency(
                            l.collateral_amount, spec, mark_price, base_spec, quote_spec,
                        );
                        if collateral_value > 0 {
                            let real_debt = l.outstanding_principal + display_interest;
                            ltv_bps = ((real_debt as i128 * LOAN_BPS_SCALE as i128) / collateral_value as i128) as i64;
                        }
                    }
                }
                (l.loan_id, l.symbol_id, l.loan_currency, l.collateral_currency, l.collateral_amount,
                 l.outstanding_principal, l.accumulated_interest, l.rate_bps, l.opened_at_ts,
                 display_interest, ltv_bps, mark_price)
            })
            .collect();
        let cross_loans = up
            .cross_loans
            .values()
            .map(|l| {
                let display_interest = self.risk.loan_service.calculate_display_interest(l, now_ms);
                (l.loan_id, l.symbol_id, l.loan_currency, l.outstanding_principal,
                 l.accumulated_interest, l.rate_bps, l.opened_at_ts, display_interest)
            })
            .collect();
        let cross_account_ltv_bps = self.risk.loan_service.calculate_cross_account_ltv_bps(
            up,
            now_ms,
            &self.ssp,
            &self.risk.last_price_cache,
            false,
        );
        SingleUserReport {
            uid,
            found: true,
            user_status: up.user_status,
            accounts: up.accounts.clone(),
            exchange_locked: up.exchange_locked.clone(),
            positions,
            cross_account_ltv_bps,
            isolated_loans,
            cross_loans,
            cross_loan_collateral: up.cross_loan_collateral.clone(),
            orders: self.matching.user_orders(uid),
        }
    }

    /// 对应 Java `FeeReportQuery` 处理逻辑。
    pub fn query_fee_report(&self) -> FeeReport {
        FeeReport { fees: self.risk.fees.clone() }
    }

    /// 对应 Java `InsuranceFundReportQuery` 处理逻辑：`symbols` 取"有 IF 名义金额记录"∪"有 IF 自营仓位"
    /// 的并集，保证只出现在自营仓位里、还没记过 notional 的 symbol 也能出现在报表中。
    pub fn query_insurance_fund(&self) -> InsuranceFundReport {
        let mut r = InsuranceFundReport::default();
        let mut symbols: std::collections::BTreeSet<i32> = self.risk.liquidation_service.notionals.keys().copied().collect();
        let mut position_values: BTreeMap<i32, i64> = BTreeMap::new();
        for pos in self.risk.liquidation_service.positions.values() {
            symbols.insert(pos.symbol);
            let mark = self.risk.last_price_cache.get(&pos.symbol).map(|r| r.mark_price).unwrap_or(0);
            *position_values.entry(pos.symbol).or_insert(0) += pos.position_value(mark);
        }
        for sym in symbols {
            let notional = self.risk.liquidation_service.notionals.get(&sym);
            r.futures.insert(
                sym,
                FuturesIfEntry {
                    available: notional.map(|n| n.available).unwrap_or(0),
                    reserved: notional.map(|n| n.reserved).unwrap_or(0),
                    position_value: position_values.get(&sym).copied().unwrap_or(0),
                },
            );
        }
        r.loan_insurance_fund = self.risk.loan_service.loan_insurance_fund.clone();
        r.mark_price = self.risk.last_price_cache.iter().map(|(&k, v)| (k, v.mark_price)).collect();
        r
    }

    /// 对应 Java `LoanPlatformReportQuery` 处理逻辑：币种集合取"资金池可用/已借出/利息收入/保险基金"
    /// 四个 map 键的并集，任一桶有记录的币种都要出现在报表里。
    pub fn query_loan_platform(&self) -> LoanPlatformReport {
        let ls = &self.risk.loan_service;
        let mut per_currency: BTreeMap<i32, LoanPlatformEntry> = BTreeMap::new();
        let mut currencies = std::collections::BTreeSet::new();
        currencies.extend(ls.loan_pool_available.keys().copied());
        currencies.extend(ls.loan_pool_borrowed.keys().copied());
        currencies.extend(ls.interest_revenue.keys().copied());
        currencies.extend(ls.loan_insurance_fund.keys().copied());
        for c in currencies {
            per_currency.insert(
                c,
                LoanPlatformEntry {
                    interest_revenue: ls.get_interest_revenue(c),
                    loan_insurance_fund: ls.get_loan_insurance_fund(c),
                    pool_available: ls.get_loan_pool_available(c),
                    pool_borrowed: ls.get_loan_pool_borrowed(c),
                },
            );
        }
        LoanPlatformReport { per_currency }
    }

    /// 对应 Java `SymbolCurrencyReportQuery` 处理逻辑：全量配置快照，无过滤。
    pub fn query_symbol_currency(&self) -> SymbolCurrencyReport {
        SymbolCurrencyReport {
            symbols: self.ssp.symbols.values().cloned().collect(),
            currencies: self.ssp.currencies.values().cloned().collect(),
        }
    }

    /// 对应 Java `StateHashReportQuery` 处理逻辑：按子系统分组件计算哈希，供跨节点/跨语言黄金向量
    /// 对拍时精确定位是哪个子系统状态分歧（而不是只有一个笼统的总哈希）。哈希算法为经典
    /// `h = h*31 + x` 滚动哈希，`BTreeMap` 保证组件按名称的确定性迭代顺序。
    pub fn query_state_hash(&self) -> StateHashReport {
        fn hash_bucket(map: &BTreeMap<i32, i64>) -> i64 {
            let mut h: i64 = 17;
            for (&k, &v) in map {
                h = h.wrapping_mul(31).wrapping_add(k as i64);
                h = h.wrapping_mul(31).wrapping_add(v);
            }
            h
        }
        let mut components = BTreeMap::new();
        components.insert("loan_service".to_string(), self.risk.loan_service.state_hash() as i64);
        components.insert("liquidation_service".to_string(), self.risk.liquidation_service.state_hash() as i64);
        components.insert("risk_fees".to_string(), hash_bucket(&self.risk.fees));
        components.insert("risk_adjustments".to_string(), hash_bucket(&self.risk.adjustments));
        components.insert("risk_suspends".to_string(), hash_bucket(&self.risk.suspends));
        let mut symbols_h: i64 = 17;
        for (&id, s) in &self.ssp.symbols {
            symbols_h = symbols_h.wrapping_mul(31).wrapping_add(id as i64);
            symbols_h = symbols_h.wrapping_mul(31).wrapping_add(s.state_hash() as i64);
        }
        components.insert("symbol_specs".to_string(), symbols_h);
        let mut ccy_h: i64 = 17;
        for (&id, c) in &self.ssp.currencies {
            ccy_h = ccy_h.wrapping_mul(31).wrapping_add(id as i64);
            ccy_h = ccy_h.wrapping_mul(31).wrapping_add(c.currency_scale_k);
            ccy_h = ccy_h.wrapping_mul(31).wrapping_add(c.collateral_weight_bps as i64);
        }
        components.insert("currency_specs".to_string(), ccy_h);
        let mut users_h: i64 = 17;
        for (&uid, up) in &self.ups.users {
            users_h = users_h.wrapping_mul(31).wrapping_add(uid);
            users_h = users_h.wrapping_mul(31).wrapping_add(up.state_hash() as i64);
        }
        components.insert("user_profiles".to_string(), users_h);
        let mark_prices: BTreeMap<i32, i64> = self.risk.last_price_cache.iter().map(|(&k, v)| (k, v.mark_price)).collect();
        let mark_price_ts: BTreeMap<i32, i64> = self.risk.last_price_cache.iter().map(|(&k, v)| (k, v.mark_price_ts)).collect();
        components.insert("risk_mark_price_ts".to_string(), hash_bucket(&mark_price_ts));
        components.insert("order_books".to_string(), self.matching.order_books_state_hash());
        components.insert("risk_last_price_cache".to_string(), hash_bucket(&mark_prices));
        StateHashReport { components }
    }

    // 把以 (base_scale_k, quote_scale_k) 定点表示的 symbol 相关金额（PnL/持仓名义价值等）折算成该
    // symbol 计价币种(quote_currency)自己的 scale，供跨 symbol 汇总到同一币种桶时口径一致。
    fn size_price_to_currency(&self, amount: i64, symbol: i32) -> Option<(i32, i64)> {
        let spec = self.ssp.get_symbol(symbol)?;
        let cspec = self.ssp.get_currency(spec.quote_currency)?;
        let v = size_price_to_currency_scale(amount, spec.base_scale_k, spec.quote_scale_k, cspec.currency_scale_k);
        Some((spec.quote_currency, v))
    }

    // 复用 RiskEngine 强平判定同一套估值口径（`futures_estimates`），只取报表需要的
    // (强平价, 保证金率scale_k, 维持保证金scale_k)，丢弃其中的未实现盈亏(已由 pos.estimate_unrealized_profit 单独取)。
    fn position_estimates(&self, up: &UserProfile, pos: &SymbolPositionRecord) -> (i64, i64, i64) {
        let Some(spec) = self.ssp.get_symbol(pos.symbol) else { return (0, 0, 0) };
        let (_upnl, liq, mr, mmsk) = RiskEngine::futures_estimates(&self.risk.last_price_cache, up, pos, spec, &self.ssp);
        (liq, mr, mmsk)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::common::cmd::command_result_code::CommandResultCode;
    use crate::core::common::cmd::order_command::OrderCommand;
    use crate::core::common::cmd::order_command_type::OrderCommandType;
    use crate::core::common::core_currency_specification::CoreCurrencySpecification;
    use crate::core::common::order_action::OrderAction;
    use crate::core::common::order_type::OrderType;
    use crate::core::common::symbol_type::SymbolType;

    const SYMBOL: i32 = 1;
    const BASE: i32 = 10;
    const QUOTE: i32 = 20;

    fn spec() -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id: SYMBOL,
            symbol_type: SymbolType::CurrencyExchangePair,
            base_currency: BASE,
            quote_currency: QUOTE,
            base_scale_k: 1,
            quote_scale_k: 1,
            taker_fee: 0,
            maker_fee: 0,
            fee_scale_k: 0,
            ..Default::default()
        }
    }

    fn seeded() -> ExchangeCore {
        let mut core = ExchangeCore::new();
        core.ssp.add_currency(CoreCurrencySpecification { currency: BASE, currency_scale_k: 1, ..Default::default() });
        core.ssp.add_currency(CoreCurrencySpecification { currency: QUOTE, currency_scale_k: 1, ..Default::default() });
        assert_eq!(core.ssp.add_symbol(spec()), CommandResultCode::Success);
        core.matching.add_symbol(&spec());
        core
    }

    #[test]
    fn total_balance_conserves_after_deposit_and_resting_bid() {
        let mut core = seeded();
        core.process_command(&mut OrderCommand { command: OrderCommandType::AddUser, uid: 1, ..Default::default() });
        core.process_command(&mut OrderCommand {
            command: OrderCommandType::BalanceAdjustment,
            uid: 1,
            symbol: QUOTE,
            price: 1_000_000,
            order_id: 1,
            ..Default::default()
        });
        core.process_command(&mut OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 2,
            symbol: SYMBOL,
            price: 50,
            size: 1000,
            reserve_bid_price: 50,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: 1,
            ..Default::default()
        });

        let tcb = core.query_total_balance();
        assert!(tcb.is_global_zero(), "conservation broken: sum={:?}", tcb.global_balances_sum());
        assert_eq!(tcb.currency_balances.get(&QUOTE).copied().unwrap_or(0), 1_000_000 - 50_000);
        assert_eq!(tcb.exchange_locked.get(&QUOTE).copied().unwrap_or(0), 50_000);
        assert_eq!(tcb.adjustments.get(&QUOTE).copied().unwrap_or(0), -1_000_000);
    }

    #[test]
    fn single_user_report_reflects_balance_and_lock() {
        let mut core = seeded();
        core.process_command(&mut OrderCommand { command: OrderCommandType::AddUser, uid: 7, ..Default::default() });
        core.process_command(&mut OrderCommand {
            command: OrderCommandType::BalanceAdjustment,
            uid: 7,
            symbol: QUOTE,
            price: 500,
            order_id: 1,
            ..Default::default()
        });
        let r = core.query_single_user(7, 0);
        assert!(r.found);
        assert_eq!(r.accounts.get(&QUOTE).copied().unwrap_or(0), 500);
        assert!(r.positions.is_empty());

        let missing = core.query_single_user(999, 0);
        assert!(!missing.found);
    }

    fn admin_cmd(command: OrderCommandType, uid: i64) -> OrderCommand {
        OrderCommand { command, uid, ..Default::default() }
    }

    #[test]
    fn suspend_resume_user_lifecycle() {
        let mut core = seeded();
        core.process_command(&mut admin_cmd(OrderCommandType::AddUser, 1));
        let mut susp = admin_cmd(OrderCommandType::SuspendUser, 1);
        core.process_command(&mut susp);
        assert_eq!(susp.result_code, Some(CommandResultCode::Success));
        assert!(core.ups.get(1).is_none(), "removed from registry after suspend");

        core.process_command(&mut admin_cmd(OrderCommandType::AddUser, 2));
        core.process_command(&mut OrderCommand {
            command: OrderCommandType::BalanceAdjustment,
            uid: 2,
            symbol: QUOTE,
            price: 5000,
            order_id: 1,
            ..Default::default()
        });
        let mut susp2 = admin_cmd(OrderCommandType::SuspendUser, 2);
        core.process_command(&mut susp2);
        assert_eq!(susp2.result_code, Some(CommandResultCode::UserMgmtUserNotSuspendableNonEmptyAccounts));

        let mut res = admin_cmd(OrderCommandType::ResumeUser, 9);
        core.process_command(&mut res);
        assert_eq!(res.result_code, Some(CommandResultCode::Success));
        assert!(core.ups.get(9).is_some());
        let mut res2 = admin_cmd(OrderCommandType::ResumeUser, 9);
        core.process_command(&mut res2);
        assert_eq!(res2.result_code, Some(CommandResultCode::UserMgmtUserNotSuspended));
    }

    #[test]
    fn position_mode_adjustment_switches_on_empty_user() {
        let mut core = seeded();
        core.process_command(&mut admin_cmd(OrderCommandType::AddUser, 1));
        let mut m = OrderCommand {
            command: OrderCommandType::PositionModeAdjustment,
            uid: 1,
            action: Some(OrderAction::Bid),
            ..Default::default()
        };
        core.process_command(&mut m);
        assert_eq!(m.result_code, Some(CommandResultCode::Success));
        let mut m2 = OrderCommand {
            command: OrderCommandType::PositionModeAdjustment,
            uid: 999,
            action: Some(OrderAction::Bid),
            ..Default::default()
        };
        core.process_command(&mut m2);
        assert_eq!(m2.result_code, Some(CommandResultCode::AuthInvalidUser));
    }

    #[test]
    fn balance_adjustment_emits_deposit_then_withdraw_events() {
        use crate::core::common::fund_event::FundEventType;
        let mut core = seeded();
        core.process_command(&mut admin_cmd(OrderCommandType::AddUser, 1));
        let mut dep = OrderCommand {
            command: OrderCommandType::BalanceAdjustment,
            uid: 1,
            symbol: QUOTE,
            price: 1000,
            order_id: 1,
            ..Default::default()
        };
        core.process_command(&mut dep);
        assert_eq!(dep.fund_events.len(), 1);
        assert_eq!(dep.fund_events[0].event_type, FundEventType::Deposit);
        assert_eq!(dep.fund_events[0].free, 1000);
        let mut wd = OrderCommand {
            command: OrderCommandType::BalanceAdjustment,
            uid: 1,
            symbol: QUOTE,
            price: -400,
            order_id: 2,
            ..Default::default()
        };
        core.process_command(&mut wd);
        assert_eq!(wd.fund_events.len(), 1);
        assert_eq!(wd.fund_events[0].event_type, FundEventType::Withdraw);
        assert_eq!(wd.fund_events[0].free, 600);
    }

    #[test]
    fn reset_fee_emits_reset_fee_events() {
        use crate::core::common::fund_event::FundEventType;
        let mut core = seeded();
        core.risk.fees.insert(QUOTE, 700);
        core.risk.loan_service.interest_revenue.insert(QUOTE, 300);
        let mut cmd = admin_cmd(OrderCommandType::ResetFee, 0);
        core.process_command(&mut cmd);
        assert_eq!(cmd.fund_events.len(), 1);
        assert_eq!(cmd.fund_events[0].event_type, FundEventType::ResetFee);
        assert_eq!(cmd.fund_events[0].currency, QUOTE);
        assert_eq!(cmd.fund_events[0].free, 1000);
    }

    #[test]
    fn reset_fee_drains_fees_into_adjustments_conserving() {
        let mut core = seeded();
        core.risk.fees.insert(QUOTE, 700);
        core.risk.loan_service.interest_revenue.insert(QUOTE, 300);
        let before_adj = core.risk.adjustments.get(&QUOTE).copied().unwrap_or(0);
        core.process_command(&mut admin_cmd(OrderCommandType::ResetFee, 0));
        assert_eq!(core.risk.fees.get(&QUOTE).copied().unwrap_or(0), 0);
        assert_eq!(core.risk.loan_service.interest_revenue.get(&QUOTE).copied().unwrap_or(0), 0);
        assert_eq!(core.risk.adjustments.get(&QUOTE).copied().unwrap_or(0), before_adj + 1000);
    }

    #[test]
    fn spot_trade_emits_transfer_events() {
        use crate::core::common::fund_event::FundEventType;
        const BUYER: i64 = 1;
        const SELLER: i64 = 2;
        let mut core = seeded();
        for (uid, cur, amt) in [(BUYER, QUOTE, 1_000_000i64), (SELLER, BASE, 1000)] {
            core.process_command(&mut admin_cmd(OrderCommandType::AddUser, uid));
            core.process_command(&mut OrderCommand {
                command: OrderCommandType::BalanceAdjustment,
                uid,
                symbol: cur,
                price: amt,
                order_id: uid,
                ..Default::default()
            });
        }
        core.process_command(&mut OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 10,
            symbol: SYMBOL,
            price: 50,
            size: 100,
            action: Some(OrderAction::Ask),
            order_type: Some(OrderType::Gtc),
            uid: SELLER,
            ..Default::default()
        });
        let mut buy = OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 11,
            symbol: SYMBOL,
            price: 50,
            size: 100,
            reserve_bid_price: 50,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: BUYER,
            ..Default::default()
        };
        core.process_command(&mut buy);
        let transfers: Vec<_> = buy.fund_events.iter().filter(|e| e.event_type == FundEventType::Transfer).collect();
        assert!(!transfers.is_empty(), "spot trade should emit TRANSFER events: {:?}", buy.fund_events);
        assert!(transfers.iter().any(|e| e.currency == QUOTE), "should have quote leg");
        assert!(transfers.iter().any(|e| e.currency == BASE), "should have base leg");
    }

    #[test]
    fn spot_trade_writes_back_mark_price() {
        const BUYER: i64 = 1;
        const SELLER: i64 = 2;
        let mut core = seeded();
        for (uid, cur, amt) in [(BUYER, QUOTE, 1_000_000i64), (SELLER, BASE, 1000)] {
            core.process_command(&mut admin_cmd(OrderCommandType::AddUser, uid));
            core.process_command(&mut OrderCommand {
                command: OrderCommandType::BalanceAdjustment,
                uid,
                symbol: cur,
                price: amt,
                order_id: uid,
                ..Default::default()
            });
        }
        core.process_command(&mut OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 10,
            symbol: SYMBOL,
            price: 50,
            size: 100,
            action: Some(OrderAction::Ask),
            order_type: Some(OrderType::Gtc),
            uid: SELLER,
            timestamp: 10_000,
            ..Default::default()
        });
        assert_eq!(core.risk.last_price_cache.get(&SYMBOL).map(|r| r.mark_price), None, "resting unmatched order should not write back");
        core.process_command(&mut OrderCommand {
            command: OrderCommandType::PlaceOrder,
            order_id: 11,
            symbol: SYMBOL,
            price: 50,
            size: 100,
            reserve_bid_price: 50,
            action: Some(OrderAction::Bid),
            order_type: Some(OrderType::Gtc),
            uid: BUYER,
            timestamp: 10_000,
            ..Default::default()
        });
        assert_eq!(core.risk.last_price_cache.get(&SYMBOL).map(|r| r.mark_price), Some(50), "spot trade price should write back markPrice");
        assert_eq!(core.risk.last_price_cache.get(&SYMBOL).map(|r| r.mark_price_ts), Some(10_000));
    }

    #[test]
    fn single_user_report_includes_resting_orders() {
        const SELLER: i64 = 7;
        let mut core = seeded();
        core.process_command(&mut admin_cmd(OrderCommandType::AddUser, SELLER));
        core.process_command(&mut OrderCommand {
            command: OrderCommandType::BalanceAdjustment,
            uid: SELLER,
            symbol: BASE,
            price: 1000,
            order_id: SELLER,
            ..Default::default()
        });
        for (oid, price) in [(101i64, 55i64), (102, 60)] {
            core.process_command(&mut OrderCommand {
                command: OrderCommandType::PlaceOrder,
                order_id: oid,
                symbol: SYMBOL,
                price,
                size: 10,
                action: Some(OrderAction::Ask),
                order_type: Some(OrderType::Gtc),
                uid: SELLER,
                ..Default::default()
            });
        }
        let r = core.query_single_user(SELLER, 0);
        assert_eq!(r.orders.len(), 2, "report should contain 2 resting orders: {:?}", r.orders);
        assert_eq!(r.orders[0].1.order_id, 101);
        assert_eq!(r.orders[0].0, SYMBOL);
        assert_eq!(r.orders[1].1.order_id, 102);
        assert!(r.orders.iter().all(|(_, o)| o.uid == SELLER));
        assert!(core.query_single_user(999, 0).orders.is_empty());
    }

    #[test]
    fn simple_reports_expose_engine_state() {
        let core = seeded();
        let sc = core.query_symbol_currency();
        assert_eq!(sc.symbols.len(), 1);
        assert_eq!(sc.currencies.len(), 2);
        assert!(core.query_fee_report().fees.is_empty());
        assert!(core.query_loan_platform().per_currency.is_empty());
        let h1 = core.query_state_hash();
        let h2 = core.query_state_hash();
        assert_eq!(h1.merged(), h2.merged());
        assert!(h1.components.contains_key("user_profiles"));
    }
}
