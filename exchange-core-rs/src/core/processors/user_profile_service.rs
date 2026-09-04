//! 对应 Java `UserProfileService`（现货子集：注册表 + addEmptyUserProfile/getUserProfile/getUserProfileOrAddSuspended）。
use std::collections::BTreeMap;

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::user_profile::UserProfile;
use crate::core::common::user_status::UserStatus;

/// 对应 Java `UserProfileService`（现货子集：注册表 + addEmptyUserProfile/getUserProfile/getUserProfileOrAddSuspended）。
#[derive(Debug, Clone, Default, serde::Serialize, serde::Deserialize)]
pub struct UserProfileService {
    pub users: BTreeMap<i64, UserProfile>,
}

impl UserProfileService {
    pub fn new() -> Self {
        Self::default()
    }

    /// 对应 Java `UserProfileService.addEmptyUserProfile`：新建 ACTIVE profile，重复 uid → UserMgmtUserAlreadyExists。
    pub fn add_empty_user_profile(&mut self, uid: i64) -> CommandResultCode {
        if self.users.contains_key(&uid) {
            return CommandResultCode::UserMgmtUserAlreadyExists;
        }
        self.users.insert(uid, UserProfile::new(uid, UserStatus::Active));
        CommandResultCode::Success
    }

    pub fn get(&self, uid: i64) -> Option<&UserProfile> {
        self.users.get(&uid)
    }

    pub fn get_mut(&mut self, uid: i64) -> Option<&mut UserProfile> {
        self.users.get_mut(&uid)
    }

    /// 对应 Java `getUserProfileOrAddSuspended`：不存在则以 SUSPENDED 状态创建（他 shard 用户首次引用时的兜底路径）。
    pub fn get_or_add_suspended(&mut self, uid: i64) -> &mut UserProfile {
        self.users
            .entry(uid)
            .or_insert_with(|| UserProfile::new(uid, UserStatus::Suspended))
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
        // 第二次调用不重建（余额保留）。
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
