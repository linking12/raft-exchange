# P3：RiskEngine + engine 管线 → vanilla 现货端到端 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `exchange-core-rs` 上把 Java exchange-core 的 **vanilla 现货**风控 + 引擎管线移植到位，交付一个能"充值 → 下单 → 撮合 → 扣余额/收费/守恒"的确定性现货引擎（`CURRENCY_EXCHANGE_PAIR` only）。

**Architecture:** 复用 P1 的 `OrderBookNaive`；新增账户/规格模型、CoreArithmeticUtils 现货子集、RiskEngine 现货 R1(冻结)/R2(结算)、MatchingEngineRouter、单线程确定性 engine 管线(R1→ME→R2)、ExchangeApi。金额 `i64` 定点、`i128` 防溢出、有序容器、禁 `HashMap` 输出序。

**Tech Stack:** Rust 2021、`std::collections::BTreeMap`、`proptest`（守恒）。

**Spec:** `docs/superpowers/specs/2026-08-31-exchange-core-rust-port-design.md`
**风控业务参考（权威）:** `docs/superpowers/specs/2026-08-31-p3-spot-risk-reference.md`（下称"参考文档"，含 R1/R2 精确公式与行号）

## Global Constraints

- 沿用 P1：单 crate `exchange-core-rs`，`src/` 分 module；金额/价格/数量 `i64`，中间 `i128` 防溢出，全程无浮点；影响输出的迭代走确定序（`BTreeMap`/显式 sort），**禁 `std::collections::HashMap` 输出序**。
- 每个 Rust 文件头注释标注对应 Java 类；风控逻辑翻译须对照**参考文档**对应小节 + Java 行号。
- **范围裁定（仅本期）**：只做 `CURRENCY_EXCHANGE_PAIR` 现货。**明确不做**：期货/margin/position、loan、liquidation/ADL、funding、internal-transfer、多线程分片、snapshot（P4-P7/延后）。RiskEngine 里所有非现货分支翻译时以 `unimplemented!("P4: futures")` 之类清晰桩占位或直接不移植，绝不静默产出错误结果。
- 守恒是硬门槛：现货 R2 结算后必须满足参考文档 §6 的两条不变式；e2e 与 proptest 强制校验。
- 提交：中文 Conventional Commits，不擅自 push。

---

## 任务依赖顺序

1. Arithmetic 地基 → 2. 账户/规格/枚举模型 → 3. MatcherTradeEvent 扩字段 + orderbook 发射 → 4. RiskEngine R1 现货冻结 → 5. R2 reject/reduce 释放 → 6. R2 sell 结算 → 7. R2 buy 结算 → 8. 非交易命令(ADD_USER/BALANCE_ADJUSTMENT/symbol&currency add)+守恒桶 → 9. MatchingEngineRouter → 10. engine 管线 + ExchangeApi → 11. e2e 现货 + 守恒 proptest。

每个 task：先写失败测试（RED）→ 实现（GREEN）→ `cargo test --lib` 全绿、`-D warnings` 无告警 → commit。

---

### Task 1：CoreArithmeticUtils 现货子集

**Files:** Create `src/utils/arithmetic.rs`；Modify `src/lib.rs`（加 `pub mod utils;`）、新建 `src/utils/mod.rs`。
**Java 参照:** `core/utils/CoreArithmeticUtils.java`（全部为纯函数）。

**Interfaces — Produces（全部纯函数，`i64` 入出，中间 `i128`）:**
- `fn ceil_mul_div(a: i64, b: i64, d: i64) -> i64`、`fn trunc_mul_div(a,b,d) -> i64`、`fn ceil_mul_mul_div(a,b,c,d) -> i64`、`fn ceil_divide(a,b)->i64`、`fn convert_scale(amount: i64, from_k: i64, to_k: i64) -> i64`（= `amount * from_k / to_k`，注意取整方向对照 Java）。
- 现货金额：`calculate_amount_ask(size)->i64` (=size)、`calculate_amount_bid(size, price)->i64` (=size*price)、`calculate_amount_bid_taker_fee(size, price, spec)->i64`、`calculate_amount_bid_taker_fee_for_budget(size, budget, spec)->i64`、`calculate_amount_bid_release_corr_maker(size, hold_price, trade_price, spec)->i64`、`calculate_taker_fee(size, price, spec)->i64`、`calculate_maker_fee(size, price, spec)->i64`、`is_ask_price_too_low(price, spec)->bool`。
- scale 便捷：`size_price_to_currency_scale(amount, spec, currency_spec)->i64`、`symbol_to_currency_scale(amount, spec, currency_spec)->i64`（内部调 `convert_scale`；`spec` 需 `base_scale_k/quote_scale_k`，`currency_spec` 需 `currency_scale_k`）。
- 依赖 `spec`（`taker_fee/maker_fee/fee_scale_k/base_scale_k/quote_scale_k`，Task 2 定义）——为解 Task 1/2 循环依赖，Task 1 先用一个**本地最小 trait 或裸参数**：把这些 fee/scale 函数设计成接受裸 `taker_fee: i64, fee_scale_k: i64` 等标量参数（不依赖 Task 2 的 struct），Task 4+ 调用时从 spec 取字段传入。**Ruling: arithmetic 层不 import 账户模型，保持纯函数无依赖。**

