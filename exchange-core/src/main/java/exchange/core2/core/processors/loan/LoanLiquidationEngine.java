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
 * proceeds 到 loanLiquidationFees / interestRevenue / poolAvailable，underwater 走 badDebt。
 */
@Slf4j
public final class LoanLiquidationEngine {

    // 强平限价容差爬梯（限价 = markPrice × (10000 − tolerance) / 10000）：卡单（连续零成交）越多，容差越宽，
    // 让 IOC 吃到更深的档位。切档按 loan.stuckLiqAttempts：<3 → 1%，<6 → 2%，否则 5%（封顶）。
    private static final long TOLERANCE_BASE_BPS = 100L;   // 1%
    private static final long TOLERANCE_TIER1_BPS = 200L;  // 2%
    private static final long TOLERANCE_TIER2_BPS = 500L;  // 5%
    private static final int STUCK_TIER1_ATTEMPTS = 3;
    private static final int STUCK_TIER2_ATTEMPTS = 6;

    // 卡单重发节流：stuck 的 loan 每 30s 才重发一次（避免每 tick 刷 raft）。进程 local，failover 后重置无碍。
    private static final long STUCK_RETRY_THROTTLE_MS = 30_000L;
    // 卡单告警阈值：连续零成交达到此次数（约 STUCK_ALERT_ATTEMPTS × 30s）仍打不进 → 告警（多半是空盘）。
    private static final int STUCK_ALERT_ATTEMPTS = 10;

    // Cross sellSize heuristic：覆盖 realDebt × (10000 + BUFFER_BPS) / 10000（fee + tolerance 冗余）。
    private static final long CROSS_SELL_BUFFER_BPS = 500L; // 5%

    private static final long MS_PER_DAY = 86400L * 1_000L;
    // Margin call 节流窗口 5 min，防用户短时间内被同一 loan 反复通知
    private static final long MARGIN_CALL_THROTTLE_MS = 5L * 60L * 1_000L;

    private final LiquidationEngine engine;

    /**
     * 每条 lane（Isolated / Cross）的进程级状态：in-flight 去重 + margin-call / 卡单重发两类节流窗口。
     * 不进 raft snapshot（failover 后重置无碍）；key = loanId(Isolated) / uid(Cross)。
     */
    private static final class LaneState {
        final MultiReaderSet<Long> inFlight = Sets.multiReader.empty();      // 已 publish 未结算的 key，去重防重复强平
        final LongLongHashMap marginCallThrottleMs = new LongLongHashMap();  // key → 上次 margin-call 通知时刻（ms）
        final LongLongHashMap liqRetryThrottleMs = new LongLongHashMap();    // key → 上次卡单重发时刻（ms）
    }

    private final LaneState isolated = new LaneState();
    private final LaneState cross = new LaneState();

    /** 容差爬梯：连续零成交次数 → 限价容差 bps。 */
    private static long toleranceBpsFor(int stuckLiqAttempts) {
        if (stuckLiqAttempts >= STUCK_TIER2_ATTEMPTS)
            return TOLERANCE_TIER2_BPS;
        if (stuckLiqAttempts >= STUCK_TIER1_ATTEMPTS)
            return TOLERANCE_TIER1_BPS;
        return TOLERANCE_BASE_BPS;
    }

    /**
     * 卡单节流 + 告警（Isolated / Cross 共用）。未卡（attempts==0）直接放行；卡住的每 STUCK_RETRY_THROTTLE_MS
     * 才放行一次，超阈值发告警。返回 true 表示本轮应跳过（仍在节流窗口内）。key = loanId(Isolated) / uid(Cross)。
     */
    private boolean stuckLiqThrottled(String lane, LongLongHashMap lastLiqMs, long key, int stuckAttempts) {
        if (stuckAttempts == 0)
            return false;
        long nowMs = System.currentTimeMillis();
        if (nowMs - lastLiqMs.get(key) < STUCK_RETRY_THROTTLE_MS)
            return true;
        if (stuckAttempts >= STUCK_ALERT_ATTEMPTS)
            log.warn("Loan liquidation STUCK: lane={} key={} attempts={} (likely no liquidity)", lane, key,
                stuckAttempts);
        lastLiqMs.put(key, nowMs);
        return false;
    }

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
            if (isolated.inFlight.contains(loan.loanId))
                return;

            CoreSymbolSpecification spec = LoanService.findSpotSpec(loan.collateralCurrency, loan.loanCurrency,
                engine.getSymbolSpecificationProvider());
            if (spec == null)
                return;
            LastPriceCacheRecord priceRecord = engine.getLastPriceCache().get(spec.symbolId);
            if (priceRecord == null || priceRecord.markPrice == 0)
                return;

