# 现货借贷强平事件驱动化 设计文档 (Plan 2)

> 承接 Plan 1(期货强平事件驱动化,已合入)。本文只覆盖现货借贷(loan)子域。

**日期**: 2026-07-16
**分支(建议)**: `loan-event-driven`
**关联**: [Plan 1 spec](2026-07-15-liquidation-event-driven-design.md) · [Plan 1 plan](../plans/2026-07-16-liquidation-futures-event-driven.md)

---

## 1. 背景与目标

### 1.1 现状

`LoanLiquidationEngine` 目前**搭 `LiquidationEngine.checkUser` 的车**:`checkUser(userProfile, ts)` 在末尾调用 `loanLiquidationEngine.check(userProfile)`。而 `checkUser` 由 `checkPositions(cmd)` 驱动:

- `cmd.symbol >= 0`(targeted):只查 `symbolToUsers` 索引里该 symbol 的持有者;
- `cmd.symbol < 0`(`LIQUIDATION_SCAN`):全量整扫兜底。

**关键缺口**:`symbolToUsers` 是**期货持仓**索引(`onPositionOpened` 只对 futures contract 登记)。因此:

- 一个**纯 loan 用户**(有抵押借贷、无期货持仓)**永远不在 targeted 路径里**,只能靠周期性 backstop 全扫捞到。
- 结果:抵押品价格暴跌时,loan 用户的强平**不是即时的**,要等下一个 `LIQUIDATION_SCAN` tick。

### 1.2 与期货的两点本质差异

1. **无 race**:loan `check()` 已经在 on-lane(命令 apply 内)跑,不像 Plan 1 之前那个 off-lane scanner 会跨 disruptor 线程读用户态。**Plan 2 不是修正确性,是补延迟 + 清理。**
2. **backstop 不可去除**:loan 的 LTV 会因**利息累积 / 期限到期**在**没有任何离散事件**的情况下越线。这类只能靠周期全扫捕捉(期货靠 price+funding 事件几乎全覆盖,loan 不行)。所以 **targeted 是 backstop 之上的延迟优化,不是替代**。

### 1.3 目标

