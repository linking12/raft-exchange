# 期货强平:事件驱动 on-lane targeted 重构 实现计划(Plan 1 / 期货)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把期货 `LiquidationEngine` 从"每 2s off-lane 全量扫描"改成"命令 apply 内 on-lane、按 symbol→用户索引 targeted 的事件驱动强平检测",根除扫描数据竞态、提升高杠杆及时性,并把 `LiquidationContext` 从复制态抽成 leader-local。

**Architecture:** 强平检查移到 RiskEngine 命令 handler 内(单写者线程,读一致状态)。价格/资金费变更命令 apply 后,只检查受影响 symbol 的持有者(靠每分片 `symbol→uid` 索引);破产则经现有 `LiquidationCmdPublisher` 发 `FORCE_LIQUIDATION`,下游 IF/ADL 不变。`LiquidationEngine` 退成薄编排,重活拆到 `PositionSymbolIndex` / `FuturesSolvencyEvaluator` / `LiquidationFlowTracker` / `LiquidationBackstopCursor`。原 2s 扫描线程删除,换一个只发命令(`LIQUIDATION_BACKSTOP_TICK` 慢兜底 + `REPRICE_LOAN_RATES` 心跳)的轻定时器。强平流程状态 `LiquidationContext` 退出 snapshot/stateHash,改为 leader-local;正确性由 R1 `normalizeCmdPositionSize` 的 size 夹取 + 换届冷启动重扫保证。

**Tech Stack:** Java 25(Amazon Corretto),LMAX Disruptor,Eclipse Collections(primitive maps),Chronicle Bytes(序列化),JRaft/Aeron(复制,server 层),JUnit 5 + AssertJ + Mockito。构建:`mvn -pl exchange-core test`(exchange-core 模块)。

## Global Constraints

- **确定性(Raft RSM)不可破**:命令 apply 路径内只做整数运算(`Math.addExact`/`multiplyExact`);不得引入 wall-clock 到复制态(时间用 `cmd.timestamp`);检查对复制状态**只读**,产出 `FORCE_LIQUIDATION` 经 publisher 进 log 再由各节点确定性 apply。
- **stateHash 参与项变更必须全节点一致**:本计划移除 `SymbolPositionRecord.liquidationCtx` 的 stateHash 参与项——pre-launch,无历史快照,直接改,不写版本兼容。
- **leader-local 结构不得进 snapshot / stateHash**:`PositionSymbolIndex`、`LiquidationFlowTracker`、`LiquidationBackstopCursor` 均为派生/进程态;快照恢复时索引**重建**,flow/cursor 换届重置(中途仓由冷启动扫描重启)。
- **不改快照字节格式**(除 `liquidationCtx` 移除那一处);`symbol→uid` 索引是派生态,恢复时遍历重建。
- **行为回归底线全绿**:`ITConservationFuzz`、`ITLoanConservation`、`PersistenceTests`、`LiquidationEngineTest`(及同目录 liquidation 测试)、`ITLiquidationIntegration`、`LoanLiquidationEngineTest` 全部保持通过。
- **命名保持** `LiquidationEngine`(职责未变,不改名为 Scanner)。
- **不新建 `...CommandProcessor` 类**:强平触发不是两步处理器;RiskEngine 各 handler 直接委托 `LiquidationEngine` 方法。
- **loan 侧本计划不动**:`LoanLiquidationEngine` 的事件驱动改造是 Plan 2。本计划中 loan 检查暂时保留由兜底路径覆盖(见 Task 8 说明),不引入 loan 的 symbol→loan 索引。
- **测试运行纪律**:仅在计划明确要求处运行测试(TDD 步骤的"跑测试")。不额外自发跑全量。
- **提交纪律**:实现代码写完**先不 commit**,等用户 review;文档/计划本身可 commit。(见 memory: commit-after-user-review)

**关键既有签名/坐标(实现时以这些为准):**
- `LiquidationEngine`:`exchange-core/src/main/java/exchange/core2/core/processors/liquidation/LiquidationEngine.java`。当前 `extends SimpleScheduledService`(line 64);构造 `LiquidationEngine(Supplier<FundEvent> eventSupplier, int shardId, ExchangeConfiguration cfg)`(83-90);`updateProvider(SymbolSpecificationProvider, CurrencySpecificationProvider, UserProfileService, IntObjectHashMap<LastPriceCacheRecord>, LoanService)`(92-105);类级 `@Getter @Setter`(某些字段)。`checkLiquidations()`(137-174)是 off-lane 全扫入口;`checkLiquidationIsolated`(204-222)、`checkLiquidationCross`(224-263)、`forceCrossLiquidation`(265-284)、`startLiquidationFlow`(286-298)、`sendWarningEvent`(300-306)、`calculateBankruptcyPrice(SymbolPositionRecord)`(313-338)、`calculateCrossBpMarginBaseAllocation`(352-386)、`nextLiquidationState(OrderCommand, SymbolPositionRecord)`(394-428)、`onMarketDone/onIFTakeoverDone/enterAdlPhase/onADLDone`(431-468)、`tryRepublishStuckLiquidation`(179-202)、`buildForceCmd/buildIFCmd/buildADLCmd`(481-495)、`publishTracked/publishUntracked`(499-511)。字段:`inFlightLiquidationCmd:MultiReaderSet<SymbolPositionRecord>`(79)、`tickBpMarginBaseCache`(80-81)、`liquidationCmdPublisher`(76)、`loanLiquidationEngine`(77)、`stuckThresholdMs`(66)。
- `SimpleScheduledService`:同包,`protected abstract void runOneIteration()`,`start()/stop()/isRunning()`,单线程 `scheduleWithFixedDelay`。
- `RiskEngine`:`exchange-core/.../processors/RiskEngine.java`。`liquidationEngine` 字段(109);构造 `new LiquidationEngine(...)`(146);`updateProvider(...)` 调用在 `initState()`(181-182)与 `recoverStateBySnapshot()`(235-236)。R1 主 switch 在 `preProcessCommand(long seq, OrderCommand cmd)`(295-660);R2 在 `handlerRiskRelease(long seq, OrderCommand cmd)`(1361-1491)。`MARKPRICE_ADJUSTMENT` case(442-454,更新 `lastPriceCache` 的 `markPrice`,非 uid-gated);`SETTLE_FUNDINGFEES` case R1(538-554)+ R2 apply(1445-1449);`FORCE_LIQUIDATION` case(502-506)→`normalizeCmdPositionSize`(1050-1066);R2 状态机推进(1466-1470)`liquidationEngine.nextLiquidationState(cmd, takerSpr)`;`normalizeCmdPositionSize` 纯把 `cmd.size = Math.min(cmd.size, position.openVolume)`。仓位 map 插入:`userProfile.positions.put(positionRecordKey, position)`(756);仓位 map 唯一删除点:`removePositionRecord(...)`(2006-2014,内部 `userProfile.positions.removeKey(...)` line 2012);交易撮合后开/平在 `handleMatcherEventMargin`(1797-1950);交割平仓 `settlePnl`(1021-1044)。快照恢复反序列化 UserProfile 在 `recoverStateBySnapshot`(198 起,swap 在 216-240)。`uidForThisHandler(uid)`(1244-1246)。exchange-core 内**无 isLeader**——leader 门控只在 server 层 publisher override。
- `SymbolPositionRecord`:`exchange-core/.../common/SymbolPositionRecord.java`。`liquidationCtx` 字段(67,注释 64-66);`writeMarshallable` 写块(719-724);读构造(123);`stateHash()` 项(767-770,`liquidationCtx` 在 770);`initialize()` 置 null(90);`reset()` 置 null(747);`toString()`(779)。BP:`calculateBankruptcyPrice(spec)`(265)、`calculateBankruptcyPrice(spec, marginBase)`(282)、私有 `calcBankruptcyPriceFromMarginBase`(305)。
- `LiquidationContext`:`exchange-core/.../processors/liquidation/LiquidationContext.java`。字段 `state/price/size/originalOrderId/lastTransitionAt`;enum `LiquidationState{LIQUIDATING(1), WAIT_IF_EXECUTION(2), WAIT_ADL_EXECUTION(3)}`;有自身 `writeMarshallable/stateHash`(内存中仍可用)。
- `OrderCommandType`:`exchange-core/.../common/cmd/OrderCommandType.java`,`(byte code, boolean mutate)`。`REPRICE_LOAN_RATES((byte)63,true)`(73);`FORCE_LIQUIDATION((byte)20,true)`;`isLoan()` switch(111-130,REPRICE 不在其中);static 注册块(134-138)自动。byte 64 空闲。
- `ApiRepriceLoanRates`:`exchange-core/.../common/api/ApiRepriceLoanRates.java`(无 payload,`@Builder`,`extends ApiCommand`)。`ExchangeApi`:translator `REPRICE_LOAN_RATES_TRANSLATOR`(1092-1096),三个 `instanceof` 分发(140-141、233-234、324-325),map 注册(1404)。
- `LiquidationCmdPublisher`:`void publish(ApiCommand cmd, Runnable onApplied)`。server 层 `ExchangeRuntime.overrideLiquidationCmdPublisher(BooleanSupplier isLeader, ...)`(42-65,`if(!isLeader) return`);role-change 启停在 `JraftClusterContainer`(85-91)、`AeronClusterContainer`(66-72),经 `ExchangeCore.getLiquidationEngines()`(map `RiskEngine::getLiquidationEngine`)。