- [ ] Step 1：为每个函数写单测（RED）——**测试值直接取 Java `CoreArithmeticUtilsTest.java`（若存在于 `src/test/`）**；对 `ceil/trunc` 各覆盖整除与非整除；`is_ask_price_too_low` 覆盖固定/比例/takerFee==0。
- [ ] Step 2：`cargo test --lib utils::arithmetic` 确认失败。
- [ ] Step 3：实现（对照 Java 逐函数；`ceilMulDiv` = `(a*b + d - 1)/d` 用 `i128`；`truncMulDiv` = `a*b/d`；注意 Java 的 `ceilMulMulDiv(a,b,c,d)` = ceil(a*b*c/d)，三乘用 `i128`）。
- [ ] Step 4：`cargo test --lib` 全绿。
- [ ] Step 5：commit — `feat(utils): CoreArithmeticUtils 现货子集（scale/fee/取整纯函数）`

---

### Task 2：账户/规格/枚举模型 + 注册表

**Files:** Create `src/api/spec.rs`（symbol/currency spec）、`src/account/mod.rs` + `src/account/profile.rs`（UserProfile）+ `src/account/registry.rs`（两个注册表）；Modify `src/api/enums.rs`（加枚举）、`src/api/command.rs`（OrderCommand 加现货所需字段）、`src/lib.rs`。
**Java 参照:** `common/CoreSymbolSpecification.java`(现货子集)、`common/CoreCurrencySpecification.java`、`common/UserProfile.java`(现货子集)、`processors/SymbolSpecificationProvider.java`、`processors/UserProfileService.java`、`common/{SymbolType,UserStatus,BalanceAdjustmentType}.java`、`common/cmd/OrderCommandType.java`。

**Interfaces — Produces:**
- 枚举（加到 `enums.rs`，`#[derive(Debug,Clone,Copy,PartialEq,Eq)]` + 码值对齐 Java）：`SymbolType { CurrencyExchangePair, FuturesContract, ... }`（现货只用前者，其余可仅列）、`UserStatus { Active, Suspended }`、`BalanceAdjustmentType { Adjustment, Suspend }`、`OrderCommandType`（列出现货相关：`PlaceOrder, CancelOrder, MoveOrder, ReduceOrder, OrderBookRequest, BalanceAdjustment, AddUser, BinaryDataCommand, Reset, Nop, ...`，含 `is_non_trading()` / `is_loan()` 分类方法，对照 Java）。
- `struct CoreCurrencySpecification { currency: i32, currency_scale_k: i64 }`。
- `struct CoreSymbolSpecification { symbol_id: i32, symbol_type: SymbolType, base_currency: i32, quote_currency: i32, base_scale_k: i64, quote_scale_k: i64, taker_fee: i64, maker_fee: i64, fee_scale_k: i64 }` + `fn is_fixed_fee(&self)->bool` (=`fee_scale_k==0`)。（margin/loan 字段本期不移植。）
- `struct UserProfile { uid: i64, user_status: UserStatus, accounts: BTreeMap<i32,i64>, exchange_locked: BTreeMap<i32,i64> }` + helpers：`fn account(&self,cur)->i64`(缺省0)、`fn locked(&self,cur)->i64`、`fn add_to_account(&mut self,cur,delta)`、`fn add_to_locked(&mut self,cur,delta)`、`fn state_hash(&self)->i32`（确定性）。（positions/loans/dedup 本期不移植；幂等表在 Task 8 视需要以最小 `BTreeSet<i64>` 引入。）
- `struct SymbolSpecificationProvider { symbols: BTreeMap<i32, CoreSymbolSpecification>, currencies: BTreeMap<i32, CoreCurrencySpecification>, spot_pair_index: BTreeSet<(i32,i32)> }` + `fn add_symbol(&mut self, spec) -> CommandResultCode`（拒重复 symbol_id；现货额外拒重复 `(base,quote)`）、`add_currency`、`get_symbol/get_currency`。
- `struct UserProfileService { users: BTreeMap<i64, UserProfile> }` + `fn add_empty_user_profile(uid)->CommandResultCode`、`get`/`get_mut`、`get_or_add_suspended(uid)`。
- `OrderCommand` 追加现货字段（若 P1 未含）：确保有 `reserve_bid_price`（已有）、`command: OrderCommandType`、以及 R2 需要的 `matcher_event` 链（已有）。补 `CommandResultCode` 现货错误码：`AuthInvalidUser, InvalidSymbol, RiskNsf, RiskInvalidReserveBidPrice, RiskAskPriceLowerThanFee, UserMgmtUserAlreadyExists, UserMgmtAccountBalanceAdjustmentNsf, UserMgmtAccountBalanceAdjustmentAlreadyAppliedSame, Success, ValidForMatchingEngine, ...`（码值对照 Java `CommandResultCode.java`）。

