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
 * 现货借贷状态 + 纯函数工具 —— per-shard 实例。承载进 raft snapshot 的账本：poolAvailable / poolBorrowed / badDebt /
 * interestRevenue / loanLiquidationFees 五桶 + 运行时配置（cross LTV 阈值 / 利用率上限 / 强平费率 / numeraire）+ 池子幂等表
 * + 利率子系统（{@link FloatingRateModel} / {@link FixedRateModel}），Isolated / Cross 共用、跨 shard 独立。
 * 另有抵债 / LTV / scale 换算 / force-sell orderId 编码等纯函数；计息按 {@code loan.isFixedRate()} 分派到对应利率 model。
 * 不持 RiskEngine ref，依赖经参数传入。命令 apply 在 {@link LoanCommandHandlers}，scanner 在 {@link LoanLiquidationEngine}。
 */
public class LoanService implements WriteBytesMarshallable, StateHash {

    // ================================================================
    // 常量
    // ================================================================

    // --- 计息 / 精度 ---
    public static final long YEAR_MS = 365L * 24L * 3600L * 1_000L; // 1 年（ms），跨节点唯一确定性形式
    public static final long BPS_SCALE = 10_000L; // bps 精度基准（10000 = 100%）

    // --- force-sell orderId 编码常量（布局见 forceSellOrderId）---
    public static final long ORDERID_NAMESPACE_TAG = 0x4CL; // 'L'：loan 强平 orderId 命名空间，独占以避开期货 'I' / ADL 'A'
    public static final long ORDERID_SUBTYPE_ISOLATED = 0x53L; // 'S'：逐仓
    public static final long ORDERID_SUBTYPE_CROSS = 0x43L; // 'C'：全仓
    public static final long ORDERID_UID_MASK = 0xF_FFFFL; // 20 bit：uid 扰动 hash
    public static final long ORDERID_LOANID_MASK = 0xFFFFL; // 16 bit：loanId 扰动 hash
    public static final long ORDERID_TS_MASK = 0xFFFL; // 12 bit：秒（4096s≈68min 后回绕）

    // ================================================================
    // State（进 raft snapshot，per-shard 独立）
    // ================================================================

    // --- 资金桶（loanCurrency scale）。poolAvailable/interestRevenue/liqFees 进全局对账；borrowed/badDebt 仅追踪 ---
    // 可借余额：CREATE/BORROW −，REPAY/force-sell/POOL_DEPOSIT +
    @Getter
    private final IntLongHashMap loanPoolAvailable;
    // 已借出 = Σ(isolated+cross) outstandingPrincipal(loanCurrency==currency)；不进对账，仅 utilization/metric
    @Getter
    private final IntLongHashMap loanPoolBorrowed;
    // 坏账：underwater 核销的损失（审计负桶，非二次扣真金）
    @Getter
    private final IntLongHashMap badDebt;
    // 利息收入：REPAY / force-sell 结算时入桶（跟撮合 fees 分账）
    @Getter
    private final IntLongHashMap interestRevenue;
    // 强平专项费：结算时按 loanLiquidationFeeBps 抽取入桶
    @Getter
    private final IntLongHashMap loanLiquidationFees;

    // --- 全局运行时配置（UPDATE_LOAN_GLOBAL_CONFIG 可调）：Cross LTV 阈值 / 池利用率上限 / 强平费率 / numeraire ---
    @Getter
    private final LoanGlobalConfig globalConfig;

    // --- 幂等 ---
    // POOL_* 命令去重（per-shard，独立于 UserProfile.processedExternalIds）
    @Getter
    private final BoundedLongDedupSet poolProcessedExternalIds;

    // --- 动态利率子系统：Floating 是动态利率引擎（曲线+累加器），Fixed 开仓锁它当前利率 ---
    @Getter
    private final FloatingRateModel floatingRate; // Isolated FLOATING + 全部 Cross：曲线 + reprice + 累加器计息
    @Getter
    private final FixedRateModel fixedRate; // Isolated LOCKED：锁 floating 当前利率 + lockedAdjust，线性计息

    // ================================================================
    // 构造 / reset / 序列化 / stateHash
    // ================================================================

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
        // IntLongHashMap.hashCode() 本身 order/capacity independent，跟 UserProfile.stateHash 的 accounts/exchangeLocked 同款
        // pattern。
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

