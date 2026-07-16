# 现货借贷强平事件驱动化 实现 Plan (Plan 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 给现货借贷强平加 targeted 事件触发(抵押 spot 对 MARKPRICE 即时触发对应 loan 持有者),并把 loan 强平检测从"挂在期货 checkUser"重构成 loan 引擎自己的入口 `checkLoans`,顺带清理 `LaneState` 冗余。

**Architecture(定稿):** 单个 `LiquidationEngine`(期货)拥有一个**稳定单例** `LoanLiquidationEngine`。loan 的**数据 + 逻辑自洽**在 loan 两类里(索引在 `LoanLiquidationEngine`、索引维护在 `LoanCommandHandlers`、检测在 `LoanLiquidationEngine.checkLoans`);只**借**期货引擎的**触发入口**(`checkPositions` 一行委托 `checkLoans`)和**基础设施**(provider / submitter / `isRunning` 门控)。`checkUser` 变纯期货。**不碰 `RiskEngine` / `LiquidationCommandSubmitter` / `LiquidationScheduledService` / `ExchangeRuntime`。**

**Tech Stack:** Java 11, exchange-core (LMAX Disruptor), Eclipse Collections, JUnit 5 + Mockito, JRaft。

**关联**: [spec](../specs/2026-07-16-loan-event-driven-liquidation-design.md)

## Global Constraints

- **确定性**:整数运算 `Math.addExact`/`Math.multiplyExact`;loan 决策时间用 `ts`(=`cmd.timestamp`),**禁 `System.currentTimeMillis()`** 于 LTV/期限/size 判定。
- **单引擎**:只有一个 `LiquidationEngine`;`LoanLiquidationEngine` 是它拥有的**稳定单例**(构造函数造一次,`updateProvider` 不再 `new`)。loan 经 `engine.getXxx()` 借 provider、`engine.getCommandSubmitter()` 借 submitter,`checkLoans` 由 `checkPositions` 在 `isRunning()` 门控后委托调用。
- **对称维护**:期货索引 `symbolToUsers` 由 `RiskEngine` 开/平仓维护;loan 索引由 `LoanCommandHandlers` 在 loan 命令 apply 维护。都不 gate(全节点确定性),不序列化,快照恢复经各自 rebuild。
- **保留 `submit(ApiCommand, Runnable)` 签名**,loan 传 `null`(不删 onApplied,不碰 submitter/scheduler/server)。
- **backstop 保留**:`LIQUIDATION_SCAN` 全扫兜利息/期限。
- **不改** force-sell 撮合/结算/记账、margin-call 通知与其 5min 节流、reprice。
- **安全性质**:stale/不完整 loan 索引只损延迟不损正确性(backstop 兜底);cross over-approximate 多查是无害 no-op。
- **retry 节流完全删除**(对齐期货),卡单靠 `stuckLiqAttempts` 容差爬梯 + 成交即停自限。

---

## File Structure

**生产(全部在 loan / liquidation 包内,不碰 RiskEngine/server)**
- `processors/liquidation/LiquidationEngine.java` — loan 引擎单例化(ctor 造);`checkPositions` 末尾委托 `loanLiquidationEngine.checkLoans(cmd)`;`checkUser` 删掉 `loan.check()`;`updateProvider` 加 `rebuildIndices()`(Task 2)。
- `processors/loan/LoanLiquidationEngine.java` — 删 `LaneState`/inFlight/retry;`check(up)`→`check(up,ts)`;新增 `checkLoans(cmd)`(targeted/backstop 选择器);force-sell/margin-call `submit(cmd,null)`;两索引 + 维护/查询/重建(Task 2)。
- `processors/loan/LoanCommandHandlers.java` — 缓存 `loanLiquidationEngine` 字段;loan 命令 apply 时调维护 hook(Task 2)。

**测试**
- `tests/unit/LoanLiquidationEngineTest.java` — 删 inFlight/throttle 用例、`check(up,ts)` sweep、重写 failover、加索引维护单测。
- `tests/integration/ITLoanTargetedLiquidation.java`(新建) — loan-only 用户抵押价暴跌 → 无 scan 即时强平。
- `tests/util/ExchangeTestContainer.java` — 加 `enableLiquidationEngines()`(只启不发 scan)。

---

## Task 1: 结构重构 + LaneState 清理