- [ ] Step 1：写单测（RED）：`add_symbol` 拒重复 symbol_id 与重复 (base,quote)；`add_empty_user_profile` 拒重复 uid；UserProfile account/locked helpers；枚举码值与 `is_non_trading` 分类。
- [ ] Step 2：确认失败。
- [ ] Step 3：实现结构 + 注册表 + 枚举。
- [ ] Step 4：`cargo test --lib` 全绿。
- [ ] Step 5：commit — `feat(account): UserProfile/SymbolSpec/Currency + 注册表 + 现货枚举与错误码`

---

### Task 3：MatcherTradeEvent 扩字段 + orderbook 发射（Ruling D 现货部分）

**Files:** Modify `src/api/event.rs`、`src/orderbook/naive.rs`（事件构造处）、相关订单簿测试。
**Java 参照:** `common/MatcherTradeEvent.java`；参考文档 §4（现货 R2 只读 `event_type,size,price,bidder_hold_price,matched_order_id,matched_order_uid,next`）。

**Interfaces — Produces/Changes:**
- `MatcherTradeEvent` 新增两字段：`bidder_hold_price: i64`、`matched_order_uid: i64`（保留 `bid_gt_ask` 不动，现货不读）。
- `OrderBookNaive` 撮合发 Trade 事件时填：`matched_order_uid` = maker `Order.uid`；`bidder_hold_price` = 该成交里 **BID 方**的 `reserve_bid_price`（maker 是 BID 时取 maker.reserve_bid_price；taker 是 BID 时取 taker 的 reserve_bid_price——对照 Java `OrderBookEventsHelper.sendTradeEvent` 的 `bidderHoldPrice` 赋值：它取 `bid.reserveBidPrice`，即成交双方中 BID 那一方的保留价）。REJECT/REDUCE 事件的 `bidder_hold_price` 亦按 Java 填（挂单主是 BID 时取其 reserve_bid_price）。
- **Consumes:** P1 `OrderBookNaive`、`Order`（有 `uid`、`reserve_bid_price`）。

- [ ] Step 1：写/改测试（RED）：新增一条撮合测试断言 Trade 事件的 `matched_order_uid == maker.uid` 且 `bidder_hold_price ==` 预期 BID 保留价（含 maker=BID 与 taker=BID 两种）。
- [ ] Step 2：确认失败。
- [ ] Step 3：加字段 + 在 `naive.rs` 所有事件构造点填值（对照 Java sendTradeEvent/sendReduceEvent/attachRejectEvent 的 bidderHoldPrice 语义）。修已有测试的事件构造（若用了字面量结构体）。
- [ ] Step 4：`cargo test --lib`（P1 的 79 + 新增）全绿。
- [ ] Step 5：commit — `feat(orderbook): MatcherTradeEvent 增 bidder_hold_price/matched_order_uid 并在撮合发射`

---

### Task 4：RiskEngine R1 — 现货下单冻结（placeExchangeOrder）

