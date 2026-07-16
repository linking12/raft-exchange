# 期货强平:事件驱动 on-lane targeted 重构 实现计划(Plan 1 / 期货)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把期货强平从"每 2s off-lane 全量扫描"改成"命令 apply 内 on-lane、按 symbol→uid targeted 的事件驱动检测",根除扫描数据竞态、提升高杠杆及时性,并把 `LiquidationContext` 从复制态抽成 leader-local。

**Architecture:** 强平检查移到 RiskEngine 命令 handler 内(单写者线程,读一致状态)。价格/资金费命令 apply 后只检查受影响 symbol 的持有者(靠 `LiquidationEngine` 里一个 symbol→uid 索引字段);破产则经现有 `LiquidationCmdPublisher` 发 `FORCE_LIQUIDATION`,下游 IF/ADL 不变。**最小改动:不抽新组件类**——索引/flow-map/游标都是 `LiquidationEngine` 的字段,targeted 入口复用现有 `checkLiquidationIsolated/Cross`。定时器不删:父类(`SimpleScheduledService` 改名 `LiquidationScheduledService`)改为"只发命令的 off-lane 发令器"(持 publisher + 心跳发 `BACKSTOP_TICK`/`REPRICE`),`LiquidationEngine`(子类)只做 on-lane 逻辑。**leader-only 逻辑用父类已有的 `isRunning()` 生命周期标志 gate(server 按 leader 身份 start/stop,exchange-core 不引入 isLeader 概念)。** 强平流程状态 `LiquidationContext` 退出 snapshot/stateHash,改 leader-local flow-map(只 leader 维护);换届后新 leader flow-map 空 → 残余仓当破产仓重发 FORCE 恢复(**无需 stuck-republish**);正确性由 R1 `normalizeCmdPositionSize` 的 size 夹取保证。

**Tech Stack:** Java 25(Corretto),LMAX Disruptor,Eclipse Collections,Chronicle Bytes,JRaft/Aeron(server 层),JUnit 5 + AssertJ + Mockito。构建:`mvn -pl exchange-core test`。

## Global Constraints

- **确定性(Raft RSM)不可破**:apply 路径内只整数运算(`Math.addExact/multiplyExact`);不引入 wall-clock 到复制态(用 `cmd.timestamp`);检查对复制状态**只读**,产出 `FORCE_LIQUIDATION` 经 publisher 进 log 再各节点确定性 apply。心跳发令 / `isRunning()` gate 只是 leader-local 调度,不进复制态。
- **exchange-core 不知道 leadership**:不引入 `isLeader` supplier。leader-only 的 liquidation 逻辑靠父类 `isRunning()`(start/stop 生命周期,server 按 leader 驱动)gate;权威 leader 门控在 server 的 `overrideLiquidationCmdPublisher`(publish 层)。
- **flow-map 只 leader 维护、不进 snapshot/stateHash**:`nextLiquidationState` 加 `isRunning()` gate → follower 不填 flow-map;正确性不依赖它(靠 size 夹取)。换届后新 leader flow-map 空,残余仓由重发 FORCE 恢复。**因此不需要 stuck-republish**。
- **stateHash 参与项变更全节点一致**:移除 `SymbolPositionRecord.liquidationCtx` 的 stateHash 参与项;pre-launch 无历史快照,直接改。
- **其余 leader-local 结构不进 snapshot/stateHash**:symbol→uid 索引、backstop 游标为派生/进程态;索引在所有节点确定性维护(开/平仓 apply,**不 gate**),快照恢复时**重建**;游标换届重置。
- **不改快照字节格式**(除 `liquidationCtx` 移除一处)。
- **行为回归底线全绿**:`ITConservationFuzz`、`ITLoanConservation`、`PersistenceTests`、`LiquidationEngineTest`(及同目录 liquidation 测试)、`ITLiquidationIntegration`、`LoanLiquidationEngineTest`。
- **YAGNI / 不为测试污染代码**:除 `ApiLiquidationBackstopTick`(新命令 payload,必须是类)外**不新建类**;索引等以字段+方法形式落在 `LiquidationEngine`。
- **命名保持** `LiquidationEngine`;**父类 `SimpleScheduledService` 改名 `LiquidationScheduledService`**(职责变为 liquidation 发令器),同步改 server 2 处 `import` + `::start/stop` 引用(Task 3 一起做保证过编)。
- **loan 侧本计划不动**(targeted 事件化是 Plan 2);仅把 reprice 心跳从 `LoanLiquidationEngine.check` 摘到父类发令。loan 检查暂由 `LiquidationEngine` 的 loan 旧路(`loanLiquidationEngine.check(up)`)覆盖。
- **所有扫描只在 on-lane**:server 换届只 `start/stop` 定时器(已有),**绝不直调 `engine` 扫描方法**(那会在换届线程 off-lane 读用户态,重现竞态)。冷启动靠 leader 定时器 start 后的兜底 sweep(经 `BACKSTOP_TICK` apply 在 disruptor 线程跑)。
- **测试运行纪律**:仅在计划标注处运行测试,不自发跑全量。
- **提交纪律**:实现代码写完**先不 commit**,等用户 review(见 memory: commit-after-user-review);文档/计划可 commit。

