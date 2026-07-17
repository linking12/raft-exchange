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
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.UserProfileService;
import exchange.core2.core.processors.liquidation.LiquidationCommandSubmitter;
import exchange.core2.core.processors.liquidation.LiquidationEngine;
import lombok.extern.slf4j.Slf4j;

/**
 * 现货借贷强平引擎（per-shard）。{@link LiquidationEngine} 构造时创建的稳定单例，作为其 loan 子域委托对象。
 *
 * <p>
 * <b>检测流程</b>：{@link LiquidationEngine#checkPositions} 过 leader gate 后委托 {@link #checkLoans} ——
 * 价格事件（{@code cmd.symbol >= 0}）经 loan 索引 targeted 选受影响的持有者；{@code LIQUIDATION_SCAN}（{@code cmd.symbol < 0}） 全量兜底。每个用户经
 * {@link #checkUser} 逐笔判定真实债务（含利息）LTV 与期限：越强平线发 force-sell IOC，越预警线发 margin call。
 *
 * <p>
 * <b>依赖</b>：全部注入，不回持 {@link LiquidationEngine}。provider 与 loanService 经 {@link #updateProvider} 注入、
 * 并在快照恢复时刷新；eventsHelper（稳定对象）与 submitter（{@link Supplier} 懒读——server 在 updateProvider 之后才 set）构造时注入。
 *
 * <p>
 * <b>targeted 索引</b>：由 {@link LoanCommandHandlers} 在 loan 命令 apply 时确定性维护（全节点、不 gate）、 {@link #updateProvider}
 * 从用户态重建；不进 raft snapshot。索引 stale 只损延迟不损正确性——backstop 全扫兜底。
 */
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
            // 受该 symbol 价格影响的持有者：isolated 按 symbolId、cross 按 base/quote 两币种，求并去重
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
        userProfileService.getUserProfiles().forEachValue(up -> checkUser(up, cmd.timestamp));
    }

    /** 逐笔检查一个用户的 isolated + cross 借贷。 */
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
        if (collateralValue < 0)
            return;

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
            final long limitPrice =
                limitPriceWithTolerance(priceRecord.markPrice, toleranceBpsFor(loan.stuckLiqAttempts));
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
     * Cross（全仓借贷）账户的强平判定。
     *
     * <p>
     * 全仓与逐仓（isolated）的记账方式完全不同：逐仓是一笔贷款配一份抵押、逐笔算 LTV；全仓则把用户所有抵押汇成一个
     * 账户级的池子（crossLoanCollateral，可含多个币种），债务是池子上并存的多笔独立借据（crossLoans），风险按整个账户
     * 的联合 LTV 判定，而不是看某一笔。全仓贷款恒为浮动利率、没有到期日，所以这里唯一的触发条件就是账户 LTV——它由
     * calculateCrossAccountLtvBps 用一个统一记账币种（numeraire）折算得出；若 numeraire 尚未配置则保守地返回 0，不触发。
     *
     * <p>
     * 当账户 LTV 越过强平线，处理方式不是一次性清空整个账户，而是渐进式去杠杆（deleveraging）：每一轮只卖出一个币种的
     * 一部分抵押、去偿还其中一笔债，把账户 LTV 往下压一点；后续的价格事件会再次进入本方法，如此反复直到账户回到安全线
     * 以内。因此每一轮真正的决策，就是"这一轮卖哪个抵押币、还哪一笔债"这一对组合的选取。
     *
     * <p>
     * 这个选取是全仓强平里最容易埋雷的地方。抵押池里有多个币种、账户上又挂着多笔债，而"卖某个币去偿某笔债"这个动作
     * 必须真的能在市场上成交——也就是必须存在一个已上市、且 markPrice 就绪的现货对（base 为卖出币、quote 为债务币）。
     * 一个很自然但错误的写法，是让抵押和目标债各自独立地挑"最优"：卖权重最高的抵押、偿利率最高的债；可一旦这两者凑成的
     * 那一对恰好没有对应现货对，本轮就只能放弃。而由于这套选取只看抵押权重和利率、跟价格无关，是完全确定性的，于是
     * 下一次价格事件进来会挑出一模一样的一对、再一次放弃——账户就此永久空转，既没被强平、坏账也永远结算不掉。
     *
     * <p>
     * 为此，我们把"该组合必须存在就绪现货对"这个条件，从选取之后的一道校验，前移成选取本身的过滤条件，下沉进下面两个
     * pick 方法里：一个抵押币只有在"卖它至少能偿到某一笔债"时才会进入候选，一笔债也只有在"与已选定的卖出币之间存在
     * 就绪现货对"时才会被选中。这样一来，被选出的首选组合一定是可成交的；如果最优的那对没有市场，选取会自动退到次优
     * 但可成交的组合；只有当账户里确实凑不出任何一对可成交的（抵押 × 债）时，才会真正放弃——那才是真正的坏账，交由后续
     * 的兜底扫描或坏账流程处理。
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
                LoanService.findSpotSpec(sellingCurrency, targetLoan.loanCurrency, symbolSpecificationProvider);
            final LastPriceCacheRecord priceRecord = lastPriceCache.get(spec.symbolId);
            final long availableCollateral = userProfile.crossLoanCollateral.get(sellingCurrency);
            final CoreCurrencySpecification sellingCurrencySpec =
                currencySpecificationProvider.getCurrencySpecification(sellingCurrency);
            final CoreCurrencySpecification loanCurrencySpec =
                currencySpecificationProvider.getCurrencySpecification(targetLoan.loanCurrency);
            // 下单张数 = min(可卖抵押, 覆盖债务+buffer 所需)；可卖抵押不足一张 lot（sub-lot 尘埃）时为 0，本轮跳过
            final long sellSize = calculateCrossSellSize(targetLoan, spec, priceRecord.markPrice, availableCollateral,
                ts, loanService, sellingCurrencySpec, loanCurrencySpec);
            if (sellSize <= 0) {
                log.warn("Cross force-sell abort: sellSize=0 (uid={} sellingCurrency={} available={})", userProfile.uid,
                    sellingCurrency, availableCollateral);
                return;
            }
            final long orderId =
                LoanService.forceSellOrderId(LoanService.ORDERID_SUBTYPE_CROSS, userProfile.uid, targetLoan.loanId, ts);
            final long limitPrice =
                limitPriceWithTolerance(priceRecord.markPrice, toleranceBpsFor(targetLoan.stuckLiqAttempts));
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

    /** 选卖出抵押币（见 {@link #checkCross} 说明）：权重 DESC → 数量 DESC → 币种 ASC，且须能偿到某笔债；无合格者返回 0。 */
    private int pickCrossCollateralToSell(UserProfile up) {
        int bestCurrency = 0;
        int bestWeight = -1;
        long bestAmount = -1;
        for (int currency : up.crossLoanCollateral.keySet().toArray()) {
            final long amount = up.crossLoanCollateral.get(currency);
            if (amount <= 0)
                continue;
            final int weight = LoanService.collateralWeightForBase(currency, symbolSpecificationProvider);
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
            LoanService.findSpotSpec(sellingCurrency, loanCurrency, symbolSpecificationProvider);
        if (spec == null)
            return false;
        final LastPriceCacheRecord priceRecord = lastPriceCache.get(spec.symbolId);
        return priceRecord != null && priceRecord.markPrice > 0;
    }

    /** 下单张数 = min(可卖抵押, 覆盖真实债务 + 5% buffer 所需)（均换算成 lot）。buffer 覆盖 fee + 限价容差冗余。 */
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

    /** 越预警线通知：走 leader-local ringbuffer、bypass raft 的 best-effort 事件；去重/限流由下游消费方负责。 */
    private void sendMarginCall(long uid, long loanId, byte mode, int loanCurrency, long ltvBps, long thresholdBps) {
        FundEvent event = eventsHelper.sendLoanMarginCallEvent(uid, loanId, mode, loanCurrency, ltvBps, thresholdBps);
        commandSubmitter.get().submit(ApiSystemLiquidationNotify.builder().fundEvent(event).build(), null);
    }

    /** 卡单容差爬梯：越卡限价越松以吃更深档位。&lt;3 次 1% / &lt;6 次 2% / 否则 5%（封顶）。 */
    private static long toleranceBpsFor(int stuckLiqAttempts) {
        if (stuckLiqAttempts >= 6)
            return 500L;
        if (stuckLiqAttempts >= 3)
            return 200L;
        return 100L;
    }

    /** 卖出限价 = markPrice × (1 − toleranceBps/10000)。 */
    private static long limitPriceWithTolerance(long markPrice, long toleranceBps) {
        return Math.multiplyExact(markPrice, 10000L - toleranceBps) / 10000L;
    }
}
