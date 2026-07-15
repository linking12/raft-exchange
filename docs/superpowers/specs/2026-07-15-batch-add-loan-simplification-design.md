# BatchAddLoanCommand 简化设计

**日期**: 2026-07-15
**分支**: `loan-config-simplification`
**状态**: 设计待 review

## 背景

`BatchAddLoanCommand`(ADD_LOAN 二进制命令,运营/管理员级)当前暴露 **17 个参数**,分三块 sub-config:

- **GlobalLoanConfig(5)**: `numeraireCurrency`, `crossLiquidationLtvBps`, `crossMarginCallLtvBps`, `loanPoolUtilizationCapBps`, `loanLiquidationFeeBps`
- **SymbolLoanConfig(7)**: `symbolId`, `loanInitialLtvBps`, `loanLiquidationLtvBps`, `loanMarginCallLtvBps`, `loanMaxAmount`, `loanMaxTermDays`, `collateralWeightBps`
- **RateCurveConfig(5)**: `baseBps`, `kinkUtilBps`, `slope1Bps`, `slope2Bps`, `lockedRateAdjustBps`

问题:参数过多 → 运营方易误配(阈值互相矛盾、单位搞错)、策略与可调项混在一起、命令认知负担重。

## 目标

1. 减少误配风险 —— 削减"必须手填、可能填错"的输入。
2. 区分策略 vs 可调 —— 全局风控策略集中、set-once;每市场只填风险锚。
3. 降低命令认知复杂度。
4. 给运营方预设档位。

## 非目标

- 不改 **SymbolLoanConfig / RateCurveConfig** 的 wire 布局(用 `−1` sentinel 保兼容)。
- 不拆分 ADD_LOAN 为多条命令(那是方案 C,本次不做)。
- 不动运行时计息/强平/reprice 逻辑,只动"配置如何被指定与 apply"。

**注意(非"非目标"):** 两个新缓冲进 `LoanGlobalConfig` 是 **additive 的序列化变更** —— 命令侧 `GlobalLoanConfig` 与状态机侧 `LoanGlobalConfig` 各 +2 个 int,其 write/read + `stateHash` 都会变。见 §兼容性 的快照处理。

## 方案:A(派生 + 可选覆盖)+ 预设工厂

每市场必填收敛到 `symbolId + initialLtvBps`;其余阈值按全局缓冲**派生**,可显式覆盖。曲线/全局策略给系统默认 + 预设工厂。

### 1. 派生规则(在 RiskEngine apply 时计算,确定性 → Raft 安全)

派生只用已在状态机内的 `LoanGlobalConfig`,输入固定 → 每副本结果一致,无共识分歧。

```
liquidationLtv = 指定值(override) 否则 initialLtv + global.ltvLiquidationBufferBps
marginCallLtv  = 指定值(override) 否则 liquidationLtv − global.ltvMarginCallBufferBps
collateralWeight = 指定值(override) 否则 initialLtvBps           // 借力=折扣对齐
```

**范围收窄(self-review 决定):** 派生只作用于**每市场(Symbol)**阈值 —— 那里旋钮最多、收益最大。**Cross 账户级 `crossLiquidation`/`crossMarginCall` 保持显式**(经 `ofGlobal` 一次性设定):它们是全局单值,派生只省一个一次性数字,却要给全局 config 引入 `−1` 语义(全局现用 `0=不改`,与"派生"冲突),complexity 不划算。缓冲字段仍进 `LoanGlobalConfig`,只服务 Symbol 派生。

派生后仍走既有不变量校验:`initial < marginCall < liquidation < 100%`(见 `SymbolLoanConfig.fieldsValid` 语义)。**派生结果违规 → 该块整体拒绝**(沿用现有"dispatch 静默跳过、不 apply、warn"语义,无 reject 码)。

### 2. Sentinel 约定(保 wire 兼容)

