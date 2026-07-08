# 现货借贷（Spot Loan）架构设计

---

## 摘要

用户以现货资产作抵押，从交易所自有流动性池借出另一种现货资产用于交易。

**支持两种模式**：
- **Isolated（逐仓）**：一笔贷款绑定一对 (collateralCcy, loanCcy)，抵押专属于该笔
- **Cross（全仓）**：账户级抵押池 + 多笔债务，共享抵押，多币种聚合 LTV

**核心机制**：
- 惰性计息（触发时现算）+ 三级 LTV 阈值（initial / margin call / liquidation）
- 同 cmd 内闭环强平：force-sell IOC 撮合 → 分账 → 关 loan 或 badDebt 兜底
- 复用 `LiquidationEngine` 作为通用 3 lane 引擎（期货 / Isolated 借贷 / Cross 借贷）
- `loanPoolAvailable` / `badDebt` 是 per-shard state，同 shard 内 Isolated + Cross 共享；跨 shard 独立（跟期货 IF 池同款）
- 命名空间 `loan*` 前缀与期货 `margin*` 完全隔离
- **抵押物专用**：loan 抵押（`isolatedLoans.collateralAmount` / `crossLoanCollateral`）只能顶 loan，**不能**顶 futures margin，也不能锁到 spot `exchangeLocked`
- **借出本金普通化**：`LOAN_CREATE / LOAN_CROSS_BORROW` 之后本金进 `accounts[loanCcy]`，跟其他余额同权 —— 可以顶 futures margin / 做现货挂单 / 提现
- 所有**新增 loan 命令**的可用余额校验统一走现有 `calculateLocked(user, ccy)`，禁止手写减法漏掉 exchangeLocked / futures 保证金；futures / spot 现有路径**本来就**走 calculateLocked，本次不需要改

**规模**：新增 3 个顶层类（`IsolatedLoanRecord` + `CrossLoanRecord` + `LoanService`），扩展 `UserProfile` +3 字段 / `RiskEngine` +1 字段 / `CoreSymbolSpecification` +7 字段（含 `loanMaxTermDays`），新增 12 条 raft 命令（10 借贷业务 + 2 池子运营）。Margin Call + Interest Settle + BadDebt 通过 FundEvent 通道 leader-local 广播不入 raft。新系统冷启，无历史 snapshot 兼容负担。

---

## 1. 概述

### 1.1 目标

现有 osl-mm-match 引擎已支持现货撮合（含 `exchangeLocked` 挂单冻结）和期货撮合（含 cross/isolated margin + IF 池 + ADL）。本设计新增现货借贷能力，让用户拿现货资产作抵押，向交易所流动性池借另一种现货资产用于交易。

**目标**：
- 引擎层完整支持 Isolated + Cross 两种借贷模式
- 借还强平资金流全程守恒可验证
- 与期货代码完全隔离（命名空间 + 数据结构 + 强平路径）
- 产品体验对齐 Binance Margin / OKX Cross Margin 主流做法

**非目标**：
- 跨衍生品统一账户（如 OKX Portfolio Margin）——借贷账本与期货保持独立
- 双跳撮合（BTC→USDT→USDC）——借贷币对强制存在对应 spot symbol
- 独立 spot-margin IF 池——badDebt 负记账兜底
- 动态利率（v1 静态利率 + 架构预留）
- VIP tier 利率打折（产品层参数）

### 1.2 关键约束

| 约束 | 说明 |
|---|---|
| 池化借贷 | 出借方是交易所自有池；loan 挂在 user 维度 |
| 强制存在 spot symbol | Isolated 借贷币对必须存在 `BASE=collateralCcy, QUOTE=loanCcy` 的 spot symbol；Cross 每个抵押币必须存在 `XXX/USDT` symbol（估值 + 强平撮合） |
| Cross 基准币 | USDT（多币种聚合估值 numeraire） |
| 惰性计息 | 无定时 tick 命令；仅在还款/强平触发点现算 |
| 同 cmd 内闭环 | force-sell IOC 撮合 + 结算 + 终态在一条 raft 命令 apply 内完成 |
| **抵押物账本隔离** | loan 抵押只服务于对应 loan 的债务偿还，不能同时顶 futures margin，也不落到 spot `exchangeLocked`。校验和 apply 通过 `calculateLocked` 独立叠加实现 |
| **本金普通余额** | 借出本金进 `accounts[loanCcy]` 后不带任何 tag，跟其他普通余额同权 |
| **可用余额单一入口** | **本次新增的**所有 loan 命令的可用余额校验（NSF、抵押可用、还款可用）走现有 `RiskEngine.calculateLocked(user, ccy)`，禁止手写减法；calculateLocked 内部原来就聚合 exchangeLocked + futures 保证金锁定，本设计**只在末尾追加** loan 抵押两项，不动 futures / spot 现有逻辑 |
| **最大贷款期限** | `spec.loanMaxTermDays` 硬性拒绝新贷超期；保证 pending interest 累积始终在 §6.2 保守阈值吸收范围内 |
| **新系统冷启** | 上线前无历史 snapshot；`UserProfile` / `CoreSymbolSpecification` / `LoanService` 序列化不做向后兼容 gate |

### 1.3 术语

- **命名空间隔离**：借贷业务前缀统一 `loan*`，与期货 `margin*` / `MarginMode` / `initMargin` / `extraMargin` 完全不重叠
- **符号规约**：Symbol `BTC/USDT`，`BASE=BTC=collateralCcy`, `QUOTE=USDT=loanCcy`
- **单位**：所有 amount 字段 currencyScale，与 `exchangeLocked` 一致
- **时间**：`lastAccrueTs` / `openedAtTs` 用 `cmd.timestamp`（raft 命令时间戳，跨节点确定性）
- **LTV**：Loan-to-Value = 借出价值 / 抵押价值（bps 表达，6000 bps = 60%）
- **`LOAN_LIQUIDATOR_UID`**：force-sell IOC 匹配时 taker 名义 uid，避免走 `loan.uid` 撞现货 self-trade 保护（对齐期货 `FORCE_LIQUIDATOR_UID`）；跟期货用**同一个** reserved uid 或独立 uid 由 §7 决定，只要跟真实用户 uid 空间不冲突即可

---

## 2. 架构总览

```
                   ┌──────────────────────────────────────────────┐
                   │              客户端 / API 层                   │
                   │  Isolated: LOAN_CREATE / REPAY /                │
                   │            ADD_COLLATERAL / RELEASE_COLLATERAL │
                   │  Cross:    LOAN_CROSS_ADD_COLLATERAL /          │
                   │            WITHDRAW_COLLATERAL / BORROW / REPAY │
                   │  运营:     POOL_DEPOSIT / POOL_WITHDRAW /       │
                   │            symbolSpecUpdate                    │
                   └──────────────────────────────────────────────┘
                                        ↓ Raft
                   ┌──────────────────────────────────────────────┐
                   │           RiskEngine（per-shard）              │
                   │  ┌────────────────────────────────────────┐  │
                   │  │  LoanService                             │  │
                   │  │    State (snapshot):                     │  │
                   │  │      loanPoolAvailable[c] (守恒正)         │  │
                   │  │      loanPoolBorrowed[c]  (utilization)  │  │
                   │  │      badDebt[c]           (守恒负)         │  │
                   │  │      crossLiquidationLtvBps              │  │
                   │  │      crossMarginCallLtvBps               │  │
                   │  │      loanPoolUtilizationCapBps           │  │
                   │  │      poolProcessedExternalIds (幂等)      │  │
                   │  │    Apply 写入 + 静态工具 + orderId helper │  │
                   │  └────────────────────────────────────────┘  │
                   │                                                │
                   │  UserProfile 扩展 3 字段:                       │
                   │    isolatedLoans    : Long → IsolatedLoanRecord │
                   │    crossLoanCollateral : Int → Long           │
                   │    crossLoans       : Long → CrossLoanRecord    │
                   │                                                │
                   │  CoreSymbolSpecification 扩展 7 字段（loan.* + │
                   │    collateralWeightBps + loanMaxTermDays）     │
                   └──────────────────────────────────────────────┘
                              ↓                          ↓
                   ┌──────────────────┐      ┌────────────────────┐
                   │   OrderBook     │      │ LiquidationEngine  │
                   │  IOC force-sell │◀────│  3 lane scanner    │
                   └──────────────────┘      │  1. 期货 positions  │
                                              │  2. isolatedLoans  │
                                              │  3. crossLoans     │
                                              └────────────────────┘
```

**组件职责**（Option 2 二级 dispatch 架构，state 与行为分开）：

- **LoanService**：**纯状态类**。承载 state（池子 / 坏账 / 三阈值 / 幂等表）+ 序列化 / stateHash + 纯函数工具（accrue / OrderId 编码 / metric / Cross LTV 计算）。**不持** RiskEngine ref，无 wire，无 late-bind
- **LoanCommandHandlers**：**命令处理类**（新引入的独立类）。承载 12 条 loan 命令的 apply 业务流（校验 → 状态转移）+ shard filter + POOL 短路 + `dispatch(OrderCommand)` 入口。**持** RiskEngine ref 通过 `engine.getXxx()` 现取 UserProfileService / SymbolSpecificationProvider / lastPriceCache / fees / adjustments / calculateLocked / LoanService state
- **RiskEngine.preProcessCommand 二级 dispatch**：首行判断 `cmd.command.isLoan()`，命中整块委托给 `loanCmdHandlers.dispatch(cmd)`；**主 switch 里永远看不到 loan 命令**，loan 子域边界清晰
- **LiquidationEngine**：从"期货专用"扩展为通用 3 lane 强平引擎。共享 `SimpleScheduledService` 2s tick、in-flight 去重框架、publisher 调用；三 lane 独立扫描独立 publish，无跨 lane 交互
- **UserProfile**：per-user 数据容器。所有借贷 per-user 数据（isolatedLoans / crossLoanCollateral / crossLoans）跟现有 `accounts` / `exchangeLocked` / `positions` 平级挂载
- **CoreSymbolSpecification**：per-symbol 规则型配置。承载 LTV 阈值 / 利率 / 单笔上限 / 抵押折价率——跟期货 `initMargin` / `maintenanceMargin` / `maxLeverage` 同款塞法

**分片架构（关键前提）**：
- RiskEngine 按 `uid % N` 分片，`LoanService` 每个 shard 一份实例
- `loanPoolAvailable` / `loanPoolBorrowed` / `badDebt` 都是 per-shard 独立 state
- Scanner / 借贷 apply / 强平决策全部 shard-local，无跨 shard 通信
- 借贷 apply 内完全闭环（无 IF / ADL），不需要期货那种跨 shard 收集→汇总→执行机制
- 池子跨 shard 不均衡由运营侧手动再平衡应对（详见 §5.10.5）

---

## 3. 领域模型

### 3.1 IsolatedLoanRecord

Isolated 一笔贷款的完整业务凭证。挂在 `UserProfile.isolatedLoans` 上。

```java
public final class IsolatedLoanRecord implements WriteBytesMarshallable, StateHash {

    public long uid;                          // 对齐 SymbolPositionRecord.uid
    public final long loanId;                 // 客户端提供，per-user 唯一（Isolated 命名空间）
    public final int  collateralCcy;
    public final int  loanCcy;
    public final int  rateBps;                // 借时 snapshot 锁定

    public final long openedAtTs;             // 开仓时间（cmd.timestamp），期限检查用

    public long collateralAmount;             // 剩余抵押
    public long outstandingPrincipal;         // 剩余本金
    public long accumulatedInterest;          // 惰性未支付利息
    public long lastAccrueTs;                 // 上次 accrue 时间点

    public IsolatedLoanRecord(long uid, long loanId, int collateralCcy, int loanCcy, int rateBps, long openedAtTs);
    public IsolatedLoanRecord(long uid, BytesIn bytes);

    public boolean isEmpty();                 // 3 个可变字段全 0
    public void validateInternalState();
    @Override public void writeMarshallable(BytesOut bytes);
    @Override public int stateHash();
    @Override public String toString();
}
```

**无 `liquidationCtx`**：Isolated 强平单态、同 cmd 内闭环，无跨命令等待状态。

### 3.2 CrossLoanRecord

Cross 单笔债务凭证。**无抵押字段**——Cross 抵押是账户级池化。

```java
public final class CrossLoanRecord implements WriteBytesMarshallable, StateHash {

    public long uid;
    public final long loanId;                 // 客户端提供，per-user 唯一（Cross 命名空间独立于 Isolated）
    public final int  loanCcy;
    public final int  rateBps;                // 借时 snapshot 锁定
    public final long openedAtTs;             // 开仓时间（cmd.timestamp），期限检查用

    public long outstandingPrincipal;
    public long accumulatedInterest;
    public long lastAccrueTs;

    public CrossLoanRecord(long uid, long loanId, int loanCcy, int rateBps, long openedAtTs);
    public CrossLoanRecord(long uid, BytesIn bytes);

    public boolean isEmpty();
    public void validateInternalState();
    @Override public void writeMarshallable(BytesOut bytes);
    @Override public int stateHash();
    @Override public String toString();
}
```

**无 `liquidationCtx`**：Cross 强平每 tick 独立决策，跨 tick 无状态接力。