**Files:** Create `src/processors/mod.rs` + `src/processors/risk.rs`（`RiskEngine` 起步）；Modify `src/lib.rs`。
**Java 参照:** 参考文档 §2；`RiskEngine.placeOrderRiskCheck/placeOrder/placeExchangeOrder`（399–685）。

**Interfaces — Produces:**
- `struct RiskEngine { /* 借用或持有 UserProfileService + SymbolSpecificationProvider 的访问；本期单 shard，无分片 */ }`。设计：RiskEngine 方法接收 `&mut UserProfileService`、`&SymbolSpecificationProvider` 与 `&mut OrderCommand`（或持有引用）——**Ruling: 本期单 shard、单线程，RiskEngine 不做 uidForThisHandler 分片，视所有 uid 为本 shard。**
- `fn place_order_risk_check(&mut self, cmd, ups, ssp) -> CommandResultCode`：加载 user（无→`AuthInvalidUser`）、spec（无→`InvalidSymbol`）→ `place_exchange_order`。
- `fn place_exchange_order(...) -> CommandResultCode`：按参考文档 §2 —— BID 用 `calculate_amount_bid_taker_fee[_for_budget]`（reserve 价校验）、ASK 用 `calculate_amount_ask` + `is_ask_price_too_low`；NSF 检查 `accounts[cur]-exchange_locked[cur]-lock < 0`；成功 `exchange_locked[cur] += lock`，accounts 不动，返回 `ValidForMatchingEngine`。
- **Consumes:** Task 1 arithmetic、Task 2 模型、Task 2 错误码。

- [ ] Step 1：写单测（RED）：给定 user 有 quote 余额，下 BID 限价单 → 返回 `ValidForMatchingEngine` 且 `exchange_locked[quote]` 增加 = `size*reserveBidPrice + fee` 缩放值；余额不足 → `RiskNsf` 且不冻结；ASK 单冻结 base=size；`reserveBidPrice < price` → `RiskInvalidReserveBidPrice`；`is_ask_price_too_low` → `RiskAskPriceLowerThanFee`。用固定费与比例费各一组。
- [ ] Step 2：确认失败。
- [ ] Step 3：实现（对照参考文档 §2 逐行）。非现货 symbol 分支 `unimplemented!("P4: futures/margin")`。
- [ ] Step 4：`cargo test --lib` 全绿。
- [ ] Step 5：commit — `feat(risk): R1 现货下单冻结 placeExchangeOrder（hold + NSF + reserve 校验）`

---

### Task 5：RiskEngine R2 — reject/reduce 释放（handleMatcherRejectReduceEventExchange）

**Files:** Modify `src/processors/risk.rs`。
**Java 参照:** 参考文档 §3a；`RiskEngine.handleMatcherRejectReduceEventExchange`（1094–1125）+ 分派 §3 头。

**Interfaces — Produces:**
- `fn handler_risk_release(&mut self, cmd, ups, ssp)`：现货分派骨架（参考文档 §3 的 if/else）：先处理链头 REJECT/REDUCE（调本任务函数），再把 mte 前移交给 Task 6/7（本任务先留 `// TODO(Task 6/7)` 占位调用）。
- `fn handle_matcher_reject_reduce_event_exchange(&mut self, cmd, mte, spec, taker_sell, taker_up)`：按 §3a 释放 `exchange_locked`（ASK 释放 base=size；BID 按 GTC/FOK_BUDGET/IOC_BUDGET/普通 分支用对应 amount 函数、`bidder_hold_price`），accounts 不动。
- **Consumes:** Task 3 的事件字段（`bidder_hold_price`、`size`、`price`）、Task 1 arithmetic。

- [ ] Step 1：写单测（RED）：下 BID 限价单冻结后，撮合产生纯 REJECT（无成交）→ `exchange_locked[quote]` 释放回 0，accounts 不变；ASK 部分成交后剩余 REDUCE → 释放剩余 base。（可先用手工构造的 cmd+matcher_event 链驱动，不必经完整 orderbook。）
- [ ] Step 2：确认失败。
- [ ] Step 3：实现 §3a + 分派骨架。
- [ ] Step 4：`cargo test --lib` 全绿。
- [ ] Step 5：commit — `feat(risk): R2 现货 reject/reduce 释放冻结`

---

### Task 6：RiskEngine R2 — sell 结算（handleMatcherEventsExchangeSell）

**Files:** Modify `src/processors/risk.rs`。
**Java 参照:** 参考文档 §3b；`handleMatcherEventsExchangeSell`（1134–1227）。

