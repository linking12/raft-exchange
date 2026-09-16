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

// 4) 读取撮合事件
let _ev = api.last_matcher_event();          // 本条命令产生的 TRADE/REDUCE/REJECT 链

// 5) 报表(只读快照,不改状态)——见下「报表」节
let u = api.single_user(2, /*now_ms=*/0);    // 单用户:账户/仓位/挂单/借贷
let _base = u.accounts.get(&1).copied().unwrap_or(0);   // buyer 收到的 base
for (_sym, order) in &u.orders { /* 该用户所有活动挂单 */ let _ = order; }

let tb = api.total_balance();                // 全局余额守恒报表
assert!(tb.is_global_zero());                // 平台账面净零(强不变量)
let _fee_pool = tb.fees.get(&2).copied().unwrap_or(0);  // quote 费用池
```

两个入口:

- **`ExchangeApi`**(`src/core/exchange_api.rs`)—— 高层门面,把常用操作封成方法(`place_order` / `cancel_order` / `move_order` / `place_futures_order` / `close_position` / `margin_adjustment` / `balance_adjustment` …),并缓存上一条命令的事件供读取(`last_matcher_event` / `last_fund_events`)。适合测试与嵌入。
- **`ExchangeCore`**(`src/core/exchange_core.rs`)—— 底层引擎。唯一入口 `process_command(&mut OrderCommand)`,加上快照 `to_snapshot_bytes` / `from_snapshot_bytes`。Raft 状态机直接喂 `OrderCommand` 走这个。

---

## 报表(查询引擎状态)

报表是**只读快照**,不改状态、不进 Raft 日志——生产里由**外部**按需拉取(对账、风控展示、水位告警)。对应 Java 的 `ReportQuery` 系列。`ExchangeApi` 暴露三个;底层 `ExchangeCore` 有对应的 `query_*`。

```rust
// ① 单用户报表:账户 / 仓位(含派生风控字段)/ 活动挂单 / 借贷。now_ms 用于 loan 实时利息与 LTV。
let u = api.single_user(uid, now_ms);
if u.found {
    let base_bal   = u.accounts.get(&base_cur).copied().unwrap_or(0);   // 可用账户(不含冻结)
    let base_lock  = u.exchange_locked.get(&base_cur).copied().unwrap_or(0); // 现货挂单冻结
    for p in &u.positions {                 // 期货仓位视图(现货用户为空)
        let _ = (p.symbol, p.direction, p.open_volume,
                 p.unrealized_pnl,           // 按当前 mark 的未实现盈亏
                 p.liquidation_price,        // 强平价
                 p.margin_ratio_scale_k);    // 保证金率(×scaleK)
    }
    for (sym, order) in &u.orders { let _ = (sym, order.order_id, order.price, order.size); }
    let _ = (u.user_status, &u.isolated_loans, &u.cross_loans, u.cross_account_ltv_bps);
}

// ② 全局余额守恒报表:逐币种各桶 + 守恒校验(对账用)。
let tb = api.total_balance();
assert!(tb.is_global_zero());               // 平台账面净零(否则 global_balances_sum() 给出各币非零残差)
let _user_sum = tb.currency_balances.get(&quote_cur).copied().unwrap_or(0); // 用户账户合计
let _fees     = tb.fees.get(&quote_cur).copied().unwrap_or(0);             // 费用池
let _locked   = tb.exchange_locked.get(&quote_cur).copied().unwrap_or(0);  // 现货冻结合计
// 另有 extra_margin / adjustments / suspends / loan_balances / loan_collateral /
//     symbol_open_interest_long|short / if_balances / if_open_interest_long|short 桶。

// ③ 保险基金报表:期货 IF(available/reserved/position_value)+ 借贷 LIF。
let ins = api.insurance_fund();
if let Some(e) = ins.futures.get(&perp_symbol) {
    let _ = (e.available, e.reserved, e.position_value);
}
let _lif = ins.loan_insurance_fund.get(&quote_cur).copied().unwrap_or(0);

// ④ symbol/currency 规格报表:**client 缩放的数据源**。引擎所有金额都是 i64 定点,
//    client 用 currency_scale_k / base_scale_k / quote_scale_k 把 raw i64 转人类可读。
let sc = api.symbol_currency();
for c in &sc.currencies {                    // 每币种的缩放
    let _ = (c.currency, c.currency_scale_k); // 人类值 = raw / currency_scale_k
}
for s in &sc.symbols {                        // 每 symbol 的 base/quote 缩放 + 费率档
    let _ = (s.symbol_id, s.base_currency, s.quote_currency, s.base_scale_k, s.quote_scale_k);
}
```

**缩放约定(client 侧做)**:引擎内一切金额/价格都是 **i64 定点**,不带小数。client 拉 `symbol_currency()` 拿到 scale 后换算,例如:

- 账户/费用等**币种金额** raw → 人类:`raw / currency_scale_k`(`currency_scale_k = 10^digit`)。
- 现货**下单量** size 是"手数",实际 base 数量 = `size × base_scale_k`;**下单价** price 是"价位步",实际报价 = `price × quote_scale_k`(再按币种精度展示)。
- 反向:人类值 → 引擎 i64 时乘回对应 scale。

> 引擎刻意不碰缩放/展示(纯定点、确定性);缩放是 client/展示层职责——`symbol_currency()` 就是给它的数据源。
>
> `PositionView` 的 `unrealized_pnl` / `liquidation_price` / `margin_ratio_scale_k` 等是**派生字段**(按传入 `now_ms` 对应的 mark price 实时算),用于风控展示——引擎内部不落库,每次查询重算。

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

两套独立实现同一撮合引擎,如何**证明行为等价**且**防止漂移**——三层递进防线(IT 翻译对拍 / 守恒 proptest / 黄金向量对拍 Java-oracle)+ 差分模糊 + 归一化规格,以及框架发现的真实问题,详见 **[`CONSISTENCY.md`](CONSISTENCY.md)**。

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

- 快照:`ExchangeCore::to_snapshot_bytes` / `from_snapshot_bytes`(bincode),供 Raft install-snapshot;测试观测缓冲(`last_cascade_events` 等)`#[serde(skip)]` 不进快照。
- `state_hash`(逐字段折叠复制态)用于多节点/快照往返一致性校验;它是 Rust 内部超集,不与 Java 的 hash 直接互比(跨实现比对见"一致性保障"③)。
