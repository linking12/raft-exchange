//! 对应 Java `exchange.core2.core.common.BalanceAdjustmentType`。余额调整命令的类别。
//! **code 值是持久化/协议契约**:0=Adjustment、1=Suspend,不可改。

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BalanceAdjustmentType {
    /// 对应 Java `ADJUSTMENT`(code 0):普通增减余额。
    Adjustment,
    /// 对应 Java `SUSPEND`(code 1):冻结/挂起。
    Suspend,
}

impl BalanceAdjustmentType {
    /// 对应 Java `getCode()`(Lombok `@Getter`);返回持久化用的 byte code。
    pub fn code(self) -> i8 {
        match self {
            BalanceAdjustmentType::Adjustment => 0,
            BalanceAdjustmentType::Suspend => 1,
        }
    }

    /// 对应 Java `of(byte)`。差异:Java 对未知 code 抛 `IllegalArgumentException`,
    /// 这里把非 1 的一切(含未知值)兜底为 `Adjustment`。
    pub fn of(code: i8) -> Self {
        match code {
            1 => BalanceAdjustmentType::Suspend,
            _ => BalanceAdjustmentType::Adjustment,
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
