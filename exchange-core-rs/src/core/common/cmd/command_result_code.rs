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
    MatchingInvalidOrderBookId, // -3005（Task 9: MatchingEngineRouter 未知 symbol）
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
            CommandResultCode::MatchingInvalidOrderBookId => -3005,
            CommandResultCode::MatchingReduceFailedWrongSize => -3051,
            CommandResultCode::UserMgmtUserAlreadyExists => -4001,
            CommandResultCode::UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame => -4101,
            CommandResultCode::UserMgmtAccountBalanceAdjustmentNsf => -4103,
            CommandResultCode::SymbolMgmtSymbolAlreadyExists => -5001,
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
    }
}