### 3.3 UserProfile 扩展

```java
public final class UserProfile {
    // 现有字段
    public final long uid;
    public PositionMode positionMode;
    public final IntObjectHashMap<SymbolPositionRecord> positions;
    public final BoundedLongDedupSet processedExternalIds;
    public final IntLongHashMap accounts;
    public final IntLongHashMap exchangeLocked;
    public UserStatus userStatus;

    // 新增（Isolated 借贷）
    public final LongObjectHashMap<IsolatedLoanRecord> isolatedLoans;

    // 新增（Cross 借贷，展平到顶层跟 accounts/exchangeLocked 平级）
    public final IntLongHashMap crossLoanCollateral;         // 多币种抵押池
    public final LongObjectHashMap<CrossLoanRecord> crossLoans;
}
```

三个新字段全部 per-user，跟 `accounts` / `exchangeLocked` / `positions` 现有 pattern 对齐。

**不加派生 view**：per-user loan 数预期 <10，`calculateLocked` 现算 `Σ loan.collateralAmount + crossLoanCollateral[c]` 跟期货 `for (position : positions)` 同 pattern。

### 3.4 LoanService

对齐 `LiquidationService` 模板：State（snapshot）+ Apply 路径写入 + 静态工具。**不是 scheduler，不 publish 命令**——只在 apply 路径被调。

```java
public class LoanService implements WriteBytesMarshallable, StateHash {

    // ==================================================================
    // State（进 raft snapshot，全局共享）
    // ==================================================================

    @Getter
    private final IntLongHashMap loanPoolAvailable;   // 池子可借出（守恒正 bucket）

    @Getter
    private final IntLongHashMap loanPoolBorrowed;    // 池子已借出（utilization 用，不进守恒）

    @Getter
    private final IntLongHashMap badDebt;             // 强平坏账（守恒负 bucket）

    @Getter
    private int crossLiquidationLtvBps;               // Cross 账户级强平线（默认 8500 = 85%）

    @Getter
    private int crossMarginCallLtvBps;                // Cross 账户级预警线（默认 8000 = 80%）

    @Getter
    private int loanPoolUtilizationCapBps;            // 池子利用率上限（默认 9000 = 90%）

    private final BoundedLongDedupSet poolProcessedExternalIds;   // POOL_DEPOSIT/WITHDRAW 幂等表（per-shard, key = hash(cmdType, externalId)，见 §5.10.3）

    private static final int DEFAULT_CROSS_LIQUIDATION_LTV_BPS = 8500;
    private static final int DEFAULT_CROSS_MARGIN_CALL_LTV_BPS = 8000;
    private static final int DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS = 9000;

    // ==================================================================
    // Injected（updateProvider 注入）
    // ==================================================================

    private UserProfileService userProfileService;
    private SymbolSpecificationProvider specProvider;
    private CurrencySpecificationProvider currencyProvider;
    private IntObjectHashMap<LastPriceCacheRecord> lastPriceCache;

    public LoanService();
    public LoanService(BytesIn bytes);
    public void updateProvider(...);
    @Override public void writeMarshallable(BytesOut bytes);
    @Override public int stateHash();
    @Override public String toString();

    // ==================================================================
    // Isolated 业务 API
    // ==================================================================

    void createIsolatedLoan(long uid, long loanId, int symbolId,
                             long collateralAmount, long principal, long cmdTimestamp);
    void repayIsolatedLoan(long uid, long loanId, long repayAmount, long cmdTimestamp);
    void addIsolatedCollateral(long uid, long loanId, long amount);
    void releaseIsolatedCollateral(long uid, long loanId, long amount);
    void applyIsolatedForceLiquidate(OrderCommand cmd);

    // ==================================================================
    // Cross 业务 API
    // ==================================================================

    void addCrossCollateral(long uid, int currency, long amount);
    void withdrawCrossCollateral(long uid, int currency, long amount);       // 校验剩余总 LTV
    void borrowCross(long uid, long loanId, int loanCcy, long principal, long cmdTimestamp);
    void repayCross(long uid, long loanId, long repayAmount, long cmdTimestamp);
    void applyCrossForceLiquidate(OrderCommand cmd);

    // ==================================================================
    // 池子运营 API（POOL_DEPOSIT / POOL_WITHDRAW 命令入口）
    // ==================================================================

    void handlePoolDeposit(OrderCommand cmd);      // shardId 匹配则加 loanPoolAvailable + 对冲 adjustments
    void handlePoolWithdraw(OrderCommand cmd);     // shardId 匹配则减 loanPoolAvailable + 对冲 adjustments

    // ==================================================================
    // 共享工具（无副作用）
    // ==================================================================

    /** 写路径：触发点调用，写入 loan.accumulatedInterest + lastAccrueTs */
    long accrueTo(IsolatedLoanRecord loan, long now);
    long accrueTo(CrossLoanRecord loan, long now);

    /** 读路径：查询接口用，返回 accrue 到 now 的完整利息（含 pending），不修改 loan */
    public long computeDisplayInterest(IsolatedLoanRecord loan, long now);
    public long computeDisplayInterest(CrossLoanRecord loan, long now);

    /** Cross 账户级 LTV（bps），折 USDT numeraire + collateralWeightBps 折价 */
    long computeCrossAccountLtvBps(UserProfile userProfile);

    /** 动态利率架构预留。v1 直接返回 spec.loanRateBps；v2 基于 utilization 曲线动态计算 */
    public int computeEffectiveRateBps(int loanCurrency);

    /** 池子利用率 bps = borrowed / (available + borrowed) */
    public int computePoolUtilizationBps(int currency);

    // ==================================================================
    // OrderId 编码（'L' 命名空间 + 'S'/'C' subtype 分区，详见 §7.4）
    // ==================================================================

    public static long generateIsolatedForceSellOrderId(IsolatedLoanRecord loan);
    public static long generateCrossForceSellOrderId(long uid, int sellingCcy);
    public static boolean isLoanForceSellOrderId(long orderId);
    public static byte loanForceSellSubtype(long orderId);   // 'S' or 'C'
}
```

**写入职责分工**（Option 2 分拆后）：
- `loanPoolAvailable` / `loanPoolBorrowed` / `badDebt` / `poolProcessedExternalIds` / 三阈值 —— LoanService 内部 state，`LoanCommandHandlers` 通过 `engine.getLoanService().getXxx()` 拿到 getter map 修改
- `UserProfile.isolatedLoans` / `crossLoans` / `crossLoanCollateral` —— per-user 数据，`LoanCommandHandlers` 直接改 UserProfile 上的字段
- `RiskEngine.fees` / `adjustments` —— 借 RiskEngine 的桶，`LoanCommandHandlers` 通过 `engine.getFees()` / `engine.getAdjustments()` 修改

**Snapshot 格式**：LoanService 首次冷启用无参构造（默认三阈值 + 空 bucket）；序列化位置读，无 `readRemaining()` gate（详见 §9.1）。后续 state 字段升级到 v2 前统一走一次冷启 snapshot 迁移或加显式 version byte。

### 3.5 CoreSymbolSpecification 扩展

跟期货 `initMargin` / `maintenanceMargin` / `maxLeverage` 塞在 symbol spec 同款——规则型 config 挂 spec。

```java
public final class CoreSymbolSpecification {
    // 现有全部字段

    // 新增（type=CURRENCY_EXCHANGE_PAIR 才生效）

    // ---- Isolated + Cross 共享 ----
    public final int  loanInitialLtvBps;      // 开仓 LTV 上限；0 = 借贷未启用
    public final int  loanLiquidationLtvBps;  // Isolated 单笔强平触发线（Cross 用 LoanService 全局）
    public final int  loanMarginCallLtvBps;   // Isolated 预警线（< loanLiquidationLtvBps；0 = 关闭）
    public final int  loanRateBps;            // 静态年化利率；0 = 免息
    public final long loanMaxAmount;          // 单笔本金上限；0 = 无上限
    public final int  loanMaxTermDays;        // 最大贷款期限（天）；0 = 无期限限制（不推荐）

    // ---- Cross 专用 ----
    public final int  collateralWeightBps;    // Cross 抵押折价率（BTC=9000 → 90% 计入）
                                              // 0 = 该 currency 不能作 Cross 抵押
                                              // Isolated 不用
}
```

**语义边界**：
- `loanInitialLtvBps == 0` 表达 "该 symbol 不允许借贷"（Isolated 和 Cross 都禁用）
- LTV 阈值排序约束：`loanInitialLtvBps < loanMarginCallLtvBps < loanLiquidationLtvBps`（spec update 时 validate）
- `collateralWeightBps` 挂在该 currency 作为 base 的 spot symbol spec 上（因为 markPrice 也在该 symbol）
- `loanMaxTermDays` 目的是防止长贷 pending interest 累积击穿 §6.2 的保守 LTV 差异吸收阈值。推荐 90d，最大 365d（1 年 = 5% 偏差正好等于典型 initial/liquidation 差距）。0 = 无期限限制，仅为 v1 保留 backdoor，产品配置层默认关闭

Spec 更新走现有 `symbolSpecUpdate` raft 命令，不新增 SpecUpsert 命令。

### 3.6 RiskEngine 扩展

```java
public class RiskEngine {
    // 现有 fees / adjustments / suspends / liquidationService / liquidationEngine

    private LoanService loanService;   // 新增，对齐 liquidationService 挂法
}
```

**不新增 `LoanLiquidationEngine`**——`LiquidationEngine` 扩展为通用 3 lane 引擎，同时处理期货 / Isolated / Cross 三种强平（详见 §7）。

---

## 4. 资金模型

### 4.1 守恒方程

```
accountBalances + extraMargin + exchangeLocked + fees + adjustments
              + suspends + ifBalances + loanPoolAvailable + badDebt = 0
```

现有 7 桶（`accountBalances / extraMargin / exchangeLocked / fees / adjustments / suspends / ifBalances`，见 `TotalCurrencyBalanceReportResult.getGlobalBalancesSum`）之上新增两个 bucket，跟 fees / adjustments / ifBalances 平级（守恒方程在每个 shard 内独立成立）：

| Bucket | 归属 | 符号 | 语义 |
|---|---|---|---|
| `loanPoolAvailable[c]` | LoanService (per-shard) | 正 | 池子可借出（同 shard 内 Isolated + Cross 共用）|
| `badDebt[c]` | LoanService (per-shard) | 负 | 交易所自吸的坏账（underwater 强平）|

**`accountBalances[c]` 定义**（守恒方程用；只减 exchangeLocked 一项，跟现有实现一致）：
```
accountBalances[c] = Σ user (accounts[c] − exchangeLocked[c])
```

只有 `exchangeLocked` 一项在这里减去 —— 因为守恒方程里 `exchangeLocked` 作独立 bucket 承接（`+ exchangeLocked` 在 sum 里补回来），两项一减一加相当于把 `accounts` 拆成"可动用 + spot 挂单锁"两部分。

**loan 冻结的处理**（有意跟 `exchangeLocked` 桶不同款，是这次的取舍）：
- `isolatedLoans.collateralAmount` / `crossLoanCollateral[c]` 都是 per-user 虚锁（accounts 里物理不动、per-user 独立字段承载 flag），**原理上**跟 `exchangeLocked` 一致
- 但本设计**不给 loan 冻结加独立 bucket** —— 守恒方程保持 9 桶（原 7 桶 + loan 新增 2 桶 `loanPoolAvailable / badDebt`）
- 后果：`accountBalances[c]` 值里**包含**用户被 loan flag 锁掉的物理量（如 Alice 抵押 1 BTC，accountBalances[BTC] 仍算 1，即使可动用 = 0）
- 权衡：改动最小 vs 报表侧"全交易所 loan 抵押总量"不能一眼看到，得从 UserProfile 遍历或加查询接口现算

**"可动用余额"由 `calculateLocked` 唯一负责**：
- 引擎里所有 NSF / 校验 / 校验后新增锁定的路径统一走 `accounts[c] − calculateLocked(user, c)`
- `calculateLocked` 里的 loan 抵押分支（§9.2）就是这个虚锁的**唯一体现**
- 查询侧要"用户可动用 c 余额"也走 `accounts − calculateLocked`

**Futures margin 保持现状不动**：`extraMargin` 是期货 ISOLATED 保证金的独立 bucket（守恒方程里独立一项，物理搬走）；CROSS futures margin 在 `accounts` 里通过 `calculateLocked` 的期货分支虚锁 —— 这两条**均属期货范畴，loan 设计不新增也不修改**。

**loan 抵押不能顶 futures margin 的落地机制**：只在 `calculateLocked(user, c)` 里追加两项（loan 抵押扣除）；futures margin 侧的 NSF 校验本身就走 `accounts − calculateLocked`，扩展后 loan 抵押自动作为 lock 扣掉，futures 侧看不到 loan 抵押的余量作可用保证金 —— **不需要改 futures 任何代码**。反向同理。

