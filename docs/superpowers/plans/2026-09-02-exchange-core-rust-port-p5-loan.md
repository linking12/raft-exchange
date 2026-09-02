# P5 现货借贷（Loan）移植 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 Java exchange-core 的现货借贷子系统（14 个 loan/pool 命令、Fixed/Floating 双利率模型、LTV/抵押物估值、虚拟锁 `calculateLocked`、强平 R2 结算钩子 + LIF 接管、reprice 管线、守恒）逐字节忠实移植进 `exchange-core-rs`。

**Architecture:** RiskEngine.pre_process_command 的三路门守（`is_loan()` → LoanCommandDispatcher；`is_non_trading()` → RiskEngineCommandDispatcher（REPRICE_LOAN_RATES/ADD_LOAN）；否则主交易 switch），沿用 Java。借贷是独立管线：R1 在 dispatcher 里做校验+状态变更并对 force-liquidate 预移抵押物、返回 VALID_FOR_MATCHING_ENGINE 走现货撮合，R2 在 handler_risk_release 现货结算后追加 postProcess 结算钩子。抵押物永不物理移出 `accounts`——用 `loanCollateralLocked` 在所有余额校验点做记账扣减（虚拟锁）；唯一物理扣减发生在 LIF 接管。

**Tech Stack:** Rust（无 disruptor-rs，单线程确定性）；i64 钱、i128 中间量（`*_exact` 溢出即 panic）；仅 `BTreeMap`/`BTreeSet`（状态/输出路径**禁** HashMap）；无浮点/Rc/RefCell/unsafe。

**Spec:** `docs/superpowers/specs/2026-09-02-p5-loan-reference.md`（权威参考，含 Java 文件+行锚点；下称"参考"）。总设计 `docs/superpowers/specs/2026-08-31-exchange-core-rust-port-design.md`。P4 期货参考 `docs/superpowers/specs/2026-09-01-p4-futures-risk-reference.md`。

**Java 源根：** `/Users/ming/project/java/binance/raft-exchange/exchange-core/src/main/java/exchange/core2/core/`（下称 `JAVA/`）。

## Global Constraints
- **忠实移植，不发明**：行为、命名（类型逐字 Java：`LoanService`/`IsolatedLoanRecord`/…；文件 snake_case：`loan_service.rs`）、包路径全部对齐 Java 树。有分歧先读 Java 定夺，参考与 Java 冲突以 Java 代码为准（参考已多处标注"trust code over doc"）。
- **确定性 substrate**：i64 钱、i128 中间量、`checked_*`/`*_exact` 语义（Java `Math.*Exact`→溢出 panic）；容器仅有序（`BTreeMap`/`BTreeSet`）；无浮点。所有跨 shard/跨 loan 的迭代顺序必须确定（loanId ASC、currency ASC 等，见参考 §5/§6.3）。
- **虚拟锁不变量**：loan 抵押物留在 `accounts`，靠 `loanCollateralLocked` 记账扣减；唯一物理扣减 = LIF 接管（参考 §3.4/§6.3）。
- **守恒**：每 currency 全局恒等式（参考 §6.2）零容差：
  `Σ_user(accounts − exchangeLocked − loanCollateral) + extraMargin + exchangeLocked + loanCollateral + (loanPoolAvailable + interestRevenue + loanInsuranceFund) + fees + adjustments == 0`。
  `loanPoolBorrowed` 是 tracker，**不进**守恒（对应钱已在借款人 accounts）。（本移植单 shard，`suspends`/`ifBalances` 若未建桶则为 0。）
- **幂等**：user 维度命令用 `processedTransactionIds.try_claim(cmd.order_id, cmd.timestamp)`（claim-and-keep，失败不释放，同 BALANCE_ADJUSTMENT）；`loanId` 是业务键不做幂等。pool/LIF 运维命令无幂等去重（参考 §2.11）。
- **构造点稳定性**：给 `UserProfile`/`OrderCommand`/`CoreSymbolSpecification`/`CoreCurrencySpecification` 加字段一律带默认值（Default 派生或显式），保 P1–P4 全部构造点编译 + 现有 ~463 测试不回归（同 P3 Task2 / P4 Ruling P4-B 做法）。
- **测试门禁**：每任务 `cargo test` 全绿（当前基线 463）；`cargo build` 无 error。（clippy 有 3 个 P4 之前遗留的 naive-orderbook warning，非本阶段引入，不阻断。）

