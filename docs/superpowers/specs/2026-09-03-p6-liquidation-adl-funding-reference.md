# P6 Reference Spec: Futures Liquidation / ADL / Funding / Internal-Transfer / Loan-Scanner / Scheduler

All paths relative to `/Users/ming/project/java/binance/raft-exchange/`. Line numbers are anchors as read 2026-09-02; re-verify if files moved.

Key Java files:
- `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/LiquidationEngine.java` (396 lines) — futures FORCE→IF→ADL state machine, targeted/scan detection
- `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/LiquidationScheduledService.java` (136 lines) — off-lane scheduler parent (shard-0, leader-gated)
- `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/LiquidationFlow.java` (32 lines) — leader-local, non-replicated per-position state
- `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/LiquidationService.java` (376 lines) — IF pool state (replicated) + orderId encoding + ADL candidate construction
- `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/LiquidationCommandSubmitter.java` (17 lines) — functional interface, leader-only submit
- `exchange-core/src/main/java/exchange/core2/core/processors/IFCommandProcessor.java` (129 lines), `ADLCommandProcessor.java` (257 lines), `FundingFeeCommandProcessor.java` (197 lines), `InternalTransferProcessor.java` (106 lines) — four `TwoStepCommandProcessor` siblings of P5's `LoanRatePricingProcessor`
- `exchange-core/src/main/java/exchange/core2/core/processors/TwoStepCommandProcessor.java` (77 lines) — shared base
- `exchange-core/src/main/java/exchange/core2/core/processors/loan/LoanLiquidationEngine.java` (394 lines, read in full)
- `exchange-core/src/main/java/exchange/core2/core/processors/RiskEngine.java` — `preProcessCommand` switch (260–385), `normalizeCmdPositionSize` (724–740), `handlerRiskRelease` (885–1023), `collectLiquidationFee` (1522–1550)
- `exchange-core/src/main/java/exchange/core2/core/processors/RiskEngineCommandDispatcher.java` — `dispatch` (77–170), `adjustMarkPrice` (436–451), `processIFDeposit`/`processIFWithdraw` (457–530ish)
- `exchange-core/src/main/java/exchange/core2/core/processors/MatchingEngineRouter.java` — `processOrder` (175–260ish)
- `exchange-core/src/main/java/exchange/core2/core/common/cmd/OrderCommandType.java` — codes, `isNonTrading()` (110–134), `isLoan()` (141–161)
- `exchange-core/src/main/java/exchange/core2/core/common/MatcherTradeEvent.java` — `matchedOrderCommandType` field (line 52)
- `exchange-core/src/main/java/exchange/core2/core/common/MatcherEventType.java` — `IF_EVENT`/`ADL_EVENT`/`FUNDING_EVENT`/`INTERNAL_TRANSFER_EVENT`
- `exchange-core/src/main/java/exchange/core2/core/common/SymbolPositionRecord.java` — `liquidationFlow`/`adlEligibility`/`pendingADLSize` fields (56–112), `calculateBankruptcyPrice` (295–312), `estimateLiquidationPrice` (341+)
- `exchange-core/src/main/java/exchange/core2/core/utils/CoreArithmeticUtils.java` — `calculateSizeToLiquidate` (201–214), `calculateDeficitAfterLiquidate` (228–240), `calculateLiquidationFee` (180–183)
- `exchange-core/src/main/java/exchange/core2/core/common/ADLUserPosition.java`, `common/FundingPaymentAndRecvNotional.java`, `common/CommonByShard.java`
- `exchange-core/src/main/java/exchange/core2/core/common/api/{ApiLiquidationOrder,ApiLiquidationScan,ApiSystemLiquidationNotify,ApiSettleFundingFees,ApiInternalTransfer,ApiIFTakeOver,ApiAutoDeleveraging}.java`
- `exchange-core/src/main/java/exchange/core2/core/ExchangeApi.java` — Api→OrderCommand translators (lines 994–1275ish, field mappings)
- `loan.md` §18.10 — explicit contrast "futures IF has automatic braking, no watermark alarm; LIF cannot copy this"

Rust-side files that already exist and are consumed/extended by P6 (no changes needed, just reuse):
- `exchange-core-rs/src/core/common/user_profile.rs` — `calculate_cross_available`, `cross_margin_base_allocation`, `try_claim_tx`, `create_positions_key`
- `exchange-core-rs/src/core/processors/user_profile_service.rs` — `get_or_add_suspended`
- `exchange-core-rs/src/core/common/symbol_position_record.rs` — `estimate_pnl`, `estimate_unrealized_profit`, `calculate_maintenance_margin` (NOT `calculate_bankruptcy_price`/`calculate_size_to_liquidate`/`calculate_deficit_after_liquidate`/`estimate_liquidation_price` — those are absent, genuinely P6-new)
- `exchange-core-rs/src/core/processors/loan_rate_pricing_processor.rs` — the P5 precedent for a lean single-shard TwoStep carrier (see §12)
- `exchange-core-rs/src/core/processors/matching_engine_router.rs:94-101` — `PlaceOrder | ClosePosition | LoanForceLiquidate | LoanCrossForceLiquidate` match arm missing `ForceLiquidation` (item 7, confirmed live bug)
- `exchange-core-rs/src/core/processors/risk_engine.rs:1938` — `markprice_adjustment` stub, explicit comment "本移植省略 liquidationEngine.checkPositions(cmd)"
- `exchange-core-rs/src/core/common/cmd/order_command_type.rs` — has `ForceLiquidation` (code 20) but **not** `InternalTransfer`, `IfTakeover`, `AutoDeleveraging`, `SettleFundingfees`(sic, Java: `SETTLE_FUNDINGFEES`), `LiquidationScan`, futures `IfDeposit`/`IfWithdraw`, `SystemLiquidationNotify`, `SuspendUser`/`ResumeUser`/`PositionModeAdjustment` — all must be added
- `exchange-core-rs/src/core/common/matcher_event_type.rs` — only `{Trade, Reject, Reduce, BinaryEvent}`, no `IfEvent`/`AdlEvent`/`FundingEvent`/`InternalTransferEvent` (see §12 decision point)

---

## 0. Big picture: 3 command-flow shapes P6 adds

1. **On-lane, event-driven, single-shard-scoped state machine** (LiquidationEngine FORCE→IF→ADL): triggered synchronously inside `RiskEngine.preProcessCommand`/`handlerRiskRelease` on the same disruptor thread that applies `MARKPRICE_ADJUSTMENT` / `LIQUIDATION_SCAN` / `SETTLE_FUNDINGFEES`. No separate thread races the replicated state.
2. **TwoStepCommandProcessor R1→ME merge→R2** (IF/ADL/Funding/InternalTransfer): identical shape to P5's `LoanRatePricingProcessor`. R1 runs per-shard inside `RiskEngine.preProcessCommand` (via `RiskEngineCommandDispatcher.dispatch` for `INTERNAL_TRANSFER`, or directly for `IF_TAKEOVER`/`AUTO_DELEVERAGING`/`SETTLE_FUNDINGFEES`); the merge stage runs once in `MatchingEngineRouter.processOrder` (`ifProcessor.process`/`adlProcessor.process`/`fundingFeeProcessor.process`/`internalTransferProcessor.process`); R2 runs per-shard inside `RiskEngine.handlerRiskRelease`.
3. **Off-lane leader-local scheduler** (`LiquidationScheduledService`): a plain `ScheduledExecutorService` on shard 0 only, gated by `isRunning()` (= leader gate, set by server start/stop), that periodically **submits** `ApiLiquidationScan` and (every Nth tick) `ApiRepriceLoanRates` through raft. It never reads replicated state directly — only emits commands that get applied deterministically on all replicas via shape (1)/(2).