**不进守恒的项**：
- `outstandingPrincipal` / `accumulatedInterest`：业务记账。借出时 `accounts += principal, loanPoolAvailable −= principal` 两个真实 bucket 互抵；principal 只是"用户欠交易所"的业务标签
- `loanPoolBorrowed`：跟 `loanPoolAvailable` 反向对称（一升一降），只用于 utilization 校验和运营 metric
- `isolatedLoans.collateralAmount` / `crossLoanCollateral[c]`：**per-user 虚锁 flag**，accounts 里物理不动。跟 exchangeLocked 原理一致但**有意不给独立桶承接**（见上）；只在 `calculateLocked` 里作扣项体现

### 4.2 关键不变量

按重要度排序：

1. **池子非负**：`loanPoolAvailable[c] ≥ 0`
2. **借出对偶**：`Σ isolatedLoans.outstandingPrincipal(loanCcy==c) + Σ crossLoans.outstandingPrincipal(loanCcy==c) == loanPoolBorrowed[c]`
3. **抵押覆盖 Isolated**：`Σ user.isolatedLoans.filter(collateralCcy==c) → loan.collateralAmount ≤ user.accounts[c] − user.exchangeLocked[c]`（回到原文档表述；futures margin 由 §4.1 的 `extraMargin` 独立桶和 `calculateLocked` 各自兜住，不需要在 loan 不变量里显式提）
4. **抵押覆盖 Cross**：`user.crossLoanCollateral[c] ≤ user.accounts[c] − user.exchangeLocked[c]`（同上，futures margin 不入这条不变量）
5. **Isolated LTV 边界**：任何 IsolatedLoan 满足 `outstandingPrincipal × 10000 < collateralAmount × markPrice × loanLiquidationLtvBps`
6. **Cross LTV 边界**：任何有非空 crossLoans 的 user 满足 `computeCrossAccountLtvBps(user) < crossLiquidationLtvBps`
7. **池子利用率上限**：新借入不得使 `loanPoolBorrowed[c] × 10000 > (loanPoolAvailable[c] + loanPoolBorrowed[c]) × loanPoolUtilizationCapBps`
8. **Isolated LTV 阈值排序**：`loanInitialLtvBps < loanMarginCallLtvBps < loanLiquidationLtvBps`
9. **Cross LTV 阈值排序**：`crossMarginCallLtvBps < crossLiquidationLtvBps`
10. **SUSPEND 守卫**：`accounts.allZero() && exchangeLocked.allZero() && isolatedLoans.isEmpty() && crossLoanCollateral.allZero() && crossLoans.isEmpty()`
11. **Dust sweep 隔离**：SUSPEND dust 路径只能 sweep `exchangeLocked`，绝不误扫 loan 抵押
12. **scale 一致性**：所有 amount 字段全 currencyScale
13. **守恒方程**：所有 bucket 求和为零
14. **loan 抵押作为 calculateLocked 扣项**：`isolatedLoans.collateralAmount` 和 `crossLoanCollateral[c]` **必须**通过 `calculateLocked` 里的 loan 分支返回给所有调用方 —— 这样 futures / spot 现有代码（沿用 calculateLocked）自动看不到 loan 抵押作可用余量。**不改** futures / spot 侧任何代码，只靠 calculateLocked 扩展一处实现双向隔离
15. **贷款期限约束**：任意 IsolatedLoan / CrossLoan 满足 `cmd.timestamp − openedAtTs ≤ spec.loanMaxTermDays × 86400 × 1e9`（LOAN_CREATE / BORROW 时 openedAtTs 记录；scanner 定期校验，超期触发强制 accrue 一次或直接强平）

每条不变量建议落 `ITConservationFuzz` 风格随机测试。

---

## 5. 业务流程

### 5.1 命令清单

```
# Isolated（5 条）
LOAN_CREATE                     (uid, loanId, symbolId, collateralAmount, principal)
LOAN_REPAY                      (uid, loanId, repayAmount)
LOAN_ADD_COLLATERAL             (uid, loanId, amount)
LOAN_RELEASE_COLLATERAL         (uid, loanId, amount)
LOAN_FORCE_LIQUIDATE            (uid, loanId, size, price, orderId)   # 由 LiquidationEngine publish

# Cross（5 条）
LOAN_CROSS_ADD_COLLATERAL       (uid, currency, amount)
LOAN_CROSS_WITHDRAW_COLLATERAL  (uid, currency, amount)
LOAN_CROSS_BORROW               (uid, loanId, loanCcy, principal)
LOAN_CROSS_REPAY                (uid, loanId, repayAmount)
LOAN_CROSS_FORCE_LIQUIDATE      (uid, sellingCcy, targetLoanId, size, price, orderId)

# 池子运营（2 条，独立命令；cmd.uid 承载 shardId，见 §5.10）
POOL_DEPOSIT                    (currency, amount)   # cmd.uid = shardId
POOL_WITHDRAW                   (currency, amount)   # cmd.uid = shardId

# 复用现有
LTV/rate/weight 更新             → 走 symbolSpecUpdate
```

**全局约定（所有 loan 命令）**：
- **`externalId` 隐含 payload 字段**：所有用户维度 loan 命令（LOAN_CREATE / REPAY / *_COLLATERAL / CROSS_BORROW / CROSS_REPAY 等）跟现有 spot/futures 命令一样携带 `externalId`；apply 前置一步 `processedExternalIds.contains(externalId) → SUCCESS`（`USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME`）。后续 §5.x 每个命令的校验列表不再重复列这一步
- **`cmd.uid` = 用户 uid**（用户维度命令）或 `shardId`（POOL_DEPOSIT/WITHDRAW，见 §5.10）
- **强平命令（LOAN_FORCE_LIQUIDATE / LOAN_CROSS_FORCE_LIQUIDATE）** 无 externalId —— scanner 生成，幂等靠 orderId 编码 + apply 时 `loan == null` 检测

Margin Call / Interest Settle / BadDebt FundEvent 通过**扩展现有** `FundEventsHelper` 加三个 `sendLoan*Event` 方法发送（不入 raft，跟期货 `sendMarginAlertEvent` 同款 leader-local ring-buffer 通道；详见 §9.5）。

**为什么 `POOL_DEPOSIT/WITHDRAW` 独立而非复用 `BALANCE_ADJUSTMENT`**：`loanPoolAvailable` 是 per-shard state，需要精细指定注资到哪个 shard 应对不均衡（详见 §5.10）。复用 `BALANCE_ADJUSTMENT + reserved uid` 会因 `uid % N` 路由永远打到同一 shard。

### 5.2 Isolated 借（LOAN_CREATE）

**校验**（顺序敏感，先便宜后昂贵）：
1. `spec.type == CURRENCY_EXCHANGE_PAIR`
2. `spec.loanInitialLtvBps > 0`
3. `isolatedLoans.containsKey(loanId) == false`
4. `principal > 0 && collateralAmount > 0`
5. `spec.loanMaxAmount == 0 || principal ≤ spec.loanMaxAmount`
6. `markPrice > 0`
7. **LTV**：`principal × 10000 ≤ collateralAmount × markPrice × spec.loanInitialLtvBps`
8. **抵押可用**：`accounts[collateralCcy] − calculateLocked(user, collateralCcy) ≥ collateralAmount`
9. `loanPoolAvailable[loanCcy] ≥ principal`
10. **池子利用率上限**：`(loanPoolBorrowed + principal) × 10000 ≤ (loanPoolAvailable + loanPoolBorrowed) × loanPoolUtilizationCapBps`

**状态转移**：
```
effectiveRateBps = loanService.computeEffectiveRateBps(loanCcy)
loan = new IsolatedLoanRecord(uid, loanId, collateralCcy, loanCcy,
                              effectiveRateBps, cmd.timestamp)
loan.collateralAmount = collateralAmount
loan.outstandingPrincipal = principal
loan.lastAccrueTs = cmd.timestamp
userProfile.isolatedLoans.put(loanId, loan)
userProfile.accounts[loanCcy] += principal
loanPoolAvailable[loanCcy] -= principal
loanPoolBorrowed[loanCcy]  += principal
```

**校验步骤 8 语义**：`calculateLocked(user, collateralCcy)` 是引擎唯一"锁定量聚合"入口 —— 现有实现里已含 exchangeLocked + futures 保证金锁定分支；本设计**在 calculateLocked 尾部追加 loan 抵押扣项**（§9.2），扩展后本次新增抵押量必须落在 `accounts − calculateLocked` 之内。上下游代码零改动，靠 calculateLocked 单点扩展让 loan 抵押跟已有 exchangeLocked / futures 保证金天然互斥。

**守恒**（1 BTC 抵押借 30000 USDT，方案 B 语义）：BTC 侧 accounts 不动、loan.collateralAmount 只是 loan record flag 不进桶，ΔBTC = 0；USDT 侧 +30000 accounts / −30000 loanPoolAvailable，Sum = 0 ✓。（loan 抵押作为 calculateLocked 扣项影响用户 BTC 可动用量，但不影响守恒方程各桶 sum）

### 5.3 Isolated 还（LOAN_REPAY）

**校验**：
1. `loan != null`
2. `loan.uid == uid`
3. `repayAmount >= 0`

**状态转移**（利息优先）：
```
accrueTo(loan, cmd.timestamp)
payoff = loan.outstandingPrincipal + loan.accumulatedInterest
actualRepay = (repayAmount == 0 || repayAmount >= payoff) ? payoff : repayAmount

free = accounts[loanCcy] - calculateLocked(user, loanCcy)
if (free < actualRepay) reject USER_MGMT_ACCOUNT_BALANCE_INSUFFICIENT

interestPart = min(actualRepay, loan.accumulatedInterest)
principalPart = actualRepay - interestPart

accounts[loanCcy] -= actualRepay
loan.accumulatedInterest -= interestPart
loan.outstandingPrincipal -= principalPart
loanPoolAvailable[loanCcy] += principalPart
loanPoolBorrowed[loanCcy]  -= principalPart
fees[loanCcy] += interestPart

if (loan.isEmpty()) isolatedLoans.remove(loanId)
```

部分还款不释放抵押。调抵押走 `LOAN_ADD_COLLATERAL` / `LOAN_RELEASE_COLLATERAL`。

**守恒**（全还 30100 USDT = 30000 本金 + 100 利息）：ΔUSDT −30100 accounts / +30000 pool / +100 fees，Sum=0 ✓。

### 5.4 Isolated 补抵押（LOAN_ADD_COLLATERAL）

**校验**：
1. `loan != null && loan.uid == uid`
2. `amount > 0`
3. **抵押可用**：`accounts[loan.collateralCcy] − calculateLocked(user, loan.collateralCcy) ≥ amount`

**状态转移**：
```
loan.collateralAmount += amount
// accounts 不动（抵押派生扣）
```

用于用户在币价下跌时主动补抵押降 LTV，避免走到强平。

### 5.5 Isolated 减抵押（LOAN_RELEASE_COLLATERAL）

**校验**：
1. `loan != null && loan.uid == uid`
2. `amount > 0 && amount ≤ loan.collateralAmount`
3. `markPrice > 0`
4. **减后 LTV 仍 < liquidation**（用户风险自负，允许操作到 marginCall 以上）：
   ```
   newCollateral = loan.collateralAmount − amount
   if (loan.outstandingPrincipal × 10000 ≥ newCollateral × markPrice × spec.loanLiquidationLtvBps)
       reject LOAN_LTV_TOO_HIGH_AFTER_RELEASE
   ```
   注：跟 initial 边界解绑，避免用户借完立刻在 initial 附近就一分抵押都撤不了；对齐 Binance Margin 允许用户在 initial < LTV < liquidation 区间操作但需接受被预警和加速被强平的风险

**状态转移**：
```
loan.collateralAmount -= amount
```

用于用户在币价上涨时抽走多余抵押。

### 5.6 Cross 加抵押（LOAN_CROSS_ADD_COLLATERAL）

**校验**：
1. `spec.collateralWeightBps > 0`（该币可作 Cross 抵押）
2. `amount > 0`
3. `accounts[currency] − calculateLocked(user, currency) ≥ amount`

**状态转移**：
```
userProfile.crossLoanCollateral.addToValue(currency, amount)
```

### 5.7 Cross 撤抵押（LOAN_CROSS_WITHDRAW_COLLATERAL）

**校验**：
1. `crossLoanCollateral[currency] >= amount`
2. **撤抵押后账户级 LTV 仍 < crossLiquidationLtvBps**（用户风险自负；跟 Isolated §5.5 同款松绑，允许撤到 marginCall 上方但拒绝直接撤到强平）

**状态转移**：
```
userProfile.crossLoanCollateral.addToValue(currency, -amount)
```

### 5.8 Cross 借（LOAN_CROSS_BORROW）

**校验**：
1. `spec (for loanCcy) != null && spec.loanInitialLtvBps > 0`
2. `crossLoans.containsKey(loanId) == false`
3. `principal > 0`
4. `spec.loanMaxAmount == 0 || principal ≤ spec.loanMaxAmount`
5. **借后账户级 LTV 仍 ≤ loanInitialLtvBps**（模拟 debts ∪ 新债，重算 LTV；超线 reject `LOAN_LTV_TOO_HIGH_AFTER_BORROW`）
6. `loanPoolAvailable[loanCcy] ≥ principal`
7. 池子利用率上限（同 LOAN_CREATE）

