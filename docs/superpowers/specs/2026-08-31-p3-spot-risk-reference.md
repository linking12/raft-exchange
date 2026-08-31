# P3 参考：RiskEngine 现货（vanilla spot）路径精确语义

> 本文件是 P3 移植的**权威业务参考**，从 Java `RiskEngine.java`（+ `RiskEngineCommandDispatcher.java`、`CoreArithmeticUtils.java`、`UserProfileService.java`、`SymbolSpecificationProvider.java`）中抽出 `CURRENCY_EXCHANGE_PAIR`（现货）路径，忽略期货/margin/loan/liquidation/funding。P3 各任务据此翻译。行号指 `exchange-core/src/main/java/exchange/core2/core/processors/RiskEngine.java`（另注文件除外）。

## 1. preProcessCommand (R1) 路由 — 行 260–385

`public boolean preProcessCommand(long seq, OrderCommand cmd)`
- 返回值：**恒 `false`**，除 `PERSIST_STATE_MATCHING`/`RECOVER_STATE_MATCHING` 返 `true`（让 disruptor 提前发布）。`false` 只表示"继续批处理"，无成败含义。
- `cmd.resultCode` 才是下游消费的结果通道。现货里只有 `PLACE_ORDER` 在 RiskEngine 设风控结果码。
- 两个前置分派门（主 switch 之前）：`cmd.command.isLoan()` → loan 分派（忽略）；`cmd.command.isNonTrading()` → `RiskEngineCommandDispatcher.dispatch`（**`ADD_USER`、`BALANCE_ADJUSTMENT`、二进制 symbol/currency add 在此**）。
- 主 switch 现货相关：
  - `MOVE_ORDER/CANCEL_ORDER/REDUCE_ORDER/ORDER_BOOK_REQUEST`：**R1 no-op**，直落撮合引擎（撮合引擎自设 resultCode）；hold 的释放在 R2。
  - `PLACE_ORDER`：若 `uidForThisHandler(cmd.uid)`，`cmd.resultCode = placeOrderRiskCheck(cmd)`；否则不动（他 shard 用户）。返回 false。
  - 其余（CLOSE_POSITION/FORCE_LIQUIDATION/…）期货/清算专用，现货不可达。

## 2. placeExchangeOrder — 现货冻结（行 399–685，现货分支 633–685）

`placeOrderRiskCheck`(399–420)：加载 UserProfile（否则 `AUTH_INVALID_USER`）+ spec（否则 `INVALID_SYMBOL`）；`cfgIgnoreRiskProcessing` 时直接 `VALID_FOR_MATCHING_ENGINE`；否则 `placeOrder`。
`placeOrder`(432)：`if (spec.type == CURRENCY_EXCHANGE_PAIR) return placeExchangeOrder(...)` —— 现货在此分叉（下方全是 margin，忽略）。

`placeExchangeOrder`(633–685)：
- **BID**：`currency = spec.quoteCurrency`
  - reserve 价校验：`FOK_BUDGET`/`IOC_BUDGET` 要求 `reserveBidPrice == price`（否则 `RISK_INVALID_RESERVE_BID_PRICE`）；普通限价要求 `reserveBidPrice >= price`。
  - `orderLockAmount`（symbol product scale = size×price 单位）：
    - BUDGET：`calculateAmountBidTakerFeeForBudget(size, cmd.price, spec)` = `budgetInSteps + fee`；`fee = size*takerFee`(固定) 或 `ceilMulDiv(budget, takerFee, feeScaleK)`(比例)。BUDGET 单的 `cmd.price` 即总预算而非单价。
    - 普通限价：`calculateAmountBidTakerFee(size, cmd.reserveBidPrice, spec)` = `size*reserveBidPrice + fee`；`fee = size*takerFee`(固定) 或 `ceilMulMulDiv(size, reserveBidPrice, takerFee, feeScaleK)`(比例)。**用 reserveBidPrice 而非 price**（保守价）。
  - 再缩放到 currency scale：`sizePriceToCurrencyScale(orderLockAmount, spec, currencySpec)`。
