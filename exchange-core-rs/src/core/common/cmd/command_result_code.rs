#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CommandResultCode {
    ValidForMatchingEngine, // 1
    Success,                // 100
    AuthInvalidUser,        // -1001
    InvalidSymbol,          // -1201
    UnsupportedSymbolType,  // -1203（P4 Task 1: 非期货 symbol 走期货下单路径）
    RiskNsf,                // -2001
    RiskInvalidReserveBidPrice, // -2002
    RiskAskPriceLowerThanFee,   // -2003
    RiskMarginTradingDisabled,  // -2004（P4: cfgMarginTradingEnabled==false）
    RiskInvalidAmount,          // -2005（P4 Task 6: MARGIN_ADJUSTMENT cmd.price<=0）
    RiskInvalidLeverage,        // -2006（P4: 杠杆倍率不在 symbol 支持范围内）
    RiskLeverageMismatch,       // -2007（P4: 新杠杆与当前仓位杠杆不匹配）
    RiskMarginModeMismatch,     // -2008（P4: 仓位模式不匹配）
    RiskMarginPositionNotExists, // -2009（P4 Task 6: MARGIN_ADJUSTMENT ISOLATED 目标仓位不存在）
    RiskMarkpriceNotAvailable,  // -2011（P4: mark price 缺失/为 0）
    MatchingUnknownOrderId, // -3002
    MatchingUnsupportedCommand, // -3004
    MatchingInvalidOrderBookId, // -3005（Task 9: MatchingEngineRouter 未知 symbol）
    MatchingMoveFailedPriceOverRiskLimit, // -3041（P2 Task 5: moveOrder 现货 BID 超出 reserveBidPrice 风控）
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
            CommandResultCode::MatchingUnknownOrderId => -3002,
            CommandResultCode::MatchingUnsupportedCommand => -3004,
            CommandResultCode::MatchingInvalidOrderBookId => -3005,
            CommandResultCode::MatchingMoveFailedPriceOverRiskLimit => -3041,
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
        assert_eq!(CommandResultCode::MatchingMoveFailedPriceOverRiskLimit.code(), -3041);
        assert_eq!(CommandResultCode::MatchingReduceFailedWrongSize.code(), -3051);
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
}
