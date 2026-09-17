//! 对应 Java `exchange.core2.core.common.PositionMode`。

/// 持仓模式：OneWay=单向持仓，Hedge=双向持仓。`code()` 对应 Java `getCode()`（lombok），
/// OneWay=0/Hedge=1。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PositionMode {
    OneWay,
    Hedge,
}

impl PositionMode {
    pub fn code(self) -> i8 {
        match self {
            PositionMode::OneWay => 0,
            PositionMode::Hedge => 1,
        }
    }

    /// 对应 Java `PositionMode.of(byte)`：未知 code 直接 panic（Java 抛 IllegalArgumentException）。
    pub fn of_code(code: i8) -> Self {
        match code {
            0 => PositionMode::OneWay,
            1 => PositionMode::Hedge,
            other => panic!("unknown PositionMode code: {other}"),
        }
    }
}

/// 对应 Java `UserProfile` 构造函数里 `this.positionMode = PositionMode.ONEWAY;`。
impl Default for PositionMode {
    fn default() -> Self {
        PositionMode::OneWay
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn codes_match_java() {
        assert_eq!(PositionMode::OneWay.code(), 0);
        assert_eq!(PositionMode::Hedge.code(), 1);
    }

    #[test]
    fn of_code_round_trips() {
        assert_eq!(PositionMode::of_code(0), PositionMode::OneWay);
        assert_eq!(PositionMode::of_code(1), PositionMode::Hedge);
    }

    #[test]
    #[should_panic]
    fn of_code_unknown_panics() {
        PositionMode::of_code(2);
    }

    #[test]
    fn default_is_oneway() {
        assert_eq!(PositionMode::default(), PositionMode::OneWay);
    }
}
