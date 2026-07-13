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
import exchange.core2.core.common.MatcherEventType;
import exchange.core2.core.common.MatcherTradeEvent;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.common.cmd.OrderCommandType;
import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * 现货借贷命令处理器 —— per-shard 实例，由 {@link RiskEngine#preProcessCommand} 对 {@code cmd.command.isLoan()} 命中的命令整块委托
 * {@link #dispatch}。负责 13 条 loan 命令的校验 + 状态转移、shard filter、POOL 短路， 以及 force-sell 的 preProcess（pre-move 抵押到
 * exchangeLocked）/ postProcess（R2 结算后路由 proceeds）。 state 与金钱逻辑在 {@link LoanService}，scanner 在
 * {@link LoanLiquidationEngine}。
 *
 * <p>
 * OrderCommand 字段复用约定：{@code orderId}=externalId（幂等 key）/ {@code reserveBidPrice}=loanId / {@code uid}=用户 uid 或 POOL
 * 命令的 shardId / {@code symbol}=symbolId 或 currency / {@code size}=金额或 force-sell 张数 / {@code price}=principal 或
 * repayAmount / {@code timestamp}=accrue 基准（ms）。各 handler 头部再具体标注。
 */
@Slf4j
public final class LoanCommandHandlers {

    private static final byte LOAN_MODE_ISOLATED = 0;
    private static final byte LOAN_MODE_CROSS = 1;

    private final RiskEngine engine;

    public LoanCommandHandlers(RiskEngine engine) {
        this.engine = engine;
    }

    // ================================================================
    // 子域 dispatch —— RiskEngine.preProcessCommand 首行直接 delegate
    // 用户维度命令走 uidForThisHandler shard filter；POOL_* 走 (int)cmd.uid == shardId self-filter。
    // ================================================================

    public void dispatch(OrderCommand cmd) {
        switch (cmd.command) {
            // Isolated
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
            // Cross
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
            // Pool ops：cmd.uid 承载 shardId，所有 shard 都跑 handle（内部短路），但只 target shard 写 cmd.resultCode
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
            case POOL_ABSORB_BAD_DEBT: {
                final CommandResultCode rc = handlePoolAbsorbBadDebt(cmd);
                if ((int)cmd.uid == engine.getShardId())
                    cmd.resultCode = rc;
                return;
            }
            default:
                // 不可达 —— RiskEngine.preProcessCommand 用 isLoan() 门守，非 loan 命令不会进来
                throw new IllegalStateException("Non-loan command dispatched to LoanCommandHandlers: " + cmd.command);
        }
    }

    // ================================================================================================================
    // Isolated 借贷 handler
    // ================================================================================================================

    /**
     * LOAN_CREATE。 字段：uid / reserveBidPrice=loanId / symbol=symbolId / size=collateralAmount / price=principal /
     * orderId=externalId
     */
    public CommandResultCode handleLoanCreate(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;
        // tryClaim early：跟 MARGIN_ADJUSTMENT / BALANCE_ADJUSTMENT 一致（claim 后失败也不释放 id，客户端重试需换新 externalId）
        if (!up.processedExternalIds.tryClaim(cmd.orderId)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        final CoreSymbolSpecification spec = engine.getSymbolSpecificationProvider().getSymbolSpecification(cmd.symbol);
        if (spec == null || spec.type != SymbolType.CURRENCY_EXCHANGE_PAIR)
            return CommandResultCode.LOAN_NOT_ENABLED;
        if (spec.loanInitialLtvBps <= 0)
            return CommandResultCode.LOAN_NOT_ENABLED;

        final long loanId = cmd.reserveBidPrice;
        if (up.isolatedLoans.containsKey(loanId))
            return CommandResultCode.LOAN_ALREADY_EXISTS;

        final long collateralAmount = cmd.size;
        final long principal = cmd.price;
        if (principal <= 0 || collateralAmount <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;
        if (spec.loanMaxAmount != 0 && principal > spec.loanMaxAmount)
            return CommandResultCode.LOAN_PRINCIPAL_EXCEEDS_LIMIT;

        final long markPrice = markPriceOrZero(spec.symbolId);
        if (markPrice <= 0)
            return CommandResultCode.LOAN_MARKPRICE_NOT_READY;

        // LTV：principal × 10000 ≤ collateralValueInLoanCurrency × loanInitialLtvBps
        // 单位统一（都在 loanCurrency currencyScale 下比较），避免原来 currencyScale(loanCurrency) 跟 sizePriceScale 混算 → 恒为 pass 的 bug
        final long collateralValueInLoanCurrency = evalCollateralInLoanCurrency(collateralAmount, spec, markPrice);
        if (collateralValueInLoanCurrency < 0)
            return CommandResultCode.LOAN_MARKPRICE_NOT_READY;
        final long lhs = Math.multiplyExact(principal, LoanService.BPS_SCALE);
        final long rhs = Math.multiplyExact(collateralValueInLoanCurrency, (long)spec.loanInitialLtvBps);
        if (lhs > rhs)
            return CommandResultCode.LOAN_LTV_TOO_HIGH;

        // 抵押可用（走 calculateLocked，扩展后自动含 futures margin / exchangeLocked / 其他 loan 抵押）
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

        // v1 利率直接从 spec.loanRateBps snapshot 到 loan，之后不变；v2 支持动态利率时替换
        final IsolatedLoanRecord loan =
            engine.getObjectsPool().get(ObjectsPool.ISOLATED_LOAN_RECORD, IsolatedLoanRecord::new);
        loan.initialize(cmd.uid, loanId, collateralCurrency, loanCurrency, spec.loanRateBps, cmd.timestamp);
        loan.symbolId = spec.symbolId;
        loan.collateralAmount = collateralAmount;
        loan.outstandingPrincipal = principal;
        up.isolatedLoans.put(loanId, loan);
        disburseLoan(up, loanService, loanCurrency, principal);
        engine.getEventsHelper().sendLoanBorrowEvent(cmd, loan.uid, loanId, LOAN_MODE_ISOLATED, loanCurrency,
            collateralCurrency, loan.outstandingPrincipal, loan.collateralAmount, loan.getRateBps(),
            isolatedLtvBps(loan, cmd.timestamp), principal, collateralAmount);
        return CommandResultCode.SUCCESS;
    }

    /**
     * LOAN_REPAY。 字段：uid / reserveBidPrice=loanId / price=repayAmount（0 = payoff full）/ orderId=externalId
     */
    public CommandResultCode handleLoanRepay(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;
        if (!up.processedExternalIds.tryClaim(cmd.orderId)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        final long loanId = cmd.reserveBidPrice;
        final IsolatedLoanRecord loan = up.isolatedLoans.get(loanId);
        if (loan == null)
            return CommandResultCode.LOAN_NOT_FOUND;
        if (loan.uid != cmd.uid)
            return CommandResultCode.LOAN_UID_MISMATCH;

        final long requestedRepay = cmd.price;
        if (requestedRepay < 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        final LoanService loanService = engine.getLoanService();
        loanService.accrueTo(loan, cmd.timestamp);
        final long payoff = Math.addExact(loan.outstandingPrincipal, loan.accumulatedInterest);
        final long actualRepay = (requestedRepay == 0 || requestedRepay >= payoff) ? payoff : requestedRepay;

        final int loanCurrency = loan.loanCurrency;
        final long free = up.accounts.get(loanCurrency) - engine.calculateLocked(up, loanCurrency);
        if (free < actualRepay)
            return CommandResultCode.LOAN_ACCOUNT_INSUFFICIENT;

        final long interestSettled = loanService.applyDebtPayment(loan, up.accounts, actualRepay);
        // 快照须读操作后、放回对象池前的 loan
        final long principalRepaid = actualRepay - interestSettled;
        engine.getEventsHelper().sendLoanRepayEvent(cmd, loan.uid, loanId, LOAN_MODE_ISOLATED, loanCurrency,
            loan.collateralCurrency, loan.outstandingPrincipal, loan.accumulatedInterest, loan.collateralAmount,
            loan.getRateBps(), isolatedLtvBps(loan, cmd.timestamp), -principalRepaid, interestSettled);

        if (loan.isEmpty()) {
            up.isolatedLoans.remove(loanId);
            engine.getObjectsPool().put(ObjectsPool.ISOLATED_LOAN_RECORD, loan);
        }
        return CommandResultCode.SUCCESS;
    }

    /**
     * LOAN_ADD_COLLATERAL—— 补抵押降 LTV。 字段：uid / reserveBidPrice=loanId / size=amount / orderId=externalId
     */
    public CommandResultCode handleLoanAddCollateral(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;
        if (!up.processedExternalIds.tryClaim(cmd.orderId)) {
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

        loan.collateralAmount = Math.addExact(loan.collateralAmount, amount);
        engine.getEventsHelper().sendLoanCollateralChangeEvent(cmd, loan.uid, loanId, LOAN_MODE_ISOLATED,
            loan.loanCurrency, loan.collateralCurrency, loan.outstandingPrincipal, loan.accumulatedInterest,
            loan.collateralAmount, loan.getRateBps(), isolatedLtvBps(loan, cmd.timestamp), amount);
        return CommandResultCode.SUCCESS;
    }

    /**
     * LOAN_RELEASE_COLLATERAL—— 减抵押；允许操作到 marginCall 上方，拒绝直接撤到强平线。 字段：uid / reserveBidPrice=loanId / size=amount /
     * orderId=externalId
     */
    public CommandResultCode handleLoanReleaseCollateral(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;
        if (!up.processedExternalIds.tryClaim(cmd.orderId)) {
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

        // 减后 LTV < liquidation 才允许（用户风险自负；对齐 Binance Margin）
        // 真实债务含 accumulatedInterest + pending，避免高利率下 principal 视角低估 LTV
        final long realDebt = Math.addExact(loan.outstandingPrincipal,
            engine.getLoanService().calculateDisplayInterest(loan, cmd.timestamp));
        final long newCollateral = loan.collateralAmount - amount;
        if (newCollateral == 0 && realDebt > 0) {
            return CommandResultCode.LOAN_LTV_TOO_HIGH_AFTER_RELEASE;
        }
        if (newCollateral > 0) {
            // 走 evalCollateralInLoanCurrency 换算到 loanCurrency currencyScale，跟 realDebt 同单位比较
            final long newCollateralValueInLoanCurrency = evalCollateralInLoanCurrency(newCollateral, spec, markPrice);
            if (newCollateralValueInLoanCurrency < 0)
                return CommandResultCode.LOAN_MARKPRICE_NOT_READY;
            final long lhs = Math.multiplyExact(realDebt, LoanService.BPS_SCALE);
            final long rhs = Math.multiplyExact(newCollateralValueInLoanCurrency, (long)spec.loanLiquidationLtvBps);
            if (lhs >= rhs)
                return CommandResultCode.LOAN_LTV_TOO_HIGH_AFTER_RELEASE;
        }

        loan.collateralAmount = newCollateral;
        // 快照须读操作后、放回对象池前的 loan；减抵押 delta = -amount
        engine.getEventsHelper().sendLoanCollateralChangeEvent(cmd, loan.uid, loanId, LOAN_MODE_ISOLATED,
            loan.loanCurrency, loan.collateralCurrency, loan.outstandingPrincipal, loan.accumulatedInterest,
            loan.collateralAmount, loan.getRateBps(), isolatedLtvBps(loan, cmd.timestamp), -amount);
        // 全零死壳清理：REPAY 已 payoff（princ+int=0）且本次 RELEASE 减到 collateralAmount=0
        // → 移除 loan record 并归还对象池，让同 loanId 可被 LOAN_CREATE 复用（否则 handleLoanCreate 里 containsKey 卡住）
        if (loan.isEmpty()) {
            up.isolatedLoans.remove(loanId);
            engine.getObjectsPool().put(ObjectsPool.ISOLATED_LOAN_RECORD, loan);
        }
        return CommandResultCode.SUCCESS;
    }

    /**
     * LOAN_FORCE_LIQUIDATE preProcess —— 校验 + pre-move 抵押到 exchangeLocked，转成标准 spot ASK IOC 交撮合。
     * 字段：reserveBidPrice=loanId / symbol=spotSymbolId / size=卖出张数(lot) / price=IOC 限价。 REJECT 部分由
     * {@link #postProcessLoanForceLiquidate} 回填 collateralAmount。
     */
    public CommandResultCode handleLoanForceLiquidate(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        // 不拦 SUSPENDED：force-liquidate 是系统风控动作，suspend 只该挡用户主动命令。
        // 若拦，suspended 用户的 underwater loan 永远清不掉 → 坏账累积 + scanner 无限重发。

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

        // cmd.size 是撮合张数（lot）；换回抵押金额（currencyScale）再记账，与结算/reject 释放同尺
        final CoreCurrencySpecification collateralSpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(loan.collateralCurrency);
        final long sellAmount = LoanService.lotsToCollateralAmount(cmd.size, spec, collateralSpec);
        if (sellAmount <= 0 || sellAmount > loan.collateralAmount)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        // Pre-move: 从 loan 记账挪到 exchangeLocked
        loan.collateralAmount -= sellAmount;
        up.exchangeLocked.addToValue(loan.collateralCurrency, sellAmount);

        // 让 orderbook 按标准 spot ASK IOC 撮合
        cmd.action = OrderAction.ASK;
        cmd.orderType = OrderType.IOC;
        return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
    }

    /**
     * LOAN_FORCE_LIQUIDATE postProcess —— {@code RiskEngine.handlerRiskRelease} 结算完 spot ASK 后调用（owner shard）。 REJECT
     * 回填 collateralAmount；TRADE 所得走 {@link LoanService#settleLiquidationProceeds}；抵押只剩尘埃且 债务未清则并入 badDebt 核销。
     */
    public void postProcessLoanForceLiquidate(OrderCommand cmd, CoreSymbolSpecification spec, UserProfile takerUp) {
        final long loanId = cmd.reserveBidPrice;
        final IsolatedLoanRecord loan = takerUp.isolatedLoans.get(loanId);
        if (loan == null) {
            log.warn("Loan gone in postProcess: uid={} loanId={}", cmd.uid, loanId);
            return;
        }
        final LoanService loanService = engine.getLoanService();
        final int loanCurrency = loan.loanCurrency;
        final CoreCurrencySpecification loanCurrencySpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(loanCurrency);
        final CoreCurrencySpecification baseSpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(loan.collateralCurrency);

        // 累加 trade / reject 量（symbol scale）
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

        // REJECT 部分：spot handler 已把 exchangeLocked[base] 释放回用户，回填 loan.collateralAmount 保守恒
        if (rejectedSize > 0) {
            long rejectedInCurrencyScale = CoreArithmeticUtils.symbolToCurrencyScale(rejectedSize, spec, baseSpec);
            loan.collateralAmount = Math.addExact(loan.collateralAmount, rejectedInCurrencyScale);
        }

        // 事件身份 early-capture（下方分支可能 recycle loan 对象，之后字段被重置）
        final long borrowerUid = loan.uid;
        final int rateBps = loan.getRateBps();
        final int collateralCurrency = loan.collateralCurrency;
        final long principalBeforeSettle = loan.outstandingPrincipal;

        // TRADE：卖出所得（扣撮合 takerFee）→ settleLiquidationProceeds（强平费 + 抵债，overpay 留用户）
        long interestPaid = 0;
        long collateralSold = 0; // 本次卖出抵押（loanCurrency 无关，用 base currency scale）
        if (tradedSize > 0) {
            final long avgTakerPrice = tradedNotional / tradedSize;
            final long takerFee = CoreArithmeticUtils.calculateTakerFee(tradedSize, avgTakerPrice, spec);
            final long receivedQuote =
                CoreArithmeticUtils.sizePriceToCurrencyScale(tradedNotional - takerFee, spec, loanCurrencySpec);
            interestPaid = loanService.settleLiquidationProceeds(loan, takerUp.accounts, receivedQuote, cmd.timestamp);
            collateralSold = CoreArithmeticUtils.symbolToCurrencyScale(tradedSize, spec, baseSpec);
        }
        final long principalRepaid = principalBeforeSettle - loan.outstandingPrincipal;

        long remainDebt = Math.addExact(loan.outstandingPrincipal, loan.accumulatedInterest);
        // Underwater 判定看"是否还有可卖的整张"，而非 collateralAmount==0：sub-lot 尘埃卖不掉，
        // 若还按 ==0 判会漏记 badDebt + loan 悬挂 + scanner 空转。
        long sellableLots = LoanService.collateralAmountToLots(loan.collateralAmount, spec, baseSpec);
        // loan 核销/清空的两个分支快照全 0；仅保留分支填当前值
        long badDebt = 0;
        long snapPrincipal = 0, snapInterest = 0, snapCollateral = 0, snapLtvBps = 0;
        if (sellableLots == 0 && remainDebt > 0) {
            // 抵押只剩不足一张的尘埃且债务未清 → 剩余债务写 badDebt，尘埃释放回用户，清空 loan
            badDebt = remainDebt;
            loanService.getBadDebt().addToValue(loanCurrency, remainDebt);
            loanService.getLoanPoolBorrowed().addToValue(loanCurrency, -loan.outstandingPrincipal);
            loan.outstandingPrincipal = 0;
            loan.accumulatedInterest = 0;
            loan.collateralAmount = 0;
            takerUp.isolatedLoans.remove(loanId);
            engine.getObjectsPool().put(ObjectsPool.ISOLATED_LOAN_RECORD, loan);
        } else if (loan.isEmpty()) {
            takerUp.isolatedLoans.remove(loanId);
            engine.getObjectsPool().put(ObjectsPool.ISOLATED_LOAN_RECORD, loan);
        } else {
            // loan 保留：卡单计数——零成交(打不进)则 +1 让 scanner 爬容差/告警；有成交则清 0
            loan.stuckLiqAttempts = tradedSize > 0 ? 0 : loan.stuckLiqAttempts + 1;
            snapPrincipal = loan.outstandingPrincipal;
            snapInterest = loan.accumulatedInterest;
            snapCollateral = loan.collateralAmount;
            snapLtvBps = isolatedLtvBps(loan, cmd.timestamp);
        }
        // 卡单零成交且无坏账 → 本轮啥也没发生，跳过 no-op 事件（scanner 会周期重试）
        if (tradedSize > 0 || badDebt > 0)
            engine.getEventsHelper().sendLoanLiquidatedEvent(cmd, borrowerUid, loanId, LOAN_MODE_ISOLATED, loanCurrency,
                collateralCurrency, snapPrincipal, snapInterest, snapCollateral, rateBps, snapLtvBps, -principalRepaid,
                -collateralSold, interestPaid, badDebt);
    }

    // ================================================================================================================
    // Cross 借贷 handler
    // ================================================================================================================

    /**
     * LOAN_CROSS_ADD_COLLATERAL。 字段：uid / symbol=currency / size=amount / orderId=externalId
     */
    public CommandResultCode handleLoanCrossAddCollateral(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;
        if (!up.processedExternalIds.tryClaim(cmd.orderId)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        final int currency = cmd.symbol;
        final long amount = cmd.size;
        if (amount <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        if (!isCollateralAllowed(currency))
            return CommandResultCode.LOAN_COLLATERAL_NOT_ALLOWED;

        final long free = up.accounts.get(currency) - engine.calculateLocked(up, currency);
        if (free < amount)
            return CommandResultCode.LOAN_COLLATERAL_INSUFFICIENT;

        up.crossLoanCollateral.addToValue(currency, amount);
        // Cross 账户级抵押变动：loanId=0、debt 字段留 0；collateralAmount=该币新余额
        engine.getEventsHelper().sendLoanCollateralChangeEvent(cmd, up.uid, 0L, LOAN_MODE_CROSS, 0, currency, 0, 0,
            up.crossLoanCollateral.get(currency), 0, crossLtvBps(up, cmd.timestamp), amount);
        return CommandResultCode.SUCCESS;
    }

    /**
     * LOAN_CROSS_WITHDRAW_COLLATERAL。 字段：uid / symbol=currency / size=amount / orderId=externalId
     * <p>
     * 撤后账户级 LTV ≥ crossLiquidationLtvBps 则 revert；numeraire 未配置时 LTV 恒 0，等效于不拦截。
     */
    public CommandResultCode handleLoanCrossWithdrawCollateral(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;
        if (!up.processedExternalIds.tryClaim(cmd.orderId)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        final int currency = cmd.symbol;
        final long amount = cmd.size;
        if (amount <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;
        if (up.crossLoanCollateral.get(currency) < amount)
            return CommandResultCode.LOAN_COLLATERAL_EXCEEDS_LOAN;

        // 撤后账户级 LTV < crossLiquidationLtvBps 才允许（模拟撤走后重算；用户风险自负，允许操作到 marginCall 上方）
        final LoanService loanService = engine.getLoanService();
        if (!loanService.isNumeraireConfigured())
            return CommandResultCode.LOAN_NUMERAIRE_NOT_CONFIGURED;
        up.crossLoanCollateral.addToValue(currency, -amount);
        final long newLtv = loanService.calculateCrossAccountLtvBps(up, cmd.timestamp,
            engine.getSymbolSpecificationProvider(), engine.getCurrencySpecificationProvider(),
            engine.getLastPriceCache(), loanService.getNumeraireCurrency());
        if (newLtv >= loanService.getCrossLiquidationLtvBps()) {
            up.crossLoanCollateral.addToValue(currency, amount); // revert
            return CommandResultCode.LOAN_CROSS_LTV_TOO_HIGH_AFTER_WITHDRAW;
        }
        // Cross 账户级抵押变动：loanId=0、debt 字段留 0；collateralAmount=该币新余额
        engine.getEventsHelper().sendLoanCollateralChangeEvent(cmd, up.uid, 0L, LOAN_MODE_CROSS, 0, currency, 0, 0,
            up.crossLoanCollateral.get(currency), 0, newLtv, -amount);
        return CommandResultCode.SUCCESS;
    }

    /**
     * LOAN_CROSS_BORROW。 字段：uid / reserveBidPrice=loanId / symbol=loanCurrency / price=principal / orderId=externalId
     * <p>
     * 借后账户级 LTV > loanInitialLtvBps 则 revert；numeraire 未配置时 LTV 恒 0，等效于不拦截。
     */
    public CommandResultCode handleLoanCrossBorrow(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;
        if (!up.processedExternalIds.tryClaim(cmd.orderId)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        final long loanId = cmd.reserveBidPrice;
        if (up.crossLoans.containsKey(loanId))
            return CommandResultCode.LOAN_ALREADY_EXISTS;

        final int loanCurrency = cmd.symbol;
        final long principal = cmd.price;
        if (principal <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        final CoreSymbolSpecification spec = findLoanSpecByQuoteCurrency(loanCurrency);
        if (spec == null || spec.loanInitialLtvBps <= 0)
            return CommandResultCode.LOAN_NOT_ENABLED;
        if (spec.loanMaxAmount != 0 && principal > spec.loanMaxAmount)
            return CommandResultCode.LOAN_PRINCIPAL_EXCEEDS_LIMIT;

        final LoanService loanService = engine.getLoanService();
        if (!loanService.isNumeraireConfigured())
            return CommandResultCode.LOAN_NUMERAIRE_NOT_CONFIGURED;
        final CommandResultCode poolCheck = verifyPoolCapacity(loanService, loanCurrency, principal);
        if (poolCheck != CommandResultCode.SUCCESS)
            return poolCheck;

        // 借后账户级 LTV ≤ loanInitialLtvBps 才允许——先落 loan 记账后调 calculateCrossAccountLtvBps，超线再 revert
        final CrossLoanRecord loan = engine.getObjectsPool().get(ObjectsPool.CROSS_LOAN_RECORD, CrossLoanRecord::new);
        loan.initialize(cmd.uid, loanId, loanCurrency, spec.loanRateBps, cmd.timestamp);
        loan.symbolId = spec.symbolId;
        loan.outstandingPrincipal = principal;
        up.crossLoans.put(loanId, loan);
        final long newLtv = loanService.calculateCrossAccountLtvBps(up, cmd.timestamp,
            engine.getSymbolSpecificationProvider(), engine.getCurrencySpecificationProvider(),
            engine.getLastPriceCache(), loanService.getNumeraireCurrency());
        if (newLtv > spec.loanInitialLtvBps) {
            up.crossLoans.remove(loanId);
            engine.getObjectsPool().put(ObjectsPool.CROSS_LOAN_RECORD, loan);
            return CommandResultCode.LOAN_LTV_TOO_HIGH_AFTER_BORROW;
        }
        disburseLoan(up, loanService, loanCurrency, principal);
        // Cross 抵押是账户级：collateralCurrency=numeraire、collateralAmount 留 0（详情查 LoanPlatformReport / SingleUserReport）
        engine.getEventsHelper().sendLoanBorrowEvent(cmd, loan.uid, loanId, LOAN_MODE_CROSS, loanCurrency,
            loanService.getNumeraireCurrency(), loan.outstandingPrincipal, 0, loan.getRateBps(), newLtv, principal, 0);
        return CommandResultCode.SUCCESS;
    }

    /**
     * LOAN_CROSS_REPAY—— 逻辑同 Isolated REPAY，但不释放抵押。 字段：uid / reserveBidPrice=loanId / price=repayAmount /
     * orderId=externalId
     */
    public CommandResultCode handleLoanCrossRepay(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;
        if (!up.processedExternalIds.tryClaim(cmd.orderId)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        final long loanId = cmd.reserveBidPrice;
        final CrossLoanRecord loan = up.crossLoans.get(loanId);
        if (loan == null)
            return CommandResultCode.LOAN_NOT_FOUND;
        if (loan.uid != cmd.uid)
            return CommandResultCode.LOAN_UID_MISMATCH;

        final long requestedRepay = cmd.price;
        if (requestedRepay < 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        final LoanService loanService = engine.getLoanService();
        loanService.accrueTo(loan, cmd.timestamp);
        final long payoff = Math.addExact(loan.outstandingPrincipal, loan.accumulatedInterest);
        final long actualRepay = (requestedRepay == 0 || requestedRepay >= payoff) ? payoff : requestedRepay;

        final int loanCurrency = loan.loanCurrency;
        final long free = up.accounts.get(loanCurrency) - engine.calculateLocked(up, loanCurrency);
        if (free < actualRepay)
            return CommandResultCode.LOAN_ACCOUNT_INSUFFICIENT;

        final long interestSettled = loanService.applyDebtPayment(loan, up.accounts, actualRepay);
        // Cross 抵押账户级：collateralAmount 留 0
        final long principalRepaid = actualRepay - interestSettled;
        engine.getEventsHelper().sendLoanRepayEvent(cmd, loan.uid, loanId, LOAN_MODE_CROSS, loanCurrency,
            loanService.getNumeraireCurrency(), loan.outstandingPrincipal, loan.accumulatedInterest, 0,
            loan.getRateBps(), crossLtvBps(up, cmd.timestamp), -principalRepaid, interestSettled);

        if (loan.isEmpty()) {
            up.crossLoans.remove(loanId);
            engine.getObjectsPool().put(ObjectsPool.CROSS_LOAN_RECORD, loan);
        }
        return CommandResultCode.SUCCESS;
    }

    /**
     * LOAN_CROSS_FORCE_LIQUIDATE preProcess —— 校验 + pre-move 卖出币抵押到 exchangeLocked，转成 spot ASK IOC 交撮合。
     * 字段：reserveBidPrice=targetLoanId / symbol=spotSymbolId(base=卖出币, quote=targetLoan.loanCurrency) / size=卖出张数(lot)。
     * REJECT 部分由 {@link #postProcessLoanCrossForceLiquidate} 回填 crossLoanCollateral。
     */
    public CommandResultCode handleLoanCrossForceLiquidate(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        // 不拦 SUSPENDED：同 handleLoanForceLiquidate —— 系统强平不该被 suspend 挡住。

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

        // cmd.size 是撮合张数（lot）；换回抵押金额（currencyScale）再记账，与结算/reject 释放同尺
        final CoreCurrencySpecification sellingCurrencySpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(sellingCurrency);
        final long sellAmount = LoanService.lotsToCollateralAmount(cmd.size, spec, sellingCurrencySpec);
        if (sellAmount <= 0 || sellAmount > availableCollateral)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        // Pre-move: crossLoanCollateral[sellingCurrency] -> exchangeLocked[sellingCurrency]
        up.crossLoanCollateral.addToValue(sellingCurrency, -sellAmount);
        up.exchangeLocked.addToValue(sellingCurrency, sellAmount);

        cmd.action = OrderAction.ASK;
        cmd.orderType = OrderType.IOC;
        return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
    }

    /**
     * LOAN_CROSS_FORCE_LIQUIDATE postProcess —— R2 结算后调用。REJECT 回填 crossLoanCollateral；TRADE 所得走
     * {@link LoanService#settleLiquidationProceeds} 偿 targetLoan；全部抵押无可卖整张且债务未清则 targetLoan 并入 badDebt。
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

        // REJECT 部分：spot handler 已释放 exchangeLocked[sellingCurrency]，回填 crossLoanCollateral 保守恒
        if (rejectedSize > 0) {
            long rejectedInCurrencyScale =
                CoreArithmeticUtils.symbolToCurrencyScale(rejectedSize, spec, sellingCurrencySpec);
            takerUp.crossLoanCollateral.addToValue(sellingCurrency, rejectedInCurrencyScale);
        }

        // 事件身份 early-capture（下方分支可能 recycle targetLoan 对象）
        final long borrowerUid = targetLoan.uid;
        final int rateBps = targetLoan.getRateBps();
        final long principalBeforeSettle = targetLoan.outstandingPrincipal;

        // TRADE：卖出所得（扣撮合 takerFee）→ settleLiquidationProceeds（强平费 + 抵债，overpay 留用户）
        long interestPaid = 0;
        long collateralSold = 0; // 卖出的 sellingCurrency 抵押（currency scale）
        if (tradedSize > 0) {
            final long avgTakerPrice = tradedNotional / tradedSize;
            final long takerFee = CoreArithmeticUtils.calculateTakerFee(tradedSize, avgTakerPrice, spec);
            final long receivedQuote =
                CoreArithmeticUtils.sizePriceToCurrencyScale(tradedNotional - takerFee, spec, loanCurrencySpec);
            interestPaid =
                loanService.settleLiquidationProceeds(targetLoan, takerUp.accounts, receivedQuote, cmd.timestamp);
            collateralSold = CoreArithmeticUtils.symbolToCurrencyScale(tradedSize, spec, sellingCurrencySpec);
        }
        final long principalRepaid = principalBeforeSettle - targetLoan.outstandingPrincipal;

        long remainTargetDebt = Math.addExact(targetLoan.outstandingPrincipal, targetLoan.accumulatedInterest);
        // Cross underwater 判定查全部 crossLoanCollateral，且用"是否还有可卖整张"而非 amount>0——
        // 别的币种还有整张可下轮继续卖；只剩 sub-lot 尘埃则视为耗尽，否则会漏记 badDebt + target loan 悬挂。
        boolean allCollateralExhausted = true;
        for (int currency : takerUp.crossLoanCollateral.keySet().toArray()) {
            if (LoanService.hasSellableCollateralLot(currency, takerUp.crossLoanCollateral.get(currency),
                engine.getSymbolSpecificationProvider(), engine.getCurrencySpecificationProvider())) {
                allCollateralExhausted = false;
                break;
            }
        }
        // 核销/清空的两个分支快照全 0；仅保留分支填当前值
        long badDebt = 0;
        long snapPrincipal = 0, snapInterest = 0;
        if (allCollateralExhausted && remainTargetDebt > 0) {
            // 账户级 underwater：所有抵押币种都清零但 target loan 债务仍剩 → 剩余债务写 badDebt
            badDebt = remainTargetDebt;
            loanService.getBadDebt().addToValue(loanCurrency, remainTargetDebt);
            loanService.getLoanPoolBorrowed().addToValue(loanCurrency, -targetLoan.outstandingPrincipal);
            targetLoan.outstandingPrincipal = 0;
            targetLoan.accumulatedInterest = 0;
            takerUp.crossLoans.remove(targetLoanId);
            engine.getObjectsPool().put(ObjectsPool.CROSS_LOAN_RECORD, targetLoan);
        } else if (targetLoan.outstandingPrincipal == 0 && targetLoan.accumulatedInterest == 0) {
            takerUp.crossLoans.remove(targetLoanId);
            engine.getObjectsPool().put(ObjectsPool.CROSS_LOAN_RECORD, targetLoan);
        } else {
            // targetLoan 保留：卡单计数（放 targetLoan）——零成交 +1、有成交清 0
            targetLoan.stuckLiqAttempts = tradedSize > 0 ? 0 : targetLoan.stuckLiqAttempts + 1;
            snapPrincipal = targetLoan.outstandingPrincipal;
            snapInterest = targetLoan.accumulatedInterest;
        }
        // Cross 强平：collateralCurrency=卖出币、collateralAmount=该币剩余；零成交无坏账则跳过 no-op 事件
        if (tradedSize > 0 || badDebt > 0)
            engine.getEventsHelper().sendLoanLiquidatedEvent(cmd, borrowerUid, targetLoanId, LOAN_MODE_CROSS, loanCurrency,
                sellingCurrency, snapPrincipal, snapInterest, takerUp.crossLoanCollateral.get(sellingCurrency), rateBps,
                crossLtvBps(takerUp, cmd.timestamp), -principalRepaid, -collateralSold, interestPaid, badDebt);
    }

    // ================================================================================================================
    // 池子运营 handler（cmd.uid 承载 shardId，跟 IF_DEPOSIT / IF_WITHDRAW 同款模式）
    // ================================================================================================================

    /**
     * POOL_DEPOSIT。 字段：uid=shardId / symbol=currency / size=amount / orderId=externalId
     * <p>
     * 其他 shard 静默 SUCCESS 短路（不 log warn，避免所有 shard 都 broadcast）。
     */
    public CommandResultCode handlePoolDeposit(OrderCommand cmd) {
        if ((int)cmd.uid != engine.getShardId())
            return CommandResultCode.SUCCESS;
        if (cmd.size <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        final LoanService loanService = engine.getLoanService();
        final long dedupKey = poolDedupKey(OrderCommandType.POOL_DEPOSIT, cmd.orderId);
        if (!loanService.getPoolProcessedExternalIds().tryClaim(dedupKey)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        loanService.getLoanPoolAvailable().addToValue(cmd.symbol, cmd.size);
        engine.getAdjustments().addToValue(cmd.symbol, -cmd.size);
        return CommandResultCode.SUCCESS;
    }

    /**
     * POOL_WITHDRAW。 字段：uid=shardId / symbol=currency / size=amount / orderId=externalId
     */
    /**
     * POOL_ABSORB_BAD_DEBT —— 官方确认坏账，清除审计追踪。
     * <p>
     * 语义：损失在 force-sell underwater 时点已经反映到 poolAvailable（recover 少于 principal）， badDebt 是历史"审计条目"记录该损失。本命令仅清 badDebt
     * tracker，不动 poolAvailable （避免二次扣真金）。运营若要补充池子容量，走 POOL_DEPOSIT 单独注资。
     * <p>
     * 字段：uid=shardId / symbol=currency / size=absorb 上限 / orderId=externalId
     */
    public CommandResultCode handlePoolAbsorbBadDebt(OrderCommand cmd) {
        if ((int)cmd.uid != engine.getShardId())
            return CommandResultCode.SUCCESS;
        if (cmd.size <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        final LoanService loanService = engine.getLoanService();
        final int currency = cmd.symbol;
        final long debtOutstanding = loanService.getBadDebt().get(currency);
        if (debtOutstanding <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;
        final long absorbed = Math.min(cmd.size, debtOutstanding);

        final long dedupKey = poolDedupKey(OrderCommandType.POOL_ABSORB_BAD_DEBT, cmd.orderId);
        if (!loanService.getPoolProcessedExternalIds().tryClaim(dedupKey)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        loanService.getBadDebt().addToValue(currency, -absorbed);
        return CommandResultCode.SUCCESS;
    }

    public CommandResultCode handlePoolWithdraw(OrderCommand cmd) {
        if ((int)cmd.uid != engine.getShardId())
            return CommandResultCode.SUCCESS;
        if (cmd.size <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        final LoanService loanService = engine.getLoanService();
        if (loanService.getLoanPoolAvailable().get(cmd.symbol) < cmd.size)
            return CommandResultCode.LOAN_POOL_INSUFFICIENT;

        final long dedupKey = poolDedupKey(OrderCommandType.POOL_WITHDRAW, cmd.orderId);
        if (!loanService.getPoolProcessedExternalIds().tryClaim(dedupKey)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED_SAME;
        }

        loanService.getLoanPoolAvailable().addToValue(cmd.symbol, -cmd.size);
        engine.getAdjustments().addToValue(cmd.symbol, cmd.size);
        return CommandResultCode.SUCCESS;
    }

    // ================================================================
    // 私有 helpers
    // ================================================================

    /** 返回 {@code lastPriceCache[symbolId].markPrice}，缺失返回 0（caller 走 LOAN_MARKPRICE_NOT_READY）。 */
    private long markPriceOrZero(int symbolId) {
        LastPriceCacheRecord record = engine.getLastPriceCache().get(symbolId);
        return record == null ? 0L : record.markPrice;
    }

    /** Isolated LTV 抵押估值：委托给 {@link LoanService#collateralValueInQuoteCurrency}，本地 helper 自己查两 currencySpec。 */
    private long evalCollateralInLoanCurrency(long amount, CoreSymbolSpecification spec, long markPrice) {
        CoreCurrencySpecification baseSpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(spec.baseCurrency);
        CoreCurrencySpecification quoteSpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(spec.quoteCurrency);
        return LoanService.collateralValueInQuoteCurrency(amount, spec, markPrice, baseSpec, quoteSpec);
    }

    /**
     * Isolated 单笔 LTV（bps）——事件用，best-effort：markPrice / spec 缺失或抵押估值 &le; 0 时返 0（下游可用 principal+collateral+markPrice 自算）。
     * 读操作后 loan 快照（outstandingPrincipal + pending 利息）÷ 抵押估值。
     */
    private long isolatedLtvBps(IsolatedLoanRecord loan, long now) {
        final CoreSymbolSpecification spec =
            engine.getSymbolSpecificationProvider().getSymbolSpecification(loan.symbolId);
        if (spec == null)
            return 0L;
        final long markPrice = markPriceOrZero(spec.symbolId);
        if (markPrice <= 0)
            return 0L;
        final long collateralValue = evalCollateralInLoanCurrency(loan.collateralAmount, spec, markPrice);
        if (collateralValue <= 0)
            return 0L;
        final long realDebt = Math.addExact(loan.outstandingPrincipal,
            engine.getLoanService().calculateDisplayInterest(loan, now));
        return Math.multiplyExact(realDebt, LoanService.BPS_SCALE) / collateralValue;
    }

    /** Cross 账户级 LTV（bps）——事件用，best-effort：numeraire 未配置返 0。 */
    private long crossLtvBps(UserProfile up, long now) {
        final LoanService loanService = engine.getLoanService();
        if (!loanService.isNumeraireConfigured())
            return 0L;
        return loanService.calculateCrossAccountLtvBps(up, now, engine.getSymbolSpecificationProvider(),
            engine.getCurrencySpecificationProvider(), engine.getLastPriceCache(), loanService.getNumeraireCurrency());
    }

    /** 判 currency 是否允许作 Cross 抵押（有 base==currency 的 spec 且 collateralWeightBps &gt; 0）。 */
    private boolean isCollateralAllowed(int currency) {
        return LoanService.collateralWeightForBase(currency, engine.getSymbolSpecificationProvider()) > 0;
    }

    /**
     * 池子容量+利用率校验（LOAN_CREATE 和 LOAN_CROSS_BORROW 共用）。 available 不足 → LOAN_POOL_INSUFFICIENT；新借出使 utilization 超 cap →
     * LOAN_POOL_UTILIZATION_EXCEEDED。
     */
    private static CommandResultCode verifyPoolCapacity(LoanService loanService, int loanCurrency, long principal) {
        final long available = loanService.getLoanPoolAvailable().get(loanCurrency);
        final long borrowed = loanService.getLoanPoolBorrowed().get(loanCurrency);
        if (available < principal)
            return CommandResultCode.LOAN_POOL_INSUFFICIENT;
        final long newBorrowed = Math.addExact(borrowed, principal);
        final long totalPool = Math.addExact(available, borrowed);
        if (totalPool > 0 && Math.multiplyExact(newBorrowed, LoanService.BPS_SCALE) > Math.multiplyExact(totalPool,
            (long)loanService.getLoanPoolUtilizationCapBps())) {
            return CommandResultCode.LOAN_POOL_UTILIZATION_EXCEEDED;
        }
        return CommandResultCode.SUCCESS;
    }

    /** 借款划账（LOAN_CREATE 和 LOAN_CROSS_BORROW 共用）：pool available → user account；pool borrowed 记账 +principal。 */
    private static void disburseLoan(UserProfile up, LoanService loanService, int loanCurrency, long principal) {
        up.accounts.addToValue(loanCurrency, principal);
        loanService.getLoanPoolAvailable().addToValue(loanCurrency, -principal);
        loanService.getLoanPoolBorrowed().addToValue(loanCurrency, principal);
    }

    private CoreSymbolSpecification findLoanSpecByQuoteCurrency(int loanCurrency) {
        return LoanService.findLoanSpecByQuoteCurrency(loanCurrency, engine.getSymbolSpecificationProvider());
    }

    /** 池子幂等 key：typeTag 高 8 位 XOR externalId，避免同 externalId 跨 cmdType 意外去重。 */
    private static long poolDedupKey(OrderCommandType cmdType, long externalId) {
        final long typeTag = (long)cmdType.getCode() & 0xFFL;
        return (typeTag << 56) ^ externalId;
    }

}
