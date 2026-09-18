#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OrderCommandType {
    PlaceOrder,
    CancelOrder,
    MoveOrder,
    ReduceOrder,
    ClosePosition,
    OrderBookRequest,
    AddUser,
    BalanceAdjustment,
    BinaryDataCommand,
    ForceLiquidation,
    LeverageAdjustment,
    MarginAdjustment,
    MarkpriceAdjustment,
    LoanCreate,
    LoanRepay,
    LoanAddCollateral,
    LoanReleaseCollateral,
    LoanForceLiquidate,
    LoanCrossAddCollateral,
    LoanCrossWithdrawCollateral,
    LoanCrossBorrow,
    LoanCrossRepay,
    LoanCrossForceLiquidate,
    PoolDeposit,
    PoolWithdraw,
    LoanIfDeposit,
    LoanIfWithdraw,
    RepriceLoanRates,
    InternalTransfer,
    SettleFundingfees,
    SystemLiquidationNotify,
    IfTakeover,
    AutoDeleveraging,
    IfDeposit,
    IfWithdraw,
    LiquidationScan,
    SettlePnl,
    SuspendUser,
    ResumeUser,
    PositionModeAdjustment,
    ResetFee,
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
            OrderCommandType::LeverageAdjustment => 21,
            OrderCommandType::MarginAdjustment => 23,
            OrderCommandType::MarkpriceAdjustment => 24,
            OrderCommandType::LoanCreate => 50,
            OrderCommandType::LoanRepay => 51,
            OrderCommandType::LoanAddCollateral => 52,
            OrderCommandType::LoanReleaseCollateral => 53,
            OrderCommandType::LoanForceLiquidate => 54,
            OrderCommandType::LoanCrossAddCollateral => 55,
            OrderCommandType::LoanCrossWithdrawCollateral => 56,
            OrderCommandType::LoanCrossBorrow => 57,
            OrderCommandType::LoanCrossRepay => 58,
            OrderCommandType::LoanCrossForceLiquidate => 59,
            OrderCommandType::PoolDeposit => 60,
            OrderCommandType::PoolWithdraw => 61,
            OrderCommandType::RepriceLoanRates => 63,
            OrderCommandType::LoanIfDeposit => 64,
            OrderCommandType::LoanIfWithdraw => 65,
            OrderCommandType::BinaryDataCommand => 91,
            OrderCommandType::Nop => 120,
            OrderCommandType::Reset => 124,
            OrderCommandType::InternalTransfer => 14,
            OrderCommandType::SettleFundingfees => 25,
            OrderCommandType::SystemLiquidationNotify => 31,
            OrderCommandType::IfTakeover => 40,
            OrderCommandType::AutoDeleveraging => 41,
            OrderCommandType::IfDeposit => 42,
            OrderCommandType::IfWithdraw => 43,
            OrderCommandType::LiquidationScan => 44,
            OrderCommandType::SettlePnl => 26,
            OrderCommandType::SuspendUser => 12,
            OrderCommandType::ResumeUser => 13,
            OrderCommandType::PositionModeAdjustment => 22,
            OrderCommandType::ResetFee => 27,
        }
    }

    pub fn from_code(c: i8) -> Self {
        match c {
            1 => OrderCommandType::PlaceOrder,
            2 => OrderCommandType::CancelOrder,
            3 => OrderCommandType::MoveOrder,
            4 => OrderCommandType::ReduceOrder,
            5 => OrderCommandType::ClosePosition,
            6 => OrderCommandType::OrderBookRequest,
            10 => OrderCommandType::AddUser,
            11 => OrderCommandType::BalanceAdjustment,
            12 => OrderCommandType::SuspendUser,
            13 => OrderCommandType::ResumeUser,
            14 => OrderCommandType::InternalTransfer,
            20 => OrderCommandType::ForceLiquidation,
            21 => OrderCommandType::LeverageAdjustment,
            22 => OrderCommandType::PositionModeAdjustment,
            23 => OrderCommandType::MarginAdjustment,
            24 => OrderCommandType::MarkpriceAdjustment,
            25 => OrderCommandType::SettleFundingfees,
            26 => OrderCommandType::SettlePnl,
            27 => OrderCommandType::ResetFee,
            31 => OrderCommandType::SystemLiquidationNotify,
            40 => OrderCommandType::IfTakeover,
            41 => OrderCommandType::AutoDeleveraging,
            42 => OrderCommandType::IfDeposit,
            43 => OrderCommandType::IfWithdraw,
            44 => OrderCommandType::LiquidationScan,
            50 => OrderCommandType::LoanCreate,
            51 => OrderCommandType::LoanRepay,
            52 => OrderCommandType::LoanAddCollateral,
            53 => OrderCommandType::LoanReleaseCollateral,
            54 => OrderCommandType::LoanForceLiquidate,
            55 => OrderCommandType::LoanCrossAddCollateral,
            56 => OrderCommandType::LoanCrossWithdrawCollateral,
            57 => OrderCommandType::LoanCrossBorrow,
            58 => OrderCommandType::LoanCrossRepay,
            59 => OrderCommandType::LoanCrossForceLiquidate,
            60 => OrderCommandType::PoolDeposit,
            61 => OrderCommandType::PoolWithdraw,
            63 => OrderCommandType::RepriceLoanRates,
            64 => OrderCommandType::LoanIfDeposit,
            65 => OrderCommandType::LoanIfWithdraw,
            91 => OrderCommandType::BinaryDataCommand,
            120 => OrderCommandType::Nop,
            124 => OrderCommandType::Reset,
            other => panic!("unknown OrderCommandType code {other}"),
        }
    }

    pub fn is_non_trading(self) -> bool {
        matches!(
            self,
            OrderCommandType::AddUser
                | OrderCommandType::BalanceAdjustment
                | OrderCommandType::BinaryDataCommand
                | OrderCommandType::LeverageAdjustment
                | OrderCommandType::MarginAdjustment
                | OrderCommandType::MarkpriceAdjustment
                | OrderCommandType::RepriceLoanRates
                | OrderCommandType::InternalTransfer
                | OrderCommandType::IfDeposit
                | OrderCommandType::IfWithdraw
                | OrderCommandType::SettlePnl
                | OrderCommandType::SuspendUser
                | OrderCommandType::ResumeUser
                | OrderCommandType::PositionModeAdjustment
                | OrderCommandType::ResetFee
                | OrderCommandType::SystemLiquidationNotify
        )
    }

    pub fn is_loan(self) -> bool {
        matches!(
            self,
            OrderCommandType::LoanCreate
                | OrderCommandType::LoanRepay
                | OrderCommandType::LoanAddCollateral
                | OrderCommandType::LoanReleaseCollateral
                | OrderCommandType::LoanForceLiquidate
                | OrderCommandType::LoanCrossAddCollateral
                | OrderCommandType::LoanCrossWithdrawCollateral
                | OrderCommandType::LoanCrossBorrow
                | OrderCommandType::LoanCrossRepay
                | OrderCommandType::LoanCrossForceLiquidate
                | OrderCommandType::PoolDeposit
                | OrderCommandType::PoolWithdraw
                | OrderCommandType::LoanIfDeposit
                | OrderCommandType::LoanIfWithdraw
        )
    }
}

