/// 对应 Java `exchange.core2.core.common.UserStatus`。
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

    /// code() 反向映射(快照反序列化)。
    pub fn of_code(code: i8) -> Self {
        match code {
            0 => UserStatus::Active,
            1 => UserStatus::Suspended,
            c => panic!("未知 UserStatus code {c}"),
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