**Interfaces — Produces:**
- `fn handle_matcher_events_exchange_sell(&mut self, cmd, mte, spec, taker_up, fees: &mut BTreeMap<i32,i64>)`：逐 TRADE 事件结算 maker（买方）：`exchange_locked[quote]-=holdQuote`、`accounts[quote]+=quoteRefund-holdQuote`、`accounts[base]+=size`；累加 taker/maker notional/size；循环后 taker（卖方）一次结算：释放 base hold、`accounts[quote]+=notional-takerFee`；`fees[quote]+=takerFee+makerFee(avg)`。
- **Consumes:** Task 1（`calculate_amount_bid_taker_fee`/`_release_corr_maker`/`calculate_taker_fee`/`calculate_maker_fee`/scale）、Task 3 事件字段、Task 8 的 `fees` 桶（本任务先接收 `&mut fees` 参数，Task 8 接线）。

- [ ] Step 1：写单测（RED）：构造 taker=ASK 与一个本地 maker=BID 的成交链，断言双方 accounts(base/quote) 与 exchange_locked 变动、taker 得 `notional-takerFee`、`fees[quote]` 累加 = takerFee+makerFee；覆盖价格改善退款（bidderHoldPrice>price）与固定/比例费。**并断言该笔 base 腿精确守恒、quote 腿守恒 modulo fees。**
- [ ] Step 2：确认失败。
- [ ] Step 3：实现 §3b（用 i128 中间量；均价算平台费避免 dust）。
- [ ] Step 4：`cargo test --lib` 全绿。
- [ ] Step 5：commit — `feat(risk): R2 现货卖方结算 handleMatcherEventsExchangeSell`

---

### Task 7：RiskEngine R2 — buy 结算（handleMatcherEventsExchangeBuy）

**Files:** Modify `src/processors/risk.rs`。
**Java 参照:** 参考文档 §3c；`handleMatcherEventsExchangeBuy`（1238–1343）。

**Interfaces — Produces:**
- `fn handle_matcher_events_exchange_buy(&mut self, cmd, mte, spec, taker_up, fees)`：镜像 Task 6；maker（卖方）：释放 base hold、`accounts[quote]+=notional-makerFee`；taker（买方）：BUDGET/普通两子情形算 `holdQuote/leftover/quoteRefund`、`exchange_locked[quote]-=holdQuote`、`accounts[quote]+=quoteRefund-holdQuote`、`accounts[base]+=takerSize`；`fees[quote]+=takerFee+makerFee(avg)`。
- 把 Task 5 分派骨架里的 `// TODO(Task 6/7)` 换成真实调用 sell/buy。
- **Consumes:** Task 1/3/6。

- [ ] Step 1：写单测（RED）：taker=BID 与本地 maker=ASK 成交链；断言双方 accounts/locked、taker 得 base 且退还 quote 溢冻（价格改善+费溢冻）、`fees` 累加；覆盖 BUDGET 与普通、固定/比例费；断言守恒（base 精确、quote modulo fees）。
- [ ] Step 2：确认失败。
- [ ] Step 3：实现 §3c + 接线分派。
- [ ] Step 4：`cargo test --lib` 全绿。
- [ ] Step 5：commit — `feat(risk): R2 现货买方结算 handleMatcherEventsExchangeBuy + 分派接线`

---

### Task 8：非交易命令（ADD_USER / BALANCE_ADJUSTMENT / symbol&currency add）+ 守恒桶

**Files:** Modify `src/processors/risk.rs`（或新 `src/processors/dispatch.rs`）、`src/account/registry.rs`。
**Java 参照:** 参考文档 §5；`RiskEngineCommandDispatcher.java`（ADD_USER/BALANCE_ADJUSTMENT/handleBinaryMessage）、`UserProfileService.balanceAdjustment`。