**关键既有签名/坐标(以此为准):**
- `SimpleScheduledService`(父类,**本计划改名 `LiquidationScheduledService`**):`exchange-core/.../processors/liquidation/SimpleScheduledService.java`。`@RequiredArgsConstructor`,字段 `delay/unit/threadFactory` + `scheduler/future/running(AtomicBoolean)`;构造 `(long delay, TimeUnit unit, ThreadFactory)` 与 `(long,TimeUnit,String name,ThreadFactory)`;`protected abstract void runOneIteration()`;`start()`(置 `running=true` + scheduleWithFixedDelay)/`stop()`(置 `running=false`)/`isRunning()`(`running.get()`);hook `beforeStart/afterStop/handleError`。**唯一子类 `LiquidationEngine`**;server 仅 `engines.forEach(SimpleScheduledService::start/stop)`(`JraftClusterContainer:87-89`+import`:48`、`AeronClusterContainer:68-70`+import`:31`)。
- `LiquidationEngine`:`.../liquidation/LiquidationEngine.java`(513 行)。`extends SimpleScheduledService`(64);构造 `(Supplier<FundEvent> eventSupplier, int shardId, ExchangeConfiguration cfg)`(83-90,内 `super(sysprop raftexchange.liquidation.interval 默认2, SECONDS, ...)`);`updateProvider(SymbolSpecificationProvider, CurrencySpecificationProvider, UserProfileService, IntObjectHashMap<LastPriceCacheRecord>, LoanService)`(92-105,内建 `new LoanLiquidationEngine(this)`)。字段:`shardId`(65)、`stuckThresholdMs`(66,**删**)、`eventsHelper`(67)、`insuranceFundEnabled`(68)、providers/`userProfileService`(72)/`lastPriceCache`(73)/`loanService`(74)、`liquidationCmdPublisher`(76,`@Setter`)、`loanLiquidationEngine`(77)、`inFlightLiquidationCmd`(79,**删**)、`tickBpMarginBaseCache`(80-81)。类级 `@Getter @Slf4j`。方法:`runOneIteration`(116-124)→`checkLiquidations`(137-174,全扫入口:`userProfileService.getUserProfiles().forEachValue(...)` L140,per-user `userProfile.positions` 迭代,`loanLiquidationEngine.check(userProfile)` L172)、`triggerOnce`(126-133)、`start`(109-114)、`tryRepublishStuckLiquidation`(179-202,**删**)、`checkLiquidationIsolated`(204-222)、`checkLiquidationCross`(224-263)、`forceCrossLiquidation`(265-284)、`startLiquidationFlow`(286-298,双门 `inFlightLiquidationCmd.contains(pos)||pos.liquidationCtx!=null`)、`sendWarningEvent`(300-306)、`calculateBankruptcyPrice(SymbolPositionRecord)`(313-338)、`calculateCrossBpMarginBaseAllocation`(352-386)、`nextLiquidationState(OrderCommand,SymbolPositionRecord)`(394-428,唯一写 `pos.liquidationCtx`)、`onMarketDone/onIFTakeoverDone/enterAdlPhase/onADLDone`(431-468)、`buildForceCmd/buildIFCmd/buildADLCmd`(481-495)、`publishTracked`(499-507,add/remove inFlight)/`publishUntracked`(509-511)。`pos.liquidationCtx` 引用点:180,288,396-399,406,432,435,453,467。
- `RiskEngine`:`.../processors/RiskEngine.java`(~2064)。`liquidationEngine` 字段(109);`new LiquidationEngine(sharedPool::getFundEventChain, shardId, cfg)`(146);`updateProvider(...)` 在 `initState`(181-182)、`recoverStateBySnapshot`(235-236)。R1 `preProcessCommand`(295-660):`MARKPRICE_ADJUSTMENT`(442-454,更新 `lastPriceCache` 的 `markPrice`,非 uid-gated,仅 `shardId==0` 写 resultCode)、`SETTLE_FUNDINGFEES` R1(538-554,`fundingFeeProcessor.collectInput`)、`FORCE_LIQUIDATION`(502-506,uid-gated→`normalizeCmdPositionSize`)、`REPRICE_LOAN_RATES`(574-580)。R2 `handlerRiskRelease`(1361-1491):`fundingFeeProcessor.applyEvent`(1445-1449)、`liquidationEngine.nextLiquidationState(cmd, takerSpr)`(1466-1470)。`normalizeCmdPositionSize`(1050-1066,`cmd.size=Math.min(cmd.size,position.openVolume)`)。仓位 map 插入:`userProfile.positions.put(positionRecordKey, position)`(756);交易开/平 `handleMatcherEventMargin`(1797-1950,taker `openPositionMargin` ~1844 / `removePositionRecord` ~1874;maker ~1921 / ~1941);交割 `settlePnl`(1021-1044,`removePositionRecord` 1038);**唯一删除点** `removePositionRecord(...)`(2006-2014,`userProfile.positions.removeKey(...)` 2012)。快照恢复反序列化 UserProfile(198 起,swap 216-240)。`uidForThisHandler`(1244-1246)。**exchange-core 内无 isLeader**(本计划也不引入)。
- `SymbolPositionRecord`:`.../common/SymbolPositionRecord.java`。`liquidationCtx` 字段(67,注释 64-66)、`initialize` 置 null(90)、读构造(123)、`writeMarshallable` 写块(719-724)、`reset` 置 null(747)、`stateHash` 项(767-770,ctx 在 770)、`toString`(779)。BP:`calculateBankruptcyPrice(spec)`(265)、`(spec,marginBase)`(282)。
- `LiquidationContext`:`.../liquidation/LiquidationContext.java`。字段 `state/price/size/originalOrderId/lastTransitionAt`;enum `LiquidationState{LIQUIDATING(1),WAIT_IF_EXECUTION(2),WAIT_ADL_EXECUTION(3)}`;有 `LiquidationContext(BytesIn)`、`writeMarshallable`、`stateHash`(序列化三件套);另有 4 参构造 `(price,size,originalOrderId,ts)`(初始 state=LIQUIDATING——实现者确认)。
- `OrderCommandType`:`.../common/cmd/OrderCommandType.java`,`(byte code,boolean mutate)`。`REPRICE_LOAN_RATES((byte)63,true)`(73)、`FORCE_LIQUIDATION((byte)20,true)`;`isLoan()` switch(111-130,REPRICE 不在其中);static 注册块(134-138)自动。**byte 64 空闲**。
- `ApiRepriceLoanRates`:`.../common/api/ApiRepriceLoanRates.java`(无 payload,`@Builder @EqualsAndHashCode(callSuper=false) extends ApiCommand`)。`ExchangeApi`:`REPRICE_LOAN_RATES_TRANSLATOR`(1092-1096),三处 `instanceof` 分发(140-141、233-234、324-325),map 注册(1404)。
- `LiquidationCmdPublisher`:`void publish(ApiCommand cmd, Runnable onApplied)`。server `overrideLiquidationCmdPublisher(BooleanSupplier isLeader, ...)`(`ExchangeRuntime` 42-65,`if(!isLeader) return`——**保留,权威门控**)。`LoanLiquidationEngine.check`(66-77)含 shard-0 reprice 心跳块,`REPRICE_INTERVAL_MS=1h`(46)、`lastRepriceMs`(50)。

