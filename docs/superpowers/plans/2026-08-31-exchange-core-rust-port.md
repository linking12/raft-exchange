# exchange-core Rust 移植 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Java `exchange-core`（`exchange.core2`，~28.5k 行）全量对等移植为独立 Rust crate `exchange-core-rs`，不集成上游。

**Architecture:** 单 crate、`src/` 内按域分 module；并发从 Disruptor 五段塌缩为单线程确定性顺序管线；金额 `i64` 定点、有序容器保证输出确定性。整个移植拆 7 个阶段，**本计划只详列第 1 阶段（vanilla 订单簿 Naive）**；每个后续阶段到达时各出一份独立详细计划（scope 太大，不合并）。

**Tech Stack:** Rust 2021 (rustc ≥ 1.75)、`std::collections::BTreeMap`、`serde`、`proptest`（后期）。

**Spec:** `docs/superpowers/specs/2026-08-31-exchange-core-rust-port-design.md`

## Global Constraints

- 单 crate `exchange-core-rs`，`src/{api,collections,orderbook,processors,engine,snapshot}/mod.rs` 已存在（提交 `9f03dbfc`）。
- 金额、价格、数量一律 `i64` 定点；中间乘除用 `i128` 防溢出；**全程禁用浮点**。
- 任何影响输出的迭代必须走确定序（`BTreeMap` / 显式 `sort`）；**禁用 `std::collections::HashMap` 的迭代序参与输出**。
- 逐类对照 Java 源：每个 Rust 文件顶部注释标注其对应的 Java 类路径。
- 不写 JNI / sidecar / golden harness / 多线程（均为首期外）。
- 提交信息用中文、Conventional Commits；不擅自 push。

---

## 阶段路线图（7 阶段，逐阶段各出详细计划）

| 阶段 | 交付物（可独立测试） | 验收门槛 |
|------|---------------------|----------|
| **P1（本计划）** | `api` 地基类型 + `orderbook` **Naive** 实现 | 端口 Java `OrderBookNaiveImplTest` 语义的单测全绿；订单簿独立可跑（无需 risk/engine） |
| P2 | `orderbook` **Direct** 实现 | Direct 与 Naive 在随机命令流下 L2/成交逐笔一致（proptest 对拍） |
| P3 | `RiskEngine`（现货余额 hold/release）+ `MatchingEngineRouter` + `engine` 确定性管线 | vanilla 现货端到端：下单→撮合→余额变动；资金守恒断言 |
| P4 | 期货 / 统一账户风控（头寸 / 保证金 / marginRatio / PositionMode） | 期货开平仓、保证金、盈亏单测全绿 |
| P5 | loan 借贷 + `LoanRatePricingProcessor` | loan 开/还/抵押、利率定价单测全绿 |
| P6 | 保险基金强平（LIF）+ ADL + 资金费 + 内部转账 + BinaryCommands | 对应单测全绿；跨分片 OI 守恒、LIF 终局不变量成立 |
| P7 | `snapshot` / journal 回填 + 全量单测翻译收尾 + `proptest` 不变量 | 全部翻译单测绿；快照 round-trip 一致 |

> 依赖顺序单向：P1→P2→P3→…→P7。每阶段完成即有一个"已验证对等"的可测子集。

---

# 第 1 阶段：api 地基类型 + Naive 订单簿

**范围**：只做订单簿本体（对应 Java `OrderBookNaiveImpl`，663 行）及其直接依赖的 api 类型。**不含** RiskEngine / engine 管线 / 期货 / loan——那些在 P3+。此阶段对标 Java 的 `OrderBookNaiveImplTest`：订单簿可脱离引擎独立测试。

**文件规划**：

- 建 `src/api/enums.rs` — OrderType / OrderAction / MatcherEventType / CommandResultCode
- 建 `src/api/order.rs` — Order（挂单）
- 建 `src/api/command.rs` — OrderCommand（引擎命令，P1 只用其撮合相关字段）
- 建 `src/api/event.rs` — MatcherTradeEvent（撮合事件）
- 建 `src/api/l2.rs` — L2MarketData（盘口快照）
- 改 `src/api/mod.rs` — 声明并 re-export 上述子模块
- 建 `src/orderbook/naive.rs` — OrderBookNaive + OrdersBucketNaive
- 建 `src/orderbook/book.rs` — `IOrderBook` trait
- 改 `src/orderbook/mod.rs` — 声明子模块
- 测试写在各文件内 `#[cfg(test)] mod tests`（Rust 惯例，对齐单 crate）