- **ASK**：`currency = spec.baseCurrency`
  - 守卫：`isAskPriceTooLow(cmd.price, spec)` → `RISK_ASK_PRICE_LOWER_THAN_FEE`（固定费 `price < takerFee`；比例费 `price < ceilDivide(feeScaleK, takerFee)`，`takerFee==0` 除外）。
  - `orderLockAmount = calculateAmountAsk(size) = size`（**ASK 侧不预留 fee**，fee 从卖出 quote 收益里扣）。
  - 缩放 `symbolToCurrencyScale(size, spec, currencySpec)`（currency==base）。
- 充足性（666–680）：现货 `freeFuturesMargin=0`、`loanLocked=0`；条件 → **现货不变式 `accounts[currency] - exchangeLocked[currency] - orderLockAmount >= 0`**，否则 `RISK_NSF`。
- **记账**（681）：`exchangeLocked.addToValue(currency, +orderLockAmount)`；**accounts 不动**。发 `sendLockEvent`（仅事件）。返回 `VALID_FOR_MATCHING_ENGINE`。

## 3. handlerRiskRelease (R2) 现货结算（分派 885–1023，现货分支 922–945）

`takerUp = uidForThisHandler(cmd.uid) ? getUserProfileOrAddSuspended(cmd.uid) : null`；`takerSell = cmd.action == ASK`。
```
if (spec.type == CURRENCY_EXCHANGE_PAIR) {                    // 922
  if (mte.eventType == REDUCE || mte.eventType == REJECT) {   // REJECT 总在最前；REDUCE 单个
    if (takerUp != null) handleMatcherRejectReduceEventExchange(cmd, mte, spec, takerSell, takerUp);
    mte = mte.nextEvent;
  }
  if (mte != null) {
    if (takerSell) handleMatcherEventsExchangeSell(cmd, mte, spec, takerUp);
    else            handleMatcherEventsExchangeBuy (cmd, mte, spec, takerUp);
  }
}
```

### 3a. handleMatcherRejectReduceEventExchange（1094–1125）— cancel/reject/reduce 释放冻结（单边，仅 taker/挂单主）
- `currency = takerSell ? baseCurrency : quoteCurrency`。
- ASK：`release = symbolToCurrencyScale(calculateAmountAsk(mte.size), spec, currencySpec)` = `mte.size`（释放未成交 base）。
- BID：按类型：
  - `PLACE_ORDER`+`FOK_BUDGET`：`releaseInSp = calculateAmountBidTakerFeeForBudget(mte.size, mte.price, spec)`。
  - `IOC_BUDGET` 且 `mte.nextEvent==null`（全拒无前置成交）：`releaseInSp = calculateAmountBidTakerFeeForBudget(cmd.size, cmd.price, spec)`（释放整份预算）。
  - `IOC_BUDGET` 有前置 TRADE（部分成交+剩余 REDUCE）：`releaseInSp = 0`（BUY 结算已释放整份预算，勿重复释放）。
  - 普通限价：`releaseInSp = calculateAmountBidTakerFee(mte.size, mte.bidderHoldPrice, spec)`（用当初冻结的保守价释放未成交量）。
  - `release = sizePriceToCurrencyScale(releaseInSp, spec, currencySpec)`。
- 应用：`exchangeLocked.addToValue(currency, -release)`；accounts 不动；`release>0` 发 `sendUnLockEvent`。守恒：accounts 不变 + locked 减 ⇒ free 增。

