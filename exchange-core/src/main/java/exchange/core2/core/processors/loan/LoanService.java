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

import exchange.core2.core.common.BoundedLongDedupSet;
import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.CrossLoanRecord;
import exchange.core2.core.common.IsolatedLoanRecord;
import exchange.core2.core.common.LoanRecord;
import exchange.core2.core.common.StateHash;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.RiskEngine.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.utils.CoreArithmeticUtils;
import exchange.core2.core.utils.SerializationUtils;
import lombok.Getter;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.Objects;

/**
 * 现货借贷状态 + 纯函数工具 —— per-shard 实例。承载进 raft snapshot 的账本（poolAvailable / poolBorrowed / badDebt / interestRevenue /
 * loanLiqFees 五桶 + 三阈值 + numeraire + 池子幂等表，Isolated / Cross 共用、跨 shard 独立）， 以及计息 / 抵债 / LTV / scale 换算 / force-sell
 * orderId 编码等纯函数。不持 RiskEngine ref，依赖经参数传入。 命令 apply 在 {@link LoanCommandHandlers}，scanner 在
 * {@link LoanLiquidationEngine}。
 */
public class LoanService implements WriteBytesMarshallable, StateHash {

    // ================================================================
    // 常量（默认阈值 + 利息公式 + OrderId 编码）
    // ================================================================

    public static final int DEFAULT_CROSS_LIQUIDATION_LTV_BPS = 8500; // 85%
    public static final int DEFAULT_CROSS_MARGIN_CALL_LTV_BPS = 8000; // 80%
    public static final int DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS = 9000; // 90%
    // 强平专项费率（bps）——v1 hardcode 200 = 2%；v2 走 spec.loanLiquidationFeeBps 可配。
    // 从 receivedQuote 抽出后进 loanLiqFees 桶（跟撮合 fees 分账）。
    public static final int LOAN_LIQUIDATION_FEE_BPS = 200;

    // Numeraire 未配置的 sentinel 值。
    public static final int NUMERAIRE_UNSET = 0;

    // 1 年 = 365 × 24 × 3600 × 1000 ms（跨节点唯一确定性形式）
    public static final long YEAR_MS = 365L * 24L * 3600L * 1_000L;
    // LTV / rate 的 bps 精度基准（10000 bps = 100%）
    public static final long BPS_SCALE = 10_000L;

    // force-sell OrderId 编码常量：顶字节 'L' 独占（避开期货 'I' / ADL 'A'）+ subtype + 打散 payload（不承担 loanId 反查）。
    public static final long ORDERID_NAMESPACE_TAG = 0x4CL; // 'L'
    public static final long ORDERID_SUBTYPE_ISOLATED = 0x53L; // 'S'
    public static final long ORDERID_SUBTYPE_CROSS = 0x43L; // 'C'
    public static final long ORDERID_PAYLOAD_MASK = 0xFFFFFFL; // 24 bit
    private static final long ORDERID_UIDHASH_MASK = 0xFFFFFL; // 20 bit
    private static final long ORDERID_TS_MASK = 0xFL; // 4 bit

    // ================================================================
    // State（进 raft snapshot，per-shard 独立）
    // 全局资金守恒：poolAvailable 正桶、badDebt 负桶；poolBorrowed 不进守恒，仅 utilization / metric。
    // ================================================================

    // LOAN_CREATE/BORROW 扣减，REPAY/force-sell/POOL_DEPOSIT 增加。
    @Getter
    private final IntLongHashMap loanPoolAvailable;

    // 不变量：Σ (isolated+cross) outstandingPrincipal(loanCurrency==currency) == loanPoolBorrowed[currency]。
    @Getter
    private final IntLongHashMap loanPoolBorrowed;

    // underwater force-sell 时交易所自吸的损失（审计条目，非二次扣真金）。
    @Getter
    private final IntLongHashMap badDebt;

    // 利息收入 / 强平专项费（loanCurrency scale），REPAY / force-sell 结算时入桶，跟撮合 fees 分账。
    @Getter
    private final IntLongHashMap interestRevenue;
    @Getter
    private final IntLongHashMap loanLiqFees;

    // Cross 账户级强平线 / 预警线 / 池子利用率上限（bps）。
    @Getter
    private int crossLiquidationLtvBps;
    @Getter
    private int crossMarginCallLtvBps;
    @Getter
    private int loanPoolUtilizationCapBps;

