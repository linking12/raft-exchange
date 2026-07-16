# 现货借贷强平事件驱动化 实现 Plan (Plan 2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给现货借贷强平加 targeted 事件触发(抵押 spot 对 MARKPRICE 即时触发对应 loan 持有者检查),并清理 `LaneState` 冗余运行态。

**Architecture:** 沿用 Plan 1 期货的事件驱动模型。`LoanLiquidationEngine` 新增两个 loan 索引(isolated 按 symbolId、cross 按 currency),由 loan 命令的确定性 apply 全节点维护、`checkPositions` leader-gated 查询、`updateProvider` 重建。删 `inFlight`/`liqRetryThrottleMs`,靠 apply-side guard + on-lane 顺序 apply 兜重复,靠 backstop 全扫兜利息/期限。

**Tech Stack:** Java 11, exchange-core (LMAX Disruptor), Eclipse Collections (`IntObjectHashMap`/`MutableLongSet`/`LongLongHashMap`), JUnit 5 + Mockito + AssertJ/Hamcrest, JRaft。

**关联**: [spec](../specs/2026-07-16-loan-event-driven-liquidation-design.md) · Plan 1 [plan](2026-07-16-liquidation-futures-event-driven.md)

## Global Constraints

- **确定性**:所有整数运算用 `Math.addExact`/`Math.multiplyExact`;loan 决策时间用 `cmd.timestamp`(经 `ts` 传入),**禁用 `System.currentTimeMillis()`** 于任何影响 LTV/期限/size 的判定。
- **索引维护 = 全节点确定性、不 gate**(在 loan 命令 apply 内);**索引查询(`checkPositions`)= leader-gated**(父类 `isRunning()`);**索引不序列化**,快照恢复经 `updateProvider` 重建。
- **backstop 保留**:`LIQUIDATION_SCAN` 全扫不动,仍兜利息/期限强平。
- **不改** force-sell 的撮合/结算/记账(`LoanCommandHandlers` 的 pre/postProcess 业务逻辑)、margin-call 通知与其 5min 节流、动态利率 reprice。
- **安全性质**:stale/不完整索引只损延迟不损正确性(backstop 无条件兜底);cross over-approximate 多查是无害 no-op。
- **retry 节流完全删除**(对齐期货),卡单靠 `stuckLiqAttempts` 容差爬梯 + 成交即停自限。

---

## File Structure

**生产(exchange-core)**
- `processors/loan/LoanLiquidationEngine.java` — 核心。删 `LaneState`/inFlight/retry-throttle;`check(up)`→`check(up, ts)`;加两索引 + 维护/查询/重建方法。
- `processors/liquidation/LiquidationCommandSubmitter.java` — `submit(ApiCommand)`(去 `onApplied`)。
- `processors/liquidation/LiquidationScheduledService.java` — `submit` wrapper + `runOneIteration` 去 `onApplied`。
- `processors/liquidation/LiquidationEngine.java` — `checkPositions` targeted 分支并 loan 索引;`updateProvider` 调 `rebuildIndices`;`checkUser` 传 `ts` 给 loan。
- `processors/loan/LoanCommandHandlers.java` — 生命周期 hook 调用索引维护。

**生产(raft-exchange-server)**
- `server/exchange/ExchangeRuntime.java` — override lambda 去 `onApplied`。

**测试**
- `tests/unit/LoanLiquidationEngineTest.java` — 删 inFlight/throttle 用例、改 submit 断言、`check(up,ts)`、重写 failover、加索引维护单测。
- `tests/integration/ITLoanTargetedLiquidation.java`(新建) — targeted MARKPRICE 触发 loan-only / cross 用户强平(无 scan);backstop 仍兜利息。
- `tests/util/ExchangeTestContainer.java` — 加 `enableLiquidationEngines()`(只启引擎不发 scan)。

---

## Task 1: Cleanup bundle —— 删 inFlight / retry 节流 / onApplied,check(up,ts)

**Files:**
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/loan/LoanLiquidationEngine.java`
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/LiquidationCommandSubmitter.java`
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/LiquidationScheduledService.java`
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/LiquidationEngine.java:182`(`check(up)`→`check(up, ts)` 调用点)
- Modify: `raft-exchange-server/src/main/java/com/binance/raftexchange/server/exchange/ExchangeRuntime.java`
- Test: `exchange-core/src/test/java/exchange/core2/tests/unit/LoanLiquidationEngineTest.java`

**Interfaces:**
- Produces: `LiquidationCommandSubmitter.submit(ApiCommand cmd)`(单参);`LoanLiquidationEngine.check(UserProfile up, long ts)`。
- Consumes: 现有 `stuckLiqAttempts`(持久化,不动)、`toleranceBpsFor` 爬梯(保留)。

- [ ] **Step 1: 改 `LiquidationCommandSubmitter` 接口为单参**

`LiquidationCommandSubmitter.java` 整体:

```java
package exchange.core2.core.processors.liquidation;

import exchange.core2.core.common.api.ApiCommand;

/**
 * 强平命令提交口:leader 侧把强平相关命令送去复制/应用。onApplied 回调已随 loan in-flight 去重删除。
 */
public interface LiquidationCommandSubmitter {
    void submit(ApiCommand cmd);
}
```

- [ ] **Step 2: 改父类 `LiquidationScheduledService` 的 submit wrapper 与 runOneIteration**

`LiquidationScheduledService.java` 里 `submit` 方法与 `runOneIteration`:

```java
    protected void runOneIteration() {
        if (shardId != 0) {
            return;
        }
        submit(ApiLiquidationScan.builder().build());
        submit(ApiRepriceLoanRates.builder().build());
    }

    protected final void submit(ApiCommand cmd) {
        if (commandSubmitter != null) {
            commandSubmitter.submit(cmd);
        }
    }
```

- [ ] **Step 3: 改 server override lambda 去 onApplied**

`ExchangeRuntime.java` 的 `overrideLiquidationCommandSubmitter` 里 submitter:

```java
        LiquidationCommandSubmitter submitter = cmd -> {
            if (!isLeader.getAsBoolean()) {
                return;
            }
            if (cmd instanceof ApiSystemLiquidationNotify) {
                // 下游事件不改 RiskEngine 状态,无需 raft;走 leader 本地 ringbuffer
                exchangeCalls.submitCommand(cmd);
                return;
            }
            commit.accept(ApiCommandConverters.liquidationCmdToRaftLog(cmd, System.currentTimeMillis()), err -> {
                if (err != null) {
                    log.warn("Liquidation raft consensus failed: cmd={}", cmd, err);
                }
            });
        };
```

- [ ] **Step 4: 重写 `LoanLiquidationEngine` —— 删 LaneState/inFlight/retry 节流,check(up,ts)**

`LoanLiquidationEngine.java` 关键改动(逐处):

1. 删 imports:`MultiReaderSet`、`Sets`。保留 `LongLongHashMap`。
2. 删 `LaneState` 嵌套类;换成两 lane 的 marginCall 节流 map 字段:

```java
    private static final long MS_PER_DAY = 86400L * 1_000L; // 期限强平用
    private static final long MARGIN_CALL_THROTTLE_MS = 5L * 60L * 1_000L; // 预警节流 5 min
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

3. `check` 加 `ts` 参数,内部 `nowMs` 全用 `ts`:

```java
    public void check(UserProfile userProfile, long ts) {
        userProfile.isolatedLoans.forEachValue(loan -> checkIsolatedLoan(loan, ts));
        checkCrossLoan(userProfile, ts);
    }

    private void checkIsolatedLoan(IsolatedLoanRecord loan, long ts) {
        if (loan.isEmpty())
            return;
        final CoreSymbolSpecification spec =
            engine.getSymbolSpecificationProvider().getSymbolSpecification(loan.symbolId);
        if (spec == null)
            return;
        final LastPriceCacheRecord priceRecord = engine.getLastPriceCache().get(spec.symbolId);
        if (priceRecord == null || priceRecord.markPrice == 0)
            return;

        final CoreCurrencySpecification baseSpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(loan.collateralCurrency);
        final CoreCurrencySpecification loanCurrencySpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(loan.loanCurrency);
        final long collateralValue = LoanService.collateralValueInQuoteCurrency(loan.collateralAmount, spec,
            priceRecord.markPrice, baseSpec, loanCurrencySpec);
        if (collateralValue < 0)
            return;

        // 真实债务含利息(accumulatedInterest + pending accrue),避免拖债避强平
        final long realDebt =
            Math.addExact(loan.outstandingPrincipal, engine.getLoanService().calculateDisplayInterest(loan, ts));
        final long ltvScaled = Math.multiplyExact(realDebt, LoanService.BPS_SCALE);

        // 期限只对 Isolated LOCKED(Fixed)生效;FLOATING 无期限
        final boolean termExpired = loan.rateMode == IsolatedLoanRecord.RATE_MODE_LOCKED
            && spec.loanConfig.maxTermDays > 0 && (ts - loan.openedAtTs) > spec.loanConfig.maxTermDays * MS_PER_DAY;

        if (termExpired || ltvScaled >= Math.multiplyExact(collateralValue, (long)spec.loanConfig.liquidationLtvBps)) {
            publishIsolatedForceSell(loan, spec, priceRecord.markPrice, toleranceBpsFor(loan.stuckLiqAttempts), ts);
            return;
        }

        final int marginCallLtvBps = spec.loanConfig.marginCallLtvBps;
        if (marginCallLtvBps > 0 && ltvScaled >= Math.multiplyExact(collateralValue, (long)marginCallLtvBps)) {
            final long ltvBps = collateralValue == 0 ? 0 : ltvScaled / collateralValue;
            sendMarginCallIfNotThrottled(isolatedMarginCallThrottleMs, loan.loanId, loan.uid, loan.loanId,
                LOAN_MODE_ISOLATED, loan.loanCurrency, ltvBps, marginCallLtvBps, ts);
        }
    }

    private void checkCrossLoan(UserProfile userProfile, long ts) {
        if (userProfile.crossLoans.isEmpty())
            return;

        final LoanService loanService = engine.getLoanService();

        // Cross 恒 Floating → 无期限,只做 LTV 强平/预警;numeraire 未配置时 ltvBps==0 不触发(保守)
        final long ltvBps = loanService.calculateCrossAccountLtvBps(userProfile, ts,
            engine.getSymbolSpecificationProvider(), engine.getCurrencySpecificationProvider(),
            engine.getLastPriceCache(), loanService.getGlobalConfig().numeraireCurrency);

        if (ltvBps >= loanService.getGlobalConfig().crossLiquidationLtvBps) {
            publishCrossForceSell(userProfile, loanService, ts, null);
        } else if (ltvBps >= loanService.getGlobalConfig().crossMarginCallLtvBps) {
            // Cross 账户级:loanId=0 / loanCurrency=0(无单笔归属)
            sendMarginCallIfNotThrottled(crossMarginCallThrottleMs, userProfile.uid, userProfile.uid, 0L,
                LOAN_MODE_CROSS, 0, ltvBps, loanService.getGlobalConfig().crossMarginCallLtvBps, ts);
        }
    }