## 范围界定（重要）
- **P5 做**：14 个 loan/pool 命令的处理器（含 force-liquidate 的 R1 预移 + R2 结算 + LIF 接管）、Fixed/Floating 利率模型、reprice TwoStep 管线、ADD_LOAN 运行时配置、虚拟锁接入现货(P3)+期货(P4) NSF、守恒 + proptest。
- **P5 不做（→ P6）**：自动清算**扫描器** `LoanLiquidationEngine`（决定何时触发、提交 force-liquidate 命令的那部分，与期货 liquidation/ADL 扫描同处 P6）。参考 §5.1/§5.2 明确此边界。force-liquidate **命令处理器**属 P5，可由测试/API 直接提交命令来覆盖（无需扫描器）。索引维护（`isolatedLoanSymbolToUsers` 等，参考 §5.1 末）属扫描器，一并 → P6。

## File Structure
新增（镜像 Java 包树）：
- `src/core/common/loan_record.rs` — `LoanRecord` trait（共享债务视图，参考 §1.3）
- `src/core/common/isolated_loan_record.rs` — `IsolatedLoanRecord`（参考 §1.1）
- `src/core/common/cross_loan_record.rs` — `CrossLoanRecord`（参考 §1.2）
- `src/core/common/symbol_loan_specification.rs` — `SymbolLoanSpecification`（参考 §1.5）
- `src/core/processors/loan/mod.rs`
- `src/core/processors/loan/loan_global_config.rs` — `LoanGlobalConfig`（参考 §1.6）
- `src/core/processors/loan/loan_service.rs` — `LoanService`（状态桶 + 纯函数：accrue/applyDebtPayment/LTV/valueInNumeraire/settleLiquidationProceeds/takeOverCrossLoan/scale 换算，参考 §1.6/§3/§4.3/§6.3）
- `src/core/processors/loan/rate/mod.rs`
- `src/core/processors/loan/rate/floating_rate_model.rs` — `FloatingRateModel`（参考 §4.1）
- `src/core/processors/loan/rate/fixed_rate_model.rs` — `FixedRateModel`（参考 §4.1）
- `src/core/processors/loan/loan_command_dispatcher.rs` — `LoanCommandDispatcher`（14 命令处理器 + R2 postProcess，参考 §2/§5.2）
- `src/core/processors/loan_rate_pricing_processor.rs` — `LoanRatePricingProcessor`（TwoStep reprice，参考 §4.2）
- `src/core/loan_e2e_tests.rs` — e2e 场景 + 守恒 proptest（Task 9）

修改：
- `src/core/common/cmd/order_command_type.rs` — 加 14 loan 变体 + REPRICE_LOAN_RATES；`is_loan()`/`is_non_trading()` 填真
- `src/core/common/user_profile.rs` — 加 `isolated_loans`/`cross_loan_collateral`/`cross_loans`（参考 §1.4）
- `src/core/common/core_currency_specification.rs` — 加 `collateral_weight_bps`（参考 §3.3）
- `src/core/common/core_symbol_specification.rs` — 加 `loan_config: SymbolLoanSpecification`
- `src/core/common/cmd/command_result_code.rs` — 加 loan 错误码（LOAN_*）
- `src/core/processors/risk_engine.rs` — pre_process_command 接 is_loan 门守；handler_risk_release 接 R2 postProcess + reprice R2；`loan_collateral_locked` 填真 + 接入 calculate_locked/现货 NSF（替换 P4 stub=0）；持有 `LoanService`
- `src/core/exchange_api.rs` — loan 命令门面 + ADD_LOAN 配置门面
- `src/core/common/mod.rs`、`src/core/processors/mod.rs`、`src/core/mod.rs` — 模块声明

---