### 3b. handleMatcherEventsExchangeSell（1134–1227）— taker 卖(ASK)，maker 买
逐 TRADE 事件（`nextEvent` 链），**每个本 shard maker**（`uidForThisHandler(mte.matchedOrderUid)`）：
- `holdQuote = sizePriceToCurrencyScale(calculateAmountBidTakerFee(mte.size, mte.bidderHoldPrice, spec), spec, quoteCS)`（maker 挂 BID 时按 taker 费率冻结的原始 quote）。
- `quoteRefund = sizePriceToCurrencyScale(calculateAmountBidReleaseCorrMaker(mte.size, mte.bidderHoldPrice, mte.price, spec), spec, quoteCS)`：`tradeAmountDiff = size*(bidderHoldPrice - price)` + `feeDiff = size*(takerFee - makerFee)`(固定) 或比例 ceil 等价 —— 退还价格改善 + taker→maker 费率差。
- `makerUp.exchangeLocked[quote] -= holdQuote`；`makerUp.accounts[quote] += quoteRefund - holdQuote`（净 quote 变动 = `-(size*price + makerFee)`）。
- `baseGained = symbolToCurrencyScale(calculateAmountAsk(size)=size, spec, baseCS)`；`makerUp.accounts[base] += baseGained`（maker 收 base，base 腿无费）。
- 累加：`takerNotional += size*price; takerSize += size`（takerUp!=null）；`makerNotional/makerSize`（本 shard maker）。
- 循环后 **taker(卖方)** 一次结算：`avgTakerPrice = takerNotional/takerSize`；`takerFee = calculateTakerFee(takerSize, avgTakerPrice, spec)`；`basePaid = takerSize`；`exchangeLocked[base] -= basePaid`；`accounts[base] -= basePaid`；`accounts[quote] += sizePriceToCurrencyScale(takerNotional - takerFee, spec, quoteCS)`（taker 得 名义 − taker 费）。
- 平台费：`avgMakerPrice = makerNotional/makerSize`；`makerFee = calculateMakerFee(makerSize, avgMakerPrice, spec)`；`fees[quote] += sizePriceToCurrencyScale(takerFee + makerFee, spec, quoteCS)`（用均价避免逐笔取整 dust）。

### 3c. handleMatcherEventsExchangeBuy（1238–1343）— taker 买(BID)，maker 卖（镜像）
每 maker（本 shard）：
- `quoteGained = calculateAmountBid(size, mte.price) = size*price`。
- `basePaid = symbolToCurrencyScale(size, spec, baseCS)`；`exchangeLocked[base] -= basePaid`；`accounts[base] -= basePaid`（maker 原 ASK 冻结正好 size）。
- `fee = calculateMakerFee(size, mte.price, spec)`；`accounts[quote] += sizePriceToCurrencyScale(quoteGained - fee, spec, quoteCS)`（maker 卖方得 名义 − maker 费）。
- 累加：`takerNotional += size*price; takerHoldNotional += size*mte.bidderHoldPrice; takerSize += size`；`makerNotional/makerSize`。
- 循环后 **taker(买方)** 结算：`avgTakerPrice = takerNotional/takerSize`；`takerFee = calculateTakerFee(takerSize, avgTakerPrice, spec)`：
  - BUDGET：`heldTotal = calculateAmountBidTakerFeeForBudget(cmd.size, cmd.price, spec)`；`leftover = heldTotal - (takerNotional + takerFee)`；`takerHoldNotional = takerNotional`；`holdQuote = sizePriceToCurrencyScale(heldTotal, spec, quoteCS)`。
  - 普通：`feeHeld = calculateTakerFee(takerSize, takerHoldNotional/takerSize, spec)`；`leftover = feeHeld - takerFee`；`holdQuote = sizePriceToCurrencyScale(takerHoldNotional + feeHeld, spec, quoteCS)`。
  - `quoteRefund = sizePriceToCurrencyScale(takerHoldNotional - takerNotional + leftover, spec, quoteCS)`。
  - `exchangeLocked[quote] -= holdQuote`；`accounts[quote] += quoteRefund - holdQuote`（净 = `-(notional + takerFee)`）。
  - `accounts[base] += symbolToCurrencyScale(takerSize, spec, baseCS)`（taker 买方得 base，base 腿无费）。
- 平台费同 3b：`fees[quote] += (takerFee + makerFee(avg))`。