    // Cross 估值基准币；UPDATE_LOAN_GLOBAL_CONFIG 配置。NUMERAIRE_UNSET(0) 时 Cross BORROW/WITHDRAW fail-close、scanner 跳过。
    @Getter
    private int numeraireCurrency;

    // POOL_* 幂等表（per-shard，独立于 UserProfile.processedExternalIds）。
    @Getter
    private final BoundedLongDedupSet poolProcessedExternalIds;

    // ================================================================
    // 构造 / reset / 序列化 / stateHash
    // ================================================================

    public LoanService() {
        this.loanPoolAvailable = new IntLongHashMap();
        this.loanPoolBorrowed = new IntLongHashMap();
        this.badDebt = new IntLongHashMap();
        this.interestRevenue = new IntLongHashMap();
        this.loanLiqFees = new IntLongHashMap();
        this.crossLiquidationLtvBps = DEFAULT_CROSS_LIQUIDATION_LTV_BPS;
        this.crossMarginCallLtvBps = DEFAULT_CROSS_MARGIN_CALL_LTV_BPS;
        this.loanPoolUtilizationCapBps = DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS;
        this.numeraireCurrency = NUMERAIRE_UNSET;
        this.poolProcessedExternalIds = new BoundedLongDedupSet();
    }

    public LoanService(BytesIn bytes) {
        this.loanPoolAvailable = SerializationUtils.readIntLongHashMap(bytes);
        this.loanPoolBorrowed = SerializationUtils.readIntLongHashMap(bytes);
        this.badDebt = SerializationUtils.readIntLongHashMap(bytes);
        this.interestRevenue = SerializationUtils.readIntLongHashMap(bytes);
        this.loanLiqFees = SerializationUtils.readIntLongHashMap(bytes);
        this.crossLiquidationLtvBps = bytes.readInt();
        this.crossMarginCallLtvBps = bytes.readInt();
        this.loanPoolUtilizationCapBps = bytes.readInt();
        this.numeraireCurrency = bytes.readInt();
        this.poolProcessedExternalIds = new BoundedLongDedupSet(bytes);
    }

    public void reset() {
        loanPoolAvailable.clear();
        loanPoolBorrowed.clear();
        badDebt.clear();
        interestRevenue.clear();
        loanLiqFees.clear();
        crossLiquidationLtvBps = DEFAULT_CROSS_LIQUIDATION_LTV_BPS;
        crossMarginCallLtvBps = DEFAULT_CROSS_MARGIN_CALL_LTV_BPS;
        loanPoolUtilizationCapBps = DEFAULT_LOAN_POOL_UTILIZATION_CAP_BPS;
        numeraireCurrency = NUMERAIRE_UNSET;
    }

    public boolean isNumeraireConfigured() {
        return numeraireCurrency != NUMERAIRE_UNSET;
    }

    public void setNumeraireCurrency(int numeraireCurrency) {
        this.numeraireCurrency = numeraireCurrency;
    }

    public void setCrossLiquidationLtvBps(int crossLiquidationLtvBps) {
        this.crossLiquidationLtvBps = crossLiquidationLtvBps;
    }

    public void setCrossMarginCallLtvBps(int crossMarginCallLtvBps) {
        this.crossMarginCallLtvBps = crossMarginCallLtvBps;
    }

    public void setLoanPoolUtilizationCapBps(int loanPoolUtilizationCapBps) {
        this.loanPoolUtilizationCapBps = loanPoolUtilizationCapBps;
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        SerializationUtils.marshallIntLongHashMap(loanPoolAvailable, bytes);
        SerializationUtils.marshallIntLongHashMap(loanPoolBorrowed, bytes);
        SerializationUtils.marshallIntLongHashMap(badDebt, bytes);
        SerializationUtils.marshallIntLongHashMap(interestRevenue, bytes);
        SerializationUtils.marshallIntLongHashMap(loanLiqFees, bytes);
        bytes.writeInt(crossLiquidationLtvBps);
        bytes.writeInt(crossMarginCallLtvBps);
        bytes.writeInt(loanPoolUtilizationCapBps);
        bytes.writeInt(numeraireCurrency);
        poolProcessedExternalIds.writeMarshallable(bytes);
    }

