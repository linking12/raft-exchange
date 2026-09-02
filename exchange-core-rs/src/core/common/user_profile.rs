//! 对应 Java: exchange.core2.core.common.UserProfile（现货子集：
//! `uid`/`userStatus`/`accounts`/`exchangeLocked`——loans/cross-margin 方法本期不移植；
//! `processedTransactionIds` 按 Task 8 brief 简化为不带过期窗口的 `BTreeSet<i64>`，
//! 对应 Java `TimeWindowDedupSet`的最小子集：只保留"claim 一次，重复即拒"语义，不做时间淘汰。
//! P4 Task 1 新增期货子集：`positionMode`/`positions` + `createPositionsKey`/
//! `countPositionRecord`/`processPositionRecord`，见 §2）。
use std::collections::{BTreeMap, BTreeSet};

use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::core_currency_specification::CoreCurrencySpecification;
use crate::core::common::core_symbol_specification::CoreSymbolSpecification;
use crate::core::common::margin_mode::MarginMode;
use crate::core::common::order_action::OrderAction;
use crate::core::common::position_mode::PositionMode;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::common::user_status::UserStatus;
use crate::core::utils::core_arithmetic_utils as arithmetic;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UserProfile {
    pub uid: i64,
    pub user_status: UserStatus,
    /// currency -> balance（对应 Java `IntLongHashMap accounts`；用户物理余额总额，未拆锁定）。
    pub accounts: BTreeMap<i32, i64>,
    /// currency -> locked amount（对应 Java `IntLongHashMap exchangeLocked`；现货挂单冻结）。
    pub exchange_locked: BTreeMap<i32, i64>,
    /// 对应 Java `UserProfile.processedTransactionIds`（简化：无过期窗口）。用于
    /// `BALANCE_ADJUSTMENT`/`INTERNAL_TRANSFER`/loan 等命令的按 `orderId` 幂等去重。
    pub processed_tx_ids: BTreeSet<i64>,
    /// 对应 Java `UserProfile.positionMode`：单向 / 双向持仓，默认 `ONEWAY`。
    pub position_mode: PositionMode,
    /// 对应 Java `UserProfile.positions`（`IntObjectHashMap<SymbolPositionRecord>`）：
    /// symbol -> 持仓记录；`HEDGE` 模式下正 symbol 为多头、负 symbol 为空头，键由
    /// [`Self::create_positions_key`] 统一计算。
    pub positions: BTreeMap<i32, SymbolPositionRecord>,
}

impl UserProfile {
    pub fn new(uid: i64, user_status: UserStatus) -> Self {
        UserProfile {
            uid,
            user_status,
            accounts: BTreeMap::new(),
            exchange_locked: BTreeMap::new(),
            processed_tx_ids: BTreeSet::new(),
            position_mode: PositionMode::default(),
            positions: BTreeMap::new(),
        }
    }

    /// 对应 Java `createPositionsKey(int symbol, OrderAction orderAction,
    /// OrderCommandType command)`（`UserProfile.java:163-172`）：
    /// - `ONEWAY`：恒返回 `symbol`（单向持仓，键与方向无关）。
    /// - `HEDGE`：`action==BID` 用 `+symbol`（多头腿），否则 `-symbol`（空头腿）；若
    ///   `command` 是 `CLOSE_POSITION`/`FORCE_LIQUIDATION`（这两类命令的 `action` 表达的是
    ///   "平仓侧"，即目标仓位的反向），再整体翻符号 `-key` 指向被平的那条腿。
    pub fn create_positions_key(&self, symbol: i32, action: OrderAction, command: OrderCommandType) -> i32 {
        if self.position_mode == PositionMode::Hedge {
            let key = if action == OrderAction::Bid { symbol } else { -symbol };
            if command == OrderCommandType::ClosePosition || command == OrderCommandType::ForceLiquidation {
                return -key;
            }
            key
        } else {
            symbol
        }
    }

    /// 对应 Java `createPositionsKey(SymbolPositionRecord position)`（`:174-179`）：
    /// `HEDGE` 下键 = `direction.multiplier * symbol`（`LONG -> +symbol`，`SHORT -> -symbol`，
    /// `EMPTY -> 0`）；`ONEWAY` 恒为 `symbol`。
    pub fn create_positions_key_of(&self, position: &SymbolPositionRecord) -> i32 {
        if self.position_mode == PositionMode::Hedge {
            position.direction.multiplier() * position.symbol
        } else {
            position.symbol
        }
    }