---

## File Structure

**新建(唯一):** `common/api/ApiLiquidationBackstopTick.java` — 空 payload 命令(模板 `ApiRepriceLoanRates`)。

**修改:**
- `common/cmd/OrderCommandType.java` — 加 `LIQUIDATION_BACKSTOP_TICK((byte)64,true)`(不进 `isLoan()`)。
- `ExchangeApi.java` — `LIQUIDATION_BACKSTOP_TICK_TRANSLATOR` + 3 分发 + map 注册。
- `common/SymbolPositionRecord.java` — 移除 `liquidationCtx` 字段/序列化/stateHash。
- `processors/liquidation/LiquidationContext.java` — 删序列化三件套(变纯内存 holder)。
- `processors/liquidation/SimpleScheduledService.java` → **改名 `LiquidationScheduledService.java`**(父类)— 持 `liquidationCmdPublisher`(+setter)、`shardId`、心跳节流字段;`runOneIteration()` 变具体 = shard-0 节流发 `BACKSTOP_TICK`+`REPRICE`;加 `protected publish(ApiCommand,Runnable)`。已有 `isRunning()` 复用为 leader gate。server 2 处引用同步改。
- `processors/liquidation/LiquidationEngine.java`(子类)— 去掉自有 publisher 字段 / `runOneIteration` / `checkLiquidations` / `triggerOnce` / `tryRepublishStuckLiquidation` / `inFlightLiquidationCmd` / `stuckThresholdMs`;加 symbol→uid 索引 + flow-map + backstop 游标(**字段**)+ `checkPositionsOnSymbol/backstopTick/onPositionOpened/onPositionClosed/rebuildIndex`(复用现有 check 方法);`checkPositionsOnSymbol`/`backstopTick`/`nextLiquidationState` 开头 `if(!isRunning())return;`(leader gate);`nextLiquidationState` 与所有 `pos.liquidationCtx` 改走 flow-map;发命令用继承的 `publish(...)`。**无 `fullScan`、无 stuck、无 isLeader**。
- `processors/loan/LoanLiquidationEngine.java` — `check` 删 reprice 心跳块(改由父类发);只留 isolated/cross loan 扫描。
- **server 无新增行为**:仅 Task 3 改名带来的 2 处引用更新;`start/stop` 换届启停与 `overrideLiquidationCmdPublisher` 均不动。

---

## Task 1: 新增 LIQUIDATION_BACKSTOP_TICK 命令(唯一新类)