### Task 1：Loan 数据模型 + 命令类型 + LoanService 骨架
**Files:** Create `common/loan_record.rs`、`common/isolated_loan_record.rs`、`common/cross_loan_record.rs`、`common/symbol_loan_specification.rs`、`processors/loan/mod.rs`、`processors/loan/loan_global_config.rs`、`processors/loan/loan_service.rs`（仅字段 + 构造 + `state_hash` + 桶存取，accrue/LTV 等留 Task2/4）。Modify `order_command_type.rs`（14 变体 + REPRICE_LOAN_RATES；`is_loan()`/`is_non_trading()`）、`command_result_code.rs`（LOAN_* 错误码）、`user_profile.rs`（3 字段带默认空）、`core_currency_specification.rs`（`collateral_weight_bps` 默认 0）、`core_symbol_specification.rs`（`loan_config` 默认空）、各 `mod.rs`。**参考:** §1、§2.11 命令清单、`JAVA/common/{IsolatedLoanRecord,CrossLoanRecord,LoanRecord,SymbolLoanSpecification}.java`、`JAVA/processors/loan/{LoanGlobalConfig,LoanService}.java`、`JAVA/common/cmd/OrderCommandType.java:57-161`。

**Interfaces:**
- Produces: `LoanRecord` trait（getters/setters 见参考 §1.3）；`IsolatedLoanRecord`/`CrossLoanRecord` 实现之，各含 §1.1/§1.2 字段 + `is_empty()`/`initialize(...)`/`state_hash()`；`SymbolLoanSpecification{ initial_ltv_bps, liquidation_ltv_bps, margin_call_ltv_bps, max_amount, max_term_days }` + `is_enabled()`；`LoanGlobalConfig`（7 int + default 值见 §1.6）；`LoanService{ loan_pool_available, loan_pool_borrowed, interest_revenue, loan_insurance_fund: BTreeMap<i32,i64>, global_config, floating_rate, fixed_rate }` + 桶存取。
- `OrderCommandType` 14 变体（参考 §0 清单）+ `RepriceLoanRates`；`is_loan()` 覆盖恰好 14 码，`is_non_trading()` 加 `RepriceLoanRates`（**不**含 loan 14 码）。
- `UserProfile`: `isolated_loans: BTreeMap<i64,IsolatedLoanRecord>`、`cross_loan_collateral: BTreeMap<i32,i64>`、`cross_loans: BTreeMap<i64,CrossLoanRecord>`（`uid` 反序列化时注入，本移植结构体内可直接持有）。

- [ ] Step1 失败测试：`is_loan()` 对 14 个 loan 变体全真、对 PlaceOrder/BalanceAdjustment 等全假；`is_non_trading()` 含 RepriceLoanRates 不含 LoanCreate；`IsolatedLoanRecord::is_empty()`（三字段全 0 才空）；`LoanGlobalConfig::default()` 七个默认值（crossLiquidationLtvBps=8500 等）；`LoanService` 桶 addToValue/get。
- [ ] Step2 建模型 + 命令类型（改现有 `is_loan_always_false_in_spot_subset` 测试为真实分类）。Step3 各 `mod.rs` 声明。Step4 `cargo test` 全绿（463 + 新，P1-P4 构造点靠默认值不破）。Step5 commit `feat(loan): 数据模型 + 命令类型 + LoanService 骨架`。

---

### Task 2：利率模型（FloatingRateModel + FixedRateModel）
**Files:** Create `processors/loan/rate/mod.rs`、`processors/loan/rate/floating_rate_model.rs`、`processors/loan/rate/fixed_rate_model.rs`。Modify `loan_service.rs`（加 `accrue_to`/`calculate_display_interest` 按 `is_fixed_rate()` 二分派发；持有两模型引用关系：fixed 引用 floating）。**参考:** §4.1、§4.3（利息去向留 Task4 用）、`JAVA/processors/loan/rate/{FloatingRateModel,FixedRateModel}.java` **全文读**、`JAVA/processors/loan/LoanService.java:123-166`。**数值最敏感区**——加法累加器 bps·ms、`truncMulDiv` 两步防溢、kinked 曲线、"truncated-but-chargeable" 游标冻结（F1）。

**Interfaces:**
- Consumes: `LoanRecord` trait（Task1）。
- Produces: `FloatingRateModel{ base_bps, kink_util_bps, slope1_bps, slope2_bps, current_rate_bps: BTreeMap<i32,i64>, acc_rate_bps_ms: BTreeMap<i32,i64>, last_reprice_ts }` + `curve_rate_bps(util)`/`utilization_bps(borrowed,avail)`/`live_acc_rate_bps_ms(ccy,now)`/`init_open_snapshot(loan,ts)`/`open_rate_bps(ccy)`/`pending_interest(loan,now)`/`accrue(loan,now)`/`advance_accumulator(ccy,now)`/`reprice_currency(ccy,util)`/`current_rate_bps_or_base(ccy)`/`set_last_reprice_ts(ts)`。
- `FixedRateModel{ locked_rate_adjust_bps }` + `open_rate_bps(ccy)`（= floating.current_rate_bps_or_base + adjust，floor 0）/`accrue(loan,now)`（simple interest，游标 last_accrue_ts）。

