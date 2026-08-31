//! 风控与业务处理器。移植体量最大、语义最硬核的部分。
//!
//! 对应 Java: `exchange.core2.core.processors.**`（25 文件 + loan/ + liquidation/）
//! - `RiskEngine` / `RiskEngineCommandDispatcher`（R1 preProcess / R2 riskRelease，按 uid 分片）
//! - `MatchingEngineRouter`（ME，按 symbol 分片）
//! - `R2Sync`（跨分片同步；ADL OI 守恒依赖）
//! - `liquidation/**`（保险基金强平 LIF）
//! - `ADLCommandProcessor`（自动减仓）
//! - `FundingFeeCommandProcessor` / `LoanRatePricingProcessor` / loan/**
//! - `InternalTransferProcessor` / `BinaryCommandsProcessor` / `UserProfileService`
//! - `SymbolSpecificationProvider` / `CurrencySpecificationProvider`
//!
//! 确定性铁律（设计文档 §5）：uid→profile、symbol→spec 等映射用有序容器；
//! 任何影响输出的迭代禁用 `HashMap` 迭代序。

// TODO(port): RiskEngine（pre_process / risk_release）
// TODO(port): MatchingEngineRouter
// TODO(port): liquidation / ADL / funding / loan
// TODO(port): internal transfer / user profile / symbol & currency spec
