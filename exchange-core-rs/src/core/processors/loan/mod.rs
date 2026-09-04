//! 对应 Java `exchange.core2.core.processors.loan.**`：现货借贷子系统，参考文档 §1-§7。

pub mod loan_command_dispatcher;
pub mod loan_global_config;
pub mod loan_liquidation_engine;
pub mod loan_service;
pub mod rate;