把 loan 检测从"挂 checkUser"重构成 `LoanLiquidationEngine.checkLoans(cmd)`,loan 引擎单例化,并删 inFlight/retry。**本 task 的 `checkLoans` 只做 backstop 全扫;targeted 分支留空占位(Task 2 接索引)**——中间态:targeted 价格事件暂不即时触发 loan(仍由 backstop SCAN 覆盖,等价今天 loan-only 用户路径),Task 2 恢复并增强。

**Files:**
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/LiquidationEngine.java`
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/loan/LoanLiquidationEngine.java`
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/loan/LoanCommandHandlers.java`
- Test: `exchange-core/src/test/java/exchange/core2/tests/unit/LoanLiquidationEngineTest.java`

**Interfaces:**
- Produces: `LoanLiquidationEngine.checkLoans(OrderCommand cmd)`、`LoanLiquidationEngine.check(UserProfile up, long ts)`。
- Consumes: `LiquidationEngine.getSymbolSpecificationProvider()/getUserProfileService()/getLastPriceCache()/getCurrencySpecificationProvider()/getLoanService()/getEventsHelper()/getCommandSubmitter()`(loan 经 `engine` 现取);`LiquidationCommandSubmitter.submit(ApiCommand, Runnable)`(传 null);`stuckLiqAttempts`(不动)、`toleranceBpsFor`(保留)。

- [ ] **Step 1: `LiquidationEngine` — loan 引擎单例化**

构造函数末尾加(`symbolToUsers` 初始化之后):
```java
        this.symbolToUsers = new IntObjectHashMap<MutableLongSet>();
        // loan 子引擎构造一次即稳定单例:LoanCommandHandlers 缓存的 ref 与本引擎检测路径始终同一实例,
        // 避免快照恢复重造导致"维护写一个实例、检测读另一个"的索引分裂。provider 由子引擎运行时经本引擎现取。
        this.loanLiquidationEngine = new LoanLiquidationEngine(this);
```
`updateProvider` 里删掉重造那行:
```java
        this.loanService = loanSvc;
        // this.loanLiquidationEngine = new LoanLiquidationEngine(this);   ← 删这行
        symbolToUsers.clear();
```

- [ ] **Step 2: `LiquidationEngine.checkPositions` 委托 checkLoans;`checkUser` 删 loan.check**

`checkPositions` 末尾(两个分支之后)加一行委托:
```java
    public void checkPositions(OrderCommand cmd) {
        if (!isRunning()) {
            return;
        }
        if (cmd.symbol >= 0) {
            MutableLongSet holders = symbolToUsers.get(cmd.symbol);
            if (holders != null) {
                holders.forEach(uid -> checkUser(userProfileService.getUserProfile(uid), cmd.timestamp));
            }
        } else {
            userProfileService.getUserProfiles().forEachValue(userProfile -> {
                checkUser(userProfile, cmd.timestamp);
            });
        }
        loanLiquidationEngine.checkLoans(cmd);   // ← loan 检测委托(leader gate 已在上面 isRunning 生效)
    }
```
`checkUser` 删掉末尾的 `loanLiquidationEngine.check(userProfile);`(loan 不再挂 checkUser,checkUser 变纯期货)。

- [ ] **Step 3: `LoanLiquidationEngine` — 加 checkLoans;check(up,ts);删 LaneState/inFlight/retry**

改动逐处:
1. 删 imports `MultiReaderSet`、`Sets`。加 `import exchange.core2.core.common.cmd.OrderCommand;`、`import org.eclipse.collections.api.set.primitive.MutableLongSet;`、`import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;`。
2. 删 `LaneState` 嵌套类,换两个 marginCall 节流 map:
```java
    private static final long MS_PER_DAY = 86400L * 1_000L;
    private static final long MARGIN_CALL_THROTTLE_MS = 5L * 60L * 1_000L;
    private static final byte LOAN_MODE_ISOLATED = 0;
    private static final byte LOAN_MODE_CROSS = 1;
    private final LiquidationEngine engine;

    // margin-call 通知节流(leader-local,best-effort):isolated keyed by loanId / cross keyed by uid
    private final LongLongHashMap isolatedMarginCallThrottleMs = new LongLongHashMap();
    private final LongLongHashMap crossMarginCallThrottleMs = new LongLongHashMap();

    public LoanLiquidationEngine(LiquidationEngine engine) {
        this.engine = engine;
    }
