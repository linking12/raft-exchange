/// 对应 Java `exchange.core2.core.common.cmd.OrderCommandType`（现货子集 + P4 Task 1 期货
/// `create_positions_key` 所需的 `CLOSE_POSITION`/`FORCE_LIQUIDATION` 两个变体；其余期货/借贷/
/// 清算变体本移植尚未列入）。
/// `is_non_trading()` / `is_loan()` 对照 Java 的二级 dispatch 门守分类语义。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OrderCommandType {
    PlaceOrder,
    CancelOrder,
    MoveOrder,
    ReduceOrder,
    /// 对应 Java `CLOSE_POSITION`（码 5）：纯减仓期货命令，`UserProfile::create_positions_key`
    /// 会把命中该类型的 key 翻转到对侧仓位（见 `common::user_profile`）。
    ClosePosition,
    OrderBookRequest,
    AddUser,
    BalanceAdjustment,
    BinaryDataCommand,
    /// 对应 Java `FORCE_LIQUIDATION`（码 20）：强平期货命令，`create_positions_key` 翻转逻辑
    /// 与 `ClosePosition` 相同（P6 强平扫描消费；本移植 Task 1 只搬键计算）。
    ForceLiquidation,
    Reset,
    Nop,
}

impl OrderCommandType {
    pub fn code(self) -> i8 {
        match self {
            OrderCommandType::PlaceOrder => 1,
            OrderCommandType::CancelOrder => 2,
            OrderCommandType::MoveOrder => 3,
            OrderCommandType::ReduceOrder => 4,
            OrderCommandType::ClosePosition => 5,
            OrderCommandType::OrderBookRequest => 6,
            OrderCommandType::AddUser => 10,
            OrderCommandType::BalanceAdjustment => 11,
            OrderCommandType::ForceLiquidation => 20,
            OrderCommandType::BinaryDataCommand => 91,
            OrderCommandType::Nop => 120,
            OrderCommandType::Reset => 124,
        }
    }

    /// 对应 Java `OrderCommandType.isNonTrading()`：命中即整块委托
    /// `RiskEngineCommandDispatcher.dispatch`，主 switch 只留交易/结算/引擎自身生命周期。
    /// 现货子集里命中的只有 `ADD_USER` / `BALANCE_ADJUSTMENT` / `BINARY_DATA_COMMAND`
    /// （Java 全集还含 INTERNAL_TRANSFER/MARGIN_ADJUSTMENT 等期货/借贷专用变体，本移植未列入）。
    pub fn is_non_trading(self) -> bool {
        matches!(
            self,
            OrderCommandType::AddUser
                | OrderCommandType::BalanceAdjustment
                | OrderCommandType::BinaryDataCommand
        )
    }

    /// 对应 Java `OrderCommandType.isLoan()`：本移植现货子集未包含任何 `LOAN_*`/`POOL_*` 变体，
    /// 因此恒为 `false`（并非分类简化，而是这些变体尚未移植）。
    pub fn is_loan(self) -> bool {
        false
    }
}

impl Default for OrderCommandType {
    /// `NOP`（码 120，非交易）：与 Java 侧无显式默认值对应，选取语义上最中性的变体，
    /// 仅用于满足 `OrderCommand` 的 `#[derive(Default)]`。
    fn default() -> Self {
        OrderCommandType::Nop
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn order_command_type_codes_match_java() {
        assert_eq!(OrderCommandType::PlaceOrder.code(), 1);
        assert_eq!(OrderCommandType::CancelOrder.code(), 2);
        assert_eq!(OrderCommandType::MoveOrder.code(), 3);
        assert_eq!(OrderCommandType::ReduceOrder.code(), 4);
        assert_eq!(OrderCommandType::ClosePosition.code(), 5);
        assert_eq!(OrderCommandType::OrderBookRequest.code(), 6);
        assert_eq!(OrderCommandType::AddUser.code(), 10);
        assert_eq!(OrderCommandType::BalanceAdjustment.code(), 11);
        assert_eq!(OrderCommandType::ForceLiquidation.code(), 20);
        assert_eq!(OrderCommandType::BinaryDataCommand.code(), 91);
        assert_eq!(OrderCommandType::Nop.code(), 120);
        assert_eq!(OrderCommandType::Reset.code(), 124);
    }

    #[test]
    fn order_command_type_is_non_trading_classification_matches_java() {
        // 非交易门守：ADD_USER / BALANCE_ADJUSTMENT / BINARY_DATA_COMMAND 命中。
        assert!(OrderCommandType::AddUser.is_non_trading());
        assert!(OrderCommandType::BalanceAdjustment.is_non_trading());
        assert!(OrderCommandType::BinaryDataCommand.is_non_trading());
        // 主 switch 交易 / 撮合直落命令：不命中。
        assert!(!OrderCommandType::PlaceOrder.is_non_trading());
        assert!(!OrderCommandType::CancelOrder.is_non_trading());
        assert!(!OrderCommandType::MoveOrder.is_non_trading());
        assert!(!OrderCommandType::ReduceOrder.is_non_trading());
        assert!(!OrderCommandType::OrderBookRequest.is_non_trading());
        assert!(!OrderCommandType::Reset.is_non_trading());
        assert!(!OrderCommandType::Nop.is_non_trading());
    }

    #[test]
    fn order_command_type_is_loan_always_false_in_spot_subset() {
        // 本移植未含 loan 命令变体（Task 2 现货子集），is_loan 恒 false。
        assert!(!OrderCommandType::PlaceOrder.is_loan());
        assert!(!OrderCommandType::BalanceAdjustment.is_loan());
    }

    #[test]
    fn order_command_type_default_is_nop() {
        assert_eq!(OrderCommandType::default(), OrderCommandType::Nop);
    }
}