    @Override
    public int stateHash() {
        // IntLongHashMap.hashCode() 本身 order/capacity independent，跟 UserProfile.stateHash 的 accounts/exchangeLocked 同款
        // pattern。
        return Objects.hash(loanPoolAvailable.hashCode(), loanPoolBorrowed.hashCode(), badDebt.hashCode(),
            interestRevenue.hashCode(), loanLiqFees.hashCode(), crossLiquidationLtvBps, crossMarginCallLtvBps,
            loanPoolUtilizationCapBps, numeraireCurrency, poolProcessedExternalIds.stateHash());
    }

    @Override
    public String toString() {
        return "LoanService{" + "poolAvail=" + loanPoolAvailable + ", poolBorrowed=" + loanPoolBorrowed + ", badDebt="
            + badDebt + ", intRev=" + interestRevenue + ", liqFees=" + loanLiqFees + ", crossLiqLtv="
            + crossLiquidationLtvBps + ", crossMcLtv=" + crossMarginCallLtvBps + ", poolCap="
            + loanPoolUtilizationCapBps + ", poolDedup=" + poolProcessedExternalIds.size() + '}';
    }

    // ================================================================
    // 惰性计息 + 抵债 —— Isolated / Cross 共用（LoanRecord 视图）
    // 计息公式：interest = elapsed_ms × principal × rateBps / (YEAR_MS × BPS_SCALE)，两次 truncMulDiv 防溢出；
    // now < lastAccrueTs（时钟倒退）视为 0 elapsed。
    // ================================================================

    /** 写路径：把利息补计到 now，累加进 accumulatedInterest 并推进 lastAccrueTs；返回本次新增利息（≥ 0）。 */
    public long accrueTo(LoanRecord loan, long now) {
        long delta = accrueDelta(loan.getOutstandingPrincipal(), loan.getRateBps(), loan.getLastAccrueTs(), now);
        if (delta > 0) {
            loan.setAccumulatedInterest(Math.addExact(loan.getAccumulatedInterest(), delta));
        }
        if (now > loan.getLastAccrueTs()) {
            loan.setLastAccrueTs(now);
        }
        return delta;
    }

    /** 读路径：accumulatedInterest + 到 now 的 pending 利息，不改 loan。 */
    public long calculateDisplayInterest(LoanRecord loan, long now) {
        long pending = accrueDelta(loan.getOutstandingPrincipal(), loan.getRateBps(), loan.getLastAccrueTs(), now);
        return Math.addExact(loan.getAccumulatedInterest(), pending);
    }

    /**
     * 用 fund（loanCurrency）按 <b>利息优先 → 本金</b> 抵债，抵扣上限为当前未偿本息（超出即 overpay，不动）。 从 account 扣实付、利息进 interestRevenue、本金回
     * loanPool；返回<b>本次结算的利息部分</b>（interestPart ≥ 0，供调用方发 LOAN_REPAY / LOAN_LIQUIDATED 事件）。REPAY 与强平结算共用此一处金钱逻辑。
     */
    public long applyDebtPayment(LoanRecord loan, IntLongHashMap account, long fund) {
        int currency = loan.getLoanCurrency();
        long interestPart = Math.min(fund, loan.getAccumulatedInterest());
        long principalPart = Math.min(fund - interestPart, loan.getOutstandingPrincipal());
        long paid = interestPart + principalPart;
        account.addToValue(currency, -paid);
        loan.setAccumulatedInterest(loan.getAccumulatedInterest() - interestPart);
        loan.setOutstandingPrincipal(loan.getOutstandingPrincipal() - principalPart);
        interestRevenue.addToValue(currency, interestPart);
        loanPoolAvailable.addToValue(currency, principalPart);
        loanPoolBorrowed.addToValue(currency, -principalPart);
        return interestPart;
    }

