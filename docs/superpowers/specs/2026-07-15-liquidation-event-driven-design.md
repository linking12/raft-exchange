# 强平引擎:事件驱动 on-lane targeted 重构 设计

**日期**: 2026-07-15
**分支**: `liquidation-event-driven`
**状态**: 设计待 review

## 背景与问题

现状:`LiquidationEngine`(每个 RiskEngine 分片一个)是一个 `SimpleScheduledService`,**独立线程每 2s** 遍历本分片所有 `UserProfile`,判破产则经 `LiquidationCmdPublisher` publish `FORCE_LIQUIDATION`。两个问题:

1. **数据竞态(P0 正确性)**:扫描线程直接读活的、非线程安全的 `UserProfileService` 的 `LongObjectHashMap<UserProfile>`,而 R1/R2 disruptor 写者线程在并发改它 → **撕裂读**(读到半更新的账户/持仓)→ 误强平/漏强平;并发插新用户还可能破坏迭代。违背了引擎"单写者"原则。且 `FORCE_LIQUIDATION` 落地时**不复核偿付**(RiskEngine:502),误判会真成交。
2. **固定 2s 全量轮询:对高杠杆偏慢 + 浪费**。爆仓由价格驱动(亚秒可发生);2s 轮询既可能漏掉快速爆仓,又在无变化时空扫全量。

本项目有高杠杆产品,需要亚秒级、有针对性的强平检测。

## 目标

- **根除扫描数据竞态**。
- 高杠杆下**及时(价格即时)、有针对性**的强平检测。
- 保持 **Raft 确定性**。
- **不堵撮合热路径**。
- 复杂度可控。

## 设计取舍(为什么选"事件驱动 on-lane targeted")

评估过的方案:
- **on-lane 全量扫**:无竞态、清晰,但每次 O(全量) 顶住撮合 → 堵。
- **off-lane + seqlock**:不占热路径,但每处多字段写要包 version(易漏,手工纪律)。
- **off-lane 事件驱动(dirty 队列 + 消费者 + advisory + 复核)**:及时但机器多、复杂。
- **on-lane 切片轮询(SCAN_TICK + 游标)**:无竞态、拥堵可摊薄,但**盲扫**、检测延迟 = sweep 周期,高杠杆不够快。

**收敛:on-lane、事件驱动、targeted。** 强平检查跑在"真正改变偿付的命令"的 apply 里(价格/资金费/reprice),且**只查受该事件影响的用户**(targeted,靠 symbol→用户索引)。
- **on-lane** → 读一致状态、单写者线程 → **无竞态**(无需 advisory/复核/seqlock);
- **targeted** → 只查受影响的几千持有者(还按分片摊)→ **热路径成本可忽略**,不是全量;
- **事件驱动** → 价格一动即时查、无变化不空扫 → **及时且不浪费**。

## 核心设计

### 1. 四类触发(全命令/事件驱动;无 off-lane 扫描线程)

偿付状况只被这几类事件主动改变,各自在对应命令 apply 里挂检查;外加一条周期性兜底命令。检查/扫描全部在 on-lane(disruptor 单写者线程)进行。**注意:仍保留一个轻定时器**,但它只 publish 命令(`REPRICE_LOAN_RATES` 心跳 + `LIQUIDATION_BACKSTOP_TICK`)、**不读任何用户状态、不做扫描**——竞态消失靠"扫描搬到 on-lane",不是靠"没有定时器"。该定时器职责等价于今天 scanner 顺带发 reprice 的那部分,非新增负担(详见 §5)。移除的是**旧的 off-lane 扫描线程**(遍历用户读活状态那根)。

| 触发 | 命令 apply(R1,每分片) | 查谁 |
|---|---|---|
| 价格波动 | `MARKPRICE_ADJUSTMENT`(更新 markPrice[S] 后) | 本分片持有 S 仓位的用户(targeted) |
| 永续资金费 | `SETTLE_FUNDINGFEES` | 被扣费的用户(targeted) |
| 借贷利率/利息 | `REPRICE_LOAN_RATES` | 相关 loan 用户(targeted) |
| **兜底** | `LIQUIDATION_BACKSTOP_TICK`(轻定时器周期性发,慢) | 切片全扫(§4 + 附录 A)+ 卡单恢复(§7) |