```

4. `publishIsolatedForceSell` / `publishCrossForceSell`:删 `stuckLiqThrottled` 判定与 `publishTracked*`,改 `currentTickMs`→`ts`,直接 `engine.getCommandSubmitter().submit(cmd)`。改动处:

```java
    private void publishIsolatedForceSell(IsolatedLoanRecord loan, CoreSymbolSpecification spec, long markPrice,
        long toleranceBps, long ts) {
        CoreCurrencySpecification baseSpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(loan.collateralCurrency);
        long sellSizeLots = LoanService.collateralAmountToLots(loan.collateralAmount, spec, baseSpec);
        if (sellSizeLots <= 0) {
            log.warn("Isolated force-sell skip: sub-lot collateral (uid={} loanId={} collateral={})", loan.uid,
                loan.loanId, loan.collateralAmount);
            return;
        }
        long orderId =
            LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_ISOLATED, loan.uid, loan.loanId, ts);
        long limitPrice = limitPriceWithTolerance(markPrice, toleranceBps);
        ApiLoanForceLiquidate cmd =
            ApiLoanForceLiquidate.builder().uid(loan.uid).symbol(spec.symbolId).loanId(loan.loanId).price(limitPrice)
                .size(sellSizeLots).orderId(orderId).action(OrderAction.ASK).orderType(OrderType.IOC).build();
        engine.getCommandSubmitter().submit(cmd);
    }
```

`publishCrossForceSell` 尾部(删 `stuckLiqThrottled` 那两行 + `currentTickMs`→`ts` + publishTracked→submit):

```java
        long sellSize = calculateCrossSellSize(targetLoan, spec, priceRecord.markPrice, availableCollateral,
            ts, loanService, sellingCurrencySpec, loanCurrencySpec);
        if (sellSize <= 0) {
            log.warn("Cross force-sell abort: sellSize=0 (uid={} sellingCurrency={} available={})", up.uid,
                sellingCurrency, availableCollateral);
            return;
        }
        long orderId =
            LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_CROSS, up.uid, targetLoan.loanId, ts);
        long limitPrice = limitPriceWithTolerance(priceRecord.markPrice, toleranceBpsFor(targetLoan.stuckLiqAttempts));
        ApiLoanCrossForceLiquidate cmd = ApiLoanCrossForceLiquidate.builder().uid(up.uid).symbol(spec.symbolId)
            .targetLoanId(targetLoan.loanId).price(limitPrice).size(sellSize).orderId(orderId).action(OrderAction.ASK)
            .orderType(OrderType.IOC).build();
        engine.getCommandSubmitter().submit(cmd);
```

(`publishCrossForceSell` 签名把 `currentTickMs` 参数名改为 `ts`。)

5. `sendMarginCallIfNotThrottled`:lane 参数换成具体 map,`nowMs`→`ts`:

```java
    private void sendMarginCallIfNotThrottled(LongLongHashMap throttleMap, long throttleKey, long uid, long loanId,
        byte mode, int loanCurrency, long ltvBps, long thresholdBps, long ts) {
        if (ts - throttleMap.get(throttleKey) < MARGIN_CALL_THROTTLE_MS)
            return;
        throttleMap.put(throttleKey, ts);
        FundEvent event =
            engine.getEventsHelper().sendLoanMarginCallEvent(uid, loanId, mode, loanCurrency, ltvBps, thresholdBps);
        engine.getCommandSubmitter().submit(ApiSystemLiquidationNotify.builder().fundEvent(event).build());
    }
