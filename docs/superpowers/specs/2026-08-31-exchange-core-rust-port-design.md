# exchange-core Rust 移植 · 设计文档

- 日期：2026-08-31
- 状态：已批准，待出实施计划
- 作者：ming + Claude
- 参考实现（同血统骨架）：https://github.com/llc-993/matching-core

## 1. 目标与非目标

### 目标
在仓库内新建独立 cargo workspace `exchange-core-rs/`，从现有 Java `exchange-core`（`exchange.core2` 包，~28.5k 行 / 179 文件）**逐类翻译，全量对等移植**整个撮合引擎，包含全部业务扩展：

- 现货撮合（GTC / IOC / FOK 等）
- 期货头寸 / 保证金 / 统一账户风控
- loan 借贷 + loan 利率定价
- 保险基金强平（LIF）、ADL 自动减仓
- 资金费（funding fee）
- 内部转账（internal transfer）
- snapshot / journal 状态持久化

### 非目标（首期不做）
- **不碰上游**：`raft-exchange-server` / `raft-exchange-client` / `jraft-core` 完全不动。
- **不做 JNI / FFI / sidecar 集成**：Rust 版是独立并存的 crate，不被 Java server 调用。
- **不做 golden 录制 / 差分 harness**：系统未上线、无流量可录，验证网只靠翻译单测。（见 §7）
- **不做性能并行**：首期单线程确定性优先，多线程分片是后续可选优化。
- **不要求 snapshot 格式与 Java Raft 互通**。

## 2. 交付形态

一个独立 cargo workspace，与 Java 模块并列，互不干扰：

```
exchange-core-rs/
├─ Cargo.toml                (workspace)
├─ crates/
│  ├─ api/          命令 + 结果 + 报告 DTO      ← core/common/api/**  (46+19 文件)
│  ├─ collections/  有序容器（ART TODO）        ← collections/**
│  ├─ orderbook/    IOrderBook + Direct/Naive    ← core/orderbook/**
│  ├─ processors/   RiskEngine / MatchingRouter /
│  │                loan / liquidation / funding ← core/processors/**
│  ├─ engine/       ExchangeCore 编排 + ExchangeApi ← core/*.java
│  └─ snapshot/     序列化 / 反序列化 + journal   ← snapshot 相关
```

crate 边界对齐 Java 子包边界，翻译时"逐文件对照"，review 与定位都方便。
（原计划的 `harness/` crate 已砍。）

## 3. 推进方式（方式 B：先写引擎，后翻译测试）

**决策**：一次性把整个 Rust 引擎写完，再翻译单测。

- 优点：Rust 代码可一次性写地道，不被 Java 结构反复牵制。
- 已知风险：正确性验证集中在后期，第一个"测试绿"来得晚。**接受此风险**——系统未上线，无生产压力。
- §1 里选过的"关键路径逐位差分"并入后期测试阶段，以翻译单测 + proptest 形式落地（不再单独建 harness）。

## 4. 并发模型（核心结构决策）

Java 侧是 LMAX Disruptor 五段流水线（多线程分片，为吞吐）：

```
Grouping → [R1 risk-hold(按 uid 分片) ∥ Journal] → ME 撮合(按 symbol 分片) → R2 risk-release(按 uid 分片)
```

其结果**本身是确定的**。Rust 侧首期**塌缩为单线程确定性顺序管线**，逻辑上完整复刻五段顺序，但顺序执行、不起线程：

```rust
for cmd in group {                       // grouping 保留：影响撮合/风控的批边界语义
    let rshard = uid % risk_shards;
    let sshard = symbol % matching_shards;
    risk[rshard].pre_process(cmd);       // R1 hold
    journal.write(cmd);                  // J（可选）
    matching[sshard].process(cmd);       // ME
    risk[rshard].risk_release(cmd);      // R2 release（含 R2Sync 跨分片同步）
}
```

