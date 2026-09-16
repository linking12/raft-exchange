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
    run_liquidation_cascade        // 排空 R1/扫描生成的 FORCE→IF→ADL / loan 强平命令,逐条重喂过管线
```

强平不是旁路线程:markprice 更新或 `LIQUIDATION_SCAN` 在 R1 里做仓位检查,把生成的强平命令塞进队列,`run_liquidation_cascade` 再把它们当普通命令跑一遍管线(FORCE 接不住 → IF 接管 → 仍接不住 → ADL 自动排空级联)。整个过程在同一次 `process_command` 内闭合,确定且可复制。

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

---

## Java↔Rust 一致性保障

两套独立实现同一撮合引擎,核心问题有二:**(a) 证明行为等价**(现在)、**(b) 防止漂移**(任一侧演进后仍等价)。本仓用**三层递进防线** + 一份**归一化规格**来同时解决,而不是单靠"人肉翻译断言"。

### 三层防线

**① IT 翻译对拍(点覆盖,Java 黄金值)**
`tests/integration/it_*.rs` 逐条翻译自 Java `exchange.core2.tests.integration`。喂同样的命令序列,断言 **API 结果码 + matcher/fund event 逐字段 + 费用 + 仓位 + 全局守恒**。凡 Java 自身断言过黄金值的路径(现货/期货/费用/资金费/交割/cross 预警的 `liquidation_price`/`margin_ratio` 等),都是真·逐值对拍 Java。
- *强*:行为等价的直接证据。*弱*:只覆盖翻译到的场景;且**受限于 Java 的断言强度**——Java 宽松处(如 ITLiquidation/ITExchangeCoreADL 不断言 fund event、只验 state)无黄金可对。

**② 守恒 proptest(不变量,随机流)**
`tests/e2e/`(现货/期货/loan/清算)对随机合式命令流**逐步**断言全局守恒(`Σ账户 + 调整 + 费用 + Σ仓位(estimate_pnl+extra_margin) == 0`)、账户非负等不变量。
- *强*:覆盖输入空间、抓金额/守恒破坏。*弱*:守恒抓不住"守恒集内的归属互换"(所以 ① 里专门补了逐用户 maker/taker 断言)。

**③ 黄金向量对拍(差分,Java 当 oracle)** —— 根治 ①② 的 oracle 受限 + 漂移
`tests/conformance.rs` + Java `exchange-core/…/ConformanceExporter.java`。一份**与实现无关的命令流** `.stream`(`tests/conformance_vectors/`),两侧各有解释器喂各自引擎:Java 侧跑 `exchange-core` **直接把实际输出当黄金**写成 `.golden`(不依赖 Java 单测断不断言),Rust 侧 replay **同一** `.stream`、产**同一格式**输出、逐行断言 == `.golden`。
- 对拍:每命令 `result_code` + 最终状态摘要(账户/仓位/费用池)+ **结算类 fund event 多重集**(funding/pnl/liquidation/adl/fee)。
- *关键收益*:清算/ADL 的最终状态与结算事件——Java 单测本身不断言、① 对不了的——现在由 **Java 引擎实际输出**当 oracle,Rust 必须逐字节一致;且两侧都可入 CI,任一侧行为漂移即报错。
- 5 个向量全绿:`spot_full_cycle`、`perp_funding`、`delivery_settle`、`liquidation_isolated`、`adl`(loser 强平 + winner ADL 减仓 + 重定价逐值对拍)。

### 归一化规格(= 刻意差异清单)

差分对拍前需先声明"哪些刻意不同、归一化后排除,其余必须逐字节相等"。清单内是设计取舍,不是逻辑分歧:

- **单分片塌缩**:Rust 单 RiskEngine = Java 所有 uid 分片的并集;最终状态经报表聚合后分片无关,可比。
- **强平触发时机**:Rust 于 markprice 更新即 targeted 扫描,Java 靠 `LIQUIDATION_SCAN`。→ 事件用**全流多重集**比对(非逐命令归属),规避触发点差异。
- **`MARGIN_ALERT`/`LIQUIDATION_ALERT`**:Rust 刻意外置(不发事件,水位告警走外部拉报表)→ 两侧都排除。
- **仓位生命周期事件**(`OPEN_POSITION`/`CLOSE_POSITION`):Java 开仓只对 maker 发、Rust maker+taker 都发(钱一致、事件数不同)→ 与状态里的 `POS` 冗余,排除。
- **记账/锁事件**(`balance_adjustment` 的 fund event 等):Java 发、Rust 不发 → 排除。
- **撮合明细**:Java 是 `SpotExecutionReport`/`FuturesExecutionReport` 高层报告,Rust 是 raw `MatcherTradeEvent`,抽象不同 → 不进 ③(撮合正确性由 ① 逐值对拍)。
- **`state_hash`**:Rust 逐字段折叠是超集,不与 Java hash 直接互比;跨实现比对用 ③ 的语义状态摘要,不用 hash。

### 这套设计发现过的真 bug

差分/翻译对拍不是形式主义,已抓出真引擎缺陷,例如:`process_order` 对 `SETTLE_FUNDINGFEES`/`LIQUIDATION_SCAN` 等把 R1 的 `Success` 覆盖成 `MatchingUnsupportedCommand`/`MatchingInvalidOrderBookId`(Java ME 对这些是 no-op、保留结果码);IF→ADL 级联升级早死;等等。

### 一致性对拍工作流

```bash
# 1) Java 当 oracle 生成/更新黄金向量(在 exchange-core 模块)
mvn -q -Dtest=ConformanceExporter -DfailIfNoTests=false test
# 2) Rust replay 同一批 .stream,逐行断言 == .golden
cargo test --test conformance
```
加一个场景 = 写一个 `.stream`(现货/期货/清算/ADL 皆可)→ Java 导出 golden → Rust 对拍。引擎行为若有意变更,须同步更新两侧并**评审 golden diff**。

---

## 构建 / 测试 / 基准

```bash
cargo build --release

cargo test --lib                 # lib 内单元测试(与生产代码同文件的 #[cfg(test)])
cargo test --test e2e            # 引擎级 e2e + 守恒 proptest(防线②)
cargo test --test integration    # Java IT 对拍(防线①,仅公开 API)
cargo test --test conformance    # 黄金向量对拍(防线③,先跑上面 mvn 生成 golden)
cargo test --test orderbook_diff # Direct vs Naive 订单簿差分
cargo test                       # 全部

cargo bench --bench engine_throughput  # 纯引擎吞吐基准(绕开 Raft,直接灌 process_command)
```

测试布局:`src/` 只留与生产代码同文件的单元测试;独立测试都在 `tests/`(`e2e/`、`integration/`、`conformance.rs` + `conformance_vectors/`、`orderbook_diff.rs`),只用公开 API。

---

## 说明

- 快照:`ExchangeCore::to_snapshot_bytes` / `from_snapshot_bytes`(bincode),供 Raft install-snapshot;测试观测缓冲(`last_cascade_events` 等)`#[serde(skip)]` 不进快照。
- `state_hash`(逐字段折叠复制态)用于多节点/快照往返一致性校验;它是 Rust 内部超集,不与 Java 的 hash 直接互比(跨实现比对见"一致性保障"③)。
