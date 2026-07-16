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

import java.util.Objects;

import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import exchange.core2.core.common.BoundedLongDedupSet;
import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.CrossLoanRecord;
import exchange.core2.core.common.LoanRecord;
import exchange.core2.core.common.StateHash;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.loan.rate.FixedRateModel;
import exchange.core2.core.processors.loan.rate.FloatingRateModel;
import exchange.core2.core.utils.CoreArithmeticUtils;
import exchange.core2.core.utils.SerializationUtils;
import lombok.Getter;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

/**
 * 现货借贷账本 + 纯函数工具集，per-shard 单例，随 raft snapshot 落盘。承载 Isolated / Cross 共用的五个资金桶
 * （可借余额 / 已借出本金 / 坏账 / 利息收入 / 强平费）、全局运行时配置（Cross 阈值、numeraire、pool 利用率上限）、
 * 幂等表，以及两套利率子系统（Fixed 定息 / Floating 活期）。命令 apply 见 {@link LoanCommandHandlers}，强平扫描见
 * {@link LoanLiquidationEngine}。
 *
 * <p><b>计息与抵债</b>：按 {@code loan.isFixedRate()} 分派到 {@link FixedRateModel}（Isolated LOCKED）或
 * {@link FloatingRateModel}（Isolated FLOATING + 全部 Cross）；写路径 {@link #accrueTo} 推进利率游标，读路径
 * {@link #calculateDisplayInterest} 只读不改状态。抵债统一走 {@link #applyDebtPayment}（利息优先、本金其次），
 * REPAY 与强平结算共用同一处金钱逻辑。
 *
 * <p><b>估值与 LTV</b>：Isolated 单笔按 symbol markPrice 折算（{@link #collateralValueInQuoteCurrency}）；Cross
 * 是账户级、按 numeraire 归一（{@link #calculateCrossAccountLtvBps}）。numeraire 未配置或喂价缺失时，scanner /
 * 展示场景 fail-open（返回 0，避免误强平），BORROW / WITHDRAW 前置校验 fail-closed（返回 {@link Long#MAX_VALUE}，
 * 避免"价格未就绪"被误读成绝对安全而超借或抽空抵押）。
 *
 * <p><b>约束</b>：所有金额都在各自 currency 的 currencyScale 下记账，与撮合用的 symbol lot（base/quoteScaleK）是
 * 两套不同精度，换算经 {@link CoreArithmeticUtils} 显式完成；跨节点运算只用整数与确定性舍入（ceil 向交易所有利、
 * trunc 向零截断），不引入浮点。
 */
@Getter
public class LoanService implements WriteBytesMarshallable, StateHash {

    public static final long YEAR_MS = 365L * 24L * 3600L * 1_000L; // 1 年（ms），跨节点唯一确定性形式，不依赖日历/闰年
    public static final long BPS_SCALE = 10_000L; // bps 精度基准（10000 = 100%）

    public static final long ORDERID_NAMESPACE_TAG = 0x4CL; // 'L'：force-sell orderId 顶字节，独占命名空间，避开期货 'I' / ADL 'A'
    public static final long ORDERID_SUBTYPE_ISOLATED = 0x53L; // 'S'
    public static final long ORDERID_SUBTYPE_CROSS = 0x43L; // 'C'
    public static final long ORDERID_UID_MASK = 0xF_FFFFL; // 20 bit uid hash
    public static final long ORDERID_LOANID_MASK = 0xFFFFL; // 16 bit loanId hash
    public static final long ORDERID_TS_MASK = 0xFFFL; // 12 bit 秒（4096s≈68min 后回绕）

    private final IntLongHashMap loanPoolAvailable; // 各币种可借余额（currency scale）
    private final IntLongHashMap loanPoolBorrowed; // 各币种已借出本金（currency scale）
    private final IntLongHashMap badDebt; // underwater 核销的坏账损失（currency scale）
    private final IntLongHashMap interestRevenue; // 利息收入（currency scale）
    private final IntLongHashMap loanLiquidationFees; // 强平专项费收入（currency scale）
    private final LoanGlobalConfig globalConfig; // 全局运行时配置（Cross 阈值 / pool 利用率上限 / numeraire）
    private final BoundedLongDedupSet poolProcessedExternalIds; // 池子运营命令（充值/调整上限等）去重
    private final FloatingRateModel floatingRate; // 活期利率：Isolated FLOATING + 全部 Cross
    private final FixedRateModel fixedRate; // 定期利率：Isolated LOCKED，开仓时锚定 floatingRate 当前利率

