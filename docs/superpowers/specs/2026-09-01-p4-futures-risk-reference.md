# P4 参考：RiskEngine 期货 / 统一账户（futures / unified-account）路径精确语义

> P4 移植的**权威业务参考**，从 Java `RiskEngine.java`(1639) + `SymbolPositionRecord.java`(742) + `UserProfile.java` + `PositionDirection/PositionMode/MarginMode/CoreSymbolSpecification` 抽出 `FUTURES_CONTRACT_PERPETUAL/DELIVERY` 风控路径。**忽略**现货(已移植 P3)、loan(P5)、liquidation-scan/ADL(P6)、funding 内部(P6)——只标 hook 点。行号指对应 Java 文件。

## 1. SymbolPositionRecord — 字段与生命周期

字段（`SymbolPositionRecord.java:48-85`）：`uid`；`symbol`/`currency`(结算=quote 币)；`direction`(LONG/SHORT/EMPTY)；`openVolume`(baseScaleK，无符号，符号由 direction 带)；`openInitMarginSum`(sizePriceScale=baseScaleK×quoteScaleK，当前开仓量锁定的初始保证金)；`openPriceSum`(sizePriceScale，成本基；均价=openPriceSum/openVolume)；`profit`(sizePriceScale，**已实现**PnL 累加器，仅在仓位清空时入账)；`pendingSellSize`/`pendingBuySize`(baseScaleK，挂单量，R1 发单前加、R2 成交/拒/减确认时减)；`pendingSellAvgPrice`/`pendingBuyAvgPrice`(挂单侧加权均价，最坏敞口/费用估算用)；`leverage`(updateLeverage 归一 0→1)；`marginMode`(ISOLATED 默认/CROSS)；`extraMargin`(sizePriceScale，MARGIN_ADJUSTMENT 手动加的保证金，清空时整额退)；`adlEligibility`/`pendingADLSize`/`liquidationFlow`(leader-local，不持久化/不入 hash)。

- `isEmpty()`(`:152-154`)：`openVolume==0 && pendingSellSize==0 && pendingBuySize==0` —— 拆记录的触发。
- 挂单 hold/release：`pendingHold(action,size,price)`(`:160-168`)、`pendingHoldBudget(action,size,budgetNotional)`(`:179-199`，BUDGET 直接跟 notional、ceil 保守)——R1 发单前调；`pendingRelease(action,size)`(`:210-226`)——R2 成交/拒/减调，返回实际释放，侧归零时重置 avg。
- 保证金/敞口：
  - `calculateRequiredMarginForFutures(spec[,lev])`(`:494-539`)——含挂单的总保证金+费预留。`worstCaseNotional = max(|open+bid|,|open-ask|)`，减 `|open|` 得 `newExposureNotional`(只减敞口的挂单不额外要保证金)，返回 `openInitMarginSum + calculateInitMargin(newExposure,lev) + max(bidFee,askFee)`。
  - `calculateRequiredMarginForOrder(spec,action,orderNotional)`(`:548-569`)——"有此单 vs 无此单"最坏敞口差；纯减仓/挂单抵消时返回 **-1 哨兵**，让调用方回退到 `calculateRequiredMarginForFutures`。
  - `estimateNotionalForOrder`(`:574-579`)——保守 notional，仅 isValidLeverage 用。
  - `calculatePendingFeeForOrder[Budget]`(`:581-615`)——加此单后较差挂单侧的 taker 费估算，仅 NSF 预检、不实收。
  - `estimateUnrealizedProfit(price)`(`:240-245`)=`sign×(openVolume×mark − openPriceSum)`；`estimatePnl`=`profit + UPnL`。
  - `calculateMaintenanceMargin(spec,price)`(`:478-484`)——MM 只算 openVolume×mark(忽略挂单)，清算风险用、**R1 NSF 不用**。
  - `calculateBankruptcyPrice`/`estimateLiquidationPrice`/`estimateMarginRatioScaleK`(`:295-469`)——清算/ADL math，P6 用；`calculateBankruptcyPrice` 的 CROSS 分支收 `crossMarginBaseFn` 回调，由 `UserProfile.crossMarginBaseAllocation` 喂(§5)。