```
3. 新增检测入口 `checkLoans`(**本 task targeted 分支占位,只做 backstop**):
```java
    /**
     * loan 强平检测入口,由 {@link LiquidationEngine#checkPositions} 委托调用(调用方已过 isRunning leader gate)。
     * {@code cmd.symbol >= 0} targeted(Task 2 接 loan 索引);{@code cmd.symbol < 0}(LIQUIDATION_SCAN)backstop 全扫。
     */
    public void checkLoans(OrderCommand cmd) {
        if (cmd.symbol >= 0) {
            // TODO(Task 2): targeted —— collectAffectedLoanUsers(spec, uids) 后逐个 check
            return;
        }
        engine.getUserProfileService().getUserProfiles().forEachValue(up -> check(up, cmd.timestamp));
    }
```
4. `check(UserProfile)` → `check(UserProfile, long ts)`,内部 `System.currentTimeMillis()` 全改 `ts`。方法体(含 `checkIsolatedLoan`/`checkCrossLoan` 都加 `ts` 参数)按现有逻辑,把 `nowMs`/`currentTickMs` 全部替换成传入的 `ts`:
```java
    public void check(UserProfile userProfile, long ts) {
        userProfile.isolatedLoans.forEachValue(loan -> checkIsolatedLoan(loan, ts));
        checkCrossLoan(userProfile, ts);
    }
```
   - `checkIsolatedLoan(IsolatedLoanRecord loan, long ts)`:删首行 `isolated.inFlight.contains` 判断(只留 `loan.isEmpty()`);`calculateDisplayInterest(loan, ts)`;期限 `(ts - loan.openedAtTs)`;越线时**直接** `publishIsolatedForceSell(loan, spec, priceRecord.markPrice, toleranceBpsFor(loan.stuckLiqAttempts), ts)`(删 `stuckLiqThrottled` 判断);marginCall 走 `sendMarginCallIfNotThrottled(isolatedMarginCallThrottleMs, loan.loanId, loan.uid, loan.loanId, LOAN_MODE_ISOLATED, loan.loanCurrency, ltvBps, marginCallLtvBps, ts)`。
   - `checkCrossLoan(UserProfile up, long ts)`:删 `cross.inFlight.contains` 判断;`calculateCrossAccountLtvBps(up, ts, ...)`;越线 `publishCrossForceSell(up, loanService, ts, null)`;marginCall 走 `sendMarginCallIfNotThrottled(crossMarginCallThrottleMs, up.uid, up.uid, 0L, LOAN_MODE_CROSS, 0, ltvBps, crossMarginCallLtvBps, ts)`。
5. `publishIsolatedForceSell(..., long ts)` / `publishCrossForceSell(..., long ts)`:参数 `currentTickMs`→`ts`;删 `stuckLiqThrottled` 那两行;结尾从 `publishTracked*` 改直接 `engine.getCommandSubmitter().submit(cmd, null);`。
6. `sendMarginCallIfNotThrottled` 改签名用具体 map + `ts`:
```java
    private void sendMarginCallIfNotThrottled(LongLongHashMap throttleMap, long throttleKey, long uid, long loanId,
        byte mode, int loanCurrency, long ltvBps, long thresholdBps, long ts) {
        if (ts - throttleMap.get(throttleKey) < MARGIN_CALL_THROTTLE_MS)
            return;
        throttleMap.put(throttleKey, ts);
        FundEvent event =
            engine.getEventsHelper().sendLoanMarginCallEvent(uid, loanId, mode, loanCurrency, ltvBps, thresholdBps);
        engine.getCommandSubmitter().submit(ApiSystemLiquidationNotify.builder().fundEvent(event).build(), null);
    }
```
7. 删掉:`isIsolatedLoanInFlight`、`isCrossLoanInFlight`、`publishTrackedIsolated`、`publishTrackedCross`、`publishTracked`、`stuckLiqThrottled`。**保留** `toleranceBpsFor`、`limitPriceWithTolerance`、Cross 选币/选笔/定量方法不变。

- [ ] **Step 4: `LoanCommandHandlers` 缓存稳定单例 ref**

字段 + 构造(同包,无需 import):
```java
    private final RiskEngine engine;
    // loan 强平子引擎(稳定单例,随 LiquidationEngine 构造一次):loan 命令 apply 时在这里维护其 targeted 索引(Task 2)。
    private final LoanLiquidationEngine loanLiquidationEngine;

    public LoanCommandHandlers(RiskEngine engine) {
        this.engine = engine;
        this.loanLiquidationEngine = engine.getLiquidationEngine().getLoanLiquidationEngine();
    }
