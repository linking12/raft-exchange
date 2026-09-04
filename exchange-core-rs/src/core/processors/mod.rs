//! 风控与业务处理器，对应 Java `exchange.core2.core.processors.**`（25 文件 + loan/ + liquidation/）。
//! 确定性铁律（设计文档 §5）：uid→profile、symbol→spec 等映射用有序容器，禁 `HashMap` 迭代序。

pub mod adl_command_processor;
pub mod funding_fee_command_processor;
pub mod if_command_processor;
pub mod internal_transfer_processor;
pub mod liquidation;
pub mod loan;
pub mod loan_rate_pricing_processor;
pub mod matching_engine_router;
pub mod risk_engine;
pub mod symbol_specification_provider;
pub mod user_profile_service;

// TODO(port): 各处理器移植状态见对应子模块。
