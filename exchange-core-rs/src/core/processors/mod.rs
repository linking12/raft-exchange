//! 模块入口，对应 Java 包 `exchange.core2.core.processors`（该包下一堆平级类，这里逐一 re-export
//! 成子模块，无单一对应文件）。各子模块概览：
//! - `risk_engine`：对应 `RiskEngine`，R1 下单校验 / R2 撮合结果落账的核心状态机；
//! - `risk_engine_command_dispatcher`：对应 `RiskEngineCommandDispatcher`，非交易类命令
//!   （账户维度/行情/运营）的二级路由；
//! - `loan`：借贷子系统（对应 Java `processors.loan` 子包），含 `LoanCommandDispatcher` 等；
//! - `twostep_command_processor`：对应抽象类 `TwoStepCommandProcessor`，R1 collect / R2 apply
//!   两步命令处理的统一接口；
//! - `adl_command_processor` / `if_command_processor` / `fundingfee_command_processor` /
//!   `internaltransfer_command_processor` / `loanratepricing_command_processor`：分别对应
//!   `ADLCommandProcessor` / `IFCommandProcessor` / `FundingFeeCommandProcessor` /
//!   `InternalTransferCommandProcessor` / 借贷利率重定价处理器，均实现 `TwoStepCommandProcessor`；
//! - `liquidation`：强平子系统（对应 Java `processors.liquidation` 子包）；
//! - `matching_engine_router`：撮合引擎路由（对应 `MatchingEngineRouter`）；
//! - `symbol_specification_provider` / `user_profile_service`：对应
//!   `SymbolSpecificationProvider` / `UserProfileService`，symbol 配置与用户档案存取；
//! - `binary_commands_processor`：二进制命令帧的 chronicle 编解码。

pub mod adl_command_processor;
pub mod fundingfee_command_processor;
pub mod if_command_processor;
pub mod internaltransfer_command_processor;
pub mod liquidation;
pub mod loan;
pub mod loanratepricing_command_processor;
pub mod binary_commands_processor;
pub mod matching_engine_router;
pub mod risk_engine;
pub mod risk_engine_command_dispatcher;
pub mod symbol_specification_provider;
pub mod twostep_command_processor;
pub mod user_profile_service;
