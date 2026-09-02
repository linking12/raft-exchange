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

    // ================================================================
    // P6 Task 1：内部转账，逐字对应 Java `CommandResultCode.java:74`（handler 留后续 Task，
    // 见参考文档 §5.1；这里只落码值，供 InternalTransferProcessor.collectInput 的 R1 校验用）。
    // ================================================================
    InternalTransferInvalidSelf, // -4301，from == to 自转

    // ================================================================
    // P5 Task 1：现货借贷错误码，逐字对应 Java `CommandResultCode.java:82-120`
    // （handler 本身留 Task 2+，这里先落码值，供后续 Task 直接引用）。
    // ================================================================
    LoanNotEnabled,          // -6001，spec.loanConfig.initialLtvBps == 0
    LoanAlreadyExists,       // -6002，loanId 已存在（Isolated / Cross 命名空间独立）
    LoanNotFound,            // -6003，loanId 不存在
    LoanUidMismatch,         // -6004，loan.uid ≠ cmd.uid
    LoanUserSuspended,       // -6005，userStatus == SUSPEND 后拒绝所有 LOAN_* 命令
    LoanInvalidAmount,       // -6010，amount ≤ 0
    LoanPrincipalExceedsLimit, // -6011，principal > spec.loanConfig.maxAmount
    LoanMarkpriceNotReady,   // -6012，markPrice 缺失或 0
    LoanLtvTooHigh,          // -6020，开仓 LTV 超线（LOAN_CREATE Isolated）
    LoanLtvTooHighAfterBorrow, // -6021，Cross 借后账户级 LTV 超线（LOAN_CROSS_BORROW）
    LoanLtvTooHighAfterRelease, // -6022，减 Isolated 抵押后 LTV 超线
    LoanCrossLtvTooHighAfterWithdraw, // -6023，撤 Cross 抵押后账户级 LTV 超线
    LoanCollateralInsufficient, // -6030，accounts − calculateLocked 不足以覆盖新抵押量
    LoanCollateralNotAllowed,  // -6031，currencySpec.collateralWeightBps == 0（Cross 抵押白名单）
    LoanCollateralExceedsLoan, // -6032，减 Isolated 抵押量 > loan.collateralAmount
    LoanAccountInsufficient,   // -6040，还款时 accounts − calculateLocked < 应还金额
    LoanPoolInsufficient,      // -6050，池子不够 / POOL_WITHDRAW 抽资超
    LoanPoolUtilizationExceeded, // -6051，借出后池子利用率超 loanPoolUtilizationCapBps
    LoanPoolWrongShard,        // -6052，POOL_DEPOSIT/WITHDRAW 参数级路由错（cmd.uid ∉ [0, N)）
    LoanIfInsufficient,        // -6053，LOAN_IF_WITHDRAW 提取超过 LIF 该币种余额
    LoanInvalidConfig,         // -6060，阈值序 / 范围违规（initial 应 < liquidation < 10000 等）
    LoanInvalidSymbolType,     // -6070，试图给非-CURRENCY_EXCHANGE_PAIR（期货/交割）配置 loan
    LoanNumeraireNotConfigured, // -6080，Cross BORROW / WITHDRAW fail-close：numeraireCurrency 未设
    LoanNotImplemented,        // -6099，reserved
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
        // Java `CommandResultCode.java:74`：INTERNAL_TRANSFER_INVALID_SELF(-4301)。
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
