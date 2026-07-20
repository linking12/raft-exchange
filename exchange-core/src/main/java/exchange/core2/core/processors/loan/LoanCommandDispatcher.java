/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package exchange.core2.core.processors.loan;

import exchange.core2.collections.objpool.ObjectsPool;
import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.CrossLoanRecord;
import exchange.core2.core.common.IsolatedLoanRecord;
import exchange.core2.core.common.LoanRecord;
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.processors.LastPriceCacheRecord;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class LoanCommandDispatcher {
    private static final byte LOAN_MODE_ISOLATED = 0; // FundEvent.loanMode 标记
    private static final byte LOAN_MODE_CROSS = 1;
    private final RiskEngine engine;
    private final LoanLiquidationEngine loanLiquidationEngine;

    public LoanCommandDispatcher(RiskEngine engine) {
        this.engine = engine;
        this.loanLiquidationEngine = engine.getLiquidationEngine().getLoanLiquidationEngine();
    }

    /**
     * OrderCommand 分发入口：按 command 类型路由到具体 handler。用户维度命令走 uidForThisHandler shard filter；
     * POOL_* 走 (int)cmd.uid == shardId self-filter。
     */
    public void dispatch(OrderCommand cmd) {
        switch (cmd.command) {
            case LOAN_CREATE:
                if (engine.uidForThisHandler(cmd.uid))
                    cmd.resultCode = handleLoanCreate(cmd);
                return;
            case LOAN_REPAY:
                if (engine.uidForThisHandler(cmd.uid))
                    cmd.resultCode = handleLoanRepay(cmd);
                return;
            case LOAN_ADD_COLLATERAL:
                if (engine.uidForThisHandler(cmd.uid))
                    cmd.resultCode = handleLoanAddCollateral(cmd);
                return;
            case LOAN_RELEASE_COLLATERAL:
                if (engine.uidForThisHandler(cmd.uid))
                    cmd.resultCode = handleLoanReleaseCollateral(cmd);
                return;
            case LOAN_FORCE_LIQUIDATE:
                if (engine.uidForThisHandler(cmd.uid))
                    cmd.resultCode = handleLoanForceLiquidate(cmd);
                return;
            case LOAN_CROSS_ADD_COLLATERAL:
                if (engine.uidForThisHandler(cmd.uid))
                    cmd.resultCode = handleLoanCrossAddCollateral(cmd);
                return;
            case LOAN_CROSS_WITHDRAW_COLLATERAL:
                if (engine.uidForThisHandler(cmd.uid))
                    cmd.resultCode = handleLoanCrossWithdrawCollateral(cmd);
                return;
            case LOAN_CROSS_BORROW:
                if (engine.uidForThisHandler(cmd.uid))
                    cmd.resultCode = handleLoanCrossBorrow(cmd);
                return;
            case LOAN_CROSS_REPAY:
                if (engine.uidForThisHandler(cmd.uid))
                    cmd.resultCode = handleLoanCrossRepay(cmd);
                return;
            case LOAN_CROSS_FORCE_LIQUIDATE:
                if (engine.uidForThisHandler(cmd.uid))
                    cmd.resultCode = handleLoanCrossForceLiquidate(cmd);
                return;
            // POOL_*: 所有 shard 都跑 handle（内部短路），但只 target shard 写 cmd.resultCode
            case POOL_DEPOSIT: {
                final CommandResultCode rc = handlePoolDeposit(cmd);
                if ((int)cmd.uid == engine.getShardId())
                    cmd.resultCode = rc;
                return;
            }
            case POOL_WITHDRAW: {
                final CommandResultCode rc = handlePoolWithdraw(cmd);
                if ((int)cmd.uid == engine.getShardId())
                    cmd.resultCode = rc;
                return;
            }
            case LOAN_IF_DEPOSIT: {
                final CommandResultCode rc = handleLoanIfDeposit(cmd);
                if ((int)cmd.uid == engine.getShardId())
                    cmd.resultCode = rc;
                return;
            }
            case LOAN_IF_WITHDRAW: {
                final CommandResultCode rc = handleLoanIfWithdraw(cmd);
                if ((int)cmd.uid == engine.getShardId())
                    cmd.resultCode = rc;
                return;
            }
            default: // 不可达：isLoan() 门守
                throw new IllegalStateException("Non-loan command dispatched to LoanCommandDispatcher: " + cmd.command);
        }
    }

    // ====================================================================================
    // Isolated 用户命令：开仓 / 还款 / 加减抵押
    // ====================================================================================

    /**
     * 开仓 Isolated 借贷：LTV 与 pool 容量校验通过后放款。symbol=symbolId / size=collateralAmount / price=principal。
     */
    public CommandResultCode handleLoanCreate(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;
        // tryClaim early：claim 后失败也不释放 id，重试需换新 transactionId（对齐 BALANCE_ADJUSTMENT）
        if (!up.processedTransactionIds.tryClaim(cmd.orderId, cmd.timestamp)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        final CoreSymbolSpecification spec = engine.getSymbolSpecificationProvider().getSymbolSpecification(cmd.symbol);
        if (spec == null || spec.type != SymbolType.CURRENCY_EXCHANGE_PAIR)
            return CommandResultCode.LOAN_NOT_ENABLED;
        if (!spec.loanConfig.isEnabled())
            return CommandResultCode.LOAN_NOT_ENABLED;

        final long loanId = cmd.reserveBidPrice;
        if (up.isolatedLoans.containsKey(loanId))
            return CommandResultCode.LOAN_ALREADY_EXISTS;

        final long collateralAmount = cmd.size;
        final long principal = cmd.price;
        if (principal <= 0 || collateralAmount <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;
        if (spec.loanConfig.maxAmount != 0 && principal > spec.loanConfig.maxAmount)
            return CommandResultCode.LOAN_PRINCIPAL_EXCEEDS_LIMIT;

        final long markPrice = markPriceOrZero(spec.symbolId);
        if (markPrice <= 0)
            return CommandResultCode.LOAN_MARKPRICE_NOT_READY;

        // LTV: principal × 10000 ≤ collateralValue × initialLtvBps，两边同在 loanCurrency currencyScale
        final long collateralValueInLoanCurrency = evalCollateralInLoanCurrency(collateralAmount, spec, markPrice);
        if (collateralValueInLoanCurrency < 0)
            return CommandResultCode.LOAN_MARKPRICE_NOT_READY;
        final long lhs = Math.multiplyExact(principal, LoanService.BPS_SCALE);
        final long rhs = Math.multiplyExact(collateralValueInLoanCurrency, (long)spec.loanConfig.initialLtvBps);
        if (lhs > rhs)
            return CommandResultCode.LOAN_LTV_TOO_HIGH;

        final int collateralCurrency = spec.baseCurrency;
        final int loanCurrency = spec.quoteCurrency;
        final long freeCollateralCurrency =
            up.accounts.get(collateralCurrency) - engine.calculateLocked(up, collateralCurrency);
        if (freeCollateralCurrency < collateralAmount)
            return CommandResultCode.LOAN_COLLATERAL_INSUFFICIENT;

        final LoanService loanService = engine.getLoanService();
        final CommandResultCode poolCheck = verifyPoolCapacity(loanService, loanCurrency, principal);
        if (poolCheck != CommandResultCode.SUCCESS)
            return poolCheck;

        // rateMode 由 cmd.userCookie 承载
        final byte rateMode = ((byte)cmd.userCookie) == IsolatedLoanRecord.RATE_MODE_FLOATING
            ? IsolatedLoanRecord.RATE_MODE_FLOATING : IsolatedLoanRecord.RATE_MODE_LOCKED;
        final int openRateBps =
            rateMode == IsolatedLoanRecord.RATE_MODE_FLOATING ? loanService.getFloatingRate().openRateBps(loanCurrency)
                : loanService.getFixedRate().openRateBps(loanCurrency);
        final IsolatedLoanRecord loan =
            engine.getObjectsPool().get(ObjectsPool.ISOLATED_LOAN_RECORD, IsolatedLoanRecord::new);
        loan.initialize(cmd.uid, loanId, spec.symbolId, collateralCurrency, loanCurrency, openRateBps, cmd.timestamp);
        loan.rateMode = rateMode;
        if (rateMode == IsolatedLoanRecord.RATE_MODE_FLOATING) {
            loanService.getFloatingRate().initOpenSnapshot(loan, cmd.timestamp); // 计息游标定在当前累加器
        }
        loan.collateralAmount = collateralAmount;
        loan.outstandingPrincipal = principal;
        up.isolatedLoans.put(loanId, loan);
        loanLiquidationEngine.onIsolatedLoanOpened(cmd.uid, loan.symbolId);
        disburseLoan(up, loanService, loanCurrency, principal);
        final long loanLocked = engine.calculateLocked(up, loanCurrency);
        final long colLocked = engine.calculateLocked(up, collateralCurrency);
        engine.getEventsHelper().sendLoanBorrowEvent(cmd, loan.uid, loanId, LOAN_MODE_ISOLATED, loanCurrency,
            collateralCurrency, loan.outstandingPrincipal, loan.collateralAmount,
            isolatedLtvBps(loan, cmd.timestamp),
            up.accounts.get(loanCurrency) - loanLocked, loanLocked,
            up.accounts.get(collateralCurrency) - colLocked, colLocked);
        return CommandResultCode.SUCCESS;
    }

    /**
     * Isolated/Cross REPAY 共用核心：校验金额 → accrue → 算实抵债额（0 或 ≥payoff 则全额）→ 查可用余额 → 抵债（利息优先）。
     * 本次抵扣的利息/本金不外传——事件发累计量快照，调用方直接读 loan。
     */
    private CommandResultCode settleRepay(UserProfile up, LoanRecord loan, OrderCommand cmd) {
        final long requestedRepay = cmd.price;
        if (requestedRepay < 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        final LoanService loanService = engine.getLoanService();
        loanService.accrueTo(loan, cmd.timestamp);
        final long payoff = Math.addExact(loan.getOutstandingPrincipal(), loan.getAccumulatedInterest());
        final long actualRepay = (requestedRepay == 0 || requestedRepay >= payoff) ? payoff : requestedRepay;

        final int loanCurrency = loan.getLoanCurrency();
        final long free = up.accounts.get(loanCurrency) - engine.calculateLocked(up, loanCurrency);
        if (free < actualRepay)
            return CommandResultCode.LOAN_ACCOUNT_INSUFFICIENT;

        loanService.applyDebtPayment(loan, up.accounts, actualRepay);
        return CommandResultCode.SUCCESS;
    }

    /** 偿还 Isolated 借贷（本金+利息）。price=repayAmount，0 = payoff 全部本息。 */
    public CommandResultCode handleLoanRepay(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;
        if (!up.processedTransactionIds.tryClaim(cmd.orderId, cmd.timestamp)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        final long loanId = cmd.reserveBidPrice;
        final IsolatedLoanRecord loan = up.isolatedLoans.get(loanId);
        if (loan == null)
            return CommandResultCode.LOAN_NOT_FOUND;
        if (loan.uid != cmd.uid)
            return CommandResultCode.LOAN_UID_MISMATCH;

        final CommandResultCode repayCode = settleRepay(up, loan, cmd);
        if (repayCode != CommandResultCode.SUCCESS)
            return repayCode;
        // 事件快照须读操作后、归还对象池前的 loan
        final int loanCurrency = loan.loanCurrency;
        final long loanLocked = engine.calculateLocked(up, loanCurrency);
        final long colLocked = engine.calculateLocked(up, loan.collateralCurrency);
        engine.getEventsHelper().sendLoanRepayEvent(cmd, loan.uid, loanId, LOAN_MODE_ISOLATED, loanCurrency,
            loan.collateralCurrency, loan.outstandingPrincipal, loan.accumulatedInterest, loan.collateralAmount,
            isolatedLtvBps(loan, cmd.timestamp), loan.cumInterestPaid,
            up.accounts.get(loanCurrency) - loanLocked, loanLocked,
            up.accounts.get(loan.collateralCurrency) - colLocked, colLocked);

        if (loan.isEmpty()) {
            up.isolatedLoans.remove(loanId);
            loanLiquidationEngine.onIsolatedLoanClosed(up, loan.symbolId);
            engine.getObjectsPool().put(ObjectsPool.ISOLATED_LOAN_RECORD, loan);
        }
        return CommandResultCode.SUCCESS;
    }

    /** 补抵押降 LTV。size=amount。 */
    public CommandResultCode handleLoanAddCollateral(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;
        if (!up.processedTransactionIds.tryClaim(cmd.orderId, cmd.timestamp)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        final long loanId = cmd.reserveBidPrice;
        final IsolatedLoanRecord loan = up.isolatedLoans.get(loanId);
        if (loan == null)
            return CommandResultCode.LOAN_NOT_FOUND;
        if (loan.uid != cmd.uid)
            return CommandResultCode.LOAN_UID_MISMATCH;

        final long amount = cmd.size;
        if (amount <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        final long free =
            up.accounts.get(loan.collateralCurrency) - engine.calculateLocked(up, loan.collateralCurrency);
        if (free < amount)
            return CommandResultCode.LOAN_COLLATERAL_INSUFFICIENT;

        engine.getLoanService().accrueTo(loan, cmd.timestamp);
        loan.collateralAmount = Math.addExact(loan.collateralAmount, amount);
        final long loanLocked = engine.calculateLocked(up, loan.loanCurrency);
        final long colLocked = engine.calculateLocked(up, loan.collateralCurrency);
        engine.getEventsHelper().sendLoanCollateralChangeEvent(cmd, loan.uid, loanId, LOAN_MODE_ISOLATED,
            loan.loanCurrency, loan.collateralCurrency, loan.outstandingPrincipal, loan.accumulatedInterest,
            loan.collateralAmount, isolatedLtvBps(loan, cmd.timestamp),
            up.accounts.get(loan.loanCurrency) - loanLocked, loanLocked,
            up.accounts.get(loan.collateralCurrency) - colLocked, colLocked);
        return CommandResultCode.SUCCESS;
    }

    /** 减抵押；允许到 marginCall 上方，拒绝撤到强平线。size=amount。 */
    public CommandResultCode handleLoanReleaseCollateral(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;
        if (!up.processedTransactionIds.tryClaim(cmd.orderId, cmd.timestamp)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        final long loanId = cmd.reserveBidPrice;
        final IsolatedLoanRecord loan = up.isolatedLoans.get(loanId);
        if (loan == null)
            return CommandResultCode.LOAN_NOT_FOUND;
        if (loan.uid != cmd.uid)
            return CommandResultCode.LOAN_UID_MISMATCH;

        final long amount = cmd.size;
        if (amount <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;
        if (amount > loan.collateralAmount)
            return CommandResultCode.LOAN_COLLATERAL_EXCEEDS_LOAN;

        final CoreSymbolSpecification spec =
            engine.getSymbolSpecificationProvider().getSymbolSpecification(loan.symbolId);
        if (spec == null)
            return CommandResultCode.LOAN_NOT_ENABLED;
        final long markPrice = markPriceOrZero(spec.symbolId);
        if (markPrice <= 0)
            return CommandResultCode.LOAN_MARKPRICE_NOT_READY;

        engine.getLoanService().accrueTo(loan, cmd.timestamp);
        // 减后 LTV < liquidation 才允许；realDebt 含 accumulatedInterest + pending，避免低估 LTV
        final long realDebt = Math.addExact(loan.outstandingPrincipal,
            engine.getLoanService().calculateDisplayInterest(loan, cmd.timestamp));
        final long newCollateral = loan.collateralAmount - amount;
        if (newCollateral == 0 && realDebt > 0) {
            return CommandResultCode.LOAN_LTV_TOO_HIGH_AFTER_RELEASE;
        }
        if (newCollateral > 0) {
            final long newCollateralValueInLoanCurrency = evalCollateralInLoanCurrency(newCollateral, spec, markPrice);
            if (newCollateralValueInLoanCurrency < 0)
                return CommandResultCode.LOAN_MARKPRICE_NOT_READY;
            final long lhs = Math.multiplyExact(realDebt, LoanService.BPS_SCALE);
            final long rhs =
                Math.multiplyExact(newCollateralValueInLoanCurrency, (long)spec.loanConfig.liquidationLtvBps);
            if (lhs >= rhs)
                return CommandResultCode.LOAN_LTV_TOO_HIGH_AFTER_RELEASE;
        }

        loan.collateralAmount = newCollateral;
        final long loanLocked = engine.calculateLocked(up, loan.loanCurrency);
        final long colLocked = engine.calculateLocked(up, loan.collateralCurrency);
        engine.getEventsHelper().sendLoanCollateralChangeEvent(cmd, loan.uid, loanId, LOAN_MODE_ISOLATED,
            loan.loanCurrency, loan.collateralCurrency, loan.outstandingPrincipal, loan.accumulatedInterest,
            loan.collateralAmount, isolatedLtvBps(loan, cmd.timestamp),
            up.accounts.get(loan.loanCurrency) - loanLocked, loanLocked,
            up.accounts.get(loan.collateralCurrency) - colLocked, colLocked);
        // 全零死壳清理：归还对象池让同 loanId 可被 LOAN_CREATE 复用
        if (loan.isEmpty()) {
            up.isolatedLoans.remove(loanId);
            loanLiquidationEngine.onIsolatedLoanClosed(up, loan.symbolId);
            engine.getObjectsPool().put(ObjectsPool.ISOLATED_LOAN_RECORD, loan);
        }
        return CommandResultCode.SUCCESS;
    }

    // ====================================================================================
    // Isolated 强平：R1 挂 IOC → R2 结算，接不住转 LIF 接管
    // ====================================================================================

    /**
     * preProcess：校验 + pre-move 抵押到 exchangeLocked，转成 spot ASK IOC 交撮合。
     * symbol=现货 symbolId / size=卖出张数(lot) / price=破产价。
     */
    public CommandResultCode handleLoanForceLiquidate(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;

        final long loanId = cmd.reserveBidPrice;
        final IsolatedLoanRecord loan = up.isolatedLoans.get(loanId);
        if (loan == null)
            return CommandResultCode.LOAN_NOT_FOUND;
        if (loan.uid != cmd.uid)
            return CommandResultCode.LOAN_UID_MISMATCH;

        final CoreSymbolSpecification spec = engine.getSymbolSpecificationProvider().getSymbolSpecification(cmd.symbol);
        if (spec == null || spec.type != SymbolType.CURRENCY_EXCHANGE_PAIR
            || spec.baseCurrency != loan.collateralCurrency || spec.quoteCurrency != loan.loanCurrency)
            return CommandResultCode.LOAN_NOT_ENABLED;

        final CoreCurrencySpecification collateralSpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(loan.collateralCurrency);
        final long sellAmount = LoanService.lotsToCollateralAmount(cmd.size, spec, collateralSpec);
        if (sellAmount <= 0 || sellAmount > loan.collateralAmount)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        loan.collateralAmount -= sellAmount;
        up.exchangeLocked.addToValue(loan.collateralCurrency, sellAmount);

        cmd.action = OrderAction.ASK;
        cmd.orderType = OrderType.IOC;
        return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
    }

    /**
     * postProcess：结算完 spot ASK 后调用（owner shard）。REJECT 回填 collateralAmount；TRADE 所得走
     * {@link LoanService#settleLiquidationProceeds}；市场接不住或抵押只剩尘埃时由 LIF 承接。
     */
    public void postProcessLoanForceLiquidate(OrderCommand cmd, CoreSymbolSpecification spec, UserProfile takerUp) {
        final long loanId = cmd.reserveBidPrice;
        final IsolatedLoanRecord loan = takerUp.isolatedLoans.get(loanId);
        if (loan == null) {
            log.error("Loan gone in postProcess, collateral released to user: uid={} loanId={} symbol={} size={}",
                cmd.uid, loanId, cmd.symbol, cmd.size);
            return;
        }
        final LoanService loanService = engine.getLoanService();
        final int loanCurrency = loan.loanCurrency;
        final CoreCurrencySpecification loanCurrencySpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(loanCurrency);
        final CoreCurrencySpecification baseSpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(loan.collateralCurrency);

        // ────────────────────────────────────────────────────────────────────
        // ① 汇总撮合结果：成交 / 拒单量（symbol scale）
        // ────────────────────────────────────────────────────────────────────
        long tradedSize = 0;
        long tradedNotional = 0;
        long rejectedSize = 0;
        for (MatcherTradeEvent ev = cmd.matcherEvent; ev != null; ev = ev.nextEvent) {
            if (ev.eventType == MatcherEventType.TRADE) {
                tradedSize += ev.size;
                tradedNotional += Math.multiplyExact(ev.size, ev.price);
            } else if (ev.eventType == MatcherEventType.REJECT) {
                rejectedSize += ev.size;
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // ② REJECT 回填：spot handler 已把 exchangeLocked 释放回用户，抵押须归位保守恒
        // ────────────────────────────────────────────────────────────────────
        if (rejectedSize > 0) {
            long rejectedInCurrencyScale = CoreArithmeticUtils.symbolToCurrencyScale(rejectedSize, spec, baseSpec);
            loan.collateralAmount = Math.addExact(loan.collateralAmount, rejectedInCurrencyScale);
        }

        // early-capture：下方分支会把 loan 归还对象池
        final long borrowerUid = loan.uid;
        final int collateralCurrency = loan.collateralCurrency;

        // ────────────────────────────────────────────────────────────────────
        // ③ TRADE 结算：所得扣 takerFee 后 → 强平费 → 利息 → 本金，overpay 留用户
        // ────────────────────────────────────────────────────────────────────
        if (tradedSize > 0) {
            final long avgTakerPrice = tradedNotional / tradedSize;
            final long takerFee = CoreArithmeticUtils.calculateTakerFee(tradedSize, avgTakerPrice, spec);
            final long receivedQuote =
                CoreArithmeticUtils.sizePriceToCurrencyScale(tradedNotional - takerFee, spec, loanCurrencySpec);
            loanService.settleLiquidationProceeds(loan, takerUp.accounts, receivedQuote, cmd.timestamp);
        }

        loanService.accrueTo(loan, cmd.timestamp); // 全拒路径 settleLiquidationProceeds 未跑过，补计后接管才不漏 pending 利息
        long remainDebt = Math.addExact(loan.outstandingPrincipal, loan.accumulatedInterest);
        // 用"是否还有可卖整张"而非 collateralAmount==0 判定，否则 sub-lot 尘埃会被当成还有救
        long sellableLots = LoanService.collateralAmountToLots(loan.collateralAmount, spec, baseSpec);
        long snapPrincipal = 0;
        long snapInterest = 0;
        long snapCollateral = 0;
        long snapLtvBps = 0;
        // early-capture：本次利息已由 settleLiquidationProceeds 累加进 loan，且下方分支会归还对象池
        long cumInterestPaid = loan.cumInterestPaid;
        boolean takenOver = false;

        // ────────────────────────────────────────────────────────────────────
        // ④ 终态判定：债清关 loan / 接不住转 LIF / 其余保留等下轮
        // ────────────────────────────────────────────────────────────────────
        // 全拒或抵押已碎成卖不掉的尘埃而债务仍在 → LIF 承接，避免无限重试
        if (remainDebt > 0 && (tradedSize == 0 || sellableLots == 0)) {
            takenOver = true;
            takeOverByInsuranceFund(takerUp, loan.outstandingPrincipal, loan.accumulatedInterest, loanCurrency,
                collateralCurrency, loan.collateralAmount);
            loan.outstandingPrincipal = 0;
            loan.accumulatedInterest = 0;
            loan.collateralAmount = 0;
            takerUp.isolatedLoans.remove(loanId);
            loanLiquidationEngine.onIsolatedLoanClosed(takerUp, loan.symbolId);
            engine.getObjectsPool().put(ObjectsPool.ISOLATED_LOAN_RECORD, loan);
        } else if (loan.isEmpty()) {
            takerUp.isolatedLoans.remove(loanId);
            loanLiquidationEngine.onIsolatedLoanClosed(takerUp, loan.symbolId);
            engine.getObjectsPool().put(ObjectsPool.ISOLATED_LOAN_RECORD, loan);
        } else {
            snapPrincipal = loan.outstandingPrincipal;
            snapInterest = loan.accumulatedInterest;
            snapCollateral = loan.collateralAmount;
            snapLtvBps = isolatedLtvBps(loan, cmd.timestamp);
        }

        // ────────────────────────────────────────────────────────────────────
        // ⑤ 发事件：有成交或发生接管才是实质变动，纯 no-op 跳过
        // ────────────────────────────────────────────────────────────────────
        if (tradedSize > 0 || takenOver)
        {
            final long loanLocked = engine.calculateLocked(takerUp, loanCurrency);
            final long colLocked = engine.calculateLocked(takerUp, collateralCurrency);
            engine.getEventsHelper().sendLoanLiquidatedEvent(cmd, borrowerUid, loanId, LOAN_MODE_ISOLATED, loanCurrency,
                collateralCurrency, snapPrincipal, snapInterest, snapCollateral, snapLtvBps, cumInterestPaid,
                takerUp.accounts.get(loanCurrency) - loanLocked, loanLocked,
                takerUp.accounts.get(collateralCurrency) - colLocked, colLocked);
        }
    }

    // ====================================================================================
    // Cross 用户命令：加减抵押 / 借款 / 还款
    // ====================================================================================

    /** Cross 账户级追加抵押（不校验 LTV，越多抵押越安全）。symbol=currency / size=amount。 */
    public CommandResultCode handleLoanCrossAddCollateral(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;
        if (!up.processedTransactionIds.tryClaim(cmd.orderId, cmd.timestamp)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        final int currency = cmd.symbol;
        final long amount = cmd.size;
        if (amount <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        // 允许作 Cross 抵押：币种级 collateralWeightBps > 0
        if (LoanService.collateralWeightForBase(currency, engine.getCurrencySpecificationProvider()) <= 0)
            return CommandResultCode.LOAN_COLLATERAL_NOT_ALLOWED;

        final long free = up.accounts.get(currency) - engine.calculateLocked(up, currency);
        if (free < amount)
            return CommandResultCode.LOAN_COLLATERAL_INSUFFICIENT;

        up.crossLoanCollateral.addToValue(currency, amount);
        // Cross 账户级：loanId=0、debt 留 0、collateralAmount=该币新余额
        final long colLocked = engine.calculateLocked(up, currency);
        engine.getEventsHelper().sendLoanCollateralChangeEvent(cmd, up.uid, 0L, LOAN_MODE_CROSS, 0, currency, 0, 0,
            up.crossLoanCollateral.get(currency), crossLtvBps(up, cmd.timestamp), 0, 0,
            up.accounts.get(currency) - colLocked, colLocked);
        loanLiquidationEngine.syncCrossExposure(up);
        return CommandResultCode.SUCCESS;
    }

    /**
     * Cross 账户级提取抵押。symbol=currency / size=amount；撤后账户级 LTV ≥ crossLiquidationLtvBps 则 revert，
     * numeraire 未配置时 LTV 恒 0（不拦截）。
     */
    public CommandResultCode handleLoanCrossWithdrawCollateral(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;
        if (!up.processedTransactionIds.tryClaim(cmd.orderId, cmd.timestamp)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        final int currency = cmd.symbol;
        final long amount = cmd.size;
        if (amount <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;
        if (up.crossLoanCollateral.get(currency) < amount)
            return CommandResultCode.LOAN_COLLATERAL_EXCEEDS_LOAN;

        // 先扣再重算 LTV，超线 revert（允许到 marginCall 上方）
        final LoanService loanService = engine.getLoanService();
        if (!loanService.isNumeraireConfigured())
            return CommandResultCode.LOAN_NUMERAIRE_NOT_CONFIGURED;
        up.crossLoanCollateral.addToValue(currency, -amount);
        final long newLtv = loanService.calculateCrossAccountLtvBps(up, cmd.timestamp,
            engine.getSymbolSpecificationProvider(), engine.getCurrencySpecificationProvider(),
            engine.getLastPriceCache(), loanService.getGlobalConfig().numeraireCurrency, true);
        if (newLtv >= loanService.getGlobalConfig().crossLiquidationLtvBps) {
            up.crossLoanCollateral.addToValue(currency, amount); // revert
            return CommandResultCode.LOAN_CROSS_LTV_TOO_HIGH_AFTER_WITHDRAW;
        }
        final long colLocked = engine.calculateLocked(up, currency);
        engine.getEventsHelper().sendLoanCollateralChangeEvent(cmd, up.uid, 0L, LOAN_MODE_CROSS, 0, currency, 0, 0,
            up.crossLoanCollateral.get(currency), newLtv, 0, 0,
            up.accounts.get(currency) - colLocked, colLocked);
        loanLiquidationEngine.syncCrossExposure(up);
        return CommandResultCode.SUCCESS;
    }

    /**
     * Cross 借款。symbol=loanCurrency / price=principal；借后账户级 LTV > loanInitialLtvBps 则 revert，
     * numeraire 未配置时 LTV 恒 0（不拦截）。
     */
    public CommandResultCode handleLoanCrossBorrow(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;
        if (!up.processedTransactionIds.tryClaim(cmd.orderId, cmd.timestamp)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        final long loanId = cmd.reserveBidPrice;
        if (up.crossLoans.containsKey(loanId))
            return CommandResultCode.LOAN_ALREADY_EXISTS;

        final int symbolId = cmd.symbol;
        final long principal = cmd.price;
        if (principal <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        // 客户端直传现货 pair 的 symbolId，loanCurrency = spec.quoteCurrency
        final CoreSymbolSpecification spec =
            engine.getSymbolSpecificationProvider().getSymbolSpecification(symbolId);
        if (spec == null || spec.type != SymbolType.CURRENCY_EXCHANGE_PAIR || !spec.loanConfig.isEnabled())
            return CommandResultCode.LOAN_NOT_ENABLED;
        final int loanCurrency = spec.quoteCurrency;
        if (spec.loanConfig.maxAmount != 0 && principal > spec.loanConfig.maxAmount)
            return CommandResultCode.LOAN_PRINCIPAL_EXCEEDS_LIMIT;

        final LoanService loanService = engine.getLoanService();
        if (!loanService.isNumeraireConfigured())
            return CommandResultCode.LOAN_NUMERAIRE_NOT_CONFIGURED;
        final CommandResultCode poolCheck = verifyPoolCapacity(loanService, loanCurrency, principal);
        if (poolCheck != CommandResultCode.SUCCESS)
            return poolCheck;

        // Cross 恒 FLOATING
        final int openRateBps = loanService.getFloatingRate().openRateBps(loanCurrency);
        // 先落 loan 记账后重算 LTV，超线再 revert
        final CrossLoanRecord loan = engine.getObjectsPool().get(ObjectsPool.CROSS_LOAN_RECORD, CrossLoanRecord::new);
        loan.initialize(cmd.uid, loanId, spec.symbolId, loanCurrency, openRateBps, cmd.timestamp);
        loanService.getFloatingRate().initOpenSnapshot(loan, cmd.timestamp);
        loan.outstandingPrincipal = principal;
        up.crossLoans.put(loanId, loan);
        final long newLtv = loanService.calculateCrossAccountLtvBps(up, cmd.timestamp,
            engine.getSymbolSpecificationProvider(), engine.getCurrencySpecificationProvider(),
            engine.getLastPriceCache(), loanService.getGlobalConfig().numeraireCurrency, true);
        if (newLtv > spec.loanConfig.initialLtvBps) {
            up.crossLoans.remove(loanId);
            engine.getObjectsPool().put(ObjectsPool.CROSS_LOAN_RECORD, loan);
            return CommandResultCode.LOAN_LTV_TOO_HIGH_AFTER_BORROW;
        }
        disburseLoan(up, loanService, loanCurrency, principal);
        // Cross 抵押账户级：collateralCurrency=numeraire、collateralAmount 留 0
        final long loanLocked = engine.calculateLocked(up, loanCurrency);
        engine.getEventsHelper().sendLoanBorrowEvent(cmd, loan.uid, loanId, LOAN_MODE_CROSS, loanCurrency,
            0, loan.outstandingPrincipal, 0, newLtv,
            up.accounts.get(loanCurrency) - loanLocked, loanLocked, 0, 0);
        loanLiquidationEngine.syncCrossExposure(up);
        return CommandResultCode.SUCCESS;
    }

    /** 同 Isolated REPAY 但不释放抵押（Cross 抵押是账户级共享）。price=repayAmount。 */
    public CommandResultCode handleLoanCrossRepay(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;
        if (!up.processedTransactionIds.tryClaim(cmd.orderId, cmd.timestamp)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        final long loanId = cmd.reserveBidPrice;
        final CrossLoanRecord loan = up.crossLoans.get(loanId);
        if (loan == null)
            return CommandResultCode.LOAN_NOT_FOUND;
        if (loan.uid != cmd.uid)
            return CommandResultCode.LOAN_UID_MISMATCH;

        final CommandResultCode repayCode = settleRepay(up, loan, cmd);
        if (repayCode != CommandResultCode.SUCCESS)
            return repayCode;
        final int loanCurrency = loan.loanCurrency;
        final long loanLocked = engine.calculateLocked(up, loanCurrency);
        engine.getEventsHelper().sendLoanRepayEvent(cmd, loan.uid, loanId, LOAN_MODE_CROSS, loanCurrency,
            0, loan.outstandingPrincipal,
            loan.accumulatedInterest, 0, crossLtvBps(up, cmd.timestamp),
            loan.cumInterestPaid, up.accounts.get(loanCurrency) - loanLocked, loanLocked, 0, 0);

        if (loan.isEmpty()) {
            up.crossLoans.remove(loanId);
            engine.getObjectsPool().put(ObjectsPool.CROSS_LOAN_RECORD, loan);
        }
        loanLiquidationEngine.syncCrossExposure(up);
        return CommandResultCode.SUCCESS;
    }

    // ====================================================================================
    // Cross 强平：R1 挂 IOC → R2 结算，接不住由 LIF 按债务占比接管
    // ====================================================================================

    /**
     * preProcess：校验 + pre-move 卖出币抵押到 exchangeLocked，转成 spot ASK IOC 交撮合。
     * reserveBidPrice=targetLoanId / symbol=现货对(base=卖出币, quote=借款币) / size=卖出张数(lot)。
     */
    public CommandResultCode handleLoanCrossForceLiquidate(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;

        final long targetLoanId = cmd.reserveBidPrice;
        final CrossLoanRecord targetLoan = up.crossLoans.get(targetLoanId);
        if (targetLoan == null)
            return CommandResultCode.LOAN_NOT_FOUND;
        if (targetLoan.uid != cmd.uid)
            return CommandResultCode.LOAN_UID_MISMATCH;

        final CoreSymbolSpecification spec = engine.getSymbolSpecificationProvider().getSymbolSpecification(cmd.symbol);
        if (spec == null || spec.type != SymbolType.CURRENCY_EXCHANGE_PAIR
            || spec.quoteCurrency != targetLoan.loanCurrency)
            return CommandResultCode.LOAN_NOT_ENABLED;

        final int sellingCurrency = spec.baseCurrency;
        final long availableCollateral = up.crossLoanCollateral.get(sellingCurrency);

        final CoreCurrencySpecification sellingCurrencySpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(sellingCurrency);
        final long sellAmount = LoanService.lotsToCollateralAmount(cmd.size, spec, sellingCurrencySpec);
        if (sellAmount <= 0 || sellAmount > availableCollateral)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        up.crossLoanCollateral.addToValue(sellingCurrency, -sellAmount);
        up.exchangeLocked.addToValue(sellingCurrency, sellAmount);

        cmd.action = OrderAction.ASK;
        cmd.orderType = OrderType.IOC;
        return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
    }

    /**
     * postProcess：R2 结算后调用。REJECT 回填 crossLoanCollateral；TRADE 所得走 {@link LoanService#settleLiquidationProceeds} 偿
     * targetLoan；全部抵押无可卖整张且债务未清则由 LIF 承接。
     */
    public void postProcessLoanCrossForceLiquidate(OrderCommand cmd, CoreSymbolSpecification spec,
        UserProfile takerUp) {
        final long targetLoanId = cmd.reserveBidPrice;
        final CrossLoanRecord targetLoan = takerUp.crossLoans.get(targetLoanId);
        if (targetLoan == null) {
            log.warn("Cross target loan gone in postProcess: uid={} loanId={}", cmd.uid, targetLoanId);
            return;
        }
        final LoanService loanService = engine.getLoanService();
        final int sellingCurrency = spec.baseCurrency;
        final int loanCurrency = targetLoan.loanCurrency;
        final CoreCurrencySpecification loanCurrencySpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(loanCurrency);
        final CoreCurrencySpecification sellingCurrencySpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(sellingCurrency);

        long tradedSize = 0;
        long tradedNotional = 0;
        long rejectedSize = 0;
        for (MatcherTradeEvent ev = cmd.matcherEvent; ev != null; ev = ev.nextEvent) {
            if (ev.eventType == MatcherEventType.TRADE) {
                tradedSize += ev.size;
                tradedNotional += Math.multiplyExact(ev.size, ev.price);
            } else if (ev.eventType == MatcherEventType.REJECT) {
                rejectedSize += ev.size;
            }
        }

        // ────────────────────────────────────────────────────────────────────
        // ② REJECT 回填：spot handler 已释放 exchangeLocked，抵押归位到账户级抵押池保守恒
        // ────────────────────────────────────────────────────────────────────
        if (rejectedSize > 0) {
            long rejectedInCurrencyScale =
                CoreArithmeticUtils.symbolToCurrencyScale(rejectedSize, spec, sellingCurrencySpec);
            takerUp.crossLoanCollateral.addToValue(sellingCurrency, rejectedInCurrencyScale);
        }

        // early-capture：下方分支会把 targetLoan 归还对象池
        final long borrowerUid = targetLoan.uid;

        // ────────────────────────────────────────────────────────────────────
        // ③ TRADE 结算：所得扣 takerFee 后 → 强平费 → 利息 → 本金，overpay 留用户
        // ────────────────────────────────────────────────────────────────────
        if (tradedSize > 0) {
            final long avgTakerPrice = tradedNotional / tradedSize;
            final long takerFee = CoreArithmeticUtils.calculateTakerFee(tradedSize, avgTakerPrice, spec);
            final long receivedQuote =
                CoreArithmeticUtils.sizePriceToCurrencyScale(tradedNotional - takerFee, spec, loanCurrencySpec);
            loanService.settleLiquidationProceeds(targetLoan, takerUp.accounts, receivedQuote, cmd.timestamp);
        }

        loanService.accrueTo(targetLoan, cmd.timestamp); // 同 Isolated：全拒路径未结算过，补计后再判债务
        long remainTargetDebt = Math.addExact(targetLoan.outstandingPrincipal, targetLoan.accumulatedInterest);
        // ────────────────────────────────────────────────────────────────────
        // ④ 终态判定：抵押是否结构性耗尽 → 决定 targetLoan 与其余债的去向
        // ────────────────────────────────────────────────────────────────────
        // 只看结构上能否变现（与选币的永久性条件同源），markPrice 未就绪属临时状态不触发接管
        boolean allCollateralExhausted = true;
        for (int currency : takerUp.crossLoanCollateral.keySet().toArray()) {
            if (LoanService.isStructurallySellable(currency, takerUp.crossLoanCollateral.get(currency), takerUp,
                engine.getSymbolSpecificationProvider(), engine.getCurrencySpecificationProvider())) {
                allCollateralExhausted = false;
                break;
            }
        }
        boolean takenOver = false;
        long snapPrincipal = 0;
        long snapInterest = 0;
        // early-capture：下方分支会把 targetLoan 归还对象池
        long cumInterestPaid = targetLoan.cumInterestPaid;
        // 市场按破产价接不住（全拒），或抵押结构上已无法变现，而债务仍在 → LIF 按债务占比承接
        if (remainTargetDebt > 0 && (tradedSize == 0 || allCollateralExhausted)) {
            takenOver = loanService.takeOverCrossLoan(takerUp, targetLoan, cmd.timestamp,
                engine.getSymbolSpecificationProvider(), engine.getCurrencySpecificationProvider(),
                engine.getLastPriceCache());
            if (takenOver) {
                closeAndRecycleCrossLoan(takerUp, targetLoan, targetLoanId);
            } else {
                // 喂价缺失无法估值 → 保留 loan 原样等下一轮，不用失真价格扣用户抵押
                log.warn("Cross LIF takeover deferred, numeraire valuation unavailable: uid={} loanId={}", borrowerUid,
                    targetLoanId);
                snapPrincipal = targetLoan.outstandingPrincipal;
                snapInterest = targetLoan.accumulatedInterest;
            }
        } else if (targetLoan.outstandingPrincipal == 0 && targetLoan.accumulatedInterest == 0) {
            takerUp.crossLoans.remove(targetLoanId);
            engine.getObjectsPool().put(ObjectsPool.CROSS_LOAN_RECORD, targetLoan);
        } else {
            snapPrincipal = targetLoan.outstandingPrincipal;
            snapInterest = targetLoan.accumulatedInterest;
        }

        // ────────────────────────────────────────────────────────────────────
        // ⑤ 发事件：collateralCurrency=卖出币、collateralAmount=该币剩余，纯 no-op 跳过
        // ────────────────────────────────────────────────────────────────────
        if (tradedSize > 0 || takenOver)
        {
            final long loanLocked = engine.calculateLocked(takerUp, loanCurrency);
            final long colLocked = engine.calculateLocked(takerUp, sellingCurrency);
            engine.getEventsHelper().sendLoanLiquidatedEvent(cmd, borrowerUid, targetLoanId, LOAN_MODE_CROSS,
                loanCurrency, sellingCurrency, snapPrincipal, snapInterest,
                takerUp.crossLoanCollateral.get(sellingCurrency), crossLtvBps(takerUp, cmd.timestamp), cumInterestPaid,
                takerUp.accounts.get(loanCurrency) - loanLocked, loanLocked,
                takerUp.accounts.get(sellingCurrency) - colLocked, colLocked);
        }
        // 抵押结构性耗尽 → 账户其余未偿债务一并由 LIF 承接
        if (allCollateralExhausted) {
            takeOverRemainingCrossLoans(cmd, takerUp, targetLoanId, sellingCurrency);
        }
        loanLiquidationEngine.syncCrossExposure(takerUp);
    }

    /**
     * 抵押结构性耗尽时，把账户其余未偿 Cross 债务一并交给 LIF 承接：账户级抵押是共享的，一笔卖不掉其余同样卖不掉。
     * 每笔各发一条 {@code LOAN_LIQUIDATED}（事件按 loanId 归属，不能合并）；占比随剩余债务集合收敛，最后一笔承接全部残余抵押。
     * targetLoan 已在调用方尝试过，跳过以免重复告警。
     *
     * <p>按 loanId 升序遍历——每笔分到多少抵押取决于"轮到它时还剩多少债"，<b>顺序即状态</b>，
     * 与 {@link LoanService#takeOverCrossLoan} 里抵押币的排序同理，不可依赖哈希序。
     */
    private void takeOverRemainingCrossLoans(OrderCommand cmd, UserProfile up, long targetLoanId, int sellingCurrency) {
        final LoanService loanService = engine.getLoanService();
        final long[] loanIds = up.crossLoans.keySet().toArray(); // 先快照：循环内会 remove
        java.util.Arrays.sort(loanIds);
        for (long loanId : loanIds) {
            final CrossLoanRecord loan = up.crossLoans.get(loanId);
            if (loanId == targetLoanId || loan == null
                || (loan.outstandingPrincipal == 0 && loan.accumulatedInterest == 0)) {
                continue;
            }
            // early-capture：closeAndRecycleCrossLoan 会归还对象池
            final int loanCurrency = loan.loanCurrency;
            final long cumInterestPaid = loan.cumInterestPaid;
            if (!loanService.takeOverCrossLoan(up, loan, cmd.timestamp, engine.getSymbolSpecificationProvider(),
                engine.getCurrencySpecificationProvider(), engine.getLastPriceCache())) {
                log.warn("Cross LIF takeover deferred, numeraire valuation unavailable: uid={} loanId={}", up.uid,
                    loanId);
                continue;
            }
            closeAndRecycleCrossLoan(up, loan, loanId);

            final long loanLocked = engine.calculateLocked(up, loanCurrency);
            final long colLocked = engine.calculateLocked(up, sellingCurrency);
            engine.getEventsHelper().sendLoanLiquidatedEvent(cmd, up.uid, loanId, LOAN_MODE_CROSS, loanCurrency,
                sellingCurrency, 0, 0, up.crossLoanCollateral.get(sellingCurrency),
                crossLtvBps(up, cmd.timestamp), cumInterestPaid,
                up.accounts.get(loanCurrency) - loanLocked, loanLocked,
                up.accounts.get(sellingCurrency) - colLocked, colLocked);
        }
    }

    /** LIF 承接后收尾：债务清零、摘出账户、回收对象池。调用后 loan 已归池，不可再读。 */
    private void closeAndRecycleCrossLoan(UserProfile up, CrossLoanRecord loan, long loanId) {
        loan.outstandingPrincipal = 0;
        loan.accumulatedInterest = 0;
        up.crossLoans.remove(loanId);
        engine.getObjectsPool().put(ObjectsPool.CROSS_LOAN_RECORD, loan);
    }

    /**
     * LIF 承接不良贷款：按债务全额代偿，取走全部抵押。
     *
     * <p>市场按破产价都接不住时的终局——池子立刻回血、债务终结、借款人不再计息，流动性风险转由 LIF 长期消化。
     * 因抵押账面价按破产价恰为债务额，LIF 等于以成本价买下抵押，名义不亏，只承担日后变现的价格风险。
     *
     * <p>LIF <b>允许为负</b>：负值即平台已垫资金额，而非损失——损失只在处置抵押库存时实现。抵押原为虚拟锁定，
     * 此处从 {@code accounts} 真实扣走转入 LIF。
     */
    private void takeOverByInsuranceFund(UserProfile up, long principal, long interest, int loanCurrency,
        int collateralCurrency, long collateral) {
        final LoanService loanService = engine.getLoanService();
        final long debt = Math.addExact(principal, interest);
        loanService.getLoanInsuranceFund().addToValue(loanCurrency, -debt);
        loanService.getLoanPoolAvailable().addToValue(loanCurrency, principal);
        loanService.getLoanPoolBorrowed().addToValue(loanCurrency, -principal);
        loanService.getInterestRevenue().addToValue(loanCurrency, interest);
        if (collateral > 0) {
            up.accounts.addToValue(collateralCurrency, -collateral);
            loanService.getLoanInsuranceFund().addToValue(collateralCurrency, collateral);
        }
    }

    // ====================================================================================
    // 运营命令：借贷池 / LIF 充提（各 shard 都跑，内部按 shardId 短路）
    // ====================================================================================

    /** 运营方注入池子流动性。uid=shardId（非真实 uid）/ symbol=currency / size=amount；非 target shard 静默 SUCCESS 短路。 */
    public CommandResultCode handlePoolDeposit(OrderCommand cmd) {
        if ((int)cmd.uid != engine.getShardId())
            return CommandResultCode.SUCCESS;
        if (cmd.size <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        final LoanService loanService = engine.getLoanService();
        loanService.getLoanPoolAvailable().addToValue(cmd.symbol, cmd.size);
        engine.getAdjustments().addToValue(cmd.symbol, -cmd.size);
        return CommandResultCode.SUCCESS;
    }

    /** 运营方从池子提取流动性（需 available 充足）。字段同 POOL_DEPOSIT。 */
    public CommandResultCode handlePoolWithdraw(OrderCommand cmd) {
        if ((int)cmd.uid != engine.getShardId())
            return CommandResultCode.SUCCESS;
        if (cmd.size <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        final LoanService loanService = engine.getLoanService();
        if (loanService.getLoanPoolAvailable().get(cmd.symbol) < cmd.size)
            return CommandResultCode.LOAN_POOL_INSUFFICIENT;

        loanService.getLoanPoolAvailable().addToValue(cmd.symbol, -cmd.size);
        engine.getAdjustments().addToValue(cmd.symbol, cmd.size);
        return CommandResultCode.SUCCESS;
    }

    /** 运营方给 LIF 注资（垫付启动资金 / 接管后补仓）。字段同 POOL_DEPOSIT。 */
    public CommandResultCode handleLoanIfDeposit(OrderCommand cmd) {
        if ((int)cmd.uid != engine.getShardId())
            return CommandResultCode.SUCCESS;
        if (cmd.size <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        engine.getLoanService().getLoanInsuranceFund().addToValue(cmd.symbol, cmd.size);
        engine.getAdjustments().addToValue(cmd.symbol, -cmd.size);
        return CommandResultCode.SUCCESS;
    }

    /**
     * 运营方从 LIF 提取（处置接管来的抵押库存，场外变现后再 deposit 回来）。字段同 POOL_DEPOSIT。
     * 余额不足即拒：LIF 允许为负是接管的被动结果，不是运营可主动透支的额度。
     */
    public CommandResultCode handleLoanIfWithdraw(OrderCommand cmd) {
        if ((int)cmd.uid != engine.getShardId())
            return CommandResultCode.SUCCESS;
        if (cmd.size <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        final LoanService loanService = engine.getLoanService();
        if (loanService.getLoanInsuranceFund().get(cmd.symbol) < cmd.size)
            return CommandResultCode.LOAN_IF_INSUFFICIENT;

        loanService.getLoanInsuranceFund().addToValue(cmd.symbol, -cmd.size);
        engine.getAdjustments().addToValue(cmd.symbol, cmd.size);
        return CommandResultCode.SUCCESS;
    }

    // ====================================================================================
    // 内部工具：估值 / LTV / 池容量 / 放款
    // ====================================================================================

    /** markPrice，缺失返回 0（caller 走 LOAN_MARKPRICE_NOT_READY）。 */
    private long markPriceOrZero(int symbolId) {
        LastPriceCacheRecord record = engine.getLastPriceCache().get(symbolId);
        return record == null ? 0L : record.markPrice;
    }

    private long evalCollateralInLoanCurrency(long amount, CoreSymbolSpecification spec, long markPrice) {
        CoreCurrencySpecification baseSpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(spec.baseCurrency);
        CoreCurrencySpecification quoteSpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(spec.quoteCurrency);
        return LoanService.collateralValueInQuoteCurrency(amount, spec, markPrice, baseSpec, quoteSpec);
    }

    /** Isolated 单笔 LTV（bps），事件用 best-effort：markPrice/spec 缺失或估值 &le; 0 返 0。 */
    private long isolatedLtvBps(IsolatedLoanRecord loan, long now) {
        final CoreSymbolSpecification spec =
            engine.getSymbolSpecificationProvider().getSymbolSpecification(loan.symbolId);
        if (spec == null)
            return 0L;
        final long markPrice = markPriceOrZero(spec.symbolId);
        if (markPrice <= 0)
            return 0L;
        final long collateralValueInLoanCurrency = evalCollateralInLoanCurrency(loan.collateralAmount, spec, markPrice);
        if (collateralValueInLoanCurrency <= 0)
            return 0L;
        final long realDebt =
            Math.addExact(loan.outstandingPrincipal, engine.getLoanService().calculateDisplayInterest(loan, now));
        return Math.multiplyExact(realDebt, LoanService.BPS_SCALE) / collateralValueInLoanCurrency;
    }

    /** Cross 账户级 LTV（bps），事件用 best-effort：numeraire 未配置返 0。 */
    private long crossLtvBps(UserProfile up, long now) {
        final LoanService loanService = engine.getLoanService();
        if (!loanService.isNumeraireConfigured())
            return 0L;
        return loanService.calculateCrossAccountLtvBps(up, now, engine.getSymbolSpecificationProvider(),
            engine.getCurrencySpecificationProvider(), engine.getLastPriceCache(),
            loanService.getGlobalConfig().numeraireCurrency);
    }

    /** 池子容量+利用率校验（CREATE 与 CROSS_BORROW 共用）。 */
    private static CommandResultCode verifyPoolCapacity(LoanService loanService, int loanCurrency, long principal) {
        final long available = loanService.getLoanPoolAvailable().get(loanCurrency);
        final long borrowed = loanService.getLoanPoolBorrowed().get(loanCurrency);
        if (available < principal)
            return CommandResultCode.LOAN_POOL_INSUFFICIENT;
        final long newBorrowed = Math.addExact(borrowed, principal);
        final long totalPool = Math.addExact(available, borrowed);
        // utilization = newBorrowed / totalPool，两边同放大 BPS_SCALE 比较，避免除法精度损失
        if (totalPool > 0) {
            final long newUtilizationScaled = Math.multiplyExact(newBorrowed, LoanService.BPS_SCALE);
            final long utilizationCapScaled =
                Math.multiplyExact(totalPool, (long)loanService.getGlobalConfig().loanPoolUtilizationCapBps);
            if (newUtilizationScaled > utilizationCapScaled) {
                return CommandResultCode.LOAN_POOL_UTILIZATION_EXCEEDED;
            }
        }
        return CommandResultCode.SUCCESS;
    }

    /** 借款划账：pool available → user account；pool borrowed 记账 +principal。 */
    private static void disburseLoan(UserProfile up, LoanService loanService, int loanCurrency, long principal) {
        up.accounts.addToValue(loanCurrency, principal);
        loanService.getLoanPoolAvailable().addToValue(loanCurrency, -principal);
        loanService.getLoanPoolBorrowed().addToValue(loanCurrency, principal);
    }

}