- 开平原语：
  - `openPositionMargin(action,sizeToOpen,tradePrice,spec,priceRec)`(`:660-669`)：增 openVolume；`openInitMarginSum += calculateInitMargin(mark×size, lev)`(保证金按**mark**，保守)；`openPriceSum += tradePrice×size`(成本基按**trade**)；设 direction。
  - `closeCurrentPositionFutures(action,tradeSize,tradePrice)`(`:625-654`)——唯一平/翻仓原语：
    - 无仓或同向 → 原样返回 tradeSize(无可平，全去开)。
    - **部分平**(openVolume>tradeSize)：按比例释放保证金 `truncMulDiv(openInitMarginSum,tradeSize,openVolume)`，减 openVolume，`openPriceSum -= tradeSize×tradePrice`(减成本基**非均价**——把 PnL 推迟进剩余仓的成本基，跨部分成交守恒)，**不实现 PnL**，返回 0。
    - **全平/翻仓**(tradeSize≥openVolume)：实现整仓 PnL(`sign×(openVolume×tradePrice − openPriceSum)` 累加进 profit)，清零 openInitMarginSum/openPriceSum/openVolume，返回 `tradeSize − openVolume`(翻仓余量)给调用方喂 openPositionMargin。
  - R2 里恒 **close-then-open**：`sizeToOpen = close(...)`；`sizeToOpen>0` 则 `openPositionMargin(...)`。无独立 flip 方法。
- `reset()`(`:693-711`) 池复用清零（`ObjectsPool.SYMBOL_POSITION_RECORD`；Rust 可弃池）。

## 2. PositionMode / MarginMode
- **PositionMode**：`ONEWAY(0)` 默认，每 symbol 一记录、键=raw symbol；`HEDGE(1)`，每 symbol 两记录、键=+symbol(多)/-symbol(空)。
  - 键逻辑 `createPositionsKey(symbol,action,command)`(`UserProfile.java:163-172`)：HEDGE→`action==BID?symbol:-symbol`；若 command 是 `CLOSE_POSITION`/`FORCE_LIQUIDATION` 则再翻符号(`-key`)(那些命令 action 表"平仓侧"=仓位反向)。ONEWAY 恒 raw symbol。`createPositionsKey(record)`(`:174-179`)：HEDGE→`direction.multiplier×symbol`。
  - ONEWAY 只减仓特例 `placeOrder`(`:464-472`)：`ONEWAY && cmd.isReduceOnly()` 时 `cmd.size` 用 `maxClosableSize` 夹到反向 openVolume；夹到≤0 则静默 SUCCESS no-op(绝不开新敞口)。HEDGE 无此夹(两腿独立)。
- **MarginMode**：`ISOLATED(0)` 默认，自筹保证金、PnL 不外借；`CROSS(1)`，按币种进账户级池，某 CROSS 仓 UPnL 可垫同币另一 CROSS 仓保证金(§5)。同 symbol 各仓必须同 marginMode + 同 leverage：`placeOrder`(`:448-453`) 用 countPositionRecord 校验，不符 `RISK_MARGIN_MODE_MISMATCH`/`RISK_LEVERAGE_MISMATCH`。

## 3. R1 期货路径
入口 `preProcessCommand`(`:260-385`)。`PLACE_ORDER`→`placeOrderRiskCheck`(`:399-420`)→`placeOrder`(`:432-504`)；`CLOSE_POSITION`→`closePositionRiskCheck`(`:823-865`)；`FORCE_LIQUIDATION`/`IF_TAKEOVER`/`AUTO_DELEVERAGING`→`normalizeCmdPositionSize`(`:724-740`，清算侧、仅标)。

