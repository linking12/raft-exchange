//! 对应 Java `exchange.core2.core.common.OrderType`。

/// 订单执行方式，`code()`/`from_code()` 对应 Java `getCode()`（lombok）/`OrderType.of(byte)`。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OrderType {
    /// Good till Cancel：一直有效直到撤单，等价于普通限价单。
    Gtc,
    /// Immediate or Cancel：带价格上限，等价于严格风控下的市价单。
    Ioc,
    /// Immediate or Cancel：带总金额上限。
    IocBudget,
    /// Fill or Kill：带价格上限，要么立即全部成交要么全部不成交。
    Fok,
    /// Fill or Kill：带总金额上限。
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
    /// 对应 Java `OrderType.of(byte)`：未知 code 返回 None（Java 抛 IllegalArgumentException）。
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
