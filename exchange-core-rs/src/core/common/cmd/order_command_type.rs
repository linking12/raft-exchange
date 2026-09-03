/// 对应 Java `exchange.core2.core.common.cmd.OrderCommandType`（现货子集 + P4 Task 1 期货
/// `create_positions_key` 所需的 `CLOSE_POSITION`/`FORCE_LIQUIDATION` 两个变体 + P4 Task 6
/// 新增 `LEVERAGE_ADJUSTMENT`/`MARGIN_ADJUSTMENT`/`MARKPRICE_ADJUSTMENT` 三个非交易命令 + P5
/// Task 1 新增 14 个 loan/pool 变体 + `RepriceLoanRates` + P6 Task 1 新增 `InternalTransfer`/
/// `IfTakeover`/`AutoDeleveraging`/期货 `IfDeposit`/`IfWithdraw`/`SettleFundingfees`/
/// `LiquidationScan`/`SystemLiquidationNotify`；其余期货/清算变体本移植尚未列入）。
/// `is_non_trading()` / `is_loan()` 对照 Java 的二级 dispatch 门守分类语义。
///
/// **Ruling P6-D**：本移植是独立 crate，无 wire-protocol 兼容需求，新增码只需在本枚举内部互异，
/// 不必与 Java 字节码值逐位对齐。参考文档 §12.4 额外指出 Java 源码 `LIQUIDATION_SCAN`（码 64）
/// 与 `LOAN_IF_DEPOSIT`（码 64）本身就重复——本移植 `LiquidationScan` 故意不选 64（已被
/// `LoanIfDeposit` 占用），选 44，规避这个 Java 既有的重复码，不是"修正" Java，只是本枚举内部
/// 互异约束下的必然选择。
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
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
    /// 对应 Java `LEVERAGE_ADJUSTMENT`（码 21）：显式调整某 symbol 全部仓位的杠杆
    /// （`RiskEngineCommandDispatcher.adjustLeverage`）。
    LeverageAdjustment,
    /// 对应 Java `MARGIN_ADJUSTMENT`（码 23）：追加/CROSS 充值保证金
    /// （`RiskEngineCommandDispatcher.adjustMargin`）。
    MarginAdjustment,
    /// 对应 Java `MARKPRICE_ADJUSTMENT`（码 24）：更新 `lastPriceCache`
    /// （`RiskEngineCommandDispatcher.adjustMarkPrice`；本移植未含 `liquidationEngine
    /// .checkPositions` 清算钩子，P6 落地）。
    MarkpriceAdjustment,

    // ================================================================
    // P5 Task 1：现货借贷 —— `isLoan()` 恰好覆盖的 14 个码（参考文档 §0/§2.11）。
    // handler 本身（`LoanCommandDispatcher`）留 Task 2+，这里只落变体 + 分类。
    // ================================================================
    /// 码 50：Isolated 开仓。
    LoanCreate,
    /// 码 51：Isolated 还款。
    LoanRepay,
    /// 码 52：Isolated 追加抵押。
    LoanAddCollateral,
    /// 码 53：Isolated 释放抵押。
    LoanReleaseCollateral,
    /// 码 54：Isolated 强平（R1 预挪抵押后转入现货撮合，R2 结算见参考文档 §2.5/§5.2）。
    LoanForceLiquidate,
    /// 码 55：Cross 追加抵押。
    LoanCrossAddCollateral,
    /// 码 56：Cross 提取抵押。
    LoanCrossWithdrawCollateral,
    /// 码 57：Cross 借款。
    LoanCrossBorrow,
    /// 码 58：Cross 还款。
    LoanCrossRepay,
    /// 码 59：Cross 强平（同 `LoanForceLiquidate` 的 R1/R2 两段式，见参考文档 §2.10/§5.2）。
    LoanCrossForceLiquidate,
    /// 码 60：借贷池运营充值（`cmd.uid` 携带 shardId，非真实 uid，见参考文档 §2.11）。
    PoolDeposit,
    /// 码 61：借贷池运营提取。
    PoolWithdraw,
    /// 码 64：保险基金运营充值。
    LoanIfDeposit,
    /// 码 65：保险基金运营提取。
    LoanIfWithdraw,

    /// 对应 Java `REPRICE_LOAN_RATES`（码 63）：**不属于** `isLoan()` 的 14 码；走
    /// `isNonTrading()` → `RiskEngineCommandDispatcher` → `LoanRatePricingProcessor`
    /// （TwoStep reprice 管线，参考文档 §4.2；本 Task 只落分类，管线本体留 Task 3+）。
    RepriceLoanRates,

    // ================================================================
    // P6 Task 1：期货强平/ADL/资金费/内部转账命令码（参考文档 §0/§9/§12.4）。
    // handler 本体（LiquidationEngine/IFCommandProcessor/ADLCommandProcessor/
    // FundingFeeCommandProcessor/InternalTransferProcessor 等）留后续 Task，这里先落变体 +
    // is_non_trading 分类。
    // ================================================================
    /// 对应 Java `INTERNAL_TRANSFER`（码 14）：账户间内部转账，`isNonTrading()` 命中，走
    /// `RiskEngineCommandDispatcher` → `InternalTransferProcessor`（TwoStep，参考文档 §5）。
    InternalTransfer,
    /// 对应 Java `SETTLE_FUNDINGFEES`（码 25）：资金费结算，外部（operator/oracle）驱动，
    /// **不属于** `isNonTrading()`——留在主 switch（参考文档 §4）。
    SettleFundingfees,
    /// 对应 Java `SYSTEM_LIQUIDATION_NOTIFY`（码 31）：`startLiquidationFlow` 附带发出的
    /// best-effort 强平告警通知，走 raft 但不 mutate 任何状态；**不属于** `isNonTrading()`
    /// （参考文档 §1.4）。
    SystemLiquidationNotify,
    /// 对应 Java `IF_TAKEOVER`（码 40）：保险基金接管破产仓位（FORCE 被拒后的下一级），
    /// TwoStep（参考文档 §1.5/§2.2）；**不属于** `isNonTrading()`——留在主 switch。
    IfTakeover,
    /// 对应 Java `AUTO_DELEVERAGING`（码 41）：ADL 摊派（IF 接管仍不足后的最终级），TwoStep
    /// （参考文档 §1.5/§3）；**不属于** `isNonTrading()`——留在主 switch。
    AutoDeleveraging,
    /// 对应 Java 期货 `IF_DEPOSIT`（码 42）：保险基金运营充值。**与 `LoanIfDeposit`
    /// （码 64，现货借贷 LIF 池）是两个完全独立的池子**（参考文档 §10"Loan LIF interaction
    /// with futures IF: None"），不可混淆；`isNonTrading()` 命中，同 `LoanIfDeposit` 走
    /// `RiskEngineCommandDispatcher`（参考文档 §2.1）。
    IfDeposit,
    /// 对应 Java 期货 `IF_WITHDRAW`（码 43）：保险基金运营提取。同 [`Self::IfDeposit`]，
    /// 与 `LoanIfWithdraw` 是独立池子；`isNonTrading()` 命中。
    IfWithdraw,
    /// 对应 Java `LIQUIDATION_SCAN`（Java 码 64，与 `LOAN_IF_DEPOSIT` 撞码——本移植选 44
    /// 规避，见类文档 Ruling P6-D）：全量强平扫描 backstop（`cmd.symbol < 0` 触发，参考文档
    /// §1.1/§7）；**不属于** `isNonTrading()`——留在主 switch。
    LiquidationScan,

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
            // 注意：Java 源码里 `LOAN_IF_DEPOSIT` 与尚未移植的 `LIQUIDATION_SCAN` 同码 64——
            // 这是 Java 既有的重复码（`OrderCommandType.java:49,70`），本移植现货子集未含
            // `LIQUIDATION_SCAN`，故此处赋值不产生实际冲突；逐字保留该数值，不做"修正"。
            OrderCommandType::LoanIfDeposit => 64,
            OrderCommandType::LoanIfWithdraw => 65,
            OrderCommandType::BinaryDataCommand => 91,
            OrderCommandType::Nop => 120,
            OrderCommandType::Reset => 124,
            // P6 Task 1：见类文档 Ruling P6-D——本移植零依赖 wire-protocol 字节对齐，新码只需
            // 互异；InternalTransfer/SettleFundingfees/SystemLiquidationNotify/IfTakeover/
            // AutoDeleveraging/IfDeposit/IfWithdraw 数值对齐 Java（参考文档 §12.4），
            // LiquidationScan 故意不取 Java 的 64（已被 LoanIfDeposit 占用），改用 44。
            OrderCommandType::InternalTransfer => 14,
            OrderCommandType::SettleFundingfees => 25,
            OrderCommandType::SystemLiquidationNotify => 31,
            OrderCommandType::IfTakeover => 40,
            OrderCommandType::AutoDeleveraging => 41,
            OrderCommandType::IfDeposit => 42,
            OrderCommandType::IfWithdraw => 43,
            OrderCommandType::LiquidationScan => 44,
        }
    }

    /// 对应 Java `OrderCommandType.isNonTrading()`（`:110-134`）：命中即整块委托
    /// `RiskEngineCommandDispatcher.dispatch`，主 switch 只留交易/结算/引擎自身生命周期。
    /// 现货子集 + P4 Task 6 期货非交易命令里命中的有 `ADD_USER` / `BALANCE_ADJUSTMENT` /
    /// `BINARY_DATA_COMMAND` / `LEVERAGE_ADJUSTMENT` / `MARGIN_ADJUSTMENT` /
    /// `MARKPRICE_ADJUSTMENT` + P5 Task 1 新增 `RepriceLoanRates`（**不含** 14 个
    /// `is_loan()` 码——reprice 走 `RiskEngineCommandDispatcher` → `LoanRatePricingProcessor`，
    /// loan 命令走独立 `LoanCommandDispatcher`，两条门守互斥，见参考文档 §0）+ P6 Task 1
    /// 新增 `InternalTransfer` / 期货 `IfDeposit` / `IfWithdraw`（参考文档 §0 末段："`isNonTrading()`
    /// now includes `INTERNAL_TRANSFER` and `MARKPRICE_ADJUSTMENT`"）。
    ///
    /// **不命中**（参考文档 §0 末段逐字确认）：`IfTakeover` / `AutoDeleveraging` /
    /// `SettleFundingfees` / `ForceLiquidation` / `LiquidationScan` /
    /// `SystemLiquidationNotify`——这些留在 `RiskEngine.preProcessCommand` 主 switch 与
    /// `MatchingEngineRouter.processOrder` 的显式分支里，不走 `isNonTrading()` dispatcher。
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
        )
    }

    /// 对应 Java `OrderCommandType.isLoan()`（`:141-161`）：`RiskEngine.preProcessCommand` 的
    /// 二级 dispatch 门守，命中则整块委托 `LoanCommandDispatcher.dispatch`，主 switch 里永远看
    /// 不到 loan 命令。恰好覆盖 14 码（参考文档 §0 清单）；`RepriceLoanRates`（码 63）**不**在
    /// 其中——它属于 `is_non_trading()`。
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
        // 非交易门守：ADD_USER / BALANCE_ADJUSTMENT / BINARY_DATA_COMMAND /
        // LEVERAGE_ADJUSTMENT / MARGIN_ADJUSTMENT / MARKPRICE_ADJUSTMENT 命中。
        assert!(OrderCommandType::AddUser.is_non_trading());
        assert!(OrderCommandType::BalanceAdjustment.is_non_trading());
        assert!(OrderCommandType::BinaryDataCommand.is_non_trading());
        assert!(OrderCommandType::LeverageAdjustment.is_non_trading());
        assert!(OrderCommandType::MarginAdjustment.is_non_trading());
        assert!(OrderCommandType::MarkpriceAdjustment.is_non_trading());
        // P5：REPRICE_LOAN_RATES 是 isNonTrading，不是 isLoan。
        assert!(OrderCommandType::RepriceLoanRates.is_non_trading());
        // 主 switch 交易 / 撮合直落命令：不命中。
        assert!(!OrderCommandType::PlaceOrder.is_non_trading());
        assert!(!OrderCommandType::CancelOrder.is_non_trading());
        assert!(!OrderCommandType::MoveOrder.is_non_trading());
        assert!(!OrderCommandType::ReduceOrder.is_non_trading());
        assert!(!OrderCommandType::OrderBookRequest.is_non_trading());
        assert!(!OrderCommandType::Reset.is_non_trading());
        assert!(!OrderCommandType::Nop.is_non_trading());
        // loan 14 码本身不属于 isNonTrading（走独立的 isLoan 门守）。
        assert!(!OrderCommandType::LoanCreate.is_non_trading());
    }

    #[test]
    fn order_command_type_is_loan_covers_exactly_fourteen_codes() {
        // 恰好 14 个 loan/pool 命令码命中（参考文档 §0 清单）。
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

        // REPRICE_LOAN_RATES 不在 isLoan 的 14 码内——它是 isNonTrading。
        assert!(!OrderCommandType::RepriceLoanRates.is_loan());
        // 主 switch 交易 / 非借贷非交易命令：不命中。
        assert!(!OrderCommandType::PlaceOrder.is_loan());
        assert!(!OrderCommandType::BalanceAdjustment.is_loan());
        assert!(!OrderCommandType::AddUser.is_loan());
        assert!(!OrderCommandType::MarkpriceAdjustment.is_loan());
    }

    #[test]
    fn order_command_type_default_is_nop() {
        assert_eq!(OrderCommandType::default(), OrderCommandType::Nop);
    }

    // ================================================================
    // P6 Task 1：新命令码 + is_non_trading 分类（参考文档 §0/§12.4）
    // ================================================================

    #[test]
    fn p6_new_codes_are_internally_distinct_and_match_java_where_unconflicted() {
        // Ruling P6-D：只需互异，Java 数值不是硬约束；但对无冲突的码保留 Java 数值方便对照。
        assert_eq!(OrderCommandType::InternalTransfer.code(), 14);
        assert_eq!(OrderCommandType::SettleFundingfees.code(), 25);
        assert_eq!(OrderCommandType::SystemLiquidationNotify.code(), 31);
        assert_eq!(OrderCommandType::IfTakeover.code(), 40);
        assert_eq!(OrderCommandType::AutoDeleveraging.code(), 41);
        assert_eq!(OrderCommandType::IfDeposit.code(), 42);
        assert_eq!(OrderCommandType::IfWithdraw.code(), 43);
        // Java 的 64 与 LOAN_IF_DEPOSIT 撞码——本移植故意不取 64，见类文档 Ruling P6-D。
        assert_eq!(OrderCommandType::LiquidationScan.code(), 44);
        assert_ne!(OrderCommandType::LiquidationScan.code(), OrderCommandType::LoanIfDeposit.code());

        // 互异性：把所有变体的 code() 丢进一个 Vec，去重后长度不变。
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
        // 参考文档 §0 末段逐字列出的"不属于 isNonTrading"清单。
        assert!(!OrderCommandType::IfTakeover.is_non_trading());
        assert!(!OrderCommandType::AutoDeleveraging.is_non_trading());
        assert!(!OrderCommandType::SettleFundingfees.is_non_trading());
        assert!(!OrderCommandType::ForceLiquidation.is_non_trading());
        assert!(!OrderCommandType::LiquidationScan.is_non_trading());
        assert!(!OrderCommandType::SystemLiquidationNotify.is_non_trading());
    }

    #[test]
    fn p6_new_codes_are_not_loan_codes() {
        // 期货 IF 池与 loan LIF 池是两个独立 bucket（参考文档 §10），互不属于对方的分类门守。
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