```

6. 删掉:`isIsolatedLoanInFlight`、`isCrossLoanInFlight`、`publishTrackedIsolated`、`publishTrackedCross`、`publishTracked`、`stuckLiqThrottled`。**保留** `toleranceBpsFor`、`limitPriceWithTolerance`、Cross 选币/选笔/定量方法不变。

- [ ] **Step 5: 改 `LiquidationEngine.checkUser` 调用点传 ts**

`LiquidationEngine.java:182`:

```java
        loanLiquidationEngine.check(userProfile, ts);
```

- [ ] **Step 6: 修 `LoanLiquidationEngineTest` 编译 + 删除失效用例**

删除以下用例(断言已删行为):`inFlight_startsEmpty_forBothLanes`、`publishTrackedIsolated_addsLoanIdToInFlight`、`publishTrackedIsolated_onAppliedCallbackClearsInFlight`、`publishTrackedIsolated_publisherThrows_cleansUpAndRethrows`、`publishTrackedCross_addsUidToInFlight`、`publishTrackedCross_onAppliedCallbackClearsInFlight`、`publishTrackedCross_publisherThrows_cleansUpAndRethrows`、`check_isolatedInFlightLoan_skipsWithoutTouchingPriceCache`、`check_crossInFlight_skipsLtvCompute`、`stuckLoan_reFireThrottled_withinWindow`。删对应 imports(`AtomicReference` 若不再用)。

全文件 mechanical sweep(两处 pattern):
- `scanner.check(up)` → `scanner.check(up, OPENED_AT_MS)`(所有调用点;`ts` 用 `OPENED_AT_MS` 即可,期限判定不受影响)。
- `.submit(any(), any())` → `.submit(any())`;`.submit(cap.capture(), any())` → `.submit(cap.capture())`;`.submit(captor.capture(), any())` → `.submit(captor.capture())`;`.submit(eq(cmd), any(Runnable.class))` 相关的用例已在删除清单里。
- `doAnswer(...).when(publisher).submit(any(), any(Runnable.class))` 相关用例已删除。

- [ ] **Step 7: 重写 failover 用例(去 in-flight 去重断言,保留 re-size)**

替换 `failover_freshScanner_reSizesFromReducedState_noDoubleLiquidation` 为:

```java
    @Test
    void reDetect_reSizesFromReducedState_afterPartialLiquidation() {
        // 强平决策是 replicated loan 状态的纯函数:一笔部分强平 apply 后(抵押 3→1 WBTC,仍 underwater),
        // 再次检测按剩余抵押重定 size(1 张),不会用原始 3 张过量强平。删 in-flight 去重后同 scanner 也会重发。
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
        verify(publisher, times(1)).submit(cap.capture());
        assertEquals(3L, ((ApiLoanForceLiquidate) cap.getValue()).size);

        // 一笔部分强平已 apply(抵押 3→1 WBTC,仍 underwater),再次检测
        loan.collateralAmount = 100L;         // 1 WBTC 剩余
        loan.outstandingPrincipal = 45_000L;  // LTV 90% 仍 ≥ 80%

        scanner.check(up, OPENED_AT_MS);
        ArgumentCaptor<ApiCommand> cap2 = ArgumentCaptor.forClass(ApiCommand.class);
        verify(publisher, times(2)).submit(cap2.capture());
        ApiLoanForceLiquidate reSized =
                (ApiLoanForceLiquidate) cap2.getAllValues().get(cap2.getAllValues().size() - 1);
        assertEquals(1L, reSized.size, "按 apply 后剩余抵押(1 张)重定 size,不会用原始 3 张 → 无过量强平");
    }
```

`toleranceLadder_widensWithStuckAttempts`、`freshLoan_notThrottled_firesImmediately` 保留(前者 `.submit(cap.capture())` sweep;后者改名 `underwaterLoan_firesForceSell` 可选,`.submit(any())` sweep)。`ArgumentMatchers.eq` / `Runnable` 相关 import 若不再用则删。

- [ ] **Step 8: 跑单测**

Run: `mvn -q -pl exchange-core test -Dtest=LoanLiquidationEngineTest -DfailIfNoTests=false`
Expected: PASS(force-sell 判定/爬梯/marginCall/re-size 全绿,无 inFlight/throttle 残留)。

- [ ] **Step 9: 编译全模块(签名跨模块改动)**

Run: `mvn -q -pl exchange-core,raft-exchange-server -am test-compile`
Expected: BUILD SUCCESS(接口单参改动在 server override 已同步)。

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "refactor(loan): 删 in-flight/retry 节流 + onApplied,决策对齐 cmd.timestamp"
```

---

## Task 2: Loan 索引 + 维护 hook

**Files:**
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/loan/LoanLiquidationEngine.java`
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/loan/LoanCommandHandlers.java`
- Test: `exchange-core/src/test/java/exchange/core2/tests/unit/LoanLiquidationEngineTest.java`

**Interfaces:**
- Produces:`LoanLiquidationEngine.onIsolatedLoanOpened(long uid, int symbolId)`、`onIsolatedLoanClosed(UserProfile up, int symbolId)`、`syncCrossExposure(UserProfile up)`、`collectAffectedLoanUsers(CoreSymbolSpecification spec, MutableLongSet sink)`、`rebuildIndices()`。
- Consumes(Task 3):`collectAffectedLoanUsers`(checkPositions)、`rebuildIndices`(updateProvider)。

