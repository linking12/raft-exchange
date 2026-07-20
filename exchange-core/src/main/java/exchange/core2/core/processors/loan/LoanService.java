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

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.CrossLoanRecord;
import exchange.core2.core.common.LoanRecord;
import exchange.core2.core.common.StateHash;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.LastPriceCacheRecord;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.loan.rate.FixedRateModel;
import exchange.core2.core.processors.loan.rate.FloatingRateModel;
import exchange.core2.core.utils.CoreArithmeticUtils;
import exchange.core2.core.utils.SerializationUtils;
import lombok.Getter;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;

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

    // 资金桶（进 raft snapshot，参与全局守恒对账）
    private final IntLongHashMap loanPoolAvailable; // 各币种可借余额（currency scale）
    private final IntLongHashMap loanPoolBorrowed; // 各币种已借出本金（currency scale）
    private final IntLongHashMap interestRevenue; // 利息收入（currency scale）
    private final IntLongHashMap loanInsuranceFund; // IF 保险基金
    // 运行时配置与利率子系统（同进 snapshot）
    private final LoanGlobalConfig globalConfig; // 全局运行时配置（Cross 阈值 / pool 利用率上限 / numeraire）
    private final FloatingRateModel floatingRate; // 活期利率：Isolated FLOATING + 全部 Cross
    private final FixedRateModel fixedRate; // 定期利率：Isolated LOCKED，开仓时锚定 floatingRate 当前利率

    public LoanService() {
        this.loanPoolAvailable = new IntLongHashMap();
        this.loanPoolBorrowed = new IntLongHashMap();
        this.interestRevenue = new IntLongHashMap();
        this.loanInsuranceFund = new IntLongHashMap();
        this.globalConfig = new LoanGlobalConfig();
        this.floatingRate = new FloatingRateModel();
        this.fixedRate = new FixedRateModel(floatingRate);
    }

    public LoanService(BytesIn bytes) {
        this.loanPoolAvailable = SerializationUtils.readIntLongHashMap(bytes);
        this.loanPoolBorrowed = SerializationUtils.readIntLongHashMap(bytes);
        this.interestRevenue = SerializationUtils.readIntLongHashMap(bytes);
        this.loanInsuranceFund = SerializationUtils.readIntLongHashMap(bytes);
        this.globalConfig = new LoanGlobalConfig(bytes);
        this.floatingRate = new FloatingRateModel(bytes);
        this.fixedRate = new FixedRateModel(floatingRate, bytes);
    }

    public void reset() {
        loanPoolAvailable.clear();
        loanPoolBorrowed.clear();
        interestRevenue.clear();
        loanInsuranceFund.clear();
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
        SerializationUtils.marshallIntLongHashMap(interestRevenue, bytes);
        SerializationUtils.marshallIntLongHashMap(loanInsuranceFund, bytes);
        globalConfig.writeMarshallable(bytes);
        floatingRate.writeMarshallable(bytes);
        fixedRate.writeMarshallable(bytes);
    }

    @Override
    public int stateHash() {
        return Objects.hash(loanPoolAvailable.hashCode(), loanPoolBorrowed.hashCode(),
            interestRevenue.hashCode(), loanInsuranceFund.hashCode(), globalConfig.stateHash(),
            floatingRate.stateHash(), fixedRate.stateHash());
    }

    @Override
    public String toString() {
        return "LoanService{" + "poolAvail=" + loanPoolAvailable + ", poolBorrowed=" + loanPoolBorrowed + ", intRev="
            + interestRevenue + ", lif=" + loanInsuranceFund + ", " + globalConfig + '}';
    }

    // ====================================================================================
    // 计息与抵债：REPAY 与强平结算共用同一处金钱逻辑
    // ====================================================================================

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
        loan.setCumInterestPaid(loan.getCumInterestPaid() + interestPart); // 单调累计，供事件发快照
        interestRevenue.addToValue(currency, interestPart);
        loanPoolAvailable.addToValue(currency, principalPart);
        loanPoolBorrowed.addToValue(currency, -principalPart);
        return interestPart;
    }

    /**
     * 强平所得 receivedQuote（已扣撮合 takerFee）的统一去向：先按 loanLiquidationFeeBps 抽强平费进 loanInsuranceFund
     * （ceil 向交易所取整，不少收），再 accrue 补计利息后抵债，剩余 overpay 留在 account。返回本次结算的利息部分（≥ 0）。
     * Isolated / Cross 强平共用。
     */
    public long settleLiquidationProceeds(LoanRecord loan, IntLongHashMap account, long receivedQuote, long now) {
        final long feeByRate = CoreArithmeticUtils.ceilMulDiv(receivedQuote, globalConfig.loanLiquidationFeeBps, BPS_SCALE);
        final long liqFee = Math.min(receivedQuote, feeByRate);
        account.addToValue(loan.getLoanCurrency(), -liqFee);
        loanInsuranceFund.addToValue(loan.getLoanCurrency(), liqFee);
        accrueTo(loan, now);
        return applyDebtPayment(loan, account, receivedQuote - liqFee);
    }

    // ====================================================================================
    // Cross 账户级 LTV：触发用加权口径，定价用市值口径
    // ====================================================================================

    /**
     * 账户级 LTV（bps）= totalDebt × BPS_SCALE / weightedCollateral，归一到 numeraire 的 currencyScale。
     * scanner 触发决策与 Cross BORROW/WITHDRAW 校验共用；喂价/spec 缺失时的取舍见 6 参重载。
     * 定价另走 {@link #calculateCrossRawLtvBps}。
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
        return crossLtvBps(userProfile, now, specProvider, currencyProvider, priceCache, numeraireCurrency,
            failClosedOnMissingPrice, true);
    }

    /**
     * 破产价定价专用的账户级 LTV（bps）：抵押取市值，不打 {@code collateralWeightBps} 折——weight 属触发判定，
     * 代进定价会把破产价抬高 1/weight 倍（loan.md §18.3）。喂价缺失同 6 参重载，返 0 由调用方兜底。
     */
    public long calculateCrossRawLtvBps(UserProfile userProfile, long now, SymbolSpecificationProvider specProvider,
        CurrencySpecificationProvider currencyProvider, IntObjectHashMap<LastPriceCacheRecord> priceCache,
        int numeraireCurrency) {
        return crossLtvBps(userProfile, now, specProvider, currencyProvider, priceCache, numeraireCurrency, false,
            false);
    }

    private long crossLtvBps(UserProfile userProfile, long now, SymbolSpecificationProvider specProvider,
        CurrencySpecificationProvider currencyProvider, IntObjectHashMap<LastPriceCacheRecord> priceCache,
        int numeraireCurrency, boolean failClosedOnMissingPrice, boolean applyWeight) {
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

        // 抵押侧：折算 numeraire 后求和，applyWeight 决定是否再打 collateralWeightBps 折
        long totalCollateral = 0L;
        final int[] currencies = userProfile.crossLoanCollateral.keySet().toArray();
        for (int currency : currencies) {
            final long amount = userProfile.crossLoanCollateral.get(currency);
            if (amount <= 0)
                continue;
            final int weight = collateralWeightForBase(currency, currencyProvider);
            if (weight <= 0)
                continue; // 非抵押白名单币：两种口径都不计入
            final long valueInNum = valueInNumeraire(currency, amount, numeraireCurrency, numeraireSpec, specProvider,
                currencyProvider, priceCache);
            if (valueInNum < 0)
                return unevaluable;
            try {
                final long contribution =
                    applyWeight ? CoreArithmeticUtils.truncMulDiv(valueInNum, weight, BPS_SCALE) : valueInNum;
                totalCollateral = Math.addExact(totalCollateral, contribution);
            } catch (ArithmeticException e) {
                return unevaluable; // 溢出不放大抵押，保守按不可估值处理
            }
        }

        if (totalCollateral <= 0) {
            return Long.MAX_VALUE;
        }
        return CoreArithmeticUtils.truncMulDiv(totalDebt, BPS_SCALE, totalCollateral);
    }

    // ====================================================================================
    // LIF 接管：市场按破产价接不住时的终局（loan.md §18）
    // ====================================================================================

    /**
     * Cross LIF 承接：按 {@code targetLoan} 债务占账户总债的比例，从共享抵押池取走等值抵押。
     *
     * <p>不整户接管——未触及强平线的其他债不该被牵连。按 numeraire 估值定额扣（而非每币种等比切）：
     * 等比切会把每种抵押都切碎，LIF 收一堆尘埃且截断误差随币种数放大；定额扣使承接的币种尽量集中。
     * 扣减顺序按 {@code collateralWeightBps} 降序、同权重按 currency 升序——<b>必须确定性</b>，
     * 该方法在 R2 于所有副本执行，哈希序会导致状态分叉。
     *
     * <p>喂价缺失时 <b>fail-closed</b>：返回 false 拒绝接管、等下一轮，绝不用失真价格决定拿走用户多少抵押。
     *
     * @return true = 已承接并扣除抵押；false = 无法估值，调用方须保留 loan 原样
     */
    public boolean takeOverCrossLoan(UserProfile up, CrossLoanRecord targetLoan, long now,
        SymbolSpecificationProvider specProvider, CurrencySpecificationProvider currencyProvider,
        IntObjectHashMap<LastPriceCacheRecord> priceCache) {
        final int numeraireCurrency = globalConfig.numeraireCurrency;
        if (numeraireCurrency == 0) {
            return false;
        }
        final CoreCurrencySpecification numeraireSpec = currencyProvider.getCurrencySpecification(numeraireCurrency);
        if (numeraireSpec == null) {
            return false;
        }

        final long targetDebt = Math.addExact(targetLoan.outstandingPrincipal, calculateDisplayInterest(targetLoan, now));
        final long targetDebtInNum = valueInNumeraire(targetLoan.loanCurrency, targetDebt, numeraireCurrency,
            numeraireSpec, specProvider, currencyProvider, priceCache);
        if (targetDebtInNum < 0) {
            return false;
        }

        long totalDebtInNum = 0L;
        for (CrossLoanRecord loan : up.crossLoans) {
            final long debt = Math.addExact(loan.outstandingPrincipal, calculateDisplayInterest(loan, now));
            if (debt <= 0) {
                continue;
            }
            final long v = valueInNumeraire(loan.loanCurrency, debt, numeraireCurrency, numeraireSpec, specProvider,
                currencyProvider, priceCache);
            if (v < 0) {
                return false;
            }
            totalDebtInNum = Math.addExact(totalDebtInNum, v);
        }
        if (totalDebtInNum <= 0) {
            return false;
        }

        // 抵押币按 weight 降序、currency 升序排定，保证各副本扣减顺序一致
        final int[] currencies = up.crossLoanCollateral.keySet().toArray();
        final Integer[] ordered = new Integer[currencies.length];
        for (int i = 0; i < currencies.length; i++) {
            ordered[i] = currencies[i];
        }
        java.util.Arrays.sort(ordered, (a, b) -> {
            final int wa = collateralWeightForBase(a, currencyProvider);
            final int wb = collateralWeightForBase(b, currencyProvider);
            return wa != wb ? Integer.compare(wb, wa) : Integer.compare(a, b);
        });

        long totalCollateralInNum = 0L;
        for (int currency : ordered) {
            final long amount = up.crossLoanCollateral.get(currency);
            // 零权重币不撑 LTV（口径同 calculateCrossAccountLtvBps），接管也不能取——否则扣了从未支撑过借款的余额
            if (amount <= 0 || collateralWeightForBase(currency, currencyProvider) <= 0) {
                continue;
            }
            final long v = valueInNumeraire(currency, amount, numeraireCurrency, numeraireSpec, specProvider,
                currencyProvider, priceCache);
            if (v < 0) {
                return false;
            }
            totalCollateralInNum = Math.addExact(totalCollateralInNum, v);
        }

        // 应取估值 = 账户抵押总值 × 该笔债占比。
        // 不足一张的尘埃在 numeraire 估值中截断为 0，因而分摊不到、留给借款人——LIF 不囤无法变现的碎屑。
        long remainingToTake =
            CoreArithmeticUtils.truncMulDiv(totalCollateralInNum, targetDebtInNum, totalDebtInNum);
        for (int currency : ordered) {
            if (remainingToTake <= 0) {
                break;
            }
            final long amount = up.crossLoanCollateral.get(currency);
            if (amount <= 0) {
                continue;
            }
            final long valueInNum = valueInNumeraire(currency, amount, numeraireCurrency, numeraireSpec, specProvider,
                currencyProvider, priceCache);
            if (valueInNum <= 0) {
                continue;
            }
            final long take = valueInNum <= remainingToTake ? amount
                : CoreArithmeticUtils.truncMulDiv(amount, remainingToTake, valueInNum);
            if (take <= 0) {
                continue;
            }
            up.crossLoanCollateral.addToValue(currency, -take);
            up.accounts.addToValue(currency, -take); // 抵押原为虚拟锁定，接管时真实扣走
            loanInsuranceFund.addToValue(currency, take);
            remainingToTake -= Math.min(valueInNum, remainingToTake);
        }

        // LIF 代偿债务：池子回血、利息落收入，LIF 转负（负值即已垫资额，非损失）
        final int loanCurrency = targetLoan.loanCurrency;
        loanInsuranceFund.addToValue(loanCurrency, -targetDebt);
        loanPoolAvailable.addToValue(loanCurrency, targetLoan.outstandingPrincipal);
        loanPoolBorrowed.addToValue(loanCurrency, -targetLoan.outstandingPrincipal);
        interestRevenue.addToValue(loanCurrency, Math.subtractExact(targetDebt, targetLoan.outstandingPrincipal));
        return true;
    }

    // ====================================================================================
    // 估值与 scale 换算：currencyScale ↔ symbol lot 两套精度的显式桥梁
    // ====================================================================================

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
        final CoreSymbolSpecification spec = specProvider.findSpotSymbol(currency, numeraireCurrency);
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

    /**
     * 该抵押币是否<b>结构上可变现</b>——只看永久能力，不看 markPrice 这类临时状态：
     * 该币 {@code collateralWeightBps > 0}（币种级），且存在 base=该币、quote=本账户某笔未偿债币种的现货对、量够 ≥1 lot
     * （卖了能真的还上债）。与 {@code pickCrossCollateralToSell} 的永久性条件同源。
     */
    public static boolean isStructurallySellable(int currency, long amount, UserProfile up,
        SymbolSpecificationProvider specProvider, CurrencySpecificationProvider currencyProvider) {
        if (amount <= 0)
            return false;
        final CoreCurrencySpecification currencySpec = currencyProvider.getCurrencySpecification(currency);
        if (currencySpec == null || currencySpec.collateralWeightBps <= 0)
            return false; // 非抵押白名单币（weight=0）
        // 遍历本账户未偿 Cross 债，对每笔债币反查 base=currency/quote=债币 的现货对：
        // 卖 currency 能换到某笔债的计价币、量够 ≥1 lot 才算“结构可变现”
        for (CrossLoanRecord loan : up.crossLoans) {
            if (loan.outstandingPrincipal <= 0)
                continue;
            final CoreSymbolSpecification spec = specProvider.findSpotSymbol(currency, loan.loanCurrency);
            if (spec != null && collateralAmountToLots(amount, spec, currencySpec) > 0) {
                return true;
            }
        }
        return false;
    }

    /** currency 作 Cross 抵押的折价率（bps）：直接读币种级配置；未配置/未开放返回 0。O(1)。 */
    public static int collateralWeightForBase(int currency, CurrencySpecificationProvider currencyProvider) {
        final CoreCurrencySpecification spec = currencyProvider.getCurrencySpecification(currency);
        return spec == null ? 0 : spec.collateralWeightBps;
    }

    // ====================================================================================
    // force-sell orderId 编码：与普通单 / 期货强平 / ADL 的 id 空间隔离
    // ====================================================================================

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
