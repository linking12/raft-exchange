#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CommandResultCode {
    ValidForMatchingEngine,
    Success,
    AuthInvalidUser,
    InvalidSymbol,
    UnsupportedSymbolType,
    RiskNsf,
    RiskInvalidReserveBidPrice,
    RiskAskPriceLowerThanFee,
    RiskMarginTradingDisabled,
    RiskInvalidAmount,
    RiskInvalidLeverage,
    RiskLeverageMismatch,
    RiskMarginModeMismatch,
    RiskMarginPositionNotExists,
    RiskMarkpriceNotAvailable,
    RiskIfInsufficient,
    MatchingUnknownOrderId,
    MatchingUnsupportedCommand,
    MatchingInvalidOrderBookId,
    MatchingMoveFailedPriceOverRiskLimit,
    MatchingReduceFailedWrongSize,
    UserMgmtUserAlreadyExists,
    UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame,
    UserMgmtAccountBalanceAdjustmentNsf,
    SymbolMgmtSymbolAlreadyExists,
    InternalTransferInvalidSelf,
    LoanNotEnabled,
    LoanAlreadyExists,
    LoanNotFound,
    LoanUidMismatch,
    LoanUserSuspended,
    LoanInvalidAmount,
    LoanPrincipalExceedsLimit,
    LoanMarkpriceNotReady,
    LoanLtvTooHigh,
    LoanLtvTooHighAfterBorrow,
    LoanLtvTooHighAfterRelease,
    LoanCrossLtvTooHighAfterWithdraw,
    LoanCollateralInsufficient,
    LoanCollateralNotAllowed,
    LoanCollateralExceedsLoan,
    LoanAccountInsufficient,
    LoanPoolInsufficient,
    LoanPoolUtilizationExceeded,
    LoanPoolWrongShard,
    LoanIfInsufficient,
    LoanInvalidConfig,
    LoanInvalidSymbolType,
    LoanNumeraireNotConfigured,
    LoanNotImplemented,
    RiskMarginPositionExists,
    UserMgmtUserNotSuspendableHasPositions,
    UserMgmtUserNotSuspendableNonEmptyAccounts,
    UserMgmtUserNotSuspended,
    UserMgmtUserAlreadySuspended,
    UserMgmtUserNotFound,
}

