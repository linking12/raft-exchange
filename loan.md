# 现货借贷（Spot Loan）架构设计

> 本文描述**已上线的**现货借贷模块（as-built），并在 §13 附**动态/浮动利率设计提案**（未实现）。
> 代码位置：`exchange-core/.../processors/loan/`（`LoanService` / `LoanCommandHandlers` / `LoanLiquidationEngine`）、
> `.../common/`（`IsolatedLoanRecord` / `CrossLoanRecord`）、`.../common/api/binary/`（配置命令）、
> `.../common/api/reports/`（查询/报表）、`FundEvent`（事件）、`RiskEngine`（dispatch + 配置 apply）。

---

## 摘要

用户以现货资产作抵押，从交易所自有流动性池借出另一种现货资产。产品形态是**加密质押借贷**（类 Binance Loans），非杠杆交易（无下单自动借币）。

**两种模式**：
- **Isolated（逐仓）**：一笔贷款绑定 (collateralCurrency, loanCurrency)，抵押与该笔 1:1。
- **Cross（全仓）**：账户级多币种抵押池 + 多笔债务，共享抵押，聚合 LTV（归一到 numeraire）。

**核心机制**：
- 惰性计息（触发点现算）+ 三级 LTV 阈值（initial / marginCall / liquidation）。
- 同 cmd 内闭环强平：force-sell IOC 撮合 → 分账 → 关 loan 或 badDebt 兜底。
- 独立 `LoanLiquidationEngine`（leader-only scanner），与期货 `LiquidationEngine` 平行、不耦合。
- 五个资金桶 + 四项运行时配置 + numeraire，per-shard；同 shard 内 Isolated + Cross 共用，跨 shard 独立。
- 命名空间 `loan*` 与期货 `margin*` 完全隔离。
- 抵押专用：loan 抵押只顶 loan 债务，不能顶 futures margin、不落 spot `exchangeLocked`；借出本金进 `accounts` 后普通化。
- 可用余额校验统一走 `RiskEngine.calculateLocked(user, ccy)`。

**规模**：3 个顶层类（`IsolatedLoanRecord` / `CrossLoanRecord` / `LoanService`）+ 命令处理类 `LoanCommandHandlers` + scanner `LoanLiquidationEngine`；`UserProfile` +3 字段 / `CoreSymbolSpecification` +7 字段 / `RiskEngine` +2 字段；13 条 raft 命令（10 借贷 + 3 池子运营）；2 条运行时配置命令；用户维度事件走 FundEvent，平台维度数据走 Report API。

---

## 1. 概述

### 1.1 目标
- 引擎层完整支持 Isolated + Cross 两种模式
- 借还强平资金流全程守恒可验证
- 与期货代码完全隔离（命名空间 + 数据结构 + 强平路径）
- 产品体验对齐 Binance Margin / Loans 主流做法

### 1.2 非目标
- 跨衍生品统一账户（Portfolio Margin）—— 借贷账本与期货独立
- 双跳撮合（BTC→USDT→USDC）—— 借贷币对强制存在对应 spot symbol
- 独立 spot-margin IF 池 —— badDebt 负记账 + `POOL_ABSORB_BAD_DEBT` 兜底
- 出借人/收益侧（Simple Earn 式双边市场）—— 平台单边出资
- 无缝杠杆交易（下单自动借/还）—— 借款是独立命令

> **动态利率**已不再是非目标：v1 固定利率（`spec.loanRateBps` 开仓锁定），动态/浮动方案见 §13 设计提案。

### 1.3 关键约束

| 约束 | 说明 |
|---|---|
| 池化借贷 | 出借方是交易所自有池；loan 挂在 user 维度 |
| 强制存在 spot symbol | Isolated 借贷币对必须存在 `BASE=collateralCurrency, QUOTE=loanCurrency` 的 spot symbol；Cross 每个抵押币须存在 `XXX/numeraire` symbol（估值 + 强平撮合） |
| Cross 基准币（numeraire） | **运行时可配**（`LoanService.numeraireCurrency`，经 `UPDATE_LOAN_GLOBAL_CONFIG` 设置，不再硬编码 USDT）；未配（`NUMERAIRE_UNSET=0`）时 Cross BORROW/WITHDRAW fail-close、scanner 的 LTV 路径保守跳过（期限强平仍生效，见 §7.3） |
| 惰性计息 | 无定时 tick 命令；仅在还款/强平触发点现算 |
| 同 cmd 内闭环 | force-sell IOC 撮合 + 结算 + 终态在一条 raft 命令 apply 内完成 |
| 抵押物账本隔离 | loan 抵押只服务对应 loan 债务，不能顶 futures margin，也不落 spot `exchangeLocked`；靠 `calculateLocked` 单点扩展实现双向隔离 |
| 本金普通余额 | 借出本金进 `accounts[loanCurrency]` 后无 tag，与其他余额同权 |
| 时间单位 | **毫秒（ms）**。`openedAtTs` / `lastAccrueTs` = `cmd.timestamp`（跨节点确定性）；`YEAR_MS` / `MS_PER_DAY` |
| 最大贷款期限 | `spec.loanMaxTermDays` 硬性拒绝新贷超期；到期由 scanner 触发期限强平 |
| 新系统冷启 | 上线前无历史 snapshot，序列化不做向后兼容 gate |

### 1.4 术语
- **命名空间隔离**：借贷前缀 `loan*`，与期货 `margin*` / `MarginMode` / `initMargin` / `extraMargin` 不重叠
- **符号规约**：`BTC/USDT`，`BASE=BTC=collateralCurrency`, `QUOTE=USDT=loanCurrency`
- **单位**：所有 amount 字段 currencyScale，与 `exchangeLocked` 一致
- **LTV**：Loan-to-Value = 借出价值 / 抵押价值（bps，6000 = 60%）
- **`SYSTEM_TRIGGERED_ORDER_ID` 侧的 force-liquidator uid**：force-sell IOC 的 taker 名义 uid，避免撞现货 self-trade 保护（对齐期货 `FORCE_LIQUIDATOR_UID`）

---

## 2. 架构总览