---

## File Structure

**新建(exchange-core `processors/liquidation/`):**
- `PositionSymbolIndex.java` — 每分片 `symbol(int) → uid 集合` 派生索引。
- `LiquidationFlowTracker.java` — `IdentityHashMap<SymbolPositionRecord, LiquidationContext>` + FORCE→IF→ADL 升级决策 + stuck 扫描。**值类型直接复用现有 `LiquidationContext`**(不新建 `LiquidationFlow`)。
- `FuturesSolvencyEvaluator.java` — 期货 isolated/cross 偿付判定 + BP/size,返回 verdict(**不 publish**);内含 `static final class LiquidationAction`(verdict 值对象)。
- `LiquidationBackstopCursor.java` — 兜底切片游标(uid 快照 + cursor)。

> **注**:**不新建** heartbeat 类。`LiquidationEngine` 保留 `extends SimpleScheduledService`,把 `runOneIteration()` 从"扫全量用户"改成"只发命令"(见修改项)。`SimpleScheduledService` 类本身不动。

**新建(exchange-core `common/api/`):**
- `ApiLiquidationBackstopTick.java` — 空 payload 命令(模板 `ApiRepriceLoanRates`)。

**修改:**
- `common/cmd/OrderCommandType.java` — 加 `LIQUIDATION_BACKSTOP_TICK((byte)64,true)`。
- `ExchangeApi.java` — 加 `LIQUIDATION_BACKSTOP_TICK_TRANSLATOR` + 3 分发 + map 注册。
- `common/SymbolPositionRecord.java` — 移除 `liquidationCtx` 字段/序列化/stateHash。
- `processors/liquidation/LiquidationEngine.java` — **保留** `extends SimpleScheduledService`,但 `runOneIteration()` 改为**只发命令**(shard 0、节流发 `BACKSTOP_TICK` + `REPRICE`),不再扫用户;薄编排;新 API `checkPositionsOnSymbol/checkUser/fullScan/backstopTick/onPositionOpened/onPositionClosed/rebuildIndex/nextLiquidationState`;持有 index/tracker/evaluator/cursor + `BooleanSupplier isLeader`。
- `processors/liquidation/LiquidationContext.java` — **复用为 leader-local 流程 holder**(不再挂 `SymbolPositionRecord`、不再被序列化)。Task 5 删除其序列化三件套(`LiquidationContext(BytesIn)` 构造、`writeMarshallable`、`stateHash`),保留字段 + `LiquidationState` enum + 4 参构造 `LiquidationContext(price,size,originalOrderId,ts)`。**默认不改名**(可选改名 `LiquidationFlow`,需 search-replace 所有 `LiquidationContext.LiquidationState` 引用)。
- `processors/RiskEngine.java` — handler 委托 + 开/平仓 index 维护 + 快照重建 + backstop handler。
- server 层:`ExchangeCore` / `Exchange Runtime` / `JraftClusterContainer` / `AeronClusterContainer` — 启停轻定时器 + 换届冷启动 `fullScan()` + 注入 `isLeader` 到引擎(Task 9)。

---

## Task 1: PositionSymbolIndex(symbol→uid 每分片派生索引)

**Files:**
- Create: `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/PositionSymbolIndex.java`
- Test: `exchange-core/src/test/java/exchange/core2/core/processors/liquidation/PositionSymbolIndexTest.java`

**Interfaces:**
- Produces:
  - `void add(int symbol, long uid)` — 持仓 0→非0 时登记。
  - `void remove(int symbol, long uid)` — 持仓非0→0 时移除;集合空则删 key。
  - `void forEachUser(int symbol, LongProcedure action)` — 遍历持有该 symbol 的 uid(无则不调用)。
  - `void clear()` — 快照重建前清空。
  - `int symbolCount()` / `boolean contains(int symbol, long uid)` — 仅测试/断言用。

- [ ] **Step 1: 写失败测试**

```java
package exchange.core2.core.processors.liquidation;

import org.eclipse.collections.api.list.primitive.MutableLongList;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PositionSymbolIndexTest {

    private MutableLongList collect(PositionSymbolIndex idx, int symbol) {
        MutableLongList out = new LongArrayList();
        idx.forEachUser(symbol, out::add);
        return out;
    }

    @Test
    void addThenForEach_returnsUsers() {
        PositionSymbolIndex idx = new PositionSymbolIndex();
        idx.add(100, 7L);
        idx.add(100, 9L);
        idx.add(200, 7L);
        assertThat(collect(idx, 100).toSortedArray()).containsExactly(7L, 9L);
        assertThat(collect(idx, 200).toArray()).containsExactly(7L);
    }

    @Test
    void unknownSymbol_noInvocation() {
        PositionSymbolIndex idx = new PositionSymbolIndex();
        assertThat(collect(idx, 999)).isEmpty();
    }

    @Test
    void remove_dropsUser_andEmptiesSymbolKey() {
        PositionSymbolIndex idx = new PositionSymbolIndex();
        idx.add(100, 7L);
        idx.add(100, 9L);
        idx.remove(100, 7L);
        assertThat(collect(idx, 100).toArray()).containsExactly(9L);
        idx.remove(100, 9L);
        assertThat(collect(idx, 100)).isEmpty();
        assertThat(idx.symbolCount()).isEqualTo(0); // 空集合的 symbol key 被清除
    }

    @Test
    void addIdempotent_noDuplicateUid() {
        PositionSymbolIndex idx = new PositionSymbolIndex();
        idx.add(100, 7L);
        idx.add(100, 7L);
        assertThat(collect(idx, 100).toArray()).containsExactly(7L);
    }

    @Test
    void removeMissing_isNoop() {
        PositionSymbolIndex idx = new PositionSymbolIndex();
        idx.remove(100, 7L); // 不抛
        idx.add(100, 7L);
        idx.remove(100, 8L); // 不影响 7
        assertThat(collect(idx, 100).toArray()).containsExactly(7L);
    }

    @Test
    void clear_emptiesAll() {
        PositionSymbolIndex idx = new PositionSymbolIndex();
        idx.add(100, 7L);
        idx.add(200, 8L);
        idx.clear();
        assertThat(idx.symbolCount()).isEqualTo(0);
        assertThat(collect(idx, 100)).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -pl exchange-core -Dtest=PositionSymbolIndexTest test`
Expected: FAIL(编译错误 `PositionSymbolIndex` 不存在)

- [ ] **Step 3: 实现**

```java
package exchange.core2.core.processors.liquidation;

import org.eclipse.collections.api.block.procedure.primitive.LongProcedure;
import org.eclipse.collections.api.set.primitive.MutableLongSet;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * 每 RiskEngine 分片一份的派生索引:symbol -> 持有该 symbol 期货仓位的 uid 集合。
 * 用于 targeted 强平检查(价格/资金费事件只查受影响 symbol 的持有者,而非全分片)。
 * 派生态:不进 snapshot / stateHash;快照恢复时由 {@link #clear()} + 遍历重建。
 * 仅在 RiskEngine 单写者线程访问,无需同步。
 */
public final class PositionSymbolIndex {

    private final IntObjectHashMap<MutableLongSet> symbolToUsers = new IntObjectHashMap<>();

    public void add(int symbol, long uid) {
        symbolToUsers.getIfAbsentPut(symbol, LongHashSet::new).add(uid);
    }

    public void remove(int symbol, long uid) {
        final MutableLongSet users = symbolToUsers.get(symbol);
        if (users == null) {
            return;
        }
        users.remove(uid);
        if (users.isEmpty()) {
            symbolToUsers.remove(symbol);
        }
    }

    public void forEachUser(int symbol, LongProcedure action) {
        final MutableLongSet users = symbolToUsers.get(symbol);
        if (users != null) {
            users.forEach(action);
        }
    }

    public void clear() {
        symbolToUsers.clear();
    }

    public int symbolCount() {
        return symbolToUsers.size();
    }

    public boolean contains(int symbol, long uid) {
        final MutableLongSet users = symbolToUsers.get(symbol);
        return users != null && users.contains(uid);
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `mvn -pl exchange-core -Dtest=PositionSymbolIndexTest test`
Expected: PASS(6 tests)

- [ ] **Step 5: 提交(仅本任务代码)**

```bash
git add exchange-core/src/main/java/exchange/core2/core/processors/liquidation/PositionSymbolIndex.java \
        exchange-core/src/test/java/exchange/core2/core/processors/liquidation/PositionSymbolIndexTest.java