```
(本 task 只缓存,不加维护 hook 调用——Task 2 才加。)

- [ ] **Step 5: 修 `LoanLiquidationEngineTest`**

删除失效用例(断言已删行为):`inFlight_startsEmpty_forBothLanes`、`publishTrackedIsolated_addsLoanIdToInFlight`、`publishTrackedIsolated_onAppliedCallbackClearsInFlight`、`publishTrackedIsolated_publisherThrows_cleansUpAndRethrows`、`publishTrackedCross_addsUidToInFlight`、`publishTrackedCross_onAppliedCallbackClearsInFlight`、`publishTrackedCross_publisherThrows_cleansUpAndRethrows`、`check_isolatedInFlightLoan_skipsWithoutTouchingPriceCache`、`check_crossInFlight_skipsLtvCompute`、`stuckLoan_reFireThrottled_withinWindow`。删不再用的 import(`AtomicReference` 等)。

Sweep:`scanner.check(up)` → `scanner.check(up, OPENED_AT_MS)`(所有调用点)。`submit` 断言签名不变(仍二参,`any()` 匹配 null,`.submit(x, any())` **不用改**)。

重写 `failover_freshScanner_reSizesFromReducedState_noDoubleLiquidation` 为(去 in-flight 去重断言,保留 re-size,submit 断言保持二参):
```java
    @Test
    void reDetect_reSizesFromReducedState_afterPartialLiquidation() {
        final int WBTC = 10;
        final int SYMBOL_NI = 200;
        currencyProvider.addCurrency(CoreCurrencySpecification.builder().id(WBTC).name("WBTC").digit(2).build());
        CoreSymbolSpecification spec = CoreSymbolSpecification.builder()
                .symbolId(SYMBOL_NI).type(SymbolType.CURRENCY_EXCHANGE_PAIR)
                .baseCurrency(WBTC).quoteCurrency(USDT).baseScaleK(1).quoteScaleK(1)
                .loanConfig(SymbolLoanSpecification.builder()
                        .initialLtvBps(6000).liquidationLtvBps(8000).marginCallLtvBps(7000)
                        .collateralWeightBps(9000).build()).build();
        specProvider.registerSymbol(SYMBOL_NI, spec);
        LastPriceCacheRecord price = new LastPriceCacheRecord();
        price.markPrice = 50_000L;
        priceCache.put(SYMBOL_NI, price);

        IsolatedLoanRecord loan = new IsolatedLoanRecord(UID, LOAN_ID, WBTC, USDT, 0, OPENED_AT_MS);
        loan.symbolId = SYMBOL_NI;
        loan.collateralAmount = 300L;          // 3 WBTC
        loan.outstandingPrincipal = 140_000L;  // LTV 93% ≥ 80% → underwater
        up.isolatedLoans.put(LOAN_ID, loan);

        scanner.check(up, OPENED_AT_MS);
        ArgumentCaptor<ApiCommand> cap = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher, times(1)).submit(cap.capture(), any());
        assertEquals(3L, ((ApiLoanForceLiquidate) cap.getValue()).size);

        loan.collateralAmount = 100L;         // 1 WBTC 剩余
        loan.outstandingPrincipal = 45_000L;  // LTV 90% 仍 ≥ 80%

        scanner.check(up, OPENED_AT_MS);
        ArgumentCaptor<ApiCommand> cap2 = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher, times(2)).submit(cap2.capture(), any());
        ApiLoanForceLiquidate reSized =
                (ApiLoanForceLiquidate) cap2.getAllValues().get(cap2.getAllValues().size() - 1);
        assertEquals(1L, reSized.size, "按 apply 后剩余抵押(1 张)重定 size,不会用原始 3 张 → 无过量强平");
    }
```
`toleranceLadder_widensWithStuckAttempts`、`freshLoan_notThrottled_firesImmediately` 保留(仅 `check(up)`→`check(up, OPENED_AT_MS)` sweep)。

- [ ] **Step 6: 跑单测 + 编译**

Run: `mvn -q -pl exchange-core test -Dtest=LoanLiquidationEngineTest -DfailIfNoTests=false`
Expected: PASS。
Run: `mvn -q -pl exchange-core test-compile`
Expected: BUILD SUCCESS。

- [ ] **Step 7: Commit**
```bash
git add -A
git commit -m "refactor(loan): 检测入口重构为 checkLoans + loan 引擎单例化 + 删 in-flight/retry 节流"
```

---

## Task 2: loan 索引 + 维护 hook + checkLoans targeted

**Files:**
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/loan/LoanLiquidationEngine.java`
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/loan/LoanCommandHandlers.java`
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/LiquidationEngine.java`(`updateProvider` 尾部加 rebuild)
- Test: `exchange-core/src/test/java/exchange/core2/tests/unit/LoanLiquidationEngineTest.java`

