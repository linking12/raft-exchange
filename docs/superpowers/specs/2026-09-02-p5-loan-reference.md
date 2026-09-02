# P5 Reference Spec: exchange-core Spot Loan (借贷) Subsystem

All paths relative to `/Users/ming/project/java/binance/raft-exchange/`. Line numbers are anchors into the files as read; re-verify if the files have moved since.

Key top-level files:
- `loan.md` — design doc (as-built, §1–§12, §14–§18; §13 is a proposal but **the FLOATING/FIXED rate model in §13 is already implemented** — code matches §13, not the "current fixed rate" language in §1.2/§15)
- `exchange-core/src/main/java/exchange/core2/core/processors/loan/LoanCommandDispatcher.java` (1071 lines) — all 14 command handlers + dispatch
- `exchange-core/src/main/java/exchange/core2/core/processors/loan/LoanService.java` (497 lines) — state + pure functions (accrue, debt payment, LTV, LIF takeover, scale conversion, orderId encoding)
- `exchange-core/src/main/java/exchange/core2/core/processors/loan/LoanGlobalConfig.java` (96 lines) — per-shard global runtime config
- `exchange-core/src/main/java/exchange/core2/core/processors/loan/LoanLiquidationEngine.java` (394 lines) — independent scanner
- `exchange-core/src/main/java/exchange/core2/core/processors/loan/rate/FloatingRateModel.java` (197 lines), `FixedRateModel.java` (102 lines)
- `exchange-core/src/main/java/exchange/core2/core/processors/LoanRatePricingProcessor.java` (105 lines) — TwoStep reprice processor
- `exchange-core/src/main/java/exchange/core2/core/common/{IsolatedLoanRecord,CrossLoanRecord,LoanRecord,SymbolLoanSpecification}.java`
- `exchange-core/src/main/java/exchange/core2/core/common/UserProfile.java` (loan fields lines 91–105, 119–121, 136–138)
- `exchange-core/src/main/java/exchange/core2/core/processors/RiskEngine.java` (loan bits: lines 260–264, 906–913, 937–945, 1029–1072)
- `exchange-core/src/main/java/exchange/core2/core/common/CoreSymbolSpecification.java` (loanConfig lines 72–98, 236–244), `CoreCurrencySpecification.java` (collateralWeightBps lines 21, 34–36)
- `exchange-core/src/main/java/exchange/core2/core/common/cmd/OrderCommandType.java` (loan codes 57–71, `isLoan()` 141–161, `isNonTrading()` 110–134)
- `exchange-core/src/main/java/exchange/core2/core/common/api/binary/BatchAddLoanCommand.java` (329 lines) — `ADD_LOAN` binary command DTO + validators
- `exchange-core/src/main/java/exchange/core2/core/processors/RiskEngineCommandDispatcher.java` (lines 563–646) — `ADD_LOAN` apply
- `exchange-core/src/main/java/exchange/core2/core/processors/liquidation/LiquidationScheduledService.java` — off-lane scheduler that also fires `REPRICE_LOAN_RATES`
- `exchange-core/src/main/java/exchange/core2/core/common/api/reports/TotalCurrencyBalanceReportResult.java` (lines 40–150) — actual conservation formula

---

## 0. Big picture: how a loan command flows

`RiskEngine.preProcessCommand` (RiskEngine.java:260–264) is a 3-way gate:
```java
if (cmd.command.isLoan()) { loanCommandDispatcher.dispatch(cmd); return false; }
if (cmd.command.isNonTrading()) { commandDispatcher.dispatch(cmd); return false; }
switch (cmd.command) { ... main trading/system switch ... }
```
`OrderCommandType.isLoan()` (OrderCommandType.java:141–161) covers exactly 14 codes: `LOAN_CREATE, LOAN_REPAY, LOAN_ADD_COLLATERAL, LOAN_RELEASE_COLLATERAL, LOAN_FORCE_LIQUIDATE, LOAN_CROSS_ADD_COLLATERAL, LOAN_CROSS_WITHDRAW_COLLATERAL, LOAN_CROSS_BORROW, LOAN_CROSS_REPAY, LOAN_CROSS_FORCE_LIQUIDATE, POOL_DEPOSIT, POOL_WITHDRAW, LOAN_IF_DEPOSIT, LOAN_IF_WITHDRAW`. **Note**: `REPRICE_LOAN_RATES` (code 63) is NOT in `isLoan()` — it's in `isNonTrading()` (line 127) and routed to `RiskEngineCommandDispatcher` (line 152–157) → `LoanRatePricingProcessor.collectInput` (R1) then to `RiskEngine.handlerRiskRelease` (R2, line 906–913). `ADD_LOAN` is not an `OrderCommandType` at all — it's a `BinaryDataCommand` (code in `BinaryCommandType.ADD_LOAN`) carried inside `BINARY_DATA_COMMAND`, applied in `RiskEngineCommandDispatcher` lines 563–646.