**状态转移**：
```
effectiveRateBps = loanService.computeEffectiveRateBps(loanCcy)
loan = new CrossLoanRecord(uid, loanId, loanCcy, effectiveRateBps, cmd.timestamp)
loan.outstandingPrincipal = principal
loan.lastAccrueTs = cmd.timestamp
userProfile.crossLoans.put(loanId, loan)
userProfile.accounts[loanCcy] += principal
loanPoolAvailable[loanCcy] -= principal
loanPoolBorrowed[loanCcy]  += principal
```

### 5.9 Cross 还（LOAN_CROSS_REPAY）

同 Isolated 还款逻辑（利息优先分账、还本金部分同步更新 pool），但**不释放抵押**（Cross 抵押是账户级，不绑单笔）。想减抵押走 `LOAN_CROSS_WITHDRAW_COLLATERAL`。

### 5.10 池子注资 / 抽资

`loanPoolAvailable` / `badDebt` 都是 **per-shard state**（跟期货 `LiquidationService.notionals` 同款设计）。用户按 `uid % N` 分片固定，借贷需求分布可能不均——某 shard 池子紧张而其他 shard 有余是常态。运营侧需要**精细指定注资到哪个 shard**。

#### 5.10.1 独立命令 + shardId 通过 `cmd.uid` 承载

```
POOL_DEPOSIT  (currency, amount, externalId)   # cmd.uid = shardId, amount > 0
POOL_WITHDRAW (currency, amount, externalId)   # cmd.uid = shardId, amount > 0
```

**路由**：复用现有 `IF_DEPOSIT / IF_WITHDRAW` 的 pattern —— `cmd.uid` 承载 `shardId`（不是真实 uid），`RiskEngine` 用 `(int)cmd.uid == currentShardId` self-filter（见 `ExchangeApi.java:1112,1123` + `RiskEngine.java:594-606`）。这样不新增 dispatch 层，raft 命令仍走同一 chain 广播，各 shard 消费者自行 short-circuit。

#### 5.10.2 Apply 逻辑

```
handlePoolDeposit(cmd):
  shardId = (int) cmd.uid
  if (shardId != currentShardId) return SUCCESS               // 幂等 no-op（其他 shard 短路）
  if (cmd.amount <= 0) reject LOAN_INVALID_AMOUNT
  if (poolProcessedExternalIds.contains(dedupKey(cmd)))
      return USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME

  loanPoolAvailable[cmd.currency] += cmd.amount
  adjustments[cmd.currency]       -= cmd.amount
  poolProcessedExternalIds.add(dedupKey(cmd))

handlePoolWithdraw(cmd):
  shardId = (int) cmd.uid
  if (shardId != currentShardId) return SUCCESS
  if (cmd.amount <= 0) reject LOAN_INVALID_AMOUNT
  if (loanPoolAvailable[cmd.currency] < cmd.amount) reject LOAN_POOL_INSUFFICIENT
  if (poolProcessedExternalIds.contains(dedupKey(cmd)))
      return USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME

  loanPoolAvailable[cmd.currency] -= cmd.amount
  adjustments[cmd.currency]       += cmd.amount
  poolProcessedExternalIds.add(dedupKey(cmd))
```

**参数级错路防护**（上层 API 校验层）：`shardId ∈ [0, N)` 检查在 `ExchangeApi` publish 之前做；apply 层遇到 `shardId != currentShardId` 静默 SUCCESS 视为正确的其他 shard 短路，**不**记 warn（因为所有 shard 都会消费同一命令，只有目标 shard 应用；把其他 shard 的短路当异常会刷屏）。但 `shardId ∉ [0, N)` 或负值等参数非法应在 publish 层拒绝并给 `LOAN_POOL_WRONG_SHARD`。

`loanPoolBorrowed` 不动（注资/抽资不产生借出）。

#### 5.10.3 幂等 key 加 cmd 类型 tag

`LoanService` 维护独立 `poolProcessedExternalIds : BoundedLongDedupSet`（跟 UserProfile 的 dedup 表分离，避免混淆）。**幂等 key = `hash(cmdType, externalId)`**——DEPOSIT / WITHDRAW 用同一 externalId 也不会互相误判为已处理（避免上层 externalId 序列跨 cmd 类型复用踩雷）。

**dedupKey 计算**：
```java
long dedupKey(OrderCommand cmd) {
    int typeTag = (cmd.command == POOL_DEPOSIT) ? 1 : 2;
    return LongHashFunction.xx().hashLongs(new long[]{ typeTag, cmd.externalId });
}
```

**注意**：不同 shard 有独立 dedup 表；同一 (type, externalId) 可以在不同 shard 各生效一次（因为路由不同）。运营侧的批量注资脚本应当为每个 shard 生成独立 externalId 序列。

#### 5.10.4 守恒验证

**POOL_DEPOSIT +100000 USDT to shard A**：

| Bucket (shard A 视角) | ΔUSDT |
|---|---:|
| loanPoolAvailable | +100000 |
| adjustments | −100000 |
| **Sum** | **0** ✓ |

#### 5.10.5 分片不均衡的运营策略

- **初始注资**：均匀分布到各 shard（`总池子 / N` 到每 shard）
- **监控**：dashboard 聚合展示各 shard `loanPoolAvailable[c]` / `loanPoolBorrowed[c]` / `utilization` / `badDebt`
- **预警**：单 shard `utilization > 80%` 时报警
- **调剂**：运营通过 `POOL_DEPOSIT/WITHDRAW` 手动再平衡（从 utilization 低的 shard 抽走补到高的）
- **不做引擎侧自动跨 shard 调剂**：跟期货 IF 池同款设计原则，跨 shard 一致性成本远高于收益

### 5.11 幂等约定

**统一原则**：所有用户维度 loan 命令走**同一套**幂等 key —— `UserProfile.processedExternalIds`（复用现有 spot/futures 已有的表）。`loanId` 只是业务主键（防止业务上创建两笔同 ID 的 loan），不承担幂等去重职责。风格一致，接入方一个规则通吃。

| 命令 | 幂等 key | 业务主键校验 | 命中处理 |
|---|---|---|---|
| `LOAN_CREATE` | `processedExternalIds(cmd.externalId)` | `isolatedLoans.containsKey(loanId)` | 幂等命中 → `USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME`；business 命中 → `LOAN_ALREADY_EXISTS` |
| `LOAN_CROSS_BORROW` | `processedExternalIds(cmd.externalId)` | `crossLoans.containsKey(loanId)` | 同上 |
| `LOAN_REPAY` / `LOAN_CROSS_REPAY` | `processedExternalIds(cmd.externalId)` | — | 幂等命中 → `USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME` |
| `LOAN_ADD_COLLATERAL` / `LOAN_RELEASE_COLLATERAL` | `processedExternalIds(cmd.externalId)` | — | 同上 |
| `LOAN_CROSS_ADD_COLLATERAL` / `WITHDRAW_COLLATERAL` | `processedExternalIds(cmd.externalId)` | — | 同上 |
| `LOAN_FORCE_LIQUIDATE` / `LOAN_CROSS_FORCE_LIQUIDATE` | orderId 编码 + apply 时 `loan == null` 检测 | — | apply 时 SUCCESS no-op（scanner-published，无 externalId） |
| `POOL_DEPOSIT/WITHDRAW` | `LoanService.poolProcessedExternalIds.hash(cmdType, externalId)`（per-shard 独立表） | — | 返回 `USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME` |

---

## 6. 利息模型

### 6.1 惰性计息

利息不定时结算，只在必要触发点现算——省 raft 命令、避免大规模 tick apply 延迟。

**触发点仅两个**：
1. `LOAN_REPAY` / `LOAN_CROSS_REPAY` apply 前
2. `LOAN_FORCE_LIQUIDATE` / `LOAN_CROSS_FORCE_LIQUIDATE` apply 前

**公式**：
```
elapsed_ns = now − loan.lastAccrueTs
interest = elapsed_ns × loan.outstandingPrincipal × loan.rateBps / (YEAR_NS × 10000)
loan.accumulatedInterest += interest
loan.lastAccrueTs = now
```
`YEAR_NS = 365L × 24 × 3600 × 1_000_000_000L`。溢出保护走 `CoreArithmeticUtils.truncMulDiv` 128-bit fallback。

Isolated 和 Cross 用同一公式（重载 `accrueTo(IsolatedLoanRecord)` / `accrueTo(CrossLoanRecord)`）。守恒零变化——`accumulatedInterest` 不进守恒方程。

### 6.2 Scanner LTV 与利息的分离

Scanner 判 LTV 时**只用 `outstandingPrincipal`，不含 `accumulatedInterest` 也不含 pending accrue**：

```
if (loan.outstandingPrincipal × 10000 ≥ collateralAmount × markPrice × liquidationLtvBps) trigger
```

若每次 2s tick 扫 100 万 loan 都算 pending interest → CPU 爆。用**保守的 `loanLiquidationLtvBps` 吸收偏差**。

**偏差量化**（5% 年化）：

| 持仓天数 | pending interest / principal 偏差 |
|---:|---:|
| 30d | 0.41% |
| 90d | 1.23% |
| 180d | 2.47% |
| 365d | 5.00% |

阈值调保守 2-5%（如 85% 而非 90%）覆盖偏差。借贷主流是短线交易，长贷罕见。

**硬性期限上限**：`spec.loanMaxTermDays` 强制拒绝超期贷。LOAN_CREATE / LOAN_CROSS_BORROW 时 `loan.openedAtTs = cmd.timestamp`；scanner 每 tick 校验 `cmd.timestamp − openedAtTs ≤ spec.loanMaxTermDays × 86400 × 1e9`，超期视同 liquidation 触发。保证 pending interest 累积始终在 §6.2 表格覆盖范围内（90d 下 1.23%，365d 下 5%，跟保守 LTV 差异对齐）。

新贷校验入口（LOAN_CREATE / LOAN_CROSS_BORROW）**不需要**校验期限（新贷从 0 起）；期限约束仅在 scanner 触发路径。

### 6.3 触发时的完整时间线

Alice 借 30000 USDT，抵押 1 BTC，rate 5% 年化：

| 时点 | 事件 | markPrice | outstandingPrincipal | accumulatedInterest | Scanner LTV | 引擎动作 |
|---|---|---:|---:|---:|---:|---|
| T0 | 借出 | 50000 | 30000 | 0 | 60% ✓ | 建 loan |
| T0+30d | 静默 | 50000 | 30000 | **0（未触发）** | 60% ✓ | 无 |
| T0+90d | 币价暴跌 | 35000 | 30000 | **0** | **85.71% ≥ 85%** → 触发 | publish FORCE_LIQUIDATE |
| T0+90d+50ms | Apply | 35000 | 30000→0 | **0→370→0** | — | apply 里 `accrueTo` 补 370；卖 1 BTC → 34825 净额；还 370 利息 + 30000 本金；剩 4455 退用户 |

**关键**：链上从 T0 到 T0+90d 期间 `accumulatedInterest` 一直是 0；强平 apply 那一瞬间一次性算出 370 并计入 totalDebt。

### 6.4 Underwater 场景

同场景币价跌到 25000：
```
accrueTo → accumulatedInterest = 370
totalDebt = 30370

卖 1 BTC → filledNotional = 25000
liquidationFee = 125（0.5%）
netForRepay = 24875

// 利息优先分账
interestPart = min(24875, 370) = 370          # 利息全收
principalPart = 24875 − 370 = 24505           # 本金部分收回

shortfall = 30370 − 24875 = 5495              # 全是本金差额
badDebt[USDT] −= 5495
loanPoolAvailable[USDT] += 30000              # 池子视角完整收回本金
loanPoolBorrowed[USDT]  −= 30000
fees[USDT] += 370 + 125 = 495                 # 利息 + 强平费入 fees
```

分账顺序（利息优先）保证 `badDebt` 只反映本金损失，对齐会计惯例。

### 6.5 查询响应默认含 pending interest

若查询接口只返回存储的 `accumulatedInterest`，用户可能看到"欠 30000"实际还款要付 30370——利息突然冒出来会造成信任危机。

**引擎侧契约**：所有借贷查询接口默认返回 accrue 到当前的完整利息。

**做法**：
- `LoanService.computeDisplayInterest(loan, now)` = `loan.accumulatedInterest + accrueDelta(loan, now)`
- 计算方法跟 `accrueTo` 一致，但不写入 loan record（read-only）
- 查询接口 response 中的 `interest` 字段一律走 `computeDisplayInterest`
- 查询接口 response 中的 `totalDebt` = `outstandingPrincipal + computeDisplayInterest`

**读写对称**：
- `accrueTo(loan, cmd.timestamp)`：写路径，触发点调用，写入 loan
- `computeDisplayInterest(loan, now)`：读路径，任何查询调用，不改状态

**服务端 API 契约（下游必须遵守）**：不允许下游只读 `loan.accumulatedInterest` 直接返回给客户端。上线前 API review 单独 gate。

---

## 7. 强平机制

### 7.1 三 lane 引擎架构

`LiquidationEngine` 扩展为通用 3 lane 引擎（不新建 `LoanLiquidationEngine`）：
1. **期货 lane**（现有）：扫 `userProfile.positions`
2. **Isolated 借贷 lane**（新增）：扫 `userProfile.isolatedLoans`
3. **Cross 借贷 lane**（新增）：扫 `userProfile.crossLoans`（非空即算总 LTV）

