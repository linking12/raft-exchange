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

pub mod risk;

// TODO(port): RiskEngine risk_release（R2）—— reject/reduce 释放（Task 5，已完成）；
// buy/sell TRADE 链结算（Task 6/7，占位 TODO 留在 risk.rs::handler_risk_release）
// TODO(port): MatchingEngineRouter
// TODO(port): liquidation / ADL / funding / loan
// TODO(port): internal transfer / user profile / symbol & currency spec
