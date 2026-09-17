//! 借贷子系统（对应 Java `exchange.core2.core.processors.loan` 包）。
//! 命令分发（loan_command_dispatcher）→ 复制状态（loan_service：池子/借据）→ 全局配置
//! （loan_global_config）→ 借贷强平引擎（loan_liquidation_engine）→ 利率模型（rate 子模块）。

pub mod loan_command_dispatcher;
pub mod loan_global_config;
pub mod loan_liquidation_engine;
pub mod loan_service;
pub mod rate;
