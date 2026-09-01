# P2：OrderBookDirectImpl（slab+索引）+ 与 Naive 差分对拍 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development。Steps 用 `- [ ]`。

**Goal:** 把 Java `OrderBookDirectImpl`（性能版订单簿，侵入式双向链）移植为 Rust slab+索引结构，实现 `IOrderBook`，与已移植的 `OrderBookNaiveImpl` **外部结果逐位一致**；用 Naive↔Direct 差分对拍证明等价。

**Architecture:** slab of `DirectOrder`(索引替指针) + slab of `Bucket` + 两个 `BTreeMap<i64,BucketIdx>`(价桶图) + `BTreeMap<i64,OrderIdx>`(orderId 索引) + best-ask/best-bid 索引。单侧一条双向链(价格+FIFO)。弃对象池/日志。事件复用现有 `MatcherTradeEvent` 规则。

**Tech Stack:** Rust 2021、`std::collections::BTreeMap`、`proptest`。

**Spec:** `docs/superpowers/specs/2026-08-31-exchange-core-rust-port-design.md`
**Direct 参考（权威）:** `docs/superpowers/specs/2026-09-01-p2-orderbook-direct-reference.md`（下称"参考"，§1-9 + Java 行号）
**Java 源:** `exchange-core/src/main/java/exchange/core2/core/orderbook/OrderBookDirectImpl.java`

## Global Constraints
- 沿用：单 crate、`core::orderbook` 下加 `order_book_direct_impl.rs`；金额 `i64`/中间 `i128`；影响输出的迭代走确定序、**禁 `HashMap`**（orderId 索引也用 `BTreeMap`）；文件头注释标 Java 类。
- **等价铁律**：Direct 对任意命令流的 trade/reduce/reject 事件链、L2、getOrderById、结果码，必须与 `OrderBookNaiveImpl` **逐位一致**。这是 P2 的验收核心。
- **Ruling P2-1（FOK_BUDGET）**：不复刻 Direct 里"BID FOK_BUDGET 用 cmd.price 当价上限"的巧合（参考 §8）——FOK_BUDGET 撮合镜像 Naive、不加价上限。
- slab 用 `Vec<Slot>` + free-list（LIFO 确定）；不引 slotmap 依赖。free/复用时机对齐参考 §2.1(h)（撮合中填满的 maker 推迟到 best 指针更新后再复用槽）。
- 提交中文 Conventional Commits，不擅自 push。

## 任务顺序
1. 数据结构 + IOrderBook 骨架 + slab/bucket 分配释放原语
2. insertOrder（入链+桶）+ 纯挂单 GTC（不撮合）+ fillAsks/fillBids(L2) + validate_internal_state(基础)
3. tryMatchInstantly（撮合主循环 + splice-out）+ GTC 撮合
4. IOC / FOK_BUDGET / IOC_BUDGET（含 Ruling P2-1）
5. cancel / reduce / move + removeOrder
6. state_hash + 完整 validate_internal_state
7. Naive↔Direct 差分对拍 proptest + 对 Direct 跑 OrderBookBaseTest 场景（验收）

每任务 TDD：失败测试→实现→`cargo test --lib` 全绿（当前基线 211）+ `-D warnings`→commit。

---

### Task 1：Direct 数据结构 + IOrderBook 骨架 + slab 原语
**Files:** Create `core/orderbook/order_book_direct_impl.rs`；Modify `core/orderbook/mod.rs`（声明 + `pub use OrderBookDirectImpl`）。
**参考:** §1、§9。

