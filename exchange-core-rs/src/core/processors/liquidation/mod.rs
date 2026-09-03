//! 对应 Java: exchange.core2.core.processors.liquidation.** —— 强平流程子系统。
//!
//! Task 5（本次）：`LiquidationService`——保险基金（IF）复制状态：`notionals`（per-symbol
//! available/reserved）+ `positions`（IF 自身接管仓位）。orderId 编码
//! （`generateLiquidationOrderId`/`generateIFOrderId`/`generateADLOrderId`/
//! `isLiquidationOrderId`）与 `computeProfitablePositionsBySymbol`（ADL 候选构造）留 Task 7
//! （`LiquidationEngine`——FORCE→IF→ADL 状态机的编排层，是这些函数唯一的调用方，参考文档 §1/§3），
//! 见 `liquidation_service.rs` 模块文档"未移植：orderId 编码 / ADL 候选构造"一节。

pub mod liquidation_engine;
pub mod liquidation_flow;
pub mod liquidation_service;