---

### Task 1：api 地基枚举

**Files:**
- Create: `src/api/enums.rs`
- Modify: `src/api/mod.rs`

**Interfaces:**
- Produces: `enum OrderType { Gtc, Ioc, IocBudget, Fok, FokBudget }`（对应 Java 码 0..4）；`enum OrderAction { Ask, Bid }`（码 0/1，含 `opposite()`）；`enum MatcherEventType { Trade, Reject, Reduce, BinaryEvent }`；`enum CommandResultCode`（P1 仅需 `Success=100, ValidForMatchingEngine=1, MatchingUnknownOrderId=-3002, MatchingUnsupportedCommand=-3004`）。所有枚举 `#[derive(Debug, Clone, Copy, PartialEq, Eq)]`，并有 `from_code(i8/i32)->Option<Self>` / `code()->` 映射，码值与 Java 完全一致。

- [ ] **Step 1：写失败测试**（`src/api/enums.rs` 末尾）

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn order_type_codes_match_java() {
        assert_eq!(OrderType::Gtc.code(), 0);
        assert_eq!(OrderType::Ioc.code(), 1);
        assert_eq!(OrderType::IocBudget.code(), 2);
        assert_eq!(OrderType::Fok.code(), 3);
        assert_eq!(OrderType::FokBudget.code(), 4);
        assert_eq!(OrderType::from_code(3), Some(OrderType::Fok));
        assert_eq!(OrderType::from_code(9), None);
    }

    #[test]
    fn order_action_opposite() {
        assert_eq!(OrderAction::Ask.opposite(), OrderAction::Bid);
        assert_eq!(OrderAction::Bid.opposite(), OrderAction::Ask);
        assert_eq!(OrderAction::Bid.code(), 1);
    }
}
```

- [ ] **Step 2：运行确认失败**

Run: `cargo test --lib api::enums`
Expected: 编译失败（`OrderType` 等未定义）。

- [ ] **Step 3：最小实现**（`src/api/enums.rs` 顶部）

```rust
//! 对应 Java: exchange.core2.core.common.{OrderType, OrderAction, MatcherEventType}
//! 及 core.common.cmd.CommandResultCode。码值与 Java 严格一致。

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OrderType { Gtc, Ioc, IocBudget, Fok, FokBudget }