**收益**：
- 单一强平入口，运维只看一个 scheduler / 一份日志 / 一套 metric
- 复用 `SimpleScheduledService` 2s tick、in-flight 去重框架、publisher
- 避免多 scheduler thread 竞争
- 三 lane 各自独立扫描独立 publish，无跨 lane 交互

**扫描顺序与跨 lane 干扰语义**：三 lane 在同一 `checkLiquidations()` per-user 循环内**按 futures → isolated → cross** 顺序执行；每 lane 独立读 tick 开始时的 in-memory state 决策。同一 user 同 tick 同时触发多 lane 强平是**允许的**——每 lane 独立 publish 命令，apply 顺序由 raft log 决定：
- 前一条 apply 让后一条的触发条件消失时，后一条 apply 时通过 `loan == null` 或 `position.isEmpty` 转 SUCCESS no-op
- in-flight set 是 leader-local 的，onApplied 回调 remove；下 tick scanner 用最新 state 重新决策
- 无跨 lane 状态传递，无跨 tick 状态接力

**LiquidationEngine 改动**：
- `updateProvider` 新增 `LoanService` 参数
- 新增 2 个 in-flight set（typed，跟现有 futures set 平级）：
  ```java
  MultiReaderSet<SymbolPositionRecord> inFlightLiquidationCmd;    // 现有
  MultiReaderSet<Long>                 inFlightIsolatedLoanLiq;   // key = loanId
  MultiReaderSet<Long>                 inFlightCrossLoanLiq;      // key = uid
  ```
- `publishTracked` 现有签名 `(ApiCommand, SymbolPositionRecord)`；新增两个 typed 重载：
  ```java
  publishTrackedIsolated(ApiCommand cmd, long loanId);
  publishTrackedCross   (ApiCommand cmd, long uid);
  ```
  各自内部 add / try-publish / catch-remove，跟现有 `publishTracked` 结构对称
- 新增 margin call 节流本地 map（per-loanId / per-uid ≥ 5 min）
- `checkLiquidations()` 在现有 per-user forEach 内加两个 lane 调用；`SymbolType.isFuturesContract(spec.type)` 早退分支保留（futures lane 用），loan lane 独立分支
- 借贷 apply 内完全闭环，无需扩展 `nextLiquidationState`（不需要 IF/ADL 推进）

### 7.2 Isolated 借贷 lane

```
checkIsolatedLoanLiquidations(userProfile):
  for each loan in userProfile.isolatedLoans.values():
    spec = specProvider.get(symbolIdOf(loan.collateralCcy, loan.loanCcy))
    priceRecord = lastPriceCache.get(spec.symbolId)
    if (priceRecord == null || priceRecord.markPrice == 0) continue

    // 期限超限 → 直接触发（一并处理，避免长贷 pending interest 击穿保守阈值）
    isTermExpired = spec.loanMaxTermDays > 0
        && (currentTickTs - loan.openedAtTs) > spec.loanMaxTermDays * 86400L * 1_000_000_000L

    ltvBpsScaled = loan.outstandingPrincipal × 10000
    thresholdLiq  = loan.collateralAmount × priceRecord.markPrice × spec.loanLiquidationLtvBps

    if (isTermExpired || ltvBpsScaled >= thresholdLiq) {
      if (inFlightIsolatedLoanLiq.contains(loan.loanId)) continue
      publishTrackedIsolated(LOAN_FORCE_LIQUIDATE {
          uid: loan.uid,
          loanId: loan.loanId,
          size: loan.collateralAmount,
          price: 0L,                                       // 0 = 吃穿深度直到 size 填满或深度耗尽
          orderId: LoanService.generateIsolatedForceSellOrderId(loan)
      }, loan.loanId)
      continue
    }

    // 预警分支
    if (spec.loanMarginCallLtvBps > 0) {
      thresholdWarn = loan.collateralAmount × priceRecord.markPrice × spec.loanMarginCallLtvBps
      if (ltvBpsScaled >= thresholdWarn)
        maybeEmitMarginCallEvent(loan, ISOLATED, ltvBps)   // 节流 per-loanId ≥ 5 min
    }
```

**"卖光抵押"决策**：Isolated 单笔本身是绑定关系，卖光最简。剩余（若有）通过关 loan 返回用户可支配。

**price = 0 语义**：force-sell 是 ASK IOC，`price = 0` 表示"接受任意成交价"（吃穿 bid 侧深度直到 size 满足或深度耗尽）。跟期货 FORCE_LIQUIDATION 的 IOC 价格策略对齐——避免只吃最上层 bid 导致 partial fill 一直触发下 tick。orderbook 无深度则本 tick filledSize=0，loan 保留等下 tick（scanner 自动重新决策）。

### 7.3 Cross 借贷 lane

Cross 强平决策每 tick 独立计算，无跨命令状态接力：

```
checkCrossLoanLiquidations(userProfile):
  if (userProfile.crossLoans.isEmpty()) return

  // 期限超限：任一 crossLoan 超期 → 强制 targetLoanId = 最老那笔
  earliestExpired = findEarliestExpiredCrossLoan(userProfile)   // 或 null

  ltvBps = loanService.computeCrossAccountLtvBps(userProfile)

  if (earliestExpired != null || ltvBps >= loanService.getCrossLiquidationLtvBps()) {
    if (inFlightCrossLoanLiq.contains(userProfile.uid)) return

    sellingCcy    = pickCollateralToSell(userProfile)      // 见下方 tiebreak 规则
    targetLoanId  = (earliestExpired != null)
                       ? earliestExpired.loanId
                       : pickLoanToRepay(userProfile)      // 见下方 tiebreak 规则
    sellSize      = calculateSizeToDeleverage(userProfile, sellingCcy, ltvBps)
    orderId       = LoanService.generateCrossForceSellOrderId(userProfile.uid, sellingCcy)
    publishTrackedCross(LOAN_CROSS_FORCE_LIQUIDATE {
        uid: userProfile.uid, sellingCcy, targetLoanId, size: sellSize,
        price: 0L, orderId
    }, userProfile.uid)
    return
  }

  // 预警分支
  if (ltvBps >= loanService.getCrossMarginCallLtvBps())
    maybeEmitMarginCallEvent(userProfile, CROSS, ltvBps)   // 节流 per-uid ≥ 5 min
```

**跨节点确定性 tiebreak**（三级排序全部严格）：

*`pickCollateralToSell(user)`*：
```
sort user.crossLoanCollateral entries by:
  1. spec.collateralWeightBps DESC      (先卖折价最少的，等价流动性最高)
  2. USDT-value DESC                    (等权重下先卖持仓大的)
  3. currency ASC                       (等值下 currency id 小的优先)
return top1.currency
```

*`pickLoanToRepay(user)`*：
```
sort user.crossLoans.values by:
  1. rateBps DESC                       (利率高的优先还)
  2. outstandingPrincipal DESC          (等利率下还金额大的)
  3. loanId ASC                         (等本金下 loanId 小的优先)
return top1.loanId
```

*`calculateSizeToDeleverage(user, sellingCcy, ltvBps)`*：目标 LTV = `crossInitialLtvBps × 0.9`；反算 sellSize 时用当前 sellingCcy 折价和 markPrice 做 close-form 求解。**每 tick 只发一次 LOAN_CROSS_FORCE_LIQUIDATE**——单币抵押体量可能不够把整体 LTV 拉到目标，允许多 tick 收敛，避免同 tick 内发多条命令带来的排队 / in-flight 竞争。

**partial deleverage 而非卖光**：对齐 Binance/OKX 主流；剩余抵押退回用户可支配。避免过度清算。

**跨 tick 无接力**：apply 完 in-flight 通过 publisher onApplied 清；下 tick 独立重扫决策。Failover 后新 leader 从 scratch 决策。

### 7.4 强平命令与 OrderId 编码

**命令 payload**：
```
LOAN_FORCE_LIQUIDATE {
    uid, loanId, size, price, orderId
}

LOAN_CROSS_FORCE_LIQUIDATE {
    uid, sellingCcy, targetLoanId, size, price, orderId
}
```

**OrderId 编码**（`'L'` 命名空间 + `'S'/'C'` subtype；避开跟期货 `'I' (0x49)` IF、`'A' (0x41)` ADL 语义混淆）：
```
| 63....56 | 55...48 | 47..........24 | 23........4 | 3....0 |
|  'L' 0x4C | subtype | payload 24bit  | uidHash 20   | ts 4   |

'L'           : loan force sell 命名空间
'S' / 'C'     : Single(Isolated) / Cross subtype
                  'S' 0x53 —— 独占，跟期货 IF 'I'/ADL 'A' 首字节隔离
                  'C' 0x43 —— 独占
payload 24bit :
  Isolated   → loanId 低 24 bit
  Cross      → sellingCcy 24 bit
uidHash 20bit : 防同用户不同 loan/ccy 撞
ts 4bit       : 短周期唯一（配合 subtype/payload 保总体唯一）
```

```java
public static long generateIsolatedForceSellOrderId(IsolatedLoanRecord loan) {
    long payload = loan.loanId & 0xFFFFFFL;
    long uidHash = (loan.uid * 31 + 17) & 0xFFFFFL;
    long ts = (System.currentTimeMillis() / 1000) & 0xFL;
    return (0x4CL << 56) | (0x53L << 48) | (payload << 24) | (uidHash << 4) | ts;
}

public static long generateCrossForceSellOrderId(long uid, int sellingCcy) {
    long payload = sellingCcy & 0xFFFFFFL;
    long uidHash = (uid * 31 + 17) & 0xFFFFFL;
    long ts = (System.currentTimeMillis() / 1000) & 0xFL;
    return (0x4CL << 56) | (0x43L << 48) | (payload << 24) | (uidHash << 4) | ts;
}
```

审计追溯：日志中 orderId 高 8 bit = `'L'` 即 loan force-sell；次高字节 `'S'`/`'C'` 区分模式；payload 反查 loanId 或 sellingCcy。

**跟现有 orderId 空间的非冲突性**：`LiquidationService.generateIFOrderId` 用 `'I' 0x49` 作顶字节、`generateADLOrderId` 用 `'A' 0x41` 作顶字节、期货 FORCE `generateLiquidationOrderId` 顶字节 = `symbol >> 24`（典型 0x00）。loan 强平首字节 `'L' 0x4C` 独占空间；次字节 subtype `'S' 0x53` / `'C' 0x43` 都不复用任何已知 tag。

### 7.5 Isolated Apply 流程

**撮合 & 结算契约（跟期货 FORCE 对齐）**：
- `LOAN_LIQUIDATOR_UID` 是 **reserved uid**，无 `UserProfile` 实例，不参与正常 spot 结算路径
- `orderbook.processIOC(uid = LOAN_LIQUIDATOR_UID, ...)` 只做**maker 侧**正常结算（maker `accounts` 加/减、maker fee 走正常 taker/maker fee split），并**产出 matcher events** 供 loan apply 消费；**taker 侧结算全部跳过**（不加/减 LOAN_LIQUIDATOR_UID 的 accounts、不算 taker fee）
- loan apply **手动**根据 matcher events 处理 `loan.uid` 的账户变动（下方 pseudo-code 里的 `accounts[loan.collateralCcy] -= filledSize` / `accounts[loan.loanCcy] += netForRepay` 等）
- Liquidation fee 由 loan apply 从 filledNotional 显式扣入 `fees` 桶，取代正常 taker fee
- 结果：撮合守恒 = maker 侧 auto settle（-filledSize collateral / +filledNotional loan）+ loan apply 手动 settle（+filledSize collateral net 抵消 maker 拿走的 = -filledSize / -netForRepay + repay = -filledSize / +125 fee etc.）—— 详见 §7.7 终态验算