- [ ] **Step 1: 先写维护单测(失败)**

在 `LoanLiquidationEngineTest` 末尾加(用直接 index 查询验证成员;`collectAffectedLoanUsers` 返回并集):

```java
    // ================================================================
    // Task 2: loan 索引维护
    // ================================================================

    @Test
    void isolatedIndex_openThenClose_membershipTracked() {
        org.eclipse.collections.api.set.primitive.MutableLongSet sink =
                new org.eclipse.collections.impl.set.mutable.primitive.LongHashSet();
        CoreSymbolSpecification spec = specProvider.getSymbolSpecification(SYMBOL);

        scanner.onIsolatedLoanOpened(UID, SYMBOL);
        scanner.collectAffectedLoanUsers(spec, sink);
        assertTrue(sink.contains(UID), "开仓后 symbolId 索引含 uid");

        // up 上没有该 symbol 的其它 loan → close 摘除
        sink.clear();
        scanner.onIsolatedLoanClosed(up, SYMBOL);
        scanner.collectAffectedLoanUsers(spec, sink);
        assertFalse(sink.contains(UID), "清空后摘除 uid");
    }

    @Test
    void isolatedIndex_close_keepsUidWhenOtherLoanOnSameSymbol() {
        IsolatedLoanRecord other = new IsolatedLoanRecord(UID, LOAN_ID + 1, BTC, USDT, 0, OPENED_AT_MS);
        other.symbolId = SYMBOL;
        other.collateralAmount = 1L;
        other.outstandingPrincipal = 10_000L; // 非空
        up.isolatedLoans.put(LOAN_ID + 1, other);

        scanner.onIsolatedLoanOpened(UID, SYMBOL);
        scanner.onIsolatedLoanClosed(up, SYMBOL); // up 上仍有 other → 不摘除

        org.eclipse.collections.api.set.primitive.MutableLongSet sink =
                new org.eclipse.collections.impl.set.mutable.primitive.LongHashSet();
        scanner.collectAffectedLoanUsers(specProvider.getSymbolSpecification(SYMBOL), sink);
        assertTrue(sink.contains(UID), "同 symbol 仍有其它非空 loan → 保留 uid");
    }

    @Test
    void crossIndex_syncExposure_indexesByCollateralAndLoanCurrency() {
        up.crossLoanCollateral.put(BTC, 5L);
        CrossLoanRecord loan = new CrossLoanRecord(UID, LOAN_ID, USDT, 0, OPENED_AT_MS);
        loan.symbolId = SYMBOL;
        loan.outstandingPrincipal = 30_000L;
        up.crossLoans.put(LOAN_ID, loan);

        scanner.syncCrossExposure(up);

        // MARKPRICE 落在 BTC/USDT(base=BTC,quote=USDT)→ 两币种任一都能命中 uid
        org.eclipse.collections.api.set.primitive.MutableLongSet sink =
                new org.eclipse.collections.impl.set.mutable.primitive.LongHashSet();
        scanner.collectAffectedLoanUsers(specProvider.getSymbolSpecification(SYMBOL), sink);
        assertTrue(sink.contains(UID), "cross 抵押币 BTC / 债务币 USDT 都索引到 uid");
    }

    @Test
    void crossIndex_fullExit_purgesUid() {
        up.crossLoanCollateral.put(BTC, 5L);
        scanner.syncCrossExposure(up);

        // 全退出:抵押清零、无 loan
        up.crossLoanCollateral.put(BTC, 0L);
        scanner.syncCrossExposure(up);

        org.eclipse.collections.api.set.primitive.MutableLongSet sink =
                new org.eclipse.collections.impl.set.mutable.primitive.LongHashSet();
        scanner.collectAffectedLoanUsers(specProvider.getSymbolSpecification(SYMBOL), sink);
        assertFalse(sink.contains(UID), "全退出后从索引摘除");
    }
```

Run: `mvn -q -pl exchange-core test -Dtest=LoanLiquidationEngineTest#isolatedIndex_openThenClose_membershipTracked -DfailIfNoTests=false`
Expected: FAIL(方法未定义 / 编译错)。

- [ ] **Step 2: 加索引字段 + imports**

`LoanLiquidationEngine.java` 顶部 imports 加:

```java
import org.eclipse.collections.api.set.primitive.MutableLongSet;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import exchange.core2.core.common.CrossLoanRecord;
```
(`CoreSymbolSpecification` 已 import。)

字段区加:

```java
    // targeted 触发索引:抵押 spot 对价格事件 → 命中受影响 loan 持有者。
    // 全节点确定性维护(loan 命令 apply 内)、不序列化(updateProvider 重建)。
    private final IntObjectHashMap<MutableLongSet> isolatedLoanSymbolToUsers = new IntObjectHashMap<>();
    private final IntObjectHashMap<MutableLongSet> crossLoanCurrencyToUsers = new IntObjectHashMap<>();
```