- [ ] Step1 失败测试（镜像 Java `LoanServiceTest` 语义）：(a) 定额利率 simple-interest 一年 = principal×rate（缩放核对）；(b) Floating kinked 曲线在 kink 上下取值；(c) 加法累加器：两笔不同时间开的 loan 用各自 accSnapshot 得正确 pending；(d) **truncated-but-chargeable**：本金极小/间隔极短使单次 accrue 截断为 0 时游标不前进，多次累积后能收到利息（不永久丢失）；(e) `advance_accumulator` 必须在 `reprice_currency` 之前——顺序颠倒会把旧区间按新率计价（构造反例断言差异）。
- [ ] Step2-3 实现两模型 + LoanService 派发。Step4 全绿。Step5 commit `feat(loan): Fixed/Floating 利率模型 + 加法累加器`。

---

### Task 3：虚拟锁 `loan_collateral_locked` 接入 calculate_locked（现货+期货 NSF）
**Files:** Modify `risk_engine.rs`（`loan_collateral_locked(up, currency)` 填真——替换 P4 stub=0：Σ isolated_loans[collateral_ccy==c].collateral_amount + cross_loan_collateral[c]；接进 `calculate_locked` 的 ③④ 项 + `place_exchange_order` 现货 NSF 的独立扣减项——补 P4 Task5 carry 的现货漏减；RiskEngine 持有 `LoanService` 字段）。**参考:** §3.4（含"4 个站点不调 calculate_locked 而单独减 loanCollateralLocked"的实现细节）、`JAVA/processors/RiskEngine.java:1029-1072`、§7 表。

**Interfaces:**
- Consumes: `UserProfile` loan 字段（Task1）。
- Produces: `RiskEngine.loan_collateral_locked(&UserProfile, i32) -> i64`（真实值）；`calculate_locked` 含 loan 项；`place_order`/futures NSF/withdrawable/margin-adjust 四站点按参考 §3.4 各自的减项接法（**逐字对齐 Java**：那四处不调 umbrella calculate_locked，而是把 loanCollateralLocked 作为独立扣减项——不要图省事统一调 calculate_locked）。

- [ ] Step1 失败测试：用户 accounts=1000、某 isolated loan collateral=300（同 currency）→ `loan_collateral_locked=300`；现货下单/提现的 free = accounts − exchangeLocked − loanCollateralLocked，超过 free 的现货单/提现被 NSF 拒；无任何 loan 的用户行为不变（P1-P4 回归全绿）；cross_loan_collateral 也计入。
- [ ] Step2-3 实现。Step4 全绿（尤其 P3 现货 211+ / P4 期货测试不回归）。Step5 commit `feat(loan): loanCollateralLocked 虚拟锁接入现货+期货 NSF`。

---

### Task 4：Isolated 生命周期命令（CREATE/REPAY/ADD/RELEASE_COLLATERAL）
**Files:** Create `processors/loan/loan_command_dispatcher.rs`（`LoanCommandDispatcher` + dispatch 表 + 4 个 Isolated 处理器 + 公共 preamble）。Modify `loan_service.rs`（`disburse_loan`、`apply_debt_payment`、`collateral_value_in_quote_currency`、`verify_pool_capacity`、scale 换算）、`risk_engine.rs`（pre_process_command `is_loan()` 门守 → dispatcher）。**参考:** §2.1-2.4、§3.1（Isolated LTV）、§4.3（利息去向）、`JAVA/processors/loan/LoanCommandDispatcher.java:130-378,1045-1069`、`JAVA/processors/loan/LoanService.java:138-166,412-420`。

**Interfaces:**
- Consumes: LoanService（Task1/2）、loan_collateral_locked（Task3）、利率模型（Task2）。
- Produces: `LoanCommandDispatcher::dispatch(cmd, up_service, ssp, loan_service) -> CommandResultCode`（按 cmd.command 路由）；4 处理器读写字段映射见参考 §2.1-2.4（cmd.symbol/size/price/reserveBidPrice/userCookie 语义逐一照搬）；`settle_repay` 共享核；LTV 门 `principal×10000 ≤ collateralValue×initialLtvBps`。