**Files:**
- Create: `common/api/ApiLiquidationBackstopTick.java`
- Modify: `common/cmd/OrderCommandType.java`(73 后加一行)、`ExchangeApi.java`(translator+3 分发+map)
- Modify: `processors/RiskEngine.java`(主 switch 加最小 case)
- Test: `test/.../ApiLiquidationBackstopTickTest.java`

**Interfaces(Produces):** `OrderCommandType.LIQUIDATION_BACKSTOP_TICK`;`ApiLiquidationBackstopTick`(空 payload);提交后经 RiskEngine 返回 `SUCCESS`(真正委托 `backstopTick` 在 Task 4 接)。

- [ ] **Step 1: 写失败测试** —— 提交 `ApiLiquidationBackstopTick.builder().build()`,断言 `submitCommandAsync(...).get()` resultCode 为 `SUCCESS`(用 `ExchangeTestContainer` 或直接 API)。
- [ ] **Step 2:** 跑失败 `mvn -pl exchange-core -Dtest=ApiLiquidationBackstopTickTest test`。
- [ ] **Step 3: 实现:**
  - `OrderCommandType`:`LIQUIDATION_BACKSTOP_TICK((byte) 64, true),`(不加入 `isLoan()`)。
  - `ApiLiquidationBackstopTick`:
    ```java
    package exchange.core2.core.common.api;
    import lombok.Builder; import lombok.EqualsAndHashCode;
    @Builder @EqualsAndHashCode(callSuper = false)
    public final class ApiLiquidationBackstopTick extends ApiCommand {
        @Override public String toString() { return "[LIQUIDATION_BACKSTOP_TICK]"; }
    }
    ```
  - `ExchangeApi`:仿 `REPRICE_LOAN_RATES_TRANSLATOR` 加 `LIQUIDATION_BACKSTOP_TICK_TRANSLATOR`(`cmd.command=LIQUIDATION_BACKSTOP_TICK; cmd.timestamp=api.timestamp; cmd.resultCode=CommandResultCode.NEW;`);140/233/324 三处加 `else if (cmd instanceof ApiLiquidationBackstopTick)` 分支;1404 附近 `m.put(ApiLiquidationBackstopTick.class, LIQUIDATION_BACKSTOP_TICK_TRANSLATOR)`。
  - `RiskEngine` 主 switch 加最小 case:`case LIQUIDATION_BACKSTOP_TICK: if (shardId == 0) cmd.resultCode = CommandResultCode.SUCCESS; return false;`。
- [ ] **Step 4:** 跑通过。
- [ ] **Step 5:** 提交。

---

## Task 2: LiquidationContext depersist → leader-local flow-map(行为保持)

**目标:** 把强平流程状态从 `SymbolPositionRecord.liquidationCtx`(复制+hash)搬到 `LiquidationEngine` 的一个 leader-local `IdentityHashMap<SymbolPositionRecord, LiquidationContext>` 字段。**触发方式此刻不变**(定时器仍 `checkLiquidations` 全扫)、**暂不加 `isRunning()` gate**(Task 3 再加),纯存储搬迁 + 去 `inFlightLiquidationCmd`,便于独立回归。

**Files:**
- Modify: `common/SymbolPositionRecord.java`(67,90,123,719-724,747,767-770,779)
- Modify: `processors/liquidation/LiquidationContext.java`(删序列化三件套)
- Modify: `processors/liquidation/LiquidationEngine.java`(加 flow-map 字段;`nextLiquidationState` 及所有 `pos.liquidationCtx`→map;`startLiquidationFlow` 双门改单门;去 `inFlightLiquidationCmd`;`publishTracked`→直接 publish)
- Test: `test/.../common/SymbolPositionRecordTest.java`(加 stateHash round-trip 用例);`LiquidationEngineTest` 保持绿

**Interfaces(Produces,Task 3/4 消费):** `LiquidationEngine` 内部持 `private final Map<SymbolPositionRecord,LiquidationContext> flows = new IdentityHashMap<>();` + 私有 `beginFlow/getFlow/removeFlow`;`SymbolPositionRecord` 无 `liquidationCtx`;`LiquidationContext` 纯内存。