`OrderCommandType.isNonTrading()` (OrderCommandType.java:110–134) now includes `INTERNAL_TRANSFER` and `MARKPRICE_ADJUSTMENT`; `IF_TAKEOVER`/`AUTO_DELEVERAGING`/`SETTLE_FUNDINGFEES`/`FORCE_LIQUIDATION`/`LIQUIDATION_SCAN` are **not** non-trading — they stay in `RiskEngine.preProcessCommand`'s main `switch` (269–384) and in `MatchingEngineRouter.processOrder`'s explicit branches (not the `isNonTrading` dispatcher).

---

## 1. LiquidationEngine — futures FORCE→IF→ADL state machine (item 1)

File: `LiquidationEngine.java`. One instance per `RiskEngine` shard, holds a stable `LoanLiquidationEngine` singleton (item 5) as a sub-delegate.

### 1.1 Detection entry: `checkPositions(cmd)` (130–148)
- Leader-gated by `isRunning()` (inherited from `LiquidationScheduledService`), no-op on followers.
- `cmd.symbol >= 0` (price-event triggered, e.g. `MARKPRICE_ADJUSTMENT`, or `SETTLE_FUNDINGFEES`): looks up `symbolToUsers[cmd.symbol]` (targeted, only that symbol's holders).
- `cmd.symbol < 0` (`LIQUIDATION_SCAN` backstop): full scan filtered by `coveredByScanSlice(cmd, uid)` — round-robin slicing, see §7.
- Always ends by delegating to `loanLiquidationEngine.checkLoans(cmd)` (item 5's entry point).

Call sites (all confirmed in RiskEngine.java):
- `LIQUIDATION_SCAN` case in `preProcessCommand` (line 324)
- `RiskEngineCommandDispatcher.adjustMarkPrice` (line 447, i.e. `MARKPRICE_ADJUSTMENT`)
- `handlerRiskRelease`'s `SETTLE_FUNDINGFEES` branch, **after** the funding R2 apply-event loop (line 977) — funding settlement can itself trigger a liquidation check on the same symbol

**Rust gap**: `risk_engine.rs:1938` `markprice_adjustment` currently has *zero* liquidation hook (explicit TODO comment). P6 must wire it; the same hook is also needed at the `SettleFundingfees` R2 call site once that command exists.

### 1.2 Isolated detection: `checkIsolated` (199–215)
```
equity = position.openInitMarginSum + estimateUnrealizedProfit(priceRecord) + position.extraMargin
maintenanceMargin = position.calculateMaintenanceMargin(spec, priceRecord)
warningThreshold = maintenanceMargin * 6 / 5   // 1.2×
if equity < maintenanceMargin:
    price = position.calculateBankruptcyPrice(spec, NO_CROSS)   // NO_CROSS = p -> 0L, isolated ignores cross callback
    sizeToLiquidate = min(openVolume, CoreArithmeticUtils.calculateSizeToLiquidate(position, spec, priceRecord))
    if sizeToLiquidate > 0: startLiquidationFlow(...)
elif equity < warningThreshold:
    sendWarningEvent(...)   // best-effort, non-replicated notify
```
`calculateSizeToLiquidate` and `calculateBankruptcyPrice`/`calculateDeficitAfterLiquidate` are **not yet ported to Rust** (verified absent from `symbol_position_record.rs`/no equivalent util file) — P4 explicitly deferred them ("清算/ADL math，P6 用" in the P4 spec's anchor list) despite documenting the Java line ranges. These are pure math functions; port `CoreArithmeticUtils.java:180-240` and `SymbolPositionRecord.java:295-312` verbatim including the integer-derivation comments (they encode non-obvious algebra — see Java docstrings, do not re-derive from scratch).

### 1.3 Cross detection: `checkCross` (218–264) + `forceCrossLiquidation` (266–286)
Groups CROSS positions by `spec.quoteCurrency`, computes per-currency `totalProfit`/`totalMaintenanceMargin` (scaled to currency scale via `CoreArithmeticUtils.sizePriceToCurrencyScale`), `equity = totalProfit + userProfile.calculateCrossAvailable(currency, ...)`. Below `warningThreshold` (1.2× MM) and above `totalMaintenanceMargin`: warning only (cheapest/most-negative-risk position picked for the alert). Below `totalMaintenanceMargin`: **liquidate positions in ascending risk-score order** (`(profit-maintenance)*100/maintenance`, most negative = most dangerous first) via `forceCrossLiquidation`, accumulating `CoreArithmeticUtils.calculateDeficitAfterLiquidate` until it covers the account-level deficit or positions run out. Uses `userProfile.crossMarginBaseAllocation(...)` (already ported, P4) to get the per-position `marginBase` callback fed to `calculateBankruptcyPrice`.

### 1.4 `startLiquidationFlow` (289–299) — idempotent FORCE submission
```
if position.liquidationFlow != null: return   // idempotency guard
orderId = LiquidationService.generateLiquidationOrderId(position)
position.liquidationFlow = new LiquidationFlow(price, size, orderId)
submit(buildForceCmd(...), null)              // ApiLiquidationOrder → OrderCommandType.FORCE_LIQUIDATION
submit(ApiSystemLiquidationNotify(alertEvent), null)   // best-effort notify, also goes through raft but mutates nothing
```
`buildForceCmd`/`buildIFCmd`/`buildADLCmd` (376–393) construct `ApiLiquidationOrder`/`ApiIFTakeOver`/`ApiAutoDeleveraging` with `action = direction==LONG ? ASK : BID` (force/liquidation is a closing trade, opposite of position direction) but IF/ADL use `direction==LONG ? BID : ASK` (counterparty **takes over** the position, i.e. same-direction-as-what-a-new-position-of-that-direction-would-need — see the code comment at RiskEngine.java:734-737 for the perspective-flip rule).

### 1.5 `advanceLiquidation(cmd, pos)` (313–345) — the state machine proper
Called from `RiskEngine.handlerRiskRelease` (line 997) only for `takerSpr != null && cmd.command ∈ {FORCE_LIQUIDATION, IF_TAKEOVER, AUTO_DELEVERAGING}`, i.e. after the taker's position record has already been located/mutated by the normal margin-settlement loop in the same call.

```
flow = pos.liquidationFlow
if flow == null:
    if cmd.command != FORCE_LIQUIDATION: log.warn, skip   // illegal — no active flow but non-FORCE cmd landed
    else: pos.liquidationFlow = new LiquidationFlow(cmd.price, cmd.size, cmd.orderId)  // leader-failover recovery path
else:
    expected = switch(cmd.command) { FORCE_LIQUIDATION -> LIQUIDATING; IF_TAKEOVER -> WAIT_IF_EXECUTION; AUTO_DELEVERAGING -> WAIT_ADL_EXECUTION }
    if flow.state != expected: log.warn "duplicate/out-of-order", skip

switch(cmd.command):
  FORCE_LIQUIDATION -> onForceApplied(cmd, pos)
  IF_TAKEOVER -> onIfTakeoverApplied(cmd, pos)
  AUTO_DELEVERAGING -> pos.liquidationFlow = null   // ADL always terminal (no further downgrade)
```

`onForceApplied` (348–361): reads `cmd.matcherEvent` (the **first** event in the R2 chain — the FORCE order is IOC, so a `REJECT` here means "insufficient counter-liquidity, partial/zero fill"). If not REJECT → fully absorbed by market, flow closes (`= null`). If REJECT → `flow.size = firstEvent.size` (REJECT event carries **remaining unfilled size**, per `MatcherTradeEvent` semantics), `flow.state = WAIT_IF_EXECUTION`, submits IF command.

`onIfTakeoverApplied` (364–374): same pattern, non-REJECT closes flow; REJECT (IF pool undersized, only partial cover — see §2) transitions to `WAIT_ADL_EXECUTION` and submits ADL.

**Critical detail**: `LiquidationFlow` (`SymbolPositionRecord.liquidationFlow`) is **leader-local, non-serialized, excluded from `stateHash()`** (confirmed: `SymbolPositionRecord.stateHash()` at line 729-731 does not list `liquidationFlow`/`adlEligibility`/`pendingADLSize`). After a leader failover, the new leader's positions all have `liquidationFlow == null`; any position still underwater gets **re-detected as a fresh bankrupt position and restarts the FORCE→IF→ADL cycle from scratch** — correctness relies entirely on `normalizeCmdPositionSize` (§1.6) clamping `cmd.size` to the *current* `openVolume` at R1 time, so a stale/duplicate command can never over-close a position. Do not try to reconstruct or persist `LiquidationFlow` state in the Rust port — replicate the "recompute from position state, tolerate the local flow object being ephemeral" design exactly.

### 1.6 `normalizeCmdPositionSize` (RiskEngine.java:724-740)
Called from `preProcessCommand` for `FORCE_LIQUIDATION`/`IF_TAKEOVER`/`AUTO_DELEVERAGING` (lines 291, 370, 378) — re-clamps `cmd.size = min(cmd.size, position.openVolume)` at R1 time (position may have shrunk between the scheduler's decision and R1 apply). Uses `userProfile.createPositionsKey(cmd.symbol, cmd.action, cmd.command)` — **note** that FORCE uses the "closer's" perspective (`action` opposite position direction) while IF/ADL use the "counterparty's" perspective (`action` same as position direction), so the same helper resolves different position records depending on which command type is passed — this is exactly the `matchedOrderCommandType`-vs-`cmd.command` distinction already flagged in the P4 carry-forward (see §9).

### 1.7 `onPositionOpened`/`onPositionClosed` (151–168) — `symbolToUsers` index
Non-replicated (not in snapshot/stateHash), maintained **deterministically by all nodes** (not leader-gated) on every position open/close apply, rebuilt from scratch in `updateProvider` on snapshot recovery (94–123, scans all `UserProfile.positions`, re-registers each futures position). `onPositionClosed` only removes the uid if no *other* position on the same symbol remains (HEDGE-mode dual-direction safety, line 157).

### 1.8 `collectLiquidationFee` (RiskEngine.java:1522-1550)
Called from `handlerRiskRelease`'s R2-finalize dispatch (line 992, only for `FORCE_LIQUIDATION`). Sums `TRADE` events' `size*price` from the (already-consumed) matcher chain, computes `CoreArithmeticUtils.calculateLiquidationFee(takerSize, avgPrice, spec)`, debits `takerUp.accounts[quoteCurrency]`, credits `liquidationService.creditLiquidationFee(symbol, notional)` (into `IFNotional.available`, see §2). No-op if `takerSize==0` (fully-rejected FORCE, nothing to fee).

---

## 2. Insurance Fund (IF) — `LiquidationService` state + `IFCommandProcessor` (item 1, sub-piece)

File: `LiquidationService.java` (376 lines), `IFCommandProcessor.java` (129 lines).

### 2.1 Replicated IF state
`LiquidationService` (per-shard singleton owned by `RiskEngine`) holds two `IntObjectHashMap`s that **are** in the snapshot/`stateHash()` (unlike `symbolToUsers`/`LiquidationFlow`):
- `notionals: symbol -> IFNotional{available, reserved}` — `available` is spendable IF balance (product/notional scale, i.e. `size*price`); `reserved` is R1-preallocated-but-not-yet-settled amount tied to an in-flight IF command.
- `positions: (direction.multiplier * symbol) -> IFPositionRecord{symbol, direction, openVolume, openPriceSum}` — the IF's own accumulated inventory from taking over bankrupt positions (key encodes sign so LONG/SHORT of same symbol don't collide).

Mutators: `creditLiquidationFee` (liquidation-fee income, §1.8), `depositToInsuranceFund`/`withdrawFromInsuranceFund` (admin `IF_DEPOSIT`/`IF_WITHDRAW`, RiskEngineCommandDispatcher.java:437-530ish, hedges against per-shard `adjustments` bucket exactly like `LOAN_IF_DEPOSIT`/`LOAN_IF_WITHDRAW` in P5), `reserveIFNotional`/`releaseReservedIFNotional` (R1/R2-finalize pair, §2.2), `acceptIFPosition` (R2 per-event, §2.2).

### 2.2 IFCommandProcessor TwoStep (mirrors `ApiIFTakeOver`)
- **R1** `collectInput`: `previewCover = liquidationService.reserveIFNotional(cmd.symbol, cmd.size, cmd.price)` — `min(available-reserved, size*price)`, i.e. **caps to what's actually coverable, never over-promises**; stored per-shard in `cmd.ifPreviewCoverByShard[shardId]`.
- **merge** `buildMatcherEvents`: sums `reservedByShard[]`, computes `totalCoverSize = Σ (reservedByShard[i] / price)` (floor division **per shard before summing** — fragmented notional cannot be pooled across shards, comment at line 50-52). If `totalCoverSize < remainingSize` → REJECT (whole thing rejected, not partial — IF takeover is all-or-nothing per command). Else emits one `IF_EVENT` per shard with nonzero reserve, `size = min(maxSizeByNotional, remainingSize)`, `matchedOrderUid = shardId` (routing key, not a real uid).
- **R2** `applyEvent`: only the shard matching `ev.matchedOrderUid` acts — `liquidationService.acceptIFPosition(symbol, direction, ev.size, cmd.price)`.
- **R2 finalize**: closes the taker's position via `closeCurrentPositionFutures` (same pattern as ADL, §3), then **always** `releaseReservedIFNotional(cmd.symbol, previewCover)` — symmetric with R1's reserve, regardless of REJECT/success (an all-reject command still needs its per-shard reserve released).

### 2.3 Natural braking, no watermark alarm needed (loan.md §18.10)
Unlike loan LIF (P5, allowed to go negative, needs external watermark monitoring), futures IF **self-limits** via `min(available, needed)` in `reserveIFNotional` — it can never go negative, and undersized coverage automatically degrades to ADL via the REJECT→`WAIT_ADL_EXECUTION` transition (§1.5). Only `log.warn` on transition, no in-engine alerting infra. **Do not port a watermark-alarm mechanism for futures IF** — it's an intentional asymmetry with LIF, not an oversight.

---

## 3. ADL — auto-deleveraging (item 2)

File: `ADLCommandProcessor.java` (257 lines), `common/ADLUserPosition.java` (42 lines).

### 3.1 R1 `collectInput` (52–100)
```
profitablePositions = liquidationService.computeProfitablePositionsBySymbol()[cmd.symbol]
    .select(pos -> pos.openVolume>0 && pos.openVolume>pos.pendingADLSize
                 && !pos.direction.isSameAsAction(cmd.action)   // opposite side only
                 && LiquidationService.unrealizedPnl(pos, bankruptcyPrice) > 0)
    .sortByLong(riskScore).reverse()   // richest/most-leveraged/most-eligible first
for pos in profitablePositions (until remaining==0):
    canTake = min(pos.openVolume - pos.pendingADLSize, remaining)
    pos.pendingADLSize += canTake        // R1 pre-reservation, symmetric release in R2 finalize
    append ADLUserPosition{uid, symbol, direction, volume=canTake, score} to per-shard linked list
cmd.adlUserPositionsByShard[shardId] = head
```
`computeProfitablePositionsBySymbol` (`LiquidationService.java:234-321`) is **recomputed from scratch every call**, not cached — explicit comment: a leader-only cache would produce different results on leader vs. follower during replay, breaking determinism. It walks all `UserProfile`s: ISOLATED positions with `unrealized profit > 0` are eligible directly (`adlEligibility` defaults to 100 for ISOLATED); CROSS positions require **account-level gating** first (`totalProfit>0 && equity >= 1.2×totalMaintenance` for that currency), then a **factor** `clamp((equity-totalMaintenance)*100/totalMaintenance, 0, 100)` is written into each eligible position's `adlEligibility` (defaults to 0 for CROSS — i.e. CROSS positions are ADL-ineligible by default unless the account clears the safety gate).

`riskScore` (`LiquidationService.java:207-213`) = `saturatingMultiply(saturatingMultiply(actualLeverage, unrealizedPnl), adlEligibility)` where `actualLeverage = openPriceSum / openInitMarginSum`. All multiplications use `saturatingMultiply` (clamp to `Long.MIN/MAX` on overflow instead of wrapping) — **overflow-wrap here would flip sign and invert the ranking**, this is load-bearing correctness, not just defensive coding.

### 3.2 merge `buildMatcherEvents` (102–165)
Cross-shard **best-of-N merge by score**: clones the per-shard head-pointer array into `cursors[]` (must clone — reusing the original array would corrupt both R2's release-walk and the pooled-object recycling walk, per the code's own extensive comment at 111-115), repeatedly picks the globally-best-score candidate across all shard cursors, consumes `min(best.volume, remaining)`, advances that shard's cursor only when its node is fully consumed. Produces one `ADL_EVENT` per consumed candidate; `cmd.size` is rewritten to the **actually consumed** total (not the original request) before returning, since it's used downstream (R2) to close the *taker's own* position by the true executed size.

### 3.3 R2 `applyEvent` (167–213) — per-event counterparty settlement
Looks up counterparty `UserProfile`/`SymbolPositionRecord` (both may have vanished between R1 selection and R2 apply — handled as best-effort skip with a `[CASCADE-DEBUG]` log, **not** an error; `pendingADLSize` correction happens uniformly in finalize regardless), calls `pos.closeCurrentPositionFutures(...)`, sends fund events, and — if the counterparty position is now fully empty — refunds `extraMargin`, removes the position record, and settles any residual `profit` back to `accounts`. This close-and-cleanup sequence (`refundExtraMargin` → `removePositionRecord` → conditional `sendPnlSettlementEvent`) is the **same pattern used 4 times** across IF/ADL/finalize-for-taker — worth factoring into one shared helper in Rust rather than copy-pasting.

### 3.4 R2 `finalizeForCommand` (215–255) — taker close + symmetric pendingADLSize release
Closes the taker's own position (same close-and-cleanup pattern), then walks `cmd.adlUserPositionsByShard[shardId]` **again** (the original R1 head, not the merge-mutated cursor clone) to release `pos.pendingADLSize -= head.volume` for every candidate this shard proposed — symmetric with R1's `+=`, regardless of how much was actually consumed by the merge (a candidate proposed-but-not-picked still needs its reservation released).

**`pendingADLSize`/`adlEligibility` are non-replicated** (excluded from `SymbolPositionRecord.stateHash()`), same category as `liquidationFlow` — but unlike `liquidationFlow` they're mutated in R1/R2 which run on **all nodes** (not leader-gated), so they stay consistent across replicas via deterministic replay, they just aren't part of the raft-verified state hash (a design choice, not a correctness gap — these are pure same-command scratch counters, always net to the same value after finalize on every node).

---

## 4. Funding fee — `FundingFeeCommandProcessor` (item 3)

File: `FundingFeeCommandProcessor.java` (197 lines). Sibling of P5's `LoanRatePricingProcessor`; per the task brief, read together if the general TwoStep shape needs a refresher.

Trigger: **externally submitted** `ApiSettleFundingFees` (an operator/oracle pushes it via `ExchangeApi`/gRPC — confirmed via `grep`, referenced from `raft-exchange-server/.../ApiCommandConverters.java` and integration tests, **not** from `LiquidationScheduledService`). Unlike `LIQUIDATION_SCAN`/`REPRICE_LOAN_RATES`, there is no in-engine cadence for funding — the funding-rate computation/cadence lives outside `exchange-core` entirely; the engine only *settles* a rate it's given.

Field mapping (`ExchangeApi.java:1083-1093`): `cmd.action` = BID(long-pays-short)/ASK(short-pays-long), `cmd.price = fundingRate`, `cmd.size = rateScaleK` (fixed-point rate + its scale, both required — `truncMulDiv` divides by `cmd.size`).

### 4.1 R1 `collectInput` (34–68)
Precondition gate: `cmd.size<=0` → `RISK_INVALID_AMOUNT`; no `LastPriceCacheRecord` for symbol → `RISK_MARKPRICE_NOT_AVAILABLE`. Walks all `ACTIVE` users' positions on `symbol`:
```
notional = openVolume * markPrice
if position.direction == cmd.action:   // payer side
    fee = truncMulDiv(notional, cmd.price, cmd.size)
    if fee > 0: shardData.payerAmounts[uid] = fee
else:                                   // receiver side
    shardData.receiverNotionals[uid] = notional   // raw notional, not fee — scaled at merge time
```

### 4.2 merge `buildMatcherEvents` (70–126) — pro-rata cross-shard distribution
```
totalPayAmount = Σ_shard payerAmounts.sum()
totalRecvNotional = Σ_shard receiverNotionals.sum()
if either == 0: no event (nothing to settle)
per shard: amount = truncMulDiv(totalPayAmount, shard.receiverNotionals.sum(), totalRecvNotional)
remainder = totalPayAmount - Σ amounts   // truncation dust
distribute remainder 1-unit-at-a-time to shards with nonempty receiverNotionals (deterministic, shard-id order)
emit one FUNDING_EVENT per shard with (amount>0 || hasPayers), ev.price=shardRecvAmount, ev.matchedOrderUid=shardId
```
This is a **two-level pro-rata split**: total payer pool → per-shard receiver share (by notional) → (in R2) per-shard receiver share → per-user share (by notional again). Both levels use the identical truncate-then-distribute-remainder-by-one pattern (also seen in P5's Cross takeover and R2 below) — a reusable primitive worth extracting once in the Rust port.

### 4.3 R2 `applyEvent`/`settleFundingFee` (128–195)
Only the shard matching `ev.matchedOrderUid` processes; pays out `payerAmounts` first (unconditional debit, no truncation needed — already exact per-user), then re-derives `receiverFees` from `receiverNotionals` via the **same** `truncMulDiv` + 1-unit-remainder-distribution pattern against `shardRecvAmount` (the shard's already-allocated slice). `settleFundingFee` looks up the position by `cmd.symbol`/`-cmd.symbol` (HEDGE dual-direction support), applies `signedFee` to `position.profit` if a live matching-direction position exists, else scales into `accounts[quoteCurrency]` directly (position already closed between R1 and R2 — funding fee follows the money, not the ghost position).

### 4.4 Conservation
Funding is **zero-sum by construction**: `Σ payerAmounts` (fee-scale) is redistributed as `Σ receiverFees` with any truncation dust absorbed by the deterministic 1-unit distribution — no `adjustments`/`fees` bucket touched, no new currency created. This is the simplest of the four TwoStep processors conservation-wise (contrast with IF/ADL, which move money into/out of the `IFNotional`/counterparty-account buckets).

---

## 5. INTERNAL_TRANSFER (item 4)

File: `InternalTransferProcessor.java` (106 lines), `common/api/ApiInternalTransfer.java` (30 lines). **Already dispatched in `RiskEngineCommandDispatcher.dispatch` case `INTERNAL_TRANSFER`** (line 90-93) — it's `isNonTrading()`, routed exactly like `MARKPRICE_ADJUSTMENT`/`IF_DEPOSIT`, not a bespoke path.

Field mapping (`ExchangeApi.java:1216-1226`): `cmd.uid = fromUid`, `cmd.size = toUid` (**overloaded** — size carries a uid, not an amount), `cmd.symbol = currency`, `cmd.price = amount`, `cmd.orderId = transactionId`.

### 5.1 R1 `collectInput` (37–69)
Runs only on the `from`-shard (`riskEngine.uidForThisHandler(cmd.uid)` — note this uses `cmd.uid`, the *from* side; the *to*-side shard does nothing at R1). Validations in order: `from==to` → `INTERNAL_TRANSFER_INVALID_SELF`; `amount<=0` → `RISK_INVALID_AMOUNT`; `from` profile missing → `AUTH_INVALID_USER`; **NSF check reuses `withdrawableBalance`** (`RiskEngine.java:747-753`, same "same accounting as withdraw" formula P5 already ported for loan withdraw) — i.e. internal transfer respects loan collateral locks and futures margin locks identically to a real withdrawal; idempotency via `from.processedTransactionIds.tryClaim(cmd.orderId, cmd.timestamp)` (same primitive as loan commands, P5-ported, **not** claim-and-release — a later failure doesn't un-claim, matching the loan-command idempotency semantics documented in P5 §2). On success: debits `from.accounts[currency] -= amount` immediately (R1, not R2 — the debit is final and doesn't wait for the ME merge stage, since there's nothing to merge on the debit side).

### 5.2 merge `buildMatcherEvents` (72–81)
Trivial 1:1 passthrough — no cross-shard aggregation needed (unlike ADL/IF/Funding which genuinely merge multiple shards' proposals). Just packages `(toUid, currency, amount)` into one `INTERNAL_TRANSFER_EVENT`.

### 5.3 R2 `applyEvent` (84–98)
Runs only on the `to`-shard (`riskEngine.uidForThisHandler(toUid)`). `getUserProfileOrAddSuspended(toUid)` — **auto-creates a SUSPENDED profile if the target uid has never been seen** (transfers to a not-yet-onboarded/deleted account still land, funds aren't lost; suspension prevents them from being used until the account is properly resumed). Credits `to.accounts[currency] += amount`.

### 5.4 Conservation
Trivially neutral: `from -= amount; to += amount`, no `adjustments`/`fees` touched, doesn't even need the `1-unit-remainder-distribution` trick since it's always a single (from,to) pair, no cross-shard splitting of one logical amount.

**This is by far the simplest of the 7 items** — good candidate to implement/test first in P6 since it validates the cross-shard TwoStep wiring pattern with minimal domain complexity, before tackling ADL/IF's multi-way merge logic.

---

## 6. Loan liquidation scanner — `LoanLiquidationEngine` full detail (item 5)

File: `LoanLiquidationEngine.java` (394 lines, read in full — P5 §5.1 only summarized this; this section supersedes it with full detail). P5 already ported the **handlers** consumed by this scanner (`LoanCommandDispatcher.handleLoanForceLiquidate`/`handleLoanCrossForceLiquidate` + `postProcessLoanForceLiquidate`/`postProcessLoanCrossForceLiquidate`, P5 §2.5/§2.10). P6 ports the **trigger** (this file) that decides *when* to submit those commands.

### 6.1 Ownership and lifecycle
Constructed once inside `LiquidationEngine`'s constructor (`new LoanLiquidationEngine(eventsHelper, this::getCommandSubmitter)`) — a stable singleton, not re-created per check. `updateProvider` is forwarded from `LiquidationEngine.updateProvider` (called on snapshot init/recovery) and rebuilds both non-replicated indices from scratch (70–88):
```
isolatedLoanSymbolToUsers.clear(); crossLoanCurrencyToUsers.clear()
for up in all UserProfiles:
    for loan in up.isolatedLoans where !loan.isEmpty(): onIsolatedLoanOpened(up.uid, loan.symbolId)
    syncCrossExposure(up)
```

### 6.2 Detection entry `checkLoans(cmd)` (98–126)
Same targeted-vs-scan shape as `LiquidationEngine.checkPositions`:
- `cmd.symbol >= 0`: unions three index lookups — `isolatedLoanSymbolToUsers[spec.symbolId]` (isolated loans directly on this pair), `crossLoanCurrencyToUsers[spec.baseCurrency]`, `crossLoanCurrencyToUsers[spec.quoteCurrency]` (cross exposure to either leg of the pair whose price just moved) — into one dedup'd uid set, then `checkUser` each.
- `cmd.symbol < 0` (LIQUIDATION_SCAN): full scan filtered by the **same** `coveredByScanSlice` helper used by futures (shared static method on `LiquidationScheduledService`, item 6).

`checkUser` (128–134) runs both `checkIsolated` (per-loan) and `checkCross` (per-account) unconditionally for every isolated/cross loan the user holds.

### 6.3 `checkIsolated` (136–186) — per-loan trigger
```
if loan.isEmpty() or no spec or no priceRecord or markPrice==0: skip
collateralValue = collateralValueInQuoteCurrency(loan.collateralAmount, spec, markPrice, baseSpec, loanCurrencySpec)
if collateralValue <= 0: return   // avoid div-by-zero on bankruptcy price
realDebt = outstandingPrincipal + calculateDisplayInterest(loan, ts)   // pending-interest-inclusive
termExpired = (rateMode==LOCKED) && maxTermDays>0 && (ts - openedAtTs) > maxTermDays*MS_PER_DAY   // FLOATING has no term
if termExpired || realDebt*BPS_SCALE >= collateralValue*liquidationLtvBps:
    sellSizeLots = collateralAmountToLots(collateralAmount, spec, baseSpec)
    if sellSizeLots <= 0: log.warn "sub-lot dust", skip this round (absorbed later at LIF-takeover time)
    orderId = forceSellOrderId(ORDERID_SUBTYPE_ISOLATED, uid, loanId, ts)
    limitPrice = ceilMulDiv(markPrice, realDebt, collateralValue)   // = bankruptcyPrice, "floor price"
    submit ApiLoanForceLiquidate{price=limitPrice, size=sellSizeLots, action=ASK, orderType=IOC}
    return
elif marginCallLtvBps>0 && realDebt*BPS_SCALE >= collateralValue*marginCallLtvBps:
    sendMarginCall(...)   // best-effort, non-replicated
```
`bankruptcyPrice` helper (383–393): `ceilMulDiv(markPrice, realDebt, collateralValue)` — a **floor price** (IOC will typically fill above it; overshoot is refunded to the borrower by `applyDebtPayment`'s cap, per P5 §2.5), ceiling-rounded to guarantee the floor is never below true break-even.

### 6.4 `checkCross` (195–247) — account-level partial deleverage, converges over multiple ticks
```
ltvBps = calculateCrossAccountLtvBps(up, ts, ..., numeraireCurrency)   // weighted (P5 §3.2)
if ltvBps >= globalConfig.crossLiquidationLtvBps:
    sellingCurrency = pickCrossCollateralToSell(up)      // §6.5
    if sellingCurrency==0: log.warn abort, return
    targetLoan = pickCrossLoanToRepay(up, sellingCurrency)  // §6.5
    if targetLoan==null: log.warn abort, return
    spec = findSpotSymbol(sellingCurrency, targetLoan.loanCurrency)   // guaranteed non-null by pick filters
    rawLtvBps = calculateCrossRawLtvBps(...)             // unweighted, for pricing (P5 §3.2)
    pricingLtvBps = rawLtvBps>0 ? rawLtvBps : ltvBps      // fallback to weighted if raw unevaluable, "conservative but never abandon"
    limitPrice = ceilMulDiv(markPrice, pricingLtvBps, BPS_SCALE)
    sellSize = calculateCrossSellSize(targetLoan, spec, limitPrice, availableCollateral, ts, ...)  // §6.6
    if sellSize<=0: log.warn abort, return
    submit ApiLoanCrossForceLiquidate{targetLoanId, price=limitPrice, size=sellSize, action=ASK, IOC}
elif ltvBps >= globalConfig.crossMarginCallLtvBps:
    sendMarginCall(uid, loanId=0, mode=CROSS, loanCurrency=0, ...)   // no single-loan attribution
```
**One (sellingCurrency, targetLoan) pair per tick, converging over multiple ticks** — the docstring (188-194) is explicit about *why* the "ready spot market exists" filter must live **inside** the pick functions rather than as a post-hoc check: picking the theoretically-optimal pair first and only then discovering no market exists for it would cause the same (unsellable) pair to be re-picked every tick forever (an infinite stall). Filtering inside the picks means the optimal pair is always among those with a real market.

### 6.5 Pick functions (305–356) — determinism-critical, cross-replica tie-break order matters
```
pickCrossCollateralToSell(up):  // weight DESC → amount DESC → currency ASC
    for currency in up.crossLoanCollateral where amount>0:
        weight = collateralWeightForBase(currency)
        if weight<=0: skip   // this currency can't be Cross collateral at all
        if up.crossLoans.noneSatisfy(l -> l.outstandingPrincipal>0 && hasReadySpotMarket(currency, l.loanCurrency)):
            skip   // selling this currency can't repay ANY debt (no market) — must filter here, not after
        keep best by (weight desc, amount desc, currency asc)
    return bestCurrency (0 = none found)

pickCrossLoanToRepay(up, sellingCurrency):  // rate DESC → principal DESC → loanId ASC
    for loan in up.crossLoans where outstandingPrincipal>0:
        if !hasReadySpotMarket(sellingCurrency, loan.loanCurrency): skip
        keep best by (rateBps desc, outstandingPrincipal desc, loanId asc)
    return best (null = none)

hasReadySpotMarket(a, b): findSpotSymbol(a,b) != null && lastPriceCache[spec.symbolId].markPrice > 0
```

### 6.6 `calculateCrossSellSize` (361–371)
```
realDebt = targetLoan.outstandingPrincipal + calculateDisplayInterest(targetLoan, now)
if realDebt<=0 || limitPrice<=0: return 0
neededLots = quoteAmountToLots(realDebt, limitPrice, spec, loanCurrencySpec)   // sized at the BANKRUPTCY price, not markPrice
availableLots = collateralAmountToLots(available, spec, sellingCurrencySpec)
return min(availableLots, neededLots)
```
Comment (359): sizing at `limitPrice` (the discounted bankruptcy floor) rather than `markPrice` is deliberate — if you size at market price but the actual sale clears at the discounted floor, you'd systematically undersize and never fully cover the debt in one pass (hence "converges over multiple ticks" rather than "always fully resolves in one tick" — this is expected, not a bug).

### 6.7 Non-replicated index maintenance (253–299)
`onIsolatedLoanOpened`/`onIsolatedLoanClosed`/`syncCrossExposure` are called **synchronously from `LoanCommandDispatcher`** on every mutating loan command (all nodes, deterministically, not leader-gated — unlike `LiquidationFlow`). `onIsolatedLoanClosed` only removes the uid from the symbol bucket if the user holds no *other* non-empty isolated loan on that symbol (multi-loan-per-symbol-per-user support). `syncCrossExposure` (274–299) has a documented **asymmetric tolerance**: partial currency-exit (user closes exposure to *one* currency but keeps others) is allowed to go stale in the index (harmless over-trigger — `checkUser` will just find nothing to liquidate on the next scan for that stale entry, self-healing), while full-account-exit (zero collateral AND zero loans across all currencies) triggers an exact sweep-removal from every currency bucket. This asymmetry is intentional (full rebuild cost avoided for the common partial case) — replicate exactly, don't "fix" it into a fully-precise index.

**P5 left the seam**: `post_process_loan_cross_force_liquidate` in the Rust port has an explicit comment (~loan_command_dispatcher.rs:1091) where Java calls `syncCrossExposure(takerUp)` — P6 must add that call when the scanner + indices land.

---

## 7. Scheduled/off-lane trigger infrastructure (item 6)

File: `LiquidationScheduledService.java` (136 lines, abstract parent of `LiquidationEngine`).

### 7.1 Shape
Plain `ScheduledExecutorService.scheduleWithFixedDelay`, constructed with `(delay, unit, threadFactory, shardId, scanSliceCount, repriceEveryNTicks)`. `LiquidationEngine`'s constructor supplies these from system properties: `raftexchange.liquidation.interval` (default 2s), `raftexchange.liquidation.scanSlices` (default 10), `raftexchange.loanReprice.everyNTicks` (default 30).

`start()`/`stop()` toggle `AtomicBoolean running` — this **same flag doubles as the leader gate** consumed by `isRunning()` in `LiquidationEngine.checkPositions`/`advanceLiquidation` and `LoanLiquidationEngine` (transitively, via the `checkLoans` call chain not itself gating but relying on the caller's gate). The server layer calls `start()`/`stop()` on raft leadership transitions — **this wiring is outside `exchange-core`** (server-side raft integration), out of scope for this reference, but the `isRunning()` boolean contract itself must be preserved by the Rust port's equivalent.

### 7.2 `runOneIteration` (57–67) — shard-0-only tick
```
if shardId != 0: return   // only shard 0 runs the scheduler; commands it submits reach all shards via raft replication
slice = scanTick mod scanSliceCount
submit ApiLiquidationScan{scanSlice=slice, sliceCount=scanSliceCount}
if scanTick % repriceEveryNTicks == 0: submit ApiRepriceLoanRates{}
scanTick++
```
`submit` delegates to the injected `LiquidationCommandSubmitter` (a functional interface, no-op if unset) — this is the seam where the server layer plugs in raft submission. **`scanTick`/`scanSlice` themselves are leader-local scheduler state**, not replicated — but the *decision of which slice to scan* is baked into the submitted `ApiLiquidationScan.scanSlice` field which **does** travel through raft (§7.3), so all replicas see the identical slice choice deterministically, even though the ticking clock that produced it is leader-only and non-deterministic in timing.

### 7.3 `coveredByScanSlice(cmd, uid)` (129–134) — static, shared by futures AND loan scanners
```
if cmd.command != LIQUIDATION_SCAN || cmd.size <= 0: return true   // non-scan commands (targeted) always "covered"; sliceCount<=0 = full scan (legacy/replay)
return floorMod(uid, cmd.size) == cmd.uid   // cmd.size = sliceCount, cmd.uid = scanSlice (per §1's ApiLiquidationScan.builder mapping: cmd.uid=api.scanSlice, cmd.size=api.sliceCount)
```
Used identically by `LiquidationEngine.checkPositions` (line 141) and `LoanLiquidationEngine.checkLoans` (line 121) — **one shared slicing decision**, both futures-position scanning and loan scanning ride the same `LIQUIDATION_SCAN` tick and the same per-tick slice, they just apply different `checkUser` logic to the users that fall in-slice. This is why a single `ApiLiquidationScan` command triggers both `LiquidationEngine.checkPositions` (which itself delegates to `loanLiquidationEngine.checkLoans` at its tail, §1.1) — there is only **one** scheduled scan command type, not two.

### 7.4 `ApiLiquidationScan` wire mapping (`ExchangeApi.java:1116-1124`)
`cmd.symbol = -1` (always full-scan mode, never targeted — targeted checks only ever originate from `MARKPRICE_ADJUSTMENT`/`SETTLE_FUNDINGFEES`, never from the scheduler), `cmd.uid = api.scanSlice`, `cmd.size = api.sliceCount`. Result code: `SUCCESS` on shard 0 only (line 325-327 of RiskEngine.java preProcessCommand).

### 7.5 `ApiRepriceLoanRates` — already fully covered in P5 §4.2; unchanged by P6, just re-confirming the scheduler is the sole trigger source (no other caller found).

**Rust status**: zero scheduler infrastructure exists yet (`grep` for `ScheduledService`/`scan_slice`/`coveredByScanSlice` in `exchange-core-rs/src` returns nothing) — this whole section is greenfield. The Rust equivalent should probably keep the exact same three-piece split: (a) a pure `covered_by_scan_slice(cmd, uid) -> bool` free function shared by both scanners, (b) an `is_running`-gated leader-check boolean threaded through both `LiquidationEngine`/`LoanLiquidationEngine` entry points, (c) a thin scheduler struct that's largely a server-integration concern and may reasonably be stubbed/simplified in the pure-library Rust crate (the `ScheduledExecutorService`/`ThreadFactory` machinery is JVM-specific plumbing, not domain logic) — but the `runOneIteration` tick-counting logic (`scanTick % scanSliceCount`, `scanTick % repriceEveryNTicks`) is domain logic and should be ported faithfully even if the surrounding thread-scheduling harness is adapted to whatever async/timer primitive the Rust server layer uses.

---

## 8. ForceLiquidation router wiring (item 7)

**Confirmed live bug, exact fix required.** `exchange-core-rs/src/core/processors/matching_engine_router.rs:94-101`:
```rust
let rc = match cmd.command {
    OrderCommandType::MoveOrder => book.move_order(cmd),
    OrderCommandType::CancelOrder => book.cancel_order(cmd),
    OrderCommandType::ReduceOrder => book.reduce_order(cmd),
    OrderCommandType::PlaceOrder
    | OrderCommandType::ClosePosition
    | OrderCommandType::LoanForceLiquidate
    | OrderCommandType::LoanCrossForceLiquidate => { /* new-order path */ }
    OrderCommandType::OrderBookRequest => { ... }
    _ => CommandResultCode::MatchingUnsupportedCommand,   // ForceLiquidation falls here!
};
```
`OrderCommandType::ForceLiquidation` (Rust enum variant already exists, code 20, confirmed in `order_command_type.rs`) is **not** in that match arm, so it silently falls to the wildcard `_ => MatchingUnsupportedCommand`, **overwriting R1's `VALID_FOR_MATCHING_ENGINE`** with a failure code — exactly the class of bug already fixed once for `ClosePosition` in P4 Task 7 (per the Rust test comment at `matching_engine_router.rs:271-273`). The fix is mechanically identical: add `| OrderCommandType::ForceLiquidation` to that match arm. Java's equivalent (`MatchingEngineRouter.java:206-214`) already includes `FORCE_LIQUIDATION` in the analogous wildcard-order-matching branch alongside `PLACE_ORDER`/`CLOSE_POSITION`/loan force-liquidate types — the Java code was never wrong here, only the Rust port has this gap (a straight omission, not a design divergence).

Also verify: `OrderCommandType::ForceLiquidation` correctly participates in `create_positions_key`'s flip logic (already ported in P4, confirmed via `user_profile.rs:77` and tests — no work needed there, just don't regress it while touching this file).

---

## 9. `MatcherTradeEvent.matched_order_command_type` gap (cross-cutting, feeds items 1–3)

Java `MatcherTradeEvent.matchedOrderCommandType` (field, line 52) is written once at match time (`OrderBookEventsHelper.java:75`, `event.matchedOrderCommandType = matchingOrder.getCommand()` — the **maker's own** original command type, captured from the resting order) and consumed exactly once, at `RiskEngine.java:1450`: `makerUp.createPositionsKey(spec.symbolId, makerAction, mte.matchedOrderCommandType)`. This is **not** `cmd.command` (the taker's command) — it's specifically needed because `createPositionsKey`'s HEDGE-mode key-flip logic depends on whether the **resting maker order** was itself a `ForceLiquidation`/`ClosePosition` (needs flip) vs a normal `PlaceOrder` (no flip), which can differ from the taker's command type in any FORCE/IF/ADL scenario where a liquidation order matches against a normal resting limit order.

Rust confirmed missing (`grep` shows `create_positions_key` calls all pass `cmd.command` as a placeholder — see `risk_engine.rs:1471-1513`, with an explicit comment acknowledging this is a stand-in that "happens to be correct for ONEWAY" but wrong once HEDGE mode + a maker-side ForceLiquidation/ClosePosition order is reachable). P6 must:
1. Add a `matched_order_command_type: OrderCommandType` field to Rust's `MatcherTradeEvent`.
2. Populate it at match time in the order book (wherever the maker's resting-order metadata is captured into the trade event — Rust equivalent of `OrderBookEventsHelper.java:75`).
3. Switch the maker-side `create_positions_key` call (currently at `risk_engine.rs:1513`, using `cmd.command` per the acknowledged placeholder) to use `mte.matched_order_command_type` instead.

This becomes reachable/necessary once ForceLiquidation (item 7) is wired into the router and can actually match against resting orders in HEDGE mode — i.e. it's a prerequisite that surfaces specifically because of item 7, not an independent nice-to-have.

---

## 10. Conservation buckets introduced by P6

| Bucket | Owner | Behavior |
|---|---|---|
| `IFNotional{available, reserved}` per symbol | `LiquidationService` (replicated) | `available` funded by `creditLiquidationFee` (§1.8) + admin `IF_DEPOSIT`/hedged against `adjustments`; spent by `acceptIFPosition` (§2.2); self-limiting (`min`, never negative) |
| `IFPositionRecord{openVolume, openPriceSum}` per (symbol,direction) | `LiquidationService` (replicated) | IF's own inventory from takeovers — a real position the IF must eventually unwind (out of scope how, just tracked here) |
| `pendingADLSize` per position | `SymbolPositionRecord` (non-replicated, R1↔R2-finalize symmetric within one command) | Prevents double-ADL of the same volume within one command's multi-shard candidate selection |
| Funding payer/receiver flows | Transient per-command (`FundingPaymentAndRecvNotional`, not persisted) | Zero-sum, no bucket touched — see §4.4 |
| Internal transfer | No new bucket — direct `accounts[from] -= x; accounts[to] += x` | Zero-sum, see §5.4 |
| Loan LIF interaction with futures IF | **None** — completely separate pools (LoanService's `loanInsuranceFund` vs. `LiquidationService`'s `IFNotional`), never cross-subsidize | Confirms P5/P6 boundary: loan liquidation proceeds never touch futures IF and vice versa |

**Full conservation identity** (extending P4/P5's formula): P4's verified identity was `Σ_users accounts + adjustments + fees + Σ_positions(estimate_pnl(mark) + extra_margin) == 0`, extended by P5 with loan buckets. P6 must extend this to include:
```
+ Σ_symbols (IFNotional.available)     // IF's own claim on the system, funded by fees + admin deposits (hedged in adjustments)
+ Σ_symbols,direction (estimate_pnl-equivalent of IFPositionRecord, at current mark)   // IF's own inventory PnL
```
The liquidation fee debit (`collectLiquidationFee`, §1.8) removes from `takerUp.accounts` and adds to `IFNotional.available` — conserved within this extended identity but **not** within P4/P5's narrower one (which predate IF's existence in the Rust port). Any P6 conservation proptest must account for the IF terms or it will show spurious drift whenever a FORCE_LIQUIDATION with a nonzero fee fires.

---

## 11. Determinism requirements (cross-cutting)

1. **ADL merge is score-based cross-shard best-of-N, not per-shard-independent** — the merge stage must run once (not per-shard) and produce identical event chains on every replica; Rust's current single-shard architecture (§12) trivializes the "cross-shard" part but the *tie-break-free deterministic ordering* of the score comparison itself (§3.2: ties currently broken only by insertion order within the sorted list, no explicit secondary key beyond `riskScore`) should be checked — if the Rust port ever needs bit-exact parity with Java's `sortThisByLong(...).reverseThis()` (a stable sort), preserve stability, don't introduce an unstable sort that could reorder equal-score candidates differently.
2. **Funding fee's remainder distribution** (§4.2/§4.3) is order-sensitive: `LongIterator` over `receiverNotionals.keySet()` in Java (Eclipse Collections hash-map iteration order — **not guaranteed stable across insertions** in general, but deterministic *given* the exact same insert sequence on every replica, since R1 fills the map identically everywhere). Rust should use an explicitly ordered container (`BTreeMap`, following the P5 `LoanRatePricingProcessor` precedent) for the remainder-distribution loop rather than relying on hash-map iteration order — safer and matches the project's established "all BTreeMap, no HashMap" determinism discipline.
3. **Cross loan-liquidation picks** (§6.5) have explicit, load-bearing tie-break chains (weight DESC → amount DESC → currency ASC; rate DESC → principal DESC → loanId ASC) — already correctly implemented as total orders in Java; port verbatim, do not "simplify" by dropping a tie-break level (two loans/currencies with identical primary+secondary keys are possible and the tertiary key is what keeps the result identical across replicas).
4. **Scan slicing** (`coveredByScanSlice`, §7.3): the slice number travels *through the replicated command* (`cmd.uid`/`cmd.size`), not computed independently by each replica from wall-clock time — this is the actual determinism mechanism, worth calling out explicitly since it'd be an easy mistake to instead have each replica compute "what slice should I be on now" from its own clock (which would diverge on any replica-to-replica timing skew).
5. **`LiquidationFlow`/`pendingADLSize`/`adlEligibility` non-replication is safe specifically because they're either (a) reconstructed identically by deterministic replay within one command (`pendingADLSize`), or (b) designed to self-heal via re-detection on next scan if lost (`LiquidationFlow` after failover)** — this is a different flavor of "non-replicated but safe" than the loan scanner's index maintenance (§6.7, safe because it's an eventually-consistent *cache*, not authoritative state). Keep these two justifications distinct when documenting/commenting the Rust equivalents — conflating them risks someone "fixing" one into unnecessary replication or, worse, treating the other's staleness tolerance as acceptable for the failover-recovery case where it actually isn't (a stale `LiquidationFlow` after failover would be a real bug if it weren't for the re-detection design).

---

## 12. Architecture decision points for the Rust port (read before starting)

### 12.1 The Rust port is currently single-shard; Java's per-shard-array TwoStep carriers should probably collapse
Confirmed by exhaustive `grep`: `exchange-core-rs/src/core/processors/risk_engine.rs` has **zero** references to `shard_id`/`num_shards`/`shard_mask`. P5's `LoanRatePricingProcessor` already established the precedent for this exact situation (its own doc comment, `loan_rate_pricing_processor.rs:11-23`, explains the choice in detail): rather than literally porting Java's `OrderCommand.commonByShard[]`/`ifPreviewCoverByShard[]`/`adlUserPositionsByShard[]`/`fundingPaymentAndRecvNotionalByShard[]` (arrays sized to `numShards`, indexed by `shardId`), it used a single `OrderCommand`-scoped carrier (`loan_reprice_events: Vec<(i32,i64)>`) since "cross-shard sum" is an identity operation under one shard, while still implementing the R1/merge/R2 **shape** faithfully so the real multi-shard semantics are documented and can be extended later. **Recommendation**: follow the same pattern for IF/ADL/Funding — implement `collect_input`/`build_matcher_events`/`apply_event` as if multi-shard (so the logic is correct and the Java parity is auditable), but back them with direct fields/`Vec`s on `OrderCommand` rather than literal `[T; N]` arrays, exactly as `loan_reprice_events` did.

### 12.2 No FundEvent/EventsHelper bus exists in the Rust port — and P1–P5 already decided not to add one
Confirmed: `grep -rl "FundEvent" exchange-core-rs/src` returns only loan-related files, and the loan dispatcher's own doc comment is explicit and should be treated as binding precedent for P6 too (`loan_command_dispatcher.rs:159-163`) — i.e. all of Java's `sendMarginAlertEvent`/`sendLiquidationAlertEvent`/`sendADLClosePositionEvent`/`sendIFClosePositionEvent`/`sendFundingFeeEvent`/`sendLoanMarginCallEvent`/`sendInternalTransferEvent`/etc. calls (§1.4, §2.2, §3.3, §3.4, §4.3, §5.3, §6.3, §6.4) should be treated as **out of scope for P6's core-logic port** — the state mutations they accompany are what matters; the notification side-channel is explicitly deferred exchange-wide. Do not build an event bus as part of P6.

### 12.3 `MatcherEventType` needs new variants (or a lean-carrier alternative, per §12.1's precedent)
Java's `IF_EVENT`/`ADL_EVENT`/`FUNDING_EVENT`/`INTERNAL_TRANSFER_EVENT` (4 of `MatcherEventType`'s 9 variants) have no Rust equivalent (`matcher_event_type.rs` has only `{Trade, Reject, Reduce, BinaryEvent}`). Two options:
- **(a)** Extend the shared `MatcherEventType` enum with the 4 new variants and thread them through `MatcherTradeEvent` — full Java parity, but touches every exhaustive `match` over `MatcherEventType` in the existing order-book/settlement code (a real, non-trivial blast radius — the risk_engine.rs doc comment for `loan_reprice_events` explicitly cites this concern as the reason it chose *not* to do this for `LOAN_REPRICE_EVENT`).
- **(b)** Follow the `loan_reprice_events` precedent per-processor: IF/ADL/Funding/InternalTransfer each get their own dedicated `OrderCommand`-scoped `Vec<...>` carrier for the R1→merge→R2 handoff, bypassing `MatcherEventType`/`MatcherTradeEvent` entirely, since (unlike a real TRADE) these "events" never need to interoperate with the shared order-book matching machinery.

Given the precedent set by P5, **(b) is likely the intended direction** for at least Funding/InternalTransfer/IF/ADL (none of which is a real order-book match — they're synthetic settlement records dressed as matcher events purely so Java's existing R1→ME→R2 pipeline plumbing could carry them).

### 12.4 `OrderCommandType` additions needed
Confirmed absent from `exchange-core-rs/src/core/common/cmd/order_command_type.rs` (only `ForceLiquidation` exists among the FORCE/IF/ADL/scan family): `InternalTransfer` (14), `IfTakeover` (40), `AutoDeleveraging` (41), futures `IfDeposit`/`IfWithdraw` (42/43 — **distinct from** the already-ported loan `LoanIfDeposit`/`LoanIfWithdraw` at 64/65, do not conflate the two IF pools), `SettleFundingfees` (25), `LiquidationScan` (64 — **code collision with Java's `LOAN_IF_DEPOSIT`!**, see next point), `SystemLiquidationNotify` (31). Also add to `is_non_trading()`: `InternalTransfer`, `IfDeposit`/`IfWithdraw` (futures); verify `MarkpriceAdjustment` already classifies correctly.

**Flag**: Java's `OrderCommandType.java:49-70` has `LIQUIDATION_SCAN((byte)64, ...)` **and** `LOAN_IF_DEPOSIT((byte)64, ...)` both at code 64 — re-verify at implementation time. The Rust port only needs its own codes to be internally distinct; they don't need to numerically match Java's wire codes bit-for-bit unless there's an external wire-protocol compat requirement (check `raft-exchange-server`'s wire encoding before assuming byte-parity is required — the Rust port is a standalone crate with no server, so byte-parity is NOT required).

---

## Summary: areas to re-verify at implementation time

1. **§12.3 MatcherEventType extension-vs-lean-carrier decision** — the single biggest structural fork for this phase; affects IF/ADL/Funding/InternalTransfer uniformly. Decide once, apply consistently.
2. **§12.1 single-shard collapse of the four `*ByShard` carriers** — same category of decision, smaller blast radius per-processor.
3. **§1.5 `LiquidationFlow` state machine + failover-recovery-via-re-detection** — get the "flow is ephemeral, re-derived from position state after failover" property right; don't persist it.
4. **§3.1–3.4 ADL's dual pendingADLSize symmetry** (R1 reserve in `collect_input`, unconditional release in `finalize` walking the **original R1 head**, not the merge-mutated cursor clone).
5. **§6.5 Cross loan-liquidation pick functions' tie-break chains and the "filter inside the pick, not after" design** — determinism + infinite-stall-avoidance both.
6. **§9 `matched_order_command_type`** — becomes load-bearing once ForceLiquidation is router-wired (item 7); port field + population + the one consuming call site together.
7. **§10 conservation identity extension** — any P6 conservation proptest needs the `IFNotional`/`IFPositionRecord` terms folded in.
8. **§4.2/§4.3 and §2.2's shared "truncate-then-distribute-remainder-by-one" pattern** — extract as one shared, unit-tested primitive.
9. **§7.5 scheduler tick-counting vs. thread-scheduling split** — port `runOneIteration`'s domain logic faithfully; the `ScheduledExecutorService` harness is JVM plumbing, adapt freely.
