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