开/平仓、存/取有保证金前置校验、造不出破产仓,不触发。**每次 `MARKPRICE_ADJUSTMENT` 都触发一次 targeted 检查,不做去抖**(targeted + 分片已足够便宜;真打满再局部加去抖,不影响架构)。

检查逻辑(复用现有 `checkLiquidationIsolated/Cross` / loan LTV 判定):
```
对每个受影响用户:
  ① 便宜偿付检查(equity < 维持保证金 / LTV 越线?)    ← 不算 BP
     否(健康,绝大多数)→ 跳过
     是 → ② 算破产价 BP → publish FORCE(经现有 LiquidationCmdPublisher 桥,限价=BP)
```
BP **条件计算**:只对判定要强平的少数用户算。cross 全仓:某 symbol 价变触发到持有它的用户后,查其**账户级**偿付;isolated 逐仓只看该仓。

### 2. `symbol → 持仓用户` 索引(每分片)

- 结构:`IntObjectHashMap<MutableLongSet>`(symbol → uid 集合)或等价,per RiskEngine 分片。
- 维护:RiskEngine 在**持仓从 0→非 0(开)时 add(symbol, uid)、从非 0→0(平)时 remove**。挂在现有开/平仓的仓位状态变更处。
- 用途:`MARKPRICE_ADJUSTMENT` 触发时 O(持有 S 的用户) 定位,而非 O(全分片用户)。
- **不进快照**(派生态):快照恢复时,遍历恢复出的各 UserProfile 持仓**重建索引**;不改快照字节格式。

### 3. 换届冷启动一次性全扫

- 新 leader 上台时,做**一次**全量扫(所有用户),兜"某个在无近期事件覆盖下已破产的仓"。一次性,非周期。
- 通过现有 leader 转换 hook 触发(server 侧已有 `isLeader`)。

### 4. 兜底命令 `LIQUIDATION_BACKSTOP_TICK`(慢,周期性)

- 轻定时器周期性(**慢**,如 30–60s)发一条 `LIQUIDATION_BACKSTOP_TICK`,on-lane apply。**apply 里做两件事**:
  - (a) **切片全扫**(每分片 uid 快照 + 游标,每 tick N 个,见附录 A):接事件漏掉的破产(funding/利息/`symbol→用户索引` bug / 空闲 loan 利息缓慢越线);
  - (b) **卡单恢复**(见 §8):扫 `ctx != null 且超阈值未推进` 的仓,republish 对应阶段命令。
- 慢即可(它是安全网,不是主检测);切片避免单次全扫顶住。

### 5. 定时器职责收缩

- **删除**每 2s 全量扫的 `SimpleScheduledService`(off-lane 扫描线程整个移除)。
- 保留一个**轻定时器**只发命令:`REPRICE_LOAN_RATES` 心跳(利率每 1h 重定价)+ `LIQUIDATION_BACKSTOP_TICK`(慢兜底)。**定时器不扫任何用户**。

### 6. 下游不动

检查产出的 `FORCE_LIQUIDATION` 照走现有:`LiquidationCmdPublisher` → `raftNode.apply` → 落地 → `MatchingEngineRouter` 订单簿 IOC 撮合 → 残余 → IF → ADL。**这套一行不改。**

### 7. `inFlightLiquidationCmd` / 卡单恢复的去留(本改法的连锁简化)

事件驱动 + 落地幂等 让"卡单"概念大幅退化:

| 现有机制 | 改后 | 理由 |
|---|---|---|
| `inFlightLiquidationCmd`(发出未 apply 去重) | **删** | 落地侧本就幂等:futures `nextLiquidationState` 对已 LIQUIDATING 的 ctx 丢弃 duplicate;loan force-liq 有抵押 guard。正确性不依赖它;重复窗口从 2s 缩到"发出→apply"几 ms,浪费极小 |
| 检查跳过 `ctx != null` 的仓 | **保留** | targeted 检查不能重启在流程中的仓 / 不能重复计费 |
| "FORCE 发了但丢了(ctx 仍 null)"重发 | **删**(自然重试) | 下一相关事件 / 兜底 tick 再检查该用户,ctx 仍 null、仍破产 → 再发一次;事件驱动的"再检查"就是重试 |
| "ctx 卡在某阶段(mid-flow)超时"republish | **保留但搬到 §4 兜底 tick 的 apply** | targeted 检查会跳过 ctx != null 的仓,故 mid-flow 卡单由慢兜底 tick 扫 `ctx != null 且超时` 的仓 republish IF/ADL 阶段 |

净效果:删掉 `inFlightLiquidationCmd` 与 off-lane 的 `tryRepublishStuckLiquidation`;"跳过流程中仓"留在 targeted 检查;"mid-flow 卡单 republish"搬进兜底 tick。

### 8. `LiquidationContext` 抽出 SymbolPositionRecord + leader-local(不持久化)

现状:`liquidationCtx`(强平流程状态机 LIQUIDATING→WAIT_IF→WAIT_ADL)是 `SymbolPositionRecord` 的字段,且进 snapshot/stateHash。两个问题:(a) 流程状态机塞进"仓位记录"混了 concern;(b) 为跨换届续流程而持久化。

改法:
- **抽出**:把强平流程状态从 `SymbolPositionRecord` 移到**强平引擎持有的独立结构**(`Map<positionKey, LiquidationFlow>`,记 stage + in-flight),不再是 position 字段。
- **leader-local、不持久化**:退出 snapshot 与 stateHash。
- **apply 侧不再靠 `ctx.state` 门控去重** —— 改由**"命令效果被当前仓位/抵押夹住"保正确**:
  - FORCE:R1 `normalizeCmdPositionSize` 已把 `cmd.size` 夹到当前仓位 → 重复/重发只平当前残余,**不可能超平**;
  - loan force-liq:抵押 guard(`sellAmount > 当前抵押 → reject`,现有 `duplicateApply_secondRejectedByCollateralGuard` 已测);
  - IF/ADL:接管/减仓按当前残余算,对已处理仓自然 no-op。
- **同任期升级 FORCE→IF→ADL**:由 leader-local 流程追踪器记 stage 驱动(FORCE 出残余 REJECT → 推 WAIT_IF → publish IF …),高效,不重试市价;targeted 检查因 `ctx != null` 跳过在流程中的仓。
- **换届 mid-flow 重启**:新 leader 追踪器空 → 残余仓在其眼里"无进行中流程"→ **换届冷启动扫描 / 事件把它当普通破产仓重新检测 → 发 FORCE**(size 夹到当前残余)→ 正常 FORCE→IF→ADL。无独立 retry 代码;代价仅"对残余多试一次市价"的轻微低效,正确收敛。

## 无竞态论证

检查跑在 RiskEngine 单写者线程上(命令 apply 内),读一致的 committed 状态,**不跨线程共享 UserProfile**;独立扫描线程被移除。因此彻底无 R1/R2-vs-scanner 的撕裂读。

## 确定性论证(Raft RSM)

- 三类触发都是 log 里确定位置的命令 apply;检查读一致 committed 状态;
- 检查对**复制状态只读**(不改 position/account;改动发生在 FORCE 落地时),产出 FORCE 经 **leader 门控** publish → 进 log → 所有节点确定性 apply;
- `symbol→用户索引` 在开/平仓(确定性 apply)时各节点同步维护;快照恢复时重建;
- 冷启动扫描 / 兜底切片游标 / 强平流程追踪器(§8)都是 leader-local(不影响复制状态);apply 侧正确性由"仓位夹住"保(§8),不依赖复制的 `ctx.state`;
- 建议**检查本身也 leader-gate**(复用现有 `isLeader` supplier),follower 不空跑,省 CPU。