**Interfaces:**
- Produces: `onIsolatedLoanOpened(long uid, int symbolId)`、`onIsolatedLoanClosed(UserProfile up, int symbolId)`、`syncCrossExposure(UserProfile up)`、`collectAffectedLoanUsers(CoreSymbolSpecification spec, MutableLongSet sink)`、`rebuildIndices()`。

- [ ] **Step 1: 先写索引维护单测(失败)** —— 直接查询验证成员

在 `LoanLiquidationEngineTest` 末尾加(`scanner` 即 LoanLiquidationEngine,经 mock `engine` 提供 specProvider/userProfileService 等):
```java
    @Test
    void isolatedIndex_openThenClose_membershipTracked() {
        org.eclipse.collections.api.set.primitive.MutableLongSet sink =
                new org.eclipse.collections.impl.set.mutable.primitive.LongHashSet();
        CoreSymbolSpecification spec = specProvider.getSymbolSpecification(SYMBOL);
        scanner.onIsolatedLoanOpened(UID, SYMBOL);
        scanner.collectAffectedLoanUsers(spec, sink);
        assertTrue(sink.contains(UID));
        sink.clear();
        scanner.onIsolatedLoanClosed(up, SYMBOL);   // up 上无该 symbol 其它 loan → 摘除
        scanner.collectAffectedLoanUsers(spec, sink);
        assertFalse(sink.contains(UID));
    }

    @Test
    void crossIndex_syncExposure_indexesByCollateralAndLoanCurrency() {
        up.crossLoanCollateral.put(BTC, 5L);
        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, USDT, 0, OPENED_AT_MS);
        loan.symbolId = SYMBOL; loan.outstandingPrincipal = 30_000L;
        up.crossLoans.put(LOAN_ID, loan);
        scanner.syncCrossExposure(up);
        org.eclipse.collections.api.set.primitive.MutableLongSet sink =
                new org.eclipse.collections.impl.set.mutable.primitive.LongHashSet();
        scanner.collectAffectedLoanUsers(specProvider.getSymbolSpecification(SYMBOL), sink);  // base=BTC/quote=USDT
        assertTrue(sink.contains(UID));
    }

    @Test
    void crossIndex_fullExit_purgesUid() {
        up.crossLoanCollateral.put(BTC, 5L);
        scanner.syncCrossExposure(up);
        up.crossLoanCollateral.put(BTC, 0L);   // 全退出
        scanner.syncCrossExposure(up);
        org.eclipse.collections.api.set.primitive.MutableLongSet sink =
                new org.eclipse.collections.impl.set.mutable.primitive.LongHashSet();
        scanner.collectAffectedLoanUsers(specProvider.getSymbolSpecification(SYMBOL), sink);
        assertFalse(sink.contains(UID));
    }
```
Run 单个用例确认 FAIL(方法未定义)。

- [ ] **Step 2: 加索引字段 + 维护/查询/重建方法** —— `LoanLiquidationEngine`

