# exchange-core-rs

Java [`exchange-core`](https://github.com/exchange-core/exchange-core)(`exchange.core2` 包)撮合引擎的 Rust 全量对等移植:现货 + 期货(永续/交割)+ 杠杆借贷,含撮合、风控、强平/ADL/保险基金、资金费、交割结算。

上游 Java 版跑在 LMAX Disruptor 多处理器多分片流水线上;本移植把并发**塌缩为单线程确定性顺序管线**,面向 Raft 复制状态机场景(每个节点按共识日志顺序 apply 同一串命令,得到逐字节一致的状态)。

- 金额一律 `i64` 定点,中间计算用 `i128` 防溢出。
- 任何影响输出的迭代都走确定序(`BTreeMap`/显式排序),**禁用 `HashMap` 迭代序**,保证多节点复制一致。

---

## 快速开始

### 构造

`ExchangeApi` 持有 `ExchangeCore`;`new()` 后经 `api.core()` 装配回调,再配置、下单、查询。

```rust
use exchange_core_rs::core::exchange_api::{
    ExchangeApi, PlaceOrderRequest, CancelOrderRequest, MoveOrderRequest,
    PlaceFuturesOrderRequest, ClosePositionRequest, MarginAdjustmentRequest,
};
use exchange_core_rs::core::common::core_symbol_specification::CoreSymbolSpecification;
use exchange_core_rs::core::common::symbol_type::SymbolType;
use exchange_core_rs::core::common::order_action::OrderAction;
use exchange_core_rs::core::common::order_type::OrderType;
use exchange_core_rs::core::common::margin_mode::MarginMode;
use exchange_core_rs::core::common::isolated_loan_record::LoanRateMode;
use exchange_core_rs::core::trade_events_handler::{TradeEventsHandler, OrderBook, SpotExecutionReport, FuturesExecutionReport};
use exchange_core_rs::core::fund_events_handler::{FundEventsHandler, FundEventReport};

// 外部只实现两个 handler trait(撮合执行报告 + 资金事件),
// 比如在 raft-server 里把 event 通过 Kafka 吐出去:KafkaTradeHandler / KafkaFundHandler。
struct MyTradeHandler;
impl TradeEventsHandler for MyTradeHandler {
    fn order_book(&mut self, _ob: OrderBook) {}
    fn spot_execution_report(&mut self, _r: SpotExecutionReport) {}
    fn futures_execution_report(&mut self, _r: FuturesExecutionReport) {}
}
struct MyFundHandler;
impl FundEventsHandler for MyFundHandler {
    fn fund_event_report(&mut self, _r: FundEventReport) {}
}

let mut api = ExchangeApi::new();

// 引擎内部驱动 SimpleEventsProcessor,把命令解码成报告分发给两个 handler,
// ssp/ups 由引擎内部注入(= Java RiskEngine 的 setter 注入)。外部只交 handler。
api.core().with_events_handlers(MyTradeHandler, MyFundHandler);

// 配置:货币(+精度)/ symbol / 开户 / 充值
api.add_currency(1, 1);
api.add_currency(2, 1);
api.add_symbol(CoreSymbolSpecification {
    symbol_id: 100, symbol_type: SymbolType::CurrencyExchangePair,
    base_currency: 1, quote_currency: 2, base_scale_k: 1, quote_scale_k: 1,
    taker_fee: 2, maker_fee: 1, ..Default::default()
});
api.add_user(1);
api.add_user(2);
api.balance_adjustment(1, 1, 1_000_000, 1);
api.balance_adjustment(2, 2, 10_000_000, 2);
```

方法按 **配置 → 交易 → 查询 → 报表** 分层,按域列常用调用:

#### 现货

```rust
api.place_order(PlaceOrderRequest { order_id: 5001, uid: 1, symbol: 100, price: 20_000, size: 1,
    reserve_bid_price: 0, action: OrderAction::Ask, order_type: OrderType::Gtc });
api.place_order(PlaceOrderRequest { order_id: 5002, uid: 2, symbol: 100, price: 20_000, size: 1,
    reserve_bid_price: 20_000, action: OrderAction::Bid, order_type: OrderType::Gtc });  // 买单须给 reserve_bid_price
api.move_order(MoveOrderRequest { order_id: 5001, uid: 1, symbol: 100, new_price: 20_010 });
api.cancel_order(CancelOrderRequest { order_id: 5001, uid: 1, symbol: 100 });
let _ev = api.last_matcher_event();
```

#### 期货

```rust
// 前置:add_futures_symbol(需开启 margin trading)
api.set_mark_price(200, 30_000);
api.leverage_adjustment(1, 200, 10);
api.adjust_position_mode(1, false);
api.place_futures_order(PlaceFuturesOrderRequest { order_id: 6001, uid: 1, symbol: 200, price: 30_000, size: 2,
    action: OrderAction::Bid, order_type: OrderType::Gtc, leverage: 10, margin_mode: MarginMode::Isolated, reduce_only: false });
api.margin_adjustment(MarginAdjustmentRequest { uid: 1, symbol: 200, action: OrderAction::Bid, amount: 1_000,
    margin_mode: MarginMode::Isolated, order_id: 6002 });
api.close_position(ClosePositionRequest { order_id: 6003, uid: 1, symbol: 200, action: OrderAction::Ask,
    price: 31_000, size: 2, order_type: OrderType::Gtc });
```

#### Loan

```rust
api.pool_deposit(2, 1_000_000, 7000);        // LP 注资借贷池
api.loan_create(7001, 1, 100, 1, 500, 40_000, LoanRateMode::Floating, 1_000);  // (order_id, uid, symbol, loan_id, collateral, principal, ..)
api.loan_add_collateral(7002, 1, 1, 100, 1_000);
api.loan_repay(7003, 1, 1, 10_000, 1_000);
api.loan_cross_borrow(7004, 1, 100, 2, 60_000, 1_000);
api.insurance_fund_deposit(200, 50_000, 7100);
// 冷门命令走通用入口 api.submit(order_command)
```

#### 报表(只读快照,对账/风控/展示外部拉)

```rust
assert!(api.total_balance().is_global_zero());  // 全局账面净零(强不变量)
let _u   = api.single_user(1, /*now_ms*/ 1_000);
let _if  = api.insurance_fund();
let _fee = api.fee_report();
let _lp  = api.loan_platform();
let _sc  = api.symbol_currency();               // client 缩放数据源
let _h   = api.state_hash();                    // 多节点/快照往返比对
```

### 接入 Raft 状态机

引擎级操作(级联去向、周期扫描、快照)都经 `api.core()`;`with_command_submitter` 收一个实现 `CommandSubmitter` trait 的**共享实例**(`Rc<RefCell<dyn CommandSubmitter>>`,三个内部引擎共享),等价 Java 的 `LiquidationCommandSubmitter.submit(cmd)`(单节点默认已装好,集群下改成交 Raft):

```rust
use std::{cell::RefCell, collections::VecDeque, rc::Rc};
use exchange_core_rs::core::common::cmd::order_command::OrderCommand;
use exchange_core_rs::core::processors::liquidation::command_submitter::CommandSubmitter;

// 级联去向:实现 CommandSubmitter,把强平/loan 次生命令交 raft 而非就地 apply(Java 接口风格)
struct RaftSubmitter { queue: Rc<RefCell<VecDeque<OrderCommand>>> }
impl CommandSubmitter for RaftSubmitter {
    fn submit(&mut self, cmd: OrderCommand) { self.queue.borrow_mut().push_back(cmd); }  // 实际:raft.propose(cmd)
}

let queue: Rc<RefCell<VecDeque<OrderCommand>>> = Rc::new(RefCell::new(VecDeque::new()));
api.core().with_command_submitter(Rc::new(RefCell::new(RaftSubmitter { queue: queue.clone() })));

// apply 循环:自身命令 + raft 回流的级联命令,都喂回引擎
api.submit(user_cmd);
loop {
    let next = queue.borrow_mut().pop_front();  // 先取出再 submit
    let Some(cmd) = next else { break };
    api.submit(cmd);
}

// 周期扫描(leader 时钟)+ 快照(install-snapshot,per RE/ME 模块 × 分片)
api.core().tick_liquidation_scheduler(now);
api.core().persist(snapshot_id, instance_id);
api.core().recover(snapshot_id, instance_id);
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
- **装配**:`ExchangeApi::new()` 自带默认 core,经 `api.core()` 挂回调 trait(Java 接口风格,非闭包)。事件出口的**外部对接面就是两个 handler trait**:`with_events_handlers(trade, fund)` 收 `TradeEventsHandler` + `FundEventsHandler`,引擎内部包进 `SimpleEventsProcessor` 并驱动、内部注入 ssp/ups(= Java `RiskEngine.initState` 对 `SimpleEventsProcessor` 的 setter 注入)——外部(如 raft-server)只需实现 `KafkaTradeHandler`/`KafkaFundHandler` 把 event 吐 Kafka,不用碰 `SimpleEventsProcessor` 或 provider。`with_results_consumer(Box<dyn ResultsConsumer>)` 是更底层的原始钩子(conformance/测试直接在命令层捕获时用)。`with_command_submitter`(实现 `CommandSubmitter`,`Rc<RefCell<dyn ...>>` 共享实例,级联去向)= Java 侧 Disruptor handler 链接线,在单管线里收敛成 trait 回调;快照 `persist`/`recover`、`tick_liquidation_scheduler` 同样经 `api.core()`。
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

2026-09-19 把撮合执行报告(match event)接进防线③:`SimpleEventsProcessor` 挂 `results_consumer`,同步向量 opt-in `#!match=on` 逐字段对拍 `Spot/FuturesExecutionReport`(不多发/漏发/错发);新增一批 IT 翻译。对拍抓到并修复一个真实资金 bug:**cross 强平破产价**——`LiquidationEngine` 查 cross margin 分配表用错 key(one-way 空头按方向符号重算,与 map key 不匹配 → margin_base=0 → 破产价偏低 → FORCE 单吃不到流动性、漏收 taker fee)。详见 CONSISTENCY.md §7.6。

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
