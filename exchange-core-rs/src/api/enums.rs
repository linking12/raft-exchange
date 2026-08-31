//! 对应 Java: exchange.core2.core.common.{OrderType, OrderAction, MatcherEventType}
//! 及 core.common.cmd.CommandResultCode。码值与 Java 严格一致。

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OrderType {
    Gtc,
    Ioc,
    IocBudget,
    Fok,
    FokBudget,
}

impl OrderType {
    pub fn code(self) -> i8 {
        match self {
            OrderType::Gtc => 0,
            OrderType::Ioc => 1,
            OrderType::IocBudget => 2,
            OrderType::Fok => 3,
            OrderType::FokBudget => 4,
        }
    }
    pub fn from_code(c: i8) -> Option<Self> {
        Some(match c {
            0 => OrderType::Gtc,
            1 => OrderType::Ioc,
            2 => OrderType::IocBudget,
            3 => OrderType::Fok,
            4 => OrderType::FokBudget,
            _ => return None,
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OrderAction {
    Ask,
    Bid,
}

impl OrderAction {
    pub fn code(self) -> i8 {
        match self {
            OrderAction::Ask => 0,
            OrderAction::Bid => 1,
        }
    }
    pub fn from_code(c: i8) -> Option<Self> {
        match c {
            0 => Some(OrderAction::Ask),
            1 => Some(OrderAction::Bid),
            _ => None,
        }
    }
    pub fn opposite(self) -> Self {
        match self {
            OrderAction::Ask => OrderAction::Bid,
            OrderAction::Bid => OrderAction::Ask,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MatcherEventType {
    Trade,
    Reject,
    Reduce,
    BinaryEvent,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CommandResultCode {
    ValidForMatchingEngine, // 1
    Success,                // 100
    MatchingUnknownOrderId, // -3002
    MatchingUnsupportedCommand, // -3004
    MatchingReduceFailedWrongSize, // -3051（Task 6: reduceOrder 请求量 <= 0）
}

impl CommandResultCode {
    pub fn code(self) -> i32 {
        match self {
            CommandResultCode::ValidForMatchingEngine => 1,
            CommandResultCode::Success => 100,
            CommandResultCode::MatchingUnknownOrderId => -3002,
            CommandResultCode::MatchingUnsupportedCommand => -3004,
            CommandResultCode::MatchingReduceFailedWrongSize => -3051,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn order_type_codes_match_java() {
        assert_eq!(OrderType::Gtc.code(), 0);
        assert_eq!(OrderType::Ioc.code(), 1);
        assert_eq!(OrderType::IocBudget.code(), 2);
        assert_eq!(OrderType::Fok.code(), 3);
        assert_eq!(OrderType::FokBudget.code(), 4);
        assert_eq!(OrderType::from_code(3), Some(OrderType::Fok));
        assert_eq!(OrderType::from_code(9), None);
    }

    #[test]
    fn order_action_opposite() {
        assert_eq!(OrderAction::Ask.opposite(), OrderAction::Bid);
        assert_eq!(OrderAction::Bid.opposite(), OrderAction::Ask);
        assert_eq!(OrderAction::Bid.code(), 1);
    }
}
