//! 对应 Java: exchange.core2.core.common.OrderAction。码值与 Java 严格一致。

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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn order_action_opposite() {
        assert_eq!(OrderAction::Ask.opposite(), OrderAction::Bid);
        assert_eq!(OrderAction::Bid.opposite(), OrderAction::Ask);
        assert_eq!(OrderAction::Bid.code(), 1);
    }
}
