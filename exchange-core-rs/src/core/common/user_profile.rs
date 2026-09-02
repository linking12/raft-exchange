//! 对应 Java: exchange.core2.core.common.UserProfile（现货子集：
//! `uid`/`userStatus`/`accounts`/`exchangeLocked`——loans/cross-margin 方法本期不移植；
//! `processedTransactionIds` 按 Task 8 brief 简化为不带过期窗口的 `BTreeSet<i64>`，
//! 对应 Java `TimeWindowDedupSet`的最小子集：只保留"claim 一次，重复即拒"语义，不做时间淘汰。
//! P4 Task 1 新增期货子集：`positionMode`/`positions` + `createPositionsKey`/
//! `countPositionRecord`/`processPositionRecord`，见 §2）。
use std::collections::{BTreeMap, BTreeSet};

use crate::core::common::cmd::order_command_type::OrderCommandType;
use crate::core::common::order_action::OrderAction;
use crate::core::common::position_mode::PositionMode;
use crate::core::common::symbol_position_record::SymbolPositionRecord;
use crate::core::common::user_status::UserStatus;

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
}