- [ ] **Step 3: 加维护 + 查询 + 重建方法**

`LoanLiquidationEngine.java` 加(放在 check 相关方法附近):

```java
    // ---- targeted 索引:维护 / 查询 / 重建 ----

    /** isolated loan 开仓:登记 uid 到 symbolId 索引。 */
    public void onIsolatedLoanOpened(long uid, int symbolId) {
        isolatedLoanSymbolToUsers.getIfAbsentPut(symbolId, LongHashSet::new).add(uid);
    }

    /** isolated loan 清空:仅当 uid 在该 symbolId 上再无其它非空 isolated loan 时摘除(HEDGE 式 holdsOther)。 */
    public void onIsolatedLoanClosed(UserProfile up, int symbolId) {
        boolean holdsOther = up.isolatedLoans.anySatisfy(l -> !l.isEmpty() && l.symbolId == symbolId);
        if (holdsOther) {
            return;
        }
        MutableLongSet s = isolatedLoanSymbolToUsers.get(symbolId);
        if (s != null) {
            s.remove(up.uid);
            if (s.isEmpty()) {
                isolatedLoanSymbolToUsers.remove(symbolId);
            }
        }
    }

    /** cross 敞口变更:把 uid 登记到当前所有敞口币种(抵押 amount>0 / 非空 loan 的 loanCurrency);全退出则清除。 */
    public void syncCrossExposure(UserProfile up) {
        up.crossLoanCollateral.forEachKeyValue((currency, amount) -> {
            if (amount > 0) {
                crossLoanCurrencyToUsers.getIfAbsentPut(currency, LongHashSet::new).add(up.uid);
            }
        });
        up.crossLoans.forEachValue(loan -> {
            if (!loan.isEmpty()) {
                crossLoanCurrencyToUsers.getIfAbsentPut(loan.loanCurrency, LongHashSet::new).add(up.uid);
            }
        });
        boolean hasLoan = up.crossLoans.anySatisfy(l -> !l.isEmpty());
        boolean hasCollateral = up.crossLoanCollateral.anySatisfy(a -> a > 0);
        if (!hasLoan && !hasCollateral) {
            purgeCrossUser(up.uid);
        }
    }

    // 部分币种退出容忍 stale(无害 over-trigger,updateProvider 重建时清);仅全退出走此精确清除。
    private void purgeCrossUser(long uid) {
        for (int currency : crossLoanCurrencyToUsers.keySet().toArray()) {
            MutableLongSet s = crossLoanCurrencyToUsers.get(currency);
            if (s != null) {
                s.remove(uid);
                if (s.isEmpty()) {
                    crossLoanCurrencyToUsers.remove(currency);
                }
            }
        }
    }

    /** 把受 symbol S(base/quote)价格影响的 loan 持有者(isolated 直接 + cross 两币种)并入 sink。 */
    public void collectAffectedLoanUsers(CoreSymbolSpecification spec, MutableLongSet sink) {
        MutableLongSet iso = isolatedLoanSymbolToUsers.get(spec.symbolId);
        if (iso != null) {
            sink.addAll(iso);
        }
        MutableLongSet base = crossLoanCurrencyToUsers.get(spec.baseCurrency);
        if (base != null) {
            sink.addAll(base);
        }
        MutableLongSet quote = crossLoanCurrencyToUsers.get(spec.quoteCurrency);
        if (quote != null) {
            sink.addAll(quote);
        }
    }

    /** 从用户态重建两索引(快照恢复,updateProvider 调用)。 */
    public void rebuildIndices() {
        isolatedLoanSymbolToUsers.clear();
        crossLoanCurrencyToUsers.clear();
        engine.getUserProfileService().getUserProfiles().forEachValue(up -> {
            up.isolatedLoans.forEachValue(loan -> {
                if (!loan.isEmpty()) {
                    onIsolatedLoanOpened(up.uid, loan.symbolId);
                }
            });
            syncCrossExposure(up);
        });
    }
```

Run: `mvn -q -pl exchange-core test -Dtest=LoanLiquidationEngineTest -DfailIfNoTests=false`
Expected: PASS(4 个索引维护单测 + 原有全绿)。

- [ ] **Step 4: 在 `LoanCommandHandlers` 挂 hook**

`LoanCommandHandlers.java` 加私有取当前 loan 引擎实例的 helper(每次取新,因 updateProvider 会重建实例):

```java
    private exchange.core2.core.processors.loan.LoanLiquidationEngine loanLiq() {
        return engine.getLiquidationEngine().getLoanLiquidationEngine();
    }
```

插入点:

1. `handleLoanCreate` —— `up.isolatedLoans.put(loanId, loan);` 之后:
```java
        loanLiq().onIsolatedLoanOpened(cmd.uid, loan.symbolId);
```