### placeOrder 期货分支(`:436-503`) 检查序
1. `isFuturesContract(spec.type)` 否则 `UNSUPPORTED_SYMBOL_TYPE`。
2. `cfgMarginTradingEnabled` 否则 `RISK_MARGIN_TRADING_DISABLED`。
3. mark price 有(`lastPriceCache.get(symbol)` 非零)否则 `RISK_MARKPRICE_NOT_AVAILABLE`。
4. marginMode/leverage 跨腿一致(`:448-453`)。
5. 解析/分配 SymbolPositionRecord(键=createPositionsKey)；缺则池取+initialize，**NSF 通过前不插入 positions**(`:489-492`)，失败可干净归还池、不污染 map。
6. ONEWAY reduce-only 夹(`:464-472`,§2)。
7. `estimateNotionalForOrder` + `spec.isValidLeverage(notional,lev)` 否则 `RISK_INVALID_LEVERAGE`。
8. `canPlaceMarginOrder(...)` NSF 否则 `RISK_NSF`。
9. 成功：提交仓入 map + `liquidationEngine.onPositionOpened`(hook)；`pendingHold[Budget]` 按单型；`sendLockPendingEvent`；返回 `VALID_FOR_MATCHING_ENGINE`。

### canPlaceMarginOrder NSF 公式(`:533-623`)（终比在 currency scale）
1. `positionMargin` = `calculateRequiredMarginForOrder(...)`；返回 -1(不扩最坏敞口)则回退 `calculateRequiredMarginForFutures`。orderNotional = BUDGET 用 cmd.price，否则 size×price。
2. `crossFreeMargin`(currency scale，可负)：遍历**所有** positions(`:561-577`)：本仓(identity 比对)若 CROSS 加自身 estimatePnl(用本单 spec 缩放)；其他同 quoteCurrency 仓：CROSS 加其 estimatePnl(用其自己 otherSpec 缩放)、**总是**减其 `calculateRequiredMarginForFutures`(任何仓的锁定保证金都减可用；只有 CROSS PnL 加回)。每项各自先 sizePriceToCurrencyScale。
3. `pendingFee` = calculatePendingFeeForOrder[Budget](taker 率估、预检非实收)。
4. `openLoss`：BUDGET 单跳过。否则 openingSize=全 cmd.size，除非(ONEWAY 且反向仓)则 `max(0, cmd.size − openVolume)`；`openLoss = max(0, orderCost−markCost)`(BID) 或 `max(0, markCost−orderCost)`(ASK)——防"以劣于 mark 的价开仓后首 tick 被清算"。
5. 比较：`spendable = accounts[cur] − exchangeLocked[cur] − loanCollateralLocked`；`required = sizePriceToCurrencyScale(positionMargin+pendingFee+openLoss) − crossFreeMargin`；**通过 iff required ≤ spendable**。跨币 PnL 绝不跨币抵。

### CLOSE_POSITION(`closePositionRiskCheck` `:823-865`)
纯减仓、无新敞口 math。同守卫序；缺仓或 `maxClosableSize≤0`→SUCCESS no-op；有效减仓：`cmd.size=closeSize`，`cmd.leverage/marginMode` 强制随仓(`:855-856`)，`pendingHold`，`sendLockPendingEvent`。`maxClosableSize`(`:870-875`)：0 除非 `direction.isOppositeToAction(action)`，否则 `min(req, openVolume)`。

### normalizeCmdPositionSize(`:724-740`)
FORCE_LIQUIDATION/IF_TAKEOVER/AUTO_DELEVERAGING 用：把 cmd.size 夹到 R1 时 openVolume，纠正 enqueue 与处理间的陈旧。无 reduce-only 方向守卫(方向性因命令视角而异)。

