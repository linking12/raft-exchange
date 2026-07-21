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
package exchange.core2.core.common.api.reports;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.utils.SerializationUtils;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.stream.Stream;

/**
 * 全交易所按 currency 聚合的对账报告，所有资金桶之和恒为 0（{@link #getGlobalBalancesSum}）。 与单用户明细快照
 * {@link SingleUserReportResult}（无平台桶、不求和）分工正交。
 */
@AllArgsConstructor
@EqualsAndHashCode
@Getter
@ToString
public final class TotalCurrencyBalanceReportResult implements ReportResult {

    // currency -> balance
    /**
     * 用户可支配余额（= UserProfile.accounts - exchangeLocked - loanCollateral）。注意：与 SingleUserReportResult.accounts
     * 语义不同——后者是真实持有总额。
     */
    final private IntLongHashMap accountBalances;
    final private IntLongHashMap extraMargin;
    final private IntLongHashMap fees;
    final private IntLongHashMap adjustments;
    final private IntLongHashMap suspends;
    /** RiskEngine 聚合的 UserProfile.exchangeLocked（现货侧冻结，作为独立 bucket 参与全局对账） */
    final private IntLongHashMap exchangeLocked;
    /**
     * loan 平台桶 = loanPoolAvailable + interestRevenue + loanInsuranceFund（均平台持有现金，参与全局对账）。 不含 loanPoolBorrowed ——
     * 那是追踪器，对应的钱在借款人账户里（disburse 时已进 accounts）。
     */
    final private IntLongHashMap loanBalances;
    /**
     * loan 抵押物（cross 账户级 crossLoanCollateral + isolated 单笔 collateralAmount）。物理仍在用户 accounts， 但被 loan 占用不可支配，从
     * accountBalances 剥出作独立 bucket 参与全局对账。
     */
    final private IntLongHashMap loanCollateral;

    // symbol -> volume
    // We have to keep shorts and longs separately because for multi-core processing different risk engine instances
    // will give non-matching results.
    // They should match when aggregated though.
    final private IntLongHashMap openInterestLong;
    final private IntLongHashMap openInterestShort;

    // currency -> balance
    private final IntLongHashMap ifBalances;
    // symbol -> volume
    private final IntLongHashMap ifOpenInterestLong;
    private final IntLongHashMap ifOpenInterestShort;

    /** currency id -> spec（含 name）。仅展示用，不参与对账或 stateHash。所有 shard 副本相同。 */
    private final IntObjectHashMap<CoreCurrencySpecification> currencySpecs;
    /** symbol id -> spec（含 base/quote currency）。仅展示用，给 open_interest_* 等 symbol-key 字段翻译可读名。 */
    private final IntObjectHashMap<CoreSymbolSpecification> symbolSpecs;

    public static TotalCurrencyBalanceReportResult createEmpty() {
        return new TotalCurrencyBalanceReportResult(null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, null);
    }

    private TotalCurrencyBalanceReportResult(final BytesIn bytesIn) {
        this.accountBalances = SerializationUtils.readNullable(bytesIn, SerializationUtils::readIntLongHashMap);
        this.extraMargin = SerializationUtils.readNullable(bytesIn, SerializationUtils::readIntLongHashMap);
        this.fees = SerializationUtils.readNullable(bytesIn, SerializationUtils::readIntLongHashMap);
        this.adjustments = SerializationUtils.readNullable(bytesIn, SerializationUtils::readIntLongHashMap);
        this.suspends = SerializationUtils.readNullable(bytesIn, SerializationUtils::readIntLongHashMap);
        this.exchangeLocked = SerializationUtils.readNullable(bytesIn, SerializationUtils::readIntLongHashMap);
        this.loanBalances = SerializationUtils.readNullable(bytesIn, SerializationUtils::readIntLongHashMap);
        this.loanCollateral = SerializationUtils.readNullable(bytesIn, SerializationUtils::readIntLongHashMap);
        this.openInterestLong = SerializationUtils.readNullable(bytesIn, SerializationUtils::readIntLongHashMap);
        this.openInterestShort = SerializationUtils.readNullable(bytesIn, SerializationUtils::readIntLongHashMap);
        this.ifBalances = SerializationUtils.readNullable(bytesIn, SerializationUtils::readIntLongHashMap);
        this.ifOpenInterestLong = SerializationUtils.readNullable(bytesIn, SerializationUtils::readIntLongHashMap);
        this.ifOpenInterestShort = SerializationUtils.readNullable(bytesIn, SerializationUtils::readIntLongHashMap);
        this.currencySpecs = SerializationUtils.readNullable(bytesIn,
            b -> SerializationUtils.readIntHashMap(b, CoreCurrencySpecification::new));
        this.symbolSpecs = SerializationUtils.readNullable(bytesIn,
            b -> SerializationUtils.readIntHashMap(b, CoreSymbolSpecification::new));
    }