**Interfaces — Produces:**
- `struct DirectOrder { order_id:i64, price:i64, size:i64, filled:i64, filled_notional:i64, reserve_bid_price:i64, action:OrderAction, order_type:OrderType, command:OrderCommandType, uid:i64, timestamp:i64, user_cookie:i32, parent:Option<BucketIdx>, next:Option<OrderIdx>, prev:Option<OrderIdx> }`（`OrderIdx=usize`/`BucketIdx=usize` newtype 或裸 usize）。
- `struct Bucket { volume:i64, num_orders:i32, tail:OrderIdx }`。
- `struct OrderBookDirectImpl { orders: Vec<Option<DirectOrder>>, order_free: Vec<usize>, buckets: Vec<Option<Bucket>>, bucket_free: Vec<usize>, ask_price_buckets: BTreeMap<i64,usize>, bid_price_buckets: BTreeMap<i64,usize>, order_id_index: BTreeMap<i64,usize>, best_ask: Option<usize>, best_bid: Option<usize>, symbol_spec: Option<CoreSymbolSpecification> }`（symbol_spec 仅 move 风控用，可 Option/占位）+ `fn new()`。
- slab 原语：`alloc_order(DirectOrder)->usize`、`free_order(usize)`、`alloc_bucket(Bucket)->usize`、`free_bucket(usize)`、`order(idx)->&`/`order_mut`、`bucket(idx)->&`/`_mut`（free-list LIFO）。
- `impl IOrderBook for OrderBookDirectImpl`：本任务先给编译占位（`new_order` 空/`todo` 无 panic 的最小体；后续任务填），`fill_l2`/`cancel`/`reduce`/`move`/`state_hash` 占位返回合理默认。**保证 crate 编译 + 211 绿。**
- Consumes: `core::common` 的 Order 相关类型、enums、`core::common::cmd::OrderCommand`。

- [ ] Step1 写失败测试：`OrderBookDirectImpl::new()` 建空簿；slab alloc/free round-trip（alloc 两个、free 一个、再 alloc 复用同槽）。
- [ ] Step2 确认失败。Step3 实现结构+原语+骨架。Step4 `cargo test --lib` 全绿。Step5 commit `feat(orderbook): OrderBookDirectImpl 数据结构 + slab 原语 + IOrderBook 骨架`。

---

### Task 2：insertOrder + 纯挂单 GTC + L2 + validate 基础
**Files:** Modify `order_book_direct_impl.rs`。**参考:** §2.3, §2.2(挂单部分), §5, §7。Java: `insertOrder(:638-715)`, `fillAsks/fillBids(:916-935)`。

**Interfaces:**
- `fn insert_order(&mut self, order_idx, free_bucket: Option<usize>)`：按 §2.3 A/B 两情形入链+桶（复用 free_bucket 或新建；查邻桶 `ask→lower/bid→higher`；成新 best 或插边界）。维护 best_ask/best_bid、bucket volume/num_orders、链指针。
- `new_order` 的 **GTC 无撮合路径**：先不撮合（Task 3 加），只把 GTC 挂上（建 DirectOrder、order_id_index.put、insert_order）。dup id 处理留 Task 3 完整版（本任务可先按无撮合场景处理）。
- `fn fill_l2(&self, size)->L2MarketData`：§5，ask 升序/bid 降序迭代价桶图，取 `tail.price`/`bucket.volume`/`num_orders`，截断 size；`size<=0`→零档（对齐 Naive 的 `fill_l2` 语义）。
- `fn validate_internal_state(&self)`：§7 的核心子集（best.next==null、链↔order_id_index 同集、bucket.volume==Σremaining、每价一桶）——测试与后续任务复用。

- [ ] Step1 失败测试：挂 3 个不同价 ask（乱序）→ `fill_l2` 升序、每档量对；挂同价多单→FIFO tail 正确、num_orders/volume 对；`validate_internal_state` 通过。挂 bid 侧对称一组。
- [ ] Step2-4 TDD。Step5 commit `feat(orderbook): Direct insertOrder + 挂单 GTC + L2 + validate 基础`。

---

### Task 3：tryMatchInstantly + GTC 撮合
**Files:** Modify `order_book_direct_impl.rs`。**参考:** §2.1, §2.2, §6, §2.1(h) 释放时机。Java: `tryMatchInstantly(:253-372)`, `newOrderPlaceGtc(:148-189)`。