2. `handleLoanRepay` —— `if (loan.isEmpty()) { up.isolatedLoans.remove(loanId); ... }` 分支内(remove 之后):
```java
            loanLiq().onIsolatedLoanClosed(up, loan.symbolId);
```
(`loan.symbolId` 在 record 上,remove 后仍可读。)

3. `handleLoanReleaseCollateral` —— 同样 `if (loan.isEmpty()) { up.isolatedLoans.remove(loanId); ... }` 内:
```java
            loanLiq().onIsolatedLoanClosed(up, loan.symbolId);
```

4. `postProcessLoanForceLiquidate` —— 两处 `takerUp.isolatedLoans.remove(loanId);`(全额清 / isEmpty)之后各加:
```java
            loanLiq().onIsolatedLoanClosed(takerUp, loan.symbolId);
```

5. Cross 五处末尾(SUCCESS 返回前)加 `loanLiq().syncCrossExposure(up);`(postProcess 用 `takerUp`):
   - `handleLoanCrossAddCollateral`(`up`)
   - `handleLoanCrossWithdrawCollateral`(`up`)
   - `handleLoanCrossBorrow`(`up`)
   - `handleLoanCrossRepay`(`up`)
   - `postProcessLoanCrossForceLiquidate`(`takerUp`)

> 注:hook 在**所有节点**的确定性 apply 内跑(不 gate),follower 保持索引热。

- [ ] **Step 5: 编译**

Run: `mvn -q -pl exchange-core test-compile`
Expected: BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(loan): targeted 强平索引(isolated symbolId / cross currency)+ 生命周期维护 hook"
```

---

## Task 3: checkPositions targeted 集成 + updateProvider 重建 + IT

**Files:**
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/LiquidationEngine.java`
- Modify: `exchange-core/src/test/java/exchange/core2/tests/util/ExchangeTestContainer.java`
- Create: `exchange-core/src/test/java/exchange/core2/tests/integration/ITLoanTargetedLiquidation.java`

**Interfaces:**
- Consumes:Task 2 的 `collectAffectedLoanUsers` / `rebuildIndices`。

- [ ] **Step 1: `checkPositions` targeted 分支并 loan 索引**

`LiquidationEngine.java` 的 `checkPositions`(替换 targeted 分支):

```java
    public void checkPositions(OrderCommand cmd) {
        if (!isRunning()) {
            return;
        }
        if (cmd.symbol >= 0) {
            MutableLongSet holders = new LongHashSet();
            MutableLongSet futuresHolders = symbolToUsers.get(cmd.symbol);
            if (futuresHolders != null) {
                holders.addAll(futuresHolders);
            }
            CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(cmd.symbol);
            if (spec != null) {
                loanLiquidationEngine.collectAffectedLoanUsers(spec, holders);
            }
            holders.forEach(uid -> checkUser(userProfileService.getUserProfile(uid), cmd.timestamp));
        } else {
            userProfileService.getUserProfiles().forEachValue(userProfile -> {
                checkUser(userProfile, cmd.timestamp);
            });
        }
    }
```

(`LongHashSet` 已 import 于 LiquidationEngine;`MutableLongSet` 已 import。)

- [ ] **Step 2: `updateProvider` 尾部重建 loan 索引**

`LiquidationEngine.java` 的 `updateProvider`,在现有 `symbolToUsers` 重建循环之后(方法结束前)加:

```java
        loanLiquidationEngine.rebuildIndices();
```

- [ ] **Step 3: 加容器 helper `enableLiquidationEngines`(只启不 scan)**

`ExchangeTestContainer.java`(triggerLiquidation 附近):

```java
    /** 只启动各分片 LiquidationEngine(令 isRunning()=true),不发 scan——用于验证纯 targeted 触发。 */
    public void enableLiquidationEngines() {
        if (exchangeCore.getLiquidationEngines() == null) {
            return;
        }
        exchangeCore.getLiquidationEngines().forEach(LiquidationEngine::start);
    }
```

- [ ] **Step 4: 写 targeted IT(先失败)**

Create `ITLoanTargetedLiquidation.java`(模型:`ITLoanForceLiquidatePipeline`)。核心用例:loan-only 用户,启用引擎后**仅**用抵押 spot 对 MARKPRICE 暴跌触发强平,**不调 triggerLiquidation**,轮询到抵押被消费:

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
 * loan 强平的 targeted 事件驱动:纯 loan 用户(无期货持仓),抵押 spot 对 MARKPRICE 暴跌即时触发强平,
 * 全程不发 LIQUIDATION_SCAN。证明 Plan 2 的 loan 索引把 targeted 路径覆盖到 loan-only 用户。
 */
@Slf4j
class ITLoanTargetedLiquidation {

    private static final long BORROWER = 6001L;
    private static final long LP = 6002L;
    private static final long LOAN_ID = 77L;
    private static final long OPEN_MARK = 1000L;   // 开仓抵押价
    private static final long CRASH_MARK = 500L;   // 暴跌后价(抵押腰斩 → LTV 翻倍越强平线)
    private static final long ETH_COLLATERAL = 100L;
    private static final long XBT_PRINCIPAL = 50_000L; // 开仓 LTV ~ 50k/(100*1000)=50%;暴跌后 100%
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