**Interfaces — Produces:**
- RiskEngine 持有 shard 全局桶：`adjustments: BTreeMap<i32,i64>`、`fees: BTreeMap<i32,i64>`（供 Task 6/7 写）；（`suspends` 本期可省，SUSPEND 型延后）。
- `fn add_user(&mut self, cmd, ups) -> CommandResultCode`（§5）。
- `fn balance_adjustment(&mut self, cmd, ups) -> CommandResultCode`：`currency=cmd.symbol, amountDiff=cmd.price`；提现 NSF 用现货口径 `accounts-exchange_locked`；幂等（最小 `BTreeSet<i64>` per user 或 UserProfile 上的 dedup 集，claim `cmd.order_id`）；成功 `accounts[cur]+=amountDiff` 且 `adjustments[cur]-=amountDiff`。
- symbol/currency add：本期**不做二进制组帧**（BinaryCommandsProcessor 太重）。**Ruling: 提供直接 API**——`SymbolSpecificationProvider::add_symbol/add_currency`（Task 2 已有）由 ExchangeApi（Task 10）以配置命令直接调用，跳过 binary 帧。e2e 用它播种 symbol/currency/用户余额（`add_empty_user_profile`+`balance_adjustment` 播种）。在计划文档记此偏离。
- `is_non_trading` 路由：engine（Task 10）据 `OrderCommandType` 把这些命令走 RiskEngine 非交易处理，不进 ME。

- [ ] Step 1：写单测（RED）：ADD_USER 建空账户、重复 → `UserMgmtUserAlreadyExists`；BALANCE_ADJUSTMENT 充值 `accounts` 增且 `adjustments` 等额反向、`Σaccounts+adjustments` 守恒；提现超额 → `RiskNsf`/`..._NSF`；同 order_id 重复 → `...AlreadyAppliedSame`。
- [ ] Step 2：确认失败。
- [ ] Step 3：实现。
- [ ] Step 4：`cargo test --lib` 全绿。
- [ ] Step 5：commit — `feat(risk): ADD_USER/BALANCE_ADJUSTMENT + adjustments 守恒桶（symbol/currency 走直接 API）`

---

### Task 9：MatchingEngineRouter

**Files:** Create `src/processors/matching_router.rs`；Modify `src/processors/mod.rs`。
**Java 参照:** `processors/MatchingEngineRouter.java`（329）。

**Interfaces — Produces:**
- `struct MatchingEngineRouter { books: BTreeMap<i32, OrderBookNaive> }`（symbol→book；本期单 shard 全量）。
- `fn process_order(&mut self, cmd: &mut OrderCommand)`：按 `cmd.command` 分派到对应 symbol 的 `IOrderBook`（PlaceOrder→new_order、Cancel/Move/Reduce→对应方法、OrderBookRequest→fill_l2 写 `cmd.market_data`）；未知 symbol → 相应错误码。仅当 R1 结果为 `ValidForMatchingEngine` 才撮合（对照 Java `IOrderBook.processCommand`）。
- symbol 新增：`fn add_symbol(&mut self, spec)`（为新 spot symbol 建空 `OrderBookNaive`）。
- **Consumes:** P1 `OrderBookNaive`/`IOrderBook`、Task 2 spec。

- [ ] Step 1：写单测（RED）：注册一个 symbol，PlaceOrder 路由到其 book 并撮合；OrderBookRequest 回填 L2；未知 symbol 返回错误；R1 非 `ValidForMatchingEngine` 的命令不撮合。
- [ ] Step 2：确认失败。
- [ ] Step 3：实现。
- [ ] Step 4：`cargo test --lib` 全绿。
- [ ] Step 5：commit — `feat(matching): MatchingEngineRouter 按 symbol 路由到 OrderBook`

---

### Task 10：engine 确定性管线 + ExchangeApi

**Files:** Modify `src/engine/mod.rs`（+ `src/engine/exchange_core.rs`、`src/engine/api.rs`）。
**Java 参照:** `core/ExchangeCore.java`、`core/ExchangeApi.java`、`core/SimpleEventsProcessor.java`；设计文档 §4。

**Interfaces — Produces:**
- `struct ExchangeCore { risk: RiskEngine, matching: MatchingEngineRouter, ups: UserProfileService, ssp: SymbolSpecificationProvider }`（本期单 shard、单线程）。
- `fn process_command(&mut self, cmd: &mut OrderCommand)`：**确定性顺序管线**（设计文档 §4）：
  - 若 `cmd.command.is_non_trading()` → RiskEngine 非交易处理（Task 8），不进 ME。
  - 否则交易命令：`risk.pre_process(cmd)`（R1，PlaceOrder 冻结/其余 no-op）→ 若 `cmd.result_code == ValidForMatchingEngine` 则 `matching.process_order(cmd)`（ME）→ `risk.handler_risk_release(cmd)`（R2 结算，读 `cmd.matcher_event`，写 fees）。
  - 单 symbol/单 shard，无 grouping 线程；但保留"R1→ME→R2"顺序与 fees/adjustments 桶。
