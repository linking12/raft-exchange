# exchange-core-rs

Java `exchange-core`（`exchange.core2` 包）的 Rust 全量对等移植。

- 设计文档: [`../docs/superpowers/specs/2026-08-31-exchange-core-rust-port-design.md`](../docs/superpowers/specs/2026-08-31-exchange-core-rust-port-design.md)
- 参考实现（同血统骨架）: https://github.com/llc-993/matching-core
- 上游 `raft-exchange-server` / `client` 首期不集成，独立并存。

## 布局（单 crate + `src/` 分 module，对齐 Java 子包 & matching-core）

| module | 职责 | 对应 Java |
|--------|------|-----------|
| `src/api` | 命令 / 结果 / 报告 DTO | `core/common/api/**` |
| `src/collections` | 有序容器（ART 后续） | `collections/**` |
| `src/orderbook` | IOrderBook + Direct/Naive | `core/orderbook/**` |
| `src/processors` | RiskEngine / MatchingRouter / loan / liquidation / funding | `core/processors/**` |
| `src/engine` | ExchangeCore 编排（确定性单线程管线）+ ExchangeApi | `core/*.java` |
| `src/snapshot` | 序列化 / 反序列化 + journal | snapshot 相关 |

exchange-core 本身即单一 module，故 Rust 侧亦为**单 crate**，内部用 `mod` 分域。

## 首期原则

- 方式 B：先写完整引擎，再翻译单测。
- 并发塌缩为**单线程确定性顺序管线**，保留分片归属（`uid % N`、`symbol % M`）。
- 金额 `i64` 定点、中间 `i128` 防溢出；**任何影响输出的迭代走确定序，禁用 `HashMap` 迭代序**。
- 不做 JNI / sidecar / golden harness / 多线程并行（均为后续可选）。
