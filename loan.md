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
- 同 cmd 内闭环强平：force-sell IOC 撮合 → 分账 → 关 loan 或 LIF 接管兜底。
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
- 出借人/收益侧（Simple Earn 式双边市场）—— 平台单边出资
- 无缝杠杆交易（下单自动借/还）—— 借款是独立命令

> **动态利率**已不再是非目标：v1 固定利率（`spec.loanRateBps` 开仓锁定），动态/浮动方案见 §13 设计提案。

### 1.3 关键约束

| 约束 | 说明 |
|---|---|
| 池化借贷 | 出借方是交易所自有池；loan 挂在 user 维度 |
| 强制存在 spot symbol | Isolated 借贷币对必须存在 `BASE=collateralCurrency, QUOTE=loanCurrency` 的 spot symbol；Cross 每个抵押币须存在 `XXX/numeraire` symbol（估值 + 强平撮合） |
| Cross 基准币（numeraire） | **运行时可配**（`LoanService.numeraireCurrency`，经 `ADD_LOAN` 设置，不再硬编码 USDT）；未配（`NUMERAIRE_UNSET=0`）时 Cross BORROW/WITHDRAW fail-close、scanner 的 LTV 路径保守跳过（期限强平仍生效，见 §7.3） |
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
   池子:     POOL_DEPOSIT / POOL_WITHDRAW / LOAN_IF_DEPOSIT / LOAN_IF_WITHDRAW
   配置:     ADD_LOAN
                     ↓ Raft
        RiskEngine（per-shard）
          preProcessCommand 首行 if (cmd.command.isLoan()) → loanCmdHandlers.dispatch(cmd)
          ├─ LoanCommandHandlers（命令 apply 业务流，持 RiskEngine ref）
          └─ LoanService（纯状态 + 纯函数工具）
               State（进 snapshot，per-shard）：
                 loanPoolAvailable[c]     （守恒正桶）
                 loanPoolBorrowed[c]      （追踪，不进守恒）
                 interestRevenue[c]       （守恒正桶）
                 loanInsuranceFund[c]     （LIF，守恒桶，允许为负）
                 lifAlertThresholds[c]    （LIF 水位告警线，非资金）
                 crossLiquidationLtvBps / crossMarginCallLtvBps
                 loanPoolUtilizationCapBps / loanLiquidationFeeBps / numeraireCurrency
                 ltvLiquidationBufferBps / ltvMarginCallBufferBps
          UserProfile 扩展 3 字段：isolatedLoans / crossLoanCollateral / crossLoans
          CoreSymbolSpecification 扩展 7 字段（loan.* + collateralWeightBps + loanMaxTermDays）
                     ↓
        LoanLiquidationEngine（独立 leader-only scanner，SimpleScheduledService）
          checkIsolated（扫 isolatedLoans） + checkCross（扫 crossLoans）
          按破产价单次报价 → publish force-sell IOC（无 in-flight 状态，决策是复制态纯函数）
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
    public long accSnapshot;            // FLOATING 计息游标：上次 accrue 的 liveAcc 快照
    public long cumInterestPaid;        // 累计已付利息，FundEvent 发快照供下游相减
}
```
`stateHash` 覆盖全 14 字段（含 uid、symbolId）。

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
    public long accSnapshot;
    public long cumInterestPaid;
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
    IntLongHashMap interestRevenue;      // 利息收入（守恒正桶）
    IntLongHashMap loanInsuranceFund;    // LIF 保险基金（守恒桶，允许为负，§18.5）
    IntLongHashMap lifAlertThresholds;   // LIF 水位告警线（正数量级，0 = 一转负就报，§18.10）
    // --- 运行时配置（ADD_LOAN 可调）---
    int crossLiquidationLtvBps;          // 默认 8500 = 85%
    int crossMarginCallLtvBps;           // 默认 8000 = 80%
    int loanPoolUtilizationCapBps;       // 默认 9000 = 90%
    int loanLiquidationFeeBps;           // 默认 200 = 2%
    int numeraireCurrency;               // NUMERAIRE_UNSET(0) = 未配
    int ltvLiquidationBufferBps;         // Symbol 派生：liquidationLtv = initialLtv + 本值
    int ltvMarginCallBufferBps;          // Symbol 派生：marginCallLtv = liquidationLtv − 本值
}
```

关键纯函数：
- `calculateDisplayInterest(LoanRecord, now)` —— 已落账利息 + 到 now 的 pending accrue
- `accrueTo(LoanRecord, now)` —— 落账 pending 到 `accumulatedInterest`（`accrueDelta` 用 `truncMulDiv` 两步防溢出）
- `applyDebtPayment(loan, account, fund)` —— 利息优先→本金抵债；利息进 `interestRevenue`、本金回 `loanPoolAvailable`；返回本次利息部分
- `settleLiquidationProceeds(loan, account, receivedQuote, now)` —— 先抽 `loanLiquidationFeeBps` 进 `loanInsuranceFund`，再 accrue + 抵债
- `calculateCrossAccountLtvBps(up, now, specProvider, currencyProvider, priceCache, numeraire)` —— 账户级 LTV（**含 pending 利息**），归一到 numeraire
- `collateralValueInQuoteCurrency(...)` / scale 换算（`collateralAmountToLots` 等）
- `forceSellOrderId(subtype, uid, loanId, tickTimeMs)` —— force-sell orderId 编码（§7.4）

