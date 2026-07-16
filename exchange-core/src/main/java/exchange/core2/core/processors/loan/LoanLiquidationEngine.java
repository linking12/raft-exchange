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

import org.eclipse.collections.api.set.MultiReaderSet;
import org.eclipse.collections.impl.factory.Sets;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.CrossLoanRecord;
import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.IsolatedLoanRecord;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.api.ApiCommand;
import exchange.core2.core.common.api.ApiLoanCrossForceLiquidate;
import exchange.core2.core.common.api.ApiLoanForceLiquidate;
import exchange.core2.core.common.api.ApiSystemLiquidationNotify;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import lombok.extern.slf4j.Slf4j;

/**
 * 现货借贷子域 scanner（per-shard），{@link LiquidationEngine} 每次检查用户时委托 {@link #check(UserProfile)} 进来。扫 Isolated + Cross 两条
 * lane：真实债务(含利息) LTV / 期限判定，越线则 publish force-sell IOC 单，仅越预警线则发 margin call。 provider / service 全经
 * {@link LiquidationEngine} 现取；in-flight 去重等运行态是进程级的，不进 raft snapshot，换届重置无碍。 （动态利率重定价 {@code REPRICE_LOAN_RATES}
 * 的心跳已上移到父类 {@code LiquidationScheduledService.runOneIteration} 发令。）
 */
@Slf4j
public final class LoanLiquidationEngine {
    private static final long MS_PER_DAY = 86400L * 1_000L; // 期限强平用
    private static final long MARGIN_CALL_THROTTLE_MS = 5L * 60L * 1_000L; // 预警节流 5 min
    private static final byte LOAN_MODE_ISOLATED = 0;
    private static final byte LOAN_MODE_CROSS = 1;
    private final LiquidationEngine engine;

    private static final class LaneState {
        final MultiReaderSet<Long> inFlight = Sets.multiReader.empty();
        final LongLongHashMap marginCallThrottleMs = new LongLongHashMap();
        final LongLongHashMap liqRetryThrottleMs = new LongLongHashMap();
    }

    private final LaneState isolated = new LaneState();
    private final LaneState cross = new LaneState();

    public LoanLiquidationEngine(LiquidationEngine engine) {
        this.engine = engine;
    }

    public void check(UserProfile userProfile) {
        userProfile.isolatedLoans.forEachValue(this::checkIsolatedLoan);
        checkCrossLoan(userProfile);
    }

    private void checkIsolatedLoan(IsolatedLoanRecord loan) {
        if (loan.isEmpty() || isolated.inFlight.contains(loan.loanId))
            return;
        final CoreSymbolSpecification spec =
            engine.getSymbolSpecificationProvider().getSymbolSpecification(loan.symbolId);
        if (spec == null)
            return;
        final LastPriceCacheRecord priceRecord = engine.getLastPriceCache().get(spec.symbolId);
        if (priceRecord == null || priceRecord.markPrice == 0)
            return;

        final long nowMs = System.currentTimeMillis();
        final CoreCurrencySpecification baseSpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(loan.collateralCurrency);
        final CoreCurrencySpecification loanCurrencySpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(loan.loanCurrency);
        final long collateralValue = LoanService.collateralValueInQuoteCurrency(loan.collateralAmount, spec,
            priceRecord.markPrice, baseSpec, loanCurrencySpec);
        if (collateralValue < 0)
            return;

        // 真实债务含利息（accumulatedInterest + pending accrue），避免拖债避强平
        final long realDebt =
            Math.addExact(loan.outstandingPrincipal, engine.getLoanService().calculateDisplayInterest(loan, nowMs));
        final long ltvScaled = Math.multiplyExact(realDebt, LoanService.BPS_SCALE);

        // 期限只对 Isolated LOCKED（Fixed）生效；FLOATING 无期限
        final boolean termExpired = loan.rateMode == IsolatedLoanRecord.RATE_MODE_LOCKED
            && spec.loanConfig.maxTermDays > 0 && (nowMs - loan.openedAtTs) > spec.loanConfig.maxTermDays * MS_PER_DAY;

        if (termExpired || ltvScaled >= Math.multiplyExact(collateralValue, (long)spec.loanConfig.liquidationLtvBps)) {
            if (!stuckLiqThrottled("isolated", isolated.liqRetryThrottleMs, loan.loanId, loan.stuckLiqAttempts))
                publishIsolatedForceSell(loan, spec, priceRecord.markPrice, toleranceBpsFor(loan.stuckLiqAttempts),
                    nowMs);
            return;
        }

        final int marginCallLtvBps = spec.loanConfig.marginCallLtvBps;
        if (marginCallLtvBps > 0 && ltvScaled >= Math.multiplyExact(collateralValue, (long)marginCallLtvBps)) {
            final long ltvBps = collateralValue == 0 ? 0 : ltvScaled / collateralValue;
            sendMarginCallIfNotThrottled(isolated, loan.loanId, loan.uid, loan.loanId, LOAN_MODE_ISOLATED,
                loan.loanCurrency, ltvBps, marginCallLtvBps);
        }
    }