impl Default for OrderCommandType {
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
        assert_eq!(OrderCommandType::LeverageAdjustment.code(), 21);
        assert_eq!(OrderCommandType::MarginAdjustment.code(), 23);
        assert_eq!(OrderCommandType::MarkpriceAdjustment.code(), 24);
        assert_eq!(OrderCommandType::LoanCreate.code(), 50);
        assert_eq!(OrderCommandType::LoanRepay.code(), 51);
        assert_eq!(OrderCommandType::LoanAddCollateral.code(), 52);
        assert_eq!(OrderCommandType::LoanReleaseCollateral.code(), 53);
        assert_eq!(OrderCommandType::LoanForceLiquidate.code(), 54);
        assert_eq!(OrderCommandType::LoanCrossAddCollateral.code(), 55);
        assert_eq!(OrderCommandType::LoanCrossWithdrawCollateral.code(), 56);
        assert_eq!(OrderCommandType::LoanCrossBorrow.code(), 57);
        assert_eq!(OrderCommandType::LoanCrossRepay.code(), 58);
        assert_eq!(OrderCommandType::LoanCrossForceLiquidate.code(), 59);
        assert_eq!(OrderCommandType::PoolDeposit.code(), 60);
        assert_eq!(OrderCommandType::PoolWithdraw.code(), 61);
        assert_eq!(OrderCommandType::RepriceLoanRates.code(), 63);
        assert_eq!(OrderCommandType::LoanIfDeposit.code(), 64);
        assert_eq!(OrderCommandType::LoanIfWithdraw.code(), 65);
        assert_eq!(OrderCommandType::BinaryDataCommand.code(), 91);
        assert_eq!(OrderCommandType::Nop.code(), 120);
        assert_eq!(OrderCommandType::Reset.code(), 124);
    }

    #[test]
    fn order_command_type_is_non_trading_classification_matches_java() {
        assert!(OrderCommandType::AddUser.is_non_trading());
        assert!(OrderCommandType::BalanceAdjustment.is_non_trading());
        assert!(OrderCommandType::BinaryDataCommand.is_non_trading());
        assert!(OrderCommandType::LeverageAdjustment.is_non_trading());
        assert!(OrderCommandType::MarginAdjustment.is_non_trading());
        assert!(OrderCommandType::MarkpriceAdjustment.is_non_trading());
        assert!(OrderCommandType::RepriceLoanRates.is_non_trading());
        assert!(!OrderCommandType::PlaceOrder.is_non_trading());
        assert!(!OrderCommandType::CancelOrder.is_non_trading());
        assert!(!OrderCommandType::MoveOrder.is_non_trading());
        assert!(!OrderCommandType::ReduceOrder.is_non_trading());
        assert!(!OrderCommandType::OrderBookRequest.is_non_trading());
        assert!(!OrderCommandType::Reset.is_non_trading());
        assert!(!OrderCommandType::Nop.is_non_trading());
        assert!(!OrderCommandType::LoanCreate.is_non_trading());
    }

    #[test]
    fn order_command_type_is_loan_covers_exactly_fourteen_codes() {
        let loan_codes = [
            OrderCommandType::LoanCreate,
            OrderCommandType::LoanRepay,
            OrderCommandType::LoanAddCollateral,
            OrderCommandType::LoanReleaseCollateral,
            OrderCommandType::LoanForceLiquidate,
            OrderCommandType::LoanCrossAddCollateral,
            OrderCommandType::LoanCrossWithdrawCollateral,
            OrderCommandType::LoanCrossBorrow,
            OrderCommandType::LoanCrossRepay,
            OrderCommandType::LoanCrossForceLiquidate,
            OrderCommandType::PoolDeposit,
            OrderCommandType::PoolWithdraw,
            OrderCommandType::LoanIfDeposit,
            OrderCommandType::LoanIfWithdraw,
        ];
        assert_eq!(loan_codes.len(), 14);
        for code in loan_codes {
            assert!(code.is_loan(), "{code:?} should be is_loan()");
        }

        assert!(!OrderCommandType::RepriceLoanRates.is_loan());
        assert!(!OrderCommandType::PlaceOrder.is_loan());
        assert!(!OrderCommandType::BalanceAdjustment.is_loan());
        assert!(!OrderCommandType::AddUser.is_loan());
        assert!(!OrderCommandType::MarkpriceAdjustment.is_loan());
    }

    #[test]
    fn order_command_type_default_is_nop() {
        assert_eq!(OrderCommandType::default(), OrderCommandType::Nop);
    }

    #[test]
    fn p6_new_codes_are_internally_distinct_and_match_java_where_unconflicted() {
        assert_eq!(OrderCommandType::InternalTransfer.code(), 14);
        assert_eq!(OrderCommandType::SettleFundingfees.code(), 25);
        assert_eq!(OrderCommandType::SystemLiquidationNotify.code(), 31);
        assert_eq!(OrderCommandType::IfTakeover.code(), 40);
        assert_eq!(OrderCommandType::AutoDeleveraging.code(), 41);
        assert_eq!(OrderCommandType::IfDeposit.code(), 42);
        assert_eq!(OrderCommandType::IfWithdraw.code(), 43);
        assert_eq!(OrderCommandType::LiquidationScan.code(), 44);
        assert_ne!(OrderCommandType::LiquidationScan.code(), OrderCommandType::LoanIfDeposit.code());

        let all = [
            OrderCommandType::PlaceOrder,
            OrderCommandType::CancelOrder,
            OrderCommandType::MoveOrder,
            OrderCommandType::ReduceOrder,
            OrderCommandType::ClosePosition,
            OrderCommandType::OrderBookRequest,
            OrderCommandType::AddUser,
            OrderCommandType::BalanceAdjustment,
            OrderCommandType::BinaryDataCommand,
            OrderCommandType::ForceLiquidation,
            OrderCommandType::LeverageAdjustment,
            OrderCommandType::MarginAdjustment,
            OrderCommandType::MarkpriceAdjustment,
            OrderCommandType::LoanCreate,
            OrderCommandType::LoanRepay,
            OrderCommandType::LoanAddCollateral,
            OrderCommandType::LoanReleaseCollateral,
            OrderCommandType::LoanForceLiquidate,
            OrderCommandType::LoanCrossAddCollateral,
            OrderCommandType::LoanCrossWithdrawCollateral,
            OrderCommandType::LoanCrossBorrow,
            OrderCommandType::LoanCrossRepay,
            OrderCommandType::LoanCrossForceLiquidate,
            OrderCommandType::PoolDeposit,
            OrderCommandType::PoolWithdraw,
            OrderCommandType::RepriceLoanRates,
            OrderCommandType::LoanIfDeposit,
            OrderCommandType::LoanIfWithdraw,
            OrderCommandType::InternalTransfer,
            OrderCommandType::SettleFundingfees,
            OrderCommandType::SystemLiquidationNotify,
            OrderCommandType::IfTakeover,
            OrderCommandType::AutoDeleveraging,
            OrderCommandType::IfDeposit,
            OrderCommandType::IfWithdraw,
            OrderCommandType::LiquidationScan,
            OrderCommandType::Reset,
            OrderCommandType::Nop,
        ];
        let mut codes: Vec<i8> = all.iter().map(|t| t.code()).collect();
        let n = codes.len();
        codes.sort_unstable();
        codes.dedup();
        assert_eq!(codes.len(), n, "OrderCommandType codes must be pairwise distinct (Ruling P6-D)");
    }

    #[test]
    fn p6_internal_transfer_and_futures_if_deposit_withdraw_are_non_trading() {
        assert!(OrderCommandType::InternalTransfer.is_non_trading());
        assert!(OrderCommandType::IfDeposit.is_non_trading());
        assert!(OrderCommandType::IfWithdraw.is_non_trading());
    }

    #[test]
    fn p6_liquidation_state_machine_and_scan_codes_stay_in_main_switch() {
        assert!(!OrderCommandType::IfTakeover.is_non_trading());
        assert!(!OrderCommandType::AutoDeleveraging.is_non_trading());
        assert!(!OrderCommandType::SettleFundingfees.is_non_trading());
        assert!(!OrderCommandType::ForceLiquidation.is_non_trading());
        assert!(!OrderCommandType::LiquidationScan.is_non_trading());
        assert!(OrderCommandType::SystemLiquidationNotify.is_non_trading());
    }

    #[test]
    fn p6_new_codes_are_not_loan_codes() {
        assert!(!OrderCommandType::IfDeposit.is_loan());
        assert!(!OrderCommandType::IfWithdraw.is_loan());
        assert!(!OrderCommandType::InternalTransfer.is_loan());
        assert!(!OrderCommandType::IfTakeover.is_loan());
        assert!(!OrderCommandType::AutoDeleveraging.is_loan());
        assert!(!OrderCommandType::SettleFundingfees.is_loan());
        assert!(!OrderCommandType::LiquidationScan.is_loan());
        assert!(!OrderCommandType::SystemLiquidationNotify.is_loan());
    }
}