- `ExchangeApi`：便捷构造命令 + 提交 + 取结果/事件的门面（`place_order`、`cancel`、`move_order`、`balance_adjustment`、`add_user`、`add_symbol`、`add_currency`、`request_l2` 等），供 e2e 与未来使用。
- **Consumes:** Task 4-9。

- [ ] Step 1：写单测（RED）：经 ExchangeApi 走完整一笔——add_currency+add_symbol+add_user+balance_adjustment 播种两个用户，一方挂卖一方吃单 → 断言双方最终 base/quote 余额与 `fees` 符合预期（一笔简单现货成交端到端）。
- [ ] Step 2：确认失败。
- [ ] Step 3：实现管线 + API。
- [ ] Step 4：`cargo test --lib` 全绿。
- [ ] Step 5：commit — `feat(engine): 确定性现货管线 R1→ME→R2 + ExchangeApi`

---

### Task 11：端到端现货场景 + 守恒 proptest

**Files:** Create `src/engine/e2e_tests.rs`（或 `#[cfg(test)]` 于 engine）；加 `proptest` 到 `[dev-dependencies]`。
**Java 参照:** `ITExchangeCoreIntegration*` 里的现货场景（若有）；设计文档 §7、参考文档 §6。

**Interfaces:** 无新生产接口；测试。

- [ ] Step 1：写 e2e 场景测试：多用户、多笔限价/IOC/FOK 成交、撤单释放；每步后断言 **全局守恒**：对每个 currency，`Σ_users accounts[cur] + adjustments[cur] + fees[cur] == 0`（参考文档 §6；播种时 `adjustments` 已等额反向，故总和恒 0）。
- [ ] Step 2：加 `proptest`：随机生成"播种 + N 条现货命令流"，跑完 assert 上述守恒式对所有 currency 成立，且无 panic、无负 `accounts`/`exchange_locked`。
- [ ] Step 3：跑通并调稳（proptest 缩小失败用例）。
- [ ] Step 4：`cargo test --lib` 全绿（含 proptest）。
- [ ] Step 5：commit — `test(engine): 现货端到端场景 + 守恒 proptest`

---

## P3 完成定义（DoD）
- `cargo build`/`cargo test --lib` 全绿，`-D warnings` 无告警。
- 经 `ExchangeApi` 能：建币/建 symbol/建用户/充值 → 下单(GTC/IOC/FOK) → 撮合 → 双方余额按 名义±费 结算 → �leave 释放冻结。
- 守恒不变式（参考文档 §6）在 e2e 与 proptest 下恒成立；无浮点、无 HashMap 输出序。
- 非现货路径以清晰桩占位，未静默产错。

## 自查（对照 spec 与参考文档）
- 参考文档 §1 路由→Task 8/10；§2 R1→Task 4；§3a→Task 5；§3b→Task 6；§3c→Task 7；§4 事件字段→Task 3；§5 非交易→Task 8；§6 守恒→Task 11。全覆盖。
- 设计文档 §4 单线程确定性管线→Task 10；§5 i64/有序容器→Global Constraints + 各 task；§6 snapshot→P7 延后。
- 范围裁定（现货 only、跳 binary 组帧用直接 API、单 shard 无分片、futures/loan 桩占位）已在 Global Constraints/Task 4/8 明示。
- 类型一致性：`fees: &mut BTreeMap<i32,i64>` 于 Task 6/7 引入、Task 8 拥有、Task 10 接线一致；`RiskEngine` 方法签名跨 Task 4-8 沿用。

---

## P3 完成状态（2026-09-01）

11/11 任务完成，逐任务 TDD + 双评审。现货引擎端到端跑通：建币/建 symbol/建用户/充值 → 下单(GTC/IOC/FOK) → 撮合 → 双方按 名义±费 结算 → 撤单释放冻结；单线程确定性 R1→ME→R2 管线 + ExchangeApi。`cargo test --lib` 211 绿（含 5 e2e 场景 + 256-case 守恒 proptest + 2 个继承缺陷 characterization），`-D warnings` 无告警。

### 决策裁定
- **用户决策：两个继承缺陷均"保 Java 一致"（不改生产代码）** —— 理由：都是 Java 参考实现自身的既有缺陷、Rust 逐字对等；且若将来做 Rust↔Java 差分对拍验证，必须逐位匹配 Java（含这些 bug）。