    /// 对应 Java `countPositionRecord(int symbol, Predicate<SymbolPositionRecord> predicate)`：
    /// 统计指定 symbol 下满足 `predicate` 的仓位记录数量——`ONEWAY` 最多 1 条（键=`symbol`），
    /// `HEDGE` 最多 2 条（键=`symbol`/`-symbol`，多空两条腿独立计数）。
    pub fn count_position_record<F>(&self, symbol: i32, predicate: F) -> i32
    where
        F: Fn(&SymbolPositionRecord) -> bool,
    {
        let mut count = 0;
        if let Some(long_record) = self.positions.get(&symbol) {
            if predicate(long_record) {
                count += 1;
            }
        }
        if self.position_mode == PositionMode::Hedge {
            if let Some(short_record) = self.positions.get(&-symbol) {
                if predicate(short_record) {
                    count += 1;
                }
            }
        }
        count
    }

    /// 对应 Java `processPositionRecord(int symbol, Consumer<SymbolPositionRecord> consumer)`：
    /// 对指定 symbol 下所有仓位记录（`ONEWAY` 0/1 条，`HEDGE` 0/1/2 条）依次调用 `consumer`。
    pub fn process_position_record<F>(&mut self, symbol: i32, mut consumer: F)
    where
        F: FnMut(&mut SymbolPositionRecord),
    {
        if let Some(long_record) = self.positions.get_mut(&symbol) {
            consumer(long_record);
        }
        if self.position_mode == PositionMode::Hedge {
            if let Some(short_record) = self.positions.get_mut(&-symbol) {
                consumer(short_record);
            }
        }
    }