    private void checkCrossLoan(UserProfile userProfile) {
        if (userProfile.crossLoans.isEmpty() || cross.inFlight.contains(userProfile.uid))
            return;

        final LoanService loanService = engine.getLoanService();
        final long nowMs = System.currentTimeMillis();

        // Cross 恒 Floating → 无期限，只做 LTV 强平/预警；numeraire 未配置时 ltvBps==0 不触发（保守）
        final long ltvBps = loanService.calculateCrossAccountLtvBps(userProfile, nowMs,
            engine.getSymbolSpecificationProvider(), engine.getCurrencySpecificationProvider(),
            engine.getLastPriceCache(), loanService.getGlobalConfig().numeraireCurrency);

        if (ltvBps >= loanService.getGlobalConfig().crossLiquidationLtvBps) {
            publishCrossForceSell(userProfile, loanService, nowMs, null);
        } else if (ltvBps >= loanService.getGlobalConfig().crossMarginCallLtvBps) {
            // Cross 账户级：loanId=0 / loanCurrency=0（无单笔归属）
            sendMarginCallIfNotThrottled(cross, userProfile.uid, userProfile.uid, 0L, LOAN_MODE_CROSS, 0, ltvBps,
                loanService.getGlobalConfig().crossMarginCallLtvBps);
        }
    }

    // ---- Force-sell 构造 + publish ----