git commit -m "feat(liquidation): add PositionSymbolIndex (symbol->uid derived index)"
```
> 注:若按 commit-after-user-review 纪律执行,此 commit 步骤改为暂存并等 review。执行者以控制器指示为准。

---

## Task 2: LiquidationBackstopCursor(兜底切片游标)

**Files:**
- Create: `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/LiquidationBackstopCursor.java`
- Test: `exchange-core/src/test/java/exchange/core2/core/processors/liquidation/LiquidationBackstopCursorTest.java`

**Interfaces:**
- Consumes: `LongObjectHashMap<UserProfile>`(`userProfileService.getUserProfiles()` 的返回类型)。
- Produces:
  - `LiquidationBackstopCursor(int sliceSize)`
  - `void nextSlice(org.eclipse.collections.api.map.primitive.LongObjectMap<UserProfile> users, java.util.function.LongConsumer perUid)` — 每次推进 `sliceSize` 个 uid;扫到尾归零并在下一次刷新 uid 快照。
  - `long sweepsCompleted()` — 完整走完一轮的次数(仅测试/可观测)。

**实现要点(TDD 同前:先写失败测试、跑失败、实现、跑通过、提交):**
- 内部持 `long[] snapshot`(uid 快照)+ `int cursor` + `long sweeps`。
- `nextSlice`:若 `snapshot == null || cursor >= snapshot.length` → `snapshot = users.keySet().toArray()`(Eclipse `LongObjectMap#keySet().toArray()` 返回 `long[]`),`cursor = 0`,`sweeps++`(仅当上一轮非空,避免首次误计——首次 `sweeps` 保持 0,详见测试)。若快照为空(无用户)直接返回。扫 `[cursor, min(cursor+sliceSize, len))`,对每个仍存在于 `users` 的 uid 调 `perUid`(快照期间用户可能已删,跳过 `users.get(uid)==null`);`cursor += sliceSize`。
- 测试覆盖:分片推进覆盖全部 uid、跨轮换快照、`sliceSize` 大于总数一次扫完、空 users 无异常、快照期间用户被删则跳过。

具体测试骨架:

```java
package exchange.core2.core.processors.liquidation;

import exchange.core2.core.common.UserProfile;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LiquidationBackstopCursorTest {

    private LongObjectHashMap<UserProfile> usersOf(long... uids) {
        LongObjectHashMap<UserProfile> m = new LongObjectHashMap<>();
        for (long u : uids) m.put(u, mock(UserProfile.class));
        return m;
    }

    @Test
    void slicesCoverAllUidsAcrossCalls() {
        LongObjectHashMap<UserProfile> users = usersOf(1, 2, 3, 4, 5);
        LiquidationBackstopCursor cur = new LiquidationBackstopCursor(2);
        List<Long> seen = new ArrayList<>();
        cur.nextSlice(users, seen::add); // 2
        cur.nextSlice(users, seen::add); // 2
        cur.nextSlice(users, seen::add); // 1 (到尾)
        assertThat(seen).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void emptyUsers_noInvocationNoThrow() {
        LiquidationBackstopCursor cur = new LiquidationBackstopCursor(4);
        List<Long> seen = new ArrayList<>();
        cur.nextSlice(new LongObjectHashMap<>(), seen::add);
        assertThat(seen).isEmpty();
    }

    @Test
    void deletedDuringSweep_skipped() {
        LongObjectHashMap<UserProfile> users = usersOf(1, 2, 3);
        LiquidationBackstopCursor cur = new LiquidationBackstopCursor(3);
        cur.nextSlice(users, uid -> {}); // 建立快照 [1,2,3]
        users.remove(2L);
        List<Long> seen = new ArrayList<>();
        cur.nextSlice(users, seen::add); // 新一轮快照,2 已不在
        assertThat(seen).containsExactlyInAnyOrder(1L, 3L);
    }
}
```

- [ ] **Step 1-5**:写失败测试 → 跑失败 → 实现 → 跑通过(`-Dtest=LiquidationBackstopCursorTest`)→ 提交。

---

## Task 3: LiquidationFlowTracker(leader-local 流程状态机,复用 LiquidationContext)

**Files:**
- Create: `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/LiquidationFlowTracker.java`
- Test: `exchange-core/src/test/java/exchange/core2/core/processors/liquidation/LiquidationFlowTrackerTest.java`

**背景:** 把原来挂在 `SymbolPositionRecord.liquidationCtx` 上的强平流程状态搬到引擎持有的 leader-local 结构。**值类型直接复用现有 `LiquidationContext`**(字段/enum/4 参构造齐备,无需新建 `LiquidationFlow`)。Tracker 按 `SymbolPositionRecord` 对象身份键入(`IdentityHashMap`),流程完结或仓位删除时移除条目。`LiquidationContext` 的序列化在 Task 5 删除(此刻它仍带序列化方法,tracker 不用而已,无碍)。

**前置确认(实现者第一步)**:`LiquidationContext` 存在 4 参构造 `LiquidationContext(long price, long size, long originalOrderId, long ts)` 且初始 `state = LIQUIDATING`(现 `nextLiquidationState` 就用它 `new`)。若签名/初值不符,以实际为准调整 `begin(...)`。

**Interfaces(Produces,后续 Task 6 消费):**
- `LiquidationFlowTracker`:
  - `LiquidationContext get(SymbolPositionRecord pos)` — 无则 null。
  - `boolean hasActiveFlow(SymbolPositionRecord pos)` — `get(pos) != null`。
  - `LiquidationContext begin(SymbolPositionRecord pos, long price, long size, long originalOrderId, long ts)` — `new LiquidationContext(price,size,originalOrderId,ts)` 并登记。
  - `void touch(LiquidationContext flow, long ts)` — 刷新 `lastTransitionAt`。
  - `void remove(SymbolPositionRecord pos)` — 流程完结/仓位删除时清除。
  - `void forEachStuck(long now, long thresholdMs, java.util.function.BiConsumer<SymbolPositionRecord, LiquidationContext> action)` — 遍历 `now - lastTransitionAt > thresholdMs` 的条目(用于兜底 republish);遍历时快照 entrySet 避免并发修改。
  - `void clear()` / `int size()`。

- [ ] **Step 1: 写失败测试**

```java
package exchange.core2.core.processors.liquidation;

import exchange.core2.core.common.SymbolPositionRecord;
import exchange.core2.core.processors.liquidation.LiquidationContext.LiquidationState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class LiquidationFlowTrackerTest {

    // identity key:pos 只作 map 键,不读其内容 → 用 mock 最省事(SymbolPositionRecord 无公开无参构造时尤其如此)
    private SymbolPositionRecord pos() {
        return mock(SymbolPositionRecord.class);
    }

    @Test
    void beginThenGet_tracksFlow() {
        LiquidationFlowTracker t = new LiquidationFlowTracker();
        SymbolPositionRecord p = pos();
        assertThat(t.hasActiveFlow(p)).isFalse();
        LiquidationContext f = t.begin(p, 100L, 5L, 42L, 1000L);
        assertThat(f.state).isEqualTo(LiquidationState.LIQUIDATING);
        assertThat(t.get(p)).isSameAs(f);
        assertThat(t.hasActiveFlow(p)).isTrue();
        assertThat(t.size()).isEqualTo(1);
    }

    @Test
    void remove_clearsFlow() {
        LiquidationFlowTracker t = new LiquidationFlowTracker();
        SymbolPositionRecord p = pos();
        t.begin(p, 100L, 5L, 42L, 1000L);
        t.remove(p);
        assertThat(t.get(p)).isNull();
        assertThat(t.size()).isEqualTo(0);
    }

    @Test
    void forEachStuck_onlyBeyondThreshold() {
        LiquidationFlowTracker t = new LiquidationFlowTracker();
        SymbolPositionRecord fresh = pos();
        SymbolPositionRecord stale = pos();
        t.begin(fresh, 1, 1, 1, 10_000L);
        t.begin(stale, 1, 1, 1, 1_000L);
        List<SymbolPositionRecord> hit = new ArrayList<>();
        t.forEachStuck(12_000L, 5_000L, (p, f) -> hit.add(p)); // fresh=2000<=5000 跳过, stale=11000>5000 命中
        assertThat(hit).containsExactly(stale);
    }

    @Test
    void touch_refreshesTimestamp_movesOutOfStuck() {
        LiquidationFlowTracker t = new LiquidationFlowTracker();
        SymbolPositionRecord p = pos();
        LiquidationContext f = t.begin(p, 1, 1, 1, 1_000L);
        t.touch(f, 12_000L);
        List<SymbolPositionRecord> hit = new ArrayList<>();
        t.forEachStuck(13_000L, 5_000L, (pp, ff) -> hit.add(pp)); // 1000<=5000 跳过
        assertThat(hit).isEmpty();
    }
}
```