    /**
     * 强平所得 receivedQuote（loanCurrency，已扣撮合 takerFee）的统一去向：先抽 {@value #LOAN_LIQUIDATION_FEE_BPS} bps 强平费进 loanLiqFees，再
     * accrue + 抵债；剩余 overpay 留在 account。Isolated / Cross 强平共用。返回<b>本次结算的利息部分</b>（≥ 0，供调用方发 LOAN_REPAY / LOAN_LIQUIDATED 事件）。
     */
    public long settleLiquidationProceeds(LoanRecord loan, IntLongHashMap account, long receivedQuote, long now) {
        long liqFee =
            Math.min(receivedQuote, CoreArithmeticUtils.ceilMulDiv(receivedQuote, LOAN_LIQUIDATION_FEE_BPS, BPS_SCALE));
        account.addToValue(loan.getLoanCurrency(), -liqFee);
        loanLiqFees.addToValue(loan.getLoanCurrency(), liqFee);
        accrueTo(loan, now);
        return applyDebtPayment(loan, account, receivedQuote - liqFee);
    }

    /** 给定 principal / rate / lastAccrueTs / now 返回应新增利息（≥ 0）；lastAccrueTs > now 返回 0。 */
    private static long accrueDelta(long outstandingPrincipal, int rateBps, long lastAccrueTs, long now) {
        if (outstandingPrincipal <= 0 || rateBps <= 0) {
            return 0L;
        }
        long elapsed = now - lastAccrueTs;
        if (elapsed <= 0) {
            return 0L;
        }
        long step1 = CoreArithmeticUtils.truncMulDiv(elapsed, outstandingPrincipal, YEAR_MS);
        return CoreArithmeticUtils.truncMulDiv(step1, rateBps, BPS_SCALE);
    }

    // ================================================================
    // 池子 metric
    // ================================================================

    /**
     * 池子利用率（bps）= borrowed / (available + borrowed)。
     * <p>
     * currency 未见过（池子从未使用过该币种）时返回 0。分母为 0 时（理论不可能，防御）返回 0。
     */
    public int calculatePoolUtilizationBps(int currency) {
        long available = loanPoolAvailable.get(currency);
        long borrowed = loanPoolBorrowed.get(currency);
        long total = Math.addExact(available, borrowed);
        if (total <= 0) {
            return 0;
        }
        return (int)CoreArithmeticUtils.truncMulDiv(borrowed, BPS_SCALE, total);
    }

    /** v2 slot（动态利率曲线）：当前恒返 0，handler 直接用 {@code spec.loanRateBps} snapshot。 */
    public int calculateEffectiveRateBps(int loanCurrency) {
        return 0;
    }

    // ================================================================
    // Cross 账户级 LTV
    // ================================================================

    /**
     * 账户级 LTV（bps）—— scanner 决策 + Cross BORROW/WITHDRAW 校验共用，归一到 numeraire currency scale：
     * 
     * <pre>
     *   LTV = totalDebt × BPS_SCALE / weightedCollateral
     *   totalDebt          = Σ valueInNumeraire(loan.loanCurrency, principal + 含 pending 的利息)
     *   weightedCollateral = Σ valueInNumeraire(cur, crossLoanCollateral[cur]) × collateralWeightBps(cur) / BPS_SCALE
     * </pre>
     * 
     * 边界返回：无债务 / numeraire 未配 / 价格未就绪 → 0（保守 skip）；有债务但无抵押 → {@link Long#MAX_VALUE}； 溢出 debt 侧 → MAX_VALUE、collateral 侧
     * → 0。
     */
    public long calculateCrossAccountLtvBps(UserProfile userProfile, long now, SymbolSpecificationProvider specProvider,
        CurrencySpecificationProvider currencyProvider, IntObjectHashMap<LastPriceCacheRecord> priceCache,
        int numeraireCurrency) {
        if (userProfile.crossLoans.isEmpty()) {
            return 0L;
        }
        if (numeraireCurrency == 0) {
            return 0L;
        }
        CoreCurrencySpecification numeraireSpec = currencyProvider.getCurrencySpecification(numeraireCurrency);
        if (numeraireSpec == null) {
            return 0L;
        }

        // ===== 债务侧 =====
        long totalDebt = 0L;
        for (CrossLoanRecord loan : userProfile.crossLoans) {
            if (loan.outstandingPrincipal <= 0)
                continue;
            long realDebt;
            try {
                realDebt = Math.addExact(loan.outstandingPrincipal, calculateDisplayInterest(loan, now));
            } catch (ArithmeticException e) {
                return Long.MAX_VALUE;
            }
            long valueInNum = valueInNumeraire(loan.loanCurrency, realDebt, numeraireCurrency, numeraireSpec,
                specProvider, currencyProvider, priceCache);
            if (valueInNum < 0)
                return 0L; // 缺 markPrice / spec / currencySpec，保守 skip
            try {
                totalDebt = Math.addExact(totalDebt, valueInNum);
            } catch (ArithmeticException e) {
                return Long.MAX_VALUE;
            }
        }

        // ===== 抵押侧 =====
        long weightedCollateral = 0L;
        int[] currencies = userProfile.crossLoanCollateral.keySet().toArray();
        for (int currency : currencies) {
            long amount = userProfile.crossLoanCollateral.get(currency);
            if (amount <= 0)
                continue;
            int weight = collateralWeightForBase(currency, specProvider);
            if (weight <= 0)
                continue;
            long valueInNum = valueInNumeraire(currency, amount, numeraireCurrency, numeraireSpec, specProvider,
                currencyProvider, priceCache);
            if (valueInNum < 0)
                return 0L;
            try {
                long weighted = CoreArithmeticUtils.truncMulDiv(valueInNum, weight, BPS_SCALE);
                weightedCollateral = Math.addExact(weightedCollateral, weighted);
            } catch (ArithmeticException e) {
                return 0L;
            }
        }

        if (weightedCollateral <= 0) {
            return Long.MAX_VALUE;
        }
        return CoreArithmeticUtils.truncMulDiv(totalDebt, BPS_SCALE, weightedCollateral);
    }