- [ ] **Step 1: 写失败测试**(`SymbolPositionRecordTest`)——非空期货仓 `writeMarshallable`→`new SymbolPositionRecord(uid,bytes)`→`restored.stateHash()==original.stateHash()`(证明序列化自洽);字段移除后 `original.liquidationCtx` 引用编译失败即预期。
- [ ] **Step 2:** 先跑现有 `LiquidationEngineTest`,`PersistenceTests` 记基线。
- [ ] **Step 3: 实现:**
  - `SymbolPositionRecord`:删 67 字段(+64-66 注释)、90 与 747 的置 null、123 读、719-724 写块(留 `writeLong(extraMargin)`)、767-770 的 ctx hash 项(让 `extraMargin` 收尾,清多余逗号 + 766 注释)、779 `toString` 片段、无用 import。**读(123)与写(719-724)必须一起删**保证字节自洽。
  - `LiquidationContext`:删 `LiquidationContext(BytesIn)`、`writeMarshallable`、`stateHash` 及 `implements`/import;保留字段+enum+4 参构造。
  - `LiquidationEngine`:加 `flows` 字段 + `beginFlow(pos,price,size,orderId,ts)=new LiquidationContext(...)` 存入 / `getFlow(pos)` / `removeFlow(pos)`。把 `nextLiquidationState`、`onMarketDone`、`onIFTakeoverDone`、`enterAdlPhase`、`onADLDone`、`startLiquidationFlow`、`checkLiquidations` 里所有 `pos.liquidationCtx` 读/写/判空**换成 `getFlow/beginFlow/removeFlow` + 本地变量**。`startLiquidationFlow` 双门 → `if (getFlow(pos) != null) return;`。删 `inFlightLiquidationCmd` 字段;`publishTracked(cmd,pos)`→`liquidationCmdPublisher.publish(cmd, null)`;`publishUntracked` 保留或并为一个 publish。**触发路径与状态机语义逐字保持**(仅存储位置变)。
    - 注:`tryRepublishStuckLiquidation` 本任务**先保留**(它读 `pos.liquidationCtx`,一并改走 `getFlow`),Task 3 再整体删除(stuck 不再需要)。
- [ ] **Step 4: 跑测试** `mvn -pl exchange-core -Dtest=SymbolPositionRecordTest,LiquidationEngineTest,PersistenceTests,ITLiquidationIntegration test`。Expected: PASS(行为等价 + stateHash 稳定)。
- [ ] **Step 5:** 提交。

---

## Task 3: 父类承载发令 + engine 转 on-lane targeted + isRunning gate + 删 stuck

**目标:** 父类改名并变 off-lane 发令器;`LiquidationEngine` 去扫描、加 targeted 入口与索引/游标字段(复用现有 check),用 `isRunning()` gate leader-only 逻辑,删除 stuck。

**Files:**
- Rename+Modify: `processors/liquidation/SimpleScheduledService.java` → `LiquidationScheduledService.java`
- Modify: server `JraftClusterContainer.java`(import`:48` + `:87-89`)、`AeronClusterContainer.java`(import`:31` + `:68-70`)—— 改名同步(仅类型名,行为不变)
- Modify: `processors/liquidation/LiquidationEngine.java`(`extends LiquidationScheduledService`)
- Modify: `processors/loan/LoanLiquidationEngine.java`(删 reprice 心跳块)
- Test: `LiquidationEngineTest`(加 targeted/backstop/heartbeat/running-gate 用例)

**Interfaces(Produces,Task 4 消费):**
- 父类:`@Setter protected LiquidationCmdPublisher liquidationCmdPublisher;`、`@Getter protected final int shardId;`(经构造注入)、`protected final void publish(ApiCommand cmd, Runnable onApplied)`。`runOneIteration()` 具体化 = 心跳发令。`isRunning()` 已有(start→true, stop→false)。
- `LiquidationEngine`:`checkPositionsOnSymbol(int symbol, OrderCommand cmd)`、`backstopTick(OrderCommand cmd)`、`onPositionOpened(int symbol, long uid)`、`onPositionClosed(SymbolPositionRecord pos)`、`rebuildIndex()`;`nextLiquidationState` 签名不变但加 `isRunning()` gate。**无 `fullScan`、无 `setIsLeader`、无 stuck**。

- [ ] **Step 1: 写测试**(`LiquidationEngineTest` 新增):
  - **heartbeat**:shard-0 引擎 `start()` 后 `runOneIteration()` 首调 publish 一条 `ApiLiquidationBackstopTick`(mock publisher 捕获);节流内二次不重发;shard≠0 不发。
  - **targeted**:两用户仅一个持有 symbol S,mark 设破产;引擎 `start()` 后 `checkPositionsOnSymbol(S, cmd)` 只对持有者发 FORCE。
  - **skip active flow**:对 `getFlow(pos)!=null` 的仓不重复发。
  - **backstop 切片**:`backstopTick(cmd)` 分片覆盖全部用户(多次调用轮完),破产者发 FORCE。**(无 stuck 分支)**
  - **running gate**:引擎**未 `start()`**(`isRunning()==false`)时 `checkPositionsOnSymbol`/`backstopTick`/`nextLiquidationState` 全部 no-op(不发、flow-map 不变)。
