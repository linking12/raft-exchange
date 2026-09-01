//! 对应 Java: exchange.core2.core.common.OrderType。码值与 Java 严格一致。

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
}
