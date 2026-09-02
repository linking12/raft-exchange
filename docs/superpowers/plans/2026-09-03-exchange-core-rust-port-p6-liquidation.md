# P6 期货清算 / IF / ADL / Funding / 内部转账 / Loan扫描器 / 调度器 移植 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Java exchange-core 的自动化/调度/跨账户机制逐字节忠实移植进 `exchange-core-rs`：期货强平 FORCE→IF→ADL 状态机、保险基金(IF)、自动减仓(ADL)、资金费(Funding)、内部转账(INTERNAL_TRANSFER)、loan 清算扫描器(LoanLiquidationEngine)、调度器(LIQUIDATION_SCAN)、ForceLiquidation 路由修复、matched_order_command_type。

**Architecture:** 三种命令流形（参考 §0）：①同线程事件驱动状态机（LiquidationEngine，在 pre_process_command/handler_risk_release 内同步触发）；②TwoStepCommandProcessor R1→merge→R2（IF/ADL/Funding/InternalTransfer，与 P5 LoanRatePricingProcessor 同形）；③off-lane leader-local 调度器（LIQUIDATION_SCAN 每 tick 提交，各副本经命令确定性重放）。

**Tech Stack:** Rust（无 disruptor-rs，单线程确定性）；i64 钱、i128 中间量（`*_exact` 溢出 panic；但 ADL riskScore 用 saturating 语义对齐 Java saturatingMultiply）；仅 `BTreeMap`/`BTreeSet`（禁 HashMap）；无浮点/Rc/RefCell/unsafe。

**Spec:** `docs/superpowers/specs/2026-09-03-p6-liquidation-adl-funding-reference.md`（权威参考，含 Java 行锚点；下称"参考"）。P5 loan 参考 `docs/superpowers/specs/2026-09-02-p5-loan-reference.md`。P4 期货参考 `docs/superpowers/specs/2026-09-01-p4-futures-risk-reference.md`。总设计 `docs/superpowers/specs/2026-08-31-exchange-core-rust-port-design.md`。

**Java 源根：** `/Users/ming/project/java/binance/raft-exchange/exchange-core/src/main/java/exchange/core2/core/`（下称 `JAVA/`）。

## Global Constraints
- **忠实移植，不发明**：行为、命名（类型逐字 Java：`LiquidationEngine`/`LiquidationService`/`ADLCommandProcessor`/`IFNotional`…；文件 snake_case；包路径镜像 Java `processors/liquidation/*`）全部对齐。参考与 Java 冲突以 Java 代码为准。
- **确定性 substrate**：i64 钱、i128 中间量；容器仅有序（`BTreeMap`/`BTreeSet`，禁 HashMap）；无浮点。所有跨 user/currency/candidate 迭代与 tie-break 必须确定（参考 §11 逐条：ADL 分数排序稳定、Funding 余数分配用有序容器、Cross pick tie-break 三级、scan slice 走命令字段）。ADL `riskScore` 用 saturating 乘法（溢出 clamp 不 wrap，否则符号翻转倒排——参考 §3.1，load-bearing）。
- **守恒（P6 扩展 P4/P5 恒等式）**：完整式再加 `+ Σ_symbols IFNotional.available + Σ_(symbol,direction) IFPositionRecord 的 estimate_pnl(mark)`。liquidation fee 从 taker accounts 移入 IFNotional.available（在扩展式内守恒，P4/P5 窄式外）。IFNotional **自限**永不为负；loan LIF 与 futures IF 是完全独立池，绝不互补贴（参考 §10）。
- **构造点稳定性**：给 `OrderCommand`/`SymbolPositionRecord`/`MatcherTradeEvent` 加字段一律带默认值（Default 派生），保 P1–P5 全部构造点编译 + 现有 677 测试不回归（同 P4-B/P5-A 做法）。非复制字段（liquidationFlow/pendingADLSize/adlEligibility/IF 索引/scanner 索引）**排除**出 state_hash（参考 §1.5/§3.4/§6.7/§11.5）。
- **测试门禁**：每任务 `cargo test` 全绿（当前基线 677）；`cargo build` 无 error。