- [ ] **Step 2:** 跑失败(`-Dtest=LiquidationFlowTrackerTest`)。

- [ ] **Step 3: 实现**

`LiquidationFlowTracker.java`:
```java
package exchange.core2.core.processors.liquidation;

import exchange.core2.core.common.SymbolPositionRecord;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * leader-local 强平流程追踪器:SymbolPositionRecord 身份 -> LiquidationContext。
 * 替代原挂在 position 上、进 raft 复制态的 liquidationCtx。不进 snapshot/stateHash。
 * 正确性不依赖它(FORCE size 由 R1 normalizeCmdPositionSize 夹到当前仓位);它只是让
 * FORCE→IF→ADL 升级高效驱动 + 让 targeted 检查跳过流程中的仓。换届/快照恢复后为空,
 * 中途残余由冷启动全扫当普通破产仓重发 FORCE 重启。仅 RiskEngine 单写者线程访问。
 */
public final class LiquidationFlowTracker {

    private final Map<SymbolPositionRecord, LiquidationContext> flows = new IdentityHashMap<>();

    public LiquidationContext get(SymbolPositionRecord pos) {
        return flows.get(pos);
    }

    public boolean hasActiveFlow(SymbolPositionRecord pos) {
        return flows.containsKey(pos);
    }

    public LiquidationContext begin(SymbolPositionRecord pos, long price, long size, long originalOrderId, long ts) {
        final LiquidationContext flow = new LiquidationContext(price, size, originalOrderId, ts);
        flows.put(pos, flow);
        return flow;
    }

    public void touch(LiquidationContext flow, long ts) {
        flow.lastTransitionAt = ts;
    }

    public void remove(SymbolPositionRecord pos) {
        flows.remove(pos);
    }

    public void forEachStuck(long now, long thresholdMs, BiConsumer<SymbolPositionRecord, LiquidationContext> action) {
        if (flows.isEmpty()) {
            return;
        }
        // 快照 entrySet:action 可能触发 begin/remove(republish 路径),避免并发修改。
        final List<Map.Entry<SymbolPositionRecord, LiquidationContext>> snapshot = new ArrayList<>(flows.entrySet());
        for (Map.Entry<SymbolPositionRecord, LiquidationContext> e : snapshot) {
            if (now - e.getValue().lastTransitionAt > thresholdMs) {
                action.accept(e.getKey(), e.getValue());
            }
        }
    }

    public void clear() {
        flows.clear();
    }

    public int size() {
        return flows.size();
    }
}
```

- [ ] **Step 4:** 跑通过(`-Dtest=LiquidationFlowTrackerTest`)。
- [ ] **Step 5:** 提交 `LiquidationFlowTracker.java` + 测试。

---

## Task 4: FuturesSolvencyEvaluator(偿付判定 + BP,纯 verdict 不 publish)

**Files:**
- Create: `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/FuturesSolvencyEvaluator.java`(含 `static final class LiquidationAction` 内部类)
- Test: `exchange-core/src/test/java/exchange/core2/core/processors/liquidation/FuturesSolvencyEvaluatorTest.java`

**背景:** 把 `LiquidationEngine` 现有 `checkLiquidationIsolated`(204-222)、`checkLiquidationCross`(224-263)、`forceCrossLiquidation`(265-284)、`calculateBankruptcyPrice`(313-338)、`calculateCrossBpMarginBaseAllocation`(352-386)的**判定与计算**逻辑**逐字移入**本类,改成**返回 verdict 而不 publish**。原方法里"发 FORCE"(`startLiquidationFlow`)与"发预警"(`sendWarningEvent`)留在 `LiquidationEngine`(Task 6 消费本类 verdict 后再 publish)。

**关键:行为等价**——数学、阈值(`warningThreshold = maintenanceMargin*6/5`、`equity < maintenanceMargin` 触发)、cross 按 currency 聚合、risk 排序、`tickBpMarginBaseCache` 复用逻辑必须**原样搬运**,只把"副作用(publish/warn)"替换为"往返回值里塞 action / warning 标记"。`tickBpMarginBaseCache` 随本类持有(每次 evaluate 一个用户前由调用方或本类在批次开始 clear——见下)。

**Interfaces:**
- `FuturesSolvencyEvaluator.LiquidationAction`:**`static final` 内部类**,字段 `final SymbolPositionRecord position; final long bankruptcyPrice; final long size; final boolean warning;`(warning=true 表示只需预警不需强平,此时 bp/size 可为 0)。静态工厂 `LiquidationAction.liquidate(pos, bp, size)` / `LiquidationAction.warn(pos)`。消费方 `LiquidationEngine` 用 `FuturesSolvencyEvaluator.LiquidationAction` 引用。
- `FuturesSolvencyEvaluator`:
  - 构造 `FuturesSolvencyEvaluator()`;`void updateProvider(SymbolSpecificationProvider, CurrencySpecificationProvider, UserProfileService, IntObjectHashMap<LastPriceCacheRecord>)`(与引擎 updateProvider 同源注入)。
  - `void beginBatch()` — 清 `tickBpMarginBaseCache`(等价原 `checkLiquidations()` 开头 `tickBpMarginBaseCache.clear()`)。targeted 单用户检查也在每次 evaluate 前调用一次即可(cache 仅同一次评估内跨仓复用)。
  - `void evaluateUser(UserProfile up, List<LiquidationAction> out)` — 对单个用户:遍历其期货仓位,ISOLATED 逐仓判定、CROSS 按 currency 聚合判定,把需要强平/预警的仓位以 `LiquidationAction` 加入 `out`。**内部不 publish、不改任何复制态、不碰 flow tracker**(是否跳过流程中的仓由调用方按 tracker 决定)。
  - 私有:`evaluateIsolated(...)`、`evaluateCross(...)`、`calculateBankruptcyPrice(SymbolPositionRecord)`、`calculateCrossBpMarginBaseAllocation(...)` —— 从 `LiquidationEngine` 逐字迁移。

- [ ] **Step 1: 写失败测试**

用真实 `UserProfile` + `SymbolPositionRecord` + `CoreSymbolSpecification` 构造一个逐仓期货用户,mark price 设到明确破产/健康两档,断言:
- 健康(equity >= maintenance)→ `out` 为空;
- 破产(equity < maintenance)→ `out` 含一个 `warning=false` 的 action,其 `bankruptcyPrice` 等于对同一 position 直接调 `position.calculateBankruptcyPrice(spec)` 的值,`size > 0`;
- 预警带(maintenance <= equity < maintenance*6/5)→ `out` 含 `warning=true` action。

（cross 用例:两仓同 currency 聚合,账户 equity < totalMaintenance → 至少一个强平 action。）测试用现有测试里构造期货用户/仓位的 helper 模式(参考 `LiquidationEngineTest`、`ExchangeTestContainer` 里构造 futures position 的方式);实现者对拍 `position.calculateBankruptcyPrice(...)`。

- [ ] **Step 2:** 跑失败(`-Dtest=FuturesSolvencyEvaluatorTest`)。

- [ ] **Step 3: 实现**——新建 `LiquidationAction`;新建 `FuturesSolvencyEvaluator`,把上列五个方法从 `LiquidationEngine` **复制**进来(先复制不删原件,原件在 Task 6 删),把 `startLiquidationFlow(up,pos,price,size)` 调用替换为 `out.add(LiquidationAction.liquidate(pos, price, size))`,把 `sendWarningEvent(up,pos,...)` 替换为 `out.add(LiquidationAction.warn(pos))`。`tickBpMarginBaseCache` 字段随本类。providers/price cache 经 `updateProvider` 注入。

- [ ] **Step 4:** 跑通过(`-Dtest=FuturesSolvencyEvaluatorTest`)。
- [ ] **Step 5:** 提交。

> **⚠️ 行为等价是本任务验收核心**:Task 6 会让引擎改用本 evaluator,届时既有 `LiquidationEngineTest` / `ITLiquidationIntegration` 必须仍绿。本任务只新增、不改引擎,故此刻这些回归测试不受影响;真正对拍在 Task 6。

---

## Task 5: 从 SymbolPositionRecord 移除 liquidationCtx(depersist)

