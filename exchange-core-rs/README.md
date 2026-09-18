# exchange-core-rs

Java [`exchange-core`](https://github.com/exchange-core/exchange-core)(`exchange.core2` 包)撮合引擎的 Rust 全量对等移植:现货 + 期货(永续/交割)+ 杠杆借贷,含撮合、风控、强平/ADL/保险基金、资金费、交割结算。

上游 Java 版跑在 LMAX Disruptor 多处理器多分片流水线上;本移植把并发**塌缩为单线程确定性顺序管线**,面向 Raft 复制状态机场景(每个节点按共识日志顺序 apply 同一串命令,得到逐字节一致的状态)。

- 金额一律 `i64` 定点,中间计算用 `i128` 防溢出。
- 任何影响输出的迭代都走确定序(`BTreeMap`/显式排序),**禁用 `HashMap` 迭代序**,保证多节点复制一致。

---

## 快速开始

### 1. 最简用法(自带默认引擎,现货撮合)

`ExchangeApi::new()` 内部自带一个装配好的 `ExchangeCore`,取来即用:

```rust
use exchange_core_rs::core::exchange_api::{ExchangeApi, PlaceOrderRequest};
use exchange_core_rs::core::common::cmd::command_result_code::CommandResultCode;
use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
use exchange_core_rs::core::common::order_action::OrderAction;
use exchange_core_rs::core::common::order_type::OrderType;
use exchange_core_rs::core::common::symbol_type::SymbolType;

let mut api = ExchangeApi::new();

// 1) 配置:注册货币(currency + 精度 scale_k)与现货 symbol
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

// 2) 配置:开户 + 充值
api.add_user(1);
api.add_user(2);
api.balance_adjustment(1, 1, 1_000_000, 1);  // seller 充 base
api.balance_adjustment(2, 2, 10_000_000, 2); // buyer 充 quote

// 3) 撮合:下单
api.place_order(PlaceOrderRequest {
    order_id: 5001, uid: 1, symbol: 100, price: 20_000, size: 1,
    reserve_bid_price: 0, action: OrderAction::Ask, order_type: OrderType::Gtc,
});
let rc = api.place_order(PlaceOrderRequest {
    order_id: 5002, uid: 2, symbol: 100, price: 20_000, size: 1,
    reserve_bid_price: 20_000, action: OrderAction::Bid, order_type: OrderType::Gtc,
});
assert_eq!(rc, CommandResultCode::Success);

// 4) 查询:上条命令的撮合事件链(TRADE/REDUCE/REJECT)
let _ev = api.last_matcher_event();

// 5) 报表:只读快照(对账/风控/展示外部拉)——total_balance / single_user / insurance_fund /
//    symbol_currency(client 缩放数据源)/ fee_report / loan_platform / state_hash;与 ExchangeCore::query_* 一一对齐
assert!(api.total_balance().is_global_zero());     // 例:全局账面净零(强不变量)
```

`ExchangeApi` 的方法按 **配置(Setup)→ 交易(Trading)→ 查询(Queries)→ 报表(Reports)** 分层(见 `src/core/exchange_api.rs`;交易段内再按 现货 → 期货 → 清算 → 结算/资金费 → 转账 → 保险基金 → loan → pool 归组)。两个入口:

- **`ExchangeApi`**(`src/core/exchange_api.rs`)—— 高层门面,把常用操作封成方法(`place_order` / `cancel_order` / `move_order` / `place_futures_order` / `close_position` / `margin_adjustment` / `balance_adjustment` …),并缓存上一条命令的事件供读取(`last_matcher_event` / `last_fund_events`)。两种构造:`ExchangeApi::new()` 自带一个默认 `ExchangeCore`(测试/嵌入即取即用);`ExchangeApi::from_core(core)` 包住一个**已按需装配好的 `ExchangeCore`**(见下)。
- **`ExchangeCore`**(`src/core/exchange_core.rs`)—— 底层引擎,唯一命令入口 `process_command(&mut OrderCommand)`。采用 **builder-setter 装配**,`new()` 后按需注入:
  - `with_serialization_processor(..)` —— 快照后端(`InMemory`/`File`,对齐 Java `ISerializationProcessor`);`persist(snapshot_id, instance_id)` / `recover(...)` 按 per-(模块 RE/ME, 分片 instanceId) 走。
  - `with_command_submitter(factory)` —— 级联/loan 强平 fan-out 的命令去向:默认工厂把命令推进内部 `pending_commands` 队列(单节点 FIFO,`drive_pending` 排空);集群模式换成"交 Raft 复制"的工厂,级联命令不再 inline apply,而是过共识再回灌。
  - `with_results_consumer(consumer)` —— 每条命令 apply 后回调 `(cmd, seq, ssp, ups)`,把结果/事件流给下游(如 `SimpleEventsProcessor` / Raft 结果处理器);对齐 Java 的 results handler。

  Raft 状态机把 core 装配好后直接喂 `OrderCommand` 走 `process_command`。

### 2. 作为 SDK 接入:装配 `ExchangeCore` → `from_core`

生产/嵌入时一般不用默认 core,而是**先按需装配一个 `ExchangeCore`,再 `from_core` 包成门面**——把撮合结果流给你的下游,并挂上快照后端:

```rust
use exchange_core_rs::core::exchange_core::ExchangeCore;
use exchange_core_rs::core::exchange_api::ExchangeApi;
use exchange_core_rs::core::snapshot::serialization_processor::FileSerializationProcessor;

let mut core = ExchangeCore::new();

// (a) 结果消费者:每条命令 apply 完回调一次,把结果/事件推给下游
//     (行情/成交推送、审计、SimpleEventsProcessor、Raft 结果处理器…)
core.with_results_consumer(Box::new(|cmd, seq, _ssp, _ups| {
    // cmd.matcher_event —— 撮合事件链(TRADE / REDUCE / REJECT)
    // cmd.fund_events   —— 资金事件(deposit / lock / pnl / fee / loan …)
    // seq               —— 全局单调序号(下游去重 / 断点续传)
    on_result(seq, cmd);
}));

// (b) 快照后端:File 落盘(Chronicle Wire,与 Java .dat 互通)/ InMemory(测试)
core.with_serialization_processor(Box::new(FileSerializationProcessor::new("./snap")));

// (c) 包成门面,照常下命令
let mut api = ExchangeApi::from_core(core);
api.add_currency(1, 1);
api.add_currency(2, 1);
// … add_symbol / add_user / balance_adjustment / place_order …
```

**接进 Raft 状态机**时再多接两根线(单机嵌入可跳过):

- **`with_command_submitter(factory)`** —— 把强平/loan 级联命令的去向从"本地 `pending_commands` 队列"换成"交 Raft 复制"的工厂:级联命令不再 inline apply,而是过共识日志再回灌 `process_command`(见「架构」)。
- **`tick_liquidation_scheduler(now)`** —— leader 按自己的时钟周期调用,内置 `LiquidationScheduler` 会投一条 `LIQUIDATION_SCAN` 进队列交复制;`start_liquidation_scheduler` / `stop_liquidation_scheduler` 控制开关。apply 侧只认命令,天然确定、可复制。

apply 循环骨架:对每条**已提交到共识日志**的命令调 `core.process_command(&mut cmd)`,再把 `results_consumer` 收到的结果发给客户端。

### 3. 期货 / loan / 快照 速览

```rust
use exchange_core_rs::core::exchange_api::PlaceFuturesOrderRequest;
use exchange_core_rs::core::common::margin_mode::MarginMode;
use exchange_core_rs::core::common::isolated_loan_record::LoanRateMode;

// 期货:开多(逐仓 10x)——交易段便捷方法
api.place_futures_order(PlaceFuturesOrderRequest {
    order_id: 6001, uid: 1, symbol: 200, price: 30_000, size: 2,
    action: OrderAction::Bid, order_type: OrderType::Gtc,
    leverage: 10, margin_mode: MarginMode::Isolated, reduce_only: false,
});

// loan:开一笔逐仓借贷——有专用便捷方法,无需手搓 OrderCommand
api.loan_create(
    /*order_id*/ 7001, /*uid*/ 1, /*symbol*/ 100, /*loan_id*/ 1,
    /*collateral*/ 500, /*principal*/ 40_000, LoanRateMode::Floating, /*ts*/ 1_000,
);
// 其余 loan/pool/保险基金/结算 同样各有便捷方法(loan_repay / loan_cross_borrow /
// pool_deposit / insurance_fund_deposit / settle_pnl …);冷门命令可走通用入口 api.submit(cmd)。
```

```rust
// 快照:leader 落盘 / 新节点 install-snapshot 后恢复(在持有 core 句柄处调用)
core.persist(snapshot_id, instance_id);   // per (RE/ME 模块, 分片 instance)
core.recover(snapshot_id, instance_id);
```

## 架构

### 确定性顺序管线

Java 的 Disruptor 五段(R1 预处理 → ME 撮合 → R2 风控释放 → …)在这里塌缩成**一个线程里的顺序调用**,每条命令依次流过:

```
process_command(cmd):
    apply_one(cmd):
        R1  risk.pre_process_command    // 校验/冻结/仓位预处理/扫描判定
        ME  matching.process_order      // 撮合(下单/撤单/改单/减量/强平吃单);非交易命令 no-op
        R2  risk.handler_risk_release    // 成交结算/释放/PnL/费用入池/事件产出
        └─ results_consumer(cmd, seq…)  // 若已注入:把该命令结果/事件流给下游
    drive_pending():                    // 排空 R1/扫描/loan 经 command_submitter 塞进 pending_commands 的
                                        // FORCE→IF→ADL / loan 强平命令,逐条重喂 apply_one 直到队列空
```

强平不是旁路线程:markprice 更新或 `LIQUIDATION_SCAN` 在 R1 里做仓位检查,经 `command_submitter` 把生成的强平命令塞进 `pending_commands`,`drive_pending` 再把它们当普通命令跑一遍管线(FORCE 接不住 → IF 接管 → 仍接不住 → ADL 自动排空级联)。整个过程在同一次 `process_command` 内闭合,确定且可复制。

周期性强平扫描也不由后台线程直接触发,而是内置 `LiquidationScheduler`(`start` / `stop` / `tick_liquidation_scheduler(now)`):leader/shard0 每次 tick 把一条 `LIQUIDATION_SCAN` 命令投进 `pending_commands` 交 Raft 复制,`tick_liquidation_scheduler` 末尾同样 `drive_pending` 排空;扫描本身在 apply 时才确定性执行(切片信息随命令复制)。

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

## ExchangeApi 与 Java 对照

Java `ExchangeApi` 是 Disruptor 之上的**异步提交层**(`RingBuffer` + `CompletableFuture` + `PromiseBuffer` + 批量/回调);Rust `ExchangeApi` 是**同步门面**——直接持有 `ExchangeCore`,每个方法内联跑一次 `process_command` 并缓存结果。**业务操作零缺失**,差异只在提交模型与便捷封装粒度:

- **命令覆盖完整**:Rust `OrderCommandType` 覆盖全部 **43 个业务命令码**;Java `OrderCommandType` 有 51 个,多出的 8 个全是**基础设施/传输类、非业务**——`GROUPING_CONTROL`/`SHUTDOWN_SIGNAL`/`RESERVED_COMPRESSED`(Disruptor/journal 生命周期,单管线 N/A)、`BINARY_DATA_QUERY`(Rust 直接调报表访问器)、`PERSIST_STATE_{MATCHING,RISK}` + `RECOVER_STATE_{MATCHING,RISK}`(这 4 个 Rust 合并进 `persist()` / `recover()` 两个方法,一次处理 RE+ME 两模块)。**44 个 `Api*` 业务命令全部有对应**。
- **便捷方法**(有专用封装的高频操作):`add_user`/`balance_adjustment`/`place_order`/`place_futures_order`/`cancel_order`/`move_order`/`reduce_order`/`close_position`/`margin_adjustment`/`leverage_adjustment`/`adjust_position_mode`/`set_mark_price`/`suspend_user`/`resume_user`;初始化批量入口 `add_currencies`/`add_symbols`/`add_accounts`/`add_loans`(对齐 Java `BatchAdd*Command`)。
- **通用入口** `submit(OrderCommand)`:loan(`LoanCreate`/`LoanRepay`/`LoanCross*`/…)、`PoolDeposit`/`PoolWithdraw`、`IfDeposit`/`IfWithdraw`、`SettlePnl`/`SettleFundingfees`、`RepriceLoanRates`、`InternalTransfer`、`ResetFee`、`Reset` 等经此提交(与 Java 逐命令对拍一致,只是不各配一个便捷 wrapper)。
- **装配**:`ExchangeApi::new()`(自带默认 core)/ `from_core(core)`(包已装配 core);`ExchangeCore::with_command_submitter` / `with_results_consumer` / `with_serialization_processor` = Java 侧 Disruptor handler 链的接线(级联去向、results 处理器、journal/快照后端),在单管线里收敛成三个显式 setter。
- **快照** `persist(snapshot_id, instance_id)` / `recover(...)` 经持有的 `SerializationProcessor` = Java `submitPersistCommandAsync`/`submitRecoverCommandAsync`(见模块表 `ExchangeCore`)。
- **报表**:直接访问器 `total_balance`/`single_user`/`fee_report`/`insurance_fund`/`loan_platform`/`symbol_currency`/`state_hash` = Java `processReport`/`submitQueryAsync`。
- **不移植**:Java 异步层(`submitCommandAsync`/`FullResponse`/`submitBatchAsync`/回调/`RingBuffer`)、`groupingControl`(Disruptor 批处理控制)——单线程顺序管线下 N/A。

---

## 与 Java 版的主要不同

| 维度 | Java `exchange-core` | 本移植 (`exchange-core-rs`) |
|------|----------------------|------------------------------|
| **并发模型** | LMAX Disruptor 多处理器环形队列,多段并行流水 | 单线程确定性顺序管线(R1→ME→R2→drain),面向 Raft 逐条 apply |
| **分片** | RiskEngine 按 `uid & shardMask` 多实例分片、订单簿按 symbol 分片,多引擎并行 | **单分片塌缩**(`shardMask=0`):一个 RiskEngine 持全部用户、一个 router 持全部 symbol。等价于 Java 所有分片的并集 |
| **强平触发** | 周期性 `LIQUIDATION_SCAN` 广播到各分片,片内按 `coveredByScanSlice` 轮询 | 保留 `LIQUIDATION_SCAN` 切片(uid 轮询公式一致);**另加** markprice 更新时的 targeted 扫描(`symbol_to_users` 索引,只扫持仓者,更省) |
| **ADL/IF 跨分片** | 同 symbol 对手方可能跨分片,需 `needSyncR2` 协调 | 单分片下对手方天然共处,级联无需跨分片同步(严格超集,更完整) |
| **调度器** | 独立 `LiquidationScheduledService` 线程直接触发 | 内置 `LiquidationScheduler`(`start`/`stop`/`tick_liquidation_scheduler(now)`):leader/shard0 每次 tick 把 `LIQUIDATION_SCAN` 命令投进 `pending_commands` 交 Raft 复制,apply 时才确定性扫描(scan_tick 本地、切片信息随命令复制) |
| **确定性** | 单分片内确定,跨分片靠 Disruptor 编排 | 全局单序;所有迭代走 `BTreeMap`/显式排序,`state_hash` 可用于多节点比对 |
| **溢出** | `long` + 少量 `Math.*Exact` | `i64` 定点 + `i128` 中间量 + `*_exact` / `saturating_*` 显式处理 |
| **上游集成** | 内嵌 Disruptor | 纯引擎库,由 `raft-exchange-server` 作为 Raft 状态机驱动;不含 JNI/sidecar |

---

## Java↔Rust 一致性保障

两套独立实现同一撮合引擎,如何**证明行为等价**且**防止漂移**——三层递进防线(IT 翻译对拍 / 守恒 proptest / 黄金向量对拍 Java-oracle)+ 差分模糊 + 归一化规格,以及框架发现的真实问题,详见 **[`CONSISTENCY.md`](CONSISTENCY.md)**。

2026-09-18 对整个 crate 做了 6 组并行逐字段 review:**资金结算 6 子系统全部 CLEAN**(零守恒漏洞),仅修了 5 处非资金的事件/报告层平价缺口;`FundEventType` 27/27 全移植,`MatcherEventType` 无遗漏(缺的 6 类由两步处理器按命令类型路由,等价)。详见 CONSISTENCY.md §7.4。

## 构建 / 测试 / 基准

```bash
cargo build --release

cargo test --lib                 # lib 内单元测试(与生产代码同文件的 #[cfg(test)])
cargo test --test e2e            # 引擎级 e2e + 守恒 proptest(防线②)
cargo test --test integration    # Java IT 对拍(防线①,仅公开 API)
cargo test --test conformance    # 黄金向量对拍(防线③,需先用 Java 生成 golden,见 CONSISTENCY.md §10)
cargo test --test orderbook_diff # Direct vs Naive 订单簿差分
cargo test                       # 全部

cargo bench --bench engine_throughput  # 纯引擎吞吐基准(绕开 Raft,直接灌 process_command)
```

测试布局:`src/` 只留与生产代码同文件的单元测试;独立测试都在 `tests/`(`e2e/`、`integration/`、`conformance.rs` + `conformance_vectors/`、`orderbook_diff.rs`),只用公开 API。

---

## 说明

- 快照:`ExchangeCore::persist` / `recover` 经持有的 `SerializationProcessor` 走 **Chronicle Wire 二进制**(与 Java `.ecs`/`.dat` 快照互通,**非 bincode/serde**——已整套删除),分帧对齐 Java `ISerializationProcessor`(RE/ME 两模块 + LZ4 autodetect);供 Raft install-snapshot。非复制态观测缓冲(`last_cascade_events` 等)不写进快照。
- `state_hash`(逐字段折叠复制态)用于多节点/快照往返一致性校验;它是 Rust 内部超集,不与 Java 的 hash 直接互比(跨实现比对见"一致性保障"③)。
