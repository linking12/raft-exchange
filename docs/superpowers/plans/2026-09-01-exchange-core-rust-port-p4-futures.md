# P4：期货 / 统一账户风控 · 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development。Steps 用 `- [ ]`。

**Goal:** 在 P3 现货引擎之上移植 Java exchange-core 的**期货 / 统一账户**风控（`FUTURES_CONTRACT_PERPETUAL`）：头寸、逐仓/全仓保证金、PnL、R1 保证金检查 + R2 结算、统一账户跨保证金。交付能开/增/减/平/翻仓 + PnL 结算 + 守恒的期货引擎。

**Architecture:** 扩 P3 的 `RiskEngine`/`UserProfile`/`CoreSymbolSpecification`；新增 `SymbolPositionRecord` + PositionDirection/PositionMode/MarginMode + mark-price 缓存。单线程确定性管线不变；金额 `i64`/中间 `i128`/有序容器/禁 HashMap。**不做**：清算 scan/ADL/IF（P6）、loan（P5）、funding 内部（P6）、交割 SETTLE_PNL（P6，perp 优先）、snapshot（P7）——非 perp 分支以 `unimplemented!` 桩占位。

**Tech Stack:** Rust 2021、BTreeMap、proptest。

**Spec:** `docs/superpowers/specs/2026-08-31-exchange-core-rust-port-design.md`
**期货参考（权威）:** `docs/superpowers/specs/2026-09-01-p4-futures-risk-reference.md`（下称"参考"，§1-8 + Java 行号）
**Java 源:** `exchange-core/.../processors/RiskEngine.java`、`common/SymbolPositionRecord.java`、`common/{PositionDirection,PositionMode,MarginMode}.java`

## Global Constraints
- 沿用 P1/P3：`core::common` 放领域模型、`core::processors` 放引擎；金额 `i64`/`i128`；影响输出迭代走确定序、**禁 HashMap**；文件头注释标 Java 类；一类一文件。
- **守恒硬门槛**（参考 §7）：每个 mutation 处 `{accounts, fees, position.openInitMarginSum, position.extraMargin, position.profit}` 任一减必配等额一增；locked/free 派生。e2e + proptest 强制。
- **范围**：仅 perp 期货 + 统一账户。清算/ADL/IF/loan/funding/交割/snapshot 以桩占位或 stub（如 `calculateLocked` 的 loanCollateralLocked 留返回 0 的 stub）。
- 保证金按 **mark 价**、成本基按 **trade 价**（参考 §1）；R1 NSF 只用**初始**保证金，MM 仅清算/报表（P6）。
- 提交中文 Conventional Commits，不擅自 push。

## 任务顺序
1. 期货领域模型 + 枚举 + spec/OrderCommand 扩展 + mark-price 缓存
2. SymbolPositionRecord 保证金/PnL/pending/open-close 原语
3. R1 期货：placeOrder 分支 + canPlaceMarginOrder NSF + isValidLeverage + reduce-only + CLOSE_POSITION
4. R2 期货：handleMatcherEventMargin（taker+maker、close/open/flip/teardown/PnL/extraMargin）+ 分派
5. 统一账户：calculateCrossAvailable + crossMarginBaseAllocation + calculateFreeFuturesMargin + calculateLocked 统一
6. MARGIN_ADJUSTMENT + mark-price 更新命令 + leverage/marginMode 一致性命令
7. engine 管线接期货命令 + ExchangeApi 期货门面（下单/平仓/调保证金/设 mark 价/建期货 symbol）
8. e2e 期货场景（开/增/减/平/翻仓 + PnL 结算 + cross/isolated）+ 守恒 proptest
9. P4 全分支终审配套（validate/不变量收尾）——并入执行，无独立任务时可省

每任务 TDD：失败测试→实现→`cargo test --lib` 全绿→`-D warnings`→commit。基线（P2 完成后）约 290+。

---

### Task 1：期货领域模型 + 枚举 + 扩展
**Files:** Create `core/common/{position_direction,position_mode,margin_mode,symbol_position_record}.rs`、`core/common/last_price_cache_record.rs`；Modify `core/common/{core_symbol_specification.rs, user_profile.rs, mod.rs}`、`core/common/cmd/{order_command.rs, command_result_code.rs}`。
**参考:** §1（字段）、§2、§8。Java: 对应 common 类 + CoreSymbolSpecification 期货字段。