**Files:**
- Modify: `exchange-core/src/main/java/exchange/core2/core/common/SymbolPositionRecord.java`(67, 90, 123, 719-724, 747, 767-770, 779)
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/LiquidationContext.java`(删序列化三件套:`LiquidationContext(BytesIn)` 构造、`writeMarshallable`、`stateHash`;及不再需要的 `WriteBytesMarshallable`/`StateHash` 接口实现声明与 Bytes/Hashing import)
- Test: `exchange-core/src/test/java/exchange/core2/core/common/SymbolPositionRecordTest.java`(若不存在则新建;否则加用例)

**背景:** `liquidationCtx` 当前进 snapshot + stateHash,且 64-66 有注释声称"必须持久化否则发散"。本计划按 spec §8 反转该决定:流程状态改为 leader-local(Task 3 的 tracker 复用 `LiquidationContext` 当值),正确性由 size 夹取 + 冷启动重扫保证。此处**删字段与其序列化/哈希参与**,并把 `LiquidationContext` 自身的序列化删掉(它不再进任何字节流)。

**Interfaces:**
- Produces:`SymbolPositionRecord` 不再有 `liquidationCtx` 字段;`writeMarshallable`/读构造/`stateHash` 少一项。`LiquidationContext` 变纯内存 holder(保留字段 + `LiquidationState` enum + 4 参构造;删序列化三件套)。

- [ ] **Step 1: 写失败测试**——序列化 round-trip 且 stateHash 稳定,证明字段已不参与:

```java
// SymbolPositionRecordTest.java(新增用例)
@Test
void serializeRoundTrip_stateHashStable_withoutLiquidationCtxField() {
    // 构造一个非空期货持仓(参考现有测试构造方式)
    SymbolPositionRecord original = buildFuturesPosition(/* uid, symbol, currency, ... */);
    net.openhft.chronicle.bytes.Bytes<java.nio.ByteBuffer> buf = net.openhft.chronicle.bytes.Bytes.elasticByteBuffer();
    original.writeMarshallable(buf);
    SymbolPositionRecord restored = new SymbolPositionRecord(original.uid, buf);
    assertThat(restored.stateHash()).isEqualTo(original.stateHash());
    // 编译期保证:下面这行若 liquidationCtx 仍存在则编译通过、删除后编译失败——用注释固化意图,不写进断言
    // original.liquidationCtx   <-- 该字段已移除
}
```
> 若无 `buildFuturesPosition` helper,实现者复用 `SymbolPositionRecordTest`/`LiquidationEngineTest` 既有构造模式或直接 `initialize(...)` + 手工设字段。

- [ ] **Step 2:** 跑现有 `SymbolPositionRecordTest`(若存在)确认基线;加的新用例此刻应能编译通过(字段还在)——本任务是删除,故"失败"体现在 Step 3 删字段后编译处需同步清理。**改为:先 Step 3 删字段与所有引用,再跑全套确认无编译错误 + 相关测试绿。**

- [ ] **Step 3: 实现删除**(逐处):
  - line 67:删 `public LiquidationContext liquidationCtx;` 及 64-66 注释。
  - line 90(`initialize()`):删 `this.liquidationCtx = null;`。
  - line 123(读构造):删 `this.liquidationCtx = bytes.readByte() == 1 ? new LiquidationContext(bytes) : null;`。**注意:写侧同步删,保证字节格式前后自洽(见下)。**
  - lines 719-724(`writeMarshallable`):删整个 `if (liquidationCtx != null) {...} else {...}` 块(保留其上 `bytes.writeLong(extraMargin)` 不动)。
  - line 747(`reset()`):删 `this.liquidationCtx = null;`。
  - lines 767-770(`stateHash()`):删末项 `liquidationCtx == null ? 0 : liquidationCtx.stateHash()`,让 `extraMargin` 成为最后一个 hash 项(注意去掉多余逗号,删 766 注释)。
  - line 779(`toString()`):删 `+ " Lctx=" + liquidationCtx`。
  - 删 `SymbolPositionRecord` 里 `LiquidationContext` 的 import(若不再被引用)。
  - **一致性:读构造(123)与写(719-724)必须一起删**——两侧都不再读写那一个 flag byte + 可选 ctx,序列化对齐。
  - **`LiquidationContext.java` 删序列化三件套**:`LiquidationContext(BytesIn)` 构造、`writeMarshallable(BytesOut)`、`stateHash()`,以及 `implements WriteBytesMarshallable`(或等价)声明、`StateHash` 接口、`BytesIn/BytesOut/HashingUtils/Objects` 等只为序列化引入的 import。保留:字段、`LiquidationState` enum(含 `code`/`of`)、4 参构造。删完确认无其它引用其序列化方法(全仓 grep `writeMarshallable`/`stateHash` 对 `LiquidationContext` 的调用应为 0)。

- [ ] **Step 4: 跑测试**

Run:
```bash
mvn -pl exchange-core -Dtest=SymbolPositionRecordTest,PersistenceTests test
```
Expected: PASS。PersistenceTests 的快照 round-trip + stateHash 一致必须绿(证明全局序列化仍自洽)。

- [ ] **Step 5:** 提交。

> **⚠️ 跨任务依赖**:此任务后 `LiquidationEngine`/`RiskEngine` 里所有 `pos.liquidationCtx` 引用会编译失败——它们在 Task 6/8 一并改到 tracker。**因此 Task 5 与 Task 6 必须连续执行、同一批验收**(单独 Task 5 后 exchange-core 编译不过是预期的中间态)。执行控制器:Task 5 完成后**不单独跑全量编译门**,直接进 Task 6,合并验收。

---

## Task 6: LiquidationEngine 重构为薄 on-lane 编排

**Files:**
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/LiquidationEngine.java`(全类)
- Test: `exchange-core/src/test/java/exchange/core2/core/processors/liquidation/LiquidationEngineTest.java`(既有,须仍绿;补 targeted/backstop 新用例)

**Interfaces(Produces,Task 8 消费):**
- **保留** `extends SimpleScheduledService`、构造签名不变(`Supplier<FundEvent>, int shardId, ExchangeConfiguration`)、`super(...)` 定时器不动、`start()`/`stop()` 与 server 换届启停接线**原样保留**。`runOneIteration()` **保留但改写**为"只发命令"(见下),不再扫用户。竞态根除靠"tick 不碰用户态"(见 Task 9 + 决策记录),不靠删定时器。
- `updateProvider(...)`:同签名,内部把 providers/userService/priceCache/loanService 转注入到内部 `FuturesSolvencyEvaluator`;并 `new PositionSymbolIndex()`、`new LiquidationFlowTracker()`、`new LiquidationBackstopCursor(sliceSize)`(若尚未建)。
- `void setIsLeader(java.util.function.BooleanSupplier isLeader)`;字段默认 `() -> true`。
- 新对外方法(全部 leader-gated:`if (!isLeader.getAsBoolean()) return;` 开头,除维护类):
  - `void checkPositionsOnSymbol(int symbol, OrderCommand cmd)` — targeted:`positionSymbolIndex.forEachUser(symbol, uid -> checkUserInternal(userProfileService.getUserProfile(uid), cmd))`。
  - `void checkUser(long uid, OrderCommand cmd)` — 单用户(资金费/兜底切片用)。
  - `void fullScan()` — **无参**,冷启动一次性全扫(server 换届时直接调,无 cmd 可用):`userProfileService.getUserProfiles().forEachValue(up -> checkUserInternal(up, lastCmdTimestamp))`。timestamp 用引擎持有的 `private long lastCmdTimestamp`(在 `checkPositionsOnSymbol/checkUser/backstopTick` 入口 `this.lastCmdTimestamp = cmd.timestamp` 刷新)。等价旧 `checkLiquidations` 但用 evaluator。**注意** `checkUserInternal` 因此改为收 `long ts` 而非 `OrderCommand cmd`(见下骨架同步改 `checkUserInternal(UserProfile up, long ts)`,`startLiquidationFlow(...,ts)`)。
  - `void backstopTick(OrderCommand cmd)` — (a) `backstopCursor.nextSlice(userProfileService.getUserProfiles(), uid -> checkUserInternal(getUserProfile(uid), cmd))`;(b) `flowTracker.forEachStuck(cmd.timestamp, stuckThresholdMs, (pos, flow) -> republishStuck(pos, flow, cmd))`。
  - `void onPositionOpened(int symbol, long uid)` → `positionSymbolIndex.add(symbol, uid)`(**非** leader-gated:索引在所有节点确定性维护)。
  - `void onPositionClosed(int symbol, long uid)` → `positionSymbolIndex.remove(symbol, uid)` **且** `flowTracker.remove(pos)`(需要 pos——见 Task 8 调用点传参;或提供 `onPositionClosed(SymbolPositionRecord pos)` 重载,内部取 `pos.symbol/uid` remove 索引 + remove flow)。**采用重载** `onPositionClosed(SymbolPositionRecord pos)`。
  - `void rebuildIndex()` — 快照恢复后:`positionSymbolIndex.clear()`;遍历 `userProfileService.getUserProfiles()` 各 `up.positions` 非空期货仓 `add(pos.symbol, up.uid)`;`flowTracker.clear()`;`backstopCursor` 重置。
  - `nextLiquidationState(OrderCommand cmd, SymbolPositionRecord pos)` — 保留签名,但内部把 `pos.liquidationCtx` 全改为 `flowTracker`:`get/begin/touch/remove`。状态机语义**逐字保持**(见下)。
