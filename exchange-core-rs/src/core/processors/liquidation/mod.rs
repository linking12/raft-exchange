//! 无直接 Java 对应的单一类；本文件只是模块声明，对应 Java
//! `exchange.core2.core.processors.liquidation` 包本身（该包下四个 Java 类分别对应
//! 本模块下的四个子模块，见各子模块文件头注释）。
//!
//! 本模块整体是期货强平子系统：leader-local 事件驱动的强平流水线——
//! 发令器（scheduler）扫描到需要处理的持仓后提交命令 → 引擎（liquidation_engine）
//! 在 R1/R2 阶段执行 FORCE/IF/ADL 撮合逻辑 → 复制状态（liquidation_service：保险
//! 基金桶 + 破产仓账本，会写入 state hash 与快照）→ per-仓流程状态机
//! （liquidation_flow：leader-local，不复制）驱动单个持仓在 FORCE → IF → ADL
//! 之间推进。

pub mod command_submitter;
pub mod liquidation_engine;
pub mod liquidation_flow;
pub mod liquidation_service;
pub mod scheduler;