- [ ] **Step 2:** 跑失败。
- [ ] **Step 3: 实现:**
  - **父类(先 rename:文件名/类名/构造名 + server 2 文件 import 与 `::start/stop`;`LiquidationEngine extends LiquidationScheduledService`):** 加 `@Setter LiquidationCmdPublisher liquidationCmdPublisher;`、`@Getter final int shardId;`(构造加 shardId 入参)、`long lastBackstopMs, lastRepriceMs;`、常量 `BACKSTOP_INTERVAL_MS`(默认 30_000,sysprop `raftexchange.liquidation.backstopIntervalMs`)/`REPRICE_INTERVAL_MS`(1h)。`protected final void publish(ApiCommand cmd, Runnable r){ if(liquidationCmdPublisher!=null) liquidationCmdPublisher.publish(cmd,r); }`。`runOneIteration()` 改具体:
    ```java
    protected void runOneIteration() {
        if (shardId != 0) return;                       // 全局命令单发
        final long now = System.currentTimeMillis();
        if (now - lastBackstopMs >= BACKSTOP_INTERVAL_MS) { lastBackstopMs = now; publish(ApiLiquidationBackstopTick.builder().build(), null); }
        if (now - lastRepriceMs  >= REPRICE_INTERVAL_MS)  { lastRepriceMs  = now; publish(ApiRepriceLoanRates.builder().build(), null); }
    }
    ```
    (类仍 `abstract`,现无 abstract 方法,合法。)
  - **`LiquidationEngine`:** 构造 `super(...)` 传入 shardId;删自有 `liquidationCmdPublisher` 字段(用继承)、`runOneIteration`/`checkLiquidations`/`triggerOnce`/`start` override(父类足够;原 `start` 若有 publisher null-check 移到父类/setter)、`tryRepublishStuckLiquidation`(**整删**)、`stuckThresholdMs`(**删**)。`publishTracked/publishUntracked` 调用点改 `publish(...)`。加字段:
    - `private final IntObjectHashMap<MutableLongSet> symbolToUsers = new IntObjectHashMap<>();`(索引)
    - `private long[] backstopSnapshot; private int backstopCursor;`(游标)
  - 新/改方法(时间戳一律用入参 `cmd.timestamp`):
    - `checkPositionsOnSymbol(int symbol, OrderCommand cmd)`:`if(!isRunning())return;` 取 `MutableLongSet users=symbolToUsers.get(symbol); if(users!=null) users.forEach(uid -> checkUser(userProfileService.getUserProfile(uid), cmd.timestamp));`
    - `private void checkUser(UserProfile up, long ts)`:复现原 `checkLiquidations` 单用户体——`tickBpMarginBaseCache` 按需 clear、迭代 `up.positions`、期货仓 `checkLiquidationIsolated`/聚合 `checkLiquidationCross`、`loanLiquidationEngine.check(up)`;**现有方法内部发 FORCE 的 `startLiquidationFlow` 原样复用**,`ts` 传给需要时间戳处(替代原 `System.currentTimeMillis()`)。
    - `backstopTick(OrderCommand cmd)`:`if(!isRunning())return;` 切片(**兼冷启动**:leader 定时器 start 后每 tick 推进,一轮 sweep 覆盖全部):游标取 `userProfileService.getUserProfiles().keySet().toArray()`(uid `long[]`)快照,每 tick 取 `[cursor, cursor+N)` 的 uid,对每个 `UserProfile up = userProfileService.getUserProfile(uid); if(up!=null) checkUser(up, cmd.timestamp);`,`cursor+=N`,扫到尾归零 + 换快照。**用户数据来自引擎自己的 `userProfileService`,命令只提供 `cmd.timestamp`**。**无 stuck 分支**。
    - `nextLiquidationState(OrderCommand cmd, SymbolPositionRecord pos)`:开头加 `if(!isRunning())return;` → flow-map 只 leader 维护(follower 不填)。其余 flow-map 逻辑同 Task 2。
    - `onPositionOpened(int symbol,long uid)`:`symbolToUsers.getIfAbsentPut(symbol,LongHashSet::new).add(uid);`(**不 gate**)
    - `onPositionClosed(SymbolPositionRecord pos)`:`MutableLongSet s=symbolToUsers.get(pos.symbol); if(s!=null){s.remove(pos.uid); if(s.isEmpty())symbolToUsers.remove(pos.symbol);} removeFlow(pos);`(**不 gate**)
    - `rebuildIndex()`:`symbolToUsers.clear(); flows.clear(); backstopSnapshot=null; backstopCursor=0;` 再遍历 `userProfileService.getUserProfiles()` 各非空期货仓 `onPositionOpened(pos.symbol, up.uid)`。
  - **`LoanLiquidationEngine.check`:** 删 66-77 reprice 心跳块,只留 `userProfile.isolatedLoans.forEachValue(this::checkIsolatedLoan); checkCrossLoan(userProfile);`(及 `lastRepriceMs`/`REPRICE_INTERVAL_MS` 若无他用则删)。

  **共用结构(伪代码,防理解偏差):** 两入口只是"挑谁"不同,都灌进同一个 `checkUser`;`checkUser` = 原 `checkLiquidations` 的单用户体逐字搬(`now`→`ts`),`checkLiquidationIsolated/Cross/startLiquidationFlow` 原地不改。
  ```java
  void checkPositionsOnSymbol(int symbol, OrderCommand cmd) {         // 入口A:targeted
      if (!isRunning()) return;
      MutableLongSet holders = symbolToUsers.get(symbol);
      if (holders != null) holders.forEach(uid -> {
          UserProfile up = userProfileService.getUserProfile(uid);
          if (up != null) checkUser(up, cmd.timestamp);
      });
  }
  void backstopTick(OrderCommand cmd) {                               // 入口B:切片兜底
      if (!isRunning()) return;
      if (snapshot == null || cursor >= snapshot.length) { snapshot = userProfileService.getUserProfiles().keySet().toArray(); cursor = 0; }
      int end = Math.min(cursor + N, snapshot.length);
      for (int i = cursor; i < end; i++) {
          UserProfile up = userProfileService.getUserProfile(snapshot[i]);
          if (up != null) checkUser(up, cmd.timestamp);
      }
      cursor = end;
  }
  void checkUser(UserProfile up, long ts) {                           // 共用核心 = 原单用户体
      IntObjectHashMap<List<SymbolPositionRecord>> crossByCurrency = newMap();
      up.positions.forEachValue(pos -> {
          if (pos == null || pos.openVolume == 0) return;
          CoreSymbolSpecification spec = symbolSpecProvider.get(pos.symbol);
          if (!isFuturesContract(spec.type)) return;
          LastPriceCacheRecord price = lastPriceCache.get(pos.symbol);
          if (price == null) return;
          if (pos.marginMode == ISOLATED) checkLiquidationIsolated(up, spec, price, pos);   // 现有,不改
          else crossByCurrency.getIfAbsentPut(spec.quoteCurrency, newList()).add(pos);
      });
      checkLiquidationCross(up, crossByCurrency);                     // 现有,不改
      loanLiquidationEngine.check(up);                               // loan 旧路(Plan 2)
  }
  ```