- [ ] Step1 失败测试：LOAN_CREATE 成功路径（校验顺序 cheap→expensive、disburse 后 accounts+=principal / loanPoolAvailable-=principal / loanPoolBorrowed+=principal、LOAN_BORROW 事件）+ 各拒绝码（LOAN_NOT_ENABLED/LOAN_ALREADY_EXISTS/LOAN_INVALID_AMOUNT/LOAN_LTV_TOO_HIGH/LOAN_COLLATERAL_INSUFFICIENT/LOAN_POOL_INSUFFICIENT）；LOAN_REPAY 利息优先 + 全额=0 语义 + 空壳回收；ADD/RELEASE_COLLATERAL（release 的 `realDebt` 含 pending、strict `<` 到清算线、newCollateral==0&&realDebt>0 拒）；每条命令后守恒（Task9 前先局部断言 accounts+pool+interestRevenue 守恒）。
- [ ] Step2-4 TDD。Step5 commit `feat(loan): Isolated 生命周期命令 CREATE/REPAY/ADD/RELEASE`。

---

### Task 5：Cross 生命周期命令（CROSS_ADD/WITHDRAW/BORROW/REPAY）+ Cross LTV
**Files:** Modify `loan_command_dispatcher.rs`（4 Cross 处理器）、`loan_service.rs`（`calculate_cross_account_ltv_bps` 加权 + `calculate_cross_raw_ltv_bps` 不加权，共享 `cross_ltv_bps(applyWeight)`；`value_in_numeraire`；`collateral_weight_for_base`；`is_numeraire_configured`）。**参考:** §2.6-2.9、§3.2（两分母！）、§3.3、`JAVA/processors/loan/LoanCommandDispatcher.java:532-705`、`JAVA/processors/loan/LoanService.java:168-269,395-410,467-470`。

**Interfaces:**
- Produces: Cross 4 处理器；`calculate_cross_account_ltv_bps(up, ssp, loan_service, fail_closed: bool)`（加权分母，trigger/borrow/withdraw 用）；`calculate_cross_raw_ltv_bps`（不加权，仅 pricing 用）；`value_in_numeraire(amount,ccy) -> i64`（-1 哨兵）；subtract-then-check 回滚模式（WITHDRAW/BORROW）。
- 关键：`fail_closed_on_missing_price`——scanner/display 传 false（缺价→LTV 0 保守跳过），BORROW/WITHDRAW 传 true（缺价→i64::MAX 拒，防超借/提空）。

- [ ] Step1 失败测试：CROSS_ADD_COLLATERAL（collateralWeightForBase>0 门、pool 到 crossLoanCollateral）；CROSS_BORROW（numeraire 未配 fail-close、always FLOATING、record 先入后 check LTV 超 initialLtv 回滚 remove+还池）；CROSS_WITHDRAW（subtract-then-check，newLtv≥crossLiquidation 回滚）；CROSS_REPAY（同 settleRepay，永不放抵押物）；加权 vs 不加权两 LTV 对同状态给不同值（构造断言）。
- [ ] Step2-4 TDD。Step5 commit `feat(loan): Cross 生命周期命令 + 加权/不加权双 LTV`。

---

### Task 6：Pool/LIF 运维命令 + ADD_LOAN 运行时配置
**Files:** Modify `loan_command_dispatcher.rs`（POOL_DEPOSIT/WITHDRAW、LOAN_IF_DEPOSIT/WITHDRAW）、`risk_engine.rs`（`is_non_trading()` 的 ADD_LOAN 经 BinaryDataCommand 应用；本移植无独立 binary 基建，按 Java `RiskEngineCommandDispatcher:563-646` 内联进 risk_engine 的 binary/config 应用点或新增 `apply_add_loan`）。Create（可选）`common/batch_add_loan_command.rs`（三段 DTO + 校验器）。**参考:** §2.11、§2.12、§6.4（RESET_FEE 扫 interestRevenue、**不**扫 LIF）、`JAVA/common/api/binary/BatchAddLoanCommand.java`、`JAVA/processors/RiskEngineCommandDispatcher.java:563-646`。