## 热路径成本(量化)

`MARKPRICE_ADJUSTMENT` 触发的检查 = O(持有 S 的用户 / 分片数) × 便宜检查(无去抖,每次价格更新都查):
- 例:某 symbol 全网 2 万持有者、8 分片 → 每分片 2500 人;便宜检查 ~几十 ns/人 → **~75µs / 分片 / 次价格更新**;BP 条件触发(极少);
- markprice 每 100ms/symbol → 每 100ms 停 ~75µs = **~0.075% 占空比**,可忽略;各分片并行。
- 对比旧"一次扫十万全量":targeted + 分片后量级差 2–3 个数量级。若某 symbol 持有者极多 × 更新极频打满,再局部加去抖(不改架构)。

## 组件与边界(防 god-class:LiquidationEngine 退成薄编排)

不把索引/流程/判定/命令都塞进 `LiquidationEngine`。按"变化原因不同"切成单一职责小件:

| 组件 | 职责 | 说明 |
|---|---|---|
| `LiquidationEngine`(薄编排 / facade) | 触发→索引找人→评估→破产则经 flow 发 FORCE | RiskEngine 调它;只协调,重活委托,几十行 |
| `PositionSymbolIndex` | `symbol → 持仓用户`:add/remove/getUsers | 独立小类,开/平仓维护 |
| `LiquidationFlowTracker` | leader-local `Map<key, LiquidationFlow>` + FORCE→IF→ADL 升级状态机 | 原 `nextLiquidationState` 搬此;管流程不管判定 |
| `FuturesSolvencyEvaluator` | 期货 isolated/cross "是否破产 + BP" verdict | BP 数学委托 `SymbolPositionRecord.calculateBankruptcyPrice` |
| `LiquidationService`(现有) | orderId/lot 工具 + build FORCE/IF/ADL 命令工厂 | 不变 |
| `LoanLiquidationEngine`(现有) | loan 域同款编排(判定/BP) | 与 `LiquidationEngine` 平行,**共用** FlowTracker/Index/Service |
| `LiquidationBackstopCursor` | 兜底切片游标(uid 快照 + cursor) | 小工具,`backstopTick()` 用 |

边界原则:**判定 / 流程 / 目标定位 / 命令构造** 各自成件(变化原因不同);**期货 / 借贷** 各一套编排但共用底层件(不重复)。就切到这几件为止,不过度拆。

## 涉及文件

**不新建 `...CommandProcessor` 类**(强平触发不是两步处理器、也不对应单一命令);RiskEngine 各命令 handler 直接委托 `LiquidationEngine` 的方法。

- `LiquidationEngine.java`:**去掉 `SimpleScheduledService` 基类**(不再定时扫描)。保留 `checkLiquidationIsolated/Cross`/`calculateBankruptcyPrice` 决策逻辑,对外暴露 `checkPositionsOnSymbol(int symbol)` / `checkUser(long uid)` / `fullScan()` / `backstopTick()`。持有 **leader-local `LiquidationFlow` 追踪器**(§8,替代 ctx)与 **symbol→用户索引**(经 `onPositionOpened/Closed` 维护)。发 FORCE 仍走 `getLiquidationCmdPublisher()`。命名保留 `LiquidationEngine`(职责未变;不叫 Scanner —— 已非轮询)。
- `RiskEngine.java`:各 handler 加一两行委托——`MARKPRICE_ADJUSTMENT`→`checkPositionsOnSymbol(cmd.symbol)`;`SETTLE_FUNDINGFEES`→`checkUser(受影响)`;`REPRICE_LOAN_RATES`→loan 侧检查;新增 `LIQUIDATION_BACKSTOP_TICK`→`backstopTick()`;开/平仓处→`onPositionOpened/Closed(symbol,uid)`;快照恢复处→重建 symbol→用户索引。
- `SymbolPositionRecord.java`:**移除 `liquidationCtx` 字段**及其 snapshot/stateHash 参与(§8)。
- `OrderCommandType`:新增 `LIQUIDATION_BACKSTOP_TICK`;`ApiLiquidationBackstopTick`(空 payload,类比 `ApiRepriceLoanRates`)。
- 定时器/心跳:轻定时器只发 `REPRICE_LOAN_RATES`(1h)+ `LIQUIDATION_BACKSTOP_TICK`(慢);删原 2s 扫描调度。
- `LoanLiquidationEngine.java`:同样从 off-lane scanner 改为被 抵押价 / REPRICE 事件驱动的 targeted loan 检查(同套思路,同样 leader-local flow / 无竞态)。