**Interfaces:**
- `fn try_match_instantly(&mut self, taker: &TakerCtx, trigger_cmd) -> (i64,i64)`：§2.1 逐步——从 best 沿 `.prev` 撮合、成交在 maker 价、桶 volume 即减、填满 maker 当场摘 index/桶/free（推迟槽复用）、发 TRADE 链到 `cmd.matcher_event`、循环后 `maker.next=null`+best 更新。taker 可是新 cmd 或 move 的既有 order（用一个 taker 抽象/参数区分）。
- 完整 `new_order` GTC：撮合→`filled==size` 不挂→dup id reject 剩余→否则 insert_order。
- 事件字段严格按 §6（bidderHoldPrice=BID 侧 reserve、matched_order_uid=maker.uid、price=maker 价、size=本笔、filled/notional=taker 累计、active/maker completed）。

- [ ] Step1 失败测试：两单撮合成一笔 trade（对照 Naive 的期望）；多桶穿越（taker 扫两个价位）事件链顺序+active_order_completed 时序；GTC 部分成交后剩余挂簿、L2 反映；dup id reject。
- [ ] Step2-4 TDD（对照参考 §2.1 逐段 + Java）。Step5 commit `feat(orderbook): Direct tryMatchInstantly 撮合主循环 + GTC 撮合`。

---

### Task 4：IOC / FOK_BUDGET / IOC_BUDGET
**Files:** Modify `order_book_direct_impl.rs`。**参考:** §3, Ruling P2-1。Java: `newOrderMatchIoc(:191)`, `newOrderMatchFokBudget(:204)`, `checkBudgetToFill(:222)`, `tryMatchInstantlyWithBudget(:381)`。

**Interfaces:**
- IOC：撮合后剩余 reject、不挂。
- FOK_BUDGET：`check_budget_to_fill`（桶级累加，MAX 哨兵）+ `is_budget_limit_satisfied`（BID `calc<=limit` / ASK `calc>=limit`）；满足→撮合、不 reject；不满足→整单 reject。**Ruling P2-1：撮合镜像 Naive、不加价上限**。
- IOC_BUDGET：仅 BID（ASK 整单 reject）；`try_match_instantly_with_budget`（每次 `affordable=remainingBudget/tradePrice`、`tradeSize=min(remaining, maker剩余, affordable)`、`==0` break、`remainingBudget-=tradeSize*price`）；剩余 reject、允许部分成交、不挂。`remainingBudget` 初值 `cmd.price`。

- [ ] Step1 失败测试：IOC 余量丢弃；FOK_BUDGET 足量成交 / 不足整拒；IOC_BUDGET 部分成交按预算截断 + ASK 拒；**Ruling P2-1 专测**（BID FOK_BUDGET 小总预算 vs 高价 ask，确认与 Naive 一致）。
- [ ] Step2-4 TDD。Step5 commit `feat(orderbook): Direct IOC/FOK_BUDGET/IOC_BUDGET`。

---

### Task 5：cancel / reduce / move + removeOrder
**Files:** Modify `order_book_direct_impl.rs`。**参考:** §4。Java: `cancelOrder(:491)`, `reduceOrder(:515)`, `moveOrder(:556)`, `removeOrder(:599)`。

**Interfaces:**
- `remove_order(&mut self, order_idx) -> Option<usize>`（§4.4：桶 volume/num_orders 减、tail 修复或删桶、通用摘链、best 修复；返回摘掉的 bucket idx 供复用/释放）。
- cancel（uid 校验、index 删、remove_order、REDUCE 事件 completed=true）。
- reduce（req>0 校验、canRemove 全删 or 部分 `size-=reduceBy`+`volume-=reduceBy` 不动链、REDUCE 事件 size=reduceBy）。
- move（uid 校验、现货 reserveBidPrice 上限→`MATCHING_MOVE_FAILED_PRICE_OVER_RISK_LIMIT`、remove→新价 try_match_instantly(taker)→全成丢弃 / 部分 insert_order 复用 freeBucket）。**注意：这里要实现 P1/P3 里 Naive 侧延后的 move reserveBidPrice 守卫**（Direct 有，且现在 spec 可得）——但仅 Direct 内；Naive 的补留其 carry-forward。

