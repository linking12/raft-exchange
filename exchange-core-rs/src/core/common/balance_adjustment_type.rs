/// 对应 Java `exchange.core2.core.common.BalanceAdjustmentType`。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BalanceAdjustmentType {
    Adjustment,
    Suspend,
}

impl BalanceAdjustmentType {
    pub fn code(self) -> i8 {
        match self {
            BalanceAdjustmentType::Adjustment => 0,
            BalanceAdjustmentType::Suspend => 1,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn balance_adjustment_type_codes_match_java() {
        assert_eq!(BalanceAdjustmentType::Adjustment.code(), 0);
        assert_eq!(BalanceAdjustmentType::Suspend.code(), 1);
    }
}