    public LoanService() {
        this.loanPoolAvailable = new IntLongHashMap();
        this.loanPoolBorrowed = new IntLongHashMap();
        this.badDebt = new IntLongHashMap();
        this.interestRevenue = new IntLongHashMap();
        this.loanLiquidationFees = new IntLongHashMap();
        this.globalConfig = new LoanGlobalConfig();
        this.poolProcessedExternalIds = new BoundedLongDedupSet();
        this.floatingRate = new FloatingRateModel();
        this.fixedRate = new FixedRateModel(floatingRate);
    }

    public LoanService(BytesIn bytes) {
        this.loanPoolAvailable = SerializationUtils.readIntLongHashMap(bytes);
        this.loanPoolBorrowed = SerializationUtils.readIntLongHashMap(bytes);
        this.badDebt = SerializationUtils.readIntLongHashMap(bytes);
        this.interestRevenue = SerializationUtils.readIntLongHashMap(bytes);
        this.loanLiquidationFees = SerializationUtils.readIntLongHashMap(bytes);
        this.globalConfig = new LoanGlobalConfig(bytes);
        this.poolProcessedExternalIds = new BoundedLongDedupSet(bytes);
        this.floatingRate = new FloatingRateModel(bytes);
        this.fixedRate = new FixedRateModel(floatingRate, bytes);
    }

    public void reset() {
        loanPoolAvailable.clear();
        loanPoolBorrowed.clear();
        badDebt.clear();
        interestRevenue.clear();
        loanLiquidationFees.clear();
        globalConfig.reset();
        floatingRate.reset();
        fixedRate.reset();
    }

    public boolean isNumeraireConfigured() {
        return globalConfig.isNumeraireConfigured();
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        SerializationUtils.marshallIntLongHashMap(loanPoolAvailable, bytes);
        SerializationUtils.marshallIntLongHashMap(loanPoolBorrowed, bytes);
        SerializationUtils.marshallIntLongHashMap(badDebt, bytes);
        SerializationUtils.marshallIntLongHashMap(interestRevenue, bytes);
        SerializationUtils.marshallIntLongHashMap(loanLiquidationFees, bytes);
        globalConfig.writeMarshallable(bytes);
        poolProcessedExternalIds.writeMarshallable(bytes);
        floatingRate.writeMarshallable(bytes);
        fixedRate.writeMarshallable(bytes);
    }

    @Override
    public int stateHash() {
        // IntLongHashMap.hashCode() 与顺序/容量无关，可跨节点确定性比较
        return Objects.hash(loanPoolAvailable.hashCode(), loanPoolBorrowed.hashCode(), badDebt.hashCode(),
            interestRevenue.hashCode(), loanLiquidationFees.hashCode(), globalConfig.stateHash(),
            poolProcessedExternalIds.stateHash(), floatingRate.stateHash(), fixedRate.stateHash());
    }

    @Override
    public String toString() {
        return "LoanService{" + "poolAvail=" + loanPoolAvailable + ", poolBorrowed=" + loanPoolBorrowed + ", badDebt="
            + badDebt + ", intRev=" + interestRevenue + ", liqFees=" + loanLiquidationFees + ", " + globalConfig
            + ", poolDedup=" + poolProcessedExternalIds.size() + '}';
    }

    /** 写路径：按 {@code loan.isFixedRate()} 分派到对应利率模型，把截至 now 的利息补计进 accumulatedInterest 并推进游标（accSnapshot 或 lastAccrueTs）；返回本次新增利息（≥ 0）。 */
    public long accrueTo(LoanRecord loan, long now) {
        return loan.isFixedRate() ? fixedRate.accrue(loan, now) : floatingRate.accrue(loan, now);
    }

    /** 读路径：返回 accumulatedInterest 加上截至 now 的 pending 利息，不推进游标、不改 loan（展示、强平判定等只读场景用）。 */
    public long calculateDisplayInterest(LoanRecord loan, long now) {
        return loan.isFixedRate() ? fixedRate.displayInterest(loan, now) : floatingRate.displayInterest(loan, now);
    }