### 3d. Mark-price（1001–1021，现货子集）
分支后取链上首个 TRADE 的 `price` 更新 `lastPriceCache[symbol]`（`applyTradePrice`，15s 窗混合）。读侧缓存，不入资金账，需要价格 EMA 对齐时才移植。

## 4. 现货 R2 消费的 MatcherTradeEvent 字段（决定 Rust 结构扩展）
`eventType, size, price, bidderHoldPrice, matchedOrderId, matchedOrderUid, nextEvent`。
其余字段（activeOrderCompleted/matchedOrderCompleted/filled/filledNotional/matchedOrderPrice/... section）现货**不读**。
→ **现货用的最小 Rust `MatcherTradeEvent` 需在现有基础上新增：`bidder_hold_price: i64`、`matched_order_uid: i64`。**

## 5. BALANCE_ADJUSTMENT / ADD_USER / symbol-currency add（`RiskEngineCommandDispatcher.java`）
- **ADD_USER**（80–84 / 177–181）：`addEmptyUserProfile(cmd.uid)` 建空 `UserProfile(uid, ACTIVE)`；`SUCCESS` 或 `USER_MGMT_USER_ALREADY_EXISTS`。`uidForThisHandler` 门。
- **BALANCE_ADJUSTMENT**（85–89 / 183–211）：`currency = cmd.symbol`，`amountDiff = cmd.price`。
  - `amountDiff<0`（提现）先查 `withdrawableBalance = accounts[cur]-exchangeLocked[cur]`（现货口径），不足 `RISK_NSF`。
  - `UserProfileService.balanceAdjustment`（71–89）：`amountDiff<0 && accounts[cur]+amountDiff<0` → `..._NSF`；幂等 `processedTransactionIds.tryClaim(cmd.orderId, nowMs)`，重复 → `..._ALREADY_APPLIED_SAME`（NSF 路径不 claim id，可同 id 修正重试）；成功 `accounts[cur] += amountDiff`。
  - 成功后守恒计数：`adjustments[cur] -= amountDiff`（ADJUSTMENT 型）或 `suspends[cur] -= amountDiff`（SUSPEND 型）。发 deposit/withdraw 事件。
- **symbol/currency add**：无独立 `ADD_SYMBOLS` 命令，走 `BINARY_DATA_COMMAND`（isNonTrading）→ `binaryCommandsProcessor` 组帧完成回调 `handleBinaryMessage`。`BatchAddSymbolsCommand`：每个 spec，`type==CURRENCY_EXCHANGE_PAIR` → `symbolSpecificationProvider.addSymbol(spec)`（`SymbolSpecificationProvider` 拒重复 symbolId，现货额外拒重复 `(base,quote)` 对）；`BatchAddCurrenciesCommand` 播种 `CoreCurrencySpecification`（含 `currencyScaleK`）；`BatchAddAccountsCommand` 播种用户+余额（`accounts[cur]+=amount` 且 `adjustments[cur]-=amount`）。

## 6. 现货守恒不变式（P3 e2e 必测）
1. **每用户每币**：`free = accounts[cur] - exchangeLocked[cur]`。placeExchangeOrder 只增 exchangeLocked 不动 accounts；结算/撤/拒 减 exchangeLocked 且 accounts 仅按真实经济转移（名义±费）变动，绝不超过冻结额。
2. **shard 全局每币**：`Σ_users accounts[cur] + adjustments[cur] + suspends[cur]` 恒定（外部充提被 adjustments/suspends 桶等额反向抵消）；`fees[quote]` 为第三桶——每笔现货成交的 taker+maker 费从用户收益扣出、加进 `fees[quote]`。base 腿无费 → `Σ accounts[base]` 逐笔精确守恒；quote 腿守恒 modulo `fees[quote]`。
   - Rust 必须精确复现：**(a)** 每用户每币 `exchange_locked`；**(b)** 全局 `fees[quote]`。ceil/trunc 取整（`ceilMulDiv`/`ceilMulMulDiv`/`truncMulDiv`）产生的 sub-unit dust 必须落到 fees 桶，账才平。