## 关键 Rulings（架构决策，随计划确定；参考 §12）
- **Ruling P6-A（§12.3，事件载体）**：IF/ADL/Funding/InternalTransfer 的 R1→merge→R2 载体，采用 **lean per-processor carrier**（各自在 `OrderCommand` 上加专用 `Vec<...>`/字段），**不**扩展共享 `MatcherEventType` 枚举——延续 P5 `LoanRatePricingProcessor`（`loan_reprice_events`）已确立并被认可的先例（避免触碰撮合引擎对 MatcherEventType 的全量穷尽 match；这些"事件"非真实撮合，无需与订单簿机器互操作）。代价若错：未来若要真事件总线需重接载体——但全 port 已决定不建事件总线（Ruling P6-B）。
- **Ruling P6-B（§12.2，无事件总线）**：全 port 无 FundEvent/EventsHelper 总线（P1-P5 既定）；所有 send*Event/告警（margin-call/liquidation-alert/ADL/IF/funding/internal-transfer notify）**不移植**——账本状态是权威真相源，通知侧信道全 port 延后。不因"完整性"建事件总线（scope creep）。
- **Ruling P6-C（§12.1，单 shard 塌缩）**：TwoStep 的 `*ByShard[]` 数组塌缩为单载体（cross-shard sum 在单 shard 是 identity），但 R1/merge/R2 **形状**忠实实现、多 shard 语义写注释可后续扩展——同 P5 先例。
- **Ruling P6-D（§12.4，命令码不需 byte-parity）**：Rust 端 OrderCommandType 新码只需内部互不冲突；Java LIQUIDATION_SCAN 与 LOAN_IF_DEPOSIT 同为 byte 64 是 Java 源自身情况，Rust 独立标准端无 server 无线协议兼容需求，各变体给互异码即可。
- **Ruling P6-E（scanner 非复制状态）**：LiquidationEngine.symbolToUsers / LoanLiquidationEngine 的两索引 / LiquidationFlow / pendingADLSize / adlEligibility 全部非复制、不进 state_hash；索引/计数由确定性重放或 updateProvider 重建维持一致（参考 §1.5/§1.7/§6.7/§11.5 的两类"非复制但安全"论证须在注释里区分保留）。
- **Ruling P6-F（调度器 harness 可适配）**：`runOneIteration` 的 tick 计数域逻辑（`scanTick % scanSliceCount`、`% repriceEveryNTicks`）忠实移植；外围 `ScheduledExecutorService`/`ThreadFactory` 是 JVM plumbing，纯库 Rust crate 里可简化为可手动 tick 的结构 + `is_running` leader 门（server 集成非本 crate 范围）。

## 范围界定
- **P6 做**：上述 7 大项全部核心逻辑 + 清算数学原语 + 守恒扩展 + e2e + proptest。
- **P6 不做（→ 后续/P7）**：真事件总线（Ruling P6-B）；server 端 raft/leader 集成与真实定时线程（Ruling P6-F，只留 is_running 门 + 可手动 tick）；IF 自有库存(IFPositionRecord)的最终 unwind 机制（Java 亦未在 exchange-core 内闭环，只跟踪）；snapshot 序列化（P7）。

