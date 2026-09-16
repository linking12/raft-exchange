# exchange-core-rs

Java [`exchange-core`](https://github.com/exchange-core/exchange-core)(`exchange.core2` 包)撮合引擎的 Rust 全量对等移植:现货 + 期货(永续/交割)+ 杠杆借贷,含撮合、风控、强平/ADL/保险基金、资金费、交割结算。

上游 Java 版跑在 LMAX Disruptor 多处理器多分片流水线上;本移植把并发**塌缩为单线程确定性顺序管线**,面向 Raft 复制状态机场景(每个节点按共识日志顺序 apply 同一串命令,得到逐字节一致的状态)。

- 金额一律 `i64` 定点,中间计算用 `i128` 防溢出。
- 任何影响输出的迭代都走确定序(`BTreeMap`/显式排序),**禁用 `HashMap` 迭代序**,保证多节点复制一致。

---

## 快速开始

```rust
use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceOrderRequest};
use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
use exchange_core_rs::core::common::order_action::OrderAction;
use exchange_core_rs::core::common::order_type::OrderType;
use exchange_core_rs::core::common::symbol_type::SymbolType;

let mut api = ExchangeApi::new();

// 1) 注册货币(currency + 精度 scale_k)与现货 symbol
api.add_currency(1, 1);   // base
api.add_currency(2, 1);   // quote
api.add_symbol(CoreSymbolSpecification {
    symbol_id: 100,
    symbol_type: SymbolType::CurrencyExchangePair,
    base_currency: 1,
    quote_currency: 2,
    base_scale_k: 1,
    quote_scale_k: 1,
    taker_fee: 2,
    maker_fee: 1,
    ..Default::default()
});

// 2) 开户 + 充值
api.add_user(1);
api.add_user(2);
api.balance_adjustment(1, 1, 1_000_000, 1); // seller 充 base
api.balance_adjustment(2, 2, 10_000_000, 2); // buyer 充 quote

// 3) 下单撮合
api.place_order(PlaceOrderRequest {
    order_id: 5001, uid: 1, symbol: 100, price: 20_000, size: 1,
    reserve_bid_price: 0, action: OrderAction::Ask, order_type: OrderType::Gtc,
});
let rc = api.place_order(PlaceOrderRequest {
    order_id: 5002, uid: 2, symbol: 100, price: 20_000, size: 1,
    reserve_bid_price: 20_000, action: OrderAction::Bid, order_type: OrderType::Gtc,
});
assert_eq!(rc, CommandResultCode::Success);

// 4) 读取撮合事件 / 账户 / 报表
let _ev = api.last_matcher_event();          // 本条命令产生的 TRADE/REDUCE/REJECT 链
let _bal = api.user_account(2, 1);           // buyer 收到的 base
assert!(api.total_balance().is_global_zero()); // 全局守恒
```

两个入口:

- **`ExchangeApi`**(`src/core/exchange_api.rs`)—— 高层门面,把常用操作封成方法(`place_order` / `cancel_order` / `move_order` / `place_futures_order` / `close_position` / `margin_adjustment` / `balance_adjustment` …),并缓存上一条命令的事件供读取(`last_matcher_event` / `last_fund_events`)。适合测试与嵌入。
- **`ExchangeCore`**(`src/core/exchange_core.rs`)—— 底层引擎。唯一入口 `process_command(&mut OrderCommand)`,加上快照 `to_snapshot_bytes` / `from_snapshot_bytes`。Raft 状态机直接喂 `OrderCommand` 走这个。

---

## 架构

### 确定性顺序管线

Java 的 Disruptor 五段(R1 预处理 → ME 撮合 → R2 风控释放 → …)在这里塌缩成**一个线程里的顺序调用**,每条命令依次流过:

```
process_command(cmd):
    R1  risk.pre_process_command   // 校验/冻结/仓位预处理/扫描判定
    ME  matching.process_order     // 撮合(下单/撤单/改单/减量/强平吃单);非交易命令 no-op
    R2  risk.handler_risk_release  // 成交结算/释放/PnL/费用入池/事件产出
    drain_liquidation_commands     // 排空 R1/扫描生成的 FORCE→IF→ADL / loan 强平命令,逐条重喂过管线
```

强平不是旁路线程:markprice 更新或 `LIQUIDATION_SCAN` 在 R1 里做仓位检查,把生成的强平命令塞进队列,`drain_liquidation_commands` 再把它们当普通命令跑一遍管线(FORCE 接不住 → IF 接管 → 仍接不住 → ADL 自动排空级联)。整个过程在同一次 `process_command` 内闭合,确定且可复制。

### 单 crate 模块划分

exchange-core 本身即单一 Java module,故 Rust 侧也是**单 crate**,内部用 `mod` 分域(全部挂在 `src/core/` 下):

| 模块 | 职责 | 对应 Java |
|------|------|-----------|
| `core::common` | 领域模型:`OrderCommand`、`CoreSymbolSpecification`、`UserProfile`、`SymbolPositionRecord`、`Order`、`FundEvent`、`MatcherTradeEvent`、loan/currency 规格等 | `core/common/**` |
| `core::common::cmd` | 命令类型 `OrderCommandType` + 结果码 `CommandResultCode` | `core/common/cmd/**` |
| `core::orderbook` | 订单簿:`IOrderBook` 抽象 + Direct(O(log N))/Naive 两实现 | `core/orderbook/**` |
| `core::processors` | `RiskEngine`、`MatchingEngineRouter`、资金费、ADL | `core/processors/**` |
| `core::processors::loan` | 借贷:命令分派、清算引擎、利率曲线(`rate/`) | loan 相关 |
| `core::processors::liquidation` | 强平引擎、清算服务、破产价、切片调度器 | `LiquidationEngine` 等 |
| `core::exchange_core` | `ExchangeCore` 编排(确定性管线 + 快照) | `core/*.java` |
| `core::exchange_api` | `ExchangeApi` 高层门面 | `core/ExchangeApi.java` |
| `core::reports` | 报表:全局余额守恒、单用户、保险基金 | `ReportQuery` 系列 |
| `core::utils` | 定点算术(`i128` 中间量、缩放、ceil/floor) | `CoreArithmeticUtils` 等 |

---

## 与 Java 版的主要不同

| 维度 | Java `exchange-core` | 本移植 (`exchange-core-rs`) |
|------|----------------------|------------------------------|
| **并发模型** | LMAX Disruptor 多处理器环形队列,多段并行流水 | 单线程确定性顺序管线(R1→ME→R2→drain),面向 Raft 逐条 apply |
| **分片** | RiskEngine 按 `uid & shardMask` 多实例分片、订单簿按 symbol 分片,多引擎并行 | **单分片塌缩**(`shardMask=0`):一个 RiskEngine 持全部用户、一个 router 持全部 symbol。等价于 Java 所有分片的并集 |
| **强平触发** | 周期性 `LIQUIDATION_SCAN` 广播到各分片,片内按 `coveredByScanSlice` 轮询 | 保留 `LIQUIDATION_SCAN` 切片(uid 轮询公式一致);**另加** markprice 更新时的 targeted 扫描(`symbol_to_users` 索引,只扫持仓者,更省) |
| **ADL/IF 跨分片** | 同 symbol 对手方可能跨分片,需 `needSyncR2` 协调 | 单分片下对手方天然共处,级联无需跨分片同步(严格超集,更完整) |
| **调度器** | 独立 `LiquidationScheduledService` 线程直接触发 | `LiquidationScheduler` 只在 leader/shard0 把 `LIQUIDATION_SCAN` 命令投进队列交 Raft 复制,apply 时才确定性扫描(scan_tick 本地、切片信息随命令复制) |
| **确定性** | 单分片内确定,跨分片靠 Disruptor 编排 | 全局单序;所有迭代走 `BTreeMap`/显式排序,`state_hash` 可用于多节点比对 |
| **溢出** | `long` + 少量 `Math.*Exact` | `i64` 定点 + `i128` 中间量 + `*_exact` / `saturating_*` 显式处理 |
| **上游集成** | 内嵌 Disruptor | 纯引擎库,由 `raft-exchange-server` 作为 Raft 状态机驱动;不含 JNI/sidecar |

> 行为对等性由 `tests/integration/`(逐条翻译自 Java `exchange.core2.tests.integration` 的 IT)保证:对拍 API 结果码 + matcher/fund event + 费用 + 仓位 + 全局守恒。刻意的差异(单分片、targeted 扫描等)在上表列明,不属于逻辑分歧。

---

## 构建 / 测试 / 基准

```bash
# 构建
cargo build --release

# 单元测试(lib 内 #[cfg(test)],含现货/期货/loan/清算的守恒 proptest)
cargo test --lib

# 集成测试(tests/integration/,Java IT 对拍,仅走公开 API)
cargo test --test integration

# 全部
cargo test

# 纯引擎吞吐基准(绕开 Raft,直接灌 process_command)
cargo bench --bench engine_throughput
```

---

## 说明

- `src/core/*_e2e_tests.rs` 是 lib 内的引擎级 e2e + 守恒 proptest;`tests/integration/it_*.rs` 是独立集成 crate,只用公开 API 逐条对拍 Java IT。
- 快照:`ExchangeCore::to_snapshot_bytes` / `from_snapshot_bytes`(bincode),供 Raft install-snapshot。
- `state_hash`(逐字段折叠复制态)用于多节点/快照往返一致性校验;它是 Rust 内部超集,不与 Java 的 hash 直接互比。
