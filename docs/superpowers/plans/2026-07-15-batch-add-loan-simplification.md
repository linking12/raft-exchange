# BatchAddLoanCommand 简化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 ADD_LOAN 配置命令的每市场必填项收敛到 `symbolId + initialLtvBps`,其余阈值按全局缓冲派生、可覆盖,并提供曲线/全局预设工厂。

**Architecture:** 方案 A(派生+可选覆盖)。每市场 override 字段用 `−1` sentinel 表示"未指定→派生";派生在纯函数 `SymbolLoanConfig.resolve(liqBuffer, mcBuffer)` 里完成,返回 `Resolved` 值对象;RiskEngine ADD_LOAN dispatch 调 resolve → 校验 resolved 不变量 → apply。两个新缓冲进 `LoanGlobalConfig`(状态机侧,快照格式变更)与命令侧 `GlobalLoanConfig`。

**Tech Stack:** Java, Lombok, Chronicle Bytes(序列化), JUnit 5, exchange-core。

## Global Constraints

- 单位一律 bps(1% = 100 bps),BPS_FULL = 10000。
- `kinkUtilBps` 固定系统常量 **8000(80%)**;新预设/ofMarket 不暴露 kink。
- `collateralWeightBps` 派生默认 = `initialLtvBps`。
- 缓冲默认:`ltvLiquidationBufferBps = 2000`、`ltvMarginCallBufferBps = 1000`。
- Sentinel:SymbolLoanConfig override 字段 **`−1` = 未指定→派生**;`0` 保留既有语义(marginCall=0 关预警、maxAmount=0 无限、maxTermDays=0 无期限、weight=0 不作抵押)。
- 快照兼容策略 **A**:pre-launch 无历史快照,直接改格式,不写版本兼容代码。
- 派生是纯函数,输入固定 → Raft 确定性,禁止引入 wall-clock/随机。
- 不改运行时计息/强平/reprice 逻辑。
- STANDARD 曲线预设 == 现有 FloatingRateModel 默认(base=200/slope1=400/slope2=6000),保证不改预设时行为不变。

---

## File Structure

- `exchange-core/src/main/java/exchange/core2/core/processors/loan/LoanGlobalConfig.java` — 状态机侧全局配置:+2 缓冲字段 + 序列化/stateHash/reset/默认。
- `exchange-core/src/main/java/exchange/core2/core/common/api/binary/BatchAddLoanCommand.java` — 命令 DTO:GlobalLoanConfig +2 缓冲字段 + wire;SymbolLoanConfig `−1` sentinel + `resolve()` + `Resolved` 值对象 + 共享不变量 helper;新工厂 `ofMarket`/链式 `withX`/`ofGlobalPolicy`;`RatePreset` enum + `ofRateCurvePreset`。
- `exchange-core/src/main/java/exchange/core2/core/processors/RiskEngine.java` — ADD_LOAN dispatch(~1168-1228):global 纳入 2 缓冲;symbol 走 `resolve()`+`Resolved.valid()`+apply。
- 测试:`BatchAddLoanCommandTest.java`、`ITLoanDynamicRate.java`(既有,扩展)。

---

### Task 1: LoanGlobalConfig 加 2 个派生缓冲字段（状态机侧）

**Files:**
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/loan/LoanGlobalConfig.java`
- Test: `exchange-core/src/test/java/exchange/core2/tests/unit/LoanServiceTest.java`(已有 `snapshot_roundTrip_preservesAllState`,扩展它 + 加默认值断言)

**Interfaces:**
- Produces: `LoanGlobalConfig.ltvLiquidationBufferBps`(int, public), `LoanGlobalConfig.ltvMarginCallBufferBps`(int, public); 常量 `DEFAULT_LTV_LIQUIDATION_BUFFER_BPS=2000`, `DEFAULT_LTV_MARGIN_CALL_BUFFER_BPS=1000`。序列化顺序:在 `loanLiquidationFeeBps` 之后追加两字段。

- [ ] **Step 1: 写失败测试**（在 LoanServiceTest 新增）

```java
@Test
void loanGlobalConfig_defaultsIncludeDerivationBuffers() {
    LoanGlobalConfig g = new LoanGlobalConfig();
    assertEquals(2000, g.ltvLiquidationBufferBps, "默认 liquidation 缓冲 20%");
    assertEquals(1000, g.ltvMarginCallBufferBps, "默认 marginCall 缓冲 10%");
}

