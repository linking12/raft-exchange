//! 对应 Java `UserProfileService`：有状态的用户档案表（uid -> `UserProfile`），
//! 负责建档/挂起(suspend)/恢复(resume)生命周期管理。Java 类注释强调
//! "Stateful(!)"——这是撮合/风控引擎持有的核心可变状态之一。
//!
//! 注意：Java 版本的 `balanceAdjustment`（按 transactionId 幂等的余额调整，
//! 见其类注释里"先 NSF 校验、再 tryClaim"的顺序不变式）在本文件**没有**对应
//! 实现，未在此处移植/复刻，余额调整逻辑在 Rust 侧的落脚点不在本文件。

use std::collections::BTreeMap;

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::user_profile::UserProfile;
use crate::core::common::user_status::UserStatus;

/// uid -> 用户档案。对应 Java `userProfiles: LongObjectHashMap<UserProfile>`。
#[derive(Debug, Clone, Default)]
pub struct UserProfileService {
    pub users: BTreeMap<i64, UserProfile>,
}

impl UserProfileService {

    pub fn new() -> Self {
        Self::default()
    }

    /// 对应 Java `addEmptyUserProfile`：以已知的唯一 uid 新建一个空档案（初始状态
    /// `Active`）。uid 已存在时拒绝（`UserMgmtUserAlreadyExists`），不覆盖。
    pub fn add_empty_user_profile(&mut self, uid: i64) -> CommandResultCode {
        if self.users.contains_key(&uid) {
            return CommandResultCode::UserMgmtUserAlreadyExists;
        }
        self.users.insert(uid, UserProfile::new(uid, UserStatus::Active));
        CommandResultCode::Success
    }

    /// 对应 Java `suspendUserProfile`：挂起（=从表中彻底移除）一个不活跃用户档案以节省内存/
    /// 提升性能。前置条件（对应 Java 各分支）：uid 必须存在；未处于已挂起状态；
    /// 无非空仓位（`positions`）；无非零余额（`accounts`）；无非零现货挂单冻结
    /// （`exchange_locked`，Java 注释指出这项也必须清零，否则 remove 时这部分锁仓
    /// 会被遗弃，后续撮合释放会让新建的 suspended 档案出现负 exchange_locked，
    /// 破坏全局守恒）。任一条件不满足则拒绝，全部满足才真正从 `users` 移除。
    pub fn suspend_user_profile(&mut self, uid: i64) -> CommandResultCode {
        let Some(up) = self.users.get(&uid) else {
            return CommandResultCode::UserMgmtUserNotFound;
        };
        if up.user_status == UserStatus::Suspended {
            return CommandResultCode::UserMgmtUserAlreadySuspended;
        }
        if up.positions.values().any(|p| !p.is_empty()) {
            return CommandResultCode::UserMgmtUserNotSuspendableHasPositions;
        }
        if up.accounts.values().any(|&v| v != 0) {
            return CommandResultCode::UserMgmtUserNotSuspendableNonEmptyAccounts;
        }
        if up.exchange_locked.values().any(|&v| v != 0) {
            return CommandResultCode::UserMgmtUserNotSuspendableNonEmptyAccounts;
        }
        self.users.remove(&uid);
        CommandResultCode::Success
    }

    /// 对应 Java `resumeUserProfile`：恢复一个已挂起的档案。若档案不存在，则视为
    /// 从未建档/彻底挂起过，直接新建一个空的 `Active` 档案（此后续的余额调整会
    /// 另行应用，Java 注释称这是为了让 resume 能"合并"档案）；若存在但不是
    /// `Suspended` 状态（含重复 resume），拒绝（`UserMgmtUserNotSuspended`）；
    /// 否则将已挂起档案（可能仍带有未清空的仓位/余额，Java 注释说明这是可能发生的
    /// 场景）翻回 `Active`。
    pub fn resume_user_profile(&mut self, uid: i64) -> CommandResultCode {
        match self.users.get_mut(&uid) {
            None => {
                self.users.insert(uid, UserProfile::new(uid, UserStatus::Active));
                CommandResultCode::Success
            }
            Some(up) if up.user_status != UserStatus::Suspended => CommandResultCode::UserMgmtUserNotSuspended,
            Some(up) => {
                up.user_status = UserStatus::Active;
                CommandResultCode::Success
            }
        }
    }

    /// 对应 Java `getUserProfileOrAddSuspended`：查不到则以 `Suspended` 状态新建并插入
    /// （而非 `Active`），返回其可变引用。
    pub fn get_or_add_suspended(&mut self, uid: i64) -> &mut UserProfile {
        self.users
            .entry(uid)
            .or_insert_with(|| UserProfile::new(uid, UserStatus::Suspended))
    }

    /// 对应 Java `getUserProfile`。
    pub fn get(&self, uid: i64) -> Option<&UserProfile> {
        self.users.get(&uid)
    }

    /// Java 无直接对应方法（`getUserProfile` 返回的是可变的 `UserProfile` 引用本身，
    /// Java 靠对象引用可变性天然支持修改；Rust 需要显式的 `get_mut` 才能拿到可变借用）。
    pub fn get_mut(&mut self, uid: i64) -> Option<&mut UserProfile> {
        self.users.get_mut(&uid)
    }
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::{to_btree_i64, ChronicleMarshallable};

// 对应 Java `writeMarshallable`（`SerializationUtils.marshallLongHashMap(userProfiles, bytes)`）
// 与读构造器 `UserProfileService(BytesIn)`。
impl ChronicleMarshallable for UserProfileService {
    fn chronicle_write(&self, w: &mut ChronicleWriter) {
        w.write_long_keyed_map(&self.users, |vw, v| v.chronicle_write(vw));
    }
    fn chronicle_read(r: &mut ChronicleReader) -> Result<Self, ChronicleError> {
        let users = to_btree_i64(r.read_long_keyed_map(UserProfile::chronicle_read)?);
        Ok(UserProfileService { users })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn add_empty_user_profile_succeeds_first_time() {
        let mut svc = UserProfileService::new();
        assert_eq!(svc.add_empty_user_profile(1), CommandResultCode::Success);
        let p = svc.get(1).unwrap();
        assert_eq!(p.user_status, UserStatus::Active);
        assert_eq!(p.account(1), 0);
    }

    #[test]
    fn add_empty_user_profile_rejects_duplicate_uid() {
        let mut svc = UserProfileService::new();
        assert_eq!(svc.add_empty_user_profile(1), CommandResultCode::Success);
        assert_eq!(svc.add_empty_user_profile(1), CommandResultCode::UserMgmtUserAlreadyExists);
    }

    #[test]
    fn get_or_add_suspended_creates_suspended_profile_once() {
        let mut svc = UserProfileService::new();
        {
            let p = svc.get_or_add_suspended(42);
            assert_eq!(p.user_status, UserStatus::Suspended);
            p.add_to_account(1, 10);
        }
        let p2 = svc.get_or_add_suspended(42);
        assert_eq!(p2.account(1), 10);
    }

    #[test]
    fn get_mut_allows_mutation() {
        let mut svc = UserProfileService::new();
        svc.add_empty_user_profile(1);
        svc.get_mut(1).unwrap().add_to_account(5, 100);
        assert_eq!(svc.get(1).unwrap().account(5), 100);
    }
}