**Interfaces:**
- Produces: 4 运维命令（`cmd.uid` 携 shardId、本移植单 shard 恒自身；DEPOSIT/WITHDRAW 与 adjustments 对冲、WITHDRAW 有下限守卫；LIF 可负但 IF_WITHDRAW 不得更负）；`apply_add_loan(batch)`——三段独立可选独立校验（GlobalLoanConfig 部分更新 all-or-nothing、SymbolLoanConfig 的 UNSET=-1 派生 resolve + kill-switch 保留其余字段 + collateralWeightBps 写 base currency、RateCurveConfig 存在即全替换）。
- 若 RESET_FEE 已在 P3 存在：扩展其 sweep 纳入 interestRevenue（→adjustments），LIF 不扫。

- [ ] Step1 失败测试：POOL_DEPOSIT/WITHDRAW 与 adjustments 守恒对冲 + WITHDRAW 超未借部分被拒；LOAN_IF_DEPOSIT/WITHDRAW（LIF 负值语义、WITHDRAW 不得推更负）；ADD_LOAN 三段各自校验 + 一段非法只 warn-skip 不影响另两段；SymbolLoanConfig resolve 派生（liquidation=initial+liqBuffer 等）+ kill-switch(initial=0 只清 initial 保留 liquidation/maxAmount)；collateralWeightBps 写到 base currency（非 symbol）last-writer-wins。
- [ ] Step2-4 TDD。Step5 commit `feat(loan): Pool/LIF 运维命令 + ADD_LOAN 运行时配置`。

---

### Task 7：Force-liquidate（R1 预移 + R2 结算钩子 + LIF 接管）
**Files:** Modify `loan_command_dispatcher.rs`（`handle_loan_force_liquidate`/`handle_loan_cross_force_liquidate` R1；`post_process_loan_force_liquidate`/`post_process_loan_cross_force_liquidate` R2；`take_over_by_insurance_fund` Isolated）、`loan_service.rs`（`settle_liquidation_proceeds` 含 loanLiquidationFeeBps ceil skim→LIF；`take_over_cross_loan` 比例分摊 + 确定性 currency 序 + fail-closed；`take_over_remaining_cross_loans` sorted loanId 序；`is_structurally_sellable`）、`risk_engine.rs`（handler_risk_release 现货结算后追加 postProcess 钩子——参考 §5.2，仅 CURRENCY_EXCHANGE_PAIR + 两 force-liquidate 命令类型）。**参考:** §2.5、§2.10、§5.2、§6.3、`JAVA/processors/loan/LoanCommandDispatcher.java:388-525,715-902,921-933`、`JAVA/processors/loan/LoanService.java:154-166,287-385`、`JAVA/processors/RiskEngine.java:937-945`。**本阶段最纠缠任务。**

**Interfaces:**
- Consumes: 现货 R2 结算路径（P3 handle_matcher_events_exchange_sell/buy）——**完全不改**，钩子是其后的 `if`。
- Produces: R1 预移（原子 `collateral -= sellAmount; exchangeLocked += sellAmount`，compare-and-consume 使重复提交安全）、返回 VALID_FOR_MATCHING_ENGINE + action=ASK/orderType=IOC；R2 汇总 TRADE/REJECT 事件（REJECT 退回 collateral）、settleLiquidationProceeds、accrueTo 二次补 pending、终态三分支（remainDebt>0&&卖不动→LIF 接管；isEmpty→删；else 留部分）、LOAN_LIQUIDATED 事件条件（tradedSize>0||takenOver）。Cross 额外：结构性 exhaustion 检查、目标 loan 比例接管、全抵押耗尽则 sorted loanId 序接管其余 cross loans。
- LIF 接管资金流严格照参考 §6.3（Isolated：LIF-=principal+interest、pool 复原、interestRevenue+=interest、collateral 物理 accounts-=/LIF+=）。

- [ ] Step1 失败测试：Isolated force-liquidate 全成交（settle 后 remainDebt=0、loan 删、守恒）；部分成交（loan 留、快照事件）；全 REJECT（collateral 退回、accrue 补 pending）；卖不动+remainDebt>0 → LIF 接管（LIF 两币变化、collateral 物理转移、守恒仍零）；重复提交 R1 幂等（compare-and-consume）。Cross：结构性不可卖 → 接管；全耗尽 → 其余 cross loans 按 loanId 升序各自接管各自事件。
- [ ] Step2-4 TDD（逐子场景）。Step5 commit `feat(loan): force-liquidate R1 预移 + R2 结算 + LIF 接管`。

---