    /**
     * 用 fund 按利息优先、本金其次抵债，封顶为当前未偿本息之和；本金部分回补 loanPoolAvailable / 冲减 loanPoolBorrowed，
     * 利息部分计入 interestRevenue。返回本次抵扣的利息部分（≥ 0，供调用方发 LOAN_REPAY / LOAN_LIQUIDATED 事件）。
     * REPAY 与强平结算共用此一处金钱逻辑。
     */
    public long applyDebtPayment(LoanRecord loan, IntLongHashMap account, long fund) {
        final int currency = loan.getLoanCurrency();
        final long interestPart = Math.min(fund, loan.getAccumulatedInterest());
        final long fundAfterInterest = fund - interestPart;
        final long principalPart = Math.min(fundAfterInterest, loan.getOutstandingPrincipal());
        final long paid = interestPart + principalPart;
        account.addToValue(currency, -paid);
        loan.setAccumulatedInterest(loan.getAccumulatedInterest() - interestPart);
        loan.setOutstandingPrincipal(loan.getOutstandingPrincipal() - principalPart);
        interestRevenue.addToValue(currency, interestPart);
        loanPoolAvailable.addToValue(currency, principalPart);
        loanPoolBorrowed.addToValue(currency, -principalPart);
        return interestPart;
    }

    /**
     * 强平所得 receivedQuote（已扣撮合 takerFee）的统一去向：先按 loanLiquidationFeeBps 抽强平费进 loanLiquidationFees
     * （ceil 向交易所取整，不少收），再 accrue 补计利息后抵债，剩余 overpay 留在 account。返回本次结算的利息部分（≥ 0）。
     * Isolated / Cross 强平共用。
     */
    public long settleLiquidationProceeds(LoanRecord loan, IntLongHashMap account, long receivedQuote, long now) {
        final long feeByRate = CoreArithmeticUtils.ceilMulDiv(receivedQuote, globalConfig.loanLiquidationFeeBps, BPS_SCALE);
        final long liqFee = Math.min(receivedQuote, feeByRate);
        account.addToValue(loan.getLoanCurrency(), -liqFee);
        loanLiquidationFees.addToValue(loan.getLoanCurrency(), liqFee);
        accrueTo(loan, now);
        return applyDebtPayment(loan, account, receivedQuote - liqFee);
    }

    /**
     * 账户级 LTV（bps）= totalDebt × BPS_SCALE / weightedCollateral，归一到 numeraire 的 currencyScale。
     * scanner 决策与 Cross BORROW/WITHDRAW 校验共用；喂价/spec 缺失时的取舍见 6 参重载。
     */
    public long calculateCrossAccountLtvBps(UserProfile userProfile, long now, SymbolSpecificationProvider specProvider,
        CurrencySpecificationProvider currencyProvider, IntObjectHashMap<LastPriceCacheRecord> priceCache,
        int numeraireCurrency) {
        return calculateCrossAccountLtvBps(userProfile, now, specProvider, currencyProvider, priceCache,
            numeraireCurrency, false);
    }

    /**
     * {@code failClosedOnMissingPrice}：缺 markPrice / spec / currencySpec 时的取向。scanner / 展示走
     * {@code false}（返 0，保守 skip，不误强平）；BORROW / WITHDRAW 前置 guard 走 {@code true}（返
     * {@link Long#MAX_VALUE}，拒绝而非放行）——否则"价格未就绪"会被读成 LTV=0=绝对安全，可绕过 LTV 限制超借 /
     * 撤走全部抵押，事后只能落坏账(F2)。
     */
    public long calculateCrossAccountLtvBps(UserProfile userProfile, long now, SymbolSpecificationProvider specProvider,
        CurrencySpecificationProvider currencyProvider, IntObjectHashMap<LastPriceCacheRecord> priceCache,
        int numeraireCurrency, boolean failClosedOnMissingPrice) {
        if (userProfile.crossLoans.isEmpty() || numeraireCurrency == 0) {
            return 0L;
        }
        final long unevaluable = failClosedOnMissingPrice ? Long.MAX_VALUE : 0L;
        final CoreCurrencySpecification numeraireSpec = currencyProvider.getCurrencySpecification(numeraireCurrency);
        if (numeraireSpec == null) {
            return unevaluable;
        }

        // 债务侧：逐笔折算成 numeraire 后求和
        long totalDebt = 0L;
        for (CrossLoanRecord loan : userProfile.crossLoans) {
            if (loan.outstandingPrincipal <= 0)
                continue;
            final long realDebt;
            try {
                realDebt = Math.addExact(loan.outstandingPrincipal, calculateDisplayInterest(loan, now));
            } catch (ArithmeticException e) {
                return Long.MAX_VALUE;
            }
            final long valueInNum = valueInNumeraire(loan.loanCurrency, realDebt, numeraireCurrency, numeraireSpec,
                specProvider, currencyProvider, priceCache);
            if (valueInNum < 0)
                return unevaluable; // 缺 markPrice / spec / currencySpec
            try {
                totalDebt = Math.addExact(totalDebt, valueInNum);
            } catch (ArithmeticException e) {
                return Long.MAX_VALUE; // 溢出视作无限大 LTV，倾向拒绝/强平而非放行
            }
        }

        // 抵押侧：按 collateralWeightBps 折价后求和
        long weightedCollateral = 0L;
        final int[] currencies = userProfile.crossLoanCollateral.keySet().toArray();
        for (int currency : currencies) {
            final long amount = userProfile.crossLoanCollateral.get(currency);
            if (amount <= 0)
                continue;
            final int weight = collateralWeightForBase(currency, specProvider);
            if (weight <= 0)
                continue;
            final long valueInNum = valueInNumeraire(currency, amount, numeraireCurrency, numeraireSpec, specProvider,
                currencyProvider, priceCache);
            if (valueInNum < 0)
                return unevaluable;
            try {
                final long weighted = CoreArithmeticUtils.truncMulDiv(valueInNum, weight, BPS_SCALE);
                weightedCollateral = Math.addExact(weightedCollateral, weighted);
            } catch (ArithmeticException e) {
                return unevaluable; // 溢出不放大抵押，保守按不可估值处理
            }
        }

        if (weightedCollateral <= 0) {
            return Long.MAX_VALUE;
        }
        return CoreArithmeticUtils.truncMulDiv(totalDebt, BPS_SCALE, weightedCollateral);
    }

