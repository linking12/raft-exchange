//! 对应 Java: exchange.core2.core.common.UserProfile（现货子集：
//! `uid`/`userStatus`/`accounts`/`exchangeLocked`——positions/loans/dedup/margin 方法本期不移植）。
use std::collections::BTreeMap;

use crate::api::enums::UserStatus;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UserProfile {
    pub uid: i64,
    pub user_status: UserStatus,
    /// currency -> balance（对应 Java `IntLongHashMap accounts`；用户物理余额总额，未拆锁定）。
    pub accounts: BTreeMap<i32, i64>,
    /// currency -> locked amount（对应 Java `IntLongHashMap exchangeLocked`；现货挂单冻结）。
    pub exchange_locked: BTreeMap<i32, i64>,
}

impl UserProfile {
    pub fn new(uid: i64, user_status: UserStatus) -> Self {
        UserProfile {
            uid,
            user_status,
            accounts: BTreeMap::new(),
            exchange_locked: BTreeMap::new(),
        }
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

    /// 确定性状态 hash：折叠 `uid` + `user_status` + 排序后的 `accounts`/`exchange_locked`
    /// （`BTreeMap` 天然按 key 升序，天然满足"排序"要求）。风格对齐
    /// `orderbook::naive::OrderBookNaive::state_hash`（`h = h*31 + field`滚动折叠 + i64->i32 fold，
    /// 对应 Java `Long.hashCode`）。不保证与 Java `Objects.hash(...)` 数值相等（现货子集未含
    /// positions/loans/processedTransactionIds 字段），只保证「同状态 → 同 hash，不同状态 → 不同 hash」。
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
}