```
        客户端 / API 层
   Isolated: LOAN_CREATE / REPAY / ADD_COLLATERAL / RELEASE_COLLATERAL
   Cross:    LOAN_CROSS_ADD_COLLATERAL / WITHDRAW_COLLATERAL / BORROW / REPAY
   强平:     LOAN_FORCE_LIQUIDATE / LOAN_CROSS_FORCE_LIQUIDATE（scanner 生成）
   池子:     POOL_DEPOSIT / POOL_WITHDRAW / POOL_ABSORB_BAD_DEBT
   配置:     UPDATE_LOAN_GLOBAL_CONFIG / UPDATE_SYMBOL_LOAN_CONFIG
                     ↓ Raft
        RiskEngine（per-shard）
          preProcessCommand 首行 if (cmd.command.isLoan()) → loanCmdHandlers.dispatch(cmd)
          ├─ LoanCommandHandlers（命令 apply 业务流，持 RiskEngine ref）
          └─ LoanService（纯状态 + 纯函数工具）
               State（进 snapshot，per-shard）：
                 loanPoolAvailable[c]     （守恒正桶）
                 loanPoolBorrowed[c]      （追踪，不进守恒）
                 badDebt[c]               （追踪，不进守恒）
                 interestRevenue[c]       （守恒正桶）
                 loanLiquidationFees[c]   （守恒正桶）
                 crossLiquidationLtvBps / crossMarginCallLtvBps
                 loanPoolUtilizationCapBps / loanLiquidationFeeBps / numeraireCurrency
                 poolProcessedExternalIds （池子命令幂等）
          UserProfile 扩展 3 字段：isolatedLoans / crossLoanCollateral / crossLoans
          CoreSymbolSpecification 扩展 7 字段（loan.* + collateralWeightBps + loanMaxTermDays）
                     ↓
        LoanLiquidationEngine（独立 leader-only scanner，SimpleScheduledService）
          checkIsolated（扫 isolatedLoans） + checkCross（扫 crossLoans）
          in-flight 去重 / 卡单节流 / 容差爬梯 → publish force-sell IOC
                     ↓ 撮合
        OrderBook（IOC force-sell）
```

**组件职责**：
- **LoanService**：纯状态类。承载五桶 + 运行时配置 + 幂等表 + 序列化/stateHash + 纯函数工具（accrue / 抵债 / 强平结算 / Cross LTV / scale 换算 / force-sell orderId 编码）。**不持** RiskEngine ref。
- **LoanCommandHandlers**：命令处理类。13 条命令的 apply 业务流 + shard filter + POOL 短路 + `dispatch(OrderCommand)` 入口。持 RiskEngine ref，经 `engine.getXxx()` 现取 UserProfileService / SymbolSpecificationProvider / lastPriceCache / fees / adjustments / calculateLocked / LoanService / eventsHelper。
- **LoanLiquidationEngine**：**独立** scanner（不是把期货 `LiquidationEngine` 扩成 3 lane）。持一个 `LiquidationEngine` ref，通过它现取 provider / priceCache / LoanService / publisher / eventsHelper。两条 lane（Isolated / Cross）各自 in-flight 去重 + 节流。
- **RiskEngine.preProcessCommand 二级 dispatch**：首行判 `cmd.command.isLoan()`，命中整块委托，主 switch 看不到 loan 命令。
- **UserProfile / CoreSymbolSpecification**：per-user 数据 / per-symbol 规则配置，与现有字段平级挂载。

**分片架构**：RiskEngine 按 `uid & shardMask` 分片，`LoanService` 每 shard 一份；五桶 per-shard 独立；scanner / apply / 强平决策全 shard-local，无跨 shard 通信；池子跨 shard 不均衡由运营侧手动再平衡。

---

## 3. 领域模型

### 3.1 IsolatedLoanRecord

对象池复用，identity 字段非 final（由 `initialize` 重置）。字段按业务分组：

```java
public final class IsolatedLoanRecord implements WriteBytesMarshallable, StateHash, LoanRecord {
    // 身份
    public long uid;                    // 由 UserProfile 上下文注入，不序列化，仅进 stateHash
    public long loanId;                 // 客户端提供，per-user 唯一（Isolated 命名空间）
    // 市场（创建时锁定，源自 spot pair spec）
    public int  symbolId;               // 所属现货 pair（= LOAN_CREATE 的 cmd.symbol）；scanner/handler 直接 getSymbolSpecification(symbolId)，省 O(N) 反查
    public int  collateralCurrency;     // = spec.baseCurrency
    public int  loanCurrency;           // = spec.quoteCurrency
    // 借款条款（创建时锁定）
    public int  rateBps;                // 年化利率，开仓锁定
    public long openedAtTs;             // 开仓时间（ms），期限强平参照
    // 金额 / 运行态（可变）
    public long collateralAmount;       // 已抵押数量（currencyScale）
    public long outstandingPrincipal;   // 剩余本金
    public long accumulatedInterest;    // 已落账未支付利息
    public long lastAccrueTs;           // 上次计息游标（ms），初始 = openedAtTs
    public int  stuckLiqAttempts;       // 连续零成交强平次数，scanner 用它爬容差 + 卡单告警
}
```
`stateHash` 覆盖全 12 字段（含 symbolId、stuckLiqAttempts）。

### 3.2 CrossLoanRecord

**无抵押字段**——Cross 抵押账户级（`UserProfile.crossLoanCollateral`）。

```java
public final class CrossLoanRecord implements WriteBytesMarshallable, StateHash, LoanRecord {
    public long uid;
    public long loanId;                 // per-user 唯一（Cross 命名空间独立于 Isolated）
    public int  symbolId;               // 借款时匹配的现货 pair（findLoanSpecByQuoteCurrency 得到）
    public int  loanCurrency;
    public int  rateBps;
    public long openedAtTs;
    public long outstandingPrincipal;
    public long accumulatedInterest;
    public long lastAccrueTs;
    public int  stuckLiqAttempts;
}
```
`stateHash` 覆盖全 10 字段。两 record 实现 `LoanRecord` 接口（accrue / 抵债共用一套逻辑）。

### 3.3 UserProfile 扩展

```java
public final LongObjectHashMap<IsolatedLoanRecord> isolatedLoans;   // loanId -> record
public final IntLongHashMap                        crossLoanCollateral; // 多币种账户级抵押池
public final LongObjectHashMap<CrossLoanRecord>    crossLoans;       // loanId -> record
```
三字段 per-user，与 `accounts` / `exchangeLocked` / `positions` 平级；不加派生 view（per-user loan 数少，`calculateLocked` 现算）。

### 3.4 LoanService（状态 + 纯函数）

```java
public class LoanService implements WriteBytesMarshallable, StateHash {
    // --- 资金桶（loanCurrency scale）---
    IntLongHashMap loanPoolAvailable;    // 可借余额（守恒正桶）
    IntLongHashMap loanPoolBorrowed;     // 已借出 = Σ outstandingPrincipal（追踪，不进守恒）
    IntLongHashMap badDebt;              // underwater 核销损失（追踪负桶，不进守恒）
    IntLongHashMap interestRevenue;      // 利息收入（守恒正桶）
    IntLongHashMap loanLiquidationFees;  // 强平专项费（守恒正桶）
    // --- 运行时配置（UPDATE_LOAN_GLOBAL_CONFIG 可调）---
    int crossLiquidationLtvBps;          // 默认 8500 = 85%
    int crossMarginCallLtvBps;           // 默认 8000 = 80%
    int loanPoolUtilizationCapBps;       // 默认 9000 = 90%
    int loanLiquidationFeeBps;           // 默认 200 = 2%
    int numeraireCurrency;               // NUMERAIRE_UNSET(0) = 未配
    // --- 幂等 ---
    BoundedLongDedupSet poolProcessedExternalIds;  // POOL_* 命令去重（per-shard）
}
```