## File Structure
新增（镜像 Java 包树）：
- `src/core/utils/core_arithmetic_utils.rs`（**扩展**现有文件）— `calculate_liquidation_fee`/`calculate_size_to_liquidate`/`calculate_deficit_after_liquidate`（Java CoreArithmeticUtils:180-240）
- `src/core/common/symbol_position_record.rs`（**扩展**）— `calculate_bankruptcy_price`（Java :295-312）+ 非复制字段 `liquidation_flow`/`pending_adl_size`/`adl_eligibility`
- `src/core/processors/liquidation/mod.rs`
- `src/core/processors/liquidation/liquidation_flow.rs` — `LiquidationFlow{price,size,order_id,state}` + `LiquidationFlowState` 枚举
- `src/core/processors/liquidation/liquidation_service.rs` — `LiquidationService`（IFNotional/IFPositionRecord 复制状态 + orderId 编码 + ADL 候选构造 + riskScore）
- `src/core/processors/liquidation/liquidation_engine.rs` — `LiquidationEngine`（checkPositions/checkIsolated/checkCross/advanceLiquidation/startLiquidationFlow/symbolToUsers 索引）
- `src/core/processors/if_command_processor.rs` — `IFCommandProcessor`（TwoStep）
- `src/core/processors/adl_command_processor.rs` — `ADLCommandProcessor`（TwoStep）
- `src/core/processors/funding_fee_command_processor.rs` — `FundingFeeCommandProcessor`（TwoStep）
- `src/core/processors/internal_transfer_processor.rs` — `InternalTransferProcessor`（TwoStep）
- `src/core/processors/loan/loan_liquidation_engine.rs` — `LoanLiquidationEngine`（扫描器 + 两索引）
- `src/core/processors/scheduler.rs` — `covered_by_scan_slice` 自由函数 + 可手动 tick 的调度结构
- `src/core/common/adl_user_position.rs` — `ADLUserPosition`
- `src/core/liquidation_e2e_tests.rs` — e2e + 守恒 proptest（末任务）

修改：
- `src/core/common/cmd/order_command_type.rs` — 加 InternalTransfer/IfTakeover/AutoDeleveraging/futures IfDeposit/IfWithdraw/SettleFundingfees/LiquidationScan/SystemLiquidationNotify（+ is_non_trading 分类）
- `src/core/common/cmd/order_command.rs` — 加 lean carriers（if_events/adl_events/funding_events/internal_transfer_event，均 Default）
- `src/core/common/matcher_trade_event.rs` — 加 `matched_order_command_type`
- `src/core/processors/matching_engine_router.rs` — ForceLiquidation 进 new-order 分支 + 填 matched_order_command_type
- `src/core/processors/risk_engine.rs` — pre_process_command/handler_risk_release 接各命令 R1/R2 + markprice→checkPositions 钩子 + normalize_cmd_position_size + collect_liquidation_fee + create_positions_key 用 matched_order_command_type + 持有 LiquidationService/LiquidationEngine
- `src/core/processors/loan/loan_command_dispatcher.rs` — 补 post_process_loan_cross_force_liquidate 里 syncCrossExposure 调用点（参考 §6.7）+ loan 变更同步索引
- `src/core/common/{command_result_code,matcher_event_type}.rs`、各 `mod.rs`

---

### Task 1：命令码 + 清算数学原语 + MatcherTradeEvent 字段
**Files:** Modify `order_command_type.rs`（新命令码 + is_non_trading）、`command_result_code.rs`（INTERNAL_TRANSFER_INVALID_SELF 等新码）、`core_arithmetic_utils.rs`（3 数学函数）、`symbol_position_record.rs`（calculate_bankruptcy_price + 3 非复制字段带默认，排除 state_hash）、`matcher_trade_event.rs`（matched_order_command_type 带默认）、`order_command.rs`（lean carriers 占位字段带默认）。**参考:** §1.2/§1.8/§12.4、`JAVA/utils/CoreArithmeticUtils.java:180-240`、`JAVA/common/SymbolPositionRecord.java:295-312`、`JAVA/common/cmd/OrderCommandType.java`。

**Interfaces:**
- Produces: OrderCommandType 新变体 + is_non_trading 分类（Ruling P6-D 内部互异码）；`CoreArithmeticUtils::calculate_liquidation_fee(size,price,spec)`/`calculate_size_to_liquidate(pos,spec,price_record)`/`calculate_deficit_after_liquidate(...)`（逐字对齐 Java 整数推导注释，不自行重推）；`SymbolPositionRecord::calculate_bankruptcy_price(spec, margin_base_fn)`；`MatcherTradeEvent.matched_order_command_type: OrderCommandType`（Default）；SymbolPositionRecord 加 `liquidation_flow: Option<...>`/`pending_adl_size: i64`/`adl_eligibility: i64`（默认 None/0/0，**排除 state_hash**）。