- 新增 **`−1` = 未指定 → 派生/默认**。
- 保留 `0` 的现有语义:`marginCall=0` 关预警、`loanMaxAmount=0` 无限、`loanMaxTermDays=0` 无期限。
- 旧的全字段命令(所有值显式 ≥0)语义不变,原样可用。新命令用 `−1` 触发派生。
- **wire 结构与字段数不变**,仅新增对 `−1` 的解释 → 旧快照 round-trip 不破。

### 3. 新对外 API(收敛必填 + 预设工厂)

新增(推荐给运营方):
```java
// 每市场必填只剩 2 个,其余派生/默认(内部把可选字段填 −1)
BatchAddLoanCommand.ofMarket(int symbolId, int initialLtvBps)
// 链式覆盖(可选,任意子集)
    .withLiquidationLtv(int).withMarginCallLtv(int).withCollateralWeight(int)
    .withMaxAmount(long).withMaxTermDays(int)

// 曲线预设(kink 固定 80%,填 base/slope1/slope2)
BatchAddLoanCommand.ofRateCurvePreset(RatePreset preset)   // CONSERVATIVE|STANDARD|AGGRESSIVE

// 全局策略:只需 numeraire,其余走默认
BatchAddLoanCommand.ofGlobalPolicy(int numeraireCurrency)
```

保留(标注为 override/高级用法,现有测试与调用不破):
`ofSymbol(...)`、`ofGlobal(...)`、`ofRateCurve(...)`、`ofGlobalNumeraire(...)`。

### 4. 固定决策(已确认)

- **`kinkUtilBps` 固定为系统常量 80%(8000 bps)**,不再由 `ofMarket`/预设暴露;`RateCurveConfig.valid()` 仍校验旧命令传入的 kink。
- **`collateralWeightBps` 默认 = `initialLtvBps`**(可覆盖)。

### 5. 全局默认常量(set-once,少动;`ofGlobalPolicy` 只传 numeraire)

沿用/新增于 `LoanGlobalConfig`:

| 参数 | 默认 | 来源 |
|------|------|------|
| `loanPoolUtilizationCapBps` | 9000 (90%) | 现有 `DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS` |
| `loanLiquidationFeeBps` | 200 (2%) | 现有 `DEFAULT_LOAN_LIQUIDATION_FEE_BPS` |
| `ltvLiquidationBufferBps` | **2000 (20%)** | 新增(initial→liquidation 缓冲) |
| `ltvMarginCallBufferBps` | **1000 (10%)** | 新增(liquidation→marginCall 缓冲) |
| `lockedRateAdjustBps` | 0 | 现有(FixedRateModel 点差) |

两个新缓冲作 `LoanGlobalConfig` 字段(可通过 `ofGlobal`/全局策略命令调),不是写死常量 → 集中、可调、set-once。默认值示例:initial 6000 → liquidation 8000 → marginCall 7000,与现有典型配置一致。

### 6. 曲线预设表(kink 固定 8000)

| 预设 | base | slope1 | slope2 | 在 kink 处 | 满载 |
|------|------|--------|--------|-----------|------|
| CONSERVATIVE | 100 | 300 | 4000 | 4% | 44% |
| **STANDARD** | 200 | 400 | 6000 | 6% | 66% |
| AGGRESSIVE | 300 | 600 | 9000 | 9% | 99% |

STANDARD = 现有 `FloatingRateModel` 默认,保证不改预设时行为不变。`lockedRateAdjustBps` 预设内默认 0。

## 数据流

`ofMarket(symbolId, initialLtv)` → 客户端把可选字段填 `−1` → 序列化(wire 不变)→ RiskEngine ADD_LOAN dispatch:对 `−1` 字段按 §1 用当前 `LoanGlobalConfig` 派生 → 派生结果过 `fieldsValid` 不变量 → valid 则 `spec.updateLoanConfig(...)`,否则 warn 跳过。

## 校验与不变量