结果码：`AUTH_INVALID_USER`/`INVALID_SYMBOL`/`VALID_FOR_MATCHING_ENGINE`/`UNSUPPORTED_SYMBOL_TYPE`/`RISK_MARGIN_TRADING_DISABLED`/`RISK_MARKPRICE_NOT_AVAILABLE`/`RISK_MARGIN_MODE_MISMATCH`/`RISK_LEVERAGE_MISMATCH`/`RISK_INVALID_LEVERAGE`/`RISK_NSF`/`SUCCESS`。

## 4. R2 期货路径 — handlerRiskRelease → handleMatcherEventMargin
`handlerRiskRelease`(`:885-1023`)：守卫 null/BINARY_EVENT + 非订单命令；非现货解析 `takerUp`/`takerSpr`(`:946-948`)，算 `takerOtherLocked`(总 locked 减本仓贡献，`:952-959`)，按 cmd.command 分派：`IF_TAKEOVER`→ifProcessor、`AUTO_DELEVERAGING`→adlProcessor、`SETTLE_FUNDINGFEES`→fundingFeeProcessor+checkPositions(**funding hook**，内部 P6)、else(普通 PLACE/CLOSE/FORCE_LIQUIDATION 成交)→`handleMatcherEventMargin` 循环(**期货结算核心**)。FORCE_LIQUIDATION 后 `collectLiquidationFee`(`:991-993`)+`advanceLiquidation`(hook)。尾：mark-price 缓存更新(`:1001-1021`，现货期货共用)。

### handleMatcherEventMargin(`:1358-1511`)
taker 块(`:1369-1443`) + maker 块(`:1445-1510`，仅 uidForThisHandler(matchedOrderUid))。maker 的 SPR 用 `getPositionRecordOrThrowEx`(必须已存在)，`makerOtherLocked` 现算。消费字段：`eventType`(TRADE/REJECT/REDUCE)、`size`、`price`、`matchedOrderUid`、`matchedOrderId`、`matchedOrderCommandType`、`nextEvent`。**bidderHoldPrice 不用**(现货专用)。

**TRADE**(两侧同逻辑，taker vs makerAction=opposite)：
1. `preVolume = spr.openVolume` 快照。
2. `pendingReleasedSize = spr.pendingRelease(action, mte.size)`；>0 则 sendUnlockPendingEvent(锁定保证金 delta = otherLocked + calculateLockedMargin)。
3. `sizeToOpen = spr.closeCurrentPositionFutures(action, mte.size, mte.price)`；`closedSize = max(0, preVolume − openVolume)`。
4. closedSize>0：**close-fee** = calculateTaker/MakerFee(closedSize, mte.price, spec)(按侧 taker/maker 率) → currency scale，**立即从 accounts 扣**、进全局 `fees` 池；sendClosePositionEvent。注意：已实现 PnL **此处不付**——close 只把 PnL 累进 spr.profit，仓清空时才付(步骤 6)。
5. sizeToOpen>0(翻仓或纯开)：`openPositionMargin(action, sizeToOpen, mte.price, spec, mark)`(保证金按 mark、成本按 trade)；open-fee 同 close 扣/进池；sendOpenPositionEvent。
6. `spr.isEmpty()`(全平无再开)：`refundExtraMargin`(退 extraMargin 进 accounts、清零、sendMarginRefundEvent) → 快照 `profitToSettle=spr.profit` → `removePositionRecord`(profit 经 sizePriceToCurrencyScale 进 accounts、onPositionClosed hook、删 map、归池) → profitToSettle≠0 则 sendPnlSettlementEvent。**这是普通成交里已实现 PnL 唯一入 accounts 处**。

**REJECT/REDUCE**(`:1416-1424`)：仅 `pendingRelease` 无 account 改动，然后同 isEmpty() 拆记录检查。

taker/maker 不对称：taker 路由键可为 `SYSTEM_TRIGGERED_ORDER_ID`(当 isLiquidationOrderId)；maker 侧 isLiquidation 恒 false(`:1471`)。