impl OrderType {
    pub fn code(self) -> i8 {
        match self {
            OrderType::Gtc => 0, OrderType::Ioc => 1, OrderType::IocBudget => 2,
            OrderType::Fok => 3, OrderType::FokBudget => 4,
        }
    }
    pub fn from_code(c: i8) -> Option<Self> {
        Some(match c {
            0 => OrderType::Gtc, 1 => OrderType::Ioc, 2 => OrderType::IocBudget,
            3 => OrderType::Fok, 4 => OrderType::FokBudget, _ => return None,
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OrderAction { Ask, Bid }

impl OrderAction {
    pub fn code(self) -> i8 { match self { OrderAction::Ask => 0, OrderAction::Bid => 1 } }
    pub fn from_code(c: i8) -> Option<Self> {
        match c { 0 => Some(OrderAction::Ask), 1 => Some(OrderAction::Bid), _ => None }
    }
    pub fn opposite(self) -> Self {
        match self { OrderAction::Ask => OrderAction::Bid, OrderAction::Bid => OrderAction::Ask }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MatcherEventType { Trade, Reject, Reduce, BinaryEvent }

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CommandResultCode {
    ValidForMatchingEngine, // 1
    Success,                // 100
    MatchingUnknownOrderId, // -3002
    MatchingUnsupportedCommand, // -3004
}

impl CommandResultCode {
    pub fn code(self) -> i32 {
        match self {
            CommandResultCode::ValidForMatchingEngine => 1,
            CommandResultCode::Success => 100,
            CommandResultCode::MatchingUnknownOrderId => -3002,
            CommandResultCode::MatchingUnsupportedCommand => -3004,
        }
    }
}
```

> 执行者注：P1 只需上述 CommandResultCode 子集；完整枚举（loan/期货错误码等）在对应阶段补齐——**在此文件里增量添加，不要另建枚举**。

- [ ] **Step 4：`src/api/mod.rs` 声明子模块**

```rust
//! 命令 / 结果 / 报告 DTO。对应 Java exchange.core2.core.common.api.** + cmd + common。
pub mod enums;
pub use enums::{CommandResultCode, MatcherEventType, OrderAction, OrderType};
```

- [ ] **Step 5：运行确认通过**

Run: `cargo test --lib api::enums`
Expected: PASS（2 tests）。

- [ ] **Step 6：提交**

```bash
git add exchange-core-rs/src/api/enums.rs exchange-core-rs/src/api/mod.rs
git commit -m "feat(api): 移植 OrderType/OrderAction/MatcherEventType/CommandResultCode 枚举"
```

---

### Task 2：Order / MatcherTradeEvent / OrderCommand 结构

**Files:**
- Create: `src/api/order.rs`, `src/api/event.rs`, `src/api/command.rs`
- Modify: `src/api/mod.rs`

**Interfaces:**
- Consumes: Task 1 的枚举。
- Produces:
  - `struct Order { order_id: i64, price: i64, size: i64, filled: i64, reserve_bid_price: i64, action: OrderAction, uid: i64, timestamp: i64 }`（对应 Java `common.Order`，撮合所需字段）。
  - `struct MatcherTradeEvent { event_type: MatcherEventType, active_order_completed: bool, maker_order_id: i64, maker_order_completed: bool, price: i64, size: i64, bid_gt_ask: bool, next: Option<Box<MatcherTradeEvent>> }`（对应 Java `common.MatcherTradeEvent`，撮合链表事件）。
  - `struct OrderCommand`（对应 `cmd.OrderCommand`，字段名/类型对齐：`order_id:i64, symbol:i32, price:i64, size:i64, reserve_bid_price:i64, action:Option<OrderAction>, order_type:Option<OrderType>, uid:i64, timestamp:i64, order_flags:i32, result_code:Option<CommandResultCode>, matcher_event:Option<Box<MatcherTradeEvent>>, market_data:Option<L2MarketData>`）；含 `fn filled(&self)->i64`。P1 先放撮合相关字段，风控字段（leverage/margin 等）在 P3+ 增补。

- [ ] **Step 1：写失败测试**（`src/api/order.rs`）

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::enums::OrderAction;

    #[test]
    fn order_remaining_size() {
        let o = Order { order_id: 1, price: 100, size: 10, filled: 3,
            reserve_bid_price: 0, action: OrderAction::Bid, uid: 7, timestamp: 0 };
        assert_eq!(o.remaining(), 7);
    }
}
```

- [ ] **Step 2：运行确认失败**

Run: `cargo test --lib api::order`
Expected: 编译失败（`Order` 未定义）。

- [ ] **Step 3：实现三个结构**

`src/api/order.rs`：
```rust
//! 对应 Java: exchange.core2.core.common.Order（撮合所需字段子集）
use crate::api::enums::OrderAction;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Order {
    pub order_id: i64,
    pub price: i64,
    pub size: i64,
    pub filled: i64,
    pub reserve_bid_price: i64,
    pub action: OrderAction,
    pub uid: i64,
    pub timestamp: i64,
}

impl Order {
    /// 未成交量 = size - filled（对应 Java Order.size - Order.filled）
    pub fn remaining(&self) -> i64 { self.size - self.filled }
}
```

`src/api/event.rs`：
```rust
//! 对应 Java: exchange.core2.core.common.MatcherTradeEvent（撮合事件单链表）
use crate::api::enums::MatcherEventType;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MatcherTradeEvent {
    pub event_type: MatcherEventType,
    pub active_order_completed: bool,
    pub maker_order_id: i64,
    pub maker_order_completed: bool,
    pub price: i64,
    pub size: i64,
    pub bid_gt_ask: bool,
    pub next: Option<Box<MatcherTradeEvent>>,
}
```

`src/api/command.rs`：
```rust
//! 对应 Java: exchange.core2.core.common.cmd.OrderCommand（P1：撮合相关字段）
use crate::api::enums::{CommandResultCode, OrderAction, OrderType};
use crate::api::event::MatcherTradeEvent;
use crate::api::l2::L2MarketData;

#[derive(Debug, Clone, Default)]
pub struct OrderCommand {
    pub order_id: i64,
    pub symbol: i32,
    pub price: i64,
    pub size: i64,
    pub reserve_bid_price: i64,
    pub action: Option<OrderAction>,
    pub order_type: Option<OrderType>,
    pub uid: i64,
    pub timestamp: i64,
    pub order_flags: i32,
    pub result_code: Option<CommandResultCode>,
    pub matcher_event: Option<Box<MatcherTradeEvent>>,
    pub market_data: Option<L2MarketData>,
}
```

> 执行者注：`OrderCommand` 需 `Default`；`Option<OrderAction>` 等无法 derive Default 的字段用 `#[derive(Default)]` 配合 `Option`（默认 `None`）即可。`L2MarketData` 在 Task 6 定义，此处先 `use`，若顺序上未就绪，先在 `l2.rs` 放空 struct 占位。

- [ ] **Step 4：`src/api/mod.rs` 增声明**

```rust
pub mod command;
pub mod event;
pub mod l2;
pub mod order;
pub use command::OrderCommand;
pub use event::MatcherTradeEvent;
pub use order::Order;
```

- [ ] **Step 5：运行确认通过**

Run: `cargo test --lib api::order`
Expected: PASS。

- [ ] **Step 6：提交**

```bash
git add exchange-core-rs/src/api/
git commit -m "feat(api): 移植 Order/MatcherTradeEvent/OrderCommand 结构"
```

---

### Task 3：`IOrderBook` trait + OrdersBucketNaive（价位桶）

**Files:**
- Create: `src/orderbook/book.rs`, `src/orderbook/naive.rs`
- Modify: `src/orderbook/mod.rs`

**Java 参照:** `orderbook/IOrderBook.java`（trait 表面）、`orderbook/OrdersBucketNaive.java`（254 行，单价位 FIFO 桶）。

**Interfaces:**
- Produces:
  - `trait IOrderBook`：`fn new_order(&mut self, cmd: &mut OrderCommand); fn cancel_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode; fn reduce_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode; fn move_order(&mut self, cmd: &mut OrderCommand) -> CommandResultCode; fn fill_l2(&self, size: i32) -> L2MarketData; fn state_hash(&self) -> i32;`
  - `struct OrdersBucketNaive { price: i64, total_volume: i64, entries: BTreeMap<i64, Order> }`——`entries` 以插入序（用单调递增 seq 作 key）保证 FIFO 时间优先；提供 `put(Order)` / `remove(order_id) -> Option<Order>` / `match_forward(...)`。

- [ ] **Step 1：写失败测试**（`src/orderbook/naive.rs`）——桶内 FIFO + 撮合数量

```rust
#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::enums::OrderAction;
    use crate::api::order::Order;

    fn mk(id: i64, size: i64) -> Order {
        Order { order_id: id, price: 100, size, filled: 0,
            reserve_bid_price: 0, action: OrderAction::Ask, uid: id, timestamp: id }
    }

    #[test]
    fn bucket_fifo_and_total_volume() {
        let mut b = OrdersBucketNaive::new(100);
        b.put(mk(1, 10));
        b.put(mk(2, 5));
        assert_eq!(b.total_volume(), 15);
        // 先进先出：撮合 12 → 全吃 order1(10) + order2 部分(2)
        let mut collected: Vec<(i64, i64)> = vec![]; // (maker_id, trade_size)
        let remaining = b.match_forward(12, &mut |maker_id, sz, _completed| {
            collected.push((maker_id, sz));
        });
        assert_eq!(remaining, 0); // 请求量全部撮合
        assert_eq!(collected, vec![(1, 10), (2, 2)]);
        assert_eq!(b.total_volume(), 3);
    }
}
```

- [ ] **Step 2：运行确认失败**

Run: `cargo test --lib orderbook::naive`
Expected: 编译失败。

- [ ] **Step 3：实现 trait 与桶**

`src/orderbook/book.rs`：按上面 Interfaces 定义 `trait IOrderBook`（方法签名照抄，body 由实现类型提供）。文件头注释标 `//! 对应 Java: orderbook/IOrderBook.java`。

`src/orderbook/naive.rs`：实现 `OrdersBucketNaive`。要点（对照 `OrdersBucketNaive.java`）：
- `entries: BTreeMap<i64, Order>`，key = 自增 `seq`（保证时间优先 FIFO）；另存 `order_id -> seq` 索引便于 O(log n) 删除。
- `match_forward(mut to_collect: i64, on_trade: &mut impl FnMut(i64,i64,bool))`：按 seq 升序遍历，每单成交 `min(remaining, order.remaining())`，回调 `(maker_id, trade_size, maker_completed)`；撮满或桶空则停；返回**剩余未撮合请求量**。移除已完成的 maker，更新 `total_volume`。

```rust
use std::collections::BTreeMap;
use crate::api::order::Order;

pub struct OrdersBucketNaive {
    price: i64,
    total_volume: i64,
    next_seq: i64,
    entries: BTreeMap<i64, Order>,      // seq -> order（FIFO）
    id_to_seq: std::collections::BTreeMap<i64, i64>, // order_id -> seq
}

impl OrdersBucketNaive {
    pub fn new(price: i64) -> Self {
        Self { price, total_volume: 0, next_seq: 0,
            entries: BTreeMap::new(), id_to_seq: BTreeMap::new() }
    }
    pub fn price(&self) -> i64 { self.price }
    pub fn total_volume(&self) -> i64 { self.total_volume }
    pub fn is_empty(&self) -> bool { self.entries.is_empty() }

    pub fn put(&mut self, order: Order) {
        self.total_volume += order.remaining();
        let seq = self.next_seq; self.next_seq += 1;
        self.id_to_seq.insert(order.order_id, seq);
        self.entries.insert(seq, order);
    }

    pub fn remove(&mut self, order_id: i64) -> Option<Order> {
        let seq = self.id_to_seq.remove(&order_id)?;
        let o = self.entries.remove(&seq)?;
        self.total_volume -= o.remaining();
        Some(o)
    }

    /// 从桶头 FIFO 撮合 `to_collect`，返回剩余未撮合量。
    pub fn match_forward(&mut self, mut to_collect: i64,
                         on_trade: &mut impl FnMut(i64, i64, bool)) -> i64 {
        let seqs: Vec<i64> = self.entries.keys().copied().collect();
        for seq in seqs {
            if to_collect == 0 { break; }
            let (maker_id, trade, completed) = {
                let o = self.entries.get_mut(&seq).unwrap();
                let avail = o.remaining();
                let trade = to_collect.min(avail);
                o.filled += trade;
                (o.order_id, trade, o.remaining() == 0)
            };
            to_collect -= trade;
            self.total_volume -= trade;
            on_trade(maker_id, trade, completed);
            if completed { self.entries.remove(&seq); self.id_to_seq.remove(&maker_id); }
        }
        to_collect
    }
}
```

> 执行者注：与 Java `OrdersBucketNaive.match` 逐行对照，尤其 `total_volume` 增减时机与"完成即移除"。这是 P1 唯一有算法的部分，必须对着 Java 核对边界。

- [ ] **Step 4：`src/orderbook/mod.rs` 声明**

```rust
pub mod book;
pub mod naive;
pub use book::IOrderBook;
pub use naive::OrderBookNaive;
```
（`OrderBookNaive` 在 Task 4 定义；此处先声明，Task 4 前该 re-export 可临时注释。）

- [ ] **Step 5：运行确认通过**

Run: `cargo test --lib orderbook::naive`
Expected: PASS。

- [ ] **Step 6：提交**

```bash
git add exchange-core-rs/src/orderbook/
git commit -m "feat(orderbook): IOrderBook trait + OrdersBucketNaive 价位桶"
```

---

### Task 4：OrderBookNaive — 下单 GTC + 撮合主循环

**Files:**
- Modify: `src/orderbook/naive.rs`（加 `OrderBookNaive`）

**Java 参照:** `orderbook/OrderBookNaiveImpl.java` 的 `newOrder` / `matchInstantly` / GTC 挂单路径。

**Interfaces:**
- Consumes: Task 3 的 `OrdersBucketNaive`、`IOrderBook`。
- Produces: `struct OrderBookNaive { ask_buckets: BTreeMap<i64, OrdersBucketNaive>, bid_buckets: BTreeMap<i64, OrdersBucketNaive>, id_index: BTreeMap<i64, (OrderAction, i64)> }`，实现 `IOrderBook::new_order`。撮合产生的事件挂到 `cmd.matcher_event`（单链表）。asks 升序、bids 降序（bids 用 `BTreeMap` 反向遍历或按 `-price` 键）。

- [ ] **Step 1：写失败测试**——两单撮合成一笔 trade

```rust
#[cfg(test)]
mod ob_tests {
    use super::*;
    use crate::api::enums::{OrderAction, OrderType};
    use crate::api::command::OrderCommand;

    fn place(book: &mut OrderBookNaive, id: i64, act: OrderAction, price: i64, size: i64) -> OrderCommand {
        let mut cmd = OrderCommand { order_id: id, symbol: 1, price, size,
            action: Some(act), order_type: Some(OrderType::Gtc), uid: id, ..Default::default() };
        book.new_order(&mut cmd);
        cmd
    }

    #[test]
    fn two_orders_cross_into_one_trade() {
        let mut book = OrderBookNaive::new();
        place(&mut book, 1, OrderAction::Ask, 100, 10); // 挂卖
        let taker = place(&mut book, 2, OrderAction::Bid, 100, 6); // 吃 6
        let ev = taker.matcher_event.as_ref().expect("应有成交事件");
        assert_eq!(ev.event_type, crate::api::enums::MatcherEventType::Trade);
        assert_eq!(ev.maker_order_id, 1);
        assert_eq!(ev.price, 100);
        assert_eq!(ev.size, 6);
        assert!(ev.next.is_none()); // 只撮一笔
        // 卖单剩 4 仍在簿上
        let l2 = book.fill_l2(10);
        assert_eq!(l2.ask_prices, vec![100]);
        assert_eq!(l2.ask_volumes, vec![4]);
    }
}
```

- [ ] **Step 2：运行确认失败**

Run: `cargo test --lib orderbook::naive::ob_tests`
Expected: 编译失败（`OrderBookNaive` 未定义）。

- [ ] **Step 3：实现 `new_order` + GTC 挂单 + 撮合**

对照 `OrderBookNaiveImpl.newOrder`：先按 taker 方向找对手侧可成交桶（bid taker 吃 asks 中价 ≤ 限价、从低到高；ask taker 吃 bids 中价 ≥ 限价、从高到低），逐桶 `match_forward`，把每笔回调转成 `MatcherTradeEvent` 链到 `cmd.matcher_event`；撮完若为 GTC 且有剩余则挂入本方桶，并写 `id_index`。**先只实现 GTC**（IOC/FOK 在 Task 5）。撮合价取 maker 挂单价（对照 Java）。

> 提供该方法的完整 Rust 由执行者对照 Java `newOrder` 逐段翻译；测试（Step 1）是其行为契约。关键不变量：撮合价=maker 价；事件链顺序=撮合发生顺序；`total_volume`/`id_index` 与簿一致。

- [ ] **Step 4：运行确认通过**

Run: `cargo test --lib orderbook::naive`
Expected: PASS（含 Task 3 用例）。

- [ ] **Step 5：提交**

```bash
git add exchange-core-rs/src/orderbook/naive.rs
git commit -m "feat(orderbook): OrderBookNaive 下单(GTC)与撮合主循环"
```

---

### Task 5：OrderBookNaive — IOC / FOK

**Files:** Modify `src/orderbook/naive.rs`

**Java 参照:** `OrderBookNaiveImpl` 中 IOC（撮合后余量丢弃、不挂簿）、FOK（`canMatchInstantlyBudget` 全量可成才成交，否则整单拒绝，`REJECT` 事件）逻辑；含 `IOC_BUDGET`/`FOK_BUDGET` 的总额上限。

**Interfaces:** Consumes Task 4；Produces：`new_order` 对 `OrderType::{Ioc,IocBudget,Fok,FokBudget}` 的分支。

- [ ] **Step 1：写失败测试**（IOC 余量丢弃、FOK 不满则拒绝）

```rust
#[test]
fn ioc_discards_remainder() {
    let mut book = OrderBookNaive::new();
    place(&mut book, 1, OrderAction::Ask, 100, 5);
    let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 100, size: 10,
        action: Some(OrderAction::Bid), order_type: Some(OrderType::Ioc), uid: 2, ..Default::default() };
    book.new_order(&mut cmd);
    // 成交 5，剩 5 丢弃、不挂簿
    assert_eq!(book.fill_l2(10).bid_prices.len(), 0);
    assert_eq!(book.fill_l2(10).ask_prices.len(), 0);
}

#[test]
fn fok_all_or_nothing_rejects() {
    let mut book = OrderBookNaive::new();
    place(&mut book, 1, OrderAction::Ask, 100, 5);
    let mut cmd = OrderCommand { order_id: 2, symbol: 1, price: 100, size: 10,
        action: Some(OrderAction::Bid), order_type: Some(OrderType::Fok), uid: 2, ..Default::default() };
    book.new_order(&mut cmd);
    // 不足量 → 整单拒绝：无成交、卖单仍在
    let ev = cmd.matcher_event.as_ref().unwrap();
    assert_eq!(ev.event_type, crate::api::enums::MatcherEventType::Reject);
    assert_eq!(book.fill_l2(10).ask_volumes, vec![5]);
}
```

- [ ] **Step 2：运行确认失败** — `cargo test --lib orderbook::naive`，Expected: 新用例 FAIL。
- [ ] **Step 3：实现 IOC/FOK 分支**（对照 Java：FOK 先探测可成交总量，够则成交、不够则发 `REJECT` 事件且不改簿；IOC 正常撮合后不挂剩余）。
- [ ] **Step 4：运行确认通过** — Expected: PASS。
- [ ] **Step 5：提交**

```bash
git commit -am "feat(orderbook): OrderBookNaive 支持 IOC/FOK"
```

---

### Task 6：OrderBookNaive — cancel / reduce / move + L2MarketData

**Files:** Create `src/api/l2.rs`；Modify `src/orderbook/naive.rs`

**Java 参照:** `OrderBookNaiveImpl.cancelOrder/reduceOrder/moveOrder`、`common.L2MarketData`。

**Interfaces:**
- Produces: `struct L2MarketData { ask_prices: Vec<i64>, ask_volumes: Vec<i64>, bid_prices: Vec<i64>, bid_volumes: Vec<i64> }`；`OrderBookNaive::{cancel_order,reduce_order,move_order,fill_l2}`。cancel/未知 id 返回 `MatchingUnknownOrderId`。

- [ ] **Step 1：写失败测试**（cancel 释放、move 改价重排、L2 有序）

```rust
#[test]
fn cancel_unknown_returns_error() {
    let mut book = OrderBookNaive::new();
    let mut cmd = OrderCommand { order_id: 999, symbol: 1, uid: 1, ..Default::default() };
    assert_eq!(book.cancel_order(&mut cmd), crate::api::enums::CommandResultCode::MatchingUnknownOrderId);
}

#[test]
fn l2_prices_sorted() {
    let mut book = OrderBookNaive::new();
    place(&mut book, 1, OrderAction::Ask, 102, 1);
    place(&mut book, 2, OrderAction::Ask, 100, 1);
    place(&mut book, 3, OrderAction::Ask, 101, 1);
    let l2 = book.fill_l2(10);
    assert_eq!(l2.ask_prices, vec![100, 101, 102]); // 卖侧升序
}
```

- [ ] **Step 2：运行确认失败** — Expected: FAIL。
- [ ] **Step 3：实现** cancel（按 `id_index` 定位桶并移除、桶空则删桶）、reduce（减量/减到 0 即撤）、move（撤旧价、按新价重新走 `new_order` 撮合/挂单）、`fill_l2`（asks 升序 / bids 降序取前 `size` 档）。
- [ ] **Step 4：运行确认通过** — Expected: PASS。
- [ ] **Step 5：提交**

```bash
git add exchange-core-rs/src/api/l2.rs exchange-core-rs/src/orderbook/naive.rs
git commit -m "feat(orderbook): cancel/reduce/move 与 L2MarketData"
```

---

### Task 7：state_hash + 翻译 Java OrderBook 单测收尾

**Files:** Modify `src/orderbook/naive.rs`；参照 Java `OrderBookNaiveImplTest` / `OrderBookBaseTest`。

**Interfaces:** Produces: `OrderBookNaive::state_hash()->i32`（确定性哈希，遍历有序桶）。

- [ ] **Step 1：定位 Java 测试** — 读 `exchange-core/src/test/java/.../orderbook/OrderBookNaiveImplTest.java` 及基类，列出全部用例名。
- [ ] **Step 2：逐条翻译**为 `#[test]`（放 `src/orderbook/naive.rs` 的 `#[cfg(test)]`），断言值直接取 Java 期望。**每翻 3-5 条跑一次** `cargo test --lib orderbook`。
- [ ] **Step 3：实现 `state_hash`** 并加一条"相同操作序 → 相同 hash、不同 → 不同 hash"的测试。
- [ ] **Step 4：全绿确认** — `cargo test --lib orderbook`，Expected: 全部 PASS。
- [ ] **Step 5：提交**

```bash
git commit -am "test(orderbook): 翻译 OrderBookNaiveImplTest 全量用例 + state_hash"
```

---

## 第 1 阶段完成定义（DoD）

- `cargo build` 与 `cargo test --lib`（orderbook + api）全绿。
- `OrderBookNaive` 覆盖 GTC/IOC/FOK 下单、cancel/reduce/move、L2、state_hash，语义对齐 Java `OrderBookNaiveImplTest`。
- 无 `HashMap` 参与输出；金额全 `i64`。
- **不含** risk/engine/期货/loan——进入 P2（Direct 实现 + 与 Naive 对拍）另出计划。

---

## 自查（对照 spec）

- **spec §2 交付形态**：单 crate + src/ 分 module —— P1 全在此结构内。✓
- **spec §4 并发**：P1 是纯订单簿、无管线，塌缩模型在 P3 engine 落地——路线图 P3 覆盖。✓
- **spec §5 确定性**：i64 定点 + BTreeMap 有序 + 禁 HashMap —— Global Constraints + 各 Task 强制。✓
- **spec §6 snapshot**：延后至 P7 —— 路线图覆盖。✓
- **spec §7 测试**：翻译单测为唯一验证网 —— Task 7 起逐阶段翻译；proptest 在 P2/P7。✓
- **spec §1 全量范围**：期货/loan/LIF/ADL/funding/内部转账 —— 路线图 P4-P6 覆盖，各自出计划。✓
- **占位符扫描**：Task 4 的 `new_order` body 与 Task 7 的翻译用例交由执行者对照 Java 源产出——已用"行为契约测试 + Java 行号参照"约束，非空泛 TODO。
- **类型一致性**：`OrdersBucketNaive::match_forward` 回调签名 `(i64,i64,bool)`、`IOrderBook` 方法名在 Task 3 定义后于 Task 4-6 沿用一致。✓

---

## P1 完成状态（2026-08-31）

7/7 任务完成，逐任务评审 + 全分支终审（opus）+ 修复波 re-review 全部 clean。**79/79 lib 测试绿**，`-D warnings` 无告警。提交范围 `81a906fc..b8a1a79e`。
- OrdersBucketNaiveTest 6/6；OrderBookBaseTest 39/40（跳 `multipleCommandsKeepInternalStateTest`，需随机命令 harness）。
- 确定性底座核验通过：全 `BTreeMap`，输出路径零 `HashMap`，`i64` 定点无浮点。
- 终审后修复：`new_order` 统一返回 `CommandResultCode` 并置 `cmd.result_code`（趁单实现者时改，避免 P2 Direct 出现后跨切）。

### 执行中确立的裁定（Rulings，供 P2/P3 参考）
- **Ruling A**：`L2MarketData` 提前到 Task 2 定义（完整字段），因 command.rs 与 orderbook trait 都引用。
- **Ruling B**：Task 3 不 re-export `OrderBookNaive`（Task 4 才定义），保每任务独立编译。
- **Ruling C**：`fill_l2` 提前到 Task 4 实现（其测试需要），修正 `fill_l2(0)` 语义=零档对齐 Java。
- **Ruling D**：`MatcherTradeEvent` 为 P1 简化结构；Java 事件字段 `bidderHoldPrice/matchedOrderUid/filled/filledNotional/matchedOrderPrice/section` **推迟到 P3**（风控消费事件时倒逼）。终审确认简化结构对 39/40 base 用例足够，未半接线。
- **Ruling E**：Task 7 收窄为"state_hash + 完整 OrdersBucketNaiveTest + OrderBookBaseTest 支持子集 + dup-id reject"，非"翻译全量"。

### P3 carry-forward（务必进 P3 计划，勿遗漏）
1. **`MatcherTradeEvent` 字段补全**到 Java 全集（Ruling D 解除）：`bidderHoldPrice`（bid 保留价，风控 balance-release 用）、`matchedOrderUid`、`filled`、`filledNotional`、`matchedOrderPrice`、`section`。届时回补 FOK/IOC_BUDGET reject 用例中丢弃的 `bidderHoldPrice` 断言、及 3 处 `getOrderById` 断言。
2. **`move_order` 现货 reserveBidPrice 上限**：Java 对 `CURRENCY_EXCHANGE_PAIR` 在 `newPrice > reserveBidPrice` 时返回 `MATCHING_MOVE_FAILED_PRICE_OVER_RISK_LIMIT`；P1 缺 `SymbolType`/`CoreSymbolSpecification` 故推迟。真实 Java 行为，必须回补。
3. **`IOrderBook` trait 增长**：`getOrderById` + `validate_internal_state`（跳过的随机命令测试需要，也是 P3 调试利器）。
4. **plain FOK 语义复核**：P1 的 plain FOK 是 Rust 原创（Java 仅 TODO 桩），按 IOC 限价类比设计；P3/API 定义 FOK 期望时复核其事件语义。
5. **uid 归属校验**：已在 Task 7 提前落地（cancel/reduce/move 校验 `order.uid == cmd.uid`），P3 无需回补。
6. **ART / 对象池 / 多线程分片**：性能优化，P1 用 BTreeMap 替代，按需再议。

### 下一阶段
P2（`OrderBookDirectImpl` + 与 Naive 随机命令对拍 proptest）另出独立详细计划。