关键纯函数：
- `calculateDisplayInterest(LoanRecord, now)` —— 已落账利息 + 到 now 的 pending accrue
- `accrueTo(LoanRecord, now)` —— 落账 pending 到 `accumulatedInterest`（`accrueDelta` 用 `truncMulDiv` 两步防溢出）
- `applyDebtPayment(loan, account, fund)` —— 利息优先→本金抵债；利息进 `interestRevenue`、本金回 `loanPoolAvailable`；返回本次利息部分
- `settleLiquidationProceeds(loan, account, receivedQuote, now)` —— 先抽 `loanLiquidationFeeBps` 进 `loanLiquidationFees`，再 accrue + 抵债
- `calculateCrossAccountLtvBps(up, now, specProvider, currencyProvider, priceCache, numeraire)` —— 账户级 LTV（**含 pending 利息**），归一到 numeraire
- `collateralValueInQuoteCurrency(...)` / scale 换算（`collateralAmountToLots` 等）
- `forceSellOrderId(subtype, uid, loanId, tickTimeMs)` —— force-sell orderId 编码（§7.4）

### 3.5 CoreSymbolSpecification 扩展（loanConfig）

per-symbol 借贷配置归组进 `SymbolLoanSpecification`（`common` 包），`CoreSymbolSpecification` 只持一个 `loanConfig` 字段（非 null，默认空 = 未启用）。含 `initialLtvBps` / `marginCallLtvBps` / `liquidationLtvBps` / `maxAmount` / `maxTermDays` / `collateralWeightBps`（`isEnabled()` = initialLtvBps > 0）。经 `UPDATE_SYMBOL_LOAN_CONFIG` 命令运行时改写（RiskEngine apply 处校验，见 §11）。

> **利率不在 spec**：利率是 **per-loanCurrency（池级）** 概念，由 `LoanService` 的利率子系统拥有（见 §13），不放 per-symbol。上述 6 项 LTV / 期限 / 抵押折价才是真正 per-pair 的。

> （历史）曾有 `loanRateBps` 每 pair 静态利率；粒度错配（同一借出币被多 pair 借应同利率）已移除，统一到 §13 的 per-currency 利率模型。

### 3.6 RiskEngine / LoanCommandHandlers / LoanLiquidationEngine
- RiskEngine +2 字段：`loanService` / `loanCmdHandlers`；`preProcessCommand` 首行二级 dispatch；`calculateLocked` 扩 loan 抵押两项；序列化/stateHash 含 loanService；apply `UPDATE_LOAN_GLOBAL_CONFIG` / `UPDATE_SYMBOL_LOAN_CONFIG`。
- LoanCommandHandlers：13 handler + dispatch + shard filter + POOL 短路。
- LoanLiquidationEngine：独立 scanner，两 lane + in-flight + 节流 + 容差爬梯。

---

## 4. 资金模型

### 4.1 守恒方程

全局（每 shard 内独立成立，见 `TotalCurrencyBalanceReportResult.getGlobalBalancesSum`）：
```
accountBalances + extraMargin + exchangeLocked + loanBalances
              + fees + adjustments + suspends + ifBalances = 0
loanBalances = loanPoolAvailable + interestRevenue + loanLiquidationFees
```

**loan 平台桶三项均为平台持有现金，参与全局对账。**

**不进守恒的项**（追踪器）：
- `loanPoolBorrowed`：与 `loanPoolAvailable` 反向对称，只用于 utilization 校验 + 运营 metric
- `badDebt`：underwater 核销的损失追踪；对应的钱**已在借款人账户里**（disburse 时进了 `accounts`），不再是平台现金 → 不入方程
- `outstandingPrincipal` / `accumulatedInterest`：业务记账标签（借出时 `accounts += principal, loanPoolAvailable −= principal` 两真实桶互抵）
- `collateralAmount` / `crossLoanCollateral[c]`：per-user 虚锁 flag，`accounts` 物理不动；只在 `calculateLocked` 作扣项（有意不给独立桶，见下）

`accountBalances[c] = Σ_user (accounts[c] − exchangeLocked[c])`。loan 抵押的虚锁**包含在** `accountBalances` 里（accounts 物理不动），"可动用余额"由 `calculateLocked` 唯一负责（§14.2）。

### 4.2 关键不变量（建议落 `ITConservationFuzz`）
1. 池子非负：`loanPoolAvailable[c] ≥ 0`
2. 借出对偶：`Σ isolatedLoans + Σ crossLoans 的 outstandingPrincipal(loanCurrency==c) == loanPoolBorrowed[c]`
3. 抵押覆盖：`Σ loan.collateralAmount(collateralCurrency==c) + crossLoanCollateral[c] ≤ accounts[c] − exchangeLocked[c]`
4. Isolated LTV 边界：`outstandingPrincipal × BPS < collateralAmount × markPrice × loanLiquidationLtvBps`
5. Cross LTV 边界：有非空 crossLoans 的 user，`crossAccountLtvBps < crossLiquidationLtvBps`
6. 利用率上限：新借入不使 `loanPoolBorrowed × BPS > (loanPoolAvailable + loanPoolBorrowed) × loanPoolUtilizationCapBps`
7. 阈值排序：`loanInitialLtvBps < loanMarginCallLtvBps < loanLiquidationLtvBps`（per-symbol）；`crossMarginCallLtvBps < crossLiquidationLtvBps`（全局，配置命令强制校验，§11）
8. 期限约束：`cmd.timestamp − openedAtTs ≤ loanMaxTermDays × MS_PER_DAY`（超期 scanner 触发强平）
9. SUSPEND 守卫：`accounts / exchangeLocked / crossLoanCollateral allZero && isolatedLoans / crossLoans isEmpty`
10. 全局对账：§4.1 方程各桶求和为零

---

## 5. 业务流程