For force-liquidate commands specifically: R1 (`LoanCommandDispatcher.handleLoanForceLiquidate`/`handleLoanCrossForceLiquidate`) pre-moves collateral and returns `VALID_FOR_MATCHING_ENGINE` with `cmd.action=ASK, cmd.orderType=IOC` — the command then flows through the **normal spot matching engine** (unchanged) and R2 settlement happens in `RiskEngine.handlerRiskRelease` (lines 937–945) which, for `CURRENCY_EXCHANGE_PAIR` symbols, after the standard spot buy/sell settlement, calls `loanCommandDispatcher.postProcessLoanForceLiquidate` / `postProcessLoanCrossForceLiquidate` as a **hook appended after normal spot settlement** — this is the "loan-side settlement of a force-liquidate fill in R2" requested; it is NOT part of the futures liquidation state machine (that's P6/out of scope). The loan force-liquidate *trigger* decision (LTV/term scan) lives entirely in `LoanLiquidationEngine`, independent of futures `LiquidationEngine`/ADL — see §5 below.

---

## 1. Data model

### 1.1 IsolatedLoanRecord (`common/IsolatedLoanRecord.java`)
Object-pool-reused (`ObjectsPool.ISOLATED_LOAN_RECORD`), identity fields non-final, reset via `initialize(...)` (lines 80–96).

Fields (lines 30–55):
- Identity: `uid` (not serialized, injected by context — see `IsolatedLoanRecord(long uid, BytesIn bytes)` ctor line 62), `loanId` (client-supplied, unique per-user within Isolated namespace)
- Opening terms (immutable after open): `symbolId` (= spot pair symbolId), `collateralCurrency` (= spec.baseCurrency), `loanCurrency` (= spec.quoteCurrency), `rateMode` (`RATE_MODE_LOCKED=0` / `RATE_MODE_FLOATING=1`, constants at lines 31–32), `rateBps` (locked-in annual rate for LOCKED; display-only for FLOATING), `openedAtTs` (ms, = `cmd.timestamp`)
- Mutable debt/collateral: `collateralAmount`, `outstandingPrincipal`, `accumulatedInterest`, `lastAccrueTs` (LOCKED cursor), `accSnapshot` (FLOATING cursor — liveAcc snapshot in bps·ms)
- Monotonic: `cumInterestPaid` (for event delta computation downstream)

`isEmpty()` (line 98–100) = `collateralAmount==0 && outstandingPrincipal==0 && accumulatedInterest==0`. `stateHash()` covers 14 fields including `uid`, `symbolId` (lines 192–197); serialization (`writeMarshallable`, lines 175–190) excludes `uid` (reconstructed from parent map context).

### 1.2 CrossLoanRecord (`common/CrossLoanRecord.java`)
**No collateral field** — Cross collateral is account-level, pooled in `UserProfile.crossLoanCollateral`. Fields (lines 31–48): `uid`, `loanId` (separate namespace from Isolated), `symbolId` (matched spot pair via `findLoanSpecByQuoteCurrency`/`findSpotSymbol`), `loanCurrency`, `rateBps`, `openedAtTs`, `outstandingPrincipal`, `accumulatedInterest`, `lastAccrueTs` (unused — Cross is always FLOATING, per line 44 comment), `accSnapshot`, `cumInterestPaid`. `isFixedRate()` (line 138–140) hardcoded `false`. `stateHash()` covers 10 fields (lines 174–178).

### 1.3 LoanRecord interface (`common/LoanRecord.java`)
Shared debt view so accrue/repay/liquidation-settlement logic is written once: `getLoanCurrency/getRateBps/get&setOutstandingPrincipal/get&setAccumulatedInterest/get&setLastAccrueTs/get&setAccSnapshot/isFixedRate/get&setCumInterestPaid`. Both records implement it; `LoanService`, `FloatingRateModel`, `FixedRateModel` all operate through this interface.

### 1.4 UserProfile attachment (`common/UserProfile.java`)
Three fields at the same level as `accounts`/`exchangeLocked`/`positions` (lines 97, 102, 105):
```java
public final LongObjectHashMap<IsolatedLoanRecord> isolatedLoans;   // loanId -> record
public final IntLongHashMap                        crossLoanCollateral; // currency -> amount (account-level pool)
public final LongObjectHashMap<CrossLoanRecord>    crossLoans;       // loanId -> record
```
Serialized at the tail of `UserProfile` (ctor lines 136–138, write lines 154–156); `stateHash()` includes all three (lines 353–355). No derived/cached view is kept — `RiskEngine.calculateLocked` recomputes live each call (comment at line 100–101).

### 1.5 SymbolLoanSpecification (`common/SymbolLoanSpecification.java`)
Attached to `CoreSymbolSpecification.loanConfig` (non-null, default empty = disabled). Per-pair fields (lines 28–32): `initialLtvBps` (0 = disabled, `isEnabled()` line 54–56), `liquidationLtvBps` (Isolated single-loan trigger; Cross uses `LoanGlobalConfig` global thresholds instead), `marginCallLtvBps` (0 = warning off), `maxAmount` (0 = no cap; **note operational caveat** in loan.md §17 — the gRPC proto for `SpotLoanConfig` only carries `symbolId`+`initialLtv`, so `maxAmount` is currently unconfigurable via gRPC and always resolves to 0), `maxTermDays` (0 = perpetual; only meaningful for Isolated LOCKED per LoanLiquidationEngine.java:160). **No `collateralWeightBps` field here** — that lives on `CoreCurrencySpecification.collateralWeightBps` (per-currency, not per-symbol/pair; see §7 below) since it's a base-currency-level Cross-collateral discount shared across all pairs with that base. Only mutation point: `update(...)` (lines 59–66), called from `CoreSymbolSpecification.updateLoanConfig` (CoreSymbolSpecification.java:240–244), itself called only from the `ADD_LOAN` symbol-config apply path.

### 1.6 LoanService (`processors/loan/LoanService.java`)
Per-shard singleton, pure-state + pure-function utility class holding **no RiskEngine reference** (loan.md line 105). Fields (LoanGlobalConfig.java + LoanService.java 51–59):
- 4 fund buckets (`IntLongHashMap`, currency-scale, all conserved except `loanPoolBorrowed`): `loanPoolAvailable`, `loanPoolBorrowed` (tracker, mirrors `Σ outstandingPrincipal`, NOT in conservation), `interestRevenue`, `loanInsuranceFund` (LIF, allowed negative)
- `globalConfig: LoanGlobalConfig` (7 ints: `numeraireCurrency`, `crossLiquidationLtvBps` default 8500, `crossMarginCallLtvBps` default 8000, `loanPoolUtilizationCapBps` default 9000, `loanLiquidationFeeBps` default 200, `ltvLiquidationBufferBps` default 2000, `ltvMarginCallBufferBps` default 1000 — LoanGlobalConfig.java:28–33)
- `floatingRate: FloatingRateModel`, `fixedRate: FixedRateModel` (holds ref to floatingRate)

---

## 2. Loan lifecycle commands (R1/dispatch)

All handlers in `LoanCommandDispatcher.java`; dispatch table at lines 51–121. Common preamble for user-dimension commands: look up `UserProfile`, reject `AUTH_INVALID_USER` if null, reject `LOAN_USER_SUSPENDED` if suspended, then `up.processedTransactionIds.tryClaim(cmd.orderId, cmd.timestamp)` for idempotency (claim-and-keep semantics — a failed later op does NOT release the claimed id; retry requires a new `transactionId`, same as `BALANCE_ADJUSTMENT`). `loanId` is a pure business key, not used for idempotency.

### 2.1 LOAN_CREATE (Isolated open) — `handleLoanCreate` (lines 130–209)
Fields: `cmd.symbol`=spot symbolId, `cmd.size`=collateralAmount (base scale), `cmd.price`=principal (quote scale), `cmd.reserveBidPrice`=loanId, `cmd.userCookie` low byte = rateMode (`RATE_MODE_FLOATING` sentinel, else LOCKED).

Validation order (cheap→expensive, lines 141–176): spec exists & `type==CURRENCY_EXCHANGE_PAIR` → `LOAN_NOT_ENABLED`; `spec.loanConfig.isEnabled()` → `LOAN_NOT_ENABLED`; `loanId` not already used → `LOAN_ALREADY_EXISTS`; `principal>0 && collateralAmount>0` → `LOAN_INVALID_AMOUNT`; `maxAmount!=0 && principal>maxAmount` → `LOAN_PRINCIPAL_EXCEEDS_LIMIT`; `markPrice>0` → `LOAN_MARKPRICE_NOT_READY`; LTV check `principal×10000 ≤ collateralValueInLoanCurrency×initialLtvBps` else `LOAN_LTV_TOO_HIGH`; free collateral (`accounts − calculateLocked ≥ collateralAmount`) else `LOAN_COLLATERAL_INSUFFICIENT`; pool capacity+utilization (`verifyPoolCapacity`, lines 1045–1062) else `LOAN_POOL_INSUFFICIENT`/`LOAN_POOL_UTILIZATION_EXCEEDED`.

Mutation: allocate record from pool, `initialize(...)`, set `rateMode`, resolve `openRateBps` from `FloatingRateModel.openRateBps` or `FixedRateModel.openRateBps` depending on mode (lines 186–195; if FLOATING, `floatingRate.initOpenSnapshot(loan, ts)` anchors `accSnapshot`), set `collateralAmount`/`outstandingPrincipal`, `up.isolatedLoans.put(loanId, loan)`, register in scanner index (`onIsolatedLoanOpened`), `disburseLoan` (below), emit `LOAN_BORROW` event.

`disburseLoan` (lines 1065–1069): `accounts[loanCurrency] += principal; loanPoolAvailable -= principal; loanPoolBorrowed += principal`.

Idempotency: `tryClaim` + `loanId` uniqueness check together prevent double-open on retry of a *new* orderId with the *same* loanId.

### 2.2 LOAN_REPAY (Isolated) — `handleLoanRepay` (234–271), shared core `settleRepay` (215–232)
`cmd.reserveBidPrice`=loanId, `cmd.price`=repayAmount (0 = full payoff).

`settleRepay`: `requestedRepay<0` → `LOAN_INVALID_AMOUNT`; `loanService.accrueTo(loan, now)`; `payoff = outstandingPrincipal + accumulatedInterest`; `actualRepay = (requested==0 || requested>=payoff) ? payoff : requested`; free-balance check (`accounts − calculateLocked ≥ actualRepay`) else `LOAN_ACCOUNT_INSUFFICIENT`; `loanService.applyDebtPayment(loan, up.accounts, actualRepay)`.

`applyDebtPayment` (LoanService.java:138–152): interest-first — `interestPart = min(fund, accumulatedInterest)`, `principalPart = min(fund-interestPart, outstandingPrincipal)`; `account -= paid`; `accumulatedInterest -= interestPart`; `outstandingPrincipal -= principalPart`; `cumInterestPaid += interestPart`; `interestRevenue += interestPart`; `loanPoolAvailable += principalPart`; `loanPoolBorrowed -= principalPart`. Partial repay does **not** release collateral. If `loan.isEmpty()` after repay, removed from map and returned to object pool (`onIsolatedLoanClosed` updates scanner index).

### 2.3 LOAN_ADD_COLLATERAL — `handleLoanAddCollateral` (274–310)
`cmd.reserveBidPrice`=loanId, `cmd.size`=amount. Validates loan exists/owned, `amount>0`, free collateral balance (`accounts−calculateLocked ≥ amount`) else `LOAN_COLLATERAL_INSUFFICIENT`; accrues interest first (so the event snapshot is current), then `collateralAmount += amount`. Emits `LOAN_COLLATERAL_CHANGE`.

### 2.4 LOAN_RELEASE_COLLATERAL — `handleLoanReleaseCollateral` (313–378)
`amount>0`, `amount ≤ collateralAmount` else `LOAN_COLLATERAL_EXCEEDS_LOAN`. Accrues interest, computes `realDebt = outstandingPrincipal + calculateDisplayInterest(loan, now)` (pending-inclusive, to avoid under-counting LTV — line 345–347 comment). If `newCollateral==0 && realDebt>0` → `LOAN_LTV_TOO_HIGH_AFTER_RELEASE`. Else if `newCollateral>0`, check `realDebt×10000 < newCollateralValue×liquidationLtvBps` (strict `<`, i.e. allowed to release down to just above the liquidation line, matching Binance semantics — "allowed to marginCall band, user's own risk", loan.md §5.4/5.5). Cleans up dead shell (`isEmpty()`) same as repay.

### 2.5 LOAN_FORCE_LIQUIDATE (Isolated) — R1 `handleLoanForceLiquidate` (388–417), R2 `postProcessLoanForceLiquidate` (423–525)
R1: `cmd.symbol`=spot symbolId, `cmd.size`=sell lots, `cmd.price`=limit price (bankruptcy price), `cmd.reserveBidPrice`=loanId. Idempotency by construction: `loan==null` → `SUCCESS` no-op (line 395–396 not directly shown but pattern at postProcess line 425–430). Pre-move: `sellAmount = lotsToCollateralAmount(...)`; `0 < sellAmount ≤ collateralAmount` else `LOAN_INVALID_AMOUNT`; **atomically** `collateralAmount -= sellAmount; exchangeLocked[collateralCurrency] += sellAmount` (compare-and-consume — this is what makes duplicate force-liquidate submissions safe without extra state, loan.md §5.1/§7.1). Sets `cmd.action=ASK, cmd.orderType=IOC`, returns `VALID_FOR_MATCHING_ENGINE`.

R2 (`postProcessLoanForceLiquidate`): sums `TRADE`/`REJECT` matcher events (lines 441–451); rejected lots refunded back to `collateralAmount` (REJECT path already released `exchangeLocked` in the generic spot handler, lines 456–459); on `tradedSize>0`, computes `avgTakerPrice`, `takerFee`, `receivedQuote` and calls `loanService.settleLiquidationProceeds` (below); always calls `loanService.accrueTo` again post-settlement to catch pending interest even on the all-reject path (line 476); computes `remainDebt` and `sellableLots` (lots-based, not `collateralAmount==0`, to correctly detect sub-lot dust — line 478 comment); **terminal-state logic** (lines 492–511):
  - `remainDebt>0 && (tradedSize==0 || sellableLots==0)` → **LIF takeover** (`takeOverByInsuranceFund`, below), loan zeroed and removed
  - else `loan.isEmpty()` → loan removed
  - else → loan kept (partial fill), snapshot values captured for the event
Event `LOAN_LIQUIDATED` emitted only if `tradedSize>0 || takenOver` (pure no-op zero-fill-and-already-clear case emits nothing).

### 2.6–2.9 Cross commands
- **LOAN_CROSS_ADD_COLLATERAL** — `handleLoanCrossAddCollateral` (532–563): `cmd.symbol`=currency, `cmd.size`=amount. No LTV check (adding collateral only helps). Requires `collateralWeightForBase(currency) > 0` else `LOAN_COLLATERAL_NOT_ALLOWED`; free-balance check; `up.crossLoanCollateral.addToValue(currency, amount)`; syncs scanner index (`syncCrossExposure`).
- **LOAN_CROSS_WITHDRAW_COLLATERAL** — `handleLoanCrossWithdrawCollateral` (569–604): requires `loanService.isNumeraireConfigured()` else `LOAN_NUMERAIRE_NOT_CONFIGURED`; subtract-then-check pattern — subtract first, recompute `calculateCrossAccountLtvBps(..., failClosedOnMissingPrice=true)`, if `newLtv >= crossLiquidationLtvBps` revert the subtraction and return `LOAN_CROSS_LTV_TOO_HIGH_AFTER_WITHDRAW`.
- **LOAN_CROSS_BORROW** — `handleLoanCrossBorrow` (610–669): `cmd.symbol`=spot symbolId (client passes the pair; `loanCurrency=spec.quoteCurrency`), `cmd.price`=principal, `cmd.reserveBidPrice`=loanId. Requires numeraire configured else fail-close; pool capacity check; **Cross is always FLOATING** — `openRateBps = floatingRate.openRateBps(loanCurrency)`; record created & put into map *before* re-checking LTV (subtract-then-check pattern again, lines 648–660): if `newLtv > initialLtvBps` after adding the loan, the loan is rolled back (`crossLoans.remove`, object returned to pool) and `LOAN_LTV_TOO_HIGH_AFTER_BORROW` returned. On success, `disburseLoan`, event, `syncCrossExposure`.
- **LOAN_CROSS_REPAY** — `handleLoanCrossRepay` (672–705): identical `settleRepay` core as Isolated, but never releases collateral (Cross collateral is account-level, shared across all Cross loans of that user).

### 2.10 LOAN_CROSS_FORCE_LIQUIDATE — R1 `handleLoanCrossForceLiquidate` (715–747), R2 `postProcessLoanCrossForceLiquidate` (753–863)
R1: `cmd.reserveBidPrice`=targetLoanId, `cmd.symbol`=spot pair (base=sellingCurrency, quote=targetLoan.loanCurrency), `cmd.size`=sell lots. Same pre-move pattern: `crossLoanCollateral[sellingCurrency] -= sellAmount; exchangeLocked[sellingCurrency] += sellAmount`.

R2: Similar trade/reject accounting as Isolated. Key Cross-specific complexity:
- **Structural exhaustion check** (lines 810–817): iterates `up.crossLoanCollateral` and calls `LoanService.isStructurallySellable` for each currency — true only if `collateralWeightBps>0` for that currency AND a ready spot pair exists to at least one of the account's outstanding-debt currencies with ≥1 sellable lot. This is a **permanent-capability** check (ignores transient markPrice=0), distinguishing "market currently can't absorb it" from "structurally can never be sold" (comment lines 809–810).
- If `remainTargetDebt>0 && (tradedSize==0 || allCollateralExhausted)` → `loanService.takeOverCrossLoan(...)` (LIF takes the *target* loan only, proportional to its share of total debt — see §5). If takeover succeeds, loan closed/recycled; **if it fails (numeraire valuation unavailable), the loan is deliberately left untouched** and a warning logged (fail-closed, lines 830–836) rather than using a stale/missing price.
- If `allCollateralExhausted`, **all other outstanding Cross loans for this user are also handed to the LIF** via `takeOverRemainingCrossLoans` (lines 873–902) — iterated in **sorted loanId order** (deterministic across replicas, comment lines 869–871), each emitting its own `LOAN_LIQUIDATED` event (never merged).

### 2.11 Pool / LIF operational commands
`POOL_DEPOSIT`/`POOL_WITHDRAW`/`LOAN_IF_DEPOSIT`/`LOAN_IF_WITHDRAW` (lines 940–997): `cmd.uid` carries `shardId` (not a real uid!); every shard runs the handler but only the target shard writes `cmd.resultCode` (dispatch lines 94–117, self-filter `(int)cmd.uid == engine.getShardId()`). No idempotency dedup on these (loan.md §5.1 — "operational side must not resubmit"). `cmd.symbol`=currency, `cmd.size`=amount.
- `POOL_DEPOSIT`: `loanPoolAvailable += amount; adjustments -= amount`.
- `POOL_WITHDRAW`: guard `loanPoolAvailable ≥ amount` else `LOAN_POOL_INSUFFICIENT` (can only withdraw the un-lent portion); `loanPoolAvailable -= amount; adjustments += amount`.
- `LOAN_IF_DEPOSIT`: `loanInsuranceFund += amount; adjustments -= amount`.
- `LOAN_IF_WITHDRAW`: guard `loanInsuranceFund ≥ amount` else `LOAN_IF_INSUFFICIENT` (LIF may go negative from takeovers, but withdrawal itself is never allowed to push it more negative — line 984–997 comment: "LIF negative is a passive consequence of takeover, not an operational overdraft facility").

### 2.12 ADD_LOAN (binary command, runtime config)
Handled entirely in `RiskEngineCommandDispatcher.java:563–646`, not through `LoanCommandDispatcher`/`isLoan()`. `BatchAddLoanCommand` (`common/api/binary/BatchAddLoanCommand.java`) bundles three **independently optional, independently validated** parts — invalid part is `log.warn`'d and skipped without affecting the other parts (loan.md §11):
- **`GlobalLoanConfig`** (5 partial-update fields, `≤0`=no-change): apply-all-or-nothing validated by `thresholdsValidGivenCurrent` (BatchAddLoanCommand.java:199–209) against the config *as it would be after* this update — `0 < marginCall < liquidation < 10000`, utilization cap `≤10000`, liquidation fee `<10000`, both LTV buffers `<10000`. Numeraire additionally requires the currency spec to exist (line 571–572).
- **`SymbolLoanConfig`** (6 fields incl. `collateralWeightBps`; `UNSET=-1` sentinel for "derive from buffers"): `resolve(liqBuffer, mcBuffer)` (BatchAddLoanCommand.java:283–290) fills unset `liquidationLtvBps = initialLtvBps + liqBuffer`, unset `marginCallLtvBps = liquidation - mcBuffer`, unset `collateralWeightBps = initialLtvBps` (defaults collateral weight to the LTV itself!), unset `maxAmount/maxTermDays = 0`. `Resolved.valid()` requires `0 ≤ initial < 10000` and (if `initial>0`) `liquidation > initial`, `liquidation<10000`, `marginCall` either 0 or strictly between. On apply: if resolved `initialLtvBps==0` (kill-switch), only the initial flag is zeroed — **liquidation/marginCall/maxAmount/maxTermDays are preserved** so existing loans aren't force-liquidated as a side effect of disabling new borrowing (RiskEngineCommandDispatcher.java:610–614 comment). Otherwise all 5 `SymbolLoanSpecification` fields written via `spec.updateLoanConfig(...)`, AND `collateralWeightBps` written separately to `CoreCurrencySpecification.updateCollateralWeight` on the **base currency** (not the symbol!) — last-writer-wins across multiple pairs sharing the same base (line 618 comment).
- **`RateCurveConfig`** (5 fields, existence = full replace, no partial-update — because 0 is a legitimate curve value): `valid()` requires `0≤base<10000`, `0<kink<10000`, `slope1/slope2≥0`. On apply, overwrites `FloatingRateModel`'s 4 curve params and `FixedRateModel.lockedRateAdjustBps`.

---

## 3. LTV / collateral math

### 3.1 Isolated LTV
`evalCollateralInLoanCurrency` → `LoanService.collateralValueInQuoteCurrency(amount, spec, markPrice, baseSpec, quoteSpec)` (LoanService.java:412–420): converts `amount` (base currencyScale) → symbol-scale, multiplies by `markPrice`, converts notional back to quote currencyScale. Open-time check: `principal × 10000 ≤ collateralValue × initialLtvBps` (LoanCommandDispatcher.java:166–169). Live/scanner LTV (`isolatedLtvBps`, LoanCommandDispatcher.java:1017–1032; `LoanLiquidationEngine.checkIsolated`, lines 136–186): `realDebt = outstandingPrincipal + calculateDisplayInterest(loan, now)` (**pending-interest-inclusive**, so a user cannot dodge liquidation by not repaying — loan.md §6.2); trigger `realDebt×10000 ≥ collateralValue×liquidationLtvBps`.

### 3.2 Cross LTV — two distinct denominators, deliberately (LoanService.java:168–269, critical subtlety)
`calculateCrossAccountLtvBps` (weighted, used for **trigger** decisions — BORROW/WITHDRAW guard and scanner trigger) sums debt in numeraire terms over all `crossLoans` (pending-interest-inclusive) as numerator, and sums collateral in numeraire terms **multiplied by `collateralWeightBps/10000`** (`applyWeight=true`) as denominator. `calculateCrossRawLtvBps` (unweighted market value, used only for **pricing** the bankruptcy price) uses the same collateral sum **without** the weight discount (`applyWeight=false`). The two overloads share the private `crossLtvBps` helper (lines 208–269) which takes an `applyWeight` flag.

Why the split matters (loan.md §18.3 callout, code comment LoanService.java:196–200): the weight is a risk haircut for *how much you're allowed to borrow against*, not a statement about *what the collateral is actually worth*. Using the weighted (discounted) LTV to compute the force-sell limit price would inflate the bankruptcy price by `1/weight`, potentially pricing above market and guaranteeing a reject even when the raw collateral value comfortably covers the debt.

`failClosedOnMissingPrice` parameter (LoanService.java:184–195): scanner/display paths pass `false` (missing price → LTV=0, conservative skip, don't accidentally trigger); BORROW/WITHDRAW guards pass `true` (missing price → `Long.MAX_VALUE`, i.e. reject rather than silently treat as "infinitely safe" — comment explicitly warns this could otherwise be exploited to over-borrow or withdraw all collateral).

`valueInNumeraire` (LoanService.java:395–410): converts `amount` of `currency` into `numeraireCurrency` via `specProvider.findSpotSymbol(currency, numeraireCurrency)` + `LastPriceCacheRecord.markPrice`; returns `-1` sentinel on any missing spec/price (propagated up as "unevaluable").

### 3.3 collateralWeightBps (per-currency, not per-symbol)
Lives on `CoreCurrencySpecification.collateralWeightBps` (CoreCurrencySpecification.java:21), 0 = "this currency cannot be used as Cross collateral" (`LOAN_COLLATERAL_NOT_ALLOWED`). Read via `LoanService.collateralWeightForBase` (LoanService.java:467–470). Only mutation point: `updateCollateralWeight` (CoreCurrencySpecification.java:35–36), called only from the `ADD_LOAN` symbol-config apply path (see §2.12) — i.e., configured indirectly through a per-pair command but stored per-base-currency.

### 3.4 The "virtual lock" model: `loanCollateralLocked` → `calculateLocked`
This is the central mechanism the memory note refers to, and it is genuinely a single-point extension. `RiskEngine.loanCollateralLocked` (RiskEngine.java:1063–1072):
```java
long loanCollateralLocked(UserProfile userProfile, int currency) {
    long locked = 0;
    for (IsolatedLoanRecord loan : userProfile.isolatedLoans)
        if (loan.collateralCurrency == currency) locked += loan.collateralAmount;
    locked += userProfile.crossLoanCollateral.get(currency);
    return locked;
}
```
`calculateLocked(userProfile, currency)` (RiskEngine.java:1029–1055) sums: ① futures margin for all positions in that currency, ② `exchangeLocked` (spot open-order holds), ③④ `loanCollateralLocked`. **Loan collateral is never physically moved out of `accounts`** — it's a bookkeeping subtraction applied everywhere balances are checked. Every consumer of `calculateLocked` (NSF checks for spot order placement `placeExchangeOrder` line 673, futures margin NSF `spendable` at line 617–618, `withdrawableBalance` line 747–753, event "free" reporting) automatically respects the loan lock — **no futures or spot code needed to change** (loan.md §14.2, §16 decision table). Only exception: when the LIF actually takes over a loan (§5 below), the amount IS physically debited from `accounts` (real transfer, not virtual) — see `takeOverByInsuranceFund` (LoanCommandDispatcher.java:921–933, `up.accounts.addToValue(collateralCurrency, -collateral)`) and `takeOverCrossLoan` (LoanService.java:372–373, same pattern).

The doc comment at RiskEngine.java:1037–1038 is explicit that 4 sites (withdraw, margin-adjustment, spot order, futures order) do **not** call `calculateLocked` directly but call `loanCollateralLocked` as a separate deduction term, because those 4 sites compute their own futures net-equity component differently; this is a real implementation detail to replicate exactly (don't just always call the umbrella `calculateLocked`).

---

## 4. Interest / rate model (as-built — Fixed+Floating dual-mode is IMPLEMENTED, not just proposed)

Despite loan.md §1.2/§15 saying "currently fixed rate, §13 is a proposal", the code in `processors/loan/rate/` **is** the §13 design, fully implemented. Treat §13 of loan.md as accurate current behavior for the rate subsystem.

### 4.1 Two rate models, one shared LoanRecord contract
- `FixedRateModel` (rate/FixedRateModel.java) — used only by **Isolated LOCKED** loans. Linear/simple interest: `accrueDelta = truncMulDiv(truncMulDiv(elapsed, principal, YEAR_MS), rateBps, BPS_SCALE)` (two-step to avoid overflow, lines 76–87), cursor = `lastAccrueTs`. `openRateBps` = `floating.currentRateBpsOrBase(loanCurrency) + lockedRateAdjustBps`, floored at 0 (lines 50–53) — i.e. Fixed rate is **derived from Floating's current curve value at open time plus a spread**, then frozen for the life of the loan.
- `FloatingRateModel` (rate/FloatingRateModel.java) — used by **Isolated FLOATING + ALL Cross loans**. Kinked curve (`curveRateBps`, lines 82–89): below `kinkUtilBps`, linear from `baseBps` with slope `slope1Bps`; above kink, `baseBps+slope1Bps` plus slope `slope2Bps` scaled over the remaining utilization range. Interest uses an **additive accumulator** (`accRateBpsMs[currency]`, "Σ rate×Δt in bps·ms"), not a per-loan lastAccrueTs — this is what makes reprice O(1) per currency regardless of loan count (loan.md §13.5): each loan stores `accSnapshot`; pending interest = `truncMulDiv(liveAcc(now) − accSnapshot, principal, YEAR_MS×BPS_SCALE)` (lines 161–169). `liveAccRateBpsMs` (lines 123–131) extrapolates the accumulator to `now` using the *current* rate for the time since `lastRepriceTs` — no need to wait for the next reprice tick to get an accurate live value. **Additive, not multiplicative/compounding** — deliberate choice for 64-bit overflow safety and because "interest only applies to principal" (loan.md §13.5 rationale).
- Both models share a "truncated-but-chargeable" guard (`FloatingRateModel.accrue` lines 138–153, `FixedRateModel.accrue` lines 56–68): if pending interest rounds to 0 due to truncation but principal>0 and time has genuinely elapsed, the accrue cursor is **not** advanced, so sub-threshold interest accumulates across repeated calls instead of being silently lost forever on high-frequency accrue calls (e.g. repeated partial repayments). This is a subtle correctness detail flagged explicitly in both files as fix "F1".
- `LoanService.accrueTo`/`calculateDisplayInterest` dispatch via `loan.isFixedRate()` (LoanService.java:123–131) — 2 call sites, no interface/polymorphism needed beyond this if/else.

### 4.2 Reprice pipeline — TwoStepCommandProcessor pattern (entangled area, read carefully at implementation time)
Triggered by `LiquidationScheduledService.runOneIteration` (liquidation/LiquidationScheduledService.java:57–67): shard-0-only, leader-gated (`isRunning()` doubles as leader gate), off-lane scheduled thread submits `ApiRepriceLoanRates` every `repriceEveryNTicks` scan ticks (default cadence configured at construction, doc says 1h). **This differs slightly from loan.md's description** (md says the throttle lives inside `LoanLiquidationEngine.check`'s shard-0 branch with a `lastRepriceMs` leader-local var; the actual code puts the tick-counting in the shared `LiquidationScheduledService` parent, alongside the `LIQUIDATION_SCAN` cadence) — trust the code (`LiquidationScheduledService.java`) over the doc text here.

`REPRICE_LOAN_RATES` is `isNonTrading()` (not `isLoan()`), goes through `RiskEngineCommandDispatcher` → `LoanRatePricingProcessor` (a `TwoStepCommandProcessor`, base class contract at `processors/TwoStepCommandProcessor.java`):
- **R1** `collectInput` (LoanRatePricingProcessor.java:35–53): each shard writes its local `loanPoolBorrowed`/`loanPoolAvailable` into `cmd.commonByShard[shardId].amounts`, encoding both in one map via key sign trick — `borrowed` at key=`currency`, `available` at key=`~currency` (bitwise complement, always negative for non-negative currency ids, so no collision).
- **matcher/merge** `buildMatcherEvents` (lines 56–91): sums across all shards' `commonByShard`, computes `util = FloatingRateModel.utilizationBps(totalBorrowed, totalAvailable)` per currency, emits one `MatcherEventType.LOAN_REPRICE_EVENT` per currency (sorted by currency for cross-replica determinism, line 77) with `matchedOrderUid=currency`, `size=util`.
- **R2** `applyEvent` (lines 93–104): each shard, per event, calls `floatingRate.advanceAccumulator(currency, cmd.timestamp)` (settle the accumulator for the *old* rate over the elapsed interval — **must** happen before repricing or the interval gets mis-costed at the new rate) then `floatingRate.repriceCurrency(currency, util)` (write the new `currentRateBps` from the curve). After the event loop, `RiskEngine.handlerRiskRelease` (RiskEngine.java:906–913) sets `loanService.getFloatingRate().setLastRepriceTs(cmd.timestamp)` **once**, after all currencies processed (not per-event) — all shards write the same value since `cmd.timestamp` is deterministic.

This TwoStep pattern is the same one used by `FundingFeeCommandProcessor` (explicitly cited as precedent, loan.md §13.3) — worth reading that sibling processor if the general TwoStep shape is unfamiliar, since `LoanRatePricingProcessor` is a thin, representative instance of it.

### 4.3 Interest destination on repay/liquidation settlement
`applyDebtPayment` and `settleLiquidationProceeds` (LoanService.java:138–166) both route the interest portion to `interestRevenue[loanCurrency]` and the principal portion back to `loanPoolAvailable[loanCurrency]`/`loanPoolBorrowed[loanCurrency]`. `settleLiquidationProceeds` additionally skims `loanLiquidationFeeBps` off the top (ceil-rounded, "don't shortchange the exchange" comment line 160) into `loanInsuranceFund` *before* calling `accrueTo`+`applyDebtPayment` on the remainder.

**Entangled/flag for implementation time**: the rate curve + accumulator math (`FloatingRateModel`) and its interplay with the TwoStep reprice pipeline is the most numerically delicate part of this subsystem (accumulator overflow safety at 64-bit, `truncMulDiv` two-step ordering, the "truncated but chargeable" cursor-freeze logic). Read `FloatingRateModel.java` in full plus `LoanRatePricingProcessor.java` together, and write dedicated unit tests mirroring `LoanServiceTest`/`UpdateLoanGlobalConfigCommandTest` (mentioned in loan.md §17) before porting.

---

## 5. Loan liquidation — trigger (scanner) + settlement hook

### 5.1 LoanLiquidationEngine — independent scanner (`processors/loan/LoanLiquidationEngine.java`)
**Not** a lane bolted onto the futures `LiquidationEngine`; it's a separate class held by `LiquidationEngine` and invoked via `checkLoans(cmd)` (line 98) from `LiquidationEngine.checkPositions` (RiskEngine's leader-gated liquidation scan entry — that call site is P6/out-of-scope internals, but the invocation boundary itself, `checkLoans`, is the hook worth knowing).

Two detection paths (lines 98–126): if `cmd.symbol>=0` (price-event-triggered), uses maintained indices `isolatedLoanSymbolToUsers`/`crossLoanCurrencyToUsers` (maintained by `onIsolatedLoanOpened`/`onIsolatedLoanClosed`/`syncCrossExposure`, called synchronously from `LoanCommandDispatcher` on every mutating command — NOT part of the replicated snapshot, rebuilt on `updateProvider` at lines 70–88) to only scan affected users; if `cmd.symbol<0` (the `LIQUIDATION_SCAN` full backstop sweep), scans all users filtered by `coveredByScanSlice` (round-robin slicing across scan ticks, shared with futures scanning).

**checkIsolated** (lines 136–186): computes `collateralValue` via `collateralValueInQuoteCurrency`; skips if `≤0` (avoid divide-by-zero on bankruptcy price); `realDebt` includes pending interest; `termExpired` computed **only for `RATE_MODE_LOCKED`** loans (`loan.rateMode == RATE_MODE_LOCKED && maxTermDays>0 && elapsed>maxTermDays×MS_PER_DAY`, line 160–161 — FLOATING Isolated loans are perpetual, no term check); trigger on `termExpired || ltvScaled≥collateralValue×liquidationLtvBps`. On trigger: converts `collateralAmount` to lots; if `<=0` (sub-lot dust), logs and **skips this round** rather than liquidating (dust will eventually be absorbed at LIF-takeover time, §6); else computes `bankruptcyPrice = ceilMulDiv(markPrice, realDebt, collateralValue)` (LoanLiquidationEngine.java:391–392) and submits `ApiLoanForceLiquidate` with a deterministic `forceSellOrderId`. If not triggered but `marginCallLtvBps>0` and LTV crosses that (lower) threshold, emits a leader-local, best-effort `LOAN_MARGIN_CALL` notification (bypasses raft, throttled downstream, does not mutate replicated state).

**checkCross** (lines 195–247): account-level LTV via `calculateCrossAccountLtvBps` (weighted). If `≥ crossLiquidationLtvBps`: **partial deleverage, one (sellingCurrency, targetLoan) pair per tick**, converging over multiple ticks (comment lines 188–194 explains why the "ready spot market exists" filter must be baked into the *pick* functions themselves rather than checked after picking the theoretically-best pair — otherwise a best-pair-with-no-market would be re-picked every tick and spin forever). `pickCrossCollateralToSell` (305–328): weight DESC → amount DESC → currency ASC, filtered to currencies that can actually repay some outstanding debt via an existing ready spot market. `pickCrossLoanToRepay` (330–346): rate DESC → principal DESC → loanId ASC, filtered to loans with a ready market against the chosen selling currency. Pricing uses `calculateCrossRawLtvBps` (raw/unweighted) for the bankruptcy price, falling back to the weighted LTV only if raw is unevaluable (line 223–226, "conservative but never abandon liquidation"). Sell size = `min(availableLots, neededLots)` computed against the **bankruptcy price**, not markPrice (line 359 comment — pricing at a discount but sizing at market price would systematically undersize the sale). If LTV is below liquidation but at/above `crossMarginCallLtvBps`, emits account-level `LOAN_MARGIN_CALL` (loanId=0, loanCurrency=0 — no single-loan attribution for the account-level warning).

### 5.2 R2 settlement hook (in scope for P5)
As described in §0: `RiskEngine.handlerRiskRelease` lines 937–945, called immediately after the standard `CURRENCY_EXCHANGE_PAIR` spot settlement path (`handleMatcherEventsExchangeSell`/`Buy`) for the two loan force-liquidate command types. This is the exact seam: **spot matching/settlement code is completely unmodified**; the loan-specific postProcess is bolted on as an `if` after it. Read `postProcessLoanForceLiquidate`/`postProcessLoanCrossForceLiquidate` (LoanCommandDispatcher.java, detailed in §2.5/§2.10) for the full settlement logic — that IS the P5-scope piece. The trigger scanning (§5.1) and the general futures liquidation state machine are P6-adjacent and can be summarized/pointed-at rather than fully replicated for P5, per the task's own scoping.

**Flag for implementation time**: `LoanLiquidationEngine`'s index maintenance (`isolatedLoanSymbolToUsers`/`crossLoanCurrencyToUsers`) is process-local, not part of replicated state, rebuilt from `UserProfile` scans on `updateProvider` (snapshot recovery). This asymmetry (mutated synchronously in the hot path, but reconstructible/non-authoritative) is a common source of subtle bugs when porting — read `onIsolatedLoanOpened`/`onIsolatedLoanClosed`/`syncCrossExposure` (lines 254–299) carefully; note `syncCrossExposure`'s comment (line 285) that partial currency-exit staleness is tolerated (harmless over-trigger) while full-account-exit removal is exact.

---

## 6. Conservation

### 6.1 Buckets introduced
Per-shard, in `LoanService`: `loanPoolAvailable[c]`, `loanPoolBorrowed[c]` (tracker only), `interestRevenue[c]`, `loanInsuranceFund[c]`. Per-user, virtual (not separate buckets, tracked inside `accounts`): `IsolatedLoanRecord.collateralAmount`, `UserProfile.crossLoanCollateral[c]`.

### 6.2 Actual conservation formula in code (as reported, `TotalCurrencyBalanceReportResult.java`)
This is the ground truth to replicate, and it's slightly more refined than loan.md §4.1's prose:
```java
// TotalCurrencyBalanceReportResult.java:124-131
getGlobalBalancesSum() = accountBalances + extraMargin + exchangeLocked + loanCollateral
                        + loanBalances + fees + adjustments + suspends + ifBalances
```
where (line 40–60):
- `accountBalances` = `Σ_user (UserProfile.accounts − exchangeLocked − loanCollateral)` — **note**: unlike loan.md §4.1's simplified `accountBalances = Σ(accounts − exchangeLocked)`, the actual report bucket subtracts loan collateral too, moving it into its own explicit `loanCollateral` bucket for reporting clarity (comment lines 56–59: "physically still in user accounts, but occupied by loan and not disposable — split out of accountBalances into an independent bucket for the global reconciliation").
- `loanCollateral` = `Σ_user (Σ isolatedLoans.collateralAmount + Σ crossLoanCollateral[c])` per currency — this is exactly the value `RiskEngine.loanCollateralLocked` computes per-user, aggregated across all users/shards.
- `loanBalances` = `loanPoolAvailable + interestRevenue + loanInsuranceFund` (explicitly **excludes** `loanPoolBorrowed` — comment line 52–54: "that's a tracker; its corresponding money is already inside the borrower's `accounts`, credited at disburse time").

So for a Rust port, the **canonical global identity** is:
```
Σ_user(accounts − exchangeLocked − loanCollateral) + extraMargin + exchangeLocked + loanCollateral
  + (loanPoolAvailable + interestRevenue + loanInsuranceFund) + fees + adjustments + suspends + ifBalances = 0
```
which telescopes back to loan.md's simpler `accountBalances(incl. loan collateral) + extraMargin + exchangeLocked + loanBalances + fees + adjustments + suspends + ifBalances = 0` — both are equivalent, but the **report code keeps loan collateral as an explicit separate summand for visibility/debuggability**, which is the "loan collateral as a separate bucket" the memory note refers to. Recommend the Rust port keep this same explicit split for parity with existing conservation-fuzz tests (`ITConservationFuzz`, `ITLoanConservation` mentioned in loan.md §17).

### 6.3 Where interest/fees go, and the LIF takeover flow (loan.md §18.6, verified against `takeOverByInsuranceFund` / `LoanService.takeOverCrossLoan`)
Normal repay/liquidation: interest → `interestRevenue` (extracted later by `RESET_FEE`'s sweep, see §6.4); principal → back to `loanPoolAvailable`/`loanPoolBorrowed`.

LIF takeover (Isolated, `LoanCommandDispatcher.takeOverByInsuranceFund`, lines 921–933):
```java
loanInsuranceFund[loanCcy]      -= (principal + interest)
loanPoolAvailable[loanCcy]      += principal
loanPoolBorrowed[loanCcy]       -= principal
interestRevenue[loanCcy]        += interest
if (collateral > 0) {
    accounts[collateralCcy]     -= collateral   // REAL debit, not virtual anymore
    loanInsuranceFund[collateralCcy] += collateral
}
```
Net effect: pool is made whole immediately (principal+interest realized as if fully repaid), LIF absorbs the debt-side loss (goes negative in `loanCcy`) and gains the collateral asset (positive in `collateralCcy`) — a **negative LIF balance is not itself a loss, it's the platform's advanced/bridged amount**; the real loss (if any) is only realized when the LIF later disposes of the collateral inventory via `LOAN_IF_WITHDRAW` (loan.md §18.5/§18.8 — explicitly supersedes/removes a formerly-existing `badDebt` bucket and `POOL_ABSORB_BAD_DEBT` command, which are **gone from current code** — do not port them).

LIF takeover (Cross, `LoanService.takeOverCrossLoan`, lines 287–385): proportional — only the *target* loan's share of total account debt (weighted by numeraire value) is taken, using a "take from highest-`collateralWeightBps`-first, deterministic tie-break by currency ascending" allocation (lines 323–376) rather than pro-rata-slicing every collateral currency (avoids dust fragmentation, comment lines 278–280). Fails closed (`return false`, loan.md §18.7) if any required price/spec is missing — the caller must leave the loan untouched rather than use a stale price to decide how much collateral to seize.

### 6.4 RESET_FEE interaction (loan.md §12)
`ResetFeeCommandProcessor` sweeps `interestRevenue` into `adjustments` (bucket → 0, `adjustments += X`) at the same time it sweeps `fees` — this is the actual withdrawal mechanism for accumulated interest income. **`loanInsuranceFund` is explicitly NOT swept** — it's a fund, not revenue; its only withdrawal path is the dedicated `LOAN_IF_WITHDRAW` command (§2.11).

---

## 7. What R1/R2 reads from CoreSymbolSpecification.loanConfig / currency specs

Per-symbol (`CoreSymbolSpecification.loanConfig: SymbolLoanSpecification`), read at:
- `initialLtvBps` — open-time LTV gate (`LOAN_CREATE`, `LOAN_CROSS_BORROW`); `isEnabled()` gate (both opens)
- `liquidationLtvBps` — Isolated scanner trigger (`LoanLiquidationEngine.checkIsolated`), Isolated release-collateral post-check (`LOAN_RELEASE_COLLATERAL`)
- `marginCallLtvBps` — Isolated scanner warning threshold
- `maxAmount` — open-time cap on both Isolated and Cross borrow
- `maxTermDays` — Isolated LOCKED-only term-expiry check in scanner

Per-currency (`CoreCurrencySpecification.collateralWeightBps`), read at:
- `LoanService.collateralWeightForBase` — gates `LOAN_CROSS_ADD_COLLATERAL` (`LOAN_COLLATERAL_NOT_ALLOWED` if 0), weights the denominator in `calculateCrossAccountLtvBps` (triggering/BORROW/WITHDRAW), used as the sort key in `pickCrossCollateralToSell` and the takeover allocation order in `takeOverCrossLoan`.

Global config (`LoanService.globalConfig: LoanGlobalConfig`, per-shard, not per-symbol), read at: Cross trigger/margin-call thresholds (`crossLiquidationLtvBps`/`crossMarginCallLtvBps`), pool utilization cap (`loanPoolUtilizationCapBps`, both opens' `verifyPoolCapacity`), liquidation fee (`loanLiquidationFeeBps`, `settleLiquidationProceeds`), numeraire currency (all Cross valuation paths), and the two LTV-derivation buffers (`ltvLiquidationBufferBps`/`ltvMarginCallBufferBps`, used only inside `ADD_LOAN`'s `SymbolLoanConfig.resolve` to compute defaults when a per-symbol override is unset).

Rate model state (`LoanService.floatingRate`/`fixedRate`) is read at loan-open time (`openRateBps`) and at every accrue/display call — not gated by symbol at all, purely by `loanCurrency` (confirming the design decision that rate is a pool-level, not pair-level, concept — loan.md §3.5 note, verified: no `loanRateBps` field remains on `SymbolLoanSpecification`).

---

## Summary of areas to re-verify at implementation time (entangled / worth extra care)

1. **`FloatingRateModel` accumulator math** (§4) — additive accumulator + reprice ordering (`advanceAccumulator` before `repriceCurrency`) + the "truncated but chargeable" cursor-freeze logic in both rate models. Read `FloatingRateModel.java` and `FixedRateModel.java` in full; these are short files but numerically subtle.
2. **`LoanRatePricingProcessor` TwoStep pipeline** (§4.2) — the shard-sum-via-key-sign-encoding trick and the exact R1/merge/R2 split; also cross-reference `FundingFeeCommandProcessor` as the sibling pattern if the general TwoStep shape needs more context.
3. **`LoanLiquidationEngine`'s Cross pick functions and structural-sellability check** (§5.1, `pickCrossCollateralToSell`/`pickCrossLoanToRepay`/`LoanService.isStructurallySellable`) — the determinism requirements (sort tie-breaks) are load-bearing for cross-replica consistency; get these exactly right.
4. **`LoanService.takeOverCrossLoan`'s proportional-allocation algorithm** (§6.3) — deterministic currency ordering, dust-truncation-to-borrower behavior, fail-closed-on-missing-price semantics.
5. **The scanner's non-replicated index maintenance** (`isolatedLoanSymbolToUsers`/`crossLoanCurrencyToUsers`) — process-local, rebuilt on snapshot recovery, mutated synchronously from command handlers; get the rebuild-vs-incremental-update boundary right.
6. The general futures `LiquidationEngine`/ADL internals that `LoanLiquidationEngine.checkLoans` plugs into (`LiquidationEngine.checkPositions`) are explicitly P6/out of scope — only the invocation boundary (`checkLoans(OrderCommand)`) and the R2 hook (`handlerRiskRelease` lines 937–945) need to be understood for P5.