1. 给 loan 独立的 targeted 索引,让**抵押 spot 对的 `MARKPRICE_ADJUSTMENT`** 即时触发对应 loan 持有者的强平检查(isolated + cross 全覆盖)。
2. 清理 `LaneState` 里因 on-lane + apply-guard 而冗余的运行态(`inFlight` / `liqRetryThrottleMs`),并把 loan 决策时间对齐 `cmd.timestamp`。(`onApplied` 参数保留、loan 传 null,不做删除——见 §4 表 #4。)

### 1.4 非目标

- 不动 backstop 全扫机制(仍由父类 `LiquidationScheduledService` 发 `LIQUIDATION_SCAN`)。
- 不动 force-sell 的撮合/结算/记账逻辑(`LoanCommandHandlers` 的 pre/postProcess)。
- 不动动态利率重定价(`REPRICE_LOAN_RATES`,Plan 1 已上移父类)。
- 不动 margin-call 通知与其 5min 节流。

---

## 2. 核心设计:索引 + 触发

### 2.1 两个新索引(归属 `LoanLiquidationEngine`)

loan 相关状态留在 loan 引擎内,`LiquidationEngine.checkPositions` 委托查询。

| 索引 | 键 → 值 | 语义 |
|---|---|---|
| `isolatedLoanSymbolToUsers` | `symbolId → MutableLongSet<uid>` | uid 持有 ≥1 笔非空 isolated loan、其 `symbolId` 为该 spot 对 |
| `crossLoanCurrencyToUsers` | `currency → MutableLongSet<uid>` | uid 在该币种上有 cross 敞口(抵押 `crossLoanCollateral[c] > 0` 或 cross loan 的 `loanCurrency == c`) |

数据结构与 `symbolToUsers` 同构:`IntObjectHashMap<MutableLongSet>`。

### 2.2 触发匹配

事件:`MARKPRICE_ADJUSTMENT` 落在某 spot 对 `S`(base=`b`, quote=`q`)。触发链路**已存在**——`RiskEngine` 处理 `MARKPRICE_ADJUSTMENT` 时写 `lastPriceCache[S]` 后调 `checkPositions(cmd)`(`cmd.symbol = S`),对 futures / spot 对一视同仁。今天 spot 的 `S` 在 `symbolToUsers`(期货索引)里查不到东西,所以对 loan 无效。

Plan 2 让 `checkPositions` 的 targeted 分支**额外**查 loan 索引:

- isolated:`isolatedLoanSymbolToUsers[S]` 的直接持有者;
- cross:`crossLoanCurrencyToUsers[b] ∪ crossLoanCurrencyToUsers[q]`。

### 2.3 cross 为何按 currency 键 + over-approximate

cross 抵押是多币种、经 numeraire 估值。一笔 spot 对 `S` 的价格变动会改变持有 `b` 或 `q` 的 cross 账户 LTV。按 currency 建索引、事件时取 `{b, q}` 两币种的持有者并集,是**保守 over-approximate**:

- 可能**多查**(某 uid 的 LTV 实际没动)→ 无害,`checkUser` 跑一遍发现没越线即 no-op;
- 极端多跳估值下可能**漏查**(某 uid 经 `S` 间接受影响却不持 `b`/`q`)→ 由 **backstop 兜底**。

### 2.4 集成:并集 + 复用 checkUser

`checkPositions` targeted 分支:把 `symbolToUsers[S]` + 两个 loan 索引查到的 uid **求并、去重**,对每个 uid 调**现有的 `checkUser`**。`checkUser` 本就 futures + loan 一起查:

- loan-only 用户:futures 部分 no-op,loan 部分生效;
- futures-only 用户:loan 部分 no-op。

**不新增检查路径,loan 索引只是拓宽 `checkPositions` 要驱动 `checkUser` 的 uid 集合。**

### 2.5 关键安全性质

> **stale / 不完整的 loan 索引只损失延迟,绝不损失正确性**——周期 backstop 无条件全扫每个用户。漏一个维护 hook 只会"慢一点",不会"错"。这是整个方案低风险的基石。

---

## 3. 索引维护与 failover

### 3.1 确定性划分(与 `symbolToUsers` 一致)

- **维护:全节点、不 gate**——hook 在每条 loan 命令的确定性 apply 内触发,follower 保持索引热,晋升即正确。
- **查询(`checkPositions`):leader-gated**(父类 `isRunning()`)。
- **不序列化**——同 `symbolToUsers`,快照恢复时在 `updateProvider` 里从权威用户态重建。(副作用:每次重建自动清掉累积的 stale 项。)

### 3.2 isolated 维护 hook

成员语义 = "uid 在 `symbolId=S` 上持有 ≥1 笔非空 isolated loan",按 futures `onPositionClosed` 的 `holdsOther` 方式引用计数。

| 触发点(`LoanCommandHandlers`) | 动作 |
|---|---|
| `handleLoanCreate`(`isolatedLoans.put` 之后) | `onIsolatedLoanOpened(uid, symbolId)`:登记 |
| `handleLoanRepay` / `handleLoanReleaseCollateral` / `postProcessLoanForceLiquidate` 里 `loan.isEmpty() → remove` 分支 | `onIsolatedLoanClosed(up, symbolId)`:**仅当** uid 在 `S` 上再无其它 isolated loan 时摘除 |

### 3.3 cross 维护 hook

统一 helper `syncCrossExposure(up)`,在**全部 5 个 cross handler + cross force-liquidate postProcess** 末尾调用。它 reconcile uid 的行:

- 对每个 `crossLoanCollateral[c] > 0` 或 有 cross loan `loanCurrency==c` 的币种 `c`,确保 `uid ∈ index[c]`;
- 若 uid 的 cross 总敞口归零(无 cross loan 且抵押全 0),把 uid 从其索引币种里清除。

触发点:`handleLoanCrossAddCollateral`、`handleLoanCrossWithdrawCollateral`、`handleLoanCrossBorrow`、`handleLoanCrossRepay`、`handleLoanCrossForceLiquidate`(postProcess)。

**唯一的简化**:**部分**币种退出(uid 撤掉币种 `c` 的抵押但仍有其它 cross 敞口)时,保留 stale 的 `c→uid`,不做精确 per-currency 摘除。代价是 `MARKPRICE(c)` 的**无害 over-trigger**(重查发现 LTV 正常 → no-op),规模受 uid 触达的币种数约束,并在下次 `updateProvider` 重建时清掉。精确摘除的记账成本不值得——backstop 保证正确性。

> 维护是确定性全节点的,所以 stale 项在所有节点上**一致**(不是 divergence),只是轻度 over-inclusion。

### 3.4 updateProvider 重建

快照恢复 / 节点启动时,遍历 `userProfiles`:

- 每笔非空 isolatedLoan → `isolatedLoanSymbolToUsers[loan.symbolId].add(uid)`;
- 每个 `crossLoanCollateral[c] > 0` 的币种、每笔 crossLoan 的 `loanCurrency` → `crossLoanCurrencyToUsers[c].add(uid)`。

(与现有 futures `symbolToUsers` 重建同处、同风格。)

### 3.5 failover(比期货更简单)

期货需要 leader-local `liquidationFlow`(FORCE→IF→ADL)在 failover 重置以便残余重新检测。**loan 无多步 leader-local flow**——force-sell 是单条 IOC,其结果落在**持久化、确定性**的 loan 记录里(抵押减少、`stuckLiqAttempts`)。新 leader:

1. 索引热(确定性维护)或重建(`updateProvider`);
2. 从持久化记录重估 underwater loan;
3. 唯一保留的 leader-local 态是 `marginCallThrottleMs` → failover 重置 → 至多一条重复 margin-call 通知,无害。

**删掉的 `inFlight` / `liqRetryThrottleMs` 都是 leader-local,靠重新检测重建,failover 不丢东西。**

---

## 4. Cleanup bundle

| # | 改动 | 说明 |
|---|---|---|
| 1 | 删 `LaneState.inFlight` + 死方法 `isIsolatedLoanInFlight`/`isCrossLoanInFlight` + `publishTracked*` 包装 → 直接 `submit` | 安全:apply-guard 拒 `sellAmount > collateralAmount`,抵押 on-lane 顺序 pre-move,重复 force-sell 自拒 |
| 2 | 删 `liqRetryThrottleMs` + `stuckLiqThrottled`(**retry 节流完全删除,对齐期货**) | 保留 `stuckLiqAttempts`(持久化、确定性)+ `toleranceBpsFor` 容差爬梯 |
| 3 | 保留 `marginCallThrottleMs`(5min 通知节流) | 不变——防刷告警,与触发方式无关 |
| 4 | **保留 `submit(ApiCommand, Runnable)` 签名,loan 传 `null`**(2026-07-16 user 定,原拟删 `onApplied`) | 删 inFlight 后 onApplied 无消费者,但删参数要拖动 `LiquidationCommandSubmitter`/`LiquidationScheduledService`/`ExchangeRuntime` 跨模块 churn——死参数清理不值当,收窄为 loan-包内改动 |
| 5 | `check(userProfile)` → `check(userProfile, ts)`,LTV/利息/期限决策用 `cmd.timestamp` 替 `System.currentTimeMillis()` | 决策 cadence 确定性、对齐期货;`checkUser` 已有 `ts`。margin-call 通知节流仍 leader-local best-effort |

清理后 `LaneState` 只剩 `marginCallThrottleMs`,塌缩为每 lane 一个 `LongLongHashMap` 字段,取消嵌套类。

### 4.1 retry 节流删除的后果(已决策:完全删除)

删 `liqRetryThrottleMs` 后,卡单(空盘零成交)的 loan 会在**每个抵押价格 tick** 重发 force-sell(而非旧 backstop cadence)。接受理由:

- `toleranceBpsFor` 容差在 6 次内爬到 5%,多数几 tick 内成交;
- 一旦成交 loan 不再 underwater → 自动停重发;
- 真·空盘是 flash-crash 罕见场景且自限(book 回来即成交)。

与期货 Plan 1 删 stuck-republish 同策。

---

## 5. 测试

### 5.1 新行为(targeted)

- **isolated targeted**:loan-only 用户(无期货持仓),抵押 spot 对 `MARKPRICE_ADJUSTMENT` 暴跌 → **无需任何 `LIQUIDATION_SCAN`** 即发 force-sell。这是核心断言(今天只有 backstop 能捞到该用户)。
- **cross targeted**:cross 用户,`MARKPRICE` 落在其抵押币种所在 spot 对 → cross force-sell,无 scan。
- **索引维护**:create/repay/release/withdraw/borrow → 成员正确;stale 部分退出只 over-trigger、不漏。

### 5.2 backstop 不回归

无价格事件、纯利息累积越 LTV 的 loan,仍由 `LIQUIDATION_SCAN` 捕捉。

### 5.3 determinism / failover

`updateProvider` 从用户态重建三个索引 → 快照恢复测试 + `stateHash` 收敛(沿用 Plan 1 校验)。

### 5.4 预期测试改动(与 Plan 1 同模式,显式暴露不掩盖)

- `LoanLiquidationEngineTest`(36 用例)对未变行为保持绿;
- 断言 **`inFlight` 去重** 或 **30s retry 节流** 的用例按新模型重写或删除(实现时逐个列出,**不改生产迁就测试**)。

---

## 6. 涉及文件(概览,细节留给实现 plan)

**生产**
- `processors/loan/LoanLiquidationEngine.java`:加两索引 + 维护/查询/重建 helper;删 `inFlight`/`liqRetryThrottleMs`/`stuckLiqThrottled`;`LaneState` 塌缩;`check(up, ts)`。
- `processors/liquidation/LiquidationEngine.java`:`checkPositions` targeted 分支**查 loan 索引求并**(只读);`updateProvider` 委托重建;`checkUser` 传 `ts` 给 loan。(唯一非 loan-包生产改动。)
- `processors/loan/LoanCommandHandlers.java`:isolated/cross 生命周期 hook 调用。

> 不改:`LiquidationCommandSubmitter` / `LiquidationScheduledService` / `ExchangeRuntime`(onApplied 保留)。

**测试**
- `LoanLiquidationEngineTest` + 相关 IT:新增 targeted 用例、改/删 inFlight/throttle 用例。

---

## 7. 风险与权衡

| 风险 | 缓解 |
|---|---|
| 漏维护 hook → 索引 stale | backstop 无条件全扫兜底;只损延迟不损正确性 |
| cross over-approximate 多查 | 无害 no-op;`checkUser` 自然过滤 |
| 删 retry 节流 → 卡单空盘期 raft 写放大 | 容差爬梯快速收敛 + 成交即停 + 空盘罕见自限;已按对齐期货决策接受 |
| `System.currentTimeMillis()` → `cmd.timestamp` 改变利息/期限判定时刻 | 更确定、更一致(本就 leader 决策、结果走 raft),对齐期货 |
