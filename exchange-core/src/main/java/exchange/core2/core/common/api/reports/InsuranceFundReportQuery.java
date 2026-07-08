package exchange.core2.core.common.api.reports;

import exchange.core2.core.common.CoreCurrencySpecification;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.processors.CurrencySpecificationProvider;
import exchange.core2.core.processors.MatchingEngineRouter;
import exchange.core2.core.processors.RiskEngine;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.liquidation.LiquidationService;
import exchange.core2.core.utils.CoreArithmeticUtils;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.Optional;
import java.util.stream.Stream;

/**
 * 一次查询拿到全部 shard 的 IF 明细。
 *
 * <p>
 * 无参数——每个 shard 独立跑 {@link #process(RiskEngine)} 生成自己的 section； {@link #createResult} 合并所有 sections 到
 * {@link InsuranceFundReportResult#getByShard()}。
 *
 * <p>
 * 数据口径完全对齐 {@link TotalCurrencyBalanceReportQuery}： IFEntry = available（IFNotional.available 的 currency scale）+
 * positionValue（IF 接管仓位 MtM）
 */
@NoArgsConstructor
@EqualsAndHashCode
@ToString
public final class InsuranceFundReportQuery implements ReportQuery<InsuranceFundReportResult> {

    public InsuranceFundReportQuery(BytesIn bytesIn) {
        // 无参数
    }

    @Override
    public int getReportTypeCode() {
        return ReportType.INSURANCE_FUND.getCode();
    }

    @Override
    public InsuranceFundReportResult createResult(Stream<BytesIn> sections) {
        return InsuranceFundReportResult.merge(sections);
    }

    @Override
    public Optional<InsuranceFundReportResult> process(MatchingEngineRouter matchingEngine) {
        // ME 不涉及 IF 数据
        return Optional.empty();
    }

    @Override
    public Optional<InsuranceFundReportResult> process(RiskEngine riskEngine) {
        final SymbolSpecificationProvider specProvider = riskEngine.getSymbolSpecificationProvider();
        final CurrencySpecificationProvider currencySpecProvider = riskEngine.getCurrencySpecificationProvider();
        final LiquidationService liquidationService = riskEngine.getLiquidationService();

        // 复用 TotalCurrencyBalanceReportQuery 同款 markPrice 兜底策略
        final IntObjectHashMap<RiskEngine.LastPriceCacheRecord> dummyLastPriceCache = new IntObjectHashMap<>();
        riskEngine.getLastPriceCache().forEachKeyValue((s, r) -> dummyLastPriceCache.put(s, r.averagingRecord()));

        // symbol → { available, positionValue }（quote-currency scale）
        final IntObjectHashMap<InsuranceFundReportResult.IFEntry> perSymbolIF = new IntObjectHashMap<>();

        // 1) available 部分：IFNotional.available → quote-currency scale
        liquidationService.getNotionals().forEachKeyValue((symbol, notional) -> {
            final CoreSymbolSpecification spec = specProvider.getSymbolSpecification(symbol);
            if (spec == null) {
                return;
            }
            final CoreCurrencySpecification currencySpec =
                currencySpecProvider.getCurrencySpecification(spec.quoteCurrency);
            final long availableInCurrency =
                CoreArithmeticUtils.sizePriceToCurrencyScale(notional.available, spec, currencySpec);
            perSymbolIF.put(symbol, new InsuranceFundReportResult.IFEntry(availableInCurrency, 0L));
        });

        // 2) positionValue 部分：IF 接管仓位 mark-to-market
        // 公式对齐 TotalCurrencyBalanceReportQuery 里的 IF positionValue 算法
        // positionValue = openPriceSum + direction * (openVolume * markPrice - openPriceSum)
        liquidationService.getPositions().forEachValue(position -> {
            final CoreSymbolSpecification spec = specProvider.getSymbolSpecification(position.symbol);
            if (spec == null) {
                return;
            }
            final CoreCurrencySpecification currencySpec =
                currencySpecProvider.getCurrencySpecification(spec.quoteCurrency);

            final RiskEngine.LastPriceCacheRecord priceRecord =
                dummyLastPriceCache.getIfAbsentPut(position.symbol, RiskEngine.LastPriceCacheRecord.dummy);
            final long unrealized = Math.multiplyExact((long)position.direction.getMultiplier(), Math
                .subtractExact(Math.multiplyExact(position.openVolume, priceRecord.markPrice), position.openPriceSum));
            final long positionValue = position.openPriceSum + unrealized;
            final long positionValueInCurrency =
                CoreArithmeticUtils.sizePriceToCurrencyScale(positionValue, spec, currencySpec);

            // 合入 perSymbolIF——same symbol 可能既在 notionals 又在 positions
            final InsuranceFundReportResult.IFEntry existing = perSymbolIF.get(position.symbol);
            final long avail = existing != null ? existing.getAvailable() : 0L;
            final long prevPos = existing != null ? existing.getPositionValue() : 0L;
            perSymbolIF.put(position.symbol,
                new InsuranceFundReportResult.IFEntry(avail, prevPos + positionValueInCurrency));
        });

        return Optional.of(InsuranceFundReportResult.ofShard(riskEngine.getShardId(), perSymbolIF));
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        // 无参数
    }
}
