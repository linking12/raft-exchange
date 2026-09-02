//! 对应 Java: exchange.core2.core.processors.loan.** —— 现货借贷子系统。
//!
//! Task 1（本次）：数据模型 + 命令类型 + `LoanService` 骨架（字段 + 构造 + `state_hash` + 桶存
//! 取）。accrue/LTV/command handlers（`LoanCommandDispatcher`）留 Task 2+，参考文档
//! `docs/superpowers/specs/2026-09-02-p5-loan-reference.md` §1-§7。

pub mod loan_global_config;
pub mod loan_service;
pub mod rate;
