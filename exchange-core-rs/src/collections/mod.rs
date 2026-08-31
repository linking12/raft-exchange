//! 有序容器抽象。
//!
//! 对应 Java: `exchange.core2.collections.**`
//! - `art/LongAdaptiveRadixTreeMap`（价位 / 订单索引）
//! - `objpool/ObjectsPool`（对象池，避 GC）
//!
//! 首期策略（设计文档 §5）：
//! - ART → 直接用 `std::collections::BTreeMap<i64, _>`（同为有序遍历，撮合结果一致）。
//!   自研 ART 是性能优化，留 TODO。
//! - ObjectsPool → 删除（Rust 所有权无 GC 压力，语义无副作用）。

// TODO(port, perf): LongAdaptiveRadixTreeMap
