//! 对应 Java: exchange.core2.core.common.MarginMode。
//! `ISOLATED(0)` 默认（逐仓，自筹保证金，PnL 不外借）；`CROSS(1)`（全仓，按币种进账户级池）。

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

    /// 对应 Java `MarginMode.of(byte code)`。未知码值 panic（对应 Java
    /// `IllegalArgumentException`）。
    pub fn of_code(code: i8) -> Self {
        match code {
            0 => MarginMode::Isolated,
            1 => MarginMode::Cross,
            other => panic!("unknown MarginMode code: {other}"),
        }
    }
}

impl Default for MarginMode {
    /// 对应 Java `SymbolPositionRecord.marginMode` / `OrderCommand.marginMode` 字段初始值
    /// `MarginMode.ISOLATED`。
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