### 3.5 CoreSymbolSpecification 扩展（loanConfig）

per-symbol 借贷配置归组进 `SymbolLoanSpecification`（`common` 包），`CoreSymbolSpecification` 只持一个 `loanConfig` 字段（非 null，默认空 = 未启用）。含 `initialLtvBps` / `marginCallLtvBps` / `liquidationLtvBps` / `maxAmount` / `maxTermDays` / `collateralWeightBps`（`isEnabled()` = initialLtvBps > 0）。经 `ADD_LOAN` 命令运行时改写（RiskEngine apply 处校验，见 §11）。

> **利率不在 spec**：利率是 **per-loanCurrency（池级）** 概念，由 `LoanService` 的利率子系统拥有（见 §13），不放 per-symbol。上述 6 项 LTV / 期限 / 抵押折价才是真正 per-pair 的。

> （历史）曾有 `loanRateBps` 每 pair 静态利率；粒度错配（同一借出币被多 pair 借应同利率）已移除，统一到 §13 的 per-currency 利率模型。

### 3.6 RiskEngine / LoanCommandHandlers / LoanLiquidationEngine
- RiskEngine +2 字段：`loanService` / `loanCmdHandlers`；`preProcessCommand` 首行二级 dispatch；`calculateLocked` 扩 loan 抵押两项；序列化/stateHash 含 loanService；apply `ADD_LOAN`。
- LoanCommandHandlers：13 handler + dispatch + shard filter + POOL 短路。
- LoanLiquidationEngine：独立 scanner，两 lane；**无本地 in-flight 状态**——决策须是复制态的纯函数，否则副本间分叉。

---

## 4. 资金模型

### 4.1 守恒方程

全局（每 shard 内独立成立，见 `TotalCurrencyBalanceReportResult.getGlobalBalancesSum`）：
```
accountBalances + extraMargin + exchangeLocked + loanBalances
              + fees + adjustments + suspends + ifBalances = 0
loanBalances = loanPoolAvailable + interestRevenue + loanInsuranceFund
```

**loan 平台桶三项均为平台持有现金，参与全局对账。**

**不进守恒的项**（追踪器）：
- `loanPoolBorrowed`：与 `loanPoolAvailable` 反向对称，只用于 utilization 校验 + 运营 metric
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

### 5.0 尺度边界（API contract）

**跨 API 边界的金额一律是该币种的 `currencyScale` 整数**——client 按 `CoreCurrencySpecification.digit` 换算完再发，
`raft-exchange-server` 的 converter 纯透传（15 个 loan converter 无一处乘除），引擎收到即记账。

逐仓与全仓都遵循，各自取各自的币：

| 命令 | 字段 | 尺度 |
|---|---|---|
| `LOAN_CREATE` | size=抵押量 / price=本金 | base 币 / quote 币 currencyScale（同一条命令两种精度） |
| `LOAN_REPAY` / `LOAN_CROSS_REPAY` | price=还款额 | 借款币 currencyScale |
| `LOAN_ADD` / `RELEASE_COLLATERAL` | size | 抵押币 currencyScale |
| `LOAN_CROSS_ADD` / `WITHDRAW_COLLATERAL` | size | 入参 currency 的 currencyScale |
| `LOAN_CROSS_BORROW` | price=本金 | 入参 loanCcy 的 currencyScale |
| `POOL_*` / `LOAN_IF_*` | size=金额 | 入参 currency 的 currencyScale |

引擎侧的护栏是构造性的：每条命令的金额都与 `up.accounts[currency]` 直接相减比较，两者必须同尺，写错立刻穿帮。

> **唯一例外：强平单走撮合单位。** `LOAN_FORCE_LIQUIDATE` / `LOAN_CROSS_FORCE_LIQUIDATE` 的 `size` 是**张数(lot)**、
> `price` 是**撮合价**，因为它们要直接进 orderbook。引擎收到后经 `lotsToCollateralAmount` 反算回 currencyScale 才记账。
> 这两条由 scanner 内部生成，**不对外暴露**（client SDK 无对应方法）。新增对外命令时不要照抄它们的形状。

> **`currencyScale` 与 symbol `lot` 是两套精度**，换算只在引擎内部发生（估值、下单量、pre-move、REJECT 回填），
> 且必须显式经 `CoreArithmeticUtils`。读路径反向：事件/报表发原始整数 + `scaleK`，由消费方还原——loan 事件因涉及
> 两个币种，借款侧用 `currencyScaleK`、抵押侧用 `loanCollateralCurrencyScaleK`，两组各自还原（§9）。

