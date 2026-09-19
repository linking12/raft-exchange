# Java ↔ Rust 一致性保障

`exchange-core-rs` 是 Java [`exchange-core`](https://github.com/exchange-core/exchange-core)(`exchange.core2`)撮合引擎的 Rust 全量对等移植。两套独立实现同一套业务,核心工程问题有二:

- **(A) 证明行为等价**——此刻 Rust 与 Java 对同样输入产出同样结果;
- **(B) 防止漂移**——任一侧后续演进(Java 打补丁 / Rust 重构)后仍等价,且能自动发现回归。

本文档描述为此建立的**三层递进防线 + 一份归一化规格**,以及框架已发现的真实问题。它不是"人肉翻译几个断言"就完事——每一层针对前一层的盲区。

---

## 目录

- [1. 总览](#1-总览)
- [2. 防线①:IT 翻译对拍](#2-防线it-翻译对拍)
- [2b. 防线①b:组件/单元测试黄金值对拍](#2b-防线b组件单元测试黄金值对拍)
- [3. 防线②:守恒 proptest](#3-防线守恒-proptest)
- [4. 防线③:黄金向量对拍(Java 当 oracle)](#4-防线黄金向量对拍java-当-oracle)
- [5. 防线③b:差分模糊](#5-防线b差分模糊)
- [6. 归一化规格(刻意差异清单)](#6-归一化规格刻意差异清单)
- [7. 框架发现的真实问题](#7-框架发现的真实问题)
- [8. 命令流 DSL 参考](#8-命令流-dsl-参考)
- [9. 输出格式规格](#9-输出格式规格)
- [10. 工作流与 CI 门禁](#10-工作流与-ci-门禁)
- [11. 路线图 / 未做](#11-路线图--未做)

---

## 1. 总览

| 层 | 手段 | 主要抓什么 | Oracle | 主要盲区 |
|----|------|-----------|--------|---------|
| ① IT 翻译 | 逐条翻译 Java IT,断言结果码/事件/费用/仓位/守恒 | 已知**端到端**场景的逐值行为 | Java 单测的黄金值 | 只覆盖翻译到的场景;端到端可能碰不到组件级边界;静态、会漂移 |
| ①b 组件/单元对拍 | 把 Java 数学敏感单测的 `assertEquals` 黄金值逐条钉进 Rust `#[cfg(test)]` | 组件级取整/缩放/边界/公式(IT 端到端漏的) | Java 单测的黄金值 | 只覆盖翻译到的单测;静态、会漂移(纯 Rust 侧) |
| ② 守恒 proptest | 随机命令流每步断言全局守恒等不变量 | 输入空间里的金额/守恒破坏 | 不变量(无需 oracle) | 抓不住"守恒集内的归属互换" |
| ③ 黄金向量对拍 | 同一命令流两侧各跑,Java 实际输出当黄金,Rust replay 断言 | 任意场景(含 Java 单测不断言的)的逐值行为 + 抗漂移 | Java **引擎实际输出** | 需归一化刻意差异;异步事件需 settle |
| ③b 差分模糊 | 确定性 PRNG 批量生成随机流,喂 ③ 的流程 | 输入空间的边角、未覆盖组合 | 同 ③ | 同 ③ |

关键递进:
- ① 强在"直接对拍 Java 数字",但**只能对拍 Java 断言过的东西**、且**只走端到端**——Java 宽松处(清算/ADL 只验 state)① 无能为力,而端到端流程可能永远碰不到某个取整/缩放/边界(翻译错了 IT 未必红)。
- ①b 补 ① 的"端到端盲区":直接把 Java **组件级单测**的黄金常量钉进 Rust——`sizePriceToCurrencyScale` 截断、`calculateSizeToLiquidate`、`calculateLocked`、cross-margin scale、开平费公式、破产价、清算价迭代解、利率曲线、`checkCross` 除零守卫等。翻译里任一取整方向/缩放/边界错,对应单测立即红。
- ② 用不变量绕开 oracle 覆盖输入空间,但守恒是**必要非充分**(金额总额对、归属可错)。
- ③ 用 **Java 引擎的实际输出**当 oracle,补上 ① 的 oracle 盲区(清算/ADL 的最终状态现在能逐值对拍)、并让两侧入 CI 抗漂移。
- ③b 在 ③ 之上用随机流把输入空间压满。

### 1.1 完成状态(2026-09-19)

**作为独立的确定性撮合引擎(单节点 / 纯 Rust raft 集群),Rust 端口已达可上线级完成度、与 Java 撮合语义对齐。** 撮合 + 风控 + 结算全域(现货 / 期货隔离·全仓·HEDGE / 清算·ADL·IF / funding / loan 隔离·cross / 交割)经**逐行深审 + 五道防线**验证:交易资金三路径逐行零分歧;报表 / 校验 / 两步 apply 三层新对比基本零分歧;① IT 翻译 357 + ①b 组件 78 + ② 守恒 proptest(含 funding/HEDGE/cross)+ ③ 黄金向量 **89**(全 27 类 `FundEventType` 逐事件 + 14 个 `#!match=on` 的 ER/ERF)+ ③b 差分模糊,全绿(lib 993)。快照 Chronicle 读写与 Java 双向逐字节对齐。历次抓到并修的真分歧见 §7。

**刻意不移植**(单线程确定性状态机下 N/A,非缺口):Disruptor 多分片 / 异步提交层 / `groupingControl` / `NO_RISK_PROCESSING` / journaling(log 即 raft)——见 §6。

**仍开放的边界**(不属于引擎撮合本体,仅"Rust 节点混入现有 Java raft 集群热迁移"才需要):① **混合集群命令流兼容**——`BinaryCommandsProcessor` 的解码 / 推进未移植(现仅快照透传),Rust 节点消费 `BINARY_DATA_COMMAND`(批量 add symbol/currency 等)尚需这块,**纯 Rust 集群不需要**;② **真·live 同进程双引擎比对**——刻意用离线 golden 差分替代(§11)。

---

## 2. 防线①:IT 翻译对拍

**位置**:`tests/integration/it_*.rs`(独立集成 crate,只用公开 API)。

**做法**:把 Java `exchange.core2.tests.integration` 的每个 IT 逐条翻译成 Rust,喂**相同命令序列**,逐值断言:
- **API 结果码**:`Success` / `RiskNsf` / `MatchingMoveFailedPriceOverRiskLimit` / `AuthInvalidUser` / `LoanNotEnabled` / `InvalidSymbol` …
- **matcher event 逐字段**:`TRADE`(price / size / maker_order_id / matched_order_uid / maker_order_completed / active_order_completed / bidder_hold_price)、`REDUCE`、`REJECT`。字段按 Java→Rust 映射(`matchedOrderId→maker_order_id` 等)。
- **fund event**:直接提交命令的资金事件(现货费池、internal_transfer 双腿、reset_fee shape、funding 落 position.profit)。
- **费用**:费池金额 + 逐用户 maker/taker 归属拆分。
- **仓位 / 账户 / 守恒**:`user_account`、`user_position`、`total_balance().is_global_zero()`。

**强**:凡 Java 自身断言过黄金值的路径(现货/期货/费用/资金费/交割/cross 预警的 `liquidation_price`/`margin_ratio` 等),都是真·逐值对拍 Java——行为等价的直接证据。

**弱 / 盲区**:
1. **受限于 Java 断言强度**:`ITLiquidationIntegration` / `ITExchangeCoreADL` 本身**零 fund event 断言**、只验 state,故清算/ADL 的金额/事件 ① 对不了(靠 ③ 补)。
2. **静态点覆盖**:只覆盖翻译到的场景。
3. **会漂移**:纯 Rust 侧测试,Java 变更不会触发它红。

---

## 2b. 防线①b:组件/单元测试黄金值对拍

**位置**:各生产文件内 `#[cfg(test)] mod java_parity`(或 `parity_*` 测试),与被测函数同文件。

**动机**:① 只走**端到端**公开 API。Java 的 `tests/unit` + `core/**` 有一批**组件级**单测,拿 `assertEquals` 精确钉住取整方向、缩放截断、边界、公式常量——这些点端到端流程未必触达(翻译错了 IT 未必红,但对应单测必红)。Rust 的 856+ lib 单测是**独立**写的,与这些 Java 单测的覆盖是否重合此前无人验证,是最大的"翻译 bug 藏身处"。

**做法**:把 Java 数学敏感单测的黄金常量**逐条**钉进 Rust 同名/对应函数的 parity 测试。规则:**钉 Java 精确值;若 Rust 算出不同值即候选翻译 bug,绝不改期望值迁就**。

**已对拍**(8 个 Java 单测 → 65 个 Rust parity 测试,**全绿、零分歧**):

| Java 单测 | Rust 落点 | 钉住的黄金值(样例) |
|-----------|-----------|----------------------|
| `CoreArithmeticUtilsScaleTest` | `core_arithmetic_utils.rs` | `sizePriceToCurrencyScale(1)=0`(0.0001 USD 截断)、`symbolToCurrencyScale=1000/2000/10/20`、零 digit `=100000` |
| `OpenCloseFeeFormulaTest` | `core_arithmetic_utils.rs` | fixed `taker=200/maker=100`(与 price 无关)、dynamic `ceil`=10000/5000、`ceil(0.02)=1` |
| `SizeToLiquidateTest` | `core_arithmetic_utils.rs` | 10 / 5 / 100 / 200 / 150 / 300(long/short 全清算档) |
| `RiskEngineCalculateLockedTest` | `risk_engine.rs` | ①期货 margin+②spot+③isolated 抵押+④cross 抵押 混合 `USDT=1700` / `BTC=5` |
| `RiskEngineCrossMarginScaleTest` | `risk_engine.rs` | 另持 `scale=10^4` cross 仓时小单 `VALID_FOR_MATCHING_ENGINE`(保证金不被放大 10000×) |
| `LoanRateCurveTest` | `floating_rate_model.rs` / `fixed_rate_model.rs` / `loan_service.rs` | 曲线 `200/400/600/3600/6600`、利用率 `3000/10000`、溢出 fallback `5000`、accumulator 累积 |
| `SymbolPositionRecordTest` | `symbol_position_record.rs` | 清算价迭代解 `48369/55555/50926/47282/-1/48913`、破产价 `96/104/95/105`、`pendingHoldBudget` |
| `LiquidationCheckCrossScaleTest` | `liquidation_engine.rs` | 缩放后 MM 归零不触发除零、健康账户不误强平 |

**成效**:数学敏感层的翻译**零分歧**——`i128` 中间量、`ceil`/`trunc` 方向、scale 换算、迭代法清算价、SHORT 分母 sign 等易错点,均与 Java 逐值一致。

**弱 / 盲区**:同 ①——只覆盖已翻的单测(Java `tests/unit` 里非数学的行为类单测、`core/**` orderbook/event 组件测试尚未逐条对拍),且纯 Rust 侧、会漂移。

---

## 3. 防线②:守恒 proptest

**位置**:`tests/e2e/`(`e2e_tests` 现货、`futures_e2e_tests` 期货、`loan_e2e_tests` 借贷、`liquidation_e2e_tests` 清算级联)。

**做法**:`proptest` 生成任意**合式**命令流,逐步跑,每步断言:
- **全局守恒**:每币种 `Σ账户 + 调整桶 + 费用桶 + Σ开仓(estimate_pnl(mark) + extra_margin) + IF/LIF 桶 == 0`。
- **账户非负**、仓位内部字段非负。
- 清算 proptest 额外覆盖 `FORCE→IF→ADL` 级联下含 IF 的守恒。

**强**:覆盖输入空间,抓金额漂移 / 守恒破坏 / panic。

**弱 / 盲区**:守恒是**必要非充分**——它抓不住"同一守恒集内的归属互换"(如把 maker 费记到 taker 头上,池总额与守恒都不变)。这正是 ① 里补逐用户 maker/taker 拆分断言的原因。

---

## 4. 防线③:黄金向量对拍(Java 当 oracle)

这是根治 ①② 的 oracle 受限 + 漂移的核心层。

### 4.1 三步流水线(Java 夹在中间)

```
① gen_conformance_fuzz (Rust, 可选)   →  写 .stream(随机流)
② ConformanceExporter  (Java)         →  读 .stream, 跑 exchange-core, 写 .golden   ← oracle 在这
③ tests/conformance.rs (Rust)         →  读 .stream + .golden, 跑 exchange-core-rs, 逐行断言 == .golden
```

- **命令流 `.stream`**:与实现无关的 DSL(见 §8),存放 `tests/conformance_vectors/`。
- **Java 导出器**:`exchange-core/src/test/java/exchange/core2/tests/conformance/ConformanceExporter.java`。用 `ExchangeTestContainer` 跑真实 exchange-core,**直接把引擎实际输出**写成同名 `.golden`——**不依赖 Java 单测断不断言**。
- **Rust replayer**:`tests/conformance.rs`。同一 DSL 解释器喂 `ExchangeApi`,产**同格式**输出,`assert_eq!` 逐行比对 `.golden`。
- `.stream` 与 `.golden` **一并入库**(种子固定、可复现)。

### 4.2 为什么 Java 必须夹在中间(而非并进 Rust 一个进程)

golden 由 Java 在**生成之后、断言之前**产出。若把生成器并进 `conformance.rs`,跑 `cargo test` 会重写 `.stream` 但此刻没有对应的新 Java golden → 必然失败。故生成器是 `examples/` 下的**一次性工具**(要加/换向量时手动跑),不是每次 `cargo test` 都跑的东西;`conformance.rs` 只读、只断言。

### 4.3 对拍内容

每个向量的 `.golden` 含最多四段(见 §9 格式):
1. **每命令结果码** `R <seq> <CODE>`(`CODE` 用 Rust CamelCase 自动转 Java SCREAMING_SNAKE,命名分歧会被对拍直接抓到)。
2. **最终状态摘要** `STATE`:排序的账户 `A`、仓位 `POS`、费用池 `FEE`(跳 0 值,分片/线程无关)。
3. **结算类 fund event 多重集** `EVENTS`:见下。
4. **撮合执行报告** `MATCH`(仅同步向量 opt-in `#!match=on`):经 `SimpleEventsProcessor` 产出的 `SpotExecutionReport`/`FuturesExecutionReport`,`ER`/`ERF` 行**按发出顺序**逐字段对拍——验证 match event 不多发/漏发/错发。两侧 fund event(`FE`)与 match event(`ER`/`ERF`)都从**同一个 `SimpleEventsProcessor` 出口**流出(Rust `tests/conformance.rs` 挂 `results_consumer`、Java exporter 挂 `SimpleEventsProcessor4Test`),口径统一。异步清算/ADL 向量刻意不开 `#!match=on`(exporter 异步捕获非确定,见 §6)。

### 4.4 关键收益:清算/ADL 的状态现在能逐值对拍

`ITLiquidationIntegration` / `ITExchangeCoreADL` 自己**不断言** fund event、仅验 state,① 对不了这些数。③ 用 **Java 引擎实际跑出的最终状态**当 oracle,Rust 必须逐字节一致。例:ADL 向量断言 loser 强平清仓、winner 减仓 `10→5` 且 `open_price_sum` 重定价、maker 剩余 5 手、fee 30——全部对拍 Java 实际输出。

### 4.5 同步 vs 异步(事件层的边界)

- **fund event 多重集**用**全流累加、排序后比对**(不比逐命令归属)——因为强平触发时机是刻意差异(Rust markprice 定向扫 vs Java `LIQUIDATION_SCAN`),但"发了哪些结算事件"多重集可比。
- **同步路径**(funding / delivery / loan / cross / hedge,命令 `.join()` 后事件已到):结算事件逐条对拍。例 funding:`FUNDINGFEE_SETTLEMENT` 两条 free=`19750`/`19650` 精确命中 Java `ITPerpetualContractIntegration` 黄金值;delivery:`PNL_SETTLEMENT` free=`24900`/`14800`;loan:`LOAN_BORROW`/`LOAN_REPAY`(`loan_isolated_cycle`);cross:单账户两 CROSS 仓账户级聚合(`cross_margin_shared`);hedge:同 symbol LONG/SHORT 双腿并存(`hedge_dual_leg`)。
- **异步路径**(清算/ADL:Java 强平走独立线程、fund event 在不同 scan 周期触发、捕获不确定):向量用 `#!events=off` **只对拍确定性的 STATE**,不对拍其事件流。exporter 的 `SCAN` 会循环 `triggerLiquidation`+`groupingControl` 直到状态**连续 6 轮稳定**(对齐 Java `testADL` 的 `waitForCondition`)。

### 4.6 确定性

Rust 侧完全确定(单管线同步)。Java 侧的异步部分靠上面的稳定循环收敛——已验证连续 3 次生成 golden 逐字节一致。**exporter 每命令后 `totalBalanceReport()` flush** 消除 Java 批处理 R1/R2 时序(见 §7①)对结果码的影响。

---

## 5. 防线③b:差分模糊

**位置**:`examples/gen_conformance_fuzz.rs`。

**做法**:xorshift64 确定性 PRNG(无依赖,固定种子)批量生成随机现货命令流(多用户、GTC+IOC、各类价/量/方向、偶发巨量触发 NSF),写入 `conformance_vectors/`,走 §4 的同一流程。把撮合引擎压满:crossing / partial fill / IOC / NSF / 多档吃单。

**跑**:`cargo run --example gen_conformance_fuzz`(重生成)→ Java 导出 golden → `cargo test --test conformance`。

**成效**:已抓到并定性两个 Java 侧问题(见 §7),二者 Rust 皆正确。

**未做**:真·live 双引擎同进程比对(JNI 或双跑同一随机流实时比对)——更重,当前用"生成向量 + 入库 golden"的离线差分替代。

---

## 6. 归一化规格(刻意差异清单)

差分对拍前必须声明"哪些刻意不同、归一化后排除/折叠,其余必须逐字节相等"。**清单内是设计取舍,不是逻辑分歧**;清单外的任何差异即真分叉、报错。

| 差异 | Java | Rust | 处理 |
|------|------|------|------|
| **并发/分片** | Disruptor 多处理器 + RiskEngine 按 `uid & shardMask` 多实例分片 | 单线程确定性单管线、单分片(`shardMask=0`) | 状态经报表聚合后分片无关,可比;事件用全流多重集 |
| **强平触发 / 周期兜底扫** | on-lane(markprice/funding apply 即 targeted 检测)**+** 定时线程周期发 `LIQUIDATION_SCAN` 全量兜底(`LiquidationScheduledService`,默认 2s) | on-lane 同 Java;周期兜底**发令逻辑**在 `scheduler.rs::run_one_iteration`,产出的 `LIQUIDATION_SCAN`/`REPRICE_LOAN_RATES` 经 `LiquidationScheduler.command_submitter` 回调出口(= Java `LiquidationCommandSubmitter`,与 cascade 同一 sink);**墙钟由外层驱动**(库内不含线程/定时器,便于单测),scheduler 不再由 `ExchangeCore` 持有 | 主触发两侧相同=on-lane;周期兜底扫两侧都有,scan 与 FORCE/IF/ADL 走同一回调出口。事件用全流多重集比,不比逐命令归属 |
| **清算命令提交 / 级联执行模型** | `LiquidationEngine`(继承 `LiquidationScheduledService`)持 `commandSubmitter` 回调,生成的 **scan/FORCE/IF/ADL** 调 `submit(cmd)` → `ExchangeCore.setCommandSubmitter` 注入的 `api.submitCommand`:无 raft 直接进 ring buffer、有 raft **经共识后**回流 apply | **回调模型对齐 Java**:`LiquidationEngine`/`LoanLiquidationEngine`/`LiquidationScheduler` 各持 `command_submitter` 回调(Rust `Box<dyn FnMut(OrderCommand)>`);`ExchangeCore::new` 把它注册成"塞进 `ExchangeCore.pending_commands: Rc<RefCell<Vec>>`"(= Java 单节点 ring buffer)。**单节点**=`process_command` 处理完主命令后 inline 自驱 `pending_commands`(逐条再走 R1→ME→R2 直到清空);**集群**=回调改注册成 raft 提交 → `pending` 恒空、驱动循环 no-op,`take_pending_commands` 交外层过共识、提交后逐条回流各自一次 `process_command`。leader-gated 生成不变 | 提交模型两侧已对齐(回调出口,覆盖 scan/FORCE/IF/ADL)。**单节点**驱动序与 **集群** 共识序不同(前者会把 ADL origin 完全消耗后再跑其 stale ADL);但 R1 夹位 `normalizeCmdPositionSize`↔`normalize_cmd_position_size` **两侧逐字节一致**(`min(cmd.size, openVolume)`,`null`/`None` 分支都 `cmd.size=0`),故任何顺序都不造钱,终态一致(见 §7.5)。**raft submitter 实际实现(jraft/aeron)在外层 server,不在本 crate**;库内提供回调出口 + 单测分流(`cluster_mode_hands_cascade_*` / `cluster_mode_cascade_completes_across_rounds_*`)。逐命令结果经 `ExchangeCore.results_consumer`(= Java `resultsConsumer`,`SimpleEventsProcessor` 可挂其上)触发,不再有引擎侧 `last_cascade_events` 聚合。命令 byte 码 `LIQUIDATION_SCAN` 两侧现均=44(Java 原 64 与 `LOAN_IF_DEPOSIT` 撞码,已改,便于上 raft 按码序列化) |
| **`MARGIN_ALERT`/`LIQUIDATION_ALERT`/`LOAN_MARGIN_CALL`(逐仓风险告警)** | 引擎内随 markprice/scan 发 | Rust 同样发(`liquidation_engine`/`loan_liquidation_engine`) | **全部已进 ③ 逐事件对拍**:`LIQUIDATION_ALERT`(8 向量)、`MARGIN_ALERT`(`margin_alert_isolated`/`margin_alert_cross`,告警带 mm≤equity<mm*6/5)、`LOAN_MARGIN_CALL`(`loan_margin_call`,marginCallLtv≤LTV<liqLtv)。⚠ 告警**非幂等**(每次扫描重发),向量用**单次 `MARK_AT` 触发、不加 `SCAN`** 避免重复。注:此为**逐仓位风险告警**,与"池子水位告警走外部拉报表"(`pool-monitoring-external`)是两回事 |
| **仓位生命周期事件** `OPEN_POSITION`/`CLOSE_POSITION` | 每笔成交 taker/maker 各自按"本仓开/增→OPEN、平→CLOSE"发(guard `sizeToOpen>0`/`closedSize>0`) | 规则逐行相同(`settle_margin_position_event`,同 guard) | **已进 ③ 逐事件对拍**(2026-09-19):两侧发射规则经代码深审确认完全一致,168 条 OPEN + 21 条 CLOSE 逐值对拍。⚠ golden **必须隔离(单向量)生成**——Java exporter 全量生成会因双发(R2+main)`processed` 去重竞态重复捕获(见 §10/[[conformance-exporter-async-flaky]]);Rust 单发确定无此问题 |
| **spot 锁事件** `Locked`/`Unlocked` | place 发 Locked;cancel/reduce/reject(`release>0`)、trade 超额退款(`quoteRefund>0`)发 Unlocked | 规则已对齐 Java(2026-09-19 修 3 处发射:sell handler maker 退款 Unlocked、buy handler taker 退款 Unlocked、reject 加 `release>0` 守卫,见 §7.8) | **已进 ③ 逐事件对拍**;金额中性(只补/收敛报告事件,账户/lock 算术不变) |
| **记账事件** `Deposit`/`Withdraw` | `balance_adjustment` 等会发 | 部分不发 | 排除(非锁/结算类;`INTERNAL_TRANSFER` 已单独进对拍) |
| **futures 锁事件** `LockPending`/`UnlockPending` | PLACE_ORDER(`:502`)/CLOSE_POSITION(`:863`)R1 预锁发 LockPending;TRADE/REJECT/REDUCE 释放发 UnlockPending | 规则平价:PLACE `risk_engine.rs:105`、CLOSE `:194` 发 LockPending;释放 `:1598`/`:1655` 发 UnlockPending,`free`/`locked` 用 `calculate_locked` 全量重算,与 Java 逐值一致 | **已进 ③ 逐事件对拍**(2026-09-19,199 LOCK_PENDING + 194 UNLOCK_PENDING),无需改生产代码(Rust 早已发);多分片路由键差异不入对拍口径(harness 只比 type/uid/cur/free/locked) |
| **撮合明细事件(高层报告)** | `SpotExecutionReport`/`FuturesExecutionReport` | 经 `SimpleEventsProcessor` 产出同型报告 | **同步 + 异步清算向量都已进 ③**(14 个 `#!match=on`,`MATCH` 段 `ER`/`ERF` 逐字段;含 FORCE/ADL/IF 强平执行报告,顺序两侧确定一致,见 §7.9);golden 用隔离生成 + 自愈规避 exporter 捕获竞态 |
| **执行报告 exec-id / trade-id** | `seq` 由 disruptor 定(R2 `-seq` + 主 `+seq` 双发) | `results_seq` 单发递增 | `ER`/`ERF` **剔除** `tid`/`eid`(seq 口径刻意不同);taker==maker 共享 id 的不变式由 ① + `simple_events_processor` 单测覆盖 |
| **`SimpleEventsProcessor` 出口结构** | `accept(cmd,seq)` 双发(`seq<0` R2 只发 fund event、`seq>=0` 发执行报告+fund+行情),`processed` 标志跨两发去重;fund event 分 `takerFundEvents`(isMaker=false)与 `makerFundEventsByShard[]`(isMaker=true,分片) | `process()` 单发(执行报告+fund+行情一次出);fund event 收敛成扁平 `cmd.fund_events`(exec-id 的 isMaker 位恒 false),无 `processed` 标志、无分片 | 单线程/单分片塌缩的必然结果:单发=Java 两发的并集,**发出的 fund event 集合与执行报告逐字段一致**(exec-id 已按上一行剔除);taker/maker 拆分与 isMaker 位仅影响被剔除的 exec-id |
| **异常隔离** | `accept` try/catch 记日志(多线程下坏 handler 不拖垮撮合) | 无 try/catch:`consume` 在 `apply_one` 内同线程调用,handler panic 直接上抛 | 刻意:确定性状态机里 handler 属纯观测层,吞 panic 会掩盖 bug 且威胁 raft 确定性,故 fail-fast |
| **MOVE 成交后 mover 的 `filledNotional`** | ~~不累计~~ **已修**:`moveOrder` 补 `filledNotional` 累计(§7.6) | 两者都累计(自洽) | 两侧一致;`spot_cancel_reduce_move`(MOVE 成交后 cancel 看 cumQ)对拍作回归护栏,`spot_cancel_after_fill` 对拍正常成交路径 |
| **`LIQUIDATION_FEE` 事件的 `profit` 字段** | 结算前快照 `position.profit`(如 -300) | 平仓结算已把 profit 归零,fee 事件 `profit=0` | 冗余字段:已实现 PnL 已在 `LIQUIDATION_CLOSE.profit` + `PNL_SETTLEMENT` + 账户体现,fee 事件的 profit 快照不额外比对(`it_mixed` 两个 `*_fully_matched_with_fee` 断言 fee.profit=0) |
| **`symbol_to_users` 强平索引维护** | 平仓时 eager 摘除(`RiskEngine.removePositionRecord` → `onPositionClosed`) | lazy 清理:下一次针对该 symbol 的 `check_positions` 用 `retain()` 剔除无仓持有人(`on_position_closed` 未接进平仓路径) | 内部性能索引(选扫描候选),非可观测资金/行为态;无仓 uid 两侧都不会被强平,清算结果一致(`it_liquidation` symbol_index 测试验 lazy 模型) |
| **`state_hash`** | Java 自己的 hash | 逐字段折叠、是超集 | 不互比;跨实现用 ③ 的语义状态摘要 |
| **现货普通 FOK(`OrderType.FOK`)** | **未实现**(`// TODO FOK support`,整单 reject) | 已实现 fill-or-kill | Rust 更完整;差分模糊不随机普通 FOK(`fok_kill` 手写覆盖)。**`FOK_BUDGET`/`IOC_BUDGET` 两侧都实现、已对拍一致** |
| **`MARKPRICE_ADJUSTMENT` 的 `price<=0`** | 无守卫:置 markPrice=0/负值,返回 SUCCESS(后续期货 place 再因 markPrice 无效被拒) | `price<=0` 直接 `RISK_INVALID_AMOUNT` 拒绝,markPrice 不变 | 刻意:Rust 更严,不接受无意义的非正 mark price(确定性状态机不写坏价);两侧终态资金一致,仅 result code 差 |
| ~~**`SETTLE_FUNDINGFEES` 的 `size<=0`**~~ **已对齐(2026-09-19)** | ~~无条件覆写为 VALID~~ **已修**:`preProcessCommand` 仅在 collectInput 未设错误码时才放行(保留 `RISK_INVALID_AMOUNT`/`RISK_MARKPRICE_NOT_AVAILABLE`) | 返回 `RISK_INVALID_AMOUNT` | 两侧现一致返回 `RISK_INVALID_AMOUNT`(Java 侧覆写 bug 已修,funding/perp ITs 绿) |
| **`add_currency` 重复币种** | skip(返回 false,保留原 spec) | 覆写(幂等重放同值无碍) | 结果码两侧都不暴露(batch binary 忽略返回);重复添加同币种的幂等性细微差,非资金/行为可观测 |
| **`NO_RISK_PROCESSING` 风控短路模式** | `cfgIgnoreRiskProcessing`(`RiskEngine.java:411` placeOrderRiskCheck / `:836` closePositionRiskCheck)置位时短路返回 `VALID_FOR_MATCHING_ENGINE`,跳过余额锁/保证金检查(测试/高频模式) | **刻意不移植**:无此配置,恒走全量风控 | Rust 更严格、绝不放行无锁订单。确定性 Raft 状态机跳过风控会破坏资金完整性,故不移植;正常部署不用该模式,无资金影响(2026-09-19 三路径深审确认) |
| **批处理 R1/R2 时序** | 未成交 IOC ASK 的 R2 锁释放滞后于下条 R1(须 barrier,否则 spurious NSF) | 单管线 R2 恒先于下条 R1 | exporter 每命令 flush,比 settled 语义 |
| **借贷池分片本地性** | `loanPoolAvailable` 每 risk 分片各一份;`POOL_DEPOSIT` 只在 `cmd.uid==shardId` 的片记账,贷款只见**本片**流动性(exporter DEFAULT=2 risk 片) | 单片塌缩,全局共池 | 向量令借款人 uid 与注资 shard 同片(偶数 uid→shard 0),两侧口径一致(见 §7.3) |

> **已消除的差异**:funding receiver 余数 dust 归属曾是刻意差异(Java 按 `LongLongHashMap` hash 序、Rust 按 `BTreeMap` 升序)。2026-09-17 已把 Java `FundingFeeCommandProcessor` 余数分配改为 **uid 升序**(`keySet().toSortedArray()`)= Rust,两侧一致且 Java oracle 更确定。现由 `funding_multi_receiver_dust` / `funding_zero_share_receiver` / `funding_multi_payer_multi_receiver` 三个 events-on 向量对拍。

**对拍白名单**(`tests/conformance.rs::fe_allowed`,进 `EVENTS`/`FE` 多重集的):**`FundEventType` 全 27 类都逐事件对拍**(2026-09-19 补齐 `Deposit`/`Withdraw`/`Transfer`/`LoanCollateralChange`/`ResetFee`;1472 条 TRANSFER、314 条 DEPOSIT 等已对拍)。两侧白名单必须与 `fe_allowed`/Java `ALLOWED` 同步维护。**无刻意排除的 fund event 类型**;所有 27 类均有向量触发(见 §7.9 补的 `withdraw_deposit`/`reset_fee` 等)。

---

## 7. 框架发现的真实问题

差分/翻译对拍不是形式主义,已抓出多个真实缺陷:

### 7.1 IT 翻译经真实管线抓到的引擎 bug(已修 Rust)
`matching_engine_router::process_order` 对 `SETTLE_FUNDINGFEES` / `IF_TAKEOVER` / `AUTO_DELEVERAGING` / `LIQUIDATION_SCAN` 落到 `_ => MatchingUnsupportedCommand` / 订单簿查找,**覆盖了 R1 设的 `Success` 结果码**(这些命令 ME 应 no-op、保留 R1 结果,对齐 Java ME 的显式分支)。老单测走 `run_full_pipeline` 跳过 ME 掩盖了它;IT 走完整 `process_command` 才现形。

### 7.2 差分模糊抓到、经 Java 测试定性的两个 Java 侧问题(Rust 皆正确)

**① Java 批处理 R1/R2 时序 hazard(非逻辑 bug)**
未成交(空簿)IOC 现货 ASK 的 base 锁释放在 R2、滞后于下一条命令的 R1 读;两条 IOC ASK 之间若无 barrier 直接连提,第二条读到未 settle 的 `exchangeLocked` → spurious `RISK_NSF`。
- Java 侧 `ITIocAskLockRelease` 定性:`unfilledIocAskReleasesBaseLock`(中间 report=flush → settle 后释放**正确**、通过)vs `consecutiveIocAskWithoutFlushHazard`(裸连提 → 第二条 `RISK_NSF`)。
- 属 exchange-core Disruptor 已知特性(同 reprice R2/R1 序 hazard,惯例用 barrier 规避,非引擎逻辑错)。Rust 单管线无此 hazard。
- **解决**:conformance exporter 每命令后 flush,比两侧 settled 语义。

**② Java 未实现现货普通 FOK(功能缺口)**
`OrderBookNaiveImpl`/`OrderBookDirectImpl` 的 `newOrder` switch 对 `OrderType.FOK` 落 default 整单 reject(`// TODO FOK support`);Rust 已正确实现 fill-or-kill。能成交时两侧分歧。Rust 更完整。
- 注:**`FOK_BUDGET`/`IOC_BUDGET` 两侧都已实现且逐值对拍**(向量 `fok_budget`/`ioc_budget`)——不要误以为整个 FOK 家族都没实现。

> **教训**:定 Java 侧"bug"前,必用**不含任何 report / `validateUserState` 的裸命令序列**复现——中间任何 report query 会 flush R2、掩盖批处理时序 hazard;Java 未实现的 order type 会走 default reject。

### 7.3 loan/HEDGE/cross-margin 向量扩面时,③ 抓到借贷池分片本地性(非 bug,单片塌缩的直接体现)

新增 `loan_isolated_cycle` 向量时,Java 报 `LOAN_POOL_INSUFFICIENT`、Rust 却建贷成功——③ 直接抓到分歧。根因**不是** `verify_pool_capacity` 逻辑差(两侧逐字节一致、默认利用率上限同为 9000bps),而是 Java `handlePoolDeposit` 有 `if (cmd.uid != shardId) return SUCCESS` 守卫:**借贷池是每 risk 分片各一份**,注资只落在目标分片。exporter 跑的 `PerformanceConfiguration.DEFAULT` 有 2 个 risk 分片,`POOL_DEPOSIT`(shard 0)与借款人 `uid=1`(`1&1`=shard 1)不同片 → 贷款看不到那笔流动性。Rust 单片塌缩天然共池。

- **定性**:属"单分片塌缩"刻意差异(§6)的直接后果,非翻译 bug。
- **解决**:向量令借款人落在注资同片(`uid & (riskEngines-1) == 0`,即偶数 uid=2 → shard 0),使场景在 Java 分片模型与 Rust 塌缩模型下**都自洽**,真正跑通 LOAN_BORROW/LOAN_REPAY 的钱账。
- **教训**:凡涉及**按币种/全局键**(非按 uid)的分片本地状态(借贷池、后续 LIF 池等),向量必须让相关 uid 与注资 shard 同片,否则撞上塌缩差异。

### 7.4 全量逐子系统 review(2026-09-18):资金结算 CLEAN,修 5 处事件/报告层平价缺口

对整个 `exchange-core-rs` 做 6 组并行逐字段对拍(风控/撮合/清算IF ADL/借贷/持仓PnL资金费/API算术)。**资金结算 6 子系统全部 CLEAN**——逐路径对照 Java 验证,零守恒漏洞、无丢失/凭空/重复/错路由(现货 R1锁定+R2买卖、保证金 R2、FORCE→IF→ADL 级联、loan disburse/repay/利息/抵押/LIF、funding/transfer 零和、PnL 实现、i128 算术、reports 9 桶齐全)。发现并修复的均为**非资金的事件/报告层**缺口:

| # | 位置 | 缺口 | 修复 |
|---|------|------|------|
| 1 | `order_book_{direct,naive}_impl` `try_match_instantly`/`match_against` | MOVE 一个**已部分成交**的挂单进入撮合时,TRADE 事件 `filled`/`filled_notional` 从 0 起算(Java `takerOrder.getFilled()` 起),客户端执行报告 `cumulative_qty/quote` 少算历史成交 | 加 `taker_prior_filled(_notional)` 入参,事件字段累加历史成交;**返回值仍是本次量**,订单状态/资金原本就正确 |
| 2 | `risk_engine::close_position_risk_check` | CLOSE_POSITION 的 R1 未发 `LockPending` 事件(Java `sendLockPendingEvent`) | `pending_hold` 后补 `push_futures_event(LockPending)`;账户/pending_hold 原本一致 |
| 3 | `order_book_direct_impl::match_against_budget_ioc` | IOC_BUDGET `active_order_completed` 用逐档 `batch_remaining==0`;**Java DirectImpl 用全局 `remainingSize==0`** | Direct 改全局;**Naive 也统一为全局**(`taker_filled==taker_size`)——见下 |
| 4 | `user_profile::cross_margin_base_allocation` | `allocated - upnl` 用普通减法,违反本文件"整数运算走 `*_exact`"红线(仅喂 CROSS 破产价估算,溢出需近 i64 上限) | 改 `sub_exact` |
| 5 | `loan_command_dispatcher` R2 后处理 | loan 强平/接管后未刷新扫描器索引(Java `onIsolatedLoanClosed`/`syncCrossExposure`);R1 的 `reconcile_loan_indices` 不覆盖 R2 路径 | 后处理末尾补 `on_isolated_loan_closed`/`sync_cross_exposure`;原本 over-trigger-safe、快照自愈 |

**#3 顺带发现 Java 自身 Direct≠Naive**:Java `OrderBookDirectImpl` budget 用**全局** `remainingSize==0`,`OrdersBucketNaive.match` 用**逐桶** `volumeToCollect==0`——同一字段两 impl 本就不同。全局是**语义正确值**(taker 未成交部分会被 REJECT、不算 completed),逐桶是 Java Naive 的 quirk。Rust 把 **Direct 与 Naive 都统一为全局**:既对齐 production 的 Java Direct,又保住 `orderbook_diff`/`*_matches_naive` 的 Direct≡Naive 参考不变式(Rust Naive 是测试参考,不单独对 Java Naive 对拍,故不镜像该 quirk)。

**事件类型完整性审计**:`FundEventType` **27/27 全移植**(DEPOSIT…INTERNAL_TRANSFER,1-50 逐一对齐);`MatcherEventType` Rust 4 个(Trade/Reject/Reduce/BinaryEvent),Java 另 6 个(IF/ADL/FUNDING/RESET_FEE/LOAN_REPRICE/INTERNAL_TRANSFER `_EVENT`)在 Java 里也**不经 R2 按事件类型分发**,而由各两步处理器**按命令类型**处理——Rust 用 `TwoStepCommandProcessor` trait 按命令类型路由,等价,那 6 类从不产出/分发,**非遗漏**。

> 全量测试:lib 986 / conformance 1 / e2e 36 / integration 323 / base_parity 78 / diff 9,0 警告。

### 7.5 守恒 proptest 抓到 ADL 摊派凭空造钱(2026-09-18,Rust+Java 同步修在 `normalizeCmdPositionSize` null 分支)

`conservation_holds_under_random_stream_with_liquidation`(防线②)偶发失败,shrink 出 11 条最小反例:同 tick 多重清算级联下,`uid4` 先作为 `uid1` 的 ADL@92 盈利 counterparty 被消耗,随后轮到 `uid4` 自己的 ADL@94——`cmd.size`(=`flow.size`,级联生成时固定、已 stale)仍按原量去杠杆 counterparty(`uid3`@94),但 `uid4` 的 origin 仓位已没了。`close_current_position_futures` 对 origin 只平 `min(size, open_volume)`(没了则一点不平)、超出量丢弃,而 counterparty 已按 full `cmd.size` 平仓 → 差额 ×(94−mark) **凭空造 12 QUOTE**。

- **根因(定位到位)**:两侧 R1 都有 `normalizeCmdPositionSize`/`normalize_cmd_position_size`=`cmd.size = min(cmd.size, position.openVolume)`,对 FORCE/IF/ADL 都调——**但 `position==null`(origin 被前序清算完全消耗)分支两侧本都 `return SUCCESS 不夹`**,stale `cmd.size` 直接进 ADL:collect 按它选满对手方、apply 平掉对手方,而 origin 已空平不掉 → 凭空造钱。Rust 同步 FIFO 排空(`run_liquidation_cascade`)会把 `uid1` 的 ADL@92 完全 apply、**消耗光 `uid4`**,再跑 `uid4` 自己的 stale ADL@94 → 撞上 null 分支;Java 异步 disruptor 顺序下 `uid4` 的 ADL 执行时 origin 尚未空,**走不到 null 分支**。
- **Rust 修复(对齐 Java 放置)**:在 `RiskEngine::normalize_cmd_position_size` 的 `position==None` 分支补 `cmd.size = 0`——**修在 Java `normalizeCmdPositionSize` 的直接对应体里、三类清算命令统一覆盖**,而非在 ADL processor 加特例(ADL processor 保持与 Java `collectInput` 一样无夹位)。唯一 delta 就是这条 null 分支(§6)。验证:minimal repro delta 0 + 3000 例 proptest + 全量绿。
- **Java 实测:不复现,但机制不是"缺陷差异"**。回归测试 `ITExchangeCoreADL#adlOriginConsumedMidCascadeConservation`(单分片,逐字节复刻 11 命令;mark92 前四仓与 Rust 完全一致;**关掉周期兜底扫、纯 on-lane 亦 PASS**,证明与周期扫无关)。两侧 R1 夹位逐字节相同,只是同步 FIFO(Rust) vs 异步 disruptor(Java) 的顺序决定了会不会把 origin 消耗光后再跑其 ADL、从而触达那条 null 分支。终态两侧逐字段一致。
- **教训**:①跨引擎"同源 bug"结论必须**实测**证,别凭代码路径推断(本次"Java 无夹位/同源同 bug"两个先期判断都被推翻——Java 其实有 R1 夹位,只是 null 分支不触达);②改动放在与 Java 对应的同一函数/同一层,别加特例。该 Java 测试留作守恒护栏 + Rust 修复后终态对照;当前无黄金向量命中此场景,Rust 修复不破坏 ③。

### 7.6 match event 进 ③(2026-09-18)抓到 Java MOVE 路径不累计 `filledNotional`(Rust 更正确)

把 `SimpleEventsProcessor` 的执行报告接进 ③(`#!match=on`)后,新向量 `spot_cancel_reduce_move` 立刻抓到分歧:一个 ASK 挂单被 `MOVE` 下移**穿越对手挂单成交** 5 手后再被 `CANCEL`,CANCEL 报告的 `cumulative_quote_qty`——**Java=0、Rust=450**(两侧 `cumulative_qty` 都=5)。

- **根因(实测定位)**:Java `OrderBookEventsHelper.sendReduceEvent` 取 `order.getFilledNotional()`,而 Java 的 MOVE 撮合路径**只累计 mover order 的 `filled`、不累计 `filledNotional`**(留 0);Rust 两者都累计,自洽。对照向量 `spot_cancel_after_fill`(正常 maker 部分成交后 CANCEL)两侧**一致**(`cumQty=4 cumQ=400`),证明只有 MOVE 路径有此 quirk,普通成交路径 `makerOrder.filledNotional += ...` 正常。
- **定性**:Java 报告层 quirk,Rust 更正确;`cumulative_qty`/账户/仓位/资金全部正确,仅 `cumulative_quote_qty` 在"MOVE 成交→reduce/cancel 报告"这一狭窄链路上不一致。
- **修复(已落地)**:Java `OrderBookDirectImpl.moveOrder` + `OrderBookNaiveImpl.moveOrder` 在 `order.filled = filled` 后补 `order.filledNotional = matchResult[1]`(与 `placeOrder` 一致)。验证:①全 85 向量重生成**仅 `spot_cancel_reduce_move` 一个 golden 变**(cumQ 0→450),其余零漂移;②`OrderBook*Test`/`*EventsProcessor*Test` + **全量 Java 套件绿**;③`filledNotional` 读者仅报告/快照/equals(不进风控结算),影响面吻合。`spot_cancel_reduce_move`(MOVE 成交后 CANCEL)现两侧一致,留作回归护栏。

### 7.7 交易资金路径全量深审(2026-09-19):三路径逐行对比,零未记录资金分歧

针对"期货/现货交易资金是否出问题"的专项深挖:三路径**逐行**对比 Java↔Rust(不看测试绿灯,直接读钱的算术与守卫)。

- **现货撮合**(`place_exchange_order`/`handle_matcher_events_exchange_{sell,buy}`/`handle_matcher_reject_reduce_event_exchange` ↔ Java `RiskEngine.placeExchangeOrder`/`handleMatcherEvents*`):下单锁(BID reserve 价 budget/limit 分支、ASK 锁 base)、撤单/减单/IOC-FOK 余量解锁(无泄漏/无双放)、taker-maker 费拆分(fee-pool 从重算均价一次性算的刻意 dust-sink 也镜像了)、`IOC_BUDGET`/`FOK_BUDGET`、reserve 超额退款(`bidder_hold_price`)——**全部逐行等价**。
- **期货开仓保证金**(`calculate_init_margin`/`is_valid_leverage`/`calculate_maintenance_margin`/`can_place_margin_order`/`cross_margin_base_allocation`/`calculate_cross_available` ↔ Java 同名):init margin 的"默认档截断、比例档 ceil"quirk、杠杆档严格 floor(`range(..key)`≡`headMap`)、维持保证金分段累加、NSF 的 cross-free-margin 逐仓按各自 symbol scale 求和、cross 分配 `sub_exact` 红线(§7.4#4)、多笔累计/flip——**全部逐行等价**。
- **期货平仓 PnL**(`close_current_position_futures`/`settle_margin_position_event`/退 extra_margin/removePositionRecord/delivery `settlePnl` ↔ Java 同名):PnL 方向符号(LONG+1/SHORT−1)、平仓费 taker-maker 不串桶、退保证金/退 profit 的 scale、partial 截断无 dust、flip 拆分序、HEDGE 双腿键(`±symbol`)隔离、delivery 无条件结算——**全部逐行等价**。

**唯一真实行为分歧**=`NO_RISK_PROCESSING` 短路模式 Rust 刻意不移植(见 §6,Rust 更严不造钱)。结论:交易资金路径是忠实移植,无可造钱/丢钱/错转的未记录分歧。(注:此前记为"PLACE_ORDER LockPending Rust 不发"已更正——`risk_engine.rs:105` 实发,§7.8 已纳入逐事件对拍。)

### 7.8 逐事件对拍扩面(2026-09-19):OPEN/CLOSE_POSITION + spot Locked/Unlocked 进 ③,修 3 处 spot 发射缺口

把交易内生命周期/锁事件也纳入 ③ 逐事件对拍(`fe_allowed` 加 `OPEN_POSITION`/`CLOSE_POSITION`/`LOCKED`/`UNLOCKED`,两侧白名单同步)。

- **OPEN/CLOSE_POSITION**:代码深审确认两侧发射规则逐行相同(per-fill per-side,guard `size_to_open>0`/`closed_size>0`),Rust 确定性正确;启用后暴露的"多一条"纯是 Java exporter **双发+`processed` 竞态重复捕获**(见 §10),非引擎分歧。
- **spot `Locked`/`Unlocked`**:逐事件对拍抓到 3 处 Rust 与 Java 的**发射**差异(均金额中性,只动事件不动账户算术),已修 `risk_engine.rs`:
  1. **GAP A**:`handle_matcher_events_exchange_sell` maker 超额退款(`quote_refund>0`)漏发 `Unlocked`(Java `RiskEngine.java:1180`)→ 补发。
  2. **GAP B**:`handle_matcher_events_exchange_buy` taker 超额退款(`quote_refund>0`)漏发 `Unlocked`(Java `:1324`)→ 补发。
  3. **多发**:`handle_matcher_reject_reduce_event_exchange` 的 `Unlocked` 缺 `release>0` 守卫(Java `:1121`),IOC_BUDGET 部分成交余量(`release==0`)时 Rust 多发一条 → 加守卫。
- 修后 `it_spot_futures_mixed` 两个 spot fill 事件序列测试按对齐后行为更新(补 Unlocked)。
- **futures `LockPending`/`UnlockPending`**(续)+ **告警向量**:两侧再加 `LOCK_PENDING`/`UNLOCK_PENDING` 到白名单——代码深审确认 Rust 早已在 `risk_engine.rs:105`(PLACE)/`:194`(CLOSE)发 LockPending、`:1598`/`:1655` 发 UnlockPending,与 Java 逐值平价,**无需改生产代码**;199 LOCK_PENDING + 194 UNLOCK_PENDING 现逐事件对拍。补 3 个告警向量 `margin_alert_isolated`/`margin_alert_cross`/`loan_margin_call`(单次 `MARK_AT` 触发,各恰一条告警)。
- 至此 22 类白名单全部有向量覆盖。

### 7.9 逐事件对拍收尾(2026-09-19):Deposit/Withdraw、异步清算 ER/ERF 进 ③;修 Java reprice 分叉

- **Deposit/Withdraw 进 ③**:深审确认两侧发射同源(仅 SUCCESS 时按 `price>0` 发 Deposit 否则 Withdraw),`fe_allowed` 加此二类。
- **异步清算/ADL 的 ER/ERF 进 ③**:给 8 个 events-on 清算/ADL 向量(`liquidation_isolated`/`adl`/`adl_multi_counterparty`/`liquidation_cross_multi_symbol`/`futures_if_takeover`/`hedge_liquidation_one_leg`/`liquidation_force_if_adl_cascade`/`futures_tiered_maintenance_liquidation`)加 `#!match=on`。验证:FORCE/ADL/IF 强平执行报告的 ER/ERF **顺序两侧确定一致**(如 FORCE 单 `ERF TRADE FILLED oid=<liq id>`);共 14 个 `#!match=on` 向量。exporter 捕获竞态由隔离生成 + 自愈循环规避。
- **修 Java reprice 分叉(§5 raft 重启 loan 分叉根因)**:`GroupingProcessor` 让 `REPRICE_LOAN_RATES` **独占 group**(组首+组尾各断一次边界,`repriceExclusiveGroup`),保证其 R2 利率写在下条 loan 命令 R1 读前冲完,不再随 live/replay 分组漂移而分叉。**Java 引擎 bug,Rust 顺序管线天然正确**;顺带关闭潜在 Java-Rust 平价差(Java 现也恒读 post-reprice)。Java loan ITs(17)+ ConservationFuzz(8)绿。见 [[reprice-r2-r1-ordering-hazard]]。
- **补齐全部 27 类 FundEventType + 命令覆盖 + ER/ERF fee 字段**(遗漏审计后):`fe_allowed`/Java `ALLOWED` 加最后 3 类 `Transfer`/`LoanCollateralChange`/`ResetFee` → **27/27 全类逐事件对拍**(1472 TRANSFER、314 DEPOSIT 等)。新增 DSL verb(两侧)+ 向量补命令覆盖:`WITHDRAW`(`withdraw_deposit` 负向 BAL)、`RESET_FEE`(`reset_fee` 扫费)、`CLOSE`(`futures_close_position`)、`LEVERAGE`(`leverage_adjust`)、`REPRICE`、`LOAN_ADD_COLLATERAL`/`LOAN_RELEASE_COLLATERAL`(`loan_isolated_collateral`)、`POOL_WITHDRAW`/`IF_WITHDRAW`/`LIF_WITHDRAW`(`pool_if_withdraw`)。ER/ERF 行加 `commAsset`/`feeAsset`(fee 币种路由,money 相关)逐字段对拍。
- 最终全绿:lib **993** / conformance **95 向量**(14 个 `#!match=on`,27/27 FundEventType) / e2e 36 / integration 357 / base_parity 78 / diff 9。

### 7.10 新一轮全对比(2026-09-19):报表/校验/两步 apply 三层,修 2 处真分歧

三组只读子代理逐方法对比报表/查询层、命令校验+result code、两步处理器 apply。**校验层与两步 apply 基本零分歧**(loan 全家、funding/ADL/IF/settle/reprice apply 全对齐)。修 2 处真分歧:
- **`insurance_fund` 报表缩放**(`reports.rs:query_insurance_fund`):`available`/`reserved`/`position_value` 原返回 raw product-scale,Rust 自己的 `total_balance` 与 Java 都缩放到 currency-scale → 内部不一致。已改为经 `size_price_to_currency` 缩放。纯外部报表(无消费者/测试/不进 ③/不影响守恒),但外部监控数值现正确。
- **cross-loan LIF-takeover 事件快照时序**(`loan_command_dispatcher.rs` taken_over 分支):原在 `close_and_recycle` **前**发 `LOAN_LIQUIDATED`(principal/interest 非零、LTV 含被吸收 loan);Java 在**后**发、`snapPrincipal=snapInterest=0`、LTV 排除。已改为**先 close 再 push(0,0)**(对齐 Java `LoanCommandDispatcher.java:819-856`)。资金/守恒本就一致,仅 3 个事件字段;非资金。
- **修 Java `SETTLE_FUNDINGFEES size<=0` 覆写 bug**:`RiskEngine.preProcessCommand` 原无条件把结果覆写成 `VALID_FOR_MATCHING_ENGINE`、掩盖 collectInput 设的 `RISK_INVALID_AMOUNT`;已改为仅未设错误码时才放行 → 两侧现一致返回 `RISK_INVALID_AMOUNT`(funding/perp ITs 绿)。
- **刻意/记录(不改)**:`MARKPRICE_ADJUSTMENT price<=0`(Rust 更严拒绝)、`add_currency` 重复(skip vs overwrite)——入 §6。`state_hash` 不互比、分片聚合、`symbol_to_users` lazy 均已在 §6。

---

## 8. 命令流 DSL 参考

`.stream` 每行一条:`VERB key=value key=value …`;`#` 开头为注释;首部 `#!events=off` 表示该向量只对拍 result+state(仅随机 fuzz 清算向量用);`#!match=on` 额外对拍撮合执行报告 `MATCH` 段(确定性同步向量 + 确定性异步清算/ADL 向量,见 §4.3/§6/§7.9)。两侧解释器(`tests/conformance.rs` / `ConformanceExporter.java`)必须同步支持每个 verb。

| VERB | 字段 | 语义 | 发 R 行? |
|------|------|------|:--:|
| `CUR` | `id digit` | 注册货币,`scale_k = 10^digit` | 否(setup) |
| `SYM_SPOT` | `id base quote baseScale quoteScale taker maker` +可选 `initialLtv liqLtv marginCallLtv maxAmount maxTermDays` | 现货对(带 `initialLtv` 时启用借贷) | 否(setup) |
| `SYM_FUT` | `id kind(PERP/DELIVERY) base quote baseScale quoteScale taker maker feeScale initMargin initMarginScaleK` | 期货 symbol(MM/杠杆档表两侧固定) | 否(setup) |
| `MARK` | `sym price` | 设标记价(不触发扫描) | 否(setup) |
| `MARK_AT` | `sym price ts` | 带时间戳标记价(Rust 触发定向扫) | 否(trigger) |
| `ENABLE_LIQ` | — | 开清算引擎 leader 门 | 否 |
| `USER` | `uid` | 开户 | 是 |
| `BAL` | `uid cur amount txid` | 充值/提现 | 是 |
| `PLACE` | `oid uid sym price size action(BID/ASK) type(GTC/IOC/FOK/FOK_BUDGET/IOC_BUDGET) reserve` | 现货下单(BUDGET:`price`=预算) | 是 |
| `PLACE_FUT` | `oid uid sym price size action type leverage margin(ISOLATED/CROSS)` | 期货下单 | 是 |
| `CANCEL` | `oid uid sym` | 撤单 | 是 |
| `REDUCE` | `oid uid sym size` | 减单(size=减少量) | 是 |
| `MOVE` | `oid uid sym price` | 移动挂单到新价(可触发成交) | 是 |
| `SCAN` | `slice sliceCount ts` | 清算扫描(Java=triggerLiquidation 循环至 settle) | 否(trigger) |
| `IF_DEPOSIT` | `sym amount txid` | 保险基金充值 | 否(setup) |
| `SETTLE_PNL` | `sym price txid` | 交割结算 | 是 |
| `SETTLE_FUNDING` | `sym action rate rateScaleK txid` | 资金费结算 | 是 |
| `POS_MODE` | `uid hedge(0/1)` | 切换单向/双向持仓(HEDGE 向量用) | 是 |
| `POOL_DEPOSIT` | `cur amount txid` | 借贷池注资(分片本地,见 §7.3) | 是 |
| `LOAN_CREATE` | `uid sym loanId collateral principal rateMode ts txid` | isolated 开贷(锁抵押、放本金) | 是 |
| `LOAN_REPAY` | `uid loanId repay ts txid` | isolated 还款(还本息、赎抵押) | 是 |

---

## 9. 输出格式规格

`.golden`(与 Rust replay 输出逐行相等):

```
R <seq> <CODE>              # 每条发 R 行的命令的结果码(SCREAMING_SNAKE)
...
STATE
A <uid> <cur> <account>    # 非零账户,uid→cur 升序
POS <uid> <sym> <DIR> <open_volume> <open_price_sum>   # open_volume≠0,DIR∈{LONG,SHORT}
FEE <cur> <amount>         # 非零费用池,cur 升序
EVENTS                     # 若非 #!events=off
FE <TYPE> uid=<uid> cur=<cur> free=<free> locked=<locked>   # 结算类白名单,整体排序后逐行
MATCH                      # 若 #!match=on
ER <execType> <orderStatus> uid= oid= side= maker= px= lastQty= lastPx= cumQty= cumQ= comm=   # 现货执行报告,按发出顺序
ERF <execType> <orderStatus> uid= oid= side= maker= pos= cp= px= lastQty= lastPx= cumQty= cumQ= avgPx= fee=   # 期货执行报告,按发出顺序
```

- `CODE`:Rust `CommandResultCode` 的 CamelCase Debug 名自动转 SCREAMING_SNAKE,与 Java `enum.name()` 对齐。
- `EVENTS` 段是**排序后的多重集**(顺序无关),只含 §6 白名单事件。
- `MATCH` 段**按发出顺序**(不排序:同步向量两侧发序确定,顺序本身是被验证的语义)。`execType`/`orderStatus`/`side`/`pos` 用与 Java `enum.name()` 对齐的 SCREAMING_SNAKE(`pos`=`ONEWAY`/`HEDGE`);剔除 seq 派生的 `tid`/`eid`(见 §6)。

---

## 10. 工作流与 CI 门禁

```bash
# 0)(可选)差分模糊:重生成随机向量
cargo run --example gen_conformance_fuzz

# 1) Java 当 oracle 生成/更新黄金向量(在 exchange-core 模块)
mvn -q -Dtest=ConformanceExporter -DfailIfNoTests=false test

# 2) Rust replay 同一批 .stream,逐行断言 == .golden
cargo test --test conformance
```

> **⚠ events-on 期货向量的 golden 必须逐向量隔离生成**(`-Dconformance.vectors.dir=<临时目录,只放一个 .stream>`)。Java exporter 全量跑一次会因**双发(R2 `-seq` + main `+seq`)+ `processed` 去重竞态**间歇性**重复捕获** `OPEN_POSITION` 等生命周期事件(同 [[conformance-exporter-async-flaky]]),污染多重集计数。Rust 单发确定,replay 侧(步骤 2)恒定,故只要**一次**拿到干净 golden 入库,CI 就稳定。隔离批量重生成脚本见 `scratchpad/regen_futures_isolated.sh` 思路(每向量单独 `conformance.vectors.dir`)。

- **加一个场景** = 写一个 `.stream`(现货/期货/清算/ADL 皆可)→ Java 导出 golden → Rust 对拍。DSL 缺 verb 就两侧解释器各加一条分支。
- **引擎行为有意变更** → 同步更新两侧实现 + 重新生成 golden + **评审 golden diff**。
- **门禁建议**:两侧都入 CI。Rust CI 跑 `cargo test`(五个 target:lib / e2e / integration / orderbook_diff / conformance);Java CI 跑 `ConformanceExporter` 生成的 golden 与入库版本 diff(golden 漂移即 Java 行为变了)。

---

## 11. 路线图 / 未做

**已完成(条目留档;标 ✅ 者为已落地):**

- ✅ **①b 组件对拍**:`OrderBookBaseTest`(78)/`OrdersBucketNaiveTest`(6)/`SimpleEventsProcessorTest`(7) 已全部 `java_` 前缀逐条对拍,外加 8 个数学敏感单测。
- ✅ **清算/ADL 事件级对拍**:确定性(SCAN 驱动)向量已 events-on——修了 Java `ConformanceExporter` 的异步捕获(`feAccum` synchronizedList + 稳定判据),`adl`/`liquidation_isolated`/`loan_liquidation_isolated` 均事件级对拍。
- ✅ **差分模糊扩面**:`gen_conformance_fuzz` 已含现货(`gen_vector`)/期货(`gen_futures_vector`)/清算(`gen_liquidation_vector`)三条随机流 + 离线 live-diff 编排(`conformance_live_diff.sh`)。
- ✅ **③ 向量扩面**:现货/期货/交割/清算/ADL/funding/loan/cross/hedge/if_takeover/loan_liquidation/cross_loan 均入库对拍。
- ✅ **match event 进 ③**(2026-09-18):`SimpleEventsProcessor` 接进对拍框架,fund event + match event 从同一出口流出;同步向量 opt-in `#!match=on` 逐字段对拍 `SpotExecutionReport`/`FuturesExecutionReport`。6 个向量:`spot_match_events`(NEW/TRADE/REJECT)、`fut_match_events`(NEW/TRADE/posSide/cp/avgPx)、`spot_cancel_reduce_move`(REDUCE/MOVE→TRADE/CANCEL)、`spot_cancel_after_fill`、`spot_multi_maker_match`(多笔 TRADE 顺序)、`fut_cancel_reduce_match`(期货 REDUCE/CANCEL)。新增 `CANCEL`/`REDUCE`/`MOVE`(现货)DSL verb。**抓到并修复** Java MOVE `filledNotional` quirk(§7.6,全量 Java 套件绿)。异步清算向量刻意不开(见 §6/[[conformance-exporter-async-flaky]])。

**刻意不做 / 需独立决策:**

- **真·live 同进程双引擎比对(JNI 或双跑)**:比离线向量重得多;当前以"生成向量 + 入库 golden"离线差分替代,已够用。除非要 CI 常态实时比对,否则不投入。
- **随机 fuzz 清算流的事件级对拍**:随机流的异步 settle 时序无法保证跨引擎确定,故 `gen_liquidation_vector` 刻意 `#!events=off` 只对拍 STATE(确定性 SCAN 向量已 events-on)。
- **Java 侧两个问题**(§7.2):参考引擎的架构特性/功能缺口(批处理时序 / 普通 FOK),是否在 Java 侧修是独立决策;Rust 已正确、conformance 已规避。

**可继续(开放式,非阻塞):**

- Java `tests/unit` 里非数学的行为类单测逐条对拍;更多 loan 子场景向量(注:LIF 注资向量曾试,对当前对拍口径 inert 已移除)。

---

## 附:测试布局速查

| 位置 | 内容 | 跑 |
|------|------|----|
| `src/**` 内 `#[cfg(test)]` | 与生产代码同文件的单元测试 | `cargo test --lib` |
| `tests/e2e/` | 引擎级 e2e + 守恒 proptest(防线②) | `cargo test --test e2e` |
| `tests/integration/` | Java IT 对拍(防线①) | `cargo test --test integration` |
| `tests/conformance.rs` + `tests/conformance_vectors/` | 黄金向量对拍(防线③/③b) | `cargo test --test conformance` |
| `tests/orderbook_diff.rs` | Direct vs Naive 订单簿差分 | `cargo test --test orderbook_diff` |
| `examples/gen_conformance_fuzz.rs` | 差分模糊向量生成器 | `cargo run --example gen_conformance_fuzz` |
| `exchange-core/.../conformance/ConformanceExporter.java` | Java oracle 导出器 | `mvn -Dtest=ConformanceExporter test` |
