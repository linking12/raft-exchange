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
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * 现货借贷子域命令处理器 —— per-shard 一份实例，挂在 {@link RiskEngine} 上。
 *
 * <p>
 * <b>二级 dispatch 入口</b>：{@link RiskEngine#preProcessCommand} 首行判断 {@code cmd.command.isLoan()}， 命中则**整块**委托给本类的
 * {@link #dispatch(OrderCommand)}；RiskEngine 主 switch 里**永远看不到** loan 命令。
 *
 * <p>
 * <b>本类承担</b>：13 条 loan 命令的 apply 业务流（校验 → 状态转移）+ shard filter + POOL 短路，含 force-sell 的
 * preProcess（pre-move 抵押到 exchangeLocked）和 postProcess（R2 spot 结算后路由 proceeds）钩子。
 * <b>不承担</b>：state 承载 / 序列化（{@link LoanService}）；scanner 触发（{@link LoanLiquidationEngine}）。
 *
 * <p>
 * <b>持依赖</b>：单一 {@link RiskEngine} ref，通过它现取 UserProfileService / SymbolSpecificationProvider / lastPriceCache / fees /
 * adjustments / calculateLocked / LoanService state。不缓存 sub-service。
 *
 * <p>
 * <b>OrderCommand 字段映射约定</b>（各 handler 头部再详列）：
 * <ul>
 * <li>{@code cmd.orderId} —— externalId（幂等 key，走 {@code UserProfile.processedExternalIds}）；force-sell 场景是本类生成的
 * orderId</li>
 * <li>{@code cmd.reserveBidPrice} —— loanId（业务主键，per-user 唯一，Isolated / Cross 命名空间独立）</li>
 * <li>{@code cmd.uid} —— 用户 uid（用户维度命令）或 shardId（POOL_DEPOSIT / POOL_WITHDRAW / POOL_ABSORB_BAD_DEBT，
 * 跟 IF_DEPOSIT 同款）</li>
 * <li>{@code cmd.symbol} —— symbolId（LOAN_CREATE，反推 collateralCcy = spec.baseCurrency / loanCcy = spec.quoteCurrency）或
 * currency（Cross / POOL 命令）</li>
 * <li>{@code cmd.size} —— 金额（collateralAmount / addAmount / releaseAmount / withdrawAmount / repayAmount 之一）</li>
 * <li>{@code cmd.price} —— 金额（LOAN_CREATE / BORROW = principal；LOAN_REPAY = repayAmount，0 表示 payoff）</li>
 * <li>{@code cmd.timestamp} —— 时间基准（accrue 用）</li>
 * </ul>
 */
@Slf4j
public final class LoanCommandHandlers {

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
    // Isolated 借贷 handler（详见 loan.md §5.2 - §5.5）
    // ================================================================================================================

    /**
     * LOAN_CREATE（详见 loan.md §5.2）。
     * <p>
     * 字段：uid / reserveBidPrice=loanId / symbol=symbolId / size=collateralAmount / price=principal / orderId=externalId
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

        // LTV：principal × 10000 ≤ collateralValueInLoanCcy × loanInitialLtvBps
        // 单位统一（都在 loanCcy currencyScale 下比较），避免原来 currencyScale(loanCcy) 跟 sizePriceScale 混算 → 恒为 pass 的 bug
        final long collateralValueInLoanCcy = evalCollateralInLoanCcy(collateralAmount, spec, markPrice);
        if (collateralValueInLoanCcy < 0)
            return CommandResultCode.LOAN_MARKPRICE_NOT_READY;
        final long lhs = Math.multiplyExact(principal, LoanService.BPS_SCALE);
        final long rhs = Math.multiplyExact(collateralValueInLoanCcy, (long)spec.loanInitialLtvBps);
        if (lhs > rhs)
            return CommandResultCode.LOAN_LTV_TOO_HIGH;

        // 抵押可用（走 calculateLocked，扩展后自动含 futures margin / exchangeLocked / 其他 loan 抵押）
        final int collateralCcy = spec.baseCurrency;
        final int loanCcy = spec.quoteCurrency;
        final long freeCollateralCcy = up.accounts.get(collateralCcy) - engine.calculateLocked(up, collateralCcy);
        if (freeCollateralCcy < collateralAmount)
            return CommandResultCode.LOAN_COLLATERAL_INSUFFICIENT;

        final LoanService loanService = engine.getLoanService();
        final CommandResultCode poolCheck = verifyPoolCapacity(loanService, loanCcy, principal);
        if (poolCheck != CommandResultCode.SUCCESS)
            return poolCheck;

        // v1 利率直接从 spec.loanRateBps snapshot 到 loan，之后不变；v2 支持动态利率时替换
        final IsolatedLoanRecord loan =
            engine.getObjectsPool().get(ObjectsPool.ISOLATED_LOAN_RECORD, IsolatedLoanRecord::new);
        loan.initialize(cmd.uid, loanId, collateralCcy, loanCcy, spec.loanRateBps, cmd.timestamp);
        loan.collateralAmount = collateralAmount;
        loan.outstandingPrincipal = principal;
        up.isolatedLoans.put(loanId, loan);
        disburseLoan(up, loanService, loanCcy, principal);
        return CommandResultCode.SUCCESS;
    }

    /**
     * LOAN_REPAY（详见 loan.md §5.3）。
     * <p>
     * 字段：uid / reserveBidPrice=loanId / price=repayAmount（0 = payoff full）/ orderId=externalId
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

        final int loanCcy = loan.loanCcy;
        final long free = up.accounts.get(loanCcy) - engine.calculateLocked(up, loanCcy);
        if (free < actualRepay)
            return CommandResultCode.LOAN_ACCOUNT_INSUFFICIENT;

        // 利息优先分账
        final long interestPart = Math.min(actualRepay, loan.accumulatedInterest);
        final long principalPart = actualRepay - interestPart;

        up.accounts.addToValue(loanCcy, -actualRepay);
        loan.accumulatedInterest -= interestPart;
        loan.outstandingPrincipal -= principalPart;
        loanService.getLoanPoolAvailable().addToValue(loanCcy, principalPart);
        loanService.getLoanPoolBorrowed().addToValue(loanCcy, -principalPart);
        loanService.getInterestRevenue().addToValue(loanCcy, interestPart);

        if (loan.isEmpty()) {
            up.isolatedLoans.remove(loanId);
            engine.getObjectsPool().put(ObjectsPool.ISOLATED_LOAN_RECORD, loan);
        }
        return CommandResultCode.SUCCESS;
    }

    /**
     * LOAN_ADD_COLLATERAL（详见 loan.md §5.4）—— 补抵押降 LTV。
     * <p>
     * 字段：uid / reserveBidPrice=loanId / size=amount / orderId=externalId
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

        final long free = up.accounts.get(loan.collateralCcy) - engine.calculateLocked(up, loan.collateralCcy);
        if (free < amount)
            return CommandResultCode.LOAN_COLLATERAL_INSUFFICIENT;

        loan.collateralAmount = Math.addExact(loan.collateralAmount, amount);
        return CommandResultCode.SUCCESS;
    }

    /**
     * LOAN_RELEASE_COLLATERAL（详见 loan.md §5.5）—— 减抵押；允许操作到 marginCall 上方，拒绝直接撤到强平线。
     * <p>
     * 字段：uid / reserveBidPrice=loanId / size=amount / orderId=externalId
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

        final CoreSymbolSpecification spec = LoanService.findSpotSpec(loan.collateralCcy, loan.loanCcy,
            engine.getSymbolSpecificationProvider());
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
            // 走 evalCollateralInLoanCcy 换算到 loanCcy currencyScale，跟 realDebt 同单位比较
            final long newCollateralValueInLoanCcy = evalCollateralInLoanCcy(newCollateral, spec, markPrice);
            if (newCollateralValueInLoanCcy < 0)
                return CommandResultCode.LOAN_MARKPRICE_NOT_READY;
            final long lhs = Math.multiplyExact(realDebt, LoanService.BPS_SCALE);
            final long rhs = Math.multiplyExact(newCollateralValueInLoanCcy, (long)spec.loanLiquidationLtvBps);
            if (lhs >= rhs)
                return CommandResultCode.LOAN_LTV_TOO_HIGH_AFTER_RELEASE;
        }

        loan.collateralAmount = newCollateral;
        // 全零死壳清理：REPAY 已 payoff（princ+int=0）且本次 RELEASE 减到 collateralAmount=0
        // → 移除 loan record 并归还对象池，让同 loanId 可被 LOAN_CREATE 复用（否则 handleLoanCreate 里 containsKey 卡住）
        if (loan.isEmpty()) {
            up.isolatedLoans.remove(loanId);
            engine.getObjectsPool().put(ObjectsPool.ISOLATED_LOAN_RECORD, loan);
        }
        return CommandResultCode.SUCCESS;
    }

    /**
     * LOAN_FORCE_LIQUIDATE preProcess —— 校验 + pre-move 抵押到 exchangeLocked，让 orderbook 走标准 spot ASK 撮合。
     *
     * <p>字段：uid / reserveBidPrice=loanId / symbol=spotSymbolId / size=collateral to sell / price=IOC limit /
     * orderId=LoanService.generateIsolatedForceSellOrderId(loan)（顶字节 'L' + subtype 'S'）
     *
     * <p>Pre-move：{@code loan.collateralAmount -= size; exchangeLocked[collateralCcy] += size}。让现有 spot 结算
     * 机制解锁 exchangeLocked 时不会跑负。REJECT 部分由 {@link #postProcessLoanForceLiquidate} 回填 loan.collateralAmount。
     */
    public CommandResultCode handleLoanForceLiquidate(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;

        // ApiLoanForceLiquidate 独立 proto + translator，loanId 走 reserveBidPrice（跟其他 loan cmd 一致）
        final long loanId = cmd.reserveBidPrice;
        final IsolatedLoanRecord loan = up.isolatedLoans.get(loanId);
        if (loan == null)
            return CommandResultCode.LOAN_NOT_FOUND;
        if (loan.uid != cmd.uid)
            return CommandResultCode.LOAN_UID_MISMATCH;

        final long sellSize = cmd.size;
        if (sellSize <= 0 || sellSize > loan.collateralAmount)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        final CoreSymbolSpecification spec = engine.getSymbolSpecificationProvider().getSymbolSpecification(cmd.symbol);
        if (spec == null || spec.type != SymbolType.CURRENCY_EXCHANGE_PAIR
            || spec.baseCurrency != loan.collateralCcy || spec.quoteCurrency != loan.loanCcy)
            return CommandResultCode.LOAN_NOT_ENABLED;

        // Pre-move: 从 loan 记账挪到 exchangeLocked
        loan.collateralAmount -= sellSize;
        up.exchangeLocked.addToValue(loan.collateralCcy, sellSize);

        // 让 orderbook 按标准 spot ASK IOC 撮合
        cmd.action = OrderAction.ASK;
        cmd.orderType = OrderType.IOC;
        return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
    }

    /**
     * LOAN_FORCE_LIQUIDATE postProcess —— R2 spot 结算后钩子，把 quote proceeds 从 taker.accounts 路由到
     * loanLiqFees / interestRevenue / poolAvailable，剩余留 overpay 给用户；REJECT 部分回填 loan.collateralAmount；
     * underwater 走 badDebt。
     *
     * <p>调用点：{@code RiskEngine.handlerRiskRelease} 处理完 spot ASK 后。takerUp 由 caller 提供（只有 owner shard
     * 调本方法）。
     */
    public void postProcessLoanForceLiquidate(OrderCommand cmd, CoreSymbolSpecification spec, UserProfile takerUp) {
        final long loanId = cmd.reserveBidPrice;
        final IsolatedLoanRecord loan = takerUp.isolatedLoans.get(loanId);
        if (loan == null) {
            log.warn("Loan gone in postProcess: uid={} loanId={}", cmd.uid, loanId);
            return;
        }
        final LoanService loanService = engine.getLoanService();
        final int loanCcy = loan.loanCcy;
        final CoreCurrencySpecification loanCcySpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(loanCcy);
        final CoreCurrencySpecification baseSpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(loan.collateralCcy);

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

        // TRADE 部分：抽 loanLiqFee → accrue → 利息优先 → principal 回池 → 剩余 overpay 留用户账户
        if (tradedSize > 0) {
            final long avgTakerPrice = tradedNotional / tradedSize;
            final long takerFee = CoreArithmeticUtils.calculateTakerFee(tradedSize, avgTakerPrice, spec);
            final long receivedQuote =
                CoreArithmeticUtils.sizePriceToCurrencyScale(tradedNotional - takerFee, spec, loanCcySpec);

            long liqFee = Math.min(receivedQuote, CoreArithmeticUtils.ceilMulDiv(receivedQuote,
                (long)LoanService.LOAN_LIQUIDATION_FEE_BPS, LoanService.BPS_SCALE));
            takerUp.accounts.addToValue(loanCcy, -liqFee);
            loanService.getLoanLiqFees().addToValue(loanCcy, liqFee);
            long available = receivedQuote - liqFee;

            loanService.accrueTo(loan, cmd.timestamp);
            long interestPay = Math.min(available, loan.accumulatedInterest);
            loan.accumulatedInterest -= interestPay;
            loanService.getInterestRevenue().addToValue(loanCcy, interestPay);
            takerUp.accounts.addToValue(loanCcy, -interestPay);
            available -= interestPay;

            long principalPay = Math.min(available, loan.outstandingPrincipal);
            loan.outstandingPrincipal -= principalPay;
            loanService.getLoanPoolAvailable().addToValue(loanCcy, principalPay);
            loanService.getLoanPoolBorrowed().addToValue(loanCcy, -principalPay);
            takerUp.accounts.addToValue(loanCcy, -principalPay);
        }

        long remainDebt = Math.addExact(loan.outstandingPrincipal, loan.accumulatedInterest);
        if (loan.collateralAmount == 0 && remainDebt > 0) {
            // Underwater：抵押清零但债务剩余 → 剩余债务（含利息）写 badDebt，清空 loan
            loanService.getBadDebt().addToValue(loanCcy, remainDebt);
            loanService.getLoanPoolBorrowed().addToValue(loanCcy, -loan.outstandingPrincipal);
            loan.outstandingPrincipal = 0;
            loan.accumulatedInterest = 0;
            takerUp.isolatedLoans.remove(loanId);
            engine.getObjectsPool().put(ObjectsPool.ISOLATED_LOAN_RECORD, loan);
        } else if (loan.isEmpty()) {
            takerUp.isolatedLoans.remove(loanId);
            engine.getObjectsPool().put(ObjectsPool.ISOLATED_LOAN_RECORD, loan);
        }
        // 部分成交：loan 保留，等 scanner 下轮
    }

    // ================================================================================================================
    // Cross 借贷 handler（详见 loan.md §5.6 - §5.9）
    // ================================================================================================================

    /**
     * LOAN_CROSS_ADD_COLLATERAL（详见 loan.md §5.6）。
     * <p>
     * 字段：uid / symbol=currency / size=amount / orderId=externalId
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
        return CommandResultCode.SUCCESS;
    }

    /**
     * LOAN_CROSS_WITHDRAW_COLLATERAL（详见 loan.md §5.7）。
     * <p>
     * 字段：uid / symbol=currency / size=amount / orderId=externalId
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
            engine.getLastPriceCache(), loanService.getNumeraireCcy());
        if (newLtv >= loanService.getCrossLiquidationLtvBps()) {
            up.crossLoanCollateral.addToValue(currency, amount); // revert
            return CommandResultCode.LOAN_CROSS_LTV_TOO_HIGH_AFTER_WITHDRAW;
        }
        return CommandResultCode.SUCCESS;
    }

    /**
     * LOAN_CROSS_BORROW（详见 loan.md §5.8）。
     * <p>
     * 字段：uid / reserveBidPrice=loanId / symbol=loanCcy / price=principal / orderId=externalId
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

        final int loanCcy = cmd.symbol;
        final long principal = cmd.price;
        if (principal <= 0)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        final CoreSymbolSpecification spec = findLoanSpecByQuoteCurrency(loanCcy);
        if (spec == null || spec.loanInitialLtvBps <= 0)
            return CommandResultCode.LOAN_NOT_ENABLED;
        if (spec.loanMaxAmount != 0 && principal > spec.loanMaxAmount)
            return CommandResultCode.LOAN_PRINCIPAL_EXCEEDS_LIMIT;

        final LoanService loanService = engine.getLoanService();
        if (!loanService.isNumeraireConfigured())
            return CommandResultCode.LOAN_NUMERAIRE_NOT_CONFIGURED;
        final CommandResultCode poolCheck = verifyPoolCapacity(loanService, loanCcy, principal);
        if (poolCheck != CommandResultCode.SUCCESS)
            return poolCheck;

        // 借后账户级 LTV ≤ loanInitialLtvBps 才允许——先落 loan 记账后调 calculateCrossAccountLtvBps，超线再 revert
        final CrossLoanRecord loan =
            engine.getObjectsPool().get(ObjectsPool.CROSS_LOAN_RECORD, CrossLoanRecord::new);
        loan.initialize(cmd.uid, loanId, loanCcy, spec.loanRateBps, cmd.timestamp);
        loan.outstandingPrincipal = principal;
        up.crossLoans.put(loanId, loan);
        final long newLtv = loanService.calculateCrossAccountLtvBps(up, cmd.timestamp,
            engine.getSymbolSpecificationProvider(), engine.getCurrencySpecificationProvider(),
            engine.getLastPriceCache(), loanService.getNumeraireCcy());
        if (newLtv > spec.loanInitialLtvBps) {
            up.crossLoans.remove(loanId);
            engine.getObjectsPool().put(ObjectsPool.CROSS_LOAN_RECORD, loan);
            return CommandResultCode.LOAN_LTV_TOO_HIGH_AFTER_BORROW;
        }
        disburseLoan(up, loanService, loanCcy, principal);
        return CommandResultCode.SUCCESS;
    }

    /**
     * LOAN_CROSS_REPAY（详见 loan.md §5.9）—— 逻辑同 Isolated REPAY，但不释放抵押。
     * <p>
     * 字段：uid / reserveBidPrice=loanId / price=repayAmount / orderId=externalId
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

        final int loanCcy = loan.loanCcy;
        final long free = up.accounts.get(loanCcy) - engine.calculateLocked(up, loanCcy);
        if (free < actualRepay)
            return CommandResultCode.LOAN_ACCOUNT_INSUFFICIENT;

        final long interestPart = Math.min(actualRepay, loan.accumulatedInterest);
        final long principalPart = actualRepay - interestPart;

        up.accounts.addToValue(loanCcy, -actualRepay);
        loan.accumulatedInterest -= interestPart;
        loan.outstandingPrincipal -= principalPart;
        loanService.getLoanPoolAvailable().addToValue(loanCcy, principalPart);
        loanService.getLoanPoolBorrowed().addToValue(loanCcy, -principalPart);
        loanService.getInterestRevenue().addToValue(loanCcy, interestPart);

        if (loan.isEmpty()) {
            up.crossLoans.remove(loanId);
            engine.getObjectsPool().put(ObjectsPool.CROSS_LOAN_RECORD, loan);
        }
        return CommandResultCode.SUCCESS;
    }

    /**
     * LOAN_CROSS_FORCE_LIQUIDATE preProcess —— 校验 + pre-move 抵押到 exchangeLocked，让 orderbook 走标准 spot ASK 撮合。
     *
     * <p>字段：uid / reserveBidPrice=targetLoanId / symbol=spotSymbolId (base=sellingCcy, quote=targetLoan.loanCcy)
     * / size=sellingCcy 抵押量 / price=IOC limit / orderId=LoanService.generateCrossForceSellOrderId(uid, sellingCcy)
     *
     * <p>Pre-move：{@code crossLoanCollateral[sellingCcy] -= size; exchangeLocked[sellingCcy] += size}。
     * REJECT 部分由 {@link #postProcessLoanCrossForceLiquidate} 回填 crossLoanCollateral。
     */
    public CommandResultCode handleLoanCrossForceLiquidate(OrderCommand cmd) {
        final UserProfile up = engine.getUserProfileService().getUserProfile(cmd.uid);
        if (up == null)
            return CommandResultCode.AUTH_INVALID_USER;
        if (up.userStatus == UserStatus.SUSPENDED)
            return CommandResultCode.LOAN_USER_SUSPENDED;

        final long targetLoanId = cmd.reserveBidPrice;
        final CrossLoanRecord targetLoan = up.crossLoans.get(targetLoanId);
        if (targetLoan == null)
            return CommandResultCode.LOAN_NOT_FOUND;
        if (targetLoan.uid != cmd.uid)
            return CommandResultCode.LOAN_UID_MISMATCH;

        final CoreSymbolSpecification spec = engine.getSymbolSpecificationProvider().getSymbolSpecification(cmd.symbol);
        if (spec == null || spec.type != SymbolType.CURRENCY_EXCHANGE_PAIR
            || spec.quoteCurrency != targetLoan.loanCcy)
            return CommandResultCode.LOAN_NOT_ENABLED;

        final int sellingCcy = spec.baseCurrency;
        final long sellSize = cmd.size;
        final long availableCollateral = up.crossLoanCollateral.get(sellingCcy);
        if (sellSize <= 0 || sellSize > availableCollateral)
            return CommandResultCode.LOAN_INVALID_AMOUNT;

        // Pre-move: crossLoanCollateral[sellingCcy] -> exchangeLocked[sellingCcy]
        up.crossLoanCollateral.addToValue(sellingCcy, -sellSize);
        up.exchangeLocked.addToValue(sellingCcy, sellSize);

        cmd.action = OrderAction.ASK;
        cmd.orderType = OrderType.IOC;
        return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
    }

    /**
     * LOAN_CROSS_FORCE_LIQUIDATE postProcess —— R2 spot 结算后钩子，把 quote proceeds 从 taker.accounts 路由到
     * loanLiqFees / interestRevenue / poolAvailable，剩余留 overpay 给用户；REJECT 部分回填 crossLoanCollateral；
     * 目标 loan underwater 走 badDebt（账户级 bad debt 由 scanner 后续 tick 判定）。
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
        final int sellingCcy = spec.baseCurrency;
        final int loanCcy = targetLoan.loanCcy;
        final CoreCurrencySpecification loanCcySpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(loanCcy);
        final CoreCurrencySpecification sellingCcySpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(sellingCcy);

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

        // REJECT 部分：spot handler 已释放 exchangeLocked[sellingCcy]，回填 crossLoanCollateral 保守恒
        if (rejectedSize > 0) {
            long rejectedInCurrencyScale =
                CoreArithmeticUtils.symbolToCurrencyScale(rejectedSize, spec, sellingCcySpec);
            takerUp.crossLoanCollateral.addToValue(sellingCcy, rejectedInCurrencyScale);
        }

        // TRADE 部分：抽 loanLiqFee → accrue → 利息优先 → principal 回池 → 剩余 overpay 留用户账户
        if (tradedSize > 0) {
            final long avgTakerPrice = tradedNotional / tradedSize;
            final long takerFee = CoreArithmeticUtils.calculateTakerFee(tradedSize, avgTakerPrice, spec);
            final long receivedQuote =
                CoreArithmeticUtils.sizePriceToCurrencyScale(tradedNotional - takerFee, spec, loanCcySpec);

            long liqFee = Math.min(receivedQuote, CoreArithmeticUtils.ceilMulDiv(receivedQuote,
                (long)LoanService.LOAN_LIQUIDATION_FEE_BPS, LoanService.BPS_SCALE));
            takerUp.accounts.addToValue(loanCcy, -liqFee);
            loanService.getLoanLiqFees().addToValue(loanCcy, liqFee);
            long available = receivedQuote - liqFee;

            loanService.accrueTo(targetLoan, cmd.timestamp);
            long interestPay = Math.min(available, targetLoan.accumulatedInterest);
            targetLoan.accumulatedInterest -= interestPay;
            loanService.getInterestRevenue().addToValue(loanCcy, interestPay);
            takerUp.accounts.addToValue(loanCcy, -interestPay);
            available -= interestPay;

            long principalPay = Math.min(available, targetLoan.outstandingPrincipal);
            targetLoan.outstandingPrincipal -= principalPay;
            loanService.getLoanPoolAvailable().addToValue(loanCcy, principalPay);
            loanService.getLoanPoolBorrowed().addToValue(loanCcy, -principalPay);
            takerUp.accounts.addToValue(loanCcy, -principalPay);
        }

        long remainTargetDebt = Math.addExact(targetLoan.outstandingPrincipal, targetLoan.accumulatedInterest);
        // Cross underwater 判定必须查全部 crossLoanCollateral，不是只查 sellingCcy——用户还可能有其他币种抵押
        // 可以下轮继续卖，不能过早把 target loan 关闭进 badDebt
        boolean allCollateralExhausted = true;
        for (int c : takerUp.crossLoanCollateral.keySet().toArray()) {
            if (takerUp.crossLoanCollateral.get(c) > 0) {
                allCollateralExhausted = false;
                break;
            }
        }
        if (allCollateralExhausted && remainTargetDebt > 0) {
            // 账户级 underwater：所有抵押币种都清零但 target loan 债务仍剩 → 剩余债务写 badDebt
            loanService.getBadDebt().addToValue(loanCcy, remainTargetDebt);
            loanService.getLoanPoolBorrowed().addToValue(loanCcy, -targetLoan.outstandingPrincipal);
            targetLoan.outstandingPrincipal = 0;
            targetLoan.accumulatedInterest = 0;
            takerUp.crossLoans.remove(targetLoanId);
            engine.getObjectsPool().put(ObjectsPool.CROSS_LOAN_RECORD, targetLoan);
        } else if (targetLoan.outstandingPrincipal == 0 && targetLoan.accumulatedInterest == 0) {
            takerUp.crossLoans.remove(targetLoanId);
            engine.getObjectsPool().put(ObjectsPool.CROSS_LOAN_RECORD, targetLoan);
        }
        // 部分成交 or 还有其他币种抵押：targetLoan 保留，等 scanner 下轮换 sellingCcy 继续 deleverage
    }

    // ================================================================================================================
    // 池子运营 handler（cmd.uid 承载 shardId，跟 IF_DEPOSIT / IF_WITHDRAW 同款模式）
    // ================================================================================================================

    /**
     * POOL_DEPOSIT（详见 loan.md §5.10.2）。
     * <p>
     * 字段：uid=shardId / symbol=currency / size=amount / orderId=externalId
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
     * POOL_WITHDRAW（详见 loan.md §5.10.2）。
     * <p>
     * 字段：uid=shardId / symbol=currency / size=amount / orderId=externalId
     */
    /**
     * POOL_ABSORB_BAD_DEBT —— 官方确认坏账，清除审计追踪。
     * <p>语义：损失在 force-sell underwater 时点已经反映到 poolAvailable（recover 少于 principal），
     * badDebt 是历史"审计条目"记录该损失。本命令仅清 badDebt tracker，不动 poolAvailable
     * （避免二次扣真金）。运营若要补充池子容量，走 POOL_DEPOSIT 单独注资。
     * <p>字段：uid=shardId / symbol=currency / size=absorb 上限 / orderId=externalId
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
    private long evalCollateralInLoanCcy(long amount, CoreSymbolSpecification spec, long markPrice) {
        CoreCurrencySpecification baseSpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(spec.baseCurrency);
        CoreCurrencySpecification quoteSpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(spec.quoteCurrency);
        return LoanService.collateralValueInQuoteCurrency(amount, spec, markPrice, baseSpec, quoteSpec);
    }

    /** 判 currency 是否允许作 Cross 抵押（有 base==currency 的 spec 且 collateralWeightBps &gt; 0）。 */
    private boolean isCollateralAllowed(int currency) {
        return LoanService.collateralWeightForBase(currency, engine.getSymbolSpecificationProvider()) > 0;
    }

    /**
     * 池子容量+利用率校验（LOAN_CREATE 和 LOAN_CROSS_BORROW 共用）。
     * available 不足 → LOAN_POOL_INSUFFICIENT；新借出使 utilization 超 cap → LOAN_POOL_UTILIZATION_EXCEEDED。
     */
    private static CommandResultCode verifyPoolCapacity(LoanService loanService, int loanCcy, long principal) {
        final long available = loanService.getLoanPoolAvailable().get(loanCcy);
        final long borrowed = loanService.getLoanPoolBorrowed().get(loanCcy);
        if (available < principal)
            return CommandResultCode.LOAN_POOL_INSUFFICIENT;
        final long newBorrowed = Math.addExact(borrowed, principal);
        final long totalPool = Math.addExact(available, borrowed);
        if (totalPool > 0 && Math.multiplyExact(newBorrowed, LoanService.BPS_SCALE)
            > Math.multiplyExact(totalPool, (long)loanService.getLoanPoolUtilizationCapBps())) {
            return CommandResultCode.LOAN_POOL_UTILIZATION_EXCEEDED;
        }
        return CommandResultCode.SUCCESS;
    }

    /** 借款划账（LOAN_CREATE 和 LOAN_CROSS_BORROW 共用）：pool available → user account；pool borrowed 记账 +principal。 */
    private static void disburseLoan(UserProfile up, LoanService loanService, int loanCcy, long principal) {
        up.accounts.addToValue(loanCcy, principal);
        loanService.getLoanPoolAvailable().addToValue(loanCcy, -principal);
        loanService.getLoanPoolBorrowed().addToValue(loanCcy, principal);
    }

    /** 找 quote==loanCcy 且启用借贷的 spec；返回第一个匹配。O(N) TODO：v2 加 reverse index。 */
    private CoreSymbolSpecification findLoanSpecByQuoteCurrency(int loanCcy) {
        SymbolSpecificationProvider provider = engine.getSymbolSpecificationProvider();
        for (CoreSymbolSpecification spec : provider.getSymbolSpecs()) {
            if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR && spec.quoteCurrency == loanCcy
                && spec.loanInitialLtvBps > 0) {
                return spec;
            }
        }
        return null;
    }

    /** 池子幂等 key：typeTag 高 8 位 XOR externalId，避免同 externalId 跨 cmdType 意外去重（loan.md §5.10.3）。 */
    private static long poolDedupKey(OrderCommandType cmdType, long externalId) {
        final long typeTag = (long)cmdType.getCode() & 0xFFL;
        return (typeTag << 56) ^ externalId;
    }

}