@Test
void loanGlobalConfig_roundTrip_preservesBuffers() {
    LoanGlobalConfig g = new LoanGlobalConfig();
    g.ltvLiquidationBufferBps = 2500;
    g.ltvMarginCallBufferBps = 1200;
    net.openhft.chronicle.bytes.Bytes<?> bytes = net.openhft.chronicle.bytes.Bytes.allocateElasticOnHeap();
    g.writeMarshallable(bytes);
    LoanGlobalConfig restored = new LoanGlobalConfig(bytes);
    assertEquals(2500, restored.ltvLiquidationBufferBps);
    assertEquals(1200, restored.ltvMarginCallBufferBps);
    assertEquals(g.stateHash(), restored.stateHash());
}
```
（若 LoanServiceTest 已 import LoanGlobalConfig / Bytes,复用其既有 import 风格,不要重复 import。）

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl exchange-core test -Dtest='LoanServiceTest#loanGlobalConfig_defaultsIncludeDerivationBuffers+loanGlobalConfig_roundTrip_preservesBuffers'`
Expected: 编译失败(字段/常量不存在)。

- [ ] **Step 3: 实现 —— LoanGlobalConfig 加字段**

在常量区加：
```java
    public static final int DEFAULT_LTV_LIQUIDATION_BUFFER_BPS = 2000; // 20% initial→liquidation
    public static final int DEFAULT_LTV_MARGIN_CALL_BUFFER_BPS = 1000; // 10% liquidation→marginCall
```
在字段区（`loanLiquidationFeeBps` 之后）加：
```java
    public int ltvLiquidationBufferBps; // Symbol 派生:liquidation = initial + 本值
    public int ltvMarginCallBufferBps;  // Symbol/Cross 派生:marginCall = liquidation − 本值
```
构造器、`reset()` 各加：
```java
        this.ltvLiquidationBufferBps = DEFAULT_LTV_LIQUIDATION_BUFFER_BPS;
        this.ltvMarginCallBufferBps = DEFAULT_LTV_MARGIN_CALL_BUFFER_BPS;
```
`LoanGlobalConfig(BytesIn bytes)` 末尾加（顺序与 write 一致）：
```java
        this.ltvLiquidationBufferBps = bytes.readInt();
        this.ltvMarginCallBufferBps = bytes.readInt();
```
`writeMarshallable` 末尾加：
```java
        bytes.writeInt(ltvLiquidationBufferBps);
        bytes.writeInt(ltvMarginCallBufferBps);
```
`stateHash` 纳入两字段：
```java
        return Objects.hash(numeraireCurrency, crossLiquidationLtvBps, crossMarginCallLtvBps, loanPoolUtilizationCapBps,
            loanLiquidationFeeBps, ltvLiquidationBufferBps, ltvMarginCallBufferBps);
```
`toString` 末尾补 `+ " liqBuf=" + ltvLiquidationBufferBps + " mcBuf=" + ltvMarginCallBufferBps`。

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl exchange-core test -Dtest='LoanServiceTest'`
Expected: PASS(含既有 snapshot round-trip —— 因 write/read 顺序一致仍绿）。

- [ ] **Step 5: 提交**

```bash
git add exchange-core/src/main/java/exchange/core2/core/processors/loan/LoanGlobalConfig.java exchange-core/src/test/java/exchange/core2/tests/unit/LoanServiceTest.java
git commit -m "feat(loan): add LTV derivation buffers to LoanGlobalConfig"
```

---

### Task 2: 命令侧 GlobalLoanConfig 加缓冲 + ofGlobalPolicy

**Files:**
- Modify: `exchange-core/src/main/java/exchange/core2/core/common/api/binary/BatchAddLoanCommand.java`
- Test: `exchange-core/src/test/java/exchange/core2/tests/unit/BatchAddLoanCommandTest.java`

**Interfaces:**
- Consumes: 无（独立）。
- Produces: `GlobalLoanConfig.ltvLiquidationBufferBps`/`ltvMarginCallBufferBps`(int, final, getter via @Getter);工厂 `BatchAddLoanCommand.ofGlobalPolicy(int numeraireCurrency)`;`ofGlobal(...)` 新增 2 个 int 尾参。命令侧 GlobalLoanConfig 语义:缓冲 `≤0 = 不改（用当前）`,`>0 = 更新`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void ofGlobalPolicy_setsNumeraire_buffersUnset() {
    BatchAddLoanCommand cmd = BatchAddLoanCommand.ofGlobalPolicy(840);
    assertTrue(cmd.hasGlobal());
    assertEquals(840, cmd.getGlobal().getNumeraireCurrency());
    assertEquals(0, cmd.getGlobal().getLtvLiquidationBufferBps(), "0=不改,走当前/默认");
    assertEquals(0, cmd.getGlobal().getLtvMarginCallBufferBps());
}

@Test
void global_buffers_bytesRoundTrip() {
    BatchAddLoanCommand cmd = BatchAddLoanCommand.ofGlobal(840, 8500, 8000, 9000, 200, 2500, 1200);
    net.openhft.chronicle.bytes.Bytes<?> b = net.openhft.chronicle.bytes.Bytes.allocateElasticOnHeap();
    cmd.writeMarshallable(b);
    BatchAddLoanCommand restored = new BatchAddLoanCommand(b);
    assertEquals(2500, restored.getGlobal().getLtvLiquidationBufferBps());
    assertEquals(1200, restored.getGlobal().getLtvMarginCallBufferBps());
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl exchange-core test -Dtest='BatchAddLoanCommandTest#ofGlobalPolicy_setsNumeraire_buffersUnset+global_buffers_bytesRoundTrip'`
Expected: 编译失败。