**Interfaces — Produces:**
- 枚举 `PositionDirection { Long, Short, Empty }`（+ `multiplier()`/`is_opposite_to_action()`）、`PositionMode { OneWay, Hedge }`、`MarginMode { Isolated, Cross }`，码值对齐 Java。
- `struct SymbolPositionRecord { uid, symbol, currency, direction, open_volume, open_init_margin_sum, open_price_sum, profit, pending_sell_size, pending_buy_size, pending_sell_avg_price, pending_buy_avg_price, leverage, margin_mode, extra_margin: i64/... }`（+ `new/initialize`、`is_empty()`、`reset()`；方法体 Task 2）。
- `CoreSymbolSpecification` 加期货字段：`init_margin, init_margin_scale_k, maintenance_margin: BTreeMap<i64,i64>(tiers), maintenance_margin_scale_k, max_leverage: BTreeMap<i64,i64>` + `calculate_init_margin(notional,lev)`、`calculate_maintenance_margin(notional)`、`is_valid_leverage(notional,lev)`（参考 §8/Java `:118-174`）。`SymbolType` 加 `FuturesContractPerpetual`（+Delivery 占位）。
- `UserProfile` 加 `position_mode: PositionMode`、`positions: BTreeMap<i32, SymbolPositionRecord>`（键=±symbol，HEDGE 用负）+ `create_positions_key(symbol,action,command)`/`(record)`、`count_position_record`/`process_position_record`（参考 §2）。
- `OrderCommand` 加 `leverage:i32, margin_mode:MarginMode, position_mode:PositionMode`（或按需）+ reduce-only（`order_flags` bit 或 `is_reduce_only()`，对齐 Java）。
- `LastPriceCacheRecord`（mark price 缓存：`ask_price/bid_price/last_price` + `apply_trade_price`）；RiskEngine 持 `last_price_cache: BTreeMap<i32, LastPriceCacheRecord>`（Task 3 用）。
- 期货错误码：`UnsupportedSymbolType, RiskMarginTradingDisabled, RiskMarkpriceNotAvailable, RiskMarginModeMismatch, RiskLeverageMismatch, RiskInvalidLeverage`（码值对齐 Java）。

- [ ] Step1 失败测试：枚举码值；create_positions_key（ONEWAY/HEDGE + CLOSE 翻符号）；calculate_init_margin（默认 notional/lev + 比例档）；calculate_maintenance_margin（分档）；is_valid_leverage（分档 floor）。
- [ ] Step2-4 TDD。Step5 commit `feat(futures): 期货领域模型/枚举 + spec 保证金公式 + UserProfile 头寸 + mark-price 缓存`。

---

### Task 2：SymbolPositionRecord 保证金/PnL/pending/open-close 原语
**Files:** Modify `core/common/symbol_position_record.rs`。**参考:** §1。Java: `SymbolPositionRecord.java:160-711`。

**Interfaces:** `pending_hold(action,size,price)`/`pending_hold_budget`、`pending_release(action,size)->i64`；`calculate_required_margin_for_futures(spec[,lev])`、`calculate_required_margin_for_order(spec,action,notional)->i64(-1 哨兵)`、`estimate_notional_for_order`、`calculate_pending_fee_for_order[_budget]`；`estimate_unrealized_profit(price)`、`estimate_pnl(price)`、`calculate_maintenance_margin(spec,price)`；`open_position_margin(action,size,trade_price,spec,mark)`、`close_current_position_futures(action,size,trade_price)->i64(翻仓余量)`。全 i128 中间。

- [ ] Step1 失败测试：pending hold/release 加减 + 侧归零重置 avg；required-margin 最坏敞口（含 -1 哨兵纯减仓）；open+partial close（成本基推迟 PnL）+ full close/flip（实现 PnL）；estimate_pnl。
- [ ] Step2-4 TDD（逐方法对照 Java）。Step5 commit `feat(futures): SymbolPositionRecord 保证金/PnL/pending/开平原语`。

---