- [ ] Step1 失败测试：3 数学函数各对 Java 已知输入输出核对（含整数截断/符号）；calculate_bankruptcy_price isolated(NO_CROSS)；新命令码 is_non_trading 分类（InternalTransfer/futures IfDeposit/IfWithdraw 为真，IfTakeover/AutoDeleveraging/SettleFundingfees/ForceLiquidation/LiquidationScan 为假）；state_hash 不含 3 非复制字段（同一 position 改这 3 字段 hash 不变）。
- [ ] Step2-4 TDD。Step5 commit `feat(liquidation): 命令码 + 清算数学原语 + MatcherTradeEvent.matched_order_command_type`。

---

### Task 2：ForceLiquidation 路由修复 + matched_order_command_type 接线
**Files:** Modify `matching_engine_router.rs`（ForceLiquidation 进 PlaceOrder|ClosePosition|Loan* new-order 分支；撮合时把 maker 挂单的原命令类型写进 trade event 的 matched_order_command_type）、`risk_engine.rs`（maker 侧 create_positions_key 改用 `mte.matched_order_command_type` 替换 cmd.command 占位）。**参考:** §8、§9、`JAVA/processors/MatchingEngineRouter.java:206-214`、`JAVA/processors/OrderBookEventsHelper.java:75`、`JAVA/processors/RiskEngine.java:1450`。

**Interfaces:**
- Consumes: matched_order_command_type 字段（Task1）。
- Produces: ForceLiquidation 命令经 router 落 book（ASK/BID 由 R1 定）不再被 wildcard 吞成 MatchingUnsupportedCommand；trade event 填 maker 原命令类型；maker create_positions_key 用之（HEDGE 翻仓正确）。

- [ ] Step1 失败测试：ForceLiquidation 命令 R1 置 ValidForMatchingEngine 后经 router 真落 book 撮合（对照修复前落 MatchingUnsupportedCommand 覆盖 R1 的回归）；一笔 taker=ForceLiquidation 撮 maker=普通 PlaceOrder，maker 侧 trade event 的 matched_order_command_type==PlaceOrder（非 taker 的 ForceLiquidation）；ONEWAY 下行为不变。
- [ ] Step2-4 TDD。Step5 commit `fix(liquidation): ForceLiquidation 路由 + matched_order_command_type 接线`。

---

### Task 3：INTERNAL_TRANSFER（最简 TwoStep，验证管线形）
**Files:** Create `internal_transfer_processor.rs`、`order_command.rs`（internal_transfer_event carrier）。Modify `risk_engine.rs`（is_non_trading 路由 R1 collect_input/merge/R2 apply_event）。**参考:** §5、`JAVA/processors/InternalTransferProcessor.java`、`JAVA/ExchangeApi.java:1216-1226`。

**Interfaces:**
- Consumes: withdrawable_balance（P5 已移植，NSF 含 loan 抵押/期货保证金锁）、try_claim_tx（P5）、get_or_add_suspended（现有）。
- Produces: `InternalTransferProcessor::{collect_input, build_matcher_events, apply_event}`；字段映射 uid=from/size=to(载 uid)/symbol=currency/price=amount/order_id=txId；R1 校验序（self→amount<=0→user missing→NSF withdrawable→try_claim）后**即时**debit from.accounts；merge 1:1；R2 credit to.accounts（to 不存在→get_or_add_suspended）。守恒 from-=/to+=。

- [ ] Step1 失败测试：成功转账 from-=amount/to+=amount 守恒；各拒绝码（self/amount<=0/AUTH_INVALID_USER/RiskNsf 经 withdrawable 含 loan 抵押锁）；idempotency（同 txId 重复不双扣，try_claim claim-and-keep）；to 未见过 uid→自动建 SUSPENDED 且入账。
- [ ] Step2-4 TDD。Step5 commit `feat(liquidation): INTERNAL_TRANSFER TwoStep`。

---

### Task 4：资金费 FundingFeeCommandProcessor（零和 TwoStep + 余数分配原语）
**Files:** Create `funding_fee_command_processor.rs`、共享余数分配原语（`utils` 里 `distribute_remainder_by_one` 或就近）、`order_command.rs`（funding carrier）。Modify `risk_engine.rs`（SettleFundingfees R1/merge/R2 + R2 后 checkPositions 钩子——参考 §1.1 line 977）。**参考:** §4、§11.2、`JAVA/processors/FundingFeeCommandProcessor.java`、`JAVA/ExchangeApi.java:1083-1093`。