### collectLiquidationFee(`:1522-1550`, 仅 FORCE_LIQUIDATION)
链上 taker TRADE size/notional 求和 → calculateLiquidationFee → 扣 accounts、进 liquidationService 池(**独立于 RiskEngine.fees**)、sendLiquidationFeeEvent。P6 hook。

### 共享 helper
- `refundExtraMargin`(`:1553-1574`)：extraMargin(sizePriceScale)经 **sizePriceToCurrencyScale**(非 symbolToCurrencyScale，`:1567-1568`)进 accounts、清零。
- `removePositionRecord`(`:1580-1589`)：残余 profit 进 accounts、onPositionClosed(hook)、删 map、归池。
- `calculateLockedMargin(pos,spec,cs)`(`:1079-1083`)=`sizePriceToCurrencyScale(calculateRequiredMarginForFutures)`。
- `calculateLocked(up,cur)`(`:1040-1055`)=**free=accounts−locked 不变式**：Σ 期货保证金(calculateLockedMargin) + exchangeLocked[cur](现货) + loanCollateralLocked(`:1063-1072`，P5)。R1/R2 事件里每个 free 旁的 locked 都是它。

## 5. 统一/全仓账户（统一账户）
- `UserProfile.calculateCrossAvailable`(`UserProfile.java:229-240`)：`accounts[cur] − exchangeLocked[cur] − Σ(ISOLATED 仓 sizePriceToCurrencyScale(calculateRequiredMarginForFutures))`。**不减 CROSS 仓保证金**(CROSS 是虚拟分配)、不加 UPnL(caller 另加)。openInitMarginSum 开仓时未物理扣 accounts(仅仓内虚锁)故 ISOLATED 要显式减；extraMargin 已在 MARGIN_ADJUSTMENT 物理扣故不重复减。用于 checkCross(清算)/报表/事件 free。
- `crossMarginBaseAllocation`(`:263-312`)：每币算账户级 `marginBalance = crossAvailable + Σ UPnL(该币所有 CROSS 仓)`，按各仓 MM 占比分 `allocated_i = marginBalance×mm_i/ΣMM`，再 `marginBase_i = allocated_i − UPnL_i`(currency scale) → currencyToSizePriceScale 喂 calculateBankruptcyPrice 的 CROSS marginBase。不变式 `Σ marginBase_i = crossAvailable`。ΣMM==0 组略过。**统一账户跨保证金共享核心**。
- ISOLATED vs CROSS free-margin：ISOLATED 的 calculateRequiredMarginForFutures 是硬扣、PnL 不外借(canPlaceMarginOrder `:561-577`、calculateFreeFuturesMargin `:774-799` 里 ISOLATED 仅对自身 symbol 贡献 UPnL)；CROSS PnL 池化可垫同币其他 CROSS 单/现货单。
- `calculateFreeFuturesMargin`(2 重载 `:759-805`)：账户级"净期货盈余"，垫现货 NSF(placeExchangeOrder)与提现 NSF(withdrawableBalance `:747-753`)。取两保守估的 **min**：①算 UPnL、CROSS 扣**初始**保证金 + ISOLATED 扣 required；②不算 UPnL、CROSS 扣**维持**保证金 + ISOLATED 扣 required。3 参重载让某 ISOLATED 仓自身 symbol 贡献 UPnL(现货单被该 symbol 期货收益垫时)。

