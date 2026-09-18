use crate::core::common::order_action::OrderAction;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PositionDirection {
    Long,
    Short,
    Empty,
}

impl PositionDirection {

    pub fn of_code(code: i8) -> Self {
        match code {
            1 => PositionDirection::Long,
            -1 => PositionDirection::Short,
            0 => PositionDirection::Empty,
            other => panic!("unknown PositionDirection code: {other}"),
        }
    }

    pub fn of_action(action: OrderAction) -> Self {
        if action == OrderAction::Bid {
            PositionDirection::Long
        } else {
            PositionDirection::Short
        }
    }

    pub fn multiplier(self) -> i32 {
        match self {
            PositionDirection::Long => 1,
            PositionDirection::Short => -1,
            PositionDirection::Empty => 0,
        }
    }

    pub fn code(self) -> i8 {
        self.multiplier() as i8
    }

    pub fn is_opposite_to_action(self, action: OrderAction) -> bool {
        (self == PositionDirection::Long && action == OrderAction::Ask)
            || (self == PositionDirection::Short && action == OrderAction::Bid)
    }

    pub fn is_same_as_action(self, action: OrderAction) -> bool {
        (self == PositionDirection::Long && action == OrderAction::Bid)
            || (self == PositionDirection::Short && action == OrderAction::Ask)
    }
}

impl Default for PositionDirection {
    fn default() -> Self {
        PositionDirection::Empty
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn multiplier_and_code_match_java() {
        assert_eq!(PositionDirection::Long.multiplier(), 1);
        assert_eq!(PositionDirection::Short.multiplier(), -1);
        assert_eq!(PositionDirection::Empty.multiplier(), 0);
        assert_eq!(PositionDirection::Long.code(), 1);
        assert_eq!(PositionDirection::Short.code(), -1);
        assert_eq!(PositionDirection::Empty.code(), 0);
    }

    #[test]
    fn of_code_round_trips() {
        assert_eq!(PositionDirection::of_code(1), PositionDirection::Long);
        assert_eq!(PositionDirection::of_code(-1), PositionDirection::Short);
        assert_eq!(PositionDirection::of_code(0), PositionDirection::Empty);
    }

    #[test]
    #[should_panic]
    fn of_code_unknown_panics() {
        PositionDirection::of_code(42);
    }

    #[test]
    fn of_action_maps_bid_to_long_ask_to_short() {
        assert_eq!(PositionDirection::of_action(OrderAction::Bid), PositionDirection::Long);
        assert_eq!(PositionDirection::of_action(OrderAction::Ask), PositionDirection::Short);
    }

    #[test]
    fn is_opposite_to_action_matches_java() {
        assert!(PositionDirection::Long.is_opposite_to_action(OrderAction::Ask));
        assert!(!PositionDirection::Long.is_opposite_to_action(OrderAction::Bid));
        assert!(PositionDirection::Short.is_opposite_to_action(OrderAction::Bid));
        assert!(!PositionDirection::Short.is_opposite_to_action(OrderAction::Ask));
        assert!(!PositionDirection::Empty.is_opposite_to_action(OrderAction::Bid));
        assert!(!PositionDirection::Empty.is_opposite_to_action(OrderAction::Ask));
    }

    #[test]
    fn is_same_as_action_matches_java() {
        assert!(PositionDirection::Long.is_same_as_action(OrderAction::Bid));
        assert!(!PositionDirection::Long.is_same_as_action(OrderAction::Ask));
        assert!(PositionDirection::Short.is_same_as_action(OrderAction::Ask));
        assert!(!PositionDirection::Short.is_same_as_action(OrderAction::Bid));
    }

    #[test]
    fn default_is_empty() {
        assert_eq!(PositionDirection::default(), PositionDirection::Empty);
    }
}