    // ================================================================
    // 惰性计息 + 抵债 —— Isolated / Cross 共用（LoanRecord 视图）
    // 计息公式：interest = elapsed_ms × principal × rateBps / (YEAR_MS × BPS_SCALE)，两次 truncMulDiv 防溢出；
    // now < lastAccrueTs（时钟倒退）视为 0 elapsed。
    // ================================================================

    /** 写路径：补计利息到 now，累加进 accumulatedInterest 并推进游标；返回本次新增利息（≥ 0）。 */
    public long accrueTo(LoanRecord loan, long now) {
        return loan.isFixedRate() ? fixedRate.accrue(loan, now) : floatingRate.accrue(loan, now);
    }

    /** 读路径：accumulatedInterest + 到 now 的 pending 利息，不改 loan。 */
    public long calculateDisplayInterest(LoanRecord loan, long now) {
        return loan.isFixedRate() ? fixedRate.displayInterest(loan, now) : floatingRate.displayInterest(loan, now);
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
     * 强平所得 receivedQuote（loanCurrency，已扣撮合 takerFee）的统一去向：先抽 {@code loanLiquidationFeeBps} 强平费进 loanLiquidationFees，再
     * accrue + 抵债；剩余 overpay 留在 account。Isolated / Cross 强平共用。返回<b>本次结算的利息部分</b>（≥ 0，供调用方发 LOAN_REPAY / LOAN_LIQUIDATED
     * 事件）。
     */
    public long settleLiquidationProceeds(LoanRecord loan, IntLongHashMap account, long receivedQuote, long now) {
        long liqFee = Math.min(receivedQuote,
            CoreArithmeticUtils.ceilMulDiv(receivedQuote, globalConfig.loanLiquidationFeeBps, BPS_SCALE));
        account.addToValue(loan.getLoanCurrency(), -liqFee);
        loanLiquidationFees.addToValue(loan.getLoanCurrency(), liqFee);
        accrueTo(loan, now);
        return applyDebtPayment(loan, account, receivedQuote - liqFee);
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

    /** 找 base=baseCurrency, quote=quoteCurrency 的现货 pair spec；O(N) 遍历（币种级动态查询，非 loan 自己的 symbol）。 */
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

    /** 找 quote==loanCurrency 且启用借贷的现货 pair spec；返回第一个匹配。O(N)，仅 Cross BORROW 用一次。 */
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

    /**
     * 返回 currency 作 Cross 抵押时的折价率（bps）；未开放作抵押返回 0。规则：找第一条 base=currency 且 collateralWeightBps &gt; 0 的现货 pair 的
     * weight。O(N)（Cross 抵押估值按币种查）。
     */
    public static int collateralWeightForBase(int currency, SymbolSpecificationProvider provider) {
        for (CoreSymbolSpecification spec : provider.getSymbolSpecs()) {
            if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR && spec.baseCurrency == currency
                && spec.loanConfig.collateralWeightBps > 0) {
                return spec.loanConfig.collateralWeightBps;
            }
        }
        return 0;
    }

    // ================================================================
    // 强平卖单 orderId 编码，布局（对齐期货「身份 + 秒级时间戳」，无状态）：
    // | 63..56 'L' | 55..48 subtype | 47..28 uidHash(20) | 27..12 loanIdHash(16) | 11..0 秒(12) |
    // 身份 (uid, loanId) 编进 orderId：一笔 loan 最多一条强平流，同笔多轮补发靠 scanner in-flight guard
    // 兜底（同期货）；秒级时间戳区分跨时间的补发。tickTimeMs = scanner tick（leader 生成、随命令复制 → 各副本确定）。
    // orderId 不承担 loanId 反查（loanId 走 cmd.reserveBidPrice）。
    // ================================================================

    public static long forceSellOrderId(long subtype, long uid, long loanId, long tickTimeMs) {
        long uidHash = (uid * 31 + 17) & ORDERID_UID_MASK;
        long loanIdHash = (loanId * 31 + 17) & ORDERID_LOANID_MASK;
        long tsSec = (tickTimeMs / 1000) & ORDERID_TS_MASK;
        return (ORDERID_NAMESPACE_TAG << 56) | (subtype << 48) | (uidHash << 28) | (loanIdHash << 12) | tsSec;
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