### 5.1 命令清单（13 条）
```
# Isolated（5）
LOAN_CREATE / LOAN_REPAY / LOAN_ADD_COLLATERAL / LOAN_RELEASE_COLLATERAL / LOAN_FORCE_LIQUIDATE
# Cross（5）
LOAN_CROSS_ADD_COLLATERAL / LOAN_CROSS_WITHDRAW_COLLATERAL / LOAN_CROSS_BORROW / LOAN_CROSS_REPAY / LOAN_CROSS_FORCE_LIQUIDATE
# 池子运营（3，cmd.uid 承载 shardId）
POOL_DEPOSIT / POOL_WITHDRAW / POOL_ABSORB_BAD_DEBT
# 配置（binary command）
UPDATE_LOAN_GLOBAL_CONFIG / UPDATE_SYMBOL_LOAN_CONFIG
```
**幂等**：用户维度命令走 `UserProfile.processedExternalIds` + `externalId`；`loanId` 只作业务主键。强平命令无 externalId，幂等靠 apply 时 `loan == null` 检测 + orderId。池子命令走 `LoanService.poolProcessedExternalIds`（key = `hash(cmdType, externalId)`，per-shard）。

### 5.2 Isolated 借（LOAN_CREATE）
校验（先便宜后贵）：spec 是 CURRENCY_EXCHANGE_PAIR → `loanInitialLtvBps > 0` → loanId 未占 → principal/collateral > 0 → `loanMaxAmount` → markPrice > 0 → 开仓 LTV ≤ initial → 抵押可用（`accounts − calculateLocked ≥ collateral`）→ `loanPoolAvailable ≥ principal` → 利用率上限。

状态转移：`initialize(...)` 后 `loan.symbolId = spec.symbolId`；`accounts[loanCurrency] += principal`，`loanPoolAvailable −= principal`，`loanPoolBorrowed += principal`；发 `LOAN_BORROW` 事件（§9）。守恒：loanCurrency 侧 +accounts/−pool = 0；collateral 侧 accounts 物理不动。

### 5.3 Isolated 还（LOAN_REPAY）
`accrueTo` → `applyDebtPayment`（利息优先）：`accounts[loanCurrency] −= paid`，利息进 **`interestRevenue`**，本金回 `loanPoolAvailable`；发 `LOAN_REPAY` 事件。守恒：−accounts / +pool（本金）/ +interestRevenue（利息）= 0。部分还款不释放抵押。loan 空壳（isEmpty）则从 map 移除（归还对象池）。

### 5.4/5.5 Isolated 补/减抵押（ADD / RELEASE_COLLATERAL）
补：抵押可用校验 → `collateralAmount += amount`（accounts 派生扣）。减：`amount ≤ collateralAmount` 且**减后 LTV < liquidation**（对齐 Binance，允许操作到 marginCall 上方，用户风险自负）→ `collateralAmount −= amount`；减到 0 且 isEmpty 则清壳。均发 `LOAN_COLLATERAL_CHANGE` 事件。

### 5.6–5.9 Cross（ADD / WITHDRAW_COLLATERAL / BORROW / REPAY）
- ADD：`collateralWeightBps > 0` + 可用校验 → `crossLoanCollateral += amount`。
- WITHDRAW：撤后账户级 LTV < `crossLiquidationLtvBps`（numeraire 未配时 LTV 恒 0 等效不拦截，但 BORROW 会 fail-close）。
- BORROW：`loanInitialLtvBps > 0` + loanId 未占 + `loanMaxAmount` + 借后账户级 LTV ≤ initial + 池子 + 利用率；`loan.symbolId = spec.symbolId`（`findLoanSpecByQuoteCurrency`）。numeraire 未配 → `LOAN_NUMERAIRE_NOT_CONFIGURED`。
- REPAY：同 Isolated 抵债逻辑，但不释放抵押（账户级）。

### 5.10 池子运营
- `POOL_DEPOSIT`：`loanPoolAvailable += amount`，`adjustments −= amount`（守恒）。
- `POOL_WITHDRAW`：偿付护栏 `loanPoolAvailable ≥ amount`（**只能提未借出部分**，借出的钱不可提）→ `loanPoolAvailable −= amount`，`adjustments += amount`。
- `POOL_ABSORB_BAD_DEBT`：管理员注资核销坏账，`badDebt[c] += absorbed`（向 0 收敛）。
- 三者 `cmd.uid` 承载 shardId，非目标 shard 静默 SUCCESS 短路；走 `poolProcessedExternalIds` 幂等。

---

## 6. 利息模型

### 6.1 惰性计息（ms）
触发点：REPAY / 强平 apply 前（`accrueTo`）+ 事件/查询时（`calculateDisplayInterest`，只读不落账）。
```
elapsed = now − loan.lastAccrueTs        // ms
step1   = truncMulDiv(elapsed, outstandingPrincipal, YEAR_MS)
pending = truncMulDiv(step1, rateBps, BPS_SCALE)
loan.accumulatedInterest += pending
loan.lastAccrueTs = now
YEAR_MS = 365L × 24 × 3600 × 1000；乘除走 CoreArithmeticUtils.truncMulDiv（128-bit）
```
利率只作用于 `outstandingPrincipal`（单利，利息单列在 `accumulatedInterest`）。守恒零变化（`accumulatedInterest` 不进方程）。

### 6.2 Scanner LTV **含 pending 利息**
scanner 判 LTV 用**真实债务**（本金 + 到 now 的 pending accrue），避免用户拖债不还避强平：
```
realDebt = outstandingPrincipal + calculateDisplayInterest(loan, now)
触发条件： realDebt × BPS ≥ collateralValueInLoanCurrency × loanLiquidationLtvBps
```
Cross 账户级 LTV（`calculateCrossAccountLtvBps`）同样含 pending 利息。
> 注：早期设计曾为省 CPU 让 scanner 只用本金 + 保守阈值吸收偏差；现已改为实时含利息，语义更准。

### 6.3 期限强平
`loanMaxTermDays > 0` 且 `now − openedAtTs > loanMaxTermDays × MS_PER_DAY` → 视同触发（Isolated 与 Cross 均支持，见 §7）。

---

## 7. 强平机制

### 7.1 独立 scanner（LoanLiquidationEngine）
**不是**把期货 `LiquidationEngine` 扩成 3 lane，而是**独立类**，由 `LiquidationEngine` 每 tick 二级委托 `check(userProfile)`：
- `checkIsolated`：遍历 `isolatedLoans`，per-loan LTV / 期限判定
- `checkCross`：`crossLoans` 非空则账户级 LTV + 期限判定

状态（进程级，不进 snapshot，failover 重置无碍）：每 lane 一个 `LaneState`（in-flight 去重 set + margin-call 节流 map + 卡单重发节流 map）；容差爬梯/卡单告警常量。onApplied 回调清 in-flight。