## 6. 费用与 funding hook
- 期货成交 taker/maker 费：close(`:1394-1397`/`:1472-1475`)与 open(`:1408-1411`/`:1485-1488`)时 calculateTaker/MakerFee(size,price,spec)→currency scale，扣 accounts[quote]、进 `RiskEngine.fees`(IntLongHashMap 按币，独立于 loan/清算池)。费公式(`CoreArithmeticUtils.java:170-178`)：`isFixedFee? size×fee : ceilMulMulDiv(size,price,fee,feeScaleK)`。
- R1 pendingFee 仅预检估。
- 清算费：独立率(spec.liquidationFee)、独立池、仅 FORCE_LIQUIDATION(§4)。
- funding hook：`SETTLE_FUNDINGFEES`。R1(`:294-310`)校验 PERPETUAL+mark 后 fundingFeeProcessor.collectInput；R2(`:972-977`)applyEvent 循环 + checkPositions。**内部 P6**。
- 交割结算 `SETTLE_PNL`(DELIVERY only)全在 R1 `settlePnl`(`:695-718`)：每非空仓按 cmd.price 强平(close+refundExtraMargin+removePositionRecord+sendPnlSettlementEvent)，复用同套原语、同步在 R1。

## 7. 守恒不变式
每币每用户 `accounts[cur] = free + locked`，locked=calculateLocked(§4)。全局守恒靠桶成对等额移动：
- 开仓/发单：**不动 accounts**——openInitMarginSum/pending 纯仓内虚拟预留；locked 增、free 减。Σaccounts 不变。
- 成交开/增：openPositionMargin 虚拟改仓(不动 accounts)，除**费**(真转移：accounts[quote]−=fee 且 fees[quote]+=fee 成对)。
- 成交减/部分平：仅 openInitMarginSum/openPriceSum/openVolume 内移(除 close-fee 同成对)；PnL 推迟进剩余成本基不实现。
- 成交全平/翻仓平腿：profit 仓内虚累加**未付**；仅 isEmpty() 时 removePositionRecord 把 `profit→accounts[cur]` 一次(仓↔账户净零)。
- extraMargin 退：refundExtraMargin `extraMargin→accounts[cur]` 一次成对。
- Reject/reduce：仅动 pendingSell/BuySize(仓内)，locked 缩、free 增。
- **Rust 须复现**：每个 mutation 处，`{accounts, RiskEngine.fees, 清算费池, position.openInitMarginSum, position.extraMargin, position.profit}` 里任一减必配等额一增——保证金/PnL/extraMargin 是仓↔账户对，费是账户↔平台费池对。locked/free 是**派生**(不存储)，从 accounts+仓/loan 态经 calculateLocked 重算。

## 8. R1/R2 期货读 CoreSymbolSpecification 的字段
`type`(`:44`)、`baseScaleK/quoteScaleK`(`:49-50`，所有 scale 转换)、`takerFee/makerFee`(`:56-57`)、`liquidationFee`(`:58`)、`feeScaleK`(`:59`，isFixedFee)、`initMargin/initMarginScaleK`(`:64-65`，`calculateInitMargin(notional,lev)`(`:135-141`)=`notional/lev` 默认或 `ceilMulDiv(notional,initMargin,initMarginScaleK×lev)`)、`maintenanceMargin` tiers + `maintenanceMarginScaleK`(`:66-68`，`calculateMaintenanceMargin`(`:150-174`)分档，**canPlaceMarginOrder 不用**，仅清算/marginRatio)、`maxLeverage` tiers(`:70`，isValidLeverage 按 notional 分档 floor 查)、`loanConfig`(期货不用)。R1 NSF 只用**初始**保证金，MM 仅清算/报表用。

## 纠缠/越界区（实现时直接读 Java）
- 清算 scan/ADL/IF：`LiquidationEngine.checkPositions/advanceLiquidation/onPositionOpened/onPositionClosed`、`LiquidationService.isLiquidationOrderId/creditLiquidationFee`、`ADLCommandProcessor`、`IFCommandProcessor`——消费上面锚点的 bankruptcy/marginRatio/crossMarginBaseAllocation，但"何时触发清算"的状态机在 P6。
- funding 内部：`FundingFeeCommandProcessor.collectInput/applyEvent`(§6 锚点，P6)。
- `calculateLocked` 的 loanCollateralLocked(`:1063-1072`)读 isolatedLoans/crossLoanCollateral(P5)——P4 期货 NSF/locked math 须对此项留等价 stub(同公式内相netted)。
