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

import static exchange.core2.core.processors.liquidation.LiquidationScheduledService.coveredByScanSlice;

import java.util.function.Supplier;

import org.eclipse.collections.api.set.primitive.MutableLongSet;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.CrossLoanRecord;
import exchange.core2.core.common.FundEvent;
import exchange.core2.core.common.IsolatedLoanRecord;
import exchange.core2.core.common.OrderAction;
import exchange.core2.core.common.OrderType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.api.ApiLoanCrossForceLiquidate;
import exchange.core2.core.common.api.ApiLoanForceLiquidate;
import exchange.core2.core.common.api.ApiSystemLiquidationNotify;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.FundEventsHelper;
import exchange.core2.core.processors.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.UserProfileService;
import exchange.core2.core.processors.liquidation.LiquidationCommandSubmitter;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class LoanLiquidationEngine {
    private static final long MS_PER_DAY = 86400L * 1_000L; // 天→毫秒，期限强平换算
    private static final byte LOAN_MODE_ISOLATED = 0;
    private static final byte LOAN_MODE_CROSS = 1;

    private final IntObjectHashMap<MutableLongSet> isolatedLoanSymbolToUsers; // symbolId → uids
    private final IntObjectHashMap<MutableLongSet> crossLoanCurrencyToUsers; // currency → uids
    private final FundEventsHelper eventsHelper;
    private final Supplier<LiquidationCommandSubmitter> commandSubmitter;

    private SymbolSpecificationProvider symbolSpecificationProvider;
    private CurrencySpecificationProvider currencySpecificationProvider;
    private UserProfileService userProfileService;
    private IntObjectHashMap<LastPriceCacheRecord> lastPriceCache;
    private LoanService loanService;

    public LoanLiquidationEngine(FundEventsHelper eventsHelper,
        Supplier<LiquidationCommandSubmitter> commandSubmitter) {
        this.eventsHelper = eventsHelper;
        this.commandSubmitter = commandSubmitter;
        this.isolatedLoanSymbolToUsers = IntObjectHashMap.newMap();
        this.crossLoanCurrencyToUsers = IntObjectHashMap.newMap();
    }

    public void updateProvider(SymbolSpecificationProvider symbolSpecProvider,
        CurrencySpecificationProvider currencySpecProvider, UserProfileService userService,
        IntObjectHashMap<LastPriceCacheRecord> lastPriceService, LoanService loanSvc) {
        this.symbolSpecificationProvider = symbolSpecProvider;
        this.currencySpecificationProvider = currencySpecProvider;
        this.userProfileService = userService;
        this.lastPriceCache = lastPriceService;
        this.loanService = loanSvc;
        isolatedLoanSymbolToUsers.clear();
        crossLoanCurrencyToUsers.clear();
        userService.getUserProfiles().forEachValue(up -> {
            up.isolatedLoans.forEachValue(loan -> {
                if (!loan.isEmpty()) {
                    onIsolatedLoanOpened(up.uid, loan.symbolId);
                }
            });
            syncCrossExposure(up);
        });
    }

    // ====================================================================================
    // 检测入口：价格事件走 targeted 索引，LIQUIDATION_SCAN 全量兜底
    // ====================================================================================

    /**
     * 强平检测入口，由 {@link LiquidationEngine#checkPositions} 委托（调用方已过 leader gate）。 {@code cmd.symbol >= 0}：查索引选受该 symbol
     * 价格影响的持有者；{@code cmd.symbol < 0}：backstop 全量。
     */
    public void checkLoans(OrderCommand cmd) {
        if (cmd.symbol >= 0) {
            final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(cmd.symbol);
            if (spec == null) {
                return;
            }
            final MutableLongSet uids = new LongHashSet();
            final MutableLongSet iso = isolatedLoanSymbolToUsers.get(spec.symbolId);
            if (iso != null) {
                uids.addAll(iso);
            }
            final MutableLongSet base = crossLoanCurrencyToUsers.get(spec.baseCurrency);
            if (base != null) {
                uids.addAll(base);
            }
            final MutableLongSet quote = crossLoanCurrencyToUsers.get(spec.quoteCurrency);
            if (quote != null) {
                uids.addAll(quote);
            }
            uids.forEach(uid -> checkUser(userProfileService.getUserProfile(uid), cmd.timestamp));
            return;
        }
        userProfileService.getUserProfiles().forEachValue(up -> {
            if (!coveredByScanSlice(cmd, up.uid)) {
                return;
            }
            checkUser(up, cmd.timestamp);
        });
    }

    public void checkUser(UserProfile userProfile, long ts) {
        if (userProfile == null) {
            return;
        }
        userProfile.isolatedLoans.forEachValue(loan -> checkIsolated(loan, ts));
        checkCross(userProfile, ts);
    }

    private void checkIsolated(IsolatedLoanRecord loan, long ts) {
        if (loan.isEmpty())
            return;
        final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(loan.symbolId);
        if (spec == null)
            return;
        final LastPriceCacheRecord priceRecord = lastPriceCache.get(spec.symbolId);
        if (priceRecord == null || priceRecord.markPrice == 0)
            return;

        final CoreCurrencySpecification baseSpec =
            currencySpecificationProvider.getCurrencySpecification(loan.collateralCurrency);
        final CoreCurrencySpecification loanCurrencySpec =
            currencySpecificationProvider.getCurrencySpecification(loan.loanCurrency);
        final long collateralValue = LoanService.collateralValueInQuoteCurrency(loan.collateralAmount, spec,
            priceRecord.markPrice, baseSpec, loanCurrencySpec);
        if (collateralValue <= 0)
            return; // 抵押估值为 0 无法定破产价（除零）

        // 真实债务含利息（已计提 + pending accrue），防拖债避强平
        final long realDebt = Math.addExact(loan.outstandingPrincipal, loanService.calculateDisplayInterest(loan, ts));
        final long ltvScaled = Math.multiplyExact(realDebt, LoanService.BPS_SCALE);

        // 期限强平仅对 Isolated LOCKED（定息）生效；FLOATING 无期限
        final boolean termExpired = loan.rateMode == IsolatedLoanRecord.RATE_MODE_LOCKED
            && spec.loanConfig.maxTermDays > 0 && (ts - loan.openedAtTs) > spec.loanConfig.maxTermDays * MS_PER_DAY;

        if (termExpired || ltvScaled >= Math.multiplyExact(collateralValue, (long)spec.loanConfig.liquidationLtvBps)) {
            // 抵押 → 下单张数；不足一张的尘埃卖不掉，留在 collateralAmount 跳过本轮。orderId 空间与 futures 强平隔离
            final long sellSizeLots = LoanService.collateralAmountToLots(loan.collateralAmount, spec, baseSpec);
            if (sellSizeLots <= 0) {
                log.warn("Isolated force-sell skip: sub-lot collateral (uid={} loanId={} collateral={})", loan.uid,
                    loan.loanId, loan.collateralAmount);
                return;
            }
            final long orderId =
                LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_ISOLATED, loan.uid, loan.loanId, ts);
            final long limitPrice = bankruptcyPrice(priceRecord.markPrice, realDebt, collateralValue);
            commandSubmitter.get()
                .submit(ApiLoanForceLiquidate.builder().uid(loan.uid).symbol(spec.symbolId).loanId(loan.loanId)
                    .price(limitPrice).size(sellSizeLots).orderId(orderId).action(OrderAction.ASK)
                    .orderType(OrderType.IOC).build(), null);
            return;
        }

        final int marginCallLtvBps = spec.loanConfig.marginCallLtvBps;
        if (marginCallLtvBps > 0 && ltvScaled >= Math.multiplyExact(collateralValue, (long)marginCallLtvBps)) {
            final long ltvBps = collateralValue == 0 ? 0 : ltvScaled / collateralValue;
            sendMarginCall(loan.uid, loan.loanId, LOAN_MODE_ISOLATED, loan.loanCurrency, ltvBps, marginCallLtvBps);
        }
    }

    /**
     * Cross 账户级强平判定：越线后渐进去杠杆，每轮只选一对 (卖出抵押币, 偿还目标 loan) 成交，多轮收敛。
     *
     * <p>
     * <b>"存在就绪现货对"必须是 pick 的过滤条件而非事后校验</b>：两个 pick 只看权重/利率、与价格无关， 因而完全确定性——若先各挑最优再校验市场，选出的那对没有现货对时会每轮重选出同一对、永久空转。 下沉进
     * pick 后，最优组合无市场会自动退到次优可成交组合。
     */
    private void checkCross(UserProfile userProfile, long ts) {
        if (userProfile.crossLoans.isEmpty())
            return;
        final long ltvBps = loanService.calculateCrossAccountLtvBps(userProfile, ts, symbolSpecificationProvider,
            currencySpecificationProvider, lastPriceCache, loanService.getGlobalConfig().numeraireCurrency);
        if (ltvBps >= loanService.getGlobalConfig().crossLiquidationLtvBps) {
            final int sellingCurrency = pickCrossCollateralToSell(userProfile);
            if (sellingCurrency == 0) {
                log.warn("Cross force-sell abort: no sellable collateral with a ready spot market (uid={})",
                    userProfile.uid);
                return;
            }
            final CrossLoanRecord targetLoan = pickCrossLoanToRepay(userProfile, sellingCurrency);
            if (targetLoan == null) {
                log.warn("Cross force-sell abort: no repayable target loan (uid={})", userProfile.uid);
                return;
            }
            // pick 的前置约束已保证该现货对存在且 markPrice 就绪，此处直接取用、无需再判空
            final CoreSymbolSpecification spec =
                symbolSpecificationProvider.findSpotSymbol(sellingCurrency, targetLoan.loanCurrency);
            final LastPriceCacheRecord priceRecord = lastPriceCache.get(spec.symbolId);
            final long availableCollateral = userProfile.crossLoanCollateral.get(sellingCurrency);
            final CoreCurrencySpecification sellingCurrencySpec =
                currencySpecificationProvider.getCurrencySpecification(sellingCurrency);
            final CoreCurrencySpecification loanCurrencySpec =
                currencySpecificationProvider.getCurrencySpecification(targetLoan.loanCurrency);
            // 破产价按市值口径 LTV 定（触发用加权 LTV，见 calculateCrossRawLtvBps）；卖量随该价算，
            // 按 markPrice 算则打折卖必然不够还债
            final long rawLtvBps = loanService.calculateCrossRawLtvBps(userProfile, ts, symbolSpecificationProvider,
                currencySpecificationProvider, lastPriceCache, loanService.getGlobalConfig().numeraireCurrency);
            // 市值口径估不出来（某抵押币无 numeraire 估值路径）时退回加权 LTV：报价偏保守，但绝不因此放弃强平
            final long pricingLtvBps = rawLtvBps > 0 ? rawLtvBps : ltvBps;
            final long limitPrice =
                CoreArithmeticUtils.ceilMulDiv(priceRecord.markPrice, pricingLtvBps, LoanService.BPS_SCALE);
            final long sellSize = calculateCrossSellSize(targetLoan, spec, limitPrice, availableCollateral, ts,
                loanService, sellingCurrencySpec, loanCurrencySpec);
            if (sellSize <= 0) {
                log.warn("Cross force-sell abort: sellSize=0 (uid={} sellingCurrency={} available={})", userProfile.uid,
                    sellingCurrency, availableCollateral);
                return;
            }
            final long orderId =
                LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_CROSS, userProfile.uid, targetLoan.loanId, ts);
            commandSubmitter.get()
                .submit(ApiLoanCrossForceLiquidate.builder().uid(userProfile.uid).symbol(spec.symbolId)
                    .targetLoanId(targetLoan.loanId).price(limitPrice).size(sellSize).orderId(orderId)
                    .action(OrderAction.ASK).orderType(OrderType.IOC).build(), null);
        } else if (ltvBps >= loanService.getGlobalConfig().crossMarginCallLtvBps) {
            // Cross 账户级预警：loanId / loanCurrency 无单笔归属，填 0
            sendMarginCall(userProfile.uid, 0L, LOAN_MODE_CROSS, 0, ltvBps,
                loanService.getGlobalConfig().crossMarginCallLtvBps);
        }
    }

    // ====================================================================================
    // targeted 索引维护：由 LoanCommandDispatcher 在 apply 时确定性更新，不进 snapshot
    // ====================================================================================

    /** isolated loan 开仓：uid 登记进 symbolId 索引。 */
    public void onIsolatedLoanOpened(long uid, int symbolId) {
        isolatedLoanSymbolToUsers.getIfAbsentPut(symbolId, LongHashSet::new).add(uid);
    }

    /** isolated loan 清空：uid 在该 symbolId 上已无其它非空 loan 时才摘除（一 uid 可持多笔同 symbol）。 */
    public void onIsolatedLoanClosed(UserProfile up, int symbolId) {
        final boolean holdsOther = up.isolatedLoans.anySatisfy(l -> !l.isEmpty() && l.symbolId == symbolId);
        if (holdsOther) {
            return;
        }
        final MutableLongSet s = isolatedLoanSymbolToUsers.get(symbolId);
        if (s != null) {
            s.remove(up.uid);
            if (s.isEmpty()) {
                isolatedLoanSymbolToUsers.remove(symbolId);
            }
        }
    }

    /** cross 敞口变更后 reconcile 索引：登记当前敞口币种（抵押&gt;0 或有借款）；账户全退出则从各币种桶摘除。 */
    public void syncCrossExposure(UserProfile up) {
        up.crossLoanCollateral.forEachKeyValue((currency, amount) -> {
            if (amount > 0) {
                crossLoanCurrencyToUsers.getIfAbsentPut(currency, LongHashSet::new).add(up.uid);
            }
        });
        up.crossLoans.forEachValue(loan -> {
            if (!loan.isEmpty()) {
                crossLoanCurrencyToUsers.getIfAbsentPut(loan.loanCurrency, LongHashSet::new).add(up.uid);
            }
        });
        // 部分币种退出容忍 stale（无害 over-trigger，下次 rebuild 清）；仅账户全退出才精确摘除
        final boolean hasLoan = up.crossLoans.anySatisfy(l -> !l.isEmpty());
        final boolean hasCollateral = up.crossLoanCollateral.anySatisfy(a -> a > 0);
        if (!hasLoan && !hasCollateral) {
            for (int currency : crossLoanCurrencyToUsers.keySet().toArray()) {
                final MutableLongSet s = crossLoanCurrencyToUsers.get(currency);
                if (s != null) {
                    s.remove(up.uid);
                    if (s.isEmpty()) {
                        crossLoanCurrencyToUsers.remove(currency);
                    }
                }
            }
        }
    }

    // ====================================================================================
    // Cross 选币选债：两个 pick 都内嵌"存在就绪现货对"过滤，见 checkCross
    // ====================================================================================

    /** 选卖出抵押币（见 {@link #checkCross} 说明）：权重 DESC → 数量 DESC → 币种 ASC，且须能偿到某笔债；无合格者返回 0。 */
    private int pickCrossCollateralToSell(UserProfile up) {
        int bestCurrency = 0;
        int bestWeight = -1;
        long bestAmount = -1;
        for (int currency : up.crossLoanCollateral.keySet().toArray()) {
            final long amount = up.crossLoanCollateral.get(currency);
            if (amount <= 0)
                continue;
            final int weight = LoanService.collateralWeightForBase(currency, currencySpecificationProvider);
            if (weight <= 0)
                continue;
            if (up.crossLoans
                .noneSatisfy(l -> l.outstandingPrincipal > 0 && hasReadySpotMarket(currency, l.loanCurrency)))
                continue; // 卖此币偿不了任何债（无现货对/markPrice 未就绪）→ 跳过，避免选中后每轮空转
            if (weight > bestWeight || (weight == bestWeight && amount > bestAmount)
                || (weight == bestWeight && amount == bestAmount && currency < bestCurrency)) {
                bestCurrency = currency;
                bestWeight = weight;
                bestAmount = amount;
            }
        }
        return bestCurrency;
    }

    /** 选偿还目标 loan（见 {@link #checkCross} 说明）：利率 DESC → 本金 DESC → loanId ASC，且须与 sellingCurrency 有就绪现货对；无则 null。 */
    private CrossLoanRecord pickCrossLoanToRepay(UserProfile up, int sellingCurrency) {
        CrossLoanRecord best = null;
        for (CrossLoanRecord loan : up.crossLoans) {
            if (loan.outstandingPrincipal <= 0)
                continue;
            if (!hasReadySpotMarket(sellingCurrency, loan.loanCurrency))
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

    /** 卖 sellingCurrency 偿 loanCurrency 的现货对存在且 markPrice 就绪（可真正成交的前提）。 */
    private boolean hasReadySpotMarket(int sellingCurrency, int loanCurrency) {
        final CoreSymbolSpecification spec =
            symbolSpecificationProvider.findSpotSymbol(sellingCurrency, loanCurrency);
        if (spec == null)
            return false;
        final LastPriceCacheRecord priceRecord = lastPriceCache.get(spec.symbolId);
        return priceRecord != null && priceRecord.markPrice > 0;
    }

    /**
     * 下单张数 = min(可卖抵押, 覆盖真实债务所需)（均换算成 lot）。 用 {@code limitPrice}（破产价）而非 markPrice 折算所需张数——按市价定量却按折价卖，必然收不回债务。
     */
    private long calculateCrossSellSize(CrossLoanRecord targetLoan, CoreSymbolSpecification spec, long limitPrice,
        long available, long now, LoanService loanService, CoreCurrencySpecification sellingCurrencySpec,
        CoreCurrencySpecification loanCurrencySpec) {
        long realDebt =
            Math.addExact(targetLoan.outstandingPrincipal, loanService.calculateDisplayInterest(targetLoan, now));
        if (realDebt <= 0 || limitPrice <= 0)
            return 0;
        long neededLots = LoanService.quoteAmountToLots(realDebt, limitPrice, spec, loanCurrencySpec);
        long availableLots = LoanService.collateralAmountToLots(available, spec, sellingCurrencySpec);
        return Math.min(availableLots, neededLots);
    }

    /** 越预警线通知：走 leader-local ringbuffer、bypass raft 的 best-effort 事件；去重/限流由下游消费方负责。 */
    private void sendMarginCall(long uid, long loanId, byte mode, int loanCurrency, long ltvBps, long thresholdBps) {
        FundEvent event = eventsHelper.sendLoanMarginCallEvent(uid, loanId, mode, loanCurrency, ltvBps, thresholdBps);
        commandSubmitter.get().submit(ApiSystemLiquidationNotify.builder().fundEvent(event).build(), null);
    }

    // ====================================================================================
    // 破产价：卖出所得刚好覆盖债务的地板价（loan.md §18.3）
    // ====================================================================================

    /**
     * 破产价：卖出所得刚好覆盖债务的价格，即 {@code markPrice × 债务 / 抵押估值}（两者同为借款币精度，比值无量纲）。 等价于 {@code LTV × markPrice}——强平线 LTV
     * 即是它相对市价的折扣。
     *
     * <p>
     * 它是<b>地板价</b>而非目标价：IOC 撮合成交在对手价，实际成交通常高于此价，超出部分经 applyDebtPayment 封顶后 退还借款人。卖不到此价说明市场接不住，转由 LIF 接管。ceil
     * 取整，确保地板不低于真实盈亏平衡点。
     */
    private static long bankruptcyPrice(long markPrice, long realDebt, long collateralValue) {
        return CoreArithmeticUtils.ceilMulDiv(markPrice, realDebt, collateralValue);
    }
}