impl CommandResultCode {
    pub fn code(self) -> i32 {
        match self {
            CommandResultCode::ValidForMatchingEngine => 1,
            CommandResultCode::Success => 100,
            CommandResultCode::AuthInvalidUser => -1001,
            CommandResultCode::InvalidSymbol => -1201,
            CommandResultCode::UnsupportedSymbolType => -1203,
            CommandResultCode::RiskNsf => -2001,
            CommandResultCode::RiskInvalidReserveBidPrice => -2002,
            CommandResultCode::RiskAskPriceLowerThanFee => -2003,
            CommandResultCode::RiskMarginTradingDisabled => -2004,
            CommandResultCode::RiskInvalidAmount => -2005,
            CommandResultCode::RiskInvalidLeverage => -2006,
            CommandResultCode::RiskLeverageMismatch => -2007,
            CommandResultCode::RiskMarginModeMismatch => -2008,
            CommandResultCode::RiskMarginPositionNotExists => -2009,
            CommandResultCode::RiskMarkpriceNotAvailable => -2011,
            CommandResultCode::RiskIfInsufficient => -2012,
            CommandResultCode::MatchingUnknownOrderId => -3002,
            CommandResultCode::MatchingUnsupportedCommand => -3004,
            CommandResultCode::MatchingInvalidOrderBookId => -3005,
            CommandResultCode::MatchingMoveFailedPriceOverRiskLimit => -3041,
            CommandResultCode::MatchingReduceFailedWrongSize => -3051,
            CommandResultCode::UserMgmtUserAlreadyExists => -4001,
            CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame => -4101,
            CommandResultCode::UserMgmtAccountBalanceAdjustmentNsf => -4103,
            CommandResultCode::SymbolMgmtSymbolAlreadyExists => -5001,
            CommandResultCode::InternalTransferInvalidSelf => -4301,
            CommandResultCode::LoanNotEnabled => -6001,
            CommandResultCode::LoanAlreadyExists => -6002,
            CommandResultCode::LoanNotFound => -6003,
            CommandResultCode::LoanUidMismatch => -6004,
            CommandResultCode::LoanUserSuspended => -6005,
            CommandResultCode::LoanInvalidAmount => -6010,
            CommandResultCode::LoanPrincipalExceedsLimit => -6011,
            CommandResultCode::LoanMarkpriceNotReady => -6012,
            CommandResultCode::LoanLtvTooHigh => -6020,
            CommandResultCode::LoanLtvTooHighAfterBorrow => -6021,
            CommandResultCode::LoanLtvTooHighAfterRelease => -6022,
            CommandResultCode::LoanCrossLtvTooHighAfterWithdraw => -6023,
            CommandResultCode::LoanCollateralInsufficient => -6030,
            CommandResultCode::LoanCollateralNotAllowed => -6031,
            CommandResultCode::LoanCollateralExceedsLoan => -6032,
            CommandResultCode::LoanAccountInsufficient => -6040,
            CommandResultCode::LoanPoolInsufficient => -6050,
            CommandResultCode::LoanPoolUtilizationExceeded => -6051,
            CommandResultCode::LoanPoolWrongShard => -6052,
            CommandResultCode::LoanIfInsufficient => -6053,
            CommandResultCode::LoanInvalidConfig => -6060,
            CommandResultCode::LoanInvalidSymbolType => -6070,
            CommandResultCode::LoanNumeraireNotConfigured => -6080,
            CommandResultCode::LoanNotImplemented => -6099,
            CommandResultCode::RiskMarginPositionExists => -2010,
            CommandResultCode::UserMgmtUserNotSuspendableHasPositions => -4130,
            CommandResultCode::UserMgmtUserNotSuspendableNonEmptyAccounts => -4131,
            CommandResultCode::UserMgmtUserNotSuspended => -4132,
            CommandResultCode::UserMgmtUserAlreadySuspended => -4133,
            CommandResultCode::UserMgmtUserNotFound => -4201,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
        assert_eq!(CommandResultCode::MatchingInvalidOrderBookId.code(), -3005);
        assert_eq!(CommandResultCode::MatchingMoveFailedPriceOverRiskLimit.code(), -3041);
        assert_eq!(CommandResultCode::MatchingReduceFailedWrongSize.code(), -3051);
    }

    #[test]
    fn p6_internal_transfer_invalid_self_matches_java() {
        assert_eq!(CommandResultCode::InternalTransferInvalidSelf.code(), -4301);
    }

    #[test]
    fn futures_command_result_codes_match_java() {
        assert_eq!(CommandResultCode::UnsupportedSymbolType.code(), -1203);
        assert_eq!(CommandResultCode::RiskMarginTradingDisabled.code(), -2004);
        assert_eq!(CommandResultCode::RiskInvalidAmount.code(), -2005);
        assert_eq!(CommandResultCode::RiskInvalidLeverage.code(), -2006);
        assert_eq!(CommandResultCode::RiskLeverageMismatch.code(), -2007);
        assert_eq!(CommandResultCode::RiskMarginModeMismatch.code(), -2008);
        assert_eq!(CommandResultCode::RiskMarginPositionNotExists.code(), -2009);
        assert_eq!(CommandResultCode::RiskMarkpriceNotAvailable.code(), -2011);
    }

    #[test]
    fn p6_futures_if_result_code_matches_java_and_differs_from_loan_lif() {
        assert_eq!(CommandResultCode::RiskIfInsufficient.code(), -2012);
        assert_ne!(CommandResultCode::RiskIfInsufficient.code(), CommandResultCode::LoanIfInsufficient.code());
    }

    #[test]
    fn loan_command_result_codes_match_java() {
        assert_eq!(CommandResultCode::LoanNotEnabled.code(), -6001);
        assert_eq!(CommandResultCode::LoanAlreadyExists.code(), -6002);
        assert_eq!(CommandResultCode::LoanNotFound.code(), -6003);
        assert_eq!(CommandResultCode::LoanUidMismatch.code(), -6004);
        assert_eq!(CommandResultCode::LoanUserSuspended.code(), -6005);
        assert_eq!(CommandResultCode::LoanInvalidAmount.code(), -6010);
        assert_eq!(CommandResultCode::LoanPrincipalExceedsLimit.code(), -6011);
        assert_eq!(CommandResultCode::LoanMarkpriceNotReady.code(), -6012);
        assert_eq!(CommandResultCode::LoanLtvTooHigh.code(), -6020);
        assert_eq!(CommandResultCode::LoanLtvTooHighAfterBorrow.code(), -6021);
        assert_eq!(CommandResultCode::LoanLtvTooHighAfterRelease.code(), -6022);
        assert_eq!(CommandResultCode::LoanCrossLtvTooHighAfterWithdraw.code(), -6023);
        assert_eq!(CommandResultCode::LoanCollateralInsufficient.code(), -6030);
        assert_eq!(CommandResultCode::LoanCollateralNotAllowed.code(), -6031);
        assert_eq!(CommandResultCode::LoanCollateralExceedsLoan.code(), -6032);
        assert_eq!(CommandResultCode::LoanAccountInsufficient.code(), -6040);
        assert_eq!(CommandResultCode::LoanPoolInsufficient.code(), -6050);
        assert_eq!(CommandResultCode::LoanPoolUtilizationExceeded.code(), -6051);
        assert_eq!(CommandResultCode::LoanPoolWrongShard.code(), -6052);
        assert_eq!(CommandResultCode::LoanIfInsufficient.code(), -6053);
        assert_eq!(CommandResultCode::LoanInvalidConfig.code(), -6060);
        assert_eq!(CommandResultCode::LoanInvalidSymbolType.code(), -6070);
        assert_eq!(CommandResultCode::LoanNumeraireNotConfigured.code(), -6080);
        assert_eq!(CommandResultCode::LoanNotImplemented.code(), -6099);
    }
}
