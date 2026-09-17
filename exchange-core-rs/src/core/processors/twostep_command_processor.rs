//! 对应 Java `exchange.core2.core.processors.TwoStepCommandProcessor`(抽象基类):两步命令
//! (SETTLE_FUNDINGFEES / IF_TAKEOVER / AUTO_DELEVERAGING / REPRICE_LOAN_RATES / INTERNAL_TRANSFER)的
//! **R1 采集(collect)+ R2 应用(apply)** 契约。
//!
//! Java 侧每个处理器持有一个 `riskEngine` 引用,接口方法只收 `cmd`;Rust 处理器**无状态**(单元结构体),
//! 无法自持 `&mut RiskEngine`(会与"引擎拥有处理器"自引用冲突),故把引擎可变状态显式打包成 [`TwoStepContext`]
//! 传入每个方法——语义与 Java 一致,只是状态传递方式不同。
//!
//! R1(`collect`)= Java `collectInput` + `buildMatcherEvents`:读状态、把计划/事件写回 `cmd`,返回结果码。
//! R2(`apply`)  = Java `applyEvent`(逐事件)+ `finalizeForCommand`:把 R1 的计划落账并发结算事件。
//! (Rust 无"框架逐事件回调"机制,R2 由处理器自身遍历 `cmd` 上的事件载体完成,故合并为单个 `apply`。)

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;

/// 两步命令 R1/R2 所需的引擎可变状态束(对应 Java 处理器持有的 `riskEngine` 引用)。
/// 各字段是 [`RiskEngine`] pipeline 里本就分开传递的三块状态;打包以便按统一 trait 签名注入。
pub struct TwoStepContext<'a> {
    pub risk: &'a mut RiskEngine,
    pub ups: &'a mut UserProfileService,
    pub ssp: &'a SymbolSpecificationProvider,
}

impl<'a> TwoStepContext<'a> {
    /// 从 pipeline 分散的三块状态打包出 ctx；调用点传 `&mut` 引用会自动 reborrow，ctx 释放后原引用继续可用。
    pub fn new(
        risk: &'a mut RiskEngine,
        ups: &'a mut UserProfileService,
        ssp: &'a SymbolSpecificationProvider,
    ) -> Self {
        Self { risk, ups, ssp }
    }
}

/// 两步命令处理器契约,对应 Java `TwoStepCommandProcessor`。
pub trait TwoStepCommandProcessor {
    /// R1:采集输入 + 生成计划/matcher 事件,写回 `cmd`;返回结果码(两步命令恒 `Success`,REJECT 是事件级信号)。
    fn collect(&self, ctx: &mut TwoStepContext, cmd: &mut OrderCommand) -> CommandResultCode;

    /// R2:把 R1 的计划落账(逐事件 apply + finalize),产出结算类 fund event 到 `cmd.fund_events`。
    fn apply(&self, ctx: &mut TwoStepContext, cmd: &mut OrderCommand);
}