### Task 3：R1 期货 — placeOrder + canPlaceMarginOrder + CLOSE_POSITION
**Files:** Modify `core/processors/risk_engine.rs`。**参考:** §3。Java: `RiskEngine.java:432-503,533-623,823-865`。

**Interfaces:** `place_order` 加期货分支（isFuturesContract→canPlaceMarginOrder 路径；spot 走 P3；非 perp `unimplemented!`）：mark 价检查、marginMode/leverage 一致性、position 记录解析/分配（NSF 前不插）、reduce-only 夹、isValidLeverage、canPlaceMarginOrder NSF（参考 §3.3 五项：positionMargin/crossFreeMargin/pendingFee/openLoss/比较）、成功 pendingHold+提交仓。`close_position_risk_check`（§3 CLOSE_POSITION）。`pre_process_command` 路由加 CLOSE_POSITION。

- [ ] Step1 失败测试：足额开多头 → ValidForMatchingEngine + pending 记录；保证金不足 → RiskNsf；杠杆超档 → RiskInvalidLeverage；marginMode 冲突 → 对应码；ONEWAY reduce-only 夹；CLOSE_POSITION 缺仓 no-op。固定/比例费 + isolated 各一组。
- [ ] Step2-4 TDD（对照参考 §3 逐项）。Step5 commit `feat(risk): R1 期货 placeOrder + canPlaceMarginOrder + CLOSE_POSITION`。

---

### Task 4：R2 期货 — handleMatcherEventMargin
**Files:** Modify `core/processors/risk_engine.rs`。**参考:** §4。Java: `RiskEngine.java:1358-1511` + 分派 `:885-1023`。

**Interfaces:** `handle_matcher_event_margin(cmd,mte,spec,taker_action,taker_up,taker_spr,cs,taker_other_locked)`：taker+maker 两块，每 TRADE：preVolume 快照 → pendingRelease(+unlockPending 事件量) → close_current_position_futures → closedSize>0 收 close-fee(扣 accounts/进 fees) → sizeToOpen>0 openPositionMargin+open-fee → isEmpty() 则 refundExtraMargin + removePositionRecord(profit→accounts) + PnL 结算事件。REJECT/REDUCE 仅 pendingRelease + isEmpty 拆记录。`handler_risk_release` 分派非现货→本函数（非 perp/清算命令桩）。`calculate_locked_margin`、`remove_position_record`、`refund_extra_margin` helper。

- [ ] Step1 失败测试：开多头成交 → 保证金锁定(虚拟)、fee 扣 accounts+进 fees、accounts 只动 fee；减仓部分 → 保证金按比例释放、PnL 未实现；全平 → PnL 入 accounts、仓拆除；翻仓 → 平+反向开。**每笔断言守恒**（参考 §7：accounts+fees 变动等于真实经济量）。maker+taker 双方。
- [ ] Step2-4 TDD。Step5 commit `feat(risk): R2 期货 handleMatcherEventMargin（开/平/翻/PnL 结算）`。

---

### Task 5：统一账户 — cross available / allocation / free margin / locked 统一
**Files:** Modify `core/common/user_profile.rs`、`core/processors/risk_engine.rs`。**参考:** §5、§4(calculateLocked)。Java: `UserProfile.java:229-312`、`RiskEngine.java:759-805,1040-1072`。

**Interfaces:** `UserProfile::calculate_cross_available(currency, currency_spec, symbol_spec_lookup)`、`cross_margin_base_allocation(...)`（MM 占比分账户 marginBalance）；`RiskEngine::calculate_free_futures_margin`（两估取 min，垫现货 NSF/提现）、`calculate_locked(up,currency)`（统一 Σ期货保证金 + exchangeLocked + loanCollateralLocked-stub(返 0)）。把 P3 的现货 NSF/withdrawable 接上 `calculate_free_futures_margin`（Task 3 spot 分支已留位或此处接）。

- [ ] Step1 失败测试：cross available = accounts − exchangeLocked − Σisolated 保证金；CROSS 仓 UPnL 可垫同币另一 CROSS 单（canPlaceMarginOrder 复用）；ISOLATED 不外借；calculate_free_futures_margin 两估取 min；calculate_locked 统一现货+期货。
- [ ] Step2-4 TDD。Step5 commit `feat(risk): 统一账户 cross available/allocation/free-futures-margin + calculate_locked 统一`。

