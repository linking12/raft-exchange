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
 * 现货借贷子域 scanner —— per-shard 一份实例，由 {@link LiquidationEngine} 每 tick 委托进来。
 *
 * <p><b>二级 scanner 入口</b>：{@link LiquidationEngine#checkLiquidations} per-user 循环里，期货扫完再调本类的
 * {@link #check(UserProfile)}；LiquidationEngine 主循环里**永远看不到** loan 扫描细节。
 *
 * <p><b>本类承担</b>：Isolated + Cross 两条 loan 扫描 lane —— in-flight 去重、真实债务(含利息) LTV / 期限判定，
 * 触发时构造 force-sell IOC 单 publish。跟 {@link LoanCommandHandlers} 完全对称——**持** {@link LiquidationEngine}
 * 单一 ref，通过它现取 SymbolSpecificationProvider / CurrencySpecificationProvider / lastPriceCache / LoanService /
 * LiquidationCmdPublisher，不缓存。
 *
 * <p><b>状态</b>：in-flight sets（Isolated keyed by loanId / Cross keyed by uid）—— 进程级，不进 raft snapshot，
 * publisher onApplied 回调清。无 raft-side ctx——scanner 每 tick 独立从 LTV / 期限判定，postProcess 结束后
 * 下一轮 tick 可立即重触发。
 *
 * <p><b>触发 → 结算流程</b>：scanner publish {@link ApiLoanForceLiquidate} / {@link ApiLoanCrossForceLiquidate} →
 * raft 共识 → apply 侧 {@link LoanCommandHandlers#handleLoanForceLiquidate}（pre-move 抵押到 exchangeLocked）→
 * orderbook 标准 spot ASK IOC 撮合 → R2 {@link LoanCommandHandlers#postProcessLoanForceLiquidate} 路由 quote
 * proceeds 到 loanLiqFees / interestRevenue / poolAvailable，underwater 走 badDebt。
 */
@Slf4j
public final class LoanLiquidationEngine {

    // Tolerance 分级 v1 hardcode 1%——限价 = markPrice × (10000 − TOLERANCE_BPS) / 10000。
    // v2 走 spec 或分级 ladder（stuck detection 部分放宽 1% → 2% → 5%）。
    private static final long ISOLATED_TOLERANCE_BPS = 100L;
    private static final long CROSS_TOLERANCE_BPS = 100L;

    // Cross sellSize heuristic：覆盖 realDebt × (10000 + BUFFER_BPS) / 10000（fee + tolerance 冗余）。
    private static final long CROSS_SELL_BUFFER_BPS = 500L; // 5%

    private static final long NS_PER_DAY = 86400L * 1_000_000_000L;
    // Margin call 节流窗口 5 min，防用户短时间内被同一 loan 反复通知
    private static final long MARGIN_CALL_THROTTLE_MS = 5L * 60L * 1_000L;

    private final LiquidationEngine engine;
    private final MultiReaderSet<Long> inFlightIsolatedLoanLiq = Sets.multiReader.empty();
    private final MultiReaderSet<Long> inFlightCrossLoanLiq = Sets.multiReader.empty();
    // 节流 per-loanId (Isolated) / per-uid (Cross)，进程 local（不进 raft snapshot）
    private final LongLongHashMap lastIsolatedMarginCallMs = new LongLongHashMap();
    private final LongLongHashMap lastCrossMarginCallMs = new LongLongHashMap();

    public LoanLiquidationEngine(LiquidationEngine engine) {
        this.engine = engine;
    }

    // ================================================================
    // Scanner 入口
    // ================================================================

    public void check(UserProfile userProfile) {
        checkIsolated(userProfile);
        checkCross(userProfile);
    }

    private void checkIsolated(UserProfile userProfile) {
        userProfile.isolatedLoans.forEachValue(loan -> {
            if (loan.isEmpty())
                return;
            if (inFlightIsolatedLoanLiq.contains(loan.loanId))
                return;

            CoreSymbolSpecification spec = LoanService.findSpotSpec(loan.collateralCurrency, loan.loanCurrency,
                engine.getSymbolSpecificationProvider());
            if (spec == null)
                return;
            LastPriceCacheRecord priceRecord = engine.getLastPriceCache().get(spec.symbolId);
            if (priceRecord == null || priceRecord.markPrice == 0)
                return;

            long currentTickNs = System.currentTimeMillis() * 1_000_000L;
            boolean termExpired = spec.loanMaxTermDays > 0
                && (currentTickNs - loan.openedAtTs) > spec.loanMaxTermDays * NS_PER_DAY;

            // 单位统一到 loanCurrency currencyScale 下比（不做换算原公式恒 pass）
            CoreCurrencySpecification baseSpec =
                engine.getCurrencySpecificationProvider().getCurrencySpecification(loan.collateralCurrency);
            CoreCurrencySpecification loanCurrencySpec =
                engine.getCurrencySpecificationProvider().getCurrencySpecification(loan.loanCurrency);
            long collateralValueInLoanCurrency = LoanService.collateralValueInQuoteCurrency(loan.collateralAmount, spec,
                priceRecord.markPrice, baseSpec, loanCurrencySpec);
            if (collateralValueInLoanCurrency < 0)
                return;

            // 真实债务含 accumulatedInterest + pending accrue，避免用户拖债不还避强平
            long realDebt = Math.addExact(loan.outstandingPrincipal,
                engine.getLoanService().calculateDisplayInterest(loan, currentTickNs));
            long ltvScaled = Math.multiplyExact(realDebt, LoanService.BPS_SCALE);
            long thresholdLiq = Math.multiplyExact(collateralValueInLoanCurrency, (long)spec.loanLiquidationLtvBps);

            if (termExpired || ltvScaled >= thresholdLiq) {
                publishIsolatedForceSell(loan, spec, priceRecord.markPrice);
                return;
            }

            if (spec.loanMarginCallLtvBps > 0) {
                long thresholdWarn = Math.multiplyExact(collateralValueInLoanCurrency, (long)spec.loanMarginCallLtvBps);
                if (ltvScaled >= thresholdWarn) {
                    long ltvBps = collateralValueInLoanCurrency == 0 ? 0
                        : Math.multiplyExact(realDebt, LoanService.BPS_SCALE) / collateralValueInLoanCurrency;
                    sendIsolatedMarginCallIfNotThrottled(loan, ltvBps);
                }
            }
        });
    }

    private void checkCross(UserProfile userProfile) {
        if (userProfile.crossLoans.isEmpty())
            return;
        if (inFlightCrossLoanLiq.contains(userProfile.uid))
            return;

        LoanService loanService = engine.getLoanService();
        long currentTickNs = System.currentTimeMillis() * 1_000_000L;
        // v1 numeraire 未配置时 ltvBps == 0，直接不触发（保守）
        long ltvBps = loanService.calculateCrossAccountLtvBps(userProfile, currentTickNs,
            engine.getSymbolSpecificationProvider(), engine.getCurrencySpecificationProvider(),
            engine.getLastPriceCache(), loanService.getNumeraireCurrency());

        if (ltvBps >= loanService.getCrossLiquidationLtvBps()) {
            publishCrossForceSell(userProfile, loanService, currentTickNs);
            return;
        }
        if (ltvBps >= loanService.getCrossMarginCallLtvBps()) {
            sendCrossMarginCallIfNotThrottled(userProfile.uid, ltvBps);
        }
    }

    // ================================================================
    // Force-sell 构造 + publish
    // ================================================================

    /**
     * 构造 Isolated 强平 IOC 单并 publishTracked。orderId 顶字节 'L' + subtype 'S'
     * （{@link LoanService#generateIsolatedForceSellOrderId}），跟 futures 强平 orderId 空间隔离。
     * 限价 = markPrice × (10000 − {@value #ISOLATED_TOLERANCE_BPS}) / 10000。
     */
    private void publishIsolatedForceSell(IsolatedLoanRecord loan, CoreSymbolSpecification spec, long markPrice) {
        // 抵押金额（currencyScale）→ 下单张数（lot）；不足一张的尘埃卖不掉，留在 collateralAmount，跳过本轮
        CoreCurrencySpecification baseSpec =
            engine.getCurrencySpecificationProvider().getCurrencySpecification(loan.collateralCurrency);
        long sellSizeLots = LoanService.collateralAmountToLots(loan.collateralAmount, spec, baseSpec);
        if (sellSizeLots <= 0) {
            log.warn("Isolated force-sell skip: sub-lot collateral (uid={} loanId={} collateral={})",
                loan.uid, loan.loanId, loan.collateralAmount);
            return;
        }
        long orderId = LoanService.generateIsolatedForceSellOrderId(loan);
        long limitPrice = limitPriceWithTolerance(markPrice, ISOLATED_TOLERANCE_BPS);
        ApiLoanForceLiquidate cmd = ApiLoanForceLiquidate.builder().uid(loan.uid).symbol(spec.symbolId)
            .loanId(loan.loanId).price(limitPrice).size(sellSizeLots).orderId(orderId)
            .action(OrderAction.ASK).orderType(OrderType.IOC).build();
        publishTrackedIsolated(cmd, loan.loanId);
    }

    /**
     * Cross 强平：tiebreak 选一对 (sellingCurrency, targetLoan) → 算 sellSize → publish。
     * Scanner 单轮只处理一对，下轮 tick 重估 LTV 决定是否继续（迭代 deleveraging）。
     */
    private void publishCrossForceSell(UserProfile up, LoanService loanService, long currentTickNs) {
        int sellingCurrency = pickCrossCollateralToSell(up);
        if (sellingCurrency == 0) {
            log.warn("Cross force-sell abort: no collateral to sell (uid={})", up.uid);
            return;
        }
        CrossLoanRecord targetLoan = pickCrossLoanToRepay(up);
        if (targetLoan == null) {
            log.warn("Cross force-sell abort: no target loan (uid={})", up.uid);
            return;
        }
        CoreSymbolSpecification spec = LoanService.findSpotSpec(sellingCurrency, targetLoan.loanCurrency,
            engine.getSymbolSpecificationProvider());
        if (spec == null) {
            log.warn("Cross force-sell abort: no spot pair sellingCurrency={} loanCurrency={} (uid={})", sellingCurrency,
                targetLoan.loanCurrency, up.uid);
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
            currentTickNs, loanService, sellingCurrencySpec, loanCurrencySpec);
        if (sellSize <= 0) {
            log.warn("Cross force-sell abort: sellSize=0 (uid={} sellingCurrency={} available={})", up.uid, sellingCurrency,
                availableCollateral);
            return;
        }
        long orderId = LoanService.generateCrossForceSellOrderId(up.uid, sellingCurrency);
        long limitPrice = limitPriceWithTolerance(priceRecord.markPrice, CROSS_TOLERANCE_BPS);
        ApiLoanCrossForceLiquidate cmd = ApiLoanCrossForceLiquidate.builder().uid(up.uid).symbol(spec.symbolId)
            .targetLoanId(targetLoan.loanId).price(limitPrice).size(sellSize).orderId(orderId)
            .action(OrderAction.ASK).orderType(OrderType.IOC).build();
        publishTrackedCross(cmd, up.uid);
    }

    // ================================================================
    // Cross tiebreak + heuristic
    // ================================================================

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
            if (weight > bestWeight
                || (weight == bestWeight && amount > bestAmount)
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
            if (best == null
                || loan.rateBps > best.rateBps
                || (loan.rateBps == best.rateBps && loan.outstandingPrincipal > best.outstandingPrincipal)
                || (loan.rateBps == best.rateBps && loan.outstandingPrincipal == best.outstandingPrincipal
                    && loan.loanId < best.loanId)) {
                best = loan;
            }
        }
        return best;
    }

    /**
     * 卖多少（返回下单张数 / lot）：覆盖 targetLoan 真实债务 + {@value #CROSS_SELL_BUFFER_BPS} bps buffer（fee + tolerance），
     * 封顶 available。neededQuote 和 available 都换算成 lot 后再取小。
     */
    private long calculateCrossSellSize(CrossLoanRecord targetLoan, CoreSymbolSpecification spec, long markPrice,
        long available, long now, LoanService loanService, CoreCurrencySpecification sellingCurrencySpec,
        CoreCurrencySpecification loanCurrencySpec) {
        long realDebt =
            Math.addExact(targetLoan.outstandingPrincipal, loanService.calculateDisplayInterest(targetLoan, now));
        if (realDebt <= 0 || markPrice <= 0)
            return 0;
        long neededQuote = Math.multiplyExact(realDebt, 10000L + CROSS_SELL_BUFFER_BPS) / 10000L;
        long neededLots = LoanService.quoteAmountToLots(neededQuote, markPrice, spec, loanCurrencySpec);
        long availableLots = LoanService.collateralAmountToLots(available, spec, sellingCurrencySpec);
        return Math.min(availableLots, neededLots);
    }

    /** 限价 = markPrice × (10000 − toleranceBps) / 10000。 */
    private static long limitPriceWithTolerance(long markPrice, long toleranceBps) {
        return Math.multiplyExact(markPrice, 10000L - toleranceBps) / 10000L;
    }

    // ================================================================
    // Margin call 通知 + 节流
    // ================================================================

    private void sendIsolatedMarginCallIfNotThrottled(IsolatedLoanRecord loan, long ltvBps) {
        long nowMs = System.currentTimeMillis();
        long lastMs = lastIsolatedMarginCallMs.get(loan.loanId);
        if (nowMs - lastMs < MARGIN_CALL_THROTTLE_MS)
            return;
        lastIsolatedMarginCallMs.put(loan.loanId, nowMs);
        FundEvent event = engine.getEventsHelper().sendLoanIsolatedMarginCallEvent(loan.uid, loan.loanId, loan.loanCurrency,
            ltvBps);
        publishMarginCall(event);
    }

    private void sendCrossMarginCallIfNotThrottled(long uid, long ltvBps) {
        long nowMs = System.currentTimeMillis();
        long lastMs = lastCrossMarginCallMs.get(uid);
        if (nowMs - lastMs < MARGIN_CALL_THROTTLE_MS)
            return;
        lastCrossMarginCallMs.put(uid, nowMs);
        FundEvent event = engine.getEventsHelper().sendLoanCrossMarginCallEvent(uid, ltvBps);
        publishMarginCall(event);
    }

    /** 走 leader-local ring-buffer bypass raft，跟 futures MARGIN_ALERT 同款 best-effort 通道。 */
    private void publishMarginCall(FundEvent event) {
        engine.getLiquidationCmdPublisher()
            .publish(ApiSystemLiquidationNotify.builder().fundEvent(event).build(), null);
    }

    // ================================================================
    // In-flight guard + publish（跟 LiquidationEngine.publishTracked 对称）
    // ================================================================

    public boolean isIsolatedLoanInFlight(long loanId) {
        return inFlightIsolatedLoanLiq.contains(loanId);
    }

    public boolean isCrossLoanInFlight(long uid) {
        return inFlightCrossLoanLiq.contains(uid);
    }

    /** Isolated loan force-sell publish 追踪：in-flight set keyed by loanId。 */
    public void publishTrackedIsolated(ApiCommand cmd, long loanId) {
        inFlightIsolatedLoanLiq.add(loanId);
        try {
            engine.getLiquidationCmdPublisher().publish(cmd, () -> inFlightIsolatedLoanLiq.remove(loanId));
        } catch (Throwable t) {
            inFlightIsolatedLoanLiq.remove(loanId);
            throw t;
        }
    }

    /** Cross loan force-sell publish 追踪：in-flight set keyed by uid（Cross 一账户一 pending）。 */
    public void publishTrackedCross(ApiCommand cmd, long uid) {
        inFlightCrossLoanLiq.add(uid);
        try {
            engine.getLiquidationCmdPublisher().publish(cmd, () -> inFlightCrossLoanLiq.remove(uid));
        } catch (Throwable t) {
            inFlightCrossLoanLiq.remove(uid);
            throw t;
        }
    }
}
