#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MarginMode {
    Isolated,
    Cross,
}

impl MarginMode {
    pub fn code(self) -> i8 {
        match self {
            MarginMode::Isolated => 0,
            MarginMode::Cross => 1,
        }
    }

    pub fn of_code(code: i8) -> Self {
        match code {
            0 => MarginMode::Isolated,
            1 => MarginMode::Cross,
            other => panic!("unknown MarginMode code: {other}"),
        }
    }
}

impl Default for MarginMode {
    fn default() -> Self {
        MarginMode::Isolated
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_match_java() {
        assert_eq!(MarginMode::Isolated.code(), 0);
        assert_eq!(MarginMode::Cross.code(), 1);
    }

    #[test]
    fn of_code_round_trips() {
        assert_eq!(MarginMode::of_code(0), MarginMode::Isolated);
        assert_eq!(MarginMode::of_code(1), MarginMode::Cross);
    }

    #[test]
    #[should_panic]
    fn of_code_unknown_panics() {
        MarginMode::of_code(2);
    }

    #[test]
    fn default_is_isolated() {
        assert_eq!(MarginMode::default(), MarginMode::Isolated);
    }
}