### 7.2 触发 → force-sell（含容差爬梯）
触发（LTV 越线 或 期限超限）时 publish force-sell IOC。**限价不是 0**，而是 markPrice 打容差折扣：
```
limitPrice = markPrice × (BPS − toleranceBps) / BPS
toleranceBps 按 loan.stuckLiqAttempts 爬梯：<3 → 1%，<6 → 2%，否则 5%（封顶）
```
连续零成交（stuck）越多容差越宽，让 IOC 吃更深档位；卡单重发按 30s 节流，连续零成交达阈值（约 10 次）告警（多半空盘）。marginCall 带（marginCall ≤ LTV < liquidation）只发 `LOAN_MARGIN_CALL` 事件（节流 ≥ 5 min），不强平。

### 7.3 Cross 强平
期限优先：`pickExpiredCrossLoan` 找到期笔 → 针对该笔强平（不依赖 numeraire 估值，到期即平）。否则账户级 LTV ≥ `crossLiquidationLtvBps` 时按 tiebreak 选一对 (sellingCurrency, targetLoan) partial deleverage（每 tick 一条，多 tick 迭代收敛）。
- 卖哪个抵押 `pickCrossCollateralToSell`：weight DESC → value DESC → currency ASC
- 还哪笔 `pickCrossLoanToRepay`：rate DESC → principal DESC → loanId ASC

### 7.4 force-sell OrderId 编码
`LoanService.forceSellOrderId(subtype, uid, loanId, tickTimeMs)`，对齐期货「身份 + 秒级时间戳」、无状态：
```
| 63..56 'L'(0x4C) | 55..48 subtype | 47..28 uidHash(20) | 27..12 loanIdHash(16) | 11..0 秒(12) |
subtype: 'S'(0x53)=Isolated / 'C'(0x43)=Cross    （避开期货 'I'=IF / 'A'=ADL）
uidHash = (uid*31+17) & 0xFFFFF；loanIdHash = (loanId*31+17) & 0xFFFF；秒 = tickTimeMs/1000 & 0xFFF（≈68min 回绕）
```
身份编进 orderId → 一笔 loan 最多一条强平流；同笔多轮补发靠 scanner in-flight guard 兜底；`tickTimeMs` = scanner tick（leader 生成、随命令复制 → 各副本确定）。helper：`isLoanForceSellOrderId` / `loanForceSellSubtype`。

### 7.5 Apply 流程（Isolated / Cross 共用结算契约）
force-sell 命令 apply（`handleLoanForceLiquidate` / `handleLoanCrossForceLiquidate`）：
1. `loan == null` → SUCCESS（幂等 no-op）
2. **pre-move**：把要卖的抵押从 `collateralAmount`（Isolated）/ `crossLoanCollateral`（Cross）原子挪进 `exchangeLocked`（compare-and-consume，抵押边界挡重复强平）
3. 返回 `VALID_FOR_MATCHING_ENGINE` → orderbook 标准 spot ASK IOC 撮合（taker = force-liquidator uid，跳过 taker 侧结算）
4. **postProcess（R2）**：按成交额 `settleLiquidationProceeds` —— 先抽强平费进 `loanLiquidationFees`，再 accrue + `applyDebtPayment`（利息进 `interestRevenue`、本金回 `loanPoolAvailable`）；overpay 退用户
5. **终态**：债清 → 关 loan；underwater（抵押尘埃卖不掉仍欠债）→ 剩余债务写 `badDebt`、本金从 borrowed 移除、发 `LOAN_LIQUIDATED`（带 badDebt）；零成交且无坏账 → no-op（不发事件），`stuckLiqAttempts++`，下 tick 重试

分账受用户 `accounts` 可动用量限制（借来的钱可能已做 futures margin），缺口进 `badDebt`——避免 loan 强平反向抽干 futures 保证金。

### 7.6 三种终态守恒
- A（债清、剩余抵押退回）：ΣΔ = 0
- B（underwater）：pool 视角完整收回本金，损失进 `badDebt` 负记账；利息进 `interestRevenue`
- C（部分成交，loan 保留）：等下 tick 独立重决策

---

## 8. 错误与边界（CommandResultCode）

`LOAN_*` 前缀与期货 `MARGIN_*` 隔离。主要码：
`LOAN_NOT_ENABLED`（`loanInitialLtvBps ≤ 0`）、`LOAN_ALREADY_EXISTS`、`LOAN_NOT_FOUND`、`LOAN_LTV_TOO_HIGH`、`LOAN_LTV_TOO_HIGH_AFTER_RELEASE` / `_AFTER_BORROW` / `_AFTER_WITHDRAW`、`LOAN_USER_SUSPENDED`、`LOAN_COLLATERAL_EXCEEDS_LOAN`、`LOAN_COLLATERAL_INSUFFICIENT`、`LOAN_ACCOUNT_INSUFFICIENT`、`LOAN_POOL_INSUFFICIENT`、`LOAN_POOL_UTILIZATION_EXCEEDED`、`LOAN_MARKPRICE_NOT_READY`、`LOAN_INVALID_AMOUNT`、`LOAN_UID_MISMATCH`、`LOAN_PRINCIPAL_EXCEEDS_LIMIT`、`LOAN_INVALID_CONFIG`（阈值序/范围违规）、`LOAN_INVALID_SYMBOL_TYPE`、`LOAN_NUMERAIRE_NOT_CONFIGURED`。

边界：markPrice 缺失/0 → 借款 reject、scanner 跳过（**无时效 age gate，依赖喂价保活**——运营侧需监控）；force-sell 深度 0 → filledSize=0、loan 保留；SUSPEND 有 loan 时 reject，dust sweep 白名单只含 `exchangeLocked`（绝不误扫 loan 抵押）。

---

## 9. 事件模型（用户维度）

用户维度事件复用 **FundEvent**（loan 操作本质是资金/余额事件）。FundEvent 扩 12 个 loan 快照字段：
`loanMode, loanCollateralCurrency, loanOutstandingPrincipal, loanAccumulatedInterest, loanCollateralAmount, loanRateBps, loanLtvBps, loanPrincipalDelta, loanCollateralDelta, loanInterestPaid, loanBadDebt, loanThresholdBps`。

5 个事件类型：
```
LOAN_MARGIN_CALL(40)        // LTV 达 marginCall 预警（scanner leader-local，bypass raft，best-effort，节流 ≥ 5min）
LOAN_BORROW(41)             // 放款 + 抵押锁定
LOAN_REPAY(42)             // 利息优先 → 本金
LOAN_COLLATERAL_CHANGE(43)  // 加/减抵押
LOAN_LIQUIDATED(44)         // 强平核销（卖出/抵债/overpay/坏账）
```
DTO 侧 `IFundEventsHandler.FundEventReport` 嵌套 `LoanSnapshot`（池化）；gRPC 侧 `event.proto` 有对应 `LoanSnapshot` message。`FundEventsHelper` 加 `sendLoanBorrow/Repay/CollateralChange/Liquidated/MarginCall` 方法。no-op LIQUIDATED（零成交无坏账）不发事件。