- [ ] **Step 4: 跑测试** `mvn -pl exchange-core -Dtest=LiquidationEngineTest,LoanLiquidationEngineTest test` + `mvn -pl raft-exchange-server -am compile`(父类改名后确认 server 过编)。Expected: exchange-core PASS(新用例 + loan 回归)、server 过编。**注**:`ITLiquidationIntegration` 因触发未接(Task 4)此刻可能红,与 Task 4 联合验收。
- [ ] **Step 5:** 提交(**Task 3 + Task 4 联合验收**:集成检测在 Task 4 闭环,`ITLiquidationIntegration` 在 Task 4 末转绿)。

---

## Task 4: RiskEngine 接线(触发委托 + 索引维护 + 快照重建 + 兜底)

**Files:**
- Modify: `processors/RiskEngine.java`
- Test: `test/.../processors/RiskEngineLiquidationWiringTest.java` + `ITLiquidationIntegration`

**Interfaces(Consumes):** Task 3 的 `checkPositionsOnSymbol/backstopTick/onPositionOpened/onPositionClosed/rebuildIndex`。

- [ ] **Step 1: 写测试**(`RiskEngineLiquidationWiringTest`):价格触发只查持有者并对破产者发 FORCE;资金费 apply 后触发该 perp 持有者检查;开仓→`symbolToUsers` 含 (symbol,uid),平仓→移除且 flow 清;快照恢复后索引与逐仓遍历对拍;`ApiLiquidationBackstopTick`→`backstopTick` 被调;重复投 `FORCE_LIQUIDATION`(size 超 openVolume)经 `normalizeCmdPositionSize` 夹取不超平。
- [ ] **Step 2:** 跑失败。
- [ ] **Step 3: 实现接线:**
  1. `MARKPRICE_ADJUSTMENT`(442-454):`priceRecord.markPrice = cmd.price;` 后加 `liquidationEngine.checkPositionsOnSymbol(cmd.symbol, cmd);`。
  2. `SETTLE_FUNDINGFEES`:R2 `handlerRiskRelease` 的 `fundingFeeProcessor.applyEvent(...)`(1445-1449)**之后**加 `liquidationEngine.checkPositionsOnSymbol(cmd.symbol, cmd);`(须费用应用后)。
  3. 开仓索引:封装 `private void trackOpenIfNeeded(int symbol,long uid,long preVolume,SymbolPositionRecord spr){ if(preVolume==0 && spr.openVolume>0 && <spr 是期货>) liquidationEngine.onPositionOpened(symbol, uid); }`,在 `handleMatcherEventMargin` taker(~1844)/maker(~1921)开仓点与 `placeOrder` 新建仓(756 后)调用(各用其 `preVolume`)。
  4. 平仓索引+flow 清:`removePositionRecord`(2006-2014)里 `positions.removeKey(...)`(2012)**前**加 `liquidationEngine.onPositionClosed(record);`(覆盖 1874/1941/1038 全路径)。
  5. `LIQUIDATION_BACKSTOP_TICK`:Task 1 最小 case 升级为 `case LIQUIDATION_BACKSTOP_TICK: liquidationEngine.backstopTick(cmd); if(shardId==0) cmd.resultCode=CommandResultCode.SUCCESS; return false;`。
  6. 快照重建:`recoverStateBySnapshot` 的 `updateProvider(...)`(235-236)后加 `liquidationEngine.rebuildIndex();`。