**约束**：
- 分片编号照搬（`uid % N`、`symbol % M`）。跨分片 OI / 守恒 / `needSyncR2ForSymbol` / ADL 等不变量依赖分片归属，**不得塌缩掉分片概念**，只塌缩线程。
- 单线程 = 天然确定、无数据竞争、无 GC，最易与 Java 期望值对齐。
- 后续如需吞吐，再按 shard 拆线程（可选优化，不进首期）。

这是整个移植语义风险最高处：等于把"Disruptor 编排"翻译成"确定性顺序循环"。

## 5. 数据结构映射与确定性底座

| Java | Rust 首期 | 说明 |
|------|----------|------|
| 金额 `long` 定点 | `i64`（中间运算 `i128` 防溢出） | 全程无浮点，与 Java 一致 |
| `LongAdaptiveRadixTreeMap`（价位 / 订单索引） | `BTreeMap<i64, _>` | 均有序遍历 → 撮合价位顺序一致、结果逐位相同。ART 是性能优化，首期不移植，留 TODO |
| `IntObjectHashMap` / `LongObjectHashMap`（uid→profile、symbol→spec 等） | `BTreeMap` 或"输出前排序 key" | ⚠ 首期最大确定性坑：引擎遍历这些 map 产出事件（清算扫描、报表）时，Java hash 序与 Rust 不同会分叉；一律用有序容器根治 |
| `ObjectsPool`（对象池避 GC） | 直接删除 | Rust 所有权无 GC 压力，池子纯性能手段、语义无副作用 |

**铁律**：Rust 引擎中任何影响输出的迭代，必须走确定序（`BTreeMap` / 显式 `sort`），绝不用 `HashMap` 迭代序。这是"翻译单测可对齐 Java 期望值"的地基。

## 6. snapshot / journal

- Java 侧为 proto 序列化（`MemorySerializationProcessor` + 各 Restorer）。
- 因不与 Java Raft 互通，首期**不要求格式兼容**：Rust 侧用 `serde` + `bincode`（或 proto + prost）重做 snapshot/restore，保持**逻辑结构对等**（快照 orderbook + risk state + loan/position 账本）。
- journal（WAL）同为 `snapshot` crate 内一块。
- 优先级低于引擎核心：可先留桩（`todo!()`），引擎主体跑通后回填。

## 7. 测试与验证

唯一验证网 = **翻译现有 1124 个 exchange-core 单测**：

- JUnit5 + AssertJ → Rust `#[test]` + `assert_eq!`；Java 单测里的期望值即对等基准。
- 不变量（资金守恒、撮合价格/时间优先级、强平终局）另用 `proptest` 随机命令流兜底。
- 顺序（方式 B）：引擎主体全部写完 → 再翻译单测。实操上单个 crate 编译通过即可翻译其对应测试，但这是执行细节，不改 B 的大结构。

## 8. 主要风险

1. **并发塌缩语义偏差**（§4）：R1→ME→R2 顺序、grouping 批边界、R2Sync 跨分片同步若复刻不精确，会在 loan LIF / ADL 这类涌现性路径上分叉。缓解：分片归属与阶段顺序逐行对照 Java。
2. **确定性坑**（§5）：任何残留的 `HashMap` 输出序都会导致对拍失败。缓解：全量有序容器 + code review 专项检查。
3. **验证后置**（方式 B）：几万行写完才知对错。缓解：crate 一编译通过即翻译其单测，尽早局部见绿。
4. **业务扩展无参考**：loan / LIF / ADL / 统一账户在参考仓库中不存在，须从 Java 源码从零翻译。缓解：这些正是逐类对照最需谨慎的部分，实施计划中单独立阶段。

## 9. 后续（本文档之外）
- 出实施计划（writing-plans 技能）：按 crate / 阶段拆分可执行任务。
- 首期之外的可选项：ART 移植、多线程分片、snapshot 与 Java 格式兼容、JNI/sidecar 集成。