- [ ] **Step 3: 实现 —— GlobalLoanConfig 加字段 + 工厂**

`GlobalLoanConfig` 加两 final 字段（`loanLiquidationFeeBps` 之后）：
```java
        private final int ltvLiquidationBufferBps; // Symbol 派生缓冲;≤0=不改
        private final int ltvMarginCallBufferBps;  // Symbol/Cross 派生缓冲;≤0=不改
```
`GlobalLoanConfig(BytesIn)` 末尾加 `this.ltvLiquidationBufferBps = bytes.readInt(); this.ltvMarginCallBufferBps = bytes.readInt();`；`write(BytesOut)` 末尾加对应 `writeInt`。（`@AllArgsConstructor` 自动含新字段。）
`ofGlobal` 改签名 + 传参：
```java
    public static BatchAddLoanCommand ofGlobal(int numeraireCurrency, int crossLiquidationLtvBps,
        int crossMarginCallLtvBps, int loanPoolUtilizationCapBps, int loanLiquidationFeeBps,
        int ltvLiquidationBufferBps, int ltvMarginCallBufferBps) {
        return new BatchAddLoanCommand(new GlobalLoanConfig(numeraireCurrency, crossLiquidationLtvBps,
            crossMarginCallLtvBps, loanPoolUtilizationCapBps, loanLiquidationFeeBps,
            ltvLiquidationBufferBps, ltvMarginCallBufferBps), null);
    }
```
`ofGlobalNumeraire` 改为 `ofGlobal(numeraireCurrency, 0,0,0,0, 0,0)`。新增：
```java
    public static BatchAddLoanCommand ofGlobalPolicy(int numeraireCurrency) {
        return ofGlobal(numeraireCurrency, 0, 0, 0, 0, 0, 0);
    }
```
`thresholdsValidGivenCurrent`：缓冲取正数时校验合法（追加到 return 布尔链）：
```java
                && (ltvLiquidationBufferBps <= 0 || ltvLiquidationBufferBps < BPS_FULL)
                && (ltvMarginCallBufferBps <= 0 || ltvMarginCallBufferBps < BPS_FULL);
```
（更新 `BatchAddLoanCommandTest` 中所有旧 `ofGlobal(...)` 调用为 7 参；旧 5 参调用点补 `,0,0`。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl exchange-core test -Dtest='BatchAddLoanCommandTest'`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add exchange-core/src/main/java/exchange/core2/core/common/api/binary/BatchAddLoanCommand.java exchange-core/src/test/java/exchange/core2/tests/unit/BatchAddLoanCommandTest.java
git commit -m "feat(loan): add derivation buffers + ofGlobalPolicy to command GlobalLoanConfig"
```

---

### Task 3: SymbolLoanConfig `−1` sentinel + resolve() + Resolved 值对象

**Files:**
- Modify: `exchange-core/src/main/java/exchange/core2/core/common/api/binary/BatchAddLoanCommand.java`
- Test: `exchange-core/src/test/java/exchange/core2/tests/unit/BatchAddLoanCommandTest.java`

**Interfaces:**
- Consumes: 无。
- Produces:
  - `SymbolLoanConfig.UNSET = -1`(public static final int)。
  - `SymbolLoanConfig.Resolved`(nested,字段:`int symbolId, initialLtvBps, liquidationLtvBps, marginCallLtvBps; long maxAmount; int maxTermDays, collateralWeightBps`;方法 `boolean valid()`)。
  - `SymbolLoanConfig.resolve(int liqBufferBps, int mcBufferBps)` → `Resolved`(纯函数)。
  - 共享静态 `SymbolLoanConfig.thresholdsValid(int initial, int marginCall, int liquidation)`。