- [ ] **Step 4: 跑测试** `mvn -pl exchange-core -Dtest=RiskEngineLiquidationWiringTest,ITLiquidationIntegration,ITConservationFuzz,PersistenceTests test`。Expected: PASS。
- [ ] **Step 5:** 提交。

---

## Task 5: 全量回归 + 换届 mid-flow 幂等集成用例(重发 FORCE 恢复)

**Files:** 扩 `ITLoanFailoverSnapshot` 或新建 `LiquidationFailoverRecoveryTest.java`

- [ ] **Step 1: 核心用例**(spec §8,验证"无 stuck、靠重发 FORCE 恢复"):FORCE 出残余(部分成交 REJECT)→ flow 处 WAIT_IF_EXECUTION;模拟换届 → **新 leader flow-map 空**(清 `flows`,模拟 follower 从未填 / 快照恢复)→ 投 `LIQUIDATION_BACKSTOP_TICK`(或下个价格事件)→ 残余仓不被跳过、当破产仓**重发 FORCE**(size 夹到当前残余)→ 重走 FORCE→IF→ADL 收敛、**不超平**、守恒不变、多节点 stateHash 一致。
- [ ] **Step 2:** 跑失败。
- [ ] **Step 3:** 视需要微调(不应大改;需则回对应 Task)。
- [ ] **Step 4: 全量** `mvn -pl exchange-core test`(~1118 基线不降 + 新用例);`mvn -pl raft-exchange-server -am test`(在线;离线 provider 缺失则记待补)。
- [ ] **Step 5:** 提交(等 review)。

---

## 测试(总览)

- 行为回归底线全绿(见 Global Constraints)。
- targeted 触发正确性(Task 3/4):价格/资金费 apply 后只查受影响用户、破产者发 FORCE。
- symbol→uid 索引(Task 3/4):开/平仓增删 + 快照重建对拍。
- 兜底切片 sweep(兼冷启动,Task 3/4);**无 stuck**。
- running gate(Task 3):引擎未 start 时 `checkPositionsOnSymbol`/`backstopTick`/`nextLiquidationState` 全 no-op(follower 语义)。
- 仓位夹住幂等(Task 4):重复/重发 FORCE 不超平。
- mid-flow 换届重启(Task 5):新 leader flow-map 空 → 重发 FORCE 收敛、不超平、不分叉。
- 无竞态(结构性):父类 tick 只 publish、不读 `UserProfile`;所有用户态读在 on-lane apply;server 不直调扫描。
- stateHash 稳定(Task 2):移除 `liquidationCtx` 后序列化 round-trip + 多节点一致。

## 关键决策记录

1. **只新建 `ApiLiquidationBackstopTick` 一个类**;索引/flow-map/游标是 `LiquidationEngine` 字段——minimal diff 降低确定性代码风险。
2. **父类 `LiquidationScheduledService`(原 `SimpleScheduledService`)= off-lane 发令器,持 publisher + 心跳发令;`LiquidationEngine`(子类)= on-lane 逻辑**。竞态根除靠"父类 tick 不碰用户态",非删定时器。
3. **exchange-core 不引入 `isLeader`**:leader-only 逻辑用父类 `isRunning()`(start/stop 生命周期,server 按 leader 驱动)gate。exchange-core 只知"我在跑",不知"谁是 leader";权威门控在 server publisher。
4. **flow-map 只 leader 维护**(`nextLiquidationState` 加 `isRunning()` gate → follower 不填);不进 stateHash/snapshot;正确性靠 R1 `normalizeCmdPositionSize` size 夹取。**这使 stuck 不再需要**:换届后新 leader flow-map 空 → 残余仓当破产仓重发 FORCE 恢复(price/backstop 触发),重走 FORCE→IF→ADL 收敛。删 `tryRepublishStuckLiquidation` + `stuckThresholdMs`。
5. **索引维护不 gate**(`onPositionOpened/Closed` 在开/平仓 apply、所有节点确定性维护);快照恢复 `rebuildIndex`。派生态,任一节点上位即可用。
6. **无独立冷启动全扫 / 无 fullScan**:兜底切片 sweep 一轮覆盖全部,兼冷启动;所有扫描 on-lane;server 不直调扫描。代价:换届后空闲已破产仓冷启动延迟 ≤ 一个 sweep 周期(价格在动的仓被 targeted 即时抓)。
7. **backstop tick 每分片 apply**:shard-0 心跳发一条命令,raft 复制,各分片 apply 扫本分片切片。
9. **`checkPositionsOnSymbol` 与 `backstopTick` 共用核心 `checkUser`**(检测逻辑一份,复用现有 `checkLiquidationIsolated/Cross`);两者只是"喂给 `checkUser` 的用户集"不同。**backstop 按"用户"切片(每 tick 固定 N 个,游标推进),不按 symbol 切**——按 symbol 切会因热门 symbol 持有者过多导致单 tick 巨型扫描(重现拥堵);按用户切每 tick 成本恒定。
8. **loan 侧本计划仅摘 reprice 心跳到父类**,targeted 事件化是 **Plan 2**。