- `runOneIteration()` **改写**(不删):body 从 `checkLiquidations()` 换成 `emitHeartbeatCommands()` —— 仅 `shardId == 0` 时,按 `BACKSTOP_INTERVAL_MS`(默认 30_000,sysprop `raftexchange.liquidation.backstopIntervalMs`)节流 `publish(ApiLiquidationBackstopTick.builder().build(), null)`、按 `REPRICE_INTERVAL_MS`(1h)节流 `publish(ApiRepriceLoanRates.builder().build(), null)`。节流用 leader-local 字段 `lastBackstopMs`/`lastRepriceMs` + `System.currentTimeMillis()`(纯调度,不进复制态)。**不碰任何用户态**——这是竞态根除的关键。基定时间隔(`super(...)`,原 2s sysprop)保留,细粒度靠内部节流。
- 删除:`checkLiquidations()`、`triggerOnce()`、`tryRepublishStuckLiquidation()`(逻辑并入 `backstopTick` 的 `republishStuck`)、`inFlightLiquidationCmd` 字段及其 `publishTracked` 里的 add/remove(见下)、`checkLiquidationIsolated/Cross`/`forceCrossLiquidation`/`calculateBankruptcyPrice`/`calculateCrossBpMarginBaseAllocation`(已迁 evaluator)。**保留** `start()` override(定时器仍在)。
- **loan 保留旧路**:`fullScan`/`backstopTick`/`checkUserInternal` 中仍调用 `loanLiquidationEngine.check(up)`(loan 事件化是 Plan 2)。**但** reprice 心跳从 `LoanLiquidationEngine.check` 里移除,改由本类 `runOneIteration()` 的 `emitHeartbeatCommands()` 发——见 Task 9;本任务先保留 loan.check 原状,Task 9 再摘 reprice。

**`checkUserInternal(UserProfile up, OrderCommand cmd)` 骨架:**
```java
// 入口方法(checkPositionsOnSymbol/checkUser/backstopTick)统一:this.lastCmdTimestamp = cmd.timestamp; 再委托本方法。
// fullScan() 无参直接传 lastCmdTimestamp。
private void checkUserInternal(UserProfile up, long ts) {
    if (up == null) return;
    evaluator.beginBatch();
    actionsScratch.clear();               // reusable List<LiquidationAction>
    evaluator.evaluateUser(up, actionsScratch);
    for (int i = 0; i < actionsScratch.size(); i++) {
        LiquidationAction a = actionsScratch.get(i);
        if (flowTracker.hasActiveFlow(a.position)) continue;   // 跳过流程中的仓(替代 inFlight/ctx!=null 双门)
        if (a.warning) {
            sendWarningEvent(up, a.position);                  // 预警,untracked publish(保留原 sendWarningEvent)
        } else {
            startLiquidationFlow(up, a.position, a.bankruptcyPrice, a.size, ts);
        }
    }
    loanLiquidationEngine.check(up);       // loan 旧路,Plan 2 再改
}
```
（`checkPositionsOnSymbol(int symbol, OrderCommand cmd)` 内:`lastCmdTimestamp = cmd.timestamp; positionSymbolIndex.forEachUser(symbol, uid -> checkUserInternal(userProfileService.getUserProfile(uid), lastCmdTimestamp));`）

**`startLiquidationFlow` 改写(替代 79 行 inFlight 双门 + 286-298):**
```java
private void startLiquidationFlow(UserProfile up, SymbolPositionRecord pos, long price, long size, long ts) {
    if (flowTracker.hasActiveFlow(pos)) return;
    long orderId = /* 原生成 orderId 逻辑 */;
    flowTracker.begin(pos, price, size, orderId, ts);
    liquidationCmdPublisher.publish(buildForceCmd(pos, price, size, orderId), null); // 不再 inFlight-track
    publishUntracked(/* 原 liquidation alert event */);
}
```

**`nextLiquidationState` 改写(逐字保状态机,存储换 tracker):**
- `LiquidationFlow flow = flowTracker.get(pos);`
- `flow == null` 且 `cmd.command == FORCE_LIQUIDATION` → `flow = flowTracker.begin(pos, cmd.price, cmd.size, cmd.orderId, cmd.timestamp)`;非 FORCE → warn+return(与原一致)。
- `flow != null` → `flowTracker.touch(flow, cmd.timestamp)`;按 `flow.state` 校验 cmd 匹配(FORCE↔LIQUIDATING、IF_TAKEOVER↔WAIT_IF_EXECUTION、AUTO_DELEVERAGING↔WAIT_ADL_EXECUTION),不匹配=duplicate→warn+return。
- dispatch:`onMarketDone`(非 REJECT→`flowTracker.remove(pos)`;REJECT→设 `flow.size`,IF 开→`flow.state=WAIT_IF_EXECUTION`+publish IF,否则 `enterAdlPhase`)、`onIFTakeoverDone`(非 REJECT→remove;REJECT→enterAdl)、`enterAdlPhase`(`flow.state=WAIT_ADL_EXECUTION`+publish ADL)、`onADLDone`(`flowTracker.remove(pos)`)。
- 所有 `pos.liquidationCtx = X` → `flowTracker.remove(pos)` 或 `flow.state = X`;所有 `new LiquidationContext(...)` → `flowTracker.begin(...)`。

**`republishStuck(SymbolPositionRecord pos, LiquidationFlow flow, OrderCommand cmd)`(并入原 `tryRepublishStuckLiquidation` 179-202 的 republish 分支):** 按 `flow.state` 重发对应阶段命令(WAIT_IF_EXECUTION→buildIFCmd、WAIT_ADL_EXECUTION→buildADLCmd、LIQUIDATING→buildForceCmd),`publish(cmd, null)`。不再有 inFlight 判断(已删)。

- [ ] **Step 1: 先让既有回归编译+跑(基线对拍)** —— 本任务改完后必须:
```bash
mvn -pl exchange-core -Dtest=LiquidationEngineTest,ITLiquidationIntegration test
```
这些**既有**测试是行为等价的守门:若 evaluator 迁移或 ctx→tracker 改写引入偏差,这里会红。**先把它们当作"失败测试"驱动**——重构未完成时它们编译不过/红,完成后转绿。

- [ ] **Step 2: 写新增 targeted / backstop 单元测试**(新用例加入 `LiquidationEngineTest` 或新建 `LiquidationEngineEventDrivenTest`):
  - `checkPositionsOnSymbol` 只检查持有该 symbol 的用户(mock/spy `PositionSymbolIndex`,或构造两用户仅一个持有 S,断言只有持有者被评估→破产者 publish 一条 FORCE)。
  - `checkUserInternal` 对有 active flow 的仓跳过(不重复 publish)。
  - `backstopTick` 触发切片扫描 + stuck republish(构造一个 `flowTracker` 里 `lastTransitionAt` 超 `stuckThresholdMs` 的 flow,断言 republish 对应阶段命令)。
  - `nextLiquidationState` 全状态机路径(FORCE→REJECT→WAIT_IF→REJECT→WAIT_ADL→done)用 tracker 验证 stage 迁移与 publish 序列(用 mock publisher 捕获命令类型)。
  - leader-gate:`setIsLeader(() -> false)` 时 `checkPositionsOnSymbol` 不 publish。

- [ ] **Step 3: 实现重构**(按上述 Interfaces 全量改写;删除已迁移方法;`pos.liquidationCtx`→tracker)。

- [ ] **Step 4: 跑测试**
```bash
mvn -pl exchange-core -Dtest=LiquidationEngineTest,LiquidationEngineEventDrivenTest,ITLiquidationIntegration,FuturesSolvencyEvaluatorTest test
```
Expected: PASS(既有回归 + 新用例全绿)。

- [ ] **Step 5:** 提交(Task 5 + Task 6 合并提交一条,因中间态编译依赖)。

---

## Task 7: 新增 LIQUIDATION_BACKSTOP_TICK 命令

**Files:**
- Modify: `exchange-core/src/main/java/exchange/core2/core/common/cmd/OrderCommandType.java`(line 73 后加一行)
- Create: `exchange-core/src/main/java/exchange/core2/core/common/api/ApiLiquidationBackstopTick.java`
- Modify: `exchange-core/src/main/java/exchange/core2/core/ExchangeApi.java`(translator + 3 分发 + map 注册)
- Test: `exchange-core/src/test/java/exchange/core2/core/ApiLiquidationBackstopTickTest.java`