### 5.1 命令清单（13 条）
```
# Isolated（5）
LOAN_CREATE / LOAN_REPAY / LOAN_ADD_COLLATERAL / LOAN_RELEASE_COLLATERAL / LOAN_FORCE_LIQUIDATE
# Cross（5）
LOAN_CROSS_ADD_COLLATERAL / LOAN_CROSS_WITHDRAW_COLLATERAL / LOAN_CROSS_BORROW / LOAN_CROSS_REPAY / LOAN_CROSS_FORCE_LIQUIDATE
# 池子 / LIF 运营（4，cmd.uid 承载 shardId）
POOL_DEPOSIT / POOL_WITHDRAW / LOAN_IF_DEPOSIT / LOAN_IF_WITHDRAW
# 配置（binary command）
ADD_LOAN
```
**幂等**：用户维度命令走 `UserProfile.processedTransactionIds.tryClaim(orderId)`（claim 后失败也不释放，重试需换新 transactionId，对齐 BALANCE_ADJUSTMENT）；`loanId` 只作业务主键。强平命令幂等靠抵押边界（pre-move compare-and-consume）+ apply 时 `loan == null` 检测。池子运营命令**无幂等去重**，靠运营侧不重发。

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
- `LOAN_IF_DEPOSIT`：LIF 注资，`loanInsuranceFund += amount`，`adjustments −= amount`（守恒）。用于开张垫付启动资金、接管把 LIF 打成负数后补仓。
- `LOAN_IF_WITHDRAW`：LIF 提取，余额护栏 `loanInsuranceFund ≥ amount`（不足 → `LOAN_IF_INSUFFICIENT`）→ `loanInsuranceFund −= amount`，`adjustments += amount`。主用途是处置接管来的抵押库存：提走场外变现、所得再 deposit 回来。**LIF 允许为负是接管的被动结果，不是运营可主动透支的额度**，故 withdraw 一律不得把余额压到 0 以下。
- 兜底不再走命令：抵押卖不掉时由 LIF 直接承接（§18.4、§18.8），`badDebt` 桶与 `POOL_ABSORB_BAD_DEBT` 已废除。
- 四者 `cmd.uid` 承载 shardId，非目标 shard 静默 SUCCESS 短路。

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

**刻意无本地 in-flight 状态**：scanner 的决策必须是复制态的纯函数，任何进程级记忆都会让副本走出不同分支。重复提交由抵押边界（pre-move compare-and-consume）挡住。仅 margin-call 预警保留进程级节流 map（纯通知，不改状态）。

### 7.2 触发 → force-sell（破产价单次报价）
触发（LTV 越线 或 期限超限）时 publish force-sell IOC。**限价不是 0**，而是破产价——卖出所得刚好覆盖债务的那个价（§18.3）：
```
limitPrice = markPrice × 债务 / 抵押市值        （= LTV × markPrice，ceil 向上取整）
```
只报一次价：接得住则平台不赔不赚、借款人损失安全垫；全拒则由 LIF 承接（§18.4）。**不再爬容差**——爬梯没有终局，卖不掉就无限重试。marginCall 带（marginCall ≤ LTV < liquidation）只发 `LOAN_MARGIN_CALL` 事件（节流 ≥ 5 min），不强平。

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
4. **postProcess（R2）**：按成交额 `settleLiquidationProceeds` —— 先抽强平费进 `loanInsuranceFund`，再 accrue + `applyDebtPayment`（利息进 `interestRevenue`、本金回 `loanPoolAvailable`）；overpay 退用户
5. **终态**：债清 → 关 loan；市场按破产价接不住（全拒）或抵押已碎成卖不掉的尘埃而仍欠债 → **LIF 按债务全额承接**（付出本息、取走抵押，§18.4），债务清零、发 `LOAN_LIQUIDATED`；纯 no-op（零成交且债已清）→ 不发事件

分账受用户 `accounts` 可动用量限制（借来的钱可能已做 futures margin），缺口由 LIF 垫付——避免 loan 强平反向抽干 futures 保证金。

### 7.6 三种终态守恒
- A（债清、剩余抵押退回）：ΣΔ = 0
- B（LIF 接管）：LIF 付出本息（转负）、取走抵押；pool 完整收回本金、利息进 `interestRevenue`——损失留在 LIF，处置库存时才实现
- C（部分成交，loan 保留）：等下 tick 独立重决策

---

## 8. 错误与边界（CommandResultCode）

`LOAN_*` 前缀与期货 `MARGIN_*` 隔离。主要码：
`LOAN_NOT_ENABLED`（`loanInitialLtvBps ≤ 0`）、`LOAN_ALREADY_EXISTS`、`LOAN_NOT_FOUND`、`LOAN_LTV_TOO_HIGH`、`LOAN_LTV_TOO_HIGH_AFTER_RELEASE` / `_AFTER_BORROW` / `_AFTER_WITHDRAW`、`LOAN_USER_SUSPENDED`、`LOAN_COLLATERAL_EXCEEDS_LOAN`、`LOAN_COLLATERAL_INSUFFICIENT`、`LOAN_ACCOUNT_INSUFFICIENT`、`LOAN_POOL_INSUFFICIENT`、`LOAN_POOL_UTILIZATION_EXCEEDED`、`LOAN_MARKPRICE_NOT_READY`、`LOAN_INVALID_AMOUNT`、`LOAN_UID_MISMATCH`、`LOAN_PRINCIPAL_EXCEEDS_LIMIT`、`LOAN_INVALID_CONFIG`（阈值序/范围违规）、`LOAN_INVALID_SYMBOL_TYPE`、`LOAN_NUMERAIRE_NOT_CONFIGURED`。

