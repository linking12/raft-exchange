//! Java exchange-core 撮合引擎向 Rust 的移植 crate 根。
//! 对应 Java 顶层包 `exchange.core2.core`：Disruptor 多阶段流水线在 Rust 侧塌缩为单线程
//! 确定性管线（见 `core::exchange_core::ExchangeCore`），供上层 Raft 状态机按 apply 顺序驱动。
pub mod core;
