use std::collections::BTreeMap;

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::user_profile::UserProfile;
use crate::core::common::user_status::UserStatus;

#[derive(Debug, Clone, Default)]
pub struct UserProfileService {
    pub users: BTreeMap<i64, UserProfile>,
}

impl UserProfileService {

    pub fn new() -> Self {
        Self::default()
    }

    pub fn add_empty_user_profile(&mut self, uid: i64) -> CommandResultCode {
        if self.users.contains_key(&uid) {
            return CommandResultCode::UserMgmtUserAlreadyExists;
        }
        self.users.insert(uid, UserProfile::new(uid, UserStatus::Active));
        CommandResultCode::Success
    }

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

    pub fn get_or_add_suspended(&mut self, uid: i64) -> &mut UserProfile {
        self.users
            .entry(uid)
            .or_insert_with(|| UserProfile::new(uid, UserStatus::Suspended))
    }

    pub fn get(&self, uid: i64) -> Option<&UserProfile> {
        self.users.get(&uid)
    }

    pub fn get_mut(&mut self, uid: i64) -> Option<&mut UserProfile> {
        self.users.get_mut(&uid)
    }
}

use crate::core::snapshot::chronicle_reader::{ChronicleError, ChronicleReader};
use crate::core::snapshot::chronicle_writer::ChronicleWriter;
use crate::core::snapshot::marshalling::{to_btree_i64, ChronicleMarshallable};

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