**Interfaces:**
- Produces: `FundingFeeCommandProcessor::{collect_input,build_matcher_events,apply_event}`；action=BID(long付)/ASK(short付)、price=rate、size=rateScaleK；R1 gate（size<=0→RiskInvalidAmount、无 markPrice→RiskMarkpriceNotAvailable）；两级 pro-rata（payer 池→per-shard receiver→per-user）均用 truncMulDiv + 1-unit 余数分配（有序容器，Ruling determinism §11.2）；settle 按 symbol/-symbol 找 HEDGE 双向仓，有活仓则入 position.profit 否则 scale 入 accounts。零和无桶。

- [ ] Step1 失败测试：一 payer 一 receiver 全额转移零和；多 receiver 按 notional pro-rata + 余数确定分配（构造截断 dust 验分配到确定的 uid）；payer/receiver 任一为空则无事件；仓在 R1/R2 间平掉→费入 accounts 不入 ghost 仓；gate 拒绝码；跑完 Σaccounts+Σposition.profit 守恒。
- [ ] Step2-4 TDD。Step5 commit `feat(liquidation): 资金费 TwoStep（零和 pro-rata + 余数分配原语）`。

---

### Task 5：保险基金 IF（LiquidationService 状态 + IFCommandProcessor）
**Files:** Create `liquidation/mod.rs`、`liquidation/liquidation_service.rs`（IFNotional/IFPositionRecord 复制状态 + reserve/release/accept/creditFee/deposit/withdraw + orderId 编码）、`if_command_processor.rs`、`order_command.rs`（if carrier）。Modify `risk_engine.rs`（持有 LiquidationService；IF_TAKEOVER R1/merge/R2 + IF_DEPOSIT/IF_WITHDRAW 经 RiskEngineCommandDispatcher 路径，与 adjustments 对冲同 P5 LOAN_IF_*）、`command_result_code.rs`。**参考:** §2、`JAVA/processors/IFCommandProcessor.java`、`JAVA/processors/liquidation/LiquidationService.java`。

**Interfaces:**
- Produces: `LiquidationService{ notionals: BTreeMap<i32,IFNotional{available,reserved}>, positions: BTreeMap<i64,IFPositionRecord> }`（复制、进 state_hash）+ `reserve_if_notional`(min(available-reserved, size*price))/`release_reserved_if_notional`/`accept_if_position`/`credit_liquidation_fee`/`deposit`/`withdraw`；`IFCommandProcessor` TwoStep（R1 reserve→per-shard preview；merge Σreserve/price floor-per-shard，totalCover<remaining→REJECT 全拒；R2 只匹配 shard accept_if_position；finalize close taker + 总是 release preview）。IF 自限永不为负；undersize→REJECT 降级 ADL（Task6 消费）。
- futures IF_DEPOSIT/IF_WITHDRAW（**区别于** loan LOAN_IF_*）与 adjustments 对冲，WITHDRAW 有下限守卫。

- [ ] Step1 失败测试：credit_liquidation_fee 入 available；reserve min 上限 + 永不为负；IF_TAKEOVER 足额 cover→accept 仓+关 taker 仓守恒；undersize→REJECT（全拒非部分）；finalize 总释放 preview（含全拒路径）；IF_DEPOSIT/WITHDRAW 对冲 adjustments 守恒 + WITHDRAW 超额拒；state_hash 含 notionals/positions。
- [ ] Step2-4 TDD。Step5 commit `feat(liquidation): 保险基金 IF（LiquidationService + IFCommandProcessor）`。

---

### Task 6：自动减仓 ADL（ADLCommandProcessor）
**Files:** Create `adl_command_processor.rs`、`common/adl_user_position.rs`、`order_command.rs`（adl carrier）。Modify `liquidation_service.rs`（compute_profitable_positions_by_symbol + riskScore + unrealizedPnl）、`risk_engine.rs`（AUTO_DELEVERAGING R1/merge/R2/finalize）。**参考:** §3、§11.1、`JAVA/processors/ADLCommandProcessor.java`、`JAVA/processors/liquidation/LiquidationService.java:207-321`。

