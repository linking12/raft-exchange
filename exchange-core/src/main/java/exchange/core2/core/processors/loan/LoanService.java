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
 * 现货借贷 <b>纯状态类</b> —— per-shard 一份实例，挂在 {@link exchange.core2.core.processors.RiskEngine} 上。
 *
 * <p><b>本类承担</b>：
 * <ul>
 * <li>State 承载 + 序列化 / stateHash（池子可借/已借/坏账/利息收入/强平费五桶 + 三阈值 + 池子幂等表）</li>
 * <li>纯函数工具：惰性计息 accrue（写路径 + 只读 calculateDisplayInterest）/ 池子利用率 / Cross 账户级 LTV /
 * force-sell orderId 编码 / spec 查询辅助（findSpotSpec / collateralWeightForBase / collateralValueInQuoteCurrency）</li>
 * </ul>
 *
 * <p><b>不承担</b>：命令 apply 业务流（走 {@link LoanCommandHandlers}）；scanner 触发（走
 * {@link LoanLiquidationEngine}）。<b>不持</b> RiskEngine ref，无 wire late-bind——所有依赖外部 provider 的方法
 * 通过参数传入而非缓存字段。
 *
 * <p><b>Per-shard state（进 raft snapshot）</b>：五个 IntLongHashMap 是同 shard 内 Isolated + Cross 共用的
 * 池子/坏账/收入账本，跨 shard 独立（对齐期货 IF 池设计）。三个阈值是 shard-local 但通常运营层保证跨 shard 一致。
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

    // 1 年 = 365 × 24 × 3600 × 10^9 ns（跨节点唯一确定性形式）
    public static final long YEAR_NS = 365L * 24L * 3600L * 1_000_000_000L;
    // LTV / rate 的 bps 精度基准（10000 bps = 100%）
    public static final long BPS_SCALE = 10_000L;

    // force-sell OrderId 编码常量（详见 loan.md §7.4）
    public static final long ORDERID_NAMESPACE_TAG = 0x4CL; // 'L' 独占，避开期货 IF 'I' 0x49 / ADL 'A' 0x41
    public static final long ORDERID_SUBTYPE_ISOLATED = 0x53L; // 'S' 独占
    public static final long ORDERID_SUBTYPE_CROSS = 0x43L; // 'C' 独占
    // 24 bit payload——只作 orderId 唯一性打散，不承担 loanId 反查（loanId 走 cmd.reserveBidPrice 传输）
    public static final long ORDERID_PAYLOAD_MASK = 0xFFFFFFL; // 24 bit
    private static final long ORDERID_UIDHASH_MASK = 0xFFFFFL; // 20 bit
    private static final long ORDERID_TS_MASK = 0xFL; // 4 bit

    // ================================================================
    // State（进 raft snapshot，per-shard 独立）
    // ================================================================

    // 池子可借出量（守恒方程正 bucket）；LOAN_CREATE / BORROW 扣减，REPAY / force-sell / POOL_DEPOSIT 增加。
    @Getter
    private final IntLongHashMap loanPoolAvailable;

    // 池子已借出量（不进守恒，只用于 utilization 校验 + 运营 metric）。
    // 不变量：Σ isolatedLoans.outstandingPrincipal(loanCcy==c) + Σ crossLoans.outstandingPrincipal(loanCcy==c) ==
    // loanPoolBorrowed[c]
    @Getter
    private final IntLongHashMap loanPoolBorrowed;

    // 强平坏账（守恒方程负 bucket）；underwater force-sell 时交易所自吸损失。
    @Getter
    private final IntLongHashMap badDebt;

    // 借贷利息收入（loanCcy scale）；REPAY / force-sell 结算时 interest 进这个桶，跟撮合 fees 分账。
    @Getter
    private final IntLongHashMap interestRevenue;

    // 借贷强平专项 fee（loanCcy scale）；force-sell 时按 LOAN_LIQUIDATION_FEE_BPS 从 receivedQuote 抽取。
    @Getter
    private final IntLongHashMap loanLiqFees;

    // Cross 账户级强平线 / 预警线 / 池子利用率上限（bps）
    @Getter
    private int crossLiquidationLtvBps;
    @Getter
    private int crossMarginCallLtvBps;
    @Getter
    private int loanPoolUtilizationCapBps;

    // Cross 借贷估值基准币 ID（loan.md §1.2 "Cross 基准币: USDT"）。走 UPDATE_LOAN_NUMERAIRE_CONFIG binary
    // 通道配置，进 raft snapshot 后跟 leader failover 一起复制。NUMERAIRE_UNSET (0) 时 Cross BORROW /
    // WITHDRAW handler fail-close，scanner 保守跳过强平（避免误触发）。
    @Getter
    private int numeraireCcy;

    // POOL_DEPOSIT / POOL_WITHDRAW 幂等表（per-shard 独立）；
    // 幂等 key = hash(cmdType, externalId)（loan.md §5.10.3），跟 UserProfile.processedExternalIds 完全独立表。
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
        this.numeraireCcy = NUMERAIRE_UNSET;
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
        this.numeraireCcy = bytes.readInt();
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
        numeraireCcy = NUMERAIRE_UNSET;
    }

    /** Numeraire 是否已配置。未配置时 Cross BORROW / WITHDRAW handler 必须 fail-close。 */
    public boolean isNumeraireConfigured() {
        return numeraireCcy != NUMERAIRE_UNSET;
    }

    /**
     * 更新 numeraireCcy —— 唯一入口是 UPDATE_LOAN_NUMERAIRE_CONFIG 命令 apply 时（RiskEngine.handleBinaryMessage）。
     * 走 raft 复制到所有节点，保持跨 shard 一致。
     */
    public void setNumeraireCcy(int numeraireCcy) {
        this.numeraireCcy = numeraireCcy;
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
        bytes.writeInt(numeraireCcy);
        poolProcessedExternalIds.writeMarshallable(bytes);
    }

    @Override
    public int stateHash() {
        // IntLongHashMap.hashCode() 本身 order/capacity independent，跟 UserProfile.stateHash 的 accounts/exchangeLocked 同款 pattern。
        return Objects.hash(loanPoolAvailable.hashCode(), loanPoolBorrowed.hashCode(), badDebt.hashCode(),
            interestRevenue.hashCode(), loanLiqFees.hashCode(),
            crossLiquidationLtvBps, crossMarginCallLtvBps, loanPoolUtilizationCapBps, numeraireCcy,
            poolProcessedExternalIds.stateHash());
    }

    @Override
    public String toString() {
        return "LoanService{" + "poolAvail=" + loanPoolAvailable + ", poolBorrowed=" + loanPoolBorrowed + ", badDebt="
            + badDebt + ", intRev=" + interestRevenue + ", liqFees=" + loanLiqFees
            + ", crossLiqLtv=" + crossLiquidationLtvBps + ", crossMcLtv=" + crossMarginCallLtvBps
            + ", poolCap=" + loanPoolUtilizationCapBps + ", poolDedup=" + poolProcessedExternalIds.size() + '}';
    }

    // ================================================================
    // 惰性计息 —— accrueTo（写路径）
    //
    // 触发点仅两个：LOAN_REPAY / LOAN_CROSS_REPAY apply 前、LOAN_FORCE_LIQUIDATE / LOAN_CROSS_FORCE_LIQUIDATE apply 前。
    // 详见 loan.md §6.1。
    //
    // 公式：interest = elapsed_ns × outstandingPrincipal × rateBps / (YEAR_NS × BPS_SCALE)
    // 溢出保护：两次 truncMulDiv（128-bit fallback），先 (elapsed × principal / YEAR_NS) 再 × rateBps / BPS_SCALE。
    // 时钟保护：now < loan.lastAccrueTs 视为 0 elapsed，不 accrue、不推 lastAccrueTs。
    // ================================================================

    /** 写路径：给 IsolatedLoanRecord 补计利息到 now；返回本次新增的利息（可能为 0）。 */
    public long accrueTo(IsolatedLoanRecord loan, long now) {
        long delta = accrueDelta(loan.outstandingPrincipal, loan.rateBps, loan.lastAccrueTs, now);
        if (delta > 0) {
            loan.accumulatedInterest = Math.addExact(loan.accumulatedInterest, delta);
        }
        if (now > loan.lastAccrueTs) {
            loan.lastAccrueTs = now;
        }
        return delta;
    }

    /** 写路径：CrossLoanRecord 版本，语义同上。 */
    public long accrueTo(CrossLoanRecord loan, long now) {
        long delta = accrueDelta(loan.outstandingPrincipal, loan.rateBps, loan.lastAccrueTs, now);
        if (delta > 0) {
            loan.accumulatedInterest = Math.addExact(loan.accumulatedInterest, delta);
        }
        if (now > loan.lastAccrueTs) {
            loan.lastAccrueTs = now;
        }
        return delta;
    }

    /** 读路径：返回 accumulatedInterest + accrueDelta(now)，不修改 loan record。 */
    public long calculateDisplayInterest(IsolatedLoanRecord loan, long now) {
        long pending = accrueDelta(loan.outstandingPrincipal, loan.rateBps, loan.lastAccrueTs, now);
        return Math.addExact(loan.accumulatedInterest, pending);
    }

    /** 读路径：CrossLoanRecord 版本，语义同上。 */
    public long calculateDisplayInterest(CrossLoanRecord loan, long now) {
        long pending = accrueDelta(loan.outstandingPrincipal, loan.rateBps, loan.lastAccrueTs, now);
        return Math.addExact(loan.accumulatedInterest, pending);
    }

    /**
     * 内部共享：给定 principal / rate / lastAccrueTs / now，返回本次应新增的利息量（≥ 0）。
     * <p>
     * 输入 lastAccrueTs > now 时返回 0（时钟倒退保护）。
     */
    private static long accrueDelta(long outstandingPrincipal, int rateBps, long lastAccrueTs, long now) {
        if (outstandingPrincipal <= 0 || rateBps <= 0) {
            return 0L;
        }
        long elapsed = now - lastAccrueTs;
        if (elapsed <= 0) {
            return 0L;
        }
        long step1 = CoreArithmeticUtils.truncMulDiv(elapsed, outstandingPrincipal, YEAR_NS);
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

    /**
     * v2 slot：当前恒返 0，handler 侧直接用 {@code spec.loanRateBps} snapshot 到 loan.rateBps。
     * v2 可基于 {@link #calculatePoolUtilizationBps} + 阶梯曲线（如 Aave 双斜率）动态计算，届时 LOAN_CREATE /
     * LOAN_CROSS_BORROW 改成 {@code Math.max(spec.loanRateBps, calculateEffectiveRateBps(loanCcy))}。
     */
    public int calculateEffectiveRateBps(int loanCurrency) {
        return 0;
    }

    // ================================================================
    // Cross 账户级 LTV
    // ================================================================

    /**
     * 账户级 LTV（bps）—— scanner 决策（≥ crossLiquidationLtvBps 触发强平；≥ crossMarginCallLtvBps 发预警）+
     * handler 校验（LOAN_CROSS_BORROW / LOAN_CROSS_WITHDRAW_COLLATERAL 前置）共用。
     * 详见 loan.md §7.3 / §6.2 / §5.7 / §5.8。
     *
     * <p>公式（scale-normalized 到 numeraire currency scale）：
     * <pre>
     *   LTV(bps) = totalDebt × BPS_SCALE / weightedCollateral
     *   totalDebt          = Σ valueInNumeraire(loan.loanCcy, realDebt)
     *   realDebt           = loan.outstandingPrincipal + calculateDisplayInterest(loan, now)
     *   weightedCollateral = Σ valueInNumeraire(c, crossLoanCollateral[c]) × collateralWeightBps(c) / BPS_SCALE
     * </pre>
     * realDebt 含 accumulated + pending accrue——避免高利率下用户拖债不还避强平（scanner 也走同款口径）。
     *
     * <p>返回值语义：
     * <ul>
     * <li>crossLoans 空 → 0（无债务）</li>
     * <li>numeraireCcy 未配置（0）→ 0（保守，避免误触发）</li>
     * <li>任一 debt/collateral 的 markPrice / spec / currencySpec 缺失 → 0（跟 scanner "价格未就绪 skip" 同款保守）</li>
     * <li>weightedCollateral == 0 而有 debt → {@link Long#MAX_VALUE}（无抵押挂债务，无穷 LTV）</li>
     * <li>溢出：debt side → Long.MAX_VALUE；collateral side → 0</li>
     * </ul>
     */
    public long calculateCrossAccountLtvBps(UserProfile userProfile, long now,
        SymbolSpecificationProvider specProvider, CurrencySpecificationProvider currencyProvider,
        IntObjectHashMap<LastPriceCacheRecord> priceCache, int numeraireCcy) {
        if (userProfile.crossLoans.isEmpty()) {
            return 0L;
        }
        if (numeraireCcy == 0) {
            return 0L;
        }
        CoreCurrencySpecification numeraireSpec = currencyProvider.getCurrencySpecification(numeraireCcy);
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
            long valueInNum = valueInNumeraire(loan.loanCcy, realDebt, numeraireCcy, numeraireSpec,
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
        for (int c : currencies) {
            long amount = userProfile.crossLoanCollateral.get(c);
            if (amount <= 0)
                continue;
            int weight = collateralWeightForBase(c, specProvider);
            if (weight <= 0)
                continue;
            long valueInNum =
                valueInNumeraire(c, amount, numeraireCcy, numeraireSpec, specProvider, currencyProvider, priceCache);
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
     * 把 currency c 的 amount（currency scale）换算成 numeraire currency scale：
     * <ol>
     * <li>{@code c == numeraireCcy}：直接返回 amount（同 currency，无需换算）</li>
     * <li>否则找 spot symbol spec 满足 base=c, quote=numeraireCcy； amount(currencyScale) → base(baseScaleK) →
     * sizePriceScale(baseScaleK × quoteScaleK) → numeraire currencyScale</li>
     * </ol>
     * 找不到 spec / markPrice 缺失 / currencySpec 缺失 → 返回 -1（caller 视为 "价格未就绪 skip"）。
     */
    private static long valueInNumeraire(int c, long amount, int numeraireCcy, CoreCurrencySpecification numeraireSpec,
        SymbolSpecificationProvider specProvider, CurrencySpecificationProvider currencyProvider,
        IntObjectHashMap<LastPriceCacheRecord> priceCache) {
        if (c == numeraireCcy) {
            return amount;
        }
        CoreSymbolSpecification spec = findSpotSpec(c, numeraireCcy, specProvider);
        if (spec == null)
            return -1L;
        LastPriceCacheRecord rec = priceCache.get(spec.symbolId);
        if (rec == null || rec.markPrice <= 0)
            return -1L;
        CoreCurrencySpecification cSpec = currencyProvider.getCurrencySpecification(c);
        if (cSpec == null)
            return -1L;
        // amount(currencyScale for c) → base scale
        long baseAmount = CoreArithmeticUtils.currencyToSymbolScale(amount, spec, cSpec);
        // × markPrice → sizePriceScale (baseScaleK × quoteScaleK)
        long notional = Math.multiplyExact(baseAmount, rec.markPrice);
        // → numeraire currency scale
        return CoreArithmeticUtils.sizePriceToCurrencyScale(notional, spec, numeraireSpec);
    }

    /**
     * 把 base currency 的 amount（currencyScale of base）经现货 spec.markPrice 换算成 quote currency 的等值量（currencyScale of quote）。
     * <p>
     * Isolated LTV 校验（`handleLoanCreate` / `handleLoanReleaseCollateral`）+ Isolated scanner 都走这一条：
     * collateralCcy = spec.baseCurrency, loanCcy = spec.quoteCurrency，spec + markPrice 由 caller 拿好。
     * <p>
     * 三步换算：{@code amount (currencyScale of base) → base(baseScaleK) → sizePriceScale (× markPrice) → currencyScale of quote}。
     * <p>
     * baseCurrencySpec 缺失时返回 -1（caller 视为 markPrice not ready，跟 Cross valueInNumeraire 同款保守）。
     */
    public static long collateralValueInQuoteCurrency(long amount, CoreSymbolSpecification spec, long markPrice,
        CoreCurrencySpecification baseCurrencySpec, CoreCurrencySpecification quoteCurrencySpec) {
        if (baseCurrencySpec == null || quoteCurrencySpec == null)
            return -1L;
        long baseAmount = CoreArithmeticUtils.currencyToSymbolScale(amount, spec, baseCurrencySpec);
        long notional = Math.multiplyExact(baseAmount, markPrice);
        return CoreArithmeticUtils.sizePriceToCurrencyScale(notional, spec, quoteCurrencySpec);
    }

    /** 找 base=baseCcy, quote=quoteCcy 的现货 pair spec；O(N) 遍历。v2 加 (base,quote)→symbolId 反查索引。 */
    public static CoreSymbolSpecification findSpotSpec(int baseCcy, int quoteCcy,
        SymbolSpecificationProvider provider) {
        for (CoreSymbolSpecification spec : provider.getSymbolSpecs()) {
            if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR && spec.baseCurrency == baseCcy
                && spec.quoteCurrency == quoteCcy) {
                return spec;
            }
        }
        return null;
    }

    /**
     * 返回 currency c 作 Cross 抵押时的折价率（bps）；未开放作抵押返回 0。
     * <p>
     * 规则：找第一条 base=c 且 collateralWeightBps &gt; 0 的现货 pair spec 的 weight。
     */
    public static int collateralWeightForBase(int c, SymbolSpecificationProvider provider) {
        for (CoreSymbolSpecification spec : provider.getSymbolSpecs()) {
            if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR && spec.baseCurrency == c
                && spec.collateralWeightBps > 0) {
                return spec.collateralWeightBps;
            }
        }
        return 0;
    }

    // ================================================================
    // Force-sell OrderId 编码（详见 loan.md §7.4）
    //
    // 布局：
    // | 63....56 | 55...48 | 47..........24 | 23........4 | 3....0 |
    // | 'L' 0x4C | subtype | payload 24bit | uidHash 20 | ts 4 |
    // ================================================================

    public static long generateIsolatedForceSellOrderId(IsolatedLoanRecord loan) {
        long payload = loan.loanId & ORDERID_PAYLOAD_MASK;
        long uidHash = (loan.uid * 31 + 17) & ORDERID_UIDHASH_MASK;
        long ts = (System.currentTimeMillis() / 1000) & ORDERID_TS_MASK;
        return (ORDERID_NAMESPACE_TAG << 56) | (ORDERID_SUBTYPE_ISOLATED << 48) | (payload << 24) | (uidHash << 4) | ts;
    }

    public static long generateCrossForceSellOrderId(long uid, int sellingCcy) {
        long payload = ((long)sellingCcy) & ORDERID_PAYLOAD_MASK;
        long uidHash = (uid * 31 + 17) & ORDERID_UIDHASH_MASK;
        long ts = (System.currentTimeMillis() / 1000) & ORDERID_TS_MASK;
        return (ORDERID_NAMESPACE_TAG << 56) | (ORDERID_SUBTYPE_CROSS << 48) | (payload << 24) | (uidHash << 4) | ts;
    }

    /** 顶字节 == 'L' 即 loan force-sell orderId（对 Isolated / Cross 都成立）。 */
    public static boolean isLoanForceSellOrderId(long orderId) {
        return ((orderId >>> 56) & 0xFFL) == ORDERID_NAMESPACE_TAG;
    }

    /**
     * 返回 loan force-sell orderId 的 subtype 字节：{@code 'S'} = Isolated / {@code 'C'} = Cross。
     * <p>
     * 调用前 caller 应先 {@link #isLoanForceSellOrderId(long)} 过滤。
     */
    public static byte loanForceSellSubtype(long orderId) {
        return (byte)((orderId >>> 48) & 0xFFL);
    }
}
