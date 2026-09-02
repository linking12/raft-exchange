//! 对应 Java: exchange.core2.core.processors.loan.** —— 现货借贷子系统。
//!
//! Task 1：数据模型 + 命令类型 + `LoanService` 骨架（字段 + 构造 + `state_hash` + 桶存取）。
//! Task 2：利率模型（accrue/display_interest 二分派）。
//! Task 4（本次）：`LoanCommandDispatcher`——Isolated 生命周期命令 CREATE/REPAY/
//! ADD_COLLATERAL/RELEASE_COLLATERAL + 公共 preamble + dispatch 表；`LoanService` 补
//! disburse_loan/apply_debt_payment/collateral_value_in_quote_currency/verify_pool_capacity。
//! Cross 命令（Task 5）、ADD_LOAN/pool-LIF 运营命令（Task 6）、强平（Task 7）、reprice
//! 管线（Task 8）留后续任务，参考文档
//! `docs/superpowers/specs/2026-09-02-p5-loan-reference.md` §1-§7。

pub mod loan_command_dispatcher;
pub mod loan_global_config;
pub mod loan_service;
pub mod rate;