imports 加 `IntObjectHashMap`(`org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap`)。字段:
```java
    // targeted 触发索引:全节点确定性维护(loan 命令 apply)、不序列化(rebuildIndices 重建)
    private final IntObjectHashMap<MutableLongSet> isolatedLoanSymbolToUsers = new IntObjectHashMap<>();
    private final IntObjectHashMap<MutableLongSet> crossLoanCurrencyToUsers = new IntObjectHashMap<>();
```
方法:
```java
    public void onIsolatedLoanOpened(long uid, int symbolId) {
        isolatedLoanSymbolToUsers.getIfAbsentPut(symbolId, LongHashSet::new).add(uid);
    }

    public void onIsolatedLoanClosed(UserProfile up, int symbolId) {
        boolean holdsOther = up.isolatedLoans.anySatisfy(l -> !l.isEmpty() && l.symbolId == symbolId);
        if (holdsOther) return;
        MutableLongSet s = isolatedLoanSymbolToUsers.get(symbolId);
        if (s != null) { s.remove(up.uid); if (s.isEmpty()) isolatedLoanSymbolToUsers.remove(symbolId); }
    }

    public void syncCrossExposure(UserProfile up) {
        up.crossLoanCollateral.forEachKeyValue((currency, amount) -> {
            if (amount > 0) crossLoanCurrencyToUsers.getIfAbsentPut(currency, LongHashSet::new).add(up.uid);
        });
        up.crossLoans.forEachValue(loan -> {
            if (!loan.isEmpty()) crossLoanCurrencyToUsers.getIfAbsentPut(loan.loanCurrency, LongHashSet::new).add(up.uid);
        });
        boolean hasLoan = up.crossLoans.anySatisfy(l -> !l.isEmpty());
        boolean hasCollateral = up.crossLoanCollateral.anySatisfy(a -> a > 0);
        if (!hasLoan && !hasCollateral) purgeCrossUser(up.uid);
    }

    // 部分币种退出容忍 stale(无害 over-trigger,rebuild 时清);仅全退出精确清除
    private void purgeCrossUser(long uid) {
        for (int currency : crossLoanCurrencyToUsers.keySet().toArray()) {
            MutableLongSet s = crossLoanCurrencyToUsers.get(currency);
            if (s != null) { s.remove(uid); if (s.isEmpty()) crossLoanCurrencyToUsers.remove(currency); }
        }
    }

    /** 把受 symbol S(base/quote)价格影响的 loan 持有者(isolated 直接 + cross 两币种)并入 sink。 */
    public void collectAffectedLoanUsers(CoreSymbolSpecification spec, MutableLongSet sink) {
        MutableLongSet iso = isolatedLoanSymbolToUsers.get(spec.symbolId);
        if (iso != null) sink.addAll(iso);
        MutableLongSet base = crossLoanCurrencyToUsers.get(spec.baseCurrency);
        if (base != null) sink.addAll(base);
        MutableLongSet quote = crossLoanCurrencyToUsers.get(spec.quoteCurrency);
        if (quote != null) sink.addAll(quote);
    }

    /** 从用户态重建两索引(快照恢复,LiquidationEngine.updateProvider 调)。 */
    public void rebuildIndices() {
        isolatedLoanSymbolToUsers.clear();
        crossLoanCurrencyToUsers.clear();
        engine.getUserProfileService().getUserProfiles().forEachValue(up -> {
            up.isolatedLoans.forEachValue(loan -> { if (!loan.isEmpty()) onIsolatedLoanOpened(up.uid, loan.symbolId); });
            syncCrossExposure(up);
        });
    }
```

- [ ] **Step 3: `checkLoans` targeted 分支接索引**

把 Task 1 的占位 TODO 换成实查:
```java
    public void checkLoans(OrderCommand cmd) {
        if (cmd.symbol >= 0) {
            CoreSymbolSpecification spec = engine.getSymbolSpecificationProvider().getSymbolSpecification(cmd.symbol);
            if (spec == null) return;
            MutableLongSet uids = new LongHashSet();
            collectAffectedLoanUsers(spec, uids);
            uids.forEach(uid -> check(engine.getUserProfileService().getUserProfile(uid), cmd.timestamp));
            return;
        }
        engine.getUserProfileService().getUserProfiles().forEachValue(up -> check(up, cmd.timestamp));
    }
```
Run: `mvn -q -pl exchange-core test -Dtest=LoanLiquidationEngineTest -DfailIfNoTests=false` → 索引单测 PASS。

- [ ] **Step 4: `LiquidationEngine.updateProvider` 尾部重建**

`updateProvider` 方法结束前(现有 symbolToUsers 重建循环之后)加:
```java
        loanLiquidationEngine.rebuildIndices();
```

- [ ] **Step 5: `LoanCommandHandlers` 挂维护 hook**

用缓存字段 `loanLiquidationEngine`,在 loan 生命周期插入:
1. `handleLoanCreate` `up.isolatedLoans.put(loanId, loan);` 之后:`loanLiquidationEngine.onIsolatedLoanOpened(cmd.uid, loan.symbolId);`
2. `handleLoanRepay` / `handleLoanReleaseCollateral` 里 `if (loan.isEmpty()) { up.isolatedLoans.remove(loanId); ... }` 内、remove 之后:`loanLiquidationEngine.onIsolatedLoanClosed(up, loan.symbolId);`
3. `postProcessLoanForceLiquidate` 两处 `takerUp.isolatedLoans.remove(loanId);` 之后:`loanLiquidationEngine.onIsolatedLoanClosed(takerUp, loan.symbolId);`
4. Cross 五处末尾(SUCCESS 前)加 `loanLiquidationEngine.syncCrossExposure(up);`(postProcess 用 `takerUp`):`handleLoanCrossAddCollateral`、`handleLoanCrossWithdrawCollateral`、`handleLoanCrossBorrow`、`handleLoanCrossRepay`、`postProcessLoanCrossForceLiquidate`。