- 派生规则(resolve)：
  - `liq = loanLiquidationLtvBps==UNSET ? initial + liqBufferBps : loanLiquidationLtvBps`
  - `mc  = loanMarginCallLtvBps==UNSET ? liq - mcBufferBps : loanMarginCallLtvBps`
  - `weight = collateralWeightBps==UNSET ? loanInitialLtvBps : collateralWeightBps`
  - `maxAmount = loanMaxAmount==UNSET ? 0 : loanMaxAmount`
  - `maxTermDays = loanMaxTermDays==UNSET ? 0 : loanMaxTermDays`
  - `initial = loanInitialLtvBps`（不派生,必填）

- [ ] **Step 1: 写失败测试**

```java
@Test
void resolve_derivesLiquidationAndMarginCallFromBuffers() {
    // initial 6000, 全部 override 未指定(−1) → liq=6000+2000=8000, mc=8000−1000=7000, weight=initial=6000
    SymbolLoanConfig s = new SymbolLoanConfig(100, 6000,
        SymbolLoanConfig.UNSET, SymbolLoanConfig.UNSET, SymbolLoanConfig.UNSET, SymbolLoanConfig.UNSET,
        SymbolLoanConfig.UNSET);
    SymbolLoanConfig.Resolved r = s.resolve(2000, 1000);
    assertEquals(8000, r.liquidationLtvBps);
    assertEquals(7000, r.marginCallLtvBps);
    assertEquals(6000, r.collateralWeightBps);
    assertEquals(0L, r.maxAmount);
    assertEquals(0, r.maxTermDays);
    assertTrue(r.valid());
}

@Test
void resolve_explicitOverridesWin() {
    SymbolLoanConfig s = new SymbolLoanConfig(100, 6000, 8500, 7500, 5_000L, 30, 9000);
    SymbolLoanConfig.Resolved r = s.resolve(2000, 1000);
    assertEquals(8500, r.liquidationLtvBps);
    assertEquals(7500, r.marginCallLtvBps);
    assertEquals(9000, r.collateralWeightBps);
    assertEquals(5_000L, r.maxAmount);
    assertEquals(30, r.maxTermDays);
    assertTrue(r.valid());
}

@Test
void resolve_marginCallZeroMeansDisabled_notDerived() {
    // marginCall 显式 0 = 关预警(既有语义),不派生
    SymbolLoanConfig s = new SymbolLoanConfig(100, 6000, SymbolLoanConfig.UNSET, 0,
        SymbolLoanConfig.UNSET, SymbolLoanConfig.UNSET, SymbolLoanConfig.UNSET);
    SymbolLoanConfig.Resolved r = s.resolve(2000, 1000);
    assertEquals(0, r.marginCallLtvBps, "0 保留:关预警");
    assertTrue(r.valid(), "marginCall=0 合法(disabled)");
}

@Test
void resolved_invalid_whenDerivedThresholdsInverted() {
    // buffer 过大导致 mc<=initial → 不合法
    SymbolLoanConfig s = new SymbolLoanConfig(100, 6000, SymbolLoanConfig.UNSET, SymbolLoanConfig.UNSET,
        SymbolLoanConfig.UNSET, SymbolLoanConfig.UNSET, SymbolLoanConfig.UNSET);
    SymbolLoanConfig.Resolved r = s.resolve(500, 1000); // liq=6500, mc=5500 < initial 6000 → invalid
    assertFalse(r.valid());
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl exchange-core test -Dtest='BatchAddLoanCommandTest#resolve_derivesLiquidationAndMarginCallFromBuffers+resolve_explicitOverridesWin+resolve_marginCallZeroMeansDisabled_notDerived+resolved_invalid_whenDerivedThresholdsInverted'`
Expected: 编译失败。

- [ ] **Step 3: 实现 —— SymbolLoanConfig 加 UNSET/resolve/Resolved/thresholdsValid**