边界：markPrice 缺失/0 → 借款 reject、scanner 跳过（**无时效 age gate**——价格再旧也照常参与估值与强平判定）。
loan 用的现货 pair 没有外部喂价方，markPrice 由本所成交价 15s 时间加权维护（`LastPriceCacheRecord.applyTradePrice`），
故风险不是"喂价方停更"而是**冷门币对长期无成交导致价格陈旧**——这不是运维疏忽而是市场状态，无法靠保活解决，
只能经 `markPriceTs`（IF 报表暴露）观测，并在选定抵押币种时规避流动性不足的 pair；force-sell 深度 0 → filledSize=0、loan 保留；SUSPEND 有 loan 时 reject，dust sweep 白名单只含 `exchangeLocked`（绝不误扫 loan 抵押）。

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
LOAN_LIQUIDATED(44)         // 强平核销（卖出/抵债/overpay/LIF 接管）
```
DTO 侧 `IFundEventsHandler.FundEventReport` 嵌套 `LoanSnapshot`（池化）；gRPC 侧 `event.proto` 有对应 `LoanSnapshot` message。`FundEventsHelper` 加 `sendLoanBorrow/Repay/CollateralChange/Liquidated/MarginCall` 方法。no-op LIQUIDATED（零成交且债已清）不发事件。

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
`ReportType.LOAN_PLATFORM`：per-shard × per-currency 聚合 interestRevenue / loanInsuranceFund / poolAvailable / poolBorrowed（per-shard section → merge，同 InsuranceFundReport 模式）。用于平台对账 / 风控大盘。

---

## 11. 配置（运行时可调）

**统一命令 `ADD_LOAN`（`BatchAddLoanCommand`，binary command code 1005）**：合并了旧 `UPDATE_LOAN_GLOBAL_CONFIG` / `UPDATE_SYMBOL_LOAN_CONFIG` 两条命令，命名对齐 `BatchAdd{Accounts,Symbols,Currencies}Command` 一族。一条命令可选带 **global** 部分（`GlobalLoanConfig`）与 **symbol** 部分（`SymbolLoanConfig`），两块作用域独立、**各自校验各自 apply**，一部分非法只跳过该部分（`log.warn`），不影响另一部分；至少提供一部分。proto 侧 `BatchAddLoanCommand{ global?, symbol? }`（`BinaryDataCommand.add_loan`）。

### 11.1 global 部分（`GlobalLoanConfig`）
5 字段 partial-update（`≤0` = 不改）：`numeraireCurrency, crossLiquidationLtvBps, crossMarginCallLtvBps, loanPoolUtilizationCapBps, loanLiquidationFeeBps`。RiskEngine apply 做 **apply-all-or-nothing 校验**：numeraire 需 currencySpec 存在；`thresholdsValidGivenCurrent` 校验生效后 `0 < marginCall < liquidation < 100%`、利用率上限 ≤ 100%、强平费 < 100%；任一不过整块拒绝（不留半更新）。

### 11.2 symbol 部分（`SymbolLoanConfig`）
6 个 per-symbol loan 字段（不含利率，见 §3.5），RiskEngine apply 校验（`fieldsValid()`）`initial < liquidation < 100%`、`marginCall` 在其间、maxAmount/maxTermDays ≥ 0、collateralWeight ∈ [0,10000] + spec 存在且为 `CURRENCY_EXCHANGE_PAIR`，valid 才 `spec.updateLoanConfig(...)`。`loanInitialLtvBps = 0` 即**停借该 pair**（LOAN_CREATE / BORROW → `LOAN_NOT_ENABLED`），可作 per-symbol 熔断开关。

### 11.3 rateCurve 部分（`RateCurveConfig`，as-built）
动态利率曲线参数（`baseBps` / `kinkUtilBps` / `slope1Bps` / `slope2Bps` + `lockedRateAdjustBps`，全局单曲线，后续可扩每币种）作为 `ADD_LOAN` 的第三个可选部分下发（proto `SpotLoanRateCurveConfig`）。**存在即整体替换**（非 partial-update——因 0 是合法曲线值）：RiskEngine apply 校验 `valid()`（`base ∈ [0,100%)`、`0 < kink < 100%`、`slope1/slope2 ≥ 0`；`lockedRateAdjustBps` 无约束、apply 时 `openRateBps` 下限 0），valid 才写 `FloatingRateModel`（曲线 4 参）+ `FixedRateModel`（点差）。**client 暂不暴露**（仅 server / 运维侧）。曲线子系统见 §13。

---

## 12. 收入提取（RESET_FEE）

`ResetFeeCommandProcessor`（TwoStep：R1 收集 → merge → R2）在清 `fees` 的同时，把 `interestRevenue` 桶也 sweep 进 `adjustments`（桶 −X、adjustments +X，守恒安全）。这是利息收入的提取路径（否则钱锁在桶里无法提走）。`loanInsuranceFund`（LIF）**不被 sweep**——它是保险基金而非收入，提取走独立的 `LOAN_IF_WITHDRAW`（§18.5）。

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
- 触发（as-built）：复用强平 scanner 的 2s 心跳——`LoanLiquidationEngine.check` 里 **shard 0** 按 `REPRICE_INTERVAL_MS`（默认 1h）节流,经 leader-gated `LiquidationCmdPublisher` publish 一条 `ApiRepriceLoanRates` 走 raft 复制。节流状态 `lastRepriceMs` 是 leader-local 进程级(换届重置无碍;晚发无害——累加器用旧率补积分,数学精确)。无独立调度线程。
- **R1** `collectInput`：每 shard 把 `borrowed` / `available` 写进 `cmd.commonByShard[shardId].amounts`——**复用这单张 map**，key 编码：**borrowed 存 key=currency、available 存 key=~currency**
- **merge** `buildMatcherEvents`：跨 shard 按 key 符号解码求和 → 每币种 `util = ΣB × BPS / (ΣB+ΣA)`，每币种一条 event 携带 util（曲线参数在 `LoanRateCurve`，matcher 拿不到 → 只算 util，利率放 R2）
- **R2** `applyEvent`：**每 shard** `curve.repriceCurrency(ccy, util)`（util 过曲线写 `currentRateBps`）；各 shard 写入相同值。只写利率缓存、不碰余额，无守恒风险

### 13.4 利率曲线（kinked，整数）——②
```
util ≤ kink:  rateBps = base + slope1 × util / kink
util > kink:  rateBps = base + slope1 + slope2 × (util − kink) / (BPS − kink)
```
参数每 `loanCurrency` 一组 + 全局默认回退（对齐 Binance 每资产独立利率；起步可只填全局默认）。经 `ADD_LOAN` 下发（§11.3）。

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
- `LoanService.writeMarshallable`：`loanPoolAvailable, loanPoolBorrowed, interestRevenue, loanInsuranceFund` 四桶 + `lifAlertThresholds` + `LoanGlobalConfig`（7 个 int）+ `FloatingRateModel` + `FixedRateModel`（stateHash 同）。
- `IsolatedLoanRecord.stateHash` 14 字段、`CrossLoanRecord` 11 字段（均含 uid、symbolId、cumInterestPaid）。
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
| force-sell 限价 | ~~markPrice × (1−容差)，容差按卡单爬梯~~ → **破产价 `D/C`，单次报价**（§18.3–18.4） | 爬梯无终局；破产价不引入新参数且与期货语义一致 |
| force-sell orderId | 身份(uid,loanId)+秒级 ts，无状态，对齐期货 | failover 无撞号；单次报价后不再有同笔多轮 |
| 分账顺序 | 利息优先 → 本金 | 本金优先回补池子，对齐会计 |
| 利息 / 保险基金桶 | `interestRevenue`（RESET_FEE 提取）/ `loanInsuranceFund`（LIF，**不被 sweep**，§18.5） | 与撮合费分账；LIF 是基金不是收入 |
| Underwater 兜底 | ~~`badDebt` + `POOL_ABSORB_BAD_DEBT`~~ → **LIF 接管**（§18.5、§18.8） | badDebt 是 LIF 的低精度特例；改由 LIF 承接后损失对账可见 |
| 事件 | 复用 FundEvent + 11 loan 字段 + 5 类型；平台数据走 Report | loan 操作即资金事件，平台侧独立查询 |
| 配置 | `ADD_LOAN` 运行时可调 + 校验 | 无需重启；阈值自洽强制 |
| 利率归属 | **per-loanCurrency，单一归 LoanService 利率子系统**（`LoanRateCurve` + Fixed/Floating 两 model，抽出 LoanService）；删 `spec.loanRateBps` | 池级概念，粒度正确、不膨胀 LoanService（§13） |
| 利率模式 | 当前固定；§13 提案 Fixed+Flexible 双模式（TwoStep 全局利用率 + kinked 曲线 + additive 累加器） | 两 SKU，开仓选 rateMode，冷启动回退曲线 base |
| numeraire | 运行时可配，未配 fail-close | 不硬编码 USDT |

---

## 17. 交付状态

- **已上线**：§2–§12、§14–§16 所述全部功能，测试覆盖（`LoanServiceTest` / `LoanCommandHandlersTest` / `LoanLiquidationEngineTest` / `UpdateLoanGlobalConfigCommandTest` / `LoanPlatformReportResultTest` / `SingleUserReportLoanTest` / `ITLoanConservation` / `ITConservationFuzz` / `ITLoanFailoverSnapshot` / `ITLoanForceLiquidatePipeline` / `ITResetFee` 等）。
- **限额现状（上线前须知晓）**：`SpotLoanConfig` 的 proto 只带 `symbolId` + `loanInitialLtvBps`（字段 3–8 已 reserved），
  converter 走 `ofMarket(symbolId, initialLtv)`，故 **`maxAmount` 恒派生为 0 = 单笔无上限，经 gRPC 配不了**；
  也没有 per-user 累计上限。当前唯一的总量约束是 `loanPoolUtilizationCapBps`——单个用户可把某币种池子借到该上限。
- **待运营**：抵押 pair 价格陈旧监控（拉 IF 报表的 `mark_price_ts`，判断距今多久无成交；期货侧同一字段仍是喂价停更告警）；**LIF 水位监控（§18.10）—— 引擎侧刻意不做，并入后续「外部定时拉 report 监控各资金池」统一建设；在那之前 LIF 无任何告警**；LIF 抵押库存处置 runbook（谁提、多久提一次、场外变现后回存）。
- **提案**：§13 动态/浮动利率。
- **后续可选**：全局一键停借开关；用抵押物直接还款；出借人/收益侧；分层利率 VIP 数据源；抵押折价分档；user 累计借款上限。

---

## 18. 强平终局：破产价 + 保险基金（LIF）

> **实现进度**：§18.3–18.9 已落地（破产价单次报价、容差爬梯删除、LIF 升格与停止 sweep、Isolated/Cross 接管、`badDebt` 废除）；
> §18 已全部落地。

### 18.1 要解决的问题：强平会永久卡死

现状下强平在**买盘缺口大于限价容差**时会无限重试且无终局，实测链路：

1. force-sell 挂 IOC，`markPrice × (1 − 容差)` 为限价
2. 全拒 → REJECT 事件 → 抵押原样回填 `loan.collateralAmount`
3. `sellableLots` 仍 > 0（抵押完好），因此**永远进不了** `sellableLots == 0 && remainDebt > 0` 的核销分支
4. 下一次价格事件重复以上，容差爬到封顶后即固定不变

后果：债务持续计息、借贷池资金被冻结、坏账既不落 `badDebt` 也无任何信号。

**期货同样存在该问题**（此前误判为"有 ADL 保证收敛"）：`ADLCommandProcessor` 在 `profitablePositions.isEmpty()` 时静默 `return`，而 `LiquidationEngine` 仍无条件 `liquidationFlow = null`，仓位继续水下。当"订单簿无深度 + IF 不足 + 无盈利对手方"三者同时成立（剧烈单边行情下高度相关），期货同样只能靠价格事件反复重试。

### 18.2 根因：loan 缺少终局吸收方

期货有 `FORCE → IF → ADL` 三级，ADL 把损失摊派给盈利对手方。loan **没有对手方可摊派**，因此必须自备终局吸收方，否则任何"卖不掉"都只能无限重试。

### 18.3 破产价：不引入新参数

期货破产价 = 权益归零的价。loan 的等价定义是**卖出所得刚好覆盖债务**：

```
C × P = D          →     P_破产 = D / C
```

其中 `C` = 抵押数量，`D` = 未偿本金 + 应计利息。代入 `LTV = D / (C × markPrice)`：

```
P_破产 = LTV × markPrice
```

即**强平线 LTV 就是破产价相对市价的折扣**。`liquidationLtvBps = 8500` 时，触发强平那一刻 `P_破产 = 0.85 × markPrice`，天然留 15% 安全垫。该价完全由现有状态导出，**不需要新增配置项**。

> **Cross 的坑：定价必须用未加权 LTV。** 上式由 `C × P = D` 导出，C 是**市值**；而 Cross 的账户级 LTV
> （`calculateCrossAccountLtvBps`）分母是按 `collateralWeightBps` 折过价的加权抵押，代进去会把破产价抬高 1/weight 倍。
> weight=7500 时报价高出 33%，加权 LTV 一旦到 10000 更会挂到市价之上、永不成交——而此时抵押市值仍是债务的 1.33 倍，
> 本该轻松卖掉，却只能一路走到 LIF 接管。**weight 是"愿意按几折放贷"的风险折扣，属触发判定；破产价问的是"卖得出多少钱"，
> 属估值。** 故触发用 `calculateCrossAccountLtvBps`（加权），定价用 `calculateCrossRawLtvBps`（市值）。Isolated 两侧
> 本就都用原始市值，无此问题。

> **联动（配置时必须知晓）**：强平 LTV 阈值同时是 **LIF 垫资规模的调节阀**。阈值 85% → 挂单折扣 15%，市场大概率吃得下，极少动用 LIF；阈值调高到 95% → 折扣仅 5%，卖不掉概率大增，**LIF 接管将成为常态**。调此参数前先评估 LIF 承压。

### 18.4 单次报价 + LIF 接管

采用期货同款形态：**报一次价，接不住就由 LIF 接管**。

| 步骤 | 动作 |
|---|---|
| 1 | force-sell 挂 IOC，限价 = `P_破产 = D / C` |
| 2a | 成交 → 所得覆盖债务，平台不赔不赚；借款人损失安全垫（即强平惩罚，公示可算） |
| 2b | 全拒 → **LIF 按债务全额接管**：付出 `D`，取走全部抵押 `C` |

因 `C` 的账面价按破产价恰为 `D`，**LIF 是以成本价买下抵押**，名义不亏，只承担日后变现的价格风险——与期货 IF 承接破产仓语义完全一致。

**随之删除**（单次报价后不再有第二次尝试，以下全部失去意义）：
- 容差爬梯 `toleranceBpsFor` 及 `MAX_TOLERANCE_ATTEMPTS` / `MAX_TOLERANCE_BPS` / `SELL_SIZE_BUFFER_BPS`
- `IsolatedLoanRecord` / `CrossLoanRecord` 的 `liquidationAttempts` 字段（含 leader-local 声明）
- 卡单告警日志（`STUCK_ALERT_ATTEMPTS`）

### 18.5 LIF 资金池

**独立于期货 IF**，建在 `LoanService`，不复用 `LiquidationService.notionals`（后者按 symbol 记 notional，loan 需按 currency 记真实余额）。

现有 `loanLiquidationFees` 桶**直接升格**为 LIF——它已在 `getGlobalBalancesSum()` 的守恒对账内，无需改动对账结构。因身份从"手续费收入"变为"保险基金"，建议改名 `loanInsuranceFund`（费只是注资来源之一，不是身份）。

| 事项 | 设计 |
|---|---|
| 初始资金 | 交易所垫付，走新增 `LOAN_IF_DEPOSIT` 命令（对冲 `adjustments`，同 `POOL_DEPOSIT` 模式） |
| 持续注资 | 强平费改为直接入 LIF |
| **停止扫走** | `ResetFeeCommandProcessor` 不再 sweep 该桶；`interestRevenue` 仍作为真实收入照常扫走 |
| 允许为负 | **是**。LIF 可无限接管，事后补充 |
| 处置出口 | `LOAN_IF_WITHDRAW`，运营提走抵押库存、场外变现后回存（余额不足即拒，不得透支）|

**LIF 为负 ≠ 亏损**：接管时付出 `loanCurrency`、收进 `collateralCurrency`，故 `LIF[USDT] = −1000万` 与 `LIF[WBTC] = +200` 并存，是**用负债换资产**，损失只在处置库存时实现。与 `badDebt`（抵押已灭失的纯损失）性质不同，报表须能区分，否则负数会被误读为窟窿。

**必须认识到**：每次接管都令 LIF 的 loanCurrency 减少、collateralCurrency 增加，且这些抵押恰是刚被证明卖不掉的。**无处置出口则 LIF 单向失血**，"事后补充"会退化为运营不停垫钱。处置出口是 LIF 可持续的前提，不是可选项。

### 18.6 接管的资金流与守恒

```
LIF[loanCcy]          -= 本金 + 利息
  loanPoolAvailable[loanCcy] += 本金
  loanPoolBorrowed[loanCcy]  -= 本金
  interestRevenue[loanCcy]   += 利息