事件路由：用户操作走 taker bucket；系统触发（force-liquidation）走 maker bucket（`SYSTEM_TRIGGERED_ORDER_ID`）。MARGIN_CALL / LIQUIDATED 的 scanner 侧事件走 leader-local，best-effort（换届可能丢，UI 需靠查询兜底）。

---

## 10. 查询与报表

### 10.1 用户维度（SingleUserReport）
`SingleUserReportResult` 除 accounts / exchangeLocked / positions / orders 外，含 loan 持仓：
- `isolatedLoans`：每笔含 loanId / symbolId / 币种 / rateBps / openedAtTs / collateralAmount / outstandingPrincipal / accumulatedInterest / **displayInterest**（含 pending）/ **ltvBps**（实时，口径对齐 scanner）/ **markPrice**
- `crossLoans`：每笔含 loanId / symbolId / loanCurrency / rateBps / openedAtTs / principal / interest / displayInterest
- `crossLoanCollateral`：账户级抵押池
- `crossAccountLtvBps`：账户级 LTV

LTV/利息复用 `LoanService` 已测 helper，与强平口径一致（避免查询看到的健康度比强平乐观）。gRPC 侧 `report.proto` 有对应 `IsolatedLoan`/`CrossLoan` message + 字段。

### 10.2 平台维度（LoanPlatformReport）
`ReportType.LOAN_PLATFORM`：per-shard × per-currency 聚合 interestRevenue / loanLiquidationFees / badDebt / poolAvailable / poolBorrowed（per-shard section → merge，同 InsuranceFundReport 模式）。用于平台对账 / 风控大盘。

---

## 11. 配置（运行时可调）

### 11.1 UPDATE_LOAN_GLOBAL_CONFIG
5 字段 partial-update（`≤0` = 不改）：`numeraireCurrency, crossLiquidationLtvBps, crossMarginCallLtvBps, loanPoolUtilizationCapBps, loanLiquidationFeeBps`。RiskEngine apply 做 **apply-all-or-nothing 校验**：numeraire 需 currencySpec 存在；`thresholdsValidGivenCurrent` 校验生效后 `0 < marginCall < liquidation < 100%`、利用率上限 ≤ 100%、强平费 < 100%；任一不过整条拒绝（`log.warn`，不留半更新）。

### 11.2 UPDATE_SYMBOL_LOAN_CONFIG
6 个 per-symbol loan 字段（不含利率，见 §3.5），RiskEngine apply 校验 `initial < liquidation < 100%`、`marginCall` 在其间、maxAmount/maxTermDays ≥ 0、collateralWeight ∈ [0,10000]，valid 才 `spec.updateLoanConfig(...)`。`loanInitialLtvBps = 0` 即**停借该 pair**（LOAN_CREATE / BORROW → `LOAN_NOT_ENABLED`），可作 per-symbol 熔断开关。

### 11.3 利率曲线配置
利率曲线参数（`base` / `kink` / `slope1` / `slope2` + `lockedRateAdjustBps`，全局单曲线，后续可扩每币种）归 `LoanService` 的利率子系统，经 `UPDATE_LOAN_GLOBAL_CONFIG`（或专用命令）下发，见 §13。

---

## 12. 收入提取（RESET_FEE）

`ResetFeeCommandProcessor`（TwoStep：R1 收集 → merge → R2）在清 `fees` 的同时，把 `interestRevenue` + `loanLiquidationFees` 两桶也 sweep 进 `adjustments`（桶 −X、adjustments +X，守恒安全）。这是利息/强平费收入的提取路径（否则钱锁在桶里无法提走）。`badDebt` 由 `POOL_ABSORB_BAD_DEBT` 单独兜底。

---

## 13. 利率模型设计（提案，未实现）

> 利率是 **per-loanCurrency（池级）** 概念，由 `LoanService` 的利率子系统**独立拥有**（不在 spec，见 §3.5）——同一借出币被多 pair 借应同利率。
> **已定：锁定 + 浮动两种模式并存**，借款人开仓选（= Binance **Fixed**（定期锁定）/ **Flexible**（活期浮动））；由池子利用率经 kinked 曲线驱动。

### 13.1 类结构（利率子系统，抽出 LoanService，防膨胀）
利率逻辑**不堆进 LoanService**，抽到 `processors.loan.rate` 子包，就**两个类**（无接口、无独立曲线类）。语义上 **Fixed 派生自 Floating**（开仓锁 floating 当前利率 + 点差），所以 Floating 是引擎、Fixed 是它的一层薄封装。

```
loan/rate/
  FloatingRateModel   = 动态利率引擎（Isolated FLOATING + 全部 Cross）。进 snapshot：
                        曲线参数 base/kink/slope1/slope2 + currentRateBps[ccy] + accRateBpsMs[ccy] 累加器 + lastRepriceTs
                        curveRateBps(util) / utilizationBps / repriceCurrency / advanceAccumulator / currentRateBpsOrBase
                        openRateBps = 当前活期利率；accrue = §13.5 累加器
  FixedRateModel      = Isolated LOCKED。持 FloatingRateModel ref。进 snapshot：仅 lockedRateAdjustBps
                        openRateBps = floating 当前利率 + lockedRateAdjustBps（下限 0）；accrue = 线性（loan.rateBps，原 §6.1）
```
- `LoanService` 持 `floatingRate` + `fixedRate(floatingRate)`；`accrueTo` / `calculateDisplayInterest` 按 `loan.isFixedRate()` **if/else 分派**（2 处，不需接口）。Isolated LOCKED → Fixed；Isolated FLOATING + 全部 Cross → Floating。
- 序列化 floating 先于 fixed（fixed 的 currentRate 来源在 floating）。

> **利率是唯一移出 spec 的 loan 配置**（per-currency 池级）。`loanInitialLtvBps` / `loanLiquidationLtvBps` / `loanMarginCallLtvBps` / `loanMaxAmount` / `loanMaxTermDays` / `collateralWeightBps` 仍在 `CoreSymbolSpecification`——它们是 **per-symbol（每 pair 各异）**，而两 model 是 per-shard 单例，放不进也不该放。

### 13.2 双利率模式（Isolated: LOCKED/FLOATING；Cross: 仅 FLOATING）
**Isolated `LOAN_CREATE` 显式带 `rateMode ∈ {LOCKED, FLOATING}`；Cross `LOAN_CROSS_BORROW` 恒 FLOATING（不带 rateMode）**。计息按 mode 分派，都落 `accumulatedInterest → interestRevenue`，守恒不变。

**组合矩阵（3 个有效 SKU，对齐 Binance：Fixed 是定期借币产品、不挂全仓）：**