## 测试

- **现有守恒/failover/liquidation 全绿**(行为回归底线):`ITConservationFuzz`、`ITLoanConservation`、`PersistenceTests`、`LiquidationEngine*Test`、`LoanLiquidationEngineTest`、`ITLiquidationIntegration`。
- 新增:
  - targeted 触发正确性:价格/资金费/reprice apply 后,只有受影响用户被检查、破产者发 FORCE;
  - `symbol→用户索引` 维护(开/平仓增删)+ 快照恢复重建一致(与逐仓遍历对拍);
  - 冷启动全扫 + 兜底切片游标;
  - **仓位夹住幂等**(§8):重复/重发 FORCE 不超平(size 夹到当前仓);loan 抵押 guard;
  - **mid-flow 换届重启**(核心新用例):FORCE 出残余后换届 → ctx 丢失 → 新 leader 冷启动扫描把残余当破产仓重发 FORCE → 收敛、不超平、不分叉(扩 `ITLoanFailoverSnapshot` / `LiquidationStuckRecoveryTest`);
  - **无竞态**:断言无独立线程读 UserProfile(结构性);
  - FORCE 落地后走现有 IF/ADL 不变。
- **live-cluster failover** 验证一条(换届冷启动 + 索引重建 + mid-flow 重启,强平不双发/不漏)。

## 兼容性 / 迁移

- 新增 `LIQUIDATION_BACKSTOP_TICK` 命令(附加,不动既有 wire)。
- `symbol→用户索引` 是派生态,快照恢复重建,**不改快照字节格式**。
- **快照格式确实变小一处**:`SymbolPositionRecord` 移除 `liquidationCtx` → 该记录序列化 + stateHash 变(§8)。pre-launch 无历史快照,直接改,不写版本兼容。
- 冷启动/兜底游标 + `LiquidationFlow` 追踪器进程级 leader-local,换届重置无碍(中途仓由冷启动扫描重启)。
- FORCE/IF/ADL 下游与订单簿撮合不动。

## 风险 / 待定

- **索引正确性**是 targeted 的命脉:开/平仓维护漏一处 → 漏检该用户的价格触发。缓解:慢兜底(可选)+ 索引维护单测 + 快照重建对拍。
- **极端热路径**(超热门 symbol × 超高频价格更新):无去抖 → 靠 targeting + 分片摊;上线前用 perf 实测每次触发停顿与 p99,真打满再局部加去抖(不改架构)。
- **mid-flow 换届的市价重试**:ctx leader-local 丢失 → 残余仓从 FORCE 重启,多试一次市价(§8)。正确收敛、无资金风险,仅轻微低效;换届本就罕见,可接受。
- **空闲 loan 利息缓慢越线**:reprice(1h)触发 + 可选慢兜底覆盖;若嫌慢,缩短 loan 兜底周期。
- **cross 账户级**:价格触发到持有 S 的用户后,须查其整个 cross 账户偿付(不止 S 仓)。

## 附录 A:慢兜底切片(仅当保留兜底时)

- 每分片一份 `uid = keySet().toArray()` 快照 + 游标;每 tick 扫 `[cursor, cursor+N)`,游标 +N;扫到底归零 + 换快照;
- N 与 tick 频率决定兜底 sweep 周期(30–60s 足矣);
- 便宜检查为主,BP 条件触发。
