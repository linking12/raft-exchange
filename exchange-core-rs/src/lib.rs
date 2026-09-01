//! # exchange-core-rs
//!
//! Java `exchange-core`（`exchange.core2` 包）的 Rust 全量对等移植。
//! 单 crate，内部按域分 module（对齐 Java 子包 & matching-core 的 `src/` 布局）。
//!
//! 设计文档: `docs/superpowers/specs/2026-08-31-exchange-core-rust-port-design.md`
//!
//! 首期原则：方式 B（先写引擎，后翻译单测）；并发塌缩为单线程确定性顺序管线；
//! 金额 `i64` 定点；任何影响输出的迭代走确定序，禁用 `HashMap` 迭代序。

pub mod collections;
pub mod core;