> 注:hook 在全节点确定性 apply 内跑(不 gate),follower 保持索引热。

- [ ] **Step 6: 编译**

Run: `mvn -q -pl exchange-core test-compile` → BUILD SUCCESS。

- [ ] **Step 7: Commit**
```bash
git add -A
git commit -m "feat(loan): targeted 强平索引 + 维护 hook + checkLoans 接索引 + updateProvider 重建"
```

---

## Task 3: targeted IT + 全量回归

**Files:**
- Create: `exchange-core/src/test/java/exchange/core2/tests/integration/ITLoanTargetedLiquidation.java`
- Modify: `exchange-core/src/test/java/exchange/core2/tests/util/ExchangeTestContainer.java`

- [ ] **Step 1: 容器 helper `enableLiquidationEngines`**

`ExchangeTestContainer.java`(triggerLiquidation 附近):
```java
    /** 只启动各分片 LiquidationEngine(令 isRunning()=true),不发 scan——用于验证纯 targeted 触发。 */
    public void enableLiquidationEngines() {
        if (exchangeCore.getLiquidationEngines() == null) return;
        exchangeCore.getLiquidationEngines().forEach(LiquidationEngine::start);
    }
```

- [ ] **Step 2: 写 targeted IT** —— 模型 `ITLoanForceLiquidatePipeline`

Create `ITLoanTargetedLiquidation.java`:loan-only 用户,启用引擎后**仅**用抵押 spot 对 MARKPRICE 暴跌触发,**不调 triggerLiquidation**,轮询到抵押被消费:
```java
package exchange.core2.tests.integration;

import static exchange.core2.tests.util.TestConstants.CURRENECY_ETH;
import static exchange.core2.tests.util.TestConstants.CURRENECY_XBT;
import static exchange.core2.tests.util.TestConstants.SYMBOLSPEC_ETH_XBT;
import static exchange.core2.tests.util.TestConstants.SYMBOL_EXCHANGE;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.TimeUnit;

import exchange.core2.core.common.MarginMode;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.api.ApiLoanCreate;
import exchange.core2.core.common.api.ApiPlaceOrder;
import exchange.core2.core.common.api.ApiPoolDeposit;
import exchange.core2.core.common.api.binary.BatchAddLoanCommand;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.config.PerformanceConfiguration;
import exchange.core2.tests.util.ExchangeTestContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

/**
 * loan 强平 targeted 事件驱动:纯 loan 用户(无期货持仓),抵押 spot 对 MARKPRICE 暴跌即时触发强平,全程不发 LIQUIDATION_SCAN。
 * 证明 loan 索引把 targeted 路径覆盖到 loan-only 用户。
 */
@Slf4j
class ITLoanTargetedLiquidation {
    private static final long BORROWER = 6001L;
    private static final long LP = 6002L;
    private static final long LOAN_ID = 77L;
    private static final long OPEN_MARK = 1000L;
    private static final long CRASH_MARK = 500L;
    private static final long ETH_COLLATERAL = 100L;
    private static final long XBT_PRINCIPAL = 50_000L;
    private static final long POOL_FUND = 1_000_000L;

    @Test
    public void collateralPriceCrash_targetedTriggersForceSell_withoutScan() throws Exception {
        try (final ExchangeTestContainer c = ExchangeTestContainer.create(PerformanceConfiguration.baseBuilder().build())) {
            c.skipGlobalReconcileOnClose();
            c.addCurrency(CURRENECY_ETH, 0);
            c.addCurrency(CURRENECY_XBT, 0);
            c.addSymbol(SYMBOLSPEC_ETH_XBT);
            c.initMarkPrice(SYMBOL_EXCHANGE, OPEN_MARK);
            c.sendBinaryDataCommandSync(
                BatchAddLoanCommand.ofSymbol(SYMBOL_EXCHANGE, 6000, 8000, 7000, Long.MAX_VALUE, 365, 10000), 5000);
            c.submitCommandSync(ApiPoolDeposit.builder().externalId(1L).shardId(0)
                .currency(CURRENECY_XBT).amount(POOL_FUND).build(), CommandResultCode.SUCCESS);
            c.initOneUser(BORROWER);
            c.initOneUser(LP);
            c.addMoneyToUser(BORROWER, CURRENECY_ETH, ETH_COLLATERAL);
            c.addMoneyToUser(LP, CURRENECY_XBT, ETH_COLLATERAL * OPEN_MARK * 2);
            c.submitCommandSync(ApiLoanCreate.builder().externalId(2L).uid(BORROWER).loanId(LOAN_ID)
                .symbol(SYMBOL_EXCHANGE).collateralAmount(ETH_COLLATERAL).principal(XBT_PRINCIPAL).build(),
                CommandResultCode.SUCCESS);
            // LP 在暴跌价挂 BID 接强平卖单
            c.submitCommandSync(ApiPlaceOrder.builder().uid(LP).orderId(1000L).action(OrderAction.BID)
                .size(ETH_COLLATERAL).price(CRASH_MARK).reservePrice(CRASH_MARK).symbol(SYMBOL_EXCHANGE)
                .orderType(OrderType.GTC).marginMode(MarginMode.ISOLATED).build(), CommandResultCode.SUCCESS);

            c.enableLiquidationEngines();  // isRunning=true,不发 scan
            // 关键:仅抵押 spot 对 MARKPRICE 暴跌 → targeted 触发
            c.updateCurrentPriceTo((int) CRASH_MARK, SYMBOL_EXCHANGE, CURRENECY_XBT);

            final long deadline = System.currentTimeMillis() + 10_000L;
            boolean liquidated = false;
            while (System.currentTimeMillis() < deadline) {
                c.getApi().groupingControl(0, 1);
                long collateral = c.getUserProfile(BORROWER).getIsolatedLoans().stream()
                    .filter(l -> l.loanId == LOAN_ID).mapToLong(l -> l.collateralAmount).findFirst().orElse(0L);
                if (collateral < ETH_COLLATERAL) { liquidated = true; break; }
                TimeUnit.MILLISECONDS.sleep(20L);
            }
            assertTrue(liquidated,
                "抵押价暴跌应经 targeted 路径即时强平(无 scan);抵押未减少说明 loan 索引未命中 loan-only 用户");
        } catch (Exception e) {
            log.error("targeted loan 强平测试失败", e);
            fail(e.getMessage());
        }
    }
}
```
> 依赖(已核实):常量在 `exchange.core2.tests.util.TestConstants`;`SingleUserReportResult.getIsolatedLoans()` 返回 `List<IsolatedLoan>`,DTO 有 public `long loanId`/`long collateralAmount`。