---

### Task 6：MARGIN_ADJUSTMENT + mark-price 更新 + leverage/marginMode 命令
**Files:** Modify `core/processors/risk_engine.rs`、注册/命令。**参考:** §1(extraMargin)、§8。Java: MARGIN_ADJUSTMENT/adjustMarkPrice 相关。

**Interfaces:** `margin_adjustment`（extraMargin 加/减，物理扣 accounts、守恒桶）；mark-price 更新命令（测试/资金费前置，更新 last_price_cache）；leverage/marginMode 变更（updateLeverage/marginMode 一致性）。（这些是期货 e2e 的前置。）

- [ ] Step1 失败测试：MARGIN_ADJUSTMENT 加保证金 accounts 减、extraMargin 增、守恒；平仓时 extraMargin 全退（Task4 已测可复用）；设 mark 价后 R1 可用；杠杆变更校验。
- [ ] Step2-4 TDD。Step5 commit `feat(risk): MARGIN_ADJUSTMENT + mark-price 更新 + leverage/marginMode 命令`。

---

### Task 7：engine 管线接期货 + ExchangeApi 期货门面
**Files:** Modify `core/exchange_core.rs`、`core/exchange_api.rs`、`core/processors/matching_engine_router.rs`（期货 symbol 建 book）。**参考:** 设计 §4。

**Interfaces:** `pre_process_command`/`handler_risk_release` 已在 Task3/4 接期货分支；ExchangeApi 加：`add_futures_symbol(spec)`、`place_futures_order(...)`、`close_position(...)`、`margin_adjustment(...)`、`set_mark_price(symbol,price)`、`request_positions/user_position` introspection。MatchingEngineRouter 对期货 symbol 同样建 OrderBook（撮合与现货无异，风控在 R1/R2）。

- [ ] Step1 失败测试：经 ExchangeApi 走完整一笔期货成交端到端——建期货 symbol/建用户/充值/设 mark 价 → 一方开多、一方开空撮合 → 断言双方头寸/保证金/accounts + fees + 守恒。
- [ ] Step2-4 TDD。Step5 commit `feat(engine): 期货命令管线 + ExchangeApi 期货门面`。

---

### Task 8：e2e 期货场景 + 守恒 proptest
**Files:** Create `core/futures_e2e_tests.rs`（或并入 e2e）。**参考:** §7。

- [ ] Step1 e2e 场景：开→增→减→平（PnL 结算）、翻仓、多用户 maker/taker、ISOLATED 与 CROSS、MARGIN_ADJUSTMENT+退还；每步断言全局守恒（每 currency `Σ_users accounts + adjustments + fees + Σ position.(openInitMarginSum 未离 accounts 故不计) == 0`；即 accounts+adjustments+fees 守恒，PnL/保证金为仓内虚拟不破坏 Σaccounts）。
- [ ] Step2 守恒 proptest：随机期货命令流（开/平/调保证金/随机 mark 价内），跑完断言 Σaccounts+adjustments+fees==0 每币、无负 accounts、无 panic。若发现真实守恒违规 → 停下报告（不弱化）。
- [ ] Step3 调稳。Step4 全绿。Step5 commit `test(futures): 期货 e2e 场景 + 守恒 proptest`。

---

## P4 完成定义
- `cargo test --lib` 全绿 + `-D warnings`；经 ExchangeApi 能开/增/减/平/翻期货仓、PnL 结算、cross/isolated、调保证金；守恒 proptest 绿。
- 非 perp/清算/loan/funding/交割/snapshot 以桩占位，未静默产错。

## 自查（对照参考）
§1 模型→T1/T2；§2 mode→T1；§3 R1→T3；§4 R2→T4；§5 统一账户→T5；§6 费/funding-hook→T4(费)/P6(funding)；§7 守恒→T4/T8；§8 spec 字段→T1。全覆盖。类型 `SymbolPositionRecord`/枚举跨 T1-8 一致；`calculate_locked` 统一现货(P3)+期货(P4)。
