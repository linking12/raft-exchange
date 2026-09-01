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

pub mod journaling;
pub mod matching_engine_router;
pub mod risk_engine;
pub mod symbol_specification_provider;
pub mod user_profile_service;

// TODO(port): RiskEngine risk_release（R2）—— reject/reduce 释放（Task 5，已完成）；
// sell TRADE 链结算（Task 6，已完成，risk_engine.rs::handle_matcher_events_exchange_sell）；
// buy TRADE 链结算（Task 7，占位 TODO 留在 risk_engine.rs::handler_risk_release）
// TODO(port): MatchingEngineRouter（Task 9，已完成，matching_engine_router.rs）
// TODO(port): liquidation / ADL / funding / loan
// TODO(port): internal transfer / user profile / symbol & currency spec