| | Fixed（LOCKED） | Flexible（FLOATING） |
|---|---|---|
| **Isolated** | ✅ 有期限 | ✅ 无期限 |
| **Cross** | ✗ 不支持 | ✅ 无期限 |

- **LOCKED（Fixed，定期，仅 Isolated）**：开仓 `loan.rateBps = curve.lockedOpenRateBps(loanCcy)` 锁死，走线性计息；存续期不变。**有期限**——吃 `loanMaxTermDays`，到期 scanner 期限强平。
- **FLOATING（Flexible，活期，Isolated + 全部 Cross）**：开仓不锁率，走 §13.5 累加器随 reprice 变。**无期限**——随借随还，只 LTV 强平。

**期限绑 mode**：只有 Isolated LOCKED 有期限。Isolated term 检查加 `&& rateMode == LOCKED`。**Cross 恒 Floating → 无期限 → 移除 `pickExpiredCrossLoan`（现有 Cross 期限强平回收，Cross 只保留 LTV 强平）**。

**机制分层**：`①全局利用率 + ②曲线`（= `LoanRateCurve`）两模式共用；`③累加器` 仅 FLOATING。链路：`周期 TwoStep → merge 全局 util[ccy] → 曲线 → currentRateBps[ccy] →（LOCKED 开仓锁定 / FLOATING 累加器）`。

### 13.3 全局利用率（TwoStepCommandProcessor）——①
池子桶 per-shard，同币种真实利用率是各 shard 之和；只有 matcher 单线程阶段有全局视角。用 `TwoStepCommandProcessor`（**FundingFee 同款先例**），全在 RAFT 日志内、确定性。
- 触发：leader 周期 submit `ApiRepriceLoanRates`，**默认每小时**（对齐 Binance Flexible 小时级刷新；可配）+ admin 手动触发入口
- **R1** `collectInput`：每 shard 把 `borrowed` / `available` 写进 `cmd.commonByShard[shardId].amounts`——**复用这单张 map**，key 编码：**borrowed 存 key=currency、available 存 key=~currency**
- **merge** `buildMatcherEvents`：跨 shard 按 key 符号解码求和 → 每币种 `util = ΣB × BPS / (ΣB+ΣA)`，每币种一条 event 携带 util（曲线参数在 `LoanRateCurve`，matcher 拿不到 → 只算 util，利率放 R2）
- **R2** `applyEvent`：**每 shard** `curve.repriceCurrency(ccy, util)`（util 过曲线写 `currentRateBps`）；各 shard 写入相同值。只写利率缓存、不碰余额，无守恒风险

### 13.4 利率曲线（kinked，整数）——②
```
util ≤ kink:  rateBps = base + slope1 × util / kink
util > kink:  rateBps = base + slope1 + slope2 × (util − kink) / (BPS − kink)
```
参数每 `loanCurrency` 一组 + 全局默认回退（对齐 Binance 每资产独立利率；起步可只填全局默认）。经 `UPDATE_LOAN_GLOBAL_CONFIG` 下发（§11.3）。

### 13.5 浮动计息（additive 累加器）——③，仅 FLOATING
`LoanRateCurve` 每币种 `accRateBpsMs[ccy]` = Σ(rateBps × Δms)（bps·ms）：
```
liveAcc(ccy, now) = accRateBpsMs[ccy] + currentRateBps[ccy] × (now − lastRepriceTs)
每笔 loan 存 accSnapshot；FloatingRateModel.accrue：
  pending = truncMulDiv(liveAcc(now) − loan.accSnapshot, principal, YEAR_MS × BPS_SCALE)
  loan.accumulatedInterest += pending;  loan.accSnapshot = liveAcc(now)
reprice R2：accRateBpsMs[ccy] += currentRateBps[ccy] × (tickTs − lastRepriceTs); currentRateBps[ccy]=新率; lastRepriceTs=tickTs
```
**是线性计息的严格推广**（利率恒定时退化成 FixedRateModel 的公式，可做回归护栏）。
**additive（单利）而非 multiplicative index（复利）**：additive 线性增长、64-bit 百年不溢、整数精确、与"利率只作用本金"一致；复利需 128-bit / renorm，收益不值。

### 13.6 数据结构 / accrue 分派 / 冷启动
- **IsolatedLoanRecord**：+ `byte rateMode`（LOCKED/FLOATING）+ `accSnapshot`（FLOATING 累加器游标）。LOCKED 用 `rateBps`+`lastAccrueTs`；FLOATING 用 `accSnapshot`。
- **CrossLoanRecord**：**只 + `accSnapshot`，不加 rateMode**（Cross 恒 FLOATING）。
- **spec**：删 `loanRateBps`（利率不再 per-symbol，见 §3.5）。
- **accrue 分派**：Isolated 按 `loan.rateMode` 取 `FixedRateModel`/`FloatingRateModel`；Cross 恒 `FloatingRateModel`。都写 `accumulatedInterest`。
- **scanner 期限门**：只 Isolated LOCKED 有期限（term 检查加 `&& rateMode == LOCKED`）。**移除 `pickExpiredCrossLoan`**（Cross 无期限）。
- **冷启动**：`currentRateBps` 未 reprice → 回退**曲线基础利率 `curve(0) = base`**（不再依赖 spec）；利率永远曲线派生、genesis 也确定。loan 未上线无迁移负担。

### 13.7 定价差 + 分层
- **LOCKED 定价差**：两模式默认同曲线值；配置 `lockedRateAdjustBps`（默认 0）给 LOCKED 加/减价（下限 0）。引擎不预设倾向。
- **VIP / 额度分层**（后续）：`finalRate = clamp(curveRate × (BPS − vipDiscountBps)/BPS ± amountTierBps, floor, ceil)`；需 `UserProfile.loanTier` + 数据源，先不做。

### 13.8 决策与后续

**已定**（对齐 Binance Crypto Loans）：
- 利率 **per-loanCurrency，单一归 LoanService 利率子系统**；删 `spec.loanRateBps`
- 类结构（as-built）：**两个类，无接口、无独立曲线类**（见 §13.1）——`FloatingRateModel`（曲线 + reprice + 累加器，动态引擎）+ `FixedRateModel`（持 floating ref，仅 `lockedRateAdjustBps`，线性计息）；曲线/利用率纯函数并入 `FloatingRateModel`
- **组合矩阵**：Isolated × {Fixed, Floating}；**Cross 仅 Floating**（Fixed 归 Isolated）
- **Cross 无期限** → 移除 `pickExpiredCrossLoan`（回收现有 Cross 期限强平，Cross 只 LTV 强平）
- 期限绑 mode（仅 Isolated LOCKED 有期限）；开仓 Isolated 显式选 `rateMode`、Cross 恒 Floating
- 定价：同曲线值 + `lockedRateAdjustBps`（默认 0）；冷启动回退曲线 base（非 spec）
- cadence 每小时 + 手动触发；曲线每 `loanCurrency` + 全局默认