**Interfaces:**
- `OrderCommandType.LIQUIDATION_BACKSTOP_TICK((byte) 64, true)`。**不加入 `isLoan()`**(它同时覆盖期货+loan 兜底,走主 switch,不是 loan 专属)。
- `ApiLiquidationBackstopTick extends ApiCommand`,无 payload,`@Builder @EqualsAndHashCode(callSuper=false)`,`toString()="[LIQUIDATION_BACKSTOP_TICK]"`。
- `ExchangeApi`:`LIQUIDATION_BACKSTOP_TICK_TRANSLATOR`(设 `cmd.command=LIQUIDATION_BACKSTOP_TICK; cmd.timestamp=api.timestamp; cmd.resultCode=NEW;`);在 140/233/324 三处加 `else if (cmd instanceof ApiLiquidationBackstopTick)` 分支;1404 附近 `m.put(ApiLiquidationBackstopTick.class, LIQUIDATION_BACKSTOP_TICK_TRANSLATOR)`。

- [ ] **Step 1: 写失败测试** —— 提交 `ApiLiquidationBackstopTick` 命令后返回码为成功(用 `ExchangeTestContainer` 或直接 API 提交,断言 `submitCommandAsync(...).get()` 的 resultCode 为 `SUCCESS`;此时 RiskEngine 尚无 handler,会走 default——见 Step 3 需同时在 RiskEngine 加最小 handler 使其返回 SUCCESS)。

> 为让本任务独立可测,Step 3 在 RiskEngine 主 switch 加 `case LIQUIDATION_BACKSTOP_TICK:` 最小实现:`if (shardId == 0) cmd.resultCode = CommandResultCode.SUCCESS; return false;`(真正委托 `backstopTick` 在 Task 8 接上)。

- [ ] **Step 2:** 跑失败(`-Dtest=ApiLiquidationBackstopTickTest`)。
- [ ] **Step 3: 实现** enum 值 + Api 类 + 3 分发 + map 注册 + RiskEngine 最小 case。
- [ ] **Step 4:** 跑通过。
- [ ] **Step 5:** 提交。

---

## Task 8: RiskEngine 接线(触发委托 + 索引维护 + 快照重建 + 兜底)

**Files:**
- Modify: `exchange-core/src/main/java/exchange/core2/core/processors/RiskEngine.java`(442-454, 538-554, 502-506/1466-1470, 756, 1874/1941/2006-2014, 1021-1044, 235-236, 574-580 附近, LIQUIDATION_BACKSTOP_TICK case)
- Test: `exchange-core/src/test/java/exchange/core2/core/processors/RiskEngineLiquidationWiringTest.java` + 扩 `ITLiquidationIntegration`

**Interfaces(Consumes,Task 6 提供):** `liquidationEngine.checkPositionsOnSymbol/checkUser/fullScan/backstopTick/onPositionOpened/onPositionClosed/rebuildIndex/nextLiquidationState`。

**接线点(全部保持既有 R1/R2 结构,只加委托行):**

- [ ] **Step 1: 写测试**(`RiskEngineLiquidationWiringTest`,white-box,可用 spy 引擎或行为断言):
  - **价格触发**:`MARKPRICE_ADJUSTMENT` apply 后,对持有该 symbol 且破产的用户产出一条 `FORCE_LIQUIDATION`(用 mock publisher 捕获);对不持有该 symbol 的用户不检查。
  - **资金费触发**:`SETTLE_FUNDINGFEES` R2 apply 后,对该 perp 持有者做偿付检查(至少断言 `checkPositionsOnSymbol(cmd.symbol)` 被触达——spy)。
  - **索引维护**:开仓(placeOrder 成交建新仓)→ `positionSymbolIndex.contains(symbol, uid)` 为真;平仓到 0(removePositionRecord)→ 为假;且该仓的 flow 被清。
  - **快照重建**:`recoverStateBySnapshot` 后 `positionSymbolIndex` 与逐仓遍历一致(对拍:遍历所有 UserProfile 非空期货仓,集合等于索引内容)。
  - **兜底 tick**:提交 `ApiLiquidationBackstopTick` → `liquidationEngine.backstopTick(cmd)` 被调用(spy),且 stuck flow 被 republish。
  - **size 夹取幂等**:对同一仓重复投递 `FORCE_LIQUIDATION`(size 大于当前 openVolume)→ `normalizeCmdPositionSize` 夹到 openVolume,重复不超平(可在 `ITLiquidationIntegration` 加"换届丢 ctx 后重发 FORCE 收敛"用例——见 §测试)。

- [ ] **Step 2:** 跑失败。

- [ ] **Step 3: 实现接线**:
  1. **MARKPRICE_ADJUSTMENT**(442-454):在 `priceRecord.markPrice = cmd.price;` 之后、`return false;` 之前加:
     ```java
     liquidationEngine.checkPositionsOnSymbol(cmd.symbol, cmd);
     ```
     (该 case 非 uid-gated,每分片都跑;各分片索引只含本分片 uid → 天然分片摊销。)
  2. **SETTLE_FUNDINGFEES**:资金费在 R2 `handlerRiskRelease`(1445-1449)`fundingFeeProcessor.applyEvent(...)` **之后**加:
     ```java
     liquidationEngine.checkPositionsOnSymbol(cmd.symbol, cmd);
     ```
     (须在费用应用后,读到更新后的账户。)
  3. **FORCE/IF/ADL 状态机**:1466-1470 现有 `liquidationEngine.nextLiquidationState(cmd, takerSpr)` 不动(Task 6 已把其内部 ctx 换 tracker)。
  4. **开仓索引**:仓位 0→非0 的登记点——在 `handleMatcherEventMargin` 里 taker `openPositionMargin` 后(1844 附近)与 maker(1921 附近),当 `openVolume` 由 0 变正时调 `liquidationEngine.onPositionOpened(spec.symbolId, uid)`;以及 `placeOrder` 新建仓 `positions.put`(756)后。**统一做法**:封装一个 `private void trackPositionOpenIfNeeded(int symbol, long uid, long preVolume, SymbolPositionRecord spr)`,在 `preVolume==0 && spr.openVolume>0` 时 `onPositionOpened`;在上述成交/建仓点调用(taker/maker 各一次,用各自 `preVolume`)。**只对期货仓**(spec futures)登记。
  5. **平仓索引 + flow 清除**:唯一删除点 `removePositionRecord`(2006-2014)——在 `userProfile.positions.removeKey(...)`(2012)**之前**加:
     ```java
     liquidationEngine.onPositionClosed(record); // 内部: index.remove(record.symbol, uid) + flowTracker.remove(record)
     ```
     覆盖 1874/1941/1038 三条到达路径(全经 removePositionRecord)。另:仓位被 reduce 到 0 但走 `isEmpty()`→removePositionRecord 亦覆盖。
  6. **快照重建**:`recoverStateBySnapshot` 里 `updateProvider(...)`(235-236)之后加 `liquidationEngine.rebuildIndex();`。
  7. **LIQUIDATION_BACKSTOP_TICK**:Task 7 的最小 case 升级为:
     ```java
     case LIQUIDATION_BACKSTOP_TICK:
         liquidationEngine.backstopTick(cmd);
         if (shardId == 0) cmd.resultCode = CommandResultCode.SUCCESS;
         return false;
     ```
     (每分片扫本分片切片 + 本分片 stuck flow。)
  8. **冷启动全扫**:提供 `RiskEngine` 或引擎侧入口供 server 换届时调(Task 9 接):`liquidationEngine.fullScan(cmd)`。因需要一个 `OrderCommand`(取 timestamp)——冷启动由 server 在成为 leader 后发一条命令触发?**决策**:冷启动复用 `LIQUIDATION_BACKSTOP_TICK` 的"全扫版"不现实(它是切片)。改为:server 换届时**直接调** `liquidationEngine.fullScan()`(无 cmd 版,timestamp 用 `flowTracker` 不需要 cmd.timestamp 的路径——冷启动只检测破产发 FORCE,`checkUserInternal` 里用到的 `ts` 传 `0L` 或引擎持有的 `lastAppliedTimestamp`)。**采用**:引擎维护 `private long lastCmdTimestamp`(每次 `checkPositionsOnSymbol/checkUser/backstopTick` 入口 `this.lastCmdTimestamp = cmd.timestamp`),`fullScan()` 无参用 `lastCmdTimestamp`。这样 server 换届可直接 `engine.fullScan()`。

- [ ] **Step 4: 跑测试**
```bash
mvn -pl exchange-core -Dtest=RiskEngineLiquidationWiringTest,ITLiquidationIntegration,ITConservationFuzz,PersistenceTests test
```
Expected: PASS。