    /**
     * currency 的 amount 换算成 numeraire currencyScale：currency==numeraire 直接返回；否则经 base=currency/quote=numeraire 现货对的
     * markPrice 折算。找不到 spec / markPrice / currencySpec → -1（caller 视为价格未就绪 skip）。
     */
    private static long valueInNumeraire(int currency, long amount, int numeraireCurrency,
        CoreCurrencySpecification numeraireSpec, SymbolSpecificationProvider specProvider,
        CurrencySpecificationProvider currencyProvider, IntObjectHashMap<LastPriceCacheRecord> priceCache) {
        if (currency == numeraireCurrency) {
            return amount;
        }
        CoreSymbolSpecification spec = findSpotSpec(currency, numeraireCurrency, specProvider);
        if (spec == null)
            return -1L;
        LastPriceCacheRecord rec = priceCache.get(spec.symbolId);
        if (rec == null || rec.markPrice <= 0)
            return -1L;
        CoreCurrencySpecification currencySpec = currencyProvider.getCurrencySpecification(currency);
        if (currencySpec == null)
            return -1L;
        // currency 的 currencyScale → base scale → ×markPrice → numeraire currencyScale
        long baseAmount = CoreArithmeticUtils.currencyToSymbolScale(amount, spec, currencySpec);
        long notional = Math.multiplyExact(baseAmount, rec.markPrice);
        return CoreArithmeticUtils.sizePriceToCurrencyScale(notional, spec, numeraireSpec);
    }

    /**
     * base amount（base currencyScale）经 markPrice 折算成 quote 等值量（quote currencyScale）；Isolated LTV / scanner 用。 三步：base
     * currencyScale → baseScale → ×markPrice(sizePriceScale) → quote currencyScale。缺 currencySpec 返回 -1。
     */
    public static long collateralValueInQuoteCurrency(long amount, CoreSymbolSpecification spec, long markPrice,
        CoreCurrencySpecification baseCurrencySpec, CoreCurrencySpecification quoteCurrencySpec) {
        if (baseCurrencySpec == null || quoteCurrencySpec == null)
            return -1L;
        long baseAmount = CoreArithmeticUtils.currencyToSymbolScale(amount, spec, baseCurrencySpec);
        long notional = Math.multiplyExact(baseAmount, markPrice);
        return CoreArithmeticUtils.sizePriceToCurrencyScale(notional, spec, quoteCurrencySpec);
    }

    // ================================================================
    // Force-sell scale 换算 —— 抵押记账（currencyScale）↔ 撮合订单张数（lot）
    // force-sell 是抵押唯一跨出账户域进撮合域的路径，故只在这里换尺。
    // ================================================================

    /** 抵押金额（base currencyScale）→ 强平下单张数（lot）；不足一张截断为 0。 */
    public static long collateralAmountToLots(long amount, CoreSymbolSpecification spec,
        CoreCurrencySpecification baseSpec) {
        return CoreArithmeticUtils.currencyToSymbolScale(amount, spec, baseSpec);
    }