**Interfaces:**
- Produces: `ADLCommandProcessor::{collect_input,build_matcher_events,apply_event,finalize_for_command}`；R1 select（openVolume>pendingADLSize、反向、unrealizedPnl>0）按 riskScore DESC，`pending_adl_size += canTake`（R1 预留）；`compute_profitable_positions_by_symbol` **每次重算**（不缓存，确定性）——ISOLATED 直接 eligible(adlEligibility=100)，CROSS 账户级门(totalProfit>0 && equity>=1.2×MM)后写 clamp 因子；`riskScore = saturating_mul(saturating_mul(actualLeverage, uPnl), adlEligibility)`（saturating！）；merge cross-shard best-of-N（clone cursor 数组，全局最优分数逐个消费，重写 cmd.size 为实际消费量）；R2 per-event 关对手仓（may vanish→best-effort skip）；finalize 关 taker 仓 + 走**原 R1 head** 释放 pending_adl_size（对称，不管实际消费多少）。共享 close-and-cleanup helper（refund extraMargin→remove position→settle 残 profit）。

- [ ] Step1 失败测试：riskScore 排序（含 saturating 溢出不翻符号）；ISOLATED eligible / CROSS 门控 + 因子；R1 预留 R2 finalize 对称释放（提议未被选中也释放，走原 head 非 cursor clone）；对手仓 R1/R2 间消失→skip 不 error；merge 重写 cmd.size 为实际消费；关仓 refund/remove/settle 守恒。
- [ ] Step2-4 TDD。Step5 commit `feat(liquidation): 自动减仓 ADL（ADLCommandProcessor）`。

---

### Task 7：LiquidationEngine FORCE→IF→ADL 状态机 + 检测 + 费用 + markprice 钩子
**Files:** Create `liquidation/liquidation_flow.rs`、`liquidation/liquidation_engine.rs`。Modify `risk_engine.rs`（持有 LiquidationEngine；markprice_adjustment 接 checkPositions 钩子；FORCE/IF/ADL 的 normalize_cmd_position_size；handler_risk_release 接 advance_liquidation + collect_liquidation_fee；onPositionOpened/Closed 维护 symbolToUsers）、`symbol_position_record.rs`（liquidation_flow 用 Task1 字段）。**参考:** §1、§11.5、`JAVA/processors/liquidation/LiquidationEngine.java`、`JAVA/processors/RiskEngine.java:724-740,992,997,1522-1550`。

**Interfaces:**
- Consumes: 数学原语(Task1)、IF processor(Task5)、ADL processor(Task6)、LiquidationService(Task5)、covered_by_scan_slice(Task9 前可留 targeted-only)。
- Produces: `LiquidationEngine{ symbol_to_users: BTreeMap<i32,BTreeSet<i64>> (非复制), liquidation_service, loan_liquidation_engine(Task8), is_running }` + `check_positions(cmd)`（leader 门；symbol>=0 targeted / symbol<0 scan-slice）+ `check_isolated`/`check_cross`/`force_cross_liquidation`（风险分升序）+ `start_liquidation_flow`（liquidation_flow 幂等门 + submit FORCE）+ `advance_liquidation`（状态机 FORCE→onForce, IF→onIf, ADL→terminal；REJECT 降级 + 提交下一级）+ `on_position_opened/closed`（symbolToUsers，HEDGE 多仓安全）；`normalize_cmd_position_size`（clamp cmd.size≤openVolume，用 create_positions_key 的 command 视角）；`collect_liquidation_fee`（FORCE 后 Σtrade→fee→debit accounts/credit IFNotional）。
- **LiquidationFlow 非复制、ephemeral、失败转移后经重检测重启周期**（不持久化，Ruling P6-E）。submit 经注入的 command submitter（leader-only；纯库里可为收集到队列供测试驱动）。