- [ ] **Step 5:** 提交。

---

## Task 9: tick 收缩为只发命令 + server 换届 fullScan/setIsLeader

**背景:** `LiquidationEngine` 保留 `extends SimpleScheduledService`(Task 6),本任务把 tick 落地为"只发命令",并把 reprice 从 loan 扫描里摘出来搬到 tick;server 换届启停**沿用现有** `SimpleScheduledService::start/stop`,只补 `fullScan()` 冷启动 + `setIsLeader`。**不新建类。**

**Files:**
- Modify: `exchange-core/.../processors/liquidation/LiquidationEngine.java`(`emitHeartbeatCommands()` + `lastBackstopMs`/`lastRepriceMs` 字段 + 常量;Task 6 已改 `runOneIteration` body 调它)
- Modify: `exchange-core/.../processors/loan/LoanLiquidationEngine.java`(删 67-77 的 reprice 发送块;`check` 只留 isolated/cross loan 扫描,仍被 `LiquidationEngine` 的 loan 旧路调用)
- Modify: server 层 `JraftClusterContainer`(85-91)/ `AeronClusterContainer`(66-72)/ 相关 `ExchangeRuntime`:LEADER→现有 `engines.forEach(SimpleScheduledService::start)` **保留**,追加 `engines.forEach(e -> { e.setIsLeader(isLeaderSupplier); e.fullScan(); })`;非 LEADER→现有 `::stop` 保留。
- Test: 扩 `LiquidationEngineTest`(或 Task 6 的 `LiquidationEngineEventDrivenTest`)加 `emitHeartbeatCommands` 用例;`LoanLiquidationEngineTest` 同步调整。

**Interfaces:**
- `LiquidationEngine.emitHeartbeatCommands()`(private,`runOneIteration` 调):仅 `shardId==0`——按 `BACKSTOP_INTERVAL_MS`(默认 30_000,sysprop `raftexchange.liquidation.backstopIntervalMs`)节流 `publish(ApiLiquidationBackstopTick.builder().build(), null)`;按 `REPRICE_INTERVAL_MS`(1h)节流 `publish(ApiRepriceLoanRates.builder().build(), null)`。节流字段 `lastBackstopMs`/`lastRepriceMs`,`System.currentTimeMillis()`。不碰用户态。
- `LoanLiquidationEngine.check`:删 reprice 发送块,只留 `userProfile.isolatedLoans.forEachValue(this::checkIsolatedLoan); checkCrossLoan(userProfile);`(及 `lastRepriceMs` 字段/常量若无他用则删)。

- [ ] **Step 1: 写失败测试** —— 在 `LiquidationEngineTest` 加 `emitHeartbeatCommands` 用例:mock/spy `LiquidationCmdPublisher`,构造 shard 0 引擎,直接调 `runOneIteration()`(或暴露 package-private `emitHeartbeatCommands()`),断言首调发一条 `ApiLiquidationBackstopTick`;为测节流可在同一 tick 周期内二次调用断言不重发(节流基于 wall-clock,测试可将 `BACKSTOP_INTERVAL_MS` sysprop 设大后验证"二次不发",或将 `lastBackstopMs` 直接置为"刚发过");shard≠0 引擎不发。

- [ ] **Step 2:** 跑失败。
- [ ] **Step 3: 实现** `emitHeartbeatCommands()` + 从 `LoanLiquidationEngine.check` 摘 reprice + server 换届追加 `setIsLeader`/`fullScan`(保留现有 start/stop)。
- [ ] **Step 4: 跑测试**
```bash
mvn -pl exchange-core -Dtest=LiquidationEngineTest,LoanLiquidationEngineTest test
```
Expected: PASS(`LoanLiquidationEngineTest` 里若有断言 reprice 由 check 发出的用例,迁到 `LiquidationEngineTest` 的 heartbeat 用例)。
- [ ] **Step 5:** 提交。

> **server 层编译**:改 `JraftClusterContainer`/`AeronClusterContainer` 后 `mvn -pl raft-exchange-server -am compile` 确认过编(既有教训:改引擎 API 要全仓 grep 下游)。因 `LiquidationEngine` 仍是 `SimpleScheduledService`,server 现有 `engines.forEach(SimpleScheduledService::start/stop)` 无需改类型,改动最小。

---

## Task 10: 全量回归 + 换届 mid-flow 幂等集成用例

**Files:**
- Modify: `exchange-core/src/test/java/.../ITLoanFailoverSnapshot.java` 或新建 `LiquidationStuckRecoveryTest.java`
- 运行全量。

**Interfaces:** 无新增产物;验收整体行为。

- [ ] **Step 1: 写核心新集成用例**(mid-flow 换届重启,spec §8 核心):
  - 构造一个进入强平的期货仓:FORCE 发出并成交出**残余**(部分成交 REJECT 残余)→ flow 处于 WAIT_IF_EXECUTION。
  - 模拟换届:清空 leader-local `flowTracker`(等价新 leader 追踪器空)/ 或走快照恢复 `rebuildIndex`。
  - 触发冷启动 `fullScan()`(或让下一个价格事件命中该残余仓)。
  - 断言:残余仓被当普通破产仓重发 `FORCE_LIQUIDATION`(size 夹到当前残余),最终收敛平掉、**不超平**、全局守恒不变、stateHash 各节点一致(用现有 failover/persistence 双节点或 round-trip 机制)。

- [ ] **Step 2:** 跑失败(逻辑未接则红)。
- [ ] **Step 3:** 视需要微调引擎/接线让用例通过(不应需大改;若需,回到对应 Task)。
- [ ] **Step 4: 全量回归**
```bash
mvn -pl exchange-core test
```
Expected: 全绿(~1118 测试基线不降;新增用例计入)。若 `-am` 依赖或 server 层受影响,另跑 `mvn -pl raft-exchange-server -am test`。
- [ ] **Step 5:** 提交(等用户 review;见提交纪律)。

---

## 测试(总览,验收对照 spec §测试)

- **行为回归底线全绿**:`ITConservationFuzz`、`ITLoanConservation`、`PersistenceTests`、`LiquidationEngineTest`、`ITLiquidationIntegration`、`LoanLiquidationEngineTest`。
- **targeted 触发正确性**(Task 6/8):价格/资金费 apply 后只有受影响用户被检查、破产者发 FORCE。
- **symbol→用户索引**(Task 1/8):开/平仓增删 + 快照恢复重建对拍。
- **冷启动全扫 + 兜底切片**(Task 2/6/8/9)。
- **仓位夹住幂等**(Task 8):重复/重发 FORCE 不超平。
- **mid-flow 换届重启**(Task 10):核心新用例,收敛、不超平、不分叉。
- **无竞态**(结构性):定时器 tick(`runOneIteration`/`emitHeartbeatCommands`)只 publish 命令、不读 `UserProfile`;所有用户态读取都在 on-lane apply。`LiquidationEngine` 仍 `extends SimpleScheduledService`,但后台线程不再触碰共享用户态——竞态根除靠"扫描搬到 on-lane",非删定时器。
- **stateHash 稳定**(Task 5):移除 `liquidationCtx` 后序列化 round-trip + 多节点 hash 一致。

## 关键决策记录(实现者须知)

1. **`LiquidationFlowTracker` 在 R2 apply 路径(`nextLiquidationState`)被所有节点确定性更新**,但**不进 stateHash/snapshot**。因此持续运行的新 leader 天然持有正确 flow;仅"快照恢复后即上位"的节点 tracker 为空 → 靠换届 `fullScan()` 冷启动重扫补齐。正确性**不依赖** tracker(靠 R1 `normalizeCmdPositionSize` 的 size 夹取 + loan 抵押 guard),tracker 只是效率与"跳过流程中仓"。
2. **检查 leader-gated**:`checkPositionsOnSymbol/checkUser/fullScan/backstopTick` 开头 `if(!isLeader) return`(默认 true,便于单测/非集群)。follower 不空跑;即便空跑也无害(publish 在 publisher 层 no-op)。
3. **索引维护非 leader-gated**:`onPositionOpened/Closed` 在所有节点确定性维护(它派生自复制态的开/平仓 apply);保证任一节点上位后索引即时可用。
4. **backstop tick 是每分片 apply**:shard-0 心跳发一条命令,经 raft 复制,所有分片 apply → 各扫本分片切片 + stuck。
5. **loan 侧本计划保留旧扫描路**(经 `loanLiquidationEngine.check(up)` 挂在 futures 检查里),仅摘走 reprice 心跳到 `LiquidationEngine.runOneIteration()` 的 `emitHeartbeatCommands()`。loan 的 targeted 事件化是 **Plan 2**。