```
LoanService.applyIsolatedForceLiquidate(cmd):
  loan = userProfile.isolatedLoans.get(cmd.loanId)
  if (loan == null) return SUCCESS                     // 幂等 no-op

  accrueTo(loan, cmd.timestamp)                        // 补利息到 now
  totalDebt = loan.outstandingPrincipal + loan.accumulatedInterest

  matcherEvents = orderbook.processIOC(
      symbol: symbolIdOf(loan.collateralCcy, loan.loanCcy),
      action: ASK, size: cmd.size, price: cmd.price,          // price = 0 吃穿深度
      uid: LOAN_LIQUIDATOR_UID,                               // 特殊 uid，避 self-trade
      orderId: cmd.orderId)

  filledSize = Σ matcherEvents.size
  filledNotional = Σ matcherEvents.price × size

  // Liquidation fee 从 filledNotional 扣入 fees
  liquidationFeeAmount = spec.isFixedFee()
      ? spec.liquidationFee
      : truncMulDiv(filledNotional, spec.liquidationFee, spec.feeScaleK)
  netForRepay = filledNotional - liquidationFeeAmount

  // 抵押→现金：用户手里 collateral 变成 loanCcy
  accounts[loan.collateralCcy] -= filledSize
  loan.collateralAmount        -= filledSize
  accounts[loan.loanCcy]       += netForRepay
  fees[loan.loanCcy]           += liquidationFeeAmount

  // 还债：利息优先，但**受限于用户 accounts 可动用量**（S2 clamp）
  // 借出的本金已经普通化进 accounts，用户可能已经拿去做 futures margin，
  // 这里不能强行透支 futures 保证金 → capped repay
  freeLoanCcy   = accounts[loan.loanCcy] - calculateLocked(user, loan.loanCcy)
  repayCap      = max(0, min(netForRepay, freeLoanCcy))
  repay         = min(repayCap, totalDebt)
  interestPart  = min(repay, loan.accumulatedInterest)
  principalPart = repay - interestPart

  accounts[loan.loanCcy] -= repay
  loan.accumulatedInterest -= interestPart
  loan.outstandingPrincipal -= principalPart
  loanPoolAvailable[loan.loanCcy] += principalPart
  loanPoolBorrowed[loan.loanCcy]  -= principalPart
  fees[loan.loanCcy] += interestPart

  // 终态判定
  remainingDebt = loan.outstandingPrincipal + loan.accumulatedInterest
  remainingCollateral = loan.collateralAmount

  if (remainingDebt > 0 && remainingCollateral == 0) {
      // underwater：抵押耗尽仍欠债 → badDebt 兜底
      badDebt[loan.loanCcy] -= remainingDebt
      loanPoolAvailable[loan.loanCcy] += loan.outstandingPrincipal
      loanPoolBorrowed[loan.loanCcy]  -= loan.outstandingPrincipal
      fees[loan.loanCcy] += loan.accumulatedInterest
      loan.outstandingPrincipal = 0
      loan.accumulatedInterest = 0
      userProfile.isolatedLoans.remove(loan.loanId)
      fundEventsHelper.sendLoanBadDebtEvent(loan.uid, loan.loanCcy, remainingDebt, ISOLATED, loan.loanId)
  } else if (remainingDebt == 0) {
      userProfile.isolatedLoans.remove(loan.loanId)      // 剩余抵押派生释放
  } else {
      // 部分成交但仍有抵押，loan 保留等下 tick
  }
```

**S2 clamp 语义**：`repay = min(netForRepay, totalDebt, freeLoanCcy)`。如果用户已经把借来的钱拿去做 futures margin，force-sell 到手的 loanCcy 不够还全部债务时，缺口进 `badDebt`。这样避免"loan 强平反过来把 futures 保证金抽干导致 futures NSF 崩"的连锁反应。业务上 badDebt 是交易所吸收的成本，vs 让 futures 位子先牺牲 —— 选前者更清晰、不需要跨 lane 协调。

### 7.6 Cross Apply 流程

同 §7.5 的**撮合 & 结算契约**（`LOAN_LIQUIDATOR_UID` reserved uid 跳过 spot 结算；maker 侧走正常路径；loan apply 手动处理 `loan.uid` 账户）。区别仅在于账户扣除位置——Cross 从 `crossLoanCollateral[sellingCcy]` 派生扣，Isolated 从 `loan.collateralAmount` 派生扣，二者都同时更新 `accounts` 保持守恒。

```
LoanService.applyCrossForceLiquidate(cmd):
  targetLoan = userProfile.crossLoans.get(cmd.targetLoanId)
  if (targetLoan == null) return SUCCESS               // 幂等 no-op

  // 补所有 debt 的利息（不只 targetLoan）
  for (debt : userProfile.crossLoans.values()) {
      accrueTo(debt, cmd.timestamp)
  }
  targetDebtTotal = targetLoan.outstandingPrincipal + targetLoan.accumulatedInterest

  matcherEvents = orderbook.processIOC(
      symbol: symbolIdOf(cmd.sellingCcy, targetLoan.loanCcy),
      action: ASK, size: cmd.size, price: cmd.price,          // price = 0 吃穿深度
      uid: LOAN_LIQUIDATOR_UID,                               // 特殊 uid，避 self-trade
      orderId: cmd.orderId)

  filledSize = Σ matcherEvents.size
  filledNotional = Σ matcherEvents.price × size

  sellSpec = specProvider.get(symbolIdOf(cmd.sellingCcy, targetLoan.loanCcy))
  liquidationFeeAmount = sellSpec.isFixedFee()
      ? sellSpec.liquidationFee
      : truncMulDiv(filledNotional, sellSpec.liquidationFee, sellSpec.feeScaleK)
  netForRepay = filledNotional - liquidationFeeAmount

  // 抵押池扣（Cross 抵押不落 accounts 之外的独立桶；filledSize 是 crossLoanCollateral 减少量）
  userProfile.crossLoanCollateral.addToValue(cmd.sellingCcy, -filledSize)
  accounts[cmd.sellingCcy]     -= filledSize
  accounts[targetLoan.loanCcy] += netForRepay
  fees[targetLoan.loanCcy]     += liquidationFeeAmount

  // 还 targetLoan：利息优先，受限于用户 accounts 可动用量（S2 clamp）
  freeLoanCcy   = accounts[targetLoan.loanCcy] - calculateLocked(user, targetLoan.loanCcy)
  repayCap      = max(0, min(netForRepay, freeLoanCcy))
  repay         = min(repayCap, targetDebtTotal)
  interestPart  = min(repay, targetLoan.accumulatedInterest)
  principalPart = repay - interestPart

  accounts[targetLoan.loanCcy] -= repay
  targetLoan.accumulatedInterest -= interestPart
  targetLoan.outstandingPrincipal -= principalPart
  loanPoolAvailable[targetLoan.loanCcy] += principalPart
  loanPoolBorrowed[targetLoan.loanCcy]  -= principalPart
  fees[targetLoan.loanCcy] += interestPart

  if (targetLoan.isEmpty()) userProfile.crossLoans.remove(cmd.targetLoanId)

  // 抵押耗尽但还有其他 debt → 账户级 underwater
  if (userProfile.crossLoanCollateral.allZero() && !userProfile.crossLoans.isEmpty()) {
      for (debt : userProfile.crossLoans.values()) {
          long remaining = debt.outstandingPrincipal + debt.accumulatedInterest
          badDebt[debt.loanCcy] -= remaining
          loanPoolAvailable[debt.loanCcy] += debt.outstandingPrincipal
          loanPoolBorrowed[debt.loanCcy]  -= debt.outstandingPrincipal
          fees[debt.loanCcy] += debt.accumulatedInterest
          fundEventsHelper.sendLoanBadDebtEvent(userProfile.uid, debt.loanCcy, remaining, CROSS, debt.loanId)
      }
      userProfile.crossLoans.clear()
  }
```

Cross Apply 也享受 S2 clamp（同 Isolated 语义）：user 拿借来的钱做了 futures margin 后，卖抵押换回 loanCcy 也可能不够偿还 —— 缺口进 `badDebt` 通过 `sendBadDebtEvent` 通道广播观测事件。

### 7.7 三种终态守恒

**终态 A（抵押够、债务清、剩余抵押退回）**：ΣΔ 全 0 ✓

**终态 B（underwater）**：`loanPoolAvailable` 视角完整收回本金，交易所通过 `badDebt` 负记账吸收本金损失；利息通过 `fees` 收（利息永远收得到）。

**终态 C（部分成交，loan 保留）**：loan 继续挂在 map，等下 tick scanner 独立决策再战。

### 7.8 撮合 symbol 约束

**方案**：借贷币对必须存在对应 spot symbol：
- Isolated：`BASE=collateralCcy, QUOTE=loanCcy`
- Cross：每个抵押币 + `USDT` 存在（估值 + 强平撮合都走 XXX/USDT）

**排除**：双跳（BTC→USDT→USDC）永远不做，避免多步原子撮合能力。

---

## 8. 错误与边界

### 8.1 CommandResultCode 新增枚举

`LOAN_*` 前缀跟期货 `MARGIN_*` 完全隔离。

| 枚举 | 触发场景 |
|---|---|
| `LOAN_NOT_ENABLED` | `spec.loanInitialLtvBps == 0` |
| `LOAN_ALREADY_EXISTS` | loanId 已存在（Isolated 或 Cross）|
| `LOAN_NOT_FOUND` | loanId 不存在 |
| `LOAN_LTV_TOO_HIGH` | 开仓 LTV 超线（LOAN_CREATE / Isolated 单笔开仓）|
| `LOAN_LTV_TOO_HIGH_AFTER_BORROW` | Cross 借后账户级 LTV 超 `loanInitialLtvBps`（LOAN_CROSS_BORROW 步骤 5）|
| `LOAN_LTV_TOO_HIGH_AFTER_RELEASE` | 减 Isolated 抵押后 LTV 超 `loanLiquidationLtvBps`（LOAN_RELEASE_COLLATERAL）|
| `LOAN_USER_SUSPENDED` | 用户已 SUSPEND 状态下提交任何 LOAN_* 命令（§8.5）|
| `LOAN_COLLATERAL_EXCEEDS_LOAN` | 减抵押量 > loan.collateralAmount |
| `LOAN_POOL_INSUFFICIENT` | 池子不够 / POOL_WITHDRAW 抽资超 |
| `LOAN_POOL_WRONG_SHARD` | POOL_DEPOSIT/WITHDRAW 路由到错误 shard（apply 时静默 SUCCESS，此枚举保留给上层参数校验用）|
| `LOAN_COLLATERAL_INSUFFICIENT` | 抵押品可用余额不够 |
| `LOAN_MARKPRICE_NOT_READY` | markPrice 缺失或 0 |
| `LOAN_INVALID_AMOUNT` | amount ≤ 0 |
| `LOAN_UID_MISMATCH` | loan.uid ≠ cmd.uid |
| `LOAN_COLLATERAL_NOT_ALLOWED` | `spec.collateralWeightBps == 0` |
| `LOAN_CROSS_LTV_TOO_HIGH_AFTER_WITHDRAW` | 撤 Cross 抵押后 LTV 超线 |
| `LOAN_PRINCIPAL_EXCEEDS_LIMIT` | `principal > spec.loanMaxAmount` |
| `LOAN_POOL_UTILIZATION_EXCEEDED` | 借出后池子利用率超线 |
| `USER_MGMT_ACCOUNT_BALANCE_INSUFFICIENT`（复用） | 还款时用户 loanCcy 不够 |
| `USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME`（复用） | externalId 幂等命中 |
| `SUCCESS`（复用） | 正常成功 or 幂等 no-op |

### 8.2 价格相关边界

| 场景 | 处理 |
|---|---|
| `markPrice == 0`（symbol 未开始成交） | LOAN_CREATE / LOAN_CROSS_BORROW reject；scanner skip |
| Cross 某抵押币 `XXX/USDT` markPrice 缺失 | scanner 跳过该 user，下 tick 重试 |
| `markPrice` 陈旧 | 不做时效性校验，依赖 feed 保活 |
| `bidPrice == 0` | fallback markPrice；再兜底 `1` |
| Force-sell 时 orderbook 深度全 0 | matcherEvents 空，`filledSize == 0`；loan 保留等下 tick |

### 8.3 数值溢出

| 场景 | 处理 |
|---|---|
| LTV 校验 `principal × 10000` 溢出 | `Math.multiplyExact` + 128-bit fallback |
| Cross 抵押聚合估值溢出 | 逐个乘完再累加 |
| `accrueTo` 三乘积溢出 | `CoreArithmeticUtils.truncMulDiv` |
| `principal + interest` 溢出 | `Math.addExact` 早抛 |

### 8.4 时序与幂等

| 场景 | 处理 |
|---|---|
| `now < loan.lastAccrueTs`（时钟倒退） | `elapsed_ns = max(0, ...)`，interest = 0 |
| Force-sell publish 后 loan 已被 REPAY 清 | apply 时 `loan == null` → SUCCESS |
| Failover 后 scanner 重扫 | in-flight set 进程级，新 leader 从空起步；orderId ts 位区分新老命令 |
| 同 loanId 两次 CREATE / BORROW | reject `LOAN_ALREADY_EXISTS` |
| 两次 REPAY（第二次 loan 已清） | reject `LOAN_NOT_FOUND` |
| Scanner 重复 publish FORCE_LIQUIDATE | 第二次 `loan == null` no-op |

### 8.5 SUSPEND

| 场景 | 处理 |
|---|---|
| user 有 loans / collateral 时 SUSPEND | reject（守卫判空失败） |
| SUSPEND 后 LOAN_* 命令 | apply 时检查 `userStatus == SUSPEND` → reject `LOAN_USER_SUSPENDED` |
| Dust sweep 误扫 loan 抵押 | 严禁；dust sweep 白名单只含 `exchangeLocked` |

### 8.6 Margin Call 事件的 best-effort 语义

Margin Call FundEvent 通过 `FundEventsHelper.sendLoanMarginCallEvent`（新增方法，跟现有 `sendMarginAlertEvent` 同一 helper）→ `ApiSystemLiquidationNotify` → `LiquidationCmdPublisher`（`ExchangeRuntime.overrideLiquidationCmdPublisher:42-66`）走 leader 本地 ringbuffer，**不入 raft**。这意味着：