### Task 8：Reprice 管线（LoanRatePricingProcessor TwoStep）
**Files:** Create `processors/loan_rate_pricing_processor.rs`。Modify `risk_engine.rs`（REPRICE_LOAN_RATES 经 `is_non_trading()` → collectInput(R1) → buildMatcherEvents(merge) → applyEvent(R2)；handler_risk_release 尾部 setLastRepriceTs 一次）。**参考:** §4.2、`JAVA/processors/LoanRatePricingProcessor.java` **全文** + `JAVA/processors/RiskEngine.java:906-913`。TwoStep sibling 先例 FundingFeeCommandProcessor（P6 才移植，本任务只需 LoanRatePricingProcessor 这一薄实例）。

**Interfaces:**
- Produces: `LoanRatePricingProcessor`——R1 `collect_input`（每 shard 写 loanPoolBorrowed@key=ccy / loanPoolAvailable@key=!ccy 的符号编码，本移植单 shard 亦保结构）；merge `build_matcher_events`（跨 shard 求和 → util → 每 ccy 一个 LOAN_REPRICE_EVENT，currency 升序）；R2 `apply_event`（per event：`advance_accumulator(ccy,ts)` 先于 `reprice_currency(ccy,util)`）；末尾 `set_last_reprice_ts(ts)` 一次。

- [ ] Step1 失败测试：单 currency 有借有贷 → util 正确 → reprice 后 current_rate_bps 按曲线更新；advance_accumulator 在 reprice 前（旧率结清旧区间）；多 currency 升序发事件确定性；last_reprice_ts 全事件后设一次。
- [ ] Step2-4 TDD。Step5 commit `feat(loan): reprice TwoStep 管线（利用率驱动浮动利率）`。

---

### Task 9：守恒扩展 + loan e2e + proptest（收官 P5）
**Files:** Create `core/loan_e2e_tests.rs`。Modify 守恒断言 helper（e2e_tests / futures_e2e_tests 共用的全局守恒函数——纳入 loan 桶 + loanCollateral split）。**参考:** §6.2 完整恒等式、§6.3/§6.4、`JAVA/common/api/reports/TotalCurrencyBalanceReportResult.java:40-150`。

- [ ] Step1 扩展全局守恒函数：每 currency 加 `loanPoolAvailable + interestRevenue + loanInsuranceFund` + 显式 `loanCollateral` 桶（Σ isolated collateral + Σ cross_loan_collateral），`loanPoolBorrowed` **排除**；断言 == 0 零容差。
- [ ] Step2 e2e 场景（每步断言全局守恒）：open Isolated → accrue → partial/full repay；Cross borrow 多笔 → withdraw 边界 → repay；force-liquidate 全成交 / LIF 接管；POOL/IF 运维；reprice 后利息累积再 repay。
- [ ] Step3 守恒 proptest：随机 loan 命令流（create/repay/add/release/cross-*/pool/if/force-liquidate/reprice + 随机 mark 价 >0 内），跑完断言全局守恒每币 == 0、无负 accounts（非 LIF）、无 panic、loanPoolBorrowed == Σ outstandingPrincipal（tracker 一致性）。**若发现真实守恒违规 → 停下报告，不弱化**（除非确证是 Java 忠实复现的既有缺陷——按既定 parity 处理并记录）。
- [ ] Step4 全绿。Step5 commit `test(loan): loan e2e 场景 + 全局守恒 proptest`。

---

## P5 完成定义
- `cargo test` 全绿；14 loan/pool 命令 + ADD_LOAN + REPRICE 经 ExchangeApi/dispatcher 可跑；Fixed/Floating 利率、Isolated/Cross LTV、force-liquidate + LIF 接管、reprice 全实现；虚拟锁接现货+期货 NSF；全局守恒 proptest 绿（含 loan 桶）。
- 自动清算**扫描器**（LoanLiquidationEngine + 索引维护）明确 → P6，以桩/无（force-liquidate 由命令直接驱动，非扫描器），未静默产错。

## 自查（对照参考 + 计划一致性）
§1 模型→T1；§4 利率→T2；§3.4 虚拟锁→T3；§2.1-2.4 Isolated→T4；§2.6-2.9+§3.2 Cross→T5；§2.11-2.12 运维/配置→T6；§2.5+§2.10+§5.2+§6.3 force-liquidate+LIF→T7；§4.2 reprice→T8；§6 守恒→T9。全覆盖。类型 `LoanService`/`LoanRecord`/`FloatingRateModel` 跨任务签名一致（Task1 定义、Task2/4/5/7 消费）。`loan_collateral_locked` 在 T3 一次填真，T4+ 复用。范围界定：扫描器→P6 已在两处标注。