    /** 强平张数（lot）→ 抵押金额（base currencyScale）；{@link #collateralAmountToLots} 的反向，pre-move 记账用。 */
    public static long lotsToCollateralAmount(long lots, CoreSymbolSpecification spec,
        CoreCurrencySpecification baseSpec) {
        return CoreArithmeticUtils.symbolToCurrencyScale(lots, spec, baseSpec);
    }

    /** 用 markPrice 把 quote 金额（loanCurrency currencyScale）反推成 base 张数（lot），向上取整（Cross 卖出量估算用）。 */
    public static long quoteAmountToLots(long quoteAmount, long markPrice, CoreSymbolSpecification spec,
        CoreCurrencySpecification quoteSpec) {
        long notional = CoreArithmeticUtils.currencyToSizePriceScale(quoteAmount, spec, quoteSpec);
        return CoreArithmeticUtils.ceilDivide(notional, markPrice);
    }

    /**
     * currency 作抵押时是否还有≥1 张可卖（任一 base=currency 的现货 pair）。sub-lot 尘埃 → false。 Cross underwater 判定用：所有抵押币种都无可卖整张，才认定账户级
     * underwater。
     */
    public static boolean hasSellableCollateralLot(int currency, long amount, SymbolSpecificationProvider specProvider,
        CurrencySpecificationProvider currencyProvider) {
        if (amount <= 0)
            return false;
        CoreCurrencySpecification currencySpec = currencyProvider.getCurrencySpecification(currency);
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

    /** 找 base=baseCurrency, quote=quoteCurrency 的现货 pair spec；O(N) 遍历。v2 加 (base,quote)→symbolId 反查索引。 */
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

    /**
     * 返回 currency 作 Cross 抵押时的折价率（bps）；未开放作抵押返回 0。 规则：找第一条 base=currency 且 collateralWeightBps &gt; 0 的现货 pair spec 的
     * weight。
     */
    public static int collateralWeightForBase(int currency, SymbolSpecificationProvider provider) {
        for (CoreSymbolSpecification spec : provider.getSymbolSpecs()) {
            if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR && spec.baseCurrency == currency
                && spec.collateralWeightBps > 0) {
                return spec.collateralWeightBps;
            }
        }
        return 0;
    }

    // ================================================================
    // Force-sell OrderId 编码，布局：
    // | 63..56 'L' | 55..48 subtype | 47..24 payload | 23..4 uidHash | 3..0 ts |
    // ================================================================

    public static long generateIsolatedForceSellOrderId(IsolatedLoanRecord loan) {
        long payload = loan.loanId & ORDERID_PAYLOAD_MASK;
        long uidHash = (loan.uid * 31 + 17) & ORDERID_UIDHASH_MASK;
        long ts = (System.currentTimeMillis() / 1000) & ORDERID_TS_MASK;
        return (ORDERID_NAMESPACE_TAG << 56) | (ORDERID_SUBTYPE_ISOLATED << 48) | (payload << 24) | (uidHash << 4) | ts;
    }

    public static long generateCrossForceSellOrderId(long uid, int sellingCurrency) {
        long payload = ((long)sellingCurrency) & ORDERID_PAYLOAD_MASK;
        long uidHash = (uid * 31 + 17) & ORDERID_UIDHASH_MASK;
        long ts = (System.currentTimeMillis() / 1000) & ORDERID_TS_MASK;
        return (ORDERID_NAMESPACE_TAG << 56) | (ORDERID_SUBTYPE_CROSS << 48) | (payload << 24) | (uidHash << 4) | ts;
    }

    /** 顶字节 == 'L' 即 loan force-sell orderId（对 Isolated / Cross 都成立）。 */
    public static boolean isLoanForceSellOrderId(long orderId) {
        return ((orderId >>> 56) & 0xFFL) == ORDERID_NAMESPACE_TAG;
    }

    /** subtype 字节：{@code 'S'}=Isolated / {@code 'C'}=Cross。调用前先 {@link #isLoanForceSellOrderId}。 */
    public static byte loanForceSellSubtype(long orderId) {
        return (byte)((orderId >>> 48) & 0xFFL);
    }
}