- `SymbolLoanConfig.fieldsValid()` 扩展:允许 `−1`(表示派生),派生**之后**再校验最终 `initial < marginCall < liquidation < 100%`、`weight ∈ [0,100%]`。
- `GlobalLoanConfig.thresholdsValidGivenCurrent(...)`:纳入两个新缓冲的合法性(缓冲取正数时须 `< BPS_FULL`)。Cross 阈值仍按现有显式校验。
- 派生逻辑集中在**单一入口**:`SymbolLoanConfig.resolve(LoanGlobalConfig global)` 返回一个"已派生的最终值"结构(纯函数,把 `−1` 字段按 §1 填实);RiskEngine dispatch 只调用它 + 校验最终值 + apply。纯函数便于单测,dispatch 不含派生分支。

## 测试影响

- **wire 不变** → 现有序列化 round-trip 测试(`BatchAddLoanCommandTest`)基本不动;补 `−1` 字段 round-trip。
- 新增单测:派生正确性(每条派生公式)、派生后不变量校验(合法/非法边界)、`−1` sentinel 语义、预设值、`collateralWeight` 默认=initialLtv、kink 固定。
- 扩展 `CoreSymbolSpecificationLoanConfigTest` / RiskEngine dispatch 层拒绝测试(已有 `ITLoanDynamicRate` 的 config 拒绝 IT):派生后非法 → 跳过不 apply。
- 回归:STANDARD 预设 == 现有默认,确保既有行为不变。

## Proto / gRPC 层简化(扩展,2026-07-15)

exchange-core 简化后,`raft-exchange-spi` 的 ADD_LOAN proto 同步收窄,避免调用方传满字段:
- **`SpotLoanConfig`**:只暴露 `symbolId`+`loanInitialLtvBps`;5 个 override 字段**完全不进 proto**(`reserved 3,4,6,7,8`),converter 走 `ofMarket(symbolId, initialLtv).build()` → 全 override = `UNSET(−1)` → exchange-core `resolve()` 派生。理由:调用面最小化,某个 override 真需要时再单独加(避免 proto 里堆一排很少用的字段)。
- **`SpotLoanRateCurveConfig`**:改 `oneof { SpotLoanRatePreset preset | SpotLoanCustomRateCurve custom }`。常规传一个 preset 档位;非标曲线走 custom 逃生口。preset UNSPECIFIED→STANDARD。
- **`SpotLoanGlobalConfig`**:字段不变(partial-update,0=不改);两个派生缓冲**不进 proto**,converter 传 0,0 → 走系统默认。
- `ApiCommandConverters.convertBatchAddLoan` 改用 `ofMarket`/`ofRateCurvePreset` 语义 + optional presence → UNSET。
- pre-launch 无 wire 兼容负担,直接改 proto。修复了 converter 直接 `new GlobalLoanConfig(int×5)` 的编译断裂(exchange-core 侧改 7 参 @AllArgsConstructor 后)。

## 兼容性 / 迁移

- **Symbol / RateCurve wire 不变**(`−1` sentinel):旧命令、旧客户端全兼容。
- **Global 序列化 + LoanGlobalConfig 快照格式变更**(+2 缓冲 int):这是本设计唯一的破坏性变更点。
  - 命令侧:`GlobalLoanConfig` 尾部追加 2 个 int(旧命令不带 → 读取端按缺省 default 处理,或旧命令仍走 5-int 布局、新命令走 7-int,靠命令版本区分)。
  - 状态机侧:`LoanGlobalConfig.write/read` +2 int → **快照字节格式与 stateHash 变化**。
  - **处理策略:A(已定)** —— loan 子系统尚未上线、无需保留的生产快照,直接改格式,接受 stateHash 变化,恢复走全量 clean start / 重放;**不写版本兼容代码**。
- 新旧工厂并存;运营方逐步迁到 `ofMarket`/预设。

## 风险

- `−1` sentinel 引入需确保**所有**读取点区分"−1=派生"与"0=既有语义",遗漏会误派生。缓解:派生集中在单一 `resolve` 入口 + 边界测试。
- `collateralWeight` 默认=initialLtv 是新耦合约定;如未来 weight 与 LTV 解耦,需回看。已在本设计显式记录。