- [ ] Step1 失败测试：isolated 检测（equity<MM 触发 FORCE、<1.2MM 仅警告不移植）；FORCE 全成交→flow 关；FORCE REJECT→WAIT_IF + 提交 IF；IF REJECT→WAIT_ADL + 提交 ADL；ADL→terminal(flow=null)；normalize clamp（仓缩小后 cmd.size 被夹）；collect_liquidation_fee 守恒（taker accounts→IFNotional）；markprice 更新触发 checkPositions targeted；symbolToUsers 开/平维护 + HEDGE 多仓不误删；out-of-order/duplicate 命令 flow.state 不符→skip。
- [ ] Step2-4 TDD（逐子场景）。Step5 commit `feat(liquidation): 期货 FORCE→IF→ADL 状态机 + 检测 + 清算费 + markprice 钩子`。

---

### Task 8：Loan 清算扫描器 LoanLiquidationEngine + 索引维护
**Files:** Create `loan/loan_liquidation_engine.rs`。Modify `loan_command_dispatcher.rs`（每 loan 变更同步 onIsolatedLoanOpened/Closed/syncCrossExposure；补 post_process_loan_cross_force_liquidate 的 syncCrossExposure 调用点——参考 §6.7 seam）、`liquidation_engine.rs`（check_positions 尾部 delegate checkLoans；updateProvider 重建索引）。**参考:** §6、§11.3、`JAVA/processors/loan/LoanLiquidationEngine.java`（全文）。

**Interfaces:**
- Consumes: P5 已移植的 collateral_value_in_quote_currency/calculate_display_interest/calculate_cross_account_ltv_bps(weighted)/calculate_cross_raw_ltv_bps/find_spot_symbol/collateral_weight_for_base、loan force-liquidate 命令 handlers（P5）。
- Produces: `LoanLiquidationEngine{ isolated_loan_symbol_to_users: BTreeMap<i32,BTreeSet<i64>>, cross_loan_currency_to_users: BTreeMap<i32,BTreeSet<i64>> (均非复制) }` + `check_loans(cmd)`（targeted 三索引并集 dedup / scan-slice）+ `check_user`→`check_isolated`（realDebt 含 pending、termExpired 仅 LOCKED、bankruptcyPrice=ceilMulDiv、sub-lot dust skip、submit ApiLoanForceLiquidate ASK/IOC）+ `check_cross`（weighted LTV 触发、每 tick 一对、pick 内含 ready-market 过滤防无限 stall、raw LTV pricing fallback weighted、calculate_cross_sell_size 按 bankruptcy price sizing）+ `pick_cross_collateral_to_sell`(weight DESC→amount DESC→currency ASC)/`pick_cross_loan_to_repay`(rate DESC→principal DESC→loanId ASC)/`has_ready_spot_market` + `on_isolated_loan_opened/closed`(多 loan 安全)/`sync_cross_exposure`(非对称容忍：部分退出留 stale 自愈、全退出精确扫除)/`update_provider`(重建)。

- [ ] Step1 失败测试：checkIsolated 触发（LTV≥liq 或 termExpired）+ 提交正确 limitPrice/lots + sub-lot dust skip + collateralValue<=0 skip（防除零）；checkCross 触发 + pick tie-break 三级（构造同分验第三级定序）+ ready-market 过滤（无市场的最优对不被反复选）；pricing 用 raw 优先 fallback weighted；index 开/平/syncCrossExposure 非对称维护 + updateProvider 重建；targeted 三索引并集。
- [ ] Step2-4 TDD。Step5 commit `feat(liquidation): loan 清算扫描器 LoanLiquidationEngine + 索引`。

---

### Task 9：调度器（covered_by_scan_slice + LIQUIDATION_SCAN + tick）
**Files:** Create `scheduler.rs`。Modify `risk_engine.rs`（LIQUIDATION_SCAN 命令进 pre_process_command 主 switch→checkPositions(symbol<0 scan)；shard-0/leader 门）、`liquidation_engine.rs`/`loan_liquidation_engine.rs`（scan 分支用 covered_by_scan_slice）。**参考:** §7、§11.4、`JAVA/processors/liquidation/LiquidationScheduledService.java`。