LIF[collateralCcy]    += 抵押              ← 从借款人 accounts 真实扣走（原为虚拟锁定）
借款人：债务清零、抵押清零
```

守恒验证：LIF 与 pool / interestRevenue 同属 `loanBalances` 聚合，前三行净变化为 0；第四行 LIF 增加 = 借款人 `accountBalances` 减少，净变化为 0。**`getGlobalBalancesSum()` 恒等式不变，无需改动对账口径**（负值同样参与求和）。

### 18.7 Cross 接管：按 numeraire 估值定额扣

整户接管会把尚未触及强平线的债一并清掉、端走全部抵押，用户无法接受。故按**该笔债占账户总债的比例**分摊抵押。

**扣法：按占比算出"应取走的 numeraire 估值"，再按确定顺序（`collateralWeightBps` 高者优先）从抵押池逐币种扣至该估值。**
不采用"每个币种按占比等比切"——那会把每种抵押都切成碎片，LIF 收到一堆尘埃、且截断误差按币种数放大。定额扣使 LIF 承接的币种尽量集中，也便于后续处置。

**已知约束**：占比与估值均需 numeraire 折算，而接管恰恰发生在市场异常时，此时喂价最不可靠——用可能失真的价格决定拿走用户多少抵押。喂价缺失时应 fail-closed（拒绝接管、告警等待），而非按残缺价格硬扣。

### 18.8 废除 badDebt：LIF 是它精度更高的超集

`badDebt` 与 LIF 在"平台替借款人兜底"这一层是同一件事，差别在**损失何时确认、确认多少**：

| | `badDebt` | LIF 接管 |
|---|---|---|
| 触发时平台拿到什么 | 什么都没有（抵押已灭失，尘埃退回用户） | **全部抵押** |
| 损失金额如何确定 | 核销当刻按剩余债务估记 | 由**实际处置价格**决定 |
| 会计形态 | 直接核销（direct write-off） | 先转入资产，处置后确认损益 |
| 是否进守恒对账 | **否**（off-balance 追踪器） | **是**（真实资金桶） |

LIF 接管的那一刻**并非损失**，只是资产形态转换；**处置库存后仍填不平的缺口才是真坏账**。故 `badDebt` 是 LIF 的特例且精度更差——核销当刻根本无从得知抵押实际价值，只能估记。

**结论：废除 `badDebt` 桶与 `POOL_ABSORB_BAD_DEBT` 命令。** 后者与 `LOAN_IF_DEPOSIT` 本是同一个运营动作（平台掏钱填坑），并存只会让运营困惑该用哪个。

附带收益：`badDebt` 现游离于守恒等式之外，废除后全部兜底损失走 LIF，而 LIF 在 `getGlobalBalancesSum()` 内——**损失变为对账可见**，不再藏在不参与校验的桶里。

### 18.9 事件

**不新增事件类型**，复用 `LOAN_LIQUIDATED`。用户视角"卖掉抵债"与"LIF 接管"结果一致——债务清零、抵押清零，无需知晓接管方。

但**事件必须照常发出**：接管真实改变用户 `accounts` 与 loan 状态，不发则下游余额对不上。LIF 侧记账走平台报表。

### 18.10 水位告警：LIF 必须有，期货 IF 的做法不可照抄

期货 IF **没有水位告警**，仅在转 IF / 转 ADL 时各打一条 `log.warn` 流水；IF 余额低到何种程度、是否被击穿均不可见（ADL 无盈利对手方时更是静默 `return`，连日志都没有）。

但 LIF **不能照抄这一点**，因为两者刹车机制不同：

| | 期货 IF | LIF |
|---|---|---|
| 余额不足时 | `Math.min(available, needed)` 封顶，少接管、剩余转 ADL | **无限接管，允许为负** |
| 是否会为负 | 否 | **是** |
| 自动刹车 | 有（降级到下一级） | **无** |

期货 IF 有自然刹车，故"还剩多少"不致命；LIF 把刹车拆了，`LIF[USDT] = −5000 万` 与 `= −50 万` 在代码看来毫无区别，都照常接管。**没有自动刹车的机制只能靠人看，因此告警是必需项而非可选项。**

**告警口径：直接盯负值本身——`LIF[currency] < −阈值` 触发，按币种独立配置。**

**实现在撮合之外**：外部监控定时拉 `ReportType.LOAN_PLATFORM`（per-shard × per-currency 的 `loanInsuranceFund`，`merge` 后覆盖全部 shard），阈值与告警策略都留在监控侧。引擎内**不加任何告警代码**，理由：

- 这是个**电平**问题不是边沿问题。本节自己的论证就是"负值本身就是平台已垫资的准确金额……直接回答要补多少钱"——轮询恰好回答它，而边沿检测反倒是为了解决"塞进引擎会刷屏"这个由放错位置制造出来的问题。
- **阈值是运营策略，不是资金状态。** 放进复制态意味着每次调阈值都要发 raft 命令、还要进 snapshot 与 `stateHash`，属分类错误。
- LIF 靠人工补仓，周期以小时/天计，轮询间隔的延迟无关紧要。
- 拉取通道本来就齐备（§10 已定"平台侧数据拉不推，不发事件"），引擎内再造一条是重复建设。

**状态：待建。** 引擎侧不会再有告警代码，这块并入后续「外部定时任务拉 report 监控各资金池」的统一建设（借贷池 / 期货 IF / LIF 共用一套拉取 + 阈值 + 告警，不各造一套）。**在那之前 LIF 没有任何告警**，只能靠人主动查报表。

建成后的形态：定时任务拉 `LOAN_PLATFORM` → 对每个 currency 判 `loanInsuranceFund[c] < −阈值` → 告警 → 运营发 `LOAN_IF_DEPOSIT` 补仓（§18.5）。抵押币积压（正值堆高）同一份数据即可看出，处置走 `LOAN_IF_WITHDRAW`。
不采用"相对潜在接管需求"口径：需汇总所有临近强平线的债务，成本高、随行情剧烈波动、且同样依赖喂价。而负值本身就是**平台已垫资的准确金额**，无需预测，并直接回答运营最关心的问题——要补多少钱。按币种配置是因为 LIF 是多币种资产负债表，「USDT 见底但 WBTC 积压」是常态。

### 18.11 待决

- 是否引入外部清算人机制以减轻 LIF 压力（见 §18.12）

### 18.12 业界路线对照

| 路线 | 代表 | 机制 | 与本设计的关系 |
|---|---|---|---|
| **自营订单簿 + 保险基金** | CEX 杠杆/借贷 | 抵押在自家撮合卖，卖不掉由 IF 兜底 | **本设计所选**。闭环可控，但受限于自家深度，冷门币种易卡 |
| **清算人激励** | Aave / Compound | 挂出折价（典型 5–10% bonus）让第三方套利者承接，自行跨市场消化 | **绕开深度问题**——清算人可场外消化，而我们只能在自家簿内找对手 |

我们没有外部清算人这一层缓冲，这正是必须自建 LIF 的原因。若后续需减轻 LIF 压力，可考虑允许做市商以破产价优先承接（独立工程，暂不纳入）。
