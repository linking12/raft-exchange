//! 状态快照与 journal（WAL）。
//!
//! 对应 Java: `MemorySerializationProcessor` + 各 Restorer（proto 序列化）
//! 及 `core/processors/journaling/**`。
//!
//! 首期策略（设计文档 §6）：
//! - 不与 Java Raft 格式互通；用 `serde`（+ bincode/后续）重做，保持逻辑结构对等。
//! - 优先级低于引擎核心，可先留桩，引擎主体跑通后回填。

// TODO(port): snapshot 序列化 / 反序列化（orderbook + risk state + loan/position 账本）
// TODO(port): journal (WAL)
