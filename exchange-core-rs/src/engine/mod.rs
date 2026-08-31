//! 引擎编排与对外 API。
//!
//! 对应 Java: `exchange.core2.core.*`
//! - `ExchangeCore`（Disruptor 五段编排）
//! - `ExchangeApi` / `SimpleEventsProcessor`
//! - `ITradeEventsHandler` / `IFundEventsHandler`
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

// TODO(port): ExchangeCore（确定性顺序管线 + grouping 批边界）
// TODO(port): ExchangeApi
// TODO(port): 事件处理 handler