- **Leader 换届会丢事件**：切换过程中 in-flight 的 warning 不会重发；新 leader 从空的节流本地 map 起步，下一 tick 才重新扫描，之间的 warning 缺口 ≤ 2s（一 tick 间隔）+ failover 窗口
- **产品侧兜底**：UI / 通知服务不能依赖 event push 作为唯一预警渠道；必须通过 `SingleUserReportQuery` 或类似查询接口周期性拉取 LTV 状态。查询接口按 §6.5 契约返回 `computeDisplayInterest` 现算利息
- **契约表达**：文档 SDK 一律标注 Margin Call event 为 "at-most-once, best-effort"，不承诺送达

同款语义适用于 §7.5 / §7.6 新增的 `sendBadDebtEvent`（v1 观测事件）—— best-effort，不入 raft，运维用来审计的兜底还是靠 badDebt bucket 本身（`badDebt[c]` 是 raft snapshot 一部分）+ 结构化 log。

### 8.7 BadDebt 观测（v1 就带）

`sendBadDebtEvent` 是**观测事件**，不是命令。触发点：`applyIsolatedForceLiquidate` / `applyCrossForceLiquidate` 内 underwater 分支执行 `badDebt[c] -= remainingDebt` 时同步触发。

**通道**：跟 Margin Call 同款 FundEvent → leader-local ring-buffer，不入 raft。

**payload**：`(uid, loanCcy, badDebtDelta, mode: ISOLATED|CROSS, loanId)`。

**运维栈**：
- 结构化 log：`badDebt.incurred uid=... ccy=... amount=... mode=... loanId=... orderId=... shardId=...`
- Metric：`loan_bad_debt_incurred_total{currency, mode, shard}` counter；`loan_bad_debt_outstanding{currency, shard}` gauge
- 权威真值：`badDebt[c]` 桶本身（进 raft snapshot），事件+log 用来还原每笔来源

`POOL_BADDEBT_REFUND` 命令留在 Phase 2；v1 运营手动通过 `POOL_DEPOSIT` 冲抵 badDebt 桶（作为 offset 抵消，业务上按赞助登记）。

---

## 9. 与现有系统集成

### 9.1 序列化 / stateHash

**新系统冷启，不做向后兼容**。`UserProfile` / `CoreSymbolSpecification` / `LoanService` 的序列化格式**直接**在末尾追加新字段，位置读；上线前无历史 snapshot 需要兼容，因此无需 version byte / `readRemaining()` gate（现有 `UserProfile.java:70-89` / `CoreSymbolSpecification.java:63-79` 都不用 gate，本次跟随同一风格）。

**UserProfile 新增字段（追加末尾）**：
```java
public UserProfile(BytesIn bytesIn) {
    // 现有字段（uid / positionMode / positions / processedExternalIds /
    //          accounts / userStatus / exchangeLocked）
    ...
    this.exchangeLocked = SerializationUtils.readIntLongHashMap(bytesIn);

    // 新字段直接位置读
    this.isolatedLoans      = SerializationUtils.readLongHashMap(bytesIn,
                                    b -> new IsolatedLoanRecord(uid, b));
    this.crossLoanCollateral = SerializationUtils.readIntLongHashMap(bytesIn);
    this.crossLoans         = SerializationUtils.readLongHashMap(bytesIn,
                                    b -> new CrossLoanRecord(uid, b));
}
```

**LoanService**：`writeMarshallable` 顺序 = `loanPoolAvailable, loanPoolBorrowed, badDebt, crossLiquidationLtvBps, crossMarginCallLtvBps, loanPoolUtilizationCapBps, poolProcessedExternalIds`。构造 `LoanService(BytesIn)` 按同顺序读，无 gate。首次冷启由 RiskEngine 通过 `new LoanService()` 无参构造初始化（默认三个常量）。

**stateHash**：
- `UserProfile.stateHash` += `stateHash(isolatedLoans)` + `crossLoanCollateral.hashCode()` + `stateHash(crossLoans)`
- `LoanService.stateHash` = `Objects.hash(stateHash(loanPoolAvailable), stateHash(loanPoolBorrowed), stateHash(badDebt), crossLiquidationLtvBps, crossMarginCallLtvBps, loanPoolUtilizationCapBps, poolProcessedExternalIds.stateHash())`
- `RiskEngine.stateHash` += `loanService.stateHash()`
- `IsolatedLoanRecord.stateHash` 显式覆盖 10 字段（uid / loanId / collateralCcy / loanCcy / rateBps / openedAtTs / collateralAmount / outstandingPrincipal / accumulatedInterest / lastAccrueTs）
- `CrossLoanRecord.stateHash` 显式覆盖 8 字段（uid / loanId / loanCcy / rateBps / openedAtTs / outstandingPrincipal / accumulatedInterest / lastAccrueTs）

`inFlightLiquidationCmd` / `inFlightIsolatedLoanLiq` / `inFlightCrossLoanLiq` 三个 set 和 margin call 节流 map 不进 stateHash（进程级、跟 engine 同生死）。

### 9.2 calculateLocked（唯一可用余额入口）

```java
public long calculateLocked(UserProfile up, int currency) {
    long locked = 0;
    // ==== 以下为现有分支，本次不改，只是原样保留 ====
    for (SymbolPositionRecord pos : up.positions) {
        if (pos.currency == currency) locked += calculateLockedMargin(pos, ...);   // 现有 futures 保证金锁定
    }
    locked += up.exchangeLocked.get(currency);                                     // 现有 spot 挂单锁定
    // ==== 以下为 loan 新增，追加到末尾 ====
    for (IsolatedLoanRecord loan : up.isolatedLoans) {                             // Isolated 抵押冻结
        if (loan.collateralCcy == currency) locked += loan.collateralAmount;
    }
    locked += up.crossLoanCollateral.get(currency);                                // Cross 抵押冻结
    return locked;
}
```

**唯一入口 + 末尾追加**：本次改动**仅仅**是在 `calculateLocked` 末尾追加两个 loan 抵押扣项；现有 futures 保证金分支 / spot exchangeLocked 分支保持字面不变。所有下游调用方（30 处 + `RiskEngine` 的 futures/spot 命令处理）无需改代码，自动把 loan 抵押视为已冻结的锁定量。

**下游影响面**（实测 `grep calculateLocked` 结果）：
- `RiskEngine.java` 22 处（NSF gate / order place / order cancel / cross margin / adjust margin / adjust leverage / place spot / withdraw）
- `FundingFeeCommandProcessor.java` 2 处
- `IFCommandProcessor.java` 2 处
- `ADLCommandProcessor.java` 4 处
- 合计 **30 处**下游全部走 `calculateLocked`，扩展后**自动**把新增两项作为锁定源纳入所有 NSF / withdraw 校验

**直接读/写 `exchangeLocked` 的 ~18 处**（`RiskEngine.java:351/395/780/829` 读 + `RiskEngine.java:837/1371/1416/1451/1515/1563` 写；`LiquidationService.java:320` / `FundEventsHelper.java:157` / `LiquidationEngine.java:258` / `SingleUserReportQuery.java:132` / `TotalCurrencyBalanceReportQuery.java:103` 读；`RiskEngine.java:444/453/459/465` SUSPEND dust；`UserProfileService.java:136` SUSPEND check）**不需要**动 —— 它们只操作 `exchangeLocked` 桶本身，loan 抵押走 loan record + `crossLoanCollateral` 独立字段，跟 `exchangeLocked` 完全解耦。

**G3 语义靠 `calculateLocked` 落地**：因为 futures margin 的 NSF 校验也走 `accounts − calculateLocked`，扩展后 loan 抵押那两项作为 lock 减去 → futures 侧永远看不到 loan 抵押的余量作可用保证金。反过来，`LOAN_CREATE / LOAN_CROSS_ADD_COLLATERAL` 校验步骤 8/3 也是同 `accounts − calculateLocked` 判可用 → 抵押不会把 futures margin 那部分算作可用。**双向自动隔离**。

### 9.3 SUSPEND 守卫

```java
if (!up.accounts.allZero()
    || !up.exchangeLocked.allZero()
    || !up.isolatedLoans.isEmpty()
    || !up.crossLoanCollateral.allZero()
    || !up.crossLoans.isEmpty()) {
    return SUSPEND_NOT_ALLOWED;
}
```

### 9.4 LiquidationEngine 扩展

- `LiquidationService` 相关代码保持不动
- `updateProvider` 参数加 `LoanService`
- 新增 2 个 in-flight set（`MultiReaderSet<Long> inFlightIsolatedLoanLiq`（key=loanId）+ `MultiReaderSet<Long> inFlightCrossLoanLiq`（key=uid））+ margin call 节流本地 map
- 新增 2 个 typed publishTracked 重载：`publishTrackedIsolated(cmd, loanId)` / `publishTrackedCross(cmd, uid)`；跟现有 `publishTracked(cmd, SymbolPositionRecord)`（`LiquidationEngine.java:433-441`）结构对称
- `checkLiquidations()` 加两个 lane 调用（在现有 per-user forEach 内并列）
- 保持 scanner 只读、不改 replicated state 的约束（跟现有 futures lane 一致）

### 9.5 FundEventsHelper 扩展（loan 事件复用现有 helper）

**决定**：loan 事件（Margin Call / Interest Settle / BadDebt）**不**新建 `LoanEventsHelper`，直接在现有 `FundEventsHelper` 上加三个 `sendLoan*Event` 方法 + 三个 `FundEventType` enum tag。理由：
- Loan 事件本质跟期货 margin alert 是**同类**（都是 leader-local 观测事件、bypass raft、best-effort），使用同一 helper 语义上一致
- 事件通道（`ApiSystemLiquidationNotify` → `LiquidationCmdPublisher`）跟期货完全共用，独立 helper 只会重复 wiring
- FundEvent 类型 tag 加在 `FundEventType` enum 里就能区分下游路由，不需要独立命名空间

**新增方法（挂在 `FundEventsHelper`）**：
- `sendLoanMarginCallEvent(uid, mode, loanId|null, ltvBps, thresholdBps)`
- `sendLoanInterestSettleEvent(uid, mode, loanId, interestSettled, currency)`
- `sendLoanBadDebtEvent(uid, currency, badDebtDelta, mode, loanId)`

**FundEventType enum 新增 3 个 tag**：
- `LOAN_MARGIN_CALL`：预警事件
- `LOAN_INTEREST_SETTLE`：还款/强平时同步 emit，让下游把利息从 fees 桶里拆出来作利息收入统计
- `LOAN_BAD_DEBT_INCURRED`：见 §8.7

**通道 + 语义**：三类事件全部走 leader-local FundEvent → `LiquidationCmdPublisher` bypass raft（跟期货 `sendMarginAlertEvent` 同款）；best-effort 语义详见 §8.6 / §8.7。

---

## 10. 关键设计决策