    /** 构造 Isolated 强平 IOC 单并 publishTracked；orderId 空间跟 futures 强平隔离。 */
    private void publishIsolatedForceSell(IsolatedLoanRecord loan, CoreSymbolSpecification spec, long markPrice,
        long toleranceBps, long currentTickMs) {
        // 抵押金额 → 下单张数；不足一张的尘埃卖不掉，留在 collateralAmount，跳过本轮
        CoreCurrencySpecification baseSpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(loan.collateralCurrency);
        long sellSizeLots = LoanService.collateralAmountToLots(loan.collateralAmount, spec, baseSpec);
        if (sellSizeLots <= 0) {
            log.warn("Isolated force-sell skip: sub-lot collateral (uid={} loanId={} collateral={})", loan.uid,
                loan.loanId, loan.collateralAmount);
            return;
        }
        long orderId =
            LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_ISOLATED, loan.uid, loan.loanId, currentTickMs);
        long limitPrice = limitPriceWithTolerance(markPrice, toleranceBps);
        ApiLoanForceLiquidate cmd =
            ApiLoanForceLiquidate.builder().uid(loan.uid).symbol(spec.symbolId).loanId(loan.loanId).price(limitPrice)
                .size(sellSizeLots).orderId(orderId).action(OrderAction.ASK).orderType(OrderType.IOC).build();
        publishTrackedIsolated(cmd, loan.loanId);
    }

    /**
     * Cross 强平：选一对 (sellingCurrency, targetLoan) → 算 sellSize → publish；单轮只处理一对，下轮 tick 重估 LTV 迭代
     * deleveraging。preselectedTarget != null（期限强平）时强平该指定 loan，否则按 tiebreak 选。
     */
    private void publishCrossForceSell(UserProfile up, LoanService loanService, long currentTickMs,
        CrossLoanRecord preselectedTarget) {
        int sellingCurrency = pickCrossCollateralToSell(up);
        if (sellingCurrency == 0) {
            log.warn("Cross force-sell abort: no collateral to sell (uid={})", up.uid);
            return;
        }
        CrossLoanRecord targetLoan = preselectedTarget != null ? preselectedTarget : pickCrossLoanToRepay(up);
        if (targetLoan == null) {
            log.warn("Cross force-sell abort: no target loan (uid={})", up.uid);
            return;
        }
        CoreSymbolSpecification spec =
            LoanService.findSpotSpec(sellingCurrency, targetLoan.loanCurrency, engine.getSymbolSpecificationProvider());
        if (spec == null) {
            log.warn("Cross force-sell abort: no spot pair sellingCurrency={} loanCurrency={} (uid={})",
                sellingCurrency, targetLoan.loanCurrency, up.uid);
            return;
        }
        LastPriceCacheRecord priceRecord = engine.getLastPriceCache().get(spec.symbolId);
        if (priceRecord == null || priceRecord.markPrice <= 0) {
            log.warn("Cross force-sell abort: markPrice not ready for symbol={} (uid={})", spec.symbolId, up.uid);
            return;
        }
        long availableCollateral = up.crossLoanCollateral.get(sellingCurrency);
        CoreCurrencySpecification sellingCurrencySpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(sellingCurrency);
        CoreCurrencySpecification loanCurrencySpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(targetLoan.loanCurrency);
        long sellSize = calculateCrossSellSize(targetLoan, spec, priceRecord.markPrice, availableCollateral,
            currentTickMs, loanService, sellingCurrencySpec, loanCurrencySpec);
        if (sellSize <= 0) {
            log.warn("Cross force-sell abort: sellSize=0 (uid={} sellingCurrency={} available={})", up.uid,
                sellingCurrency, availableCollateral);
            return;
        }
        if (stuckLiqThrottled("cross", cross.liqRetryThrottleMs, up.uid, targetLoan.stuckLiqAttempts))
            return;
        long orderId =
            LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_CROSS, up.uid, targetLoan.loanId, currentTickMs);
        long limitPrice = limitPriceWithTolerance(priceRecord.markPrice, toleranceBpsFor(targetLoan.stuckLiqAttempts));
        ApiLoanCrossForceLiquidate cmd = ApiLoanCrossForceLiquidate.builder().uid(up.uid).symbol(spec.symbolId)
            .targetLoanId(targetLoan.loanId).price(limitPrice).size(sellSize).orderId(orderId).action(OrderAction.ASK)
            .orderType(OrderType.IOC).build();
        publishTrackedCross(cmd, up.uid);
    }

    // ---- Cross 选卖出币 / 选还款笔 / 定量 ----

    /** 选卖出抵押币：weight DESC / amount DESC / currency ASC。返回 0 表示无可卖抵押。 */
    private int pickCrossCollateralToSell(UserProfile up) {
        int bestCurrency = 0;
        int bestWeight = -1;
        long bestAmount = -1;
        for (int currency : up.crossLoanCollateral.keySet().toArray()) {
            long amount = up.crossLoanCollateral.get(currency);
            if (amount <= 0)
                continue;
            int weight = LoanService.collateralWeightForBase(currency, engine.getSymbolSpecificationProvider());
            if (weight <= 0)
                continue;
            if (weight > bestWeight || (weight == bestWeight && amount > bestAmount)
                || (weight == bestWeight && amount == bestAmount && currency < bestCurrency)) {
                bestCurrency = currency;
                bestWeight = weight;
                bestAmount = amount;
            }
        }
        return bestCurrency;
    }

    /** 选偿还目标 loan：rate DESC / principal DESC / loanId ASC。返回 null 表示无 loan 可偿。 */
    private CrossLoanRecord pickCrossLoanToRepay(UserProfile up) {
        CrossLoanRecord best = null;
        for (CrossLoanRecord loan : up.crossLoans) {
            if (loan.outstandingPrincipal <= 0)
                continue;
            if (best == null || loan.rateBps > best.rateBps
                || (loan.rateBps == best.rateBps && loan.outstandingPrincipal > best.outstandingPrincipal)
                || (loan.rateBps == best.rateBps && loan.outstandingPrincipal == best.outstandingPrincipal
                    && loan.loanId < best.loanId)) {
                best = loan;
            }
        }
        return best;
    }

    /** 卖多少（下单张数）：覆盖真实债务 + 5% buffer（fee + tolerance 冗余），封顶 available（都换算成 lot 后取小）。 */
    private long calculateCrossSellSize(CrossLoanRecord targetLoan, CoreSymbolSpecification spec, long markPrice,
        long available, long now, LoanService loanService, CoreCurrencySpecification sellingCurrencySpec,
        CoreCurrencySpecification loanCurrencySpec) {
        long realDebt =
            Math.addExact(targetLoan.outstandingPrincipal, loanService.calculateDisplayInterest(targetLoan, now));
        if (realDebt <= 0 || markPrice <= 0)
            return 0;
        long neededQuote = Math.multiplyExact(realDebt, 10000L + 500L) / 10000L; // +5% buffer
        long neededLots = LoanService.quoteAmountToLots(neededQuote, markPrice, spec, loanCurrencySpec);
        long availableLots = LoanService.collateralAmountToLots(available, spec, sellingCurrencySpec);
        return Math.min(availableLots, neededLots);
    }

    // ---- Margin call 通知 + 节流 ----

    // 两 lane 共用：throttleKey = loanId(Isolated) / uid(Cross)；走 leader-local ring-buffer bypass raft，best-effort。
    private void sendMarginCallIfNotThrottled(LaneState lane, long throttleKey, long uid, long loanId, byte mode,
        int loanCurrency, long ltvBps, long thresholdBps) {
        long nowMs = System.currentTimeMillis();
        if (nowMs - lane.marginCallThrottleMs.get(throttleKey) < MARGIN_CALL_THROTTLE_MS)
            return;
        lane.marginCallThrottleMs.put(throttleKey, nowMs);
        FundEvent event =
            engine.getEventsHelper().sendLoanMarginCallEvent(uid, loanId, mode, loanCurrency, ltvBps, thresholdBps);
        engine.getCommandSubmitter().submit(ApiSystemLiquidationNotify.builder().fundEvent(event).build(),
            null);
    }

    // ---- In-flight 去重 + publish ----

    public boolean isIsolatedLoanInFlight(long loanId) {
        return isolated.inFlight.contains(loanId);
    }

    public boolean isCrossLoanInFlight(long uid) {
        return cross.inFlight.contains(uid);
    }

    /** Isolated force-sell publish 追踪：in-flight set keyed by loanId。 */
    public void publishTrackedIsolated(ApiCommand cmd, long loanId) {
        publishTracked(isolated, cmd, loanId);
    }

    /** Cross force-sell publish 追踪：in-flight set keyed by uid（Cross 一账户一 pending）。 */
    public void publishTrackedCross(ApiCommand cmd, long uid) {
        publishTracked(cross, cmd, uid);
    }

    // 两 lane 共用：key 进 in-flight，publish 的 onApplied 回调清掉（异常路径也清，避免死值）。
    private void publishTracked(LaneState lane, ApiCommand cmd, long key) {
        lane.inFlight.add(key);
        try {
            engine.getCommandSubmitter().submit(cmd, () -> lane.inFlight.remove(key));
        } catch (Throwable t) {
            lane.inFlight.remove(key);
            throw t;
        }
    }

    // ---- 小工具 ----

    /** 容差爬梯：卡单越多限价容差越宽（吃更深档位）。&lt; 3 次 → 1%，&lt; 6 次 → 2%，否则 5%（封顶）。 */
    private static long toleranceBpsFor(int stuckLiqAttempts) {
        if (stuckLiqAttempts >= 6)
            return 500L;
        if (stuckLiqAttempts >= 3)
            return 200L;
        return 100L;
    }

    /** 卡单节流 + 告警（两 lane 共用）：30s 内已重发过则跳过（避免刷 raft）；连续零成交 ≥ 10 次告警（多半空盘）。 */
    private static boolean stuckLiqThrottled(String lane, LongLongHashMap lastLiqMs, long key, int stuckAttempts) {
        if (stuckAttempts == 0)
            return false;
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastLiqMs.get(key) < 30_000L)
            return true;
        if (stuckAttempts >= 10)
            log.warn("Loan liquidation STUCK: lane={} key={} attempts={} (likely no liquidity)", lane, key,
                stuckAttempts);
        lastLiqMs.put(key, nowMs);
        return false;
    }

    /** 限价 = markPrice × (10000 − toleranceBps) / 10000。 */
    private static long limitPriceWithTolerance(long markPrice, long toleranceBps) {
        return Math.multiplyExact(markPrice, 10000L - toleranceBps) / 10000L;
    }
}