在 `SymbolLoanConfig` 内加：
```java
        public static final int UNSET = -1; // override 字段未指定→派生/默认

        /** 派生后的最终配置(所有 −1 已填实)。 */
        @AllArgsConstructor
        @Getter
        @ToString
        public static final class Resolved {
            public final int symbolId;
            public final int initialLtvBps;
            public final int liquidationLtvBps;
            public final int marginCallLtvBps;
            public final long maxAmount;
            public final int maxTermDays;
            public final int collateralWeightBps;

            public boolean valid() {
                return initialLtvBps >= 0 && initialLtvBps < BPS_FULL
                    && (initialLtvBps == 0 || thresholdsValid(initialLtvBps, marginCallLtvBps, liquidationLtvBps))
                    && maxAmount >= 0 && maxTermDays >= 0
                    && collateralWeightBps >= 0 && collateralWeightBps <= BPS_FULL;
            }
        }

        /** initial < marginCall < liquidation < 100%;marginCall==0 表示关预警(合法)。 */
        static boolean thresholdsValid(int initial, int marginCall, int liquidation) {
            return liquidation > initial && liquidation < BPS_FULL
                && (marginCall == 0 || (marginCall > initial && marginCall < liquidation));
        }

        public Resolved resolve(int liqBufferBps, int mcBufferBps) {
            int liq = loanLiquidationLtvBps == UNSET ? loanInitialLtvBps + liqBufferBps : loanLiquidationLtvBps;
            int mc = loanMarginCallLtvBps == UNSET ? liq - mcBufferBps : loanMarginCallLtvBps;
            int weight = collateralWeightBps == UNSET ? loanInitialLtvBps : collateralWeightBps;
            long maxAmt = loanMaxAmount == UNSET ? 0L : loanMaxAmount;
            int term = loanMaxTermDays == UNSET ? 0 : loanMaxTermDays;
            return new Resolved(symbolId, loanInitialLtvBps, liq, mc, maxAmt, term, weight);
        }
```
`fieldsValid()` 复用 helper（保持既有 explicit 校验语义,DRY）：
```java
        public boolean fieldsValid() {
            return loanInitialLtvBps >= 0 && loanInitialLtvBps < BPS_FULL
                && (loanInitialLtvBps == 0 || thresholdsValid(loanInitialLtvBps, loanMarginCallLtvBps, loanLiquidationLtvBps))
                && loanMaxAmount >= 0 && loanMaxTermDays >= 0 && collateralWeightBps >= 0
                && collateralWeightBps <= BPS_FULL;
        }
```
（确认 `lombok.AllArgsConstructor`/`Getter`/`ToString` 已在文件 import。）

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl exchange-core test -Dtest='BatchAddLoanCommandTest'`
Expected: PASS（既有 fieldsValid 测试因 helper 语义等价仍绿）。

- [ ] **Step 5: 提交**

```bash
git add exchange-core/src/main/java/exchange/core2/core/common/api/binary/BatchAddLoanCommand.java exchange-core/src/test/java/exchange/core2/tests/unit/BatchAddLoanCommandTest.java
git commit -m "feat(loan): add UNSET sentinel + resolve() derivation to SymbolLoanConfig"
```

---

### Task 4: ofMarket + 链式 override 工厂

**Files:**
- Modify: `exchange-core/src/main/java/exchange/core2/core/common/api/binary/BatchAddLoanCommand.java`
- Test: `exchange-core/src/test/java/exchange/core2/tests/unit/BatchAddLoanCommandTest.java`

**Interfaces:**
- Consumes: `SymbolLoanConfig.UNSET`(Task 3)。
- Produces: `BatchAddLoanCommand.ofMarket(int symbolId, int initialLtvBps)` → 内部 SymbolLoanConfig 全 override=UNSET;链式 `MarketBuilder`(`withLiquidationLtv/withMarginCallLtv/withCollateralWeight/withMaxAmount/withMaxTermDays` → `build()` 返回 `BatchAddLoanCommand`)。

- [ ] **Step 1: 写失败测试**

```java
@Test
void ofMarket_minimal_allOverridesUnset() {
    BatchAddLoanCommand cmd = BatchAddLoanCommand.ofMarket(100, 6000).build();
    SymbolLoanConfig s = cmd.getSymbol();
    assertEquals(100, s.getSymbolId());
    assertEquals(6000, s.getLoanInitialLtvBps());
    assertEquals(SymbolLoanConfig.UNSET, s.getLoanLiquidationLtvBps());
    assertEquals(SymbolLoanConfig.UNSET, s.getLoanMarginCallLtvBps());
    assertEquals(SymbolLoanConfig.UNSET, s.getCollateralWeightBps());
    assertEquals((long) SymbolLoanConfig.UNSET, s.getLoanMaxAmount());
    assertEquals(SymbolLoanConfig.UNSET, s.getLoanMaxTermDays());
}