| 决策 | 选定 | 理由 |
|---|---|---|
| **抵押物账本隔离** | loan 抵押不能顶 futures margin；借出本金进 accounts 后普通化 | G3 决策；靠 `calculateLocked` 唯一入口自动双向隔离，无需专门 gate |
| **可用余额校验唯一入口** | 全部 loan 命令走 `accounts − calculateLocked` | 避免手写减法漏 futures margin / 其他 loan 抵押；memory `takerBaseLocked` 教训 |
| **序列化兼容策略** | 新系统冷启，不做 version byte / readRemaining gate | 现有 UserProfile / CoreSymbolSpecification 也是位置读；新字段追加末尾 |
| 支持模式 | Isolated + Cross 并行 | 一次实现两种模式，共享基础设施 |
| 强平引擎 | 复用 `LiquidationEngine` 扩 3 lane | 单一强平入口，共享 scheduler / 去重框架 |
| Loan 主数据挂哪 | `UserProfile.isolatedLoans` / `crossLoans` | 对齐 positions 挂 UserProfile |
| Cross 抵押池挂哪 | `UserProfile.crossLoanCollateral` 展平 | 跟 accounts / exchangeLocked 平级 |
| loanId 谁发号 | 客户端提供，Isolated / Cross 命名空间独立 | 对齐 orderId pattern，省全局 counter |
| 派生 view collateralLocked | 不做 | per-user loan 数少，calculateLocked 现算 |
| Isolated LTV 阈值 | `CoreSymbolSpecification.loanLiquidationLtvBps` 三级（initial / marginCall / liquidation）| 规则型 config 挂 spec，对齐 Binance 三级 |
| **减/撤抵押校验阈值** | 减后 LTV < `loanLiquidationLtvBps`（Isolated）/ `crossLiquidationLtvBps`（Cross）| 允许用户在 initial < LTV < liquidation 区间操作，用户风险自负，对齐 Binance |
| **最大贷款期限** | `CoreSymbolSpecification.loanMaxTermDays`（默认 90d，最大 365d） | 硬性拒绝超期贷；保证 pending interest 累积不击穿 §6.2 保守阈值 |
| Cross 强平阈值 | `LoanService.crossLiquidationLtvBps` 全局 | 账户级不属于单 symbol |
| Cross Margin Call 阈值 | `LoanService.crossMarginCallLtvBps` 全局 | 跟强平阈值一起 |
| Cross 抵押折价 | `CoreSymbolSpecification.collateralWeightBps` per-currency | 通过该币 spot symbol spec 承载 |
| 单笔本金上限 | `CoreSymbolSpecification.loanMaxAmount` | 防单一大户吸干池子 |
| 池子利用率上限 | `LoanService.loanPoolUtilizationCapBps` 全局默认 90% | 防挤兑 |
| 池子已借出跟踪 | `LoanService.loanPoolBorrowed` 进 snapshot 不进守恒 | utilization 校验 + 运营 metric |
| 池子 / badDebt 挂哪 | `LoanService` 内 state | 对齐 LiquidationService 拥有 IFNotional |
| 池子共享 | per-shard 内 `loanPoolAvailable` / `badDebt` Isolated + Cross 共用；跨 shard 独立 | 交易所池子不区分模式；跨 shard 独立跟期货 IF 池同款 |
| 分片架构 | RiskEngine 按 uid % N 分片；LoanService per-shard 一份 | 跟撮合分片一致；借贷 apply 完全 shard-local，无跨 shard 通信 |
| 利率模型 | v1 静态 + 架构预留 `computeEffectiveRateBps` | v2 升级动态利率 API 稳定 |
| 利息计算模式 | 惰性 accrue | 触发点仅 REPAY / FORCE_LIQUIDATE apply 前，省 tick 命令 |
| Scanner LTV 算 pending interest | 不算，只用 principal | 100 万 loan 每 2s 扫都算爆 CPU；保守阈值吸收 5% 偏差 |
| 分账顺序 | 利息优先 | badDebt 只反映本金损失，对齐会计惯例 + Binance/OKX |
| Liquidation Fee | 从 filledNotional 扣入 fees | 区分用户主动还 vs 被动强平成本 |
| Margin Call 通道 | 扩现有 `FundEventsHelper` 加 `sendLoanMarginCallEvent`，leader-local FundEvent 不入 raft | 跟期货 `sendMarginAlertEvent` 同 helper 同通道；避免重复 wiring |
| Margin Call 节流 | 本地 map，per-loan/uid ≥ 5 min | 避免 scanner 2s 一次刷屏 |
| Isolated 强平状态机 | 单态、同 cmd 内闭环 | 无跨命令等待 |
| Cross 强平状态机 | 每 tick 独立决策，无 ctx | 决策是当前 state 纯函数，跨 tick 无接力 |
| 强平走 raft 命令 | 必须 | Scanner leader-only，follower 需 apply 一致 |
| 借贷需不需要 IF/ADL | 不需要 | 强平钱从对手方同步支付，underwater 进 badDebt |
| Isolated 卖多少抵押 | 卖光 | 单笔绑定关系，最简 |
| Cross 卖多少抵押 | partial deleverage（LTV 回到 initial × 0.9）| 对齐 Binance/OKX，避免过度清算 |
| Cross 卖哪个 / 还哪个 | 三级 tiebreak：卖 weight DESC → value DESC → currency ASC；还 rate DESC → principal DESC → loanId ASC | 严格确定性，跨节点一致 |
| **Cross deleverage 单 tick 收敛** | 每 tick 一条 LOAN_CROSS_FORCE_LIQUIDATE，多 tick 迭代到目标 LTV | 避免同 tick 多命令排队 + in-flight 竞争 |
| **force-sell IOC 价格** | `price = 0`（吃穿深度） | 跟期货 FORCE_LIQUIDATION 一致，避免只吃最上层 bid 导致 partial fill |
| **force-sell taker uid** | `LOAN_LIQUIDATOR_UID`（跟期货 FORCE 特殊 uid 同款方案） | 避免 loan.uid 撞现货 self-trade 保护 |
| **repay clamp (S2)** | `repay = min(netForRepay, totalDebt, accounts[loanCcy] − calculateLocked)`；缺口进 badDebt | 借来的钱可能已经进 futures margin；避免 loan 强平反向抽干 futures 保证金 |
| Underwater 兜底 | badDebt 负记账 + FundEvent 观测事件 | 池子视角完整收回本金；v1 就带 metric + log 便于运营追溯 |
| force-sell orderId 编码 | `'L'` tag + `'S'/'C'` subtype | 独占字节空间，避开期货 IF `'I'` / ADL `'A'` 语义混淆 |
| 撮合 symbol 约束 | 强制存在 spot symbol | Isolated 走 collateral/loan pair；Cross 走 XXX/USDT |
| Cross 基准币 | USDT | LTV 聚合估值 numeraire |
| Scanner 触发方式 | SimpleScheduledService 2s | 对齐 LiquidationEngine |
| 池子注资 | 独立 `POOL_DEPOSIT`/`POOL_WITHDRAW` 命令，`cmd.uid` 承载 `shardId`（跟 IF_DEPOSIT/WITHDRAW 同款模式） | `loanPoolAvailable` 是 per-shard state；复用现有 `cmd.uid` 承载 shardId + `RiskEngine` self-filter 机制（`RiskEngine.java:594-606`），不需要新增 dispatch 层 |
| **池子幂等 key** | `hash(cmdType, externalId)` per-shard 独立表 | 加 cmdType tag 避免 DEPOSIT/WITHDRAW 复用同 externalId 时的意外去重 |
| **用户维度 loan 命令幂等** | 统一走 `UserProfile.processedExternalIds` + `externalId`；loanId 只作业务主键 | 风格一致，接入方一个规则通吃；避免文档 §5.11 之前"CREATE 靠 loanId、REPAY 靠 externalId" 两套 key 踩坑 |
| **Margin Call / BadDebt 事件通道** | 扩现有 `FundEventsHelper`（不新建独立 helper），事件通过 leader-local ring-buffer bypass raft，best-effort | 跟期货 `sendMarginAlertEvent` 同 helper 同通道；UI 靠 `computeDisplayInterest` 查询兜底 |
| 池子跨 shard 调剂 | 不做引擎侧自动调剂，运营手动再平衡 | 跟期货 IF 池同款设计原则；跨 shard 一致性成本远高于收益 |
| Isolated 补/减抵押 | 支持 `LOAN_ADD_COLLATERAL` / `LOAN_RELEASE_COLLATERAL` | 产品基线，用户主动风控 |
| 查询响应利息展示 | 默认含 pending interest | `computeDisplayInterest` read-only 现算 |

---

## 11. 与主流产品对齐

| 特性 | Binance Margin | 我们 v1 |
|---|---|---|
| Isolated / Cross 双模式 | ✅ | ✅ |
| 三级 LTV 阈值 | ✅ | ✅ |
| Margin Call 通知 | ✅ 邮件/push | ✅ FundEvent 通道 |
| Liquidation Fee | ✅ | ✅ |
| Cross partial deleverage | ✅ | ✅ |
| Cross 抵押折价 | ✅ | ✅ |
| 单笔本金上限 | ✅ | ✅ |
| 池子利用率上限 | ✅ | ✅ |
| Isolated 补/减抵押 | ✅ | ✅ |
| 小时计息 | ✅ | 惰性（等价语义，UI 通过 `computeDisplayInterest` 现算展示） |
| 动态利率（跟 utilization 挂钩） | ✅ | v2 升级（v1 静态 + API 架构预留） |
| VIP tier 利率打折 | ✅ | v2+（产品层参数）|
| 跨衍生品统一账户 | OKX Portfolio Margin | 非目标（借贷 / 期货账本独立）|

---

## 12. 交付

### 12.1 改动清单

| 位置 | 改动 |
|---|---|
| `IsolatedLoanRecord.java` | 新建（含 `openedAtTs` 字段） |
| `CrossLoanRecord.java` | 新建（含 `openedAtTs` 字段） |
| `LoanService.java` | 新建，**纯状态类**：state（池子 / 坏账 / 三阈值 / 幂等表）+ 序列化 / stateHash + 纯函数工具（accrue / computeDisplayInterest / 池子 metric / Cross LTV / OrderId helpers）；**不持** RiskEngine ref |
| `LoanCommandHandlers.java` | 新建，**命令处理类**（Option 2 分拆的独立类）：12 handler + `dispatch(OrderCommand)` 入口 + shard filter + POOL 短路；持 RiskEngine ref 通过 `engine.getXxx()` 现取所有 sub-service |
| `FundEventsHelper.java` | 扩展：加 3 个 `sendLoan*Event` 方法（`sendLoanMarginCallEvent` 不挂桶 / `sendLoanInterestSettleEvent` / `sendLoanBadDebtEvent` 挂 taker 桶）；不新建独立 helper |
| `FundEvent.java` | +3 字段：`loanAmount` / `loanExtra` / `loanMode`（语义随 eventType 变化，见 loan.md §9.5）|
| `FundEventType` enum | 加 3 个 tag：`LOAN_MARGIN_CALL(40)` / `LOAN_INTEREST_SETTLE(41)` / `LOAN_BAD_DEBT_INCURRED(42)` |
| `UserProfile.java` | +3 字段（`isolatedLoans` / `crossLoanCollateral` / `crossLoans`）；序列化位置追加末尾，无 gate；stateHash 显式覆盖三新字段 |
| `RiskEngine.java` | +2 字段（`loanService` / `loanCmdHandlers`）；序列化 / stateHash 加 loanService；`calculateLocked` 扩 loan 抵押两项；`preProcessCommand` 首行加 `if (cmd.command.isLoan()) { loanCmdHandlers.dispatch(cmd); return false; }` 二级 dispatch |
| `OrderCommandType` enum | 加 12 个（5 Isolated + 5 Cross + 2 Pool）+ 加 `isLoan()` 判断方法（switch 覆盖 12 值） |
| `CoreSymbolSpecification.java` | +7 字段（含 `loanMaxTermDays`）；序列化位置追加末尾，无 gate |
| `LiquidationEngine.java` | 扩展 3 lane：`updateProvider` 加 LoanService；新增 2 个 typed `MultiReaderSet<Long>` in-flight set + margin call 节流本地 map；新增 `publishTrackedIsolated` / `publishTrackedCross` 两个 typed 重载；`checkLiquidations` 加两 lane |
| `UserProfileService.suspend` | 守卫扩展含 `isolatedLoans.isEmpty() && crossLoanCollateral.allZero() && crossLoans.isEmpty()` |
| `TotalCurrencyBalanceReportResult` | `getGlobalBalancesSum` 加 `loanPoolAvailable` + `badDebt` 2 bucket（现共 9 桶） |
| `TotalCurrencyBalanceReportQuery` | 聚合两桶 |
| Raft 命令 | +12（5 Isolated + 5 Cross + 2 池子运营 `POOL_DEPOSIT`/`POOL_WITHDRAW`）；`POOL_*` 命令的 `cmd.uid` 承载 `shardId`（复用 IF_DEPOSIT/WITHDRAW pattern）；Margin Call / Interest Settle / BadDebt 走 FundEvent 不入 raft |
| `CommandResultCode` | +19 左右 `LOAN_*` 枚举（含 `LOAN_LTV_TOO_HIGH_AFTER_BORROW` / `LOAN_USER_SUSPENDED`） |
| OrderId 命名空间 | `'L' 0x4C` tag + `'S' 0x53` / `'C' 0x43` subtype |
| Reserved UID | `LOAN_LIQUIDATOR_UID` 常量（复用或独立于期货 `FORCE_LIQUIDATOR_UID`） |
| Query API 契约 | 借贷查询接口 response 中 `interest` 字段一律走 `LoanService.computeDisplayInterest` |
| `ITConservationFuzz` | 覆盖 §4.2 不变量（15 条）；`isGlobalBalancesAllZero` 自动包含新两桶 |
| Metric | `loan_bad_debt_incurred_total{currency,mode,shard}` counter；`loan_bad_debt_outstanding{currency,shard}` gauge；`loan_pool_utilization_bps{currency,shard}` gauge |

### 12.2 未做（后续 Phase）

- **精确 `calculateSizeToLiquidate`**：Cross v1 用简化 close-form 求解 + 多 tick 迭代收敛；v2 可精细化含滑点建模
- **`LoanIndex` 按 currency 反查**：LTV 扫描超过预算再加
- **`LOAN_CONFIG_UPDATE` 命令**：在线调 LoanService 内的三个阈值；目前重启生效
- **独立 spot-margin IF 池**：目前 badDebt 直接负记账 + FundEvent 观测；v2 可加真独立 IF 池按贡献分账
- **`POOL_BADDEBT_REFUND` 命令**：v1 运营手动通过 `POOL_DEPOSIT` 冲抵；v2 加专属命令并附赞助方登记
- **动态利率 v2**：`computeEffectiveRateBps` 目前静态返回 `spec.loanRateBps`；v2 升级为基于 utilization 曲线动态计算
- **抵押折价分档**：`collateralWeightBps` 目前单值；v2 可升级为 `MutableSortedMap<Long, Long>`（notional → weightBps）跟期货 maintenanceMargin 同款
- **VIP tier 利率打折**：产品层参数
- **批量强平**：一条 `LOAN_CROSS_FORCE_LIQUIDATE` 携带多个 tuple；v1 单 tuple 多 tick
- **User 累计借款上限**：v1 只有单笔上限；v2+ 加 per-user 累计 cap
- **强制年度 accrue 命令**：v1 靠 `loanMaxTermDays` 硬性拒绝超期；v2 可支持 loan renew / 定期强制 accrue 而不强平
- **跨 shard 池子自动再平衡**：v1 运营手动通过 `POOL_DEPOSIT/WITHDRAW` 调剂；v2 可加内嵌 rebalancer