- [ ] Step1 失败测试：cancel 未知 id→错误码、cancel 释放挂单、reduce 部分/全撤、move 改价重撮合/重挂、move 越 reserve 上限拒；每步后 `validate_internal_state` 通过。
- [ ] Step2-4 TDD。Step5 commit `feat(orderbook): Direct cancel/reduce/move + removeOrder`。

---

### Task 6：state_hash + 完整 validate_internal_state
**Files:** Modify `order_book_direct_impl.rs`。**参考:** §7 全量。

**Interfaces:**
- `state_hash(&self)->i32`：确定性 fold（ask 链升序/bid 链降序遍历 order 的 id/price/remaining）。**须与 `OrderBookNaiveImpl::state_hash` 对同一逻辑簿产出相同值**（若两者 hash 公式不同，则改为：Direct 与 Naive 用同一个"规范遍历→hash"函数，以便差分对拍能比 hash；在报告说明）。
- 完整 `validate_internal_state`：§7 全部 10 条不变式。

- [ ] Step1 失败测试：state_hash 确定+敏感；相同逻辑簿 Naive.state_hash == Direct.state_hash（用少量手工构造簿）；validate 覆盖 §7 各条（含跨桶单调、ART↔链 1:1、无孤儿）。
- [ ] Step2-4 TDD。Step5 commit `feat(orderbook): Direct state_hash + 完整 validate_internal_state`。

---

### Task 7：Naive↔Direct 差分对拍 proptest + OrderBookBaseTest 场景（验收）
**Files:** Create `core/orderbook/direct_naive_diff_tests.rs`（或 `#[cfg(test)]` 于 direct 文件）；Modify mod 声明。

**Interfaces:** 无生产接口；测试。

- [ ] Step1：**差分 proptest**——proptest 生成随机命令流（place GTC/IOC/FOK_BUDGET/IOC_BUDGET bid/ask 随机 price/size/reserve；cancel/reduce/move 取此前挂上的 id）。同一命令流分别喂 `OrderBookNaiveImpl` 与 `OrderBookDirectImpl`，**每条命令后逐位断言相同**：`cmd.result_code`、`cmd.matcher_event` 事件链（逐字段：type/maker_order_id/matched_order_uid/price/size/bidder_hold_price/active_order_completed/maker_order_completed/next）、`fill_l2(深)`、`state_hash`；并各自 `validate_internal_state`。失败 shrink 到最小。
- [ ] Step2：把 P1 的 `OrderBookBaseTest` 核心场景（现有 Naive 单测）参数化/复制一份对 **Direct** 跑（或在差分 proptest 覆盖足够时，至少补几条定向 Direct 单测：多桶撮合、cancel/move、L2）。
- [ ] Step3：跑通并调稳（proptest cases ~256）。若差分暴露任何 Direct≠Naive → **停下报告**（是 Direct 移植 bug，不弱化断言）。
- [ ] Step4 全绿。Step5 commit `test(orderbook): Naive↔Direct 差分对拍 proptest + Direct 场景`。

---

## P2 完成定义
- `cargo test --lib` 全绿（211 + Direct 单测 + 差分 proptest），`-D warnings` 干净。
- Direct 对随机命令流与 Naive **逐位一致**（差分 proptest 256 cases 绿）。
- `validate_internal_state` 覆盖 §7；无 HashMap；i64 定点。
- move reserveBidPrice 守卫在 Direct 落地（Naive 侧补仍在 carry-forward）。

## 自查（对照参考）
§1 结构→T1；§2.3 insert/§5 L2→T2；§2.1 撮合→T3；§3 IOC/FOK/预算→T4；§4 cancel/reduce/move→T5；§7 不变式→T6；§8 差分+Ruling P2-1→T4/T7。全覆盖。类型 `OrderIdx/BucketIdx` 跨 T1-7 一致；事件字段规则复用 §6 = 现有 MatcherTradeEvent。
