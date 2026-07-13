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
import exchange.core2.core.common.PositionDirection;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.MatchingEngineRouter;
import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.Optional;
import java.util.stream.Stream;

@NoArgsConstructor
@EqualsAndHashCode
@ToString
public final class TotalCurrencyBalanceReportQuery implements ReportQuery<TotalCurrencyBalanceReportResult> {

    private static CurrencySpecificationProvider currencySpecificationProvider;
    private static SymbolSpecificationProvider symbolSpecificationProvider;

    private static void saveSymbolSpecificationProvider(SymbolSpecificationProvider provider) {
        symbolSpecificationProvider = provider;
    }

    private static void saveCurrencySpecificationProvider(CurrencySpecificationProvider provider) {
        currencySpecificationProvider = provider;
    }

    public TotalCurrencyBalanceReportQuery(BytesIn bytesIn) {
        // do nothing
    }

    @Override
    public int getReportTypeCode() {
        return ReportType.TOTAL_CURRENCY_BALANCE.getCode();
    }

    @Override
    public TotalCurrencyBalanceReportResult createResult(final Stream<BytesIn> sections) {
        return TotalCurrencyBalanceReportResult.merge(sections);
    }

    @Override
    public Optional<TotalCurrencyBalanceReportResult> process(final MatchingEngineRouter matchingEngine) {
        // accounts 已是真实持有总额，现货挂单冻结由 UserProfile.exchangeLocked 直接维护，
        // 不需要再从 OrderBook 重算 ordersBalances。
        return Optional.of(TotalCurrencyBalanceReportResult.createEmpty());
    }