@Test
void ofMarket_withOverrides_setsExplicit() {
    BatchAddLoanCommand cmd = BatchAddLoanCommand.ofMarket(100, 6000)
        .withLiquidationLtv(8500).withCollateralWeight(9000).withMaxTermDays(30).build();
    SymbolLoanConfig s = cmd.getSymbol();
    assertEquals(8500, s.getLoanLiquidationLtvBps());
    assertEquals(9000, s.getCollateralWeightBps());
    assertEquals(30, s.getLoanMaxTermDays());
    assertEquals(SymbolLoanConfig.UNSET, s.getLoanMarginCallLtvBps(), "未 override 仍 UNSET");
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl exchange-core test -Dtest='BatchAddLoanCommandTest#ofMarket_minimal_allOverridesUnset+ofMarket_withOverrides_setsExplicit'`
Expected: 编译失败。

- [ ] **Step 3: 实现 —— ofMarket + MarketBuilder**

在 `BatchAddLoanCommand` 加：
```java
    public static MarketBuilder ofMarket(int symbolId, int initialLtvBps) {
        return new MarketBuilder(symbolId, initialLtvBps);
    }

    public static final class MarketBuilder {
        private final int symbolId;
        private final int initialLtvBps;
        private int liquidationLtvBps = SymbolLoanConfig.UNSET;
        private int marginCallLtvBps = SymbolLoanConfig.UNSET;
        private long maxAmount = SymbolLoanConfig.UNSET;
        private int maxTermDays = SymbolLoanConfig.UNSET;
        private int collateralWeightBps = SymbolLoanConfig.UNSET;

        MarketBuilder(int symbolId, int initialLtvBps) {
            this.symbolId = symbolId;
            this.initialLtvBps = initialLtvBps;
        }

        public MarketBuilder withLiquidationLtv(int v) { this.liquidationLtvBps = v; return this; }
        public MarketBuilder withMarginCallLtv(int v) { this.marginCallLtvBps = v; return this; }
        public MarketBuilder withCollateralWeight(int v) { this.collateralWeightBps = v; return this; }
        public MarketBuilder withMaxAmount(long v) { this.maxAmount = v; return this; }
        public MarketBuilder withMaxTermDays(int v) { this.maxTermDays = v; return this; }

        public BatchAddLoanCommand build() {
            return new BatchAddLoanCommand(null, new SymbolLoanConfig(symbolId, initialLtvBps,
                liquidationLtvBps, marginCallLtvBps, maxAmount, maxTermDays, collateralWeightBps));
        }
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl exchange-core test -Dtest='BatchAddLoanCommandTest'`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add exchange-core/src/main/java/exchange/core2/core/common/api/binary/BatchAddLoanCommand.java exchange-core/src/test/java/exchange/core2/tests/unit/BatchAddLoanCommandTest.java
git commit -m "feat(loan): add ofMarket minimal factory + chained overrides"
```

---

### Task 5: RatePreset enum + ofRateCurvePreset（kink 固定 80%）

**Files:**
- Modify: `exchange-core/src/main/java/exchange/core2/core/common/api/binary/BatchAddLoanCommand.java`
- Test: `exchange-core/src/test/java/exchange/core2/tests/unit/BatchAddLoanCommandTest.java`

**Interfaces:**
- Consumes: 无。
- Produces: `BatchAddLoanCommand.RatePreset`(enum: CONSERVATIVE/STANDARD/AGGRESSIVE,携带 base/slope1/slope2);`BatchAddLoanCommand.ofRateCurvePreset(RatePreset)` → RateCurveConfig(kink=8000, lockedAdjust=0)。常量 `FIXED_KINK_UTIL_BPS = 8000`。

- [ ] **Step 1: 写失败测试**

```java
@Test
void ofRateCurvePreset_standardMatchesCurrentDefaults() {
    RateCurveConfig rc = BatchAddLoanCommand.ofRateCurvePreset(BatchAddLoanCommand.RatePreset.STANDARD).getRateCurve();
    assertEquals(200, rc.getBaseBps());
    assertEquals(8000, rc.getKinkUtilBps(), "kink 固定 80%");
    assertEquals(400, rc.getSlope1Bps());
    assertEquals(6000, rc.getSlope2Bps());
    assertEquals(0, rc.getLockedRateAdjustBps());
    assertTrue(rc.valid());
}

@Test
void ofRateCurvePreset_conservativeAndAggressive_valid_kinkFixed() {
    for (BatchAddLoanCommand.RatePreset p : BatchAddLoanCommand.RatePreset.values()) {
        RateCurveConfig rc = BatchAddLoanCommand.ofRateCurvePreset(p).getRateCurve();
        assertEquals(8000, rc.getKinkUtilBps());
        assertTrue(rc.valid(), "preset " + p + " 曲线自洽");
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -o -pl exchange-core test -Dtest='BatchAddLoanCommandTest#ofRateCurvePreset_standardMatchesCurrentDefaults+ofRateCurvePreset_conservativeAndAggressive_valid_kinkFixed'`
Expected: 编译失败。

- [ ] **Step 3: 实现 —— RatePreset + ofRateCurvePreset**

```java
    public static final int FIXED_KINK_UTIL_BPS = 8000; // 拐点固定 80%

    public enum RatePreset {
        CONSERVATIVE(100, 300, 4000),
        STANDARD(200, 400, 6000),
        AGGRESSIVE(300, 600, 9000);
        final int baseBps, slope1Bps, slope2Bps;
        RatePreset(int baseBps, int slope1Bps, int slope2Bps) {
            this.baseBps = baseBps; this.slope1Bps = slope1Bps; this.slope2Bps = slope2Bps;
        }
    }

    public static BatchAddLoanCommand ofRateCurvePreset(RatePreset preset) {
        return new BatchAddLoanCommand(null, null,
            new RateCurveConfig(preset.baseBps, FIXED_KINK_UTIL_BPS, preset.slope1Bps, preset.slope2Bps, 0));
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -o -pl exchange-core test -Dtest='BatchAddLoanCommandTest'`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add exchange-core/src/main/java/exchange/core2/core/common/api/binary/BatchAddLoanCommand.java exchange-core/src/test/java/exchange/core2/tests/unit/BatchAddLoanCommandTest.java
git commit -m "feat(loan): add rate curve presets with fixed 80% kink"
```

---

### Task 6: RiskEngine dispatch 接入 resolve() + 缓冲

**Files:**
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/RiskEngine.java`(ADD_LOAN dispatch,~1168-1228)
- Test: `exchange-core/src/test/java/exchange/core2/tests/integration/ITLoanDynamicRate.java`(Task 7 覆盖端到端;本任务先接线,编译 + 现有 IT 绿)

**Interfaces:**
- Consumes: `LoanGlobalConfig.ltvLiquidationBufferBps/ltvMarginCallBufferBps`(Task 1);`SymbolLoanConfig.resolve(int,int)` + `Resolved.valid()`(Task 3);命令侧 `GlobalLoanConfig.getLtvLiquidationBufferBps/getLtvMarginCallBufferBps`(Task 2)。
- Produces: dispatch 行为:symbol 部分走 resolve→valid→apply;global 部分把 2 缓冲纳入 partial-update。

- [ ] **Step 1: 改 global 分支纳入缓冲**

在 `RiskEngine` global apply 块(现有 `if (g.getLoanLiquidationFeeBps() > 0) {...}` 之后,`log.info` 之前）加：
```java
                    if (g.getLtvLiquidationBufferBps() > 0) {
                        config.ltvLiquidationBufferBps = g.getLtvLiquidationBufferBps();
                    }
                    if (g.getLtvMarginCallBufferBps() > 0) {
                        config.ltvMarginCallBufferBps = g.getLtvMarginCallBufferBps();
                    }
```

- [ ] **Step 2: 改 symbol 分支走 resolve**

把现有 symbol apply 块（`if (spec != null && spec.type == ... && s.fieldsValid()) { spec.updateLoanConfig(s.getLoanInitialLtvBps(), ...); ... }`）替换为：
```java
            if (cmd.hasSymbol()) {
                final BatchAddLoanCommand.SymbolLoanConfig s = cmd.getSymbol();
                final CoreSymbolSpecification spec =
                    symbolSpecificationProvider.getSymbolSpecification(s.getSymbolId());
                final LoanGlobalConfig gc = loanService.getGlobalConfig();
                final BatchAddLoanCommand.SymbolLoanConfig.Resolved r =
                    s.resolve(gc.ltvLiquidationBufferBps, gc.ltvMarginCallBufferBps);
                if (spec != null && spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR && r.valid()) {
                    spec.updateLoanConfig(r.initialLtvBps, r.liquidationLtvBps, r.marginCallLtvBps,
                        r.maxAmount, r.maxTermDays, r.collateralWeightBps);
                    log.info("ADD_LOAN symbol config applied (resolved): {}", r);
                } else {
                    log.warn("ADD_LOAN symbol config rejected: {}", s);
                }
            }
```
（若 `BatchAddLoanCommand.SymbolLoanConfig.Resolved` 需 import,加之。）

- [ ] **Step 3: 编译 + 跑现有 IT 确认不回归**

Run: `mvn -o -pl exchange-core test -Dtest='ITLoanDynamicRate,ITLoanFailoverSnapshot,ITLoanConservation'`
Expected: PASS(现有命令走 explicit 值,resolve 无 −1 → 行为等价)。

- [ ] **Step 4: 提交**

```bash
git add exchange-core/src/main/java/exchange/core2/core/processors/RiskEngine.java
git commit -m "feat(loan): wire ADD_LOAN dispatch to resolve() derivation + buffers"
```

---

### Task 7: 端到端派生 IT（ofMarket + 预设 + 全局策略）

**Files:**
- Modify: `exchange-core/src/test/java/exchange/core2/tests/integration/ITLoanDynamicRate.java`

**Interfaces:**
- Consumes: `ofMarket`(Task 4)、`ofRateCurvePreset`(Task 5)、`ofGlobalPolicy`(Task 2)、dispatch(Task 6)。复用文件既有 `boot`/`createLoan`/`loanRateBps` 与 `ApiNop` 屏障 pattern。

- [ ] **Step 1: 写端到端测试**

```java
@Test
public void ofMarket_derivesThresholds_borrowRespectsDerivedInitialLtv() throws Exception {
    // 用 ofMarket 只设 initialLtv=6000;liquidation/marginCall/weight 派生。缓冲用默认(2000/1000)。
    try (ExchangeTestContainer c = boot(200, 0)) {
        // 覆盖 boot 里的 symbol 配置:改用 ofMarket 最小配置
        c.sendBinaryDataCommandSync(BatchAddLoanCommand.ofMarket(SYMBOL, 6000).build(), 5200);
        // initialLtv=6000 → 借 100 BTC(mark 50000=5,000,000 抵押价值)可借上限 3,000,000
        // 借 2,900,000(<上限) 应 SUCCESS
        c.submitCommandSync(exchange.core2.core.common.api.ApiLoanCreate.builder()
            .externalId(1_000_050L).uid(BORROWER).loanId(50L).symbol(SYMBOL)
            .collateralAmount(100L).principal(2_900_000L).rateMode((byte) 1).build(),
            CommandResultCode.SUCCESS);
    }
}

@Test
public void ofMarket_borrowAboveDerivedInitialLtv_rejected() throws Exception {
    try (ExchangeTestContainer c = boot(200, 0)) {
        c.sendBinaryDataCommandSync(BatchAddLoanCommand.ofMarket(SYMBOL, 6000).build(), 5201);
        // 借 3,100,000 > 上限 3,000,000 → LTV 超 initial → 拒绝
        c.submitCommandSync(exchange.core2.core.common.api.ApiLoanCreate.builder()
            .externalId(1_000_051L).uid(BORROWER).loanId(51L).symbol(SYMBOL)
            .collateralAmount(100L).principal(3_100_000L).rateMode((byte) 1).build(),
            CommandResultCode.LOAN_LTV_TOO_HIGH);
    }
}

@Test
public void ofRateCurvePreset_standard_floatingOpensAtPresetBase() throws Exception {
    try (ExchangeTestContainer c = boot(999, 0)) { // boot 设了个非标 base=999
        // 用 STANDARD 预设覆盖 → base 变回 200
        c.sendBinaryDataCommandSync(
            BatchAddLoanCommand.ofRateCurvePreset(BatchAddLoanCommand.RatePreset.STANDARD), 5202);
        createLoan(c, 1_000_052L, 52L, 100_000L, (byte) 1); // FLOATING
        assertEquals(200, loanRateBps(c, 52L), "STANDARD 预设 base=200");
    }
}
```
（如 `boot` 未暴露 base=999 场景,直接用 `boot(200,0)` 并断言 200 亦可;关键是 preset 覆盖后取 base。按文件既有 helper 语义择一,保持确定性。）

- [ ] **Step 2: 跑测试确认通过**

Run: `mvn -o -pl exchange-core test -Dtest='ITLoanDynamicRate'`
Expected: PASS。

- [ ] **Step 3: 全量回归**

Run: `mvn -o -pl exchange-core test`
Expected: BUILD SUCCESS,0 failures(两段 execution:unit + IT)。

- [ ] **Step 4: 提交**

```bash
git add exchange-core/src/test/java/exchange/core2/tests/integration/ITLoanDynamicRate.java
git commit -m "test(loan): e2e derivation via ofMarket + presets"
```

---

## 收尾

- [ ] 全量 `mvn -o -pl exchange-core test` 绿(~1103+ 用例)。
- [ ] 更新 `loan.md`(§11/§13 config 章节)描述新 ofMarket/预设/派生缓冲与 `−1` 语义(若该文档存在且覆盖 ADD_LOAN)。
- [ ] 更新 memory `loan-coverage-state` 若命令表面变化影响后续测试口径。