    /// 对应 Java `calculateCrossAvailable(int currency, CoreCurrencySpecification, IntFunction)`
    /// （`UserProfile.java:229-240`）：cross 可支配余额（currency scale）=
    /// `accounts − exchangeLocked − Σ 同 currency 各 ISOLATED 仓的虚拟锁定保证金`。
    ///
    /// **不**减 CROSS 仓的保证金（CROSS 是账户级虚拟分配，见 [`Self::cross_margin_base_allocation`]），
    /// **不**加任何 UPnL（由 caller 按需另加，如 `cross_margin_base_allocation` 里加 CROSS UPnL）。
    /// `openInitMarginSum` 开仓时未从 accounts 物理扣除（只在仓位记录里虚拟锁定），故 ISOLATED
    /// 仓要显式剥离；`extraMargin` 已在 `MARGIN_ADJUSTMENT` 时从 accounts 扣走，
    /// `calculate_required_margin_for_futures` 内部已含 `extraMargin` 的抵扣（详见该方法），
    /// 不重复处理。
    ///
    /// `symbol_spec_lookup` 用闭包解耦，避免 `common` 包反向依赖 `processors` 里的
    /// `SymbolSpecificationProvider`；caller 通常传 `|symbol| ssp.get_symbol(symbol)`。
    /// spec 缺失的仓跳过不扣（宁可 equity 略高估也不 panic）。
    pub fn calculate_cross_available<'a, F>(
        &self,
        currency: i32,
        currency_spec: &CoreCurrencySpecification,
        symbol_spec_lookup: F,
    ) -> i64
    where
        F: Fn(i32) -> Option<&'a CoreSymbolSpecification>,
    {
        let mut cross_available = self.account(currency) - self.locked(currency);
        for iso in self.positions.values() {
            if iso.margin_mode != MarginMode::Isolated || iso.currency != currency {
                continue;
            }
            let iso_spec = match symbol_spec_lookup(iso.symbol) {
                Some(s) => s,
                None => continue,
            };
            cross_available -= arithmetic::size_price_to_currency_scale(
                iso.calculate_required_margin_for_futures(iso_spec),
                iso_spec.base_scale_k,
                iso_spec.quote_scale_k,
                currency_spec.currency_scale_k,
            );
        }
        cross_available
    }

    /// 对应 Java `crossMarginBaseAllocation(IntFunction, IntFunction, IntObjectHashMap)`
    /// （`UserProfile.java:263-312`）：一次算好整账户所有 CROSS 仓的破产价基础 `marginBase`
    /// （position key → marginBase，sizePrice scale，与 `SymbolPositionRecord.open_init_margin_sum`
    /// 同 scale），直接喂 `SymbolPositionRecord::calculate_bankruptcy_price`（P6）的 CROSS 回调。
    ///
    /// 按 currency 分组，组内账户级 `marginBalance` 按 MM 占比分给每个 CROSS 仓：
    /// ```text
    /// marginBalance = crossAvailable + Σ UPnL（该 currency 全部 CROSS 仓）
    /// allocated_i   = truncMulDiv(marginBalance, mm_i, ΣMM)   （按 MM 占比分账户余额，向零截断）
    /// marginBase_i  = allocated_i − UPnL_i                    （currency scale）
    /// 返回值        = currencyToSizePriceScale(marginBase_i, spec_i, currencySpec)
    /// ```
    /// 守恒不变式（连续数学意义下）：`Σ marginBase_i(currency scale) = crossAvailable`——
    /// `truncMulDiv` 的整数截断在 `ΣMM` 不能整除 `marginBalance × mm_i` 时会引入 ≤ (n−1) 个
    /// currency 最小单位的截断误差，与 Java 逐字一致（非本移植引入的新误差）。
    ///
    /// 边界：某 currency 组 `ΣMM == 0` → 该组不产出任何 entry（caller 对未出现的 position key
    /// 取默认 0）；单个仓 spec 或 mark price 缺失 → 该仓跳过（不计入 UPnL/MM 累加、也不产出
    /// entry），其余仓照常分摊。`marginBalance` 用 `i128` 中间精度承接 `crossAvailable + totalUpnl`
    /// 的加法（Java 原版此处有过 `long` 溢出修复，本移植直接用 `i128` 规避同类问题，参考文档 §5
    /// "before you begin" 提示）。
    ///
    /// `symbol_spec_lookup`/`currency_spec_lookup`/`mark_price_lookup` 均用闭包解耦，避免
    /// `common` 包反向依赖 `processors` 里的 provider；caller 通常传
    /// `|s| ssp.get_symbol(s)` / `|c| ssp.get_currency(c)` / `|s| engine.mark_price(s)`。
    pub fn cross_margin_base_allocation<'a, FS, FC, FM>(
        &self,
        symbol_spec_lookup: FS,
        currency_spec_lookup: FC,
        mark_price_lookup: FM,
    ) -> BTreeMap<i32, i64>
    where
        FS: Fn(i32) -> Option<&'a CoreSymbolSpecification>,
        FC: Fn(i32) -> Option<&'a CoreCurrencySpecification>,
        FM: Fn(i32) -> Option<i64>,
    {
        let mut margin_base_by_pos: BTreeMap<i32, i64> = BTreeMap::new();

        // 账户级 marginBalance 分摊在单一 currency 内闭合，先按仓位的 currency 字段分组
        // （不是 position key——同一 currency 下可能有多个 symbol 的 CROSS 仓）。
        let mut cross_by_currency: BTreeMap<i32, Vec<i32>> = BTreeMap::new();
        for (&key, p) in self.positions.iter() {
            if p.margin_mode == MarginMode::Cross {
                cross_by_currency.entry(p.currency).or_default().push(key);
            }
        }

        for (&currency, keys) in cross_by_currency.iter() {
            let currency_spec = match currency_spec_lookup(currency) {
                Some(c) => c,
                None => continue, // currency spec 缺失整组跳过，无法做 scale 换算
            };

            let mut upnl_by_pos: BTreeMap<i32, i64> = BTreeMap::new();
            let mut mm_by_pos: BTreeMap<i32, i64> = BTreeMap::new();
            let mut total_upnl: i64 = 0;
            let mut total_mm: i64 = 0;

            for &key in keys {
                let p = &self.positions[&key];
                let p_spec = match symbol_spec_lookup(p.symbol) {
                    Some(s) => s,
                    None => continue,
                };
                let p_price = match mark_price_lookup(p.symbol) {
                    Some(price) => price,
                    None => continue,
                };
                let pnl = arithmetic::size_price_to_currency_scale(
                    p.estimate_pnl(p_price),
                    p_spec.base_scale_k,
                    p_spec.quote_scale_k,
                    currency_spec.currency_scale_k,
                );
                let mm = arithmetic::size_price_to_currency_scale(
                    p.calculate_maintenance_margin(p_spec, p_price),
                    p_spec.base_scale_k,
                    p_spec.quote_scale_k,
                    currency_spec.currency_scale_k,
                );
                upnl_by_pos.insert(key, pnl);
                mm_by_pos.insert(key, mm);
                total_upnl += pnl;
                total_mm += mm;
            }

            if total_mm == 0 {
                continue;
            }

            let cross_available = self.calculate_cross_available(currency, currency_spec, &symbol_spec_lookup);
            let margin_balance =
                i64::try_from(cross_available as i128 + total_upnl as i128)
                    .unwrap_or_else(|_| panic!("overflow: crossAvailable + totalUpnl for currency {currency}"));

            for (&key, &mm) in mm_by_pos.iter() {
                let allocated = arithmetic::trunc_mul_div(margin_balance, mm, total_mm);
                let margin_base_currency = allocated - upnl_by_pos[&key];
                // currency scale → sizePriceScale（喂 SPR.calculate_bankruptcy_price，与
                // open_init_margin_sum 同 scale）；spec 在上面的累加循环里已确认存在。
                let pos_spec = symbol_spec_lookup(self.positions[&key].symbol)
                    .expect("symbol spec disappeared between accumulation and allocation loops");
                margin_base_by_pos.insert(
                    key,
                    arithmetic::currency_to_size_price_scale(
                        margin_base_currency,
                        pos_spec.base_scale_k,
                        pos_spec.quote_scale_k,
                        currency_spec.currency_scale_k,
                    ),
                );
            }
        }

        margin_base_by_pos
    }

    /// 对应 Java `TimeWindowDedupSet.tryClaim(id, nowMs)`（简化：省略时间窗口淘汰）：
    /// 首次见到该 `tx_id` → 记录并返回 `true`；已见过 → 返回 `false`，不重复记录。
    pub fn try_claim_tx(&mut self, tx_id: i64) -> bool {
        self.processed_tx_ids.insert(tx_id)
    }

    /// 对应 Java `accounts.get(currency)`：Eclipse Collections 原始类型 map 缺省值语义，缺省 0。
    pub fn account(&self, currency: i32) -> i64 {
        *self.accounts.get(&currency).unwrap_or(&0)
    }

    /// 对应 Java `exchangeLocked.get(currency)`：缺省 0。
    pub fn locked(&self, currency: i32) -> i64 {
        *self.exchange_locked.get(&currency).unwrap_or(&0)
    }

    /// 对应 Java `accounts.addToValue(currency, delta)`：缺省 0 起累加，`delta` 可为负。
    pub fn add_to_account(&mut self, currency: i32, delta: i64) {
        *self.accounts.entry(currency).or_insert(0) += delta;
    }

    /// 对应 Java `exchangeLocked.addToValue(currency, delta)`。
    pub fn add_to_locked(&mut self, currency: i32, delta: i64) {
        *self.exchange_locked.entry(currency).or_insert(0) += delta;
    }

    /// 确定性状态 hash：折叠 `uid`、`user_status`、排序后的 `accounts`/`exchange_locked`、
    /// `position_mode`、排序后的 `positions`（`BTreeMap` 天然按 key 升序，天然满足"排序"要求）。
    /// 风格对齐 `orderbook::order_book_naive_impl::OrderBookNaiveImpl::state_hash`
    /// （`h = h*31 + field`滚动折叠 + i64->i32 fold，对应 Java `Long.hashCode`）。不保证与
    /// Java `Objects.hash(...)` 数值相等（现货子集未含 loans/processedTransactionIds 字段），
    /// 只保证「同状态 → 同 hash，不同状态 → 不同 hash」。
    pub fn state_hash(&self) -> i32 {
        let mut h: i64 = 17;
        h = h.wrapping_mul(31).wrapping_add(self.uid);
        h = h.wrapping_mul(31).wrapping_add(self.user_status.code() as i64);
        for (&cur, &amt) in &self.accounts {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(amt);
        }
        for (&cur, &amt) in &self.exchange_locked {
            h = h.wrapping_mul(31).wrapping_add(cur as i64);
            h = h.wrapping_mul(31).wrapping_add(amt);
        }
        h = h.wrapping_mul(31).wrapping_add(self.position_mode.code() as i64);
        for (&key, record) in &self.positions {
            h = h.wrapping_mul(31).wrapping_add(key as i64);
            h = h.wrapping_mul(31).wrapping_add(record.state_hash() as i64);
        }
        ((h >> 32) as i32) ^ (h as i32)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn account_and_locked_default_to_zero() {
        let p = UserProfile::new(1, UserStatus::Active);
        assert_eq!(p.account(1), 0);
        assert_eq!(p.locked(1), 0);
    }

    #[test]
    fn add_to_account_accumulates_from_zero() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.add_to_account(1, 100);
        p.add_to_account(1, -30);
        assert_eq!(p.account(1), 70);
        assert_eq!(p.account(2), 0); // 未涉及币种仍缺省 0
    }

    #[test]
    fn add_to_locked_accumulates_from_zero() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.add_to_locked(1, 50);
        p.add_to_locked(1, 25);
        assert_eq!(p.locked(1), 75);
    }

    #[test]
    fn state_hash_deterministic_for_same_state() {
        let mut a = UserProfile::new(7, UserStatus::Active);
        a.add_to_account(1, 100);
        a.add_to_locked(1, 10);
        let mut b = UserProfile::new(7, UserStatus::Active);
        b.add_to_account(1, 100);
        b.add_to_locked(1, 10);
        assert_eq!(a.state_hash(), b.state_hash());
    }

    #[test]
    fn state_hash_changes_with_different_state() {
        let base = UserProfile::new(7, UserStatus::Active);
        let h0 = base.state_hash();

        let diff_uid = UserProfile::new(8, UserStatus::Active);
        assert_ne!(h0, diff_uid.state_hash());

        let diff_status = UserProfile::new(7, UserStatus::Suspended);
        assert_ne!(h0, diff_status.state_hash());

        let mut diff_account = UserProfile::new(7, UserStatus::Active);
        diff_account.add_to_account(1, 1);
        assert_ne!(h0, diff_account.state_hash());

        let mut diff_locked = UserProfile::new(7, UserStatus::Active);
        diff_locked.add_to_locked(1, 1);
        assert_ne!(h0, diff_locked.state_hash());
    }

    #[test]
    fn new_defaults_to_oneway_with_empty_positions() {
        let p = UserProfile::new(1, UserStatus::Active);
        assert_eq!(p.position_mode, PositionMode::OneWay);
        assert!(p.positions.is_empty());
    }

    // ------------------------------------------------------------------
    // create_positions_key — 对应 Java `UserProfile.java:163-172`
    // ------------------------------------------------------------------

    #[test]
    fn create_positions_key_oneway_is_always_raw_symbol() {
        let p = UserProfile::new(1, UserStatus::Active);
        assert_eq!(p.create_positions_key(100, OrderAction::Bid, OrderCommandType::PlaceOrder), 100);
        assert_eq!(p.create_positions_key(100, OrderAction::Ask, OrderCommandType::PlaceOrder), 100);
        assert_eq!(p.create_positions_key(100, OrderAction::Ask, OrderCommandType::ClosePosition), 100);
    }

    #[test]
    fn create_positions_key_hedge_place_order_uses_action_sign() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.position_mode = PositionMode::Hedge;
        assert_eq!(p.create_positions_key(100, OrderAction::Bid, OrderCommandType::PlaceOrder), 100);
        assert_eq!(p.create_positions_key(100, OrderAction::Ask, OrderCommandType::PlaceOrder), -100);
    }

    #[test]
    fn create_positions_key_hedge_close_or_force_liquidation_flips_sign() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.position_mode = PositionMode::Hedge;
        // BID + CLOSE_POSITION: 先按 action 得 +100，再因 CLOSE_POSITION 翻符号 -> -100
        assert_eq!(p.create_positions_key(100, OrderAction::Bid, OrderCommandType::ClosePosition), -100);
        // ASK + CLOSE_POSITION: 先按 action 得 -100，再翻符号 -> +100
        assert_eq!(p.create_positions_key(100, OrderAction::Ask, OrderCommandType::ClosePosition), 100);
        // FORCE_LIQUIDATION 与 CLOSE_POSITION 同规则
        assert_eq!(p.create_positions_key(100, OrderAction::Bid, OrderCommandType::ForceLiquidation), -100);
        assert_eq!(p.create_positions_key(100, OrderAction::Ask, OrderCommandType::ForceLiquidation), 100);
    }

    #[test]
    fn create_positions_key_of_record_oneway_is_raw_symbol() {
        let p = UserProfile::new(1, UserStatus::Active);
        let mut rec = SymbolPositionRecord::new(1, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 1);
        rec.direction = crate::core::common::position_direction::PositionDirection::Short;
        assert_eq!(p.create_positions_key_of(&rec), 100);
    }

    #[test]
    fn create_positions_key_of_record_hedge_uses_direction_multiplier() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.position_mode = PositionMode::Hedge;
        let mut rec = SymbolPositionRecord::new(1, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 1);

        rec.direction = crate::core::common::position_direction::PositionDirection::Long;
        assert_eq!(p.create_positions_key_of(&rec), 100);

        rec.direction = crate::core::common::position_direction::PositionDirection::Short;
        assert_eq!(p.create_positions_key_of(&rec), -100);

        rec.direction = crate::core::common::position_direction::PositionDirection::Empty;
        assert_eq!(p.create_positions_key_of(&rec), 0);
    }

    // ------------------------------------------------------------------
    // count_position_record / process_position_record
    // ------------------------------------------------------------------

    #[test]
    fn count_position_record_oneway_counts_only_raw_symbol_key() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.positions.insert(100, SymbolPositionRecord::new(1, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 1));
        assert_eq!(p.count_position_record(100, |_| true), 1);
        assert_eq!(p.count_position_record(200, |_| true), 0);
    }

    #[test]
    fn count_position_record_hedge_counts_both_legs_matching_predicate() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.position_mode = PositionMode::Hedge;
        p.positions.insert(100, SymbolPositionRecord::new(1, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 5));
        p.positions.insert(-100, SymbolPositionRecord::new(1, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 3));

        assert_eq!(p.count_position_record(100, |_| true), 2);
        assert_eq!(p.count_position_record(100, |r| r.leverage == 5), 1);
        assert_eq!(p.count_position_record(100, |r| r.leverage == 99), 0);
    }

    #[test]
    fn process_position_record_oneway_visits_only_raw_symbol() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.positions.insert(100, SymbolPositionRecord::new(1, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 1));

        let mut visited = 0;
        p.process_position_record(100, |r| {
            r.profit = 42;
            visited += 1;
        });
        assert_eq!(visited, 1);
        assert_eq!(p.positions.get(&100).unwrap().profit, 42);
    }

    #[test]
    fn process_position_record_hedge_visits_both_legs() {
        let mut p = UserProfile::new(1, UserStatus::Active);
        p.position_mode = PositionMode::Hedge;
        p.positions.insert(100, SymbolPositionRecord::new(1, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 1));
        p.positions.insert(-100, SymbolPositionRecord::new(1, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 1));

        let mut visited = 0;
        p.process_position_record(100, |r| {
            r.profit = 7;
            visited += 1;
        });
        assert_eq!(visited, 2);
        assert_eq!(p.positions.get(&100).unwrap().profit, 7);
        assert_eq!(p.positions.get(&-100).unwrap().profit, 7);
    }

    #[test]
    fn state_hash_changes_with_position_mode_and_positions() {
        let base = UserProfile::new(7, UserStatus::Active);
        let h0 = base.state_hash();

        let mut diff_mode = UserProfile::new(7, UserStatus::Active);
        diff_mode.position_mode = PositionMode::Hedge;
        assert_ne!(h0, diff_mode.state_hash());

        let mut diff_positions = UserProfile::new(7, UserStatus::Active);
        diff_positions
            .positions
            .insert(100, SymbolPositionRecord::new(7, 100, 2, crate::core::common::margin_mode::MarginMode::Isolated, 1));
        assert_ne!(h0, diff_positions.state_hash());
    }

    // ------------------------------------------------------------------
    // calculate_cross_available — 对应 Java `UserProfile.java:229-240`（P4 Task 5 §5）
    // ------------------------------------------------------------------

    fn currency_spec_scale1(currency: i32) -> CoreCurrencySpecification {
        CoreCurrencySpecification { currency, currency_scale_k: 1 }
    }

    /// `base_scale_k=quote_scale_k=currency_scale_k=1`：`size_price_to_currency_scale`/
    /// `currency_to_size_price_scale` 恒等换算，让测试算术直接在原始整数上验证公式，不被 scale
    /// 换算噪声干扰（scale 换算本身已在 `core_arithmetic_utils` 单测覆盖）。
    fn symbol_spec_scale1(symbol_id: i32, base_currency: i32, quote_currency: i32) -> CoreSymbolSpecification {
        CoreSymbolSpecification {
            symbol_id,
            base_currency,
            quote_currency,
            base_scale_k: 1,
            quote_scale_k: 1,
            ..Default::default()
        }
    }

    /// 构造一个真实开仓（非空）的仓位：`leverage=1` + `init_margin` 未配置（0，按 Java 文档
    /// 100% 初始保证金率）时，`calculate_required_margin_for_futures` 退化为直接返回
    /// `open_init_margin_sum`（无 pending 挂单，`new_exposure_notional=0`）——测试里按此口径手工
    /// 摆放 `open_init_margin_sum`，等价于「先开仓再收敛保证金」但省去中间步骤。
    #[allow(clippy::too_many_arguments)] // 测试 helper，逐字对应仓位关键字段，拆分反而失真
    fn open_position(
        uid: i64,
        symbol: i32,
        currency: i32,
        margin_mode: MarginMode,
        direction: crate::core::common::position_direction::PositionDirection,
        open_volume: i64,
        open_price_sum: i64,
        open_init_margin_sum: i64,
    ) -> SymbolPositionRecord {
        let mut p = SymbolPositionRecord::new(uid, symbol, currency, margin_mode, 1);
        p.direction = direction;
        p.open_volume = open_volume;
        p.open_price_sum = open_price_sum;
        p.open_init_margin_sum = open_init_margin_sum;
        p
    }

    #[test]
    fn calculate_cross_available_subtracts_isolated_margin_in_same_currency() {
        let mut up = UserProfile::new(1, UserStatus::Active);
        up.add_to_account(2, 100_000);
        up.add_to_locked(2, 5_000);
        let iso = open_position(
            1,
            100,
            2,
            MarginMode::Isolated,
            crate::core::common::position_direction::PositionDirection::Long,
            10,
            10_000,
            10_000,
        );
        up.positions.insert(100, iso);

        let spec = symbol_spec_scale1(100, 1, 2);
        let currency_spec = currency_spec_scale1(2);
        let available = up.calculate_cross_available(2, &currency_spec, |s| if s == 100 { Some(&spec) } else { None });

        assert_eq!(available, 100_000 - 5_000 - 10_000);
    }

    #[test]
    fn calculate_cross_available_does_not_subtract_cross_position_margin() {
        let mut up = UserProfile::new(1, UserStatus::Active);
        up.add_to_account(2, 100_000);
        let cross = open_position(
            1,
            100,
            2,
            MarginMode::Cross,
            crate::core::common::position_direction::PositionDirection::Long,
            10,
            10_000,
            10_000,
        );
        up.positions.insert(100, cross);

        let spec = symbol_spec_scale1(100, 1, 2);
        let currency_spec = currency_spec_scale1(2);
        // CROSS 仓的保证金不减——它是账户级虚拟分配，见 cross_margin_base_allocation。
        let available = up.calculate_cross_available(2, &currency_spec, |s| if s == 100 { Some(&spec) } else { None });

        assert_eq!(available, 100_000);
    }

    #[test]
    fn calculate_cross_available_ignores_isolated_position_in_other_currency() {
        let mut up = UserProfile::new(1, UserStatus::Active);
        up.add_to_account(2, 100_000);
        let iso_other_cur = open_position(
            1,
            100,
            3, // 另一 currency
            MarginMode::Isolated,
            crate::core::common::position_direction::PositionDirection::Long,
            10,
            10_000,
            10_000,
        );
        up.positions.insert(100, iso_other_cur);

        let spec = symbol_spec_scale1(100, 1, 3);
        let currency_spec = currency_spec_scale1(2);
        let available = up.calculate_cross_available(2, &currency_spec, |s| if s == 100 { Some(&spec) } else { None });

        // 只查 currency=2 的账户/冻结，仓位在 currency=3 上，不扣。
        assert_eq!(available, 100_000);
    }

    #[test]
    fn calculate_cross_available_skips_isolated_position_with_missing_spec() {
        let mut up = UserProfile::new(1, UserStatus::Active);
        up.add_to_account(2, 100_000);
        let iso = open_position(
            1,
            100,
            2,
            MarginMode::Isolated,
            crate::core::common::position_direction::PositionDirection::Long,
            10,
            10_000,
            10_000,
        );
        up.positions.insert(100, iso);

        let currency_spec = currency_spec_scale1(2);
        // spec 查找恒返回 None：宁可 equity 略高估也不 panic。
        let available =
            up.calculate_cross_available(2, &currency_spec, |_s: i32| -> Option<&CoreSymbolSpecification> { None });

        assert_eq!(available, 100_000);
    }

    // ------------------------------------------------------------------
    // cross_margin_base_allocation — 对应 Java `UserProfile.java:263-312`（P4 Task 5 §5）
    // ------------------------------------------------------------------

    #[test]
    fn cross_margin_base_allocation_invariant_sum_equals_cross_available_with_even_mm_split() {
        use crate::core::common::position_direction::PositionDirection;

        let mut up = UserProfile::new(1, UserStatus::Active);
        up.add_to_account(2, 100_000);
        // 无 ISOLATED 仓、无冻结 → cross_available = 100_000。

        // P1: LONG，open_volume=10，mark=100 → notional=1000 → MM1=1000（未配置分档表按 100%）；
        //     open_price_sum=900 → UPnL1 = 1*(1000-900) = 100。
        let p1 = open_position(1, 100, 2, MarginMode::Cross, PositionDirection::Long, 10, 900, 0);
        // P2: SHORT，open_volume=20，mark=50 → notional=1000 → MM2=1000；
        //     open_price_sum=1100 → UPnL2 = -1*(1000-1100) = 100。
        let p2 = open_position(1, 200, 2, MarginMode::Cross, PositionDirection::Short, 20, 1100, 0);
        up.positions.insert(100, p1);
        up.positions.insert(200, p2);

        let spec1 = symbol_spec_scale1(100, 1, 2);
        let spec2 = symbol_spec_scale1(200, 1, 2);
        let currency_spec = currency_spec_scale1(2);

        let symbol_spec_lookup = |s: i32| -> Option<&CoreSymbolSpecification> {
            if s == 100 {
                Some(&spec1)
            } else if s == 200 {
                Some(&spec2)
            } else {
                None
            }
        };
        let currency_spec_lookup = |c: i32| -> Option<&CoreCurrencySpecification> {
            if c == 2 {
                Some(&currency_spec)
            } else {
                None
            }
        };
        let mark_price_lookup = |s: i32| -> Option<i64> {
            match s {
                100 => Some(100),
                200 => Some(50),
                _ => None,
            }
        };

        let allocation = up.cross_margin_base_allocation(symbol_spec_lookup, currency_spec_lookup, mark_price_lookup);

        // MM 相等（各 1000）→ marginBalance(100_200) 均分，无截断误差。
        // margin_base_i(currency scale) = allocated_i(50_100) - upnl_i(100) = 50_000。
        // scale 恒等（base=quote=currency_scale_k=1）→ sizePrice scale 数值不变。
        assert_eq!(*allocation.get(&100).unwrap(), 50_000);
        assert_eq!(*allocation.get(&200).unwrap(), 50_000);

        // 守恒不变式：Σ marginBase_i == cross_available（currency scale；此处 scale 恒等）。
        let cross_available = up.calculate_cross_available(2, &currency_spec, symbol_spec_lookup);
        let sum: i64 = allocation.values().sum();
        assert_eq!(sum, cross_available);
        assert_eq!(cross_available, 100_000);
    }

    #[test]
    fn cross_margin_base_allocation_splits_proportionally_to_maintenance_margin() {
        use crate::core::common::position_direction::PositionDirection;

        let mut up = UserProfile::new(1, UserStatus::Active);
        up.add_to_account(2, 100_000);

        // MM1=1000（volume10×mark100，1/3 权重）、MM2=2000（volume20×mark100，2/3 权重）；
        // UPnL 均 0（open_price_sum == open_volume × mark）。
        let p1 = open_position(1, 100, 2, MarginMode::Cross, PositionDirection::Long, 10, 1_000, 0);
        let p2 = open_position(1, 200, 2, MarginMode::Cross, PositionDirection::Long, 20, 2_000, 0);
        up.positions.insert(100, p1);
        up.positions.insert(200, p2);

        let spec1 = symbol_spec_scale1(100, 1, 2);
        let spec2 = symbol_spec_scale1(200, 1, 2);
        let currency_spec = currency_spec_scale1(2);

        let symbol_spec_lookup = |s: i32| -> Option<&CoreSymbolSpecification> {
            if s == 100 {
                Some(&spec1)
            } else if s == 200 {
                Some(&spec2)
            } else {
                None
            }
        };
        let currency_spec_lookup = |c: i32| -> Option<&CoreCurrencySpecification> {
            if c == 2 {
                Some(&currency_spec)
            } else {
                None
            }
        };
        let mark_price_lookup = |_s: i32| -> Option<i64> { Some(100) };

        let allocation = up.cross_margin_base_allocation(symbol_spec_lookup, currency_spec_lookup, mark_price_lookup);

        // marginBalance = cross_available(100_000) + totalUpnl(0) = 100_000；MM1:MM2 = 1:2。
        // allocated1 = trunc(100_000*1000/3000) = 33_333；allocated2 = trunc(100_000*2000/3000) = 66_666。
        // marginBase_i = allocated_i（UPnL_i=0）。
        assert_eq!(*allocation.get(&100).unwrap(), 33_333);
        assert_eq!(*allocation.get(&200).unwrap(), 66_666);

        // 截断误差 1（100_000 - 33_333 - 66_666 = 1）：与 Java truncMulDiv 逐字一致的已知舍入行为，
        // 不是本移植引入的新误差（见 cross_margin_base_allocation 文档注释）。
        let cross_available = up.calculate_cross_available(2, &currency_spec, symbol_spec_lookup);
        let sum: i64 = allocation.values().sum();
        assert_eq!(cross_available - sum, 1);
    }

    #[test]
    fn cross_margin_base_allocation_omits_currency_group_with_zero_total_mm() {
        use crate::core::common::position_direction::PositionDirection;

        let mut up = UserProfile::new(1, UserStatus::Active);
        up.add_to_account(2, 100_000);
        // open_volume=0 → calculate_maintenance_margin 恒 0（Java: 空仓不计维持保证金）。
        let empty_cross = open_position(1, 100, 2, MarginMode::Cross, PositionDirection::Empty, 0, 0, 0);
        up.positions.insert(100, empty_cross);

        let spec = symbol_spec_scale1(100, 1, 2);
        let currency_spec = currency_spec_scale1(2);
        let allocation = up.cross_margin_base_allocation(
            |s| if s == 100 { Some(&spec) } else { None },
            |c| if c == 2 { Some(&currency_spec) } else { None },
            |_s| Some(100),
        );

        assert!(allocation.is_empty());
    }

    #[test]
    fn cross_margin_base_allocation_skips_position_with_missing_mark_price_but_keeps_others() {
        use crate::core::common::position_direction::PositionDirection;

        let mut up = UserProfile::new(1, UserStatus::Active);
        up.add_to_account(2, 100_000);
        let p1 = open_position(1, 100, 2, MarginMode::Cross, PositionDirection::Long, 10, 1_000, 0);
        let p2 = open_position(1, 200, 2, MarginMode::Cross, PositionDirection::Long, 10, 1_000, 0);
        up.positions.insert(100, p1);
        up.positions.insert(200, p2); // symbol 200 无 mark price

        let spec1 = symbol_spec_scale1(100, 1, 2);
        let spec2 = symbol_spec_scale1(200, 1, 2);
        let currency_spec = currency_spec_scale1(2);

        let allocation = up.cross_margin_base_allocation(
            |s| if s == 100 {
                Some(&spec1)
            } else if s == 200 {
                Some(&spec2)
            } else {
                None
            },
            |c| if c == 2 { Some(&currency_spec) } else { None },
            |s| if s == 100 { Some(100) } else { None }, // symbol 200 缺 mark price
        );

        // symbol 200 跳过（不计入 UPnL/MM，也不产出 entry）；symbol 100 独占全部 marginBalance。
        assert!(allocation.contains_key(&100));
        assert!(!allocation.contains_key(&200));
        assert_eq!(*allocation.get(&100).unwrap(), 100_000);
    }
}
