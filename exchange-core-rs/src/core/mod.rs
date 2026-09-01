//! 引擎核心。对应 Java 包 `exchange.core2.core`。
//!
//! 并发模型（设计文档 §4）：Java 的 Disruptor 五段
//!   `Grouping → [R1 ∥ Journal] → ME → R2`
//! 首期塌缩为**单线程确定性顺序管线**，保留分片归属（`uid % N`、`symbol % M`）：
//!
//! ```text
//! for cmd in group {
//!     let rshard = uid % risk_shards;
//!     let sshard = symbol % matching_shards;
//!     risk[rshard].pre_process(cmd);   // R1
//!     journal.write(cmd);              // J（可选）
//!     matching[sshard].process(cmd);   // ME
//!     risk[rshard].risk_release(cmd);  // R2（含 R2Sync）
//! }
//! ```
//!
//! 本期单 shard、单线程，`rshard`/`sshard` 恒为 0——具体实现见 [`exchange_core::ExchangeCore`]。

pub mod common;
pub mod orderbook;
pub mod processors;
pub mod utils;

pub mod exchange_core;
pub mod exchange_api;

#[cfg(test)]
mod e2e_tests;