**后续（非首版）**：用户自选期限（7/30/90d 期限维度曲线）、VIP 分层、每币种曲线细化、用抵押物直接还款、出借人/收益侧（见 §15）

---

## 14. 与现有系统集成

### 14.1 序列化 / stateHash
新系统冷启，位置读、无 version gate。
- `UserProfile`：末尾追加 `isolatedLoans` / `crossLoanCollateral` / `crossLoans`；stateHash 含三者。
- `LoanService.writeMarshallable`：`loanPoolAvailable, loanPoolBorrowed, badDebt, interestRevenue, loanLiquidationFees, crossLiquidationLtvBps, crossMarginCallLtvBps, loanPoolUtilizationCapBps, loanLiquidationFeeBps, numeraireCurrency, poolProcessedExternalIds`（stateHash 同）。
- `IsolatedLoanRecord.stateHash` 12 字段、`CrossLoanRecord` 10 字段（均含 symbolId、stuckLiqAttempts）。
- scanner 的 in-flight / 节流 map 不进 stateHash（进程级）。
- `ITLoanFailoverSnapshot` 验证快照恢复后 hash 一致 + 守恒。

### 14.2 calculateLocked（唯一可用余额入口）
在现有 futures 保证金 + `exchangeLocked` 分支末尾追加两项：`Σ isolatedLoans.collateralAmount(collateralCurrency==c)` + `crossLoanCollateral[c]`。所有下游（NSF / withdraw / place order）自动把 loan 抵押视为锁定量 → loan 抵押与 futures margin 双向自动隔离，**不改 futures / spot 任何代码**。

### 14.3 SUSPEND 守卫
扩展含 `isolatedLoans.isEmpty() && crossLoanCollateral.allZero() && crossLoans.isEmpty()`。

---

## 15. 与主流产品对齐 / 产品形态

产品形态 = **加密质押借贷（Binance Loans 类）**，非杠杆交易。

| 特性 | Binance | 我们 |
|---|---|---|
| Isolated / Cross 双模式 | ✅ | ✅ |
| 三级 LTV 阈值 / Margin Call / 强平费 / partial deleverage / 抵押折价 / 单笔上限 / 利用率上限 / 补减抵押 | ✅ | ✅ |
| 期限（Fixed）/ 永续（Flexible） | ✅ | ✅（`loanMaxTermDays`=0 永续，>0 定期） |
| 多币种抵押 | ✅ | ✅ Cross（Isolated 1:1） |
| 用户持仓 + 实时 LTV 查询 | ✅ | ✅ SingleUserReport |
| 运行时调阈值/费率/numeraire + per-symbol 熔断 | ✅ | ✅ 配置命令 |
| 小时计息 | ✅ | 惰性等价（查询现算 displayInterest） |
| Fixed（锁定）/ Flexible（浮动）双利率产品 | ✅ | 🚧 §13 提案（已定两者并存，开仓选 `rateMode`） |
| **动态/分层利率** | ✅ util 曲线 + VIP | 🚧 §13 提案（当前固定利率） |
| **出借人/收益侧（Simple Earn）** | ✅ | ❌ 平台单边出资 |
| **无缝杠杆交易（auto-borrow）** | ✅ Margin | ❌ 显式借（本产品是 Loans 形态） |
| **用抵押物直接还款** | ✅ | ❌（只有 REPAY + 被动强平） |
| 组合保证金（统一账户） | ✅ | ❌ 借贷/期货独立 |

---

## 16. 关键设计决策（摘）

| 决策 | 选定 | 理由 |
|---|---|---|
| 抵押账本隔离 | loan 抵押不顶 futures margin；本金普通化 | `calculateLocked` 单点扩展双向隔离 |
| 强平引擎 | **独立 `LoanLiquidationEngine`** | loan 子域边界清晰，与期货解耦 |
| Scanner LTV | 含 pending 利息 | 语义准，防拖债避强平 |
| force-sell 限价 | markPrice × (1−容差)，容差按卡单爬梯 1/2/5% | 吃更深档位，避免只吃顶档反复 partial |
| force-sell orderId | 身份(uid,loanId)+秒级 ts，无状态，对齐期货 | failover 无撞号，in-flight guard 兜同笔多轮 |
| 分账顺序 | 利息优先 → 本金 | badDebt 只反映本金损失，对齐会计 |
| 利息 / 强平费桶 | `interestRevenue` / `loanLiquidationFees`（非 `fees`） | 与撮合费分账，经 RESET_FEE 提取 |
| Underwater 兜底 | `badDebt` 负记账 + `POOL_ABSORB_BAD_DEBT` | 不进守恒（钱在借款人账户），管理员注资核销 |
| 事件 | 复用 FundEvent + 12 loan 字段 + 5 类型；平台数据走 Report | loan 操作即资金事件，平台侧独立查询 |
| 配置 | `UPDATE_LOAN_GLOBAL_CONFIG` / `UPDATE_SYMBOL_LOAN_CONFIG` 运行时可调 + 校验 | 无需重启；阈值自洽强制 |
| 利率归属 | **per-loanCurrency，单一归 LoanService 利率子系统**（`LoanRateCurve` + Fixed/Floating 两 model，抽出 LoanService）；删 `spec.loanRateBps` | 池级概念，粒度正确、不膨胀 LoanService（§13） |
| 利率模式 | 当前固定；§13 提案 Fixed+Flexible 双模式（TwoStep 全局利用率 + kinked 曲线 + additive 累加器） | 两 SKU，开仓选 rateMode，冷启动回退曲线 base |
| numeraire | 运行时可配，未配 fail-close | 不硬编码 USDT |

---

## 17. 交付状态

- **已上线**：§2–§12、§14–§16 所述全部功能，测试覆盖（`LoanServiceTest` / `LoanCommandHandlersTest` / `LoanLiquidationEngineTest` / `UpdateLoanGlobalConfigCommandTest` / `LoanPlatformReportResultTest` / `SingleUserReportLoanTest` / `ITLoanConservation` / `ITConservationFuzz` / `ITLoanFailoverSnapshot` / `ITLoanForceLiquidatePipeline` / `ITResetFee` 等）。
- **待运营**：markPrice 喂价停更监控告警；坏账兜底 runbook（触发/资金来源/频率）。
- **提案**：§13 动态/浮动利率。
- **后续可选**：全局一键停借开关；用抵押物直接还款；出借人/收益侧；分层利率 VIP 数据源；抵押折价分档；user 累计借款上限。