Run: `mvn -q -pl exchange-core test -Dtest=ITLoanTargetedLiquidation -DfailIfNoTests=false` → PASS。

- [ ] **Step 3: 全量回归**

Run: `mvn -q -pl exchange-core test`
Expected: 全绿(unit + IT)。重点回归 loan 套件:`LoanLiquidationEngineTest`、`ITLoanForceLiquidatePipeline`、`ITLoanConservation`、`ITLoanFailoverSnapshot`(快照恢复后单例 + rebuild 正确)。
Run: `mvn -q -pl raft-exchange-server -am test`
Expected: 全绿(本 plan 不改 server;确认 loan/liquidation 改动经 raft 路径不回归)。

- [ ] **Step 4: Commit**
```bash
git add -A
git commit -m "test(loan): targeted 强平 IT + 全量回归确认"
```

---

## Self-Review 结果

- **Spec 覆盖**:检测入口重构(checkLoans)+ 单例化 + 清理 = Task 1;索引 + 维护 + targeted = Task 2;IT + 回归 = Task 3。全覆盖。
- **架构定稿(2026-07-16 user 定)**:单引擎;loan 数据/逻辑自洽在 loan 两类,借期货引擎的触发入口(checkPositions→checkLoans 一行委托)+ 基础设施;`checkUser` 纯期货;不碰 RiskEngine/submitter/scheduler/server;保留 `submit(cmd, Runnable)` 传 null。
- **中间态**:Task 1 后 targeted loan 暂空(backstop 覆盖),Task 2 补索引恢复并增强——已在 Task 1 标注。
- **占位符**:无 TBD;IT 依赖(`TestConstants`、`getIsolatedLoans()`)已核实。
- **类型一致性**:`checkLoans(OrderCommand)`、`check(UserProfile, long)`、`collectAffectedLoanUsers(spec, sink)`、`rebuildIndices()`、维护方法签名跨 task 一致;`IntObjectHashMap<MutableLongSet>` 与 `symbolToUsers` 同型。