            // LP 在暴跌价挂 BID 接强平卖单(force-sell 是 ASK IOC)
            c.submitCommandSync(ApiPlaceOrder.builder().uid(LP).orderId(1000L).action(OrderAction.BID)
                .size(ETH_COLLATERAL).price(CRASH_MARK).reservePrice(CRASH_MARK).symbol(SYMBOL_EXCHANGE)
                .orderType(OrderType.GTC).marginMode(MarginMode.ISOLATED).build(), CommandResultCode.SUCCESS);

            // 启用引擎(isRunning=true)但不发 scan
            c.enableLiquidationEngines();

            // 关键:仅抵押 spot 对 MARKPRICE 暴跌 → targeted 触发,不调 triggerLiquidation
            c.updateCurrentPriceTo((int) CRASH_MARK, SYMBOL_EXCHANGE, CURRENECY_XBT);

            // 轮询到抵押被强平消费(loan 记录抵押降到不足一张 / 归零),最长 10s
            final long deadline = System.currentTimeMillis() + 10_000L;
            boolean liquidated = false;
            while (System.currentTimeMillis() < deadline) {
                c.getApi().groupingControl(0, 1);
                // 读该 loan 剩余抵押;若已全额强平并清壳,loan 不在列表 → 视为 0(< 原始量 → 已强平)
                long collateral = c.getUserProfile(BORROWER).getIsolatedLoans().stream()
                    .filter(l -> l.loanId == LOAN_ID).mapToLong(l -> l.collateralAmount).findFirst().orElse(0L);
                if (collateral < ETH_COLLATERAL) {
                    liquidated = true;
                    break;
                }
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

> 依赖(已核实):常量来自 `exchange.core2.tests.util.TestConstants`(同 `ITLoanForceLiquidatePipeline`);`SingleUserReportResult.getIsolatedLoans()` 返回 `List<IsolatedLoan>`,DTO 有 public `long loanId` / `long collateralAmount`,故上面直接 stream 读取。`ApiPlaceOrder` 的 spot BID 走 ISOLATED(现货对 marginMode 语义同 `ITLoanForceLiquidatePipeline` 的 LP 挂单)。

Run: `mvn -q -pl exchange-core test -Dtest=ITLoanTargetedLiquidation -DfailIfNoTests=false`
Expected(实现前若代码未接):FAIL;接好后 PASS。

- [ ] **Step 5: 跑 targeted IT + 回归 loan 套件**

Run: `mvn -q -pl exchange-core test -Dtest=ITLoanTargetedLiquidation,LoanLiquidationEngineTest,ITLoanForceLiquidatePipeline,ITLoanConservation,ITLoanFailoverSnapshot -DfailIfNoTests=false`
Expected: PASS(targeted 触发生效;force-sell pipeline / 守恒 / failover 快照重建不回归)。

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(loan): checkPositions 并 loan 索引 targeted 触发 + updateProvider 重建 + IT"
```

---

## Task 4: 全量回归 + determinism 确认

**Files:** 无生产改动(纯验证)。

- [ ] **Step 1: exchange-core 全量**

Run: `mvn -q -pl exchange-core test`
Expected: 全绿(两段 surefire:unit + IT)。若有断言 inFlight/throttle 的**其它**测试挂了,按新模型改(不改生产迁就测试),记录到 review。

- [ ] **Step 2: server 全量**

Run: `mvn -q -pl raft-exchange-server -am test`
Expected: 全绿(接口单参改动 + override 同步)。

- [ ] **Step 3: 记录 backstop 不回归 + stale-index 只损延迟的验证点**

确认(通过已有 IT / 阅读):无价格事件、纯利息越 LTV 的 loan 仍由 `LIQUIDATION_SCAN` 捕捉(`ITLoanConservation` / backstop 路径);`updateProvider` 重建在 failover 快照测试(`ITLoanFailoverSnapshot`)后索引正确、`stateHash` 收敛。

---

## Self-Review 结果

- **Spec 覆盖**:targeted(isolated+cross 索引)= Task 2+3;LaneState 清理 + onApplied + ts = Task 1;backstop 保留 = 不动 + Task 4 确认;determinism/rebuild = Task 3 Step 2 + Task 4 Step 3。全覆盖。
- **占位符扫描**:无 TBD。原两处待核实(常量归属 `TestConstants`、`getIsolatedLoans()` 读抵押)已核实坐实于代码。
- **类型一致性**:`check(up, ts)`、`submit(ApiCommand)`、`collectAffectedLoanUsers(spec, sink)`、`rebuildIndices()` 跨 task 签名一致;`IntObjectHashMap<MutableLongSet>` 与 `symbolToUsers` 同型。
- **顺序**:Task 1(清理,腾出 onApplied)→ Task 2(索引+维护,dead 直到 Task 3)→ Task 3(集成+IT)→ Task 4(回归)。每 task 独立可测。
