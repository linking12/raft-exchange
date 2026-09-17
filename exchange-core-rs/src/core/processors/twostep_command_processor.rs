//! 对应 Java `exchange.core2.core.processors.TwoStepCommandProcessor`（抽象基类）。
//!
//! Java 版的"两步"分三个阶段：R1 `collectInput`（各 shard 并行收集局部观察，写到 cmd 的 per-shard
//! 槽位）→ matcher stage `process`/`buildMatcherEvents`（单线程合并产生 MatcherTradeEvent 链）→
//! R2 `applyEvent`（每 shard 遍历 matcherEvent 链逐笔应用）+ `finalizeForCommand`（每 shard 收尾一次）。
//! 同一个 Java processor 类在运行时存在两份独立实例：matcher stage 实例只持 `eventsHelper`；
//! R1/R2 实例（每 shard 一份）只持 `riskEngine`；两边方法互不调用，跨用会在 `Objects.requireNonNull`
//! 处报错。IF / ADL / FundingFee 三类强平相关命令共享这一形态。
//!
//! Rust 版没有 Disruptor 分片/matcher-stage 架构（参见 `risk_engine_command_dispatcher.rs` 头部说明：
//! Rust 是单实例、无分片），所以这里把 Java 的三段收窄成两段：`collect` 合并了 Java 的 R1
//! `collectInput`（读取当前状态、算出要做的事）与 matcher-stage 的 `buildMatcherEvents`（产生要应用的
//! 事件），一次调用内直接算完并把结果写回 cmd；`apply` 合并了 Java R2 的 `applyEvent`（逐笔应用
//! matcherEvent）与 `finalizeForCommand`（命令级收尾），一次调用把 `collect` 阶段算好的结果落地到
//! RiskEngine/UserProfileService。已知实现（如 `FundingFeeCommandProcessor`）内部仍保留
//! `collect_input`/`apply_event`/`build_matcher_events` 等私有辅助函数名以呼应 Java 对应方法，
//! 但对外只暴露 `collect`/`apply` 这两段。
//!
//! `TwoStepMasterProcessor`/`TwoStepSlaveProcessor`（Disruptor `EventProcessor` 的主从线程协调，管的是
//! ring buffer 消费者的启停/自旋等待，不是命令的收集/应用语义）在 Rust 这套单实例架构下没有对应物，
//! 不要与本 trait 混淆。

use crate::core::common::cmd::command_result_code::CommandResultCode;
use crate::core::common::cmd::order_command::OrderCommand;
use crate::core::processors::risk_engine::RiskEngine;
use crate::core::processors::symbol_specification_provider::SymbolSpecificationProvider;
use crate::core::processors::user_profile_service::UserProfileService;

/// 两步处理器执行时依赖的可变上下文，把 R1/R2 都要用到的三个引用打包传递，
/// 对应 Java `TwoStepCommandProcessor` 内持有的 `riskEngine`（Rust 里进一步拆成
/// risk/ups/ssp 三个字段，因为 Rust 没有把 UserProfileService/SymbolSpecificationProvider
/// 挂在 RiskEngine 内部持有 `&mut` 借用）。
pub struct TwoStepContext<'a> {
    pub risk: &'a mut RiskEngine,
    pub ups: &'a mut UserProfileService,
    pub ssp: &'a SymbolSpecificationProvider,
}

impl<'a> TwoStepContext<'a> {
    pub fn new(
        risk: &'a mut RiskEngine,
        ups: &'a mut UserProfileService,
        ssp: &'a SymbolSpecificationProvider,
    ) -> Self {
        Self { risk, ups, ssp }
    }
}

/// 两步命令处理器统一接口，对应 Java 抽象类 `TwoStepCommandProcessor`（收窄后的两段式，见文件头说明）。
pub trait TwoStepCommandProcessor {
    /// R1：对应 Java `collectInput`。读取当前状态、做校验/计算，把要执行的动作准备好
    /// （写回 cmd 或内部临时状态），返回命令结果码。
    fn collect(&self, ctx: &mut TwoStepContext, cmd: &mut OrderCommand) -> CommandResultCode;

    /// R2：对应 Java `applyEvent` + `finalizeForCommand` 的合并。把 `collect` 阶段准备好的动作
    /// 真正落到 RiskEngine/UserProfileService 状态上。
    fn apply(&self, ctx: &mut TwoStepContext, cmd: &mut OrderCommand);
}