            long currentTickMs = System.currentTimeMillis();
            boolean termExpired = spec.loanMaxTermDays > 0
                && (currentTickMs - loan.openedAtTs) > spec.loanMaxTermDays * MS_PER_DAY;

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
                engine.getLoanService().calculateDisplayInterest(loan, currentTickMs));
            long ltvScaled = Math.multiplyExact(realDebt, LoanService.BPS_SCALE);
            long thresholdLiq = Math.multiplyExact(collateralValueInLoanCurrency, (long)spec.loanLiquidationLtvBps);

            if (termExpired || ltvScaled >= thresholdLiq) {
                if (stuckLiqThrottled("isolated", isolated.liqRetryThrottleMs, loan.loanId, loan.stuckLiqAttempts))
                    return;
                publishIsolatedForceSell(loan, spec, priceRecord.markPrice, toleranceBpsFor(loan.stuckLiqAttempts));
                return;
            }

            if (spec.loanMarginCallLtvBps > 0) {
                long thresholdWarn = Math.multiplyExact(collateralValueInLoanCurrency, (long)spec.loanMarginCallLtvBps);
                if (ltvScaled >= thresholdWarn) {
                    long ltvBps = collateralValueInLoanCurrency == 0 ? 0
                        : Math.multiplyExact(realDebt, LoanService.BPS_SCALE) / collateralValueInLoanCurrency;
                    sendIsolatedMarginCallIfNotThrottled(loan, ltvBps, spec.loanMarginCallLtvBps);
                }
            }
        });
    }

    private void checkCross(UserProfile userProfile) {
        if (userProfile.crossLoans.isEmpty())
            return;
        if (cross.inFlight.contains(userProfile.uid))
            return;

        LoanService loanService = engine.getLoanService();
        long currentTickMs = System.currentTimeMillis();

        // 期限强平优先：到期是硬约束、不依赖 numeraire 估值（只看 openedAtTs + term），到期即平那笔
        CrossLoanRecord expired = pickExpiredCrossLoan(userProfile, currentTickMs);
        if (expired != null) {
            publishCrossForceSell(userProfile, loanService, currentTickMs, expired);
            return;
        }

        // LTV 强平/预警：numeraire 未配置时 ltvBps == 0，直接不触发（保守）
        long ltvBps = loanService.calculateCrossAccountLtvBps(userProfile, currentTickMs,
            engine.getSymbolSpecificationProvider(), engine.getCurrencySpecificationProvider(),
            engine.getLastPriceCache(), loanService.getNumeraireCurrency());

        if (ltvBps >= loanService.getCrossLiquidationLtvBps()) {
            publishCrossForceSell(userProfile, loanService, currentTickMs, null);
            return;
        }
        if (ltvBps >= loanService.getCrossMarginCallLtvBps()) {
            sendCrossMarginCallIfNotThrottled(userProfile.uid, ltvBps, loanService.getCrossMarginCallLtvBps());
        }
    }

    // ================================================================
    // Force-sell 构造 + publish
    // ================================================================

    /**
     * 构造 Isolated 强平 IOC 单并 publishTracked。orderId 顶字节 'L' + subtype 'S'
     * （{@link LoanService#generateIsolatedForceSellOrderId}），跟 futures 强平 orderId 空间隔离。
     * 限价 = markPrice × (10000 − toleranceBps) / 10000，toleranceBps 由卡单爬梯给出。
     */
    private void publishIsolatedForceSell(IsolatedLoanRecord loan, CoreSymbolSpecification spec, long markPrice,
        long toleranceBps) {
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
        long limitPrice = limitPriceWithTolerance(markPrice, toleranceBps);
        ApiLoanForceLiquidate cmd = ApiLoanForceLiquidate.builder().uid(loan.uid).symbol(spec.symbolId)
            .loanId(loan.loanId).price(limitPrice).size(sellSizeLots).orderId(orderId)
            .action(OrderAction.ASK).orderType(OrderType.IOC).build();
        publishTrackedIsolated(cmd, loan.loanId);
    }

    /**
     * Cross 强平：tiebreak 选一对 (sellingCurrency, targetLoan) → 算 sellSize → publish。
     * Scanner 单轮只处理一对，下轮 tick 重估 LTV 决定是否继续（迭代 deleveraging）。
     */
    /** preselectedTarget != null（期限强平）时强平该指定 loan；否则（LTV 强平）按 tiebreak 选一笔降账户 LTV。 */
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
            currentTickMs, loanService, sellingCurrencySpec, loanCurrencySpec);
        if (sellSize <= 0) {
            log.warn("Cross force-sell abort: sellSize=0 (uid={} sellingCurrency={} available={})", up.uid, sellingCurrency,
                availableCollateral);
            return;
        }
        // 卡单节流（per-uid）+ 容差爬梯（按 targetLoan 的连续零成交次数）
        if (stuckLiqThrottled("cross", cross.liqRetryThrottleMs, up.uid, targetLoan.stuckLiqAttempts))
            return;
        long orderId = LoanService.generateCrossForceSellOrderId(up.uid, sellingCurrency);
        long limitPrice = limitPriceWithTolerance(priceRecord.markPrice, toleranceBpsFor(targetLoan.stuckLiqAttempts));
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
     * 挑一笔已到期（{@code now - openedAtTs > loanMaxTermDays}）的 Cross loan；确定性选 loanId 最小。
     * 期限从 loanCurrency 对应 spec fresh 读（跟 Isolated / 借款时一致，不 snapshot）；无匹配返回 null。
     */
    private CrossLoanRecord pickExpiredCrossLoan(UserProfile up, long currentTickMs) {
        CrossLoanRecord best = null;
        for (CrossLoanRecord loan : up.crossLoans) {
            if (loan.outstandingPrincipal <= 0)
                continue;
            CoreSymbolSpecification spec = LoanService.findLoanSpecByQuoteCurrency(loan.loanCurrency,
                engine.getSymbolSpecificationProvider());
            if (spec == null || spec.loanMaxTermDays <= 0)
                continue;
            if ((currentTickMs - loan.openedAtTs) <= spec.loanMaxTermDays * MS_PER_DAY)
                continue;
            if (best == null || loan.loanId < best.loanId)
                best = loan;
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

    // loanMode: 0 = Isolated，1 = Cross（对齐 FundEvent.loanMode 约定）
    private static final byte LOAN_MODE_ISOLATED = 0;
    private static final byte LOAN_MODE_CROSS = 1;

    private void sendIsolatedMarginCallIfNotThrottled(IsolatedLoanRecord loan, long ltvBps, long thresholdBps) {
        long nowMs = System.currentTimeMillis();
        long lastMs = isolated.marginCallThrottleMs.get(loan.loanId);
        if (nowMs - lastMs < MARGIN_CALL_THROTTLE_MS)
            return;
        isolated.marginCallThrottleMs.put(loan.loanId, nowMs);
        FundEvent event = engine.getEventsHelper().sendLoanMarginCallEvent(loan.uid, loan.loanId, LOAN_MODE_ISOLATED,
            loan.loanCurrency, ltvBps, thresholdBps);
        publishMarginCall(event);
    }

    private void sendCrossMarginCallIfNotThrottled(long uid, long ltvBps, long thresholdBps) {
        long nowMs = System.currentTimeMillis();
        long lastMs = cross.marginCallThrottleMs.get(uid);
        if (nowMs - lastMs < MARGIN_CALL_THROTTLE_MS)
            return;
        cross.marginCallThrottleMs.put(uid, nowMs);
        // Cross 账户级：loanId=0、loanCurrency=0（无单笔归属）
        FundEvent event = engine.getEventsHelper().sendLoanMarginCallEvent(uid, 0L, LOAN_MODE_CROSS, 0, ltvBps,
            thresholdBps);
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
        return isolated.inFlight.contains(loanId);
    }

    public boolean isCrossLoanInFlight(long uid) {
        return cross.inFlight.contains(uid);
    }

    /** Isolated loan force-sell publish 追踪：in-flight set keyed by loanId。 */
    public void publishTrackedIsolated(ApiCommand cmd, long loanId) {
        isolated.inFlight.add(loanId);
        try {
            engine.getLiquidationCmdPublisher().publish(cmd, () -> isolated.inFlight.remove(loanId));
        } catch (Throwable t) {
            isolated.inFlight.remove(loanId);
            throw t;
        }
    }

    /** Cross loan force-sell publish 追踪：in-flight set keyed by uid（Cross 一账户一 pending）。 */
    public void publishTrackedCross(ApiCommand cmd, long uid) {
        cross.inFlight.add(uid);
        try {
            engine.getLiquidationCmdPublisher().publish(cmd, () -> cross.inFlight.remove(uid));
        } catch (Throwable t) {
            cross.inFlight.remove(uid);
            throw t;
        }
    }
}
