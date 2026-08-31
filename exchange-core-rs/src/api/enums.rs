//! 对应 Java: exchange.core2.core.common.{OrderType, OrderAction, MatcherEventType,
//! SymbolType, UserStatus, BalanceAdjustmentType} 及 core.common.cmd.{CommandResultCode,
//! OrderCommandType}。码值与 Java 严格一致。

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
    AuthInvalidUser,        // -1001
    InvalidSymbol,          // -1201
    RiskNsf,                // -2001
    RiskInvalidReserveBidPrice, // -2002
    RiskAskPriceLowerThanFee,   // -2003
    MatchingUnknownOrderId, // -3002
    MatchingUnsupportedCommand, // -3004
    MatchingReduceFailedWrongSize, // -3051（Task 6: reduceOrder 请求量 <= 0）
    UserMgmtUserAlreadyExists, // -4001
    UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame, // -4101
    UserMgmtAccountBalanceAdjustmentNsf,                // -4103
    SymbolMgmtSymbolAlreadyExists, // -5001（SymbolSpecificationProvider.addSymbol dup 拒绝）
}

impl CommandResultCode {
    pub fn code(self) -> i32 {
        match self {
            CommandResultCode::ValidForMatchingEngine => 1,
            CommandResultCode::Success => 100,
            CommandResultCode::AuthInvalidUser => -1001,
            CommandResultCode::InvalidSymbol => -1201,
            CommandResultCode::RiskNsf => -2001,
            CommandResultCode::RiskInvalidReserveBidPrice => -2002,
            CommandResultCode::RiskAskPriceLowerThanFee => -2003,
            CommandResultCode::MatchingUnknownOrderId => -3002,
            CommandResultCode::MatchingUnsupportedCommand => -3004,
            CommandResultCode::MatchingReduceFailedWrongSize => -3051,
            CommandResultCode::UserMgmtUserAlreadyExists => -4001,
            CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame => -4101,
            CommandResultCode::UserMgmtAccountBalanceAdjustmentNsf => -4103,
            CommandResultCode::SymbolMgmtSymbolAlreadyExists => -5001,
        }
    }
}

/// 对应 Java `exchange.core2.core.common.SymbolType`。现货移植只用 `CurrencyExchangePair`；
/// 其余变体（期货/期权）仅保留码值以便未来分支/序列化对齐，本期无业务逻辑。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SymbolType {
    CurrencyExchangePair,
    FuturesContractPerpetual,
    FuturesContractDelivery,
    Option,
}

impl SymbolType {
    pub fn code(self) -> i8 {
        match self {
            SymbolType::CurrencyExchangePair => 0,
            SymbolType::FuturesContractPerpetual => 1,
            SymbolType::FuturesContractDelivery => 2,
            SymbolType::Option => 3,
        }
    }
}

/// 对应 Java `exchange.core2.core.common.UserStatus`。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UserStatus {
    Active,
    Suspended,
}

impl UserStatus {
    pub fn code(self) -> i8 {
        match self {
            UserStatus::Active => 0,
            UserStatus::Suspended => 1,
        }
    }
}

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

/// 对应 Java `exchange.core2.core.common.cmd.OrderCommandType`（现货相关子集）。
/// `is_non_trading()` / `is_loan()` 对照 Java 的二级 dispatch 门守分类语义。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OrderCommandType {
    PlaceOrder,
    CancelOrder,
    MoveOrder,
    ReduceOrder,
    OrderBookRequest,
    AddUser,
    BalanceAdjustment,
    BinaryDataCommand,
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
            OrderCommandType::OrderBookRequest => 6,
            OrderCommandType::AddUser => 10,
            OrderCommandType::BalanceAdjustment => 11,
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

    // ---- Task 2 RED: SymbolType / UserStatus / BalanceAdjustmentType 码值 ----

    #[test]
    fn symbol_type_codes_match_java() {
        assert_eq!(SymbolType::CurrencyExchangePair.code(), 0);
        assert_eq!(SymbolType::FuturesContractPerpetual.code(), 1);
        assert_eq!(SymbolType::FuturesContractDelivery.code(), 2);
        assert_eq!(SymbolType::Option.code(), 3);
    }

    #[test]
    fn user_status_codes_match_java() {
        assert_eq!(UserStatus::Active.code(), 0);
        assert_eq!(UserStatus::Suspended.code(), 1);
    }

    #[test]
    fn balance_adjustment_type_codes_match_java() {
        assert_eq!(BalanceAdjustmentType::Adjustment.code(), 0);
        assert_eq!(BalanceAdjustmentType::Suspend.code(), 1);
    }

    // ---- Task 2 RED: OrderCommandType 码值 + is_non_trading/is_loan 分类 ----

    #[test]
    fn order_command_type_codes_match_java() {
        assert_eq!(OrderCommandType::PlaceOrder.code(), 1);
        assert_eq!(OrderCommandType::CancelOrder.code(), 2);
        assert_eq!(OrderCommandType::MoveOrder.code(), 3);
        assert_eq!(OrderCommandType::ReduceOrder.code(), 4);
        assert_eq!(OrderCommandType::OrderBookRequest.code(), 6);
        assert_eq!(OrderCommandType::AddUser.code(), 10);
        assert_eq!(OrderCommandType::BalanceAdjustment.code(), 11);
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

    // ---- Task 2 RED: CommandResultCode 现货错误码 ----

    #[test]
    fn spot_command_result_codes_match_java() {
        assert_eq!(CommandResultCode::AuthInvalidUser.code(), -1001);
        assert_eq!(CommandResultCode::InvalidSymbol.code(), -1201);
        assert_eq!(CommandResultCode::RiskNsf.code(), -2001);
        assert_eq!(CommandResultCode::RiskInvalidReserveBidPrice.code(), -2002);
        assert_eq!(CommandResultCode::RiskAskPriceLowerThanFee.code(), -2003);
        assert_eq!(CommandResultCode::UserMgmtUserAlreadyExists.code(), -4001);
        assert_eq!(CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame.code(), -4101);
        assert_eq!(CommandResultCode::UserMgmtAccountBalanceAdjustmentNsf.code(), -4103);
        assert_eq!(CommandResultCode::SymbolMgmtSymbolAlreadyExists.code(), -5001);
    }

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