    /**
     * amount 换算成 numeraire 的 currencyScale，经 base=currency/quote=numeraire 现货对的 markPrice 折算；
     * currency 与 numeraire 相同时直接返回 amount。缺 spec / markPrice / currencySpec → -1（价格未就绪，交由上层 skip）。
     */
    private static long valueInNumeraire(int currency, long amount, int numeraireCurrency,
        CoreCurrencySpecification numeraireSpec, SymbolSpecificationProvider specProvider,
        CurrencySpecificationProvider currencyProvider, IntObjectHashMap<LastPriceCacheRecord> priceCache) {
        if (currency == numeraireCurrency) {
            return amount;
        }
        final CoreSymbolSpecification spec = findSpotSpec(currency, numeraireCurrency, specProvider);
        if (spec == null)
            return -1L;
        final LastPriceCacheRecord priceRecord = priceCache.get(spec.symbolId);
        if (priceRecord == null || priceRecord.markPrice <= 0)
            return -1L;
        final CoreCurrencySpecification currencySpec = currencyProvider.getCurrencySpecification(currency);
        // currency 视作 base、numeraire 视作 quote，复用同一套折算
        return collateralValueInQuoteCurrency(amount, spec, priceRecord.markPrice, currencySpec, numeraireSpec);
    }

    /** base amount（base currencyScale）经 markPrice 折算成 quote 等值量（quote currencyScale）；Isolated LTV 判定与 scanner 估值共用。缺 currencySpec（base 或 quote）返回 -1。 */
    public static long collateralValueInQuoteCurrency(long amount, CoreSymbolSpecification spec, long markPrice,
        CoreCurrencySpecification baseCurrencySpec, CoreCurrencySpecification quoteCurrencySpec) {
        if (baseCurrencySpec == null || quoteCurrencySpec == null)
            return -1L;
        final long baseAmount = CoreArithmeticUtils.currencyToSymbolScale(amount, spec, baseCurrencySpec);
        final long notional = Math.multiplyExact(baseAmount, markPrice);
        return CoreArithmeticUtils.sizePriceToCurrencyScale(notional, spec, quoteCurrencySpec);
    }

    /** 抵押金额（base currencyScale）→ 强平下单张数（lot，即 base symbolScale）；不足一张截断为 0。 */
    public static long collateralAmountToLots(long amount, CoreSymbolSpecification spec,
        CoreCurrencySpecification baseSpec) {
        return CoreArithmeticUtils.currencyToSymbolScale(amount, spec, baseSpec);
    }

    /** 强平张数（lot）→ 抵押金额（base currencyScale）；{@link #collateralAmountToLots} 的反向，pre-move 记账用。 */
    public static long lotsToCollateralAmount(long lots, CoreSymbolSpecification spec,
        CoreCurrencySpecification baseSpec) {
        return CoreArithmeticUtils.symbolToCurrencyScale(lots, spec, baseSpec);
    }