### ⚠ 继承自 Java 的守恒缺陷（忠实复现，未修，务必知悉）
两者均为 Java `exchange-core` 参考实现自身的既有缺陷（经取证确认），Rust 逐字对等复现，**非移植错误**，按用户决策刻意保留：

1. **缺陷#1 — exchange_locked 可变负（比例费 BID 跨多次释放）**：`ceil(a)+ceil(b) ≥ ceil(a+b)` 超可加性。比例费 BID 挂单被拆成 ≥2 次释放（两 taker 分吃 / reduce+cancel）时释放总额超原始冻结额，`exchange_locked[quote]` 变负 → 虚高可用余额（偿付会计暴露）。**真资金守恒 Σaccounts+adjustments+fees 仍成立**（只破 locked>=0 标记）。Java: `RiskEngine.java:1154-1163/1094-1120`, `CoreArithmeticUtils.java:96-101`；Java 自身守恒检查对此恒等失明。Rust characterization test 记录之。
2. **缺陷#2 — Σaccounts+fees 单笔漂 ±1（比例费 + maker_fee≠0，卖方与买方两侧都有）**：`calculateAmountBidReleaseCorrMaker` 合并 ceil(takerFee,makerFee) 与 fees 桶独立 `calculateMakerFee` ceil 不一致（`ceil(a)−ceil(b)≠ceil(a−b)`）。maker_fee≠0 时**单笔**成交即破**真守恒** Σaccounts+fees ±1（会累积）。
   - **卖方侧**（taker=ASK，maker=BID）：Java `RiskEngine.java:1222` + `CoreArithmeticUtils.java:126-140/175-178`；Rust `arithmetic.rs:273-292/230-237` + `risk.rs` sell 结算。
   - **买方侧**（taker=BID，maker=ASK，终审确认同类忠实缺陷）：maker fee 按 `mte.price` 逐事件收、按 `avg_maker_price` 单 ceil 入 fees（Java `RiskEngine.java:1274` vs `:1338`；Rust `risk.rs:576` vs `:687`）。
   - proptest 比例费生成器 pin `maker_fee=0` 同时规避两侧（test-param-only，已注释）。**未来修复须同时覆盖买卖两侧**（勿只修卖方那一处表达式）。

**若未来要"修得比 Java 更正确"**：新开一期，dust→fees 桶 + per-order 剩余冻结追踪、末笔精确释放（参考文档 §6 已预告方向）；但那会破坏 Rust↔Java 差分对拍的逐位一致性，需权衡。

### P4+ carry-forward（承 P1 未了 + P3 新增）
- （P1 承接）MatcherTradeEvent 未补的 Java 全字段（futures 用）、IOrderBook 的 getOrderById/validate_internal_state。
- **⚠ move_order 未验证（P4 必做）**：`ExchangeApi::move_order` 已暴露为公开方法，但 (a) 缺 Java 的 reserve-bid-price 上限守卫（BID 移到 > reserveBidPrice 应返回 `MATCHING_MOVE_FAILED_PRICE_OVER_RISK_LIMIT`，现无条件重撮合 → 可能对未冻结的 quote 结算、欠抵押，真守恒风险）；(b) e2e 场景与 proptest 均**未覆盖** move 路径（GenCmd 无 Move 变体）。**P3 中 move_order 视为未验证、非生产就绪**。P4 补守卫（P3 已有 SymbolType/spec 可做）+ 给 proptest 加 Move 臂。
- （P3 新增）上述两个继承守恒缺陷；processed_tx_ids 无时间窗去重且未入 state_hash（raft/快照需处理）；suspends 桶 + SUSPEND 型 balance-adjust；binary 组帧（当前走直接 API）；snapshot。
- **性能/并发管线（选型已定）**：Java 的 LMAX Disruptor 五段（Grouping → R1∥Journal → ME → R2，分片）在性能期用 **`disruptor-rs`**（crates.io 的 `disruptor` crate，nicholassm/disruptor-rs）复现——它是 LMAX Disruptor 的 Rust 移植，单生产者 + 依赖定序消费者。**约束：多线程管线必须保持逐位确定性**（分片归属 uid%N/symbol%M + 阶段定序照搬 Java），否则破坏 Raft 状态机前提与 Rust↔Java 差分对拍。
- 下一阶段可选：P2（OrderBookDirectImpl，链表→slab+索引，另出计划）或直接 P4（期货/统一账户）。
