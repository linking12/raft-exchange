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

### 1. 三类"改变偿付的事件" → on-lane targeted 检查

偿付状况只被这几类事件主动改变,各自在对应命令 apply 里挂检查:

| 事件 | 触发点(命令 apply,R1,每分片) | 查谁(targeted) |
|---|---|---|
| 价格波动 | `MARKPRICE_ADJUSTMENT`(更新 markPrice[S] 后) | 本分片持有 S 仓位的用户 |
| 永续资金费 | `SETTLE_FUNDINGFEES` | 被扣费的用户 |
| 借贷利率/利息 | `REPRICE_LOAN_RATES` | 相关 loan 用户 |

开/平仓、存/取有保证金前置校验、造不出破产仓,不触发。

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

### 3. 去抖(per symbol,防高频价格更新打满热路径)

- 每 symbol 记 `lastCheckedPrice` / `lastCheckedNs`;`MARKPRICE_ADJUSTMENT` 触发检查前,若**价格变动 < 阈值** 或 **距上次检查 < K ms**,则跳过本次检查(价格仍照常更新)。
- 状态**进程级、leader-local**(不进快照,换届重置无碍)。
- 目的:把某 symbol 的检查频率封顶,避免"热门 symbol × 超高频价格更新"叠加。

### 4. 换届冷启动一次性全扫

- 新 leader 上台时,做**一次**全量扫(所有用户),兜"某个在无近期事件覆盖下已破产的仓"。一次性,非周期。
- 通过现有 leader 转换 hook 触发(server 侧已有 `isLeader`)。

### 5. (可选)慢速防御性兜底

- 一个**很慢**(30–60s)的切片轮询(SCAN_TICK + 游标,每 tick N 个用户),纯防御 `symbol→用户索引` bug / 边角事件漏(如空闲 loan 的利息缓慢越线)。
- 信得过索引 + 测试可省;保留则用切片(见附录 A)避免单次全扫顶住。

### 6. 定时器职责收缩

- **删除**每 2s 全量扫的重定时任务。
- 保留一个**轻定时器**只发:周期性 `REPRICE_LOAN_RATES` 心跳(利率每 1h 重定价,纯时间驱动)+(可选)慢兜底 tick。**不再扫任何用户**。

### 7. 下游不动

检查产出的 `FORCE_LIQUIDATION` 照走现有:`LiquidationCmdPublisher` → `raftNode.apply` → 落地 → `MatchingEngineRouter` 订单簿 IOC 撮合 → 残余 → IF → ADL。**这套一行不改。**

## 无竞态论证

检查跑在 RiskEngine 单写者线程上(命令 apply 内),读一致的 committed 状态,**不跨线程共享 UserProfile**;独立扫描线程被移除。因此彻底无 R1/R2-vs-scanner 的撕裂读。

## 确定性论证(Raft RSM)

- 三类触发都是 log 里确定位置的命令 apply;检查读一致 committed 状态;
- 检查对**复制状态只读**(不改 position/account;改动发生在 FORCE 落地时),产出 FORCE 经 **leader 门控** publish → 进 log → 所有节点确定性 apply;
- `symbol→用户索引` 在开/平仓(确定性 apply)时各节点同步维护;快照恢复时重建;
- 去抖/冷启动扫描是 leader-local 决策(不影响复制状态);
- 建议**检查本身也 leader-gate**(复用现有 `isLeader` supplier),follower 不空跑,省 CPU。

## 热路径成本(量化)

`MARKPRICE_ADJUSTMENT` 触发的检查 = O(持有 S 的用户 / 分片数) × 便宜检查,+ 去抖封顶:
- 例:某 symbol 全网 2 万持有者、8 分片 → 每分片 2500 人;便宜检查 ~几十 ns/人 → **~75µs / 分片 / 次**;BP 条件触发(极少);
- 去抖到每 100ms/symbol → 每 100ms 停 ~75µs = **~0.075% 占空比**,可忽略;各分片并行。
- 对比旧"一次扫十万全量":targeted + 分片 + 去抖后量级差 2–3 个数量级。

## 涉及文件

- `exchange-core/.../processors/liquidation/LiquidationEngine.java`:移除 `SimpleScheduledService` 扫描循环;保留 `checkLiquidationIsolated/Cross`/`calculateBankruptcyPrice` 决策逻辑,改为对外提供 `checkUsersOnSymbol(int symbol)` / `checkUser(long uid)` / `fullScan()`(冷启动/兜底);发 FORCE 仍走 `getLiquidationCmdPublisher()`。
- `exchange-core/.../processors/RiskEngine.java`:`MARKPRICE_ADJUSTMENT` / `SETTLE_FUNDINGFEES` / `REPRICE_LOAN_RATES` 的 apply 里调 targeted 检查;开/平仓维护 `symbol→用户索引`;快照恢复重建索引;冷启动/兜底触发。
- `symbol→用户索引` + 去抖状态:RiskEngine 内新增字段(per 分片,进程级)。
- 定时器/心跳:server 侧或 exchange-core 侧发 `REPRICE_LOAN_RATES` 的轻定时器保留;删原 2s 扫描调度。
- `LoanLiquidationEngine`:同样从"scanner off-lane"改为被 REPRICE/价格事件驱动的 targeted loan 检查(同套思路)。

## 测试

- **现有守恒/failover/liquidation 全绿**(行为回归底线):`ITConservationFuzz`、`ITLoanConservation`、`PersistenceTests`、`LiquidationEngine*Test`、`LoanLiquidationEngineTest`、`ITLiquidationIntegration`。
- 新增:
  - targeted 触发正确性:价格/资金费/reprice apply 后,只有受影响用户被检查、破产者发 FORCE;
  - `symbol→用户索引` 维护(开/平仓增删)+ 快照恢复重建一致;
  - 去抖(变动小/过密不重复检查,变动大即时检查);
  - 冷启动全扫;
  - **无竞态**:断言无独立线程读 UserProfile(结构性);
  - FORCE 落地后走现有 IF/ADL 不变。
- **live-cluster failover** 验证一条(换届冷启动 + 索引重建 + 强平不双发/不漏)。

## 兼容性 / 迁移

- 不改 wire / 命令格式;`symbol→用户索引` 是派生态,快照恢复重建,**不改快照字节格式**。
- 去抖/冷启动状态进程级,换届重置无碍。
- FORCE/IF/ADL 下游与订单簿撮合不动。

## 风险 / 待定

- **索引正确性**是 targeted 的命脉:开/平仓维护漏一处 → 漏检该用户的价格触发。缓解:慢兜底(可选)+ 索引维护单测 + 快照重建对拍。
- **极端热路径**(超热门 symbol × 超高频价格更新):去抖封顶;上线前用 perf 测实测每次触发停顿与 p99。
- **空闲 loan 利息缓慢越线**:reprice(1h)触发 + 可选慢兜底覆盖;若嫌慢,缩短 loan 兜底周期。
- **cross 账户级**:价格触发到持有 S 的用户后,须查其整个 cross 账户偿付(不止 S 仓)。

## 附录 A:慢兜底切片(仅当保留兜底时)

- 每分片一份 `uid = keySet().toArray()` 快照 + 游标;每 tick 扫 `[cursor, cursor+N)`,游标 +N;扫到底归零 + 换快照;
- N 与 tick 频率决定兜底 sweep 周期(30–60s 足矣);
- 便宜检查为主,BP 条件触发。