    /** 用 markPrice 把 quote 金额（loanCurrency currencyScale）反推成 base 张数（lot），ceil 向上取整以确保覆盖债务（Cross 卖出量估算用）。 */
    public static long quoteAmountToLots(long quoteAmount, long markPrice, CoreSymbolSpecification spec,
        CoreCurrencySpecification quoteSpec) {
        final long notional = CoreArithmeticUtils.currencyToSizePriceScale(quoteAmount, spec, quoteSpec);
        return CoreArithmeticUtils.ceilDivide(notional, markPrice);
    }

    /** currency 作抵押是否还有 ≥1 张可卖（任一 base=currency 现货 pair）；sub-lot 尘埃视作不可卖。Cross underwater 判定用。 */
    public static boolean hasSellableCollateralLot(int currency, long amount, SymbolSpecificationProvider specProvider,
        CurrencySpecificationProvider currencyProvider) {
        if (amount <= 0)
            return false;
        final CoreCurrencySpecification currencySpec = currencyProvider.getCurrencySpecification(currency);
        if (currencySpec == null)
            return false;
        for (CoreSymbolSpecification spec : specProvider.getSymbolSpecs()) {
            if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR && spec.baseCurrency == currency
                && collateralAmountToLots(amount, spec, currencySpec) > 0) {
                return true;
            }
        }
        return false;
    }

    /** 找 base=baseCurrency, quote=quoteCurrency 的现货 pair spec；O(N) 遍历全部 symbol（币种级动态查询，非 loan 自身的 symbol）。 */
    public static CoreSymbolSpecification findSpotSpec(int baseCurrency, int quoteCurrency,
        SymbolSpecificationProvider provider) {
        for (CoreSymbolSpecification spec : provider.getSymbolSpecs()) {
            if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR && spec.baseCurrency == baseCurrency
                && spec.quoteCurrency == quoteCurrency) {
                return spec;
            }
        }
        return null;
    }

    /** 找 quote==loanCurrency 且已开放借贷的现货 pair spec，返回第一个匹配。O(N)，仅 Cross BORROW 选定标的 symbol 用一次。 */
    public static CoreSymbolSpecification findLoanSpecByQuoteCurrency(int loanCurrency,
        SymbolSpecificationProvider provider) {
        for (CoreSymbolSpecification spec : provider.getSymbolSpecs()) {
            if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR && spec.quoteCurrency == loanCurrency
                && spec.loanConfig.isEnabled()) {
                return spec;
            }
        }
        return null;
    }

    /** currency 作 Cross 抵押的折价率（bps）= 第一条 base=currency 且 collateralWeightBps&gt;0 的现货 pair 的 weight；未开放返回 0。O(N)。 */
    public static int collateralWeightForBase(int currency, SymbolSpecificationProvider provider) {
        for (CoreSymbolSpecification spec : provider.getSymbolSpecs()) {
            if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR && spec.baseCurrency == currency
                && spec.loanConfig.collateralWeightBps > 0) {
                return spec.loanConfig.collateralWeightBps;
            }
        }
        return 0;
    }

    /**
     * 编码强平卖单 orderId，与普通订单、期货强平、ADL 的 orderId 空间隔离。位布局：
     * {@code | 63..56 'L' | 55..48 subtype | 47..28 uidHash(20) | 27..12 loanIdHash(16) | 11..0 秒(12) |}。
     * uid/loanId 只取 hash 低位、秒仅 12 bit 会回绕，不保证全局唯一，仅用于强平卖单的可辨识。
     */
    public static long forceSellOrderId(long subtype, long uid, long loanId, long tickTimeMs) {
        final long uidHash = (uid * 31 + 17) & ORDERID_UID_MASK;
        final long loanIdHash = (loanId * 31 + 17) & ORDERID_LOANID_MASK;
        final long tsSec = (tickTimeMs / 1000) & ORDERID_TS_MASK;
        return (ORDERID_NAMESPACE_TAG << 56) | (subtype << 48) | (uidHash << 28) | (loanIdHash << 12) | tsSec;
    }

    /** 顶字节 == 'L' 即 loan force-sell orderId（对 Isolated / Cross 都成立）。 */
    public static boolean isLoanForceSellOrderId(long orderId) {
        return ((orderId >>> 56) & 0xFFL) == ORDERID_NAMESPACE_TAG;
    }

    /** subtype 字节：{@code 'S'}=Isolated / {@code 'C'}=Cross。调用前先判定 {@link #isLoanForceSellOrderId}。 */
    public static byte loanForceSellSubtype(long orderId) {
        return (byte)((orderId >>> 48) & 0xFFL);
    }
}