**Interfaces:**
- Produces: `covered_by_scan_slice(cmd, uid) -> bool`（自由函数，futures+loan 扫描器共用：非 SCAN 或 size<=0→true；否则 floorMod(uid,cmd.size)==cmd.uid）；`LiquidationScheduler{ scan_tick, scan_slice_count, reprice_every_n_ticks, is_running }` + `run_one_iteration()`（shard-0 门；提交 ApiLiquidationScan{symbol=-1,uid=slice,size=sliceCount}；每 N tick 提交 ApiRepriceLoanRates；tick++）——提交经注入 submitter（纯库测试可驱动）；LIQUIDATION_SCAN 应用时 checkPositions(symbol<0) 全扫过 slice 过滤（futures + 尾部 loan checkLoans 同 slice）。

- [ ] Step1 失败测试：covered_by_scan_slice（非 scan→true；scan 按 floorMod slice 过滤特定 uid）；run_one_iteration shard-0 门 + tick 计数（slice=tick%count、每 N tick reprice）；一个 LIQUIDATION_SCAN 同时驱动 futures checkPositions + loan checkLoans 且同 slice；slice 号走命令字段（各副本一致，非本地时钟）。
- [ ] Step2-4 TDD。Step5 commit `feat(liquidation): 调度器 covered_by_scan_slice + LIQUIDATION_SCAN tick`。

---

### Task 10：守恒扩展 + 清算 e2e + proptest（收官 P6）
**Files:** Create `core/liquidation_e2e_tests.rs`。Modify 守恒 helper（纳入 IFNotional.available + IFPositionRecord PnL）。**参考:** §10、§11、`JAVA/common/api/reports/TotalCurrencyBalanceReportResult.java`。

- [ ] Step1 扩展全局守恒 helper：P4/P5 完整式再加 `+ Σ IFNotional.available + Σ IFPositionRecord.estimate_pnl(mark)`；零容差。
- [ ] Step2 e2e（每步断言全局守恒）：期货 FORCE 全成交（含 liquidation fee 入 IF）；FORCE→IF takeover；IF undersize→ADL 级联；funding 结算多空；internal transfer；loan 扫描器触发 force-liquidate（对接 P5 handler）；混合（期货仓 + loan + IF + funding）。
- [ ] Step3 守恒 proptest：随机命令流（现货+期货下单/平仓/调保证金/markprice/FORCE/IF_TAKEOVER/ADL/funding/internal-transfer/loan 全命令/LIQUIDATION_SCAN + 随机 mark>0），跑完断言全局守恒每币==0（含 IF 项）、无非预期负余额、IFNotional 永不为负、无 panic。**违规→最小化+停+报告**（除非确证 Java 忠实缺陷→parity 记录）。
- [ ] Step4 全绿。Step5 commit `test(liquidation): 期货清算/ADL/funding/转账 e2e + 守恒 proptest`。

---

## P6 完成定义
- `cargo test` 全绿；FORCE→IF→ADL 状态机、IF、ADL、funding、internal-transfer、loan 扫描器、调度器、ForceLiquidation 路由、matched_order_command_type 全实现；守恒 proptest 绿（含 IF 项）。
- 事件总线/真实定时线程/server raft 集成/IF 库存 unwind 明确不做（Rulings P6-B/F），以桩/门占位未静默产错。

## 自查（对照参考 + 计划一致性）
§1 状态机→T7；§2 IF→T5；§3 ADL→T6；§4 funding→T4；§5 internal-transfer→T3；§6 loan 扫描器→T8；§7 调度器→T9；§8 router→T2；§9 matched_order_command_type→T1(字段)+T2(接线)；§10 守恒→T10；§11 确定性→贯穿(saturating/有序容器/tie-break/scan-slice)；§12 决策→Rulings P6-A..F。命令码/数学原语(T1)先行；ForceLiquidation 路由(T2)在 IF/ADL 前（它们靠 FORCE 落 book）；INTERNAL_TRANSFER(T3) 最简先验管线；IF(T5)在 ADL(T6)前（ADL 是 IF undersize 的降级）；LiquidationEngine(T7)编排 IF/ADL；loan 扫描器(T8)+调度器(T9)后；守恒 proptest(T10) 收官。类型 `LiquidationService`/`LiquidationEngine`/`IFNotional` 跨任务签名一致。