    @Override
    public Optional<TotalCurrencyBalanceReportResult> process(final RiskEngine riskEngine) {
        saveCurrencySpecificationProvider(riskEngine.getCurrencySpecificationProvider());
        saveSymbolSpecificationProvider(riskEngine.getSymbolSpecificationProvider());
        // prepare fast price cache for profit estimation with some price (exact value is not important, except ask==bid
        // condition)
        final IntObjectHashMap<RiskEngine.LastPriceCacheRecord> dummyLastPriceCache = new IntObjectHashMap<>();
        riskEngine.getLastPriceCache().forEachKeyValue((s, r) -> dummyLastPriceCache.put(s, r.averagingRecord()));

        final IntLongHashMap currencyBalance = new IntLongHashMap();
        final IntLongHashMap extraMargin = new IntLongHashMap();
        final IntLongHashMap exchangeLockedTotal = new IntLongHashMap();

        final IntLongHashMap symbolOpenInterestLong = new IntLongHashMap();
        final IntLongHashMap symbolOpenInterestShort = new IntLongHashMap();

        final SymbolSpecificationProvider symbolSpecificationProvider = riskEngine.getSymbolSpecificationProvider();

        // 按 symbol 累加 PnL 和 extraMargin（在 product scale = baseScaleK × quoteScaleK 维度），
        // 再一次性 convert 到 currency scale。避免按用户逐个 convert 时
        // sizePriceToCurrencyScale 内整数截断导致的 per-user 残量累计
        // （zero-sum 仓位在 product scale 下精确为 0，但分别截断后 sum 会漂移）。
        // currency 在 convert 阶段从 spec.quoteCurrency 取出 — 不依赖 positionRecord.currency，
        // 让"同 symbol 同 currency"这个约束显式由 spec 强制，避免数据不一致时静默错算。
        final IntLongHashMap pnlBySymbol = new IntLongHashMap();
        final IntLongHashMap extraMarginBySymbol = new IntLongHashMap();

        riskEngine.getUserProfileService().getUserProfiles().forEach(userProfile -> {
            // 对账采用 OLD 风格的 bucket 拆分：accountBalances 报告"可支配"部分，
            // exchangeLocked 单独作为独立 bucket 参与对账求和。
            // 这样全局守恒等式：accountBalances + extraMargin + exchangeLocked + fees + adjustments + suspends + ifBalances = 0
            userProfile.accounts.forEachKeyValue(currencyBalance::addToValue);
            userProfile.exchangeLocked.forEachKeyValue((c, v) -> {
                currencyBalance.addToValue(c, -v); // accountBalances 减去冻结部分
                exchangeLockedTotal.addToValue(c, v); // 独立 bucket
            });
            userProfile.positions.forEachValue(positionRecord -> {
                final RiskEngine.LastPriceCacheRecord avgPrice =
                    dummyLastPriceCache.getIfAbsentPut(positionRecord.symbol, RiskEngine.LastPriceCacheRecord.dummy);
                long profit = positionRecord.estimatePnl(avgPrice); // product scale
                pnlBySymbol.addToValue(positionRecord.symbol, profit);
                if (positionRecord.extraMargin > 0) {
                    extraMarginBySymbol.addToValue(positionRecord.symbol, positionRecord.extraMargin);
                }
                if (positionRecord.direction == PositionDirection.LONG) {
                    symbolOpenInterestLong.addToValue(positionRecord.symbol, positionRecord.openVolume);
                } else if (positionRecord.direction == PositionDirection.SHORT) {
                    symbolOpenInterestShort.addToValue(positionRecord.symbol, positionRecord.openVolume);
                }
            });
        });

        // 现在每 symbol 维度做一次 scale 转换
        pnlBySymbol.forEachKeyValue((symbolId, accumulated) -> {
            CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(symbolId);
            CoreCurrencySpecification currencySpec =
                currencySpecificationProvider.getCurrencySpecification(spec.quoteCurrency);
            long profit = CoreArithmeticUtils.sizePriceToCurrencyScale(accumulated, spec, currencySpec);
            currencyBalance.addToValue(spec.quoteCurrency, profit);
        });
        extraMarginBySymbol.forEachKeyValue((symbolId, accumulated) -> {
            CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(symbolId);
            CoreCurrencySpecification currencySpec =
                currencySpecificationProvider.getCurrencySpecification(spec.quoteCurrency);
            // pos.extraMargin 由 RiskEngine 以 sizePriceScale (baseScaleK × quoteScaleK) 写入，
            // 见 RiskEngine#MARGIN_ADJUSTMENT 注释 — 这里必须用 sizePriceToCurrencyScale，
            // 不能用 symbolToCurrencyScale（会按 quoteScaleK 量级回算，多放大 baseScaleK 倍）
            long amount = CoreArithmeticUtils.sizePriceToCurrencyScale(accumulated, spec, currencySpec);
            extraMargin.addToValue(spec.quoteCurrency, amount);
        });

        final IntLongHashMap ifBalance = new IntLongHashMap();
        final IntLongHashMap ifOpenInterestLong = new IntLongHashMap();
        final IntLongHashMap ifOpenInterestShort = new IntLongHashMap();
        riskEngine.getLiquidationService().getNotionals().forEachKeyValue((symbol, notional) -> {
            CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(symbol);
            CoreCurrencySpecification currencySpec =
                currencySpecificationProvider.getCurrencySpecification(spec.quoteCurrency);
            long fee = CoreArithmeticUtils.sizePriceToCurrencyScale(notional.available, spec, currencySpec);
            ifBalance.addToValue(spec.quoteCurrency, fee);
        });
        riskEngine.getLiquidationService().getPositions().forEachValue(position -> {
            if (position.direction == PositionDirection.LONG) {
                ifOpenInterestLong.addToValue(position.symbol, position.openVolume);
            } else if (position.direction == PositionDirection.SHORT) {
                ifOpenInterestShort.addToValue(position.symbol, position.openVolume);
            }
            //
            // ifBalances 必须按 "IF 现金 + 持仓 mark-to-market 价值" 一起算，否则对账方程不闭环。
            //
            // 公式（与 SymbolPositionRecord.estimateUnrealizedProfit 同款）：
            // positionValue = openPriceSum + direction * (volume * markPrice - openPriceSum)
            // 展开：
            // long → volume * markPrice // 现在卖能拿多少钱
            // short → 2 * openPriceSum - volume * markPrice // 对称形式（见下文注 2）
            //
            // 为什么这样写：
            //
            // (1) acceptIFPosition 在接管时做的是
            // notional.available -= size * price // 现金口袋 -N*P
            // position.openPriceSum += size * price // 持仓口袋 +N*P
            // 钱没消失，只是从"现金"挪到了"持仓"。若 ifBalances 只算 available，
            // takeover 后 ifBalances 凭空掉 N*P，全局 sum 漂 -N*P。
            //
            // (2) 上半段在算用户持仓时已经把 estimatePnl 加进了 accountBalances（按 mark
            // 做 MtM）。IF 是用户的对手方 —— 用户那边 mark 一动 ΔMtM 就跑了，IF 这边
            // 若只记成本基础静止不动，对手方的 ΔMtM 没人对冲，sum 又会漂。把 IF 也按
            // 同一口径 MtM，两边的 Δ 自然抵消。
            //
            // 例（这正是 testLiquidationReopenAndReliquidate 跑到 mark=400 时遇到的场景）：
            //
            // 初始：LOSER long 5@1000，MAKER short 5@1000，IF 充值 6000 (现金)
            // mark 跌到 600 后 LOSER 被 IF 接管：
            // LOSER realized -2000 → accountBalances 减 2000
            // IF acceptIFPosition: available 6000→3000, openPriceSum 0→3000
            // IF positionValue = 3000 + 1*(5*600 - 3000) = 3000
            // ifBalances = 3000 (cash) + 3000 (position) = 6000 ← takeover 前后不变
            // MAKER unrealized = -1*(5*600 - 5000) = +2000 ← 进 accountBalances
            //
            // 现在把 mark 改到 400：
            // MAKER unrealized: -1*(5*400 - 5000) = +3000 ← ΔaccountBalances = +1000
            // IF positionValue: 3000 + 1*(5*400 - 3000) = 2000 ← ΔifBalances = -1000
            // 净 Δsum = 0 ✓ 对账仍闭环
            //
            // 若仍按旧逻辑（ifBalances 只算 available 或 openPriceSum 静态成本基础）：
            // ΔifBalances = 0，但 MAKER 跑出了 +1000 → sum 漂 +1000 ✗
            //
            // 注 1：dummyLastPriceCache + dummy 兜底跟上半段用户 PnL 共用同一个 markPrice
            // 来源，确保两侧用同一个价。
            // 注 2：short 形式看着别扭，是因为 acceptIFPosition 对 short 也走 available -= spend
            // （没有"卖空收钱"的语义），公式只是镜像该实现。math 上 ΔIF + Δuser 仍抵消。
            //
            CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(position.symbol);
            CoreCurrencySpecification currencySpec =
                currencySpecificationProvider.getCurrencySpecification(spec.quoteCurrency);
            final RiskEngine.LastPriceCacheRecord priceRecord =
                dummyLastPriceCache.getIfAbsentPut(position.symbol, RiskEngine.LastPriceCacheRecord.dummy);
            long unrealized = Math.multiplyExact(
                (long) position.direction.getMultiplier(),
                Math.subtractExact(Math.multiplyExact(position.openVolume, priceRecord.markPrice), position.openPriceSum));
            long positionValue = position.openPriceSum + unrealized;
            long positionValueInCurrency =
                CoreArithmeticUtils.sizePriceToCurrencyScale(positionValue, spec, currencySpec);
            ifBalance.addToValue(spec.quoteCurrency, positionValueInCurrency);
        });

        // loan 平台桶：poolAvailable + interestRevenue + loanLiquidationFees（平台持有现金，参与全局对账）
        final IntLongHashMap loanBalances = new IntLongHashMap();
        final exchange.core2.core.processors.loan.LoanService loanService = riskEngine.getLoanService();
        if (loanService != null) {
            loanService.getLoanPoolAvailable().forEachKeyValue(loanBalances::addToValue);
            loanService.getInterestRevenue().forEachKeyValue(loanBalances::addToValue);
            loanService.getLoanLiquidationFees().forEachKeyValue(loanBalances::addToValue);
        }

        return Optional.of(
            new TotalCurrencyBalanceReportResult(currencyBalance, extraMargin, new IntLongHashMap(riskEngine.getFees()),
                new IntLongHashMap(riskEngine.getAdjustments()), new IntLongHashMap(riskEngine.getSuspends()),
                exchangeLockedTotal, loanBalances, symbolOpenInterestLong, symbolOpenInterestShort, ifBalance,
                ifOpenInterestLong, ifOpenInterestShort, riskEngine.getCurrencySpecificationProvider().getCurrencySpecs(),
                riskEngine.getSymbolSpecificationProvider().getSymbolSpecs()));
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        // do nothing
    }
}