    @Override
    public void writeMarshallable(final BytesOut bytes) {
        SerializationUtils.marshallNullable(accountBalances, bytes, SerializationUtils::marshallIntLongHashMap);
        SerializationUtils.marshallNullable(extraMargin, bytes, SerializationUtils::marshallIntLongHashMap);
        SerializationUtils.marshallNullable(fees, bytes, SerializationUtils::marshallIntLongHashMap);
        SerializationUtils.marshallNullable(adjustments, bytes, SerializationUtils::marshallIntLongHashMap);
        SerializationUtils.marshallNullable(suspends, bytes, SerializationUtils::marshallIntLongHashMap);
        SerializationUtils.marshallNullable(exchangeLocked, bytes, SerializationUtils::marshallIntLongHashMap);
        SerializationUtils.marshallNullable(loanBalances, bytes, SerializationUtils::marshallIntLongHashMap);
        SerializationUtils.marshallNullable(loanCollateral, bytes, SerializationUtils::marshallIntLongHashMap);
        SerializationUtils.marshallNullable(openInterestLong, bytes, SerializationUtils::marshallIntLongHashMap);
        SerializationUtils.marshallNullable(openInterestShort, bytes, SerializationUtils::marshallIntLongHashMap);
        SerializationUtils.marshallNullable(ifBalances, bytes, SerializationUtils::marshallIntLongHashMap);
        SerializationUtils.marshallNullable(ifOpenInterestLong, bytes, SerializationUtils::marshallIntLongHashMap);
        SerializationUtils.marshallNullable(ifOpenInterestShort, bytes, SerializationUtils::marshallIntLongHashMap);
        SerializationUtils.marshallNullable(currencySpecs, bytes, SerializationUtils::marshallIntHashMap);
        SerializationUtils.marshallNullable(symbolSpecs, bytes, SerializationUtils::marshallIntHashMap);
    }

    /**
     * 全局对账：accountBalances + extraMargin + exchangeLocked + loanCollateral + loanBalances + fees + adjustments +
     * suspends + ifBalances = 0。clients / platform / external 三者互斥并覆盖以上全部桶。
     */
    public IntLongHashMap getGlobalBalancesSum() {
        return SerializationUtils.mergeSum(accountBalances, extraMargin, exchangeLocked, loanCollateral, loanBalances,
            fees, adjustments, suspends, ifBalances);
    }

    /** 用户侧求和（含 suspends 冻结）。 */
    public IntLongHashMap getClientsBalancesSum() {
        return SerializationUtils.mergeSum(accountBalances, extraMargin, exchangeLocked, loanCollateral, suspends);
    }

    /** 平台侧求和：手续费 + 借贷平台桶 + 保险基金。 */
    public IntLongHashMap getPlatformBalancesSum() {
        return SerializationUtils.mergeSum(fees, loanBalances, ifBalances);
    }

    /** 外部侧：adjustments（存取款对冲分录）。 */
    public IntLongHashMap getExternalSum() {
        return SerializationUtils.mergeSum(adjustments);
    }

    public boolean isGlobalBalancesAllZero() {
        return getGlobalBalancesSum().allSatisfy(amount -> amount == 0L);
    }

    public static TotalCurrencyBalanceReportResult merge(final Stream<BytesIn> pieces) {
        return pieces.sequential() // 强制串行流，不用ForkJoinPool
            .map(TotalCurrencyBalanceReportResult::new).reduce(TotalCurrencyBalanceReportResult.createEmpty(),
                (a, b) -> new TotalCurrencyBalanceReportResult(
                    SerializationUtils.mergeSum(a.accountBalances, b.accountBalances),
                    SerializationUtils.mergeSum(a.extraMargin, b.extraMargin),
                    SerializationUtils.mergeSum(a.fees, b.fees),
                    SerializationUtils.mergeSum(a.adjustments, b.adjustments),
                    SerializationUtils.mergeSum(a.suspends, b.suspends),
                    SerializationUtils.mergeSum(a.exchangeLocked, b.exchangeLocked),
                    SerializationUtils.mergeSum(a.loanBalances, b.loanBalances),
                    SerializationUtils.mergeSum(a.loanCollateral, b.loanCollateral),
                    SerializationUtils.mergeSum(a.openInterestLong, b.openInterestLong),
                    SerializationUtils.mergeSum(a.openInterestShort, b.openInterestShort),
                    SerializationUtils.mergeSum(a.ifBalances, b.ifBalances),
                    SerializationUtils.mergeSum(a.ifOpenInterestLong, b.ifOpenInterestLong),
                    SerializationUtils.mergeSum(a.ifOpenInterestShort, b.ifOpenInterestShort),
                    // 所有 shard 的 currencySpecs / symbolSpecs 内容相同（同一批 batch 注册），任取其一即可
                    SerializationUtils.mergeOverride(a.currencySpecs, b.currencySpecs),
                    SerializationUtils.mergeOverride(a.symbolSpecs, b.symbolSpecs)));
    }

}
