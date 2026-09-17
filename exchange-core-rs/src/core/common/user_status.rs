//! 对应 Java `exchange.core2.core.common.UserStatus`。

/// 用户状态：Active=正常，Suspended=已挂起（挂起后拒绝除恢复外的绝大多数命令，
/// 详见 loan.md 中 `LOAN_USER_SUSPENDED` 相关判定）。`code()` 对应 Java `getCode()`（lombok），
/// Active=0/Suspended=1。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UserStatus {
    Active,
    Suspended,
}

impl UserStatus {
    pub fn code(self) -> i8 {
        match self {
            UserStatus::Active => 0,
            UserStatus::Suspended => 1,
        }
    }

    /// 对应 Java `UserStatus.of(byte)`：未知 code 直接 panic（Java 抛 IllegalArgumentException）。
    pub fn of_code(code: i8) -> Self {
        match code {
            0 => UserStatus::Active,
            1 => UserStatus::Suspended,
            c => panic!("unknown UserStatus code {c}"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn user_status_codes_match_java() {
        assert_eq!(UserStatus::Active.code(), 0);
        assert_eq!(UserStatus::Suspended.code(), 1);
    }
}