---

## P5 完成状态（2026-09-02）
**完成。** 9 任务全部评审 clean + 全分支终审 **Ready to merge: YES**。`cargo test` **677 绿**（+214 loan 测试，含 loan 守恒 proptest）。执行账本：`.superpowers/sdd/2026-09-02-exchange-core-rust-port-p5-loan/progress.md`（工作区随收官删除；摘录于此）。

**全分支终审（opus）两大签核（显式）**
- **无潜在守恒 bug**：逐路径核对所有动钱操作皆为「配对守恒」——disburse/apply_debt_payment/settle_liquidation_proceeds/LIF 接管(Isolated+Cross，每次抵押扣押恒为 `crossLoanCollateral−=/accounts−=/LIF+=` 三行匹配三元组，估值 bookkeeping 无论如何截断都不破守恒)/cross-borrow rollback(disburse 前 remove 无动钱)/pool·LIF 运维(桶↔adjustments 对冲)。§6.2 恒等式 telescope 后是对独立 mutate 状态的可证伪约束（非 ΣX−ΣX）；loanPoolBorrowed==Σoutstanding tracker 独立校验；accumulated_interest/outstanding_principal 正确不进任何桶（是 claim，付款时才实现）。
- **无可达 Rust-only panic**：每个 `*_exact`/panic 站点都映射到 Java 同样 throw 的 `Math.*Exact`；唯一 Java 有意 catch→哨兵的 crossLtvBps，Rust 忠实用 checked→i64::MAX/unevaluable 不 panic；无可达除零（bankruptcy-price 除法在未移植的 scanner 里，P5 handler 收 cmd.price 限价）；Task5 Cross-LTV 溢出经 Task6 ADD_LOAN `[0,10000]` 门守端到端闭合。
- 跨任务一致性/确定性（weight DESC·currency ASC·loanId ASC·reprice currency ASC）/crash-safety 全 clean。

**完整守恒恒等式（每 currency，零容差）**：
`Σ_user(accounts − exchangeLocked − loanCollateral) + extraMargin + exchangeLocked + loanCollateral + (loanPoolAvailable + interestRevenue + loanInsuranceFund) + fees + adjustments == 0`（loanPoolBorrowed 排除=tracker）。

**Commit 链（P5 range 3762c73e..bf7f6fb1，12 commits）**：plan 3762c73e｜T1 3cd5d602+fix 3a68ae2a｜T2 fa2be541｜T3 4af2a426｜T4 a79bff66｜T5 fbc34ed1｜T6 26062a26+doc 2332b1ed｜T7 83513171｜T8 fd7119b2｜T9 9382602c+follow-up bf7f6fb1。

**carry-forward → P6（终审确认，均惰性/接受范围）**
- 自动清算 SCANNER：`LoanLiquidationEngine`（checkLoans/checkIsolated/checkCross/pick 函数/is_structurally_sellable）+ 非复制索引（isolatedLoanSymbolToUsers/crossLoanCurrencyToUsers）+ sync 钩子（onIsolatedLoanOpened/Closed/syncCrossExposure）。**P6 须补** `post_process_loan_cross_force_liquidate` 里 Java 调 `syncCrossExposure(takerUp)` 的调用点（Rust 已留注释 :1091）。
- **bankruptcy-price 计算**（`ceilMulDiv(mark, realDebt, collateralValue)` + `collateralValue<=0` skip 的除零守卫）只在未移植 scanner 里——P6 移植时须带上该守卫。
- 事件发射（LOAN_LIQUIDATED/LOAN_BORROW/margin-call）：全 port 无事件总线，账本为准；`cum_interest_paid` 已维护但仅事件消费。
- LIQUIDATION_SCAN 与 LOAN_IF_DEPOSIT 共享字节码 64（Task1 记，LIQUIDATION_SCAN 未移植，P6 移植时注意）。
- minor（非阻断，Java-faithful）：postProcess 从 avg_taker_price 重算 taker fee 可能与现货逐笔 fee 舍入不同——只移动 user-overpay↔LIF/pool 的分配，不破守恒。
